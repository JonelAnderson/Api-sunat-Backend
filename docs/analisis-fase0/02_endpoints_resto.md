# 02 — Endpoints "resto" de API-PRO (todo salvo facturas/boletas/NC/ND/pagos y NV/cotizaciones)

Fase 0 — Análisis. Fuente: `C:\laragon\www\API-PRO` (solo lectura). Rutas relativas a esa raíz.
Todas las referencias `archivo:línea` son del código actual.

> Convención: "ApiResponse" = trait `app/Http/Traits/ApiResponse.php`:
> - `success($datos, $mensaje='OK', $codigo=200, $meta=null)` → `{"estado":"exito","mensaje":...,"datos":...[, "meta":...]}` (l.21-34)
> - `created($datos, $mensaje='Creado exitosamente')` → igual con HTTP 201 (l.36-39)
> - `error($mensaje='Error', $codigo=400, $errores=null)` → `{"estado":"error","mensaje":...[, "errores":...]}`; `errores` solo si es *truthy* (l.41-53)
> - `errorAccionable(...)` → añade `codigo_error` y `siguiente_accion` (l.55-72)
>
> IMPORTANTE para la migración: varios endpoints NO usan el trait y construyen el JSON a mano (sin `mensaje`, con `meta` o `paginacion` en raíz, etc.). Se detalla caso por caso.

---

## 0. Infraestructura transversal (necesaria para replicar el contrato)

### 0.1 Prefijos y grupos (`routes/api.php`)
- Laravel monta `routes/api.php` bajo `/api` (bootstrap/app.php:22). Todas las URLs de abajo empiezan por `/api`.
- Grupo público `/api/v1` con `throttle:api` (api.php:37-42): `registro`, `planes`, `credenciales/recuperar`, `credenciales/recuperar/verificar`.
- Grupo protegido `/api/v1` con middleware **en este orden**: `resolve.tenant`, `idempotency`, `throttle:api`, `log.api`, `usage.headers` (api.php:45).
- `GET /api/health` solo `throttle:api` (api.php:34). Además Laravel expone `GET /up` (health nativo, bootstrap/app.php:24).
- `bridge/*` (api.php:385-387): **sin ningún middleware** (ni throttle). Autenticación manual con `X-Bridge-Key`.
- Alias `/api/sunat/*` (api.php:391-409): solo `resolve.tenant` + `throttle:api` (sin idempotency, log, usage headers, license ni check.limit).
- `debug/lookup` (api.php:322-381): solo existe si `APP_DEBUG=true`; **sin middleware alguno**.

### 0.2 Middlewares globales
- `HandleCors` y `ForceJsonAccept` antepuestos (bootstrap/app.php:67-70). `ForceJsonAccept` fuerza `Accept: application/json` en `api/*` (app/Http/Middleware/ForceJsonAccept.php:18-25).
- `SecurityHeaders` añadido al final (bootstrap/app.php:73) — cabeceras de seguridad en todas las respuestas.
- `trustProxies('*')` con X-Forwarded-* (bootstrap/app.php:31-38) → `url()` genera URLs con el host/scheme del proxy (afecta a `consulta_estado`, `archivos.*`).

### 0.3 Manejo global de excepciones (formato de error) — bootstrap/app.php:82-168
Para cualquier ruta `api/*`:
| Excepción | HTTP | Cuerpo |
|---|---|---|
| `ValidationException` (incluye `$request->validate()` inline) | 422 | `{"estado":"error","mensaje":"Error de validación","errores":{campo:[msg,...]}}` |
| `ModelNotFoundException` / `NotFoundHttpException` (ruta inexistente, `findOrFail`) | 404 | `{"estado":"error","mensaje":"Recurso no encontrado."}` |
| `AuthenticationException` | 401 | `{"estado":"error","mensaje":"No autenticado."}` |
| `AuthorizationException` | 403 | `{"estado":"error","mensaje":"No autorizado."}` |
| `MethodNotAllowedHttpException` | 405 | `{"estado":"error","mensaje":"Método HTTP no permitido para esta ruta."}` |
| `ThrottleRequestsException` | 429 | `{"estado":"error","mensaje":"Demasiadas solicitudes. Intenta de nuevo más tarde."}` |
| `HttpException` genérico (abort) | su código | `{"estado":"error","mensaje": <msg> o "Error."}` |
| Cualquier otro `Throwable` | 500 | `{"estado":"error","mensaje":"Error interno del servidor."}` + si `APP_DEBUG`: `excepcion`, `detalle`, `archivo` |

Los FormRequests de `app/Http/Requests/Api/V1/*` sobreescriben `failedValidation` con el **mismo** cuerpo 422 (`estado/mensaje/errores`). Mensajes de validación en español (`config/app.php:81` locale `es`, `lang/es`).

Nota: parámetros de ruta tipados `int $id` con valor no numérico (p.ej. `/guias-remision/abc`) provocan `TypeError` → **500** "Error interno del servidor." (no 404).

### 0.4 `resolve.tenant` (app/Http/Middleware/ResolveTenant.php)
- Lee **solo cabeceras** `X-Api-Key` y `X-Api-Secret` (l.18-19; nunca query string).
- Falta alguna → 401 `{"estado":"error","mensaje":"Las cabeceras X-Api-Key y X-Api-Secret son requeridas."}` (l.21-26).
- Tenant buscado por `api_key` con caché 600 s clave `tenant:key:{apiKey}` (l.28-30). No existe → 401 "Credenciales de API inválidas." (l.32-37).
- `api_secret` está cifrado en BD (cast `encrypted`, app/Models/Tenant.php:92) y se compara **en claro** con `hash_equals` (l.44). Si no se puede descifrar → 401 con mensaje "No se pudo validar el API Secret de la empresa (credencial ilegible). Regenérela desde el panel: Configuración → Credenciales → Regenerar." (l.45-50). Secret distinto → 401 "Credenciales de API inválidas." (l.52-57).
- `is_active=false` → 403 "Empresa desactivada. Contacte al administrador." (l.59-64).
- Inyecta el modelo en el input: `$request->merge(['tenant'=>$tenant])` (l.66) → `request->all()` contiene la clave `tenant`.

### 0.5 `idempotency` (app/Http/Middleware/EnsureIdempotency.php)
- Solo POST/PUT/PATCH/DELETE con cabecera `Idempotency-Key` y tenant resuelto (l.113). Key truncada a 255 (l.117). Hash = sha256(`METHOD|path|rawBody`) (l.118).
- Misma key + hash distinto → 422 `{"estado":"error","mensaje":"La Idempotency-Key ya se usó con un cuerpo distinto."}` (l.126-131).
- Ya completada → devuelve el cuerpo/código guardado, `Content-Type: application/json`, cabecera `Idempotency-Replayed: true` (l.132-136).
- En curso (o colisión de insert) → 409 "Ya hay una solicitud en proceso con esa Idempotency-Key." (l.138-141, 155-160).
- Respuestas <500 se guardan y se añade `Idempotency-Replayed: false`; ≥500 liberan la key (l.162-175). Tabla `idempotency_keys(tenant_id, idempotency_key, request_hash, response_status, response_body, created_at)`.

