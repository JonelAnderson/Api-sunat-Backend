package pe.facturacion.api.modules.portal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import pe.facturacion.api.modules.admin.portal.AdminPortalController;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.portal.security.PortalPublic;
import pe.facturacion.api.modules.portal.tenant.PortalDocumentsController;
import pe.facturacion.api.modules.portal.tenant.PortalTenantController;
import pe.facturacion.api.modules.portal.user.PortalAuthController;
import pe.facturacion.api.modules.portal.user.PortalUser;

/**
 * Reglas del portal que no deben romperse con un endpoint nuevo: solo lectura (salvo cuenta propia y regenerar
 * credenciales), toda ruta de empresa pasa por el aislamiento por membresía, los endpoints de la consola declaran su
 * permiso y ninguna respuesta del portal arma campos secretos.
 */
class PortalEndpointsRulesTest {

    record Route(String httpMethod, String path, Method method) {
    }

    static final List<Class<?>> PORTAL = List.of(PortalAuthController.class, PortalTenantController.class, PortalDocumentsController.class);

    private static List<Route> routes(Class<?> controller) {
        String base = controller.getAnnotation(RequestMapping.class).value()[0];
        List<Route> out = new ArrayList<>();
        for (Method m : controller.getDeclaredMethods()) {
            add(out, base, m, "GET", m.getAnnotation(GetMapping.class) == null ? null : m.getAnnotation(GetMapping.class).value());
            add(out, base, m, "POST", m.getAnnotation(PostMapping.class) == null ? null : m.getAnnotation(PostMapping.class).value());
            add(out, base, m, "PUT", m.getAnnotation(PutMapping.class) == null ? null : m.getAnnotation(PutMapping.class).value());
            add(out, base, m, "DELETE", m.getAnnotation(DeleteMapping.class) == null ? null : m.getAnnotation(DeleteMapping.class).value());
            add(out, base, m, "PATCH", m.getAnnotation(PatchMapping.class) == null ? null : m.getAnnotation(PatchMapping.class).value());
        }
        return out;
    }

    private static void add(List<Route> out, String base, Method m, String http, String[] paths) {
        if (paths != null) {
            out.add(new Route(http, base + (paths.length == 0 ? "" : paths[0]), m));
        }
    }

    @Test
    void el_portal_es_de_solo_lectura_salvo_la_cuenta_propia_y_regenerar_credenciales() {
        List<Route> all = new ArrayList<>();
        PORTAL.forEach(c -> all.addAll(routes(c)));
        assertThat(all).hasSizeGreaterThan(15);
        for (Route r : all) {
            assertThat(r.path()).startsWith("/api/portal/");
            assertThat(r.httpMethod()).as(r.path()).isIn("GET", "POST", "PUT");
            if (!"GET".equals(r.httpMethod())) {
                boolean own = r.path().startsWith("/api/portal/auth/");
                boolean regenerate = "POST".equals(r.httpMethod()) && r.path().equals("/api/portal/tenants/{id}/credenciales/regenerar");
                assertThat(own || regenerate).as("escritura permitida: %s %s", r.httpMethod(), r.path()).isTrue();
            }
        }
        // Y exactamente una escritura sobre empresas
        assertThat(all.stream().filter(r -> !"GET".equals(r.httpMethod()) && r.path().contains("/tenants/")).map(Route::path))
                .containsExactly("/api/portal/tenants/{id}/credenciales/regenerar");
    }

    @Test
    void solo_la_activacion_el_login_el_mfa_y_el_refresh_son_publicos() {
        List<String> publicPaths = new ArrayList<>();
        for (Class<?> c : PORTAL) {
            for (Route r : routes(c)) {
                if (r.method().isAnnotationPresent(PortalPublic.class)) {
                    publicPaths.add(r.path());
                }
            }
        }
        assertThat(publicPaths).containsExactlyInAnyOrder("/api/portal/auth/activate", "/api/portal/auth/login", "/api/portal/auth/login/mfa",
                "/api/portal/auth/login/mfa/setup", "/api/portal/auth/login/mfa/setup/confirm", "/api/portal/auth/refreshToken");
    }

