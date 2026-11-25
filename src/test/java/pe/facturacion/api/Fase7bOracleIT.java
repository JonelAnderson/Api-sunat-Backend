package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.support.IntegrationTest;

/**
 * Fase 7B contra el oráculo de API-PRO ({@code fixtures/oracle/fase7b.json}): panel, reportes en
 * JSON y PDF, exportación ZIP, búsqueda de documento y consulta de CPE. Mismo escenario en las dos
 * APIs (comprobantes, sucursales, clientes, pagos y ajustes por SQL) y misma respuesta, salvo las
 * correcciones listadas en {@link #CORRECCIONES} (ver docs/DIFERENCIAS.md).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {"app.queue.worker-enabled=false", "app.security.internal-token=token-interno-pruebas", "app.paridad-api-pro=true",
    "app.emision-ilimitada-global=true", "app.lookup.token=token-pruebas"})
class Fase7bOracleIT extends IntegrationTest {

    /**
     * Correcciones sobre la respuesta de API-PRO (regex → reemplazo): días de atraso enteros (Carbon 3
     * devolvía días con fracción) y números de documento agrupados como texto (PHP los volvía número).
     */
    static final List<String[]> CORRECCIONES = List.of(
            new String[] {"\"dias_atraso\":(\\d+)\\.\\d+", "\"dias_atraso\":$1"},
            new String[] {"\"num_doc\":(\\d+)", "\"num_doc\":\"$1\""});

    static final HttpServer LOOKUP;
    static final Map<String, byte[]> FAKES = new HashMap<>();

    static {
        try {
            LOOKUP = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            LOOKUP.createContext("/", ex -> {
                byte[] body = FAKES.get(ex.getRequestURI().toString());
                boolean auth = "Bearer token-pruebas".equals(ex.getRequestHeaders().getFirst("Authorization"));
                ex.getResponseHeaders().add("Content-Type", "application/json");
                if (body == null || !auth) {
                    ex.sendResponseHeaders(404, -1);
                } else {
                    ex.sendResponseHeaders(200, body.length);
                    ex.getResponseBody().write(body);
                }
                ex.close();
            });
            LOOKUP.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void lookupProps(DynamicPropertyRegistry r) {
        r.add("app.lookup.base-url", () -> "http://127.0.0.1:" + LOOKUP.getAddress().getPort());
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    FileStorage storage;

    Map<String, Object> scenario;
    List<Map<String, Object>> expected;

    @BeforeAll
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        scenario = PhpJson.decodeObject(new String(resource("/fixtures/oracle/scenario_fase7b.json"), StandardCharsets.UTF_8));
        expected = (List<Map<String, Object>>) PhpJson.decode(new String(resource("/fixtures/oracle/fase7b.json"), StandardCharsets.UTF_8));
        for (Map<String, Object> f : (List<Map<String, Object>>) scenario.get("http_fakes")) {
            FAKES.put(String.valueOf(f.get("url")).replace("https://api.apis.net.pe", ""), PhpJson.encode(f.get("body")).getBytes(StandardCharsets.UTF_8));
        }
        Clock.useFixed(Instant.parse(String.valueOf(scenario.get("now"))));
        jdbc.execute("TRUNCATE invoice_items, boleta_items, credit_note_items, debit_note_items, payments, invoices, boletas, "
                + "credit_notes, debit_notes, summaries, voided_documents RESTART IDENTITY");
        // Mismos ids que el oráculo (sucursales 1 y 2, clientes desde 1); las referencias quedan en NULL
        jdbc.execute("DELETE FROM sucursales");
        jdbc.execute("DELETE FROM clients");
        jdbc.execute("ALTER SEQUENCE sucursales_id_seq RESTART WITH 1");
        jdbc.execute("ALTER SEQUENCE clients_id_seq RESTART WITH 1");
        jdbc.update("UPDATE series SET correlativo = 0 WHERE tenant_id = (SELECT id FROM tenants WHERE ruc = '20000000001')");
        jdbc.update("UPDATE tenants SET client_id = NULL, client_secret = NULL WHERE ruc = '20000000001'");
        Set<String> keys = redis.keys("report:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @AfterAll
    void tearDown() {
        Clock.useSystem();
        LOOKUP.stop(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mismasRespuestasQueApiPro() throws Exception {
        Map<String, String> headers = new HashMap<>(demoAuth());
        headers.put("X-Internal-Token", "token-interno-pruebas");
        headers.put("Accept", "application/json");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) scenario.get("steps");
        List<String> fallas = new ArrayList<>();
        int pdfs = 0;
        int zips = 0;
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
            if (step.get("file") != null) {
                storage.put(Disk.PRIVATE, String.valueOf(step.get("file")), String.valueOf(step.get("content")).getBytes(StandardCharsets.UTF_8));
                continue;
            }
            // Sin caché entre pasos, como el oráculo
            for (String pattern : List.of("report:*", "doc_lookup:*")) {
                Set<String> keys = redis.keys(pattern);
                if (keys != null && !keys.isEmpty()) {
                    redis.delete(keys);
                }
            }
            String url = String.valueOf(step.get("url")).replace(" ", "%20");
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + url));
            headers.forEach(b::header);
            if (step.get("body") != null) {
                b.header("Content-Type", "application/json").method(String.valueOf(step.get("method")),
                        HttpRequest.BodyPublishers.ofString(PhpJson.encode(step.get("body"))));
            } else {
                b.method(String.valueOf(step.get("method")), HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<byte[]> res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            int want = ((Number) exp.get("status")).intValue();
            if (res.statusCode() != want) {
                fallas.add(name + ": HTTP " + res.statusCode() + " (esperado " + want + ") " + new String(res.body(), StandardCharsets.UTF_8));
                continue;
            }
            Map<String, Object> eh = (Map<String, Object>) exp.get("headers");
            if (exp.get("pdf_base64") != null) {
                pdfs++;
                if (!String.valueOf(eh.get("content-disposition")).equals(res.headers().firstValue("Content-Disposition").orElse(""))) {
                    fallas.add(name + ": cabeceras " + res.headers().map());
                }
                if (name.contains("Registro de ventas (PDF)")) {
                    String err = registroVentasCorregido(Base64.getDecoder().decode(String.valueOf(exp.get("pdf_base64"))), res.body());
                    if (err != null) {
                        fallas.add(name + ": " + err);
                    }
                    continue;
                }
                Map<String, Integer> wantWords = corregirPalabras(words(Base64.getDecoder().decode(String.valueOf(exp.get("pdf_base64")))));
                Map<String, Integer> gotWords = words(res.body());
                if (!wantWords.equals(gotWords)) {
                    fallas.add(name + ": texto distinto\n    falta " + Fase6OracleIT.diff(wantWords, gotWords) + "\n    sobra " + Fase6OracleIT.diff(gotWords, wantWords));
                }
                continue;
            }
            if (exp.get("zip") != null) {
                zips++;
                if (!String.valueOf(eh.get("content-disposition")).equals(res.headers().firstValue("Content-Disposition").orElse(""))) {
                    fallas.add(name + ": cabeceras " + res.headers().map());
                }
                List<Map<String, Object>> got = entradas(res.body());
                if (!got.equals(exp.get("zip"))) {
                    fallas.add(name + ": ZIP\n    esperado " + exp.get("zip") + "\n    obtenido " + got);
                }
                continue;
            }
            String wantBody = String.valueOf(exp.get("body"));
            for (String[] c : CORRECCIONES) {
                wantBody = wantBody.replaceAll(c[0], c[1]);
            }
            Object got = PhpJson.decode(new String(res.body(), StandardCharsets.UTF_8));
            Object wanted = PhpJson.decode(wantBody);
            if (!got.equals(wanted)) {
                fallas.add(name + ":\n    esperado " + PhpJson.encodeUnescaped(wanted) + "\n    obtenido " + PhpJson.encodeUnescaped(got));
            }
        }
        assertThat(fallas).as(String.join("\n", fallas)).isEmpty();
        assertThat(pdfs).isEqualTo(7);
        assertThat(zips).isEqualTo(4);
    }

    /**
     * Palabras del PDF uniendo los cortes de línea tras un guion: en columnas angostas ("F001-000001",
     * "2026-10-27") dompdf y Flying Saucer cortan en guiones distintos por fracciones de punto.
     */
    static Map<String, Integer> words(byte[] pdf) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc).replaceAll("-\\s+(?=\\S)", "-");
            Map<String, Integer> m = new TreeMap<>();
            for (String w : text.trim().split("\\s+")) {
                if (!w.isEmpty()) {
                    m.merge(w, 1, Integer::sum);
                }
            }
            return m;
        }
    }

    /** La hora del PDF es la de Lima y los días de atraso son enteros (ver CORRECCIONES). */
    static Map<String, Integer> corregirPalabras(Map<String, Integer> words) {
        Map<String, Integer> out = new TreeMap<>();
        words.forEach((w, n) -> {
            String c = w.equals("15:15:30") ? "10:15:30" : w.matches("\\d+\\.\\d{6,}") ? w.substring(0, w.indexOf('.')) : w;
            out.merge(c, n, Integer::sum);
        });
        return out;
    }

    /**
     * Corrección (P12): API-PRO repetía "Subtotal ..." cada vez que cambiaba el tipo al recorrer por
     * fecha (con el total completo del tipo); aquí las filas van agrupadas por tipo y cada subtotal
     * aparece una vez. Se exige: los mismos comprobantes, un subtotal por tipo con el mismo texto
     * que API-PRO, y el mismo gran total y venta neta.
     */
    static String registroVentasCorregido(byte[] oracle, byte[] ours) throws Exception {
        String want = text(oracle);
        String got = text(ours);
        // El PDF de API-PRO sin las repeticiones de cada línea "Subtotal ..."
        java.util.Set<String> vistas = new java.util.HashSet<>();
        StringBuilder sinRepetidos = new StringBuilder();
        for (String linea : want.split("\\R")) {
            if (linea.trim().startsWith("Subtotal ") && !vistas.add(linea.trim())) {
                continue;
            }
            sinRepetidos.append(linea).append('\n');
        }
        for (String l : vistas) {
            int veces = got.split(java.util.regex.Pattern.quote(l), -1).length - 1;
            if (veces != 1) {
                return "\"" + l + "\" aparece " + veces + " vez/veces (se espera 1)";
            }
        }
        // Mismo contenido sin importar el orden de las filas: el ajuste de línea en columnas angostas
        // ("2026-10-" / "24") se intercala distinto según la fila vecina, así que se separa también por guiones
        Map<String, Integer> w = tokens(sinRepetidos.toString());
        Map<String, Integer> g = tokens(got);
        if (!w.equals(g)) {
            return "texto distinto\n    falta " + Fase6OracleIT.diff(w, g) + "\n    sobra " + Fase6OracleIT.diff(g, w);
        }
        return null;
    }

    static Map<String, Integer> tokens(String text) {
        Map<String, Integer> m = new TreeMap<>();
        for (String t : text.trim().split("[\\s-]+")) {
            if (!t.isEmpty()) {
                m.merge(t.equals("15:15:30") ? "10:15:30" : t, 1, Integer::sum);
            }
        }
        return m;
    }

    static String text(byte[] pdf) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(d);
        }
    }

    static List<Map<String, Object>> entradas(byte[] zip) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", e.getName());
                m.put("sha1", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(in.readAllBytes())));
                out.add(m);
            }
        }
        return out;
    }
}
