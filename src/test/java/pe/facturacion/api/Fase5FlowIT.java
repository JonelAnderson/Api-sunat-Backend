package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
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
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.support.FakeSunat;
import pe.facturacion.api.support.IntegrationTest;

/**
 * Fase 5 de punta a punta contra un SUNAT simulado: retenciones y percepciones por el servicio
 * "otros CPE", reversión (RR) con su ticket en ese mismo servicio y guías por la API REST GRE
 * (token OAuth2, envío con hash del ZIP, ticket, CDR, token revocado y rechazo).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Fase5FlowIT extends IntegrationTest {

    static final FakeSunat SUNAT;
    static final String RUC = "20333333333";

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
        r.add("app.sunat.beta.retention", () -> SUNAT.url("/otroscpe/billService"));
        r.add("app.sunat.beta.gre-auth", () -> SUNAT.url("/gre"));
        r.add("app.sunat.beta.gre-cpe", () -> SUNAT.url("/gre"));
        r.add("app.sunat.beta.gre-client-id", () -> "cliente-prueba");
        r.add("app.sunat.beta.gre-client-secret", () -> "secreto-prueba");
        r.add("app.webhooks.allow-private", () -> "true");
        r.add("app.queue.poll-ms", () -> "100");
        r.add("app.sunat.ticket-check-delay", () -> "1");
    }

    @Autowired
    TenantRepository tenants;
    @Autowired
    CertificateStore certificates;
    @Autowired
    JdbcTemplate jdbc;

    Map<String, String> auth;
    final String hoy = Clock.todayLima().toString();

    @BeforeAll
    void setUp() throws Exception {
        SUNAT.pendingPolls = 1;
        auth = new HashMap<>(newTenant(tenants, RUC));
        auth.put("Accept", "application/json");
        Tenant t = tenants.findByRuc(RUC).orElseThrow();
        t.setDireccion("AV. GUIAS 1");
        t.setUbigeo("150101");
        t.setWebhookUrl(SUNAT.url("/webhook"));
        t.setCertificatePath(certificates.store(t, new String(resource("/fixtures/certs/cert-con-clave.pem"), StandardCharsets.US_ASCII)));
        tenants.save(t);
        for (String[] s : new String[][] {{"20", "R001"}, {"40", "P001"}, {"09", "T001"}, {"31", "V001"}}) {
            jdbc.update("INSERT INTO series (tenant_id, tipo_documento, serie, correlativo, is_active, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 0, true, now(), now())", t.getId(), s[0], s[1]);
        }
    }

    @AfterAll
    void tearDown() {
        SUNAT.close();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> datos(HttpResponse<String> res) {
        return (Map<String, Object>) PhpJson.decodeObject(res.body()).get("datos");
    }

    Map<String, Object> await(String path, Predicate<Map<String, Object>> condition) throws Exception {
        long until = System.currentTimeMillis() + 25_000;
        Map<String, Object> d = null;
        while (System.currentTimeMillis() < until) {
            d = datos(get(path, auth));
            if (d != null && condition.test(d)) {
                return d;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Tiempo agotado esperando " + path + ": " + PhpJson.encode(d));
    }

    static void until(BooleanSupplier condition) throws InterruptedException {
        long until = System.currentTimeMillis() + 25_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < until) {
            Thread.sleep(200);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    long create(String path, String body) throws Exception {
        HttpResponse<String> res = send("POST", path, body, auth);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        return ((Number) datos(res).get("id")).longValue();
    }

    String retencion(String moneda, String extra) {
        return "{\"serie\":\"R001\",\"fecha_emision\":\"" + hoy + "\",\"regimen\":\"01\",\"tasa\":3,"
                + "\"proveedor\":{\"tipo_doc\":\"6\",\"num_doc\":\"20444444444\",\"razon_social\":\"PROVEEDOR & HIJOS SAC\"},"
                + "\"documentos\":[{\"tipo_doc\":\"01\",\"num_doc\":\"F001-10\",\"fecha_emision\":\"" + hoy + "\",\"imp_total\":1000,"
                + "\"moneda\":\"" + moneda + "\",\"fecha_retencion\":\"" + hoy + "\",\"pagos\":[{\"importe\":1000,\"fecha\":\"" + hoy + "\"}]"
                + extra + "}]}";
    }

    static String estado(Map<String, Object> d) {
        return String.valueOf(d.get("sunat_status"));
    }

    @SuppressWarnings("unchecked")
    static String sunatEstado(Map<String, Object> d) {
        return String.valueOf(((Map<String, Object>) d.get("sunat")).get("estado"));
    }

    @Test
    @Order(1)
    void retencionAceptadaPorOtrosCpeYReversion() throws Exception {
        long id = create("/api/v1/retenciones", retencion("PEN", ""));
        Map<String, Object> d = await("/api/v1/retenciones/" + id, x -> estado(x).equals("aceptado"));
        assertThat(d.get("sunat_code")).isEqualTo("0");
        assertThat(SUNAT.otrosCpe).contains(RUC + "-20-R001-1.zip");
        FakeSunat.Received r = SUNAT.received.stream().filter(x -> x.fileName().equals(RUC + "-20-R001-1.zip")).findFirst().orElseThrow();
        assertThat(r.signatureValid()).isTrue();
        assertThat(r.xml()).contains("<sac:SUNATRetentionAmount currencyID=\"PEN\">30.00</sac:SUNATRetentionAmount>")
                .contains("<![CDATA[PROVEEDOR & HIJOS SAC]]>");
        assertThat(get("/api/v1/retenciones/" + id + "/xml", auth).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/retenciones/" + id + "/cdr", auth).statusCode()).isEqualTo(200);
        until(() -> SUNAT.webhooks.stream().anyMatch(w -> "retention.sent".equals(w.event())));
        FakeSunat.Webhook w = SUNAT.webhooks.stream().filter(x -> "retention.sent".equals(x.event())).findFirst().orElseThrow();
        assertThat(w.body()).contains("\"tipo_documento\":\"20\"").contains("\"total\":\"30.00\"").contains("\"numero_completo\":\"R001-00000001\"");

        // Corrección: fecha_generacion distinta de la emisión de la retención → 422
        HttpResponse<String> otraFecha = send("POST", "/api/v1/reversiones", "{\"fecha_generacion\":\"2026-04-19\",\"detalles\":"
                + "[{\"tipo_documento\":\"20\",\"serie\":\"R001\",\"correlativo\":\"1\",\"motivo\":\"Error\"}]}", auth);
        assertThat(otraFecha.statusCode()).isEqualTo(422);
        assertThat(otraFecha.body()).contains("fue emitido el " + hoy);

        // Reversión: queda "anulacion_en_proceso" hasta que SUNAT responde el ticket (en "otros CPE")
        HttpResponse<String> rr = send("POST", "/api/v1/reversiones", "{\"fecha_generacion\":\"" + hoy + "\",\"detalles\":"
                + "[{\"tipo_documento\":\"20\",\"serie\":\"R001\",\"correlativo\":\"1\",\"motivo\":\"Error\"}]}", auth);
        assertThat(rr.statusCode()).as(rr.body()).isEqualTo(201);
        Map<String, Object> rd = datos(rr);
        assertThat(String.valueOf(rd.get("consulta_estado"))).endsWith("/api/v1/anulaciones/" + rd.get("voided_id") + "/estado");
        assertThat(estado(datos(get("/api/v1/retenciones/" + id, auth)))).isIn("anulacion_en_proceso", "anulado");
        await("/api/v1/anulaciones/" + rd.get("voided_id") + "/estado", x -> "aceptado".equals(x.get("estado_sunat")));
        assertThat(estado(datos(get("/api/v1/retenciones/" + id, auth)))).isEqualTo("anulado");
        assertThat(SUNAT.otrosCpe).anyMatch(s -> s.startsWith(RUC + "-RR-")).anyMatch(s -> s.startsWith("getStatus:"));

        // Duplicado: el documento ya no está aceptado
        HttpResponse<String> dup = send("POST", "/api/v1/reversiones", "{\"fecha_generacion\":\"" + hoy + "\",\"detalles\":"
                + "[{\"tipo_documento\":\"20\",\"serie\":\"R001\",\"correlativo\":\"1\",\"motivo\":\"Otra vez\"}]}", auth);
        assertThat(dup.statusCode()).isEqualTo(422);

        // Corrección: reenviar una retención revertida la revivía como "aceptado"
        HttpResponse<String> reenvio = send("POST", "/api/v1/retenciones/" + id + "/enviar", null, auth);
        assertThat(reenvio.statusCode()).isEqualTo(422);
        assertThat(reenvio.body()).contains("revertida");
        assertThat(estado(datos(get("/api/v1/retenciones/" + id, auth)))).isEqualTo("anulado");
    }

    @Test
    @Order(2)
    void percepcionAceptada() throws Exception {
        long id = create("/api/v1/percepciones", "{\"serie\":\"P001\",\"fecha_emision\":\"" + hoy + "\",\"regimen\":\"01\",\"tasa\":2,"
                + "\"cliente\":{\"tipo_doc\":\"6\",\"num_doc\":\"20555555555\",\"razon_social\":\"CLIENTE SAC\"},"
                + "\"documentos\":[{\"tipo_doc\":\"01\",\"num_doc\":\"F001-20\",\"fecha_emision\":\"" + hoy + "\",\"imp_total\":500,"
                + "\"fecha_percepcion\":\"" + hoy + "\",\"cobros\":[{\"importe\":500,\"fecha\":\"" + hoy + "\"}]}]}");
        Map<String, Object> d = await("/api/v1/percepciones/" + id, x -> estado(x).equals("aceptado"));
        assertThat(((Number) d.get("imp_percibido")).doubleValue()).isEqualTo(10.0);
        assertThat(((Number) d.get("imp_cobrado")).doubleValue()).isEqualTo(510.0);
        assertThat(SUNAT.otrosCpe).contains(RUC + "-40-P001-1.zip");

        // PDF de la percepción (API-PRO no lo tenía) y "formato" aceptado en todos los documentos
        var pdf = getBytes("/api/v1/percepciones/" + id + "/pdf?formato=ticket-80", auth);
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("Content-Disposition")).hasValue("inline; filename=\"P001-00000001.pdf\"");
        String text = pdfText(pdf.body()).replaceAll("\\s+", " ");
        assertThat(text).contains("COMPROBANTE DE PERCEPCIÓN ELECTRÓNICA").contains("Imp. a Cobrar").contains("S/ 510.00")
                .contains("Percepción Venta Interna (2%)");
        assertThat(Fase6OracleIT.qr(pdf.body())).isEqualTo(RUC + "|40|P001|1|10.00|510.00|" + hoy + "|6|20555555555");
        // Mejora M1: una sola página del alto del contenido (API-PRO: siempre 841.89 pt)
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf.body())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            assertThat(doc.getPage(0).getMediaBox().getWidth()).isCloseTo(226.77f, org.assertj.core.data.Offset.offset(0.1f));
            assertThat(doc.getPage(0).getMediaBox().getHeight()).isBetween(200f, 800f);
        }
        var a4 = getBytes("/api/v1/percepciones/" + id + "/pdf?format=a4", auth);
        assertThat(pdfText(a4.body()).replaceAll("\\s+", " ")).contains("Total Imp. Percibido:").contains("F. Percepción");
        assertThat(getBytes("/api/v1/percepciones/" + id + "/pdf?formato=oficio", auth).statusCode()).isEqualTo(422);
    }

    @Test
    @Order(3)
    @SuppressWarnings("unchecked")
    void monedaExtranjeraExigeTipoDeCambioYSeConvierteASoles() throws Exception {
        HttpResponse<String> sin = send("POST", "/api/v1/retenciones", retencion("USD", ""), auth);
        assertThat(sin.statusCode()).isEqualTo(422);
        assertThat(((Map<String, Object>) PhpJson.decodeObject(sin.body()).get("errores"))).containsKey("documentos.0.tipo_cambio");

        long id = create("/api/v1/retenciones", retencion("USD", ",\"tipo_cambio\":{\"moneda_ref\":\"USD\",\"moneda_obj\":\"PEN\","
                + "\"factor\":3.75,\"fecha\":\"" + hoy + "\"}"));
        Map<String, Object> d = await("/api/v1/retenciones/" + id, x -> estado(x).equals("aceptado"));
        assertThat(((Number) d.get("imp_retenido")).doubleValue()).isEqualTo(112.5);
        assertThat(((Number) d.get("imp_pagado")).doubleValue()).isEqualTo(3637.5);
        String xml = SUNAT.received.stream().filter(x -> x.fileName().equals(RUC + "-20-R001-2.zip")).findFirst().orElseThrow().xml();
        assertThat(xml).contains("<cbc:TotalInvoiceAmount currencyID=\"USD\">1000.00</cbc:TotalInvoiceAmount>")
                .contains("<cbc:CalculationRate>3.750000</cbc:CalculationRate>");
    }

    String guia(String serie, String extra) {
        return "{\"serie\":\"" + serie + "\",\"fecha_emision\":\"" + hoy + "\",\"destinatario\":{\"tipo_doc\":\"6\",\"num_doc\":\"20666666666\","
                + "\"razon_social\":\"DESTINO SAC\"},\"cod_traslado\":\"01\",\"mod_traslado\":\"02\",\"fecha_traslado\":\"" + hoy + "\","
                + "\"peso_total\":10,\"partida_ubigeo\":\"150101\",\"partida_direccion\":\"AV. A 1\",\"llegada_ubigeo\":\"150203\","
                + "\"llegada_direccion\":\"AV. B 2\",\"vehiculo\":{\"placa\":\"ABC123\"},\"conductor\":{\"tipo_doc\":\"1\",\"num_doc\":\"12345678\","
                + "\"nombres\":\"JOSE D'ANGELO\",\"apellidos\":\"PEREZ\",\"licencia\":\"Q12345678\"},"
                + "\"items\":[{\"descripcion\":\"Caja \\\"grande\\\"\",\"cantidad\":2,\"unidad\":\"NIU\"}]" + extra + "}";
    }

    @Test
    @Order(4)
    void guiaRemitentePorApiGre() throws Exception {
        long id = create("/api/v1/guias-remision", guia("T001", ",\"tercero\":{\"tipo_doc\":\"6\",\"num_doc\":\"20777777777\",\"razon_social\":\"PROVEEDOR SAC\"}"));
        Map<String, Object> d = await("/api/v1/guias-remision/" + id, x -> sunatEstado(x).equals("aceptado"));
        assertThat(d.get("tipo_documento")).isEqualTo("09");
        FakeSunat.GreReceived g = SUNAT.greReceived.get(0);
        assertThat(g.name()).isEqualTo(RUC + "-09-T001-1");
        assertThat(g.signatureValid()).isTrue();
        assertThat(g.hashValid()).isTrue();
        assertThat(g.xml()).contains("<cbc:FirstName>JOSE D'ANGELO</cbc:FirstName>").contains("<cac:SellerSupplierParty>");
        assertThat(SUNAT.greTokenRequests.get(0)).contains("grant_type=password").contains("scope=https://api-cpe.sunat.gob.pe")
                .contains("client_id=cliente-prueba").contains("username=" + RUC);
        assertThat(get("/api/v1/guias-remision/" + id + "/xml", auth).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/guias-remision/" + id + "/cdr", auth).statusCode()).isEqualTo(200);
        HttpResponse<String> st = get("/api/v1/guias-remision/" + id + "/estado", auth);
        assertThat(st.body()).contains("Estado ya resuelto.");
        until(() -> SUNAT.webhooks.stream().anyMatch(w -> "document.status_updated".equals(w.event()) && w.body().contains("\"tipo_documento\":\"09\"")));
    }

    @Test
    @Order(5)
    void tokenRevocadoSeRenuevaYGrtSeEnvia() throws Exception {
        int tokens = SUNAT.greTokenRequests.size();
        SUNAT.greReceived.forEach(g -> SUNAT.revokedTokens.add(g.token()));
        long id = create("/api/v1/guias-remision-transportista", "{\"serie\":\"V001\",\"fecha_emision\":\"" + hoy + "\","
                + "\"remitente\":{\"tipo_doc\":\"6\",\"num_doc\":\"20512345678\",\"razon_social\":\"REMITENTE & CIA SAC\"},"
                + "\"destinatario\":{\"tipo_doc\":\"6\",\"num_doc\":\"20666666666\",\"razon_social\":\"DESTINO SAC\"},"
                + "\"doc_relacionado\":{\"tipo_codigo\":\"01\",\"numero\":\"F001-1\",\"ruc_emisor\":\"20512345678\"},\"fecha_traslado\":\"" + hoy + "\",\"peso_total\":5,"
                + "\"partida_ubigeo\":\"150101\",\"partida_direccion\":\"A\",\"llegada_ubigeo\":\"150101\",\"llegada_direccion\":\"B\","
                + "\"vehiculo\":{\"placa\":\"ABC123\"},\"conductor\":[{\"tipo_doc\":\"1\",\"num_doc\":\"12345678\",\"nombres\":\"JUAN\","
                + "\"apellidos\":\"PEREZ\",\"licencia\":\"Q1\"}],\"items\":[{\"descripcion\":\"x\",\"cantidad\":1}]}");
        Map<String, Object> d = await("/api/v1/guias-remision/" + id, x -> sunatEstado(x).equals("aceptado"));
        assertThat(d.get("tipo_documento")).isEqualTo("31");
        assertThat(SUNAT.greTokenRequests.size()).isGreaterThan(tokens);
        FakeSunat.GreReceived g = SUNAT.greReceived.stream().filter(x -> x.name().endsWith("-31-V001-1")).findFirst().orElseThrow();
        assertThat(g.xml()).doesNotContain("<cbc:HandlingCode").contains("<cac:DespatchParty>")
                .contains("<![CDATA[REMITENTE & CIA SAC]]>").contains("<cbc:LicensePlateID>ABC123</cbc:LicensePlateID>");
    }

    @Test
    @Order(6)
    void guiaRechazadaYCorregida() throws Exception {
        SUNAT.responses.put("T001-2", "422:2554");
        long id = create("/api/v1/guias-remision", guia("T001", ""));
        Map<String, Object> d = await("/api/v1/guias-remision/" + id, x -> sunatEstado(x).equals("rechazado"));
        assertThat(String.valueOf(((Map<?, ?>) d.get("sunat")).get("codigo"))).isEqualTo("2554");
        SUNAT.responses.remove("T001-2");
        HttpResponse<String> upd = send("PUT", "/api/v1/guias-remision/" + id, guia("T001", ",\"llegada_ruc\":\"20666666666\","
                + "\"llegada_cod_local\":\"0001\",\"tipo_documento\":\"09\""), auth);
        assertThat(upd.statusCode()).as(upd.body()).isEqualTo(200);
        await("/api/v1/guias-remision/" + id, x -> sunatEstado(x).equals("aceptado"));
        List<FakeSunat.GreReceived> envios = SUNAT.greReceived.stream().filter(x -> x.name().endsWith("-09-T001-2")).toList();
        assertThat(envios.get(envios.size() - 1).xml()).contains("<cbc:AddressTypeCode listID=\"20666666666\">0001</cbc:AddressTypeCode>");
    }
    @Test
    @Order(7)
    @SuppressWarnings("unchecked")
    void listadoDeGuiasConFiltros() throws Exception {
        // Corrección: API-PRO ignoraba los filtros del listado de guías
        Map<String, Object> aceptadas = datos(get("/api/v1/guias-remision?sunat_status=aceptado", auth));
        List<Map<String, Object>> lista = (List<Map<String, Object>>) aceptadas.get("datos");
        assertThat(lista).isNotEmpty().allSatisfy(g -> assertThat(sunatEstado(g)).isEqualTo("aceptado"));
        for (String tipo : List.of("09", "31")) {
            List<Map<String, Object>> porTipo = (List<Map<String, Object>>) datos(get("/api/v1/guias-remision?tipo_documento=" + tipo, auth)).get("datos");
            assertThat(porTipo).isNotEmpty().allSatisfy(g -> assertThat(g.get("tipo_documento")).isEqualTo(tipo));
        }
        assertThat((List<Object>) datos(get("/api/v1/guias-remision?serie=T001&fecha_desde=" + hoy + "&fecha_hasta=" + hoy, auth)).get("datos"))
                .isNotEmpty();
        assertThat(get("/api/v1/guias-remision?fecha_desde=26-10-2026&fecha_hasta=" + hoy, auth).statusCode()).isEqualTo(422);
        // PUT de una guía que no es de la empresa: 404 antes que la validación del cuerpo
        assertThat(send("PUT", "/api/v1/guias-remision/999999", "{}", auth).statusCode()).isEqualTo(404);
    }
}
