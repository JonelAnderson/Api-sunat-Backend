package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.support.IntegrationTest;

/** Registro público, planes, suscripción, recuperación de credenciales y bridge SaaS. */
@TestPropertySource(properties = {"app.security.bridge-secret=bridge-test-secret",
    // Registro público y autoservicio de suscripción vienen apagados por defecto: aquí se prueban encendidos
    "app.registro.enabled=true", "app.suscripcion-autoservicio.enabled=true"})
class CuentaIT extends IntegrationTest {

    @Autowired
    TenantRepository tenants;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> datos(HttpResponse<String> r) {
        return (Map<String, Object>) PhpJson.decodeObject(r.body()).get("datos");
    }

    @Test
    void registroPublicoSiempreEnPlanFreeYCredencialesFuncionan() throws Exception {
        Map<String, String> fields = new HashMap<>();
        fields.put("ruc", "20400000001");
        fields.put("razon_social", "NUEVA EMPRESA SAC");
        fields.put("direccion", "AV. LIMA 123");
        fields.put("ubigeo", "150101");
        fields.put("sol_user", "MODDATOS");
        fields.put("sol_pass", "moddatos");
        fields.put("plan", "business");
        fields.put("tax_regime", "rus");
        fields.put("nrus_categoria", "1");
        fields.put("igv_rate_override", "10.5");
        fields.put("contrasena_certificado", "test1234");
        HttpResponse<String> r = multipart("POST", "/api/v1/registro", fields,
                Map.of("certificado", new Part("cert.pfx", resource("/fixtures/certs/moderno.pfx"))), Map.of());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        Map<String, Object> d = datos(r);
        assertThat(d.keySet()).containsExactly("tenant_id", "ruc", "razon_social", "entorno", "plan", "tax_regime",
                "igv_rate_override", "nrus_categoria", "api_key", "api_secret", "importante");
        assertThat(d.get("plan")).as("SEC-020: el plan enviado se ignora").isEqualTo("free");
        assertThat(d.get("entorno")).isEqualTo("beta");
        assertThat(d.get("igv_rate_override")).isEqualTo("10.50");
        assertThat((String) d.get("api_secret")).matches("[0-9a-f]{64}");
        Map<String, String> auth = Map.of("X-Api-Key", (String) d.get("api_key"), "X-Api-Secret", (String) d.get("api_secret"));
        assertThat(get("/api/v1/empresa", auth).body()).contains("\"tiene_certificado\":true");

        HttpResponse<String> dup = multipart("POST", "/api/v1/registro", fields,
                Map.of("certificado", new Part("cert.pfx", resource("/fixtures/certs/moderno.pfx"))), Map.of());
        assertThat(dup.statusCode()).isEqualTo(422);
        assertThat(dup.body()).contains("\"ruc\":[\"El campo ruc ya ha sido registrado.\"]");
    }

