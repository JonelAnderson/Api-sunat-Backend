package pe.facturacion.api.modules.tenant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import pe.facturacion.api.modules.mail.MailService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.security.ApiCredentials;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.crypto.EncryptedString;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.validation.Validator;

/**
 * Recuperación de credenciales por correo ({@code CredentialRecoveryController}), con la
 * corrección BUG-01 de la rama de seguridad: el api_secret devuelto es el mismo que se guarda.
 */
@RestController
@Tag(name = "Registro y credenciales")
public class CredencialesController {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CredencialesController.class);
    private static final String GENERIC ="Si el RUC o email está registrado, recibirás las instrucciones en breve.";

    private final TenantRepository tenants;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final MailService mail;

    public CredencialesController(TenantRepository tenants, JdbcTemplate jdbc, StringRedisTemplate redis, MailService mail) {
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.redis = redis;
        this.mail = mail;
    }

    @Operation(summary = "Solicitar recuperación (ruc o email); responde siempre lo mismo")
    @PostMapping("/api/v1/credenciales/recuperar")
    public ResponseEntity<Object> solicitar(HttpServletRequest request) {
        Input input = Input.of(request);
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("email", "required_without:ruc|nullable|email|max:100");
        rules.put("ruc", "required_without:email|nullable|digits:11");
        Validator.make(input.all(), rules).validate();

        // 3 intentos por IP cada 10 minutos (RateLimiter::hit($key, 600))
        String rlKey = "credential-recovery:" + request.getRemoteAddr();
        String attempts = redis.opsForValue().get(rlKey);
        if (attempts != null && Long.parseLong(attempts) >= 3) {
            Long ttl = redis.getExpire(rlKey);
            return ApiResponse.error("Demasiados intentos. Intenta de nuevo en " + Math.max(0, ttl == null ? 0 : ttl) + " segundos.", 429);
        }
        Long hits = redis.opsForValue().increment(rlKey);
        if (hits != null && hits == 1) {
            redis.expire(rlKey, Duration.ofSeconds(600));
        }

        Long tenantId = null;
        if (input.filled("ruc")) {
            tenantId = first("SELECT id FROM tenants WHERE ruc = ? AND is_active AND deleted_at IS NULL", input.string("ruc"));
        } else if (input.filled("email")) {
            String email = input.string("email").trim().toLowerCase();
            tenantId = first("SELECT id FROM tenants WHERE is_active AND deleted_at IS NULL AND emails @> to_jsonb(ARRAY[?]::text[]) ORDER BY id",
                    email);
            if (tenantId == null) {
                tenantId = first("SELECT t.id FROM tenants t JOIN users u ON u.id = t.user_id "
                        + "WHERE t.is_active AND t.deleted_at IS NULL AND u.email = ? ORDER BY t.id", email);
            }
        }
        if (tenantId == null) {
            return ApiResponse.success(null, GENERIC);
        }
        Tenant tenant = tenants.findById(tenantId).orElseThrow();
        String destino = emailDestino(tenant);
        if (destino == null) {
            // Corrección: API-PRO respondía 422 "No hay un email registrado", lo que revelaba que el RUC
            // existe. Se responde lo mismo que para un RUC inexistente y queda en el log para soporte
            LOG.warn("credenciales.recuperar.sin_email tenant_id={}", tenant.getId());
            return ApiResponse.success(null, GENERIC);
        }
        String token = ApiCredentials.random(64);
        redis.opsForValue().set("credential_recovery:" + token, String.valueOf(tenant.getId()), Duration.ofMinutes(30));
        mail.send(destino, "Recuperación de credenciales API — API SUNAT", recoveryHtml(tenant, token));
        return ApiResponse.success(null, GENERIC);
    }

    @Operation(summary = "Canjear el token del correo por credenciales nuevas")
    @PostMapping("/api/v1/credenciales/recuperar/verificar")
    public ResponseEntity<Object> verificar(HttpServletRequest request) {
        Input input = Input.of(request);
        Validator.make(input.all(), Map.of("token", "required|string|size:64")).validate();
        String key = "credential_recovery:" + input.string("token");
        String tenantId = redis.opsForValue().get(key);
        if (tenantId == null) {
            return ApiResponse.error("Token inválido o expirado.", 422);
        }
        Tenant tenant = tenants.findById(Long.parseLong(tenantId)).orElse(null);
        if (tenant == null || !tenant.isActive()) {
            redis.delete(key);
            return ApiResponse.error("Token inválido o expirado.", 422);
        }
        redis.delete(key);
        String newKey = ApiCredentials.newApiKey();
        String newSecret = ApiCredentials.random(64);
        tenant.setApiKey(newKey);
        tenant.setApiSecret(EncryptedString.ofPlain(newSecret));
        tenants.save(tenant);
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("api_key", newKey);
        datos.put("api_secret", newSecret);
        datos.put("aviso", "Guarda estas credenciales ahora. El api_secret NO se volverá a mostrar.");
        return ApiResponse.success(datos, "Credenciales regeneradas exitosamente.");
    }

    private String emailDestino(Tenant tenant) {
        List<Object> emails = tenant.getEmails();
        if (emails != null && !emails.isEmpty() && emails.get(0) != null) {
            return emails.get(0).toString();
        }
        if (tenant.getUserId() != null) {
            List<String> u = jdbc.queryForList("SELECT email FROM users WHERE id = ?", String.class, tenant.getUserId());
            if (!u.isEmpty() && u.get(0) != null && !u.get(0).isEmpty()) {
                return u.get(0);
            }
        }
        return null;
    }

    private Long first(String sql, Object arg) {
        List<Long> ids = jdbc.queryForList(sql, Long.class, arg);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Contenido de {@code emails/credential-recovery.blade.php}. */
    private static String recoveryHtml(Tenant tenant, String token) {
        String nombre = HtmlUtils.htmlEscape(tenant.getRazonSocial() == null ? "" : tenant.getRazonSocial());
        return "<!doctype html><html><body style=\"font-family:Arial,sans-serif;background:#f8fafc;padding:24px;\">"
                + "<div style=\"max-width:560px;margin:auto;background:#fff;border-radius:12px;padding:32px;\">"
                + "<p style=\"font-size:22px;font-weight:800;color:#0f172a;margin:0 0 4px;\">" + nombre + "</p>"
                + "<p style=\"font-size:13px;color:#94a3b8;font-weight:600;margin:0 0 32px;\">RUC " + HtmlUtils.htmlEscape(tenant.getRuc()) + "</p>"
                + "<p style=\"font-size:10px;font-weight:800;color:#2563eb;letter-spacing:2px;text-transform:uppercase;\">Token de recuperación</p>"
                + "<div style=\"background:#0f172a;border-radius:8px;padding:18px 20px;margin:0 0 12px;\">"
                + "<p style=\"font-family:'Courier New',monospace;font-size:12px;color:#e2e8f0;word-break:break-all;margin:0;\">" + token + "</p></div>"
                + "<p style=\"font-size:12px;color:#15803d;font-weight:700;\">✓ Válido 30 minutos · ⚡ Un solo uso</p>"
                + "<p style=\"font-size:10px;font-weight:800;color:#2563eb;letter-spacing:2px;text-transform:uppercase;\">Endpoint</p>"
                + "<p style=\"font-family:'Courier New',monospace;font-size:12px;color:#334155;\">POST /api/v1/credenciales/recuperar/verificar</p>"
                + "<p style=\"font-size:12px;color:#94a3b8;margin-top:28px;\">Si no solicitaste esto, ignora este correo — tus credenciales actuales no se modificarán.</p>"
                + "</div></body></html>";
    }
}
