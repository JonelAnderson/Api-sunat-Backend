package pe.facturacion.api.modules.settlement;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import pe.facturacion.api.config.LcProperties;
import pe.facturacion.api.modules.catalog.SunatRules;
import pe.facturacion.api.modules.document.DocumentCalculationService;
import pe.facturacion.api.modules.document.PaymentService;
import pe.facturacion.api.modules.plan.EmissionPolicy;
import pe.facturacion.api.modules.plan.PlanService;
import pe.facturacion.api.modules.serie.SerieRepository;
import pe.facturacion.api.modules.tax.TaxRateService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.ticket.ReversionService;
import pe.facturacion.api.modules.ticket.TicketRepository.TicketDoc;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiException;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.api.Paginator;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.shared.validation.Validator;
import pe.facturacion.api.sunat.builder.PhpDates;

/**
 * Liquidación de compra electrónica (tipo 04): módulo nuevo (API-PRO no lo tiene). Emisión,
 * consultas, descargas, envío, pagos, reversión y reporte de retenciones. Ver
 * {@code docs/LIQUIDACION_COMPRA.md}.
 */
@Service
public class SettlementEndpoints {

    private static final Logger log = LoggerFactory.getLogger(SettlementEndpoints.class);
    static final List<String> CAT60 = List.of("01", "02", "03", "04", "05");
    static final List<String> CAUSALES = List.of("vendedor_nombre", "vendedor_documento", "producto");

    private final SettlementRepository repo;
    private final SettlementCalculator calculator;
    private final DocumentCalculationService documentCalculator;
    private final SerieRepository series;
    private final SunatRules sunatRules;
    private final TaxRateService taxRates;
    private final JdbcTemplate jdbc;
    private final PlanService plans;
    private final EmissionPolicy emission;
    private final SendSettlementJob sendJob;
    private final ReversionService reversions;
    private final FileStorage storage;
    private final LcProperties props;
    private final TransactionTemplate tx;

    public SettlementEndpoints(SettlementRepository repo, SettlementCalculator calculator, DocumentCalculationService documentCalculator,
            SerieRepository series, SunatRules sunatRules, TaxRateService taxRates, JdbcTemplate jdbc, PlanService plans,
            EmissionPolicy emission, SendSettlementJob sendJob, ReversionService reversions, FileStorage storage, LcProperties props,
            TransactionTemplate tx) {
        this.repo = repo;
        this.calculator = calculator;
        this.documentCalculator = documentCalculator;
        this.series = series;
        this.sunatRules = sunatRules;
        this.taxRates = taxRates;
        this.jdbc = jdbc;
        this.plans = plans;
        this.emission = emission;
        this.sendJob = sendJob;
        this.reversions = reversions;
        this.storage = storage;
        this.props = props;
        this.tx = tx;
    }

    // ------------------------------------------------------------ validación

