package pe.facturacion.api.modules.consulta;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.validation.Validator;

/**
 * {@code GET /api/v1/buscar-documento} ({@code ConsultController::lookupDocument}): primero en
 * los clientes de la empresa ({@code fuente: local}); si no está y es RUC o DNI, en el proveedor
 * externo ({@code source: sunat|reniec}).
 */
@RestController
@Tag(name = "Consultas")
public class BuscarDocumentoController {

    private final NamedParameterJdbcTemplate jdbc;
    private final DocumentLookupService lookup;

    public BuscarDocumentoController(NamedParameterJdbcTemplate jdbc, DocumentLookupService lookup) {
        this.jdbc = jdbc;
        this.lookup = lookup;
    }

    @Operation(summary = "Buscar RUC/DNI en los clientes y en SUNAT/RENIEC (tipo, numero)")
    @GetMapping("/api/v1/buscar-documento")
    public ResponseEntity<Object> buscar(HttpServletRequest http) {
        return buscar(ApiContext.requireTenant(http), Input.of(http));
    }

    /** Búsqueda para la empresa indicada (la usa también el panel de administración en sus formularios de emisión). */
    public ResponseEntity<Object> buscar(Tenant t, Input in) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("tipo", "required|string|in:1,4,6,7,0");
        rules.put("numero", "required|string|min:8|max:15");
        Validator.make(in.all(), rules).validate();
        String tipo = in.string("tipo");
        String numero = in.string("numero");
        List<Map<String, Object>> local = jdbc.queryForList("SELECT tipo_documento, numero_documento, razon_social, direccion FROM clients "
                + "WHERE tenant_id = :t AND tipo_documento = :tipo AND numero_documento = :n ORDER BY id LIMIT 1",
                new MapSqlParameterSource("t", t.getId()).addValue("tipo", tipo).addValue("n", numero));
        if (!local.isEmpty()) {
            Map<String, Object> c = local.get(0);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("tipo_doc", c.get("tipo_documento"));
            d.put("num_doc", c.get("numero_documento"));
            d.put("razon_social", c.get("razon_social"));
            d.put("direccion", c.get("direccion") == null ? "" : c.get("direccion"));
            d.put("fuente", "local");
            return ApiResponse.success(d);
        }
        if ("1".equals(tipo) || "6".equals(tipo)) {
            Map<String, Object> r = lookup.lookup(tipo, numero);
            if (r != null) {
                return ApiResponse.success(r);
            }
        }
        return ApiResponse.error("No se encontró información para el documento ingresado", 404);
    }
}
