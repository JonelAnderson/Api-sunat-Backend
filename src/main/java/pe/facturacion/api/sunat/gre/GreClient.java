package pe.facturacion.api.sunat.gre;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import pe.facturacion.api.config.AppProperties;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.crypto.EncryptedString;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.sunat.SunatSender;
import pe.facturacion.api.sunat.ws.CdrReader;
import pe.facturacion.api.sunat.ws.SunatZip;

/**
 * API REST de la GRE (guías de remisión 2022), equivalente a {@code Greenter\Api} +
 * {@code GreSender} + el manejo de token de {@code GreenterService}:
 *
 * <ul>
 *   <li>Token OAuth2 ({@code grant_type=password}, usuario = RUC + usuario SOL) cacheado en Redis
 *       por empresa y client_id; se reutiliza mientras le queden más de 10 minutos.</li>
 *   <li>Envío: ZIP en base64 con su SHA-256 → {@code numTicket}.</li>
 *   <li>Consulta: {@code codRespuesta} 98 = en proceso; 0/99 = procesado (CDR si
 *       {@code indCdrGenerado = 1}).</li>
 *   <li>Si el proveedor responde "Token NO existe" se descarta el token y se reintenta una vez.</li>
 * </ul>
 *
 * <p>Credenciales: client_id/secret de la empresa → los del panel (sire_client_*) → los del
 * entorno. Nunca se registran el token, el secreto ni la clave SOL.
 */
@Service
public class GreClient {

    private static final Logger log = LoggerFactory.getLogger(GreClient.class);
    static final String SCOPE = "https://api-cpe.sunat.gob.pe";

    /** Resultado del envío: ticket o error ({@code code} = cod de SUNAT, "API", "HTTP"...). */
    public record SendResult(boolean success, String ticket, String errorCode, String errorMessage) {
    }

    /** Resultado de la consulta del ticket, con las claves de {@code getGreStatus()}. */
    public record StatusResult(boolean success, byte[] cdrZip, String code, String description, List<String> notes,
            boolean accepted, String errorCode, String errorMessage) {

        static StatusResult error(String code, String message) {
            return new StatusResult(false, null, null, null, List.of(), false, code, message);
        }
    }

    /** Respuesta HTTP no exitosa del proveedor (equivale a {@code ApiException}). */
    static final class ApiError extends Exception {
        final int status;
        final String body;

        ApiError(int status, String body) {
            super("[" + status + "] " + (body == null ? "" : body));
            this.status = status;
            this.body = body == null ? "" : body;
        }

        boolean missingToken() {
            return status == 401 && body.contains("Token NO existe");
        }
    }

    private final AppProperties props;
    private final StringRedisTemplate redis;
    private final HttpClient http;
    private final int timeout;

