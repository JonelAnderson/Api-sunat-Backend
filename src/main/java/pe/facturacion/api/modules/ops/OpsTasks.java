package pe.facturacion.api.modules.ops;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import pe.facturacion.api.modules.certificate.CertificateService;
import pe.facturacion.api.modules.certificate.CertificateStore;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.document.SendDocumentJob;
import pe.facturacion.api.modules.health.HealthChecks;
import pe.facturacion.api.modules.mail.MailService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.modules.ticket.TicketJobs;
import pe.facturacion.api.modules.ticket.TicketRepository;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.queue.JobQueue;
import pe.facturacion.api.shared.time.Clock;

/**
 * Tareas programadas y de operación de API-PRO ({@code routes/console.php} y sus comandos
 * Artisan). Cada una devuelve las líneas de su reporte (lo que el comando imprimía); las usa el
 * planificador ({@link Scheduler}) y la línea de comandos ({@code java -jar api.jar ops <tarea>}).
 *
 * <p>No se portan: particiones de {@code api_logs} (tabla simple con purga, D5), SIRE (fase
 * posterior), licencia, panel web y los comandos de reparación de datos propios de API-PRO.
 */
@Service
public class OpsTasks {

    private static final Logger log = LoggerFactory.getLogger(OpsTasks.class);

    private final JdbcTemplate jdbc;
    private final SendDocumentJob sendJob;
    private final TicketJobs ticketJobs;
    private final TenantRepository tenants;
    private final CertificateStore certificates;
    private final CertificateService certificateService;
    private final HealthChecks health;
    private final JobQueue queue;
    private final MailService mail;
    private final OpsAlert alert;
    private final int certDiasAviso;
    private final int umbralJobsFallidos;
    private final String appUrl;

    public OpsTasks(JdbcTemplate jdbc, SendDocumentJob sendJob, TicketJobs ticketJobs, TenantRepository tenants,
            CertificateStore certificates, CertificateService certificateService, HealthChecks health, JobQueue queue,
            MailService mail, OpsAlert alert, @Value("${app.ops.cert-dias-aviso:30}") int certDiasAviso,
            @Value("${app.ops.jobs-fallidos-umbral:1}") int umbralJobsFallidos, @Value("${app.url:http://localhost:8081}") String appUrl) {
        this.jdbc = jdbc;
        this.sendJob = sendJob;
        this.ticketJobs = ticketJobs;
        this.tenants = tenants;
        this.certificates = certificates;
        this.certificateService = certificateService;
        this.health = health;
        this.queue = queue;
        this.mail = mail;
        this.alert = alert;
        this.certDiasAviso = certDiasAviso;
        this.umbralJobsFallidos = umbralJobsFallidos;
        this.appUrl = appUrl;
    }

    // ---------------------------------------------------------------- SUNAT

    /**
     * {@code sunat:reintentar-pendientes}: reencola facturas y boletas en {@code enviado} sin
     * cambios desde hace {@code minutos} y creadas en los últimos {@code dias}. Nunca toca los
     * rechazados ni los {@code pendiente} (envío manual o por resumen).
     *
     * <p>Corrección: excluye las boletas con ticket (van en un resumen diario); API-PRO las
     * volvía a mandar por {@code sendBill} y SUNAT las recibía dos veces.
     */
    public List<String> reintentarPendientes(int minutos, int dias, List<String> estados) {
        LocalDateTime ahora = Clock.nowUtc();
        int total = 0;
        for (DocumentKind kind : List.of(DocumentKind.FACTURA, DocumentKind.BOLETA)) {
            String sql = "SELECT id FROM " + kind.table + " WHERE sunat_status IN (" + String.join(",", estados.stream().map(e -> "?").toList())
                    + ") AND updated_at <= ? AND created_at >= ? AND deleted_at IS NULL"
                    + (kind == DocumentKind.BOLETA ? " AND ticket IS NULL" : "") + " ORDER BY id";
            List<Object> args = new ArrayList<>(estados);
            args.add(ahora.minusMinutes(Math.max(1, minutos)));
            args.add(ahora.minusDays(Math.max(1, dias)));
            for (Long id : jdbc.queryForList(sql, Long.class, args.toArray())) {
                sendJob.dispatch(kind, id);
                total++;
            }
        }
        return total > 0 ? List.of("Reencolados " + total + " comprobante(s) a SUNAT.") : List.of();
    }

