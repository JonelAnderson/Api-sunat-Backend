package pe.facturacion.api.modules.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.time.Clock;

/**
 * {@code AuditLog::registrar()} de API-PRO: bitácora append-only de acciones sensibles.
 * En la API el actor es la empresa ({@code actor_tipo=api}, {@code "API empresa {ruc}"}).
 * Nunca lanza: auditar no debe romper la acción.
 */
@Service
public class AuditLogService {

    /** Clase del sujeto como la guarda Laravel ({@code $subject::class}). */
    public static final String SUBJECT_TENANT = "App\\Models\\Tenant";

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final JdbcTemplate jdbc;

    public AuditLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void registrar(HttpServletRequest request, String accion, Long tenantId, String subjectType, Object subjectId,
            String descripcion, Map<String, Object> metadata) {
        try {
            Tenant tenant = request == null ? null : ApiContext.tenant(request);
            String actorTipo = tenant != null ? "api" : "sistema";
            String actorEtiqueta = tenant != null ? "API empresa " + (tenant.getRuc() != null ? tenant.getRuc() : tenant.getId()) : null;
            Long tid = tenantId != null ? tenantId : (tenant != null ? tenant.getId() : null);
            String ua = request == null ? null : request.getHeader("User-Agent");
            jdbc.update("INSERT INTO audit_logs (tenant_id, user_id, actor_tipo, actor_etiqueta, accion, subject_type, subject_id, "
                    + "descripcion, ip, user_agent, metadata, created_at) VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                    tid, actorTipo, actorEtiqueta, accion, subjectType, subjectId == null ? null : subjectId.toString(),
                    descripcion, request == null ? null : request.getRemoteAddr(),
                    ua == null ? "" : ua.substring(0, Math.min(512, ua.length())),
                    metadata == null ? null : PhpJson.encodeUnescaped(metadata),
                    Timestamp.valueOf(Clock.nowUtc()));
        } catch (RuntimeException e) {
            log.warn("No se pudo registrar auditoría {}: {}", accion, e.getMessage());
        }
    }

    /** Clase del sujeto de las acciones sobre administradores. */
    public static final String SUBJECT_USER = "App\\Models\\User";
    /** Sujeto de las acciones sobre personas del portal de clientes ({@code portal_users}). */
    public static final String SUBJECT_PORTAL_USER = "PortalUser";

    /**
     * Acción de una persona del portal de clientes ({@code /api/portal/**}) o de la consola sobre ellas:
     * {@code actor_tipo=portal} (o {@code consola}), {@code user_id} vacío (los ids de {@code portal_users} no son los de
     * {@code users}), {@code actor_etiqueta} = su correo (o {@code consola:<usuario>}) y el id de la persona en el
     * sujeto. Nunca lanza y nunca guarda contraseñas, códigos, tokens ni credenciales.
     */
    public void registrarPortal(HttpServletRequest request, String actorTipo, String actorEtiqueta, String accion, Long tenantId,
            String subjectType, Object subjectId, String descripcion, Map<String, Object> metadata) {
        try {
            String ua = request == null ? null : request.getHeader("User-Agent");
            String etiqueta = actorEtiqueta == null ? null : actorEtiqueta.substring(0, Math.min(255, actorEtiqueta.length()));
            String desc = descripcion == null ? null : descripcion.substring(0, Math.min(255, descripcion.length()));
            jdbc.update("INSERT INTO audit_logs (tenant_id, user_id, actor_tipo, actor_etiqueta, accion, subject_type, subject_id, "
                    + "descripcion, ip, user_agent, metadata, created_at) VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                    tenantId, actorTipo, etiqueta, accion, subjectType, subjectId == null ? null : subjectId.toString(), desc,
                    request == null ? null : request.getRemoteAddr(),
                    ua == null ? "" : ua.substring(0, Math.min(512, ua.length())),
                    metadata == null ? null : PhpJson.encodeUnescaped(metadata),
                    Timestamp.valueOf(Clock.nowUtc()));
        } catch (RuntimeException e) {
            log.warn("No se pudo registrar auditoría {}: {}", accion, e.getMessage());
        }
    }

    /**
     * Acción del panel de administración ({@code /api/admin/**}): {@code actor_tipo=admin}, {@code user_id} del
     * administrador y {@code actor_etiqueta} = su correo. Sin administrador conocido (login fallido con un correo que
     * no existe) {@code user_id} queda vacío. Nunca lanza y nunca guarda contraseñas, códigos ni tokens.
     */
    public void registrarAdmin(HttpServletRequest request, Long userId, String email, String accion, Long tenantId,
            String subjectType, Object subjectId, String descripcion, Map<String, Object> metadata) {
        try {
            String ua = request == null ? null : request.getHeader("User-Agent");
            String etiqueta = email == null ? null : email.substring(0, Math.min(255, email.length()));
            String desc = descripcion == null ? null : descripcion.substring(0, Math.min(255, descripcion.length()));
            // La consola de plataforma (acceso de servicio) actúa con userId 0: no es un usuario de la tabla users, así que
            // user_id queda vacío (si no, la clave foránea rechazaría la fila) y el actor se identifica por actor_etiqueta
            Long auditUserId = userId != null && userId > 0 ? userId : null;
            jdbc.update("INSERT INTO audit_logs (tenant_id, user_id, actor_tipo, actor_etiqueta, accion, subject_type, subject_id, "
                    + "descripcion, ip, user_agent, metadata, created_at) VALUES (?, ?, 'admin', ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                    tenantId, auditUserId, etiqueta, accion, subjectType, subjectId == null ? null : subjectId.toString(), desc,
                    request == null ? null : request.getRemoteAddr(),
                    ua == null ? "" : ua.substring(0, Math.min(512, ua.length())),
                    metadata == null ? null : PhpJson.encodeUnescaped(metadata),
                    Timestamp.valueOf(Clock.nowUtc()));
        } catch (RuntimeException e) {
            log.warn("No se pudo registrar auditoría {}: {}", accion, e.getMessage());
        }
    }
}
