package pe.facturacion.api.modules.guide;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.modules.webhook.WebhookJob;
import pe.facturacion.api.shared.queue.JobQueue;
import pe.facturacion.api.shared.queue.JobQueue.JobContext;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.sunat.SunatSender;
import pe.facturacion.api.sunat.builder.DespatchModelBuilder;
import pe.facturacion.api.sunat.gre.GreClient;
import pe.facturacion.api.sunat.model.DespatchModels.Despatch;
import pe.facturacion.api.sunat.xml.DespatchXmlBuilder;

/**
 * {@code SendDispatchGuideToSunat} y {@code CheckTicketStatus} de las guías (API REST GRE).
 *
 * <p>Correcciones: un error de certificado o de credenciales GRE deja la guía {@code pendiente}
 * (no es un rechazo de SUNAT); "en proceso" (98) y los fallos de red nunca la marcan rechazada.
 */
@Component
public class GuideJobs {

    private static final Logger log = LoggerFactory.getLogger(GuideJobs.class);
    static final String SEND = "send-guide";
    static final String CHECK = "check-guide";
    static final long[] SEND_BACKOFF = {15, 30, 60, 60, 120, 120, 300, 300, 600, 600};
    static final long[] CHECK_BACKOFF = {15, 30, 30, 60, 60, 60, 120, 120, 120, 300, 300, 300, 600, 600, 600};
    static final List<String> RETRYABLE = List.of("0", "100", "109", "500", "1033", "HTTP");

    private final JobQueue queue;
    private final GuideRepository guides;
    private final TenantRepository tenants;
    private final SunatSender sender;
    private final GreClient gre;
    private final FileStorage storage;
    private final WebhookJob webhooks;
    private final pe.facturacion.api.modules.pdf.PdfService pdf;

    @Value("${app.sunat.ticket-check-delay:15}")
    long checkDelay = 15;

    public GuideJobs(JobQueue queue, GuideRepository guides, TenantRepository tenants, SunatSender sender, GreClient gre,
            FileStorage storage, WebhookJob webhooks,
            @org.springframework.context.annotation.Lazy pe.facturacion.api.modules.pdf.PdfService pdf) {
        this.queue = queue;
        this.guides = guides;
        this.tenants = tenants;
        this.sender = sender;
        this.gre = gre;
        this.storage = storage;
        this.webhooks = webhooks;
        this.pdf = pdf;
    }

    @PostConstruct
    void register() {
        queue.register(new Handler(SEND, 10, SEND_BACKOFF, true));
        queue.register(new Handler(CHECK, 15, CHECK_BACKOFF, false));
    }

    public void dispatchSend(long id) {
        queue.dispatch(SEND, Map.of("id", id));
    }

    void dispatchCheck(long id) {
        queue.dispatch(CHECK, Map.of("id", id), checkDelay);
    }

    private final class Handler implements JobQueue.JobHandler {
        private final String type;
        private final int tries;
        private final long[] backoff;
        private final boolean send;

        Handler(String type, int tries, long[] backoff, boolean send) {
            this.type = type;
            this.tries = tries;
            this.backoff = backoff;
            this.send = send;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public String queue() {
            return "sunat";
        }

        @Override
        public int tries() {
            return tries;
        }

        @Override
        public long backoff(int attempt) {
            return attempt - 1 < backoff.length ? backoff[attempt - 1] : 600;
        }

        @Override
        public void handle(JobContext ctx) {
            long id = ((Number) ctx.data().get("id")).longValue();
            Map<String, Object> g = guides.findById(id).orElse(null);
            if (g == null) {
                return;
            }
            Tenant tenant = tenants.findById(((Number) g.get("tenant_id")).longValue()).orElse(null);
            if (tenant == null) {
                return;
            }
            if (send) {
                send(ctx, g, tenant, this);
            } else {
                check(ctx, g, tenant, this);
            }
        }
    }

    // ------------------------------------------------------------------ envío

