package pe.facturacion.api.modules.tenant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import pe.facturacion.api.config.AppProperties;
import pe.facturacion.api.modules.certificate.CertificateService;
import pe.facturacion.api.modules.certificate.CertificateStore;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.security.ApiCredentials;
import pe.facturacion.api.shared.crypto.EncryptedString;
import pe.facturacion.api.shared.crypto.SecretCipher;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.shared.validation.FileSniffer;
import pe.facturacion.api.shared.validation.Validator;

/**
 * Bridge para el SaaS principal ({@code SaasAuthController}), protegido con {@code X-Bridge-Key}.
 * Correcciones respecto de {@code main}: comparación en tiempo constante, throttle 20/min,
 * {@code provision} devuelve el api_secret real (no el valor cifrado) y {@code tenant} no
 * desactiva las FK (borrado ordenado, compatible con PostgreSQL).
 */
@RestController
@Tag(name = "Bridge SaaS")
public class BridgeController {

    private static final Logger log = LoggerFactory.getLogger(BridgeController.class);
    private static final int TOKEN_TTL_SECONDS = 600;
    static final String RUC_TAKEN_MESSAGE = "El RUC ya está registrado en otra cuenta.";

    private final AppProperties props;
    private final TenantRepository tenants;
    private final TenantService tenantService;
    private final CertificateService certificates;
    private final CertificateStore certificateStore;
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public BridgeController(AppProperties props, TenantRepository tenants, TenantService tenantService,
            CertificateService certificates, CertificateStore certificateStore, JdbcTemplate jdbc) {
        this.props = props;
        this.tenants = tenants;
        this.tenantService = tenantService;
        this.certificates = certificates;
        this.certificateStore = certificateStore;
        this.jdbc = jdbc;
    }

