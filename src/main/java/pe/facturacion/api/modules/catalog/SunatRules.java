package pe.facturacion.api.modules.catalog;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import pe.facturacion.api.shared.php.Php;
import pe.facturacion.api.shared.validation.CustomRule;
import pe.facturacion.api.shared.validation.RuleSets;

/**
 * Reglas propias de API-PRO: {@code App\Rules\SunatCatalog} (catálogos de
 * {@code config/sunat_catalogs.php}) y {@code App\Rules\SunatProductCode} (UNSPSC, catálogo 25).
 * {@link #resolve(String)} traduce los marcadores de {@code validation/*.json}.
 */
@Component
public class SunatRules {

    private static final Map<String, Object> CATALOGS = RuleSets.read("sunat_catalogs");

    private final JdbcTemplate jdbc;

    public SunatRules(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code "SunatCatalog:51"} o {@code "SunatProductCode"}. */
    public CustomRule resolve(String marker) {
        if (marker.startsWith("SunatCatalog:")) {
            return catalog(marker.substring("SunatCatalog:".length()));
        }
        if (marker.equals("SunatProductCode")) {
            return productCode();
        }
        throw new IllegalArgumentException("Regla desconocida: " + marker);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> catalogEntries(String number) {
        Object c = CATALOGS.get(number);
        return c instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    public static CustomRule catalog(String number) {
        return (attribute, value, validator) -> {
            if (!(value instanceof String) && !Php.isInt(value)) {
                return "El campo :attribute debe ser un código válido del Catálogo SUNAT N° " + number + ".";
            }
            String key = Php.toStr(value);
            Map<String, Object> entries = catalogEntries(number);
            if (!entries.containsKey(key)) {
                return "El campo :attribute contiene un código inválido '" + key + "'. Catálogo SUNAT N° " + number
                        + ". Códigos válidos: " + String.join(", ", entries.keySet()) + ".";
            }
            return null;
        };
    }

    public CustomRule productCode() {
        return (attribute, value, validator) -> {
            String codigo = Php.toStr(value);
            if (!codigo.matches("\\d{8}")) {
                return "El Código de producto SUNAT debe tener 8 dígitos numéricos (Catálogo N.° 25, UNSPSC).";
            }
            if (codigo.endsWith("0000")) {
                return "El Código de producto SUNAT debe especificarse como mínimo a nivel de clase UNSPSC (no puede terminar en 0000).";
            }
            Integer n = jdbc.queryForObject("SELECT count(*) FROM unspsc_codes WHERE codigo = ?", Integer.class, codigo);
            if (n == null || n == 0) {
                return "El Código de producto SUNAT '" + codigo + "' no es válido: no existe en el Catálogo N.° 25 (UNSPSC v14).";
            }
            return null;
        };
    }
}
