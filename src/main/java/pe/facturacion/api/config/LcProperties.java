package pe.facturacion.api.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parámetros de la liquidación de compra (tipo 04). Las tasas y excepciones viven en
 * configuración porque cambian por norma:
 *
 * <ul>
 *   <li>{@code retencionTasa}: retención del Impuesto a la Renta, 1.5 % (RS 234-2005/SUNAT, art. 5).</li>
 *   <li>{@code retencionTasaOro}: 4 % para la compra de oro (tipo de operación 0503).</li>
 *   <li>{@code retencionMinimo}: sin retención si el importe de la operación (sin tributos) es
 *       igual o inferior a S/ 700 (RS 234-2005/SUNAT, arts. 1 d) y 6).</li>
 *   <li>{@code agroExentoHasta}: productos primarios agropecuarios excluidos de la retención
 *       hasta esa fecha (RS 234-2005/SUNAT art. 2; prórroga hasta el 31/12/2026).</li>
 *   <li>{@code uit}: valor de la UIT por año (2026: S/ 5 500, DS 301-2025-EF);
 *       {@code limiteUit}: ventas anuales del vendedor (75 UIT, regla SUNAT 2451).</li>
 *   <li>{@code reversionDias}: reversión hasta el 5.º día desde la emisión y resumen de
 *       reversiones hasta 5 días desde el CDR aceptado (RS 000047-2026/SUNAT).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.lc")
public record LcProperties(
        BigDecimal igvTasa,
        BigDecimal retencionTasa,
        BigDecimal retencionTasaOro,
        BigDecimal retencionMinimo,
        LocalDate agroExentoHasta,
        Map<Integer, BigDecimal> uit,
        int limiteUit,
        int reversionDias) {

    /** UIT del año; si no está configurada, la del último año configurado anterior. */
    public BigDecimal uitDe(int anio) {
        if (uit == null || uit.isEmpty()) {
            return null;
        }
        return uit.entrySet().stream().filter(e -> e.getKey() <= anio).max(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue).orElse(null);
    }
}
