package pe.facturacion.api.modules.serie;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import pe.facturacion.api.modules.sucursal.Sucursal;
import pe.facturacion.api.modules.sucursal.SucursalRepository;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.security.ApiContext;
import pe.facturacion.api.shared.api.ApiException;
import pe.facturacion.api.shared.api.ApiResponse;
import pe.facturacion.api.shared.http.Input;
import pe.facturacion.api.shared.validation.DatabaseRules;
import pe.facturacion.api.shared.validation.PhpValues;
import pe.facturacion.api.shared.validation.Validator;

/** {@code Route::apiResource('series')} + {@code init-defaults} ({@code SerieController} de API-PRO). */
@RestController
@RequestMapping("/api/v1/series")
@Tag(name = "Series")
public class SerieController {

    private final SerieRepository series;
    private final SucursalRepository sucursales;
    private final DatabaseRules db;
    private final CorrelativoEmitido emitido;

    public SerieController(SerieRepository series, SucursalRepository sucursales, DatabaseRules db, CorrelativoEmitido emitido) {
        this.series = series;
        this.sucursales = sucursales;
        this.db = db;
        this.emitido = emitido;
    }

    @Operation(summary = "Listar series (include_inactive, sucursal_id, tipo)")
    @GetMapping
    public ResponseEntity<Object> index(HttpServletRequest request) {
        Tenant tenant = ApiContext.requireTenant(request);
        return index(tenant, Input.of(request));
    }

    /** Series de la empresa indicada (lo usa también el panel de administración con la misma respuesta). */
    public ResponseEntity<Object> index(Tenant tenant, Input input) {
        Long sucursalId = input.filled("sucursal_id") ? (long) Input.intval(input.get("sucursal_id")) : null;
        String tipo = null;
        if (input.filled("tipo")) {
            tipo = Serie.TIPOS.get(input.string("tipo"));
        }
        List<Serie> list = series.list(tenant.getId(), !input.bool("include_inactive"), sucursalId, tipo);
        Map<Long, Sucursal> suc = sucursalesOf(tenant, list);
        return ApiResponse.success(list.stream().map(s -> format(s, suc.get(s.getSucursalId()))).toList());
    }

    @Operation(summary = "Crear una serie o varias ({\"series\":[...]})")
    @PostMapping
    @Transactional
    public ResponseEntity<Object> store(HttpServletRequest request) {
        return store(ApiContext.requireTenant(request), Input.of(request));
    }

    /** Crear series de la empresa indicada (lo usa también el panel de administración con la misma respuesta). */
    @Transactional
    public ResponseEntity<Object> store(Tenant tenant, Input input) {
        String tiposValidos = String.join(",", Serie.TIPOS.keySet());

        List<Map<String, Object>> items = items(input);

        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("series", "sometimes|array|min:1");
        rules.put("series.*.tipo", "required|string|in:" + tiposValidos);
        rules.put("series.*.serie", List.of("required", "string", "size:4", "regex:/^[A-Z][A-Z0-9]{3}$/"));
        rules.put("series.*.sucursal_id", "required|integer|exists:sucursales,id");
        rules.put("series.*.correlativo_inicial", "sometimes|integer|min:0");
        rules.put("series.*.correlativo_minimo", "sometimes|integer|min:0");
        rules.put("tipo", "sometimes|string|in:" + tiposValidos);
        rules.put("serie", List.of("sometimes", "string", "size:4", "regex:/^[A-Z][A-Z0-9]{3}$/"));
        rules.put("sucursal_id", "sometimes|integer|exists:sucursales,id");
        rules.put("correlativo_inicial", "sometimes|integer|min:0");
        rules.put("correlativo_minimo", "sometimes|integer|min:0");
        Map<String, String> messages = new LinkedHashMap<>();
        messages.put("series.*.tipo.in", "El tipo debe ser uno de: " + tiposValidos);
        messages.put("series.*.serie.regex", "La serie debe ser 4 caracteres (ej: F001, B001).");
        messages.put("series.*.sucursal_id.required", "Debe indicar la sucursal.");
        messages.put("tipo.in", "El tipo debe ser uno de: " + tiposValidos);
        messages.put("serie.regex", "La serie debe ser 4 caracteres (ej: F001, B001).");
        Validator.make(input.all(), rules).messages(messages).database(db).validate();

        Set<Long> ids = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            Object sid = item.get("sucursal_id");
            if (ApiResponse.isTruthy(sid)) { // pluck()->unique()->filter()
                ids.add((long) Input.intval(sid));
            }
        }
        Map<Long, Sucursal> suc = sucursales.findByTenantIdAndIdIn(tenant.getId(), ids).stream()
                .collect(Collectors.toMap(Sucursal::getId, Function.identity()));

