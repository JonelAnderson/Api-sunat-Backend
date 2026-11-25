package pe.facturacion.api.modules.admin.security;

import static pe.facturacion.api.modules.admin.security.AdminPermissions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Roles del panel ({@code users.role}) y sus permisos (matriz completa en el §10 del diseño):
 * <ul>
 *   <li>{@code superadmin}: todo, también administradores, auditoría, configuración global, eliminar planes y eliminar
 *       empresas;</li>
 *   <li>{@code operador}: todo menos administradores, auditoría, configuración global y eliminar planes; tiene todos
 *       los permisos de las fases C y D;</li>
 *   <li>{@code soporte}: todas las consultas (no la auditoría) y las consultas en vivo a SUNAT; de las fases C y D,
 *       solo reenviar, anular y refrescar tickets (como el {@code soporte} de la referencia);</li>
 *   <li>{@code lectura}: solo consultas guardadas (nunca una escritura ni una consulta en vivo a SUNAT).</li>
 * </ul>
 */
public enum AdminRole {

    SUPERADMIN("superadmin", "Superadministrador", Set.copyOf(ALL)),
    OPERADOR("operador", "Operador", Set.of(
            DASHBOARD_VIEW, TENANTS_VIEW, TENANTS_MANAGE, USAGE_VIEW, CERTIFICATES_VIEW, REJECTIONS_VIEW, JOBS_VIEW, JOBS_RETRY,
            HEALTH_VIEW, PLANS_VIEW, PLANS_MANAGE, SETTINGS_VIEW, SUNAT_QUERY,
            TENANTS_EDIT, TENANTS_CREDENTIALS, TENANTS_CREDENTIALS_ROTATE, DOCUMENTS_ISSUE, DOCUMENTS_RESEND, DOCUMENTS_VOID,
            DOCUMENTS_REFRESH)),
    SOPORTE("soporte", "Soporte", Set.of(
            DASHBOARD_VIEW, TENANTS_VIEW, USAGE_VIEW, CERTIFICATES_VIEW, REJECTIONS_VIEW, JOBS_VIEW, HEALTH_VIEW, PLANS_VIEW,
            SETTINGS_VIEW, SUNAT_QUERY,
            DOCUMENTS_RESEND, DOCUMENTS_VOID, DOCUMENTS_REFRESH)),
    LECTURA("lectura", "Solo lectura", Set.of(
            DASHBOARD_VIEW, TENANTS_VIEW, USAGE_VIEW, CERTIFICATES_VIEW, REJECTIONS_VIEW, JOBS_VIEW, HEALTH_VIEW, PLANS_VIEW,
            SETTINGS_VIEW)),
    /**
     * La consola de plataforma ({@code X-Admin-Key}, ver {@link AdminInterceptor}). No es un rol de usuario: no está en
     * {@link #codes()} ni en {@link #fromCode(String)}, así que nadie puede asignarlo ni iniciar sesión con él. Ve
     * empresas, uso, certificados, rechazos, trabajos y salud; solo escribe estado y plan de una empresa y reintenta
     * trabajos, y administra las invitaciones y los accesos del portal de clientes ({@link AdminPermissions#PORTAL_MANAGE}).
     * Con {@link AdminPermissions#TENANTS_CREDENTIALS_ROTATE} regenera la api_key / api_secret de una empresa para entregarlas al ERP
     * (nunca ve las vigentes). Nunca la clave SOL, el certificado ni las credenciales GRE, ni comprobantes, usuarios del panel,
     * auditoría ni configuración global.
     */
    SERVICIO("servicio", "Consola de plataforma", Set.of(
            TENANTS_VIEW, TENANTS_MANAGE, USAGE_VIEW, CERTIFICATES_VIEW, REJECTIONS_VIEW, JOBS_VIEW, JOBS_RETRY, HEALTH_VIEW,
            PLANS_VIEW, PORTAL_MANAGE, TENANTS_CREDENTIALS_ROTATE));

    private final String code;
    private final String label;
    private final Set<String> permissions;

    AdminRole(String code, String label, Set<String> permissions) {
        this.code = code;
        this.label = label;
        this.permissions = permissions;
    }

    /** Valor guardado en {@code users.role}. */
    public String code() {
        return code;
    }

    /** Nombre para mostrar. */
    public String label() {
        return label;
    }

    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    /** Permisos en el orden del catálogo (lo que devuelve {@code GET auth/permissions}). */
    public List<String> permissions() {
        return AdminPermissions.ALL.stream().filter(permissions::contains).toList();
    }

    /**
     * Solo el rol de lectura tiene prohibida toda escritura en el panel. El de soporte puede escribir únicamente donde
     * el endpoint pide un permiso que tiene (fase D: reenviar, anular y refrescar comprobantes).
     */
    public boolean canWrite() {
        return this != LECTURA;
    }

    public static Optional<AdminRole> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(r -> r != SERVICIO && r.code.equals(code)).findFirst();
    }

    /** Roles que se pueden asignar a un usuario del panel (sin el de la consola). */
    public static List<String> codes() {
        return Arrays.stream(values()).filter(r -> r != SERVICIO).map(AdminRole::code).toList();
    }
}
