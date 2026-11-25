package pe.facturacion.api.modules.admin.document;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.admin.api.AdminActionAudit;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminV1Actions;
import pe.facturacion.api.modules.admin.security.AdminPrincipal;
import pe.facturacion.api.modules.admin.security.AdminRateLimiter;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.EmissionRoutes;
import pe.facturacion.api.security.RateLimitInterceptor;

/**
 * Reglas comunes de las acciones ante SUNAT en nombre de una empresa (fase D, §11.6 del diseño):
 * <ol>
 *   <li>la empresa debe estar activa (una empresa suspendida no puede usar la API, así que el panel tampoco opera sus
 *       comprobantes);</li>
 *   <li>cada acción cuenta en la ventana por minuto del administrador
 *       ({@link AdminRateLimiter#DOCUMENT_ACTIONS_PER_MINUTE}) y en la de la empresa (la de {@code /api/v1}, según su
 *       plan): agotada → 429;</li>
 *   <li>la llamada al servicio de {@code /api/v1} se audita siempre que llega a responder (también si responde con un
 *       error de negocio), con el administrador, la empresa, el documento, el motivo y el resultado.</li>
 * </ol>
 */
@Component
public class AdminDocumentGuard {

    static final String SUSPENDED = "La empresa está suspendida: actívala antes de operar sus comprobantes.";
    static final String LIMIT_TENANT = "La empresa alcanzó su límite de solicitudes por minuto. Intenta de nuevo en un momento.";
    static final String LIMIT_ADMIN = "Alcanzaste el límite de acciones sobre comprobantes por minuto. Intenta de nuevo en un momento.";

    private final RateLimitInterceptor tenantLimit;
    private final AdminRateLimiter adminLimit;
    private final AdminActionAudit audit;

    public AdminDocumentGuard(RateLimitInterceptor tenantLimit, AdminRateLimiter adminLimit, AdminActionAudit audit) {
        this.tenantLimit = tenantLimit;
        this.adminLimit = adminLimit;
        this.audit = audit;
    }

    /** La empresa puede operar comprobantes (activa). */
    public void requireActive(Tenant tenant) {
        if (!tenant.isActive()) {
            throw AdminException.fail(SUSPENDED);
        }
    }

    /** La empresa puede emitir comprobantes nuevos: no está suspendida por falta de pago (consulta y reenvío sí). */
    public void requireCanEmit(Tenant tenant) {
        if (tenant.isBillingSuspended()) {
            throw AdminException.fail(EmissionRoutes.BILLING_SUSPENDED_MESSAGE);
        }
    }

    /** Cuenta la acción en los límites del administrador y de la empresa (429 si alguno se agotó). */
    public void consume(HttpServletRequest request, Tenant tenant) {
        if (!adminLimit.allowDocumentAction(AdminPrincipal.require(request).userId())) {
            throw AdminException.status(429, LIMIT_ADMIN);
        }
        boolean allowed;
        try {
            allowed = tenantLimit.consumeTenant(tenant);
        } catch (RuntimeException e) {
            throw AdminException.status(503, "No se pudo verificar el límite de solicitudes de la empresa. Intenta de nuevo.");
        }
        if (!allowed) {
            throw AdminException.status(429, LIMIT_TENANT);
        }
    }

    /**
     * Llama al servicio de {@code /api/v1}, audita el resultado ({@code accion}, con {@code meta} + {@code http},
     * {@code resultado} y, si hubo respuesta correcta, el id / número / estado SUNAT) y devuelve el sobre del panel.
     */
    public ResponseEntity<Object> call(HttpServletRequest request, Tenant tenant, String accion, String descripcion,
            Map<String, Object> meta, Supplier<ResponseEntity<Object>> v1Call) {
        ResponseEntity<Object> v1 = v1Call.get();
        Map<String, Object> m = new java.util.LinkedHashMap<>(meta);
        m.put("http", v1.getStatusCode().value());
        boolean ok = AdminV1Actions.ok(v1);
        m.put("resultado", ok ? "ok" : "error");
        if (ok) {
            Object data = AdminV1Actions.data(v1);
            Long resultId = AdminV1Actions.documentId(data);
            String numero = AdminV1Actions.documentNumber(data);
            String estado = AdminV1Actions.sunatStatus(data);
            if (!m.containsKey("documento_id") && resultId != null) {
                m.put("documento_id", resultId);
            } else if (resultId != null && !resultId.equals(m.get("documento_id"))) {
                m.put("resultado_id", resultId);
            }
            if (numero != null) {
                m.put("numero", numero);
            }
            if (estado != null) {
                m.put("estado_sunat", estado);
            }
        } else if (v1.getBody() instanceof Map<?, ?> b && b.get("mensaje") != null) {
            String msg = String.valueOf(b.get("mensaje"));
            m.put("mensaje", msg.length() > 300 ? msg.substring(0, 300) : msg);
        }
        audit.record(request, accion, tenant, descripcion, m);
        return AdminV1Actions.envelope(v1);
    }
}
