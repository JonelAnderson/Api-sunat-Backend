# 03 — Autenticación, autorización, multiempresa, seguridad, configuración y despliegue (API-PRO)

> Fase 0 (análisis) — fuente: `C:\laragon\www\API-PRO` (rama `main`, HEAD `62952c3`). Solo lectura.
> Todas las rutas de archivo son relativas a la raíz de API-PRO. Formato `archivo:línea`.
> No se copian valores secretos (solo nombres de variables / claves).

**Aviso importante:** existe una rama local `fix/security-hardening` (merge-base `1a24171`) con 20+ correcciones de seguridad documentadas en `SEGURIDAD-REPO.md` que **NO están en `main`**. Este documento describe el comportamiento de `main` (lo que hoy corre) y, donde aplica, indica la diferencia con la rama endurecida (marcado **[rama-sec]**). Recomendación para la migración: decidir explícitamente qué comportamiento replicar; lo razonable es implementar el comportamiento endurecido (ver §8).

---

## 1. Pipeline HTTP y middlewares

### 1.1 Configuración global (`bootstrap/app.php`)

| Elemento | Detalle | Ref |
|---|---|---|
| Rutas | `web.php`, `api.php` (prefijo `/api`), `console.php`, health `/up` | `bootstrap/app.php:19-24` |
| Trusted proxies | `at: '*'` con X-Forwarded-For/Host/Port/Proto/AWS_ELB | `:31-38` — **[rama-sec]** solo rangos privados RFC1918+loopback o `TRUSTED_PROXIES` (CSV) |
| Cookies sin cifrar | `appearance`, `sidebar_state` | `:40` |
| Grupo `web` (append) | `HandleAppearance`, `HandleInertiaRequests`, `AddLinkHeadersForPreloadedAssets`, `EnsureFirstUserRegistration` | `:42-47` |
| Global prepend | `ForceJsonAccept` y luego `HandleCors` (HandleCors queda primero) | `:67,70` |
| Global append | `SecurityHeaders` | `:73` |
| Excepciones | Sentry `Integration::handles` + render JSON para `api/*` o `expectsJson()` | `:75-163` |

**Aliases de middleware** (`bootstrap/app.php:49-61`):

| Alias | Clase |
|---|---|
| `resolve.tenant` | `App\Http\Middleware\ResolveTenant` |
| `log.api` | `LogApiRequest` |
| `check.limit` | `CheckDocumentLimit` (parámetro `sunat`\|`internal`) |
| `license` | `CheckLicense` |
| `plan` | `CheckPlanLimit` (`plan:feature:<x>` / `plan:usage:<x>`) |
| `usage.headers` | `UsageWarningHeader` |
| `idempotency` | `EnsureIdempotency` |
| `sire.enabled` | `EnsureSireEnabled` |
| `admin` | `EnsureAdmin` |
| `panel.write` | `EnsurePanelWrite` |
| `tenant.member` | `EnsureTenantMember` (**definido pero no usado en ninguna ruta**) |

### 1.2 Formato unificado de errores API (contrato) — `bootstrap/app.php:85-162`

Aplica a `api/*` o cuando `expectsJson()`. Siempre `{"estado":"error","mensaje":...}`:

| Excepción | HTTP | Cuerpo exacto |
|---|---|---|
| `ValidationException` | 422 | `{"estado":"error","mensaje":"Error de validación","errores":{campo:[msgs]}}` |
| `ModelNotFoundException` / `NotFoundHttpException` | 404 | `{"estado":"error","mensaje":"Recurso no encontrado."}` |
| `AuthenticationException` | 401 | `"No autenticado."` |
| `AuthorizationException` | 403 | `"No autorizado."` |
| `MethodNotAllowedHttpException` | 405 | `"Método HTTP no permitido para esta ruta."` |
| `ThrottleRequestsException` | 429 | `"Demasiadas solicitudes. Intenta de nuevo más tarde."` (ver obs. sobre cabeceras perdidas) |
| Otro `HttpExceptionInterface` (abort) | su código | `mensaje` = `getMessage()` o `"Error."` |
| Fallback | 500 | `"Error interno del servidor."`; si `APP_DEBUG`: + `excepcion`, `detalle`, `archivo` |

Respuestas de éxito vía trait `App\Http\Traits\ApiResponse` (`app/Http/Traits/ApiResponse.php:21-72`): `{"estado":"exito","mensaje":"OK","datos":...,"meta"?}`; `created()` → 201 con mensaje `"Creado exitosamente"`; `error()` → `{"estado":"error","mensaje","errores"?}` (default 400); `errorAccionable()` añade `codigo_error` y `siguiente_accion` (default 422).

### 1.3 Grupos de rutas API (`routes/api.php`)

| Grupo | Middlewares (en orden) | Ref |
|---|---|---|
| `GET /api/health` | `throttle:api` | `routes/api.php:34` |
| Público `/api/v1`: `POST registro`, `GET planes`, `POST credenciales/recuperar`, `POST credenciales/recuperar/verificar` | `throttle:api` | `:37-42` |
| Protegido `/api/v1/*` | `resolve.tenant` → `idempotency` → `throttle:api` → `log.api` → `usage.headers` | `:45` |
| Emisión (POST facturas, facturas/masivo, boletas, notas-credito, notas-debito, guias-remision, guias-remision-transportista, resumenes, anulaciones, retenciones, percepciones, reversiones) | + `license`, `check.limit:sunat` | `:69-182` |
| Cotizaciones / notas-venta (fuera de alcance) | + `check.limit:internal` | `:215,223` |
| SIRE `/api/v1/sire/*` (salvo activar/desactivar) | + `sire.enabled` | `:260-310` |
| Debug `GET /api/debug/lookup` (solo si `APP_DEBUG=true`) | **sin auth**; expone prefijo del token de lookup | `:322-381` |
| Bridge SaaS `POST bridge/auth`, `POST bridge/provision`, `DELETE bridge/tenant` | **ninguno** (valida `X-Bridge-Key` en controlador) | `:385-387` — **[rama-sec]** `throttle:20,1` + `hash_equals` |
| Alias `/api/sunat/*` (configuracion, facturas, boletas, anular) | solo `resolve.tenant`, `throttle:api` | `:391-409` — **sin license ni check.limit ni idempotency ni log** (SEC-003). **[rama-sec]** mismo stack que v1 + `license`,`check.limit:sunat` en POST |

Notas de orden: los middlewares de grupo corren antes que los de ruta, por lo que `license` y `check.limit` se ejecutan **dentro** de `log.api` (sus rechazos sí se registran). En cambio, los 401 de `resolve.tenant` y las repeticiones de `idempotency` **no** pasan por `throttle` ni `log.api`.

`GET /api/v1/sunat/estado` (closure, `routes/api.php:48-64`): `{"estado":"exito","sunat":{"disponible":bool,"circuit":"closed|open|half_open","fallas":int,"entorno":"beta|production"}}` usando el endpoint `facturacion.sunat.{env}.fe`.

### 1.4 `resolve.tenant` — `app/Http/Middleware/ResolveTenant.php`

Flujo exacto:
1. Lee **solo cabeceras** `X-Api-Key` y `X-Api-Secret` (no query string) — `:18-19`.
2. Falta alguna → **401** `{"estado":"error","mensaje":"Las cabeceras X-Api-Key y X-Api-Secret son requeridas."}` — `:21-26`.
3. Busca tenant por `api_key` (igualdad exacta, columna en claro, UNIQUE) cacheado `Cache::remember("tenant:key:{$apiKey}", 600, ...)` — `:28-30`. Cachea el modelo Eloquent completo 10 min. (**[rama-sec]** TTL 60 s.) Nota: `Tenant` usa `SoftDeletes`, así que tenants borrados lógicamente no se encuentran.
4. No existe → **401** `"Credenciales de API inválidas."` — `:32-37`.
5. Compara `hash_equals((string)$tenant->api_secret, $apiSecret)` (el cast `encrypted` descifra el valor guardado; comparación en claro, timing-safe) — `:43-44`.
6. `DecryptException` (valor no cifrado con la APP_KEY actual) → **401** `"No se pudo validar el API Secret de la empresa (credencial ilegible). Regenérela desde el panel: Configuración → Credenciales → Regenerar."` — `:45-50`.
7. Secret distinto → **401** `"Credenciales de API inválidas."` — `:52-57`.
8. `is_active=false` → **403** `"Empresa desactivada. Contacte al administrador."` — `:59-64` (se comprueba DESPUÉS de validar el secret).
9. `$request->merge(['tenant' => $tenant])` — el tenant viaja como input `tenant` de la request (`$request->get('tenant')`) — `:66`. **[rama-sec]** además `CurrentTenant::setId()` para global scope.

**Generación y almacenamiento de credenciales**

| Momento | api_key | api_secret | Ref |
|---|---|---|---|
| Alta (`Tenant::creating`) si vacíos | `Str::random(64)` | `hash('sha256', Str::random(64))` → 64 hex (ese hex ES el secret que usa el cliente) | `app/Models/Tenant.php:133-140` |
| `POST /api/v1/empresa/credenciales/regenerar` | `Str::random(64)` | `Str::random(64)` (en claro, cifrado por cast) | `app/Http/Controllers/Api/V1/TenantController.php:164-193` |
| Admin `POST admin/empresas/{t}/regenerar-credenciales` | `Str::random(64)` | `Str::random(64)`, cifrado a mano con `Crypt::encryptString` vía query builder (evita DecryptException en dirty-check) | `app/Http/Controllers/Admin/TenantController.php:427-...` |
| Recuperación por email (`credenciales/recuperar/verificar`) | `Str::random(64)` | **guarda `hash('sha256',$raw)` pero devuelve `$raw`** → el secret devuelto NO autentica (BUG-01, corregido solo en rama-sec) | `app/Http/Controllers/Api/V1/CredentialRecoveryController.php:109-122` |
| Bridge `provision` | (del modelo) | devuelve `getRawOriginal('api_secret')` = **sobre cifrado**, no el secret en claro → inutilizable | `app/Http/Controllers/Auth/SaasAuthController.php:135-141` |

