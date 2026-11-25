package pe.facturacion.api.shared.json;

import java.math.BigDecimal;

/**
 * Formato de números idéntico a {@code json_encode} de PHP con
 * {@code serialize_precision = -1} (lo que usa Laravel en API-PRO).
 *
 * <ul>
 *   <li>Representación decimal más corta que reconstruye el double (100.0 → {@code 100}).</li>
 *   <li>Notación exponencial si el exponente decimal está fuera de [-4, 17):
 *       {@code 1.0e+17}, {@code 1.0e-5}.</li>
 *   <li>{@code -0.0} → {@code -0}.</li>
 * </ul>
 */
public final class PhpNumbers {

    private PhpNumbers() {
    }

    /** Equivalente a {@code json_encode((float) $value)}. */
    public static String formatFloat(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            // PHP json_encode falla con INF/NAN; nunca debería ocurrir con importes.
            throw new IllegalArgumentException("Número no representable en JSON: " + value);
        }
        if (value == 0.0) {
            return (1 / value < 0) ? "-0" : "0";
        }
        // Desde JDK 19, Double.toString devuelve la representación más corta (Ryu-like).
        BigDecimal bd = new BigDecimal(Double.toString(value)).stripTrailingZeros();
        boolean negative = bd.signum() < 0;
        String digits = bd.unscaledValue().abs().toString();
        // decpt = posición del punto decimal respecto al primer dígito (convención de dtoa)
        int decpt = digits.length() - bd.scale();

        StringBuilder out = new StringBuilder(24);
        if (negative) {
            out.append('-');
        }
        if (decpt < -3 || decpt > 17) {
            out.append(digits.charAt(0)).append('.');
            out.append(digits.length() > 1 ? digits.substring(1) : "0");
            int exp = decpt - 1;
            out.append('e').append(exp < 0 ? '-' : '+').append(Math.abs(exp));
        } else if (decpt <= 0) {
            out.append("0.");
            out.append("0".repeat(-decpt));
            out.append(digits);
        } else if (decpt >= digits.length()) {
            out.append(digits);
            out.append("0".repeat(decpt - digits.length()));
        } else {
            out.append(digits, 0, decpt).append('.').append(digits.substring(decpt));
        }
        return out.toString();
    }

    /**
     * Un {@link BigDecimal} de BD convertido como lo hacen los Resources de API-PRO:
     * {@code (float) $model->monto}.
     */
    public static String formatDecimalAsFloat(BigDecimal value) {
        return formatFloat(value.doubleValue());
    }
}
