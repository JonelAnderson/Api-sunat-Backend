package pe.facturacion.api.modules.plan;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.plan.domain.PlanRepository;
import pe.facturacion.api.modules.plan.domain.Subscription;
import pe.facturacion.api.modules.plan.domain.SubscriptionRepository;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.shared.validation.DatabaseRules;
import pe.facturacion.api.shared.validation.Validator;

/** Planes (público) y suscripción de la empresa ({@code SubscriptionController} de API-PRO). */
@RestController
@Tag(name = "Planes y suscripción")
public class SubscriptionController {

    private static final List<String> ACTIVE = List.of(Subscription.STATUS_ACTIVE, Subscription.STATUS_TRIALING);

    private final PlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionService service;
    private final PlanService planService;
    private final DatabaseRules db;
    private final JdbcTemplate jdbc;

    public SubscriptionController(PlanRepository plans, SubscriptionRepository subscriptions, SubscriptionService service,
            PlanService planService, DatabaseRules db, JdbcTemplate jdbc) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.service = service;
        this.planService = planService;
        this.db = db;
        this.jdbc = jdbc;
    }

    @Operation(summary = "Planes activos (público). Los precios salen como texto, igual que API-PRO")
    @GetMapping("/api/v1/planes")
    public ResponseEntity<Object> plans() {
        List<Map<String, Object>> list = plans.findByActiveTrueOrderBySortOrderAsc().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slug", p.getSlug());
            m.put("nombre", p.getName());
            m.put("precio_mensual", p.getPriceMonthly().setScale(2, RoundingMode.HALF_UP).toPlainString());
            m.put("precio_anual", p.getPriceYearly().setScale(2, RoundingMode.HALF_UP).toPlainString());
            m.put("limites", p.getLimits());
            m.put("caracteristicas", p.getFeatures());
            return m;
        }).toList();
        return ApiResponse.success(list);
    }

    @Operation(summary = "Suscripción activa y uso del plan")
    @GetMapping("/api/v1/suscripcion")
    public ResponseEntity<Object> show(HttpServletRequest request) {
        return show(ApiContext.requireTenant(request));
    }

    /** Suscripción y uso de la empresa indicada (lo usa también el panel de administración con la misma respuesta). */
    public ResponseEntity<Object> show(Tenant tenant) {
        Subscription s = subscriptions.findFirstByTenantIdAndStatusInOrderByIdDesc(tenant.getId(), ACTIVE).orElse(null);
        Map<String, Object> suscripcion = null;
        if (s != null) {
            suscripcion = new LinkedHashMap<>();
            suscripcion.put("id", s.getId());
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("slug", s.getPlan().getSlug());
            plan.put("nombre", s.getPlan().getName());
            suscripcion.put("plan", plan);
            suscripcion.put("estado", s.getStatus());
            suscripcion.put("ciclo_facturacion", s.getBillingCycle());
            suscripcion.put("fin_prueba", Clock.iso8601Utc(s.getTrialEndsAt()));
            suscripcion.put("fin_periodo_actual", Clock.iso8601Utc(s.getCurrentPeriodEnd()));
            suscripcion.put("cancelado_en", Clock.iso8601Utc(s.getCancelledAt()));
            suscripcion.put("ultimos_cuatro_tarjeta", s.getCardLastFour());
            suscripcion.put("marca_tarjeta", s.getCardBrand());
        }
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("suscripcion", suscripcion);
        datos.put("uso", planService.getUsageReport(tenant));
        return ApiResponse.success(datos);
    }

    @Operation(summary = "Crear suscripción (plan_slug, ciclo_facturacion, prueba)")
    @PostMapping("/api/v1/suscripcion")
    public ResponseEntity<Object> store(HttpServletRequest request) {
        Tenant tenant = ApiContext.requireTenant(request);
        Input input = Input.of(request);
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("plan_slug", "required|string|exists:plans,slug");
        rules.put("ciclo_facturacion", "sometimes|string|in:monthly,yearly");
        rules.put("token", "sometimes|string");
        rules.put("prueba", "sometimes|boolean");
        Validator.make(input.all(), rules).database(db).validate();
        try {
            Subscription s = service.create(tenant, input.string("plan_slug"), input.string("ciclo_facturacion"),
                    input.string("token"), input.bool("prueba") ? 14 : 0);
            Map<String, Object> datos = new LinkedHashMap<>();
            datos.put("id_suscripcion", s.getId());
            datos.put("plan", s.getPlan().getSlug());
            datos.put("estado", s.getStatus());
            datos.put("fin_periodo_actual", Clock.iso8601Utc(s.getCurrentPeriodEnd()));
            return ApiResponse.created(datos, "Suscripción creada exitosamente.");
        } catch (SubscriptionService.SubscriptionException e) {
            return ApiResponse.error(e.getMessage(), 422);
        }
    }

    @Operation(summary = "Cambiar de plan (mejora inmediata, reducción al fin del periodo)")
    @PutMapping("/api/v1/suscripcion/cambiar-plan")
    public ResponseEntity<Object> changePlan(HttpServletRequest request) {
        Tenant tenant = ApiContext.requireTenant(request);
        Input input = Input.of(request);
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("plan_slug", "required|string|exists:plans,slug");
        rules.put("ciclo_facturacion", "sometimes|string|in:monthly,yearly");
        rules.put("token", "sometimes|string");
        Validator.make(input.all(), rules).database(db).validate();
        try {
            Subscription s = service.changePlan(tenant, input.string("plan_slug"), input.string("ciclo_facturacion"), input.string("token"));
            Map<String, Object> datos = new LinkedHashMap<>();
            datos.put("id_suscripcion", s.getId());
            datos.put("plan", s.getPlan().getSlug());
            datos.put("estado", s.getStatus());
            return ApiResponse.success(datos, "Plan actualizado exitosamente.");
        } catch (SubscriptionService.SubscriptionException e) {
            return ApiResponse.error(e.getMessage(), 422);
        }
    }

    @Operation(summary = "Cancelar la suscripción")
    @PutMapping("/api/v1/suscripcion/cancelar")
    public ResponseEntity<Object> cancel(HttpServletRequest request) {
        Subscription s = service.cancel(ApiContext.requireTenant(request));
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("estado", s.getStatus());
        datos.put("cancelado_en", Clock.iso8601Utc(s.getCancelledAt()));
        // La suscripción sigue vigente hasta el fin del periodo (ver SubscriptionService.cancel)
        datos.put("acceso_hasta", Clock.iso8601Utc(s.getCurrentPeriodEnd()));
        return ApiResponse.success(datos, "Suscripción cancelada. Acceso disponible hasta el final del periodo.");
    }

    @Operation(summary = "Pagos de la suscripción")
    @GetMapping("/api/v1/suscripcion/pagos")
    public ResponseEntity<Object> payments(HttpServletRequest request) {
        return payments(ApiContext.requireTenant(request));
    }

    public ResponseEntity<Object> payments(Tenant tenant) {
        List<Map<String, Object>> list = jdbc.query(
                "SELECT p.* FROM subscription_payments p JOIN subscriptions s ON s.id = p.subscription_id "
                        + "WHERE s.tenant_id = ? ORDER BY p.created_at DESC NULLS LAST, p.id DESC",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("monto", rs.getBigDecimal("amount").setScale(2, RoundingMode.HALF_UP).toPlainString());
                    m.put("moneda", rs.getString("currency"));
                    m.put("estado", rs.getString("status"));
                    Timestamp paid = rs.getTimestamp("paid_at");
                    m.put("pagado_en", paid == null ? null : Clock.iso8601Utc(paid.toLocalDateTime()));
                    Date ps = rs.getDate("period_start");
                    Date pe = rs.getDate("period_end");
                    m.put("inicio_periodo", ps == null ? null : ps.toLocalDate().toString());
                    m.put("fin_periodo", pe == null ? null : pe.toLocalDate().toString());
                    return m;
                }, tenant.getId());
        return ApiResponse.success(list);
    }

    @Operation(summary = "Reporte de uso del plan")
    @GetMapping("/api/v1/suscripcion/uso")
    public ResponseEntity<Object> usage(HttpServletRequest request) {
        return usage(ApiContext.requireTenant(request));
    }

    public ResponseEntity<Object> usage(Tenant tenant) {
        return ApiResponse.success(planService.getUsageReport(tenant));
    }
}
