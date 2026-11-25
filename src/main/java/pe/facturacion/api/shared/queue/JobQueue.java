package pe.facturacion.api.shared.queue;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pe.facturacion.api.shared.json.PhpJson;

/**
 * Cola de trabajos sobre Redis, equivalente al driver {@code redis} de las colas de Laravel:
 *
 * <ul>
 *   <li>Colas con nombre ({@code sunat}, {@code webhooks}); cada trabajo es JSON con su tipo,
 *       sus datos y el número de intentos.</li>
 *   <li>{@code ZSET queues:{cola}:delayed} con la hora de disponibilidad; el worker toma los
 *       vencidos con un script atómico y los pasa a {@code queues:{cola}:reserved} con un plazo
 *       (si el proceso muere, otro worker los recupera al vencer el plazo).</li>
 *   <li>{@link JobContext#release(long)} = {@code $this->release($delay)}; una excepción reintenta
 *       con el backoff del manejador hasta agotar {@code tries}.</li>
 *   <li>Se encola al confirmar la transacción ({@code after_commit}): el trabajo nunca ve un
 *       comprobante sin confirmar.</li>
 * </ul>
 */
@Component
public class JobQueue {

    private static final Logger log = LoggerFactory.getLogger(JobQueue.class);

    /** Toma hasta ARGV[2] trabajos vencidos y los reserva hasta ARGV[3]. */
    private static final DefaultRedisScript<List> POP = new DefaultRedisScript<>(
            "local jobs = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))\n"
                    + "for _, j in ipairs(jobs) do\n"
                    + "  redis.call('ZREM', KEYS[1], j)\n"
                    + "  redis.call('ZADD', KEYS[2], ARGV[3], j)\n"
                    + "end\n"
                    + "return jobs", List.class);

    /** Devuelve a la cola los reservados cuyo plazo venció (worker caído). */
    private static final DefaultRedisScript<Long> RECOVER = new DefaultRedisScript<>(
            "local jobs = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])\n"
                    + "for _, j in ipairs(jobs) do\n"
                    + "  redis.call('ZREM', KEYS[1], j)\n"
                    + "  redis.call('ZADD', KEYS[2], ARGV[1], j)\n"
                    + "end\n"
                    + "return #jobs", Long.class);

    /** Manejador de un tipo de trabajo. */
    public interface JobHandler {

        String type();

        String queue();

        /** {@code $tries}. */
        int tries();

        /** {@code $timeout} en segundos (plazo de reserva). */
        default int timeoutSeconds() {
            return 90;
        }

        /** {@code $backoff[$attempt - 1]} en segundos, usado cuando el trabajo lanza una excepción. */
        long backoff(int attempt);

        void handle(JobContext ctx) throws Exception;

        /** {@code failed()}: se agotaron los intentos con excepción. */
        default void failed(JobContext ctx, Throwable e) {
        }
    }

    /** Estado de una ejecución. */
    public static final class JobContext {
        private final Map<String, Object> data;
        private final int attempts;
        private Long releaseDelay;
        private boolean countAttempt = true;

        JobContext(Map<String, Object> data, int attempts) {
            this.data = data;
            this.attempts = attempts;
        }

        public Map<String, Object> data() {
            return data;
        }

        /** {@code $this->attempts()}: 1 en la primera ejecución. */
        public int attempts() {
            return attempts;
        }

        /** {@code $this->release($delay)}. */
        public void release(long delaySeconds) {
            this.releaseDelay = delaySeconds;
        }

        /** Reencola sin consumir un intento (límite por empresa o envío en curso del mismo documento). */
        public void releaseWithoutAttempt(long delaySeconds) {
            this.releaseDelay = delaySeconds;
            this.countAttempt = false;
        }
    }

    private final StringRedisTemplate redis;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final Map<String, JobHandler> handlers = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final Semaphore slots;
    private final boolean workerEnabled;
    private final String prefix;

