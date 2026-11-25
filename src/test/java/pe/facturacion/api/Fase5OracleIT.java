package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import pe.facturacion.api.modules.certificate.CertificateStore;
import pe.facturacion.api.modules.document.DocumentArrayMapper;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.document.SaleDocument;
import pe.facturacion.api.modules.document.SaleDocumentRepository;
import pe.facturacion.api.modules.guide.GuideJobs;
import pe.facturacion.api.modules.guide.GuideRepository;
import pe.facturacion.api.modules.retper.RetPerKind;
import pe.facturacion.api.modules.retper.RetPerRepository;
import pe.facturacion.api.modules.retper.SendRetPerJob;
import pe.facturacion.api.modules.tax.TaxRateService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.modules.ticket.TicketRepository;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.sunat.SunatSender;
import pe.facturacion.api.sunat.builder.DespatchModelBuilder;
import pe.facturacion.api.sunat.builder.InvoiceModelBuilder;
import pe.facturacion.api.sunat.builder.RetPerModelBuilder;
import pe.facturacion.api.sunat.builder.TicketModelBuilder;
import pe.facturacion.api.sunat.xml.DespatchXmlBuilder;
import pe.facturacion.api.sunat.xml.RetPerXmlBuilder;
import pe.facturacion.api.sunat.xml.TicketXmlBuilder;
import pe.facturacion.api.support.IntegrationTest;

