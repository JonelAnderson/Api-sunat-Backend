package pe.facturacion.api.shared.php;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.core.type.TypeReference;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Paridad bit a bit con {@code round()}, {@code number_format()} y {@code (string)} de PHP 8.3
 * sobre 6 000 valores (fixture generado con PHP: {@code fixtures/php_math.json}).
 */
class PhpMathParityTest {

    private static List<Map<String, String>> rows;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = PhpMathParityTest.class.getResourceAsStream("/fixtures/php_math.json")) {
            rows = new tools.jackson.databind.ObjectMapper().readValue(in, new TypeReference<>() {
            });
        }
    }

    private static double d(String hex) {
        return Double.longBitsToDouble(Long.parseUnsignedLong(hex, 16));
    }

    @Test
    void roundIgualQuePhp() {
        for (Map<String, String> r : rows) {
            double v = d(r.get("v"));
            for (int p : new int[] {0, 2, 4, 5, 6, 10}) {
                assertThat(Double.doubleToRawLongBits(PhpMath.round(v, p)))
                        .as("round(%s, %d)", r.get("s"), p)
                        .isEqualTo(Double.doubleToRawLongBits(d(r.get("r" + p))));
            }
        }
    }

    @Test
    void numberFormatIgualQuePhp() {
        for (Map<String, String> r : rows) {
            double v = d(r.get("v"));
            assertThat(PhpMath.numberFormat(v, 2)).as("number_format(%s, 2)", r.get("s")).isEqualTo(r.get("n2"));
            assertThat(PhpMath.numberFormat(v, 5)).as("number_format(%s, 5)", r.get("s")).isEqualTo(r.get("n5"));
            assertThat(PhpMath.numberFormat(v, 10)).as("number_format(%s, 10)", r.get("s")).isEqualTo(r.get("n10"));
        }
    }

    @Test
    void cadenaIgualQuePhp() {
        for (Map<String, String> r : rows) {
            assertThat(PhpMath.toStr(d(r.get("v")))).isEqualTo(r.get("s"));
        }
    }
}