    public GreClient(AppProperties props, StringRedisTemplate redis) {
        this.props = props;
        this.redis = redis;
        this.timeout = props.sunat() == null || props.sunat().httpTimeout() <= 0 ? 60 : props.sunat().httpTimeout();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(timeout, 30))).build();
    }

    // ----------------------------------------------------------------- envío

    public SendResult send(Tenant tenant, String name, String signedXml) {
        byte[] zip = SunatZip.compress(name + ".xml", signedXml.getBytes(StandardCharsets.UTF_8), Clock.nowLima());
        Map<String, Object> archivo = new LinkedHashMap<>();
        archivo.put("nomArchivo", name + ".zip");
        archivo.put("arcGreZip", Base64.getEncoder().encodeToString(zip));
        archivo.put("hashZip", sha256(zip));
        String body = PhpJson.encodeUnescaped(Map.of("archivo", archivo));
        try {
            Map<String, Object> res = withToken(tenant, token -> request(tenant, "POST",
                    "/contribuyente/gem/comprobantes/" + name, token, body));
            Object ticket = res.get("numTicket");
            return new SendResult(true, ticket == null ? null : Php.toStr(ticket), null, null);
        } catch (ApiError e) {
            String[] err = processError(e);
            return new SendResult(false, null, err[0], err[1]);
        } catch (IOException e) {
            return new SendResult(false, null, "HTTP", "Error de conexión con la API GRE: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------- consulta

    public StatusResult status(Tenant tenant, String ticket) {
        Map<String, Object> res;
        try {
            res = withToken(tenant, token -> request(tenant, "GET", "/contribuyente/gem/comprobantes/envios/" + ticket, token, null));
        } catch (ApiError e) {
            String[] err = processError(e);
            return StatusResult.error(err[0], err[1]);
        } catch (IOException e) {
            return StatusResult.error("HTTP", "Error de conexión con la API GRE: " + e.getMessage());
        }
        String code = res.get("codRespuesta") == null ? "" : Php.toStr(res.get("codRespuesta"));
        if ("98".equals(code)) {
            return StatusResult.error(code, "En proceso");
        }
        String errorCode = null;
        String errorMessage = null;
        if (res.get("error") instanceof Map<?, ?> err) {
            errorCode = err.get("numError") == null ? null : Php.toStr(err.get("numError"));
            errorMessage = err.get("desError") == null ? null : Php.toStr(err.get("desError"));
        }
        if (("0".equals(code) || "99".equals(code)) && "1".equals(Php.toStr(res.get("indCdrGenerado"))) && res.get("arcCdr") != null) {
            byte[] cdrZip = Base64.getMimeDecoder().decode(Php.toStr(res.get("arcCdr")));
            CdrReader.Cdr cdr = CdrReader.fromZip(cdrZip);
            return new StatusResult(true, cdrZip, cdr.code() == null ? null : cdr.code().trim(), cdr.description(), cdr.notes(),
                    cdr.accepted(), null, null);
        }
        // Corrección: sin CDR y sin error API-PRO fallaba con un error interno; aquí queda como error con el código
        return StatusResult.error(errorCode != null ? errorCode : code,
                errorMessage != null ? errorMessage : "SUNAT no generó CDR (codRespuesta " + code + ").");
    }

    // ------------------------------------------------------------ transporte

    @FunctionalInterface
    interface Call {
        Map<String, Object> run(String token) throws ApiError, IOException;
    }

    /** Ejecuta con token; ante "Token NO existe" descarta el token y reintenta una vez. */
    private Map<String, Object> withToken(Tenant tenant, Call call) throws ApiError, IOException {
        String token = token(tenant);
        try {
            return call.run(token);
        } catch (ApiError e) {
            if (!e.missingToken()) {
                throw e;
            }
            forgetToken(tenant);
            return call.run(token(tenant));
        }
    }

    private Map<String, Object> request(Tenant tenant, String method, String path, String token, String body) throws ApiError, IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoints(tenant).greCpe() + path))
                .timeout(Duration.ofSeconds(timeout))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return exchange(b.build());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchange(HttpRequest req) throws ApiError, IOException {
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrumpido", e);
        }
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new ApiError(res.statusCode(), res.body());
        }
        Object decoded = res.body() == null || res.body().isBlank() ? null : PhpJson.decode(res.body());
        return decoded instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    /** {@code GreSender::processException()}: 422 → primer error; 500 → {cod, msg}; otro → "API". */
    static String[] processError(ApiError e) {
        Object body;
        try {
            body = e.body.isBlank() ? null : PhpJson.decode(e.body);
        } catch (RuntimeException ex) {
            body = null;
        }
        if (e.status == 422 && body instanceof Map<?, ?> m && m.get("errors") instanceof List<?> errors && !errors.isEmpty()
                && errors.get(0) instanceof Map<?, ?> first) {
            return new String[] {Php.toStr(first.get("cod")), Php.toStr(first.get("msg"))};
        }
        if (e.status == 500 && body instanceof Map<?, ?> m) {
            return new String[] {Php.toStr(m.get("cod")), Php.toStr(m.get("msg"))};
        }
        return new String[] {"API", e.getMessage()};
    }

    // ----------------------------------------------------------------- token

    private String token(Tenant tenant) throws ApiError, IOException {
        String[] cred = credentials(tenant);
        String key = tokenKey(tenant, cred[0]);
        String cached = redis.opsForValue().get(key);
        Long ttl = cached == null ? null : redis.getExpire(key, TimeUnit.SECONDS);
        if (cached != null && ttl != null && ttl > 600) {
            return cached;
        }
        StringJoiner form = new StringJoiner("&");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "password");
        fields.put("scope", SCOPE);
        fields.put("client_id", cred[0]);
        fields.put("client_secret", cred[1]);
        fields.put("username", tenant.getRuc() + reveal(tenant.getSolUser()));
        fields.put("password", reveal(tenant.getSolPass()));
        fields.forEach((k, v) -> form.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoints(tenant).greAuth() + "/clientessol/"
                        + URLEncoder.encode(cred[0], StandardCharsets.UTF_8) + "/oauth2/token/"))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();
        Map<String, Object> res = exchange(req);
        Object token = res.get("access_token");
        if (Php.empty(token)) {
            throw new ApiError(401, "Cliente No autorizado");
        }
        long expires = res.get("expires_in") == null ? 3600 : Php.toInt(res.get("expires_in"));
        if (expires > 0) {
            redis.opsForValue().set(key, Php.toStr(token), Duration.ofSeconds(expires));
        }
        log.info("gre.token.nuevo ruc={} entorno={} expira_en={}s", tenant.getRuc(), tenant.getEnvironment(), expires);
        return Php.toStr(token);
    }

    private void forgetToken(Tenant tenant) {
        redis.delete(tokenKey(tenant, credentials(tenant)[0]));
    }

    /** Espacio del token: entorno, endpoints, RUC y usuario SOL (como {@code greTokenNamespace()}), más el client_id. */
    private String tokenKey(Tenant tenant, String clientId) {
        AppProperties.Endpoints e = endpoints(tenant);
        String ns = String.join("|", Php.toStr(tenant.getEnvironment()), e.greAuth(), e.greCpe(), tenant.getRuc(),
                reveal(tenant.getSolUser()), clientId);
        return "gre-token:" + sha256(ns.getBytes(StandardCharsets.UTF_8));
    }

    /** client_id/secret de la empresa → los del panel → los del entorno. */
    String[] credentials(Tenant tenant) {
        AppProperties.Endpoints e = endpoints(tenant);
        String id = firstNonEmpty(tenant.getClientId(), tenant.getSireClientId(), e.greClientId());
        String secret = firstNonEmpty(reveal(tenant.getClientSecret()), reveal(tenant.getSireClientSecret()), e.greClientSecret());
        if (id == null || secret == null) {
            throw new SunatSender.ConfigurationException("Credenciales GRE (client_id/client_secret) no configuradas. "
                    + "Regístralas en la empresa o en SUNAT_BETA_GRE_CLIENT_ID / SUNAT_BETA_GRE_CLIENT_SECRET (SUNAT_PROD_* en producción).");
        }
        return new String[] {id, secret};
    }

    private AppProperties.Endpoints endpoints(Tenant tenant) {
        return props.sunat().forEnvironment(tenant.getEnvironment());
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private static String reveal(EncryptedString s) {
        return s == null || s.reveal() == null ? "" : s.reveal();
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
