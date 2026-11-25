package pe.facturacion.api.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static String key() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    @Test
    void cifraYDescifra() {
        SecretCipher c = new SecretCipher(key());
        String enc = c.encrypt("MODDATOS");
        assertThat(enc).startsWith("v1:").doesNotContain("MODDATOS");
        assertThat(c.encrypt("MODDATOS")).as("IV aleatorio").isNotEqualTo(enc);
        assertThat(c.decrypt(enc)).isEqualTo("MODDATOS");
    }

    @Test
    void rechazaValoresAjenosOAlterados() {
        SecretCipher c = new SecretCipher(key());
        assertThatThrownBy(() -> c.decrypt("texto-plano")).isInstanceOf(SecretCipher.DecryptException.class);
        String enc = c.encrypt("secreto");
        String alterado = enc.substring(0, enc.length() - 4) + "AAAA";
        assertThatThrownBy(() -> c.decrypt(alterado)).isInstanceOf(SecretCipher.DecryptException.class);
    }

    @Test
    void exigeClaveDe32Bytes() {
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher("")).isInstanceOf(IllegalStateException.class);
    }
}
