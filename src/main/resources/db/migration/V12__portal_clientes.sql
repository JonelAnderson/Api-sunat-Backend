-- V12: portal de clientes (/api/portal/**). Diseño: Docs/api-sunat-portal-clientes-diseno.md (§3 y §4).
-- Las personas de una empresa emisora entran a consultar sus comprobantes y a regenerar sus credenciales. Es un
-- mundo aparte del panel interno: sus usuarios NO viven en "users" (que son los administradores y los dueños de
-- empresa de API-PRO) y sus sesiones tienen su propio JWT (iss = api-sunat-portal, aud = portal).
-- Todas las fechas son UTC.

-- ---------------------------------------------------------------------
-- Usuarios del portal
-- ---------------------------------------------------------------------
CREATE TABLE portal_users (
    id                       bigserial PRIMARY KEY,
    email                    varchar(255) NOT NULL,
    name                     varchar(255) NOT NULL,
    -- NULL hasta que la persona fija su contraseña con el enlace de la invitación
    password_hash            varchar(100) NULL,
    is_active                boolean      NOT NULL DEFAULT true,
    failed_login_count       int          NOT NULL DEFAULT 0,
    locked_until             timestamp(0) NULL,
    session_version          int          NOT NULL DEFAULT 0,
    -- Clave TOTP cifrada con SecretCipher (prefijo "v1:"); los códigos de recuperación son hashes SHA-256 (arreglo JSON)
    two_factor_secret        text         NULL,
    two_factor_confirmed_at  timestamp(0) NULL,
    two_factor_recovery_codes text        NULL,
    last_login_at            timestamp(0) NULL,
    created_at               timestamp(0) NULL,
    updated_at               timestamp(0) NULL
);
-- El correo es el usuario del login: único sin distinguir mayúsculas
CREATE UNIQUE INDEX portal_users_email_lower_unique ON portal_users (lower(email));
COMMENT ON COLUMN portal_users.password_hash IS 'BCrypt. NULL = invitado que aún no activó su cuenta (no puede iniciar sesión)';
COMMENT ON COLUMN portal_users.session_version IS 'Sube al cerrar sesión, cambiar la contraseña, restablecer el MFA, deshabilitar o reinvitar: invalida los JWT';

-- ---------------------------------------------------------------------
-- Empresas de cada usuario (la consola es dueña de este conjunto)
-- ---------------------------------------------------------------------
CREATE TABLE portal_user_tenants (
    portal_user_id bigint       NOT NULL REFERENCES portal_users (id) ON DELETE CASCADE,
    tenant_id      bigint       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    created_at     timestamp(0) NULL,
    CONSTRAINT portal_user_tenants_pk PRIMARY KEY (portal_user_id, tenant_id)
);
CREATE INDEX portal_user_tenants_tenant_id_index ON portal_user_tenants (tenant_id);

-- ---------------------------------------------------------------------
-- Invitaciones: solo se guarda el SHA-256 del token (32 bytes al azar), vence a las 72 h y sirve una sola vez
-- ---------------------------------------------------------------------
CREATE TABLE portal_invitations (
    id             bigserial PRIMARY KEY,
    portal_user_id bigint       NOT NULL REFERENCES portal_users (id) ON DELETE CASCADE,
    token_hash     varchar(64)  NOT NULL,
    expires_at     timestamp(0) NOT NULL,
    used_at        timestamp(0) NULL,
    created_at     timestamp(0) NULL,
    -- Quién la pidió: "consola:<usuario>" o el correo del superadmin del panel
    created_by     varchar(255) NULL,
    CONSTRAINT portal_invitations_token_hash_unique UNIQUE (token_hash)
);
CREATE INDEX portal_invitations_user_index ON portal_invitations (portal_user_id);

-- ---------------------------------------------------------------------
-- Refresh tokens del portal (opacos; se guarda solo el SHA-256). Una tabla y no una columna: una persona puede tener
-- varios dispositivos y "cerrar sesión" los revoca todos.
-- ---------------------------------------------------------------------
CREATE TABLE portal_refresh_tokens (
    id             bigserial PRIMARY KEY,
    portal_user_id bigint       NOT NULL REFERENCES portal_users (id) ON DELETE CASCADE,
    token_hash     varchar(64)  NOT NULL,
    expires_at     timestamp(0) NOT NULL,
    revoked_at     timestamp(0) NULL,
    created_at     timestamp(0) NULL,
    CONSTRAINT portal_refresh_tokens_token_hash_unique UNIQUE (token_hash)
);
CREATE INDEX portal_refresh_tokens_user_index ON portal_refresh_tokens (portal_user_id);
