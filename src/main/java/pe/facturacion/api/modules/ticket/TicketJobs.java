package pe.facturacion.api.modules.ticket;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.document.SaleDocument;
import pe.facturacion.api.modules.document.SaleDocumentRepository;
import pe.facturacion.api.modules.tax.TaxRateService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.modules.ticket.TicketRepository.TicketDoc;
import pe.facturacion.api.modules.webhook.WebhookJob;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.queue.JobQueue;
import pe.facturacion.api.shared.queue.JobQueue.JobContext;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.sunat.SunatSender;
import pe.facturacion.api.sunat.builder.TicketModelBuilder;
import pe.facturacion.api.sunat.xml.TicketXmlBuilder;

/**
 * {@code SendSummaryToSunat}, {@code SendVoidedToSunat}, {@code CheckSummaryTicketStatus} y
 * {@code CheckVoidedTicketStatus}.
 *
 * <p>Correcciones: las boletas de un resumen de anulación siguen {@code anulacion_en_proceso}
 * hasta la respuesta del ticket (API-PRO las marcaba {@code anulado} al recibir el ticket); si el
 * envío de una baja o anulación se rechaza antes del ticket, los documentos vuelven a
 * {@code aceptado}; los errores de transporte se reintentan; el porcentaje de IGV del resumen
 * es el del régimen de la empresa (API-PRO fijaba 18).
 */
@Component
public class TicketJobs {

    static final String SEND = "send-ticket";
    static final String CHECK = "check-ticket";
    static final long[] SEND_BACKOFF = {15, 30, 60, 60, 120, 120, 300, 300, 600, 600};
    static final long[] CHECK_BACKOFF = {15, 30, 30, 60, 60, 60, 120, 120, 120, 300, 300, 300, 600, 600, 600};
    static final List<String> RETRYABLE = List.of("0", "100", "109", "500", "1033", "2800", "HTTP", "NETWORK_ERROR");

    private final JobQueue queue;
    private final TicketRepository tickets;
    private final SaleDocumentRepository documents;
    private final TenantRepository tenants;
    private final SunatSender sender;
    private final TaxRateService taxRates;
    private final FileStorage storage;
    private final WebhookJob webhooks;
    private final VoidedEndpoints voidedEndpoints;
    private final pe.facturacion.api.modules.pdf.PdfService pdf;

    public TicketJobs(JobQueue queue, TicketRepository tickets, SaleDocumentRepository documents, TenantRepository tenants,
            SunatSender sender, TaxRateService taxRates, FileStorage storage, WebhookJob webhooks, @Lazy VoidedEndpoints voidedEndpoints,
            @Lazy pe.facturacion.api.modules.pdf.PdfService pdf) {
        this.queue = queue;
        this.tickets = tickets;
        this.documents = documents;
        this.tenants = tenants;
        this.sender = sender;
        this.taxRates = taxRates;
        this.storage = storage;
        this.webhooks = webhooks;
        this.voidedEndpoints = voidedEndpoints;
        this.pdf = pdf;
    }

    @PostConstruct
    void register() {
        queue.register(new Handler(SEND, 10, SEND_BACKOFF, this::send));
        queue.register(new Handler(CHECK, 15, CHECK_BACKOFF, this::check));
    }

    public void dispatchSend(String table, long id) {
        queue.dispatch(SEND, data(table, id));
    }

    /** Espera antes de la primera consulta del ticket (API-PRO: 15 s). */
    @org.springframework.beans.factory.annotation.Value("${app.sunat.ticket-check-delay:15}")
    long checkDelay = 15;

    public void dispatchCheck(String table, long id) {
        queue.dispatch(CHECK, data(table, id), checkDelay);
    }

