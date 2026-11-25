package pe.facturacion.api.modules.admin.tenant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminActionAudit;
import pe.facturacion.api.modules.admin.api.AdminV1Actions;
import pe.facturacion.api.modules.admin.api.AdminValidation;
import pe.facturacion.api.modules.admin.panel.AdminTenantViews;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.client.ClienteController;
import pe.facturacion.api.modules.serie.SerieController;
import pe.facturacion.api.modules.sucursal.SucursalController;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.http.Input;

/**
 * Sucursales, series y clientes de una empresa desde el panel (fase C, §11.5 del diseño). Llaman a los mismos
 * controladores de {@code /api/v1} con la empresa de la ruta ({@code store / update / destroy(Tenant, …)}): mismas
 * reglas (cod_local único, sucursal principal, prefijos de serie, nunca bajar el correlativo de lo ya emitido, cliente
 * único por documento), mismo recurso snake_case en {@code data}. Un registro de otra empresa responde 404. Cada
 * escritura correcta se audita ({@code admin.sucursal.*}, {@code admin.serie.*}, {@code admin.cliente.*}) con el
 * motivo opcional del cuerpo ({@code motivo}, que no se pasa a {@code /api/v1}).
 */
@RestController
@RequestMapping("/api/admin/tenants/{id}")
@Tag(name = "Panel de administración · sucursales, series y clientes de la empresa")
public class AdminTenantResourcesController {

    private final AdminTenantViews views;
    private final SucursalController sucursales;
    private final SerieController series;
    private final ClienteController clientes;
    private final AdminActionAudit audit;

    public AdminTenantResourcesController(AdminTenantViews views, SucursalController sucursales, SerieController series,
            ClienteController clientes, AdminActionAudit audit) {
        this.views = views;
        this.sucursales = sucursales;
        this.series = series;
        this.clientes = clientes;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ sucursales

    @Operation(summary = "Crear sucursal (cuerpo de POST /api/v1/sucursales)")
    @AdminPermission(AdminPermissions.TENANTS_EDIT)
    @PostMapping("/sucursales")
    public ResponseEntity<Object> crearSucursal(@PathVariable long id, HttpServletRequest request) {
        return write(id, request, "sucursal", "creada", null, (t, in) -> sucursales.store(t, in));
    }

    @Operation(summary = "Actualizar sucursal (cuerpo de PUT /api/v1/sucursales/{id}; cod_local no se edita)")
    @AdminPermission(AdminPermissions.TENANTS_EDIT)
    @PutMapping("/sucursales/{sucursalId}")
    public ResponseEntity<Object> editarSucursal(@PathVariable long id, @PathVariable long sucursalId, HttpServletRequest request) {
        return write(id, request, "sucursal", "actualizada", sucursalId, (t, in) -> sucursales.update(t, in, sucursalId));
    }

    @Operation(summary = "Eliminar sucursal (sin series y no principal)")
    @AdminPermission(AdminPermissions.TENANTS_EDIT)
    @DeleteMapping("/sucursales/{sucursalId}")
    public ResponseEntity<Object> eliminarSucursal(@PathVariable long id, @PathVariable long sucursalId, HttpServletRequest request) {
        return write(id, request, "sucursal", "eliminada", sucursalId, (t, in) -> sucursales.destroy(t, sucursalId));
    }

    // ------------------------------------------------------------------ series

    @Operation(summary = "Crear una o varias series (cuerpo de POST /api/v1/series)")
    @AdminPermission(AdminPermissions.TENANTS_EDIT)
    @PostMapping("/series")
    public ResponseEntity<Object> crearSerie(@PathVariable long id, HttpServletRequest request) {
        return write(id, request, "serie", "creada", null, (t, in) -> series.store(t, in));
    }

    @Operation(summary = "Actualizar serie (activo, correlativo, sucursal_id); el correlativo nunca baja de lo emitido")
    @AdminPermission(AdminPermissions.TENANTS_EDIT)
    @PutMapping("/series/{serieId}")
    public ResponseEntity<Object> editarSerie(@PathVariable long id, @PathVariable long serieId, HttpServletRequest request) {
        return write(id, request, "serie", "actualizada", serieId, (t, in) -> series.update(t, in, serieId));
    }

    @Operation(summary = "Desactivar serie (borrado lógico)")
    @AdminPermission(AdminPermissions.TENANTS_EDIT)
    @DeleteMapping("/series/{serieId}")
    public ResponseEntity<Object> desactivarSerie(@PathVariable long id, @PathVariable long serieId, HttpServletRequest request) {
        return write(id, request, "serie", "desactivada", serieId, (t, in) -> series.destroy(t, serieId));
    }

    // ------------------------------------------------------------------ clientes

    @Operation(summary = "Crear o actualizar cliente por documento (cuerpo de POST /api/v1/clientes)")
    @AdminPermission(AdminPermissions.TENANTS_EDIT)
    @PostMapping("/clientes")
    public ResponseEntity<Object> crearCliente(@PathVariable long id, HttpServletRequest request) {
        return write(id, request, "cliente", "guardado", null, (t, in) -> clientes.store(t, in));
    }

    @Operation(summary = "Actualizar cliente (tipo y número de documento no cambian)")
    @AdminPermission(AdminPermissions.TENANTS_EDIT)
    @PutMapping("/clientes/{clienteId}")
    public ResponseEntity<Object> editarCliente(@PathVariable long id, @PathVariable long clienteId, HttpServletRequest request) {
        return write(id, request, "cliente", "actualizado", clienteId, (t, in) -> clientes.update(t, in, clienteId));
    }

    @Operation(summary = "Eliminar cliente (borrado físico; los comprobantes guardan sus datos)")
    @AdminPermission(AdminPermissions.TENANTS_EDIT)
    @DeleteMapping("/clientes/{clienteId}")
    public ResponseEntity<Object> eliminarCliente(@PathVariable long id, @PathVariable long clienteId, HttpServletRequest request) {
        return write(id, request, "cliente", "eliminado", clienteId, (t, in) -> clientes.destroy(t, clienteId));
    }

    // ------------------------------------------------------------------ apoyo

    /**
     * Valida el motivo opcional, quita {@code motivo} y los parámetros del panel de la entrada, llama a {@code /api/v1},
     * traduce la respuesta al sobre y audita la escritura correcta ({@code admin.{recurso}.{accion}}).
     */
    private ResponseEntity<Object> write(long id, HttpServletRequest request, String recurso, String accion, Long recordId,
            BiFunction<Tenant, Input, ResponseEntity<Object>> call) {
        Tenant tenant = views.tenant(id);
        Input input = views.input(request);
        String motivo = input.string("motivo");
        new AdminValidation().reason(motivo, false).throwIfAny();
        Map<String, Object> forV1 = new LinkedHashMap<>(input.all());
        forV1.remove("motivo");
        ResponseEntity<Object> v1 = call.apply(tenant, new Input(forV1));
        ResponseEntity<Object> response = AdminV1Actions.envelope(v1);
        Object data = AdminV1Actions.data(v1);
        Long registro = recordId != null ? recordId : AdminV1Actions.documentId(data);
        audit.record(request, "admin." + recurso + "." + accion, tenant, "Panel: " + recurso + " " + accion
                + (registro == null ? "" : " " + registro) + " de la empresa " + tenant.getRuc(),
                AdminActionAudit.meta("recurso", recurso, "id", registro, "campos", forV1.keySet().stream().sorted().toList(), "motivo", motivo));
        return response;
    }
}
