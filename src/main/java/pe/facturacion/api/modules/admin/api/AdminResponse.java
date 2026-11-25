package pe.facturacion.api.modules.admin.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import pe.facturacion.api.shared.json.PhpJson;

/**
 * Sobre de las respuestas del panel, el mismo de la consola / ERP (camelCase):
 * <pre>{ "succeeded": true, "messages": [], "data": …, "totalRecords": 0, "errors": [] }</pre>
 * Un error de negocio o de validación responde HTTP 200 con {@code succeeded: false}; 401 / 403 / 429 / 503 usan el
 * mismo sobre. Nada de esto afecta al formato Laravel de {@code /api/v1}.
 */
public final class AdminResponse {

    private AdminResponse() {
    }

    public static Map<String, Object> body(boolean succeeded, List<String> messages, Object data, long totalRecords,
            List<Map<String, Object>> errors) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("succeeded", succeeded);
        m.put("messages", messages == null ? List.of() : messages);
        m.put("data", data);
        m.put("totalRecords", totalRecords);
        m.put("errors", errors == null ? List.of() : errors);
        return m;
    }

    public static ResponseEntity<Object> ok(Object data) {
        return ResponseEntity.ok(body(true, List.of(), data, 0, List.of()));
    }

    public static ResponseEntity<Object> ok(Object data, String message) {
        return ResponseEntity.ok(body(true, List.of(message), data, 0, List.of()));
    }

    /** Listado paginado: {@code totalRecords} lleva el total sin paginar. */
    public static ResponseEntity<Object> page(List<?> rows, long total) {
        return ResponseEntity.ok(body(true, List.of(), rows, total, List.of()));
    }

    /** Error de negocio: HTTP 200 con {@code succeeded: false}. */
    public static ResponseEntity<Object> fail(String message) {
        return ResponseEntity.ok(body(false, List.of(message), null, 0, List.of()));
    }

    public static ResponseEntity<Object> status(int status, String message) {
        return ResponseEntity.status(status).body(body(false, List.of(message), null, 0, List.of()));
    }

    /** Escribe el sobre desde un interceptor (401, 403, 429, 503). */
    public static void write(HttpServletResponse response, int status, String message) throws IOException {
        byte[] bytes = PhpJson.encode(body(false, List.of(message), null, 0, List.of())).getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    /** Error de validación de un campo. */
    public static Map<String, Object> fieldError(String propertyName, String errorMessage) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("propertyName", propertyName);
        m.put("errorMessage", errorMessage);
        return m;
    }
}
