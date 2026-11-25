package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pe.facturacion.api.modules.certificate.CertificateStore;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.support.FakeSunat;
import pe.facturacion.api.support.IntegrationTest;

/**
 * Flujo completo contra un SUNAT simulado: emisión → cola → firma → ZIP → sendBill → CDR →
 * estado, archivos, webhook firmado; rechazos, observaciones, soap:Fault, pagos, edición,
 * reenvío y boletas para resumen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SunatFlowIT extends IntegrationTest {

    static final FakeSunat SUNAT;
    static final String RUC = "20111111111";

    static {
        try {
            SUNAT = new FakeSunat();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void sunatProps(DynamicPropertyRegistry r) {
        r.add("app.sunat.beta.fe", () -> SUNAT.url("/billService"));
        r.add("app.queue.poll-ms", () -> "100");
        r.add("app.webhooks.allow-private", () -> "true");
    }

    @Autowired
    TenantRepository tenants;
    @Autowired
    CertificateStore certificates;
    @Autowired
    JdbcTemplate jdbc;

    Map<String, String> auth;

    @BeforeAll
    void setUp() throws Exception {
        auth = new HashMap<>(newTenant(tenants, RUC));
        auth.put("Accept", "application/json");
        Tenant t = tenants.findByRuc(RUC).orElseThrow();
        t.setNombreComercial("PRUEBAS FLUJO");
        t.setDireccion("AV. PRUEBA 456");
        t.setUbigeo("150101");
        t.setDepartamento("LIMA");
        t.setProvincia("LIMA");
        t.setDistrito("LIMA");
        t.setWebhookUrl(SUNAT.url("/webhook"));
        t.setCertificatePath(certificates.store(t, new String(resource("/fixtures/certs/cert-con-clave.pem"), StandardCharsets.US_ASCII)));
        tenants.save(t);
        for (String[] s : new String[][] {{"01", "F001"}, {"03", "B001"}}) {
            jdbc.update("INSERT INTO series (tenant_id, tipo_documento, serie, correlativo, is_active, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 0, true, now(), now())", t.getId(), s[0], s[1]);
        }
    }

    @AfterAll
    void tearDown() {
        SUNAT.close();
    }

    static String factura(double precio) {
        return "{\"serie\":\"F001\",\"fecha_emision\":\"2026-10-24\",\"cliente\":{\"tipo_doc\":\"6\",\"num_doc\":\"20601234567\","
                + "\"razon_social\":\"CLIENTE & ASOCIADOS S.A.C.\",\"email\":\"cliente@example.com\"},"
                + "\"items\":[{\"codigo\":\"P1\",\"descripcion\":\"Servicio <especial>\",\"unidad\":\"ZZ\",\"cantidad\":2,\"precio_unitario\":" + precio + "}]}";
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> datos(HttpResponse<String> res) {
        return (Map<String, Object>) PhpJson.decodeObject(res.body()).get("datos");
    }

    /** Espera hasta 20 s a que el comprobante cumpla la condición. */
    @SuppressWarnings("unchecked")
    Map<String, Object> await(String path, Predicate<Map<String, Object>> condition) throws Exception {
        long until = System.currentTimeMillis() + 20_000;
        Map<String, Object> d = null;
        while (System.currentTimeMillis() < until) {
            d = datos(get(path, auth));
            if (condition.test(d)) {
                return d;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Tiempo agotado esperando " + path + ": " + PhpJson.encode(d));
    }

    @SuppressWarnings("unchecked")
    static String estado(Map<String, Object> d) {
        return String.valueOf(((Map<String, Object>) d.get("sunat")).get("estado"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sunat(Map<String, Object> d) {
        return (Map<String, Object>) d.get("sunat");
    }

    @Test
    @Order(1)
    @SuppressWarnings("unchecked")
    void facturaAceptadaConCdrXmlYWebhook() throws Exception {
        HttpResponse<String> res = send("POST", "/api/v1/facturas", factura(118), auth);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        Map<String, Object> d = datos(res);
        assertThat(estado(d)).isEqualTo("enviado");
        long id = ((Number) d.get("id")).longValue();

        Map<String, Object> ok = await("/api/v1/facturas/" + id, x -> estado(x).equals("aceptado"));
        assertThat(sunat(ok).get("codigo")).isEqualTo("0");
        assertThat(sunat(ok).get("hash_cpe")).asString().hasSize(28);
        Map<String, Object> archivos = (Map<String, Object>) ok.get("archivos");
        assertThat(archivos.get("xml")).asString().endsWith("/api/v1/facturas/" + id + "/xml");
        assertThat(archivos.get("cdr")).asString().endsWith("/api/v1/facturas/" + id + "/cdr");

        FakeSunat.Received r = SUNAT.received.get(0);
        assertThat(r.fileName()).isEqualTo(RUC + "-01-F001-1.zip");
        assertThat(r.signatureValid()).as("firma XMLDSig válida").isTrue();
        assertThat(r.soapUser()).isEqualTo(RUC + "MODDATOS");
        assertThat(r.xml()).contains("<![CDATA[CLIENTE & ASOCIADOS S.A.C.]]>").contains("<![CDATA[Servicio <especial>]]>");

        HttpResponse<String> xml = get("/api/v1/facturas/" + id + "/xml", auth);
        assertThat(xml.statusCode()).isEqualTo(200);
        assertThat(xml.headers().firstValue("Content-Disposition").orElse("")).contains("F001-000001.xml");
        assertThat(xml.body()).isEqualTo(r.xml());

        HttpResponse<byte[]> cdr = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/v1/facturas/" + id + "/cdr"))
                .headers(auth.entrySet().stream().flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue())).toArray(String[]::new))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(cdr.statusCode()).isEqualTo(200);
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(cdr.body()))) {
            assertThat(z.getNextEntry()).isNotNull();
        }

        long until = System.currentTimeMillis() + 10_000;
        while (SUNAT.webhooks.isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(100);
        }
        FakeSunat.Webhook w = SUNAT.webhooks.get(0);
        assertThat(w.event()).isEqualTo("document.sent");
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(("secret-" + RUC).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertThat(w.signature()).isEqualTo("sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(w.body().getBytes(StandardCharsets.UTF_8))));
        Map<String, Object> payload = PhpJson.decodeObject(w.body());
        assertThat(((Map<String, Object>) payload.get("document")).get("numero_completo")).isEqualTo("F001-000001");
        assertThat(((Map<String, Object>) payload.get("document")).get("total")).isEqualTo("236.00");

        HttpResponse<String> again = send("POST", "/api/v1/facturas/" + id + "/enviar", null, auth);
        assertThat(again.statusCode()).isEqualTo(422);
        assertThat(again.body()).contains("Esta factura ya fue aceptada por SUNAT.");
    }

    @Test
    @Order(2)
    void rechazoPorCdrFaultYObservacion() throws Exception {
        SUNAT.responses.put("F001-2", "2017");
        SUNAT.responses.put("F001-3", "FAULT:0306");
        SUNAT.responses.put("F001-4", "4252");
        long r1 = ((Number) datos(send("POST", "/api/v1/facturas", factura(10), auth)).get("id")).longValue();
        long r2 = ((Number) datos(send("POST", "/api/v1/facturas", factura(20), auth)).get("id")).longValue();
        long r3 = ((Number) datos(send("POST", "/api/v1/facturas", factura(30), auth)).get("id")).longValue();

        Map<String, Object> rej = await("/api/v1/facturas/" + r1, x -> estado(x).equals("rechazado"));
        assertThat(sunat(rej).get("codigo")).isEqualTo("2017");
        assertThat(sunat(rej).get("descripcion")).isEqualTo("Rechazo simulado 2017");

        Map<String, Object> fault = await("/api/v1/facturas/" + r2, x -> estado(x).equals("rechazado"));
        assertThat(sunat(fault).get("codigo")).isEqualTo("0306");

        Map<String, Object> obs = await("/api/v1/facturas/" + r3, x -> estado(x).equals("aceptado"));
        assertThat(sunat(obs).get("codigo")).isEqualTo("4252");
        assertThat(sunat(obs).get("notas")).isEqualTo(List.of("4252 - Observación simulada"));

        // Corregir la rechazada: se recalcula con la tasa del régimen y se reenvía
        SUNAT.responses.put("F001-2", "0");
        HttpResponse<String> upd = send("PUT", "/api/v1/facturas/" + r1,
                "{\"items\":[{\"codigo\":\"P9\",\"cod_producto_sunat\":\"43211503\",\"descripcion\":\"Laptop\",\"unidad\":\"NIU\",\"cantidad\":1,\"precio_unitario\":1180}]}", auth);
        assertThat(upd.statusCode()).as(upd.body()).isEqualTo(200);
        assertThat(upd.body()).contains("Factura actualizada y reenviada a SUNAT.");
        Map<String, Object> fixed = await("/api/v1/facturas/" + r1, x -> estado(x).equals("aceptado"));
        assertThat(fixed.get("leyenda")).isEqualTo("MIL CIENTO OCHENTA CON 00/100 SOLES");
        String sent = SUNAT.received.get(SUNAT.received.size() - 1).xml();
        assertThat(sent).contains("<cbc:ItemClassificationCode>43211503</cbc:ItemClassificationCode>");
        assertThat(sent).contains("<cbc:PayableAmount currencyID=\"PEN\">1180.00</cbc:PayableAmount>");

        HttpResponse<String> list = get("/api/v1/facturas?sunat_status=rechazado", auth);
        assertThat(PhpJson.encode(datos(list).get("paginacion"))).contains("\"total\":1");
    }

    @Test
    @Order(3)
    @SuppressWarnings("unchecked")
    void pagos() throws Exception {
        long id = ((Number) datos(send("POST", "/api/v1/facturas", factura(100), auth)).get("id")).longValue();
        HttpResponse<String> p = send("POST", "/api/v1/facturas/" + id + "/pagos",
                "{\"metodo\":\"efectivo\",\"monto\":100,\"monto_recibido\":150}", auth);
        assertThat(p.statusCode()).as(p.body()).isEqualTo(200);
        Map<String, Object> body = PhpJson.decodeObject(p.body());
        assertThat(body.get("estado_pago")).isEqualTo("parcial");
        Map<String, Object> pago = (Map<String, Object>) ((List<Object>) body.get("pagos")).get(0);
        assertThat(((Number) pago.get("vuelto")).doubleValue()).isEqualTo(50.0);

        HttpResponse<String> invalid = send("POST", "/api/v1/facturas/" + id + "/pagos", "{\"metodo\":\"bitcoin\",\"monto\":1}", auth);
        assertThat(invalid.statusCode()).isEqualTo(422);

        HttpResponse<String> idx = get("/api/v1/facturas/" + id + "/pagos", auth);
        assertThat(((Number) PhpJson.decodeObject(idx.body()).get("total_documento")).doubleValue()).isEqualTo(200.0);

        HttpResponse<String> del = send("DELETE", "/api/v1/facturas/" + id + "/pagos/" + pago.get("id"), null, auth);
        assertThat(del.statusCode()).isEqualTo(200);
        assertThat(PhpJson.decodeObject(del.body()).get("estado_pago")).isEqualTo("pendiente");
        assertThat(send("DELETE", "/api/v1/facturas/" + id + "/pagos/999999", null, auth).statusCode()).isEqualTo(404);
    }

    @Test
    @Order(4)
    void boletaSoloRegistroYEliminacion() throws Exception {
        int before = SUNAT.received.size();
        HttpResponse<String> res = send("POST", "/api/v1/boletas",
                "{\"serie\":\"B001\",\"fecha_emision\":\"2026-10-24\",\"solo_registro\":true,\"cliente\":{\"tipo_doc\":\"1\",\"num_doc\":\"12345678\",\"razon_social\":\"JUAN PEREZ\"},"
                        + "\"items\":[{\"descripcion\":\"Menú\",\"unidad\":\"NIU\",\"cantidad\":1,\"precio_unitario\":25}]}", auth);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        assertThat(PhpJson.decodeObject(res.body()).get("mensaje")).isEqualTo("Boleta registrada. Pendiente de envío vía resumen diario.");
        Map<String, Object> d = datos(res);
        assertThat(estado(d)).isEqualTo("pendiente");
        long id = ((Number) d.get("id")).longValue();
        Thread.sleep(800);
        assertThat(SUNAT.received.size()).isEqualTo(before);

        assertThat(send("DELETE", "/api/v1/boletas/" + id, null, auth).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/boletas/" + id, auth).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/boletas/abc", auth).statusCode()).isEqualTo(404);
    }

    @Test
    @Order(5)
    void validacionesDeNegocio() throws Exception {
        HttpResponse<String> dni = send("POST", "/api/v1/facturas",
                factura(10).replace("\"tipo_doc\":\"6\",\"num_doc\":\"20601234567\"", "\"tipo_doc\":\"1\",\"num_doc\":\"12345678\""), auth);
        assertThat(dni.statusCode()).isEqualTo(422);
        assertThat(dni.body()).contains("Las facturas requieren cliente con RUC (tipo_doc=6)");

        HttpResponse<String> serie = send("POST", "/api/v1/facturas", factura(10).replace("F001", "F999"), auth);
        assertThat(serie.statusCode()).isEqualTo(422);
        assertThat(serie.body()).contains("No tienes registrada la serie F999 para facturas.");

        HttpResponse<String> dup = send("POST", "/api/v1/facturas", factura(10).replace("\"serie\":\"F001\"", "\"serie\":\"F001\",\"correlativo\":1"), auth);
        assertThat(dup.statusCode()).isEqualTo(422);
        assertThat(dup.body()).contains("El correlativo 1 ya existe para la serie F001.");

        HttpResponse<String> masivo = send("POST", "/api/v1/facturas/masivo",
                "{\"facturas\":[" + factura(10) + ",{\"serie\":\"F001\"},5]}", auth);
        assertThat(masivo.statusCode()).as(masivo.body()).isEqualTo(201);
        assertThat(masivo.body()).contains("\"creadas\":1").contains("\"errores\":2");
        // Corrección: si no se crea ninguna, "estado" es "error" (API-PRO respondía 422 con "exito")
        HttpResponse<String> ninguna = send("POST", "/api/v1/facturas/masivo", "{\"facturas\":[{\"serie\":\"F001\"}]}", auth);
        assertThat(ninguna.statusCode()).isEqualTo(422);
        assertThat(ninguna.body()).startsWith("{\"estado\":\"error\"");
    }
}
