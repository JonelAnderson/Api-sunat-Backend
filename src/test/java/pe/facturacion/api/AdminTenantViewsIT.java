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
 * Vistas de empresa del panel ({@code /api/admin/tenants/{id}/...}) contra PostgreSQL y Redis reales: devuelven el mismo
 * recurso que {@code /api/v1}, nunca datos de otra empresa (404), descargas con los mismos bytes, sin secretos,
 * auditadas y legibles por el rol {@code lectura}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    // Clave de prueba sin valor real (≥ 32 bytes)
    "app.admin.jwt-secret=clave-de-prueba-del-panel-admin-0123456789abcdef",
    "app.admin.allowed-origins=https://localhost:4220",
    "app.queue.worker-enabled=false"
})
class AdminTenantViewsIT extends IntegrationTest {

    static final String PASSWORD = "Prueba#2026";
    static final String RUC_A = "20888888881";
    static final String RUC_B = "20888888882";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    TenantRepository tenants;

    final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    Instant clock;

    Map<String, String> authA;
    Map<String, String> authB;
    long tenantA;
    long tenantB;
    long facturaA;
    long facturaB;

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
    void resetRateLimit() {
        redis.delete("throttle:admin-auth:127.0.0.1");
        redis.delete("throttle:admin-auth:0:0:0:0:0:0:0:1");
    }

    // ------------------------------------------------------------------ aislamiento entre empresas

