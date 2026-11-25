package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import pe.facturacion.api.modules.certificate.CertificateStore;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.support.IntegrationTest;

/**
 * Fase 6 contra el oráculo de API-PRO ({@code fixtures/oracle/fase6.json}): la representación
 * impresa de 48 comprobantes (facturas en todas sus variantes, boletas, notas, guías 09/31 y
 * retenciones) en A4, A5 y tickets de 80 y 58 mm. Se compara el texto del PDF (extraído con PDFBox
 * de ambos PDF, como multiconjunto de palabras), el contenido del QR y las cabeceras HTTP.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {"app.queue.worker-enabled=false", "app.security.internal-token=token-interno-pruebas", "app.paridad-api-pro=true"})
class Fase6OracleIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TenantRepository tenants;
    @Autowired
    CertificateStore certificates;

    Map<String, Object> scenario;
    List<Map<String, Object>> expected;

    @BeforeAll
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        scenario = PhpJson.decodeObject(new String(resource("/fixtures/oracle/scenario_fase6.json"), StandardCharsets.UTF_8));
        expected = (List<Map<String, Object>>) PhpJson.decode(new String(resource("/fixtures/oracle/fase6.json"), StandardCharsets.UTF_8));
        Clock.useFixed(Instant.parse(String.valueOf(scenario.get("now"))));
        jdbc.execute("TRUNCATE invoice_items, boleta_items, credit_note_items, debit_note_items, payments, invoices, boletas, "
                + "credit_notes, debit_notes, summaries, voided_documents, retention_items, retentions, perception_items, perceptions, "
                + "dispatch_guides RESTART IDENTITY");
        Map<String, Object> t = (Map<String, Object>) scenario.get("tenant");
        Tenant demo = tenants.findByRuc("20000000001").orElseThrow();
        jdbc.update("UPDATE series SET correlativo = 0 WHERE tenant_id = ?", demo.getId());
        for (String[] s : new String[][] {{"07", "FC01"}, {"07", "BC01"}, {"08", "FD01"}, {"08", "BD01"}}) {
            jdbc.update("INSERT INTO series (tenant_id, tipo_documento, serie, correlativo, is_active, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 0, true, now(), now()) ON CONFLICT DO NOTHING", demo.getId(), s[0], s[1]);
        }
        demo.setCertificatePath(certificates.store(demo, new String(resource("/fixtures/certs/cert-con-clave.pem"), StandardCharsets.US_ASCII)));
        demo.setTelefonos((List<Object>) t.get("telefonos"));
        demo.setEmails((List<Object>) t.get("emails"));
        demo.setCuentasBancarias((List<Object>) t.get("cuentas_bancarias"));
        demo.setBilleterasDigitales((List<Object>) t.get("billeteras_digitales"));
        demo.setMensajeAgradecimiento((String) t.get("mensaje_agradecimiento"));
        demo.setMensajePromocional((String) t.get("mensaje_promocional"));
        tenants.save(demo);
    }

    @AfterAll
    void tearDown() {
        Clock.useSystem();
    }

    @Test
    @SuppressWarnings("unchecked")
    void mismoContenidoQueApiPro() throws Exception {
        Map<String, String> headers = new HashMap<>(demoAuth());
        headers.put("X-Internal-Token", "token-interno-pruebas");
        headers.put("Accept", "application/json");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) scenario.get("steps");
        List<String> fallas = new ArrayList<>();
        int pdfs = 0;
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
            if (exp.get("pdf_base64") == null) {
                HttpResponse<String> res = send(String.valueOf(step.get("method")), url,
                        step.get("body") == null ? null : PhpJson.encode(step.get("body")), headers);
                int want = ((Number) exp.get("status")).intValue();
                if (res.statusCode() != want) {
                    fallas.add(name + ": HTTP " + res.statusCode() + " (esperado " + want + ") " + res.body());
                } else if (want >= 400 && !PhpJson.decode(res.body()).equals(PhpJson.decode(String.valueOf(exp.get("body"))))) {
                    fallas.add(name + ": " + res.body() + " ≠ " + exp.get("body"));
                }
                continue;
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + url)).GET();
            headers.forEach(b::header);
            HttpResponse<byte[]> res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                fallas.add(name + ": HTTP " + res.statusCode() + " " + new String(res.body(), StandardCharsets.UTF_8));
                continue;
            }
            pdfs++;
            Map<String, Object> eh = (Map<String, Object>) exp.get("headers");
            if (!"application/pdf".equals(res.headers().firstValue("Content-Type").orElse(""))
                    || !String.valueOf(eh.get("content-disposition")).equals(res.headers().firstValue("Content-Disposition").orElse(""))) {
                fallas.add(name + ": cabeceras " + res.headers().map());
            }
            byte[] oracle = Base64.getDecoder().decode(String.valueOf(exp.get("pdf_base64")));
            Map<String, Integer> want = words(oracle);
            Map<String, Integer> got = words(res.body());
            if (!want.equals(got)) {
                fallas.add(name + ": texto distinto\n    falta " + diff(want, got) + "\n    sobra " + diff(got, want));
            }
            List<String> qrs = (List<String>) exp.get("qr");
            if (!qrs.isEmpty()) {
                String wantQr = qrs.get(0);
                // Corrección: la GRT (serie V) informa su tipo real 31; API-PRO ponía 09
                String[] p = wantQr.split("\\|", -1);
                if ("09".equals(p[1]) && p[2].startsWith("V")) {
                    p[1] = "31";
                    wantQr = String.join("|", p);
                }
                String gotQr = qr(res.body());
                if (!wantQr.equals(gotQr)) {
                    fallas.add(name + ": QR " + gotQr + " ≠ " + wantQr);
                }
            }
        }
        assertThat(fallas).as(String.join("\n", fallas)).isEmpty();
        assertThat(pdfs).isGreaterThan(190);
    }

    @Test
    void aliasApiSunat() throws Exception {
        // /api/sunat/facturas|boletas (index, pdf) sobre los endpoints V1
        Map<String, String> h = new HashMap<>(demoAuth());
        h.put("Accept", "application/json");
        assertThat(get("/api/sunat/facturas", h).statusCode()).isEqualTo(200);
        assertThat(get("/api/sunat/boletas", h).statusCode()).isEqualTo(200);
        assertThat(getBytes("/api/sunat/facturas/999999/pdf", h).statusCode()).isEqualTo(404);
    }

    /** Palabras del texto del PDF (multiconjunto): independiente del motor y del salto de línea. */
    static Map<String, Integer> words(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            Map<String, Integer> m = new TreeMap<>();
            for (String w : text.trim().split("\\s+")) {
                if (!w.isEmpty()) {
                    m.merge(w, 1, Integer::sum);
                }
            }
            return m;
        }
    }

    static Map<String, Integer> diff(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> d = new TreeMap<>();
        a.forEach((k, v) -> {
            int n = v - b.getOrDefault(k, 0);
            if (n > 0) {
                d.put(k, n);
            }
        });
        return d;
    }

    /** Lee el QR: la imagen embebida que ZXing pueda decodificar. */
    public static String qr(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                for (COSName n : page.getResources().getXObjectNames()) {
                    PDXObject x = page.getResources().getXObject(n);
                    if (x instanceof PDImageXObject img) {
                        BufferedImage bi = img.getImage();
                        String text = decode(bi);
                        if (text != null) {
                            return text;
                        }
                    }
                }
            }
            // Imagen anidada en un XObject de formulario: se decodifica la página renderizada
            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                String text = decode(renderer.renderImageWithDPI(i, 200));
                if (text != null) {
                    return text;
                }
            }
        }
        return null;
    }

    static String decode(BufferedImage bi) {
        try {
            return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(bi))),
                    Map.of(com.google.zxing.DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
        } catch (Exception e) {
            // segundo intento: código "puro" (imagen generada, sin ruido)
        }
        try {
            return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(bi))),
                    Map.of(com.google.zxing.DecodeHintType.PURE_BARCODE, Boolean.TRUE)).getText();
        } catch (Exception e) {
            return null;
        }
    }
}
