package pe.facturacion.api.modules.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** TOTP con los vectores de prueba de la RFC 6238 (apéndice B, HMAC-SHA1) y la ventana de ±1 paso. */
class TotpTest {

    /** Clave de los vectores SHA-1 de la RFC: "12345678901234567890" en ASCII. */
    static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    static final String RFC_KEY_B32 = Base32.encode(RFC_KEY);

    @ParameterizedTest
    @CsvSource({
        "59, 94287082",
        "1111111109, 07081804",
        "1111111111, 14050471",
        "1234567890, 89005924",
        "2000000000, 69279037",
        "20000000000, 65353130"
    })
    void rfc6238_vectores_sha1_8_digitos(long unix, String expected) {
        long step = Totp.step(Instant.ofEpochSecond(unix));
        assertThat(Totp.code(RFC_KEY, step, 8)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"59, 287082", "1111111109, 081804", "1234567890, 005924", "20000000000, 353130"})
    void con_6_digitos_son_los_ultimos_6_del_vector(long unix, String expected) {
        assertThat(Totp.code(RFC_KEY_B32, Instant.ofEpochSecond(unix))).isEqualTo(expected);
    }

    @Test
    void acepta_un_paso_antes_y_uno_despues_pero_no_dos() {
        Instant now = Instant.ofEpochSecond(1_234_567_890);
        long step = Totp.step(now);
        String previous = Totp.code(RFC_KEY, step - 1, 6);
        String next = Totp.code(RFC_KEY, step + 1, 6);
        String tooOld = Totp.code(RFC_KEY, step - 2, 6);
        String tooNew = Totp.code(RFC_KEY, step + 2, 6);

        assertThat(Totp.matchStep(RFC_KEY_B32, Totp.code(RFC_KEY_B32, now), now)).isEqualTo(step);
        assertThat(Totp.matchStep(RFC_KEY_B32, previous, now)).isEqualTo(step - 1);
        assertThat(Totp.matchStep(RFC_KEY_B32, next, now)).isEqualTo(step + 1);
        assertThat(Totp.verify(RFC_KEY_B32, tooOld, now)).isFalse();
        assertThat(Totp.verify(RFC_KEY_B32, tooNew, now)).isFalse();
    }

    @Test
    void rechaza_codigos_mal_formados() {
        Instant now = Instant.ofEpochSecond(59);
        assertThat(Totp.verify(RFC_KEY_B32, null, now)).isFalse();
        assertThat(Totp.verify(RFC_KEY_B32, "", now)).isFalse();
        assertThat(Totp.verify(RFC_KEY_B32, "28708", now)).isFalse();
        assertThat(Totp.verify(RFC_KEY_B32, "2870822", now)).isFalse();
        assertThat(Totp.verify(RFC_KEY_B32, "28708a", now)).isFalse();
        // Espacios al copiar desde el teléfono sí valen
        assertThat(Totp.verify(RFC_KEY_B32, "287 082", now)).isTrue();
    }

    @Test
    void clave_nueva_de_160_bits_y_uri_del_autenticador() {
        String secret = Totp.newSecret();
        assertThat(Base32.decode(secret)).hasSize(20);
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(Totp.newSecret()).isNotEqualTo(secret);

        assertThat(Totp.authenticatorUri("ana.perez@example.com", "JBSWY3DPEHPK3PXP"))
                .isEqualTo("otpauth://totp/API%20SUNAT:ana.perez%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=API%20SUNAT");
        assertThat(Totp.formatSharedKey("JBSWY3DPEHPK3PXP")).isEqualTo("JBSW Y3DP EHPK 3PXP");
        // La clave agrupada se puede volver a leer
        assertThat(Base32.decode(Totp.formatSharedKey(secret))).isEqualTo(Base32.decode(secret));
    }
}
