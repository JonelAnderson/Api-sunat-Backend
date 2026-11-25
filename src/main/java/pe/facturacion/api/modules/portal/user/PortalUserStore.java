package pe.facturacion.api.modules.portal.user;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import pe.facturacion.api.modules.admin.api.AdminFormat;
import pe.facturacion.api.modules.admin.api.AdminPaging;

/**
 * Acceso a {@code portal_users}, {@code portal_user_tenants}, {@code portal_invitations} y
 * {@code portal_refresh_tokens}. Solo datos: las reglas (bloqueo, política de contraseñas, invitaciones) viven en los
 * servicios. Fechas siempre en UTC.
 */
@Repository
public class PortalUserStore {

    private static final String COLUMNS = "id, email, name, password_hash, is_active, failed_login_count, locked_until, session_version, "
            + "two_factor_secret, two_factor_confirmed_at, two_factor_recovery_codes, last_login_at";

    private static final RowMapper<PortalUser> MAPPER = PortalUserStore::map;

    private final JdbcTemplate jdbc;

    public PortalUserStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static PortalUser map(ResultSet rs, int row) throws SQLException {
        return new PortalUser(rs.getLong("id"), rs.getString("email"), rs.getString("name"), rs.getString("password_hash"),
                rs.getBoolean("is_active"), rs.getInt("failed_login_count"), rs.getObject("locked_until", LocalDateTime.class),
                rs.getInt("session_version"), rs.getString("two_factor_secret"),
                rs.getObject("two_factor_confirmed_at", LocalDateTime.class), rs.getString("two_factor_recovery_codes"),
                rs.getObject("last_login_at", LocalDateTime.class));
    }

    // ------------------------------------------------------------------ lectura

    /** Usuario por correo (sin distinguir mayúsculas). */
    public Optional<PortalUser> findByEmail(String email) {
        return jdbc.query("SELECT " + COLUMNS + " FROM portal_users WHERE lower(email) = lower(?)", MAPPER, email).stream().findFirst();
    }

    public Optional<PortalUser> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM portal_users WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** Lo mínimo para validar una sesión en cada petición. */
    public record SessionState(long id, String email, String name, boolean active, boolean activated, int sessionVersion) {
    }

    public Optional<SessionState> sessionState(long id) {
        return jdbc.query("SELECT id, email, name, is_active, (password_hash IS NOT NULL AND password_hash <> '') AS activated, "
                + "session_version FROM portal_users WHERE id = ?",
                (rs, i) -> new SessionState(rs.getLong("id"), rs.getString("email"), rs.getString("name"), rs.getBoolean("is_active"),
                        rs.getBoolean("activated"), rs.getInt("session_version")), id).stream().findFirst();
    }

    // ------------------------------------------------------------------ empresas

    /** Empresas del usuario que existen y no están eliminadas. */
    public List<Long> tenantIds(long userId) {
        return jdbc.queryForList("SELECT ut.tenant_id FROM portal_user_tenants ut JOIN tenants t ON t.id = ut.tenant_id "
                + "WHERE ut.portal_user_id = ? AND t.deleted_at IS NULL ORDER BY ut.tenant_id", Long.class, userId);
    }

