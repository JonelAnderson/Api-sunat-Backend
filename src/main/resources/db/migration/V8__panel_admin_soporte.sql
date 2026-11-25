-- V8: panel de administración, fases A y B (Docs/api-sunat-panel-admin-diseno.md §10).

-- ---------------------------------------------------------------------
-- Rol "soporte": lee todo y hace consultas en vivo a SUNAT; en la fase D podrá reenviar, anular y refrescar.
-- V7 había convertido el "soporte" de API-PRO en "lectura" (no hay cómo saber cuáles eran): un superadmin les
-- asigna el rol nuevo desde Administradores.
-- ---------------------------------------------------------------------
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IS NULL OR role IN ('superadmin', 'operador', 'soporte', 'lectura'));

-- ---------------------------------------------------------------------
-- Configuración global (tabla settings, la misma de API-PRO):
--   emision_ilimitada_global   (jsonb booleano) → todas las empresas emiten sin cupo
--   nuevas_empresas_ilimitadas (jsonb booleano) → las empresas creadas desde el panel nacen ilimitadas (fase C)
--   consulta_token_global      (jsonb texto)    → token de consulta RUC / DNI, CIFRADO ("v1:…", SecretCipher)
-- API-PRO guardaba el token en claro: si quedó uno así (copiado de su base), se borra; el superadmin lo vuelve
-- a ingresar desde el panel y queda cifrado. El proveedor de API-PRO (json / migo) no aplica aquí.
-- ---------------------------------------------------------------------
DELETE FROM settings WHERE key = 'consulta_token_global'
    AND (value IS NULL OR jsonb_typeof(value) <> 'string' OR (value #>> '{}') NOT LIKE 'v1:%');
DELETE FROM settings WHERE key = 'consulta_proveedor_global';

-- ---------------------------------------------------------------------
-- Feed de envíos y comprobantes de todas las empresas (ventana por fecha de emisión, sin filtrar por empresa)
-- ---------------------------------------------------------------------
CREATE INDEX invoices_fecha_emision_index ON invoices (fecha_emision) WHERE deleted_at IS NULL;
CREATE INDEX boletas_fecha_emision_index ON boletas (fecha_emision) WHERE deleted_at IS NULL;
