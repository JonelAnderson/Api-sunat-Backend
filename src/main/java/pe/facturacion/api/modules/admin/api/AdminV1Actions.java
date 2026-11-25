package pe.facturacion.api.modules.admin.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;

/**
 * Respuesta de una <b>escritura</b> de {@code /api/v1} (emitir, corregir, reenviar, anular, refrescar, CRUD de
 * sucursales / series / clientes) convertida al sobre del panel (fases C y D, §11 del diseño):
 * <ul>
 *   <li>éxito ({@code 2xx} y {@code estado} distinto de {@code error}): HTTP 200, {@code succeeded: true},
 *       {@code data} = {@code datos} de la API (snake_case, tal cual) o, si la respuesta no trae {@code datos}, el
 *       resto del cuerpo sin {@code estado} ni {@code mensaje}; {@code messages} = {@code mensaje} más el texto de cada
 *       {@code advertencias[].mensaje};</li>
 *   <li>error: la misma regla de las vistas (§9.1): 404 → 404, 5xx → 500, el resto (422, 409, 429 del cupo del
 *       plan…) → HTTP 200 con {@code succeeded: false}.</li>
 * </ul>
 * Además expone lo que la auditoría necesita del resultado (id, número y estado SUNAT del documento).
 */
public final class AdminV1Actions {

    private AdminV1Actions() {
    }

    /** Sobre del panel para la respuesta de una escritura de {@code /api/v1}; si es un error, lanza el del panel. */
    public static ResponseEntity<Object> envelope(ResponseEntity<Object> v1) {
        return ResponseEntity.ok(AdminResponse.body(true, messages(successBody(v1)), data(v1), 0, List.of()));
    }

    /** {@code data} de una respuesta correcta (ver la clase); lanza el error del panel si la respuesta es un error. */
    public static Object data(ResponseEntity<Object> v1) {
        Map<String, Object> body = successBody(v1);
        if (body.containsKey("datos")) {
            return body.get("datos");
        }
        Map<String, Object> rest = new LinkedHashMap<>(body);
        rest.remove("estado");
        rest.remove("mensaje");
        return rest;
    }

    /** {@code mensaje} y las advertencias de una respuesta correcta. */
    static List<String> messages(Map<String, Object> body) {
        List<String> out = new ArrayList<>();
        if (body.get("mensaje") != null) {
            out.add(String.valueOf(body.get("mensaje")));
        }
        if (body.get("advertencias") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("mensaje") != null) {
                    out.add(String.valueOf(m.get("mensaje")));
                }
            }
        }
        return out;
    }

    /** true si la respuesta de la API es correcta (no lanza). */
    public static boolean ok(ResponseEntity<Object> v1) {
        return v1.getStatusCode().is2xxSuccessful() && v1.getBody() instanceof Map<?, ?> m && !"error".equals(m.get("estado"));
    }

    private static Map<String, Object> successBody(ResponseEntity<Object> v1) {
        return AdminV1Responses.successBody(v1);
    }

    // ------------------------------------------------------------------ resultado para la auditoría

    /** Id del documento creado o actualizado ({@code id}, {@code id_resumen}, {@code id_anulacion} o {@code voided_id}). */
    public static Long documentId(Object data) {
        if (data instanceof Map<?, ?> m) {
            for (String k : List.of("id", "id_resumen", "id_anulacion", "voided_id")) {
                if (m.get(k) instanceof Number n) {
                    return n.longValue();
                }
            }
        }
        return null;
    }

    /** Número o identificador del documento ({@code numero_completo}, {@code numero} o {@code identifier}). */
    public static String documentNumber(Object data) {
        if (data instanceof Map<?, ?> m) {
            for (String k : List.of("numero_completo", "numero", "identifier")) {
                if (m.get(k) != null) {
                    return String.valueOf(m.get(k));
                }
            }
        }
        return null;
    }

    /** Estado SUNAT del resultado ({@code sunat.estado}, {@code sunat_status} o {@code estado_sunat}). */
    public static String sunatStatus(Object data) {
        if (data instanceof Map<?, ?> m) {
            if (m.get("sunat") instanceof Map<?, ?> s && s.get("estado") != null) {
                return String.valueOf(s.get("estado"));
            }
            for (String k : List.of("sunat_status", "estado_sunat")) {
                if (m.get(k) != null) {
                    return String.valueOf(m.get(k));
                }
            }
        }
        return null;
    }
}
