package pe.facturacion.api.shared.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Middlewares globales de Laravel aplicados a toda la entrada (query + JSON):
 * {@code TrimStrings} (salvo contraseñas) y {@code ConvertEmptyStringsToNull}.
 * Como {@code TransformsRequest}, la excepción se compara con la ruta completa
 * ({@code password} exime solo al campo de primer nivel).
 */
public final class InputSanitizer {

    // Mismo patrón que Illuminate\Foundation\Http\Middleware\TrimStrings (Laravel 11+)
    private static final Pattern TRIM = Pattern.compile("^[\\s\\x{FEFF}\\x{200B}\\x{200E}]+|[\\s\\x{FEFF}\\x{200B}\\x{200E}]+$",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> NO_TRIM = Set.of("current_password", "password", "password_confirmation");

    private InputSanitizer() {
    }

    @SuppressWarnings("unchecked")
    private static Object clean(String path, Object value) {
        if (value instanceof String s) {
            String v = NO_TRIM.contains(path) ? s : TRIM.matcher(s).replaceAll("");
            return v.isEmpty() ? null : v;
        }
        String prefix = path.isEmpty() ? "" : path + ".";
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> out.put(k, clean(prefix + k, v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                out.add(clean(prefix + i, list.get(i)));
            }
            return out;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> cleanAll(Map<String, Object> input) {
        return (Map<String, Object>) clean("", input);
    }
}
