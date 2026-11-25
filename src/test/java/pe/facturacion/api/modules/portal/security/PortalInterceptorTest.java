package pe.facturacion.api.modules.portal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import pe.facturacion.api.config.AdminProperties;
import pe.facturacion.api.config.PortalProperties;
import pe.facturacion.api.modules.admin.security.AdminInterceptor;
import pe.facturacion.api.modules.admin.security.AdminJwt;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.admin.security.AdminRateLimiter;
import pe.facturacion.api.modules.admin.security.AdminSessionCache;
import pe.facturacion.api.modules.admin.security.AdminTokens;
import pe.facturacion.api.modules.portal.user.PortalUserStore.SessionState;
import pe.facturacion.api.shared.time.Clock;

/**
 * Pila del portal: 503 sin clave, 429 por IP en /auth (también la activación), 401 sin token / token del panel /
 * sesión revocada / persona deshabilitada o sin activar, solo lectura fuera de /auth salvo regenerar credenciales.
 * Y en sentido contrario: el panel rechaza el token del portal.
 */
class PortalInterceptorTest {

    static final String PORTAL_SECRET = "clave-de-prueba-del-portal-0123456789abcdef";
    static final String ADMIN_SECRET = "clave-de-prueba-del-panel-0123456789abcdef";

    /** Controlador ficticio para construir los HandlerMethod. */
    static class Fake {
        @PortalPublic
        public void activate() {
        }

        @PortalPublic
        public void login() {
        }

        public void me() {
        }

        public void documents() {
        }

        public void regenerate() {
        }

        public void sneakyWrite() {
        }
    }

    PortalRateLimiter limiter;
    PortalSessionCache sessions;
    PortalInterceptor interceptor;
    PortalJwt jwt;

    @BeforeEach
    void setUp() {
        limiter = mock(PortalRateLimiter.class);
        when(limiter.allow(anyString())).thenReturn(true);
        sessions = mock(PortalSessionCache.class);
        interceptor = new PortalInterceptor(portalTokens(PORTAL_SECRET), limiter, sessions);
        jwt = new PortalJwt(PORTAL_SECRET.getBytes());
    }

    @AfterEach
    void tearDown() {
        Clock.useSystem();
    }

    static PortalTokens portalTokens(String portalSecret) {
        return new PortalTokens(new PortalProperties(portalSecret, null), new AdminProperties(ADMIN_SECRET, null, null, null));
    }

    @Test
    void sin_clave_del_portal_todo_responde_503_incluso_lo_publico() throws Exception {
        for (String secret : new String[] {"", null, "corta", ADMIN_SECRET}) {
            PortalInterceptor off = new PortalInterceptor(portalTokens(secret), limiter, sessions);
            MockHttpServletResponse res = new MockHttpServletResponse();
            assertThat(off.preHandle(request("POST", "/api/portal/auth/login", null), res, handler("login"))).as("secret=%s", secret).isFalse();
            assertThat(res.getStatus()).isEqualTo(503);
            assertThat(res.getContentAsString()).contains("PORTAL_JWT_SECRET").contains("\"succeeded\":false");
        }
    }

