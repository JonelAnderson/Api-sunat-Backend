package pe.facturacion.api.modules.admin.document;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminActionAudit;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminIdempotency;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.api.AdminV1Responses;
import pe.facturacion.api.modules.admin.api.AdminValidation;
import pe.facturacion.api.modules.admin.panel.AdminTenantViews;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.admin.security.AdminPrincipal;
import pe.facturacion.api.modules.admin.security.AdminRateLimiter;
import pe.facturacion.api.modules.consulta.BuscarDocumentoController;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.document.SaleDocumentEndpoints;
import pe.facturacion.api.modules.guide.GuideEndpoints;
import pe.facturacion.api.modules.retper.RetPerEndpoints;
import pe.facturacion.api.modules.retper.RetPerKind;
import pe.facturacion.api.modules.settlement.SettlementEndpoints;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.ticket.SummaryEndpoints;
import pe.facturacion.api.shared.http.Input;

/**
 * Emisión de comprobantes en nombre de una empresa desde el panel (fase D, §11.7 del diseño), permiso
 * {@code Admin.Documents.Issue} (superadmin y operador). Cada {@code POST} llama al <b>mismo</b> {@code store} de
 * {@code /api/v1} con la empresa de la ruta y el mismo cuerpo JSON: validación completa del FormRequest, cupo del plan
 * y {@code EmissionPolicy}, bloqueo y avance del correlativo, contador de uso del mes, envío encolado, webhooks. Nunca
 * un atajo con el cuerpo sin validar como la web de la referencia (§2.4-2 de la comparativa).
 *
 * <p>La cabecera {@code Idempotency-Key} es obligatoria ({@link AdminIdempotency}). La empresa debe estar activa y cada
 * emisión cuenta en los límites por minuto del administrador y de la empresa. Se audita como
 * {@code admin.documento.emitido} con el id, número y estado del comprobante creado y la clave de idempotencia.
 */
@RestController
@RequestMapping("/api/admin/tenants/{id}")
@Tag(name = "Panel de administración · emisión de comprobantes")
public class AdminDocumentIssueController {

    static final String ACCION_EMITIDO = "admin.documento.emitido";
    static final String SUMMARY_VOID = "Para anular boletas use POST tenants/{id}/boletas/{docId}/anular: este endpoint solo crea el "
            + "resumen diario de las boletas pendientes de una fecha.";

    private final AdminTenantViews views;
    private final AdminDocumentGuard guard;
    private final AdminIdempotency idempotency;
    private final SaleDocumentEndpoints sales;
    private final GuideEndpoints guides;
    private final RetPerEndpoints retper;
    private final SettlementEndpoints settlements;
    private final SummaryEndpoints summaries;
    private final BuscarDocumentoController buscar;
    private final AdminRateLimiter adminLimit;

    public AdminDocumentIssueController(AdminTenantViews views, AdminDocumentGuard guard, AdminIdempotency idempotency,
            SaleDocumentEndpoints sales, GuideEndpoints guides, RetPerEndpoints retper, SettlementEndpoints settlements,
            SummaryEndpoints summaries, BuscarDocumentoController buscar, AdminRateLimiter adminLimit) {
        this.views = views;
        this.guard = guard;
        this.idempotency = idempotency;
        this.sales = sales;
        this.guides = guides;
        this.retper = retper;
        this.settlements = settlements;
        this.summaries = summaries;
        this.buscar = buscar;
        this.adminLimit = adminLimit;
    }

    @Operation(summary = "Emitir factura, boleta o nota de crédito / débito (cuerpo de POST /api/v1/{tipo}; Idempotency-Key)")
    @AdminPermission(AdminPermissions.DOCUMENTS_ISSUE)
    @PostMapping("/{tipo:" + AdminDocumentRules.SALE_TIPOS + "}")
    public ResponseEntity<Object> emitirVenta(@PathVariable long id, @PathVariable String tipo, HttpServletRequest request) {
        DocumentKind kind = AdminDocumentRules.saleKind(tipo);
        return issue(request, id, tipo, kind.tipoDocumento, (t, in) -> sales.store(kind, t, in));
    }

    @Operation(summary = "Emitir guía de remisión remitente 09 (o 31 con tipo_documento) (cuerpo de POST /api/v1/guias-remision)")
    @AdminPermission(AdminPermissions.DOCUMENTS_ISSUE)
    @PostMapping("/guias-remision")
    public ResponseEntity<Object> emitirGuia(@PathVariable long id, HttpServletRequest request) {
        return issue(request, id, "guias-remision", null, (t, in) -> guides.store(t, in, false));
    }

