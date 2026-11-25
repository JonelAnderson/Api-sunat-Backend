package pe.facturacion.api.modules.webhook;

import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.document.SaleDocument;
import pe.facturacion.api.modules.document.SaleDocumentRepository;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.queue.JobQueue;
import pe.facturacion.api.shared.queue.JobQueue.JobContext;
import pe.facturacion.api.shared.time.Clock;

/**
 * {@code NotifyWebhookJob}: POST firmado con HMAC-SHA256 ({@code X-Signature: sha256=...}, clave =
 * {@code api_secret} de la empresa) en la cola {@code webhooks}, 3 intentos con backoff 10/30/90 s.
 *
 * <p>Correcciones de la rama de seguridad (SEC-005): el destino se resuelve en el momento del
 * envío y se descarta si cae en una red privada o reservada; además no se siguen redirecciones.
 */
@Component
public class WebhookJob implements JobQueue.JobHandler {

    public static final String TYPE = "webhook";
    private static final Logger log = LoggerFactory.getLogger(WebhookJob.class);
    private static final long[] BACKOFF = {10, 30, 90};

    private final JobQueue queue;
    private final SaleDocumentRepository documents;
    private final pe.facturacion.api.modules.ticket.TicketRepository tickets;
    private final TenantRepository tenants;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    private final boolean allowPrivate;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public WebhookJob(JobQueue queue, SaleDocumentRepository documents, pe.facturacion.api.modules.ticket.TicketRepository tickets,
            TenantRepository tenants, org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,
            @Value("${app.webhooks.allow-private:false}") boolean allowPrivate) {
        this.queue = queue;
        this.documents = documents;
        this.tickets = tickets;
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.allowPrivate = allowPrivate;
    }

    @PostConstruct
    void register() {
        queue.register(this);
    }

    /** {@code NotifyWebhookJob::dispatch($modelClass, $id, $event)}. */
    public void dispatch(DocumentKind kind, long documentId, String event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", kind.payableType);
        data.put("id", documentId);
        data.put("event", event);
        queue.dispatch(TYPE, data);
    }

    /** {@code NotifyWebhookJob::dispatch(Summary::class | VoidedDocument::class, $id, $event)}. */
    public void dispatchTicket(String table, long id, String event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("table", table);
        data.put("id", id);
        data.put("event", event);
        queue.dispatch(TYPE, data);
    }

