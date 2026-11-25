package pe.facturacion.api.shared.validation;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import pe.facturacion.api.shared.json.PhpNumbers;

/**
 * Semántica de valores de PHP usada por el validador de Laravel: {@code is_numeric},
 * {@code filter_var(FILTER_VALIDATE_INT)}, conversión {@code (string)}, etc.
 * Los cuerpos JSON llegan como Map/List/String/Integer/Long/BigDecimal/Boolean/null.
 */
public final class PhpValues {

    // is_numeric de PHP 8: espacios iniciales/finales permitidos, signo, decimales y exponente.
    private static final Pattern NUMERIC = Pattern.compile("^[ \\t\\n\\r\\x0B\\f]*[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?[ \\t\\n\\r\\x0B\\f]*$");
    private static final Pattern INT = Pattern.compile("^[ \\t\\n\\r\\x0B\\f]*[+-]?\\d+[ \\t\\n\\r\\x0B\\f]*$");

    private PhpValues() {
    }

    public static boolean isArray(Object v) {
        return v instanceof Map<?, ?> || v instanceof List<?>;
    }

    public static boolean isString(Object v) {
        return v instanceof String;
    }

    public static boolean isNumeric(Object v) {
        if (v instanceof Number) {
            return true;
        }
        return v instanceof String s && NUMERIC.matcher(s).matches();
    }

    /** {@code filter_var($v, FILTER_VALIDATE_INT) !== false}. */
    public static boolean isInteger(Object v) {
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return true;
        }
        if (v instanceof Boolean b) {
            return b; // (string) true === "1"
        }
        if (v instanceof BigDecimal || v instanceof Double || v instanceof Float) {
            return INT.matcher(toPhpString(v)).matches() && fitsLong(toPhpString(v));
        }
        if (v instanceof String s) {
            return INT.matcher(s).matches() && fitsLong(s.trim());
        }
        return false;
    }

    private static boolean fitsLong(String s) {
        try {
            Long.parseLong(s.trim().replace("+", ""));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static BigDecimal toNumber(Object v) {
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (v instanceof Boolean b) {
            return b ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (v instanceof String s && isNumeric(s)) {
            return new BigDecimal(s.trim().replace("+", ""));
        }
        return BigDecimal.ZERO;
    }

    /** Conversión {@code (string) $v} de PHP para escalares. */
    public static String toPhpString(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Boolean b) {
            return b ? "1" : "";
        }
        if (v instanceof BigDecimal bd) {
            // json_decode produce float: "1.50" → 1.5; "2.0" → 2
            return PhpNumbers.formatFloat(bd.doubleValue());
        }
        if (v instanceof Double d) {
            return PhpNumbers.formatFloat(d);
        }
        if (v instanceof Float f) {
            return PhpNumbers.formatFloat(f.doubleValue());
        }
        return v.toString();
    }

    /** {@code count($v)} para arrays PHP. */
    public static int count(Object v) {
        if (v instanceof Map<?, ?> m) {
            return m.size();
        }
        if (v instanceof Collection<?> c) {
            return c.size();
        }
        return 0;
    }

    /** {@code mb_strlen} (puntos de código). */
    public static int mbStrlen(String s) {
        return s.codePointCount(0, s.length());
    }

    /** Valor vacío para {@code required}: null, cadena en blanco o array vacío. */
    public static boolean isEmptyForRequired(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof String s) {
            return s.trim().isEmpty();
        }
        if (isArray(v)) {
            return count(v) < 1;
        }
        return false;
    }
}
