package pe.facturacion.api.modules.portal.user;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminFormat;
import pe.facturacion.api.modules.admin.api.AdminValidation;
import pe.facturacion.api.modules.admin.security.PasswordPolicy;
import pe.facturacion.api.modules.admin.security.RecoveryCodes;
import pe.facturacion.api.modules.admin.security.Totp;
import pe.facturacion.api.modules.audit.AuditLogService;
import pe.facturacion.api.modules.portal.security.PortalJwt;
import pe.facturacion.api.modules.portal.security.PortalMessages;
import pe.facturacion.api.modules.portal.security.PortalPrincipal;
import pe.facturacion.api.modules.portal.security.PortalSessionCache;
import pe.facturacion.api.modules.portal.security.PortalTokens;
import pe.facturacion.api.shared.crypto.SecretCipher;
import pe.facturacion.api.shared.time.Clock;

/**
 * Activación de la cuenta, login en dos pasos (contraseña + TOTP obligatorio), refresh rotativo, cierre de sesión y la
 * cuenta propia del portal de clientes. Es la copia del mecanismo del panel ({@code AdminAuthService}) sobre
 * {@code portal_users}: mismas reglas, otro mundo (tablas, JWT, caché de sesión y claves de Redis propios).
 *
 * <ul>
 *   <li>5 contraseñas o códigos fallidos → bloqueo de 15 min. El mensaje de una contraseña incorrecta no revela si el
 *       correo existe; el de cuenta bloqueada solo se muestra a quien acierta la contraseña.</li>
 *   <li>La clave TOTP se guarda cifrada con {@link SecretCipher}; los códigos de recuperación como SHA-256.</li>
 *   <li>Un código TOTP sirve una sola vez (marca del paso en Redis 2 min).</li>
 *   <li>Cerrar sesión, cambiar o restablecer la contraseña, restablecer el MFA, deshabilitar y reinvitar suben
 *       {@code session_version} y revocan todos los refresh tokens.</li>
 *   <li>La activación nunca devuelve tokens: la persona entra después por el login (con MFA).</li>
 * </ul>
 */
@Service
public class PortalAuthService {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    public static final Duration REFRESH_TTL = Duration.ofDays(7);