- Almacenamiento: `api_key` VARCHAR(64) UNIQUE en claro (`database/migrations/2026_03_04_000001_create_tenants_table.php`); `api_secret` cast `encrypted` (AES-256-CBC de Laravel con `APP_KEY`, sobre JSON base64 ~312 chars) — `app/Models/Tenant.php:89-92`.
- Migración `2026_10_03_120000_encrypt_tenant_api_secret.php`: amplía `api_secret` a TEXT (`:18-20`) y cifra con `Crypt::encryptString` los valores en claro existentes, idempotente (si `decryptString` no falla, salta) (`:22-38`). `down()` descifra y vuelve a VARCHAR(64).
- Decisión documentada (SEC-009, `SEGURIDAD-REPO.md`): el secret es **reversible a propósito** porque también es la clave HMAC de firma de webhooks (ver §6.6). Las implementaciones nuevas deben guardar el secret cifrado reversible (no hash) para mantener compatibilidad.
- Compatibilidad de cifrado entre lenguajes: el formato es el de Laravel `Crypt` (`base64(json{iv,value,mac,tag})`, AES-256-CBC + HMAC-SHA256 con APP_KEY `base64:`). Si las 7 tecnologías comparten BD, deben implementar este formato (también para `sol_user`, `sol_pass`, `client_secret`, `certificate_password`, `consulta_token`, `sire_client_secret`). `encrypted` cast en Laravel 11+ serializa con `encryptString` (sin serialize PHP) — verificar.
- `GET /api/v1/empresa/credenciales` devuelve `api_key` en claro y `api_secret` = texto fijo `"*** oculto — use POST /empresa/credenciales/regenerar para obtener uno nuevo ***"` — `TenantController.php:154-162`.
- Invalidación de caché `tenant:key:{api_key}`: API TenantController update/logo/regenerar/cert/destroy (`:117,144,181,233,260`), Admin TenantController (`:324,359,413,431`), recuperación (`:107`). **No** invalidan: panel `/sunat/configuracion` (Web ConfiguracionController), activación SIRE (`SireActivacionController.php:36,59`, `Web/Sunat/SireController.php:66`), contadores de uso.

### 1.5 `idempotency` — `app/Http/Middleware/EnsureIdempotency.php`

- Aplica a métodos `POST, PUT, PATCH, DELETE` con cabecera `Idempotency-Key` y tenant resuelto; si no, pasa — `:25,29-34`.
- Clave truncada a 255 chars — `:36`. Huella: `sha256(METHOD|path|rawBody)` — `:37`.
- Tabla `idempotency_keys` (`database/migrations/2026_10_03_120100_create_idempotency_keys_table.php:79-90`): `id, tenant_id, idempotency_key(255), request_hash(64), response_status smallint null, response_body longtext null, created_at`; UNIQUE(`tenant_id`,`idempotency_key`); índice `created_at`.
- Existe registro:
  - hash distinto → **422** `{"estado":"error","mensaje":"La Idempotency-Key ya se usó con un cuerpo distinto."}` — `:45-50`
  - con respuesta guardada → replay: mismo cuerpo y status, `Content-Type: application/json`, `Idempotency-Replayed: true` — `:51-55`
  - sin respuesta (en curso) → **409** `"Ya hay una solicitud en proceso con esa Idempotency-Key."` — `:57-60`
- No existe: INSERT reserva; si falla por UNIQUE (concurrencia) → 409 mismo mensaje — `:65-79`.
- Tras ejecutar: status < 500 → guarda status+body y añade `Idempotency-Replayed: false` — `:85-89`; ≥500 o sin body → DELETE (permite reintento) — `:90-94`.
- **TTL: no existe.** No hay purga programada (no está en `routes/console.php`; sólo el índice "para purga") → las claves valen para siempre y la tabla crece sin límite.
- El replay no conserva cabeceras originales (p. ej. `Content-Type` de PDF/XML, `X-Usage-*`), siempre fuerza `application/json`.

### 1.6 `throttle:api` — `app/Providers/AppServiceProvider.php:173-213`

- Limiter `api` (`:175-191`): si `config('services.internal_token')` y `X-Internal-Token === token` (comparación `===`, **[rama-sec]** `hash_equals`) → sin límite. Si no, límite por minuto según **columna `tenants.plan`** (no la suscripción activa): `free`=30, `pro`=120, `business`=300, otro=30. Clave: `tenant.id` o IP si no hay tenant (rutas públicas).
- Respuesta al exceder: 429 JSON del handler (§1.2).
- Otros limiters: `sunat-sire` (jobs SIRE, `config('sire.rate_limit.per_tenant_per_minute')`=30, clave `sire:{tenantId}`) `:195-202`; `sunat-tenant` (jobs `SendDocumentToSunat`, `facturacion.throughput.per_tenant_per_minute` = env `SUNAT_PER_TENANT_PER_MINUTE` default 120, clave `sunat:{tenantId}`) `:207-212`.
- Fortify: `login` 5/min por `lower(email)|ip`; `two-factor` 5/min por `session('login.id')` — `app/Providers/FortifyServiceProvider.php:351-361`. `PUT settings/password` `throttle:6,1` (`routes/settings.php:20-22`).
- Recuperación de credenciales: limitador manual 3 intentos/10 min por IP; al exceder → 429 `"Demasiados intentos. Intenta de nuevo en {s} segundos."` (`CredentialRecoveryController.php:28-33`).
- No existe `RouteServiceProvider`.

### 1.7 `log.api` — `app/Http/Middleware/LogApiRequest.php`

- Mide duración ms; tras la respuesta, `dispatch(fn => ApiLog::create(...))->afterResponse()` (sin cola) — `:15-51`.
- Campos guardados en `api_logs` (`database/migrations/2026_03_04_000007_create_api_logs_table.php:155-169`): `tenant_id` (FK nullOnDelete), `endpoint` (= `$request->path()`), `method`, `request_body` JSON, `response_body` JSON, `status_code`, `ip_address`, `user_agent`(500), `duration_ms`, `created_at`. Índice (`tenant_id`,`created_at`). Purga: `logs:purge --days=90` semanal (`routes/console.php:15`; `app/Console/Commands/PurgeOldApiLogs.php:33-35`).
- `request_body` = `$request->except(['tenant'])`, saneado (`:60-78`): `UploadedFile` → `{"_file":true,"name","size","mime"}`; claves enmascaradas a `***` (recursivo, por nombre de clave, solo si no vacías): `contrasena_certificado`, `sol_pass`, `password`, `api_secret`. **[rama-sec]** añade `client_secret`, `sire_client_secret`, `consulta_token`, `client_id`.
- `response_body`: si ≤4096 bytes → JSON decodificado completo (**sin enmascarar**); si mayor → `{"estado","mensaje","_truncated":true,"_size":n}` — `:22-34`.

### 1.8 `usage.headers` — `app/Http/Middleware/UsageWarningHeader.php`

Post-respuesta, si hay tenant (`:16-49`), plan = `PlanService::getActivePlan()` (caché 300 s, `app/Services/Plan/PlanService.php:17-33`; sin suscripción activa → plan `free` de BD o uno construido):
- Si `documents_month` (default 30) ≠ -1: `X-Usage-Documents: {documents_this_month}/{limite}`; si ≥80 %: `X-Usage-Warning: approaching_limit` y `X-Usage-Remaining: max(0, limite-actual)`.
- Si `ai_messages_month` (default 10) ≠ -1: `X-Usage-AI: {ai_messages_this_month}/{limite}`.
- Siempre `X-Plan: {slug}`.
- `documents_this_month` sale del Tenant **cacheado** por ResolveTenant (hasta 600 s desfasado) y es el contador de columna (no el conteo real que usa `check.limit`).

### 1.9 `license` — `app/Http/Middleware/CheckLicense.php` + `app/Services/License/*` + `config/license.php` + `LICENSING.md`

- Tenant `environment === 'beta'` → siempre pasa (`:31-35`).
- Si no, `LicenseClient::check()` (`app/Services/License/LicenseClient.php:39-64`):
  1. `license.enabled=false` **y** `APP_ENV=local` → permitido (en otros entornos el switch se ignora).
  2. Falta `license.key` o `license.secret` → deny `licencia_no_configurada` / `"Falta configurar LICENSE_KEY y LICENSE_SECRET para emitir en producción."`.
  3. Cache `license:ok === true` → allow.
  4. `POST {server_url}/api/v1/licencias/validar` con cabeceras `X-License-Key`, `X-License-Secret`, `Accept: application/json`; body `{machine_id, domain, app_version, fingerprint}`; timeout `license.timeout` (8 s) — `:133-164`. Respuesta JSON `{valida, codigo, mensaje}`.
  5. Hubo respuesta HTTP (cualquier código, incluido 5xx): `valida=true` → cache `license:ok` `cache_ttl` (3600 s) + `license:last_ok` forever → allow; si no → deny inmediato con `codigo`/`mensaje` del servidor — `:85-98`.
  6. Sin respuesta (`ConnectionException`) → gracia: si `now - last_ok ≤ grace_days(7)*86400` → allow con `offlineGrace` (`gracia_offline`), si no deny `servidor_licencia_inalcanzable` — `:108-125`.
- Respuesta de bloqueo: **403** `{"estado":"error","mensaje":<message>,"codigo_error":<code>}` — `CheckLicense.php:51-55`. En gracia: pasa y añade cabecera `X-License-Warning: <mensaje>` — `:40-46`.
- Segunda barrera: `GreenterService::guardEmisionAutorizada()` lanza `RuntimeException('Emisión no disponible: …')` en producción sin licencia (`app/Services/Greenter/GreenterService.php:45-56`).
- Credenciales de licencia: `.env` (`LICENSE_KEY`, `LICENSE_SECRET`, `LICENSE_DOMAIN`) o BD tabla `settings` (`license_key`, `license_secret` cifrado con `Crypt::encryptString`, `license_domain`), que **pisan** al .env en `AppServiceProvider::configureLicenseFromDb()` (`app/Providers/AppServiceProvider.php:61-83`). Activación: comando `license:activate` o panel `admin/licencia` (`app/Http/Controllers/Admin/LicenciaController.php:63`).
- `MachineId` se genera una vez y persiste en `storage/app/...` (`app/Services/License/MachineId.php:8-20`); `BuildInfo::fingerprint()` = sello de build (anti-filtración, ver `LICENSING.md` "Antes de ofuscar: sellar la copia"). `LICENSING.md` describe ofuscación (ionCube/SourceGuardian) de los archivos críticos de licencia; no afecta al contrato de la API, sí a la estrategia de cada tecnología.
- Servidor de licencias por defecto: `https://agenda.kodevo.es` (`config/license.php:22`).

