package pe.facturacion.api.modules.document;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import pe.facturacion.api.config.AppProperties;
import pe.facturacion.api.modules.tax.TaxRateService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.modules.webhook.WebhookJob;
import pe.facturacion.api.shared.queue.JobQueue;
import pe.facturacion.api.shared.queue.JobQueue.JobContext;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.sunat.SunatCircuitBreaker;
import pe.facturacion.api.sunat.SunatSender;
import pe.facturacion.api.sunat.builder.InvoiceModelBuilder;

/**
 * {@code SendDocumentToSunat} para facturas y boletas: cola {@code sunat}, 20 intentos, backoff
 * 15 s … 1 h (~15 h de caída de SUNAT), timeout 90 s, límite por empresa, un solo envío a la vez
 * por documento y circuit breaker por endpoint.
 *
 * <p>Correcciones (DIFERENCIAS): el circuit breaker cuenta los fallos de transporte; un error de
 * certificado deja el comprobante {@code pendiente} (no es un rechazo de SUNAT); la espera por
 * el límite de la empresa o por otro envío del mismo documento no consume intentos.
 */
@Component
public class SendDocumentJob implements JobQueue.JobHandler {

    public static final String TYPE = "send-document";
    private static final Logger log = LoggerFactory.getLogger(SendDocumentJob.class);
    static final long[] BACKOFF = {15, 30, 60, 120, 300, 600, 1200, 1800,
        3600, 3600, 3600, 3600, 3600, 3600, 3600, 3600, 3600, 3600, 3600, 3600};
    static final int TRIES = 20;
    private static final List<String> RETRYABLE = List.of("0", "100", "109", "500", "1033", "2800", "HTTP", "NETWORK_ERROR");

    private final JobQueue queue;
    private final SaleDocumentRepository documents;
    private final TenantRepository tenants;
    private final DocumentStorage storage;
    private final SunatSender sender;
    private final SunatCircuitBreaker breaker;
    private final TaxRateService taxRates;
    private final WebhookJob webhooks;
    private final StringRedisTemplate redis;
    private final AppProperties props;
    private final pe.facturacion.api.modules.pdf.PdfService pdf;

    public SendDocumentJob(JobQueue queue, SaleDocumentRepository documents, TenantRepository tenants, DocumentStorage storage,
            SunatSender sender, SunatCircuitBreaker breaker, TaxRateService taxRates, WebhookJob webhooks,
            StringRedisTemplate redis, AppProperties props, @org.springframework.context.annotation.Lazy pe.facturacion.api.modules.pdf.PdfService pdf) {
        this.queue = queue;
        this.documents = documents;
        this.tenants = tenants;
        this.storage = storage;
        this.sender = sender;
        this.breaker = breaker;
        this.taxRates = taxRates;
        this.webhooks = webhooks;
        this.redis = redis;
        this.props = props;
        this.pdf = pdf;
    }

    @PostConstruct
    void register() {
        queue.register(this);
    }

