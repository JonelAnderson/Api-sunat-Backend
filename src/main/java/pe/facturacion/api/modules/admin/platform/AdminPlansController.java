package pe.facturacion.api.modules.admin.platform;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminBody;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminFormat;
import pe.facturacion.api.modules.admin.api.AdminPaging;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.admin.security.AdminPrincipal;
import pe.facturacion.api.modules.audit.AuditLogService;
import pe.facturacion.api.modules.plan.PanelPlanService;
import pe.facturacion.api.modules.plan.domain.Plan;
import pe.facturacion.api.modules.plan.domain.PlanRepository;
import pe.facturacion.api.shared.http.RequestInput;
import pe.facturacion.api.shared.json.PhpJson;

/**
 * Catálogo de planes ({@code /api/admin/plans}), como {@code Admin/PlanController} de la referencia. Escribe la misma
 * tabla {@code plans} (entidad {@link Plan}) que leen {@code GET /api/v1/planes}, la suscripción y el cupo: el formato
 * de esas respuestas no cambia, solo sus datos.
 *
 * <p>Permisos: leer, todos; crear / editar / activar, {@code Admin.Plans.Manage} (operador o superior, como el
 * {@code admin} de la referencia); eliminar, {@code Admin.Plans.Delete} (solo superadmin, como su DELETE) y solo un plan
 * que no sea del sistema y no tenga suscripciones. Cada escritura se audita con el antes y el después.
 */
@RestController
@RequestMapping("/api/admin/plans")
@Tag(name = "Panel de administración · planes")
public class AdminPlansController {

    static final String SUBJECT_PLAN = "App\\Models\\Plan";
    private static final String COLUMNS = "p.id, p.slug, p.name, p.price_monthly, p.price_yearly, p.limits::text AS limits, "
            + "p.features::text AS features, p.is_unlimited, p.duration_days, p.sort_order, p.is_active, p.created_at, p.updated_at, "
            + "(SELECT count(*) FROM subscriptions s WHERE s.plan_id = p.id) AS subscriptions, "
            + "(SELECT count(*) FROM tenants t WHERE t.plan = p.slug AND t.deleted_at IS NULL) AS tenants";

    private final JdbcTemplate jdbc;
    private final PlanRepository plans;
    private final AuditLogService audit;
    private final PanelPlanService panelPlans;

