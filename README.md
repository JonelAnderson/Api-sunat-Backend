# API SUNAT · Spring Boot

API REST multiempresa de facturación electrónica SUNAT (Perú) en **Java 21 + Spring Boot 4.2** (PostgreSQL 18),
equivalente a API-PRO (Laravel + Greenter): mismas URLs, campos JSON, códigos HTTP y formato
de errores. Un sistema que hoy consume API-PRO solo cambia la URL base.

> Estado: **Fase 6** — base, autenticación, errores y health (Fase 1); empresa, certificados,
> sucursales, clientes, series, catálogos UNSPSC/ubigeo, registro, planes, suscripción,
> recuperación de credenciales y bridge SaaS (Fase 2); **facturas y boletas con envío a SUNAT**
> (XML UBL 2.1 idéntico a Greenter, firma XMLDSig, ZIP, sendBill, CDR, cola con reintentos, webhooks) (Fase 3);
> **notas de crédito y débito, resumen diario, comunicación de baja, tickets y consulta de CDR** (Fase 4);
> **guías de remisión remitente y transportista (API REST GRE), retenciones, percepciones y reversión** (Fase 5);
> **liquidación de compra electrónica (tipo 04), módulo nuevo** (Fase 5B, ver [`docs/LIQUIDACION_COMPRA.md`](docs/LIQUIDACION_COMPRA.md));
> **representación impresa en PDF con QR (A4, A5, tickets de 80 y 58 mm)** (Fase 6);
> **trabajos fallidos, tareas programadas, comandos de operación, alertas, varias instancias,
> Postman y documentación** (Fase 7); **panel, reportes (JSON y PDF), exportación ZIP, búsqueda
> RUC/DNI y Consulta Integrada de CPE** (Fase 7B); **auditoría final** (Fase 8, ver [`docs/AUDITORIA.md`](docs/AUDITORIA.md)).
>
> Pendiente: SIRE (fase posterior).

## Endpoints disponibles

