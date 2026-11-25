package pe.facturacion.api.modules.retper;

import jakarta.servlet.http.HttpServletRequest;
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
import pe.facturacion.api.modules.catalog.SunatRules;
import pe.facturacion.api.modules.plan.EmissionPolicy;
import pe.facturacion.api.modules.plan.PlanService;
import pe.facturacion.api.modules.serie.SerieRepository;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiException;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.api.Paginator;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.php.PhpMath;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.shared.validation.Validator;
import pe.facturacion.api.sunat.builder.PhpDates;

/**
 * {@code RetentionController} / {@code PerceptionController} con su FormRequest y su
 * {@code Create*Action}.
 *
 * <p>Correcciones: un documento en moneda extranjera exige {@code tipo_cambio} y lo retenido o
 * percibido se calcula en soles (API-PRO aplicaba la tasa al importe en dólares y lo informaba
 * como PEN); el mensaje del envío manual apunta a la ruta real ({@code /retenciones/{id}/enviar});
 * {@code per_page} tiene tope 100; un error interno no expone el mensaje de la excepción.
 */
@Service
public class RetPerEndpoints {

    private static final Logger log = LoggerFactory.getLogger(RetPerEndpoints.class);

    private final RetPerRepository repo;
    private final SerieRepository series;
    private final JdbcTemplate jdbc;
    private final PlanService plans;
    private final EmissionPolicy emission;
    private final SendRetPerJob sendJob;
    private final FileStorage storage;
    private final TransactionTemplate tx;

    public RetPerEndpoints(RetPerRepository repo, SerieRepository series, JdbcTemplate jdbc, PlanService plans, EmissionPolicy emission,
            SendRetPerJob sendJob, FileStorage storage, TransactionTemplate tx) {
        this.repo = repo;
        this.series = series;
        this.jdbc = jdbc;
        this.plans = plans;
        this.emission = emission;
        this.sendJob = sendJob;
        this.storage = storage;
        this.tx = tx;
    }

    // ------------------------------------------------------------ validación

    static Map<String, Object> rules(RetPerKind k) {
        String t = k.tercero;
        String d = "documentos.*.";
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("serie", "required|string|size:4|regex:/^" + k.prefijoSerie + "[A-Z0-9]{3}$/");
        r.put("correlativo", "nullable|integer|min:1");
        r.put("cod_local", "nullable|string|size:4");
        r.put("fecha_emision", "required|date");
        r.put(t + ".tipo_doc", List.of("required", "string", SunatRules.catalog("06")));
        r.put(t + ".num_doc", "required|string|max:15");
        r.put(t + ".razon_social", "required|string|max:1500");
        r.put(t + ".direccion", "nullable|string|max:500");
        r.put("regimen", List.of("required", "string", SunatRules.catalog(k.perception ? "22" : "23")));
        r.put("tasa", "required|numeric|min:0|max:100");
        r.put("observacion", "nullable|string|max:500");
        r.put("documentos", "required|array|min:1");
        r.put(d + "tipo_doc", "required|string|in:01,03,12");
        r.put(d + "num_doc", "required|string");
        r.put(d + "fecha_emision", "required|date");
        r.put(d + "imp_total", "required|numeric|gt:0");
        r.put(d + "moneda", "nullable|string|in:PEN,USD,EUR");
        r.put(d + k.pagos, "required|array|min:1");
        r.put(d + k.pagos + ".*.moneda", "nullable|string|in:PEN,USD,EUR");
        r.put(d + k.pagos + ".*.importe", "required|numeric|gt:0");
        r.put(d + k.pagos + ".*.fecha", "required|date");
        r.put(d + k.fechaItem, "required|date");
        r.put(d + k.itemMonto, "nullable|numeric|min:0");
        r.put(d + k.itemNeto, "nullable|numeric|min:0");
        r.put(d + "tipo_cambio", "nullable|array");
        r.put(d + "tipo_cambio.moneda_ref", "required_with:documentos.*.tipo_cambio|string");
        r.put(d + "tipo_cambio.moneda_obj", "required_with:documentos.*.tipo_cambio|string");
        r.put(d + "tipo_cambio.factor", "required_with:documentos.*.tipo_cambio|numeric|gt:0");
        r.put(d + "tipo_cambio.fecha", "required_with:documentos.*.tipo_cambio|date");
        return r;
    }

    Map<String, Object> validate(RetPerKind k, Tenant tenant, Map<String, Object> input) {
        Validator v = Validator.make(input, rules(k));
        v.after(val -> {
            validarSerieRegistrada(val, k, tenant);
            validarCorrelativoManual(val, k, tenant);
            validarTipoCambio(val);
        });
        return v.validate();
    }

