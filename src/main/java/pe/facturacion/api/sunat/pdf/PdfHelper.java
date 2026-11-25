package pe.facturacion.api.sunat.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.php.PhpMath;

/**
 * Funciones de las plantillas ({@code ${h.…}}) con la semántica de PHP/Blade: {@code empty()},
 * {@code number_format()} con separador de miles, conversión de un float a texto e
 * {@code implode()}.
 */
public final class PdfHelper {

    public static final PdfHelper INSTANCE = new PdfHelper();

    private PdfHelper() {
    }

    /** {@code !empty($v)}. */
    public boolean filled(Object v) {
        return !Php.empty(v);
    }

    /** {@code number_format($v, 2)}: "1,234.50". */
    public String n2(Object v) {
        return PhpMath.numberFormatGrouped(Php.toFloat(v), 2);
    }

    /** {@code number_format($v, $dec)}. */
    public String n(Object v, int dec) {
        return PhpMath.numberFormatGrouped(Php.toFloat(v), dec);
    }

    /** {@code {{ $float }}}: un float impreso por PHP (precision 14, "2" para 2.0). */
    public String num(Object v) {
        return v == null ? "" : PhpMath.toStr(Php.toFloat(v));
    }

    public String s(Object v) {
        return v == null ? "" : Php.toStr(v);
    }

    /** {@code implode($sep, $lista)}. */
    public String join(Object list, String sep) {
        List<String> out = new ArrayList<>();
        if (list instanceof List<?> l) {
            l.forEach(o -> out.add(Php.toStr(o)));
        } else if (list instanceof Map<?, ?> m) {
            m.values().forEach(o -> out.add(Php.toStr(o)));
        }
        return String.join(sep, out);
    }

    /** {@code ucfirst()}. */
    public String ucfirst(Object v) {
        String s = Php.toStr(v);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** {@code $a ?? $b}: el primer valor no nulo. */
    public Object coalesce(Object a, Object b) {
        return a != null ? a : b;
    }
}
