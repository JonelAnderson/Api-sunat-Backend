package pe.facturacion.api.modules.client;

import java.util.Map;
import org.springframework.stereotype.Service;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.php.Php;

/**
 * {@code ClientResolverService}: {@code updateOrCreate} del cliente por (empresa, tipo y número
 * de documento). Razón social y dirección siempre se actualizan; email y teléfono solo si vienen.
 */
@Service
public class ClientResolver {

    private final ClientRepository clients;

    public ClientResolver(ClientRepository clients) {
        this.clients = clients;
    }

    public Client resolve(Tenant tenant, Map<String, Object> data) {
        String tipo = Php.toStr(data.get("tipo_doc"));
        String numero = Php.toStr(data.get("num_doc"));
        Client c = clients.findByTenantIdAndTipoDocumentoAndNumeroDocumento(tenant.getId(), tipo, numero).orElseGet(() -> {
            Client n = new Client();
            n.setTenantId(tenant.getId());
            n.setTipoDocumento(tipo);
            n.setNumeroDocumento(numero);
            return n;
        });
        c.setRazonSocial(Php.toStr(data.get("razon_social")));
        c.setDireccion(data.get("direccion") == null ? null : Php.toStr(data.get("direccion")));
        if (!Php.empty(data.get("email"))) {
            c.setEmail(Php.toStr(data.get("email")));
        }
        if (!Php.empty(data.get("telefono"))) {
            c.setTelefono(Php.toStr(data.get("telefono")));
        }
        return clients.saveAndFlush(c);
    }
}
