# ANÁLISIS DE API-PRO — FASE 0 (base común para las 7 tecnologías)

> Proyecto: **API SUNAT multilenguaje** · Curso de Facturación Electrónica SUNAT
> Referencia analizada: `C:\laragon\www\API-PRO` (Laravel 12 + Greenter 5.2), rama **`main`** (HEAD `62952c3`), **solo lectura**.
> Fecha del análisis: 2026-10-24.
> Destino de este documento: `spring-boot/docs/` (prompt 01). Cada una de las otras 6 APIs copiará este documento y sus anexos a **su propia** carpeta `docs/` al empezar, sin enlazar carpetas ajenas.
> Regla transversal del usuario: **todo el proyecto se desarrolla, ejecuta y prueba con Docker** (desde la Fase 1).

## Cómo leer este documento

Este archivo es la **síntesis ejecutiva y las decisiones**. El detalle byte a byte (reglas de validación exactas, mensajes, JSON literales, plantillas XML, fórmulas) está en los 8 anexos de `docs/analisis-fase0/`. Todas las afirmaciones llevan referencias `archivo:línea` de API-PRO en los anexos.

| Anexo | Contenido | Tamaño |
|---|---|---|
| [`01_endpoints_cpe.md`](analisis-fase0/01_endpoints_cpe.md) | Facturas, boletas, NC, ND y pagos: rutas, FormRequests (reglas en orden), mensajes, motor de cálculo, Resources, serialización JSON, 25 observaciones | 81 KB |
| [`02_endpoints_resto.md`](analisis-fase0/02_endpoints_resto.md) | Guías 09/31, resúmenes, bajas, retenciones, percepciones, reversiones, consultas, empresa, sucursales, clientes, series, catálogos, panel, exportación, reportes, SIRE, registro, suscripción, bridge, alias; 42 observaciones | 105 KB |
| [`03_auth_tenancy_config.md`](analisis-fase0/03_auth_tenancy_config.md) | Middlewares, autenticación, idempotencia, límites, licencia, RBAC, multiempresa, certificados, configuración, `.env`, almacenamiento, seguridad, despliegue; 32 riesgos | 65 KB |
| [`04_modelo_datos.md`](analisis-fase0/04_modelo_datos.md) | Esquema final tras las 91 migraciones, ficha por tabla, modelos, estados, MySQL→PostgreSQL, seeders; 19 observaciones | 62 KB |
| [`05_nucleo_sunat_app.md`](analisis-fase0/05_nucleo_sunat_app.md) | Mapeo JSON→Greenter por tipo, post-procesados XML, envío, CDR, estados, tickets, jobs, webhooks, almacenamiento, PDF/QR; 29 bugs | 77 KB |
| [`06_greenter_internos.md`](analisis-fase0/06_greenter_internos.md) | Especificación interna de Greenter (verificada ejecutando): plantillas Twig literales, formato numérico, firma, ZIP, SOAP, CDR, GRE REST, endpoints, riesgos de portabilidad | 163 KB |
| [`07_postman_tests_docs.md`](analisis-fase0/07_postman_tests_docs.md) | Colección Postman (295 requests, 105 bodies literales), cruce con rutas, tests Pest, documentación interna, XML/CDR de referencia para pruebas doradas | 310 KB |
| [`08_documentacion_sunat.md`](analisis-fase0/08_documentacion_sunat.md) | Documentación oficial SUNAT: servicios, firma, códigos de retorno, catálogos, reglas por documento y **liquidación de compra** fila por fila | 82 KB |

---

## 0. Resumen ejecutivo

1. **API-PRO es más que una API de facturación.** Contiene: API REST multiempresa (`/api/v1`, 224 rutas incluidas SIRE), panel web Inertia/React (`routes/web.php`), SaaS de suscripciones (planes, Culqi, uso), servidor de licencias externo, SIRE (25 endpoints contra la API SIRE de SUNAT) y un *bridge* para otro SaaS. **Hay que decidir el alcance** de los bloques no-SUNAT (ver §13, decisión D2).
2. **Hay una rama de seguridad no fusionada.** `fix/security-hardening` tiene 20+ commits (CORS seguro, global scope por tenant, validación de `masivo`/`PUT`, registro siempre en plan free, no filtrar `getMessage()` en 500, corrección de la recuperación de credenciales, etc.) que **no están en `main`**. Recomendación: replicar el contrato de `main` con el comportamiento endurecido de esa rama (D1).
3. **El contrato HTTP es estable pero irregular.** Envoltorio `{estado, mensaje, datos}` en casi todo, pero 5 formas de paginación, `por_pagina` vs `per_page`, claves en inglés en reversiones, respuestas sin `mensaje`, 202 con `estado: pendiente|error` en guías. La regla del prompt ("cambiar solo la URL base") obliga a replicar estas irregularidades.
4. **El envío a SUNAT siempre es asíncrono** (colas `sunat` y `default`), con estados `pendiente → enviado → aceptado|rechazado → anulacion_en_proceso → anulado`, reintentos (20 en `sunat`, 10 en el resto), circuit breaker y polling de tickets.
5. **Greenter se puede especificar completamente.** El anexo 06 documenta plantillas, firma (RSA-SHA1/SHA1, C14N, `Id="GreenterSign"`), ZIP, SOAP y GRE REST, y se verificó ejecutando PHP contra el `vendor/` sin red. Es la base para reimplementar el núcleo en Java, Python, C#, TypeScript y Rust.
6. **Hay bugs reales en API-PRO** que no deben copiarse a ciegas: consulta de CDR rota, bajas marcadas como anuladas antes del resultado, fechas de RA/RR en UTC, reportes que pierden el último día, recuperación de credenciales rota, etc. Lista consolidada en §12 para decidir (D3).
7. **Liquidación de compra (tipo 04):** las reglas de validación vigentes (24/04/2026) traen la estructura completa de `SelfBilledInvoice`. **Greenter no soporta el tipo 04** (verificado: no hay `SelfBilled` en `vendor/greenter`), así que **incluso la versión Laravel** necesitará un generador propio. Faltan en la carpeta oficial: XSD, RS 244-2019 / Anexo 9-A, la resolución de reversiones y las tasas de retención (§11).
8. **Documentación oficial incompleta.** La carpeta `04 UBL 2.1 y XSD` **no contiene ningún `.xsd`**, solo PDFs (guías de 2017, en parte contradichas por las reglas vigentes). `01 Resoluciones` y `05 Guías Portal SOL` están vacías. La validación XSD del prompt (§11 del prompt) requiere conseguir los XSD (D6).

---

## 1. Inventario de rutas

Prefijo global `/api`. Pila de middleware del grupo protegido `/api/v1` (en este orden): `resolve.tenant` → `idempotency` → `throttle:api` → `log.api` → `usage.headers`. Las rutas de **emisión** agregan `license` + `check.limit:sunat`.

Leyenda: **M** = se migra · **X** = se excluye (NV/COT) · **A** = se migra adaptando (quitar NV/COT) · **D** = decisión del usuario (§13).

### 1.1 Públicas y de infraestructura

| Método | URL | Middleware | Controlador@acción | Alc. |
|---|---|---|---|---|
| GET | `/api/health` | throttle:api | `HealthController` (invocable; BD/Redis/disco/SUNAT; 200/503) | M |
| POST | `/api/v1/registro` | throttle:api | `RegisterController@store` (multipart, certificado obligatorio) | D2 |
| GET | `/api/v1/planes` | throttle:api | `SubscriptionController@plans` | D2 |
| POST | `/api/v1/credenciales/recuperar` | throttle:api | `CredentialRecoveryController@solicitar` (correo con código) | M |
| POST | `/api/v1/credenciales/recuperar/verificar` | throttle:api | `CredentialRecoveryController@verificar` | M (corregir BUG-01) |
| GET | `/api/v1/sunat/estado` | grupo v1 | closure → circuit breaker `{estado, sunat:{disponible,circuit,fallas,entorno}}` | M |
| GET | `/api/debug/lookup` | ninguno (solo `APP_DEBUG`) | closure de diagnóstico | **No migrar** |
| POST | `/api/bridge/auth` · `/api/bridge/provision` · DELETE `/api/bridge/tenant` | ninguno (`X-Bridge-Key`) | `SaasAuthController` | D2 |

### 1.2 Comprobantes SUNAT (01, 03, 07, 08) y pagos — detalle en anexo 01

