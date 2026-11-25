package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.support.IntegrationTest;

/**
 * Con los valores por defecto ({@code REGISTRO_PUBLICO_ENABLED} y {@code SUSCRIPCION_AUTOSERVICIO_ENABLED} en false) el
 * registro público y el autoservicio de suscripción responden 404 sin crear ni cambiar nada; las lecturas siguen activas.
 */
@TestPropertySource(properties = "app.queue.worker-enabled=false")
class PublicFeaturesDisabledIT extends IntegrationTest {

    @Autowired
    TenantRepository tenants;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void el_registro_publico_apagado_responde_404_y_no_crea_la_empresa() throws Exception {
        long antes = tenants.count();
        HttpResponse<String> r = send("POST", "/api/v1/registro", "{\"ruc\":\"20400000021\",\"razon_social\":\"X SAC\"}", Map.of());
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(PhpJson.decodeObject(r.body())).containsEntry("estado", "error");
        assertThat(r.body()).contains("registro p\\u00fablico");
        assertThat(tenants.count()).isEqualTo(antes);
    }

    @Test
    void el_autoservicio_apagado_responde_404_pero_las_lecturas_siguen() throws Exception {
        Map<String, String> auth = newTenant(tenants, "20400000022");
        long tid = tenants.findByRuc("20400000022").orElseThrow().getId();

        for (String[] call : new String[][] {{"POST", "/api/v1/suscripcion"}, {"PUT", "/api/v1/suscripcion/cambiar-plan"},
                {"PUT", "/api/v1/suscripcion/cancelar"}}) {
            HttpResponse<String> r = send(call[0], call[1], "{\"plan_slug\":\"pro\"}", auth);
            assertThat(r.statusCode()).as(call[1]).isEqualTo(404);
            assertThat(r.body()).contains("autoservicio de suscripci\\u00f3n");
        }
        // Responde 404 aun sin credenciales (corre antes de la autenticación) y no crea ninguna suscripción
        assertThat(send("POST", "/api/v1/suscripcion", "{\"plan_slug\":\"pro\"}", Map.of()).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions WHERE tenant_id = ?", Long.class, tid)).isZero();

        assertThat(get("/api/v1/suscripcion/uso", auth).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/suscripcion", auth).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/suscripcion/pagos", auth).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/planes", Map.of()).statusCode()).isEqualTo(200);
    }
}
