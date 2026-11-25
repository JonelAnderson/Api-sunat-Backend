package pe.facturacion.api.modules.client;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiException;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.api.Paginator;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.shared.validation.Validator;

/** {@code Route::apiResource('clientes')} ({@code ClientController} + {@code StoreClientRequest}). */
@RestController
@RequestMapping("/api/v1/clientes")
@Tag(name = "Clientes")
public class ClienteController {

    private static final List<String> FIELDS = List.of("razon_social", "nombre_comercial", "direccion", "email", "telefono");

    private final ClientRepository clients;

    public ClienteController(ClientRepository clients) {
        this.clients = clients;
    }

    @Operation(summary = "Listar clientes (buscar, por_pagina, page)")
    @GetMapping
    public ResponseEntity<Object> index(HttpServletRequest request) {
        Tenant tenant = ApiContext.requireTenant(request);
        return index(tenant, Input.of(request));
    }

    /** Clientes de la empresa indicada (lo usa también el panel de administración con la misma respuesta). */
    public ResponseEntity<Object> index(Tenant tenant, Input input) {
        // has('buscar'): con ?buscar= vacío (null tras ConvertEmptyStringsToNull) busca '%%' → todos
        String pattern = input.has("buscar") ? "%" + (input.string("buscar") == null ? "" : input.string("buscar")) + "%" : null;
        int perPage = Math.min(Paginator.perPage(input.integer("por_pagina", 15)), 100);
        int page = Paginator.page(input);
        long total = clients.countSearch(tenant.getId(), pattern);
        List<Client> items = clients.search(tenant.getId(), pattern, perPage, Paginator.offset(page, perPage));
        return ApiResponse.success(Paginator.of(items, page, perPage, total).toDatosPaginacion(ClienteController::resource));
    }

    @Operation(summary = "Crear o actualizar cliente por tipo y número de documento (siempre 201)")
    @PostMapping
    @Transactional
    public ResponseEntity<Object> store(HttpServletRequest request) {
        return store(ApiContext.requireTenant(request), Input.of(request));
    }

    /** Crear o actualizar un cliente de la empresa indicada (lo usa también el panel de administración con la misma respuesta). */
    @Transactional
    public ResponseEntity<Object> store(Tenant tenant, Input input) {
        Map<String, Object> data = validate(input, false);
        Client client = clients.findByTenantIdAndTipoDocumentoAndNumeroDocumento(
                tenant.getId(), (String) data.get("tipo_documento"), (String) data.get("numero_documento")).orElseGet(() -> {
                    Client c = new Client();
                    c.setTenantId(tenant.getId());
                    c.setTipoDocumento((String) data.get("tipo_documento"));
                    c.setNumeroDocumento((String) data.get("numero_documento"));
                    return c;
                });
        apply(client, data);
        clients.save(client);
        return ApiResponse.created(resource(client));
    }

    @Operation(summary = "Ver cliente")
    @GetMapping("/{id}")
    public ResponseEntity<Object> show(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.success(resource(find(request, id)));
    }

    @Operation(summary = "Actualizar cliente (tipo y número de documento no se modifican)")
    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    @Transactional
    public ResponseEntity<Object> update(HttpServletRequest request, @PathVariable long id) {
        return update(ApiContext.requireTenant(request), Input.of(request), id);
    }

    /** Actualizar un cliente de la empresa indicada (404 si es de otra). */
    @Transactional
    public ResponseEntity<Object> update(Tenant tenant, Input input, long id) {
        Map<String, Object> data = validate(input, true);
        Client client = find(tenant, id);
        apply(client, data);
        clients.save(client);
        return ApiResponse.success(resource(client), "Cliente actualizado.");
    }

    @Operation(summary = "Eliminar cliente (borrado físico)")
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Object> destroy(HttpServletRequest request, @PathVariable long id) {
        return destroy(ApiContext.requireTenant(request), id);
    }

    /** Eliminar un cliente de la empresa indicada (borrado físico; 404 si es de otra). */
    @Transactional
    public ResponseEntity<Object> destroy(Tenant tenant, long id) {
        clients.delete(find(tenant, id));
        return ApiResponse.success(null, "Cliente eliminado.");
    }

    /** {@code StoreClientRequest}: alias tipo_doc/num_doc y reglas según el método. */
    private Map<String, Object> validate(Input input, boolean isUpdate) {
        Map<String, Object> data = input.all();
        if (!input.filled("tipo_documento") && input.filled("tipo_doc")) {
            data.put("tipo_documento", input.get("tipo_doc"));
        }
        if (!input.filled("numero_documento") && input.filled("num_doc")) {
            data.put("numero_documento", input.get("num_doc"));
        }
        String presence = isUpdate ? "sometimes" : "required";
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("tipo_documento", List.of(presence, "string", "in:0,1,4,6,7,A"));
        rules.put("numero_documento", List.of(presence, "string", "max:15"));
        rules.put("razon_social", List.of(presence, "string", "max:255"));
        rules.put("nombre_comercial", "nullable|string|max:255");
        rules.put("direccion", "nullable|string|max:500");
        rules.put("email", "nullable|email");
        rules.put("telefono", "nullable|string|max:20");
        rules.put("ubigeo", "nullable|string|between:4,6");
        return Validator.make(data, rules).validate();
    }

    /** Solo los campos validados presentes (la tabla no tiene ubigeo: se valida y se descarta como en API-PRO). */
    private static void apply(Client c, Map<String, Object> data) {
        for (String f : FIELDS) {
            if (!data.containsKey(f)) {
                continue;
            }
            String v = (String) data.get(f);
            switch (f) {
                case "razon_social" -> c.setRazonSocial(v);
                case "nombre_comercial" -> c.setNombreComercial(v);
                case "direccion" -> c.setDireccion(v);
                case "email" -> c.setEmail(v);
                case "telefono" -> c.setTelefono(v);
                default -> {
                }
            }
        }
    }

    private Client find(HttpServletRequest request, long id) {
        return find(ApiContext.requireTenant(request), id);
    }

    private Client find(Tenant tenant, long id) {
        return clients.findByIdAndTenantId(id, tenant.getId()).orElseThrow(ApiException::notFound);
    }

    /** {@code ClientResource}. */
    static Map<String, Object> resource(Client c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("tipo_documento", c.getTipoDocumento());
        m.put("numero_documento", c.getNumeroDocumento());
        m.put("razon_social", c.getRazonSocial());
        m.put("nombre_comercial", c.getNombreComercial());
        m.put("direccion", c.getDireccion());
        m.put("email", c.getEmail());
        m.put("telefono", c.getTelefono());
        m.put("creado_en", Clock.iso8601Utc(c.getCreatedAt()));
        return m;
    }
}