        List<Map<String, Object>> creadas = new ArrayList<>();
        List<String> errores = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            Map<String, Object> item = items.get(index);
            Object tipoRaw = item.get("tipo");
            String tipo = tipoRaw == null ? "" : PhpValues.toPhpString(tipoRaw);
            String serieValue = item.get("serie") == null ? "" : PhpValues.toPhpString(item.get("serie")).toUpperCase();
            Object sucursalIdRaw = item.get("sucursal_id");
            int correlativoInicial = item.get("correlativo_inicial") == null ? 0 : Input.intval(item.get("correlativo_inicial"));
            // Piso (sincronización desde el ERP): si viene, el contador nunca baja ni choca con lo emitido; no es error pedir menos
            Integer correlativoMinimo = item.get("correlativo_minimo") == null ? null : Input.intval(item.get("correlativo_minimo"));

            if (tipo.isEmpty() || !Serie.TIPOS.containsKey(tipo)) {
                errores.add("#" + index + ": tipo '" + tipo + "' no válido.");
                continue;
            }
            String tipoCodigo = Serie.TIPOS.get(tipo);

            Sucursal sucursal = sucursalIdRaw == null ? null : suc.get((long) Input.intval(sucursalIdRaw));
            if (sucursal == null) {
                errores.add("#" + index + " (" + serieValue + "): sucursal_id "
                        + (sucursalIdRaw == null ? "" : PhpValues.toPhpString(sucursalIdRaw)) + " no encontrada.");
                continue;
            }
            if (!sucursal.isActive()) {
                errores.add("#" + index + " (" + serieValue + "): sucursal '" + sucursal.getNombre() + "' está desactivada.");
                continue;
            }

            List<String> prefijos = Serie.PREFIJOS.getOrDefault(tipoCodigo, List.of());
            String primeraLetra = serieValue.isEmpty() ? "" : serieValue.substring(0, 1);
            if (!prefijos.isEmpty() && !prefijos.contains(primeraLetra)) {
                String lista = String.join(", ", prefijos.stream().map(p -> p + "XXX").toList());
                errores.add("#" + index + " (" + serieValue + "): para " + tipo + " la serie debe empezar con: " + lista + ".");
                continue;
            }

