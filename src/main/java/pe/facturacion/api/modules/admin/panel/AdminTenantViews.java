package pe.facturacion.api.modules.admin.panel;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminPaging;
import pe.facturacion.api.modules.admin.security.AdminPrincipal;
import pe.facturacion.api.modules.audit.AuditLogService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.http.RequestInput;

/**
 * Apoyo de las vistas de empresa del panel ({@code /api/admin/tenants/{id}/...}): la empresa (404 si no existe), la
 * entrada que reciben los servicios de {@code /api/v1} y la auditoría.
 *
 * <p>Las vistas llaman a los mismos servicios que usa la empresa con su {@code X-Api-Key}, pero con la empresa de la
 * ruta: nunca se usan las credenciales de la empresa ni se llama a {@code /api/v1} por HTTP.
 *
 * <p><b>Auditoría</b> ({@code audit_logs}, {@code actor_tipo='admin'}): el detalle de un documento, los reportes y las
 * descargas se registran siempre; los listados, el panel y la configuración una sola vez por administrador, empresa y
 * vista cada {@link #LIST_AUDIT_WINDOW} (pasar de página, cambiar un filtro o recargar el tablero no llena la bitácora).
 * Si Redis no responde se registra igual.
 */
@Component
public class AdminTenantViews {

    public static final String ACCION_CONSULTA = "admin.empresa.consulta";
    public static final String ACCION_DESCARGA = "admin.empresa.descarga";
    public static final String ACCION_CONSULTA_SUNAT = "admin.empresa.consulta_sunat";
    static final Duration LIST_AUDIT_WINDOW = Duration.ofMinutes(5);
    static final String AUDIT_KEY_PREFIX = "admin-audit:vista:";

    /** Parámetros del panel que no se pasan a los servicios de {@code /api/v1}. */
    static final List<String> ADMIN_PARAMS = List.of("records", "numPage", "textFilter", "stateFilter", "inline");
    /** Paginación de {@code /api/v1}: la fija el panel ({@code records}/{@code numPage}, tope 50). */
    static final List<String> V1_PAGING = List.of("page", "por_pagina", "per_page");

    private static final Logger log = LoggerFactory.getLogger(AdminTenantViews.class);

    private final TenantRepository tenants;
    private final AuditLogService audit;
    private final StringRedisTemplate redis;

    public AdminTenantViews(TenantRepository tenants, AuditLogService audit, StringRedisTemplate redis) {
        this.tenants = tenants;
        this.audit = audit;
        this.redis = redis;
    }

    /** Empresa de la ruta; si no existe (o está eliminada) responde 404 con el sobre. */
    public Tenant tenant(long id) {
        return tenants.findById(id).orElseThrow(() -> AdminException.status(404, "No existe la empresa."));
    }

