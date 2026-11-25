package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import pe.facturacion.api.modules.admin.security.Totp;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.support.IntegrationTest;

/**
 * Panel de administración ({@code /api/admin/**}) contra PostgreSQL y Redis reales: login con MFA obligatorio,
 * bloqueo, roles, suspensión de empresas, refresh rotativo, logout y que no salgan secretos.
 *
 * <p>El reloj de la aplicación ({@link Clock}) avanza 30 s antes de cada código TOTP: un mismo código no se puede usar
 * dos veces (protección contra reutilización).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    // Clave de prueba sin valor real (≥ 32 bytes)
    "app.admin.jwt-secret=clave-de-prueba-del-panel-admin-0123456789abcdef",
    "app.admin.allowed-origins=https://localhost:4220",
    "app.queue.worker-enabled=false"
})
class AdminPanelIT extends IntegrationTest {

    /** Contraseña de prueba que cumple la política. */
    static final String PASSWORD = "Prueba#2026";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    TenantRepository tenants;

    final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    Instant clock;

    @BeforeAll
    void fixClock() {
        clock = Instant.now();
        Clock.useFixed(clock);
    }

    @AfterAll
    void releaseClock() {
        Clock.useSystem();
    }

    @BeforeEach
    void resetRateLimit() {
        redis.delete("throttle:admin-auth:127.0.0.1");
        redis.delete("throttle:admin-auth:0:0:0:0:0:0:0:1");
    }

    // ------------------------------------------------------------------ login y MFA

    @Test
    void primer_login_configura_el_mfa_y_luego_pide_el_codigo() throws Exception {
        String email = "superadmin.flujo@example.com";
        createAdmin("Ana Pérez Flujo", email, "superadmin");

        Map<String, Object> login = data(post("/api/admin/auth/login", "{\"userName\":\"" + email.toUpperCase() + "\",\"password\":\"" + PASSWORD + "\"}", null));
        assertThat(login).containsEntry("mfaSetupRequired", true).containsKey("mfaTicket").doesNotContainKey("token");
        String ticket = (String) login.get("mfaTicket");

        // Con el ticket de configuración no se entra directo
        Map<String, Object> wrongStage = body(post("/api/admin/auth/login/mfa", "{\"mfaTicket\":\"" + ticket + "\",\"code\":\"123456\"}", null));
        assertThat(wrongStage).containsEntry("succeeded", false);

        Map<String, Object> setup = data(post("/api/admin/auth/login/mfa/setup", "{\"mfaTicket\":\"" + ticket + "\"}", null));
        String secret = ((String) setup.get("sharedKey")).replace(" ", "");
        assertThat((String) setup.get("authenticatorUri"))
                .startsWith("otpauth://totp/API%20SUNAT:superadmin.flujo%40example.com?secret=" + secret).endsWith("&issuer=API%20SUNAT");

        Map<String, Object> confirm = data(post("/api/admin/auth/login/mfa/setup/confirm",
                "{\"mfaTicket\":\"" + ticket + "\",\"code\":\"" + nextCode(secret) + "\"}", null));
        assertThat(confirm).containsKeys("token", "refreshToken", "refreshTokenExpiryTime");
        assertThat((List<?>) confirm.get("recoveryCodes")).hasSize(10);
        assertThat((String) confirm.get("refreshTokenExpiryTime")).endsWith("Z");
        String token = (String) confirm.get("token");

        // La clave quedó cifrada y los códigos como hash
        Map<String, Object> row = jdbc.queryForMap("SELECT two_factor_secret, two_factor_recovery_codes FROM users WHERE email = ?", email);
        assertThat((String) row.get("two_factor_secret")).startsWith("v1:").doesNotContain(secret);
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) confirm.get("recoveryCodes");
        assertThat((String) row.get("two_factor_recovery_codes")).doesNotContain(codes.get(0));

        Map<String, Object> me = data(get("/api/admin/auth/me", bearer(token)));
        assertThat(me).containsEntry("userName", email).containsEntry("firstName", "Ana").containsEntry("lastName", "Pérez Flujo")
                .containsEntry("role", "superadmin").containsEntry("profilePictureUrl", null);
        assertThat(dataList(get("/api/admin/auth/permissions", bearer(token)))).contains("Admin.Users.Manage", "Admin.Audit.View");
        assertThat(dataList(get("/api/admin/auth/menu", bearer(token)))).hasSize(6);
        assertThat(data(get("/api/admin/auth/mfa/status", bearer(token)))).containsEntry("enabled", true).containsEntry("recoveryCodesLeft", 10);

        // Segundo login: pide el código (no hay "recordar dispositivo")
        Map<String, Object> again = data(post("/api/admin/auth/login", credentials(email), null));
        assertThat(again).containsEntry("mfaRequired", true);
        Map<String, Object> mfa = data(post("/api/admin/auth/login/mfa",
                "{\"mfaTicket\":\"" + again.get("mfaTicket") + "\",\"code\":\"" + nextCode(secret) + "\"}", null));
        assertThat(mfa).containsKeys("token", "refreshToken", "refreshTokenExpiryTime").containsEntry("recoveryCodesLeft", 10);

        // Con un código de recuperación (un solo uso)
        Map<String, Object> third = data(post("/api/admin/auth/login", credentials(email), null));
        Map<String, Object> withRecovery = data(post("/api/admin/auth/login/mfa",
                "{\"mfaTicket\":\"" + third.get("mfaTicket") + "\",\"recoveryCode\":\"" + codes.get(0) + "\"}", null));
        assertThat(withRecovery).containsEntry("recoveryCodesLeft", 9);
        Map<String, Object> fourth = data(post("/api/admin/auth/login", credentials(email), null));
        assertThat(body(post("/api/admin/auth/login/mfa",
                "{\"mfaTicket\":\"" + fourth.get("mfaTicket") + "\",\"recoveryCode\":\"" + codes.get(0) + "\"}", null)))
                .containsEntry("succeeded", false);

        // Auditoría del login con actor admin
        Long logins = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'admin.login' AND actor_tipo = 'admin' "
                + "AND actor_etiqueta = ? AND user_id IS NOT NULL", Long.class, email);
        assertThat(logins).isGreaterThanOrEqualTo(3);
    }

    @Test
    void el_mismo_codigo_totp_no_sirve_dos_veces() throws Exception {
        String email = "superadmin.replay@example.com";
        createAdmin("Replay", email, "superadmin");
        String secret = fullLogin(email).secret();
        String code = nextCode(secret);
        Map<String, Object> a = data(post("/api/admin/auth/login", credentials(email), null));
        assertThat(body(post("/api/admin/auth/login/mfa", "{\"mfaTicket\":\"" + a.get("mfaTicket") + "\",\"code\":\"" + code + "\"}", null)))
                .containsEntry("succeeded", true);
        Map<String, Object> b = data(post("/api/admin/auth/login", credentials(email), null));
        assertThat(body(post("/api/admin/auth/login/mfa", "{\"mfaTicket\":\"" + b.get("mfaTicket") + "\",\"code\":\"" + code + "\"}", null)))
                .containsEntry("succeeded", false);
    }

    @Test
    void cinco_fallos_bloquean_la_cuenta_15_minutos() throws Exception {
        String email = "operador.bloqueo@example.com";
        createAdmin("Bloqueo", email, "operador");
        String generic = null;
        for (int i = 0; i < 5; i++) {
            Map<String, Object> r = body(post("/api/admin/auth/login", "{\"userName\":\"" + email + "\",\"password\":\"Incorrecta#1\"}", null));
            assertThat(r).containsEntry("succeeded", false);
            generic = firstMessage(r);
        }
        // Un correo que no existe recibe el mismo mensaje
        Map<String, Object> unknown = body(post("/api/admin/auth/login", "{\"userName\":\"nadie@example.com\",\"password\":\"Incorrecta#1\"}", null));
        assertThat(firstMessage(unknown)).isEqualTo(generic);

        // Bloqueada: ni con la contraseña correcta
        Map<String, Object> locked = body(post("/api/admin/auth/login", credentials(email), null));
        assertThat(locked).containsEntry("succeeded", false);
        assertThat(firstMessage(locked)).contains("bloqueada");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'admin.cuenta.bloqueada' AND actor_etiqueta = ?",
                Long.class, email)).isEqualTo(1L);

        // A los 15 minutos vuelve a entrar
        advance(Duration.ofMinutes(16));
        assertThat(data(post("/api/admin/auth/login", credentials(email), null))).containsKey("mfaTicket");
    }

    @Test
    void limite_de_10_intentos_por_minuto_por_ip() throws Exception {
        int last = 0;
        for (int i = 0; i < 11; i++) {
            last = post("/api/admin/auth/login", "{\"userName\":\"limite" + i + "@example.com\",\"password\":\"Incorrecta#1\"}", null).statusCode();
        }
        assertThat(last).isEqualTo(429);
    }

    // ------------------------------------------------------------------ roles

    @Test
    void lectura_no_puede_escribir() throws Exception {
        String email = "lectura.rol@example.com";
        createAdmin("Lectura", email, "lectura");
        String token = fullLogin(email).token();
        long tenantId = demoTenantId();
        assertThat(get("/api/admin/tenants", bearer(token)).statusCode()).isEqualTo(200);
        assertThat(get("/api/admin/dashboard", bearer(token)).statusCode()).isEqualTo(200);
        HttpResponse<String> put = send("PUT", "/api/admin/tenants/" + tenantId + "/estado", "{\"activo\":false,\"motivo\":\"x\"}", bearer(token));
        assertThat(put.statusCode()).isEqualTo(403);
        assertThat(body(put)).containsEntry("succeeded", false);
        assertThat(post("/api/admin/jobs/retry-all", "{}", bearer(token)).statusCode()).isEqualTo(403);
        assertThat(send("PUT", "/api/admin/tenants/" + tenantId + "/plan", "{\"plan\":\"pro\",\"maxDocumentsMonth\":10}", bearer(token))
                .statusCode()).isEqualTo(403);
        assertThat(dataList(get("/api/admin/auth/permissions", bearer(token)))).doesNotContain("Admin.Tenants.Manage", "Admin.Jobs.Retry");
    }

    @Test
    void administradores_y_auditoria_solo_superadmin() throws Exception {
        createAdmin("Operador Uno", "operador.uno@example.com", "operador");
        createAdmin("Super Uno", "super.uno@example.com", "superadmin");
        String operador = fullLogin("operador.uno@example.com").token();
        String superadmin = fullLogin("super.uno@example.com").token();

        assertThat(get("/api/admin/users", bearer(operador)).statusCode()).isEqualTo(403);
        assertThat(get("/api/admin/audit", bearer(operador)).statusCode()).isEqualTo(403);
        assertThat(post("/api/admin/users", "{\"name\":\"X\",\"email\":\"x@example.com\",\"role\":\"lectura\",\"password\":\"" + PASSWORD + "\"}",
                bearer(operador)).statusCode()).isEqualTo(403);

        HttpResponse<String> list = get("/api/admin/users?records=0&numPage=-1&textFilter=uno", bearer(superadmin));
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(body(list)).containsKeys("succeeded", "messages", "data", "totalRecords", "errors");
        assertThat(list.body()).doesNotContain("two_factor").doesNotContain("\"password\"").doesNotContain("$2a$");

        // Crear: la contraseña cumple la política
        Map<String, Object> weak = body(post("/api/admin/users", "{\"name\":\"Nuevo\",\"email\":\"nuevo@example.com\",\"role\":\"lectura\","
                + "\"password\":\"corta\"}", bearer(superadmin)));
        assertThat(weak).containsEntry("succeeded", false);
        assertThat((List<?>) weak.get("errors")).isNotEmpty();
        Map<String, Object> created = data(post("/api/admin/users", "{\"name\":\"Nuevo\",\"email\":\"Nuevo@Example.com\",\"role\":\"lectura\","
                + "\"password\":\"" + PASSWORD + "\"}", bearer(superadmin)));
        assertThat(created).containsEntry("email", "nuevo@example.com").containsEntry("roleName", "Solo lectura").containsEntry("mfaEnabled", false);

        // No puede quitarse el rol ni desactivarse a sí mismo
        long selfId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'super.uno@example.com'", Long.class);
        assertThat(body(send("PUT", "/api/admin/users/" + selfId, "{\"name\":\"Super Uno\",\"email\":\"super.uno@example.com\",\"role\":\"operador\"}",
                bearer(superadmin)))).containsEntry("succeeded", false);
        assertThat(body(send("PUT", "/api/admin/users/" + selfId + "/toggle-status", null, bearer(superadmin)))).containsEntry("succeeded", false);

        Map<String, Object> audit = body(get("/api/admin/audit?actorTipo=admin&textFilter=admin.usuario.creado", bearer(superadmin)));
        assertThat(audit).containsEntry("succeeded", true);
        assertThat(((Number) audit.get("totalRecords")).longValue()).isGreaterThanOrEqualTo(1);

        // Desactivar a otro revoca sus sesiones al instante
        long opId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'operador.uno@example.com'", Long.class);
        assertThat(body(send("PUT", "/api/admin/users/" + opId + "/toggle-status", null, bearer(superadmin)))).containsEntry("succeeded", true);
        assertThat(get("/api/admin/dashboard", bearer(operador)).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ empresas

    @Test
    void suspender_una_empresa_le_cierra_la_api() throws Exception {
        createAdmin("Operador Empresas", "operador.empresas@example.com", "operador");
        String token = fullLogin("operador.empresas@example.com").token();
        Map<String, String> tenantAuth = newTenant(tenants, "20777777771");
        long tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE ruc = '20777777771'", Long.class);
        assertThat(get("/api/v1/series", tenantAuth).statusCode()).isEqualTo(200);

        Map<String, Object> noReason = body(send("PUT", "/api/admin/tenants/" + tenantId + "/estado", "{\"activo\":false}", bearer(token)));
        assertThat(noReason).containsEntry("succeeded", false);

        Map<String, Object> suspended = data(send("PUT", "/api/admin/tenants/" + tenantId + "/estado",
                "{\"activo\":false,\"motivo\":\"Falta de pago\"}", bearer(token)));
        assertThat(suspended).containsEntry("isActive", false).containsEntry("suspensionReason", "Falta de pago");
        assertThat(get("/api/v1/series", tenantAuth).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'admin.empresa.suspendida' AND tenant_id = ? "
                + "AND actor_tipo = 'admin'", Long.class, tenantId)).isEqualTo(1L);

        Map<String, Object> active = data(send("PUT", "/api/admin/tenants/" + tenantId + "/estado", "{\"activo\":true}", bearer(token)));
        assertThat(active).containsEntry("isActive", true).containsEntry("suspensionReason", null);
        assertThat(get("/api/v1/series", tenantAuth).statusCode()).isEqualTo(200);

        Map<String, Object> plan = data(send("PUT", "/api/admin/tenants/" + tenantId + "/plan",
                "{\"plan\":\"pro\",\"maxDocumentsMonth\":500,\"emissionMode\":\"unlimited\"}", bearer(token)));
        assertThat(plan).containsEntry("plan", "pro").containsEntry("maxDocumentsMonth", 500).containsEntry("emissionMode", "unlimited");
        assertThat(body(send("PUT", "/api/admin/tenants/" + tenantId + "/plan", "{\"plan\":\"gold\",\"maxDocumentsMonth\":-1}", bearer(token))))
                .containsEntry("succeeded", false);
    }

    @Test
    void el_detalle_de_la_empresa_no_lleva_secretos() throws Exception {
        createAdmin("Super Detalle", "super.detalle@example.com", "superadmin");
        String token = fullLogin("super.detalle@example.com").token();
        long tenantId = demoTenantId();
        HttpResponse<String> r = get("/api/admin/tenants/" + tenantId, bearer(token));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).contains("no-store");
        Map<String, Object> d = data(r);
        assertThat(d).containsKeys("ruc", "razonSocial", "direccion", "emails", "sucursales", "series", "lastDocumentAt",
                "documentsByStatus", "certificate", "webhookUrl", "sireEnabled");
        assertThat(r.body()).doesNotContain(DEMO_SECRET).doesNotContain(DEMO_KEY).doesNotContain("MODDATOS")
                .doesNotContain("apiSecret").doesNotContain("api_secret").doesNotContain("solPass").doesNotContain("sol_pass")
                .doesNotContain("certificatePassword").doesNotContain("clientSecret").doesNotContain("consultaToken");

        // Listado camelCase con fechas ISO UTC y total sin paginar
        Map<String, Object> list = body(get("/api/admin/tenants?records=1&numPage=1", bearer(token)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list.get("data");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsKeys("id", "ruc", "razonSocial", "nombreComercial", "environment", "plan", "emissionMode",
                "isActive", "documentsThisMonth", "maxDocumentsMonth", "suspensionReason", "createdAt");
        assertThat(((Number) list.get("totalRecords")).longValue()).isGreaterThanOrEqualTo(1);

        // El resto del panel responde con el sobre
        for (String path : List.of("/api/admin/dashboard", "/api/admin/usage", "/api/admin/certificates", "/api/admin/rejections",
                "/api/admin/jobs", "/api/admin/health")) {
            HttpResponse<String> p = get(path, bearer(token));
            assertThat(p.statusCode()).as(path).isEqualTo(200);
            assertThat(body(p)).as(path).containsEntry("succeeded", true);
        }
        assertThat(body(get("/api/admin/usage?mes=2026-13", bearer(token)))).containsEntry("succeeded", false);
    }

    // ------------------------------------------------------------------ sesiones

    @Test
    void el_refresh_token_rota() throws Exception {
        createAdmin("Super Refresh", "super.refresh@example.com", "superadmin");
        Session s = fullLogin("super.refresh@example.com");
        Map<String, Object> first = data(post("/api/admin/auth/refreshToken",
                "{\"token\":\"" + s.token() + "\",\"refreshToken\":\"" + s.refresh() + "\"}", null));
        assertThat(first).containsKeys("token", "refreshToken", "refreshTokenExpiryTime", "recoveryCodesLeft");
        assertThat(first.get("refreshToken")).isNotEqualTo(s.refresh());

        // El viejo ya no sirve; el nuevo sí
        assertThat(body(post("/api/admin/auth/refreshToken", "{\"token\":\"" + s.token() + "\",\"refreshToken\":\"" + s.refresh() + "\"}", null)))
                .containsEntry("succeeded", false);
        assertThat(body(post("/api/admin/auth/refreshToken",
                "{\"token\":\"" + first.get("token") + "\",\"refreshToken\":\"" + first.get("refreshToken") + "\"}", null)))
                .containsEntry("succeeded", true);
        // Un token de acceso de otro usuario no acompaña a este refresh
        assertThat(body(post("/api/admin/auth/refreshToken", "{\"token\":\"x.y.z\",\"refreshToken\":\"no-existe\"}", null)))
                .containsEntry("succeeded", false);
    }

    @Test
    void logout_revoca_acceso_y_refresh() throws Exception {
        createAdmin("Super Logout", "super.logout@example.com", "superadmin");
        Session s = fullLogin("super.logout@example.com");
        assertThat(get("/api/admin/auth/me", bearer(s.token())).statusCode()).isEqualTo(200);
        assertThat(body(post("/api/admin/auth/logout", null, bearer(s.token())))).containsEntry("succeeded", true).containsEntry("data", null);
        assertThat(get("/api/admin/auth/me", bearer(s.token())).statusCode()).isEqualTo(401);
        assertThat(body(post("/api/admin/auth/refreshToken", "{\"token\":\"" + s.token() + "\",\"refreshToken\":\"" + s.refresh() + "\"}", null)))
                .containsEntry("succeeded", false);
    }

    @Test
    void cambiar_la_contrasena_cierra_las_sesiones() throws Exception {
        createAdmin("Super Clave", "super.clave@example.com", "superadmin");
        Session s = fullLogin("super.clave@example.com");
        Map<String, Object> mismatch = body(send("PUT", "/api/admin/auth/password",
                "{\"password\":\"" + PASSWORD + "\",\"newPassword\":\"Nueva#2026x\",\"confirmNewPassword\":\"Otra#2026x\"}", bearer(s.token())));
        assertThat(mismatch).containsEntry("succeeded", false);
        assertThat(body(send("PUT", "/api/admin/auth/password",
                "{\"password\":\"" + PASSWORD + "\",\"newPassword\":\"Nueva#2026x\",\"confirmNewPassword\":\"Nueva#2026x\"}", bearer(s.token()))))
                .containsEntry("succeeded", true);
        assertThat(get("/api/admin/auth/me", bearer(s.token())).statusCode()).isEqualTo(401);
        assertThat(data(post("/api/admin/auth/login", "{\"userName\":\"super.clave@example.com\",\"password\":\"Nueva#2026x\"}", null)))
                .containsEntry("mfaRequired", true);
    }

    @Test
    void cors_solo_para_los_origenes_del_panel() throws Exception {
        HttpResponse<String> allowed = send("OPTIONS", "/api/admin/auth/login", null, Map.of("Origin", "https://localhost:4220",
                "Access-Control-Request-Method", "POST"));
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains("https://localhost:4220");
        HttpResponse<String> denied = send("OPTIONS", "/api/admin/auth/login", null, Map.of("Origin", "https://evil.example.com",
                "Access-Control-Request-Method", "POST"));
        assertThat(denied.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    // ------------------------------------------------------------------ apoyo

    record Session(String token, String refresh, String secret) {
    }

    void createAdmin(String name, String email, String role) {
        jdbc.update("DELETE FROM users WHERE lower(email) = lower(?)", email);
        jdbc.update("INSERT INTO users (name, email, password, is_admin, role, is_active, created_at, updated_at) "
                + "VALUES (?, ?, ?, true, ?, true, now(), now())", name, email, bcrypt.encode(PASSWORD), role);
    }

    /** Login completo con configuración del MFA; devuelve el token, el refresh y la clave TOTP. */
    Session fullLogin(String email) throws Exception {
        resetRateLimit();
        Map<String, Object> login = data(post("/api/admin/auth/login", credentials(email), null));
        String ticket = (String) login.get("mfaTicket");
        String secret = ((String) data(post("/api/admin/auth/login/mfa/setup", "{\"mfaTicket\":\"" + ticket + "\"}", null)).get("sharedKey"))
                .replace(" ", "");
        Map<String, Object> confirm = data(post("/api/admin/auth/login/mfa/setup/confirm",
                "{\"mfaTicket\":\"" + ticket + "\",\"code\":\"" + nextCode(secret) + "\"}", null));
        resetRateLimit();
        return new Session((String) confirm.get("token"), (String) confirm.get("refreshToken"), secret);
    }

    /** Avanza el reloj un paso TOTP y devuelve el código de ese paso. */
    String nextCode(String secret) {
        advance(Duration.ofSeconds(30));
        return Totp.code(secret, Clock.now());
    }

    void advance(Duration d) {
        clock = clock.plus(d);
        Clock.useFixed(clock);
    }

    long demoTenantId() {
        return jdbc.queryForObject("SELECT id FROM tenants WHERE ruc = '20000000001'", Long.class);
    }

    static String credentials(String email) {
        return "{\"userName\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    static Map<String, String> bearer(String token) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        return h;
    }

    HttpResponse<String> post(String path, String json, Map<String, String> headers) throws Exception {
        return send("POST", path, json == null ? "{}" : json, headers == null ? Map.of() : headers);
    }

    static Map<String, Object> body(HttpResponse<String> r) {
        Map<String, Object> m = PhpJson.decodeObject(r.body());
        assertThat(m).as("respuesta JSON: " + r.statusCode()).isNotNull();
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> data(HttpResponse<String> r) {
        return (Map<String, Object>) okData(r);
    }

    @SuppressWarnings("unchecked")
    static List<Object> dataList(HttpResponse<String> r) {
        return (List<Object>) okData(r);
    }

    static Object okData(HttpResponse<String> r) {
        Map<String, Object> m = body(r);
        assertThat(m).as(r.body()).containsEntry("succeeded", true);
        return m.get("data");
    }

    @SuppressWarnings("unchecked")
    static String firstMessage(Map<String, Object> body) {
        List<String> messages = (List<String>) body.get("messages");
        return messages.isEmpty() ? null : messages.get(0);
    }
}
