# 01 — Endpoints de Comprobantes SUNAT (01, 03, 07, 08) + Pagos — Contrato de API-PRO

> Fuente: `C:\laragon\www\API-PRO` (Laravel 12.69.3, PHP 8.3). Todas las rutas de archivo son relativas a esa raíz.
> Fase 0 (análisis). Fuente de verdad = **código**, no `documentacion/` (hay discrepancias, ver §12).
> Convención: `archivo:línea`. "NC" = nota de crédito, "ND" = nota de débito.

---

## 0. Índice

1. Rutas, prefijos y pila de middleware
2. Formato de respuesta (trait ApiResponse) y manejador global de excepciones
3. Serialización JSON (detalles byte a byte)
4. Request de creación — Factura (01)
5. Request de creación — Boleta (03)
6. Request de creación — NC (07) y ND (08)
7. Lógica de creación (Actions), correlativo, cola, estados
8. Motor de cálculo (DocumentCalculationService + TaxRateService) — fórmulas exactas
9. Resources (respuesta de éxito) — estructura exacta
10. Acciones por endpoint (store, masivo, index, show, update, destroy, enviar/reenviar, xml, cdr, pdf)
11. Pagos (PaymentController)
12. Alias `/api/sunat/*`
13. Diferencias factura vs boleta vs notas; reglas de series
14. Observaciones / posibles bugs

---

## 1. Rutas y middleware

### 1.1 Rutas `/api/v1` (routes/api.php:45-118)

Grupo: `Route::prefix('v1')->middleware(['resolve.tenant', 'idempotency', 'throttle:api', 'log.api', 'usage.headers'])` (routes/api.php:45). Prefijo real: `/api/v1` (bootstrap/app.php:285 registra `routes/api.php` con prefijo `api`).

| Método | URL | Controller@acción | Middleware extra | Ref |
|---|---|---|---|---|
| POST | /api/v1/facturas | InvoiceController@store | license, check.limit:sunat | api.php:69 |
| POST | /api/v1/facturas/masivo | InvoiceController@masivo | license, check.limit:sunat | api.php:70 |
| GET | /api/v1/facturas | InvoiceController@index | — | api.php:71 |
| GET | /api/v1/facturas/{id} | InvoiceController@show | — | api.php:72 |
| PUT | /api/v1/facturas/{id} | InvoiceController@update | — | api.php:73 |
| GET | /api/v1/facturas/{id}/xml | InvoiceController@xml | — | api.php:74 |
| GET | /api/v1/facturas/{id}/cdr | InvoiceController@cdr | — | api.php:75 |
| GET | /api/v1/facturas/{id}/pdf | InvoiceController@pdf | — | api.php:76 |
| POST | /api/v1/facturas/{id}/reenviar | InvoiceController@resend | — | api.php:77 |
| POST | /api/v1/facturas/{id}/enviar | InvoiceController@enviar | — | api.php:78 |
| POST | /api/v1/facturas/{id}/pagos | PaymentController@store (`defaults('docType','facturas')`) | — | api.php:79 |
| GET | /api/v1/facturas/{id}/pagos | PaymentController@index | — | api.php:80 |
| DELETE | /api/v1/facturas/{id}/pagos/{paymentId} | PaymentController@destroy | — | api.php:81 |
| POST | /api/v1/boletas | BoletaController@store | license, check.limit:sunat | api.php:84 |
| GET | /api/v1/boletas | BoletaController@index | — | api.php:85 |
| GET | /api/v1/boletas/{id} | BoletaController@show | — | api.php:86 |
| PUT | /api/v1/boletas/{id} | BoletaController@update | — | api.php:87 |
| DELETE | /api/v1/boletas/{id} | BoletaController@destroy | — | api.php:88 |
| GET | /api/v1/boletas/{id}/xml · /cdr · /pdf | BoletaController@xml/cdr/pdf | — | api.php:89-91 |
| POST | /api/v1/boletas/{id}/reenviar | BoletaController@resend | — | api.php:92 |
| POST | /api/v1/boletas/{id}/enviar | BoletaController@enviar | — | api.php:93 |
| POST/GET | /api/v1/boletas/{id}/pagos | PaymentController@store/index | — | api.php:94-95 |
| DELETE | /api/v1/boletas/{id}/pagos/{paymentId} | PaymentController@destroy | — | api.php:96 |
| POST | /api/v1/notas-credito | CreditNoteController@store | license, check.limit:sunat | api.php:99 |
| GET | /api/v1/notas-credito | @index | — | api.php:100 |
| GET | /api/v1/notas-credito/{id} | @show | — | api.php:101 |
| GET | /api/v1/notas-credito/{id}/xml · /cdr · /pdf | @xml/@cdr/@pdf | — | api.php:102-104 |
| PUT | /api/v1/notas-credito/{id} | @update | — | api.php:105 |
| POST | /api/v1/notas-credito/{id}/reenviar · /enviar | @resend/@enviar | — | api.php:106-107 |
| (idéntico) | /api/v1/notas-debito… | DebitNoteController | store con license, check.limit:sunat | api.php:110-118 |

**No existen**: `DELETE /facturas/{id}`, `DELETE /notas-credito/{id}`, `DELETE /notas-debito/{id}`, pagos en notas. Rutas inexistentes/métodos no permitidos → 404 / 405 del handler global (§2.2).

Parámetro `{id}`: sin restricción `where`. Las acciones declaran `int $id` (excepto `InvoiceController@pdf`: `int|string $id` con `(int)` cast, InvoiceController.php:333-336, y PaymentController: `string $id` + `(int)`). Ver §14 sobre ids no numéricos.

### 1.2 Pila de middleware (orden de ejecución)

Globales (bootstrap/app.php:289-337 + defaults de Laravel 12):
1. `HandleCors` (prepend) y `ForceJsonAccept` (prepend): fuerza `Accept: application/json` en `api/*` (app/Http/Middleware/ForceJsonAccept.php:18-23).
2. Defaults Laravel: TrustProxies (at `*`), `TrimStrings`, `ConvertEmptyStringsToNull` (¡los `""` llegan como `null`!), ValidatePostSize, etc.
3. `SecurityHeaders` (append) → añade a TODA respuesta: `X-Content-Type-Options: nosniff`, `X-Frame-Options: SAMEORIGIN`, `Referrer-Policy: strict-origin-when-cross-origin`, `X-Permitted-Cross-Domain-Policies: none`, `Strict-Transport-Security: max-age=31536000; includeSubDomains` solo si HTTPS; elimina `X-Powered-By` (SecurityHeaders.php:20-35).

De grupo/ruta, en este orden:
1. **resolve.tenant** (app/Http/Middleware/ResolveTenant.php):
   - Cabeceras `X-Api-Key` y `X-Api-Secret` (solo cabecera, no query). Si falta alguna → 401 `{"estado":"error","mensaje":"Las cabeceras X-Api-Key y X-Api-Secret son requeridas."}` (:21-26).
   - Tenant por `api_key` (cache 600 s `tenant:key:{apiKey}`); no existe → 401 `{"estado":"error","mensaje":"Credenciales de API inválidas."}` (:32-37).
   - Secret comparado con `hash_equals` contra `api_secret` (cifrado). Si no se puede descifrar → 401 `{"estado":"error","mensaje":"No se pudo validar el API Secret de la empresa (credencial ilegible). Regenérela desde el panel: Configuración → Credenciales → Regenerar."}` (:43-50). Secret no coincide → 401 "Credenciales de API inválidas." (:52-57).
   - `is_active=false` → 403 `{"estado":"error","mensaje":"Empresa desactivada. Contacte al administrador."}` (:59-64).
   - `$request->merge(['tenant' => $tenant])` (:66) — el tenant queda **dentro del input** (`$request->all()` lo contiene).
2. **idempotency** (app/Http/Middleware/EnsureIdempotency.php) — solo POST/PUT/PATCH/DELETE con cabecera `Idempotency-Key` (truncada a 255):
   - hash = `sha256(METHOD|path|rawBody)` (:107). Tabla `idempotency_keys` (tenant_id, idempotency_key, request_hash, response_status, response_body, created_at).
   - Key existente con hash distinto → 422 `{"estado":"error","mensaje":"La Idempotency-Key ya se usó con un cuerpo distinto."}` (:115-120).
   - Key existente con respuesta guardada → replay: mismo body y status, `Content-Type: application/json`, cabecera `Idempotency-Replayed: true` (:121-125).
   - Key existente sin respuesta (en curso) o carrera en INSERT (unique) → 409 `{"estado":"error","mensaje":"Ya hay una solicitud en proceso con esa Idempotency-Key."}` (:127-131, :144-149).
   - Tras ejecutar: si status < 500 se guarda y se añade `Idempotency-Replayed: false`; si ≥ 500 se borra la key (:155-164).
   - No hay TTL/expiración visible en el middleware.
3. **throttle:api** (app/Providers/AppServiceProvider.php:175-190): `X-Internal-Token` == `config('services.internal_token')` → sin límite. Si no: por minuto según `$tenant->plan`: free=30, pro=120, business=300, otro=30; clave `tenant.id` (o IP). Laravel añade `X-RateLimit-Limit`, `X-RateLimit-Remaining` y en 429 `Retry-After`, `X-RateLimit-Reset`. Cuerpo 429 (handler global): `{"estado":"error","mensaje":"Demasiadas solicitudes. Intenta de nuevo más tarde."}`.
4. **log.api** (LogApiRequest.php): registra request/response en cola; no altera la respuesta.
5. **usage.headers** (UsageWarningHeader.php:16-50): añade `X-Usage-Documents: {documents_this_month}/{limit}` (si límite ≠ -1; default 30), `X-Usage-Warning: approaching_limit` y `X-Usage-Remaining` si ≥80 %, `X-Usage-AI: {n}/{limit}` (default 10) y `X-Plan: {slug}`.
6. **license** (solo store/masivo) (CheckLicense.php:30-57): tenant `environment === 'beta'` → pasa siempre. Producción: `LicenseClient::check()`; si allowed con gracia offline → cabecera `X-License-Warning: {mensaje}`; si no → 403 `{"estado":"error","mensaje":"<check.message>","codigo_error":"<check.code>"}`.
7. **check.limit:sunat** (solo store/masivo) (CheckDocumentLimit.php):
   - Suscripción vencida → 403 `{"estado":"error","mensaje":"Tu suscripción venció. Renueva el plan para seguir emitiendo comprobantes.","codigo_error":"<CODE_SUBSCRIPTION_EXPIRED>","plan_actual":"<slug|null>","recurso":"documents_month"}` (:45-53).
   - Cupo alcanzado → 429 `{"estado":"error","mensaje":"Has alcanzado el limite de documentos SUNAT ({used}/{limit} por mes).[ sufijo]","codigo_error":"<CODE_LIMIT_REACHED>","plan_actual":..,"recurso":"documents_month","actual":int,"limite":int,"mejora_plan":obj|null}` (:56-79). Sufijo free: `" Actualiza a Pro por S/49/mes para más documentos."` + `mejora_plan={"slug":"pro","price":49,"currency":"PEN","trial_days":14}`; pro: `" Actualiza a Business por S/99/mes para documentos ilimitados."` + `{"slug":"business","price":99,"currency":"PEN","trial_days":14}`.
   - (Los valores de `CODE_*` están en `app/Services/Plan/EmissionDecision.php`; los cubre otro análisis.)

> Orden importante: `idempotency` va ANTES de throttle/license/check.limit, por lo que un 403/429 de esos middlewares (status < 500) queda **guardado** bajo la Idempotency-Key.

---

## 2. Formato de respuesta y errores

### 2.1 Trait `ApiResponse` (app/Http/Traits/ApiResponse.php)

```php
success($datos=null, $mensaje='OK', $codigo=200, $meta=null)
  → {"estado":"exito","mensaje":$mensaje,"datos":$datos}  (+ "meta" solo si $meta !== null)   (:21-34)
created($datos, $mensaje='Creado exitosamente') → success(..., 201)                           (:36-39)
error($mensaje='Error', $codigo=400, $errores=null)
  → {"estado":"error","mensaje":$mensaje}  (+ "errores" solo si $errores es truthy)            (:41-53)
errorAccionable($mensaje, $codigoError, $siguienteAccion=null, $codigo=422)
  → {"estado":"error","mensaje":..,"codigo_error":..,"siguiente_accion":{...}}                 (:55-72)
```
Orden de claves: `estado`, `mensaje`, `datos`, (`meta`). En errores: `estado`, `mensaje`, (`errores` | `codigo_error`, `siguiente_accion`).

