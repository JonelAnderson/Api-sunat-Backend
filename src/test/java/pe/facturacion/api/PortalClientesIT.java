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
import org.springframework.test.context.TestPropertySource;
import pe.facturacion.api.modules.admin.security.Totp;
import pe.facturacion.api.modules.portal.security.PortalMessages;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.support.IntegrationTest;

/**
 * Portal de clientes ({@code /api/portal/**}, C6) contra PostgreSQL y Redis reales: invitación desde la consola
 * (clave de servicio), activación, login con MFA, bloqueo, aislamiento por empresa (404), separación de los JWT del
 * panel y del portal, paso de seguridad y límite al regenerar credenciales, invitación idempotente, deshabilitar y que
 * ninguna respuesta lleve campos secretos. Claves y contraseñas de prueba, sin valor real.
 *
 * <p>Requiere Docker (Testcontainers), igual que el resto de las {@code *IT}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "app.portal.jwt-secret=clave-de-prueba-del-portal-0123456789abcdef",
    "app.portal.allowed-origins=https://localhost:4230",
    "app.admin.jwt-secret=clave-de-prueba-del-panel-admin-0123456789abcdef",
    "app.admin.service.key=clave-de-servicio-de-prueba-0123456789abcdef",
    "app.admin.service.allowed-ips=127.0.0.1,::1,0:0:0:0:0:0:0:1",
    "app.queue.worker-enabled=false"
})
class PortalClientesIT extends IntegrationTest {

    static final String PASSWORD = "Prueba#2026";
    static final String NEW_PASSWORD = "Nueva#2026ab";
    static final String SERVICE_KEY = "clave-de-servicio-de-prueba-0123456789abcdef";
    static final String RUC_A = "20777777771";
    static final String RUC_B = "20777777772";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    TenantRepository tenants;

    Instant clock;
    Map<String, String> authA;
    Map<String, String> authB;
    long tenantA;
    long tenantB;
    long facturaA;
    long facturaB;
    int seq;

    /** Persona con sesión del portal: token, refresh, clave del autenticador y códigos de recuperación. */
    record Session(long userId, String email, String token, String refreshToken, String secret, List<String> recoveryCodes) {
    }

    @BeforeAll
    void setUp() throws Exception {
        clock = Instant.now();
        Clock.useFixed(clock);
        authA = newTenant(tenants, RUC_A);
        authB = newTenant(tenants, RUC_B);
        tenantA = jdbc.queryForObject("SELECT id FROM tenants WHERE ruc = ?", Long.class, RUC_A);
        tenantB = jdbc.queryForObject("SELECT id FROM tenants WHERE ruc = ?", Long.class, RUC_B);
        facturaA = emitirFactura(authA);
        facturaB = emitirFactura(authB);
    }

    @AfterAll
    void releaseClock() {
        Clock.useSystem();
    }

    @BeforeEach
    void resetLimits() {
        resetRateLimit();
        redis.delete("throttle:portal-regenerate:" + tenantA);
        redis.delete("throttle:portal-regenerate:" + tenantB);
    }

    // ------------------------------------------------------------------ invitación y activación

    @Test
    void la_consola_invita_la_persona_activa_y_entra_con_mfa() throws Exception {
        String email = uniqueEmail("alta");
        Map<String, Object> inv = invite(email, "Ana Cliente", List.of(tenantA), false);
        String token = (String) inv.get("token");
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(inv).containsKeys("userId", "expiresAt");

        // En la base solo queda el hash del token, y la cuenta sigue sin contraseña
        assertThat(jdbc.queryForObject("SELECT count(*) FROM portal_invitations WHERE token_hash = ?", Long.class, token)).isZero();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM portal_users WHERE lower(email) = ?", String.class, email)).isNull();
        // Antes de activar no puede entrar, y el mensaje no lo delata
        assertThat(body(post("/api/portal/auth/login", loginBody(email, PASSWORD))).get("messages")).isEqualTo(List.of(PortalMessages.BAD_CREDENTIALS));

        activate(token, PASSWORD);
        Session s = firstLogin(email);

        Map<String, Object> me = data(get("/api/portal/auth/me", bearer(s.token())));
        assertThat(me).containsOnlyKeys("id", "name", "email", "tenants");
        assertThat(me).containsEntry("email", email).containsEntry("name", "Ana Cliente");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mine = (List<Map<String, Object>>) me.get("tenants");
        assertThat(mine).extracting(t -> ((Number) t.get("id")).longValue()).containsExactly(tenantA);
        assertThat(mine.get(0)).containsEntry("ruc", RUC_A);

        // Segundo ingreso: ya con el MFA configurado pide solo el código
        resetRateLimit();
        Map<String, Object> second = data(post("/api/portal/auth/login", loginBody(email, PASSWORD)));
        assertThat(second).containsEntry("mfaRequired", true).doesNotContainKey("token");
    }

    @Test
    void la_invitacion_sirve_una_vez_y_vencida_usada_o_reemplazada_responde_lo_mismo() throws Exception {
        String email = uniqueEmail("activacion");
        String token = (String) invite(email, "Ana", List.of(tenantA), false).get("token");
        activate(token, PASSWORD);

        // Usada
        Map<String, Object> usada = body(post("/api/portal/auth/activate", activateBody(token, NEW_PASSWORD)));
        assertThat(usada).containsEntry("succeeded", false).containsEntry("messages", List.of(PortalMessages.INVITATION_INVALID));

        // Vencida (se vence a mano en la base)
        String vencida = (String) invite(uniqueEmail("vencida"), "Beto", List.of(tenantA), false).get("token");
        jdbc.update("UPDATE portal_invitations SET expires_at = now() - interval '1 minute'");
        assertThat(body(post("/api/portal/auth/activate", activateBody(vencida, PASSWORD)))).containsEntry("messages",
                List.of(PortalMessages.INVITATION_INVALID));

        // Reemplazada por una invitación nueva: la vieja deja de valer, la nueva sirve
        String reemail = uniqueEmail("reemplazada");
        String vieja = (String) invite(reemail, "Carla", List.of(tenantA), false).get("token");
        String nueva = (String) invite(reemail, "Carla", List.of(tenantA), false).get("token");
        assertThat(body(post("/api/portal/auth/activate", activateBody(vieja, PASSWORD)))).containsEntry("messages",
                List.of(PortalMessages.INVITATION_INVALID));
        assertThat(body(post("/api/portal/auth/activate", activateBody(nueva, PASSWORD)))).containsEntry("succeeded", true);

        // Desconocido
        assertThat(body(post("/api/portal/auth/activate", activateBody("x".repeat(43), PASSWORD)))).containsEntry("messages",
                List.of(PortalMessages.INVITATION_INVALID));
        // Todos los fallos son HTTP 200 con el sobre y ninguno entrega tokens
        assertThat(post("/api/portal/auth/activate", activateBody(vieja, PASSWORD)).body()).doesNotContain("\"token\"").doesNotContain("refreshToken");
    }

    @Test
    void una_contrasena_debil_no_gasta_la_invitacion() throws Exception {
        String email = uniqueEmail("debil");
        String token = (String) invite(email, "Ana", List.of(tenantA), false).get("token");

        Map<String, Object> weak = body(post("/api/portal/auth/activate", activateBody(token, "corta")));
        assertThat(weak).containsEntry("succeeded", false);
        assertThat(weak.get("messages")).isNotEqualTo(List.of(PortalMessages.INVITATION_INVALID));
        // El mismo enlace sigue sirviendo con una contraseña válida
        assertThat(body(post("/api/portal/auth/activate", activateBody(token, PASSWORD)))).containsEntry("succeeded", true);
    }

    @Test
    void invitar_de_nuevo_reemplaza_empresas_y_contrasena_reactiva_y_cierra_las_sesiones() throws Exception {
        String email = uniqueEmail("reinvitacion");
        activate((String) invite(email, "Ana", List.of(tenantA), false).get("token"), PASSWORD);
        Session viejo = firstLogin(email);
        assertThat(get("/api/portal/tenants/" + tenantA + "/resumen", bearer(viejo.token())).statusCode()).isEqualTo(200);

        // Se deshabilita y se vuelve a invitar a otra empresa
        setActive(viejo.userId(), false);
        Map<String, Object> again = invite(email, "Ana Nueva", List.of(tenantB), false);
        assertThat(((Number) again.get("userId")).longValue()).isEqualTo(viejo.userId());

        // La sesión anterior se cerró y la contraseña vieja ya no sirve (queda sin contraseña hasta activar)
        assertThat(get("/api/portal/tenants/" + tenantA + "/resumen", bearer(viejo.token())).statusCode()).isEqualTo(401);
        resetRateLimit();
        assertThat(body(post("/api/portal/auth/login", loginBody(email, PASSWORD))).get("messages")).isEqualTo(List.of(PortalMessages.BAD_CREDENTIALS));

        activate((String) again.get("token"), NEW_PASSWORD);
        // El MFA se conserva (no se pidió resetMfa): solo falta el código
        resetRateLimit();
        Map<String, Object> login = data(post("/api/portal/auth/login", loginBody(email, NEW_PASSWORD)));
        assertThat(login).containsEntry("mfaRequired", true);
        Session nuevo = codeLogin(email, NEW_PASSWORD, viejo.secret());
        assertThat(get("/api/portal/tenants/" + tenantB + "/resumen", bearer(nuevo.token())).statusCode()).isEqualTo(200);
        // Las empresas se reemplazaron, no se sumaron
        assertThat(get("/api/portal/tenants/" + tenantA + "/resumen", bearer(nuevo.token())).statusCode()).isEqualTo(404);

        // Con resetMfa el autenticador se borra y vuelve a configurarse en el siguiente ingreso
        activate((String) invite(email, "Ana Nueva", List.of(tenantB), true).get("token"), PASSWORD);
        resetRateLimit();
        assertThat(data(post("/api/portal/auth/login", loginBody(email, PASSWORD)))).containsEntry("mfaSetupRequired", true);
    }

    // ------------------------------------------------------------------ login y bloqueo

    @Test
    void correo_inexistente_y_contrasena_incorrecta_responden_exactamente_lo_mismo() throws Exception {
        String email = uniqueEmail("generico");
        activate((String) invite(email, "Ana", List.of(tenantA), false).get("token"), PASSWORD);

        HttpResponse<String> noExiste = post("/api/portal/auth/login", loginBody("nadie@cliente.pe", PASSWORD));
        HttpResponse<String> mal = post("/api/portal/auth/login", loginBody(email, "Otra#2026x"));
        assertThat(noExiste.statusCode()).isEqualTo(mal.statusCode());
        assertThat(body(noExiste)).isEqualTo(body(mal));
        assertThat(body(mal)).containsEntry("succeeded", false).containsEntry("messages", List.of(PortalMessages.BAD_CREDENTIALS));
    }

    @Test
    void cinco_fallos_bloquean_la_cuenta_y_el_codigo_correcto_ya_no_entra() throws Exception {
        String email = uniqueEmail("bloqueo");
        activate((String) invite(email, "Ana", List.of(tenantA), false).get("token"), PASSWORD);
        Session s = firstLogin(email);

        for (int i = 0; i < 5; i++) {
            resetRateLimit();
            assertThat(body(post("/api/portal/auth/login", loginBody(email, "Otra#2026x"))).get("messages"))
                    .isEqualTo(List.of(PortalMessages.BAD_CREDENTIALS));
        }
        assertThat(jdbc.queryForObject("SELECT locked_until IS NOT NULL FROM portal_users WHERE lower(email) = ?", Boolean.class, email)).isTrue();
        // Quien acierta la contraseña se entera del bloqueo; quien no, sigue viendo el mensaje genérico
        resetRateLimit();
        assertThat(body(post("/api/portal/auth/login", loginBody(email, PASSWORD))).get("messages")).isEqualTo(List.of(PortalMessages.LOCKED));
        assertThat(body(post("/api/portal/auth/login", loginBody(email, "Otra#2026x"))).get("messages"))
                .isEqualTo(List.of(PortalMessages.BAD_CREDENTIALS));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'portal.cuenta.bloqueada' AND actor_etiqueta = ?", Long.class,
                email)).isEqualTo(1L);

        // Pasado el bloqueo (15 min) vuelve a entrar
        clock = clock.plus(Duration.ofMinutes(16));
        Clock.useFixed(clock);
        resetRateLimit();
        Session back = codeLogin(email, PASSWORD, s.secret());
        assertThat(back.token()).isNotBlank();
    }

    @Test
    void un_codigo_de_mfa_vale_una_sola_vez_y_los_de_recuperacion_tambien() throws Exception {
        String email = uniqueEmail("mfa");
        activate((String) invite(email, "Ana", List.of(tenantA), false).get("token"), PASSWORD);
        Session s = firstLogin(email);
        resetRateLimit();

        Map<String, Object> login = data(post("/api/portal/auth/login", loginBody(email, PASSWORD)));
        String ticket = (String) login.get("mfaTicket");
        tick();
        String code = Totp.code(s.secret(), Clock.now());
        assertThat(body(post("/api/portal/auth/login/mfa", "{\"mfaTicket\":\"" + ticket + "\",\"code\":\"" + code + "\"}"))).containsEntry("succeeded", true);
        // El mismo código en el mismo paso de 30 s no vuelve a servir
        resetRateLimit();
        String ticket2 = (String) data(post("/api/portal/auth/login", loginBody(email, PASSWORD))).get("mfaTicket");
        assertThat(body(post("/api/portal/auth/login/mfa", "{\"mfaTicket\":\"" + ticket2 + "\",\"code\":\"" + code + "\"}")))
                .containsEntry("succeeded", false).containsEntry("messages", List.of(PortalMessages.BAD_CODE));

        // Un código de recuperación entra una vez
        String recovery = s.recoveryCodes().get(0);
        String ticket3 = (String) data(post("/api/portal/auth/login", loginBody(email, PASSWORD))).get("mfaTicket");
        assertThat(body(post("/api/portal/auth/login/mfa", "{\"mfaTicket\":\"" + ticket3 + "\",\"recoveryCode\":\"" + recovery + "\"}")))
                .containsEntry("succeeded", true);
        String ticket4 = (String) data(post("/api/portal/auth/login", loginBody(email, PASSWORD))).get("mfaTicket");
        assertThat(body(post("/api/portal/auth/login/mfa", "{\"mfaTicket\":\"" + ticket4 + "\",\"recoveryCode\":\"" + recovery + "\"}")))
                .containsEntry("succeeded", false);
    }

    // ------------------------------------------------------------------ aislamiento entre empresas

    @Test
    void una_persona_nunca_ve_la_empresa_ni_los_comprobantes_de_otra() throws Exception {
        Session ana = newUser("aislamiento.a", List.of(tenantA));
        Session beto = newUser("aislamiento.b", List.of(tenantB));

        // Propia: 200 y solo sus documentos
        Map<String, Object> list = body(get("/api/portal/tenants/" + tenantA + "/facturas?records=50", bearer(ana.token())));
        assertThat(list).containsEntry("succeeded", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list.get("data");
        assertThat(rows).extracting(r -> ((Number) r.get("id")).longValue()).contains(facturaA).doesNotContain(facturaB);

        // Ajena: 404 con el mismo mensaje que una empresa que no existe (no revela cuál existe)
        for (String path : List.of("/resumen", "/uso", "/documentos", "/documentos/resumen", "/facturas", "/facturas/" + facturaB,
                "/facturas/" + facturaB + "/estado", "/facturas/" + facturaB + "/xml", "/facturas/" + facturaB + "/cdr",
                "/facturas/" + facturaB + "/pdf", "/boletas", "/notas-credito")) {
            HttpResponse<String> ajena = get("/api/portal/tenants/" + tenantB + path, bearer(ana.token()));
            HttpResponse<String> inexistente = get("/api/portal/tenants/999999999" + path, bearer(ana.token()));
            assertThat(ajena.statusCode()).as(path).isEqualTo(404);
            assertThat(inexistente.statusCode()).as(path).isEqualTo(404);
            assertThat(body(ajena)).as(path).isEqualTo(body(inexistente));
            assertThat(body(ajena)).containsEntry("succeeded", false).containsEntry("data", null);
        }
        // El documento de B pedido bajo la empresa A de Ana tampoco aparece
        assertThat(get("/api/portal/tenants/" + tenantA + "/facturas/" + facturaB, bearer(ana.token())).statusCode()).isEqualTo(404);
        // Y la regeneración de credenciales de una empresa ajena es 404 antes de pedir contraseña o código
        HttpResponse<String> regen = post("/api/portal/tenants/" + tenantB + "/credenciales/regenerar",
                "{\"password\":\"" + PASSWORD + "\",\"code\":\"123456\"}", bearer(ana.token()));
        assertThat(regen.statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'portal.credenciales.regeneradas' AND tenant_id = ?",
                Long.class, tenantB)).isZero();

        // Cada una ve lo suyo
        assertThat(get("/api/portal/tenants/" + tenantB + "/facturas/" + facturaB, bearer(beto.token())).statusCode()).isEqualTo(200);
        assertThat(get("/api/portal/tenants/" + tenantA + "/facturas/" + facturaA, bearer(beto.token())).statusCode()).isEqualTo(404);

        // Una persona con dos empresas ve las dos
        Session ambas = newUser("aislamiento.ab", List.of(tenantA, tenantB));
        assertThat(get("/api/portal/tenants/" + tenantA + "/resumen", bearer(ambas.token())).statusCode()).isEqualTo(200);
        assertThat(get("/api/portal/tenants/" + tenantB + "/resumen", bearer(ambas.token())).statusCode()).isEqualTo(200);
    }

    @Test
    void resumen_uso_y_comprobantes_de_la_empresa_propia() throws Exception {
        Session ana = newUser("consulta", List.of(tenantA));

        Map<String, Object> resumen = data(get("/api/portal/tenants/" + tenantA + "/resumen", bearer(ana.token())));
        assertThat(resumen).containsEntry("ruc", RUC_A).containsKeys("plan", "planName", "documentsThisMonth", "maxDocumentsMonth", "certificate");
        assertThat(((Number) resumen.get("documentsThisMonth")).longValue()).isGreaterThanOrEqualTo(1L);

        Map<String, Object> uso = data(get("/api/portal/tenants/" + tenantA + "/uso", bearer(ana.token())));
        assertThat(uso).containsKeys("month", "documents", "maxDocuments", "history");
        assertThat((List<?>) uso.get("history")).hasSize(6);

        Map<String, Object> unificada = body(get("/api/portal/tenants/" + tenantA + "/documentos", bearer(ana.token())));
        assertThat(unificada).containsEntry("succeeded", true);
        assertThat(data(get("/api/portal/tenants/" + tenantA + "/documentos/resumen", bearer(ana.token())))).isNotEmpty();

        // Detalle y archivos del documento propio
        Map<String, Object> detalle = data(get("/api/portal/tenants/" + tenantA + "/facturas/" + facturaA, bearer(ana.token())));
        assertThat(detalle).doesNotContainKey("datos_edicion");
        Map<String, Object> v1 = PhpJson.decodeObject(get("/api/v1/facturas/" + facturaA, authA).body());
        assertThat(detalle).isEqualTo(v1.get("datos"));
        HttpResponse<byte[]> pdf = getBytes("/api/portal/tenants/" + tenantA + "/facturas/" + facturaA + "/pdf", bearer(ana.token()));
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("Content-Type")).contains("application/pdf");
        assertThat(pdf.body()).isEqualTo(getBytes("/api/v1/facturas/" + facturaA + "/pdf", authA).body());
        Map<String, Object> estado = data(get("/api/portal/tenants/" + tenantA + "/facturas/" + facturaA + "/estado", bearer(ana.token())));
        assertThat(estado).containsOnlyKeys("id", "tipo_documento", "numero_completo", "sunat", "enviado_en");

        // Detalle y descarga quedan en la auditoría con el actor del portal
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'portal.empresa.descarga' AND actor_tipo = 'portal' "
                + "AND actor_etiqueta = ? AND tenant_id = ? AND metadata->>'archivo' = 'pdf'", Long.class, ana.email(), tenantA)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'portal.empresa.consulta' AND actor_etiqueta = ? "
                + "AND tenant_id = ?", Long.class, ana.email(), tenantA)).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void ninguna_respuesta_del_portal_lleva_campos_secretos() throws Exception {
        Session ana = newUser("secretos", List.of(tenantA));
        String apiKey = authA.get("X-Api-Key");
        String apiSecret = authA.get("X-Api-Secret");
        for (String path : List.of("/api/portal/auth/me", "/api/portal/auth/mfa/status", "/api/portal/tenants/" + tenantA + "/resumen",
                "/api/portal/tenants/" + tenantA + "/uso", "/api/portal/tenants/" + tenantA + "/documentos",
                "/api/portal/tenants/" + tenantA + "/facturas", "/api/portal/tenants/" + tenantA + "/facturas/" + facturaA,
                "/api/portal/tenants/" + tenantA + "/facturas/" + facturaA + "/estado")) {
            HttpResponse<String> r = get(path, bearer(ana.token()));
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            assertThat(r.headers().firstValue("Cache-Control")).as(path).contains("no-store");
            assertThat(r.body()).as(path).doesNotContain(apiKey).doesNotContain(apiSecret).doesNotContain("MODDATOS")
                    .doesNotContain("\"sol_user\"").doesNotContain("\"sol_pass\"").doesNotContain("\"client_id\"").doesNotContain("\"client_secret\"")
                    .doesNotContain("\"api_secret\"").doesNotContain("\"api_key\"").doesNotContain("\"consulta_token\"")
                    .doesNotContain("\"certificate_password\"").doesNotContain("password_hash").doesNotContain("two_factor")
                    .doesNotContain("$2a$").doesNotContain("$2b$").doesNotContain("BEGIN");
        }
        // La lista de la consola tampoco trae hash ni claves del MFA
        HttpResponse<String> users = serviceGet("/api/admin/portal/users?textFilter=" + ana.email());
        assertThat(users.statusCode()).isEqualTo(200);
        assertThat(users.body()).doesNotContain("password_hash").doesNotContain("two_factor").doesNotContain("$2a$").doesNotContain(ana.secret());
        assertThat(body(users)).containsEntry("succeeded", true);
        // Los textos de las rutas del portal que no existen (credenciales guardadas, certificado) no tienen vista
        for (String path : List.of("/credenciales", "/empresa", "/certificado", "/sol")) {
            assertThat(get("/api/portal/tenants/" + tenantA + path, bearer(ana.token())).statusCode()).as(path).isIn(404, 405);
        }
    }

    // ------------------------------------------------------------------ separación de los JWT

    @Test
    void un_token_del_panel_no_entra_al_portal_ni_uno_del_portal_al_panel() throws Exception {
        Session ana = newUser("separacion", List.of(tenantA));
        String panelToken = panelLogin();

        // Panel → portal
        assertThat(get("/api/portal/auth/me", bearer(panelToken)).statusCode()).isEqualTo(401);
        assertThat(get("/api/portal/tenants/" + tenantA + "/resumen", bearer(panelToken)).statusCode()).isEqualTo(401);
        // Portal → panel
        assertThat(get("/api/admin/tenants", bearer(ana.token())).statusCode()).isEqualTo(401);
        assertThat(get("/api/admin/tenants/" + tenantA + "/facturas", bearer(ana.token())).statusCode()).isEqualTo(401);
        assertThat(get("/api/admin/portal/users", bearer(ana.token())).statusCode()).isEqualTo(401);
        // Y con el token del panel de un superadmin sí se administra el portal (mismo esquema que el resto de /api/admin)
        assertThat(get("/api/admin/portal/users", bearer(panelToken)).statusCode()).isEqualTo(200);
        // Ni la clave de servicio abre el portal ni un token del portal vale como clave de servicio
        Map<String, String> service = serviceHeaders();
        assertThat(get("/api/portal/auth/me", service).statusCode()).isEqualTo(401);
        // Un ticket MFA no es un token de acceso
        resetRateLimit();
        String ticket = (String) data(post("/api/portal/auth/login", loginBody(ana.email(), PASSWORD))).get("mfaTicket");
        assertThat(get("/api/portal/auth/me", bearer(ticket)).statusCode()).isEqualTo(401);
        // Sin token y solo lectura
        assertThat(get("/api/portal/auth/me", Map.of()).statusCode()).isEqualTo(401);
        HttpResponse<String> write = send("POST", "/api/portal/tenants/" + tenantA + "/facturas", "{}", bearer(ana.token()));
        assertThat(write.statusCode()).isIn(403, 404, 405);
        assertThat(send("DELETE", "/api/portal/tenants/" + tenantA + "/facturas/" + facturaA, null, bearer(ana.token())).statusCode()).isIn(403, 404, 405);
    }

    // ------------------------------------------------------------------ credenciales (paso de seguridad)

    @Test
    void regenerar_credenciales_exige_contrasena_y_codigo_y_las_anteriores_dejan_de_servir() throws Exception {
        Session ana = newUser("credenciales", List.of(tenantA));
        String path = "/api/portal/tenants/" + tenantA + "/credenciales/regenerar";
        String oldKey = authA.get("X-Api-Key");

        // Sin código, con la contraseña mal o con el código mal: el mismo mensaje y nada cambia
        tick();
        String good = Totp.code(ana.secret(), Clock.now());
        for (String bodyJson : List.of("{\"password\":\"" + PASSWORD + "\"}", "{\"code\":\"" + good + "\"}",
                "{\"password\":\"Otra#2026x\",\"code\":\"" + good + "\"}", "{\"password\":\"" + PASSWORD + "\",\"code\":\"000000\"}")) {
            HttpResponse<String> r = post(path, bodyJson, bearer(ana.token()));
            assertThat(body(r)).as(bodyJson).containsEntry("succeeded", false);
            assertThat(r.body()).as(bodyJson).doesNotContain("apiSecret");
        }
        assertThat(get("/api/v1/facturas/" + facturaA, authA).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'portal.credenciales.regeneradas' AND tenant_id = ?",
                Long.class, tenantA)).isZero();

        // Con contraseña y código vigentes: devuelve las nuevas, una sola vez y sin caché
        tick();
        HttpResponse<String> ok = post(path, "{\"password\":\"" + PASSWORD + "\",\"code\":\"" + Totp.code(ana.secret(), Clock.now()) + "\"}",
                bearer(ana.token()));
        Map<String, Object> creds = data(ok);
        assertThat(creds).containsKeys("apiKey", "apiSecret", "aviso");
        assertThat(ok.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat((String) creds.get("apiKey")).isNotEqualTo(oldKey);

        // La clave nueva funciona y la anterior ya no
        Map<String, String> fresh = new HashMap<>();
        fresh.put("X-Api-Key", (String) creds.get("apiKey"));
        fresh.put("X-Api-Secret", (String) creds.get("apiSecret"));
        assertThat(get("/api/v1/facturas/" + facturaA, fresh).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/facturas/" + facturaA, authA).statusCode()).isIn(401, 403);
        authA = fresh;
        // La auditoría no guarda ni la clave ni el secreto
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND (descripcion LIKE ? OR metadata::text LIKE ?)",
                Long.class, tenantA, "%" + creds.get("apiSecret") + "%", "%" + creds.get("apiSecret") + "%")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND (descripcion LIKE ? OR metadata::text LIKE ?)",
                Long.class, tenantA, "%" + creds.get("apiKey") + "%", "%" + creds.get("apiKey") + "%")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'portal.credenciales.regeneradas' AND actor_etiqueta = ?",
                Long.class, ana.email())).isEqualTo(1L);
    }

    @Test
    void se_regeneran_3_veces_por_hora_y_la_cuarta_se_rechaza() throws Exception {
        Session ana = newUser("limite", List.of(tenantB));
        String path = "/api/portal/tenants/" + tenantB + "/credenciales/regenerar";
        for (int i = 1; i <= 3; i++) {
            tick();
            Map<String, Object> r = body(post(path, "{\"password\":\"" + PASSWORD + "\",\"code\":\"" + Totp.code(ana.secret(), Clock.now()) + "\"}",
                    bearer(ana.token())));
            assertThat(r).as("regeneración %d", i).containsEntry("succeeded", true);
        }
        tick();
        HttpResponse<String> cuarta = post(path, "{\"password\":\"" + PASSWORD + "\",\"code\":\"" + Totp.code(ana.secret(), Clock.now()) + "\"}",
                bearer(ana.token()));
        assertThat(body(cuarta)).containsEntry("succeeded", false);
        assertThat(cuarta.body()).doesNotContain("apiSecret");
        // El cupo es de la empresa, no de la persona: otra persona de la misma empresa tampoco puede
        Session otra = newUser("limite.otra", List.of(tenantB));
        tick();
        assertThat(body(post(path, "{\"password\":\"" + PASSWORD + "\",\"code\":\"" + Totp.code(otra.secret(), Clock.now()) + "\"}",
                bearer(otra.token())))).containsEntry("succeeded", false);
    }

    @Test
    void cinco_pasos_de_seguridad_fallidos_bloquean_la_cuenta_y_cierran_sus_sesiones() throws Exception {
        Session ana = newUser("stepup.bloqueo", List.of(tenantA));
        String path = "/api/portal/tenants/" + tenantA + "/credenciales/regenerar";
        for (int i = 0; i < 5; i++) {
            body(post(path, "{\"password\":\"Otra#2026x\",\"code\":\"000000\"}", bearer(ana.token())));
        }
        // La sesión se cerró y la cuenta quedó bloqueada
        assertThat(get("/api/portal/auth/me", bearer(ana.token())).statusCode()).isEqualTo(401);
        resetRateLimit();
        assertThat(body(post("/api/portal/auth/login", loginBody(ana.email(), PASSWORD))).get("messages")).isEqualTo(List.of(PortalMessages.LOCKED));
    }

    // ------------------------------------------------------------------ deshabilitar y sesiones

    @Test
    void deshabilitar_cierra_las_sesiones_al_instante_y_habilitar_exige_ingresar_de_nuevo() throws Exception {
        Session ana = newUser("deshabilitar", List.of(tenantA));
        assertThat(get("/api/portal/auth/me", bearer(ana.token())).statusCode()).isEqualTo(200);

        setActive(ana.userId(), false);
        // El token de acceso y el refresh dejan de servir; el login responde que el acceso está deshabilitado solo a quien acierta
        assertThat(get("/api/portal/auth/me", bearer(ana.token())).statusCode()).isEqualTo(401);
        resetRateLimit();
        assertThat(body(post("/api/portal/auth/refreshToken", "{\"token\":\"" + ana.token() + "\",\"refreshToken\":\"" + ana.refreshToken() + "\"}"))
                .get("succeeded")).isEqualTo(false);
        assertThat(body(post("/api/portal/auth/login", loginBody(ana.email(), PASSWORD))).get("messages")).isEqualTo(List.of(PortalMessages.INACTIVE));
        assertThat(body(post("/api/portal/auth/login", loginBody(ana.email(), "Otra#2026x"))).get("messages"))
                .isEqualTo(List.of(PortalMessages.BAD_CREDENTIALS));

        // Habilitada otra vez: el token viejo sigue muerto, pero puede entrar de nuevo
        setActive(ana.userId(), true);
        assertThat(get("/api/portal/auth/me", bearer(ana.token())).statusCode()).isEqualTo(401);
        resetRateLimit();
        Session back = codeLogin(ana.email(), PASSWORD, ana.secret());
        assertThat(get("/api/portal/auth/me", bearer(back.token())).statusCode()).isEqualTo(200);
    }

    @Test
    void cerrar_sesion_cambiar_contrasena_y_refresh_rotan_o_revocan_las_sesiones() throws Exception {
        Session ana = newUser("sesiones", List.of(tenantA));

        // Refresh: entrega un par nuevo y el refresh anterior no se reutiliza
        Map<String, Object> pair = data(post("/api/portal/auth/refreshToken",
                "{\"token\":\"" + ana.token() + "\",\"refreshToken\":\"" + ana.refreshToken() + "\"}"));
        assertThat(pair).containsKeys("token", "refreshToken");
        assertThat(body(post("/api/portal/auth/refreshToken", "{\"token\":\"" + ana.token() + "\",\"refreshToken\":\"" + ana.refreshToken() + "\"}")))
                .containsEntry("succeeded", false);
        String token = (String) pair.get("token");

        // Cambiar la contraseña cierra todas las sesiones
        Map<String, Object> changed = body(send("PUT", "/api/portal/auth/password", "{\"password\":\"" + PASSWORD + "\",\"newPassword\":\""
                + NEW_PASSWORD + "\",\"confirmNewPassword\":\"" + NEW_PASSWORD + "\"}", bearer(token)));
        assertThat(changed).containsEntry("succeeded", true);
        assertThat(get("/api/portal/auth/me", bearer(token)).statusCode()).isEqualTo(401);

        resetRateLimit();
        Session again = codeLogin(ana.email(), NEW_PASSWORD, ana.secret());
        // Cerrar sesión revoca el token
        assertThat(body(post("/api/portal/auth/logout", "{}", bearer(again.token())))).containsEntry("succeeded", true);
        assertThat(get("/api/portal/auth/me", bearer(again.token())).statusCode()).isEqualTo(401);
    }

    @Test
    void el_limite_por_ip_de_10_intentos_por_minuto_responde_429() throws Exception {
        resetRateLimit();
        int ok = 0;
        int limited = 0;
        for (int i = 0; i < 14; i++) {
            int status = post("/api/portal/auth/login", loginBody("nadie@cliente.pe", PASSWORD)).statusCode();
            if (status == 429) {
                limited++;
            } else {
                ok++;
            }
        }
        assertThat(ok).isEqualTo(10);
        assertThat(limited).isEqualTo(4);
        // La activación comparte el límite (el token se adivina o se prueba por fuerza bruta)
        assertThat(post("/api/portal/auth/activate", activateBody("x".repeat(43), PASSWORD)).statusCode()).isEqualTo(429);
    }

    @Test
    void la_consola_solo_entra_con_su_clave_y_la_lista_filtra_por_empresa() throws Exception {
        // Sin clave ni token
        assertThat(send("POST", "/api/admin/portal/invitations", "{}", Map.of()).statusCode()).isIn(401, 503);
        // Clave incorrecta
        Map<String, String> bad = serviceHeaders();
        bad.put("X-Admin-Key", SERVICE_KEY + "x");
        assertThat(send("POST", "/api/admin/portal/invitations", "{}", bad).statusCode()).isEqualTo(401);
        // Sin actor
        Map<String, String> noActor = serviceHeaders();
        noActor.remove("X-Admin-Actor");
        assertThat(send("GET", "/api/admin/portal/users", null, noActor).statusCode()).isEqualTo(400);

        // Validaciones de la invitación (HTTP 200 con el sobre)
        assertThat(body(send("POST", "/api/admin/portal/invitations", "{\"email\":\"no-es-correo\",\"name\":\"Ana\",\"tenantIds\":[" + tenantA + "]}",
                serviceHeaders()))).containsEntry("succeeded", false);
        assertThat(body(send("POST", "/api/admin/portal/invitations", "{\"email\":\"ana@cliente.pe\",\"name\":\"Ana\",\"tenantIds\":[]}",
                serviceHeaders()))).containsEntry("succeeded", false);
        assertThat(body(send("POST", "/api/admin/portal/invitations", "{\"email\":\"ana@cliente.pe\",\"name\":\"Ana\",\"tenantIds\":[999999999]}",
                serviceHeaders()))).containsEntry("succeeded", false);

        String email = uniqueEmail("lista");
        invite(email, "Ana Lista", List.of(tenantA), false);
        Map<String, Object> list = body(serviceGet("/api/admin/portal/users?tenantId=" + tenantA + "&textFilter=" + email));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list.get("data");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsOnlyKeys("id", "email", "name", "active", "activated", "mfaEnabled", "lastLoginAt", "tenantIds");
        assertThat(rows.get(0)).containsEntry("activated", false).containsEntry("mfaEnabled", false);
        assertThat(((List<?>) body(serviceGet("/api/admin/portal/users?tenantId=" + tenantB + "&textFilter=" + email)).get("data"))).isEmpty();
        // Cada alta y cada cambio de estado queda auditado sin el token de la invitación
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion ILIKE 'admin.portal.%'", Long.class)).isGreaterThanOrEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE metadata::text ~ '[A-Za-z0-9_-]{43}' AND accion ILIKE 'admin.portal.%'",
                Long.class)).isZero();
    }

    // ------------------------------------------------------------------ apoyo

    /** Alta completa: invita, activa y configura el MFA; devuelve la sesión del primer ingreso. */
    Session newUser(String prefix, List<Long> tenantIds) throws Exception {
        String email = uniqueEmail(prefix);
        activate((String) invite(email, "Persona " + prefix, tenantIds, false).get("token"), PASSWORD);
        return firstLogin(email);
    }

    /** Login → configura el autenticador → confirma el código (dos pasos con ticket). */
    Session firstLogin(String email) throws Exception {
        resetRateLimit();
        Map<String, Object> login = data(post("/api/portal/auth/login", loginBody(email, PASSWORD)));
        assertThat(login).containsEntry("mfaSetupRequired", true);
        String ticket = (String) login.get("mfaTicket");
        String secret = ((String) data(post("/api/portal/auth/login/mfa/setup", "{\"mfaTicket\":\"" + ticket + "\"}")).get("sharedKey")).replace(" ", "");
        tick();
        Map<String, Object> confirm = data(post("/api/portal/auth/login/mfa/setup/confirm",
                "{\"mfaTicket\":\"" + ticket + "\",\"code\":\"" + Totp.code(secret, Clock.now()) + "\"}"));
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) confirm.get("recoveryCodes");
        assertThat(codes).hasSize(10);
        long userId = jdbc.queryForObject("SELECT id FROM portal_users WHERE lower(email) = ?", Long.class, email);
        resetRateLimit();
        return new Session(userId, email, (String) confirm.get("token"), (String) confirm.get("refreshToken"), secret, codes);
    }

    /** Login de una persona con el autenticador ya configurado: contraseña y luego el código. */
    Session codeLogin(String email, String password, String secret) throws Exception {
        resetRateLimit();
        String ticket = (String) data(post("/api/portal/auth/login", loginBody(email, password))).get("mfaTicket");
        tick();
        Map<String, Object> done = data(post("/api/portal/auth/login/mfa",
                "{\"mfaTicket\":\"" + ticket + "\",\"code\":\"" + Totp.code(secret, Clock.now()) + "\"}"));
        long userId = jdbc.queryForObject("SELECT id FROM portal_users WHERE lower(email) = ?", Long.class, email);
        resetRateLimit();
        return new Session(userId, email, (String) done.get("token"), (String) done.get("refreshToken"), secret, List.of());
    }

    Map<String, Object> invite(String email, String name, List<Long> tenantIds, boolean resetMfa) throws Exception {
        String ids = String.join(",", tenantIds.stream().map(String::valueOf).toList());
        HttpResponse<String> r = send("POST", "/api/admin/portal/invitations", "{\"email\":\"" + email + "\",\"name\":\"" + name + "\",\"tenantIds\":["
                + ids + "],\"resetMfa\":" + resetMfa + "}", serviceHeaders());
        assertThat(r.headers().firstValue("Cache-Control")).as("la invitación lleva el token: sin caché").contains("no-store");
        return data(r);
    }

    void setActive(long userId, boolean active) throws Exception {
        HttpResponse<String> r = send("PUT", "/api/admin/portal/users/" + userId + "/estado", "{\"active\":" + active + "}", serviceHeaders());
        assertThat(body(r)).as(r.body()).containsEntry("succeeded", true);
    }

    void activate(String token, String password) throws Exception {
        resetRateLimit();
        Map<String, Object> r = body(post("/api/portal/auth/activate", activateBody(token, password)));
        assertThat(r).as("activación %s", r).containsEntry("succeeded", true);
    }

    /** Panel: superadmin con MFA (el flujo del panel de las demás pruebas). */
    String panelLogin() throws Exception {
        String email = uniqueEmail("panel");
        jdbc.update("INSERT INTO users (name, email, password, is_admin, role, is_active, created_at, updated_at) "
                + "VALUES (?, ?, ?, true, 'superadmin', true, now(), now())", "Admin panel", email,
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(PASSWORD));
        redis.delete("throttle:admin-auth:127.0.0.1");
        redis.delete("throttle:admin-auth:0:0:0:0:0:0:0:1");
        Map<String, Object> login = data(send("POST", "/api/admin/auth/login", "{\"userName\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}",
                Map.of()));
        String ticket = (String) login.get("mfaTicket");
        String secret = ((String) data(send("POST", "/api/admin/auth/login/mfa/setup", "{\"mfaTicket\":\"" + ticket + "\"}", Map.of()))
                .get("sharedKey")).replace(" ", "");
        tick();
        Map<String, Object> confirm = data(send("POST", "/api/admin/auth/login/mfa/setup/confirm",
                "{\"mfaTicket\":\"" + ticket + "\",\"code\":\"" + Totp.code(secret, Clock.now()) + "\"}", Map.of()));
        redis.delete("throttle:admin-auth:127.0.0.1");
        redis.delete("throttle:admin-auth:0:0:0:0:0:0:0:1");
        return (String) confirm.get("token");
    }

    /** Sucursal, series por defecto y una factura pendiente (sin envío a SUNAT); devuelve su id. */
    long emitirFactura(Map<String, String> auth) throws Exception {
        HttpResponse<String> suc = send("POST", "/api/v1/sucursales",
                "{\"nombre\":\"Principal\",\"cod_local\":\"0000\",\"direccion\":\"Av 1\",\"ubigeo\":\"150101\"}", auth);
        assertThat(suc.statusCode()).as(suc.body()).isEqualTo(201);
        HttpResponse<String> init = send("POST", "/api/v1/series/init-defaults", "{}", auth);
        assertThat(init.statusCode()).as(init.body()).isEqualTo(200);
        HttpResponse<String> f = send("POST", "/api/v1/facturas", "{\"serie\":\"F001\",\"fecha_emision\":\"" + Clock.todayLima()
                + "\",\"enviar_automatico\":false,\"cliente\":{\"tipo_doc\":\"6\",\"num_doc\":\"20601234567\",\"razon_social\":\"CLIENTE SAC\","
                + "\"direccion\":\"AV. LIMA 1\"},\"items\":[{\"codigo\":\"P1\",\"descripcion\":\"Producto\",\"unidad\":\"NIU\",\"cantidad\":1,"
                + "\"precio_unitario\":118,\"tip_afe_igv\":\"10\"}]}", auth);
        assertThat(f.statusCode()).as(f.body()).isEqualTo(201);
        @SuppressWarnings("unchecked")
        Map<String, Object> datos = (Map<String, Object>) PhpJson.decodeObject(f.body()).get("datos");
        return ((Number) datos.get("id")).longValue();
    }

    /** Avanza el reloj fijo un paso de TOTP (30 s) para que el siguiente código sea otro. */
    void tick() {
        clock = clock.plus(Duration.ofSeconds(30));
        Clock.useFixed(clock);
    }

    void resetRateLimit() {
        for (String ip : List.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1")) {
            redis.delete("throttle:portal-auth:" + ip);
        }
    }

    String uniqueEmail(String prefix) {
        return prefix + "." + (++seq) + "." + System.nanoTime() + "@cliente.pe";
    }

    static String loginBody(String email, String password) {
        return "{\"userName\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    static String activateBody(String token, String password) {
        return "{\"token\":\"" + token + "\",\"password\":\"" + password + "\",\"confirmPassword\":\"" + password + "\"}";
    }

    HttpResponse<String> post(String path, String json) throws Exception {
        return send("POST", path, json, Map.of());
    }

    HttpResponse<String> post(String path, String json, Map<String, String> headers) throws Exception {
        return send("POST", path, json, headers);
    }

    Map<String, String> serviceHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("X-Admin-Key", SERVICE_KEY);
        h.put("X-Admin-Actor", "operador@consola.pe");
        return h;
    }

    HttpResponse<String> serviceGet(String path) throws Exception {
        return get(path, serviceHeaders());
    }

    static Map<String, String> bearer(String token) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        return h;
    }

    static Map<String, Object> body(HttpResponse<String> r) {
        Map<String, Object> m = PhpJson.decodeObject(r.body());
        assertThat(m).as("respuesta JSON: " + r.statusCode() + " " + r.body()).isNotNull();
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> data(HttpResponse<String> r) {
        Map<String, Object> m = body(r);
        assertThat(m).as(r.body()).containsEntry("succeeded", true);
        return (Map<String, Object>) m.get("data");
    }
}
