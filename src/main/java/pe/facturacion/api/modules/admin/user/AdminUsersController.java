package pe.facturacion.api.modules.admin.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminBody;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminFormat;
import pe.facturacion.api.modules.admin.api.AdminPaging;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.api.AdminValidation;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.admin.security.AdminPrincipal;
import pe.facturacion.api.modules.admin.security.AdminRole;
import pe.facturacion.api.modules.admin.security.AdminSessionCache;
import pe.facturacion.api.modules.admin.security.PasswordPolicy;
import pe.facturacion.api.modules.admin.security.RecoveryCodes;
import pe.facturacion.api.modules.audit.AuditLogService;
import pe.facturacion.api.shared.time.Clock;

/**
 * Administradores del panel ({@code /api/admin/users}, §5.2, solo superadmin). Reglas: nadie se quita a sí mismo el
 * rol superadmin ni se desactiva, y la plataforma nunca queda sin un superadmin activo. Desactivar, restablecer el MFA
 * y poner una contraseña temporal cierran las sesiones del usuario.
 */
@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "Panel de administración · administradores")
public class AdminUsersController {

    private final AdminUserStore users;
    private final AdminAuthService auth;
    private final AdminSessionCache sessions;
    private final AuditLogService audit;

    public AdminUsersController(AdminUserStore users, AdminAuthService auth, AdminSessionCache sessions, AuditLogService audit) {
        this.users = users;
        this.auth = auth;
        this.sessions = sessions;
        this.audit = audit;
    }

    @Operation(summary = "Administradores (paginado, textFilter nombre / correo, stateFilter 0 / 1)")
    @AdminPermission(AdminPermissions.USERS_MANAGE)
    @GetMapping
    public ResponseEntity<Object> list(@RequestParam(required = false) String records, @RequestParam(required = false) String numPage,
            @RequestParam(required = false) String textFilter, @RequestParam(required = false) String stateFilter) {
        AdminPaging paging = AdminPaging.of(records, numPage);
        String text = textFilter == null || textFilter.isBlank() ? null : textFilter.trim();
        Boolean active = state(stateFilter);
        List<Map<String, Object>> rows = users.list(text, active, paging).stream().map(AdminUsersController::row).toList();
        return AdminResponse.page(rows, users.count(text, active));
    }

    @Operation(summary = "Crea un administrador (configura el MFA en su primer login)")
    @AdminPermission(AdminPermissions.USERS_MANAGE)
    @PostMapping
    @Transactional
    public ResponseEntity<Object> create(HttpServletRequest request) {
        AdminPrincipal me = AdminPrincipal.require(request);
        AdminBody b = AdminBody.of(request);
        String name = b.text("name");
        String email = b.text("email") == null ? null : b.text("email").toLowerCase();
        String role = b.text("role");
        String password = b.raw("password");
        AdminValidation v = validateProfile(name, email, role, null);
        PasswordPolicy.validate(password).forEach(e -> v.add("password", e));
        v.throwIfAny();
        LocalDateTime now = Clock.nowUtc();
        long id = users.insert(name, email, auth.hashPassword(password), role, now);
        audit.registrarAdmin(request, me.userId(), me.email(), "admin.usuario.creado", null, AuditLogService.SUBJECT_USER, id,
                "Administrador creado: " + email + " (" + role + ")", Map.of("rol", role));
        return AdminResponse.ok(row(users.findById(id).orElseThrow()), "Administrador creado. Configurará el MFA en su primer ingreso.");
    }

    @Operation(summary = "Edita nombre, correo y rol")
    @AdminPermission(AdminPermissions.USERS_MANAGE)
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<Object> update(@PathVariable long id, HttpServletRequest request) {
        AdminPrincipal me = AdminPrincipal.require(request);
        users.lockSuperadmins();
        AdminUser target = find(id);
        AdminBody b = AdminBody.of(request);
        String name = b.text("name");
        String email = b.text("email") == null ? null : b.text("email").toLowerCase();
        String role = b.text("role");
        validateProfile(name, email, role, id).throwIfAny();
        boolean wasSuperadmin = "superadmin".equals(target.role());
        if (wasSuperadmin && !"superadmin".equals(role)) {
            if (target.id() == me.userId()) {
                throw AdminException.fail("No puedes quitarte a ti mismo el rol de superadministrador.");
            }
            if (target.active() && users.countActiveSuperadminsExcept(target.id()) == 0) {
                throw AdminException.fail("La plataforma debe tener al menos un superadministrador activo.");
            }
        }
        users.updateProfile(id, name, email, role, Clock.nowUtc());
        sessions.invalidate(id);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("rol_anterior", target.role());
        meta.put("rol", role);
        audit.registrarAdmin(request, me.userId(), me.email(), "admin.usuario.actualizado", null, AuditLogService.SUBJECT_USER, id,
                "Administrador actualizado: " + email, meta);
        return AdminResponse.ok(row(find(id)), "Administrador actualizado.");
    }

