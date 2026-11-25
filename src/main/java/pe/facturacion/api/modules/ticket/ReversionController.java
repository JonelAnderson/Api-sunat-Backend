package pe.facturacion.api.modules.ticket;

import static pe.facturacion.api.modules.ticket.SummaryEndpoints.asList;
import static pe.facturacion.api.modules.ticket.SummaryEndpoints.baseUrl;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.plan.EmissionPolicy;
import pe.facturacion.api.modules.retper.RetPerKind;
import pe.facturacion.api.modules.retper.RetPerRepository;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.modules.ticket.TicketRepository.TicketDoc;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.shared.validation.Validator;
import pe.facturacion.api.sunat.builder.PhpDates;

/**
 * {@code ReversionController}: Reversión (RR) de retenciones (20) y percepciones (40). Se guarda
 * en {@code voided_documents} con identificador {@code RR-AAAAMMDD-NNN}; el envío, el ticket y la
 * consulta usan los de la comunicación de baja ({@code /anulaciones/{id}/...}).
 *
 * <p>Correcciones: {@code consulta_estado} apunta a la ruta que existe
 * ({@code /anulaciones/{id}/estado}; API-PRO daba {@code /voided/{id}/status}); el antiduplicado
 * compara el correlativo sin importar su tipo ni los ceros a la izquierda; los documentos siguen
 * {@code anulacion_en_proceso} hasta que SUNAT responde el ticket (API-PRO los marcaba
 * {@code anulado} al recibir el ticket); la fecha de comunicación por defecto es la de Lima (API-PRO
 * usaba la de UTC); la numeración del día se serializa; se guardan solo los campos validados de
 * cada detalle.
 */
@RestController
@RequestMapping("/api/v1/reversiones")
@Tag(name = "Reversiones")
public class ReversionController {

    private static final Logger log = LoggerFactory.getLogger(ReversionController.class);

    private final RetPerRepository retper;
    private final VoidedEndpoints voided;
    private final ReversionService reversions;
    private final EmissionPolicy emission;

    public ReversionController(RetPerRepository retper, VoidedEndpoints voided, ReversionService reversions, EmissionPolicy emission) {
        this.retper = retper;
        this.voided = voided;
        this.reversions = reversions;
        this.emission = emission;
    }

    @Operation(summary = "Crear la reversión de retenciones/percepciones aceptadas y encolar su envío")
    @PostMapping
    public ResponseEntity<Object> store(HttpServletRequest http) {
        Tenant tenant = ApiContext.requireTenant(http);
        return store(tenant, Input.of(http));
    }

    /** Reversión de retenciones o percepciones de la empresa indicada con las mismas reglas (la usa también el panel). */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> store(Tenant tenant, Input in) {
        ResponseEntity<Object> denied = emission.deny(tenant);
        if (denied != null) {
            return denied;
        }
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("fecha_generacion", "required|date");
        rules.put("fecha_comunicacion", "sometimes|date");
        rules.put("detalles", "required|array|min:1");
        rules.put("detalles.*.tipo_documento", "required|string|in:20,40");
        rules.put("detalles.*.serie", "required|string|size:4");
        rules.put("detalles.*.correlativo", "required|string");
        rules.put("detalles.*.motivo", "required|string|max:255");
        Map<String, Object> validated = Validator.make(in.all(), rules).validate();

        List<Object> detalles = new ArrayList<>();
        for (Object o : asList(validated.get("detalles"))) {
            Map<String, Object> d = (Map<String, Object>) o;
            Map<String, Object> det = new LinkedHashMap<>();
            for (String k : List.of("tipo_documento", "serie", "correlativo", "motivo")) {
                det.put(k, d.get(k));
            }
            detalles.add(det);
        }
        LocalDate fechaCom = validated.get("fecha_comunicacion") != null
                ? PhpDates.parseDate(Php.toStr(validated.get("fecha_comunicacion")))
                : Clock.todayLima();
        LocalDate fechaGen = PhpDates.parseDate(Php.toStr(validated.get("fecha_generacion")));
        boolean enviarAuto = in.all().containsKey("enviar_automatico") ? in.bool("enviar_automatico") : true;

        List<String> errores = validarDetalles(tenant.getId(), detalles, fechaGen);
        if (!errores.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("estado", "error");
            body.put("mensaje", "No se puede anular: " + String.join(" ", errores));
            body.put("errores", errores);
            return ResponseEntity.status(422).body(body);
        }
        try {
            TicketDoc v = reversions.crear(tenant.getId(), detalles, fechaGen, fechaCom, enviarAuto);
            Map<String, Object> datos = new LinkedHashMap<>();
            datos.put("voided_id", v.id);
            datos.put("identifier", v.identifier);
            datos.put("correlativo", v.correlativo);
            datos.put("fecha_comunicacion", fechaCom.toString());
            datos.put("total_documentos", detalles.size());
            datos.put("sunat_status", v.sunatStatus);
            datos.put("consulta_estado", baseUrl() + "/api/v1/anulaciones/" + v.id + "/estado");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("estado", "exito");
            body.put("mensaje", enviarAuto ? "Reversión encolada para envío a SUNAT."
                    : "Reversión creada en estado pendiente. Use POST /anulaciones/{id}/enviar para enviarla a SUNAT.");
            body.put("datos", datos);
            return ResponseEntity.status(201).body(body);
        } catch (RuntimeException e) {
            String ref = UUID.randomUUID().toString();
            log.error("No se pudo crear la reversión. error_id={}", ref, e);
            return ApiResponse.error("No se pudo crear la reversión. (ref: " + ref + ")", 500, Map.of("error_id", ref));
        }
    }

    /** {@code validateDetalles()}: el documento existe, está aceptado y no tiene otra reversión vigente. */
    List<String> validarDetalles(long tenantId, List<Object> detalles, LocalDate fechaGeneracion) {
        List<String> errors = new ArrayList<>();
        for (Object o : detalles) {
            Map<?, ?> d = (Map<?, ?>) o;
            String tipo = Php.toStr(d.get("tipo_documento"));
            String serie = Php.toStr(d.get("serie"));
            String correlativo = Php.toStr(d.get("correlativo"));
            RetPerKind kind = RetPerKind.fromTipo(tipo);
            String ref = serie + "-" + correlativo;
            if (kind == null) {
                errors.add("Tipo de documento " + tipo + " no soportado para reversión.");
                continue;
            }
            Map<String, Object> doc = correlativo.trim().matches("\\d+")
                    ? retper.findBySerie(kind, tenantId, serie, Php.toInt(correlativo.trim())).orElse(null)
                    : null;
            if (doc == null) {
                errors.add("Documento " + ref + " no existe.");
                continue;
            }
            String status = Php.toStr(doc.get("sunat_status"));
            if (!"aceptado".equals(status.toLowerCase(Locale.ROOT))) {
                errors.add("Documento " + ref + " no está aceptado por SUNAT (estado: " + status + ").");
                continue;
            }
            String fecha = VoidedEndpoints.fechaDistinta(ref, ((java.time.LocalDateTime) doc.get("fecha_emision")).toLocalDate(), fechaGeneracion);
            if (fecha != null) {
                errors.add(fecha);
                continue;
            }
            TicketDoc duplicate = voided.existeConDetalle(tenantId, "RR-", tipo, serie, correlativo);
            if (duplicate != null) {
                errors.add("Ya existe una reversión para " + ref + " (" + duplicate.identifier + ").");
            }
        }
        return errors;
    }
}
