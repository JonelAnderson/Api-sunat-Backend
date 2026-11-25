package pe.facturacion.api.modules.plan;

import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.facturacion.api.modules.plan.domain.Plan;
import pe.facturacion.api.modules.plan.domain.PlanRepository;
import pe.facturacion.api.modules.plan.domain.Subscription;
import pe.facturacion.api.modules.plan.domain.SubscriptionRepository;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.time.Clock;

/** Equivalente a {@code App\Services\Plan\PlanService} de API-PRO. */
@Service
public class PlanService {

    private static final List<String> ACTIVE_STATUSES = List.of(Subscription.STATUS_ACTIVE, Subscription.STATUS_TRIALING);
    /** {@code TIER_ORDER} de API-PRO (no contempla "pro": queda en 0, igual que el original). */
    private static final Map<String, Integer> TIER_ORDER = Map.of("free", 0, "starter", 1, "growth", 2, "business", 3);

    private final PlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final TenantRepository tenants;
    private final JdbcTemplate jdbc;

    public PlanService(PlanRepository plans, SubscriptionRepository subscriptions, TenantRepository tenants, JdbcTemplate jdbc) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.tenants = tenants;
        this.jdbc = jdbc;
    }

    /** Plan de la suscripción activa (active/trialing) o, si no hay, el plan "free". */
    @Transactional(readOnly = true)
    public Plan getActivePlan(Tenant tenant) {
        return subscriptions.findFirstByTenantIdAndStatusInOrderByIdDesc(tenant.getId(), ACTIVE_STATUSES)
                .map(Subscription::getPlan)
                .orElseGet(() -> plans.findBySlug("free").orElseGet(Plan::builtFree));
    }

    /** Middleware {@code usage.headers} ({@code UsageWarningHeader}). */
    public void applyUsageHeaders(Tenant tenant, HttpServletResponse response) {
        Plan plan = getActivePlan(tenant);

        int docLimit = DocumentQuota.headerLimit(tenant.getDocumentsMonthOverride(), plan);
        if (docLimit != -1) {
            int current = tenant.getDocumentsThisMonth();
            response.setHeader("X-Usage-Documents", current + "/" + docLimit);
            double pct = docLimit > 0 ? ((double) current / docLimit) * 100 : 0;
            if (pct >= 80) {
                response.setHeader("X-Usage-Warning", "approaching_limit");
                response.setHeader("X-Usage-Remaining", String.valueOf(Math.max(0, docLimit - current)));
            }
        }

        int aiLimit = plan.getLimit("ai_messages_month", 10);
        if (aiLimit != -1) {
            response.setHeader("X-Usage-AI", tenant.getAiMessagesThisMonth() + "/" + aiLimit);
        }

        response.setHeader("X-Plan", plan.getSlug());
    }

    /** {@code getUsageReport()}: reinicia los contadores si cambió el mes. */
    @Transactional
    public Map<String, Object> getUsageReport(Tenant tenant) {
        ensureUsageResetCurrent(tenant);
        Plan plan = getActivePlan(tenant);
        Map<String, Object> limits = plan.getLimits() == null ? Map.of() : plan.getLimits();
        // Cupo del panel (V9): si existe, es el límite que se informa; sin él, los valores de siempre
        Integer override = tenant.getDocumentsMonthOverride();
        int tier = TIER_ORDER.getOrDefault(plan.getSlug(), 0);

        Map<String, Object> planInfo = new LinkedHashMap<>();
        planInfo.put("slug", plan.getSlug());
        planInfo.put("tier", tier);
        planInfo.put("price", plan.getPriceMonthly().doubleValue());

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("documents_month", map("current", tenant.getDocumentsThisMonth(),
                "limit", DocumentQuota.reportLimit(override, limits), "unlimited", DocumentQuota.reportUnlimited(override, limits)));
        usage.put("ai_messages_month", map("current", tenant.getAiMessagesThisMonth(),
                "limit", limits.getOrDefault("ai_messages_month", 10), "unlimited", isMinusOne(limits.getOrDefault("ai_messages_month", 0))));
        usage.put("team_members", map("limit", limits.getOrDefault("team_members", 1)));
        Integer sucursales = jdbc.queryForObject("SELECT count(*) FROM sucursales WHERE tenant_id = ?", Integer.class, tenant.getId());
        usage.put("sucursales", map("current", sucursales == null ? 0 : sucursales, "limit", limits.getOrDefault("sucursales", 1)));
        usage.put("productos", map("limit", limits.getOrDefault("productos", 100), "unlimited", isMinusOne(limits.getOrDefault("productos", 100))));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("plan", planInfo);
        report.put("usage", usage);
        report.put("features", plan.getFeatures() == null ? List.of() : plan.getFeatures());
        report.put("next_upgrade", tier < 3 ? nextUpgrade(tier) : null);
        return report;
    }

    /** {@code incrementUsage($tenant, 'documents')}: suma atómica sobre el contador del mes. */
    @Transactional
    public void incrementDocumentUsage(Tenant tenant) {
        ensureUsageResetCurrent(tenant);
        jdbc.update("UPDATE tenants SET documents_this_month = documents_this_month + 1, updated_at = ? WHERE id = ?",
                Clock.nowUtc().withNano(0), tenant.getId());
        tenant.setDocumentsThisMonth(tenant.getDocumentsThisMonth() + 1);
    }

    private Map<String, Object> nextUpgrade(int currentTier) {
        return plans.findByActiveTrueOrderBySortOrderAsc().stream()
                .filter(p -> p.getSortOrder() > currentTier)
                .findFirst()
                .map(p -> map("slug", p.getSlug(), "price", p.getPriceMonthly().doubleValue()))
                .orElse(null);
    }

    /** {@code ensureUsageResetCurrent()} + {@code resetMonthlyUsage()}. */
    private void ensureUsageResetCurrent(Tenant tenant) {
        String currentMonth = Clock.nowUtc().toLocalDate().toString().substring(0, 7);
        if (!currentMonth.equals(tenant.getUsageResetMonth())) {
            tenant.setDocumentsThisMonth(0);
            tenant.setAiMessagesThisMonth(0);
            tenant.setUsageResetMonth(currentMonth);
            tenants.save(tenant);
        }
    }

    private static boolean isMinusOne(Object v) {
        return v instanceof Number n && !(v instanceof Double) && n.longValue() == -1;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
