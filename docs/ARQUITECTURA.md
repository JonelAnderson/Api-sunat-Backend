# Arquitectura

API REST multiempresa de facturación electrónica SUNAT, equivalente a API-PRO, en Java 21 +
Spring Boot 4.2. Se desarrolla, ejecuta y prueba con Docker (API + PostgreSQL 18 + Redis 7.4).

## Capas

```text
                 Cliente (ERP, POS, e-commerce)
                              │  HTTP JSON · X-Api-Key + X-Api-Secret
                              ▼
┌────────────────────────── Presentación ───────────────────────────┐
│ Filtros servlet: CachedBodyFilter → ApiPipelineFilter (post-proceso)│
│ Spring Security: cabeceras de seguridad, CORS, sin sesión          │
│ Interceptores MVC (solo rutas existentes, orden de Laravel):       │
│   TenantAuth → Idempotency → RateLimit → ApiLog/UsageHeaders       │
│ Controladores delgados (modules/*/…Controller)                     │
├────────────────────────── Aplicación ─────────────────────────────┤
│ Endpoints/servicios por módulo · Validator (réplica del de Laravel)│
│ Trabajos en cola (Send*Job, TicketJobs, GuideJobs, WebhookJob)     │
│ Planificador (ops.Scheduler) y comandos de operación (ops.OpsCli)  │
├──────────────────────────── Dominio ──────────────────────────────┤
│ Entidades/repositorios · cálculo de totales · máquina de estados   │
├──────────────────────── Infraestructura ──────────────────────────┤
│ PostgreSQL (Flyway V1–V5) · Redis (colas, rate limit, caché de     │
│ tenant, circuit breaker, token GRE, candados del planificador)     │
│ Almacenamiento en disco (STORAGE_PATH): XML, CDR, PDF, certificados│
│ Núcleo SUNAT aislado (sunat/): builder → xml → sign → ws/gre → CDR │
│ · pdf (Thymeleaf + Flying Saucer + QR ZXing)                       │
└───────────────────────────────────────────────────────────────────┘
          │ SOAP (sendBill/sendSummary/getStatus)   │ REST OAuth2 (GRE)
          ▼                                        ▼
      SUNAT billService · otros CPE · consulta CDR     API GRE SUNAT / Nubefact (beta)
```

## Paquetes

| Paquete | Contenido |
|---|---|
| `config` | Propiedades (`app.*`), Spring Security, interceptores, conversor JSON, tasas de liquidación |
| `security` | Pila HTTP de API-PRO: autenticación del tenant, idempotencia, rate limit, log y cabeceras de uso |
| `shared.api` | Envoltorio `{estado, mensaje, datos}`, `ApiException`, manejador global de errores |
| `shared.json` / `shared.php` | Serialización idéntica a `json_encode` y utilidades con semántica de PHP |
| `shared.validation` | Validador con la semántica de Laravel 12 (reglas, comodines, mensajes en español) |
| `shared.queue` | `JobQueue`: cola en Redis (ZSET retrasados/reservados), reintentos con backoff, `failed_jobs` |
| `shared.crypto` · `shared.storage` · `shared.time` · `shared.http` | Cifrado AES-256-GCM, disco, convención de fechas, lectura de la entrada |
| `modules.document` | Facturas, boletas, notas de crédito/débito, alias `/api/sunat/*`, envío (`SendDocumentJob`) |
| `modules.ticket` | Resumen diario (RC), comunicación de baja (RA), reversión (RR), consulta de tickets |
| `modules.guide` | Guías de remisión remitente (09) y transportista (31) por la API REST GRE |
| `modules.retper` | Retenciones (20) y percepciones (40) por el servicio "otros CPE" |
| `modules.settlement` | Liquidación de compra (04): cálculo, emisión, pagos, reversión, reporte |
| `modules.pdf` | Representación impresa A4/A5/ticket 80/58 mm con QR, caché y guardado al aceptar |
| `modules.ops` | Tareas programadas, comandos de operación, alertas operativas, verificación de certificados |
| `modules.report` | Panel (`/panel/*`), reportes en JSON y PDF (`/reportes/*`) y exportación ZIP de XML/PDF |
| `modules.consulta` | Consulta de CDR, Consulta Integrada de CPE (REST OAuth2) y búsqueda RUC/DNI (clientes + proveedor externo) |
| `modules.admin` | Panel de administración y acceso de servicio de la consola (`/api/admin/**`): JWT propio, MFA, roles y permisos por endpoint; `modules.admin.portal` administra el portal de clientes desde la consola |
| `modules.portal` | Portal de clientes (`/api/portal/**`, solo lectura): `security` (JWT `iss=api-sunat-portal` / `aud=portal`, interceptor, límites en Redis), `user` (invitación, activación, login + MFA, sesiones), `tenant` (resumen, uso, comprobantes y regenerar credenciales con aislamiento por membresía). Reutiliza los servicios del panel y de `/api/v1`, sin consultas propias de comprobantes |
| `modules.tenant` · `plan` · `serie` · `sucursal` · `client` · `catalog` · `tax` · `certificate` · `webhook` · `health` · `apilog` · `audit` · `mail` · `idempotency` | Resto de funcionalidades de API-PRO |
| `sunat` | Núcleo aislado: `builder` (entrada → modelo), `xml` (UBL 2.1), `sign` (XMLDSig), `ws` (SOAP), `gre` (REST), `pdf` |
| `seed` | Catálogos, planes y empresa demo |

