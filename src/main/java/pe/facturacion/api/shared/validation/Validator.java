package pe.facturacion.api.shared.validation;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.multipart.MultipartFile;

/**
 * Validador con la misma semántica que {@code Illuminate\Validation\Validator} (Laravel 12),
 * para producir exactamente los mismos errores (orden, claves y mensajes en español) que API-PRO.
 *
 * <p>Las reglas se declaran con la sintaxis de Laravel, copiadas literalmente de los
 * FormRequests: {@code "required|string|max:255"} o una lista con cadenas y {@link CustomRule}.
 *
 * <pre>
 * Validator v = Validator.make(input, rules)
 *         .messages(Map.of("serie.regex", "La serie de factura debe empezar con F..."))
 *         .after(val -> { if (...) val.addError("cliente.tipo_doc", "..."); });
 * Map&lt;String, Object&gt; datos = v.validate();   // lanza ValidationException (422)
 * </pre>
 */
public final class Validator {

    private static final Set<String> IMPLICIT = Set.of(
            "accepted", "accepted_if", "declined", "declined_if", "filled", "missing", "missing_if",
            "missing_unless", "missing_with", "missing_with_all", "present", "present_if", "present_unless",
            "present_with", "present_with_all", "required", "required_if", "required_if_accepted",
            "required_if_declined", "required_unless", "required_with", "required_with_all",
            "required_without", "required_without_all");
    private static final Set<String> NUMERIC_RULES = Set.of("numeric", "integer", "decimal");
    private static final Set<String> SIZE_MESSAGE_RULES = Set.of("size", "between", "min", "max", "gt", "lt", "gte", "lte");
    private static final Set<String> MARKERS = Set.of("nullable", "sometimes", "bail");

    private final Map<String, Object> data;
    private final LinkedHashMap<String, List<Object>> declaredRules = new LinkedHashMap<>();
    private final Map<String, String> customMessages = new LinkedHashMap<>();
    private final Map<String, String> customAttributes = new HashMap<>();
    private final List<Consumer<Validator>> afterHooks = new ArrayList<>();

    private LinkedHashMap<String, List<ParsedRule>> rules;
    private final Map<String, String> primaryAttribute = new HashMap<>();
    private final Set<String> implicitAttributes = new HashSet<>();
    private final LinkedHashMap<String, List<String>> errors = new LinkedHashMap<>();
    private final Map<String, Set<String>> failedRules = new HashMap<>();
    private boolean ran;

    private record ParsedRule(String name, List<String> params, CustomRule custom) {
        boolean isImplicit() {
            return custom != null ? custom.implicit() : IMPLICIT.contains(name);
        }
    }

    private Validator(Map<String, Object> data) {
        this.data = data;
    }

    /**
     * @param data  entrada ya saneada (ver {@link InputSanitizer})
     * @param rules atributo → {@code "regla|regla:param"} o {@code List} de cadenas y {@link CustomRule}
     */
    public static Validator make(Map<String, Object> data, Map<String, ?> rules) {
        Validator v = new Validator(data);
        rules.forEach((attr, r) -> v.declaredRules.put(attr, toRuleList(r)));
        return v;
    }

