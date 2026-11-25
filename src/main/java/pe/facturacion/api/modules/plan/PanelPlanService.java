package pe.facturacion.api.modules.plan;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.facturacion.api.modules.plan.domain.Plan;
import pe.facturacion.api.modules.plan.domain.PlanRepository;
import pe.facturacion.api.modules.plan.domain.Subscription;
import pe.facturacion.api.modules.plan.domain.SubscriptionRepository;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.time.Clock;

/**
 * El plan que fija el panel de administración es el plan efectivo (Docs/api-sunat-panel-admin-diseno.md §12.2).
 *
 * <p>{@link #assign} ({@code PUT /api/admin/tenants/{id}/plan} y {@code POST /api/admin/tenants}):
 * <ol>
 *   <li>Cancela las reducciones programadas ({@code pending}).</li>
 *   <li>Si la suscripción vigente ya es de ese plan, está {@code active}, no tiene cancelación pedida, no venció y se
 *       renueva sin cobro (la del panel o un plan gratuito), se <b>conserva</b> con su periodo. Si no, se cancelan las
 *       vigentes ({@code active}, {@code trialing}, {@code past_due}) y se <b>crea</b> una {@code active} con
 *       {@code payment_gateway = 'admin'} (como el {@code asignarPlan} de la referencia), desde hoy y sin vencimiento
 *       (o {@code duration_days} del plan si lo tiene).</li>
 *   <li>Escribe en la empresa {@code plan}, el cupo del panel ({@code documents_month_override}, {@code null} si es
 *       igual al del plan), la copia del cupo efectivo ({@code max_documents_month}) y el modo de emisión.</li>
 * </ol>
 * Nunca hace cobros. La suscripción propia de la empresa ({@code /api/v1/suscripcion}) sigue funcionando: si la
 * empresa cambia o cancela su plan, ese plan pasa a regir con su cupo (ver {@link SubscriptionService}).
 */
@Service
public class PanelPlanService {

    /** {@code subscriptions.payment_gateway} de una suscripción asignada desde el panel (sin cobro). */
    public static final String GATEWAY_PANEL = "admin";
    static final List<String> VIGENTES = List.of(Subscription.STATUS_ACTIVE, Subscription.STATUS_TRIALING);
    static final List<String> A_CANCELAR = List.of(Subscription.STATUS_ACTIVE, Subscription.STATUS_TRIALING, Subscription.STATUS_PAST_DUE);

    /** Error de negocio del panel (plan inexistente). */
    public static class PanelPlanException extends RuntimeException {
        public PanelPlanException(String message) {
            super(message);
        }
    }

    /** Qué pasó con la suscripción. */
    public enum Action {
        /** La vigente ya era de ese plan y se conservó con su periodo. */
        CONSERVADA,
        /** Se creó una suscripción del panel (y se cancelaron las vigentes). */
        CREADA,
        /** Plan Free sin fila en {@code plans}: solo se cancelaron las vigentes (rige el Free incorporado). */
        CANCELADA;

        public String code() {
            return name().toLowerCase();
        }
    }

    /** Estado de plan y cupo de una empresa en un momento (para la auditoría y el panel). */
    public record Snapshot(String plan, int maxDocumentsMonth, Integer override, String emissionMode, Map<String, Object> subscription) {

