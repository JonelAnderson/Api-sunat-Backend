package pe.facturacion.api.modules.admin.panel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.document.SaleDocument;
import pe.facturacion.api.modules.document.SaleDocument.SaleItem;
import pe.facturacion.api.modules.document.SaleDocumentRepository;
import pe.facturacion.api.modules.guide.GuideRepository;
import pe.facturacion.api.modules.tax.TaxRateService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.php.Php;

/**
 * {@code datos_edicion} del detalle de un comprobante en el panel (Docs/api-sunat-panel-admin-diseno.md §12.1): el
 * cuerpo que acepta el {@code PUT} de "corregir y reenviar" (§11.8.4), armado con lo guardado, para que el formulario
 * lo precargue completo en lugar de pedir de nuevo lo que no muestra el recurso de {@code /api/v1}.
 *
 * <p>Solo existe en el panel: el recurso de {@code /api/v1} no cambia. Mismas claves snake_case del {@code PUT}; no
 * lleva {@code correlativo} (el {@code PUT} lo tomaría como un número manual repetido), {@code leyenda} (se recalcula
 * con los ítems) ni {@code motivo} (lo escribe el administrador).
 */
@Component
public class AdminEditData {

    private final GuideRepository guides;
    private final SaleDocumentRepository documents;
    private final TaxRateService taxRates;

    public AdminEditData(GuideRepository guides, SaleDocumentRepository documents, TaxRateService taxRates) {
        this.guides = guides;
        this.documents = documents;
        this.taxRates = taxRates;
    }

    /** Cuerpo de {@code PUT tenants/{id}/guias-remision/{docId}} (null si la guía no es de la empresa). */
    public Map<String, Object> guide(Tenant tenant, long id) {
        return guides.find(tenant.getId(), id).map(AdminEditData::guide).orElse(null);
    }

    /** Cuerpo de {@code PUT tenants/{id}/{tipo}/{docId}} (null si el comprobante no es de la empresa). */
    public Map<String, Object> sale(DocumentKind kind, Tenant tenant, long id) {
        return documents.find(kind, tenant.getId(), id).map(d -> {
            documents.loadItems(d);
            return sale(d, item -> taxRates.rateForItem(item, tenant));
        }).orElse(null);
    }

    // ------------------------------------------------------------------ armado (sin acceso a datos)

