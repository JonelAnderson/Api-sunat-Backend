package pe.facturacion.api.shared.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error de negocio que se traduce a una respuesta {@code {"estado":"error",...}}
 * con el cuerpo exacto que defina quien la lanza.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final Map<String, Object> body;

    public ApiException(int status, Map<String, Object> body) {
        super(String.valueOf(body.get("mensaje")));
        this.status = status;
        this.body = body;
    }

    public static ApiException of(int status, String mensaje) {
        return new ApiException(status, ApiResponse.errorBody(mensaje, null));
    }

    public static ApiException of(int status, String mensaje, Object errores) {
        return new ApiException(status, ApiResponse.errorBody(mensaje, errores));
    }

    /** {@code errorAccionable()} de API-PRO. */
    public static ApiException accionable(int status, String mensaje, String codigoError, Map<String, Object> siguienteAccion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "error");
        body.put("mensaje", mensaje);
        body.put("codigo_error", codigoError);
        if (siguienteAccion != null) {
            body.put("siguiente_accion", siguienteAccion);
        }
        return new ApiException(status, body);
    }

    /** 404 {@code "Recurso no encontrado."} (ModelNotFoundException en API-PRO). */
    public static ApiException notFound() {
        return of(404, "Recurso no encontrado.");
    }

    public int getStatus() {
        return status;
    }

    public Map<String, Object> getBody() {
        return body;
    }
}