        /** Metadatos de auditoría (snake_case, como el resto de {@code audit_logs}). */
        public Map<String, Object> toMeta() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("plan", plan);
            m.put("max_documents_month", maxDocumentsMonth);
            m.put("cupo_panel", override);
            m.put("emission_mode", emissionMode);
            m.put("suscripcion", subscription);
            return m;
        }
    }

    /** Resultado de {@link #assign}. */
    public record Result(Snapshot before, Snapshot after, Action action, int pendingCancelled, List<Long> cancelledIds) {
    }

    /** Plan efectivo y cupo de una empresa, para el detalle del panel. */
    public record State(Plan effectivePlan, Subscription current, Subscription pending, Integer override, int planDefault,
            int effectiveLimit) {
    }

    private final PlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final JdbcTemplate jdbc;

    public PanelPlanService(PlanRepository plans, SubscriptionRepository subscriptions, JdbcTemplate jdbc) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.jdbc = jdbc;
    }

    /**
     * Fija plan, cupo y modo de emisión desde el panel y sincroniza la suscripción (ver la clase).
     *
     * @param requestedQuota cupo mensual pedido (≥ 0, ya validado) o {@code null} para usar el del plan
     * @param emissionMode   {@code plan} / {@code unlimited} (ya validado)
     */
    @Transactional
    public Result assign(Tenant tenant, String planSlug, Long requestedQuota, String emissionMode) {
        Optional<Plan> row = plans.findBySlug(planSlug);
        if (row.isEmpty() && !"free".equals(planSlug)) {
            throw new PanelPlanException("No existe el plan " + planSlug + " en la tabla de planes.");
        }
        long tenantId = tenant.getId();
        // Bloquea la empresa: un cambio de plan a la vez (panel o /api/v1)
        Map<String, Object> cols = jdbc.queryForMap("SELECT plan, max_documents_month, documents_month_override, emission_mode "
                + "FROM tenants WHERE id = ? FOR UPDATE", tenantId);
        Subscription current = subscriptions.findFirstByTenantIdAndStatusInOrderByIdDesc(tenantId, VIGENTES).orElse(null);
        Snapshot before = new Snapshot(String.valueOf(cols.get("plan")), ((Number) cols.get("max_documents_month")).intValue(),
                cols.get("documents_month_override") == null ? null : ((Number) cols.get("documents_month_override")).intValue(),
                String.valueOf(cols.get("emission_mode")), subscriptionMeta(current));

        LocalDateTime now = Clock.nowUtc().withNano(0);
        int pendingCancelled = 0;
        for (Subscription p : subscriptions.findByTenantIdAndStatusIn(tenantId, List.of(Subscription.STATUS_PENDING))) {
            p.setStatus(Subscription.STATUS_CANCELLED);
            p.setCancelledAt(now);
            subscriptions.save(p);
            pendingCancelled++;
        }

        Action action;
        List<Long> cancelled = new ArrayList<>();
        Subscription after = current;
        if (current != null && keepCurrent(current.getStatus(), current.getPlan().getSlug(), current.getCancelledAt(),
                current.getCurrentPeriodEnd(), current.getPaymentGateway(), cyclePrice(current), now, planSlug)) {
            action = Action.CONSERVADA;
        } else {
            List<Subscription> vigentes = new ArrayList<>(subscriptions.findByTenantIdAndStatusIn(tenantId, A_CANCELAR));
            vigentes.sort(Comparator.comparing(Subscription::getId));
            for (Subscription s : vigentes) {
                s.setStatus(Subscription.STATUS_CANCELLED);
                if (s.getCancelledAt() == null) {
                    s.setCancelledAt(now);
                }
                subscriptions.save(s);
                cancelled.add(s.getId());
            }
            if (row.isPresent()) {
                Subscription sub = new Subscription();
                sub.setTenantId(tenantId);
                sub.setPlan(row.get());
                sub.setStatus(Subscription.STATUS_ACTIVE);
                sub.setBillingCycle("monthly");
                sub.setCurrentPeriodStart(now);
                sub.setCurrentPeriodEnd(periodEnd(row.get().getDurationDays(), now));
                sub.setPaymentGateway(GATEWAY_PANEL);
                after = subscriptions.save(sub);
                action = Action.CREADA;
            } else {
                after = null;
                action = Action.CANCELADA;
            }
        }

        Plan effective = row.orElseGet(Plan::builtFree);
        Integer override = DocumentQuota.overrideFor(requestedQuota, effective);
        int limit = DocumentQuota.effective(override, effective);
        jdbc.update("UPDATE tenants SET plan = ?, max_documents_month = ?, documents_month_override = ?, emission_mode = ?, updated_at = ? "
                + "WHERE id = ?", planSlug, limit, override, emissionMode, now, tenantId);
        // La entidad queda igual a la fila (si el llamador la guarda después con JPA, no pisa estos valores)
        tenant.setPlan(planSlug);
        tenant.setMaxDocumentsMonth(limit);
        tenant.setDocumentsMonthOverride(override);
        tenant.setEmissionMode(emissionMode);
        Snapshot afterSnap = new Snapshot(planSlug, limit, override, emissionMode, subscriptionMeta(after));
        return new Result(before, afterSnap, action, pendingCancelled, cancelled);
    }

    /** Plan efectivo, suscripción vigente, reducción programada y cupo (para el detalle del panel). */
    @Transactional(readOnly = true)
    public State state(Tenant tenant) {
        Subscription current = subscriptions.findFirstByTenantIdAndStatusInOrderByIdDesc(tenant.getId(), VIGENTES).orElse(null);
        Plan effective = current != null ? current.getPlan() : plans.findBySlug("free").orElseGet(Plan::builtFree);
        Subscription pending = subscriptions.findByTenantIdAndStatusIn(tenant.getId(), List.of(Subscription.STATUS_PENDING)).stream()
                .max(Comparator.comparing(Subscription::getId)).orElse(null);
        Integer override = tenant.getDocumentsMonthOverride();
        return new State(effective, current, pending, override, DocumentQuota.planDefault(effective),
                DocumentQuota.effective(override, effective));
    }

    /**
     * Tras editar un plan en el panel ({@code PUT plans/{id}}): actualiza la copia {@code max_documents_month} de las
     * empresas que siguen ese plan sin cupo del panel (las que lo tienen en su suscripción vigente y, si es Free, las
     * que no tienen suscripción). Devuelve cuántas cambiaron.
     */
    @Transactional
    public int refreshMirrors(Plan plan) {
        int limit = DocumentQuota.planDefault(plan);
        boolean free = "free".equals(plan.getSlug());
        return jdbc.update("UPDATE tenants t SET max_documents_month = ?, updated_at = ? WHERE t.deleted_at IS NULL "
                + "AND t.documents_month_override IS NULL AND t.max_documents_month <> ? AND ("
                + "(SELECT s.plan_id FROM subscriptions s WHERE s.tenant_id = t.id AND s.status IN ('active', 'trialing') "
                + "ORDER BY s.id DESC LIMIT 1) = ? "
                + "OR (? AND NOT EXISTS (SELECT 1 FROM subscriptions s WHERE s.tenant_id = t.id AND s.status IN ('active', 'trialing'))))",
                limit, Clock.nowUtc().withNano(0), limit, plan.getId(), free);
    }

    // ------------------------------------------------------------------ reglas (sin acceso a datos)

    /**
     * ¿Se conserva la suscripción vigente al fijar {@code targetSlug} desde el panel? Solo si ya es de ese plan, está
     * {@code active} (no en prueba), sin cancelación pedida, sin vencer, y se renueva sin cobro (asignada por el panel
     * o con precio 0 en su ciclo): así no cae a {@code past_due} ni a Free por sí sola.
     */
    static boolean keepCurrent(String status, String slug, LocalDateTime cancelledAt, LocalDateTime periodEnd, String gateway,
            BigDecimal cyclePrice, LocalDateTime now, String targetSlug) {
        if (!Subscription.STATUS_ACTIVE.equals(status) || !targetSlug.equals(slug) || cancelledAt != null) {
            return false;
        }
        if (periodEnd != null && !periodEnd.isAfter(now)) {
            return false;
        }
        return GATEWAY_PANEL.equals(gateway) || cyclePrice == null || cyclePrice.signum() <= 0;
    }

    /** Fin del periodo de una suscripción del panel: {@code duration_days} del plan o, sin él, sin vencimiento. */
    static LocalDateTime periodEnd(Integer durationDays, LocalDateTime now) {
        return durationDays == null || durationDays <= 0 ? null : now.plusDays(durationDays);
    }

    private static BigDecimal cyclePrice(Subscription s) {
        Plan p = s.getPlan();
        return "yearly".equals(s.getBillingCycle()) ? p.getPriceYearly() : p.getPriceMonthly();
    }

    static Map<String, Object> subscriptionMeta(Subscription s) {
        if (s == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("plan", s.getPlan() == null ? null : s.getPlan().getSlug());
        m.put("estado", s.getStatus());
        m.put("origen", s.getPaymentGateway());
        m.put("fin_periodo", Clock.iso8601Utc(s.getCurrentPeriodEnd()));
        return m;
    }
}
