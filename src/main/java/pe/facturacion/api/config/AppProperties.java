package pe.facturacion.api.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de la aplicación (prefijo {@code app} en application.yml).
 * Todos los valores provienen de variables de entorno.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String env,
        String url,
        String timezone,
        boolean debug,
        String encryptionKey,
        boolean seed,
        String storagePath,
        Security security,
        Mail mail,
        Demo demo,
        Sunat sunat,
        Registro registro,
        SuscripcionAutoservicio suscripcionAutoservicio) {

    /**
     * Registro público de empresas ({@code POST /api/v1/registro}, {@code REGISTRO_PUBLICO_ENABLED}). Apagado por defecto:
     * las empresas se dan de alta desde la consola de plataforma o el bridge SaaS.
     */
    public record Registro(boolean enabled) {
    }

    /**
     * Autoservicio de suscripción de la empresa con su propia API key (crear, cambiar de plan y cancelar,
     * {@code SUSCRIPCION_AUTOSERVICIO_ENABLED}). Apagado por defecto: el plan lo gestiona la consola de plataforma.
     */
    public record SuscripcionAutoservicio(boolean enabled) {
    }

    /** Registro público habilitado (ausente = apagado). */
    public boolean registroEnabled() {
        return registro != null && registro.enabled();
    }

    /** Autoservicio de suscripción habilitado (ausente = apagado). */
    public boolean suscripcionAutoservicioEnabled() {
        return suscripcionAutoservicio != null && suscripcionAutoservicio.enabled();
    }

    public record Mail(String fromAddress, String fromName) {
    }

    public record Security(
            String corsAllowedOrigins,
            String internalToken,
            String bridgeSecret,
            int tenantCacheTtl,
            int idempotencyTtlDays,
            int apiLogsRetentionDays) {

        /** Orígenes CORS: lista separada por comas; por defecto solo APP_URL (SEC-017). */
        public List<String> corsOrigins(String appUrl) {
            String raw = corsAllowedOrigins == null || corsAllowedOrigins.isBlank() ? appUrl : corsAllowedOrigins;
            return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }

    public record Demo(String apiKey, String apiSecret) {
    }

    public record Sunat(int httpTimeout, int perTenantPerMinute, Endpoints beta, Endpoints production) {

        /** Endpoints del entorno de la empresa: {@code beta} o {@code production}. */
        public Endpoints forEnvironment(String environment) {
            return "production".equals(environment) ? production : beta;
        }
    }

    public record Endpoints(
            String fe,
            String retention,
            String consultaCdr,
            String greAuth,
            String greCpe,
            String greClientId,
            String greClientSecret) {
    }

    public boolean isProduction() {
        return "production".equalsIgnoreCase(env);
    }
}
