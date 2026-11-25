package pe.facturacion.api.shared.validation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Convierte una expresión PCRE con delimitadores ({@code /^F[A-Z0-9]{3}$/i}) a {@link Pattern}. */
public final class PhpRegex {

    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private PhpRegex() {
    }

    public static Pattern compile(String phpPattern) {
        return CACHE.computeIfAbsent(phpPattern, PhpRegex::doCompile);
    }

    private static Pattern doCompile(String p) {
        char delimiter = p.charAt(0);
        char closing = switch (delimiter) {
            case '(' -> ')';
            case '{' -> '}';
            case '[' -> ']';
            case '<' -> '>';
            default -> delimiter;
        };
        int end = p.lastIndexOf(closing);
        String body = p.substring(1, end);
        String modifiers = p.substring(end + 1);
        int flags = 0;
        for (char m : modifiers.toCharArray()) {
            switch (m) {
                case 'i' -> flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                case 'm' -> flags |= Pattern.MULTILINE;
                case 's' -> flags |= Pattern.DOTALL;
                case 'x' -> flags |= Pattern.COMMENTS;
                case 'u' -> flags |= Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNICODE_CASE;
                default -> {
                }
            }
        }
        // PCRE: "$" sin modificador D también acepta un salto de línea final; Java igual con "$".
        return Pattern.compile(body, flags);
    }
}
