package pe.facturacion.api.modules.portal.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.portal.user.PortalUserStore.SessionState;
import pe.facturacion.api.shared.time.Clock;

/**
 * Pila del portal de clientes para {@code /api/portal/**} (solo corre en rutas que existen, como el resto de la API):
 * <ol>
 *   <li>sin {@code PORTAL_JWT_SECRET} → 503;</li>
 *   <li>{@code POST / PUT} de {@code /api/portal/auth/**} (incluida la activación) → límite por IP (429), antes de
 *       autenticar;</li>
 *   <li>endpoints {@link PortalPublic} pasan; el resto exige {@code Authorization: Bearer} con un JWT de acceso
 *       <b>del portal</b> (emisor y audiencia propios) cuyo {@code sv} coincida con
 *       {@code portal_users.session_version} y cuya persona siga habilitada (401);</li>
 *   <li>solo lectura: fuera de {@code /auth} la única escritura permitida es regenerar las credenciales de una
 *       empresa; cualquier otra escritura se rechaza (403) aunque algún día se declare un endpoint (falla cerrada).</li>
 * </ol>
 * Las respuestas del portal llevan {@code Cache-Control: no-store}. Que la empresa de la ruta sea de la persona lo
 * resuelve {@code PortalTenantAccess} en cada endpoint ({@code tenants/{id}/**}, 404 si no).
 */
@Component
public class PortalInterceptor implements HandlerInterceptor {

    public static final String PREFIX = "/api/portal/";
    static final String AUTH_PREFIX = "/api/portal/auth/";
    /** La única escritura fuera de {@code /auth}: regenerar las credenciales de una empresa. */
    static final Pattern REGENERATE = Pattern.compile("^/api/portal/tenants/\\d+/credenciales/regenerar$");

    private final PortalTokens tokens;
    private final PortalRateLimiter rateLimiter;
    private final PortalSessionCache sessions;

    public PortalInterceptor(PortalTokens tokens, PortalRateLimiter rateLimiter, PortalSessionCache sessions) {
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        response.setHeader("Cache-Control", "no-store");
        if (!tokens.enabled()) {
            AdminResponse.write(response, 503, PortalMessages.PORTAL_DISABLED);
            return false;
        }
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        String path = request.getRequestURI();
        boolean read = isRead(request.getMethod());
        boolean authPath = path.startsWith(AUTH_PREFIX);
        if (authPath && !read && !rateLimiter.allow(request.getRemoteAddr())) {
            AdminResponse.write(response, 429, PortalMessages.TOO_MANY);
            return false;
        }
        if (method.hasMethodAnnotation(PortalPublic.class)) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7) || header.length() <= 7) {
            AdminResponse.write(response, 401, PortalMessages.UNAUTHENTICATED);
            return false;
        }
        Optional<PortalJwt.AccessClaims> claims = tokens.jwt().verifyAccess(header.substring(7).trim(), Clock.now());
        if (claims.isEmpty()) {
            AdminResponse.write(response, 401, PortalMessages.SESSION_INVALID);
            return false;
        }
        long userId = claims.get().userId();
        Optional<SessionState> state = sessions.get(userId);
        if (state.isEmpty() || !state.get().active() || !state.get().activated()
                || state.get().sessionVersion() != claims.get().sessionVersion()) {
            AdminResponse.write(response, 401, PortalMessages.SESSION_INVALID);
            return false;
        }
        boolean regenerate = "POST".equals(request.getMethod()) && REGENERATE.matcher(path).matches();
        if (!read && !authPath && !regenerate) {
            AdminResponse.write(response, 403, PortalMessages.FORBIDDEN);
            return false;
        }
        new PortalPrincipal(userId, state.get().email(), state.get().name()).bind(request);
        return true;
    }

    private static boolean isRead(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }
}
