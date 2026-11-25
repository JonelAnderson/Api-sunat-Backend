package pe.facturacion.api.modules.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import pe.facturacion.api.config.AdminProperties;
import pe.facturacion.api.config.AdminServiceProperties;

/**
 * Acceso de servicio de la consola de plataforma (X-Admin-Key): falla cerrada ante clave, IP, actor, ruta y permiso.
 * Todas las claves son de prueba, sin valor real.
 */
class AdminServiceAccessTest {

    static final String KEY = "clave-de-servicio-de-prueba-0123456789abcdef";
    static final String CONSOLE_IP = "10.20.30.40";

    static class Fake {
        @AdminPermission(AdminPermissions.TENANTS_VIEW)
        public void tenants() {
        }

        @AdminPermission(AdminPermissions.TENANTS_MANAGE)
        public void estado() {
        }

        @AdminPermission(AdminPermissions.JOBS_RETRY)
        public void retry() {
        }

        @AdminPermission(AdminPermissions.TENANTS_CREDENTIALS)
        public void credentials() {
        }

        @AdminPermission(AdminPermissions.USERS_MANAGE)
        public void users() {
        }

        @AdminPermission(AdminPermissions.DOCUMENTS_RESEND)
        public void resend() {
        }

        @AdminPermission(AdminPermissions.PORTAL_MANAGE)
        public void portal() {
        }

        /** Sin permiso declarado. */
        public void undeclared() {
        }

        @AdminPublic
        public void login() {
        }
    }

    AdminRateLimiter limiter;
    AdminInterceptor interceptor;

    @BeforeEach
    void setUp() {
        limiter = mock(AdminRateLimiter.class);
        when(limiter.allow(anyString())).thenReturn(true);
        interceptor = interceptor(KEY, CONSOLE_IP);
    }

    private AdminInterceptor interceptor(String key, String ips) {
        // El panel sin ADMIN_JWT_SECRET: la consola funciona aunque no haya login de administradores
        return new AdminInterceptor(new AdminTokens(new AdminProperties("", null, null, null)), limiter, mock(AdminSessionCache.class),
                new AdminServiceProperties(key, ips));
    }

    @Test
    void con_clave_ip_y_actor_correctos_actua_con_el_rol_servicio() throws Exception {
        MockHttpServletRequest ok = service("PUT", "/api/admin/tenants/5/estado", KEY, "ana@consola");
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(ok, res, handler("estado"))).isTrue();
        assertThat(res.getHeader("Cache-Control")).isEqualTo("no-store");
        AdminPrincipal p = AdminPrincipal.require(ok);
        assertThat(p.role()).isEqualTo(AdminRole.SERVICIO);
        assertThat(p.email()).isEqualTo("consola:ana@consola");
        assertThat(p.userId()).isEqualTo(AdminInterceptor.SERVICE_USER_ID);