    /** Retenciones, percepciones y guías: {@code NotifyWebhookJob::dispatch(Retention::class | Perception::class | DispatchGuide::class, ...)}. */
    public void dispatchRow(String table, long id, String event) {
        if (!ROW_TABLES.contains(table)) {
            throw new IllegalArgumentException("Tabla no admitida: " + table);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("row_table", table);
        data.put("id", id);
        data.put("event", event);
        queue.dispatch(TYPE, data);
    }

    static final java.util.List<String> ROW_TABLES = java.util.List.of("retentions", "perceptions", "dispatch_guides", "purchase_settlements");

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String queue() {
        return "webhooks";
    }

    @Override
    public int tries() {
        return 3;
    }

    @Override
    public long backoff(int attempt) {
        return BACKOFF[Math.min(attempt, BACKOFF.length) - 1];
    }

    @Override
    public void handle(JobContext ctx) throws Exception {
        long id = ((Number) ctx.data().get("id")).longValue();
        String event = String.valueOf(ctx.data().get("event"));
        Long tenantId;
        Map<String, Object> payload;
        if (ctx.data().get("row_table") != null) {
            String table = String.valueOf(ctx.data().get("row_table"));
            if (!ROW_TABLES.contains(table)) {
                return;
            }
            var rows = jdbc.queryForList("SELECT * FROM " + table + " WHERE id = :id", Map.of("id", id));
            if (rows.isEmpty()) {
                return;
            }
            tenantId = ((Number) rows.get(0).get("tenant_id")).longValue();
            payload = rowPayload(table, rows.get(0));
        } else if (ctx.data().get("table") != null) {
            var t = tickets.findById(String.valueOf(ctx.data().get("table")), id).orElse(null);
            if (t == null) {
                return;
            }
            tenantId = t.tenantId;
            payload = ticketPayload(t);
        } else {
            DocumentKind kind = DocumentKind.fromPayableType(String.valueOf(ctx.data().get("kind")));
            SaleDocument d = documents.findById(kind, id).orElse(null);
            if (d == null) {
                return;
            }
            tenantId = d.tenantId;
            payload = payload(d);
        }
        Tenant tenant = tenants.findById(tenantId).orElse(null);
        if (tenant == null || tenant.getWebhookUrl() == null || tenant.getWebhookUrl().isEmpty()) {
            return;
        }
        if (!isSafeDestination(tenant.getWebhookUrl())) {
            log.warn("Webhook omitido: destino no permitido (posible SSRF). tenant_id={} event={}", tenant.getId(), event);
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", event);
        body.put("document", payload);
        body.put("timestamp", Clock.iso8601Utc(Clock.nowUtc()));
        String json = PhpJson.encodeUnescaped(body);
        String secret = tenant.getApiSecret() == null ? "" : tenant.getApiSecret().reveal();
        HttpRequest req = HttpRequest.newBuilder(URI.create(tenant.getWebhookUrl()))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("X-Signature", "sha256=" + hmac(json, secret == null ? "" : secret))
                .header("X-Webhook-Event", event)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        // Como Http::post() de Laravel: una respuesta 4xx/5xx no lanza (no se reintenta);
        // solo los fallos de conexión reintentan.
        http.send(req, HttpResponse.BodyHandlers.discarding());
    }

    /** {@code buildDocumentPayload()}. */
    static Map<String, Object> payload(SaleDocument d) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", d.id);
        p.put("tipo_documento", d.kind.tipoDocumento);
        p.put("serie", d.serie);
        p.put("correlativo", d.correlativo);
        p.put("numero_completo", d.numeroCompleto());
        p.put("fecha_emision", d.fechaEmision.toLocalDate().toString());
        p.put("total", SaleDocumentRepository.cast(d.mtoImpVenta, 2));
        p.put("sunat_status", d.sunatStatus);
        p.put("sunat_code", d.sunatCode);
        p.put("sunat_description", d.sunatDescription);
        p.put("hash_cpe", d.hashCpe);
        return p;
    }

    /**
     * Payload de retención/percepción ({@code buildDocumentPayload}) y de guía ({@code buildDispatchPayload}).
     * Corrección: API-PRO enviaba {@code tipo_documento} y {@code total} nulos en retenciones y
     * percepciones, y la guía de transportista como "09" con el número sin rellenar.
     */
    static Map<String, Object> rowPayload(String table, Map<String, Object> r) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", ((Number) r.get("id")).longValue());
        Object fecha = r.get("fecha_emision");
        String fechaEmision = fecha instanceof java.sql.Timestamp t ? t.toLocalDateTime().toLocalDate().toString() : String.valueOf(fecha);
        if (table.equals("dispatch_guides")) {
            p.put("tipo_documento", r.get("tipo_documento"));
            p.put("serie", r.get("serie"));
            p.put("correlativo", ((Number) r.get("correlativo")).longValue());
            p.put("numero_completo", r.get("serie") + "-" + String.format("%06d", ((Number) r.get("correlativo")).longValue()));
            p.put("fecha_emision", fechaEmision);
            p.put("destinatario", r.get("destinatario_razon_social"));
            p.put("ticket", r.get("ticket"));
        } else if (table.equals("purchase_settlements")) {
            p.put("tipo_documento", "04");
            p.put("serie", r.get("serie"));
            p.put("correlativo", ((Number) r.get("correlativo")).longValue());
            p.put("numero_completo", r.get("serie") + "-" + ((Number) r.get("correlativo")).longValue());
            p.put("fecha_emision", fechaEmision);
            Object total = r.get("mto_imp_neto");
            p.put("total", total instanceof java.math.BigDecimal b ? b.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() : total);
            Object renta = r.get("mto_retencion_renta");
            p.put("retencion_renta", renta instanceof java.math.BigDecimal b ? b.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() : renta);
        } else {
            boolean perception = table.equals("perceptions");
            p.put("tipo_documento", perception ? "40" : "20");
            p.put("serie", r.get("serie"));
            p.put("correlativo", ((Number) r.get("correlativo")).longValue());
            p.put("numero_completo", r.get("serie") + "-" + String.format("%08d", ((Number) r.get("correlativo")).longValue()));
            p.put("fecha_emision", fechaEmision);
            Object total = r.get(perception ? "imp_percibido" : "imp_retenido");
            p.put("total", total instanceof java.math.BigDecimal b ? b.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() : total);
        }
        p.put("sunat_status", r.get("sunat_status"));
        p.put("sunat_code", r.get("sunat_code"));
        p.put("sunat_description", r.get("sunat_description"));
        if (!table.equals("dispatch_guides")) {
            p.put("hash_cpe", r.get("hash_cpe"));
        }
        return p;
    }

    /** {@code buildSummaryPayload()} / {@code buildVoidedPayload()}. */
    static Map<String, Object> ticketPayload(pe.facturacion.api.modules.ticket.TicketRepository.TicketDoc t) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", t.id);
        if (t.isSummary()) {
            p.put("tipo_documento", "resumen");
            p.put("identifier", t.identifier);
            p.put("tipo", t.tipo);
            p.put("fecha_referencia", t.fechaReferencia == null ? null : t.fechaReferencia.toString());
            p.put("fecha_envio", t.fechaEnvio == null ? null : t.fechaEnvio.toString());
        } else {
            p.put("tipo_documento", "comunicacion_baja");
            p.put("identifier", t.identifier);
            p.put("fecha_generacion", t.fechaReferencia == null ? null : t.fechaReferencia.toString());
            p.put("fecha_comunicacion", t.fechaEnvio == null ? null : t.fechaEnvio.toString());
        }
        p.put("total_documentos", t.totalDocumentos);
        p.put("ticket", t.ticket);
        p.put("sunat_status", t.sunatStatus);
        p.put("sunat_code", t.sunatCode);
        p.put("sunat_description", t.sunatDescription);
        return p;
    }

    static String hmac(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    /** {@code WebhookUrlGuard::esDestinoSeguro()}. */
    boolean isSafeDestination(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null || uri.getHost().isEmpty()) {
            return false;
        }
        if (allowPrivate) {
            return true;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost().replace("[", "").replace("]", ""));
        } catch (Exception e) {
            // Un host que no resuelve no alcanza nada interno: la conexión fallará sola
            return true;
        }
        for (InetAddress a : addresses) {
            if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                    || a.isMulticastAddress() || isReserved(a)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isReserved(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0 || first >= 240 || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 0);
        }
        // IPv6 únicas locales (fc00::/7)
        return (b[0] & 0xfe) == 0xfc;
    }
}