## Flujo de una petición protegida

1. `CachedBodyFilter` guarda el cuerpo crudo (huella de idempotencia y log).
2. `ApiPipelineFilter` mantiene la respuesta en memoria hasta el final.
3. Spring Security añade cabeceras de seguridad y resuelve CORS.
4. `DispatcherServlet` busca la ruta: si no existe → 404, si el método no aplica → 405.
5. Interceptores: autenticación (401/403), idempotencia (replay/409/422), rate limit (429),
   marca de log y cabeceras de uso.
6. Controlador → servicio → respuesta con el envoltorio de API-PRO.
7. Post-proceso: cabeceras `X-Usage-*`/`X-Plan`, registro asíncrono en `api_logs`,
   guardado de la respuesta idempotente.

## Flujo de emisión

```text
POST /api/v1/facturas
  → validación (422) → cupo del plan → cálculo de totales
  → correlativo (SELECT … FOR UPDATE sobre series) → guardar (estado pendiente) → COMMIT
  → encolar SendDocumentJob (solo tras el commit)                         ← respuesta 201

Worker (cualquier instancia):
  modelo UBL → XML → firma XMLDSig (certificado del tenant) → ZIP
  → sendBill SOAP con WS-Security (usuario SOL) ──timeout/5xx──→ reintento con backoff
  → CDR: aceptado | observado | rechazado → guarda XML/CDR (y PDF si PDF_AUTO_GENERATE)
  → webhook al cliente (WebhookJob, firmado HMAC)
  → intentos agotados → failed_jobs + alerta operativa
```

Variantes del mismo flujo:

| Comprobante | Envío | Respuesta |
|---|---|---|
| Factura, notas de factura, liquidación de compra | `sendBill` (billService FE) | CDR síncrono |
| Boleta y sus notas | Resumen diario `sendSummary` (o `sendBill` individual) | ticket → `getStatus` |
| Baja (RA) | `sendSummary` | ticket → `getStatus` → documentos `anulado` |
| Retención, percepción, reversión (RR) | servicio "otros CPE" | CDR síncrono / ticket |
| Guías 09 y 31 | REST GRE: token OAuth2 (caché en Redis) → envío → `numTicket` | consulta del ticket |

## Varias instancias

La API no guarda estado en memoria que deba compartirse; todo vive en PostgreSQL, Redis o el
volumen de almacenamiento:

- **Cola**: cada instancia ejecuta workers; un trabajo se reserva con un script Lua atómico
  (`delayed` → `reserved`), así que lo procesa una sola. Si la instancia muere, la reserva vence
  y otra lo recupera.
- **Planificador**: todas las instancias tienen el cron activo, pero cada tarea toma un candado
  `scheduler:<tarea>` en Redis (`SET NX` con expiración menor al intervalo): corre en una sola
  instancia y nunca se solapa consigo misma (`onOneServer` + `withoutOverlapping` de Laravel).