    /** {@code ValidatesSerieRegistrada}. */
    private void validarSerieRegistrada(Validator v, RetPerKind k, Tenant tenant) {
        Object serie = v.input("serie");
        if (tenant == null || !(serie instanceof String s) || s.isEmpty()) {
            return;
        }
        var found = series.findByTenantIdAndTipoDocumentoAndSerie(tenant.getId(), k.tipoDocumento, s);
        if (found.isPresent() && found.get().isActive()) {
            return;
        }
        if (found.isPresent()) {
            v.addError("serie", "La serie " + s + " está desactivada. Actívala en el módulo Series para poder emitir " + k.plural + ".");
            return;
        }
        boolean tieneAlguna = !series.list(tenant.getId(), false, null, k.tipoDocumento).isEmpty();
        v.addError("serie", tieneAlguna
                ? "No tienes registrada la serie " + s + " para " + k.plural + ". Revisa el número de serie o regístrala en el módulo Series."
                : "Aún no tienes ninguna serie registrada para " + k.plural + ". Crea una serie de " + k.plural + " en el módulo Series antes de emitir.");
    }

    /** {@code ValidatesManualCorrelativo}. */
    private void validarCorrelativoManual(Validator v, RetPerKind k, Tenant tenant) {
        Object correlativo = v.input("correlativo");
        Object serie = v.input("serie");
        if (correlativo == null || "".equals(correlativo) || tenant == null || !Php.truthy(serie)) {
            return;
        }
        if (repo.existsCorrelativo(k, tenant.getId(), Php.toStr(serie), Php.toInt(correlativo))) {
            v.addError("correlativo", "El correlativo " + Php.toStr(correlativo) + " ya existe para la serie " + Php.toStr(serie)
                    + ". Use otro número o deje el campo vacío para autoincremento.");
        }
    }

    /** Corrección: moneda distinta de PEN sin tipo de cambio → SUNAT no puede convertir a soles. */
    private static void validarTipoCambio(Validator v) {
        int i = 0;
        for (Object o : asList(v.input("documentos"))) {
            if (o instanceof Map<?, ?> doc) {
                Object moneda = doc.get("moneda");
                if (moneda instanceof String m && !m.isEmpty() && !"PEN".equals(m) && Php.empty(doc.get("tipo_cambio"))) {
                    v.addError("documentos." + i + ".tipo_cambio",
                            "El tipo de cambio es obligatorio cuando la moneda del documento es " + m + " (los importes se informan en soles).");
                }
            }
            i++;
        }
    }

    // ------------------------------------------------------------- creación

    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> store(RetPerKind k, HttpServletRequest http) {
        Tenant tenant = ApiContext.requireTenant(http);
        return store(k, tenant, Input.of(http));
    }

    /** Emisión de una retención o percepción de la empresa indicada con la misma tubería de {@code /api/v1} (la usa también el panel). */
    public ResponseEntity<Object> store(RetPerKind k, Tenant tenant, Input in) {
        ResponseEntity<Object> denied = emission.deny(tenant);
        if (denied != null) {
            return denied;
        }
        Map<String, Object> data = validate(k, tenant, in.all());
        boolean enviarAuto = in.all().containsKey("enviar_automatico") ? in.bool("enviar_automatico") : true;
        try {
            long id = tx.execute(st -> create(k, tenant, data, enviarAuto));
            String msg = enviarAuto
                    ? k.titulo + " creada y encolada para envío a SUNAT."
                    : k.titulo + " creada en estado pendiente. Use POST /" + k.plural + "/{id}/enviar para enviarla a SUNAT.";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("estado", "exito");
            body.put("mensaje", msg);
            body.put("datos", format(k, repo.findById(k, id).orElseThrow(), null));
            return ResponseEntity.status(201).body(body);
        } catch (RuntimeException e) {
            String ref = UUID.randomUUID().toString();
            log.error("No se pudo crear la {}. error_id={}", k.nombre, ref, e);
            return ApiResponse.error("Error al crear " + k.nombre + ". (ref: " + ref + ")", 500, Map.of("error_id", ref));
        }
    }

