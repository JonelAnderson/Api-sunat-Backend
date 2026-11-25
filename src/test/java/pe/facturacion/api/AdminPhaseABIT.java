package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
import pe.facturacion.api.modules.settings.PlatformSettings;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.support.IntegrationTest;

/**
 * Fases A y B del panel (§10 del diseño) contra PostgreSQL y Redis reales: feed de envíos y vista unificada, sucursales
 * y series globales, tablero ampliado, constancia de anulación, PDF en línea, ZIP en streaming con topes, consultas en
 * vivo a SUNAT auditadas, planes (soporte no escribe, operador no elimina), configuración global con el token de
 * consulta de solo escritura y el perfil propio.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    // Clave de prueba sin valor real (≥ 32 bytes)
    "app.admin.jwt-secret=clave-de-prueba-del-panel-admin-0123456789abcdef",
    "app.admin.allowed-origins=https://localhost:4220",
    "app.queue.worker-enabled=false"
})
class AdminPhaseABIT extends IntegrationTest {

    static final String PASSWORD = "Prueba#2026";
    static final String RUC_A = "20777777771";
    static final String RUC_B = "20777777772";
    /** Token de prueba sin valor real. */
    static final String LOOKUP_TOKEN = "token-de-prueba-sin-valor-real-0042";

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
        Map<String, String> authB = newTenant(tenants, RUC_B);
        tenantA = jdbc.queryForObject("SELECT id FROM tenants WHERE ruc = ?", Long.class, RUC_A);
        tenantB = jdbc.queryForObject("SELECT id FROM tenants WHERE ruc = ?", Long.class, RUC_B);
        prepararSeries(authA);
        prepararSeries(authB);
        facturaA = emitir(authA, "facturas", "F001", "6", "20601234567");
        facturaB = emitir(authB, "facturas", "F001", "6", "20601234567");
        boletaA = emitir(authA, "boletas", "B001", "1", "45678912");
    }

    @AfterAll
    void releaseClock() {
        Clock.useSystem();
        jdbc.update("DELETE FROM plans WHERE slug = 'it-starter'");
        jdbc.update("DELETE FROM settings WHERE key IN (?, ?, ?)", PlatformSettings.EMISION_ILIMITADA_GLOBAL,
                PlatformSettings.NUEVAS_EMPRESAS_ILIMITADAS, PlatformSettings.CONSULTA_TOKEN_GLOBAL);
        settings.invalidate();
    }

    @BeforeEach
    void resetRateLimit() {
        redis.delete("throttle:admin-auth:127.0.0.1");
        redis.delete("throttle:admin-auth:0:0:0:0:0:0:0:1");
    }

    // ------------------------------------------------------------------ fase A: feed, vista unificada y catálogos

    @Test
    void feed_de_envios_de_todas_las_empresas_con_filtros() throws Exception {
        String token = login("soporte.feed@example.com", "soporte");
        Map<String, Object> feed = body(get("/api/admin/submissions?records=50&textFilter=2077777777", bearer(token)));
        assertThat(feed).containsEntry("succeeded", true);
        List<Map<String, Object>> rows = rows(feed);
        assertThat(rows).extracting(r -> ((Number) r.get("tenantId")).longValue()).contains(tenantA, tenantB);
        Map<String, Object> fila = rows.stream().filter(r -> ((Number) r.get("id")).longValue() == facturaA && "01".equals(r.get("tipo")))
                .findFirst().orElseThrow();
        assertThat(fila.keySet()).containsExactly("id", "tipo", "tipoNombre", "vista", "tenantId", "ruc", "razonSocial", "serie", "correlativo",
                "numero", "cliente", "clienteDoc", "moneda", "total", "sunatStatus", "sunatCode", "sunatDescription", "observado",
                "fechaEmision", "sentAt", "createdAt", "sucursalId", "hasXml", "hasCdr", "hasPdf");
        assertThat(fila).containsEntry("vista", "facturas").containsEntry("ruc", RUC_A).containsEntry("sunatStatus", "pendiente");

        // Filtros: empresa, tipo y estado
        List<Map<String, Object>> soloB = rows(body(get("/api/admin/submissions?tenantId=" + tenantB + "&tipo=01", bearer(token))));
        assertThat(soloB).isNotEmpty().allMatch(r -> ((Number) r.get("tenantId")).longValue() == tenantB && "01".equals(r.get("tipo")));
        assertThat(rows(body(get("/api/admin/submissions?tenantId=" + tenantA + "&tipo=03", bearer(token)))))
                .extracting(r -> ((Number) r.get("id")).longValue()).contains(boletaA);
        assertThat(rows(body(get("/api/admin/submissions?estado=rechazado&tenantId=" + tenantA, bearer(token))))).isEmpty();
        assertThat(body(get("/api/admin/submissions?estado=borrado", bearer(token)))).containsEntry("succeeded", false);
        assertThat(body(get("/api/admin/submissions?startDate=2025-01-01&endDate=2026-12-31", bearer(token)))).containsEntry("succeeded", false);
        // Tope de 50 filas por página
        assertThat(rows(body(get("/api/admin/submissions?records=500", bearer(token))))).hasSizeLessThanOrEqualTo(50);
    }

    @Test
    void vista_unificada_global_y_de_la_empresa_con_contadores() throws Exception {
        String token = login("lectura.docs@example.com", "lectura");
        List<Map<String, Object>> globales = rows(body(get("/api/admin/documents?records=50&textFilter=2077777777", bearer(token))));
        assertThat(globales).extracting(r -> ((Number) r.get("tenantId")).longValue()).contains(tenantA, tenantB);

        // Solo facturas y boletas: otra prueba agrega una comunicación de baja (RA) a la empresa A
        Map<String, Object> deA = body(get("/api/admin/tenants/" + tenantA + "/comprobantes?records=50&tipo=01,03&orderBy=numero&orderDir=asc",
                bearer(token)));
        assertThat(rows(deA)).isNotEmpty().allMatch(r -> ((Number) r.get("tenantId")).longValue() == tenantA);
        assertThat(((Number) deA.get("totalRecords")).longValue()).isEqualTo(2);
        assertThat(body(get("/api/admin/tenants/" + tenantA + "/comprobantes?orderBy=xml_path", bearer(token)))).containsEntry("succeeded", false);
        assertThat(get("/api/admin/tenants/999999999/comprobantes", bearer(token)).statusCode()).isEqualTo(404);

        @SuppressWarnings("unchecked")
        Map<String, Object> resumen = (Map<String, Object>) data(get("/api/admin/tenants/" + tenantA + "/comprobantes/resumen?tipo=01", bearer(token)));
        @SuppressWarnings("unchecked")
        Map<String, Object> porTipo = (Map<String, Object>) resumen.get("porTipo");
        assertThat(((Number) porTipo.get("01")).longValue()).isEqualTo(1);
        assertThat(((Number) porTipo.get("03")).longValue()).isEqualTo(1);
        assertThat(((Number) resumen.get("total")).longValue()).isGreaterThanOrEqualTo(2);
        assertThat(resumen).containsKeys("porEstado", "observados");

        // La vista de una empresa se audita (ventana de 5 minutos), la global no
        Long auditadas = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'admin.empresa.consulta' AND tenant_id = ? "
                + "AND metadata->>'vista' = 'comprobantes' AND actor_etiqueta = ?", Long.class, tenantA, "lectura.docs@example.com");
        assertThat(auditadas).isEqualTo(1L);
    }

    @Test
    void sucursales_y_series_de_todas_las_empresas() throws Exception {
        String token = login("lectura.catalogos@example.com", "lectura");
        List<Map<String, Object>> sucursales = rows(body(get("/api/admin/branches?records=50&textFilter=2077777777", bearer(token))));
        assertThat(sucursales).extracting(r -> ((Number) r.get("tenantId")).longValue()).contains(tenantA, tenantB);
        assertThat(sucursales.get(0).keySet()).containsExactly("id", "tenantId", "ruc", "razonSocial", "nombre", "codLocal", "direccion",
                "ubigeo", "isPrincipal", "isActive", "totalSeries");
        assertThat(rows(body(get("/api/admin/branches?tenantId=" + tenantB, bearer(token)))))
                .allMatch(r -> ((Number) r.get("tenantId")).longValue() == tenantB);

        List<Map<String, Object>> series = rows(body(get("/api/admin/series?tipo=01&textFilter=" + RUC_A, bearer(token))));
        assertThat(series).isNotEmpty().allMatch(r -> "01".equals(r.get("tipoDocumento")) && RUC_A.equals(r.get("ruc")));
        assertThat(series.get(0)).containsKeys("tipoNombre", "correlativo", "siguienteNumero", "sucursalNombre");
        assertThat(body(get("/api/admin/series?tipo=99", bearer(token)))).containsEntry("succeeded", false);
    }

    @Test
    void tablero_con_las_metricas_globales() throws Exception {
        String token = login("lectura.tablero@example.com", "lectura");
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) data(get("/api/admin/dashboard", bearer(token)));
        // Claves anteriores intactas y las nuevas
        assertThat(d).containsKeys("tenantsActive", "documentsThisMonth", "pendingNow", "certificatesBroken", "tenantsTotal", "documentsToday",
                "documentsPreviousMonth", "documentsGrowth", "salesThisMonth", "salesPreviousMonth", "salesGrowth", "documentsByDay",
                "documentsByType", "tenantsByPlan", "tenantsByRegime", "tenantsByEnvironment", "sunatStatusThisMonth", "topTenants",
                "recentSubmissions", "period", "metricsAt");
        assertThat((List<?>) d.get("documentsByDay")).hasSize(30);
        assertThat((List<?>) d.get("documentsByType")).hasSize(12);
        assertThat((List<?>) d.get("recentSubmissions")).hasSizeLessThanOrEqualTo(8);
        assertThat(((Number) d.get("documentsToday")).longValue()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void constancia_de_anulacion_y_pdf_en_linea() throws Exception {
        String token = login("lectura.anulacion@example.com", "lectura");
        HttpResponse<String> sin = get("/api/admin/tenants/" + tenantA + "/facturas/" + facturaA + "/anulacion", bearer(token));
        assertThat(body(sin)).containsEntry("succeeded", true).containsEntry("data", null);
        assertThat(get("/api/admin/tenants/" + tenantA + "/facturas/" + facturaA + "/anulacion/xml", bearer(token)).statusCode()).isEqualTo(404);
        // De otra empresa: 404
        assertThat(get("/api/admin/tenants/" + tenantA + "/facturas/" + facturaB + "/anulacion", bearer(token)).statusCode()).isEqualTo(404);

        String serie = jdbc.queryForObject("SELECT serie FROM invoices WHERE id = ?", String.class, facturaA);
        Integer correlativo = jdbc.queryForObject("SELECT correlativo FROM invoices WHERE id = ?", Integer.class, facturaA);
        String path = RUC_A + "/bajas/xml/RA-20261124-901.xml";
        storage.put(FileStorage.Disk.PRIVATE, path, "<VoidedDocuments/>".getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO voided_documents (tenant_id, identifier, correlativo, fecha_generacion, fecha_comunicacion, total_documentos, "
                + "detalles, xml_path, sunat_status, created_at, updated_at) VALUES (?, 'RA-20261124-901', '901', current_date, current_date, 1, "
                + "?::jsonb, ?, 'aceptado', now(), now())", tenantA,
                "[{\"tipo_documento\":\"01\",\"serie\":\"" + serie + "\",\"correlativo\":\"000" + correlativo + "\",\"motivo\":\"Prueba\"}]", path);
        @SuppressWarnings("unchecked")
        Map<String, Object> ra = (Map<String, Object>) data(get("/api/admin/tenants/" + tenantA + "/facturas/" + facturaA + "/anulacion",
                bearer(token)));
        assertThat(ra).containsEntry("tipo", "RA").containsEntry("identifier", "RA-20261124-901").containsEntry("vista", "anulaciones")
                .containsEntry("hasXml", true).containsEntry("hasCdr", false);
        HttpResponse<byte[]> xml = getBytes("/api/admin/tenants/" + tenantA + "/facturas/" + facturaA + "/anulacion/xml", bearer(token));
        assertThat(xml.statusCode()).isEqualTo(200);
        assertThat(new String(xml.body(), StandardCharsets.UTF_8)).isEqualTo("<VoidedDocuments/>");
        assertThat(xml.headers().firstValue("Content-Disposition").orElse("")).contains("RA-20261124-901.xml");
        assertThat(xml.headers().firstValue("Cache-Control").orElse("")).isEqualTo("no-store");

        // PDF: inline=0 fuerza la descarga; sin el parámetro queda la disposición de la API (inline)
        HttpResponse<byte[]> pdf = getBytes("/api/admin/tenants/" + tenantA + "/facturas/" + facturaA + "/pdf?inline=0", bearer(token));
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment;");
        assertThat(pdf.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        HttpResponse<byte[]> preview = getBytes("/api/admin/tenants/" + tenantA + "/facturas/" + facturaA + "/pdf?inline=1", bearer(token));
        assertThat(preview.headers().firstValue("Content-Disposition").orElse("")).startsWith("inline;");
    }

    // ------------------------------------------------------------------ fase A: ZIP en streaming con topes

    @Test
    void zip_con_topes_streaming_y_auditado() throws Exception {
        String token = login("lectura.zip@example.com", "lectura");
        String base = "/api/admin/tenants/" + tenantA + "/comprobantes/exportar-zip";
        LocalDateRange r = new LocalDateRange();

        // Más de 31 días: tope del panel (aunque /api/v1 acepte 366)
        Map<String, Object> largo = body(get(base + "?fecha_desde=2026-01-01&fecha_hasta=2026-03-01", bearer(token)));
        assertThat(largo).containsEntry("succeeded", false);
        assertThat(String.valueOf(largo.get("messages"))).contains("31 días");
        // Validación de la API: sin fechas
        assertThat(body(get(base, bearer(token)))).containsEntry("succeeded", false);

        // Con un XML guardado: ZIP en streaming con la estructura de /api/v1
        String path = RUC_A + "/facturas/xml/zip-prueba.xml";
        storage.put(FileStorage.Disk.PRIVATE, path, "<Invoice/>".getBytes(StandardCharsets.UTF_8));
        jdbc.update("UPDATE invoices SET xml_path = ? WHERE id = ?", path, facturaA);
        HttpResponse<byte[]> zip = getBytes(base + "?fecha_desde=" + r.from + "&fecha_hasta=" + r.to + "&documentos=facturas", bearer(token));
        assertThat(zip.statusCode()).isEqualTo(200);
        assertThat(zip.headers().firstValue("Content-Type").orElse("")).startsWith("application/zip");
        assertThat(zip.headers().firstValue("Cache-Control").orElse("")).isEqualTo("no-store");
        List<String> entries = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.body()))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                entries.add(e.getName());
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("<Invoice/>");
            }
        }
        assertThat(entries).hasSize(1).allMatch(n -> n.startsWith("facturas/") && n.endsWith(".xml"));
        Long auditada = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'admin.empresa.descarga' AND tenant_id = ? "
                + "AND metadata->>'vista' = 'comprobantes.exportar-zip' AND (metadata->>'archivos')::int = 1", Long.class, tenantA);
        assertThat(auditada).isEqualTo(1L);

        // Solo los de la empresa de la ruta: la B no tiene archivos → 404 de la API
        jdbc.update("UPDATE invoices SET xml_path = NULL WHERE tenant_id = ?", tenantB);
        assertThat(get("/api/admin/tenants/" + tenantB + "/comprobantes/exportar-zip?fecha_desde=" + r.from + "&fecha_hasta=" + r.to,
                bearer(token)).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ fase A: consultas en vivo a SUNAT

    @Test
    void consulta_cpe_auditada_sin_credenciales_ni_para_lectura() throws Exception {
        String lectura = login("lectura.cpe@example.com", "lectura");
        String query = "/api/admin/tenants/" + tenantA + "/consultar-cpe?tipo_doc=01&serie=F001&correlativo=1&fecha_emision=24/11/2026&monto=118";
        assertThat(get(query, bearer(lectura)).statusCode()).isEqualTo(403);

        String soporte = login("soporte.cpe@example.com", "soporte");
        long antes = countAudit("admin.empresa.consulta_sunat", tenantA);
        // La empresa no tiene client_id / client_secret: la API responde 422 sin llamar a SUNAT; el panel, 200 con succeeded:false
        Map<String, Object> r = body(get(query, bearer(soporte)));
        assertThat(r).containsEntry("succeeded", false);
        assertThat(String.valueOf(r.get("messages"))).contains("client_id/client_secret");
        assertThat(r.toString()).doesNotContain("MODDATOS").doesNotContain("secret-" + RUC_A);
        assertThat(countAudit("admin.empresa.consulta_sunat", tenantA)).isEqualTo(antes + 1);
        Map<String, Object> meta = PhpJson.decodeObject(jdbc.queryForObject("SELECT metadata::text FROM audit_logs WHERE accion = "
                + "'admin.empresa.consulta_sunat' AND tenant_id = ? ORDER BY id DESC LIMIT 1", String.class, tenantA));
        assertThat(meta).containsEntry("vista", "consultar-cpe").containsEntry("tipo_doc", "01").containsEntry("serie", "F001");
        assertThat(((Number) meta.get("http")).intValue()).isEqualTo(422);

        // Validación fallida: no llega al servicio y no se audita
        assertThat(body(get("/api/admin/tenants/" + tenantA + "/consultar-cpe?tipo_doc=99", bearer(soporte)))).containsEntry("succeeded", false);
        assertThat(countAudit("admin.empresa.consulta_sunat", tenantA)).isEqualTo(antes + 1);
    }

    // ------------------------------------------------------------------ fase B: planes

    @Test
    void planes_soporte_no_escribe_operador_no_elimina() throws Exception {
        String soporte = login("soporte.planes@example.com", "soporte");
        String operador = login("operador.planes@example.com", "operador");
        String superadmin = login("super.planes@example.com", "superadmin");
        String plan = "{\"slug\":\"it-starter\",\"name\":\"IT Starter\",\"priceMonthly\":29.9,\"limits\":{\"documents_month\":500},"
                + "\"features\":[\"facturacion\"],\"sortOrder\":50,\"isActive\":true}";

        assertThat(rows(body(get("/api/admin/plans?records=50", bearer(soporte))))).isNotEmpty();
        assertThat(send("POST", "/api/admin/plans", plan, bearer(soporte)).statusCode()).isEqualTo(403);

        @SuppressWarnings("unchecked")
        Map<String, Object> creado = (Map<String, Object>) data(send("POST", "/api/admin/plans", plan, bearer(operador)));
        long id = ((Number) creado.get("id")).longValue();
        assertThat(creado).containsEntry("slug", "it-starter").containsEntry("isSystem", false).containsKeys("limits", "features");
        assertThat(send("PUT", "/api/admin/plans/" + id + "/toggle", "{}", bearer(soporte)).statusCode()).isEqualTo(403);
        assertThat(body(send("POST", "/api/admin/plans", plan, bearer(operador)))).containsEntry("succeeded", false);

        // /api/v1/planes: mismo formato, ahora con el plan nuevo
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> publicos = (List<Map<String, Object>>) PhpJson.decodeObject(get("/api/v1/planes", Map.of()).body()).get("datos");
        Map<String, Object> nuevo = publicos.stream().filter(p -> "it-starter".equals(p.get("slug"))).findFirst().orElseThrow();
        assertThat(nuevo.keySet()).containsExactly("slug", "nombre", "precio_mensual", "precio_anual", "limites", "caracteristicas");
        assertThat(nuevo).containsEntry("precio_mensual", "29.90").containsEntry("precio_anual", "0.00");

        // Los planes del sistema no cambian de slug ni se eliminan
        long free = jdbc.queryForObject("SELECT id FROM plans WHERE slug = 'free'", Long.class);
        assertThat(body(send("PUT", "/api/admin/plans/" + free, "{\"slug\":\"gratis\",\"name\":\"Free\",\"priceMonthly\":0}", bearer(operador))))
                .containsEntry("succeeded", false);
        assertThat(body(send("DELETE", "/api/admin/plans/" + free, null, bearer(superadmin)))).containsEntry("succeeded", false);

        // Eliminar: solo superadmin
        assertThat(send("DELETE", "/api/admin/plans/" + id, null, bearer(operador)).statusCode()).isEqualTo(403);
        assertThat(body(send("DELETE", "/api/admin/plans/" + id, null, bearer(superadmin)))).containsEntry("succeeded", true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plans WHERE id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion IN ('admin.plan.creado', 'admin.plan.eliminado') "
                + "AND subject_id = ?", Long.class, String.valueOf(id))).isEqualTo(2L);
    }

    // ------------------------------------------------------------------ fase B: configuración y token de consulta

    @Test
    void el_token_de_consulta_nunca_se_devuelve_y_queda_cifrado() throws Exception {
        String operador = login("operador.config@example.com", "operador");
        String superadmin = login("super.config@example.com", "superadmin");
        String body = "{\"token\":\"" + LOOKUP_TOKEN + "\"}";

        assertThat(send("PUT", "/api/admin/settings/lookup-token", body, bearer(operador)).statusCode()).isEqualTo(403);
        HttpResponse<String> put = send("PUT", "/api/admin/settings/lookup-token", body, bearer(superadmin));
        assertThat(body(put)).containsEntry("succeeded", true);
        assertThat(put.body()).doesNotContain(LOOKUP_TOKEN);

        HttpResponse<String> get = get("/api/admin/settings", bearer(operador));
        assertThat(get.body()).doesNotContain(LOOKUP_TOKEN);
        @SuppressWarnings("unchecked")
        Map<String, Object> token = (Map<String, Object>) ((Map<String, Object>) data(get)).get("lookupToken");
        assertThat(token).containsEntry("configured", true).containsEntry("source", "panel").containsEntry("last4", "0042");

        // En la BD, cifrado; en la auditoría, nunca
        String stored = jdbc.queryForObject("SELECT value #>> '{}' FROM settings WHERE key = ?", String.class, PlatformSettings.CONSULTA_TOKEN_GLOBAL);
        assertThat(stored).startsWith("v1:").doesNotContain(LOOKUP_TOKEN);
        assertThat(settings.lookupToken()).contains(LOOKUP_TOKEN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE coalesce(metadata::text, '') || coalesce(descripcion, '') LIKE ?",
                Long.class, "%" + LOOKUP_TOKEN + "%")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'admin.configuracion.token_consulta' "
                + "AND actor_etiqueta = ?", Long.class, "super.config@example.com")).isEqualTo(1L);

        // Quitarlo
        assertThat(body(send("DELETE", "/api/admin/settings/lookup-token", null, bearer(superadmin)))).containsEntry("succeeded", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) ((Map<String, Object>) data(get("/api/admin/settings", bearer(operador)))).get("lookupToken");
        assertThat(after).containsEntry("source", "none").containsEntry("last4", null);
    }

    @Test
    void emision_global_con_motivo_y_solo_superadmin() throws Exception {
        String operador = login("operador.emision@example.com", "operador");
        String superadmin = login("super.emision@example.com", "superadmin");
        assertThat(send("PUT", "/api/admin/settings/emission", "{\"globalUnlimited\":true,\"newTenantsUnlimited\":false,\"motivo\":\"x\"}",
                bearer(operador)).statusCode()).isEqualTo(403);
        // Encender la emisión global exige motivo
        assertThat(body(send("PUT", "/api/admin/settings/emission", "{\"globalUnlimited\":true,\"newTenantsUnlimited\":true}",
                bearer(superadmin)))).containsEntry("succeeded", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> e = (Map<String, Object>) ((Map<String, Object>) data(send("PUT", "/api/admin/settings/emission",
                "{\"globalUnlimited\":true,\"newTenantsUnlimited\":true,\"motivo\":\"Campaña de fin de año\"}", bearer(superadmin)))).get("emission");
        assertThat(e).containsEntry("globalUnlimited", true).containsEntry("globalUnlimitedEffective", true).containsEntry("newTenantsUnlimited", true);
        assertThat(settings.emisionIlimitadaGlobal()).isTrue();
        Map<String, Object> meta = PhpJson.decodeObject(jdbc.queryForObject("SELECT metadata::text FROM audit_logs WHERE "
                + "accion = 'admin.configuracion.emision' ORDER BY id DESC LIMIT 1", String.class));
        assertThat(meta).containsEntry("motivo", "Campaña de fin de año").containsKeys("antes", "despues");
        // Apagar (vuelve al estado inicial para las demás pruebas)
        data(send("PUT", "/api/admin/settings/emission", "{\"globalUnlimited\":false,\"newTenantsUnlimited\":false,\"motivo\":\"Fin\"}",
                bearer(superadmin)));
        assertThat(settings.emisionIlimitadaGlobal()).isFalse();
    }

    // ------------------------------------------------------------------ perfil propio y rol soporte

    @Test
    void perfil_propio_con_contrasena_y_correo_unico() throws Exception {
        String token = login("lectura.perfil@example.com", "lectura");
        login("otro.perfil@example.com", "lectura");
        assertThat(body(send("PUT", "/api/admin/auth/me", "{\"name\":\"Nuevo\",\"email\":\"lectura.perfil@example.com\",\"password\":\"Mala#2026\"}",
                bearer(token)))).containsEntry("succeeded", false);
        assertThat(body(send("PUT", "/api/admin/auth/me", "{\"name\":\"Nuevo\",\"email\":\"otro.perfil@example.com\",\"password\":\"" + PASSWORD
                + "\"}", bearer(token)))).containsEntry("succeeded", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> me = (Map<String, Object>) data(send("PUT", "/api/admin/auth/me",
                "{\"name\":\"Lucía Ramos\",\"email\":\"Lucia.Perfil@example.com\",\"password\":\"" + PASSWORD + "\"}", bearer(token)));
        assertThat(me).containsEntry("fullName", "Lucía Ramos").containsEntry("email", "lucia.perfil@example.com").containsEntry("role", "lectura");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'admin.perfil.actualizado' AND actor_etiqueta = ?",
                Long.class, "lucia.perfil@example.com")).isEqualTo(1L);
        // La sesión sigue vigente
        assertThat(get("/api/admin/auth/me", bearer(token)).statusCode()).isEqualTo(200);
    }

    @Test
    void el_superadmin_asigna_el_rol_soporte() throws Exception {
        String superadmin = login("super.roles@example.com", "superadmin");
        jdbc.update("DELETE FROM users WHERE lower(email) = 'nuevo.soporte@example.com'");
        @SuppressWarnings("unchecked")
        Map<String, Object> u = (Map<String, Object>) data(send("POST", "/api/admin/users",
                "{\"name\":\"Soporte Uno\",\"email\":\"nuevo.soporte@example.com\",\"role\":\"soporte\",\"password\":\"" + PASSWORD + "\"}",
                bearer(superadmin)));
        assertThat(u).containsEntry("role", "soporte").containsEntry("roleName", "Soporte");
        String soporte = login("soporte.permisos@example.com", "soporte");
        @SuppressWarnings("unchecked")
        List<String> permisos = (List<String>) data(get("/api/admin/auth/permissions", bearer(soporte)));
        assertThat(permisos).contains("Admin.Sunat.Query", "Admin.Documents.Resend").doesNotContain("Admin.Plans.Manage", "Admin.Audit.View");
        assertThat(get("/api/admin/audit", bearer(soporte)).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/admin/jobs/retry-all", "{}", bearer(soporte)).statusCode()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ apoyo

    /** Rango de hoy (Lima) para la exportación. */
    static final class LocalDateRange {
        final String from = Clock.todayLima().minusDays(1).toString();
        final String to = Clock.todayLima().plusDays(1).toString();
    }

    long countAudit(String accion, long tenant) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = ? AND tenant_id = ?", Long.class, accion, tenant);
        return n == null ? 0 : n;
    }

    void prepararSeries(Map<String, String> auth) throws Exception {
        HttpResponse<String> suc = send("POST", "/api/v1/sucursales",
                "{\"nombre\":\"Principal\",\"cod_local\":\"0000\",\"direccion\":\"Av 1\",\"ubigeo\":\"150101\"}", auth);
        assertThat(suc.statusCode()).as(suc.body()).isEqualTo(201);
        HttpResponse<String> init = send("POST", "/api/v1/series/init-defaults", "{}", auth);
        assertThat(init.statusCode()).as(init.body()).isEqualTo(200);
    }

    /** Factura o boleta pendiente (sin envío a SUNAT); devuelve su id. */
    long emitir(Map<String, String> auth, String tipo, String serie, String tipoDoc, String numDoc) throws Exception {
        HttpResponse<String> f = send("POST", "/api/v1/" + tipo, "{\"serie\":\"" + serie + "\",\"fecha_emision\":\"" + Clock.todayLima()
                + "\",\"enviar_automatico\":false,\"cliente\":{\"tipo_doc\":\"" + tipoDoc + "\",\"num_doc\":\"" + numDoc
                + "\",\"razon_social\":\"CLIENTE SAC\",\"direccion\":\"AV. LIMA 1\"},\"items\":[{\"codigo\":\"P1\",\"descripcion\":\"Producto\","
                + "\"unidad\":\"NIU\",\"cantidad\":1,\"precio_unitario\":118,\"tip_afe_igv\":\"10\"}]}", auth);
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

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> body) {
        assertThat(body).as(String.valueOf(body)).containsEntry("succeeded", true);
        return (List<Map<String, Object>>) body.get("data");
    }
}