- **Correlativos**: bloqueo de fila en PostgreSQL; dos instancias nunca repiten número.
- **Rate limit, idempotencia, circuit breaker y token GRE**: en Redis.
- **Archivos**: el volumen `storage` debe ser compartido entre instancias (NFS, EFS, volumen
  Docker común).

Ejemplo local: `docker compose up -d --scale api=2` requiere quitar el puerto fijo y poner un
balanceador delante; ver README.

## Tareas programadas

`modules.ops.Scheduler` reproduce `routes/console.php` de API-PRO (horarios en UTC):

| Tarea | Horario | Qué hace |
|---|---|---|
| `sunat:reintentar-pendientes` | cada 15 min | Reencola facturas/boletas en `enviado` hace más de 15 min (últimos 7 días) |
| `summaries:poll-pending` | cada 5 min | Consulta tickets de resúmenes sin estado final |
| `ops:salud` | cada 10 min | BD, Redis, disco, almacenamiento, circuit breaker SUNAT |
| `ops:jobs-fallidos` | cada hora | Alerta si hay trabajos en `failed_jobs` |
| `certificados:verificar` | 08:00 | Certificados vencidos, por vencer o ilegibles |
| `suscripciones:renovar` | 06:00 | Renueva planes gratis; los de pago pasan a `past_due` |
| `suscripciones:vencer-pruebas` | 07:00 | Aviso a 3 días y paso a Free |
| `uso:reiniciar` | día 1, 00:05 | Reinicia contadores mensuales |
| `logs:purge` | domingo 03:00 | Borra `api_logs` antiguos (`API_LOGS_RETENTION_DAYS`) |

Cada tarea también se ejecuta a mano: `docker compose run --rm api ops <tarea>`.

## Sincronización de datos maestros desde el ERP

El ERP (u otro cliente con `X-Api-Key` + `X-Api-Secret`) mantiene al día en la API la empresa, sus sucursales, sus series y su logo. Sin
endpoints nuevos: es un upsert contra los de siempre, y es idempotente (repetir la misma sincronización no cambia nada).

| Dato | Endpoint | Clave natural / cómo reconciliar |
| --- | --- | --- |
| Empresa | `PUT /api/v1/empresa` | Solo se aplican los campos enviados y no nulos; repetir no cambia nada. El RUC no se edita |
| Logo | `POST /api/v1/empresa/logo` (multipart `logo`: jpg, jpeg, png o webp, máx. 2 MB) | Reemplaza el anterior |
| Sucursal | `GET/POST /api/v1/sucursales`, `PUT /api/v1/sucursales/{id}` | `cod_local` (4 dígitos, único por empresa): `GET` (sin paginar) → buscar por `cod_local` → `PUT` por id o `POST`. Un `POST` repetido responde 409 |
| Serie | `GET/POST /api/v1/series`, `PUT /api/v1/series/{id}` | `tipo` + `serie`: `GET /series?include_inactive=1` (el filtro `tipo` recibe el **nombre**: `factura`, `boleta`…) → buscar → `PUT` o `POST` (una serie desactivada se reactiva con el `POST`) |

**Piso del correlativo (`correlativo_minimo`).** Para que el ERP, que numera los comprobantes, no choque con la API: `PUT /series/{id}` y
`POST /series` aceptan `correlativo_minimo`. El contador queda en el mayor entre el actual, el piso pedido y el último comprobante emitido
de esa serie (`CorrelativoEmitido.piso`): **nunca baja** y pedir un valor menor no es un error (a diferencia de `correlativo`, que fija el
valor exacto y responde 422 si baja de lo emitido). No se puede mandar `correlativo` y `correlativo_minimo` juntos (422). La fila de la
serie se bloquea durante el cambio, igual que en la emisión. La emisión con `correlativo` manual (`CreateSaleDocumentAction`) también solo
sube el contador, nunca lo baja.

No hay límite de plan que se aplique al crear sucursales o series (el plan solo informa el uso de sucursales en el endpoint de uso de la suscripción).
Los errores de validación son 422 con `errores` por campo (formato `estado` / `mensaje` / `errores`, en español).
