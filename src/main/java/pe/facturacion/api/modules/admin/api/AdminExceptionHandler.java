package pe.facturacion.api.modules.admin.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import pe.facturacion.api.shared.api.ApiException;
import pe.facturacion.api.shared.validation.ValidationException;

/**
 * Errores de los controladores del panel y del portal de clientes con el sobre {@code {succeeded:false, ...}}. Solo
 * aplica a los paquetes {@code modules.admin} y {@code modules.portal} y va antes que {@code GlobalExceptionHandler},
 * que sigue respondiendo con el formato de Laravel en el resto de la API.
 */
@RestControllerAdvice(basePackages = {"pe.facturacion.api.modules.admin", "pe.facturacion.api.modules.portal"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminExceptionHandler.class);

    @ExceptionHandler(AdminException.class)
    public ResponseEntity<Object> handleAdmin(AdminException e) {
        return ResponseEntity.status(e.status())
                .header("Cache-Control", "no-store")
                .body(AdminResponse.body(false, e.messages(), null, 0, e.errors()));
    }

    /**
     * Error de un servicio de {@code /api/v1} que reutilizan las vistas de empresa (p. ej. {@code Recurso no
     * encontrado.} cuando el documento es de otra empresa): mismo sobre y misma regla de códigos que el panel.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApi(ApiException e) {
        return handleAdmin(AdminV1Responses.error(e.getStatus(), e.getBody()));
    }

    /** Validación de los filtros reutilizados de {@code /api/v1} (HTTP 200 con {@code errors}). */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Object> handleValidation(ValidationException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mensaje", e.getMessage());
        body.put("errores", e.getErrors());
        return handleAdmin(AdminV1Responses.error(422, body));
    }

    /** {id} no numérico. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return AdminResponse.status(404, "Recurso no encontrado.");
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Object> handleUnexpected(Throwable e, HttpServletRequest request) {
        String ref = UUID.randomUUID().toString();
        log.error("Error no controlado en el panel {} {} (error_id={})", request.getMethod(), request.getRequestURI(), ref, e);
        return AdminResponse.status(500, "Error interno del servidor (referencia " + ref + ").");
    }
}
