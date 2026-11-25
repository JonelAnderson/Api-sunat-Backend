package pe.facturacion.api.modules.admin.security;

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
import pe.facturacion.api.modules.admin.user.AdminUserStore.SessionState;
import pe.facturacion.api.shared.time.Clock;

/** Pila del panel: 503 sin clave, 429 por IP, 401 sin token / sesión revocada y 403 por rol. */
class AdminInterceptorTest {

    static final String SECRET = "clave-de-prueba-del-panel-0123456789abcdef";

    /** Controlador ficticio para construir los HandlerMethod. */
    static class Fake {
        @AdminPublic
        public void login() {
        }

        public void me() {
        }

        @AdminPermission(AdminPermissions.TENANTS_MANAGE)
        public void suspend() {
        }

        @AdminPermission(AdminPermissions.USERS_MANAGE)
        public void users() {
        }

        @AdminPermission(AdminPermissions.TENANTS_VIEW)
        public void tenants() {
        }

        @AdminPermission(AdminPermissions.PLANS_MANAGE)
        public void savePlan() {
        }

        @AdminPermission(AdminPermissions.PLANS_VIEW)
        public void plans() {
        }

        @AdminPermission(AdminPermissions.DOCUMENTS_RESEND)
        public void resend() {
        }
    }

    AdminRateLimiter limiter;
    AdminSessionCache sessions;
    AdminInterceptor interceptor;
    AdminJwt jwt;

    @BeforeEach
    void setUp() {
        limiter = mock(AdminRateLimiter.class);
        when(limiter.allow(anyString())).thenReturn(true);
        sessions = mock(AdminSessionCache.class);
        interceptor = new AdminInterceptor(new AdminTokens(new AdminProperties(SECRET, null, null, null)), limiter, sessions);
        jwt = new AdminJwt(SECRET.getBytes());
    }

    @AfterEach
    void tearDown() {
        Clock.useSystem();
    }

