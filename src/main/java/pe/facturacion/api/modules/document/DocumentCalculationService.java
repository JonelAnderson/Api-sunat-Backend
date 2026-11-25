package pe.facturacion.api.modules.document;

import static pe.facturacion.api.shared.php.Php.coalesce;
import static pe.facturacion.api.shared.php.Php.empty;
import static pe.facturacion.api.shared.php.Php.inArray;
import static pe.facturacion.api.shared.php.Php.isset;
import static pe.facturacion.api.shared.php.Php.strictEq;
import static pe.facturacion.api.shared.php.Php.toFloat;
import static pe.facturacion.api.shared.php.PhpMath.round;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import pe.facturacion.api.modules.tax.TaxRateService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.php.Php;

/**
 * Port de {@code App\Services\DocumentCalculationService}: cálculo de ítems, totales y leyenda
 * con la misma aritmética de {@code float} y {@code round()} de PHP (ver {@code PhpMath}).
 */
@Service
public class DocumentCalculationService {

    static final List<String> GRATUITO_GRAVADO = List.of("11", "12", "13", "14", "15", "16");
    static final List<String> GRATUITO_INAFECTO = List.of("21", "31", "32", "33", "34", "35", "36");
    static final List<String> GRATUITO = List.of("11", "12", "13", "14", "15", "16", "21", "31", "32", "33", "34", "35", "36");

    private final TaxRateService taxRates;

