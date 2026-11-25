package pe.facturacion.api.modules.admin.security;

import java.util.List;

/**
 * Catálogo de permisos del panel (los que devuelve {@code GET /api/admin/auth/permissions}). La matriz rol × permiso
 * vive en {@link AdminRole} y se documenta en el §10 de {@code Docs/api-sunat-panel-admin-diseno.md}.
 *
 * <p>Fases C y D (escrituras sobre los datos de una empresa y acciones ante SUNAT en su nombre, §11 del diseño): sus
 * permisos se definieron en la fase B y ahora los usan sus endpoints; la fase C agrega {@link #TENANTS_DELETE} (solo
 * superadmin).
 */
public final class AdminPermissions {

    public static final String DASHBOARD_VIEW = "Admin.Dashboard.View";
    public static final String TENANTS_VIEW = "Admin.Tenants.View";
    public static final String TENANTS_MANAGE = "Admin.Tenants.Manage";
    public static final String USAGE_VIEW = "Admin.Usage.View";
    public static final String CERTIFICATES_VIEW = "Admin.Certificates.View";
    /** Rechazos y el feed de envíos SUNAT de todas las empresas. */
    public static final String REJECTIONS_VIEW = "Admin.Rejections.View";
    public static final String JOBS_VIEW = "Admin.Jobs.View";
    public static final String JOBS_RETRY = "Admin.Jobs.Retry";
    public static final String HEALTH_VIEW = "Admin.Health.View";
    /** Catálogo de planes (lectura). */
    public static final String PLANS_VIEW = "Admin.Plans.View";
    /** Crear, editar y activar / desactivar planes (operador o superior). */
    public static final String PLANS_MANAGE = "Admin.Plans.Manage";
    /** Eliminar un plan sin suscripciones (solo superadmin, como el DELETE de la referencia). */
    public static final String PLANS_DELETE = "Admin.Plans.Delete";
    /** Configuración global de emisión y token de consulta RUC / DNI (lectura: nunca devuelve el token). */
    public static final String SETTINGS_VIEW = "Admin.Settings.View";
    /** Cambiar la configuración global (solo superadmin). */
    public static final String SETTINGS_MANAGE = "Admin.Settings.Manage";
    /** Consultas en vivo a SUNAT con las credenciales guardadas de la empresa (consulta CPE y CDR); no escriben. */
    public static final String SUNAT_QUERY = "Admin.Sunat.Query";
    /** Solo superadmin. */
    public static final String USERS_MANAGE = "Admin.Users.Manage";
    /** Solo superadmin. */
    public static final String AUDIT_VIEW = "Admin.Audit.View";

    // ------------------------------------------------------------------ fases C y D (§11 del diseño)

    /** Fase C: editar los datos de una empresa (alta, edición, sucursales, series, clientes, configuración). */
    public static final String TENANTS_EDIT = "Admin.Tenants.Edit";
    /** Fase C: credenciales de una empresa (SOL, certificado, client_secret, regenerar api_key / api_secret). */
    public static final String TENANTS_CREDENTIALS = "Admin.Tenants.Credentials";
    /**
     * Entrega de credenciales al ERP: solo regenerar la api_key / api_secret de una empresa ({@code credenciales/regenerar}). Es más
     * angosto que {@link #TENANTS_CREDENTIALS}: no permite tocar la clave SOL, el certificado ni las credenciales GRE. La consola de
     * plataforma tiene este y no aquel.
     */
    public static final String TENANTS_CREDENTIALS_ROTATE = "Admin.Tenants.Credentials.Rotate";
    /**
     * Fase C: eliminar una empresa (borrado lógico, con motivo y confirmando el RUC). Solo superadmin, como el DELETE de
     * la referencia.
     */
    public static final String TENANTS_DELETE = "Admin.Tenants.Delete";
    /** Fase D: emitir y editar comprobantes rechazados en nombre de la empresa. */
    public static final String DOCUMENTS_ISSUE = "Admin.Documents.Issue";
    /** Fase D: reenviar o enviar a SUNAT. */
    public static final String DOCUMENTS_RESEND = "Admin.Documents.Resend";
    /** Fase D: comunicación de baja, resumen de anulación y reversión. */
    public static final String DOCUMENTS_VOID = "Admin.Documents.Void";
    /** Fase D: consultar el ticket en SUNAT y guardar el resultado (resúmenes, bajas, guías). */
    public static final String DOCUMENTS_REFRESH = "Admin.Documents.Refresh";

    // ------------------------------------------------------------------ portal de clientes (C6)

    /**
     * Invitar, reinvitar, listar y habilitar / deshabilitar a las personas del portal de clientes
     * ({@code /api/admin/portal/**}). Solo superadmin y la consola de plataforma: la invitación entrega un token que
     * da acceso a las empresas elegidas.
     */
    public static final String PORTAL_MANAGE = "Admin.Portal.Manage";

    /** Permisos de las fases C y D que ya existían antes de sus endpoints (el panel los conoce desde la fase B). */
    public static final List<String> FUTURE = List.of(TENANTS_EDIT, TENANTS_CREDENTIALS, DOCUMENTS_ISSUE, DOCUMENTS_RESEND,
            DOCUMENTS_VOID, DOCUMENTS_REFRESH);

    public static final List<String> ALL = List.of(DASHBOARD_VIEW, TENANTS_VIEW, TENANTS_MANAGE, USAGE_VIEW, CERTIFICATES_VIEW,
            REJECTIONS_VIEW, JOBS_VIEW, JOBS_RETRY, HEALTH_VIEW, PLANS_VIEW, PLANS_MANAGE, PLANS_DELETE, SETTINGS_VIEW,
            SETTINGS_MANAGE, SUNAT_QUERY, USERS_MANAGE, AUDIT_VIEW, TENANTS_EDIT, TENANTS_CREDENTIALS, TENANTS_CREDENTIALS_ROTATE, TENANTS_DELETE, DOCUMENTS_ISSUE,
            DOCUMENTS_RESEND, DOCUMENTS_VOID, DOCUMENTS_REFRESH, PORTAL_MANAGE);

    private AdminPermissions() {
    }
}
