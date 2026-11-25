package pe.facturacion.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import pe.facturacion.api.config.AppProperties;

/**
 * Interruptores de las rutas de autoservicio de {@code /api/v1} (Docs/plataforma-consola-arquitectura.md §5.3). Con la
 * consola de plataforma, las empresas y sus planes los administra la consola; estas rutas quedan apagadas por defecto:
 * <ul>
 *   <li>{@code REGISTRO_PUBLICO_ENABLED} → {@code POST /api/v1/registro} (alta de una empresa con cualquier RUC).</li>
 *   <li>{@code SUSCRIPCION_AUTOSERVICIO_ENABLED} → {@code POST /api/v1/suscripcion}, {@code PUT .../cambiar-plan} y
 *       {@code PUT .../cancelar} (la empresa cambia su propio plan con su API key; el cobro es de Culqi).</li>
 * </ul>
 * Apagado responde 404 en el sobre de la API (como una ruta que no existe) y no ejecuta nada, y corre antes de la
 * autenticación y del límite de solicitudes: no revela nada ni gasta cuota. Quedan siempre activas las lecturas
 * ({@code GET suscripcion}, {@code pagos} y {@code uso}): no cambian estado ni dependen de Culqi, y son lo que lee el
 * cliente para ver su cupo; el panel de administración llama a esos métodos sin pasar por HTTP.
 */
@Component
public class PublicFeatureInterceptor implements HandlerInterceptor {

    public static final String REGISTRO_PATH = "/api/v1/registro";
    public static final String SUSCRIPCION_PATH = "/api/v1/suscripcion";

    public static final String REGISTRO_OFF_MESSAGE =
            "El registro público de empresas no está disponible. Solicite el alta de su empresa al administrador del servicio.";
    public static final String SUSCRIPCION_OFF_MESSAGE =
            "El autoservicio de suscripción no está disponible. Solicite el cambio de plan al administrador del servicio.";

    /** Métodos que cambian estado: solo estos se apagan en {@code /suscripcion}. */
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AppProperties props;

    public PublicFeatureInterceptor(AppProperties props) {
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Optional<String> off = disabledMessage(props, request.getMethod(), request.getRequestURI());
        if (off.isEmpty()) {
            return true;
        }
        ApiContext.writeJson(response, 404, ApiContext.error(off.get()));
        return false;
    }

    /** Mensaje de la ruta apagada, o vacío si la petición puede seguir. Pura: solo mira método, ruta y banderas. */
    public static Optional<String> disabledMessage(AppProperties props, String method, String uri) {
        String path = uri == null ? "" : uri.replaceAll("/+$", "");
        if (path.equals(REGISTRO_PATH)) {
            return props.registroEnabled() ? Optional.empty() : Optional.of(REGISTRO_OFF_MESSAGE);
        }
        boolean suscripcion = path.equals(SUSCRIPCION_PATH) || path.startsWith(SUSCRIPCION_PATH + "/");
        if (suscripcion && method != null && WRITES.contains(method.toUpperCase())) {
            return props.suscripcionAutoservicioEnabled() ? Optional.empty() : Optional.of(SUSCRIPCION_OFF_MESSAGE);
        }
        return Optional.empty();
    }
}
