package pe.facturacion.api.modules.pdf;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.document.SaleDocument;
import pe.facturacion.api.modules.document.SaleDocument.Payment;
import pe.facturacion.api.modules.document.SaleDocument.SaleItem;
import pe.facturacion.api.modules.retper.RetPerKind;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.php.PhpMath;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;
import pe.facturacion.api.sunat.pdf.PdfDocumentTypes;
import pe.facturacion.api.sunat.pdf.QrImage;

/**
 * {@code DocumentDataMapper} de API-PRO: datos de la representación impresa por tipo de documento,
 * con los cálculos de presentación de las vistas Blade (tasa de IGV, montos netos de anticipos,
 * etiquetas, forma de pago...) ya resueltos para que las plantillas solo pinten.
 *
 * <p>QR: {@code RUC|TIPO|SERIE|CORRELATIVO|IGV|TOTAL|FECHA|TIPO_DOC|NUM_DOC} (retención:
 * retenido y pagado; percepción: percibido y cobrado; liquidación: IGV y neto pagado al
 * vendedor). Corrección: la guía transportista informa su tipo real (31); API-PRO ponía 09.
 */
@Component
public class PdfDataMapper {

    static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a", Locale.US);
    static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final Map<String, String> GUIA_TIPOS = Map.of("09", "Guía de Remisión", "31", "Guía de Remisión Transportista",
            "71", "Guía de Remisión Remitente");

    private final FileStorage storage;

    public PdfDataMapper(FileStorage storage) {
        this.storage = storage;
    }

    // ------------------------------------------------------------ comprobantes

