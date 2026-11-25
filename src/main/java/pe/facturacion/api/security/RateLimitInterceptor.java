package pe.facturacion.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import pe.facturacion.api.config.AppProperties;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.crypto.SecretCipher;

/**
 * Limitador {@code throttle:api} de API-PRO: solicitudes por minuto según la columna
 * {@code tenants.plan} (free 30 · pro 120 · business 300), por empresa o por IP en rutas públicas.
 * {@code X-Internal-Token} válido exime del límite. Ventana fija de 60 s en Redis (compartida
 * entre instancias), con cabeceras {@code X-RateLimit-Limit} y {@code X-RateLimit-Remaining}.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Map<String, Integer> LIMITS = Map.of("free", 30, "pro", 120, "business", 300);

    private final StringRedisTemplate redis;
    private final AppProperties props;

    public RateLimitInterceptor(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String internal = props.security().internalToken();
        if (internal != null && !internal.isEmpty()
                && SecretCipher.constantTimeEquals(internal, request.getHeader("X-Internal-Token") == null ? "" : request.getHeader("X-Internal-Token"))) {
            return true;
        }

        Tenant tenant = ApiContext.tenant(request);
        int max;
        String key;
        if (request.getRequestURI().startsWith("/api/bridge/")) {
            // Rama de seguridad (SEC-007): throttle:20,1 por IP en el bridge SaaS
            max = 20;
            key = "throttle:bridge:" + request.getRemoteAddr();
        } else {
            String plan = tenant == null || tenant.getPlan() == null ? "free" : tenant.getPlan();
            max = LIMITS.getOrDefault(plan, 30);
            key = "throttle:api:" + (tenant != null ? String.valueOf(tenant.getId()) : request.getRemoteAddr());
        }

        long used = consume(key, max);
        if (used < 0) {
            ApiContext.writeJson(response, 429, ApiContext.error("Demasiadas solicitudes. Intenta de nuevo más tarde."));
            return false;
        }
        response.setHeader("X-RateLimit-Limit", String.valueOf(max));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, max - used)));
        return true;
    }

    /**
     * Cuenta una solicitud en la ventana de la empresa (la misma de {@code /api/v1}, según su plan). La usan las
     * consultas en vivo a SUNAT del panel de administración: no pueden superar lo que la empresa misma podría pedir.
     * false si la empresa ya agotó su ventana.
     */
    public boolean consumeTenant(Tenant tenant) {
        String plan = tenant.getPlan() == null ? "free" : tenant.getPlan();
        return consume("throttle:api:" + tenant.getId(), LIMITS.getOrDefault(plan, 30)) >= 0;
    }

    /** Ventana fija de 60 s: solicitudes usadas con esta, o -1 si ya se llegó al máximo (no cuenta). */
    private long consume(String key, int max) {
        String current = redis.opsForValue().get(key);
        long attempts = current == null ? 0 : Long.parseLong(current);
        if (attempts >= max) {
            return -1;
        }
        Long hits = redis.opsForValue().increment(key);
        if (hits != null && hits == 1) {
            redis.expire(key, Duration.ofSeconds(60));
        }
        return hits == null ? attempts + 1 : hits;
    }
}
