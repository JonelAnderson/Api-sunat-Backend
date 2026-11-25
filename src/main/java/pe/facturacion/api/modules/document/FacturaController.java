package pe.facturacion.api.modules.document;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Facturas (01): {@code InvoiceController} y pagos de API-PRO. */
@RestController
@RequestMapping("/api/v1/facturas")
@Tag(name = "Facturas")
public class FacturaController {

    private static final DocumentKind KIND = DocumentKind.FACTURA;
    private final SaleDocumentEndpoints endpoints;
    private final pe.facturacion.api.modules.pdf.PdfService pdf;

    public FacturaController(SaleDocumentEndpoints endpoints, pe.facturacion.api.modules.pdf.PdfService pdf) {
        this.endpoints = endpoints;
        this.pdf = pdf;
    }

    @Operation(summary = "Representación impresa en PDF (?formato=a4|a5|ticket-80|ticket-58)")
    @GetMapping("/{id}/pdf")
    public ResponseEntity<Object> pdf(HttpServletRequest request, @PathVariable long id) {
        return pdf.sale(request, KIND, id);
    }

    @Operation(summary = "Emitir factura (se encola el envío a SUNAT)")
    @PostMapping
    public ResponseEntity<Object> store(HttpServletRequest request) {
        return endpoints.store(KIND, request);
    }

    @Operation(summary = "Emisión masiva (máximo 100 facturas)")
    @PostMapping("/masivo")
    public ResponseEntity<Object> masivo(HttpServletRequest request) {
        return endpoints.masivo(request);
    }

    @Operation(summary = "Listar facturas con filtros y paginación")
    @GetMapping
    public ResponseEntity<Object> index(HttpServletRequest request) {
        return endpoints.index(KIND, request);
    }

    @Operation(summary = "Ver factura")
    @GetMapping("/{id}")
    public ResponseEntity<Object> show(HttpServletRequest request, @PathVariable long id) {
        return endpoints.show(KIND, request, id);
    }

    @Operation(summary = "Corregir factura no aceptada y reenviar")
    @PutMapping("/{id}")
    public ResponseEntity<Object> update(HttpServletRequest request, @PathVariable long id) {
        return endpoints.update(KIND, request, id);
    }

    @Operation(summary = "Descargar XML firmado")
    @GetMapping("/{id}/xml")
    public ResponseEntity<Object> xml(HttpServletRequest request, @PathVariable long id) {
        return endpoints.xml(KIND, request, id);
    }

    @Operation(summary = "Descargar CDR (ZIP)")
    @GetMapping("/{id}/cdr")
    public ResponseEntity<Object> cdr(HttpServletRequest request, @PathVariable long id) {
        return endpoints.cdr(KIND, request, id);
    }

    @Operation(summary = "Reenviar a SUNAT")
    @PostMapping("/{id}/reenviar")
    public ResponseEntity<Object> reenviar(HttpServletRequest request, @PathVariable long id) {
        return endpoints.enviar(KIND, request, id);
    }

    @Operation(summary = "Enviar a SUNAT")
    @PostMapping("/{id}/enviar")
    public ResponseEntity<Object> enviar(HttpServletRequest request, @PathVariable long id) {
        return endpoints.enviar(KIND, request, id);
    }

    @Operation(summary = "Registrar pagos")
    @PostMapping("/{id}/pagos")
    public ResponseEntity<Object> pagosStore(HttpServletRequest request, @PathVariable String id) {
        return endpoints.paymentStore(KIND, request, id);
    }

    @Operation(summary = "Listar pagos")
    @GetMapping("/{id}/pagos")
    public ResponseEntity<Object> pagosIndex(HttpServletRequest request, @PathVariable String id) {
        return endpoints.paymentIndex(KIND, request, id);
    }

    @Operation(summary = "Eliminar pago")
    @DeleteMapping("/{id}/pagos/{paymentId}")
    public ResponseEntity<Object> pagosDestroy(HttpServletRequest request, @PathVariable String id, @PathVariable String paymentId) {
        return endpoints.paymentDestroy(KIND, request, id, paymentId);
    }
}