    public AdminPlansController(JdbcTemplate jdbc, PlanRepository plans, AuditLogService audit, PanelPlanService panelPlans) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.audit = audit;
        this.panelPlans = panelPlans;
    }

    @Operation(summary = "Planes (todos, también inactivos; textFilter slug / nombre, stateFilter; orden sortOrder)")
    @AdminPermission(AdminPermissions.PLANS_VIEW)
    @GetMapping
    public ResponseEntity<Object> list(@RequestParam(required = false) String textFilter, @RequestParam(required = false) String stateFilter,
            @RequestParam(required = false) String records, @RequestParam(required = false) String numPage) {
        AdminPaging paging = AdminPaging.of(records, numPage);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (textFilter != null && !textFilter.isBlank()) {
            String like = AdminFormat.likeContains(textFilter.trim());
            where.append(" AND (p.slug LIKE lower(?) ESCAPE '\\' OR f_unaccent_lower(p.name) LIKE (f_unaccent_lower(?)) ESCAPE '\\')");
            args.add(like);
            args.add(like);
        }
        if ("1".equals(stateFilter) || "true".equalsIgnoreCase(stateFilter)) {
            where.append(" AND p.is_active = true");
        } else if ("0".equals(stateFilter) || "false".equalsIgnoreCase(stateFilter)) {
            where.append(" AND p.is_active = false");
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM plans p" + where, Long.class, args.toArray());
        args.add(paging.records());
        args.add(paging.offset());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + COLUMNS + " FROM plans p" + where
                + " ORDER BY p.sort_order, p.id LIMIT ? OFFSET ?", args.toArray()).stream().map(AdminPlansController::row).toList();
        return AdminResponse.page(rows, total == null ? 0 : total);
    }

    @Operation(summary = "Catálogo de límites y funcionalidades conocidos (etiquetas para el formulario)")
    @AdminPermission(AdminPermissions.PLANS_VIEW)
    @GetMapping("/catalog")
    public ResponseEntity<Object> catalog() {
        return AdminResponse.ok(AdminPlanCatalog.asMap());
    }

    @Operation(summary = "Detalle del plan con sus límites y funcionalidades")
    @AdminPermission(AdminPermissions.PLANS_VIEW)
    @GetMapping("/{id}")
    public ResponseEntity<Object> show(@PathVariable long id) {
        return AdminResponse.ok(detail(id));
    }

    @Operation(summary = "Crea un plan")
    @AdminPermission(AdminPermissions.PLANS_MANAGE)
    @PostMapping
    @Transactional
    public ResponseEntity<Object> create(HttpServletRequest request) {
        AdminPrincipal me = AdminPrincipal.require(request);
        Map<String, Object> raw = body(request);
        AdminPlanRules.Data d = AdminPlanRules.validate(AdminBody.of(raw), raw, null);
        if (plans.findBySlug(d.slug()).isPresent()) {
            throw AdminException.fail("Ya existe un plan con el slug " + d.slug() + ".");
        }
        Plan p = new Plan();
        apply(p, d);
        p = saveOrFail(p);
        Map<String, Object> after = detail(p.getId());
        audit.registrarAdmin(request, me.userId(), me.email(), "admin.plan.creado", null, SUBJECT_PLAN, p.getId(),
                "Plan creado: " + d.slug(), Map.of("despues", snapshot(after)));
        return AdminResponse.ok(after, "Plan creado.");
    }

    @Operation(summary = "Edita un plan (el slug de free / pro / business no cambia)")
    @AdminPermission(AdminPermissions.PLANS_MANAGE)
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<Object> update(@PathVariable long id, HttpServletRequest request) {
        AdminPrincipal me = AdminPrincipal.require(request);
        Plan p = find(id);
        Map<String, Object> before = detail(id);
        Map<String, Object> raw = body(request);
        AdminPlanRules.Data d = AdminPlanRules.validate(AdminBody.of(raw), raw, p.getSlug());
        if (!d.slug().equals(p.getSlug()) && plans.findBySlug(d.slug()).isPresent()) {
            throw AdminException.fail("Ya existe un plan con el slug " + d.slug() + ".");
        }
        apply(p, d);
        Plan saved = saveOrFail(p);
        // Las empresas que siguen este plan sin cupo del panel ven su nuevo cupo también en max_documents_month (§12.2)
        int empresas = panelPlans.refreshMirrors(saved);
        Map<String, Object> after = detail(id);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("antes", snapshot(before));
        meta.put("despues", snapshot(after));
        meta.put("empresas_cupo_actualizado", empresas);
        audit.registrarAdmin(request, me.userId(), me.email(), "admin.plan.actualizado", null, SUBJECT_PLAN, id,
                "Plan actualizado: " + d.slug(), meta);
        return AdminResponse.ok(after, "Plan actualizado.");
    }

    @Operation(summary = "Activa o desactiva un plan (inactivo: no aparece en GET /api/v1/planes)")
    @AdminPermission(AdminPermissions.PLANS_MANAGE)
    @PutMapping("/{id}/toggle")
    @Transactional
    public ResponseEntity<Object> toggle(@PathVariable long id, HttpServletRequest request) {
        AdminPrincipal me = AdminPrincipal.require(request);
        Plan p = find(id);
        boolean active = !p.isActive();
        p.setActive(active);
        saveOrFail(p);
        audit.registrarAdmin(request, me.userId(), me.email(), active ? "admin.plan.activado" : "admin.plan.desactivado", null, SUBJECT_PLAN, id,
                (active ? "Plan activado: " : "Plan desactivado: ") + p.getSlug(), Map.of("activo_anterior", !active, "activo", active));
        return AdminResponse.ok(detail(id), active ? "Plan activado." : "Plan desactivado.");
    }

    @Operation(summary = "Elimina un plan sin suscripciones (no los del sistema; solo superadmin)")
    @AdminPermission(AdminPermissions.PLANS_DELETE)
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Object> delete(@PathVariable long id, HttpServletRequest request) {
        AdminPrincipal me = AdminPrincipal.require(request);
        Plan p = find(id);
        if (AdminPlanRules.SYSTEM_SLUGS.contains(p.getSlug())) {
            throw AdminException.fail("Los planes del sistema (free, pro, business) no se eliminan; desactívalo si no quieres ofrecerlo.");
        }
        Map<String, Object> before = detail(id);
        if (((Number) before.get("subscriptionsCount")).longValue() > 0) {
            throw AdminException.fail("No se puede eliminar: hay suscripciones asociadas. Desactívalo en su lugar.");
        }
        try {
            plans.delete(p);
            plans.flush();
        } catch (DataIntegrityViolationException e) {
            throw AdminException.fail("No se puede eliminar: hay suscripciones asociadas. Desactívalo en su lugar.");
        }
        audit.registrarAdmin(request, me.userId(), me.email(), "admin.plan.eliminado", null, SUBJECT_PLAN, id,
                "Plan eliminado: " + p.getSlug(), Map.of("antes", snapshot(before)));
        return AdminResponse.ok(null, "Plan eliminado.");
    }

    // ------------------------------------------------------------------ apoyo

    private Plan find(long id) {
        return plans.findById(id).orElseThrow(() -> AdminException.status(404, "No existe el plan."));
    }

    private Plan saveOrFail(Plan p) {
        try {
            return plans.saveAndFlush(p);
        } catch (DataIntegrityViolationException e) {
            throw AdminException.fail("Ya existe un plan con el slug " + p.getSlug() + ".");
        }
    }

    private static void apply(Plan p, AdminPlanRules.Data d) {
        p.setSlug(d.slug());
        p.setName(d.name());
        p.setPriceMonthly(d.priceMonthly());
        p.setPriceYearly(d.priceYearly());
        p.setSortOrder(d.sortOrder());
        p.setLimits(d.limits());
        p.setFeatures(d.features());
        p.setUnlimited(d.unlimited());
        p.setDurationDays(d.durationDays());
        p.setActive(d.active());
    }

    private static Map<String, Object> body(HttpServletRequest request) {
        Map<String, Object> m = PhpJson.decodeObject(RequestInput.rawBody(request));
        return m == null ? Map.of() : m;
    }

    private Map<String, Object> detail(long id) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT " + COLUMNS + " FROM plans p WHERE p.id = ?", id);
        if (r.isEmpty()) {
            throw AdminException.status(404, "No existe el plan.");
        }
        Map<String, Object> m = row(r.get(0));
        Object limits = r.get(0).get("limits") == null ? null : PhpJson.decode(String.valueOf(r.get(0).get("limits")));
        Object features = r.get(0).get("features") == null ? null : PhpJson.decode(String.valueOf(r.get(0).get("features")));
        m.put("limits", limits instanceof Map<?, ?> ? limits : new LinkedHashMap<>());
        m.put("features", features instanceof List<?> ? features : List.of());
        m.put("createdAt", AdminFormat.instant(r.get(0).get("created_at")));
        m.put("updatedAt", AdminFormat.instant(r.get(0).get("updated_at")));
        return m;
    }

    /** Lo auditable del plan (sin conteos ni fechas). */
    private static Map<String, Object> snapshot(Map<String, Object> d) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("slug", "name", "priceMonthly", "priceYearly", "sortOrder", "isUnlimited", "durationDays", "isActive",
                "limits", "features")) {
            m.put(k, d.get(k));
        }
        return m;
    }

    /** Fila del listado. */
    static Map<String, Object> row(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        String slug = String.valueOf(r.get("slug"));
        Object limits = r.get("limits") == null ? null : PhpJson.decode(String.valueOf(r.get("limits")));
        Object features = r.get("features") == null ? null : PhpJson.decode(String.valueOf(r.get("features")));
        Object docs = limits instanceof Map<?, ?> l ? l.get("documents_month") : null;
        boolean unlimited = Boolean.TRUE.equals(r.get("is_unlimited"));
        m.put("id", AdminFormat.longOrNull(r.get("id")));
        m.put("slug", slug);
        m.put("name", r.get("name"));
        m.put("priceMonthly", money(r.get("price_monthly")));
        m.put("priceYearly", money(r.get("price_yearly")));
        m.put("documentsMonth", docs instanceof Number n ? n.intValue() : null);
        m.put("isUnlimited", unlimited);
        m.put("durationDays", r.get("duration_days") instanceof Number n ? n.intValue() : null);
        m.put("featuresCount", features instanceof List<?> f ? f.size() : 0);
        m.put("sortOrder", AdminFormat.intOrZero(r.get("sort_order")));
        m.put("isActive", Boolean.TRUE.equals(r.get("is_active")));
        m.put("isSystem", AdminPlanRules.SYSTEM_SLUGS.contains(slug));
        m.put("subscriptionsCount", AdminFormat.longOrNull(r.get("subscriptions")));
        m.put("tenantsCount", AdminFormat.longOrNull(r.get("tenants")));
        return m;
    }

    private static Double money(Object v) {
        return v instanceof BigDecimal b ? b.doubleValue() : v instanceof Number n ? n.doubleValue() : null;
    }
}
