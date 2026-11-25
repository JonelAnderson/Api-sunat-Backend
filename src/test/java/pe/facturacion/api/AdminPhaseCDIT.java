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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import pe.facturacion.api.modules.admin.security.Totp;
import pe.facturacion.api.modules.settings.PlatformSettings;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.support.FakeSunat;
import pe.facturacion.api.support.IntegrationTest;

/**
 * Fases C y D del panel (§11 del diseño) contra PostgreSQL y Redis reales: alta, edición y eliminación de empresas,
 * credenciales de solo escritura, regenerar con el secreto una sola vez, CRUD de sucursales / series / clientes, y las
 * acciones ante SUNAT (emitir con cupo e idempotencia, reenviar, anular, refrescar, corregir) con su matriz de roles,
 * el aislamiento entre empresas y la auditoría de cada escritura. La cola no corre (los envíos quedan encolados) y la
 * consulta de CDR va a un SUNAT simulado.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    // Clave de prueba sin valor real (≥ 32 bytes)
    "app.admin.jwt-secret=clave-de-prueba-del-panel-admin-0123456789abcdef",
    "app.admin.allowed-origins=https://localhost:4220",
    "app.queue.worker-enabled=false",
    // La empresa cambia su plan por /api/v1/suscripcion (apagado por defecto)
    "app.suscripcion-autoservicio.enabled=true"
})
class AdminPhaseCDIT extends IntegrationTest {

    static final FakeSunat SUNAT;
    static final String PASSWORD = "Prueba#2026";
    static final String RUC_A = "20434343431";
    static final String RUC_B = "20434343432";
    static final String RUC_Q = "20434343433";
    /** Valores de prueba sin valor real: nunca deben volver en una respuesta, la auditoría ni los logs. */
    static final String SOL_USER = "USUPRUEBA9";
    static final String SOL_PASS = "Clave-Prueba-SOL-77";
    static final String CLIENT_ID = "cid-prueba-panel-001";
    static final String CLIENT_SECRET = "csec-prueba-panel-002";

    static {
        try {
            SUNAT = new FakeSunat();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void sunatProps(DynamicPropertyRegistry r) {
        r.add("app.sunat.beta.fe", () -> SUNAT.url("/billService"));
        r.add("app.sunat.beta.consulta-cdr", () -> SUNAT.url("/billService"));
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    TenantRepository tenants;
    @Autowired
    FileStorage storage;
    @Autowired
    PlatformSettings settings;

    final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    Instant clock;

    Map<String, String> authA;
    Map<String, String> authB;
    long tenantA;
    long tenantB;
    long facturaA;
    long facturaB;
    long boletaA;

    @BeforeAll
    void setUp() throws Exception {
        clock = Instant.now();
        Clock.useFixed(clock);
        authA = newTenant(tenants, RUC_A);
        authB = newTenant(tenants, RUC_B);
        tenantA = jdbc.queryForObject("SELECT id FROM tenants WHERE ruc = ?", Long.class, RUC_A);
        tenantB = jdbc.queryForObject("SELECT id FROM tenants WHERE ruc = ?", Long.class, RUC_B);
        prepararSeries(authA);
        prepararSeries(authB);
        facturaA = emitir(authA, "facturas", "F001", "6", "20601234567");
        facturaB = emitir(authB, "facturas", "F001", "6", "20601234567");
        boletaA = emitir(authA, "boletas", "B001", "1", "45678912");
    }

    @AfterAll
    void tearDown() {
        Clock.useSystem();
        jdbc.update("DELETE FROM subscriptions WHERE plan_id IN (SELECT id FROM plans WHERE slug = 'it-cupo')");
        jdbc.update("DELETE FROM plans WHERE slug = 'it-cupo'");
        jdbc.update("DELETE FROM settings WHERE key = ?", PlatformSettings.NUEVAS_EMPRESAS_ILIMITADAS);
        settings.invalidate();
        SUNAT.close();
    }

    @BeforeEach
    void resetRateLimit() {
        redis.delete("throttle:admin-auth:127.0.0.1");
        redis.delete("throttle:admin-auth:0:0:0:0:0:0:0:1");
    }

    // ------------------------------------------------------------------ fase C: alta, edición, logo y eliminación

    @Test
    void alta_con_ruc_valido_credenciales_una_sola_vez_y_409_si_existe() throws Exception {
        String operador = login("operador.alta@example.com", "operador");
        String ruc = validRuc("205555555");
        jdbc.update("DELETE FROM tenants WHERE ruc = ?", ruc);

        Map<String, Object> malo = body(send("POST", "/api/admin/tenants", "{\"ruc\":\"20555555551\",\"razon_social\":\"X\"}", bearer(operador)));
        assertThat(malo).containsEntry("succeeded", false);
        assertThat(String.valueOf(malo.get("errors"))).contains("ruc");
        Map<String, Object> conSecreto = body(send("POST", "/api/admin/tenants", "{\"ruc\":\"" + ruc + "\",\"razon_social\":\"X\","
                + "\"direccion\":\"AV 1\",\"ubigeo\":\"150101\",\"sol_pass\":\"" + SOL_PASS + "\"}", bearer(operador)));
        assertThat(conSecreto).containsEntry("succeeded", false);
        assertThat(String.valueOf(conSecreto.get("errors"))).contains("sol_pass").doesNotContain(SOL_PASS);

        // Las empresas nuevas nacen ilimitadas si la configuración global lo dice (y no se indica el modo)
        settings.setBoolean(PlatformSettings.NUEVAS_EMPRESAS_ILIMITADAS, true);
        settings.invalidate();
        HttpResponse<String> alta = send("POST", "/api/admin/tenants", "{\"ruc\":\"" + ruc + "\",\"razon_social\":\"NUEVA EMPRESA SAC\","
                + "\"direccion\":\"AV. NUEVA 100\",\"ubigeo\":\"150101\",\"emails\":[\"facturas@nueva.pe\"],\"plan\":\"pro\","
                + "\"max_documents_month\":1000}", bearer(operador));
        assertThat(alta.headers().firstValue("Cache-Control")).hasValue("no-store");
        Map<String, Object> data = map(data(alta));
        String apiKey = (String) data.get("apiKey");
        String apiSecret = (String) data.get("apiSecret");
        assertThat(apiKey).hasSize(64);
        assertThat(apiSecret).hasSize(64);
        long id = ((Number) data.get("tenantId")).longValue();
        assertThat(map(data.get("empresa"))).containsEntry("ruc", ruc).containsEntry("razon_social", "NUEVA EMPRESA SAC")
                .containsEntry("plan", "pro").doesNotContainKeys("sol_user", "client_id");
        assertThat(jdbc.queryForObject("SELECT emission_mode FROM tenants WHERE id = ?", String.class, id)).isEqualTo("unlimited");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series WHERE tenant_id = ?", Long.class, id)).isEqualTo(6L);
        // Las credenciales devueltas sirven en /api/v1
        assertThat(get("/api/v1/empresa", Map.of("X-Api-Key", apiKey, "X-Api-Secret", apiSecret)).statusCode()).isEqualTo(200);
        // El secreto no queda en la auditoría ni en la idempotencia
        assertThat(countAudit("admin.empresa.creada", id)).isEqualTo(1L);
        assertNoneContains(apiSecret);
        // RUC repetido → 409
        HttpResponse<String> dup = send("POST", "/api/admin/tenants", "{\"ruc\":\"" + ruc + "\",\"razon_social\":\"OTRA\",\"direccion\":\"AV 2\","
                + "\"ubigeo\":\"150101\"}", bearer(operador));
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(body(dup)).containsEntry("succeeded", false);
    }

    @Test
    void edicion_logo_por_contenido_y_eliminacion_logica_solo_superadmin() throws Exception {
        String operador = login("operador.edicion@example.com", "operador");
        String superadmin = login("super.edicion@example.com", "superadmin");
        String ruc = validRuc("206666666");
        jdbc.update("DELETE FROM tenants WHERE ruc = ?", ruc);
        Map<String, Object> alta = map(data(send("POST", "/api/admin/tenants", "{\"ruc\":\"" + ruc + "\",\"razon_social\":\"EDITABLE SAC\","
                + "\"direccion\":\"AV 1\",\"ubigeo\":\"150101\",\"series_por_defecto\":false}", bearer(operador))));
        long id = ((Number) alta.get("tenantId")).longValue();
        String apiKey = (String) alta.get("apiKey");
        String apiSecret = (String) alta.get("apiSecret");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series WHERE tenant_id = ?", Long.class, id)).isZero();

        Map<String, Object> editada = map(data(send("PUT", "/api/admin/tenants/" + id, "{\"razon_social\":\"EDITADA SAC\","
                + "\"mensaje_agradecimiento\":\"Gracias\",\"motivo\":\"Pedido del cliente\"}", bearer(operador))));
        assertThat(editada).containsEntry("razon_social", "EDITADA SAC").containsEntry("mensaje_agradecimiento", "Gracias");
        String meta = lastAuditMeta("admin.empresa.actualizada", id);
        assertThat(meta).contains("EDITABLE SAC").contains("EDITADA SAC").contains("Pedido del cliente");
        assertThat(body(send("PUT", "/api/admin/tenants/" + id, "{\"client_secret\":\"" + CLIENT_SECRET + "\"}", bearer(operador))))
                .containsEntry("succeeded", false);
        assertThat(body(send("PUT", "/api/admin/tenants/" + id, "{\"ruc\":\"20100070970\"}", bearer(operador)))).containsEntry("succeeded", false);

        // Logo: un SVG con nombre .png se rechaza; un PNG real se guarda con la extensión de su contenido
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        assertThat(body(multipart("POST", "/api/admin/tenants/" + id + "/logo", Map.of(),
                Map.of("logo", new Part("logo.png", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes())), bearer(operador))))
                .containsEntry("succeeded", false);
        Map<String, Object> logo = map(data(multipart("POST", "/api/admin/tenants/" + id + "/logo", Map.of(),
                Map.of("logo", new Part("logo.html", png)), bearer(operador))));
        assertThat(logo).containsEntry("formato", "png");
        assertThat(storage.exists(FileStorage.Disk.PUBLIC, "logos/" + ruc + "/logo.png")).isTrue();
        assertThat(countAudit("admin.empresa.logo", id)).isEqualTo(1L);

        // Eliminar: solo superadmin, con motivo y el RUC escrito
        assertThat(send("DELETE", "/api/admin/tenants/" + id, "{\"motivo\":\"Baja\",\"ruc\":\"" + ruc + "\"}", bearer(operador)).statusCode())
                .isEqualTo(403);
        assertThat(body(send("DELETE", "/api/admin/tenants/" + id, "{\"motivo\":\"Baja\",\"ruc\":\"20000000000\"}", bearer(superadmin))))
                .containsEntry("succeeded", false);
        assertThat(body(send("DELETE", "/api/admin/tenants/" + id, "{\"ruc\":\"" + ruc + "\"}", bearer(superadmin)))).containsEntry("succeeded", false);
        data(send("DELETE", "/api/admin/tenants/" + id, "{\"motivo\":\"Baja solicitada\",\"ruc\":\"" + ruc + "\"}", bearer(superadmin)));
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL AND NOT is_active FROM tenants WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(countAudit("admin.empresa.eliminada", id)).isEqualTo(1L);
        assertThat(get("/api/admin/tenants/" + id + "/empresa", bearer(operador)).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/empresa", Map.of("X-Api-Key", apiKey, "X-Api-Secret", apiSecret)).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ fase C: credenciales

    @Test
    void credenciales_de_solo_escritura_nunca_se_leen() throws Exception {
        String operador = login("operador.cred@example.com", "operador");
        String lectura = login("lectura.cred@example.com", "lectura");
        String base = "/api/admin/tenants/" + tenantA;

        assertThat(body(send("PUT", base + "/credenciales/sol", "{\"sol_user\":\"" + SOL_USER + "\"}", bearer(operador))))
                .containsEntry("succeeded", false);
        HttpResponse<String> sol = send("PUT", base + "/credenciales/sol", "{\"sol_user\":\"" + SOL_USER + "\",\"sol_pass\":\"" + SOL_PASS + "\"}",
                bearer(operador));
        assertThat(map(data(sol))).containsEntry("hasSolUser", true).containsEntry("hasSolPassword", true);
        HttpResponse<String> gre = send("PUT", base + "/credenciales/gre", "{\"client_id\":\"" + CLIENT_ID + "\",\"client_secret\":\""
                + CLIENT_SECRET + "\"}", bearer(operador));
        assertThat(map(data(gre))).containsEntry("hasClientId", true).containsEntry("hasClientSecret", true);
        HttpResponse<String> cert = multipart("POST", base + "/certificado", Map.of("contrasena_certificado", "test1234"),
                Map.of("certificado", new Part("moderno.pfx", resource("/fixtures/certs/moderno.pfx"))), bearer(operador));
        Map<String, Object> certificado = map(map(data(cert)).get("certificate"));
        assertThat(certificado).containsEntry("present", true);
        assertThat(certificado.get("subject")).isNotNull();
        assertThat(certificado.get("validTo")).isNotNull();
        // Formato no admitido y contraseña errónea
        assertThat(body(multipart("POST", base + "/certificado", Map.of("contrasena_certificado", "test1234"),
                Map.of("certificado", new Part("cert.pem", resource("/fixtures/certs/cert-con-clave.pem"))), bearer(operador))))
                .containsEntry("succeeded", false);
        assertThat(body(multipart("POST", base + "/certificado", Map.of("contrasena_certificado", "mala"),
                Map.of("certificado", new Part("moderno.pfx", resource("/fixtures/certs/moderno.pfx"))), bearer(operador))))
                .containsEntry("succeeded", false);

        // Guardado cifrado y sin la contraseña del certificado (H17)
        Tenant t = tenants.findById(tenantA).orElseThrow();
        assertThat(t.getSolPass().reveal()).isEqualTo(SOL_PASS);
        assertThat(t.getClientSecret().reveal()).isEqualTo(CLIENT_SECRET);
        assertThat(t.getCertificatePassword()).isNull();

        // Ninguna lectura devuelve los valores (tampoco a lectura, que sí ve los indicadores)
        for (HttpResponse<String> r : List.of(sol, gre, cert, get(base + "/credenciales", bearer(lectura)), get(base + "/empresa", bearer(lectura)),
                get("/api/admin/tenants/" + tenantA, bearer(lectura)))) {
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).doesNotContain(SOL_USER).doesNotContain(SOL_PASS).doesNotContain(CLIENT_ID).doesNotContain(CLIENT_SECRET)
                    .doesNotContain("test1234");
        }
        assertThat(map(data(get(base + "/credenciales", bearer(lectura))))).containsKeys("hasSolUser", "hasSolPassword", "hasClientId",
                "hasClientSecret", "apiKeyLast4", "certificate");
        for (String accion : List.of("admin.empresa.credenciales_sol", "admin.empresa.credenciales_gre", "admin.empresa.certificado")) {
            assertThat(countAudit(accion, tenantA)).as(accion).isGreaterThanOrEqualTo(1L);
        }
        assertNoneContains(SOL_PASS);
        assertNoneContains(CLIENT_SECRET);
        assertNoneContains(SOL_USER);
        // lectura y soporte no escriben credenciales
        String soporte = login("soporte.cred@example.com", "soporte");
        assertThat(send("PUT", base + "/credenciales/sol", "{\"sol_user\":\"A\",\"sol_pass\":\"B\"}", bearer(lectura)).statusCode()).isEqualTo(403);
        assertThat(send("PUT", base + "/credenciales/gre", "{\"client_id\":\"A\",\"client_secret\":\"B\"}", bearer(soporte)).statusCode()).isEqualTo(403);
    }

    @Test
    void regenerar_devuelve_el_secreto_una_sola_vez_y_nunca_lo_guarda() throws Exception {
        String operador = login("operador.regen@example.com", "operador");
        Map<String, String> auth = newTenant(tenants, "20434343434");
        long id = jdbc.queryForObject("SELECT id FROM tenants WHERE ruc = '20434343434'", Long.class);
        String path = "/api/admin/tenants/" + id + "/credenciales/regenerar";

        assertThat(body(send("POST", path, "{}", bearer(operador)))).containsEntry("succeeded", false);
        HttpResponse<String> r = send("POST", path, "{\"motivo\":\"La empresa perdió su secreto\"}", bearer(operador));
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        Map<String, Object> d = map(data(r));
        String key = (String) d.get("apiKey");
        String secret = (String) d.get("apiSecret");
        assertThat(key).hasSize(64);
        assertThat(secret).hasSize(64);
        assertThat(get("/api/v1/empresa", auth).statusCode()).as("las anteriores ya no sirven").isEqualTo(401);
        assertThat(get("/api/v1/empresa", Map.of("X-Api-Key", key, "X-Api-Secret", secret)).statusCode()).isEqualTo(200);
        // Nunca vuelve a mostrarse
        HttpResponse<String> estado = get("/api/admin/tenants/" + id + "/credenciales", bearer(operador));
        assertThat(estado.body()).doesNotContain(secret).doesNotContain(key);
        assertThat(map(data(estado))).containsEntry("apiKeyLast4", key.substring(60));
        assertThat(countAudit("admin.empresa.credenciales_api", id)).isEqualTo(1L);
        assertThat(lastAuditMeta("admin.empresa.credenciales_api", id)).contains("La empresa perdió su secreto");
        assertNoneContains(secret);

        // H10: el regenerar de /api/v1 tampoco queda en idempotency_keys aunque traiga Idempotency-Key
        Map<String, String> h = new HashMap<>(Map.of("X-Api-Key", key, "X-Api-Secret", secret, "Idempotency-Key", "regen-h10-0001"));
        HttpResponse<String> v1 = send("POST", "/api/v1/empresa/credenciales/regenerar", "{}", h);
        assertThat(v1.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = 'regen-h10-0001'", Long.class)).isZero();
        @SuppressWarnings("unchecked")
        String v1Secret = (String) ((Map<String, Object>) PhpJson.decodeObject(v1.body()).get("datos")).get("api_secret");
        assertNoneContains(v1Secret);
    }

    // ------------------------------------------------------------------ fase C: sucursales, series y clientes

    @Test
    void crud_de_sucursales_series_y_clientes_con_las_reglas_de_v1_y_auditado() throws Exception {
        String operador = login("operador.crud@example.com", "operador");
        String base = "/api/admin/tenants/" + tenantA;
        Map<String, Object> suc = map(data(send("POST", base + "/sucursales", "{\"nombre\":\"Anexo Norte\",\"cod_local\":\"0007\","
                + "\"direccion\":\"AV. NORTE 7\",\"ubigeo\":\"150101\",\"motivo\":\"Apertura\"}", bearer(operador))));
        long sucId = ((Number) suc.get("id")).longValue();
        assertThat(suc).containsEntry("cod_local", "0007").containsEntry("es_principal", false);
        // cod_local repetido: la regla de /api/v1 (409) llega como succeeded:false
        assertThat(body(send("POST", base + "/sucursales", "{\"nombre\":\"Otra\",\"cod_local\":\"0007\",\"direccion\":\"X\",\"ubigeo\":\"150101\"}",
                bearer(operador)))).containsEntry("succeeded", false);
        assertThat(map(data(send("PUT", base + "/sucursales/" + sucId, "{\"nombre\":\"Anexo Norte 2\"}", bearer(operador)))))
                .containsEntry("nombre", "Anexo Norte 2");

        Map<String, Object> creadas = map(data(send("POST", base + "/series", "{\"tipo\":\"factura\",\"serie\":\"F077\",\"sucursal_id\":" + sucId + "}",
                bearer(operador))));
        long serieId = ((Number) map(((List<?>) creadas.get("creadas")).get(0)).get("id")).longValue();
        assertThat(map(data(send("PUT", base + "/series/" + serieId, "{\"correlativo\":5}", bearer(operador))))).containsEntry("siguiente_numero", "F077-6");
        data(send("DELETE", base + "/series/" + serieId, "{}", bearer(operador)));
        assertThat(jdbc.queryForObject("SELECT is_active FROM series WHERE id = ?", Boolean.class, serieId)).isFalse();

        Map<String, Object> cli = map(data(send("POST", base + "/clientes", "{\"tipo_documento\":\"6\",\"numero_documento\":\"20601234567\","
                + "\"razon_social\":\"CLIENTE PANEL SAC\"}", bearer(operador))));
        long cliId = ((Number) cli.get("id")).longValue();
        assertThat(map(data(send("PUT", base + "/clientes/" + cliId, "{\"email\":\"pagos@cliente.pe\"}", bearer(operador)))))
                .containsEntry("email", "pagos@cliente.pe");
        data(send("DELETE", base + "/clientes/" + cliId, null, bearer(operador)));
        // La sucursal con series no se elimina (regla de /api/v1); una vacía sí
        assertThat(body(send("DELETE", base + "/sucursales/" + sucId, null, bearer(operador)))).containsEntry("succeeded", false);
        long vacia = ((Number) map(data(send("POST", base + "/sucursales", "{\"nombre\":\"Temporal\",\"cod_local\":\"0008\","
                + "\"direccion\":\"AV. TEMPORAL 8\",\"ubigeo\":\"150101\"}", bearer(operador)))).get("id")).longValue();
        data(send("DELETE", base + "/sucursales/" + vacia, null, bearer(operador)));

        for (String accion : List.of("admin.sucursal.creada", "admin.sucursal.actualizada", "admin.sucursal.eliminada", "admin.serie.creada",
                "admin.serie.actualizada", "admin.serie.desactivada", "admin.cliente.guardado", "admin.cliente.actualizado", "admin.cliente.eliminado")) {
            assertThat(countAudit(accion, tenantA)).as(accion).isGreaterThanOrEqualTo(1L);
        }
        // La validación de /api/v1 no audita ni escribe
        long antes = countAudit("admin.cliente.guardado", tenantA);
        assertThat(body(send("POST", base + "/clientes", "{\"tipo_documento\":\"9\"}", bearer(operador)))).containsEntry("succeeded", false);
        assertThat(countAudit("admin.cliente.guardado", tenantA)).isEqualTo(antes);
    }

    // ------------------------------------------------------------------ fase D: emitir

    @Test
    void emision_desde_el_panel_con_cupo_del_plan_e_idempotencia() throws Exception {
        String operador = login("operador.emite@example.com", "operador");
        Map<String, String> authQ = newTenant(tenants, RUC_Q);
        long tenantQ = jdbc.queryForObject("SELECT id FROM tenants WHERE ruc = ?", Long.class, RUC_Q);
        prepararSeries(authQ);
        // Producción, modo plan y un plan con cupo de 1 documento al mes
        jdbc.update("UPDATE tenants SET environment = 'production', emission_mode = 'plan' WHERE id = ?", tenantQ);
        jdbc.update("DELETE FROM plans WHERE slug = 'it-cupo'");
        long plan = jdbc.queryForObject("INSERT INTO plans (slug, name, price_monthly, price_yearly, limits, features, is_unlimited, sort_order, "
                + "is_active, created_at, updated_at) VALUES ('it-cupo', 'IT Cupo', 0, 0, '{\"documents_month\":1}'::jsonb, '[]'::jsonb, false, 99, "
                + "true, now(), now()) RETURNING id", Long.class);
        jdbc.update("INSERT INTO subscriptions (tenant_id, plan_id, status, billing_cycle, current_period_start, current_period_end, created_at, "
                + "updated_at) VALUES (?, ?, 'active', 'monthly', now() - interval '1 day', now() + interval '30 day', now(), now())", tenantQ, plan);
        String path = "/api/admin/tenants/" + tenantQ + "/facturas";
        String factura = facturaJson("F001");

        // Sin Idempotency-Key no se emite
        Map<String, Object> sinClave = body(send("POST", path, factura, bearer(operador)));
        assertThat(sinClave).containsEntry("succeeded", false);
        assertThat(String.valueOf(sinClave.get("errors"))).contains("Idempotency-Key");
        assertThat(invoices(tenantQ)).isZero();

        Map<String, String> h1 = bearer(operador);
        h1.put("Idempotency-Key", "it-panel-emite-0001");
        HttpResponse<String> primera = send("POST", path, factura, h1);
        assertThat(primera.headers().firstValue("Idempotency-Replayed")).hasValue("false");
        Map<String, Object> creada = map(data(primera));
        assertThat(creada).containsEntry("numero_completo", "F001-000001").containsKeys("cliente", "totales", "items", "sunat", "archivos");
        // Repetir (doble clic, reintento): misma respuesta, un solo comprobante y sin otra fila de auditoría
        HttpResponse<String> repetida = send("POST", path, factura, h1);
        assertThat(repetida.headers().firstValue("Idempotency-Replayed")).hasValue("true");
        assertThat(((Number) map(data(repetida)).get("id")).longValue()).isEqualTo(((Number) creada.get("id")).longValue());
        assertThat(invoices(tenantQ)).isEqualTo(1L);
        // Misma clave con otro cuerpo
        assertThat(body(send("POST", path, facturaJson("F001").replace("\"cantidad\":1", "\"cantidad\":2"), h1))).containsEntry("succeeded", false);

        // Cupo del plan agotado: el mismo EmissionPolicy de /api/v1
        Map<String, String> h2 = bearer(operador);
        h2.put("Idempotency-Key", "it-panel-emite-0002");
        Map<String, Object> cupo = body(send("POST", path, factura, h2));
        assertThat(cupo).containsEntry("succeeded", false);
        assertThat(String.valueOf(cupo.get("messages"))).contains("limite de documentos");
        assertThat(invoices(tenantQ)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE tenant_id = ? AND idempotency_key = 'panel:it-panel-emite-0002'",
                Long.class, tenantQ)).as("un error libera la clave").isZero();
        assertThat(countAudit("admin.documento.emitido", tenantQ)).isEqualTo(2L);
        assertThat(lastAuditMeta("admin.documento.emitido", tenantQ)).contains("\"resultado\":\"error\"");
        // La empresa usa sus propias claves sin chocar con las del panel
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE tenant_id = ? AND idempotency_key = 'panel:it-panel-emite-0001'",
                Long.class, tenantQ)).isEqualTo(1L);

        // Empresa en beta: emite una boleta desde el panel con el mismo recurso de /api/v1
        Map<String, String> h3 = bearer(operador);
        h3.put("Idempotency-Key", "it-panel-emite-0003");
        Map<String, Object> boleta = map(data(send("POST", "/api/admin/tenants/" + tenantA + "/boletas", facturaJson("B001")
                .replace("\"tipo_doc\":\"6\",\"num_doc\":\"20601234567\"", "\"tipo_doc\":\"1\",\"num_doc\":\"45678912\""), h3)));
        assertThat(boleta).containsEntry("tipo_documento", "03");
    }

    // ------------------------------------------------------------------ fase D: soporte, lectura y aislamiento

    @Test
    void soporte_reenvia_anula_y_refresca_pero_no_emite_ni_edita() throws Exception {
        String soporte = login("soporte.acciones@example.com", "soporte");
        long factura = emitir(authA, "facturas", "F001", "6", "20601234567");
        String doc = "/api/admin/tenants/" + tenantA + "/facturas/" + factura;

        // Reenviar exige motivo
        assertThat(body(send("POST", doc + "/enviar", "{}", bearer(soporte)))).containsEntry("succeeded", false);
        Map<String, Object> enviada = map(data(send("POST", doc + "/enviar", "{\"motivo\":\"El cliente no la recibió\"}", bearer(soporte))));
        assertThat(map(enviada.get("sunat"))).containsEntry("estado", "enviado");
        assertThat(lastAuditMeta("admin.documento.reenviado", tenantA)).contains("El cliente no la recibió").contains("\"documento_id\":" + factura);

        // Anular: llega al servicio de /api/v1 (que exige que esté aceptada): no es 403
        Map<String, Object> anular = body(send("POST", doc + "/anular", "{\"motivo\":\"Error en el RUC\"}", bearer(soporte)));
        assertThat(anular).containsEntry("succeeded", false);
        assertThat(String.valueOf(anular.get("messages"))).contains("no está aceptado");
        assertThat(lastAuditMeta("admin.documento.anulado", tenantA)).contains("\"operacion\":\"RA\"").contains("\"resultado\":\"error\"");

        // Refrescar: consulta el CDR al SUNAT simulado (no lo tiene: queda como estaba)
        Map<String, Object> refresco = body(send("POST", doc + "/refrescar", "{}", bearer(soporte)));
        assertThat(refresco).containsEntry("succeeded", false);
        assertThat(countAudit("admin.documento.refrescado", tenantA)).isGreaterThanOrEqualTo(1L);

        // Nunca emite, corrige ni edita datos o credenciales
        Map<String, String> h = bearer(soporte);
        h.put("Idempotency-Key", "it-soporte-emite-0001");
        assertThat(send("POST", "/api/admin/tenants/" + tenantA + "/facturas", facturaJson("F001"), h).statusCode()).isEqualTo(403);
        assertThat(send("PUT", doc, "{\"observacion\":\"x\",\"motivo\":\"y\"}", bearer(soporte)).statusCode()).isEqualTo(403);
        assertThat(send("PUT", "/api/admin/tenants/" + tenantA, "{\"razon_social\":\"X\"}", bearer(soporte)).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/admin/tenants/" + tenantA + "/sucursales", "{}", bearer(soporte)).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/admin/tenants/" + tenantA + "/credenciales/regenerar", "{\"motivo\":\"x\"}", bearer(soporte)).statusCode())
                .isEqualTo(403);
        assertThat(send("POST", "/api/admin/tenants", "{}", bearer(soporte)).statusCode()).isEqualTo(403);
    }

    @Test
    void lectura_no_escribe_nada() throws Exception {
        String lectura = login("lectura.acciones@example.com", "lectura");
        String t = "/api/admin/tenants/" + tenantA;
        Map<String, String> h = bearer(lectura);
        h.put("Idempotency-Key", "it-lectura-emite-0001");
        for (String[] w : new String[][] {
            {"POST", "/api/admin/tenants"}, {"PUT", t}, {"DELETE", t}, {"POST", t + "/logo"}, {"PUT", t + "/credenciales/sol"},
            {"PUT", t + "/credenciales/gre"}, {"POST", t + "/certificado"}, {"POST", t + "/credenciales/regenerar"}, {"POST", t + "/sucursales"},
            {"POST", t + "/series"}, {"POST", t + "/clientes"}, {"POST", t + "/facturas"}, {"POST", t + "/guias-remision"},
            {"POST", t + "/resumenes"}, {"POST", t + "/facturas/" + facturaA + "/enviar"}, {"POST", t + "/facturas/" + facturaA + "/anular"},
            {"POST", t + "/boletas/" + boletaA + "/anular"}, {"POST", t + "/facturas/" + facturaA + "/refrescar"},
            {"PUT", t + "/facturas/" + facturaA}}) {
            assertThat(send(w[0], w[1], "{\"motivo\":\"x\"}", h).statusCode()).as(w[0] + " " + w[1]).isEqualTo(403);
        }
        assertThat(get(t + "/buscar-documento?tipo=6&numero=20601234567", bearer(lectura)).statusCode()).isEqualTo(403);
        assertThat(get("/api/admin/tenants/ruc-lookup?ruc=20100070970", bearer(lectura)).statusCode()).isEqualTo(403);
        assertThat(get("/api/admin/catalogos/ubigeo/regiones", bearer(lectura)).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT sunat_status FROM invoices WHERE id = ?", String.class, facturaA)).isEqualTo("pendiente");
    }

    @Test
    void documentos_de_otra_empresa_responden_404() throws Exception {
        String operador = login("operador.aislado@example.com", "operador");
        String a = "/api/admin/tenants/" + tenantA;
        for (String[] w : new String[][] {
            {"POST", a + "/facturas/" + facturaB + "/enviar", "{\"motivo\":\"x\"}"},
            {"POST", a + "/facturas/" + facturaB + "/anular", "{\"motivo\":\"x\"}"},
            {"POST", a + "/facturas/" + facturaB + "/refrescar", "{}"},
            {"PUT", a + "/facturas/" + facturaB, "{\"observacion\":\"x\",\"motivo\":\"y\"}"},
            {"POST", a + "/anulaciones/999999/enviar", "{\"motivo\":\"x\"}"},
            {"POST", a + "/resumenes/999999/refrescar", "{}"},
            {"PUT", a + "/sucursales/" + sucursalDe(tenantB), "{\"nombre\":\"X\"}"},
            {"PUT", a + "/series/" + serieDe(tenantB), "{\"activo\":false}"}}) {
            HttpResponse<String> r = send(w[0], w[1], w[2], bearer(operador));
            assertThat(r.statusCode()).as(w[0] + " " + w[1]).isEqualTo(404);
            assertThat(body(r)).containsEntry("succeeded", false);
        }
        assertThat(jdbc.queryForObject("SELECT sunat_status FROM invoices WHERE id = ?", String.class, facturaB)).isEqualTo("pendiente");
        assertThat(jdbc.queryForObject("SELECT is_active FROM series WHERE id = ?", Boolean.class, serieDe(tenantB))).isTrue();
        assertThat(send("POST", "/api/admin/tenants/99999999/facturas/1/enviar", "{\"motivo\":\"x\"}", bearer(operador)).statusCode()).isEqualTo(404);
    }

    @Test
    void corregir_un_rechazado_exige_motivo_y_se_audita() throws Exception {
        String operador = login("operador.corrige@example.com", "operador");
        long factura = emitir(authA, "facturas", "F001", "6", "20601234567");
        jdbc.update("UPDATE invoices SET sunat_status = 'rechazado', sunat_code = '2017' WHERE id = ?", factura);
        String doc = "/api/admin/tenants/" + tenantA + "/facturas/" + factura;
        assertThat(body(send("PUT", doc, "{\"observacion\":\"Corregida\"}", bearer(operador)))).containsEntry("succeeded", false);
        Map<String, Object> corregida = map(data(send("PUT", doc, "{\"observacion\":\"Corregida\",\"motivo\":\"RUC del cliente corregido\"}",
                bearer(operador))));
        assertThat(corregida).containsEntry("observacion", "Corregida");
        assertThat(map(corregida.get("sunat")).get("estado")).isIn("pendiente", "enviado");
        assertThat(lastAuditMeta("admin.documento.corregido", tenantA)).contains("RUC del cliente corregido").doesNotContain("\"motivo_panel\"");
        // Suspendida: el panel no opera sus comprobantes
        jdbc.update("UPDATE tenants SET is_active = false WHERE id = ?", tenantA);
        try {
            Map<String, Object> suspendida = body(send("POST", doc + "/enviar", "{\"motivo\":\"x\"}", bearer(operador)));
            assertThat(suspendida).containsEntry("succeeded", false);
            assertThat(String.valueOf(suspendida.get("messages"))).contains("suspendida");
        } finally {
            jdbc.update("UPDATE tenants SET is_active = true WHERE id = ?", tenantA);
        }
    }

    // ------------------------------------------------------------------ §12: plan del panel y datos de edición

    @Test
    void el_plan_del_panel_es_el_efectivo_y_su_cupo_es_el_que_se_aplica() throws Exception {
        String operador = login("operador.plan@example.com", "operador");
        String ruc = validRuc("204545454");
        jdbc.update("DELETE FROM tenants WHERE ruc = ?", ruc);
        Map<String, String> auth = newTenant(tenants, ruc);
        long id = jdbc.queryForObject("SELECT id FROM tenants WHERE ruc = ?", Long.class, ruc);
        String path = "/api/admin/tenants/" + id + "/plan";
        // Una reducción programada que el panel debe cancelar
        jdbc.update("INSERT INTO subscriptions (tenant_id, plan_id, status, billing_cycle, current_period_start, created_at, updated_at) "
                + "SELECT ?, id, 'pending', 'monthly', now() + interval '10 day', now(), now() FROM plans WHERE slug = 'free'", id);

        Map<String, Object> pro = map(data(send("PUT", path, "{\"plan\":\"pro\",\"maxDocumentsMonth\":50,\"motivo\":\"Acuerdo comercial\"}",
                bearer(operador))));
        assertThat(pro).containsEntry("plan", "pro").containsEntry("effectivePlan", "pro").containsEntry("planInSync", true)
                .containsEntry("maxDocumentsMonth", 50);
        assertThat(map(pro.get("subscription"))).containsEntry("plan", "pro").containsEntry("status", "active").containsEntry("setByPanel", true)
                .containsEntry("periodEnd", null);
        assertThat(map(pro.get("quota"))).containsEntry("documentsMonth", 50).containsEntry("source", "panel").containsEntry("override", 50);
        assertThat(pro.get("pendingSubscription")).isNull();
        // /api/v1 lo ve igual: suscripción, uso y cabeceras (mismos formatos)
        Map<String, Object> v1 = map(PhpJson.decodeObject(get("/api/v1/suscripcion", auth).body()).get("datos"));
        assertThat(map(map(v1.get("suscripcion")).get("plan"))).containsEntry("slug", "pro");
        assertThat(map(map(map(v1.get("uso")).get("usage")).get("documents_month"))).containsEntry("limit", 50).containsEntry("unlimited", false);
        assertThat(get("/api/v1/sunat/estado", auth).headers().firstValue("X-Plan")).hasValue("pro");
        assertThat(lastAuditMeta("admin.empresa.plan_cambiado", id)).contains("\"suscripcion_accion\":\"creada\"")
                .contains("\"pendientes_canceladas\":1").contains("Acuerdo comercial");

        // Mismo plan sin cupo: se conserva la suscripción y el cupo vuelve a ser el del plan
        Map<String, Object> igual = map(data(send("PUT", path, "{\"plan\":\"pro\"}", bearer(operador))));
        assertThat(map(igual.get("subscription")).get("id")).isEqualTo(map(pro.get("subscription")).get("id"));
        assertThat(map(igual.get("quota"))).containsEntry("source", "plan").containsEntry("override", null);
        assertThat(lastAuditMeta("admin.empresa.plan_cambiado", id)).contains("\"suscripcion_accion\":\"conservada\"");

        // Cupo agotado en producción: EmissionPolicy usa el cupo del panel
        jdbc.update("UPDATE tenants SET environment = 'production', emission_mode = 'plan' WHERE id = ?", id);
        send("PUT", path, "{\"plan\":\"pro\",\"maxDocumentsMonth\":0}", bearer(operador));
        HttpResponse<String> emitir = send("POST", "/api/v1/facturas", facturaJson("F001"), auth);
        assertThat(emitir.statusCode()).isEqualTo(429);
        assertThat(emitir.body()).contains("(0/0 por mes)");
        // La empresa cambia su plan por /api/v1: rige ese plan y se borra el cupo del panel
        jdbc.update("UPDATE tenants SET environment = 'beta' WHERE id = ?", id);
        assertThat(send("POST", "/api/v1/suscripcion", "{\"plan_slug\":\"business\"}", auth).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT documents_month_override FROM tenants WHERE id = ?", Integer.class, id)).isNull();
        Map<String, Object> detalle = map(data(get("/api/admin/tenants/" + id, bearer(operador))));
        assertThat(detalle).containsEntry("effectivePlan", "business");
        assertThat(map(detalle.get("subscription"))).containsEntry("setByPanel", false).containsEntry("status", "trialing");
    }

    @Test
    void el_detalle_de_una_guia_trae_el_cuerpo_completo_del_put() throws Exception {
        String operador = login("operador.guia@example.com", "operador");
        String guia = "{\"serie\":\"T001\",\"fecha_emision\":\"" + Clock.todayLima() + "\",\"enviar_automatico\":false,"
                + "\"destinatario\":{\"tipo_doc\":\"6\",\"num_doc\":\"20601234567\",\"razon_social\":\"CLIENTE SAC\"},"
                + "\"cod_traslado\":\"01\",\"mod_traslado\":\"02\",\"fecha_traslado\":\"" + Clock.todayLima() + "\",\"peso_total\":12.5,"
                + "\"num_bultos\":2,\"partida_ubigeo\":\"150122\",\"partida_direccion\":\"JR. ALMACEN 200\",\"partida_ruc\":\"" + RUC_A + "\","
                + "\"partida_cod_local\":\"0000\",\"llegada_ubigeo\":\"150101\",\"llegada_direccion\":\"AV. LIMA 1\","
                + "\"vehiculo\":{\"placa\":\"ABC123\",\"tuc\":\"TUC1234567\"},"
                + "\"conductor\":{\"tipo_doc\":\"1\",\"num_doc\":\"45678912\",\"nombres\":\"JUAN\",\"apellidos\":\"PEREZ\",\"licencia\":\"Q45678912\"},"
                + "\"items\":[{\"codigo\":\"P1\",\"descripcion\":\"Producto\",\"unidad\":\"NIU\",\"cantidad\":5}]}";
        if (jdbc.queryForObject("SELECT count(*) FROM series WHERE tenant_id = ? AND serie = 'T001'", Long.class, tenantA) == 0) {
            HttpResponse<String> serie = send("POST", "/api/v1/series", "{\"tipo\":\"guia_remision\",\"serie\":\"T001\",\"sucursal_id\":"
                    + sucursalDe(tenantA) + "}", authA);
            assertThat(serie.statusCode()).as(serie.body()).isEqualTo(201);
        }
        HttpResponse<String> creada = send("POST", "/api/v1/guias-remision", guia, authA);
        assertThat(creada.statusCode()).as(creada.body()).isEqualTo(201);
        long guiaId = ((Number) map(PhpJson.decodeObject(creada.body()).get("datos")).get("id")).longValue();
        jdbc.update("UPDATE dispatch_guides SET sunat_status = 'rechazado' WHERE id = ?", guiaId);
        String doc = "/api/admin/tenants/" + tenantA + "/guias-remision/" + guiaId;

        Map<String, Object> detalle = map(data(get(doc, bearer(operador))));
        Map<String, Object> edicion = map(detalle.get("datos_edicion"));
        assertThat(edicion).containsEntry("partida_ruc", RUC_A).containsEntry("partida_cod_local", "0000").containsEntry("num_bultos", 2)
                .containsKeys("remitente", "doc_relacionado", "indicadores", "datos_pagador_flete", "tercero").doesNotContainKey("correlativo");
        assertThat(map(edicion.get("vehiculo"))).containsEntry("tuc", "TUC1234567");

        // El cuerpo tal cual (más el motivo) sirve para corregir y reenviar
        edicion.put("observacion", "Corregida desde el panel");
        edicion.put("motivo", "Rechazo de SUNAT");
        Map<String, Object> corregida = map(data(send("PUT", doc, PhpJson.encode(edicion), bearer(operador))));
        assertThat(corregida).containsEntry("observacion", "Corregida desde el panel");
        assertThat(jdbc.queryForObject("SELECT partida_ruc FROM dispatch_guides WHERE id = ?", String.class, guiaId)).isEqualTo(RUC_A);

        // Factura: datos_edicion con la afectación de cada ítem
        Map<String, Object> factura = map(data(get("/api/admin/tenants/" + tenantA + "/facturas/" + facturaA, bearer(operador))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) map(factura.get("datos_edicion")).get("items");
        assertThat(items.get(0)).containsEntry("tip_afe_igv", "10").containsKeys("precio_unitario", "porcentaje_igv", "cod_producto_sunat");
    }

    // ------------------------------------------------------------------ apoyo

    long invoices(long tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM invoices WHERE tenant_id = ?", Long.class, tenant);
    }

    long sucursalDe(long tenant) {
        return jdbc.queryForObject("SELECT min(id) FROM sucursales WHERE tenant_id = ?", Long.class, tenant);
    }

    long serieDe(long tenant) {
        return jdbc.queryForObject("SELECT min(id) FROM series WHERE tenant_id = ?", Long.class, tenant);
    }

    long countAudit(String accion, long tenant) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = ? AND tenant_id = ?", Long.class, accion, tenant);
        return n == null ? 0 : n;
    }

    String lastAuditMeta(String accion, long tenant) {
        return jdbc.queryForObject("SELECT coalesce(metadata::text, '') || ' ' || coalesce(descripcion, '') FROM audit_logs "
                + "WHERE accion = ? AND tenant_id = ? ORDER BY id DESC LIMIT 1", String.class, accion, tenant);
    }

    /** Un secreto nunca queda en la auditoría, en las respuestas guardadas para idempotencia ni en api_logs. */
    void assertNoneContains(String secret) {
        String like = "%" + secret + "%";
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE metadata::text LIKE ? OR descripcion LIKE ?", Long.class, like, like))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE response_body LIKE ?", Long.class, like)).isZero();
    }

    /** RUC de prueba con su dígito verificador (prefijo de 9 dígitos que empieza en 20). */
    static String validRuc(String nine) {
        String base = nine + "0";
        int[] w = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += (base.charAt(i) - '0') * w[i];
        }
        int r = 11 - (sum % 11);
        return base + (r == 10 ? 0 : r == 11 ? 1 : r);
    }

    static String facturaJson(String serie) {
        return "{\"serie\":\"" + serie + "\",\"fecha_emision\":\"" + Clock.todayLima() + "\",\"enviar_automatico\":false,"
                + "\"cliente\":{\"tipo_doc\":\"6\",\"num_doc\":\"20601234567\",\"razon_social\":\"CLIENTE SAC\",\"direccion\":\"AV. LIMA 1\"},"
                + "\"items\":[{\"codigo\":\"P1\",\"descripcion\":\"Producto\",\"unidad\":\"NIU\",\"cantidad\":1,\"precio_unitario\":118,\"tip_afe_igv\":\"10\"}]}";
    }

    void prepararSeries(Map<String, String> auth) throws Exception {
        HttpResponse<String> suc = send("POST", "/api/v1/sucursales",
                "{\"nombre\":\"Principal\",\"cod_local\":\"0000\",\"direccion\":\"Av 1\",\"ubigeo\":\"150101\"}", auth);
        assertThat(suc.statusCode()).as(suc.body()).isEqualTo(201);
        HttpResponse<String> init = send("POST", "/api/v1/series/init-defaults", "{}", auth);
        assertThat(init.statusCode()).as(init.body()).isEqualTo(200);
    }

    /** Factura o boleta pendiente (sin envío a SUNAT) emitida por /api/v1; devuelve su id. */
    long emitir(Map<String, String> auth, String tipo, String serie, String tipoDoc, String numDoc) throws Exception {
        String json = facturaJson(serie).replace("\"tipo_doc\":\"6\",\"num_doc\":\"20601234567\"",
                "\"tipo_doc\":\"" + tipoDoc + "\",\"num_doc\":\"" + numDoc + "\"");
        HttpResponse<String> f = send("POST", "/api/v1/" + tipo, json, auth);
        assertThat(f.statusCode()).as(f.body()).isEqualTo(201);
        @SuppressWarnings("unchecked")
        Map<String, Object> datos = (Map<String, Object>) PhpJson.decodeObject(f.body()).get("datos");
        return ((Number) datos.get("id")).longValue();
    }

    String login(String email, String role) throws Exception {
        jdbc.update("DELETE FROM users WHERE lower(email) = lower(?)", email);
        jdbc.update("INSERT INTO users (name, email, password, is_admin, role, is_active, created_at, updated_at) "
                + "VALUES (?, ?, ?, true, ?, true, now(), now())", "Admin " + role, email, bcrypt.encode(PASSWORD), role);
        resetRateLimit();
        Map<String, Object> login = map(data(send("POST", "/api/admin/auth/login",
                "{\"userName\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", Map.of())));
        String ticket = (String) login.get("mfaTicket");
        String secret = ((String) map(data(send("POST", "/api/admin/auth/login/mfa/setup", "{\"mfaTicket\":\"" + ticket + "\"}", Map.of())))
                .get("sharedKey")).replace(" ", "");
        clock = clock.plus(Duration.ofSeconds(30));
        Clock.useFixed(clock);
        Map<String, Object> confirm = map(data(send("POST", "/api/admin/auth/login/mfa/setup/confirm",
                "{\"mfaTicket\":\"" + ticket + "\",\"code\":\"" + Totp.code(secret, Clock.now()) + "\"}", Map.of())));
        resetRateLimit();
        return (String) confirm.get("token");
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

    static Object data(HttpResponse<String> r) {
        Map<String, Object> m = body(r);
        assertThat(m).as(r.body()).containsEntry("succeeded", true);
        return m.get("data");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }
}
