package pe.facturacion.api.modules.document;

import static pe.facturacion.api.modules.document.SaleDocumentRepository.cast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pe.facturacion.api.modules.document.SaleDocument.SaleItem;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.php.PhpMath;
import pe.facturacion.api.shared.time.Clock;

/**
 * {@code SendDocumentToSunat::documentToArray()}: el arreglo que recibe el builder, con los
 * tipos que produce Eloquent (casts {@code decimal:N} como cadena, JSON decodificado).
 */
public final class DocumentArrayMapper {

    private DocumentArrayMapper() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toArray(SaleDocument d) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", d.id);
        data.put("serie", d.serie);
        data.put("correlativo", (long) d.correlativo);
        data.put("cod_local", d.codLocal);
        data.put("fecha_emision", d.fechaEmision.format(Clock.Y_M_D_H_I_S));
        data.put("fecha_vencimiento", d.fechaVencimiento == null ? null : d.fechaVencimiento.toString());
        data.put("tipo_operacion", d.tipoOperacion);
        data.put("tipo_moneda", d.tipoMoneda);
        data.put("forma_pago", d.formaPago);
        data.put("mto_oper_gravadas", cast(d.mtoOperGravadas, 2));
        data.put("mto_oper_exoneradas", cast(d.mtoOperExoneradas, 2));
        data.put("mto_oper_inafectas", cast(d.mtoOperInafectas, 2));
        data.put("mto_oper_exportacion", cast(d.mtoOperExportacion, 2));
        data.put("mto_oper_gratuitas", cast(d.mtoOperGratuitas, 2));
        data.put("mto_igv", cast(d.mtoIgv, 2));
        data.put("mto_base_ivap", cast(d.mtoBaseIvap, 2));
        data.put("mto_ivap", cast(d.mtoIvap, 2));
        data.put("mto_isc", cast(d.mtoIsc, 2));
        data.put("mto_icbper", cast(d.mtoIcbper, 2));
        data.put("total_impuestos", cast(d.totalImpuestos, 2));
        data.put("valor_venta", cast(d.valorVenta, 2));
        data.put("sub_total", cast(d.subTotal, 2));
        data.put("mto_imp_venta", cast(d.mtoImpVenta, 2));
        data.put("total_anticipos", cast(d.totalAnticipos, 2));
        data.put("total_descuentos", cast(d.totalDescuentos, 2));
        data.put("leyenda", d.leyenda);
        data.put("observacion", d.observacion);
        data.put("cuotas", d.cuotas);
        data.put("detraccion", d.detraccion);
        data.put("percepcion", d.percepcion);
        data.put("anticipos", d.anticipos);
        data.put("descuentos_globales", d.descuentosGlobales);
        data.put("guias", d.guias);
        data.put("extras", d.extras);
        // Corrección (DIFERENCIAS): las leyendas validadas se guardan y llegan al XML
        data.put("leyendas", d.leyendas);
        data.put("contrato_colaboracion", d.contratoColaboracion);
        if (d.kind.isNote()) {
            // credit_notes/debit_notes no tienen estas columnas: no llegan al builder
            for (String k : List.of("fecha_vencimiento", "tipo_operacion", "forma_pago", "mto_oper_exportacion", "mto_base_ivap",
                    "mto_ivap", "cuotas", "detraccion", "percepcion", "anticipos", "extras")) {
                data.remove(k);
            }
            data.put("doc_afectado_tipo", d.docAfectadoTipo);
            data.put("doc_afectado_serie", d.docAfectadoSerie);
            data.put("doc_afectado_correlativo", d.docAfectadoCorrelativo);
            data.put("cod_motivo", d.codMotivo);
            data.put("des_motivo", d.desMotivo);
        }
        data.put("tipo_documento", d.kind.tipoDocumento);
        Map<String, Object> cliente = new LinkedHashMap<>();
        cliente.put("tipo_doc", d.clientTipoDoc);
        cliente.put("num_doc", d.clientNumDoc);
        cliente.put("razon_social", d.clientRazonSocial);
        cliente.put("direccion", d.clientDireccion);
        data.put("cliente", cliente);

        // sum_otros_descuentos desde descuentos globales 03/04 (SUNAT 3300)
        double sumOtrosDescuentos = 0;
        if (!Php.empty(d.descuentosGlobales)) {
            for (Object o : (List<Object>) d.descuentosGlobales) {
                Map<String, Object> desc = (Map<String, Object>) o;
                Object codTipo = Php.coalesce(desc, "cod_tipo", "02");
                if (Php.strictEq(codTipo, "03") || Php.strictEq(codTipo, "04")) {
                    sumOtrosDescuentos += Php.toFloat(Php.coalesce(desc, "monto", 0L));
                }
            }
        }
        if (sumOtrosDescuentos > 0) {
            data.put("sum_otros_descuentos", PhpMath.round(sumOtrosDescuentos, 2));
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (SaleItem i : d.items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("codigo", i.codigo);
            m.put("cod_producto_sunat", i.codProductoSunat);
            m.put("descripcion", i.descripcion);
            m.put("unidad", i.unidad);
            m.put("cantidad", cast(i.cantidad, 4));
            m.put("precio_unitario", cast(i.mtoPrecioUnitario, 4));
            m.put("mto_valor_unitario", cast(i.mtoValorUnitario, 4));
            m.put("mto_valor_venta", cast(i.mtoValorVenta, 2));
            m.put("mto_base_igv", cast(i.mtoBaseIgv, 2));
            m.put("porcentaje_igv", cast(i.porcentajeIgv, 2));
            m.put("igv", cast(i.igv, 2));
            m.put("tip_afe_igv", i.tipAfeIgv);
            m.put("total_impuestos", cast(i.totalImpuestos, 2));
            m.put("isc", i.isc == null ? 0L : cast(i.isc, 2));
            m.put("tip_sis_isc", i.tipSisIsc == null ? "01" : i.tipSisIsc);
            m.put("icbper", i.icbper == null ? 0L : cast(i.icbper, 2));
            m.put("factor_icbper", i.factorIcbper == null ? 0L : i.factorIcbper.toPlainString());
            if (i.porcentajeIsc != null) {
                m.put("porcentaje_isc", i.porcentajeIsc.toPlainString());
            }
            if (!Php.empty(i.descuentos)) {
                m.put("descuentos", i.descuentos);
            }
            items.add(m);
        }
        data.put("items", items);
        return data;
    }
}
