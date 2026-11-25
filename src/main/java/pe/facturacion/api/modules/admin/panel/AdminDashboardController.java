package pe.facturacion.api.modules.admin.panel;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminFormat;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.health.HealthChecks;
import pe.facturacion.api.modules.ops.OpsTasks.RevisionCertificado;
import pe.facturacion.api.modules.ops.Scheduler;
import pe.facturacion.api.shared.queue.JobQueue;
import pe.facturacion.api.shared.time.Clock;

/** Tablero y salud del panel ({@code GET /api/admin/dashboard} y {@code GET /api/admin/health}). */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Panel de administración · tablero")
public class AdminDashboardController {

    /** Sin tareas en este tiempo el planificador se reporta con error (la más frecuente corre cada 5 min). */
    static final Duration SCHEDULER_STALE = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;
    private final AdminCertificates certificates;
    private final HealthChecks health;
    private final JobQueue queue;
    private final StringRedisTemplate redis;
    private final boolean schedulerEnabled;
    private final int failedJobsThreshold;
    private final AdminDashboardMetrics metrics;

    public AdminDashboardController(JdbcTemplate jdbc, AdminCertificates certificates, HealthChecks health, JobQueue queue,
            StringRedisTemplate redis, AdminDashboardMetrics metrics, @Value("${app.scheduler.enabled:true}") boolean schedulerEnabled,
            @Value("${app.ops.jobs-fallidos-umbral:1}") int failedJobsThreshold) {
        this.jdbc = jdbc;
        this.certificates = certificates;
        this.health = health;
        this.queue = queue;
        this.redis = redis;
        this.schedulerEnabled = schedulerEnabled;
        this.failedJobsThreshold = failedJobsThreshold;
        this.metrics = metrics;
    }

    @Operation(summary = "Tablero: empresas, comprobantes del mes (Lima), pendientes, trabajos fallidos, certificados y métricas globales "
            + "(ventas, por día, por tipo, por plan / régimen / entorno, estado SUNAT, top empresas, últimos envíos)")
    @AdminPermission(AdminPermissions.DASHBOARD_VIEW)
    @GetMapping("/dashboard")
    public ResponseEntity<Object> dashboard() {
        Map<String, Object> t = jdbc.queryForMap("SELECT count(*) FILTER (WHERE is_active) AS active, count(*) FILTER (WHERE NOT is_active) AS inactive, "
                + "count(*) FILTER (WHERE environment = 'production') AS production, count(*) FILTER (WHERE environment = 'beta') AS beta "
                + "FROM tenants WHERE deleted_at IS NULL");
        var range = AdminDocuments.monthRange(YearMonth.from(Clock.todayLima()));
        List<Object> args = new ArrayList<>();
        String month = AdminDocuments.union("count(*) AS n, count(*) FILTER (WHERE sunat_status = 'rechazado') AS r",
                "fecha_emision >= ? AND fecha_emision < ?", List.of(range[0], range[1]), args);
        Map<String, Object> docs = jdbc.queryForMap("SELECT coalesce(sum(n), 0) AS n, coalesce(sum(r), 0) AS r FROM (" + month + ") x",
                args.toArray());
        List<Object> pArgs = new ArrayList<>();
        String pending = AdminDocuments.union("count(*) AS n", "sunat_status IN ('pendiente', 'enviado')", List.of(), pArgs);
        Long pendingNow = jdbc.queryForObject("SELECT coalesce(sum(n), 0) FROM (" + pending + ") x", Long.class, pArgs.toArray());
        Long failed = jdbc.queryForObject("SELECT count(*) FROM failed_jobs", Long.class);

        List<AdminCertificates.Row> certs = certificates.activeTenants(certificates.defaultDays());
        long expiring = certs.stream().filter(r -> RevisionCertificado.POR_VENCER.equals(r.status())).count();
        long broken = certs.stream().filter(r -> RevisionCertificado.ROTO.equals(r.status()) || RevisionCertificado.VENCIDO.equals(r.status()))
                .count();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenantsActive", AdminFormat.longOrNull(t.get("active")));
        data.put("tenantsInactive", AdminFormat.longOrNull(t.get("inactive")));
        data.put("tenantsProduction", AdminFormat.longOrNull(t.get("production")));
        data.put("tenantsBeta", AdminFormat.longOrNull(t.get("beta")));
        data.put("documentsThisMonth", AdminFormat.longOrNull(docs.get("n")));
        data.put("rejectedThisMonth", AdminFormat.longOrNull(docs.get("r")));
        data.put("pendingNow", pendingNow == null ? 0 : pendingNow);
        data.put("failedJobs", failed == null ? 0 : failed);
        data.put("certificatesExpiring", expiring);
        data.put("certificatesBroken", broken);
        // Métricas globales de la referencia (caché de 60 s): se agregan sin cambiar las claves anteriores
        data.putAll(metrics.get());
        return AdminResponse.ok(data);
    }