    /**
     * {@code summaries:poll-pending}: vuelve a consultar el ticket de los resúmenes sin estado
     * final. Los primeros 6 intentos cada 10 min y luego cada 4 h; más de 15 días o sin ticket
     * solo se reportan.
     */
    public List<String> pollResumenes(int limite) {
        List<String> out = new ArrayList<>();
        List<String> sinTicket = new ArrayList<>();
        List<String> abandonados = new ArrayList<>();
        int encolados = 0;
        LocalDateTime ahora = Clock.nowUtc();
        for (Map<String, Object> s : jdbc.queryForList("SELECT id, identifier, ticket, created_at, last_polled_at, poll_attempts FROM summaries "
                + "WHERE sunat_status NOT IN ('aceptado', 'rechazado') ORDER BY last_polled_at IS NULL DESC, last_polled_at LIMIT ?", limite)) {
            String identifier = (String) s.get("identifier");
            if (s.get("ticket") == null || String.valueOf(s.get("ticket")).isEmpty()) {
                sinTicket.add(identifier);
                continue;
            }
            LocalDateTime creado = ts(s.get("created_at"));
            if (creado != null && java.time.Duration.between(creado, ahora).toDays() > 15) {
                abandonados.add(identifier);
                continue;
            }
            LocalDateTime ultimo = ts(s.get("last_polled_at"));
            int intentos = ((Number) s.get("poll_attempts")).intValue();
            if (ultimo != null && (intentos < 6 ? ultimo.plusMinutes(10) : ultimo.plusHours(4)).isAfter(ahora)) {
                continue;
            }
            jdbc.update("UPDATE summaries SET last_polled_at = ?, poll_attempts = poll_attempts + 1 WHERE id = ?", ahora.withNano(0),
                    ((Number) s.get("id")).longValue());
            ticketJobs.dispatchCheck(TicketRepository.SUMMARIES, ((Number) s.get("id")).longValue());
            encolados++;
        }
        if (!sinTicket.isEmpty()) {
            log.warn("Resúmenes en enviado sin ticket: el envío no llegó a SUNAT identificadores={}", sinTicket);
            out.add(sinTicket.size() + " resumen(es) sin ticket — requieren revisión: " + String.join(", ", sinTicket));
        }
        if (!abandonados.isEmpty()) {
            log.warn("Resúmenes sin resolver tras 15 días identificadores={}", abandonados);
            out.add(abandonados.size() + " resumen(es) llevan más de 15 días sin resolver: " + String.join(", ", abandonados));
        }
        out.add(encolados + " consulta(s) de ticket encolada(s).");
        return out;
    }

    // ------------------------------------------------------------------ logs

    /** {@code logs:purge}: borra los registros de {@code api_logs} con más de {@code dias}. */
    public List<String> purgarLogs(int dias) {
        LocalDateTime corte = Clock.nowUtc().minusDays(dias);
        int borrados = jdbc.update("DELETE FROM api_logs WHERE created_at < ?", corte);
        return List.of("Eliminando api_logs anteriores a " + corte.toLocalDate() + " (" + dias + " días)...", "Registros eliminados: " + borrados);
    }

    // ---------------------------------------------------------- suscripciones