    @Test
    void los_endpoints_publicos_pasan_sin_token_y_las_respuestas_no_se_cachean() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("POST", "/api/portal/auth/login", null), res, handler("login"))).isTrue();
        assertThat(res.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(interceptor.preHandle(request("POST", "/api/portal/auth/activate", null), new MockHttpServletResponse(), handler("activate"))).isTrue();
    }

    @Test
    void el_limite_por_ip_corre_antes_de_autenticar_y_cubre_la_activacion() throws Exception {
        when(limiter.allow(anyString())).thenReturn(false);
        for (String[] call : new String[][] {{"POST", "/api/portal/auth/login", "login"}, {"POST", "/api/portal/auth/activate", "activate"},
                {"PUT", "/api/portal/auth/password", "me"}}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(request(call[0], call[1], null), res, handler(call[2]))).as(call[1]).isFalse();
            assertThat(res.getStatus()).as(call[1]).isEqualTo(429);
        }
        // Las lecturas no gastan el límite de autenticación
        MockHttpServletResponse read = new MockHttpServletResponse();
        when(sessions.get(anyLong())).thenReturn(Optional.of(state(true, true, 0)));
        assertThat(interceptor.preHandle(request("GET", "/api/portal/auth/me", jwt.issueAccess(1, 0, Clock.now())), read, handler("me"))).isTrue();
    }

    @Test
    void sin_token_o_con_token_invalido_401() throws Exception {
        MockHttpServletResponse none = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("GET", "/api/portal/auth/me", null), none, handler("me"))).isFalse();
        assertThat(none.getStatus()).isEqualTo(401);

        MockHttpServletResponse junk = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("GET", "/api/portal/auth/me", "no-es-un-jwt"), junk, handler("me"))).isFalse();
        assertThat(junk.getStatus()).isEqualTo(401);

        // Un ticket MFA no es un token de acceso
        MockHttpServletResponse ticket = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("GET", "/api/portal/auth/me", jwt.issueMfaTicket(1, PortalJwt.STAGE_VERIFY, 0, Clock.now())),
                ticket, handler("me"))).isFalse();
        assertThat(ticket.getStatus()).isEqualTo(401);
    }

    @Test
    void un_token_del_panel_no_entra_al_portal() throws Exception {
        String adminToken = new AdminJwt(ADMIN_SECRET.getBytes()).issueAccess(1, "superadmin", 0, Clock.now());
        when(sessions.get(anyLong())).thenReturn(Optional.of(state(true, true, 0)));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("GET", "/api/portal/tenants/1/resumen", adminToken), res, handler("documents"))).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        verify(sessions, never()).get(anyLong());
    }

    @Test
    void un_token_del_portal_no_entra_al_panel() throws Exception {
        // El panel con su propia pila: ni la clave ni el emisor coinciden
        AdminSessionCache adminSessions = mock(AdminSessionCache.class);
        AdminRateLimiter adminLimiter = mock(AdminRateLimiter.class);
        when(adminLimiter.allow(anyString())).thenReturn(true);
        AdminInterceptor admin = new AdminInterceptor(new AdminTokens(new AdminProperties(ADMIN_SECRET, null, null, null)), adminLimiter,
                adminSessions);
        String portalToken = jwt.issueAccess(1, 0, Clock.now());

        MockHttpServletRequest req = request("GET", "/api/admin/tenants", portalToken);
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(admin.preHandle(req, res, new HandlerMethod(new AdminFake(), AdminFake.class.getMethod("tenants")))).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        verify(adminSessions, never()).get(anyLong());

        // Tampoco con la misma clave en los dos mundos: el emisor del token manda
        AdminInterceptor sameSecret = new AdminInterceptor(new AdminTokens(new AdminProperties(PORTAL_SECRET, null, null, null)), adminLimiter,
                adminSessions);
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        assertThat(sameSecret.preHandle(request("GET", "/api/admin/tenants", portalToken), res2,
                new HandlerMethod(new AdminFake(), AdminFake.class.getMethod("tenants")))).isFalse();
        assertThat(res2.getStatus()).isEqualTo(401);
    }

    static class AdminFake {
        @AdminPermission(AdminPermissions.TENANTS_VIEW)
        public void tenants() {
        }
    }

    @Test
    void version_de_sesion_distinta_persona_deshabilitada_o_sin_activar_401() throws Exception {
        String token = jwt.issueAccess(1, 2, Clock.now());
        for (SessionState s : new SessionState[] {state(true, true, 3), state(false, true, 2), state(true, false, 2)}) {
            when(sessions.get(1)).thenReturn(Optional.of(s));
            MockHttpServletResponse res = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(request("GET", "/api/portal/auth/me", token), res, handler("me"))).as("%s", s).isFalse();
            assertThat(res.getStatus()).isEqualTo(401);
        }
        when(sessions.get(1)).thenReturn(Optional.empty());
        MockHttpServletResponse gone = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("GET", "/api/portal/auth/me", token), gone, handler("me"))).isFalse();
        assertThat(gone.getStatus()).isEqualTo(401);
    }

    @Test
    void con_sesion_valida_deja_a_la_persona_en_la_peticion() throws Exception {
        when(sessions.get(1)).thenReturn(Optional.of(state(true, true, 2)));
        MockHttpServletRequest req = request("GET", "/api/portal/tenants/9/resumen", jwt.issueAccess(1, 2, Clock.now()));
        assertThat(interceptor.preHandle(req, new MockHttpServletResponse(), handler("documents"))).isTrue();
        PortalPrincipal p = PortalPrincipal.require(req);
        assertThat(p.userId()).isEqualTo(1);
        assertThat(p.email()).isEqualTo("ana@cliente.pe");
    }

    @Test
    void solo_lectura_la_unica_escritura_fuera_de_auth_es_regenerar_credenciales() throws Exception {
        when(sessions.get(1)).thenReturn(Optional.of(state(true, true, 0)));
        String token = jwt.issueAccess(1, 0, Clock.now());

        // Cuenta propia (/auth): escribe
        assertThat(interceptor.preHandle(request("PUT", "/api/portal/auth/password", token), new MockHttpServletResponse(), handler("me"))).isTrue();
        // Regenerar credenciales: la excepción
        assertThat(interceptor.preHandle(request("POST", "/api/portal/tenants/12/credenciales/regenerar", token), new MockHttpServletResponse(),
                handler("regenerate"))).isTrue();
        // Cualquier otra escritura se rechaza aunque algún día alguien declare el endpoint
        for (String[] call : new String[][] {{"POST", "/api/portal/tenants/12/facturas"}, {"PUT", "/api/portal/tenants/12/facturas/5"},
                {"DELETE", "/api/portal/tenants/12/facturas/5"}, {"POST", "/api/portal/tenants/12/credenciales/regenerar/otro"},
                {"POST", "/api/portal/tenants/abc/credenciales/regenerar"}, {"PUT", "/api/portal/tenants/12/credenciales/regenerar"},
                {"POST", "/api/portal/tenants/12/credenciales/sol"}}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            boolean allowed = call[1].matches("^/api/portal/tenants/\\d+/credenciales/regenerar$") && "POST".equals(call[0]);
            assertThat(interceptor.preHandle(request(call[0], call[1], token), res, handler("sneakyWrite"))).as("%s %s", call[0], call[1])
                    .isEqualTo(allowed);
            if (!allowed) {
                assertThat(res.getStatus()).as(call[1]).isEqualTo(403);
            }
        }
        // Sin token una escritura sigue siendo 401 (no 403)
        MockHttpServletResponse anon = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("POST", "/api/portal/tenants/12/facturas", null), anon, handler("sneakyWrite"))).isFalse();
        assertThat(anon.getStatus()).isEqualTo(401);
    }

    private static SessionState state(boolean active, boolean activated, int sessionVersion) {
        return new SessionState(1, "ana@cliente.pe", "Ana", active, activated, sessionVersion);
    }

    private static MockHttpServletRequest request(String method, String uri, String bearer) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRequestURI(uri);
        r.setRemoteAddr("10.0.0.1");
        if (bearer != null) {
            r.addHeader("Authorization", "Bearer " + bearer);
        }
        return r;
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Fake(), Fake.class.getMethod(name));
    }
}
