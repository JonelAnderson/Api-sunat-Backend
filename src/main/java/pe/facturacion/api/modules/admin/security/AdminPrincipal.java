package pe.facturacion.api.modules.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import pe.facturacion.api.modules.admin.api.AdminException;

/** Administrador autenticado de la petición en curso (lo deja {@link AdminInterceptor}). */
public record AdminPrincipal(long userId, String email, String name, AdminRole role) {

    static final String ATTR = AdminPrincipal.class.getName();

    public static AdminPrincipal require(HttpServletRequest request) {
        if (request.getAttribute(ATTR) instanceof AdminPrincipal p) {
            return p;
        }
        throw AdminException.status(401, AdminMessages.UNAUTHENTICATED);
    }

    void bind(HttpServletRequest request) {
        request.setAttribute(ATTR, this);
    }
}