    /**
     * {@code inline} de las descargas de PDF: {@code 1} / {@code true} → {@code Content-Disposition: inline} (vista
     * previa en el navegador), {@code 0} / {@code false} → {@code attachment}; sin el parámetro, la de la API.
     */
    public static Boolean inline(HttpServletRequest request) {
        String v = request.getParameter("inline");
        if (v == null) {
            return null;
        }
        return switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "1", "true" -> Boolean.TRUE;
            case "0", "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** Entrada sin paginación (detalle, panel, reportes): los parámetros de la petición tal cual, sin los del panel. */
    public Input input(HttpServletRequest request) {
        return toV1Input(RequestInput.all(request), null, null);
    }

    /** Igual que {@link #input(HttpServletRequest)} sin algunos parámetros (p. ej. {@code formato}: reportes solo JSON). */
    public Input input(HttpServletRequest request, List<String> drop) {
        Input in = input(request);
        drop.forEach(in.all()::remove);
        return in;
    }

    /** Entrada de un listado: {@code records}/{@code numPage} pasan a {@code por_pagina}/{@code per_page}/{@code page}. */
    public Input pagedInput(HttpServletRequest request, String textAlias) {
        Map<String, Object> raw = RequestInput.all(request);
        AdminPaging paging = AdminPaging.of(text(raw.get("records")), text(raw.get("numPage")));
        return toV1Input(raw, paging, textAlias);
    }

    /**
     * Entrada para un servicio de {@code /api/v1}: quita los parámetros del panel; con paginación fija la página y el
     * tamaño (pisa los que mande el cliente, para respetar el tope de 50); {@code textFilter} se copia al parámetro de
     * búsqueda de esa vista ({@code textAlias}) si el cliente no lo mandó.
     */
    static Input toV1Input(Map<String, Object> raw, AdminPaging paging, String textAlias) {
        Map<String, Object> in = new LinkedHashMap<>(raw);
        Object text = raw.get("textFilter");
        ADMIN_PARAMS.forEach(in::remove);
        if (paging != null) {
            V1_PAGING.forEach(in::remove);
            in.put("page", String.valueOf(paging.numPage()));
            in.put("por_pagina", String.valueOf(paging.records()));
            in.put("per_page", String.valueOf(paging.records()));
        }
        if (textAlias != null && text instanceof String s && !s.isBlank() && !in.containsKey(textAlias)) {
            in.put(textAlias, s.trim());
        }
        return new Input(in);
    }

    private static String text(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    // ------------------------------------------------------------------ auditoría

    /** Consulta de un listado, del panel o de la configuración (una vez por ventana). */
    public void auditView(HttpServletRequest request, Tenant tenant, String vista) {
        AdminPrincipal me = AdminPrincipal.require(request);
        if (!firstInWindow(me.userId(), tenant.getId(), vista)) {
            return;
        }
        write(request, me, ACCION_CONSULTA, tenant, "Panel: consulta de " + vista + " de la empresa " + tenant.getRuc(), meta(vista, null, null, null));
    }

    /** Detalle de un documento o un reporte (siempre). */
    public void auditDetail(HttpServletRequest request, Tenant tenant, String vista, String tipo, Long documentId) {
        AdminPrincipal me = AdminPrincipal.require(request);
        write(request, me, ACCION_CONSULTA, tenant, "Panel: consulta de " + vista + (documentId == null ? "" : " " + documentId)
                + " de la empresa " + tenant.getRuc(), meta(vista, tipo, documentId, null));
    }

    /** Descarga de un archivo (siempre). */
    public void auditDownload(HttpServletRequest request, Tenant tenant, String vista, String tipo, long documentId, String archivo) {
        AdminPrincipal me = AdminPrincipal.require(request);
        write(request, me, ACCION_DESCARGA, tenant, "Panel: descarga del " + archivo.toUpperCase(java.util.Locale.ROOT) + " de " + vista
                + " " + documentId + " de la empresa " + tenant.getRuc(), meta(vista, tipo, documentId, archivo));
    }

    /** Exportación ZIP (siempre), con el rango, los tipos y el número de archivos y bytes. */
    public void auditExport(HttpServletRequest request, Tenant tenant, Map<String, Object> meta) {
        AdminPrincipal me = AdminPrincipal.require(request);
        write(request, me, ACCION_DESCARGA, tenant, "Panel: exportación ZIP de comprobantes (" + meta.get("fecha_desde") + " a "
                + meta.get("fecha_hasta") + ", " + meta.get("archivos") + " archivos) de la empresa " + tenant.getRuc(), meta);
    }

    /**
     * Consulta en vivo a SUNAT con las credenciales guardadas de la empresa (siempre, también si SUNAT respondió con un
     * error): {@code admin.empresa.consulta_sunat}. La metadata lleva solo el documento consultado y el resultado.
     */
    public void auditSunatQuery(HttpServletRequest request, Tenant tenant, String vista, Map<String, Object> meta) {
        AdminPrincipal me = AdminPrincipal.require(request);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vista", vista);
        m.putAll(meta);
        write(request, me, ACCION_CONSULTA_SUNAT, tenant, "Panel: " + vista + " en SUNAT para la empresa " + tenant.getRuc(), m);
    }

    static Map<String, Object> meta(String vista, String tipo, Long documentId, String archivo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vista", vista);
        if (tipo != null) {
            m.put("tipo", tipo);
        }
        if (documentId != null) {
            m.put("documento_id", documentId);
        }
        if (archivo != null) {
            m.put("archivo", archivo);
        }
        return m;
    }

    private void write(HttpServletRequest request, AdminPrincipal me, String accion, Tenant tenant, String descripcion,
            Map<String, Object> meta) {
        audit.registrarAdmin(request, me.userId(), me.email(), accion, tenant.getId(), AuditLogService.SUBJECT_TENANT, tenant.getId(),
                descripcion, meta);
    }

    /** true si es la primera consulta de esa vista en la ventana (o si Redis no responde). */
    private boolean firstInWindow(long userId, long tenantId, String vista) {
        try {
            Boolean first = redis.opsForValue().setIfAbsent(AUDIT_KEY_PREFIX + userId + ":" + tenantId + ":" + vista, "1", LIST_AUDIT_WINDOW);
            return !Boolean.FALSE.equals(first);
        } catch (RuntimeException e) {
            log.warn("admin.auditoria.redis_no_disponible error={}", e.getClass().getSimpleName());
            return true;
        }
    }
}
