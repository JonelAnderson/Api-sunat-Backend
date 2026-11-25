package pe.facturacion.api.modules.admin.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Menú del panel, fijo en el código (sin tabla de menús), con la forma {@code MenuNavApiItem} del frontend:
 * {@code id, title, type (group / collapse / item), icon, url, order, children}. Cada opción lleva el permiso que la
 * muestra; un grupo sin opciones visibles no se devuelve.
 */
public final class AdminMenu {

    /** Opción del menú; {@code permission} null = visible para todos. */
    public record Entry(int id, String title, String type, String icon, String url, String permission, List<Entry> children) {
    }

    public static final List<Entry> TREE = List.of(
            group(1, "Principal",
                    item(101, "Inicio", "feather icon-home", "/private/home", AdminPermissions.DASHBOARD_VIEW)),
            group(2, "Emisores",
                    item(201, "Empresas", "feather icon-briefcase", "/private/sunat/tenants", AdminPermissions.TENANTS_VIEW),
                    item(203, "Comprobantes", "feather icon-file", "/private/sunat/documents", AdminPermissions.TENANTS_VIEW),
                    item(204, "Sucursales", "feather icon-map-pin", "/private/sunat/branches", AdminPermissions.TENANTS_VIEW),
                    item(205, "Series", "feather icon-hash", "/private/sunat/series", AdminPermissions.TENANTS_VIEW),
                    item(202, "Certificados", "feather icon-award", "/private/sunat/certificates", AdminPermissions.CERTIFICATES_VIEW)),
            group(3, "Operación",
                    item(305, "Envíos SUNAT", "feather icon-send", "/private/sunat/submissions", AdminPermissions.REJECTIONS_VIEW),
                    item(301, "Uso del mes", "feather icon-bar-chart-2", "/private/sunat/usage", AdminPermissions.USAGE_VIEW),
                    item(302, "Rechazos SUNAT", "feather icon-alert-triangle", "/private/sunat/rejections", AdminPermissions.REJECTIONS_VIEW),
                    item(306, "Consulta CPE", "feather icon-search", "/private/sunat/cpe-query", AdminPermissions.SUNAT_QUERY),
                    item(303, "Trabajos fallidos", "feather icon-refresh-cw", "/private/sunat/jobs", AdminPermissions.JOBS_VIEW),
                    item(304, "Salud", "feather icon-activity", "/private/sunat/health", AdminPermissions.HEALTH_VIEW)),
            group(6, "Plataforma",
                    item(601, "Planes", "feather icon-layers", "/private/platform/plans", AdminPermissions.PLANS_VIEW),
                    item(602, "Configuración de emisión", "feather icon-sliders", "/private/platform/settings", AdminPermissions.SETTINGS_VIEW)),
            group(4, "Administración",
                    item(401, "Administradores", "feather icon-users", "/private/admin/users", AdminPermissions.USERS_MANAGE),
                    item(402, "Auditoría", "feather icon-file-text", "/private/admin/audit", AdminPermissions.AUDIT_VIEW)),
            group(5, "Soporte",
                    item(501, "Ayuda", "feather icon-help-circle", "/private/help", null)));

    private AdminMenu() {
    }

    /** Árbol filtrado por los permisos del rol, listo para serializar. */
    public static List<Map<String, Object>> forRole(AdminRole role) {
        return filter(TREE, role);
    }

    private static List<Map<String, Object>> filter(List<Entry> entries, AdminRole role) {
        List<Map<String, Object>> out = new ArrayList<>();
        int order = 1;
        for (Entry e : entries) {
            if (e.permission() != null && !role.has(e.permission())) {
                continue;
            }
            List<Map<String, Object>> children = filter(e.children(), role);
            if (!"item".equals(e.type()) && children.isEmpty()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("title", e.title());
            m.put("type", e.type());
            m.put("icon", e.icon());
            m.put("url", e.url());
            m.put("order", order++);
            m.put("children", children);
            out.add(m);
        }
        return out;
    }

    private static Entry group(int id, String title, Entry... items) {
        return new Entry(id, title, "group", null, null, null, List.of(items));
    }

    private static Entry item(int id, String title, String icon, String url, String permission) {
        return new Entry(id, title, "item", icon, url, permission, List.of());
    }
}