| Recurso | Rutas | Alc. |
|---|---|---|
| Facturas 01 | POST `facturas`ᴱ, POST `facturas/masivo`ᴱ, GET `facturas`, GET/PUT `facturas/{id}`, GET `facturas/{id}/xml|cdr|pdf`, POST `facturas/{id}/reenviar|enviar`, POST/GET `facturas/{id}/pagos`, DELETE `facturas/{id}/pagos/{paymentId}` | M |
| Boletas 03 | POST `boletas`ᴱ, GET `boletas`, GET/PUT/DELETE `boletas/{id}`, GET `boletas/{id}/xml|cdr|pdf`, POST `boletas/{id}/reenviar|enviar`, POST/GET/DELETE pagos (igual que facturas) | M |
| Notas de crédito 07 | POST `notas-credito`ᴱ, GET `notas-credito`, GET/PUT `notas-credito/{id}`, GET `…/{id}/xml|cdr|pdf`, POST `…/{id}/reenviar|enviar` | M |
| Notas de débito 08 | igual que NC con `notas-debito` | M |

ᴱ = con `license` + `check.limit:sunat`.

### 1.3 Otros documentos SUNAT — detalle en anexo 02

| Recurso | Rutas | Alc. |
|---|---|---|
| Guías 09/31 | POST `guias-remision`ᴱ (09 por defecto, 31 con `tipo_documento='31'`), POST `guias-remision-transportista`ᴱ (fuerza 31), GET `guias-remision`, GET/PUT `guias-remision/{id}`, GET `…/{id}/pdf|xml|cdr`, GET `…/{id}/estado` (consulta SUNAT síncrona), POST `…/{id}/enviar` | M |
| Resúmenes RC | GET/POST `resumenes`ᴱ, GET `resumenes/{id}/estado` (solo BD), GET `…/{id}/xml|cdr`, POST `…/{id}/enviar` | M |
| Bajas RA | POST `anulaciones`ᴱ, GET `anulaciones`, GET `anulaciones/{id}`, GET `…/{id}/estado` (síncrona), GET `…/{id}/xml|cdr`, POST `…/{id}/enviar` | M |
| Retenciones 20 | POST `retenciones`ᴱ, GET `retenciones`, GET `retenciones/{id}`, GET `…/{id}/pdf|xml|cdr`, POST `…/{id}/enviar` | M |
| Percepciones 40 | POST `percepciones`ᴱ, GET `percepciones`, GET `percepciones/{id}`, GET `…/{id}/xml|cdr`, POST `…/{id}/enviar` (sin PDF) | M |
| Reversiones RR | POST `reversiones`ᴱ (solo 20/40 hoy) | M (+ extender a 04) |
| Consultas | POST `consultar-cdr`, GET `consultar-cpe` (REST/OAuth2 consulta integrada), GET `buscar-documento` (RUC/DNI local + json.pe/migo) | M (corregir consultar-cdr) |

### 1.4 Configuración y catálogos

| Recurso | Rutas | Alc. |
|---|---|---|
| Empresa | GET/PUT/DELETE `empresa`, POST `empresa/logo`, POST `empresa/certificado`, GET `empresa/credenciales`, POST `empresa/credenciales/regenerar` | M |
| Sucursales | `apiResource('sucursales')` (index, store, show, update, destroy) | M |
| Clientes | `apiResource('clientes')` | M |
| Series | `apiResource('series')` + POST `series/init-defaults` | M |
| Catálogo producto SUNAT (UNSPSC, cat. 25) | GET `catalogos/producto-sunat`, `…/segmentos`, `…/familias`, `…/clases`, `…/productos`, `…/{codigo}` (8 dígitos) | M |
| Ubigeo | GET `catalogos/ubigeo/regiones|provincias|distritos` | M |

### 1.5 Panel, reportes, exportación

| Recurso | Rutas | Alc. |
|---|---|---|
| Panel | GET `panel` (A: quitar `notas_venta`), `panel/indicadores`, `estado-sunat`, `cobranzas`, `ventas-mensuales`, `por-sucursal`, `por-moneda`, `clientes`, `productos`, `documentos-recientes`, `alertas` | M / A |
| Exportación | GET `comprobantes/exportar-zip` | M |
| Reportes | GET `reportes/registro-ventas`, `ventas-consolidado`, `notas`, `cobranzas` (A), `por-cliente` (A), `por-sucursal` · `reportes/documentos-internos` | M / A / **X** |

### 1.6 SIRE, suscripciones, alias

| Recurso | Rutas | Alc. |
|---|---|---|
| SIRE | POST `sire/activar|desactivar`; con `sire.enabled`: GET `sire/periodos`; RCE: `rce/constancia`, `rce/{periodo}/propuesta|resumen`, POST `aceptar-propuesta|registrar-preliminar|reemplazar-propuesta|no-domiciliados|complementar-propuesta`, `ajustes-posteriores/{variant}/cargar|enviar|descargar|eliminar`, `comprobantes`, `comprobantes/{id}`, `reconciliar`, `reconciliar-async`, `reconciliaciones`, `rce/reconciliaciones/{id}`; tickets: `tickets`, `tickets/{numTicket}`, `…/refrescar`, `…/archivo` | D2 |
| Suscripción | GET/POST `suscripcion`, PUT `suscripcion/cambiar-plan|cancelar`, GET `suscripcion/pagos|uso` | D2 |
| Alias `/api/sunat/*` | GET/POST `configuracion`; GET/POST `facturas`, GET `facturas/{id}/pdf|xml`, POST `facturas/{id}/anular`; lo mismo para `boletas` (solo `resolve.tenant` + `throttle`) | M (con la pila completa; D3) |

### 1.7 Excluidas (Notas de Venta y Cotizaciones)

`POST/GET cotizaciones`, `GET/PUT cotizaciones/{id}`, `PUT cotizaciones/{id}/estado`, `GET cotizaciones/{id}/pdf`, `POST/GET notas-venta`, `GET/PUT notas-venta/{id}`, `GET notas-venta/{id}/pdf`, pagos de `notas-venta`, `reportes/documentos-internos`, middleware `check.limit:internal`. Las 16 requests de la colección Postman asociadas están marcadas `[EXCLUIR]` en el anexo 07.

### 1.8 Fuera de la API

- `routes/web.php` (346 líneas): panel Inertia/React (`/sunat/*` empresa, `/admin/*` plataforma, Horizon, Fortify). Incluye dos rutas públicas peligrosas: `/ver-errores` (vuelca el log) y `/limpiar-cache-ahora`. **No migrar** (D2).
- `routes/settings.php`: perfil, contraseña, 2FA del panel.
- `routes/console.php`: scheduler (§7.4).

---

## 2. Contrato de request/response

### 2.1 Envoltorio y errores (anexo 01 §2, anexo 03 §1.2)

- **Éxito:** `{"estado":"exito","mensaje":"OK","datos":…}` (+ `"meta"` si aplica). `created()` → **201** con mensaje `"Creado exitosamente"` u otro específico.
- **Error de negocio:** `{"estado":"error","mensaje":…}` (+ `errores`); variante accionable con `codigo_error` y `siguiente_accion` (422 por defecto).
- **Manejador global** (`bootstrap/app.php`):

| Caso | HTTP | Cuerpo |
|---|---|---|
| Validación | 422 | `{"estado":"error","mensaje":"Error de validación","errores":{"campo":["msg"]}}` |
| No encontrado | 404 | `"Recurso no encontrado."` |
| No autenticado / no autorizado | 401 / 403 | `"No autenticado."` / `"No autorizado."` |
| Método no permitido | 405 | `"Método HTTP no permitido para esta ruta."` |
| Throttle | 429 | `"Demasiadas solicitudes. Intenta de nuevo más tarde."` |
| Otro HttpException | su código | `getMessage()` o `"Error."` |
| Excepción | 500 | `"Error interno del servidor."` (+ `excepcion`, `detalle`, `archivo` si `APP_DEBUG`) |

- **Excepciones al envoltorio:** pagos (sin `estado`, alta con 200), catálogos (sin `mensaje`, `total/pagina/por_pagina` en raíz), `sunat/estado` (clave `sunat`), resúmenes/anulaciones (sin `mensaje`), guías (`202` con `estado: pendiente|error`), bridge (`{"error":…}`), SIRE (paginador crudo de Laravel), `buscar-documento` (`fuente` local vs `source` externo).

### 2.2 Serialización JSON byte a byte (anexo 01 §3)

- Floats sin `.0` (`100`, no `100.0`); representación más corta (`serialize_precision=-1`).
- `/` escapada como `\/` y no-ASCII como `\uXXXX` (sin `JSON_UNESCAPED_*`). `Content-Type: application/json` sin charset.
- Decimales de BD → `float` en los Resources (excepto planes y SIRE, que salen como **string**).
- `fecha_emision` = `Y-m-d H:i:s` en hora de Lima (guardada como *wall-clock*); `fecha_vencimiento` = `Y-m-d`; `creado_en`/`enviado_en` = ISO 8601 en **UTC** (`+00:00`), porque `config/app.php` fija `timezone=UTC`.
- Claves `when()`/`whenLoaded()` **desaparecen** cuando no aplican (no salen como `null`).
- `numero_completo` = `{serie}-{correlativo con 6 dígitos}` (p. ej. `F001-000008`).

