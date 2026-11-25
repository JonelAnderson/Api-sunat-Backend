package pe.facturacion.api.modules.admin.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Política de contraseñas de los administradores: 8 a 64 caracteres con mayúscula, minúscula, número y carácter
 * especial. BCrypt solo usa los primeros 72 bytes (y Spring Security rechaza más), así que también se limita a 72
 * bytes UTF-8.
 */
public final class PasswordPolicy {

    public static final int MIN = 8;
    public static final int MAX = 64;
    public static final int MAX_BCRYPT_BYTES = 72;

    private PasswordPolicy() {
    }

    /** Mensajes de lo que falta; vacío si la contraseña cumple. */
    public static List<String> validate(String password) {
        List<String> errors = new ArrayList<>();
        if (password == null || password.isEmpty()) {
            errors.add("La contraseña es obligatoria.");
            return errors;
        }
        int length = password.codePointCount(0, password.length());
        if (length < MIN || length > MAX) {
            errors.add("La contraseña debe tener entre " + MIN + " y " + MAX + " caracteres.");
        } else if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_BYTES) {
            errors.add("La contraseña es demasiado larga (use menos caracteres especiales o con tilde).");
        }
        if (password.codePoints().noneMatch(Character::isUpperCase)) {
            errors.add("La contraseña debe tener al menos una letra mayúscula.");
        }
        if (password.codePoints().noneMatch(Character::isLowerCase)) {
            errors.add("La contraseña debe tener al menos una letra minúscula.");
        }
        if (password.codePoints().noneMatch(Character::isDigit)) {
            errors.add("La contraseña debe tener al menos un número.");
        }
        if (password.codePoints().allMatch(Character::isLetterOrDigit)) {
            errors.add("La contraseña debe tener al menos un carácter especial.");
        }
        return errors;
    }

    public static boolean isValid(String password) {
        return validate(password).isEmpty();
    }
}