    /** {@code Create*Action::execute()} dentro de la transacción. */
    @SuppressWarnings("unchecked")
    private long create(RetPerKind k, Tenant tenant, Map<String, Object> data, boolean enviarAuto) {
        List<Map<String, Object>> locked = jdbc.queryForList(
                "SELECT id, correlativo FROM series WHERE tenant_id = ? AND tipo_documento = ? AND serie = ? "
                        + "AND is_active = true LIMIT 1 FOR UPDATE", tenant.getId(), k.tipoDocumento, Php.toStr(data.get("serie")));
        if (locked.isEmpty()) {
            throw ApiException.notFound();
        }
        long serieId = ((Number) locked.get(0).get("id")).longValue();
        int actual = ((Number) locked.get(0).get("correlativo")).intValue();
        int correlativo;
        Object manual = data.get("correlativo");
        if (manual == null) {
            correlativo = actual + 1;
        } else {
            correlativo = (int) Php.toInt(manual);
        }
        if (correlativo > actual) {
            jdbc.update("UPDATE series SET correlativo = ?, updated_at = ? WHERE id = ?", correlativo, Clock.nowUtc().withNano(0), serieId);
        }
        double tasa = Php.toFloat(data.get("tasa"));
        List<Map<String, Object>> documentos = (List<Map<String, Object>>) data.get("documentos");
        double totalMonto = 0;
        double totalNeto = 0;
        List<double[]> montos = new ArrayList<>();
        for (Map<String, Object> doc : documentos) {
            double[] m = montos(k, doc, tasa);
            montos.add(m);
            totalMonto += m[0];
            totalNeto += m[1];
        }
        Map<String, Object> tercero = (Map<String, Object>) data.get(k.tercero);
        String t = k.tercero;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("tenant_id", tenant.getId());
        c.put("serie", Php.toStr(data.get("serie")));
        c.put("correlativo", correlativo);
        c.put("cod_local", Php.toStr(Php.coalesce(data, "cod_local", "0000")));
        c.put("fecha_emision", resolveEmisionDateTime(Php.toStr(data.get("fecha_emision"))));
        c.put(t + "_tipo_doc", Php.toStr(tercero.get("tipo_doc")));
        c.put(t + "_num_doc", Php.toStr(tercero.get("num_doc")));
        c.put(t + "_razon_social", Php.toStr(tercero.get("razon_social")));
        c.put(t + "_direccion", tercero.get("direccion") == null ? null : Php.toStr(tercero.get("direccion")));
        c.put("regimen", Php.toStr(data.get("regimen")));
        c.put("tasa", tasa);
        c.put(k.totalMonto, PhpMath.round(totalMonto, 2));
        c.put(k.totalNeto, PhpMath.round(totalNeto, 2));
        c.put("observacion", data.get("observacion") == null ? null : Php.toStr(data.get("observacion")));
        c.put("sunat_status", "pendiente");
        long id = repo.insert(k.table, c);
        for (int i = 0; i < documentos.size(); i++) {
            Map<String, Object> doc = documentos.get(i);
            Map<String, Object> it = new LinkedHashMap<>();
            it.put(k.foreignKey, id);
            it.put("tipo_doc", Php.toStr(doc.get("tipo_doc")));
            it.put("num_doc", Php.toStr(doc.get("num_doc")));
            it.put("fecha_emision_doc", PhpDates.parseDate(Php.toStr(doc.get("fecha_emision"))));
            it.put("imp_total", Php.toFloat(doc.get("imp_total")));
            it.put("moneda", Php.toStr(Php.coalesce(doc, "moneda", "PEN")));
            it.put(k.pagos, doc.get(k.pagos));
            it.put(k.fechaItem, PhpDates.parseDate(Php.toStr(doc.get(k.fechaItem))));
            it.put(k.itemMonto, montos.get(i)[0]);
            it.put(k.itemNeto, montos.get(i)[1]);
            it.put("tipo_cambio", doc.get("tipo_cambio"));
            repo.insert(k.itemsTable, it);
        }
        plans.incrementDocumentUsage(tenant);
        if (enviarAuto) {
            sendJob.dispatch(k, id);
            repo.update(k, id, Map.of("sunat_status", "enviado"));
        }
        return id;
    }

    /**
     * Importe retenido/percibido y neto pagado/cobrado de un documento: los enviados o
     * {@code round(total × tasa / 100, 2)} y {@code total ∓ monto}. En moneda extranjera la base
     * se convierte a soles con el factor del tipo de cambio.
     */
    @SuppressWarnings("unchecked")
    static double[] montos(RetPerKind k, Map<String, Object> doc, double tasa) {
        double impTotal = Php.toFloat(doc.get("imp_total"));
        Object moneda = doc.get("moneda");
        if (moneda instanceof String m && !m.isEmpty() && !"PEN".equals(m) && doc.get("tipo_cambio") instanceof Map<?, ?> tc) {
            impTotal = PhpMath.round(impTotal * Php.toFloat(((Map<String, Object>) tc).get("factor")), 2);
        }
        Object monto = doc.get(k.itemMonto);
        double impMonto = monto != null ? Php.toFloat(monto) : PhpMath.round(impTotal * tasa / 100, 2);
        Object neto = doc.get(k.itemNeto);
        double impNeto = neto != null ? Php.toFloat(neto) : PhpMath.round(k.perception ? impTotal + impMonto : impTotal - impMonto, 2);
        return new double[] {impMonto, impNeto};
    }

