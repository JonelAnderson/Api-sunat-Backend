package pe.facturacion.api.modules.admin.panel;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.api.AdminV1Responses;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.guide.GuideEndpoints;
import pe.facturacion.api.modules.pdf.PdfService;
import pe.facturacion.api.modules.retper.RetPerEndpoints;
import pe.facturacion.api.modules.retper.RetPerKind;
import pe.facturacion.api.modules.settlement.SettlementEndpoints;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.ticket.SummaryEndpoints;
import pe.facturacion.api.modules.ticket.VoidedEndpoints;

/**
 * Otros comprobantes de una empresa desde el panel (solo lectura): guías de remisión, retenciones, percepciones,
 * liquidaciones de compra, resúmenes diarios y comunicaciones de baja / reversiones. Reutiliza los servicios de
 * {@code /api/v1} con la empresa de la ruta (mismos filtros, recurso y archivos). Las consultas en vivo a SUNAT
 * ({@code GET .../estado} de guías y anulaciones) no se exponen: escriben el resultado y usan las credenciales de la
 * empresa; el panel muestra el estado guardado.
 */
@RestController
@RequestMapping("/api/admin/tenants/{id}")
@Tag(name = "Panel de administración · otros comprobantes de la empresa")
public class AdminTenantOtherDocumentsController {

    private final AdminTenantViews views;
    private final GuideEndpoints guides;
    private final RetPerEndpoints retper;
    private final SettlementEndpoints settlements;
    private final SummaryEndpoints summaries;
    private final VoidedEndpoints voided;
    private final PdfService pdf;
    private final AdminEditData editData;

    public AdminTenantOtherDocumentsController(AdminTenantViews views, GuideEndpoints guides, RetPerEndpoints retper,
            SettlementEndpoints settlements, SummaryEndpoints summaries, VoidedEndpoints voided, PdfService pdf, AdminEditData editData) {
        this.views = views;
        this.guides = guides;
        this.retper = retper;
        this.settlements = settlements;
        this.summaries = summaries;
        this.voided = voided;
        this.pdf = pdf;
        this.editData = editData;
    }

    // ------------------------------------------------------------------ guías de remisión

    @Operation(summary = "Guías de remisión (sunat_status, tipo_documento, serie, fecha_desde + fecha_hasta)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/guias-remision")
    public ResponseEntity<Object> guias(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.page(guides.index(tenant, views.pagedInput(request, null)));
        views.auditView(request, tenant, "guias-remision");
        return r;
    }

    @Operation(summary = "Detalle de la guía de remisión (recurso de /api/v1 más datos_edicion: el cuerpo completo del PUT de corrección)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/guias-remision/{docId}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> guia(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        Map<String, Object> resource = new LinkedHashMap<>((Map<String, Object>) AdminV1Responses.successBodyData(guides.show(tenant, docId)));
        // Única clave que agrega el panel (§12.1): lo que el PUT necesita y el recurso de /api/v1 no muestra
        resource.put(AdminTenantDocumentsController.EDIT_KEY, editData.guide(tenant, docId));
        views.auditDetail(request, tenant, "guias-remision.detalle", null, docId);
        return AdminResponse.ok(resource);
    }

    @Operation(summary = "Descarga el XML de la guía")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/guias-remision/{docId}/xml")
    public ResponseEntity<Object> guiaXml(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.file(guides.xml(tenant, docId));
        views.auditDownload(request, tenant, "guias-remision", null, docId, "xml");
        return r;
    }

    @Operation(summary = "Descarga el CDR de la guía")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/guias-remision/{docId}/cdr")
    public ResponseEntity<Object> guiaCdr(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.file(guides.cdr(tenant, docId));
        views.auditDownload(request, tenant, "guias-remision", null, docId, "cdr");
        return r;
    }

    @Operation(summary = "PDF de la guía (?formato=a4|a5|ticket-80|ticket-58; inline=1 vista previa, inline=0 descarga)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/guias-remision/{docId}/pdf")
    public ResponseEntity<Object> guiaPdf(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.file(pdf.guide(tenant, views.input(request), docId), AdminTenantViews.inline(request));
        views.auditDownload(request, tenant, "guias-remision", null, docId, "pdf");
        return r;
    }

