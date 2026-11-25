package pe.facturacion.api.modules.admin.document;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminActionAudit;
import pe.facturacion.api.modules.admin.api.AdminBody;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminValidation;
import pe.facturacion.api.modules.admin.panel.AdminTenantViews;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.document.DocumentKind;
import pe.facturacion.api.modules.document.SaleDocument;
import pe.facturacion.api.modules.document.SaleDocumentEndpoints;
import pe.facturacion.api.modules.document.SaleDocumentRepository;
import pe.facturacion.api.modules.guide.GuideEndpoints;
import pe.facturacion.api.modules.retper.RetPerEndpoints;
import pe.facturacion.api.modules.retper.RetPerKind;
import pe.facturacion.api.modules.retper.RetPerRepository;
import pe.facturacion.api.modules.settlement.SettlementEndpoints;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.ticket.ReversionController;
import pe.facturacion.api.modules.ticket.SummaryEndpoints;
import pe.facturacion.api.modules.ticket.VoidedEndpoints;
import pe.facturacion.api.shared.http.Input;

/**
 * Acciones ante SUNAT sobre comprobantes ya emitidos de una empresa (fase D, §11.6 del diseño): reenviar, anular
 * (baja RA, resumen de anulación RC o reversión RR), refrescar el estado en SUNAT y corregir un rechazado. Llaman a los
 * mismos servicios de {@code /api/v1} con la empresa de la ruta (mismas validaciones, plazos, bloqueos de numeración y
 * trabajos de la cola); el panel nunca habla con SUNAT por su cuenta ni usa un atajo.
 *
 * <p>Permisos: reenviar {@code Admin.Documents.Resend}, anular {@code Admin.Documents.Void}, refrescar
 * {@code Admin.Documents.Refresh} (los tres también para {@code soporte}) y corregir {@code Admin.Documents.Issue}. El
 * motivo es obligatorio para reenviar, anular y corregir. Cada acción cuenta en los límites por minuto del
 * administrador y de la empresa y se audita ({@code admin.documento.*}). Un documento de otra empresa responde 404.
 */
@RestController
@RequestMapping("/api/admin/tenants/{id}")
@Tag(name = "Panel de administración · acciones ante SUNAT")
public class AdminDocumentActionsController {

    static final String ACCION_REENVIADO = "admin.documento.reenviado";
    static final String ACCION_ANULADO = "admin.documento.anulado";
    static final String ACCION_REFRESCADO = "admin.documento.refrescado";
    static final String ACCION_CORREGIDO = "admin.documento.corregido";

    private final AdminTenantViews views;
    private final AdminDocumentGuard guard;
    private final SaleDocumentEndpoints sales;
    private final SaleDocumentRepository saleRepo;
    private final GuideEndpoints guides;
    private final RetPerEndpoints retper;
    private final RetPerRepository retperRepo;
    private final SettlementEndpoints settlements;
    private final SummaryEndpoints summaries;
    private final VoidedEndpoints voided;
    private final ReversionController reversions;

    public AdminDocumentActionsController(AdminTenantViews views, AdminDocumentGuard guard, SaleDocumentEndpoints sales,
            SaleDocumentRepository saleRepo, GuideEndpoints guides, RetPerEndpoints retper, RetPerRepository retperRepo,
            SettlementEndpoints settlements, SummaryEndpoints summaries, VoidedEndpoints voided, ReversionController reversions) {
        this.views = views;
        this.guard = guard;
        this.sales = sales;
        this.saleRepo = saleRepo;
        this.guides = guides;
        this.retper = retper;
        this.retperRepo = retperRepo;
        this.settlements = settlements;
        this.summaries = summaries;
        this.voided = voided;
        this.reversions = reversions;
    }

    // ------------------------------------------------------------------ reenviar

    @Operation(summary = "Enviar o reenviar a SUNAT una factura, boleta o nota no aceptada (motivo obligatorio)")
    @AdminPermission(AdminPermissions.DOCUMENTS_RESEND)
    @PostMapping("/{tipo:" + AdminDocumentRules.SALE_TIPOS + "}/{docId}/enviar")
    public ResponseEntity<Object> reenviarVenta(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId,
            HttpServletRequest request) {
        DocumentKind kind = AdminDocumentRules.saleKind(tipo);
        return action(request, id, ACCION_REENVIADO, tipo, kind.tipoDocumento, docId, true, "reenvío",
                t -> sales.enviar(kind, t, docId));
    }