    @Test
    void toda_ruta_de_empresa_recibe_el_id_y_pasa_por_el_aislamiento_por_membresia() throws IOException {
        for (Class<?> c : List.of(PortalTenantController.class, PortalDocumentsController.class)) {
            List<Route> routes = routes(c);
            assertThat(routes).isNotEmpty();
            for (Route r : routes) {
                boolean hasId = java.util.Arrays.stream(r.method().getParameters())
                        .anyMatch(p -> p.isAnnotationPresent(PathVariable.class) && p.getType() == long.class && p.getName().equals("id"));
                assertThat(hasId).as("%s recibe el id de la empresa", r.path()).isTrue();
            }
            // Cada método toma la empresa de PortalTenantAccess.require: una ruta nueva sin esa llamada rompe esta prueba
            String source = source(c);
            long requires = Pattern.compile("access\\.require\\(request, id\\)").matcher(source).results().count();
            assertThat(requires).as("%s: llamadas a access.require", c.getSimpleName()).isEqualTo(routes.size());
            // Nunca la empresa de un parámetro libre ni del cuerpo
            assertThat(source).doesNotContain("getParameter(\"tenantId\")").doesNotContain("\"tenantId\"").doesNotContain("views.tenant(");
        }
    }

    @Test
    void los_endpoints_de_la_consola_declaran_su_permiso() {
        List<Route> routes = routes(AdminPortalController.class);
        assertThat(routes.stream().map(r -> r.httpMethod() + " " + r.path())).containsExactlyInAnyOrder(
                "POST /api/admin/portal/invitations", "GET /api/admin/portal/users", "PUT /api/admin/portal/users/{id}/estado");
        for (Route r : routes) {
            AdminPermission p = r.method().getAnnotation(AdminPermission.class);
            assertThat(p).as(r.path()).isNotNull();
            assertThat(p.value()).as(r.path()).isEqualTo(AdminPermissions.PORTAL_MANAGE);
        }
    }

    @Test
    void la_fila_de_la_consola_nunca_lleva_hash_clave_del_mfa_ni_codigos() {
        PortalUser u = new PortalUser(1, "ana@cliente.pe", "Ana", "$2a$10$hash-secreto", true, 2, null, 7, "v1:clave-secreta", LocalDateTime.now(),
                "[\"codigo-hash\"]", LocalDateTime.now());
        // La fila de la consola se arma con campos elegidos uno por uno
        Map<String, Object> row = invokeRow(u);
        assertThat(row).containsOnlyKeys("id", "email", "name", "active", "activated", "mfaEnabled", "lastLoginAt", "tenantIds");
        assertThat(row.toString()).doesNotContain("hash-secreto").doesNotContain("clave-secreta").doesNotContain("codigo-hash");
        // El toString del usuario tampoco los imprime (logs)
        assertThat(u.toString()).doesNotContain("hash-secreto").doesNotContain("clave-secreta").doesNotContain("codigo-hash").doesNotContain("ana@cliente.pe");
    }

    @Test
    void ningun_controlador_del_portal_arma_campos_secretos_salvo_las_credenciales_nuevas_y_el_token_de_invitacion() throws IOException {
        Pattern put = Pattern.compile("(?:data|m|meta|resource)\\.put\\(\"([^\"]+)\"");
        Pattern secretish = Pattern.compile("(?i)(secret|sol_?pass|hash|token|two_?factor|recovery|password|certificate_?password|clave)");
        Map<Class<?>, Set<String>> allowed = Map.of(
                PortalTenantController.class, Set.of("apiKey", "apiSecret"),
                PortalDocumentsController.class, Set.of(),
                AdminPortalController.class, Set.of("token"));
        for (Map.Entry<Class<?>, Set<String>> e : allowed.entrySet()) {
            Matcher m = put.matcher(source(e.getKey()));
            while (m.find()) {
                String key = m.group(1);
                if (secretish.matcher(key).find()) {
                    assertThat(e.getValue()).as("%s arma el campo secreto '%s'", e.getKey().getSimpleName(), key).contains(key);
                }
            }
        }
        // La única respuesta con credenciales es la de regenerar y va sin caché
        String tenant = source(PortalTenantController.class);
        assertThat(tenant.indexOf("\"apiSecret\"")).isEqualTo(tenant.lastIndexOf("\"apiSecret\""));
        assertThat(tenant).contains("\"Cache-Control\", \"no-store\"");
        assertThat(source(AdminPortalController.class)).contains("\"Cache-Control\", \"no-store\"");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invokeRow(PortalUser u) {
        try {
            Method row = AdminPortalController.class.getDeclaredMethod("row", PortalUser.class, List.class);
            row.setAccessible(true);
            return (Map<String, Object>) row.invoke(null, u, List.of(5L));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fuente del controlador (Maven corre las pruebas desde la raíz del módulo). */
    private static String source(Class<?> c) throws IOException {
        Path file = Path.of("src/main/java", c.getName().replace('.', '/') + ".java");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
