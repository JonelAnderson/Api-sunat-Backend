package pe.facturacion.api.modules.admin.user;

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
import pe.facturacion.api.modules.admin.security.AdminJwt;
import pe.facturacion.api.modules.admin.security.AdminMessages;
import pe.facturacion.api.modules.admin.security.AdminPrincipal;
import pe.facturacion.api.modules.admin.security.AdminSessionCache;
import pe.facturacion.api.modules.admin.security.AdminTokens;
import pe.facturacion.api.modules.admin.security.PasswordPolicy;
import pe.facturacion.api.modules.admin.security.RecoveryCodes;
import pe.facturacion.api.modules.admin.security.Totp;
import pe.facturacion.api.modules.audit.AuditLogService;
import pe.facturacion.api.shared.crypto.SecretCipher;
import pe.facturacion.api.shared.time.Clock;

/**
 * Login del panel en dos pasos (contraseña + TOTP obligatorio), refresh rotativo, cierre de sesión y la cuenta propia.
 *
 * <ul>
 *   <li>5 contraseñas o códigos fallidos → bloqueo de 15 min. El mensaje de una contraseña incorrecta no revela si el
 *       correo existe; el de cuenta bloqueada solo se muestra a quien acierta la contraseña.</li>
 *   <li>La clave TOTP se guarda cifrada con {@link SecretCipher}; los códigos de recuperación como SHA-256.</li>
 *   <li>Un código TOTP sirve una sola vez (marca del paso en Redis 2 min).</li>
 *   <li>Cerrar sesión, cambiar o restablecer la contraseña, restablecer el MFA o desactivar suben
 *       {@code session_version} y revocan todos los refresh tokens.</li>
 * </ul>
 */
@Service
public class AdminAuthService {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    public static final Duration REFRESH_TTL = Duration.ofDays(7);

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminUserStore users;
    private final AdminTokens tokens;
    private final AdminSessionCache sessions;
    private final SecretCipher cipher;
    private final AuditLogService audit;
    private final StringRedisTemplate redis;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    /** Hash de relleno para que un correo inexistente tarde lo mismo que uno real. */
    private final String dummyHash;