    private static final Logger log = LoggerFactory.getLogger(PortalAuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PortalUserStore users;
    private final PortalTokens tokens;
    private final PortalSessionCache sessions;
    private final PortalTenantQueries tenantQueries;
    private final SecretCipher cipher;
    private final AuditLogService audit;
    private final StringRedisTemplate redis;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    /** Hash de relleno para que un correo inexistente tarde lo mismo que uno real. */
    private final String dummyHash;

    public PortalAuthService(PortalUserStore users, PortalTokens tokens, PortalSessionCache sessions, PortalTenantQueries tenantQueries,
            SecretCipher cipher, AuditLogService audit, StringRedisTemplate redis) {
        this.users = users;
        this.tokens = tokens;
        this.sessions = sessions;
        this.tenantQueries = tenantQueries;
        this.cipher = cipher;
        this.audit = audit;
        this.redis = redis;
        byte[] r = new byte[16];
        RANDOM.nextBytes(r);
        this.dummyHash = encoder.encode(HexFormat.of().formatHex(r));
    }

    // ------------------------------------------------------------------ contraseñas

    public String hashPassword(String password) {
        return encoder.encode(password);
    }

    public boolean passwordMatches(String raw, String hash) {
        if (raw == null || hash == null || hash.isBlank()) {
            return false;
        }
        try {
            return encoder.matches(raw, hash);
        } catch (IllegalArgumentException e) {
            // Más de 72 bytes o hash con otro formato
            return false;
        }
    }

    // ------------------------------------------------------------------ activación

    /**
     * {@code POST auth/activate}: fija la contraseña con el token de una invitación vigente. Valida primero la
     * contraseña (sus errores no dicen nada del token) y después gasta la invitación (una sola vez, atómico). Un token
     * inválido, usado, vencido, reemplazado por otra invitación o de una persona deshabilitada responde siempre el
     * mismo mensaje. No devuelve tokens.
     */
    public void activate(String token, String password, String confirmPassword, HttpServletRequest request) {
        AdminValidation v = new AdminValidation();
        v.required("password", password, "Ingresa la contraseña.");
        if (password != null && !password.isEmpty()) {
            PasswordPolicy.validate(password).forEach(e -> v.add("password", e));
        }
        if (confirmPassword == null || !confirmPassword.equals(password)) {
            v.add("confirmPassword", "La confirmación no coincide con la contraseña.");
        }
        v.throwIfAny();
        if (token == null || token.isBlank() || token.length() > 200) {
            audit.registrarPortal(request, "portal", null, "portal.activacion.fallida", null, AuditLogService.SUBJECT_PORTAL_USER, null,
                    "Activación rechazada: enlace no válido", null);
            throw AdminException.fail(PortalMessages.INVITATION_INVALID);
        }
        LocalDateTime now = Clock.nowUtc();
        Optional<Long> owner = users.consumeInvitation(sha256(token.trim()), now);
        Optional<PortalUser> user = owner.flatMap(users::findById).filter(PortalUser::active);
        if (user.isEmpty()) {
            audit.registrarPortal(request, "portal", null, "portal.activacion.fallida", null, AuditLogService.SUBJECT_PORTAL_USER,
                    owner.orElse(null), "Activación rechazada: enlace no válido, usado, vencido o de una cuenta deshabilitada", null);
            throw AdminException.fail(PortalMessages.INVITATION_INVALID);
        }
        PortalUser u = user.get();
        users.updatePassword(u.id(), hashPassword(password), now);
        sessions.invalidate(u.id());
        audit.registrarPortal(request, "portal", u.email(), "portal.activacion", null, AuditLogService.SUBJECT_PORTAL_USER, u.id(),
                "Cuenta del portal activada (contraseña fijada con la invitación)", null);
    }

    // ------------------------------------------------------------------ paso 1: contraseña

    /** {@code POST auth/login}: {@code {mfaRequired, mfaTicket}} o {@code {mfaSetupRequired, mfaTicket}}. */
    public Map<String, Object> login(String userName, String password, HttpServletRequest request) {
        String email = userName == null ? null : userName.trim().toLowerCase();
        if (email == null || email.isEmpty() || password == null || password.isEmpty()) {
            throw AdminException.fail("Ingresa tu correo y tu contraseña.");
        }
        if (email.length() > 255 || password.length() > 1024) {
            throw AdminException.fail(PortalMessages.BAD_CREDENTIALS);
        }
        LocalDateTime now = Clock.nowUtc();
        Optional<PortalUser> found = users.findByEmail(email);
        if (found.isEmpty()) {
            passwordMatches(password, dummyHash);
            audit.registrarPortal(request, "portal", email, "portal.login.fallido", null, null, null,
                    "Inicio de sesión fallido en el portal (correo no registrado)", null);
            throw AdminException.fail(PortalMessages.BAD_CREDENTIALS);
        }
        PortalUser user = found.get();
        boolean ok = passwordMatches(password, user.passwordHash());
        if (user.lockedAt(now)) {
            // Solo quien acierta la contraseña se entera del bloqueo; el contador no sigue subiendo
            record(request, user, "portal.login.fallido", "Inicio de sesión rechazado: cuenta bloqueada");
            throw AdminException.fail(ok ? PortalMessages.LOCKED : PortalMessages.BAD_CREDENTIALS);
        }
        if (!ok) {
            registerFailure(user, request, "contraseña incorrecta");
            throw AdminException.fail(PortalMessages.BAD_CREDENTIALS);
        }
        if (!user.canLogin()) {
            record(request, user, "portal.login.fallido", "Inicio de sesión rechazado: cuenta deshabilitada");
            throw AdminException.fail(PortalMessages.INACTIVE);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (user.mfaEnabled()) {
            data.put("mfaRequired", true);
            data.put("mfaTicket", tokens.jwt().issueMfaTicket(user.id(), PortalJwt.STAGE_VERIFY, user.sessionVersion(), Clock.now()));
        } else {
            data.put("mfaSetupRequired", true);
            data.put("mfaTicket", tokens.jwt().issueMfaTicket(user.id(), PortalJwt.STAGE_SETUP, user.sessionVersion(), Clock.now()));
        }
        return data;
    }

    // ------------------------------------------------------------------ paso 2: código

    /** {@code POST auth/login/mfa}: código TOTP o de recuperación → tokens. */
    public Map<String, Object> completeMfa(String ticket, String code, String recoveryCode, HttpServletRequest request) {
        PortalUser user = userFromTicket(ticket, PortalJwt.STAGE_VERIFY);
        if (!user.mfaEnabled()) {
            throw AdminException.fail(PortalMessages.TICKET_INVALID);
        }
        boolean hasCode = code != null && !code.isBlank();
        boolean hasRecovery = recoveryCode != null && !recoveryCode.isBlank();
        if (!hasCode && !hasRecovery) {
            throw AdminException.fail(PortalMessages.CODE_REQUIRED);
        }
        boolean ok;
        String via;
        if (hasCode) {
            ok = verifyTotp(user, code);
            via = "código TOTP";
        } else {
            ok = consumeRecoveryCode(user, recoveryCode);
            via = "código de recuperación";
        }
        if (!ok) {
            failCode(user, request, via);
        }
        users.loginSucceeded(user.id(), Clock.nowUtc());
        PortalUser fresh = users.findById(user.id()).orElse(user);
        record(request, user, "portal.login", "Inicio de sesión en el portal (" + via + ")");
        Map<String, Object> data = issueTokens(fresh);
        data.put("recoveryCodesLeft", RecoveryCodes.fromJson(fresh.twoFactorRecoveryCodes()).size());
        return data;
    }

    /** {@code POST auth/login/mfa/setup}: clave nueva (pendiente hasta confirmarla). */
    public Map<String, Object> setupMfa(String ticket) {
        PortalUser user = userFromTicket(ticket, PortalJwt.STAGE_SETUP);
        if (user.mfaEnabled()) {
            throw AdminException.fail("El MFA ya está configurado. Inicia sesión de nuevo.");
        }
        String secret = Totp.newSecret();
        users.setPendingSecret(user.id(), cipher.encrypt(secret), Clock.nowUtc());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sharedKey", Totp.formatSharedKey(secret));
        data.put("authenticatorUri", Totp.authenticatorUri(user.email(), secret));
        return data;
    }

    /** {@code POST auth/login/mfa/setup/confirm}: primer código → MFA activo, 10 códigos de recuperación y tokens. */
    public Map<String, Object> confirmSetup(String ticket, String code, HttpServletRequest request) {
        PortalUser user = userFromTicket(ticket, PortalJwt.STAGE_SETUP);
        if (user.mfaEnabled()) {
            throw AdminException.fail("El MFA ya está configurado. Inicia sesión de nuevo.");
        }
        if (user.twoFactorSecret() == null || user.twoFactorSecret().isBlank()) {
            throw AdminException.fail("Primero genera la clave del autenticador.");
        }
        if (code == null || code.isBlank()) {
            throw AdminException.fail("Ingresa el código de tu aplicación autenticadora.");
        }
        if (!verifyTotp(user, code)) {
            failCode(user, request, "código TOTP al configurar el MFA");
        }
        List<String> codes = RecoveryCodes.generate();
        LocalDateTime now = Clock.nowUtc();
        if (!users.confirmMfa(user.id(), RecoveryCodes.toJson(RecoveryCodes.hashAll(codes)), now)) {
            throw AdminException.fail(PortalMessages.TICKET_INVALID);
        }
        users.loginSucceeded(user.id(), now);
        record(request, user, "portal.mfa.configurado", "MFA configurado en el portal");
        record(request, user, "portal.login", "Inicio de sesión en el portal (configuración del MFA)");
        Map<String, Object> data = issueTokens(users.findById(user.id()).orElse(user));
        data.put("recoveryCodes", codes);
        return data;
    }

    // ------------------------------------------------------------------ refresh y logout

    /** {@code POST auth/refreshToken}: usa el refresh token (lo revoca) y entrega otro par. */
    public Map<String, Object> refresh(String accessToken, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank() || refreshToken.length() > 200) {
            throw AdminException.fail(PortalMessages.SESSION_EXPIRED);
        }
        LocalDateTime now = Clock.nowUtc();
        Optional<Long> owner = users.consumeRefreshToken(sha256(refreshToken.trim()), now);
        if (owner.isEmpty()) {
            throw AdminException.fail(PortalMessages.SESSION_EXPIRED);
        }
        if (accessToken != null && !accessToken.isBlank()) {
            Optional<Long> sub = tokens.jwt().subjectOfAccessIgnoringExpiry(accessToken);
            if (sub.isEmpty() || !sub.get().equals(owner.get())) {
                throw AdminException.fail(PortalMessages.SESSION_EXPIRED);
            }
        }
        PortalUser user = users.findById(owner.get()).filter(PortalUser::canLogin)
                .orElseThrow(() -> AdminException.fail(PortalMessages.SESSION_EXPIRED));
        Map<String, Object> data = issueTokens(user);
        data.put("recoveryCodesLeft", RecoveryCodes.fromJson(user.twoFactorRecoveryCodes()).size());
        return data;
    }

    /** {@code POST auth/logout}: cierra la sesión en todos los dispositivos. */
    public void logout(PortalPrincipal principal, HttpServletRequest request) {
        closeSessions(principal.userId());
        audit.registrarPortal(request, "portal", principal.email(), "portal.logout", null, AuditLogService.SUBJECT_PORTAL_USER,
                principal.userId(), "Cierre de sesión en el portal", null);
    }

    /** Sube session_version, revoca los refresh tokens y olvida la caché de esta instancia. */
    public void closeSessions(long userId) {
        users.bumpSession(userId, Clock.nowUtc());
        sessions.invalidate(userId);
    }

    // ------------------------------------------------------------------ cuenta propia

    /** {@code GET auth/me}: la persona y sus empresas (sin contraseña, MFA ni credenciales). */
    public Map<String, Object> me(PortalPrincipal principal) {
        PortalUser u = users.findById(principal.userId()).orElseThrow(() -> AdminException.status(401, PortalMessages.SESSION_INVALID));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", u.id());
        data.put("name", u.name());
        data.put("email", u.email());
        data.put("tenants", tenantQueries.tenantsOf(u.id()));
        return data;
    }

    /** {@code PUT auth/password}: cambia la propia contraseña y cierra todas las sesiones. */
    public void changePassword(PortalPrincipal principal, String current, String newPassword, String confirm, HttpServletRequest request) {
        PortalUser u = users.findById(principal.userId()).orElseThrow(() -> AdminException.status(401, PortalMessages.SESSION_INVALID));
        AdminValidation v = new AdminValidation();
        v.required("password", current, "Ingresa tu contraseña actual.");
        v.required("newPassword", newPassword, "Ingresa la nueva contraseña.");
        v.throwIfAny();
        if (!passwordMatches(current, u.passwordHash())) {
            LocalDateTime lockedUntil = users.registerFailure(u.id(), MAX_FAILURES, Clock.nowUtc().plus(LOCK_DURATION), Clock.nowUtc());
            record(request, u, "portal.password.fallido", "Cambio de contraseña rechazado: la contraseña actual no coincide");
            if (lockedUntil != null && lockedUntil.isAfter(Clock.nowUtc())) {
                lockAudit(u, request);
                closeSessions(u.id());
                throw AdminException.fail(PortalMessages.LOCKED);
            }
            throw AdminException.fail("La contraseña actual no es correcta.");
        }
        PasswordPolicy.validate(newPassword).forEach(e -> v.add("newPassword", e));
        if (confirm == null || !confirm.equals(newPassword)) {
            v.add("confirmNewPassword", "La confirmación no coincide con la nueva contraseña.");
        }
        if (!v.hasErrors() && newPassword.equals(current)) {
            v.add("newPassword", "La nueva contraseña debe ser distinta de la actual.");
        }
        v.throwIfAny();
        users.updatePassword(u.id(), hashPassword(newPassword), Clock.nowUtc());
        closeSessions(u.id());
        record(request, u, "portal.password.cambiada", "Cambio de la propia contraseña (se cerraron todas las sesiones)");
    }

    public Map<String, Object> mfaStatus(PortalPrincipal principal) {
        PortalUser u = users.findById(principal.userId()).orElseThrow(() -> AdminException.status(401, PortalMessages.SESSION_INVALID));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", u.mfaEnabled());
        data.put("recoveryCodesLeft", RecoveryCodes.fromJson(u.twoFactorRecoveryCodes()).size());
        return data;
    }

    /** {@code POST auth/mfa/recovery-codes}: con un código TOTP vigente, genera 10 códigos nuevos (los anteriores dejan de valer). */
    public Map<String, Object> regenerateRecoveryCodes(PortalPrincipal principal, String code, HttpServletRequest request) {
        PortalUser u = users.findById(principal.userId()).orElseThrow(() -> AdminException.status(401, PortalMessages.SESSION_INVALID));
        if (!u.mfaEnabled()) {
            throw AdminException.fail("El MFA no está configurado.");
        }
        if (code == null || code.isBlank()) {
            throw AdminException.fail("Ingresa el código de tu aplicación autenticadora.");
        }
        if (!verifyTotp(u, code)) {
            LocalDateTime lockedUntil = users.registerFailure(u.id(), MAX_FAILURES, Clock.nowUtc().plus(LOCK_DURATION), Clock.nowUtc());
            record(request, u, "portal.mfa.codigo_fallido", "Código TOTP incorrecto al regenerar los códigos de recuperación");
            if (lockedUntil != null && lockedUntil.isAfter(Clock.nowUtc())) {
                lockAudit(u, request);
                closeSessions(u.id());
                throw AdminException.fail(PortalMessages.LOCKED);
            }
            throw AdminException.fail(PortalMessages.BAD_CODE);
        }
        List<String> codes = RecoveryCodes.generate();
        users.setRecoveryCodes(u.id(), RecoveryCodes.toJson(RecoveryCodes.hashAll(codes)), Clock.nowUtc());
        record(request, u, "portal.mfa.codigos_regenerados", "Códigos de recuperación regenerados");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recoveryCodes", codes);
        return data;
    }

    // ------------------------------------------------------------------ paso de seguridad (credenciales de la API)

    /**
     * Confirma de nuevo a la persona antes de una acción delicada: la contraseña <b>y</b> un código TOTP vigente, en el
     * mismo pedido. Un fallo (de cualquiera de los dos) cuenta para el bloqueo como un login fallido y no dice cuál de
     * los dos falló. El código de recuperación no sirve aquí.
     */
    public void stepUp(long userId, String password, String code, HttpServletRequest request) {
        PortalUser u = users.findById(userId).orElseThrow(() -> AdminException.status(401, PortalMessages.SESSION_INVALID));
        if (password == null || password.isEmpty() || code == null || code.isBlank()) {
            throw AdminException.fail("Ingresa tu contraseña y el código de tu aplicación autenticadora.");
        }
        if (password.length() > 1024) {
            throw AdminException.fail(PortalMessages.STEP_UP_FAILED);
        }
        if (u.lockedAt(Clock.nowUtc())) {
            throw AdminException.fail(PortalMessages.LOCKED);
        }
        // Se evalúan los dos siempre: la respuesta y el tiempo no dicen cuál de los dos falló
        boolean passwordOk = passwordMatches(password, u.passwordHash());
        boolean codeOk = u.mfaEnabled() && verifyTotp(u, code);
        if (!passwordOk || !codeOk) {
            boolean locked = registerFailure(u, request, "paso de seguridad de las credenciales fallido");
            if (locked) {
                closeSessions(u.id());
            }
            throw AdminException.fail(locked ? PortalMessages.LOCKED : PortalMessages.STEP_UP_FAILED);
        }
    }

    // ------------------------------------------------------------------ apoyo

    /** Usuario de un ticket MFA vigente de la etapa pedida; el ticket deja de valer si cambió session_version. */
    private PortalUser userFromTicket(String ticket, String stage) {
        Optional<PortalJwt.MfaClaims> claims = tokens.jwt().verifyMfaTicket(ticket, Clock.now());
        if (claims.isEmpty() || !stage.equals(claims.get().stage())) {
            throw AdminException.fail(PortalMessages.TICKET_INVALID);
        }
        PortalUser user = users.findById(claims.get().userId())
                .filter(u -> u.sessionVersion() == claims.get().sessionVersion())
                .orElseThrow(() -> AdminException.fail(PortalMessages.TICKET_INVALID));
        if (!user.canLogin()) {
            throw AdminException.fail(PortalMessages.INACTIVE);
        }
        if (user.lockedAt(Clock.nowUtc())) {
            throw AdminException.fail(PortalMessages.LOCKED);
        }
        return user;
    }

    /** TOTP vigente y no usado antes (el paso usado queda marcado en Redis). */
    private boolean verifyTotp(PortalUser user, String code) {
        String secret;
        try {
            secret = cipher.decrypt(user.twoFactorSecret());
        } catch (SecretCipher.DecryptException e) {
            log.warn("portal.mfa.clave_ilegible usuario={}", user.id());
            throw AdminException.fail("No se pudo leer la clave del MFA. Pide que restablezcan tu MFA con una invitación nueva.");
        }
        long step;
        try {
            step = Totp.matchStep(secret, code, Clock.now());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (step < 0) {
            return false;
        }
        try {
            Boolean first = redis.opsForValue().setIfAbsent("portal:totp-used:" + user.id() + ":" + step, "1", Duration.ofMinutes(2));
            return !Boolean.FALSE.equals(first);
        } catch (RuntimeException e) {
            // Sin Redis no se puede marcar el código: se acepta (la ventana es de 90 s)
            log.warn("portal.mfa.redis_no_disponible error={}", e.getClass().getSimpleName());
            return true;
        }
    }

    private boolean consumeRecoveryCode(PortalUser user, String code) {
        List<String> hashes = RecoveryCodes.fromJson(user.twoFactorRecoveryCodes());
        Optional<List<String>> remaining = RecoveryCodes.consume(hashes, code);
        if (remaining.isEmpty()) {
            return false;
        }
        return users.replaceRecoveryCodes(user.id(), user.twoFactorRecoveryCodes(), RecoveryCodes.toJson(remaining.get()), Clock.nowUtc());
    }

    /** Código incorrecto: cuenta para el bloqueo y siempre lanza. */
    private void failCode(PortalUser user, HttpServletRequest request, String via) {
        boolean locked = registerFailure(user, request, via + " incorrecto");
        throw AdminException.fail(locked ? PortalMessages.LOCKED : PortalMessages.BAD_CODE);
    }

    /** Suma un fallo; true si con este la cuenta quedó bloqueada. */
    private boolean registerFailure(PortalUser user, HttpServletRequest request, String motivo) {
        LocalDateTime now = Clock.nowUtc();
        LocalDateTime lockedUntil = users.registerFailure(user.id(), MAX_FAILURES, now.plus(LOCK_DURATION), now);
        record(request, user, "portal.login.fallido", "Fallo de autenticación en el portal: " + motivo);
        boolean locked = lockedUntil != null && lockedUntil.isAfter(now);
        if (locked) {
            lockAudit(user, request);
        }
        return locked;
    }

    private void lockAudit(PortalUser user, HttpServletRequest request) {
        record(request, user, "portal.cuenta.bloqueada", "Cuenta bloqueada 15 minutos por " + MAX_FAILURES + " intentos fallidos");
    }

    private void record(HttpServletRequest request, PortalUser user, String accion, String descripcion) {
        audit.registrarPortal(request, "portal", user.email(), accion, null, AuditLogService.SUBJECT_PORTAL_USER, user.id(), descripcion, null);
    }

    /** Token de acceso (30 min) + refresh opaco de 32 bytes (se guarda solo su SHA-256, 7 días). */
    private Map<String, Object> issueTokens(PortalUser user) {
        Instant now = Clock.now();
        String access = tokens.jwt().issueAccess(user.id(), user.sessionVersion(), now);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC).withNano(0);
        LocalDateTime expires = nowUtc.plus(REFRESH_TTL);
        users.insertRefreshToken(user.id(), sha256(refresh), expires, nowUtc);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", access);
        data.put("refreshToken", refresh);
        data.put("refreshTokenExpiryTime", AdminFormat.instant(expires));
        return data;
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
