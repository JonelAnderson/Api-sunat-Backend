package pe.facturacion.api.modules.settlement;

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
 * Filas de {@code purchase_settlements}, sus ítems y sus pagos ({@code payments} con
 * {@code payable_type = 'purchase_settlement'}) como mapas columna → valor.
 */
@Repository
public class SettlementRepository {

    public static final String TABLE = "purchase_settlements";
    static final String ITEMS = "purchase_settlement_items";
    static final String PAYABLE = "purchase_settlement";
    private static final List<String> JSON = List.of("guias", "sunat_notes", "oro");

    private final NamedParameterJdbcTemplate jdbc;

    public SettlementRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Map<String, Object>> find(long tenantId, long id) {
        return one("SELECT * FROM " + TABLE + " WHERE id = :id AND tenant_id = :t AND deleted_at IS NULL",
                new MapSqlParameterSource().addValue("id", id).addValue("t", tenantId));
    }

    public Optional<Map<String, Object>> findById(long id) {
        return one("SELECT * FROM " + TABLE + " WHERE id = :id AND deleted_at IS NULL", new MapSqlParameterSource("id", id));
    }

    public Optional<Map<String, Object>> findBySerie(long tenantId, String serie, long correlativo) {
        return one("SELECT * FROM " + TABLE + " WHERE tenant_id = :t AND serie = :s AND correlativo = :c AND deleted_at IS NULL",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("s", serie).addValue("c", correlativo));
    }

    private Optional<Map<String, Object>> one(String sql, MapSqlParameterSource p) {
        return jdbc.queryForList(sql, p).stream().map(SettlementRepository::row).findFirst();
    }

    public List<Map<String, Object>> query(String where, MapSqlParameterSource p, String order, int limit, int offset) {
        p.addValue("limit", limit).addValue("offset", offset);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM " + TABLE + " WHERE " + where + " AND deleted_at IS NULL ORDER BY "
                + order + " LIMIT :limit OFFSET :offset", p)) {
            rows.add(row(r));
        }
        return rows;
    }

    public long count(String where, MapSqlParameterSource p) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + TABLE + " WHERE " + where + " AND deleted_at IS NULL", p, Long.class);
        return n == null ? 0 : n;
    }

    public BigDecimal sum(String column, String where, MapSqlParameterSource p) {
        BigDecimal n = jdbc.queryForObject("SELECT COALESCE(sum(" + column + "), 0) FROM " + TABLE + " WHERE " + where
                + " AND deleted_at IS NULL", p, BigDecimal.class);
        return n == null ? BigDecimal.ZERO : n;
    }

    public List<Map<String, Object>> items(long id) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM " + ITEMS + " WHERE purchase_settlement_id = :id ORDER BY id",
                Map.of("id", id))) {
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

    public void update(long id, Map<String, Object> changes) {
        StringJoiner set = new StringJoiner(", ");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id).addValue("updated_at", Clock.nowUtc().withNano(0));
        changes.forEach((k, v) -> {
            set.add(k + " = " + (JSON.contains(k) ? "CAST(:" + k + " AS jsonb)" : ":" + k));
            p.addValue(k, JSON.contains(k) ? (v == null ? null : PhpJson.encode(v)) : v);
        });
        set.add("updated_at = :updated_at");
        jdbc.update("UPDATE " + TABLE + " SET " + set + " WHERE id = :id", p);
    }

    // ----------------------------------------------------------------- pagos

    public List<Map<String, Object>> payments(long id) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM payments WHERE payable_type = :pt AND payable_id = :id ORDER BY id",
                new MapSqlParameterSource().addValue("pt", PAYABLE).addValue("id", id))) {
            rows.add(row(r));
        }
        return rows;
    }

    public void insertPayment(long tenantId, long id, Map<String, Object> pago) {
        Map<String, Object> c = new LinkedHashMap<>(pago);
        c.put("tenant_id", tenantId);
        c.put("payable_type", PAYABLE);
        c.put("payable_id", id);
        insert("payments", c);
    }

    public int deletePayment(long id, long paymentId) {
        return jdbc.update("DELETE FROM payments WHERE id = :p AND payable_type = :pt AND payable_id = :id",
                new MapSqlParameterSource().addValue("p", paymentId).addValue("pt", PAYABLE).addValue("id", id));
    }

    public BigDecimal sumPayments(long id) {
        BigDecimal n = jdbc.queryForObject("SELECT COALESCE(sum(monto), 0) FROM payments WHERE payable_type = :pt AND payable_id = :id",
                new MapSqlParameterSource().addValue("pt", PAYABLE).addValue("id", id), BigDecimal.class);
        return n == null ? BigDecimal.ZERO : n;
    }

    static Map<String, Object> row(Map<String, Object> r) {
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
}
