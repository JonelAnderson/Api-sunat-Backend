package pe.facturacion.api.modules.settlement;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liquidación de compra electrónica (tipo 04): módulo nuevo, no existe en API-PRO. */
@RestController
@RequestMapping("/api/v1/liquidaciones-compra")
@Tag(name = "Liquidaciones de compra")
public class LiquidacionCompraController {

    private final SettlementEndpoints endpoints;

    private final pe.facturacion.api.modules.pdf.PdfService pdf;

    public LiquidacionCompraController(SettlementEndpoints endpoints, pe.facturacion.api.modules.pdf.PdfService pdf) {
        this.endpoints = endpoints;
        this.pdf = pdf;
    }

    @Operation(summary = "Representación impresa en PDF (?formato=a4|a5|ticket-80|ticket-58)")
    @GetMapping("/{id}/pdf")
    public ResponseEntity<Object> pdf(HttpServletRequest request, @PathVariable long id) {
        return pdf.settlement(request, id);
    }

    @Operation(summary = "Emitir y encolar el envío a SUNAT (enviar_automatico=false la deja pendiente)")
    @PostMapping
    public ResponseEntity<Object> store(HttpServletRequest request) {
        return endpoints.store(request);
    }

    @Operation(summary = "Listar (filtros: sunat_status, fecha_desde, fecha_hasta, serie, vendedor_num_doc, por_pagina)")
    @GetMapping
    public ResponseEntity<Object> index(HttpServletRequest request) {
        return endpoints.index(request);
    }

    @Operation(summary = "Reporte de IGV y renta retenidos del periodo (?periodo=AAAA-MM)")
    @GetMapping("/reporte-retenciones")
    public ResponseEntity<Object> reporte(HttpServletRequest request) {
        return endpoints.reporteRetenciones(request);
    }

    @Operation(summary = "Ver con su estado SUNAT")
    @GetMapping("/{id}")
    public ResponseEntity<Object> show(HttpServletRequest request, @PathVariable long id) {
        return endpoints.show(request, id);
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
        return endpoints.enviar(request, id, false);
    }

    @Operation(summary = "Reenviar una liquidación pendiente o rechazada")
    @PostMapping("/{id}/reenviar")
    public ResponseEntity<Object> reenviar(HttpServletRequest request, @PathVariable long id) {
        return endpoints.enviar(request, id, true);
    }

    @Operation(summary = "Revertir (causal, motivo): genera el Resumen diario de reversiones (RR)")
    @PostMapping("/{id}/reversion")
    public ResponseEntity<Object> reversion(HttpServletRequest request, @PathVariable long id) {
        return endpoints.reversion(request, id);
    }

    @Operation(summary = "Registrar pagos al vendedor")
    @PostMapping("/{id}/pagos")
    public ResponseEntity<Object> pagosStore(HttpServletRequest request, @PathVariable long id) {
        return endpoints.paymentStore(request, id);
    }

    @Operation(summary = "Listar pagos")
    @GetMapping("/{id}/pagos")
    public ResponseEntity<Object> pagosIndex(HttpServletRequest request, @PathVariable long id) {
        return endpoints.paymentIndex(request, id, null);
    }

    @Operation(summary = "Eliminar pago")
    @DeleteMapping("/{id}/pagos/{paymentId}")
    public ResponseEntity<Object> pagosDestroy(HttpServletRequest request, @PathVariable long id, @PathVariable long paymentId) {
        return endpoints.paymentDestroy(request, id, paymentId);
    }
}
