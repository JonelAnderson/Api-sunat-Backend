package pe.facturacion.api.shared.validation;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Mensajes de validación en español, exportados literalmente de
 * {@code API-PRO/lang/es/validation.php} a {@code resources/lang/es/validation.json}.
 */
public final class ValidationMessages {

    private static final Map<String, Object> MESSAGES = load();

    private ValidationMessages() {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        try (InputStream in = ValidationMessages.class.getResourceAsStream("/lang/es/validation.json")) {
            return new ObjectMapper().readValue(in, Map.class);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("No se pudo cargar lang/es/validation.json", e);
        }
    }

    /** Mensaje de una regla; para reglas por tipo (min, max, size...) se indica el tipo. */
    @SuppressWarnings("unchecked")
    public static String get(String rule, String type) {
        Object m = MESSAGES.get(rule);
        if (m instanceof Map<?, ?> byType) {
            Object t = ((Map<String, Object>) byType).get(type);
            return t == null ? "validation." + rule + "." + type : t.toString();
        }
        return m == null ? "validation." + rule : m.toString();
    }

    /** Traducción de {@code validation.attributes.{name}} o null. */
    @SuppressWarnings("unchecked")
    public static String attribute(String name) {
        Object attrs = MESSAGES.get("attributes");
        if (attrs instanceof Map<?, ?> map) {
            Object v = ((Map<String, Object>) map).get(name);
            return v == null ? null : v.toString();
        }
        return null;
    }
}
