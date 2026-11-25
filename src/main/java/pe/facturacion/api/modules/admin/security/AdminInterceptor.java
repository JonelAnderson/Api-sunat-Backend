package pe.facturacion.api.modules.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import pe.facturacion.api.config.AdminServiceProperties;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.user.AdminUserStore.SessionState;
import pe.facturacion.api.shared.crypto.SecretCipher;
import pe.facturacion.api.shared.time.Clock;

/**
 * Pila del panel para {@code /api/admin/**} (solo corre en rutas que existen, como el resto de la API):
 * <ol>
 *   <li>sin {@code ADMIN_JWT_SECRET} → 503;</li>
 *   <li>{@code POST / PUT} de {@code /api/admin/auth/**} → límite por IP (429), antes de autenticar;</li>
 *   <li>endpoints {@link AdminPublic} pasan; el resto exige {@code Authorization: Bearer} con un JWT de acceso válido
 *       cuyo {@code sv} coincida con {@code users.session_version} (401);</li>
 *   <li>{@link AdminPermission} del endpoint según el rol actual de la BD (403) y, por si acaso, el rol de lectura
 *       nunca escribe fuera de su propia cuenta y ninguna escritura fuera de {@code /auth} pasa sin declarar su
 *       permiso (403).</li>
 * </ol>
 * Las respuestas del panel llevan {@code Cache-Control: no-store}.
 *
 * <p>Acceso de servicio de la consola de plataforma: una petición con {@code X-Admin-Key} no usa el JWT; se valida con
 * {@link #serviceAuth} (IP permitida, clave en tiempo constante, {@code X-Admin-Actor}) y actúa con el rol
 * {@link AdminRole#SERVICIO}.
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    public static final String PREFIX = "/api/admin/";
    static final String AUTH_PREFIX = "/api/admin/auth/";
    public static final String SERVICE_KEY_HEADER = "X-Admin-Key";
    public static final String SERVICE_ACTOR_HEADER = "X-Admin-Actor";
    /** {@code user_id} de la auditoría para la consola: no es un usuario del panel (los ids reales empiezan en 1). */
    public static final long SERVICE_USER_ID = 0L;
    /** Prefijo de {@code actor_etiqueta} en {@code audit_logs}. */
    public static final String SERVICE_ACTOR_PREFIX = "consola:";

    private final AdminTokens tokens;
    private final AdminRateLimiter rateLimiter;
    private final AdminSessionCache sessions;
    private final AdminServiceProperties service;
    private final IpAllowList serviceIps;

    /** Sin acceso de servicio (el de las pruebas del panel). */
    public AdminInterceptor(AdminTokens tokens, AdminRateLimiter rateLimiter, AdminSessionCache sessions) {
        this(tokens, rateLimiter, sessions, new AdminServiceProperties(null, null));
    }

    @Autowired
    public AdminInterceptor(AdminTokens tokens, AdminRateLimiter rateLimiter, AdminSessionCache sessions,
            AdminServiceProperties service) {
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.sessions = sessions;
        this.service = service;
        this.serviceIps = new IpAllowList(service.ips());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        response.setHeader("Cache-Control", "no-store");
        if (request.getHeader(SERVICE_KEY_HEADER) != null) {
            return serviceAuth(request, response, handler);
        }
        if (!tokens.enabled()) {
            AdminResponse.write(response, 503, AdminMessages.PANEL_DISABLED);
            return false;
        }
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        String path = request.getRequestURI();
        boolean read = isRead(request.getMethod());
        boolean authPath = path.startsWith(AUTH_PREFIX);
        if (authPath && !read && !rateLimiter.allow(request.getRemoteAddr())) {
            AdminResponse.write(response, 429, AdminMessages.TOO_MANY);
            return false;
        }
        if (method.hasMethodAnnotation(AdminPublic.class)) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7) || header.length() <= 7) {
            AdminResponse.write(response, 401, AdminMessages.UNAUTHENTICATED);
            return false;
        }
        Optional<AdminJwt.AccessClaims> claims = tokens.jwt().verifyAccess(header.substring(7).trim(), Clock.now());
        if (claims.isEmpty()) {
            AdminResponse.write(response, 401, AdminMessages.SESSION_INVALID);
            return false;
        }
        long userId = claims.get().userId();
        Optional<SessionState> state = sessions.get(userId);
        Optional<AdminRole> role = state.flatMap(s -> AdminRole.fromCode(s.role()));
        if (state.isEmpty() || role.isEmpty() || !state.get().admin() || !state.get().active()
                || state.get().sessionVersion() != claims.get().sessionVersion()) {
            AdminResponse.write(response, 401, AdminMessages.SESSION_INVALID);
            return false;
        }
        AdminPrincipal principal = new AdminPrincipal(userId, state.get().email(), state.get().name(), role.get());

        AdminPermission required = method.getMethodAnnotation(AdminPermission.class);
        if (required != null && !role.get().has(required.value())) {
            AdminResponse.write(response, 403, AdminMessages.FORBIDDEN);
            return false;
        }
        // Defensa adicional: "lectura" solo consulta; fuera de /auth (su propia cuenta) nunca escribe. Y toda escritura
        // fuera de /auth debe declarar su permiso: sin @AdminPermission se rechaza para todos (falla cerrada), así un
        // endpoint nuevo olvidado no queda abierto al rol de soporte.
        if (!read && !authPath && (!role.get().canWrite() || required == null)) {
            AdminResponse.write(response, 403, AdminMessages.FORBIDDEN);
            return false;
        }
        principal.bind(request);
        return true;
    }

    /**
     * Acceso de la consola de plataforma, en este orden y fallando cerrado:
     * <ol>
     *   <li>sin {@code ADMIN_SERVICE_KEY} (≥ 32 bytes) → 503;</li>
     *   <li>IP fuera de {@code ADMIN_SERVICE_ALLOWED_IPS} (o lista vacía) → 403, antes de mirar la clave;</li>
     *   <li>clave distinta (comparación en tiempo constante) → 401, con el mismo límite por IP que el login (429);</li>
     *   <li>sin {@code X-Admin-Actor} (el usuario de la consola que hizo la acción) → 400;</li>
     *   <li>solo endpoints que declaran su {@link AdminPermission} y que el rol {@link AdminRole#SERVICIO} tiene;
     *       nunca {@code /api/admin/auth/**} → 403.</li>
     * </ol>
     * El actor queda en la auditoría como {@code consola:<usuario>}.
     */
    private boolean serviceAuth(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!service.enabled()) {
            AdminResponse.write(response, 503, AdminMessages.SERVICE_DISABLED);
            return false;
        }
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        String ip = request.getRemoteAddr();
        if (!serviceIps.allows(ip)) {
            AdminResponse.write(response, 403, AdminMessages.FORBIDDEN);
            return false;
        }
        if (!SecretCipher.constantTimeEquals(service.keyValue(), request.getHeader(SERVICE_KEY_HEADER))) {
            boolean attemptsLeft = rateLimiter.allow(ip);
            AdminResponse.write(response, attemptsLeft ? 401 : 429, attemptsLeft ? AdminMessages.UNAUTHENTICATED : AdminMessages.TOO_MANY);
            return false;
        }
        String actor = cleanActor(request.getHeader(SERVICE_ACTOR_HEADER));
        if (actor == null) {
            AdminResponse.write(response, 400, AdminMessages.SERVICE_ACTOR_REQUIRED);
            return false;
        }
        AdminPermission required = method.getMethodAnnotation(AdminPermission.class);
        if (request.getRequestURI().startsWith(AUTH_PREFIX) || required == null || !AdminRole.SERVICIO.has(required.value())) {
            AdminResponse.write(response, 403, AdminMessages.FORBIDDEN);
            return false;
        }
        new AdminPrincipal(SERVICE_USER_ID, SERVICE_ACTOR_PREFIX + actor, actor, AdminRole.SERVICIO).bind(request);
        return true;
    }

    /** El actor sin espacios ni caracteres de control, de 1 a 120 caracteres; null si no sirve. */
    static String cleanActor(String raw) {
        if (raw == null) {
            return null;
        }
        String actor = raw.trim();
        if (actor.isEmpty() || actor.length() > 120 || actor.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        return actor;
    }

    private static boolean isRead(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }
}
