package pe.facturacion.api.modules.ticket;

import static pe.facturacion.api.modules.ticket.SummaryEndpoints.asList;
import static pe.facturacion.api.modules.ticket.SummaryEndpoints.baseUrl;
import static pe.facturacion.api.modules.ticket.SummaryEndpoints.map;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import pe.facturacion.api.modules.audit.AuditLogService;
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
 * {@code VoidedController} + {@code VoidedService}: comunicación de baja (RA) de facturas y notas.
 *
 * <p>Correcciones: {@code fecha_comunicacion} por defecto en hora de Lima (API-PRO usaba UTC), la
 * numeración del día se serializa, el plazo de 7 días se compara como fecha de Lima y la consulta
 * de estado no trata el código 98 ("en proceso") como rechazo.
 */
@Service
public class VoidedEndpoints {

    private static final Logger log = LoggerFactory.getLogger(VoidedEndpoints.class);
    private static final DateTimeFormatter LARAVEL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'00:00:00.000000'Z'", Locale.ROOT);

    private final TicketRepository tickets;
    private final SaleDocumentRepository documents;
    private final TicketJobs jobs;
    private final EmissionPolicy emission;
    private final FileStorage storage;
    private final AuditLogService audit;
    private final TransactionTemplate tx;

    public VoidedEndpoints(TicketRepository tickets, SaleDocumentRepository documents, TicketJobs jobs, EmissionPolicy emission,
            FileStorage storage, AuditLogService audit, TransactionTemplate tx) {
        this.tickets = tickets;
        this.documents = documents;
        this.jobs = jobs;
        this.emission = emission;
        this.storage = storage;
        this.audit = audit;
        this.tx = tx;
    }

    public ResponseEntity<Object> store(HttpServletRequest http) {
        Tenant tenant = ApiContext.requireTenant(http);
        return store(tenant, Input.of(http), http);
    }

