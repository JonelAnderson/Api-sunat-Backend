package pe.facturacion.api.modules.guide;

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
 * Filas de {@code dispatch_guides} como mapas columna → valor (jsonb decodificado, fechas como
 * {@code LocalDate}/{@code LocalDateTime}). Solo guías sin borrado lógico.
 */
@Repository
public class GuideRepository {

    static final String TABLE = "dispatch_guides";
    static final List<String> JSON = List.of("remitente", "doc_relacionado", "datos_subcontratador", "datos_pagador_flete",
            "autorizacion_especial", "transportista", "vehiculo", "conductor", "indicadores", "items", "tercero", "comprador");

    private final NamedParameterJdbcTemplate jdbc;

    public GuideRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Map<String, Object>> find(long tenantId, long id) {
        return jdbc.queryForList("SELECT * FROM " + TABLE + " WHERE id = :id AND tenant_id = :t AND deleted_at IS NULL",
                new MapSqlParameterSource().addValue("id", id).addValue("t", tenantId)).stream().map(GuideRepository::row).findFirst();
    }

    public Optional<Map<String, Object>> findById(long id) {
        return jdbc.queryForList("SELECT * FROM " + TABLE + " WHERE id = :id AND deleted_at IS NULL", Map.of("id", id))
                .stream().map(GuideRepository::row).findFirst();
    }

    public List<Map<String, Object>> page(long tenantId, int limit, int offset) {
        return page(tenantId, "", new MapSqlParameterSource(), limit, offset);
    }

    /** {@code filtros}: condiciones extra (" AND ...") con sus parámetros en {@code p}. */
    public List<Map<String, Object>> page(long tenantId, String filtros, MapSqlParameterSource p, int limit, int offset) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM " + TABLE + " WHERE tenant_id = :t AND deleted_at IS NULL" + filtros
                + " ORDER BY created_at DESC, id DESC LIMIT :l OFFSET :o",
                p.addValue("t", tenantId).addValue("l", limit).addValue("o", offset))) {
            rows.add(row(r));
        }
        return rows;
    }

    public long count(long tenantId) {
        return count(tenantId, "", new MapSqlParameterSource());
    }

    public long count(long tenantId, String filtros, MapSqlParameterSource p) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + TABLE + " WHERE tenant_id = :t AND deleted_at IS NULL" + filtros,
                p.addValue("t", tenantId), Long.class);
        return n == null ? 0 : n;
    }

    public boolean existsCorrelativo(long tenantId, String serie, long correlativo) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + TABLE + " WHERE tenant_id = :t AND serie = :s AND correlativo = :c "
                + "AND deleted_at IS NULL", new MapSqlParameterSource().addValue("t", tenantId).addValue("s", serie).addValue("c", correlativo),
                Long.class);
        return n != null && n > 0;
    }

    public long insert(Map<String, Object> columns) {
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
        jdbc.update("INSERT INTO " + TABLE + " (" + names + ") VALUES (" + values + ")", p, keys, new String[] {"id"});
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
}
