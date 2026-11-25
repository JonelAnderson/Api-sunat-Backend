package pe.facturacion.api.modules.admin.panel;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminBody;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminFormat;
import pe.facturacion.api.modules.admin.api.AdminPaging;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.api.AdminValidation;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.admin.security.AdminPrincipal;
import pe.facturacion.api.modules.admin.security.AdminRole;
import pe.facturacion.api.modules.audit.AuditLogService;
import pe.facturacion.api.modules.plan.DocumentQuota;
import pe.facturacion.api.modules.plan.EmissionPolicy;
import pe.facturacion.api.modules.plan.PanelPlanService;
import pe.facturacion.api.modules.plan.domain.Subscription;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.time.Clock;

/**
 * Empresas emisoras desde el panel ({@code /api/admin/tenants}): listado, detalle, suspender / activar y cambiar plan.
 * Nunca devuelve secretos (clave SOL, certificado, su contraseña, api_secret, client_secret ni tokens): las columnas se
 * eligen una por una.
 */
@RestController
@RequestMapping("/api/admin/tenants")
@Tag(name = "Panel de administración · empresas")
public class AdminTenantsController {

    static final Set<String> PLANS = Set.of("free", "pro", "business");
    static final Set<String> ENVIRONMENTS = Set.of(Tenant.ENV_BETA, Tenant.ENV_PRODUCTION);
    static final Set<String> EMISSION_MODES = Set.of(Tenant.EMISSION_PLAN, Tenant.EMISSION_UNLIMITED);

    private static final String ROW_COLUMNS = "t.id, t.ruc, t.razon_social, t.nombre_comercial, t.environment, t.plan, t.emission_mode, "
            + "t.is_active, t.documents_this_month, t.max_documents_month, t.suspension_reason, t.suspended_at, t.suspension_kind, t.created_at";

    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final AdminCertificates certificates;
    private final AuditLogService audit;
    private final PanelPlanService panelPlans;
    private final EmissionPolicy emission;