    @Operation(summary = "Salud: base de datos, Redis, cola, planificador y SUNAT (circuit breaker)")
    @AdminPermission(AdminPermissions.HEALTH_VIEW)
    @GetMapping("/health")
    public ResponseEntity<Object> health() {
        Map<String, HealthChecks.Check> byKey = new LinkedHashMap<>();
        for (HealthChecks.Check c : health.ejecutar()) {
            byKey.put(c.clave(), c);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("database", component(byKey.get("salud-bd")));
        data.put("redis", component(byKey.get("salud-redis")));
        data.put("queue", queueStatus());
        data.put("scheduler", schedulerStatus());
        Map<String, Object> sunat = new LinkedHashMap<>();
        sunat.put("beta", component(byKey.get("salud-sunat-beta")));
        sunat.put("production", component(byKey.get("salud-sunat-produccion")));
        data.put("sunat", sunat);
        data.put("storage", component(byKey.get("salud-storage")));
        data.put("disk", component(byKey.get("salud-disco")));
        return AdminResponse.ok(data);
    }

    private Map<String, Object> queueStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        Long failed;
        try {
            failed = jdbc.queryForObject("SELECT count(*) FROM failed_jobs", Long.class);
        } catch (RuntimeException e) {
            failed = null;
        }
        Long pending = 0L;
        try {
            for (String q : queue.queues()) {
                pending += queue.size(q);
            }
        } catch (RuntimeException e) {
            pending = null;
        }
        m.put("pending", pending);
        m.put("failed", failed);
        if (pending == null || failed == null) {
            m.put("status", "error");
            m.put("message", "No se pudo leer el estado de la cola.");
        } else if (failed >= Math.max(1, failedJobsThreshold)) {
            m.put("status", "error");
            m.put("message", failed + " trabajo(s) fallido(s): revisa y reintenta desde Trabajos fallidos.");
        } else {
            m.put("status", "ok");
            m.put("message", pending + " trabajo(s) en cola.");
        }
        return m;
    }

    private Map<String, Object> schedulerStatus() {
        if (!schedulerEnabled) {
            return status("error", "El planificador está desactivado en esta instancia (SCHEDULER_ENABLED=false).");
        }
        String last;
        try {
            last = redis.opsForValue().get(Scheduler.HEARTBEAT_KEY);
        } catch (RuntimeException e) {
            return status("error", "No se pudo leer el latido del planificador (Redis).");
        }
        if (last == null) {
            return status("error", "Sin tareas ejecutadas en la última hora.");
        }
        try {
            Instant at = Instant.parse(last);
            if (at.isBefore(Clock.now().minus(SCHEDULER_STALE))) {
                return status("error", "La última tarea corrió el " + AdminFormat.instant(at) + ".");
            }
            return status("ok", "Última tarea: " + AdminFormat.instant(at) + ".");
        } catch (DateTimeParseException e) {
            return status("error", "Latido del planificador ilegible.");
        }
    }

    /** {@code {status: ok | error, message}} de un chequeo de {@link HealthChecks} (aviso cuenta como error). */
    private static Map<String, Object> component(HealthChecks.Check c) {
        if (c == null) {
            return status("error", "No configurado.");
        }
        String message = c.detalle() == null || c.detalle().isEmpty() ? ("ok".equals(c.estado()) ? "Operativo." : c.estado()) : c.detalle();
        return status("ok".equals(c.estado()) ? "ok" : "error", message);
    }

    private static Map<String, Object> status(String status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("message", message);
        return m;
    }
}
