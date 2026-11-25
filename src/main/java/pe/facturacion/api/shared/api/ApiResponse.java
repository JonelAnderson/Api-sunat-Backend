package pe.facturacion.api.shared.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;

/**
 * Envoltorio de respuestas idéntico al trait {@code App\Http\Traits\ApiResponse} de API-PRO.
 *
 * <pre>
 * éxito: {"estado":"exito","mensaje":"OK","datos":...,("meta":...)}
 * error: {"estado":"error","mensaje":"...",("errores":...)}
 * </pre>
 */
public final class ApiResponse {

    private ApiResponse() {
    }

    public static ResponseEntity<Object> success(Object datos) {
        return success(datos, "OK", 200, null);
    }

    public static ResponseEntity<Object> success(Object datos, String mensaje) {
        return success(datos, mensaje, 200, null);
    }

    public static ResponseEntity<Object> success(Object datos, String mensaje, int codigo, Object meta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "exito");
        body.put("mensaje", mensaje);
        body.put("datos", datos);
        if (meta != null) {
            body.put("meta", meta);
        }
        return ResponseEntity.status(codigo).body(body);
    }

    /** {@code created()} de API-PRO: 201 con mensaje "Creado exitosamente" por defecto. */
    public static ResponseEntity<Object> created(Object datos) {
        return success(datos, "Creado exitosamente", 201, null);
    }

    public static ResponseEntity<Object> created(Object datos, String mensaje) {
        return success(datos, mensaje, 201, null);
    }

    public static ResponseEntity<Object> error(String mensaje, int codigo) {
        return error(mensaje, codigo, null);
    }

    /** {@code error()} de API-PRO: {@code errores} solo se incluye si no está vacío. */
    public static ResponseEntity<Object> error(String mensaje, int codigo, Object errores) {
        return ResponseEntity.status(codigo).body(errorBody(mensaje, errores));
    }

    public static Map<String, Object> errorBody(String mensaje, Object errores) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "error");
        body.put("mensaje", mensaje);
        if (isTruthy(errores)) {
            body.put("errores", errores);
        }
        return body;
    }

    /** {@code errorAccionable()} de API-PRO (por defecto 422). */
    public static ResponseEntity<Object> errorAccionable(String mensaje, String codigoError, Map<String, Object> siguienteAccion, int codigo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "error");
        body.put("mensaje", mensaje);
        body.put("codigo_error", codigoError);
        if (siguienteAccion != null) {
            body.put("siguiente_accion", siguienteAccion);
        }
        return ResponseEntity.status(codigo).body(body);
    }

    /** Valor "truthy" al estilo PHP: null, false, "", "0", 0 y colecciones vacías son falsos. */
    public static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return !s.isEmpty() && !"0".equals(s);
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (value instanceof java.util.Collection<?> c) {
            return !c.isEmpty();
        }
        return true;
    }
}