    public AdminTenantsController(JdbcTemplate jdbc, TenantRepository tenants, AdminCertificates certificates, AuditLogService audit,
            PanelPlanService panelPlans, EmissionPolicy emission) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.certificates = certificates;
        this.audit = audit;
        this.panelPlans = panelPlans;
        this.emission = emission;
    }

    @Operation(summary = "Empresas (paginado; textFilter RUC / razón social / nombre comercial; stateFilter; environment; plan)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping
    public ResponseEntity<Object> list(@RequestParam(required = false) String records, @RequestParam(required = false) String numPage,
            @RequestParam(required = false) String textFilter, @RequestParam(required = false) String stateFilter,
            @RequestParam(required = false) String environment, @RequestParam(required = false) String plan) {
        AdminPaging paging = AdminPaging.of(records, numPage);
        StringBuilder where = new StringBuilder(" WHERE t.deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        if (textFilter != null && !textFilter.isBlank()) {
            where.append(" AND (t.ruc LIKE ? ESCAPE '\\' OR f_unaccent_lower(coalesce(t.razon_social, '')) LIKE (f_unaccent_lower(?)) ESCAPE '\\'"
                    + " OR f_unaccent_lower(coalesce(t.nombre_comercial, '')) LIKE (f_unaccent_lower(?)) ESCAPE '\\')");
            String like = AdminFormat.likeContains(textFilter.trim());
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Boolean active = AdminPanelParams.state(stateFilter);
        if (active != null) {
            where.append(" AND t.is_active = ?");
            args.add(active);
        }
        if (environment != null && ENVIRONMENTS.contains(environment.trim())) {
            where.append(" AND t.environment = ?");
            args.add(environment.trim());
        }
        if (plan != null && PLANS.contains(plan.trim())) {
            where.append(" AND t.plan = ?");
            args.add(plan.trim());
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM tenants t" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(paging.records());
        pageArgs.add(paging.offset());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + ROW_COLUMNS + " FROM tenants t" + where
                + " ORDER BY t.id DESC LIMIT ? OFFSET ?", pageArgs.toArray()).stream().map(AdminTenantsController::row).toList();
        return AdminResponse.page(rows, total == null ? 0 : total);
    }

    @Operation(summary = "Detalle de la empresa (sin secretos): sucursales, series, documentos del mes por estado y certificado")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/{id}")
    public ResponseEntity<Object> detail(@PathVariable long id) {
        Map<String, Object> base = baseRow(id);
        Tenant tenant = tenants.findById(id).orElseThrow(AdminTenantsController::notFound);
        Map<String, Object> data = new LinkedHashMap<>(row(base));
        data.put("direccion", tenant.getDireccion());
        data.put("emails", tenant.getEmails() == null ? List.of() : tenant.getEmails());
        data.put("sucursales", jdbc.queryForList("SELECT id, nombre, cod_local, direccion, ubigeo, is_principal, is_active FROM sucursales "
                + "WHERE tenant_id = ? ORDER BY is_principal DESC, id", id).stream().map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", AdminFormat.longOrNull(s.get("id")));
                    m.put("nombre", s.get("nombre"));
                    m.put("codLocal", s.get("cod_local"));
                    m.put("direccion", s.get("direccion"));
                    m.put("ubigeo", s.get("ubigeo"));
                    m.put("isPrincipal", s.get("is_principal"));
                    m.put("isActive", s.get("is_active"));
                    return m;
                }).toList());
        data.put("series", jdbc.queryForList("SELECT id, tipo_documento, serie, correlativo, sucursal_nombre, is_active FROM series "
                + "WHERE tenant_id = ? ORDER BY tipo_documento, serie", id).stream().map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", AdminFormat.longOrNull(s.get("id")));
                    m.put("tipoDocumento", s.get("tipo_documento"));
                    m.put("serie", s.get("serie"));
                    m.put("correlativo", s.get("correlativo"));
                    m.put("sucursalNombre", s.get("sucursal_nombre"));
                    m.put("isActive", s.get("is_active"));
                    return m;
                }).toList());

        List<Object> lastArgs = new ArrayList<>();
        String last = AdminDocuments.union("max(created_at) AS m", "tenant_id = ?", List.of(id), lastArgs);
        data.put("lastDocumentAt", AdminFormat.instant(jdbc.queryForObject("SELECT max(m) FROM (" + last + ") x", LocalDateTime.class,
                lastArgs.toArray())));

        var range = AdminDocuments.monthRange(YearMonth.from(Clock.todayLima()));
        List<Object> monthArgs = new ArrayList<>();
        String month = AdminDocuments.union("sunat_status", "tenant_id = ? AND fecha_emision >= ? AND fecha_emision < ?",
                List.of(id, range[0], range[1]), monthArgs);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT sunat_status, count(*) AS n FROM (" + month + ") x GROUP BY sunat_status",
                monthArgs.toArray())) {
            counts.put(String.valueOf(r.get("sunat_status")), AdminFormat.longOrNull(r.get("n")));
        }
        Map<String, Object> byStatus = new LinkedHashMap<>();
        for (String[] s : AdminDocuments.STATUSES) {
            byStatus.put(s[1], counts.getOrDefault(s[0], 0L));
        }
        data.put("documentsByStatus", byStatus);

        AdminCertificates.Row cert = certificates.row(tenant, certificates.defaultDays());
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("status", cert.status());
        c.put("expiresOn", cert.expiresOn() == null ? null : cert.expiresOn().toString());
        c.put("message", cert.message());
        data.put("certificate", c);
        data.put("webhookUrl", tenant.getWebhookUrl());
        data.put("sireEnabled", tenant.isSireEnabled());
        // Plan efectivo, suscripción y cupo que se aplica (§12.2)
        data.putAll(planBlocks(tenant));
        return AdminResponse.ok(data);
    }

    @Operation(summary = "Suspende (motivo obligatorio) o activa la empresa; suspendida, la API le responde 403. Con tipo = pago "
            + "(la consola de plataforma) sigue activa: consulta y descarga sí, emisión nueva no (402)")
    @AdminPermission(AdminPermissions.TENANTS_MANAGE)
    @PutMapping("/{id}/estado")
    public ResponseEntity<Object> estado(@PathVariable long id, HttpServletRequest request) {
        AdminPrincipal me = AdminPrincipal.require(request);
        Map<String, Object> before = baseRow(id);
        AdminBody b = AdminBody.of(request);
        Boolean activo = b.bool("activo");
        String motivo = b.text("motivo");
        String tipo = b.text("tipo");
        boolean service = me.role() == AdminRole.SERVICIO;
        AdminValidation v = new AdminValidation();
        if (tipo != null && !"manual".equals(tipo) && !"pago".equals(tipo)) {
            v.add("tipo", "El tipo de suspensión debe ser manual o pago.");
        } else if (service && Boolean.FALSE.equals(activo) && !"pago".equals(tipo)) {
            // La suspensión manual (403 en todo) es decisión de una persona en el panel, no de la consola
            v.add("tipo", "La consola solo suspende por falta de pago (tipo = pago).");
        }
        if (activo == null) {
            v.add("activo", "Indica si la empresa queda activa (true) o suspendida (false).");
        } else if (!activo) {
            v.required("motivo", motivo, "El motivo de la suspensión es obligatorio.");
            v.maxLength("motivo", motivo, 400, "El motivo no debe superar los 400 caracteres.");
        }
        v.throwIfAny();
        LocalDateTime now = Clock.nowUtc();
        boolean wasActive = Boolean.TRUE.equals(before.get("is_active"));
        boolean wasBilling = "pago".equals(before.get("suspension_kind"));
        boolean byBilling = !activo && "pago".equals(tipo);
        // Lo que la consola no debe pisar: una suspensión manual del panel (is_active = false) manda sobre la de pago
        boolean skip = (activo && service && !wasBilling) || (byBilling && !wasActive);
        String message = activo ? "Empresa activada." : "Empresa suspendida.";
        if (skip) {
            message = activo
                    ? (wasActive ? "La empresa no estaba suspendida por falta de pago." : "La empresa está suspendida manualmente en el panel: la consola no la reactiva.")
                    : "La empresa ya está suspendida manualmente en el panel: se mantiene.";
        } else if (activo) {
            jdbc.update("UPDATE tenants SET is_active = true, suspension_reason = NULL, suspended_at = NULL, suspension_kind = NULL, updated_at = ? WHERE id = ?",
                    now, id);
        } else if (byBilling) {
            // Sigue activa (consulta, descarga y cola funcionan); solo la emisión nueva se rechaza con 402
            jdbc.update("UPDATE tenants SET suspension_reason = ?, suspended_at = ?, suspension_kind = 'pago', updated_at = ? WHERE id = ?",
                    motivo, wasBilling ? before.get("suspended_at") : now, now, id);
            message = "Emisión suspendida por falta de pago.";
        } else {
            jdbc.update("UPDATE tenants SET is_active = false, suspension_reason = ?, suspended_at = ?, suspension_kind = NULL, updated_at = ? WHERE id = ?",
                    motivo, now, now, id);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("activo_anterior", before.get("is_active"));
        meta.put("activo", activo);
        if (tipo != null) {
            meta.put("tipo", tipo);
        }
        if (skip) {
            meta.put("sin_cambios", true);
        }
        if (!activo) {
            meta.put("motivo", motivo);
        }
        String action = skip ? "admin.empresa.estado_sin_cambios" : activo ? "admin.empresa.activada" : "admin.empresa.suspendida";
        String label = skip ? "Estado sin cambios: " : (activo ? "Empresa activada: " : byBilling ? "Emisión suspendida por falta de pago: " : "Empresa suspendida: ");
        audit.registrarAdmin(request, me.userId(), me.email(), action, id, AuditLogService.SUBJECT_TENANT, id,
                label + before.get("ruc") + (activo ? "" : " — " + motivo), meta);
        return AdminResponse.ok(row(baseRow(id)), message);
    }

    /**
     * Cambia plan, cupo mensual y modo de emisión. El plan del panel es el plan efectivo (§12.2): se sincroniza la
     * suscripción ({@link PanelPlanService#assign}) y el cupo pedido es el que aplica {@code EmissionPolicy}
     * ({@code maxDocumentsMonth} vacío o igual al del plan → sigue al plan).
     */
    @Operation(summary = "Cambia plan (pasa a ser el plan efectivo: sincroniza la suscripción), cupo mensual y modo de emisión")
    @AdminPermission(AdminPermissions.TENANTS_MANAGE)
    @PutMapping("/{id}/plan")
    public ResponseEntity<Object> plan(@PathVariable long id, HttpServletRequest request) {
        AdminPrincipal me = AdminPrincipal.require(request);
        Map<String, Object> base = baseRow(id);
        Tenant tenant = tenants.findById(id).orElseThrow(AdminTenantsController::notFound);
        AdminBody b = AdminBody.of(request);
        String plan = b.text("plan");
        Long max = b.integer("maxDocumentsMonth");
        String mode = b.text("emissionMode");
        String motivo = b.text("motivo");
        AdminValidation v = new AdminValidation();
        if (plan == null || !PLANS.contains(plan)) {
            v.add("plan", "El plan debe ser free, pro o business.");
        }
        if (b.has("maxDocumentsMonth") && (max == null || max < 0 || max > Integer.MAX_VALUE)) {
            v.add("maxDocumentsMonth", "El cupo mensual debe ser un número entero mayor o igual a 0 (vacío = el cupo del plan).");
        }
        if (mode != null && !EMISSION_MODES.contains(mode)) {
            v.add("emissionMode", "El modo de emisión debe ser plan o unlimited.");
        }
        v.reason(motivo, false);
        v.throwIfAny();
        String newMode = mode != null ? mode : String.valueOf(base.get("emission_mode"));
        PanelPlanService.Result r;
        try {
            r = panelPlans.assign(tenant, plan, max, newMode);
        } catch (PanelPlanService.PanelPlanException e) {
            throw new AdminException(200, List.of(e.getMessage()), List.of(AdminResponse.fieldError("plan", e.getMessage())));
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("antes", r.before().toMeta());
        meta.put("despues", r.after().toMeta());
        meta.put("suscripcion_accion", r.action().code());
        meta.put("pendientes_canceladas", r.pendingCancelled());
        meta.put("suscripciones_canceladas", r.cancelledIds());
        if (motivo != null) {
            meta.put("motivo", motivo);
        }
        int limit = r.after().maxDocumentsMonth();
        audit.registrarAdmin(request, me.userId(), me.email(), "admin.empresa.plan_cambiado", id, AuditLogService.SUBJECT_TENANT, id,
                "Plan de la empresa " + base.get("ruc") + ": " + plan + " / " + (limit == -1 ? "ilimitado" : limit + " doc.") + " ("
                        + DocumentQuota.source(r.after().override()) + ") / " + newMode + " — suscripción " + r.action().code(), meta);
        Map<String, Object> data = new LinkedHashMap<>(row(baseRow(id)));
        data.putAll(planBlocks(tenants.findById(id).orElseThrow(AdminTenantsController::notFound)));
        return AdminResponse.ok(data, "Plan actualizado.");
    }

    /**
     * Plan efectivo, suscripción y cupo de la empresa (§12.2), en camelCase:
     * {@code effectivePlan}, {@code planInSync}, {@code subscription}, {@code pendingSubscription} y {@code quota}.
     */
    Map<String, Object> planBlocks(Tenant tenant) {
        PanelPlanService.State s = panelPlans.state(tenant);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("effectivePlan", s.effectivePlan().getSlug());
        m.put("planInSync", s.effectivePlan().getSlug().equals(tenant.getPlan()));
        m.put("subscription", subscription(s.current()));
        Map<String, Object> pending = null;
        if (s.pending() != null) {
            pending = new LinkedHashMap<>();
            pending.put("id", s.pending().getId());
            pending.put("plan", s.pending().getPlan().getSlug());
            pending.put("startsAt", AdminFormat.instant(s.pending().getCurrentPeriodStart()));
        }
        m.put("pendingSubscription", pending);
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("documentsMonth", s.effectiveLimit());
        q.put("unlimited", s.effectiveLimit() == DocumentQuota.UNLIMITED);
        q.put("source", DocumentQuota.source(s.override()));
        q.put("planDefault", s.planDefault());
        q.put("override", s.override());
        q.put("usedThisMonth", emission.usedThisMonth(tenant));
        q.put("exemptReason", emission.exemptReason(tenant));
        m.put("quota", q);
        return m;
    }

    private static Map<String, Object> subscription(Subscription s) {
        if (s == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("plan", s.getPlan().getSlug());
        m.put("status", s.getStatus());
        m.put("setByPanel", PanelPlanService.GATEWAY_PANEL.equals(s.getPaymentGateway()));
        m.put("billingCycle", s.getBillingCycle());
        m.put("periodStart", AdminFormat.instant(s.getCurrentPeriodStart()));
        m.put("periodEnd", AdminFormat.instant(s.getCurrentPeriodEnd()));
        m.put("trialEndsAt", AdminFormat.instant(s.getTrialEndsAt()));
        m.put("cancelledAt", AdminFormat.instant(s.getCancelledAt()));
        return m;
    }

    // ------------------------------------------------------------------ apoyo

    private Map<String, Object> baseRow(long id) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT " + ROW_COLUMNS + " FROM tenants t WHERE t.id = ? AND t.deleted_at IS NULL", id);
        if (r.isEmpty()) {
            throw notFound();
        }
        return r.get(0);
    }

    private static AdminException notFound() {
        return AdminException.fail("No existe la empresa.");
    }

    /** Fila del listado (columnas elegidas: nunca secretos). */
    static Map<String, Object> row(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", AdminFormat.longOrNull(r.get("id")));
        m.put("ruc", r.get("ruc"));
        m.put("razonSocial", r.get("razon_social"));
        m.put("nombreComercial", r.get("nombre_comercial"));
        m.put("environment", r.get("environment"));
        m.put("plan", r.get("plan"));
        m.put("emissionMode", r.get("emission_mode"));
        m.put("isActive", r.get("is_active"));
        m.put("documentsThisMonth", AdminFormat.intOrZero(r.get("documents_this_month")));
        m.put("maxDocumentsMonth", AdminFormat.intOrZero(r.get("max_documents_month")));
        m.put("suspensionReason", r.get("suspension_reason"));
        m.put("suspendedAt", AdminFormat.instant(r.get("suspended_at")));
        m.put("suspensionKind", r.get("suspension_kind"));
        m.put("billingSuspended", "pago".equals(r.get("suspension_kind")));
        m.put("createdAt", AdminFormat.instant(r.get("created_at")));
        return m;
    }
}