    /** {@code ResolvesEmisionDateTime}: solo fecha → hora actual de Lima. */
    static LocalDateTime resolveEmisionDateTime(String fecha) {
        if (fecha.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return LocalDate.parse(fecha).atTime(Clock.nowLima().toLocalTime().withNano(0));
        }
        return PhpDates.parseDateTime(fecha);
    }

    // ------------------------------------------------------------- consultas

    public ResponseEntity<Object> enviar(RetPerKind k, HttpServletRequest http, long id) {
        return enviar(k, ApiContext.requireTenant(http), id);
    }

    /** Enviar o reenviar a SUNAT una retención o percepción de la empresa indicada (404 si es de otra). */
    public ResponseEntity<Object> enviar(RetPerKind k, Tenant tenant, long id) {
        Map<String, Object> row = repo.find(k, tenant.getId(), id).orElseThrow(ApiException::notFound);
        if ("aceptado".equals(row.get("sunat_status"))) {
            return ApiResponse.error("Esta " + k.nombre + " ya fue aceptada por SUNAT.", 422);
        }
        // Corrección: revertida (o en reversión) no se vuelve a enviar; reenviarla la revivía como "aceptado"
        if ("anulado".equals(row.get("sunat_status")) || "anulacion_en_proceso".equals(row.get("sunat_status"))) {
            return ApiResponse.error("Esta " + k.nombre + " está revertida (o en proceso de reversión) y no se puede enviar a SUNAT.", 422);
        }
        tx.executeWithoutResult(st -> {
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("sunat_status", "enviado");
            ch.put("sunat_code", null);
            ch.put("sunat_description", null);
            repo.update(k, id, ch);
            sendJob.dispatch(k, id);
        });
        return ApiResponse.success(format(k, repo.findById(k, id).orElseThrow(), null), k.titulo + " enviada a SUNAT.");
    }

    public ResponseEntity<Object> index(RetPerKind k, HttpServletRequest http) {
        Tenant tenant = ApiContext.requireTenant(http);
        return index(k, tenant, Input.of(http));
    }

    /** Listado de la empresa indicada (lo usa también el panel de administración con la misma respuesta). */
    public ResponseEntity<Object> index(RetPerKind k, Tenant tenant, Input in) {
        Map<String, Object> all = in.all();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", tenant.getId());
        List<String> w = new ArrayList<>(List.of("tenant_id = :t"));
        // $request->has(): la clave presente (aunque vacía) aplica el filtro
        if (all.containsKey("sunat_status")) {
            p.addValue("st", in.get("sunat_status") == null ? null : Php.toStr(in.get("sunat_status")));
            w.add("sunat_status = :st");
        }
        if (all.containsKey("fecha_desde") && all.containsKey("fecha_hasta")) {
            p.addValue("fd", Php.toStr(in.get("fecha_desde")) + " 00:00:00").addValue("fh", Php.toStr(in.get("fecha_hasta")) + " 23:59:59");
            w.add("fecha_emision BETWEEN CAST(:fd AS timestamp) AND CAST(:fh AS timestamp)");
        }
        if (all.containsKey("serie")) {
            p.addValue("serie", in.get("serie") == null ? null : Php.toStr(in.get("serie")));
            w.add("serie = :serie");
        }
        String numDoc = k.tercero + "_num_doc";
        if (all.containsKey(numDoc)) {
            p.addValue("nd", in.get(numDoc) == null ? null : Php.toStr(in.get(numDoc)));
            w.add(numDoc + " = :nd");
        }
        String where = String.join(" AND ", w);
        int perPage = perPage(in.get("per_page"));
        int page = Paginator.page(in);
        long total = repo.count(k, where, p);
        List<Object> datos = new ArrayList<>();
        for (Map<String, Object> r : repo.query(k, where, p, perPage, (page - 1) * perPage)) {
            datos.add(format(k, r, null));
        }
        Map<String, Object> paginacion = new LinkedHashMap<>();
        paginacion.put("total", total);
        paginacion.put("pagina_actual", page);
        paginacion.put("ultima_pagina", (int) Math.max(Math.ceil((double) total / perPage), 1));
        paginacion.put("por_pagina", perPage);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("datos", datos);
        d.put("paginacion", paginacion);
        return ApiResponse.success(d);
    }