    /**
     * {@code ProcessRecurringPayments}: renueva las suscripciones activas vencidas. Sin cobro con
     * tarjeta (D2): un plan gratuito renueva el periodo; uno de pago queda {@code past_due} y se
     * avisa por correo, como API-PRO cuando no hay tarjeta registrada.
     *
     * <p>Corrección: el nuevo periodo empieza donde terminó el anterior (en API-PRO el inicio
     * quedaba igual al fin porque Carbon muta la fecha al sumar el mes).
     */
    public List<String> renovarSuscripciones() {
        LocalDateTime ahora = Clock.nowUtc().withNano(0);
        // Corrección: la cancelación vale al final del periodo (SubscriptionService.cancel); al llegar
        // ese momento la suscripción se cierra y la empresa pasa a Free
        List<Map<String, Object>> canceladas = jdbc.queryForList("SELECT id, tenant_id FROM subscriptions WHERE status IN ('active', 'trialing') "
                + "AND cancelled_at IS NOT NULL AND current_period_end <= ?", ahora);
        for (Map<String, Object> s : canceladas) {
            jdbc.update("UPDATE subscriptions SET status = 'cancelled', updated_at = ? WHERE id = ?", ahora, ((Number) s.get("id")).longValue());
            // El plan cambia sin el panel: rige el cupo del plan nuevo (se borra el cupo del panel, V9)
            jdbc.update("UPDATE tenants SET plan = 'free', max_documents_month = ?, documents_month_override = NULL, updated_at = ? "
                    + "WHERE id = ?", limiteDocumentos("free"), ahora, ((Number) s.get("tenant_id")).longValue());
        }
        // Corrección: la reducción de plan programada ("pending") se aplica al empezar su periodo
        // (API-PRO la creaba y nunca la activaba). Sin cobro con tarjeta (D2), un plan de pago queda past_due.
        List<Map<String, Object>> programadas = jdbc.queryForList("SELECT s.id, s.tenant_id, p.slug, p.price_monthly, p.price_yearly, s.billing_cycle "
                + "FROM subscriptions s JOIN plans p ON p.id = s.plan_id WHERE s.status = 'pending' AND s.current_period_start <= ? ORDER BY s.id", ahora);
        for (Map<String, Object> s : programadas) {
            long tenantId = ((Number) s.get("tenant_id")).longValue();
            BigDecimal precio = (BigDecimal) ("yearly".equals(s.get("billing_cycle")) ? s.get("price_yearly") : s.get("price_monthly"));
            jdbc.update("UPDATE subscriptions SET status = 'cancelled', cancelled_at = COALESCE(cancelled_at, ?), updated_at = ? "
                    + "WHERE tenant_id = ? AND status IN ('active', 'trialing', 'past_due')", ahora, ahora, tenantId);
            jdbc.update("UPDATE subscriptions SET status = ?, updated_at = ? WHERE id = ?", precio == null || precio.signum() <= 0 ? "active" : "past_due",
                    ahora, ((Number) s.get("id")).longValue());
            jdbc.update("UPDATE tenants SET plan = ?, max_documents_month = ?, documents_month_override = NULL, updated_at = ? WHERE id = ?",
                    s.get("slug"), limiteDocumentos((String) s.get("slug")), ahora, tenantId);
        }
        List<Map<String, Object>> subs = jdbc.queryForList("SELECT s.id, s.tenant_id, s.billing_cycle, s.current_period_end, "
                + "p.name AS plan_name, p.price_monthly, p.price_yearly FROM subscriptions s JOIN plans p ON p.id = s.plan_id "
                + "WHERE s.status = 'active' AND s.current_period_end <= ? AND s.cancelled_at IS NULL ORDER BY s.id", ahora);
        int renovadas = 0;
        int vencidas = 0;
        for (Map<String, Object> s : subs) {
            long id = ((Number) s.get("id")).longValue();
            try {
                boolean anual = "yearly".equals(s.get("billing_cycle"));
                BigDecimal monto = (BigDecimal) (anual ? s.get("price_yearly") : s.get("price_monthly"));
                if (monto == null || monto.signum() <= 0) {
                    LocalDateTime inicio = ts(s.get("current_period_end"));
                    LocalDateTime fin = anual ? inicio.plusYears(1) : inicio.plusMonths(1);
                    jdbc.update("UPDATE subscriptions SET status = 'active', current_period_start = ?, current_period_end = ?, updated_at = ? "
                            + "WHERE id = ?", inicio, fin, ahora, id);
                    renovadas++;
                    continue;
                }
                jdbc.update("UPDATE subscriptions SET status = 'past_due', updated_at = ? WHERE id = ?", ahora, id);
                vencidas++;
                tenants.findById(((Number) s.get("tenant_id")).longValue()).ifPresent(t ->
                        notificar(t, "Problema con tu pago", pagoFallidoHtml(t, (String) s.get("plan_name"),
                                "El cobro automático con tarjeta no está habilitado en esta API")));
            } catch (RuntimeException e) {
                log.error("Error al procesar pago recurrente para la suscripción {} error={}", id, e.getMessage());
            }
        }
        log.info("Procesando {} pagos recurrentes", subs.size());
        return List.of("Procesando " + subs.size() + " pagos recurrentes: " + renovadas + " renovada(s), " + vencidas + " en past_due.",
                canceladas.size() + " cancelación(es) aplicada(s), " + programadas.size() + " cambio(s) de plan programado(s) aplicado(s).");
    }

