package pe.facturacion.api.modules.admin.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import pe.facturacion.api.modules.admin.api.AdminFormat;
import pe.facturacion.api.modules.admin.api.AdminPaging;

/**
 * Acceso a {@code users} y {@code admin_refresh_tokens} para el panel. Solo datos: las reglas (bloqueo, último
 * superadmin, política de contraseñas) viven en los servicios y controladores. Fechas siempre en UTC.
 */
@Repository
public class AdminUserStore {

    private static final String COLUMNS = "id, name, email, password, is_admin, role, is_active, two_factor_secret, "
            + "two_factor_recovery_codes, two_factor_confirmed_at, failed_login_count, locked_until, session_version, last_login_at";

    private static final RowMapper<AdminUser> MAPPER = AdminUserStore::map;

    private final JdbcTemplate jdbc;

    public AdminUserStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static AdminUser map(ResultSet rs, int row) throws SQLException {
        return new AdminUser(rs.getLong("id"), rs.getString("name"), rs.getString("email"), rs.getString("password"),
                rs.getBoolean("is_admin"), rs.getString("role"), rs.getBoolean("is_active"), rs.getString("two_factor_secret"),
                rs.getString("two_factor_recovery_codes"), rs.getObject("two_factor_confirmed_at", LocalDateTime.class),
                rs.getInt("failed_login_count"), rs.getObject("locked_until", LocalDateTime.class), rs.getInt("session_version"),
                rs.getObject("last_login_at", LocalDateTime.class));
    }

    // ------------------------------------------------------------------ lectura

