package pe.facturacion.api.modules.admin.user;

import java.time.LocalDateTime;
import java.util.Optional;
import pe.facturacion.api.modules.admin.security.AdminRole;

/** Fila de {@code users} vista por el panel. Las fechas están en UTC. */
public record AdminUser(
        long id,
        String name,
        String email,
        String passwordHash,
        boolean admin,
        String role,
        boolean active,
        String twoFactorSecret,
        String twoFactorRecoveryCodes,
        LocalDateTime twoFactorConfirmedAt,
        int failedLoginCount,
        LocalDateTime lockedUntil,
        int sessionVersion,
        LocalDateTime lastLoginAt) {

    public Optional<AdminRole> adminRole() {
        return AdminRole.fromCode(role);
    }

    /** Puede entrar al panel: administrador, activo y con un rol del panel. */
    public boolean canUsePanel() {
        return admin && active && adminRole().isPresent();
    }

    /** MFA confirmado (si solo hay una clave pendiente, falta configurarlo). */
    public boolean mfaEnabled() {
        return twoFactorConfirmedAt != null && twoFactorSecret != null && !twoFactorSecret.isBlank();
    }

    public boolean lockedAt(LocalDateTime nowUtc) {
        return lockedUntil != null && lockedUntil.isAfter(nowUtc);
    }

    @Override
    public String toString() {
        // Nunca imprimir el hash ni los secretos del MFA
        return "AdminUser[id=" + id + ", role=" + role + ", active=" + active + "]";
    }
}
