package pe.facturacion.api.modules.admin.panel;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.api.AdminV1Responses;
import pe.facturacion.api.modules.admin.security.AdminPermission;
import pe.facturacion.api.modules.admin.security.AdminPermissions;
import pe.facturacion.api.modules.admin.security.AdminPrincipal;
import pe.facturacion.api.modules.admin.security.AdminRateLimiter;
import pe.facturacion.api.modules.consulta.ConsultaCdrController;
import pe.facturacion.api.modules.consulta.CpeConsultaController;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.RateLimitInterceptor;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.php.Php;

/**
 * Consultas en vivo a SUNAT de una empresa desde el panel (decisión 2026-11-24): consulta integrada de CPE y estado /
 * CDR de un comprobante. Llaman al <b>mismo</b> código de {@code GET /api/v1/consultar-cpe} y
 * {@code POST /api/v1/consultar-cdr} con la empresa de la ruta, así que usan sus credenciales guardadas en el servidor
 * (el administrador nunca las ve), el mismo token en Redis y los mismos tiempos de espera. No escriben nada.
 *
 * <p>Límites: cada consulta cuenta en la ventana por minuto de la empresa (la de {@code /api/v1}, según su plan) y en la
 * del administrador ({@link AdminRateLimiter#SUNAT_QUERIES_PER_MINUTE}). Cada consulta que llega al servicio se audita
 * ({@code admin.empresa.consulta_sunat}), también si SUNAT respondió con error; una validación fallida no.
 */
@RestController
@RequestMapping("/api/admin/tenants/{id}")
@Tag(name = "Panel de administración · consultas en vivo a SUNAT")
public class AdminTenantSunatController {

    static final String LIMIT_TENANT = "La empresa alcanzó su límite de solicitudes por minuto. Intenta de nuevo en un momento.";
    static final String LIMIT_ADMIN = "Alcanzaste el límite de consultas a SUNAT por minuto. Intenta de nuevo en un momento.";

    private final AdminTenantViews views;
    private final CpeConsultaController cpe;
    private final ConsultaCdrController cdr;
    private final RateLimitInterceptor tenantLimit;
    private final AdminRateLimiter adminLimit;

    public AdminTenantSunatController(AdminTenantViews views, CpeConsultaController cpe, ConsultaCdrController cdr,
            RateLimitInterceptor tenantLimit, AdminRateLimiter adminLimit) {
        this.views = views;
        this.cpe = cpe;
        this.cdr = cdr;
        this.tenantLimit = tenantLimit;
        this.adminLimit = adminLimit;
    }

    @Operation(summary = "Consulta integrada de CPE en SUNAT (tipo_doc, serie, correlativo, fecha_emision dd/mm/aaaa, monto, ruc_emisor?)")
    @AdminPermission(AdminPermissions.SUNAT_QUERY)
    @GetMapping("/consultar-cpe")
    public ResponseEntity<Object> consultarCpe(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        Input in = views.input(request);
        return run(request, tenant, "consultar-cpe", in, List.of("tipo_doc", "serie", "correlativo"), () -> cpe.consultar(tenant, in));
    }

    @Operation(summary = "Estado y CDR de un comprobante en SUNAT (tipo_documento 01|03|07|08, serie, correlativo); no guarda nada")
    @AdminPermission(AdminPermissions.SUNAT_QUERY)
    @GetMapping("/consultar-cdr")
    public ResponseEntity<Object> consultarCdr(@PathVariable long id, HttpServletRequest request) {
        Tenant tenant = views.tenant(id);
        Input in = views.input(request);
        return run(request, tenant, "consultar-cdr", in, List.of("tipo_documento", "serie", "correlativo"), () -> cdr.cdrStatus(tenant, in));
    }

    private ResponseEntity<Object> run(HttpServletRequest request, Tenant tenant, String vista, Input in, List<String> keys,
            Supplier<ResponseEntity<Object>> call) {
        AdminPrincipal me = AdminPrincipal.require(request);
        if (!adminLimit.allowSunatQuery(me.userId())) {
            throw AdminException.status(429, LIMIT_ADMIN);
        }
        boolean allowed;
        try {
            allowed = tenantLimit.consumeTenant(tenant);
        } catch (RuntimeException e) {
            throw AdminException.status(503, "No se pudo verificar el límite de solicitudes de la empresa. Intenta de nuevo.");
        }
        if (!allowed) {
            throw AdminException.status(429, LIMIT_TENANT);
        }
        ResponseEntity<Object> v1 = call.get();
        views.auditSunatQuery(request, tenant, vista, meta(in, keys, v1));
        return AdminV1Responses.data(v1);
    }

    /** Documento consultado y resultado (código HTTP y, si hubo respuesta, lo esencial); nunca credenciales ni el CDR. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> meta(Input in, List<String> keys, ResponseEntity<Object> v1) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : keys) {
            String v = Php.toStr(in.get(k));
            m.put(k, v.length() > 20 ? v.substring(0, 20) : v);
        }
        int status = v1.getStatusCode().value();
        m.put("http", status);
        Object datos = v1.getBody() instanceof Map<?, ?> b ? b.get("datos") : null;
        if (status < 300 && datos instanceof Map<?, ?> d) {
            Map<String, Object> data = (Map<String, Object>) d;
            for (String k : List.of("encontrado", "estado_cp", "success", "code", "accepted")) {
                if (data.containsKey(k)) {
                    m.put(k, data.get(k));
                }
            }
        }
        return m;
    }
}