### 2.3 Validación (anexo 01 §2.3–§6, anexo 02 por módulo)

- Mensajes Laravel en español (`lang/es/validation.php`). El nombre del atributo sigue el algoritmo de Laravel: `items` → `elementos`; rutas con comodín crudas (`items.0.unidad`); resto `snake` con espacios (`cliente.num doc`). Hay reglas propias `SunatCatalog(n)` y `SunatProductCode` con mensajes literales (anexo 01 §2.3).
- `validated()` **descarta en silencio** campos no declarados (p. ej. `sucursal_id`, `items.*.descuento`). Las implementaciones deben aceptar y descartar igual (no responder 422 por campos extra).
- Catálogos válidos del config (`config/sunat_catalogs.php`) copiados en anexo 01 §2.3. Para reproducir los mensajes `Códigos válidos: …` hay que copiar el orden exacto.
- Series: factura `^F[A-Z0-9]{3}$`, boleta `^B[A-Z0-9]{3}$`; NC/ND sin regex (serie activa del tipo con prefijo F/B según el documento afectado).

### 2.4 Paginación (anexo 02 §21.33)

| Forma | Dónde | Parámetro de tamaño |
|---|---|---|
| `datos: {datos:[…], paginacion:{…}}` | facturas, boletas, NC, ND, guías, clientes, retenciones, percepciones | `por_pagina` (retenciones/percepciones: `per_page`) |
| `datos:[…]` + `paginacion` en raíz, sin `mensaje` | resúmenes | `por_pagina` |
| `datos:[…]` + `meta` | anulaciones | `per_page` |
| Paginador crudo de Laravel | SIRE comprobantes/tickets | `per_page` |
| `total`, `pagina`, `por_pagina` en raíz | catálogo producto SUNAT | `per_page` |

Propuesta: una utilidad de paginación por **forma**, configurada por endpoint, para no dispersar la irregularidad.

### 2.5 Descargas

| Tipo | Content-Type | Disposition | Nombre | Si falta |
|---|---|---|---|---|
| XML | `application/xml` | `attachment` | `{numero_completo}.xml` (RC/RA/RR: `{identificador}.xml`) | 404 JSON `"XML no disponible"` |
| CDR | `application/zip` | `attachment` | `R-{numero_completo}.zip` | 404 JSON `"CDR no disponible"` |
| PDF | `application/pdf` | `inline` + `Cache-Control: private, max-age=300` | `{numero_completo}.pdf` | se genera al vuelo |

PDF: `format` = `a4` | `a5` | `ticket-80` | `ticket-58` (la factura acepta también `formato`).

### 2.6 Cabeceras de respuesta propias

`X-Usage-Documents: n/limite`, `X-Usage-Warning: approaching_limit`, `X-Usage-Remaining`, `X-Usage-AI`, `X-Plan`, `Idempotency-Replayed: true|false`, `X-License-Warning`. Seguridad: `X-Content-Type-Options`, `X-Frame-Options: SAMEORIGIN`, `Referrer-Policy`, `X-Permitted-Cross-Domain-Policies`, HSTS solo en HTTPS.

### 2.7 Ejemplo literal (factura creada, 201)

```json
{"estado":"exito","mensaje":"Factura creada y encolada para env\u00edo a SUNAT.","datos":{"id":15,"tipo_documento":"01","serie":"F001","correlativo":8,"numero_completo":"F001-000008","cod_local":"0000","fecha_emision":"2026-10-24 10:15:30","fecha_vencimiento":null,"tipo_operacion":"0101","tipo_moneda":"PEN","forma_pago":"Contado","cliente":{"tipo_doc":"6","num_doc":"20555666777","razon_social":"ACME SAC"},"totales":{"gravadas":200,"igv":36,"total_impuestos":36,"valor_venta":200,"sub_total":236,"total":236},"items":[{"codigo":"P001","descripcion":"PRODUCTO","unidad":"NIU","cantidad":2,"precio_unitario":118,"valor_unitario":100,"igv":36,"total":236}],"sunat":{"estado":"enviado","codigo":null,"descripcion":null,"notas":null,"hash_cpe":null},"archivos":{"xml":null,"cdr":null,"pdf":null},"leyenda":"DOSCIENTOS TREINTA Y SEIS CON 00\/100 SOLES","observacion":null,"estado_pago":"pendiente","monto_pagado":0,"pagos":[],"enviado_en":null,"creado_en":"2026-10-24T15:15:30+00:00"}}
```

Estructuras completas de `InvoiceResource`, `BoletaResource`, `CreditNoteResource`, `DispatchGuideResource`, `TenantResource`, etc.: anexos 01 §9 y 02.

### 2.8 Motor de cálculo (anexo 01 §8)

- **Tasa IGV** (`TaxRateService`): `igv_rate_override` del tenant → 0 si régimen `rus` → **18**. Afectación `17` (IVAP) → 4. `porcentaje_igv` por ítem tiene prioridad.
- **Por ítem:** valor unitario = precio / (1 + IGV) a 10 decimales (con ISC: / ((1+ISC)(1+IGV))); valor de venta = vu × q − descuentos de línea; **IGV por diferencia** = round(P × q, 2) − descuento con IGV − vv; gratuitas con valor referencial; ICBPER = factor × q si se envía; ISC = vv × % si se envía.
- **Totales:** gravadas, exoneradas, inafectas, exportación, gratuitas, IVAP; descuento global `02` recalcula IGV proporcional; `03` no afecta base; anticipos restan del total.
- **Leyenda 1000:** monto en letras `"{LETRAS} CON {dd}/100 SOLES|DOLARES AMERICANOS"` (algoritmo literal con sus imperfecciones: `VEINTIUNO MIL`, EUR → DÓLARES).
- **Redondeo:** PHP `round()` = *half away from zero* con pre-redondeo (`round(1.005, 2)` = 1.01). En los demás lenguajes hay que usar decimales exactos (`BigDecimal`, `Decimal`, `decimal`, `rust_decimal`) partiendo de la representación decimal corta del número. Es un requisito de las pruebas doradas.

---

## 3. Autenticación y autorización (anexo 03 §1–§2)

### 3.1 API (contrato a replicar)

- Solo cabeceras **`X-Api-Key`** y **`X-Api-Secret`** (no query string).
- `api_key`: 64 caracteres aleatorios, en claro, UNIQUE. `api_secret`: se guarda **cifrado reversible** (no hash) porque también es la clave HMAC de los webhooks; se compara en tiempo constante.
- Respuestas: 401 `"Las cabeceras X-Api-Key y X-Api-Secret son requeridas."` · 401 `"Credenciales de API inválidas."` · 401 secret ilegible (mensaje largo, anexo 03 §1.4) · 403 `"Empresa desactivada. Contacte al administrador."` (se valida después del secret).
- Tenant cacheado 600 s (rama de seguridad: 60 s). No hay JWT ni login de usuario en la API: **la credencial de empresa da acceso total a su tenant**.
- Implicación para los prompts: el prompt pide "Spring Security + JWT (respetando el mismo contrato de login/token que API-PRO)". **API-PRO no usa JWT ni login en la API**; el contrato es API Key + Secret por cabecera. Se implementará un filtro de autenticación por cabeceras equivalente (D4).

### 3.2 Otros controles de la pila

| Middleware | Comportamiento clave |
|---|---|
| `idempotency` | Cabecera `Idempotency-Key` (POST/PUT/PATCH/DELETE); huella `sha256(METHOD|path|body)`; 422 si el cuerpo difiere, 409 si está en proceso, *replay* con `Idempotency-Replayed: true`; respuestas 5xx no se guardan; **sin TTL** (proponer TTL 24 h configurable). |
| `throttle:api` | Por minuto según `tenants.plan`: free 30 · pro 120 · business 300; `X-Internal-Token` exento. |
| `log.api` | Guarda request/response en `api_logs` (enmascara `contrasena_certificado`, `sol_pass`, `password`, `api_secret`; la respuesta **no** se enmascara). |
| `license` | Beta exento; producción valida contra servidor de licencias externo; 403 con `codigo_error`. (D2) |
| `check.limit:sunat` | Beta/ilimitado exentos; 403 `suscripcion_vencida`, 429 `limite_alcanzado` con `mejora_plan`. (D2) |
| `sire.enabled` | 403 `sire_no_activado`. |

