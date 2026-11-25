package pe.facturacion.api.modules.catalog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.shared.api.Paginator;
import pe.facturacion.api.shared.http.Input;

/**
 * Buscador del Código de Producto SUNAT (UNSPSC v14, catálogo 25), equivalente a
 * {@code CatalogoProductoController} de API-PRO (respuestas sin {@code mensaje}).
 */
@RestController
@RequestMapping("/api/v1/catalogos/producto-sunat")
@Tag(name = "Catálogos")
public class CatalogoProductoController {

    private final JdbcTemplate jdbc;

    public CatalogoProductoController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Operation(summary = "Buscar por texto o por prefijo de código (q, per_page 1..50, page)")
    @GetMapping
    public ResponseEntity<Object> search(HttpServletRequest request) {
        Input input = Input.of(request);
        String q = input.string("q") == null ? "" : input.string("q").trim();
        int perPage = Math.min(50, Math.max(1, Input.intval(input.get("per_page", 20))));
        if (q.codePointCount(0, q.length()) < 2) {
            return error("El parámetro \"q\" debe tener al menos 2 caracteres.");
        }
        int page = Paginator.page(input);
        int offset = Paginator.offset(page, perPage);

        String where;
        String order;
        List<Object> params = new ArrayList<>();
        List<Object> orderParams = new ArrayList<>();
        if (q.chars().allMatch(Character::isDigit)) {
            where = "codigo LIKE ?";
            params.add(q + "%");
            order = "codigo";
        } else {
            String[] words = q.split("\\s+");
            List<String> conds = new ArrayList<>();
            for (String w : words) {
                conds.add("f_unaccent_lower(descripcion) LIKE f_unaccent_lower(?)");
                params.add("%" + w + "%");
            }
            where = String.join(" AND ", conds);
            order = "CASE WHEN f_unaccent_lower(descripcion) LIKE f_unaccent_lower(?) THEN 0 ELSE 1 END, char_length(descripcion), codigo";
            orderParams.add(words[0] + "%");
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM unspsc_codes WHERE " + where, Long.class, params.toArray());
        List<Object> all = new ArrayList<>(params);
        all.addAll(orderParams);
        all.add(perPage);
        all.add(offset);
        List<Map<String, Object>> datos = jdbc.query(
                "SELECT codigo, descripcion, clase FROM unspsc_codes WHERE " + where + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("codigo", rs.getString("codigo"));
                    m.put("descripcion", rs.getString("descripcion"));
                    m.put("clase", rs.getString("clase"));
                    return m;
                }, all.toArray());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "exito");
        body.put("total", total == null ? 0 : total);
        body.put("pagina", page);
        body.put("por_pagina", perPage);
        body.put("datos", datos);
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Segmentos UNSPSC")
    @GetMapping("/segmentos")
    public ResponseEntity<Object> segmentos() {
        return nivel("segmento", null);
    }

    @Operation(summary = "Familias de un segmento (segmento=NN)")
    @GetMapping("/familias")
    public ResponseEntity<Object> familias(HttpServletRequest request) {
        String segmento = param(request, "segmento");
        if (!segmento.matches("^\\d{2}$")) {
            return paramError("segmento", "2 dígitos");
        }
        return nivel("familia", segmento);
    }

    @Operation(summary = "Clases de una familia (familia=NNNN)")
    @GetMapping("/clases")
    public ResponseEntity<Object> clases(HttpServletRequest request) {
        String familia = param(request, "familia");
        if (!familia.matches("^\\d{4}$")) {
            return paramError("familia", "4 dígitos");
        }
        return nivel("clase", familia);
    }

    @Operation(summary = "Productos de una clase (clase=NNNNNN)")
    @GetMapping("/productos")
    public ResponseEntity<Object> productos(HttpServletRequest request) {
        String clase = param(request, "clase");
        if (!clase.matches("^\\d{6}$")) {
            return paramError("clase", "6 dígitos");
        }
        List<Map<String, Object>> items = jdbc.query(
                "SELECT codigo, descripcion FROM unspsc_codes WHERE clase = ? AND codigo NOT LIKE '%00' ORDER BY codigo",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("codigo", rs.getString("codigo"));
                    m.put("descripcion", rs.getString("descripcion"));
                    return m;
                }, clase);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "exito");
        body.put("total", items.size());
        body.put("datos", items);
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Validar un código de 8 dígitos y ver su jerarquía")
    @GetMapping("/{codigo:[0-9]{8}}")
    public ResponseEntity<Object> show(@PathVariable String codigo) {
        List<String> desc = jdbc.queryForList("SELECT descripcion FROM unspsc_codes WHERE codigo = ?", String.class, codigo);
        if (desc.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("estado", "error");
            body.put("valido", false);
            body.put("mensaje", "El código " + codigo + " no existe en el catálogo UNSPSC v14.");
            return ResponseEntity.status(404).body(body);
        }
        Map<String, String> niveles = new LinkedHashMap<>();
        niveles.put("segmento", codigo.substring(0, 2));
        niveles.put("familia", codigo.substring(0, 4));
        niveles.put("clase", codigo.substring(0, 6));
        Map<String, String> nombres = new LinkedHashMap<>();
        jdbc.query("SELECT nivel, codigo, nombre FROM unspsc_taxonomy WHERE codigo IN (?, ?, ?)",
                rs -> {
                    nombres.put(rs.getString("nivel") + ":" + rs.getString("codigo"), rs.getString("nombre"));
                }, niveles.get("segmento"), niveles.get("familia"), niveles.get("clase"));
        Map<String, Object> jerarquia = new LinkedHashMap<>();
        niveles.forEach((nivel, cod) -> {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("codigo", cod);
            n.put("nombre", nombres.get(nivel + ":" + cod));
            jerarquia.put(nivel, n);
        });
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("codigo", codigo);
        datos.put("descripcion", desc.get(0));
        datos.put("jerarquia", jerarquia);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "exito");
        body.put("valido", true);
        body.put("datos", datos);
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Object> nivel(String nivel, String parent) {
        String sql = "SELECT codigo, nombre FROM unspsc_taxonomy WHERE nivel = ?" + (parent == null ? "" : " AND parent = ?") + " ORDER BY codigo";
        Object[] args = parent == null ? new Object[] {nivel} : new Object[] {nivel, parent};
        List<Map<String, Object>> items = jdbc.query(sql, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("codigo", rs.getString("codigo"));
            m.put("nombre", rs.getString("nombre"));
            return m;
        }, args);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "exito");
        body.put("total", items.size());
        body.put("datos", items);
        return ResponseEntity.ok(body);
    }

    private static String param(HttpServletRequest request, String name) {
        String v = Input.of(request).string(name);
        return v == null ? "" : v;
    }

    private static ResponseEntity<Object> paramError(String param, String formato) {
        return error("El parámetro \"" + param + "\" es obligatorio y debe tener " + formato + ".");
    }

    private static ResponseEntity<Object> error(String mensaje) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "error");
        body.put("mensaje", mensaje);
        return ResponseEntity.status(422).body(body);
    }
}