    // ------------------------------------------------------------------ retenciones y percepciones

    @Operation(summary = "Retenciones o percepciones (sunat_status, fecha_desde + fecha_hasta, serie, proveedor_num_doc / cliente_num_doc)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/{tipo:retenciones|percepciones}")
    public ResponseEntity<Object> retPer(@PathVariable long id, @PathVariable String tipo, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.page(retper.index(retPerKind(tipo), tenant, views.pagedInput(request, null)));
        views.auditView(request, tenant, tipo);
        return r;
    }

    @Operation(summary = "Detalle de la retención o percepción con sus documentos relacionados")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/{tipo:retenciones|percepciones}/{docId}")
    public ResponseEntity<Object> retPerShow(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId,
            HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        RetPerKind k = retPerKind(tipo);
        ResponseEntity<Object> r = AdminV1Responses.data(retper.show(k, tenant, docId));
        views.auditDetail(request, tenant, tipo + ".detalle", k.tipoDocumento, docId);
        return r;
    }

    @Operation(summary = "Descarga el XML de la retención o percepción")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/{tipo:retenciones|percepciones}/{docId}/xml")
    public ResponseEntity<Object> retPerXml(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId,
            HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        RetPerKind k = retPerKind(tipo);
        ResponseEntity<Object> r = AdminV1Responses.file(retper.xml(k, tenant, docId));
        views.auditDownload(request, tenant, tipo, k.tipoDocumento, docId, "xml");
        return r;
    }

    @Operation(summary = "Descarga el CDR de la retención o percepción")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/{tipo:retenciones|percepciones}/{docId}/cdr")
    public ResponseEntity<Object> retPerCdr(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId,
            HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        RetPerKind k = retPerKind(tipo);
        ResponseEntity<Object> r = AdminV1Responses.file(retper.cdr(k, tenant, docId));
        views.auditDownload(request, tenant, tipo, k.tipoDocumento, docId, "cdr");
        return r;
    }

    @Operation(summary = "PDF de la retención o percepción (?formato=a4|a5|ticket-80|ticket-58; inline=1 vista previa, inline=0 descarga)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/{tipo:retenciones|percepciones}/{docId}/pdf")
    public ResponseEntity<Object> retPerPdf(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId,
            HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        RetPerKind k = retPerKind(tipo);
        ResponseEntity<Object> r = AdminV1Responses.file(pdf.retper(tenant, views.input(request), k, docId), AdminTenantViews.inline(request));
        views.auditDownload(request, tenant, tipo, k.tipoDocumento, docId, "pdf");
        return r;
    }

    // ------------------------------------------------------------------ liquidaciones de compra

    @Operation(summary = "Liquidaciones de compra (sunat_status, fecha_desde, fecha_hasta, serie, vendedor_num_doc)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/liquidaciones-compra")
    public ResponseEntity<Object> liquidaciones(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.page(settlements.index(tenant, views.pagedInput(request, null)));
        views.auditView(request, tenant, "liquidaciones-compra");
        return r;
    }

    @Operation(summary = "Detalle de la liquidación de compra con sus ítems")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/liquidaciones-compra/{docId}")
    public ResponseEntity<Object> liquidacion(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.data(settlements.show(tenant, docId));
        views.auditDetail(request, tenant, "liquidaciones-compra.detalle", "04", docId);
        return r;
    }

    @Operation(summary = "Pagos al vendedor de la liquidación de compra")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/liquidaciones-compra/{docId}/pagos")
    public ResponseEntity<Object> liquidacionPagos(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.whole(settlements.paymentIndex(tenant, docId, null));
        views.auditDetail(request, tenant, "liquidaciones-compra.pagos", "04", docId);
        return r;
    }

