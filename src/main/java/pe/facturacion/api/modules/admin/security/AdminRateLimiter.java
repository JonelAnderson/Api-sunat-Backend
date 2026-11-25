package pe.facturacion.api.modules.admin.security;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Límite por IP de las acciones de autenticación del panel: 10 por minuto, ventana fija de 60 s en Redis (la misma
 * que {@code RateLimitInterceptor}, compartida entre instancias). Corre antes de autenticar. Si Redis no responde
 * deja pasar: el bloqueo por cuenta (5 fallos → 15 min, en la BD) sigue protegiendo el login.
 */
@Component
public class AdminRateLimiter {

    public static final int MAX_PER_MINUTE = 10;
    static final String PREFIX = "throttle:admin-auth:";

    private static final Logger log = LoggerFactory.getLogger(AdminRateLimiter.class);

    private final StringRedisTemplate redis;

    public AdminRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Consultas en vivo a SUNAT por administrador y minuto (además del límite de la empresa). */
    public static final int SUNAT_QUERIES_PER_MINUTE = 20;
    static final String SUNAT_PREFIX = "throttle:admin-sunat:";

    /**
     * Acciones ante SUNAT en nombre de una empresa (fase D: emitir, corregir, reenviar, anular y refrescar) por
     * administrador y minuto, además del límite de la empresa.
     */
    public static final int DOCUMENT_ACTIONS_PER_MINUTE = 30;
    static final String DOCUMENT_PREFIX = "throttle:admin-documents:";

    /** true si el administrador aún puede operar comprobantes en la ventana actual (y cuenta esta). */
    public boolean allowDocumentAction(long userId) {
        return allow(DOCUMENT_PREFIX + userId, DOCUMENT_ACTIONS_PER_MINUTE);
    }

    /** true si la IP aún tiene intentos en la ventana actual (y cuenta este). */
    public boolean allow(String ip) {
        return allow(PREFIX + ip, MAX_PER_MINUTE);
    }

    /** true si el administrador aún puede consultar SUNAT en vivo en la ventana actual (y cuenta esta). */
    public boolean allowSunatQuery(long userId) {
        return allow(SUNAT_PREFIX + userId, SUNAT_QUERIES_PER_MINUTE);
    }

    private boolean allow(String key, int max) {
        try {
            String current = redis.opsForValue().get(key);
            long attempts = current == null ? 0 : Long.parseLong(current);
            if (attempts >= max) {
                return false;
            }
            Long hits = redis.opsForValue().increment(key);
            if (hits != null && hits == 1) {
                redis.expire(key, Duration.ofSeconds(60));
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("admin.ratelimit.redis_no_disponible error={}", e.getClass().getSimpleName());
            return true;
        }
    }
}
