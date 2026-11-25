package pe.facturacion.api.modules.report;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.php.PhpMath;
import pe.facturacion.api.shared.time.Clock;

/**
 * Panel ({@code DashboardController} de API-PRO): mismas consultas, claves y orden.
 *
 * <p>Correcciones: "hoy", "semana" y "mes" se calculan en America/Lima (API-PRO usaba UTC); el
 * mes anterior de {@code /panel} incluye su último día; los meses se restan sin desbordarse (en
 * API-PRO, el 31 de un mes "un mes atrás" caía en el mes actual); las notas de venta (fuera de
 * alcance) se informan en cero; un periodo inválido responde 422 en vez de 500.
 */
@RestController
@RequestMapping("/api/v1/panel")
@Tag(name = "Panel")
public class PanelController {

    static final String VIVO = "deleted_at IS NULL";
    static final String VALIDO = "sunat_status NOT IN ('anulado', 'rechazado', 'anulacion_en_proceso')";
    static final String[] MESES = {"", "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre",
        "Octubre", "Noviembre", "Diciembre"};

    private final NamedParameterJdbcTemplate jdbc;

    public PanelController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Periodo(LocalDateTime desde, LocalDateTime hasta, String label) {
        Map<String, Object> json(boolean conLabel) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("desde", desde.format(Clock.Y_M_D_H_I_S));
            m.put("hasta", hasta.format(Clock.Y_M_D_H_I_S));
            if (conLabel) {
                m.put("label", label);
            }
            return m;
        }
    }

    // ------------------------------------------------------------------ endpoints

    @Operation(summary = "Panel del periodo (mes=YYYY-MM o desde/hasta)")
    @GetMapping({"", "/"})
    public ResponseEntity<Object> index(HttpServletRequest http) {
        Tenant t = ApiContext.requireTenant(http);
        return index(t, Input.of(http));
    }

    public ResponseEntity<Object> index(Tenant t, Input in) {
        Periodo p = periodo(in);
        if (p == null) {
            return periodoInvalido();
        }
        MapSqlParameterSource q = params(t, p.desde(), p.hasta());
        Map<String, Map<String, Object>> resumen = new LinkedHashMap<>();
        for (String[] tbl : new String[][] {{"facturas", "invoices"}, {"boletas", "boletas"}, {"notas_credito", "credit_notes"},
            {"notas_debito", "debit_notes"}}) {
            Map<String, Object> r = jdbc.queryForMap("SELECT count(*) AS cnt, COALESCE(sum(mto_imp_venta), 0) AS total FROM " + tbl[1]
                    + " WHERE tenant_id = :t AND fecha_emision BETWEEN :d AND :h AND " + VIVO, q);
            resumen.put(tbl[0], countTotal(lng(r.get("cnt")), Reports.round2(dbl(r.get("total")))));
        }
        LocalDate inicio = p.desde().toLocalDate().withDayOfMonth(1).minusMonths(1);
        MapSqlParameterSource prev = params(t, inicio.atStartOfDay(), YearMonth.from(inicio).atEndOfMonth().atTime(23, 59, 59));
        Map<String, Object> ant = jdbc.queryForMap("SELECT count(*) AS cnt, COALESCE(sum(mto_imp_venta), 0) AS total FROM ("
                + "SELECT mto_imp_venta FROM invoices WHERE tenant_id = :t AND fecha_emision BETWEEN :d AND :h AND " + VIVO
                + " UNION ALL SELECT mto_imp_venta FROM boletas WHERE tenant_id = :t AND fecha_emision BETWEEN :d AND :h AND " + VIVO
                + ") d", prev);
        double prevTotal = dbl(ant.get("total"));
        long prevDocs = lng(ant.get("cnt"));
        double currentTotal = dbl(resumen.get("facturas").get("total")) + dbl(resumen.get("boletas").get("total"));
        long currentDocs = lng(resumen.get("facturas").get("count")) + lng(resumen.get("boletas").get("count"));

        List<Map<String, Object>> grafico = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT day, COALESCE(sum(total), 0) AS ventas FROM ("
                + "SELECT to_char(fecha_emision, 'YYYY-MM-DD') AS day, mto_imp_venta AS total FROM invoices"
                + " WHERE tenant_id = :t AND fecha_emision BETWEEN :d AND :h AND " + VIVO
                + " UNION ALL SELECT to_char(fecha_emision, 'YYYY-MM-DD'), mto_imp_venta FROM boletas"
                + " WHERE tenant_id = :t AND fecha_emision BETWEEN :d AND :h AND " + VIVO + ") s GROUP BY day ORDER BY day", q)) {
            grafico.add(Reports.map("fecha", r.get("day"), "ventas", Reports.round2(dbl(r.get("ventas")))));
        }
        List<Map<String, Object>> productos = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT descripcion AS name, ROUND(SUM(cantidad * mto_precio_unitario), 2) AS total FROM ("
                + ITEMS + ") c GROUP BY descripcion ORDER BY total DESC, descripcion COLLATE \"C\" LIMIT 10", q)) {
            productos.add(Reports.map("nombre", Reports.mbSubstr(r.get("name"), 60), "total", dbl(r.get("total"))));
        }
        List<Map<String, Object>> clientes = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT client_razon_social AS name, ROUND(SUM(mto_imp_venta), 2) AS total, count(*) AS docs FROM ("
                + "SELECT client_razon_social, mto_imp_venta FROM invoices WHERE tenant_id = :t AND fecha_emision BETWEEN :d AND :h AND " + VIVO
                + " UNION ALL SELECT client_razon_social, mto_imp_venta FROM boletas WHERE tenant_id = :t AND fecha_emision BETWEEN :d AND :h AND "
                + VIVO + ") c GROUP BY client_razon_social ORDER BY total DESC, client_razon_social COLLATE \"C\" LIMIT 10", q)) {
            clientes.add(Reports.map("nombre", Reports.mbSubstr(r.get("name"), 60), "total", dbl(r.get("total")), "documentos", lng(r.get("docs"))));
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("periodo", p.json(true));
        resumen.forEach(d::put);
        d.put("notas_venta", countTotal(0, 0L));
        // Mismo orden de claves que API-PRO: notas_venta va después de notas_debito
        d.put("grafico", grafico);
        d.put("top_productos", productos);
        d.put("top_clientes", clientes);
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("ventas_actuales", Reports.round2(currentTotal));
        comp.put("ventas_anteriores", Reports.round2(prevTotal));
        comp.put("cambio_ventas_porcentaje", pctChange(currentTotal, prevTotal));
        comp.put("documentos_actuales", currentDocs);
        comp.put("documentos_anteriores", prevDocs);
        comp.put("cambio_documentos_porcentaje", pctChange(currentDocs, prevDocs));
        d.put("comparacion", comp);
        return ApiResponse.success(d);
    }

    @Operation(summary = "KPIs: hoy, ayer, semana, mes y año con crecimiento")
    @GetMapping("/indicadores")
    public ResponseEntity<Object> indicadores(HttpServletRequest http) {
        return indicadores(ApiContext.requireTenant(http));
    }

    public ResponseEntity<Object> indicadores(Tenant t) {
        long tid = t.getId();
        LocalDate hoy = Clock.todayLima();
        LocalDate mes = hoy.withDayOfMonth(1);
        LocalDate mesAnt = mes.minusMonths(1);
        Map<String, Object> kHoy = kpi(tid, hoy, hoy);
        Map<String, Object> kAyer = kpi(tid, hoy.minusDays(1), hoy.minusDays(1));
        Map<String, Object> kMes = kpi(tid, mes, hoy);
        Map<String, Object> kMesAnt = kpi(tid, mesAnt, YearMonth.from(mesAnt).atEndOfMonth());
        Map<String, Object> kAnio = kpi(tid, hoy.withDayOfYear(1), hoy);
        LocalDate anioAnt = hoy.withDayOfYear(1).minusYears(1);
        Map<String, Object> kAnioAnt = kpi(tid, anioAnt, anioAnt.withMonth(12).withDayOfMonth(31));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("hoy", kHoy);
        d.put("ayer", kAyer);
        d.put("semana", kpi(tid, hoy.with(DayOfWeek.MONDAY), hoy));
        d.put("mes_actual", kMes);
        d.put("mes_anterior", kMesAnt);
        d.put("anio_actual", kAnio);
        d.put("anio_anterior", kAnioAnt);
        d.put("crecimiento", Reports.map("vs_ayer", pctChange(dbl(kHoy.get("ventas")), dbl(kAyer.get("ventas"))),
                "vs_mes_anterior", pctChange(dbl(kMes.get("ventas")), dbl(kMesAnt.get("ventas"))),
                "vs_anio_anterior", pctChange(dbl(kAnio.get("ventas")), dbl(kAnioAnt.get("ventas")))));
        return ApiResponse.success(d);
    }

    @Operation(summary = "Estado SUNAT por estado y tipo, observados y últimos rechazos")
    @GetMapping("/estado-sunat")
    public ResponseEntity<Object> estadoSunat(HttpServletRequest http) {
        Tenant t = ApiContext.requireTenant(http);
        return estadoSunat(t, Input.of(http));
    }

    public ResponseEntity<Object> estadoSunat(Tenant t, Input in) {
        Periodo p = periodo(in);
        if (p == null) {
            return periodoInvalido();
        }
        MapSqlParameterSource q = params(t, p.desde(), p.hasta());
        StringBuilder sql = new StringBuilder("SELECT tipo, sunat_status AS estado, count(*) AS cnt FROM (");
        String[][] tablas = {{"facturas", "invoices"}, {"boletas", "boletas"}, {"notas_credito", "credit_notes"}, {"notas_debito", "debit_notes"}};
        for (int i = 0; i < tablas.length; i++) {
            sql.append(i == 0 ? "" : " UNION ALL ").append("SELECT '").append(tablas[i][0]).append("' AS tipo, sunat_status FROM ")
                    .append(tablas[i][1]).append(" WHERE tenant_id = :t AND fecha_emision BETWEEN :d AND :h AND ").append(VIVO);
        }
        sql.append(") docs GROUP BY tipo, sunat_status ORDER BY tipo COLLATE \"C\", sunat_status COLLATE \"C\"");
        Map<String, Map<String, Object>> porTipo = new LinkedHashMap<>();
        Map<String, Object> porEstado = new LinkedHashMap<>();
        for (String e : List.of("pendiente", "enviado", "aceptado", "rechazado", "anulado")) {
            porEstado.put(e, 0L);
        }
        long total = 0;
        for (Map<String, Object> r : jdbc.queryForList(sql.toString(), q)) {
            long n = lng(r.get("cnt"));
            String estado = String.valueOf(r.get("estado"));
            porTipo.computeIfAbsent(String.valueOf(r.get("tipo")), k -> new LinkedHashMap<>()).put(estado, n);
            porEstado.merge(estado, n, (a, b) -> lng(a) + lng(b));
            total += n;
        }
        String obs = " WHERE tenant_id = :t AND fecha_emision BETWEEN :d AND :h AND " + VIVO
                + " AND sunat_status = 'aceptado' AND sunat_code IS NOT NULL AND sunat_code <> '' AND sunat_code <> '0'";
        Long observados = jdbc.queryForObject("SELECT count(*) FROM (SELECT sunat_code FROM invoices" + obs
                + " UNION ALL SELECT sunat_code FROM boletas" + obs + ") o", q, Long.class);
        String[][] rech = {{"Factura", "invoices"}, {"Boleta", "boletas"}, {"Nota Crédito", "credit_notes"}, {"Nota Débito", "debit_notes"}};
        StringBuilder r = new StringBuilder("SELECT tipo, serie, correlativo, sunat_code, sunat_description, fecha_emision FROM (");
        for (int i = 0; i < rech.length; i++) {
            r.append(i == 0 ? "" : " UNION ALL ").append("SELECT ").append(i).append(" AS ord, id, '").append(rech[i][0])
                    .append("' AS tipo, serie, correlativo, sunat_code, sunat_description, fecha_emision FROM ").append(rech[i][1])
                    .append(" WHERE tenant_id = :t AND sunat_status = 'rechazado' AND ").append(VIVO);
        }
        r.append(") x ORDER BY fecha_emision DESC, ord, id LIMIT 10");
        List<Map<String, Object>> rechazos = new ArrayList<>();
        for (Map<String, Object> x : jdbc.queryForList(r.toString(), q)) {
            rechazos.add(Reports.map("tipo", x.get("tipo"), "numero", Reports.numero(x.get("serie"), x.get("correlativo"), 8),
                    "codigo_sunat", x.get("sunat_code"), "motivo", Reports.mbSubstr(x.get("sunat_description"), 200),
                    "fecha", Reports.dateTime(x.get("fecha_emision"))));
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("periodo", p.json(false));
        d.put("total_documentos", total);
        d.put("por_estado", porEstado);
        // PHP serializa el arreglo vacío como lista
        d.put("por_tipo", porTipo.isEmpty() ? List.of() : porTipo);
        d.put("observados", observados == null ? 0 : observados);
        d.put("tasa_aceptacion", total > 0 ? (Object) PhpMath.round(lng(porEstado.get("aceptado")) * 100.0 / total, 1) : 0L);
        d.put("ultimos_rechazos", rechazos);
        return ApiResponse.success(d);
    }

    @Operation(summary = "Cuentas por cobrar: resumen, antigüedad y principales deudores")
    @GetMapping("/cobranzas")
    public ResponseEntity<Object> cobranzas(HttpServletRequest http) {
        return cobranzas(ApiContext.requireTenant(http));
    }

    public ResponseEntity<Object> cobranzas(Tenant t) {
        MapSqlParameterSource q = new MapSqlParameterSource("t", t.getId()).addValue("hoy", Clock.todayLima());
        Map<String, Object> resumen = new LinkedHashMap<>();
        for (String k : List.of("pagado", "parcial", "pendiente")) {
            resumen.put(k, Reports.map("documentos", 0L, "monto_total", 0L, "monto_pagado", 0L, "saldo", 0L));
        }
        for (Map<String, Object> r : jdbc.queryForList("SELECT payment_status, count(*) AS cnt, COALESCE(sum(mto_imp_venta), 0) AS monto_total, "
                + "COALESCE(sum(monto_pagado), 0) AS monto_pagado, COALESCE(sum(mto_imp_venta - monto_pagado), 0) AS saldo FROM ("
                + "SELECT payment_status, mto_imp_venta, monto_pagado FROM invoices WHERE tenant_id = :t AND " + VALIDO + " AND " + VIVO
                + " UNION ALL SELECT payment_status, mto_imp_venta, monto_pagado FROM boletas WHERE tenant_id = :t AND " + VALIDO + " AND " + VIVO
                + ") d GROUP BY payment_status", q)) {
            resumen.put(String.valueOf(r.get("payment_status")), Reports.map("documentos", lng(r.get("cnt")),
                    "monto_total", Reports.round2(dbl(r.get("monto_total"))), "monto_pagado", Reports.round2(dbl(r.get("monto_pagado"))),
                    "saldo", Reports.round2(dbl(r.get("saldo")))));
        }
        String dias = "(CAST(:hoy AS date) - fecha_vencimiento)";
        String saldoPend = " WHERE tenant_id = :t AND " + VALIDO + " AND " + VIVO + " AND payment_status IN ('pendiente', 'parcial')";
        Map<String, Object> ag = jdbc.queryForMap("SELECT "
                + "SUM(CASE WHEN " + dias + " < 0 THEN saldo ELSE 0 END) AS por_vencer, "
                + "SUM(CASE WHEN " + dias + " BETWEEN 0 AND 30 THEN saldo ELSE 0 END) AS v30, "
                + "SUM(CASE WHEN " + dias + " BETWEEN 31 AND 60 THEN saldo ELSE 0 END) AS v60, "
                + "SUM(CASE WHEN " + dias + " BETWEEN 61 AND 90 THEN saldo ELSE 0 END) AS v90, "
                + "SUM(CASE WHEN " + dias + " > 90 THEN saldo ELSE 0 END) AS vmas, count(*) AS docs FROM ("
                + "SELECT fecha_vencimiento, (mto_imp_venta - monto_pagado) AS saldo FROM invoices" + saldoPend + " AND fecha_vencimiento IS NOT NULL"
                + " UNION ALL SELECT fecha_vencimiento, (mto_imp_venta - monto_pagado) FROM boletas" + saldoPend + " AND fecha_vencimiento IS NOT NULL"
                + ") d", q);
        List<Map<String, Object>> deudores = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT client_num_doc AS ruc, client_razon_social AS cliente, count(*) AS documentos, "
                + "ROUND(SUM(mto_imp_venta - monto_pagado), 2) AS saldo FROM ("
                + "SELECT client_num_doc, client_razon_social, mto_imp_venta, monto_pagado FROM invoices" + saldoPend
                + " UNION ALL SELECT client_num_doc, client_razon_social, mto_imp_venta, monto_pagado FROM boletas" + saldoPend
                + ") d GROUP BY client_num_doc, client_razon_social HAVING SUM(mto_imp_venta - monto_pagado) > 0"
                + " ORDER BY saldo DESC, client_num_doc COLLATE \"C\", client_razon_social COLLATE \"C\" LIMIT 10", q)) {
            deudores.add(Reports.map("ruc", r.get("ruc"), "cliente", Reports.mbSubstr(r.get("cliente"), 60), "documentos", lng(r.get("documentos")),
                    "saldo", dbl(r.get("saldo"))));
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("resumen", resumen);
        d.put("aging", Reports.map("por_vencer", Reports.round2(dbl(ag.get("por_vencer"))), "vencido_0_30", Reports.round2(dbl(ag.get("v30"))),
                "vencido_31_60", Reports.round2(dbl(ag.get("v60"))), "vencido_61_90", Reports.round2(dbl(ag.get("v90"))),
                "vencido_mas_90", Reports.round2(dbl(ag.get("vmas"))), "documentos_con_saldo", lng(ag.get("docs"))));
        d.put("top_deudores", deudores);
        return ApiResponse.success(d);
    }

    @Operation(summary = "Ventas de los últimos 12 meses frente al año anterior")
    @GetMapping("/ventas-mensuales")
    public ResponseEntity<Object> ventasMensuales(HttpServletRequest http) {
        return ventasMensuales(ApiContext.requireTenant(http));
    }

    public ResponseEntity<Object> ventasMensuales(Tenant t) {
        long tid = t.getId();
        YearMonth actual = YearMonth.from(Clock.todayLima());
        Map<String, Double> ventas = ventasPorMes(tid, actual.minusMonths(11), actual);
        Map<String, Double> anterior = ventasPorMes(tid, actual.minusYears(1).minusMonths(11), actual.minusYears(1));
        List<Map<String, Object>> meses = new ArrayList<>();
        double total = 0;
        double totalAnt = 0;
        for (int i = 11; i >= 0; i--) {
            YearMonth m = actual.minusMonths(i);
            double v = Reports.round2(ventas.getOrDefault(m.toString(), 0.0));
            double va = Reports.round2(anterior.getOrDefault(m.minusYears(1).toString(), 0.0));
            total += v;
            totalAnt += va;
            meses.add(Reports.map("mes", m.toString(), "mes_label", MESES[m.getMonthValue()] + " " + m.getYear(), "ventas", v,
                    "ventas_anio_anterior", va));
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("meses", meses);
        d.put("total_12_meses", Reports.round2(total));
        d.put("total_12_meses_anterior", Reports.round2(totalAnt));
        d.put("crecimiento_porcentaje", pctChange(total, totalAnt));
        d.put("promedio_mensual", Reports.round2(total / 12));
        return ApiResponse.success(d);
    }

    @Operation(summary = "Ranking de ventas por sucursal")
    @GetMapping("/por-sucursal")
    public ResponseEntity<Object> porSucursal(HttpServletRequest http) {
        Tenant t = ApiContext.requireTenant(http);
        return porSucursal(t, Input.of(http));
    }

    public ResponseEntity<Object> porSucursal(Tenant t, Input in) {
        Periodo p = periodo(in);
        if (p == null) {
            return periodoInvalido();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT s.cod_local, s.nombre, "
                + "COALESCE(SUM(CASE WHEN d.tipo = 'factura' THEN d.monto ELSE 0 END), 0) AS facturas_monto, "
                + "COALESCE(SUM(CASE WHEN d.tipo = 'factura' THEN 1 ELSE 0 END), 0) AS facturas_cnt, "
                + "COALESCE(SUM(CASE WHEN d.tipo = 'boleta' THEN d.monto ELSE 0 END), 0) AS boletas_monto, "
                + "COALESCE(SUM(CASE WHEN d.tipo = 'boleta' THEN 1 ELSE 0 END), 0) AS boletas_cnt, COALESCE(SUM(d.monto), 0) AS total "
                + "FROM sucursales s LEFT JOIN ("
                + "SELECT 'factura' AS tipo, cod_local, mto_imp_venta AS monto FROM invoices WHERE tenant_id = :t AND fecha_emision BETWEEN :d AND :h AND "
                + VIVO + " AND " + VALIDO
                + " UNION ALL SELECT 'boleta', cod_local, mto_imp_venta FROM boletas WHERE tenant_id = :t AND fecha_emision BETWEEN :d AND :h AND "
                + VIVO + " AND " + VALIDO + ") d ON d.cod_local = s.cod_local WHERE s.tenant_id = :t GROUP BY s.cod_local, s.nombre "
                + "ORDER BY total DESC, s.cod_local COLLATE \"C\", s.nombre COLLATE \"C\"", params(t, p.desde(), p.hasta()));
        double general = 0;
        for (Map<String, Object> r : rows) {
            general += dbl(r.get("total"));
        }
        List<Map<String, Object>> sucursales = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            double tot = dbl(r.get("total"));
            sucursales.add(Reports.map("cod_local", r.get("cod_local"), "nombre", r.get("nombre"),
                    "facturas", Reports.map("cantidad", lng(r.get("facturas_cnt")), "monto", Reports.round2(dbl(r.get("facturas_monto")))),
                    "boletas", Reports.map("cantidad", lng(r.get("boletas_cnt")), "monto", Reports.round2(dbl(r.get("boletas_monto")))),
                    "total", Reports.round2(tot), "share_porcentaje", general > 0 ? (Object) PhpMath.round(tot / general * 100, 1) : 0L));
        }
        return ApiResponse.success(Reports.map("periodo", p.json(false), "total_general", Reports.round2(general), "sucursales", sucursales));
    }

    @Operation(summary = "Ventas por moneda")
    @GetMapping("/por-moneda")
    public ResponseEntity<Object> porMoneda(HttpServletRequest http) {
        Tenant t = ApiContext.requireTenant(http);
        return porMoneda(t, Input.of(http));
    }

    public ResponseEntity<Object> porMoneda(Tenant t, Input in) {
        Periodo p = periodo(in);
        if (p == null) {
            return periodoInvalido();
        }
        List<Map<String, Object>> monedas = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT tipo_moneda, count(*) AS cnt, COALESCE(SUM(mto_imp_venta), 0) AS total, "
                + "COALESCE(SUM(mto_igv), 0) AS igv FROM (" + ventasValidas("tipo_moneda, mto_imp_venta, mto_igv")
                + ") d GROUP BY tipo_moneda ORDER BY total DESC, tipo_moneda COLLATE \"C\"", params(t, p.desde(), p.hasta()))) {
            monedas.add(Reports.map("moneda", r.get("tipo_moneda"), "documentos", lng(r.get("cnt")), "total", Reports.round2(dbl(r.get("total"))),
                    "igv", Reports.round2(dbl(r.get("igv")))));
        }
        return ApiResponse.success(Reports.map("periodo", p.json(false), "monedas", monedas));
    }

    @Operation(summary = "Clientes: principales, nuevos y recurrentes")
    @GetMapping("/clientes")
    public ResponseEntity<Object> clientes(HttpServletRequest http) {
        Tenant t = ApiContext.requireTenant(http);
        return clientes(t, Input.of(http));
    }

    public ResponseEntity<Object> clientes(Tenant t, Input in) {
        Periodo p = periodo(in);
        if (p == null) {
            return periodoInvalido();
        }
        MapSqlParameterSource q = params(t, p.desde(), p.hasta());
        List<Map<String, Object>> top = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT client_num_doc AS ruc, client_razon_social AS nombre, client_tipo_doc AS tipo_doc, "
                + "count(*) AS documentos, ROUND(SUM(mto_imp_venta), 2) AS total FROM ("
                + ventasValidas("client_num_doc, client_razon_social, client_tipo_doc, mto_imp_venta")
                + ") d GROUP BY client_num_doc, client_razon_social, client_tipo_doc ORDER BY total DESC, client_num_doc COLLATE \"C\", "
                + "client_razon_social COLLATE \"C\", client_tipo_doc COLLATE \"C\" LIMIT 20", q)) {
            top.add(Reports.map("ruc", r.get("ruc"), "tipo_documento", r.get("tipo_doc"), "nombre", Reports.mbSubstr(r.get("nombre"), 80),
                    "documentos", lng(r.get("documentos")), "total", dbl(r.get("total"))));
        }
        String docs = "SELECT client_num_doc FROM invoices WHERE tenant_id = :t AND fecha_emision BETWEEN :d AND :h AND " + VIVO
                + " UNION ALL SELECT client_num_doc FROM boletas WHERE tenant_id = :t AND fecha_emision BETWEEN :d AND :h AND " + VIVO;
        Long unicos = jdbc.queryForObject("SELECT count(DISTINCT client_num_doc) FROM (" + docs + ") d", q, Long.class);
        Long nuevos = jdbc.queryForObject("SELECT count(*) FROM clients WHERE tenant_id = :t AND created_at BETWEEN :d AND :h", q, Long.class);
        Long registrados = jdbc.queryForObject("SELECT count(*) FROM clients WHERE tenant_id = :t", q, Long.class);
        Long recurrentes = jdbc.queryForObject("SELECT count(*) FROM (SELECT client_num_doc FROM (" + docs
                + ") d GROUP BY client_num_doc HAVING count(*) > 1) r", q, Long.class);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("periodo", p.json(false));
        d.put("total_registrados", registrados);
        d.put("clientes_unicos_periodo", unicos);
        d.put("clientes_nuevos", nuevos);
        d.put("clientes_recurrentes", recurrentes);
        d.put("top_20", top);
        return ApiResponse.success(d);
    }

    @Operation(summary = "Productos: más vendidos por monto y cantidad, y por afectación del IGV")
    @GetMapping("/productos")
    public ResponseEntity<Object> productos(HttpServletRequest http) {
        Tenant t = ApiContext.requireTenant(http);
        return productos(t, Input.of(http));
    }

    public ResponseEntity<Object> productos(Tenant t, Input in) {
        Periodo p = periodo(in);
        if (p == null) {
            return periodoInvalido();
        }
        MapSqlParameterSource q = params(t, p.desde(), p.hasta());
        String base = "SELECT descripcion, codigo, ROUND(SUM(cantidad), 2) AS cantidad, ROUND(SUM(cantidad * mto_precio_unitario), 2) AS total FROM ("
                + ITEMS_CODIGO + ") c GROUP BY descripcion, codigo ORDER BY ";
        String empate = ", descripcion COLLATE \"C\", codigo COLLATE \"C\" LIMIT 20";
        List<Map<String, Object>> porTipo = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT tip_afe_igv, count(*) AS items, ROUND(SUM(cantidad * mto_precio_unitario), 2) AS total FROM ("
                + ITEMS_IGV + ") c GROUP BY tip_afe_igv ORDER BY total DESC, tip_afe_igv COLLATE \"C\"", q)) {
            String code = (String) r.get("tip_afe_igv");
            porTipo.add(Reports.map("codigo", code, "descripcion", tipoAfectacion(code), "items", lng(r.get("items")), "total", dbl(r.get("total"))));
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("periodo", p.json(false));
        d.put("top_por_venta", productos(jdbc.queryForList(base + "total DESC" + empate, q)));
        d.put("top_por_cantidad", productos(jdbc.queryForList(base + "cantidad DESC" + empate, q)));
        d.put("por_tipo_igv", porTipo);
        return ApiResponse.success(d);
    }

    @Operation(summary = "Últimos comprobantes emitidos (limit, máximo 100)")
    @GetMapping("/documentos-recientes")
    public ResponseEntity<Object> documentosRecientes(HttpServletRequest http) {
        Tenant t = ApiContext.requireTenant(http);
        return documentosRecientes(t, Input.of(http));
    }

    public ResponseEntity<Object> documentosRecientes(Tenant t, Input in) {
        Object raw = in.get("limit");
        long limit = Math.max(0, Math.min(raw == null ? 20 : Input.intval(raw), 100));
        String[][] tablas = {{"factura", "invoices"}, {"boleta", "boletas"}, {"nota_credito", "credit_notes"}, {"nota_debito", "debit_notes"}};
        StringBuilder sql = new StringBuilder("SELECT * FROM (");
        for (int i = 0; i < tablas.length; i++) {
            sql.append(i == 0 ? "" : " UNION ALL ").append("SELECT ").append(i).append(" AS ord, '").append(tablas[i][0])
                    .append("' AS tipo, id, serie, correlativo, fecha_emision, created_at, client_num_doc, client_razon_social, tipo_moneda, ")
                    .append("mto_imp_venta, sunat_status FROM ").append(tablas[i][1]).append(" WHERE tenant_id = :t AND ").append(VIVO);
        }
        sql.append(") d ORDER BY created_at DESC, ord, id DESC LIMIT :limit");
        List<Map<String, Object>> docs = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql.toString(), new MapSqlParameterSource("t", t.getId()).addValue("limit", limit))) {
            docs.add(Reports.map("tipo", r.get("tipo"), "id", lng(r.get("id")), "numero", Reports.numero(r.get("serie"), r.get("correlativo"), 8),
                    "fecha_emision", Reports.dateTime(r.get("fecha_emision")), "cliente_documento", r.get("client_num_doc"),
                    "cliente", Reports.mbSubstr(r.get("client_razon_social"), 60), "moneda", r.get("tipo_moneda"),
                    "total", Reports.round2(dbl(r.get("mto_imp_venta"))), "estado_sunat", r.get("sunat_status"),
                    "emitido_en", Reports.dateTime(r.get("created_at"))));
        }
        return ApiResponse.success(Reports.map("documentos", docs));
    }

    @Operation(summary = "Alertas: rechazos, vencimientos, pendientes de envío y series por agotarse")
    @GetMapping("/alertas")
    public ResponseEntity<Object> alertas(HttpServletRequest http) {
        return alertas(ApiContext.requireTenant(http));
    }

    public ResponseEntity<Object> alertas(Tenant t) {
        LocalDate hoy = Clock.todayLima();
        // updated_at se guarda en UTC
        MapSqlParameterSource q = new MapSqlParameterSource("t", t.getId()).addValue("hace7", Clock.nowUtc().minusDays(7).withNano(0))
                .addValue("hoy", hoy).addValue("en7", hoy.plusDays(7));
        Long rechazados = jdbc.queryForObject("SELECT count(*) FROM (SELECT id FROM invoices WHERE tenant_id = :t AND sunat_status = 'rechazado' "
                + "AND updated_at >= :hace7 AND " + VIVO + " UNION ALL SELECT id FROM boletas WHERE tenant_id = :t AND sunat_status = 'rechazado' "
                + "AND updated_at >= :hace7 AND " + VIVO + ") r", q, Long.class);
        String pend = " WHERE tenant_id = :t AND " + VALIDO + " AND " + VIVO + " AND payment_status IN ('pendiente', 'parcial') AND ";
        Map<String, Object> pronto = jdbc.queryForMap("SELECT count(*) AS cnt, COALESCE(SUM(mto_imp_venta - monto_pagado), 0) AS monto FROM ("
                + "SELECT mto_imp_venta, monto_pagado FROM invoices" + pend + "fecha_vencimiento BETWEEN :hoy AND :en7"
                + " UNION ALL SELECT mto_imp_venta, monto_pagado FROM boletas" + pend + "fecha_vencimiento BETWEEN :hoy AND :en7) v", q);
        Map<String, Object> vencidos = jdbc.queryForMap("SELECT count(*) AS cnt, COALESCE(SUM(mto_imp_venta - monto_pagado), 0) AS monto FROM ("
                + "SELECT mto_imp_venta, monto_pagado FROM invoices" + pend + "fecha_vencimiento < :hoy"
                + " UNION ALL SELECT mto_imp_venta, monto_pagado FROM boletas" + pend + "fecha_vencimiento < :hoy) v", q);
        StringBuilder sinEnviar = new StringBuilder("SELECT count(*) FROM (");
        String[] tablas = {"invoices", "boletas", "credit_notes", "debit_notes"};
        for (int i = 0; i < tablas.length; i++) {
            sinEnviar.append(i == 0 ? "" : " UNION ALL ").append("SELECT id FROM ").append(tablas[i])
                    .append(" WHERE tenant_id = :t AND sunat_status IN ('pendiente', 'enviado') AND ").append(VIVO);
        }
        Long pendientes = jdbc.queryForObject(sinEnviar.append(") p").toString(), q, Long.class);
        List<Map<String, Object>> series = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT tipo_documento, serie, correlativo FROM series WHERE tenant_id = :t "
                + "AND correlativo > 90000000 ORDER BY id", q)) {
            series.add(Reports.map("tipo_documento", r.get("tipo_documento"), "serie", r.get("serie"), "correlativo", lng(r.get("correlativo"))));
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("rechazados_7_dias", Reports.map("cantidad", rechazados));
        d.put("vencen_proximos_7_dias", Reports.map("cantidad", lng(pronto.get("cnt")), "monto", Reports.round2(dbl(pronto.get("monto")))));
        d.put("vencidos", Reports.map("cantidad", lng(vencidos.get("cnt")), "monto", Reports.round2(dbl(vencidos.get("monto")))));
        d.put("pendientes_envio_sunat", Reports.map("cantidad", pendientes));
        d.put("series_por_agotar", series);
        return ApiResponse.success(d);
    }

    // ------------------------------------------------------------------ apoyo

    static final String ITEMS = "SELECT ii.descripcion, ii.cantidad, ii.mto_precio_unitario FROM invoice_items ii JOIN invoices i ON i.id = ii.invoice_id "
            + "WHERE i.tenant_id = :t AND i.fecha_emision BETWEEN :d AND :h AND i.deleted_at IS NULL "
            + "UNION ALL SELECT bi.descripcion, bi.cantidad, bi.mto_precio_unitario FROM boleta_items bi JOIN boletas b ON b.id = bi.boleta_id "
            + "WHERE b.tenant_id = :t AND b.fecha_emision BETWEEN :d AND :h AND b.deleted_at IS NULL";
    static final String ITEMS_CODIGO = ITEMS.replace("ii.descripcion,", "ii.descripcion, ii.codigo,").replace("bi.descripcion,", "bi.descripcion, bi.codigo,");
    static final String ITEMS_IGV = ITEMS.replace("ii.descripcion,", "ii.tip_afe_igv,").replace("bi.descripcion,", "bi.tip_afe_igv,");

    private static String ventasValidas(String cols) {
        return "SELECT " + cols + " FROM invoices WHERE tenant_id = :t AND fecha_emision BETWEEN :d AND :h AND " + VIVO + " AND " + VALIDO
                + " UNION ALL SELECT " + cols + " FROM boletas WHERE tenant_id = :t AND fecha_emision BETWEEN :d AND :h AND " + VIVO + " AND " + VALIDO;
    }

    private static List<Map<String, Object>> productos(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(Reports.map("codigo", r.get("codigo"), "descripcion", Reports.mbSubstr(r.get("descripcion"), 80), "cantidad", dbl(r.get("cantidad")),
                    "total", dbl(r.get("total"))));
        }
        return out;
    }

    private Map<String, Object> kpi(long tid, LocalDate desde, LocalDate hasta) {
        Map<String, Object> r = jdbc.queryForMap("SELECT count(*) AS docs, COALESCE(SUM(mto_imp_venta), 0) AS ventas, COALESCE(SUM(mto_igv), 0) AS igv, "
                + "COALESCE(SUM(mto_oper_gratuitas), 0) AS gratuitas, COALESCE(SUM(mto_icbper), 0) AS icbper FROM ("
                + ventasValidas("mto_imp_venta, mto_igv, mto_oper_gratuitas, mto_icbper") + ") d",
                new MapSqlParameterSource("t", tid).addValue("d", desde.atStartOfDay()).addValue("h", hasta.atTime(23, 59, 59)));
        long docs = lng(r.get("docs"));
        double ventas = dbl(r.get("ventas"));
        return Reports.map("documentos", docs, "ventas", Reports.round2(ventas), "igv", Reports.round2(dbl(r.get("igv"))),
                "gratuitas", Reports.round2(dbl(r.get("gratuitas"))), "icbper", Reports.round2(dbl(r.get("icbper"))),
                "ticket_promedio", docs > 0 ? (Object) Reports.round2(ventas / docs) : 0L);
    }

    private Map<String, Double> ventasPorMes(long tid, YearMonth desde, YearMonth hasta) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT ym, SUM(mto_imp_venta) AS total FROM ("
                + ventasValidas("to_char(fecha_emision, 'YYYY-MM') AS ym, mto_imp_venta") + ") d GROUP BY ym",
                new MapSqlParameterSource("t", tid).addValue("d", desde.atDay(1).atStartOfDay()).addValue("h", hasta.atEndOfMonth().atTime(23, 59, 59)))) {
            out.put(String.valueOf(r.get("ym")), dbl(r.get("total")));
        }
        return out;
    }

    /** {@code resolvePeriod}: desde+hasta (Y-m-d) o mes=YYYY-MM (por defecto el mes actual en Lima). */
    static Periodo periodo(Input in) {
        if (in.filled("desde") && in.filled("hasta")) {
            String desde = in.string("desde");
            String hasta = in.string("hasta");
            LocalDate d = Reports.parseDate(desde);
            LocalDate h = Reports.parseDate(hasta);
            if (d == null || h == null) {
                return null;
            }
            return new Periodo(d.atStartOfDay(), h.atTime(23, 59, 59), desde + " al " + hasta);
        }
        Object mes = in.get("mes");
        YearMonth m;
        if (mes == null) {
            m = YearMonth.from(Clock.todayLima());
        } else {
            String s = Php.toStr(mes);
            if (!s.matches("\\d{4}-\\d{2}") || Integer.parseInt(s.substring(5)) < 1 || Integer.parseInt(s.substring(5)) > 12) {
                return null;
            }
            m = YearMonth.parse(s);
        }
        return new Periodo(m.atDay(1).atStartOfDay(), m.atEndOfMonth().atTime(23, 59, 59), MESES[m.getMonthValue()] + " " + m.getYear());
    }

    static ResponseEntity<Object> periodoInvalido() {
        return ApiResponse.error("Periodo inválido. Use mes=AAAA-MM o desde y hasta con formato AAAA-MM-DD.", 422);
    }

    private static MapSqlParameterSource params(Tenant t, LocalDateTime d, LocalDateTime h) {
        return new MapSqlParameterSource("t", t.getId()).addValue("d", d).addValue("h", h);
    }

    private static Map<String, Object> countTotal(long count, Object total) {
        return Reports.map("count", count, "total", total);
    }

    /** {@code pctChange}: 100 si antes no había y ahora sí; si no, variación con un decimal. */
    static double pctChange(double actual, double anterior) {
        if (anterior <= 0) {
            return actual > 0 ? 100.0 : 0.0;
        }
        return PhpMath.round((actual - anterior) / anterior * 100, 1);
    }

    static String tipoAfectacion(String code) {
        if (code == null) {
            return "Otro (-)";
        }
        return switch (code) {
            case "10" -> "Gravado";
            case "11", "12", "13", "14", "15", "16" -> "Gravado - Retiro";
            case "17" -> "Gravado - IVAP";
            case "20" -> "Exonerado";
            case "21" -> "Exonerado - Transferencia Gratuita";
            case "30" -> "Inafecto";
            case "31", "32", "33", "34", "35", "36" -> "Inafecto - Retiro";
            case "40" -> "Exportación";
            default -> "Otro (" + code + ")";
        };
    }

    static double dbl(Object v) {
        return v == null ? 0 : v instanceof Number n ? n.doubleValue() : Php.toFloat(v);
    }

    static long lng(Object v) {
        return v == null ? 0 : v instanceof Number n ? n.longValue() : Php.toInt(v);
    }
}
