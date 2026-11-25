package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.security.ApiCredentials;
import pe.facturacion.api.shared.crypto.EncryptedString;
import pe.facturacion.api.support.IntegrationTest;

/** Contrato HTTP transversal de API-PRO: errores, autenticación, idempotencia, límites, logs y cabeceras. */
class ApiPipelineIT extends IntegrationTest {

    @Autowired
    TenantRepository tenants;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void rutaInexistenteDa404SinPedirCredenciales() throws Exception {
        HttpResponse<String> r = get("/api/v1/no-existe", Map.of());
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"Recurso no encontrado.\"}");
        assertThat(r.headers().firstValue("Content-Type")).hasValue("application/json");
    }

    @Test
    void metodoNoPermitidoDa405ConMensajeEscapadoComoPhp() throws Exception {
        HttpResponse<String> r = send("DELETE", "/api/health", null, Map.of());
        assertThat(r.statusCode()).isEqualTo(405);
        assertThat(r.body()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"M\\u00e9todo HTTP no permitido para esta ruta.\"}");
    }

    @Test
    void sinCabecerasDa401() throws Exception {
        HttpResponse<String> r = get("/api/v1/sunat/estado", Map.of());
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"Las cabeceras X-Api-Key y X-Api-Secret son requeridas.\"}");
    }

    @Test
    void credencialesInvalidasDa401() throws Exception {
        HttpResponse<String> r = get("/api/v1/sunat/estado", Map.of("X-Api-Key", DEMO_KEY, "X-Api-Secret", "otro"));
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"Credenciales de API inv\\u00e1lidas.\"}");
        HttpResponse<String> r2 = get("/api/v1/sunat/estado", Map.of("X-Api-Key", "no-existe", "X-Api-Secret", "x"));
        assertThat(r2.statusCode()).isEqualTo(401);
    }

    @Test
    void credencialesPorQueryStringNoAutentican() throws Exception {
        HttpResponse<String> r = get("/api/v1/sunat/estado?api_key=" + DEMO_KEY + "&api_secret=" + DEMO_SECRET, Map.of());
        assertThat(r.statusCode()).isEqualTo(401);
    }

    @Test
    void empresaDesactivadaDa403() throws Exception {
        String key = ApiCredentials.newApiKey();
        Tenant t = new Tenant();
        t.setRuc("20999999991");
        t.setRazonSocial("INACTIVA SAC");
        t.setSolUser(EncryptedString.ofPlain("MODDATOS"));
        t.setSolPass(EncryptedString.ofPlain("MODDATOS"));
        t.setApiKey(key);
        t.setApiSecret(EncryptedString.ofPlain("s3cr3t"));
        t.setActive(false);
        tenants.save(t);

        HttpResponse<String> r = get("/api/v1/sunat/estado", Map.of("X-Api-Key", key, "X-Api-Secret", "s3cr3t"));
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"Empresa desactivada. Contacte al administrador.\"}");
    }

    @Test
    void sunatEstadoConCabecerasDeUsoYSeguridad() throws Exception {
        HttpResponse<String> r = get("/api/v1/sunat/estado", demoAuth());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"estado\":\"exito\",\"sunat\":{\"disponible\":true,\"circuit\":\"closed\",\"fallas\":0,\"entorno\":\"beta\"}}");
        assertThat(r.headers().firstValue("X-Plan")).hasValue("free");
        assertThat(r.headers().firstValue("X-Usage-Documents")).hasValue("0/30");
        assertThat(r.headers().firstValue("X-Usage-AI")).hasValue("0/10");
        assertThat(r.headers().firstValue("X-RateLimit-Limit")).hasValue("30");
        assertThat(r.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(r.headers().firstValue("X-Frame-Options")).hasValue("SAMEORIGIN");
        assertThat(r.headers().firstValue("Referrer-Policy")).hasValue("strict-origin-when-cross-origin");
        assertThat(r.headers().firstValue("X-Permitted-Cross-Domain-Policies")).hasValue("none");
        assertThat(r.headers().firstValue("Strict-Transport-Security")).as("solo sobre HTTPS").isEmpty();
    }

    @Test
    void validacionDevuelve422ConFormatoDeLaravel() throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/_pruebas/validar",
                "{\"serie\":\" B001 \",\"cliente\":{},\"items\":[{\"cantidad\":0},{\"cantidad\":\"\"}]}", demoAuth());
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(r.body()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"Error de validaci\\u00f3n\",\"errores\":{"
                + "\"serie\":[\"La serie de factura debe empezar con F seguido de 3 caracteres alfanum\\u00e9ricos (ej. F001).\"],"
                + "\"cliente.num_doc\":[\"El campo cliente.num doc es obligatorio.\"],"
                + "\"items.0.cantidad\":[\"El campo items.0.cantidad debe ser mayor que 0.\"],"
                + "\"items.1.cantidad\":[\"El campo items.1.cantidad es obligatorio.\"]}}");
    }

    @Test
    void errorInternoNoFiltraDetallesSinDebug() throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/_pruebas/explota", "{}", demoAuth());
        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(r.body()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"Error interno del servidor.\"}");
    }

    @Test
    void idempotenciaRepiteLaRespuestaYDetectaCuerposDistintos() throws Exception {
        Map<String, String> h = new HashMap<>(demoAuth());
        h.put("Idempotency-Key", "pedido-123");
        HttpResponse<String> first = send("POST", "/api/v1/_pruebas/eco", "{\"a\":1}", h);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.headers().firstValue("Idempotency-Replayed")).hasValue("false");

        HttpResponse<String> replay = send("POST", "/api/v1/_pruebas/eco", "{\"a\":1}", h);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).hasValue("true");

        HttpResponse<String> conflict = send("POST", "/api/v1/_pruebas/eco", "{\"a\":2}", h);
        assertThat(conflict.statusCode()).isEqualTo(422);
        assertThat(conflict.body()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"La Idempotency-Key ya se us\\u00f3 con un cuerpo distinto.\"}");
    }

    @Test
    void apiLogsGuardaLaPeticionConSecretosEnmascarados() throws Exception {
        send("POST", "/api/v1/_pruebas/eco", "{\"sol_pass\":\"clave\",\"client_secret\":\"x\",\"dato\":\"visible\"}", demoAuth());
        List<Map<String, Object>> rows = List.of();
        for (int i = 0; i < 50 && rows.isEmpty(); i++) {
            rows = jdbc.queryForList("SELECT endpoint, method, status_code, request_body::text AS req FROM api_logs "
                    + "WHERE endpoint = 'api/v1/_pruebas/eco' AND request_body->>'dato' = 'visible'");
            if (rows.isEmpty()) {
                Thread.sleep(100);
            }
        }
        assertThat(rows).isNotEmpty();
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("method")).isEqualTo("POST");
        assertThat(((Number) row.get("status_code")).intValue()).isEqualTo(201);
        assertThat((String) row.get("req")).contains("\"sol_pass\": \"***\"").contains("\"client_secret\": \"***\"").doesNotContain("clave");
    }

    @Test
    void limiteDePeticionesPorPlan() throws Exception {
        String key = ApiCredentials.newApiKey();
        Tenant t = new Tenant();
        t.setRuc("20999999992");
        t.setRazonSocial("LIMITE SAC");
        t.setSolUser(EncryptedString.ofPlain("MODDATOS"));
        t.setSolPass(EncryptedString.ofPlain("MODDATOS"));
        t.setApiKey(key);
        t.setApiSecret(EncryptedString.ofPlain("s3cr3t"));
        tenants.save(t);
        Map<String, String> auth = Map.of("X-Api-Key", key, "X-Api-Secret", "s3cr3t");

        for (int i = 1; i <= 30; i++) {
            HttpResponse<String> r = get("/api/v1/sunat/estado", auth);
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.headers().firstValue("X-RateLimit-Remaining")).hasValue(String.valueOf(30 - i));
        }
        HttpResponse<String> blocked = get("/api/v1/sunat/estado", auth);
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.body()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"Demasiadas solicitudes. Intenta de nuevo m\\u00e1s tarde.\"}");
    }
}
