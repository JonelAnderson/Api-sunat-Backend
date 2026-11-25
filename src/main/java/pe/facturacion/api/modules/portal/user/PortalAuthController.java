package pe.facturacion.api.modules.portal.user;

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
import pe.facturacion.api.modules.portal.security.PortalPrincipal;
import pe.facturacion.api.modules.portal.security.PortalPublic;

/**
 * Autenticación del portal de clientes ({@code /api/portal/auth}, §5 del diseño): la misma forma que
 * {@code /api/admin/auth} (cuerpo con {@code userName} = correo) más la activación de la cuenta con el token de la
 * invitación. Todas las acciones anónimas pasan por el límite de 10 por minuto y por IP.
 */
@RestController
@RequestMapping("/api/portal/auth")
@Tag(name = "Portal de clientes · autenticación")
public class PortalAuthController {

    private final PortalAuthService auth;

    public PortalAuthController(PortalAuthService auth) {
        this.auth = auth;
    }

    @Operation(summary = "Activa la cuenta: fija la contraseña con el token de la invitación (no devuelve tokens)")
    @PortalPublic
    @PostMapping("/activate")
    public ResponseEntity<Object> activate(HttpServletRequest request) {
        AdminBody b = AdminBody.of(request);
        auth.activate(b.raw("token"), b.raw("password"), b.raw("confirmPassword"), request);
        return AdminResponse.ok(null, "Cuenta activada. Ya puedes iniciar sesión.");
    }

    @Operation(summary = "Paso 1: correo y contraseña → ticket MFA (verificar o configurar)")
    @PortalPublic
    @PostMapping("/login")
    public ResponseEntity<Object> login(HttpServletRequest request) {
        AdminBody b = AdminBody.of(request);
        return AdminResponse.ok(auth.login(b.raw("userName"), b.raw("password"), request));
    }

    @Operation(summary = "Paso 2: código TOTP o de recuperación → token, refreshToken")
    @PortalPublic
    @PostMapping("/login/mfa")
    public ResponseEntity<Object> loginMfa(HttpServletRequest request) {
        AdminBody b = AdminBody.of(request);
        return AdminResponse.ok(auth.completeMfa(b.raw("mfaTicket"), b.text("code"), b.text("recoveryCode"), request));
    }

    @Operation(summary = "Primer ingreso: clave del autenticador (sharedKey y otpauth://)")
    @PortalPublic
    @PostMapping("/login/mfa/setup")
    public ResponseEntity<Object> setup(HttpServletRequest request) {
        return AdminResponse.ok(auth.setupMfa(AdminBody.of(request).raw("mfaTicket")));
    }

    @Operation(summary = "Primer ingreso: confirma el código → tokens y 10 códigos de recuperación")
    @PortalPublic
    @PostMapping("/login/mfa/setup/confirm")
    public ResponseEntity<Object> confirm(HttpServletRequest request) {
        AdminBody b = AdminBody.of(request);
        return AdminResponse.ok(auth.confirmSetup(b.raw("mfaTicket"), b.text("code"), request));
    }

    @Operation(summary = "Renueva el token (el refresh token rota)")
    @PortalPublic
    @PostMapping("/refreshToken")
    public ResponseEntity<Object> refresh(HttpServletRequest request) {
        AdminBody b = AdminBody.of(request);
        return AdminResponse.ok(auth.refresh(b.raw("token"), b.raw("refreshToken")));
    }

    @Operation(summary = "Cierra la sesión en todos los dispositivos")
    @PostMapping("/logout")
    public ResponseEntity<Object> logout(HttpServletRequest request) {
        auth.logout(PortalPrincipal.require(request), request);
        return AdminResponse.ok(null, "Sesión cerrada.");
    }

    @Operation(summary = "La persona y sus empresas (sin contraseña, MFA ni credenciales)")
    @GetMapping("/me")
    public ResponseEntity<Object> me(HttpServletRequest request) {
        return AdminResponse.ok(auth.me(PortalPrincipal.require(request)));
    }

    @Operation(summary = "Cambia la propia contraseña (cierra todas las sesiones)")
    @PutMapping("/password")
    public ResponseEntity<Object> password(HttpServletRequest request) {
        AdminBody b = AdminBody.of(request);
        auth.changePassword(PortalPrincipal.require(request), b.raw("password"), b.raw("newPassword"), b.raw("confirmNewPassword"), request);
        return AdminResponse.ok(null, "Contraseña actualizada. Inicia sesión de nuevo.");
    }

    @Operation(summary = "Estado del MFA propio")
    @GetMapping("/mfa/status")
    public ResponseEntity<Object> mfaStatus(HttpServletRequest request) {
        return AdminResponse.ok(auth.mfaStatus(PortalPrincipal.require(request)));
    }

    @Operation(summary = "Regenera los 10 códigos de recuperación (con un código TOTP)")
    @PostMapping("/mfa/recovery-codes")
    public ResponseEntity<Object> recoveryCodes(HttpServletRequest request) {
        AdminBody b = AdminBody.of(request);
        return AdminResponse.ok(auth.regenerateRecoveryCodes(PortalPrincipal.require(request), b.text("code"), request));
    }
}
