package pe.facturacion.api.modules.admin.panel;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminFormat;
import pe.facturacion.api.modules.admin.api.AdminPaging;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.api.AdminValidation;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.serie.Serie;

/**
 * Sucursales y series de todas las empresas ({@code GET /api/admin/branches} y {@code GET /api/admin/series}), como
 * {@code SucursalGlobalController} / {@code SerieGlobalController} de la referencia: paginados (tope 50), con filtro
 * de empresa y texto. Solo lectura; editar es de la fase C.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Panel de administración · sucursales y series")
public class AdminCatalogController {

    private final JdbcTemplate jdbc;

    public AdminCatalogController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Operation(summary = "Sucursales de todas las empresas (tenantId, textFilter nombre / código / RUC / razón social, stateFilter)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/branches")
    public ResponseEntity<Object> branches(@RequestParam(required = false) String tenantId, @RequestParam(required = false) String textFilter,
            @RequestParam(required = false) String stateFilter, @RequestParam(required = false) String records,
            @RequestParam(required = false) String numPage) {
        AdminPaging paging = AdminPaging.of(records, numPage);
        StringBuilder where = new StringBuilder(" WHERE t.deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        Long tenant = AdminPanelParams.longParam("tenantId", tenantId);
        if (tenant != null) {
            where.append(" AND s.tenant_id = ?");
            args.add(tenant);
        }
        Boolean active = AdminPanelParams.state(stateFilter);
        if (active != null) {
            where.append(" AND s.is_active = ?");
            args.add(active);
        }
        String text = AdminPanelParams.text(textFilter);
        if (text != null) {
            String like = AdminFormat.likeContains(text);
            where.append(" AND (f_unaccent_lower(s.nombre) LIKE (f_unaccent_lower(?)) ESCAPE '\\' OR s.cod_local LIKE ? ESCAPE '\\'"
                    + " OR t.ruc LIKE ? ESCAPE '\\' OR f_unaccent_lower(coalesce(t.razon_social, '')) LIKE (f_unaccent_lower(?)) ESCAPE '\\')");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        String from = " FROM sucursales s JOIN tenants t ON t.id = s.tenant_id" + where;
        Long total = jdbc.queryForObject("SELECT count(*)" + from, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(paging.records());
        pageArgs.add(paging.offset());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT s.id, s.tenant_id, t.ruc, t.razon_social, s.nombre, s.cod_local, s.direccion, "
                + "s.ubigeo, s.is_principal, s.is_active, (SELECT count(*) FROM series x WHERE x.sucursal_id = s.id) AS total_series" + from
                + " ORDER BY t.razon_social, s.tenant_id, s.is_principal DESC, s.nombre, s.id LIMIT ? OFFSET ?", pageArgs.toArray())
                .stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", AdminFormat.longOrNull(r.get("id")));
                    m.put("tenantId", AdminFormat.longOrNull(r.get("tenant_id")));
                    m.put("ruc", r.get("ruc"));
                    m.put("razonSocial", r.get("razon_social"));
                    m.put("nombre", r.get("nombre"));
                    m.put("codLocal", r.get("cod_local"));
                    m.put("direccion", r.get("direccion"));
                    m.put("ubigeo", r.get("ubigeo"));
                    m.put("isPrincipal", r.get("is_principal"));
                    m.put("isActive", r.get("is_active"));
                    m.put("totalSeries", AdminFormat.longOrNull(r.get("total_series")));
                    return m;
                }).toList();
        return AdminResponse.page(rows, total == null ? 0 : total);
    }

    @Operation(summary = "Series de todas las empresas (tenantId, tipo 01/03/07/08/09/31/20/40/04, textFilter serie / RUC / razón social, stateFilter)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/series")
    public ResponseEntity<Object> series(@RequestParam(required = false) String tenantId, @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String textFilter, @RequestParam(required = false) String stateFilter,
            @RequestParam(required = false) String records, @RequestParam(required = false) String numPage) {
        AdminPaging paging = AdminPaging.of(records, numPage);
        StringBuilder where = new StringBuilder(" WHERE t.deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        Long tenant = AdminPanelParams.longParam("tenantId", tenantId);
        if (tenant != null) {
            where.append(" AND x.tenant_id = ?");
            args.add(tenant);
        }
        String code = AdminPanelParams.text(tipo);
        if (code != null) {
            if (!Serie.TIPOS.containsValue(code)) {
                new AdminValidation().add("tipo", "Tipo no válido. Use: " + String.join(", ", Serie.TIPOS.values()) + ".").throwIfAny();
            }
            where.append(" AND x.tipo_documento = ?");
            args.add(code);
        }
        Boolean active = AdminPanelParams.state(stateFilter);
        if (active != null) {
            where.append(" AND x.is_active = ?");
            args.add(active);
        }
        String text = AdminPanelParams.text(textFilter);
        if (text != null) {
            String like = AdminFormat.likeContains(text);
            where.append(" AND (upper(x.serie) LIKE ? ESCAPE '\\' OR t.ruc LIKE ? ESCAPE '\\'"
                    + " OR f_unaccent_lower(coalesce(t.razon_social, '')) LIKE (f_unaccent_lower(?)) ESCAPE '\\')");
            args.add(AdminFormat.likeContains(text.toUpperCase(java.util.Locale.ROOT)));
            args.add(like);
            args.add(like);
        }
        String from = " FROM series x JOIN tenants t ON t.id = x.tenant_id LEFT JOIN sucursales s ON s.id = x.sucursal_id" + where;
        Long total = jdbc.queryForObject("SELECT count(*)" + from, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(paging.records());
        pageArgs.add(paging.offset());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT x.id, x.tenant_id, t.ruc, t.razon_social, x.tipo_documento, x.serie, "
                + "x.correlativo, x.sucursal_id, coalesce(s.nombre, x.sucursal_nombre) AS sucursal_nombre, x.is_active" + from
                + " ORDER BY t.razon_social, x.tenant_id, x.tipo_documento, x.serie, x.id LIMIT ? OFFSET ?", pageArgs.toArray())
                .stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String tipoDoc = String.valueOf(r.get("tipo_documento"));
                    AdminAllDocuments.Tipo t = AdminAllDocuments.tipo(tipoDoc);
                    long correlativo = AdminFormat.longOrNull(r.get("correlativo"));
                    m.put("id", AdminFormat.longOrNull(r.get("id")));
                    m.put("tenantId", AdminFormat.longOrNull(r.get("tenant_id")));
                    m.put("ruc", r.get("ruc"));
                    m.put("razonSocial", r.get("razon_social"));
                    m.put("tipoDocumento", tipoDoc);
                    m.put("tipoNombre", t == null ? tipoDoc : t.nombre());
                    m.put("serie", r.get("serie"));
                    m.put("correlativo", correlativo);
                    m.put("siguienteNumero", r.get("serie") + "-" + (correlativo + 1));
                    m.put("sucursalId", AdminFormat.longOrNull(r.get("sucursal_id")));
                    m.put("sucursalNombre", r.get("sucursal_nombre"));
                    m.put("isActive", r.get("is_active"));
                    return m;
                }).toList();
        return AdminResponse.page(rows, total == null ? 0 : total);
    }
}
