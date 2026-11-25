package pe.facturacion.api.modules.admin.panel;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminFormat;
import pe.facturacion.api.modules.admin.api.AdminPaging;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.api.AdminValidation;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.time.Clock;

/**
 * Documentos de todas las empresas y de todos los tipos ({@link AdminAllDocuments}):
 * <ul>
 *   <li>{@code GET submissions}: feed de envíos SUNAT de todas las empresas en todos los estados (como "Logs de envíos"
 *       de la referencia, con todos los tipos), del más reciente al más antiguo por momento de envío (o de creación si
 *       aún no se envió); por defecto los últimos 30 días.</li>
 *   <li>{@code GET documents} y {@code documents/summary}: vista unificada global con contadores por tipo.</li>
 *   <li>{@code GET tenants/{id}/comprobantes} y {@code comprobantes/resumen}: la vista unificada de una empresa
 *       (como {@code admin/empresas/{t}/comprobantes} de la referencia), auditada como las vistas del §9.</li>
 * </ul>
 * Las filas llevan {@code vista} (segmento de la vista de empresa del §9) para abrir el detalle y las descargas.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Panel de administración · envíos y comprobantes")
public class AdminDocumentsController {

    /** Ventana por defecto del feed y de la vista global sin empresa. */
    static final int DEFAULT_DAYS = 30;

    private final AdminAllDocuments documents;
    private final AdminTenantViews views;

    public AdminDocumentsController(AdminAllDocuments documents, AdminTenantViews views) {
        this.documents = documents;
        this.views = views;
    }

    @Operation(summary = "Feed de envíos SUNAT de todas las empresas (startDate / endDate yyyy-MM-dd Lima, por defecto 30 días; "
            + "estado, tipo, tenantId, textFilter)")
    @AdminPermission(AdminPermissions.REJECTIONS_VIEW)
    @GetMapping("/submissions")
    public ResponseEntity<Object> submissions(HttpServletRequest request) {
        AdminAllDocuments.Filter f = filter(request, null, true);
        AdminPaging paging = AdminPaging.of(request.getParameter("records"), request.getParameter("numPage"));
        List<Map<String, Object>> rows = documents.page(f, paging, AdminAllDocuments.ORDER.get("sentAt"), false);
        return AdminResponse.page(rows, documents.count(f));
    }

    @Operation(summary = "Comprobantes de todas las empresas y de todos los tipos (tenantId opcional; sin empresa ni fechas, los últimos 30 días)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/documents")
    public ResponseEntity<Object> list(HttpServletRequest request) {
        AdminAllDocuments.Filter f = filter(request, null, false);
        return page(f, request);
    }

    @Operation(summary = "Contadores por tipo y estado de la vista global (mismos filtros salvo tipo y estado)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/documents/summary")
    public ResponseEntity<Object> summary(HttpServletRequest request) {
        return AdminResponse.ok(documents.summary(forSummary(filter(request, null, false))));
    }

    @Operation(summary = "Todos los comprobantes de la empresa en una vista (tipo, estado, serie, sucursalId, fechas, textFilter, orden)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/tenants/{id}/comprobantes")
    public ResponseEntity<Object> tenantList(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = page(filter(request, tenant.getId(), false), request);
        views.auditView(request, tenant, "comprobantes");
        return r;
    }

    @Operation(summary = "Contadores por tipo y estado de los comprobantes de la empresa (mismos filtros salvo tipo y estado)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/tenants/{id}/comprobantes/resumen")
    public ResponseEntity<Object> tenantSummary(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        Map<String, Object> data = documents.summary(forSummary(filter(request, tenant.getId(), false)));
        views.auditView(request, tenant, "comprobantes");
        return AdminResponse.ok(data);
    }

    // ------------------------------------------------------------------ apoyo

    private ResponseEntity<Object> page(AdminAllDocuments.Filter f, HttpServletRequest request) {
        return page(documents, f, request);
    }

    /** De paquete: {@link AdminTenantUnifiedDocuments} lo reutiliza para la vista unificada del portal de clientes. */
    static ResponseEntity<Object> page(AdminAllDocuments documents, AdminAllDocuments.Filter f, HttpServletRequest request) {
        AdminPaging paging = AdminPaging.of(request.getParameter("records"), request.getParameter("numPage"));
        String orderBy = AdminPanelParams.text(request.getParameter("orderBy"));
        String expr = AdminAllDocuments.ORDER.get(orderBy == null ? "fechaEmision" : orderBy);
        if (expr == null) {
            new AdminValidation().add("orderBy", "Orden no válido. Use: fechaEmision, createdAt, sentAt, total, tipo, serie, numero o estado.")
                    .throwIfAny();
        }
        boolean asc = "asc".equalsIgnoreCase(AdminPanelParams.text(request.getParameter("orderDir")));
        return AdminResponse.page(documents.page(f, paging, expr, asc), documents.count(f));
    }

    /**
     * Filtros comunes. {@code tenantId} fijo (vista de una empresa) o el del parámetro; sin empresa (o en el feed) y sin
     * fechas se usan los últimos {@link #DEFAULT_DAYS} días. El rango no puede superar 366 días.
     */
    static AdminAllDocuments.Filter filter(HttpServletRequest request, Long fixedTenant, boolean feed) {
        LocalDate start = AdminFormat.parseDay("startDate", request.getParameter("startDate"));
        LocalDate end = AdminFormat.parseDay("endDate", request.getParameter("endDate"));
        Long tenantId = fixedTenant != null ? fixedTenant : AdminPanelParams.longParam("tenantId", request.getParameter("tenantId"));
        if (start == null && end == null && (feed || tenantId == null)) {
            LocalDate[] last = AdminAllDocuments.lastDays(Clock.todayLima(), DEFAULT_DAYS);
            start = last[0];
            end = last[1];
        }
        AdminPanelParams.checkRange(start, end);
        String serie = AdminPanelParams.text(request.getParameter("serie"));
        if (serie != null && serie.length() > 4) {
            new AdminValidation().add("serie", "La serie tiene como máximo 4 caracteres.").throwIfAny();
        }
        String text = AdminPanelParams.text(request.getParameter("textFilter"));
        if (text != null && text.length() > 100) {
            text = text.substring(0, 100);
        }
        return new AdminAllDocuments.Filter(tenantId, start, end, AdminAllDocuments.parseTipos(request.getParameter("tipo")),
                AdminAllDocuments.parseEstado(request.getParameter("estado")), serie,
                AdminPanelParams.longParam("sucursalId", request.getParameter("sucursalId")), text);
    }

    static AdminAllDocuments.Filter forSummary(AdminAllDocuments.Filter f) {
        return new AdminAllDocuments.Filter(f.tenantId(), f.start(), f.end(), java.util.Set.of(), null, f.serie(), f.sucursalId(), f.text());
    }
}
