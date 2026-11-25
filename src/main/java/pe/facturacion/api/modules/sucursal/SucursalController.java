package pe.facturacion.api.modules.sucursal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiException;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.validation.Validator;

/** {@code Route::apiResource('sucursales')} ({@code SucursalController} de API-PRO). */
@RestController
@RequestMapping("/api/v1/sucursales")
@Tag(name = "Sucursales")
public class SucursalController {

    private final SucursalRepository sucursales;
    private final JdbcTemplate jdbc;

    public SucursalController(SucursalRepository sucursales, JdbcTemplate jdbc) {
        this.sucursales = sucursales;
        this.jdbc = jdbc;
    }

    @Operation(summary = "Listar sucursales (sin paginación)")
    @GetMapping
    public ResponseEntity<Object> index(HttpServletRequest request) {
        return index(ApiContext.requireTenant(request));
    }

    /** Sucursales de la empresa indicada (lo usa también el panel de administración con la misma respuesta). */
    public ResponseEntity<Object> index(Tenant tenant) {
        List<Map<String, Object>> list = sucursales.listForTenant(tenant.getId()).stream()
                .map(s -> format(s, seriesCount(s.getId()))).toList();
        return ApiResponse.success(list);
    }

    @Operation(summary = "Crear sucursal")
    @PostMapping
    @Transactional
    public ResponseEntity<Object> store(HttpServletRequest request) {
        return store(ApiContext.requireTenant(request), Input.of(request));
    }

    /** Crear una sucursal de la empresa indicada (lo usa también el panel de administración con la misma respuesta). */
    @Transactional
    public ResponseEntity<Object> store(Tenant tenant, Input input) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("nombre", "required|string|max:100");
        rules.put("cod_local", List.of("required", "string", "size:4", "regex:/^[0-9]{4}$/"));
        rules.put("direccion", "required|string|max:500");
        rules.put("ubigeo", "required|string|between:4,6");
        rules.put("telefono", "nullable|string|max:20");
        rules.put("email", "nullable|email|max:255");
        rules.put("es_principal", "sometimes|boolean");
        Validator.make(input.all(), rules)
                .messages(Map.of("cod_local.regex", "El código de local debe ser 4 dígitos (ej: 0000, 0001)."))
                .validate();

        String codLocal = input.string("cod_local");
        if (sucursales.existsByTenantIdAndCodLocal(tenant.getId(), codLocal)) {
            return ApiResponse.error("El código de local " + codLocal + " ya existe.", 409);
        }
        if (input.bool("es_principal")) {
            sucursales.clearPrincipal(tenant.getId(), -1L);
        }
        boolean isFirst = sucursales.countByTenantId(tenant.getId()) == 0;

        Sucursal s = new Sucursal();
        s.setTenantId(tenant.getId());
        s.setNombre(input.string("nombre"));
        s.setCodLocal(codLocal);
        s.setDireccion(input.string("direccion"));
        s.setUbigeo(input.string("ubigeo"));
        s.setTelefono(input.string("telefono"));
        s.setEmail(input.string("email"));
        s.setPrincipal(input.bool("es_principal") || isFirst);
        s.setActive(true);
        sucursales.save(s);
        return ApiResponse.created(format(s, 0));
    }

    @Operation(summary = "Ver sucursal")
    @GetMapping("/{id}")
    public ResponseEntity<Object> show(HttpServletRequest request, @PathVariable long id) {
        Sucursal s = find(request, id);
        return ApiResponse.success(format(s, seriesCount(s.getId())));
    }

    @Operation(summary = "Actualizar sucursal (cod_local no es editable)")
    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    @Transactional
    public ResponseEntity<Object> update(HttpServletRequest request, @PathVariable long id) {
        Tenant tenant = ApiContext.requireTenant(request);
        return update(tenant, Input.of(request), id);
    }

    /** Actualizar una sucursal de la empresa indicada (404 si es de otra). */
    @Transactional
    public ResponseEntity<Object> update(Tenant tenant, Input input, long id) {
        Sucursal s = find(tenant, id);
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("nombre", "sometimes|string|max:100");
        rules.put("direccion", "sometimes|string|max:500");
        rules.put("ubigeo", "sometimes|string|between:4,6");
        rules.put("telefono", "sometimes|nullable|string|max:20");
        rules.put("email", "sometimes|nullable|email|max:255");
        rules.put("es_principal", "sometimes|boolean");
        rules.put("activo", "sometimes|boolean");
        Validator.make(input.all(), rules).validate();

        if (input.bool("es_principal")) {
            sucursales.clearPrincipal(tenant.getId(), s.getId());
        }
        if (input.has("nombre")) {
            s.setNombre(input.string("nombre"));
        }
        if (input.has("direccion")) {
            s.setDireccion(input.string("direccion"));
        }
        if (input.has("ubigeo")) {
            s.setUbigeo(input.string("ubigeo"));
        }
        if (input.has("telefono")) {
            s.setTelefono(input.string("telefono"));
        }
        if (input.has("email")) {
            s.setEmail(input.string("email"));
        }
        if (input.has("es_principal")) {
            s.setPrincipal(input.bool("es_principal"));
        }
        if (input.has("activo")) {
            s.setActive(input.bool("activo"));
        }
        sucursales.save(s);
        // API-PRO no recarga el conteo tras actualizar: total_series sale 0.
        return ApiResponse.success(format(s, 0), "Sucursal actualizada.");
    }

    @Operation(summary = "Eliminar sucursal (sin series y no principal)")
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Object> destroy(HttpServletRequest request, @PathVariable long id) {
        return destroy(ApiContext.requireTenant(request), id);
    }

    /** Eliminar una sucursal de la empresa indicada (sin series y no principal; 404 si es de otra). */
    @Transactional
    public ResponseEntity<Object> destroy(Tenant tenant, long id) {
        Sucursal s = find(tenant, id);
        int count = seriesCount(s.getId());
        if (count > 0) {
            return ApiResponse.error("No se puede eliminar: tiene " + count + " serie(s) asociada(s). Reasigne las series primero.", 409);
        }
        if (s.isPrincipal()) {
            return ApiResponse.error("No se puede eliminar la sucursal principal. Asigne otra como principal primero.", 409);
        }
        sucursales.delete(s);
        return ApiResponse.success(null, "Sucursal eliminada.");
    }

    private Sucursal find(HttpServletRequest request, long id) {
        return find(ApiContext.requireTenant(request), id);
    }

    private Sucursal find(Tenant tenant, long id) {
        return sucursales.findByIdAndTenantId(id, tenant.getId()).orElseThrow(ApiException::notFound);
    }

    private int seriesCount(long sucursalId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM series WHERE sucursal_id = ?", Integer.class, sucursalId);
        return n == null ? 0 : n;
    }

    static Map<String, Object> format(Sucursal s, int totalSeries) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("nombre", s.getNombre());
        m.put("cod_local", s.getCodLocal());
        m.put("direccion", s.getDireccion());
        m.put("ubigeo", s.getUbigeo());
        m.put("telefono", s.getTelefono());
        m.put("email", s.getEmail());
        m.put("es_principal", s.isPrincipal());
        m.put("activo", s.isActive());
        m.put("total_series", totalSeries);
        return m;
    }
}
