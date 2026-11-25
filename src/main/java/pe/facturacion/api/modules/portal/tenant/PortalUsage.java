package pe.facturacion.api.modules.portal.tenant;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Uso mensual de una empresa para {@code GET tenants/{id}/uso}: mes actual y los 5 anteriores, contados con la misma
 * definición del cupo ({@code EmissionPolicy}): comprobantes SUNAT y guías creados en el mes (UTC), incluidos los
 * eliminados. Así el mes actual coincide con lo que el cupo cuenta. Una sola consulta por empresa.
 */
@Component
public class PortalUsage {

    /** Meses que devuelve el historial: el actual y los 5 anteriores. */
    public static final int MONTHS = 6;

    private static final List<String> TABLES = List.of("invoices", "boletas", "credit_notes", "debit_notes", "dispatch_guides");

    private final JdbcTemplate jdbc;

    public PortalUsage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code [{ month: "yyyy-MM", documents }]} del más antiguo al mes {@code current}, con 0 en los meses sin comprobantes. */
    public List<Map<String, Object>> history(long tenantId, YearMonth current) {
        YearMonth first = current.minusMonths(MONTHS - 1L);
        LocalDateTime from = first.atDay(1).atStartOfDay();
        LocalDateTime to = current.plusMonths(1).atDay(1).atStartOfDay();
        String one = "SELECT created_at FROM %s WHERE tenant_id = ? AND created_at >= ? AND created_at < ?";
        String sql = "SELECT to_char(created_at, 'YYYY-MM') AS m, count(*) AS n FROM ("
                + String.join(" UNION ALL ", TABLES.stream().map(t -> String.format(one, t)).toList()) + ") d GROUP BY 1";
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < TABLES.size(); i++) {
            args.add(tenantId);
            args.add(from);
            args.add(to);
        }
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, args.toArray())) {
            counts.put(String.valueOf(r.get("m")), ((Number) r.get("n")).longValue());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < MONTHS; i++) {
            String month = first.plusMonths(i).toString();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", month);
            m.put("documents", counts.getOrDefault(month, 0L));
            out.add(m);
        }
        return out;
    }
}
