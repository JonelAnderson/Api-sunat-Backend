package pe.facturacion.api.modules.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Matriz rol → permisos y menú filtrado por permisos. */
class AdminRoleMenuTest {

    @Test
    void superadmin_tiene_todos_los_permisos() {
        assertThat(AdminRole.SUPERADMIN.permissions()).containsExactlyElementsOf(AdminPermissions.ALL);
        assertThat(AdminRole.SUPERADMIN.canWrite()).isTrue();
    }

    @Test
    void operador_todo_menos_administradores_auditoria_configuracion_y_eliminar_planes() {
        assertThat(AdminRole.OPERADOR.permissions()).containsExactly(
                "Admin.Dashboard.View", "Admin.Tenants.View", "Admin.Tenants.Manage", "Admin.Usage.View", "Admin.Certificates.View",
                "Admin.Rejections.View", "Admin.Jobs.View", "Admin.Jobs.Retry", "Admin.Health.View", "Admin.Plans.View",
                "Admin.Plans.Manage", "Admin.Settings.View", "Admin.Sunat.Query", "Admin.Tenants.Edit", "Admin.Tenants.Credentials",
                "Admin.Tenants.Credentials.Rotate", "Admin.Documents.Issue", "Admin.Documents.Resend", "Admin.Documents.Void", "Admin.Documents.Refresh");
        assertThat(AdminRole.OPERADOR.has(AdminPermissions.SETTINGS_MANAGE)).isFalse();
        assertThat(AdminRole.OPERADOR.has(AdminPermissions.PLANS_DELETE)).isFalse();
        assertThat(AdminRole.OPERADOR.has(AdminPermissions.USERS_MANAGE)).isFalse();
        assertThat(AdminRole.OPERADOR.has(AdminPermissions.AUDIT_VIEW)).isFalse();
        assertThat(AdminRole.OPERADOR.canWrite()).isTrue();
    }

    @Test
    void lectura_solo_permisos_de_consulta() {
        assertThat(AdminRole.LECTURA.permissions()).allMatch(p -> p.endsWith(".View"));
        assertThat(AdminRole.LECTURA.has(AdminPermissions.TENANTS_MANAGE)).isFalse();
        assertThat(AdminRole.LECTURA.has(AdminPermissions.JOBS_RETRY)).isFalse();
        assertThat(AdminRole.LECTURA.has(AdminPermissions.AUDIT_VIEW)).isFalse();
        assertThat(AdminRole.LECTURA.has(AdminPermissions.USERS_MANAGE)).isFalse();
        assertThat(AdminRole.LECTURA.has(AdminPermissions.SUNAT_QUERY)).isFalse();
        assertThat(AdminRole.LECTURA.permissions()).doesNotContainAnyElementsOf(AdminPermissions.FUTURE);
        assertThat(AdminRole.LECTURA.canWrite()).isFalse();
    }

    @Test
    void soporte_lee_todo_consulta_sunat_y_en_la_fase_d_solo_reenvia_anula_y_refresca() {
        assertThat(AdminRole.SOPORTE.permissions()).containsExactly(
                "Admin.Dashboard.View", "Admin.Tenants.View", "Admin.Usage.View", "Admin.Certificates.View", "Admin.Rejections.View",
                "Admin.Jobs.View", "Admin.Health.View", "Admin.Plans.View", "Admin.Settings.View", "Admin.Sunat.Query",
                "Admin.Documents.Resend", "Admin.Documents.Void", "Admin.Documents.Refresh");
        // Lee todo lo que lee "lectura"
        assertThat(AdminRole.SOPORTE.permissions()).containsAll(AdminRole.LECTURA.permissions());
        // Nunca escribe datos de la plataforma ni de la empresa
        for (String p : List.of(AdminPermissions.TENANTS_MANAGE, AdminPermissions.JOBS_RETRY, AdminPermissions.PLANS_MANAGE,
                AdminPermissions.PLANS_DELETE, AdminPermissions.SETTINGS_MANAGE, AdminPermissions.USERS_MANAGE, AdminPermissions.AUDIT_VIEW,
                AdminPermissions.TENANTS_EDIT, AdminPermissions.TENANTS_CREDENTIALS, AdminPermissions.DOCUMENTS_ISSUE)) {
            assertThat(AdminRole.SOPORTE.has(p)).as(p).isFalse();
        }
        assertThat(AdminRole.SOPORTE.canWrite()).isTrue();
        assertThat(AdminRole.SOPORTE.label()).isEqualTo("Soporte");
    }

