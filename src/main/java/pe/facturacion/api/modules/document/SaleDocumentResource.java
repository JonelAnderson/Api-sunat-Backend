package pe.facturacion.api.modules.document;

import static pe.facturacion.api.modules.document.SaleDocumentRepository.cast;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pe.facturacion.api.modules.client.Client;
import pe.facturacion.api.modules.document.SaleDocument.Payment;
import pe.facturacion.api.modules.document.SaleDocument.SaleItem;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.php.PhpMath;
import pe.facturacion.api.shared.time.Clock;

/**
 * {@code InvoiceResource} y {@code BoletaResource} (mismo orden de claves, mismos filtros y las
 * pequeñas diferencias entre ambos: descuentos por ítem, posición de {@code cuotas}, {@code extras}).
 */
public final class SaleDocumentResource {

    private SaleDocumentResource() {
    }

    /**
     * @param items    {@code null} si la relación no se cargó ({@code whenLoaded})
     * @param payments {@code null} si la relación no se cargó
     * @param baseUrl  raíz de la petición (el {@code url()} de Laravel)
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toArray(SaleDocument d, Client client, List<SaleItem> items, List<Payment> payments, String baseUrl) {
        boolean factura = d.kind == DocumentKind.FACTURA;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", d.id);
        r.put("tipo_documento", d.kind.tipoDocumento);
        r.put("serie", d.serie);
        r.put("correlativo", d.correlativo);
        r.put("numero_completo", d.numeroCompleto());
        r.put("cod_local", d.codLocal);
        r.put("fecha_emision", d.fechaEmision.format(Clock.Y_M_D_H_I_S));
        r.put("fecha_vencimiento", d.fechaVencimiento == null ? null : d.fechaVencimiento.toString());
        r.put("tipo_operacion", d.tipoOperacion);
        r.put("tipo_moneda", d.tipoMoneda);
        r.put("forma_pago", d.formaPago);

        Map<String, Object> cliente = new LinkedHashMap<>();
        putNotNull(cliente, "tipo_doc", d.clientTipoDoc);
        putNotNull(cliente, "num_doc", d.clientNumDoc);
        putNotNull(cliente, "razon_social", d.clientRazonSocial);
        putNotNull(cliente, "direccion", d.clientDireccion);
        putNotNull(cliente, "email", client == null ? null : client.getEmail());
        putNotNull(cliente, "telefono", client == null ? null : client.getTelefono());
        r.put("cliente", cliente.isEmpty() ? List.of() : cliente);

        Map<String, Object> totales = new LinkedHashMap<>();
        positive(totales, "gravadas", d.mtoOperGravadas);
        positive(totales, "exoneradas", d.mtoOperExoneradas);
        positive(totales, "inafectas", d.mtoOperInafectas);
        positive(totales, "exportacion", d.mtoOperExportacion);
        positive(totales, "gratuitas", d.mtoOperGratuitas);
        positive(totales, "igv", d.mtoIgv);
        positive(totales, "base_ivap", d.mtoBaseIvap);
        positive(totales, "ivap", d.mtoIvap);
        positive(totales, "isc", d.mtoIsc);
        positive(totales, "icbper", d.mtoIcbper);
        totales.put("total_impuestos", f(d.totalImpuestos));
        totales.put("valor_venta", f(d.valorVenta));
        totales.put("sub_total", f(d.subTotal));
        totales.put("total", f(d.mtoImpVenta));
        r.put("totales", totales);

        if (items != null) {
            List<Object> list = new ArrayList<>();
            for (SaleItem i : items) {
                list.add(item(i, factura));
            }
            r.put("items", list);
        }
        if (!factura && Php.truthy(d.cuotas)) {
            r.put("cuotas", d.cuotas);
        }
        if (Php.truthy(d.detraccion)) {
            r.put("detraccion", d.detraccion);
        }
        if (Php.truthy(d.percepcion)) {
            r.put("percepcion", d.percepcion);
        }
        if (Php.truthy(d.anticipos)) {
            r.put("anticipos", d.anticipos);
        }
        if (factura && Php.truthy(d.cuotas)) {
            r.put("cuotas", d.cuotas);
        }
        if (factura) {
            if (!Php.empty(d.descuentosGlobales)) {
                List<Object> list = new ArrayList<>();
                for (Object o : (List<Object>) d.descuentosGlobales) {
                    list.add(discount((Map<String, Object>) o, "02"));
                }
                r.put("descuentos_globales", list);
            }
        } else if (Php.truthy(d.descuentosGlobales)) {
            r.put("descuentos_globales", d.descuentosGlobales);
        }
        if (Php.truthy(d.guias)) {
            r.put("guias", d.guias);
        }
        if (factura && Php.truthy(d.extras)) {
            r.put("extras", d.extras);
        }

        Map<String, Object> sunat = new LinkedHashMap<>();
        sunat.put("estado", d.sunatStatus);
        sunat.put("codigo", d.sunatCode);
        sunat.put("descripcion", d.sunatDescription);
        sunat.put("notas", d.sunatNotes);
        sunat.put("hash_cpe", d.hashCpe);
        r.put("sunat", sunat);

        String prefix = baseUrl + "/api/v1/" + d.kind.urlSegment + "/" + d.id;
        Map<String, Object> archivos = new LinkedHashMap<>();
        archivos.put("xml", d.xmlPath != null ? prefix + "/xml" : null);
        archivos.put("cdr", d.cdrPath != null ? prefix + "/cdr" : null);
        archivos.put("pdf", d.pdfPath != null ? prefix + "/pdf" : null);
        r.put("archivos", archivos);

        r.put("leyenda", d.leyenda);
        r.put("observacion", d.observacion);
        r.put("estado_pago", d.paymentStatus);
        r.put("monto_pagado", f(d.montoPagado));
        if (payments != null) {
            List<Object> list = new ArrayList<>();
            for (Payment p : payments) {
                list.add(payment(p));
            }
            r.put("pagos", list);
        }
        r.put("enviado_en", d.sentAt == null ? null : Clock.iso8601Utc(d.sentAt));
        r.put("creado_en", d.createdAt == null ? null : Clock.iso8601Utc(d.createdAt));
        return r;
    }

    /** {@code CreditNoteResource} / {@code DebitNoteResource}. */
    public static Map<String, Object> toNoteArray(SaleDocument d, Client client, List<SaleItem> items, String baseUrl) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", d.id);
        r.put("tipo_documento", d.kind.tipoDocumento);
        r.put("serie", d.serie);
        r.put("correlativo", d.correlativo);
        r.put("numero_completo", d.numeroCompleto());
        r.put("cod_local", d.codLocal);
        r.put("fecha_emision", d.fechaEmision.format(Clock.Y_M_D_H_I_S));
        r.put("tipo_moneda", d.tipoMoneda);
        Map<String, Object> cliente = new LinkedHashMap<>();
        putNotNull(cliente, "tipo_doc", d.clientTipoDoc);
        putNotNull(cliente, "num_doc", d.clientNumDoc);
        putNotNull(cliente, "razon_social", d.clientRazonSocial);
        putNotNull(cliente, "direccion", d.clientDireccion);
        putNotNull(cliente, "email", client == null ? null : client.getEmail());
        putNotNull(cliente, "telefono", client == null ? null : client.getTelefono());
        r.put("cliente", cliente.isEmpty() ? List.of() : cliente);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("tipo", d.docAfectadoTipo);
        doc.put("serie", d.docAfectadoSerie);
        doc.put("correlativo", d.docAfectadoCorrelativo);
        doc.put("motivo_codigo", d.codMotivo);
        doc.put("motivo_descripcion", d.desMotivo);
        r.put("doc_afectado", doc);
        Map<String, Object> totales = new LinkedHashMap<>();
        positive(totales, "gravadas", d.mtoOperGravadas);
        positive(totales, "exoneradas", d.mtoOperExoneradas);
        positive(totales, "inafectas", d.mtoOperInafectas);
        positive(totales, "gratuitas", d.mtoOperGratuitas);
        positive(totales, "igv", d.mtoIgv);
        positive(totales, "isc", d.mtoIsc);
        positive(totales, "icbper", d.mtoIcbper);
        totales.put("total_impuestos", f(d.totalImpuestos));
        totales.put("valor_venta", f(d.valorVenta));
        totales.put("sub_total", f(d.subTotal));
        totales.put("total", f(d.mtoImpVenta));
        r.put("totales", totales);
        if (items != null) {
            List<Object> list = new ArrayList<>();
            for (SaleItem i : items) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("codigo", i.codigo);
                m.put("descripcion", i.descripcion);
                m.put("unidad", i.unidad);
                m.put("cantidad", f4(i.cantidad));
                m.put("precio_unitario", f4(i.mtoPrecioUnitario));
                m.put("valor_unitario", f4(i.mtoValorUnitario));
                m.put("igv", f(i.igv));
                if (f(i.descuento) != 0) {
                    m.put("descuento", f(i.descuento));
                }
                m.put("total", f(i.mtoValorVenta));
                list.add(m);
            }
            r.put("items", list);
        }
        if (Php.truthy(d.guias)) {
            r.put("guias", d.guias);
        }
        Map<String, Object> sunat = new LinkedHashMap<>();
        sunat.put("estado", d.sunatStatus);
        sunat.put("codigo", d.sunatCode);
        sunat.put("descripcion", d.sunatDescription);
        sunat.put("notas", d.sunatNotes);
        sunat.put("hash_cpe", d.hashCpe);
        r.put("sunat", sunat);
        String prefix = baseUrl + "/api/v1/" + d.kind.urlSegment + "/" + d.id;
        Map<String, Object> archivos = new LinkedHashMap<>();
        archivos.put("xml", d.xmlPath != null ? prefix + "/xml" : null);
        archivos.put("cdr", d.cdrPath != null ? prefix + "/cdr" : null);
        archivos.put("pdf", d.pdfPath != null ? prefix + "/pdf" : null);
        r.put("archivos", archivos);
        r.put("leyenda", d.leyenda);
        r.put("observacion", d.observacion);
        r.put("enviado_en", d.sentAt == null ? null : Clock.iso8601Utc(d.sentAt));
        r.put("creado_en", d.createdAt == null ? null : Clock.iso8601Utc(d.createdAt));
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> item(SaleItem i, boolean factura) {
        double precioUnitario = f4(i.mtoPrecioUnitario);
        double cantidad = f4(i.cantidad);
        double valorVenta = f(i.mtoValorVenta);
        double igv = f(i.igv);
        double icbper = i.icbper == null ? 0 : f(i.icbper);
        double isc = i.isc == null ? 0 : f(i.isc);
        double descuentoBase = f(i.descuento);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codigo", i.codigo);
        m.put("descripcion", i.descripcion);
        m.put("unidad", i.unidad);
        m.put("cantidad", cantidad);
        m.put("precio_unitario", precioUnitario);
        m.put("valor_unitario", f4(i.mtoValorUnitario));
        m.put("igv", igv);
        if (isc != 0) {
            m.put("isc", isc);
        }
        if (icbper != 0) {
            m.put("icbper", icbper);
        }
        if (descuentoBase != 0) {
            m.put("descuento", descuentoBase);
        }
        if (factura && !Php.empty(i.descuentos)) {
            List<Object> list = new ArrayList<>();
            for (Object o : (List<Object>) i.descuentos) {
                list.add(discount((Map<String, Object>) o, "00"));
            }
            m.put("descuentos", list);
        }
        m.put("total", PhpMath.round(valorVenta + igv + icbper + isc, 2));
        return m;
    }

    private static Map<String, Object> discount(Map<String, Object> d, String defaultTipo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cod_tipo", Php.coalesce(d, "cod_tipo", defaultTipo));
        m.put("monto_base", Php.toFloat(Php.coalesce(d, "monto_base", 0L)));
        m.put("factor", Php.toFloat(Php.coalesce(d, "factor", 0L)));
        m.put("monto", Php.toFloat(Php.coalesce(d, "monto", 0L)));
        return m;
    }

    /** {@code PaymentController::formatPayment()} y el bloque {@code pagos} del Resource. */
    public static Map<String, Object> payment(Payment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id);
        m.put("metodo", p.metodo);
        m.put("monto", f(p.monto));
        m.put("referencia", p.referencia);
        // decimal:2 → "0.00" es truthy en PHP: solo null da null
        m.put("monto_recibido", p.montoRecibido != null ? f(p.montoRecibido) : null);
        m.put("vuelto", "efectivo".equals(p.metodo) && p.montoRecibido != null
                ? PhpMath.round(f(p.montoRecibido) - f(p.monto), 2) : null);
        m.put("notas", p.notas);
        m.put("creado_en", p.createdAt == null ? null : Clock.iso8601Utc(p.createdAt));
        return m;
    }

    private static void positive(Map<String, Object> m, String key, BigDecimal v) {
        double d = f(v);
        if (d > 0) {
            m.put(key, d);
        }
    }

    private static void putNotNull(Map<String, Object> m, String key, Object v) {
        if (v != null) {
            m.put(key, v);
        }
    }

    /** {@code (float) $model->campo} con cast {@code decimal:2}. */
    static double f(BigDecimal v) {
        return v == null ? 0 : Php.toFloat(cast(v, 2));
    }

    /** {@code (float)} de un cast {@code decimal:4}. */
    static double f4(BigDecimal v) {
        return v == null ? 0 : Php.toFloat(cast(v, 4));
    }
}