### 3.3 Panel web (fuera de la API)

Fortify (sesión, 2FA), roles globales `super_admin|admin|soporte|lectura|cliente`, membresías por empresa (`tenant_user`: `owner|cajero` con *abilities* `{tipo}.{accion}`). **No aplica a la API**; solo relevante si se migra el panel (D2).

---

## 4. Modelo de datos (anexo 04)

### 4.1 Tablas de dominio (alcance)

| Grupo | Tablas |
|---|---|
| Multiempresa | `tenants`, `sucursales`, `series`, `clients`, `users`, `tenant_user` |
| Comprobantes | `invoices`, `boletas` (estructura idéntica), `credit_notes`, `debit_notes`, `invoice_items`, `boleta_items`, `credit_note_items`, `debit_note_items` |
| Otros CPE | `dispatch_guides` (ítems en JSON), `summaries`, `voided_documents` (RA y RR), `retentions` + `retention_items`, `perceptions` + `perception_items` |
| Pagos | `payments` (polimórfica; `payable_type` guarda clases PHP → mapear a valores neutros) |
| SaaS (D2) | `plans`, `subscriptions`, `subscription_payments`, `settings` |
| Operación | `api_logs` (particionada por mes en MySQL), `idempotency_keys`, `audit_logs` |
| Catálogos | `unspsc_codes` (~52 840), `unspsc_taxonomy` (4 294), `ubigeo_regiones/provincias/distritos` (25/196/~1 873) |
| SIRE (D2) | 5 tablas `sire_*` |
| Framework | `jobs`, `failed_jobs`, `sessions` (solo panel), `cache`, `cache_locks`, `password_reset_tokens` (solo panel) |

**Excluidas:** `internal_documents`, `internal_document_items` (NV/COT). Tablas legacy `documents`/`document_items` (eliminadas por migración; no replicar; queda `dispatch_guides.document_id` huérfana).

### 4.2 Estados

- `sunat_status` en 01/03/07/08: `pendiente, enviado, aceptado, rechazado, anulado, anulacion_en_proceso`.
- Guías, resúmenes, bajas: `pendiente, enviado, aceptado, rechazado`. Retenciones/percepciones: `string(30)` (admiten también `anulado`, `anulacion_en_proceso`).
- `payment_status`: `pendiente, parcial, pagado`. `tenants.environment`: `beta, production` (no `produccion`).

### 4.3 MySQL → PostgreSQL (6 APIs)

- Enums → `varchar` + `CHECK` (o enum de PG); JSON → `jsonb`; `unsigned` desaparece; DATETIME → `timestamp` (fijar zona `America/Lima` explícitamente).
- **Índices en FK**: PostgreSQL no los crea solo; hay que declararlos.
- Partición de `api_logs`: `PARTITION BY RANGE (created_at)` mensual (DDL en anexo 04 §4) o tabla simple con purga (proponer tabla simple + purga de 90 días para simplificar; D5).
- `CHAR(n)` → `varchar(n)` (evitar relleno con espacios).
- Usar `whereJsonContains` equivalente correcto (`@>` en jsonb) en los anti-duplicados de RA/RR/RC.

### 4.4 Correcciones de esquema recomendadas

UNIQUE en `voided_documents (tenant_id, identifier)`, `retentions/perceptions (tenant_id, serie, correlativo)`; UNIQUE incondicional en `summaries (tenant_id, identifier)`; `sire_client_secret` como `text`; leer `mto_valor_unitario` con 10 decimales (hoy el cast lo redondea a 4).

### 4.5 Seeders

Planes `free/pro/business`, UNSPSC (CSV 2,6 MB) + taxonomía, ubigeo (`public/data_ubi.txt`). **No hay usuario inicial ni empresa demo**: el prompt los pide, así que se agregarán (empresa demo beta `20000000001` / `MODDATOS`, series por defecto F001, B001, FC01, FD01, BC01, BD01, T001, V001, R001, P001 y L001).

---

## 5. Multiempresa (anexo 03 §3)

- Un **tenant** = una empresa emisora (RUC), con su entorno `beta|production`, credenciales SOL (usuario secundario; el WS-Security usa `RUC+usuario`), credenciales GRE OAuth2 (`client_id`/`client_secret`, con *fallback* a las de SIRE y a las de `.env`), certificado, logo, régimen tributario, tasa IGV *override*, webhook y token de consulta RUC/DNI.
- Campos cifrados con `APP_KEY` de Laravel: `sol_user`, `sol_pass`, `client_secret`, `certificate_password`, `api_secret`, `consulta_token`, `sire_client_secret`. **Como cada API tiene su propia BD**, no hace falta compatibilidad con el cifrado de Laravel: cada tecnología usará AES-256-GCM con clave propia de `.env` (D5). Solo si en el futuro se migran datos reales desde API-PRO habrá que descifrar con la `APP_KEY` original.
- **Certificado:** acepta `pfx, p12, pem, cer, crt` (≤100 KB); PFX se convierte a PEM (con reintento `openssl -legacy` para RC2-40/3DES, típico de certificados SUNAT). Hoy el PEM con la clave privada queda **en claro** en disco: en las nuevas APIs se guardará **cifrado en reposo**.
- **Aislamiento:** en `main` es manual (`forTenant($id)`) en cada consulta; un recurso de otro tenant responde 404. En las nuevas APIs: filtro por tenant **obligatorio en la capa de repositorio** (equivalente al *global scope* de la rama de seguridad).
- **Sucursales:** `cod_local` (4) UNIQUE por tenant; las series pueden asociarse a sucursal; el documento hereda `cod_local` (va al XML como `AddressTypeCode`).
- **Series y correlativos:** `series.correlativo` guarda el último número usado; asignación con `SELECT … FOR UPDATE` dentro de la transacción; el correlativo manual solo adelanta el puntero.

---

## 6. Núcleo SUNAT (anexos 05 y 06)

### 6.1 Documentos soportados

| Tipo | Raíz XML (UBL) | Versión UBL / Customization | Servicio | Respuesta |
|---|---|---|---|---|
| 01 Factura · 03 Boleta | `Invoice` | 2.1 / 2.0 | billService FE `sendBill` | CDR síncrono |
| 07 NC · 08 ND | `CreditNote` / `DebitNote` | 2.1 / 2.0 | FE `sendBill` | CDR síncrono |
| RC Resumen diario | `SummaryDocuments` | 2.0 / 1.1 | FE `sendSummary` + `getStatus` | ticket |
| RA Comunicación de baja | `VoidedDocuments` | 2.0 / 1.0 | FE `sendSummary` + `getStatus` | ticket |
| 20 Retención · 40 Percepción | `Retention` / `Perception` | 2.0 / 1.0 | billService *otroscpe* `sendBill` | CDR síncrono |
| RR Reversión | `VoidedDocuments` (prefijo RR) | 2.0 / 1.0 | *otroscpe* `sendSummary` + `getStatus` | ticket |
| 09 GRR · 31 GRT | `DespatchAdvice` (GRE 2022) | 2.1 / 2.0 | **API REST** GRE (OAuth2) | ticket |
| **04 Liquidación (nuevo)** | `SelfBilledInvoice` | 2.1 / 2.0 | FE `sendBill` (a confirmar en beta) | CDR síncrono |

### 6.2 Endpoints (desde `.env`, valores por defecto de `config/facturacion.php`)

| Clave | Beta | Producción |
|---|---|---|
| FE | `https://e-beta.sunat.gob.pe/ol-ti-itcpfegem-beta/billService` | `https://e-factura.sunat.gob.pe/ol-ti-itcpfegem/billService` |
| Retención/percepción/reversión | `https://e-beta.sunat.gob.pe/ol-ti-itemision-otroscpe-gem-beta/billService` | `https://e-factura.sunat.gob.pe/ol-ti-itemision-otroscpe-gem/billService` |
| Consulta CDR | `https://e-beta.sunat.gob.pe/ol-it-wsconscpegem-beta/billConsultService` | `https://e-factura.sunat.gob.pe/ol-it-wsconscpegem/billConsultService` |
| GRE token | `https://gre-test.nubefact.com/v1` (proxy de pruebas) | `https://api-seguridad.sunat.gob.pe/v1` |
| GRE envío | `https://gre-test.nubefact.com/v1` | `https://api-cpe.sunat.gob.pe/v1` |

### 6.3 Generación XML (anexo 06 §2 y anexos A–C con las plantillas literales)

