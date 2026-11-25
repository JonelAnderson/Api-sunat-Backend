-- V7: panel de administración interno (/api/admin/**). Diseño: Docs/api-sunat-panel-admin-diseno.md (§3 y §4).
-- Administradores = users con is_admin = true y un rol del panel (superadmin / operador / lectura).

-- ---------------------------------------------------------------------
-- Roles: los de API-PRO se traducen antes de poner el CHECK
-- ---------------------------------------------------------------------
-- Administrador antiguo sin rol: en API-PRO solo leía (EnsurePanelWrite bloqueaba las escrituras)
UPDATE users SET role = 'lectura' WHERE is_admin = true AND role IS NULL;
-- super_admin → superadmin; admin → operador; soporte → lectura (no escribía; el reenvío no existe en el panel);
-- cliente (dueño de empresa) y cualquier otro valor → sin rol del panel
UPDATE users SET role = CASE role
        WHEN 'super_admin' THEN 'superadmin'
        WHEN 'superadmin' THEN 'superadmin'
        WHEN 'admin' THEN 'operador'
        WHEN 'operador' THEN 'operador'
        WHEN 'soporte' THEN 'lectura'
        WHEN 'lectura' THEN 'lectura'
        ELSE NULL END
    WHERE role IS NOT NULL;

ALTER TABLE users
    ADD COLUMN failed_login_count int          NOT NULL DEFAULT 0,
    ADD COLUMN locked_until       timestamp(0) NULL,
    ADD COLUMN session_version    int          NOT NULL DEFAULT 0,
    ADD COLUMN last_login_at      timestamp(0) NULL,
    ADD CONSTRAINT users_role_check CHECK (role IS NULL OR role IN ('superadmin', 'operador', 'lectura'));
COMMENT ON COLUMN users.failed_login_count IS 'Contraseñas o códigos MFA fallidos seguidos (5 → bloqueo de 15 min)';
COMMENT ON COLUMN users.session_version IS 'Sube al cerrar sesión, cambiar la contraseña, restablecer el MFA o desactivar: invalida los JWT';

-- El MFA de Laravel (Fortify) estaba cifrado con la APP_KEY de API-PRO y no se puede leer aquí: esos
-- administradores lo configuran de nuevo en su primer ingreso al panel (claves de este sistema: prefijo "v1:").
UPDATE users SET two_factor_secret = NULL, two_factor_recovery_codes = NULL, two_factor_confirmed_at = NULL
    WHERE is_admin = true AND two_factor_secret IS NOT NULL AND two_factor_secret NOT LIKE 'v1:%';

CREATE INDEX users_is_admin_index ON users (is_admin) WHERE is_admin = true;

-- ---------------------------------------------------------------------
-- Refresh tokens del panel (opacos; se guarda solo el SHA-256)
-- ---------------------------------------------------------------------
CREATE TABLE admin_refresh_tokens (
    id         bigserial PRIMARY KEY,
    user_id    bigint       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash varchar(64)  NOT NULL,
    expires_at timestamp(0) NOT NULL,
    revoked_at timestamp(0) NULL,
    created_at timestamp(0) NULL,
    CONSTRAINT admin_refresh_tokens_token_hash_unique UNIQUE (token_hash)
);
CREATE INDEX admin_refresh_tokens_user_id_index ON admin_refresh_tokens (user_id);

-- ---------------------------------------------------------------------
-- Suspensión de empresas desde el panel
-- ---------------------------------------------------------------------
ALTER TABLE tenants
    ADD COLUMN suspension_reason varchar(400) NULL,
    ADD COLUMN suspended_at      timestamp(0) NULL;

-- ---------------------------------------------------------------------
-- Listado de rechazos SUNAT de todas las empresas (índices parciales: pocas filas)
-- ---------------------------------------------------------------------
CREATE INDEX invoices_rechazados_fecha_index ON invoices (fecha_emision) WHERE sunat_status = 'rechazado';
CREATE INDEX boletas_rechazados_fecha_index ON boletas (fecha_emision) WHERE sunat_status = 'rechazado';
CREATE INDEX credit_notes_rechazados_fecha_index ON credit_notes (fecha_emision) WHERE sunat_status = 'rechazado';
CREATE INDEX debit_notes_rechazados_fecha_index ON debit_notes (fecha_emision) WHERE sunat_status = 'rechazado';
