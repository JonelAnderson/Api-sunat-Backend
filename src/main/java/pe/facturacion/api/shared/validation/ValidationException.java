package pe.facturacion.api.shared.validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Errores de validación: se responden como 422 {@code "Error de validación"} con {@code errores}. */
public class ValidationException extends RuntimeException {

    private final LinkedHashMap<String, List<String>> errors;

    public ValidationException(LinkedHashMap<String, List<String>> errors) {
        super("Error de validación");
        this.errors = errors;
    }

    public static ValidationException single(String attribute, String message) {
        LinkedHashMap<String, List<String>> e = new LinkedHashMap<>();
        e.put(attribute, List.of(message));
        return new ValidationException(e);
    }

    public Map<String, List<String>> getErrors() {
        return errors;
    }
}
