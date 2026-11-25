package pe.facturacion.api.config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Panel de administración interno (prefijo {@code app.admin}). Todos los valores vienen de variables de entorno
 * ({@code ADMIN_JWT_SECRET}, {@code ADMIN_ALLOWED_ORIGINS}, {@code ADMIN_INITIAL_EMAIL}, {@code ADMIN_INITIAL_PASSWORD}).
 */
@ConfigurationProperties(prefix = "app.admin")
public record AdminProperties(String jwtSecret, String allowedOrigins, String initialEmail, String initialPassword) {

    /** Bytes mínimos de la clave HS256 (256 bits). */
    public static final int MIN_SECRET_BYTES = 32;

    /** El panel solo funciona con una clave de firma de al menos 32 bytes; si no, responde 503. */
    public boolean enabled() {
        return jwtSecret != null && jwtSecret.trim().getBytes(StandardCharsets.UTF_8).length >= MIN_SECRET_BYTES;
    }

    /** Clave de firma del JWT y del ticket MFA (los bytes UTF-8 de la variable, sin espacios alrededor). */
    public byte[] secretBytes() {
        return jwtSecret == null ? new byte[0] : jwtSecret.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** Orígenes del frontend del panel (coma). Nunca se acepta el comodín "*". */
    public List<String> origins() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return List.of();
        }
        return Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty() && !"*".equals(s)).toList();
    }
}
