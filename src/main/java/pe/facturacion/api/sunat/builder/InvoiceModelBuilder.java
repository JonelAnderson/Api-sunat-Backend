package pe.facturacion.api.sunat.builder;

import static pe.facturacion.api.shared.php.Php.coalesce;
import static pe.facturacion.api.shared.php.Php.empty;
import static pe.facturacion.api.shared.php.Php.inArray;
import static pe.facturacion.api.shared.php.Php.isset;
import static pe.facturacion.api.shared.php.Php.strictEq;
import static pe.facturacion.api.shared.php.Php.toFloat;
import static pe.facturacion.api.shared.php.Php.toStr;
import static pe.facturacion.api.shared.php.Php.truthy;
import static pe.facturacion.api.shared.php.PhpMath.round;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import pe.facturacion.api.modules.tax.TaxRateService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.sunat.model.SaleModels.Address;
import pe.facturacion.api.sunat.model.SaleModels.Charge;
import pe.facturacion.api.sunat.model.SaleModels.Client;
import pe.facturacion.api.sunat.model.SaleModels.Company;
import pe.facturacion.api.sunat.model.SaleModels.Cuota;
import pe.facturacion.api.sunat.model.SaleModels.Detraction;
import pe.facturacion.api.sunat.model.SaleModels.Document;
import pe.facturacion.api.sunat.model.SaleModels.FormaPago;
import pe.facturacion.api.sunat.model.SaleModels.Invoice;
import pe.facturacion.api.sunat.model.SaleModels.Legend;
import pe.facturacion.api.sunat.model.SaleModels.Prepayment;
import pe.facturacion.api.sunat.model.SaleModels.SaleDetail;
import pe.facturacion.api.sunat.model.SaleModels.SalePerception;

/**
 * Port de {@code App\Services\Greenter\Builders\InvoiceBuilder}: convierte el arreglo del
 * documento guardado ({@code documentToArray}) en el modelo de factura/boleta, con los mismos
 * ajustes para SUNAT (anticipos, gratuitas, descuentos, ISC, ICBPER, leyendas).
 *
 * <p>Los valores del mapa tienen los tipos de PHP: decimales como cadena ({@code "2000.00"}),
 * enteros, y arreglos JSON como {@link List}/{@link Map}.
 */
public final class InvoiceModelBuilder {

    static final List<String> GRATUITO_GRAVADO = List.of("11", "12", "13", "14", "15", "16");
    static final List<String> GRATUITO_INAFECTO = List.of("21", "31", "32", "33", "34", "35", "36");
    static final List<String> GRATUITO = List.of("11", "12", "13", "14", "15", "16", "21", "31", "32", "33", "34", "35", "36");

    private final Tenant tenant;
    private final TaxRateService taxRates;

    public InvoiceModelBuilder(Tenant tenant, TaxRateService taxRates) {
        this.tenant = tenant;
        this.taxRates = taxRates;
    }

