package pe.facturacion.api.modules.portal.tenant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminBody;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminResponse;
import pe.facturacion.api.modules.admin.panel.AdminCertificates;
import pe.facturacion.api.modules.audit.AuditLogService;
import pe.facturacion.api.modules.plan.EmissionPolicy;
import pe.facturacion.api.modules.plan.PanelPlanService;
import pe.facturacion.api.modules.portal.security.PortalPrincipal;
import pe.facturacion.api.modules.portal.security.PortalRateLimiter;
import pe.facturacion.api.modules.portal.user.PortalAuthService;
import pe.facturacion.api.modules.tenant.TenantService;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.time.Clock;

/**
 * Datos propios de una empresa del portal (§5 del diseño): resumen, uso y regenerar las credenciales de la API. La
 * empresa sale siempre de las de la persona ({@link PortalTenantAccess}, 404 si no es suya). Nunca devuelve la clave
 * SOL, el certificado, el {@code api_secret} guardado, el {@code client_secret} ni tokens: las columnas se eligen una
 * por una. La única respuesta con credenciales es la de regenerar, una sola vez.
 */
@RestController
@RequestMapping("/api/portal/tenants/{id}")
@Tag(name = "Portal de clientes · empresa")
public class PortalTenantController {

    static final String CREDENCIALES_AVISO = "Guarda estas credenciales ahora: el api_secret no se volverá a mostrar. Si se pierde, "
            + "regenéralas (las anteriores dejan de servir).";

    private final PortalTenantAccess access;
    private final PanelPlanService panelPlans;
    private final EmissionPolicy emission;
    private final AdminCertificates certificates;
    private final PortalUsage usage;
    private final PortalAuthService auth;
    private final PortalRateLimiter limiter;
    private final TenantService tenantService;
    private final AuditLogService audit;

    public PortalTenantController(PortalTenantAccess access, PanelPlanService panelPlans, EmissionPolicy emission,
            AdminCertificates certificates, PortalUsage usage, PortalAuthService auth, PortalRateLimiter limiter,
            TenantService tenantService, AuditLogService audit) {
        this.access = access;
        this.panelPlans = panelPlans;
        this.emission = emission;
        this.certificates = certificates;
        this.usage = usage;
        this.auth = auth;
        this.limiter = limiter;
        this.tenantService = tenantService;
        this.audit = audit;
    }

    @Operation(summary = "Resumen de la empresa: plan, cupo del mes y estado del certificado")
    @GetMapping("/resumen")
    public ResponseEntity<Object> resumen(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = access.require(request, id);
        PanelPlanService.State plan = panelPlans.state(tenant);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ruc", tenant.getRuc());
        data.put("businessName", tenant.getRazonSocial());
        data.put("tradeName", tenant.getNombreComercial());
        data.put("environment", tenant.getEnvironment());
        data.put("plan", plan.effectivePlan().getSlug());
        data.put("planName", plan.effectivePlan().getName());
        data.put("active", tenant.isActive());
        data.put("billingSuspended", "pago".equals(tenant.getSuspensionKind()));
        data.put("documentsThisMonth", emission.usedThisMonth(tenant));
        // -1 = ilimitado (el mismo criterio del cupo del panel)
        data.put("maxDocumentsMonth", plan.effectiveLimit());
        AdminCertificates.Row cert = certificates.row(tenant, certificates.defaultDays());
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("status", cert.status());
        c.put("expiresOn", cert.expiresOn() == null ? null : cert.expiresOn().toString());
        c.put("daysLeft", cert.daysLeft());
        data.put("certificate", c);
        return AdminResponse.ok(data);
    }

    @Operation(summary = "Uso: comprobantes del mes (el que cuenta el cupo) y de los 5 meses anteriores")
    @GetMapping("/uso")
    public ResponseEntity<Object> uso(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = access.require(request, id);
        PanelPlanService.State plan = panelPlans.state(tenant);
        YearMonth month = YearMonth.from(Clock.nowUtc());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("month", month.toString());
        data.put("documents", emission.usedThisMonth(tenant));
        data.put("maxDocuments", plan.effectiveLimit());
        data.put("history", usage.history(tenant.getId(), month));
        return AdminResponse.ok(data);
    }

    @Operation(summary = "Regenera api_key y api_secret (contraseña + código TOTP; 3 por hora y empresa); el secreto se muestra una sola vez")
    @PostMapping("/credenciales/regenerar")
    public ResponseEntity<Object> regenerar(@PathVariable long id, HttpServletRequest request) {
        PortalPrincipal me = PortalPrincipal.require(request);
        Tenant tenant = access.require(request, id);
        if (!tenant.isActive()) {
            throw AdminException.fail("La empresa está suspendida: no se pueden regenerar sus credenciales. Comunícate con soporte.");
        }
        AdminBody b = AdminBody.of(request);
        // Paso de seguridad en el mismo pedido: contraseña y código TOTP vigente. Un fallo cuenta para el bloqueo.
        auth.stepUp(me.userId(), b.raw("password"), b.text("code"), request);
        switch (limiter.takeRegeneration(tenant.getId())) {
            case EXHAUSTED -> throw AdminException.fail("Esta empresa ya regeneró sus credenciales "
                    + PortalRateLimiter.REGENERATIONS_PER_HOUR + " veces en la última hora. Inténtalo más tarde.");
            case UNAVAILABLE -> throw AdminException.status(503, "No se pudo verificar el límite de regeneraciones. Inténtalo más tarde.");
            case GRANTED -> {
            }
        }
        TenantService.ApiKeyPair pair = tenantService.rotateApiCredentials(tenant);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("accion", "regeneradas");
        meta.put("portal_user_id", me.userId());
        audit.registrarPortal(request, "portal", me.email(), "portal.credenciales.regeneradas", tenant.getId(), AuditLogService.SUBJECT_TENANT,
                tenant.getId(), "Portal: regeneró api_key y api_secret de la empresa " + tenant.getRuc() + ". Las anteriores quedaron inválidas.", meta);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("apiKey", pair.apiKey());
        data.put("apiSecret", pair.apiSecret());
        data.put("aviso", CREDENCIALES_AVISO);
        // Con secretos: sin caché en el navegador ni en intermediarios
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Pragma", "no-cache")
                .body(AdminResponse.body(true, List.of("Credenciales regeneradas. Las anteriores ya no son válidas."), data, 0, List.of()));
    }
}