    public DocumentCalculationService(TaxRateService taxRates) {
        this.taxRates = taxRates;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> calculateItems(List<Map<String, Object>> items, Tenant tenant) {
        List<Map<String, Object>> calculated = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Object tipAfeIgv = item.get("tip_afe_igv") != null ? item.get("tip_afe_igv") : taxRates.defaultTipAfeIgv(tenant);
            double porcentajeIgv = taxRates.rateForItem(item, tenant);
            double cantidad = toFloat(item.get("cantidad"));
            double precioUnitario = toFloat(item.get("precio_unitario"));
            double porcentajeIsc = toFloat(coalesce(item, "porcentaje_isc", 0L));
            boolean isGratuito = inArray(tipAfeIgv, GRATUITO);
            boolean isGratuitoGravado = inArray(tipAfeIgv, GRATUITO_GRAVADO);
            boolean es10 = strictEq(tipAfeIgv, "10");
            boolean es17 = strictEq(tipAfeIgv, "17");
            boolean es20 = strictEq(tipAfeIgv, "20");
            boolean es30o40 = strictEq(tipAfeIgv, "30") || strictEq(tipAfeIgv, "40");

            // Valor unitario (SUNAT admite hasta 10 decimales)
            Object valorUnitario;
            if (es10) {
                if (porcentajeIsc > 0 && !isset(item, "mto_valor_unitario")) {
                    valorUnitario = round(precioUnitario / ((1 + porcentajeIsc / 100) * (1 + porcentajeIgv / 100)), 10);
                } else {
                    valorUnitario = round(precioUnitario / (1 + porcentajeIgv / 100), 10);
                }
            } else if (es17) {
                valorUnitario = round(precioUnitario / (1 + porcentajeIgv / 100), 10);
            } else if (inArray(tipAfeIgv, List.of("20", "30", "40"))) {
                valorUnitario = precioUnitario;
            } else if (isGratuitoGravado || isGratuito) {
                valorUnitario = 0L;
            } else {
                valorUnitario = toFloat(coalesce(item, "mto_valor_unitario", precioUnitario));
            }

            // Descuentos por línea sobre la base sin IGV
            double descuentoBase = 0;
            double descuentoConIgv = 0;
            List<Object> recalculated = null;
            if (!empty(item.get("descuentos"))) {
                double valorBruto = (isGratuito ? toFloat(coalesce(item, "mto_valor_unitario", precioUnitario)) : toFloat(valorUnitario)) * cantidad;
                double totalConIgvBruto = round(precioUnitario * cantidad, 2);
                recalculated = new ArrayList<>();
                for (Object o : (List<Object>) item.get("descuentos")) {
                    Map<String, Object> desc = (Map<String, Object>) o;
                    Object codTipo = coalesce(desc, "cod_tipo", "00");
                    if (strictEq(codTipo, "00") || strictEq(codTipo, "01")) {
                        double montoBase = round(valorBruto, 2);
                        Object factor;
                        double monto;
                        if (!empty(desc.get("porcentaje"))) {
                            factor = round(toFloat(desc.get("porcentaje")) / 100, 6);
                            monto = round(montoBase * toFloat(factor), 2);
                        } else if (!empty(desc.get("factor"))) {
                            factor = toFloat(desc.get("factor"));
                            monto = round(montoBase * toFloat(factor), 2);
                        } else {
                            monto = toFloat(coalesce(desc, "monto", 0L));
                            factor = montoBase > 0 ? (Object) round(monto / montoBase, 6) : (Object) 0L;
                        }
                        descuentoBase += monto;
                        descuentoConIgv += round(totalConIgvBruto * toFloat(factor), 2);
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("cod_tipo", codTipo);
                        r.put("monto_base", montoBase);
                        r.put("factor", factor);
                        r.put("monto", monto);
                        recalculated.add(r);
                    } else {
                        recalculated.add(desc);
                    }
                }
            }

            double valorVenta;
            Object igv;
            Object porcentaje = porcentajeIgv;
            if (es10 || es17) {
                valorVenta = round(toFloat(valorUnitario) * cantidad - descuentoBase, 2);
                double totalConIgv = round(precioUnitario * cantidad, 2) - descuentoConIgv;
                igv = round(totalConIgv - valorVenta, 2);
            } else if (es20 || es30o40) {
                valorVenta = round(toFloat(valorUnitario) * cantidad - descuentoBase, 2);
                igv = 0L;
                porcentaje = 0L;
            } else if (isGratuitoGravado) {
                double valorGratuito = toFloat(coalesce(item, "mto_valor_unitario", precioUnitario));
                valorUnitario = 0L;
                valorVenta = round(valorGratuito * cantidad, 2);
                igv = item.get("igv") != null ? toFloat(item.get("igv")) : round(valorVenta * porcentajeIgv / 100, 2);
            } else if (isGratuito) {
                double valorGratuito = toFloat(coalesce(item, "mto_valor_unitario", precioUnitario));
                valorUnitario = 0L;
                valorVenta = round(valorGratuito * cantidad, 2);
                igv = 0L;
                porcentaje = 0L;
            } else {
                valorUnitario = toFloat(coalesce(item, "mto_valor_unitario", precioUnitario));
                valorVenta = round(toFloat(valorUnitario) * cantidad, 2);
                igv = toFloat(coalesce(item, "igv", 0L));
            }

            if (!isGratuito) {
                valorUnitario = item.get("mto_valor_unitario") != null ? toFloat(item.get("mto_valor_unitario")) : toFloat(valorUnitario);
            }
            valorVenta = item.get("mto_valor_venta") != null ? toFloat(item.get("mto_valor_venta")) : valorVenta;
            double isc = toFloat(coalesce(item, "isc", 0L));
            double icbper = toFloat(coalesce(item, "icbper", 0L));
            double factorIcbper = toFloat(coalesce(item, "factor_icbper", 0L));

            if (isc <= 0 && porcentajeIsc > 0) {
                isc = round(valorVenta * porcentajeIsc / 100, 2);
            }
            if (icbper <= 0 && factorIcbper > 0) {
                icbper = round(factorIcbper * cantidad, 2);
            }

            Object baseIgv;
            if (isc > 0 && !isset(item, "mto_base_igv") && !isset(item, "igv")) {
                baseIgv = round(valorVenta + isc, 2);
                igv = round(toFloat(baseIgv) * toFloat(porcentaje) / 100, 2);
            } else {
                igv = item.get("igv") != null ? toFloat(item.get("igv")) : toFloat(igv);
                baseIgv = item.get("mto_base_igv") != null ? toFloat(item.get("mto_base_igv")) : valorVenta;
            }
            if (inArray(tipAfeIgv, GRATUITO_INAFECTO)) {
                baseIgv = valorVenta;
            }

            double calculado = toFloat(igv) + isc + icbper;
            // Corrección: API-PRO usaba el total_impuestos del cliente aunque no cuadrara con sus
            // propios tributos (el XML salía con TaxTotal inconsistente)
            if (item.get("total_impuestos") != null && Math.abs(toFloat(item.get("total_impuestos")) - calculado) > 0.01) {
                String esperado = String.format(java.util.Locale.ROOT, "%.2f", round(calculado, 2));
                throw pe.facturacion.api.shared.api.ApiException.of(422, "Error de validación",
                        Map.of("items." + calculated.size() + ".total_impuestos",
                                List.of("Debe ser " + esperado + " (IGV/IVAP + ISC + ICBPER del ítem).")));
            }
            double totalImpuestos = item.get("total_impuestos") != null
                    ? toFloat(item.get("total_impuestos"))
                    : calculado;

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("codigo", item.get("codigo"));
            c.put("cod_producto_sunat", item.get("cod_producto_sunat"));
            c.put("descripcion", item.get("descripcion"));
            c.put("unidad", item.get("unidad"));
            c.put("cantidad", cantidad);
            c.put("mto_valor_unitario", valorUnitario);
            c.put("mto_valor_venta", valorVenta);
            c.put("mto_base_igv", baseIgv);
            c.put("porcentaje_igv", porcentaje);
            c.put("igv", igv);
            c.put("tip_afe_igv", tipAfeIgv);
            c.put("isc", isc);
            c.put("icbper", icbper);
            c.put("total_impuestos", totalImpuestos);
            c.put("mto_precio_unitario", precioUnitario);
            c.put("descuento", item.get("descuento") != null ? toFloat(item.get("descuento")) : descuentoBase);
            c.put("descuentos", recalculated != null ? recalculated : item.get("descuentos"));
            // Corrección (DIFERENCIAS): API-PRO no guardaba estos tres y el XML los recalculaba
            c.put("tip_sis_isc", item.get("tip_sis_isc"));
            c.put("porcentaje_isc", item.get("porcentaje_isc") != null ? porcentajeIsc : null);
            c.put("factor_icbper", item.get("factor_icbper") != null ? factorIcbper : null);
            calculated.add(c);
        }
        return calculated;
    }