- Una sola línea, `<?xml version="1.0" encoding="utf-8"?>`, plantillas 2.1 mínimas (sin `listAgencyName` salvo GRE).
- Números: montos con `number_format(x, 2, '.', '')`; `n_format_limit` solo formatea si hay más decimales que el límite; cantidades y porcentajes sin filtro salen con el formato nativo de PHP (`18.0` → `18`). Hay que emularlo exactamente.
- Condiciones `{% if x %}` (omiten el bloque con 0) vs `is not null` (lo incluyen): varía por plantilla, tabulado en anexo 06.
- Fechas en `America/Lima`, incluidos los identificadores RC/RA/RR.
- Greenter **no escapa** `&` ni `<` fuera de CDATA: en las nuevas APIs **sí se escapará** (diferencia documentada en `DIFERENCIAS.md`).
- **Post-procesados de la app** antes de firmar (anexo 05 §1): GRT 31 (quita `HandlingCode`/`TransportModeCode`, añade `CarrierParty`, `TransportMeans`, `DespatchParty`, `Consignment`, `OriginatorCustomerParty`), `LoadingTransportEvent` en GRR, contrato de colaboración, eliminación de `<cbc:CompanyID/>` vacíos y del `BillingReference` vacío en ND motivo 13. Los XmlBuilder nuevos los integran directamente.
- Leyendas automáticas: 1000 (monto en letras), 2006 (detracción), 2000 (percepción), 2007 (IVAP). La 1002 (gratuitas) **no** se agrega.

### 6.4 Firma (anexo 06 §3, verificada)

- RSA-SHA1, digest SHA1, C14N inclusiva 1.0, transform *enveloped*, `Reference URI=""`, `KeyInfo/X509Data/X509Certificate`.
- `<ds:Signature Id="GreenterSign">` dentro del primer `ext:UBLExtensions/ext:UBLExtension/ext:ExtensionContent` vacío; `cac:Signature/cbc:URI = #GREENTER-SIGN` (no coinciden; copiar literal).
- Hash del PDF/QR y `hash_cpe` = `DigestValue` en base64.
- Riesgos: Java 17+/.NET 6+ bloquean SHA1 por política (hay que habilitarlo explícitamente); la C14N del `SignedInfo` arrastra los `xmlns` de la raíz. Se mitiga con verificación de firma en las pruebas.

### 6.5 ZIP, SOAP y CDR (anexo 06 §4)

- ZIP con una entrada `{RUC}-{TIPO}-{SERIE}-{NUMERO}.xml` (deflate) dentro de `{mismo nombre}.zip`.
- SOAP: operaciones `sendBill`, `sendSummary`, `getStatus`, `getStatusCdr` en `http://service.sunat.gob.pe`; WS-Security `UsernameToken` `PasswordText` con usuario `RUC+usuarioSOL`.
- `soap:Fault` → código = dígitos del faultcode o faultstring (`Client.0111` → `0111`).
- CDR: primer `.xml` del ZIP de respuesta; se leen `ResponseCode`, `Description`, `ReferenceID`, `Note[]`. Descripciones en ISO-8859-1 → UTF-8.
- **Reglas de la app (prevalecen sobre Greenter):** 0 = aceptado; **1000–3999 = rechazado** (aunque Greenter diga éxito); ≥4000 = aceptado con observación. Reintentables: `0`, `100`, `109`, `500`, `1033`, `2800` y errores `HTTP`/`NETWORK_ERROR` (estos dos solo en 01/03/07/08).
- Tickets: 98 = en proceso; 0 = procesado; 99 = procesado con errores (leer CDR). En el polling de API-PRO, `0`, `98`, `187` (y `401` en GRE) también se tratan como "en proceso".

### 6.6 GRE REST (anexo 06 §5)

- Token: `POST {auth}/clientessol/{client_id}/oauth2/token/`, `grant_type=password`, `scope=https://api-cpe.sunat.gob.pe`, usuario `RUC+usuarioSOL`; se reutiliza mientras le queden más de 600 s; reintento si responde "Token NO existe".
- Envío: `POST {cpe}/contribuyente/gem/comprobantes/{nombre}` con `{"archivo":{"nomArchivo","arcGreZip"(base64),"hashZip"(sha256 hex del zip)}}` → `numTicket`.
- Consulta: `GET {cpe}/contribuyente/gem/comprobantes/envios/{ticket}`; un 422 se lee del primer elemento de `errors[]`.

### 6.7 Consultas

- `consultar-cdr` debe usar **`getStatusCdr(ruc, tipo, serie, numero)`** contra `billConsultService` (API-PRO usa `getStatus` y está roto; verificado en `app/Actions/Documents/ConsultCdrAction.php:17`).
- `consultar-cpe`: Consulta Integrada REST con OAuth2 (`client_id/secret` del tenant), parámetros `fecha_emision` dd/mm/yyyy y `monto`.

### 6.8 PDF y QR (anexo 05 §7)

- dompdf con plantillas Blade en A4, A5, ticket 80 mm y 58 mm; branding por empresa (logo, teléfonos, cuentas, billeteras, mensajes).
- QR: `RUC|TIPO|SERIE|CORRELATIVO|IGV|TOTAL|FECHA|TIPO_DOC_CLIENTE|NUM_DOC_CLIENTE` (**sin hash**; la retención tiene su variante). El prompt pide "PDF con QR y hash": el hash se imprime en el PDF; el QR se mantiene igual que API-PRO.
- `greenter/report` **no se usa**.

---

## 7. Colas, jobs, reintentos, tareas programadas (anexos 03 §4.2 y 05 §5)

### 7.1 Jobs

| Job | Cola | Intentos | Backoff | Notas |
|---|---|---|---|---|
| `SendDocumentToSunat` (01/03/07/08) | `sunat` | 20 | 15 s … 3600 s (~15 h) | timeout 90 s; rate limit por tenant (120/min); sin solapamiento por documento; no reenvía `aceptado/anulado`; circuit breaker |
| `SendDispatchGuideToSunat`, `SendSummaryToSunat`, `SendVoidedToSunat`, `SendRetentionToSunat`, `SendPerceptionToSunat`, `SendReversionToSunat` | `default` | 10 | 15 s … 600 s | al agotar → `rechazado` (`MAX_RETRIES`) |
| `CheckTicketStatus` (GRE), `CheckSummaryTicketStatus`, `CheckVoidedTicketStatus` | `default` | 15 | 15 s … 600 s | polling de tickets |
| `NotifyWebhookJob` | `webhooks` | 3 | 10, 30, 90 s | `{event, document, timestamp}`, `X-Signature: sha256=HMAC(body, api_secret)`, `X-Webhook-Event`, timeout 15 s |
| Correos | `mail` | — | — | recuperación de credenciales, avisos |
| SIRE | `sire-poll`, `sire-heavy`, `sire-process` | — | — | D2 |

- Agotados los 20 intentos por error transitorio, el documento vuelve a `pendiente` con `sunat_code = SUNAT_TIMEOUT` (webhook `document.timeout_sunat`) y luego se intenta recuperar el CDR.
- **Circuit breaker** por endpoint: 5 fallos en 60 s → abierto 300 s → semiabierto 30 s con 1 sonda → fallo de sonda → abierto 600 s. Estado en caché `sunat_cb:*`.

### 7.2 Eventos y webhooks

Eventos internos `DocumentCreated`, `DocumentSent`, `DocumentRejected` (+ eventos de suscripción). Eventos de webhook emitidos: `document.sent`, `document.rejected`, `document.timeout_sunat`, `document.status_updated`, `summary.sent|rejected|status_updated`, `voided.sent|rejected|status_updated`, `reversion.sent|rejected`, `retention.sent|rejected`, `perception.sent|rejected`. Payloads por tipo en anexo 05 §5.2 (p. ej. documento: `id, tipo_documento, serie, correlativo, numero_completo, fecha_emision, total, sunat_status, sunat_code, sunat_description, hash_cpe`). Un 4xx/5xx del receptor **no** se reintenta (solo errores de conexión): propuesta C, reintentar también ante 5xx. Para la LC se agregan `purchase_settlement.sent|rejected|status_updated` (D7).

### 7.3 Propuesta para las nuevas APIs

- Cola persistente con **Redis** (BullMQ, Celery/RQ, Hangfire, Spring/Redis o tabla de trabajos + `@Scheduled`, `apalis` en Rust) con los mismos nombres de cola, intentos y backoff.
- Envío idempotente: bloqueo por documento (`sunat-send:{tipo}:{id}`) y transición de estado atómica (`UPDATE … WHERE sunat_status IN (…)`), para que dos workers nunca envíen el mismo comprobante.
- Encolar **después del commit** (API-PRO encola dentro de la transacción; bug 05/16).

### 7.4 Scheduler

