package pe.facturacion.api.shared.php;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conversiones y comparaciones con la semántica de PHP, para portar la lógica de cálculo
 * de API-PRO sin cambiar resultados. Los valores siguen el modelo de {@code PhpJson}:
 * {@code null}, {@link Boolean}, {@link Long}/{@link Integer}, {@link Double} o
 * {@link BigDecimal} (float de JSON), {@link String}, {@link List}, {@link Map}.
 */
public final class Php {

    /** Prefijo numérico que reconoce {@code strtod} (con espacios iniciales). */
    private static final Pattern LEADING_NUMBER =
            Pattern.compile("^[ \\t\\n\\r\\x0B\\f]*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)");

    private Php() {
    }

    /** {@code (float) $v}. */
    public static double toFloat(Object v) {
        if (v == null) {
            return 0.0;
        }
        if (v instanceof Double d) {
            return d;
        }
        if (v instanceof BigDecimal bd) {
            return bd.doubleValue();
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        if (v instanceof String s) {
            Matcher m = LEADING_NUMBER.matcher(s);
            return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
        }
        if (v instanceof Collection<?> c) {
            return c.isEmpty() ? 0.0 : 1.0;
        }
        if (v instanceof Map<?, ?> m) {
            return m.isEmpty() ? 0.0 : 1.0;
        }
        return 0.0;
    }

    /** {@code (int) $v}. */
    public static long toInt(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        if (v instanceof BigInteger bi) {
            return bi.longValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof String s) {
            Matcher m = Pattern.compile("^[ \\t\\n\\r\\x0B\\f]*([+-]?\\d+)").matcher(s);
            if (!m.find()) {
                return 0;
            }
            double d = toFloat(s);
            if (Math.abs(d) < 9.2e18) {
                return (long) d;
            }
            return 0;
        }
        double d = toFloat(v);
        if (!Double.isFinite(d)) {
            return 0;
        }
        return (long) d;
    }

    /** Valor entero de PHP ({@code is_int}). */
    public static boolean isInt(Object v) {
        return v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte
                || v instanceof BigInteger;
    }

    /** Valor float de PHP ({@code is_float}). */
    public static boolean isFloat(Object v) {
        return v instanceof Double || v instanceof Float || v instanceof BigDecimal;
    }

    /** {@code empty($v)} / negación de la verdad de PHP y Twig. */
    public static boolean empty(Object v) {
        return !truthy(v);
    }

    /** Verdad de PHP ({@code if ($v)}) y de Twig ({@code {% if v %}}). */
    public static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (isInt(v)) {
            return ((Number) v).longValue() != 0;
        }
        if (isFloat(v)) {
            return toFloat(v) != 0.0;
        }
        if (v instanceof String s) {
            return !s.isEmpty() && !"0".equals(s);
        }
        if (v instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    /** {@code (string) $v} (floats con precision 14). */
    public static String toStr(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Boolean b) {
            return b ? "1" : "";
        }
        if (isFloat(v)) {
            return PhpMath.toStr(toFloat(v));
        }
        if (v instanceof Collection<?> || v instanceof Map<?, ?>) {
            return "Array";
        }
        return v.toString();
    }

    /** {@code $a === $b} para una cadena literal. */
    public static boolean strictEq(Object a, String b) {
        return a instanceof String s && s.equals(b);
    }

    /**
     * {@code $v == $b} con {@code $b} cadena (comparación débil de {@code in_array} sin strict):
     * dos cadenas numéricas se comparan como números; un número contra cadena numérica también.
     */
    public static boolean looseEqString(Object v, String b) {
        if (v == null) {
            return b.isEmpty();
        }
        if (v instanceof Boolean bool) {
            return bool == truthy(b);
        }
        if (v instanceof String s) {
            if (isNumericString(s) && isNumericString(b)) {
                return toFloat(s) == toFloat(b);
            }
            return s.equals(b);
        }
        if (v instanceof Number) {
            if (isNumericString(b)) {
                return toFloat(v) == toFloat(b);
            }
            return toStr(v).equals(b);
        }
        return false;
    }

    /** {@code in_array($v, $list)} (comparación débil). */
    public static boolean inArray(Object v, List<String> list) {
        for (String s : list) {
            if (looseEqString(v, s)) {
                return true;
            }
        }
        return false;
    }

    /** {@code is_numeric($s)} para cadenas (permite espacios iniciales y finales). */
    public static boolean isNumericString(String s) {
        return s.matches("[ \\t\\n\\r\\x0B\\f]*[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?[ \\t\\n\\r\\x0B\\f]*");
    }

    /** {@code $arr[$key] ?? $default} sobre un mapa. */
    public static Object coalesce(Map<String, ?> map, String key, Object def) {
        if (map == null) {
            return def;
        }
        Object v = map.get(key);
        return v != null ? v : def;
    }

    /** {@code isset($arr[$key])}. */
    public static boolean isset(Map<String, ?> map, String key) {
        return map != null && map.get(key) != null;
    }

    /** {@code array_sum(array_column($rows, $key))} (int si todos son enteros, si no float). */
    public static Object arraySumColumn(Object rows, String key) {
        boolean allInt = true;
        long li = 0;
        double d = 0;
        if (rows instanceof Collection<?> c) {
            for (Object row : c) {
                if (!(row instanceof Map<?, ?> m) || !m.containsKey(key)) {
                    continue;
                }
                Object v = m.get(key);
                if (allInt && isInt(v)) {
                    li += ((Number) v).longValue();
                } else {
                    if (allInt) {
                        d = li;
                        allInt = false;
                    }
                    d += toNumberForSum(v);
                }
            }
        }
        return allInt ? (Object) li : (Object) d;
    }

    private static double toNumberForSum(Object v) {
        // array_sum suma con la conversión numérica de PHP (las cadenas no numéricas valen 0)
        return toFloat(v);
    }

    /** Número (int o float) de PHP a double. */
    public static double num(Object v) {
        return toFloat(v);
    }
}