    @Operation(summary = "Emitir guía de remisión transportista 31 (cuerpo de POST /api/v1/guias-remision-transportista)")
    @AdminPermission(AdminPermissions.DOCUMENTS_ISSUE)
    @PostMapping("/guias-remision-transportista")
    public ResponseEntity<Object> emitirGuiaTransportista(@PathVariable long id, HttpServletRequest request) {
        return issue(request, id, "guias-remision", "31", (t, in) -> guides.store(t, in, true));
    }

    @Operation(summary = "Emitir retención (20) o percepción (40) (cuerpo de POST /api/v1/{tipo})")
    @AdminPermission(AdminPermissions.DOCUMENTS_ISSUE)
    @PostMapping("/{tipo:" + AdminDocumentRules.RETPER_TIPOS + "}")
    public ResponseEntity<Object> emitirRetPer(@PathVariable long id, @PathVariable String tipo, HttpServletRequest request) {
        RetPerKind k = AdminDocumentRules.retPerKind(tipo);
        return issue(request, id, tipo, k.tipoDocumento, (t, in) -> retper.store(k, t, in));
    }

    @Operation(summary = "Emitir liquidación de compra (04) (cuerpo de POST /api/v1/liquidaciones-compra)")
    @AdminPermission(AdminPermissions.DOCUMENTS_ISSUE)
    @PostMapping("/liquidaciones-compra")
    public ResponseEntity<Object> emitirLiquidacion(@PathVariable long id, HttpServletRequest request) {
        return issue(request, id, "liquidaciones-compra", "04", (t, in) -> settlements.store(t, in));
    }

    @Operation(summary = "Resumen diario de las boletas pendientes de una fecha (cuerpo de POST /api/v1/resumenes sin anular)")
    @AdminPermission(AdminPermissions.DOCUMENTS_ISSUE)
    @PostMapping("/resumenes")
    public ResponseEntity<Object> emitirResumen(@PathVariable long id, HttpServletRequest request) {
        return issue(request, id, "resumenes", "RC", (t, in) -> {
            if (AdminDocumentRules.isSummaryVoid(in.all())) {
                throw new AdminException(200, java.util.List.of(SUMMARY_VOID), java.util.List.of(AdminResponse.fieldError("anular", SUMMARY_VOID)));
            }
            return summaries.store(t, in);
        });
    }

    /**
     * Ayuda de los formularios: busca un RUC / DNI entre los clientes de la empresa y, si no está, en SUNAT / RENIEC
     * (como {@code GET /api/v1/buscar-documento}). Cuenta en el límite de consultas del administrador.
     */
    @Operation(summary = "Buscar RUC / DNI en los clientes de la empresa y en SUNAT / RENIEC (tipo, numero)")
    @AdminPermission(AdminPermissions.DOCUMENTS_ISSUE)
    @GetMapping("/buscar-documento")
    public ResponseEntity<Object> buscarDocumento(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        if (!adminLimit.allowSunatQuery(AdminPrincipal.require(request).userId())) {
            throw AdminException.status(429, "Alcanzaste el límite de consultas por minuto. Intenta de nuevo en un momento.");
        }
        ResponseEntity<Object> v1 = buscar.buscar(tenant, views.input(request));
        if (v1.getStatusCode().value() == 404 && v1.getBody() instanceof Map<?, ?> b) {
            return AdminResponse.fail(String.valueOf(b.get("mensaje")));
        }
        return AdminV1Responses.data(v1);
    }

    // ------------------------------------------------------------------ apoyo

    private ResponseEntity<Object> issue(HttpServletRequest request, long id, String vista, String tipo,
            BiFunction<Tenant, Input, ResponseEntity<Object>> call) {
        Tenant tenant = views.tenant(id);
        return idempotency.run(request, tenant, () -> {
            guard.requireActive(tenant);
            guard.requireCanEmit(tenant);
            Input input = views.input(request);
            String motivo = input.string("motivo");
            new AdminValidation().reason(motivo, false).throwIfAny();
            Map<String, Object> forV1 = new LinkedHashMap<>(input.all());
            forV1.remove("motivo");
            guard.consume(request, tenant);
            return guard.call(request, tenant, ACCION_EMITIDO, "Panel: emisión de " + vista + " para la empresa " + tenant.getRuc(),
                    AdminActionAudit.meta("vista", vista, "tipo", tipo, "serie", forV1.get("serie") == null ? null : String.valueOf(forV1.get("serie")),
                            "idempotency_key", AdminIdempotency.storedKey(request), "motivo", motivo),
                    () -> call.apply(tenant, new Input(forV1)));
        });
    }
}