| Tarea | Frecuencia |
|---|---|
| `sunat:reintentar-pendientes` (reencola pendiente/enviado sin rechazar) | cada 15 min |
| `summaries:poll-pending` | cada 5 min |
| `logs:purge --days=90` | domingos 03:00 |
| `partitions:create --months=3` (MySQL) | día 1, 02:00 |
| `certificados:verificar --alertar` (30 días) | diario 08:00 |
| `ops:jobs-fallidos`, `ops:salud` | cada hora / cada 10 min |
| `ResetMonthlyUsage`, `CheckTrialExpiration`, `ProcessRecurringPayments` | mensual / diario (D2) |
| `sire:poll-pending`, `sire:reconcile-all` | cada minuto / diario (D2) |
| Backups | diario |

Agregar en las nuevas APIs: purga de `idempotency_keys` y polling de RA/RR (hoy sin rescate).

---

## 8. Almacenamiento de XML, CDR y PDF

- Disco privado `comprobantes` (`storage/app/private`); ruta `{ruc}/{cod_local|0000}/{YYYY-MM-DD fecha_emision}/{carpeta}/{xml|cdr|pdf}/{archivo}`.
- Carpetas: `facturas`, `boletas`, `notas_credito`, `notas_debito`, `guias`, `otros` (RC/RA en `xml/` y `cdr/` del día; retenciones, percepciones y reversiones en `retentions/`, `perceptions/`, `reversions/`).
- Nombres: `{SERIE}-{correlativo 6 dígitos}.xml`, `R-{…}.zip`, `{…}.pdf`. El ZIP enviado a SUNAT **no** se guarda. Respaldo en BD: `xml_content`/`cdr_content` en algunas tablas.
- Certificados: `certificates/{ruc}/cert.pem` (privado). Logos: `logos/{ruc}/logo.{ext}` (público).
- Propuesta: mismo árbol de rutas en un volumen Docker (`/data/comprobantes`), con abstracción de almacenamiento (local / S3-MinIO). Para la liquidación: carpeta `liquidaciones_compra`.

---

## 9. Variables de entorno (anexo 03 §4.4; solo nombres)

| Grupo | Variables de API-PRO | Equivalente en las nuevas APIs |
|---|---|---|
| App | `APP_NAME`, `APP_ENV`, `APP_KEY`, `APP_DEBUG`, `APP_URL`, `APP_TIMEZONE` | `APP_ENV`, `APP_URL`, `APP_PORT` (8081…8087), `APP_TIMEZONE=America/Lima`, `APP_ENCRYPTION_KEY` |
| BD | `DB_*` | `DB_HOST`, `DB_PORT`, `DB_DATABASE=api_sunat_{carpeta}`, `DB_USERNAME`, `DB_PASSWORD` |
| Colas/caché | `QUEUE_CONNECTION`, `CACHE_STORE`, `REDIS_*` | `REDIS_URL` |
| SUNAT | `SUNAT_BETA_GRE_AUTH`, `SUNAT_BETA_GRE_CPE`, `SUNAT_BETA_GRE_CLIENT_ID/SECRET`, `SUNAT_GRE_AUTH`, `SUNAT_GRE_CPE`, `SUNAT_GRE_CLIENT_ID/SECRET`, `SUNAT_PER_TENANT_PER_MINUTE` (no están en `.env.example`) | `SUNAT_BETA_FE_URL`, `SUNAT_PROD_FE_URL`, `SUNAT_*_RETENTION_URL`, `SUNAT_*_CONSULTA_CDR_URL`, `SUNAT_*_GRE_AUTH_URL`, `SUNAT_*_GRE_CPE_URL`, `SUNAT_*_GRE_CLIENT_ID/SECRET`, `SUNAT_HTTP_TIMEOUT`, `SUNAT_PER_TENANT_PER_MINUTE` |
| Certificado PSE legado | `CERTIFICATE_PATH`, `CERTIFICATE_PASSWORD`, `CERTIFICATE_PEM_B64`, `SUNAT_RUC_PSE`, `SUNAT_RAZON_SOCIAL_PSE` | No migrar (cada empresa sube su certificado) salvo decisión contraria |
| Consulta RUC/DNI | `APIS_NET_PE_TOKEN`, `LOOKUP_API_TOKEN` + token por tenant | `LOOKUP_API_TOKEN`, `LOOKUP_PROVIDER` |
| Correo | `MAIL_*` | `MAIL_*` |
| Seguridad | `CORS_ALLOWED_ORIGINS`, `INTERNAL_TOKEN`, `SAAS_BRIDGE_SECRET`, (rama sec) `TRUSTED_PROXIES` | iguales |
| Licencia (D2) | `LICENSE_*` | según D2 |
| SIRE (D2) | `SIRE_AUTH_HOST`, `SIRE_API_HOST`, `SIRE_STORAGE_DISK` | según D2 |
| Pagos (D2) | `CULQI_PUBLIC_KEY`, `CULQI_SECRET_KEY` | según D2 |
| Observabilidad | `SENTRY_*`, `OPS_ALERT_*`, `LOG_*` | `LOG_LEVEL`, `LOG_FORMAT=json`, `SENTRY_DSN` |
| Liquidación (nuevo) | — | `LC_RETENCION_RENTA_TASA=1.5`, `LC_RETENCION_RENTA_TASA_ORO=4`, `LC_RETENCION_RENTA_MINIMO=700`, `LC_AGRO_EXENTO_HASTA=2026-12-31`, `LC_UIT_VALOR`, `LC_LIMITE_UIT=75`, `LC_REVERSION_DIAS=5` |

`config/facturacion.php` trae credenciales GRE de prueba escritas en el código: **no se replican** como valor por defecto.

---

## 10. Pruebas y colección Postman (anexo 07)

- **Postman:** 295 requests en 23 carpetas; autenticación solo por cabeceras; sin scripts de test ni respuestas guardadas; usa variables no declaradas (`id_factura`, `id_boleta`, `id_NC`…) e IDs fijos. Requests que hoy fallan contra API-PRO: `consultar-cpe` (usa `monto_total` y fecha ISO, el código pide `monto` y dd/mm/yyyy), `consultar-cdr` (`tipo_doc` vs `tipo_documento`), `PUT /empresa` (`webhook_url` vs `url_webhook` y un comentario `//` que invalida el JSON).
- **Sin request en la colección:** `/api/health`, `/v1/sunat/estado`, `/facturas/masivo`, `/buscar-documento`, `/series/init-defaults`, recuperación de credenciales, `DELETE /empresa`, alias `/api/sunat/*`, bridge.
- **Tests de API-PRO:** Pest + SQLite en memoria; sin XML dorados ni prueba de punta a punta. Portar: `MejorasApiTest`, `FormaPagoValidationTest`, `AnulacionDescargaTest`, `IdempotenciaSecretoWebhookTest`, `HealthEndpointTest` y los unitarios de tasas IGV, leyenda, fecha Lima, clasificación de códigos SUNAT y máquinas de estado.
- **Pruebas doradas:** 181 XML firmados con CDR 0 (beta) en `storage/app/public`:
  - `20123456789/0000/2026-08-24/` (F001-1…25) corresponde a la carpeta de facturas de Postman.
  - `<RUC-REAL>/…` cubre todos los tipos (detracción, percepción, exportación, gratuita, crédito, boleta, NC, ND, GRR, GRT, RC, RA, retención, percepción, RR).
  - XML GRE oficiales de SUNAT + `UBL-DespatchAdvice-2.1.xsd` + XSL de validación en la carpeta del manual de guías.
  - Se copiarán a `tests/fixtures/` de cada API **solo** los XML (sin certificados, tokens ni volcados). El RUC `<RUC-REAL>` es real: los fixtures se tratan como datos internos.
- **No usar:** `storage/app/private/API SUNAT/*.zip` (volcado de BD), `cert.pem`/`.pfx`, `token.txt`, `guia-trasportista/config.php` (credenciales reales en claro). La suite usará un certificado autofirmado propio.
- **Propuesta de suite común:** colección Postman corregida + *environment* (`base_url`, `api_key`, `api_secret`, `id_*`) + scripts Newman que validen status, `estado` y capturen IDs, ejecutable contra las 7 APIs cambiando `base_url`.

---

## 11. Liquidación de compra electrónica (tipo 04) — propuesta (anexo 08 §6)

### 11.1 Lo que dice la documentación oficial disponible (reglas de validación 24/04/2026, hoja `LiquidacionCompra2_0`)

