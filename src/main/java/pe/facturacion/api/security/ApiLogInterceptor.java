package pe.facturacion.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import pe.facturacion.api.shared.http.RequestInput;

/**
 * Marca la petición para {@code log.api} y {@code usage.headers}. El registro y las cabeceras
 * se escriben al terminar en {@link ApiPipelineFilter} (equivalente a {@code afterResponse()}).
 */
@Component
public class ApiLogInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ApiContext.LOG_START, System.nanoTime());
        // Aquí la petición ya viene resuelta como multipart: se capturan también los archivos.
        request.setAttribute(ApiContext.LOG_INPUT, RequestInput.all(request));
        request.setAttribute(ApiContext.USAGE_HEADERS, Boolean.TRUE);
        return true;
    }
}
