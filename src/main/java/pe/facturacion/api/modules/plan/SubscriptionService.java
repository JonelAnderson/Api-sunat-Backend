package pe.facturacion.api.modules.plan;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.facturacion.api.modules.plan.domain.Plan;
import pe.facturacion.api.modules.plan.domain.PlanRepository;
import pe.facturacion.api.modules.plan.domain.Subscription;
import pe.facturacion.api.modules.plan.domain.SubscriptionRepository;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.api.ApiException;
import pe.facturacion.api.shared.time.Clock;

/**
 * Acciones de suscripción de API-PRO ({@code CreateSubscriptionAction}, {@code ChangePlanAction},
 * {@code CancelSubscriptionAction}). El cobro con Culqi no se migra (decisión D2): una
 * suscripción de pago con token devuelve 422; sin token inicia la prueba de 14 días, igual que API-PRO.
 */
@Service
public class SubscriptionService {

    /** Error de negocio (RuntimeException en API-PRO → 422 con su mensaje). */
    public static class SubscriptionException extends RuntimeException {
        public SubscriptionException(String message) {
            super(message);
        }
    }

    private static final List<String> ACTIVE = List.of(Subscription.STATUS_ACTIVE, Subscription.STATUS_TRIALING);
    private static final List<String> CON_PENDIENTE = List.of(Subscription.STATUS_ACTIVE, Subscription.STATUS_TRIALING, Subscription.STATUS_PENDING);

    private final PlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final TenantRepository tenants;

