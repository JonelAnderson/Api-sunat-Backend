package pe.facturacion.api.support;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.http.RequestInput;
import pe.facturacion.api.shared.validation.Validator;

/** Endpoints solo de prueba (en src/test) para ejercitar la pila de middlewares y la validación. */
@RestController
public class TestEndpointsController {

    @PostMapping("/api/v1/_pruebas/eco")
    public ResponseEntity<Object> eco(HttpServletRequest request) {
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("tenant", ApiContext.requireTenant(request).getRuc());
        datos.put("entrada", RequestInput.all(request));
        return ApiResponse.created(datos);
    }

    @PostMapping("/api/v1/_pruebas/validar")
    public ResponseEntity<Object> validar(HttpServletRequest request) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("serie", java.util.List.of("required", "string", "size:4", "regex:/^F[A-Z0-9]{3}$/"));
        rules.put("cliente.num_doc", "required|string");
        rules.put("items", "required|array|min:1");
        rules.put("items.*.cantidad", "required|numeric|gt:0");
        Map<String, Object> datos = Validator.make(RequestInput.all(request), rules)
                .messages(Map.of("serie.regex", "La serie de factura debe empezar con F seguido de 3 caracteres alfanuméricos (ej. F001)."))
                .validate();
        return ApiResponse.success(datos);
    }

    @PostMapping("/api/v1/_pruebas/explota")
    public ResponseEntity<Object> explota() {
        throw new IllegalStateException("detalle interno que no debe filtrarse");
    }
}
