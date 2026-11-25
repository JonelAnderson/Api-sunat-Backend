package pe.facturacion.api.modules.report;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.pdf.PdfDataMapper;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.php.PhpMath;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.sunat.pdf.PdfRenderer;

/**
 * {@code ReportPdfService}: PDF de los reportes con el mismo contenido que las vistas
 * {@code resources/views/pdf/reports/*.blade.php} de API-PRO. Cada reporte se describe como una
 * lista de bloques (indicadores, tablas, textos) que pinta la plantilla {@code reports/report}.
 *
 * <p>Corrección: las vistas de API-PRO leían {@code sunat_status}/{@code payment_status}, claves
 * que el reporte no trae, y el PDF fallaba siempre que había comprobantes.
 */
@Component
public class ReportPdf {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final DateTimeFormatter DMY_HIS = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss");
    private static final Map<String, String> AGING_COLORS = Map.of("0-15", "aging-green", "16-30", "aging-yellow", "31-60", "aging-orange",
            "61-90", "aging-red", "91-120", "aging-darkred", "120+", "aging-crimson");

    private final PdfDataMapper mapper;

    public ReportPdf(PdfDataMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] generate(String view, Map<String, Object> data, Tenant tenant, boolean landscape) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("tenant", Reports.map("ruc", tenant.getRuc(), "razon_social", tenant.getRazonSocial(),
                "nombre_comercial", tenant.getNombreComercial() != null ? tenant.getNombreComercial() : tenant.getRazonSocial(),
                "direccion", tenant.getDireccion(), "logo_base64", mapper.logo(tenant)));
        vars.put("generated_at", Clock.nowLima().format(DMY_HIS));
        vars.put("titulo", data.get("titulo"));
        vars.put("periodo_texto", periodo(data.get("periodo")));
        vars.put("filtros", data.get("filtros"));
        vars.put("page_size", landscape ? "A4 landscape" : "A4");
        vars.put("blocks", switch (view) {
            case "registro_ventas" -> registroVentas(data);
            case "ventas_consolidado" -> ventasConsolidado(data);
            case "notas" -> notas(data);
            case "cobranzas" -> cobranzas(data);
            case "por_cliente" -> porCliente(data);
            case "por_sucursal" -> porSucursal(data);
            default -> throw new IllegalArgumentException("Vista de reporte desconocida: " + view);
        });
        return PdfRenderer.renderTemplate("reports/report", vars);
    }

    // ------------------------------------------------------------------ reportes

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> registroVentas(Map<String, Object> d) {
        Map<String, Object> porTipo = (Map<String, Object>) d.get("totales_por_tipo");
        Table t = new Table(null).head("Nro@3", "F.Emisión@6", "F.Venc.@6", "Tipo@3", "Serie-Corr.@8", "T.Doc@3", "RUC/DNI@8", "Razón Social@14", "r:B.Gravada@6",
                "r:B.Exoner.@6", "r:B.Inafect.@6", "r:B.Gratuit.@5", "r:IGV@6", "r:ISC@4", "r:ICBPER@4", "r:Total@6", "Mon.@3", "Estado@3");
        int nro = 1;
        String actual = null;
        // Corrección: API-PRO recorría por fecha y cortaba un subtotal cada vez que cambiaba el tipo,
        // con el total COMPLETO del tipo → subtotales repetidos. Agrupado por tipo (orden estable:
        // dentro de cada tipo sigue el orden por fecha), cada subtotal aparece una vez
        List<Map<String, Object>> docs = new ArrayList<>(list(d.get("documentos")));
        docs.sort(java.util.Comparator.comparing(doc -> String.valueOf(doc.get("tipo_doc"))));
        for (Map<String, Object> doc : docs) {
            String tipo = String.valueOf(doc.get("tipo_doc"));
            if (actual != null && !actual.equals(tipo)) {
                subtotal(t, actual, (Map<String, Object>) porTipo.get(actual));
            }
            actual = tipo;
            t.row(null, c(String.valueOf(nro++), "text-center"), c(doc.get("fecha_emision")), c(doc.get("fecha_vencimiento") == null ? "-" : doc.get("fecha_vencimiento")),
                    c(tipo, "text-center"), c(doc.get("numero_completo")), c(doc.get("client_tipo_doc"), "text-center"), c(doc.get("client_num_doc")),
                    c(limit(doc.get("client_razon_social"), 25)), n(doc.get("mto_oper_gravadas")), n(doc.get("mto_oper_exoneradas")),
                    n(doc.get("mto_oper_inafectas")), n(doc.get("mto_oper_gratuitas")), n(doc.get("mto_igv")), n(doc.get("mto_isc")),
                    n(doc.get("mto_icbper")), n(doc.get("mto_imp_venta")), c(doc.get("tipo_moneda"), "text-center"), estadoSunat(doc.get("estado_sunat")));
        }
        if (actual != null && porTipo.containsKey(actual)) {
            subtotal(t, actual, (Map<String, Object>) porTipo.get(actual));
        }
        Map<String, Object> g = (Map<String, Object>) d.get("gran_total");
        t.row("total", sumCells("GRAN TOTAL:", g));
        return List.of(t.block(), text("VENTA NETA DEL PERIODO: S/ " + nf(d.get("venta_neta")),
                "margin-top: 8px; font-size: 10px; font-weight: bold; text-align: right;"));
    }

    private static void subtotal(Table t, String tipo, Map<String, Object> sub) {
        String nombre = switch (tipo) {
            case "01" -> "Facturas";
            case "03" -> "Boletas";
            case "07" -> "NC";
            default -> "ND";
        };
        t.row("subtotal", sumCells("Subtotal " + nombre + ":", sub));
    }

    private static Map<String, Object>[] sumCells(String label, Map<String, Object> s) {
        return cells(span(label, 8, "text-right"), n(s.get("mto_oper_gravadas")), n(s.get("mto_oper_exoneradas")), n(s.get("mto_oper_inafectas")),
                n(s.get("mto_oper_gratuitas")), n(s.get("mto_igv")), n(s.get("mto_isc")), n(s.get("mto_icbper")), n(s.get("mto_imp_venta")), c(""),
                c(String.valueOf(s.get("cantidad")), "text-center"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> ventasConsolidado(Map<String, Object> d) {
        Map<String, Object> k = (Map<String, Object>) d.get("kpis");
        List<Map<String, Object>> b = new ArrayList<>();
        b.add(kpis(kpi("S/ " + nf(k.get("total_facturado")), "Total Facturado", null), kpi("S/ " + nf(k.get("total_igv")), "Total IGV", null),
                kpi("S/ " + nf(k.get("venta_neta")), "Venta Neta", null), kpi(echo(k.get("porcentaje_cobrado")) + "%", "% Cobrado", null)));
        b.add(new Table(null).style("width: 60%; margin-bottom: 10px;").row(null, strong("Docs emitidos:", echo(k.get("docs_emitidos"))),
                strong("Ticket promedio:", "S/ " + nf(k.get("ticket_promedio"))), strong("NC emitidas:", echo(k.get("nc_emitidas"))),
                strong("ND emitidas:", echo(k.get("nd_emitidas")))).block());
        Table tipos = new Table("Desglose por Tipo de Documento").head("Tipo", "Nombre", "r:Cantidad", "r:Monto (S/)");
        for (Map<String, Object> r : list(d.get("desglose_por_tipo"))) {
            tipos.row(null, c(r.get("tipo"), "text-center"), c(r.get("nombre")), c(echo(r.get("cantidad")), "text-right"), n(r.get("monto")));
        }
        b.add(tipos.block());
        Table tmp = new Table("Desglose Temporal").head("Periodo", "r:Facturas", "r:Monto F.", "r:Boletas", "r:Monto B.", "r:NC", "r:ND", "r:Neto (S/)", "r:IGV (S/)");
        for (Map<String, Object> r : list(d.get("desglose_temporal"))) {
            tmp.row(null, c(r.get("periodo")), c(echo(r.get("facturas")), "text-right"), n(r.get("facturas_monto")), c(echo(r.get("boletas")), "text-right"),
                    n(r.get("boletas_monto")), c(echo(r.get("nc")), "text-right"), c(echo(r.get("nd")), "text-right"), n(r.get("neto")), n(r.get("igv")));
        }
        b.add(tmp.block());
        List<Map<String, Object>> suc = list(d.get("desglose_por_sucursal"));
        if (suc.size() > 1) {
            Table s = new Table("Desglose por Sucursal").head("Sucursal", "r:Facturas", "r:Boletas", "r:NC", "r:ND", "r:Neto (S/)");
            for (Map<String, Object> r : suc) {
                s.row(null, c(r.get("sucursal") + " (" + r.get("cod_local") + ")"), c(echo(r.get("facturas")), "text-right"),
                        c(echo(r.get("boletas")), "text-right"), c(echo(r.get("nc")), "text-right"), c(echo(r.get("nd")), "text-right"), n(r.get("monto")));
            }
            b.add(s.block());
        }
        List<Map<String, Object>> cli = list(d.get("top_clientes"));
        if (!cli.isEmpty()) {
            Table s = new Table("Top 10 Clientes por Monto").head("Nro@5", "RUC/DNI@15", "Razón Social@40", "r:Docs@15", "r:Monto (S/)@25");
            int i = 1;
            for (Map<String, Object> r : cli) {
                s.row(null, c(String.valueOf(i++), "text-center"), c(r.get("num_doc")), c(r.get("razon_social")), c(echo(r.get("cantidad_docs")), "text-right"),
                        n(r.get("monto_bruto")));
            }
            b.add(s.block());
        }
        List<Map<String, Object>> prod = list(d.get("top_productos"));
        if (!prod.isEmpty()) {
            Table s = new Table("Top 10 Productos").head("Nro@5", "Descripción@55", "r:Cantidad@15", "r:Monto (S/)@25");
            int i = 1;
            for (Map<String, Object> r : prod) {
                s.row(null, c(String.valueOf(i++), "text-center"), c(r.get("descripcion")), n(r.get("cantidad")), n(r.get("monto")));
            }
            b.add(s.block());
        }
        List<Map<String, Object>> mon = list(d.get("desglose_por_moneda"));
        if (mon.size() > 1) {
            Table s = new Table("Desglose por Moneda").head("Moneda", "r:Cantidad", "r:Monto");
            for (Map<String, Object> r : mon) {
                s.row(null, c(r.get("moneda")), c(echo(r.get("cantidad")), "text-right"), n(r.get("monto")));
            }
            b.add(s.block());
        }
        return b;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> notas(Map<String, Object> d) {
        Map<String, Object> k = (Map<String, Object>) d.get("kpis");
        List<Map<String, Object>> b = new ArrayList<>();
        b.add(kpis(kpi("S/ " + nf(k.get("total_nc")), "Total NC (" + echo(k.get("cantidad_nc")) + ")", null),
                kpi("S/ " + nf(k.get("total_nd")), "Total ND (" + echo(k.get("cantidad_nd")) + ")", null),
                kpi("S/ " + nf(k.get("nc_netas")), "NC Netas (NC-ND)", null), kpi("S/ " + nf(k.get("igv_revertido")), "IGV Revertido", null)));
        List<Map<String, Object>> mot = list(d.get("resumen_por_motivo"));
        if (!mot.isEmpty()) {
            Table t = new Table("Resumen por Motivo (NC)").head("Código@8", "Motivo@30", "r:Cantidad@12", "r:Monto Total@18", "r:IGV Revertido@16", "r:Base Gravada@16");
            for (Map<String, Object> r : mot) {
                t.row(null, c(r.get("cod_motivo"), "text-center"), c(r.get("des_motivo")), c(echo(r.get("cantidad")), "text-right"), n(r.get("monto_total")),
                        n(r.get("igv_revertido")), n(r.get("base_gravada_reducida")));
            }
            b.add(t.block());
        }
        for (String[] s : new String[][] {{"notas_credito", "Notas de Crédito"}, {"notas_debito", "Notas de Débito"}}) {
            List<Map<String, Object>> notas = list(d.get(s[0]));
            if (notas.isEmpty()) {
                continue;
            }
            Table t = new Table(s[1]).head("Nro", "F.Emisión", "Número", "Doc.Afectado", "Motivo", "RUC/DNI", "Razón Social", "r:B.Gravada", "r:IGV",
                    "r:Total", "Estado");
            int i = 1;
            for (Map<String, Object> r : notas) {
                t.row(null, c(String.valueOf(i++), "text-center"), c(r.get("fecha_emision")), c(r.get("numero_completo")), c(r.get("doc_afectado")),
                        c(r.get("cod_motivo") + " - " + limit(r.get("des_motivo"), 20)), c(r.get("client_num_doc")), c(limit(r.get("client_razon_social"), 20)),
                        n(r.get("mto_oper_gravadas")), n(r.get("mto_igv")), n(r.get("mto_imp_venta")), estadoSunat(r.get("estado_sunat")));
            }
            b.add(t.block());
        }
        return b;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cobranzas(Map<String, Object> d) {
        Map<String, Object> k = (Map<String, Object>) d.get("kpis");
        List<Map<String, Object>> b = new ArrayList<>();
        b.add(kpis(kpi("S/ " + nf(k.get("total_facturado")), "Total Facturado", null), kpi("S/ " + nf(k.get("total_cobrado")), "Total Cobrado", "kpi-highlight"),
                kpi("S/ " + nf(k.get("total_pendiente")), "Total Pendiente", "kpi-danger"), kpi(echo(k.get("porcentaje_cobranza")) + "%", "% Cobranza", null)));
        b.add(aging("Antigüedad de Deuda (Aging)", (Map<String, Object>) d.get("aging")));
        List<Map<String, Object>> met = list(d.get("resumen_por_metodo"));
        if (!met.isEmpty()) {
            Table t = new Table("Resumen por Método de Pago").head("Método", "r:Cantidad", "r:Monto (S/)");
            for (Map<String, Object> r : met) {
                t.row(null, c(ucfirst(Php.toStr(r.get("metodo")))), c(echo(r.get("cantidad")), "text-right"), n(r.get("monto")));
            }
            b.add(t.block());
        }
        Table cli = new Table("Resumen por Cliente (Top Deudores)").head("RUC/DNI", "Razón Social", "r:Facturado (S/)", "r:Cobrado (S/)", "r:Pendiente (S/)");
        List<Map<String, Object>> clientes = list(d.get("resumen_por_cliente"));
        for (Map<String, Object> r : clientes.subList(0, Math.min(20, clientes.size()))) {
            cli.row(null, c(r.get("num_doc")), c(limit(r.get("razon_social"), 35)), n(r.get("total_facturado")), n(r.get("total_cobrado")),
                    n(r.get("total_pendiente")));
        }
        b.add(cli.block());
        Table det = new Table("Detalle de Documentos").head("Tipo", "Número", "Cliente", "F.Emisión", "F.Venc.", "r:Total", "r:Cobrado", "r:Pendiente",
                "r:Días Atraso", "Estado");
        for (Map<String, Object> r : list(d.get("detalle"))) {
            det.row(null, c(r.get("tipo"), "text-center"), c(r.get("numero_completo")), c(limit(r.get("client_razon_social"), 20)), c(r.get("fecha_emision")),
                    c(r.get("fecha_vencimiento") == null ? "-" : r.get("fecha_vencimiento")), n(r.get("total")), n(r.get("cobrado")), n(r.get("pendiente")),
                    c(PanelController.dbl(r.get("dias_atraso")) > 0 ? echo(r.get("dias_atraso")) : "-", "text-right"), estadoPago(r.get("estado_pago"), "Pendiente"));
        }
        b.add(det.block());
        return b;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> porCliente(Map<String, Object> d) {
        Map<String, Object> c = (Map<String, Object>) d.get("cliente");
        Map<String, Object> r = (Map<String, Object>) d.get("resumen");
        List<Map<String, Object>> b = new ArrayList<>();
        List<String> izq = new ArrayList<>();
        izq.add(Php.toStr(c.getOrDefault("razon_social", "N/A")));
        izq.add(Php.toStr(c.getOrDefault("tipo_documento", "")) + " " + Php.toStr(c.getOrDefault("numero_documento", "")));
        izq.add(Php.toStr(c.getOrDefault("direccion", "")));
        List<String> der = new ArrayList<>();
        if (!Php.empty(c.get("email"))) {
            der.add("Email: " + c.get("email"));
        }
        if (!Php.empty(c.get("telefono"))) {
            der.add("Tel: " + c.get("telefono"));
        }
        if (!Php.empty(c.get("nombre_comercial"))) {
            der.add("N. Comercial: " + c.get("nombre_comercial"));
        }
        b.add(Reports.map("type", "info", "title", "Datos del Cliente", "left", izq, "right", der));
        b.add(kpis(kpi("S/ " + nf(r.get("total_facturado")), "Total Facturado", null), kpi("S/ " + nf(r.get("neto")), "Neto", null),
                kpi("S/ " + nf(r.get("cobrado")), "Cobrado", null), kpi("S/ " + nf(r.get("pendiente")), "Pendiente", null)));
        b.add(new Table(null).style("width: 50%; margin-bottom: 8px;").row(null, strong("NC emitidas:", "S/ " + nf(r.get("nc_emitidas"))),
                strong("ND emitidas:", "S/ " + nf(r.get("nd_emitidas")))).block());
        Table det = new Table("Detalle de Documentos").head("Tipo", "Número", "F.Emisión", "F.Venc.", "r:Total", "Mon.", "SUNAT", "Pago", "r:Pagado");
        for (Map<String, Object> x : list(d.get("detalle_documentos"))) {
            Object pago = x.get("estado_pago");
            det.row(null, c(x.get("tipo_nombre")), c(x.get("numero_completo")), c(x.get("fecha_emision")),
                    c(x.get("fecha_vencimiento") == null ? "-" : x.get("fecha_vencimiento")), n(x.get("mto_imp_venta")), c(x.get("tipo_moneda"), "text-center"),
                    x.get("estado_sunat") == null || Php.empty(x.get("estado_sunat")) ? c("-", "text-center") : estadoSunat(x.get("estado_sunat")),
                    pago == null || Php.empty(pago) ? c("-", "text-center") : estadoPago(pago, "Pend."), n(x.get("monto_pagado")));
        }
        b.add(det.block());
        List<Map<String, Object>> pagos = list(d.get("historial_pagos"));
        if (!pagos.isEmpty()) {
            Table t = new Table("Historial de Pagos").head("Fecha", "Método", "r:Monto (S/)", "Referencia", "Notas");
            for (Map<String, Object> p : pagos) {
                t.row(null, c(p.get("fecha")), c(ucfirst(Php.toStr(p.get("metodo")))), n(p.get("monto")), c(p.get("referencia") == null ? "-" : p.get("referencia")),
                        c(limit(p.get("notas") == null ? "-" : p.get("notas"), 30)));
            }
            b.add(t.block());
        }
        b.add(aging("Antigüedad de Deuda", (Map<String, Object>) d.get("aging")));
        return b;
    }

    private List<Map<String, Object>> porSucursal(Map<String, Object> d) {
        List<Map<String, Object>> comp = list(d.get("comparativo"));
        Table t = new Table("Comparativo por Sucursal").head("Sucursal", "Cod.Local", "r:Fact. (Cant)", "r:Fact. (S/)", "r:Bol. (Cant)", "r:Bol. (S/)",
                "r:NC (Cant)", "r:NC (S/)", "r:ND (Cant)", "r:ND (S/)", "r:Bruto", "r:Neto", "r:IGV", "r:Cobrado", "r:Pend.", "r:%");
        double[] tot = new double[13];
        for (Map<String, Object> s : comp) {
            String[] grupos = {"facturas", "boletas", "notas_credito", "notas_debito"};
            List<Map<String, Object>> cells = new ArrayList<>(List.of(c(limit(s.get("nombre"), 15)), c(s.get("cod_local"), "text-center")));
            for (int i = 0; i < grupos.length; i++) {
                Map<String, Object> g = sub(s, grupos[i]);
                cells.add(c(echo(g.get("cantidad")), "text-right"));
                cells.add(n(g.get("monto")));
                tot[i * 2] += PanelController.dbl(g.get("cantidad"));
                tot[i * 2 + 1] += PanelController.dbl(g.get("monto"));
            }
            String[] montos = {"total_bruto", "neto", "igv", "cobrado", "pendiente"};
            for (int i = 0; i < montos.length; i++) {
                cells.add(n(s.get(montos[i])));
                tot[8 + i] += PanelController.dbl(s.get(montos[i]));
            }
            cells.add(c(echo(s.get("porcentaje_total")) + "%", "text-right"));
            t.rowList(null, cells);
        }
        List<Map<String, Object>> total = new ArrayList<>(List.of(span("TOTAL:", 2, "text-right")));
        for (int i = 0; i < 8; i++) {
            total.add(i % 2 == 0 ? c(PhpMath.toStr(tot[i]), "text-right") : n(tot[i]));
        }
        total.add(n(tot[8]));
        total.add(n(d.get("gran_total")));
        for (int i = 10; i < 13; i++) {
            total.add(n(tot[i]));
        }
        total.add(c("100%", "text-right"));
        t.rowList("total", total);
        Table kpi = new Table("KPIs por Sucursal").head("Sucursal", "r:Ticket Promedio (S/)", "r:Docs/Día Promedio", "r:% del Total");
        for (Map<String, Object> s : comp) {
            Map<String, Object> pct = c(echo(s.get("porcentaje_total")) + "%", "text-right");
            pct.put("bar", Reports.map("cls", "aging-green", "width", echo(s.get("porcentaje_total"))));
            kpi.row(null, c(s.get("nombre")), n(s.get("ticket_promedio")), c(echo(s.get("docs_dia_promedio")), "text-right"), pct);
        }
        return List.of(t.block(), kpi.block());
    }

    // ------------------------------------------------------------------ piezas

    private static Map<String, Object> aging(String title, Map<String, Object> aging) {
        Table t = new Table(title).head("Rango (días)", "r:Monto Pendiente (S/)", "Proporción@50");
        double max = 0;
        for (Object v : aging.values()) {
            max = Math.max(max, PanelController.dbl(v));
        }
        for (Map.Entry<String, Object> e : aging.entrySet()) {
            Map<String, Object> bar = c("");
            if (max > 0) {
                double w = Math.max(2, PanelController.dbl(e.getValue()) / max * 100);
                bar.put("bar", Reports.map("cls", AGING_COLORS.getOrDefault(e.getKey(), "aging-green"), "width", PhpMath.toStr(w)));
            }
            t.row(null, c(e.getKey()), n(e.getValue()), bar);
        }
        return t.block();
    }

    private static Map<String, Object> estadoSunat(Object estado) {
        String s = Php.toStr(estado);
        Map<String, Object> cell = c("", "text-center");
        if ("aceptado".equals(s)) {
            cell.put("badge", Reports.map("cls", "badge-success", "text", "Acept."));
        } else if ("rechazado".equals(s)) {
            cell.put("badge", Reports.map("cls", "badge-danger", "text", "Rech."));
        } else {
            cell.put("badge", Reports.map("cls", "badge-warning", "text", ucfirst(s.length() > 5 ? s.substring(0, 5) : s)));
        }
        return cell;
    }

    private static Map<String, Object> estadoPago(Object estado, String pendiente) {
        String s = Php.toStr(estado);
        Map<String, Object> cell = c("", "text-center");
        if ("pagado".equals(s)) {
            cell.put("badge", Reports.map("cls", "badge-success", "text", "Pagado"));
        } else if ("parcial".equals(s)) {
            cell.put("badge", Reports.map("cls", "badge-warning", "text", "Parcial"));
        } else {
            cell.put("badge", Reports.map("cls", "badge-danger", "text", pendiente));
        }
        return cell;
    }

    private static Map<String, Object> kpis(Map<String, Object>... items) {
        return Reports.map("type", "kpis", "items", List.of(items));
    }

    private static Map<String, Object> kpi(String value, String label, String cls) {
        return Reports.map("value", value, "label", label, "cls", cls);
    }

    private static Map<String, Object> text(String text, String style) {
        return Reports.map("type", "text", "text", text, "style", style);
    }

    private static Map<String, Object> strong(String label, String value) {
        Map<String, Object> cell = c(" " + value);
        cell.put("strong", label);
        return cell;
    }

    static Map<String, Object> c(Object text) {
        return c(text, null);
    }

    static Map<String, Object> c(Object text, String cls) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text == null ? "" : String.valueOf(text));
        m.put("cls", cls);
        m.put("colspan", 1);
        return m;
    }

    private static Map<String, Object> span(String text, int colspan, String cls) {
        Map<String, Object> m = c(text, cls);
        m.put("colspan", colspan);
        return m;
    }

    /** Importe con {@code number_format($v, 2)} alineado a la derecha. */
    private static Map<String, Object> n(Object v) {
        return c(nf(v), "text-right");
    }

    @SafeVarargs
    private static Map<String, Object>[] cells(Map<String, Object>... c) {
        return c;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> m, String k) {
        return (Map<String, Object>) m.get(k);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object v) {
        return v == null ? List.of() : (List<Map<String, Object>>) v;
    }

    /** {@code number_format($v, 2)}: miles con coma, decimales con punto. */
    static String nf(Object v) {
        return PhpMath.numberFormatGrouped(PanelController.dbl(v), 2);
    }

    /** {@code {{ $v }}} de Blade para números (PHP imprime 100.0 como "100"). */
    static String echo(Object v) {
        if (v instanceof Double || v instanceof Float) {
            return PhpMath.toStr(((Number) v).doubleValue());
        }
        return v == null ? "" : String.valueOf(v);
    }

    /** {@code Str::limit($v, $n)}: corta a n caracteres, recorta espacios finales y agrega "...". */
    static String limit(Object v, int n) {
        String s = v == null ? "" : String.valueOf(v);
        if (s.codePointCount(0, s.length()) <= n) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, n)).stripTrailing() + "...";
    }

    static String ucfirst(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String periodo(Object p) {
        if (!(p instanceof Map<?, ?> m) || "-".equals(m.get("desde"))) {
            return null;
        }
        LocalDate d = Reports.parseDate(Php.toStr(m.get("desde")));
        LocalDate h = Reports.parseDate(Php.toStr(m.get("hasta")));
        return d == null || h == null ? null : d.format(DMY) + " — " + h.format(DMY);
    }

    /** Tabla de la plantilla: título de sección, cabeceras y filas de celdas. */
    static final class Table {
        final Map<String, Object> block = new LinkedHashMap<>();
        final List<Map<String, Object>> headers = new ArrayList<>();
        final List<Map<String, Object>> rows = new ArrayList<>();

        Table(String title) {
            block.put("type", "table");
            block.put("title", title);
            block.put("style", null);
            block.put("headers", headers);
            block.put("rows", rows);
        }

        /** Cabeceras; el prefijo "r:" alinea a la derecha y el sufijo "@8" fija el ancho en porcentaje. */
        Table head(String... h) {
            for (String x : h) {
                boolean right = x.startsWith("r:");
                String t = right ? x.substring(2) : x;
                int at = t.lastIndexOf('@');
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("text", at < 0 ? t : t.substring(0, at));
                m.put("cls", right ? "text-right" : null);
                m.put("style", at < 0 ? null : "width:" + t.substring(at + 1) + "%;");
                headers.add(m);
            }
            return this;
        }

        Table style(String s) {
            block.put("style", s);
            return this;
        }

        @SafeVarargs
        final Table row(String cls, Map<String, Object>... cells) {
            return rowList(cls, List.of(cells));
        }

        Table rowList(String cls, List<Map<String, Object>> cells) {
            rows.add(Reports.map("cls", cls, "cells", cells));
            return this;
        }

        Map<String, Object> block() {
            return block;
        }
    }
}