### 2.2 Manejador global (bootstrap/app.php:338-431)

Aplica cuando `request->is('api/*') || expectsJson()` (siempre en API por ForceJsonAccept):

| Excepción | HTTP | Cuerpo |
|---|---|---|
| `ValidationException` | 422 | `{"estado":"error","mensaje":"Error de validación","errores":{campo:[msgs]}}` (:358-364) |
| `ModelNotFoundException` / `NotFoundHttpException` (incluye `abort(404,'...')`) | 404 | `{"estado":"error","mensaje":"Recurso no encontrado."}` (:367-373) |
| `AuthenticationException` | 401 | `{"estado":"error","mensaje":"No autenticado."}` |
| `AuthorizationException` | 403 | `{"estado":"error","mensaje":"No autorizado."}` |
| `MethodNotAllowedHttpException` | 405 | `{"estado":"error","mensaje":"Método HTTP no permitido para esta ruta."}` |
| `ThrottleRequestsException` | 429 | `{"estado":"error","mensaje":"Demasiadas solicitudes. Intenta de nuevo más tarde."}` |
| Otro `HttpExceptionInterface` | su status | `{"estado":"error","mensaje": getMessage() ?: "Error."}` |
| Cualquier otro `Throwable` | 500 | `{"estado":"error","mensaje":"Error interno del servidor."}` + si `APP_DEBUG`: `"excepcion"`(clase), `"detalle"`(mensaje), `"archivo"`("file:line") (:418-430) |

Nota: `abort(404, 'Tipo de documento no válido')` de PaymentController lanza `NotFoundHttpException` → devuelve "Recurso no encontrado." (la rama 404 se evalúa antes que la genérica).

### 2.3 Validación de FormRequests

Todos los `Store*Request` sobrescriben `failedValidation` con el MISMO cuerpo que el handler: 422 `{"estado":"error","mensaje":"Error de validación","errores":{...}}` (p. ej. StoreInvoiceRequest.php:226-233). `errores` es el MessageBag: objeto `{ "campo.ruta": ["mensaje1", ...] }`, en orden: primero errores de reglas (en orden de declaración de reglas y expansión de comodines por índice), luego los añadidos en `withValidator()->after()` (los hooks `after` se ejecutan aunque haya errores de reglas).

Mensajes por defecto en español (`lang/es/validation.php`, locale `es`, config/app.php:81). Plantillas relevantes:
- required: `El campo :attribute es obligatorio.`
- required_with: `El campo :attribute es obligatorio cuando :values está presente.`
- required_unless: `El campo :attribute es obligatorio a menos que :other esté en :values.`
- string: `El campo :attribute debe ser una cadena de caracteres.`
- size.string: `El campo :attribute debe contener :size caracteres.`
- regex: `El formato del campo :attribute no es válido.` (sobrescrito en serie de factura/boleta)
- integer: `El campo :attribute debe ser un número entero.`
- numeric: `El campo :attribute debe ser numérico.`
- min.numeric: `El tamaño de :attribute debe ser de al menos :min.` · min.array: `El campo :attribute debe tener al menos :min elementos.`
- max.numeric: `El campo :attribute no debe ser mayor que :max.` · max.string: `El campo :attribute no debe ser mayor que :max caracteres.`
- gt.numeric: `El campo :attribute debe ser mayor que :value.`
- in: `El campo :attribute no está en la lista de valores permitidos.`
- date: `El campo :attribute debe ser una fecha válida.`
- email: `El campo :attribute no es un correo válido.`
- array: `El campo :attribute debe ser un conjunto.`

Nombre mostrado (`:attribute`), algoritmo Laravel `FormatsMessages::getDisplayableAttribute` (vendor/.../Validation/Concerns/FormatsMessages.php:275-309):
1. Si hay traducción en `validation.attributes` → se usa. Relevante: `items` → **`elementos`**, `email` (solo top-level) → `correo electrónico`, `value` → `valor` (solo top-level).
2. Si el atributo proviene de una regla con comodín (`items.*.x`) → se muestra la ruta cruda: `items.0.descripcion`.
3. Si no: `str_replace('_',' ', Str::snake($attr))` → `fecha_emision` → `fecha emision`; `cliente.num_doc` → `cliente.num doc`; `cliente.tipo_doc` → `cliente.tipo doc`.
   Ej.: `{"serie":["El campo serie es obligatorio."],"items":["El campo elementos es obligatorio."],"cliente.num_doc":["El campo cliente.num doc es obligatorio."],"items.0.unidad":["El campo items.0.unidad no está en la lista de valores permitidos."]}`.
`:values` de required_with también se traduce con el mismo algoritmo (p. ej. `leyendas`, `detraccion`, `contrato colaboracion.numero`).

Reglas personalizadas:
- `SunatCatalog($n)` (app/Rules/SunatCatalog.php:29-43): si el valor no es string ni int → `El campo :attribute debe ser un código válido del Catálogo SUNAT N° {n}.`; si no está en `config/sunat_catalogs.php[$n]` → `El campo :attribute contiene un código inválido '{valor}'. Catálogo SUNAT N° {n}. Códigos válidos: {lista separada por ", "}.`
- `SunatProductCode` (app/Rules/SunatProductCode.php:123-142): no `^\d{8}$` → `El Código de producto SUNAT debe tener 8 dígitos numéricos (Catálogo N.° 25, UNSPSC).`; termina en `0000` → `El Código de producto SUNAT debe especificarse como mínimo a nivel de clase UNSPSC (no puede terminar en 0000).`; no existe en tabla UNSPSC (`UnspscCode::existe`) → `El Código de producto SUNAT '{codigo}' no es válido: no existe en el Catálogo N.° 25 (UNSPSC v14).`

Catálogos (claves válidas de config/sunat_catalogs.php):
- 01 (tipo doc, usado en `guias.*.tipo_doc`): 01, 03, 07, 08, 09, 12, 20, 31, 40
- 06 (doc identidad): 0, 1, 4, 6, 7, A, B, C, D, E
- 07 (afectación IGV): 10, 11, 12, 13, 14, 15, 16, 17, 20, 21, 30, 31, 32, 33, 34, 35, 36, 37, 40
- 08 (sistema ISC): 01, 02, 03
- 09 (motivo NC): 01–09
- 10 (motivo ND): 01, 02, 03, 04, 05, 06, 07, 08, 09, 11, 12, 13
- 12 (doc relacionado/anticipo): 01–10, 99
- 22 (régimen percepción): 01 (tasa 2), 02 (tasa 1), 03 (tasa 0.5)
- 51 (tipo operación): 0101, 0112, 0113, 0200–0208, 0301, 0302, 0303, 0401, 1001, 1002, 1003, 1004, 2001
- 52 (leyendas): 1000, 1002, 2000–2011
- 53 (cargos/descuentos): 00, 01, 02, 03, 04, 05, 06, 45, 46, 47, 48, 49, 50, 51, 52, 53
- 54 (bienes detracción): 001, 003, 004, 005, 007, 008, 009, 010, 012, 014, 017, 019, 020, 021, 022, 023, 024, 025, 026, 027, 030, 031, 034, 035, 036, 037, 039, 040, 038, 042, 043, 046, 047 (orden del archivo; se listan así en el mensaje)
- 59 (medio pago): 001–013, 101–108, 999
- icbper (factor por año): 2019 0.1, 2020 0.2, 2021 0.3, 2022 0.4, 2023–2026 0.5 (no se usa automáticamente en cálculo; ver §8).
> El mensaje "Códigos válidos: ..." lista las claves en el orden del archivo de config; para reproducirlo byte a byte hay que copiar `config/sunat_catalogs.php` tal cual (incluidas descripciones si se usan en otros sitios).

---

## 3. Serialización JSON (byte a byte)

- `response()->json()` de Laravel usa flags `0`: sin `JSON_PRESERVE_ZERO_FRACTION` → `(float)100.0` se emite `100` (verificado: `json_encode(["a"=>100.0])` → `{"a":100}`), `118.5` → `118.5`. Barras `/` se escapan (`\/`) y caracteres no ASCII se escapan como `\uXXXX` (sin `JSON_UNESCAPED_UNICODE`/`SLASHES`). Ej.: URL en `archivos.xml` → `"https:\/\/host\/api\/v1\/facturas\/5\/xml"`; `"Error de validación"` → `"Error de validaci\u00f3n"`. `Content-Type: application/json` (sin charset).
- Floats: precisión `serialize_precision=-1` (repr. más corta: `0.1+0.2` → `0.30000000000000004`).
- Decimales de BD con cast `decimal:2`/`decimal:4` llegan como string y los Resources los castean a `(float)` → números JSON.
- `correlativo` cast `integer` (HasDocumentFields.php:49).
- Fechas: `fecha_emision` → `'Y-m-d H:i:s'` (wall-clock Lima guardado tal cual); `fecha_vencimiento` → `'Y-m-d'`; `creado_en`/`enviado_en` → `toIso8601String()` en zona **UTC** (config/app.php:68 `'timezone' => 'UTC'`, fijo, ignora `APP_TIMEZONE`): `"2026-10-24T15:04:05+00:00"`. Carbon inmutable (AppServiceProvider `Date::use(CarbonImmutable::class)`).
- Claves ausentes: `whenLoaded()` / `when()` falsos → la clave **desaparece** (no `null`); el orden del resto se mantiene.
- Un `JsonResource` dentro de `success()` se serializa sin envoltorio `data` (no se usa `toResponse`). Una `ResourceCollection` paginada anidada se serializa como array plano (sin `links`/`meta` de Laravel).

---

## 4. POST /facturas — StoreInvoiceRequest (app/Http/Requests/Api/V1/StoreInvoiceRequest.php)

`authorize()` = true. **Importante**: el Action recibe `$request->validated()`; en Laravel 12 `validated()` excluye claves no declaradas en reglas, y para reglas `array` con hijos declarados solo conserva los hijos validados (`excludeUnvalidatedArrayKeys`). Claves no listadas abajo se **descartan** silenciosamente (p. ej. `sucursal_id`, `items.*.descuento`, `percepcion.mto_total`, `descuentos_globales.*.factor`, `mto_isc`, `mto_icbper`, `mto_base_ivap`, `mto_ivap`, `mto_igv_gratuitas`). Excepciones: `extras` (array sin hijos) se guarda completo. `enviar_automatico` se lee aparte con `$request->boolean()`.

### 4.1 Reglas (orden exacto de declaración)