    /** {@code limits.documents_month} del plan (30 si no está definido). */
    private int limiteDocumentos(String slug) {
        List<String> limits = jdbc.queryForList("SELECT limits::text FROM plans WHERE slug = ?", String.class, slug);
        if (!limits.isEmpty() && limits.get(0) != null && PhpJson.decode(limits.get(0)) instanceof Map<?, ?> m
                && m.get("documents_month") instanceof Number n) {
            return n.intValue();
        }
        return 30;
    }

    /**
     * {@code CheckTrialExpiration}: avisa las pruebas que vencen en 3 días y pasa al plan Free las
     * vencidas (límite de documentos del plan Free, 30 si no está definido).
     */
    public List<String> vencerPruebas() {
        LocalDateTime ahora = Clock.nowUtc().withNano(0);
        int avisadas = 0;
        for (Map<String, Object> s : jdbc.queryForList("SELECT s.id, s.tenant_id, s.trial_ends_at, p.name AS plan_name FROM subscriptions s "
                + "JOIN plans p ON p.id = s.plan_id WHERE s.status = 'trialing' AND s.trial_ends_at BETWEEN ? AND ?", ahora, ahora.plusDays(3))) {
            Tenant t = tenants.findById(((Number) s.get("tenant_id")).longValue()).orElse(null);
            if (t != null) {
                notificar(t, "Tu prueba gratis termina en 3 días", pruebaHtml(t, (String) s.get("plan_name"), ts(s.get("trial_ends_at"))));
                avisadas++;
            }
        }
        List<Map<String, Object>> vencidas = jdbc.queryForList("SELECT id, tenant_id FROM subscriptions WHERE status = 'trialing' AND trial_ends_at < ?",
                ahora);
        int limiteFree = 30;
        List<String> limits = jdbc.queryForList("SELECT limits::text FROM plans WHERE slug = 'free'", String.class);
        if (!limits.isEmpty() && PhpJson.decode(limits.get(0)) instanceof Map<?, ?> m && m.get("documents_month") instanceof Number n) {
            limiteFree = n.intValue();
        }
        for (Map<String, Object> s : vencidas) {
            jdbc.update("UPDATE subscriptions SET status = 'expired', updated_at = ? WHERE id = ?", ahora, ((Number) s.get("id")).longValue());
            jdbc.update("UPDATE tenants SET plan = 'free', max_documents_month = ?, documents_month_override = NULL, updated_at = ? "
                    + "WHERE id = ?", limiteFree, ahora, ((Number) s.get("tenant_id")).longValue());
        }
        log.info("Expirando {} pruebas", vencidas.size());
        return List.of(avisadas + " aviso(s) de prueba por vencer.", "Expirando " + vencidas.size() + " pruebas");
    }

    /** {@code ResetMonthlyUsage}: reinicia los contadores de uso del mes. */
    public List<String> reiniciarUso() {
        String mes = Clock.nowUtc().toLocalDate().toString().substring(0, 7);
        int n = jdbc.update("UPDATE tenants SET documents_this_month = 0, ai_messages_this_month = 0, usage_reset_month = ? "
                + "WHERE usage_reset_month IS NULL OR usage_reset_month <> ?", mes, mes);
        log.info("Uso mensual reiniciado para {} tenants", n);
        return List.of("Uso mensual reiniciado para " + n + " tenants");
    }

    // ---------------------------------------------------------- operación

    /** {@code certificados:verificar}: que cada empresa pueda firmar (existe, tiene clave, no vence pronto). */
    public List<String> verificarCertificados(Long tenantId, boolean alertar) {
        List<String> out = new ArrayList<>();
        List<String> problemas = new ArrayList<>();
        List<Tenant> lista = tenantId != null ? tenants.findById(tenantId).map(List::of).orElse(List.of())
                : tenants.findAll(org.springframework.data.domain.Sort.by("id"));
        for (Tenant t : lista) {
            String[] r = revisarCertificado(t);
            out.add("#" + t.getId() + " " + t.getRazonSocial() + " [" + t.getEnvironment() + "]: " + r[0] + " — " + r[1]);
            if (!"OK".equals(r[0])) {
                problemas.add("#" + t.getId() + " " + t.getRazonSocial() + " [" + t.getEnvironment() + "]: " + r[0] + " — " + r[1]);
            }
        }
        if (!problemas.isEmpty()) {
            out.add(problemas.size() + " tenant(s) no pueden firmar comprobantes.");
            if (alertar) {
                alert.aviso("Certificados con problemas", problemas.size() + " empresa(s) con certificado vencido, por vencer o ilegible.",
                        Map.of("detalle", String.join(" | ", problemas)), "cert-problemas");
            }
        } else {
            out.add("Todos los certificados pueden firmar.");
        }
        return out;
    }

