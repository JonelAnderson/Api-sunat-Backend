package pe.facturacion.api.modules.document;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.pdf.PdfService;
import pe.facturacion.api.modules.ticket.VoidedEndpoints;

/**
 * Alias {@code /api/sunat/...} de API-PRO sobre los endpoints V1 (misma autenticación):
 * facturas y boletas (listar, emitir, PDF, XML y anular). {@code anular} crea la comunicación de
 * baja con el cuerpo de {@code POST /api/v1/anulaciones}, como en API-PRO.
 */
@RestController
@RequestMapping("/api/sunat")
@Tag(name = "Alias /api/sunat")
public class SunatAliasController {

    private final SaleDocumentEndpoints endpoints;
    private final VoidedEndpoints voided;
    private final PdfService pdf;

    public SunatAliasController(SaleDocumentEndpoints endpoints, VoidedEndpoints voided, PdfService pdf) {
        this.endpoints = endpoints;
        this.voided = voided;
        this.pdf = pdf;
    }

    private static DocumentKind kind(String tipo) {
        return "boletas".equals(tipo) ? DocumentKind.BOLETA : DocumentKind.FACTURA;
    }

    @Operation(summary = "Listar facturas o boletas")
    @GetMapping("/{tipo:facturas|boletas}")
    public ResponseEntity<Object> index(HttpServletRequest request, @PathVariable String tipo) {
        return endpoints.index(kind(tipo), request);
    }

    @Operation(summary = "Emitir factura o boleta")
    @PostMapping("/{tipo:facturas|boletas}")
    public ResponseEntity<Object> store(HttpServletRequest request, @PathVariable String tipo) {
        return endpoints.store(kind(tipo), request);
    }

    @Operation(summary = "PDF de factura o boleta")
    @GetMapping("/{tipo:facturas|boletas}/{id}/pdf")
    public ResponseEntity<Object> pdf(HttpServletRequest request, @PathVariable String tipo, @PathVariable long id) {
        return pdf.sale(request, kind(tipo), id);
    }

    @Operation(summary = "XML de factura o boleta")
    @GetMapping("/{tipo:facturas|boletas}/{id}/xml")
    public ResponseEntity<Object> xml(HttpServletRequest request, @PathVariable String tipo, @PathVariable long id) {
        return endpoints.xml(kind(tipo), request, id);
    }

    @Operation(summary = "Anular (comunicación de baja)")
    @PostMapping("/{tipo:facturas|boletas}/{id}/anular")
    public ResponseEntity<Object> anular(HttpServletRequest request, @PathVariable String tipo, @PathVariable long id) {
        return voided.store(request);
    }
}
