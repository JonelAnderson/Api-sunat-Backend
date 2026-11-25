package pe.facturacion.api.modules.ticket;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.document.SaleDocument;
import pe.facturacion.api.modules.document.SaleDocumentRepository;
import pe.facturacion.api.modules.plan.EmissionPolicy;
import pe.facturacion.api.modules.ticket.TicketRepository.TicketDoc;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiException;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.shared.validation.Validator;

/**
 * {@code SummaryController}: resumen diario de boletas (envío de pendientes o anulación de aceptadas).
 *
 * <p>Correcciones: los plazos de 7 días se comparan como fechas de Lima (API-PRO interpretaba la
 * fecha recibida en UTC y rechazaba el último día del plazo), y un resumen de anulación deja las
 * boletas en {@code anulacion_en_proceso} hasta que SUNAT responde.
 */
@Service
public class SummaryEndpoints {

    private static final Logger log = LoggerFactory.getLogger(SummaryEndpoints.class);

    private final TicketRepository tickets;
    private final SaleDocumentRepository documents;
    private final TicketJobs jobs;
    private final EmissionPolicy emission;
    private final FileStorage storage;
    private final TransactionTemplate tx;

    public SummaryEndpoints(TicketRepository tickets, SaleDocumentRepository documents, TicketJobs jobs, EmissionPolicy emission,
            FileStorage storage, TransactionTemplate tx) {
        this.tickets = tickets;
        this.documents = documents;
        this.jobs = jobs;
        this.emission = emission;
        this.storage = storage;
        this.tx = tx;
    }

    public ResponseEntity<Object> index(HttpServletRequest http) {
        Tenant tenant = ApiContext.requireTenant(http);
        return index(tenant, Input.of(http));
    }

