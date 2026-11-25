-- Trabajos de la cola que agotaron sus intentos (tabla failed_jobs de Laravel). La revisa la tarea
-- "ops:jobs-fallidos" y se reintentan con "ops jobs-fallidos:reintentar" (queue:retry de Laravel).
-- payload: el trabajo tal como estaba en la cola (tipo, id del documento e intentos; sin secretos).
CREATE TABLE failed_jobs (
    id         bigserial PRIMARY KEY,
    uuid       varchar(255) NOT NULL,
    connection varchar(50)  NOT NULL DEFAULT 'redis',
    queue      varchar(100) NOT NULL,
    payload    jsonb        NOT NULL,
    exception  text         NOT NULL,
    failed_at  timestamp(0) NOT NULL DEFAULT now(),
    CONSTRAINT failed_jobs_uuid_unique UNIQUE (uuid)
);
CREATE INDEX failed_jobs_queue_index ON failed_jobs (queue);
