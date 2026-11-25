package pe.facturacion.api.sunat.xml;

import java.util.regex.Pattern;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.php.PhpMath;

/**
 * Escritor de XML en una sola línea que reproduce la salida de las plantillas Twig de Greenter
 * ({@code {% apply spaceless %}}, autoescape desactivado, CDATA en los mismos nodos).
 *
 * <p>Única diferencia deliberada: el texto fuera de CDATA y los atributos se escapan
 * ({@code & < >} y {@code "}). Greenter los imprime crudos y un {@code &} produce un XML
 * inválido que no se puede firmar; con datos normales la salida es idéntica byte a byte.
 */
public final class XmlWriter {

    /** {@code />\s+</} de PCRE sin modo UTF (espacios ASCII). */
    private static final Pattern SPACELESS = Pattern.compile(">[ \\t\\n\\x0B\\f\\r]+<");

    private final StringBuilder sb = new StringBuilder(8192);

    public XmlWriter raw(String s) {
        sb.append(s);
        return this;
    }

    public XmlWriter open(String tag) {
        sb.append('<').append(tag).append('>');
        return this;
    }

    /** Abre una etiqueta con atributos {@code nombre, valor, nombre, valor...}. */
    public XmlWriter openAttrs(String tag, String... attrs) {
        sb.append('<').append(tag);
        for (int i = 0; i < attrs.length; i += 2) {
            sb.append(' ').append(attrs[i]).append("=\"").append(escapeAttr(attrs[i + 1])).append('"');
        }
        sb.append('>');
        return this;
    }

    public XmlWriter close(String tag) {
        sb.append("</").append(tag).append('>');
        return this;
    }

    /** {@code <tag>{{ value }}</tag>}. */
    public XmlWriter el(String tag, Object value) {
        return open(tag).text(value).close(tag);
    }

    /** {@code <tag attr="{{ attrValue }}">{{ value }}</tag>}. */
    public XmlWriter el(String tag, String attr, Object attrValue, Object value) {
        return openAttrs(tag, attr, Php.toStr(attrValue)).text(value).close(tag);
    }

    /** {@code <tag currencyID="..">{{ value|n_format }}</tag>}. */
    public XmlWriter amount(String tag, String currency, Object value) {
        return el(tag, "currencyID", currency, nFormat(value, 2));
    }

    /** {@code <tag><![CDATA[{{ value }}]]></tag>}. */
    public XmlWriter cdata(String tag, Object value) {
        return open(tag).cdataText(value).close(tag);
    }

    public XmlWriter cdataText(Object value) {
        sb.append("<![CDATA[").append(Php.toStr(value).replace("]]>", "]]]]><![CDATA[>")).append("]]>");
        return this;
    }

    public XmlWriter text(Object value) {
        sb.append(escapeText(Php.toStr(value)));
        return this;
    }

    /** Resultado final: equivalente a {@code trim(preg_replace('/>\s+</', '><', $xml))}. */
    public String build() {
        return SPACELESS.matcher(sb).replaceAll("><").strip();
    }

    /** Filtro {@code n_format(dec)}: {@code number_format($n, $dec, '.', '')}. */
    public static String nFormat(Object value, int dec) {
        return PhpMath.numberFormat(Php.toFloat(value), dec);
    }

    /** Filtro {@code n_format_limit(dec)}. */
    public static String nFormatLimit(Object value, int dec) {
        String s = Php.toStr(value);
        int dot = s.lastIndexOf('.');
        int decimals = dot < 0 ? 0 : s.length() - dot - 1;
        return decimals > dec || s.contains("E") ? nFormat(value, dec) : s;
    }

    static String escapeText(String s) {
        if (s.indexOf('&') < 0 && s.indexOf('<') < 0 && s.indexOf('>') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\r", "&#13;");
    }

    static String escapeAttr(String s) {
        // El parser normaliza tabuladores y saltos de línea de un atributo a espacios
        return escapeText(s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')).replace("\"", "&quot;");
    }
}
