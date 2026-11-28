package pe.facturacion.api.modules.consulta;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import pe.facturacion.api.modules.settings.PlatformSettings;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.php.Php;

/**
 * {@code DocumentLookupService}: RUC (SUNAT) y DNI (RENIEC) en el proveedor externo. Por defecto PeruDevs
 * ({@code LOOKUP_PROVIDER=perudevs}, el mismo que usa el ERP); {@code apisnetpe} usa apis.net.pe
 * ({@code LOOKUP_BASE_URL}). Solo se guardan en caché (24 h) las consultas con resultado. Nunca registra la respuesta
 * (datos personales) ni la clave.
 *
 * <p>Token / clave: la global que guarda el panel ({@code settings.consulta_token_global}, cifrada) y, si no hay una, la de
 * {@code LOOKUP_API_TOKEN}.
 *
 * <p>Ubicación del RUC: el proveedor da los nombres de departamento, provincia y distrito (apis.net.pe además el ubigeo);
 * el código de 6 dígitos sale de las tablas propias {@code ubigeo_regiones / provincias / distritos}.
 */
@Service
public class DocumentLookupService {

    private static final Logger log = LoggerFactory.getLogger(DocumentLookupService.class);
    private static final Duration CACHE = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final PlatformSettings settings;
    private final JdbcTemplate jdbc;
    private final boolean perudevs;
    private final String baseUrl;
    private final String token;
    private final Duration timeout;
    private final HttpClient http;

    public DocumentLookupService(StringRedisTemplate redis, PlatformSettings settings, JdbcTemplate jdbc,
            @Value("${app.lookup.provider:perudevs}") String provider,
            @Value("${app.lookup.base-url:}") String baseUrl,
            @Value("${app.lookup.token:}") String token, @Value("${app.lookup.timeout:8}") int timeoutSeconds) {
        this.redis = redis;
        this.settings = settings;
        this.jdbc = jdbc;
        this.perudevs = !"apisnetpe".equalsIgnoreCase(provider == null ? "" : provider.trim());
        String defaultBase = this.perudevs ? "https://api.perudevs.com/api/v1" : "https://api.apis.net.pe";
        this.baseUrl = (baseUrl == null || baseUrl.isBlank() ? defaultBase : baseUrl).replaceAll("/+$", "");
        this.token = token;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    /** Claves de ubicación que solo entrega {@link #lookupWithLocation}: la respuesta pública de /api/v1 no las lleva. */
    private static final String[] LOCATION_KEYS = {"ubigeo", "departamento", "provincia", "distrito"};

    /** Datos del documento o null si el proveedor no lo encuentra (o falla). */
    public Map<String, Object> lookup(String tipo, String numero) {
        Map<String, Object> full = lookupWithLocation(tipo, numero);
        if (full == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>(full);
        for (String k : LOCATION_KEYS) {
            m.remove(k);
        }
        return m;
    }

    /**
     * Lo mismo que {@link #lookup} y, si el proveedor lo trae, el ubigeo (6 dígitos) y los nombres de departamento,
     * provincia y distrito: lo usa el alta de empresas del panel para elegir la ubicación sola.
     */
    public Map<String, Object> lookupWithLocation(String tipo, String numero) {
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
        Object razon = first(d, "razon_social", "razonSocial", "nombre_o_razon_social", "nombre");
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
        locate(d, m);
        return m;
    }

    /**
     * Pone en {@code m} el ubigeo (6 dígitos) y los nombres de departamento, provincia y distrito. Primero por los nombres
     * que da el proveedor, buscados en las tablas propias (el nombre que queda es el del catálogo); si no calzan, por el
     * ubigeo del proveedor. Si no se logra ubicar completo, no pone nada (el formulario lo pide a mano).
     */
    private void locate(Map<String, Object> d, Map<String, Object> m) {
        try {
            String dep = Php.toStr(orEmpty(d.get("departamento"))).trim();
            String prov = Php.toStr(orEmpty(d.get("provincia"))).trim();
            String dist = Php.toStr(orEmpty(d.get("distrito"))).trim();
            if (!dep.isEmpty() && !prov.isEmpty() && !dist.isEmpty()) {
                List<Map<String, Object>> regions = jdbc.queryForList("SELECT id, nombre FROM ubigeo_regiones");
                Map<String, Object> region = byName(regions, dep);
                if (region != null) {
                    Map<String, Object> province = byName(jdbc.queryForList(
                            "SELECT id, nombre FROM ubigeo_provincias WHERE region_id = ?", region.get("id")), prov);
                    if (province != null) {
                        Map<String, Object> district = byName(jdbc.queryForList(
                                "SELECT id, nombre FROM ubigeo_distritos WHERE provincia_id = ?", province.get("id")), dist);
                        if (district != null) {
                            put(m, district.get("id"), region.get("nombre"), province.get("nombre"), district.get("nombre"));
                            return;
                        }
                    }
                }
            }
            String code = Php.toStr(orEmpty(d.get("ubigeo"))).trim();
            if (code.matches("\\d{6}")) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT d.nombre AS distrito, p.nombre AS provincia, r.nombre AS departamento FROM ubigeo_distritos d "
                                + "JOIN ubigeo_provincias p ON p.id = d.provincia_id JOIN ubigeo_regiones r ON r.id = p.region_id WHERE d.id = ?", code);
                if (!rows.isEmpty()) {
                    Map<String, Object> r = rows.get(0);
                    put(m, code, r.get("departamento"), r.get("provincia"), r.get("distrito"));
                }
            }
        } catch (RuntimeException e) {
            // La ubicación es una ayuda: si las tablas fallan, la consulta del RUC sigue sin ella.
            log.warn("lookup.ubigeo error={}", e.getClass().getSimpleName());
        }
    }

