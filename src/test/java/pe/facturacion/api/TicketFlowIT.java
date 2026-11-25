package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
 * Notas, resumen diario, comunicación de baja y consulta de CDR contra un SUNAT simulado:
 * sendBill / sendSummary → ticket → getStatus → estado final de los documentos.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TicketFlowIT extends IntegrationTest {

    static final FakeSunat SUNAT;
    static final String RUC = "20222222222";

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
        r.add("app.sunat.beta.consulta-cdr", () -> SUNAT.url("/billService"));
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
        t.setDireccion("AV. TICKETS 1");
        t.setUbigeo("150101");
        t.setCertificatePath(certificates.store(t, new String(resource("/fixtures/certs/cert-con-clave.pem"), StandardCharsets.US_ASCII)));
        tenants.save(t);
        for (String[] s : new String[][] {{"01", "F001"}, {"03", "B001"}, {"07", "FC01"}, {"08", "FD01"}}) {
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

    @SuppressWarnings("unchecked")
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

    @SuppressWarnings("unchecked")
    static String sunatEstado(Map<String, Object> d) {
        return String.valueOf(((Map<String, Object>) d.get("sunat")).get("estado"));
    }

    String factura(double precio) {
        return "{\"serie\":\"F001\",\"fecha_emision\":\"" + hoy + "\",\"cliente\":{\"tipo_doc\":\"6\",\"num_doc\":\"20601234567\",\"razon_social\":\"CLIENTE SAC\"},"
                + "\"items\":[{\"descripcion\":\"Servicio\",\"unidad\":\"ZZ\",\"cantidad\":1,\"precio_unitario\":" + precio + "}]}";
    }

    String boleta(double precio, boolean soloRegistro) {
        return "{\"serie\":\"B001\",\"fecha_emision\":\"" + hoy + "\",\"solo_registro\":" + soloRegistro
                + ",\"cliente\":{\"tipo_doc\":\"1\",\"num_doc\":\"12345678\",\"razon_social\":\"JUAN PEREZ\"},"
                + "\"items\":[{\"descripcion\":\"Menú\",\"unidad\":\"NIU\",\"cantidad\":1,\"precio_unitario\":" + precio + "}]}";
    }

    long create(String path, String body) throws Exception {
        HttpResponse<String> res = send("POST", path, body, auth);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        return ((Number) datos(res).get("id")).longValue();
    }

    @Test
    @Order(1)
    @SuppressWarnings("unchecked")
    void notasDeCreditoYDebitoAceptadas() throws Exception {
        long f = create("/api/v1/facturas", factura(236));
        await("/api/v1/facturas/" + f, d -> sunatEstado(d).equals("aceptado"));

        long nc = create("/api/v1/notas-credito", "{\"serie\":\"FC01\",\"fecha_emision\":\"" + hoy + "\",\"doc_afectado_tipo\":\"01\","
                + "\"doc_afectado_serie\":\"F001\",\"doc_afectado_correlativo\":\"1\",\"cod_motivo\":\"07\",\"des_motivo\":\"Devolución & ajuste\","
                + "\"cliente\":{\"tipo_doc\":\"6\",\"num_doc\":\"20601234567\",\"razon_social\":\"CLIENTE SAC\"},"
                + "\"items\":[{\"descripcion\":\"Servicio\",\"unidad\":\"ZZ\",\"cantidad\":1,\"precio_unitario\":118}]}");
        Map<String, Object> ok = await("/api/v1/notas-credito/" + nc, d -> sunatEstado(d).equals("aceptado"));
        assertThat(((Map<String, Object>) ok.get("doc_afectado")).get("motivo_codigo")).isEqualTo("07");
        FakeSunat.Received r = SUNAT.received.get(SUNAT.received.size() - 1);
        assertThat(r.fileName()).isEqualTo(RUC + "-07-FC01-1.zip");
        assertThat(r.signatureValid()).isTrue();
        assertThat(r.xml()).contains("<cbc:Description>Devolución &amp; ajuste</cbc:Description>")
                .contains("<cac:LegalMonetaryTotal><cbc:PayableAmount currencyID=\"PEN\">118.00</cbc:PayableAmount>");

        HttpResponse<String> mayor = send("POST", "/api/v1/notas-credito", "{\"serie\":\"FC01\",\"fecha_emision\":\"" + hoy + "\",\"doc_afectado_tipo\":\"01\","
                + "\"doc_afectado_serie\":\"F001\",\"doc_afectado_correlativo\":\"1\",\"cod_motivo\":\"07\",\"des_motivo\":\"x\","
                + "\"cliente\":{\"tipo_doc\":\"6\",\"num_doc\":\"20601234567\",\"razon_social\":\"CLIENTE SAC\"},"
                + "\"items\":[{\"descripcion\":\"x\",\"unidad\":\"ZZ\",\"cantidad\":1,\"precio_unitario\":1000}]}", auth);
        assertThat(mayor.statusCode()).isEqualTo(422);
        assertThat(PhpJson.encodeUnescaped(PhpJson.decode(mayor.body()))).contains("no puede superar el del documento que modifica F001-1 (236)");

        long nd = create("/api/v1/notas-debito", "{\"serie\":\"FD01\",\"fecha_emision\":\"" + hoy + "\",\"cod_motivo\":\"13\","
                + "\"des_motivo\":\"Penalidad\",\"cliente\":{\"tipo_doc\":\"6\",\"num_doc\":\"20601234567\",\"razon_social\":\"CLIENTE SAC\"},"
                + "\"items\":[{\"descripcion\":\"Penalidad\",\"unidad\":\"ZZ\",\"cantidad\":1,\"precio_unitario\":50,\"tip_afe_igv\":\"30\"}]}");
        await("/api/v1/notas-debito/" + nd, d -> sunatEstado(d).equals("aceptado"));
        String ndXml = SUNAT.received.get(SUNAT.received.size() - 1).xml();
        assertThat(ndXml).doesNotContain("<cac:BillingReference>").contains("<cac:RequestedMonetaryTotal>");

        HttpResponse<String> lista = get("/api/v1/notas-credito?sunat_status=aceptado", auth);
        assertThat(PhpJson.encode(datos(lista).get("paginacion"))).contains("\"total\":1");
        assertThat(get("/api/v1/notas-credito?sunat_status=", auth).statusCode()).isEqualTo(200);
    }

    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    void resumenDiarioEnvioYAnulacion() throws Exception {
        long b1 = create("/api/v1/boletas", boleta(20, true));
        long b2 = create("/api/v1/boletas", boleta(35, true));
        HttpResponse<String> rc = send("POST", "/api/v1/resumenes", "{\"fecha_resumen\":\"" + hoy + "\"}", auth);
        assertThat(rc.statusCode()).as(rc.body()).isEqualTo(201);
        long rcId = ((Number) datos(rc).get("id_resumen")).longValue();
        assertThat(datos(rc).get("identifier")).isEqualTo("RC-" + hoy.replace("-", "") + "-001");

        Map<String, Object> estado = await("/api/v1/resumenes/" + rcId + "/estado", d -> "aceptado".equals(d.get("estado_sunat")));
        assertThat(estado.get("ticket")).isNotNull();
        for (Object o : (List<Object>) estado.get("documentos")) {
            assertThat(((Map<String, Object>) o).get("estado_sunat")).isEqualTo("aceptado");
        }
        assertThat(get("/api/v1/resumenes/" + rcId + "/xml", auth).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/resumenes/" + rcId + "/cdr", auth).statusCode()).isEqualTo(200);
        FakeSunat.Received sent = SUNAT.received.stream().filter(x -> x.fileName().contains("-RC-")).findFirst().orElseThrow();
        assertThat(sent.signatureValid()).isTrue();
        assertThat(sent.xml()).contains("<cbc:ID>B001-1</cbc:ID>").contains("<cbc:ID>B001-2</cbc:ID>");

        // Anulación de una boleta aceptada: en proceso hasta que el ticket responde
        HttpResponse<String> an = send("POST", "/api/v1/resumenes",
                "{\"fecha_resumen\":\"" + hoy + "\",\"anular\":[{\"id\":" + b1 + ",\"motivo\":\"Error\"}]}", auth);
        assertThat(an.statusCode()).as(an.body()).isEqualTo(201);
        long anId = ((Number) datos(an).get("id_resumen")).longValue();
        assertThat(datos(an).get("identifier")).isEqualTo("RC-" + hoy.replace("-", "") + "-002");
        await("/api/v1/resumenes/" + anId + "/estado", d -> "aceptado".equals(d.get("estado_sunat")));
        await("/api/v1/boletas/" + b1, d -> sunatEstado(d).equals("anulado"));
        assertThat(sunatEstado(datos(get("/api/v1/boletas/" + b2, auth)))).isEqualTo("aceptado");

        HttpResponse<String> dup = send("POST", "/api/v1/resumenes", "{\"fecha_resumen\":\"" + hoy + "\"}", auth);
        assertThat(dup.statusCode()).isEqualTo(422);
        HttpResponse<String> fuera = send("POST", "/api/v1/resumenes", "{\"fecha_resumen\":\"" + LocalDate.parse(hoy).minusDays(7) + "\"}", auth);
        // El último día del plazo de 7 días se acepta (API-PRO lo rechazaba por zona horaria)
        assertThat(fuera.body()).doesNotContain("Fecha límite");
    }

    @Test
    @Order(3)
    void comunicacionDeBajaYRechazoPorTicket() throws Exception {
        long f2 = create("/api/v1/facturas", factura(118));
        await("/api/v1/facturas/" + f2, d -> sunatEstado(d).equals("aceptado"));
        // Corrección: fecha_generacion (ReferenceDate) distinta de la emisión → 422 sin crear la baja
        HttpResponse<String> otraFecha = send("POST", "/api/v1/anulaciones", "{\"fecha_generacion\":\"" + LocalDate.parse(hoy).minusDays(1)
                + "\",\"detalles\":[{\"tipo_documento\":\"01\",\"serie\":\"F001\",\"correlativo\":\"2\",\"motivo\":\"Error\"}]}", auth);
        assertThat(otraFecha.statusCode()).isEqualTo(422);
        assertThat(otraFecha.body()).contains("fue emitido el " + hoy);
        assertThat(sunatEstado(datos(get("/api/v1/facturas/" + f2, auth)))).isEqualTo("aceptado");
        SUNAT.pendingPolls = 1;
        HttpResponse<String> ra = send("POST", "/api/v1/anulaciones", "{\"fecha_generacion\":\"" + hoy + "\",\"detalles\":"
                + "[{\"tipo_documento\":\"01\",\"serie\":\"F001\",\"correlativo\":\"2\",\"motivo\":\"Error en el cliente\"}]}", auth);
        assertThat(ra.statusCode()).as(ra.body()).isEqualTo(201);
        long raId = ((Number) datos(ra).get("id_anulacion")).longValue();
        assertThat(datos(ra).get("fecha_comunicacion")).isEqualTo(hoy);
        assertThat(sunatEstado(datos(get("/api/v1/facturas/" + f2, auth)))).isIn("anulacion_en_proceso");
        // La consulta en vivo trata el 98 como "en proceso", no como rechazo
        Map<String, Object> fin = await("/api/v1/anulaciones/" + raId + "/estado", d -> "aceptado".equals(d.get("estado_sunat")));
        assertThat(fin.get("ticket")).isNotNull();
        await("/api/v1/facturas/" + f2, d -> sunatEstado(d).equals("anulado"));
        assertThat(send("POST", "/api/v1/facturas/" + f2 + "/enviar", null, auth).statusCode()).isEqualTo(422);

        // Baja rechazada por SUNAT: la factura vuelve a "aceptado"
        SUNAT.pendingPolls = 0;
        long f3 = create("/api/v1/facturas", factura(59));
        await("/api/v1/facturas/" + f3, d -> sunatEstado(d).equals("aceptado"));
        SUNAT.responses.put("RA-" + hoy.replace("-", "") + "-002", "2375");
        HttpResponse<String> ra2 = send("POST", "/api/v1/anulaciones", "{\"fecha_generacion\":\"" + hoy + "\",\"detalles\":"
                + "[{\"tipo_documento\":\"01\",\"serie\":\"F001\",\"correlativo\":\"3\",\"motivo\":\"Otra\"}]}", auth);
        assertThat(ra2.statusCode()).as(ra2.body()).isEqualTo(201);
        long ra2Id = ((Number) datos(ra2).get("id_anulacion")).longValue();
        Map<String, Object> rej = await("/api/v1/anulaciones/" + ra2Id, d -> "rechazado".equals(d.get("estado_sunat")));
        assertThat(rej.get("codigo_sunat")).isEqualTo("2375");
        await("/api/v1/facturas/" + f3, d -> sunatEstado(d).equals("aceptado"));
    }

    @Test
    @Order(4)
    @SuppressWarnings("unchecked")
    void consultarCdr() throws Exception {
        HttpResponse<String> res = send("POST", "/api/v1/consultar-cdr", "{\"tipo_documento\":\"01\",\"serie\":\"F001\",\"correlativo\":1}", auth);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        Map<String, Object> d = datos(res);
        assertThat(d.get("success")).as(res.body()).isEqualTo(true);
        assertThat(d.get("code")).isEqualTo("0");
        assertThat(d.get("accepted")).isEqualTo(true);
        assertThat((String) d.get("cdr_zip")).isNotBlank();

        HttpResponse<String> none = send("POST", "/api/v1/consultar-cdr", "{\"tipo_documento\":\"01\",\"serie\":\"F001\",\"correlativo\":99}", auth);
        Map<String, Object> n = datos(none);
        assertThat(n.get("success")).isEqualTo(false);
        assertThat(n.get("error_code")).isEqualTo("0127");
        assertThat(send("POST", "/api/v1/consultar-cdr", "{\"tipo_documento\":\"09\"}", auth).statusCode()).isEqualTo(422);
    }

    /** Correcciones de la corrida de la colección: PUT respeta el modo de envío, pagos, NC total y bajas. */
    @Test
    @Order(5)
    void edicionPagosYNotaDeCreditoTotal() throws Exception {
        // PUT de un borrador manual: sigue pendiente y no se envía (API-PRO lo enviaba)
        int recibidos = SUNAT.received.size();
        long borrador = create("/api/v1/facturas", factura(100).replaceFirst("\\{", "{\"enviar_automatico\":false,"));
        HttpResponse<String> edit = send("PUT", "/api/v1/facturas/" + borrador,
                "{\"items\":[{\"descripcion\":\"Servicio\",\"unidad\":\"ZZ\",\"cantidad\":2,\"precio_unitario\":100}]}", auth);
        assertThat(edit.statusCode()).as(edit.body()).isEqualTo(200);
        assertThat(edit.body()).contains("en estado pendiente");
        HttpResponse<String> bol = send("POST", "/api/v1/boletas", boleta(20, true), auth);
        long boletaId = ((Number) datos(bol).get("id")).longValue();
        HttpResponse<String> editBol = send("PUT", "/api/v1/boletas/" + boletaId, "{\"observacion\":\"x\"}", auth);
        assertThat(editBol.body()).contains("resumen diario");
        Thread.sleep(1500);
        assertThat(sunatEstado(datos(get("/api/v1/facturas/" + borrador, auth)))).isEqualTo("pendiente");
        assertThat(sunatEstado(datos(get("/api/v1/boletas/" + boletaId, auth)))).isEqualTo("pendiente");
        assertThat(SUNAT.received).hasSize(recibidos);
        // enviar_automatico en el PUT decide esa edición
        assertThat(send("PUT", "/api/v1/facturas/" + borrador, "{\"enviar_automatico\":true}", auth).body()).contains("reenviada a SUNAT");
        await("/api/v1/facturas/" + borrador, d -> sunatEstado(d).equals("aceptado"));
        send("DELETE", "/api/v1/boletas/" + boletaId, null, auth);

        // Pagos: no se cobra más que el total
        long f = create("/api/v1/facturas", factura(236));
        Map<String, Object> fd = await("/api/v1/facturas/" + f, d -> sunatEstado(d).equals("aceptado"));
        String corr = String.valueOf(fd.get("correlativo"));
        HttpResponse<String> exceso = send("POST", "/api/v1/facturas/" + f + "/pagos", "{\"metodo\":\"efectivo\",\"monto\":336}", auth);
        assertThat(exceso.statusCode()).isEqualTo(422);
        assertThat(exceso.body()).contains("supera el saldo pendiente (236.00)");
        assertThat(send("POST", "/api/v1/facturas/" + f + "/pagos", "{\"metodo\":\"efectivo\",\"monto\":100}", auth).body()).contains("parcial");
        assertThat(send("POST", "/api/v1/facturas/" + f + "/pagos", "{\"metodo\":\"yape\",\"monto\":136.01}", auth).statusCode()).isEqualTo(422);
        assertThat(send("POST", "/api/v1/facturas/" + f + "/pagos", "{\"metodo\":\"yape\",\"monto\":136}", auth).body()).contains("\"pagado\"");

        // NC de anulación de la operación (01) aceptada: la factura ya no es cuenta por cobrar
        long nc = create("/api/v1/notas-credito", "{\"serie\":\"FC01\",\"fecha_emision\":\"" + hoy + "\",\"doc_afectado_tipo\":\"01\","
                + "\"doc_afectado_serie\":\"F001\",\"doc_afectado_correlativo\":\"" + corr + "\",\"cod_motivo\":\"01\",\"des_motivo\":\"Anulación\","
                + "\"cliente\":{\"tipo_doc\":\"6\",\"num_doc\":\"20601234567\",\"razon_social\":\"CLIENTE SAC\"},"
                + "\"items\":[{\"descripcion\":\"Servicio\",\"unidad\":\"ZZ\",\"cantidad\":1,\"precio_unitario\":236}]}");
        Map<String, Object> ncd = await("/api/v1/notas-credito/" + nc, d -> sunatEstado(d).equals("aceptado"));
        Map<String, Object> anulada = datos(get("/api/v1/facturas/" + f, auth));
        assertThat(anulada.get("estado_pago")).isEqualTo("anulado");
        assertThat(sunatEstado(anulada)).isEqualTo("aceptado");
        assertThat(send("POST", "/api/v1/facturas/" + f + "/pagos", "{\"metodo\":\"efectivo\",\"monto\":1}", auth).statusCode()).isEqualTo(422);
        assertThat(get("/api/v1/reportes/cobranzas?fecha_desde=" + hoy + "&fecha_hasta=" + hoy, auth).body())
                .doesNotContain("\"numero_completo\":\"F001-" + corr + "\"");

        // Baja de la NC: la factura vuelve a su estado de pago y, sin NC vigente, ya se puede anular
        HttpResponse<String> raNc = send("POST", "/api/v1/anulaciones", "{\"fecha_generacion\":\"" + hoy + "\",\"detalles\":"
                + "[{\"tipo_documento\":\"07\",\"serie\":\"FC01\",\"correlativo\":\"" + ncd.get("correlativo") + "\",\"motivo\":\"NC errada\"}]}", auth);
        assertThat(raNc.statusCode()).as(raNc.body()).isEqualTo(201);
        await("/api/v1/notas-credito/" + nc, d -> sunatEstado(d).equals("anulado"));
        assertThat(datos(get("/api/v1/facturas/" + f, auth)).get("estado_pago")).isEqualTo("pagado");
        HttpResponse<String> raF = send("POST", "/api/v1/anulaciones", "{\"fecha_generacion\":\"" + hoy + "\",\"detalles\":"
                + "[{\"tipo_documento\":\"01\",\"serie\":\"F001\",\"correlativo\":\"" + corr + "\",\"motivo\":\"Error\"}]}", auth);
        assertThat(raF.statusCode()).as(raF.body()).isEqualTo(201);
    }

    /** Detracción, ICBPER vigente y boletas de resumen fuera de plazo. */
    @Test
    @Order(6)
    void detraccionIcbperYBoletaFueraDePlazo() throws Exception {
        String det = "\"detraccion\":{\"cod_bien\":\"037\",\"porcentaje\":12,\"cta_banco\":\"00-000-123456\",\"monto\":%s},";
        HttpResponse<String> mal = send("POST", "/api/v1/facturas", factura(1180).replaceFirst("\\{", "{" + String.format(det, "100")), auth);
        assertThat(mal.statusCode()).isEqualTo(422);
        assertThat(mal.body()).contains("no corresponde al 12% del importe total (141.60)");
        long f = create("/api/v1/facturas", factura(1180).replaceFirst("\\{", "{" + String.format(det, "141.60")));
        // El tipo de operación guardado es el del XML (1001), no 0101
        assertThat(jdbc.queryForObject("SELECT tipo_operacion FROM invoices WHERE id = ?", String.class, f)).isEqualTo("1001");
        await("/api/v1/facturas/" + f, d -> sunatEstado(d).equals("aceptado"));
        assertThat(SUNAT.received.get(SUNAT.received.size() - 1).xml()).contains("listID=\"1001\"");

        String bolsa = "{\"serie\":\"F001\",\"fecha_emision\":\"" + hoy + "\",\"cliente\":{\"tipo_doc\":\"6\",\"num_doc\":\"20601234567\","
                + "\"razon_social\":\"CLIENTE SAC\"},\"items\":[{\"descripcion\":\"Bolsa\",\"unidad\":\"NIU\",\"cantidad\":3,\"precio_unitario\":0.10,"
                + "\"factor_icbper\":%s}]}";
        HttpResponse<String> vieja = send("POST", "/api/v1/facturas", String.format(bolsa, "0.20"), auth);
        assertThat(vieja.statusCode()).isEqualTo(422);
        assertThat(vieja.body()).contains("items.0.factor_icbper").contains("0.50 por bolsa");
        HttpResponse<String> vigente = send("POST", "/api/v1/facturas", String.format(bolsa, "0.50"), auth);
        assertThat(vigente.statusCode()).as(vigente.body()).isEqualTo(201);
        assertThat(PhpJson.encode(datos(vigente).get("totales"))).contains("\"icbper\":1.5");

        // La NC va en la moneda del comprobante que modifica (la factura es en PEN)
        Object corrF = datos(get("/api/v1/facturas/" + f, auth)).get("correlativo");
        HttpResponse<String> ncUsd = send("POST", "/api/v1/notas-credito", "{\"serie\":\"FC01\",\"fecha_emision\":\"" + hoy + "\",\"tipo_moneda\":\"USD\","
                + "\"doc_afectado_tipo\":\"01\",\"doc_afectado_serie\":\"F001\",\"doc_afectado_correlativo\":\"" + corrF + "\",\"cod_motivo\":\"07\","
                + "\"des_motivo\":\"Devolución\",\"cliente\":{\"tipo_doc\":\"6\",\"num_doc\":\"20601234567\",\"razon_social\":\"CLIENTE SAC\"},"
                + "\"items\":[{\"descripcion\":\"Servicio\",\"unidad\":\"ZZ\",\"cantidad\":1,\"precio_unitario\":10}]}", auth);
        assertThat(ncUsd.statusCode()).as(ncUsd.body()).isEqualTo(422);
        assertThat(ncUsd.body()).contains("\"tipo_moneda\"").contains("moneda del comprobante que modifica (PEN)");

        // total_impuestos del ítem: debe cuadrar con IGV + ISC + ICBPER
        String conImpuestos = factura(118).replace("\"precio_unitario\":118.0}", "\"precio_unitario\":118.0,\"total_impuestos\":%s}");
        HttpResponse<String> descuadre = send("POST", "/api/v1/facturas", String.format(conImpuestos, "30"), auth);
        assertThat(descuadre.statusCode()).as(descuadre.body()).isEqualTo(422);
        assertThat(descuadre.body()).contains("items.0.total_impuestos").contains("Debe ser 18.00");
        assertThat(send("POST", "/api/v1/facturas", String.format(conImpuestos, "18"), auth).statusCode()).isEqualTo(201);

        HttpResponse<String> fuera = send("POST", "/api/v1/boletas", boleta(10, true).replace(hoy, LocalDate.parse(hoy).minusDays(8).toString()), auth);
        assertThat(fuera.statusCode()).isEqualTo(422);
        assertThat(fuera.body()).contains("dentro de los 7 d");
    }
}