    @SuppressWarnings("unchecked")
    public Invoice build(Map<String, Object> data) {
        Invoice invoice = new Invoice();
        invoice.ublVersion = "2.1";
        invoice.tipoOperacion = toStr(coalesce(data, "tipo_operacion", "0101"));
        invoice.tipoDoc = toStr(data.get("tipo_documento"));
        invoice.serie = toStr(data.get("serie"));
        invoice.correlativo = toStr(data.get("correlativo"));
        invoice.fechaEmision = PhpDates.parseDateTime(toStr(data.get("fecha_emision")));
        invoice.tipoMoneda = toStr(coalesce(data, "tipo_moneda", "PEN"));
        invoice.company = buildCompany(toStr(coalesce(data, "cod_local", "0000")));
        invoice.client = buildClient((Map<String, Object>) data.get("cliente"));

        if (!empty(data.get("fecha_vencimiento"))) {
            invoice.fecVencimiento = PhpDates.parseDate(toStr(data.get("fecha_vencimiento")));
        }

        // Forma de pago
        if (Php.looseEqString(coalesce(data, "forma_pago", "Contado"), "Credito")) {
            Object cuotasData = coalesce(data, "cuotas", List.of());
            Object sumCuotas = Php.arraySumColumn(cuotasData, "monto");
            double totalCredito = toFloat(sumCuotas) > 0 ? toFloat(sumCuotas) : toFloat(coalesce(data, "mto_imp_venta", 0L));
            invoice.formaPago = FormaPago.credito(totalCredito);
            if (truthy(cuotasData)) {
                List<Cuota> cuotas = new ArrayList<>();
                for (Object c : (List<Object>) cuotasData) {
                    Map<String, Object> cuota = (Map<String, Object>) c;
                    Cuota q = new Cuota();
                    q.monto = cuota.get("monto") == null ? null : toFloat(cuota.get("monto"));
                    q.fechaPago = PhpDates.parseDate(toStr(cuota.get("fecha_pago")));
                    cuotas.add(q);
                }
                invoice.cuotas = cuotas;
            }
        } else {
            invoice.formaPago = FormaPago.contado();
        }

        // Montos: solo se asignan los > 0 para no generar nodos de tributo vacíos
        double mtoOperGravadas = toFloat(coalesce(data, "mto_oper_gravadas", 0L));
        double mtoOperExoneradas = toFloat(coalesce(data, "mto_oper_exoneradas", 0L));
        double mtoOperInafectas = toFloat(coalesce(data, "mto_oper_inafectas", 0L));
        double mtoOperGratuitas = toFloat(coalesce(data, "mto_oper_gratuitas", 0L));
        double mtoOperExportacion = toFloat(coalesce(data, "mto_oper_exportacion", 0L));
        double mtoIgv = toFloat(coalesce(data, "mto_igv", 0L));

        // Anticipos (SUNAT 3277/3287/3291/3294/4296/4297)
        double anticipoBase = 0.0;
        String anticipoCodDesc = "04";
        double totalAnticiposBuilder = toFloat(coalesce(data, "total_anticipos", 0L));
        if (totalAnticiposBuilder <= 0 && !empty(data.get("anticipos"))) {
            Object sumMonto = Php.arraySumColumn(data.get("anticipos"), "monto");
            totalAnticiposBuilder = toFloat(truthy(sumMonto) ? sumMonto : Php.arraySumColumn(data.get("anticipos"), "total"));
        }
        if (totalAnticiposBuilder > 0 && mtoOperGravadas > 0) {
            double igvFracAnticipo = taxRates.defaultIgvRate(tenant) / 100;
            anticipoBase = round(totalAnticiposBuilder / (1 + igvFracAnticipo), 2);
            mtoOperGravadas = round(Math.max(0, mtoOperGravadas - anticipoBase), 2);
            mtoIgv = round(mtoOperGravadas * igvFracAnticipo, 2);
        } else if (totalAnticiposBuilder > 0 && (mtoOperInafectas > 0 || mtoOperExoneradas > 0)) {
            anticipoBase = round(totalAnticiposBuilder, 2);
            anticipoCodDesc = "05";
            if (mtoOperExoneradas > 0) {
                mtoOperExoneradas = round(Math.max(0, mtoOperExoneradas - anticipoBase), 2);
            }
        }

        // TaxTotal/TaxAmount = suma de TaxSubtotal/TaxAmount (SUNAT 3294)
        double totalImpuestosHeader = anticipoBase > 0
                ? mtoIgv + toFloat(coalesce(data, "mto_isc", 0L)) + toFloat(coalesce(data, "mto_icbper", 0L))
                        + toFloat(coalesce(data, "mto_ivap", 0L))
                : toFloat(coalesce(data, "total_impuestos", mtoIgv));

        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        if (empty(data.get("mto_igv_gratuitas")) && !empty(items)) {
            double computed = 0;
            for (Map<String, Object> item : items) {
                if (inArray(coalesce(item, "tip_afe_igv", ""), GRATUITO_GRAVADO)) {
                    computed += toFloat(coalesce(item, "igv", 0L));
                }
            }
            if (computed > 0) {
                data.put("mto_igv_gratuitas", round(computed, 2));
            }
        }

        if (mtoOperGravadas > 0) {
            invoice.mtoOperGravadas = mtoOperGravadas;
        }
        if (mtoOperExoneradas > 0) {
            invoice.mtoOperExoneradas = mtoOperExoneradas;
        }
        if (mtoOperInafectas > 0) {
            invoice.mtoOperInafectas = mtoOperInafectas;
        }
        if (mtoOperGratuitas > 0) {
            invoice.mtoOperGratuitas = mtoOperGratuitas;
        }
        if (mtoOperExportacion > 0) {
            invoice.mtoOperExportacion = mtoOperExportacion;
        }
        if (mtoIgv > 0) {
            invoice.mtoIGV = mtoIgv;
        }
        invoice.totalImpuestos = totalImpuestosHeader;
        invoice.valorVenta = toFloat(coalesce(data, "valor_venta", 0L));
        invoice.subTotal = toFloat(coalesce(data, "sub_total", 0L));
        invoice.mtoImpVenta = toFloat(coalesce(data, "mto_imp_venta", 0L));

        if (!empty(data.get("mto_igv_gratuitas"))) {
            invoice.mtoIGVGratuitas = toFloat(data.get("mto_igv_gratuitas"));
        }
        if (!empty(data.get("mto_isc"))) {
            invoice.mtoISC = toFloat(data.get("mto_isc"));
            Object base = data.get("mto_base_isc") != null ? data.get("mto_base_isc") : coalesce(data, "mto_oper_gravadas", 0L);
            invoice.mtoBaseIsc = toFloat(base);
        }
        if (!empty(data.get("mto_icbper"))) {
            invoice.icbper = toFloat(data.get("mto_icbper"));
        }
        if (!empty(data.get("mto_base_ivap"))) {
            invoice.mtoBaseIvap = toFloat(data.get("mto_base_ivap"));
        }
        if (!empty(data.get("mto_ivap"))) {
            invoice.mtoIvap = toFloat(data.get("mto_ivap"));
        }
        if (!empty(data.get("sum_otros_descuentos"))) {
            invoice.sumOtrosDescuentos = toFloat(data.get("sum_otros_descuentos"));
        }

        List<SaleDetail> details = new ArrayList<>();
        for (Map<String, Object> item : items) {
            details.add(buildItem(item));
        }
        invoice.details = details;

        // Leyendas
        List<Legend> legends = new ArrayList<>();
        if (!empty(data.get("leyenda"))) {
            legends.add(new Legend("1000", toStr(data.get("leyenda"))));
        }
        if (!empty(data.get("leyendas"))) {
            for (Object l : (List<Object>) data.get("leyendas")) {
                Map<String, Object> legend = (Map<String, Object>) l;
                legends.add(new Legend(toStr(legend.get("code")), toStr(legend.get("value"))));
            }
        }
        if (!empty(data.get("detraccion")) && legends.stream().noneMatch(l -> "2006".equals(l.code))) {
            legends.add(new Legend("2006", "Operación sujeta a detracción"));
        }
        if (!empty(data.get("percepcion")) && legends.stream().noneMatch(l -> "2000".equals(l.code))) {
            legends.add(new Legend("2000", "COMPROBANTE DE PERCEPCIÓN"));
        }
        boolean tieneIvap = items.stream().anyMatch(it -> strictEq(coalesce(it, "tip_afe_igv", ""), "17"));
        if (tieneIvap && legends.stream().noneMatch(l -> "2007".equals(l.code))) {
            legends.add(new Legend("2007", "Operación sujeta al IVAP"));
        }
        if (!legends.isEmpty()) {
            invoice.legends = legends;
        }

        // Guías relacionadas
        if (!empty(data.get("guias"))) {
            List<Document> guias = new ArrayList<>();
            for (Object g : (List<Object>) data.get("guias")) {
                Map<String, Object> guia = (Map<String, Object>) g;
                Document d = new Document();
                d.tipoDoc = toStr(guia.get("tipo_doc"));
                d.nroDoc = toStr(guia.get("nro_doc"));
                guias.add(d);
            }
            invoice.guias = guias;
        }

        // Detracción
        if (!empty(data.get("detraccion"))) {
            Map<String, Object> det = (Map<String, Object>) data.get("detraccion");
            invoice.tipoOperacion = toStr(coalesce(det, "tipo_operacion", "1001"));
            Detraction d = new Detraction();
            d.codBienDetraccion = toStr(det.get("cod_bien"));
            d.codMedioPago = toStr(coalesce(det, "cod_medio_pago", "001"));
            d.ctaBanco = toStr(det.get("cta_banco"));
            d.percent = toFloat(det.get("porcentaje"));
            d.mount = toFloat(det.get("monto"));
            invoice.detraccion = d;
        }

        // Percepción
        if (!empty(data.get("percepcion"))) {
            Map<String, Object> per = (Map<String, Object>) data.get("percepcion");
            invoice.tipoOperacion = toStr(coalesce(per, "tipo_operacion", "2001"));
            double perBase = toFloat(coalesce(per, "mto_base", coalesce(per, "base", 0L)));
            double perMto = toFloat(coalesce(per, "mto", coalesce(per, "monto", 0L)));
            double perTotal = per.get("mto_total") != null ? toFloat(per.get("mto_total")) : perBase + perMto;
            double porcentaje;
            if (perBase > 0) {
                porcentaje = round(perMto / perBase, 5);
            } else {
                porcentaje = toFloat(coalesce(per, "porcentaje", 0L));
                if (porcentaje > 1) {
                    porcentaje = porcentaje / 100;
                }
            }
            String codRegRaw = toStr(coalesce(per, "cod_reg", coalesce(per, "cod_regimen", "51")));
            String codReg = switch (codRegRaw) {
                case "01" -> "51";
                case "02" -> "52";
                case "03" -> "53";
                default -> codRegRaw;
            };
            SalePerception p = new SalePerception();
            p.codReg = codReg;
            p.porcentaje = porcentaje;
            p.mtoBase = perBase;
            p.mto = perMto;
            p.mtoTotal = perTotal;
            invoice.perception = p;
        }

        // Anticipos
        double totalAnticipos = 0;
        if (!empty(data.get("anticipos"))) {
            List<Prepayment> anticipos = new ArrayList<>();
            for (Object a : (List<Object>) data.get("anticipos")) {
                Map<String, Object> ant = (Map<String, Object>) a;
                String nroDocRel;
                if (ant.get("nro_doc_rel") != null) {
                    nroDocRel = toStr(ant.get("nro_doc_rel"));
                } else if (isset(ant, "serie") && isset(ant, "correlativo")) {
                    nroDocRel = toStr(ant.get("serie")) + "-" + toStr(ant.get("correlativo"));
                } else {
                    nroDocRel = "";
                }
                Prepayment p = new Prepayment();
                p.tipoDocRel = toStr(coalesce(ant, "tipo_doc_rel", coalesce(ant, "tipo_doc", "02")));
                p.nroDocRel = nroDocRel;
                p.total = toFloat(coalesce(ant, "total", coalesce(ant, "monto", 0L)));
                anticipos.add(p);
            }
            invoice.anticipos = anticipos;
            Object v = data.get("total_anticipos") != null
                    ? data.get("total_anticipos")
                    : Php.arraySumColumn(data.get("anticipos"), "total");
            if (!truthy(v)) {
                v = Php.arraySumColumn(data.get("anticipos"), "monto");
            }
            totalAnticipos = toFloat(v);
            invoice.totalAnticipos = totalAnticipos;
        }

        // Descuentos globales
        List<Charge> descuentos = new ArrayList<>();
        if (!empty(data.get("descuentos_globales"))) {
            for (Object o : (List<Object>) data.get("descuentos_globales")) {
                Map<String, Object> desc = (Map<String, Object>) o;
                double monto = toFloat(coalesce(desc, "monto", 0L));
                double factor;
                double montoBase;
                if (!empty(desc.get("porcentaje"))) {
                    factor = round(toFloat(desc.get("porcentaje")) / 100, 6);
                    montoBase = round(mtoOperGravadas, 2);
                    monto = round(montoBase * factor, 2);
                } else if (!empty(desc.get("factor")) && !empty(desc.get("monto_base"))) {
                    factor = toFloat(desc.get("factor"));
                    montoBase = toFloat(desc.get("monto_base"));
                } else {
                    montoBase = round(mtoOperGravadas != 0 ? mtoOperGravadas : monto, 2);
                    factor = montoBase > 0 ? round(monto / montoBase, 6) : 1;
                }
                Charge c = new Charge();
                c.codTipo = toStr(coalesce(desc, "cod_tipo", "02"));
                c.montoBase = montoBase;
                c.factor = factor;
                c.monto = monto;
                descuentos.add(c);
            }
        }
        if (totalAnticipos > 0 && anticipoBase > 0) {
            boolean hasDescAnticipo = descuentos.stream().anyMatch(d -> "04".equals(d.codTipo) || "05".equals(d.codTipo));
            if (!hasDescAnticipo) {
                Charge c = new Charge();
                c.codTipo = anticipoCodDesc;
                c.montoBase = anticipoBase;
                c.factor = 1.0;
                c.monto = anticipoBase;
                descuentos.add(c);
            }
        }
        if (!descuentos.isEmpty()) {
            invoice.descuentos = descuentos;
        }
        return invoice;
    }

