-- API-PRO corre en MySQL con utf8mb4_unicode_ci: los ORDER BY y LIKE no distinguen
-- mayúsculas ni tildes. En PostgreSQL se replica con:
--   * colación ICU no determinista "es_ci" para ordenar (nombre COLLATE "es_ci");
--   * unaccent(lower(...)) para las búsquedas LIKE.
CREATE COLLATION IF NOT EXISTS es_ci (provider = icu, locale = 'und-u-ks-level1', deterministic = false);
CREATE EXTENSION IF NOT EXISTS unaccent;

-- Función inmutable envoltorio (unaccent() es STABLE) para poder indexar búsquedas.
CREATE OR REPLACE FUNCTION f_unaccent_lower(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT lower(public.unaccent('public.unaccent', $1)) $$;
