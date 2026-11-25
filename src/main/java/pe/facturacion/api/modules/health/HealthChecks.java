package pe.facturacion.api.modules.health;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import pe.facturacion.api.config.AppProperties;
import pe.facturacion.api.sunat.SunatCircuitBreaker;

/**
 * Chequeos de infraestructura de {@code App\Support\Ops\HealthChecks} (API-PRO).
 * Estado por componente: {@code ok | aviso | falla}; global: {@code ok | degradado | caido}.
 * SUNAT y el disco no tumban la API (no producen 503).
 */
@Component
public class HealthChecks {

    public record Check(String nombre, String clave, String estado, String detalle, boolean afectaDisponibilidad) {
    }

    public record Resumen(String overall, int http) {
    }

    private static final int DISCO_MIN_LIBRE_PCT = 10;

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final SunatCircuitBreaker breaker;
    private final AppProperties props;

    public HealthChecks(JdbcTemplate jdbc, StringRedisTemplate redis, SunatCircuitBreaker breaker, AppProperties props) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.breaker = breaker;
        this.props = props;
    }

    public List<Check> ejecutar() {
        List<Check> checks = new ArrayList<>();
        checks.add(bd());
        checks.add(redis());
        checks.add(disco());
        checks.add(almacenamiento());
        checks.addAll(sunat());
        return checks;
    }

    public static Resumen resumen(List<Check> checks) {
        boolean caido = false;
        boolean degradado = false;
        for (Check c : checks) {
            if ("falla".equals(c.estado()) && c.afectaDisponibilidad()) {
                caido = true;
            } else if (!"ok".equals(c.estado())) {
                degradado = true;
            }
        }
        if (caido) {
            return new Resumen("caido", 503);
        }
        return new Resumen(degradado ? "degradado" : "ok", 200);
    }

    private Check bd() {
        try {
            jdbc.queryForObject("select 1", Integer.class);
            return new Check("Base de datos", "salud-bd", "ok", "pgsql", true);
        } catch (RuntimeException e) {
            return new Check("Base de datos", "salud-bd", "falla", "inaccesible", true);
        }
    }

    private Check redis() {
        try {
            String pong = redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping());
            return new Check("Redis", "salud-redis", pong == null ? "falla" : "ok", pong == null ? "inaccesible" : "", true);
        } catch (RuntimeException e) {
            return new Check("Redis", "salud-redis", "falla", "inaccesible", true);
        }
    }

    private Check disco() {
        File root = storageRoot().toFile();
        long libre = root.getUsableSpace();
        long total = root.getTotalSpace();
        if (total <= 0) {
            return new Check("Disco", "salud-disco", "aviso", "no se pudo leer", false);
        }
        int pct = (int) Math.round((double) libre / total * 100);
        return new Check("Disco", "salud-disco", pct < DISCO_MIN_LIBRE_PCT ? "aviso" : "ok", pct + "% libre", false);
    }

    private Check almacenamiento() {
        try {
            Path dir = storageRoot().resolve("ops-health");
            Files.createDirectories(dir);
            Path probe = dir.resolve("probe_" + UUID.randomUUID() + ".txt");
            Files.writeString(probe, "ok");
            Files.delete(probe);
            return new Check("Almacenamiento", "salud-storage", "ok", "", true);
        } catch (Exception e) {
            return new Check("Almacenamiento", "salud-storage", "falla", "no escribible", true);
        }
    }

    private List<Check> sunat() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("beta", props.sunat().beta().fe());
        endpoints.put("produccion", props.sunat().production().fe());
        List<Check> out = new ArrayList<>();
        endpoints.forEach((nombre, endpoint) -> {
            if (endpoint == null || endpoint.isEmpty()) {
                return;
            }
            String estado = breaker.getState(endpoint);
            String salud = switch (estado) {
                case SunatCircuitBreaker.STATE_OPEN -> "falla";
                case SunatCircuitBreaker.STATE_HALF_OPEN -> "aviso";
                default -> "ok";
            };
            String detalle = switch (estado) {
                case SunatCircuitBreaker.STATE_OPEN -> "circuit breaker abierto";
                case SunatCircuitBreaker.STATE_HALF_OPEN -> "recuperando";
                default -> "";
            };
            out.add(new Check("SUNAT " + nombre, "salud-sunat-" + nombre, salud, detalle, false));
        });
        return out;
    }

    private Path storageRoot() {
        Path p = Path.of(props.storagePath());
        try {
            Files.createDirectories(p);
        } catch (Exception ignored) {
            // se reporta en el chequeo de almacenamiento
        }
        return p;
    }
}
