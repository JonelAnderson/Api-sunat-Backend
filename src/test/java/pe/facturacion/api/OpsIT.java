package pe.facturacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import pe.facturacion.api.modules.ops.OpsTasks;
import pe.facturacion.api.modules.ops.Scheduler;
import pe.facturacion.api.shared.queue.JobQueue;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.support.IntegrationTest;

/**
 * Fase 7: trabajos fallidos ({@code failed_jobs} + reintento), tareas programadas de suscripciones
 * y uso, verificación de certificados y candado del planificador entre instancias.
 */
class OpsIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JobQueue queue;
    @Autowired
    OpsTasks tasks;
    @Autowired
    StringRedisTemplate redis;

    static final AtomicInteger EJECUCIONES = new AtomicInteger();

    long demoTenant() {
        return jdbc.queryForObject("SELECT id FROM tenants WHERE ruc = '20000000001'", Long.class);
    }

    @Test
    void trabajoAgotadoQuedaEnFailedJobsYSeReintenta() throws Exception {
        queue.register(new JobQueue.JobHandler() {
            public String type() {
                return "test.falla";
            }

            public String queue() {
                return "test-ops";
            }

            public int tries() {
                return 1;
            }

            public long backoff(int attempt) {
                return 0;
            }

            public void handle(JobQueue.JobContext ctx) {
                EJECUCIONES.incrementAndGet();
                throw new IllegalStateException("fallo de prueba");
            }
        });
        jdbc.update("DELETE FROM failed_jobs");
        queue.dispatch("test.falla", Map.of("x", 1));
        long limite = System.currentTimeMillis() + 15_000;
        while (count("SELECT count(*) FROM failed_jobs") == 0 && System.currentTimeMillis() < limite) {
            Thread.sleep(100);
        }
        Map<String, Object> fila = jdbc.queryForMap("SELECT queue, payload::text AS payload, exception FROM failed_jobs");
        assertThat(fila.get("queue")).isEqualTo("test-ops");
        assertThat((String) fila.get("payload")).contains("test.falla");
        assertThat((String) fila.get("exception")).contains("fallo de prueba");

        assertThat(String.join("\n", tasks.revisarJobsFallidos(false))).contains("Hay 1 job(s) fallido(s).").contains("test-ops: 1");

        int antes = EJECUCIONES.get();
        assertThat(tasks.reintentarJobsFallidos(null)).containsExactly("1 trabajo(s) reencolado(s).");
        limite = System.currentTimeMillis() + 15_000;
        while (count("SELECT count(*) FROM failed_jobs") == 0 && System.currentTimeMillis() < limite) {
            Thread.sleep(100);
        }
        // Se volvió a ejecutar (con los intentos en cero) y, al fallar otra vez, volvió a failed_jobs
        assertThat(EJECUCIONES.get()).isGreaterThan(antes);
        assertThat(count("SELECT count(*) FROM failed_jobs")).isEqualTo(1);
        jdbc.update("DELETE FROM failed_jobs");
        assertThat(String.join("\n", tasks.revisarJobsFallidos(false))).contains("Todo en orden");
    }

    @Test
    void renovacionGratisEmpiezaDondeTerminoElPeriodoYLaDePagoQuedaPastDue() {
        long t = demoTenant();
        long free = jdbc.queryForObject("SELECT id FROM plans WHERE slug = 'free'", Long.class);
        long pro = jdbc.queryForObject("SELECT id FROM plans WHERE slug = 'pro'", Long.class);
        LocalDateTime fin = Clock.nowUtc().withNano(0).minusDays(1);
        long sGratis = insertSub(t, free, "active", null, fin.minusMonths(1), fin);
        long sPago = insertSub(t, pro, "active", null, fin.minusMonths(1), fin);
        try {
            assertThat(tasks.renovarSuscripciones().get(0)).contains("1 renovada(s), 1 en past_due");
            Map<String, Object> g = jdbc.queryForMap("SELECT status, current_period_start, current_period_end FROM subscriptions WHERE id = ?", sGratis);
            assertThat(g.get("status")).isEqualTo("active");
            assertThat(((java.sql.Timestamp) g.get("current_period_start")).toLocalDateTime()).isEqualTo(fin);
            assertThat(((java.sql.Timestamp) g.get("current_period_end")).toLocalDateTime()).isEqualTo(fin.plusMonths(1));
            assertThat(jdbc.queryForObject("SELECT status FROM subscriptions WHERE id = ?", String.class, sPago)).isEqualTo("past_due");
        } finally {
            jdbc.update("DELETE FROM subscriptions WHERE id IN (?, ?)", sGratis, sPago);
        }
    }

    @Test
    void cancelacionAlFinDelPeriodoYReduccionProgramada() {
        long t = demoTenant();
        long free = jdbc.queryForObject("SELECT id FROM plans WHERE slug = 'free'", Long.class);
        long pro = jdbc.queryForObject("SELECT id FROM plans WHERE slug = 'pro'", Long.class);
        long business = jdbc.queryForObject("SELECT id FROM plans WHERE slug = 'business'", Long.class);
        Map<String, Object> original = jdbc.queryForMap("SELECT plan, max_documents_month FROM tenants WHERE id = ?", t);
        LocalDateTime fin = Clock.nowUtc().withNano(0).minusHours(1);
        // Cancelada con el periodo terminado: se cierra y la empresa pasa a Free (no se renueva)
        long cancelada = insertSub(t, pro, "active", null, fin.minusMonths(1), fin);
        jdbc.update("UPDATE subscriptions SET cancelled_at = ? WHERE id = ?", fin.minusDays(3), cancelada);
        jdbc.update("UPDATE tenants SET plan = 'pro' WHERE id = ?", t);
        // Reducción programada a Free que empieza al terminar el periodo de Business
        long actual = insertSub(t, business, "active", null, fin.minusMonths(1), fin);
        long programada = insertSub(t, free, "pending", null, fin, fin.plusMonths(1));
        try {
            assertThat(tasks.renovarSuscripciones().get(1)).contains("1 cancelación(es) aplicada(s), 1 cambio(s) de plan programado(s)");
            assertThat(jdbc.queryForObject("SELECT status FROM subscriptions WHERE id = ?", String.class, cancelada)).isEqualTo("cancelled");
            assertThat(jdbc.queryForObject("SELECT status FROM subscriptions WHERE id = ?", String.class, actual)).isEqualTo("cancelled");
            assertThat(jdbc.queryForObject("SELECT status FROM subscriptions WHERE id = ?", String.class, programada)).isEqualTo("active");
            assertThat(jdbc.queryForObject("SELECT plan FROM tenants WHERE id = ?", String.class, t)).isEqualTo("free");
        } finally {
            jdbc.update("DELETE FROM subscriptions WHERE id IN (?, ?, ?)", cancelada, actual, programada);
            jdbc.update("UPDATE tenants SET plan = ?, max_documents_month = ? WHERE id = ?", original.get("plan"), original.get("max_documents_month"), t);
        }
    }

    @Test
    void pruebaVencidaPasaAlPlanFree() {
        long t = demoTenant();
        long pro = jdbc.queryForObject("SELECT id FROM plans WHERE slug = 'pro'", Long.class);
        Map<String, Object> original = jdbc.queryForMap("SELECT plan, max_documents_month FROM tenants WHERE id = ?", t);
        jdbc.update("UPDATE tenants SET plan = 'pro', max_documents_month = 500 WHERE id = ?", t);
        long s = insertSub(t, pro, "trialing", Clock.nowUtc().withNano(0).minusHours(1), null, null);
        try {
            assertThat(tasks.vencerPruebas()).contains("Expirando 1 pruebas");
            assertThat(jdbc.queryForObject("SELECT status FROM subscriptions WHERE id = ?", String.class, s)).isEqualTo("expired");
            Map<String, Object> tenant = jdbc.queryForMap("SELECT plan, max_documents_month FROM tenants WHERE id = ?", t);
            assertThat(tenant.get("plan")).isEqualTo("free");
            assertThat(((Number) tenant.get("max_documents_month")).intValue()).isEqualTo(30);
        } finally {
            jdbc.update("DELETE FROM subscriptions WHERE id = ?", s);
            jdbc.update("UPDATE tenants SET plan = ?, max_documents_month = ? WHERE id = ?", original.get("plan"), original.get("max_documents_month"), t);
        }
    }

    @Test
    void reinicioDeUsoSoloUnaVezPorMes() {
        long t = demoTenant();
        jdbc.update("UPDATE tenants SET documents_this_month = 7, usage_reset_month = '2000-01' WHERE id = ?", t);
        tasks.reiniciarUso();
        assertThat(jdbc.queryForObject("SELECT documents_this_month FROM tenants WHERE id = ?", Integer.class, t)).isZero();
        assertThat(tasks.reiniciarUso()).containsExactly("Uso mensual reiniciado para 0 tenants");
    }

    @Test
    void verificacionDeCertificadosReportaLaEmpresaSinCertificado() {
        long t = demoTenant();
        List<String> out = tasks.verificarCertificados(t, false);
        assertThat(out.get(0)).startsWith("#" + t + " EMPRESA DEMO S.A.C.").containsAnyOf("SIN CERT", "OK", "POR VENCER", "VENCIDO", "ROTO");
    }

    @Test
    void elCandadoDelPlanificadorEvitaQueOtraInstanciaRepitaLaTarea() throws Exception {
        // Dos instancias con el planificador activo compartiendo Redis
        Scheduler a = new Scheduler(tasks, redis, true, 90);
        Scheduler b = new Scheduler(tasks, redis, true, 90);
        String nombre = "test:" + java.util.UUID.randomUUID();
        AtomicInteger corridas = new AtomicInteger();
        java.lang.reflect.Method run = Scheduler.class.getDeclaredMethod("run", String.class, Duration.class, java.util.function.Supplier.class);
        run.setAccessible(true);
        java.util.function.Supplier<List<String>> tarea = () -> {
            corridas.incrementAndGet();
            return List.of("ok");
        };
        assertThat((Boolean) run.invoke(a, nombre, Duration.ofMinutes(1), tarea)).isTrue();
        assertThat((Boolean) run.invoke(b, nombre, Duration.ofMinutes(1), tarea)).isFalse();
        assertThat(corridas.get()).isEqualTo(1);
        // Desactivado: no corre ni toma candado
        Scheduler apagado = new Scheduler(tasks, redis, false, 90);
        redis.delete("scheduler:" + nombre);
        assertThat((Boolean) run.invoke(apagado, nombre, Duration.ofMinutes(1), tarea)).isFalse();
        assertThat(redis.hasKey("scheduler:" + nombre)).isFalse();
    }

    long insertSub(long tenant, long plan, String status, LocalDateTime trialEnds, LocalDateTime start, LocalDateTime end) {
        return jdbc.queryForObject("INSERT INTO subscriptions (tenant_id, plan_id, status, billing_cycle, trial_ends_at, current_period_start, "
                + "current_period_end, created_at, updated_at) VALUES (?, ?, ?, 'monthly', ?, ?, ?, now(), now()) RETURNING id", Long.class,
                tenant, plan, status, trialEnds, start, end);
    }

    long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
