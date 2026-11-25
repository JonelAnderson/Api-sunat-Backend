package pe.facturacion.api.sunat.builder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code new DateTime($s, new DateTimeZone('America/Lima'))} seguido del filtro {@code date} de
 * Twig (que convierte a hora de Lima): si la cadena trae zona horaria se convierte a Lima; si
 * no, se interpreta como hora local de Lima.
 */
public final class PhpDates {

    public static final ZoneId LIMA = ZoneId.of("America/Lima");

    private static final Pattern DATE_TIME = Pattern.compile(
            "^\\s*(\\d{4})-(\\d{2})-(\\d{2})(?:[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?(?:\\.\\d+)?)?\\s*(Z|[+-]\\d{2}:?\\d{2})?\\s*$");

    private PhpDates() {
    }

    public static LocalDateTime parseDateTime(String s) {
        Matcher m = DATE_TIME.matcher(s == null ? "" : s);
        if (!m.matches()) {
            throw new IllegalArgumentException("Fecha no válida: " + s);
        }
        LocalDateTime local = LocalDateTime.of(
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                m.group(4) == null ? 0 : Integer.parseInt(m.group(4)),
                m.group(5) == null ? 0 : Integer.parseInt(m.group(5)),
                m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
        String zone = m.group(7);
        if (zone == null) {
            return local;
        }
        String offset = zone.equals("Z") ? "+00:00" : (zone.contains(":") ? zone : zone.substring(0, 3) + ":" + zone.substring(3));
        return OffsetDateTime.of(local, java.time.ZoneOffset.of(offset)).atZoneSameInstant(LIMA).toLocalDateTime();
    }

    public static LocalDate parseDate(String s) {
        return parseDateTime(s).toLocalDate();
    }
}
