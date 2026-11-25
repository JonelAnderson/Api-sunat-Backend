package pe.facturacion.api.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.core.type.TypeReference;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pe.facturacion.api.shared.json.PhpJson;

/**
 * Paridad con el Validator real de Laravel: {@code fixtures/validation/expected.json} se generó
 * ejecutando Illuminate\Validation de API-PRO con los mismos casos ({@code cases.json}).
 * Compara errores (claves, orden y mensajes) y {@code validated()}.
 */
class LaravelValidatorParityTest {

    @SuppressWarnings("unchecked")
    static Stream<Arguments> casos() throws Exception {
        List<Map<String, Object>> cases = read("/fixtures/validation/cases.json");
        List<Map<String, Object>> expected = read("/fixtures/validation/expected.json");
        List<Arguments> args = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            args.add(Arguments.of(cases.get(i).get("name"), cases.get(i), expected.get(i)));
        }
        return args.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("casos")
    @SuppressWarnings("unchecked")
    void mismosErroresQueLaravel(String name, Map<String, Object> caso, Map<String, Object> esperado) {
        Map<String, Object> data = (Map<String, Object>) caso.get("data");
        Map<String, Object> rules = (Map<String, Object>) caso.get("rules");
        Map<String, String> messages = (Map<String, String>) caso.getOrDefault("messages", Map.of());

        Validator v = Validator.make(data, rules).messages(messages);
        Map<String, List<String>> errors = v.errors();

        Map<String, Object> expectedErrors = (Map<String, Object>) esperado.get("errors");
        // Mismo JSON = mismas claves, mismo orden y mismos mensajes
        assertThat(PhpJson.encode(errors.isEmpty() ? Map.of() : errors)).isEqualTo(PhpJson.encode(expectedErrors));

        Object expectedValidated = esperado.get("validated");
        if (expectedValidated != null) {
            Object expectedJson = expectedValidated instanceof List<?> l && l.isEmpty() ? Map.of() : expectedValidated;
            assertThat(PhpJson.encode(v.validated())).isEqualTo(PhpJson.encode(expectedJson));
        }
    }

    private static List<Map<String, Object>> read(String path) throws Exception {
        try (InputStream in = LaravelValidatorParityTest.class.getResourceAsStream(path)) {
            return PhpJson.mapper().readValue(in, new TypeReference<>() {
            });
        }
    }
}
