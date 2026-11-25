# API SUNAT — despliegue en Coolify

Recurso Docker Compose con `db` (PostgreSQL 18), `redis` (con contraseña) y `api` (Spring Boot; Flyway migra la base al arrancar).
Guía general de todos los proyectos: `Docs/despliegue-coolify.md` del proyecto.

## 1. Crear el recurso
1. *New Resource* → repositorio `Api-sunat-Backend` → build pack **Docker Compose**.
2. *Base Directory* `/` · *Docker Compose Location* `/docker-compose.coolify.yml` · rama `deploy-prod` (o `deploy-qa`).
3. *Environment Variables* (ver §2).
4. *Domains*: en el servicio `api` poner `https://api.midominio.pe:8081` (el `:8081` es el puerto interno). `db` y `redis` sin dominio.
5. *Deploy*. Salud: `/actuator/health/liveness` (la imagen ya trae el HEALTHCHECK).

## 2. Variables

| Variable | Obligatoria | Qué es |
| --- | --- | --- |
| `DB_PASSWORD` | Sí | Contraseña de Postgres (`openssl rand -base64 24`, sin `$ " ' \ #`) |
| `REDIS_PASSWORD` | Sí | Contraseña de Redis (misma regla) |
| `APP_ENCRYPTION_KEY` | Sí | AES-256 en base64 (`openssl rand -base64 32`). **Respaldarla**: sin ella no se descifran clave SOL, certificados ni `api_secret` |
| `APP_URL` | Sí | URL pública de la API: `https://api.midominio.pe` |
| `SUNAT_SHARED_NETWORK` | No | Nombre de la red compartida (`sunat-prod` por defecto; `sunat-qa` para QA en el mismo servidor) |
| `SUNAT_DB_LOCAL_PORT` | No | Puerto local de la base para DBeaver con túnel SSH (5436) |
| `INTERNAL_TOKEN`, `SAAS_BRIDGE_SECRET` | Recomendadas | Token interno y secreto del bridge SaaS (`openssl rand -base64 48`, distintos entre sí) |
| `ADMIN_JWT_SECRET` | Para el panel | Firma del JWT del panel interno (≥ 32 bytes). Sin ella el panel queda apagado |
| `ADMIN_ALLOWED_ORIGINS` | Si hay otro origen | Origen del panel; con el panel por `/api` en su dominio no hace falta |
| `ADMIN_INITIAL_EMAIL`, `ADMIN_INITIAL_PASSWORD` | Primer arranque | Primer superadmin del panel; **quitarlas después del primer ingreso** |
| `ADMIN_SERVICE_KEY`, `ADMIN_SERVICE_ALLOWED_IPS` | Para la consola | Clave de servicio (= `API_SUNAT_ADMIN_KEY` de la consola, ≥ 32 bytes) e IP o red de la consola |
| `PORTAL_JWT_SECRET`, `PORTAL_ALLOWED_ORIGINS` | Para el portal | Clave del portal (≥ 32 bytes, **distinta** de `ADMIN_JWT_SECRET`) y dominio público del portal (https, sin `*`) |
| `SUNAT_PROD_GRE_CLIENT_ID` / `_SECRET` | Para guías de remisión | Credenciales de la API REST de GRE |
| `LOOKUP_API_TOKEN`, `MAIL_*`, `OPS_WEBHOOK_URL` | Opcionales | Consulta RUC / DNI, correo y alertas |

El resto de la configuración (URLs de SUNAT, cupos, planificador, cola) tiene su valor por defecto en `application.yml`. Autoservicio
(`REGISTRO_PUBLICO_ENABLED`, `SUSCRIPCION_AUTOSERVICIO_ENABLED`) sale **apagado**.

## 3. Cómo se conectan los demás
- **Panel y portal (frontends):** dentro de la red `sunat-prod`, la API es `http://sunat-api:8081`; sus nginx solo reenvían `/api/admin/`
  (panel) y `/api/portal/` (portal). Despliegue primero esta API.
- **Consola de plataforma:** `API_SUNAT_URL` = el dominio público de la API (`https://api.midominio.pe`), `API_SUNAT_ADMIN_KEY` = la
  `ADMIN_SERVICE_KEY` de aquí, y la IP de salida del servidor de la consola en `ADMIN_SERVICE_ALLOWED_IPS`.
- **Empresas emisoras (ERP u otro cliente):** usan `https://api.midominio.pe/api/v1` con su `X-Api-Key` / `X-Api-Secret`.

## 4. Despliegue automático
`.github/workflows/deploy-prod.yml` y `deploy-qa.yml` llaman al webhook de Coolify al hacer push a `deploy-prod` / `deploy-qa`.
Secretos del repositorio: `COOLIFY_PROD_URL`, `COOLIFY_PROD_TOKEN`, `COOLIFY_QA_URL`, `COOLIFY_QA_TOKEN`.

## 5. Respaldo y mantenimiento
- Base: tarea programada de Coolify con `pg_dump` hacia el volumen `sunat-db-backups`, copiado fuera del servidor.
- Archivos (XML, CDR, PDF, certificados): volumen `sunat-storage`; respaldarlo junto con la base y con `APP_ENCRYPTION_KEY`.
- DBeaver: túnel SSH al VPS y conexión a `localhost:5436` (solo escucha en 127.0.0.1).
- Migraciones: Flyway al arrancar; una migración nueva no puede usar `CREATE INDEX CONCURRENTLY` dentro de una transacción.