    /** {@code calculateTotals($items, &$data, ...)}: modifica {@code data} igual que el original. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> calculateTotals(List<Map<String, Object>> items, Map<String, Object> data, Tenant tenant) {
        double gravadas = 0;
        double exoneradas = 0;
        double inafectas = 0;
        double exportacion = 0;
        double gratuitas = 0;
        double baseIvap = 0;
        double totalIvap = 0;
        double totalIgv = 0;
        double igvGratuitas = 0;
        double totalIsc = 0;
        double totalIcbper = 0;
        double sumDescuentosNoBase = 0;

        for (Map<String, Object> item : items) {
            Object tip = item.get("tip_afe_igv");
            double valorVenta = toFloat(item.get("mto_valor_venta"));
            if (strictEq(tip, "10")) {
                gravadas += valorVenta;
                totalIgv += toFloat(item.get("igv"));
            } else if (strictEq(tip, "17")) {
                baseIvap += valorVenta;
                totalIvap += toFloat(item.get("igv"));
            } else if (strictEq(tip, "20")) {
                exoneradas += valorVenta;
            } else if (strictEq(tip, "30")) {
                inafectas += valorVenta;
            } else if (strictEq(tip, "40")) {
                exportacion += valorVenta;
            } else if (inArray(tip, GRATUITO)) {
                gratuitas += valorVenta;
                igvGratuitas += toFloat(item.get("igv"));
            }
            totalIsc += toFloat(item.get("isc"));
            totalIcbper += toFloat(item.get("icbper"));
        }

        // El anticipo se declara vía PrepaidPayment; no como AllowanceCharge 04
        if (!empty(data.get("anticipos")) && !isset(data, "total_anticipos")) {
            data.put("total_anticipos", collectionSum(data.get("anticipos"), "monto"));
        }

        double descuentoGlobalGravadas = 0;
        if (!empty(data.get("descuentos_globales"))) {
            List<Object> normalized = new ArrayList<>();
            for (Object o : (List<Object>) data.get("descuentos_globales")) {
                Map<String, Object> desc = (Map<String, Object>) o;
                Object codTipo = coalesce(desc, "cod_tipo", "02");
                boolean afectaGravadas = inArray(codTipo, List.of("02", "04", "05", "06"));
                double monto;
                if (!empty(desc.get("porcentaje")) && empty(desc.get("monto"))) {
                    double baseParaDesc = afectaGravadas ? gravadas : (exoneradas + inafectas + exportacion + gravadas);
                    monto = round(baseParaDesc * (toFloat(desc.get("porcentaje")) / 100), 2);
                } else {
                    monto = toFloat(coalesce(desc, "monto", 0L));
                }
                double montoBase = round(afectaGravadas ? gravadas : (gravadas + exoneradas + inafectas), 2);
                Object factor = montoBase > 0 ? (Object) round(monto / montoBase, 6) : (Object) 0L;
                Map<String, Object> n = new LinkedHashMap<>(desc);
                n.put("monto", monto);
                n.put("monto_base", toFloat(coalesce(desc, "monto_base", montoBase)));
                n.put("factor", toFloat(coalesce(desc, "factor", factor)));
                normalized.add(n);
                if (strictEq(codTipo, "02")) {
                    descuentoGlobalGravadas += monto;
                } else if (strictEq(codTipo, "03")) {
                    sumDescuentosNoBase += monto;
                }
            }
            data.put("descuentos_globales", normalized);
        }

        if (descuentoGlobalGravadas > 0) {
            double defaultFrac = taxRates.defaultIgvRate(tenant) / 100;
            double igvRate = gravadas > 0 ? (totalIgv / gravadas) : defaultFrac;
            gravadas -= descuentoGlobalGravadas;
            totalIgv = round(gravadas * igvRate, 2);
        }

        gravadas = Math.max(0, round(gravadas, 2));
        exoneradas = Math.max(0, round(exoneradas, 2));
        inafectas = Math.max(0, round(inafectas, 2));
        exportacion = Math.max(0, round(exportacion, 2));
        baseIvap = Math.max(0, round(baseIvap, 2));
        totalIvap = Math.max(0, round(totalIvap, 2));
        gratuitas = Math.max(0, round(gratuitas, 2));
        totalIgv = Math.max(0, round(totalIgv, 2));
        igvGratuitas = round(igvGratuitas, 2);
        totalIsc = Math.max(0, round(totalIsc, 2));
        totalIcbper = Math.max(0, round(totalIcbper, 2));
        double totalImpuestos = round(totalIgv + totalIvap + totalIsc + totalIcbper, 2);
        double valorVenta = round(gravadas + exoneradas + inafectas + exportacion + baseIvap, 2);
        sumDescuentosNoBase = round(sumDescuentosNoBase, 2);
        double subTotal = round(valorVenta + totalImpuestos, 2);
        double totalAnticipos = toFloat(data.get("total_anticipos") != null
                ? data.get("total_anticipos")
                : collectionSum(coalesce(data, "anticipos", List.of()), "monto"));
        double mtoImpVenta = Math.max(0, round(subTotal - totalAnticipos - sumDescuentosNoBase, 2));

        Map<String, Object> t = new LinkedHashMap<>();
        t.put("mto_oper_gravadas", pick(data, "mto_oper_gravadas", gravadas));
        t.put("mto_oper_exoneradas", pick(data, "mto_oper_exoneradas", exoneradas));
        t.put("mto_oper_inafectas", pick(data, "mto_oper_inafectas", inafectas));
        t.put("mto_oper_exportacion", pick(data, "mto_oper_exportacion", exportacion));
        t.put("mto_oper_gratuitas", pick(data, "mto_oper_gratuitas", gratuitas));
        t.put("mto_igv", pick(data, "mto_igv", totalIgv));
        t.put("mto_base_ivap", pick(data, "mto_base_ivap", baseIvap));
        t.put("mto_ivap", pick(data, "mto_ivap", totalIvap));
        t.put("mto_igv_gratuitas", pick(data, "mto_igv_gratuitas", igvGratuitas));
        t.put("mto_isc", pick(data, "mto_isc", totalIsc));
        t.put("mto_icbper", pick(data, "mto_icbper", totalIcbper));
        t.put("total_impuestos", pick(data, "total_impuestos", totalImpuestos));
        t.put("valor_venta", pick(data, "valor_venta", valorVenta));
        t.put("sub_total", pick(data, "sub_total", subTotal));
        t.put("mto_imp_venta", pick(data, "mto_imp_venta", mtoImpVenta));
        t.put("sum_otros_descuentos", pick(data, "sum_otros_descuentos", sumDescuentosNoBase));
        t.put("total_descuentos", pick(data, "total_descuentos", descuentoGlobalGravadas + sumDescuentosNoBase));
        return t;
    }

    private static double pick(Map<String, Object> data, String key, double computed) {
        Object v = data.get(key);
        return v != null ? toFloat(v) : computed;
    }

    /** {@code collect($rows)->sum($key)}: entero mientras todos los sumandos lo sean. */
    @SuppressWarnings("unchecked")
    static Object collectionSum(Object rows, String key) {
        long li = 0;
        double d = 0;
        boolean allInt = true;
        if (rows instanceof List<?> list) {
            for (Object row : list) {
                Object v = row instanceof Map<?, ?> m ? ((Map<String, Object>) m).get(key) : null;
                if (v == null) {
                    continue;
                }
                Object n = v instanceof String s ? numericString(s) : v;
                if (allInt && Php.isInt(n)) {
                    li += ((Number) n).longValue();
                } else {
                    if (allInt) {
                        d = li;
                        allInt = false;
                    }
                    d += toFloat(n);
                }
            }
        }
        return allInt ? (Object) li : (Object) d;
    }