    @Operation(summary = "Activa o desactiva (desactivar cierra sus sesiones)")
    @AdminPermission(AdminPermissions.USERS_MANAGE)
    @PutMapping("/{id}/toggle-status")
    @Transactional
    public ResponseEntity<Object> toggle(@PathVariable long id, HttpServletRequest request) {
        AdminPrincipal me = AdminPrincipal.require(request);
        users.lockSuperadmins();
        AdminUser target = find(id);
        boolean activate = !target.active();
        if (!activate) {
            if (target.id() == me.userId()) {
                throw AdminException.fail("No puedes desactivarte a ti mismo.");
            }
            if ("superadmin".equals(target.role()) && users.countActiveSuperadminsExcept(target.id()) == 0) {
                throw AdminException.fail("No se puede desactivar al último superadministrador activo.");
            }
        }
        users.setActive(id, activate, Clock.nowUtc());
        if (activate) {
            sessions.invalidate(id);
        } else {
            auth.closeSessions(id);
        }
        audit.registrarAdmin(request, me.userId(), me.email(), activate ? "admin.usuario.activado" : "admin.usuario.desactivado", null,
                AuditLogService.SUBJECT_USER, id, (activate ? "Administrador activado: " : "Administrador desactivado: ") + target.email(), null);
        return AdminResponse.ok(row(find(id)), activate ? "Administrador activado." : "Administrador desactivado.");
    }

    @Operation(summary = "Restablece el MFA (lo configura de nuevo en su próximo ingreso)")
    @AdminPermission(AdminPermissions.USERS_MANAGE)
    @PostMapping("/{id}/mfa/reset")
    @Transactional
    public ResponseEntity<Object> resetMfa(@PathVariable long id, HttpServletRequest request) {
        AdminPrincipal me = AdminPrincipal.require(request);
        AdminUser target = find(id);
        users.resetMfa(id, Clock.nowUtc());
        auth.closeSessions(id);
        audit.registrarAdmin(request, me.userId(), me.email(), "admin.usuario.mfa_restablecido", null, AuditLogService.SUBJECT_USER, id,
                "MFA restablecido: " + target.email(), null);
        return AdminResponse.ok(row(find(id)), "MFA restablecido. Lo configurará en su próximo ingreso.");
    }

    @Operation(summary = "Quita el bloqueo por intentos fallidos")
    @AdminPermission(AdminPermissions.USERS_MANAGE)
    @PostMapping("/{id}/unlock")
    @Transactional
    public ResponseEntity<Object> unlock(@PathVariable long id, HttpServletRequest request) {
        AdminPrincipal me = AdminPrincipal.require(request);
        AdminUser target = find(id);
        users.unlock(id, Clock.nowUtc());
        audit.registrarAdmin(request, me.userId(), me.email(), "admin.usuario.desbloqueado", null, AuditLogService.SUBJECT_USER, id,
                "Administrador desbloqueado: " + target.email(), null);
        return AdminResponse.ok(row(find(id)), "Administrador desbloqueado.");
    }

    @Operation(summary = "Pone una contraseña temporal (cierra sus sesiones)")
    @AdminPermission(AdminPermissions.USERS_MANAGE)
    @PostMapping("/{id}/password")
    @Transactional
    public ResponseEntity<Object> password(@PathVariable long id, HttpServletRequest request) {
        AdminPrincipal me = AdminPrincipal.require(request);
        AdminUser target = find(id);
        String password = AdminBody.of(request).raw("password");
        AdminValidation v = new AdminValidation();
        PasswordPolicy.validate(password).forEach(e -> v.add("password", e));
        v.throwIfAny();
        LocalDateTime now = Clock.nowUtc();
        users.updatePassword(id, auth.hashPassword(password), now);
        users.unlock(id, now);
        auth.closeSessions(id);
        audit.registrarAdmin(request, me.userId(), me.email(), "admin.usuario.password_restablecida", null, AuditLogService.SUBJECT_USER,
                id, "Contraseña temporal asignada: " + target.email(), null);
        return AdminResponse.ok(null, "Contraseña temporal asignada. Se cerraron sus sesiones.");
    }

    // ------------------------------------------------------------------ apoyo

    private AdminUser find(long id) {
        return users.findAdminById(id).orElseThrow(() -> AdminException.fail("No existe el administrador."));
    }

    private AdminValidation validateProfile(String name, String email, String role, Long exceptId) {
        AdminValidation v = new AdminValidation();
        v.required("name", name, "El nombre es obligatorio.");
        v.maxLength("name", name, 255, "El nombre no debe superar los 255 caracteres.");
        if (email == null) {
            v.add("email", "El correo es obligatorio.");
        } else if (!AdminValidation.isEmail(email)) {
            v.add("email", "El correo no es válido.");
        } else if (users.emailTaken(email, exceptId)) {
            v.add("email", "Ya existe un usuario con ese correo.");
        }
        if (AdminRole.fromCode(role).isEmpty()) {
            v.add("role", "El rol debe ser superadmin, operador, soporte o lectura.");
        }
        return v;
    }

    private static Boolean state(String stateFilter) {
        if ("1".equals(stateFilter) || "true".equalsIgnoreCase(stateFilter)) {
            return Boolean.TRUE;
        }
        if ("0".equals(stateFilter) || "false".equalsIgnoreCase(stateFilter)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** Fila sin contraseña, clave TOTP ni códigos de recuperación. */
    static Map<String, Object> row(AdminUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("name", u.name());
        m.put("email", u.email());
        m.put("role", u.role());
        m.put("roleName", u.adminRole().map(AdminRole::label).orElse(null));
        m.put("isActive", u.active());
        m.put("mfaEnabled", u.mfaEnabled());
        LocalDateTime now = Clock.nowUtc();
        m.put("lockedUntil", u.lockedAt(now) ? AdminFormat.instant(u.lockedUntil()) : null);
        m.put("lastLoginAt", AdminFormat.instant(u.lastLoginAt()));
        m.put("recoveryCodesLeft", RecoveryCodes.fromJson(u.twoFactorRecoveryCodes()).size());
        return m;
    }
}
