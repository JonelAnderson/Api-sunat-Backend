package pe.facturacion.api.modules.admin.panel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.admin.api.AdminFormat;
import pe.facturacion.api.modules.admin.api.AdminPaging;
import pe.facturacion.api.modules.admin.api.AdminValidation;

/**
 * Todos los documentos electrónicos (de una empresa o de todas) en una sola consulta: {@code UNION ALL} de columnas
 * normalizadas de facturas, boletas, notas de crédito y de débito, guías (09 / 31), retenciones, percepciones,
 * liquidaciones de compra, resúmenes diarios (RC) y comunicaciones de baja / reversiones (RA / RR). Filtra, cuenta y
 * pagina en la BD (sin cargar todo en memoria), como el {@code EmpresaComprobantesQuery} de la referencia, sin las
 * cotizaciones ni las notas de venta (no existen en esta API).
 *
 * <p>Solo lectura de columnas elegidas: nunca rutas de archivos, contenido XML / CDR ni datos de credenciales.
 */
@Component
public class AdminAllDocuments {

    /** Código del tipo → nombre y segmento de la vista de empresa del §9 (para abrir el detalle). */
    public record Tipo(String codigo, String nombre, String vista) {
    }

    public static final List<Tipo> TIPOS = List.of(
            new Tipo("01", "Factura", "facturas"),
            new Tipo("03", "Boleta", "boletas"),
            new Tipo("07", "Nota de crédito", "notas-credito"),
            new Tipo("08", "Nota de débito", "notas-debito"),
            new Tipo("09", "Guía de remisión remitente", "guias-remision"),
            new Tipo("31", "Guía de remisión transportista", "guias-remision"),
            new Tipo("20", "Retención", "retenciones"),
            new Tipo("40", "Percepción", "percepciones"),
            new Tipo("04", "Liquidación de compra", "liquidaciones-compra"),
            new Tipo("RC", "Resumen diario", "resumenes"),
            new Tipo("RA", "Comunicación de baja", "anulaciones"),
            new Tipo("RR", "Reversión", "anulaciones"));

    /** Estados SUNAT de todas las tablas, más {@code observado} (aceptado con observaciones del CDR). */
    public static final List<String> ESTADOS = List.of("pendiente", "enviado", "aceptado", "rechazado", "anulado", "anulacion_en_proceso",
            "reversion_en_proceso", "revertido", "observado");

    /** Orden del listado: parámetro {@code orderBy} → expresión. */
    static final Map<String, String> ORDER = Map.of(
            "fechaEmision", "x.fecha_emision", "createdAt", "x.created_at", "sentAt", "coalesce(x.sent_at, x.created_at)",
            "total", "x.total", "tipo", "x.tipo", "serie", "x.serie", "numero", "x.numero", "estado", "x.estado");

    static final String OBSERVED = AdminDocuments.OBSERVED;

    private static final String CORE = "SELECT '%s'::varchar AS tipo, id, tenant_id, serie::varchar AS serie, correlativo::text AS correlativo, "
            + "(serie || '-' || lpad(correlativo::text, 8, '0'))::varchar AS numero, client_razon_social::varchar AS cliente, "
            + "client_num_doc::varchar AS cliente_doc, tipo_moneda::varchar AS moneda, mto_imp_venta::numeric AS total, "
            + "sunat_status::varchar AS estado, sunat_code::varchar AS sunat_code, sunat_description::text AS sunat_description, "
            + OBSERVED + " AS observado, fecha_emision::timestamp AS fecha_emision, sent_at::timestamp AS sent_at, "
            + "created_at::timestamp AS created_at, sucursal_id::bigint AS sucursal_id, xml_path IS NOT NULL AS has_xml, "
            + "cdr_path IS NOT NULL AS has_cdr, pdf_path IS NOT NULL AS has_pdf FROM %s WHERE deleted_at IS NULL";