    @Test
    @SuppressWarnings("unchecked")
    void planesYSuscripcion() throws Exception {
        HttpResponse<String> planes = get("/api/v1/planes", Map.of());
        assertThat(planes.statusCode()).isEqualTo(200);
        List<Map<String, Object>> list = (List<Map<String, Object>>) PhpJson.decodeObject(planes.body()).get("datos");
        assertThat(list).extracting(p -> p.get("slug")).containsExactly("free", "pro", "business");
        assertThat(list.get(1)).containsEntry("precio_mensual", "49.00").containsEntry("precio_anual", "490.00");

        Map<String, String> auth = newTenant(tenants, "20400000002");
        Map<String, Object> show = datos(get("/api/v1/suscripcion", auth));
        assertThat(show.get("suscripcion")).isNull();
        Map<String, Object> uso = (Map<String, Object>) show.get("uso");
        assertThat(uso.keySet()).containsExactly("plan", "usage", "features", "next_upgrade");

        HttpResponse<String> trial = send("POST", "/api/v1/suscripcion", "{\"plan_slug\":\"pro\"}", auth);
        assertThat(trial.statusCode()).isEqualTo(201);
        assertThat(datos(trial)).containsEntry("plan", "pro").containsEntry("estado", "trialing");
        assertThat(PhpJson.decodeObject(trial.body()).get("mensaje")).isEqualTo("Suscripción creada exitosamente.");

        HttpResponse<String> conToken = send("POST", "/api/v1/suscripcion", "{\"plan_slug\":\"business\",\"token\":\"tkn_test\"}", auth);
        assertThat(conToken.statusCode()).as("sin cobro real con Culqi").isEqualTo(422);

        HttpResponse<String> noExiste = send("POST", "/api/v1/suscripcion", "{\"plan_slug\":\"oro\"}", auth);
        assertThat(noExiste.body()).contains("\"plan_slug\":[\"El campo plan slug no existe.\"]");

        HttpResponse<String> baja = send("PUT", "/api/v1/suscripcion/cambiar-plan", "{\"plan_slug\":\"free\"}", auth);
        assertThat(datos(baja)).containsEntry("plan", "free").containsEntry("estado", "pending");

        // Corrección: cambiar al mismo plan ya no crea otra suscripción "pending"
        HttpResponse<String> mismo = send("PUT", "/api/v1/suscripcion/cambiar-plan", "{\"plan_slug\":\"pro\"}", auth);
        assertThat(mismo.statusCode()).isEqualTo(422);

        // Corrección: la cancelación vale al final del periodo (API-PRO pasaba a Free al instante)
        // y cancela también la reducción programada
        HttpResponse<String> cancel = send("PUT", "/api/v1/suscripcion/cancelar", null, auth);
        assertThat(datos(cancel)).containsEntry("estado", "trialing").containsKey("acceso_hasta");
        assertThat(datos(cancel).get("cancelado_en")).isNotNull();
        Map<String, Object> vigente = datos(get("/api/v1/suscripcion", auth));
        assertThat(((Map<String, Object>) ((Map<String, Object>) vigente.get("suscripcion")).get("plan")).get("slug")).isEqualTo("pro");
        assertThat(PhpJson.encode(vigente.get("uso"))).contains("\"slug\":\"pro\"");
        long tid = tenants.findByRuc("20400000002").orElseThrow().getId();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions WHERE tenant_id = ? AND status = 'pending'", Long.class, tid)).isZero();
        HttpResponse<String> cancel2 = send("PUT", "/api/v1/suscripcion/cancelar", null, auth);
        assertThat(cancel2.statusCode()).isEqualTo(422);
        assertThat(cancel2.body()).contains("ya est\\u00e1 cancelada");
        assertThat(get("/api/v1/suscripcion/pagos", auth).body()).isEqualTo("{\"estado\":\"exito\",\"mensaje\":\"OK\",\"datos\":[]}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void recuperacionDeCredencialesDevuelveUnSecretQueAutentica() throws Exception {
        Map<String, String> auth = newTenant(tenants, "20400000003");
        Tenant t = tenants.findByRuc("20400000003").orElseThrow();
        t.setEmails(List.of("admin@empresa.pe"));
        tenants.save(t);

        HttpResponse<String> desconocido = send("POST", "/api/v1/credenciales/recuperar", "{\"ruc\":\"20999999999\"}", Map.of());
        assertThat(desconocido.body()).isEqualTo("{\"estado\":\"exito\",\"mensaje\":\"Si el RUC o email est\\u00e1 registrado, "
                + "recibir\\u00e1s las instrucciones en breve.\",\"datos\":null}");

        // Corrección: una empresa sin email responde igual que un RUC inexistente (no revela que existe)
        newTenant(tenants, "20400000009");
        HttpResponse<String> sinEmail = send("POST", "/api/v1/credenciales/recuperar", "{\"ruc\":\"20400000009\"}", Map.of());
        assertThat(sinEmail.statusCode()).isEqualTo(200);
        assertThat(sinEmail.body()).isEqualTo(desconocido.body());

        HttpResponse<String> ok = send("POST", "/api/v1/credenciales/recuperar", "{\"email\":\"ADMIN@empresa.pe\"}", Map.of());
        assertThat(ok.statusCode()).isEqualTo(200);
        Set<String> keys = redis.keys("credential_recovery:*");
        String token = keys.stream().filter(k -> "".equals("") && t.getId().toString().equals(redis.opsForValue().get(k)))
                .findFirst().orElseThrow().substring("credential_recovery:".length());

        HttpResponse<String> verif = send("POST", "/api/v1/credenciales/recuperar/verificar", "{\"token\":\"" + token + "\"}", Map.of());
        assertThat(verif.statusCode()).isEqualTo(200);
        Map<String, Object> d = datos(verif);
        assertThat(get("/api/v1/empresa", Map.of("X-Api-Key", (String) d.get("api_key"), "X-Api-Secret", (String) d.get("api_secret")))
                .statusCode()).as("BUG-01 corregido").isEqualTo(200);
        assertThat(get("/api/v1/empresa", auth).statusCode()).isEqualTo(401);

        HttpResponse<String> reuso = send("POST", "/api/v1/credenciales/recuperar/verificar", "{\"token\":\"" + token + "\"}", Map.of());
        assertThat(reuso.body()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"Token inv\\u00e1lido o expirado.\"}");
    }

    @Test
    void bridgeSaas() throws Exception {
        assertThat(send("POST", "/api/bridge/provision", "{}", Map.of()).body()).isEqualTo("{\"error\":\"No autorizado\"}");
        Map<String, String> key = Map.of("X-Bridge-Key", "bridge-test-secret");
        HttpResponse<String> prov = send("POST", "/api/bridge/provision", "{\"email\":\"dueno@saas.pe\",\"ruc\":\"20400000004\","
                + "\"razon_social\":\"SAAS SAC\",\"sol_user\":\"MODDATOS\",\"sol_pass\":\"moddatos\"}", key);
        assertThat(prov.statusCode()).isEqualTo(200);
        Map<String, Object> b = PhpJson.decodeObject(prov.body());
        assertThat(b.keySet()).containsExactly("api_key", "api_secret", "ruc", "razon_social", "environment");
        Map<String, String> auth = Map.of("X-Api-Key", (String) b.get("api_key"), "X-Api-Secret", (String) b.get("api_secret"));
        assertThat(get("/api/v1/empresa", auth).statusCode()).as("devuelve el secret real, no el cifrado").isEqualTo(200);
        assertThat(get("/api/v1/series", auth).body()).contains("F001").contains("BD01");

        HttpResponse<String> token = send("POST", "/api/bridge/auth", "{\"email\":\"dueno@saas.pe\",\"name\":\"Dueño\"}", key);
        assertThat(token.body()).contains("\"redirect_url\":").contains("\"expires_in\":600");

        assertThat(send("DELETE", "/api/bridge/tenant", "{\"ruc\":\"20400000004\"}", key).body()).isEqualTo("{\"success\":true}");
        assertThat(tenants.findByRuc("20400000004")).isEmpty();
    }

    @Test
    void bridgeNoAdoptaLaEmpresaDeOtro() throws Exception {
        Map<String, String> key = Map.of("X-Bridge-Key", "bridge-test-secret");
        String body = "{\"email\":\"%s\",\"ruc\":\"%s\",\"razon_social\":\"X SAC\",\"sol_user\":\"%s\",\"sol_pass\":\"moddatos\"}";
        HttpResponse<String> primero = send("POST", "/api/bridge/provision", body.formatted("uno@saas.pe", "20400000011", "MODDATOS"), key);
        assertThat(primero.statusCode()).isEqualTo(200);
        Map<String, Object> creds = PhpJson.decodeObject(primero.body());

        // Otro usuario del SaaS pide el mismo RUC: 409, sin cambiar SOL ni devolver credenciales, y no se crea su usuario
        HttpResponse<String> ajeno = send("POST", "/api/bridge/provision", body.formatted("dos@saas.pe", "20400000011", "OTROUSER"), key);
        assertThat(ajeno.statusCode()).isEqualTo(409);
        assertThat(ajeno.body()).doesNotContain((String) creds.get("api_key")).doesNotContain((String) creds.get("api_secret"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = 'dos@saas.pe'", Long.class)).isZero();
        assertThat(tenants.findByRuc("20400000011").orElseThrow().getSolUser().reveal()).isEqualTo("MODDATOS");

        // Una empresa del registro público / consola (sin dueño) tampoco se adopta
        newTenant(tenants, "20400000012");
        assertThat(send("POST", "/api/bridge/provision", body.formatted("uno@saas.pe", "20400000012", "MODDATOS"), key).statusCode()).isEqualTo(409);

        // El dueño sí puede volver a provisionar y recibe sus mismas credenciales
        HttpResponse<String> dueno = send("POST", "/api/bridge/provision", body.formatted("uno@saas.pe", "20400000011", "NUEVOSOL"), key);
        assertThat(dueno.statusCode()).isEqualTo(200);
        assertThat(PhpJson.decodeObject(dueno.body()).get("api_key")).isEqualTo(creds.get("api_key"));
        assertThat(tenants.findByRuc("20400000011").orElseThrow().getSolUser().reveal()).isEqualTo("NUEVOSOL");
    }
}