    public AdminAuthService(AdminUserStore users, AdminTokens tokens, AdminSessionCache sessions, SecretCipher cipher,
            AuditLogService audit, StringRedisTemplate redis) {
        this.users = users;
        this.tokens = tokens;
        this.sessions = sessions;
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

    // ------------------------------------------------------------------ paso 1: contraseña

    /** {@code POST auth/login}: {@code {mfaRequired, mfaTicket}} o {@code {mfaSetupRequired, mfaTicket}}. */
    public Map<String, Object> login(String userName, String password, HttpServletRequest request) {
        String email = userName == null ? null : userName.trim().toLowerCase();
        if (email == null || email.isEmpty() || password == null || password.isEmpty()) {
            throw AdminException.fail("Ingresa tu correo y tu contraseña.");
        }
        if (email.length() > 255 || password.length() > 1024) {
            throw AdminException.fail(AdminMessages.BAD_CREDENTIALS);
        }
        LocalDateTime now = Clock.nowUtc();
        Optional<AdminUser> found = users.findAdminByEmail(email);
        if (found.isEmpty()) {
            passwordMatches(password, dummyHash);
            audit.registrarAdmin(request, null, email, "admin.login.fallido", null, null, null,
                    "Inicio de sesión fallido en el panel (correo no registrado como administrador)", null);
            throw AdminException.fail(AdminMessages.BAD_CREDENTIALS);
        }
        AdminUser user = found.get();
        boolean ok = passwordMatches(password, user.passwordHash());
        if (user.lockedAt(now)) {
            // Solo quien acierta la contraseña se entera del bloqueo; el contador no sigue subiendo
            audit.registrarAdmin(request, user.id(), user.email(), "admin.login.fallido", null, AuditLogService.SUBJECT_USER, user.id(),
                    "Inicio de sesión rechazado: cuenta bloqueada", null);
            throw AdminException.fail(ok ? AdminMessages.LOCKED : AdminMessages.BAD_CREDENTIALS);
        }
        if (!ok) {
            registerFailure(user, request, "contraseña incorrecta");
            throw AdminException.fail(AdminMessages.BAD_CREDENTIALS);
        }
        if (!user.canUsePanel()) {
            audit.registrarAdmin(request, user.id(), user.email(), "admin.login.fallido", null, AuditLogService.SUBJECT_USER, user.id(),
                    "Inicio de sesión rechazado: usuario desactivado o sin rol del panel", null);
            throw AdminException.fail(AdminMessages.INACTIVE);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (user.mfaEnabled()) {
            data.put("mfaRequired", true);
            data.put("mfaTicket", tokens.jwt().issueMfaTicket(user.id(), AdminJwt.STAGE_VERIFY, user.sessionVersion(), Clock.now()));
        } else {
            data.put("mfaSetupRequired", true);
            data.put("mfaTicket", tokens.jwt().issueMfaTicket(user.id(), AdminJwt.STAGE_SETUP, user.sessionVersion(), Clock.now()));
        }
        return data;
    }

    // ------------------------------------------------------------------ paso 2: código

    /** {@code POST auth/login/mfa}: código TOTP o de recuperación → tokens. */
    public Map<String, Object> completeMfa(String ticket, String code, String recoveryCode, HttpServletRequest request) {
        AdminUser user = userFromTicket(ticket, AdminJwt.STAGE_VERIFY);
        if (!user.mfaEnabled()) {
            throw AdminException.fail(AdminMessages.TICKET_INVALID);
        }
        boolean hasCode = code != null && !code.isBlank();
        boolean hasRecovery = recoveryCode != null && !recoveryCode.isBlank();
        if (!hasCode && !hasRecovery) {
            throw AdminException.fail(AdminMessages.CODE_REQUIRED);
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
        AdminUser fresh = users.findById(user.id()).orElse(user);
        audit.registrarAdmin(request, user.id(), user.email(), "admin.login", null, AuditLogService.SUBJECT_USER, user.id(),
                "Inicio de sesión en el panel (" + via + ")", null);
        Map<String, Object> data = issueTokens(fresh);
        data.put("recoveryCodesLeft", RecoveryCodes.fromJson(fresh.twoFactorRecoveryCodes()).size());
        return data;
    }

    /** {@code POST auth/login/mfa/setup}: clave nueva (pendiente hasta confirmarla). */
    public Map<String, Object> setupMfa(String ticket) {
        AdminUser user = userFromTicket(ticket, AdminJwt.STAGE_SETUP);
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
        AdminUser user = userFromTicket(ticket, AdminJwt.STAGE_SETUP);
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
            throw AdminException.fail(AdminMessages.TICKET_INVALID);
        }
        users.loginSucceeded(user.id(), now);
        audit.registrarAdmin(request, user.id(), user.email(), "admin.mfa.configurado", null, AuditLogService.SUBJECT_USER, user.id(),
                "MFA configurado en el panel", null);
        audit.registrarAdmin(request, user.id(), user.email(), "admin.login", null, AuditLogService.SUBJECT_USER, user.id(),
                "Inicio de sesión en el panel (configuración del MFA)", null);
        Map<String, Object> data = issueTokens(users.findById(user.id()).orElse(user));
        data.put("recoveryCodes", codes);
        return data;
    }

    // ------------------------------------------------------------------ refresh y logout

    /** {@code POST auth/refreshToken}: usa el refresh token (lo revoca) y entrega otro par. */
    public Map<String, Object> refresh(String accessToken, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank() || refreshToken.length() > 200) {
            throw AdminException.fail(AdminMessages.SESSION_EXPIRED);
        }
        LocalDateTime now = Clock.nowUtc();
        Optional<Long> owner = users.consumeRefreshToken(sha256(refreshToken.trim()), now);
        if (owner.isEmpty()) {
            throw AdminException.fail(AdminMessages.SESSION_EXPIRED);
        }
        if (accessToken != null && !accessToken.isBlank()) {
            Optional<Long> sub = tokens.jwt().subjectOfAccessIgnoringExpiry(accessToken);
            if (sub.isEmpty() || !sub.get().equals(owner.get())) {
                throw AdminException.fail(AdminMessages.SESSION_EXPIRED);
            }
        }
        AdminUser user = users.findById(owner.get()).filter(AdminUser::canUsePanel)
                .orElseThrow(() -> AdminException.fail(AdminMessages.SESSION_EXPIRED));
        Map<String, Object> data = issueTokens(user);
        data.put("recoveryCodesLeft", RecoveryCodes.fromJson(user.twoFactorRecoveryCodes()).size());
        return data;
    }

    /** {@code POST auth/logout}: cierra la sesión en todos los dispositivos. */
    public void logout(AdminPrincipal principal, HttpServletRequest request) {
        closeSessions(principal.userId());
        audit.registrarAdmin(request, principal.userId(), principal.email(), "admin.logout", null, AuditLogService.SUBJECT_USER,
                principal.userId(), "Cierre de sesión en el panel", null);
    }

    /** Sube session_version, revoca los refresh tokens y olvida la caché de esta instancia. */
    public void closeSessions(long userId) {
        users.bumpSession(userId, Clock.nowUtc());
        sessions.invalidate(userId);
    }

    // ------------------------------------------------------------------ cuenta propia

    public Map<String, Object> me(AdminPrincipal principal) {
        AdminUser u = users.findById(principal.userId()).orElseThrow(() -> AdminException.status(401, AdminMessages.SESSION_INVALID));
        String name = u.name() == null ? "" : u.name().trim();
        int sp = name.indexOf(' ');
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", u.id());
        data.put("userName", u.email());
        data.put("firstName", sp < 0 ? name : name.substring(0, sp));
        data.put("lastName", sp < 0 ? "" : name.substring(sp + 1).trim());
        data.put("fullName", name);
        data.put("email", u.email());
        data.put("profilePictureUrl", null);
        data.put("role", u.role());
        return data;
    }

    /** {@code PUT auth/password}: cambia la propia contraseña y cierra todas las sesiones. */
    public void changePassword(AdminPrincipal principal, String current, String newPassword, String confirm, HttpServletRequest request) {
        AdminUser u = users.findById(principal.userId()).orElseThrow(() -> AdminException.status(401, AdminMessages.SESSION_INVALID));
        AdminValidation v = new AdminValidation();
        v.required("password", current, "Ingresa tu contraseña actual.");
        v.required("newPassword", newPassword, "Ingresa la nueva contraseña.");
        v.throwIfAny();
        if (!passwordMatches(current, u.passwordHash())) {
            LocalDateTime lockedUntil = users.registerFailure(u.id(), MAX_FAILURES, Clock.nowUtc().plus(LOCK_DURATION), Clock.nowUtc());
            audit.registrarAdmin(request, u.id(), u.email(), "admin.password.fallido", null, AuditLogService.SUBJECT_USER, u.id(),
                    "Cambio de contraseña rechazado: la contraseña actual no coincide", null);
            if (lockedUntil != null && lockedUntil.isAfter(Clock.nowUtc())) {
                lockAudit(u, request);
                closeSessions(u.id());
                throw AdminException.fail(AdminMessages.LOCKED);
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
        audit.registrarAdmin(request, u.id(), u.email(), "admin.password.cambiada", null, AuditLogService.SUBJECT_USER, u.id(),
                "Cambio de la propia contraseña (se cerraron todas las sesiones)", null);
    }

    /**
     * {@code PUT auth/me}: cambia el nombre y el correo propios, confirmando con la contraseña actual (una contraseña
     * incorrecta cuenta para el bloqueo, como al cambiarla). El correo es el usuario del login y debe ser único entre
     * todos los usuarios. Las sesiones siguen abiertas (el JWT no lleva datos personales).
     */
    public Map<String, Object> updateProfile(AdminPrincipal principal, String name, String email, String password, HttpServletRequest request) {
        AdminUser u = users.findById(principal.userId()).orElseThrow(() -> AdminException.status(401, AdminMessages.SESSION_INVALID));
        AdminValidation v = new AdminValidation();
        v.required("name", name, "El nombre es obligatorio.");
        v.maxLength("name", name, 255, "El nombre no debe superar los 255 caracteres.");
        if (email == null) {
            v.add("email", "El correo es obligatorio.");
        } else if (!AdminValidation.isEmail(email)) {
            v.add("email", "El correo no es válido.");
        }
        v.required("password", password, "Ingresa tu contraseña actual para confirmar el cambio.");
        v.throwIfAny();
        if (!passwordMatches(password, u.passwordHash())) {
            LocalDateTime lockedUntil = users.registerFailure(u.id(), MAX_FAILURES, Clock.nowUtc().plus(LOCK_DURATION), Clock.nowUtc());
            audit.registrarAdmin(request, u.id(), u.email(), "admin.perfil.fallido", null, AuditLogService.SUBJECT_USER, u.id(),
                    "Cambio del perfil rechazado: la contraseña actual no coincide", null);
            if (lockedUntil != null && lockedUntil.isAfter(Clock.nowUtc())) {
                lockAudit(u, request);
                closeSessions(u.id());
                throw AdminException.fail(AdminMessages.LOCKED);
            }
            throw AdminException.fail("La contraseña actual no es correcta.");
        }
        if (users.emailTaken(email, u.id())) {
            v.add("email", "Ya existe un usuario con ese correo.");
            v.throwIfAny();
        }
        users.updateNameEmail(u.id(), name, email, Clock.nowUtc());
        sessions.invalidate(u.id());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("nombre_cambiado", !name.equals(u.name()));
        meta.put("correo_anterior", u.email());
        meta.put("correo", email);
        audit.registrarAdmin(request, u.id(), email, "admin.perfil.actualizado", null, AuditLogService.SUBJECT_USER, u.id(),
                "Perfil propio actualizado" + (email.equalsIgnoreCase(u.email()) ? "" : " (correo: " + u.email() + " → " + email + ")"), meta);
        return me(principal);
    }

    public Map<String, Object> mfaStatus(AdminPrincipal principal) {
        AdminUser u = users.findById(principal.userId()).orElseThrow(() -> AdminException.status(401, AdminMessages.SESSION_INVALID));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", u.mfaEnabled());
        data.put("recoveryCodesLeft", RecoveryCodes.fromJson(u.twoFactorRecoveryCodes()).size());
        return data;
    }

    /** {@code POST auth/mfa/recovery-codes}: con un código TOTP vigente, genera 10 códigos nuevos (los anteriores dejan de valer). */
    public Map<String, Object> regenerateRecoveryCodes(AdminPrincipal principal, String code, HttpServletRequest request) {
        AdminUser u = users.findById(principal.userId()).orElseThrow(() -> AdminException.status(401, AdminMessages.SESSION_INVALID));
        if (!u.mfaEnabled()) {
            throw AdminException.fail("El MFA no está configurado.");
        }
        if (code == null || code.isBlank()) {
            throw AdminException.fail("Ingresa el código de tu aplicación autenticadora.");
        }
        if (!verifyTotp(u, code)) {
            LocalDateTime lockedUntil = users.registerFailure(u.id(), MAX_FAILURES, Clock.nowUtc().plus(LOCK_DURATION), Clock.nowUtc());
            audit.registrarAdmin(request, u.id(), u.email(), "admin.mfa.codigo_fallido", null, AuditLogService.SUBJECT_USER, u.id(),
                    "Código TOTP incorrecto al regenerar los códigos de recuperación", null);
            if (lockedUntil != null && lockedUntil.isAfter(Clock.nowUtc())) {
                lockAudit(u, request);
                closeSessions(u.id());
                throw AdminException.fail(AdminMessages.LOCKED);
            }
            throw AdminException.fail(AdminMessages.BAD_CODE);
        }
        List<String> codes = RecoveryCodes.generate();
        users.setRecoveryCodes(u.id(), RecoveryCodes.toJson(RecoveryCodes.hashAll(codes)), Clock.nowUtc());
        audit.registrarAdmin(request, u.id(), u.email(), "admin.mfa.codigos_regenerados", null, AuditLogService.SUBJECT_USER, u.id(),
                "Códigos de recuperación regenerados", null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recoveryCodes", codes);
        return data;
    }

    // ------------------------------------------------------------------ apoyo

    /** Usuario de un ticket MFA vigente de la etapa pedida; el ticket deja de valer si cambió session_version. */
    private AdminUser userFromTicket(String ticket, String stage) {
        Optional<AdminJwt.MfaClaims> claims = tokens.jwt().verifyMfaTicket(ticket, Clock.now());
        if (claims.isEmpty() || !stage.equals(claims.get().stage())) {
            throw AdminException.fail(AdminMessages.TICKET_INVALID);
        }
        AdminUser user = users.findAdminById(claims.get().userId())
                .filter(u -> u.sessionVersion() == claims.get().sessionVersion())
                .orElseThrow(() -> AdminException.fail(AdminMessages.TICKET_INVALID));
        if (!user.canUsePanel()) {
            throw AdminException.fail(AdminMessages.INACTIVE);
        }
        if (user.lockedAt(Clock.nowUtc())) {
            throw AdminException.fail(AdminMessages.LOCKED);
        }
        return user;
    }

    /** TOTP vigente y no usado antes (el paso usado queda marcado en Redis). */
    private boolean verifyTotp(AdminUser user, String code) {
        String secret;
        try {
            secret = cipher.decrypt(user.twoFactorSecret());
        } catch (SecretCipher.DecryptException e) {
            log.warn("admin.mfa.clave_ilegible usuario={}", user.id());
            throw AdminException.fail("No se pudo leer la clave del MFA. Pide a un superadministrador que restablezca tu MFA.");
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
            Boolean first = redis.opsForValue().setIfAbsent("admin:totp-used:" + user.id() + ":" + step, "1", Duration.ofMinutes(2));
            return !Boolean.FALSE.equals(first);
        } catch (RuntimeException e) {
            // Sin Redis no se puede marcar el código: se acepta (la ventana es de 90 s)
            log.warn("admin.mfa.redis_no_disponible error={}", e.getClass().getSimpleName());
            return true;
        }
    }

    private boolean consumeRecoveryCode(AdminUser user, String code) {
        List<String> hashes = RecoveryCodes.fromJson(user.twoFactorRecoveryCodes());
        Optional<List<String>> remaining = RecoveryCodes.consume(hashes, code);
        if (remaining.isEmpty()) {
            return false;
        }
        return users.replaceRecoveryCodes(user.id(), user.twoFactorRecoveryCodes(), RecoveryCodes.toJson(remaining.get()), Clock.nowUtc());
    }

    /** Código incorrecto: cuenta para el bloqueo y siempre lanza. */
    private void failCode(AdminUser user, HttpServletRequest request, String via) {
        boolean locked = registerFailure(user, request, via + " incorrecto");
        throw AdminException.fail(locked ? AdminMessages.LOCKED : AdminMessages.BAD_CODE);
    }

    /** Suma un fallo; true si con este la cuenta quedó bloqueada. */
    private boolean registerFailure(AdminUser user, HttpServletRequest request, String motivo) {
        LocalDateTime now = Clock.nowUtc();
        LocalDateTime lockedUntil = users.registerFailure(user.id(), MAX_FAILURES, now.plus(LOCK_DURATION), now);
        audit.registrarAdmin(request, user.id(), user.email(), "admin.login.fallido", null, AuditLogService.SUBJECT_USER, user.id(),
                "Inicio de sesión fallido en el panel: " + motivo, null);
        boolean locked = lockedUntil != null && lockedUntil.isAfter(now);
        if (locked) {
            lockAudit(user, request);
        }
        return locked;
    }

    private void lockAudit(AdminUser user, HttpServletRequest request) {
        audit.registrarAdmin(request, user.id(), user.email(), "admin.cuenta.bloqueada", null, AuditLogService.SUBJECT_USER, user.id(),
                "Cuenta bloqueada 15 minutos por " + MAX_FAILURES + " intentos fallidos", null);
    }

    /** Token de acceso (30 min) + refresh opaco de 32 bytes (se guarda solo su SHA-256, 7 días). */
    private Map<String, Object> issueTokens(AdminUser user) {
        Instant now = Clock.now();
        String access = tokens.jwt().issueAccess(user.id(), user.role(), user.sessionVersion(), now);
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

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
