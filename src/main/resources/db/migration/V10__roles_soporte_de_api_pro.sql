-- V10: devuelve el rol "soporte" a los usuarios que lo tenían en API-PRO (Docs/api-sunat-panel-admin-diseno.md §12.3).
--
-- V7 los convirtió en "lectura". Si esta base se actualizó desde antes de V7 con esta versión, el callback
-- beforeMigrate.sql guardó quiénes eran "soporte" en admin_legacy_roles antes de aplicar V7; aquí se restauran
-- (solo si siguen en "lectura") y se borra la copia. En una base que ya había pasado V7 no hay copia y no hace nada:
-- un superadmin les asigna "soporte" desde Administradores (docs/INSTALACION.md §10.4).
DO $$
BEGIN
    IF to_regclass('admin_legacy_roles') IS NOT NULL THEN
        UPDATE users u
           SET role = 'soporte', updated_at = (now() AT TIME ZONE 'UTC')
         WHERE u.role = 'lectura'
           AND u.id IN (SELECT user_id FROM admin_legacy_roles WHERE role = 'soporte');
        DROP TABLE admin_legacy_roles;
    END IF;
END $$;