    static final List<String> SOURCES = List.of(
            String.format(CORE, "01", "invoices"),
            String.format(CORE, "03", "boletas"),
            String.format(CORE, "07", "credit_notes"),
            String.format(CORE, "08", "debit_notes"),
            "SELECT tipo_documento::varchar AS tipo, id, tenant_id, serie::varchar, correlativo::text, "
                    + "(serie || '-' || lpad(correlativo::text, 8, '0'))::varchar, destinatario_razon_social::varchar, "
                    + "destinatario_num_doc::varchar, NULL::varchar, NULL::numeric, sunat_status::varchar, sunat_code::varchar, "
                    + "sunat_description::text, false, fecha_emision::timestamp, sent_at::timestamp, created_at::timestamp, "
                    + "sucursal_id::bigint, (xml_path IS NOT NULL OR xml_content IS NOT NULL), (cdr_path IS NOT NULL OR cdr_content IS NOT NULL), "
                    + "pdf_path IS NOT NULL FROM dispatch_guides WHERE deleted_at IS NULL",
            "SELECT '20'::varchar, id, tenant_id, serie::varchar, correlativo::text, (serie || '-' || lpad(correlativo::text, 8, '0'))::varchar, "
                    + "proveedor_razon_social::varchar, proveedor_num_doc::varchar, 'PEN'::varchar, imp_retenido::numeric, "
                    + "sunat_status::varchar, sunat_code::varchar, sunat_description::text, " + OBSERVED + ", fecha_emision::timestamp, "
                    + "sent_at::timestamp, created_at::timestamp, NULL::bigint, xml_path IS NOT NULL, cdr_path IS NOT NULL, "
                    + "pdf_path IS NOT NULL FROM retentions WHERE deleted_at IS NULL",
            "SELECT '40'::varchar, id, tenant_id, serie::varchar, correlativo::text, (serie || '-' || lpad(correlativo::text, 8, '0'))::varchar, "
                    + "cliente_razon_social::varchar, cliente_num_doc::varchar, 'PEN'::varchar, imp_percibido::numeric, "
                    + "sunat_status::varchar, sunat_code::varchar, sunat_description::text, " + OBSERVED + ", fecha_emision::timestamp, "
                    + "sent_at::timestamp, created_at::timestamp, NULL::bigint, xml_path IS NOT NULL, cdr_path IS NOT NULL, "
                    + "pdf_path IS NOT NULL FROM perceptions WHERE deleted_at IS NULL",
            "SELECT '04'::varchar, id, tenant_id, serie::varchar, correlativo::text, (serie || '-' || lpad(correlativo::text, 8, '0'))::varchar, "
                    + "vendedor_nombre::varchar, vendedor_num_doc::varchar, tipo_moneda::varchar, sub_total::numeric, "
                    + "sunat_status::varchar, sunat_code::varchar, sunat_description::text, " + OBSERVED + ", fecha_emision::timestamp, "
                    + "sent_at::timestamp, created_at::timestamp, sucursal_id::bigint, xml_path IS NOT NULL, cdr_path IS NOT NULL, "
                    + "pdf_path IS NOT NULL FROM purchase_settlements WHERE deleted_at IS NULL",
            "SELECT 'RC'::varchar, id, tenant_id, NULL::varchar, correlativo::text, identifier::varchar, NULL::varchar, NULL::varchar, "
                    + "NULL::varchar, NULL::numeric, sunat_status::varchar, sunat_code::varchar, sunat_description::text, " + OBSERVED + ", "
                    + "fecha_referencia::timestamp, NULL::timestamp, created_at::timestamp, NULL::bigint, xml_path IS NOT NULL, "
                    + "cdr_path IS NOT NULL, false FROM summaries",
            "SELECT (CASE WHEN identifier LIKE 'RR-%' THEN 'RR' ELSE 'RA' END)::varchar, id, tenant_id, NULL::varchar, correlativo::text, "
                    + "identifier::varchar, NULL::varchar, NULL::varchar, NULL::varchar, NULL::numeric, sunat_status::varchar, "
                    + "sunat_code::varchar, sunat_description::text, " + OBSERVED + ", fecha_generacion::timestamp, NULL::timestamp, "
                    + "created_at::timestamp, NULL::bigint, xml_path IS NOT NULL, cdr_path IS NOT NULL, false FROM voided_documents");

    static final String UNION = String.join(" UNION ALL ", SOURCES);

    /**
     * Filtros: {@code tenantId} (null = todas), días de emisión (Lima, inclusive), tipos, estado, serie (contiene),
     * sucursal y texto (número, serie, cliente, su documento y, sin empresa fija, RUC / razón social del emisor y la
     * descripción de SUNAT).
     */
    public record Filter(Long tenantId, LocalDate start, LocalDate end, Set<String> tipos, String estado, String serie, Long sucursalId,
            String text) {
    }