    @Operation(summary = "Enviar o reenviar a SUNAT una guía de remisión (motivo obligatorio)")
    @AdminPermission(AdminPermissions.DOCUMENTS_RESEND)
    @PostMapping("/guias-remision/{docId}/enviar")
    public ResponseEntity<Object> reenviarGuia(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        return action(request, id, ACCION_REENVIADO, "guias-remision", null, docId, true, "reenvío", t -> guides.enviar(t, docId));
    }

    @Operation(summary = "Enviar o reenviar a SUNAT una retención o percepción (motivo obligatorio)")
    @AdminPermission(AdminPermissions.DOCUMENTS_RESEND)
    @PostMapping("/{tipo:" + AdminDocumentRules.RETPER_TIPOS + "}/{docId}/enviar")
    public ResponseEntity<Object> reenviarRetPer(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId,
            HttpServletRequest request) {
        RetPerKind k = AdminDocumentRules.retPerKind(tipo);
        return action(request, id, ACCION_REENVIADO, tipo, k.tipoDocumento, docId, true, "reenvío", t -> retper.enviar(k, t, docId));
    }

    @Operation(summary = "Enviar o reenviar a SUNAT una liquidación de compra no aceptada (motivo obligatorio)")
    @AdminPermission(AdminPermissions.DOCUMENTS_RESEND)
    @PostMapping("/liquidaciones-compra/{docId}/enviar")
    public ResponseEntity<Object> reenviarLiquidacion(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        return action(request, id, ACCION_REENVIADO, "liquidaciones-compra", "04", docId, true, "reenvío",
                t -> settlements.enviar(t, docId, false));
    }

    @Operation(summary = "Enviar o reenviar a SUNAT un resumen diario (motivo obligatorio)")
    @AdminPermission(AdminPermissions.DOCUMENTS_RESEND)
    @PostMapping("/resumenes/{docId}/enviar")
    public ResponseEntity<Object> reenviarResumen(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        return action(request, id, ACCION_REENVIADO, "resumenes", "RC", docId, true, "reenvío", t -> summaries.enviar(t, docId));
    }

    @Operation(summary = "Enviar o reenviar a SUNAT una comunicación de baja o reversión (motivo obligatorio)")
    @AdminPermission(AdminPermissions.DOCUMENTS_RESEND)
    @PostMapping("/anulaciones/{docId}/enviar")
    public ResponseEntity<Object> reenviarAnulacion(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        return action(request, id, ACCION_REENVIADO, "anulaciones", "RA", docId, true, "reenvío", t -> voided.enviar(t, docId));
    }

    // ------------------------------------------------------------------ anular

    @Operation(summary = "Anular una factura o nota aceptada por comunicación de baja (RA); motivo obligatorio (va a SUNAT)")
    @AdminPermission(AdminPermissions.DOCUMENTS_VOID)
    @PostMapping("/{tipo:facturas|notas-credito|notas-debito}/{docId}/anular")
    public ResponseEntity<Object> anularVenta(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId,
            HttpServletRequest request) {
        DocumentKind kind = AdminDocumentRules.saleKind(tipo);
        return voidAction(request, id, tipo, kind.tipoDocumento, docId, (t, motivo) -> {
            SaleDocument d = saleDocument(kind, t, docId);
            Map<String, Object> body = AdminDocumentRules.raPayload(kind, d.serie, d.correlativo, d.fechaEmision.toLocalDate(), motivo);
            return voided.store(t, new Input(body), null);
        });
    }

    @Operation(summary = "Anular una boleta aceptada por resumen de anulación (RC); motivo obligatorio (va a SUNAT)")
    @AdminPermission(AdminPermissions.DOCUMENTS_VOID)
    @PostMapping("/boletas/{docId}/anular")
    public ResponseEntity<Object> anularBoleta(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        return voidAction(request, id, "boletas", "03", docId, (t, motivo) -> {
            SaleDocument d = saleDocument(DocumentKind.BOLETA, t, docId);
            return summaries.store(t, new Input(AdminDocumentRules.rcPayload(d.id, d.fechaEmision.toLocalDate(), motivo)));
        });
    }

