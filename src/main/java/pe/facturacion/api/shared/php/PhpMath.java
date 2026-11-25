package pe.facturacion.api.shared.php;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Aritmética de PHP 8.3 reproducida bit a bit (API-PRO calcula importes con {@code float}).
 *
 * <ul>
 *   <li>{@link #round(double, int)}: {@code round()} con el "pre-redondeo" de
 *       {@code ext/standard/math.c} (PHP ≤ 8.3), modo HALF_UP.</li>
 *   <li>{@link #numberFormat(double, int)}: {@code number_format($n, $dec, '.', '')}.</li>
 *   <li>{@link #toStr(double)}: {@code (string) $float} con {@code precision = 14}
 *       (lo que imprime Twig y lo que PDO envía a la BD).</li>
 * </ul>
 */
public final class PhpMath {

    private static final double[] POW10 = {
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11,
        1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};

    private static final double[] LOG_TABLE = {
        1e-8, 1e-7, 1e-6, 1e-5, 1e-4, 1e-3, 1e-2, 1e-1, 1e0, 1e1, 1e2,
        1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13,
        1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};

    private PhpMath() {
    }

    private static int intLog10Abs(double value) {
        value = Math.abs(value);
        if (value < 1e-8 || value > 1e22) {
            return (int) Math.floor(Math.log10(value));
        }
        int result = 15;
        result += value < LOG_TABLE[result] ? -8 : 8;
        result += value < LOG_TABLE[result] ? -4 : 4;
        result += value < LOG_TABLE[result] ? -2 : 2;
        result += value < LOG_TABLE[result] ? -1 : 1;
        if (value < LOG_TABLE[result]) {
            result -= 1;
        }
        return result - 8;
    }

    private static double intPow10(int power) {
        if (power < 0 || power > 22) {
            return Math.pow(10.0, power);
        }
        return POW10[power];
    }

    private static double roundHelper(double value) {
        return value >= 0.0 ? Math.floor(value + 0.5) : Math.ceil(value - 0.5);
    }

    /** {@code round($value, $places)} de PHP 8.3 (PHP_ROUND_HALF_UP). */
    public static double round(double value, int places) {
        if (!Double.isFinite(value) || value == 0.0) {
            return value;
        }
        int precisionPlaces = 14 - intLog10Abs(value);
        double f1 = intPow10(Math.abs(places));
        double tmp;
        if (precisionPlaces > places && precisionPlaces - 15 < places) {
            double f2 = intPow10(Math.abs(precisionPlaces));
            tmp = precisionPlaces >= 0 ? value * f2 : value / f2;
            tmp = roundHelper(tmp);
            int usePrecision = places - precisionPlaces;
            f2 = intPow10(Math.abs(usePrecision));
            tmp = tmp / f2;
        } else {
            tmp = places >= 0 ? value * f1 : value / f1;
            if (Math.abs(tmp) >= 1e15) {
                return value;
            }
        }
        tmp = roundHelper(tmp);
        if (Math.abs(places) < 23) {
            tmp = places > 0 ? tmp / f1 : tmp * f1;
        } else {
            String buf = String.format(java.util.Locale.ROOT, "%15fe%d", tmp, -places);
            try {
                tmp = Double.parseDouble(buf.trim());
            } catch (NumberFormatException e) {
                return value;
            }
            if (!Double.isFinite(tmp)) {
                return value;
            }
        }
        return tmp;
    }

    /** {@code round($value)} (0 decimales). */
    public static double round(double value) {
        return round(value, 0);
    }

    /** {@code number_format($n, $dec, '.', '')}. */
    public static String numberFormat(double d, int dec) {
        boolean negative = false;
        if (d < 0) {
            negative = true;
            d = -d;
        }
        d = round(d, dec);
        dec = Math.max(0, dec);
        if (!Double.isFinite(d)) {
            return Double.isNaN(d) ? "nan" : "inf";
        }
        String s = new BigDecimal(d).setScale(dec, RoundingMode.HALF_EVEN).toPlainString();
        if (negative && d == 0) {
            negative = false;
        }
        return negative ? "-" + s : s;
    }

    /**
     * {@code number_format($n, $dec)} con los separadores por defecto de PHP ("." decimal y ","
     * de miles), como en las plantillas Blade del PDF.
     */
    public static String numberFormatGrouped(double d, int dec) {
        String plain = numberFormat(d, dec);
        boolean negative = plain.startsWith("-");
        String s = negative ? plain.substring(1) : plain;
        int dot = s.indexOf('.');
        String ent = dot < 0 ? s : s.substring(0, dot);
        StringBuilder g = new StringBuilder();
        for (int i = 0; i < ent.length(); i++) {
            if (i > 0 && (ent.length() - i) % 3 == 0) {
                g.append(',');
            }
            g.append(ent.charAt(i));
        }
        return (negative ? "-" : "") + g + (dot < 0 ? "" : s.substring(dot));
    }

    /** {@code (string) $float} con {@code precision = 14} ({@code zend_gcvt}). */
    public static String toStr(double value) {
        return gcvt(value, 14);
    }

    static String gcvt(double value, int ndigit) {
        if (Double.isNaN(value)) {
            return "NAN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "INF" : "-INF";
        }
        if (value == 0.0) {
            return (1 / value < 0) ? "-0" : "0";
        }
        BigDecimal bd = new BigDecimal(value).round(new MathContext(ndigit, RoundingMode.HALF_EVEN)).stripTrailingZeros();
        boolean negative = bd.signum() < 0;
        String digits = bd.unscaledValue().abs().toString();
        int decpt = digits.length() - bd.scale();
        StringBuilder out = new StringBuilder(24);
        if (negative) {
            out.append('-');
        }
        if (decpt < 0 ? decpt < -3 : decpt > ndigit) {
            int exp = decpt - 1;
            out.append(digits.charAt(0)).append('.');
            out.append(digits.length() > 1 ? digits.substring(1) : "0");
            out.append('E').append(exp < 0 ? '-' : '+').append(Math.abs(exp));
        } else if (decpt <= 0) {
            out.append("0.").append("0".repeat(-decpt)).append(digits);
        } else if (decpt >= digits.length()) {
            out.append(digits).append("0".repeat(decpt - digits.length()));
        } else {
            out.append(digits, 0, decpt).append('.').append(digits.substring(decpt));
        }
        return out.toString();
    }
}
