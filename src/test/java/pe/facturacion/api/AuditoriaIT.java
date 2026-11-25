package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.crypto.EncryptedString;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.support.IntegrationTest;

/**
 * Fase 8 (auditoría): aislamiento entre empresas en todas las rutas con {id} del OpenAPI y
 * correlativos sin duplicados bajo concurrencia.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {"app.queue.worker-enabled=false", "app.security.internal-token=token-interno-pruebas",
    "app.emision-ilimitada-global=true"})
class AuditoriaIT extends IntegrationTest {

    static final String KEY_B = "AuditKeyB" + "0123456789".repeat(5) + "abcde";
    static final String SECRET_B = "audit-secret-b";

    /** Primer segmento de la ruta → tabla con tenant_id. */
    static final Map<String, String> TABLAS = new LinkedHashMap<>();

    static {
        TABLAS.put("facturas", "invoices");
        TABLAS.put("boletas", "boletas");
        TABLAS.put("notas-credito", "credit_notes");
        TABLAS.put("notas-debito", "debit_notes");
        TABLAS.put("resumenes", "summaries");
        TABLAS.put("anulaciones", "voided_documents");
        TABLAS.put("guias-remision", "dispatch_guides");
        TABLAS.put("retenciones", "retentions");
        TABLAS.put("percepciones", "perceptions");
        TABLAS.put("liquidaciones-compra", "purchase_settlements");
        TABLAS.put("sucursales", "sucursales");
        TABLAS.put("clientes", "clients");
        TABLAS.put("series", "series");
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TenantRepository tenants;

    Map<String, String> demo;
    Map<String, String> otra;

    static final String FACTURA = "{\"serie\":\"F001\",\"fecha_emision\":\"" + pe.facturacion.api.shared.time.Clock.nowLima().withNano(0)
            .format(pe.facturacion.api.shared.time.Clock.Y_M_D_H_I_S) + "\",\"cliente\":{\"tipo_doc\":\"6\",\"num_doc\":\"20601234567\",\"razon_social\":\"CLIENTE SAC\","
            + "\"direccion\":\"AV. LIMA 1\"},\"items\":[{\"codigo\":\"P1\",\"descripcion\":\"Producto\",\"unidad\":\"NIU\",\"cantidad\":1,"
            + "\"precio_unitario\":118,\"tip_afe_igv\":\"10\"}]}";

    @BeforeAll
    void setUp() {
        if (tenants.findByRuc("20888888881").isEmpty()) {
            Tenant t = new Tenant();
            t.setRuc("20888888881");
            t.setRazonSocial("EMPRESA AUDITORIA B S.A.C.");
            t.setDireccion("AV. B 1");
            t.setUbigeo("150101");
            t.setSolUser(EncryptedString.ofPlain("MODDATOS"));
            t.setSolPass(EncryptedString.ofPlain("MODDATOS"));
            t.setEnvironment(Tenant.ENV_BETA);
            t.setApiKey(KEY_B);
            t.setApiSecret(EncryptedString.ofPlain(SECRET_B));
            t.setPlan("free");
            tenants.save(t);
        }
        demo = new HashMap<>(demoAuth());
        demo.put("X-Internal-Token", "token-interno-pruebas");
        demo.put("Accept", "application/json");
        otra = new HashMap<>(Map.of("X-Api-Key", KEY_B, "X-Api-Secret", SECRET_B, "X-Internal-Token", "token-interno-pruebas",
                "Accept", "application/json"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void otraEmpresaNoAccedeAComprobantesNiConfiguracionAjenos() throws Exception {
        assertThat(send("POST", "/api/v1/facturas", FACTURA, demo).statusCode()).isEqualTo(201);
        assertThat(send("POST", "/api/v1/boletas", FACTURA.replace("F001", "B001").replace("\"tipo_doc\":\"6\",\"num_doc\":\"20601234567\"",
                "\"tipo_doc\":\"1\",\"num_doc\":\"12345678\""), demo).statusCode()).isEqualTo(201);
        long demoId = jdbc.queryForObject("SELECT id FROM tenants WHERE ruc = '20000000001'", Long.class);
        Map<String, Object> spec = PhpJson.decodeObject(get("/api/docs/openapi.json", Map.of()).body());
        List<String> fugas = new ArrayList<>();
        Set<String> probadas = new TreeSet<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) spec.get("paths")).entrySet()) {
            String path = e.getKey();
            if (!path.startsWith("/api/v1/") || !path.contains("{")) {
                continue;
            }
            String seg = path.substring("/api/v1/".length()).split("/")[0];
            String tabla = TABLAS.get(seg);
            if (tabla == null) {
                continue; // catálogos públicos por código ({codigo}) no pertenecen a una empresa
            }
            List<Long> ids = jdbc.queryForList("SELECT id FROM " + tabla + " WHERE tenant_id = ? ORDER BY id LIMIT 1", Long.class, demoId);
            if (ids.isEmpty()) {
                continue;
            }
            String url = path.replaceFirst("\\{[^}]+\\}", String.valueOf(ids.get(0))).replaceAll("\\{[^}]+\\}", "1");
            for (String method : ((Map<String, Object>) e.getValue()).keySet()) {
                String m = method.toUpperCase();
                HttpResponse<String> r = send(m, url, m.equals("GET") || m.equals("DELETE") ? null : "{}", otra);
                probadas.add(m + " " + path);
                if (r.statusCode() < 400) {
                    fugas.add(m + " " + url + " → " + r.statusCode() + " " + r.body());
                }
            }
        }
        assertThat(fugas).as(String.join("\n", fugas)).isEmpty();
        assertThat(probadas).contains("GET /api/v1/facturas/{id}", "GET /api/v1/facturas/{id}/xml", "GET /api/v1/facturas/{id}/pdf",
                "POST /api/v1/facturas/{id}/enviar", "PUT /api/v1/facturas/{id}", "GET /api/v1/boletas/{id}/pagos", "DELETE /api/v1/boletas/{id}");
        // Los listados, el panel y los reportes de la otra empresa no muestran nada del demo
        assertThat(get("/api/v1/facturas", otra).body()).doesNotContain("20601234567");
        assertThat(get("/api/v1/panel/documentos-recientes", otra).body()).doesNotContain("F001");
        assertThat(get("/api/v1/reportes/registro-ventas?fecha_desde=2026-01-01&fecha_hasta=2026-12-31", otra).body()).doesNotContain("F001");
        assertThat(get("/api/v1/comprobantes/exportar-zip?fecha_desde=2026-01-01&fecha_hasta=2026-12-31&tipo=ambos", otra).statusCode()).isEqualTo(404);
    }

    @Test
    void correlativosSinDuplicadosBajoConcurrencia() throws Exception {
        long demoId = jdbc.queryForObject("SELECT id FROM tenants WHERE ruc = '20000000001'", Long.class);
        jdbc.update("UPDATE series SET correlativo = 0 WHERE tenant_id = ? AND serie = 'F002'", demoId);
        if (jdbc.queryForObject("SELECT count(*) FROM series WHERE tenant_id = ? AND serie = 'F002'", Long.class, demoId) == 0) {
            jdbc.update("INSERT INTO series (tenant_id, tipo_documento, serie, correlativo, is_active, created_at, updated_at) "
                    + "VALUES (?, '01', 'F002', 0, true, now(), now())", demoId);
        }
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Callable<HttpResponse<String>>> tareas = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                tareas.add(() -> send("POST", "/api/v1/facturas", FACTURA.replace("F001", "F002"), demo));
            }
            List<Long> correlativos = new ArrayList<>();
            for (Future<HttpResponse<String>> f : pool.invokeAll(tareas)) {
                HttpResponse<String> r = f.get();
                assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
                correlativos.add(((Number) ((Map<?, ?>) PhpJson.decodeObject(r.body()).get("datos")).get("correlativo")).longValue());
            }
            assertThat(new TreeSet<>(correlativos)).hasSize(n).first().isEqualTo(1L);
            assertThat(new TreeSet<>(correlativos).last()).isEqualTo((long) n);
            assertThat(jdbc.queryForObject("SELECT correlativo FROM series WHERE tenant_id = ? AND serie = 'F002'", Long.class, demoId)).isEqualTo(n);
        } finally {
            pool.shutdownNow();
            // La serie F002 no es del seeder: otras pruebas cuentan las series de la empresa demo
            jdbc.update("DELETE FROM invoice_items WHERE invoice_id IN (SELECT id FROM invoices WHERE tenant_id = ? AND serie = 'F002')", demoId);
            jdbc.update("DELETE FROM invoices WHERE tenant_id = ? AND serie = 'F002'", demoId);
            jdbc.update("DELETE FROM series WHERE tenant_id = ? AND serie = 'F002'", demoId);
        }
    }
}
