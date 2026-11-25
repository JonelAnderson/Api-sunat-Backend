package pe.facturacion.api.sunat.builder;

import static pe.facturacion.api.shared.php.Php.coalesce;
import static pe.facturacion.api.shared.php.Php.empty;
import static pe.facturacion.api.shared.php.Php.inArray;
import static pe.facturacion.api.shared.php.Php.isset;
import static pe.facturacion.api.shared.php.Php.strictEq;
import static pe.facturacion.api.shared.php.Php.toFloat;
import static pe.facturacion.api.shared.php.Php.toStr;
import static pe.facturacion.api.shared.php.PhpMath.round;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import pe.facturacion.api.modules.tax.TaxRateService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.sunat.model.SaleModels.Charge;
import pe.facturacion.api.sunat.model.SaleModels.Document;
import pe.facturacion.api.sunat.model.SaleModels.Legend;
import pe.facturacion.api.sunat.model.SaleModels.Note;
import pe.facturacion.api.sunat.model.SaleModels.SaleDetail;

/**
 * Port de {@code App\Services\Greenter\Builders\NoteBuilder} (notas de crédito 07 y débito 08).
 * Difiere del de factura: no fija valor de venta ni subtotal, las gratuitas van con valor de
 * venta 0 y el precio efectivo con descuento se redondea a 2 decimales.
 */
public final class NoteModelBuilder {

    private final Tenant tenant;
    private final TaxRateService taxRates;
    private final InvoiceModelBuilder shared;

    public NoteModelBuilder(Tenant tenant, TaxRateService taxRates) {
        this.tenant = tenant;
        this.taxRates = taxRates;
        this.shared = new InvoiceModelBuilder(tenant, taxRates);
    }