/**
 * Fase 5 contra el oráculo de API-PRO ({@code fixtures/oracle/fase5.json}): retenciones,
 * percepciones, facturas con percepción, reversiones y guías de remisión. Mismo HTTP, mismo JSON
 * (salvo las correcciones documentadas en DIFERENCIAS) y mismo XML firmado.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {"app.queue.worker-enabled=false", "app.security.internal-token=token-interno-pruebas", "app.paridad-api-pro=true"})
class Fase5OracleIT extends IntegrationTest {

    /**
     * Correcciones sobre la respuesta de API-PRO (regex → reemplazo en el cuerpo esperado):
     * mensajes con la ruta en inglés, la URL de consulta de la reversión que no existe y la
     * indicación de ejecutar el worker de Laravel.
     */
    static final List<String[]> CORRECCIONES = List.of(
            new String[] {"Use POST \\\\/retentions\\\\/", "Use POST \\\\/retenciones\\\\/"},
            new String[] {"Use POST \\\\/perceptions\\\\/", "Use POST \\\\/percepciones\\\\/"},
            new String[] {"\\\\/api\\\\/v1\\\\/voided\\\\/(\\d+)\\\\/status", "\\\\/api\\\\/v1\\\\/anulaciones\\\\/$1\\\\/estado"},
            new String[] {"ejecut\\\\u00e1ndose: php artisan queue:work --queue=default", "ejecut\\\\u00e1ndose (cola sunat)."});

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TenantRepository tenants;
    @Autowired
    CertificateStore certificates;
    @Autowired
    SaleDocumentRepository documents;
    @Autowired
    RetPerRepository retper;
    @Autowired
    TicketRepository tickets;
    @Autowired
    GuideRepository guides;
    @Autowired
    SunatSender sender;
    @Autowired
    TaxRateService taxRates;

    Map<String, Object> scenario;
    List<Map<String, Object>> expected;

    @BeforeAll
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        scenario = PhpJson.decodeObject(new String(resource("/fixtures/oracle/scenario_fase5.json"), StandardCharsets.UTF_8));
        expected = (List<Map<String, Object>>) PhpJson.decode(new String(resource("/fixtures/oracle/fase5.json"), StandardCharsets.UTF_8));
        Clock.useFixed(Instant.parse(String.valueOf(scenario.get("now"))));
        jdbc.execute("TRUNCATE invoice_items, boleta_items, credit_note_items, debit_note_items, payments, invoices, boletas, "
                + "credit_notes, debit_notes, summaries, voided_documents, retention_items, retentions, perception_items, perceptions, "
                + "dispatch_guides RESTART IDENTITY");
        Tenant demo = tenants.findByRuc("20000000001").orElseThrow();
        jdbc.update("UPDATE series SET correlativo = 0 WHERE tenant_id = ?", demo.getId());
        demo.setCertificatePath(certificates.store(demo, new String(resource("/fixtures/certs/cert-con-clave.pem"), StandardCharsets.US_ASCII)));
        tenants.save(demo);
    }

    @AfterAll
    void tearDown() {
        Clock.useSystem();
    }

    @Test
    @SuppressWarnings("unchecked")
    void mismasRespuestasYXmlQueApiPro() throws Exception {
        Map<String, String> headers = new HashMap<>(demoAuth());
        headers.put("X-Internal-Token", "token-interno-pruebas");
        headers.put("Accept", "application/json");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) scenario.get("steps");
        List<String> fallas = new ArrayList<>();
        int xmlComparados = 0;
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            Map<String, Object> exp = expected.get(i);
            String name = i + " " + step.get("name");
            if (step.get("sql") != null) {
                for (String q : String.valueOf(step.get("sql")).split(";")) {
                    if (!q.isBlank()) {
                        jdbc.update(q);
                    }
                }
                continue;
            }
            String url = String.valueOf(step.get("url"));
            HttpResponse<String> res = send(String.valueOf(step.get("method")), url,
                    step.get("body") == null ? null : PhpJson.encode(step.get("body")), headers);
            int want = ((Number) exp.get("status")).intValue();
            if (res.statusCode() != want) {
                fallas.add(name + ": HTTP " + res.statusCode() + " (esperado " + want + ") " + res.body());
                continue;
            }
            String wantBody = Fase4OracleIT.hosts(String.valueOf(exp.get("body")));
            for (String[] c : CORRECCIONES) {
                wantBody = wantBody.replaceAll(c[0], c[1]);
            }
            Object got = PhpJson.decode(Fase4OracleIT.hosts(res.body()));
            if (url.contains("guias") && got instanceof Map<?, ?> g && g.get("datos") instanceof Map<?, ?> datos) {
                // Corrección: la guía informa su tipo_documento (API-PRO no lo devolvía)
                Object lista = datos.get("datos");
                for (Object o : lista instanceof List<?> l ? l : List.of(datos)) {
                    Object tipo = ((Map<String, Object>) o).remove("tipo_documento");
                    if (!"09".equals(tipo) && !"31".equals(tipo)) {
                        fallas.add(name + ": tipo_documento ausente o inválido: " + tipo);
                    }
                }
            }
            Object wanted = PhpJson.decode(wantBody);
            corregirTipoOperacion(step.get("body"), wanted);
            if (!got.equals(wanted)) {
                fallas.add(name + ":\n    esperado " + PhpJson.encodeUnescaped(wanted) + "\n    obtenido " + PhpJson.encodeUnescaped(got));
                continue;
            }
            if (exp.get("xml_signed") != null) {
                String xml = signedXml(url, (Map<String, Object>) ((Map<String, Object>) got).get("datos"));
                xmlComparados++;
                if (!xml.equals(exp.get("xml_signed"))) {
                    fallas.add(name + ": XML firmado distinto\n" + FacturaBoletaOracleIT.firstDiff(String.valueOf(exp.get("xml_signed")), xml));
                }
            }
        }
        assertThat(fallas).as(String.join("\n", fallas)).isEmpty();
        assertThat(xmlComparados).isGreaterThan(20);
    }

    /**
     * Corrección (DIFERENCIAS): con detracción o percepción se guarda y devuelve el tipo de operación
     * que lleva el XML (1001 / 2001); API-PRO respondía 0101.
     */
    @SuppressWarnings("unchecked")
    static void corregirTipoOperacion(Object stepBody, Object want) {
        if (!(stepBody instanceof Map<?, ?> body) || !(want instanceof Map<?, ?> w) || !(w.get("datos") instanceof Map<?, ?> datos)
                || !datos.containsKey("tipo_operacion")) {
            return;
        }
        String tipo = null;
        if (body.get("detraccion") instanceof Map<?, ?> det && !det.isEmpty()) {
            tipo = det.get("tipo_operacion") != null ? String.valueOf(det.get("tipo_operacion")) : "1001";
        }
        if (body.get("percepcion") instanceof Map<?, ?> per && !per.isEmpty()) {
            tipo = per.get("tipo_operacion") != null ? String.valueOf(per.get("tipo_operacion")) : "2001";
        }
        if (tipo != null) {
            ((Map<String, Object>) datos).put("tipo_operacion", tipo);
        }
    }

    /** XML firmado tal como lo enviaría el trabajo de la cola. */
    @SuppressWarnings("unchecked")
    String signedXml(String url, Map<String, Object> datos) {
        Tenant demo = tenants.findByRuc("20000000001").orElseThrow();
        if (url.contains("reversiones")) {
            var v = tickets.findById(TicketRepository.VOIDED, ((Number) datos.get("voided_id")).longValue()).orElseThrow();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("correlativo", v.correlativo);
            data.put("fecha_generacion", v.fechaReferencia.toString());
            data.put("fecha_comunicacion", v.fechaEnvio.toString());
            data.put("detalles", v.detalles);
            return sender.signXml(demo, TicketXmlBuilder.voided(TicketModelBuilder.reversion(demo, data))).xml();
        }
        RetPerKind rp = url.contains("retenciones") ? RetPerKind.RETENCION : url.contains("percepciones") ? RetPerKind.PERCEPCION : null;
        if (rp != null) {
            long id = ((Number) datos.get("id")).longValue();
            Map<String, Object> data = SendRetPerJob.toArray(rp, retper.findById(rp, id).orElseThrow(), retper.items(rp, id));
            return sender.signXml(demo, RetPerXmlBuilder.build(RetPerModelBuilder.build(demo, data, rp.perception))).xml();
        }
        if (url.contains("guias")) {
            Map<String, Object> data = GuideJobs.toArray(guides.findById(((Number) datos.get("id")).longValue()).orElseThrow(), demo);
            return sender.signXml(demo, DespatchXmlBuilder.build(DespatchModelBuilder.build(demo, data), data)).xml();
        }
        SaleDocument d = documents.find(DocumentKind.FACTURA, demo.getId(), ((Number) datos.get("id")).longValue()).orElseThrow();
        documents.loadItems(d);
        Map<String, Object> data = DocumentArrayMapper.toArray(d);
        Map<String, Object> contrato = d.contratoColaboracion instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        return sender.sign(demo, new InvoiceModelBuilder(demo, taxRates).build(data), contrato).xml();
    }
}