    Map<String, Object> rules() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("serie", "required|string|size:4|regex:/^L[A-Z0-9]{3}$/");
        r.put("correlativo", "nullable|integer|min:1|max:99999999");
        r.put("fecha_emision", "required|date");
        r.put("tipo_operacion", "nullable|string|in:0501,0502,0503");
        r.put("tipo_moneda", "nullable|string|in:PEN");
        r.put("productos_agropecuarios", "nullable|boolean");
        r.put("vendedor", "required|array");
        r.put("vendedor.tipo_doc", "required|string|in:1,4");
        r.put("vendedor.num_doc", "required|string|max:15");
        r.put("vendedor.nombre", "required|string|min:3|max:1500");
        r.put("vendedor.direccion", "required|string|min:3|max:250");
        r.put("vendedor.ubigeo", "required|string|digits:6");
        r.put("vendedor.tipo_domicilio", "required|string|in:" + String.join(",", CAT60));
        r.put("lugar", "required|array");
        r.put("lugar.direccion", "required|string|min:3|max:250");
        r.put("lugar.ubigeo", "required|string|digits:6");
        r.put("lugar.tipo", "required|string|in:" + String.join(",", CAT60));
        r.put("items", "required|array|min:1|max:999");
        r.put("items.*.descripcion", "required|string|max:500");
        r.put("items.*.cantidad", "required|numeric|gt:0");
        r.put("items.*.valor_unitario", "required|numeric|gt:0");
        r.put("items.*.unidad", "nullable|string|max:3");
        r.put("items.*.codigo", "nullable|string|max:30");
        r.put("items.*.cod_producto_sunat", List.of("required", "string", "digits:8", sunatRules.productCode()));
        r.put("items.*.tip_afe_igv", "nullable|string|in:10,20,30");
        r.put("items.*.oro", "nullable|array");
        r.put("items.*.oro.codigo_concesion", "nullable|string|max:50");
        r.put("items.*.oro.ley_mineral", "nullable|numeric|min:0|max:100");
        r.put("items.*.oro.naturaleza_mineral", "nullable|string|max:6");
        r.put("items.*.oro.nombre_derecho_minero", "nullable|string|max:100");
        r.put("guias", "nullable|array|max:10");
        r.put("guias.*.tipo", "required|string|in:09,31");
        r.put("guias.*.numero", "required|string|max:30");
        r.put("placa_vehiculo", "nullable|string|regex:/^[A-Z0-9]{6,8}$/");
        r.put("observacion", "nullable|string|max:500");
        return r;
    }

    Map<String, Object> validate(Tenant tenant, Map<String, Object> input) {
        Validator v = Validator.make(input, rules());
        v.after(val -> after(val, tenant));
        return v.validate();
    }

    @SuppressWarnings("unchecked")
    private void after(Validator v, Tenant tenant) {
        validarSerie(v, tenant);
        if (taxRates.isNrus(tenant)) {
            v.addError("serie", "La liquidación de compra solo la emite un contribuyente afecto al Impuesto a la Renta de tercera "
                    + "categoría; el Nuevo RUS no puede emitirla (SUNAT 2463).");
        }
        Object tipoOp = v.input("tipo_operacion");
        if ("0502".equals(tipoOp)) {
            v.addError("tipo_operacion", "La liquidación de compra por anticipos (0502) aún no está disponible en esta API.");
        }
        if (v.input("vendedor") instanceof Map<?, ?> vend) {
            Object tipo = vend.get("tipo_doc");
            String num = Php.toStr(vend.get("num_doc"));
            if ("1".equals(tipo) && !num.matches("^\\d{8}$")) {
                v.addError("vendedor.num_doc", "El DNI del vendedor debe tener 8 dígitos.");
            } else if ("4".equals(tipo) && !num.matches("^[A-Za-z0-9]{1,15}$")) {
                v.addError("vendedor.num_doc", "El carné de extranjería debe ser alfanumérico, de hasta 15 caracteres y sin espacios (SUNAT 2802).");
            }
        }
        LocalDate fecha = date(v.input("fecha_emision"));
        if (fecha != null) {
            LocalDate hoy = Clock.todayLima();
            if (fecha.isAfter(hoy)) {
                v.addError("fecha_emision", "La fecha de emisión no puede ser posterior a la fecha de envío (SUNAT 2329).");
            } else if (fecha.isBefore(hoy.minusDays(1))) {
                v.addError("fecha_emision", "La liquidación de compra se envía en su fecha de emisión o hasta el día calendario siguiente; "
                        + "fuera de ese plazo no tiene calidad de liquidación de compra (RS 097-2012/SUNAT, art. 12.1 b; SUNAT 2108).");
            }
        }
        if ("0503".equals(tipoOp)) {
            int i = 0;
            for (Object o : asList(v.input("items"))) {
                Map<?, ?> oro = o instanceof Map<?, ?> m && m.get("oro") instanceof Map<?, ?> om ? om : Map.of();
                for (String campo : List.of("codigo_concesion", "ley_mineral", "naturaleza_mineral", "nombre_derecho_minero")) {
                    if (Php.empty(oro.get(campo))) {
                        v.addError("items." + i + ".oro." + campo, "La compra de oro (0503) exige " + campo.replace('_', ' ')
                                + " en cada ítem (catálogo 55).");
                    }
                }
                i++;
            }
        }
        if (tenant != null && fecha != null && !v.hasError("vendedor.num_doc") && v.input("vendedor") instanceof Map<?, ?> vend) {
            validarLimiteAnual(v, tenant, Php.toStr(vend.get("num_doc")), fecha, (List<Object>) v.input("items"));
        }
    }

    private void validarSerie(Validator v, Tenant tenant) {
        Object serie = v.input("serie");
        if (tenant == null || !(serie instanceof String s) || s.isEmpty()) {
            return;
        }
        var found = series.findByTenantIdAndTipoDocumentoAndSerie(tenant.getId(), "04", s);
        if (found.isEmpty()) {
            v.addError("serie", "No tienes registrada la serie " + s + " para liquidaciones de compra. Regístrala en el módulo Series.");
        } else if (!found.get().isActive()) {
            v.addError("serie", "La serie " + s + " está desactivada. Actívala en el módulo Series para poder emitir liquidaciones de compra.");
        }
        Object correlativo = v.input("correlativo");
        if (correlativo != null && !"".equals(correlativo) && Php.isInt(correlativo)
                && repo.findBySerie(tenant.getId(), s, Php.toInt(correlativo)).isPresent()) {
            v.addError("correlativo", "El correlativo " + Php.toStr(correlativo) + " ya existe para la serie " + s
                    + ". Use otro número o deje el campo vacío para autoincremento.");
        }
    }

    /**
     * Ventas del vendedor en el año: no puede superar 75 UIT (SUNAT 2451). SUNAT suma todas las
     * liquidaciones del vendedor; aquí se suman las de esta empresa (las demás no son visibles).
     */
    private void validarLimiteAnual(Validator v, Tenant tenant, String numDoc, LocalDate fecha, List<Object> items) {
        BigDecimal uit = props.uitDe(fecha.getYear());
        if (uit == null || items == null) {
            return;
        }
        BigDecimal actual = BigDecimal.ZERO;
        for (Object o : items) {
            if (o instanceof Map<?, ?> m && numeric(m.get("cantidad")) && numeric(m.get("valor_unitario"))) {
                actual = actual.add(SettlementCalculator.dec(m.get("cantidad")).multiply(SettlementCalculator.dec(m.get("valor_unitario"))));
            }
        }
        BigDecimal acumulado = repo.sum("valor_venta", "tenant_id = :t AND vendedor_num_doc = :d AND fecha_emision >= :desde "
                + "AND sunat_status NOT IN ('rechazado', 'revertido')", new MapSqlParameterSource().addValue("t", tenant.getId())
                .addValue("d", numDoc).addValue("desde", fecha.withDayOfYear(1).atStartOfDay()));
        BigDecimal limite = uit.multiply(BigDecimal.valueOf(props.limiteUit()));
        if (acumulado.add(actual).compareTo(limite) > 0) {
            v.addError("vendedor.num_doc", "Las compras al vendedor en " + fecha.getYear() + " superarían " + props.limiteUit()
                    + " UIT (S/ " + limite.setScale(2, RoundingMode.HALF_UP).toPlainString() + "): no corresponde emitir liquidación de compra "
                    + "(SUNAT 2451). Acumulado: S/ " + acumulado.setScale(2, RoundingMode.HALF_UP).toPlainString() + ".");
        }
    }

    // ---------------------------------------------------------------- emisión

    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> store(HttpServletRequest http) {
        Tenant tenant = ApiContext.requireTenant(http);
        return store(tenant, Input.of(http));
    }

    /** Emisión de una liquidación de compra de la empresa indicada con la misma tubería de {@code /api/v1} (la usa también el panel). */
    public ResponseEntity<Object> store(Tenant tenant, Input in) {
        ResponseEntity<Object> denied = emission.deny(tenant);
        if (denied != null) {
            return denied;
        }
        Map<String, Object> data = validate(tenant, in.all());
        boolean enviarAuto = in.all().containsKey("enviar_automatico") ? in.bool("enviar_automatico") : true;
        try {
            long id = tx.execute(st -> create(tenant, data, enviarAuto));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("estado", "exito");
            body.put("mensaje", enviarAuto ? "Liquidación de compra creada y encolada para envío a SUNAT."
                    : "Liquidación de compra creada en estado pendiente. Use POST /liquidaciones-compra/{id}/enviar para enviarla a SUNAT.");
            body.put("datos", resource(repo.findById(id).orElseThrow(), true));
            return ResponseEntity.status(201).body(body);
        } catch (RuntimeException e) {
            return internal("Error al crear la liquidación de compra.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private long create(Tenant tenant, Map<String, Object> data, boolean enviarAuto) {
        List<Map<String, Object>> locked = jdbc.queryForList(
                "SELECT s.id, s.correlativo, su.id AS sucursal_id, su.cod_local FROM series s LEFT JOIN sucursales su ON su.id = s.sucursal_id "
                        + "WHERE s.tenant_id = ? AND s.tipo_documento = '04' AND s.serie = ? AND s.is_active = true LIMIT 1 FOR UPDATE OF s",
                tenant.getId(), Php.toStr(data.get("serie")));
        if (locked.isEmpty()) {
            throw ApiException.notFound();
        }
        Map<String, Object> serie = locked.get(0);
        int actual = ((Number) serie.get("correlativo")).intValue();
        int correlativo = data.get("correlativo") == null ? actual + 1 : (int) Php.toInt(data.get("correlativo"));
        if (correlativo > actual) {
            jdbc.update("UPDATE series SET correlativo = ?, updated_at = ? WHERE id = ?", correlativo, Clock.nowUtc().withNano(0), serie.get("id"));
        }
        LocalDateTime fecha = resolveEmisionDateTime(Php.toStr(data.get("fecha_emision")));
        String tipoOp = Php.toStr(Php.coalesce(data, "tipo_operacion", "0501"));
        boolean agro = Php.truthy(data.get("productos_agropecuarios"));
        SettlementCalculator.Result calc = calculator.calculate((List<Map<String, Object>>) data.get("items"), tipoOp, agro, fecha.toLocalDate());
        Map<String, Object> t = calc.totals();
        Map<String, Object> vend = (Map<String, Object>) data.get("vendedor");
        Map<String, Object> lugar = (Map<String, Object>) data.get("lugar");
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("tenant_id", tenant.getId());
        c.put("sucursal_id", serie.get("sucursal_id"));
        c.put("cod_local", serie.get("cod_local") != null ? serie.get("cod_local") : "0000");
        c.put("serie", Php.toStr(data.get("serie")));
        c.put("correlativo", correlativo);
        c.put("fecha_emision", fecha);
        c.put("tipo_operacion", tipoOp);
        c.put("tipo_moneda", "PEN");
        c.put("vendedor_tipo_doc", Php.toStr(vend.get("tipo_doc")));
        c.put("vendedor_num_doc", Php.toStr(vend.get("num_doc")).toUpperCase(java.util.Locale.ROOT));
        c.put("vendedor_nombre", Php.toStr(vend.get("nombre")).trim());
        c.put("vendedor_direccion", Php.toStr(vend.get("direccion")).trim());
        c.put("vendedor_ubigeo", Php.toStr(vend.get("ubigeo")));
        c.put("vendedor_tipo_domicilio", Php.toStr(vend.get("tipo_domicilio")));
        c.put("lugar_direccion", Php.toStr(lugar.get("direccion")).trim());
        c.put("lugar_ubigeo", Php.toStr(lugar.get("ubigeo")));
        c.put("lugar_tipo", Php.toStr(lugar.get("tipo")));
        c.put("productos_agropecuarios", agro);
        for (String k : List.of("mto_oper_gravadas", "mto_oper_exoneradas", "mto_oper_inafectas", "mto_igv", "porcentaje_igv", "valor_venta",
                "mto_base_renta", "porcentaje_renta", "mto_retencion_renta", "sub_total", "mto_imp_neto")) {
            c.put(k, t.get(k));
        }
        c.put("leyenda", documentCalculator.generateLeyenda(((BigDecimal) t.get("mto_imp_neto")).doubleValue(), "PEN"));
        c.put("observacion", data.get("observacion") == null ? null : Php.toStr(data.get("observacion")));
        List<Object> guias = new ArrayList<>();
        for (Object o : asList(data.get("guias"))) {
            Map<String, Object> g = (Map<String, Object>) o;
            guias.add(Map.of("tipo", Php.toStr(g.get("tipo")), "numero", Php.toStr(g.get("numero"))));
        }
        c.put("guias", guias.isEmpty() ? null : guias);
        c.put("placa_vehiculo", data.get("placa_vehiculo") == null ? null : Php.toStr(data.get("placa_vehiculo")));
        c.put("sunat_status", "pendiente");
        long id = repo.insert(SettlementRepository.TABLE, c);
        for (Map<String, Object> it : calc.items()) {
            Map<String, Object> row = new LinkedHashMap<>(it);
            row.put("purchase_settlement_id", id);
            repo.insert(SettlementRepository.ITEMS, row);
        }
        plans.incrementDocumentUsage(tenant);
        if (enviarAuto) {
            sendJob.dispatch(id);
            repo.update(id, Map.of("sunat_status", "enviado"));
        }
        return id;
    }

    static LocalDateTime resolveEmisionDateTime(String fecha) {
        if (fecha.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return LocalDate.parse(fecha).atTime(Clock.nowLima().toLocalTime().withNano(0));
        }
        return PhpDates.parseDateTime(fecha);
    }

    // ------------------------------------------------------------- consultas

    public ResponseEntity<Object> index(HttpServletRequest http) {
        Tenant tenant = ApiContext.requireTenant(http);
        return index(tenant, Input.of(http));
    }

    /** Listado de la empresa indicada (lo usa también el panel de administración con la misma respuesta). */
    public ResponseEntity<Object> index(Tenant tenant, Input in) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", tenant.getId());
        List<String> w = new ArrayList<>(List.of("tenant_id = :t"));
        if (Php.truthy(in.get("sunat_status"))) {
            p.addValue("st", Php.toStr(in.get("sunat_status")));
            w.add("sunat_status = :st");
        }
        LocalDate desde = date(in.get("fecha_desde"));
        if (desde != null) {
            p.addValue("fd", desde.atStartOfDay());
            w.add("fecha_emision >= :fd");
        }
        LocalDate hasta = date(in.get("fecha_hasta"));
        if (hasta != null) {
            p.addValue("fh", hasta.plusDays(1).atStartOfDay());
            w.add("fecha_emision < :fh");
        }
        if (Php.truthy(in.get("serie"))) {
            p.addValue("serie", Php.toStr(in.get("serie")));
            w.add("serie = :serie");
        }
        if (Php.truthy(in.get("vendedor_num_doc"))) {
            p.addValue("vd", Php.toStr(in.get("vendedor_num_doc")));
            w.add("vendedor_num_doc = :vd");
        }
        String where = String.join(" AND ", w);
        Object raw = in.get("por_pagina");
        int n = raw == null ? 15 : (int) Php.toInt(raw);
        int perPage = n <= 0 ? 15 : Math.min(n, 100);
        int page = Paginator.page(in);
        long total = repo.count(where, p);
        List<Object> datos = new ArrayList<>();
        for (Map<String, Object> r : repo.query(where, p, "fecha_emision DESC, id DESC", perPage, (page - 1) * perPage)) {
            datos.add(resource(r, false));
        }
        Map<String, Object> paginacion = new LinkedHashMap<>();
        paginacion.put("pagina_actual", page);
        paginacion.put("ultima_pagina", (int) Math.max(Math.ceil((double) total / perPage), 1));
        paginacion.put("por_pagina", perPage);
        paginacion.put("total", total);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("datos", datos);
        d.put("paginacion", paginacion);
        return ApiResponse.success(d);
    }

    public ResponseEntity<Object> show(HttpServletRequest http, long id) {
        return ApiResponse.success(resource(find(http, id), true));
    }

    public ResponseEntity<Object> show(Tenant tenant, long id) {
        return ApiResponse.success(resource(find(tenant, id), true));
    }

    public ResponseEntity<Object> enviar(HttpServletRequest http, long id, boolean reenviar) {
        return enviar(ApiContext.requireTenant(http), id, reenviar);
    }

    /** Enviar (o reenviar, solo pendientes o rechazadas) una liquidación de la empresa indicada (404 si es de otra). */
    public ResponseEntity<Object> enviar(Tenant tenant, long id, boolean reenviar) {
        Map<String, Object> s = find(tenant, id);
        String st = Php.toStr(s.get("sunat_status"));
        if (List.of("aceptado", "reversion_en_proceso", "revertido").contains(st)) {
            return ApiResponse.error("Esta liquidación de compra ya fue aceptada por SUNAT (estado: " + st + ").", 422);
        }
        if (reenviar && !"rechazado".equals(st) && !"pendiente".equals(st)) {
            return ApiResponse.error("Solo se reenvían liquidaciones pendientes o rechazadas (estado: " + st + ").", 422);
        }
        tx.executeWithoutResult(t -> {
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("sunat_status", "enviado");
            ch.put("sunat_code", null);
            ch.put("sunat_description", null);
            repo.update(id, ch);
            sendJob.dispatch(id);
        });
        return ApiResponse.success(resource(repo.findById(id).orElseThrow(), true), "Liquidación de compra enviada a SUNAT.");
    }

    public ResponseEntity<Object> xml(HttpServletRequest http, long id) {
        return xml(ApiContext.requireTenant(http), id);
    }

    public ResponseEntity<Object> xml(Tenant tenant, long id) {
        Map<String, Object> s = find(tenant, id);
        String path = (String) s.get("xml_path");
        if (path == null || !storage.exists(Disk.PRIVATE, path)) {
            return ApiResponse.error("XML no disponible", 404);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/xml")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + SendSettlementJob.numero(s) + ".xml\"")
                .body(new String(storage.get(Disk.PRIVATE, path), java.nio.charset.StandardCharsets.UTF_8));
    }

    public ResponseEntity<Object> cdr(HttpServletRequest http, long id) {
        return cdr(ApiContext.requireTenant(http), id);
    }

    public ResponseEntity<Object> cdr(Tenant tenant, long id) {
        Map<String, Object> s = find(tenant, id);
        String path = (String) s.get("cdr_path");
        if (path == null || !storage.exists(Disk.PRIVATE, path)) {
            return ApiResponse.error("CDR no disponible", 404);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"R-" + SendSettlementJob.numero(s) + ".zip\"")
                .body(storage.get(Disk.PRIVATE, path));
    }

    // ----------------------------------------------------------------- pagos

    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> paymentStore(HttpServletRequest http, long id) {
        Tenant tenant = ApiContext.requireTenant(http);
        Map<String, Object> data = new LinkedHashMap<>(Input.of(http).all());
        if (data.containsKey("metodo") && !data.containsKey("pagos")) {
            Map<String, Object> pago = new LinkedHashMap<>();
            for (String k : List.of("metodo", "monto", "referencia", "notas")) {
                pago.put(k, data.get(k));
            }
            data.put("pagos", new ArrayList<>(List.of(pago)));
        }
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("pagos", "required|array|min:1");
        rules.put("pagos.*.metodo", "required|string|in:" + String.join(",", PaymentService.METODOS));
        rules.put("pagos.*.monto", "required|numeric|min:0.01");
        rules.put("pagos.*.referencia", "nullable|string|max:100");
        rules.put("pagos.*.notas", "nullable|string|max:255");
        Validator.make(data, rules).validate();
        Map<String, Object> s = repo.find(tenant.getId(), id).orElseThrow(ApiException::notFound);
        if ("revertido".equals(s.get("sunat_status")) || "reversion_en_proceso".equals(s.get("sunat_status"))) {
            return ApiResponse.error("La liquidación de compra está revertida o en proceso de reversión: no admite pagos.", 422);
        }
        tx.executeWithoutResult(t -> {
            for (Object o : asList(data.get("pagos"))) {
                Map<String, Object> pago = (Map<String, Object>) o;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("metodo", Php.toStr(pago.get("metodo")));
                row.put("monto", SettlementCalculator.dec(pago.get("monto")).setScale(2, RoundingMode.HALF_UP));
                row.put("referencia", pago.get("referencia") == null ? null : Php.toStr(pago.get("referencia")));
                row.put("notas", pago.get("notas") == null ? null : Php.toStr(pago.get("notas")));
                repo.insertPayment(tenant.getId(), id, row);
            }
            recalculatePayments(s);
        });
        return paymentIndex(http, id, "Pagos registrados correctamente");
    }

    public ResponseEntity<Object> paymentIndex(HttpServletRequest http, long id, String mensaje) {
        return paymentIndex(ApiContext.requireTenant(http), id, mensaje);
    }

    public ResponseEntity<Object> paymentIndex(Tenant tenant, long id, String mensaje) {
        Map<String, Object> s = find(tenant, id);
        Map<String, Object> body = new LinkedHashMap<>();
        if (mensaje != null) {
            body.put("mensaje", mensaje);
        }
        List<Object> pagos = new ArrayList<>();
        for (Map<String, Object> p : repo.payments(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ((Number) p.get("id")).longValue());
            m.put("metodo", p.get("metodo"));
            m.put("monto", f(p.get("monto")));
            m.put("referencia", p.get("referencia"));
            m.put("notas", p.get("notas"));
            m.put("creado_en", p.get("created_at") == null ? null : Clock.iso8601Utc((LocalDateTime) p.get("created_at")));
            pagos.add(m);
        }
        body.put(mensaje == null ? "datos" : "pagos", pagos);
        body.put("estado_pago", s.get("payment_status"));
        body.put("monto_pagado", f(s.get("monto_pagado")));
        body.put("total_documento", f(s.get("mto_imp_neto")));
        return ResponseEntity.ok(body);
    }

    public ResponseEntity<Object> paymentDestroy(HttpServletRequest http, long id, long paymentId) {
        Map<String, Object> s = find(http, id);
        tx.executeWithoutResult(t -> {
            if (repo.deletePayment(id, paymentId) == 0) {
                throw ApiException.notFound();
            }
            recalculatePayments(s);
        });
        Map<String, Object> fresh = repo.findById(id).orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mensaje", "Pago eliminado correctamente");
        body.put("estado_pago", fresh.get("payment_status"));
        body.put("monto_pagado", f(fresh.get("monto_pagado")));
        return ResponseEntity.ok(body);
    }

    private void recalculatePayments(Map<String, Object> s) {
        long id = ((Number) s.get("id")).longValue();
        BigDecimal pagado = repo.sumPayments(id);
        BigDecimal total = (BigDecimal) s.get("mto_imp_neto");
        String status = pagado.signum() == 0 ? "pendiente" : pagado.compareTo(total) < 0 ? "parcial" : "pagado";
        repo.update(id, Map.of("monto_pagado", pagado, "payment_status", status));
    }

    // -------------------------------------------------------------- reversión

    /**
     * Reversión (RS 097-2012/SUNAT art. 29-F y RS 188-2010/SUNAT art. 26, modificados por la
     * RS 000047-2026/SUNAT): CDR aceptado; causal (nombre del vendedor, su documento o producto,
     * cantidad o unidad); sin pagos registrados; hasta el 5.º día calendario siguiente a la emisión
     * y con el resumen de reversiones dentro de los 5 días siguientes al CDR aceptado. Se informa con
     * un Resumen diario de reversiones (RR); el número de la liquidación no se reutiliza.
     */
    public ResponseEntity<Object> reversion(HttpServletRequest http, long id) {
        return reversion(ApiContext.requireTenant(http), Input.of(http), id);
    }

    /** Reversión (RR) de una liquidación de la empresa indicada, con los mismos plazos y reglas (la usa también el panel). */
    public ResponseEntity<Object> reversion(Tenant tenant, Input in, long id) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("causal", "required|string|in:" + String.join(",", CAUSALES));
        rules.put("motivo", "required|string|min:3|max:100");
        Map<String, Object> data = Validator.make(in.all(), rules).validate();
        Map<String, Object> s = repo.find(tenant.getId(), id).orElseThrow(ApiException::notFound);
        List<String> errores = new ArrayList<>();
        String st = Php.toStr(s.get("sunat_status"));
        LocalDate hoy = Clock.todayLima();
        LocalDate emision = ((LocalDateTime) s.get("fecha_emision")).toLocalDate();
        int dias = props.reversionDias();
        if ("reversion_en_proceso".equals(st) || "revertido".equals(st)) {
            errores.add("La liquidación ya tiene una reversión (estado: " + st + ") (SUNAT 2751).");
        } else if (!"aceptado".equals(st)) {
            errores.add("Solo se revierte una liquidación con CDR aceptado (estado: " + st + ") (SUNAT 2398).");
        } else {
            if (hoy.isAfter(emision.plusDays(dias))) {
                errores.add("El plazo de reversión venció: hasta el " + dias + ".º día calendario siguiente a la emisión ("
                        + emision.plusDays(dias) + ").");
            }
            LocalDateTime cdr = (LocalDateTime) s.get("cdr_fecha");
            if (cdr != null && hoy.isAfter(cdr.toLocalDate().plusDays(dias))) {
                errores.add("El resumen de reversiones debía enviarse dentro de los " + dias + " días siguientes al CDR aceptado ("
                        + cdr.toLocalDate().plusDays(dias) + ") (SUNAT 2958).");
            }
            if (repo.sumPayments(id).signum() > 0) {
                errores.add("La liquidación tiene pagos registrados: no se puede revertir (SUNAT 2471).");
            }
        }
        if (!errores.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("estado", "error");
            body.put("mensaje", "No se puede revertir: " + String.join(" ", errores));
            body.put("errores", errores);
            return ResponseEntity.status(422).body(body);
        }
        String motivo = Php.toStr(data.get("motivo")).trim();
        Map<String, Object> det = new LinkedHashMap<>();
        det.put("tipo_documento", "04");
        det.put("serie", s.get("serie"));
        det.put("correlativo", String.valueOf(((Number) s.get("correlativo")).longValue()));
        det.put("motivo", motivo);
        det.put("causal", Php.toStr(data.get("causal")));
        try {
            TicketDoc rr = reversions.crear(tenant.getId(), new ArrayList<>(List.of(det)), emision, hoy, true);
            Map<String, Object> datos = new LinkedHashMap<>();
            datos.put("id_liquidacion", id);
            datos.put("voided_id", rr.id);
            datos.put("identifier", rr.identifier);
            datos.put("sunat_status", rr.sunatStatus);
            datos.put("consulta_estado", baseUrl() + "/api/v1/anulaciones/" + rr.id + "/estado");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("estado", "exito");
            body.put("mensaje", "Reversión encolada para envío a SUNAT. La liquidación queda en reversion_en_proceso hasta la respuesta.");
            body.put("datos", datos);
            return ResponseEntity.status(201).body(body);
        } catch (RuntimeException e) {
            return internal("No se pudo crear la reversión.", e);
        }
    }

    // ---------------------------------------------------------------- reporte

    /**
     * Retenciones del periodo (por fecha de emisión): IGV retenido y renta retenida de cada
     * liquidación aceptada (o en reversión), para el pago por SUNAT Virtual vinculado a cada
     * comprobante y la declaración en el Formulario Virtual 617 (RS 000047-2026/SUNAT).
     */
    public ResponseEntity<Object> reporteRetenciones(HttpServletRequest http) {
        Tenant tenant = ApiContext.requireTenant(http);
        return reporteRetenciones(tenant, Input.of(http));
    }

    public ResponseEntity<Object> reporteRetenciones(Tenant tenant, Input in) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("periodo", List.of("required", "string", "regex:/^\\d{4}-(0[1-9]|1[0-2])$/"));
        Validator.make(in.all(), rules).validate();
        String periodo = Php.toStr(in.get("periodo"));
        LocalDate desde = LocalDate.parse(periodo + "-01");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", tenant.getId())
                .addValue("d", desde.atStartOfDay()).addValue("h", desde.plusMonths(1).atStartOfDay());
        String where = "tenant_id = :t AND fecha_emision >= :d AND fecha_emision < :h AND sunat_status IN ('aceptado', 'reversion_en_proceso')";
        List<Object> filas = new ArrayList<>();
        BigDecimal[] tot = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (Map<String, Object> s : repo.query(where, p, "fecha_emision, id", 100000, 0)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ((Number) s.get("id")).longValue());
            m.put("numero", s.get("serie") + "-" + s.get("correlativo"));
            m.put("fecha_emision", ((LocalDateTime) s.get("fecha_emision")).toLocalDate().toString());
            m.put("vendedor", vendedor(s));
            m.put("base_imponible_igv", f(s.get("mto_oper_gravadas")));
            m.put("igv_retenido", f(s.get("mto_igv")));
            m.put("base_renta", f(s.get("mto_base_renta")));
            m.put("porcentaje_renta", f(s.get("porcentaje_renta")));
            m.put("renta_retenida", f(s.get("mto_retencion_renta")));
            m.put("neto_pagado_vendedor", f(s.get("mto_imp_neto")));
            m.put("estado_sunat", s.get("sunat_status"));
            m.put("estado_pago", s.get("payment_status"));
            filas.add(m);
            String[] cols = {"mto_oper_gravadas", "mto_igv", "mto_base_renta", "mto_retencion_renta", "mto_imp_neto"};
            for (int i = 0; i < cols.length; i++) {
                tot[i] = tot[i].add((BigDecimal) s.get(cols[i]));
            }
        }
        Map<String, Object> totales = new LinkedHashMap<>();
        totales.put("cantidad", filas.size());
        totales.put("base_imponible_igv", tot[0].doubleValue());
        totales.put("igv_retenido", tot[1].doubleValue());
        totales.put("base_renta", tot[2].doubleValue());
        totales.put("renta_retenida", tot[3].doubleValue());
        totales.put("neto_pagado_vendedor", tot[4].doubleValue());
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("periodo", periodo);
        d.put("liquidaciones", filas);
        d.put("totales", totales);
        d.put("nota", "Desde el periodo julio de 2026 el IGV retenido se paga por SUNAT Virtual vinculado a cada liquidación de compra "
                + "y se declara en el Formulario Virtual 617; no se paga en bancos con NPS (RS 000047-2026/SUNAT). La renta retenida "
                + "se declara y paga en el PDT/Formulario 617 del periodo.");
        return ApiResponse.success(d);
    }

    // ---------------------------------------------------------------- recurso

    Map<String, Object> resource(Map<String, Object> s, boolean withItems) {
        long id = ((Number) s.get("id")).longValue();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("tipo_documento", "04");
        m.put("serie", s.get("serie"));
        m.put("correlativo", ((Number) s.get("correlativo")).longValue());
        m.put("numero_completo", s.get("serie") + "-" + s.get("correlativo"));
        m.put("fecha_emision", ((LocalDateTime) s.get("fecha_emision")).format(Clock.Y_M_D_H_I_S));
        m.put("tipo_operacion", s.get("tipo_operacion"));
        m.put("tipo_moneda", s.get("tipo_moneda"));
        m.put("vendedor", vendedor(s));
        Map<String, Object> lugar = new LinkedHashMap<>();
        lugar.put("direccion", s.get("lugar_direccion"));
        lugar.put("ubigeo", s.get("lugar_ubigeo"));
        lugar.put("tipo", s.get("lugar_tipo"));
        m.put("lugar", lugar);
        m.put("productos_agropecuarios", s.get("productos_agropecuarios"));
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("gravadas", f(s.get("mto_oper_gravadas")));
        t.put("exoneradas", f(s.get("mto_oper_exoneradas")));
        t.put("inafectas", f(s.get("mto_oper_inafectas")));
        t.put("porcentaje_igv", f(s.get("porcentaje_igv")));
        t.put("igv", f(s.get("mto_igv")));
        t.put("valor_venta", f(s.get("valor_venta")));
        t.put("sub_total", f(s.get("sub_total")));
        t.put("base_renta", f(s.get("mto_base_renta")));
        t.put("porcentaje_renta", f(s.get("porcentaje_renta")));
        t.put("retencion_renta", f(s.get("mto_retencion_renta")));
        t.put("neto_a_pagar", f(s.get("mto_imp_neto")));
        m.put("totales", t);
        if (withItems) {
            List<Object> items = new ArrayList<>();
            for (Map<String, Object> it : repo.items(id)) {
                Map<String, Object> i = new LinkedHashMap<>();
                i.put("codigo", it.get("codigo"));
                i.put("cod_producto_sunat", it.get("cod_producto_sunat"));
                i.put("descripcion", it.get("descripcion"));
                i.put("unidad", it.get("unidad"));
                i.put("cantidad", ((BigDecimal) it.get("cantidad")).stripTrailingZeros().doubleValue());
                i.put("valor_unitario", ((BigDecimal) it.get("valor_unitario")).stripTrailingZeros().doubleValue());
                i.put("precio_unitario", ((BigDecimal) it.get("precio_unitario")).stripTrailingZeros().doubleValue());
                i.put("tip_afe_igv", it.get("tip_afe_igv"));
                i.put("valor_venta", f(it.get("valor_venta")));
                i.put("igv", f(it.get("igv")));
                i.put("porcentaje_renta", f(it.get("porcentaje_renta")));
                i.put("renta", f(it.get("renta")));
                if (it.get("oro") != null) {
                    i.put("oro", it.get("oro"));
                }
                items.add(i);
            }
            m.put("items", items);
        }
        m.put("guias", s.get("guias"));
        m.put("placa_vehiculo", s.get("placa_vehiculo"));
        m.put("leyenda", s.get("leyenda"));
        m.put("observacion", s.get("observacion"));
        Map<String, Object> sunat = new LinkedHashMap<>();
        sunat.put("estado", s.get("sunat_status"));
        sunat.put("codigo", s.get("sunat_code"));
        sunat.put("descripcion", s.get("sunat_description"));
        sunat.put("notas", s.get("sunat_notes"));
        sunat.put("hash_cpe", s.get("hash_cpe"));
        sunat.put("fecha_cdr", s.get("cdr_fecha") == null ? null : ((LocalDateTime) s.get("cdr_fecha")).format(Clock.Y_M_D_H_I_S));
        m.put("sunat", sunat);
        LocalDate emision = ((LocalDateTime) s.get("fecha_emision")).toLocalDate();
        Map<String, Object> rev = new LinkedHashMap<>();
        rev.put("plazo_hasta", emision.plusDays(props.reversionDias()).toString());
        rev.put("disponible", "aceptado".equals(s.get("sunat_status")) && !Clock.todayLima().isAfter(emision.plusDays(props.reversionDias()))
                && ((BigDecimal) s.get("monto_pagado")).signum() == 0);
        m.put("reversion", rev);
        String base = baseUrl() + "/api/v1/liquidaciones-compra/" + id;
        Map<String, Object> archivos = new LinkedHashMap<>();
        archivos.put("xml", s.get("xml_path") != null ? base + "/xml" : null);
        archivos.put("cdr", s.get("cdr_path") != null ? base + "/cdr" : null);
        archivos.put("pdf", s.get("pdf_path") != null ? base + "/pdf" : null);
        m.put("archivos", archivos);
        m.put("estado_pago", s.get("payment_status"));
        m.put("monto_pagado", f(s.get("monto_pagado")));
        m.put("enviado_en", s.get("sent_at") == null ? null : Clock.iso8601Utc((LocalDateTime) s.get("sent_at")));
        m.put("creado_en", Clock.iso8601Utc((LocalDateTime) s.get("created_at")));
        return m;
    }

    private static Map<String, Object> vendedor(Map<String, Object> s) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("tipo_doc", s.get("vendedor_tipo_doc"));
        v.put("num_doc", s.get("vendedor_num_doc"));
        v.put("nombre", s.get("vendedor_nombre"));
        v.put("direccion", s.get("vendedor_direccion"));
        v.put("ubigeo", s.get("vendedor_ubigeo"));
        v.put("tipo_domicilio", s.get("vendedor_tipo_domicilio"));
        return v;
    }

    private Map<String, Object> find(HttpServletRequest http, long id) {
        return find(ApiContext.requireTenant(http), id);
    }

    private Map<String, Object> find(Tenant tenant, long id) {
        return repo.find(tenant.getId(), id).orElseThrow(ApiException::notFound);
    }

    static boolean numeric(Object v) {
        return v instanceof Number || v instanceof String s && Php.isNumericString(s);
    }

    static double f(Object v) {
        return v instanceof BigDecimal b ? b.setScale(2, RoundingMode.HALF_UP).doubleValue() : v == null ? 0 : Php.toFloat(v);
    }

    static LocalDate date(Object v) {
        if (!(v instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            return PhpDates.parseDate(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
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

    private static ResponseEntity<Object> internal(String mensaje, RuntimeException e) {
        String ref = UUID.randomUUID().toString();
        log.error("{} error_id={}", mensaje, ref, e);
        return ApiResponse.error(mensaje.replaceAll("\\.$", "") + " (ref: " + ref + ")", 500, Map.of("error_id", ref));
    }
}
