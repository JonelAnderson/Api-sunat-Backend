package pe.facturacion.api.modules.plan;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import pe.facturacion.api.modules.plan.domain.Plan;
import pe.facturacion.api.modules.plan.domain.Subscription;
import pe.facturacion.api.modules.plan.domain.SubscriptionRepository;
import pe.facturacion.api.modules.settings.PlatformSettings;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.time.Clock;

/**
 * Middleware {@code check.limit:sunat} ({@code CheckDocumentLimit} + {@code EmissionPolicyService}):
 * switch global → entorno beta → empresa ilimitada → plan (vencimiento y cupo mensual).
 * Sin la caché de 300 s del conteo: el cupo se evalúa siempre con datos frescos.
 *
 * <p>El switch global es el de la configuración del panel ({@code settings.emision_ilimitada_global}, como en API-PRO)
 * o la variable {@code EMISION_ILIMITADA_GLOBAL}: basta con que uno de los dos esté encendido.
 */
@Service
public class EmissionPolicy {

    private final PlanService plans;
    private final SubscriptionRepository subscriptions;
    private final JdbcTemplate jdbc;
    private final PlatformSettings settings;
    private final boolean globalUnlimited;

    public EmissionPolicy(PlanService plans, SubscriptionRepository subscriptions, JdbcTemplate jdbc, PlatformSettings settings,
            @Value("${app.emision-ilimitada-global:false}") boolean globalUnlimited) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.jdbc = jdbc;
        this.settings = settings;
        this.globalUnlimited = globalUnlimited;
    }

    /** Interruptor del entorno ({@code EMISION_ILIMITADA_GLOBAL}); el panel lo muestra pero no lo puede apagar. */
    public boolean globalUnlimitedByEnvironment() {
        return globalUnlimited;
    }

    /**
     * Por qué la empresa emite sin cupo: {@code global} (switch del panel o del entorno), {@code beta} o
     * {@code unlimited} (modo de emisión de la empresa); {@code null} si se le aplica el cupo del mes.
     */
    public String exemptReason(Tenant tenant) {
        if (globalUnlimited || settings.emisionIlimitadaGlobal()) {
            return "global";
        }
        if ("beta".equals(tenant.getEnvironment())) {
            return "beta";
        }
        if (Tenant.EMISSION_UNLIMITED.equals(tenant.getEmissionMode())) {
            return "unlimited";
        }
        return null;
    }

    /** {@code null} si puede emitir; si no, la respuesta 403/429 de API-PRO. */
    public ResponseEntity<Object> deny(Tenant tenant) {
        if (tenant == null || exemptReason(tenant) != null) {
            return null;
        }
        Plan plan = plans.getActivePlan(tenant);
        Subscription sub = subscriptions
                .findFirstByTenantIdAndStatusInOrderByIdDesc(tenant.getId(), List.of(Subscription.STATUS_ACTIVE, Subscription.STATUS_TRIALING))
                .orElse(null);
        if (sub != null && sub.getCurrentPeriodEnd() != null && sub.getCurrentPeriodEnd().isBefore(Clock.nowUtc())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("estado", "error");
            body.put("mensaje", "Tu suscripción venció. Renueva el plan para seguir emitiendo comprobantes.");
            body.put("codigo_error", "suscripcion_vencida");
            body.put("plan_actual", plan.getSlug());
            body.put("recurso", "documents_month");
            return ResponseEntity.status(403).body(body);
        }
        // Cupo del panel (V9) o, sin él, el del plan efectivo (DocumentQuota)
        int max = DocumentQuota.effective(tenant.getDocumentsMonthOverride(), plan);
        if (max == DocumentQuota.UNLIMITED) {
            return null;
        }
        int used = countDocuments(tenant);
        if (used < max) {
            return null;
        }
        String message = "Has alcanzado el limite de documentos SUNAT (" + used + "/" + max + " por mes).";
        Map<String, Object> next = null;
        if ("free".equals(plan.getSlug())) {
            message += " Actualiza a Pro por S/49/mes para más documentos.";
            next = nextPlan("pro", 49);
        } else if ("pro".equals(plan.getSlug())) {
            message += " Actualiza a Business por S/99/mes para documentos ilimitados.";
            next = nextPlan("business", 99);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "error");
        body.put("mensaje", message);
        body.put("codigo_error", "limite_alcanzado");
        body.put("plan_actual", plan.getSlug());
        body.put("recurso", "documents_month");
        body.put("actual", used);
        body.put("limite", max);
        body.put("mejora_plan", next);
        return ResponseEntity.status(429).body(body);
    }

    private static Map<String, Object> nextPlan(String slug, int price) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", slug);
        m.put("price", price);
        m.put("currency", "PEN");
        m.put("trial_days", 14);
        return m;
    }

    /** Lo que cuenta el cupo en el mes en curso (lo muestra el panel junto al cupo efectivo). */
    public int usedThisMonth(Tenant tenant) {
        return countDocuments(tenant);
    }

    /** Comprobantes SUNAT creados en el mes (UTC), incluidos los eliminados, como API-PRO. */
    int countDocuments(Tenant tenant) {
        LocalDate first = Clock.nowUtc().toLocalDate().withDayOfMonth(1);
        LocalDateTime start = first.atStartOfDay();
        LocalDateTime end = first.plusMonths(1).minusDays(1).atTime(23, 59, 59);
        String one = "SELECT COUNT(*) AS cnt FROM %s WHERE tenant_id = ? AND created_at BETWEEN ? AND ?";
        String sql = "SELECT COALESCE(SUM(cnt), 0) FROM (" + String.join(" UNION ALL ",
                List.of("invoices", "boletas", "credit_notes", "debit_notes", "dispatch_guides").stream()
                        .map(t -> String.format(one, t)).toList()) + ") AS counts";
        Object[] args = new Object[15];
        for (int i = 0; i < 5; i++) {
            args[i * 3] = tenant.getId();
            args[i * 3 + 1] = start;
            args[i * 3 + 2] = end;
        }
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n.intValue();
    }
}
