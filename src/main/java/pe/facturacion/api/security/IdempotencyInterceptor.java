package pe.facturacion.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import pe.facturacion.api.modules.idempotency.IdempotencyStore;
import pe.facturacion.api.modules.idempotency.IdempotencyStore.Record;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.crypto.SecretCipher;
import pe.facturacion.api.shared.http.RequestInput;

/**
 * Middleware {@code idempotency} de API-PRO ({@code EnsureIdempotency}).
 * Cabecera {@code Idempotency-Key} en POST/PUT/PATCH/DELETE; huella {@code sha256(METHOD|path|cuerpo)}.
 * La respuesta se guarda en {@link ApiPipelineFilter} al terminar la petición.
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    private static final Set<String> METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final IdempotencyStore store;

    public IdempotencyInterceptor(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String key = request.getHeader("Idempotency-Key");
        Tenant tenant = ApiContext.tenant(request);
        if (!METHODS.contains(request.getMethod()) || key == null || key.isEmpty() || "0".equals(key) || tenant == null) {
            return true;
        }
        key = key.length() > 255 ? key.substring(0, 255) : key;
        String hash = sha256(request.getMethod() + "|" + ApiContext.laravelPath(request) + "|" + rawContent(request));

        Record existing = store.find(tenant.getId(), key);
        if (existing != null) {
            if (!SecretCipher.constantTimeEquals(existing.requestHash(), hash)) {
                ApiContext.writeJson(response, 422, ApiContext.error("La Idempotency-Key ya se usó con un cuerpo distinto."));
                return false;
            }
            if (existing.responseStatus() != null) {
                byte[] body = existing.responseBody() == null ? new byte[0] : existing.responseBody().getBytes(StandardCharsets.UTF_8);
                response.setStatus(existing.responseStatus());
                response.setContentType("application/json");
                response.setHeader("Idempotency-Replayed", "true");
                response.setContentLength(body.length);
                response.getOutputStream().write(body);
                request.setAttribute(ApiContext.IDEMPOTENCY_REPLAYED, Boolean.TRUE);
                return false;
            }
            ApiContext.writeJson(response, 409, ApiContext.error("Ya hay una solicitud en proceso con esa Idempotency-Key."));
            return false;
        }

        if (!store.reserve(tenant.getId(), key, hash)) {
            ApiContext.writeJson(response, 409, ApiContext.error("Ya hay una solicitud en proceso con esa Idempotency-Key."));
            return false;
        }
        request.setAttribute(ApiContext.IDEMPOTENCY_KEY, key);
        return true;
    }

    /** {@code $request->getContent()}: en multipart PHP no expone el cuerpo crudo (cadena vacía). */
    private static String rawContent(HttpServletRequest request) {
        String ct = request.getContentType() == null ? "" : request.getContentType().toLowerCase();
        if (ct.startsWith("multipart/")) {
            return "";
        }
        return RequestInput.rawBody(request);
    }

    static String sha256(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
