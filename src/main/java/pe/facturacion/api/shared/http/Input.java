package pe.facturacion.api.shared.http;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;
import pe.facturacion.api.shared.validation.DataPath;
import pe.facturacion.api.shared.validation.PhpValues;

/**
 * Entrada de la petición con los helpers de {@code Illuminate\Http\Request} que usan los
 * controladores de API-PRO: {@code input}, {@code has}, {@code filled}, {@code boolean},
 * {@code integer}, {@code only}, {@code file}.
 */
public final class Input {

    private final Map<String, Object> data;

    public Input(Map<String, Object> data) {
        this.data = data;
    }

    public static Input of(HttpServletRequest request) {
        return new Input(RequestInput.all(request));
    }

    public Map<String, Object> all() {
        return data;
    }

    /** {@code $request->input($key)}: valor o null (acepta notación de puntos). */
    public Object get(String key) {
        return DataPath.getOrNull(data, key);
    }

    public Object get(String key, Object defaultValue) {
        Object v = DataPath.get(data, key);
        return v == DataPath.MISSING ? defaultValue : v;
    }

    public String string(String key) {
        Object v = get(key);
        return v == null ? null : PhpValues.toPhpString(v);
    }

    public String string(String key, String defaultValue) {
        Object v = DataPath.get(data, key);
        if (v == DataPath.MISSING) {
            return defaultValue;
        }
        return v == null ? null : PhpValues.toPhpString(v);
    }

    /** {@code $request->has($key)}: la clave existe aunque su valor sea null. */
    public boolean has(String key) {
        return DataPath.has(data, key);
    }

    /** {@code $request->filled($key)}: existe y no es una cadena vacía (los arrays siempre cuentan). */
    public boolean filled(String key) {
        Object v = get(key);
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean || PhpValues.isArray(v) || v instanceof MultipartFile) {
            return true;
        }
        return !PhpValues.toPhpString(v).trim().isEmpty();
    }

    /** {@code $request->boolean($key)}: {@code FILTER_VALIDATE_BOOLEAN}. */
    public boolean bool(String key) {
        Object v = get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return false;
        }
        String s = PhpValues.toPhpString(v).trim().toLowerCase();
        return s.equals("1") || s.equals("true") || s.equals("on") || s.equals("yes");
    }

    /** {@code $request->integer($key, $default)}: {@code intval()} del valor. */
    public int integer(String key, int defaultValue) {
        Object v = DataPath.get(data, key);
        if (v == DataPath.MISSING) {
            return defaultValue;
        }
        return intval(v);
    }

    /** {@code intval()} de PHP. */
    public static int intval(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof Number n) {
            return (int) n.doubleValue();
        }
        String s = PhpValues.toPhpString(v).trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^[+-]?\\d+").matcher(s);
        if (!m.find()) {
            return 0;
        }
        try {
            long l = Long.parseLong(m.group().replace("+", ""));
            return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, l));
        } catch (NumberFormatException e) {
            return m.group().startsWith("-") ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        }
    }

    /** {@code $request->only([...])}: solo las claves presentes. */
    public Map<String, Object> only(Collection<String> keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : keys) {
            if (has(k)) {
                out.put(k, get(k));
            }
        }
        return out;
    }

    public Map<String, Object> only(String... keys) {
        return only(List.of(keys));
    }

    public MultipartFile file(String key) {
        Object v = get(key);
        return v instanceof MultipartFile f && !f.isEmpty() ? f : null;
    }

    public boolean hasFile(String key) {
        return file(key) != null;
    }
}
