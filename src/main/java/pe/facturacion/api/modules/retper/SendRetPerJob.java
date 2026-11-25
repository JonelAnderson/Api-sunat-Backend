package pe.facturacion.api.modules.retper;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import pe.facturacion.api.sunat.builder.RetPerModelBuilder;
import pe.facturacion.api.sunat.model.RetPerModels.Doc;
import pe.facturacion.api.sunat.xml.RetPerXmlBuilder;

/**
 * {@code SendRetentionToSunat} / {@code SendPerceptionToSunat}: firma y envía por {@code sendBill}
 * al servicio "otros CPE"; 10 intentos con backoff 15 s … 10 min.
 *
 * <p>Correcciones: un error de certificado deja el documento {@code pendiente} (no es un rechazo
 * de SUNAT) y los errores de transporte HTTP/red se reintentan como en los comprobantes.
 */
@Component
public class SendRetPerJob implements JobQueue.JobHandler {

    public static final String TYPE = "send-retper";
    static final long[] BACKOFF = {15, 30, 60, 60, 120, 120, 300, 300, 600, 600};
    static final int TRIES = 10;
    private static final List<String> RETRYABLE = List.of("0", "100", "109", "500", "1033", "2800", "HTTP", "NETWORK_ERROR");

    private final JobQueue queue;
    private final RetPerRepository repo;
    private final TenantRepository tenants;
    private final SunatSender sender;
    private final FileStorage storage;
    private final WebhookJob webhooks;

    public SendRetPerJob(JobQueue queue, RetPerRepository repo, TenantRepository tenants, SunatSender sender, FileStorage storage,
            WebhookJob webhooks) {
        this.queue = queue;
        this.repo = repo;
        this.tenants = tenants;
        this.sender = sender;
        this.storage = storage;
        this.webhooks = webhooks;
    }

    @PostConstruct
    void register() {
        queue.register(this);
    }

    public void dispatch(RetPerKind kind, long id) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tipo", kind.tipoDocumento);
        data.put("id", id);
        queue.dispatch(TYPE, data);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String queue() {
        return "sunat";
    }

    @Override
    public int tries() {
        return TRIES;
    }

    @Override
    public long backoff(int attempt) {
        return attempt - 1 < BACKOFF.length ? BACKOFF[attempt - 1] : 600;
    }

    @Override
    public void handle(JobContext ctx) {
        RetPerKind kind = RetPerKind.fromTipo(String.valueOf(ctx.data().get("tipo")));
        long id = ((Number) ctx.data().get("id")).longValue();
        Map<String, Object> row = repo.findById(kind, id).orElse(null);
        if (row == null) {
            return;
        }
        Tenant tenant = tenants.findById(((Number) row.get("tenant_id")).longValue()).orElse(null);
        if (tenant == null) {
            return;
        }
        SunatSender.Result result;
        try {
            Doc doc = RetPerModelBuilder.build(tenant, toArray(kind, row, repo.items(kind, id)), kind.perception);
            result = sender.sendBill(tenant, sender.retentionEndpoint(tenant), doc.name(),
                    sender.signXml(tenant, RetPerXmlBuilder.build(doc)));
        } catch (SunatSender.ConfigurationException e) {
            repo.update(kind, id, map("sunat_status", "pendiente", "sunat_code", "CERT_ERROR",
                    "sunat_description", truncate(e.getMessage(), 500)));
            return;
        }
        String serieCorr = row.get("serie") + "-" + row.get("correlativo");
        if (result.success()) {
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("sunat_status", result.accepted() ? "aceptado" : "rechazado");
            ch.put("sunat_code", result.code());
            ch.put("sunat_description", result.description());
            ch.put("sunat_notes", result.notes());
            ch.put("hash_cpe", result.hash());
            ch.put("sent_at", Clock.nowUtc().withNano(0));
            if (result.xml() != null) {
                String xmlPath = tenant.getRuc() + "/" + kind.evento + "s/" + serieCorr + ".xml";
                storage.put(Disk.PRIVATE, xmlPath, result.xml().getBytes(StandardCharsets.UTF_8));
                ch.put("xml_path", xmlPath);
            }
            if (result.cdrZip() != null) {
                String cdrPath = tenant.getRuc() + "/" + kind.evento + "s/R-" + serieCorr + ".zip";
                storage.put(Disk.PRIVATE, cdrPath, result.cdrZip());
                ch.put("cdr_path", cdrPath);
            }
            repo.update(kind, id, ch);
            webhook(tenant, kind, id, kind.evento + ".sent");
            return;
        }
        String errorCode = result.errorCode() == null ? "" : result.errorCode();
        if (RETRYABLE.contains(errorCode)) {
            if (ctx.attempts() >= TRIES) {
                markRejected(kind, id, tenant, "MAX_RETRIES", "Agotados " + TRIES + " intentos: " + result.errorMessage());
                return;
            }
            ctx.release(backoff(ctx.attempts()));
            return;
        }
        markRejected(kind, id, tenant, errorCode, result.errorMessage());
    }

