package pe.facturacion.api.modules.admin.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminBody;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.security.AdminMenu;
import pe.facturacion.api.modules.admin.security.AdminPrincipal;
import pe.facturacion.api.modules.admin.security.AdminPublic;

/**
 * Autenticación del panel ({@code /api/admin/auth}, §5.1 del diseño). Las respuestas imitan el contrato de identidad
 * del ERP / consola para reutilizar sus pantallas de login y MFA.
 */
@RestController
@RequestMapping("/api/admin/auth")
@Tag(name = "Panel de administración · autenticación")
public class AdminAuthController {

    private final AdminAuthService auth;

    public AdminAuthController(AdminAuthService auth) {
        this.auth = auth;
    }

    @Operation(summary = "Paso 1: correo y contraseña → ticket MFA (verificar o configurar)")
    @AdminPublic
    @PostMapping("/login")
    public ResponseEntity<Object> login(HttpServletRequest request) {
        AdminBody b = AdminBody.of(request);
        return AdminResponse.ok(auth.login(b.raw("userName"), b.raw("password"), request));
    }

    @Operation(summary = "Paso 2: código TOTP o de recuperación → token, refreshToken")
    @AdminPublic
    @PostMapping("/login/mfa")
    public ResponseEntity<Object> loginMfa(HttpServletRequest request) {
        AdminBody b = AdminBody.of(request);
        return AdminResponse.ok(auth.completeMfa(b.raw("mfaTicket"), b.text("code"), b.text("recoveryCode"), request));
    }

    @Operation(summary = "Primer ingreso: clave del autenticador (sharedKey y otpauth://)")
    @AdminPublic
    @PostMapping("/login/mfa/setup")
    public ResponseEntity<Object> setup(HttpServletRequest request) {
        return AdminResponse.ok(auth.setupMfa(AdminBody.of(request).raw("mfaTicket")));
    }

    @Operation(summary = "Primer ingreso: confirma el código → tokens y 10 códigos de recuperación")
    @AdminPublic
    @PostMapping("/login/mfa/setup/confirm")
    public ResponseEntity<Object> confirm(HttpServletRequest request) {
        AdminBody b = AdminBody.of(request);
        return AdminResponse.ok(auth.confirmSetup(b.raw("mfaTicket"), b.text("code"), request));
    }

    @Operation(summary = "Renueva el token (el refresh token rota)")
    @AdminPublic
    @PostMapping("/refreshToken")
    public ResponseEntity<Object> refresh(HttpServletRequest request) {
        AdminBody b = AdminBody.of(request);
        return AdminResponse.ok(auth.refresh(b.raw("token"), b.raw("refreshToken")));
    }

    @Operation(summary = "Cierra la sesión en todos los dispositivos")
    @PostMapping("/logout")
    public ResponseEntity<Object> logout(HttpServletRequest request) {
        auth.logout(AdminPrincipal.require(request), request);
        return AdminResponse.ok(null, "Sesión cerrada.");
    }

    @Operation(summary = "Datos del administrador (sin la contraseña ni el MFA)")
    @GetMapping("/me")
    public ResponseEntity<Object> me(HttpServletRequest request) {
        return AdminResponse.ok(auth.me(AdminPrincipal.require(request)));
    }

    @Operation(summary = "Edita el nombre y el correo propios (confirma con la contraseña actual; el correo es único)")
    @PutMapping("/me")
    public ResponseEntity<Object> updateMe(HttpServletRequest request) {
        AdminBody b = AdminBody.of(request);
        String email = b.text("email") == null ? null : b.text("email").toLowerCase(java.util.Locale.ROOT);
        return AdminResponse.ok(auth.updateProfile(AdminPrincipal.require(request), b.text("name"), email, b.raw("password"), request),
                "Perfil actualizado.");
    }

    @Operation(summary = "Permisos del rol")
    @GetMapping("/permissions")
    public ResponseEntity<Object> permissions(HttpServletRequest request) {
        return AdminResponse.ok(AdminPrincipal.require(request).role().permissions());
    }

    @Operation(summary = "Menú del panel filtrado por permisos (forma MenuNavApiItem)")
    @GetMapping("/menu")
    public ResponseEntity<Object> menu(HttpServletRequest request) {
        return AdminResponse.ok(AdminMenu.forRole(AdminPrincipal.require(request).role()));
    }

    @Operation(summary = "Cambia la propia contraseña (cierra todas las sesiones)")
    @PutMapping("/password")
    public ResponseEntity<Object> password(HttpServletRequest request) {
        AdminBody b = AdminBody.of(request);
        auth.changePassword(AdminPrincipal.require(request), b.raw("password"), b.raw("newPassword"), b.raw("confirmNewPassword"), request);
        return AdminResponse.ok(null, "Contraseña actualizada. Inicia sesión de nuevo.");
    }

    @Operation(summary = "Estado del MFA propio")
    @GetMapping("/mfa/status")
    public ResponseEntity<Object> mfaStatus(HttpServletRequest request) {
        return AdminResponse.ok(auth.mfaStatus(AdminPrincipal.require(request)));
    }

    @Operation(summary = "Regenera los 10 códigos de recuperación (con un código TOTP)")
    @PostMapping("/mfa/recovery-codes")
    public ResponseEntity<Object> recoveryCodes(HttpServletRequest request) {
        AdminBody b = AdminBody.of(request);
        return AdminResponse.ok(auth.regenerateRecoveryCodes(AdminPrincipal.require(request), b.text("code"), request));
    }
}
