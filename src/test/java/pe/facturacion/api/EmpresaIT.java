package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.support.IntegrationTest;

/** Contrato de /api/v1/empresa (TenantController de API-PRO). */
class EmpresaIT extends IntegrationTest {

    @Autowired
    TenantRepository tenants;
    @Autowired
    FileStorage storage;

    @Test
    @SuppressWarnings("unchecked")
    void showDevuelveTenantResourceConElMismoOrdenDeClaves() throws Exception {
        Map<String, String> auth = newTenant(tenants, "20100000001");
        HttpResponse<String> r = get("/api/v1/empresa", auth);
        assertThat(r.statusCode()).isEqualTo(200);
        Map<String, Object> datos = (Map<String, Object>) PhpJson.decodeObject(r.body()).get("datos");
        assertThat(datos.keySet()).containsExactly("id", "ruc", "razon_social", "nombre_comercial", "direccion", "ubigeo",
                "departamento", "provincia", "distrito", "sol_user", "tiene_sol_pass", "client_id", "tiene_client_secret",
                "webhook_url", "entorno", "plan", "tax_regime", "igv_rate_override", "nrus_categoria", "tasa_igv_vigente",
                "max_documentos_mes", "documentos_este_mes", "activo", "logo_url", "tiene_certificado", "telefonos", "emails",
                "cuentas_bancarias", "billeteras_digitales", "mensaje_agradecimiento", "mensaje_promocional", "tiene_webhook", "creado_en");
        assertThat(datos.get("sol_user")).isEqualTo("MODDATOS");
        assertThat(datos.get("tiene_sol_pass")).isEqualTo(true);
        assertThat(datos.get("tasa_igv_vigente")).isEqualTo(18);
        assertThat(datos.get("telefonos")).isEqualTo(List.of());
        assertThat(r.body()).contains("\"tasa_igv_vigente\":18,");
        // El alias del SaaS devuelve lo mismo
        assertThat(get("/api/sunat/configuracion", auth).body()).isEqualTo(r.body());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateValidaYMapeaCamposComoApiPro() throws Exception {
        Map<String, String> auth = newTenant(tenants, "20100000002");
        HttpResponse<String> bad = send("PUT", "/api/v1/empresa",
                "{\"entorno\":\"produccion\",\"igv_rate_override\":45,\"emails\":[\"x\"],\"cuentas_bancarias\":[{\"banco\":\"BCP\"}]}", auth);
        assertThat(bad.statusCode()).isEqualTo(422);
        assertThat(bad.body()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"Error de validaci\\u00f3n\",\"errores\":{"
                + "\"entorno\":[\"El campo entorno no est\\u00e1 en la lista de valores permitidos.\"],"
                + "\"igv_rate_override\":[\"El campo igv rate override tiene que estar entre 0 - 30.\"],"
                + "\"emails.0\":[\"El campo emails.0 no es un correo v\\u00e1lido.\"],"
                + "\"cuentas_bancarias.0.tipo_cuenta\":[\"El campo cuentas_bancarias.0.tipo_cuenta es obligatorio.\"],"
                + "\"cuentas_bancarias.0.moneda\":[\"El campo cuentas_bancarias.0.moneda es obligatorio.\"],"
                + "\"cuentas_bancarias.0.numero\":[\"El campo cuentas_bancarias.0.numero es obligatorio.\"]}}");

        HttpResponse<String> ok = send("PUT", "/api/v1/empresa",
                "{\"razon_social\":\"NUEVA RAZON SAC\",\"url_webhook\":\"https://mi.erp/webhook\",\"telefonos\":[\"999888777\"],"
                        + "\"tax_regime\":\"rus\",\"nrus_categoria\":2,\"igv_rate_override\":10.5,\"direccion\":null}", auth);
        assertThat(ok.statusCode()).isEqualTo(200);
        Map<String, Object> body = PhpJson.decodeObject(ok.body());
        assertThat(body.get("mensaje")).isEqualTo("Empresa actualizada.");
        Map<String, Object> d = (Map<String, Object>) body.get("datos");
        assertThat(d.get("razon_social")).isEqualTo("NUEVA RAZON SAC");
        assertThat(d.get("webhook_url")).isEqualTo("https://mi.erp/webhook");
        assertThat(d.get("tiene_webhook")).isEqualTo(true);
        assertThat(d.get("nrus_categoria")).isEqualTo(2);
        assertThat(d.get("tasa_igv_vigente")).as("el override tiene prioridad sobre RUS").isEqualTo(new java.math.BigDecimal("10.5"));

        // Cambiar a un régimen distinto de RUS limpia nrus_categoria; null explícito borra el override
        HttpResponse<String> again = send("PUT", "/api/v1/empresa", "{\"tax_regime\":\"general\",\"igv_rate_override\":null}", auth);
        Map<String, Object> d2 = (Map<String, Object>) PhpJson.decodeObject(again.body()).get("datos");
        assertThat(d2.get("nrus_categoria")).isNull();
        assertThat(d2.get("igv_rate_override")).isNull();
        assertThat(d2.get("tasa_igv_vigente")).isEqualTo(18);
    }

    @Test
    void certificadoPfxLegacyModernoYErroresConLosMensajesDeApiPro() throws Exception {
        Map<String, String> auth = newTenant(tenants, "20100000003");
        byte[] legacy = resource("/fixtures/certs/legacy-rc2.pfx");

        HttpResponse<String> sinClave = multipart("POST", "/api/v1/empresa/certificado", Map.of(),
                Map.of("certificado", new Part("cert.pfx", legacy)), auth);
        assertThat(sinClave.statusCode()).isEqualTo(422);
        assertThat(sinClave.body()).isEqualTo(
                "{\"estado\":\"error\",\"mensaje\":\"El campo contrasena_certificado es obligatorio para archivos .pfx\\/.p12\"}");

        HttpResponse<String> claveMala = multipart("POST", "/api/v1/empresa/certificado", Map.of("contrasena_certificado", "mala"),
                Map.of("certificado", new Part("cert.pfx", legacy)), auth);
        assertThat(claveMala.statusCode()).isEqualTo(422);
        assertThat(claveMala.body()).contains("No se pudo leer el archivo PFX. Verifique que el password sea correcto.");

        HttpResponse<String> soloPublico = multipart("POST", "/api/v1/empresa/certificado", Map.of(),
                Map.of("certificado", new Part("sunat.cer", resource("/fixtures/certs/solo-publico.cer"))), auth);
        assertThat(soloPublico.statusCode()).isEqualTo(422);
        assertThat(soloPublico.body()).contains("NO la clave privada");

        HttpResponse<String> formato = multipart("POST", "/api/v1/empresa/certificado", Map.of(),
                Map.of("certificado", new Part("cert.txt", "hola".getBytes())), auth);
        assertThat(formato.body()).isEqualTo("{\"estado\":\"error\",\"mensaje\":\"Formato no soportado. Use .pfx, .p12, .pem, .cer o .crt\"}");

        for (String file : List.of("legacy-rc2.pfx", "moderno.pfx")) {
            HttpResponse<String> ok = multipart("POST", "/api/v1/empresa/certificado", Map.of("contrasena_certificado", "test1234"),
                    Map.of("certificado", new Part(file, resource("/fixtures/certs/" + file))), auth);
            assertThat(ok.statusCode()).as(file).isEqualTo(200);
            assertThat(ok.body()).isEqualTo("{\"estado\":\"exito\",\"mensaje\":\"Certificado actualizado y convertido a PEM.\",\"datos\":null}");
        }
        HttpResponse<String> pem = multipart("POST", "/api/v1/empresa/certificado", Map.of(),
                Map.of("certificado", new Part("cert.pem", resource("/fixtures/certs/cert-con-clave.pem"))), auth);
        assertThat(pem.statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/empresa", auth).body()).contains("\"tiene_certificado\":true");
    }

    @Test
    void logoSeGuardaYSeSirveEnStorage() throws Exception {
        Map<String, String> auth = newTenant(tenants, "20100000004");
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        HttpResponse<String> r = multipart("POST", "/api/v1/empresa/logo", Map.of(), Map.of("logo", new Part("mi-logo.png", png)), auth);
        assertThat(r.statusCode()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> datos = (Map<String, Object>) PhpJson.decodeObject(r.body()).get("datos");
        assertThat(datos.get("logo_path")).isEqualTo("logos/20100000004/logo.png");
        HttpResponse<String> img = get("/storage/logos/20100000004/logo.png", Map.of());
        assertThat(img.statusCode()).isEqualTo(200);

        HttpResponse<String> svg = multipart("POST", "/api/v1/empresa/logo", Map.of(),
                Map.of("logo", new Part("x.png", "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes())), auth);
        assertThat(svg.statusCode()).as("un SVG disfrazado de PNG se rechaza").isEqualTo(422);

        // Corrección: al eliminar la empresa se borran su certificado (clave privada) y su logo
        assertThat(multipart("POST", "/api/v1/empresa/certificado", Map.of(),
                Map.of("certificado", new Part("cert.pem", resource("/fixtures/certs/cert-con-clave.pem"))), auth).statusCode()).isEqualTo(200);
        assertThat(storage.exists(FileStorage.Disk.PRIVATE, "certificates/20100000004/cert.pem.enc")).isTrue();
        HttpResponse<String> del = send("DELETE", "/api/v1/empresa", null, auth);
        assertThat(del.statusCode()).as(del.body()).isEqualTo(200);
        assertThat(storage.exists(FileStorage.Disk.PRIVATE, "certificates/20100000004/cert.pem.enc")).isFalse();
        assertThat(get("/storage/logos/20100000004/logo.png", Map.of()).statusCode()).isEqualTo(404);
    }

    @Test
    void credencialesOcultasYRegeneracion() throws Exception {
        Map<String, String> auth = newTenant(tenants, "20100000005");
        HttpResponse<String> show = get("/api/v1/empresa/credenciales", auth);
        assertThat(show.body()).contains("\"api_secret\":\"*** oculto \\u2014 use POST \\/empresa\\/credenciales\\/regenerar");

        HttpResponse<String> regen = send("POST", "/api/v1/empresa/credenciales/regenerar", "{}", auth);
        assertThat(regen.statusCode()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) PhpJson.decodeObject(regen.body()).get("datos");
        assertThat((String) d.get("api_key")).hasSize(64);
        assertThat((String) d.get("api_secret")).hasSize(64);
        assertThat(get("/api/v1/empresa", auth).statusCode()).as("las anteriores ya no sirven").isEqualTo(401);
        assertThat(get("/api/v1/empresa", Map.of("X-Api-Key", (String) d.get("api_key"), "X-Api-Secret", (String) d.get("api_secret")))
                .statusCode()).isEqualTo(200);
    }

    @Test
    void destroyBorraLaEmpresa() throws Exception {
        Map<String, String> auth = newTenant(tenants, "20100000006");
        HttpResponse<String> r = send("DELETE", "/api/v1/empresa", null, auth);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).matches("\\{\"estado\":\"exito\",\"mensaje\":\"Empresa eliminada.\",\"datos\":\\{\"tenant_id\":\\d+}}");
        assertThat(tenants.findByRuc("20100000006")).isEmpty();
    }
}