    private void send(JobContext ctx, Map<String, Object> g, Tenant tenant, Handler h) {
        long id = ((Number) g.get("id")).longValue();
        Map<String, Object> data = toArray(g, tenant);
        String signed;
        Despatch despatch;
        try {
            despatch = DespatchModelBuilder.build(tenant, data);
            signed = sender.signXml(tenant, DespatchXmlBuilder.build(despatch, data)).xml();
        } catch (SunatSender.ConfigurationException e) {
            guides.update(id, map("sunat_status", "pendiente", "sunat_code", "CERT_ERROR", "sunat_description", truncate(e.getMessage(), 500)));
            return;
        } catch (RuntimeException e) {
            markRejected(id, tenant, "BUILD_ERROR", e.getMessage());
            return;
        }
        String xmlPath = path(tenant, g, "xml") + "/" + numero(g) + ".xml";
        storage.put(Disk.PRIVATE, xmlPath, signed.getBytes(StandardCharsets.UTF_8));
        guides.update(id, Map.of("xml_path", xmlPath));
        GreClient.SendResult r;
        try {
            r = gre.send(tenant, despatch.name(), signed);
        } catch (SunatSender.ConfigurationException e) {
            guides.update(id, map("sunat_status", "pendiente", "sunat_code", "GRE_CREDENCIALES", "sunat_description", truncate(e.getMessage(), 500)));
            return;
        }
        if (r.success()) {
            guides.update(id, map("ticket", r.ticket(), "sunat_status", "enviado", "sunat_code", null, "sunat_description", null));
            pdf.storeGuide(id, tenant);
            dispatchCheck(id);
            webhook(tenant, id, "document.sent");
            return;
        }
        String code = r.errorCode() == null ? "" : r.errorCode();
        if (RETRYABLE.contains(code)) {
            if (ctx.attempts() >= h.tries) {
                markRejected(id, tenant, "MAX_RETRIES", "Agotados " + h.tries + " intentos: " + r.errorMessage());
                return;
            }
            ctx.release(h.backoff(ctx.attempts()));
            return;
        }
        markRejected(id, tenant, code, r.errorMessage());
    }

    /** Arreglo de {@code SendDispatchGuideToSunat::handle()}: el modelo con las fechas como Y-m-d. */
    public static Map<String, Object> toArray(Map<String, Object> g, Tenant tenant) {
        Map<String, Object> data = new LinkedHashMap<>(g);
        data.put("fecha_emision", ((LocalDateTime) g.get("fecha_emision")).toLocalDate().toString());
        data.put("fecha_traslado", ((LocalDate) g.get("fecha_traslado")).toString());
        data.put("fecha_entrega_transportista", g.get("fecha_entrega_transportista") == null ? null : g.get("fecha_entrega_transportista").toString());
        data.put("peso_total", g.get("peso_total") instanceof BigDecimal b ? b.doubleValue() : g.get("peso_total"));
        data.put("correlativo", ((Number) g.get("correlativo")).longValue());
        Map<String, Object> dest = new LinkedHashMap<>();
        dest.put("tipo_doc", g.get("destinatario_tipo_doc"));
        dest.put("num_doc", g.get("destinatario_num_doc"));
        dest.put("razon_social", g.get("destinatario_razon_social"));
        data.put("destinatario", dest);
        if ("31".equals(g.get("tipo_documento")) && pe.facturacion.api.shared.php.Php.empty(data.get("remitente"))) {
            Map<String, Object> rem = new LinkedHashMap<>();
            rem.put("tipo_doc", "6");
            rem.put("num_doc", tenant.getRuc());
            rem.put("razon_social", tenant.getRazonSocial());
            data.put("remitente", rem);
        }
        return data;
    }

    // ---------------------------------------------------------------- ticket