| Campo | Regla | Línea |
|---|---|---|
| `contrato_colaboracion` | `nullable\|array` | ValidatesContratoColaboracion.php:80 |
| `contrato_colaboracion.numero` | `nullable\|string\|max:50` | :81 |
| `contrato_colaboracion.tipo` | `required_with:contrato_colaboracion.numero\|in:1,2` | :82 |
| `contrato_colaboracion.descripcion` | `required_with:contrato_colaboracion.numero\|string\|max:250` | :83 |
| `contrato_colaboracion.porcentaje` | `required_with:contrato_colaboracion.numero\|numeric\|min:0\|max:99.99` | :84 |
| `serie` | `required, string, size:4, regex:/^F[A-Z0-9]{3}$/` | :31 |
| `correlativo` | `nullable\|integer\|min:1` | :33 |
| `cod_local` | `nullable\|string\|size:4` | :34 |
| `fecha_emision` | `required\|date` | :35 |
| `fecha_vencimiento` | `nullable\|date` | :36 |
| `tipo_operacion` | `nullable, string, SunatCatalog('51')` | :37 |
| `tipo_moneda` | `nullable\|string\|in:PEN,USD,EUR` | :38 |
| `forma_pago` | `nullable\|string\|in:Contado,Credito,Tarjeta,Billetera Digital,Deposito,Transferencia` | :42 |
| `cliente.tipo_doc` | `required, string, SunatCatalog('06')` | :45 |
| `cliente.num_doc` | `required\|string\|max:15` | :46 |
| `cliente.razon_social` | `required\|string\|max:1500` | :47 |
| `cliente.direccion` | `nullable\|string\|max:500` | :48 |
| `cliente.email` | `nullable\|email` | :49 |
| `cliente.telefono` | `nullable\|string\|max:20` | :50 |
| `mto_oper_gravadas`, `mto_oper_exoneradas`, `mto_oper_inafectas`, `mto_oper_exportacion`, `mto_oper_gratuitas`, `mto_igv`, `total_impuestos`, `valor_venta`, `sub_total`, `mto_imp_venta`, `sum_otros_descuentos` | `nullable\|numeric\|min:0` (cada uno; **si vienen, sobrescriben el cálculo**, §8.3) | :53-63 |
| `items` | `required\|array\|min:1` | :66 |
| `items.*.codigo` | `nullable\|string\|max:30` | :67 |
| `items.*.cod_producto_sunat` | `nullable, string, SunatProductCode` | :68 |
| `items.*.descripcion` | `required\|string\|max:500` | :69 |
| `items.*.unidad` | `required\|string\|in:` 4A,BJ,BLL,BG,BO,BX,CT,CMK,CMQ,CMT,CEN,CY,CJ,DZN,DZP,BE,GLI,GRM,GRO,HLT,LEF,SET,KGM,KTM,KWH,KT,CA,LBR,LTR,MWH,MTR,MTK,MTQ,MGM,MLT,MMT,MMK,MMQ,MLL,UM,ONZ,PF,PK,PR,FOT,FTK,FTQ,C62,PG,ST,INH,RM,DR,STN,LTN,TNE,TU,NIU,ZZ,GLL,YRD,YDK,U2,HUR,QD,HD,JG,JR,CH,AV,SA,BT,HT,RD,RL,SEC,DAY,MON | :70 |
| `items.*.cantidad` | `required\|numeric\|gt:0` | :71 |
| `items.*.precio_unitario` | `required\|numeric\|min:0` (precio CON IGV) | :72 |
| `items.*.porcentaje_igv` | `nullable\|numeric` | :73 |
| `items.*.tip_afe_igv` | `nullable, string, SunatCatalog('07')` | :74 |
| `items.*.igv` | `nullable\|numeric` | :75 |
| `items.*.isc` | `nullable\|numeric` | :76 |
| `items.*.porcentaje_isc` | `nullable\|numeric\|min:0` | :77 |
| `items.*.tip_sis_isc` | `nullable, string, SunatCatalog('08')` | :78 |
| `items.*.icbper` | `nullable\|numeric` | :79 |
| `items.*.factor_icbper` | `nullable\|numeric` | :80 |
| `items.*.mto_valor_unitario` | `nullable\|numeric\|min:0` | :81 |
| `items.*.mto_valor_venta` | `nullable\|numeric` | :82 |
| `items.*.mto_base_igv` | `nullable\|numeric\|min:0` | :83 |
| `items.*.total_impuestos` | `nullable\|numeric` | :84 |
| `items.*.descuentos` | `nullable\|array` | :87 |
| `items.*.descuentos.*.cod_tipo` | `nullable, string, SunatCatalog('53')` | :88 |
| `items.*.descuentos.*.monto_base` | `nullable\|numeric\|min:0` | :89 |
| `items.*.descuentos.*.factor` | `nullable\|numeric\|min:0\|max:1` | :90 |
| `items.*.descuentos.*.porcentaje` | `nullable\|numeric\|min:0\|max:100` | :91 |
| `items.*.descuentos.*.monto` | `nullable\|numeric\|min:0` | :92 |
| `leyenda` | `nullable\|string\|max:500` | :95 |
| `leyendas` | `nullable\|array` | :96 |
| `leyendas.*.code` | `required_with:leyendas, string, SunatCatalog('52')` | :97 |
| `leyendas.*.value` | `required_with:leyendas\|string\|max:500` | :98 |
| `cuotas` | `nullable\|array` | :101 |
| `cuotas.*.monto` | `required_with:cuotas\|numeric\|gt:0` | :102 |
| `cuotas.*.fecha_pago` | `required_with:cuotas\|date` | :103 |
| `guias` | `nullable\|array` | :106 |
| `guias.*.tipo_doc` | `required_with:guias, string, SunatCatalog('01')` | :107 |
| `guias.*.nro_doc` | `required_with:guias\|string` | :108 |
| `detraccion` | `nullable\|array` | :111 |
| `detraccion.cod_bien` | `required_with:detraccion, string, SunatCatalog('54')` | :112 |
| `detraccion.porcentaje` | `required_with:detraccion\|numeric\|min:0\|max:100` | :113 |
| `detraccion.cta_banco` | `required_with:detraccion\|string\|max:20` | :114 |
| `detraccion.cod_medio_pago` | `nullable, string, SunatCatalog('59')` | :115 |
| `detraccion.monto` | `required_with:detraccion\|numeric\|min:0` | :116 |
| `percepcion` | `nullable\|array` | :119 |
| `percepcion.cod_regimen` | `required_with:percepcion, string, SunatCatalog('22')` | :120 |
| `percepcion.porcentaje` | `required_with:percepcion\|numeric\|min:0\|max:100` | :121 |
| `percepcion.monto` | `required_with:percepcion\|numeric\|min:0` | :122 |
| `percepcion.base` | `required_with:percepcion\|numeric\|min:0` | :123 |
| `anticipos` | `nullable\|array` | :126 |
| `anticipos.*.tipo_doc` | `required_with:anticipos, string, SunatCatalog('12')` | :127 |
| `anticipos.*.serie` | `required_with:anticipos\|string\|max:4` | :128 |
| `anticipos.*.correlativo` | `required_with:anticipos\|string\|max:8` (¡string!) | :129 |
| `anticipos.*.monto` | `required_with:anticipos\|numeric\|min:0` | :130 |
| `total_anticipos` | `nullable\|numeric\|min:0` | :132 |
| `total_descuentos` | `nullable\|numeric\|min:0` | :133 |
| `descuentos_globales` | `nullable\|array` | :136 |
| `descuentos_globales.*.cod_tipo` | `required_with:descuentos_globales, string, SunatCatalog('53')` | :137 |
| `descuentos_globales.*.porcentaje` | `nullable\|numeric\|min:0\|max:1` | :138 |
| `descuentos_globales.*.monto` | `required_with:descuentos_globales\|numeric\|min:0` | :139 |
| `descuentos_globales.*.monto_base` | `nullable\|numeric\|min:0` | :140 |
| `extras` | `nullable\|array` (libre) | :142 |
| `observacion` | `nullable\|string\|max:500` | :143 |
| `pagos` | `nullable\|array` | :146 |
| `pagos.*.metodo` | `required_with:pagos\|string` (¡no restringido a METODOS aquí!) | :147 |
| `pagos.*.monto` | `required_with:pagos\|numeric\|gt:0` | :148 |
| `pagos.*.referencia` | `nullable\|string\|max:100` | :149 |
| `pagos.*.monto_recibido` | `nullable\|numeric\|min:0` | :150 |
| `pagos.*.notas` | `nullable\|string\|max:255` | :151 |

Campos fuera de reglas leídos por el controller: `enviar_automatico` (boolean, default `true`, InvoiceController.php:27). `$request->boolean()` usa `FILTER_VALIDATE_BOOLEAN`: `true/"1"/"true"/"on"/"yes"` → true; ausente → true; `false/"0"/"false"/"off"/"no"/""/null` presente → false.

Mensaje personalizado (:155-160): `serie.regex` → `La serie de factura debe empezar con F seguida de 3 caracteres alfanuméricos (ej: F001, FA01, FABC).`

### 4.2 Validaciones `after` (orden exacto, StoreInvoiceRequest.php:162-224)

1. `validarSerieRegistrada($v, '01', 'facturas')` (ValidatesSerieRegistrada.php:235-275). Solo si hay tenant y `serie` es string no vacío. Busca `series` (tenant_id, tipo_documento='01', serie):
   - activa → OK.
   - existe inactiva → `serie`: `La serie {serie} está desactivada. Actívala en el módulo Series para poder emitir facturas.`
   - no existe y el tenant tiene alguna serie tipo 01 → `serie`: `No tienes registrada la serie {serie} para facturas. Revisa el número de serie o regístrala en el módulo Series.`
   - no tiene ninguna tipo 01 → `serie`: `Aún no tienes ninguna serie registrada para facturas. Crea una serie de facturas en el módulo Series antes de emitir.`
2. `validarCorrelativoManual($v, Invoice::class)` (ValidatesManualCorrelativo.php:110-133): si `correlativo` no es null/'' → si existe documento (tenant, serie, correlativo) (excluye soft-deleted) → `correlativo`: `El correlativo {correlativo} ya existe para la serie {serie}. Use otro número o deje el campo vacío para autoincremento.`
3. `validarCodProductoObligatorio($v)` (ValidatesCodProductoSunat.php:23-39): si `tenant->obligado_codigo_producto` truthy → por cada item sin `cod_producto_sunat`: `items.{i}.cod_producto_sunat`: `Su RUC está obligado por SUNAT (padrón "Obligado a enviar código de producto") a consignar el Código de producto SUNAT en cada ítem.`
4. `validarTipoOperacion0112($v)` (:41-55): si `tipo_operacion === '0112'` y ningún item tiene `cod_producto_sunat` ∈ {`84121901`,`80131501`} → `items`: `El tipo de operación 0112 (gastos deducibles persona natural) requiere al menos un ítem con Código de producto SUNAT '84121901' (crédito hipotecario) o '80131501' (arrendamiento de inmuebles).`
5. NRUS: si `tenant->tax_regime === config('regimenes.restringido')` (= `'rus'`, config/regimenes.php:37) → `tax_regime`: `Los contribuyentes del régimen NRUS solo pueden emitir boletas de venta, no facturas.` (:171-177)
6. `forma_pago === 'Credito'` y `cuotas` vacío → `cuotas`: `Las cuotas son obligatorias cuando la forma de pago es Crédito.` (:180-182)
7. Exportación: `tipo_operacion` (default `'0101'`) empieza por `'02'` → si `cliente.tipo_doc === '6'` → `cliente.tipo_doc`: `En exportación el receptor es no domiciliado: use tipo_doc 0 (Doc. trib. no dom. sin RUC), 4, 7 o A, no RUC (6).`; y **return** (no valida RUC) (:191-203).
8. Venta interna: `tipo_doc !== null && !== '6'` → `cliente.tipo_doc`: `Las facturas requieren cliente con RUC (tipo_doc=6). Para clientes con DNI u otros documentos use POST /boletas.` y return (:206-213).
9. `tipo_doc === '6'` y `strlen(num_doc) !== 11` → `cliente.num_doc`: `El RUC debe tener exactamente 11 dígitos.` (:215-217)
10. `tipo_doc === '6'`, longitud 11 y no `/^(10|15|17|20)/` → `cliente.num_doc`: `El RUC no tiene un prefijo válido (10, 15, 17, 20).` (:220-222)

> No se valida que `fecha_vencimiento` exista con crédito, ni que la suma de cuotas = total, ni detracción vs total, ni la moneda de anticipos.

---

## 5. POST /boletas — StoreBoletaRequest (app/Http/Requests/Api/V1/StoreBoletaRequest.php)

Reglas **idénticas** a factura (mismo orden, mismas líneas 29-152) salvo:
- `serie` → `regex:/^B[A-Z0-9]{3}$/` (:31). Mensaje (:158): `La serie de boleta debe empezar con B seguida de 3 caracteres alfanuméricos (ej: B001, BA01, BABC).`
- `cliente.num_doc` → `['required','string','max:15']` (equivalente).

Campos extra leídos por el controller: `solo_registro` (boolean, default false) y `enviar_automatico` (boolean, default true) (BoletaController.php:27-28).

