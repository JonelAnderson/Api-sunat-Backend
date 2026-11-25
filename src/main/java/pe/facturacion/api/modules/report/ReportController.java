package pe.facturacion.api.modules.report;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.json.PhpJson;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.time.Clock;
import pe.facturacion.api.shared.validation.CustomRule;
import pe.facturacion.api.shared.validation.Validator;

/**
 * Reportes ({@code ReportController} + {@code ReportRequest} de API-PRO): JSON (en caché 5 minutos)
 * o PDF con {@code formato=pdf}. {@code reportes/documentos-internos} no existe: era solo de
 * cotizaciones y notas de venta (fuera de alcance).
 *
 * <p>Correcciones: {@code sucursal_id} debe ser una sucursal de la empresa (API-PRO aceptaba la de
 * otra); una fecha válida para Laravel pero no AAAA-MM-DD responde 422 (API-PRO daba 500 al
 * calcular el rango); la hora del PDF es la de Lima.
 */
@RestController
@RequestMapping("/api/v1/reportes")
@Tag(name = "Reportes")
public class ReportController {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final ReportService reports;
    private final ReportPdf pdf;
    private final StringRedisTemplate redis;
    private final NamedParameterJdbcTemplate jdbc;

    public ReportController(ReportService reports, ReportPdf pdf, StringRedisTemplate redis, NamedParameterJdbcTemplate jdbc) {
        this.reports = reports;
        this.pdf = pdf;
        this.redis = redis;
        this.jdbc = jdbc;
    }

    @Operation(summary = "Registro de ventas (facturas, boletas y notas) del periodo")
    @GetMapping("/registro-ventas")
    public ResponseEntity<Object> registroVentas(HttpServletRequest http) {
        Tenant t = ApiContext.requireTenant(http);
        return registroVentas(t, Input.of(http));
    }

    public ResponseEntity<Object> registroVentas(Tenant t, Input in) {
        return report(t, in, "registro-ventas", "registro_ventas", "registroVentas", "Registro de ventas generado.", true, reports::registroVentas);
    }

    @Operation(summary = "Ventas consolidado: indicadores y desgloses (agrupacion=dia|semana|mes)")
    @GetMapping("/ventas-consolidado")
    public ResponseEntity<Object> ventasConsolidado(HttpServletRequest http) {
        Tenant t = ApiContext.requireTenant(http);
        return ventasConsolidado(t, Input.of(http));
    }

    public ResponseEntity<Object> ventasConsolidado(Tenant t, Input in) {
        return report(t, in, "ventas-consolidado", "ventas_consolidado", "ventasConsolidado", "Ventas consolidado generado.", false,
                reports::ventasConsolidado);
    }

    @Operation(summary = "Notas de crédito y débito del periodo")
    @GetMapping("/notas")
    public ResponseEntity<Object> notas(HttpServletRequest http) {
        return report(ApiContext.requireTenant(http), Input.of(http), "notas-credito-debito", "notas", "notas", "Reporte de notas generado.", false, reports::notas);
    }

    @Operation(summary = "Cobranzas y cuentas por cobrar")
    @GetMapping("/cobranzas")
    public ResponseEntity<Object> cobranzas(HttpServletRequest http) {
        return report(ApiContext.requireTenant(http), Input.of(http), "cobranzas", "cobranzas", "cobranzas", "Reporte de cobranzas generado.", false, reports::cobranzas);
    }

    @Operation(summary = "Estado de cuenta de un cliente (client_num_doc)")
    @GetMapping("/por-cliente")
    public ResponseEntity<Object> porCliente(HttpServletRequest http) {
        Tenant t = ApiContext.requireTenant(http);
        return porCliente(t, Input.of(http));
    }

    public ResponseEntity<Object> porCliente(Tenant t, Input in) {
        Map<String, Object> f = validar(in, t);
        if (Php.empty(f.get("client_num_doc"))) {
            return ApiResponse.error("El filtro client_num_doc es requerido para este reporte.", 422);
        }
        return report(t, in, "estado-cuenta-" + f.get("client_num_doc"), "por_cliente", "porCliente", "Estado de cuenta generado.", false,
                reports::porCliente);
    }

