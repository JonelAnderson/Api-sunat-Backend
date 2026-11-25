package pe.facturacion.api.shared.validation;

/**
 * Regla propia (equivalente a una clase {@code ValidationRule} de Laravel, p. ej.
 * {@code SunatCatalog}, {@code SunatProductCode} o {@code exists}).
 * Devuelve null si pasa, o el mensaje de error (puede contener {@code :attribute}).
 */
@FunctionalInterface
public interface CustomRule {

    String validate(String attribute, Object value, Validator validator);

    /** Si es implícita se evalúa también cuando el valor falta o está vacío. */
    default boolean implicit() {
        return false;
    }
}