    private final JdbcTemplate jdbc;

    public AdminAllDocuments(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Filas de la página con el orden indicado (expresión ya validada contra {@link #ORDER}). */
    public List<Map<String, Object>> page(Filter f, AdminPaging paging, String orderExpr, boolean asc) {
        List<Object> args = new ArrayList<>();
        String where = where(f, args);
        args.add(paging.records());
        args.add(paging.offset());
        String dir = asc ? "ASC" : "DESC";
        String sql = "SELECT x.*, t.ruc, t.razon_social FROM (" + UNION + ") x JOIN tenants t ON t.id = x.tenant_id AND t.deleted_at IS NULL"
                + where + " ORDER BY " + orderExpr + " " + dir + " NULLS LAST, x.id " + dir + ", x.tipo LIMIT ? OFFSET ?";
        return jdbc.queryForList(sql, args.toArray()).stream().map(AdminAllDocuments::row).toList();
    }

    public long count(Filter f) {
        List<Object> args = new ArrayList<>();
        String where = where(f, args);
        Long n = jdbc.queryForObject("SELECT count(*) FROM (" + UNION + ") x JOIN tenants t ON t.id = x.tenant_id AND t.deleted_at IS NULL"
                + where, Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** Contadores por tipo y por estado con los filtros dados ({@code porTipo} trae todos los tipos, en 0 si no hay). */
    public Map<String, Object> summary(Filter f) {
        List<Object> args = new ArrayList<>();
        String where = where(f, args);
        Map<String, Long> byTipo = new LinkedHashMap<>();
        TIPOS.forEach(t -> byTipo.put(t.codigo(), 0L));
        Map<String, Long> byEstado = new LinkedHashMap<>();
        ESTADOS.stream().filter(e -> !"observado".equals(e)).forEach(e -> byEstado.put(e, 0L));
        long total = 0;
        long observados = 0;
        for (Map<String, Object> r : jdbc.queryForList("SELECT x.tipo, x.estado, count(*) AS n, count(*) FILTER (WHERE x.observado) AS o FROM ("
                + UNION + ") x JOIN tenants t ON t.id = x.tenant_id AND t.deleted_at IS NULL" + where + " GROUP BY x.tipo, x.estado",
                args.toArray())) {
            long n = AdminFormat.longOrNull(r.get("n"));
            byTipo.merge(String.valueOf(r.get("tipo")), n, Long::sum);
            byEstado.merge(String.valueOf(r.get("estado")), n, Long::sum);
            observados += AdminFormat.longOrNull(r.get("o"));
            total += n;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("porTipo", byTipo);
        m.put("porEstado", byEstado);
        m.put("observados", observados);
        return m;
    }

    static String where(Filter f, List<Object> args) {
        StringBuilder w = new StringBuilder(" WHERE 1 = 1");
        if (f.tenantId() != null) {
            w.append(" AND x.tenant_id = ?");
            args.add(f.tenantId());
        }
        if (f.start() != null) {
            w.append(" AND x.fecha_emision >= ?");
            args.add(f.start().atStartOfDay());
        }
        if (f.end() != null) {
            w.append(" AND x.fecha_emision < ?");
            args.add(f.end().plusDays(1).atStartOfDay());
        }
        if (f.tipos() != null && !f.tipos().isEmpty()) {
            w.append(" AND x.tipo IN (").append(String.join(", ", f.tipos().stream().map(t -> "?").toList())).append(")");
            args.addAll(f.tipos());
        }
        if (f.estado() != null) {
            if ("observado".equals(f.estado())) {
                w.append(" AND x.estado = 'aceptado' AND x.observado");
            } else {
                w.append(" AND x.estado = ?");
                args.add(f.estado());
            }
        }
        if (f.serie() != null) {
            w.append(" AND upper(coalesce(x.serie, '')) LIKE ? ESCAPE '\\'");
            args.add(AdminFormat.likeContains(f.serie().toUpperCase(java.util.Locale.ROOT)));
        }
        if (f.sucursalId() != null) {
            w.append(" AND x.sucursal_id = ?");
            args.add(f.sucursalId());
        }
        if (f.text() != null) {
            String like = AdminFormat.likeContains(f.text());
            String upper = AdminFormat.likeContains(f.text().toUpperCase(java.util.Locale.ROOT));
            w.append(" AND (upper(x.numero) LIKE ? ESCAPE '\\' OR coalesce(x.cliente_doc, '') LIKE ? ESCAPE '\\'"
                    + " OR f_unaccent_lower(coalesce(x.cliente, '')) LIKE (f_unaccent_lower(?)) ESCAPE '\\'");
            args.add(upper);
            args.add(like);
            args.add(like);
            if (f.tenantId() == null) {
                w.append(" OR t.ruc LIKE ? ESCAPE '\\' OR f_unaccent_lower(coalesce(t.razon_social, '')) LIKE (f_unaccent_lower(?)) ESCAPE '\\'"
                        + " OR f_unaccent_lower(coalesce(x.sunat_description, '')) LIKE (f_unaccent_lower(?)) ESCAPE '\\'");
                args.add(like);
                args.add(like);
                args.add(like);
            }
            w.append(")");
        }
        return w.toString();
    }

    // ------------------------------------------------------------------ parámetros (puros)

    /** {@code tipo}: uno o varios códigos separados por coma (01, 03, 07, 08, 09, 31, 20, 40, 04, RC, RA, RR). */
    public static Set<String> parseTipos(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        AdminValidation v = new AdminValidation();
        for (String s : raw.split(",")) {
            String code = s.trim().toUpperCase(java.util.Locale.ROOT);
            if (code.isEmpty()) {
                continue;
            }
            if (tipo(code) == null) {
                v.add("tipo", "Tipo de documento no válido: " + code + ". Use 01, 03, 07, 08, 09, 31, 20, 40, 04, RC, RA o RR.");
            } else {
                out.add(code);
            }
        }
        v.throwIfAny();
        return out;
    }

    /** {@code estado}: un estado SUNAT o {@code observado}; vacío = todos. */
    public static String parseEstado(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!ESTADOS.contains(s)) {
            new AdminValidation().add("estado", "Estado no válido. Use: " + String.join(", ", ESTADOS) + ".").throwIfAny();
        }
        return s;
    }

    public static Tipo tipo(String codigo) {
        return TIPOS.stream().filter(t -> t.codigo().equals(codigo)).findFirst().orElse(null);
    }

    static Map<String, Object> row(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        String codigo = String.valueOf(r.get("tipo"));
        Tipo t = tipo(codigo);
        m.put("id", AdminFormat.longOrNull(r.get("id")));
        m.put("tipo", codigo);
        m.put("tipoNombre", t == null ? codigo : t.nombre());
        m.put("vista", t == null ? null : t.vista());
        m.put("tenantId", AdminFormat.longOrNull(r.get("tenant_id")));
        m.put("ruc", r.get("ruc"));
        m.put("razonSocial", r.get("razon_social"));
        m.put("serie", r.get("serie"));
        m.put("correlativo", r.get("correlativo"));
        m.put("numero", r.get("numero"));
        m.put("cliente", r.get("cliente"));
        m.put("clienteDoc", r.get("cliente_doc"));
        m.put("moneda", r.get("moneda"));
        m.put("total", r.get("total") instanceof BigDecimal b ? b.doubleValue() : null);
        m.put("sunatStatus", r.get("estado"));
        m.put("sunatCode", r.get("sunat_code"));
        m.put("sunatDescription", r.get("sunat_description"));
        m.put("observado", Boolean.TRUE.equals(r.get("observado")));
        m.put("fechaEmision", AdminFormat.day(r.get("fecha_emision")));
        m.put("sentAt", AdminFormat.instant(r.get("sent_at")));
        m.put("createdAt", AdminFormat.instant(r.get("created_at")));
        m.put("sucursalId", AdminFormat.longOrNull(r.get("sucursal_id")));
        m.put("hasXml", Boolean.TRUE.equals(r.get("has_xml")));
        m.put("hasCdr", Boolean.TRUE.equals(r.get("has_cdr")));
        m.put("hasPdf", Boolean.TRUE.equals(r.get("has_pdf")));
        return m;
    }

    /** Rango por defecto de los listados globales: los últimos {@code days} días hasta hoy (Lima). */
    static LocalDate[] lastDays(LocalDate today, int days) {
        return new LocalDate[] {today.minusDays(days - 1L), today};
    }
}
