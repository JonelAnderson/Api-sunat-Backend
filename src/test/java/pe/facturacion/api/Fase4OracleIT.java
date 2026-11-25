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
import java.util.Set;
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
import pe.facturacion.api.modules.tax.TaxRateService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.modules.ticket.TicketJobs;
import pe.facturacion.api.modules.ticket.TicketRepository;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.sunat.SunatSender;
import pe.facturacion.api.sunat.builder.InvoiceModelBuilder;
import pe.facturacion.api.sunat.builder.NoteModelBuilder;
import pe.facturacion.api.sunat.builder.TicketModelBuilder;
import pe.facturacion.api.sunat.xml.TicketXmlBuilder;
import pe.facturacion.api.support.IntegrationTest;

/**
 * Fase 4 contra el oráculo de API-PRO ({@code fixtures/oracle/fase4.json}): notas de crédito y
 * débito, resumen diario (envío y anulación), comunicación de baja, sus validaciones, listados y
 * consultas de estado. Mismo HTTP, mismo JSON y mismo XML firmado.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {"app.queue.worker-enabled=false", "app.security.internal-token=token-interno-pruebas", "app.paridad-api-pro=true"})
class Fase4OracleIT extends IntegrationTest {

    /** Pasos que no se comparan: el oráculo (SQLite) no soporta DATE_FORMAT y respondió 500. */
    static final Set<String> SKIP = Set.of("Resúmenes del mes");

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TenantRepository tenants;
    @Autowired
    CertificateStore certificates;
    @Autowired
    SaleDocumentRepository documents;
    @Autowired
    TicketRepository tickets;
    @Autowired
    TicketJobs ticketJobs;
    @Autowired
    SunatSender sender;
    @Autowired
    TaxRateService taxRates;

    Map<String, Object> scenario;
    List<Map<String, Object>> expected;

    @BeforeAll
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        scenario = PhpJson.decodeObject(new String(resource("/fixtures/oracle/scenario_fase4.json"), StandardCharsets.UTF_8));
        expected = (List<Map<String, Object>>) PhpJson.decode(new String(resource("/fixtures/oracle/fase4.json"), StandardCharsets.UTF_8));
        Clock.useFixed(Instant.parse(String.valueOf(scenario.get("now"))));
        jdbc.execute("TRUNCATE invoice_items, boleta_items, credit_note_items, debit_note_items, payments, invoices, boletas, "
                + "credit_notes, debit_notes, summaries, voided_documents RESTART IDENTITY");
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
        int xmlEsperados = 0;
        java.util.Set<String> bajasManuales = new java.util.HashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            Map<String, Object> exp = expected.get(i);
            String name = String.valueOf(step.get("name"));
            if (step.get("sql") != null) {
                for (String q : String.valueOf(step.get("sql")).split(";")) {
                    if (!q.isBlank()) {
                        jdbc.update(q);
                    }
                }
                continue;
            }
            HttpResponse<String> res = send(String.valueOf(step.get("method")), String.valueOf(step.get("url")),
                    step.get("body") == null ? null : PhpJson.encode(step.get("body")), headers);
            if (SKIP.contains(name)) {
                continue;
            }
            if (res.statusCode() == 201 && String.valueOf(step.get("url")).equals("/api/v1/anulaciones") && step.get("body") instanceof Map<?, ?> b
                    && Boolean.FALSE.equals(b.get("enviar_automatico"))) {
                for (Object o : (List<Object>) b.get("detalles")) {
                    Map<?, ?> det = (Map<?, ?>) o;
                    bajasManuales.add(det.get("serie") + "|" + Integer.parseInt(String.valueOf(det.get("correlativo"))));
                }
            }
            int want = ((Number) exp.get("status")).intValue();
            if (res.statusCode() != want) {
                fallas.add(name + ": HTTP " + res.statusCode() + " (esperado " + want + ") " + res.body());
                continue;
            }
            Object got = PhpJson.decode(hosts(res.body()));
            Object wanted = PhpJson.decode(hosts(String.valueOf(exp.get("body"))));
            corregirBajaManual(wanted, bajasManuales);
            if (!got.equals(wanted)) {
                fallas.add(name + ":\n    esperado " + PhpJson.encodeUnescaped(wanted) + "\n    obtenido " + PhpJson.encodeUnescaped(got));
                continue;
            }
            if (exp.get("xml_signed") != null) {
                xmlEsperados++;
                String xml = signedXml(String.valueOf(step.get("url")), (Map<String, Object>) ((Map<String, Object>) got).get("datos"));
                xmlComparados++;
                if (!xml.equals(exp.get("xml_signed"))) {
                    fallas.add(name + ": XML firmado distinto\n" + FacturaBoletaOracleIT.firstDiff(String.valueOf(exp.get("xml_signed")), xml));
                }
            }
        }
        assertThat(fallas).as(String.join("\n", fallas)).isEmpty();
        assertThat(xmlComparados).isEqualTo(xmlEsperados).isGreaterThan(15);
    }

    /**
     * Corrección (O1): una baja manual pendiente no marca sus comprobantes hasta que se envía;
     * API-PRO los dejaba en "anulacion_en_proceso" desde que se creaba.
     */
    @SuppressWarnings("unchecked")
    static void corregirBajaManual(Object o, java.util.Set<String> manuales) {
        if (o instanceof List<?> l) {
            l.forEach(x -> corregirBajaManual(x, manuales));
        } else if (o instanceof Map<?, ?> m) {
            if (m.get("sunat") instanceof Map<?, ?> s && "anulacion_en_proceso".equals(s.get("estado"))
                    && manuales.contains(m.get("serie") + "|" + m.get("correlativo"))) {
                ((Map<String, Object>) s).put("estado", "aceptado");
            }
            m.values().forEach(x -> corregirBajaManual(x, manuales));
        }
    }

    /** XML firmado tal como lo enviaría el trabajo de la cola. */
    @SuppressWarnings("unchecked")
    String signedXml(String url, Map<String, Object> datos) {
        Tenant demo = tenants.findByRuc("20000000001").orElseThrow();
        if (url.contains("resumenes")) {
            var s = tickets.findById(TicketRepository.SUMMARIES, ((Number) datos.get("id_resumen")).longValue()).orElseThrow();
            return sender.signXml(demo, TicketXmlBuilder.summary(TicketModelBuilder.summary(demo, ticketJobs.summaryData(s, demo)))).xml();
        }
        if (url.contains("anulaciones")) {
            var v = tickets.findById(TicketRepository.VOIDED, ((Number) datos.get("id_anulacion")).longValue()).orElseThrow();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("correlativo", v.correlativo);
            data.put("fecha_generacion", v.fechaReferencia.toString());
            data.put("fecha_comunicacion", v.fechaEnvio.toString());
            data.put("detalles", v.detalles);
            return sender.signXml(demo, TicketXmlBuilder.voided(TicketModelBuilder.voided(demo, data))).xml();
        }
        DocumentKind kind = url.contains("notas-credito") ? DocumentKind.NOTA_CREDITO
                : url.contains("notas-debito") ? DocumentKind.NOTA_DEBITO
                : url.contains("boletas") ? DocumentKind.BOLETA : DocumentKind.FACTURA;
        SaleDocument d = documents.find(kind, demo.getId(), ((Number) datos.get("id")).longValue()).orElseThrow();
        documents.loadItems(d);
        Map<String, Object> data = DocumentArrayMapper.toArray(d);
        var model = kind.isNote() ? new NoteModelBuilder(demo, taxRates).build(data) : new InvoiceModelBuilder(demo, taxRates).build(data);
        Map<String, Object> contrato = d.contratoColaboracion instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        return sender.sign(demo, model, contrato).xml();
    }

    /** El oráculo responde con host "localhost" sin puerto. */
    static String hosts(String body) {
        return body.replaceAll("http:\\\\?/\\\\?/localhost(:\\d+)?", "http://localhost");
    }
}
