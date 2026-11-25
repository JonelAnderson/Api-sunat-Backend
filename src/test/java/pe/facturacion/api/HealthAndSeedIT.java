package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.support.IntegrationTest;

class HealthAndSeedIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @SuppressWarnings("unchecked")
    void healthPublicoConLosMismosChequeosQueApiPro() throws Exception {
        HttpResponse<String> r = get("/api/health", Map.of());
        assertThat(r.statusCode()).isEqualTo(200);
        Map<String, Object> body = PhpJson.decodeObject(r.body());
        assertThat(body.keySet()).containsExactly("estado", "timestamp", "checks");
        assertThat(body.get("estado")).isEqualTo("ok");
        assertThat((String) body.get("timestamp")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+00:00");
        List<Map<String, Object>> checks = (List<Map<String, Object>>) body.get("checks");
        assertThat(checks).extracting(c -> c.get("componente"))
                .containsExactly("Base de datos", "Redis", "Disco", "Almacenamiento", "SUNAT beta", "SUNAT produccion");
        assertThat(checks.get(0).keySet()).containsExactly("componente", "estado", "detalle");
        assertThat(checks.get(0).get("detalle")).isEqualTo("pgsql");
    }

    @Test
    void seedersCarganCatalogosPlanesYEmpresaDemo() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM unspsc_codes", Integer.class)).isGreaterThan(52000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM unspsc_taxonomy", Integer.class)).isGreaterThan(4000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ubigeo_regiones", Integer.class)).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ubigeo_provincias", Integer.class)).isEqualTo(196);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ubigeo_distritos", Integer.class)).isGreaterThan(1800);
        assertThat(jdbc.queryForList("SELECT slug FROM plans ORDER BY sort_order", String.class)).containsExactly("free", "pro", "business");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series s JOIN tenants t ON t.id = s.tenant_id WHERE t.ruc = '20000000001'",
                Integer.class)).isEqualTo(10);
        String solUser = jdbc.queryForObject("SELECT sol_user FROM tenants WHERE ruc = '20000000001'", String.class);
        assertThat(solUser).startsWith("v1:").doesNotContain("MODDATOS");
    }
}
