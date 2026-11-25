package pe.facturacion.api.modules.consulta;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.validation.Validator;
import pe.facturacion.api.sunat.SunatSender;

/**
 * {@code POST /api/v1/consultar-cdr} ({@code ConsultController::cdrStatus}).
 *
 * <p>Corrección: API-PRO llamaba a {@code getStatus} con el nombre del comprobante como ticket
 * (nunca encontraba nada); aquí se usa {@code getStatusCdr} del servicio de consulta y el CDR se
 * devuelve en base64 ({@code cdr_zip}) en lugar de bytes crudos dentro del JSON.
 */
@RestController
@Tag(name = "Consultas")
public class ConsultaCdrController {

    private static final Logger log = LoggerFactory.getLogger(ConsultaCdrController.class);
    private final SunatSender sender;

    public ConsultaCdrController(SunatSender sender) {
        this.sender = sender;
    }

    @Operation(summary = "Consultar estado y CDR de un comprobante en SUNAT")
    @PostMapping("/api/v1/consultar-cdr")
    public ResponseEntity<Object> cdrStatus(HttpServletRequest http) {
        return cdrStatus(ApiContext.requireTenant(http), Input.of(http));
    }

    /** Consulta de la empresa indicada con su clave SOL guardada (la usa también el panel de administración). No escribe. */
    public ResponseEntity<Object> cdrStatus(Tenant tenant, Input in) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("tipo_documento", "required|string|in:01,03,07,08");
        rules.put("serie", "required|string|size:4");
        rules.put("correlativo", "required|integer|min:1");
        Validator.make(in.all(), rules).validate();
        try {
            SunatSender.CdrStatus r = sender.consultCdr(tenant, Php.toStr(in.get("tipo_documento")), Php.toStr(in.get("serie")),
                    Php.toInt(in.get("correlativo")));
            Map<String, Object> datos = new LinkedHashMap<>();
            datos.put("success", r.success());
            if (r.success()) {
                datos.put("code", r.code());
                datos.put("description", r.description());
                datos.put("notes", r.notes());
                datos.put("accepted", r.accepted());
                datos.put("cdr_zip", r.cdrZip() == null ? null : Base64.getEncoder().encodeToString(r.cdrZip()));
            } else {
                datos.put("error_code", r.code());
                datos.put("error_message", r.message());
            }
            return ApiResponse.success(datos);
        } catch (RuntimeException e) {
            String ref = UUID.randomUUID().toString();
            log.error("Error al consultar CDR. error_id={}", ref, e);
            return ApiResponse.error("Error al consultar CDR. (ref: " + ref + ")", 500, Map.of("error_id", ref));
        }
    }
}