| Grupo | Rutas |
|---|---|
| Salud | `GET /api/health`, `GET /api/v1/sunat/estado` |
| Empresa | `GET/PUT/DELETE /api/v1/empresa`, `POST /api/v1/empresa/logo`, `POST /api/v1/empresa/certificado`, `GET /api/v1/empresa/credenciales`, `POST /api/v1/empresa/credenciales/regenerar`, alias `GET/POST /api/sunat/configuracion` |
| Configuración | `/api/v1/sucursales`, `/api/v1/clientes`, `/api/v1/series` (+ `POST /api/v1/series/init-defaults`) |
| Catálogos | `/api/v1/catalogos/producto-sunat` (+ `segmentos`, `familias`, `clases`, `productos`, `{codigo}`), `/api/v1/catalogos/ubigeo/regiones|provincias|distritos` |
| Cuenta | `POST /api/v1/registro` (**apagado por defecto**, `REGISTRO_PUBLICO_ENABLED`), `GET /api/v1/planes`, `POST /api/v1/credenciales/recuperar(/verificar)`, `/api/v1/suscripcion` (+ `cambiar-plan`, `cancelar`, `pagos`, `uso`; crear, cambiar de plan y cancelar están **apagados por defecto**, `SUSCRIPCION_AUTOSERVICIO_ENABLED`; las lecturas siempre activas) |
| Bridge SaaS | `POST /api/bridge/auth`, `POST /api/bridge/provision` (409 si el RUC ya es de otra cuenta), `DELETE /api/bridge/tenant` (cabecera `X-Bridge-Key`) |
| Facturas (01) | `POST/GET /api/v1/facturas`, `POST /api/v1/facturas/masivo`, `GET/PUT /api/v1/facturas/{id}`, `GET .../{id}/xml`, `GET .../{id}/cdr`, `POST .../{id}/enviar`, `POST .../{id}/reenviar`, `GET/POST .../{id}/pagos`, `DELETE .../{id}/pagos/{pagoId}` |
| Boletas (03) | Igual que facturas (sin `masivo`) + `DELETE /api/v1/boletas/{id}` y `solo_registro` para el resumen diario |
| Notas de crédito (07) y débito (08) | `POST/GET /api/v1/notas-credito`, `GET/PUT .../{id}`, `GET .../{id}/xml`, `GET .../{id}/cdr`, `POST .../{id}/enviar`, `POST .../{id}/reenviar` (igual en `/api/v1/notas-debito`) |
| Resumen diario (RC) | `POST/GET /api/v1/resumenes`, `GET .../{id}/estado`, `GET .../{id}/xml`, `GET .../{id}/cdr`, `POST .../{id}/enviar` |
| Comunicación de baja (RA) | `POST/GET /api/v1/anulaciones`, `GET .../{id}`, `GET .../{id}/estado`, `GET .../{id}/xml`, `GET .../{id}/cdr`, `POST .../{id}/enviar` |
| Guías de remisión (09 / 31) | `POST/GET /api/v1/guias-remision`, `POST /api/v1/guias-remision-transportista`, `GET/PUT .../{id}`, `GET .../{id}/xml`, `GET .../{id}/cdr`, `GET .../{id}/estado`, `POST .../{id}/enviar` |
| Retenciones (20) y percepciones (40) | `POST/GET /api/v1/retenciones`, `GET .../{id}`, `GET .../{id}/xml`, `GET .../{id}/cdr`, `POST .../{id}/enviar` (igual en `/api/v1/percepciones`) |
| Reversión (RR) | `POST /api/v1/reversiones` (estado, XML, CDR y reenvío por `/api/v1/anulaciones/{id}/...`) |
| Liquidación de compra (04) — nuevo | `POST/GET /api/v1/liquidaciones-compra`, `GET .../{id}`, `GET .../{id}/xml`, `GET .../{id}/cdr`, `POST .../{id}/enviar`, `POST .../{id}/reenviar`, `POST .../{id}/reversion`, `GET/POST .../{id}/pagos`, `DELETE .../{id}/pagos/{pagoId}`, `GET .../reporte-retenciones?periodo=AAAA-MM` |
| Representación impresa | `GET .../{id}/pdf?formato=a4\|a5\|ticket-80\|ticket-58` en facturas, boletas, notas de crédito y débito, guías, retenciones, percepciones y liquidaciones de compra (se guarda un A4 al ser aceptado) |
| Alias `/api/sunat` | `GET/POST /api/sunat/facturas\|boletas`, `GET .../{id}/pdf`, `GET .../{id}/xml`, `POST .../{id}/anular`, `GET/POST /api/sunat/configuracion` |
| Consultas | `POST /api/v1/consultar-cdr`, `GET /api/v1/consultar-cpe` (Consulta Integrada de SUNAT con el `client_id`/`client_secret` de la empresa), `GET /api/v1/buscar-documento?tipo=6&numero=...` (clientes de la empresa y luego SUNAT/RENIEC vía `LOOKUP_BASE_URL`) |
| Panel | `GET /api/v1/panel` (`mes=AAAA-MM` o `desde`/`hasta`) y `/panel/indicadores`, `estado-sunat`, `cobranzas`, `ventas-mensuales`, `por-sucursal`, `por-moneda`, `clientes`, `productos`, `documentos-recientes`, `alertas` |
| Reportes | `GET /api/v1/reportes/registro-ventas`, `ventas-consolidado`, `notas`, `cobranzas`, `por-cliente`, `por-sucursal` (`fecha_desde`, `fecha_hasta`, filtros; `formato=pdf` para el PDF) |
| Exportación | `GET /api/v1/comprobantes/exportar-zip?fecha_desde=&fecha_hasta=&tipo=xml\|pdf\|ambos&documentos=facturas,boletas,notas-credito,notas-debito` |
| Panel de administración (interno) | `/api/admin/auth/**` (login + MFA TOTP, refresh, cuenta), `/api/admin/users`, `/api/admin/dashboard`, `/api/admin/tenants` (+ `/{id}`, `/{id}/estado`, `/{id}/plan`), `/api/admin/usage`, `/api/admin/certificates`, `/api/admin/rejections`, `/api/admin/jobs` (+ `/{id}/retry`, `/retry-all`), `/api/admin/health`, `/api/admin/audit`. JWT de administrador; JSON camelCase con el sobre `{succeeded, messages, data, totalRecords, errors}` (ver [Panel de administración](#panel-de-administración)) |
> Plan completo y decisiones: [`docs/ANALISIS_API_PRO.md`](docs/ANALISIS_API_PRO.md).

> **¿Primera vez?** Siga el [manual de instalación y funcionamiento](docs/INSTALACION.md): clonar,
> configurar, levantar con o sin Docker, emitir la primera factura y cómo funcionan la cola y las
> tareas programadas.

## Requisitos

Con Docker: solo **Docker Desktop** (con Docker Compose v2); no hace falta Java, Maven ni PostgreSQL en la máquina.
Sin Docker: JDK 21, Maven 3.9, PostgreSQL 18 y Redis 7 (pasos en [`docs/INSTALACION.md`](docs/INSTALACION.md#6-opción-b-levantar-sin-docker)).

## Puesta en marcha

```bash
cp .env.example .env
# Completa al menos:
#   APP_ENCRYPTION_KEY  →  openssl rand -base64 32
#   DB_PASSWORD         →  cualquier contraseña
#   DEMO_API_KEY / DEMO_API_SECRET (opcional; si faltan se generan en storage/demo-credentials.txt)
docker compose up -d --build
```

| Servicio | URL / puerto en el host |
|---|---|
| API | http://localhost:8081 |
| Swagger UI | http://localhost:8081/api/docs |
| PostgreSQL (`api_sunat_spring_boot`) | localhost:15081 |
| Redis | localhost:16081 |

Al arrancar con `APP_SEED=true` se cargan los catálogos (UNSPSC, ubigeo), los planes y una
**empresa demo en BETA** (RUC `20000000001`, usuario SOL `MODDATOS`) con sus series.

```bash
curl http://localhost:8081/api/health
curl http://localhost:8081/api/v1/sunat/estado \
  -H "X-Api-Key: $DEMO_API_KEY" -H "X-Api-Secret: $DEMO_API_SECRET"
```

Si las credenciales demo se generaron solas:

```bash
docker compose exec api cat /data/storage/demo-credentials.txt
```

## Desarrollo y pruebas (todo en Docker)

```bash
docker compose run --rm mvn test                 # pruebas unitarias + integración (Testcontainers)
docker compose run --rm mvn -q -DskipTests package
docker compose up -d --build api                 # reconstruir y reiniciar solo la API
docker compose logs -f api
docker compose down                              # detener (los datos quedan en volúmenes)
docker compose down -v                           # detener y borrar BD, Redis y almacenamiento
```

Las pruebas de integración levantan PostgreSQL y Redis con Testcontainers a través del socket de
Docker del host. Las de paridad comparan contra fixtures generados con el **validador y el
`json_encode` reales de Laravel** de API-PRO (`src/test/resources/fixtures`).

### Oráculo de API-PRO (regenerar fixtures de paridad)

`tools/oracle/oracle.php` ejecuta un escenario contra el código real de API-PRO (solo lectura: BD
SQLite en memoria, colas, correo y HTTP simulados, reloj fijo; lo que escribe queda en
`tools/oracle/storage`, ignorado por git) y guarda la respuesta de cada paso. Requiere PHP 8.3 y
una copia de API-PRO con sus dependencias (`API_PRO`, por defecto `C:/laragon/www/API-PRO`):

```bash
node tools/oracle/scenarios/fase7b.js
php tools/oracle/oracle.php src/test/resources/fixtures/oracle/scenario_fase7b.json src/test/resources/fixtures/oracle/fase7b.json
```

## Autenticación

Cada empresa (tenant) usa sus credenciales en cabeceras; nunca en la query string:

```http
X-Api-Key: {API_KEY}
X-Api-Secret: {API_SECRET}
```

Opcionales: `Idempotency-Key` (POST/PUT/PATCH/DELETE) y `X-Internal-Token` (sin rate limit).

## Panel de administración

Web interna (`Admin-api-sunat-Frontend`) con la que el equipo de la plataforma controla **todas** las empresas: tablero,
empresas (suspender / activar, cambiar plan), uso del mes, certificados, rechazos SUNAT, trabajos fallidos, salud,
administradores y auditoría. Contrato completo: `Docs/api-sunat-panel-admin-diseno.md` del proyecto.

- **Activación:** sin `ADMIN_JWT_SECRET` (≥ 32 bytes, `openssl rand -base64 48`) todo `/api/admin/**` responde 503.
- **Quién entra:** usuarios con `is_admin = true` y rol `superadmin`, `operador` o `lectura` (solo consultas).
- **Primer superadmin:** si no hay ningún administrador, al arrancar se crea con `ADMIN_INITIAL_EMAIL` /
  `ADMIN_INITIAL_PASSWORD` (la contraseña cumple la política: 8–64 caracteres con mayúscula, minúscula, número y
  especial). Después de crearlo, quite `ADMIN_INITIAL_PASSWORD` del `.env`.
- **MFA obligatorio:** contraseña + código TOTP (Google Authenticator, Microsoft Authenticator…). En el primer ingreso
  se configura con el QR y se entregan 10 códigos de recuperación de un solo uso. Un superadmin restablece el MFA o la
  contraseña de otro administrador desde el panel.
- **Acceso de servicio de la consola de plataforma** (`Admin-saas-Backend`, servidor a servidor, sin login ni MFA):
  cabeceras `X-Admin-Key` (= `ADMIN_SERVICE_KEY`, ≥ 32 bytes, distinta de `SAAS_BRIDGE_SECRET` y de `ADMIN_JWT_SECRET`) y
  `X-Admin-Actor` (el usuario de la consola que hace la acción; queda en `audit_logs` como `consola:<usuario>`).
  - Falla cerrada: sin clave → 503; IP fuera de `ADMIN_SERVICE_ALLOWED_IPS` (IP o CIDR, coma; vacía = nadie) → 403 sin mirar
    la clave; clave incorrecta → 401 (429 tras 10 intentos por minuto y por IP); sin actor → 400. No depende de
    `ADMIN_JWT_SECRET`.
  - Rol `servicio` (no se asigna a usuarios): empresas (lista y detalle), **estado** y **plan** de una empresa, uso,
    certificados, rechazos, trabajos (ver y reintentar), salud y planes. Nunca credenciales, comprobantes, usuarios,
    auditoría, configuración global ni `/api/admin/auth/**`. Un endpoint sin `@AdminPermission` queda cerrado.
  - La consola nunca expone la clave al navegador: solo la usa `Admin-saas-Backend`.
- **Suspensión por falta de pago** (`PUT /api/admin/tenants/{id}/estado` con `{ activo: false, motivo, tipo: "pago" }`; la consola
  solo puede usar `pago`, `tipo` vacío o `manual` es la suspensión del panel): la empresa **sigue activa**
  (`suspension_kind = 'pago'`, migración V11). La API rechaza con **402** solo la emisión nueva (`POST` de facturas,
  boletas, notas, guías, retenciones, percepciones y liquidaciones, incluidas `facturas/masivo` y `/api/sunat/facturas|boletas`;
  `EmissionRoutes`) y la emisión desde el panel. Siguen funcionando la consulta, la descarga de XML / CDR / PDF, reenviar o
  enviar lo ya registrado, resúmenes diarios, comunicaciones de baja y reversiones (obligaciones ante SUNAT). La suspensión
  manual del panel (`is_active = false`, 403 en todo) manda sobre la de pago: la consola no la pisa ni la reactiva.
  Reactivar (`activo: true`) limpia la de pago. Cada cambio queda en `audit_logs` (`admin.empresa.suspendida` / `activada`,
  con `tipo`) y la fila de la empresa trae `suspensionKind` y `billingSuspended`.
- **CORS:** solo los orígenes de `ADMIN_ALLOWED_ORIGINS` (coma), y solo para `/api/admin/**`.
- **Protecciones:** 5 fallos → bloqueo de 15 min; 10 intentos de login por minuto por IP; JWT de 30 min sin datos
  personales y refresh token rotativo de 7 días; cerrar sesión, cambiar la contraseña, restablecer el MFA o desactivar
  revocan todas las sesiones; cada acción queda en `audit_logs` con `actor_tipo = admin`.

## Portal de clientes

Web pública para que **el cliente final** (la empresa que emite con la API) consulte lo suyo: resumen y uso del mes,
comprobantes con sus XML / CDR / PDF y regenerar sus credenciales de API. Es solo lectura (la única escritura sobre una
empresa es regenerar credenciales). Contrato completo: `Docs/api-sunat-portal-clientes-diseno.md` del proyecto. Código en
`modules/portal` (más `modules/admin/portal` para la consola); migración `V12__portal_clientes.sql`.

- **Activación:** sin `PORTAL_JWT_SECRET` (≥ 32 bytes, `openssl rand -base64 48`, **distinta** de `ADMIN_JWT_SECRET`) todo
  `/api/portal/**` responde 503, también el login. Si es igual a la del panel el portal queda apagado.
- **Alta por invitación desde la consola** (no hay registro propio): `POST /api/admin/portal/invitations`
  (`{email, name, tenantIds[], resetMfa}`) devuelve un token de 72 h **una sola vez**; en la base solo queda su SHA-256. La
  persona lo usa en `POST /api/portal/auth/activate` para fijar su contraseña (política 8–64 con mayúscula, minúscula,
  número y especial; no devuelve tokens). Invitar de nuevo a un correo existente es idempotente: reemplaza empresas y
  contraseña, reactiva la cuenta, invalida las invitaciones anteriores y cierra sus sesiones (`resetMfa: true` borra
  también el autenticador).
- **MFA obligatorio** (contraseña + TOTP, 10 códigos de recuperación), mismo flujo que el panel pero con JWT propio
  (`iss = api-sunat-portal`, `aud = portal`, sin datos personales): un token del panel no entra al portal ni uno del portal
  al panel, aunque alguien configurara la misma clave en los dos.
- **Aislamiento:** cada persona tiene sus empresas (`portal_user_tenants`); una empresa que no es suya responde **404** con el
  mismo mensaje que una que no existe, antes de pedir nada más.
- **Credenciales:** `POST /api/portal/tenants/{id}/credenciales/regenerar` exige contraseña **y** código TOTP vigente en el
  mismo pedido; 3 por hora y por empresa (Redis; sin Redis no regenera). El `api_secret` se muestra una sola vez, con
  `no-store`. Nunca se devuelve SOL, certificado, `client_secret` ni el secreto guardado.
- **Protecciones:** 5 fallos → bloqueo de 15 min; 10 intentos por minuto y por IP en `/api/portal/auth/**` (incluida la
  activación); JWT de 30 min y refresh rotativo de 7 días; cerrar sesión, cambiar la contraseña, deshabilitar o reinvitar
  revocan todas las sesiones; detalle y descargas quedan en `audit_logs` con `actor_tipo = portal`.
- **CORS:** solo los orígenes de `PORTAL_ALLOWED_ORIGINS` (coma, sin comodín) y solo para `/api/portal/**`.
- **Consola** (`/api/admin/portal/**`, permiso `Admin.Portal.Manage`: la clave de servicio y el superadmin):
  `POST invitations`, `GET users` (`tenantId`, `textFilter`, paginado) y `PUT users/{id}/estado` (`{active}`).

## Variables de entorno

Todas están documentadas en [`.env.example`](.env.example): aplicación, BD, Redis,
almacenamiento, seguridad HTTP, endpoints SUNAT (beta y producción), cola, planificador y alertas,
consulta RUC/DNI, correo, logs, tasas de liquidación de compra, PDF, panel de administración
(`ADMIN_JWT_SECRET`, `ADMIN_ALLOWED_ORIGINS`, `ADMIN_INITIAL_EMAIL`, `ADMIN_INITIAL_PASSWORD`) y portal de clientes
(`PORTAL_JWT_SECRET`, `PORTAL_ALLOWED_ORIGINS`).
Ningún secreto vive en el código ni en el repositorio.

**Rutas de autoservicio (apagadas por defecto).** Con la consola de plataforma, las empresas y sus planes se gestionan
desde la consola (`/api/admin`) o el bridge SaaS, así que dos grupos de `/api/v1` vienen apagados:

| Variable | Por defecto | Rutas que enciende |
|---|---|---|
| `REGISTRO_PUBLICO_ENABLED` | `false` | `POST /api/v1/registro` |
| `SUSCRIPCION_AUTOSERVICIO_ENABLED` | `false` | `POST /api/v1/suscripcion`, `PUT /api/v1/suscripcion/cambiar-plan`, `PUT /api/v1/suscripcion/cancelar` |

Apagadas responden **404** con el sobre de la API (`{"estado":"error","mensaje":"…"}`, en español), antes de la
autenticación y del límite de solicitudes, y no crean ni cambian nada. `GET /api/v1/suscripcion`, `/pagos` y `/uso`
(y `GET /api/v1/planes`) siguen activas: solo leen y no dependen de Culqi. El panel de administración no usa estas rutas
HTTP, así que no se afecta. Quien dependa del registro público o del autoservicio (una página de alta propia) debe encender
la variable correspondiente en su `.env`.

Las guías de remisión usan la API REST GRE con OAuth2: el `client_id`/`client_secret` se toma de
la empresa (`PUT /api/v1/empresa`) o, si no lo tiene, de `SUNAT_BETA_GRE_CLIENT_ID` /
`SUNAT_BETA_GRE_CLIENT_SECRET` (`SUNAT_PROD_*` en producción). En beta los endpoints GRE apuntan al
sandbox de Nubefact, que solo autoriza su RUC de prueba.

## Probar en BETA de SUNAT

BETA acepta cualquier certificado (incluso autofirmado) y el usuario SOL `MODDATOS`/`MODDATOS`.

1. **Registra una empresa** (devuelve su `api_key` y `api_secret` una sola vez; guárdalos):

   ```bash
   curl -X POST http://localhost:8081/api/v1/registro -H "Accept: application/json" \
     -F ruc=20123456789 -F "razon_social=MI EMPRESA S.A.C." -F "direccion=AV. PRINCIPAL 123" \
     -F ubigeo=150101 -F sol_user=MODDATOS -F sol_pass=MODDATOS -F entorno=beta \
     -F "certificado=@mi-certificado.pfx" -F contrasena_certificado=...
   ```

   O usa la empresa demo que crea `APP_SEED=true`. Para un certificado de prueba:
   `openssl req -x509 -newkey rsa:2048 -nodes -days 365 -subj "/CN=PRUEBA" -keyout k.pem -out c.pem && cat k.pem c.pem > cert-beta.pem`.

2. **Emite** (la respuesta llega al instante con estado `pendiente`; el envío lo hace la cola):

   ```bash
   curl -X POST http://localhost:8081/api/v1/facturas -H "Content-Type: application/json" \
     -H "X-Api-Key: $API_KEY" -H "X-Api-Secret: $API_SECRET" -d @factura.json
   ```

3. **Consulta el resultado** a los pocos segundos: `GET /api/v1/facturas/{id}` (`sunat.estado`:
   `aceptado`, `observado` o `rechazado`, con el código y la descripción del CDR).

4. **Descarga** `GET .../{id}/xml`, `GET .../{id}/cdr` y `GET .../{id}/pdf?formato=a4`.

Las boletas se declaran en el resumen diario (`POST /api/v1/resumenes`) o una por una con
`POST /api/v1/boletas/{id}/enviar`. Las guías de remisión necesitan credenciales GRE; en BETA el
sandbox de Nubefact solo autoriza su RUC de prueba. La colección Postman tiene un ejemplo de
cada comprobante.

## Postman

Hay dos colecciones; [`docs/postman/README.md`](docs/postman/README.md) explica cuándo usar cada una.
La de flujo completo ([`API-SUNAT-Spring-Boot.flujo-completo.postman_collection.json`](docs/postman/API-SUNAT-Spring-Boot.flujo-completo.postman_collection.json))
se ejecuta de principio a fin y deja cada comprobante aceptado en BETA. La de referencia:


[`docs/postman/API-SUNAT-Spring-Boot.postman_collection.json`](docs/postman/API-SUNAT-Spring-Boot.postman_collection.json):
la colección de API-PRO con las mismas peticiones y ejemplos (304), sin SIRE, cotizaciones ni notas
de venta, más liquidación de compra, alias `/api/sunat` y bridge. Importa la colección y completa
las variables `api_key` y `api_secret` (y `base_url` si no usas `http://localhost:8081/api/v1`).
Desde la terminal:

```bash
docker run --rm -v "$PWD/docs/postman:/etc/newman" postman/newman:6-alpine run \
  API-SUNAT-Spring-Boot.postman_collection.json --folder "00. Sistema" \
  --env-var base_url=http://host.docker.internal:8081/api/v1 --env-var host=http://host.docker.internal:8081 \
  --env-var api_key=... --env-var api_secret=...
```

## Colas y tareas programadas

- **Cola** (Redis): envíos a SUNAT, consultas de ticket, guías y webhooks se procesan en segundo
  plano con reintentos y espera creciente. Un trabajo que agota sus intentos queda en la tabla
  `failed_jobs` y genera una alerta.
- **Planificador**: corre dentro de la API (`SCHEDULER_ENABLED=true`) con los mismos horarios que
  API-PRO (UTC): reintento de comprobantes atascados, consulta de resúmenes, renovación de
  suscripciones, pruebas gratis, uso mensual, certificados, jobs fallidos, salud y purga de logs.
  Tabla completa en [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md#tareas-programadas).
- **Alertas**: con `OPS_WEBHOOK_URL` (Slack, Discord o genérico) se avisan los certificados por
  vencer, los jobs fallidos y las caídas de BD/Redis/SUNAT; sin URL solo quedan en el log.

### Comandos de operación

Equivalen a `php artisan …` de API-PRO. Arrancan sin servidor web ni worker, ejecutan la tarea e
imprimen el resultado:

```bash
docker compose run --rm api ops                                   # lista de tareas
docker compose run --rm api ops ops:salud
docker compose run --rm api ops certificados:verificar --tenant=5
docker compose run --rm api ops sunat:reintentar-pendientes --minutos=15 --dias=7
docker compose run --rm api ops summaries:poll-pending --limit=100
docker compose run --rm api ops ops:jobs-fallidos
docker compose run --rm api ops jobs-fallidos:reintentar          # todos, o --id=N
docker compose run --rm api ops logs:purge --days=90
docker compose run --rm api ops suscripciones:renovar | suscripciones:vencer-pruebas | uso:reiniciar
```

Fuera de Docker: `java -jar api-sunat.jar ops <tarea>`.

## Varias instancias

La API puede correr en varias réplicas detrás de un balanceador, con la misma BD, el mismo Redis
y el **mismo volumen de almacenamiento** (XML, CDR, PDF y certificados):

- Cada trabajo de la cola lo toma una sola réplica (reserva atómica en Redis).
- Cada tarea programada corre en una sola réplica (candado en Redis por tarea).
- Correlativos con bloqueo de fila en PostgreSQL; rate limit, idempotencia y caché en Redis.

Prueba local con una segunda réplica (sin puerto publicado):

```bash
docker compose up -d
docker compose run -d --name api-2 api
docker logs api-2 | grep scheduler.task
```

Si prefieres que solo una réplica ejecute el planificador, usa `SCHEDULER_ENABLED=false` en las demás.

## Respaldos

API-PRO respaldaba la BD con un comando propio; aquí basta `pg_dump` del contenedor y una copia del
volumen de almacenamiento:

```bash
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB"' > respaldo-$(date +%F).dump
docker compose exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean' < respaldo-AAAA-MM-DD.dump
docker run --rm -v api-sunat-spring-boot_storage:/s -v "$PWD":/b alpine tar czf /b/storage-$(date +%F).tgz -C /s .
```

Los respaldos contienen certificados y secretos cifrados: guárdalos con la misma protección que
`APP_ENCRYPTION_KEY` (que va aparte; sin ella no se descifran).

## Documentación

- [`docs/INSTALACION.md`](docs/INSTALACION.md): manual de instalación (con y sin Docker) y funcionamiento de la cola y las tareas.
- [`docs/ANALISIS_API_PRO.md`](docs/ANALISIS_API_PRO.md): análisis de API-PRO (Fase 0) y decisiones.
- [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md): capas, paquetes, flujo de emisión, varias instancias y tareas programadas.
- [`docs/DIFERENCIAS.md`](docs/DIFERENCIAS.md): diferencias con API-PRO.
- [`docs/AUDITORIA.md`](docs/AUDITORIA.md): auditoría final (criterios de aceptación, seguridad y pendientes).
- [`docs/LIQUIDACION_COMPRA.md`](docs/LIQUIDACION_COMPRA.md): liquidación de compra (tipo 04).
- [`docs/postman/`](docs/postman/): colección Postman.
- Swagger UI en http://localhost:8081/api/docs (OpenAPI en `/api/docs/openapi.json`).
- `docs/analisis-fase0/`: anexos detallados del análisis.