    private void markRejected(RetPerKind kind, long id, Tenant tenant, String code, String message) {
        repo.update(kind, id, map("sunat_status", "rechazado", "sunat_code", truncate(code, 20),
                "sunat_description", message == null ? null : truncate(message, 500)));
        webhook(tenant, kind, id, kind.evento + ".rejected");
    }

    private void webhook(Tenant tenant, RetPerKind kind, long id, String event) {
        if (tenant.getWebhookUrl() != null && !tenant.getWebhookUrl().isEmpty()) {
            webhooks.dispatchRow(kind.table, id, event);
        }
    }

    /** {@code retentionToArray()} / {@code perceptionToArray()}. */
    public static Map<String, Object> toArray(RetPerKind kind, Map<String, Object> row, List<Map<String, Object>> items) {
        String t = kind.tercero;
        Map<String, Object> tercero = new LinkedHashMap<>();
        tercero.put("tipo_doc", row.get(t + "_tipo_doc"));
        tercero.put("num_doc", row.get(t + "_num_doc"));
        tercero.put("razon_social", row.get(t + "_razon_social"));
        tercero.put("direccion", row.get(t + "_direccion"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serie", row.get("serie"));
        data.put("correlativo", ((Number) row.get("correlativo")).longValue());
        data.put("cod_local", row.get("cod_local"));
        data.put("fecha_emision", ((LocalDateTime) row.get("fecha_emision")).toLocalDate().toString());
        data.put(t, tercero);
        data.put("regimen", row.get("regimen"));
        data.put("tasa", RetPerRepository.f(row.get("tasa")));
        data.put(kind.totalMonto, RetPerRepository.f(row.get(kind.totalMonto)));
        data.put(kind.totalNeto, RetPerRepository.f(row.get(kind.totalNeto)));
        data.put("observacion", row.get("observacion"));
        List<Object> documentos = new ArrayList<>();
        for (Map<String, Object> it : items) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("tipo_doc", it.get("tipo_doc"));
            d.put("num_doc", it.get("num_doc"));
            d.put("fecha_emision", ((LocalDate) it.get("fecha_emision_doc")).toString());
            d.put("imp_total", RetPerRepository.f(it.get("imp_total")));
            d.put("moneda", it.get("moneda"));
            d.put(kind.pagos, it.get(kind.pagos));
            d.put(kind.fechaItem, ((LocalDate) it.get(kind.fechaItem)).toString());
            d.put(kind.itemMonto, RetPerRepository.f(it.get(kind.itemMonto)));
            d.put(kind.itemNeto, RetPerRepository.f(it.get(kind.itemNeto)));
            d.put("tipo_cambio", it.get("tipo_cambio"));
            documentos.add(d);
        }
        data.put("documentos", documentos);
        return data;
    }

    private static String truncate(String s, int max) {
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
