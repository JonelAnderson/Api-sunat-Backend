package pe.facturacion.api.modules.portal.security;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pe.facturacion.api.config.AdminProperties;
import pe.facturacion.api.config.PortalProperties;
import pe.facturacion.api.modules.admin.api.AdminException;

/**
 * Firmador del portal. Sin {@code PORTAL_JWT_SECRET} (o con menos de 32 bytes, o igual a {@code ADMIN_JWT_SECRET}) el
 * portal queda apagado y todo {@code /api/portal/**} responde 503, como el panel sin su clave.
 */
@Component
public class PortalTokens {

    private static final Logger log = LoggerFactory.getLogger(PortalTokens.class);

    private final PortalJwt jwt;

    public PortalTokens(PortalProperties props, AdminProperties admin) {
        if (props.enabled() && Arrays.equals(props.secretBytes(), admin.secretBytes())) {
            this.jwt = null;
            log.warn("PORTAL_JWT_SECRET es igual a ADMIN_JWT_SECRET: el portal de clientes queda desactivado (deben ser claves distintas)");
        } else if (props.enabled()) {
            this.jwt = new PortalJwt(props.secretBytes());
        } else {
            this.jwt = null;
            if (props.jwtSecret() != null && !props.jwtSecret().isBlank()) {
                log.warn("PORTAL_JWT_SECRET tiene menos de {} bytes: el portal de clientes queda desactivado", PortalProperties.MIN_SECRET_BYTES);
            } else {
                log.info("Portal de clientes desactivado (sin PORTAL_JWT_SECRET)");
            }
        }
    }

    public boolean enabled() {
        return jwt != null;
    }

    public PortalJwt jwt() {
        if (jwt == null) {
            throw AdminException.status(503, PortalMessages.PORTAL_DISABLED);
        }
        return jwt;
    }
}