    /** {@code SendDocumentToSunat::dispatch($modelClass, $id)}. */
    public void dispatch(DocumentKind kind, long id) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", kind.payableType);
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
        return attempt - 1 < BACKOFF.length ? BACKOFF[attempt - 1] : 3600;
    }

    @Override
    public void handle(JobContext ctx) {
        DocumentKind kind = DocumentKind.fromPayableType(String.valueOf(ctx.data().get("kind")));
        long id = ((Number) ctx.data().get("id")).longValue();
        SaleDocument d = documents.findById(kind, id).orElse(null);
        if (d == null) {
            return;
        }
        Tenant tenant = tenants.findById(d.tenantId).orElse(null);
        if (tenant == null) {
            return;
        }

        // RateLimited('sunat-tenant'): cada empresa drena a su ritmo
        int perMinute = props.sunat().perTenantPerMinute() <= 0 ? 120 : props.sunat().perTenantPerMinute();
        String rlKey = "sunat-tenant:" + tenant.getId() + ":" + (System.currentTimeMillis() / 60000);
        Long used = redis.opsForValue().increment(rlKey);
        if (used != null && used == 1) {
            redis.expire(rlKey, Duration.ofSeconds(70));
        }
        if (used != null && used > perMinute) {
            ctx.releaseWithoutAttempt(60 - (System.currentTimeMillis() / 1000) % 60 + 1);
            return;
        }

        // WithoutOverlapping("sunat-send:{class}:{id}")->expireAfter(180)
        String lockKey = "sunat-send:" + kind.payableType + ":" + id;
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(180));
        if (!Boolean.TRUE.equals(locked)) {
            ctx.releaseWithoutAttempt(5);
            return;
        }
        try {
            send(ctx, kind, d, tenant);
        } finally {
            redis.delete(lockKey);
        }
    }

    private void send(JobContext ctx, DocumentKind kind, SaleDocument d, Tenant tenant) {
        // Un comprobante aceptado o anulado no se reenvía
        if ("aceptado".equals(d.sunatStatus) || "anulado".equals(d.sunatStatus)) {
            return;
        }
        if ("pendiente".equals(d.sunatStatus) && "SUNAT_TIMEOUT".equals(d.sunatCode) && recoverFromCdr(d, tenant)) {
            return;
        }

        String endpoint = sender.endpoint(tenant);
        if (!breaker.isAvailable(endpoint)) {
            // SUNAT caído: se libera con jitter para no volver todos a la vez
            ctx.release(300 + ThreadLocalRandom.current().nextInt(0, 121));
            return;
        }

        documents.loadItems(d);
        SunatSender.Result result;
        try {
            Map<String, Object> data = DocumentArrayMapper.toArray(d);
            pe.facturacion.api.sunat.model.SaleModels.Invoice invoice = kind.isNote()
                    ? new pe.facturacion.api.sunat.builder.NoteModelBuilder(tenant, taxRates).build(data)
                    : new InvoiceModelBuilder(tenant, taxRates).build(data);
            @SuppressWarnings("unchecked")
            Map<String, Object> contrato = d.contratoColaboracion instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            result = sender.send(tenant, invoice, contrato);
        } catch (SunatSender.ConfigurationException e) {
            documents.update(d, map("sunat_status", "pendiente", "sunat_code", "CERT_ERROR",
                    "sunat_description", truncate(e.getMessage(), 500)));
            return;
        }

        if (isTransport(result)) {
            breaker.recordFailure(endpoint);
        } else {
            breaker.recordSuccess(endpoint);
        }

        if (result.success()) {
            markAccepted(kind, d, tenant, result, result.code(), result.description(), result.notes());
            return;
        }

        String errorCode = result.errorCode() == null ? "" : result.errorCode();
        if (RETRYABLE.contains(errorCode)) {
            handleRetryableError(ctx, kind, d, tenant);
            return;
        }
        if (errorCode.matches("\\d+") && Long.parseLong(errorCode.length() > 18 ? "9999" : errorCode) >= 4000) {
            markAccepted(kind, d, tenant, result, truncate(errorCode, 20), truncate(result.errorMessage(), 500), null);
            return;
        }
        markAsRejected(kind, d, tenant, errorCode, result.errorMessage(), result.xml());
    }

    private void markAccepted(DocumentKind kind, SaleDocument d, Tenant tenant, SunatSender.Result r, String code,
            String description, List<String> notes) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("sunat_status", "aceptado");
        changes.put("sunat_code", code);
        changes.put("sunat_description", description);
        if (notes != null) {
            changes.put("sunat_notes", notes);
        }
        changes.put("hash_cpe", r.hash());
        changes.put("sent_at", Clock.nowUtc().withNano(0));
        // Antes de marcar la NC: quien la vea aceptada ya encuentra el comprobante sin deuda
        documents.anularCobroPorNotaCredito(d);
        documents.update(d, changes);
        apply(d, changes);
        if (r.xml() != null && !r.xml().isEmpty()) {
            storage.storeXml(d, tenant, r.xml());
        }
        if (r.cdrZip() != null && r.cdrZip().length > 0) {
            storage.storeCdr(d, tenant, r.cdrZip());
        }
        // generateAndStore(): PDF A4 guardado al aceptarse
        pdf.storeSale(d, tenant);
        if (hasWebhook(tenant)) {
            webhooks.dispatch(kind, d.id, "document.sent");
        }
    }

    private void markAsRejected(DocumentKind kind, SaleDocument d, Tenant tenant, String code, String message, String xml) {
        documents.update(d, map("sunat_status", "rechazado", "sunat_code", truncate(code, 20),
                "sunat_description", message == null ? null : truncate(message, 500)));
        if (xml != null && !xml.isEmpty()) {
            storage.storeXml(d, tenant, xml);
        }
        if (hasWebhook(tenant)) {
            webhooks.dispatch(kind, d.id, "document.rejected");
        }
    }

    private void handleRetryableError(JobContext ctx, DocumentKind kind, SaleDocument d, Tenant tenant) {
        if (ctx.attempts() >= TRIES) {
            // Antes de rendirse: quizá SUNAT sí lo procesó
            if (recoverFromCdr(d, tenant)) {
                return;
            }
            documents.update(d, map("sunat_status", "pendiente", "sunat_code", "SUNAT_TIMEOUT",
                    "sunat_description", "SUNAT no disponible después de " + TRIES + " intentos. Use POST /reenviar cuando SUNAT esté disponible."));
            if (hasWebhook(tenant)) {
                webhooks.dispatch(kind, d.id, "document.timeout_sunat");
            }
            return;
        }
        ctx.release(backoff(ctx.attempts()));
    }

    /**
     * {@code recoverFromCdr()} con {@code getStatusCdr} (API-PRO llamaba a {@code getStatus} con el
     * nombre del archivo como ticket y nunca recuperaba nada).
     */
    boolean recoverFromCdr(SaleDocument d, Tenant tenant) {
        SunatSender.CdrStatus res;
        try {
            res = sender.consultCdr(tenant, d.kind.tipoDocumento, d.serie, d.correlativo);
        } catch (RuntimeException e) {
            return false;
        }
        if (!res.success()) {
            return false;
        }
        String code = res.code() == null ? "" : res.code();
        boolean digits = code.matches("\\d+");
        boolean aceptado = res.accepted() || code.equals("0") || (digits && Long.parseLong(code) >= 4000);
        if (aceptado) {
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("sunat_status", "aceptado");
            changes.put("sunat_code", code.isEmpty() ? "0" : truncate(code, 20));
            changes.put("sunat_description", res.description() == null ? null : truncate(res.description(), 500));
            changes.put("sunat_notes", res.notes());
            changes.put("sent_at", Clock.nowUtc().withNano(0));
            documents.anularCobroPorNotaCredito(d);
            documents.update(d, changes);
            if (res.cdrZip() != null && res.cdrZip().length > 0) {
                storage.storeCdr(d, tenant, res.cdrZip());
            }
            pdf.storeSale(d, tenant);
            return true;
        }
        if (digits && Long.parseLong(code) >= 1000) {
            documents.update(d, map("sunat_status", "rechazado", "sunat_code", truncate(code, 20),
                    "sunat_description", res.description() == null ? null : truncate(res.description(), 500)));
            return true;
        }
        return false;
    }

    private static boolean isTransport(SunatSender.Result r) {
        return !r.success() && r.errorCode() != null && List.of("HTTP", "NETWORK_ERROR").contains(r.errorCode());
    }

    private static boolean hasWebhook(Tenant t) {
        return t.getWebhookUrl() != null && !t.getWebhookUrl().isEmpty();
    }

    private static void apply(SaleDocument d, Map<String, Object> changes) {
        d.sunatStatus = (String) changes.getOrDefault("sunat_status", d.sunatStatus);
        d.sunatCode = (String) changes.getOrDefault("sunat_code", d.sunatCode);
        d.sunatDescription = (String) changes.getOrDefault("sunat_description", d.sunatDescription);
        d.hashCpe = (String) changes.getOrDefault("hash_cpe", d.hashCpe);
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Override
    public void failed(JobContext ctx, Throwable e) {
        log.error("sunat.send.job_failed kind={} id={} error={}", ctx.data().get("kind"), ctx.data().get("id"), e.toString());
    }
}
