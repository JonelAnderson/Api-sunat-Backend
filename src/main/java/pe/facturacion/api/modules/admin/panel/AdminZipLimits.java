package pe.facturacion.api.modules.admin.panel;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Topes de la exportación ZIP del panel (corrige el hallazgo H12 en la ruta del panel: el ZIP se arma en streaming,
 * nunca entero en memoria, y con límites conocidos antes de empezar a escribir).
 */
final class AdminZipLimits {

    /** Días como máximo entre fecha_desde y fecha_hasta (inclusive): un mes. */
    static final int MAX_DAYS = 31;
    /** Archivos como máximo en un ZIP. */
    static final int MAX_FILES = 2000;
    /** Bytes como máximo sumando los archivos (sin comprimir): 200 MB. */
    static final long MAX_BYTES = 200L * 1024 * 1024;

    private AdminZipLimits() {
    }

    /** Mensaje si el rango supera el tope del panel; null si está bien o si las fechas no se entienden (las valida la API). */
    static String rangeError(String desdeRaw, String hastaRaw) {
        LocalDate desde = day(desdeRaw);
        LocalDate hasta = day(hastaRaw);
        if (desde == null || hasta == null || desde.isAfter(hasta)) {
            return null;
        }
        if (ChronoUnit.DAYS.between(desde, hasta) + 1 > MAX_DAYS) {
            return "Desde el panel el ZIP abarca como máximo " + MAX_DAYS + " días. Divide el rango en meses.";
        }
        return null;
    }

    /** Mensaje si el ZIP pasaría de los topes de archivos o de tamaño; null si cabe. */
    static String sizeError(int files, long bytes) {
        if (files > MAX_FILES) {
            return "El ZIP tendría " + files + " archivos; el máximo desde el panel es " + MAX_FILES + ". Acota el rango o los tipos.";
        }
        if (bytes > MAX_BYTES) {
            return "El ZIP pesaría " + (bytes / (1024 * 1024)) + " MB; el máximo desde el panel es " + (MAX_BYTES / (1024 * 1024))
                    + " MB. Acota el rango o los tipos.";
        }
        return null;
    }

    private static LocalDate day(String raw) {
        if (raw == null || !raw.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
