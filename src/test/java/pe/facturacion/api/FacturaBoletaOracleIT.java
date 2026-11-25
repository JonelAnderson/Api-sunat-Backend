package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
import pe.facturacion.api.modules.tax.TaxRateService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.sunat.SunatSender;
import pe.facturacion.api.sunat.builder.InvoiceModelBuilder;
import pe.facturacion.api.support.IntegrationTest;

/**
 * Los 28 ejemplos de facturas y boletas de la colección Postman, enviados a esta API y comparados
 * con lo que respondió API-PRO real ({@code fixtures/oracle/fact_bol.json}): mismo código HTTP,
 * mismo cuerpo JSON y mismo XML firmado (con el mismo certificado de prueba).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {"app.queue.worker-enabled=false", "app.security.internal-token=token-interno-pruebas", "app.paridad-api-pro=true"})
class FacturaBoletaOracleIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TenantRepository tenants;
    @Autowired
    CertificateStore certificates;
    @Autowired
    SaleDocumentRepository documents;
    @Autowired
    SunatSender sender;
    @Autowired
    TaxRateService taxRates;

    Map<String, Object> scenario;
    List<Map<String, Object>> expected;

    @BeforeAll
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        scenario = PhpJson.decodeObject(new String(resource("/fixtures/oracle/scenario_fact_bol.json"), StandardCharsets.UTF_8));
        expected = (List<Map<String, Object>>) PhpJson.decode(new String(resource("/fixtures/oracle/fact_bol.json"), StandardCharsets.UTF_8));
        Clock.useFixed(Instant.parse(String.valueOf(scenario.get("now"))));

        Tenant demo = tenants.findByRuc("20000000001").orElseThrow();
        for (String t : List.of("invoice_items", "boleta_items")) {
            jdbc.update("DELETE FROM " + t + " WHERE " + (t.startsWith("invoice") ? "invoice_id" : "boleta_id")
                    + " IN (SELECT id FROM " + (t.startsWith("invoice") ? "invoices" : "boletas") + " WHERE tenant_id = ?)", demo.getId());
        }
        jdbc.update("DELETE FROM payments WHERE tenant_id = ?", demo.getId());
        jdbc.update("DELETE FROM invoices WHERE tenant_id = ?", demo.getId());
        jdbc.update("DELETE FROM boletas WHERE tenant_id = ?", demo.getId());
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
            String name = String.valueOf(step.get("name"));
            HttpResponse<String> res = send(String.valueOf(step.get("method")), String.valueOf(step.get("url")),
                    PhpJson.encode(step.get("body")), headers);
            if (res.statusCode() != ((Number) exp.get("status")).intValue()) {
                fallas.add(name + ": HTTP " + res.statusCode() + " (esperado " + exp.get("status") + ") " + res.body());
                continue;
            }
            Map<String, Object> got = PhpJson.decodeObject(res.body());
            Object docId = got.get("datos") instanceof Map<?, ?> dm ? dm.get("id") : null;
            Map<String, Object> want = PhpJson.decodeObject(String.valueOf(exp.get("body")));
            normalize(got);
            normalize(want);
            Fase5OracleIT.corregirTipoOperacion(step.get("body"), want);
            if (!got.equals(want)) {
                fallas.add(name + ":\n    esperado " + PhpJson.encodeUnescaped(want) + "\n    obtenido " + PhpJson.encodeUnescaped(got));
                continue;
            }
            if (exp.get("xml_signed") != null) {
                DocumentKind kind = String.valueOf(step.get("url")).contains("boletas") ? DocumentKind.BOLETA : DocumentKind.FACTURA;
                Tenant demo = tenants.findByRuc("20000000001").orElseThrow();
                SaleDocument d = documents.find(kind, demo.getId(), ((Number) docId).longValue()).orElseThrow();
                documents.loadItems(d);
                var invoice = new InvoiceModelBuilder(demo, taxRates).build(DocumentArrayMapper.toArray(d));
                Map<String, Object> contrato = d.contratoColaboracion instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
                String xml = sender.sign(demo, invoice, contrato).xml();
                xmlComparados++;
                if (!xml.equals(exp.get("xml_signed"))) {
                    fallas.add(name + ": XML firmado distinto\n" + firstDiff(String.valueOf(exp.get("xml_signed")), xml));
                }
            }
        }
        assertThat(fallas).as(String.join("\n", fallas)).isEmpty();
        assertThat(xmlComparados).as("XML firmados comparados").isEqualTo(steps.size());
    }

    /** Ids autoincrementales y hosts no se comparan. */
    @SuppressWarnings("unchecked")
    static void normalize(Map<String, Object> body) {
        if (body.get("datos") instanceof Map<?, ?> d) {
            ((Map<String, Object>) d).remove("id");
        }
    }

    static String firstDiff(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        int from = Math.max(0, i - 150);
        return "    esperado …" + a.substring(from, Math.min(a.length(), i + 150)) + "\n    obtenido …" + b.substring(from, Math.min(b.length(), i + 150));
    }
}
