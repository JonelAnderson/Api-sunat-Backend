package pe.facturacion.api.shared.validation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import pe.facturacion.api.shared.json.PhpJson;

/**
 * Reglas de los FormRequest de API-PRO exportadas a {@code resources/validation/*.json}
 * ({@code {"rules": {...}, "messages": {...}}}). Las reglas propias aparecen como marcadores
 * ({@code @SunatCatalog:51}, {@code @SunatProductCode}) que se resuelven con {@code customs}.
 */
public final class RuleSets {

    public record RuleSet(Map<String, List<Object>> rules, Map<String, String> messages) {

        /** Copia con reglas modificables (para ajustes puntuales de cada endpoint). */
        public Map<String, Object> rulesCopy() {
            Map<String, Object> out = new LinkedHashMap<>();
            rules.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
            return out;
        }
    }

    private static final Map<String, Map<String, Object>> RAW = new ConcurrentHashMap<>();

    private RuleSets() {
    }

    /** Carga {@code validation/{name}.json} y sustituye los marcadores {@code @Regla[:param]}. */
    @SuppressWarnings("unchecked")
    public static RuleSet load(String name, Function<String, CustomRule> customs) {
        Map<String, Object> raw = RAW.computeIfAbsent(name, RuleSets::read);
        Map<String, List<Object>> rules = new LinkedHashMap<>();
        ((Map<String, Object>) raw.get("rules")).forEach((attr, list) -> {
            List<Object> out = new ArrayList<>();
            for (Object r : (List<Object>) list) {
                if (r instanceof String s && s.startsWith("@")) {
                    out.add(customs.apply(s.substring(1)));
                } else {
                    out.add(r);
                }
            }
            rules.put(attr, out);
        });
        Map<String, String> messages = new LinkedHashMap<>();
        Object m = raw.get("messages");
        if (m instanceof Map<?, ?> mm) {
            mm.forEach((k, v) -> messages.put(String.valueOf(k), String.valueOf(v)));
        }
        return new RuleSet(rules, messages);
    }

    /** {@code resources/validation/{name}.json} decodificado. */
    public static Map<String, Object> read(String name) {
        try (InputStream in = RuleSets.class.getResourceAsStream("/validation/" + name + ".json")) {
            if (in == null) {
                throw new IllegalStateException("No existe validation/" + name + ".json");
            }
            return PhpJson.decodeObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