### 1.10 `check.limit:sunat|internal` — `app/Http/Middleware/CheckDocumentLimit.php` + `app/Services/Plan/EmissionPolicyService.php`

Prioridad de decisión (`EmissionPolicyService.php:36-55`):
1. Setting global `emision_ilimitada_global` → ilimitado.
2. `tenant.environment === 'beta'` → ilimitado.
3. `tenant.emission_mode === 'unlimited'` → ilimitado.
4. Plan: suscripción activa con `current_period_end` pasado → `suscripcion_vencida`; límite `documents_month` (default 30) o `internal_documents_month` (default 20); `plan.is_unlimited` o límite `-1` → ilimitado; si `usados >= max` → `limite_alcanzado` — `:57-82`.
- Conteo (`:88-127`) cacheado 300 s: `sunat` = COUNT por `created_at` del mes en `invoices + boletas + credit_notes + debit_notes + dispatch_guides` (no cuenta retenciones, percepciones, resúmenes, bajas, aunque esos POST pasan por el middleware). `internal` = `internal_documents`.
- Respuestas (`CheckDocumentLimit.php:39-80`):
  - Vencida → **403** `{"estado":"error","mensaje":"Tu suscripción venció. Renueva el plan para seguir emitiendo comprobantes.","codigo_error":"suscripcion_vencida","plan_actual":slug,"recurso":"documents_month|internal_documents_month"}`.
  - Cupo → **429** `{"estado":"error","mensaje":"Has alcanzado el limite de documentos SUNAT ({n}/{max} por mes).[ upsell]","codigo_error":"limite_alcanzado","plan_actual","recurso","actual","limite","mejora_plan"}`; upsell: `free` → `" Actualiza a Pro por S/49/mes para más documentos."` + `mejora_plan={"slug":"pro","price":49,"currency":"PEN","trial_days":14}`; `pro` → `" Actualiza a Business por S/99/mes para documentos ilimitados."` + `{"slug":"business","price":99,...}`; otros → `null`. (Nota: "limite" sin tilde en el mensaje; "documentos internos" para internal.)
- `plan:feature:<f>` / `plan:usage:<k>` (`CheckPlanLimit.php:61-118`): 403 `codigo_error":"caracteristica_no_disponible"` / 429 `"limite_alcanzado"`. **No se usa en ninguna ruta actual** de `routes/api.php`.

### 1.11 `sire.enabled` — `app/Http/Middleware/EnsureSireEnabled.php`

- Sin tenant → **401** `{"estado":"error","mensaje":"Empresa no resuelta."}`; `sire_enabled=false` → **403** `{"estado":"error","mensaje":"SIRE no está activado para esta empresa. Llama primero a POST /v1/sire/activar con credenciales válidas.","codigo_error":"sire_no_activado"}` — `:24-37`.

### 1.12 Middlewares web/panel

| Middleware | Comportamiento | Ref |
|---|---|---|
| `ForceJsonAccept` | En `api/*` fuerza `Accept: application/json` | `app/Http/Middleware/ForceJsonAccept.php:18-25` |
| `SecurityHeaders` | `X-Content-Type-Options: nosniff`, `X-Frame-Options: SAMEORIGIN`, `Referrer-Policy: strict-origin-when-cross-origin`, `X-Permitted-Cross-Domain-Policies: none`, `Strict-Transport-Security: max-age=31536000; includeSubDomains` solo si HTTPS; quita `X-Powered-By`. Sin CSP (**[rama-sec]** CSP Report-Only en HTML) | `SecurityHeaders.php:17-36` |
| `HandleAppearance` | Comparte `appearance` (cookie, default `system`) con la vista | `HandleAppearance.php:17-22` |
| `HandleInertiaRequests` | Root view `app`; props compartidas: `name`, `auth.user`, `tenant{id,ruc,razon_social,environment,sol_configurado,logo_url,favicon_url}`, `branding{appLogo,favicon}`, `empresa{rol,esSuperAdmin,disponibles(≤100),can[]}`, `flash{success,error,info,warning,empresa_creada,nuevoCajero,emitido}`, `sidebarOpen` | `HandleInertiaRequests.php:38-106` |
| `EnsureFirstUserRegistration` | Sin usuarios: `/login` → redirect `/register`. Con usuarios: `/register` → 404 | `EnsureFirstUserRegistration.php:24-37` |
| `admin` (`EnsureAdmin`) | Sin user → redirect login; requiere `hasPanelAccess()` **o** `is_admin`; `is_active=false` → 403 | `EnsureAdmin.php:13-33` |
| `panel.write` (`EnsurePanelWrite`) | Métodos seguros pasan; super_admin todo; admin escribe pero DELETE → 403 `"Solo un super administrador puede eliminar."`; soporte solo rutas con sufijo `comprobantes.reenviar`/`comprobantes.anular`; resto 403 `"Tu rol es de solo lectura para esta acción."` | `EnsurePanelWrite.php:30-65` |
| `tenant.member` (`EnsureTenantMember`) | Exige empresa activa (`EmpresaActiva::actual()`) y la pone en `request->attributes['empresa']`. **No aplicado en rutas** | `EnsureTenantMember.php:19-34` |

---

## 2. Autenticación web / panel y RBAC

### 2.1 Fortify (`config/fortify.php`, `app/Providers/FortifyServiceProvider.php`)
- Guard `web`, username `email`, home `/dashboard`, prefix `''`, middleware `['web']` (`config/fortify.php:18,48,76,89,104`).
- Features: `registration`, `resetPasswords`, `emailVerification`, `twoFactorAuthentication(confirm:true, confirmPassword:true)` (`config/fortify.php:146-155`). `User` usa `TwoFactorAuthenticatable` (`app/Models/User.php:14`); columnas 2FA en `2025_08_14_170933_add_two_factor_columns_to_users_table.php`. **`User` no implementa `MustVerifyEmail`** (comentado, `User.php:5`) → `verified` no bloquea nada.
- Registro solo del primer usuario: `CreateNewUser` crea `is_admin=true`, `role=super_admin` si no hay usuarios (`app/Actions/Fortify/CreateNewUser.php:31-40`), luego bloqueado por `EnsureFirstUserRegistration`.
- Vistas Inertia: `auth/login`, `auth/register`, `auth/two-factor-challenge`, etc. (`FortifyServiceProvider.php:320-345`).
- Política de contraseñas en producción: min 12, mayúsc/minúsc, letras, números, símbolos, `uncompromised()` (`AppServiceProvider.php:223-231`). Password cast `hashed` (bcrypt, `BCRYPT_ROUNDS`).
- Login **no** verifica `users.is_active` (no hay `authenticateUsing`): un usuario desactivado puede iniciar sesión (bloqueado luego en admin y en `User::puede()`).
- Ajustes: `routes/settings.php` (perfil, password, apariencia, 2FA).

### 2.2 Bridge SaaS (Zaresk) — `app/Http/Controllers/Auth/SaasAuthController.php`
- Secreto `config('app.saas_bridge_secret')` = env `SAAS_BRIDGE_SECRET` (`config/app.php:126`), cabecera `X-Bridge-Key` comparada con `!==` (no timing-safe); 403 `{"error":"No autorizado"}` (`:35-39,146-150,177-181`).
- `POST /api/bridge/provision`: crea/obtiene User por email y Tenant por RUC (beta/free/activo si nuevo), cert opcional, crea series F001, FC01, FD01, B001, BC01, BD01; responde `{api_key, api_secret(cifrado!), ruc, razon_social, environment}` (`:33-142`).
- `DELETE /api/bridge/tenant`: `SET FOREIGN_KEY_CHECKS=0` + `forceDelete` por RUC (específico MySQL) (`:144-168`).
- `POST /api/bridge/auth`: token = `base64(hmac_sha256(payload, secret) + '|' + payload)` con payload `{"email","name","ts"}`; devuelve `redirect_url` a `/auth/from-saas?token=` y `expires_in: 600` (`:175-200`).
- `GET /auth/from-saas`: valida firma (`hash_equals`) y `ts` ≤ 600 s, `firstOrCreate` User (sin rol), `Auth::login(remember:true)`; token **reutilizable** en la ventana (SEC-016, corregido en rama-sec) (`:207-264`).

### 2.3 Roles globales (`users.role`, `users.is_admin`, `users.is_active`)
`app/Models/User.php:31-145`, migraciones `2026_07_03_151900_add_is_admin_to_users_table.php`, `2026_07_30_000007_add_role_and_active_to_users.php` (is_admin=true → super_admin).

| Rol | Panel admin | Gestión usuarios | Escribir (crear/editar) | Eliminar | Reenviar/anular |
|---|---|---|---|---|---|
| `super_admin` | sí (bypass `Gate::before`) | sí | sí | sí | sí |
| `admin` | sí | sí | sí | no | sí |
| `soporte` | sí | no | no | no | sí |
| `lectura` | sí (solo GET) | no | no | no | no |
| `cliente` | no (dueño de empresa, usa `/sunat`) | — | — | — | — |
| `null` + `is_admin=true` (legado) | sí (lectura; `EnsurePanelWrite` bloquea escrituras) | no | no | no | no |

