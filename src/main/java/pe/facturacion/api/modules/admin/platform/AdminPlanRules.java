package pe.facturacion.api.modules.admin.platform;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import pe.facturacion.api.modules.admin.api.AdminBody;
import pe.facturacion.api.modules.admin.api.AdminValidation;

/**
 * Validación de un plan del panel (las reglas de {@code PlanController::validar} de la referencia, sin su lógica de
 * caché). Pura: no lee la BD; la unicidad del slug y las suscripciones las revisa el controlador.
 *
 * <ul>
 *   <li>{@code slug}: 1–50 de {@code [a-z0-9_-]}; los planes del sistema ({@link #SYSTEM_SLUGS}) no cambian de slug ni
 *       se eliminan, porque {@code tenants.plan} solo admite esos tres y la API busca {@code free} por su slug.</li>
 *   <li>Límites: claves {@code [a-z0-9_]} (máx. 50 caracteres y 50 claves) con un entero ≥ -1 (-1 = ilimitado).</li>
 *   <li>Funcionalidades: {@code [a-z0-9_]} de hasta 50 caracteres, sin repetir (máx. 100).</li>
 *   <li>Plan ilimitado → {@code documents_month} y {@code internal_documents_month} = -1 (como la referencia).</li>
 * </ul>
 */
public final class AdminPlanRules {

    public static final Set<String> SYSTEM_SLUGS = Set.of("free", "pro", "business");
    static final Pattern SLUG = Pattern.compile("^[a-z0-9_-]{1,50}$");
    static final Pattern KEY = Pattern.compile("^[a-z0-9_]{1,50}$");
    static final int MAX_LIMITS = 50;
    static final int MAX_FEATURES = 100;
    static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");

    /** Plan validado, listo para guardar. */
    public record Data(String slug, String name, BigDecimal priceMonthly, BigDecimal priceYearly, int sortOrder,
            Map<String, Object> limits, List<String> features, boolean unlimited, Integer durationDays, boolean active) {
    }

    private AdminPlanRules() {
    }

    /** Valida el cuerpo; {@code currentSlug} es el slug guardado al editar (null al crear). */
    public static Data validate(AdminBody b, Map<String, Object> raw, String currentSlug) {
        AdminValidation v = new AdminValidation();
        String slug = b.text("slug");
        if (slug == null) {
            v.add("slug", "El slug es obligatorio.");
        } else if (!SLUG.matcher(slug).matches()) {
            v.add("slug", "El slug solo puede contener minúsculas, números, guiones y guiones bajos (máximo 50).");
        } else if (currentSlug != null && SYSTEM_SLUGS.contains(currentSlug) && !currentSlug.equals(slug)) {
            v.add("slug", "El slug de los planes del sistema (free, pro, business) no se puede cambiar.");
        }
        String name = b.text("name");
        v.required("name", name, "El nombre es obligatorio.");
        v.maxLength("name", name, 100, "El nombre no debe superar los 100 caracteres.");
        BigDecimal monthly = price(v, "priceMonthly", raw.get("priceMonthly"), true);
        BigDecimal yearly = price(v, "priceYearly", raw.get("priceYearly"), false);
        int sortOrder = 0;
        if (raw.get("sortOrder") != null) {
            Long s = b.integer("sortOrder");
            if (s == null || s < 0 || s > 100_000) {
                v.add("sortOrder", "El orden debe ser un entero entre 0 y 100000.");
            } else {
                sortOrder = s.intValue();
            }
        }
        Integer duration = null;
        if (raw.get("durationDays") != null) {
            Long d = b.integer("durationDays");
            if (d == null || d < 1 || d > 3650) {
                v.add("durationDays", "La vigencia debe ser un entero entre 1 y 3650 días.");
            } else {
                duration = d.intValue();
            }
        }
        Boolean unlimited = raw.get("isUnlimited") == null ? Boolean.FALSE : b.bool("isUnlimited");
        if (unlimited == null) {
            v.add("isUnlimited", "isUnlimited debe ser true o false.");
        }
        Boolean active = raw.get("isActive") == null ? Boolean.TRUE : b.bool("isActive");
        if (active == null) {
            v.add("isActive", "isActive debe ser true o false.");
        }
        Map<String, Object> limits = limits(v, raw.get("limits"));
        List<String> features = features(v, raw.get("features"));
        v.throwIfAny();
        if (Boolean.TRUE.equals(unlimited)) {
            limits.put("documents_month", -1);
            limits.put("internal_documents_month", -1);
        }
        return new Data(slug, name, monthly, yearly == null ? BigDecimal.ZERO.setScale(2) : yearly, sortOrder, limits, features,
                Boolean.TRUE.equals(unlimited), duration, Boolean.TRUE.equals(active));
    }

    private static BigDecimal price(AdminValidation v, String property, Object value, boolean required) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            if (required) {
                v.add(property, "El precio es obligatorio.");
            }
            return null;
        }
        try {
            BigDecimal d = value instanceof Number n ? new BigDecimal(n.toString()) : new BigDecimal(String.valueOf(value).trim());
            if (d.signum() < 0 || d.compareTo(MAX_PRICE) > 0 || d.stripTrailingZeros().scale() > 2) {
                v.add(property, "El precio debe ser un número entre 0 y 99999999.99, con hasta 2 decimales.");
                return null;
            }
            return d.setScale(2, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException e) {
            v.add(property, "El precio debe ser un número.");
            return null;
        }
    }

    private static Map<String, Object> limits(AdminValidation v, Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value == null) {
            return out;
        }
        if (!(value instanceof Map<?, ?> m)) {
            v.add("limits", "limits debe ser un objeto {clave: número}.");
            return out;
        }
        if (m.size() > MAX_LIMITS) {
            v.add("limits", "Como máximo " + MAX_LIMITS + " límites.");
            return out;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object raw = e.getValue();
            if (raw == null || (raw instanceof String s && s.isBlank())) {
                continue;
            }
            if (!KEY.matcher(key).matches()) {
                v.add("limits." + safe(key), "La clave del límite solo puede contener minúsculas, números y guion bajo.");
                continue;
            }
            Long n = AdminBody.of(Map.of("x", raw)).integer("x");
            if (n == null || n < -1 || n > Integer.MAX_VALUE) {
                v.add("limits." + key, "El límite debe ser -1 (ilimitado) o un número entero mayor o igual a 0.");
                continue;
            }
            out.put(key, n.intValue());
        }
        return out;
    }

    private static List<String> features(AdminValidation v, Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (!(value instanceof List<?> l)) {
            v.add("features", "features debe ser una lista de textos.");
            return new ArrayList<>();
        }
        if (l.size() > MAX_FEATURES) {
            v.add("features", "Como máximo " + MAX_FEATURES + " funcionalidades.");
            return new ArrayList<>();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object o : l) {
            if (!(o instanceof String s) || !KEY.matcher(s).matches()) {
                v.add("features", "Cada funcionalidad debe ser snake_case (minúsculas, números y guion bajo, máximo 50).");
                continue;
            }
            out.add(s);
        }
        return new ArrayList<>(out);
    }

    private static String safe(String key) {
        String k = key.length() > 50 ? key.substring(0, 50) : key;
        return k.replaceAll("[^A-Za-z0-9_.-]", "?");
    }
}