    private static void put(Map<String, Object> m, Object ubigeo, Object departamento, Object provincia, Object distrito) {
        m.put("ubigeo", Php.toStr(ubigeo));
        m.put("departamento", Php.toStr(departamento));
        m.put("provincia", Php.toStr(provincia));
        m.put("distrito", Php.toStr(distrito));
    }

    private static Map<String, Object> byName(List<Map<String, Object>> rows, String name) {
        String wanted = normalize(name);
        for (Map<String, Object> row : rows) {
            if (normalize(Php.toStr(row.get("nombre"))).equals(wanted)) {
                return row;
            }
        }
        return null;
    }

    /** Sin tildes, en mayúsculas y con los espacios simples: "Ñuñoa" y "NUNOA" calzan. */
    static String normalize(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim();
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

    /**
     * PeruDevs: GET {base}/ruc?document=...&key=... o {base}/dni/complete?...; la respuesta trae {@code estado} y
     * {@code resultado}. apis.net.pe: GET {base}/v1/{ruc|dni}?numero=... con Bearer; devuelve {@code data} si viene envuelto.
     * La URL de PeruDevs lleva la clave: nunca se registra.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path, String numero) {
        try {
            String encoded = URLEncoder.encode(numero, StandardCharsets.UTF_8);
            HttpRequest.Builder builder;
            if (perudevs) {
                String route = "dni".equals(path) ? "/dni/complete" : "/ruc";
                builder = HttpRequest.newBuilder(URI.create(baseUrl + route + "?document=" + encoded + "&key="
                        + URLEncoder.encode(token(), StandardCharsets.UTF_8)));
            } else {
                builder = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/" + path + "?numero=" + encoded))
                        .header("Authorization", "Bearer " + token());
            }
            HttpRequest req = builder.timeout(timeout).header("Accept", "application/json").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("lookup.{} http={}", path, res.statusCode());
                return null;
            }
            Object body = PhpJson.decode(res.body());
            if (!(body instanceof Map<?, ?> m)) {
                return null;
            }
            if (perudevs) {
                Object found = m.get("resultado");
                return Boolean.TRUE.equals(m.get("estado")) && found instanceof Map<?, ?> ? (Map<String, Object>) found : null;
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
