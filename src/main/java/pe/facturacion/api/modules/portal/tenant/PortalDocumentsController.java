package pe.facturacion.api.modules.portal.tenant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.api.AdminV1Responses;
import pe.facturacion.api.modules.admin.panel.AdminTenantDocumentsController;
import pe.facturacion.api.modules.admin.panel.AdminTenantUnifiedDocuments;
import pe.facturacion.api.modules.admin.panel.AdminTenantViews;
import pe.facturacion.api.modules.audit.AuditLogService;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.document.SaleDocumentEndpoints;
import pe.facturacion.api.modules.pdf.PdfService;
import pe.facturacion.api.modules.portal.security.PortalPrincipal;
import pe.facturacion.api.modules.tenant.domain.Tenant;

/**
 * Comprobantes de una empresa en el portal de clientes (solo lectura): facturas, boletas, notas de crédito y de débito.
 * No tiene consultas propias: reutiliza {@link SaleDocumentEndpoints}, {@link PdfService} y la vista unificada del panel
 * ({@link AdminTenantUnifiedDocuments}) con la empresa que fija {@link PortalTenantAccess} desde las de la persona, así
 * que los filtros, el recurso (snake_case, el mismo que la empresa obtiene en {@code /api/v1/{tipo}}) y los archivos
 * son los del panel. Un documento de otra empresa responde 404 (la búsqueda va siempre por {@code tenant_id}).
 *
 * <p>No hay edición, emisión, anulación ni corrección, ni la clave {@code datos_edicion} que el panel agrega al
 * detalle. Cada descarga y cada detalle quedan en la auditoría con el actor del portal.
 */
@RestController
@RequestMapping("/api/portal/tenants/{id}")
@Tag(name = "Portal de clientes · comprobantes")
public class PortalDocumentsController {

    private static final String TIPOS = AdminTenantDocumentsController.TIPOS;

    private final PortalTenantAccess access;
    private final AdminTenantViews views;
    private final SaleDocumentEndpoints endpoints;
    private final PdfService pdf;
    private final AdminTenantUnifiedDocuments unified;
    private final AuditLogService audit;

    public PortalDocumentsController(PortalTenantAccess access, AdminTenantViews views, SaleDocumentEndpoints endpoints, PdfService pdf,
            AdminTenantUnifiedDocuments unified, AuditLogService audit) {
        this.access = access;
        this.views = views;
        this.endpoints = endpoints;
        this.pdf = pdf;
        this.unified = unified;
        this.audit = audit;
    }

    @Operation(summary = "Todos los comprobantes en una vista (tipo, estado, serie, sucursalId, startDate / endDate, textFilter, orden)")
    @GetMapping("/documentos")
    public ResponseEntity<Object> documentos(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = access.require(request, id);
        return unified.list(tenant.getId(), request);
    }

    @Operation(summary = "Contadores por tipo y estado de la vista unificada")
    @GetMapping("/documentos/resumen")
    public ResponseEntity<Object> documentosResumen(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = access.require(request, id);
        return AdminResponse.ok(unified.summary(tenant.getId(), request));
    }

    @Operation(summary = "Comprobantes de un tipo (mismos filtros que /api/v1/{tipo}; records, numPage, textFilter)")
    @GetMapping("/{tipo:" + TIPOS + "}")
    public ResponseEntity<Object> list(@PathVariable long id, @PathVariable String tipo, HttpServletRequest request) {
        Tenant tenant = access.require(request, id);
        DocumentKind kind = AdminTenantDocumentsController.kind(tipo);
        return AdminV1Responses.page(endpoints.index(kind, tenant, views.pagedInput(request, "search")));
    }

    @Operation(summary = "Detalle del comprobante (ítems y pagos), como GET /api/v1/{tipo}/{id}")
    @GetMapping("/{tipo:" + TIPOS + "}/{docId}")
    public ResponseEntity<Object> show(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = access.require(request, id);
        DocumentKind kind = AdminTenantDocumentsController.kind(tipo);
        ResponseEntity<Object> v1 = endpoints.show(kind, tenant, docId);
        ResponseEntity<Object> r = AdminResponse.ok(AdminV1Responses.successBodyData(v1));
        auditView(request, tenant, tipo + ".detalle", docId, null);
        return r;
    }

    @Operation(summary = "Estado SUNAT guardado del comprobante (no consulta a SUNAT)")
    @GetMapping("/{tipo:" + TIPOS + "}/{docId}/estado")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> estado(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = access.require(request, id);
        DocumentKind kind = AdminTenantDocumentsController.kind(tipo);
        Map<String, Object> resource = (Map<String, Object>) AdminV1Responses.successBodyData(endpoints.show(kind, tenant, docId));
        auditView(request, tenant, tipo + ".estado", docId, null);
        return AdminResponse.ok(AdminV1Responses.pick(resource, AdminTenantDocumentsController.ESTADO_KEYS));
    }

    @Operation(summary = "Descarga el XML firmado")
    @GetMapping("/{tipo:" + TIPOS + "}/{docId}/xml")
    public ResponseEntity<Object> xml(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = access.require(request, id);
        DocumentKind kind = AdminTenantDocumentsController.kind(tipo);
        ResponseEntity<Object> r = AdminV1Responses.file(endpoints.xml(kind, tenant, docId));
        auditView(request, tenant, tipo, docId, "xml");
        return r;
    }

    @Operation(summary = "Descarga el CDR de SUNAT (ZIP)")
    @GetMapping("/{tipo:" + TIPOS + "}/{docId}/cdr")
    public ResponseEntity<Object> cdr(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = access.require(request, id);
        DocumentKind kind = AdminTenantDocumentsController.kind(tipo);
        ResponseEntity<Object> r = AdminV1Responses.file(endpoints.cdr(kind, tenant, docId));
        auditView(request, tenant, tipo, docId, "cdr");
        return r;
    }

    @Operation(summary = "Representación impresa en PDF (?formato=a4|a5|ticket-80|ticket-58; inline=1 vista previa, inline=0 descarga)")
    @GetMapping("/{tipo:" + TIPOS + "}/{docId}/pdf")
    public ResponseEntity<Object> pdf(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = access.require(request, id);
        DocumentKind kind = AdminTenantDocumentsController.kind(tipo);
        ResponseEntity<Object> r = AdminV1Responses.file(pdf.sale(tenant, views.input(request), kind, docId), AdminTenantViews.inline(request));
        auditView(request, tenant, tipo, docId, "pdf");
        return r;
    }

    /** Detalle o descarga de un comprobante (siempre), con el actor del portal y sin datos del comprobante en la metadata. */
    private void auditView(HttpServletRequest request, Tenant tenant, String vista, long docId, String archivo) {
        PortalPrincipal me = PortalPrincipal.require(request);
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("vista", vista);
        meta.put("documento_id", docId);
        meta.put("portal_user_id", me.userId());
        if (archivo != null) {
            meta.put("archivo", archivo);
        }
        String accion = archivo == null ? "portal.empresa.consulta" : "portal.empresa.descarga";
        audit.registrarPortal(request, "portal", me.email(), accion, tenant.getId(), AuditLogService.SUBJECT_TENANT, tenant.getId(),
                "Portal: " + (archivo == null ? "consulta de " : "descarga del " + archivo.toUpperCase(java.util.Locale.ROOT) + " de ") + vista
                        + " " + docId + " de la empresa " + tenant.getRuc(), meta);
    }
}
