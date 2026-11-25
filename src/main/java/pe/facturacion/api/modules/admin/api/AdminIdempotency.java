package pe.facturacion.api.modules.admin.api;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.idempotency.IdempotencyStore;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.crypto.SecretCipher;
import pe.facturacion.api.shared.http.RequestInput;
import pe.facturacion.api.shared.json.PhpJson;

/**
 * Idempotencia de las emisiones desde el panel (fase D, §11.4 del diseño). La cabecera {@code Idempotency-Key} es
 * <b>obligatoria</b> (a diferencia de {@code /api/v1}, donde es opcional): un doble clic o un reintento tras un corte de
 * red nunca emite dos comprobantes.
 *
 * <ul>
 *   <li>Misma tabla {@code idempotency_keys} y misma caducidad que {@code /api/v1}, con la empresa de la ruta y la clave
 *       con el prefijo {@value #PREFIX} (no choca con las claves que usa la propia empresa).</li>
 *   <li>Huella {@code sha256("panel|" + método + "|" + ruta + "|" + cuerpo)}: la misma clave con otro cuerpo (u otra
 *       ruta) se rechaza.</li>
 *   <li>Solo se guarda una respuesta correcta ({@code succeeded: true}); un error (validación, cupo del plan, 404…)
 *       libera la clave para poder corregir y reintentar con la misma.</li>
 *   <li>Repetir una petición ya resuelta devuelve la misma respuesta con {@code Idempotency-Replayed: true} (sin volver
 *       a contar en los límites ni a auditar); mientras la primera sigue en curso responde 409.</li>
 *   <li>Nunca se usa con respuestas que llevan secretos (alta de empresa, regenerar credenciales): hallazgo H10.</li>
 * </ul>
 */
@Component
public class AdminIdempotency {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED = "Idempotency-Replayed";
    static final String PREFIX = "panel:";
    static final int MIN_LENGTH = 8;
    static final int MAX_LENGTH = 100;
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]+$");

    static final String MISSING = "La cabecera Idempotency-Key es obligatoria para emitir desde el panel (use un UUID por intento de emisión).";
    static final String INVALID = "La Idempotency-Key debe tener de " + MIN_LENGTH + " a " + MAX_LENGTH
            + " caracteres: letras, números, punto, guion, guion bajo o dos puntos (p. ej. un UUID).";
    static final String MISMATCH = "La Idempotency-Key ya se usó con otra petición. Genere una clave nueva para cada comprobante.";
    static final String IN_PROGRESS = "Ya hay una emisión en curso con esa Idempotency-Key. Espere su respuesta.";

    private final IdempotencyStore store;

    public AdminIdempotency(IdempotencyStore store) {
        this.store = store;
    }

    /** Mensaje de error de la cabecera, o null si es válida. */
    public static String keyError(String key) {
        if (key == null || key.isBlank()) {
            return MISSING;
        }
        String k = key.trim();
        if (k.length() < MIN_LENGTH || k.length() > MAX_LENGTH || !KEY.matcher(k).matches()) {
            return INVALID;
        }
        return null;
    }

    /** Huella de la petición (método, ruta y cuerpo crudo). */
    static String fingerprint(String method, String path, String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(("panel|" + method + "|" + path + "|" + body).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Clave de la petición (ya validada) tal como se guarda. */
    public static String storedKey(HttpServletRequest request) {
        return PREFIX + request.getHeader(HEADER).trim();
    }

    /**
     * Ejecuta {@code action} una sola vez por clave y empresa. La validación de la cabecera va antes que todo (sin
     * clave no se consume ningún límite).
     */
    public ResponseEntity<Object> run(HttpServletRequest request, Tenant tenant, Supplier<ResponseEntity<Object>> action) {
        String error = keyError(request.getHeader(HEADER));
        if (error != null) {
            throw new AdminException(200, List.of(error), List.of(AdminResponse.fieldError(HEADER, error)));
        }
        String key = storedKey(request);
        String hash = fingerprint(request.getMethod(), request.getRequestURI(), RequestInput.rawBody(request));
        IdempotencyStore.Record existing = store.find(tenant.getId(), key);
        if (existing != null) {
            if (!SecretCipher.constantTimeEquals(existing.requestHash(), hash)) {
                throw AdminException.fail(MISMATCH);
            }
            if (existing.responseStatus() != null) {
                Map<String, Object> body = PhpJson.decodeObject(existing.responseBody());
                return ResponseEntity.status(existing.responseStatus()).header(REPLAYED, "true").body(body);
            }
            throw AdminException.status(409, IN_PROGRESS);
        }
        if (!store.reserve(tenant.getId(), key, hash)) {
            throw AdminException.status(409, IN_PROGRESS);
        }
        ResponseEntity<Object> response;
        try {
            response = action.get();
        } catch (RuntimeException e) {
            store.release(tenant.getId(), key);
            throw e;
        }
        if (succeeded(response)) {
            store.complete(tenant.getId(), key, response.getStatusCode().value(), PhpJson.encode(response.getBody()));
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).header(REPLAYED, "false")
                    .body(response.getBody());
        }
        store.release(tenant.getId(), key);
        return response;
    }

    static boolean succeeded(ResponseEntity<Object> response) {
        return response.getStatusCode().is2xxSuccessful() && response.getBody() instanceof Map<?, ?> m
                && Boolean.TRUE.equals(m.get("succeeded"));
    }
}