    @SuppressWarnings("unchecked")
    SaleDetail buildItem(Map<String, Object> item) {
        SaleDetail detail = new SaleDetail();
        Object tipRaw = item.get("tip_afe_igv") != null ? item.get("tip_afe_igv") : taxRates.defaultTipAfeIgv(tenant);
        double porcentajeIgv = taxRates.rateForItem(item, tenant);
        double cantidad = toFloat(item.get("cantidad"));
        double precioUnitario = toFloat(item.get("precio_unitario"));
        boolean isGratuito = inArray(tipRaw, GRATUITO);
        boolean isGratuitoGravado = inArray(tipRaw, GRATUITO_GRAVADO);
        boolean es10 = strictEq(tipRaw, "10");
        boolean es17 = strictEq(tipRaw, "17");
        boolean esSinIgv = inArray(tipRaw, List.of("20", "30", "40"));

        Double valorUnitario = null;
        if (es10 || es17) {
            valorUnitario = round(precioUnitario / (1 + porcentajeIgv / 100), 10);
        } else if (esSinIgv) {
            valorUnitario = precioUnitario;
        }

        double descuentoBase = 0;
        double descuentoConIgv = 0;
        List<Map<String, Object>> recalculated = null;
        if (!empty(item.get("descuentos"))) {
            double valorBrutoForDiscount = valorUnitario != null
                    ? valorUnitario
                    : (isGratuito ? toFloat(coalesce(item, "mto_valor_unitario", precioUnitario)) : precioUnitario);
            double valorBruto = valorBrutoForDiscount * cantidad;
            double totalConIgvBruto = round(precioUnitario * cantidad, 2);
            recalculated = new ArrayList<>();
            for (Object o : (List<Object>) item.get("descuentos")) {
                Map<String, Object> desc = (Map<String, Object>) o;
                Object codTipo = coalesce(desc, "cod_tipo", "00");
                if (strictEq(codTipo, "00") || strictEq(codTipo, "01")) {
                    double montoBase = round(valorBruto, 2);
                    double factor;
                    double monto;
                    if (!empty(desc.get("porcentaje"))) {
                        factor = round(toFloat(desc.get("porcentaje")) / 100, 6);
                        monto = round(montoBase * factor, 2);
                    } else if (!empty(desc.get("factor"))) {
                        factor = toFloat(desc.get("factor"));
                        monto = round(montoBase * factor, 2);
                    } else {
                        monto = toFloat(coalesce(desc, "monto", 0L));
                        factor = montoBase > 0 ? round(monto / montoBase, 6) : 0;
                    }
                    descuentoBase += monto;
                    descuentoConIgv += round(totalConIgvBruto * factor, 2);
                    // "01" se normaliza a "00": ambos reducen la base (SUNAT 3271)
                    recalculated.add(Map.of("cod_tipo", "00", "monto_base", montoBase, "factor", factor, "monto", monto));
                } else {
                    recalculated.add(desc);
                }
            }
        }

        double valorVenta;
        double igv;
        Double igvBase = null;
        double vu = valorUnitario == null ? 0 : valorUnitario;
        if (es10 || es17) {
            valorVenta = round(vu * cantidad - descuentoBase, 2);
            double totalConIgv = round(precioUnitario * cantidad, 2) - descuentoConIgv;
            igv = round(totalConIgv - valorVenta, 2);
        } else if (esSinIgv) {
            valorVenta = round(vu * cantidad - descuentoBase, 2);
            igv = 0;
            porcentajeIgv = 0;
        } else if (isGratuitoGravado) {
            double valorGratuito = toFloat(coalesce(item, "mto_valor_unitario", precioUnitario));
            valorUnitario = 0.0;
            igvBase = round(valorGratuito * cantidad, 2);
            valorVenta = igvBase;
            igv = item.get("igv") != null ? toFloat(item.get("igv")) : round(igvBase * porcentajeIgv / 100, 2);
        } else if (isGratuito) {
            valorUnitario = 0.0;
            valorVenta = 0;
            igv = 0;
            porcentajeIgv = 0;
        } else {
            valorUnitario = toFloat(coalesce(item, "mto_valor_unitario", precioUnitario));
            valorVenta = round(valorUnitario * cantidad, 2);
            igv = toFloat(coalesce(item, "igv", 0L));
        }

        if (!isGratuito) {
            valorUnitario = item.get("mto_valor_unitario") != null ? toFloat(item.get("mto_valor_unitario")) : toFloat(valorUnitario);
        }
        if (isGratuitoGravado) {
            valorVenta = item.get("mto_valor_venta") != null ? toFloat(item.get("mto_valor_venta")) : valorVenta;
        } else if (isGratuito) {
            double valorRefUnit = toFloat(coalesce(item, "mto_valor_unitario", 0L));
            if (valorRefUnit <= 0) {
                valorRefUnit = toFloat(coalesce(item, "mto_valor_venta", 0L)) / Math.max(cantidad, 1);
            }
            valorVenta = round(valorRefUnit * cantidad, 2);
        } else {
            valorVenta = item.get("mto_valor_venta") != null ? toFloat(item.get("mto_valor_venta")) : valorVenta;
        }

        double isc = toFloat(coalesce(item, "isc", 0L));
        double icbper = toFloat(coalesce(item, "icbper", 0L));

        double defaultBaseIgv;
        if (isGratuitoGravado) {
            defaultBaseIgv = igvBase != null
                    ? igvBase
                    : round(toFloat(coalesce(item, "mto_valor_unitario", precioUnitario)) * cantidad, 2);
        } else {
            defaultBaseIgv = valorVenta;
        }

        double baseIgv;
        if (isc > 0 && !isset(item, "mto_base_igv") && !isset(item, "igv")) {
            baseIgv = round(defaultBaseIgv + isc, 2);
            igv = round(baseIgv * porcentajeIgv / 100, 2);
        } else {
            igv = item.get("igv") != null ? toFloat(item.get("igv")) : igv;
            baseIgv = item.get("mto_base_igv") != null ? toFloat(item.get("mto_base_igv")) : defaultBaseIgv;
        }
        if (inArray(tipRaw, GRATUITO_INAFECTO)) {
            baseIgv = valorVenta;
        }

        double totalImpuestos = item.get("total_impuestos") != null ? toFloat(item.get("total_impuestos")) : igv + isc + icbper;

        detail.codProducto = toStr(coalesce(item, "codigo", ""));
        detail.unidad = toStr(item.get("unidad"));
        detail.descripcion = toStr(item.get("descripcion"));
        detail.cantidad = cantidad;
        detail.mtoValorUnitario = toFloat(valorUnitario);
        detail.mtoValorVenta = valorVenta;
        detail.mtoBaseIgv = baseIgv;
        detail.porcentajeIgv = porcentajeIgv;
        detail.igv = igv;
        detail.tipAfeIgv = toStr(tipRaw);
        detail.totalImpuestos = totalImpuestos;

        if (!empty(item.get("cod_producto_sunat"))) {
            detail.codProdSunat = toStr(item.get("cod_producto_sunat"));
        }

        if (isGratuito || isGratuitoGravado) {
            double mtoValorGratuito = toFloat(coalesce(item, "mto_valor_unitario", 0L));
            if (mtoValorGratuito <= 0 && cantidad > 0) {
                mtoValorGratuito = round(toFloat(coalesce(item, "mto_valor_venta", 0L)) / cantidad, 2);
            }
            if (mtoValorGratuito <= 0) {
                mtoValorGratuito = precioUnitario;
            }
            detail.mtoPrecioUnitario = 0.0;
            detail.mtoValorGratuito = mtoValorGratuito;
        } else if (descuentoConIgv > 0 && cantidad > 0) {
            double effectiveTotal = round(precioUnitario * cantidad, 2) - descuentoConIgv;
            detail.mtoPrecioUnitario = round(effectiveTotal / cantidad, 10);
        } else {
            detail.mtoPrecioUnitario = precioUnitario;
        }

        Object descuentosToUse = recalculated != null ? recalculated : item.get("descuentos");
        if (!empty(descuentosToUse)) {
            List<Charge> charges = new ArrayList<>();
            for (Object o : (List<Object>) descuentosToUse) {
                Map<String, Object> desc = (Map<String, Object>) o;
                Charge c = new Charge();
                c.codTipo = toStr(coalesce(desc, "cod_tipo", "00"));
                c.montoBase = round(toFloat(coalesce(desc, "monto_base", 0L)), 2);
                c.factor = toFloat(coalesce(desc, "factor", 1L));
                c.monto = round(toFloat(desc.get("monto")), 2);
                charges.add(c);
            }
            detail.descuentos = charges;
        }

        if (isc > 0) {
            double mtoBaseIsc = item.get("mto_base_isc") != null ? toFloat(item.get("mto_base_isc")) : valorVenta;
            double pctIsc = toFloat(coalesce(item, "porcentaje_isc", 0L));
            if (pctIsc <= 0 && mtoBaseIsc > 0) {
                pctIsc = round(isc / mtoBaseIsc * 100, 2);
            }
            detail.isc = isc;
            detail.mtoBaseIsc = mtoBaseIsc;
            detail.porcentajeIsc = pctIsc;
            detail.tipSisIsc = toStr(coalesce(item, "tip_sis_isc", "01"));
        }

        if (icbper > 0) {
            detail.icbper = icbper;
            double factorIcbper = toFloat(coalesce(item, "factor_icbper", 0L));
            if (factorIcbper <= 0 && cantidad > 0) {
                factorIcbper = round(icbper / cantidad, 2);
            }
            detail.factorIcbper = factorIcbper;
        }
        return detail;
    }