            Serie existing = series.findByTenantIdAndTipoDocumentoAndSerie(tenant.getId(), tipoCodigo, serieValue).orElse(null);
            if (existing != null && existing.isActive()) {
                errores.add("#" + index + " (" + serieValue + "): ya existe para " + tipo + ".");
                continue;
            }
            // Reactivar una serie desactivada: sus números emitidos siguen declarados
            String bajo = existing != null && correlativoMinimo == null
                    ? emitido.validar(tenant.getId(), tipoCodigo, serieValue, correlativoInicial) : null;
            if (bajo != null) {
                errores.add("#" + index + " (" + serieValue + "): " + bajo);
                continue;
            }
            Serie s = existing != null ? existing : new Serie();
            if (existing == null) {
                s.setTenantId(tenant.getId());
                s.setTipoDocumento(tipoCodigo);
                s.setSerie(serieValue);
            }
            if (correlativoMinimo == null) {
                s.setCorrelativo(correlativoInicial);
            } else if (existing == null) {
                s.setCorrelativo(Math.max(correlativoInicial, correlativoMinimo));
            } else {
                s = series.lockById(existing.getId()).orElseThrow(ApiException::notFound);
                s.setCorrelativo(CorrelativoEmitido.piso(s.getCorrelativo(), correlativoMinimo, emitido.maximo(tenant.getId(), tipoCodigo, serieValue)));
            }
            s.setSucursalId(sucursal.getId());
            s.setSucursalNombre(sucursal.getNombre());
            s.setActive(true);
            series.save(s);
            creadas.add(format(s, sucursal));
        }

        if (!errores.isEmpty() && creadas.isEmpty()) {
            return ApiResponse.error("No se pudo crear ninguna serie.", 422, errores);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("creadas", creadas);
        if (!errores.isEmpty()) {
            response.put("errores", errores);
        }
        return ApiResponse.created(response);
    }

    @Operation(summary = "Ver serie")
    @GetMapping("/{id}")
    public ResponseEntity<Object> show(HttpServletRequest request, @PathVariable long id) {
        Tenant tenant = ApiContext.requireTenant(request);
        Serie s = find(tenant, id);
        return ApiResponse.success(format(s, sucursalOf(tenant, s)));
    }

    @Operation(summary = "Actualizar serie (activo, correlativo, correlativo_minimo [piso: solo sube], sucursal_id)")
    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    @Transactional
    public ResponseEntity<Object> update(HttpServletRequest request, @PathVariable long id) {
        return update(ApiContext.requireTenant(request), Input.of(request), id);
    }

    /** Actualizar una serie de la empresa indicada (sin bajar el correlativo de lo emitido; 404 si es de otra). */
    @Transactional
    public ResponseEntity<Object> update(Tenant tenant, Input input, long id) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("activo", "sometimes|boolean");
        rules.put("correlativo", "sometimes|integer|min:0");
        rules.put("correlativo_minimo", "sometimes|integer|min:0");
        rules.put("sucursal_id", "sometimes|integer|exists:sucursales,id");
        Validator.make(input.all(), rules).database(db).validate();
        if (input.has("correlativo") && input.has("correlativo_minimo")) {
            return ApiResponse.error("Envíe correlativo o correlativo_minimo, no ambos.", 422);
        }

        Serie s = find(tenant, id);
        if (input.filled("sucursal_id")) {
            Sucursal sucursal = sucursales.findByIdAndTenantId((long) Input.intval(input.get("sucursal_id")), tenant.getId()).orElse(null);
            if (sucursal == null) {
                return ApiResponse.error("La sucursal no pertenece a su empresa.", 403);
            }
            s.setSucursalId(sucursal.getId());
            s.setSucursalNombre(sucursal.getNombre());
        }
        if (input.has("correlativo")) {
            // Bloquea la fila como la emisión: ningún comprobante se numera entre la validación y el guardado
            s = series.lockById(s.getId()).orElseThrow(ApiException::notFound);
            int nuevo = Input.intval(input.get("correlativo"));
            String bajo = emitido.validar(tenant.getId(), s.getTipoDocumento(), s.getSerie(), nuevo);
            if (bajo != null) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return ApiResponse.error(bajo, 422);
            }
            s.setCorrelativo(nuevo);
        }
        if (input.has("correlativo_minimo")) {
            // Piso: solo sube. Idempotente; nunca falla por pedir un número menor al actual o al último emitido.
            s = series.lockById(s.getId()).orElseThrow(ApiException::notFound);
            s.setCorrelativo(CorrelativoEmitido.piso(s.getCorrelativo(), Input.intval(input.get("correlativo_minimo")),
                    emitido.maximo(tenant.getId(), s.getTipoDocumento(), s.getSerie())));
        }
        if (input.has("activo")) {
            s.setActive(input.bool("activo"));
        }
        series.save(s);
        return ApiResponse.success(format(s, sucursalOf(tenant, s)), "Serie actualizada.");
    }

    @Operation(summary = "Desactivar serie (borrado lógico)")
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Object> destroy(HttpServletRequest request, @PathVariable long id) {
        return destroy(ApiContext.requireTenant(request), id);
    }

    /** Desactivar una serie de la empresa indicada (borrado lógico; 404 si es de otra). */
    @Transactional
    public ResponseEntity<Object> destroy(Tenant tenant, long id) {
        Serie s = find(tenant, id);
        s.setActive(false);
        series.save(s);
        return ApiResponse.success(null, "Serie desactivada.");
    }

    @Operation(summary = "Crear o actualizar las series por defecto F001 y B001")
    @PostMapping("/init-defaults")
    @Transactional
    public ResponseEntity<Object> initDefaults(HttpServletRequest request) {
        Input input = Input.of(request);
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("serie_factura", List.of("sometimes", "string", "size:4", "regex:/^F[A-Z0-9]{3}$/"));
        rules.put("correlativo_factura", "sometimes|integer|min:0");
        rules.put("serie_boleta", List.of("sometimes", "string", "size:4", "regex:/^B[A-Z0-9]{3}$/"));
        rules.put("correlativo_boleta", "sometimes|integer|min:0");
        Validator.make(input.all(), rules).validate();

        Tenant tenant = ApiContext.requireTenant(request);
        List<Map<String, Object>> result = new ArrayList<>();
        String[][] defaults = {
            {"01", input.string("serie_factura", "F001"), "correlativo_factura"},
            {"03", input.string("serie_boleta", "B001"), "correlativo_boleta"}
        };
        for (String[] d : defaults) {
            String serieValue = d[1] == null ? "" : d[1].toUpperCase();
            int correlativo = Input.intval(input.get(d[2], 0));
            Serie s = series.findByTenantIdAndTipoDocumentoAndSerie(tenant.getId(), d[0], serieValue).orElse(null);
            if (s == null) {
                s = new Serie();
                s.setTenantId(tenant.getId());
                s.setTipoDocumento(d[0]);
                s.setSerie(serieValue);
                s.setCorrelativo(correlativo);
                s.setActive(true);
                series.save(s);
            } else if (input.has(d[2])) {
                s = series.lockById(s.getId()).orElseThrow(ApiException::notFound);
                String bajo = emitido.validar(tenant.getId(), d[0], serieValue, correlativo);
                if (bajo != null) {
                    // La otra serie del par tampoco queda modificada
                    TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                    return ApiResponse.error(bajo, 422);
                }
                s.setCorrelativo(correlativo);
                series.save(s);
            }
            result.add(format(s, sucursalOf(tenant, s)));
        }
        return ApiResponse.success(result, "Series configuradas correctamente.");
    }

    /** {@code $request->input('series', [])} o, si está vacío, el cuerpo completo como un solo ítem. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Input input) {
        Object raw = input.get("series");
        List<Map<String, Object>> items = new ArrayList<>();
        if (raw instanceof List<?> list && !list.isEmpty()) {
            for (Object o : list) {
                items.add(o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>());
            }
        } else if (raw instanceof Map<?, ?> map && !map.isEmpty()) {
            for (Object o : map.values()) {
                items.add(o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>());
            }
        } else {
            items.add(input.all());
        }
        return items;
    }

    private Serie find(Tenant tenant, long id) {
        return series.findByIdAndTenantId(id, tenant.getId()).orElseThrow(ApiException::notFound);
    }

    private Sucursal sucursalOf(Tenant tenant, Serie s) {
        return s.getSucursalId() == null ? null : sucursales.findByIdAndTenantId(s.getSucursalId(), tenant.getId()).orElse(null);
    }

    private Map<Long, Sucursal> sucursalesOf(Tenant tenant, List<Serie> list) {
        Set<Long> ids = list.stream().map(Serie::getSucursalId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        return sucursales.findByTenantIdAndIdIn(tenant.getId(), ids).stream().collect(Collectors.toMap(Sucursal::getId, Function.identity()));
    }

    /** {@code formatSerie()}. */
    static Map<String, Object> format(Serie s, Sucursal sucursal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("tipo", Serie.nombreTipo(s.getTipoDocumento()));
        m.put("tipo_documento", s.getTipoDocumento());
        m.put("serie", s.getSerie());
        m.put("correlativo", s.getCorrelativo());
        m.put("siguiente_numero", s.getSerie() + "-" + (s.getCorrelativo() + 1));
        if (sucursal != null) {
            Map<String, Object> suc = new LinkedHashMap<>();
            suc.put("id", sucursal.getId());
            suc.put("nombre", sucursal.getNombre());
            suc.put("cod_local", sucursal.getCodLocal());
            m.put("sucursal", suc);
        } else {
            m.put("sucursal", null);
        }
        m.put("activo", s.isActive());
        return m;
    }
}
