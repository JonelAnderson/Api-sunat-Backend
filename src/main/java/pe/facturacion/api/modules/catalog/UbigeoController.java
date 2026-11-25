package pe.facturacion.api.modules.catalog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.http.Input;

/** Ubigeos INEI/SUNAT para selects dependientes región → provincia → distrito. */
@RestController
@RequestMapping("/api/v1/catalogos/ubigeo")
@Tag(name = "Catálogos")
public class UbigeoController {

    private final JdbcTemplate jdbc;

    public UbigeoController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Operation(summary = "Regiones")
    @GetMapping("/regiones")
    public ResponseEntity<Object> regiones() {
        return ApiResponse.success(jdbc.queryForList("SELECT id, nombre FROM ubigeo_regiones ORDER BY nombre COLLATE \"es_ci\", id"));
    }

    @Operation(summary = "Provincias de una región (region_id=NN)")
    @GetMapping("/provincias")
    public ResponseEntity<Object> provincias(HttpServletRequest request) {
        String regionId = param(request, "region_id");
        if (!regionId.matches("^\\d{2}$")) {
            return ApiResponse.error("El parámetro region_id es requerido (2 dígitos).", 422);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, nombre, region_id FROM ubigeo_provincias WHERE region_id = ? ORDER BY nombre COLLATE \"es_ci\", id", regionId);
        return ApiResponse.success(rows);
    }

    @Operation(summary = "Distritos de una provincia (provincia_id=NNNN); el id es el ubigeo")
    @GetMapping("/distritos")
    public ResponseEntity<Object> distritos(HttpServletRequest request) {
        String provinciaId = param(request, "provincia_id");
        if (!provinciaId.matches("^\\d{4}$")) {
            return ApiResponse.error("El parámetro provincia_id es requerido (4 dígitos).", 422);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, nombre, provincia_id FROM ubigeo_distritos WHERE provincia_id = ? ORDER BY nombre COLLATE \"es_ci\", id", provinciaId);
        return ApiResponse.success(rows);
    }

    private static String param(HttpServletRequest request, String name) {
        String v = Input.of(request).string(name);
        return v == null ? "" : v;
    }
}