    /** Factura, boleta, nota de crédito o de débito ({@code mapStandardDocument}). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> sale(SaleDocument d, Tenant tenant, List<Payment> payments) {
        String tipo = d.kind.tipoDocumento;
        Map<String, Object> m = base(tenant, tipo, d.serie, d.numeroCompleto(), d.fechaEmision, d.codLocal);
        boolean contingencia = d.serie != null && d.serie.matches("\\d+");
        if (contingencia && (tipo.equals("01") || tipo.equals("03"))) {
            m.put("titulo", Php.toStr(m.get("titulo")).replace("ELECTRÓNICA", "DE CONTINGENCIA"));
        }
        m.put("fecha_vencimiento", d.fechaVencimiento == null ? null : d.fechaVencimiento.toString());
        String moneda = d.tipoMoneda == null ? "PEN" : d.tipoMoneda;
        m.put("tipo_moneda", moneda);
        m.put("moneda_simbolo", simbolo(moneda));
        m.put("forma_pago", d.formaPago);
        m.put("receptor", receptor(d.clientTipoDoc, d.clientNumDoc, d.clientRazonSocial, d.clientDireccion == null ? "" : d.clientDireccion));

        List<Object> items = new ArrayList<>();
        Double igvPct = null;
        boolean ivap = false;
        for (SaleItem it : d.items) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("cantidad", f(it.cantidad));
            i.put("unidad", it.unidad == null ? "NIU" : it.unidad);
            i.put("descripcion", it.descripcion);
            i.put("cod_producto_sunat", it.codProductoSunat);
            i.put("valor_unitario", f(it.mtoValorUnitario));
            i.put("precio_unitario", f(it.mtoPrecioUnitario));
            i.put("descuento", f(it.descuento));
            i.put("importe_con_igv", PhpMath.round(f(it.mtoValorVenta) + f(it.igv), 2));
            items.add(i);
            if (igvPct == null && f(it.porcentajeIgv) > 0 && f(it.igv) > 0) {
                igvPct = f(it.porcentajeIgv);
            }
            ivap |= "17".equals(it.tipAfeIgv);
        }
        m.put("items", items);

        double gravadas = f(d.mtoOperGravadas);
        double igv = f(d.mtoIgv);
        double anticipos = f(d.totalAnticipos);
        Map<String, Object> percepcion = percepcion(d.percepcion);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("moneda_prefix", "PEN".equals(moneda) ? simbolo(moneda) : moneda);
        t.put("gravadas", gravadas);
        t.put("exoneradas", f(d.mtoOperExoneradas));
        t.put("inafectas", f(d.mtoOperInafectas));
        t.put("gratuitas", f(d.mtoOperGratuitas));
        t.put("base_ivap", f(d.mtoBaseIvap));
        t.put("ivap", f(d.mtoIvap));
        t.put("isc", f(d.mtoIsc));
        t.put("icbper", f(d.mtoIcbper));
        t.put("descuentos", f(d.totalDescuentos));
        t.put("total", f(d.mtoImpVenta));
        t.put("anticipos", anticipos);
        t.put("igv_pct", igvPctText(d.tipoOperacion, igvPct, gravadas, igv));
        double gravDisp = gravadas;
        double igvDisp = igv;
        if (anticipos > 0 && gravadas > 0) {
            double frac = igv > 0 ? igv / gravadas : 0;
            double base = frac > 0 ? PhpMath.round(anticipos / (1 + frac), 2) : anticipos;
            gravDisp = PhpMath.round(gravadas - base, 2);
            igvDisp = PhpMath.round(igv - (anticipos - base), 2);
        }
        t.put("grav_disp", gravDisp);
        t.put("igv_disp", igvDisp);
        t.put("show_igv", igv > 0 || f(d.mtoBaseIvap) == 0);
        t.put("percepcion", percepcion);
        m.put("totales", t);

        m.put("leyenda", d.leyenda == null ? "" : d.leyenda);
        List<String> leyendas = new ArrayList<>();
        Map<String, Object> detraccion = detraccion(d.detraccion);
        if (detraccion != null) {
            leyendas.add("Operación sujeta a detracción");
        }
        if (percepcion != null) {
            leyendas.add("COMPROBANTE DE PERCEPCIÓN");
        }
        if (ivap) {
            leyendas.add("Operación sujeta al IVAP");
        }
        m.put("leyendas_sunat", leyendas);
        m.put("observacion", d.observacion);
        m.put("hash_cpe", d.hashCpe == null ? "" : d.hashCpe);

        // Información de pago
        Map<String, Object> pago = new LinkedHashMap<>();
        boolean conPagos = payments != null && !payments.isEmpty();
        double adelanto = 0;
        if (conPagos) {
            for (Payment p : payments) {
                adelanto += f(p.monto);
            }
        }
        pago.put("adelanto", conPagos && "Credito".equals(d.formaPago) ? adelanto : null);
        List<Object> cuotas = new ArrayList<>();
        int n = 0;
        for (Object o : asList(d.cuotas)) {
            Map<String, Object> c = o instanceof Map<?, ?> cm ? (Map<String, Object>) cm : Map.of();
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("n", ++n);
            q.put("fecha", Php.toStr(Php.coalesce(c, "fecha_pago", Php.coalesce(c, "fecha", ""))));
            q.put("monto", Php.toFloat(Php.coalesce(c, "monto", 0L)));
            cuotas.add(q);
        }
        pago.put("cuotas", cuotas);
        pago.put("detraccion", detraccion);
        pago.put("monto_neto", detraccion != null ? PhpMath.round(f(d.mtoImpVenta) - Php.toFloat(detraccion.get("monto")), 2) : f(d.mtoImpVenta));
        List<Object> guias = new ArrayList<>();
        boolean first = true;
        for (Object o : asList(d.guias)) {
            Map<String, Object> g = o instanceof Map<?, ?> gm ? (Map<String, Object>) gm : Map.of();
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("label", GUIA_TIPOS.getOrDefault(Php.toStr(g.get("tipo_doc")), "Guía"));
            q.put("nro", Php.toStr(g.get("nro_doc")));
            q.put("first", first);
            first = false;
            guias.add(q);
        }
        pago.put("guias", guias);
        pago.put("percepcion", percepcion);
        boolean credito = !cuotas.isEmpty() || "Credito".equals(d.formaPago);
        pago.put("credito_cuotas", credito && !cuotas.isEmpty());
        pago.put("mostrar", (tipo.equals("01") || tipo.equals("03")) && (detraccion != null || credito || !Php.empty(d.formaPago) || conPagos
                || !guias.isEmpty() || percepcion != null));
        pago.put("mostrar_ticket", (tipo.equals("01") || tipo.equals("03")) && (!Php.empty(d.formaPago) || !cuotas.isEmpty()
                || detraccion != null || conPagos || !guias.isEmpty()));
        m.put("pago", pago);

        if (d.kind.isNote() && !Php.empty(d.docAfectadoTipo)) {
            Map<String, Object> nota = new LinkedHashMap<>();
            nota.put("tipo_label", switch (d.docAfectadoTipo) {
                case "01" -> "Factura";
                case "03" -> "Boleta";
                case "07" -> "Nota de Crédito";
                case "08" -> "Nota de Débito";
                default -> "Documento";
            });
            nota.put("numero", (d.docAfectadoSerie == null ? "" : d.docAfectadoSerie) + "-"
                    + (d.docAfectadoCorrelativo == null ? "" : d.docAfectadoCorrelativo));
            nota.put("cod_motivo", d.codMotivo);
            nota.put("des_motivo", d.desMotivo == null ? "" : d.desMotivo);
            m.put("nota", nota);
        }
        m.put("qr_base64", QrImage.dataUri(String.join("|", tenant.getRuc(), tipo, d.serie, String.valueOf(d.correlativo),
                dec2(d.mtoIgv), dec2(d.mtoImpVenta), d.fechaEmision.toLocalDate().toString(), Php.toStr(d.clientTipoDoc),
                Php.toStr(d.clientNumDoc))));
        return m;
    }

    /** "18" / "10.5": tasa de IGV de los totales (Blade: totals.blade.php). */
    static String igvPctText(String tipoOperacion, Double pct, double gravadas, double igv) {
        double v;
        if (tipoOperacion != null && tipoOperacion.startsWith("02")) {
            return "0";
        } else if (pct != null && pct != 0) {
            v = pct;
        } else if (gravadas > 0 && igv > 0) {
            v = PhpMath.round(igv / gravadas * 100, 2);
        } else {
            return "0";
        }
        if (v == Math.floor(v)) {
            return String.valueOf((long) v);
        }
        String s = PhpMath.numberFormat(v, 2);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> percepcion(Object raw) {
        if (Php.empty(raw) || !(raw instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, Object> p = (Map<String, Object>) raw;
        double porcentaje = Php.toFloat(Php.coalesce(p, "porcentaje", 0L));
        double monto = Php.toFloat(p.get("mto") != null ? p.get("mto") : Php.coalesce(p, "monto", 0L));
        double base = Php.toFloat(p.get("mto_base") != null ? p.get("mto_base") : Php.coalesce(p, "base", 0L));
        double total = Php.toFloat(Php.coalesce(p, "mto_total", 0L));
        if (total == 0.0) {
            total = PhpMath.round(base + monto, 2);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codigo", Php.toStr(p.get("cod_regimen") != null ? p.get("cod_regimen") : p.get("cod_reg") != null ? p.get("cod_reg") : Php.coalesce(p, "codigo", "")));
        m.put("porcentaje", porcentaje);
        m.put("monto", monto);
        m.put("mto_base", base);
        m.put("mto_total", total);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> detraccion(Object raw) {
        if (Php.empty(raw) || !(raw instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, Object> d = (Map<String, Object>) raw;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codigo", Php.toStr(d.get("cod_bien") != null ? d.get("cod_bien") : Php.coalesce(d, "codigo", "")));
        m.put("cuenta", Php.toStr(d.get("cta_banco") != null ? d.get("cta_banco") : Php.coalesce(d, "cuenta", "")));
        m.put("porcentaje", Php.toFloat(Php.coalesce(d, "porcentaje", 0L)));
        m.put("monto", Php.toFloat(Php.coalesce(d, "monto", 0L)));
        return m;
    }

    // ------------------------------------------------------------------ guías

    @SuppressWarnings("unchecked")
    public Map<String, Object> guide(Map<String, Object> g, Tenant tenant) {
        String tipo = Php.toStr(Php.coalesce(g, "tipo_documento", "09"));
        String numero = g.get("serie") + "-" + String.format("%06d", ((Number) g.get("correlativo")).longValue());
        Map<String, Object> m = base(tenant, tipo, (String) g.get("serie"), numero, (LocalDateTime) g.get("fecha_emision"), (String) g.get("cod_local"));
        m.put("tipo_moneda", "PEN");
        m.put("moneda_simbolo", "S/");
        m.put("receptor", receptor((String) g.get("destinatario_tipo_doc"), (String) g.get("destinatario_num_doc"),
                (String) g.get("destinatario_razon_social"), ""));
        List<Object> items = new ArrayList<>();
        for (Object o : asList(g.get("items"))) {
            Map<String, Object> it = (Map<String, Object>) o;
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("cantidad", Php.toFloat(Php.coalesce(it, "cantidad", 0L)));
            i.put("unidad", Php.toStr(Php.coalesce(it, "unidad", "NIU")));
            i.put("descripcion", Php.toStr(it.get("descripcion") != null ? it.get("descripcion") : Php.coalesce(it, "nombre", "")));
            i.put("cod_producto_sunat", null);
            items.add(i);
        }
        m.put("items", items);
        Map<String, Object> transp = g.get("transportista") instanceof Map<?, ?> t ? (Map<String, Object>) t : Map.of();
        Map<String, Object> veh = g.get("vehiculo") instanceof Map<?, ?> v ? (Map<String, Object>) v : Map.of();
        List<Object> conductores = new ArrayList<>();
        Object cond = g.get("conductor");
        List<Object> lista = cond instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>(List.of(cond == null ? Map.of() : cond));
        boolean first = true;
        for (Object o : lista) {
            Map<String, Object> c = o instanceof Map<?, ?> cm ? (Map<String, Object>) cm : Map.of();
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("num_doc", Php.toStr(c.get("num_doc") != null ? c.get("num_doc") : Php.coalesce(c, "nroDoc", "")));
            q.put("nombres", Php.toStr(Php.coalesce(c, "nombres", "")));
            q.put("apellidos", Php.toStr(Php.coalesce(c, "apellidos", "")));
            q.put("licencia", Php.toStr(Php.coalesce(c, "licencia", "")));
            q.put("first", first);
            first = false;
            conductores.add(q);
        }
        Map<String, Object> dsp = new LinkedHashMap<>();
        dsp.put("cod_traslado", g.get("cod_traslado"));
        dsp.put("mod_traslado", g.get("mod_traslado"));
        dsp.put("fecha_traslado", g.get("fecha_traslado") == null ? "" : g.get("fecha_traslado").toString());
        dsp.put("peso_total", Php.toFloat(g.get("peso_total")));
        dsp.put("und_peso_total", Php.toStr(Php.coalesce(g, "und_peso_total", "KGM")));
        dsp.put("num_bultos", g.get("num_bultos"));
        dsp.put("partida_ubigeo", g.get("partida_ubigeo"));
        dsp.put("partida_direccion", g.get("partida_direccion"));
        dsp.put("llegada_ubigeo", g.get("llegada_ubigeo"));
        dsp.put("llegada_direccion", g.get("llegada_direccion"));
        dsp.put("transportista_razon_social", Php.toStr(transp.get("razon_social") != null ? transp.get("razon_social")
                : Php.coalesce(transp, "denominacion", "")));
        dsp.put("transportista_num_doc", Php.toStr(Php.coalesce(transp, "num_doc", "")));
        dsp.put("placa", Php.toStr(veh.get("placa") != null ? veh.get("placa") : Php.coalesce(veh, "nroPlaca", "")));
        dsp.put("conductores", conductores);
        m.put("dispatch", dsp);
        m.put("hash_cpe", g.get("hash_cpe") == null ? "" : g.get("hash_cpe"));
        m.put("observacion", null);
        m.put("leyenda", "");
        m.put("qr_base64", QrImage.dataUri(String.join("|", tenant.getRuc(), tipo, (String) g.get("serie"),
                String.valueOf(((Number) g.get("correlativo")).longValue()), "0.00", "0.00",
                ((LocalDateTime) g.get("fecha_emision")).toLocalDate().toString(), Php.toStr(g.get("destinatario_tipo_doc")),
                Php.toStr(g.get("destinatario_num_doc")))));
        return m;
    }

    // ------------------------------------------------ retención y percepción

    public Map<String, Object> retper(RetPerKind k, Map<String, Object> r, List<Map<String, Object>> items, Tenant tenant) {
        String numero = r.get("serie") + "-" + String.format("%08d", ((Number) r.get("correlativo")).longValue());
        Map<String, Object> m = base(tenant, k.tipoDocumento, (String) r.get("serie"), numero, (LocalDateTime) r.get("fecha_emision"), "0000");
        m.put("tipo_moneda", "PEN");
        m.put("moneda_simbolo", "S/");
        String t = k.tercero;
        m.put("receptor", receptor((String) r.get(t + "_tipo_doc"), (String) r.get(t + "_num_doc"), (String) r.get(t + "_razon_social"),
                r.get(t + "_direccion") == null ? "" : (String) r.get(t + "_direccion")));
        double tasa = Php.toFloat(r.get("tasa"));
        String regimen = (String) r.get("regimen");
        Map<String, String> regimenes = k.perception
                ? Map.of("01", "Percepción Venta Interna (2%)", "02", "Percepción adquisición de combustible (1%)",
                        "03", "Percepción agente con tasa especial (0.5%)")
                : Map.of("01", "Tasa 3% (Régimen General)", "02", "Tasa 6% (Régimen Especial)");
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("regimen_label", regimenes.getOrDefault(regimen, "Tasa " + PhpMath.toStr(tasa) + "%"));
        ret.put("tasa", PhpMath.toStr(tasa));
        ret.put("monto", Php.toFloat(r.get(k.totalMonto)));
        ret.put("neto", Php.toFloat(r.get(k.totalNeto)));
        ret.put("label_monto", k.perception ? "Imp. Percibido" : "Imp. Retenido");
        ret.put("label_neto", k.perception ? "Imp. a Cobrar" : "Imp. a Pagar");
        ret.put("label_fecha", k.perception ? "F. Percepción" : "F. Retención");
        ret.put("label_fecha_ticket", k.perception ? "F.Perc" : "F.Ret");
        ret.put("label_neto_ticket", k.perception ? "Cobrar" : "Pagar");
        ret.put("label_monto_ticket", k.perception ? "Percibido" : "Retenido");
        Map<String, String> tipos = Map.of("01", "Factura", "03", "Boleta", "12", "T.Reg.");
        List<Object> docs = new ArrayList<>();
        for (Map<String, Object> it : items) {
            Map<String, Object> d = new LinkedHashMap<>();
            String td = (String) it.get("tipo_doc");
            d.put("tipo_label", tipos.getOrDefault(td, td));
            d.put("num_doc", it.get("num_doc"));
            d.put("fecha_emision", ((LocalDate) it.get("fecha_emision_doc")).format(FECHA));
            String moneda = it.get("moneda") == null ? "PEN" : (String) it.get("moneda");
            d.put("moneda", moneda);
            d.put("simbolo_total", "USD".equals(moneda) ? "$" : "S/");
            d.put("imp_total", Php.toFloat(it.get("imp_total")));
            d.put("fecha", ((LocalDate) it.get(k.fechaItem)).format(FECHA));
            d.put("monto", Php.toFloat(it.get(k.itemMonto)));
            d.put("neto", Php.toFloat(it.get(k.itemNeto)));
            docs.add(d);
        }
        ret.put("documentos", docs);
        m.put("retencion", ret);
        m.put("hash_cpe", r.get("hash_cpe") == null ? "" : r.get("hash_cpe"));
        m.put("observacion", r.get("observacion"));
        m.put("leyenda", "");
        m.put("qr_base64", QrImage.dataUri(String.join("|", tenant.getRuc(), k.tipoDocumento, (String) r.get("serie"),
                String.valueOf(((Number) r.get("correlativo")).longValue()), PhpMath.numberFormat(Php.toFloat(r.get(k.totalMonto)), 2),
                PhpMath.numberFormat(Php.toFloat(r.get(k.totalNeto)), 2), ((LocalDateTime) r.get("fecha_emision")).toLocalDate().toString(),
                Php.toStr(r.get(t + "_tipo_doc")), Php.toStr(r.get(t + "_num_doc")))));
        return m;
    }

    // ---------------------------------------------------- liquidación de compra

    public Map<String, Object> settlement(Map<String, Object> s, List<Map<String, Object>> items, Tenant tenant) {
        String numero = s.get("serie") + "-" + String.format("%08d", ((Number) s.get("correlativo")).longValue());
        Map<String, Object> m = base(tenant, "04", (String) s.get("serie"), numero, (LocalDateTime) s.get("fecha_emision"), (String) s.get("cod_local"));
        m.put("tipo_moneda", "PEN");
        m.put("moneda_simbolo", "S/");
        Map<String, Object> vendedor = receptor((String) s.get("vendedor_tipo_doc"), (String) s.get("vendedor_num_doc"),
                (String) s.get("vendedor_nombre"), (String) s.get("vendedor_direccion"));
        vendedor.put("titulo", "Vendedor:");
        m.put("receptor", vendedor);
        List<Object> lines = new ArrayList<>();
        for (Map<String, Object> it : items) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("cantidad", f(it.get("cantidad")));
            i.put("unidad", it.get("unidad"));
            i.put("descripcion", it.get("descripcion"));
            i.put("cod_producto_sunat", it.get("cod_producto_sunat"));
            i.put("valor_unitario", f(it.get("valor_unitario")));
            i.put("precio_unitario", f(it.get("precio_unitario")));
            i.put("descuento", 0.0);
            i.put("importe_con_igv", PhpMath.round(f(it.get("valor_venta")) + f(it.get("igv")), 2));
            lines.add(i);
        }
        m.put("items", lines);
        Map<String, String> cat60 = Map.of("01", "Punto de venta", "02", "Producción", "03", "Extracción", "04", "Explotación", "05", "Otros");
        Map<String, Object> lc = new LinkedHashMap<>();
        lc.put("operacion", switch (Php.toStr(s.get("tipo_operacion"))) {
            case "0503" -> "0503 - Compra de oro";
            case "0502" -> "0502 - Anticipos";
            default -> "0501 - Compra interna";
        });
        lc.put("domicilio", "[" + s.get("vendedor_ubigeo") + "] " + s.get("vendedor_direccion") + " ("
                + cat60.getOrDefault((String) s.get("vendedor_tipo_domicilio"), Php.toStr(s.get("vendedor_tipo_domicilio"))) + ")");
        lc.put("lugar", "[" + s.get("lugar_ubigeo") + "] " + s.get("lugar_direccion") + " ("
                + cat60.getOrDefault((String) s.get("lugar_tipo"), Php.toStr(s.get("lugar_tipo"))) + ")");
        lc.put("placa", s.get("placa_vehiculo"));
        lc.put("gravadas", f(s.get("mto_oper_gravadas")));
        lc.put("exoneradas", f(s.get("mto_oper_exoneradas")));
        lc.put("inafectas", f(s.get("mto_oper_inafectas")));
        lc.put("igv_pct", igvPctText(null, f(s.get("porcentaje_igv")), 0, 0));
        lc.put("igv", f(s.get("mto_igv")));
        lc.put("sub_total", f(s.get("sub_total")));
        lc.put("renta_pct", PhpMath.toStr(f(s.get("porcentaje_renta"))));
        lc.put("renta", f(s.get("mto_retencion_renta")));
        lc.put("neto", f(s.get("mto_imp_neto")));
        m.put("liquidacion", lc);
        m.put("leyenda", s.get("leyenda") == null ? "" : s.get("leyenda"));
        m.put("leyendas_sunat", List.of());
        m.put("hash_cpe", s.get("hash_cpe") == null ? "" : s.get("hash_cpe"));
        m.put("observacion", s.get("observacion"));
        m.put("qr_base64", QrImage.dataUri(String.join("|", tenant.getRuc(), "04", (String) s.get("serie"),
                String.valueOf(((Number) s.get("correlativo")).longValue()), dec2(s.get("mto_igv")), dec2(s.get("mto_imp_neto")),
                ((LocalDateTime) s.get("fecha_emision")).toLocalDate().toString(), Php.toStr(s.get("vendedor_tipo_doc")),
                Php.toStr(s.get("vendedor_num_doc")))));
        return m;
    }

    // ------------------------------------------------------------------ apoyo

    private Map<String, Object> base(Tenant tenant, String tipo, String serie, String numero, LocalDateTime fecha, String codLocal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo_documento", tipo);
        m.put("titulo", PdfDocumentTypes.get(tipo).titulo());
        m.put("serie", serie);
        m.put("numero_completo", numero);
        m.put("fecha_emision", fecha == null ? "" : fecha.format(FECHA_HORA));
        m.put("fecha_vencimiento", null);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("ruc", tenant.getRuc());
        e.put("razon_social", tenant.getRazonSocial());
        e.put("nombre_comercial", tenant.getNombreComercial() != null ? tenant.getNombreComercial() : tenant.getRazonSocial());
        e.put("direccion", tenant.getDireccion() == null ? "" : tenant.getDireccion());
        e.put("cod_local", codLocal == null ? "0000" : codLocal);
        m.put("emisor", e);
        m.put("logo_base64", logo(tenant));
        m.put("telefonos", tenant.getTelefonos() == null ? List.of() : tenant.getTelefonos());
        m.put("emails", tenant.getEmails() == null ? List.of() : tenant.getEmails());
        List<Object> cuentas = new ArrayList<>();
        for (Object o : tenant.getCuentasBancarias() == null ? List.of() : tenant.getCuentasBancarias()) {
            if (o instanceof Map<?, ?> c) {
                Map<String, Object> q = new LinkedHashMap<>();
                q.put("banco", Php.toStr(c.get("banco")));
                q.put("tipo_cuenta", c.get("tipo_cuenta") == null ? "" : Php.toStr(c.get("tipo_cuenta")));
                q.put("moneda", c.get("moneda") == null ? "PEN" : Php.toStr(c.get("moneda")));
                q.put("numero", Php.toStr(c.get("numero")));
                q.put("cci", c.get("cci") == null ? "" : Php.toStr(c.get("cci")));
                cuentas.add(q);
            }
        }
        m.put("cuentas_bancarias", cuentas);
        List<Object> billeteras = new ArrayList<>();
        List<?> lb = tenant.getBilleterasDigitales() == null ? List.of() : tenant.getBilleterasDigitales();
        for (int i = 0; i < lb.size(); i++) {
            if (lb.get(i) instanceof Map<?, ?> b) {
                Map<String, Object> q = new LinkedHashMap<>();
                String tipo2 = Php.toStr(b.get("tipo"));
                q.put("tipo", tipo2.isEmpty() ? tipo2 : Character.toUpperCase(tipo2.charAt(0)) + tipo2.substring(1));
                q.put("numero", Php.toStr(b.get("numero")));
                q.put("titular", b.get("titular") == null ? "" : Php.toStr(b.get("titular")));
                q.put("last", i == lb.size() - 1);
                billeteras.add(q);
            }
        }
        m.put("billeteras_digitales", billeteras);
        m.put("mensaje_agradecimiento", tenant.getMensajeAgradecimiento());
        m.put("mensaje_promocional", tenant.getMensajePromocional());
        m.put("leyendas_sunat", List.of());
        return m;
    }

    private static Map<String, Object> receptor(String tipoDoc, String numDoc, String razonSocial, String direccion) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tipo_doc", tipoDoc);
        r.put("num_doc", numDoc);
        r.put("razon_social", razonSocial);
        r.put("direccion", direccion);
        String t = tipoDoc == null ? "" : tipoDoc;
        r.put("label", switch (t) {
            case "6" -> "RUC";
            case "1" -> "DNI";
            case "4" -> "Carnet Extranjería";
            case "7" -> "Pasaporte";
            default -> "Documento";
        });
        r.put("label_ticket", switch (t) {
            case "6" -> "RUC";
            case "1" -> "DNI";
            case "4" -> "C.E.";
            case "7" -> "Pasaporte";
            default -> "Doc";
        });
        r.put("titulo", "Cliente:");
        return r;
    }

    /** Logo embebido como {@code data:} URI (el motor de PDF no descarga URLs). */
    public String logo(Tenant tenant) {
        String path = tenant.getLogoPath();
        if (path == null || path.isEmpty() || !storage.exists(Disk.PUBLIC, path)) {
            return null;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        String mime = lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "image/jpeg" : lower.endsWith(".gif") ? "image/gif" : "image/png";
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(storage.get(Disk.PUBLIC, path));
    }

    static String simbolo(String moneda) {
        return switch (moneda) {
            case "PEN" -> "S/";
            case "USD" -> "$";
            case "EUR" -> "€";
            default -> moneda;
        };
    }

    /** Decimal de la BD tal como lo da Eloquent ({@code decimal:2} → "180.00"). */
    static String dec2(Object v) {
        if (v == null) {
            return "0.00";
        }
        return v instanceof BigDecimal b ? b.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() : PhpMath.numberFormat(Php.toFloat(v), 2);
    }

    static double f(Object v) {
        return v == null ? 0 : Php.toFloat(v);
    }

    static List<Object> asList(Object v) {
        if (v instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        if (v instanceof Map<?, ?> m) {
            return new ArrayList<>(m.values());
        }
        return new ArrayList<>();
    }

    static boolean isSale(DocumentKind k) {
        return k == DocumentKind.FACTURA || k == DocumentKind.BOLETA;
    }
}
