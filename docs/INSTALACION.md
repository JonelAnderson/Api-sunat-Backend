# Manual de instalación y funcionamiento

Guía paso a paso para clonar, configurar y levantar la **API SUNAT (Spring Boot)**, con Docker o
sin Docker, y para entender cómo trabajan por dentro la cola de envíos a SUNAT y las tareas
programadas.

## Índice

1. [Qué se instala](#1-qué-se-instala)
2. [Requisitos](#2-requisitos)
3. [Clonar el repositorio](#3-clonar-el-repositorio)
4. [Configurar el archivo `.env`](#4-configurar-el-archivo-env)
5. [Opción A: levantar con Docker (recomendada)](#5-opción-a-levantar-con-docker-recomendada)
6. [Opción B: levantar sin Docker](#6-opción-b-levantar-sin-docker)
7. [Comprobar que funciona y emitir la primera factura](#7-comprobar-que-funciona-y-emitir-la-primera-factura)
8. [Cómo funcionan los jobs (cola de trabajos)](#8-cómo-funcionan-los-jobs-cola-de-trabajos)
9. [Tareas programadas](#9-tareas-programadas)
10. [Operación diaria](#10-operación-diaria)
11. [Pasar a producción](#11-pasar-a-producción)
12. [Solución de problemas](#12-solución-de-problemas)

---

## 1. Qué se instala

```text
                 ┌────────────────────────────── API SUNAT (un solo proceso Java) ─┐
 Cliente HTTP ──▶│  API REST :8081   ──▶  Cola de trabajos (worker)  ──▶  SUNAT     │
 (ERP, POS,      │       │                 Planificador (tareas)          (SOAP/REST)│
  Postman)       └───────┼──────────────────────┼──────────────────────────────────┘
                         ▼                      ▼
                   PostgreSQL 18           Redis 7                Almacenamiento
                   (datos, migraciones     (cola, caché,          (XML, CDR, PDF y
                    automáticas Flyway)     rate limit, candados)  certificados cifrados)
```

| Componente | Para qué sirve |
|---|---|
| **API** (Java 21 + Spring Boot 4.2) | Recibe las peticiones, valida, calcula, guarda y encola el envío. En el mismo proceso corren el **worker** de la cola y el **planificador**. |
| **PostgreSQL 18** | Empresas, comprobantes, series, pagos, trabajos fallidos. Las tablas se crean solas al arrancar (Flyway). |
| **Redis 7** | Cola de trabajos, caché, límite de peticiones, idempotencia y candados de las tareas programadas. |
| **Almacenamiento** (carpeta o volumen) | XML firmados, CDR de SUNAT, PDF y certificados de cada empresa (cifrados). |

No hay que instalar un worker ni un cron aparte: al levantar la API ya están funcionando.

## 2. Requisitos

**Con Docker (opción A):**

- [Git](https://git-scm.com/downloads).
- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (Windows/macOS) o Docker Engine
  con el plugin **Compose v2** (Linux). Comprobar: `docker compose version`.
- 4 GB de RAM libres y unos 3 GB de disco para las imágenes.

**Sin Docker (opción B):**

| Programa | Versión | Notas |
|---|---|---|
| JDK | **21** (por ejemplo Eclipse Temurin 21) | `java -version` debe decir 21 |
| Maven | 3.9 o superior | Solo para compilar (`mvn -v`) |
| PostgreSQL | 17 | Con soporte ICU y la extensión `unaccent` (vienen en los instaladores oficiales; en Linux, paquete `postgresql-contrib`) |
| Redis | 7 | En Windows no hay versión oficial: use WSL, [Memurai](https://www.memurai.com/) o Redis en Docker (modo híbrido, §6.4) |

**Puertos** que usa por defecto:

| Puerto | Servicio | Cambiar con |
|---|---|---|
| 8081 | API | `APP_PORT` (Docker) / `SERVER_PORT` (sin Docker) |
| 15081 | PostgreSQL publicado por Docker | `DB_HOST_PORT` |
| 16081 | Redis publicado por Docker | `REDIS_HOST_PORT` |

## 3. Clonar el repositorio

```bash
git clone https://github.com/yorchavez9/API-SUNAT-SPRING-BOOT.git
cd API-SUNAT-SPRING-BOOT
```

Todos los comandos de este manual se ejecutan **dentro de esa carpeta**.

## 4. Configurar el archivo `.env`

La configuración vive en variables de entorno. El repositorio trae una plantilla con todas,
comentadas: [`.env.example`](../.env.example). El archivo `.env` real **nunca se sube a git**
(ya está en `.gitignore`).

```bash
cp .env.example .env          # Windows PowerShell: Copy-Item .env.example .env
```

Edite `.env` y complete **como mínimo** estas dos:

| Variable | Qué poner |
|---|---|
| `APP_ENCRYPTION_KEY` | Clave de 32 bytes en base64 con la que se cifran claves SOL, `api_secret` y certificados. |
| `DB_PASSWORD` | Contraseña de la base de datos (con Docker se crea con esa contraseña). |

Generar la clave:

```bash
openssl rand -base64 32
```

```powershell
# Windows PowerShell (sin openssl): generador criptográfico
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

> **Guarde `APP_ENCRYPTION_KEY` en un lugar seguro y no la cambie después.** Si se pierde o se
> cambia, los secretos ya guardados (claves SOL, certificados, credenciales de las empresas) no se
> pueden descifrar.

Otras variables útiles al empezar (todas opcionales):

| Variable | Valor por defecto | Para qué |
|---|---|---|
| `APP_SEED` | `true` | Carga catálogos (UNSPSC, ubigeo), planes y una **empresa demo en BETA** (RUC `20000000001`). Debe estar en `true` al menos en el primer arranque. |
| `DEMO_API_KEY` / `DEMO_API_SECRET` | vacías | Credenciales fijas para la empresa demo; si quedan vacías se generan solas (ver §7). |
| `INTERNAL_TOKEN` | vacía | Token para integraciones internas sin límite de peticiones (cabecera `X-Internal-Token`). |
| `MAIL_*` | vacías | Servidor SMTP para la recuperación de credenciales por correo. |
| `OPS_WEBHOOK_URL` | vacía | Webhook de Slack/Discord para alertas operativas (si no, solo quedan en el log). |
| `LOG_FORMAT` | `json` | `text` es más legible en desarrollo. |
| `ADMIN_JWT_SECRET`, `ADMIN_ALLOWED_ORIGINS`, `ADMIN_INITIAL_EMAIL`, `ADMIN_INITIAL_PASSWORD` | vacías | Panel de administración interno (apagado sin la clave); ver §10.4. |
| `PORTAL_JWT_SECRET`, `PORTAL_ALLOWED_ORIGINS` | vacías | Portal de clientes (apagado sin la clave, que debe ser distinta de `ADMIN_JWT_SECRET`); ver §10.5. |

El resto (URLs de SUNAT beta y producción, cola, planificador, PDF, liquidación de compra) ya trae
valores correctos.

## 5. Opción A: levantar con Docker (recomendada)

Docker levanta la API, PostgreSQL y Redis con un solo comando; no hace falta instalar Java ni bases
de datos.

### 5.1 Primer arranque

```bash
docker compose up -d --build
```

La primera vez descarga las imágenes y compila la API (5 a 10 minutos). Ver el progreso:

```bash
docker compose ps              # los tres servicios deben quedar "running" / "healthy"
docker compose logs -f api     # Ctrl+C para salir de los logs (la API sigue corriendo)
```

Cuando el log muestre `Started ApiApplication`, la API responde:

```bash
curl http://localhost:8081/api/health
```

| Qué | Dónde |
|---|---|
| API | http://localhost:8081/api/v1 |
| Documentación interactiva (Swagger) | http://localhost:8081/api/docs |
| PostgreSQL (desde su PC, p. ej. con HeidiSQL/DBeaver) | `localhost:15081`, base `api_sunat_spring_boot`, usuario `api_sunat` |
| Redis | `localhost:16081` |

### 5.2 Comandos del día a día

| Acción | Comando |
|---|---|
| Detener (los datos se conservan) | `docker compose down` |
| Volver a levantar | `docker compose up -d` |
| Ver logs | `docker compose logs -f api` |
| Reiniciar solo la API | `docker compose restart api` |
| Actualizar a una versión nueva | `git pull` y luego `docker compose up -d --build api` |
| Borrar **todo** (BD, Redis y archivos) | `docker compose down -v` ⚠️ irreversible |

Los datos viven en volúmenes de Docker (`pgdata`, `redisdata`, `storage`), así que sobreviven a
`down`, a reinicios del equipo y a actualizaciones. Las migraciones de base de datos de una versión
nueva se aplican solas al arrancar.

### 5.3 Variante: Postgres compartido de desarrollo (`docker-compose.local.yml`)

Para no levantar un Postgres por cada sistema, `docker-compose.local.yml` levanta solo la API y Redis y usa
un PostgreSQL 18 compartido llamado `postgres-dev` (su propio compose, red `postgres-dev_default`).

1. Crear la base y el usuario del `.env` en `postgres-dev` (una sola vez; no imprime la contraseña):

   ```bash
   v() { grep -E "^$1=" .env | cut -d= -f2- | tr -d '\r"'; }
   docker exec -i postgres-dev psql -U postgres -v ON_ERROR_STOP=1 \
     -v u="$(v DB_USERNAME)" -v p="$(v DB_PASSWORD)" -v d="$(v DB_DATABASE)" <<'SQL'
   CREATE ROLE :"u" LOGIN PASSWORD :'p';
   CREATE DATABASE :"d" OWNER :"u";
   \c :"d"
   CREATE EXTENSION IF NOT EXISTS unaccent;
   SQL
   ```

2. Definir `REDIS_PASSWORD` en `.env` (Redis no publica puerto: solo lo ve la API).
3. `docker compose -f docker-compose.local.yml up -d --build`.

La API queda en `http://127.0.0.1:8081` (solo esta PC). Las credenciales de la empresa demo generadas
quedan en `docker compose -f docker-compose.local.yml exec api cat /data/storage/demo-credentials.txt`.

## 6. Opción B: levantar sin Docker

### 6.1 Preparar PostgreSQL y Redis

Cree el usuario y la base de datos (con `psql` como superusuario `postgres`):

```sql
CREATE USER api_sunat WITH PASSWORD 'la-misma-de-DB_PASSWORD';
CREATE DATABASE api_sunat_spring_boot OWNER api_sunat ENCODING 'UTF8' TEMPLATE template0;
```

El usuario debe ser **dueño** de la base: la primera migración crea la extensión `unaccent` y una
intercalación ICU para búsquedas sin tildes ni mayúsculas.

Arranque Redis (`redis-server`, el servicio de su sistema, Memurai en Windows o el modo híbrido §6.4).

### 6.2 Cargar el `.env` en la terminal

Sin Docker, la API **no lee el archivo `.env` por sí sola**: toma las variables de entorno del
proceso. En `.env` ponga `DB_HOST=localhost`, `DB_PORT=5432`, `REDIS_HOST=localhost`,
`REDIS_PORT=6379` (o los de su servidor) y cárguelo en la terminal antes de arrancar:

```bash
# Linux / macOS / Git Bash
set -a; . ./.env; set +a
```

```powershell
# Windows PowerShell (solo para esta ventana)
Get-Content .env | Where-Object { $_ -match '^\s*[A-Z_][A-Z0-9_]*=' } | ForEach-Object {
  $k, $v = $_ -split '=', 2
  [Environment]::SetEnvironmentVariable($k.Trim(), $v.Trim().Trim('"'), 'Process')
}
```

### 6.3 Compilar y arrancar

```bash
mvn -DskipTests package              # genera target/api-sunat.jar
java -jar target/api-sunat.jar       # arranca API + worker + planificador
```

- Puerto distinto de 8081: `SERVER_PORT=9090 java -jar target/api-sunat.jar`
  (PowerShell: `$env:SERVER_PORT=9090` antes de `java -jar`).
- Los archivos (XML, CDR, PDF, certificados) se guardan en `STORAGE_PATH` (por defecto `./storage`).
- Para dejarlo como servicio en Linux, un ejemplo de `systemd`:

  ```ini
  # /etc/systemd/system/api-sunat.service
  [Unit]
  Description=API SUNAT
  After=network.target postgresql.service redis.service

  [Service]
  User=apisunat
  WorkingDirectory=/opt/api-sunat
  EnvironmentFile=/opt/api-sunat/.env
  ExecStart=/usr/bin/java -jar /opt/api-sunat/api-sunat.jar
  Restart=on-failure

  [Install]
  WantedBy=multi-user.target
  ```

  `sudo systemctl enable --now api-sunat` y logs con `journalctl -u api-sunat -f`.
  `EnvironmentFile` lee el mismo `.env`.

### 6.4 Modo híbrido (recomendado para desarrollar sin instalar bases de datos)

PostgreSQL y Redis en Docker, la API con su Java local:

```bash
docker compose up -d postgres redis
```

y en `.env`: `DB_HOST=localhost`, `DB_PORT=15081`, `REDIS_HOST=localhost`, `REDIS_PORT=16081`.
Luego §6.2 y §6.3.

## 7. Comprobar que funciona y emitir la primera factura

Todo esto se hace en **BETA** (el entorno de pruebas de SUNAT): acepta el usuario SOL
`MODDATOS`/`MODDATOS` y cualquier certificado, incluso uno autofirmado. Nada de lo que se emite en
BETA tiene validez tributaria.

1. **Salud** (no pide credenciales): BD, Redis, disco, almacenamiento y SUNAT deben salir `ok`.

   ```bash
   curl http://localhost:8081/api/health
   ```

2. **Certificado de prueba** (solo para BETA):

   ```bash
   openssl req -x509 -newkey rsa:2048 -nodes -days 365 -subj "/CN=PRUEBA" -keyout k.pem -out c.pem
   cat k.pem c.pem > cert-beta.pem          # PowerShell: Get-Content k.pem, c.pem | Set-Content cert-beta.pem
   ```

3. **Registrar su empresa.** La respuesta trae `api_key` y `api_secret` **una sola vez**: guárdelos.

   ```bash
   curl -X POST http://localhost:8081/api/v1/registro -H "Accept: application/json" \
     -F ruc=20123456789 -F "razon_social=MI EMPRESA S.A.C." -F "direccion=AV. PRINCIPAL 123" \
     -F ubigeo=150101 -F sol_user=MODDATOS -F sol_pass=MODDATOS -F entorno=beta \
     -F email=admin@miempresa.pe -F "certificado=@cert-beta.pem"
   ```

   En las siguientes peticiones, reemplace `SU_API_KEY` y `SU_API_SECRET` por esos valores.

4. **Crear las series.** Una empresa recién registrada no tiene series y no puede emitir hasta
   crearlas. Esto crea `F001` (facturas) y `B001` (boletas):

   ```bash
   curl -X POST http://localhost:8081/api/v1/series/init-defaults -H "Content-Type: application/json" \
     -H "X-Api-Key: SU_API_KEY" -H "X-Api-Secret: SU_API_SECRET" -d "{}"
   ```

   Para notas de crédito y débito, guías, retenciones, etc. cree sus series con `POST /api/v1/series`
   (ver la colección Postman).

5. **Emitir una factura:**

   ```bash
   curl -X POST http://localhost:8081/api/v1/facturas \
     -H "Content-Type: application/json" -H "X-Api-Key: SU_API_KEY" -H "X-Api-Secret: SU_API_SECRET" \
     -d '{"serie":"F001","fecha_emision":"2026-10-27",
          "cliente":{"tipo_doc":"6","num_doc":"20601234567","razon_social":"CLIENTE SAC","direccion":"AV. LIMA 1"},
          "items":[{"codigo":"P1","descripcion":"Producto","unidad":"NIU","cantidad":1,"precio_unitario":118,"tip_afe_igv":"10"}]}'
   ```

   (Use la fecha de hoy.) La respuesta llega al instante con `"sunat": {"estado": "enviado"}`: el
   envío real lo hace la cola (§8).

6. **Ver el resultado** a los pocos segundos: `GET /api/v1/facturas/{id}` → `sunat.estado` pasa a
   `aceptado` (o `rechazado`, con el código y la descripción de SUNAT). Descargas:
   `.../{id}/xml`, `.../{id}/cdr`, `.../{id}/pdf?formato=a4|a5|ticket-80|ticket-58`.

**Atajo: la empresa demo.** Con `APP_SEED=true` ya existe una empresa BETA (RUC `20000000001`) con
todas sus series. Sus credenciales son `DEMO_API_KEY`/`DEMO_API_SECRET` del `.env` o, si las dejó
vacías, las generadas en el primer arranque:

```bash
docker compose exec api cat /data/storage/demo-credentials.txt   # con Docker
cat storage/demo-credentials.txt                                  # sin Docker
```

Para ver todos los endpoints y probar los demás comprobantes, siga el [`README`](../README.md), la
documentación interactiva (http://localhost:8081/api/docs) y la colección Postman de
[`docs/postman/`](postman/).

## 8. Cómo funcionan los jobs (cola de trabajos)

Enviar un comprobante a SUNAT puede tardar segundos o fallar por un corte de SUNAT. Por eso la API
**no espera a SUNAT**: guarda el comprobante, responde y deja el envío en una cola de Redis que
procesa un *worker* dentro del mismo proceso.

### 8.1 Recorrido de una factura

```text
POST /api/v1/facturas
  1. valida los datos (422 si algo está mal)
  2. asigna el correlativo (bloqueo en BD: nunca se repite) y guarda   → estado "pendiente"
  3. encola el trabajo de envío (solo después de confirmar en BD)       → estado "enviado"
  4. responde 201 al cliente  ◀── aquí termina la petición

Worker (segundos después):
  5. arma el XML UBL 2.1, lo firma con el certificado de la empresa y lo comprime
  6. lo envía a SUNAT con el usuario SOL
  7. guarda el XML, el CDR y el PDF A4     → estado "aceptado" o "rechazado"
  8. avisa al sistema del cliente por webhook (si configuró url_webhook)
  si SUNAT no responde: reintenta más tarde (8.3)
```

| Estado (`sunat.estado`) | Significado |
|---|---|
| `pendiente` | Guardado sin enviar: envío manual (`enviar_automatico: false`), boleta para resumen diario (`solo_registro`) o fallo del certificado. |
| `enviado` | En la cola o esperando respuesta (los resúmenes, bajas y guías usan un *ticket* que se consulta después). |
| `aceptado` | SUNAT lo aceptó (puede traer observaciones en `sunat.notas`). |
| `rechazado` | SUNAT lo rechazó: corregir con `PUT` y se reenvía. |
| `anulacion_en_proceso` / `anulado` | Con una baja o reversión enviada / aceptada. |

### 8.2 Tipos de trabajo

| Trabajo | Qué hace | Intentos | Espera entre intentos |
|---|---|---|---|
| Envío de factura, boleta y notas | `sendBill` a SUNAT | 20 | de 15 s hasta 1 h, creciente |
| Envío de resumen diario / baja | `sendSummary` → recibe un ticket | 10 | de 15 s a 10 min |
| Consulta de ticket (resumen, baja, reversión) | `getStatus` hasta tener el CDR | 15 | de 15 s a 10 min |
| Envío y consulta de guías de remisión | API REST GRE (token OAuth2) + ticket | 10 / 15 | de 15 s a 10 min |
| Retenciones, percepciones, liquidación de compra | Servicio "otros CPE" | 10 | de 15 s a 10 min |
| Webhook al cliente | `POST` firmado a `url_webhook` | 3 | 10 s, 30 s, 90 s |

Un **rechazo** de SUNAT no se reintenta (es definitivo hasta corregir el comprobante); sí se
reintentan los cortes de red, los *timeouts* y los errores 5xx de SUNAT.

### 8.3 Reintentos, fallos y recuperación

- **Trabajos fallidos:** si un trabajo agota sus intentos, queda en la tabla `failed_jobs` y se
  genera una alerta. Revisarlos y reintentarlos:

  ```bash
  docker compose run --rm api ops ops:jobs-fallidos
  docker compose run --rm api ops jobs-fallidos:reintentar          # todos (o --id=N)
  ```

- **Si la API se cae** con trabajos en curso, no se pierden: cada trabajo reservado tiene un plazo y,
  al vencer, otro worker (o la misma API al volver) lo retoma.
- **Comprobantes atascados** en `enviado` más de 15 minutos se reencolan solos cada 15 minutos
  (tarea `sunat:reintentar-pendientes`, §9).
- **Varias instancias:** se pueden levantar varias copias de la API con la misma BD, el mismo Redis y
  el mismo almacenamiento; cada trabajo lo toma una sola copia.

### 8.4 Webhooks al sistema del cliente

Si la empresa configura `url_webhook` (`PUT /api/v1/empresa`), cada cambio de estado se le notifica
con un `POST` JSON:

- Cabeceras `X-Webhook-Event` (por ejemplo `document.sent`, `document.rejected`,
  `summary.status_updated`, `voided.status_updated`) y `X-Signature: sha256=<HMAC>`.
- La firma es un HMAC-SHA256 del cuerpo con el `api_secret` de la empresa: verifíquela antes de
  confiar en el aviso.
- Por seguridad no se envían webhooks a direcciones de red privadas (`localhost`, `192.168.x`…),
  salvo `WEBHOOKS_ALLOW_PRIVATE=true` en desarrollo.

### 8.5 Configuración de la cola

| Variable | Defecto | Efecto |
|---|---|---|
| `QUEUE_WORKER_ENABLED` | `true` | `false` = esta instancia solo atiende HTTP y no procesa la cola. |
| `QUEUE_CONCURRENCY` | `4` | Trabajos simultáneos por instancia. |
| `QUEUE_POLL_MS` | `500` | Cada cuánto el worker busca trabajos nuevos. |
| `SUNAT_PER_TENANT_PER_MINUTE` | `120` | Límite de envíos por minuto por empresa. |
| `SUNAT_HTTP_TIMEOUT` | `60` | Segundos máximos de espera a SUNAT. |

## 9. Tareas programadas

El planificador también corre dentro de la API (`SCHEDULER_ENABLED=true`). Los horarios están en
**UTC** (Perú = UTC−5):

| Tarea | Cuándo (UTC) | Hora Perú | Qué hace |
|---|---|---|---|
| `summaries:poll-pending` | cada 5 min | — | Consulta los tickets de resúmenes sin respuesta final |
| `ops:salud` | cada 10 min | — | Revisa BD, Redis, disco, almacenamiento y SUNAT; alerta si algo falla |
| `sunat:reintentar-pendientes` | cada 15 min | — | Reencola comprobantes atascados en `enviado` |
| `ops:jobs-fallidos` | cada hora | — | Alerta si hay trabajos fallidos |
| `suscripciones:renovar` | 06:00 | 01:00 | Renueva planes, aplica cancelaciones y cambios de plan programados |
| `suscripciones:vencer-pruebas` | 07:00 | 02:00 | Avisa y vence las pruebas gratis |
| `certificados:verificar` | 08:00 | 03:00 | Avisa de certificados vencidos o por vencer (`OPS_CERT_DIAS_AVISO`) |
| `uso:reiniciar` | día 1, 00:05 | último día del mes, 19:05 | Reinicia los contadores de uso del mes |
| `logs:purge` | domingo 03:00 | sábado 22:00 | Borra registros de peticiones antiguos (`API_LOGS_RETENTION_DAYS`) |

Con varias instancias cada tarea corre en una sola (candado en Redis). Cualquier tarea se puede
lanzar a mano (§10.1).

## 10. Operación diaria

### 10.1 Comandos de operación

Ejecutan una tarea y terminan (no levantan el servidor web):

```bash
docker compose run --rm api ops                          # lista de tareas
docker compose run --rm api ops ops:salud
docker compose run --rm api ops certificados:verificar --tenant=5
docker compose run --rm api ops sunat:reintentar-pendientes --minutos=15 --dias=7
```

Sin Docker: `java -jar target/api-sunat.jar ops <tarea>` (con el `.env` cargado, §6.2).

### 10.2 Logs

- Docker: `docker compose logs -f api` (`--since 1h` para acotar).
- Sin Docker: la salida estándar del proceso (o `journalctl` con systemd).
- Formato JSON por defecto (`LOG_FORMAT=json`), listo para Loki/ELK; `text` para leer a mano. Los
  logs nunca incluyen claves, certificados ni `api_secret`.

### 10.3 Respaldos

Respalde **la base de datos y el almacenamiento**; la `APP_ENCRYPTION_KEY` se guarda aparte (sin ella
los respaldos no se pueden descifrar). Comandos listos en el [`README`](../README.md#respaldos).

### 10.4 Panel de administración

La web interna `Admin-api-sunat-Frontend` (puerto 4220 en desarrollo) usa `/api/admin/**` para ver y controlar
todas las empresas. Viene **apagada**: sin `ADMIN_JWT_SECRET` esas rutas responden 503.

1. En el `.env`:
   ```dotenv
   ADMIN_JWT_SECRET=<salida de: openssl rand -base64 48>
   ADMIN_ALLOWED_ORIGINS=https://localhost:4220
   ADMIN_INITIAL_EMAIL=correo-del-primer-superadmin@su-dominio.pe
   ADMIN_INITIAL_PASSWORD=<8-64 caracteres con mayúscula, minúscula, número y especial>
   ```
   En Windows sin openssl, genere la clave con el mismo comando de PowerShell de §4 (48 bytes en lugar de 32).
2. Reinicie la API. Si no existe ningún administrador, se crea el superadmin con ese correo (el log dice
   "Superadministrador inicial del panel creado"; nunca muestra la contraseña). Si la contraseña no cumple la política,
   el log lo avisa y no se crea.
3. En el primer ingreso el panel pide configurar el **MFA**: escanee el QR con una app autenticadora (Google
   Authenticator, Microsoft Authenticator, 1Password…), escriba el código y **guarde los 10 códigos de recuperación**
   (cada uno sirve una vez si pierde el teléfono). Desde entonces cada ingreso pide contraseña y código.
4. Quite `ADMIN_INITIAL_PASSWORD` del `.env`. Los demás administradores los crea el superadmin desde *Administración →
   Administradores* (roles `superadmin`, `operador`, `soporte` y `lectura`).

Si un administrador pierde el teléfono y los códigos, otro superadmin le restablece el MFA (o la contraseña) desde el
panel. Cinco contraseñas o códigos fallidos bloquean la cuenta 15 minutos (un superadmin puede desbloquearla).

**Usuarios `soporte` de una base de API-PRO.** Al arrancar, Flyway aplica las migraciones pendientes. Si la base trae
los usuarios de API-PRO y todavía no aplicó `V7__panel_admin.sql`, el callback `db/migration/beforeMigrate.sql` guarda
quiénes tenían el rol `soporte` y `V10__roles_soporte_de_api_pro.sql` se lo devuelve (V7 los habría dejado en
`lectura`); no hay nada que hacer a mano. En una base que **ya** había aplicado V7 antes de esta versión, esos usuarios
quedaron en `lectura` y no hay registro de quiénes eran: un superadmin les asigna el rol `soporte` desde *Administración
→ Administradores* (editar el usuario y elegir el rol). El rol `soporte` lee todo menos la auditoría, consulta SUNAT en
vivo y puede reenviar, anular y refrescar comprobantes (diseño §10.2 y §12.3 en `Docs/api-sunat-panel-admin-diseno.md`).

**Plan y cupo desde el panel.** Desde V9 el plan que se fija en el panel (*Empresas → Cambiar plan*, o al dar de alta)
es el plan efectivo de la empresa: el panel crea su suscripción (`payment_gateway = 'admin'`, sin cobro) y el cupo
mensual que se escribe ahí es el que se aplica al emitir (vacío = el cupo del plan). V9 no cambia el cupo ni el plan de
ninguna empresa existente; si antes de esta versión se cambió un plan desde el panel, vuelva a guardarlo para que rija
(el detalle de la empresa lo marca con `planInSync: false`). Detalle en el diseño §12.2.

### 10.5 Portal de clientes

La web pública del cliente final usa `/api/portal/**` para consultar su resumen, su uso y sus comprobantes y regenerar
sus credenciales (contrato en `Docs/api-sunat-portal-clientes-diseno.md`). Viene **apagada**: sin `PORTAL_JWT_SECRET`
todas esas rutas, también el login, responden 503.

1. En el `.env`:
   ```dotenv
   PORTAL_JWT_SECRET=<salida de: openssl rand -base64 48>
   PORTAL_ALLOWED_ORIGINS=https://localhost:4230
   ```
   La clave debe tener al menos 32 bytes y ser **distinta** de `ADMIN_JWT_SECRET` (si son iguales el portal queda
   apagado y el log lo avisa). En producción, `PORTAL_ALLOWED_ORIGINS` lleva la URL HTTPS del portal (sin comodín; sin
   ella un navegador no puede llamar a la API desde otro origen).
2. Reinicie la API. La migración `V12__portal_clientes.sql` crea las tablas `portal_users`, `portal_user_tenants`,
   `portal_invitations` y `portal_refresh_tokens`.
3. Las personas del portal no se registran solas: la consola de plataforma las invita
   (`POST /api/admin/portal/invitations`, con `X-Admin-Key` + `X-Admin-Actor`, o un superadmin del panel). El token de la
   invitación se entrega **una sola vez**, vence a las 72 horas y se usa en la pantalla de activación del portal.
4. En el primer ingreso el portal pide configurar el **MFA** (QR y 10 códigos de recuperación), igual que el panel.

**Límites.** 10 intentos por minuto y por IP en `/api/portal/auth/**` (login, MFA, activación, refresh; si Redis no
responde este límite deja pasar, porque el bloqueo de 15 min tras 5 fallos por cuenta vive en la base) y 3 regeneraciones
de credenciales por hora y por empresa (si Redis no responde **no** se regenera). El límite por IP usa la dirección que ve
la API (Tomcat solo confía en `X-Forwarded-For` de redes privadas o loopback): detrás de un proxy del VPS confirme que
reenvía la IP real del cliente, o todos los clientes compartirán el mismo cupo.

**Si alguien pierde el acceso:** la consola invita de nuevo al mismo correo (reemplaza la contraseña y cierra sus sesiones;
con `resetMfa: true` también borra el autenticador). Para cortar el acceso de inmediato, `PUT /api/admin/portal/users/{id}/estado`
con `{ "active": false }` cierra todas sus sesiones al instante.

## 11. Pasar a producción

| Paso | Detalle |
|---|---|
| `APP_ENV=production` y `APP_DEBUG=false` | Las respuestas de error nunca muestran detalles internos. |
| `APP_URL` | URL pública con HTTPS (se usa en enlaces de XML/PDF). Ponga la API detrás de un proxy con TLS (Nginx, Caddy, Traefik). |
| Empresa en producción | `PUT /api/v1/empresa` con `entorno: "produccion"`, usuario y clave SOL reales y certificado digital real (`POST /api/v1/empresa/certificado`). |
| Guías de remisión | `client_id`/`client_secret` de la API GRE de SUNAT en la empresa (o `SUNAT_PROD_GRE_*`). |
| Secretos | `INTERNAL_TOKEN`, `SAAS_BRIDGE_SECRET`, `MAIL_*`, `LOOKUP_API_TOKEN` según los use; `CORS_ALLOWED_ORIGINS` si un navegador llama a la API. |
| Registro y suscripción | `REGISTRO_PUBLICO_ENABLED` y `SUSCRIPCION_AUTOSERVICIO_ENABLED` vienen en `false` (`POST /api/v1/registro` y crear / cambiar de plan / cancelar en `/api/v1/suscripcion` responden 404). Enciéndalas solo si las empresas se dan de alta o cambian de plan por su cuenta; con la consola de plataforma se gestionan desde ella. |
| Alertas | `OPS_WEBHOOK_URL` para enterarse de certificados por vencer, trabajos fallidos y caídas. |
| Datos iniciales | El primer arranque necesita `APP_SEED=true` (catálogos y planes; también crea la empresa demo, que siempre es BETA y no emite en producción). Después puede pasarse a `false`. |
| Respaldos | Programe los respaldos de §10.3 y pruebe restaurarlos. |
| Panel de administración | `ADMIN_JWT_SECRET` propio de producción (≥ 32 bytes), `ADMIN_ALLOWED_ORIGINS` con la URL HTTPS del panel y el primer superadmin (§10.4). Sin la clave el panel queda apagado. |
| Portal de clientes | `PORTAL_JWT_SECRET` propio (≥ 32 bytes y distinto de `ADMIN_JWT_SECRET`) y `PORTAL_ALLOWED_ORIGINS` con la URL HTTPS del portal (§10.5). Sin la clave el portal queda apagado. |

## 12. Solución de problemas

| Síntoma | Causa probable | Solución |
|---|---|---|
| `failed to connect to the docker API` | Docker Desktop no está abierto | Ábralo y espere a que diga *Engine running*. |
| `Define DB_PASSWORD en .env` | Falta `DB_PASSWORD` | Complétela en `.env` (§4). |
| La API se detiene con `APP_ENCRYPTION_KEY no está definida` o `debe ser de 32 bytes` | Clave vacía o mal generada | Genérela con `openssl rand -base64 32` (§4). |
| `port is already allocated` / `Address already in use` | Otro programa usa 8081, 15081 o 16081 | Cambie `APP_PORT`, `DB_HOST_PORT`, `REDIS_HOST_PORT` (Docker) o `SERVER_PORT` (sin Docker). |
| Sin Docker: `Connection refused` a PostgreSQL o Redis | `.env` no cargado o host/puerto incorrecto | Cargue el `.env` en la misma terminal (§6.2) y revise `DB_HOST`/`DB_PORT`/`REDIS_*`. |
| Sin Docker: error de migración con `unaccent` o `collation` | El usuario no es dueño de la base o falta `postgresql-contrib`/ICU | Cree la base con `OWNER api_sunat` (§6.1) e instale los paquetes. |
| `Aún no tienes ninguna serie registrada para facturas` | Empresa nueva sin series | Créelas con `POST /api/v1/series/init-defaults` (§7, paso 4). |
| `401` en todas las peticiones | `X-Api-Key`/`X-Api-Secret` incorrectos | Use las credenciales de la empresa (demo: §7); se pueden regenerar con `POST /api/v1/empresa/credenciales/regenerar`. |
| Comprobante en `enviado` mucho tiempo | SUNAT lento o caído | La cola reintenta sola; revise `GET /api/health` y `docker compose logs api`. |
| Comprobante `rechazado` | Dato inválido para SUNAT | Lea `sunat.codigo` y `sunat.descripcion`, corrija con `PUT` y se reenvía. |
| Guías rechazadas en BETA | El sandbox GRE solo autoriza su RUC de prueba | Normal en BETA; en producción use sus credenciales GRE. |
| `/api/admin/...` responde 503 | Falta `ADMIN_JWT_SECRET` o tiene menos de 32 bytes | Defínala (§10.4) y reinicie. |
| El panel no carga datos (error de CORS en el navegador) | Su origen no está en `ADMIN_ALLOWED_ORIGINS` | Agréguelo (por ejemplo `https://localhost:4220`) y reinicie. |
| `/api/portal/...` responde 503 | Falta `PORTAL_JWT_SECRET`, tiene menos de 32 bytes o es igual a `ADMIN_JWT_SECRET` | Defina una clave propia (§10.5) y reinicie. |
| El portal no carga datos (error de CORS en el navegador) | Su origen no está en `PORTAL_ALLOWED_ORIGINS` | Agréguelo (por ejemplo `https://localhost:4230`) y reinicie. |
| El portal responde 429 en el login | Más de 10 intentos por minuto desde la misma IP | Espere un minuto; si pasa a menudo con usuarios legítimos, revise que la API vea la IP real del cliente (proxy). |
| La invitación "no es válida o venció" | Token usado, vencido (72 h) o reemplazado por una invitación nueva | La consola invita de nuevo al mismo correo y entrega el token nuevo. |
| No se creó el primer superadmin | Ya hay un administrador, o el correo / la contraseña no son válidos | Revise el aviso en el log; la contraseña necesita mayúscula, minúscula, número y especial. |

---

**Para desarrolladores:** las pruebas automáticas corren en Docker
(`docker compose run --rm mvn test`, varios minutos; necesitan Docker por Testcontainers). La
arquitectura interna está en [`ARQUITECTURA.md`](ARQUITECTURA.md) y las diferencias con API-PRO en
[`DIFERENCIAS.md`](DIFERENCIAS.md).
