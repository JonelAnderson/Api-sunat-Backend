package pe.facturacion.api.modules.admin.api;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import pe.facturacion.api.shared.http.RequestInput;
import pe.facturacion.api.shared.json.PhpJson;

/**
 * Cuerpo JSON de una petición del panel, leído tal cual (sin el recorte de espacios de Laravel: una contraseña puede
 * empezar o terminar con espacio). Un cuerpo vacío o mal formado es un objeto vacío.
 */
public final class AdminBody {

    private final Map<String, Object> values;

    private AdminBody(Map<String, Object> values) {
        this.values = values;
    }

    public static AdminBody of(HttpServletRequest request) {
        Map<String, Object> m = PhpJson.decodeObject(RequestInput.rawBody(request));
        return new AdminBody(m == null ? Map.of() : m);
    }

    public static AdminBody of(Map<String, Object> values) {
        return new AdminBody(values == null ? Map.of() : values);
    }

    public boolean has(String key) {
        return values.containsKey(key) && values.get(key) != null;
    }

    /** Texto sin modificar (contraseñas). Números y booleanos se pasan a texto; objetos y listas → null. */
    public String raw(String key) {
        Object v = values.get(key);
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        return null;
    }

    /** Texto recortado; vacío → null. */
    public String text(String key) {
        String s = raw(key);
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    /** true / false (también "true" / "false" / 1 / 0); cualquier otra cosa → null. */
    public Boolean bool(String key) {
        Object v = values.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n && (n.intValue() == 0 || n.intValue() == 1) && new BigDecimal(n.toString()).scale() <= 0) {
            return n.intValue() == 1;
        }
        if (v instanceof String s) {
            return switch (s.trim().toLowerCase()) {
                case "true", "1" -> Boolean.TRUE;
                case "false", "0" -> Boolean.FALSE;
                default -> null;
            };
        }
        return null;
    }

    /**
     * Arreglo de enteros (cada elemento, como {@link #integer}); {@code null} si la clave no es un arreglo o algún
     * elemento no es un entero.
     */
    public List<Long> integers(String key) {
        if (!(values.get(key) instanceof List<?> list)) {
            return null;
        }
        List<Long> out = new ArrayList<>();
        for (Object element : list) {
            Long n = toLong(element);
            if (n == null) {
                return null;
            }
            out.add(n);
        }
        return out;
    }

    /** Entero (sin decimales); cualquier otra cosa → null. */
    public Long integer(String key) {
        return toLong(values.get(key));
    }

    private static Long toLong(Object v) {
        try {
            if (v instanceof Number n) {
                BigDecimal d = new BigDecimal(n.toString());
                return d.stripTrailingZeros().scale() <= 0 ? d.longValueExact() : null;
            }
            if (v instanceof String s && s.trim().matches("-?\\d{1,18}")) {
                return Long.parseLong(s.trim());
            }
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
        return null;
    }
}