Validaciones `after` (:162-205), en orden:
1. `validarSerieRegistrada($v, '03', 'boletas')` (mensajes con "boletas").
2. `validarCorrelativoManual($v, Boleta::class)`.
3. `validarCodProductoObligatorio`, 4. `validarTipoOperacion0112`.
5. `total = Σ (float)cantidad × (float)precio_unitario` (sin redondeo, sin considerar moneda ni descuentos). `tipoDoc = input('cliente.tipo_doc', '0')`. Si `total > 700 && tipoDoc === '0'` → `cliente.tipo_doc`: `Para boletas mayores a S/ 700.00 es obligatorio consignar el documento de identidad del cliente (DNI, RUC, CE, etc.).`
6. `tipoDoc === '1'` y `strlen(num_doc) !== 8` → `cliente.num_doc`: `El DNI debe tener exactamente 8 dígitos.`
7. `tipoDoc === '6'` y `strlen(num_doc) !== 11` → `cliente.num_doc`: `El RUC debe tener exactamente 11 dígitos.` (sin validación de prefijo).
8. `forma_pago === 'Credito'` y sin cuotas → `cuotas`: `Las cuotas son obligatorias cuando la forma de pago es Crédito.`

No hay restricción NRUS (NRUS sí puede emitir boletas) ni regla de exportación.

---

## 6. NC (07) y ND (08) — StoreCreditNoteRequest / StoreDebitNoteRequest

### 6.1 Reglas NC (app/Http/Requests/Api/V1/StoreCreditNoteRequest.php:28-113), orden:
- `contrato_colaboracion*` (idénticas a §4.1).
- `serie` → `required|string|size:4` (**sin regex**; se exige serie registrada tipo 07) (:32).
- `correlativo` `nullable|integer|min:1`; `cod_local` `nullable|string|size:4`; `fecha_emision` `required|date`; `tipo_moneda` `nullable|string|in:PEN,USD,EUR`. (No hay `fecha_vencimiento`, `tipo_operacion`, `forma_pago`, cuotas, detracción, percepción, anticipos, pagos, extras.)
- `cliente.*` idénticos a factura (:40-45).
- `doc_afectado_tipo` `required|string|in:01,03,12` (:48)
- `doc_afectado_serie` `required|string|size:4` (:49)
- `doc_afectado_correlativo` `required|string` (:50) (¡string!)
- `cod_motivo` `required, string, SunatCatalog('09')` (:51)
- `des_motivo` `required|string|max:250` (:52)
- `observacion` `nullable|string|max:500` (:53)
- Montos: `mto_oper_gravadas`, `mto_oper_exoneradas`, `mto_oper_inafectas`, `mto_oper_gratuitas`, `mto_igv`, `total_impuestos`, `valor_venta`, `sub_total`, `mto_imp_venta`, `sum_otros_descuentos` → `nullable|numeric|min:0` (:56-65) (sin `mto_oper_exportacion`).
- Items: igual a factura **excepto que NO incluye `items.*.porcentaje_isc`** (:68-85) → se descarta en `validated()`.
- `items.*.descuentos*` igual (:88-93). `leyenda`, `leyendas*` igual (:96-99). `guias*` igual (:102-104).
- `descuentos_globales*` igual a factura (:107-111).

`after` (:115-133):
1. `validarSerieRegistrada($v, '07', 'notas de crédito')` (mensajes con "notas de crédito").
2. `validarCorrelativoManual($v, CreditNote::class)`.
3. `validarCodProductoObligatorio($v)` (no hay validación 0112 en notas).
4. `validarMontoVsDocumentoModificado($v)` (ValidatesNotaCreditoMonto.php:159-211):
   - Solo si NO hay errores previos, hay tenant, `cod_motivo !== '10'` y `doc_afectado_tipo ∈ {'01','03'}`.
   - Busca Invoice/Boleta del tenant con `serie=doc_afectado_serie` y `correlativo=(int)doc_afectado_correlativo`. Si no existe → no valida. Si `input('tipo_moneda','PEN') !== doc.tipo_moneda` → no valida.
   - Calcula total NC con `calculateItems($this->all()['items'], $tenant, fecha)` + `calculateTotals(...)` → `round(mto_imp_venta,2)` (si lanza excepción → no valida).
   - Tolerancia: boleta 1.0, factura 0.0. Si `ncTotal > docTotal + tol` → `doc_afectado_correlativo`: `El importe total de la nota de crédito ({ncTotal}) no puede superar el del documento que modifica {serie}-{corr} ({docTotal}) (ERR-3286/3503).` (floats impresos con conversión PHP a string: `236` / `236.5`).
   - Nota: el código '10' no existe en el Cat. 09 configurado (01–09), así que en la práctica siempre aplica.
5. NRUS (`tax_regime==='rus'`) y `doc_afectado_tipo !== '03'` → `doc_afectado_tipo`: `Los contribuyentes del régimen NRUS solo pueden emitir notas de crédito contra boletas de venta (tipo 03).`

### 6.2 Diferencias ND (StoreDebitNoteRequest.php vs NC)
- `doc_afectado_tipo` → `nullable|required_unless:cod_motivo,13|string|in:01,03,12`; `doc_afectado_serie` → `nullable|required_unless:cod_motivo,13|string|size:4`; `doc_afectado_correlativo` → `nullable|required_unless:cod_motivo,13|string` (opcionales solo con motivo 13 "Penalidades").
- `cod_motivo` → `SunatCatalog('10')`.
- **Sin** `descuentos_globales*` (se descartan).
- Sin `validarMontoVsDocumentoModificado`.
- `after` (StoreDebitNoteRequest.php:107-138): serie registrada `'08','notas de débito'`; correlativo manual (DebitNote); cod producto obligatorio; si `cod_motivo === '13'`: `igvTotal = (float)input('mto_igv',0) + (float)input('mto_ivap',0) + Σ (float)items[i].igv` (valores enviados por el cliente, no calculados); si > 0 → `cod_motivo`: `La nota de débito por Penalidades (motivo 13) solo admite operaciones inafectas del IGV/IVAP (ERR-3507): los importes de IGV/IVAP deben ser 0.`; NRUS con `doc_afectado_tipo !== '03'` → `doc_afectado_tipo`: `Los contribuyentes del régimen NRUS solo pueden emitir notas de débito contra boletas de venta (tipo 03).` (con motivo 13 y sin doc afectado, un NRUS recibe este error porque `null !== '03'`).
- Mensaje required_unless ejemplo: `El campo doc afectado tipo es obligatorio a menos que cod motivo esté en 13.`

---

## 7. Lógica de creación (Actions)

### 7.1 CreateInvoiceAction (app/Actions/Documents/CreateInvoiceAction.php:27-123)
Todo dentro de `DB::transaction` (:29):
1. `Serie` (tenant, tipo '01', serie, `is_active=true`) `->with('sucursal')->lockForUpdate()->firstOrFail()` (:30-36). (Si falla → `ModelNotFoundException` capturada por el controller → 500, ver §10.1.)
2. Correlativo: `$serie->resolveCorrelativo($data['correlativo'] ?? null)` (:38) → app/Models/Serie.php:283-300, en transacción anidada con `lockForUpdate` sobre la fila de serie:
   - automático: `increment('correlativo')` y devuelve el nuevo valor (la columna `series.correlativo` = **último usado**, default 0 → primer comprobante = 1).
   - manual: si `manual > serie.correlativo` actualiza el puntero; devuelve `manual` (no retrocede; permite rellenar huecos).
3. `$client = ClientResolverService::resolve($tenant, $data['cliente'])` (:44) → app/Services/ClientResolverService.php:173-196: `Client::updateOrCreate([tenant_id, tipo_documento=tipo_doc, numero_documento=num_doc], ['razon_social', 'direccion'=>?? null, +email si no vacío, +telefono si no vacío])`. (Sobrescribe razón social/dirección del cliente maestro; email/teléfono solo si vienen.)
4. `calculateItems($data['items'], $tenant, $fechaEmision)` y `calculateTotals($items, $data /*por referencia*/, $tenant, $fecha)` (:47-48); `array_merge($data, $totals)` (:49). calculateTotals modifica `$data['descuentos_globales']` (normalizado) y `$data['total_anticipos']`.
5. Leyenda (:51-56): si `leyenda` vacía → `generateLeyenda(percepcion.mto_total si no vacío, si no totals.mto_imp_venta, tipo_moneda ?? 'PEN')`. (En store `percepcion.mto_total` nunca llega: se descarta en validated().)
6. `Invoice::create` (:58-101) con:
   - `sucursal_id = serie.sucursal?.id`; `cod_local = serie.sucursal?.cod_local ?? data.cod_local ?? '0000'` (la sucursal de la serie tiene prioridad sobre el payload).
   - `fecha_emision = resolveEmisionDateTime(data.fecha_emision)` (Concerns/ResolvesEmisionDateTime.php:145-156): si `^\d{4}-\d{2}-\d{2}$` → `"{fecha} {HH:mm:ss actual de America/Lima}"`; si no → `Carbon::parse($fecha, 'America/Lima')->setTimezone('America/Lima')->format('Y-m-d H:i:s')` (naive = hora Lima; con zona/Z → convertida a Lima).
   - defaults: `tipo_operacion ?? '0101'`, `tipo_moneda ?? 'PEN'`, `forma_pago ?? 'Contado'`, `fecha_vencimiento ?? null`.
   - snapshot cliente: `client_tipo_doc/num_doc/razon_social/direccion`.
   - totales calculados (mto_oper_*, mto_igv, mto_base_ivap, mto_ivap, mto_isc, mto_icbper, total_impuestos, valor_venta, sub_total, mto_imp_venta).
   - `total_anticipos = data.total_anticipos ?? Σ anticipos.monto`; `total_descuentos = data.total_descuentos ?? 0` (tras el merge ya contiene el calculado).
   - JSON: `cuotas, detraccion, percepcion, anticipos, descuentos_globales (normalizado), guias, contrato_colaboracion, extras` (tal cual validados).
   - `sunat_status = 'pendiente'`. `payment_status` default BD `'pendiente'`, `monto_pagado` default 0.
   - **No se persisten**: `leyendas` (array Cat. 52), `sum_otros_descuentos`, `mto_igv_gratuitas`.
7. Items: `$invoice->items()->create($item)` por cada item calculado (:103-105).
8. `event(DocumentCreated)`, `Cache::forget("tenant:{id}:doc_count:Y-m")` y `sunat_count`, `PlanService::incrementUsage($tenant,'documents')` (incrementa `tenants.documents_this_month`) (:107-110).
9. Si `enviarAutomatico` → `SendDocumentToSunat::dispatch(Invoice::class, id)` (cola `sunat`) y `update(['sunat_status'=>'enviado'])` (:112-115). Si no, queda `pendiente`.
10. Si `pagos` no vacío → `RegisterPaymentAction::execute($invoice, $pagos)` (:117-119).
11. Devuelve `$invoice->fresh(['items','payments','client'])`.

### 7.2 CreateBoletaAction (app/Actions/Documents/CreateBoletaAction.php)
Igual a factura con: tipo '03'; `tipo_operacion ?? (tenant.tax_regime === 'rus' ? '0113' : '0101')` (:68); firma `execute($tenant, $data, $soloRegistro=false, $enviarAutomatico=true)`; envío (:113-119): `solo_registro` → no se encola (queda `pendiente`, para resumen diario); si no y `enviar_automatico` → dispatch + `enviado`; si no → `pendiente`.

### 7.3 CreateCreditNoteAction / CreateDebitNoteAction (app/Actions/Documents/CreateCreditNoteAction.php)
Igual flujo (tipo '07'/'08'), sin pagos, sin `RegisterPaymentAction`. Leyenda: `generateLeyenda(totals.mto_imp_venta, tipo_moneda ?? 'PEN')` si vacía (:49-51). Campos guardados (:53-89): tenant, sucursal, cod_local, client_id, serie, correlativo, fecha_emision, tipo_moneda (default PEN), cliente snapshot, `doc_afectado_tipo/serie/correlativo` (ND: `?? null`), `cod_motivo`, `des_motivo`, mto_oper_gravadas/exoneradas/inafectas/gratuitas, mto_igv, mto_isc, mto_icbper, total_impuestos, valor_venta, sub_total, mto_imp_venta, `total_anticipos = data.total_anticipos ?? 0`, `total_descuentos ?? 0`, leyenda, observacion, guias, contrato_colaboracion, `sunat_status='pendiente'`. **No guarda** `descuentos_globales` (NC) ni exportación/IVAP. Devuelve `fresh(['items','client'])`.