Gates (`AppServiceProvider.php:88-116`): `Gate::before` super_admin; `manage-users`, `write`, `delete`, `resend`; por empresa `emitir|editar|anular|reenviar|descargar` con args `(User, Tenant, tipo)` → `User::puede("{tipo}.{accion}", $t)`; y `gestionar-clientes`, `gestionar-series`, `gestionar-sucursales`, `ver-reportes`, `exportar`, `consultar-cpe`, `editar-empresa`, `ver-apikey`, `gestionar-equipo`, `gestionar-sire`. Horizon: `viewHorizon` = activo y (super_admin o `is_admin`) (`app/Providers/HorizonServiceProvider.php:26-31`; `config/horizon.php:86` middleware `web,auth`).

### 2.4 Membresías por empresa (`tenant_user`) y abilities
- Tabla `tenant_user` (`2026_08_23_120000_create_tenant_user_table.php`): `tenant_id`, `user_id` (FK cascade), `role` (`owner`|`cajero`, default `cajero`), `abilities` JSON (solo cajero), `is_active`, timestamps, UNIQUE(tenant,user). Backfill owner desde `tenants.user_id`. `Tenant::created/updated` sincroniza owner (`app/Models/Tenant.php:144-165`).
- `TenantMembership::permite()`: inactiva → false; owner → todo; cajero → `in_array(ability, abilities)` (`app/Models/TenantMembership.php:223-251`).
- Catálogo `app/Support/Rbac/Ability.php`: acciones `emitir, editar, anular, reenviar, descargar` × tipos `factura, boleta, nota_credito, nota_debito, guia_remitente, guia_transportista, resumen, anulacion, retencion, percepcion, reversion, cotizacion, nota_venta` (`{tipo}.{accion}`) + módulos `cliente.gestionar, serie.gestionar, sucursal.gestionar, reporte.ver, exportar, consulta.cpe, config.editar, apikey.ver, equipo.gestionar, sire.gestionar` (`:16-141`). Preset cajero: emitir/descargar/reenviar de factura, boleta, NC, ND, cotización, nota de venta + `cliente.gestionar` (`:149-171`). No delegables a cajero: `equipo.gestionar`, `config.editar`, `apikey.ver` (`:178-192`).
- Empresa activa del panel: `App\Services\Tenancy\EmpresaActiva` — sesión `empresa_activa_id`; valida pertenencia (super admin: todas; resto: membresías activas); `actualOFallar()` → 403 `"No tienes una empresa asignada. Contacta al administrador."` (`app/Services/Tenancy/EmpresaActiva.php:215-291`). Cada controlador `Web/Sunat/*` la usa + `Gate::authorize(...)` (ej. `FacturaController.php:112,215`).
- La API (X-Api-Key) **no** usa RBAC de usuarios: la credencial de empresa tiene acceso total a su tenant.

---

## 3. Multiempresa

### 3.1 Modelo `Tenant` (`app/Models/Tenant.php`, tabla `tenants`)
Campos (fillable `:24-70`) y origen en migraciones:

| Campo | Tipo / cast | Notas |
|---|---|---|
| `ruc` | VARCHAR(11) UNIQUE | |
| `razon_social` (nullable desde `2026_04_29`), `nombre_comercial`, `direccion`(500), `ubigeo`(6), `departamento`, `provincia`, `distrito` | | ubicación en `2026_03_05_182154` |
| `telefonos`, `emails`, `cuentas_bancarias`, `billeteras_digitales` | JSON → array | `2026_03_15_000001` |
| `mensaje_agradecimiento`, `mensaje_promocional` | VARCHAR(500) | |
| `sol_user`, `sol_pass` | TEXT, cast `encrypted` | usuario SOL secundario (sin RUC; Greenter concatena RUC+usuario). Default panel admin `MODDATOS` (beta) |
| `client_id` | VARCHAR(100) **en claro** | OAuth2 GRE |
| `client_secret` | TEXT, `encrypted` | |
| `sire_client_id` (100, claro), `sire_client_secret` (VARCHAR(255), `encrypted`) | | fallback mutuo con client_id/secret |
| `certificate_path` (500), `certificate_password` (TEXT, `encrypted`) | | |
| `environment` | ENUM(`beta`,`production`) default `beta` | por empresa |
| `webhook_url` (500) | | |
| `logo_path`, `favicon_path` | | disco `public` |
| `api_key` (64 UNIQUE), `api_secret` (TEXT, `encrypted`) | | |
| `consulta_token` (TEXT, `encrypted`), `consulta_proveedor` (VARCHAR(10) default `json`; `json`\|`migo`) | | consulta RUC/DNI (api.json.pe / api.migo.pe) |
| `plan` | ENUM(`free`,`pro`,`business`) default `free` | usado por throttle; la emisión usa suscripción |
| `emission_mode` | VARCHAR(20) default `plan` (`plan`\|`unlimited`) | `Tenant::EMISSION_*` `:19-22` |
| `tax_regime` | VARCHAR(32) default `general`; valores `rus`,`rer`,`mype`,`general` (`config/regimenes.php`); `restringido`=`rus` | normalizado en `2026_07_30_160000` |
| `igv_rate_override` | DECIMAL(5,2) | |
| `nrus_categoria` | int (1\|2) | solo `rus` |
| `obligado_codigo_producto` | bool | |
| `max_documents_month` (int, permite -1), `documents_this_month`, `ai_messages_this_month`, `usage_reset_month` | | contadores |
| `is_active` bool, `sire_enabled` bool, `sire_last_period_synced`(6), `sire_last_reconciliation_at` | | |
| `user_id` (FK users nullOnDelete) | dueño | |
| timestamps, `deleted_at` (SoftDeletes) | | |

`$hidden`: `sol_pass, client_secret, certificate_password, api_secret, consulta_token, sire_client_secret` (`:72-79`). `sol_user` y `client_id` NO ocultos (aparecen en `TenantResource`: `app/Http/Resources/Api/V1/TenantResource.php:23-26`).

`TenantResource` (respuesta de `GET /api/v1/empresa`): `id, ruc, razon_social, nombre_comercial, direccion, ubigeo, departamento, provincia, distrito, sol_user, tiene_sol_pass, client_id, tiene_client_secret, webhook_url, entorno, plan, tax_regime, igv_rate_override(float|null), nrus_categoria, tasa_igv_vigente, max_documentos_mes, documentos_este_mes (conteo real invoices+boletas+NC+ND del mes), activo, logo_url (Storage::url + ?v=timestamp), tiene_certificado, telefonos, emails, cuentas_bancarias, billeteras_digitales, mensaje_agradecimiento, mensaje_promocional, tiene_webhook, creado_en (ISO8601)` (`:13-49`).

`PUT /api/v1/empresa` acepta (`TenantController.php:30-65`): `razon_social, nombre_comercial, direccion, ubigeo(4-6), departamento, provincia, distrito, sol_user(≤50), sol_pass(≤50), client_id(≤100), client_secret(≤255), entorno(beta|production), url_webhook(url), logo(jpg/png ≤2MB), telefonos[≤5], emails[≤5], cuentas_bancarias[≤5]{banco,tipo_cuenta,moneda PEN|USD,numero,cci?,titular?}, billeteras_digitales[≤5]{tipo yape|plin|tunki|otro,numero,titular?}, mensaje_*, tax_regime, igv_rate_override(0-30), nrus_categoria(1|2)`; se ignoran nulls (no se puede borrar un campo salvo igv_rate_override/nrus_categoria); régimen ≠ rus limpia nrus_categoria. **El cliente puede cambiar su propio `entorno` a `production` por API** (sujeto a licencia y plan).

### 3.2 Sucursales y series
- `sucursales` (`2026_03_05_130000`): `tenant_id` FK cascade, `nombre`(100), `cod_local`(4), `direccion`(500), `ubigeo`(6), `telefono`, `email`, `is_principal`, `is_active`; UNIQUE(`tenant_id`,`cod_local`). Se añadió `sucursal_id` (FK nullOnDelete) a `series` y a `invoices, boletas, credit_notes, debit_notes, dispatch_guides`.
- `series` (`2026_03_04_000003` + `2026_03_05_120000/130500`): `tenant_id`, `tipo_documento`(2), `serie`(4), `correlativo` uint default 0, `is_active`, `sucursal_nombre`(100), `sucursal_id`; UNIQUE(`tenant_id`,`tipo_documento`,`serie`). Tipos (`app/Models/Serie.php:16-24`): factura 01, boleta 03, nota_credito 07, nota_debito 08, guia_remision 09, guia_transportista 31, retencion 20, percepcion 40. Correlativo con `lockForUpdate` en transacción (`Serie.php:86-105`; `Actions/Documents/Create*Action.php`).

### 3.3 Aislamiento por tenant
- **No hay Global Scope** en `main` (`EnsureTenantMember.php:13` lo dice explícitamente). Trait `App\Models\Concerns\BelongsToTenant` solo aporta `tenant()` y `scopeForTenant($tenantId)` (`app/Models/Concerns/BelongsToTenant.php:11-19`). Lo usan: `ApiLog, Client, DispatchGuide, InternalDocument, Perception, Retention, Serie, Sucursal, Summary, VoidedDocument` y `Models/Traits/HasDocumentFields` (Invoice/Boleta/CreditNote/DebitNote).
- Aislamiento 100 % manual en controladores: patrón `Modelo::forTenant($tenant->id)->findOrFail($id)` (71 usos de `forTenant(` y 41 `where('tenant_id'` en `Http/Controllers/Api/V1`; ej. `SucursalController.php:76,84,120`, `SerieController.php:21`). Recurso de otro tenant → 404 `"Recurso no encontrado."`.
- Panel: `EmpresaActiva` + Gates por empresa. Admin cruza empresas por route-model binding `{tenant}`.
- **[rama-sec]** `App\Support\Tenancy\CurrentTenant` + global scope context-aware en `BelongsToTenant` (solo activo en contexto API) + autocompletado de `tenant_id` en `creating`.
- Recomendación para la migración: implementar filtro por tenant obligatorio a nivel repositorio/ORM en contexto API (equivalente al scope de la rama-sec).

### 3.4 Entorno beta/producción por empresa
- `tenant.environment` selecciona endpoints (`config("facturacion.sunat.{env}.*")`, §4.1) en `GreenterService` (`:65-72,114-125,169-206`) y jobs.
- Beta: exento de licencia (`CheckLicense.php:33`, `GreenterService.php:47`) y de cupos (`EmissionPolicyService.php:44`).

