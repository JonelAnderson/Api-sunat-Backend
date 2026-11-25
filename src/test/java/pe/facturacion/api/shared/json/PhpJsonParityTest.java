package pe.facturacion.api.shared.json;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.core.type.TypeReference;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Paridad byte a byte con {@code json_encode} de PHP ({@code fixtures/php_json.json}). */
class PhpJsonParityTest {

    private static Map<String, Object> fixture;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = PhpJsonParityTest.class.getResourceAsStream("/fixtures/php_json.json")) {
            fixture = new tools.jackson.databind.ObjectMapper().readValue(in, new TypeReference<>() {
            });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void numerosComoPhp() {
        for (Map<String, String> n : (List<Map<String, String>>) fixture.get("numbers")) {
            double value = Double.parseDouble(n.get("repr"));
            assertThat(PhpNumbers.formatFloat(value)).as("json_encode(%s)", n.get("repr")).isEqualTo(n.get("json"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void cadenasComoPhp() {
        for (Map<String, String> s : (List<Map<String, String>>) fixture.get("strings")) {
            String value = new String(Base64.getDecoder().decode(s.get("value")), StandardCharsets.UTF_8);
            assertThat(PhpJson.encode(value)).isEqualTo(s.get("json"));
        }
    }

    @Test
    void objetoComoLaravel() {
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("total", 236.0);
        datos.put("igv", new java.math.BigDecimal("36.00"));
        datos.put("url", "https://h/api/v1/facturas/5/xml");
        datos.put("vacio", PhpJson.emptyArray());
        datos.put("nulo", null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estado", "exito");
        body.put("mensaje", "Factura creada y encolada para envío a SUNAT.");
        body.put("datos", datos);
        assertThat(PhpJson.encode(body)).isEqualTo(fixture.get("object"));
    }
}