    @Test
    void sin_clave_todo_responde_503() throws Exception {
        AdminInterceptor off = new AdminInterceptor(new AdminTokens(new AdminProperties("", null, null, null)), limiter, sessions);
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(off.preHandle(req("POST", "/api/admin/auth/login"), res, handler("login"))).isFalse();
        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getContentAsString()).contains("\"succeeded\":false").contains("ADMIN_JWT_SECRET");
        // Clave demasiado corta: también apagado
        AdminInterceptor shortKey = new AdminInterceptor(new AdminTokens(new AdminProperties("corta", null, null, null)), limiter, sessions);
        assertThat(shortKey.preHandle(req("GET", "/api/admin/dashboard"), new MockHttpServletResponse(), handler("tenants"))).isFalse();
    }

    @Test
    void login_publico_pero_con_limite_por_ip() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req("POST", "/api/admin/auth/login"), res, handler("login"))).isTrue();
        assertThat(res.getHeader("Cache-Control")).isEqualTo("no-store");

        when(limiter.allow(anyString())).thenReturn(false);
        MockHttpServletResponse limited = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req("POST", "/api/admin/auth/login"), limited, handler("login"))).isFalse();
        assertThat(limited.getStatus()).isEqualTo(429);
    }

    @Test
    void las_lecturas_de_auth_no_cuentan_para_el_limite() throws Exception {
        when(sessions.get(anyLong())).thenReturn(Optional.of(state("lectura", 0)));
        interceptor.preHandle(bearer(req("GET", "/api/admin/auth/me"), "lectura", 0), new MockHttpServletResponse(), handler("me"));
        verify(limiter, never()).allow(anyString());
    }

    @Test
    void sin_token_o_token_invalido_401() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req("GET", "/api/admin/auth/me"), res, handler("me"))).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);

        MockHttpServletRequest bad = req("GET", "/api/admin/auth/me");
        bad.addHeader("Authorization", "Bearer x.y.z");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(bad, res2, handler("me"))).isFalse();
        assertThat(res2.getStatus()).isEqualTo(401);

        // El ticket MFA no sirve como token de acceso
        MockHttpServletRequest ticket = req("GET", "/api/admin/auth/me");
        ticket.addHeader("Authorization", "Bearer " + jwt.issueMfaTicket(1, AdminJwt.STAGE_VERIFY, 0, Clock.now()));
        MockHttpServletResponse res3 = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(ticket, res3, handler("me"))).isFalse();
        assertThat(res3.getStatus()).isEqualTo(401);
    }

    @Test
    void sesion_revocada_o_usuario_desactivado_401() throws Exception {
        when(sessions.get(1L)).thenReturn(Optional.of(state("superadmin", 1)));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(bearer(req("GET", "/api/admin/auth/me"), "superadmin", 0), res, handler("me"))).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);

        when(sessions.get(1L)).thenReturn(Optional.of(new SessionState(1, "a@example.com", "Ana", "superadmin", true, false, 0)));
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(bearer(req("GET", "/api/admin/auth/me"), "superadmin", 0), res2, handler("me"))).isFalse();
        assertThat(res2.getStatus()).isEqualTo(401);
    }

    @Test
    void permisos_segun_el_rol_actual_de_la_bd() throws Exception {
        // El token dice superadmin, pero en la BD ya es lectura: manda la BD
        when(sessions.get(1L)).thenReturn(Optional.of(state("lectura", 0)));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(bearer(req("PUT", "/api/admin/tenants/5/estado"), "superadmin", 0), res, handler("suspend"))).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);

        MockHttpServletRequest ok = bearer(req("GET", "/api/admin/tenants"), "lectura", 0);
        assertThat(interceptor.preHandle(ok, new MockHttpServletResponse(), handler("tenants"))).isTrue();
        assertThat(AdminPrincipal.require(ok).role()).isEqualTo(AdminRole.LECTURA);
    }

    @Test
    void solo_superadmin_administra_usuarios() throws Exception {
        when(sessions.get(1L)).thenReturn(Optional.of(state("operador", 0)));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(bearer(req("GET", "/api/admin/users"), "operador", 0), res, handler("users"))).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);

        when(sessions.get(1L)).thenReturn(Optional.of(state("superadmin", 0)));
        assertThat(interceptor.preHandle(bearer(req("GET", "/api/admin/users"), "superadmin", 0), new MockHttpServletResponse(),
                handler("users"))).isTrue();
    }

    @Test
    void lectura_nunca_escribe_aunque_el_endpoint_no_pida_permiso() throws Exception {
        when(sessions.get(1L)).thenReturn(Optional.of(state("lectura", 0)));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(bearer(req("POST", "/api/admin/jobs/retry-all"), "lectura", 0), res, handler("me"))).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
        // Su propia cuenta (/auth) sí: cerrar sesión, cambiar contraseña
        assertThat(interceptor.preHandle(bearer(req("POST", "/api/admin/auth/logout"), "lectura", 0), new MockHttpServletResponse(),
                handler("me"))).isTrue();
    }

    @Test
    void soporte_lee_pero_no_escribe_planes() throws Exception {
        when(sessions.get(1L)).thenReturn(Optional.of(state("soporte", 0)));
        assertThat(interceptor.preHandle(bearer(req("GET", "/api/admin/plans"), "soporte", 0), new MockHttpServletResponse(),
                handler("plans"))).isTrue();
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(bearer(req("POST", "/api/admin/plans"), "soporte", 0), res, handler("savePlan"))).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
        MockHttpServletResponse estado = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(bearer(req("PUT", "/api/admin/tenants/5/estado"), "soporte", 0), estado, handler("suspend"))).isFalse();
        assertThat(estado.getStatus()).isEqualTo(403);
        // Fase D: escribe solo donde el endpoint pide un permiso que tiene
        assertThat(interceptor.preHandle(bearer(req("POST", "/api/admin/tenants/5/facturas/1/reenviar"), "soporte", 0),
                new MockHttpServletResponse(), handler("resend"))).isTrue();
    }

    @Test
    void una_escritura_sin_permiso_declarado_se_rechaza_para_todos() throws Exception {
        for (String role : java.util.List.of("superadmin", "operador", "soporte")) {
            when(sessions.get(1L)).thenReturn(Optional.of(state(role, 0)));
            MockHttpServletResponse res = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(bearer(req("POST", "/api/admin/algo-nuevo"), role, 0), res, handler("me"))).as(role).isFalse();
            assertThat(res.getStatus()).as(role).isEqualTo(403);
        }
        // Su propia cuenta (/auth) no declara permiso y sigue permitida
        when(sessions.get(1L)).thenReturn(Optional.of(state("soporte", 0)));
        assertThat(interceptor.preHandle(bearer(req("PUT", "/api/admin/auth/me"), "soporte", 0), new MockHttpServletResponse(),
                handler("me"))).isTrue();
    }

    @Test
    void lectura_no_tiene_las_escrituras_de_la_fase_d() throws Exception {
        when(sessions.get(1L)).thenReturn(Optional.of(state("lectura", 0)));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(bearer(req("POST", "/api/admin/tenants/5/facturas/1/reenviar"), "lectura", 0), res,
                handler("resend"))).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ apoyo

    private static MockHttpServletRequest req(String method, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRemoteAddr("203.0.113.7");
        return r;
    }

    private MockHttpServletRequest bearer(MockHttpServletRequest r, String role, int sv) {
        r.addHeader("Authorization", "Bearer " + jwt.issueAccess(1, role, sv, Clock.now()));
        return r;
    }

    private static SessionState state(String role, int sv) {
        return new SessionState(1, "a@example.com", "Ana Pérez", role, true, true, sv);
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Fake(), Fake.class.getMethod(name));
    }
}
