package pe.facturacion.api.modules.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Base32 con los vectores de la RFC 4648 §10. */
class Base32Test {

    @ParameterizedTest
    @CsvSource(value = {
        "'', ''",
        "f, MY======",
        "fo, MZXQ====",
        "foo, MZXW6===",
        "foob, MZXW6YQ=",
        "fooba, MZXW6YTB",
        "foobar, MZXW6YTBOI======"
    })
    void vectores_rfc4648(String plain, String encoded) {
        byte[] bytes = plain.getBytes(StandardCharsets.US_ASCII);
        assertThat(Base32.encode(bytes, true)).isEqualTo(encoded);
        assertThat(Base32.encode(bytes)).isEqualTo(encoded.replace("=", ""));
        assertThat(Base32.decode(encoded)).isEqualTo(bytes);
        assertThat(Base32.decode(encoded.replace("=", ""))).isEqualTo(bytes);
    }

    @Test
    void decodifica_minusculas_espacios_y_guiones() {
        assertThat(new String(Base32.decode("mzxw 6ytb-oi"), StandardCharsets.US_ASCII)).isEqualTo("foobar");
    }

    @Test
    void ida_y_vuelta_con_bytes_aleatorios() {
        SecureRandom r = new SecureRandom();
        for (int len = 0; len < 40; len++) {
            byte[] b = new byte[len];
            r.nextBytes(b);
            assertThat(Base32.decode(Base32.encode(b))).isEqualTo(b);
        }
    }

    @Test
    void rechaza_caracteres_fuera_del_alfabeto() {
        assertThatThrownBy(() -> Base32.decode("MZXW1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base32.decode("MZ*W")).isInstanceOf(IllegalArgumentException.class);
    }
}
