package pe.facturacion.api.modules.portal.security;

import jakarta.servlet.http.HttpServletRequest;
import pe.facturacion.api.modules.admin.api.AdminException;

/** Persona del portal autenticada en la petición en curso (la deja {@link PortalInterceptor}). */
public record PortalPrincipal(long userId, String email, String name) {

    static final String ATTR = PortalPrincipal.class.getName();

    public static PortalPrincipal require(HttpServletRequest request) {
        if (request.getAttribute(ATTR) instanceof PortalPrincipal p) {
            return p;
        }
        throw AdminException.status(401, PortalMessages.UNAUTHENTICATED);
    }

    void bind(HttpServletRequest request) {
        request.setAttribute(ATTR, this);
    }
}