    @Operation(summary = "Revertir una retención o percepción aceptada (RR); motivo obligatorio (va a SUNAT)")
    @AdminPermission(AdminPermissions.DOCUMENTS_VOID)
    @PostMapping("/{tipo:" + AdminDocumentRules.RETPER_TIPOS + "}/{docId}/revertir")
    public ResponseEntity<Object> revertirRetPer(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId,
            HttpServletRequest request) {
        RetPerKind k = AdminDocumentRules.retPerKind(tipo);
        return voidAction(request, id, tipo, k.tipoDocumento, docId, (t, motivo) -> {
            Map<String, Object> row = retperRepo.find(k, t.getId(), docId).orElseThrow(AdminDocumentActionsController::notFound);
            LocalDate fecha = ((LocalDateTime) row.get("fecha_emision")).toLocalDate();
            Map<String, Object> body = AdminDocumentRules.rrPayload(k, String.valueOf(row.get("serie")), ((Number) row.get("correlativo")).longValue(),
                    fecha, motivo);
            return reversions.store(t, new Input(body));
        });
    }

    @Operation(summary = "Revertir una liquidación de compra aceptada (RR): causal y motivo (3 a 100 caracteres) obligatorios")
    @AdminPermission(AdminPermissions.DOCUMENTS_VOID)
    @PostMapping("/liquidaciones-compra/{docId}/revertir")
    public ResponseEntity<Object> revertirLiquidacion(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        guard.requireActive(tenant);
        AdminBody b = AdminBody.of(request);
        String motivo = b.text("motivo");
        new AdminValidation().reason(motivo, true).throwIfAny();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("causal", b.text("causal"));
        body.put("motivo", motivo);
        guard.consume(request, tenant);
        return guard.call(request, tenant, ACCION_ANULADO, "Panel: reversión de la liquidación de compra " + docId + " de la empresa " + tenant.getRuc(),
                AdminActionAudit.meta("vista", "liquidaciones-compra", "tipo", "04", "documento_id", docId, "operacion", "RR",
                        "causal", b.text("causal"), "motivo", motivo),
                () -> settlements.reversion(tenant, new Input(body), docId));
    }

    // ------------------------------------------------------------------ refrescar

    @Operation(summary = "Consultar en SUNAT el CDR de una factura, boleta o nota pendiente o enviada y guardar el resultado")
    @AdminPermission(AdminPermissions.DOCUMENTS_REFRESH)
    @PostMapping("/{tipo:" + AdminDocumentRules.SALE_TIPOS + "}/{docId}/refrescar")
    public ResponseEntity<Object> refrescarVenta(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId,
            HttpServletRequest request) {
        DocumentKind kind = AdminDocumentRules.saleKind(tipo);
        return action(request, id, ACCION_REFRESCADO, tipo, kind.tipoDocumento, docId, false, "consulta del CDR",
                t -> sales.refreshCdr(kind, t, docId));
    }

    @Operation(summary = "Consultar el ticket de una guía en SUNAT y guardar el resultado")
    @AdminPermission(AdminPermissions.DOCUMENTS_REFRESH)
    @PostMapping("/guias-remision/{docId}/refrescar")
    public ResponseEntity<Object> refrescarGuia(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        return action(request, id, ACCION_REFRESCADO, "guias-remision", null, docId, false, "consulta del ticket",
                t -> guides.checkStatus(t, docId));
    }

    @Operation(summary = "Consultar el ticket de un resumen diario en SUNAT y guardar el resultado")
    @AdminPermission(AdminPermissions.DOCUMENTS_REFRESH)
    @PostMapping("/resumenes/{docId}/refrescar")
    public ResponseEntity<Object> refrescarResumen(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        return action(request, id, ACCION_REFRESCADO, "resumenes", "RC", docId, false, "consulta del ticket",
                t -> summaries.refresh(t, docId));
    }

    @Operation(summary = "Consultar el ticket de una comunicación de baja o reversión en SUNAT y guardar el resultado")
    @AdminPermission(AdminPermissions.DOCUMENTS_REFRESH)
    @PostMapping("/anulaciones/{docId}/refrescar")
    public ResponseEntity<Object> refrescarAnulacion(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        return action(request, id, ACCION_REFRESCADO, "anulaciones", "RA", docId, false, "consulta del ticket",
                t -> voided.checkStatus(t, docId));
    }

    // ------------------------------------------------------------------ corregir un rechazado

    @Operation(summary = "Corregir y reenviar una factura, boleta o nota no aceptada (cuerpo de PUT /api/v1/{tipo}/{id} + motivo)")
    @AdminPermission(AdminPermissions.DOCUMENTS_ISSUE)
    @PutMapping("/{tipo:" + AdminDocumentRules.SALE_TIPOS + "}/{docId}")
    public ResponseEntity<Object> corregirVenta(@PathVariable long id, @PathVariable String tipo, @PathVariable long docId,
            HttpServletRequest request) {
        DocumentKind kind = AdminDocumentRules.saleKind(tipo);
        return fix(request, id, tipo, kind.tipoDocumento, docId, (t, in) -> sales.update(kind, t, in, docId));
    }