    /**
     * Comunicación de baja de la empresa indicada con las mismas reglas y plazos de {@code POST /api/v1/anulaciones}.
     * {@code auditRequest}: la petición de {@code /api/v1} para su pista {@code comprobante.anulado} (actor = la
     * empresa), o null cuando la llama el panel de administración, que registra su propia acción.
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> store(Tenant tenant, Input in, HttpServletRequest auditRequest) {
        ResponseEntity<Object> denied = emission.deny(tenant);
        if (denied != null) {
            return denied;
        }
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("fecha_generacion", "required|date");
        rules.put("fecha_comunicacion", "sometimes|date");
        rules.put("detalles", "required|array|min:1");
        rules.put("detalles.*.tipo_documento", "required|string|in:01,03,07,08,20,40");
        rules.put("detalles.*.serie", "required|string|size:4");
        rules.put("detalles.*.correlativo", "required|string");
        rules.put("detalles.*.motivo", "required|string|max:255");
        Map<String, Object> validated = Validator.make(in.all(), rules).validate();
        boolean enviarAuto = in.all().containsKey("enviar_automatico") ? in.bool("enviar_automatico") : true;
        try {
            String fechaCom = validated.get("fecha_comunicacion") != null
                    ? Php.toStr(validated.get("fecha_comunicacion"))
                    : Clock.todayLima().toString();
            List<Object> detalles = asList(validated.get("detalles"));
            for (Object o : detalles) {
                if (o instanceof Map<?, ?> d && Php.strictEq(d.get("tipo_documento"), "03")) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("estado", "error");
                    body.put("mensaje", "Las boletas no se anulan por comunicación de baja. Use POST /resumenes con {\"anular\":[{\"id\":..., \"motivo\":\"...\"}]}");
                    body.put("codigo_error", "boletas_no_soportadas_en_ra");
                    body.put("siguiente_accion", map("operacion", "anular_boleta_por_resumen_diario", "endpoint", "POST /api/v1/resumenes"));
                    return ResponseEntity.status(422).body(body);
                }
            }
            List<String> errores = validarDetalles(tenant.getId(), detalles,
                    pe.facturacion.api.sunat.builder.PhpDates.parseDate(Php.toStr(validated.get("fecha_generacion"))));
            if (!errores.isEmpty()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("estado", "error");
                body.put("mensaje", "No se puede anular: " + String.join(" ", errores));
                body.put("errores", errores);
                return ResponseEntity.status(422).body(body);
            }
            LocalDate fechaComunicacion = pe.facturacion.api.sunat.builder.PhpDates.parseDate(fechaCom);
            TicketDoc voided = tx.execute(status -> {
                tickets.lockNumbering(TicketRepository.VOIDED, tenant.getId());
                String correlativo = SummaryEndpoints.pad3(tickets.maxCorrelativo(TicketRepository.VOIDED, tenant.getId(), fechaComunicacion, "") + 1);
                TicketDoc v = new TicketDoc();
                v.table = TicketRepository.VOIDED;
                v.tenantId = tenant.getId();
                v.identifier = "RA-" + fechaComunicacion.toString().replace("-", "") + "-" + correlativo;
                v.correlativo = correlativo;
                v.fechaReferencia = pe.facturacion.api.sunat.builder.PhpDates.parseDate(Php.toStr(validated.get("fecha_generacion")));
                v.fechaEnvio = fechaComunicacion;
                v.totalDocumentos = detalles.size();
                v.detalles = detalles;
                v.motivo = detalles.get(0) instanceof Map<?, ?> d0 && d0.get("motivo") != null ? Php.toStr(d0.get("motivo")) : null;
                v.sunatStatus = "pendiente";
                tickets.insert(v);
                if (enviarAuto) {
                    // Corrección: una baja manual pendiente no marca los comprobantes hasta que se envía
                    marcar(tenant.getId(), detalles, "anulacion_en_proceso", null);
                    jobs.dispatchSend(TicketRepository.VOIDED, v.id);
                    tickets.update(v, Map.of("sunat_status", "enviado"));
                    v.sunatStatus = "enviado";
                }
                return v;
            });
            if (auditRequest != null) {
                audit.registrar(auditRequest, "comprobante.anulado", tenant.getId(), "App\\Models\\VoidedDocument", voided.id,
                        "Creó comunicación de baja " + voided.identifier + " (API)", map("detalles", detalles));
            }
            String message = enviarAuto ? "Comunicación de baja encolada para envío a SUNAT."
                    : "Comunicación de baja creada en estado pendiente. Use POST /anulaciones/{id}/enviar para enviarla a SUNAT.";
            Map<String, Object> datos = new LinkedHashMap<>();
            datos.put("id_anulacion", voided.id);
            datos.put("identifier", voided.identifier);
            datos.put("correlativo", voided.correlativo);
            datos.put("fecha_comunicacion", fechaCom);
            datos.put("total_documentos", detalles.size());
            datos.put("estado_sunat", enviarAuto ? "enviado" : "pendiente");
            datos.put("consulta_estado", baseUrl() + "/api/v1/anulaciones/" + voided.id + "/estado");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("estado", "exito");
            body.put("mensaje", message);
            body.put("datos", datos);
            return ResponseEntity.status(201).body(body);
        } catch (RuntimeException e) {
            String ref = UUID.randomUUID().toString();
            log.error("No se pudo crear la comunicación de baja. error_id={}", ref, e);
            return ApiResponse.error("No se pudo crear la comunicación de baja. (ref: " + ref + ")", 500, map("error_id", ref));
        }
    }

    /** {@code VoidedService::validarDetalles()}. */
    List<String> validarDetalles(long tenantId, List<Object> detalles, LocalDate fechaGeneracion) {
        List<String> errors = new ArrayList<>();
        LocalDate limite = Clock.todayLima().minusDays(7);
        for (Object o : detalles) {
            Map<?, ?> det = o instanceof Map<?, ?> m ? m : Map.of();
            Object tipo = det.get("tipo_documento");
            Object serie = det.get("serie");
            Object correlativo = det.get("correlativo");
            if (!Php.truthy(tipo) || !Php.truthy(serie) || !Php.truthy(correlativo)) {
                errors.add("Cada detalle requiere tipo_documento, serie y correlativo.");
                continue;
            }
            DocumentKind kind = DocumentKind.fromTipo(Php.toStr(tipo));
            if (kind == null) {
                errors.add("Tipo de documento " + Php.toStr(tipo) + " no soportado para anulación.");
                continue;
            }
            String ref = Php.toStr(serie) + "-" + Php.toStr(correlativo);
            SaleDocument doc = findDocument(kind, tenantId, Php.toStr(serie), Php.toStr(correlativo));
            if (doc == null) {
                errors.add("Documento " + ref + " no existe.");
                continue;
            }
            String status = doc.sunatStatus == null ? "" : doc.sunatStatus.toLowerCase(Locale.ROOT);
            if (!status.equals("aceptado")) {
                errors.add("Documento " + ref + " no está aceptado por SUNAT (estado actual: " + status + ").");
                continue;
            }
            LocalDate emision = doc.fechaEmision.toLocalDate();
            if (emision.isBefore(limite)) {
                errors.add("Documento " + ref + " ya pasó el plazo de 7 días para anulación (emitido el " + emision + ").");
                continue;
            }
            String fecha = fechaDistinta(ref, emision, fechaGeneracion);
            if (fecha != null) {
                errors.add(fecha);
                continue;
            }
            if (kind == DocumentKind.FACTURA && documents.count(DocumentKind.NOTA_CREDITO, "tenant_id = :t AND doc_afectado_tipo = '01' "
                    + "AND doc_afectado_serie = :s AND doc_afectado_correlativo = :c AND deleted_at IS NULL "
                    // Corrección: una NC anulada o rechazada ya no modifica la factura
                    + "AND sunat_status NOT IN ('anulado', 'rechazado')",
                    new MapSqlParameterSource().addValue("t", tenantId).addValue("s", Php.toStr(serie)).addValue("c", Php.toStr(correlativo))) > 0) {
                errors.add("La factura " + ref + " tiene una nota de crédito asociada. Usa la NC en vez de anular la factura.");
                continue;
            }
            TicketDoc duplicate = existeConDetalle(tenantId, "RA-", Php.toStr(tipo), Php.toStr(serie), correlativo);
            if (duplicate != null) {
                errors.add("Ya existe una comunicación de baja para " + ref + " (" + duplicate.identifier + ").");
            }
        }
        return errors;
    }

