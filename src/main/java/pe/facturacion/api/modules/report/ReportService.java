package pe.facturacion.api.modules.report;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.php.PhpMath;
import pe.facturacion.api.shared.time.Clock;

/**
 * {@code ReportService} de API-PRO: mismos datos, claves y orden (las colecciones de Laravel
 * conservan el orden de aparición y sus ordenamientos son estables).
 *
 * <p>Correcciones: el rango incluye el último día (API-PRO comparaba la fecha y hora de emisión
 * con la fecha sola y dejaba fuera ese día); los números de documento de los agrupados se
 * devuelven como texto (PHP los convertía en número y perdía los ceros a la izquierda); los días
 * de atraso son días enteros en America/Lima; las cobranzas no cuentan los pagos a vendedores de
 * las liquidaciones de compra; un estado de cuenta sin comprobantes no lista los pagos de todos
 * los clientes; no incluye cotizaciones ni notas de venta (fuera de alcance).
 */
@Service
public class ReportService {

    private final NamedParameterJdbcTemplate jdbc;

    public ReportService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Fila de comprobante con los tipos de su modelo Eloquent. */
    static final class Doc {
        final DocumentKind kind;
        final Map<String, Object> row;

        Doc(DocumentKind kind, Map<String, Object> row) {
            this.kind = kind;
            this.row = row;
        }

        long id() {
            return ((Number) row.get("id")).longValue();
        }

        Object get(String k) {
            return row.get(k);
        }

        double num(String k) {
            Object v = row.get(k);
            return v == null ? 0 : ((Number) v).doubleValue();
        }

        LocalDateTime emision() {
            return ((Timestamp) row.get("fecha_emision")).toLocalDateTime();
        }

        LocalDate vencimiento() {
            Object v = row.get("fecha_vencimiento");
            return v == null ? null : ((Date) v).toLocalDate();
        }

        String numeroCompleto() {
            return Reports.numero(row.get("serie"), row.get("correlativo"), 6);
        }
    }

    // ------------------------------------------------------------------ registro de ventas

    public Map<String, Object> registroVentas(Tenant t, Map<String, Object> f) {
        List<Doc> inv = documentos(DocumentKind.FACTURA, t, f, "sunat");
        List<Doc> bol = documentos(DocumentKind.BOLETA, t, f, "sunat");
        List<Doc> nc = documentos(DocumentKind.NOTA_CREDITO, t, f, "sunat");
        List<Doc> nd = documentos(DocumentKind.NOTA_DEBITO, t, f, "sunat");
        List<Doc> all = new ArrayList<>(inv);
        all.addAll(bol);
        all.addAll(nc);
        all.addAll(nd);
        all.sort(Comparator.comparing(Doc::emision));
        List<Map<String, Object>> filas = new ArrayList<>();
        for (Doc d : all) {
            filas.add(documentRow(d));
        }
        Map<String, Object> porTipo = new LinkedHashMap<>();
        porTipo.put("01", totales(inv));
        porTipo.put("03", totales(bol));
        porTipo.put("07", totales(nc));
        porTipo.put("08", totales(nd));
        double neta = Reports.round2(sumOf(inv, "mto_imp_venta") + sumOf(bol, "mto_imp_venta") + sumOf(nd, "mto_imp_venta")
                - sumOf(nc, "mto_imp_venta"));
        return Reports.map("titulo", "REGISTRO DE VENTAS", "periodo", periodo(f), "filtros", filtros(f), "documentos", filas,
                "totales_por_tipo", porTipo, "gran_total", totales(all), "venta_neta", neta, "total_documentos", (long) all.size());
    }

    // ------------------------------------------------------------------ ventas consolidado