    @Operation(summary = "Corregir y reenviar una guía pendiente o rechazada (cuerpo de PUT /api/v1/guias-remision/{id} + motivo)")
    @AdminPermission(AdminPermissions.DOCUMENTS_ISSUE)
    @PutMapping("/guias-remision/{docId}")
    public ResponseEntity<Object> corregirGuia(@PathVariable long id, @PathVariable long docId, HttpServletRequest request) {
        return fix(request, id, "guias-remision", null, docId, (t, in) -> guides.update(t, in, docId));
    }

    // ------------------------------------------------------------------ apoyo

    /** Reenviar o refrescar: cuerpo {@code {motivo}} (obligatorio si {@code reasonRequired}). */
    private ResponseEntity<Object> action(HttpServletRequest request, long id, String accion, String vista, String tipo, long docId,
            boolean reasonRequired, String verbo, java.util.function.Function<Tenant, ResponseEntity<Object>> call) {
        Tenant tenant = views.tenant(id);
        guard.requireActive(tenant);
        String motivo = AdminBody.of(request).text("motivo");
        new AdminValidation().reason(motivo, reasonRequired).throwIfAny();
        guard.consume(request, tenant);
        return guard.call(request, tenant, accion, "Panel: " + verbo + " de " + vista + " " + docId + " de la empresa " + tenant.getRuc(),
                AdminActionAudit.meta("vista", vista, "tipo", tipo, "documento_id", docId, "motivo", motivo), () -> call.apply(tenant));
    }

    /** Anular: cuerpo {@code {motivo}} obligatorio, de hasta {@link AdminDocumentRules#SUNAT_REASON_MAX} caracteres. */
    private ResponseEntity<Object> voidAction(HttpServletRequest request, long id, String vista, String tipo, long docId,
            java.util.function.BiFunction<Tenant, String, ResponseEntity<Object>> call) {
        Tenant tenant = views.tenant(id);
        guard.requireActive(tenant);
        String motivo = AdminBody.of(request).text("motivo");
        AdminValidation v = new AdminValidation().reason(motivo, true);
        v.maxLength("motivo", motivo, AdminDocumentRules.SUNAT_REASON_MAX,
                "El motivo de la anulación no debe superar los " + AdminDocumentRules.SUNAT_REASON_MAX + " caracteres (va a SUNAT).");
        if (v.hasErrors()) {
            v.throwIfAny();
        }
        guard.consume(request, tenant);
        String operacion = "boletas".equals(vista) ? "RC" : "20".equals(tipo) || "40".equals(tipo) ? "RR" : "RA";
        Supplier<ResponseEntity<Object>> v1 = () -> call.apply(tenant, motivo);
        return guard.call(request, tenant, ACCION_ANULADO, "Panel: anulación (" + operacion + ") de " + vista + " " + docId + " de la empresa "
                + tenant.getRuc(), AdminActionAudit.meta("vista", vista, "tipo", tipo, "documento_id", docId, "operacion", operacion, "motivo", motivo), v1);
    }

    /** Corregir: cuerpo de {@code PUT /api/v1/...} con {@code motivo} obligatorio (no se pasa a la API). */
    private ResponseEntity<Object> fix(HttpServletRequest request, long id, String vista, String tipo, long docId,
            java.util.function.BiFunction<Tenant, Input, ResponseEntity<Object>> call) {
        Tenant tenant = views.tenant(id);
        guard.requireActive(tenant);
        Input input = views.input(request);
        String motivo = input.string("motivo");
        new AdminValidation().reason(motivo, true).throwIfAny();
        Map<String, Object> forV1 = new LinkedHashMap<>(input.all());
        forV1.remove("motivo");
        guard.consume(request, tenant);
        return guard.call(request, tenant, ACCION_CORREGIDO, "Panel: corrección y reenvío de " + vista + " " + docId + " de la empresa " + tenant.getRuc(),
                AdminActionAudit.meta("vista", vista, "tipo", tipo, "documento_id", docId, "campos", forV1.keySet().stream().sorted().toList(),
                        "motivo", motivo), () -> call.apply(tenant, new Input(forV1)));
    }

    private SaleDocument saleDocument(DocumentKind kind, Tenant tenant, long docId) {
        return saleRepo.find(kind, tenant.getId(), docId).orElseThrow(AdminDocumentActionsController::notFound);
    }

    static AdminException notFound() {
        return AdminException.status(404, "Recurso no encontrado.");
    }
}
