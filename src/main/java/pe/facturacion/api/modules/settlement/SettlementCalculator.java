package pe.facturacion.api.modules.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import pe.facturacion.api.config.LcProperties;
import pe.facturacion.api.shared.php.Php;

/**
 * Montos de la liquidación de compra según las reglas de validación SUNAT (hoja
 * {@code LiquidacionCompra2_0}, todas con tolerancia ±1):
 *
 * <pre>
 * Por ítem:   valor de venta = cantidad × valor unitario                         (4288)
 *             IGV   = valor de venta × tasa IGV           (solo gravado, 10)      (3103)
 *             renta = valor de venta × tasa de retención                          (2464)
 *             precio unitario = (valor de venta + IGV) / cantidad                 (4287)
 * Globales:   IGV = Σ bases gravadas × tasa IGV                                   (4290)
 *             retención = Σ retención de los ítems                                (4346)
 *             subtotal (TaxInclusive) = valor de venta + IGV                      (4310)
 *             neto (Payable) = subtotal − IGV − retención = valor de venta − retención (4312)
 * </pre>
 *
 * <p>La tasa de retención (RS 234-2005/SUNAT y configuración {@code app.lc}): oro (0503) 4 %;
 * productos primarios agropecuarios excluidos hasta {@code agro-exento-hasta}; sin retención si
 * el importe de la operación sin tributos es igual o inferior a S/ 700; en otro caso 1.5 %.
 */
@Component
public class SettlementCalculator {

    private final LcProperties props;

    public SettlementCalculator(LcProperties props) {
        this.props = props;
    }

    /** Resultado: ítems calculados y totales de cabecera. */
    public record Result(List<Map<String, Object>> items, Map<String, Object> totals) {
    }

    /** Tasa de retención de renta aplicable y su motivo. */
    public record Tasa(BigDecimal porcentaje, String motivo) {
    }

    public Tasa tasaRenta(String tipoOperacion, boolean agropecuarios, LocalDate fechaEmision, BigDecimal importeOperacion) {
        if (agropecuarios && props.agroExentoHasta() != null && !fechaEmision.isAfter(props.agroExentoHasta())) {
            return new Tasa(BigDecimal.ZERO, "productos primarios agropecuarios excluidos de la retención hasta " + props.agroExentoHasta());
        }
        if (importeOperacion.compareTo(props.retencionMinimo()) <= 0) {
            return new Tasa(BigDecimal.ZERO, "importe de la operación no supera S/ " + props.retencionMinimo().toPlainString());
        }
        if ("0503".equals(tipoOperacion)) {
            return new Tasa(props.retencionTasaOro(), "compra de oro");
        }
        return new Tasa(props.retencionTasa(), "tasa general");
    }

    @SuppressWarnings("unchecked")
    public Result calculate(List<Map<String, Object>> input, String tipoOperacion, boolean agropecuarios, LocalDate fechaEmision) {
        BigDecimal tasaIgv = props.igvTasa();
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal gravadas = BigDecimal.ZERO;
        BigDecimal exoneradas = BigDecimal.ZERO;
        BigDecimal inafectas = BigDecimal.ZERO;
        for (Map<String, Object> it : input) {
            BigDecimal cantidad = dec(it.get("cantidad"));
            BigDecimal vu = dec(it.get("valor_unitario"));
            String afe = it.get("tip_afe_igv") == null ? "10" : Php.toStr(it.get("tip_afe_igv"));
            BigDecimal vv = cantidad.multiply(vu).setScale(2, RoundingMode.HALF_UP);
            switch (afe) {
                case "20" -> exoneradas = exoneradas.add(vv);
                case "30" -> inafectas = inafectas.add(vv);
                default -> gravadas = gravadas.add(vv);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("codigo", it.get("codigo") == null ? null : Php.toStr(it.get("codigo")));
            m.put("cod_producto_sunat", Php.toStr(it.get("cod_producto_sunat")));
            m.put("descripcion", Php.toStr(it.get("descripcion")));
            m.put("unidad", it.get("unidad") == null ? "KGM" : Php.toStr(it.get("unidad")));
            m.put("cantidad", cantidad);
            m.put("valor_unitario", vu);
            m.put("tip_afe_igv", afe);
            m.put("valor_venta", vv);
            m.put("oro", it.get("oro") instanceof Map<?, ?> oro ? (Map<String, Object>) oro : null);
            items.add(m);
        }
        BigDecimal valorVenta = gravadas.add(exoneradas).add(inafectas);
        Tasa tasa = tasaRenta(tipoOperacion, agropecuarios, fechaEmision, valorVenta);
        BigDecimal renta = BigDecimal.ZERO;
        for (Map<String, Object> m : items) {
            BigDecimal vv = (BigDecimal) m.get("valor_venta");
            BigDecimal pIgv = "10".equals(m.get("tip_afe_igv")) ? tasaIgv : BigDecimal.ZERO;
            BigDecimal igv = vv.multiply(pIgv).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal r = vv.multiply(tasa.porcentaje()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal cantidad = (BigDecimal) m.get("cantidad");
            m.put("precio_unitario", vv.add(igv).divide(cantidad, 10, RoundingMode.HALF_UP));
            m.put("porcentaje_igv", pIgv);
            m.put("igv", igv);
            m.put("porcentaje_renta", tasa.porcentaje());
            m.put("renta", r);
            renta = renta.add(r);
        }
        BigDecimal igv = gravadas.multiply(tasaIgv).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal subTotal = valorVenta.add(igv);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("mto_oper_gravadas", gravadas);
        t.put("mto_oper_exoneradas", exoneradas);
        t.put("mto_oper_inafectas", inafectas);
        t.put("porcentaje_igv", tasaIgv);
        t.put("mto_igv", igv);
        t.put("valor_venta", valorVenta);
        t.put("mto_base_renta", valorVenta);
        t.put("porcentaje_renta", tasa.porcentaje());
        t.put("motivo_tasa_renta", tasa.motivo());
        t.put("mto_retencion_renta", renta);
        t.put("sub_total", subTotal);
        t.put("mto_imp_neto", subTotal.subtract(igv).subtract(renta));
        return new Result(items, t);
    }

    static BigDecimal dec(Object v) {
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(Php.toStr(v).trim());
    }
}