    private void check(JobContext ctx, Map<String, Object> g, Tenant tenant, Handler h) {
        if (g.get("ticket") == null || "aceptado".equals(g.get("sunat_status")) || "rechazado".equals(g.get("sunat_status"))) {
            return;
        }
        Poll p = poll(g, tenant);
        if (p.pending()) {
            if (ctx.attempts() < h.tries) {
                ctx.release(h.backoff(ctx.attempts()));
                return;
            }
            GreClient.StatusResult r = p.result();
            guides.update(((Number) g.get("id")).longValue(), map("sunat_status", "rechazado", "sunat_code", truncate(r == null ? "" : r.errorCode(), 20),
                    "sunat_description", r == null ? null : truncate(r.errorMessage(), 500)));
            webhook(tenant, ((Number) g.get("id")).longValue(), "document.status_updated");
        }
    }

    /** Resultado de una consulta: {@code pending} = hay que volver a consultar. */
    record Poll(boolean pending, GreClient.StatusResult result) {
    }

    /**
     * Consulta el ticket y aplica el resultado. {@code pending}: SUNAT aún procesa (98, 0, 187),
     * token GRE inválido (401) o fallo de red; hay que volver a consultar.
     */
    Poll poll(Map<String, Object> g, Tenant tenant) {
        long id = ((Number) g.get("id")).longValue();
        GreClient.StatusResult r = gre.status(tenant, (String) g.get("ticket"));
        if (r.success()) {
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("sunat_status", r.accepted() ? "aceptado" : "rechazado");
            ch.put("sunat_code", r.code() == null ? null : truncate(r.code(), 20));
            ch.put("sunat_description", r.description() == null ? null : truncate(r.description(), 500));
            ch.put("sent_at", Clock.nowUtc().withNano(0));
            if (r.cdrZip() != null) {
                String cdrPath = path(tenant, g, "cdr") + "/R-" + numero(g) + ".zip";
                storage.put(Disk.PRIVATE, cdrPath, r.cdrZip());
                ch.put("cdr_path", cdrPath);
            }
            guides.update(id, ch);
            webhook(tenant, id, "document.status_updated");
            return new Poll(false, r);
        }
        if (isPending(r.errorCode())) {
            return new Poll(true, r);
        }
        guides.update(id, map("sunat_status", "rechazado", "sunat_code", truncate(r.errorCode(), 20),
                "sunat_description", r.errorMessage() == null ? null : truncate(r.errorMessage(), 500)));
        webhook(tenant, id, "document.status_updated");
        return new Poll(false, r);
    }

    static boolean isPending(String code) {
        if (code == null || !code.matches("\\d+")) {
            return true;
        }
        int n = code.length() > 9 ? Integer.MAX_VALUE : Integer.parseInt(code);
        return n == 0 || n == 98 || n == 187 || n == 401;
    }

    private void markRejected(long id, Tenant tenant, String code, String message) {
        log.warn("gre.send.rechazado guia_id={} ruc={} codigo={}", id, tenant.getRuc(), code);
        guides.update(id, map("sunat_status", "rechazado", "sunat_code", truncate(code, 20),
                "sunat_description", message == null ? null : truncate(message, 500)));
        webhook(tenant, id, "document.rejected");
    }

    private void webhook(Tenant tenant, long id, String event) {
        if (tenant.getWebhookUrl() != null && !tenant.getWebhookUrl().isEmpty()) {
            webhooks.dispatchRow(GuideRepository.TABLE, id, event);
        }
    }

    /** {@code DocumentStorageService::buildPath()}: {ruc}/{cod_local}/{fecha}/guias/{tipo}. */
    static String path(Tenant tenant, Map<String, Object> g, String type) {
        Object codLocal = g.get("cod_local") == null ? "0000" : g.get("cod_local");
        return tenant.getRuc() + "/" + codLocal + "/" + ((LocalDateTime) g.get("fecha_emision")).toLocalDate() + "/guias/" + type;
    }

    /** {@code numero_completo}: serie + correlativo con 6 dígitos. */
    static String numero(Map<String, Object> g) {
        return g.get("serie") + "-" + String.format("%06d", ((Number) g.get("correlativo")).longValue());
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
