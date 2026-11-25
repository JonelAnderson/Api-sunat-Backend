package pe.facturacion.api.shared.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Acceso con notación de puntos ({@code cliente.num_doc}, {@code items.0.unidad}) sobre la
 * entrada (Map/List), equivalente a {@code data_get}/{@code Arr::has}/{@code Arr::set} de Laravel.
 */
public final class DataPath {

    /** Marca de "no existe" (distinta de null, que sí es un valor presente). */
    public static final Object MISSING = new Object() {
        @Override
        public String toString() {
            return "MISSING";
        }
    };

    private DataPath() {
    }

    public static Object get(Object data, String path) {
        Object current = data;
        for (String segment : path.split("\\.", -1)) {
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(segment)) {
                    return MISSING;
                }
                current = map.get(segment);
            } else if (current instanceof List<?> list) {
                int idx = index(segment);
                if (idx < 0 || idx >= list.size()) {
                    return MISSING;
                }
                current = list.get(idx);
            } else {
                return MISSING;
            }
        }
        return current;
    }

    public static Object getOrNull(Object data, String path) {
        Object v = get(data, path);
        return v == MISSING ? null : v;
    }

    public static boolean has(Object data, String path) {
        return get(data, path) != MISSING;
    }

    /**
     * Expande un atributo con comodines contra los datos: {@code items.*.unidad} con dos ítems
     * produce {@code items.0.unidad} e {@code items.1.unidad}. Sin comodines devuelve el mismo atributo.
     */
    public static List<String> expand(Object data, String pattern) {
        List<String> out = new ArrayList<>();
        if (!pattern.contains("*")) {
            out.add(pattern);
            return out;
        }
        expandInto(data, pattern.split("\\.", -1), 0, "", out);
        return out;
    }

    private static void expandInto(Object node, String[] segments, int i, String prefix, List<String> out) {
        if (i == segments.length) {
            out.add(prefix);
            return;
        }
        String seg = segments[i];
        String sep = prefix.isEmpty() ? "" : ".";
        if ("*".equals(seg)) {
            if (node instanceof List<?> list) {
                for (int k = 0; k < list.size(); k++) {
                    expandInto(list.get(k), segments, i + 1, prefix + sep + k, out);
                }
            } else if (node instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    expandInto(e.getValue(), segments, i + 1, prefix + sep + e.getKey(), out);
                }
            }
            return;
        }
        Object child = MISSING;
        if (node instanceof Map<?, ?> map && map.containsKey(seg)) {
            child = map.get(seg);
        } else if (node instanceof List<?> list) {
            int idx = index(seg);
            if (idx >= 0 && idx < list.size()) {
                child = list.get(idx);
            }
        }
        // Laravel inicializa como null los atributos hoja que faltan dentro de un comodín
        // (para que "required" falle); las ramas intermedias inexistentes no se expanden.
        if (child == MISSING && i < segments.length - 1 && containsWildcard(segments, i + 1)) {
            return;
        }
        expandInto(child == MISSING ? null : child, segments, i + 1, prefix + sep + seg, out);
    }

    private static boolean containsWildcard(String[] segments, int from) {
        for (int k = from; k < segments.length; k++) {
            if ("*".equals(segments[k])) {
                return true;
            }
        }
        return false;
    }

    /** {@code Arr::set} creando mapas intermedios; los índices numéricos se convierten luego en listas. */
    @SuppressWarnings("unchecked")
    public static void set(Map<String, Object> target, String path, Object value) {
        String[] segments = path.split("\\.", -1);
        Map<String, Object> current = target;
        for (int i = 0; i < segments.length - 1; i++) {
            Object next = current.get(segments[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                current.put(segments[i], next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(segments[segments.length - 1], value);
    }

    /** Convierte mapas con claves 0..n-1 consecutivas en listas (arrays secuenciales de PHP). */
    @SuppressWarnings("unchecked")
    public static Object normalizeLists(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            m.replaceAll((k, v) -> normalizeLists(v));
            if (!m.isEmpty() && isSequential(m)) {
                return new ArrayList<>(m.values());
            }
            return m;
        }
        if (node instanceof List<?> list) {
            List<Object> l = new ArrayList<>(list.size());
            for (Object o : list) {
                l.add(normalizeLists(o));
            }
            return l;
        }
        return node;
    }

    private static boolean isSequential(Map<String, Object> m) {
        int expected = 0;
        for (String k : m.keySet()) {
            if (!String.valueOf(expected).equals(k)) {
                return false;
            }
            expected++;
        }
        return true;
    }

    private static int index(String segment) {
        if (segment.isEmpty() || segment.length() > 9) {
            return -1;
        }
        for (int i = 0; i < segment.length(); i++) {
            if (!Character.isDigit(segment.charAt(i))) {
                return -1;
            }
        }
        return Integer.parseInt(segment);
    }
}