    public Map<String, Object> ventasConsolidado(Tenant t, Map<String, Object> f) {
        String agrupacion = f.get("agrupacion") == null ? "mes" : String.valueOf(f.get("agrupacion"));
        List<Doc> inv = documentos(DocumentKind.FACTURA, t, f, "sunat");
        List<Doc> bol = documentos(DocumentKind.BOLETA, t, f, "sunat");
        List<Doc> nc = documentos(DocumentKind.NOTA_CREDITO, t, f, "sunat");
        List<Doc> nd = documentos(DocumentKind.NOTA_DEBITO, t, f, "sunat");
        double facturado = sumOf(inv, "mto_imp_venta") + sumOf(bol, "mto_imp_venta") + sumOf(nd, "mto_imp_venta");
        double totalNc = sumOf(nc, "mto_imp_venta");
        double igv = sumOf(inv, "mto_igv") + sumOf(bol, "mto_igv") + sumOf(nd, "mto_igv") - sumOf(nc, "mto_igv");
        long emitidos = inv.size() + bol.size() + nc.size() + nd.size();
        int ventas = inv.size() + bol.size();
        Object ticket = emitidos > 0 ? (Object) Reports.round2(facturado / (ventas == 0 ? 1 : ventas)) : 0L;
        double cobrado = sumOf(inv, "monto_pagado") + sumOf(bol, "monto_pagado");
        Object pctCobrado = facturado > 0 ? (Object) PhpMath.round(cobrado / facturado * 100, 1) : 0L;

        // Desglose temporal: claves ordenadas como ksort
        Map<String, List<Doc>> grupos = new TreeMap<>();
        for (List<Doc> l : List.of(inv, bol, nc, nd)) {
            for (Doc d : l) {
                LocalDate fecha = d.emision().toLocalDate();
                String k = switch (agrupacion) {
                    case "dia" -> fecha.toString();
                    case "semana" -> fecha.with(DayOfWeek.MONDAY).toString();
                    default -> fecha.toString().substring(0, 7);
                };
                grupos.computeIfAbsent(k, x -> new ArrayList<>()).add(d);
            }
        }
        List<Map<String, Object>> temporal = new ArrayList<>();
        grupos.forEach((periodo, docs) -> {
            Map<DocumentKind, List<Doc>> by = groupKinds(docs);
            double f01 = sumOf(by.get(DocumentKind.FACTURA), "mto_imp_venta");
            double f03 = sumOf(by.get(DocumentKind.BOLETA), "mto_imp_venta");
            double f07 = sumOf(by.get(DocumentKind.NOTA_CREDITO), "mto_imp_venta");
            double f08 = sumOf(by.get(DocumentKind.NOTA_DEBITO), "mto_imp_venta");
            temporal.add(Reports.map("periodo", periodo, "facturas", (long) by.get(DocumentKind.FACTURA).size(), "facturas_monto", Reports.round2(f01),
                    "boletas", (long) by.get(DocumentKind.BOLETA).size(), "boletas_monto", Reports.round2(f03),
                    "nc", (long) by.get(DocumentKind.NOTA_CREDITO).size(), "nc_monto", Reports.round2(f07),
                    "nd", (long) by.get(DocumentKind.NOTA_DEBITO).size(), "nd_monto", Reports.round2(f08),
                    "neto", Reports.round2(f01 + f03 + f08 - f07), "igv", Reports.round2(sumOf(docs, "mto_igv"))));
        });
        List<Map<String, Object>> porTipo = List.of(
                Reports.map("tipo", "01", "nombre", "Facturas", "cantidad", (long) inv.size(), "monto", Reports.round2(sumOf(inv, "mto_imp_venta"))),
                Reports.map("tipo", "03", "nombre", "Boletas", "cantidad", (long) bol.size(), "monto", Reports.round2(sumOf(bol, "mto_imp_venta"))),
                Reports.map("tipo", "07", "nombre", "Notas de Crédito", "cantidad", (long) nc.size(), "monto", Reports.round2(totalNc)),
                Reports.map("tipo", "08", "nombre", "Notas de Débito", "cantidad", (long) nd.size(), "monto", Reports.round2(sumOf(nd, "mto_imp_venta"))));
        List<Map<String, Object>> porSucursal = new ArrayList<>();
        for (Map<String, Object> s : sucursales(t)) {
            String cod = String.valueOf(s.get("cod_local"));
            List<Doc> fi = byLocal(inv, cod);
            List<Doc> fb = byLocal(bol, cod);
            List<Doc> fnc = byLocal(nc, cod);
            List<Doc> fnd = byLocal(nd, cod);
            porSucursal.add(Reports.map("sucursal", s.get("nombre"), "cod_local", cod, "facturas", (long) fi.size(), "boletas", (long) fb.size(),
                    "nc", (long) fnc.size(), "nd", (long) fnd.size(), "monto", Reports.round2(sumOf(fi, "mto_imp_venta") + sumOf(fb, "mto_imp_venta")
                            + sumOf(fnd, "mto_imp_venta") - sumOf(fnc, "mto_imp_venta"))));
        }
        List<Doc> ventasDocs = new ArrayList<>(inv);
        ventasDocs.addAll(bol);
        List<Map<String, Object>> topClientes = new ArrayList<>();
        groupBy(ventasDocs, d -> String.valueOf(d.get("client_num_doc"))).forEach((numDoc, docs) -> topClientes.add(Reports.map("num_doc", numDoc,
                "razon_social", docs.get(0).get("client_razon_social"), "cantidad_docs", (long) docs.size(),
                "monto_bruto", Reports.round2(sumOf(docs, "mto_imp_venta")))));
        sortDesc(topClientes, "monto_bruto");
        List<Map<String, Object>> porMoneda = new ArrayList<>();
        groupBy(ventasDocs, d -> String.valueOf(d.get("tipo_moneda"))).forEach((moneda, docs) -> porMoneda.add(Reports.map("moneda", moneda,
                "cantidad", (long) docs.size(), "monto", Reports.round2(sumOf(docs, "mto_imp_venta")))));

        Map<String, Object> kpis = Reports.map("total_facturado", Reports.round2(facturado), "total_igv", Reports.round2(igv), "docs_emitidos", emitidos,
                "ticket_promedio", ticket, "nc_emitidas", (long) nc.size(), "nd_emitidas", (long) nd.size(),
                "venta_neta", Reports.round2(facturado - totalNc), "total_cobrado", Reports.round2(cobrado), "porcentaje_cobrado", pctCobrado);
        return Reports.map("titulo", "VENTAS CONSOLIDADO", "periodo", periodo(f), "filtros", filtros(f), "kpis", kpis,
                "desglose_temporal", temporal, "desglose_por_tipo", porTipo, "desglose_por_sucursal", porSucursal,
                "top_clientes", topClientes.subList(0, Math.min(10, topClientes.size())), "top_productos", topProductos(t, f),
                "desglose_por_moneda", porMoneda);
    }

