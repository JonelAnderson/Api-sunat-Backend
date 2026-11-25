package pe.facturacion.api.modules.ticket;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

/** Filas de {@code summaries} (resumen diario) y {@code voided_documents} (comunicación de baja). */
@Repository
public class TicketRepository {

    /** Documento resuelto por ticket. */
    public static class TicketDoc {
        public String table;
        public Long id;
        public Long tenantId;
        public String identifier;
        public String correlativo;
        /** summaries.fecha_referencia / voided_documents.fecha_generacion */
        public LocalDate fechaReferencia;
        /** summaries.fecha_envio / voided_documents.fecha_comunicacion */
        public LocalDate fechaEnvio;
        public int totalDocumentos;
        /** Solo resumen: envio / anulacion. */
        public String tipo;
        /** Resumen: ids de boletas. Baja: detalles. */
        public Object documentIds;
        public Object detalles;
        public String motivo;
        public Long anuladoPor;
        public String xmlPath;
        public String cdrPath;
        public String ticket;
        public String sunatStatus;
        public String sunatCode;
        public String sunatDescription;
        public Object sunatNotes;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;

        public boolean isSummary() {
            return "summaries".equals(table);
        }
    }

    public static final String SUMMARIES = "summaries";
    public static final String VOIDED = "voided_documents";
    private static final List<String> JSON = List.of("document_ids", "detalles", "sunat_notes");

    private final NamedParameterJdbcTemplate jdbc;

    public TicketRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TicketDoc> find(String table, long tenantId, long id) {
        return jdbc.query("SELECT * FROM " + table + " WHERE id = :id AND tenant_id = :t",
                new MapSqlParameterSource().addValue("id", id).addValue("t", tenantId), (rs, n) -> map(table, rs)).stream().findFirst();
    }

    public Optional<TicketDoc> findById(String table, long id) {
        return jdbc.query("SELECT * FROM " + table + " WHERE id = :id", Map.of("id", id), (rs, n) -> map(table, rs)).stream().findFirst();
    }

    public List<TicketDoc> query(String table, String where, MapSqlParameterSource p, String order, int limit, int offset) {
        p.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query("SELECT * FROM " + table + " WHERE " + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset", p,
                (rs, n) -> map(table, rs));
    }

    public List<TicketDoc> all(String table, String where, MapSqlParameterSource p) {
        return jdbc.query("SELECT * FROM " + table + " WHERE " + where + " ORDER BY id DESC", p, (rs, n) -> map(table, rs));
    }

