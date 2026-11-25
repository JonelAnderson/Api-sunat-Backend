package pe.facturacion.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.api.ApiException;
import pe.facturacion.api.shared.json.PhpJson;

/** Datos de la petición en curso compartidos por interceptores, filtro y controladores. */
public final class ApiContext {

    static final String TENANT = ApiContext.class.getName() + ".tenant";
    static final String IDEMPOTENCY_KEY = ApiContext.class.getName() + ".idempotencyKey";
    static final String IDEMPOTENCY_REPLAYED = ApiContext.class.getName() + ".idempotencyReplayed";
    static final String LOG_START = ApiContext.class.getName() + ".logStart";
    static final String LOG_INPUT = ApiContext.class.getName() + ".logInput";
    static final String USAGE_HEADERS = ApiContext.class.getName() + ".usageHeaders";

    private ApiContext() {
    }

    /** Empresa autenticada por {@code X-Api-Key}/{@code X-Api-Secret} (o null en rutas públicas). */
    public static Tenant tenant(HttpServletRequest request) {
        return (Tenant) request.getAttribute(TENANT);
    }

    public static Tenant requireTenant(HttpServletRequest request) {
        Tenant t = tenant(request);
        if (t == null) {
            throw ApiException.of(401, "No autenticado.");
        }
        return t;
    }

    static void setTenant(HttpServletRequest request, Tenant tenant) {
        request.setAttribute(TENANT, tenant);
    }

    /** {@code $request->path()} de Laravel: sin barra inicial ni query (p. ej. {@code api/v1/facturas}). */
    public static String laravelPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        String trimmed = uri.replaceAll("^/+|/+$", "");
        return trimmed.isEmpty() ? "/" : trimmed;
    }

    /** Escribe una respuesta JSON con el formato de API-PRO desde un interceptor. */
    public static void writeJson(HttpServletResponse response, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = PhpJson.encode(body).getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType("application/json");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    public static Map<String, Object> error(String mensaje) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "error");
        body.put("mensaje", mensaje);
        return body;
    }
}