### 3.5 Credenciales SOL y GRE
- SOL: `See::setClaveSOL($tenant->ruc, $tenant->sol_user, $tenant->sol_pass)` (SOAP WS-Security usuario = RUC+usuario) — `GreenterService.php:76-80`; igual para GRE `Api::setClaveSOL` (`:157-160`). SIRE usa `username={RUC}{SOL_USER}` sin separador (`app/Sire/Services/Auth/SireAuthService.php:24,103`).
- GRE OAuth2 (`GreenterService.php:201-207`), prioridad: `tenant.client_id` → `tenant.sire_client_id` → `config(facturacion.sunat.{env}.gre_client_id)`; ídem secret. Si faltan → `RuntimeException('Credenciales GRE (client_id/client_secret) no configuradas. Agrega SUNAT_GRE_CLIENT_ID y SUNAT_GRE_CLIENT_SECRET en .env')` (`:146-152`). SIRE inversa: `sire_client_id ?? client_id` (`Tenant.php:113-119`).
- Token GRE cacheado en Laravel Cache, clave `greenter:gre:token:` + `sha1(namespace|client_id)` donde namespace = `env|guias_auth|guias_cpe|ruc|sol_user`, TTL = expiración del token (`app/Services/Greenter/LaravelCacheTokenStore.php:26-47`, `GreenterService.php:165-178`).
- Validaciones de longitud: registro `sol_user ≤20`, `sol_pass ≤50`; update API `sol_user ≤50`.

### 3.6 Certificado digital (`app/Services/CertificateService.php`)
- Subida por API: `POST /api/v1/empresa/certificado` (`certificado` file ≤100 KB; `contrasena_certificado` ≤100, obligatorio para pfx/p12) y en `POST /api/v1/registro` (obligatorio) — `TenantController.php:195-242`, `RegisterController.php:43-68`. Extensiones aceptadas: `pfx, p12, pem, cer, crt`; si no → 422 `"Formato no soportado. Use .pfx, .p12, .pem, .cer o .crt"`; pfx sin password → 422 `"El campo contrasena_certificado es obligatorio para archivos .pfx/.p12"`.
- `convertToPem()` (`:19-43`): pem/cer/crt → si contiene `-----BEGIN` se valida; si no intenta DER→PEM. pfx/p12 → `pfxToPem` (`:98-164`): normaliza binario (detecta base64/BOM, `:177-214`), `openssl_pkcs12_read`; si falla (OpenSSL 3 rechaza RC2-40/3DES que usa SUNAT) reintenta con binario `openssl pkcs12 [-legacy] -in tmp -nodes -passin env:PFX_PASSWORD` (password por variable de entorno, no argv, `:224-268`). Detecta corrupción por U+FFFD. Resultado = `cert PEM + clave privada PEM` **sin cifrar**.
- `asegurarClavePrivada()` (`:62-93`): exige `openssl_x509_read` y `openssl_pkey_get_private` OK; mensajes específicos para clave cifrada o ausente. Todos los errores → `RuntimeException` → 422 con el mensaje.
- Almacenamiento: disco `local` (`storage/app/private`) — API/registro/bridge: `certificates/{ruc}/cert.pem` (`app/Services/Storage/DocumentStorageService.php:102-109`); admin panel: `tenants/{id}/certs/cert.pem` (`Admin/TenantController.php:554-556`). `certificate_path` guarda la ruta relativa; `certificate_password` se guarda cifrado (aunque el PEM ya no la necesita).
- Lectura (`Tenant::getCertificateContent()`, `Tenant.php:269-293`): prueba `certificate_path` (absoluta si empieza por `/` o contiene `:`), luego `certificates/{ruc}/cert.pem`, luego `tenants/{id}/certs/cert.pem` en disco `local`. `GreenterService::toPem()` (`:925-983`): si es PEM verifica clave privada; si es PKCS12 crudo prueba contraseñas `CERTIFICATE_PASSWORD`, la del tenant, `SUNAT_RUC_PSE` y vacía.
- **El PEM con la clave privada queda en claro en disco** (no cifrado en reposo). Disco `local` con `serve => true` (`config/filesystems.php:36`, SEC-019 corregido en rama-sec).
- Vencimientos: `certificados:verificar --alertar` diario 08:00 (avisa con `OPS_CERT_DIAS_AVISO`=30 días).

### 3.7 Logo / favicon / branding
- API: `POST /api/v1/empresa/logo` (jpg,jpeg,png,webp ≤2 MB) y `PUT empresa` (`logo` jpg/png) → `logos/{ruc}/logo.{ext}` en disco `public`; respuesta `{logo_path, logo_url: Storage::url(path)+'?v='+timestamp}` (`TenantController.php:128-152`). Registro: `logos/{ruc}/<hash>` (`RegisterController.php:101-106`).
- Panel empresa: `tenants/{id}/logos/<hash>`, favicon `tenants/{id}/branding/<hash>` (`Web/Sunat/ConfiguracionController.php:182,188`). Admin: `tenants/{id}/logos` (`Admin/TenantController.php:568`). Plataforma: `branding/<hash>` (settings `app_logo_path`, `favicon_path`; `Admin/ConfiguracionController.php:79,87`).

---

## 4. Configuración

### 4.1 `config/facturacion.php` (endpoints SUNAT — URLs exactas)

| Clave | Beta | Producción |
|---|---|---|
| `sunat.{env}.fe` (factura/boleta/NC/ND/resumen/baja) | `https://e-beta.sunat.gob.pe/ol-ti-itcpfegem-beta/billService` | `https://e-factura.sunat.gob.pe/ol-ti-itcpfegem/billService` |
| `sunat.{env}.retention` (retención/percepción/reversión) | `https://e-beta.sunat.gob.pe/ol-ti-itemision-otroscpe-gem-beta/billService` | `https://e-factura.sunat.gob.pe/ol-ti-itemision-otroscpe-gem/billService` |
| `sunat.{env}.guias_auth` (token OAuth2 GRE) | env `SUNAT_BETA_GRE_AUTH`, default `https://gre-test.nubefact.com/v1` | env `SUNAT_GRE_AUTH`, default `https://api-seguridad.sunat.gob.pe/v1` |
| `sunat.{env}.guias_cpe` (envío GRE) | env `SUNAT_BETA_GRE_CPE`, default `https://gre-test.nubefact.com/v1` | env `SUNAT_GRE_CPE`, default `https://api-cpe.sunat.gob.pe/v1` |
| `sunat.{env}.gre_client_id/secret` | env `SUNAT_BETA_GRE_CLIENT_ID/SECRET` (defaults de prueba públicos hardcodeados en el archivo — no copiados) | env `SUNAT_GRE_CLIENT_ID/SECRET` (default vacío) |
| `sunat.{env}.consulta_cdr` | `https://e-beta.sunat.gob.pe/ol-it-wsconscpegem-beta/billConsultService` | `https://e-factura.sunat.gob.pe/ol-it-wsconscpegem/billConsultService` |

Ref: `config/facturacion.php:35-56`. Otras claves:
- `storage.certificates_disk='local'`, `storage.documents_disk='public'` (`:16-19`; obsoleto — los comprobantes ya van al disco privado `comprobantes`).
- `throughput.per_tenant_per_minute` = `SUNAT_PER_TENANT_PER_MINUTE` (120) (`:31-33`).
- `certificate.path/password/pem_b64/pse_ruc/pse_razon_social` = `CERTIFICATE_PATH`, `CERTIFICATE_PASSWORD`, `CERTIFICATE_PEM_B64`, `SUNAT_RUC_PSE`, `SUNAT_RAZON_SOCIAL_PSE` (`:58-64`) — certificado global/PSE legado.
- `lookup.token` = `APIS_NET_PE_TOKEN` ?? `LOOKUP_API_TOKEN`; `lookup.base_url = https://api.apis.net.pe` (`:66-69`).
- `facturacion.plans` ya no existe (`:71-72`) pero `RegisterController.php:70,92` la sigue leyendo (cae al default 20).

Otros hosts externos: SIRE `https://api-seguridad.sunat.gob.pe/v1` (auth) y `https://api-sire.sunat.gob.pe/v1` (api), scope `https://api-sire.sunat.gob.pe` (`config/sire.php`); consulta RUC/DNI `https://api.json.pe/api/ruc|dni`, `https://api.migo.pe/api/v2/ruc|dni/{n}` (`app/Services/Consulta/ConsultaDocumentoService.php:66,119-120`).

### 4.2 Timeouts, reintentos, circuit breaker, colas

- **SOAP SUNAT (Greenter)**: sin timeout explícito → `default_socket_timeout` de PHP (60 s por defecto). Job `SendDocumentToSunat::$timeout=90`.
- HTTP: webhook 15 s (`Jobs/NotifyWebhookJob.php:76`); consulta json.pe 8 s / migo 12 s; licencia 8 s; SIRE auth 15 s, API 30 s (connect 10), TUS 60/300 s; OpsAlert webhook 5 s.
- **Reintentos por job** (`app/Jobs/*`):

| Job | Cola | tries | backoff (s) | Extras |
|---|---|---|---|---|
| `SendDocumentToSunat` (01/03/07/08) | `sunat` | 20 | 15,30,60,120,300,600,1200,1800, 3600×12 (~15 h) | timeout 90; middleware `RateLimited('sunat-tenant')`, `WithoutOverlapping("sunat-send:{class}:{id}")->expireAfter(180)`; no reenvía `aceptado/anulado`; si `pendiente`+`SUNAT_TIMEOUT` consulta CDR antes; circuit abierto → `release(300+rand(0,120))` |
| `SendDispatchGuideToSunat`, `SendSummaryToSunat`, `SendVoidedToSunat`, `SendRetentionToSunat`, `SendPerceptionToSunat`, `SendReversionToSunat` | `default` | 10 | 15,30,60,60,120,120,300,300,600,600 | tras agotar → `MAX_RETRIES` rechazado |
| `CheckTicketStatus`, `CheckSummaryTicketStatus`, `CheckVoidedTicketStatus` | `default` | 15 | 15,30,30,60,60,60,120,120,120,300,300,300,600,600,600 | |
| `NotifyWebhookJob` | `webhooks` | 3 | 10,30,90 | |
| Mailables (`app/Mail/BaseMailable.php:32`) | `mail` | | | |
| SIRE jobs | `sire-poll`, `sire-heavy`, `sire-process` (`config/sire.php` queues); `Sire/Jobs/NotifyWebhookJob` → `default` | | | limiter `sunat-sire` |

