package pe.facturacion.api.modules.admin.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * Convierte la respuesta de un endpoint de {@code /api/v1} (formato Laravel {@code {estado, mensaje, datos}}) al sobre
 * del panel, sin tocar el recurso: {@code data} lleva exactamente lo que la API devuelve a la empresa (snake_case).
 *
 * <ul>
 *   <li>{@link #data}: {@code data} = {@code datos} (detalle, panel, reportes, listas sin paginar).</li>
 *   <li>{@link #page}: listados; {@code data} = el arreglo de filas y {@code totalRecords} = {@code paginacion.total}
 *       (o {@code meta.total}); sin paginación, el tamaño del arreglo.</li>
 *   <li>{@link #whole}: respuestas sin {@code estado} (pagos): {@code data} = el cuerpo completo.</li>
 *   <li>{@link #file}: descargas; se devuelven los mismos bytes, {@code Content-Type} y {@code Content-Disposition}.</li>
 * </ul>
 * Un error de la API se traduce como el resto del panel: 404 → 404 con el sobre; 5xx → 500; el resto (422, 409…) →
 * HTTP 200 con {@code succeeded: false}, y los {@code errores} de validación pasan a {@code errors}.
 */
public final class AdminV1Responses {

    private AdminV1Responses() {
    }

    public static ResponseEntity<Object> data(ResponseEntity<Object> v1) {
        return AdminResponse.ok(successBody(v1).get("datos"));
    }

    public static ResponseEntity<Object> whole(ResponseEntity<Object> v1) {
        return AdminResponse.ok(successBody(v1));
    }

    public static ResponseEntity<Object> page(ResponseEntity<Object> v1) {
        Map<String, Object> body = successBody(v1);
        Object datos = body.get("datos");
        List<?> rows;
        Object pagination;
        if (datos instanceof Map<?, ?> m && m.get("datos") instanceof List<?> l) {
            rows = l;
            pagination = m.get("paginacion");
        } else if (datos instanceof List<?> l) {
            rows = l;
            pagination = body.containsKey("paginacion") ? body.get("paginacion") : body.get("meta");
        } else {
            throw new IllegalStateException("La respuesta de la API no es un listado.");
        }
        long total = pagination instanceof Map<?, ?> p && p.get("total") instanceof Number n ? n.longValue() : rows.size();
        return AdminResponse.page(rows, total);
    }

    /** Descarga: mismos bytes y cabeceras de la API, sin caché en el navegador. */
    public static ResponseEntity<Object> file(ResponseEntity<Object> v1) {
        return file(v1, null);
    }

    /**
     * Descarga con la disposición elegida por el panel: {@code inline = true} para verla en el navegador (vista previa
     * del PDF), {@code false} para forzar la descarga y {@code null} para dejar la de la API. El nombre del archivo no
     * cambia y siguen {@code no-store} y {@code nosniff}.
     */
    public static ResponseEntity<Object> file(ResponseEntity<Object> v1, Boolean inline) {
        Object body = v1.getBody();
        if (!v1.getStatusCode().is2xxSuccessful() || body instanceof Map<?, ?>) {
            throw error(v1.getStatusCode().value(), body);
        }
        byte[] bytes;
        if (body instanceof byte[] b) {
            bytes = b;
        } else if (body instanceof String s) {
            bytes = s.getBytes(StandardCharsets.UTF_8);
        } else {
            throw new IllegalStateException("La respuesta de la API no es un archivo.");
        }
        HttpHeaders headers = new HttpHeaders();
        copy(v1.getHeaders(), headers, HttpHeaders.CONTENT_TYPE);
        copy(v1.getHeaders(), headers, HttpHeaders.CONTENT_DISPOSITION);
        if (inline != null) {
            headers.set(HttpHeaders.CONTENT_DISPOSITION, disposition(headers.getFirst(HttpHeaders.CONTENT_DISPOSITION), inline));
        }
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.setContentLength(bytes.length);
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    /** Cambia el tipo de {@code Content-Disposition} ({@code inline} / {@code attachment}) y conserva el nombre. */
    static String disposition(String original, boolean inline) {
        String type = inline ? "inline" : "attachment";
        if (original == null || original.isBlank()) {
            return type;
        }
        int semi = original.indexOf(';');
        return semi < 0 ? type : type + original.substring(semi);
    }

    private static void copy(HttpHeaders from, HttpHeaders to, String name) {
        String value = from.getFirst(name);
        if (value != null) {
            to.set(name, value);
        }
    }

    /** Copia del mapa sin las claves indicadas (para quitar datos sensibles de un recurso reutilizado). */
    public static Map<String, Object> without(Map<String, Object> resource, Collection<String> keys) {
        Map<String, Object> m = new LinkedHashMap<>(resource);
        keys.forEach(m::remove);
        return m;
    }

    /** Solo las claves indicadas, en ese orden (las que no existan se omiten). */
    public static Map<String, Object> pick(Map<String, Object> resource, Collection<String> keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : keys) {
            if (resource.containsKey(k)) {
                m.put(k, resource.get(k));
            }
        }
        return m;
    }

    /** {@code datos} de una respuesta correcta de la API (para proyectar parte del recurso). */
    public static Object successBodyData(ResponseEntity<Object> v1) {
        return successBody(v1).get("datos");
    }

    /** Cuerpo de una respuesta correcta de la API; si es un error, lanza el error del panel equivalente. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> successBody(ResponseEntity<Object> v1) {
        Object body = v1.getBody();
        boolean ok = v1.getStatusCode().is2xxSuccessful() && body instanceof Map<?, ?> m && !"error".equals(m.get("estado"));
        if (!ok) {
            throw error(v1.getStatusCode().value(), body);
        }
        return (Map<String, Object>) body;
    }

    /**
     * Error de la API ({@code {estado:"error", mensaje, errores?}}) como error del panel: 404 sigue siendo 404, 5xx es
     * 500 y el resto responde HTTP 200 con {@code succeeded:false} (como los errores de negocio del panel).
     */
    public static AdminException error(int status, Object body) {
        String mensaje = body instanceof Map<?, ?> m && m.get("mensaje") != null ? String.valueOf(m.get("mensaje"))
                : status == 404 ? "Recurso no encontrado." : "No se pudo completar la consulta.";
        List<Map<String, Object>> errors = new ArrayList<>();
        if (body instanceof Map<?, ?> m && m.get("errores") instanceof Map<?, ?> fields) {
            for (Map.Entry<?, ?> e : fields.entrySet()) {
                if (e.getValue() instanceof Collection<?> list) {
                    for (Object msg : list) {
                        errors.add(AdminResponse.fieldError(String.valueOf(e.getKey()), String.valueOf(msg)));
                    }
                } else if (e.getValue() != null) {
                    errors.add(AdminResponse.fieldError(String.valueOf(e.getKey()), String.valueOf(e.getValue())));
                }
            }
        }
        List<String> messages = errors.isEmpty() ? List.of(mensaje)
                : errors.stream().map(e -> String.valueOf(e.get("errorMessage"))).toList();
        int code = status == 404 ? 404 : status >= 500 ? 500 : 200;
        return new AdminException(code, messages, errors);
    }
}
