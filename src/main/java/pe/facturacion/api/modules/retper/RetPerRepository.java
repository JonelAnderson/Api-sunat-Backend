package pe.facturacion.api.modules.retper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.time.Clock;

/**
 * Filas de {@code retentions}/{@code perceptions} y sus ítems como mapas columna → valor:
 * jsonb decodificado, fechas como {@code LocalDate}/{@code LocalDateTime} y decimales como
 * {@code BigDecimal}. Solo filas sin borrado lógico.
 */
@Repository
public class RetPerRepository {

    private static final List<String> JSON = List.of("pagos", "cobros", "tipo_cambio", "sunat_notes");

    private final NamedParameterJdbcTemplate jdbc;

    public RetPerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Map<String, Object>> find(RetPerKind kind, long tenantId, long id) {
        return jdbc.queryForList("SELECT * FROM " + kind.table + " WHERE id = :id AND tenant_id = :t AND deleted_at IS NULL",
                new MapSqlParameterSource().addValue("id", id).addValue("t", tenantId)).stream().map(RetPerRepository::row).findFirst();
    }

    public Optional<Map<String, Object>> findById(RetPerKind kind, long id) {
        return jdbc.queryForList("SELECT * FROM " + kind.table + " WHERE id = :id AND deleted_at IS NULL", Map.of("id", id))
                .stream().map(RetPerRepository::row).findFirst();
    }

    /** Documento por serie y correlativo (reversión). */
    public Optional<Map<String, Object>> findBySerie(RetPerKind kind, long tenantId, String serie, long correlativo) {
        return jdbc.queryForList("SELECT * FROM " + kind.table + " WHERE tenant_id = :t AND serie = :s AND correlativo = :c "
                + "AND deleted_at IS NULL LIMIT 1", new MapSqlParameterSource().addValue("t", tenantId).addValue("s", serie)
                .addValue("c", correlativo)).stream().map(RetPerRepository::row).findFirst();
    }

    public List<Map<String, Object>> query(RetPerKind kind, String where, MapSqlParameterSource p, int limit, int offset) {
        p.addValue("limit", limit).addValue("offset", offset);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM " + kind.table + " WHERE " + where
                + " AND deleted_at IS NULL ORDER BY created_at DESC, id ASC LIMIT :limit OFFSET :offset", p)) {
            rows.add(row(r));
        }
        return rows;
    }

    public long count(RetPerKind kind, String where, MapSqlParameterSource p) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + kind.table + " WHERE " + where + " AND deleted_at IS NULL", p, Long.class);
        return n == null ? 0 : n;
    }

    public boolean existsCorrelativo(RetPerKind kind, long tenantId, String serie, long correlativo) {
        // Como el ValidatesManualCorrelativo de API-PRO: SoftDeletes excluye los borrados
        return count(kind, "tenant_id = :t AND serie = :s AND correlativo = :c",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("s", serie).addValue("c", correlativo)) > 0;
    }

    public List<Map<String, Object>> items(RetPerKind kind, long id) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM " + kind.itemsTable + " WHERE " + kind.foreignKey
                + " = :id ORDER BY id", Map.of("id", id))) {
            rows.add(row(r));
        }
        return rows;
    }

    public long insert(String table, Map<String, Object> columns) {
        LocalDateTime now = Clock.nowUtc().withNano(0);
        Map<String, Object> c = new LinkedHashMap<>(columns);
        c.put("created_at", now);
        c.put("updated_at", now);
        StringJoiner names = new StringJoiner(", ");
        StringJoiner values = new StringJoiner(", ");
        MapSqlParameterSource p = new MapSqlParameterSource();
        c.forEach((k, v) -> {
            names.add(k);
            values.add(JSON.contains(k) ? "CAST(:" + k + " AS jsonb)" : ":" + k);
            p.addValue(k, JSON.contains(k) ? (v == null ? null : PhpJson.encode(v)) : v);
        });
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO " + table + " (" + names + ") VALUES (" + values + ")", p, keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    public void update(RetPerKind kind, long id, Map<String, Object> changes) {
        StringJoiner set = new StringJoiner(", ");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id).addValue("updated_at", Clock.nowUtc().withNano(0));
        changes.forEach((k, v) -> {
            set.add(k + " = " + (JSON.contains(k) ? "CAST(:" + k + " AS jsonb)" : ":" + k));
            p.addValue(k, JSON.contains(k) ? (v == null ? null : PhpJson.encode(v)) : v);
        });
        set.add("updated_at = :updated_at");
        jdbc.update("UPDATE " + kind.table + " SET " + set + " WHERE id = :id", p);
    }

    /** Cambia el estado de los documentos indicados (opcionalmente solo desde un estado). */
    public int updateStatus(RetPerKind kind, long tenantId, String serie, long correlativo, String estado, String soloDesde) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", tenantId).addValue("s", serie).addValue("c", correlativo)
                .addValue("e", estado).addValue("u", Clock.nowUtc().withNano(0));
        String where = "tenant_id = :t AND serie = :s AND correlativo = :c AND deleted_at IS NULL";
        if (soloDesde != null) {
            p.addValue("desde", soloDesde);
            where += " AND sunat_status = :desde";
        }
        return jdbc.update("UPDATE " + kind.table + " SET sunat_status = :e, updated_at = :u WHERE " + where, p);
    }

    private static Map<String, Object> row(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        r.forEach((k, v) -> {
            if (JSON.contains(k) && v != null) {
                // jsonb llega como PGobject (driver en scope runtime): su toString() es el JSON
                m.put(k, PhpJson.decode(v.toString()));
            } else if (v instanceof Timestamp t) {
                m.put(k, t.toLocalDateTime());
            } else if (v instanceof java.sql.Date d) {
                m.put(k, d.toLocalDate());
            } else {
                m.put(k, v);
            }
        });
        return m;
    }

    /** Cast {@code (float)} de un decimal de Eloquent. */
    public static double f(Object v) {
        return v instanceof BigDecimal b ? b.doubleValue() : v instanceof Number n ? n.doubleValue() : 0;
    }
}