    public JobQueue(StringRedisTemplate redis,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            @Value("${app.queue.worker-enabled:true}") boolean workerEnabled,
            @Value("${app.queue.concurrency:4}") int concurrency,
            @Value("${app.queue.prefix:queues}") String prefix) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.workerEnabled = workerEnabled;
        this.prefix = prefix;
        this.slots = new Semaphore(Math.max(1, concurrency));
        this.executor = Executors.newFixedThreadPool(Math.max(1, concurrency), r -> {
            Thread t = new Thread(r, "job-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void register(JobHandler handler) {
        handlers.put(handler.type(), handler);
    }

    /** {@code Job::dispatch(...)}: encola al confirmar la transacción actual (o de inmediato si no hay). */
    public void dispatch(String type, Map<String, Object> data) {
        dispatch(type, data, 0);
    }

    public void dispatch(String type, Map<String, Object> data, long delaySeconds) {
        JobHandler h = handlers.get(type);
        if (h == null) {
            throw new IllegalStateException("Trabajo sin manejador: " + type);
        }
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("uuid", UUID.randomUUID().toString());
        job.put("type", type);
        job.put("data", data);
        job.put("attempts", 0);
        Runnable push = () -> push(h.queue(), job, delaySeconds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    push.run();
                }
            });
        } else {
            push.run();
        }
    }

    private void push(String queue, Map<String, Object> job, long delaySeconds) {
        redis.opsForZSet().add(key(queue, "delayed"), PhpJson.encode(job), System.currentTimeMillis() + delaySeconds * 1000);
    }

    private String key(String queue, String part) {
        return prefix + ":" + queue + ":" + part;
    }

    /** Colas con al menos un manejador registrado (diagnóstico: salud del panel de administración). */
    public List<String> queues() {
        return handlers.values().stream().map(JobHandler::queue).distinct().sorted().toList();
    }

    /** Número de trabajos pendientes (diagnóstico y pruebas). */
    public long size(String queue) {
        Long d = redis.opsForZSet().zCard(key(queue, "delayed"));
        Long r = redis.opsForZSet().zCard(key(queue, "reserved"));
        return (d == null ? 0 : d) + (r == null ? 0 : r);
    }

    @Scheduled(fixedDelayString = "${app.queue.poll-ms:500}")
    public void poll() {
        if (!workerEnabled) {
            return;
        }
        for (String queue : handlers.values().stream().map(JobHandler::queue).distinct().toList()) {
            try {
                long now = System.currentTimeMillis();
                redis.execute(RECOVER, List.of(key(queue, "reserved"), key(queue, "delayed")), String.valueOf(now));
                int free = slots.availablePermits();
                if (free <= 0) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<String> jobs = redis.execute(POP, List.of(key(queue, "delayed"), key(queue, "reserved")),
                        String.valueOf(now), String.valueOf(free), String.valueOf(now + Duration.ofMinutes(5).toMillis()));
                if (jobs == null) {
                    continue;
                }
                for (String raw : jobs) {
                    slots.acquireUninterruptibly();
                    executor.submit(() -> {
                        try {
                            run(queue, raw);
                        } finally {
                            slots.release();
                        }
                    });
                }
            } catch (RuntimeException e) {
                log.warn("queue.poll.failed cola={} error={}", queue, e.getMessage());
            }
        }
    }

    /** Ejecuta un trabajo reservado y decide si se borra, se reencola o falla. */
    @SuppressWarnings("unchecked")
    void run(String queue, String raw) {
        Map<String, Object> job = PhpJson.decodeObject(raw);
        JobHandler h = handlers.get(String.valueOf(job.get("type")));
        int attempts = ((Number) job.getOrDefault("attempts", 0)).intValue() + 1;
        JobContext ctx = new JobContext((Map<String, Object>) job.get("data"), attempts);
        try {
            if (h == null) {
                log.error("queue.job.unknown tipo={}", job.get("type"));
                return;
            }
            h.handle(ctx);
            if (ctx.releaseDelay != null) {
                job.put("attempts", ctx.countAttempt ? attempts : attempts - 1);
                push(queue, job, ctx.releaseDelay);
            }
        } catch (Throwable e) {
            if (attempts >= h.tries()) {
                log.error("queue.job.failed tipo={} intentos={} error={}", job.get("type"), attempts, e.toString());
                recordFailure(queue, job, e);
                try {
                    h.failed(ctx, e);
                } catch (RuntimeException ignored) {
                    // failed() nunca debe tumbar el worker
                }
            } else {
                log.warn("queue.job.retry tipo={} intento={} error={}", job.get("type"), attempts, e.toString());
                job.put("attempts", attempts);
                push(queue, job, h.backoff(attempts));
            }
        } finally {
            redis.opsForZSet().remove(key(queue, "reserved"), raw);
        }
    }

    /** Guarda el trabajo agotado en {@code failed_jobs} (tabla de Laravel), sin la traza completa. */
    private void recordFailure(String queue, Map<String, Object> job, Throwable e) {
        try {
            String ex = e.toString();
            jdbc.update("INSERT INTO failed_jobs (uuid, queue, payload, exception, failed_at) VALUES (?, ?, CAST(? AS jsonb), ?, ?) "
                    + "ON CONFLICT (uuid) DO NOTHING", String.valueOf(job.get("uuid")), queue, PhpJson.encode(job),
                    ex.length() > 4000 ? ex.substring(0, 4000) : ex, pe.facturacion.api.shared.time.Clock.nowUtc().withNano(0));
        } catch (RuntimeException ignored) {
            // el registro del fallo nunca debe tumbar el worker
        }
    }

    /**
     * {@code queue:retry}: vuelve a encolar los trabajos fallidos (todos o el de ese id) con los
     * intentos en cero y los quita de {@code failed_jobs}. Devuelve cuántos reencoló.
     */
    public int retryFailed(Long id) {
        java.util.List<Map<String, Object>> rows = id == null
                ? jdbc.queryForList("SELECT id, queue, payload::text AS payload FROM failed_jobs ORDER BY id")
                : jdbc.queryForList("SELECT id, queue, payload::text AS payload FROM failed_jobs WHERE id = ?", id);
        int n = 0;
        for (Map<String, Object> r : rows) {
            Map<String, Object> job = PhpJson.decodeObject(String.valueOf(r.get("payload")));
            job.put("attempts", 0);
            job.put("uuid", UUID.randomUUID().toString());
            push(String.valueOf(r.get("queue")), job, 0);
            jdbc.update("DELETE FROM failed_jobs WHERE id = ?", ((Number) r.get("id")).longValue());
            n++;
        }
        return n;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