### 7.4 Job `SendDocumentToSunat` (app/Jobs/SendDocumentToSunat.php) — asíncrono
- Cola `sunat` (:41), `tries=20`, `backoff=[15,30,60,120,300,600,1200,1800,3600×12]`, `timeout=90`. Middleware: `RateLimited('sunat-tenant')` (por tenant, `facturacion.throughput.per_tenant_per_minute` default 120/min) y `WithoutOverlapping("sunat-send:{class}:{id}")->expireAfter(180)` (:50-60). Conexión por defecto `database` (`QUEUE_CONNECTION=database`), `after_commit=false` (config/queue.php:44).
- `handle()` (:68-216):
  - Si estado ∈ {aceptado, anulado} → no hace nada (:77-79).
  - Si `pendiente` con `sunat_code='SUNAT_TIMEOUT'` → consulta CDR primero (`recoverFromCdr`).
  - Circuit breaker abierto → `release(300 + rand(0..120))`.
  - Construye con Greenter (`buildInvoice` para 01/03, `buildNote` 07/08) a partir de `$document->toArray()` + items de BD (:355-413) y envía.
  - `SoapFault` → reintento; `XmlSignException`/`RuntimeException` → `rechazado` con `sunat_code='CERT_ERROR'`.
  - Éxito: `sunat_status = accepted ? 'aceptado' : 'rechazado'`, `sunat_code`, `sunat_description`, `sunat_notes`, `hash_cpe`, `sent_at=now()`; guarda XML y CDR (`DocumentStorageService`), genera PDF A4 (`pdf.auto_generate=true`), evento `DocumentSent`, webhook `document.sent` si `webhook_url`.
  - Error: códigos reintentables `0,100,109,500,1033,2800,HTTP,NETWORK_ERROR` → backoff; agotados → consulta CDR; si no → `pendiente`, `sunat_code='SUNAT_TIMEOUT'`, `sunat_description="SUNAT no disponible después de 20 intentos. Use POST /reenviar cuando SUNAT esté disponible."`, webhook `document.timeout_sunat`.
  - Código numérico ≥ 4000 → `aceptado` (observaciones) con code/descr truncados (20/500), guarda XML/CDR/PDF.
  - Otro → `rechazado` (code `substr(...,0,20)`, descripción `substr(...,0,500)`), guarda XML si vino, webhook `document.rejected`, evento `DocumentRejected`.
- Estados posibles de `sunat_status` (enum BD): `pendiente`, `enviado`, `aceptado`, `rechazado`, `anulado`, `anulacion_en_proceso` (migración 2026_03_10_140826).

### 7.5 Idempotencia
Solo la del middleware (§1.2). No hay idempotencia de negocio por serie/correlativo salvo el índice único `(tenant_id, serie, correlativo)` en invoices/boletas/credit_notes/debit_notes (p. ej. migración create_invoices_table) y la validación de correlativo manual.

---

## 8. Motor de cálculo — fórmulas exactas

### 8.1 TaxRateService (app/Services/TaxRateService.php)
- `defaultIgvRate(tenant, fecha)` (:64-81): si `tenant.igv_rate_override !== null` → ese valor (float); si `tax_regime === 'rus'` → 0.0; si no → **18.0**. (La fecha ya no influye; `LEY_31556` es solo referencia.)
- `defaultTipAfeIgv(tenant)` (:105-108): `'30'` si `tax_regime === 'rus'`, si no `'10'`.
- `rateForItem(item, tenant, fecha)` (:122-142): (1) `isset(item.porcentaje_igv)` → `(float)porcentaje_igv`; (2) tip (o default) `'17'` → 4.0; (3) `'10'` o `11..16` → `defaultIgvRate`; (4) resto → 0.0.

### 8.2 calculateItems (app/Services/DocumentCalculationService.php:16-186)
Constantes: gratuitas gravadas G = {11,12,13,14,15,16}; gratuitas inafectas/exoneradas I = {21,31,32,33,34,35,36}; gratuitas = G ∪ I. (Nota: '37' no está en ninguna lista → cae en rama "else".)

