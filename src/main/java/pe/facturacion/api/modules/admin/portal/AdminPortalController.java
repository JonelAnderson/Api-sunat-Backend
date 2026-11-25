package pe.facturacion.api.modules.admin.portal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminBody;
import pe.facturacion.api.modules.admin.api.AdminFormat;
import pe.facturacion.api.modules.admin.api.AdminPaging;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.api.AdminValidation;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.admin.security.AdminPrincipal;
import pe.facturacion.api.modules.portal.user.PortalInvitationService;
import pe.facturacion.api.modules.portal.user.PortalUser;
import pe.facturacion.api.modules.portal.user.PortalUserStore;

/**
 * Administración de las personas del portal de clientes desde la consola de plataforma ({@code /api/admin/portal/**},
 * §6 del diseño). Va por el mismo esquema de acceso que el resto de {@code /api/admin/**}: {@code X-Admin-Key} +
 * {@code X-Admin-Actor} (rol de servicio) o el JWT de un superadmin del panel, con el permiso
 * {@link AdminPermissions#PORTAL_MANAGE}. Ninguna respuesta lleva contraseña, hash, clave del MFA ni códigos; el único
 * secreto es el token de la invitación, una sola vez, en la respuesta de {@code POST invitations}.
 */
@RestController
@RequestMapping("/api/admin/portal")
@Tag(name = "Panel de administración · portal de clientes")
public class AdminPortalController {

    private final PortalInvitationService invitations;
    private final PortalUserStore users;

    public AdminPortalController(PortalInvitationService invitations, PortalUserStore users) {
        this.invitations = invitations;
        this.users = users;
    }

    @Operation(summary = "Invita (o reinvita) a una persona al portal: crea o reemplaza usuario, empresas y contraseña; devuelve el token una sola vez")
    @AdminPermission(AdminPermissions.PORTAL_MANAGE)
    @PostMapping("/invitations")
    public ResponseEntity<Object> invite(HttpServletRequest request) {
        AdminBody b = AdminBody.of(request);
        String email = b.text("email") == null ? null : b.text("email").toLowerCase(Locale.ROOT);
        String name = b.text("name");
        List<Long> tenantIds = b.integers("tenantIds");
        AdminValidation v = new AdminValidation();
        if (email == null) {
            v.add("email", "El correo es obligatorio.");
        } else if (!AdminValidation.isEmail(email)) {
            v.add("email", "El correo no es válido.");
        }
        v.required("name", name, "El nombre es obligatorio.");
        v.maxLength("name", name, 255, "El nombre no debe superar los 255 caracteres.");
        if (tenantIds == null || tenantIds.isEmpty()) {
            v.add("tenantIds", "Indica al menos una empresa (arreglo de ids).");
        } else if (tenantIds.stream().anyMatch(id -> id <= 0)) {
            v.add("tenantIds", "Los ids de empresa deben ser enteros positivos.");
        } else if (new LinkedHashSet<>(tenantIds).size() > PortalInvitationService.MAX_TENANTS) {
            v.add("tenantIds", "Una persona no puede tener más de " + PortalInvitationService.MAX_TENANTS + " empresas.");
        }
        Boolean resetMfa = b.has("resetMfa") ? b.bool("resetMfa") : Boolean.FALSE;
        if (resetMfa == null) {
            v.add("resetMfa", "resetMfa debe ser true o false.");
        }
        v.throwIfAny();

        PortalInvitationService.Invitation invitation = invitations.invite(email, name, tenantIds, resetMfa, AdminPrincipal.require(request), request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", invitation.userId());
        // El token en claro solo existe en esta respuesta (en la base queda su SHA-256): sin caché en ningún lado
        data.put("token", invitation.token());
        data.put("expiresAt", AdminFormat.instant(invitation.expiresAt()));
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Pragma", "no-cache")
                .body(AdminResponse.body(true, List.of(invitation.reinvited() ? "Invitación reenviada." : "Invitación creada."), data, 0, List.of()));
    }

    @Operation(summary = "Personas del portal (paginado; tenantId, textFilter sobre nombre y correo)")
    @AdminPermission(AdminPermissions.PORTAL_MANAGE)
    @GetMapping("/users")
    public ResponseEntity<Object> list(HttpServletRequest request) {
        AdminPaging paging = AdminPaging.of(request.getParameter("records"), request.getParameter("numPage"));
        String text = clean(request.getParameter("textFilter"));
        Long tenantId = tenantId(request.getParameter("tenantId"));
        List<PortalUser> rows = users.list(text, tenantId, paging);
        Map<Long, List<Long>> tenants = users.tenantIdsByUsers(rows.stream().map(PortalUser::id).toList());
        return AdminResponse.page(rows.stream().map(u -> row(u, tenants.getOrDefault(u.id(), List.of()))).toList(), users.count(text, tenantId));
    }

    @Operation(summary = "Habilita o deshabilita a la persona (idempotente; al deshabilitar cierra sus sesiones)")
    @AdminPermission(AdminPermissions.PORTAL_MANAGE)
    @PutMapping("/users/{id}/estado")
    public ResponseEntity<Object> estado(@PathVariable long id, HttpServletRequest request) {
        Boolean active = AdminBody.of(request).bool("active");
        if (active == null) {
            new AdminValidation().add("active", "Indica si la persona queda habilitada (true) o deshabilitada (false).").throwIfAny();
        }
        PortalUser user = invitations.setActive(id, active, AdminPrincipal.require(request), request);
        return AdminResponse.ok(row(user, users.tenantIds(user.id())), active ? "Persona habilitada." : "Persona deshabilitada.");
    }

    /** Fila de la consola: nunca el hash, la clave del MFA ni los códigos de recuperación. */
    static Map<String, Object> row(PortalUser u, List<Long> tenantIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("email", u.email());
        m.put("name", u.name());
        m.put("active", u.active());
        m.put("activated", u.activated());
        m.put("mfaEnabled", u.mfaEnabled());
        m.put("lastLoginAt", AdminFormat.instant(u.lastLoginAt()));
        m.put("tenantIds", tenantIds);
        return m;
    }

    private static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    private static Long tenantId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!raw.trim().matches("\\d{1,18}")) {
            new AdminValidation().add("tenantId", "tenantId debe ser un número entero.").throwIfAny();
        }
        return Long.parseLong(raw.trim());
    }
}