    // ------------------------------------------------------------------ notas

    public Map<String, Object> notas(Tenant t, Map<String, Object> f) {
        List<Map<String, Object>> nc = new ArrayList<>();
        for (Doc d : documentos(DocumentKind.NOTA_CREDITO, t, f, "sunat")) {
            nc.add(noteRow(d));
        }
        List<Map<String, Object>> nd = new ArrayList<>();
        for (Doc d : documentos(DocumentKind.NOTA_DEBITO, t, f, "sunat")) {
            nd.add(noteRow(d));
        }
        List<Map<String, Object>> motivos = new ArrayList<>();
        groupBy(nc, r -> String.valueOf(r.get("cod_motivo"))).forEach((cod, notas) -> motivos.add(Reports.map("cod_motivo", cod,
                "des_motivo", notas.get(0).get("des_motivo"), "cantidad", (long) notas.size(), "monto_total", Reports.round2(sumMaps(notas, "mto_imp_venta")),
                "igv_revertido", Reports.round2(sumMaps(notas, "mto_igv")), "base_gravada_reducida", Reports.round2(sumMaps(notas, "mto_oper_gravadas")))));
        sortDesc(motivos, "monto_total");
        double totalNc = Reports.round2(sumMaps(nc, "mto_imp_venta"));
        double totalNd = Reports.round2(sumMaps(nd, "mto_imp_venta"));
        Map<String, Object> kpis = Reports.map("total_nc", totalNc, "cantidad_nc", (long) nc.size(), "total_nd", totalNd, "cantidad_nd", (long) nd.size(),
                "nc_netas", Reports.round2(totalNc - totalNd), "igv_revertido", Reports.round2(sumMaps(nc, "mto_igv")),
                "base_gravada_reducida", Reports.round2(sumMaps(nc, "mto_oper_gravadas")));
        return Reports.map("titulo", "NOTAS DE CRÉDITO Y DÉBITO", "periodo", periodo(f), "filtros", filtros(f), "kpis", kpis,
                "notas_credito", nc, "notas_debito", nd, "resumen_por_motivo", motivos);
    }

    // ------------------------------------------------------------------ cobranzas