    /** {@code $request->integer('per_page', 15)}: inválido o ≤ 0 → 15; tope 100 (API-PRO no lo tenía). */
    static int perPage(Object raw) {
        int n = raw == null ? 15 : (int) Php.toInt(raw);
        return n <= 0 ? 15 : Math.min(n, 100);
    }

    public ResponseEntity<Object> show(RetPerKind k, HttpServletRequest http, long id) {
        return show(k, ApiContext.requireTenant(http), id);
    }

    public ResponseEntity<Object> show(RetPerKind k, Tenant tenant, long id) {
        Map<String, Object> row = repo.find(k, tenant.getId(), id).orElseThrow(ApiException::notFound);
        return ApiResponse.success(format(k, row, repo.items(k, id)));
    }

    public ResponseEntity<Object> xml(RetPerKind k, HttpServletRequest http, long id) {
        return xml(k, ApiContext.requireTenant(http), id);
    }

    public ResponseEntity<Object> xml(RetPerKind k, Tenant tenant, long id) {
        Map<String, Object> row = repo.find(k, tenant.getId(), id).orElseThrow(ApiException::notFound);
        String path = (String) row.get("xml_path");
        if (path == null || !storage.exists(Disk.PRIVATE, path)) {
            return ApiResponse.error("XML no disponible", 404);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/xml")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + numero(row) + ".xml\"")
                .body(new String(storage.get(Disk.PRIVATE, path), java.nio.charset.StandardCharsets.UTF_8));
    }

    public ResponseEntity<Object> cdr(RetPerKind k, HttpServletRequest http, long id) {
        return cdr(k, ApiContext.requireTenant(http), id);
    }

    public ResponseEntity<Object> cdr(RetPerKind k, Tenant tenant, long id) {
        Map<String, Object> row = repo.find(k, tenant.getId(), id).orElseThrow(ApiException::notFound);
        String path = (String) row.get("cdr_path");
        if (path == null || !storage.exists(Disk.PRIVATE, path)) {
            return ApiResponse.error("CDR no disponible", 404);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"R-" + numero(row) + ".zip\"")
                .body(storage.get(Disk.PRIVATE, path));
    }

    /** {@code numero_completo}: serie + correlativo con 8 dígitos. */
    static String numero(Map<String, Object> row) {
        return row.get("serie") + "-" + String.format("%08d", ((Number) row.get("correlativo")).longValue());
    }

    /** {@code formatRetention()} / {@code formatPerception()}. */
    static Map<String, Object> format(RetPerKind k, Map<String, Object> r, List<Map<String, Object>> items) {
        String t = k.tercero;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ((Number) r.get("id")).longValue());
        m.put("serie", r.get("serie"));
        m.put("correlativo", ((Number) r.get("correlativo")).longValue());
        m.put("numero", numero(r));
        m.put("fecha_emision", ((LocalDateTime) r.get("fecha_emision")).toLocalDate().toString());
        Map<String, Object> tercero = new LinkedHashMap<>();
        tercero.put("tipo_doc", r.get(t + "_tipo_doc"));
        tercero.put("num_doc", r.get(t + "_num_doc"));
        tercero.put("razon_social", r.get(t + "_razon_social"));
        m.put(t, tercero);
        m.put("regimen", r.get("regimen"));
        m.put("tasa", RetPerRepository.f(r.get("tasa")));
        m.put(k.totalMonto, RetPerRepository.f(r.get(k.totalMonto)));
        m.put(k.totalNeto, RetPerRepository.f(r.get(k.totalNeto)));
        m.put("sunat_status", r.get("sunat_status"));
        m.put("sunat_code", r.get("sunat_code"));
        m.put("created_at", Clock.iso8601Utc((LocalDateTime) r.get("created_at")));
        if (items != null) {
            List<Object> docs = new ArrayList<>();
            for (Map<String, Object> it : items) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("tipo_doc", it.get("tipo_doc"));
                d.put("num_doc", it.get("num_doc"));
                d.put("fecha_emision", ((LocalDate) it.get("fecha_emision_doc")).toString());
                d.put("imp_total", RetPerRepository.f(it.get("imp_total")));
                d.put("moneda", it.get("moneda"));
                d.put(k.fechaItem, ((LocalDate) it.get(k.fechaItem)).toString());
                d.put(k.itemMonto, RetPerRepository.f(it.get(k.itemMonto)));
                d.put(k.itemNeto, RetPerRepository.f(it.get(k.itemNeto)));
                docs.add(d);
            }
            m.put("documentos", docs);
        }
        return m;
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
}