    @Operation(summary = "Comparativo por sucursal")
    @GetMapping("/por-sucursal")
    public ResponseEntity<Object> porSucursal(HttpServletRequest http) {
        return report(ApiContext.requireTenant(http), Input.of(http), "comparativo-sucursales", "por_sucursal", "porSucursal", "Comparativo por sucursal generado.", true, reports::porSucursal);
    }

    /** {@code cachedReport}: PDF sin caché; JSON en caché 5 minutos por empresa, reporte y filtros. */
    private ResponseEntity<Object> report(Tenant t, Input in, String pdfName, String view, String method, String mensaje, boolean landscape,
            BiFunction<Tenant, Map<String, Object>, Map<String, Object>> build) {
        Map<String, Object> f = validar(in, t);
        if ("pdf".equals(f.get("formato"))) {
            byte[] content = pdf.generate(view, build.apply(t, f), t, landscape);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + pdfName + "-" + Clock.todayLima() + ".pdf\"")
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                    .body(content);
        }
        String key = "report:" + t.getId() + ":" + method + ":" + md5(PhpJson.encode(f));
        String cached = redis.opsForValue().get(key);
        Object data;
        if (cached != null) {
            data = PhpJson.decode(cached);
        } else {
            data = build.apply(t, f);
            redis.opsForValue().set(key, PhpJson.encode(data), CACHE_TTL);
        }
        return ApiResponse.success(data, mensaje);
    }

    /** {@code ReportRequest}: reglas, rango máximo de 366 días y sucursal de la empresa. */
    Map<String, Object> validar(Input in, Tenant t) {
        CustomRule sucursal = (attribute, value, v) -> {
            Long n = jdbc.queryForObject("SELECT count(*) FROM sucursales WHERE id = :id AND tenant_id = :t",
                    new MapSqlParameterSource("id", Php.toInt(value)).addValue("t", t.getId()), Long.class);
            return n != null && n > 0 ? null : "El campo :attribute no existe.";
        };
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("fecha_desde", "required|date");
        rules.put("fecha_hasta", "required|date|after_or_equal:fecha_desde");
        rules.put("sucursal_id", List.of("nullable", "integer", sucursal));
        rules.put("serie", "nullable|string|size:4");
        rules.put("client_num_doc", "nullable|string|max:15");
        rules.put("estado_sunat", "nullable|string|in:pendiente,enviado,aceptado,rechazado");
        rules.put("estado_pago", "nullable|string|in:pendiente,parcial,pagado");
        rules.put("tipo_moneda", "nullable|string|in:PEN,USD");
        rules.put("formato", "nullable|string|in:json,pdf");
        rules.put("agrupacion", "nullable|string|in:dia,semana,mes");
        rules.put("vencido", "nullable|boolean");
        Validator v = Validator.make(in.all(), rules).after(x -> {
            Object d = x.input("fecha_desde");
            Object h = x.input("fecha_hasta");
            if (Php.truthy(d) && Php.truthy(h) && !x.hasError("fecha_desde") && !x.hasError("fecha_hasta")) {
                LocalDate desde = Reports.parseDate(Php.toStr(d));
                LocalDate hasta = Reports.parseDate(Php.toStr(h));
                if (desde == null) {
                    x.addError("fecha_desde", "El campo fecha desde debe tener el formato AAAA-MM-DD.");
                } else if (hasta == null) {
                    x.addError("fecha_hasta", "El campo fecha hasta debe tener el formato AAAA-MM-DD.");
                } else if (ChronoUnit.DAYS.between(desde, hasta) > 366) {
                    x.addError("fecha_hasta", "El rango máximo permitido es de 1 año (366 días).");
                }
            }
        });
        return v.validate();
    }

    private static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