    public Map<String, Object> cobranzas(Tenant t, Map<String, Object> f) {
        boolean vencidos = Reports.filterBool(f.get("vencido"));
        List<Doc> docs = new ArrayList<>();
        for (DocumentKind k : List.of(DocumentKind.FACTURA, DocumentKind.BOLETA)) {
            List<Doc> l = documentos(k, t, f, "payment");
            if (vencidos) {
                LocalDate hoy = Clock.todayLima();
                l.removeIf(d -> d.vencimiento() == null || !d.vencimiento().isBefore(hoy) || "pagado".equals(d.get("payment_status")));
            }
            docs.addAll(l);
        }
        double facturado = Reports.round2(sumOf(docs, "mto_imp_venta"));
        double cobrado = Reports.round2(sumOf(docs, "monto_pagado"));
        LocalDate hoy = Clock.todayLima();
        List<Map<String, Object>> detalle = new ArrayList<>();
        for (Doc d : docs) {
            double pendiente = d.num("mto_imp_venta") - d.num("monto_pagado");
            long atraso = 0;
            if (d.vencimiento() != null && !d.vencimiento().isAfter(hoy) && pendiente > 0) {
                atraso = ChronoUnit.DAYS.between(d.vencimiento(), hoy);
            }
            detalle.add(Reports.map("id", d.id(), "tipo", d.kind.tipoDocumento, "numero_completo", d.numeroCompleto(),
                    "client_num_doc", d.get("client_num_doc"), "client_razon_social", d.get("client_razon_social"),
                    "fecha_emision", d.emision().toLocalDate().toString(), "fecha_vencimiento", d.vencimiento() == null ? null : d.vencimiento().toString(),
                    "tipo_moneda", d.get("tipo_moneda"), "total", d.num("mto_imp_venta"), "cobrado", d.num("monto_pagado"),
                    "pendiente", Reports.round2(pendiente), "dias_atraso", atraso, "estado_pago", d.get("payment_status")));
        }
        sortDesc(detalle, "pendiente");
        // Pagos recibidos por facturas y boletas (no los pagos a vendedores de las liquidaciones)
        List<Map<String, Object>> metodos = new ArrayList<>();
        List<Map<String, Object>> pagos = jdbc.queryForList("SELECT metodo, monto FROM payments WHERE tenant_id = :t AND payable_type IN (:tipos) "
                + "AND created_at BETWEEN :d AND :h ORDER BY id", new MapSqlParameterSource("t", t.getId())
                        .addValue("tipos", List.of(DocumentKind.FACTURA.payableType, DocumentKind.BOLETA.payableType))
                        .addValue("d", desde(f).atStartOfDay()).addValue("h", hasta(f).atTime(23, 59, 59)));
        groupBy(pagos, p -> String.valueOf(p.get("metodo"))).forEach((metodo, ps) -> metodos.add(Reports.map("metodo", metodo,
                "cantidad", (long) ps.size(), "monto", Reports.round2(sumMaps(ps, "monto")))));
        sortDesc(metodos, "monto");
        List<Map<String, Object>> clientes = new ArrayList<>();
        groupBy(docs, d -> String.valueOf(d.get("client_num_doc"))).forEach((numDoc, ds) -> clientes.add(Reports.map("num_doc", numDoc,
                "razon_social", ds.get(0).get("client_razon_social"), "total_facturado", Reports.round2(sumOf(ds, "mto_imp_venta")),
                "total_cobrado", Reports.round2(sumOf(ds, "monto_pagado")),
                "total_pendiente", Reports.round2(sumOf(ds, "mto_imp_venta") - sumOf(ds, "monto_pagado")))));
        sortDesc(clientes, "total_pendiente");
        Map<String, Object> kpis = Reports.map("total_facturado", facturado, "total_cobrado", cobrado,
                "total_pendiente", Reports.round2(facturado - cobrado),
                "porcentaje_cobranza", facturado > 0 ? (Object) PhpMath.round(cobrado / facturado * 100, 1) : 0L);
        return Reports.map("titulo", "COBRANZAS Y CUENTAS POR COBRAR", "periodo", periodo(f), "filtros", filtros(f), "kpis", kpis,
                "aging", aging(docs), "detalle", detalle, "resumen_por_metodo", metodos, "resumen_por_cliente", clientes);
    }

    // ------------------------------------------------------------------ estado de cuenta

