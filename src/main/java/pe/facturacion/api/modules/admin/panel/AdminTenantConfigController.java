package pe.facturacion.api.modules.admin.panel;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.api.AdminV1Responses;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.client.ClienteController;
import pe.facturacion.api.modules.plan.SubscriptionController;
import pe.facturacion.api.modules.report.ReportController;
import pe.facturacion.api.modules.serie.SerieController;
import pe.facturacion.api.modules.settlement.SettlementEndpoints;
import pe.facturacion.api.modules.sucursal.SucursalController;
import pe.facturacion.api.modules.tenant.TenantService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.http.Input;

/**
 * Configuración y reportes de una empresa desde el panel (solo lectura): datos de la empresa, sucursales, series,
 * clientes, suscripción (pagos y uso del plan) y reportes. Reutiliza los mismos servicios y controladores de
 * {@code /api/v1} con la empresa de la ruta. {@code /api/v1/empresa/credenciales} no se expone, y del recurso de la
 * empresa se quitan {@code sol_user} y {@code client_id} (credenciales de SUNAT); la API ya no devuelve la clave SOL,
 * el {@code client_secret}, el certificado ni su contraseña, solo indicadores {@code tiene_*}.
 */
@RestController
@RequestMapping("/api/admin/tenants/{id}")
@Tag(name = "Panel de administración · configuración y reportes de la empresa")
public class AdminTenantConfigController {

    /** Claves del recurso de {@code GET /api/v1/empresa} que el panel no muestra (credenciales de SUNAT). */
    public static final List<String> EMPRESA_OCULTOS = List.of("sol_user", "client_id");
    /** Los reportes del panel son solo JSON (el PDF queda para la empresa). */
    static final List<String> REPORT_DROP = List.of("formato");

    private final AdminTenantViews views;
    private final TenantService tenantService;
    private final SucursalController sucursales;
    private final SerieController series;
    private final ClienteController clientes;
    private final SubscriptionController subscriptions;
    private final ReportController reports;
    private final SettlementEndpoints settlements;

    public AdminTenantConfigController(AdminTenantViews views, TenantService tenantService, SucursalController sucursales,
            SerieController series, ClienteController clientes, SubscriptionController subscriptions, ReportController reports,
            SettlementEndpoints settlements) {
        this.views = views;
        this.tenantService = tenantService;
        this.sucursales = sucursales;
        this.series = series;
        this.clientes = clientes;
        this.subscriptions = subscriptions;
        this.reports = reports;
        this.settlements = settlements;
    }

    @Operation(summary = "Datos de la empresa (GET /api/v1/empresa sin sol_user ni client_id)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/empresa")
    public ResponseEntity<Object> empresa(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        Map<String, Object> data = empresaSinSecretos(tenantService.resource(tenant));
        views.auditView(request, tenant, "empresa");
        return AdminResponse.ok(data);
    }

    public static Map<String, Object> empresaSinSecretos(Map<String, Object> resource) {
        return AdminV1Responses.without(resource, EMPRESA_OCULTOS);
    }

    @Operation(summary = "Sucursales (sin paginación)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/sucursales")
    public ResponseEntity<Object> sucursales(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.page(sucursales.index(tenant));
        views.auditView(request, tenant, "sucursales");
        return r;
    }

    @Operation(summary = "Series (include_inactive, sucursal_id, tipo; sin paginación)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/series")
    public ResponseEntity<Object> series(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.page(series.index(tenant, views.input(request)));
        views.auditView(request, tenant, "series");
        return r;
    }

    @Operation(summary = "Clientes (buscar o textFilter; records, numPage)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/clientes")
    public ResponseEntity<Object> clientes(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.page(clientes.index(tenant, views.pagedInput(request, "buscar")));
        views.auditView(request, tenant, "clientes");
        return r;
    }

    @Operation(summary = "Suscripción activa y uso del plan, como GET /api/v1/suscripcion")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/suscripcion")
    public ResponseEntity<Object> suscripcion(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.data(subscriptions.show(tenant));
        views.auditView(request, tenant, "suscripcion");
        return r;
    }

    @Operation(summary = "Pagos de la suscripción (sin paginación)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/suscripcion/pagos")
    public ResponseEntity<Object> suscripcionPagos(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.page(subscriptions.payments(tenant));
        views.auditView(request, tenant, "suscripcion");
        return r;
    }

    @Operation(summary = "Uso del plan, como GET /api/v1/suscripcion/uso")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/suscripcion/uso")
    public ResponseEntity<Object> suscripcionUso(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.data(subscriptions.usage(tenant));
        views.auditView(request, tenant, "suscripcion");
        return r;
    }

    @Operation(summary = "Reporte (JSON) con los filtros de /api/v1/reportes/{reporte}: fecha_desde, fecha_hasta, sucursal_id…")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/reportes/{reporte:registro-ventas|ventas-consolidado|por-cliente}")
    public ResponseEntity<Object> reporte(@PathVariable long id, @PathVariable String reporte, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        Input in = views.input(request, REPORT_DROP);
        ResponseEntity<Object> v1 = switch (reporte) {
            case "registro-ventas" -> reports.registroVentas(tenant, in);
            case "ventas-consolidado" -> reports.ventasConsolidado(tenant, in);
            case "por-cliente" -> reports.porCliente(tenant, in);
            default -> throw AdminException.status(404, "Recurso no encontrado.");
        };
        ResponseEntity<Object> r = AdminV1Responses.data(v1);
        views.auditDetail(request, tenant, "reportes." + reporte, null, null);
        return r;
    }

    @Operation(summary = "Reporte de IGV y renta retenidos en liquidaciones de compra (periodo=AAAA-MM)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/reportes/retenciones")
    public ResponseEntity<Object> reporteRetenciones(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.data(settlements.reporteRetenciones(tenant, views.input(request)));
        views.auditDetail(request, tenant, "reportes.retenciones", null, null);
        return r;
    }
}
