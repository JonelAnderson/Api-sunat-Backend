package pe.facturacion.api.modules.portal.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.portal.security.PortalMessages;
import pe.facturacion.api.modules.portal.security.PortalPrincipal;
import pe.facturacion.api.modules.portal.user.PortalUserStore;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.tenant.domain.TenantRepository;

/**
 * Aislamiento entre empresas del portal: toda ruta {@code tenants/{id}/**} resuelve la empresa **solo** desde las
 * empresas vinculadas a la persona autenticada ({@code portal_user_tenants}). Si {@code {id}} no es suyo, no existe o
 * está eliminada, la respuesta es la misma 404 genérica: no revela si la empresa existe. La empresa nunca se toma de un
 * parámetro libre del pedido.
 */
@Component
public class PortalTenantAccess {

    private final PortalUserStore users;
    private final TenantRepository tenants;

    public PortalTenantAccess(PortalUserStore users, TenantRepository tenants) {
        this.users = users;
        this.tenants = tenants;
    }

    /** La empresa de la ruta si es de la persona autenticada; si no, 404. */
    public Tenant require(HttpServletRequest request, long tenantId) {
        PortalPrincipal me = PortalPrincipal.require(request);
        if (!users.hasTenant(me.userId(), tenantId)) {
            throw notFound();
        }
        return tenants.findById(tenantId).orElseThrow(PortalTenantAccess::notFound);
    }

    static AdminException notFound() {
        return AdminException.status(404, PortalMessages.NOT_FOUND);
    }
}
