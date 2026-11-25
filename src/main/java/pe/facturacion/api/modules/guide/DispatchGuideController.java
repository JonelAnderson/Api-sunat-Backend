package pe.facturacion.api.modules.guide;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Guías de remisión electrónicas: remitente (09) y transportista (31) por la API REST GRE. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Guías de remisión")
public class DispatchGuideController {

    private final GuideEndpoints endpoints;

    private final pe.facturacion.api.modules.pdf.PdfService pdf;

    public DispatchGuideController(GuideEndpoints endpoints, pe.facturacion.api.modules.pdf.PdfService pdf) {
        this.endpoints = endpoints;
        this.pdf = pdf;
    }

    @Operation(summary = "Representación impresa en PDF (?formato=a4|a5|ticket-80|ticket-58)")
    @GetMapping("/guias-remision/{id}/pdf")
    public ResponseEntity<Object> pdf(HttpServletRequest request, @PathVariable long id) {
        return pdf.guide(request, id);
    }

    @Operation(summary = "Crear guía (tipo_documento 09 por defecto o 31) y encolar el envío")
    @PostMapping("/guias-remision")
    public ResponseEntity<Object> store(HttpServletRequest request) {
        return endpoints.store(request, false);
    }

    @Operation(summary = "Crear Guía de Remisión Transportista (31)")
    @PostMapping("/guias-remision-transportista")
    public ResponseEntity<Object> storeTransportista(HttpServletRequest request) {
        return endpoints.store(request, true);
    }

    @Operation(summary = "Listar guías (?por_pagina)")
    @GetMapping("/guias-remision")
    public ResponseEntity<Object> index(HttpServletRequest request) {
        return endpoints.index(request);
    }

    @Operation(summary = "Ver guía")
    @GetMapping("/guias-remision/{id}")
    public ResponseEntity<Object> show(HttpServletRequest request, @PathVariable long id) {
        return endpoints.show(request, id);
    }

    @Operation(summary = "Corregir una guía pendiente o rechazada y reenviarla")
    @PutMapping("/guias-remision/{id}")
    public ResponseEntity<Object> update(HttpServletRequest request, @PathVariable long id) {
        return endpoints.update(request, id);
    }

    @Operation(summary = "Descargar XML firmado")
    @GetMapping("/guias-remision/{id}/xml")
    public ResponseEntity<Object> xml(HttpServletRequest request, @PathVariable long id) {
        return endpoints.xml(request, id);
    }

    @Operation(summary = "Descargar CDR")
    @GetMapping("/guias-remision/{id}/cdr")
    public ResponseEntity<Object> cdr(HttpServletRequest request, @PathVariable long id) {
        return endpoints.cdr(request, id);
    }

    @Operation(summary = "Consultar el ticket en SUNAT")
    @GetMapping("/guias-remision/{id}/estado")
    public ResponseEntity<Object> estado(HttpServletRequest request, @PathVariable long id) {
        return endpoints.checkStatus(request, id);
    }

    @Operation(summary = "Enviar (o reenviar) a SUNAT")
    @PostMapping("/guias-remision/{id}/enviar")
    public ResponseEntity<Object> enviar(HttpServletRequest request, @PathVariable long id) {
        return endpoints.enviar(request, id);
    }
}
