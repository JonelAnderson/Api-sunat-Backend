package pe.facturacion.api.modules.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Códigos de recuperación: 10 de un solo uso, guardados como SHA-256. */
class RecoveryCodesTest {

    @Test
    void genera_10_codigos_distintos_con_formato_legible() {
        List<String> codes = RecoveryCodes.generate();
        assertThat(codes).hasSize(10);
        assertThat(new HashSet<>(codes)).hasSize(10);
        assertThat(codes).allMatch(c -> c.matches("[a-hjkmnp-z2-9]{5}-[a-hjkmnp-z2-9]{5}"));
    }

    @Test
    void guarda_solo_el_sha256_hexadecimal() {
        List<String> codes = RecoveryCodes.generate();
        List<String> hashes = RecoveryCodes.hashAll(codes);
        assertThat(hashes).allMatch(h -> h.matches("[0-9a-f]{64}"));
        String json = RecoveryCodes.toJson(hashes);
        for (String c : codes) {
            assertThat(json).doesNotContain(c).doesNotContain(c.replace("-", ""));
        }
        assertThat(RecoveryCodes.fromJson(json)).containsExactlyElementsOf(hashes);
        // SHA-256 de "abcdefghij" (el código normalizado)
        assertThat(RecoveryCodes.hash("abcde-fghij")).isEqualTo("72399361da6a7754fec986dca5b7cbaf1c810a28ded4abaf56b2106d06cb78b0");
    }

    @Test
    void normaliza_mayusculas_espacios_y_guion() {
        assertThat(RecoveryCodes.hash(" ABCDE fghij ")).isEqualTo(RecoveryCodes.hash("abcde-fghij"));
        assertThat(RecoveryCodes.hash("abcdefghij")).isEqualTo(RecoveryCodes.hash("abcde-fghij"));
    }

    @Test
    void cada_codigo_se_consume_una_sola_vez() {
        List<String> codes = RecoveryCodes.generate();
        List<String> hashes = RecoveryCodes.hashAll(codes);

        List<String> left = RecoveryCodes.consume(hashes, codes.get(3).toUpperCase()).orElseThrow();
        assertThat(left).hasSize(9).doesNotContain(RecoveryCodes.hash(codes.get(3)));
        assertThat(RecoveryCodes.consume(left, codes.get(3))).isEmpty();
        assertThat(RecoveryCodes.consume(left, codes.get(4))).isPresent();
    }

    @Test
    void rechaza_codigos_inexistentes_o_mal_formados() {
        List<String> hashes = RecoveryCodes.hashAll(RecoveryCodes.generate());
        assertThat(RecoveryCodes.consume(hashes, "zzzzz-zzzzz")).isEmpty();
        assertThat(RecoveryCodes.consume(hashes, "")).isEmpty();
        assertThat(RecoveryCodes.consume(hashes, null)).isEmpty();
        assertThat(RecoveryCodes.consume(hashes, "abc")).isEmpty();
    }

    @Test
    void columna_vacia_o_invalida_no_tiene_codigos() {
        assertThat(RecoveryCodes.fromJson(null)).isEmpty();
        assertThat(RecoveryCodes.fromJson("")).isEmpty();
        assertThat(RecoveryCodes.fromJson("no es json")).isEmpty();
        assertThat(RecoveryCodes.fromJson("{\"a\":1}")).isEmpty();
        assertThat(RecoveryCodes.fromJson("[\"corto\", 5]")).isEmpty();
    }
}
