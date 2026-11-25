package pe.facturacion.api.modules.guide;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import pe.facturacion.api.modules.plan.EmissionPolicy;
import pe.facturacion.api.modules.plan.PlanService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiException;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.api.Paginator;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.sunat.builder.PhpDates;

/**
 * {@code DispatchGuideController} con {@code CreateDispatchGuideAction} y
 * {@code UpdateDispatchGuideAction}: GRE remitente (09) y transportista (31).
 *
 * <p>Correcciones: la respuesta incluye {@code tipo_documento}; al editar se actualizan todos los
 * campos (API-PRO perdía remitente, documentos relacionados, subcontratador, pagador, RUC/código de
 * local de partida y llegada, indicadores...) y una GRT editada sigue siendo GRT; se guardan
 * {@code tercero} y {@code comprador}; "en proceso" (98) no marca la guía como rechazada al
 * consultar el estado; {@code por_pagina} tiene tope 100 y los errores internos no exponen el
 * mensaje de la excepción.
 */
@Service
public class GuideEndpoints {

    private static final Logger log = LoggerFactory.getLogger(GuideEndpoints.class);

    private final GuideRepository guides;
    private final GuideRequest request;
    private final GuideJobs jobs;
    private final JdbcTemplate jdbc;
    private final PlanService plans;
    private final EmissionPolicy emission;
    private final FileStorage storage;
    private final TransactionTemplate tx;

    public GuideEndpoints(GuideRepository guides, GuideRequest request, GuideJobs jobs, JdbcTemplate jdbc, PlanService plans,
            EmissionPolicy emission, FileStorage storage, TransactionTemplate tx) {
        this.guides = guides;
        this.request = request;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.plans = plans;
        this.emission = emission;
        this.storage = storage;
        this.tx = tx;
    }

    public ResponseEntity<Object> store(HttpServletRequest http, boolean transportista) {
        Tenant tenant = ApiContext.requireTenant(http);
        return store(tenant, Input.of(http), transportista);
    }