### 0.6 `throttle:api` (app/Providers/AppServiceProvider.php:175-191)
- Bypass si `X-Internal-Token` == `config('services.internal_token')` (no vacío).
- Límite/min por `tenant.plan`: free 30, pro 120, business 300, otro 30. Key = tenant id o IP (rutas públicas/bridge-less → IP).
- Exceso → 429 (formato global). Cabeceras estándar `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `Retry-After`.

### 0.7 `log.api` (app/Http/Middleware/LogApiRequest.php)
- Tras la respuesta encola (afterResponse) un `ApiLog` con endpoint, método, request (sin `tenant`, ocultando `contrasena_certificado`, `sol_pass`, `password`, `api_secret` → `***`; ficheros → metadata), response (si >4096 bytes solo `estado`,`mensaje`,`_truncated`,`_size`), status, ip, UA, duración. Ojo: NO enmascara `client_secret` ni el `api_secret` de la *respuesta* (regenerar credenciales queda en el log).

### 0.8 `usage.headers` (app/Http/Middleware/UsageWarningHeader.php:195-229)
- `X-Usage-Documents: {documents_this_month}/{limit}` si límite ≠ -1; si ≥80 %: `X-Usage-Warning: approaching_limit`, `X-Usage-Remaining`.
- `X-Usage-AI: {ai_messages_this_month}/{limit}` si ≠ -1.
- `X-Plan: {slug}` siempre.

### 0.9 `license` (app/Http/Middleware/CheckLicense.php)
- Tenant `environment==='beta'` → pasa siempre. Si no, `LicenseClient::check()`; permitido en gracia offline → cabecera `X-License-Warning`. Denegado → 403 `{"estado":"error","mensaje":<msg>,"codigo_error":<code>}`.

### 0.10 `check.limit:{categoria}` (app/Http/Middleware/CheckDocumentLimit.php)
- Delega en `EmissionPolicyService::evaluate` (prioridad global → beta → empresa → plan).
- Suscripción vencida → 403 `{"estado":"error","mensaje":"Tu suscripción venció. Renueva el plan para seguir emitiendo comprobantes.","codigo_error":<CODE_SUBSCRIPTION_EXPIRED>,"plan_actual":slug,"recurso":"documents_month"|"internal_documents_month"}` (l.46-54).
- Cupo → 429 `{"estado":"error","mensaje":"Has alcanzado el limite de documentos SUNAT (n/limite por mes).[upsell]","codigo_error":<CODE_LIMIT_REACHED>,"plan_actual","recurso","actual","limite","mejora_plan":{slug,price,currency,trial_days}|null}` (l.57-79).
- Conteo `sunat` = invoices+boletas+credit_notes+debit_notes+dispatch_guides creados en el mes (app/Services/Plan/EmissionPolicyService.php:105-131). `internal` = `internal_documents` (l.94-103) → **solo NV/cotizaciones (fuera de alcance)**.

### 0.11 `sire.enabled` (app/Http/Middleware/EnsureSireEnabled.php)
- Sin tenant → 401 "Empresa no resuelta." ; `sire_enabled=false` → 403 `{"estado":"error","mensaje":"SIRE no está activado para esta empresa. Llama primero a POST /v1/sire/activar con credenciales válidas.","codigo_error":"sire_no_activado"}`.

### 0.12 Zona horaria
`config/app.php:68` → `timezone = UTC`. Muchos cálculos usan `now()` (UTC) salvo resúmenes/anulaciones que usan explícitamente `America/Lima` en algunos puntos. Ver observaciones.

---

## 1. Guías de remisión (GRR 09 / GRT 31)

Controlador: `app/Http/Controllers/Api/V1/DispatchGuideController.php`. Recurso: `app/Http/Resources/Api/V1/DispatchGuideResource.php`. FormRequest: `app/Http/Requests/Api/V1/StoreDispatchGuideRequest.php`. Acción: `app/Actions/Documents/CreateDispatchGuideAction.php`, `UpdateDispatchGuideAction.php`. Job: `app/Jobs/SendDispatchGuideToSunat.php` (+ `CheckTicketStatus`).

| Método | URL | Middleware extra | Acción |
|---|---|---|---|
| POST | /api/v1/guias-remision | license, check.limit:sunat | store (api.php:134) |
| GET | /api/v1/guias-remision | — | index (135) |
| GET | /api/v1/guias-remision/{id} | — | show (136) |
| PUT | /api/v1/guias-remision/{id} | — | update (137) |
| GET | /api/v1/guias-remision/{id}/pdf | — | pdf (138) |
| GET | /api/v1/guias-remision/{id}/xml | — | xml (139) |
| GET | /api/v1/guias-remision/{id}/cdr | — | cdr (140) |
| GET | /api/v1/guias-remision/{id}/estado | — | checkStatus (141) |
| POST | /api/v1/guias-remision/{id}/enviar | — | enviar (142) |
| POST | /api/v1/guias-remision-transportista | license, check.limit:sunat | storeTransportista (145) |

### 1.1 Request (StoreDispatchGuideRequest) — usado por store, storeTransportista **y update**
`prepareForValidation` (l.27-59):
1. Si el path contiene `guias-remision-transportista` → `tipo_documento='31'`.
2. `doc_relacionado` como objeto único (tiene `tipo_codigo`) → se envuelve en array.
3. `conductor` como **lista** → se mueve a `conductores` (si `conductores` vacío) y se elimina `conductor`.
4. Alias `fecha_de_entrega_al_transportista` y `fecha_entrega_al_transportista` → `fecha_entrega_transportista` (si éste no viene).

Reglas (l.61-198) — PHP con claves duplicadas: la **última** gana (marcado ⚠):
| Campo | Regla efectiva |
|---|---|
| tipo_documento | sometimes, string, in:09,31 (default lógico '09') |
| serie | required, string, size:4 |
| correlativo | nullable, integer, min:1 (si se omite → autoincremento) |
| fecha_emision | required, date |
| observacion | nullable, string, max:500 |
| destinatario.tipo_doc | required, string, SunatCatalog('06') |
| destinatario.num_doc | required, string, max:15 |
| destinatario.razon_social | required, string, max:255 |
| tercero | nullable array; tercero.tipo_doc required_with + Cat06; num_doc required_with max:15; razon_social required_with max:255 |
| comprador | idem tercero |
| cod_traslado | required_unless:tipo_documento,31, string, in:01,02,03,04,05,06,07,08,09,13,14,17,18,19 |
| mod_traslado | required_unless:tipo_documento,31, string, in:01,02 |
| fecha_traslado | required, date |
| fecha_entrega_transportista | nullable, date |
| peso_total | required, numeric, gt:0 |
| und_peso_total | nullable, string, max:3 (default 'KGM' al persistir) |
| num_bultos | nullable, integer, min:1 |
| indicadores / indicadores.* | nullable array / string |
| llegada_ubigeo, partida_ubigeo | required, string, size:6 |
| llegada_direccion, partida_direccion | required, string, max:500 |
| llegada_ruc, partida_ruc | nullable, string, size:11 |
| llegada_cod_local, partida_cod_local | nullable, string, max:5 |
| transportista | nullable array; tipo_doc required_with string; num_doc required_with max:11; razon_social required_with max:255; nro_mtc nullable max:20 |
| vehiculo | nullable array; vehiculo.placa required_with, regex `^[A-Z0-9]{6,8}$` |
| vehiculo.nro_circulacion ⚠ | nullable, string, max:15 (l.182 pisa al regex de l.125) |
| vehiculo.tuc ⚠ | nullable, string, max:15 (l.183 pisa l.126) |
| vehiculo.cod_emisor | nullable, string, size:2 |
| vehiculo.nro_autorizacion | nullable, string, max:50 |
| vehiculo.secundarios | nullable array; `*.placa` required regex `^[A-Z0-9]{6,8}$`; `*.nro_circulacion` ⚠ nullable max:15 (l.186 pisa l.129); `*.tuc` nullable regex `^[A-Z0-9]{10,15}$` (l.130, no pisado); `*.cod_emisor` size:2; `*.nro_autorizacion` max:50 |
| conductor (objeto) | nullable array; tipo_doc required_with; num_doc required_with max:15; tipo nullable in:Principal,Secundario; nombres/apellidos required_with max:100; licencia required_with max:20 |
| conductores.* | tipo_doc required; num_doc required max:15; tipo nullable in:Principal,Secundario; nombres, apellidos required max:100; licencia required max:20 |
| doc_relacionado | nullable, array, max:5; `*.tipo_codigo` required_with in:01,03,04,09,12,31,48,50,52,65..69,71..78,80,82,91; `*.numero` required_with max:100; `*.tipo_descripcion` nullable max:120; `*.ruc_emisor` nullable size:11 |
| remitente | nullable array; tipo_doc required_with in:1,6; num_doc required_with max:15; razon_social required_with max:250 |
| datos_subcontratador | nullable array; num_doc required_with size:11; razon_social required_with max:250 |
| datos_pagador_flete | nullable array; tipo required_with in:remitente,subcontratador,tercero; tipo_doc nullable in:1,6; num_doc nullable max:15; razon_social nullable max:250 |
| autorizacion_especial | nullable array; cod_emisor nullable size:2; nro_autorizacion nullable max:50 |
| items | required, array, min:1; `*.descripcion` required max:500; `*.cantidad` required numeric gt:0; `*.unidad` nullable max:5; `*.codigo` nullable max:50; `*.cod_prod_sunat` nullable max:20 |
| enviar_automatico | **no validado**; se lee con `$request->boolean('enviar_automatico', true)` |

Validaciones `after` (l.200-388):
- Serie registrada y activa del tipo (09 o 31) — trait `ValidatesSerieRegistrada` (app/Http/Requests/Concerns/ValidatesSerieRegistrada.php:70-110). Mensajes: serie desactivada / no registrada / ninguna serie del tipo (etiqueta "guías de remisión" o "guías de remisión transportista").
- Correlativo manual no duplicado (tenant+serie+correlativo en `dispatch_guides`) — `ValidatesManualCorrelativo` (Concerns/ValidatesManualCorrelativo.php:23-46) → error en `correlativo`.
- **GRT (31)**: `remitente` obligatorio; `remitente.num_doc` ≠ RUC tenant; `doc_relacionado` obligatorio; `tipo_codigo` ∈ {01,03,09,31,50,52}; si 09/31 → `ruc_emisor` obligatorio; `vehiculo` obligatorio; `conductor` o `conductores` obligatorio; si `datos_subcontratador` → `datos_pagador_flete` obligatorio; si pagador `tercero` → num_doc y razon_social obligatorios.
- **GRR (09)**: si `mod_traslado=01` y no M1L → `transportista` obligatorio; si `fecha_emision >= 2026-06-01` → `fecha_entrega_transportista` obligatoria; ésta no puede ser < `fecha_traslado`; `transportista.num_doc` ≠ RUC tenant. Si `mod_traslado=02` y no M1L → `vehiculo` y conductor(es) obligatorios. M1L = algún indicador que contenga "M1L".
- Por motivo (Cat.20, solo GRR): motivos 06/17/19 → destinatario tipo_doc=6; 02/04/07 → destinatario = RUC emisor; 01/03/05/06/09/14/17 → destinatario ≠ RUC emisor; 08/09/19 → doc_relacionado tipo 50/52 (19 también 91).
- Error 422 con formato estándar `estado/mensaje/errores` (l.390-397).

### 1.2 POST /guias-remision y /guias-remision-transportista
Lógica (CreateDispatchGuideAction.php:18-97), en transacción:
- `Serie` con `lockForUpdate` (tenant, tipo, serie, activa) → `resolveCorrelativo` (app/Models/Serie.php:86-103: incrementa o usa el manual y sube el contador si es mayor).
- GRT sin `cod_traslado/mod_traslado` → default '01'/'01'.
- `sucursal_id` y `cod_local` desde la serie (`cod_local` de sucursal ?? `data.cod_local` ?? '0000').
- `conductor` persiste `conductor ?? conductores`.
- Estado inicial `sunat_status='pendiente'`; evento `DocumentCreated`; borra caché `tenant:{id}:sunat_count:{Y-m}`; `PlanService::incrementUsage('documents')`.
- Si `enviar_automatico` (default true): `SendDispatchGuideToSunat::dispatch` (cola) y `sunat_status='enviado'`.
- storeTransportista fuerza `tipo_documento='31'` (DispatchGuideController.php:52).

Respuesta 201 (DispatchGuideController.php:72-88), **construida a mano**:
```json
{
  "estado": "exito",
  "mensaje": "Guía de remisión creada y encolada para envío a SUNAT." | "Guía de remisión creada en estado pendiente. Use POST /guias-remision/{id}/enviar para enviarla a SUNAT.",
  "datos": { ...DispatchGuideResource... },
  "advertencias": [ { "codigo": "grt_beta_no_soportado", "mensaje": "SUNAT beta no valida completamente las Guías de Remisión Transportista. Para pruebas realistas cambia entorno a \"production\" en PUT /empresa." } ]
}
```
- `advertencias` solo si tipo 31 y tenant `environment==='beta'`.
- Mensajes GRT: "Guía de Remisión Transportista creada y encolada para envío a SUNAT." / "Guía de Remisión Transportista creada en estado pendiente. Use POST /guias-remision/{id}/enviar para enviarla a SUNAT."
- Error en la acción → 500 `{"estado":"error","mensaje":"Error al crear guía: <exception msg>"}` (GRT: "Error al crear guía transportista: ...").

**DispatchGuideResource** (DispatchGuideResource.php:10-54):
```json
{
  "id": int, "serie": "T001", "correlativo": int,
  "numero_completo": "T001-000123",           // serie + '-' + correlativo pad 6 (app/Models/DispatchGuide.php:88-91)
  "fecha_emision": "Y-m-d H:i:s",
  "destinatario": {"tipo_doc","num_doc","razon_social"},
  "envio": {"cod_traslado","mod_traslado","fecha_traslado":"Y-m-d","fecha_entrega_transportista":"Y-m-d"|null,"peso_total": float,"und_peso_total"},
  "direcciones": {"llegada":{"ubigeo","direccion"},"partida":{"ubigeo","direccion"}},
  "observacion", "transportista": obj|null, "vehiculo": obj|null, "conductor": obj|array|null,
  "num_bultos": int|null, "items": array,
  "sunat": {"estado","codigo","descripcion","ticket"},
  "archivos": {"xml": url|null, "cdr": url|null, "pdf": url|null},   // url("/api/v1/guias-remision/{id}/xml") etc. si existe *_path
  "creado_en": ISO8601
}
```
No expone `tipo_documento`, remitente, doc_relacionado, indicadores, ruc/cod_local de direcciones, etc.

Estados `sunat_status` guía: `pendiente` → `enviado` (al encolar / al recibir ticket) → `aceptado` | `rechazado`. Job `SendDispatchGuideToSunat` (tries 10, backoff [15,30,60,60,120,120,300,300,600,600]): envía por API REST GRE (`sendDespatch`), guarda XML, si OK guarda `ticket`, genera PDF, encola `CheckTicketStatus` a +15 s; errores reintentables códigos 0,100,109,500,1033 o de conexión; agotados → `rechazado` con `MAX_RETRIES`; error de construcción → `BUILD_ERROR`. Webhooks `document.sent` / `document.rejected` si `webhook_url`.

### 1.3 GET /guias-remision (index) — l.114-131
- Query: `por_pagina` (int, default 15). **Sin filtros**. Orden `created_at desc`. Paginación Laravel (`page`).
- Respuesta 200 (vía `success`, datos anidados):
```json
{"estado":"exito","mensaje":"OK","datos":{"datos":[Resource...],"paginacion":{"pagina_actual","ultima_pagina","por_pagina","total"}}}
```

### 1.4 GET /guias-remision/{id} — l.133-139
200 `success(Resource)` → `{"estado":"exito","mensaje":"OK","datos":{...}}`. No existe/otro tenant → 404 global.

### 1.5 PUT /guias-remision/{id} — l.141-157
- Valida con el mismo StoreDispatchGuideRequest (todas las reglas `required` aplican; `tipo_documento` default '09' si no se envía).
- Estado debe ser `pendiente` o `rechazado`; si no → 422 `{"estado":"error","mensaje":"Solo guías pendientes o rechazadas pueden editarse."}`.
- UpdateDispatchGuideAction (l.111-150): actualiza fecha_emision, destinatario, cod/mod traslado (con default), fechas, peso, und, bultos, ubigeos/direcciones (NO ruc/cod_local), transportista, vehiculo, conductor(es), items; resetea `sunat_status='pendiente'`, `sunat_code/description/ticket/xml_path/cdr_path/pdf_path=null`; **siempre** encola el envío y pone `enviado`.
- 200 `{"estado":"exito","mensaje":"Guía actualizada y reenviada a SUNAT.","datos":Resource}`; excepción → 500 "Error al actualizar guía: ...".

### 1.6 GET /guias-remision/{id}/pdf — l.159-183
- Query `format` (default `config('pdf.default_format','a4')`), valores `PdfFormatConfig`: a4, a5, ticket-80, ticket-58. Inválido → 422 "Formato inválido. Opciones: a4, a5, ticket-80, ticket-58".
- Caché: si formato = default y `pdf_path` → contenido almacenado; si no, caché en disco `storage/app/pdf-cache/{tenant_id}/{tabla}_{id}_{format}.pdf` TTL 1 h (app/Http/Traits/CachesPdf.php:83-119). Si no hay, genera y cachea.
- 200 binario, headers: `Content-Type: application/pdf`, `Content-Disposition: inline; filename="{numero_completo}.pdf"`, `Cache-Control: private, max-age=300`.

### 1.7 GET /guias-remision/{id}/xml — l.185-201
- `DocumentStorageService::getXmlContent`. No disponible → 404 `{"estado":"error","mensaje":"XML no disponible"}`.
- 200 `Content-Type: application/xml`, `Content-Disposition: attachment; filename="{numero_completo}.xml"`.

### 1.8 GET /guias-remision/{id}/cdr — l.203-219
- No disponible → 404 "CDR no disponible". 200 `Content-Type: application/zip`, `Content-Disposition: attachment; filename="R-{numero_completo}.zip"`.

### 1.9 GET /guias-remision/{id}/estado (checkStatus) — l.221-298 — **consulta SUNAT síncrona**
1. Sin ticket:
   - estado `pendiente`/`enviado` → **202** `{"estado":"pendiente","mensaje":"La guía todavía no tiene ticket SUNAT. Primero debe ejecutarse el envío inicial de la guía.","guia":{id,numero_completo,estado_sunat,codigo_sunat,descripcion_sunat,"tiene_ticket":false},"siguiente_accion": "Use POST /guias-remision/{id}/enviar para enviar la guía a SUNAT." (si pendiente) | "Verifique que el worker de colas esté ejecutándose: php artisan queue:work --queue=default" (si enviado)}`.
   - otro estado → **409** con `"estado":"error"`, mensaje "La guía no tiene ticket SUNAT porque el envío inicial falló antes de recibir ticket." y `siguiente_accion` "Corrija el error indicado y use POST /guias-remision/{id}/enviar para reintentar.".
2. Estado `aceptado`/`rechazado` (salvo rechazado con code `API` y descripción con "Token NO existe") → 200 `success(Resource,"Estado ya resuelto.")`.
3. Consulta `GreenterService::getGreStatus(ticket)` (reintenta una vez si falta token GRE):
   - éxito → actualiza `aceptado|rechazado` (según `accepted`, default true), code (≤20), description (≤500), `sent_at`; guarda CDR; 200 `success(Resource)` (mensaje "OK").
   - error con código 0/187/401 → **202** `{"estado":"error","mensaje":"Error al consultar estado: <msg|Respuesta inválida de SUNAT.>"}`.
   - otro error → marca `rechazado` con ese código y 200 `success(Resource,"Estado ya resuelto.")`.
   - excepción → 500 "Error al consultar estado: ...".

### 1.10 POST /guias-remision/{id}/enviar — l.90-112
- `aceptado` → 422 "Esta guía de remisión ya fue aceptada por SUNAT.".
- Resetea a `pendiente` (code/description null), encola job, pone `enviado`. **No** limpia `ticket` (ver observaciones).
- 200 `{"estado":"exito","mensaje":"Guía de remisión enviada a SUNAT.","datos":Resource}`.

---

## 2. Resúmenes diarios (RC)

Controlador `app/Http/Controllers/Api/V1/SummaryController.php`; Request `StoreSummaryRequest.php`; Jobs `SendSummaryToSunat.php`, `CheckSummaryTicketStatus.php`; comando programado `summaries:poll-pending` cada 5 min (routes/console.php:79-82).

| Método | URL | MW extra | Acción |
|---|---|---|---|
| GET | /api/v1/resumenes | — | index (api.php:148) |
| POST | /api/v1/resumenes | license, check.limit:sunat | store (149) |
| GET | /api/v1/resumenes/{id}/estado | — | checkStatus (150) |
| GET | /api/v1/resumenes/{id}/xml | — | xml (151) |
| GET | /api/v1/resumenes/{id}/cdr | — | cdr (152) |
| POST | /api/v1/resumenes/{id}/enviar | — | enviar (153) |

(No hay `GET /resumenes/{id}` → 404 global.)

### 2.1 GET /resumenes — l.24-66
- Filtros: `mes` (`YYYY-MM`, compara año-mes de `fecha_envio`; pgsql `to_char`, mysql `DATE_FORMAT`), `tipo` (`envio`|`anulacion`), `por_pagina` (default 15), `page`.
- 200 **sin `mensaje`**:
```json
{"estado":"exito","datos":[{"id","identifier","tipo","fecha_referencia":"Y-m-d","fecha_envio":"Y-m-d","total_documentos","ticket","estado_sunat","codigo_sunat","descripcion_sunat","creado_en":ISO8601}],
 "paginacion":{"total","pagina_actual","ultima_pagina"}}
```

### 2.2 POST /resumenes — l.75-246
Request (StoreSummaryRequest.php:18-29): `fecha_resumen` required|date; `anular` nullable|array; `anular.*.id` required_with:anular|integer; `anular.*.motivo` required_with:anular|string|max:255; `anular.*.tipo_documento` nullable|in:03,07,08. `enviar_automatico` (no validado, default true).

Lógica:
- Ventana: `fecha_resumen` ∈ [hoy(Lima) − 7 días, hoy fin de día]; si no → 422 "SUNAT solo permite resumen diario del mismo día de emisión o hasta 7 días calendario después. Fecha límite: Y-m-d".
- **Modo anulación** (si `anular` no vacío): busca `Boleta` del tenant por ids con `sunat_status='aceptado'` (solo boletas; `tipo_documento` y `motivo` se ignoran). Vacío → 422 "No se encontraron boletas aceptadas por SUNAT para anular.". Por boleta: emisión < límite 7 días → error; con NC asociada (`credit_notes` doc_afectado 03/serie/correlativo) → error; ya en resumen `anulacion` pendiente/enviado/aceptado (`whereJsonContains document_ids`) → error. Con errores → 422 `{"estado":"error","mensaje":"No se puede anular: <errores unidos>","errores":[...strings]}`.
- **Modo envío**: boletas del tenant con `fecha_emision` = fecha y `sunat_status='pendiente'`. Vacío → 422 "No hay boletas pendientes para la fecha Y-m-d.". Excluye las ya incluidas en resúmenes `envio` de esa `fecha_referencia` pendiente/enviado/aceptado; si quedan 0 → 422 "Las boletas de la fecha Y-m-d ya están incluidas en un resumen (en proceso o aceptado). Consulta su estado en vez de generar otro — reenviarlas da el error SUNAT 2282 \"documento ya informado anteriormente\".".
- Identificador: `fecha_envio` = hoy Lima; correlativo = 1 + max(correlativo) de summaries del tenant con esa fecha_envio, con `Cache::lock` 10 s, pad 3 → `RC-YYYYMMDD-NNN`. Unique (tenant_id, identifier) (migración 2026_07_30_140000).
- Crea `Summary` (`tipo` envio|anulacion, `document_ids`, `sunat_status='pendiente'`). Anulación → boletas a `anulacion_en_proceso`.
- `enviar_automatico` → `SendSummaryToSunat::dispatch` y `enviado`.
- 201:
```json
{"estado":"exito","mensaje":"Resumen diario encolado para envío a SUNAT." | "Resumen de anulación encolado para envío a SUNAT." | "<base> creado en estado pendiente. Use POST /resumenes/{id}/enviar para enviarlo a SUNAT.",
 "datos":{"id_resumen","identifier","fecha_envio":"Y-m-d","fecha_documentos":"Y-m-d","correlativo":"001","accion":"envio"|"anulacion","total_documentos","estado_sunat":"enviado"|"pendiente",
   "documentos":[{"id","tipo_documento":"03","serie_nro":"B001-12","estado":"1"|"3","cliente":{"tipo_doc","num_doc"},"mto_oper_gravadas","mto_oper_exoneradas","mto_oper_inafectas","mto_oper_gratuitas","mto_igv","total","estado_sunat"}],
   "consulta_estado": url("/api/v1/resumenes/{id}/estado")}}