    public Map<String, Object> porCliente(Tenant t, Map<String, Object> f) {
        String numDoc = String.valueOf(f.get("client_num_doc"));
        List<Map<String, Object>> cli = jdbc.queryForList("SELECT * FROM clients WHERE tenant_id = :t AND numero_documento = :n ORDER BY id LIMIT 1",
                new MapSqlParameterSource("t", t.getId()).addValue("n", numDoc));
        Map<String, Object> cliente;
        if (cli.isEmpty()) {
            cliente = Reports.map("numero_documento", numDoc, "razon_social", "No registrado");
        } else {
            Map<String, Object> c = cli.get(0);
            cliente = Reports.map("tipo_documento", c.get("tipo_documento"), "numero_documento", c.get("numero_documento"),
                    "razon_social", c.get("razon_social"), "nombre_comercial", c.get("nombre_comercial"), "direccion", c.get("direccion"),
                    "email", c.get("email"), "telefono", c.get("telefono"));
        }
        Map<DocumentKind, List<Doc>> docs = new LinkedHashMap<>();
        for (DocumentKind k : DocumentKind.values()) {
            docs.put(k, query(k, t, "client_num_doc = :n AND fecha_emision BETWEEN :d AND :h", " ORDER BY fecha_emision, id",
                    rango(t, f).addValue("n", numDoc)));
        }
        List<Doc> inv = docs.get(DocumentKind.FACTURA);
        List<Doc> bol = docs.get(DocumentKind.BOLETA);
        double facturado = Reports.round2(sumOf(inv, "mto_imp_venta") + sumOf(bol, "mto_imp_venta"));
        double totalNc = Reports.round2(sumOf(docs.get(DocumentKind.NOTA_CREDITO), "mto_imp_venta"));
        double totalNd = Reports.round2(sumOf(docs.get(DocumentKind.NOTA_DEBITO), "mto_imp_venta"));
        double neto = Reports.round2(facturado - totalNc + totalNd);
        double cobrado = Reports.round2(sumOf(inv, "monto_pagado") + sumOf(bol, "monto_pagado"));
        Map<DocumentKind, String> nombres = Map.of(DocumentKind.FACTURA, "Factura", DocumentKind.BOLETA, "Boleta",
                DocumentKind.NOTA_CREDITO, "Nota de Crédito", DocumentKind.NOTA_DEBITO, "Nota de Débito");
        List<Map<String, Object>> detalle = new ArrayList<>();
        docs.forEach((k, l) -> {
            for (Doc d : l) {
                detalle.add(Reports.map("tipo", k.tipoDocumento, "tipo_nombre", nombres.get(k), "numero_completo", d.numeroCompleto(),
                        "fecha_emision", d.emision().toLocalDate().toString(), "fecha_vencimiento", d.vencimiento() == null ? null : d.vencimiento().toString(),
                        "mto_imp_venta", d.num("mto_imp_venta"), "tipo_moneda", d.get("tipo_moneda"), "estado_sunat", d.get("sunat_status"),
                        "estado_pago", d.row.get("payment_status"), "monto_pagado", d.num("monto_pagado")));
            }
        });
        detalle.sort(Comparator.comparing(m -> String.valueOf(m.get("fecha_emision"))));
        List<Map<String, Object>> historial = new ArrayList<>();
        List<Long> invIds = inv.stream().map(Doc::id).toList();
        List<Long> bolIds = bol.stream().map(Doc::id).toList();
        if (!invIds.isEmpty() || !bolIds.isEmpty()) {
            for (Map<String, Object> p : jdbc.queryForList("SELECT * FROM payments WHERE tenant_id = :t AND ((payable_type = :fi AND payable_id IN (:inv)) "
                    + "OR (payable_type = :bo AND payable_id IN (:bol))) ORDER BY created_at, id", new MapSqlParameterSource("t", t.getId())
                            .addValue("fi", DocumentKind.FACTURA.payableType).addValue("inv", invIds.isEmpty() ? List.of(-1L) : invIds)
                            .addValue("bo", DocumentKind.BOLETA.payableType).addValue("bol", bolIds.isEmpty() ? List.of(-1L) : bolIds))) {
                LocalDateTime creado = ((Timestamp) p.get("created_at")).toLocalDateTime();
                historial.add(Reports.map("fecha", creado.format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")),
                        "metodo", p.get("metodo"), "monto", ((BigDecimal) p.get("monto")).doubleValue(), "referencia", p.get("referencia"),
                        "notas", p.get("notas")));
            }
        }
        List<Doc> conPago = new ArrayList<>(inv);
        conPago.addAll(bol);
        return Reports.map("titulo", "ESTADO DE CUENTA POR CLIENTE", "periodo", periodo(f), "cliente", cliente,
                "resumen", Reports.map("total_facturado", facturado, "nc_emitidas", totalNc, "nd_emitidas", totalNd, "neto", neto,
                        "cobrado", cobrado, "pendiente", Reports.round2(neto - cobrado)),
                "detalle_documentos", detalle, "historial_pagos", historial, "aging", aging(conPago));
    }

    // ------------------------------------------------------------------ comparativo por sucursal

