package pe.facturacion.api.modules.serie;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Corrección: el mayor correlativo ya emitido en una serie. El correlativo de la serie nunca
 * puede quedar por debajo: el siguiente comprobante repetiría un número ya declarado a SUNAT.
 */
@Component
public class CorrelativoEmitido {

    /** Código de tipo (catálogo 01) → tabla de comprobantes. */
    static final Map<String, String> TABLAS = Map.of(
            "01", "invoices",
            "03", "boletas",
            "07", "credit_notes",
            "08", "debit_notes",
            "09", "dispatch_guides",
            "31", "dispatch_guides",
            "20", "retentions",
            "40", "perceptions",
            "04", "purchase_settlements");

    private final JdbcTemplate jdbc;

    public CorrelativoEmitido(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Incluye borrados y rechazados: el número pudo llegar a SUNAT. */
    public int maximo(long tenantId, String tipoDocumento, String serie) {
        String tabla = TABLAS.get(tipoDocumento);
        if (tabla == null) {
            return 0;
        }
        String extra = tabla.equals("dispatch_guides") ? " AND tipo_documento = ?" : "";
        Object[] args = extra.isEmpty() ? new Object[] {tenantId, serie} : new Object[] {tenantId, serie, tipoDocumento};
        Integer max = jdbc.queryForObject("SELECT max(correlativo) FROM " + tabla + " WHERE tenant_id = ? AND serie = ?" + extra,
                Integer.class, args);
        return max == null ? 0 : max;
    }

    /**
     * Piso del correlativo (sincronización desde el ERP): el contador nunca baja; queda en el mayor entre el actual, el piso pedido y
     * el último emitido. Es idempotente: repetir la misma petición no cambia nada.
     */
    public static int piso(int actual, int minimo, int emitido) {
        return Math.max(Math.max(actual, minimo), emitido);
    }

    /** Mensaje de error si {@code nuevo} baja del último emitido; {@code null} si es válido. */
    public String validar(long tenantId, String tipoDocumento, String serie, int nuevo) {
        int max = maximo(tenantId, tipoDocumento, serie);
        if (nuevo < max) {
            return "El correlativo de la serie " + serie + " no puede ser menor que el último emitido (" + max
                    + "): el siguiente comprobante repetiría un número ya usado.";
        }
        return null;
    }
}
