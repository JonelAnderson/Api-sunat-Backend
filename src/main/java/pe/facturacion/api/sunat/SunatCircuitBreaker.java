package pe.facturacion.api.sunat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Circuit breaker por endpoint de SUNAT, compartido entre instancias vía Redis
 * ({@code App\Services\Sunat\SunatCircuitBreaker} de API-PRO).
 *
 * <pre>
 * CLOSED → OPEN:      5 fallos en 60 s
 * OPEN → HALF_OPEN:   al expirar el TTL de 300 s
 * HALF_OPEN → CLOSED: sonda exitosa
 * HALF_OPEN → OPEN:   sonda fallida (TTL x2)
 * </pre>
 */
@Component
public class SunatCircuitBreaker {

    public static final String STATE_CLOSED = "closed";
    public static final String STATE_OPEN = "open";
    public static final String STATE_HALF_OPEN = "half_open";

    private static final Logger log = LoggerFactory.getLogger(SunatCircuitBreaker.class);
    private static final int FAILURE_THRESHOLD = 5;
    private static final int FAILURE_WINDOW_SECONDS = 60;
    private static final int OPEN_TTL_SECONDS = 300;
    private static final int HALF_OPEN_TTL_SECONDS = 30;

    private final StringRedisTemplate redis;

    public SunatCircuitBreaker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record Stats(String endpoint, String state, int failures) {
    }

    /** ¿Se puede enviar ahora? En HALF_OPEN solo deja pasar una sonda (SETNX atómico). */
    public boolean isAvailable(String endpoint) {
        return switch (getState(endpoint)) {
            case STATE_CLOSED -> true;
            case STATE_OPEN -> false;
            default -> Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(endpoint, "probe"), "1", Duration.ofSeconds(HALF_OPEN_TTL_SECONDS)));
        };
    }

    public void recordSuccess(String endpoint) {
        boolean wasOpen = !STATE_CLOSED.equals(getState(endpoint));
        redis.delete(java.util.List.of(key(endpoint, "open_state"), key(endpoint, "was_open"), key(endpoint, "failures"), key(endpoint, "probe")));
        if (wasOpen) {
            log.info("SunatCircuitBreaker: [{}] recuperado → CLOSED", endpoint);
        }
    }

    public void recordFailure(String endpoint) {
        String k = key(endpoint, "failures");
        Long failures = redis.opsForValue().increment(k);
        if (failures != null && failures == 1) {
            redis.expire(k, Duration.ofSeconds(FAILURE_WINDOW_SECONDS));
        }
        if (failures != null && failures >= FAILURE_THRESHOLD) {
            transition(STATE_OPEN, endpoint, OPEN_TTL_SECONDS);
        }
    }

    public void recordProbeFailure(String endpoint) {
        redis.delete(key(endpoint, "probe"));
        transition(STATE_OPEN, endpoint, OPEN_TTL_SECONDS * 2);
    }

    public String getState(String endpoint) {
        if (Boolean.TRUE.equals(redis.hasKey(key(endpoint, "open_state")))) {
            return STATE_OPEN;
        }
        if (Boolean.TRUE.equals(redis.hasKey(key(endpoint, "was_open")))) {
            return STATE_HALF_OPEN;
        }
        return STATE_CLOSED;
    }

    public Stats getStats(String endpoint) {
        String f = redis.opsForValue().get(key(endpoint, "failures"));
        return new Stats(endpoint, getState(endpoint), f == null ? 0 : Integer.parseInt(f));
    }

    public void forceClose(String endpoint) {
        recordSuccess(endpoint);
        log.info("SunatCircuitBreaker: [{}] CLOSED por fuerza (manual)", endpoint);
    }

    private void transition(String state, String endpoint, int ttl) {
        String current = getState(endpoint);
        if (current.equals(state)) {
            return;
        }
        log.warn("SunatCircuitBreaker: [{}] {} → {} (TTL: {}s)", endpoint, current, state, ttl);
        redis.opsForValue().set(key(endpoint, "open_state"), "1", Duration.ofSeconds(ttl));
        redis.opsForValue().set(key(endpoint, "was_open"), "1", Duration.ofSeconds(ttl + HALF_OPEN_TTL_SECONDS));
    }

    private static String key(String endpoint, String suffix) {
        return "sunat_cb:" + md5(endpoint).substring(0, 8) + ":" + suffix;
    }

    private static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