    @SuppressWarnings("unchecked")
    public Note build(Map<String, Object> data) {
        Note note = new Note();
        note.ublVersion = "2.1";
        note.tipoDoc = toStr(data.get("tipo_documento"));
        note.serie = toStr(data.get("serie"));
        note.correlativo = toStr(data.get("correlativo"));
        note.fechaEmision = PhpDates.parseDateTime(toStr(data.get("fecha_emision")));
        note.tipoMoneda = toStr(coalesce(data, "tipo_moneda", "PEN"));
        note.company = shared.buildCompany(toStr(coalesce(data, "cod_local", "0000")));
        note.client = InvoiceModelBuilder.buildClient((Map<String, Object>) data.get("cliente"));

        // Documento afectado: opcional en la ND por penalidad (motivo 13)
        if (!empty(data.get("doc_afectado_tipo")) && !empty(data.get("doc_afectado_serie"))) {
            note.tipDocAfectado = toStr(data.get("doc_afectado_tipo"));
            note.numDocfectado = toStr(data.get("doc_afectado_serie")) + "-" + toStr(data.get("doc_afectado_correlativo"));
        }
        note.codMotivo = toStr(data.get("cod_motivo"));
        note.desMotivo = toStr(data.get("des_motivo"));

        double mtoOperGravadas = toFloat(coalesce(data, "mto_oper_gravadas", 0L));
        double mtoOperExoneradas = toFloat(coalesce(data, "mto_oper_exoneradas", 0L));
        double mtoOperInafectas = toFloat(coalesce(data, "mto_oper_inafectas", 0L));
        double mtoOperGratuitas = toFloat(coalesce(data, "mto_oper_gratuitas", 0L));
        double mtoIgv = toFloat(coalesce(data, "mto_igv", 0L));
        if (mtoOperGravadas > 0) {
            note.mtoOperGravadas = mtoOperGravadas;
        }
        if (mtoOperExoneradas > 0) {
            note.mtoOperExoneradas = mtoOperExoneradas;
        }
        if (mtoOperInafectas > 0) {
            note.mtoOperInafectas = mtoOperInafectas;
        }
        if (mtoOperGratuitas > 0) {
            note.mtoOperGratuitas = mtoOperGratuitas;
        }
        if (mtoIgv > 0) {
            note.mtoIGV = mtoIgv;
        }
        note.totalImpuestos = toFloat(coalesce(data, "total_impuestos", mtoIgv));
        note.mtoImpVenta = toFloat(coalesce(data, "mto_imp_venta", 0L));

        if (!empty(data.get("mto_igv_gratuitas"))) {
            note.mtoIGVGratuitas = toFloat(data.get("mto_igv_gratuitas"));
        }
        if (!empty(data.get("mto_isc"))) {
            note.mtoISC = toFloat(data.get("mto_isc"));
            Object base = data.get("mto_base_isc") != null ? data.get("mto_base_isc") : coalesce(data, "mto_oper_gravadas", 0L);
            note.mtoBaseIsc = toFloat(base);
        }
        if (!empty(data.get("mto_icbper"))) {
            note.icbper = toFloat(data.get("mto_icbper"));
        }
        if (!empty(data.get("mto_base_ivap"))) {
            note.mtoBaseIvap = toFloat(data.get("mto_base_ivap"));
        }
        if (!empty(data.get("mto_ivap"))) {
            note.mtoIvap = toFloat(data.get("mto_ivap"));
        }
        if (!empty(data.get("sum_otros_descuentos"))) {
            note.sumOtrosDescuentos = toFloat(data.get("sum_otros_descuentos"));
        }

        List<SaleDetail> details = new ArrayList<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) data.get("items")) {
            details.add(buildItem(item));
        }
        note.details = details;

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
        if (!legends.isEmpty()) {
            note.legends = legends;
        }

        if (!empty(data.get("guias"))) {
            List<Document> guias = new ArrayList<>();
            for (Object g : (List<Object>) data.get("guias")) {
                Map<String, Object> guia = (Map<String, Object>) g;
                Document d = new Document();
                d.tipoDoc = toStr(guia.get("tipo_doc"));
                d.nroDoc = toStr(guia.get("nro_doc"));
                guias.add(d);
            }
            note.guias = guias;
        }

        // Descuentos globales (la plantilla de notas no los imprime; se conservan en el modelo)
        if (!empty(data.get("descuentos_globales"))) {
            List<Charge> descuentos = new ArrayList<>();
            for (Object o : (List<Object>) data.get("descuentos_globales")) {
                Map<String, Object> desc = (Map<String, Object>) o;
                Charge c = new Charge();
                c.codTipo = toStr(coalesce(desc, "cod_tipo", "02"));
                c.montoBase = toFloat(coalesce(desc, "monto_base", 0L));
                c.factor = toFloat(coalesce(desc, "factor", coalesce(desc, "porcentaje", 1L)));
                c.monto = toFloat(desc.get("monto"));
                descuentos.add(c);
            }
            note.descuentos = descuentos;
        }
        return note;
    }

    @SuppressWarnings("unchecked")
    SaleDetail buildItem(Map<String, Object> item) {
        SaleDetail detail = new SaleDetail();
        Object tipRaw = item.get("tip_afe_igv") != null ? item.get("tip_afe_igv") : taxRates.defaultTipAfeIgv(tenant);
        double porcentajeIgv = taxRates.rateForItem(item, tenant);
        double cantidad = toFloat(item.get("cantidad"));
        double precioUnitario = toFloat(item.get("precio_unitario"));
        boolean isGratuito = inArray(tipRaw, InvoiceModelBuilder.GRATUITO);
        boolean isGratuitoGravado = inArray(tipRaw, InvoiceModelBuilder.GRATUITO_GRAVADO);
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
                if (strictEq(codTipo, "00")) {
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
                    recalculated.add(Map.of("cod_tipo", codTipo, "monto_base", montoBase, "factor", factor, "monto", monto));
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
            valorVenta = 0;
            igvBase = round(valorGratuito * cantidad, 2);
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
        if (isGratuito || isGratuitoGravado) {
            valorVenta = 0;
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
            detail.mtoPrecioUnitario = 0.0;
            detail.mtoValorGratuito = toFloat(coalesce(item, "mto_valor_unitario", precioUnitario));
        } else if (descuentoConIgv > 0 && cantidad > 0) {
            double effectiveTotal = round(precioUnitario * cantidad, 2) - descuentoConIgv;
            detail.mtoPrecioUnitario = round(effectiveTotal / cantidad, 2);
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
}
