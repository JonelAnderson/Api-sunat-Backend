package pe.facturacion.api.modules.admin.panel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.admin.api.AdminFormat;
import pe.facturacion.api.modules.admin.api.AdminPaging;
import pe.facturacion.api.shared.time.Clock;

/**
 * Métricas globales del tablero, las del {@code DashboardController} de la referencia (empresas, documentos de hoy,
 * del mes y del anterior, ventas y crecimiento, documentos por día y por tipo, empresas por plan, régimen y entorno,
 * estado SUNAT, top empresas y últimos envíos). Son ~15 consultas agregadas sobre todas las empresas: se guardan
 * {@link #TTL_NANOS} por instancia.
 *
 * <p>Correcciones respecto de la referencia: los rangos de días van de las 00:00 al día siguiente (allí
 * {@code whereBetween} con la fecha sola dejaba fuera el día de hoy) y las ventas suman solo comprobantes en soles
 * ({@code PEN}): no se mezclan monedas.
 */
@Component
public class AdminDashboardMetrics {

    static final long TTL_NANOS = 60_000_000_000L;
    static final int DAYS = 30;
    static final int TOP = 5;
    static final int RECENT = 8;

    private record Cached(Map<String, Object> data, long loadedAt) {
    }

    private final JdbcTemplate jdbc;
    private final AdminAllDocuments documents;
    private volatile Cached cached;