- **Circuit breaker** (`app/Services/Sunat/SunatCircuitBreaker.php`): por endpoint (clave cache `sunat_cb:{md5(endpoint)[0..8]}:{failures|open_state|was_open|probe}`); umbral 5 `SoapFault` en 60 s → OPEN 300 s; luego HALF_OPEN 30 s con 1 sonda (`Cache::add` probe); éxito → CLOSED; fallo de sonda → OPEN 600 s (`:30-35,41-127`). Solo `SendDocumentToSunat` lo usa (`Jobs/SendDocumentToSunat.php:96-104,121-126`). Comando `SunatCircuitCommand` para ops.
- **Horizon** (`config/horizon.php` defaults/environments): supervisor `sunat` (cola `sunat`, timeout 85, prod maxProcesses 5, local 2) y `otros` (`webhooks, mail, sire-process, sire-heavy, sire-poll, default`, timeout 60, prod 10, local 3); `tries=1` a nivel supervisor (los jobs declaran los suyos); conexión `redis` `retry_after` 90 (`config/queue.php:67-73`). Default `QUEUE_CONNECTION=database`. Ruta `/horizon` (`HORIZON_PATH`), middleware `web,auth` + gate.

### 4.3 Otros config relevantes
- `config/comprobantes.php:92` `disk` = `COMPROBANTES_DISK` (default `comprobantes`; `s3` soportado con salvedades).
- `config/license.php` (§1.9): `enabled`, `server_url`, `key`, `secret`, `domain` (default APP_URL), `app_version` (1.0.0), `cache_ttl` 3600, `grace_days` 7, `timeout` 8.
- `config/plans.php`: catálogo de claves de límites (`documents_month`, `internal_documents_month`, `ai_messages_month`, `team_members`, `sucursales`, `productos`, marketplace…; `-1` = ilimitado) y features (`facturacion, boletas, notas, guias_remision, retenciones, percepciones, anulaciones, sire, cotizaciones, notas_venta, pagos, …`). Planes reales en tabla `plans` (PlanSeeder).
- `config/sire.php`: hosts, `cod_libro` (rce 080000, rvie 140000), token (`safety_margin_seconds` 60, prefijo `sire_token:`, grant `password`), polling (10 s, 180 intentos, ×1.5, máx 60 s), rate 30/min, storage (`SIRE_STORAGE_DISK`=local, base `sire`), colas, códigos de proceso, upload ≤6 GB `.zip`, eventos webhook.
- `config/ops.php`: `OPS_ALERT_*` (webhook, canal log, etiqueta, throttle 60 min, timeout 5 s), umbrales `jobs_fallidos` 1, `cert_dias_aviso` 30, `disco_min_libre_pct` 10.
- `config/regimenes.php`: `lista` (rus, rer, mype, general), `default` general, `restringido` rus.
- `config/services.php`: postmark, resend, ses, slack, `culqi.public_key/secret_key`, `internal_token` (`INTERNAL_TOKEN`), `apis_net_pe.token`.
- `config/app.php`: locale `es`, faker `es_PE`, `saas_bridge_secret` (`:126`), `APP_PREVIOUS_KEYS` (rotación APP_KEY).
- `config/session.php`: driver `database`, lifetime 120, `same_site=lax`, `http_only`. `config/cache.php`: default `database`.
- `config/backup.php`, `config/sentry.php`, `config/cors.php` → §6.
- Settings de BD (tabla `settings` `key/value json`, caché `app:settings` 3600 s; `app/Services/Settings/SettingsService.php:20-54`): `emision_ilimitada_global`, `nuevas_empresas_ilimitadas`, `consulta_token_global` (**en claro**), `consulta_proveedor_global`, `app_logo_path`, `favicon_path`, `license_key`, `license_secret` (cifrado), `license_domain`.

### 4.4 Variables de `.env.example` (solo nombres y propósito)

| Variable | Propósito |
|---|---|
| `APP_NAME`, `APP_ENV`, `APP_KEY`, `APP_DEBUG`, `APP_URL`, `APP_TIMEZONE` | App base; `APP_KEY` cifra todos los campos `encrypted` (crítico compartirlo entre implementaciones) |
| `APP_LOCALE`, `APP_FALLBACK_LOCALE`, `APP_FAKER_LOCALE` | Localización es/es_PE |
| `APP_MAINTENANCE_DRIVER` (`APP_MAINTENANCE_STORE` comentado) | Modo mantenimiento |
| `PHP_CLI_SERVER_WORKERS` (comentado) | Workers de `artisan serve` |
| `BCRYPT_ROUNDS` | Coste bcrypt |
| `LOG_CHANNEL`, `LOG_STACK`, `LOG_DEPRECATIONS_CHANNEL`, `LOG_LEVEL` | Logging |
| `DB_CONNECTION`, `DB_HOST`, `DB_PORT`, `DB_DATABASE`, `DB_USERNAME`, `DB_PASSWORD` | BD (MySQL por defecto; bloques pgsql/sqlsrv comentados) |
| `SESSION_DRIVER`, `SESSION_LIFETIME`, `SESSION_ENCRYPT`, `SESSION_PATH`, `SESSION_DOMAIN` | Sesión panel |
| `BROADCAST_CONNECTION`, `FILESYSTEM_DISK`, `QUEUE_CONNECTION`, `CACHE_STORE` (`CACHE_PREFIX` comentado) | Infra |
| `MEMCACHED_HOST`, `REDIS_CLIENT`, `REDIS_HOST`, `REDIS_PASSWORD`, `REDIS_PORT` | Cache/colas |
| `MAIL_MAILER`, `MAIL_SCHEME`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM_ADDRESS`, `MAIL_FROM_NAME` | Correo (recuperación credenciales, avisos) |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_DEFAULT_REGION`, `AWS_BUCKET`, `AWS_USE_PATH_STYLE_ENDPOINT` | Disco s3/MinIO |
| `BACKUP_NAME`, `BACKUP_DISK`, `BACKUP_ARCHIVE_PASSWORD` | spatie/laravel-backup |
| `OPS_ALERT_WEBHOOK_URL`, `OPS_ALERT_LABEL`, `OPS_ALERT_THROTTLE_MINUTES`, `OPS_JOBS_FALLIDOS_UMBRAL`, `OPS_CERT_DIAS_AVISO`, `OPS_DISCO_MIN_LIBRE_PCT` | Alertas de operación |
| `HORIZON_PATH` (`HORIZON_DOMAIN` comentado) | Horizon |
| `SENTRY_LARAVEL_DSN`, `SENTRY_TRACES_SAMPLE_RATE`, `SENTRY_SEND_DEFAULT_PII` | Sentry |
| `LICENSE_ENABLED`, `LICENSE_SERVER_URL`, `LICENSE_KEY`, `LICENSE_SECRET`, `LICENSE_DOMAIN`, `LICENSE_APP_VERSION`, `LICENSE_GRACE_DAYS` | Licencia |
| `VITE_APP_NAME` | Frontend |

**Usadas en `config/*.php` pero ausentes de `.env.example`** (deben documentarse para las nuevas implementaciones): `SAAS_BRIDGE_SECRET`, `INTERNAL_TOKEN`, `CORS_ALLOWED_ORIGINS`, `COMPROBANTES_DISK`, `SUNAT_PER_TENANT_PER_MINUTE`, `SUNAT_BETA_GRE_AUTH`, `SUNAT_BETA_GRE_CPE`, `SUNAT_BETA_GRE_CLIENT_ID`, `SUNAT_BETA_GRE_CLIENT_SECRET`, `SUNAT_GRE_AUTH`, `SUNAT_GRE_CPE`, `SUNAT_GRE_CLIENT_ID`, `SUNAT_GRE_CLIENT_SECRET`, `CERTIFICATE_PATH`, `CERTIFICATE_PASSWORD`, `CERTIFICATE_PEM_B64`, `SUNAT_RUC_PSE`, `SUNAT_RAZON_SOCIAL_PSE`, `APIS_NET_PE_TOKEN`, `LOOKUP_API_TOKEN`, `SIRE_AUTH_HOST`, `SIRE_API_HOST`, `SIRE_STORAGE_DISK`, `CULQI_PUBLIC_KEY`, `CULQI_SECRET_KEY`, `LICENSE_CACHE_TTL`, `LICENSE_TIMEOUT`, `OPS_ALERT_LOG_CHANNEL`, `OPS_ALERT_WEBHOOK_TIMEOUT`, `APP_PREVIOUS_KEYS`, `AWS_ENDPOINT`, `AWS_URL`, `SCRIBE_AUTH_KEY`, `SCRIBE_BASE_URL`, resto de `SENTRY_*`, `REDIS_QUEUE_RETRY_AFTER`, `DB_QUEUE_RETRY_AFTER`; Docker: `SKIP_MIGRATIONS`, `PORT`, `PHP_CLI_SERVER_WORKERS`; **[rama-sec]** `TRUSTED_PROXIES`.

---

## 5. Almacenamiento

### 5.1 Discos (`config/filesystems.php:31-72`)
| Disco | Raíz | Notas |
|---|---|---|
| `local` (default `FILESYSTEM_DISK`) | `storage/app/private` | `serve => true` (Laravel 12 sirve archivos por URL firmada temporal) — certificados, SIRE, machine-id |
| `public` | `storage/app/public`, URL `{APP_URL}/storage` (symlink `public/storage`) | logos, favicons, branding; legado de comprobantes |
| `comprobantes` | `storage/app/private` (misma raíz que `local`) | XML/CDR/PDF; seleccionable por `COMPROBANTES_DISK` |
| `s3` | AWS/MinIO | opcional |

