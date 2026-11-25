package pe.facturacion.api.modules.admin.panel;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminFormat;
import pe.facturacion.api.modules.admin.api.AdminPaging;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.admin.security.AdminPrincipal;
import pe.facturacion.api.modules.audit.AuditLogService;
import pe.facturacion.api.modules.ops.OpsTasks.RevisionCertificado;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.queue.JobQueue;
import pe.facturacion.api.shared.time.Clock;

/** Operación desde el panel: uso del mes, certificados, rechazos SUNAT, trabajos fallidos y auditoría. */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Panel de administración · operación")
public class AdminOpsController {

    static final Set<String> CERT_STATUSES = Set.of(RevisionCertificado.OK, RevisionCertificado.POR_VENCER, RevisionCertificado.VENCIDO,
            RevisionCertificado.ROTO, RevisionCertificado.SIN_CERTIFICADO);
    static final int EXCEPTION_CHARS = 500;

    private final JdbcTemplate jdbc;
    private final AdminCertificates certificates;
    private final JobQueue queue;
    private final AuditLogService audit;

    public AdminOpsController(JdbcTemplate jdbc, AdminCertificates certificates, JobQueue queue, AuditLogService audit) {
        this.jdbc = jdbc;
        this.certificates = certificates;
        this.queue = queue;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ uso del mes

    @Operation(summary = "Uso por empresa del mes (mes=yyyy-MM, Lima): facturas, boletas y notas por fecha de emisión")
    @AdminPermission(AdminPermissions.USAGE_VIEW)
    @GetMapping("/usage")
    public ResponseEntity<Object> usage(@RequestParam(required = false) String mes, @RequestParam(required = false) String records,
            @RequestParam(required = false) String numPage, @RequestParam(required = false) String textFilter) {
        YearMonth month = AdminPanelParams.month(mes);
        AdminPaging paging = AdminPaging.of(records, numPage);
        String text = AdminPanelParams.text(textFilter);
        LocalDateTime[] range = AdminDocuments.monthRange(month);

        StringBuilder where = new StringBuilder(" WHERE t.deleted_at IS NULL");
        List<Object> filterArgs = new ArrayList<>();
        if (text != null) {
            String like = AdminFormat.likeContains(text);
            where.append(" AND (t.ruc LIKE ? ESCAPE '\\' OR f_unaccent_lower(coalesce(t.razon_social, '')) LIKE (f_unaccent_lower(?)) ESCAPE '\\')");
            filterArgs.add(like);
            filterArgs.add(like);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM tenants t" + where, Long.class, filterArgs.toArray());

        List<Object> args = new ArrayList<>();
        String docs = AdminDocuments.union("tenant_id, sunat_status", "fecha_emision >= ? AND fecha_emision < ?", List.of(range[0], range[1]), args);
        args.addAll(filterArgs);
        args.add(paging.records());
        args.add(paging.offset());
        String sql = "WITH agg AS (SELECT tenant_id, count(*) AS issued, "
                + "count(*) FILTER (WHERE sunat_status = 'aceptado') AS accepted, "
                + "count(*) FILTER (WHERE sunat_status = 'rechazado') AS rejected, "
                + "count(*) FILTER (WHERE sunat_status IN ('pendiente', 'enviado')) AS pending, "
                + "count(*) FILTER (WHERE sunat_status IN ('anulado', 'anulacion_en_proceso')) AS voided "
                + "FROM (" + docs + ") d GROUP BY tenant_id) "
                + "SELECT t.id, t.ruc, t.razon_social, t.plan, t.max_documents_month, coalesce(a.issued, 0) AS issued, "
                + "coalesce(a.accepted, 0) AS accepted, coalesce(a.rejected, 0) AS rejected, coalesce(a.pending, 0) AS pending, "
                + "coalesce(a.voided, 0) AS voided FROM tenants t LEFT JOIN agg a ON a.tenant_id = t.id" + where
                + " ORDER BY coalesce(a.issued, 0) DESC, t.id DESC LIMIT ? OFFSET ?";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args.toArray()).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tenantId", AdminFormat.longOrNull(r.get("id")));
            m.put("ruc", r.get("ruc"));
            m.put("razonSocial", r.get("razon_social"));
            m.put("plan", r.get("plan"));
            m.put("maxDocumentsMonth", AdminFormat.intOrZero(r.get("max_documents_month")));
            m.put("issued", AdminFormat.longOrNull(r.get("issued")));
            m.put("accepted", AdminFormat.longOrNull(r.get("accepted")));
            m.put("rejected", AdminFormat.longOrNull(r.get("rejected")));
            m.put("pending", AdminFormat.longOrNull(r.get("pending")));
            m.put("voided", AdminFormat.longOrNull(r.get("voided")));
            return m;
        }).toList();
        return AdminResponse.page(rows, total == null ? 0 : total);
    }

    // ------------------------------------------------------------------ certificados

    @Operation(summary = "Certificado de cada empresa activa (dias de aviso, status OK / POR_VENCER / VENCIDO / ROTO / SIN_CERTIFICADO)")
    @AdminPermission(AdminPermissions.CERTIFICATES_VIEW)
    @GetMapping("/certificates")
    public ResponseEntity<Object> certificates(@RequestParam(required = false) String dias, @RequestParam(required = false) String status,
            @RequestParam(required = false) String records, @RequestParam(required = false) String numPage) {
        int days = AdminPanelParams.intParam("dias", dias, certificates.defaultDays(), 0, 3650);
        String wanted = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (wanted != null && !CERT_STATUSES.contains(wanted)) {
            wanted = null;
        }
        AdminPaging paging = AdminPaging.of(records, numPage);
        String filter = wanted;
        List<AdminCertificates.Row> all = certificates.activeTenants(days).stream()
                .filter(r -> filter == null || filter.equals(r.status())).toList();
        int from = (int) Math.min(all.size(), paging.offset());
        int to = Math.min(all.size(), from + paging.records());
        return AdminResponse.page(all.subList(from, to).stream().map(AdminCertificates.Row::toMap).toList(), all.size());
    }

    // ------------------------------------------------------------------ rechazos

    @Operation(summary = "Comprobantes rechazados u observados por SUNAT (startDate / endDate yyyy-MM-dd Lima, por defecto 7 días)")
    @AdminPermission(AdminPermissions.REJECTIONS_VIEW)
    @GetMapping("/rejections")
    public ResponseEntity<Object> rejections(@RequestParam(required = false) String startDate, @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String tenantId, @RequestParam(required = false) String textFilter,
            @RequestParam(required = false) String records, @RequestParam(required = false) String numPage) {
        LocalDate end = AdminFormat.parseDay("endDate", endDate);
        LocalDate start = AdminFormat.parseDay("startDate", startDate);
        if (end == null) {
            end = start != null && start.plusDays(6).isBefore(Clock.todayLima()) ? start.plusDays(6) : Clock.todayLima();
        }
        if (start == null) {
            start = end.minusDays(6);
        }
        AdminPanelParams.checkRange(start, end);
        Long tenant = AdminPanelParams.longParam("tenantId", tenantId);
        String text = AdminPanelParams.text(textFilter);
        AdminPaging paging = AdminPaging.of(records, numPage);
        LocalDateTime[] range = AdminDocuments.dayRange(start, end);

        List<Object> args = new ArrayList<>();
        List<Object> whereArgs = new ArrayList<>(List.of(range[0], range[1]));
        String where = "fecha_emision >= ? AND fecha_emision < ? AND (sunat_status = 'rechazado' OR (sunat_status = 'aceptado' AND "
                + AdminDocuments.OBSERVED + "))";
        if (tenant != null) {
            where += " AND tenant_id = ?";
            whereArgs.add(tenant);
        }
        String docs = AdminDocuments.union("id, tenant_id, {tipo} AS tipo, {tipoNombre} AS tipo_nombre, serie, correlativo, fecha_emision, "
                + "sunat_status, sunat_code, sunat_description, sunat_notes::text AS sunat_notes", where, whereArgs, args);
        String outer = " FROM (" + docs + ") x JOIN tenants t ON t.id = x.tenant_id";
        if (text != null) {
            String like = AdminFormat.likeContains(text.toUpperCase());
            outer += " WHERE (t.ruc LIKE ? ESCAPE '\\' OR upper(x.serie) LIKE ? ESCAPE '\\' OR upper(x.serie || '-' || x.correlativo) LIKE ? ESCAPE '\\')";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT count(*)" + outer, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(paging.records());
        pageArgs.add(paging.offset());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT x.*, t.ruc, t.razon_social" + outer
                + " ORDER BY x.fecha_emision DESC, x.tipo, x.id DESC LIMIT ? OFFSET ?", pageArgs.toArray()).stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", AdminFormat.longOrNull(r.get("id")));
                    m.put("tenantId", AdminFormat.longOrNull(r.get("tenant_id")));
                    m.put("ruc", r.get("ruc"));
                    m.put("razonSocial", r.get("razon_social"));
                    m.put("tipo", r.get("tipo"));
                    m.put("tipoNombre", r.get("tipo_nombre"));
                    m.put("serie", r.get("serie"));
                    m.put("correlativo", r.get("correlativo"));
                    m.put("fechaEmision", AdminFormat.day(r.get("fecha_emision")));
                    m.put("sunatStatus", r.get("sunat_status"));
                    m.put("sunatCode", r.get("sunat_code"));
                    m.put("sunatDescription", r.get("sunat_description"));
                    Object notes = r.get("sunat_notes") == null ? null : PhpJson.decode(String.valueOf(r.get("sunat_notes")));
                    m.put("sunatNotes", notes instanceof List<?> l ? l : List.of());
                    return m;
                }).toList();
        return AdminResponse.page(rows, total == null ? 0 : total);
    }

    // ------------------------------------------------------------------ trabajos fallidos

    @Operation(summary = "Trabajos de la cola que agotaron sus intentos (failed_jobs)")
    @AdminPermission(AdminPermissions.JOBS_VIEW)
    @GetMapping("/jobs")
    public ResponseEntity<Object> jobs(@RequestParam(required = false) String queue, @RequestParam(required = false) String records,
            @RequestParam(required = false) String numPage) {
        AdminPaging paging = AdminPaging.of(records, numPage);
        String q = AdminPanelParams.text(queue);
        String where = q == null ? "" : " WHERE queue = ?";
        List<Object> args = new ArrayList<>();
        if (q != null) {
            args.add(q);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM failed_jobs" + where, Long.class, args.toArray());
        args.add(paging.records());
        args.add(paging.offset());
        // El payload guarda los intentos previos al último: el último intento fallido suma uno (JobQueue.run)
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, queue, payload->>'type' AS type, payload->'data'->>'id' AS document_id, "
                + "payload->>'attempts' AS attempts, left(exception, " + EXCEPTION_CHARS + ") AS exception, failed_at FROM failed_jobs" + where
                + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray()).stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", AdminFormat.longOrNull(r.get("id")));
                    m.put("queue", r.get("queue"));
                    m.put("type", r.get("type"));
                    m.put("documentId", digits(r.get("document_id")));
                    Long attempts = digits(r.get("attempts"));
                    m.put("attempts", attempts == null ? null : attempts + 1);
                    m.put("exception", r.get("exception"));
                    m.put("failedAt", AdminFormat.instant(r.get("failed_at")));
                    return m;
                }).toList();
        return AdminResponse.page(rows, total == null ? 0 : total);
    }

    @Operation(summary = "Reencola un trabajo fallido")
    @AdminPermission(AdminPermissions.JOBS_RETRY)
    @PostMapping("/jobs/{id}/retry")
    public ResponseEntity<Object> retry(@PathVariable long id, HttpServletRequest request) {
        AdminPrincipal me = AdminPrincipal.require(request);
        int n = queue.retryFailed(id);
        if (n == 0) {
            throw AdminException.fail("No existe el trabajo fallido (quizá ya se reintentó).");
        }
        audit.registrarAdmin(request, me.userId(), me.email(), "admin.jobs.reintentado", null, "failed_jobs", id,
                "Trabajo fallido #" + id + " reencolado", null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("retried", n);
        return AdminResponse.ok(data, "Trabajo reencolado.");
    }

    @Operation(summary = "Reencola todos los trabajos fallidos")
    @AdminPermission(AdminPermissions.JOBS_RETRY)
    @PostMapping("/jobs/retry-all")
    public ResponseEntity<Object> retryAll(HttpServletRequest request) {
        AdminPrincipal me = AdminPrincipal.require(request);
        int n = queue.retryFailed(null);
        audit.registrarAdmin(request, me.userId(), me.email(), "admin.jobs.reintentados", null, "failed_jobs", null,
                n + " trabajo(s) fallido(s) reencolado(s)", Map.of("total", n));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("retried", n);
        return AdminResponse.ok(data, n + " trabajo(s) reencolado(s).");
    }

    // ------------------------------------------------------------------ auditoría

    @Operation(summary = "Bitácora de auditoría (solo superadmin; startDate / endDate yyyy-MM-dd Lima, textFilter, actorTipo)")
    @AdminPermission(AdminPermissions.AUDIT_VIEW)
    @GetMapping("/audit")
    public ResponseEntity<Object> audit(@RequestParam(required = false) String startDate, @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String textFilter, @RequestParam(required = false) String actorTipo,
            @RequestParam(required = false) String records, @RequestParam(required = false) String numPage) {
        LocalDate start = AdminFormat.parseDay("startDate", startDate);
        LocalDate end = AdminFormat.parseDay("endDate", endDate);
        if (start != null && end != null && start.isAfter(end)) {
            AdminPanelParams.checkRange(start, end);
        }
        AdminPaging paging = AdminPaging.of(records, numPage);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (start != null) {
            where.append(" AND created_at >= ?");
            args.add(limaDayStartUtc(start));
        }
        if (end != null) {
            where.append(" AND created_at < ?");
            args.add(limaDayStartUtc(end.plusDays(1)));
        }
        String actor = AdminPanelParams.text(actorTipo);
        if (actor != null) {
            where.append(" AND actor_tipo = ?");
            args.add(actor);
        }
        String text = AdminPanelParams.text(textFilter);
        if (text != null) {
            String like = AdminFormat.likeContains(text);
            where.append(" AND (f_unaccent_lower(accion) LIKE (f_unaccent_lower(?)) ESCAPE '\\'"
                    + " OR f_unaccent_lower(coalesce(descripcion, '')) LIKE (f_unaccent_lower(?)) ESCAPE '\\'"
                    + " OR f_unaccent_lower(coalesce(actor_etiqueta, '')) LIKE (f_unaccent_lower(?)) ESCAPE '\\')");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM audit_logs" + where, Long.class, args.toArray());
        args.add(paging.records());
        args.add(paging.offset());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, created_at, actor_tipo, actor_etiqueta, accion, tenant_id, descripcion, ip "
                + "FROM audit_logs" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", args.toArray()).stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", AdminFormat.longOrNull(r.get("id")));
                    m.put("createdAt", AdminFormat.instant(r.get("created_at")));
                    m.put("actorTipo", r.get("actor_tipo"));
                    m.put("actorEtiqueta", r.get("actor_etiqueta"));
                    m.put("accion", r.get("accion"));
                    m.put("tenantId", AdminFormat.longOrNull(r.get("tenant_id")));
                    m.put("descripcion", r.get("descripcion"));
                    m.put("ip", r.get("ip"));
                    return m;
                }).toList();
        return AdminResponse.page(rows, total == null ? 0 : total);
    }

    /** 00:00 de Lima de ese día, como hora UTC (created_at se guarda en UTC). */
    static LocalDateTime limaDayStartUtc(LocalDate day) {
        return day.atStartOfDay(Clock.LIMA).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static Long digits(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.matches("\\d{1,18}") ? Long.parseLong(s) : null;
    }
}