    private static List<Object> toRuleList(Object r) {
        List<Object> out = new ArrayList<>();
        if (r instanceof String s) {
            for (String part : s.split("\\|")) {
                if (!part.isEmpty()) {
                    out.add(part);
                }
            }
        } else if (r instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) {
                    // En la forma de arreglo Laravel no separa por "|" (permite regex con "|")
                    out.add(s);
                } else {
                    out.add(o);
                }
            }
        } else if (r instanceof CustomRule c) {
            out.add(c);
        }
        return out;
    }

    public Validator messages(Map<String, String> messages) {
        customMessages.putAll(messages);
        return this;
    }

    public Validator attributes(Map<String, String> attributes) {
        customAttributes.putAll(attributes);
        return this;
    }

    /** Hook {@code withValidator()->after()}: se ejecuta siempre, aunque haya errores de reglas. */
    public Validator after(Consumer<Validator> hook) {
        afterHooks.add(hook);
        return this;
    }

    /** Habilita las reglas {@code exists:tabla,columna} y {@code unique:tabla,columna}. */
    public Validator database(DatabaseRules db) {
        this.db = db;
        return this;
    }

    private DatabaseRules db;

    private DatabaseRules db() {
        if (db == null) {
            throw new IllegalStateException("Reglas exists/unique requieren Validator.database(...)");
        }
        return db;
    }

    // ------------------------------------------------------------------ ejecución

    public boolean fails() {
        run();
        return !errors.isEmpty();
    }

    /** Valida y devuelve {@code validated()}; lanza {@link ValidationException} si hay errores. */
    public Map<String, Object> validate() {
        if (fails()) {
            throw new ValidationException(new LinkedHashMap<>(errors));
        }
        return validated();
    }

    public Map<String, List<String>> errors() {
        run();
        return errors;
    }

    private void run() {
        if (ran) {
            return;
        }
        ran = true;
        expandRules();
        for (Map.Entry<String, List<ParsedRule>> entry : rules.entrySet()) {
            String attribute = entry.getKey();
            for (ParsedRule rule : entry.getValue()) {
                validateAttribute(attribute, rule);
                if (shouldStopValidating(attribute)) {
                    break;
                }
            }
        }
        for (Consumer<Validator> hook : afterHooks) {
            hook.accept(this);
        }
    }

    /**
     * {@code ValidationRuleParser::explode()}: las reglas explícitas conservan su posición; cada
     * regla con comodín se elimina y sus expansiones se añaden AL FINAL (en el orden en que se
     * procesan los patrones). Si una expansión coincide con un atributo ya existente, sus reglas
     * se fusionan en esa posición.
     */
    private void expandRules() {
        rules = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> e : declaredRules.entrySet()) {
            if (!e.getKey().contains("*")) {
                rules.put(e.getKey(), new ArrayList<>(e.getValue().stream().map(Validator::parse).toList()));
            }
        }
        for (Map.Entry<String, List<Object>> e : declaredRules.entrySet()) {
            String pattern = e.getKey();
            if (!pattern.contains("*")) {
                continue;
            }
            List<ParsedRule> parsed = e.getValue().stream().map(Validator::parse).toList();
            for (String attr : DataPath.expand(data, pattern)) {
                primaryAttribute.put(attr, pattern);
                implicitAttributes.add(pattern);
                rules.computeIfAbsent(attr, k -> new ArrayList<>()).addAll(parsed);
            }
        }
    }

    private static ParsedRule parse(Object rule) {
        if (rule instanceof CustomRule c) {
            return new ParsedRule("custom", List.of(), c);
        }
        String s = rule.toString();
        int idx = s.indexOf(':');
        if (idx < 0) {
            return new ParsedRule(s.trim().toLowerCase(), List.of(), null);
        }
        String name = s.substring(0, idx).trim().toLowerCase();
        String raw = s.substring(idx + 1);
        List<String> params = "regex".equals(name) || "not_regex".equals(name)
                ? List.of(raw)
                : Arrays.stream(raw.split(",", -1)).map(String::trim).toList();
        return new ParsedRule(name, params, null);
    }

    private void validateAttribute(String attribute, ParsedRule rule) {
        if (rule.custom() == null && MARKERS.contains(rule.name())) {
            return;
        }
        Object value = value(attribute);
        if (!isValidatable(rule, attribute, value)) {
            return;
        }
        if (rule.custom() != null) {
            String message = rule.custom().validate(attribute, value, this);
            if (message != null) {
                addFailure(attribute, "custom", replace(message, attribute, rule));
            }
            return;
        }
        if (!passes(rule, attribute, value)) {
            addFailure(attribute, rule.name(), buildMessage(attribute, rule, value));
        }
    }

    private boolean isValidatable(ParsedRule rule, String attribute, Object value) {
        boolean implicit = rule.isImplicit();
        // presentOrRuleIsImplicit
        if (value instanceof String s && s.trim().isEmpty()) {
            if (!implicit) {
                return false;
            }
        } else if (value == DataPath.MISSING && !implicit) {
            return false;
        }
        // passesOptionalCheck (sometimes)
        if (hasRule(attribute, "sometimes") && !presentForSometimes(attribute)) {
            return false;
        }
        // isNotNullIfMarkedAsNullable
        if (!implicit && hasRule(attribute, "nullable") && (value == null || value == DataPath.MISSING)) {
            return false;
        }
        // hasNotFailedPreviousRuleIfPresenceRule
        if (("unique".equals(rule.name()) || "exists".equals(rule.name())) && errors.containsKey(attribute)) {
            return false;
        }
        return true;
    }

    /**
     * {@code passesOptionalCheck()}: el atributo existe en {@code Arr::dot(datos inicializados)}
     * o en el primer nivel. Los atributos expandidos de un comodín siempre se inicializan;
     * {@code Arr::dot} aplana los arrays no vacíos, por lo que un sub-arreglo no vacío de
     * segundo nivel cuenta como ausente (comportamiento de Laravel).
     */
    private boolean presentForSometimes(String attribute) {
        if (primaryAttribute.containsKey(attribute) || data.containsKey(attribute)) {
            return true;
        }
        Object v = DataPath.get(data, attribute);
        if (v == DataPath.MISSING) {
            return false;
        }
        return !(PhpValues.isArray(v) && PhpValues.count(v) > 0);
    }

    private boolean shouldStopValidating(String attribute) {
        if (hasRule(attribute, "bail")) {
            return errors.containsKey(attribute);
        }
        Set<String> failed = failedRules.get(attribute);
        if (failed == null) {
            return false;
        }
        for (String f : failed) {
            if (IMPLICIT.contains(f)) {
                return true;
            }
        }
        return false;
    }

    private void addFailure(String attribute, String rule, String message) {
        errors.computeIfAbsent(attribute, k -> new ArrayList<>()).add(message);
        failedRules.computeIfAbsent(attribute, k -> new HashSet<>()).add(rule);
    }

    // ------------------------------------------------------------------ API para hooks after

    /** {@code $validator->errors()->add($attribute, $message)}: mensaje literal. */
    public void addError(String attribute, String message) {
        errors.computeIfAbsent(attribute, k -> new ArrayList<>()).add(message);
    }

    public boolean hasError(String attribute) {
        return errors.containsKey(attribute);
    }

    /** Valor del atributo o {@link DataPath#MISSING}. */
    public Object value(String attribute) {
        return DataPath.get(data, attribute);
    }

    public Object input(String attribute) {
        return DataPath.getOrNull(data, attribute);
    }

    public Map<String, Object> data() {
        return data;
    }

    /** {@code validated()} de Laravel 12 (excluye claves no validadas de los arrays con hijos declarados). */
    public Map<String, Object> validated() {
        run();
        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> keys = rules.keySet();
        for (Map.Entry<String, List<ParsedRule>> e : rules.entrySet()) {
            String key = e.getKey();
            Object value = DataPath.get(data, key);
            if (value == DataPath.MISSING) {
                continue;
            }
            boolean isArrayRule = e.getValue().stream().anyMatch(r -> "array".equals(r.name()));
            if (isArrayRule && keys.stream().anyMatch(k -> k.startsWith(key + "."))) {
                continue;
            }
            DataPath.set(result, key, value);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = (Map<String, Object>) DataPath.normalizeLists(result);
        return normalized;
    }

    // ------------------------------------------------------------------ reglas

    private boolean hasRule(String attribute, String rule) {
        List<ParsedRule> list = rules.get(attribute);
        return list != null && list.stream().anyMatch(r -> r.custom() == null && r.name().equals(rule));
    }

    private boolean hasAnyRule(String attribute, Set<String> names) {
        List<ParsedRule> list = rules.get(attribute);
        return list != null && list.stream().anyMatch(r -> r.custom() == null && names.contains(r.name()));
    }

    private boolean passes(ParsedRule rule, String attribute, Object v) {
        Object value = v == DataPath.MISSING ? null : v;
        List<String> p = rule.params();
        return switch (rule.name()) {
            case "required", "filled" -> !PhpValues.isEmptyForRequired(value);
            case "present" -> v != DataPath.MISSING;
            case "required_with" -> !anyFilled(p) || !PhpValues.isEmptyForRequired(value);
            case "required_with_all" -> !allFilled(p) || !PhpValues.isEmptyForRequired(value);
            case "required_without" -> !anyEmpty(p) || !PhpValues.isEmptyForRequired(value);
            case "required_without_all" -> !allEmpty(p) || !PhpValues.isEmptyForRequired(value);
            case "required_if" -> !dependentMatches(p) || !PhpValues.isEmptyForRequired(value);
            case "required_unless" -> dependentMatches(p) || !PhpValues.isEmptyForRequired(value);
            case "accepted" -> isAccepted(value);
            case "string" -> value instanceof String;
            case "integer" -> PhpValues.isInteger(value);
            case "numeric" -> PhpValues.isNumeric(value);
            case "boolean" -> isBooleanLike(value);
            case "array", "list" -> PhpValues.isArray(value) && (!"list".equals(rule.name()) || value instanceof List);
            case "in" -> passesIn(attribute, value, p);
            case "not_in" -> !passesIn(attribute, value, p);
            case "regex" -> passesRegex(value, p.get(0));
            case "not_regex" -> (value instanceof String || PhpValues.isNumeric(value)) && !passesRegex(value, p.get(0));
            case "size" -> compareSize(attribute, value).compareTo(new BigDecimal(p.get(0))) == 0;
            case "min" -> compareSize(attribute, value).compareTo(new BigDecimal(p.get(0))) >= 0;
            case "max" -> compareSize(attribute, value).compareTo(new BigDecimal(p.get(0))) <= 0;
            case "between" -> {
                BigDecimal s = compareSize(attribute, value);
                yield s.compareTo(new BigDecimal(p.get(0))) >= 0 && s.compareTo(new BigDecimal(p.get(1))) <= 0;
            }
            case "gt" -> compareWith(attribute, value, p.get(0), c -> c > 0);
            case "gte" -> compareWith(attribute, value, p.get(0), c -> c >= 0);
            case "lt" -> compareWith(attribute, value, p.get(0), c -> c < 0);
            case "lte" -> compareWith(attribute, value, p.get(0), c -> c <= 0);
            case "digits" -> {
                String s = PhpValues.toPhpString(value);
                yield s.matches("\\d+") && s.length() == Integer.parseInt(p.get(0));
            }
            case "digits_between" -> {
                String s = PhpValues.toPhpString(value);
                yield s.matches("\\d+") && s.length() >= Integer.parseInt(p.get(0)) && s.length() <= Integer.parseInt(p.get(1));
            }
            case "alpha_num" -> value instanceof String s && s.matches("^[\\p{L}\\p{M}\\p{N}]+$");
            case "date" -> isDate(value);
            case "date_format" -> isDateFormat(value, p.get(0));
            case "after" -> compareDates(value, p.get(0), c -> c > 0);
            case "after_or_equal" -> compareDates(value, p.get(0), c -> c >= 0);
            case "before" -> compareDates(value, p.get(0), c -> c < 0);
            case "before_or_equal" -> compareDates(value, p.get(0), c -> c <= 0);
            case "email" -> value instanceof String s && isEmail(s);
            case "url" -> value instanceof String s && isUrl(s);
            case "json" -> value instanceof String s && isJson(s);
            case "decimal" -> isDecimal(value, p);
            case "file" -> value instanceof MultipartFile f && !f.isEmpty();
            // Laravel 11+: "image" ya no admite SVG salvo allow_svg (equivale a SEC-012)
            case "image" -> value instanceof MultipartFile f && hasExtension(f, List.of("jpg", "jpeg", "png", "gif", "bmp", "webp"));
            case "mimes" -> value instanceof MultipartFile f && hasExtension(f, p);
            case "exists" -> db().exists(p.get(0), p.size() > 1 ? p.get(1) : attribute, value);
            case "unique" -> db().unique(p.get(0), p.size() > 1 ? p.get(1) : attribute, value);
            default -> throw new IllegalArgumentException("Regla de validación no soportada: " + rule.name());
        };
    }

    private boolean anyFilled(List<String> attrs) {
        return attrs.stream().anyMatch(a -> !PhpValues.isEmptyForRequired(DataPath.getOrNull(data, a)));
    }

    private boolean allFilled(List<String> attrs) {
        return attrs.stream().allMatch(a -> !PhpValues.isEmptyForRequired(DataPath.getOrNull(data, a)));
    }

    private boolean anyEmpty(List<String> attrs) {
        return attrs.stream().anyMatch(a -> PhpValues.isEmptyForRequired(DataPath.getOrNull(data, a)));
    }

    private boolean allEmpty(List<String> attrs) {
        return attrs.stream().allMatch(a -> PhpValues.isEmptyForRequired(DataPath.getOrNull(data, a)));
    }

    /** ¿El valor del campo {@code params[0]} está en {@code params[1..]}? (comparación de PHP). */
    private boolean dependentMatches(List<String> params) {
        Object other = DataPath.getOrNull(data, params.get(0));
        List<String> values = params.subList(1, params.size());
        for (String candidate : values) {
            if (other instanceof Boolean b) {
                if (("true".equals(candidate) && b) || ("false".equals(candidate) && !b)) {
                    return true;
                }
            } else if (other == null) {
                if ("null".equals(candidate)) {
                    return true;
                }
            } else if (looseEquals(other, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looseEquals(Object a, String b) {
        String sa = PhpValues.toPhpString(a);
        if (PhpValues.isNumeric(sa) && PhpValues.isNumeric(b)) {
            return PhpValues.toNumber(sa).compareTo(PhpValues.toNumber(b)) == 0;
        }
        return sa.equals(b);
    }

    private boolean passesIn(String attribute, Object value, List<String> params) {
        if (PhpValues.isArray(value)) {
            if (!hasRule(attribute, "array")) {
                return false;
            }
            Iterable<?> items = value instanceof Map<?, ?> m ? m.values() : (List<?>) value;
            for (Object item : items) {
                if (PhpValues.isArray(item) || !params.contains(PhpValues.toPhpString(item))) {
                    return false;
                }
            }
            return true;
        }
        return params.contains(PhpValues.toPhpString(value));
    }

    private static boolean passesRegex(Object value, String phpPattern) {
        if (!(value instanceof String) && !PhpValues.isNumeric(value)) {
            return false;
        }
        return PhpRegex.compile(phpPattern).matcher(PhpValues.toPhpString(value)).find();
    }

    private BigDecimal compareSize(String attribute, Object value) {
        return getSize(attribute, value);
    }

    /** {@code getSize()} de Laravel. */
    private BigDecimal getSize(String attribute, Object value) {
        if (PhpValues.isNumeric(value) && hasAnyRule(attribute, NUMERIC_RULES)) {
            return PhpValues.toNumber(value);
        }
        if (PhpValues.isArray(value)) {
            return BigDecimal.valueOf(PhpValues.count(value));
        }
        if (value instanceof MultipartFile f) {
            return BigDecimal.valueOf(f.getSize()).divide(BigDecimal.valueOf(1024), 10, java.math.RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(PhpValues.mbStrlen(PhpValues.toPhpString(value)));
    }

    /** {@code validateGt/Gte/Lt/Lte} de Laravel 12. */
    private boolean compareWith(String attribute, Object value, String param, java.util.function.IntPredicate ok) {
        Object compared = DataPath.getOrNull(data, param);
        if (compared == null && PhpValues.isNumeric(value) && PhpValues.isNumeric(param)) {
            return ok.test(getSize(attribute, value).compareTo(new BigDecimal(param.trim())));
        }
        if (PhpValues.isNumeric(param)) {
            return false;
        }
        if (hasAnyRule(attribute, NUMERIC_RULES) && PhpValues.isNumeric(value) && PhpValues.isNumeric(compared)) {
            return ok.test(PhpValues.toNumber(value).compareTo(PhpValues.toNumber(compared)));
        }
        if (!sameType(value, compared)) {
            return false;
        }
        return ok.test(getSize(attribute, value).compareTo(getSize(attribute, compared)));
    }

    /** {@code isSameType()}: {@code gettype($a) === gettype($b)}. */
    private static boolean sameType(Object a, Object b) {
        return phpType(a).equals(phpType(b));
    }

    private static String phpType(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short) {
            return "integer";
        }
        if (v instanceof Number) {
            return "double";
        }
        if (v instanceof String) {
            return "string";
        }
        if (PhpValues.isArray(v)) {
            return "array";
        }
        return "object";
    }

    private static boolean isAccepted(Object v) {
        return v instanceof Boolean b ? b
                : v != null && List.of("yes", "on", "1", "true").contains(PhpValues.toPhpString(v).toLowerCase())
                        && !(v instanceof Number n && n.doubleValue() != 1);
    }

    private static boolean isBooleanLike(Object v) {
        if (v instanceof Boolean) {
            return true;
        }
        if (v instanceof Integer i) {
            return i == 0 || i == 1;
        }
        if (v instanceof Long l) {
            return l == 0 || l == 1;
        }
        return v instanceof String s && ("0".equals(s) || "1".equals(s));
    }

    private static final Pattern ISO_DATE = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})([ T]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d+)?)?)?(Z|[+-]\\d{2}:?\\d{2})?$");
    private static final Pattern DMY = Pattern.compile("^(\\d{1,2})[-.](\\d{1,2})[-.](\\d{4})$");
    private static final Pattern MDY = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$");
    private static final Pattern YMD_SLASH = Pattern.compile("^(\\d{4})/(\\d{1,2})/(\\d{1,2})$");

    /** Aproximación de {@code strtotime() !== false && checkdate()} para los formatos usuales. */
    private static boolean isDate(Object value) {
        if (!(value instanceof String s)) {
            return false;
        }
        String t = s.trim();
        Matcher m;
        if ((m = ISO_DATE.matcher(t)).matches() || (m = YMD_SLASH.matcher(t)).matches()) {
            return validDate(m.group(1), m.group(2), m.group(3));
        }
        if ((m = DMY.matcher(t)).matches()) {
            return validDate(m.group(3), m.group(2), m.group(1));
        }
        if ((m = MDY.matcher(t)).matches()) {
            return validDate(m.group(3), m.group(1), m.group(2));
        }
        return List.of("now", "today", "tomorrow", "yesterday").contains(t.toLowerCase());
    }

    /**
     * {@code compareDates()} de Laravel: el parámetro es otro campo (si existe en la entrada) o una
     * fecha; si alguno no se puede interpretar como fecha, la regla falla.
     */
    private boolean compareDates(Object value, String param, java.util.function.IntPredicate ok) {
        Object other = DataPath.get(data, param);
        java.time.LocalDateTime a = dateValue(value);
        java.time.LocalDateTime b = dateValue(other == DataPath.MISSING ? param : other);
        return a != null && b != null && ok.test(a.compareTo(b));
    }

    /** {@code strtotime()} para los mismos formatos que {@link #isDate}; null si no aplica. */
    static java.time.LocalDateTime dateValue(Object value) {
        if (!(value instanceof String s) || !isDate(s)) {
            return null;
        }
        String t = s.trim();
        java.time.LocalDate today = pe.facturacion.api.shared.time.Clock.nowUtc().toLocalDate();
        switch (t.toLowerCase()) {
            case "now":
                return pe.facturacion.api.shared.time.Clock.nowUtc();
            case "today":
                return today.atStartOfDay();
            case "tomorrow":
                return today.plusDays(1).atStartOfDay();
            case "yesterday":
                return today.minusDays(1).atStartOfDay();
            default:
                break;
        }
        Matcher m;
        if ((m = ISO_DATE.matcher(t)).matches() || (m = YMD_SLASH.matcher(t)).matches()) {
            java.time.LocalDate d = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            String time = m.groupCount() >= 4 && m.group(4) != null ? m.group(4).trim().replace("T", "") : null;
            if (time == null) {
                return d.atStartOfDay();
            }
            String[] hms = time.split("[:.]");
            return d.atTime(Integer.parseInt(hms[0]), Integer.parseInt(hms[1]), hms.length > 2 ? Integer.parseInt(hms[2]) : 0);
        }
        if ((m = DMY.matcher(t)).matches()) {
            return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1))).atStartOfDay();
        }
        if ((m = MDY.matcher(t)).matches()) {
            return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))).atStartOfDay();
        }
        return null;
    }

    private static boolean validDate(String y, String mo, String d) {
        try {
            LocalDate.of(Integer.parseInt(y), Integer.parseInt(mo), Integer.parseInt(d));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isDateFormat(Object value, String phpFormat) {
        if (!(value instanceof String s)) {
            return false;
        }
        String javaPattern = phpFormat.replace("Y", "uuuu").replace("m", "MM").replace("d", "dd")
                .replace("H", "HH").replace("i", "mm").replace("s", "ss");
        try {
            DateTimeFormatter f = DateTimeFormatter.ofPattern(javaPattern).withResolverStyle(ResolverStyle.STRICT);
            f.parse(s);
            return true;
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return false;
        }
    }

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@\"(),:;<>\\[\\\\\\]]+@[^\\s@\"(),:;<>\\[\\\\\\]]+\\.[^\\s@\"(),:;<>\\[\\\\\\]]+$");

    private static boolean isEmail(String s) {
        return EMAIL.matcher(s).matches() && !s.contains("..");
    }

    private static boolean isUrl(String s) {
        try {
            URI uri = new URI(s);
            return uri.getScheme() != null && uri.getHost() != null
                    && List.of("http", "https", "ftp", "ftps").contains(uri.getScheme().toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isJson(String s) {
        return pe.facturacion.api.shared.json.PhpJson.decode(s) != null || "null".equals(s.trim());
    }

    private static boolean isDecimal(Object value, List<String> params) {
        if (!PhpValues.isNumeric(value)) {
            return false;
        }
        String s = PhpValues.toPhpString(value);
        int dot = s.indexOf('.');
        int decimals = dot < 0 ? 0 : s.length() - dot - 1;
        int min = Integer.parseInt(params.get(0));
        int max = params.size() > 1 ? Integer.parseInt(params.get(1)) : min;
        return decimals >= min && decimals <= max;
    }

    /**
     * {@code mimes}: compara contra la extensión deducida del CONTENIDO ({@code guessExtension()}
     * de Laravel); si el tipo no se reconoce por firma, se usa la extensión del nombre.
     */
    private static boolean hasExtension(MultipartFile f, List<String> exts) {
        String ext = FileSniffer.guessExtension(f);
        List<String> allowed = exts.stream().map(String::toLowerCase).toList();
        return allowed.contains(ext) || (allowed.contains("jpg") && "jpeg".equals(ext))
                || (allowed.contains("jpeg") && "jpg".equals(ext));
    }

    // ------------------------------------------------------------------ mensajes

    private String buildMessage(String attribute, ParsedRule rule, Object value) {
        String custom = customMessage(attribute, rule.name());
        String message;
        if (custom != null) {
            message = custom;
        } else if (SIZE_MESSAGE_RULES.contains(rule.name())) {
            message = ValidationMessages.get(rule.name(), attributeType(attribute, value));
        } else {
            message = ValidationMessages.get(rule.name(), null);
        }
        return replace(message, attribute, rule);
    }

    /** {@code getFromLocalArray()}: claves "atributo.regla" y "atributo", con comodines. */
    private String customMessage(String attribute, String rule) {
        for (String key : List.of(attribute + "." + rule, attribute)) {
            for (Map.Entry<String, String> e : customMessages.entrySet()) {
                String sourceKey = e.getKey();
                if (sourceKey.contains("*")) {
                    String regex = "^" + Pattern.quote(sourceKey).replace("*", "\\E([^.]*)\\Q") + "$";
                    if (Pattern.compile(regex).matcher(key).matches()) {
                        return e.getValue();
                    }
                } else if (sourceKey.equals(key)) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    private String attributeType(String attribute, Object value) {
        if (hasAnyRule(attribute, NUMERIC_RULES)) {
            return "numeric";
        }
        if (hasRule(attribute, "array") || hasRule(attribute, "list")) {
            return "array";
        }
        if (value instanceof MultipartFile) {
            return "file";
        }
        return "string";
    }

    private String replace(String message, String attribute, ParsedRule rule) {
        String display = displayableAttribute(attribute);
        String out = message
                .replace(":attribute", display)
                .replace(":Attribute", ucfirst(display))
                .replace(":ATTRIBUTE", display.toUpperCase());
        List<String> p = rule.params();
        switch (rule.name()) {
            case "min" -> out = out.replace(":min", p.get(0));
            case "max" -> out = out.replace(":max", p.get(0));
            case "size" -> out = out.replace(":size", p.get(0));
            case "between", "digits_between" -> out = out.replace(":min", p.get(0)).replace(":max", p.get(1));
            case "digits" -> out = out.replace(":digits", p.get(0));
            case "decimal" -> out = out.replace(":decimal", p.size() > 1 ? p.get(0) + "-" + p.get(1) : p.get(0));
            case "date_format" -> out = out.replace(":format", p.get(0));
            // replaceAfter() y similares: nombre visible si el parámetro es otro campo; si no, la fecha
            case "after", "after_or_equal", "before", "before_or_equal" ->
                    out = out.replace(":date", DataPath.get(data, p.get(0)) == DataPath.MISSING ? p.get(0) : displayableAttribute(p.get(0)));
            case "in", "not_in", "mimes" -> out = out.replace(":values", String.join(", ",
                    p.stream().map(v -> displayableValue(attribute, v)).toList()));
            case "required_with", "required_with_all", "required_without", "required_without_all" ->
                    out = out.replace(":values", String.join(" / ", p.stream().map(this::displayableAttribute).toList()));
            case "required_if" -> out = out.replace(":other", displayableAttribute(p.get(0)))
                    .replace(":value", displayableValue(p.get(0), DataPath.getOrNull(data, p.get(0))));
            case "required_unless" -> out = out.replace(":other", displayableAttribute(p.get(0)))
                    .replace(":values", String.join(", ", p.subList(1, p.size()).stream()
                            .map(v -> displayableValue(p.get(0), v)).toList()));
            case "gt", "gte", "lt", "lte" -> {
                // replaceGt(): sin valor en el campo → nombre visible del parámetro; si no, su tamaño
                Object other = DataPath.getOrNull(data, p.get(0));
                String v = other == null ? displayableAttribute(p.get(0))
                        : getSize(attribute, other).stripTrailingZeros().toPlainString();
                out = out.replace(":value", v);
            }
            default -> {
            }
        }
        return out;
    }

    /** {@code getDisplayableAttribute()} de Laravel. */
    public String displayableAttribute(String attribute) {
        String primary = primaryAttribute.getOrDefault(attribute, attribute);
        List<String> expected = attribute.equals(primary) ? List.of(attribute) : List.of(attribute, primary);
        for (String name : expected) {
            if (customAttributes.containsKey(name)) {
                return customAttributes.get(name);
            }
            String translated = ValidationMessages.attribute(name);
            if (translated != null) {
                return translated;
            }
        }
        if (implicitAttributes.contains(primary) && !attribute.equals(primary)) {
            return attribute;
        }
        return snake(attribute).replace('_', ' ');
    }

    private static String displayableValue(String attribute, Object value) {
        if (PhpValues.isArray(value)) {
            return "array";
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value == null) {
            return "empty";
        }
        return PhpValues.toPhpString(value);
    }

    /** {@code Str::snake()} para los nombres de atributo (ya suelen venir en snake_case). */
    private static String snake(String value) {
        if (value.equals(value.toLowerCase())) {
            return value;
        }
        String spaced = value.replaceAll("(.)(?=\\p{Lu})", "$1_");
        return spaced.toLowerCase().replaceAll("\\s+", "");
    }

    private static String ucfirst(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
