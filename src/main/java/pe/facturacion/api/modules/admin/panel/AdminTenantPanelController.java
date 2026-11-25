package pe.facturacion.api.modules.admin.panel;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminV1Responses;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.report.PanelController;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.http.Input;

/**
 * Panel (tablero) de una empresa desde el panel de administración: los mismos datos que {@code GET /api/v1/panel} y
 * sus subrutas, con los mismos parámetros ({@code mes=AAAA-MM} o {@code desde} + {@code hasta}; {@code limit} en
 * documentos recientes). Reutiliza {@link PanelController} con la empresa de la ruta.
 */
@RestController
@RequestMapping("/api/admin/tenants/{id}/panel")
@Tag(name = "Panel de administración · panel de la empresa")
public class AdminTenantPanelController {

    static final String SECCIONES = "indicadores|estado-sunat|cobranzas|ventas-mensuales|por-sucursal|por-moneda|clientes|productos"
            + "|documentos-recientes|alertas";

    private final AdminTenantViews views;
    private final PanelController panel;

    public AdminTenantPanelController(AdminTenantViews views, PanelController panel) {
        this.views = views;
        this.panel = panel;
    }

    @Operation(summary = "Resumen del periodo (mes=AAAA-MM o desde + hasta), como GET /api/v1/panel")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping
    public ResponseEntity<Object> index(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.data(panel.index(tenant, views.input(request)));
        views.auditView(request, tenant, "panel");
        return r;
    }

    @Operation(summary = "Sección del panel, como GET /api/v1/panel/{seccion}")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/{seccion:" + SECCIONES + "}")
    public ResponseEntity<Object> seccion(@PathVariable long id, @PathVariable String seccion, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        Input in = views.input(request);
        ResponseEntity<Object> v1 = switch (seccion) {
            case "indicadores" -> panel.indicadores(tenant);
            case "estado-sunat" -> panel.estadoSunat(tenant, in);
            case "cobranzas" -> panel.cobranzas(tenant);
            case "ventas-mensuales" -> panel.ventasMensuales(tenant);
            case "por-sucursal" -> panel.porSucursal(tenant, in);
            case "por-moneda" -> panel.porMoneda(tenant, in);
            case "clientes" -> panel.clientes(tenant, in);
            case "productos" -> panel.productos(tenant, in);
            case "documentos-recientes" -> panel.documentosRecientes(tenant, in);
            case "alertas" -> panel.alertas(tenant);
            default -> throw AdminException.status(404, "Recurso no encontrado.");
        };
        ResponseEntity<Object> r = AdminV1Responses.data(v1);
        // Una sola vista "panel": abrir el tablero (que pide todas las secciones) deja una fila, no once
        views.auditView(request, tenant, "panel");
        return r;
    }
}
