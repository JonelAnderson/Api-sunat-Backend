package pe.facturacion.api.modules.tax;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import pe.facturacion.api.modules.tenant.domain.Tenant;
import pe.facturacion.api.shared.php.Php;

/**
 * Tasa de IGV vigente, equivalente a {@code App\Services\TaxRateService} de API-PRO.
 * Precedencia: {@code porcentaje_igv} del ítem → IVAP (17) 4 % → override del tenant → RUS 0 % → 18 %.
 */
@Service
public class TaxRateService {

    public static final String REGIMEN_GENERAL = "general";
    public static final String REGIMEN_RUS = "rus";
    /** {@code config/regimenes.php} → lista (en orden). */
    public static final List<String> REGIMENES = List.of("rus", "rer", "mype", "general");
    /** Régimen con restricciones de emisión (solo boletas, sin IGV). */
    public static final String REGIMEN_RESTRINGIDO = "rus";

    /** Ley 31556 (restaurantes y hoteles MYPE): solo referencia, se declara en igv_rate_override. */
    public static final Map<Integer, Double> LEY_31556 = Map.of(
            2022, 10.0, 2023, 10.0, 2024, 10.0, 2025, 10.0, 2026, 10.5, 2027, 15.0, 2028, 18.0, 2029, 18.0);

    private static final List<String> GRATUITAS_GRAVADAS = List.of("11", "12", "13", "14", "15", "16");

    public double defaultIgvRate(Tenant tenant) {
        if (tenant != null && tenant.getIgvRateOverride() != null) {
            return tenant.getIgvRateOverride().doubleValue();
        }
        String regimen = tenant == null || tenant.getTaxRegime() == null ? REGIMEN_GENERAL : tenant.getTaxRegime();
        if (REGIMEN_RUS.equals(regimen)) {
            return 0.0;
        }
        return 18.0;
    }

    public boolean isNrus(Tenant tenant) {
        return tenant != null && REGIMEN_RUS.equals(tenant.getTaxRegime());
    }

    /** '30' (inafecto) si es RUS, '10' (gravado) en otro caso. */
    public String defaultTipAfeIgv(Tenant tenant) {
        return isNrus(tenant) ? "30" : "10";
    }

    /** Tasa efectiva de un ítem ({@code isset} y comparaciones estrictas, como API-PRO). */
    public double rateForItem(Map<String, Object> item, Tenant tenant) {
        Object p = item.get("porcentaje_igv");
        if (p != null) {
            return Php.toFloat(p);
        }
        Object tip = item.get("tip_afe_igv") != null ? item.get("tip_afe_igv") : defaultTipAfeIgv(tenant);
        if (Php.strictEq(tip, "17")) {
            return 4.0;
        }
        if (Php.strictEq(tip, "10") || (tip instanceof String s && GRATUITAS_GRAVADAS.contains(s))) {
            return defaultIgvRate(tenant);
        }
        return 0.0;
    }

    /** Tasa actual para mostrar al usuario ({@code tasa_igv_vigente}). */
    public double currentRateFor(Tenant tenant) {
        return defaultIgvRate(tenant);
    }
}