    /**
     * Corrección: {@code fecha_generacion} va como ReferenceDate y SUNAT exige que sea la fecha de
     * emisión de los comprobantes; un lote con fechas distintas tampoco puede declararse junto.
     */
    static String fechaDistinta(String ref, LocalDate emision, LocalDate fechaGeneracion) {
        if (emision.equals(fechaGeneracion)) {
            return null;
        }
        return "Documento " + ref + " fue emitido el " + emision + ": fecha_generacion (" + fechaGeneracion
                + ") debe ser la fecha de emisión de los comprobantes (envíe en lotes separados los de fechas distintas).";
    }

    SaleDocument findDocument(DocumentKind kind, long tenantId, String serie, String correlativo) {
        if (!correlativo.trim().matches("\\d+")) {
            return null;
        }
        List<SaleDocument> found = documents.query(kind, "tenant_id = :t AND serie = :s AND correlativo = :c AND deleted_at IS NULL",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("s", serie).addValue("c", Php.toInt(correlativo)), "id", 1, 0);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Baja o reversión vigente que ya contiene ese comprobante (correlativo comparado sin ceros a la izquierda). */
    public TicketDoc existeConDetalle(long tenantId, String prefix, String tipo, String serie, Object correlativo) {
        String corr = stripZeros(Php.toStr(correlativo));
        for (TicketDoc v : tickets.all(TicketRepository.VOIDED, "tenant_id = :t AND identifier LIKE :p AND sunat_status IN ('pendiente', 'enviado', 'aceptado')",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("p", prefix + "%"))) {
            for (Object o : asList(v.detalles)) {
                if (o instanceof Map<?, ?> d && Php.strictEq(d.get("tipo_documento"), tipo) && Php.strictEq(d.get("serie"), serie)
                        && stripZeros(Php.toStr(d.get("correlativo"))).equals(corr)) {
                    return v;
                }
            }
        }
        return null;
    }

    private static String stripZeros(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }

    /**
     * Cambia el estado de los comprobantes de la baja. Con {@code soloDesde} solo actualiza los que
     * tienen ese estado (revertir una baja rechazada).
     */
    public void marcar(long tenantId, List<Object> detalles, String estado, String soloDesde) {
        for (Object o : detalles) {
            if (!(o instanceof Map<?, ?> d)) {
                continue;
            }
            String tipo = Php.toStr(d.get("tipo_documento"));
            DocumentKind kind = DocumentKind.fromTipo(tipo);
            // Reversión (RR): retenciones (20) y percepciones (40)
            var retper = pe.facturacion.api.modules.retper.RetPerKind.fromTipo(tipo);
            String table = kind != null ? kind.table : retper != null ? retper.table : null;
            // Liquidación de compra (04): sus estados son reversion_en_proceso / revertido
            boolean liquidacion = "04".equals(tipo);
            if (liquidacion) {
                table = pe.facturacion.api.modules.settlement.SettlementRepository.TABLE;
            }
            String corr = Php.toStr(d.get("correlativo")).trim();
            if (table == null || !corr.matches("\\d+")) {
                continue;
            }
            String nuevo = liquidacion ? estadoLiquidacion(estado) : estado;
            String desde = liquidacion ? estadoLiquidacion(soloDesde) : soloDesde;
            MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", tenantId).addValue("s", Php.toStr(d.get("serie")))
                    .addValue("c", Php.toInt(corr));
            String where = "tenant_id = :t AND serie = :s AND correlativo = :c";
            if (desde != null) {
                p.addValue("desde", desde);
                where += " AND sunat_status = :desde";
            } else if (!nuevo.equals("aceptado") && !nuevo.equals("anulacion_en_proceso") && !nuevo.equals("reversion_en_proceso")) {
                // estado final (anulado / revertido): idempotente
                p.addValue("final", nuevo);
                where += " AND sunat_status <> :final";
            }
            tickets.updateDocuments(table, where, p, Map.of("sunat_status", nuevo));
            if (kind == DocumentKind.NOTA_CREDITO && "anulado".equals(nuevo)) {
                documents.restaurarCobroTrasBajaDeNc(tenantId, Php.toStr(d.get("serie")), (int) Php.toInt(corr));
            }
        }
    }

    private static String estadoLiquidacion(String estado) {
        if (estado == null) {
            return null;
        }
        return switch (estado) {
            case "anulacion_en_proceso" -> "reversion_en_proceso";
            case "anulado" -> "revertido";
            default -> estado;
        };
    }

    public ResponseEntity<Object> show(HttpServletRequest http, long id) {
        return show(ApiContext.requireTenant(http), id);
    }

    public ResponseEntity<Object> show(Tenant tenant, long id) {
        return ResponseEntity.ok(buildResponse(tickets.find(TicketRepository.VOIDED, tenant.getId(), id).orElseThrow(ApiException::notFound)));
    }

    /** {@code GET /anulaciones/{id}/estado}: si sigue "enviado" con ticket, consulta a SUNAT en el momento. */
    public ResponseEntity<Object> checkStatus(HttpServletRequest http, long id) {
        return checkStatus(ApiContext.requireTenant(http), id);
    }

    /** Estado de la baja de la empresa indicada; si sigue "enviado" con ticket, consulta a SUNAT y guarda el resultado. */
    public ResponseEntity<Object> checkStatus(Tenant tenant, long id) {
        TicketDoc v = tickets.find(TicketRepository.VOIDED, tenant.getId(), id).orElseThrow(ApiException::notFound);
        if (v.ticket != null && "enviado".equals(v.sunatStatus)) {
            try {
                jobs.pollOnce(v, tenant);
            } catch (RuntimeException e) {
                // Si SUNAT no responde se devuelve el estado guardado
            }
            v = tickets.find(TicketRepository.VOIDED, tenant.getId(), id).orElseThrow(ApiException::notFound);
        }
        return ResponseEntity.ok(buildResponse(v));
    }

    public ResponseEntity<Object> enviar(HttpServletRequest http, long id) {
        return enviar(ApiContext.requireTenant(http), id);
    }

    /** Enviar o reenviar a SUNAT una baja o reversión de la empresa indicada (404 si es de otra). */
    public ResponseEntity<Object> enviar(Tenant tenant, long id) {
        TicketDoc v = tickets.find(TicketRepository.VOIDED, tenant.getId(), id).orElseThrow(ApiException::notFound);
        if ("aceptado".equals(v.sunatStatus)) {
            return ApiResponse.error("Esta comunicación de baja ya fue aceptada por SUNAT.", 422);
        }
        boolean reversion = v.identifier != null && v.identifier.startsWith("RR-");
        tx.executeWithoutResult(st -> {
            tickets.update(v, map("sunat_status", "enviado", "sunat_code", null, "sunat_description", null));
            // Al enviarse (baja manual o reenvío de una rechazada) los comprobantes aceptados pasan a "en proceso"
            marcar(tenant.getId(), asList(v.detalles), "anulacion_en_proceso", "aceptado");
            jobs.dispatchSend(TicketRepository.VOIDED, v.id);
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "exito");
        body.put("mensaje", reversion ? "Reversión enviada a SUNAT." : "Comunicación de baja enviada a SUNAT.");
        body.put("datos", map("id_anulacion", v.id, "identifier", v.identifier, "estado_sunat", "enviado"));
        return ResponseEntity.ok(body);
    }

    public ResponseEntity<Object> file(HttpServletRequest http, long id, String tipo) {
        return file(ApiContext.requireTenant(http), id, tipo);
    }

    public ResponseEntity<Object> file(Tenant tenant, long id, String tipo) {
        TicketDoc v = tickets.find(TicketRepository.VOIDED, tenant.getId(), id).orElseThrow(ApiException::notFound);
        String path = "cdr".equals(tipo) ? v.cdrPath : v.xmlPath;
        if (path == null || path.isEmpty() || !storage.exists(Disk.PRIVATE, path)) {
            return ApiResponse.error(tipo.toUpperCase(Locale.ROOT) + " de la anulación no disponible", 404);
        }
        String nombre = v.identifier != null && !v.identifier.isEmpty() ? v.identifier : "anulacion-" + v.id;
        byte[] content = storage.get(Disk.PRIVATE, path);
        return "cdr".equals(tipo)
                ? ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "application/zip")
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"R-" + nombre + ".zip\"").body(content)
                : ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "application/xml")
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nombre + ".xml\"")
                        .body(new String(content, java.nio.charset.StandardCharsets.UTF_8));
    }

    public ResponseEntity<Object> index(HttpServletRequest http) {
        Tenant tenant = ApiContext.requireTenant(http);
        return index(tenant, Input.of(http));
    }

    /** Listado de la empresa indicada (lo usa también el panel de administración con la misma respuesta). */
    public ResponseEntity<Object> index(Tenant tenant, Input in) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", tenant.getId());
        List<String> w = new ArrayList<>(List.of("tenant_id = :t"));
        if (in.filled("estado")) {
            p.addValue("estado", Php.toStr(in.get("estado")));
            w.add("sunat_status = :estado");
        }
        if (in.filled("fecha_desde")) {
            p.addValue("desde", Php.toStr(in.get("fecha_desde")));
            w.add("CAST(fecha_comunicacion AS text) >= :desde");
        }
        if (in.filled("fecha_hasta")) {
            p.addValue("hasta", Php.toStr(in.get("fecha_hasta")));
            w.add("CAST(fecha_comunicacion AS text) <= :hasta");
        }
        String where = String.join(" AND ", w);
        int perPage = SummaryEndpoints.perPage(in.get("per_page"));
        int page = pe.facturacion.api.shared.api.Paginator.page(in);
        long total = tickets.count(TicketRepository.VOIDED, where, p);
        List<Object> datos = new ArrayList<>();
        for (TicketDoc v : tickets.query(TicketRepository.VOIDED, where, p, "id desc", perPage, (page - 1) * perPage)) {
            datos.add(raw(v));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "exito");
        body.put("datos", datos);
        body.put("meta", map("pagina_actual", page, "por_pagina", perPage, "total", total,
                "ultima_pagina", (int) Math.max(Math.ceil((double) total / perPage), 1)));
        return ResponseEntity.ok(body);
    }

    /** {@code $model->toArray()} de {@code VoidedDocument} (lo que devuelve el listado de API-PRO). */
    static Map<String, Object> raw(TicketDoc v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id);
        m.put("tenant_id", v.tenantId);
        m.put("identifier", v.identifier);
        m.put("correlativo", v.correlativo);
        m.put("fecha_generacion", v.fechaReferencia == null ? null : v.fechaReferencia.format(LARAVEL_DATE));
        m.put("fecha_comunicacion", v.fechaEnvio == null ? null : v.fechaEnvio.format(LARAVEL_DATE));
        m.put("total_documentos", v.totalDocumentos);
        m.put("detalles", v.detalles);
        m.put("xml_path", v.xmlPath);
        m.put("ticket", v.ticket);
        m.put("sunat_status", v.sunatStatus);
        m.put("sunat_code", v.sunatCode);
        m.put("sunat_description", v.sunatDescription);
        m.put("sunat_notes", v.sunatNotes);
        m.put("created_at", micro(v.createdAt));
        m.put("updated_at", micro(v.updatedAt));
        m.put("motivo", v.motivo);
        m.put("anulado_por", v.anuladoPor);
        m.put("cdr_path", v.cdrPath);
        return m;
    }

    private static String micro(java.time.LocalDateTime t) {
        return t == null ? null : t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.000000'Z'", Locale.ROOT));
    }

    static Map<String, Object> buildResponse(TicketDoc v) {
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("id_anulacion", v.id);
        datos.put("identifier", v.identifier);
        datos.put("correlativo", v.correlativo);
        datos.put("ticket", v.ticket);
        datos.put("fecha_generacion", v.fechaReferencia == null ? null : v.fechaReferencia.toString());
        datos.put("fecha_comunicacion", v.fechaEnvio == null ? null : v.fechaEnvio.toString());
        datos.put("estado_sunat", v.sunatStatus);
        datos.put("codigo_sunat", v.sunatCode);
        datos.put("descripcion_sunat", v.sunatDescription);
        datos.put("notas_sunat", v.sunatNotes);
        datos.put("total_documentos", v.totalDocumentos);
        datos.put("detalles", v.detalles);
        datos.put("creado_en", v.createdAt == null ? null : Clock.iso8601Utc(v.createdAt));
        datos.put("actualizado_en", v.updatedAt == null ? null : Clock.iso8601Utc(v.updatedAt));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "exito");
        body.put("datos", datos);
        return body;
    }
}
