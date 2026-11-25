package pe.facturacion.api.modules.admin.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pe.facturacion.api.config.AdminProperties;
import pe.facturacion.api.modules.admin.api.AdminException;

/**
 * Firmador del panel. Sin {@code ADMIN_JWT_SECRET} (o con menos de 32 bytes) el panel queda apagado y todo
 * {@code /api/admin/**} responde 503, como el bridge sin su clave.
 */
@Component
public class AdminTokens {

    private static final Logger log = LoggerFactory.getLogger(AdminTokens.class);

    private final AdminJwt jwt;

    public AdminTokens(AdminProperties props) {
        if (props.enabled()) {
            this.jwt = new AdminJwt(props.secretBytes());
        } else {
            this.jwt = null;
            if (props.jwtSecret() != null && !props.jwtSecret().isBlank()) {
                log.warn("ADMIN_JWT_SECRET tiene menos de {} bytes: el panel de administración queda desactivado", AdminProperties.MIN_SECRET_BYTES);
            } else {
                log.info("Panel de administración desactivado (sin ADMIN_JWT_SECRET)");
            }
        }
    }

    public boolean enabled() {
        return jwt != null;
    }

    public AdminJwt jwt() {
        if (jwt == null) {
            throw AdminException.status(503, AdminMessages.PANEL_DISABLED);
        }
        return jwt;
    }
}
