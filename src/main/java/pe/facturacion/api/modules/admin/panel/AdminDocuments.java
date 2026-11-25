package pe.facturacion.api.modules.admin.panel;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Las cuatro tablas de comprobantes que cuenta el panel (facturas, boletas, notas de crédito y de débito), con sus
 * columnas comunes {@code tenant_id, serie, correlativo, fecha_emision} (hora de Lima), {@code sunat_status},
 * {@code sunat_code}, {@code sunat_description}, {@code sunat_notes} y {@code deleted_at}.
 */
final class AdminDocuments {

    record Table(String name, String tipo, String tipoNombre) {
    }

    static final List<Table> TABLES = List.of(
            new Table("invoices", "01", "Factura"),
            new Table("boletas", "03", "Boleta"),
            new Table("credit_notes", "07", "Nota de crédito"),
            new Table("debit_notes", "08", "Nota de débito"));

    /** Estados SUNAT de la columna {@code sunat_status} con su clave camelCase del contrato. */
    static final List<String[]> STATUSES = List.of(
            new String[] {"pendiente", "pendiente"},
            new String[] {"enviado", "enviado"},
            new String[] {"aceptado", "aceptado"},
            new String[] {"rechazado", "rechazado"},
            new String[] {"anulado", "anulado"},
            new String[] {"anulacion_en_proceso", "anulacionEnProceso"});

    /** Observado: aceptado con observaciones del CDR ({@code sunat_notes} con al menos un elemento). */
    static final String OBSERVED = "(CASE WHEN jsonb_typeof(sunat_notes) = 'array' THEN jsonb_array_length(sunat_notes) > 0 ELSE false END)";

    private AdminDocuments() {
    }

    /**
     * {@code SELECT {columns} FROM t WHERE deleted_at IS NULL [AND where]} unido con {@code UNION ALL} para las cuatro
     * tablas; {@code {tipo}} y {@code {tipoNombre}} se reemplazan por los de cada tabla. Los parámetros de {@code where}
     * se repiten por tabla en {@code args}.
     */
    static String union(String columns, String where, List<Object> whereArgs, List<Object> args) {
        List<String> parts = new ArrayList<>();
        for (Table t : TABLES) {
            String cols = columns.replace("{tipo}", "'" + t.tipo() + "'").replace("{tipoNombre}", "'" + t.tipoNombre() + "'");
            parts.add("SELECT " + cols + " FROM " + t.name() + " WHERE deleted_at IS NULL" + (where == null ? "" : " AND " + where));
            args.addAll(whereArgs);
        }
        return String.join(" UNION ALL ", parts);
    }

    /** [primer día 00:00, primer día del mes siguiente 00:00) en hora local de Lima (como se guarda fecha_emision). */
    static LocalDateTime[] monthRange(YearMonth month) {
        return new LocalDateTime[] {month.atDay(1).atStartOfDay(), month.plusMonths(1).atDay(1).atStartOfDay()};
    }

    static LocalDateTime[] dayRange(LocalDate start, LocalDate end) {
        return new LocalDateTime[] {start.atStartOfDay(), end.plusDays(1).atStartOfDay()};
    }
}
