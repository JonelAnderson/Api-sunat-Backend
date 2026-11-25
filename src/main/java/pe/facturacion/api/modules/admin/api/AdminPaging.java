package pe.facturacion.api.modules.admin.api;

/**
 * Paginación de los listados del panel: {@code records} 1–50 (0, vacío, negativo o no numérico → 10; más de 50 → 50)
 * y {@code numPage} desde 1 (menor a 1 o inválido → 1). La página se acota para que el desplazamiento no desborde.
 */
public record AdminPaging(int records, int numPage) {

    public static final int DEFAULT_RECORDS = 10;
    public static final int MAX_RECORDS = 50;
    /** Tope de página: 1 000 000 × 50 filas cabe holgado en un long. */
    public static final int MAX_PAGE = 1_000_000;

    public static AdminPaging of(String records, String numPage) {
        int r = parse(records);
        if (r <= 0) {
            r = DEFAULT_RECORDS;
        } else if (r > MAX_RECORDS) {
            r = MAX_RECORDS;
        }
        int p = parse(numPage);
        if (p < 1) {
            p = 1;
        } else if (p > MAX_PAGE) {
            p = MAX_PAGE;
        }
        return new AdminPaging(r, p);
    }

    public long offset() {
        return (long) (numPage - 1) * records;
    }

    /** Entero o 0 si viene vacío o no es un número (los valores enormes cuentan como "muy grandes"). */
    private static int parse(String raw) {
        if (raw == null) {
            return 0;
        }
        String s = raw.trim();
        if (!s.matches("-?\\d{1,18}")) {
            return 0;
        }
        long v = Long.parseLong(s);
        if (v > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return v < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) v;
    }
}