    @Operation(summary = "Token firmado (HMAC) para el inicio de sesión desde el SaaS")
    @PostMapping("/api/bridge/auth")
    public ResponseEntity<Object> auth(HttpServletRequest request) throws Exception {
        if (!authorized(request)) {
            return unauthorized();
        }
        Input input = Input.of(request);
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("email", "required|email");
        rules.put("name", "required|string|max:255");
        Validator.make(input.all(), rules).validate();

        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("email", input.string("email"));
        payloadMap.put("name", input.string("name"));
        payloadMap.put("ts", Clock.now().getEpochSecond());
        payloadMap.put("nonce", ApiCredentials.random(32)); // SEC-016: un solo uso
        String payload = PhpJson.encode(payloadMap);
        String signature = hmacHex(payload, props.security().bridgeSecret());
        String token = Base64.getEncoder().encodeToString((signature + "|" + payload).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("redirect_url", props.url().replaceAll("/+$", "") + "/auth/from-saas?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
        body.put("expires_in", TOKEN_TTL_SECONDS);
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Crear o actualizar la empresa de un usuario del SaaS")
    @PostMapping("/api/bridge/provision")
    @Transactional
    public ResponseEntity<Object> provision(HttpServletRequest request) throws Exception {
        if (!authorized(request)) {
            return unauthorized();
        }
        Input input = Input.of(request);
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("email", "required|email");
        rules.put("name", "nullable|string|max:255");
        rules.put("ruc", "required|string|size:11");
        rules.put("razon_social", "required|string|max:255");
        rules.put("sol_user", "required|string|max:20");
        rules.put("sol_pass", "required|string|max:50");
        rules.put("certificate", "nullable|file|max:4096");
        rules.put("certificate_password", "nullable|string|max:255");
        Validator.make(input.all(), rules).validate();

        // H2: un RUC que ya existe solo lo puede reprovisionar su dueño. No se adopta la empresa de otro cliente (ni la
        // creada por el registro público o la consola, sin dueño): reemplazaría su SOL y su certificado y devolvería sus
        // credenciales. Se decide antes de crear el usuario para no dejar filas por una petición rechazada.
        Tenant tenant = tenants.findByRuc(input.string("ruc")).orElse(null);
        boolean isNew = tenant == null;
        if (!isNew && !ownedBy(tenant, findUserId(input.string("email")))) {
            log.warn("Bridge: provision rechazado, el RUC ya pertenece a otra cuenta (tenant {})", tenant.getId());
            return ResponseEntity.status(409).body(Map.of("error", RUC_TAKEN_MESSAGE));
        }
        long userId = firstOrCreateUser(input.string("email"), input.string("name"));

        if (isNew) {
            tenant = new Tenant();
            tenant.setRuc(input.string("ruc"));
            tenant.setEnvironment(Tenant.ENV_BETA);
            tenant.setPlan("free");
            tenant.setActive(true);
            tenant.setApiKey(ApiCredentials.newApiKey());
            tenant.setApiSecret(EncryptedString.ofPlain(ApiCredentials.newApiSecret()));
        }
        if (tenant.getUserId() == null) {
            tenant.setUserId(userId);
        }
        tenant.setSolUser(EncryptedString.ofPlain(input.string("sol_user")));
        tenant.setSolPass(EncryptedString.ofPlain(input.string("sol_pass")));
        if (tenant.getRazonSocial() == null) {
            tenant.setRazonSocial(input.string("razon_social"));
        }
        tenant = tenants.save(tenant);

        MultipartFile cert = input.file("certificate");
        if (cert != null) {
            try {
                String pem = certificates.convertToPem(cert.getBytes(), FileSniffer.clientExtension(cert).toLowerCase(),
                        input.string("certificate_password"));
                tenant.setCertificatePath(certificateStore.store(tenant, pem));
            } catch (CertificateService.CertificateException e) {
                // API-PRO hacía un redirect (back()->withErrors()) en una API JSON: se responde 422 en el formato del bridge.
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", e.getMessage());
                return ResponseEntity.status(422).body(err);
            }
        }
        if (input.filled("certificate_password")) {
            tenant.setCertificatePassword(EncryptedString.ofPlain(input.string("certificate_password")));
        }
        tenant = tenants.save(tenant);

        tenantService.createDefaultSeries(tenant.getId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("api_key", tenant.getApiKey());
        body.put("api_secret", reveal(tenant.getApiSecret()));
        body.put("ruc", tenant.getRuc());
        body.put("razon_social", tenant.getRazonSocial() == null ? "" : tenant.getRazonSocial());
        body.put("environment", tenant.getEnvironment());
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Eliminar la empresa por RUC (borrado físico)")
    @DeleteMapping("/api/bridge/tenant")
    public ResponseEntity<Object> deleteTenant(HttpServletRequest request) {
        if (!authorized(request)) {
            return unauthorized();
        }
        Input input = Input.of(request);
        Validator.make(input.all(), Map.of("ruc", "required|string|size:11")).validate();
        try {
            tenants.findByRuc(input.string("ruc")).ifPresent(tenantService::forceDelete);
        } catch (RuntimeException e) {
            log.error("Error al eliminar tenant por bridge", e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "Error al eliminar tenant.");
            return ResponseEntity.status(500).body(err);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /**
     * H2: la empresa es del usuario del bridge solo si ya tiene dueño y es ese usuario. Sin dueño ({@code user_id} nulo:
     * registro público, consola) o con otro dueño no lo es. Pura, para probarla sin base de datos.
     */
    static boolean ownedBy(Tenant tenant, Optional<Long> userId) {
        return tenant.getUserId() != null && userId.isPresent() && tenant.getUserId().equals(userId.get());
    }

    private Optional<Long> findUserId(String email) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM users WHERE email = ?", Long.class, email);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    private long firstOrCreateUser(String email, String name) {
        Optional<Long> existing = findUserId(email);
        if (existing.isPresent()) {
            return existing.get();
        }
        Timestamp now = Timestamp.valueOf(Clock.nowUtc());
        return jdbc.queryForObject("INSERT INTO users (name, email, password, email_verified_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id", Long.class,
                name == null ? "Usuario" : name, email, bcrypt.encode(ApiCredentials.random(32)), now, now, now);
    }

    private boolean authorized(HttpServletRequest request) {
        String key = props.security().bridgeSecret();
        String sent = request.getHeader("X-Bridge-Key");
        return key != null && !key.isEmpty() && SecretCipher.constantTimeEquals(key, sent == null ? "" : sent);
    }

    private static ResponseEntity<Object> unauthorized() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "No autorizado");
        return ResponseEntity.status(403).body(body);
    }

    private static String reveal(EncryptedString s) {
        try {
            return s == null ? null : s.reveal();
        } catch (SecretCipher.DecryptException e) {
            return null;
        }
    }

    private static String hmacHex(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
