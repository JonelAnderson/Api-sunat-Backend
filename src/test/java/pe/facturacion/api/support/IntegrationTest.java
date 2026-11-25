package pe.facturacion.api.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base de pruebas de integración: la aplicación completa sobre PostgreSQL y Redis reales
 * (Testcontainers, mismas imágenes que docker-compose). Los contenedores se comparten entre clases.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    /** 64 caracteres, como Str::random(64). */
    public static final String DEMO_KEY = "DemoKey" + "0123456789".repeat(5) + "abcdefg";
    public static final String DEMO_SECRET = "demo-secret-test";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("api_sunat_test").withUsername("test").withPassword("test");
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("app.encryption-key", () -> Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()));
        r.add("app.seed", () -> "true");
        r.add("app.demo.api-key", () -> DEMO_KEY);
        r.add("app.demo.api-secret", () -> DEMO_SECRET);
        String storage = Files.createTempDirectory("api-sunat-storage").toString();
        r.add("app.storage-path", () -> storage);
        // Cada contexto de pruebas tiene su propia cola: los workers de otros contextos no la consumen
        String queuePrefix = "queues-test-" + java.util.UUID.randomUUID();
        r.add("app.queue.prefix", () -> queuePrefix);
        // Las pruebas invocan las tareas programadas directamente
        r.add("app.scheduler.enabled", () -> "false");
    }

    @LocalServerPort
    protected int port;

    protected final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    protected HttpResponse<String> send(String method, String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        return send("GET", path, null, headers);
    }

    /** GET de un binario (PDF, ZIP). */
    protected HttpResponse<byte[]> getBytes(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30)).GET();
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** Texto de un PDF (PDFBox). */
    protected static String pdfText(byte[] pdf) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        }
    }

    /** Archivo para multipart. */
    public record Part(String filename, byte[] content) {
    }

    /** Petición multipart/form-data con campos de texto y archivos. */
    protected HttpResponse<String> multipart(String method, String path, Map<String, String> fields, Map<String, Part> files,
            Map<String, String> headers) throws Exception {
        String boundary = "----apisunat" + System.nanoTime();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
        for (Map.Entry<String, String> f : fields.entrySet()) {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n"
                    + f.getValue() + "\r\n").getBytes(utf8));
        }
        for (Map.Entry<String, Part> f : files.entrySet()) {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"; filename=\""
                    + f.getValue().filename() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(utf8));
            out.write(f.getValue().content());
            out.write("\r\n".getBytes(utf8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(utf8));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected static byte[] resource(String path) throws Exception {
        try (java.io.InputStream in = IntegrationTest.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }

    /** Crea una empresa de prueba y devuelve sus cabeceras de autenticación. */
    protected Map<String, String> newTenant(pe.facturacion.api.modules.tenant.domain.TenantRepository repo, String ruc) {
        String key = pe.facturacion.api.security.ApiCredentials.newApiKey();
        pe.facturacion.api.modules.tenant.domain.Tenant t = new pe.facturacion.api.modules.tenant.domain.Tenant();
        t.setRuc(ruc);
        t.setRazonSocial("EMPRESA " + ruc);
        t.setSolUser(pe.facturacion.api.shared.crypto.EncryptedString.ofPlain("MODDATOS"));
        t.setSolPass(pe.facturacion.api.shared.crypto.EncryptedString.ofPlain("MODDATOS"));
        t.setApiKey(key);
        t.setApiSecret(pe.facturacion.api.shared.crypto.EncryptedString.ofPlain("secret-" + ruc));
        t.setPlan("business"); // 300 req/min: el rate limit no interfiere en las pruebas
        repo.save(t);
        return Map.of("X-Api-Key", key, "X-Api-Secret", "secret-" + ruc);
    }

    protected static Map<String, String> demoAuth() {
        return Map.of("X-Api-Key", DEMO_KEY, "X-Api-Secret", DEMO_SECRET);
    }
}
