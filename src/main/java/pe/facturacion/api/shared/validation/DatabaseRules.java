package pe.facturacion.api.shared.validation;

import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import pe.facturacion.api.shared.http.Input;

/**
 * Reglas {@code exists:tabla,columna} y {@code unique:tabla,columna} de Laravel. Solo admite
 * tablas y columnas de una lista blanca (el nombre se interpola en el SQL).
 */
@Component
public class DatabaseRules {

    private static final Set<String> TABLES = Set.of("sucursales", "plans", "tenants", "clients", "series", "users");
    private static final java.util.regex.Pattern IDENT = java.util.regex.Pattern.compile("^[a-z_]+$");

    private final JdbcTemplate jdbc;

    public DatabaseRules(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(String table, String column, Object value) {
        return count(table, column, value) > 0;
    }

    public boolean unique(String table, String column, Object value) {
        return count(table, column, value) == 0;
    }

    private long count(String table, String column, Object value) {
        if (!TABLES.contains(table) || !IDENT.matcher(column).matches()) {
            throw new IllegalArgumentException("Tabla/columna no permitida en regla de BD: " + table + "." + column);
        }
        if (value == null || PhpValues.isArray(value)) {
            return 0;
        }
        Object param = "id".equals(column) ? (Object) (long) Input.intval(value) : PhpValues.toPhpString(value);
        if ("id".equals(column) && !PhpValues.isInteger(value)) {
            return 0;
        }
        // Como Laravel, no excluye registros con borrado lógico.
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, param);
        return n == null ? 0 : n;
    }
}
