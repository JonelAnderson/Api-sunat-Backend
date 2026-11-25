package pe.facturacion.api.modules.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.config.AppProperties;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.sunat.SunatCircuitBreaker;

@RestController
@Tag(name = "Salud")
public class HealthController {

    private static final String CACHE_KEY = "ops:health";

    private final HealthChecks health;
    private final StringRedisTemplate redis;
    private final SunatCircuitBreaker breaker;
    private final AppProperties props;

    public HealthController(HealthChecks health, StringRedisTemplate redis, SunatCircuitBreaker breaker, AppProperties props) {
        this.health = health;
        this.redis = redis;
        this.breaker = breaker;
        this.props = props;
    }

    /**
     * {@code GET /api/health}: chequeo profundo público para monitores. 200 si la API opera
     * (aunque esté degradada), 503 si BD, Redis o almacenamiento están caídos. Cacheado 15 s.
     */
    @Operation(summary = "Health check profundo (público)")
    @GetMapping("/api/health")
    public ResponseEntity<String> health() {
        String cached = null;
        try {
            cached = redis.opsForValue().get(CACHE_KEY);
        } catch (RuntimeException ignored) {
            // Redis caído: se calcula en vivo
        }
        if (cached != null) {
            int sep = cached.indexOf('|');
            return json(Integer.parseInt(cached.substring(0, sep)), cached.substring(sep + 1));
        }
        List<HealthChecks.Check> checks = health.ejecutar();
        HealthChecks.Resumen resumen = HealthChecks.resumen(checks);
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("estado", resumen.overall());
        cuerpo.put("timestamp", Clock.iso8601Utc(Clock.now()));
        cuerpo.put("checks", checks.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("componente", c.nombre());
            m.put("estado", c.estado());
            m.put("detalle", c.detalle());
            return m;
        }).toList());
        String body = PhpJson.encode(cuerpo);
        try {
            redis.opsForValue().set(CACHE_KEY, resumen.http() + "|" + body, Duration.ofSeconds(15));
        } catch (RuntimeException ignored) {
            // sin caché
        }
        return json(resumen.http(), body);
    }

    /** {@code GET /api/v1/sunat/estado}: estado del circuit breaker del endpoint FE del entorno de la empresa. */
    @Operation(summary = "Disponibilidad de SUNAT para la empresa (circuit breaker)")
    @GetMapping("/api/v1/sunat/estado")
    public Map<String, Object> sunatEstado(HttpServletRequest request) {
        Tenant tenant = ApiContext.requireTenant(request);
        String env = tenant.getEnvironment();
        SunatCircuitBreaker.Stats stats = breaker.getStats(props.sunat().forEnvironment(env).fe());
        Map<String, Object> sunat = new LinkedHashMap<>();
        sunat.put("disponible", SunatCircuitBreaker.STATE_CLOSED.equals(stats.state()));
        sunat.put("circuit", stats.state());
        sunat.put("fallas", stats.failures());
        sunat.put("entorno", env);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "exito");
        body.put("sunat", sunat);
        return body;
    }

    private static ResponseEntity<String> json(int status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
