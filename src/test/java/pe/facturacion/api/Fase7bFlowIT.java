package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.crypto.EncryptedString;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.support.IntegrationTest;

/**
 * Fase 7B, lo que el oráculo no cubre: correcciones (último día del rango, periodo inválido,
 * sucursal de otra empresa), la ruta excluida de documentos internos y la consulta de CPE contra
 * un SUNAT simulado (token en caché y reintento con token vencido).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {"app.queue.worker-enabled=false", "app.security.internal-token=token-interno-pruebas",
    "app.emision-ilimitada-global=true"})
class Fase7bFlowIT extends IntegrationTest {

    static final HttpServer SUNAT;
    static final AtomicInteger TOKENS = new AtomicInteger();
    static final AtomicInteger RECHAZOS = new AtomicInteger();

    static {
        try {
            SUNAT = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SUNAT.createContext("/seguridad/clientesextranet/", ex -> {
                String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                byte[] body;
                int status;
                if (form.contains("client_secret=secreto-valido")) {
                    status = 200;
                    body = ("{\"access_token\":\"tok-" + TOKENS.incrementAndGet() + "\",\"token_type\":\"JWT\",\"expires_in\":3600}").getBytes();
                } else {
                    status = 400;
                    body = "{\"error\":\"invalid_client\",\"error_description\":\"cliente no autorizado\"}".getBytes();
                }
                ex.sendResponseHeaders(status, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            SUNAT.createContext("/api/contribuyente/contribuyentes/", ex -> {
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                byte[] body;
                int status = 200;
                if ("Bearer tok-viejo".equals(auth)) {
                    RECHAZOS.incrementAndGet();
                    status = 401;
                    body = "{}".getBytes();
                } else if (req.contains("\"numero\":\"999\"")) {
                    body = "{\"success\":false,\"message\":\"No existe el comprobante\"}".getBytes();
                } else {
                    body = ("{\"success\":true,\"message\":\"Operation Success! \",\"data\":{\"estadoCp\":\"1\",\"estadoRuc\":\"00\",\"condDomiRuc\":\"00\","
                            + "\"observaciones\":[\"- El comprobante fue registrado\"]},\"eco\":" + PhpJson.encode(req) + "}").getBytes();
                }
                ex.sendResponseHeaders(status, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            SUNAT.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void sunatProps(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + SUNAT.getAddress().getPort();
        r.add("app.sunat.cpe.auth-url", () -> base + "/seguridad");
        r.add("app.sunat.cpe.api-url", () -> base + "/api");
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TenantRepository tenants;
    @Autowired
    StringRedisTemplate redis;

    Map<String, String> h;

    @BeforeAll
    void setUp() {
        Clock.useFixed(Instant.parse("2026-10-24T15:15:30Z"));
        jdbc.execute("TRUNCATE invoice_items, boleta_items, credit_note_items, debit_note_items, payments, invoices, boletas, credit_notes, debit_notes "
                + "RESTART IDENTITY");
        jdbc.update("UPDATE series SET correlativo = 0 WHERE tenant_id = (SELECT id FROM tenants WHERE ruc = '20000000001')");
        h = new HashMap<>(demoAuth());
        h.put("X-Internal-Token", "token-interno-pruebas");
        h.put("Accept", "application/json");
    }

    @AfterAll
    void tearDown() {
        Clock.useSystem();
        SUNAT.stop(0);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> datos(HttpResponse<String> r) {
        return (Map<String, Object>) PhpJson.decodeObject(r.body()).get("datos");
    }

    @Test
    void reportesIncluyenElUltimoDiaDelRango() throws Exception {
        String factura = "{\"serie\":\"F001\",\"fecha_emision\":\"2026-10-24 10:15:30\",\"cliente\":{\"tipo_doc\":\"6\",\"num_doc\":\"20601234567\","
                + "\"razon_social\":\"CLIENTE PRUEBA S.A.C.\",\"direccion\":\"AV. LIMA 123\"},\"items\":[{\"codigo\":\"P1\",\"descripcion\":\"Producto\","
                + "\"unidad\":\"NIU\",\"cantidad\":1,\"precio_unitario\":118,\"tip_afe_igv\":\"10\"}]}";
        assertThat(send("POST", "/api/v1/facturas", factura, h).statusCode()).isEqualTo(201);
        // API-PRO comparaba fecha_emision (con hora) contra "2026-10-24" y no la incluía
        Map<String, Object> d = datos(get("/api/v1/reportes/registro-ventas?fecha_desde=2026-10-24&fecha_hasta=2026-10-24", h));
        assertThat(((Number) d.get("total_documentos")).intValue()).isEqualTo(1);
        HttpResponse<String> notas = get("/api/v1/reportes/notas?fecha_desde=2026-10-01&fecha_hasta=2026-10-24", h);
        assertThat(notas.statusCode()).isEqualTo(200);
        // PDF: nombre con la fecha de Lima
        HttpResponse<byte[]> pdf = getBytes("/api/v1/reportes/registro-ventas?fecha_desde=2026-10-24&fecha_hasta=2026-10-24&formato=pdf", h);
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("Content-Disposition").orElse("")).isEqualTo("inline; filename=\"registro-ventas-2026-10-24.pdf\"");
        assertThat(pdfText(pdf.body()).replaceAll("-\\s+", "-")).contains("REGISTRO DE VENTAS", "F001-000001", "24/10/2026 10:15:30");
    }

    @Test
    void validacionesCorregidas() throws Exception {
        // Fecha que Laravel acepta como "date" pero no es AAAA-MM-DD: 422 (API-PRO daba 500)
        HttpResponse<String> r = get("/api/v1/reportes/registro-ventas?fecha_desde=tomorrow&fecha_hasta=2026-12-31", h);
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(r.body()).contains("fecha_desde");
        // Sucursal de otra empresa: no existe para esta
        Tenant ajena = tenants.findByRuc("20777777777").orElseGet(() -> {
            Tenant t = new Tenant();
            t.setRuc("20777777777");
            t.setRazonSocial("EMPRESA AJENA S.A.C.");
            t.setDireccion("AV. AJENA 1");
            t.setUbigeo("150101");
            t.setSolUser(EncryptedString.ofPlain("MODDATOS"));
            t.setSolPass(EncryptedString.ofPlain("MODDATOS"));
            t.setEnvironment(Tenant.ENV_BETA);
            t.setApiKey("AjenaKey" + "0123456789".repeat(5) + "abcdef");
            t.setApiSecret(EncryptedString.ofPlain("ajena-secret"));
            t.setPlan("free");
            return tenants.save(t);
        });
        Long otra = jdbc.queryForObject("INSERT INTO sucursales (tenant_id, nombre, cod_local, direccion, ubigeo, created_at, updated_at) "
                + "VALUES (?, 'AJENA', '0777', 'X', '150101', now(), now()) RETURNING id", Long.class, ajena.getId());
        r = get("/api/v1/reportes/registro-ventas?fecha_desde=2026-10-01&fecha_hasta=2026-10-24&sucursal_id=" + otra, h);
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(r.body()).contains("sucursal id no existe");
        jdbc.update("DELETE FROM sucursales WHERE id = ?", otra);
        // Periodo del panel inválido: 422 (API-PRO daba 500)
        assertThat(get("/api/v1/panel?mes=2026-13", h).statusCode()).isEqualTo(422);
        assertThat(get("/api/v1/panel/clientes?desde=2026-10-01&hasta=ayer", h).statusCode()).isEqualTo(422);
        // documentos-internos (cotizaciones y notas de venta) no existe
        assertThat(get("/api/v1/reportes/documentos-internos?fecha_desde=2026-10-01&fecha_hasta=2026-10-24", h).statusCode()).isEqualTo(404);
        // Exportación: "tomorrow" ya no es una fecha válida
        assertThat(get("/api/v1/comprobantes/exportar-zip?fecha_desde=tomorrow&fecha_hasta=2026-10-24", h).statusCode()).isEqualTo(422);
    }

    @Test
    @SuppressWarnings("unchecked")
    void consultaDeCpeConTokenEnCacheYReintento() throws Exception {
        Tenant demo = tenants.findByRuc("20000000001").orElseThrow();
        demo.setClientId("cliente-cpe");
        demo.setClientSecret(EncryptedString.ofPlain("secreto-valido"));
        tenants.save(demo);
        redis.delete("sunat_cpe_token:" + demo.getId());
        try {
            String q = "/api/v1/consultar-cpe?tipo_doc=01&serie=F001&correlativo=12&fecha_emision=24/10/2026&monto=1180";
            HttpResponse<String> r = get(q, h);
            assertThat(r.statusCode()).isEqualTo(200);
            Map<String, Object> d = datos(r);
            assertThat(d).containsEntry("encontrado", true).containsEntry("estado_cp", "1").containsEntry("estado_cp_descripcion", "ACEPTADO")
                    .containsEntry("estado_ruc_descripcion", "ACTIVO").containsEntry("cond_domi_ruc_descripcion", "HABIDO")
                    .containsEntry("consultado_por_ruc", "20000000001").containsEntry("ruc_emisor", "20000000001").containsEntry("correlativo", "12");
            assertThat((List<Object>) d.get("observaciones")).containsExactly("- El comprobante fue registrado");
            int tokens = TOKENS.get();
            // Segunda consulta: usa el token guardado; comprobante inexistente
            r = get(q.replace("correlativo=12", "correlativo=999"), h);
            assertThat(datos(r)).containsEntry("encontrado", false).containsEntry("mensaje", "No existe el comprobante");
            assertThat(TOKENS.get()).isEqualTo(tokens);
            // Token guardado rechazado por SUNAT: se renueva y se reintenta una vez
            redis.opsForValue().set("sunat_cpe_token:" + demo.getId(), "tok-viejo");
            r = get(q, h);
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(RECHAZOS.get()).isEqualTo(1);
            assertThat(TOKENS.get()).isEqualTo(tokens + 1);
            // Credenciales inválidas: 401 sin exponer la URL ni el client_id
            demo.setClientSecret(EncryptedString.ofPlain("otro"));
            tenants.save(demo);
            redis.delete("sunat_cpe_token:" + demo.getId());
            r = get(q, h);
            assertThat(r.statusCode()).isEqualTo(401);
            assertThat(r.body()).contains("SUNAT rechaz").doesNotContain("cliente-cpe").doesNotContain("127.0.0.1");
        } finally {
            demo.setClientId(null);
            demo.setClientSecret(null);
            tenants.save(demo);
        }
    }
}