    /** Emisión de una guía de la empresa indicada con la misma tubería de {@code /api/v1} (la usa también el panel). */
    public ResponseEntity<Object> store(Tenant tenant, Input in, boolean transportista) {
        ResponseEntity<Object> denied = emission.deny(tenant);
        if (denied != null) {
            return denied;
        }
        Map<String, Object> data = new LinkedHashMap<>(request.validate(tenant, GuideRequest.prepare(in.all(), transportista)));
        if (transportista) {
            data.put("tipo_documento", "31");
        }
        boolean enviarAuto = in.all().containsKey("enviar_automatico") ? in.bool("enviar_automatico") : true;
        String tipo = Php.toStr(Php.coalesce(data, "tipo_documento", "09"));
        try {
            long id = tx.execute(st -> create(tenant, data, enviarAuto));
            boolean grt = "31".equals(tipo);
            String msg;
            if (transportista) {
                msg = enviarAuto ? "Guía de Remisión Transportista creada y encolada para envío a SUNAT."
                        : "Guía de Remisión Transportista creada en estado pendiente. Use POST /guias-remision/{id}/enviar para enviarla a SUNAT.";
            } else {
                msg = enviarAuto ? "Guía de remisión creada y encolada para envío a SUNAT."
                        : "Guía de remisión creada en estado pendiente. Use POST /guias-remision/{id}/enviar para enviarla a SUNAT.";
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("estado", "exito");
            body.put("mensaje", msg);
            body.put("datos", resource(guides.findById(id).orElseThrow()));
            if (grt && "beta".equals(tenant.getEnvironment())) {
                Map<String, Object> adv = new LinkedHashMap<>();
                adv.put("codigo", "grt_beta_no_soportado");
                adv.put("mensaje", "SUNAT beta no valida completamente las Guías de Remisión Transportista. Para pruebas realistas cambia "
                        + "entorno a \"production\" en PUT /empresa.");
                body.put("advertencias", List.of(adv));
            }
            return ResponseEntity.status(201).body(body);
        } catch (RuntimeException e) {
            return internal(transportista ? "Error al crear guía transportista." : "Error al crear guía.", e);
        }
    }

    /** {@code CreateDispatchGuideAction::execute()}. */
    private long create(Tenant tenant, Map<String, Object> data, boolean enviarAuto) {
        String tipo = Php.toStr(Php.coalesce(data, "tipo_documento", "09"));
        List<Map<String, Object>> locked = jdbc.queryForList(
                "SELECT s.id, s.correlativo, su.id AS sucursal_id, su.cod_local FROM series s LEFT JOIN sucursales su ON su.id = s.sucursal_id "
                        + "WHERE s.tenant_id = ? AND s.tipo_documento = ? AND s.serie = ? AND s.is_active = true LIMIT 1 FOR UPDATE OF s",
                tenant.getId(), tipo, Php.toStr(data.get("serie")));
        if (locked.isEmpty()) {
            throw ApiException.notFound();
        }
        Map<String, Object> serie = locked.get(0);
        int actual = ((Number) serie.get("correlativo")).intValue();
        int correlativo = data.get("correlativo") == null ? actual + 1 : (int) Php.toInt(data.get("correlativo"));
        if (correlativo > actual) {
            jdbc.update("UPDATE series SET correlativo = ?, updated_at = ? WHERE id = ?", correlativo, Clock.nowUtc().withNano(0), serie.get("id"));
        }
        // La GRT no lleva motivo/modalidad; las columnas son NOT NULL
        if ("31".equals(tipo)) {
            if (data.get("cod_traslado") == null) {
                data.put("cod_traslado", "01");
            }
            if (data.get("mod_traslado") == null) {
                data.put("mod_traslado", "01");
            }
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("tenant_id", tenant.getId());
        c.put("sucursal_id", serie.get("sucursal_id"));
        c.put("cod_local", serie.get("cod_local") != null ? serie.get("cod_local") : Php.toStr(Php.coalesce(data, "cod_local", "0000")));
        c.put("tipo_documento", tipo);
        c.put("serie", Php.toStr(data.get("serie")));
        c.put("correlativo", correlativo);
        c.putAll(columns(data));
        c.put("sunat_status", "pendiente");
        long id = guides.insert(c);
        plans.incrementDocumentUsage(tenant);
        if (enviarAuto) {
            jobs.dispatchSend(id);
            guides.update(id, Map.of("sunat_status", "enviado"));
        }
        return id;
    }

    /** Columnas editables de la guía desde los datos validados. */
    static Map<String, Object> columns(Map<String, Object> data) {
        @SuppressWarnings("unchecked")
        Map<String, Object> dest = (Map<String, Object>) data.get("destinatario");
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("fecha_emision", resolveEmisionDateTime(Php.toStr(data.get("fecha_emision"))));
        c.put("destinatario_tipo_doc", Php.toStr(dest.get("tipo_doc")));
        c.put("destinatario_num_doc", Php.toStr(dest.get("num_doc")));
        c.put("destinatario_razon_social", Php.toStr(dest.get("razon_social")));
        c.put("cod_traslado", Php.toStr(data.get("cod_traslado")));
        c.put("mod_traslado", Php.toStr(data.get("mod_traslado")));
        c.put("fecha_traslado", PhpDates.parseDate(Php.toStr(data.get("fecha_traslado"))));
        c.put("fecha_entrega_transportista", data.get("fecha_entrega_transportista") == null ? null
                : PhpDates.parseDate(Php.toStr(data.get("fecha_entrega_transportista"))));
        c.put("peso_total", Php.toFloat(data.get("peso_total")));
        c.put("und_peso_total", Php.toStr(Php.coalesce(data, "und_peso_total", "KGM")));
        c.put("num_bultos", data.get("num_bultos") == null ? null : Php.toInt(data.get("num_bultos")));
        for (String k : List.of("llegada_ubigeo", "llegada_direccion", "partida_ubigeo", "partida_direccion")) {
            c.put(k, Php.toStr(data.get(k)));
        }
        for (String k : List.of("llegada_ruc", "llegada_cod_local", "partida_ruc", "partida_cod_local", "observacion")) {
            c.put(k, data.get(k) == null ? null : Php.toStr(data.get(k)));
        }
        for (String k : List.of("transportista", "vehiculo", "indicadores", "remitente", "doc_relacionado", "datos_subcontratador",
                "datos_pagador_flete", "autorizacion_especial", "tercero", "comprador")) {
            c.put(k, data.get(k));
        }
        c.put("conductor", data.get("conductor") != null ? data.get("conductor") : data.get("conductores"));
        c.put("items", data.get("items"));
        return c;
    }

    static LocalDateTime resolveEmisionDateTime(String fecha) {
        if (fecha.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return LocalDate.parse(fecha).atTime(Clock.nowLima().toLocalTime().withNano(0));
        }
        return PhpDates.parseDateTime(fecha);
    }

    public ResponseEntity<Object> update(HttpServletRequest http, long id) {
        return update(ApiContext.requireTenant(http), Input.of(http), id);
    }

    /** Corregir y reenviar una guía pendiente o rechazada de la empresa indicada (404 si es de otra). */
    public ResponseEntity<Object> update(Tenant tenant, Input in, long id) {
        Map<String, Object> input = new LinkedHashMap<>(in.all());
        // Corrección: primero si la guía es de la empresa (API-PRO validaba antes y una guía ajena daba 422)
        Map<String, Object> actual = guides.find(tenant.getId(), id).orElseThrow(ApiException::notFound);
        // Una GRT editada sin tipo_documento sigue validándose como GRT
        if (!input.containsKey("tipo_documento")) {
            input.put("tipo_documento", actual.get("tipo_documento"));
        }
        Map<String, Object> data = new LinkedHashMap<>(request.validate(tenant, GuideRequest.prepare(input, false)));
        if (!List.of("pendiente", "rechazado").contains(actual.get("sunat_status"))) {
            return ApiResponse.error("Solo guías pendientes o rechazadas pueden editarse.", 422);
        }
        try {
            tx.executeWithoutResult(st -> {
                if (data.get("cod_traslado") == null) {
                    data.put("cod_traslado", actual.get("cod_traslado") == null ? "01" : actual.get("cod_traslado"));
                }
                if (data.get("mod_traslado") == null) {
                    data.put("mod_traslado", actual.get("mod_traslado") == null ? "01" : actual.get("mod_traslado"));
                }
                Map<String, Object> ch = columns(data);
                ch.put("sunat_status", "pendiente");
                for (String k : List.of("sunat_code", "sunat_description", "ticket", "xml_path", "cdr_path", "pdf_path")) {
                    ch.put(k, null);
                }
                guides.update(id, ch);
                jobs.dispatchSend(id);
                guides.update(id, Map.of("sunat_status", "enviado"));
            });
            return ApiResponse.success(resource(guides.findById(id).orElseThrow()), "Guía actualizada y reenviada a SUNAT.");
        } catch (RuntimeException e) {
            return internal("Error al actualizar guía.", e);
        }
    }

    public ResponseEntity<Object> enviar(HttpServletRequest http, long id) {
        return enviar(ApiContext.requireTenant(http), id);
    }

    /** Enviar o reenviar a SUNAT una guía de la empresa indicada (404 si es de otra). */
    public ResponseEntity<Object> enviar(Tenant tenant, long id) {
        Map<String, Object> g = guides.find(tenant.getId(), id).orElseThrow(ApiException::notFound);
        if ("aceptado".equals(g.get("sunat_status"))) {
            return ApiResponse.error("Esta guía de remisión ya fue aceptada por SUNAT.", 422);
        }
        tx.executeWithoutResult(st -> {
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("sunat_status", "enviado");
            ch.put("sunat_code", null);
            ch.put("sunat_description", null);
            guides.update(id, ch);
            jobs.dispatchSend(id);
        });
        return ApiResponse.success(resource(guides.findById(id).orElseThrow()), "Guía de remisión enviada a SUNAT.");
    }

    public ResponseEntity<Object> index(HttpServletRequest http) {
        Tenant tenant = ApiContext.requireTenant(http);
        return index(tenant, Input.of(http));
    }

    /** Listado de la empresa indicada (lo usa también el panel de administración con la misma respuesta). */
    public ResponseEntity<Object> index(Tenant tenant, Input in) {
        Object raw = in.get("por_pagina");
        int n = raw == null ? 15 : (int) Php.toInt(raw);
        int perPage = n <= 0 ? 15 : Math.min(n, 100);
        int page = Paginator.page(in);
        // Corrección: API-PRO no filtraba el listado de guías (la colección usa ?sunat_status= y ?tipo_documento=31)
        StringBuilder w = new StringBuilder();
        org.springframework.jdbc.core.namedparam.MapSqlParameterSource p = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
        for (String[] f : new String[][] {{"sunat_status", "sunat_status"}, {"tipo_documento", "tipo_documento"}, {"serie", "serie"}}) {
            if (in.filled(f[0])) {
                w.append(" AND ").append(f[1]).append(" = :").append(f[0]);
                p.addValue(f[0], Php.toStr(in.get(f[0])));
            }
        }
        if (in.filled("fecha_desde") && in.filled("fecha_hasta")) {
            String desde = Php.toStr(in.get("fecha_desde"));
            String hasta = Php.toStr(in.get("fecha_hasta"));
            if (!desde.matches("\\d{4}-\\d{2}-\\d{2}") || !hasta.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return ApiResponse.error("fecha_desde y fecha_hasta deben tener el formato AAAA-MM-DD.", 422);
            }
            w.append(" AND fecha_emision BETWEEN CAST(:fd AS timestamp) AND CAST(:fh AS timestamp)");
            p.addValue("fd", desde + " 00:00:00").addValue("fh", hasta + " 23:59:59");
        }
        long total = guides.count(tenant.getId(), w.toString(), p);
        List<Object> datos = new ArrayList<>();
        for (Map<String, Object> g : guides.page(tenant.getId(), w.toString(), p, perPage, (page - 1) * perPage)) {
            datos.add(resource(g));
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
        return show(ApiContext.requireTenant(http), id);
    }

    public ResponseEntity<Object> show(Tenant tenant, long id) {
        return ApiResponse.success(resource(guides.find(tenant.getId(), id).orElseThrow(ApiException::notFound)));
    }

    public ResponseEntity<Object> xml(HttpServletRequest http, long id) {
        return xml(ApiContext.requireTenant(http), id);
    }

    public ResponseEntity<Object> xml(Tenant tenant, long id) {
        Map<String, Object> g = guides.find(tenant.getId(), id).orElseThrow(ApiException::notFound);
        String path = (String) g.get("xml_path");
        String content = path != null && storage.exists(Disk.PRIVATE, path)
                ? new String(storage.get(Disk.PRIVATE, path), java.nio.charset.StandardCharsets.UTF_8)
                : (String) g.get("xml_content");
        if (content == null || content.isEmpty()) {
            return ApiResponse.error("XML no disponible", 404);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/xml")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + GuideJobs.numero(g) + ".xml\"")
                .body(content);
    }

    public ResponseEntity<Object> cdr(HttpServletRequest http, long id) {
        return cdr(ApiContext.requireTenant(http), id);
    }

    public ResponseEntity<Object> cdr(Tenant tenant, long id) {
        Map<String, Object> g = guides.find(tenant.getId(), id).orElseThrow(ApiException::notFound);
        String path = (String) g.get("cdr_path");
        byte[] content = path != null && storage.exists(Disk.PRIVATE, path) ? storage.get(Disk.PRIVATE, path) : (byte[]) g.get("cdr_content");
        if (content == null || content.length == 0) {
            return ApiResponse.error("CDR no disponible", 404);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"R-" + GuideJobs.numero(g) + ".zip\"")
                .body(content);
    }

    public ResponseEntity<Object> checkStatus(HttpServletRequest http, long id) {
        return checkStatus(ApiContext.requireTenant(http), id);
    }

    /** Consulta el ticket de la guía en SUNAT y guarda el resultado (empresa indicada; 404 si es de otra). */
    public ResponseEntity<Object> checkStatus(Tenant tenant, long id) {
        Map<String, Object> g = guides.find(tenant.getId(), id).orElseThrow(ApiException::notFound);
        String status = Php.toStr(g.get("sunat_status"));
        if (g.get("ticket") == null) {
            boolean waiting = "pendiente".equals(status) || "enviado".equals(status);
            Map<String, Object> guia = new LinkedHashMap<>();
            guia.put("id", id);
            guia.put("numero_completo", GuideJobs.numero(g));
            guia.put("estado_sunat", status);
            guia.put("codigo_sunat", g.get("sunat_code"));
            guia.put("descripcion_sunat", g.get("sunat_description"));
            guia.put("tiene_ticket", false);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("estado", waiting ? "pendiente" : "error");
            body.put("mensaje", waiting ? "La guía todavía no tiene ticket SUNAT. Primero debe ejecutarse el envío inicial de la guía."
                    : "La guía no tiene ticket SUNAT porque el envío inicial falló antes de recibir ticket.");
            body.put("guia", guia);
            body.put("siguiente_accion", !waiting
                    ? "Corrija el error indicado y use POST /guias-remision/" + id + "/enviar para reintentar."
                    : "pendiente".equals(status) ? "Use POST /guias-remision/" + id + "/enviar para enviar la guía a SUNAT."
                    : "Verifique que el worker de colas esté ejecutándose (cola sunat).");
            return ResponseEntity.status(waiting ? 202 : 409).body(body);
        }
        boolean tokenRetry = "rechazado".equals(status) && "API".equals(g.get("sunat_code"))
                && Php.toStr(g.get("sunat_description")).contains("Token NO existe");
        if (("aceptado".equals(status) || "rechazado".equals(status)) && !tokenRetry) {
            return ApiResponse.success(resource(g), "Estado ya resuelto.");
        }
        try {
            GuideJobs.Poll p = jobs.poll(g, tenant);
            if (p.pending()) {
                return ApiResponse.error("Error al consultar estado: " + (p.result().errorMessage() == null
                        ? "Respuesta inválida de SUNAT." : p.result().errorMessage()), 202);
            }
            Map<String, Object> fresh = guides.findById(id).orElseThrow();
            return p.result().success() ? ApiResponse.success(resource(fresh)) : ApiResponse.success(resource(fresh), "Estado ya resuelto.");
        } catch (RuntimeException e) {
            return internal("Error al consultar estado.", e);
        }
    }

    /** {@code DispatchGuideResource}, más {@code tipo_documento}. */
    static Map<String, Object> resource(Map<String, Object> g) {
        long id = ((Number) g.get("id")).longValue();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("tipo_documento", g.get("tipo_documento"));
        m.put("serie", g.get("serie"));
        m.put("correlativo", ((Number) g.get("correlativo")).longValue());
        m.put("numero_completo", GuideJobs.numero(g));
        m.put("fecha_emision", ((LocalDateTime) g.get("fecha_emision")).format(Clock.Y_M_D_H_I_S));
        Map<String, Object> dest = new LinkedHashMap<>();
        dest.put("tipo_doc", g.get("destinatario_tipo_doc"));
        dest.put("num_doc", g.get("destinatario_num_doc"));
        dest.put("razon_social", g.get("destinatario_razon_social"));
        m.put("destinatario", dest);
        Map<String, Object> envio = new LinkedHashMap<>();
        envio.put("cod_traslado", g.get("cod_traslado"));
        envio.put("mod_traslado", g.get("mod_traslado"));
        envio.put("fecha_traslado", g.get("fecha_traslado").toString());
        envio.put("fecha_entrega_transportista", g.get("fecha_entrega_transportista") == null ? null : g.get("fecha_entrega_transportista").toString());
        envio.put("peso_total", g.get("peso_total") instanceof java.math.BigDecimal b ? b.doubleValue() : Php.toFloat(g.get("peso_total")));
        envio.put("und_peso_total", g.get("und_peso_total"));
        m.put("envio", envio);
        Map<String, Object> llegada = new LinkedHashMap<>();
        llegada.put("ubigeo", g.get("llegada_ubigeo"));
        llegada.put("direccion", g.get("llegada_direccion"));
        Map<String, Object> partida = new LinkedHashMap<>();
        partida.put("ubigeo", g.get("partida_ubigeo"));
        partida.put("direccion", g.get("partida_direccion"));
        m.put("direcciones", Map.of("llegada", llegada, "partida", partida));
        m.put("observacion", g.get("observacion"));
        m.put("transportista", g.get("transportista"));
        m.put("vehiculo", g.get("vehiculo"));
        m.put("conductor", g.get("conductor"));
        m.put("num_bultos", g.get("num_bultos") == null ? null : ((Number) g.get("num_bultos")).longValue());
        m.put("items", g.get("items"));
        Map<String, Object> sunat = new LinkedHashMap<>();
        sunat.put("estado", g.get("sunat_status"));
        sunat.put("codigo", g.get("sunat_code"));
        sunat.put("descripcion", g.get("sunat_description"));
        sunat.put("ticket", g.get("ticket"));
        m.put("sunat", sunat);
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString() + "/api/v1/guias-remision/" + id;
        Map<String, Object> archivos = new LinkedHashMap<>();
        archivos.put("xml", g.get("xml_path") != null ? base + "/xml" : null);
        archivos.put("cdr", g.get("cdr_path") != null ? base + "/cdr" : null);
        archivos.put("pdf", g.get("pdf_path") != null ? base + "/pdf" : null);
        m.put("archivos", archivos);
        m.put("creado_en", Clock.iso8601Utc((LocalDateTime) g.get("created_at")));
        return m;
    }

    private static ResponseEntity<Object> internal(String mensaje, RuntimeException e) {
        String ref = UUID.randomUUID().toString();
        log.error("{} error_id={}", mensaje, ref, e);
        return ApiResponse.error(mensaje.replaceAll("\\.$", "") + " (ref: " + ref + ")", 500, Map.of("error_id", ref));
    }
}