    /** Guía remitente (09) o transportista (31) desde su fila de {@code dispatch_guides} (JSON ya decodificado). */
    static Map<String, Object> guide(Map<String, Object> g) {
        boolean grt = "31".equals(g.get("tipo_documento"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo_documento", g.get("tipo_documento"));
        m.put("serie", g.get("serie"));
        m.put("fecha_emision", day(g.get("fecha_emision")));
        Map<String, Object> dest = new LinkedHashMap<>();
        dest.put("tipo_doc", g.get("destinatario_tipo_doc"));
        dest.put("num_doc", g.get("destinatario_num_doc"));
        dest.put("razon_social", g.get("destinatario_razon_social"));
        m.put("destinatario", dest);
        m.put("tercero", g.get("tercero"));
        m.put("comprador", g.get("comprador"));
        if (!grt) {
            // La GRT no lleva motivo ni modalidad (las columnas guardan "01" solo porque son NOT NULL)
            m.put("cod_traslado", g.get("cod_traslado"));
            m.put("mod_traslado", g.get("mod_traslado"));
        }
        m.put("fecha_traslado", day(g.get("fecha_traslado")));
        m.put("fecha_entrega_transportista", day(g.get("fecha_entrega_transportista")));
        m.put("peso_total", number(g.get("peso_total")));
        m.put("und_peso_total", g.get("und_peso_total"));
        m.put("num_bultos", g.get("num_bultos") instanceof Number n ? (Object) n.longValue() : null);
        m.put("indicadores", g.get("indicadores"));
        for (String k : List.of("partida_ubigeo", "partida_direccion", "partida_ruc", "partida_cod_local",
                "llegada_ubigeo", "llegada_direccion", "llegada_ruc", "llegada_cod_local")) {
            m.put(k, g.get(k));
        }
        m.put("transportista", g.get("transportista"));
        m.put("vehiculo", g.get("vehiculo"));
        // La columna conductor guarda el conductor (objeto) o la lista de conductores
        Object conductor = g.get("conductor");
        boolean lista = conductor instanceof List<?>;
        m.put("conductor", lista ? null : conductor);
        m.put("conductores", lista ? conductor : null);
        m.put("remitente", g.get("remitente"));
        m.put("doc_relacionado", g.get("doc_relacionado"));
        m.put("datos_subcontratador", g.get("datos_subcontratador"));
        m.put("datos_pagador_flete", g.get("datos_pagador_flete"));
        m.put("autorizacion_especial", g.get("autorizacion_especial"));
        m.put("observacion", g.get("observacion"));
        m.put("items", g.get("items"));
        return m;
    }

    /**
     * Factura, boleta o nota con sus ítems cargados. {@code defaultRate}: la tasa de IGV que el servidor usaría para un
     * ítem sin {@code porcentaje_igv} ({@code TaxRateService.rateForItem}); el porcentaje guardado solo se envía si es
     * otro (así un cambio de régimen o de afectación en el formulario no arrastra una tasa vieja).
     */
    static Map<String, Object> sale(SaleDocument d, ToDoubleFunction<Map<String, Object>> defaultRate) {
        boolean note = d.kind.isNote();
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> cliente = new LinkedHashMap<>();
        putNotNull(cliente, "tipo_doc", d.clientTipoDoc);
        putNotNull(cliente, "num_doc", d.clientNumDoc);
        putNotNull(cliente, "razon_social", d.clientRazonSocial);
        cliente.put("direccion", d.clientDireccion);
        m.put("cliente", cliente);
        if (!note) {
            m.put("tipo_operacion", d.tipoOperacion);
            m.put("forma_pago", d.formaPago);
            m.put("fecha_vencimiento", d.fechaVencimiento == null ? null : d.fechaVencimiento.toString());
        }
        m.put("tipo_moneda", d.tipoMoneda);
        m.put("observacion", d.observacion);
        if (note) {
            m.put("doc_afectado_tipo", d.docAfectadoTipo);
            m.put("doc_afectado_serie", d.docAfectadoSerie);
            m.put("doc_afectado_correlativo", d.docAfectadoCorrelativo);
            putNotNull(m, "cod_motivo", d.codMotivo);
            putNotNull(m, "des_motivo", d.desMotivo);
        }
        List<Object> items = new ArrayList<>();
        for (SaleItem i : d.items) {
            items.add(item(i, defaultRate));
        }
        m.put("items", items);
        // El PUT recalcula los totales con lo que llega en el cuerpo: sin estos, perdería los descuentos globales y
        // anticipos guardados (y la leyenda con percepción). El PUT no los cambia: solo cuadran los totales.
        if (!Php.empty(d.descuentosGlobales)) {
            m.put("descuentos_globales", d.descuentosGlobales);
        }
        if (Php.truthy(d.anticipos)) {
            m.put("anticipos", d.anticipos);
        }
        if (Php.truthy(d.percepcion)) {
            m.put("percepcion", d.percepcion);
        }
        return m;
    }

    static Map<String, Object> item(SaleItem i, ToDoubleFunction<Map<String, Object>> defaultRate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codigo", i.codigo);
        m.put("cod_producto_sunat", i.codProductoSunat);
        m.put("descripcion", i.descripcion);
        m.put("unidad", i.unidad);
        m.put("cantidad", number(i.cantidad));
        // Precio con IGV que se envió (en gratuitos, el valor referencial)
        m.put("precio_unitario", number(i.mtoPrecioUnitario));
        m.put("tip_afe_igv", i.tipAfeIgv);
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("tip_afe_igv", i.tipAfeIgv);
        Double pct = number(i.porcentajeIgv);
        m.put("porcentaje_igv", pct != null && Math.abs(pct - defaultRate.applyAsDouble(probe)) > 0.0001 ? pct : null);
        boolean conDescuentos = !Php.empty(i.descuentos);
        m.put("descuentos", conDescuentos ? i.descuentos : null);
        Double descuento = number(i.descuento);
        m.put("descuento", !conDescuentos && descuento != null && descuento != 0 ? descuento : null);
        // ISC e ICBPER: la tasa o el factor guardados (el monto se recalcula); si no hay, el monto
        m.put("tip_sis_isc", i.tipSisIsc);
        Double porcentajeIsc = number(i.porcentajeIsc);
        m.put("porcentaje_isc", porcentajeIsc);
        Double isc = number(i.isc);
        m.put("isc", porcentajeIsc == null && isc != null && isc > 0 ? isc : null);
        Double factorIcbper = number(i.factorIcbper);
        m.put("factor_icbper", factorIcbper);
        Double icbper = number(i.icbper);
        m.put("icbper", factorIcbper == null && icbper != null && icbper > 0 ? icbper : null);
        return m;
    }

    private static Double number(Object v) {
        if (v instanceof BigDecimal b) {
            return b.doubleValue();
        }
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static String day(Object v) {
        if (v instanceof LocalDateTime t) {
            return t.toLocalDate().toString();
        }
        if (v instanceof LocalDate d) {
            return d.toString();
        }
        return v == null ? null : v.toString();
    }

    private static void putNotNull(Map<String, Object> m, String key, Object v) {
        if (v != null) {
            m.put(key, v);
        }
    }
}
