package pe.facturacion.api.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import pe.facturacion.api.config.AppProperties;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.shared.validation.ValidationException;

/**
 * Manejador global equivalente a {@code withExceptions()} de {@code bootstrap/app.php} en API-PRO.
 * Todos los errores salen como {@code {"estado":"error","mensaje":...}}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AppProperties props;

    public GlobalExceptionHandler(AppProperties props) {
        this.props = props;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(e.getBody());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Object> handleValidation(ValidationException e) {
        return ApiResponse.error("Error de validación", 422, e.getErrors());
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Object> handleNotFound(Exception e, HttpServletRequest request) {
        if (isAdmin(request)) {
            return AdminResponse.status(404, "Recurso no encontrado.");
        }
        return ApiResponse.error("Recurso no encontrado.", 404);
    }

    /**
     * Rutas del panel de administración ({@code /api/admin/**}): sus errores sin controlador (404, 405) van con el sobre
     * camelCase del panel; el resto de la API sigue igual.
     */
    private static boolean isAdmin(HttpServletRequest request) {
        return request != null && request.getRequestURI() != null && request.getRequestURI().startsWith("/api/admin/");
    }

    /**
     * API-PRO responde 500 (TypeError de PHP) ante un {id} no numérico; se corrige a 404
     * (ver docs/DIFERENCIAS.md).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ApiResponse.error("Recurso no encontrado.", 404);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        if (isAdmin(request)) {
            return AdminResponse.status(405, "Método HTTP no permitido para esta ruta.");
        }
        return ApiResponse.error("Método HTTP no permitido para esta ruta.", 405);
    }

    /** PostTooLargeException de Laravel: HttpException 413 sin mensaje → "Error.". */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Object> handlePayloadTooLarge(MaxUploadSizeExceededException e) {
        return ApiResponse.error("Error.", 413);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Object> handleUnexpected(Throwable e, HttpServletRequest request) {
        String ref = UUID.randomUUID().toString();
        log.error("Error no controlado en {} {} (error_id={})", request.getMethod(), request.getRequestURI(), ref, e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "error");
        body.put("mensaje", "Error interno del servidor.");
        // Como APP_DEBUG de Laravel, pero nunca en producción (aunque alguien lo deje activado)
        if (props.debug() && !"production".equalsIgnoreCase(props.env())) {
            body.put("excepcion", e.getClass().getName());
            body.put("detalle", e.getMessage());
            StackTraceElement top = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
            body.put("archivo", top == null ? null : top.getFileName() + ":" + top.getLineNumber());
        }
        return ResponseEntity.status(500).body(body);
    }
}
