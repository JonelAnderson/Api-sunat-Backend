package pe.facturacion.api.modules.retper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Comprobantes de retenciones (tipo 20). */
@RestController
@RequestMapping("/api/v1/retenciones")
@Tag(name = "Retenciones")
public class RetencionController {

    private static final RetPerKind KIND = RetPerKind.RETENCION;

    private final RetPerEndpoints endpoints;

    private final pe.facturacion.api.modules.pdf.PdfService pdf;

    public RetencionController(RetPerEndpoints endpoints, pe.facturacion.api.modules.pdf.PdfService pdf) {
        this.endpoints = endpoints;
        this.pdf = pdf;
    }

    @Operation(summary = "Representación impresa en PDF (?formato=a4|a5|ticket-80|ticket-58)")
    @GetMapping("/{id}/pdf")
    public ResponseEntity<Object> pdf(HttpServletRequest request, @PathVariable long id) {
        return pdf.retper(request, KIND, id);
    }

    @Operation(summary = "Listar (filtros: sunat_status, fecha_desde + fecha_hasta, serie, proveedor_num_doc, per_page)")
    @GetMapping
    public ResponseEntity<Object> index(HttpServletRequest request) {
        return endpoints.index(KIND, request);
    }

    @Operation(summary = "Crear y encolar el envío a SUNAT (enviar_automatico=false lo deja pendiente)")
    @PostMapping
    public ResponseEntity<Object> store(HttpServletRequest request) {
        return endpoints.store(KIND, request);
    }

    @Operation(summary = "Ver con sus documentos relacionados")
    @GetMapping("/{id}")
    public ResponseEntity<Object> show(HttpServletRequest request, @PathVariable long id) {
        return endpoints.show(KIND, request, id);
    }

    @Operation(summary = "Descargar XML firmado")
    @GetMapping("/{id}/xml")
    public ResponseEntity<Object> xml(HttpServletRequest request, @PathVariable long id) {
        return endpoints.xml(KIND, request, id);
    }

    @Operation(summary = "Descargar CDR")
    @GetMapping("/{id}/cdr")
    public ResponseEntity<Object> cdr(HttpServletRequest request, @PathVariable long id) {
        return endpoints.cdr(KIND, request, id);
    }

    @Operation(summary = "Enviar (o reenviar) a SUNAT")
    @PostMapping("/{id}/enviar")
    public ResponseEntity<Object> enviar(HttpServletRequest request, @PathVariable long id) {
        return endpoints.enviar(KIND, request, id);
    }
}
