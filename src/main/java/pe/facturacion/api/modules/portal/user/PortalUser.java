package pe.facturacion.api.modules.portal.user;

import java.time.LocalDateTime;

/** Fila de {@code portal_users}. Las fechas están en UTC. */
public record PortalUser(
        long id,
        String email,
        String name,
        String passwordHash,
        boolean active,
        int failedLoginCount,
        LocalDateTime lockedUntil,
        int sessionVersion,
        String twoFactorSecret,
        LocalDateTime twoFactorConfirmedAt,
        String twoFactorRecoveryCodes,
        LocalDateTime lastLoginAt) {

    /** Ya fijó su contraseña con el enlace de la invitación. */
    public boolean activated() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    /** Puede iniciar sesión: habilitado y con contraseña. */
    public boolean canLogin() {
        return active && activated();
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
        return "PortalUser[id=" + id + ", active=" + active + "]";
    }
}