Por ítem:
```
tip   = item.tip_afe_igv ?? defaultTipAfeIgv(tenant)                       (:26)
pIGV  = rateForItem(item, tenant, fecha)                                    (:28)
q     = (float) cantidad ; P = (float) precio_unitario
pISC  = (float)(porcentaje_isc ?? 0)
-- valor unitario (:36-53)
tip=='10': si pISC>0 y !isset(mto_valor_unitario): vu = round(P / ((1+pISC/100)*(1+pIGV/100)), 10)
           si no:                                   vu = round(P / (1+pIGV/100), 10)
tip=='17': vu = round(P / (1+pIGV/100), 10)
tip∈{20,30,40}: vu = P
tip∈G o I: vu = 0
otro: vu = (float)(mto_valor_unitario ?? P)
-- descuentos de línea (:55-93), solo si item.descuentos no vacío
valorBruto       = (esGratuito ? (float)(mto_valor_unitario ?? P) : vu) * q
totalConIgvBruto = round(P*q, 2)
por cada desc: cod = desc.cod_tipo ?? '00'
  si cod ∈ {'00','01'}:
     montoBase = round(valorBruto, 2)
     si !empty(porcentaje): factor = round(porcentaje/100, 6); monto = round(montoBase*factor, 2)
     elif !empty(factor):   factor = (float)factor;           monto = round(montoBase*factor, 2)
     else:                  monto = (float)(monto ?? 0); factor = montoBase>0 ? round(monto/montoBase, 6) : 0
     descuentoBase   += monto
     descuentoConIgv += round(totalConIgvBruto * factor, 2)
     guarda {cod_tipo, monto_base, factor, monto}
  si no: se guarda el desc tal cual (no afecta cálculo)
-- valor venta e IGV (:95-127)
tip=='10': vv = round(vu*q - descuentoBase, 2); igv = round( (round(P*q,2) - descuentoConIgv) - vv, 2)
tip=='20': vv = round(vu*q - descuentoBase, 2); igv=0; pIGV=0
tip∈{30,40}: idem; igv=0; pIGV=0
tip=='17': vv = round(vu*q - descuentoBase, 2); igv = round((round(P*q,2) - descuentoConIgv) - vv, 2)
tip∈G: vref=(float)(mto_valor_unitario ?? P); vu=0; vv=round(vref*q,2); igv=(float)(item.igv ?? round(vv*pIGV/100,2))
tip∈I: vref=idem; vu=0; vv=round(vref*q,2); igv=0; pIGV=0
otro: vu=(float)(mto_valor_unitario ?? P); vv=round(vu*q,2); igv=(float)(item.igv ?? 0)
-- overrides (:129-160)
si !gratuito: vu = (float)(item.mto_valor_unitario ?? vu)
vv = (float)(item.mto_valor_venta ?? vv)
isc = (float)(item.isc ?? 0); icbper = (float)(item.icbper ?? 0); fIcb = (float)(item.factor_icbper ?? 0)
si isc<=0 y pISC>0: isc = round(vv*pISC/100, 2)
si icbper<=0 y fIcb>0: icbper = round(fIcb*q, 2)
si isc>0 y !isset(item.mto_base_igv) y !isset(item.igv): base = round(vv+isc, 2); igv = round(base*pIGV/100, 2)
si no: igv = (float)(item.igv ?? igv); base = (float)(item.mto_base_igv ?? vv)
si tip∈I: base = vv
totalImp = (float)(item.total_impuestos ?? (igv + isc + icbper))
```
Salida por ítem (:164-182): `codigo, cod_producto_sunat, descripcion, unidad, cantidad, mto_valor_unitario, mto_valor_venta, mto_base_igv, porcentaje_igv, igv, tip_afe_igv, isc, icbper, total_impuestos, mto_precio_unitario (=P), descuento ((float)(item.descuento ?? descuentoBase)), descuentos (recalculados ?? item.descuentos ?? null)`.
- Redondeo PHP `round()` = half away from zero (¡no banker's!). Reimplementaciones deben usar HALF_UP sobre el valor double (ojo: PHP aplica "pre-rounding" para corregir errores binarios — `round(1.005,2)` = 1.01 en PHP, mientras que muchos lenguajes dan 1.00; usar BigDecimal desde la representación decimal corta del double para emularlo).
- Almacenamiento: `mto_valor_unitario` y `mto_precio_unitario`, `cantidad` → DECIMAL(12,4); importes → DECIMAL(12,2); `porcentaje_igv` DECIMAL(5,2) (migración create_invoice_items_table). El valor unitario de 10 decimales se trunca/redondea a 4 al persistir (MySQL redondea).
- **No hay** ICBPER automático por año (el factor del config no se usa aquí; solo si el cliente envía `factor_icbper` o `icbper`).
- `tip_sis_isc` y `factor_icbper` NO se guardan en items (no están en fillable); el job manda `tip_sis_isc ?? '01'` y `factor_icbper ?? 0` (SendDocumentToSunat.php:401-403).

### 8.3 calculateTotals (DocumentCalculationService.php:188-327)
```
por ítem: tip=='10' → gravadas += vv; igv += item.igv
          '17'      → baseIvap += vv; ivap += item.igv
          '20'      → exoneradas += vv
          '30'      → inafectas += vv
          '40'      → exportacion += vv
          ∈ gratuitas → gratuitas += vv; igvGratuitas += item.igv
          (otros p. ej. '37' no suman a ninguna base)
          isc += item.isc ; icbper += item.icbper   (todos)
si anticipos y !isset(total_anticipos): data.total_anticipos = Σ anticipos.monto
descuentos_globales (si no vacío), por cada desc:
   cod = cod_tipo ?? '02'
   si !empty(porcentaje) y empty(monto): base = cod∈{02,04,05,06} ? gravadas : (exon+inaf+export+grav); monto = round(base*porcentaje/100, 2)
   si no: monto = (float)(monto ?? 0)
   montoBase = round(cod∈{02,04,05,06} ? gravadas : (grav+exon+inaf), 2); factor = montoBase>0 ? round(monto/montoBase, 6) : 0
   normalizado = desc + {monto, monto_base: (float)(desc.monto_base ?? montoBase), factor: (float)(desc.factor ?? factor)}
   cod=='02' → descGlobalGrav += monto ; cod=='03' → sumDescNoBase += monto   (04/05/06 solo se normalizan)
   data.descuentos_globales = normalizados
si descGlobalGrav>0: rate = gravadas>0 ? igv/gravadas : defaultIgvRate/100; gravadas -= descGlobalGrav; igv = round(gravadas*rate, 2)
gravadas = max(0, round(gravadas,2)); idem exoneradas, inafectas, exportacion, baseIvap, ivap, gratuitas, igv, isc, icbper
igvGratuitas = round(igvGratuitas,2)
totalImpuestos = round(igv + ivap + isc + icbper, 2)
valorVenta = round(grav + exon + inaf + export + baseIvap, 2)
subTotal = round(valorVenta + totalImpuestos, 2)
totalAnticipos = (float)(data.total_anticipos ?? Σ anticipos.monto)
mtoImpVenta = max(0, round(subTotal - totalAnticipos - sumDescNoBase, 2))
```
Retorno (cada valor se reemplaza por el del payload si viene, `(float)($data[k] ?? calculado)`) (:308-326): `mto_oper_gravadas, mto_oper_exoneradas, mto_oper_inafectas, mto_oper_exportacion, mto_oper_gratuitas, mto_igv, mto_base_ivap, mto_ivap, mto_igv_gratuitas, mto_isc, mto_icbper, total_impuestos, valor_venta, sub_total, mto_imp_venta, sum_otros_descuentos, total_descuentos (= data.total_descuentos ?? descGlobalGrav + sumDescNoBase)`. En store solo pueden venir los validados (§4.1); en `update`/`masivo` cualquiera (sin validación).
- Detracción y percepción **no** alteran totales (solo se guardan; la percepción la arma el builder XML).

### 8.4 generateLeyenda (DocumentCalculationService.php:329-399)
`entero = (int)total; dec = round((total-entero)*100)`; moneda `PEN` → `SOLES`, cualquier otra (USD **y EUR**) → `DOLARES AMERICANOS`. Formato: `"{LETRAS} CON {dec 2 dígitos}/100 {MONEDA}"`, p. ej. 236 → `DOSCIENTOS TREINTA Y SEIS CON 00/100 SOLES`.
`numberToWords`: 0→`CERO`; 100→`CIEN`; millones: 1→`UN MILLON`, n→`{n} MILLONES`; miles: 1→`MIL`, n→`{n} MIL`; centenas `CIENTO, DOSCIENTOS, …, NOVECIENTOS` (resto exacto 100 → `CIEN`); 10–15 `DIEZ…QUINCE`; 16–19 `DIECI`+unidad; 21–29 `VEINTI`+unidad; otras decenas `{DECENA}[ Y {UNIDAD}]`; unidades `UNO…NUEVE` (sin apócope: 1 → `UNO`, 21 → `VEINTIUNO`, 21000 → `VEINTIUNO MIL`, 101 → `CIENTO UNO`); `trim` final. Copiar el algoritmo literalmente (incluye sus imperfecciones).

---

## 9. Resources (estructura exacta, orden de claves)

### 9.1 InvoiceResource (app/Http/Resources/Api/V1/InvoiceResource.php:12-126)
```
id (int)
tipo_documento: "01"
serie, correlativo (int), numero_completo ("{serie}-{correlativo con 6 dígitos 0-pad}", HasDocumentFields.php:16-19; p. ej. "F001-000123")
cod_local
fecha_emision ("Y-m-d H:i:s")
fecha_vencimiento ("Y-m-d" | null)
tipo_operacion, tipo_moneda, forma_pago
cliente: {tipo_doc, num_doc, razon_social, direccion, email, telefono}   ← se eliminan las claves null;
         email/telefono salen del Client maestro ACTUAL (lazy-load relación client)
totales: {gravadas, exoneradas, inafectas, exportacion, gratuitas, igv, base_ivap, ivap, isc, icbper,
          total_impuestos, valor_venta, sub_total, total}
         ← floats; se omite toda clave con valor <= 0 excepto total_impuestos, valor_venta, sub_total, total (siempre)
items (solo si relación cargada): [ {codigo, descripcion, unidad, cantidad, precio_unitario, valor_unitario,
         igv, isc, icbper, descuento, descuentos, total} ]
         total = round(mto_valor_venta + igv + icbper + isc, 2)
         se omiten descuento/isc/icbper/descuentos si == 0 o null; codigo null SÍ aparece (null) — el filtro solo aplica a esas 4 claves
         descuentos = [{cod_tipo (?? "00"), monto_base, factor, monto}] (floats)
detraccion (si truthy, objeto tal cual guardado)
percepcion (si truthy)
anticipos (si truthy)
cuotas (si truthy)
descuentos_globales (si no vacío): [{cod_tipo (?? "02"), monto_base, factor, monto}]
guias (si truthy)
extras (si truthy)
sunat: {estado, codigo, descripcion, notas (array|null), hash_cpe}
archivos: {xml: url si xml_path, cdr: url si cdr_path, pdf: url si pdf_path}   ← null si no hay archivo;
          url() absoluto: "{APP_URL/host}/api/v1/facturas/{id}/xml|cdr|pdf"
leyenda, observacion
estado_pago (payment_status: pendiente|parcial|pagado)
monto_pagado (float)
pagos (solo si relación cargada): [{id, metodo, monto, referencia, monto_recibido, vuelto, notas, creado_en}] (ver §11.4)
enviado_en (sent_at ISO8601 | null)
creado_en (created_at ISO8601)
```
Nota: `contrato_colaboracion`, `total_anticipos`, `total_descuentos`, `sucursal_id`, `mto_base_igv`, `porcentaje_igv`, `tip_afe_igv`, `cod_producto_sunat` **no** se devuelven.

### 9.2 BoletaResource (app/Http/Resources/Api/V1/BoletaResource.php) — diferencias con factura
- `tipo_documento: "03"`.
- Items: **sin** clave `descuentos`; filtro: se omiten `descuento`, `isc`, `icbper` si `== 0`.
- Orden de claves opcionales: `detraccion, percepcion, anticipos, cuotas, descuentos_globales, guias` — `cuotas` va después de `anticipos` en ambos, pero **`descuentos_globales` se devuelve CRUDO** (tal como se guardó normalizado: `{cod_tipo, porcentaje?, monto, monto_base, factor}` en el orden de `array_merge($desc, [...])`), no reformateado.
- **No** devuelve `extras`.
- `archivos` con `/api/v1/boletas/{id}/...`.

### 9.3 CreditNoteResource / DebitNoteResource (app/Http/Resources/Api/V1/CreditNoteResource.php:12-77)
```
id, tipo_documento ("07" | "08"), serie, correlativo, numero_completo, cod_local, fecha_emision, tipo_moneda,
cliente {…igual, sin nulls},
doc_afectado: {tipo, serie, correlativo (string), motivo_codigo, motivo_descripcion}   ← sin filtrar (ND motivo 13 puede tener null)
totales: {gravadas, exoneradas, inafectas, gratuitas, igv, isc, icbper, total_impuestos, valor_venta, sub_total, total}
         ← mismo filtro (>0 o claves siempre presentes); SIN exportacion/base_ivap/ivap
items (si cargados): [{codigo, descripcion, unidad, cantidad, precio_unitario, valor_unitario, igv, descuento, total}]
         ← total = mto_valor_venta (¡SIN IGV, distinto a factura!); se omite descuento si == 0; sin isc/icbper/descuentos
guias (si truthy)
sunat {estado, codigo, descripcion, notas, hash_cpe}
archivos {xml, cdr, pdf} → /api/v1/notas-credito/{id}/... | /api/v1/notas-debito/{id}/...
leyenda, observacion, enviado_en, creado_en
```
Sin `estado_pago`, `monto_pagado`, `pagos`, `descuentos_globales`, `fecha_vencimiento`, `tipo_operacion`, `forma_pago`.

### 9.4 Ejemplo literal (factura creada, enviar_automatico=true, 1 ítem 2×118 gravado, sin pagos)
HTTP 201:
```json
{"estado":"exito","mensaje":"Factura creada y encolada para env\u00edo a SUNAT.","datos":{"id":15,"tipo_documento":"01","serie":"F001","correlativo":8,"numero_completo":"F001-000008","cod_local":"0000","fecha_emision":"2026-10-24 10:15:30","fecha_vencimiento":null,"tipo_operacion":"0101","tipo_moneda":"PEN","forma_pago":"Contado","cliente":{"tipo_doc":"6","num_doc":"20555666777","razon_social":"ACME SAC"},"totales":{"gravadas":200,"igv":36,"total_impuestos":36,"valor_venta":200,"sub_total":236,"total":236},"items":[{"codigo":"P001","descripcion":"PRODUCTO","unidad":"NIU","cantidad":2,"precio_unitario":118,"valor_unitario":100,"igv":36,"total":236}],"sunat":{"estado":"enviado","codigo":null,"descripcion":null,"notas":null,"hash_cpe":null},"archivos":{"xml":null,"cdr":null,"pdf":null},"leyenda":"DOSCIENTOS TREINTA Y SEIS CON 00\/100 SOLES","observacion":null,"estado_pago":"pendiente","monto_pagado":0,"pagos":[],"enviado_en":null,"creado_en":"2026-10-24T15:15:30+00:00"}}
```
(`cliente.direccion` omitido por ser null; `direccion`/`email`/`telefono` aparecen si existen.)

---

## 10. Acciones por endpoint

### 10.1 store (facturas / boletas / notas)
- Validación FormRequest → 422 (§2.3).
- Llama Action dentro de `try { } catch (\Throwable $e)` → cualquier excepción (BD, serie no encontrada, carrera de unique, etc.) → **500** `{"estado":"error","mensaje":"Error al crear factura: {getMessage()}"}` (InvoiceController.php:37-39). Prefijos: `Error al crear boleta: `, `Error al crear nota de crédito: `, `Error al crear nota de débito: `. (Expone el mensaje interno, p. ej. `No query results for model [App\Models\Serie].` o SQLSTATE…)
- Éxito → 201 `created(Resource, msg)`:
  - Factura: `enviar_automatico` true → `Factura creada y encolada para envío a SUNAT.`; false → `Factura creada en estado pendiente. Use POST /facturas/{id}/enviar para enviarla a SUNAT.` (literal `{id}`) (InvoiceController.php:32-34).
  - Boleta (BoletaController.php:33-37): `solo_registro` → `Boleta registrada. Pendiente de envío vía resumen diario.`; `!enviar_automatico` → `Boleta creada en estado pendiente. Use POST /boletas/{id}/enviar para enviarla a SUNAT.`; default → `Boleta creada y encolada para envío a SUNAT.` (`solo_registro` gana sobre `enviar_automatico`).
  - NC: `Nota de crédito creada y encolada para envío a SUNAT.` / `Nota de crédito creada en estado pendiente. Use POST /notas-credito/{id}/enviar para enviarla a SUNAT.`; ND: `Nota de débito creada y encolada para envío a SUNAT.` / `Nota de débito creada en estado pendiente. Use POST /notas-debito/{id}/enviar para enviarla a SUNAT.`
- El Resource devuelto tiene `items` y (factura/boleta) `pagos` cargados; `sunat.estado` = `enviado` (auto) o `pendiente`.

### 10.2 POST /facturas/masivo (InvoiceController.php:42-111)
- Body: `{"facturas":[ {...payload de factura...}, ... ]}`. **Sin FormRequest** (sin reglas, sin catálogos, sin regex de serie, sin after-hooks).
- `facturas` no array o vacío → 422 `{"estado":"error","mensaje":"El campo \"facturas\" debe ser un array con al menos 1 elemento."}` (:48-50).
- `count > 100` → 422 `{"estado":"error","mensaje":"Máximo 100 facturas por envío masivo."}` (:52-55).
- Por elemento (índice del array, preserva claves si es objeto): si `empty()` alguno de `serie, fecha_emision, cliente, items` → error `{"indice":i,"mensaje":"Campos requeridos faltantes: serie, items","data":{...}}` (:62-75). Si no: `CreateInvoiceAction::execute($tenant, $data, true)` (siempre envío automático; ignora `enviar_automatico`); cada factura en su propia transacción (éxitos parciales). Excepción → `{"indice":i,"mensaje":getMessage(),"data":{...}}`.
- Éxito por ítem: `{"indice":i,"id":int,"numero_completo":"F001-000009","total":<mto_imp_venta>,"estado_sunat":"enviado"}` — `total` es el valor del cast `decimal:2` → **string** `"236.00"` (no se castea a float) (:79-85).
- Respuesta (:98-110): `success(datos, msg, code)`:
```json
{"estado":"exito","mensaje":"2 factura(s) creadas y encoladas para envío a SUNAT.","datos":{"resumen":{"total_enviadas":3,"creadas":2,"errores":1},"facturas":[...],"errores":[...]}}
```
  HTTP 201 si `creadas > 0`; si 0 → HTTP **422** con `estado:"exito"` y mensaje `Ninguna factura fue creada. Revisa los errores.`
- `check.limit` se evalúa una sola vez para todo el lote.

### 10.3 index
**Facturas/Boletas** (InvoiceController.php:113-150, BoletaController.php:45-81):
- Base `forTenant(tenant.id)` (excluye soft-deleted).
- `?con=items,payments` (separado por comas, intersección con permitidas `items`,`payments`) → eager load (y aparecen `items`/`pagos` en cada elemento). `?correlativo=` presente → carga `items` automáticamente.
- Filtros `AppliesDocumentFilters` (app/Http/Traits/AppliesDocumentFilters.php:14-142), todos con `filled()` (no vacío):
  - `fecha_desde` / `fecha_hasta` → `whereDate(fecha_emision >= / <=)`
  - `vencimiento_desde` / `vencimiento_hasta` → `whereDate(fecha_vencimiento ...)`
  - `sunat_status` → `whereIn` (CSV, trim)
  - `estado_pago` → `whereIn(payment_status)` (CSV)
  - `serie` → exacto, o `LIKE` si contiene `%`
  - `correlativo_desde`, `correlativo_hasta` (int, >= / <=), `correlativo` (int, =)
  - `client_num_doc` (=), `client_tipo_doc` (=), `cliente` (`client_razon_social LIKE %x%`)
  - `tipo_moneda` (= strtoupper), `tipo_operacion` (=), `forma_pago` (=), `sucursal_id` (int =)
  - `monto_min` / `monto_max` → `mto_imp_venta >= / <=` (float)
  - `search` o `q` → `(razon_social LIKE %t% OR client_num_doc LIKE %t% OR serie LIKE %t% [OR correlativo = (int)t si numérico])`
  - `con_cdr` (boolean) → `cdr_path IS NOT NULL`
  - Orden: `ordenar_por` ∈ {fecha_emision, fecha_vencimiento, created_at, updated_at, correlativo, mto_imp_venta, sunat_status, payment_status, client_razon_social} (si no, `created_at`); `orden` asc|desc (default desc).
- Paginación: `por_pagina` = `min((int)input('por_pagina',15), 100)`; página con `?page=N` (Laravel). `paginate(0)` usa el perPage del modelo (15).
- Respuesta 200:
```json
{"estado":"exito","mensaje":"OK","datos":{"datos":[ {Resource sin items/pagos salvo ?con} ],"paginacion":{"pagina_actual":1,"ultima_pagina":3,"por_pagina":15,"total":42}}}
```
**NC/ND** (CreditNoteController.php:41-83) — distinto, NO usa el trait:
- Orden fijo `created_at DESC`. Filtros con `has()` (presencia): `sunat_status` (=, scope `status(string)`), `fecha_desde`+`fecha_hasta` (ambos requeridos → `whereBetween(fecha_emision, ["{desde} 00:00:00","{hasta} 23:59:59"])`), `serie` (=), `client_num_doc` (=), `sucursal_id` (=), `tipo_moneda` (= sin upper).
- `por_pagina` = `$request->integer('por_pagina', 15)` **sin tope de 100**.
- No admite `con`; `items` nunca presente en el listado.
- Misma envoltura `{datos:{datos:[],paginacion:{...}}}`.

### 10.4 show
- Factura/Boleta: `with(['items','payments'])->forTenant()->findOrFail($id)` → 200 `success(Resource)` (mensaje `OK`). No existe / otro tenant / soft-deleted → 404 `Recurso no encontrado.`
- NC/ND: `with('items')` (sin pagos).

### 10.5 update (PUT) — Factura/Boleta (InvoiceController.php:160-266; BoletaController.php:91-192)
- Carga con items y payments; 404 si no existe.
- Si `sunat_status === 'aceptado'` → 422 `errorAccionable`:
```json
{"estado":"error","mensaje":"No se puede editar una factura aceptada por SUNAT. Emita una Nota de Crédito para corregir o anular.","codigo_error":"documento_aceptado_no_editable","siguiente_accion":{"operacion":"emitir_nota_credito","endpoint":"POST /api/v1/notas-credito","doc_afectado":{"tipo":"01","serie":"F001","correlativo":8}}}
```
  (Boleta: mensaje `No se puede editar una boleta aceptada por SUNAT. Emita una Nota de Crédito para corregir o anular.` y `tipo:"03"`; `correlativo` int.)
- Cualquier otro estado (pendiente, enviado, rechazado, **anulado, anulacion_en_proceso**) se permite.
- **Sin validación** (`$request->all()`), en `DB::transaction`:
  1. Si `cliente` no vacío → fusiona `tipo_doc/num_doc/razon_social/direccion` (claves ausentes mantienen el valor) y `ClientResolverService::resolve` (no actualiza `client_id` del documento).
  2. Campos simples (si la clave existe, incluso null): `fecha_vencimiento, tipo_operacion, tipo_moneda, forma_pago, observacion`.
  3. Si `items` no vacío: `calculateItems($items)` y `calculateTotals($items, $data)` **sin tenant ni fecha** (→ IGV 18 % y tip default '10' aunque el tenant sea RUS u override); `fill($totals)`; `leyenda = data.leyenda ?? generateLeyenda(percepcion.mto_total ?: mto_imp_venta, data.tipo_moneda ?? doc.tipo_moneda ?? 'PEN')`; borra items y `insert` masivo con: `invoice_id|boleta_id, codigo, descripcion, unidad, cantidad, mto_valor_unitario, mto_valor_venta, mto_base_igv, porcentaje_igv, igv, tip_afe_igv, isc, icbper, total_impuestos, mto_precio_unitario, descuento, created_at, updated_at` (pierde `cod_producto_sunat` y `descuentos`).
     No cambia `serie`, `correlativo`, `fecha_emision`, `detraccion`, `cuotas`, etc.
  4. Reset: `sunat_status='pendiente'`, `sunat_code/description/notes = null`; `save()`; `SendDocumentToSunat::dispatch` (sin poner `enviado`).
- 200: `{"estado":"exito","mensaje":"Factura actualizada y reenviada a SUNAT.","datos":{Resource con items y pagos}}` (Boleta: `Boleta actualizada y reenviada a SUNAT.`). `sunat.estado` = `pendiente`.
- Excepciones dentro (p. ej. `cliente` no array, item sin `cantidad`) → handler global 500 `Error interno del servidor.`

**update NC/ND** (CreditNoteController.php:93-197): igual pero:
- Bloqueo si aceptado → 422 `{"estado":"error","mensaje":"No se puede editar una nota de crédito aceptada por SUNAT. Si la NC es errónea, anúlela por comunicación de baja.","codigo_error":"documento_aceptado_no_editable","siguiente_accion":{"operacion":"anular_por_comunicacion_baja","endpoint":"POST /api/v1/anulaciones","doc_afectado":{"tipo":"07","serie":"FC01","correlativo":3}}}` (ND: `No se puede editar una nota de débito aceptada por SUNAT. Si la ND es errónea, anúlela por comunicación de baja.`, tipo "08").
- Campos simples: `tipo_moneda, observacion, doc_afectado_tipo, doc_afectado_serie, doc_afectado_correlativo, cod_motivo, des_motivo`.
- Leyenda con `mto_imp_venta` (sin percepción). Items insert incluye `descuentos` (json_encode) además (pero no `cod_producto_sunat`).
- Mensaje: `Nota de crédito actualizada y reenviada a SUNAT.` / `Nota de débito actualizada y reenviada a SUNAT.`; Resource con `items`.

### 10.6 destroy — solo Boletas (BoletaController.php:194-211)
- 404 si no existe.
- Permitido solo si `sunat_status ∈ {pendiente, rechazado}` **y** `hash_cpe` vacío; si no → 422 `{"estado":"error","mensaje":"No se puede eliminar una boleta ya aceptada por SUNAT. Debe anularla vía resumen RC."}` (mismo mensaje aunque el estado sea `enviado`).
- Borra items (hard delete) y la boleta (**soft delete**, `deleted_at`). Pagos no se borran.
- 200 `{"estado":"exito","mensaje":"Boleta eliminada localmente.","datos":null}`.

### 10.7 enviar / reenviar (iguales; `resend()` llama `enviar()`) (InvoiceController.php:268-295)
- 404 si no existe.
- `aceptado` → 422 `{"estado":"error","mensaje":"Esta factura ya fue aceptada por SUNAT."}` (Boleta: `Esta boleta ya fue aceptada por SUNAT.`; NC: `Esta nota de crédito ya fue aceptada por SUNAT.`; ND: `Esta nota de débito ya fue aceptada por SUNAT.`).
- Cualquier otro estado: `update(sunat_status='pendiente', sunat_code=null, sunat_description=null)` (no limpia `sunat_notes`), `dispatch(SendDocumentToSunat)`, `update(sunat_status='enviado')`.
- 200 `{"estado":"exito","mensaje":"Factura enviada a SUNAT.","datos":{Resource de fresh() SIN items ni pagos}}` (mensajes: `Boleta enviada a SUNAT.`, `Nota de crédito enviada a SUNAT.`, `Nota de débito enviada a SUNAT.`).
- No hay request body. No se usa resumen diario aunque la boleta tenga `solo_registro`.

### 10.8 xml (InvoiceController.php:297-313)
- 404 JSON (handler) si el documento no existe.
- Contenido: `DocumentStorageService::getXmlContent` (app/Services/Storage/DocumentStorageService.php:65-72): si `xml_path` y existe en el disco `Comprobantes` → contenido; si no → `$doc->xml_content ?? null` (atributo inexistente → null).
- Sin contenido → 404 `{"estado":"error","mensaje":"XML no disponible"}` (sin punto final).
- 200: body XML crudo; `Content-Type: application/xml` (sin charset); `Content-Disposition: attachment; filename="{numero_completo}.xml"` (p. ej. `F001-000008.xml`).
- Ruta de almacenamiento: `{ruc}/{cod_local}/{Y-m-d de fecha_emision}/{facturas|boletas|notas_credito|notas_debito}/xml/{numero_completo}.xml` (DocumentStorageService.php:26-37, 122-151).

### 10.9 cdr
- Igual; sin contenido → 404 `{"estado":"error","mensaje":"CDR no disponible"}`.
- 200: ZIP; `Content-Type: application/zip`; `Content-Disposition: attachment; filename="R-{numero_completo}.zip"`.

### 10.10 pdf (InvoiceController.php:333-358)
- Formato: **Factura** `input('formato', input('format', config('pdf.default_format','a4')))` (acepta `formato` o `format`); **Boleta/NC/ND** solo `input('format', 'a4')` (BoletaController.php:282, CreditNoteController.php:268).
- Valores (enum `PdfFormatConfig`, app/Services/Pdf/PdfFormatConfig.php:5-10): `a4`, `a5`, `ticket-80`, `ticket-58` (case-sensitive). Inválido → 422 `{"estado":"error","mensaje":"Formato inválido. Opciones: a4, a5, ticket-80, ticket-58"}`.
- Papel (dompdf): A4/A5 portrait; ticket-80 = `[0,0,226.77,841.89]` pt (80 mm × 297 mm), ticket-58 = `[0,0,164.41,841.89]`; márgenes 15/10/5/3; vista `pdf.formats.{a4|a5|ticket_80|ticket_58}`.
- Caché (app/Http/Traits/CachesPdf.php:9-45): si formato == default (`a4`) y hay `pdf_path` → PDF almacenado (generado por el job tras aceptación). Si no, caché en disco `storage/app/pdf-cache/{tenant_id}/{tabla}_{id}_{formato}.pdf` con TTL 3600 s. Si no hay → genera al vuelo (`PdfGeneratorService::generate`) y guarda en caché. El PDF se puede generar aunque el documento esté pendiente/rechazado.
- 200: `Content-Type: application/pdf`; `Content-Disposition: inline; filename="{numero_completo}.pdf"`; `Cache-Control: private, max-age=300`.
- Error de generación → excepción → 500 global.

---

## 11. Pagos (app/Http/Controllers/Api/V1/PaymentController.php)

Tipo de documento: `$request->segment(3)` (`/api/v1/{facturas|boletas}/...`) mapeado a `Invoice`/`Boleta` (`notas-venta` fuera de alcance) (:17-21, :91-95). (El `defaults('docType',...)` de la ruta no se usa.) Documento: `Model::where('tenant_id', …)->with('payments')->findOrFail((int)$id)` → 404 `Recurso no encontrado.` No usa el trait ApiResponse: **respuestas sin `estado`**.

### 11.1 POST /{facturas|boletas}/{id}/pagos (:23-59)
- Acepta pago único `{"metodo","monto","referencia","monto_recibido","notas"}`: si existe `metodo` y no `pagos` → se convierte en `pagos:[{...}]` (:26-36).
- Reglas (:38-45) — `$request->validate` → ValidationException → handler global 422 `{"estado":"error","mensaje":"Error de validación","errores":{...}}`. **La validación ocurre antes** de buscar el documento (422 incluso si el id no existe):
  - `pagos` `required|array|min:1`
  - `pagos.*.metodo` `required|string|in:efectivo,yape,plin,transferencia,tarjeta,cheque,otro` (app/Models/Payment.php:11-19)
  - `pagos.*.monto` `required|numeric|min:0.01`
  - `pagos.*.referencia` `nullable|string|max:100`
  - `pagos.*.monto_recibido` `nullable|numeric|min:0`
  - `pagos.*.notas` `nullable|string|max:255`
  (Errores de pago único se reportan como `pagos.0.metodo`, etc.)
- `RegisterPaymentAction::execute` (app/Actions/Payments/RegisterPaymentAction.php:11-27): en transacción crea cada `Payment` (morph `payable_type` = clase del modelo, `payable_id`, `tenant_id`, `metodo`, `monto`, `referencia`, `monto_recibido`, `notas`) y `recalculate`.
- `recalculate` (:29-45): `monto_pagado = SUM(payments.monto)`; `total=(float)mto_imp_venta`; `status = 'pendiente'`; si `0 < pagado < total` → `parcial`; elif `pagado >= total` → `pagado` (también cuando pagado=0 y total=0). Sin tope: se permite sobrepago. No valida estado SUNAT.
- 200 (no 201):
```json
{"mensaje":"Pagos registrados correctamente","estado_pago":"parcial","monto_pagado":100,"pagos":[{...formatPayment...}]}
```
  (`pagos` = todos los pagos del documento, no solo los nuevos.)

### 11.2 GET /{facturas|boletas}/{id}/pagos (:61-71)
200:
```json
{"datos":[{...}],"estado_pago":"pendiente","monto_pagado":0,"total_documento":236}
```

### 11.3 DELETE /{facturas|boletas}/{id}/pagos/{paymentId} (:73-89)
- Busca el pago dentro de `document->payments()` con `id=(int)paymentId` → `firstOrFail` (404 `Recurso no encontrado.`). Hard delete. `recalculate`.
- 200 `{"mensaje":"Pago eliminado correctamente","estado_pago":"...","monto_pagado":0}`.

### 11.4 formatPayment (:112-126) (idéntico al de los Resources)
```
{"id":int,"metodo":"efectivo","monto":float,"referencia":str|null,
 "monto_recibido": (monto_recibido truthy ? float : null),
 "vuelto": (metodo=='efectivo' && monto_recibido truthy ? round(monto_recibido - monto, 2) : null),
 "notas":str|null,"creado_en":"ISO8601 UTC"}
```
Ojo: `monto_recibido` tiene cast `decimal:2` → string `"0.00"` que en PHP es truthy → un `monto_recibido: 0` se devuelve `0` y `vuelto` = `-monto` (negativo).

---

## 12. Alias `/api/sunat/*` (routes/api.php:391-409)

Middleware **solo** `['resolve.tenant', 'throttle:api']` — **sin** `idempotency`, `log.api`, `usage.headers`, `license`, `check.limit:sunat`.

| Método | URL | Destino |
|---|---|---|
| GET | /api/sunat/configuracion | TenantController@show |
| POST | /api/sunat/configuracion | TenantController@update |
| GET | /api/sunat/facturas | InvoiceController@index |
| POST | /api/sunat/facturas | InvoiceController@store |
| GET | /api/sunat/facturas/{id}/pdf | InvoiceController@pdf |
| GET | /api/sunat/facturas/{id}/xml | InvoiceController@xml |
| POST | /api/sunat/facturas/{id}/anular | VoidedController@store (ignora `{id}`; exige body de comunicación de baja: `fecha_generacion` required|date, `fecha_comunicacion` sometimes|date, `detalles` required|array|min:1 con `tipo_documento` in:01,03,07,08,20,40, `serie` size:4, `correlativo` string, `motivo` max:255; boletas `03` → 422 `boletas_no_soportadas_en_ra`) |
| GET/POST | /api/sunat/boletas | BoletaController@index/store |
| GET | /api/sunat/boletas/{id}/pdf · /xml | BoletaController@pdf/xml |
| POST | /api/sunat/boletas/{id}/anular | VoidedController@store (para boleta siempre devolverá 422 si el detalle es tipo 03) |

Comportamiento idéntico al v1 (mismas respuestas). Las URLs de `archivos` en los Resources siguen apuntando a `/api/v1/...`. "facturas + boletas unificados" (comentario) es falso: cada alias lista solo su tipo.

---

## 13. Diferencias por tipo y reglas de series

| Aspecto | Factura 01 | Boleta 03 | NC 07 | ND 08 |
|---|---|---|---|---|
| Regex serie (request) | `^F[A-Z0-9]{3}$` | `^B[A-Z0-9]{3}$` | ninguna (size:4) | ninguna (size:4) |
| Serie registrada y activa (tipo) | 01 | 03 | 07 | 08 |
| Prefijos permitidos al crear series (Serie::PREFIJOS, app/Models/Serie.php:237-246; regex general SerieController:59 `^[A-Z][A-Z0-9]{3}$`) | F | B | F o B (FC01, BC01…) | F o B (FD01, BD01…) |
| Cliente | RUC (6) 11 díg., prefijo 10/15/17/20; exportación (02xx) prohíbe 6 | cualquiera Cat.06; >700 exige doc ≠ '0'; DNI 8, RUC 11 | Cat.06, sin reglas extra | idem |
| NRUS (`tax_regime='rus'`) | prohibido | permitido; tipo_operacion default 0113 | solo contra 03 | solo contra 03 |
| `solo_registro` (resumen diario) | no | sí | no | no |
| DELETE | no | sí (pendiente/rechazado sin hash) | no | no |
| Pagos | sí | sí | no | no |
| update bloqueado si aceptado → sugerencia | NC | NC | comunicación de baja | comunicación de baja |
| Resource items.total | con IGV | con IGV | valor venta sin IGV | idem |
| Resource extras | sí | no | no | no |
| descuentos_globales en Resource | reformateado | crudo | no | no |
| pdf parámetro | `formato` o `format` | `format` | `format` | `format` |
| Monto ≤ doc afectado | — | — | sí (salvo motivo 10) | — |

No hay validación de coherencia entre prefijo de la serie de la nota y el tipo de doc afectado (FC vs factura/BC vs boleta) — SUNAT lo valida.

---

## 14. Observaciones / posibles bugs (replicar o decidir conscientemente)

1. **`leyendas` (Cat. 52) se valida pero nunca se persiste** (no está en `Invoice::create` ni en el fillable) → el builder (InvoiceBuilder.php:193, NoteBuilder.php:117) nunca las recibe. Igual `sum_otros_descuentos`/`mto_igv_gratuitas` calculados no se guardan.
2. **`validated()` descarta campos**: `items.*.descuento`, `percepcion.mto_total` (la lógica de leyenda con percepción es código muerto en store), `descuentos_globales.*.factor`, `sucursal_id` (documentado en `documentacion/emision/facturas.md` pero ignorado), `mto_isc/mto_icbper/mto_ivap/mto_base_ivap` overrides. En NC se descarta `items.*.porcentaje_isc` (no tiene regla) → el ISC por porcentaje no se calcula en NC.
3. `descuentos_globales.*.porcentaje` tiene `max:1` pero el cálculo lo divide entre 100 (0.1 → 0.1 %). Además solo se usa si `monto` está vacío, pero `monto` es `required_with` (solo `monto: 0` activa el porcentaje).
4. NC valida `descuentos_globales` y los usa en el total pero **no los guarda** → el XML (desde BD) no incluye el descuento global aunque `mto_imp_venta` sí lo refleje.
5. `tip_sis_isc` y `factor_icbper` no se guardan en items → el job envía siempre `tip_sis_isc='01'`.
6. `update` (PUT) **no valida nada**, recalcula sin tenant/fecha (IGV 18 % fijo aunque sea RUS u `igv_rate_override`), pierde `cod_producto_sunat` y (factura/boleta) `descuentos` de items, y permite editar documentos `anulado`/`anulacion_en_proceso`/`enviado`. Deja `sunat_status='pendiente'` (no `enviado`) tras encolar.
7. `enviar`/`reenviar` aceptan documentos `anulado` / `anulacion_en_proceso`: los pasan a `enviado` y el job (que solo omite `aceptado|anulado` al ejecutarse) los reenvía a SUNAT.
8. Carrera en `enviar`: se encola el job y después se hace `update(enviado)` fuera de transacción; si un worker termina antes, el estado final queda `enviado` pisando `aceptado/rechazado`. En store el dispatch ocurre dentro de la transacción con `after_commit=false` (con cola redis el worker podría no encontrar el documento → reintento).
9. `masivo`: sin validación de FormRequest (catálogos, regex, RUC, NRUS…); `total` devuelto como string `"236.00"`; HTTP 422 con `"estado":"exito"` cuando no se crea ninguna; `check.limit` se evalúa una vez para hasta 100 documentos; ignora `enviar_automatico`.
10. Validación de correlativo manual usa la consulta con SoftDeletes (excluye eliminados), pero el índice único `(tenant_id, serie, correlativo)` incluye eliminados → reutilizar el correlativo de una boleta borrada pasa la validación y falla con 500 `Error al crear boleta: SQLSTATE[23000]...`.
11. Store captura `\Throwable` y responde **500** con el mensaje interno (fuga de detalles SQL). Idempotency no guarda 5xx (permite reintento).
12. Ids no numéricos: acciones con `int $id` → PHP `TypeError` → 500 `Error interno del servidor.` (p. ej. `GET /facturas/abc`, `GET /facturas/masivo`). `InvoiceController@pdf` usa `(int)` → 404. Pagos usan `(int)` → 404.
13. Índice NC/ND usa `has()` en vez de `filled()`: `?sunat_status=` (vacío → null por ConvertEmptyStringsToNull) llama `scopeStatus(null)` con tipo `string` → TypeError 500; `por_pagina` sin tope. En facturas/boletas `por_pagina` negativo → probable error SQL 500.
14. Boleta: regla > S/700 suma `cantidad*precio` sin considerar moneda (USD/EUR) ni descuentos; `cliente.tipo_doc` es required, por lo que el default '0' no aplica salvo envío explícito.
15. `generateLeyenda`: EUR se rotula `DOLARES AMERICANOS`; sin apócope (`UNO`, `VEINTIUNO MIL`).
16. Doble conteo de uso posible: además de `PlanService::incrementUsage` en cada Action, Laravel 12 tiene *event discovery* activo por defecto (`Application::configure()->withEvents()`), por lo que `app/Listeners/IncrementDocumentUsage` (escucha `DocumentCreated`) probablemente también se registra pese al comentario "DESCONECTADO" (AppServiceProvider.php:120-123). Verificar en runtime (`php artisan event:list`).
17. Payment: en store de factura/boleta `pagos.*.metodo` acepta cualquier string; en `/pagos` está restringido a 7 métodos. `vuelto` negativo con `monto_recibido: 0` (string `"0.00"` truthy). Pagos permitidos sobre documentos rechazados/anulados y sin tope de monto.
18. BoletaResource no usa el formateo de `descuentos_globales` ni `extras` ni `descuentos` por ítem (inconsistente con InvoiceResource). NC/ND `items.total` = valor venta sin IGV.
19. `cliente.email/telefono` en Resources salen del cliente maestro actual (puede diferir del momento de emisión); `ClientResolverService` sobrescribe razón social/dirección del maestro en cada emisión.
20. `destroy` de boleta: mensaje "ya aceptada" incluso si el estado es `enviado`; soft delete; pagos huérfanos.
21. `/api/sunat/*` evita `license` y `check.limit:sunat` (emisión sin límite de plan ni licencia) y la idempotencia; `/anular` ignora `{id}`.
22. Idempotency se ejecuta antes que throttle/license/check.limit → respuestas 403/429 quedan "cacheadas" bajo la key; no se ve expiración de keys.
23. `fecha_emision` se guarda como wall-clock Lima en columna datetime (migración 2026_03_16_200000), pero `created_at`/`sent_at` se devuelven en UTC (`+00:00`) por `timezone='UTC'` fijo en config/app.php:68.
24. Las mensajes de error de validación usan nombres de atributo "crudos" (`cliente.num doc`, `items.0.unidad`, `elementos`) — replicar el algoritmo de §2.3 si se busca igualdad exacta.
25. ND motivo 13: la regla ERR-3507 suma los IGV **enviados** por el cliente, no los calculados (un ítem gravado '10' sin `igv` explícito pasa la validación y se calcula con IGV).