    /** true si la empresa es del usuario y no está eliminada (la base del 404 de {@code tenants/{id}/**}). */
    public boolean hasTenant(long userId, long tenantId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM portal_user_tenants ut JOIN tenants t ON t.id = ut.tenant_id "
                + "WHERE ut.portal_user_id = ? AND ut.tenant_id = ? AND t.deleted_at IS NULL", Long.class, userId, tenantId);
        return n != null && n > 0;
    }

    /** De los ids pedidos, los que son empresas existentes y no eliminadas. */
    public Set<Long> existingTenantIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        List<Long> found = jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT id FROM tenants WHERE deleted_at IS NULL AND id = ANY (?)");
            ps.setArray(1, con.createArrayOf("bigint", ids.toArray()));
            return ps;
        }, (rs, i) -> rs.getLong(1));
        return new LinkedHashSet<>(found);
    }

    /** Reemplaza el conjunto de empresas del usuario. */
    public void replaceTenants(long userId, Collection<Long> tenantIds, LocalDateTime now) {
        jdbc.update("DELETE FROM portal_user_tenants WHERE portal_user_id = ?", userId);
        for (Long tenantId : new LinkedHashSet<>(tenantIds)) {
            jdbc.update("INSERT INTO portal_user_tenants (portal_user_id, tenant_id, created_at) VALUES (?, ?, ?)", userId, tenantId, now);
        }
    }

    /** Empresas de varios usuarios de una vez (sin consultar por cada fila). */
    public Map<Long, List<Long>> tenantIdsByUsers(Collection<Long> userIds) {
        Map<Long, List<Long>> out = new LinkedHashMap<>();
        if (userIds.isEmpty()) {
            return out;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT ut.portal_user_id, ut.tenant_id FROM portal_user_tenants ut "
                    + "JOIN tenants t ON t.id = ut.tenant_id WHERE t.deleted_at IS NULL AND ut.portal_user_id = ANY (?) "
                    + "ORDER BY ut.portal_user_id, ut.tenant_id");
            Array arr = con.createArrayOf("bigint", userIds.toArray());
            ps.setArray(1, arr);
            return ps;
        }, rs -> {
            out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2));
        });
        return out;
    }

    // ------------------------------------------------------------------ listado (consola)

    public List<PortalUser> list(String text, Long tenantId, AdminPaging paging) {
        List<Object> args = new ArrayList<>();
        String where = where(text, tenantId, args);
        args.add(paging.records());
        args.add(paging.offset());
        return jdbc.query("SELECT " + COLUMNS + " FROM portal_users u" + where + " ORDER BY u.id DESC LIMIT ? OFFSET ?",
                MAPPER, args.toArray());
    }

    public long count(String text, Long tenantId) {
        List<Object> args = new ArrayList<>();
        Long n = jdbc.queryForObject("SELECT count(*) FROM portal_users u" + where(text, tenantId, args), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    private static String where(String text, Long tenantId, List<Object> args) {
        StringBuilder sb = new StringBuilder(" WHERE true");
        if (text != null) {
            sb.append(" AND (f_unaccent_lower(u.name) LIKE (f_unaccent_lower(?)) ESCAPE '\\' OR lower(u.email) LIKE lower(?) ESCAPE '\\')");
            String like = AdminFormat.likeContains(text);
            args.add(like);
            args.add(like);
        }
        if (tenantId != null) {
            sb.append(" AND EXISTS (SELECT 1 FROM portal_user_tenants ut WHERE ut.portal_user_id = u.id AND ut.tenant_id = ?)");
            args.add(tenantId);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ alta y estado

    /** Crea la persona (sin contraseña). Devuelve su id, o {@code 0} si el correo ya existe (dos invitaciones a la vez). */
    public long insert(String email, String name, LocalDateTime now) {
        List<Long> id = jdbc.query("INSERT INTO portal_users (email, name, is_active, created_at, updated_at) "
                + "VALUES (?, ?, true, ?, ?) ON CONFLICT (lower(email)) DO NOTHING RETURNING id", (rs, i) -> rs.getLong(1),
                email, name, now, now);
        return id.isEmpty() ? 0 : id.get(0);
    }

    /**
     * Reinvitación: nuevo nombre, reactiva, borra la contraseña (la persona fija otra con el enlace), limpia el
     * bloqueo y, con {@code resetMfa}, el MFA. Sube la versión de sesión y revoca los refresh tokens.
     */
    public void reinvite(long id, String name, boolean resetMfa, LocalDateTime now) {
        jdbc.update("UPDATE portal_users SET name = ?, is_active = true, password_hash = NULL, failed_login_count = 0, "
                + "locked_until = NULL, session_version = session_version + 1, updated_at = ? WHERE id = ?", name, now, id);
        if (resetMfa) {
            resetMfa(id, now);
        }
        revokeAllRefreshTokens(id, now);
    }

    public void setActive(long id, boolean active, LocalDateTime now) {
        jdbc.update("UPDATE portal_users SET is_active = ?, updated_at = ? WHERE id = ?", active, now, id);
    }

    public void updatePassword(long id, String passwordHash, LocalDateTime now) {
        jdbc.update("UPDATE portal_users SET password_hash = ?, failed_login_count = 0, locked_until = NULL, updated_at = ? WHERE id = ?",
                passwordHash, now, id);
    }

    // ------------------------------------------------------------------ invitaciones

    /** Invalida las invitaciones sin usar de la persona (una nueva reemplaza a las anteriores). */
    public void invalidateInvitations(long userId, LocalDateTime now) {
        jdbc.update("UPDATE portal_invitations SET expires_at = ? WHERE portal_user_id = ? AND used_at IS NULL AND expires_at > ?",
                now, userId, now);
    }

    public void insertInvitation(long userId, String tokenHash, LocalDateTime expiresAt, LocalDateTime now, String createdBy) {
        jdbc.update("INSERT INTO portal_invitations (portal_user_id, token_hash, expires_at, created_at, created_by) VALUES (?, ?, ?, ?, ?)",
                userId, tokenHash, expiresAt, now, createdBy);
    }

    /**
     * Usa (marca) una invitación vigente y sin usar y devuelve su usuario. Atómico: un enlace sirve una sola vez, aunque
     * lleguen dos peticiones a la vez.
     */
    public Optional<Long> consumeInvitation(String tokenHash, LocalDateTime now) {
        return jdbc.query("UPDATE portal_invitations SET used_at = ? WHERE token_hash = ? AND used_at IS NULL AND expires_at > ? "
                + "RETURNING portal_user_id", (rs, i) -> rs.getLong(1), now, tokenHash, now).stream().findFirst();
    }

    // ------------------------------------------------------------------ login y bloqueo

    /**
     * Suma un fallo (contraseña o código) y, al llegar a {@code maxFailures}, bloquea hasta {@code lockUntil} y
     * reinicia el contador. Atómico. Devuelve el {@code locked_until} resultante.
     */
    public LocalDateTime registerFailure(long id, int maxFailures, LocalDateTime lockUntil, LocalDateTime now) {
        List<LocalDateTime> r = jdbc.query("UPDATE portal_users SET "
                + "locked_until = CASE WHEN failed_login_count + 1 >= ? THEN ? ELSE locked_until END, "
                + "failed_login_count = CASE WHEN failed_login_count + 1 >= ? THEN 0 ELSE failed_login_count + 1 END, "
                + "updated_at = ? WHERE id = ? RETURNING locked_until",
                (rs, i) -> rs.getObject(1, LocalDateTime.class), maxFailures, lockUntil, maxFailures, now, id);
        return r.isEmpty() ? null : r.get(0);
    }

    public void loginSucceeded(long id, LocalDateTime now) {
        jdbc.update("UPDATE portal_users SET failed_login_count = 0, locked_until = NULL, last_login_at = ?, updated_at = ? WHERE id = ?",
                now, now, id);
    }

    // ------------------------------------------------------------------ MFA

    /** Clave TOTP nueva (cifrada) aún sin confirmar: el MFA sigue sin configurar hasta {@link #confirmMfa}. */
    public void setPendingSecret(long id, String encryptedSecret, LocalDateTime now) {
        jdbc.update("UPDATE portal_users SET two_factor_secret = ?, two_factor_confirmed_at = NULL, two_factor_recovery_codes = NULL, "
                + "updated_at = ? WHERE id = ? AND two_factor_confirmed_at IS NULL", encryptedSecret, now, id);
    }

    /** Confirma el MFA; solo si seguía sin confirmar (dos confirmaciones a la vez: gana una). */
    public boolean confirmMfa(long id, String recoveryHashesJson, LocalDateTime now) {
        return jdbc.update("UPDATE portal_users SET two_factor_confirmed_at = ?, two_factor_recovery_codes = ?, updated_at = ? "
                + "WHERE id = ? AND two_factor_confirmed_at IS NULL AND two_factor_secret IS NOT NULL", now, recoveryHashesJson, now, id) == 1;
    }

    /** Reemplaza los códigos solo si nadie los cambió antes (un código de recuperación se usa una sola vez). */
    public boolean replaceRecoveryCodes(long id, String expectedJson, String newJson, LocalDateTime now) {
        return jdbc.update("UPDATE portal_users SET two_factor_recovery_codes = ?, updated_at = ? WHERE id = ? "
                + "AND two_factor_recovery_codes IS NOT DISTINCT FROM ?", newJson, now, id, expectedJson) == 1;
    }

    public void setRecoveryCodes(long id, String json, LocalDateTime now) {
        jdbc.update("UPDATE portal_users SET two_factor_recovery_codes = ?, updated_at = ? WHERE id = ?", json, now, id);
    }

    public void resetMfa(long id, LocalDateTime now) {
        jdbc.update("UPDATE portal_users SET two_factor_secret = NULL, two_factor_recovery_codes = NULL, two_factor_confirmed_at = NULL, "
                + "updated_at = ? WHERE id = ?", now, id);
    }

    // ------------------------------------------------------------------ sesiones

    /** Cierra todas las sesiones: sube session_version y revoca todos los refresh tokens. */
    public void bumpSession(long id, LocalDateTime now) {
        jdbc.update("UPDATE portal_users SET session_version = session_version + 1, updated_at = ? WHERE id = ?", now, id);
        revokeAllRefreshTokens(id, now);
    }

    public void insertRefreshToken(long userId, String tokenHash, LocalDateTime expiresAt, LocalDateTime now) {
        // Limpieza de los vencidos o revocados de este usuario (la tabla no crece sin límite)
        jdbc.update("DELETE FROM portal_refresh_tokens WHERE portal_user_id = ? AND (expires_at < ? OR revoked_at < ?)", userId, now,
                now.minusDays(1));
        jdbc.update("INSERT INTO portal_refresh_tokens (portal_user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?)",
                userId, tokenHash, expiresAt, now);
    }

    /** Usa (revoca) un refresh token vigente y devuelve su usuario. Atómico: un token sirve una sola vez. */
    public Optional<Long> consumeRefreshToken(String tokenHash, LocalDateTime now) {
        return jdbc.query("UPDATE portal_refresh_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL "
                + "AND expires_at > ? RETURNING portal_user_id", (rs, i) -> rs.getLong(1), now, tokenHash, now).stream().findFirst();
    }

    public void revokeAllRefreshTokens(long userId, LocalDateTime now) {
        jdbc.update("UPDATE portal_refresh_tokens SET revoked_at = ? WHERE portal_user_id = ? AND revoked_at IS NULL", now, userId);
    }

    public long countActiveRefreshTokens(long userId, LocalDateTime now) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM portal_refresh_tokens WHERE portal_user_id = ? AND revoked_at IS NULL "
                + "AND expires_at > ?", Long.class, userId, now);
        return n == null ? 0 : n;
    }
}
