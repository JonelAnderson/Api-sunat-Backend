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

/** Resumen diario de boletas (RC): {@code SummaryController} de API-PRO. */
@RestController
@RequestMapping("/api/v1/resumenes")
@Tag(name = "Resumen diario")
public class ResumenController {

    private final SummaryEndpoints endpoints;

    public ResumenController(SummaryEndpoints endpoints) {
        this.endpoints = endpoints;
    }

    @Operation(summary = "Listar resúmenes (?mes=YYYY-MM, ?tipo=envio|anulacion)")
    @GetMapping
    public ResponseEntity<Object> index(HttpServletRequest request) {
        return endpoints.index(request);
    }

    @Operation(summary = "Crear resumen de boletas pendientes o de anulación ({\"anular\":[{\"id\":..}]})")
    @PostMapping
    public ResponseEntity<Object> store(HttpServletRequest request) {
        return endpoints.store(request);
    }

    @Operation(summary = "Estado del resumen y sus boletas")
    @GetMapping("/{id}/estado")
    public ResponseEntity<Object> estado(HttpServletRequest request, @PathVariable long id) {
        return endpoints.checkStatus(request, id);
    }

    @Operation(summary = "Descargar XML firmado")
    @GetMapping("/{id}/xml")
    public ResponseEntity<Object> xml(HttpServletRequest request, @PathVariable long id) {
        return endpoints.xml(request, id);
    }

    @Operation(summary = "Descargar CDR")
    @GetMapping("/{id}/cdr")
    public ResponseEntity<Object> cdr(HttpServletRequest request, @PathVariable long id) {
        return endpoints.cdr(request, id);
    }

    @Operation(summary = "Enviar a SUNAT")
    @PostMapping("/{id}/enviar")
    public ResponseEntity<Object> enviar(HttpServletRequest request, @PathVariable long id) {
        return endpoints.enviar(request, id);
    }
}
