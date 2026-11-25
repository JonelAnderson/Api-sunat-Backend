package pe.facturacion.api.modules.ops;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code routes/console.php} de API-PRO: mismas tareas y horarios, en UTC (zona de la app
 * Laravel). Cada ejecución toma un candado en Redis por tarea y franja: con varias instancias de
 * la API, la tarea corre en una sola ({@code onOneServer}) y nunca se solapa consigo misma
 * ({@code withoutOverlapping}). Se desactiva con {@code SCHEDULER_ENABLED=false}.
 */
@Component
public class Scheduler {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);
    static final String ZONA = "UTC";
    /** Instante ISO de la última tarea que corrió en cualquier instancia. */
    public static final String HEARTBEAT_KEY = "scheduler:heartbeat";

    private final OpsTasks tasks;
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final int logsRetentionDays;

    public Scheduler(OpsTasks tasks, StringRedisTemplate redis, @Value("${app.scheduler.enabled:true}") boolean enabled,
            @Value("${app.security.api-logs-retention-days:90}") int logsRetentionDays) {
        this.tasks = tasks;
        this.redis = redis;
        this.enabled = enabled;
        this.logsRetentionDays = logsRetentionDays;
    }

    /** {@code logs:purge --days=90}: domingos 03:00. */
    @Scheduled(cron = "0 0 3 * * SUN", zone = ZONA)
    public void purgarLogs() {
        run("logs:purge", Duration.ofHours(1), () -> tasks.purgarLogs(logsRetentionDays));
    }

    /** {@code ProcessRecurringPayments}: diario 06:00. */
    @Scheduled(cron = "0 0 6 * * *", zone = ZONA)
    public void renovarSuscripciones() {
        run("suscripciones:renovar", Duration.ofHours(1), tasks::renovarSuscripciones);
    }

    /** {@code CheckTrialExpiration}: diario 07:00. */
    @Scheduled(cron = "0 0 7 * * *", zone = ZONA)
    public void vencerPruebas() {
        run("suscripciones:vencer-pruebas", Duration.ofHours(1), tasks::vencerPruebas);
    }

    /** {@code ResetMonthlyUsage}: día 1 a las 00:05. */
    @Scheduled(cron = "0 5 0 1 * *", zone = ZONA)
    public void reiniciarUso() {
        run("uso:reiniciar", Duration.ofHours(1), tasks::reiniciarUso);
    }

    /** {@code sunat:reintentar-pendientes}: cada 15 minutos. */
    @Scheduled(cron = "0 */15 * * * *", zone = ZONA)
    public void reintentarPendientes() {
        run("sunat:reintentar-pendientes", Duration.ofMinutes(14), () -> tasks.reintentarPendientes(15, 7, List.of("enviado")));
    }

    /** {@code summaries:poll-pending}: cada 5 minutos. */
    @Scheduled(cron = "0 */5 * * * *", zone = ZONA)
    public void pollResumenes() {
        run("summaries:poll-pending", Duration.ofMinutes(4), () -> tasks.pollResumenes(100));
    }

    /** {@code certificados:verificar --alertar}: diario 08:00. */
    @Scheduled(cron = "0 0 8 * * *", zone = ZONA)
    public void verificarCertificados() {
        run("certificados:verificar", Duration.ofHours(1), () -> tasks.verificarCertificados(null, true));
    }

    /** {@code ops:jobs-fallidos --alertar}: cada hora. */
    @Scheduled(cron = "0 0 * * * *", zone = ZONA)
    public void revisarJobsFallidos() {
        run("ops:jobs-fallidos", Duration.ofMinutes(50), () -> tasks.revisarJobsFallidos(true));
    }

    /** {@code ops:salud --alertar}: cada 10 minutos. */
    @Scheduled(cron = "0 */10 * * * *", zone = ZONA)
    public void revisarSalud() {
        run("ops:salud", Duration.ofMinutes(9), () -> tasks.revisarSalud(true));
    }

    /**
     * Ejecuta la tarea si esta instancia gana el candado. El candado dura menos que el intervalo
     * de la tarea: cubre la franja actual y evita que otra instancia la repita.
     */
    boolean run(String name, Duration lock, Supplier<List<String>> task) {
        if (!enabled) {
            return false;
        }
        String key = "scheduler:" + name;
        Boolean won = redis.opsForValue().setIfAbsent(key, "1", lock);
        if (!Boolean.TRUE.equals(won)) {
            return false;
        }
        long start = System.currentTimeMillis();
        try {
            // Última ejecución de cualquier tarea (la lee la salud del panel de administración)
            redis.opsForValue().set(HEARTBEAT_KEY, pe.facturacion.api.shared.time.Clock.now().toString(), Duration.ofHours(1));
        } catch (RuntimeException ignored) {
            // el latido nunca impide correr la tarea
        }
        try {
            List<String> out = task.get();
            log.info("scheduler.task tarea={} ms={} resultado={}", name, System.currentTimeMillis() - start, String.join(" | ", out));
        } catch (RuntimeException e) {
            log.error("scheduler.task.failed tarea={} error={}", name, e.toString());
        }
        return true;
    }
}
