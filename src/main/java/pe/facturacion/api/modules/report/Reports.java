package pe.facturacion.api.modules.report;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.Map;
import pe.facturacion.api.shared.php.PhpMath;
import pe.facturacion.api.shared.time.Clock;

/** Utilidades comunes del panel, los reportes y la exportación (semántica de PHP). */
final class Reports {

    static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private Reports() {
    }

    /** {@code round($x, 2)} de PHP. */
    static double round2(double v) {
        return PhpMath.round(v, 2);
    }

    /** Mapa ordenado a partir de pares clave, valor. */
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** {@code mb_substr((string) $v, 0, $n)}: por caracteres, no por bytes. */
    static String mbSubstr(Object v, int n) {
        String s = v == null ? "" : String.valueOf(v);
        int cps = s.codePointCount(0, s.length());
        return cps <= n ? s : s.substring(0, s.offsetByCodePoints(0, n));
    }

    /** {@code serie-str_pad(correlativo, n, '0', STR_PAD_LEFT)}. */
    static String numero(Object serie, Object correlativo, int pad) {
        String c = String.valueOf(correlativo);
        return serie + "-" + (c.length() >= pad ? c : "0".repeat(pad - c.length()) + c);
    }

    /** Fecha y hora tal como la devuelve la BD ({@code Y-m-d H:i:s}). */
    static String dateTime(Object v) {
        if (v == null) {
            return null;
        }
        LocalDateTime t = v instanceof Timestamp ts ? ts.toLocalDateTime() : v instanceof LocalDateTime l ? l : null;
        return t == null ? String.valueOf(v) : t.format(Clock.Y_M_D_H_I_S);
    }

    /** {@code filter_var($v, FILTER_VALIDATE_BOOLEAN)}: "1", "true", "on" y "yes" son verdaderos. */
    static boolean filterBool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        String s = v == null ? "" : String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT);
        return s.equals("1") || s.equals("true") || s.equals("on") || s.equals("yes");
    }

    /** {@code Y-m-d} estricto; null si no es una fecha válida. */
    static LocalDate parseDate(String s) {
        if (s == null || !s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            return LocalDate.parse(s, YMD);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
