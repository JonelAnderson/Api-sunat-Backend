package pe.facturacion.api.modules.admin.panel;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import pe.facturacion.api.modules.admin.api.AdminValidation;
import pe.facturacion.api.shared.time.Clock;

/** Parámetros comunes de los listados del panel. */
final class AdminPanelParams {

    /** Rango máximo de fechas de los listados por periodo. */
    static final int MAX_RANGE_DAYS = 366;

    private AdminPanelParams() {
    }

    /** {@code stateFilter}: 1 / true → activos, 0 / false → inactivos, otra cosa → todos. */
    static Boolean state(String stateFilter) {
        if (stateFilter == null) {
            return null;
        }
        String s = stateFilter.trim();
        if ("1".equals(s) || "true".equalsIgnoreCase(s)) {
            return Boolean.TRUE;
        }
        if ("0".equals(s) || "false".equalsIgnoreCase(s)) {
            return Boolean.FALSE;
        }
        return null;
    }

    static String text(String textFilter) {
        return textFilter == null || textFilter.isBlank() ? null : textFilter.trim();
    }

    /** {@code mes=yyyy-MM}; por defecto el mes actual en Lima. */
    static YearMonth month(String mes) {
        if (mes == null || mes.isBlank()) {
            return YearMonth.from(Clock.todayLima());
        }
        if (!mes.trim().matches("\\d{4}-(0[1-9]|1[0-2])")) {
            new AdminValidation().add("mes", "El mes debe tener el formato yyyy-MM.").throwIfAny();
        }
        try {
            return YearMonth.parse(mes.trim());
        } catch (DateTimeParseException e) {
            new AdminValidation().add("mes", "El mes debe tener el formato yyyy-MM.").throwIfAny();
            return null;
        }
    }

    /** Valida que el inicio no sea posterior al fin y que el rango no pase de {@link #MAX_RANGE_DAYS}. */
    static void checkRange(LocalDate start, LocalDate end) {
        if (start != null && end != null) {
            if (start.isAfter(end)) {
                new AdminValidation().add("startDate", "La fecha de inicio no puede ser posterior a la de fin.").throwIfAny();
            }
            if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
                new AdminValidation().add("endDate", "El rango no puede superar los " + MAX_RANGE_DAYS + " días.").throwIfAny();
            }
        }
    }

    /** Entero o {@code fallback} si viene vacío; valida el rango. */
    static int intParam(String property, String raw, int fallback, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String s = raw.trim();
        if (!s.matches("-?\\d{1,9}") || Integer.parseInt(s) < min || Integer.parseInt(s) > max) {
            new AdminValidation().add(property, "Debe ser un número entre " + min + " y " + max + ".").throwIfAny();
        }
        return Integer.parseInt(s);
    }

    static Long longParam(String property, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        if (!s.matches("\\d{1,18}")) {
            new AdminValidation().add(property, "Debe ser un número.").throwIfAny();
        }
        return Long.parseLong(s);
    }
}