    /** Resultado de revisar el certificado de una empresa ({@code certificados:verificar} y el panel de administración). */
    public record RevisionCertificado(String estado, LocalDate vence, String mensaje) {

        public static final String OK = "OK";
        public static final String POR_VENCER = "POR_VENCER";
        public static final String VENCIDO = "VENCIDO";
        public static final String ROTO = "ROTO";
        public static final String SIN_CERTIFICADO = "SIN_CERTIFICADO";

        /** Etiqueta del reporte de la línea de comandos (se conserva la de siempre). */
        String etiqueta() {
            return switch (estado) {
                case POR_VENCER -> "POR VENCER";
                case SIN_CERTIFICADO -> "SIN CERT";
                default -> estado;
            };
        }
    }

    String[] revisarCertificado(Tenant t) {
        RevisionCertificado r = revisarCertificado(t, certDiasAviso);
        return new String[] {r.etiqueta(), r.mensaje()};
    }

    /** Días de aviso por defecto ({@code OPS_CERT_DIAS_AVISO}). */
    public int certDiasAviso() {
        return certDiasAviso;
    }

    /**
     * Que la empresa pueda firmar: hay archivo, se puede leer, tiene clave privada y no vence en {@code diasAviso}
     * días o menos. Una sola regla para la tarea programada, la línea de comandos y el panel.
     */
    public RevisionCertificado revisarCertificado(Tenant t, int diasAviso) {
        String pem;
        try {
            pem = certificates.load(t);
        } catch (RuntimeException e) {
            return new RevisionCertificado(RevisionCertificado.ROTO, null, "El certificado no se puede leer");
        }
        if (pem == null) {
            return new RevisionCertificado(RevisionCertificado.SIN_CERTIFICADO, null, "No hay archivo cargado");
        }
        if (!pem.contains("PRIVATE KEY")) {
            return new RevisionCertificado(RevisionCertificado.ROTO, null, "Sin clave privada — subieron el .cer en vez del .pfx");
        }
        CertificateService.Material m;
        try {
            m = certificateService.parse(pem);
        } catch (RuntimeException e) {
            return new RevisionCertificado(RevisionCertificado.ROTO, null, "El certificado no se puede leer");
        }
        LocalDate vence = m.certificate().getNotAfter().toInstant().atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate hoy = Clock.nowUtc().toLocalDate();
        if (vence.isBefore(hoy)) {
            return new RevisionCertificado(RevisionCertificado.VENCIDO, vence, "Venció el " + vence);
        }
        if (vence.isBefore(hoy.plusDays(diasAviso))) {
            return new RevisionCertificado(RevisionCertificado.POR_VENCER, vence, "Vence el " + vence);
        }
        return new RevisionCertificado(RevisionCertificado.OK, vence, "Vence el " + vence);
    }

