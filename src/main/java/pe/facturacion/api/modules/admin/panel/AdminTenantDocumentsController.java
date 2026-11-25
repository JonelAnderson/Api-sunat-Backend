package pe.facturacion.api.modules.admin.panel;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.api.AdminV1Responses;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.document.SaleDocumentEndpoints;
import pe.facturacion.api.modules.pdf.PdfService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.ticket.SummaryEndpoints;
import pe.facturacion.api.modules.ticket.VoidedEndpoints;

/**
 * Comprobantes de una empresa desde el panel (solo lectura): facturas, boletas, notas de crédito y notas de débito.
 * Reutiliza {@link SaleDocumentEndpoints} y {@link PdfService} con la empresa de la ruta: mismos filtros, mismo recurso
 * (snake_case) y mismos archivos que la empresa obtiene en {@code /api/v1/{tipo}}. Un documento de otra empresa
 * responde 404 (la búsqueda va siempre por {@code tenant_id}).
 */
@RestController
@RequestMapping("/api/admin/tenants/{id}")
@Tag(name = "Panel de administración · comprobantes de la empresa")
public class AdminTenantDocumentsController {

    /** Públicos: el portal de clientes reutiliza los mismos tipos, el mismo estado y la misma resolución del segmento. */
    public static final String TIPOS = "facturas|boletas|notas-credito|notas-debito";
    /** Claves del recurso que forman el estado SUNAT del comprobante. */
    public static final List<String> ESTADO_KEYS = List.of("id", "tipo_documento", "numero_completo", "sunat", "enviado_en");
    /** Clave que el panel agrega al detalle (comprobantes y guías): el cuerpo del PUT de corrección (§12.1). */
    public static final String EDIT_KEY = "datos_edicion";

    private final AdminTenantViews views;
    private final SaleDocumentEndpoints endpoints;
    private final PdfService pdf;
    private final AdminVoidLookup voids;
    private final SummaryEndpoints summaries;
    private final VoidedEndpoints voided;
    private final AdminEditData editData;

    public AdminTenantDocumentsController(AdminTenantViews views, SaleDocumentEndpoints endpoints, PdfService pdf, AdminVoidLookup voids,
            SummaryEndpoints summaries, VoidedEndpoints voided, AdminEditData editData) {
        this.views = views;
        this.endpoints = endpoints;
        this.pdf = pdf;
        this.voids = voids;
        this.summaries = summaries;
        this.voided = voided;
        this.editData = editData;
    }

    @Operation(summary = "Comprobantes de la empresa (mismos filtros que /api/v1/{tipo}; records, numPage, textFilter)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/{tipo:" + TIPOS + "}")
    public ResponseEntity<Object> list(@PathVariable long id, @PathVariable String tipo, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        DocumentKind kind = kind(tipo);
        ResponseEntity<Object> r = AdminV1Responses.page(endpoints.index(kind, tenant, views.pagedInput(request, "search")));
        views.auditView(request, tenant, tipo);
        return r;
    }

    @Operation(summary = "Detalle del comprobante (ítems y pagos), como GET /api/v1/{tipo}/{id}, más datos_edicion (cuerpo del PUT de corrección)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/{tipo:" + TIPOS + "}/{docId}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> show(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        DocumentKind kind = kind(tipo);
        Map<String, Object> resource = new LinkedHashMap<>((Map<String, Object>) AdminV1Responses.successBodyData(
                endpoints.show(kind, tenant, docId)));
        // Única clave que agrega el panel (§12.1): el cuerpo del PUT con lo guardado (tip_afe_igv, ISC, ICBPER…)
        resource.put(EDIT_KEY, editData.sale(kind, tenant, docId));
        views.auditDetail(request, tenant, tipo + ".detalle", kind.tipoDocumento, docId);
        return AdminResponse.ok(resource);
    }

    @Operation(summary = "Estado SUNAT guardado del comprobante (no consulta a SUNAT)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/{tipo:" + TIPOS + "}/{docId}/estado")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> estado(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        DocumentKind kind = kind(tipo);
        Map<String, Object> resource = (Map<String, Object>) AdminV1Responses.successBodyData(endpoints.show(kind, tenant, docId));
        views.auditDetail(request, tenant, tipo + ".estado", kind.tipoDocumento, docId);
        return AdminResponse.ok(AdminV1Responses.pick(resource, ESTADO_KEYS));
    }