    public SubscriptionService(PlanRepository plans, SubscriptionRepository subscriptions, TenantRepository tenants) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.tenants = tenants;
    }

    @Transactional
    public Subscription create(Tenant tenant, String planSlug, String billingCycle, String token, int trialDays) {
        Plan plan = activePlan(planSlug);
        String cycle = billingCycle == null || billingCycle.isEmpty() ? "monthly" : billingCycle;
        LocalDateTime now = Clock.nowUtc();

        // Corrección: también la reducción programada ("pending"), que API-PRO dejaba viva
        for (Subscription s : subscriptions.findByTenantIdAndStatusIn(tenant.getId(), CON_PENDIENTE)) {
            s.setStatus(Subscription.STATUS_CANCELLED);
            s.setCancelledAt(now);
            subscriptions.save(s);
        }

        Subscription sub = new Subscription();
        sub.setTenantId(tenant.getId());
        sub.setPlan(plan);
        sub.setBillingCycle(cycle);
        boolean hasToken = token != null && !token.isEmpty();
        if (trialDays > 0 || (!"free".equals(plan.getSlug()) && !hasToken)) {
            int days = trialDays > 0 ? trialDays : 14;
            sub.setStatus(Subscription.STATUS_TRIALING);
            sub.setTrialEndsAt(now.plusDays(days));
            sub.setCurrentPeriodStart(now);
            sub.setCurrentPeriodEnd(now.plusDays(days));
        } else if (plan.getPriceMonthly().signum() > 0 && hasToken) {
            throw new SubscriptionException("El cobro con tarjeta (Culqi) no está habilitado en esta API. "
                    + "Solicite la suscripción sin token para iniciar el periodo de prueba.");
        } else {
            sub.setStatus(Subscription.STATUS_ACTIVE);
            sub.setCurrentPeriodStart(now);
            sub.setCurrentPeriodEnd(now.plusYears(1));
        }
        subscriptions.save(sub);

        tenant.setPlan(plan.getSlug());
        // La empresa eligió su plan: rige el cupo de ese plan (se borra el cupo del panel, §12.2)
        tenant.setDocumentsMonthOverride(null);
        tenant.setMaxDocumentsMonth(DocumentQuota.effective(null, plan));
        tenants.save(tenant);
        return sub;
    }

    /** Mejora: inmediata (nueva suscripción). Reducción: suscripción "pending" al final del periodo actual. */
    @Transactional
    public Subscription changePlan(Tenant tenant, String planSlug, String billingCycle, String token) {
        Plan newPlan = activePlan(planSlug);
        Subscription current = subscriptions.findFirstByTenantIdAndStatusInOrderByCreatedAtDescIdDesc(tenant.getId(), ACTIVE).orElse(null);
        Plan currentPlan = current == null ? null : current.getPlan();
        // Corrección: cambiar al mismo plan creaba una suscripción "pending" idéntica
        if (currentPlan != null && currentPlan.getSlug().equals(newPlan.getSlug())) {
            throw ApiException.of(422, "La suscripción ya está en el plan " + newPlan.getName() + ".");
        }
        if (current == null || (currentPlan != null && newPlan.getSortOrder() > currentPlan.getSortOrder())) {
            return create(tenant, planSlug, billingCycle, token, 0);
        }
        cancelarPendientes(tenant.getId(), Clock.nowUtc());
        Subscription pending = new Subscription();
        pending.setTenantId(tenant.getId());
        pending.setPlan(newPlan);
        pending.setStatus(Subscription.STATUS_PENDING);
        pending.setBillingCycle(current.getBillingCycle());
        LocalDateTime start = current.getCurrentPeriodEnd() == null ? Clock.nowUtc() : current.getCurrentPeriodEnd();
        pending.setCurrentPeriodStart(start);
        pending.setCurrentPeriodEnd("yearly".equals(current.getBillingCycle()) ? start.plusYears(1) : start.plusMonths(1));
        return subscriptions.save(pending);
    }

    /**
     * Cancela al final del periodo; si el periodo ya terminó, degrada a "free" de inmediato.
     *
     * <p>Corrección: API-PRO pasaba la suscripción a "cancelled" al instante (plan efectivo free y
     * cupos de free) aunque respondía "acceso hasta el final del periodo". Aquí la suscripción sigue
     * vigente con {@code cancelled_at} hasta su fin (la renovación no la renueva y la tarea de
     * renovación o de fin de prueba la cierra), y se cancela la reducción programada.
     */
    @Transactional
    public Subscription cancel(Tenant tenant) {
        Subscription sub = subscriptions.findFirstByTenantIdAndStatusInOrderByCreatedAtDescIdDesc(tenant.getId(), ACTIVE)
                .orElseThrow(() -> ApiException.of(404, "No hay suscripción activa para cancelar."));
        if (sub.getCancelledAt() != null) {
            throw ApiException.of(422, "La suscripción ya está cancelada; termina el " + Clock.iso8601Utc(sub.getCurrentPeriodEnd()) + ".");
        }
        LocalDateTime now = Clock.nowUtc();
        cancelarPendientes(tenant.getId(), now);
        sub.setCancelledAt(now);
        LocalDateTime periodEnd = sub.getCurrentPeriodEnd() == null ? now : sub.getCurrentPeriodEnd();
        if (now.isBefore(periodEnd)) {
            return subscriptions.save(sub);
        }
        sub.setStatus(Subscription.STATUS_CANCELLED);
        subscriptions.save(sub);
        Plan free = plans.findBySlug("free").orElse(null);
        tenant.setPlan("free");
        tenant.setDocumentsMonthOverride(null);
        tenant.setMaxDocumentsMonth(DocumentQuota.effective(null, free == null ? Plan.builtFree() : free));
        tenants.save(tenant);
        return sub;
    }

    private void cancelarPendientes(long tenantId, LocalDateTime now) {
        for (Subscription p : subscriptions.findByTenantIdAndStatusIn(tenantId, List.of(Subscription.STATUS_PENDING))) {
            p.setStatus(Subscription.STATUS_CANCELLED);
            p.setCancelledAt(now);
            subscriptions.save(p);
        }
    }

    private Plan activePlan(String slug) {
        return plans.findBySlug(slug).filter(Plan::isActive).orElseThrow(ApiException::notFound);
    }
}