    public Map<String, Object> porSucursal(Tenant t, Map<String, Object> f) {
        Object filtro = f.get("sucursal_id");
        LocalDate desde = desde(f);
        LocalDate hasta = hasta(f);
        long dias = Math.max(1, ChronoUnit.DAYS.between(desde, hasta) + 1);
        List<Map<String, Object>> comparativo = new ArrayList<>();
        for (Map<String, Object> s : sucursales(t)) {
            if (Php.truthy(filtro) && ((Number) s.get("id")).longValue() != Php.toInt(filtro)) {
                continue;
            }
            String cod = String.valueOf(s.get("cod_local"));
            Map<DocumentKind, List<Doc>> d = new LinkedHashMap<>();
            for (DocumentKind k : DocumentKind.values()) {
                d.put(k, query(k, t, "cod_local = :c AND fecha_emision BETWEEN :d AND :h", " ORDER BY id", rango(t, f).addValue("c", cod)));
            }
            List<Doc> inv = d.get(DocumentKind.FACTURA);
            List<Doc> bol = d.get(DocumentKind.BOLETA);
            List<Doc> nc = d.get(DocumentKind.NOTA_CREDITO);
            List<Doc> nd = d.get(DocumentKind.NOTA_DEBITO);
            double bruto = Reports.round2(sumOf(inv, "mto_imp_venta") + sumOf(bol, "mto_imp_venta") + sumOf(nd, "mto_imp_venta"));
            double totalNc = Reports.round2(sumOf(nc, "mto_imp_venta"));
            double neto = Reports.round2(bruto - totalNc);
            double cobrado = Reports.round2(sumOf(inv, "monto_pagado") + sumOf(bol, "monto_pagado"));
            int ventas = inv.size() + bol.size();
            long totalDocs = inv.size() + bol.size() + nc.size() + nd.size();
            comparativo.add(Reports.map("sucursal_id", ((Number) s.get("id")).longValue(), "nombre", s.get("nombre"), "cod_local", cod,
                    "facturas", Reports.map("cantidad", (long) inv.size(), "monto", Reports.round2(sumOf(inv, "mto_imp_venta"))),
                    "boletas", Reports.map("cantidad", (long) bol.size(), "monto", Reports.round2(sumOf(bol, "mto_imp_venta"))),
                    "notas_credito", Reports.map("cantidad", (long) nc.size(), "monto", totalNc),
                    "notas_debito", Reports.map("cantidad", (long) nd.size(), "monto", Reports.round2(sumOf(nd, "mto_imp_venta"))),
                    "total_bruto", bruto, "neto", neto,
                    "igv", Reports.round2(sumOf(inv, "mto_igv") + sumOf(bol, "mto_igv") + sumOf(nd, "mto_igv") - sumOf(nc, "mto_igv")),
                    "cobrado", cobrado, "pendiente", Reports.round2(neto - cobrado),
                    "ticket_promedio", ventas > 0 ? (Object) Reports.round2(bruto / ventas) : 0L,
                    "docs_dia_promedio", PhpMath.round((double) totalDocs / dias, 1)));
        }
        double gran = sumMaps(comparativo, "neto");
        for (Map<String, Object> s : comparativo) {
            s.put("porcentaje_total", gran > 0 ? (Object) PhpMath.round(((Number) s.get("neto")).doubleValue() / gran * 100, 1) : 0L);
        }
        return Reports.map("titulo", "COMPARATIVO POR SUCURSAL", "periodo", periodo(f), "filtros", filtros(f), "comparativo", comparativo,
                "gran_total", Reports.round2(gran));
    }

    // ------------------------------------------------------------------ apoyo

    /** {@code queryDocuments}: filtros comunes, orden por fecha de emisión, serie y correlativo. */
    List<Doc> documentos(DocumentKind k, Tenant t, Map<String, Object> f, String modo) {
        StringBuilder w = new StringBuilder("fecha_emision BETWEEN :d AND :h");
        MapSqlParameterSource p = rango(t, f);
        if (Php.truthy(f.get("sucursal_id"))) {
            List<String> cod = jdbc.queryForList("SELECT cod_local FROM sucursales WHERE id = :s AND tenant_id = :t",
                    new MapSqlParameterSource("s", Php.toInt(f.get("sucursal_id"))).addValue("t", t.getId()), String.class);
            if (!cod.isEmpty()) {
                w.append(" AND cod_local = :cod");
                p.addValue("cod", cod.get(0));
            }
        }
        for (String[] c : new String[][] {{"serie", "serie"}, {"client_num_doc", "client_num_doc"}, {"tipo_moneda", "tipo_moneda"}}) {
            if (Php.truthy(f.get(c[0]))) {
                w.append(" AND ").append(c[1]).append(" = :").append(c[0]);
                p.addValue(c[0], Php.toStr(f.get(c[0])));
            }
        }
        if ("sunat".equals(modo) && Php.truthy(f.get("estado_sunat"))) {
            w.append(" AND sunat_status = :es");
            p.addValue("es", Php.toStr(f.get("estado_sunat")));
        }
        if ("payment".equals(modo) && Php.truthy(f.get("estado_pago"))) {
            w.append(" AND payment_status = :ep");
            p.addValue("ep", Php.toStr(f.get("estado_pago")));
        } else if ("payment".equals(modo)) {
            // Corrección: anulados por NC de anulación total no son cuentas por cobrar
            w.append(" AND payment_status <> 'anulado'");
        }
        return query(k, t, w.toString(), " ORDER BY fecha_emision, serie COLLATE \"C\", correlativo", p);
    }

