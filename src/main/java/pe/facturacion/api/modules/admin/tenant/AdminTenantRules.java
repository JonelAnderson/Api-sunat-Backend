package pe.facturacion.api.modules.admin.tenant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reglas puras de las escrituras sobre una empresa desde el panel (fase C, §11 del diseño): RUC, formato del logo por
 * su contenido, campos de credenciales que no viajan en la edición general y diferencias para la auditoría.
 */
public final class AdminTenantRules {

    /** Prefijos válidos de un RUC (personas naturales 10, no domiciliados 15 / 16 / 17, personas jurídicas 20). */
    static final List<String> RUC_PREFIXES = List.of("10", "15", "16", "17", "20");
    private static final int[] RUC_WEIGHTS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};

    /** Logo: como máximo 2 MB (lo mismo que {@code /api/v1/empresa/logo}). */
    static final long LOGO_MAX_BYTES = 2L * 1024 * 1024;
    /** Formatos de logo aceptados, reconocidos por su contenido (nunca por el nombre del archivo: hallazgo H4). */
    static final List<String> LOGO_FORMATS = List.of("png", "jpg", "webp");

    /**
     * Credenciales de SUNAT que no se aceptan en la edición general (van en sus endpoints de solo escritura, con el
     * permiso {@code Admin.Tenants.Credentials}), ni el logo (va en {@code POST tenants/{id}/logo}).
     */
    static final Set<String> NOT_IN_EDIT = Set.of("sol_user", "sol_pass", "client_id", "client_secret", "logo",
            "certificado", "contrasena_certificado");

    /** Claves del recurso de la empresa que no se comparan en la auditoría (derivadas o que cambian solas). */
    static final Set<String> AUDIT_IGNORED = Set.of("documentos_este_mes", "tasa_igv_vigente", "logo_url");

    private AdminTenantRules() {
    }

    /** Mensaje de error del RUC o null si es válido: 11 dígitos, prefijo de SUNAT y dígito verificador (módulo 11). */
    public static String rucError(String ruc) {
        if (ruc == null || ruc.isBlank()) {
            return "El RUC es obligatorio.";
        }
        if (!ruc.matches("\\d{11}")) {
            return "El RUC debe tener exactamente 11 dígitos.";
        }
        if (!RUC_PREFIXES.contains(ruc.substring(0, 2))) {
            return "El RUC debe empezar con 10, 15, 16, 17 o 20.";
        }
        if (checkDigit(ruc) != ruc.charAt(10) - '0') {
            return "El RUC no es válido: el dígito verificador no corresponde.";
        }
        return null;
    }

    /** Dígito verificador de SUNAT: pesos 5,4,3,2,7,6,5,4,3,2; 11 − (suma mód 11), con 10 → 0 y 11 → 1. */
    static int checkDigit(String ruc) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += (ruc.charAt(i) - '0') * RUC_WEIGHTS[i];
        }
        int r = 11 - (sum % 11);
        return r == 10 ? 0 : r == 11 ? 1 : r;
    }

    /** Formato del logo según sus primeros bytes ({@code png}, {@code jpg} o {@code webp}); null si no es ninguno. */
    public static String logoFormat(byte[] head) {
        if (head == null) {
            return null;
        }
        int n = head.length;
        if (n >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G' && head[4] == 0x0D
                && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) {
            return "png";
        }
        if (n >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (n >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F' && head[8] == 'W' && head[9] == 'E'
                && head[10] == 'B' && head[11] == 'P') {
            return "webp";
        }
        return null;
    }

    /** Claves del cuerpo de la edición que no se aceptan allí (orden alfabético, para el mensaje). */
    static List<String> forbiddenInEdit(Map<String, Object> body) {
        return body.keySet().stream().filter(NOT_IN_EDIT::contains).sorted().toList();
    }

    /**
     * Diferencias entre el recurso de la empresa antes y después (sin secretos: el recurso del panel ya no trae
     * {@code sol_user} ni {@code client_id}): {@code {clave: {antes, despues}}}.
     */
    static Map<String, Object> changes(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : after.entrySet()) {
            if (AUDIT_IGNORED.contains(e.getKey())) {
                continue;
            }
            Object old = before.get(e.getKey());
            if (!Objects.equals(normalize(old), normalize(e.getValue()))) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("antes", old);
                c.put("despues", e.getValue());
                out.put(e.getKey(), c);
            }
        }
        return out;
    }

    /** Números comparados por valor (1 y 1.0 son iguales). */
    private static Object normalize(Object v) {
        if (v instanceof Number n) {
            return new java.math.BigDecimal(n.toString()).stripTrailingZeros();
        }
        return v;
    }

    /** Últimos 4 caracteres de un identificador público (api_key) para reconocerlo sin mostrarlo. */
    static String last4(String value) {
        return value == null || value.length() < 12 ? null : value.substring(value.length() - 4);
    }
}
