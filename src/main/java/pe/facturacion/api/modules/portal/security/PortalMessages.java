package pe.facturacion.api.modules.portal.security;

/** Mensajes del portal que se repiten (en español). Ninguno revela si un correo o un token existe. */
public final class PortalMessages {

    public static final String PORTAL_DISABLED = "El portal de clientes está desactivado (falta PORTAL_JWT_SECRET).";
    public static final String UNAUTHENTICATED = "Inicia sesión para continuar.";
    public static final String SESSION_INVALID = "La sesión no es válida o venció. Inicia sesión de nuevo.";
    public static final String FORBIDDEN = "No tienes permiso para esta acción.";
    public static final String NOT_FOUND = "Recurso no encontrado.";
    public static final String TOO_MANY = "Demasiados intentos. Espera un minuto e inténtalo de nuevo.";
    /** No revela si el correo existe. */
    public static final String BAD_CREDENTIALS = "Correo o contraseña incorrectos.";
    public static final String LOCKED = "La cuenta está bloqueada por intentos fallidos. Inténtalo de nuevo en 15 minutos.";
    public static final String INACTIVE = "Tu acceso está deshabilitado. Comunícate con quien administra tu cuenta.";
    public static final String TICKET_INVALID = "El inicio de sesión venció. Ingresa de nuevo tu correo y contraseña.";
    public static final String BAD_CODE = "El código no es correcto.";
    public static final String CODE_REQUIRED = "Ingresa el código de tu aplicación autenticadora o un código de recuperación.";
    public static final String SESSION_EXPIRED = "La sesión venció. Inicia sesión de nuevo.";
    /** Un solo mensaje para un enlace inválido, usado, vencido o reemplazado: no dice cuál de los casos es. */
    public static final String INVITATION_INVALID = "El enlace de activación no es válido o venció. Pide que te envíen una invitación nueva.";
    /** El paso de seguridad de las credenciales no dice si falló la contraseña o el código. */
    public static final String STEP_UP_FAILED = "La contraseña o el código no son correctos.";

    private PortalMessages() {
    }
}
