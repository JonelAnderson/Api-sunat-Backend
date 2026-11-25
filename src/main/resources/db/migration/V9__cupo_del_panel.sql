-- V9: el plan del panel es el plan efectivo y su cupo mensual es el que se aplica
-- (Docs/api-sunat-panel-admin-diseno.md §12.2; regla en modules/plan/DocumentQuota y PanelPlanService).
--
-- Antes de V9, tenants.max_documents_month no se aplicaba en ninguna parte: el cupo salía siempre del plan de la
-- suscripción activa (o de Free). Muchas empresas guardan ahí el 20 por defecto de la columna (alta por /registro,
-- por el panel o el bridge), que no es su cupo real. Por eso esta migración NO convierte ningún valor existente en
-- cupo del panel: ninguna empresa cambia de cupo ni de plan al actualizar. Lo único que cambia es la copia
-- informativa max_documents_month, que pasa a mostrar el cupo que de verdad se aplica.

-- ---------------------------------------------------------------------
-- Cupo fijado desde el panel: NULL = el documents_month del plan efectivo
-- ---------------------------------------------------------------------
ALTER TABLE tenants
    ADD COLUMN documents_month_override integer NULL,
    ADD CONSTRAINT tenants_documents_month_override_check CHECK (documents_month_override IS NULL OR documents_month_override >= 0);
COMMENT ON COLUMN tenants.documents_month_override IS 'Cupo mensual fijado desde el panel (NULL = el del plan efectivo). Lo aplica EmissionPolicy';
COMMENT ON COLUMN tenants.max_documents_month IS 'Copia del cupo mensual efectivo (-1 = ilimitado): documents_month_override o el del plan';

-- ---------------------------------------------------------------------
-- max_documents_month = cupo que se aplica hoy (mismo cálculo que PlanService.getActivePlan + Plan.getLimit):
-- el documents_month del plan de la suscripción activa o en prueba más reciente; sin suscripción, el del plan
-- free; -1 si el plan es ilimitado; 30 si el plan no lo define; -1 si no existe el plan free (Free incorporado).
-- ---------------------------------------------------------------------
UPDATE tenants t
SET max_documents_month = q.cupo
FROM (
    SELECT t2.id,
           COALESCE(
               (SELECT CASE
                           WHEN p.is_unlimited THEN -1
                           WHEN jsonb_typeof(p.limits -> 'documents_month') = 'number'
                               THEN trunc(LEAST(GREATEST((p.limits ->> 'documents_month')::numeric, -1), 2147483647))::integer
                           ELSE 30 END
                  FROM subscriptions s
                  JOIN plans p ON p.id = s.plan_id
                 WHERE s.tenant_id = t2.id AND s.status IN ('active', 'trialing')
                 ORDER BY s.id DESC
                 LIMIT 1),
               (SELECT CASE
                           WHEN p.is_unlimited THEN -1
                           WHEN jsonb_typeof(p.limits -> 'documents_month') = 'number'
                               THEN trunc(LEAST(GREATEST((p.limits ->> 'documents_month')::numeric, -1), 2147483647))::integer
                           ELSE 30 END
                  FROM plans p
                 WHERE p.slug = 'free'),
               -1) AS cupo
      FROM tenants t2
) q
WHERE q.id = t.id
  AND t.max_documents_month IS DISTINCT FROM q.cupo;
