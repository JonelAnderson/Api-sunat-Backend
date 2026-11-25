package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.support.IntegrationTest;

/** Sin {@code ADMIN_JWT_SECRET} el panel queda apagado: todo {@code /api/admin/**} responde 503 con el sobre. */
@TestPropertySource(properties = {"app.admin.jwt-secret=", "app.queue.worker-enabled=false"})
class AdminPanelDisabledIT extends IntegrationTest {

    @Test
    void sin_clave_el_panel_responde_503() throws Exception {
        for (String[] call : List.of(new String[] {"POST", "/api/admin/auth/login"}, new String[] {"GET", "/api/admin/dashboard"},
                new String[] {"GET", "/api/admin/tenants"})) {
            HttpResponse<String> r = send(call[0], call[1], "POST".equals(call[0]) ? "{\"userName\":\"a@example.com\",\"password\":\"x\"}" : null,
                    Map.of());
            assertThat(r.statusCode()).as(call[1]).isEqualTo(503);
            Map<String, Object> body = PhpJson.decodeObject(r.body());
            assertThat(body).containsEntry("succeeded", false).containsKeys("messages", "data", "totalRecords", "errors");
        }
        // El resto de la API no cambia
        assertThat(get("/api/v1/planes", Map.of()).statusCode()).isEqualTo(200);
    }
}