    @Operation(summary = "Descarga el XML de la liquidación de compra")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/liquidaciones-compra/{docId}/xml")
    public ResponseEntity<Object> liquidacionXml(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.file(settlements.xml(tenant, docId));
        views.auditDownload(request, tenant, "liquidaciones-compra", "04", docId, "xml");
        return r;
    }

    @Operation(summary = "Descarga el CDR de la liquidación de compra")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/liquidaciones-compra/{docId}/cdr")
    public ResponseEntity<Object> liquidacionCdr(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.file(settlements.cdr(tenant, docId));
        views.auditDownload(request, tenant, "liquidaciones-compra", "04", docId, "cdr");
        return r;
    }

    @Operation(summary = "PDF de la liquidación de compra (?formato=a4|a5|ticket-80|ticket-58; inline=1 vista previa, inline=0 descarga)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/liquidaciones-compra/{docId}/pdf")
    public ResponseEntity<Object> liquidacionPdf(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.file(pdf.settlement(tenant, views.input(request), docId), AdminTenantViews.inline(request));
        views.auditDownload(request, tenant, "liquidaciones-compra", "04", docId, "pdf");
        return r;
    }

    // ------------------------------------------------------------------ resúmenes diarios

    @Operation(summary = "Resúmenes diarios (mes=AAAA-MM, tipo=envio|anulacion)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/resumenes")
    public ResponseEntity<Object> resumenes(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.page(summaries.index(tenant, views.pagedInput(request, null)));
        views.auditView(request, tenant, "resumenes");
        return r;
    }

    @Operation(summary = "Detalle del resumen y sus boletas (estado guardado, como GET /api/v1/resumenes/{id}/estado)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/resumenes/{docId}")
    public ResponseEntity<Object> resumen(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.data(summaries.checkStatus(tenant, docId));
        views.auditDetail(request, tenant, "resumenes.detalle", null, docId);
        return r;
    }

    @Operation(summary = "Descarga el XML del resumen")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/resumenes/{docId}/xml")
    public ResponseEntity<Object> resumenXml(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.file(summaries.xml(tenant, docId));
        views.auditDownload(request, tenant, "resumenes", null, docId, "xml");
        return r;
    }

    @Operation(summary = "Descarga el CDR del resumen")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/resumenes/{docId}/cdr")
    public ResponseEntity<Object> resumenCdr(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.file(summaries.cdr(tenant, docId));
        views.auditDownload(request, tenant, "resumenes", null, docId, "cdr");
        return r;
    }

    // ------------------------------------------------------------------ comunicaciones de baja y reversiones

    @Operation(summary = "Comunicaciones de baja y reversiones (estado, fecha_desde, fecha_hasta)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/anulaciones")
    public ResponseEntity<Object> anulaciones(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.page(voided.index(tenant, views.pagedInput(request, null)));
        views.auditView(request, tenant, "anulaciones");
        return r;
    }

    @Operation(summary = "Detalle de la comunicación de baja o reversión (estado guardado)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/anulaciones/{docId}")
    public ResponseEntity<Object> anulacion(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.data(voided.show(tenant, docId));
        views.auditDetail(request, tenant, "anulaciones.detalle", null, docId);
        return r;
    }

    @Operation(summary = "Descarga el XML de la comunicación de baja o reversión")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/anulaciones/{docId}/xml")
    public ResponseEntity<Object> anulacionXml(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.file(voided.file(tenant, docId, "xml"));
        views.auditDownload(request, tenant, "anulaciones", null, docId, "xml");
        return r;
    }

    @Operation(summary = "Descarga el CDR de la comunicación de baja o reversión")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/anulaciones/{docId}/cdr")
    public ResponseEntity<Object> anulacionCdr(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        ResponseEntity<Object> r = AdminV1Responses.file(voided.file(tenant, docId, "cdr"));
        views.auditDownload(request, tenant, "anulaciones", null, docId, "cdr");
        return r;
    }

    static RetPerKind retPerKind(String segment) {
        return "percepciones".equals(segment) ? RetPerKind.PERCEPCION : RetPerKind.RETENCION;
    }
}