### 5.2 Rutas y nombres
- Comprobantes (`app/Services/Storage/DocumentStorageService.php:14-63,122-151`): `{ruc}/{cod_local|0000}/{YYYY-MM-DD fecha_emision}/{carpeta}/{xml|cdr|pdf}/{archivo}`; carpeta: `facturas` (01), `boletas` (03), `notas_credito` (07), `notas_debito` (08), `guias` (DispatchGuide), `otros`. Archivos: `{numero_completo}.xml`, `R-{numero_completo}.zip` (CDR), `{numero_completo}.pdf`. Se guarda la ruta en `xml_path`/`cdr_path`/`pdf_path` del documento. Lectura con fallback a `xml_content`/`cdr_content` en BD.
- `App\Support\Storage\Comprobantes` (`app/Support/Storage/Comprobantes.php:22-88`): escribe siempre en el disco privado configurado; `exists/get/download/path` caen a `public` (legado); `delete` borra en ambos. Comandos `comprobantes:mover-a-privado` (`MoverComprobantesAPrivado`), `MigrarComprobantesAS3`.
- Certificados: `certificates/{ruc}/cert.pem` o `tenants/{id}/certs/cert.pem` (disco `local`).
- Logos: `logos/{ruc}/logo.{ext}`, `tenants/{id}/logos/*`, `tenants/{id}/branding/*`, `branding/*` (disco `public`).
- Cache Greenter: `storage/app/cache/greenter` (`GreenterService.php:83-88`).
- SIRE: disco `SIRE_STORAGE_DISK` base `sire/`.
- Otros resúmenes/bajas/retenciones/percepciones: ver doc de documentos (se usan también `cdr_path` en `summaries` y `voided_documents`).

---

## 6. Seguridad

### 6.1 CORS (`config/cors.php`)
`paths=['api/*']` (`:9`), métodos `*`, `allowed_origins` = CSV de `CORS_ALLOWED_ORIGINS` con **default `*`** (`:18-20`; **[rama-sec]** default `APP_URL`), headers `*`, `exposed_headers = X-Usage-Documents-Used, X-Usage-Documents-Limit, X-Usage-Percent, X-Usage-Warning` (`:26-31`) — **no coinciden** con las cabeceras realmente emitidas (`X-Usage-Documents`, `X-Usage-Remaining`, `X-Usage-AI`, `X-Plan`, `Idempotency-Replayed`, `X-License-Warning`), `max_age` 86400, `supports_credentials=false`.

### 6.2 Cabeceras de seguridad — §1.12 (`SecurityHeaders`).

### 6.3 `SEGURIDAD-REPO.md`
Auditoría OWASP API Top 10 (15-10-2026). Hallazgos y su estado en `main` (todos "corregidos" solo en `fix/security-hardening`):
SEC-001 escalamiento admin→super_admin; SEC-002 `/ver-errores` sin auth (**presente en main**, `routes/web.php:95-106`); SEC-003 alias `/api/sunat/*` sin licencia/plan (**presente**); SEC-004 `/limpiar-cache-ahora` sin auth (**presente**, `routes/web.php:73-93`, con RUC/email hardcodeados); SEC-005 SSRF por `webhook_url`; SEC-006 deps vulnerables; SEC-007 bridge sin throttle y comparación no timing-safe (**presente**); SEC-008 `/sunat/mi-api-key` sin gate; SEC-010 masivo/PUT sin validación; SEC-011 fuga de `getMessage()` en 500; SEC-012 SVG en logos; SEC-013 `trustProxies('*')`; SEC-014 secretos en `api_logs`; SEC-016 token from-saas reutilizable; SEC-017 CORS `*`; SEC-018 sin CSP; SEC-019 `local` con `serve`; SEC-020 registro público elige plan de pago (**presente**, `RegisterController.php:31,71`); SEC-021 `X-Internal-Token` con `===`; SEC-022 TTL caché tenant 600 s; SEC-023 `sol_user` visible a solo lectura; BUG-01 recuperación de credenciales rota (**presente**). Aceptados: SEC-009 (api_secret reversible por HMAC de webhooks) y SEC-024 (ruta de logo predecible por RUC). Pendiente ops: SEC-025 `BACKUP_ARCHIVE_PASSWORD`. SEC-015 (global scope) implementado en rama-sec.

### 6.4 Enmascarado de logs — §1.7. `CertificateService` nunca registra passwords (`:133-142`); `GreenterService::toPem` tampoco (`:956-958`). `api_logs.response_body` no se enmascara.

### 6.5 `audit_logs` (`database/migrations/2026_10_03_140000_create_audit_logs_table.php`, `app/Models/AuditLog.php`)
Append-only (`UPDATED_AT=null`): `tenant_id`, `user_id`, `actor_tipo` (`usuario`|`api`|`sistema`), `actor_etiqueta` (`"Nombre <email>"` o `"API empresa {ruc}"`), `accion`(80), `subject_type`, `subject_id`, `descripcion`, `ip`(45), `user_agent`(512), `metadata` JSON, `created_at`. `AuditLog::registrar()` resuelve actor/IP/UA de la request y nunca lanza (`report($e)`) (`:52-105`). Acciones vistas: `empresa.actualizada`, `credenciales.regeneradas`, `certificado.actualizado`, `empresa.eliminada` (API TenantController), más anulaciones (VoidedController, AnulacionController), equipo, usuarios, licencia. Visor: `admin/bitacora` (`BitacoraController`), comando `VerAuditoria`. Sin purga.

### 6.6 Webhooks salientes (contrato relevante) — `app/Jobs/NotifyWebhookJob.php:51-77`
`POST tenant.webhook_url`, cuerpo JSON `{"event","document":{...},"timestamp":ISO8601}` (`JSON_UNESCAPED_UNICODE|SLASHES`), cabeceras `Content-Type: application/json`, `X-Signature: sha256=` + `hex(hmac_sha256(body, api_secret_en_claro))`, `X-Webhook-Event: <event>`, timeout 15 s. La respuesta no se verifica (no hay `throw()`), por lo que un 4xx/5xx del receptor **no** provoca reintento. Sin protección SSRF en main (**[rama-sec]** `WebhookUrlGuard`/`SafeWebhookUrl`).

### 6.7 Sentry
`\Sentry\Laravel\Integration::handles($exceptions)` (`bootstrap/app.php:77`); DSN `SENTRY_LARAVEL_DSN` (o `SENTRY_DSN`), no-op si vacío; `send_default_pii=false`, `sql_bindings=false` por defecto (`config/sentry.php:11,54,80,107`).

### 6.8 Backup (`config/backup.php`, `routes/console.php:59-61`)
Incluye `storage/app/private` (certificados PEM, comprobantes, SIRE) y **`.env`** (`config/backup.php:33-35`); excluye vendor, node_modules, storage/framework; BD por `DB_CONNECTION`. Disco `BACKUP_DISK` (default `local` → mismo servidor), cifrado zip `BACKUP_ARCHIVE_PASSWORD` (`:190`, vacío por defecto). `storage/app/public` (logos, comprobantes legados) **no** se respalda. Programación: `backup:clean` 01:00, `backup:run` 01:30, `backup:monitor` 09:00; fallos → `OpsAlert` (webhook) (`AppServiceProvider.php:137-168`).

### 6.9 Otros
- `DB::prohibitDestructiveCommands()` en producción (`AppServiceProvider.php:219-221`).
- `Date::use(CarbonImmutable)` global.
- `/api/debug/lookup` solo con `APP_DEBUG=true`, sin auth.

---

## 7. Despliegue

### 7.1 Docker (`Dockerfile`, `docker/start.sh`, `railway.json`, `.dockerignore`)
- Imagen `php:8.3-fpm-alpine`; extensiones `pdo_mysql, mysqli, pdo_pgsql, pgsql, zip, soap, dom, gd, bcmath, intl, mbstring, opcache, pcntl` + `redis` (PECL); binario `openssl` (necesario para PFX legacy); `composer install --no-dev`; build frontend Vite dentro de la imagen con `.env` temporal; crea `storage/*` y permisos www-data; `EXPOSE 8000`; `HEALTHCHECK curl /up` (`Dockerfile:1-96`).
- `start.sh`: `config:clear`, `cache:clear`, `migrate --force` salvo `SKIP_MIGRATIONS=true` (fallo no tumba el contenedor), `config:cache`, `route:cache`, `view:cache`, `storage:link`, y **`php artisan serve --host=0.0.0.0 --port=${PORT:-8000}`** con `PHP_CLI_SERVER_WORKERS` (default 10) — servidor de desarrollo en producción (`docker/start.sh:1-36`).
- `railway.json`: builder Dockerfile, healthcheck `/up` (100 s), restart ON_FAILURE ×10.
- `.dockerignore` excluye `.env`, `.env.*`, `*.md`, `tests`, vendor, node_modules.
- No hay `docker-compose.yml` en el repo; la topología (web + worker `php artisan horizon` + scheduler `php artisan schedule:work`) está documentada en `documentacion/despliegue/runbook-deploy.md:66-124`.

### 7.2 VPS bare-metal (`deploy-vps.sh`)
`git pull origin main` → `composer install --no-dev --optimize-autoloader` → `npm ci && npm run build` → `php artisan migrate --force` → `php artisan optimize:clear` → `php artisan queue:restart` (`deploy-vps.sh:21-47`). Avisa de recargar PHP-FPM si OPcache `validate_timestamps=0`. **No ejecuta `horizon:terminate`** (con Horizon, los workers siguen con el código viejo). Supervisor/Horizon: `documentacion/despliegue/escalamiento-horizon.md:58-135`, `vps-desde-cero.md:230-251`.

