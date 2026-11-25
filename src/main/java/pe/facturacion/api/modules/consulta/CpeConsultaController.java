package pe.facturacion.api.modules.consulta;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.php.PhpMath;
import pe.facturacion.api.shared.validation.Validator;

/**
 * {@code GET /api/v1/consultar-cpe} ({@code CpeConsultaController} + {@code SunatCpeConsultaService}):
 * Consulta Integrada de Comprobantes de SUNAT por API REST, con el {@code client_id}/{@code client_secret}
 * de la empresa (Clave SOL → Consulta Integrada CPE → API; solo producción). El token se guarda en
 * Redis hasta un minuto antes de vencer.
 *
 * <p>Correcciones: los errores no exponen la URL de SUNAT (que lleva el client_id) y, si SUNAT
 * rechaza un token guardado, se pide uno nuevo y se reintenta una vez.
 */
@RestController
@Tag(name = "Consultas")
public class CpeConsultaController {

    private static final Logger log = LoggerFactory.getLogger(CpeConsultaController.class);
    static final String SCOPE = "https://api.sunat.gob.pe/v1/contribuyente/contribuyentes";
    private static final Map<String, String> ESTADO_CP = Map.of("0", "NO EXISTE", "1", "ACEPTADO", "2", "ANULADO", "3", "AUTORIZADO", "4", "NO AUTORIZADO");
    private static final Map<String, String> ESTADO_RUC = Map.of("00", "ACTIVO", "01", "BAJA PROVISIONAL", "02", "BAJA PROV. POR OFICIO",
            "03", "SUSPENSION TEMPORAL", "10", "BAJA DEFINITIVA", "11", "BAJA DE OFICIO", "22", "INHABILITADO-VENT.UNICA");
    private static final Map<String, String> COND_DOMI = Map.of("00", "HABIDO", "09", "PENDIENTE", "11", "POR VERIFICAR", "12", "NO HABIDO",
            "20", "NO HALLADO");

    private final StringRedisTemplate redis;
    private final String authUrl;
    private final String apiUrl;
    private final Duration timeout;
    private final HttpClient http;

