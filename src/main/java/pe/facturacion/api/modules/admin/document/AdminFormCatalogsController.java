package pe.facturacion.api.modules.admin.document;

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
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.api.AdminV1Responses;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.catalog.CatalogoProductoController;
import pe.facturacion.api.modules.catalog.UbigeoController;

/**
 * Catálogos que usan los formularios del panel (alta de empresa, sucursales, guías, liquidaciones: §11.7 del diseño):
 * ubigeo y productos SUNAT (UNSPSC). Son los mismos catálogos globales de {@code /api/v1/catalogos/...} (no dependen de
 * la empresa), con los mismos parámetros, y {@code data} lleva sus filas tal cual. Permiso {@code Admin.Tenants.View}.
 */
@RestController
@RequestMapping("/api/admin/catalogos")
@Tag(name = "Panel de administración · catálogos de los formularios")
public class AdminFormCatalogsController {

    private final UbigeoController ubigeo;
    private final CatalogoProductoController productos;

    public AdminFormCatalogsController(UbigeoController ubigeo, CatalogoProductoController productos) {
        this.ubigeo = ubigeo;
        this.productos = productos;
    }

    @Operation(summary = "Regiones (departamentos)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/ubigeo/regiones")
    public ResponseEntity<Object> regiones() {
        return AdminV1Responses.page(ubigeo.regiones());
    }

    @Operation(summary = "Provincias de una región (region_id=NN)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/ubigeo/provincias")
    public ResponseEntity<Object> provincias(HttpServletRequest request) {
        return AdminV1Responses.page(ubigeo.provincias(request));
    }

    @Operation(summary = "Distritos de una provincia (provincia_id=NNNN); el id es el ubigeo")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/ubigeo/distritos")
    public ResponseEntity<Object> distritos(HttpServletRequest request) {
        return AdminV1Responses.page(ubigeo.distritos(request));
    }

    @Operation(summary = "Productos SUNAT: búsqueda por texto o prefijo de código (q ≥ 2, per_page 1..50, page)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/producto-sunat")
    public ResponseEntity<Object> buscarProducto(HttpServletRequest request) {
        ResponseEntity<Object> v1 = productos.search(request);
        Object rows = AdminV1Responses.successBodyData(v1);
        Object total = v1.getBody() instanceof Map<?, ?> b ? b.get("total") : null;
        return AdminResponse.page(rows instanceof List<?> l ? l : List.of(), total instanceof Number n ? n.longValue() : 0);
    }

    @Operation(summary = "Segmentos UNSPSC")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/producto-sunat/segmentos")
    public ResponseEntity<Object> segmentos() {
        return AdminV1Responses.page(productos.segmentos());
    }

    @Operation(summary = "Familias de un segmento (segmento=NN)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/producto-sunat/familias")
    public ResponseEntity<Object> familias(HttpServletRequest request) {
        return AdminV1Responses.page(productos.familias(request));
    }

    @Operation(summary = "Clases de una familia (familia=NNNN)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/producto-sunat/clases")
    public ResponseEntity<Object> clases(HttpServletRequest request) {
        return AdminV1Responses.page(productos.clases(request));
    }

    @Operation(summary = "Productos de una clase (clase=NNNNNN)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/producto-sunat/productos")
    public ResponseEntity<Object> productosDeClase(HttpServletRequest request) {
        return AdminV1Responses.page(productos.productos(request));
    }

    @Operation(summary = "Validar un código UNSPSC de 8 dígitos y ver su jerarquía")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/producto-sunat/{codigo:[0-9]{8}}")
    public ResponseEntity<Object> producto(@PathVariable String codigo) {
        return AdminV1Responses.data(productos.show(codigo));
    }
}