    public long count(String table, String where, MapSqlParameterSource p) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, p, Long.class);
        return n == null ? 0 : n;
    }

    /** Máximo correlativo numérico del día (por fecha de envío / comunicación). */
    public int maxCorrelativo(String table, long tenantId, LocalDate fecha, String identifierPrefix) {
        String col = SUMMARIES.equals(table) ? "fecha_envio" : "fecha_comunicacion";
        Integer n = jdbc.queryForObject("SELECT COALESCE(max(CAST(correlativo AS integer)), 0) FROM " + table
                + " WHERE tenant_id = :t AND " + col + " = :f AND identifier LIKE :p",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("f", fecha).addValue("p", identifierPrefix + "%"), Integer.class);
        return n == null ? 0 : n;
    }

    /** Serializa la numeración del día por empresa (bloqueo transaccional de PostgreSQL). */
    public void lockNumbering(String table, long tenantId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(:k1, :k2)",
                new MapSqlParameterSource().addValue("k1", table.hashCode()).addValue("k2", (int) tenantId));
    }

    public long insert(TicketDoc d) {
        LocalDateTime now = Clock.nowUtc().withNano(0);
        d.createdAt = now;
        d.updatedAt = now;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("tenant_id", d.tenantId);
        c.put("identifier", d.identifier);
        c.put("correlativo", d.correlativo);
        if (d.isSummary()) {
            c.put("fecha_referencia", d.fechaReferencia);
            c.put("fecha_envio", d.fechaEnvio);
            c.put("tipo", d.tipo);
            c.put("document_ids", d.documentIds);
        } else {
            c.put("fecha_generacion", d.fechaReferencia);
            c.put("fecha_comunicacion", d.fechaEnvio);
            c.put("detalles", d.detalles);
        }
        c.put("total_documentos", d.totalDocumentos);
        c.put("motivo", d.motivo);
        c.put("anulado_por", d.anuladoPor);
        c.put("sunat_status", d.sunatStatus);
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
        jdbc.update("INSERT INTO " + d.table + " (" + names + ") VALUES (" + values + ")", p, keys, new String[] {"id"});
        d.id = keys.getKey().longValue();
        return d.id;
    }

    public void update(TicketDoc d, Map<String, Object> changes) {
        LocalDateTime now = Clock.nowUtc().withNano(0);
        StringJoiner set = new StringJoiner(", ");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", d.id).addValue("updated_at", now);
        changes.forEach((k, v) -> {
            set.add(k + " = " + (JSON.contains(k) ? "CAST(:" + k + " AS jsonb)" : ":" + k));
            p.addValue(k, JSON.contains(k) ? (v == null ? null : PhpJson.encode(v)) : v);
        });
        set.add("updated_at = :updated_at");
        jdbc.update("UPDATE " + d.table + " SET " + set + " WHERE id = :id", p);
        d.updatedAt = now;
    }

    /** Actualiza el estado de los documentos de una tabla de comprobantes (sin tocar updated_at de otros). */
    public int updateDocuments(String table, String where, MapSqlParameterSource p, Map<String, Object> changes) {
        if (changes.isEmpty()) {
            return 0;
        }
        StringJoiner set = new StringJoiner(", ");
        changes.forEach((k, v) -> {
            set.add(k + " = " + ("sunat_notes".equals(k) ? "CAST(:set_" + k + " AS jsonb)" : ":set_" + k));
            p.addValue("set_" + k, "sunat_notes".equals(k) ? (v == null ? null : PhpJson.encode(v)) : v);
        });
        p.addValue("set_updated_at", Clock.nowUtc().withNano(0));
        return jdbc.update("UPDATE " + table + " SET " + set + ", updated_at = :set_updated_at WHERE " + where, p);
    }

    private static TicketDoc map(String table, ResultSet rs) throws SQLException {
        TicketDoc d = new TicketDoc();
        boolean summary = SUMMARIES.equals(table);
        d.table = table;
        d.id = rs.getLong("id");
        d.tenantId = rs.getLong("tenant_id");
        d.identifier = rs.getString("identifier");
        d.correlativo = rs.getString("correlativo");
        d.fechaReferencia = date(rs, summary ? "fecha_referencia" : "fecha_generacion");
        d.fechaEnvio = date(rs, summary ? "fecha_envio" : "fecha_comunicacion");
        d.totalDocumentos = rs.getInt("total_documentos");
        if (summary) {
            d.tipo = rs.getString("tipo");
            d.documentIds = json(rs, "document_ids");
        } else {
            d.detalles = json(rs, "detalles");
        }
        d.motivo = rs.getString("motivo");
        d.anuladoPor = (Long) rs.getObject("anulado_por", Long.class);
        d.xmlPath = rs.getString("xml_path");
        d.cdrPath = rs.getString("cdr_path");
        d.ticket = rs.getString("ticket");
        d.sunatStatus = rs.getString("sunat_status");
        d.sunatCode = rs.getString("sunat_code");
        d.sunatDescription = rs.getString("sunat_description");
        d.sunatNotes = json(rs, "sunat_notes");
        d.createdAt = ts(rs, "created_at");
        d.updatedAt = ts(rs, "updated_at");
        return d;
    }

    private static LocalDate date(ResultSet rs, String col) throws SQLException {
        java.sql.Date v = rs.getDate(col);
        return v == null ? null : v.toLocalDate();
    }

    private static LocalDateTime ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toLocalDateTime();
    }

    private static Object json(ResultSet rs, String col) throws SQLException {
        String s = rs.getString(col);
        return s == null ? null : PhpJson.decode(s);
    }
}
