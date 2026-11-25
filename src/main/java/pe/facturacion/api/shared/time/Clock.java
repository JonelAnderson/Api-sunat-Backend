package pe.facturacion.api.shared.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Fechas con la convención de API-PRO:
 * <ul>
 *   <li>{@code created_at}, {@code updated_at}, {@code sent_at}: UTC (config/app.php timezone=UTC);</li>
 *   <li>{@code fecha_emision} y fechas de negocio SUNAT: hora de Lima;</li>
 *   <li>salidas ISO 8601 como Carbon {@code toIso8601String()}: {@code 2026-10-24T15:04:05+00:00}.</li>
 * </ul>
 */
public final class Clock {

    public static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");
    public static final DateTimeFormatter Y_M_D_H_I_S = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private static volatile java.time.Clock clock = java.time.Clock.systemUTC();

    private Clock() {
    }

    /** Solo para pruebas. */
    public static void useFixed(Instant instant) {
        clock = java.time.Clock.fixed(instant, ZoneOffset.UTC);
    }

    public static void useSystem() {
        clock = java.time.Clock.systemUTC();
    }

    public static Instant now() {
        return Instant.now(clock);
    }

    /** {@code now()} de Laravel con timezone UTC, sin fracción (columnas timestamp(0)). */
    public static LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(now(), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    public static LocalDateTime nowLima() {
        return LocalDateTime.ofInstant(now(), LIMA).truncatedTo(ChronoUnit.SECONDS);
    }

    public static LocalDate todayLima() {
        return LocalDate.ofInstant(now(), LIMA);
    }

    /** ISO 8601 en UTC de un timestamp guardado en UTC. */
    public static String iso8601Utc(LocalDateTime utc) {
        return utc == null ? null : OffsetDateTime.of(utc, ZoneOffset.UTC).format(ISO);
    }

    public static String iso8601Utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).format(ISO);
    }
}
