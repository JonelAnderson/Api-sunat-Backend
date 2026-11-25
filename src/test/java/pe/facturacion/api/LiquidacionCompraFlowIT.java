package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Liquidación de compra (tipo 04) de punta a punta contra un SUNAT simulado: emisión por
 * {@code sendBill}, XML {@code SelfBilledInvoice}, tasas de retención de renta, validaciones,
 * pagos, reversión (RR por "otros CPE") y reporte de retenciones.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LiquidacionCompraFlowIT extends IntegrationTest {

    static final FakeSunat SUNAT;
    static final String RUC = "20444444440";

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
        SUNAT.pendingPolls = 0;
        auth = new HashMap<>(newTenant(tenants, RUC));
        auth.put("Accept", "application/json");
        Tenant t = tenants.findByRuc(RUC).orElseThrow();
        t.setDireccion("AV. COMPRAS 1");
        t.setUbigeo("150101");
        t.setCertificatePath(certificates.store(t, new String(resource("/fixtures/certs/cert-con-clave.pem"), StandardCharsets.US_ASCII)));
        tenants.save(t);
        jdbc.update("INSERT INTO series (tenant_id, tipo_documento, serie, correlativo, is_active, created_at, updated_at) "
                + "VALUES (?, '04', 'L001', 0, true, now(), now())", t.getId());
    }

    @AfterAll
    void tearDown() {
        SUNAT.close();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> datos(HttpResponse<String> res) {
        return (Map<String, Object>) PhpJson.decodeObject(res.body()).get("datos");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> m(Object o) {
        return (Map<String, Object>) o;
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

    static String estado(Map<String, Object> d) {
        return String.valueOf(m(d.get("sunat")).get("estado"));
    }

    static double num(Object v) {
        return ((Number) v).doubleValue();
    }

    String body(String extra, String items) {
        return "{\"serie\":\"L001\",\"fecha_emision\":\"" + hoy + "\","
                + "\"vendedor\":{\"tipo_doc\":\"1\",\"num_doc\":\"45678912\",\"nombre\":\"QUISPE MAMANI JUAN\","
                + "\"direccion\":\"CASERIO SAN JUAN S/N\",\"ubigeo\":\"080101\",\"tipo_domicilio\":\"02\"},"
                + "\"lugar\":{\"direccion\":\"CHACRA LOS ANDES KM 5\",\"ubigeo\":\"080101\",\"tipo\":\"02\"},"
                + "\"items\":" + items + extra + "}";
    }

    static String item(double cantidad, double valor) {
        return "[{\"codigo\":\"CAFE01\",\"cod_producto_sunat\":\"50131700\",\"descripcion\":\"Café pergamino\",\"unidad\":\"KGM\","
                + "\"cantidad\":" + cantidad + ",\"valor_unitario\":" + valor + "}]";
    }

    long create(String json) throws Exception {
        HttpResponse<String> res = send("POST", "/api/v1/liquidaciones-compra", json, auth);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        return ((Number) datos(res).get("id")).longValue();
    }

    @Test
    @Order(1)
    void emitidaYAceptadaConRetencionDeRenta() throws Exception {
        long id = create(body(",\"placa_vehiculo\":\"ABC123\"", item(100, 10)));
        Map<String, Object> d = await("/api/v1/liquidaciones-compra/" + id, x -> estado(x).equals("aceptado"));
        Map<String, Object> t = m(d.get("totales"));
        assertThat(num(t.get("valor_venta"))).isEqualTo(1000.0);
        assertThat(num(t.get("igv"))).isEqualTo(180.0);
        assertThat(num(t.get("sub_total"))).isEqualTo(1180.0);
        assertThat(num(t.get("porcentaje_renta"))).isEqualTo(1.5);
        assertThat(num(t.get("retencion_renta"))).isEqualTo(15.0);
        assertThat(num(t.get("neto_a_pagar"))).isEqualTo(985.0);
        assertThat(m(d.get("sunat")).get("fecha_cdr")).isNotNull();
        assertThat(m(d.get("reversion")).get("disponible")).isEqualTo(true);

        FakeSunat.Received r = SUNAT.received.stream().filter(x -> x.fileName().equals(RUC + "-04-L001-1.zip")).findFirst().orElseThrow();
        assertThat(r.signatureValid()).isTrue();
        String xml = r.xml();
        // Contra el XSD OASIS UBL-SelfBilledInvoice-2.1 (orden de elementos y tipos)
        pe.facturacion.api.sunat.xml.UblXsdTest.validate(xml);
        assertThat(xml).contains("<SelfBilledInvoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:SelfBilledInvoice-2\"")
                .contains("listID=\"0501\"").contains(">04</cbc:InvoiceTypeCode>")
                .contains("<cbc:LocationTypeCode>02</cbc:LocationTypeCode>")
                .contains("<cbc:AddressTypeCode>02</cbc:AddressTypeCode>")
                .contains("<cbc:LicensePlateID>ABC123</cbc:LicensePlateID>")
                .contains(">3000</cbc:ID><cbc:Name>IR</cbc:Name><cbc:TaxTypeCode>TOX</cbc:TaxTypeCode>")
                .contains("<cac:TaxTotal><cbc:TaxAmount currencyID=\"PEN\">180.00</cbc:TaxAmount>")
                .contains("<cbc:PayableAmount currencyID=\"PEN\">985.00</cbc:PayableAmount>");
        // Orden del esquema SelfBilledInvoice: comprador (Customer) antes que el vendedor (Supplier)
        assertThat(xml.indexOf("<cac:AccountingCustomerParty>")).isLessThan(xml.indexOf("<cac:AccountingSupplierParty>"));
        assertThat(xml.indexOf("<cac:AccountingSupplierParty>")).isLessThan(xml.indexOf("<cac:DeliveryTerms>"));
        assertThat(get("/api/v1/liquidaciones-compra/" + id + "/xml", auth).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/liquidaciones-compra/" + id + "/cdr", auth).statusCode()).isEqualTo(200);

        // Representación impresa: guardada al aceptarse (archivos.pdf) y descargable en los 4 formatos
        await("/api/v1/liquidaciones-compra/" + id, x -> m(x.get("archivos")).get("pdf") != null);
        for (String f : List.of("a4", "a5", "ticket-80", "ticket-58")) {
            var pdf = getBytes("/api/v1/liquidaciones-compra/" + id + "/pdf?formato=" + f, auth);
            assertThat(pdf.statusCode()).as(f).isEqualTo(200);
            String text = pdfText(pdf.body()).replaceAll("\\s+", " ");
            assertThat(text).as(f).contains("LIQUIDACIÓN DE COMPRA").contains("QUISPE MAMANI JUAN").contains("985.00")
                    .contains("CHACRA LOS ANDES KM 5");
            assertThat(Fase6OracleIT.qr(pdf.body())).as(f).isEqualTo(RUC + "|04|L001|1|180.00|985.00|" + hoy + "|1|45678912");
        }
    }

    @Test
    @Order(2)
    void tasasSinRetencionYOro() throws Exception {
        long menor = create(body("", item(70, 10)));
        Map<String, Object> d1 = datos(get("/api/v1/liquidaciones-compra/" + menor, auth));
        assertThat(num(m(d1.get("totales")).get("retencion_renta"))).isEqualTo(0.0);

        long agro = create(body(",\"productos_agropecuarios\":true", item(100, 10)));
        assertThat(num(m(datos(get("/api/v1/liquidaciones-compra/" + agro, auth)).get("totales")).get("retencion_renta"))).isEqualTo(0.0);

        HttpResponse<String> sinProps = send("POST", "/api/v1/liquidaciones-compra", body(",\"tipo_operacion\":\"0503\"", item(1, 2000)), auth);
        assertThat(sinProps.statusCode()).isEqualTo(422);
        assertThat(sinProps.body()).contains("items.0.oro.codigo_concesion");

        long oro = create(body(",\"tipo_operacion\":\"0503\"", "[{\"cod_producto_sunat\":\"11101602\",\"descripcion\":\"Oro en bruto\","
                + "\"unidad\":\"GRM\",\"cantidad\":10,\"valor_unitario\":200,\"tip_afe_igv\":\"10\",\"oro\":{\"codigo_concesion\":\"010012345\","
                + "\"ley_mineral\":85.5,\"naturaleza_mineral\":\"1\",\"nombre_derecho_minero\":\"MINA ESPERANZA\"}}]"));
        Map<String, Object> d = await("/api/v1/liquidaciones-compra/" + oro, x -> estado(x).equals("aceptado"));
        assertThat(num(m(d.get("totales")).get("porcentaje_renta"))).isEqualTo(4.0);
        assertThat(num(m(d.get("totales")).get("retencion_renta"))).isEqualTo(80.0);
        String xml = SUNAT.received.stream().filter(x -> x.xml() != null && x.xml().contains("listID=\"0503\"")).findFirst().orElseThrow().xml();
        pe.facturacion.api.sunat.xml.UblXsdTest.validate(xml);
        assertThat(xml).contains(">6000</cbc:NameCode>").contains("<cbc:Value>MINA ESPERANZA</cbc:Value>");
    }

    @Test
    @Order(3)
    @SuppressWarnings("unchecked")
    void validaciones() throws Exception {
        String malo = "{\"serie\":\"F001\",\"fecha_emision\":\"2020-01-01\",\"tipo_operacion\":\"0502\","
                + "\"vendedor\":{\"tipo_doc\":\"6\",\"num_doc\":\"20100000001\",\"nombre\":\"X\",\"direccion\":\"A\",\"ubigeo\":\"1\",\"tipo_domicilio\":\"09\"},"
                + "\"items\":[{\"descripcion\":\"x\",\"cantidad\":1,\"valor_unitario\":1}]}";
        HttpResponse<String> res = send("POST", "/api/v1/liquidaciones-compra", malo, auth);
        assertThat(res.statusCode()).isEqualTo(422);
        Map<String, Object> errores = (Map<String, Object>) PhpJson.decodeObject(res.body()).get("errores");
        assertThat(errores).containsKeys("serie", "fecha_emision", "tipo_operacion", "vendedor.tipo_doc", "vendedor.ubigeo",
                "vendedor.tipo_domicilio", "lugar", "items.0.cod_producto_sunat");

        HttpResponse<String> dni = send("POST", "/api/v1/liquidaciones-compra",
                body("", item(1, 10)).replace("45678912", "4567891"), auth);
        assertThat(dni.statusCode()).isEqualTo(422);
        assertThat(dni.body()).contains("8 d\\u00edgitos");

        // 75 UIT de 2026 = S/ 412 500: el acumulado del vendedor no puede superarlo
        HttpResponse<String> limite = send("POST", "/api/v1/liquidaciones-compra", body("", item(1, 500000)), auth);
        assertThat(limite.statusCode()).isEqualTo(422);
        assertThat(limite.body()).contains("75 UIT");
    }

    @Test
    @Order(4)
    void pagosImpidenReversionYReversionAceptada() throws Exception {
        long id = create(body("", item(100, 10)));
        await("/api/v1/liquidaciones-compra/" + id, x -> estado(x).equals("aceptado"));

        HttpResponse<String> pago = send("POST", "/api/v1/liquidaciones-compra/" + id + "/pagos",
                "{\"metodo\":\"efectivo\",\"monto\":100}", auth);
        assertThat(pago.statusCode()).as(pago.body()).isEqualTo(200);
        HttpResponse<String> conPago = send("POST", "/api/v1/liquidaciones-compra/" + id + "/reversion",
                "{\"causal\":\"producto\",\"motivo\":\"Cantidad errada\"}", auth);
        assertThat(conPago.statusCode()).isEqualTo(422);
        assertThat(conPago.body()).contains("2471");
        long pagoId = ((Number) m(((List<?>) PhpJson.decodeObject(get("/api/v1/liquidaciones-compra/" + id + "/pagos", auth).body())
                .get("datos")).get(0)).get("id")).longValue();
        assertThat(send("DELETE", "/api/v1/liquidaciones-compra/" + id + "/pagos/" + pagoId, null, auth).statusCode()).isEqualTo(200);

        HttpResponse<String> rev = send("POST", "/api/v1/liquidaciones-compra/" + id + "/reversion",
                "{\"causal\":\"vendedor_nombre\",\"motivo\":\"Nombre del vendedor errado\"}", auth);
        assertThat(rev.statusCode()).as(rev.body()).isEqualTo(201);
        Map<String, Object> rd = datos(rev);
        await("/api/v1/anulaciones/" + rd.get("voided_id") + "/estado", x -> "aceptado".equals(x.get("estado_sunat")));
        Map<String, Object> d = datos(get("/api/v1/liquidaciones-compra/" + id, auth));
        assertThat(estado(d)).isEqualTo("revertido");
        String rr = SUNAT.received.stream().filter(x -> x.fileName().startsWith(RUC + "-RR-")).findFirst().orElseThrow().xml();
        assertThat(rr).contains("<cbc:DocumentTypeCode>04</cbc:DocumentTypeCode>").contains("<sac:DocumentSerialID>L001</sac:DocumentSerialID>");
        assertThat(SUNAT.otrosCpe).anyMatch(s -> s.startsWith(RUC + "-RR-"));

        HttpResponse<String> otra = send("POST", "/api/v1/liquidaciones-compra/" + id + "/reversion",
                "{\"causal\":\"producto\",\"motivo\":\"Otra vez\"}", auth);
        assertThat(otra.statusCode()).isEqualTo(422);
    }

    @Test
    @Order(5)
    @SuppressWarnings("unchecked")
    void reporteDeRetenciones() throws Exception {
        HttpResponse<String> res = get("/api/v1/liquidaciones-compra/reporte-retenciones?periodo=" + hoy.substring(0, 7), auth);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        Map<String, Object> d = datos(res);
        List<Object> filas = (List<Object>) d.get("liquidaciones");
        // Las aceptadas del mes (la revertida no cuenta): 1000 (1.5 %), 700 (0 %), agro (0 %) y oro (4 %)
        assertThat(filas).hasSize(4);
        Map<String, Object> tot = m(d.get("totales"));
        assertThat(num(tot.get("renta_retenida"))).isEqualTo(95.0);
        assertThat(num(tot.get("igv_retenido"))).isEqualTo(180 + 126 + 180 + 360.0);
        assertThat(get("/api/v1/liquidaciones-compra/reporte-retenciones?periodo=2026-13", auth).statusCode()).isEqualTo(422);
    }
}