    /** {@code ops:jobs-fallidos}: alerta si se acumulan trabajos en {@code failed_jobs}. */
    public List<String> revisarJobsFallidos(boolean alertar) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM failed_jobs", Long.class);
        long n = total == null ? 0 : total;
        if (n < umbralJobsFallidos) {
            return List.of("Jobs fallidos: " + n + " (umbral: " + umbralJobsFallidos + "). Todo en orden.");
        }
        List<String> out = new ArrayList<>();
        List<String> desglose = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT queue, count(*) AS total FROM failed_jobs GROUP BY queue ORDER BY total DESC")) {
            desglose.add(r.get("queue") + "=" + r.get("total"));
            out.add(r.get("queue") + ": " + r.get("total"));
        }
        out.add("Hay " + n + " job(s) fallido(s).");
        if (alertar) {
            List<String> ultimo = jdbc.queryForList("SELECT failed_at::text FROM failed_jobs ORDER BY id DESC LIMIT 1", String.class);
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("por_cola", String.join(", ", desglose));
            ctx.put("ultimo_fallo", ultimo.isEmpty() ? "" : ultimo.get(0));
            alert.critico("Jobs fallidos en cola", "Se acumulan " + n + " job(s) en failed_jobs. Revisa y reintenta (ops jobs-fallidos:reintentar).",
                    ctx, "jobs-fallidos");
        }
        return out;
    }

    /** {@code queue:retry}: reencola los trabajos fallidos (todos o uno). */
    public List<String> reintentarJobsFallidos(Long id) {
        int n = queue.retryFailed(id);
        return List.of(n + " trabajo(s) reencolado(s).");
    }

    /** {@code ops:salud}: BD, Redis, disco, almacenamiento y circuit breaker de SUNAT. */
    public List<String> revisarSalud(boolean alertar) {
        List<String> out = new ArrayList<>();
        List<HealthChecks.Check> checks = health.ejecutar();
        for (HealthChecks.Check c : checks) {
            out.add(c.nombre() + ": " + c.estado().toUpperCase() + (c.detalle().isEmpty() ? "" : " — " + c.detalle()));
            if (!"ok".equals(c.estado()) && alertar) {
                alert.enviar("falla".equals(c.estado()) ? OpsAlert.CRITICO : OpsAlert.AVISO, "Salud: " + c.nombre(),
                        c.detalle().isEmpty() ? "con problemas" : c.detalle(), Map.of(), c.clave());
            }
        }
        HealthChecks.Resumen r = HealthChecks.resumen(checks);
        out.add("ok".equals(r.overall()) ? "Todo saludable." : "Estado general: " + r.overall() + ".");
        return out;
    }

    // ----------------------------------------------------------------- correo

    /** Correo al usuario dueño de la empresa ({@code $tenant->user->email}), como los listeners de API-PRO. */
    private void notificar(Tenant t, String asunto, String html) {
        if (t.getUserId() == null) {
            return;
        }
        List<String> emails = jdbc.queryForList("SELECT email FROM users WHERE id = ?", String.class, t.getUserId());
        if (emails.isEmpty() || emails.get(0) == null || emails.get(0).isEmpty()) {
            return;
        }
        mail.send(emails.get(0), asunto, html);
    }

    private String pruebaHtml(Tenant t, String plan, LocalDateTime fin) {
        return layout("Tu prueba gratis termina en 3 días",
                "<p>Hola " + esc(t.getRazonSocial()) + ",</p>"
                + "<p>Tu período de prueba del plan <strong>" + esc(plan) + "</strong> finaliza el <strong>"
                + (fin == null ? "" : fin.toLocalDate()) + "</strong>.</p>"
                + "<p><strong>¿Qué pasa después?</strong><br/>Si no eliges un plan, tu cuenta pasará al plan Free con un límite menor de comprobantes.</p>"
                + "<p>Para elegir tu plan usa <code>POST /api/v1/suscripcion/cambiar-plan</code> (documentación: "
                + esc(appUrl) + "/api/docs).</p>");
    }

    private String pagoFallidoHtml(Tenant t, String plan, String motivo) {
        return layout("No pudimos procesar tu pago",
                "<p>Hola " + esc(t.getRazonSocial()) + ",</p>"
                + "<p>Hubo un problema al renovar tu suscripción al plan <strong>" + esc(plan) + "</strong>.</p>"
                + "<p><strong>Motivo:</strong> " + esc(motivo) + "</p>"
                + "<p>Para evitar la interrupción del servicio, comunícate con el administrador de la API para regularizar el pago.</p>"
                + "<p style=\"font-size: 13px; color: #94a3b8;\">Si el pago no se regulariza en los próximos 7 días, tu cuenta pasará al plan Free.</p>");
    }

    private static String layout(String titulo, String cuerpo) {
        return "<!DOCTYPE html><html><body style=\"font-family: Arial, sans-serif; color: #1e293b;\"><h2>" + esc(titulo) + "</h2>" + cuerpo
                + "<hr/><p style=\"font-size: 12px; color: #94a3b8;\">API SUNAT</p></body></html>";
    }

    private static String esc(Object v) {
        return v == null ? "" : String.valueOf(v).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static LocalDateTime ts(Object v) {
        if (v instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime();
        }
        return v instanceof LocalDateTime l ? l : null;
    }
}
