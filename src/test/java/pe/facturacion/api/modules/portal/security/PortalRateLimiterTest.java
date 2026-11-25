package pe.facturacion.api.modules.portal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import pe.facturacion.api.modules.portal.security.PortalRateLimiter.Regeneration;

/** Límites del portal: 10 por minuto y por IP (falla abierto) y 3 regeneraciones por hora y empresa (falla cerrado). */
class PortalRateLimiterTest {

    StringRedisTemplate redis;
    ValueOperations<String, String> ops;
    PortalRateLimiter limiter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        limiter = new PortalRateLimiter(redis);
    }

    @Test
    void la_ip_tiene_10_intentos_por_minuto() {
        AtomicLong counter = new AtomicLong();
        when(ops.get(anyString())).thenAnswer(i -> counter.get() == 0 ? null : String.valueOf(counter.get()));
        when(ops.increment(anyString())).thenAnswer(i -> counter.incrementAndGet());
        for (int i = 0; i < PortalRateLimiter.AUTH_PER_MINUTE; i++) {
            assertThat(limiter.allow("1.2.3.4")).as("intento %d", i + 1).isTrue();
        }
        assertThat(limiter.allow("1.2.3.4")).isFalse();
        // La ventana de 60 s se fija con el primer intento
        verify(redis).expire("throttle:portal-auth:1.2.3.4", Duration.ofSeconds(60));
    }

    @Test
    void sin_redis_el_limite_por_ip_deja_pasar_porque_el_bloqueo_por_cuenta_sigue_en_la_base() {
        when(ops.get(anyString())).thenThrow(new IllegalStateException("redis caído"));
        assertThat(limiter.allow("1.2.3.4")).isTrue();
    }

    @Test
    void cada_empresa_regenera_3_veces_por_hora() {
        AtomicLong counter = new AtomicLong();
        when(ops.increment(anyString())).thenAnswer(i -> counter.incrementAndGet());
        when(redis.getExpire(anyString())).thenReturn(3600L);
        for (int i = 0; i < PortalRateLimiter.REGENERATIONS_PER_HOUR; i++) {
            assertThat(limiter.takeRegeneration(7)).as("regeneración %d", i + 1).isEqualTo(Regeneration.GRANTED);
        }
        assertThat(limiter.takeRegeneration(7)).isEqualTo(Regeneration.EXHAUSTED);
        assertThat(limiter.takeRegeneration(7)).isEqualTo(Regeneration.EXHAUSTED);
    }

    @Test
    void el_cupo_de_regeneracion_va_por_empresa_y_vence_a_la_hora() {
        AtomicLong counter = new AtomicLong();
        when(ops.increment("throttle:portal-regenerate:7")).thenAnswer(i -> counter.incrementAndGet());
        when(ops.increment("throttle:portal-regenerate:8")).thenReturn(1L);
        when(redis.getExpire(anyString())).thenReturn(-1L);
        limiter.takeRegeneration(7);
        verify(redis).expire("throttle:portal-regenerate:7", Duration.ofHours(1));
        // Otra empresa no se ve afectada por el uso de la primera
        assertThat(limiter.takeRegeneration(8)).isEqualTo(Regeneration.GRANTED);
    }

    @Test
    void una_clave_sin_vencimiento_se_repara_para_no_bloquear_a_la_empresa_para_siempre() {
        when(ops.increment(anyString())).thenReturn(2L);
        when(redis.getExpire(anyString())).thenReturn(-1L);
        assertThat(limiter.takeRegeneration(7)).isEqualTo(Regeneration.GRANTED);
        verify(redis).expire("throttle:portal-regenerate:7", Duration.ofHours(1));
    }

    @Test
    void sin_redis_no_se_regenera_falla_cerrado() {
        when(ops.increment(anyString())).thenThrow(new IllegalStateException("redis caído"));
        assertThat(limiter.takeRegeneration(7)).isEqualTo(Regeneration.UNAVAILABLE);
        org.mockito.Mockito.doReturn(null).when(ops).increment(anyString());
        assertThat(limiter.takeRegeneration(7)).isEqualTo(Regeneration.UNAVAILABLE);
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }
}