    @Test
    void nunca_devuelve_documentos_de_otra_empresa() throws Exception {
        String token = login("lectura.aislamiento@example.com", "lectura");

        Map<String, Object> list = body(get("/api/admin/tenants/" + tenantA + "/facturas?records=50", bearer(token)));
        assertThat(list).containsEntry("succeeded", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list.get("data");
        assertThat(rows).extracting(r -> ((Number) r.get("id")).longValue()).contains(facturaA).doesNotContain(facturaB);
        assertThat(((Number) list.get("totalRecords")).longValue()).isEqualTo(rows.size());

        // Documento de la empresa B pedido con el id de la empresa A → 404 con el sobre
        HttpResponse<String> ajeno = get("/api/admin/tenants/" + tenantA + "/facturas/" + facturaB, bearer(token));
        assertThat(ajeno.statusCode()).isEqualTo(404);
        assertThat(body(ajeno)).containsEntry("succeeded", false).containsEntry("data", null);
        for (String sub : List.of("/estado", "/pagos")) {
            assertThat(get("/api/admin/tenants/" + tenantA + "/facturas/" + facturaB + sub, bearer(token)).statusCode()).as(sub).isEqualTo(404);
        }
        // Con su propia empresa sí aparece
        assertThat(get("/api/admin/tenants/" + tenantB + "/facturas/" + facturaB, bearer(token)).statusCode()).isEqualTo(200);

        // Empresa inexistente → 404
        HttpResponse<String> sinEmpresa = get("/api/admin/tenants/999999999/facturas", bearer(token));
        assertThat(sinEmpresa.statusCode()).isEqualTo(404);
        assertThat(body(sinEmpresa)).containsEntry("succeeded", false);
    }

    @Test
    void las_descargas_de_documentos_ajenos_responden_404() throws Exception {
        String token = login("lectura.descargas@example.com", "lectura");
        for (String archivo : List.of("xml", "cdr", "pdf")) {
            HttpResponse<String> r = get("/api/admin/tenants/" + tenantA + "/facturas/" + facturaB + "/" + archivo, bearer(token));
            assertThat(r.statusCode()).as(archivo).isEqualTo(404);
            assertThat(body(r)).as(archivo).containsEntry("succeeded", false);
        }
        for (String path : List.of("/guias-remision/" + Long.MAX_VALUE + "/xml", "/retenciones/" + Long.MAX_VALUE + "/pdf",
                "/liquidaciones-compra/" + Long.MAX_VALUE + "/cdr", "/resumenes/" + Long.MAX_VALUE + "/xml",
                "/anulaciones/" + Long.MAX_VALUE + "/cdr")) {
            assertThat(get("/api/admin/tenants/" + tenantA + path, bearer(token)).statusCode()).as(path).isEqualTo(404);
        }
    }

    // ------------------------------------------------------------------ mismo recurso y mismos archivos

    @Test
    void el_detalle_y_las_descargas_son_los_mismos_de_la_api() throws Exception {
        String token = login("lectura.paridad@example.com", "lectura");

        Map<String, Object> v1 = PhpJson.decodeObject(get("/api/v1/facturas/" + facturaA, authA).body());
        Map<String, Object> admin = body(get("/api/admin/tenants/" + tenantA + "/facturas/" + facturaA, bearer(token)));
        // El recurso es el mismo de /api/v1 más datos_edicion (§12.1), que solo agrega el panel
        @SuppressWarnings("unchecked")
        Map<String, Object> detalle = new java.util.LinkedHashMap<>((Map<String, Object>) admin.get("data"));
        assertThat(detalle.remove("datos_edicion")).isInstanceOf(Map.class);
        assertThat(detalle).isEqualTo(v1.get("datos"));

        @SuppressWarnings("unchecked")
        Map<String, Object> estado = (Map<String, Object>) body(get("/api/admin/tenants/" + tenantA + "/facturas/" + facturaA + "/estado",
                bearer(token))).get("data");
        assertThat(estado).containsOnlyKeys("id", "tipo_documento", "numero_completo", "sunat", "enviado_en");

        @SuppressWarnings("unchecked")
        Map<String, Object> pagos = (Map<String, Object>) body(get("/api/admin/tenants/" + tenantA + "/facturas/" + facturaA + "/pagos",
                bearer(token))).get("data");
        assertThat(pagos).containsKeys("datos", "estado_pago", "monto_pagado", "total_documento");

        // PDF: mismos bytes, tipo y nombre que la descarga de la empresa (la caché de PDF hace que sean idénticos)
        HttpResponse<byte[]> pdfV1 = getBytes("/api/v1/facturas/" + facturaA + "/pdf", authA);
        HttpResponse<byte[]> pdfAdmin = getBytes("/api/admin/tenants/" + tenantA + "/facturas/" + facturaA + "/pdf", bearer(token));
        assertThat(pdfAdmin.statusCode()).isEqualTo(200);
        assertThat(pdfAdmin.headers().firstValue("Content-Type")).contains("application/pdf");
        assertThat(pdfAdmin.headers().firstValue("Content-Disposition")).isEqualTo(pdfV1.headers().firstValue("Content-Disposition"));
        assertThat(pdfAdmin.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(pdfAdmin.body()).isEqualTo(pdfV1.body());

        // XML: si la empresa lo tiene, mismos bytes; si no, 404 con el sobre
        HttpResponse<byte[]> xmlV1 = getBytes("/api/v1/facturas/" + facturaA + "/xml", authA);
        HttpResponse<byte[]> xmlAdmin = getBytes("/api/admin/tenants/" + tenantA + "/facturas/" + facturaA + "/xml", bearer(token));
        assertThat(xmlAdmin.statusCode()).isEqualTo(xmlV1.statusCode());
        if (xmlV1.statusCode() == 200) {
            assertThat(xmlAdmin.body()).isEqualTo(xmlV1.body());
            assertThat(xmlAdmin.headers().firstValue("Content-Type")).contains("application/xml");
        } else {
            assertThat(new String(xmlAdmin.body(), java.nio.charset.StandardCharsets.UTF_8)).contains("\"succeeded\":false");
        }

        // Formato inválido: error de validación con el sobre (HTTP 200, succeeded:false)
        assertThat(body(get("/api/admin/tenants/" + tenantA + "/facturas/" + facturaA + "/pdf?formato=carta", bearer(token))))
                .containsEntry("succeeded", false);
    }

    // ------------------------------------------------------------------ sin secretos

    @Test
    void la_empresa_y_la_configuracion_no_llevan_secretos() throws Exception {
        String token = login("lectura.secretos@example.com", "lectura");
        String apiKey = authA.get("X-Api-Key");
        String apiSecret = authA.get("X-Api-Secret");
        for (String path : List.of("/empresa", "/sucursales", "/series", "/clientes", "/suscripcion", "/suscripcion/pagos",
                "/suscripcion/uso", "/panel", "/facturas", "/facturas/" + facturaA)) {
            HttpResponse<String> r = get("/api/admin/tenants/" + tenantA + path, bearer(token));
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            assertThat(body(r)).as(path).containsEntry("succeeded", true);
            assertThat(r.body()).as(path).doesNotContain(apiKey).doesNotContain(apiSecret).doesNotContain("MODDATOS")
                    .doesNotContain("\"sol_user\"").doesNotContain("\"sol_pass\"").doesNotContain("\"client_id\"")
                    .doesNotContain("\"client_secret\"").doesNotContain("\"api_secret\"").doesNotContain("\"api_key\"")
                    .doesNotContain("\"consulta_token\"").doesNotContain("\"certificate_password\"").doesNotContain("BEGIN");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> empresa = (Map<String, Object>) body(get("/api/admin/tenants/" + tenantA + "/empresa", bearer(token))).get("data");
        assertThat(empresa).containsEntry("ruc", RUC_A).containsKeys("tiene_sol_pass", "tiene_client_secret", "tiene_certificado");
        // Las credenciales de la empresa no tienen vista en el panel
        assertThat(get("/api/admin/tenants/" + tenantA + "/credenciales", bearer(token)).statusCode()).isEqualTo(404);
        assertThat(get("/api/admin/tenants/" + tenantA + "/empresa/credenciales", bearer(token)).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ resto de vistas

    @Test
    void lectura_puede_ver_todas_las_vistas() throws Exception {
        String token = login("lectura.vistas@example.com", "lectura");
        String hoy = Clock.todayLima().toString();
        for (String path : List.of("/boletas", "/notas-credito", "/notas-debito", "/guias-remision", "/retenciones", "/percepciones",
                "/liquidaciones-compra", "/resumenes", "/anulaciones", "/panel/indicadores", "/panel/estado-sunat", "/panel/cobranzas",
                "/panel/ventas-mensuales", "/panel/por-sucursal", "/panel/por-moneda", "/panel/clientes", "/panel/productos",
                "/panel/documentos-recientes?limit=5", "/panel/alertas", "/panel?mes=" + hoy.substring(0, 7),
                "/reportes/registro-ventas?fecha_desde=" + hoy + "&fecha_hasta=" + hoy,
                "/reportes/ventas-consolidado?fecha_desde=" + hoy + "&fecha_hasta=" + hoy,
                "/reportes/por-cliente?fecha_desde=" + hoy + "&fecha_hasta=" + hoy + "&client_num_doc=20601234567",
                "/reportes/retenciones?periodo=" + hoy.substring(0, 7))) {
            HttpResponse<String> r = get("/api/admin/tenants/" + tenantA + path, bearer(token));
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            assertThat(body(r)).as(path + " " + r.body()).containsEntry("succeeded", true);
        }
        // El panel del mes es el mismo de /api/v1/panel
        Map<String, Object> v1 = PhpJson.decodeObject(get("/api/v1/panel/estado-sunat", authA).body());
        assertThat(body(get("/api/admin/tenants/" + tenantA + "/panel/estado-sunat", bearer(token))).get("data")).isEqualTo(v1.get("datos"));

        // Validación de los filtros de /api/v1 con el sobre del panel
        Map<String, Object> sinFechas = body(get("/api/admin/tenants/" + tenantA + "/reportes/registro-ventas", bearer(token)));
        assertThat(sinFechas).containsEntry("succeeded", false);
        assertThat((List<?>) sinFechas.get("errors")).isNotEmpty();
        assertThat(body(get("/api/admin/tenants/" + tenantA + "/panel?mes=2026-13", bearer(token)))).containsEntry("succeeded", false);
        // Reportes del panel: solo JSON aunque se pida PDF
        HttpResponse<String> pdf = get("/api/admin/tenants/" + tenantA + "/reportes/registro-ventas?formato=pdf&fecha_desde=" + hoy
                + "&fecha_hasta=" + hoy, bearer(token));
        assertThat(pdf.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));
    }

    // ------------------------------------------------------------------ auditoría

    @Test
    void cada_consulta_y_descarga_queda_auditada() throws Exception {
        String email = "lectura.auditoria@example.com";
        String token = login(email, "lectura");
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);

        assertThat(get("/api/admin/tenants/" + tenantA + "/facturas/" + facturaA, bearer(token)).statusCode()).isEqualTo(200);
        assertThat(getBytes("/api/admin/tenants/" + tenantA + "/facturas/" + facturaA + "/pdf", bearer(token)).statusCode()).isEqualTo(200);
        Long detalle = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'admin.empresa.consulta' AND actor_tipo = 'admin' "
                + "AND user_id = ? AND actor_etiqueta = ? AND tenant_id = ? AND metadata->>'vista' = 'facturas.detalle' "
                + "AND metadata->>'tipo' = '01' AND (metadata->>'documento_id')::bigint = ?", Long.class, userId, email, tenantA, facturaA);
        assertThat(detalle).isEqualTo(1L);
        Long descarga = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'admin.empresa.descarga' AND user_id = ? "
                + "AND tenant_id = ? AND metadata->>'archivo' = 'pdf' AND (metadata->>'documento_id')::bigint = ?", Long.class, userId, tenantA, facturaA);
        assertThat(descarga).isEqualTo(1L);

        // Listados: una fila por vista en la ventana de 5 minutos, aunque se pida otra página
        for (int page = 1; page <= 3; page++) {
            assertThat(get("/api/admin/tenants/" + tenantA + "/facturas?numPage=" + page, bearer(token)).statusCode()).isEqualTo(200);
        }
        Long listados = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'admin.empresa.consulta' AND user_id = ? "
                + "AND tenant_id = ? AND metadata->>'vista' = 'facturas'", Long.class, userId, tenantA);
        assertThat(listados).isEqualTo(1L);

        // Un 404 (documento ajeno) no deja fila de consulta
        get("/api/admin/tenants/" + tenantA + "/facturas/" + facturaB, bearer(token));
        Long ajenos = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE user_id = ? AND (metadata->>'documento_id')::bigint = ?",
                Long.class, userId, facturaB);
        assertThat(ajenos).isZero();
    }

    @Test
    void sin_token_responde_401_y_solo_get() throws Exception {
        assertThat(get("/api/admin/tenants/" + tenantA + "/facturas", Map.of()).statusCode()).isEqualTo(401);
        String token = login("lectura.metodos@example.com", "lectura");
        // Las vistas son de solo lectura: no existe ninguna escritura sobre los comprobantes de la empresa
        HttpResponse<String> post = send("POST", "/api/admin/tenants/" + tenantA + "/facturas", "{}", bearer(token));
        assertThat(post.statusCode()).isIn(403, 405);
        assertThat(PhpJson.decodeObject(get("/api/v1/facturas/" + facturaA, authA).body())).containsEntry("estado", "exito");
    }

    // ------------------------------------------------------------------ apoyo

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

    String login(String email, String role) throws Exception {
        jdbc.update("DELETE FROM users WHERE lower(email) = lower(?)", email);
        jdbc.update("INSERT INTO users (name, email, password, is_admin, role, is_active, created_at, updated_at) "
                + "VALUES (?, ?, ?, true, ?, true, now(), now())", "Admin " + role, email, bcrypt.encode(PASSWORD), role);
        resetRateLimit();
        Map<String, Object> login = data(send("POST", "/api/admin/auth/login",
                "{\"userName\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", Map.of()));
        String ticket = (String) login.get("mfaTicket");
        String secret = ((String) data(send("POST", "/api/admin/auth/login/mfa/setup", "{\"mfaTicket\":\"" + ticket + "\"}", Map.of()))
                .get("sharedKey")).replace(" ", "");
        clock = clock.plus(Duration.ofSeconds(30));
        Clock.useFixed(clock);
        Map<String, Object> confirm = data(send("POST", "/api/admin/auth/login/mfa/setup/confirm",
                "{\"mfaTicket\":\"" + ticket + "\",\"code\":\"" + Totp.code(secret, Clock.now()) + "\"}", Map.of()));
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

    @SuppressWarnings("unchecked")
    static Map<String, Object> data(HttpResponse<String> r) {
        Map<String, Object> m = body(r);
        assertThat(m).as(r.body()).containsEntry("succeeded", true);
        return (Map<String, Object>) m.get("data");
    }
}
