package pe.facturacion.api.modules.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Política: 8–64 caracteres con mayúscula, minúscula, número y especial (y ≤ 72 bytes para BCrypt). */
class PasswordPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"Prueba#2026", "aA1!aaaa", "Clave segura 9.", "Ñandú#2026x"})
    void acepta_contrasenas_que_cumplen(String password) {
        assertThat(PasswordPolicy.validate(password)).isEmpty();
        assertThat(PasswordPolicy.isValid(password)).isTrue();
    }

    @Test
    void longitud_entre_8_y_64() {
        assertThat(PasswordPolicy.validate("aA1!aaa")).anyMatch(m -> m.contains("entre 8 y 64"));
        assertThat(PasswordPolicy.validate("aA1!" + "a".repeat(60))).isEmpty();
        assertThat(PasswordPolicy.validate("aA1!" + "a".repeat(61))).anyMatch(m -> m.contains("entre 8 y 64"));
    }

    @Test
    void cada_requisito_tiene_su_mensaje() {
        assertThat(PasswordPolicy.validate("prueba#2026")).containsExactly("La contraseña debe tener al menos una letra mayúscula.");
        assertThat(PasswordPolicy.validate("PRUEBA#2026")).containsExactly("La contraseña debe tener al menos una letra minúscula.");
        assertThat(PasswordPolicy.validate("Prueba#abcd")).containsExactly("La contraseña debe tener al menos un número.");
        assertThat(PasswordPolicy.validate("Prueba2026x")).containsExactly("La contraseña debe tener al menos un carácter especial.");
        assertThat(PasswordPolicy.validate(null)).containsExactly("La contraseña es obligatoria.");
        assertThat(PasswordPolicy.validate("")).containsExactly("La contraseña es obligatoria.");
    }

    @Test
    void no_pasa_de_72_bytes_aunque_tenga_64_caracteres() {
        // 64 caracteres, pero las tildes ocupan 2 bytes en UTF-8 → más de 72 bytes
        String p = "Aa1!" + "á".repeat(60);
        assertThat(p.codePointCount(0, p.length())).isEqualTo(64);
        assertThat(PasswordPolicy.validate(p)).anyMatch(m -> m.contains("demasiado larga"));
    }
}