    private List<Doc> query(DocumentKind k, Tenant t, String where, String order, MapSqlParameterSource p) {
        List<Doc> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM " + k.table + " WHERE tenant_id = :t AND deleted_at IS NULL AND " + where + order,
                p.addValue("t", t.getId()))) {
            out.add(new Doc(k, r));
        }
        return out;
    }

    private MapSqlParameterSource rango(Tenant t, Map<String, Object> f) {
        return new MapSqlParameterSource("t", t.getId()).addValue("d", desde(f).atStartOfDay()).addValue("h", hasta(f).atTime(23, 59, 59));
    }

    static LocalDate desde(Map<String, Object> f) {
        return Reports.parseDate(Php.toStr(f.get("fecha_desde")));
    }

    static LocalDate hasta(Map<String, Object> f) {
        return Reports.parseDate(Php.toStr(f.get("fecha_hasta")));
    }

    private List<Map<String, Object>> sucursales(Tenant t) {
        return jdbc.queryForList("SELECT id, nombre, cod_local FROM sucursales WHERE tenant_id = :t AND is_active = true ORDER BY id",
                new MapSqlParameterSource("t", t.getId()));
    }

    /** {@code topProductos}: los 10 más vendidos por monto (facturas y boletas, sin filtro de estado). */
    private List<Map<String, Object>> topProductos(Tenant t, Map<String, Object> f) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT descripcion, SUM(cantidad) AS total_cantidad, SUM(cantidad * mto_precio_unitario) AS total_monto "
                + "FROM (" + PanelController.ITEMS + ") c GROUP BY descripcion ORDER BY total_monto DESC, descripcion COLLATE \"C\" LIMIT 10", rango(t, f))) {
            out.add(Reports.map("descripcion", r.get("descripcion"), "cantidad", PanelController.dbl(r.get("total_cantidad")),
                    "monto", Reports.round2(PanelController.dbl(r.get("total_monto")))));
        }
        return out;
    }

    private static Map<String, Object> documentRow(Doc d) {
        return Reports.map("id", d.id(), "fecha_emision", d.emision().toLocalDate().toString(),
                "fecha_vencimiento", d.vencimiento() == null ? null : d.vencimiento().toString(), "tipo_doc", d.kind.tipoDocumento,
                "tipo_doc_nombre", switch (d.kind) {
                    case FACTURA -> "FACTURA";
                    case BOLETA -> "BOLETA";
                    case NOTA_CREDITO -> "NOTA DE CRÉDITO";
                    case NOTA_DEBITO -> "NOTA DE DÉBITO";
                }, "serie", d.get("serie"), "correlativo", ((Number) d.get("correlativo")).longValue(), "numero_completo", d.numeroCompleto(),
                "client_tipo_doc", d.get("client_tipo_doc"), "client_num_doc", d.get("client_num_doc"), "client_razon_social", d.get("client_razon_social"),
                "mto_oper_gravadas", d.num("mto_oper_gravadas"), "mto_oper_exoneradas", d.num("mto_oper_exoneradas"),
                "mto_oper_inafectas", d.num("mto_oper_inafectas"), "mto_oper_gratuitas", d.num("mto_oper_gratuitas"), "mto_igv", d.num("mto_igv"),
                "mto_isc", d.num("mto_isc"), "mto_icbper", d.num("mto_icbper"), "otros_tributos", 0L, "mto_imp_venta", d.num("mto_imp_venta"),
                "tipo_moneda", d.get("tipo_moneda"), "estado_sunat", d.get("sunat_status"));
    }

    private static Map<String, Object> noteRow(Doc d) {
        Object serie = d.get("doc_afectado_serie");
        Object corr = d.get("doc_afectado_correlativo");
        return Reports.map("id", d.id(), "serie", d.get("serie"), "correlativo", ((Number) d.get("correlativo")).longValue(),
                "numero_completo", d.numeroCompleto(), "fecha_emision", d.emision().toLocalDate().toString(),
                "tipo_doc_afectado", d.get("doc_afectado_tipo"), "serie_afectado", serie, "correlativo_afectado", corr,
                "doc_afectado", (serie == null ? "" : serie) + "-" + (corr == null ? "" : corr), "cod_motivo", d.get("cod_motivo"),
                "des_motivo", d.get("des_motivo"), "client_num_doc", d.get("client_num_doc"), "client_razon_social", d.get("client_razon_social"),
                "mto_oper_gravadas", d.num("mto_oper_gravadas"), "mto_igv", d.num("mto_igv"), "mto_imp_venta", d.num("mto_imp_venta"),
                "tipo_moneda", d.get("tipo_moneda"), "estado_sunat", d.get("sunat_status"));
    }

    private static Map<String, Object> totales(List<Doc> docs) {
        return Reports.map("cantidad", (long) docs.size(), "mto_oper_gravadas", Reports.round2(sumOf(docs, "mto_oper_gravadas")),
                "mto_oper_exoneradas", Reports.round2(sumOf(docs, "mto_oper_exoneradas")), "mto_oper_inafectas", Reports.round2(sumOf(docs, "mto_oper_inafectas")),
                "mto_oper_gratuitas", Reports.round2(sumOf(docs, "mto_oper_gratuitas")), "mto_igv", Reports.round2(sumOf(docs, "mto_igv")),
                "mto_isc", Reports.round2(sumOf(docs, "mto_isc")), "mto_icbper", Reports.round2(sumOf(docs, "mto_icbper")),
                "mto_imp_venta", Reports.round2(sumOf(docs, "mto_imp_venta")));
    }

    /** Antigüedad del saldo por días desde el vencimiento (o la emisión), en días enteros. */
    private static Map<String, Object> aging(List<Doc> docs) {
        double[] r = new double[6];
        LocalDate hoy = Clock.todayLima();
        for (Doc d : docs) {
            double pendiente = d.num("mto_imp_venta") - d.num("monto_pagado");
            if (pendiente <= 0) {
                continue;
            }
            LocalDate ref = d.vencimiento() != null ? d.vencimiento() : d.emision().toLocalDate();
            long dias = Math.max(0, ChronoUnit.DAYS.between(ref, hoy));
            int i = dias <= 15 ? 0 : dias <= 30 ? 1 : dias <= 60 ? 2 : dias <= 90 ? 3 : dias <= 120 ? 4 : 5;
            r[i] += pendiente;
        }
        return Reports.map("0-15", Reports.round2(r[0]), "16-30", Reports.round2(r[1]), "31-60", Reports.round2(r[2]), "61-90", Reports.round2(r[3]),
                "91-120", Reports.round2(r[4]), "120+", Reports.round2(r[5]));
    }

    private static Map<String, Object> periodo(Map<String, Object> f) {
        return Reports.map("desde", f.get("fecha_desde"), "hasta", f.get("fecha_hasta"));
    }

    /** {@code filtrosAplicados}: los filtros no vacíos (semántica de {@code empty()}), en orden fijo. */
    static List<Map<String, Object>> filtros(Map<String, Object> f) {
        String[][] labels = {{"sucursal_id", "Sucursal"}, {"serie", "Serie"}, {"client_num_doc", "Cliente"}, {"estado_sunat", "Estado SUNAT"},
            {"estado_pago", "Estado Pago"}, {"tipo_moneda", "Moneda"}, {"agrupacion", "Agrupación"}, {"vencido", "Solo vencidos"}};
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] l : labels) {
            if (!Php.empty(f.get(l[0]))) {
                out.add(Reports.map("filtro", l[1], "valor", f.get(l[0])));
            }
        }
        return out;
    }

    private static Map<DocumentKind, List<Doc>> groupKinds(List<Doc> docs) {
        Map<DocumentKind, List<Doc>> m = new LinkedHashMap<>();
        for (DocumentKind k : DocumentKind.values()) {
            m.put(k, new ArrayList<>());
        }
        for (Doc d : docs) {
            m.get(d.kind).add(d);
        }
        return m;
    }

    private static List<Doc> byLocal(List<Doc> docs, String cod) {
        return docs.stream().filter(d -> cod.equals(d.get("cod_local"))).toList();
    }

    /** {@code groupBy}: grupos en orden de primera aparición. */
    static <T> Map<String, List<T>> groupBy(List<T> items, Function<T, String> key) {
        Map<String, List<T>> m = new LinkedHashMap<>();
        for (T i : items) {
            m.computeIfAbsent(key.apply(i), k -> new ArrayList<>()).add(i);
        }
        return m;
    }

    /** {@code sortByDesc}: estable, como el ordenamiento de PHP 8. */
    static void sortDesc(List<Map<String, Object>> rows, String key) {
        rows.sort((a, b) -> Double.compare(PanelController.dbl(b.get(key)), PanelController.dbl(a.get(key))));
    }

    static double sumOf(List<Doc> docs, String k) {
        double s = 0;
        for (Doc d : docs) {
            s += d.num(k);
        }
        return s;
    }

    static double sumMaps(List<Map<String, Object>> rows, String k) {
        double s = 0;
        for (Map<String, Object> r : rows) {
            s += PanelController.dbl(r.get(k));
        }
        return s;
    }
}
