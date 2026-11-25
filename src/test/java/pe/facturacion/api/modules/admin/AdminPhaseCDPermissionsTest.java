package pe.facturacion.api.modules.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.method.HandlerMethod;
import pe.facturacion.api.config.AdminProperties;
import pe.facturacion.api.modules.admin.document.AdminDocumentActionsController;
import pe.facturacion.api.modules.admin.document.AdminDocumentIssueController;
import pe.facturacion.api.modules.admin.document.AdminFormCatalogsController;
import pe.facturacion.api.modules.admin.security.AdminInterceptor;
import pe.facturacion.api.modules.admin.security.AdminJwt;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.admin.security.AdminRateLimiter;
import pe.facturacion.api.modules.admin.security.AdminRole;
import pe.facturacion.api.modules.admin.security.AdminSessionCache;
import pe.facturacion.api.modules.admin.security.AdminTokens;
import pe.facturacion.api.modules.admin.tenant.AdminTenantCredentialsController;
import pe.facturacion.api.modules.admin.tenant.AdminTenantResourcesController;
import pe.facturacion.api.modules.admin.tenant.AdminTenantWriteController;
import pe.facturacion.api.modules.admin.user.AdminUserStore.SessionState;
import pe.facturacion.api.shared.time.Clock;

/**
 * Permisos de los endpoints de las fases C y D (§11 del diseño) leídos de los controladores reales: toda escritura
 * declara su permiso, {@code soporte} solo reenvía / anula / refresca, {@code lectura} no escribe nada y eliminar una
 * empresa es solo de superadmin.
 */
class AdminPhaseCDPermissionsTest {

    static final String SECRET = "clave-de-prueba-del-panel-0123456789abcdef";
    static final List<Class<?>> CONTROLLERS = List.of(AdminTenantWriteController.class, AdminTenantCredentialsController.class,
            AdminTenantResourcesController.class, AdminDocumentActionsController.class, AdminDocumentIssueController.class,
            AdminFormCatalogsController.class);

    @AfterEach
    void tearDown() {
        Clock.useSystem();
    }

    @Test
    void cada_endpoint_declara_su_permiso() {
        List<String> sin = new ArrayList<>();
        for (Method m : handlers()) {
            if (m.getAnnotation(AdminPermission.class) == null) {
                sin.add(m.getDeclaringClass().getSimpleName() + "." + m.getName());
            }
        }
        assertThat(sin).as("endpoints sin @AdminPermission").isEmpty();
    }