| Aspecto | Regla | Código |
|---|---|---|
| Raíz | `SelfBilledInvoice`, UBL 2.1, Customization 2.0, `InvoiceTypeCode = 04` | 1003/1004 |
| Canal | Solo SEE-del contribuyente (no OSE) | f.1 |
| Serie | `^L[A-Z0-9]{3}-[0-9]{1,8}$` (contingencia numérica) | 1001 |
| Tipo de operación (cat. 51) | Solo **0501** compra interna, **0502** anticipos, **0503** compra de oro (exige propiedades 6000/6004/6005/6006) | 3206 |
| Emisor = comprador | `cac:AccountingCustomerParty`, RUC `schemeID=6`, activo, habido, **afecto a Renta 3.ª** | 2010, 2011, 2463, 1086 |
| Vendedor | `cac:AccountingSupplierParty`, doc **1, 4 o 7**; DNI existente, no fallecido, mayor de edad, sin RUC activo | 2800, 2450, 2460, 2461, 2462 |
| Domicilio del vendedor | Dirección, ubigeo y `AddressTypeCode` (cat. 60: 01 Punto de venta, 02 Producción, 03 Extracción, 04 Explotación, 05 Otros) obligatorios | 2452, 2453, 2456, 2457 |
| Lugar de la operación | `cac:DeliveryTerms/cac:DeliveryLocation`: dirección, ubigeo y `LocationTypeCode` (cat. 60) obligatorios | 2454, 2455, 2917, 2458, 2459 |
| Código de producto SUNAT | Obligatorio desde 01/08/2026 | 3506 |
| Tributos por línea | 1000/9996/9997/9998 + **3000 (Renta, `IR`, `TOX`) obligatorio** y/o 9999 | 3223 |
| `TaxTotal` (línea y global) | Suma 1000 + 9999, **sin** el 3000 | 4293, 4301 |
| `TaxInclusiveAmount` | Valor de venta + 9999 + IGV | 4310 |
| `PayableAmount` | TaxInclusive − IGV − Renta − 9999 − anticipos + redondeo | 4312 |
| Límite anual | 75 UIT por vendedor | 2451 |
| Plazo de envío | 1 día; fecha de emisión no posterior al envío | 2108, 2329 |
| Anticipos | `PrepaidPayment`, documento tipo 10, descuento cat. 53 `61` (retención de renta por anticipos) | 2465 |
| NC/ND | **No** admiten el 04 como documento modificado | — |
| Reversión | **Resumen de Reversiones** (`VoidedDocuments` RR, `sendSummary`), acepta tipo 04; plazo **5 días desde el CDR aceptado** (vigencia 01/08/2026); la LC no debe tener pagos; no se reutiliza el número | 2308, 2345, 2958, 2398, 2751, 2471 |

Todos los descuadres de totales de la LC son **observaciones**, no rechazos (salvo los indicados con E).

### 11.2 Tablas propuestas

- `series`: admitir `tipo_documento = '04'` (prefijo L).
- `purchase_settlements` (nombre en inglés como el resto del esquema; alternativa `liquidaciones_compra`, D7): `id`, `tenant_id`, `sucursal_id`, `cod_local`, `serie`, `correlativo`, `fecha_emision` (datetime Lima), `tipo_operacion` (0501/0502/0503), `tipo_moneda`, `vendedor_tipo_doc` (1/4/7), `vendedor_num_doc`, `vendedor_nombre`, `vendedor_direccion`, `vendedor_ubigeo`, `vendedor_tipo_domicilio` (cat. 60), `lugar_direccion`, `lugar_ubigeo`, `lugar_tipo` (cat. 60), `mto_oper_gravadas`, `mto_oper_exoneradas`, `mto_oper_inafectas`, `mto_oper_gratuitas`, `mto_igv`, `mto_base_renta`, `porcentaje_renta`, `mto_retencion_renta`, `mto_otros_tributos`, `sub_total` (TaxInclusive), `total_anticipos`, `redondeo`, `mto_imp_neto` (PayableAmount), `leyenda`, `observacion`, `guia_relacionada` (json), `placa_vehiculo`, `motivo_traslado` (cat. 20), `sunat_status` (`pendiente, enviado, aceptado, rechazado, reversion_en_proceso, revertido`), `sunat_code`, `sunat_description`, `sunat_notes` (json), `hash_cpe`, `cdr_fecha` (fecha del CDR aceptado: base del plazo de reversión), `xml_path`, `cdr_path`, `pdf_path`, `payment_status`, `monto_pagado`, `sent_at`, timestamps, `deleted_at`. UNIQUE (`tenant_id`, `serie`, `correlativo`).
- `purchase_settlement_items`: igual que `invoice_items` + `cod_producto_sunat` obligatorio + `porcentaje_renta`, `mto_base_renta`, `mto_renta`, propiedades de oro (json).
- Reversión: reutiliza `voided_documents` (prefijo RR) con `tipo_documento = '04'` en sus detalles.
- Pagos: reutiliza `payments` (necesario para la regla 2471 "sin pagos registrados").

### 11.3 Endpoints propuestos (mismo prefijo y pila que API-PRO)

| Método | URL | Notas |
|---|---|---|
| POST | `/api/v1/liquidaciones-compra` | con `license` + `check.limit:sunat`; asíncrono por cola `sunat`; 201 |
| GET | `/api/v1/liquidaciones-compra` | filtros como facturas; paginación `datos.datos` + `datos.paginacion`, `por_pagina` |
| GET | `/api/v1/liquidaciones-compra/{id}` | estado incluido en `sunat` |
| GET | `/api/v1/liquidaciones-compra/{id}/xml|cdr|pdf` | igual que facturas |
| POST | `/api/v1/liquidaciones-compra/{id}/enviar` · `/reenviar` | igual que facturas (el prompt no los lista; se agregan por consistencia, D7) |
| POST | `/api/v1/liquidaciones-compra/{id}/reversion` | valida CDR aceptado, causal, plazo y que no tenga pagos; genera RR |
| POST/GET/DELETE | `/api/v1/liquidaciones-compra/{id}/pagos` | necesario para registrar el pago y aplicar la regla 2471 (D7) |
| GET | `/api/v1/liquidaciones-compra/reporte-retenciones?periodo=AAAA-MM` | por liquidación: base, IGV retenido, renta retenida; totales del periodo (apoyo para pago vía SUNAT Virtual / Form. 617) |

### 11.4 Diferencias con la factura

Roles invertidos (Customer = emisor, Supplier = vendedor) y el Customer va **antes** que el Supplier en el XML; lugar de operación obligatorio; tributo 3000 por línea y global; el neto no incluye IGV; sin forma de pago, detracción, percepción, ISC, ICBPER, IVAP ni exportación; reversión en lugar de NC/baja; código de producto obligatorio.

### 11.5 Huecos y contradicciones a resolver antes de la Fase 5B (D6)

1. **No hay XSD** (`UBL-SelfBilledInvoice-2.1.xsd` y demás) ni guía XML de la LC. El orden de elementos propuesto sale del estándar OASIS y hay que verificarlo.
2. **RS 244-2019 / Anexo 9-A** no está en la carpeta.
3. **Resolución de reversiones:** el prompt cita la **RS 000047-2026** (vigente desde julio de 2026, "hasta el quinto día calendario siguiente a la emisión"). Las reglas de validación citan la **RS 048-2026 / 000048-2026**, vigencia **01/08/2026**, y miden **5 días desde el CDR aceptado** (ERR 2958). Propuesta: validar las dos condiciones (la más estricta) hasta tener el texto.
4. **Tasas de retención de renta** (1,5 %, 4 % oro, mínimo S/ 700, agro exento hasta 31/12/2026): vienen del prompt, no de la documentación (regla 4237 solo dice "tasas vigentes"). Irán en configuración.
5. **Valor de la UIT** (para el límite de 75 UIT): configuración por año. La validación del acumulado anual del vendedor se hará con las LC emitidas por el propio tenant (SUNAT valida el total global).
6. **Endpoint de envío** y si `getStatusCdr` acepta el 04: a confirmar en beta (hipótesis: `billService` FE con `sendBill`; consulta por Consulta Integrada REST, que sí admite el 04). Endpoint del RR de una LC (FE u *otroscpe*): a confirmar en beta.

---

## 12. Bugs y comportamientos de API-PRO — tabla de decisión (D3)

Propuesta: **corregir** (C) los que rompen funcionalidad o seguridad y documentar cada diferencia en `docs/DIFERENCIAS.md`; **replicar** (R) las irregularidades de contrato que un cliente puede depender de ellas.