    Company buildCompany(String codLocal) {
        Company company = new Company();
        company.ruc = tenant.getRuc();
        company.razonSocial = tenant.getRazonSocial();
        // SUNAT 4092: si no hay nombre comercial se usa la razón social
        company.nombreComercial = truthy(tenant.getNombreComercial()) ? tenant.getNombreComercial() : tenant.getRazonSocial();
        Address address = new Address();
        address.codLocal = codLocal;
        address.direccion = tenant.getDireccion() != null ? tenant.getDireccion() : "-";
        if (truthy(tenant.getUbigeo())) {
            address.ubigueo = tenant.getUbigeo();
        }
        if (truthy(tenant.getDepartamento())) {
            address.departamento = tenant.getDepartamento();
        }
        if (truthy(tenant.getProvincia())) {
            address.provincia = tenant.getProvincia();
        }
        if (truthy(tenant.getDistrito())) {
            address.distrito = tenant.getDistrito();
        }
        company.address = address;
        return company;
    }

    static Client buildClient(Map<String, Object> clientData) {
        Client client = new Client();
        client.tipoDoc = toStr(clientData.get("tipo_doc"));
        client.numDoc = toStr(clientData.get("num_doc"));
        client.rznSocial = toStr(clientData.get("razon_social"));
        if (!empty(clientData.get("direccion"))) {
            Address a = new Address();
            a.direccion = toStr(clientData.get("direccion"));
            client.address = a;
        }
        return client;
    }
}
