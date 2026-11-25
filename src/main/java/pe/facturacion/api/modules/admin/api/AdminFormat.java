package pe.facturacion.api.modules.admin.api;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Formatos del contrato del panel: instantes en ISO-8601 UTC con {@code Z} ({@code 2026-11-23T15:04:05Z}), días como
 * {@code yyyy-MM-dd} (hora de Lima) y búsquedas {@code LIKE} con {@code %} / {@code _} escapados.
 */
public final class AdminFormat {

    private AdminFormat() {
    }

    /** Instante ISO-8601 UTC de una columna {@code timestamp(0)} guardada en UTC. */
    public static String instant(Object utc) {
        LocalDateTime t = toLocalDateTime(utc);
        return t == null ? null : DateTimeFormatter.ISO_INSTANT.format(t.truncatedTo(ChronoUnit.SECONDS).toInstant(ZoneOffset.UTC));
    }

    public static String instant(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }

    /** Día {@code yyyy-MM-dd} de una fecha u hora local (fecha_emision ya está en hora de Lima). */
    public static String day(Object value) {
        if (value instanceof LocalDate d) {
            return d.toString();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        LocalDateTime t = toLocalDateTime(value);
        return t == null ? null : t.toLocalDate().toString();
    }

    public static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime l) {
            return l;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof java.time.OffsetDateTime o) {
            return o.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        }
        return null;
    }

    /** {@code yyyy-MM-dd} o null si viene vacío; lanza un error de validación si no es una fecha. */
    public static LocalDate parseDay(String property, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            new AdminValidation().add(property, "La fecha debe tener el formato yyyy-MM-dd.").throwIfAny();
            return null;
        }
    }

    /** Patrón {@code %texto%} con los comodines de LIKE escapados (usar con {@code ESCAPE '\'}). */
    public static String likeContains(String text) {
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public static Long longOrNull(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    public static int intOrZero(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }
}