    @Test
    void matriz_de_las_fases_c_y_d() {
        assertThat(AdminPermissions.FUTURE).containsExactly("Admin.Tenants.Edit", "Admin.Tenants.Credentials", "Admin.Documents.Issue",
                "Admin.Documents.Resend", "Admin.Documents.Void", "Admin.Documents.Refresh");
        for (String p : AdminPermissions.FUTURE) {
            assertThat(AdminRole.SUPERADMIN.has(p)).as(p).isTrue();
            assertThat(AdminRole.OPERADOR.has(p)).as(p).isTrue();
            assertThat(AdminRole.LECTURA.has(p)).as(p).isFalse();
        }
        assertThat(AdminPermissions.FUTURE.stream().filter(AdminRole.SOPORTE::has).toList())
                .containsExactly("Admin.Documents.Resend", "Admin.Documents.Void", "Admin.Documents.Refresh");
        assertThat(AdminPermissions.ALL).doesNotHaveDuplicates().containsAll(AdminPermissions.FUTURE);
    }

    @Test
    void codigos_de_rol_de_la_columna() {
        assertThat(AdminRole.codes()).containsExactly("superadmin", "operador", "soporte", "lectura");
        assertThat(AdminRole.fromCode("soporte")).contains(AdminRole.SOPORTE);
        assertThat(AdminRole.fromCode("operador")).contains(AdminRole.OPERADOR);
        assertThat(AdminRole.fromCode("super_admin")).isEmpty();
        assertThat(AdminRole.fromCode("SUPERADMIN")).isEmpty();
        assertThat(AdminRole.fromCode(null)).isEmpty();
        assertThat(AdminRole.SUPERADMIN.label()).isEqualTo("Superadministrador");
    }

    @Test
    void menu_completo_del_superadmin() {
        List<Map<String, Object>> menu = AdminMenu.forRole(AdminRole.SUPERADMIN);
        assertThat(menu).extracting(m -> m.get("title"))
                .containsExactly("Principal", "Emisores", "Operación", "Plataforma", "Administración", "Soporte");
        assertThat(menu).allMatch(m -> "group".equals(m.get("type")));
        assertThat(urls(menu)).containsExactly("/private/home", "/private/sunat/tenants", "/private/sunat/documents",
                "/private/sunat/branches", "/private/sunat/series", "/private/sunat/certificates", "/private/sunat/submissions",
                "/private/sunat/usage", "/private/sunat/rejections", "/private/sunat/cpe-query", "/private/sunat/jobs",
                "/private/sunat/health", "/private/platform/plans", "/private/platform/settings", "/private/admin/users",
                "/private/admin/audit", "/private/help");
    }

    @Test
    void operador_soporte_y_lectura_no_ven_administracion() {
        for (AdminRole role : List.of(AdminRole.OPERADOR, AdminRole.SOPORTE, AdminRole.LECTURA)) {
            List<Map<String, Object>> menu = AdminMenu.forRole(role);
            assertThat(menu).extracting(m -> m.get("title")).as(role.code())
                    .containsExactly("Principal", "Emisores", "Operación", "Plataforma", "Soporte");
            assertThat(urls(menu)).as(role.code()).doesNotContain("/private/admin/users", "/private/admin/audit")
                    .contains("/private/help", "/private/platform/plans", "/private/platform/settings", "/private/sunat/submissions");
        }
    }

    @Test
    void la_consulta_cpe_solo_para_quien_consulta_sunat() {
        assertThat(urls(AdminMenu.forRole(AdminRole.SOPORTE))).contains("/private/sunat/cpe-query");
        assertThat(urls(AdminMenu.forRole(AdminRole.OPERADOR))).contains("/private/sunat/cpe-query");
        assertThat(urls(AdminMenu.forRole(AdminRole.LECTURA))).doesNotContain("/private/sunat/cpe-query");
    }

    @Test
    void cada_opcion_tiene_la_forma_menu_nav_api_item() {
        Map<String, Object> group = AdminMenu.forRole(AdminRole.SUPERADMIN).get(1);
        assertThat(group.keySet()).containsExactly("id", "title", "type", "icon", "url", "order", "children");
        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) group.get("children")).get(0);
        assertThat(item).containsEntry("type", "item").containsEntry("title", "Empresas").containsEntry("id", 201);
        assertThat(item.get("icon")).isEqualTo("feather icon-briefcase");
    }

    @SuppressWarnings("unchecked")
    private static List<String> urls(List<Map<String, Object>> menu) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> m : menu) {
            if (m.get("url") != null) {
                out.add((String) m.get("url"));
            }
            out.addAll(urls((List<Map<String, Object>>) m.get("children")));
        }
        return out;
    }
}