### 7.3 Hosting compartido (`cron-jobs.php`)
Runner por cron cada minuto: lock `storage/framework/cron-jobs.lock` (stale 70 s), `schedule:run`, luego `queue:work --queue=sunat,webhooks,mail,default --stop-when-empty --max-time≤45 --sleep=3 --tries=3 --timeout=30`; logs `storage/logs/cron-jobs.log` / `cron-jobs-error.log` (`cron-jobs.php:44-50,101-127`). **No escucha `sire-poll`, `sire-heavy`, `sire-process`** → los jobs SIRE nunca se procesan en esta modalidad.

### 7.4 Colas — nombres
`sunat` (envío comprobantes 01/03/07/08), `webhooks`, `mail`, `default` (guías, resúmenes, bajas, retenciones, percepciones, reversiones, consultas de ticket, jobs de suscripción, webhook SIRE), `sire-poll`, `sire-heavy`, `sire-process`.

### 7.5 Scheduler (`routes/console.php`)
| Tarea | Frecuencia | Ref |
|---|---|---|
| `partitions:create --months=3` | día 1, 02:00 | `:12` |
| `logs:purge --days=90` (api_logs) | domingos 03:00 | `:15` |
| `ProcessRecurringPayments` (job) | diario 06:00 | `:19` |
| `CheckTrialExpiration` (job) | diario 07:00 | `:22` |
| `ResetMonthlyUsage` (job) | día 1, 00:05 | `:25` |
| `sire:poll-pending` | cada minuto, withoutOverlapping, background | `:29-32` |
| `sire:reconcile-all` | diario 03:00 | `:35-37` |
| `sunat:reintentar-pendientes` (reencola pendiente/enviado, no rechazados) | cada 15 min | `:42-45` |
| `summaries:poll-pending` | cada 5 min | `:51-54` |
| `backup:clean` / `backup:run` / `backup:monitor` | 01:00 / 01:30 / 09:00 | `:59-61` |
| `certificados:verificar --alertar` | diario 08:00 | `:65` |
| `ops:jobs-fallidos --alertar` | cada hora | `:68` |
| `ops:salud --alertar` (BD, Redis, disco, circuit breaker) | cada 10 min | `:71-74` |
Ninguna tarea purga `idempotency_keys` ni `audit_logs`.

---

## 8. Observaciones / posibles bugs / riesgos

**Críticos / altos (en `main`)**
1. **Rutas de mantenimiento públicas**: `GET /ver-errores` vuelca las últimas 200 líneas de `laravel.log` sin auth (`routes/web.php:95-106`); `GET /limpiar-cache-ahora` ejecuta `optimize:clear` y crea un tenant con RUC/email hardcodeados (`:73-93`). No replicar.
2. **Alias `/api/sunat/*`** emite facturas/boletas/anulaciones **sin `license` ni `check.limit`**, sin idempotencia ni log (`routes/api.php:391-409`) → elusión del candado de licencia y de cupos. Replicar con el stack completo (rama-sec).
3. **Recuperación de credenciales rota (BUG-01)**: guarda `sha256(raw)` y devuelve `raw` → el secret entregado nunca autentica (`CredentialRecoveryController.php:109-122`).
4. **Bridge provision devuelve el sobre cifrado** (`getRawOriginal('api_secret')`) en vez del secret (`SaasAuthController.php:137`); X-Bridge-Key con `!==` y sin throttle; `deleteTenant` desactiva FKs (MySQL-only).
5. **`api_secret` en claro dentro de `api_logs.response_body`**: `POST empresa/credenciales/regenerar` está bajo `log.api` y su respuesta (<4 KB) se guarda sin enmascarar. El enmascarado solo aplica al request y omite `client_secret`, `sire_client_secret`, `consulta_token`, `certificate_password`.
6. **Registro público** `POST /api/v1/registro` permite elegir `plan` (business) y `entorno=production` sin pago (`RegisterController.php:30-31,71,87`). Además el throttle se calcula con `tenants.plan`, así que un plan auto-asignado sube el rate limit.
7. **Caché del tenant 600 s sin invalidación en varios caminos**: panel `/sunat/configuracion` (cambio de entorno/SOL), activación SIRE (API y panel) → tras `POST /v1/sire/activar` el middleware `sire.enabled` puede seguir devolviendo 403 hasta 10 min; contadores de `usage.headers` desfasados. Además el modelo cacheado se usa en controladores (environment/credenciales viejos en lecturas síncronas).
8. **Clave privada del certificado en claro** en `storage/app/private` (PEM sin cifrar), incluida en backups locales sin password por defecto; disco `local` con `serve => true`. Recomendación: cifrar el PEM en reposo en las nuevas implementaciones (manteniendo compatibilidad de lectura).
9. **`trustProxies('*')`** permite falsear `X-Forwarded-For` → evade throttle por IP (rutas públicas, recuperación 3/10 min) y falsea `api_logs.ip_address`/`audit_logs.ip`.
10. **Sin global scope de tenant**: aislamiento manual en cada consulta (hoy correcto según auditoría, pero frágil).

**Medios**
11. `idempotency_keys` sin TTL ni purga; el replay pierde cabeceras y fuerza `application/json` (un replay de un endpoint binario cambiaría el Content-Type).
12. Orden de middlewares: `throttle:api` va **después** de `resolve.tenant` → intentos de fuerza bruta con API keys inválidas no están limitados ni registrados.
13. Respuesta 429 custom descarta `Retry-After`/`X-RateLimit-*` del `ThrottleRequestsException` (`bootstrap/app.php:139-144`).
14. Licencia: cualquier respuesta HTTP del servidor de licencias (incluido 5xx/502) se trata como "no válida" y bloquea producción inmediatamente, sin gracia (`LicenseClient.php:85-98`). La gracia solo aplica a errores de conexión.
15. `check.limit` evalúa una vez por petición: `facturas/masivo` puede superar el cupo; el conteo está cacheado 300 s (sobrepaso en ráfagas) y no cuenta retenciones/percepciones/resúmenes/bajas aunque pasen por el gate.
16. Inconsistencia de fuentes de plan: throttle usa `tenants.plan`; emisión y `usage.headers` usan la suscripción activa; `usage.headers` usa la columna `documents_this_month` y `check.limit` el COUNT real.
17. CORS: default `*` y `exposed_headers` con nombres que no existen; las cabeceras reales no son legibles desde navegador.
18. Webhooks: no se valida el status de respuesta (sin reintento ante 4xx/5xx), sin protección SSRF; firma con el api_secret (rotar credenciales cambia la clave HMAC del receptor sin aviso).
19. `RateLimited('sunat-tenant')` y el `release()` del circuit breaker consumen intentos (`tries=20`): un tenant masivo o una caída larga pueden agotar reintentos y llevar documentos a `failed_jobs`.
20. Greenter SOAP sin timeout explícito (depende de `default_socket_timeout`); `cron-jobs.php` pasa `--timeout=30` pero el job declara 90 (prevalece el del job, y puede exceder el `MAX_EXECUTION_TIME` 55 s del runner).
21. `cron-jobs.php` no procesa colas SIRE; `deploy-vps.sh` no reinicia Horizon (`horizon:terminate`).
22. Producción en Docker usa `php artisan serve` (servidor de desarrollo).
23. Login web no comprueba `users.is_active`; `emailVerification` activado pero `User` no implementa `MustVerifyEmail`; `EnsureAdmin` deja entrar a cualquier `is_admin=true` aunque su rol sea `cliente`; Horizon gate igual.
24. `HandleInertiaRequests` da todas las abilities a un owner aunque su membresía esté inactiva (no chequea `is_active`) — `HandleInertiaRequests.php:55-57` (solo UI; los Gates sí lo chequean). Para super admin carga TODAS las empresas en cada request (`EmpresaActiva::disponibles()` sin límite antes del `take(100)`).
25. `sire_client_secret` es VARCHAR(255) con cast `encrypted`: un secret de más de ~100 caracteres no cabe cifrado (truncado/errores). `client_id` y `sire_client_id` en claro (no críticos).
26. `consulta_token_global` en tabla `settings` en claro (el token por tenant sí cifrado).
27. Inconsistencia de rutas de certificado (`certificates/{ruc}/cert.pem` vs `tenants/{id}/certs/cert.pem`) y de logos (`logos/{ruc}/logo.ext` vs `tenants/{id}/logos/*`); `TenantResource.logo_url` usa `Storage::url()` del disco por defecto (`local`) aunque el logo está en `public` (funciona por coincidencia de prefijo `/storage`).
28. `RegisterController` lee `config('facturacion.plans')` inexistente → `max_documents_month` siempre 20.
29. `config/facturacion.php` trae client_id/secret GRE de prueba hardcodeados como default para beta (son públicos de prueba, pero conviene moverlos a env).
30. `.env.example` omite variables necesarias (`SAAS_BRIDGE_SECRET`, `INTERNAL_TOKEN`, `CORS_ALLOWED_ORIGINS`, GRE de producción, `COMPROBANTES_DISK`, etc.; ver §4.4).
31. `/api/debug/lookup` (si `APP_DEBUG=true`) es público y llama a un proveedor externo con el token global.
32. Backups: incluyen `.env` (APP_KEY → descifra todo) y, con `BACKUP_DISK=local`, quedan en el mismo servidor; `storage/app/public` no se respalda.

**Implicaciones para la migración a 7 tecnologías**
- Compatibilidad de cifrado: para compartir BD, cada implementación debe leer/escribir el formato `Crypt` de Laravel con la misma `APP_KEY` (campos `encrypted`), incluido `settings.license_secret` (`encryptString`).
- Replicar exactamente: cabeceras `X-Api-Key`/`X-Api-Secret` (solo header), mensajes y códigos 401/403 de §1.4, idempotencia (§1.5, mismo hash `sha256(METHOD|path|body)` con `path` sin `/` inicial tal como lo da Laravel, p. ej. `api/v1/facturas`), cabeceras `X-Usage-*`/`X-Plan`/`Idempotency-Replayed`/`X-License-Warning`, firma de webhook (`X-Signature: sha256=<hex>`), límites de throttle por plan y formato de errores §1.2.
- Decidir si replicar el comportamiento `main` o el endurecido; se recomienda el endurecido (rama `fix/security-hardening`) y corregir BUG-01/bridge.