    public AdminDashboardMetrics(JdbcTemplate jdbc, AdminAllDocuments documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    public Map<String, Object> get() {
        long now = System.nanoTime();
        Cached c = cached;
        if (c != null && now - c.loadedAt() <= TTL_NANOS) {
            return c.data();
        }
        Map<String, Object> data = compute(Clock.todayLima());
        cached = new Cached(data, now);
        return data;
    }

    Map<String, Object> compute(LocalDate today) {
        YearMonth month = YearMonth.from(today);
        LocalDateTime monthStart = month.atDay(1).atStartOfDay();
        LocalDateTime tomorrow = today.plusDays(1).atStartOfDay();
        LocalDateTime prevStart = month.minusMonths(1).atDay(1).atStartOfDay();

        long docsToday = countDocs(today.atStartOfDay(), tomorrow);
        long docsMonth = countDocs(monthStart, tomorrow);
        long docsPrev = countDocs(prevStart, monthStart);
        double salesMonth = sumSales(monthStart, tomorrow);
        double salesPrev = sumSales(prevStart, monthStart);

        Map<String, Object> m = new LinkedHashMap<>();
        Long total = jdbc.queryForObject("SELECT count(*) FROM tenants WHERE deleted_at IS NULL", Long.class);
        m.put("tenantsTotal", total == null ? 0 : total);
        m.put("documentsToday", docsToday);
        m.put("documentsPreviousMonth", docsPrev);
        m.put("documentsGrowth", growth(docsMonth, docsPrev));
        m.put("salesThisMonth", salesMonth);
        m.put("salesPreviousMonth", salesPrev);
        m.put("salesGrowth", growth(salesMonth, salesPrev));
        m.put("documentsByDay", byDay(today));
        m.put("documentsByType", byType(monthStart, tomorrow));
        m.put("tenantsByPlan", tenantsBy("plan"));
        m.put("tenantsByRegime", tenantsBy("tax_regime"));
        m.put("tenantsByEnvironment", tenantsBy("environment"));
        m.put("sunatStatusThisMonth", statusThisMonth(monthStart, tomorrow));
        m.put("topTenants", topTenants(monthStart, tomorrow));
        m.put("recentSubmissions", documents.page(new AdminAllDocuments.Filter(null, today.minusDays(DAYS - 1L), today, null, null, null, null, null),
                new AdminPaging(RECENT, 1), AdminAllDocuments.ORDER.get("sentAt"), false));
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("monthStart", month.atDay(1).toString());
        period.put("today", today.toString());
        m.put("period", period);
        m.put("metricsAt", AdminFormat.instant(Clock.now()));
        return m;
    }

    /** Facturas, boletas, notas de crédito y de débito emitidas en [desde, hasta). */
    private long countDocs(LocalDateTime from, LocalDateTime to) {
        List<Object> args = new ArrayList<>();
        String sql = AdminDocuments.union("count(*) AS n", "fecha_emision >= ? AND fecha_emision < ?", List.of(from, to), args);
        Long n = jdbc.queryForObject("SELECT coalesce(sum(n), 0) FROM (" + sql + ") x", Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** Ventas aceptadas en soles: facturas y boletas (como la referencia). */
    private double sumSales(LocalDateTime from, LocalDateTime to) {
        BigDecimal s = jdbc.queryForObject("SELECT coalesce(sum(t), 0) FROM ("
                + "SELECT sum(mto_imp_venta) AS t FROM invoices WHERE deleted_at IS NULL AND sunat_status = 'aceptado' AND tipo_moneda = 'PEN' "
                + "AND fecha_emision >= ? AND fecha_emision < ? UNION ALL "
                + "SELECT sum(mto_imp_venta) FROM boletas WHERE deleted_at IS NULL AND sunat_status = 'aceptado' AND tipo_moneda = 'PEN' "
                + "AND fecha_emision >= ? AND fecha_emision < ?) x", BigDecimal.class, from, to, from, to);
        return s == null ? 0 : s.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** Variación porcentual con un decimal; null si el periodo anterior es 0 (como la referencia). */
    static Double growth(double current, double previous) {
        if (previous <= 0) {
            return null;
        }
        return BigDecimal.valueOf((current - previous) / previous * 100).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** Últimos 30 días (todos, aunque no tengan documentos): facturas, boletas y notas (crédito + débito). */
    private List<Map<String, Object>> byDay(LocalDate today) {
        LocalDate first = today.minusDays(DAYS - 1L);
        Map<String, Map<String, Object>> days = new LinkedHashMap<>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toString());
            row.put("facturas", 0L);
            row.put("boletas", 0L);
            row.put("notas", 0L);
            days.put(d.toString(), row);
        }
        List<Object> args = new ArrayList<>();
        String sql = AdminDocuments.union("{tipo} AS tipo, fecha_emision", "fecha_emision >= ? AND fecha_emision < ?",
                List.of(first.atStartOfDay(), today.plusDays(1).atStartOfDay()), args);
        for (Map<String, Object> r : jdbc.queryForList("SELECT tipo, CAST(fecha_emision AS date) AS d, count(*) AS n FROM (" + sql
                + ") x GROUP BY tipo, CAST(fecha_emision AS date)", args.toArray())) {
            Map<String, Object> row = days.get(AdminFormat.day(r.get("d")));
            if (row == null) {
                continue;
            }
            String key = switch (String.valueOf(r.get("tipo"))) {
                case "01" -> "facturas";
                case "03" -> "boletas";
                default -> "notas";
            };
            row.put(key, (Long) row.get(key) + AdminFormat.longOrNull(r.get("n")));
        }
        return new ArrayList<>(days.values());
    }

    /** Documentos del mes por tipo (todos los electrónicos individuales, también guías, retenciones y percepciones). */
    private List<Map<String, Object>> byType(LocalDateTime from, LocalDateTime to) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT x.tipo, count(*) AS n FROM (" + AdminAllDocuments.UNION + ") x "
                + "JOIN tenants t ON t.id = x.tenant_id AND t.deleted_at IS NULL WHERE x.fecha_emision >= ? AND x.fecha_emision < ? "
                + "GROUP BY x.tipo", from, to)) {
            counts.put(String.valueOf(r.get("tipo")), AdminFormat.longOrNull(r.get("n")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (AdminAllDocuments.Tipo t : AdminAllDocuments.TIPOS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tipo", t.codigo());
            m.put("nombre", t.nombre());
            m.put("total", counts.getOrDefault(t.codigo(), 0L));
            out.add(m);
        }
        return out;
    }

    /** Empresas (no eliminadas) agrupadas por una columna de {@code tenants} (lista blanca). */
    private List<Map<String, Object>> tenantsBy(String column) {
        if (!List.of("plan", "tax_regime", "environment").contains(column)) {
            throw new IllegalArgumentException(column);
        }
        return jdbc.queryForList("SELECT " + column + " AS k, count(*) AS n FROM tenants WHERE deleted_at IS NULL GROUP BY " + column
                + " ORDER BY count(*) DESC, " + column).stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", r.get("k"));
                    m.put("label", label(column, r.get("k") == null ? null : String.valueOf(r.get("k"))));
                    m.put("total", AdminFormat.longOrNull(r.get("n")));
                    return m;
                }).toList();
    }

    static String label(String column, String value) {
        if (value == null) {
            return "Sin definir";
        }
        return switch (column) {
            case "tax_regime" -> switch (value) {
                case "rus" -> "Nuevo RUS";
                case "rer" -> "Régimen Especial (RER)";
                case "mype" -> "Régimen MYPE Tributario";
                case "general" -> "Régimen General";
                default -> value;
            };
            case "environment" -> "production".equals(value) ? "Producción" : "beta".equals(value) ? "Beta" : value;
            default -> value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
        };
    }

    /** Facturas, boletas y notas del mes por estado SUNAT (siempre los seis estados). */
    private Map<String, Object> statusThisMonth(LocalDateTime from, LocalDateTime to) {
        List<Object> args = new ArrayList<>();
        String sql = AdminDocuments.union("sunat_status", "fecha_emision >= ? AND fecha_emision < ?", List.of(from, to), args);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT sunat_status, count(*) AS n FROM (" + sql + ") x GROUP BY sunat_status",
                args.toArray())) {
            counts.put(String.valueOf(r.get("sunat_status")), AdminFormat.longOrNull(r.get("n")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String[] s : AdminDocuments.STATUSES) {
            out.put(s[1], counts.getOrDefault(s[0], 0L));
        }
        return out;
    }

    /** Las 5 empresas con más ventas aceptadas en soles del mes (facturas y boletas). */
    private List<Map<String, Object>> topTenants(LocalDateTime from, LocalDateTime to) {
        String part = "SELECT tenant_id, sum(mto_imp_venta) AS mto, count(*) AS cnt FROM %s WHERE deleted_at IS NULL AND sunat_status = 'aceptado' "
                + "AND tipo_moneda = 'PEN' AND fecha_emision >= ? AND fecha_emision < ? GROUP BY tenant_id";
        return jdbc.queryForList("SELECT t.id, t.ruc, t.razon_social, sum(d.mto) AS ventas, sum(d.cnt) AS docs FROM ("
                + String.format(part, "invoices") + " UNION ALL " + String.format(part, "boletas") + ") d "
                + "JOIN tenants t ON t.id = d.tenant_id AND t.deleted_at IS NULL GROUP BY t.id, t.ruc, t.razon_social "
                + "ORDER BY sum(d.mto) DESC, t.id LIMIT " + TOP, from, to, from, to).stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tenantId", AdminFormat.longOrNull(r.get("id")));
                    m.put("ruc", r.get("ruc"));
                    m.put("razonSocial", r.get("razon_social"));
                    m.put("sales", r.get("ventas") instanceof BigDecimal b ? b.setScale(2, RoundingMode.HALF_UP).doubleValue() : 0d);
                    m.put("documents", AdminFormat.longOrNull(r.get("docs")));
                    return m;
                }).toList();
    }
}
