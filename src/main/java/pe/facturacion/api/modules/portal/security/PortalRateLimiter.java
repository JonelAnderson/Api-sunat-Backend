package pe.facturacion.api.modules.portal.security;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Límites del portal en Redis (ventanas fijas, compartidas entre instancias):
 * <ul>
 *   <li>{@link #allow}: autenticación por IP, 10 por minuto, antes de autenticar. Si Redis no responde deja pasar: el
 *       bloqueo por cuenta (5 fallos → 15 min, en la BD) sigue protegiendo el login;</li>
 *   <li>{@link #takeRegeneration}: regenerar credenciales, 3 por hora y por empresa. Aquí <b>falla cerrado</b>: sin
 *       Redis no se puede verificar el límite y no se regenera.</li>
 * </ul>
 */
@Component
public class PortalRateLimiter {

    public static final int AUTH_PER_MINUTE = 10;
    public static final int REGENERATIONS_PER_HOUR = 3;
    static final String AUTH_PREFIX = "throttle:portal-auth:";
    static final String REGENERATE_PREFIX = "throttle:portal-regenerate:";

    private static final Logger log = LoggerFactory.getLogger(PortalRateLimiter.class);

    private final StringRedisTemplate redis;

    public PortalRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** true si la IP aún tiene intentos en la ventana actual (y cuenta este). */
    public boolean allow(String ip) {
        try {
            String key = AUTH_PREFIX + ip;
            String current = redis.opsForValue().get(key);
            long attempts = current == null ? 0 : Long.parseLong(current);
            if (attempts >= AUTH_PER_MINUTE) {
                return false;
            }
            Long hits = redis.opsForValue().increment(key);
            if (hits != null && hits == 1) {
                redis.expire(key, Duration.ofSeconds(60));
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("portal.ratelimit.redis_no_disponible error={}", e.getClass().getSimpleName());
            return true;
        }
    }

    /** Resultado de pedir un cupo de regeneración. */
    public enum Regeneration {
        /** Cupo tomado. */
        GRANTED,
        /** Ya hubo 3 en la última hora. */
        EXHAUSTED,
        /** No se pudo verificar el límite (Redis caído): no se regenera. */
        UNAVAILABLE
    }

    /** Toma uno de los 3 cupos por hora de la empresa (atómico: dos pedidos a la vez no pasan el tope). */
    public Regeneration takeRegeneration(long tenantId) {
        try {
            String key = REGENERATE_PREFIX + tenantId;
            Long hits = redis.opsForValue().increment(key);
            if (hits == null) {
                return Regeneration.UNAVAILABLE;
            }
            Long ttl = redis.getExpire(key);
            if (hits == 1 || ttl == null || ttl < 0) {
                // Primera vez, o sin vencimiento (se cayó entre INCR y EXPIRE): la empresa nunca queda bloqueada para siempre
                redis.expire(key, Duration.ofHours(1));
            }
            return hits > REGENERATIONS_PER_HOUR ? Regeneration.EXHAUSTED : Regeneration.GRANTED;
        } catch (RuntimeException e) {
            log.warn("portal.ratelimit.regenerar_redis_no_disponible error={}", e.getClass().getSimpleName());
            return Regeneration.UNAVAILABLE;
        }
    }
}