    /** Listado de la empresa indicada (lo usa también el panel de administración con la misma respuesta). */
    public ResponseEntity<Object> index(Tenant tenant, Input in) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", tenant.getId());
        List<String> w = new ArrayList<>(List.of("tenant_id = :t"));
        if (Php.truthy(in.get("mes"))) {
            p.addValue("mes", Php.toStr(in.get("mes")));
            w.add("to_char(fecha_envio, 'YYYY-MM') = :mes");
        }
        if (Php.truthy(in.get("tipo"))) {
            p.addValue("tipo", Php.toStr(in.get("tipo")));
            w.add("tipo = :tipo");
        }
        String where = String.join(" AND ", w);
        int perPage = perPage(in.get("por_pagina"));
        int page = pe.facturacion.api.shared.api.Paginator.page(in);
        long total = tickets.count(TicketRepository.SUMMARIES, where, p);
        List<Object> datos = new ArrayList<>();
        for (TicketDoc s : tickets.query(TicketRepository.SUMMARIES, where, p, "created_at desc, id asc", perPage, (page - 1) * perPage)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id);
            m.put("identifier", s.identifier);
            m.put("tipo", s.tipo);
            m.put("fecha_referencia", s.fechaReferencia.toString());
            m.put("fecha_envio", s.fechaEnvio.toString());
            m.put("total_documentos", s.totalDocumentos);
            m.put("ticket", s.ticket);
            m.put("estado_sunat", s.sunatStatus);
            m.put("codigo_sunat", s.sunatCode);
            m.put("descripcion_sunat", s.sunatDescription);
            m.put("creado_en", Clock.iso8601Utc(s.createdAt));
            datos.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "exito");
        body.put("datos", datos);
        body.put("paginacion", map("total", total, "pagina_actual", page,
                "ultima_pagina", (int) Math.max(Math.ceil((double) total / perPage), 1)));
        return ResponseEntity.ok(body);
    }

    /** {@code paginate($request->query('por_pagina', 15))}: inválido o ≤ 0 → 15; máximo 100. */
    static int perPage(Object raw) {
        int n = raw == null ? 15 : (int) Php.toInt(raw);
        return n <= 0 ? 15 : Math.min(n, 100);
    }

    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> store(HttpServletRequest http) {
        Tenant tenant = ApiContext.requireTenant(http);
        return store(tenant, Input.of(http));
    }

    /** Resumen diario (envío o anulación de boletas) de la empresa indicada con las mismas reglas y plazos (lo usa también el panel). */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> store(Tenant tenant, Input in) {
        ResponseEntity<Object> denied = emission.deny(tenant);
        if (denied != null) {
            return denied;
        }
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("fecha_resumen", "required|date");
        rules.put("anular", "nullable|array");
        rules.put("anular.*.id", "required_with:anular|integer");
        rules.put("anular.*.motivo", "required_with:anular|string|max:255");
        rules.put("anular.*.tipo_documento", "nullable|string|in:03,07,08");
        Validator.make(in.all(), rules).validate();

        LocalDate fechaResumen = pe.facturacion.api.sunat.builder.PhpDates.parseDate(Php.toStr(in.get("fecha_resumen")));
        boolean enviarAuto = in.all().containsKey("enviar_automatico") ? in.bool("enviar_automatico") : true;
        LocalDate hoy = Clock.todayLima();
        LocalDate limiteAnterior = hoy.minusDays(7);
        if (fechaResumen.isBefore(limiteAnterior) || fechaResumen.isAfter(hoy)) {
            return ApiResponse.error("SUNAT solo permite resumen diario del mismo día de emisión o hasta 7 días calendario después. Fecha límite: "
                    + limiteAnterior, 422);
        }
        boolean isAnulacion = Php.truthy(in.get("anular"));
        try {
            List<SaleDocument> boletas;
            if (isAnulacion) {
                List<Long> ids = new ArrayList<>();
                for (Object o : asList(in.get("anular"))) {
                    if (o instanceof Map<?, ?> m && m.get("id") != null) {
                        ids.add(Php.toInt(m.get("id")));
                    }
                }
                boletas = ids.isEmpty() ? List.of() : documents.query(DocumentKind.BOLETA,
                        "tenant_id = :t AND id IN (:ids) AND sunat_status = 'aceptado' AND deleted_at IS NULL",
                        new MapSqlParameterSource().addValue("t", tenant.getId()).addValue("ids", ids), "id", 1000, 0);
                if (boletas.isEmpty()) {
                    return ApiResponse.error("No se encontraron boletas aceptadas por SUNAT para anular.", 422);
                }
                List<String> errores = new ArrayList<>();
                for (SaleDocument b : boletas) {
                    String ref = b.serie + "-" + b.correlativo;
                    LocalDate emision = b.fechaEmision.toLocalDate();
                    if (emision.isBefore(limiteAnterior)) {
                        errores.add("Boleta " + ref + " ya pasó el plazo de 7 días para anulación (emitida el " + emision + ").");
                        continue;
                    }
                    if (documents.count(DocumentKind.NOTA_CREDITO, "tenant_id = :t AND doc_afectado_tipo = '03' AND doc_afectado_serie = :s "
                            + "AND doc_afectado_correlativo = :c AND deleted_at IS NULL", new MapSqlParameterSource().addValue("t", tenant.getId())
                            .addValue("s", b.serie).addValue("c", String.valueOf(b.correlativo))) > 0) {
                        errores.add("La boleta " + ref + " tiene una nota de crédito asociada. Usa la NC en vez de anularla.");
                        continue;
                    }
                    List<TicketDoc> dup = tickets.all(TicketRepository.SUMMARIES, "tenant_id = :t AND tipo = 'anulacion' "
                            + "AND sunat_status IN ('pendiente', 'enviado', 'aceptado') AND document_ids @> CAST(:id AS jsonb)",
                            new MapSqlParameterSource().addValue("t", tenant.getId()).addValue("id", "[" + b.id + "]"));
                    if (!dup.isEmpty()) {
                        errores.add("Ya existe un resumen de anulación para la boleta " + ref + " (" + dup.get(dup.size() - 1).identifier + ").");
                    }
                }
                if (!errores.isEmpty()) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("estado", "error");
                    body.put("mensaje", "No se puede anular: " + String.join(" ", errores));
                    body.put("errores", errores);
                    return ResponseEntity.status(422).body(body);
                }
            } else {
                boletas = documents.query(DocumentKind.BOLETA, "tenant_id = :t AND CAST(fecha_emision AS date) = :f "
                        + "AND sunat_status = 'pendiente' AND deleted_at IS NULL",
                        new MapSqlParameterSource().addValue("t", tenant.getId()).addValue("f", fechaResumen), "id", 100000, 0);
                if (boletas.isEmpty()) {
                    return ApiResponse.error("No hay boletas pendientes para la fecha " + fechaResumen + ".", 422);
                }
                List<Long> yaEnResumen = new ArrayList<>();
                for (TicketDoc s : tickets.all(TicketRepository.SUMMARIES, "tenant_id = :t AND tipo = 'envio' AND fecha_referencia = :f "
                        + "AND sunat_status IN ('pendiente', 'enviado', 'aceptado')",
                        new MapSqlParameterSource().addValue("t", tenant.getId()).addValue("f", fechaResumen))) {
                    for (Object id : asList(s.documentIds)) {
                        yaEnResumen.add(Php.toInt(id));
                    }
                }
                boletas = boletas.stream().filter(b -> !yaEnResumen.contains(b.id)).toList();
                if (boletas.isEmpty()) {
                    return ApiResponse.error("Las boletas de la fecha " + fechaResumen + " ya están incluidas en un resumen (en proceso o aceptado). "
                            + "Consulta su estado en vez de generar otro — reenviarlas da el error SUNAT 2282 \"documento ya informado anteriormente\".", 422);
                }
            }

            List<SaleDocument> incluidas = boletas;
            TicketDoc summary = tx.execute(status -> {
                LocalDate fechaEnvio = Clock.todayLima();
                tickets.lockNumbering(TicketRepository.SUMMARIES, tenant.getId());
                String correlativo = pad3(tickets.maxCorrelativo(TicketRepository.SUMMARIES, tenant.getId(), fechaEnvio, "") + 1);
                TicketDoc s = new TicketDoc();
                s.table = TicketRepository.SUMMARIES;
                s.tenantId = tenant.getId();
                s.identifier = "RC-" + fechaEnvio.toString().replace("-", "") + "-" + correlativo;
                s.correlativo = correlativo;
                s.fechaReferencia = fechaResumen;
                s.fechaEnvio = fechaEnvio;
                s.totalDocumentos = incluidas.size();
                s.tipo = isAnulacion ? "anulacion" : "envio";
                s.documentIds = incluidas.stream().map(b -> b.id).toList();
                s.sunatStatus = "pendiente";
                tickets.insert(s);
                if (isAnulacion) {
                    tickets.updateDocuments("boletas", "id IN (:ids)", new MapSqlParameterSource("ids", s.documentIds),
                            Map.of("sunat_status", "anulacion_en_proceso"));
                }
                if (enviarAuto) {
                    jobs.dispatchSend(TicketRepository.SUMMARIES, s.id);
                    tickets.update(s, Map.of("sunat_status", "enviado"));
                    s.sunatStatus = "enviado";
                }
                return s;
            });

            String base = isAnulacion ? "Resumen de anulación" : "Resumen diario";
            String message = enviarAuto ? base + " encolado para envío a SUNAT."
                    : base + " creado en estado pendiente. Use POST /resumenes/{id}/enviar para enviarlo a SUNAT.";
            Map<String, Object> datos = new LinkedHashMap<>();
            datos.put("id_resumen", summary.id);
            datos.put("identifier", summary.identifier);
            datos.put("fecha_envio", summary.fechaEnvio.toString());
            datos.put("fecha_documentos", fechaResumen.toString());
            datos.put("correlativo", summary.correlativo);
            datos.put("accion", isAnulacion ? "anulacion" : "envio");
            datos.put("total_documentos", incluidas.size());
            datos.put("estado_sunat", enviarAuto ? "enviado" : "pendiente");
            List<Object> docs = new ArrayList<>();
            for (SaleDocument b : incluidas) {
                docs.add(documentoResumen(b, isAnulacion));
            }
            datos.put("documentos", docs);
            datos.put("consulta_estado", baseUrl() + "/api/v1/resumenes/" + summary.id + "/estado");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("estado", "exito");
            body.put("mensaje", message);
            body.put("datos", datos);
            return ResponseEntity.status(201).body(body);
        } catch (RuntimeException e) {
            String ref = UUID.randomUUID().toString();
            log.error("No se pudo crear el resumen. error_id={}", ref, e);
            return ApiResponse.error("No se pudo crear el resumen. (ref: " + ref + ")", 500, map("error_id", ref));
        }
    }

    /** Detalle de la boleta como viaja en el XML del resumen. */
    static Map<String, Object> documentoResumen(SaleDocument doc, boolean anulacion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", doc.id);
        m.put("tipo_documento", "03");
        m.put("serie_nro", doc.serie + "-" + doc.correlativo);
        m.put("estado", anulacion ? "3" : "1");
        m.put("cliente", map("tipo_doc", doc.clientTipoDoc, "num_doc", doc.clientNumDoc));
        m.put("mto_oper_gravadas", f(doc.mtoOperGravadas));
        m.put("mto_oper_exoneradas", f(doc.mtoOperExoneradas));
        m.put("mto_oper_inafectas", f(doc.mtoOperInafectas));
        m.put("mto_oper_gratuitas", f(doc.mtoOperGratuitas));
        m.put("mto_igv", f(doc.mtoIgv));
        m.put("total", f(doc.mtoImpVenta));
        m.put("estado_sunat", doc.sunatStatus);
        return m;
    }

    public ResponseEntity<Object> enviar(HttpServletRequest http, long id) {
        return enviar(ApiContext.requireTenant(http), id);
    }

    /** Enviar o reenviar a SUNAT un resumen de la empresa indicada (404 si es de otra). */
    public ResponseEntity<Object> enviar(Tenant tenant, long id) {
        TicketDoc s = tickets.find(TicketRepository.SUMMARIES, tenant.getId(), id).orElseThrow(ApiException::notFound);
        if ("aceptado".equals(s.sunatStatus)) {
            return ApiResponse.error("Este resumen ya fue aceptado por SUNAT.", 422);
        }
        tx.executeWithoutResult(st -> {
            tickets.update(s, map("sunat_status", "enviado", "sunat_code", null, "sunat_description", null));
            jobs.dispatchSend(TicketRepository.SUMMARIES, s.id);
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "exito");
        body.put("mensaje", "Resumen enviado a SUNAT.");
        body.put("datos", map("id_resumen", s.id, "identifier", s.identifier, "estado_sunat", "enviado",
                "consulta_estado", baseUrl() + "/api/v1/resumenes/" + s.id + "/estado"));
        return ResponseEntity.ok(body);
    }

    public ResponseEntity<Object> checkStatus(HttpServletRequest http, long id) {
        return checkStatus(ApiContext.requireTenant(http), id);
    }

    /**
     * Consulta en el momento el ticket de un resumen enviado (como {@code GET /anulaciones/{id}/estado} hace con las
     * bajas) y guarda el resultado con el mismo código del trabajo de consulta ({@code TicketJobs}); luego devuelve el
     * estado guardado. Si el resumen no está enviado o no tiene ticket, solo devuelve el estado. No existe en
     * {@code /api/v1} (allí lo consulta la tarea programada): lo usa el panel de administración.
     */
    public ResponseEntity<Object> refresh(Tenant tenant, long id) {
        TicketDoc s = tickets.find(TicketRepository.SUMMARIES, tenant.getId(), id).orElseThrow(ApiException::notFound);
        if (s.ticket != null && "enviado".equals(s.sunatStatus)) {
            try {
                jobs.pollOnce(s, tenant);
            } catch (RuntimeException e) {
                // Si SUNAT no responde se devuelve el estado guardado
                log.warn("resumen.refrescar.sin_respuesta id={} error={}", s.id, e.getClass().getSimpleName());
            }
        }
        return checkStatus(tenant, id);
    }

    /** Estado guardado del resumen y sus boletas (solo lee la base: no consulta a SUNAT). */
    public ResponseEntity<Object> checkStatus(Tenant tenant, long id) {
        TicketDoc s = tickets.find(TicketRepository.SUMMARIES, tenant.getId(), id).orElseThrow(ApiException::notFound);
        List<Object> docs = new ArrayList<>();
        for (SaleDocument b : boletasDe(s)) {
            docs.add(documentoResumen(b, "anulacion".equals(s.tipo)));
        }
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("id_resumen", s.id);
        datos.put("identifier", s.identifier);
        datos.put("ticket", s.ticket);
        datos.put("estado_sunat", s.sunatStatus);
        datos.put("codigo_sunat", s.sunatCode);
        datos.put("descripcion_sunat", s.sunatDescription);
        datos.put("notas_sunat", s.sunatNotes);
        datos.put("tipo", s.tipo);
        datos.put("total_documentos", s.totalDocumentos);
        datos.put("documentos", docs);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "exito");
        body.put("datos", datos);
        return ResponseEntity.ok(body);
    }

    List<SaleDocument> boletasDe(TicketDoc s) {
        List<Long> ids = new ArrayList<>();
        for (Object o : asList(s.documentIds)) {
            ids.add(Php.toInt(o));
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return documents.query(DocumentKind.BOLETA, "id IN (:ids)", new MapSqlParameterSource("ids", ids), "id", 100000, 0);
    }

    public ResponseEntity<Object> xml(HttpServletRequest http, long id) {
        return xml(ApiContext.requireTenant(http), id);
    }

    public ResponseEntity<Object> xml(Tenant tenant, long id) {
        TicketDoc s = tickets.find(TicketRepository.SUMMARIES, tenant.getId(), id).orElseThrow(ApiException::notFound);
        if (s.xmlPath == null) {
            String motivo = switch (s.sunatStatus) {
                // Corrección: un resumen "pendiente" es manual (enviar_automatico=false) o falló por el
                // certificado; en ninguno de los dos casos la cola lo envía sola (API-PRO decía "espera al worker")
                case "pendiente" -> "CERT_ERROR".equals(s.sunatCode)
                        ? "El resumen no se pudo firmar (certificado). Corrija el certificado y use POST /resumenes/{id}/enviar."
                        : "El resumen está pendiente de envío (creado con enviar_automatico=false). Use POST /resumenes/{id}/enviar para enviarlo a SUNAT.";
                case "enviado" -> "El resumen fue enviado pero el XML no está disponible aún. Consulta el estado con GET /resumenes/{id}/estado.";
                case "rechazado" -> "El resumen fue rechazado por SUNAT y el XML no pudo guardarse. Código: "
                        + (s.sunatCode == null ? "N/A" : s.sunatCode) + ".";
                default -> "XML no generado (estado: " + s.sunatStatus + ").";
            };
            return ApiResponse.error(motivo, 404);
        }
        if (!storage.exists(Disk.PRIVATE, s.xmlPath)) {
            // Sin la ruta interna en el mensaje (API-PRO la mostraba)
            return ApiResponse.error("El XML fue generado pero el archivo no se encuentra en disco.", 404);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/xml")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + s.identifier + ".xml\"")
                .body(new String(storage.get(Disk.PRIVATE, s.xmlPath), java.nio.charset.StandardCharsets.UTF_8));
    }

    public ResponseEntity<Object> cdr(HttpServletRequest http, long id) {
        return cdr(ApiContext.requireTenant(http), id);
    }

    public ResponseEntity<Object> cdr(Tenant tenant, long id) {
        TicketDoc s = tickets.find(TicketRepository.SUMMARIES, tenant.getId(), id).orElseThrow(ApiException::notFound);
        if (s.cdrPath == null || !storage.exists(Disk.PRIVATE, s.cdrPath)) {
            return ApiResponse.error("CDR no disponible", 404);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"R-" + s.identifier + ".zip\"")
                .body(storage.get(Disk.PRIVATE, s.cdrPath));
    }

    static String pad3(int n) {
        String s = String.valueOf(n);
        return s.length() < 3 ? "0".repeat(3 - s.length()) + s : s;
    }

    static double f(java.math.BigDecimal v) {
        return v == null ? 0 : v.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
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

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
