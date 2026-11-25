package pe.facturacion.api.modules.admin.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Acumula errores de validación {@code {propertyName, errorMessage}} y los lanza juntos (HTTP 200, succeeded:false). */
public final class AdminValidation {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");

    private final List<Map<String, Object>> errors = new ArrayList<>();

    public AdminValidation add(String property, String message) {
        errors.add(AdminResponse.fieldError(property, message));
        return this;
    }

    public AdminValidation required(String property, Object value, String message) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            add(property, message);
        }
        return this;
    }

    public AdminValidation maxLength(String property, String value, int max, String message) {
        if (value != null && value.length() > max) {
            add(property, message);
        }
        return this;
    }

    /** Largo máximo del motivo de una acción del panel (el mismo de la suspensión de una empresa). */
    public static final int REASON_MAX = 400;

    /**
     * Motivo de una acción del panel ({@code motivo}): obligatorio si {@code required}, como máximo
     * {@link #REASON_MAX} caracteres.
     */
    public AdminValidation reason(String motivo, boolean required) {
        if (required && (motivo == null || motivo.isBlank())) {
            return add("motivo", "El motivo es obligatorio.");
        }
        return maxLength("motivo", motivo, REASON_MAX, "El motivo no debe superar los " + REASON_MAX + " caracteres.");
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** Lanza si hay errores: {@code messages} lleva los textos y {@code errors} el detalle por campo. */
    public void throwIfAny() {
        if (!errors.isEmpty()) {
            List<String> messages = errors.stream().map(e -> String.valueOf(e.get("errorMessage"))).toList();
            throw new AdminException(200, messages, errors);
        }
    }

    public static boolean isEmail(String value) {
        return value != null && value.length() <= 255 && EMAIL.matcher(value).matches();
    }
}
