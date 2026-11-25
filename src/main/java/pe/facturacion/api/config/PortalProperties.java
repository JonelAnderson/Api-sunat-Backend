package pe.facturacion.api.config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Portal de clientes (prefijo {@code app.portal}, diseño {@code Docs/api-sunat-portal-clientes-diseno.md}). Los valores
 * vienen de variables de entorno ({@code PORTAL_JWT_SECRET}, {@code PORTAL_ALLOWED_ORIGINS}), nunca de un archivo.
 *
 * <p>La clave del portal es <b>distinta</b> de {@code ADMIN_JWT_SECRET}: aun así cada JWT lleva su propio emisor y
 * audiencia, así que un token de un mundo nunca sirve en el otro.
 */
@ConfigurationProperties(prefix = "app.portal")
public record PortalProperties(String jwtSecret, String allowedOrigins) {

    /** Bytes mínimos de la clave HS256 (256 bits). */
    public static final int MIN_SECRET_BYTES = 32;

    /** El portal solo funciona con una clave de firma de al menos 32 bytes; si no, responde 503. */
    public boolean enabled() {
        return jwtSecret != null && jwtSecret.trim().getBytes(StandardCharsets.UTF_8).length >= MIN_SECRET_BYTES;
    }

    /** Clave de firma del JWT y del ticket MFA (los bytes UTF-8 de la variable, sin espacios alrededor). */
    public byte[] secretBytes() {
        return jwtSecret == null ? new byte[0] : jwtSecret.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** Orígenes del frontend del portal (coma). Nunca se acepta el comodín "*". */
    public List<String> origins() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return List.of();
        }
        return Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty() && !"*".equals(s)).toList();
    }
}