    private static Map<String, Object> data(String table, long id) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("table", table);
        d.put("id", id);
        return d;
    }

    @FunctionalInterface
    interface Body {
        void run(JobContext ctx, TicketDoc doc, Tenant tenant, long[] backoff, int tries);
    }

    /** Manejador genérico de la cola {@code sunat}. */
    private final class Handler implements JobQueue.JobHandler {
        private final String type;
        private final int tries;
        private final long[] backoff;
        private final Body body;

        Handler(String type, int tries, long[] backoff, Body body) {
            this.type = type;
            this.tries = tries;
            this.backoff = backoff;
            this.body = body;
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
            String table = String.valueOf(ctx.data().get("table"));
            long id = ((Number) ctx.data().get("id")).longValue();
            TicketDoc doc = tickets.findById(table, id).orElse(null);
            if (doc == null) {
                return;
            }
            Tenant tenant = tenants.findById(doc.tenantId).orElse(null);
            if (tenant == null) {
                return;
            }
            body.run(ctx, doc, tenant, backoff, tries);
        }
    }

    // ------------------------------------------------------------------ envío

    void send(JobContext ctx, TicketDoc doc, Tenant tenant, long[] backoff, int tries) {
        Map<String, Object> data;
        String name;
        String unsigned;
        String folderDate;
        if (doc.isSummary()) {
            data = summaryData(doc, tenant);
            var s = TicketModelBuilder.summary(tenant, data);
            name = s.name();
            unsigned = TicketXmlBuilder.summary(s);
            folderDate = doc.fechaReferencia.toString();
        } else {
            data = new LinkedHashMap<>();
            data.put("correlativo", doc.correlativo);
            data.put("fecha_generacion", doc.fechaReferencia.toString());
            data.put("fecha_comunicacion", doc.fechaEnvio.toString());
            data.put("detalles", doc.detalles);
            var v = isReversion(doc) ? TicketModelBuilder.reversion(tenant, data) : TicketModelBuilder.voided(tenant, data);
            name = v.name();
            unsigned = TicketXmlBuilder.voided(v);
            folderDate = doc.fechaEnvio.toString();
        }
        String identifier = name.substring(tenant.getRuc().length() + 1);
        SunatSender.TicketResult result;
        try {
            result = sender.sendSummary(tenant, endpoint(doc, tenant), name, unsigned);
        } catch (SunatSender.ConfigurationException e) {
            tickets.update(doc, map("sunat_status", "pendiente", "sunat_code", "CERT_ERROR", "sunat_description", truncate(e.getMessage(), 500)));
            return;
        }
        if (result.xml() != null) {
            String xmlPath = isReversion(doc)
                    ? tenant.getRuc() + "/reversions/" + identifier + ".xml"
                    : tenant.getRuc() + "/0000/" + folderDate + "/xml/" + identifier + ".xml";
            storage.put(Disk.PRIVATE, xmlPath, result.xml().getBytes(StandardCharsets.UTF_8));
            Map<String, Object> ch = new LinkedHashMap<>();
            if (doc.isSummary()) {
                ch.put("identifier", identifier);
            }
            ch.put("xml_path", xmlPath);
            tickets.update(doc, ch);
            doc.xmlPath = xmlPath;
        }
        if (result.success()) {
            tickets.update(doc, map("ticket", result.ticket(), "sunat_status", "enviado"));
            doc.ticket = result.ticket();
            if (doc.isSummary()) {
                Map<String, Object> boletaChanges = new LinkedHashMap<>();
                // Envío: 'enviado' con el ticket. Anulación: sigue 'anulacion_en_proceso' hasta el resultado
                if (!"anulacion".equals(doc.tipo)) {
                    boletaChanges.put("sunat_status", "enviado");
                }
                boletaChanges.put("ticket", result.ticket());
                boletaChanges.put("sent_at", Clock.nowUtc().withNano(0));
                updateBoletas(doc, null, boletaChanges);
            }
            dispatchCheck(doc.table, doc.id);
            webhook(tenant, doc, doc.isSummary() ? "summary.sent" : isReversion(doc) ? "reversion.sent" : "voided.sent");
            return;
        }
        String errorCode = result.errorCode() == null ? "" : result.errorCode();
        if (RETRYABLE.contains(errorCode)) {
            if (ctx.attempts() >= tries) {
                markRejected(doc, tenant, "MAX_RETRIES", "Agotados " + tries + " intentos: " + result.errorMessage());
                return;
            }
            ctx.release(ctx.attempts() - 1 < backoff.length ? backoff[ctx.attempts() - 1] : 600);
            return;
        }
        markRejected(doc, tenant, errorCode, result.errorMessage());
    }

    /** Reversión (RR) de retenciones o percepciones. */
    static boolean isReversion(TicketDoc doc) {
        return !doc.isSummary() && doc.identifier != null && doc.identifier.startsWith("RR-");
    }

    /** La reversión se envía y se consulta en el servicio "otros CPE"; el resto, en el de facturas. */
    private String endpoint(TicketDoc doc, Tenant tenant) {
        return isReversion(doc) ? sender.retentionEndpoint(tenant) : sender.endpoint(tenant);
    }

    /** Arreglo de {@code SendSummaryToSunat::handle()}. */
    public Map<String, Object> summaryData(TicketDoc s, Tenant tenant) {
        String estado = "anulacion".equals(s.tipo) ? "3" : "1";
        double porcentaje = taxRates.defaultIgvRate(tenant);
        List<Object> detalles = new ArrayList<>();
        for (SaleDocument b : boletas(s)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("tipo_documento", "03");
            d.put("serie_nro", b.serie + "-" + b.correlativo);
            d.put("estado", estado);
            d.put("cliente_tipo_doc", b.clientTipoDoc);
            d.put("cliente_num_doc", b.clientNumDoc);
            d.put("total", f(b.mtoImpVenta));
            d.put("mto_oper_gravadas", f(b.mtoOperGravadas));
            d.put("mto_oper_exoneradas", f(b.mtoOperExoneradas));
            d.put("mto_oper_inafectas", f(b.mtoOperInafectas));
            d.put("mto_oper_gratuitas", f(b.mtoOperGratuitas));
            d.put("mto_igv", f(b.mtoIgv));
            d.put("porcentaje_igv", porcentaje);
            detalles.add(d);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("correlativo", s.correlativo);
        data.put("fecha_referencia", s.fechaReferencia.toString());
        data.put("fecha_envio", s.fechaEnvio.toString());
        data.put("fecha_resumen", s.fechaReferencia.toString());
        data.put("detalles", detalles);
        return data;
    }

    private void markRejected(TicketDoc doc, Tenant tenant, String code, String message) {
        tickets.update(doc, map("sunat_status", "rechazado", "sunat_code", truncate(code, 20),
                "sunat_description", message == null ? null : truncate(message, 500)));
        revertAnulacion(doc);
        webhook(tenant, doc, doc.isSummary() ? "summary.rejected" : isReversion(doc) ? "reversion.rejected" : "voided.rejected");
    }

    /** Una anulación que no procede deja los documentos como estaban: aceptados. */
    private void revertAnulacion(TicketDoc doc) {
        if (doc.isSummary()) {
            if ("anulacion".equals(doc.tipo)) {
                updateBoletas(doc, "anulacion_en_proceso", Map.of("sunat_status", "aceptado"));
            }
        } else {
            voidedEndpoints.marcar(doc.tenantId, SummaryEndpoints.asList(doc.detalles), "aceptado", "anulacion_en_proceso");
        }
    }

    // ---------------------------------------------------------------- ticket

    void check(JobContext ctx, TicketDoc doc, Tenant tenant, long[] backoff, int tries) {
        if (doc.ticket == null || "aceptado".equals(doc.sunatStatus) || "rechazado".equals(doc.sunatStatus)) {
            return;
        }
        if (!pollOnce(doc, tenant)) {
            ctx.release(ctx.attempts() - 1 < backoff.length ? backoff[ctx.attempts() - 1] : 600);
        }
    }

    /**
     * Consulta el ticket y aplica el resultado. Devuelve {@code false} si SUNAT aún procesa
     * (0, 98, 187) o hubo un fallo de red: hay que volver a consultar.
     */
    boolean pollOnce(TicketDoc doc, Tenant tenant) {
        SunatSender.StatusResult r = sender.getStatus(tenant, endpoint(doc, tenant), doc.ticket);
        if (r.success()) {
            boolean accepted = r.accepted();
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("sunat_status", accepted ? "aceptado" : "rechazado");
            ch.put("sunat_code", r.code());
            ch.put("sunat_description", r.description());
            ch.put("sunat_notes", r.notes());
            if (r.cdrZip() != null && doc.xmlPath != null) {
                String cdrPath = doc.xmlPath.replace("/xml/", "/cdr/").replaceAll("\\.xml$", ".zip")
                        .replace(doc.identifier, "R-" + doc.identifier);
                storage.put(Disk.PRIVATE, cdrPath, r.cdrZip());
                ch.put("cdr_path", cdrPath);
            }
            tickets.update(doc, ch);
            if (doc.isSummary()) {
                applySummaryResult(doc, accepted, r.code(), r.description(), r.notes());
                if (accepted && !"anulacion".equals(doc.tipo)) {
                    // CheckSummaryTicketStatus: PDF de cada boleta aceptada
                    for (SaleDocument b : boletas(doc)) {
                        pdf.storeSale(b, tenant);
                    }
                }
            } else if (accepted) {
                voidedEndpoints.marcar(doc.tenantId, SummaryEndpoints.asList(doc.detalles), "anulado", null);
            } else {
                voidedEndpoints.marcar(doc.tenantId, SummaryEndpoints.asList(doc.detalles), "aceptado", "anulacion_en_proceso");
            }
            webhook(tenant, doc, doc.isSummary() ? "summary.status_updated" : "voided.status_updated");
            return true;
        }
        String errorCode = r.errorCode() == null ? "" : r.errorCode();
        Integer n = errorCode.matches("\\d+") ? Integer.valueOf(errorCode.length() > 9 ? "999999999" : errorCode) : null;
        if (n == null || n == 0 || n == 98 || n == 187) {
            return false;
        }
        tickets.update(doc, map("sunat_status", "rechazado", "sunat_code", errorCode, "sunat_description", r.errorMessage()));
        if (doc.isSummary()) {
            if ("anulacion".equals(doc.tipo)) {
                updateBoletas(doc, "anulacion_en_proceso", Map.of("sunat_status", "aceptado"));
            } else {
                updateBoletasExcept(doc, map("sunat_status", "rechazado", "sunat_code", errorCode, "sunat_description", r.errorMessage()));
            }
        } else {
            voidedEndpoints.marcar(doc.tenantId, SummaryEndpoints.asList(doc.detalles), "aceptado", "anulacion_en_proceso");
        }
        webhook(tenant, doc, doc.isSummary() ? "summary.status_updated" : "voided.status_updated");
        return true;
    }

    /** Estado final de las boletas de un resumen procesado. */
    private void applySummaryResult(TicketDoc s, boolean accepted, String code, String description, List<String> notes) {
        boolean anulacion = "anulacion".equals(s.tipo);
        String status = accepted ? (anulacion ? "anulado" : "aceptado") : (anulacion ? "aceptado" : "rechazado");
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("sunat_status", status);
        if (!(anulacion && !accepted)) {
            ch.put("sunat_code", code);
            ch.put("sunat_description", description);
            ch.put("sunat_notes", notes);
        }
        if ("rechazado".equals(status)) {
            updateBoletasExcept(s, ch);
        } else {
            updateBoletas(s, null, ch);
        }
    }

    private void updateBoletas(TicketDoc s, String onlyStatus, Map<String, Object> changes) {
        List<Long> ids = ids(s);
        if (ids.isEmpty()) {
            return;
        }
        MapSqlParameterSource p = new MapSqlParameterSource("ids", ids);
        String where = "id IN (:ids)";
        if (onlyStatus != null) {
            p.addValue("only", onlyStatus);
            where += " AND sunat_status = :only";
        }
        tickets.updateDocuments("boletas", where, p, changes);
    }

    /** No degrada boletas ya aceptadas o anuladas por otro resumen (rechazo 2282 por duplicado). */
    private void updateBoletasExcept(TicketDoc s, Map<String, Object> changes) {
        List<Long> ids = ids(s);
        if (!ids.isEmpty()) {
            tickets.updateDocuments("boletas", "id IN (:ids) AND sunat_status NOT IN ('aceptado', 'anulado')",
                    new MapSqlParameterSource("ids", ids), changes);
        }
    }

    private List<Long> ids(TicketDoc s) {
        List<Long> ids = new ArrayList<>();
        for (Object o : SummaryEndpoints.asList(s.documentIds)) {
            ids.add(Php.toInt(o));
        }
        return ids;
    }

    private List<SaleDocument> boletas(TicketDoc s) {
        List<Long> ids = ids(s);
        if (ids.isEmpty()) {
            return List.of();
        }
        return documents.query(DocumentKind.BOLETA, "id IN (:ids)", new MapSqlParameterSource("ids", ids), "id", 100000, 0);
    }

    private void webhook(Tenant tenant, TicketDoc doc, String event) {
        if (tenant.getWebhookUrl() != null && !tenant.getWebhookUrl().isEmpty()) {
            webhooks.dispatchTicket(doc.table, doc.id, event);
        }
    }

    private static double f(BigDecimal v) {
        return v == null ? 0 : v.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
