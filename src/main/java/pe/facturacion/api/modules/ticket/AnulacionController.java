package pe.facturacion.api.modules.ticket;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Comunicación de baja (RA): {@code VoidedController} de API-PRO. */
@RestController
@RequestMapping("/api/v1/anulaciones")
@Tag(name = "Comunicación de baja")
public class AnulacionController {

    private final VoidedEndpoints endpoints;

    public AnulacionController(VoidedEndpoints endpoints) {
        this.endpoints = endpoints;
    }

    @Operation(summary = "Crear comunicación de baja de facturas y notas aceptadas")
    @PostMapping
    public ResponseEntity<Object> store(HttpServletRequest request) {
        return endpoints.store(request);
    }

    @Operation(summary = "Listar (?estado=, ?fecha_desde=, ?fecha_hasta=, ?per_page=)")
    @GetMapping
    public ResponseEntity<Object> index(HttpServletRequest request) {
        return endpoints.index(request);
    }

    @Operation(summary = "Ver comunicación de baja")
    @GetMapping("/{id}")
    public ResponseEntity<Object> show(HttpServletRequest request, @PathVariable long id) {
        return endpoints.show(request, id);
    }

    @Operation(summary = "Estado en SUNAT (consulta el ticket si sigue en proceso)")
    @GetMapping("/{id}/estado")
    public ResponseEntity<Object> estado(HttpServletRequest request, @PathVariable long id) {
        return endpoints.checkStatus(request, id);
    }

    @Operation(summary = "Descargar XML firmado")
    @GetMapping("/{id}/xml")
    public ResponseEntity<Object> xml(HttpServletRequest request, @PathVariable long id) {
        return endpoints.file(request, id, "xml");
    }

    @Operation(summary = "Descargar CDR")
    @GetMapping("/{id}/cdr")
    public ResponseEntity<Object> cdr(HttpServletRequest request, @PathVariable long id) {
        return endpoints.file(request, id, "cdr");
    }

    @Operation(summary = "Enviar a SUNAT")
    @PostMapping("/{id}/enviar")
    public ResponseEntity<Object> enviar(HttpServletRequest request, @PathVariable long id) {
        return endpoints.enviar(request, id);
    }
}