    @Test
    void matriz_de_permisos_por_endpoint() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("AdminTenantWriteController.create", AdminPermissions.TENANTS_EDIT);
        expected.put("AdminTenantWriteController.update", AdminPermissions.TENANTS_EDIT);
        expected.put("AdminTenantWriteController.logo", AdminPermissions.TENANTS_EDIT);
        expected.put("AdminTenantWriteController.rucLookup", AdminPermissions.TENANTS_EDIT);
        expected.put("AdminTenantWriteController.delete", AdminPermissions.TENANTS_DELETE);
        expected.put("AdminTenantCredentialsController.show", AdminPermissions.TENANTS_VIEW);
        expected.put("AdminTenantCredentialsController.sol", AdminPermissions.TENANTS_CREDENTIALS);
        expected.put("AdminTenantCredentialsController.gre", AdminPermissions.TENANTS_CREDENTIALS);
        expected.put("AdminTenantCredentialsController.certificado", AdminPermissions.TENANTS_CREDENTIALS);
        expected.put("AdminTenantCredentialsController.regenerar", AdminPermissions.TENANTS_CREDENTIALS_ROTATE);
        for (Method m : handlers()) {
            String key = m.getDeclaringClass().getSimpleName() + "." + m.getName();
            String perm = m.getAnnotation(AdminPermission.class).value();
            if (expected.containsKey(key)) {
                assertThat(perm).as(key).isEqualTo(expected.get(key));
            }
            if (m.getDeclaringClass() == AdminTenantResourcesController.class) {
                assertThat(perm).as(key).isEqualTo(AdminPermissions.TENANTS_EDIT);
            }
            if (m.getDeclaringClass() == AdminDocumentIssueController.class) {
                assertThat(perm).as(key).isEqualTo(AdminPermissions.DOCUMENTS_ISSUE);
            }
            if (m.getDeclaringClass() == AdminFormCatalogsController.class) {
                assertThat(perm).as(key).isEqualTo(AdminPermissions.TENANTS_VIEW);
            }
            if (m.getDeclaringClass() == AdminDocumentActionsController.class) {
                String expectedPerm = m.getName().startsWith("reenviar") ? AdminPermissions.DOCUMENTS_RESEND
                        : m.getName().startsWith("anular") || m.getName().startsWith("revertir") ? AdminPermissions.DOCUMENTS_VOID
                        : m.getName().startsWith("refrescar") ? AdminPermissions.DOCUMENTS_REFRESH
                        : AdminPermissions.DOCUMENTS_ISSUE; // corregir
                assertThat(perm).as(key).isEqualTo(expectedPerm);
            }
        }
    }

    @Test
    void soporte_reenvia_anula_y_refresca_pero_no_emite_ni_edita() {
        for (Method m : handlers()) {
            String perm = m.getAnnotation(AdminPermission.class).value();
            boolean write = !m.isAnnotationPresent(GetMapping.class);
            boolean allowed = AdminRole.SOPORTE.has(perm) && (!write || AdminRole.SOPORTE.canWrite());
            boolean resendVoidRefresh = List.of(AdminPermissions.DOCUMENTS_RESEND, AdminPermissions.DOCUMENTS_VOID,
                    AdminPermissions.DOCUMENTS_REFRESH).contains(perm);
            if (write) {
                assertThat(allowed).as(m.getName()).isEqualTo(resendVoidRefresh);
            }
        }
    }

    @Test
    void lectura_no_escribe_nada_y_solo_ve_indicadores() {
        for (Method m : handlers()) {
            String perm = m.getAnnotation(AdminPermission.class).value();
            boolean write = !m.isAnnotationPresent(GetMapping.class);
            boolean allowed = AdminRole.LECTURA.has(perm) && (!write || AdminRole.LECTURA.canWrite());
            if (write) {
                assertThat(allowed).as(m.getName()).isFalse();
            } else {
                // Lecturas: el estado de las credenciales (solo indicadores) y los catálogos sí; RUC y buscar-documento no
                assertThat(allowed).as(m.getName()).isEqualTo(AdminPermissions.TENANTS_VIEW.equals(perm));
            }
        }
    }

    @Test
    void eliminar_empresas_solo_superadmin() {
        assertThat(AdminRole.SUPERADMIN.has(AdminPermissions.TENANTS_DELETE)).isTrue();
        for (AdminRole r : List.of(AdminRole.OPERADOR, AdminRole.SOPORTE, AdminRole.LECTURA)) {
            assertThat(r.has(AdminPermissions.TENANTS_DELETE)).as(r.code()).isFalse();
        }
        assertThat(AdminPermissions.ALL).containsSubsequence(AdminPermissions.TENANTS_CREDENTIALS, AdminPermissions.TENANTS_DELETE,
                AdminPermissions.DOCUMENTS_ISSUE);
    }

    @Test
    void el_interceptor_aplica_la_matriz_a_los_controladores_reales() throws Exception {
        AdminRateLimiter limiter = mock(AdminRateLimiter.class);
        when(limiter.allow(anyString())).thenReturn(true);
        AdminSessionCache sessions = mock(AdminSessionCache.class);
        AdminInterceptor interceptor = new AdminInterceptor(new AdminTokens(new AdminProperties(SECRET, null, null, null)), limiter, sessions);
        AdminJwt jwt = new AdminJwt(SECRET.getBytes());
        Object actions = mock(AdminDocumentActionsController.class);
        Object issue = mock(AdminDocumentIssueController.class);
        Object write = mock(AdminTenantWriteController.class);
        Object creds = mock(AdminTenantCredentialsController.class);

        Map<String, Integer> soporte = new LinkedHashMap<>();
        Map<String, Integer> lectura = new LinkedHashMap<>();
        Map<String, Integer> operador = new LinkedHashMap<>();
        for (Object[] c : List.of(
                new Object[] {"POST", "/api/admin/tenants/5/facturas/1/enviar", actions, "reenviarVenta"},
                new Object[] {"POST", "/api/admin/tenants/5/facturas/1/anular", actions, "anularVenta"},
                new Object[] {"POST", "/api/admin/tenants/5/guias-remision/1/refrescar", actions, "refrescarGuia"},
                new Object[] {"PUT", "/api/admin/tenants/5/facturas/1", actions, "corregirVenta"},
                new Object[] {"POST", "/api/admin/tenants/5/facturas", issue, "emitirVenta"},
                new Object[] {"PUT", "/api/admin/tenants/5", write, "update"},
                new Object[] {"DELETE", "/api/admin/tenants/5", write, "delete"},
                new Object[] {"POST", "/api/admin/tenants/5/credenciales/regenerar", creds, "regenerar"},
                new Object[] {"GET", "/api/admin/tenants/5/credenciales", creds, "show"})) {
            String key = c[0] + " " + c[1];
            soporte.put(key, status(interceptor, sessions, jwt, "soporte", c));
            lectura.put(key, status(interceptor, sessions, jwt, "lectura", c));
            operador.put(key, status(interceptor, sessions, jwt, "operador", c));
        }
        assertThat(soporte).containsEntry("POST /api/admin/tenants/5/facturas/1/enviar", 200)
                .containsEntry("POST /api/admin/tenants/5/facturas/1/anular", 200)
                .containsEntry("POST /api/admin/tenants/5/guias-remision/1/refrescar", 200)
                .containsEntry("PUT /api/admin/tenants/5/facturas/1", 403)
                .containsEntry("POST /api/admin/tenants/5/facturas", 403)
                .containsEntry("PUT /api/admin/tenants/5", 403)
                .containsEntry("DELETE /api/admin/tenants/5", 403)
                .containsEntry("POST /api/admin/tenants/5/credenciales/regenerar", 403)
                .containsEntry("GET /api/admin/tenants/5/credenciales", 200);
        assertThat(lectura).allSatisfy((k, v) -> assertThat(v).as(k).isEqualTo(k.startsWith("GET") ? 200 : 403));
        assertThat(operador).allSatisfy((k, v) -> assertThat(v).as(k).isEqualTo(k.startsWith("DELETE") ? 403 : 200));
    }

    private static int status(AdminInterceptor interceptor, AdminSessionCache sessions, AdminJwt jwt, String role, Object[] c)
            throws Exception {
        when(sessions.get(1L)).thenReturn(Optional.of(new SessionState(1, "a@example.com", "Ana", role, true, true, 0)));
        MockHttpServletRequest req = new MockHttpServletRequest((String) c[0], (String) c[1]);
        req.setRemoteAddr("203.0.113.7");
        req.addHeader("Authorization", "Bearer " + jwt.issueAccess(1, role, 0, Clock.now()));
        MockHttpServletResponse res = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(c[2], method(c[2].getClass(), (String) c[3]));
        return interceptor.preHandle(req, res, handler) ? 200 : res.getStatus();
    }

    private static Method method(Class<?> type, String name) {
        Class<?> t = type;
        while (t != null) {
            for (Method m : t.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getAnnotation(AdminPermission.class) != null) {
                    return m;
                }
            }
            t = t.getSuperclass();
        }
        throw new IllegalArgumentException(name);
    }

    private static List<Method> handlers() {
        List<Method> out = new ArrayList<>();
        for (Class<?> c : CONTROLLERS) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(GetMapping.class) || m.isAnnotationPresent(PostMapping.class)
                        || m.isAnnotationPresent(PutMapping.class) || m.isAnnotationPresent(DeleteMapping.class)) {
                    out.add(m);
                }
            }
        }
        assertThat(out).hasSizeGreaterThan(40);
        return out;
    }
}
