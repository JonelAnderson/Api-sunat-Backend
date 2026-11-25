package pe.facturacion.api.modules.admin.security;

/** Mensajes del panel que se repiten (en español, como el resto de la API). */
public final class AdminMessages {

    public static final String PANEL_DISABLED = "El panel de administración está desactivado (falta ADMIN_JWT_SECRET).";
    public static final String SERVICE_DISABLED = "El acceso de la consola de plataforma está desactivado (falta ADMIN_SERVICE_KEY).";
    public static final String SERVICE_ACTOR_REQUIRED = "Falta la cabecera X-Admin-Actor con el usuario de la consola que hace la acción.";
    public static final String UNAUTHENTICATED = "Inicia sesión para continuar.";
    public static final String SESSION_INVALID = "La sesión no es válida o venció. Inicia sesión de nuevo.";
    public static final String FORBIDDEN = "No tienes permiso para esta acción.";
    public static final String TOO_MANY = "Demasiados intentos. Espera un minuto e inténtalo de nuevo.";
    /** No revela si el correo existe. */
    public static final String BAD_CREDENTIALS = "Correo o contraseña incorrectos.";
    public static final String LOCKED = "La cuenta está bloqueada por intentos fallidos. Inténtalo de nuevo en 15 minutos.";
    public static final String INACTIVE = "El usuario está desactivado. Pide a un superadministrador que lo active.";
    public static final String TICKET_INVALID = "El inicio de sesión venció. Ingresa de nuevo tu correo y contraseña.";
    public static final String BAD_CODE = "El código no es correcto.";
    public static final String CODE_REQUIRED = "Ingresa el código de tu aplicación autenticadora o un código de recuperación.";
    public static final String SESSION_EXPIRED = "La sesión venció. Inicia sesión de nuevo.";

    private AdminMessages() {
    }
}
