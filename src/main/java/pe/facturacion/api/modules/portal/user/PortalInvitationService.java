package pe.facturacion.api.modules.portal.user;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminFormat;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.security.AdminPrincipal;
import pe.facturacion.api.modules.audit.AuditLogService;
import pe.facturacion.api.modules.portal.security.PortalSessionCache;
import pe.facturacion.api.shared.time.Clock;

/**
 * Alta de las personas del portal por invitación (la pide la consola de plataforma por {@code /api/admin/portal/**}).
 * Una invitación nueva a un correo que ya existe <b>reemplaza</b> lo anterior: el nombre, el conjunto de empresas, la
 * contraseña (queda sin contraseña hasta que la persona active con el enlace nuevo), el bloqueo y, con
 * {@code resetMfa}, el MFA; reactiva la cuenta, cierra todas sus sesiones e invalida las invitaciones anteriores.
 *
 * <p>El token (32 bytes al azar, Base64 URL) solo existe en la respuesta de este método: en la base queda su SHA-256.
 * Vence a las 72 h y sirve una sola vez.
 */
@Service
public class PortalInvitationService {

    public static final Duration INVITATION_TTL = Duration.ofHours(72);
    /** Tope de empresas por persona (una persona no administra cientos de empresas emisoras). */
    public static final int MAX_TENANTS = 50;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Resultado: el token en claro se entrega una sola vez. */
    public record Invitation(long userId, String token, LocalDateTime expiresAt, boolean reinvited) {
    }

    private final PortalUserStore users;
    private final PortalSessionCache sessions;
    private final AuditLogService audit;

    public PortalInvitationService(PortalUserStore users, PortalSessionCache sessions, AuditLogService audit) {
        this.users = users;
        this.sessions = sessions;
        this.audit = audit;
    }

    /**
     * Crea o reinvita a la persona y emite la invitación. {@code email} ya viene en minúsculas y validado, y
     * {@code tenantIds} sin vacíos ni repetidos.
     */
    @Transactional
    public Invitation invite(String email, String name, List<Long> tenantIds, boolean resetMfa, AdminPrincipal actor,
            HttpServletRequest request) {
        Set<Long> wanted = new LinkedHashSet<>(tenantIds);
        Set<Long> existing = users.existingTenantIds(wanted);
        List<Long> missing = wanted.stream().filter(id -> !existing.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new AdminException(200, List.of("No existen o fueron eliminadas las empresas: " + missing.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(", ")) + "."),
                    List.of(AdminResponse.fieldError("tenantIds", "Alguna empresa no existe o fue eliminada.")));
        }

        LocalDateTime now = Clock.nowUtc().withNano(0);
        long userId = users.insert(email, name, now);
        boolean reinvited = userId == 0;
        if (reinvited) {
            Optional<PortalUser> found = users.findByEmail(email);
            userId = found.orElseThrow(() -> AdminException.fail("No se pudo crear la invitación. Inténtalo de nuevo.")).id();
            users.reinvite(userId, name, resetMfa, now);
            sessions.invalidate(userId);
        }
        users.replaceTenants(userId, wanted, now);
        users.invalidateInvitations(userId, now);

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        LocalDateTime expiresAt = now.plus(INVITATION_TTL);
        users.insertInvitation(userId, PortalAuthService.sha256(token), expiresAt, now, actor.email());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("reinvitado", reinvited);
        meta.put("reset_mfa", resetMfa);
        meta.put("empresas", List.copyOf(wanted));
        meta.put("vence", AdminFormat.instant(expiresAt));
        // Nunca el token ni su hash
        audit.registrarAdmin(request, actor.userId(), actor.email(), reinvited ? "admin.portal.reinvitado" : "admin.portal.invitado", null,
                AuditLogService.SUBJECT_PORTAL_USER, userId,
                (reinvited ? "Portal: reinvitó a " : "Portal: invitó a ") + email + " (" + wanted.size() + " empresa(s))", meta);
        return new Invitation(userId, token, expiresAt, reinvited);
    }

    /**
     * Habilita o deshabilita a la persona. Idempotente. Al deshabilitar cierra todas sus sesiones al instante (sube
     * {@code session_version} y revoca los refresh tokens).
     */
    @Transactional
    public PortalUser setActive(long id, boolean active, AdminPrincipal actor, HttpServletRequest request) {
        PortalUser user = users.findById(id).orElseThrow(() -> AdminException.status(404, "No existe el usuario del portal."));
        LocalDateTime now = Clock.nowUtc().withNano(0);
        users.setActive(id, active, now);
        if (!active) {
            users.bumpSession(id, now);
        }
        sessions.invalidate(id);
        audit.registrarAdmin(request, actor.userId(), actor.email(), active ? "admin.portal.habilitado" : "admin.portal.deshabilitado", null,
                AuditLogService.SUBJECT_PORTAL_USER, id,
                "Portal: " + (active ? "habilitó" : "deshabilitó") + " a " + user.email(), null);
        return users.findById(id).orElse(user);
    }
}