```
- Excepción → 500 "Error al crear resumen: ...".

Estados (jobs): Summary `pendiente` → `enviado` (ticket) → `aceptado`|`rechazado`. Boletas en envío: `pendiente` → `enviado` (al recibir ticket) → `aceptado`|`rechazado` (no degrada ya aceptadas/anuladas). En anulación: `anulacion_en_proceso` → **`anulado` al recibir ticket** (SendSummaryToSunat.php:87-93) → si aceptado `anulado`, si rechazado vuelve a `aceptado` (CheckSummaryTicketStatus.php:79-101). Reintentables envío: 0,100,109,500,1033,2800; polling: 0,98,187 o no numérico.

### 2.3 GET /resumenes/{id}/estado — l.305-334 — **NO consulta SUNAT** (solo BD; el polling lo hacen jobs/cron)
200 sin `mensaje`: `{"estado":"exito","datos":{"id_resumen","identifier","ticket","estado_sunat","codigo_sunat","descripcion_sunat","notas_sunat":array|null,"tipo","total_documentos","documentos":[documentoResumen...]}}`. `documentos[].estado_sunat` = estado actual de la boleta.

### 2.4 GET /resumenes/{id}/xml — l.336-365
- Sin `xml_path` → 404 con mensaje según estado: pendiente "El resumen aún no fue procesado (estado: pendiente). Espera a que el worker lo envíe a SUNAT." / enviado "El resumen fue enviado pero el XML no está disponible aún. Consulta el estado con GET /resumenes/{id}/estado." / rechazado "El resumen fue rechazado por SUNAT y el XML no pudo guardarse. Código: X." / otro "XML no generado (estado: X).".
- Archivo inexistente → 404 "El XML fue generado pero el archivo no se encuentra en disco. Ruta esperada: <path>" (filtra ruta interna).
- 200 `application/xml`, `attachment; filename="{identifier}.xml"`. Ruta de almacenamiento: `{ruc}/0000/{fecha_referencia}/xml/{identifier}.xml` (SendSummaryToSunat.php:72).

### 2.5 GET /resumenes/{id}/cdr — l.367-382
404 "CDR no disponible"; 200 `application/zip`, `attachment; filename="R-{identifier}.zip"`.

### 2.6 POST /resumenes/{id}/enviar — l.275-303
`aceptado` → 422 "Este resumen ya fue aceptado por SUNAT.". Reset a pendiente, encola, `enviado`. 200 `{"estado":"exito","mensaje":"Resumen enviado a SUNAT.","datos":{"id_resumen","identifier","estado_sunat":"enviado","consulta_estado":url}}`.

---

## 3. Comunicaciones de baja (RA) — `/anulaciones`

Controlador `app/Http/Controllers/Api/V1/VoidedController.php`; servicio `app/Services/Documents/VoidedService.php`; Request `StoreVoidedRequest.php`; modelo `VoidedDocument` (tabla `voided_documents`, compartida con RR). Jobs `SendVoidedToSunat`, `CheckVoidedTicketStatus`.

| Método | URL | MW extra | Acción |
|---|---|---|---|
| POST | /api/v1/anulaciones | license, check.limit:sunat | store (api.php:156) |
| GET | /api/v1/anulaciones | — | index (157) |
| GET | /api/v1/anulaciones/{id} | — | show (158) |
| GET | /api/v1/anulaciones/{id}/estado | — | checkStatus (159) |
| GET | /api/v1/anulaciones/{id}/xml | — | xml (160) |
| GET | /api/v1/anulaciones/{id}/cdr | — | cdr (161) |
| POST | /api/v1/anulaciones/{id}/enviar | — | enviar (162) |

### 3.1 POST /anulaciones — l.26-99
Request (StoreVoidedRequest.php:57-69): `fecha_generacion` required|date; `fecha_comunicacion` sometimes|date (default `now()` UTC Y-m-d); `detalles` required|array|min:1; `detalles.*.tipo_documento` required|in:01,03,07,08,20,40; `detalles.*.serie` required|size:4; `detalles.*.correlativo` required|**string**; `detalles.*.motivo` required|max:255. `enviar_automatico` no validado (default true).
- Algún detalle tipo `03` → 422:
```json
{"estado":"error","mensaje":"Las boletas no se anulan por comunicación de baja. Use POST /resumenes con {\"anular\":[{\"id\":..., \"motivo\":\"...\"}]}","codigo_error":"boletas_no_soportadas_en_ra","siguiente_accion":{"operacion":"anular_boleta_por_resumen_diario","endpoint":"POST /api/v1/resumenes"}}
```
- `VoidedService::crear` (l.37-88) + `validarDetalles` (l.96-174): por detalle: tipo soportado solo 01/07/08 (20/40 → "Tipo de documento 20 no soportado para anulación."); documento existe; `sunat_status='aceptado'`; emisión ≥ hoy(Lima)−7 días; factura sin NC asociada; sin RA vigente (pendiente/enviado/aceptado) que lo contenga (filtrado en PHP). Errores → 422 `{"estado":"error","mensaje":"No se puede anular: ...","errores":[...]}`.
- Correlativo = max(correlativo) de `voided_documents` del tenant con esa `fecha_comunicacion` (**incluye RR**) +1, pad 3 → `RA-YYYYMMDD-NNN` (sin lock).
- Crea VoidedDocument (`motivo` = motivo del primer detalle), marca documentos `anulacion_en_proceso`, encola `SendVoidedToSunat` → `enviado`. `AuditLog::registrar('comprobante.anulado', ...)`.
- 201: `{"estado":"exito","mensaje":"Comunicación de baja encolada para envío a SUNAT." | "Comunicación de baja creada en estado pendiente. Use POST /anulaciones/{id}/enviar para enviarla a SUNAT.","datos":{"id_anulacion","identifier","correlativo","fecha_comunicacion","total_documentos","estado_sunat":"enviado"|"pendiente","consulta_estado":url("/api/v1/anulaciones/{id}/estado")}}`.
- Excepción → 500 `{"estado":"error","mensaje":"Error: <msg>"}`.

### 3.2 GET /anulaciones — l.272-300
- Filtros: `estado` (= sunat_status), `fecha_desde`, `fecha_hasta` (sobre `fecha_comunicacion`), `per_page` (**no** `por_pagina`; default 15), `page`. Orden `id desc`. Incluye RA **y RR** (misma tabla).
- 200 sin `mensaje`: `{"estado":"exito","datos":[<modelo VoidedDocument crudo>],"meta":{"pagina_actual","por_pagina","total","ultima_pagina"}}`.
- Modelo crudo (toArray): `id, tenant_id, identifier, correlativo, fecha_generacion, fecha_comunicacion` (cast date → ISO `2026-10-24T00:00:00.000000Z`), `total_documentos, detalles (array), motivo, anulado_por, xml_path, cdr_path, ticket, sunat_status, sunat_code, sunat_description, sunat_notes (array), created_at, updated_at` (app/Models/VoidedDocument.php; migraciones 2026_03_05_000010, 2026_08_24_130000, 2026_08_24_140000).

### 3.3 GET /anulaciones/{id} (show) y buildResponse — l.205-211, 249-270
200 sin `mensaje`: `{"estado":"exito","datos":{"id_anulacion","identifier","correlativo","ticket","fecha_generacion":"Y-m-d","fecha_comunicacion":"Y-m-d","estado_sunat","codigo_sunat","descripcion_sunat","notas_sunat","total_documentos","detalles":[...],"creado_en":ISO,"actualizado_en":ISO}}`.

### 3.4 GET /anulaciones/{id}/estado — l.123-167 — **consulta SUNAT síncrona** si `ticket` y estado `enviado`
- `getStatus(ticket)`: éxito → `aceptado|rechazado`, code, description, notes; si aceptado → documentos originales (01/03/07/08) a `anulado` (no 20/40). Error numérico distinto de 0/187 → `rechazado`. 0/187 o no numérico/excepción → sin cambios. Responde `buildResponse` (200).

### 3.5 GET /anulaciones/{id}/xml y /cdr — l.214-247
- Ruta vacía o inexistente → 404 `"XML de la anulación no disponible"` / `"CDR de la anulación no disponible"`.
- Nombre = `identifier` o `anulacion-{id}`. XML: `application/xml`, `attachment; filename="{nombre}.xml"`; CDR: `application/zip`, `attachment; filename="R-{nombre}.zip"`.

### 3.6 POST /anulaciones/{id}/enviar — l.169-203
- `aceptado` → 422 "Esta comunicación de baja ya fue aceptada por SUNAT.".
- Si `identifier` empieza por `RR-` → encola `SendReversionToSunat`; si no `SendVoidedToSunat`. Estado `enviado`.
- 200 `{"estado":"exito","mensaje":"Reversión enviada a SUNAT." | "Comunicación de baja enviada a SUNAT.","datos":{"id_anulacion","identifier","estado_sunat":"enviado"}}`.

---

## 4. Retenciones (20)

Controlador `app/Http/Controllers/Api/V1/RetentionController.php`; Request `StoreRetentionRequest.php`; acción `CreateRetentionAction.php`; job `SendRetentionToSunat` (envío **síncrono a SUNAT dentro del job**, CDR directo, sin ticket).

| Método | URL | MW extra | Acción |
|---|---|---|---|
| POST | /api/v1/retenciones | license, check.limit:sunat | store (api.php:165) |
| GET | /api/v1/retenciones | — | index (166) |
| GET | /api/v1/retenciones/{id} | — | show (167) |
| GET | /api/v1/retenciones/{id}/pdf | — | pdf (168) |
| GET | /api/v1/retenciones/{id}/xml | — | xml (169) |
| GET | /api/v1/retenciones/{id}/cdr | — | cdr (170) |
| POST | /api/v1/retenciones/{id}/enviar | — | enviar (171) |

### 4.1 Request (StoreRetentionRequest.php:233-280)
`serie` required|size:4|regex `^R[A-Z0-9]{3}$`; `correlativo` nullable|integer|min:1; `cod_local` nullable|size:4; `fecha_emision` required|date; `proveedor.tipo_doc` required + Cat06; `proveedor.num_doc` required|max:15; `proveedor.razon_social` required|max:1500; `proveedor.direccion` nullable|max:500; `regimen` required + Cat23; `tasa` required|numeric|0..100; `observacion` nullable|max:500; `documentos` required|array|min:1; `documentos.*.tipo_doc` required|in:01,03,12; `.num_doc` required; `.fecha_emision` required|date; `.imp_total` required|numeric|gt:0; `.moneda` nullable|in:PEN,USD,EUR; `.pagos` required|array|min:1; `.pagos.*.moneda` nullable|in:PEN,USD,EUR; `.pagos.*.importe` required|gt:0; `.pagos.*.fecha` required|date; `.fecha_retencion` required|date; `.imp_retenido` nullable|min:0; `.imp_pagar` nullable|min:0; `.tipo_cambio` nullable|array con `moneda_ref`, `moneda_obj`, `factor`(gt:0), `fecha` required_with. After: serie registrada tipo '20' ("retenciones") y correlativo manual no duplicado.

### 4.2 POST /retenciones — l.24-44
- Acción (CreateRetentionAction.php:19-91): lock serie tipo 20; por documento `imp_retenido = dado ?? round(imp_total*tasa/100,2)`, `imp_pagar = dado ?? round(imp_total - imp_retenido,2)`; totales `imp_retenido`, `imp_pagado`; `cod_local` default '0000'; items en `retention_items` (`pagos`, `tipo_cambio` JSON); invalida caché, incrementa uso; encola si `enviar_automatico` → `enviado`.
- 201 `{"estado":"exito","mensaje":"Retención creada y encolada para envío a SUNAT." | "Retención creada en estado pendiente. Use POST /retentions/{id}/enviar para enviarla a SUNAT.","datos":formatRetention}` (el texto dice `/retentions/`, sic).
- Excepción → 500 "Error al crear retención: ...".

`formatRetention` (l.172-208):
```json
{"id","serie","correlativo","numero":"R001-00000012",   // pad 8 (app/Models/Retention.php:44-47)
 "fecha_emision":"Y-m-d","proveedor":{"tipo_doc","num_doc","razon_social"},"regimen","tasa":float,
 "imp_retenido":float,"imp_pagado":float,"sunat_status","sunat_code","created_at":ISO,
 "documentos":[{"tipo_doc","num_doc","fecha_emision","imp_total","moneda","fecha_retencion","imp_retenido","imp_pagar"}]   // solo en show (withItems)
}
```
Nota: en store, aunque las `items` vienen cargadas, `documentos` NO se incluye (withItems=false).

### 4.3 GET /retenciones — l.70-106
Filtros (con `has`, no `filled`): `sunat_status` (scope `status`), `fecha_desde`+`fecha_hasta` (ambos; añade 00:00:00/23:59:59), `serie`, `proveedor_num_doc`; `per_page` default 15 (**no** `por_pagina`). Orden `created_at desc`.
200: `{"estado":"exito","mensaje":"OK","datos":{"datos":[formatRetention...],"paginacion":{"total","pagina_actual","ultima_pagina","por_pagina"}}}`.

### 4.4 GET /retenciones/{id} — 200 `success(formatRetention(+documentos))`.
### 4.5 GET /retenciones/{id}/pdf — igual que guías (format a4/a5/ticket-80/ticket-58; `inline; filename="{numero}.pdf"`; `Cache-Control: private, max-age=300`).
### 4.6 GET /retenciones/{id}/xml | /cdr — 404 "XML no disponible"/"CDR no disponible"; `attachment; filename="{numero}.xml"` / `"R-{numero}.zip"`.
### 4.7 POST /retenciones/{id}/enviar — `aceptado` → 422 "Esta retención ya fue aceptada por SUNAT."; 200 `success(formatRetention, "Retención enviada a SUNAT.")`.

---

## 5. Percepciones (40)

Controlador `app/Http/Controllers/Api/V1/PerceptionController.php`; Request `StorePerceptionRequest.php`; acción `CreatePerceptionAction.php`; job `SendPerceptionToSunat` (síncrono en job).

| Método | URL | MW extra | Acción |
|---|---|---|---|
| POST | /api/v1/percepciones | license, check.limit:sunat | store (api.php:174) |
| GET | /api/v1/percepciones | — | index (175) |
| GET | /api/v1/percepciones/{id} | — | show (176) |
| GET | /api/v1/percepciones/{id}/xml | — | xml (177) |
| GET | /api/v1/percepciones/{id}/cdr | — | cdr (178) |
| POST | /api/v1/percepciones/{id}/enviar | — | enviar (179) |

**No existe `/percepciones/{id}/pdf`** (sí en retenciones).

Request (StorePerceptionRequest.php:204-251): idéntico a retención salvo: `serie` regex `^P[A-Z0-9]{3}$`; `cliente.*` (en lugar de proveedor; razon_social max:1500); `regimen` Cat22; `documentos.*.cobros` (en vez de `pagos`) con `moneda`, `importe`, `fecha`; `documentos.*.fecha_percepcion`, `imp_percibido`, `imp_cobrar`; serie registrada tipo '40' ("percepciones").

Cálculo (CreatePerceptionAction.php:303-309): `imp_percibido = dado ?? round(imp_total*tasa/100,2)`; `imp_cobrar = dado ?? round(imp_total + imp_percibido,2)`; totales `imp_percibido`, `imp_cobrado`.

Respuestas: idénticas en forma a retenciones con `formatPerception` (l.143-179): `{"id","serie","correlativo","numero"(pad 8),"fecha_emision","cliente":{tipo_doc,num_doc,razon_social},"regimen","tasa","imp_percibido","imp_cobrado","sunat_status","sunat_code","created_at", "documentos":[{"tipo_doc","num_doc","fecha_emision","imp_total","moneda","fecha_percepcion","imp_percibido","imp_cobrar"}] (solo show)}`. Mensajes: "Percepción creada y encolada para envío a SUNAT." / "Percepción creada en estado pendiente. Use POST /perceptions/{id}/enviar para enviarla a SUNAT." (sic) / "Error al crear percepción: ..." / "Esta percepción ya fue aceptada por SUNAT." / "Percepción enviada a SUNAT.". Index filtros: `sunat_status`, `fecha_desde`+`fecha_hasta`, `serie`, `cliente_num_doc`, `per_page`.

---

## 6. Reversiones (RR) — anulación de retenciones/percepciones

`POST /api/v1/reversiones` — MW license, check.limit:sunat — `ReversionController@store` (api.php:182; app/Http/Controllers/Api/V1/ReversionController.php:24-99). Es el **único** endpoint RR; consulta/envío/descarga se hacen vía `/anulaciones/{id}/*` (misma tabla).

Request (validación inline, l.26-34; error 422 global): `fecha_generacion` required|date; `fecha_comunicacion` sometimes|date; `detalles` required|array|min:1; `detalles.*.tipo_documento` required|in:20,40; `.serie` required|size:4; `.correlativo` required|string; `.motivo` required|max:255. `enviar_automatico` (default true). Usa `$request->all()` (no `validated`).

Lógica: valida existencia, `aceptado` y no duplicado (con `whereJsonContains` de objeto — ver obs.) → 422 `{"estado":"error","mensaje":"No se puede anular: ...","errores":[...]}`. Correlativo = max(correlativo) de identifiers `RR-{fecha}-%` +1 pad 3 → `RR-YYYYMMDD-NNN`. Crea VoidedDocument, marca 20/40 `anulacion_en_proceso`, encola `SendReversionToSunat` → `enviado`. (No usa `VoidedService::crearReversion`, que es código duplicado usado por el panel web.)

201:
```json
{"estado":"exito","mensaje":"Reversión encolada para envío a SUNAT." | "Reversión creada en estado pendiente. Use POST /anulaciones/{id}/enviar para enviarla a SUNAT.",
 "datos":{"voided_id","identifier","correlativo","fecha_comunicacion","total_documentos","sunat_status":"enviado"|"pendiente","consulta_estado": url("/api/v1/voided/{id}/status")}}
```
(Claves en inglés `voided_id`, `sunat_status` a diferencia de RA; `consulta_estado` apunta a una ruta **inexistente**.) Excepción → 500 "Error: ...".

Estados RR: documento 20/40 `aceptado` → `anulacion_en_proceso` → `anulado` **al recibir ticket** (SendReversionToSunat.php:68-78) → CheckVoidedTicketStatus: aceptado `anulado`; rechazado revierte solo los `anulacion_en_proceso` (ya no hay ninguno → quedan `anulado`).

---

## 7. Consultas SUNAT y búsqueda de documentos

### 7.1 POST /api/v1/consultar-cdr — `ConsultController@cdrStatus` (api.php:185; ConsultController.php:17-39)
- Validación inline: `tipo_documento` required|in:01,03,07,08; `serie` required|size:4; `correlativo` required|integer|min:1.
- Lógica (app/Actions/Documents/ConsultCdrAction.php:155-163): `GreenterService::getStatus("{ruc}-{tipo}-{serie}-{numero}", config("facturacion.sunat.{env}.consulta_cdr"))` — usa el método de consulta de **ticket** contra el endpoint `billConsultService` (ver obs.).
- Resultado de `getStatus` (app/Services/Greenter/GreenterService.php:727-776): éxito `{"success":true,"cdr_zip":<binario>,"code","description","notes":[],"accepted":bool}`; fallo `{"success":false,"error_code":"NETWORK_ERROR"|código,"error_message"}`.
- 200 `success($result)` → `{"estado":"exito","mensaje":"OK","datos":{...arriba...}}` (incluso si `success:false`). Excepción → 500 "Error al consultar CDR: ...". (Con `cdr_zip` binario el `json_encode` fallaría → 500.)

### 7.2 GET /api/v1/consultar-cpe — `CpeConsultaController@consultar` (api.php:188; CpeConsultaController.php:101-144)
- Query (validate inline): `ruc_emisor` nullable|size:11 (default RUC tenant); `tipo_doc` required|in:01,03,04,07,08,R1,R7; `serie` required|max:4; `correlativo` required|integer|min:1; `fecha_emision` required regex `^\d{2}/\d{2}/\d{4}$` (dd/mm/yyyy); `monto` required|numeric|min:0.
- Servicio `app/Services/SunatCpeConsultaService.php`: requiere `client_id/client_secret` del tenant (si no → RuntimeException → 422 "El tenant no tiene configurado client_id/client_secret para consultar SUNAT."). Token OAuth `client_credentials` con scope `https://api.sunat.gob.pe/v1/contribuyente/contribuyentes`, cacheado `sunat_cpe_token:{tenant_id}` (expires_in − 60 s, mín. 60). Llama `ConsultaApi::consultarCpe(rucTenant, filtro)` (monto con 2 decimales).
- 200 `success(array_merge(result, {...}))`:
  - encontrado: `{"encontrado":true,"estado_cp","estado_cp_descripcion" (0 NO EXISTE,1 ACEPTADO,2 ANULADO,3 AUTORIZADO,4 NO AUTORIZADO, otro DESCONOCIDO),"estado_ruc","estado_ruc_descripcion","cond_domi_ruc","cond_domi_ruc_descripcion","observaciones":[],"consultado_por_ruc","ruc_emisor","tipo_doc","serie","correlativo"}`
  - no encontrado: `{"encontrado":false,"mensaje":<sunat msg>, "consultado_por_ruc",...}`
- Errores: `unauthorized_client`/"cliente no autorizado" → 401 (mensaje largo sobre Clave SOL → Consulta Integrada CPE → API, "No hay entorno beta, solo producción."); `invalid_client` → 401 "El client_id o client_secret es inválido. Regenera las credenciales en Clave SOL."; otro → 500 "Error al consultar CPE en SUNAT: ...".

### 7.3 GET /api/v1/buscar-documento — `ConsultController@lookupDocument` (api.php:191; ConsultController.php:46-85)
- Query: `tipo` required|in:1,4,6,7,0; `numero` required|string|min:8|max:15.
- 1) Busca en `clients` del tenant por `tipo_documento`+`numero_documento` → 200 `success({"tipo_doc","num_doc","razon_social","direccion" (''),"fuente":"local"})`.
- 2) Solo tipo 1 o 6: `DocumentLookupService::lookup` (app/Services/DocumentLookupService.php) contra proveedor externo `config('facturacion.lookup.base_url')` = apis.net.pe, rutas `GET /v1/ruc?numero=` y `GET /v1/dni?numero=` con `Authorization: Bearer <APIS_NET_PE_TOKEN|LOOKUP_API_TOKEN>`, timeout 8 s; caché 24 h solo éxitos (`doc_lookup:{tipo}:{numero}`).
  - RUC: `{"tipo_doc":"6","num_doc","razon_social"(razonSocial|nombre_o_razon_social|nombre),"direccion","estado","condicion","source":"sunat"}`
  - DNI: `{"tipo_doc":"1","num_doc","razon_social"(nombreCompleto | "apPat apMat nombres"),"nombres","apellido_paterno","apellido_materno","direccion","source":"reniec"}`
- No encontrado → 404 `{"estado":"error","mensaje":"No se encontró información para el documento ingresado"}`.
- Ojo: clave `fuente` (local) vs `source` (externo).

### 7.4 GET /api/debug/lookup (solo APP_DEBUG) — api.php:322-381
Sin auth. Query `numero` (default un RUC fijo), `tipo` (default '6'). Prueba 5 formas de autenticación contra apis.net.pe y devuelve `{"token_preview": primeros 12 chars del token + '...', "endpoint_v2", "results":{bearer, raw_header, query_param_v2, v1_query_param, v1_bearer}}`. **No migrar** (o migrar solo como herramienta de diagnóstico desactivada); filtra parte del token.

---

## 8. Empresa (perfil del tenant)

Controlador `app/Http/Controllers/Api/V1/TenantController.php`; recurso `TenantResource.php`.

| Método | URL | Acción |
|---|---|---|
| GET | /api/v1/empresa | show (api.php:194) |
| PUT | /api/v1/empresa | update (195) |
| DELETE | /api/v1/empresa | destroy (196) |
| POST | /api/v1/empresa/logo | uploadLogo (197) |
| POST | /api/v1/empresa/certificado | uploadCertificate (198) |
| GET | /api/v1/empresa/credenciales | credenciales (199) |
| POST | /api/v1/empresa/credenciales/regenerar | regenerarCredenciales (200) |
| GET | /api/sunat/configuracion (alias) | show (api.php:394) |
| POST | /api/sunat/configuracion (alias) | update (api.php:395) |

**TenantResource** (TenantResource.php:13-49):
```json
{"id","ruc","razon_social","nombre_comercial","direccion","ubigeo","departamento","provincia","distrito",
 "sol_user","tiene_sol_pass":bool,"client_id","tiene_client_secret":bool,"webhook_url","entorno":"beta"|"production","plan",
 "tax_regime","igv_rate_override":float|null,"nrus_categoria","tasa_igv_vigente":(TaxRateService::currentRateFor),
 "max_documentos_mes","documentos_este_mes":(invoices+boletas+NC+ND creados este mes, Tenant.php:244-253),
 "activo":bool,"logo_url": Storage::url(logo_path)+"?v="+updated_at.timestamp | null,"tiene_certificado":bool,
 "telefonos":[],"emails":[],"cuentas_bancarias":[],"billeteras_digitales":[],"mensaje_agradecimiento","mensaje_promocional",
 "tiene_webhook":bool,"creado_en":ISO}
```
- `GET /empresa` → 200 `success(TenantResource)`.

### 8.1 PUT /empresa (y POST /api/sunat/configuracion) — l.28-126
Validación inline: `razon_social` sometimes|max:255; `nombre_comercial` nullable|max:255; `direccion` nullable|max:500; `ubigeo` nullable|between:4,6; `departamento/provincia/distrito` nullable|max:100; `sol_user` nullable|max:50; `sol_pass` nullable|max:50; `client_id` nullable|max:100; `client_secret` nullable|max:255; `entorno` nullable|in:beta,production; `url_webhook` nullable|url|max:500; `logo` nullable|image|mimes:jpg,jpeg,png|max:2048 (KB); `telefonos` nullable|array|max:5, `.*` max:20; `emails` nullable|array|max:5, `.*` email|max:100; `cuentas_bancarias` nullable|array|max:5 con `banco` required max:50, `tipo_cuenta` required max:30, `moneda` required in:PEN,USD, `numero` required max:30, `cci` nullable max:25, `titular` nullable max:100; `billeteras_digitales` nullable|array|max:5 con `tipo` required in:yape,plin,tunki,otro, `numero` required max:20, `titular` nullable max:100; `mensaje_agradecimiento`, `mensaje_promocional` nullable|max:500; `tax_regime` nullable|in:rus,rer,mype,general (config/regimenes.php:23-28); `igv_rate_override` nullable|numeric|0..30; `nrus_categoria` nullable|in:1,2.
- Mapeo: `entorno`→`environment`, `url_webhook`→`webhook_url`. Campos null se descartan (no se puede "borrar" un campo salvo `igv_rate_override` y `nrus_categoria`, que aceptan null explícito si la clave está presente). Si `tax_regime` ≠ 'rus' → `nrus_categoria=null`. `logo` multipart → `logos/{ruc}/logo.{ext}` en disco `public`.
- Invalida caché `tenant:key:{api_key}`; AuditLog `empresa.actualizada`.
- 200 `success(TenantResource(fresh), "Empresa actualizada.")`.

### 8.2 POST /empresa/logo — l.128-152
`logo` required|image|mimes:jpg,jpeg,png,webp|max:2048. Guarda `logos/{ruc}/logo.{ext}` (public). 200 `success({"logo_path","logo_url": Storage::url+"?v="+timestamp}, "Logo actualizado.")`.

### 8.3 POST /empresa/certificado — l.195-242
`certificado` required|file|max:100 (KB); `contrasena_certificado` nullable|max:100. Extensión ∈ pfx,p12,pem,cer,crt; si no → 422 "Formato no soportado. Use .pfx, .p12, .pem, .cer o .crt". pfx/p12 sin contraseña → 422 "El campo contrasena_certificado es obligatorio para archivos .pfx/.p12". `CertificateService::convertToPem` (RuntimeException → 422 con su mensaje). Guarda PEM (`DocumentStorageService::storeCertificate`), `certificate_password` (cifrado). AuditLog. 200 `{"estado":"exito","mensaje":"Certificado actualizado y convertido a PEM.","datos":null}`.

### 8.4 GET /empresa/credenciales — l.154-162
200 `success({"api_key": <real>, "api_secret":"*** oculto — use POST /empresa/credenciales/regenerar para obtener uno nuevo ***"}, "El api_secret no puede mostrarse. Si lo perdiste, regenera tus credenciales.")`.

### 8.5 POST /empresa/credenciales/regenerar — l.164-193
Genera `api_key`=Str::random(64), `api_secret`=Str::random(64) (guardado en claro bajo cast encrypted). Invalida caché antigua. AuditLog. 200 `success({"api_key","api_secret","aviso":"Guarda estas credenciales ahora. El api_secret NO se volverá a mostrar."}, "Credenciales regeneradas. Las anteriores ya no son válidas.")`. Con `Idempotency-Key` la respuesta (con el secret) queda almacenada en `idempotency_keys`.

### 8.6 DELETE /empresa — l.244-265
AuditLog `empresa.eliminada` y `$tenant->forceDelete()` (borrado físico, cascadas de FK). 200 `success({"tenant_id"}, "Empresa eliminada.")`. Sin confirmación adicional.

---

## 9. Sucursales — `Route::apiResource('sucursales')` (api.php:203)
Controlador `app/Http/Controllers/Api/V1/SucursalController.php`. URLs: `GET/POST /api/v1/sucursales`, `GET/PUT/PATCH/DELETE /api/v1/sucursales/{id}` (parámetro de ruta generado por Laravel: `{sucursale}`; el controlador lo recibe posicionalmente como `int $id`).

Formato (`format`, l.135-149): `{"id","nombre","cod_local","direccion","ubigeo","telefono","email","es_principal":bool,"activo":bool,"total_series":int}`.
- **index** (l.15-26): sin paginación ni filtros; orden `is_principal desc, nombre`; `withCount('series')`. 200 `success([...])`.
- **store** (l.28-71): `nombre` required|max:100; `cod_local` required|size:4|regex `^[0-9]{4}$` (msg "El código de local debe ser 4 dígitos (ej: 0000, 0001)."); `direccion` required|max:500; `ubigeo` required|between:4,6; `telefono` nullable|max:20; `email` nullable|email|max:255; `es_principal` sometimes|boolean. Duplicado cod_local → **409** "El código de local XXXX ya existe.". Si es_principal → desmarca las demás; la primera sucursal siempre es principal. 201 `created(format)` mensaje "Creado exitosamente" (`total_series` 0).
- **show** (l.73-79): 200 `success(format)`.
- **update** (l.81-115): `nombre` sometimes|max:100; `direccion` sometimes|max:500; `ubigeo` sometimes|between:4,6; `telefono`/`email` sometimes|nullable; `es_principal` sometimes|boolean; `activo` sometimes|boolean. `cod_local` no editable. 200 `success(format, "Sucursal actualizada.")` (`total_series` vendrá 0 porque no se recarga el count).
- **destroy** (l.117-133): con series → 409 "No se puede eliminar: tiene N serie(s) asociada(s). Reasigne las series primero."; principal → 409 "No se puede eliminar la sucursal principal. Asigne otra como principal primero."; 200 `success(null,"Sucursal eliminada.")` (borrado físico).

## 10. Clientes — `Route::apiResource('clientes')` (api.php:206)
Controlador `ClientController.php`; Request `StoreClientRequest.php`; recurso `ClientResource.php` → `{"id","tipo_documento","numero_documento","razon_social","nombre_comercial","direccion","email","telefono","creado_en"}` (no expone `ubigeo`).
- Request: alias `tipo_doc`→`tipo_documento`, `num_doc`→`numero_documento` (prepareForValidation). POST: `tipo_documento` required|in:0,1,4,6,7,A; `numero_documento` required|max:15; `razon_social` required|max:255; PUT/PATCH: los tres `sometimes`. Comunes: `nombre_comercial` nullable|max:255; `direccion` nullable|max:500; `email` nullable|email; `telefono` nullable|max:20; `ubigeo` nullable|between:4,6.
- **index**: `buscar` (LIKE en razon_social o numero_documento), `por_pagina` default 15; orden razon_social. 200 `{"estado":"exito","mensaje":"OK","datos":{"datos":[...],"paginacion":{"pagina_actual","ultima_pagina","por_pagina","total"}}}`.
- **store**: `updateOrCreate` por (tenant, tipo, número) → siempre 201 "Creado exitosamente" aunque actualice.
- **show**: 200.
- **update**: solo actualiza razon_social, nombre_comercial, direccion, email, telefono, ubigeo (tipo/número ignorados). 200 "Cliente actualizado.".
- **destroy**: borrado físico. 200 `success(null,"Cliente eliminado.")`.

## 11. Series — `Route::apiResource('series')` + init-defaults (api.php:209-210)
Controlador `SerieController.php`. Tipos (app/Models/Serie.php:16-49): `factura`→01, `boleta`→03, `nota_credito`→07, `nota_debito`→08, `guia_remision`→09, `guia_transportista`→31, `retencion`→20, `percepcion`→40. Prefijos: 01 F; 03 B; 07/08 F o B; 09 T; 31 V; 20 R; 40 P.

`formatSerie` (l.269-285): `{"id","tipo": nombre amigable,"tipo_documento","serie","correlativo","siguiente_numero":"F001-{correlativo+1}","sucursal":{"id","nombre","cod_local"}|null,"activo"}`.
- **GET /series** (l.17-41): `include_inactive` (bool, default false → solo activas), `sucursal_id`, `tipo` (nombre amigable). Sin paginación. Orden tipo_documento, serie. 200 `success([...])`.
- **POST /series** (l.43-169): acepta `{"series":[...]}` o un solo objeto. Reglas: `series` sometimes|array|min:1; `series.*.tipo` required|in:<nombres>; `series.*.serie` required|size:4|regex `^[A-Z][A-Z0-9]{3}$`; `series.*.sucursal_id` required|integer|exists:sucursales,id; `series.*.correlativo_inicial` sometimes|integer|min:0; y las mismas sin prefijo como `sometimes`. Mensajes custom: "El tipo debe ser uno de: ...", "La serie debe ser 4 caracteres (ej: F001, B001).", "Debe indicar la sucursal.". Por ítem (en transacción) errores acumulados como strings `"#i (SERIE): ..."`: tipo no válido; sucursal no encontrada (del tenant) o desactivada; prefijo inválido ("para X la serie debe empezar con: FXXX, ..."); ya existe activa. Existente inactiva → se reactiva con `correlativo_inicial`.
  - Todos fallan → 422 `{"estado":"error","mensaje":"No se pudo crear ninguna serie.","errores":[...]}`.
  - Alguno OK → 201 `{"estado":"exito","mensaje":"Creado exitosamente","datos":{"creadas":[formatSerie...],"errores":[...] (solo si hubo)}}`.
- **GET /series/{id}**: 200.
- **PUT/PATCH /series/{id}** (l.179-208): `activo` sometimes|boolean; `correlativo` sometimes|integer|min:0; `sucursal_id` sometimes|integer|exists. Sucursal de otro tenant → 403 "La sucursal no pertenece a su empresa.". 200 "Serie actualizada.".
- **DELETE /series/{id}**: soft (`is_active=false`). 200 `success(null,"Serie desactivada.")`.
- **POST /series/init-defaults** (l.229-267): `serie_factura` sometimes|size:4|regex `^F[A-Z0-9]{3}$` (default F001); `correlativo_factura` sometimes|integer|min:0 (default 0); `serie_boleta` regex `^B[A-Z0-9]{3}$` (default B001); `correlativo_boleta`. `firstOrCreate` sin sucursal; si existía y se envió el correlativo explícito, lo actualiza. 200 `success([2 × formatSerie], "Series configuradas correctamente.")`.

---

## 12. Catálogos

### 12.1 Producto SUNAT (UNSPSC v14, Cat. 25) — `CatalogoProductoController` (api.php:121-126). Respuestas construidas a mano (sin `mensaje`).
| URL | Query | Respuesta 200 |
|---|---|---|
| GET /api/v1/catalogos/producto-sunat | `q` (≥2 chars, si no → 422 `{"estado":"error","mensaje":"El parámetro \"q\" debe tener al menos 2 caracteres."}`), `per_page` (1..50, default 20), `page` | `{"estado":"exito","total","pagina","por_pagina","datos":[{"codigo","descripcion","clase"}]}`. Si `q` es numérico → prefijo de código ordenado por código; si texto → AND de palabras con LIKE, orden: empieza por la 1.ª palabra, `CHAR_LENGTH(descripcion)`, código (l.24-66) |
| GET .../producto-sunat/segmentos | — | `{"estado":"exito","total","datos":[{"codigo","nombre"}]}` (tabla `unspsc_taxonomy` nivel segmento) |
| GET .../producto-sunat/familias | `segmento` (2 dígitos; si no → 422 `{"estado":"error","mensaje":"El parámetro \"segmento\" es obligatorio y debe tener 2 dígitos."}`) | igual |
| GET .../producto-sunat/clases | `familia` (4 dígitos) | igual |
| GET .../producto-sunat/productos | `clase` (6 dígitos) | `{"estado":"exito","total","datos":[{"codigo","descripcion"}]}` (UnspscCode con `clase`, código no termina en "00") |
| GET .../producto-sunat/{codigo} (where `[0-9]{8}`) | — | `{"estado":"exito","valido":true,"datos":{"codigo","descripcion","jerarquia":{"segmento":{"codigo","nombre"},"familia":{...},"clase":{...}}}}`; inexistente → 404 `{"estado":"error","valido":false,"mensaje":"El código X no existe en el catálogo UNSPSC v14."}`; código no 8 dígitos → **no entra a la ruta** → 404 global "Recurso no encontrado." (el 422 del controlador l.71-77 es inalcanzable) |

### 12.2 Ubigeo — `UbigeoController` (api.php:129-131); también expuesto en web `/sunat/ubigeo/*` (web.php:205-207).
- `GET /api/v1/catalogos/ubigeo/regiones` → 200 `success([{"id","nombre"}])` (tabla `ubigeo_regiones`, orden nombre).
- `GET .../provincias?region_id=NN` (2 dígitos; si no → 422 "El parámetro region_id es requerido (2 dígitos).") → `[{"id","nombre","region_id"}]`.
- `GET .../distritos?provincia_id=NNNN` (4 dígitos; si no → 422 "El parámetro provincia_id es requerido (4 dígitos).") → `[{"id","nombre","provincia_id"}]`. El `id` del distrito (6 dígitos) es el ubigeo.

---

## 13. Panel / Dashboard — `/api/v1/panel/*` (api.php:233-245)
Controlador `app/Http/Controllers/Api/V1/DashboardController.php` (SQL crudo con UNION ALL). Todas devuelven 200 `success(datos)` (mensaje "OK"). Periodo común `resolvePeriod` (l.~776-789): `desde`+`hasta` (Y-m-d, ambos) → `["desde 00:00:00","hasta 23:59:59","desde al hasta"]`; si no, `mes=YYYY-MM` (default mes actual UTC) → primer/último día + label "Octubre 2026". **Los valores `periodo.desde/hasta` en la respuesta incluyen la hora** (`"2026-10-01 00:00:00"`).

| URL | Datos (claves) | Dependencia NV/COT |
|---|---|---|
| GET /panel | `periodo{desde,hasta,label}`, `facturas{count,total}`, `boletas`, `notas_credito`, `notas_debito`, **`notas_venta`**, `grafico[{fecha,ventas}]` (facturas+boletas+**NV**), `top_productos[{nombre,total}]` (10), `top_clientes[{nombre,total,documentos}]` (10), `comparacion{ventas_actuales (F+B+**NV**),ventas_anteriores (F+B mes anterior),cambio_ventas_porcentaje,documentos_actuales (F+B+**NV**),documentos_anteriores,cambio_documentos_porcentaje}` | **SÍ** — consulta `internal_documents` `type='sale_note'` y `status!='anulada'` (l.47-49, 87-89). Adaptar: quitar `notas_venta` o devolverlo `{count:0,total:0}` y excluir NV de gráfico/comparación |
| GET /panel/indicadores | `hoy, ayer, semana, mes_actual, mes_anterior, anio_actual, anio_anterior` cada uno `{documentos,ventas,igv,gratuitas,icbper,ticket_promedio}` (solo F+B no anulados/rechazados/en anulación) + `crecimiento{vs_ayer,vs_mes_anterior,vs_anio_anterior}` | No |
| GET /panel/estado-sunat | `periodo`, `total_documentos`, `por_estado{pendiente,enviado,aceptado,rechazado,anulado,(+otros que aparezcan)}`, `por_tipo{facturas:{estado:n},...}` (`[]` si vacío), `observados` (F+B aceptados con sunat_code ≠ ''/'0'), `tasa_aceptacion`, `ultimos_rechazos[{tipo,numero (pad 8),codigo_sunat,motivo(200),fecha}]` (10, sin filtro de periodo) | No |
| GET /panel/cobranzas | `resumen{pagado,parcial,pendiente: {documentos,monto_total,monto_pagado,saldo}}`, `aging{por_vencer,vencido_0_30,vencido_31_60,vencido_61_90,vencido_mas_90,documentos_con_saldo}`, `top_deudores[{ruc,cliente,documentos,saldo}]` (F+B, sin periodo) | No (no incluye NV aunque las NV tienen pagos) |
| GET /panel/ventas-mensuales | `meses[{mes:"Y-m",mes_label,ventas,ventas_anio_anterior}]` (12), `total_12_meses`, `total_12_meses_anterior`, `crecimiento_porcentaje`, `promedio_mensual` | No |
| GET /panel/por-sucursal | `periodo`, `total_general`, `sucursales[{cod_local,nombre,facturas{cantidad,monto},boletas{...},total,share_porcentaje}]` (LEFT JOIN por cod_local) | No |
| GET /panel/por-moneda | `periodo`, `monedas[{moneda,documentos,total,igv}]` | No |
| GET /panel/clientes | `periodo`, `total_registrados`, `clientes_unicos_periodo`, `clientes_nuevos`, `clientes_recurrentes`, `top_20[{ruc,tipo_documento,nombre,documentos,total}]` | No |
| GET /panel/productos | `periodo`, `top_por_venta[{codigo,descripcion,cantidad,total}]` (20), `top_por_cantidad` (20), `por_tipo_igv[{codigo,descripcion,items,total}]` | No |
| GET /panel/documentos-recientes | `limit` (default 20, máx 100) → `documentos[{tipo:factura|boleta|nota_credito|nota_debito,id,numero(pad 8),fecha_emision,cliente_documento,cliente,moneda,total,estado_sunat,emitido_en}]` | No |
| GET /panel/alertas | `rechazados_7_dias{cantidad}`, `vencen_proximos_7_dias{cantidad,monto}`, `vencidos{cantidad,monto}`, `pendientes_envio_sunat{cantidad}`, `series_por_agotar[{tipo_documento,serie,correlativo}]` (correlativo > 90 000 000) | No |

Porcentajes `pctChange` (l.~857): si anterior ≤ 0 → 100.0 si actual > 0, si no 0.0. Fechas `fecha_emision`/`created_at` vienen como string de BD (formato del driver).

---

## 14. Exportación masiva — `GET /api/v1/comprobantes/exportar-zip` (api.php:248; ExportController.php:43-184)
- Query: `fecha_desde`, `fecha_hasta` (requeridos, Y-m-d; falta → 422 "Los parámetros fecha_desde y fecha_hasta son requeridos (formato Y-m-d)."; inválida → 422 "Formato de fecha inválido. Use Y-m-d (ej: 2026-05-01)."; desde>hasta → 422 "fecha_desde no puede ser mayor que fecha_hasta."; rango > 366 días → 422 "El rango máximo permitido es 366 días."), `tipo` = xml|pdf|ambos (default xml; otro → 422 "El parámetro tipo debe ser xml, pdf o ambos."), `documentos` CSV de facturas,boletas,notas-credito,notas-debito (default todos; ninguno válido → 422 "Ningún tipo de documento válido especificado. Use: facturas, boletas, notas-credito, notas-debito"), `sucursal_id`, `estado` (default `todos`; cualquier otro valor filtra `sunat_status`).
- Solo facturas/boletas/NC/ND (no guías, retenciones, percepciones, NV, cotizaciones).
- Estructura del ZIP: `{tipo}/{Y-m}/{serie}-{correlativo pad 6}.xml|.pdf`.
- Sin archivos → 404 "No se encontraron archivos para los criterios especificados. Verifique que los documentos tengan XML/PDF generados y estén dentro del rango de fechas.".
- 200 streamed: `Content-Type: application/zip`, `Content-Length`, `Content-Disposition: attachment; filename="comprobantes_{ruc}_{desdeSinGuiones}_{hastaSinGuiones}_{tipo}.zip"`.
- Error al crear zip → 500 "No se pudo crear el archivo ZIP.".

---

## 15. Reportes — `/api/v1/reportes/*` (api.php:251-257)
Controlador `ReportController.php`; servicios `app/Services/Reports/ReportService.php`, `ReportPdfService.php`; Request `ReportRequest.php`.

Request común (ReportRequest.php:299-329): `fecha_desde` required|date; `fecha_hasta` required|date|after_or_equal:fecha_desde; rango ≤ 366 días (error en `fecha_hasta`: "El rango máximo permitido es de 1 año (366 días)."); `sucursal_id` nullable|integer|exists:sucursales,id; `serie` nullable|size:4; `client_num_doc` nullable|max:15; `estado_sunat` nullable|in:pendiente,enviado,aceptado,rechazado; `estado_pago` nullable|in:pendiente,parcial,pagado; `tipo_moneda` nullable|in:PEN,USD; `formato` nullable|in:json,pdf; `agrupacion` nullable|in:dia,semana,mes; `vencido` nullable|boolean. 422 estándar.

Comportamiento (ReportController.php:248-282): `formato=pdf` → PDF sin caché, `Content-Type: application/pdf`, `Content-Disposition: inline; filename="{nombre}-{Y-m-d}.pdf"`, `Cache-Control: private, max-age=60`; JSON → caché 300 s clave `report:{tenant}:{metodo}:{md5(filters)}` y 200 `success(data, mensaje)`.

| URL | Nombre PDF / vista / orientación | Mensaje | Datos | NV/COT |
|---|---|---|---|---|
| GET /reportes/registro-ventas | registro-ventas / registro_ventas / landscape | "Registro de ventas generado." | `titulo:"REGISTRO DE VENTAS"`, `periodo{desde,hasta}`, `filtros[{filtro,valor}]`, `documentos[mapDocumentRow]`, `totales_por_tipo{"01","03","07","08": sumarTotales}`, `gran_total`, `venta_neta` (01+03+08−07), `total_documentos` | No |
| GET /reportes/ventas-consolidado | ventas-consolidado / ventas_consolidado | "Ventas consolidado generado." | `titulo`, `periodo`, `filtros`, `kpis{total_facturado,total_igv,docs_emitidos,ticket_promedio,nc_emitidas,nd_emitidas,venta_neta,total_cobrado,porcentaje_cobrado}`, `desglose_temporal[{periodo,facturas,facturas_monto,boletas,boletas_monto,nc,nc_monto,nd,nd_monto,neto,igv}]`, `desglose_por_tipo[{tipo,nombre,cantidad,monto}]`, `desglose_por_sucursal[{sucursal,cod_local,facturas,boletas,nc,nd,monto}]`, `top_clientes[{num_doc,razon_social,cantidad_docs,monto_bruto}]`, `top_productos[{descripcion,cantidad,monto}]`, `desglose_por_moneda[{moneda,cantidad,monto}]` | No |
| GET /reportes/notas | notas-credito-debito / notas | "Reporte de notas generado." | `titulo:"NOTAS DE CRÉDITO Y DÉBITO"`, `periodo`, `filtros`, `kpis{total_nc,cantidad_nc,total_nd,cantidad_nd,nc_netas,igv_revertido,base_gravada_reducida}`, `notas_credito[{id,serie,correlativo,numero_completo,fecha_emision,tipo_doc_afectado,serie_afectado,correlativo_afectado,doc_afectado,cod_motivo,des_motivo,client_num_doc,client_razon_social,mto_oper_gravadas,mto_igv,mto_imp_venta,tipo_moneda,estado_sunat}]`, `notas_debito[...]`, `resumen_por_motivo[{cod_motivo,des_motivo,cantidad,monto_total,igv_revertido,base_gravada_reducida}]` | No |
| GET /reportes/cobranzas | cobranzas / cobranzas | "Reporte de cobranzas generado." | `titulo:"COBRANZAS Y CUENTAS POR COBRAR"`, `periodo`, `filtros`, `kpis{total_facturado,total_cobrado,total_pendiente,porcentaje_cobranza}`, `aging{"0-15","16-30","31-60","61-90","91-120","120+"}`, `detalle[{id,tipo:"01"|"03",numero_completo,client_num_doc,client_razon_social,fecha_emision,fecha_vencimiento,tipo_moneda,total,cobrado,pendiente,dias_atraso,estado_pago}]`, `resumen_por_metodo[{metodo,cantidad,monto}]`, `resumen_por_cliente[{num_doc,razon_social,total_facturado,total_cobrado,total_pendiente}]` | **Parcial**: `resumen_por_metodo` usa TODOS los `payments` del tenant en el rango (incluye pagos de **notas de venta**, ReportService.php:290-302) |
| GET /reportes/documentos-internos | documentos-internos / documentos_internos | "Reporte de documentos internos generado." | `cotizaciones{...}`, `notas_venta{...}`, `detalle_cotizaciones`, `detalle_notas_venta`, `top_clientes` | **SÍ — 100 % NV/COT. EXCLUIR** |
| GET /reportes/por-cliente | `estado-cuenta-{client_num_doc}` / por_cliente | "Estado de cuenta generado." (sin `client_num_doc` → 422 "El filtro client_num_doc es requerido para este reporte.") | `titulo:"ESTADO DE CUENTA POR CLIENTE"`, `periodo`, `cliente{tipo_documento,numero_documento,razon_social,nombre_comercial,direccion,email,telefono}` o `{numero_documento,razon_social:"No registrado"}`, `resumen{total_facturado,nc_emitidas,nd_emitidas,neto,cobrado,pendiente}`, `detalle_documentos[{tipo ("01","03","07","08",**"COT","NV"**),tipo_nombre,numero_completo,fecha_emision,fecha_vencimiento,mto_imp_venta,tipo_moneda,estado_sunat,estado_pago,monto_pagado}]`, `historial_pagos[{fecha,metodo,monto,referencia,notas}]` (incluye pagos de **NV**), `aging` | **SÍ (parcial)**: incluye cotizaciones y NV en detalle e historial de pagos (ReportService.php:452-457, 499-517). Adaptar: quitar COT/NV |
| GET /reportes/por-sucursal | comparativo-sucursales / por_sucursal / landscape | "Comparativo por sucursal generado." | `titulo:"COMPARATIVO POR SUCURSAL"`, `periodo`, `filtros`, `comparativo[{sucursal_id,nombre,cod_local,facturas{cantidad,monto},boletas,notas_credito,notas_debito,total_bruto,neto,igv,cobrado,pendiente,ticket_promedio,docs_dia_promedio,porcentaje_total}]`, `gran_total` | No |

Filtros en `queryDocuments` (ReportService.php:~640-675): tenant, `fecha_emision BETWEEN fecha_desde AND fecha_hasta` (**sin hora**, ver obs.), `sucursal_id`→`cod_local` de la sucursal (Sucursal::find sin filtrar tenant), `serie`, `client_num_doc`, `tipo_moneda`, `estado_sunat` (modo sunat) o `estado_pago` (modo payment). `por-cliente` y `por-sucursal` usan el scope `fechas()` (con 00:00:00/23:59:59, app/Models/Traits/HasDocumentFields.php:36-42).

---

## 16. SIRE — `/api/v1/sire/*` (api.php:260-310)
Controladores en `app/Http/Controllers/Api/V1/Sire/`; módulo de negocio `app/Sire/*` (servicios, jobs, modelos `sire_*`). Programación: `sire:poll-pending` cada minuto, `sire:reconcile-all` diario 03:00 (routes/console.php:57-65).

Formato de error común: `SireException` (app/Sire/Exceptions/SireException.php: `sunatCode`, `httpStatus` default 500) → `error($msg, $httpStatus, ['sunat_code'=>...])` → `{"estado":"error","mensaje":..., "errores":{"sunat_code": <str|null>}}` (siempre presente porque el array no está vacío). `InvalidArgumentException` (periodo inválido: "El periodo debe tener formato yyyymm: X", "Mes inválido: N", "Año inválido: N" — app/Sire/Support/PeriodoTributario.php) → 422 sin `errores`. Credenciales: `sireCredentials()` = `sire_client_id ?? client_id`, `sire_client_secret ?? client_secret` + sol_user/sol_pass (Tenant.php:116-117); sin ellas → 422 "El tenant no tiene configurado client_id/client_secret/sol_user/sol_pass. Configúralos antes de usar SIRE."; rechazo SUNAT → 401 (sunat_code `unauthorized_client`).

`{periodo}` = `yyyymm`. Respuestas asíncronas: HTTP **202** con `mensaje` "Solicitud aceptada" / "Upload aceptado" / "Encolado".

| # | Método | URL | MW | Controlador@acción | Request | Respuesta éxito |
|---|---|---|---|---|---|---|
| 1 | POST | /sire/activar | (sin sire.enabled) | SireActivacionController@activar | — | 200 `datos{sire_enabled:true,ruc,razon_social,mensaje:"SIRE activado. Ya puedes consumir los endpoints /v1/sire/*.",token_preview: 20 chars + "..."}` (obtiene token real OAuth SUNAT) |
| 2 | POST | /sire/desactivar | (sin sire.enabled) | @desactivar | — | 200 `datos{sire_enabled:false,mensaje:"SIRE desactivado. El token cacheado fue invalidado."}` |
| 3 | GET | /sire/periodos | sire.enabled | SirePeriodoController@index | `libro` sometimes in:rce,rvie (default rce) | 200 `datos{libro:"080000"|"140000",libro_desc,ejercicios:[...SUNAT 5.33]}` |
| 4 | GET | /sire/rce/constancia | sire.enabled | SireRceController@constancia | `nom_constancia` required|max:200 | 200 stream PDF, `Content-Type: application/pdf`, `attachment; filename="{nom_constancia}"` (5.49) |
| 5 | GET | /sire/rce/{periodo}/propuesta | sire.enabled | @propuesta | `formato` txt|csv|excel; `fec_emision_ini`, `fec_emision_fin` (Y-m-d); `cod_tipo_cdp` size:2; `num_serie_cdp`; `num_cdp`; `num_doc_adquiriente`; `mto_desde`, `mto_hasta` numeric | **202** `datos{num_ticket,per_tributario,estado (cod_estado_proceso),mensaje:"Propuesta solicitada. Consulta el ticket para obtener el archivo."}` (5.34) |
| 6 | GET | /sire/rce/{periodo}/resumen | sire.enabled | @resumen | `tipo` propuesta|preliminar|registro|preliminar_registrado|ajustes_posteriores|no_domiciliados|no_incluidos_excluidos (default propuesta); `formato` txt|csv|excel | 200 stream binario; Content-Type = el de SUNAT o `application/octet-stream`; `attachment; filename="resumen-{periodo}-{codTipoResumen 1..7}.{txt|csv|xlsx}"` (5.35) |
| 7 | POST | /sire/rce/{periodo}/aceptar-propuesta | sire.enabled | @aceptarPropuesta | — | **202** `datos{num_ticket,per_tributario,estado,mensaje:"Propuesta aceptada. El sistema cambiará al estado \"Preliminar registrado\" cuando el ticket termine."}` (5.2) |
| 8 | POST | /sire/rce/{periodo}/registrar-preliminar | sire.enabled | @registrarPreliminar | — | 200 `datos{exitoso,per_tributario,respuesta_sunat}` (síncrono, 5.4) |
| 9 | POST | /sire/rce/{periodo}/reemplazar-propuesta | sire.enabled | SireUploadController@reemplazarPropuesta | multipart `archivo` (zip, max 6 GB, required_without:txt) **o** `txt` (string, required_without:archivo); `secuencia` 1..99999999 (default 1) | **202** `datos{num_ticket,per_tributario,estado,archivo (nom_archivo_importacion),mensaje:"Propuesta reemplazada. Siga el ticket."}` (TUS 5.3) |
| 10 | POST | /sire/rce/{periodo}/no-domiciliados | sire.enabled | @noDomiciliados | ídem | 202, mensaje "No domiciliados cargados. Siga el ticket." (5.5) |
| 11 | POST | /sire/rce/{periodo}/complementar-propuesta | sire.enabled | @complementarPropuesta | ídem | 202, "Propuesta complementada. Siga el ticket." (5.6). `RuntimeException` → 422 |
| 12 | POST | /sire/rce/{periodo}/ajustes-posteriores/{variant}/cargar | sire.enabled | SireAjustesController@cargar | `txt` required; `secuencia` | **202** `datos{num_ticket,per_tributario,variant,estado,archivo,mensaje:"Ajustes cargados. Siga el ticket para confirmar."}` |
| 13 | POST | .../ajustes-posteriores/{variant}/enviar | sire.enabled | @enviar | `num_ticket_carga` required|size:16 | **202** `datos{num_ticket,per_tributario,variant,estado,mensaje:"Ajustes enviados. Sigue el ticket para ver el resultado."}` |
| 14 | GET | .../ajustes-posteriores/{variant}/descargar | sire.enabled | @descargar | `formato` txt|csv|excel | **202** `datos{num_ticket,per_tributario,variant,formato (extensión),mensaje:"Generación de archivo solicitada. Sigue el ticket."}` |
| 15 | POST | .../ajustes-posteriores/{variant}/eliminar | sire.enabled | @eliminar | `cod_ajuste_posterior` required; `detalles` required|array|min:1 con `cod_tipo_cdp` size:2, `num_serie_cdp`, `num_cdp`, `cod_car` required, `id` sometimes | 200 `datos{variant,per_tributario,eliminados (count),respuesta_sunat}` |
| 16 | GET | /sire/rce/{periodo}/comprobantes | sire.enabled | SireComprobanteController@index | `fase` propuesta|preliminar|registrado; `cod_tipo_cdp` size:2; `num_doc_proveedor`; `incluido` bool; `por_pagina` 1..200 (default 50); `sort_by` fec_emision|mto_total|razon_social_proveedor; `sort_dir` asc|desc; `page` | 200 `datos{totales{total_comprobantes,suma_bi_gravada,suma_igv,suma_total},comprobantes:<Paginator Laravel serializado: current_page,data[],first_page_url,from,last_page,last_page_url,links[],next_page_url,path,per_page,prev_page_url,to,total>}` |
| 17 | GET | /sire/rce/{periodo}/comprobantes/{id} | sire.enabled | @show | — | 200 `datos` = modelo `SireComprobante` crudo (id, tenant_id, sire_periodo_id, origen_ticket_id, per_tributario, fase, car_sunat, fec_emision, fec_vencimiento (date ISO), cod_tipo_cdp, num_serie_cdp, num_cdp, num_doc_proveedor, tipo_doc_proveedor, razon_social_proveedor, cod_moneda, mto_* (decimal → **string** "123.45"), tipo_cambio (string 4 dec), cod_inconsistencia, incluido (bool), raw_line, created_at, updated_at) |
| 18 | GET | /sire/rce/{periodo}/reconciliar | sire.enabled | SireReconciliationController@reconciliar | — | 200 `datos{tenant_id,per_tributario,run_at,totales,por_proveedor,por_tipo,cruce_local,alertas}` (síncrono; ReconciliationReport::toArray) |
| 19 | POST | /sire/rce/{periodo}/reconciliar-async | sire.enabled | @reconciliarAsync | — | **202** "Encolado" `datos{mensaje:"Reconciliación encolada. Consulta el historial con GET /v1/sire/rce/{periodo}/reconciliaciones",per_tributario}` (job `ReconcilePeriodoJob`) |
| 20 | GET | /sire/rce/{periodo}/reconciliaciones | sire.enabled | @historial | — | 200 `datos[{id,per_tributario,total_sunat,match_count,only_sunat_count,diff_amount_count,run_at}]` (últimos 20; **no** valida formato de periodo) |
| 21 | GET | /sire/rce/reconciliaciones/{id} | sire.enabled | @show | — | 200 `datos{id,per_tributario,run_at,details}` |
| 22 | GET | /sire/tickets | sire.enabled | SireTicketController@index | `per_tributario` size:6; `estado` size:2 (cod_estado_proceso); `cod_proceso` max:10; `finalizado` bool; `por_pagina` 1..100 (default 20) | 200 `datos` = Paginator Laravel serializado cuyos `data[]` son `presentTicket` |
| 23 | GET | /sire/tickets/{numTicket} | sire.enabled | @show | — | 200 `datos` = presentTicket: `{num_ticket,per_tributario,cod_proceso,des_proceso,estado,estado_descripcion,estado_enum (PENDIENTE|EN_PROCESO|PROCESANDO|TERMINADO|TERMINADO_ERRORES|ERROR),finalizado,exitoso,archivo_disponible,nom_archivo_reporte,cnt_cp_informados,cnt_cp_error,poll_attempts,created_at,last_polled_at,finished_at}` |
| 24 | POST | /sire/tickets/{numTicket}/refrescar | sire.enabled | @refresh | — | 200 presentTicket. Si no final → consulta SUNAT (`TicketService::fetchStatus`); si éxito con reporte y sin archivo local → encola `DownloadTicketFileJob` |
| 25 | GET | /sire/tickets/{numTicket}/archivo | sire.enabled | @archivo | — | Sin archivo → **409** "El archivo aún no está disponible. Verifica el estado con GET /v1/sire/tickets/{numTicket}"; no en storage → 404 "Archivo no encontrado en storage."; 200 descarga (`Storage::download`, disco `config('sire.storage.disk')`, nombre `nom_archivo_reporte` o `ticket-{numTicket}.zip`) |

Variants de ajustes (URL → valor devuelto en `variant`): `actual`→`actual`, `no-domiciliados`→`no_domiciliados`, `periodos-anteriores`→`periodos_anteriores`, `periodos-anteriores-nd`→`periodos_anteriores_nd` (app/Sire/Enums/VariantAjuste.php:15-18). Otro → 422 "Variant inválido. Use: actual | no-domiciliados | periodos-anteriores | periodos-anteriores-nd". Estados ticket SUNAT: 01 pendiente, 02 en proceso, 03 procesando, 05 terminado, 06 terminado con errores, 07 error (EstadoTicket.php).

Nota: el formulario TUS valida tamaño 6 GB pero PHP/nginx limitan antes. `num_ticket` etc. los persiste `SireTicket`.

---

## 17. Registro, planes, recuperación de credenciales, suscripción

### 17.1 POST /api/v1/registro (público) — RegisterController@store (api.php:38; RegisterController.php:17-123)
Multipart. Validación: `ruc` required|size:11|unique:tenants,ruc; `razon_social` required|max:255; `nombre_comercial` nullable|max:255; `direccion` required|max:500; `ubigeo` required|between:4,6; `departamento/provincia/distrito` nullable|max:100; `sol_user` required|max:20; `sol_pass` required|max:50; `entorno` sometimes|in:beta,production (default beta); `plan` sometimes|in:free,pro,business (default free); `tax_regime` sometimes|in:rus,rer,mype,general (default general); `igv_rate_override` nullable|numeric|0..30; `nrus_categoria` nullable|in:1,2 (solo si tax_regime=rus); `client_id` nullable|max:100; `client_secret` nullable|max:255; `certificado` required|file|max:100 KB; `contrasena_certificado` nullable|max:100; `logo` nullable|image|jpg,jpeg,png|max:2048.
- Certificado: mismas validaciones/mensajes que /empresa/certificado ("Formato de certificado no soportado. Use .pfx, .p12, .pem, .cer o .crt", contraseña obligatoria pfx/p12, errores de conversión → 422).
- Crea Tenant (`max_documents_month = config('facturacion.plans')[plan]['max_documents'] ?? 20` → config inexistente ⇒ siempre 20); el hook `creating` genera `api_key` = Str::random(64) y `api_secret` = sha256 hex de un random (Tenant.php:131-138). Guarda PEM y logo (`logos/{ruc}/<hash>.ext`).
- 201 `{"estado":"exito","mensaje":"Creado exitosamente","datos":{"tenant_id","ruc","razon_social","entorno","plan","tax_regime","igv_rate_override","nrus_categoria","api_key","api_secret","importante":"Guarde sus credenciales. El api_secret NO se puede recuperar."}}`.

### 17.2 GET /api/v1/planes (público) — SubscriptionController@plans (l.143-155)
200 `success([{"slug","nombre","precio_mensual":"49.00" (decimal:2 → string),"precio_anual":"...","limites":{...},"caracteristicas":{...}|[...]}])`; planes `is_active` ordenados por `sort_order` (app/Models/Plan.php:23-34, 61-64).

### 17.3 POST /api/v1/credenciales/recuperar (público) — CredentialRecoveryController@solicitar (l.20-83)
- `email` required_without:ruc|nullable|email|max:100; `ruc` required_without:email|nullable|digits:11.
- Rate limit propio 3 intentos/IP/10 min → 429 `{"estado":"error","mensaje":"Demasiados intentos. Intenta de nuevo en N segundos."}`.
- Busca tenant activo por RUC, o por email en JSON `emails` (whereJsonContains), o por email del usuario vinculado.
- No encontrado → 200 `{"estado":"exito","mensaje":"Si el RUC o email está registrado, recibirás las instrucciones en breve.","datos":null}`.
- Sin email destino → 422 "No hay un email registrado para esta empresa. Contacta al soporte." (revela existencia del RUC).
- Token Str::random(64) en caché `credential_recovery:{token}` 30 min; envía `CredentialRecoveryMail` (síncrono) → misma respuesta 200 genérica.

### 17.4 POST /api/v1/credenciales/recuperar/verificar (público) — @verificar (l.85-123)
- `token` required|string|size:64. Inválido/expirado/tenant inactivo → 422 "Token inválido o expirado.".
- Borra token y caché del api_key; nuevo `api_key` random 64; **guarda `api_secret = sha256(raw)` pero devuelve `raw`** (ver obs. crítica).
- 200 `success({"api_key","api_secret","aviso":"Guarda estas credenciales ahora. El api_secret NO se volverá a mostrar."}, "Credenciales regeneradas exitosamente.")`.

### 17.5 Suscripción (protegidas) — SubscriptionController
| Método | URL | Request | Respuesta |
|---|---|---|---|
| GET | /api/v1/suscripcion (l.157-179) | — | 200 `datos{"suscripcion": {id,plan{slug,nombre},estado,ciclo_facturacion,fin_prueba,fin_periodo_actual,cancelado_en (ISO|null),ultimos_cuatro_tarjeta,marca_tarjeta} | null, "uso": getUsageReport}` |
| POST | /api/v1/suscripcion (l.181-214) | `plan_slug` required|exists:plans,slug; `ciclo_facturacion` sometimes|in:monthly,yearly; `token` sometimes|string (token de pago); `prueba` sometimes|boolean (→ trial 14 días) | 201 "Suscripción creada exitosamente." `datos{id_suscripcion,plan,estado,fin_periodo_actual}`; RuntimeException → 422 con su mensaje |
| PUT | /api/v1/suscripcion/cambiar-plan (l.216-241) | `plan_slug` required|exists; `ciclo_facturacion`; `token` | 200 "Plan actualizado exitosamente." `datos{id_suscripcion,plan,estado}`; RuntimeException → 422 |
| PUT | /api/v1/suscripcion/cancelar (l.243-257) | — | 200 "Suscripción cancelada. Acceso disponible hasta el final del periodo." `datos{estado,cancelado_en}`; sin suscripción (ModelNotFound) → 404 "No hay suscripción activa para cancelar." |
| GET | /api/v1/suscripcion/pagos (l.259-281) | — | 200 `datos[{id,monto,moneda,estado,pagado_en (ISO),inicio_periodo (Y-m-d),fin_periodo}]` desc por created_at, sin paginación |
| GET | /api/v1/suscripcion/uso (l.283-288) | — | 200 `datos` = getUsageReport |

`getUsageReport` (app/Services/Plan/PlanService.php:164-208): `{"plan":{slug,tier,price(float)},"usage":{"documents_month":{current,limit,unlimited},"ai_messages_month":{current,limit,unlimited},"team_members":{limit},"sucursales":{current,limit},"productos":{limit,unlimited}},"features":{...},"next_upgrade":{slug,price}|null}` (resetea contadores si cambió el mes). Acciones en `app/Actions/Subscription/*` (no leídas en detalle; contienen la integración de cobro).

---

## 18. Estado SUNAT, health, bridge, alias

### 18.1 GET /api/v1/sunat/estado — closure (api.php:48-64), grupo protegido
`SunatCircuitBreaker::getStats(config("facturacion.sunat.{env}.fe"))` (app/Services/Sunat/SunatCircuitBreaker.php:99-106; estados closed|open|half_open, caché). 200 **sin `mensaje`**: `{"estado":"exito","sunat":{"disponible": state==="closed","circuit":"closed"|"open"|"half_open","fallas":int,"entorno":"beta"|"production"}}` (clave `sunat`, no `datos`).

### 18.2 GET /api/health — HealthController (__invoke)
Caché 15 s `ops:health`. Checks (app/Support/Ops/HealthChecks.php:32-40): "Base de datos" (detalle = driver), "Redis" (si se usa), "Disco" (`N% libre`, aviso si bajo; nunca 503), "Almacenamiento", "SUNAT beta", "SUNAT produccion" (open → falla, half_open → aviso; no 503). 
Respuesta: HTTP 200 (`ok`/`degradado`) o 503 (`caido`: falla de BD/Redis/almacenamiento):
`{"estado":"ok"|"degradado"|"caido","timestamp":ISO,"checks":[{"componente","estado":"ok"|"aviso"|"falla","detalle"}]}` (sin `mensaje`/`datos`).

### 18.3 Bridge SaaS — `app/Http/Controllers/Auth/SaasAuthController.php` (sin middleware; header `X-Bridge-Key` == `config('app.saas_bridge_secret')`, comparación `!==` no constante; vacío o distinto → **403 `{"error":"No autorizado"}`** — formato distinto al estándar)
- **POST /api/bridge/auth** (generateToken, l.175-201): `email` required|email; `name` required|max:255. Token = base64(`hmac_sha256(payload, secret)|payload`), payload `{"email","name","ts"}`. 200 `{"redirect_url": route('auth.from-saas',{token}) → /auth/from-saas?token=...,"expires_in":600}`. El login web (GET /auth/from-saas, web.php:70) valida HMAC y TTL 600 s, crea/usa User y sesión.
- **POST /api/bridge/provision** (l.33-142): `email` required|email; `name` nullable|max:255; `ruc` required|size:11; `razon_social` required|max:255; `sol_user` required|max:20; `sol_pass` required|max:50; `certificate` nullable|file|max:4096; `certificate_password` nullable|max:255. Crea/busca User por email, Tenant por RUC (nuevo: beta, free, activo; `razon_social` solo si no tenía), guarda SOL, certificado (PEM `cert.pem`) y crea series F001, FC01, FD01, B001, BC01, BD01 si no existen. 200 `{"api_key","api_secret": getRawOriginal('api_secret') (valor CIFRADO en BD),"ruc","razon_social","environment"}`. Error de certificado → `back()->withErrors()` (redirect en una API JSON).
- **DELETE /api/bridge/tenant** (l.144-168): `ruc` required|size:11. Ejecuta `SET FOREIGN_KEY_CHECKS=0` (MySQL) + `forceDelete` + `=1`. 200 `{"success":true}`; excepción → 500 `{"error":"Error al eliminar tenant: ..."}`.

### 18.4 Alias `/api/sunat/*` (api.php:391-409) — MW: resolve.tenant, throttle:api únicamente
| Método | URL | Destino | Nota |
|---|---|---|---|
| GET | /api/sunat/configuracion | TenantController@show | = GET /v1/empresa |
| POST | /api/sunat/configuracion | TenantController@update | = PUT /v1/empresa (en POST) |
| GET | /api/sunat/facturas | InvoiceController@index | (otro analista) |
| POST | /api/sunat/facturas | InvoiceController@store | **sin license ni check.limit** |
| GET | /api/sunat/facturas/{id}/pdf, /xml | InvoiceController@pdf/xml | |
| POST | /api/sunat/facturas/{id}/anular | VoidedController@store | `{id}` ignorado; exige body `fecha_generacion`+`detalles` (StoreVoidedRequest); sin license/check.limit |
| GET | /api/sunat/boletas | BoletaController@index | |
| POST | /api/sunat/boletas | BoletaController@store | sin license/check.limit |
| GET | /api/sunat/boletas/{id}/pdf, /xml | BoletaController@pdf/xml | |
| POST | /api/sunat/boletas/{id}/anular | VoidedController@store | siempre falla para boletas (tipo 03 → 422 `boletas_no_soportadas_en_ra`); `{id}` ignorado |

---

## 19. Inventario resumido `routes/web.php`, `routes/settings.php`, `routes/console.php`

(Panel Inertia/React, sesión web — **no forma parte del contrato REST**; listado para decidir alcance.)

### 19.1 web.php
- `GET /` (l.40-61): si `Accept` contiene `text/html` → redirect a dashboard/login/`/register`; si no → JSON `{"api":"SUNAT API","version":"v1","status":"ok","docs": url('/api/v1')}`. **Este JSON sí podría considerarse contrato** (clientes curl).
- `GET /styleguide` (Inertia).
- `GET /auth/from-saas` → SaasAuthController@login (bridge web).
- **`GET /limpiar-cache-ahora`** (l.74-95) y **`GET /ver-errores`** (l.97-108): SIN autenticación (ver obs. de seguridad). No migrar.
- Grupo `auth, verified`:
  - `GET /dashboard`.
  - Prefijo `/sunat` (nombres `sunat.*`): dashboard, empresa-activa (PUT), configuración (GET/PUT, probar, consulta-token), facturas/boletas (index/nueva/store), documentos/{tipo}/{id}/pdf, historial (index, pdf/xml/cdr/respuesta, reenviar, anular, anulación xml|cdr), comprobantes/{01|03}/{id}/editar|actualizar, nota-credito y nota-debito (index/nueva/store/editar/actualizar), anulaciones (nueva/store), resumenes (index/nueva/store/refrescar/descargar xml|cdr), guias (index/nueva/store/editar/actualizar), retenciones, percepciones, reversiones (nueva/store), **nota-venta** (index/nueva/store) y **cotizaciones** (index/nueva/store/estado PATCH/convertir/DELETE) [fuera de alcance], clientes (CRUD + buscar-ruc), buscar-cliente, buscar-documento, ubigeo (regiones/provincias/distritos → mismo UbigeoController API), mi-api-key, series (CRUD + toggle), sucursales (CRUD + toggle), consulta-cpe (+buscar), exportar (+descargar), reportes (+registro-ventas), sire (index, activar, desactivar, periodos, propuesta, aceptar, preliminar, reconciliar, tickets, tickets/{n}/refrescar|archivo), equipo (CRUD + toggle).
- Grupo `auth, admin, panel.write`, prefijo `/admin`: empresas (CRUD, toggle, regenerar-credenciales), sucursales/series anidadas por empresa, logs-envios, bitacora, comprobantes por empresa (index, respuesta, reenviar, anular, download xml|cdr|pdf|anulacion-xml|anulacion-cdr), sucursales/series globales, usuarios (can:manage-users), planes (CRUD + toggle), configuración global (+consulta-token, branding), buscar-ruc, licencia (edit/activar).
- `require settings.php`. Login/registro/2FA/reset los registra **Laravel Fortify** (`config/fortify.php`), no aparecen en routes/*.

### 19.2 settings.php (auth)
`GET settings` → redirect `/settings/profile`; `GET/PATCH settings/profile`; (auth+verified) `DELETE settings/profile`, `GET/PUT settings/password` (throttle 6,1), `settings/appearance` (Inertia), `GET settings/two-factor`.

### 19.3 console.php (scheduler) — tareas de fondo que la migración debe replicar
| Tarea | Frecuencia |
|---|---|
| `inspire` (demo) | — |
| `partitions:create --months=3` | mensual día 1 02:00 |
| `logs:purge --days=90` (api_logs) | domingos 03:00 |
| Job `ProcessRecurringPayments` | diario 06:00 |
| Job `CheckTrialExpiration` | diario 07:00 |
| Job `ResetMonthlyUsage` | día 1 00:05 |
| `sire:poll-pending` | cada minuto, withoutOverlapping, background |
| `sire:reconcile-all` | diario 03:00 |
| `sunat:reintentar-pendientes` (facturas/boletas pendiente/enviado) | cada 15 min |
| `summaries:poll-pending` (tickets de resúmenes) | cada 5 min |
| `backup:clean` / `backup:run` / `backup:monitor` (spatie) | 01:00 / 01:30 / 09:00 |
| `certificados:verificar --alertar` | diario 08:00 |
| `ops:jobs-fallidos --alertar` | cada hora |
| `ops:salud --alertar` | cada 10 min |

Otros comandos artisan existentes (app/Console/Commands): CreateTenantCommand, License*, MakeAdmin, ManagePartitions, MigrarComprobantesAS3, MoverComprobantesAPrivado, Reconsultar/Reparar* (bajas, boletas resumen, rechazos), RegenerarPdfs, ClearPdfCache, SunatCircuitCommand, TestLookup, VerAuditoria, Sire/*.

---

## 20. Dependencias con Notas de Venta / Cotizaciones (excluir o adaptar)

| Elemento | Dependencia | Acción sugerida |
|---|---|---|
| `POST /cotizaciones`, `POST /notas-venta` con `check.limit:internal` (api.php:215, 223) | Única ruta que usa la categoría `internal` (cuenta `internal_documents`) | Excluir junto con NV/COT; la categoría `internal` y `internal_documents_month` de CheckDocumentLimit/EmissionPolicyService pueden no migrarse |
| `notas-venta/{id}/pagos` (api.php:228-230) | PaymentController con docType notas-venta | Excluir (otro analista cubre pagos) |
| `GET /reportes/documentos-internos` | 100 % internal_documents | **Excluir** |
| `GET /reportes/por-cliente` | Incluye COT/NV en `detalle_documentos` y pagos de NV en `historial_pagos` | Adaptar (omitir tipos COT/NV) — cambia el contrato; documentar |
| `GET /reportes/cobranzas` | `resumen_por_metodo` suma todos los `payments` (incluye NV) | Adaptar o documentar diferencia |
| `GET /panel` | `notas_venta{count,total}` + NV en gráfico, ventas/documentos actuales | Adaptar: mantener clave con ceros o quitarla; excluir NV de agregados |
| `ReportService` (InternalDocument import) | métodos documentosInternos/porCliente | Adaptar |
| Web `/sunat/nota-venta*`, `/sunat/cotizaciones*` | Panel | Fuera de alcance |
| `PlanService`/`UsageWarningHeader` | No dependen de NV (usa `documents_this_month`) | — |
| `GET /panel/cobranzas`, `/panel/*` restantes, `/comprobantes/exportar-zip` | No dependen de NV | — |

---

## 21. Observaciones / posibles bugs

### Críticos / seguridad
1. **Recuperación de credenciales rota**: `verificar` guarda `api_secret = sha256(raw)` y devuelve `raw` (CredentialRecoveryController.php:109-120), pero ResolveTenant compara el valor almacenado en claro (ResolveTenant.php:44) → las credenciales recuperadas **nunca autentican**. (En `regenerarCredenciales` se corrigió explícitamente, TenantController.php:170-174.)
2. **Bridge provision devuelve el api_secret cifrado**: `getRawOriginal('api_secret')` (SaasAuthController.php:137) devuelve el sobre `encrypted` de Laravel, no el secreto → el SaaS recibe un valor que no autentica (salvo que lo descifre con la APP_KEY). Además, error de certificado devuelve `back()->withErrors()` en método tipado `JsonResponse` → TypeError → 500.
3. **Bridge delete usa `SET FOREIGN_KEY_CHECKS`** (MySQL) — en PostgreSQL (los comentarios indican producción en Postgres, VoidedService.php:303-308) falla siempre → 500. Comparación de `X-Bridge-Key` con `!==` (no tiempo constante) y sin throttle en `bridge/*`.
4. **Rutas web públicas peligrosas**: `GET /limpiar-cache-ahora` (ejecuta `optimize:clear` y crea un tenant con datos personales hardcodeados) y `GET /ver-errores` (muestra las últimas 200 líneas de `laravel.log`) sin autenticación (web.php:74-108). No migrar.
5. **Alias `/api/sunat/*` evitan `license` y `check.limit:sunat`** (POST facturas/boletas/anular) e idempotencia/logs/usage headers (api.php:391-409) → bypass del cupo del plan y de la licencia.
6. **`POST /registro` público permite elegir `plan` (pro/business) y `entorno=production`** sin pago (RegisterController.php:30-31, 88). `max_documents_month` usa `config('facturacion.plans')` que ya no existe → siempre 20.
7. `POST /sire/activar` devuelve `token_preview` (20 primeros caracteres del token OAuth SUNAT); `debug/lookup` expone 12 caracteres del token del proveedor externo y no tiene auth.
8. `LogApiRequest` no enmascara `client_secret` (request) ni el `api_secret` devuelto en respuestas (regenerar/recuperar/registro) → queda en `api_logs`. Idempotency también guarda esas respuestas.
9. `config/facturacion.php:41-42` contiene **credenciales GRE beta por defecto hardcodeadas** (client_id/secret de test). No se copian aquí; no replicar como default.
10. `consultar-cpe` 422 de RuntimeException y mensaje "sin email" en recuperar revelan existencia del tenant (enumeración por RUC).

### Lógica SUNAT
11. **`consultar-cdr` usa la API equivocada**: llama `See::getStatus(ticket)` con `"{ruc}-{tipo}-{serie}-{numero}"` como ticket contra `billConsultService` (ConsultCdrAction.php:155-163) en vez de `ConsultCdrService::getStatusCdr(...)`. Además, si hubiera éxito, `cdr_zip` binario dentro del JSON rompería `json_encode` → 500. Reimplementar correctamente (getStatusCdr / getStatus por comprobante) y decidir si `cdr_zip` va en base64.
12. **Anulación por resumen: boletas quedan `anulado` aunque SUNAT rechace**: SendSummaryToSunat marca `anulado` al recibir ticket (SendSummaryToSunat.php:87-93); CheckSummaryTicketStatus en error definitivo solo revierte las `anulacion_en_proceso` (l.140-143) — ya no existen. (La rama "success+not accepted" sí revierte a `aceptado` sin filtro.) Mismo patrón en **RR**: SendReversionToSunat marca 20/40 `anulado` al recibir ticket (l.68-78) y la reversión rechazada solo revierte `anulacion_en_proceso`.
13. Resumen de anulación ignora `anular.*.tipo_documento` (07/08) y `motivo` (solo boletas por id) — validación acepta 07/08 pero no tiene efecto.
14. `generateCorrelativo` de resúmenes: el lock se libera tras calcular el máximo, **antes** del `Summary::create` (SummaryController.php:403-404) → carrera posible (el unique index es la red; si la migración no creó el índice por duplicados previos, no hay protección).
15. RA: correlativo `max(correlativo)` por fecha sin lock (VoidedService.php:60-65) y mezcla RA+RR del mismo día (RR filtra por prefijo, RA no) → posibles huecos/duplicados. `fecha_comunicacion` default con `now()` en **UTC** (VoidedController.php:34; VoidedService.php:46) → tras las 19:00 Lima toma el día siguiente.
16. `ReversionController` usa `whereJsonContains('detalles', {objeto})` para anti-duplicado (l.139-147) — en PostgreSQL no encuentra objetos dentro de arrays (el propio VoidedService documenta el bug y lo corrigió solo allí) → anti-duplicado RR inefectivo. Código RR duplicado entre controlador y `VoidedService::crearReversion`.
17. `RR` `consulta_estado` apunta a `/api/v1/voided/{id}/status` (inexistente) y usa claves `voided_id`/`sunat_status` (RA usa `id_anulacion`/`estado_sunat`).
18. `GET /anulaciones/{id}/estado` con RR aceptada no marca 20/40 como anulados (updateOriginalDocuments solo 01/03/07/08); depende del job.
19. StoreVoidedRequest acepta tipos 20/40 que VoidedService rechaza ("no soportado para anulación"); mensaje correcto sería usar /reversiones.
20. Guía `enviar` no limpia `ticket`: si había ticket previo, `checkStatus` podría consultar el ticket viejo; el job lo sobreescribe solo si el nuevo envío tiene éxito.
21. Guía `update` valida con StoreDispatchGuideRequest: `tipo_documento` default 09 → al editar una GRT sin enviar `tipo_documento=31` se aplican reglas GRR y la validación de serie tipo 09 falla; `validarCorrelativoManual` marca duplicado si se reenvía el mismo `correlativo` de la propia guía; no actualiza remitente, doc_relacionado, indicadores, *_ruc, *_cod_local, subcontratador, pagador, autorizaciones (los cambios se pierden silenciosamente); serie/correlativo enviados se ignoran.
22. Reglas duplicadas en StoreDispatchGuideRequest (`vehiculo.nro_circulacion`, `vehiculo.tuc`, `vehiculo.secundarios.*.nro_circulacion`): la última definición elimina los regex alfanuméricos (l.125-129 vs 182-186). Replicar el comportamiento efectivo (max:15) o corregir conscientemente.
23. DispatchGuideResource no expone `tipo_documento` (09/31), remitente ni doc_relacionado → el cliente no puede distinguir GRR/GRT en listados.
24. Retención/percepción: `formatX` en store no incluye `documentos`; mensajes de store apuntan a rutas en inglés inexistentes (`/retentions/{id}/enviar`, `/perceptions/{id}/enviar`). No hay PDF de percepción.
25. Conteo de cupo `sunat` no incluye retenciones, percepciones, resúmenes ni bajas aunque pasan por `check.limit:sunat` e `incrementUsage` (EmissionPolicyService.php:105-131) → inconsistencia entre `X-Usage-Documents`/`documentos_este_mes` y el límite real.

### Reportes / panel
26. **Rango de fechas sin hora en ReportService**: `queryDocuments` (`whereBetween('fecha_emision',[desde,hasta])`), `documentosInternos` y `topProductos` comparan una columna DATETIME con fechas `Y-m-d` → excluyen los documentos del **último día** (después de 00:00:00). `por-cliente`/`por-sucursal` sí usan `fechas()` con 23:59:59. Afecta registro-ventas, consolidado, notas, cobranzas.
27. Panel y KPIs usan `now()` en **UTC** (config/app.php:68) → "hoy", "ayer", "mes" desfasados 5 h respecto a Lima.
28. `/panel` compara ventas actuales (F+B+NV) contra anteriores (solo F+B); totales por tipo no excluyen rechazados/anulados (otros endpoints sí).
29. `/panel/estado-sunat` `por_tipo` se serializa como `[]` vacío u objeto según haya datos; `periodo` incluye horas. `indicadores` repite 6 consultas para `crecimiento` (rendimiento).
30. `ReportRequest.sucursal_id` usa `exists:sucursales,id` sin tenant y `queryDocuments` hace `Sucursal::find` sin tenant → se puede filtrar por `cod_local` de sucursal ajena (leak mínimo). Igual en `series` store/update (`exists` global; luego se valida tenant).
31. `ventasConsolidado.ticket_promedio` divide total (F+B+ND) entre (F+B); `granTotal` de registro-ventas suma NC como positivo.
32. ExportController: `tempnam()` crea un archivo extra (el `.zip` es otro nombre) que nunca se borra; `ob_flush()` sin buffer puede emitir notice; nombres de archivo con pad 6.

### Contrato / consistencia (replicar tal cual o decidir)
33. Paginación heterogénea: `datos.datos+datos.paginacion` (guías, clientes, retenciones, percepciones), `datos+paginacion` en raíz sin `mensaje` (resúmenes), `datos+meta` (anulaciones), paginator Laravel crudo (SIRE comprobantes/tickets), `total/pagina/por_pagina` en raíz (catálogo). Parámetros de tamaño: `por_pagina` vs `per_page` (anulaciones, retenciones, percepciones, catálogo).
34. Respuestas sin `mensaje`: resúmenes (index, estado), anulaciones (index/show/estado), catálogos, `sunat/estado` (usa `sunat` en vez de `datos`), health. Bridge usa `{"error":...}`/`{"success":true}`.
35. `buscar-documento`: `fuente` (local) vs `source` (externo). Planes: precios decimal → string JSON. SireComprobante montos decimal → string.
36. `GET /catalogos/producto-sunat/{codigo}` con código no 8 dígitos → 404 global (el 422 del controlador es código muerto).
37. `POST /clientes` devuelve 201 también cuando actualiza (updateOrCreate). `PUT /sucursales/{id}` devuelve `total_series: 0`. `DELETE /clientes|/sucursales` borrado físico; `DELETE /series` lógico; `DELETE /empresa` forceDelete inmediato.
38. `PUT /empresa`: no permite vaciar campos (array_filter descarta null); `sire_client_id/secret` no configurables por API.
39. Parámetros `{id}` no numéricos → 500 (TypeError) en lugar de 404.
40. `GET /resumenes/{id}/estado` no consulta SUNAT (a diferencia de guías y anulaciones, que sí consultan en línea).
41. `xml` de resumen expone la ruta interna de almacenamiento en el mensaje 404.
42. `checkStatus` de guías devuelve 202 con `"estado":"error"` para errores transitorios (0/187/401) y 202 con `"estado":"pendiente"` sin ticket — valores de `estado` fuera del par exito/error.
