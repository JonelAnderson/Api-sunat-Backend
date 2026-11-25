package pe.facturacion.api.modules.admin.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.admin.security.AdminPrincipal;
import pe.facturacion.api.modules.audit.AuditLogService;
import pe.facturacion.api.modules.tenant.domain.Tenant;

/**
 * Pista de auditoría de las escrituras de las fases C y D (§11.8 del diseño): {@code audit_logs} con
 * {@code actor_tipo = admin}, el administrador ({@code user_id}, {@code actor_etiqueta} = correo), la empresa
 * ({@code tenant_id}, sujeto {@code App\Models\Tenant}) y la metadata de la acción (documento, motivo, resultado).
 * Nunca guarda credenciales: quien llama arma la metadata sin valores secretos.
 */
@Component
public class AdminActionAudit {

    private final AuditLogService audit;

    public AdminActionAudit(AuditLogService audit) {
        this.audit = audit;
    }

    public void record(HttpServletRequest request, String accion, Tenant tenant, String descripcion, Map<String, Object> metadata) {
        AdminPrincipal me = AdminPrincipal.require(request);
        audit.registrarAdmin(request, me.userId(), me.email(), accion, tenant.getId(), AuditLogService.SUBJECT_TENANT, tenant.getId(),
                descripcion, metadata);
    }

    /** Metadata ordenada sin las claves con valor null. */
    public static Map<String, Object> meta(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put((String) kv[i], kv[i + 1]);
            }
        }
        return m;
    }
}