    public CpeConsultaController(StringRedisTemplate redis, @Value("${app.sunat.cpe.auth-url:https://api-seguridad.sunat.gob.pe/v1}") String authUrl,
            @Value("${app.sunat.cpe.api-url:https://api.sunat.gob.pe/v1}") String apiUrl, @Value("${app.sunat.http-timeout:60}") int timeoutSeconds) {
        this.redis = redis;
        this.authUrl = authUrl.replaceAll("/+$", "");
        this.apiUrl = apiUrl.replaceAll("/+$", "");
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    /** Respuesta de error de SUNAT (incluye el cuerpo para reconocer el tipo de rechazo). */
    static final class SunatError extends RuntimeException {
        final int status;
        final String body;

        SunatError(int status, String body) {
            super("HTTP " + status);
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    @Operation(summary = "Consultar en SUNAT el estado de un comprobante (tipo_doc, serie, correlativo, fecha_emision dd/mm/aaaa, monto)")
    @GetMapping("/api/v1/consultar-cpe")
    public ResponseEntity<Object> consultar(HttpServletRequest req) {
        return consultar(ApiContext.requireTenant(req), Input.of(req));
    }

    /**
     * Consulta de la empresa indicada con sus credenciales guardadas (la usa también el panel de administración, que
     * nunca ve el client_id ni el client_secret): mismas reglas, mismo token en Redis y mismos tiempos de espera.
     */
    public ResponseEntity<Object> consultar(Tenant t, Input in) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("ruc_emisor", "nullable|string|size:11");
        rules.put("tipo_doc", "required|string|in:01,03,04,07,08,R1,R7");
        rules.put("serie", "required|string|max:4");
        rules.put("correlativo", "required|integer|min:1");
        rules.put("fecha_emision", List.of("required", "string", "regex:/^\\d{2}\\/\\d{2}\\/\\d{4}$/"));
        rules.put("monto", "required|numeric|min:0");
        Map<String, Object> v = Validator.make(in.all(), rules).validate();
        Object rucEmisor = v.get("ruc_emisor") != null ? v.get("ruc_emisor") : t.getRuc();
        String clientId = t.getClientId();
        String secret = t.getClientSecret() == null ? null : t.getClientSecret().reveal();
        if (Php.empty(clientId) || Php.empty(secret)) {
            return ApiResponse.error("El tenant no tiene configurado client_id/client_secret para consultar SUNAT.", 422);
        }
        Map<String, Object> filtro = new LinkedHashMap<>();
        filtro.put("numRuc", Php.toStr(rucEmisor));
        filtro.put("codComp", Php.toStr(v.get("tipo_doc")));
        filtro.put("numeroSerie", Php.toStr(v.get("serie")));
        filtro.put("numero", Php.toStr(v.get("correlativo")));
        filtro.put("fechaEmision", Php.toStr(v.get("fecha_emision")));
        filtro.put("monto", PhpMath.numberFormat(Php.toFloat(v.get("monto")), 2));
        try {
            Map<String, Object> result;
            try {
                result = consultarCpe(t, token(t, clientId, secret, false), filtro);
            } catch (SunatError e) {
                if (e.status != 401) {
                    throw e;
                }
                result = consultarCpe(t, token(t, clientId, secret, true), filtro);
            }
            result.put("consultado_por_ruc", t.getRuc());
            result.put("ruc_emisor", rucEmisor);
            result.put("tipo_doc", v.get("tipo_doc"));
            result.put("serie", v.get("serie"));
            result.put("correlativo", v.get("correlativo"));
            return ApiResponse.success(result);
        } catch (SunatError e) {
            String b = e.body.toLowerCase(java.util.Locale.ROOT);
            if (b.contains("unauthorized_client") || b.contains("cliente no autorizado")) {
                return ApiResponse.error("SUNAT rechazó las credenciales (client_id/client_secret). Verifica que las generaste desde Clave SOL → Empresas → "
                        + "Comprobantes de pago → Consulta Integrada CPE → API. No hay entorno beta, solo producción.", 401);
            }
            if (b.contains("invalid_client")) {
                return ApiResponse.error("El client_id o client_secret es inválido. Regenera las credenciales en Clave SOL.", 401);
            }
            log.warn("cpe.consulta.error http={}", e.status);
            return ApiResponse.error("Error al consultar CPE en SUNAT: HTTP " + e.status, 500);
        } catch (RuntimeException e) {
            log.warn("cpe.consulta.error tipo={}", e.getClass().getSimpleName());
            return ApiResponse.error("Error al consultar CPE en SUNAT: " + e.getClass().getSimpleName(), 500);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> consultarCpe(Tenant t, String token, Map<String, Object> filtro) {
        Map<String, Object> r = (Map<String, Object>) send(HttpRequest.newBuilder(URI.create(apiUrl + "/contribuyente/contribuyentes/" + t.getRuc()
                + "/validarcomprobante")).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(PhpJson.encode(filtro))));
        Map<String, Object> out = new LinkedHashMap<>();
        if (!Php.truthy(r.get("success"))) {
            out.put("encontrado", false);
            out.put("mensaje", r.get("message"));
            return out;
        }
        Map<String, Object> d = r.get("data") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Object cp = d.get("estadoCp");
        Object ruc = d.get("estadoRuc");
        Object domi = d.get("condDomiRuc");
        out.put("encontrado", true);
        out.put("estado_cp", cp);
        out.put("estado_cp_descripcion", ESTADO_CP.getOrDefault(Php.toStr(cp), "DESCONOCIDO"));
        out.put("estado_ruc", ruc);
        out.put("estado_ruc_descripcion", ESTADO_RUC.getOrDefault(Php.toStr(ruc), "DESCONOCIDO"));
        out.put("cond_domi_ruc", domi);
        out.put("cond_domi_ruc_descripcion", COND_DOMI.getOrDefault(Php.toStr(domi), "DESCONOCIDO"));
        out.put("observaciones", d.get("observaciones") == null ? List.of() : d.get("observaciones"));
        return out;
    }

    /** Token OAuth2 (client_credentials) guardado en Redis hasta 60 s antes de vencer (mínimo 60 s). */
    @SuppressWarnings("unchecked")
    private String token(Tenant t, String clientId, String secret, boolean renovar) {
        String key = "sunat_cpe_token:" + t.getId();
        if (!renovar) {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return cached;
            }
        }
        String form = "grant_type=client_credentials&scope=" + enc(SCOPE) + "&client_id=" + enc(clientId) + "&client_secret=" + enc(secret);
        Map<String, Object> r = (Map<String, Object>) send(HttpRequest.newBuilder(URI.create(authUrl + "/clientesextranet/" + enc(clientId) + "/oauth2/token/"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
        String token = Php.toStr(r.get("access_token"));
        long ttl = Math.max(60, Php.toInt(r.get("expires_in")) - 60);
        redis.opsForValue().set(key, token, Duration.ofSeconds(ttl));
        return token;
    }

    private Object send(HttpRequest.Builder b) {
        try {
            HttpResponse<String> res = http.send(b.timeout(timeout).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() > 299) {
                throw new SunatError(res.statusCode(), res.body());
            }
            Object body = PhpJson.decode(res.body());
            return body instanceof Map<?, ?> ? body : new LinkedHashMap<>();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Consulta interrumpida");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Sin conexión con SUNAT", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