    @Operation(summary = "Pagos registrados del comprobante, como GET /api/v1/{facturas|boletas}/{id}/pagos")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/{tipo:facturas|boletas}/{docId}/pagos")
    public ResponseEntity<Object> pagos(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        DocumentKind kind = kind(tipo);
        ResponseEntity<Object> r = AdminV1Responses.whole(endpoints.paymentIndex(kind, tenant, String.valueOf(docId)));
        views.auditDetail(request, tenant, tipo + ".pagos", kind.tipoDocumento, docId);
        return r;
    }

    @Operation(summary = "Descarga el XML firmado")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/{tipo:" + TIPOS + "}/{docId}/xml")
    public ResponseEntity<Object> xml(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        DocumentKind kind = kind(tipo);
        ResponseEntity<Object> r = AdminV1Responses.file(endpoints.xml(kind, tenant, docId));
        views.auditDownload(request, tenant, tipo, kind.tipoDocumento, docId, "xml");
        return r;
    }

    @Operation(summary = "Descarga el CDR de SUNAT (ZIP)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/{tipo:" + TIPOS + "}/{docId}/cdr")
    public ResponseEntity<Object> cdr(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        DocumentKind kind = kind(tipo);
        ResponseEntity<Object> r = AdminV1Responses.file(endpoints.cdr(kind, tenant, docId));
        views.auditDownload(request, tenant, tipo, kind.tipoDocumento, docId, "cdr");
        return r;
    }

    @Operation(summary = "Representación impresa en PDF (?formato=a4|a5|ticket-80|ticket-58; inline=1 vista previa, inline=0 descarga)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/{tipo:" + TIPOS + "}/{docId}/pdf")
    public ResponseEntity<Object> pdf(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        DocumentKind kind = kind(tipo);
        ResponseEntity<Object> r = AdminV1Responses.file(pdf.sale(tenant, views.input(request), kind, docId), AdminTenantViews.inline(request));
        views.auditDownload(request, tenant, tipo, kind.tipoDocumento, docId, "pdf");
        return r;
    }

    // ------------------------------------------------------------------ constancia de anulación

    @Operation(summary = "Comunicación de baja (RA) o resumen de anulación (RC) que anuló el comprobante (data null si no hay)")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/{tipo:" + TIPOS + "}/{docId}/anulacion")
    public ResponseEntity<Object> anulacion(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        DocumentKind kind = kind(tipo);
        var found = voids.find(kind, tenant.getId(), docId);
        views.auditDetail(request, tenant, tipo + ".anulacion", kind.tipoDocumento, docId);
        return found.isPresent() ? AdminResponse.ok(found.get().toMap())
                : AdminResponse.ok(null, "El comprobante no tiene una comunicación de baja ni un resumen de anulación.");
    }

    @Operation(summary = "Constancia de anulación: XML o CDR del RA / RC que anuló el comprobante")
    @AdminPermission(AdminPermissions.TENANTS_VIEW)
    @GetMapping("/{tipo:" + TIPOS + "}/{docId}/anulacion/{archivo:xml|cdr}")
    public ResponseEntity<Object> anulacionArchivo(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId,
            @PathVariable String archivo, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        DocumentKind kind = kind(tipo);
        AdminVoidLookup.Found found = voids.find(kind, tenant.getId(), docId)
                .orElseThrow(() -> AdminException.status(404, "No se encontró el registro de anulación de este comprobante."));
        ResponseEntity<Object> v1 = "RC".equals(found.tipo())
                ? ("cdr".equals(archivo) ? summaries.cdr(tenant, found.id()) : summaries.xml(tenant, found.id()))
                : voided.file(tenant, found.id(), archivo);
        ResponseEntity<Object> r = AdminV1Responses.file(v1);
        views.auditDownload(request, tenant, tipo + ".anulacion", kind.tipoDocumento, docId, archivo);
        return r;
    }

    /** Segmento de la URL ({@code facturas}, {@code notas-credito}…) → tipo de comprobante. */
    public static DocumentKind kind(String segment) {
        for (DocumentKind k : DocumentKind.values()) {
            if (k.urlSegment.equals(segment)) {
                return k;
            }
        }
        throw AdminException.status(404, "Recurso no encontrado.");
    }
}
