package pe.facturacion.api.modules.consulta;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import pe.facturacion.api.modules.settings.PlatformSettings;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.php.Php;

/**
 * {@code DocumentLookupService}: RUC (SUNAT) y DNI (RENIEC) en el proveedor externo
 * (apis.net.pe por defecto, {@code LOOKUP_BASE_URL} / {@code LOOKUP_API_TOKEN}). Solo se guardan
 * en caché (24 h) las consultas con resultado. Nunca registra la respuesta (datos personales).
 *
 * <p>Token: el global que guarda el panel ({@code settings.consulta_token_global}, cifrado) y, si no hay uno, el de
 * {@code LOOKUP_API_TOKEN}.
 */
@Service
public class DocumentLookupService {

    private static final Logger log = LoggerFactory.getLogger(DocumentLookupService.class);
    private static final Duration CACHE = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final PlatformSettings settings;
    private final String baseUrl;
    private final String token;
    private final Duration timeout;
    private final HttpClient http;

    public DocumentLookupService(StringRedisTemplate redis, PlatformSettings settings, @Value("${app.lookup.base-url:https://api.apis.net.pe}") String baseUrl,
            @Value("${app.lookup.token:}") String token, @Value("${app.lookup.timeout:8}") int timeoutSeconds) {
        this.redis = redis;
        this.settings = settings;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    /** Datos del documento o null si el proveedor no lo encuentra (o falla). */
    public Map<String, Object> lookup(String tipo, String numero) {
        String key = "doc_lookup:" + tipo + ":" + numero;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return PhpJson.decodeObject(cached);
        }
        Map<String, Object> r = "6".equals(tipo) ? ruc(numero) : dni(numero);
        if (r != null) {
            redis.opsForValue().set(key, PhpJson.encode(r), CACHE);
        }
        return r;
    }

    private Map<String, Object> ruc(String ruc) {
        Map<String, Object> d = get("ruc", ruc);
        if (d == null) {
            return null;
        }
        Object razon = first(d, "razonSocial", "nombre_o_razon_social", "nombre");
        if (Php.empty(razon)) {
            log.warn("lookup.ruc.sin_razon_social");
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo_doc", "6");
        m.put("num_doc", ruc);
        m.put("razon_social", razon);
        m.put("direccion", orEmpty(first(d, "direccion", "direccion_completa")));
        m.put("estado", orEmpty(d.get("estado")));
        m.put("condicion", orEmpty(d.get("condicion")));
        m.put("source", "sunat");
        return m;
    }

    private Map<String, Object> dni(String dni) {
        Map<String, Object> d = get("dni", dni);
        if (d == null) {
            return null;
        }
        Object nombres = d.get("nombres");
        Object paterno = orEmpty(first(d, "apellidoPaterno", "apellido_paterno"));
        Object materno = orEmpty(first(d, "apellidoMaterno", "apellido_materno"));
        Object completo = first(d, "nombreCompleto", "nombre_completo");
        if (Php.empty(nombres) && Php.empty(completo)) {
            log.warn("lookup.dni.sin_nombres");
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo_doc", "1");
        m.put("num_doc", dni);
        m.put("razon_social", completo != null ? completo : (Php.toStr(paterno) + " " + Php.toStr(materno) + " " + Php.toStr(nombres)).trim());
        m.put("nombres", orEmpty(nombres));
        m.put("apellido_paterno", paterno);
        m.put("apellido_materno", materno);
        m.put("direccion", orEmpty(first(d, "direccion", "direccion_completa")));
        m.put("source", "reniec");
        return m;
    }

    /** GET {base}/v1/{ruc|dni}?numero=...; devuelve {@code data} si viene envuelto. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path, String numero) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/" + path + "?numero=" + URLEncoder.encode(numero, StandardCharsets.UTF_8)))
                    .timeout(timeout).header("Authorization", "Bearer " + token()).header("Accept", "application/json").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("lookup.{} http={}", path, res.statusCode());
                return null;
            }
            Object body = PhpJson.decode(res.body());
            if (!(body instanceof Map<?, ?> m)) {
                return null;
            }
            Object data = m.get("data");
            return (Map<String, Object>) (data instanceof Map<?, ?> ? data : m);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("lookup.{} error={}", path, e.getClass().getSimpleName());
            return null;
        }
    }

    /** Token del panel (BD) o, si no hay, el de la variable de entorno. */
    String token() {
        return settings.lookupToken().orElse(token);
    }

    /** true si hay un token del entorno ({@code LOOKUP_API_TOKEN}); el panel lo muestra sin revelarlo. */
    public boolean environmentTokenConfigured() {
        return token != null && !token.isBlank();
    }

    private static Object first(Map<String, Object> d, String... keys) {
        for (String k : keys) {
            if (d.get(k) != null) {
                return d.get(k);
            }
        }
        return null;
    }

    private static Object orEmpty(Object v) {
        return v == null ? "" : v;
    }
}