| # | Hallazgo (anexo) | Propuesta |
|---|---|---|
| 1 | `consultar-cdr` usa `getStatus` en vez de `getStatusCdr`; además el CDR binario rompería el JSON (05/1, 02/11) | C (devolver `cdr_zip` en base64) |
| 2 | Recuperación de credenciales devuelve un secret que no autentica (03/3) | C |
| 3 | `bridge/provision` devuelve el secret cifrado; `bridge/tenant` usa SQL de MySQL (02/2-3) | C (si D2 lo incluye) |
| 4 | Alias `/api/sunat/*` sin licencia, cupo, idempotencia ni log; `/boletas/{id}/anular` siempre falla (02/5, 01/21) | C (pila completa) |
| 5 | RC/RR marcan `anulado` al recibir el ticket y no revierten si SUNAT rechaza; RA rechazada antes del ticket no revierte (05/4-5) | C (`anulacion_en_proceso` hasta el resultado) |
| 6 | Boletas de un RC quedan `enviado` y el reintento de 15 min las reenvía una a una (05/3) | C (no reencolar documentos con ticket de RC) |
| 7 | `fecha_comunicacion` de RA/RR en UTC (después de las 19:00 de Lima sale el día siguiente) (05/7) | C (America/Lima) |
| 8 | `GET /anulaciones/{id}/estado` trata 98 como rechazo y consulta las RR en el endpoint FE (05/6) | C |
| 9 | Circuit breaker casi nunca se abre (05/2) | C (contar fallos de transporte) |
| 10 | Falta de licencia o error del builder → `rechazado CERT_ERROR` permanente (05/8) | C (error de configuración, no rechazo SUNAT) |
| 11 | `enviar`/`reenviar` reenvía documentos anulados; carrera que deja `enviado` sobre `aceptado` (01/7-8) | C |
| 12 | `PUT` de comprobantes sin validación, IGV 18 % fijo, pierde `cod_producto_sunat` y descuentos (01/6) | C (validar como `store`, rama sec) |
| 13 | `masivo` sin validación (01/9) | C (validar cada ítem); se mantiene `total` como string y el 422 con `estado: exito` (R) |
| 14 | `leyendas` se valida pero no se guarda; NC no guarda `descuentos_globales`; ISC/ICBPER no se persisten; RC con IGV 18 % fijo (01/1-5, 05/9-10) | C |
| 15 | Escala de `descuentos_globales.porcentaje` incoherente (0..1 validado, usado como 0-100) (01/3, 05/11) | D (definir escala; propuesta 0-100 con validación `max:100`) |
| 16 | Id no numérico → 500; `?sunat_status=` vacío → 500 en NC/ND; `por_pagina` sin tope (01/12-13) | C (404 / ignorar / tope 100) |
| 17 | Reportes pierden el último día del rango; panel en UTC (02/26-27) | C |
| 18 | Guías: reglas duplicadas anulan regex de `nro_circulacion`/`tuc`; update de GRT valida como GRR y descarta campos; respuesta sin `tipo_documento` (02/21-23) | C (agregar `tipo_documento` a la respuesta es aditivo) |
| 19 | RR: `consulta_estado` apunta a ruta inexistente, claves en inglés, anti-duplicado roto en PostgreSQL (02/16-17) | R claves; C ruta y anti-duplicado |
| 20 | Correlativos RA/RR sin bloqueo; RC con carrera (02/14-15) | C |
| 21 | Doble conteo de uso por *event discovery* (01/16, 05/17) | C (un solo punto de conteo) |
| 22 | `generateLeyenda`: EUR como dólares, "VEINTIUNO MIL", posible "100/100" (05/13) | D (propuesta: R para que el XML sea idéntico, salvo EUR → EUROS y el caso 100/100) |
| 23 | Retención/percepción en moneda extranjera sin tipo de cambio (05/21) | C |
| 24 | Rutas web `/ver-errores` y `/limpiar-cache-ahora` públicas; `debug/lookup` | No migrar |
| 25 | Registro público permite plan de pago y producción (03/6) | C (rama sec) |
| 26 | `api_secret` regenerado queda en claro en `api_logs`; enmascarado incompleto (03/5) | C |
| 27 | Idempotency sin TTL; replay pierde cabeceras (03/11) | C (TTL configurable; replay solo de JSON) |
| 28 | Sin timeouts SOAP/HTTP (05, 03/20) | C (timeouts en `.env`) |
| 29 | `trustProxies('*')`, CORS `*`, clave privada en claro (03/8-9, 17) | C |
| 30 | Paginación heterogénea, respuestas sin `mensaje`, `por_pagina`/`per_page`, decimales como string en planes/SIRE, 202 en guías (02/33-42) | **R** (contrato) |

---

## 13. Decisiones que necesito de ti antes de la Fase 1

| ID | Decisión | Mi recomendación |
|---|---|---|
| **D1** | Base de comportamiento: `main` o `fix/security-hardening` | Contrato de `main` + correcciones de la rama de seguridad |
| **D2** | Alcance de los módulos no-SUNAT: panel web Inertia, suscripciones/planes/Culqi, licencia (servidor externo + ofuscación), SIRE, bridge SaaS, registro público, `check.limit` por plan | Migrar **toda la API `/api`** salvo NV/COT. **No** migrar el panel web ni el sistema de licencia comercial (es protección del producto original; el middleware `license` se omite o queda como interruptor desactivado). SIRE al final (fase adicional) por su tamaño. Suscripciones y planes: migrar los endpoints y límites, **sin** cobro con Culqi (o confirmar si lo quieres) |
| **D3** | Bugs: corregir o replicar (tabla §12) | Según la columna "Propuesta" |
| **D4** | Autenticación: el prompt dice JWT, API-PRO usa API Key + Secret | API Key + Secret por cabeceras, idéntico a API-PRO (JWT no existe en el contrato) |
| **D5** | Cifrado de secretos y `api_logs` | AES-256-GCM propio por API (cada una tiene su BD); `api_logs` sin partición + purga de 90 días |
| **D6** | Documentación SUNAT faltante | Que me proporciones los XSD UBL 2.1/SUNAT, la RS 244-2019 (Anexo 9-A) y la resolución vigente de reversiones de LC. Mientras tanto avanzo con todo lo demás; la LC (Fase 5B) se implementa con las reglas de validación y se confirma en beta |
| **D7** | Liquidación: nombres de tablas y endpoints extra (`enviar`, `reenviar`, `pagos`) | Tablas `purchase_settlements` / `purchase_settlement_items` (coherente con el esquema en inglés); agregar `enviar`, `reenviar` y `pagos` |
| **D8** | Entorno de desarrollo | Docker Desktop debe estar en ejecución para la Fase 1 (hoy el motor no responde) |

---

### 13.1 Decisiones tomadas (2026-10-24)

| ID | Decisión del usuario |
|---|---|
| D1 | **Contrato de `main` + correcciones de `fix/security-hardening`** y bugs funcionales corregidos; cada diferencia en `docs/DIFERENCIAS.md` |
| D2 | Se migran **suscripciones y planes**, **SIRE** (fase adicional al final) y **registro público + bridge SaaS**. **No** se migra la licencia comercial (middleware `license` omitido) ni el panel web Inertia. Culqi: sin cobro real salvo indicación posterior |
| D3 | Según la columna "Propuesta" de §12 (consecuencia de D1) |
| D4 | **API Key + Secret** por cabeceras, idéntico a API-PRO (sin JWT) |
| D5, D7 | Se aplican las recomendaciones de §13 salvo objeción |
| D6 | Se avanza; el usuario proporcionará después los XSD, la RS 244-2019 y la resolución de reversiones de LC |
| D8 | Pendiente: iniciar Docker Desktop antes de la Fase 1 |

## 14. Plan para la Fase 1 de Spring Boot (tras tu aprobación)

1. Estructura `spring-boot/` con Maven, Java 21, Spring Boot 3.x, paquetes según §6 del prompt; `git init` propio.
2. `Dockerfile` multi-etapa y `docker-compose.yml` (API en 8081, PostgreSQL `api_sunat_spring_boot`, Redis), con build y tests dentro de contenedores.
3. Flyway con el esquema de §4 (sin NV/COT) y seeders (catálogos, planes, empresa demo beta, series por defecto).
4. Filtro de autenticación `X-Api-Key`/`X-Api-Secret`, idempotencia, rate limit por plan, log de API enmascarado, cabeceras de uso y seguridad, CORS.
5. Envoltorio de respuesta y manejador global de errores idéntico (§2), serializador JSON compatible con PHP (§2.2), mensajes de validación en español con el algoritmo de nombres de atributo.
6. `/api/health` y `/api/v1/sunat/estado`; Swagger (springdoc); `.env.example`, `README.md`, `docs/DIFERENCIAS.md`.
7. Pruebas: unitarias (redondeo PHP, serialización, leyenda) y de integración con Testcontainers.