        assertThat(interceptor.preHandle(service("GET", "/api/admin/tenants", KEY, "ana"), new MockHttpServletResponse(), handler("tenants")))
                .isTrue();
        assertThat(interceptor.preHandle(service("POST", "/api/admin/jobs/1/retry", KEY, "ana"), new MockHttpServletResponse(),
                handler("retry"))).isTrue();
    }

    @Test
    void sin_clave_de_servicio_configurada_503() throws Exception {
        for (String key : new String[] {"", null, "corta"}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            assertThat(interceptor(key, CONSOLE_IP).preHandle(service("GET", "/api/admin/tenants", "corta", "ana"), res, handler("tenants")))
                    .isFalse();
            assertThat(res.getStatus()).isEqualTo(503);
            assertThat(res.getContentAsString()).contains("ADMIN_SERVICE_KEY");
        }
    }

    @Test
    void la_clave_sola_no_basta_ip_fuera_de_la_lista_403() throws Exception {
        for (String ips : new String[] {"", null, "*", "10.20.30.41", "192.168.0.0/16"}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            assertThat(interceptor(KEY, ips).preHandle(service("GET", "/api/admin/tenants", KEY, "ana"), res, handler("tenants"))).isFalse();
            assertThat(res.getStatus()).as("ips=%s", ips).isEqualTo(403);
        }
        // Una red CIDR que sí la incluye
        assertThat(interceptor(KEY, "10.20.30.0/24").preHandle(service("GET", "/api/admin/tenants", KEY, "ana"), new MockHttpServletResponse(),
                handler("tenants"))).isTrue();
    }

    @Test
    void clave_incorrecta_401_y_con_limite_por_ip_429() throws Exception {
        MockHttpServletResponse bad = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(service("GET", "/api/admin/tenants", KEY + "x", "ana"), bad, handler("tenants"))).isFalse();
        assertThat(bad.getStatus()).isEqualTo(401);
        // Ni siquiera dice si la clave existe: mismo mensaje que sin sesión
        assertThat(bad.getContentAsString()).doesNotContain(KEY);

        when(limiter.allow(anyString())).thenReturn(false);
        MockHttpServletResponse limited = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(service("GET", "/api/admin/tenants", KEY + "x", "ana"), limited, handler("tenants"))).isFalse();
        assertThat(limited.getStatus()).isEqualTo(429);
    }

    @Test
    void sin_actor_o_con_actor_invalido_400() throws Exception {
        for (String actor : new String[] {null, "", "   ", "a".repeat(121), "ana\nroot"}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(service("GET", "/api/admin/tenants", KEY, actor), res, handler("tenants"))).isFalse();
            assertThat(res.getStatus()).as("actor=%s", actor).isEqualTo(400);
        }
    }

    @Test
    void el_rol_servicio_no_llega_a_credenciales_usuarios_comprobantes_ni_a_lo_que_no_declara_permiso() throws Exception {
        for (String m : new String[] {"credentials", "users", "resend", "undeclared"}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(service("POST", "/api/admin/x", KEY, "ana"), res, handler(m))).as(m).isFalse();
            assertThat(res.getStatus()).as(m).isEqualTo(403);
        }
        // Nunca por /api/admin/auth/**, ni con un permiso permitido ni en un endpoint público
        for (String m : new String[] {"tenants", "login"}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(service("GET", "/api/admin/auth/me", KEY, "ana"), res, handler(m))).as(m).isFalse();
            assertThat(res.getStatus()).as(m).isEqualTo(403);
        }
    }

    @Test
    void el_rol_servicio_no_se_puede_asignar_ni_iniciar_sesion_con_el() {
        assertThat(AdminRole.codes()).doesNotContain("servicio").containsExactly("superadmin", "operador", "soporte", "lectura");
        assertThat(AdminRole.fromCode("servicio")).isEmpty();
        for (String perm : AdminPermissions.ALL) {
            boolean expected = switch (perm) {
                case AdminPermissions.TENANTS_VIEW, AdminPermissions.TENANTS_MANAGE, AdminPermissions.USAGE_VIEW,
                        AdminPermissions.CERTIFICATES_VIEW, AdminPermissions.REJECTIONS_VIEW, AdminPermissions.JOBS_VIEW,
                        AdminPermissions.JOBS_RETRY, AdminPermissions.HEALTH_VIEW, AdminPermissions.PLANS_VIEW,
                        AdminPermissions.PORTAL_MANAGE, AdminPermissions.TENANTS_CREDENTIALS_ROTATE -> true;
                default -> false;
            };
            assertThat(AdminRole.SERVICIO.has(perm)).as(perm).isEqualTo(expected);
        }
    }

    @Test
    void la_consola_administra_el_portal_de_clientes_y_solo_el_superadmin_entre_los_roles_del_panel() throws Exception {
        // La consola (rol servicio) invita y deshabilita por /api/admin/portal/**
        for (String[] call : new String[][] {{"POST", "/api/admin/portal/invitations"}, {"GET", "/api/admin/portal/users"},
                {"PUT", "/api/admin/portal/users/5/estado"}}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(service(call[0], call[1], KEY, "ana"), res, handler("portal"))).as(call[1]).isTrue();
        }
        // Una clave de servicio incorrecta no entra al portal
        MockHttpServletResponse bad = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(service("POST", "/api/admin/portal/invitations", KEY + "x", "ana"), bad, handler("portal"))).isFalse();
        assertThat(bad.getStatus()).isEqualTo(401);
        // Y de los roles del panel solo el superadmin: un token de invitación da acceso a empresas ajenas
        // Regenerar la api_key / api_secret (entrega al ERP) es del superadmin, el operador y la consola; nunca de soporte ni lectura,
        // y la consola no gana por eso la clave SOL, el certificado ni las credenciales GRE (TENANTS_CREDENTIALS).
        assertThat(AdminRole.SUPERADMIN.has(AdminPermissions.TENANTS_CREDENTIALS_ROTATE)).isTrue();
        assertThat(AdminRole.OPERADOR.has(AdminPermissions.TENANTS_CREDENTIALS_ROTATE)).isTrue();
        assertThat(AdminRole.SERVICIO.has(AdminPermissions.TENANTS_CREDENTIALS_ROTATE)).isTrue();
        assertThat(AdminRole.SERVICIO.has(AdminPermissions.TENANTS_CREDENTIALS)).isFalse();
        assertThat(AdminRole.SOPORTE.has(AdminPermissions.TENANTS_CREDENTIALS_ROTATE)).isFalse();
        assertThat(AdminRole.LECTURA.has(AdminPermissions.TENANTS_CREDENTIALS_ROTATE)).isFalse();
        assertThat(AdminRole.SUPERADMIN.has(AdminPermissions.PORTAL_MANAGE)).isTrue();
        for (AdminRole r : new AdminRole[] {AdminRole.OPERADOR, AdminRole.SOPORTE, AdminRole.LECTURA}) {
            assertThat(r.has(AdminPermissions.PORTAL_MANAGE)).as(r.code()).isFalse();
        }
    }

    @Test
    void el_login_de_administradores_sigue_igual_sin_la_cabecera_de_servicio() throws Exception {
        // Sin X-Admin-Key la petición va por la pila del JWT: con el panel apagado, 503 del panel
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockHttpServletRequest plain = new MockHttpServletRequest("GET", "/api/admin/tenants");
        plain.setRemoteAddr(CONSOLE_IP);
        assertThat(interceptor.preHandle(plain, res, handler("tenants"))).isFalse();
        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getContentAsString()).contains("ADMIN_JWT_SECRET");
    }

    private static MockHttpServletRequest service(String method, String uri, String key, String actor) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRemoteAddr(CONSOLE_IP);
        r.addHeader("X-Admin-Key", key == null ? "" : key);
        if (actor != null) {
            r.addHeader("X-Admin-Actor", actor);
        }
        return r;
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Fake(), Fake.class.getMethod(name));
    }
}
