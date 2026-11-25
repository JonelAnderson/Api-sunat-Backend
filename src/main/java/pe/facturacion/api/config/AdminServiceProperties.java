package pe.facturacion.api.config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Acceso de servicio de la consola de plataforma ({@code Admin-saas-Backend}) a {@code /api/admin/**} (prefijo
 * {@code app.admin.service}, diseño {@code Docs/plataforma-consola-arquitectura.md} §5.3). Los valores vienen de
 * variables de entorno ({@code ADMIN_SERVICE_KEY}, {@code ADMIN_SERVICE_ALLOWED_IPS}), nunca de un archivo.
 *
 * <p>Sin clave de al menos 32 bytes el acceso de servicio queda apagado (503). Sin IP permitidas también se rechaza todo:
 * una clave sola no basta.
 */
@ConfigurationProperties(prefix = "app.admin.service")
public record AdminServiceProperties(String key, String allowedIps) {

    /** Bytes mínimos de la clave (256 bits), igual que el secreto del JWT del panel. */
    public static final int MIN_KEY_BYTES = 32;

    public boolean enabled() {
        return key != null && key.trim().getBytes(StandardCharsets.UTF_8).length >= MIN_KEY_BYTES;
    }

    /** La clave sin espacios alrededor, o vacía si no está configurada. */
    public String keyValue() {
        return key == null ? "" : key.trim();
    }

    /** IP o redes CIDR permitidas (coma). Nunca se acepta un comodín. */
    public List<String> ips() {
        if (allowedIps == null || allowedIps.isBlank()) {
            return List.of();
        }
        return Arrays.stream(allowedIps.split(",")).map(String::trim).filter(s -> !s.isEmpty() && !"*".equals(s)).toList();
    }
}