    /** Administrador por correo (sin distinguir mayúsculas). */
    public Optional<AdminUser> findAdminByEmail(String email) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE lower(email) = lower(?) AND is_admin = true ORDER BY id LIMIT 1",
                MAPPER, email).stream().findFirst();
    }

    public Optional<AdminUser> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** Administrador por id (los usuarios que no son administradores no existen para el panel). */
    public Optional<AdminUser> findAdminById(long id) {
        return findById(id).filter(AdminUser::admin);
    }

    /** Correo ya usado por cualquier usuario (también los dueños de empresas), sin distinguir mayúsculas. */
    public boolean emailTaken(String email, Long exceptId) {
        Long n = exceptId == null
                ? jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = lower(?)", Long.class, email)
                : jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = lower(?) AND id <> ?", Long.class, email, exceptId);
        return n != null && n > 0;
    }

    public long countAdmins() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM users WHERE is_admin = true", Long.class);
        return n == null ? 0 : n;
    }

    /**
     * Bloquea las filas de los superadmins hasta el fin de la transacción: dos cambios simultáneos no pueden dejar a la
     * plataforma sin superadmin activo.
     */
    public void lockSuperadmins() {
        jdbc.queryForList("SELECT id FROM users WHERE is_admin = true AND role = 'superadmin' ORDER BY id FOR UPDATE", Long.class);
    }

    /** Superadmins activos sin contar a {@code exceptId}. */
    public long countActiveSuperadminsExcept(long exceptId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM users WHERE is_admin = true AND is_active = true AND role = 'superadmin' "
                + "AND id <> ?", Long.class, exceptId);
        return n == null ? 0 : n;
    }

    /** Listado paginado de administradores ({@code textFilter} sobre nombre y correo, {@code stateFilter} 0 / 1). */
    public List<AdminUser> list(String text, Boolean active, AdminPaging paging) {
        List<Object> args = new ArrayList<>();
        String where = where(text, active, args);
        args.add(paging.records());
        args.add(paging.offset());
        return jdbc.query("SELECT " + COLUMNS + " FROM users" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", MAPPER, args.toArray());
    }

    public long count(String text, Boolean active) {
        List<Object> args = new ArrayList<>();
        Long n = jdbc.queryForObject("SELECT count(*) FROM users" + where(text, active, args), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    private static String where(String text, Boolean active, List<Object> args) {
        StringBuilder sb = new StringBuilder(" WHERE is_admin = true");
        if (text != null) {
            sb.append(" AND (f_unaccent_lower(name) LIKE (f_unaccent_lower(?)) ESCAPE '\\' OR lower(email) LIKE lower(?) ESCAPE '\\')");
            String like = AdminFormat.likeContains(text);
            args.add(like);
            args.add(like);
        }
        if (active != null) {
            sb.append(" AND is_active = ?");
            args.add(active);
        }
        return sb.toString();
    }

    /** Lo mínimo para validar una sesión en cada petición. */
    public record SessionState(long id, String email, String name, String role, boolean admin, boolean active, int sessionVersion) {
    }

    public Optional<SessionState> sessionState(long id) {
        return jdbc.query("SELECT id, email, name, role, is_admin, is_active, session_version FROM users WHERE id = ?",
                (rs, i) -> new SessionState(rs.getLong("id"), rs.getString("email"), rs.getString("name"), rs.getString("role"),
                        rs.getBoolean("is_admin"), rs.getBoolean("is_active"), rs.getInt("session_version")), id)
                .stream().findFirst();
    }

    // ------------------------------------------------------------------ login y bloqueo

    /**
     * Suma un fallo (contraseña o código) y, al llegar a {@code maxFailures}, bloquea hasta {@code lockUntil} y
     * reinicia el contador. Atómico. Devuelve el {@code locked_until} resultante.
     */
    public LocalDateTime registerFailure(long id, int maxFailures, LocalDateTime lockUntil, LocalDateTime now) {
        List<LocalDateTime> r = jdbc.query("UPDATE users SET "
                + "locked_until = CASE WHEN failed_login_count + 1 >= ? THEN ? ELSE locked_until END, "
                + "failed_login_count = CASE WHEN failed_login_count + 1 >= ? THEN 0 ELSE failed_login_count + 1 END, "
                + "updated_at = ? WHERE id = ? RETURNING locked_until",
                (rs, i) -> rs.getObject(1, LocalDateTime.class), maxFailures, lockUntil, maxFailures, now, id);
        return r.isEmpty() ? null : r.get(0);
    }

    public void loginSucceeded(long id, LocalDateTime now) {
        jdbc.update("UPDATE users SET failed_login_count = 0, locked_until = NULL, last_login_at = ?, updated_at = ? WHERE id = ?",
                now, now, id);
    }

    public void unlock(long id, LocalDateTime now) {
        jdbc.update("UPDATE users SET failed_login_count = 0, locked_until = NULL, updated_at = ? WHERE id = ?", now, id);
    }

    // ------------------------------------------------------------------ MFA

    /** Clave TOTP nueva (cifrada) aún sin confirmar: el MFA sigue sin configurar hasta {@link #confirmMfa}. */
    public void setPendingSecret(long id, String encryptedSecret, LocalDateTime now) {
        jdbc.update("UPDATE users SET two_factor_secret = ?, two_factor_confirmed_at = NULL, two_factor_recovery_codes = NULL, "
                + "updated_at = ? WHERE id = ? AND two_factor_confirmed_at IS NULL", encryptedSecret, now, id);
    }

    /** Confirma el MFA; solo si seguía sin confirmar (dos confirmaciones a la vez: gana una). */
    public boolean confirmMfa(long id, String recoveryHashesJson, LocalDateTime now) {
        return jdbc.update("UPDATE users SET two_factor_confirmed_at = ?, two_factor_recovery_codes = ?, updated_at = ? "
                + "WHERE id = ? AND two_factor_confirmed_at IS NULL AND two_factor_secret IS NOT NULL", now, recoveryHashesJson, now, id) == 1;
    }

    /** Reemplaza los códigos solo si nadie los cambió antes (un código de recuperación se usa una sola vez). */
    public boolean replaceRecoveryCodes(long id, String expectedJson, String newJson, LocalDateTime now) {
        return jdbc.update("UPDATE users SET two_factor_recovery_codes = ?, updated_at = ? WHERE id = ? "
                + "AND two_factor_recovery_codes IS NOT DISTINCT FROM ?", newJson, now, id, expectedJson) == 1;
    }

    public void setRecoveryCodes(long id, String json, LocalDateTime now) {
        jdbc.update("UPDATE users SET two_factor_recovery_codes = ?, updated_at = ? WHERE id = ?", json, now, id);
    }

    public void resetMfa(long id, LocalDateTime now) {
        jdbc.update("UPDATE users SET two_factor_secret = NULL, two_factor_recovery_codes = NULL, two_factor_confirmed_at = NULL, "
                + "updated_at = ? WHERE id = ?", now, id);
    }

    // ------------------------------------------------------------------ sesiones

    /** Cierra todas las sesiones: sube session_version y revoca todos los refresh tokens. */
    public void bumpSession(long id, LocalDateTime now) {
        jdbc.update("UPDATE users SET session_version = session_version + 1, updated_at = ? WHERE id = ?", now, id);
        revokeAllRefreshTokens(id, now);
    }

    public void insertRefreshToken(long userId, String tokenHash, LocalDateTime expiresAt, LocalDateTime now) {
        // Limpieza de los vencidos o revocados de este usuario (la tabla no crece sin límite)
        jdbc.update("DELETE FROM admin_refresh_tokens WHERE user_id = ? AND (expires_at < ? OR revoked_at < ?)", userId, now,
                now.minusDays(1));
        jdbc.update("INSERT INTO admin_refresh_tokens (user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?)",
                userId, tokenHash, expiresAt, now);
    }

    /** Usa (revoca) un refresh token vigente y devuelve su usuario. Atómico: un token sirve una sola vez. */
    public Optional<Long> consumeRefreshToken(String tokenHash, LocalDateTime now) {
        return jdbc.query("UPDATE admin_refresh_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL "
                + "AND expires_at > ? RETURNING user_id", (rs, i) -> rs.getLong(1), now, tokenHash, now).stream().findFirst();
    }

    public void revokeAllRefreshTokens(long userId, LocalDateTime now) {
        jdbc.update("UPDATE admin_refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL", now, userId);
    }

    public long countActiveRefreshTokens(long userId, LocalDateTime now) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM admin_refresh_tokens WHERE user_id = ? AND revoked_at IS NULL AND expires_at > ?",
                Long.class, userId, now);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ administración

    public long insert(String name, String email, String passwordHash, String role, LocalDateTime now) {
        Long id = jdbc.queryForObject("INSERT INTO users (name, email, password, is_admin, role, is_active, created_at, updated_at) "
                + "VALUES (?, ?, ?, true, ?, true, ?, ?) RETURNING id", Long.class, name, email, passwordHash, role, now, now);
        return id == null ? 0 : id;
    }

    public void updateProfile(long id, String name, String email, String role, LocalDateTime now) {
        jdbc.update("UPDATE users SET name = ?, email = ?, role = ?, updated_at = ? WHERE id = ?", name, email, role, now, id);
    }

    /** Nombre y correo propios (Mi cuenta); el rol no cambia. */
    public void updateNameEmail(long id, String name, String email, LocalDateTime now) {
        jdbc.update("UPDATE users SET name = ?, email = ?, updated_at = ? WHERE id = ?", name, email, now, id);
    }

    public void setActive(long id, boolean active, LocalDateTime now) {
        jdbc.update("UPDATE users SET is_active = ?, updated_at = ? WHERE id = ?", active, now, id);
    }

    public void updatePassword(long id, String passwordHash, LocalDateTime now) {
        jdbc.update("UPDATE users SET password = ?, updated_at = ? WHERE id = ?", passwordHash, now, id);
    }
}
