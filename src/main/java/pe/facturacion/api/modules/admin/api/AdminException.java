package pe.facturacion.api.modules.admin.api;

import java.util.List;
import java.util.Map;

/**
 * Error del panel que {@link AdminExceptionHandler} convierte al sobre {@code {succeeded:false, ...}}.
 * Los errores de negocio y validación van con HTTP 200 (como la consola); 401 / 403 / 404 / 429 / 503 con su código.
 */
public class AdminException extends RuntimeException {

    private final int status;
    private final List<String> messages;
    private final List<Map<String, Object>> errors;

    public AdminException(int status, List<String> messages, List<Map<String, Object>> errors) {
        super(messages.isEmpty() ? "" : messages.get(0));
        this.status = status;
        this.messages = List.copyOf(messages);
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /** Error de negocio (HTTP 200, {@code succeeded:false}). */
    public static AdminException fail(String message) {
        return new AdminException(200, List.of(message), List.of());
    }

    public static AdminException status(int status, String message) {
        return new AdminException(status, List.of(message), List.of());
    }

    public int status() {
        return status;
    }

    public List<String> messages() {
        return messages;
    }

    public List<Map<String, Object>> errors() {
        return errors;
    }
}