    /** Conversión numérica de PHP de una cadena: {@code "10"} → int, {@code "10.5"} → float. */
    private static Object numericString(String s) {
        String t = s.trim();
        if (t.matches("[+-]?\\d+")) {
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                return toFloat(t);
            }
        }
        return toFloat(t);
    }

    // ----------------------------------------------------------------- leyenda

    private static final String[] UNIDADES = {"", "UNO", "DOS", "TRES", "CUATRO", "CINCO", "SEIS", "SIETE", "OCHO", "NUEVE"};
    private static final String[] ESPECIALES = {"DIEZ", "ONCE", "DOCE", "TRECE", "CATORCE", "QUINCE"};
    private static final String[] DECENAS = {"", "DIEZ", "VEINTE", "TREINTA", "CUARENTA", "CINCUENTA", "SESENTA", "SETENTA", "OCHENTA", "NOVENTA"};
    private static final String[] CENTENAS = {"", "CIENTO", "DOSCIENTOS", "TRESCIENTOS", "CUATROCIENTOS", "QUINIENTOS",
        "SEISCIENTOS", "SETECIENTOS", "OCHOCIENTOS", "NOVECIENTOS"};

    /**
     * {@code generateLeyenda()}. Correcciones (DIFERENCIAS): EUR → "EUROS" (API-PRO decía
     * "DOLARES AMERICANOS") y un redondeo a 100 céntimos pasa al entero ("100/100" no existe).
     */
    public String generateLeyenda(double total, String moneda) {
        long entero = (long) total;
        double decimales = round((total - entero) * 100);
        if (decimales >= 100) {
            entero += 1;
            decimales = 0;
        }
        String monedaTexto = switch (moneda == null ? "" : moneda) {
            case "PEN" -> "SOLES";
            case "EUR" -> "EUROS";
            default -> "DOLARES AMERICANOS";
        };
        String dec = Php.toStr(decimales);
        if (dec.length() < 2) {
            dec = "0".repeat(2 - dec.length()) + dec;
        }
        return numberToWords(entero).toUpperCase(java.util.Locale.ROOT) + " CON " + dec + "/100 " + monedaTexto;
    }

    static String numberToWords(long number) {
        if (number == 0) {
            return "CERO";
        }
        if (number == 100) {
            return "CIEN";
        }
        StringBuilder r = new StringBuilder();
        if (number >= 1_000_000) {
            long millones = number / 1_000_000;
            r.append(millones == 1 ? "UN MILLON" : numberToWords(millones) + " MILLONES");
            number %= 1_000_000;
            if (number > 0) {
                r.append(' ');
            }
        }
        if (number >= 1000) {
            long miles = number / 1000;
            r.append(miles == 1 ? "MIL" : numberToWords(miles) + " MIL");
            number %= 1000;
            if (number > 0) {
                r.append(' ');
            }
        }
        if (number >= 100) {
            if (number == 100) {
                return r + "CIEN";
            }
            r.append(CENTENAS[(int) (number / 100)]).append(' ');
            number %= 100;
        }
        if (number >= 10 && number <= 15) {
            r.append(ESPECIALES[(int) number - 10]);
        } else if (number >= 16 && number <= 19) {
            r.append("DIECI").append(UNIDADES[(int) number - 10]);
        } else if (number >= 21 && number <= 29) {
            r.append("VEINTI").append(UNIDADES[(int) number - 20]);
        } else if (number >= 10) {
            r.append(DECENAS[(int) (number / 10)]);
            number %= 10;
            if (number > 0) {
                r.append(" Y ").append(UNIDADES[(int) number]);
            }
        } else if (number > 0) {
            r.append(UNIDADES[(int) number]);
        }
        return r.toString().trim();
    }
}
