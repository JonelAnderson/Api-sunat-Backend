package pe.facturacion.api.shared.json;

import java.math.BigDecimal;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import java.util.List;
import java.util.Map;

/**
 * Serialización JSON byte a byte compatible con {@code response()->json()} de Laravel
 * ({@code json_encode} sin flags):
 * <ul>
 *   <li>números como PHP ({@link PhpNumbers});</li>
 *   <li>{@code /} escapada como {@code \/};</li>
 *   <li>todo carácter no ASCII escapado como {@code \}{@code uxxxx} en minúsculas (pares
 *       sustitutos para emojis, igual que PHP);</li>
 *   <li>secuencias {@code \}{@code u} de control en minúsculas.</li>
 * </ul>
 * Las respuestas se construyen con {@link java.util.LinkedHashMap} o records para conservar
 * el orden de claves de API-PRO.
 */
public final class PhpJson {

    private static final ObjectMapper MAPPER = buildMapper();

    private PhpJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Serializa como lo haría {@code json_encode($value)} en API-PRO. */
    public static String encode(Object value) {
        try {
            return escapeLikePhp(MAPPER.writeValueAsString(value));
        } catch (JacksonException e) {
            throw new IllegalStateException("No se pudo serializar a JSON", e);
        }
    }

    /** Como {@code json_encode($value, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)} (webhooks). */
    public static String encodeUnescaped(Object value) {
        try {
            return lowercaseUnicodeEscapes(MAPPER.writeValueAsString(value));
        } catch (JacksonException e) {
            throw new IllegalStateException("No se pudo serializar a JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> decodeObject(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    public static Object decode(String json) {
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    /**
     * Recorre el JSON generado por Jackson y aplica el escapado de PHP solo dentro de las
     * cadenas: {@code /} → {@code \/}, no-ASCII → {@code \}{@code uxxxx}, hex en minúsculas.
     */
    static String escapeLikePhp(String json) {
        StringBuilder out = new StringBuilder(json.length() + 16);
        boolean inString = false;
        int n = json.length();
        for (int i = 0; i < n; i++) {
            char c = json.charAt(i);
            if (!inString) {
                out.append(c);
                if (c == '"') {
                    inString = true;
                }
                continue;
            }
            if (c == '\\') {
                char next = json.charAt(i + 1);
                if (next == 'u') {
                    out.append("\\u").append(json.substring(i + 2, i + 6).toLowerCase());
                    i += 5;
                } else {
                    out.append(c).append(next);
                    i++;
                }
            } else if (c == '"') {
                out.append(c);
                inString = false;
            } else if (c == '/') {
                out.append("\\/");
            } else if (c > 0x7F) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String lowercaseUnicodeEscapes(String json) {
        StringBuilder out = new StringBuilder(json.length());
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString && c == '\\') {
                char next = json.charAt(i + 1);
                if (next == 'u') {
                    out.append("\\u").append(json.substring(i + 2, i + 6).toLowerCase());
                    i += 5;
                } else {
                    out.append(c).append(next);
                    i++;
                }
                continue;
            }
            if (c == '"') {
                inString = !inString;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static ObjectMapper buildMapper() {
        SimpleModule php = new SimpleModule("php-json");
        ValueSerializer<Number> floatSerializer = new ValueSerializer<>() {
            @Override
            public void serialize(Number value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeNumber(PhpNumbers.formatFloat(value.doubleValue()));
            }
        };
        php.addSerializer(Double.class, floatSerializer);
        php.addSerializer(Double.TYPE, floatSerializer);
        php.addSerializer(Float.class, floatSerializer);
        php.addSerializer(Float.TYPE, floatSerializer);
        php.addSerializer(BigDecimal.class, new ValueSerializer<>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeNumber(PhpNumbers.formatDecimalAsFloat(value));
            }
        });
        // Jackson 3 cambió varios valores por defecto (JSTEP-2). Se fijan los de Jackson 2 para que la salida
        // siga byte a byte igual a la de Laravel: orden de claves de declaración (no alfabético), enums por
        // nombre, "/" sin escapar aquí (lo escapa escapeLikePhp solo donde PHP lo hace) y lectura tolerante.
        return JsonMapper.builder()
                .addModule(php)
                .findAndAddModules()
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(EnumFeature.WRITE_ENUMS_USING_TO_STRING)
                .disable(EnumFeature.READ_ENUMS_USING_TO_STRING)
                .disable(JsonWriteFeature.ESCAPE_FORWARD_SLASHES)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
    }

    /** Lista vacía que PHP serializa como {@code []}. */
    public static List<Object> emptyArray() {
        return List.of();
    }
}
