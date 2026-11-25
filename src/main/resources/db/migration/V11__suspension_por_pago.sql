-- Suspensión por falta de pago desde la consola de plataforma (Docs/plataforma-consola-arquitectura.md §4 y §5.3).
-- 'pago': la empresa sigue activa (is_active = true), así que puede consultar y descargar sus comprobantes y la cola
-- termina lo que ya tenía (resúmenes y bajas pendientes), pero la API rechaza la emisión nueva con 402.
-- NULL: sin suspensión por pago. La suspensión manual del panel sigue siendo is_active = false (403 en todo).
ALTER TABLE tenants
    ADD COLUMN suspension_kind varchar(10) NULL,
    ADD CONSTRAINT tenants_suspension_kind_check CHECK (suspension_kind IS NULL OR suspension_kind = 'pago');
