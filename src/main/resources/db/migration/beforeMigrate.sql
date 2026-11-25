-- Callback de Flyway (corre antes de cada migrate; no es una migración versionada y no tiene checksum).
--
-- V7__panel_admin.sql convierte el rol "soporte" de API-PRO en "lectura" (su CHECK aún no admitía "soporte";
-- V8 lo agregó después). V7 ya está aplicada en algunas bases y no se puede editar, así que, en una base que
-- todavía NO aplicó V7, aquí se guarda quiénes eran "soporte" antes de que V7 los convierta, y
-- V10__roles_soporte_de_api_pro.sql les devuelve el rol y borra esta copia.
--
-- Idempotente y sin efecto en una base nueva (sin users) o en una que ya pasó V7 (los "soporte" ya se
-- perdieron: un superadmin los reasigna desde Administradores, ver docs/INSTALACION.md §10.4).
-- Los IF van anidados: PL/pgSQL solo planifica cada sentencia cuando la ejecuta, así que nunca toca una tabla
-- o columna que no existe.
DO $$
BEGIN
    IF to_regclass('users') IS NOT NULL
       AND to_regclass('flyway_schema_history') IS NOT NULL
       AND EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema() AND table_name = 'users' AND column_name = 'role') THEN
        IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '7' AND success) THEN
            CREATE TABLE IF NOT EXISTS admin_legacy_roles (
                user_id     bigint       PRIMARY KEY,
                role        varchar(50)  NOT NULL,
                captured_at timestamp(0) NOT NULL DEFAULT (now() AT TIME ZONE 'UTC')
            );
            INSERT INTO admin_legacy_roles (user_id, role)
                SELECT id, role FROM users WHERE role = 'soporte'
                ON CONFLICT (user_id) DO NOTHING;
        END IF;
    END IF;
END $$;
