# Diferencias con API-PRO

Registro de toda diferencia deliberada o inevitable entre esta API (Java + Spring Boot) y API-PRO
(Laravel + Greenter, rama `main`). Base de comportamiento acordada (Fase 0, D1): **contrato de
`main` + correcciones de la rama `fix/security-hardening` + bugs funcionales corregidos**.

Leyenda: **C** corrección deliberada · **T** diferencia técnica inevitable · **A** alcance acordado · **N** funcionalidad nueva (no existe en API-PRO) · **?** diferencia observada, a decidir (intencional o no) o a confirmar con el oráculo.

Referencia actual (desde 2026-11-24): `Source-code/plataform-api-sunat` (copia de API-PRO del 2026-11-23).
Comparación de rutas en `Docs/api-sunat-comparativa-panel-laravel.md` §1 y en `docs/AUDITORIA.md` (Revisión 2026-11-24).

## Transversales (Fase 1)

| # | Tipo | Tema | API-PRO | Esta API |
|---|---|---|---|---|
| 1 | A | Panel web, licencia comercial, notas de venta y cotizaciones | Existen | No se migran (D2). El middleware `license` no existe: la emisión en producción no depende de un servidor de licencias. |
| 2 | T | Cifrado de secretos en BD | Cast `encrypted` de Laravel (AES-256-CBC + HMAC, `APP_KEY`) | AES-256-GCM con `APP_ENCRYPTION_KEY` (formato `v1:` + base64). Cada API tiene su propia BD, así que no se comparte el formato. |
| 3 | C | Caché del tenant autenticado | 600 s (60 s en la rama de seguridad); cambios de credenciales o activación tardan en aplicarse | Sin caché: lectura por índice único en cada petición. Una credencial rotada o una empresa desactivada dejan de funcionar al instante en todas las instancias. |
| 4 | C | `{id}` no numérico | 500 (TypeError de PHP) | 404 `Recurso no encontrado.` |
| 5 | C | Errores 500 | En `main` varios controladores devolvían `getMessage()` (SQL, rutas) | Siempre `Error interno del servidor.`; el detalle va solo al log con `error_id` (SEC-011). Con `APP_DEBUG=true` se añaden `excepcion`, `detalle`, `archivo` como en Laravel. |
| 6 | C | Idempotencia: caducidad | Las claves no caducan ni se purgan | Caducan a los `IDEMPOTENCY_TTL_DAYS` días (por defecto 1) y se purgan cada hora. |
| 7 | C | Idempotencia: qué se guarda | Cualquier respuesta < 500 con contenido, incluidas 429 y binarias (un PDF repetido salía como `application/json`) | Solo respuestas JSON < 500 y distintas de 429. |
| 8 | C | `api_logs`: enmascarado | Solo en el request y solo 4 claves; el `api_secret` regenerado quedaba en claro en la respuesta | Lista ampliada de la rama de seguridad (`client_secret`, `sire_client_secret`, `consulta_token`, `client_id`) y enmascarado también en la respuesta. |
| 9 | T | `api_logs`: partición | Particionada por mes en MySQL | Tabla simple con índice por `created_at` y purga de 90 días (`API_LOGS_RETENTION_DAYS`). |
| 10 | C | CORS | Origen por defecto `*`; `exposed_headers` con nombres inexistentes | Por defecto solo `APP_URL` (SEC-017); se exponen las cabeceras reales (`X-Usage-*`, `X-Plan`, `Idempotency-Replayed`, `X-RateLimit-*`). |
| 11 | C | Proxies de confianza | `trustProxies('*')` (se podía falsear la IP) | `RemoteIpValve` de Tomcat: solo confía en `X-Forwarded-*` de redes privadas/loopback (SEC-013). |
| 12 | C | `X-Internal-Token` | Comparación `===` | Comparación en tiempo constante (SEC-021). |
| 13 | T | Rate limit | Caché de Laravel (archivo/BD/Redis según entorno) | Redis compartido entre instancias, ventana fija de 60 s, mismos límites por plan. |
| 14 | C | Plan en caché | `PlanService::getActivePlan` cachea 300 s | Sin caché: se lee la suscripción activa en cada petición. |
| 15 | A | Planes sembrados | Incluyen `internal_documents_month`, `cotizaciones`, `notas_venta` | Se omiten esas claves (módulos fuera de alcance). |
| 16 | C | Seeders | No crean usuario ni empresa demo | Empresa demo BETA (RUC `20000000001`, `MODDATOS`) con 10 series por defecto. Sus credenciales salen de `DEMO_API_KEY`/`DEMO_API_SECRET` o se generan y se escriben en `storage/demo-credentials.txt` (nunca en el log). |
| 17 | C | Esquema de BD | Sin UNIQUE en `voided_documents.identifier` ni en `retentions/perceptions (serie, correlativo)`; UNIQUE condicional en `summaries`; `sire_client_secret` varchar(255) cifrado | UNIQUE en las cuatro tablas; `sire_client_secret` como `text`; índices en todas las FK. |
| 18 | C | Columnas que API-PRO valida pero no guarda | `leyendas`, `descuentos_globales` de NC, `tip_sis_isc`, `porcentaje_isc`, `factor_icbper` | Existen columnas para guardarlas (se usarán en las fases 3–4). |
| 19 | C | `payments.payable_type` | Nombre de clase PHP (`App\Models\Invoice`) | Valor neutro (`invoice`, `boleta`). |
| 20 | T | Rutas inexistentes / métodos no permitidos | Laravel responde 404/405 antes de la autenticación | Igual: la pila de autenticación son interceptores de Spring MVC que solo corren sobre rutas existentes. |

## Configuración y cuenta (Fase 2)

| # | Tipo | Tema | API-PRO | Esta API |
|---|---|---|---|---|
| 21 | C | Clave privada del certificado | PEM en claro en `certificates/{ruc}/cert.pem` | PEM cifrado (AES-256-GCM) en `certificates/{ruc}/cert.pem.enc`; mismos mensajes de validación. Los PFX antiguos (RC2-40/3DES) se leen con el KeyStore de Java, sin depender del binario `openssl -legacy`. |
| 22 | T | Orden y búsqueda sin tildes | MySQL `utf8mb4_unicode_ci` | Colación ICU `es_ci` para `ORDER BY` y `unaccent(lower())` para `LIKE` (clientes, UNSPSC, ubigeo): mismos resultados y orden. |
| 23 | C | `por_pagina` de clientes | Sin tope (negativo → error SQL 500) | 0 o negativo → 15; máximo 100. |
| 24 | C | `POST /registro` | Permitía elegir plan de pago (SEC-020) | El plan enviado se ignora: siempre `free`. |
| 25 | C | Recuperación de credenciales | Guardaba `sha256(secret)` y devolvía el secret: no autenticaba (BUG-01) | Guarda y devuelve el mismo secret; el token es de un solo uso (Redis, 30 min). |
| 26 | C | Correo sin SMTP | Mailer `log` (el token quedaba en el log) | Sin `MAIL_HOST` el correo se guarda en `private/mails/` y no se escribe en el log. |
| 27 | A | Suscripción con pago | Cobro con Culqi | Sin cobro real (D2): con `token` para un plan de pago → 422; sin token inicia la prueba de 14 días como API-PRO. Tampoco se envía el correo de bienvenida. |
| 28 | C | Bridge SaaS | `X-Bridge-Key` con `!==`, sin throttle; `provision` devolvía el secret cifrado; error de certificado hacía un redirect; `DELETE` usaba `SET FOREIGN_KEY_CHECKS` (MySQL) | Comparación en tiempo constante, 20 req/min por IP, secret real, 422 `{"error":...}` y borrado ordenado compatible con PostgreSQL. `bridge/auth` incluye `nonce` (SEC-016); la página `/auth/from-saas` pertenece al panel web, que no se migra. |
| 29 | C | `DELETE /empresa` | Fallaba en PostgreSQL si había retenciones/percepciones (FK sin cascada) | Se borran primero retenciones y percepciones. |
| 30 | T | `exists`/`unique` | Consultas de Eloquent | Mismas reglas sobre una lista blanca de tablas. |

## Facturas, boletas y envío a SUNAT (Fase 3)

Paridad verificada: los 28 ejemplos de facturas y boletas de la colección Postman, ejecutados contra
API-PRO real (oráculo), dan el mismo código HTTP, el mismo JSON y el **mismo XML firmado byte a byte**
(`FacturaBoletaOracleIT`, `GreenterXmlParityTest`). El cálculo usa la aritmética `float` y `round()`
de PHP 8.3 (`PhpMath`, validado sobre 6 000 valores).

| # | Tipo | Tema | API-PRO | Esta API |
|---|---|---|---|---|
| 31 | C | `PUT /facturas|boletas/{id}` | Sin validación, IGV 18 % fijo, perdía `cod_producto_sunat` y descuentos del ítem | Validación de la rama de seguridad (SEC-010), tasa del régimen de la empresa y el ítem se guarda completo |
| 32 | C | `POST /facturas/masivo` | Solo comprobaba 4 campos | Cada factura pasa la validación completa de `POST /facturas`; los errores salen en `errores[].errores`. Se mantiene `total` como cadena y el 422 con `estado: exito` |
| 33 | C | `enviar`/`reenviar`/`PUT` de un comprobante anulado | Lo reenviaba a SUNAT | 422: un comprobante anulado o en anulación no se envía |
| 34 | C | Carrera `enviado` sobre `aceptado` | El job podía terminar antes del `update(['sunat_status' => 'enviado'])` | El trabajo se encola al confirmar la transacción |
| 35 | C | `leyendas` | Se validaban pero no se guardaban ni llegaban al XML | Se guardan y se agregan como `cbc:Note` |
| 36 | C | `tip_sis_isc`, `porcentaje_isc`, `factor_icbper` del ítem | No se guardaban; el XML los recalculaba | Se guardan y el XML usa los valores enviados |
| 37 | C | `descuentos_globales.*.porcentaje` | Validado `max:1` pero usado como 0-100 | Escala 0-100 (`max:100`) |
| 38 | C | Leyenda en euros / 100 céntimos | "DOLARES AMERICANOS" para EUR; podía salir "100/100" | "EUROS"; el redondeo a 100 céntimos suma 1 al entero |
| 39 | C | Conteo de uso del mes | Podía contarse dos veces (listener por *event discovery*) | Un solo incremento atómico por comprobante |
| 40 | C | Circuit breaker | Solo contaba `SoapFault` (casi nunca) | Cuenta los errores de transporte (`HTTP`, `NETWORK_ERROR`) |
| 41 | C | Certificado faltante o inválido | `rechazado` + `CERT_ERROR` (parecía un rechazo de SUNAT) | Queda `pendiente` con `sunat_code = CERT_ERROR` y el motivo; se reenvía al corregir el certificado |
| 42 | C | Recuperación por CDR tras timeout | Llamaba a `getStatus` con el nombre del archivo como ticket (nunca recuperaba) | `getStatusCdr` del servicio de consulta |
| 43 | C | Espera por límite de la empresa u otro envío del mismo documento | Consumía intentos (podía agotar los 20) | Se reencola sin consumir intentos |
| 44 | C | TLS con SUNAT y clave SOL | `verify_peer=false`; la clave SOL se insertaba sin escapar en WS-Security | Certificado de SUNAT validado; clave escapada. Timeout configurable (`SUNAT_HTTP_TIMEOUT`) |
| 45 | C | Texto con `&`, `<`, `>` fuera de CDATA | Se imprimía crudo (XML inválido, la firma fallaba) | Se escapa; con datos normales el XML es idéntico |
| 46 | C | Webhooks | Seguían redirecciones; sin control de destino en `main` | Sin redirecciones y bloqueo de destinos privados/reservados al enviar (SEC-005) |
| 47 | C | Errores 500 al crear | `getMessage()` de la excepción | `No se pudo crear la factura. (ref: …)` + `error_id` (rama de seguridad) |
| 48 | C | Correlativo manual duplicado de un comprobante eliminado | La validación lo ignoraba y el `INSERT` fallaba con 500 | La validación también considera los eliminados |
| 49 | T | Cola de trabajos | Colas de Laravel (`sunat`, `webhooks`) | Cola propia sobre Redis con los mismos intentos, backoff y timeout; un trabajo de un worker caído se recupera a los 5 min |
| 50 | T | Orden de listados | `ORDER BY` de la columna pedida | Igual, más `id` como desempate (paginación estable); `client_razon_social` con colación `es_ci` |
| 51 | A | PDF automático tras la aceptación | Se generaba al aceptar | Llega en la Fase 6 (`archivos.pdf` es `null` hasta entonces) |
| 52 | T | Cupo mensual (`check.limit:sunat`) | Conteo cacheado 300 s; switch global en la tabla de ajustes del panel | Conteo sin caché; switch global por `EMISION_ILIMITADA_GLOBAL` |

## Notas, resumen diario, bajas y consulta de CDR (Fase 4)

Paridad verificada con el oráculo de API-PRO (`fixtures/oracle/fase4.json`, 55 pasos: NC, ND,
RC de envío y de anulación, RA, validaciones, listados y estados): mismo HTTP, mismo JSON y mismo
XML firmado byte a byte (`Fase4OracleIT`, `TicketAndNoteXmlParityTest`). Probado en SUNAT beta:
NC, ND, resumen diario y comunicación de baja aceptados.

| # | Tipo | Tema | API-PRO | Esta API |
|---|---|---|---|---|
| 53 | C | `POST /consultar-cdr` | Llamaba a `getStatus` con el nombre del comprobante como ticket (nunca encontraba nada) y el CDR binario rompía el JSON | `getStatusCdr` del servicio de consulta; `datos` = `{success, code, description, notes, accepted, cdr_zip (base64)}` o `{success: false, error_code, error_message}`. SUNAT beta no tiene este servicio (responde 404); funciona en producción |
| 54 | C | Resumen de anulación (RC) | Marcaba las boletas `anulado` al recibir el ticket, aunque SUNAT luego lo rechazara | Siguen `anulacion_en_proceso` hasta el resultado: aceptado → `anulado`; rechazado → `aceptado` |
| 55 | C | RC/RA rechazados antes del ticket | Los documentos quedaban `anulacion_en_proceso` para siempre | Vuelven a `aceptado` |
| 56 | C | Plazo de 7 días (RC y RA) | Comparaba la fecha recibida interpretada en UTC: el último día del plazo se rechazaba | Se comparan fechas de Lima |
| 57 | C | `fecha_comunicacion` por defecto de la RA | `now()` en UTC (después de las 19:00 salía el día siguiente) | Fecha de Lima |
| 58 | C | Numeración de RC/RA del día | Sin bloqueo (dos peticiones simultáneas podían repetir correlativo) | Bloqueo transaccional por empresa (`pg_advisory_xact_lock`) |
| 59 | C | `GET /anulaciones/{id}/estado` | El código 98 ("en proceso") se guardaba como rechazo | 98, 0 y 187 son "en proceso"; mismo criterio que el trabajo de la cola |
| 60 | C | Porcentaje de IGV en el resumen diario | Fijo 18 | El del régimen de la empresa (`igv_rate_override`, RUS 0) |
| 61 | C | Errores de transporte al enviar RC/RA | Se marcaban `rechazado` | Se reintentan como en facturas |
| 62 | C | Listado de NC/ND con un filtro vacío (`?sunat_status=`) | 500 | Se ignora; `por_pagina` limitado a 1–100 |
| 63 | C | `PUT` de NC/ND | Sin validación y con IGV 18 % fijo | Valida cliente, ítems, documento afectado y motivo (catálogos 09/10) y usa la tasa del régimen |
| 64 | C | NC/ND anulada | `enviar`/`reenviar` la volvía a mandar | 422, igual que facturas (ítem 33) |
| 65 | C | `descuentos_globales` de la NC | Se validaban pero no se guardaban | Se guardan (la plantilla de Greenter no los imprime: el XML no cambia) |
| 66 | C | Texto con `&` o `<` en el motivo de una nota | XML inválido: la firma fallaba | Se escapa (ítem 45) |
| 67 | C | XML de RC sin archivo en disco | El mensaje mostraba la ruta interna | Mensaje sin la ruta |
| 68 | T | Listado de resúmenes con la misma hora de creación | Orden sin desempate | Desempate por `id` ascendente (lo que devuelve API-PRO) |

## Guías de remisión, retenciones, percepciones y reversión (Fase 5)

Paridad verificada con el oráculo de API-PRO (`fixtures/oracle/fase5.json`, 44 pasos: GRE
remitente 09 y transportista 31 por todas las variantes de la colección, retenciones, percepciones,
facturas con percepción, reversiones, validaciones, listados y estados): mismo HTTP, mismo JSON
(salvo las correcciones de abajo) y mismo XML firmado byte a byte (`Fase5OracleIT`). Flujo completo
contra un SUNAT simulado (`Fase5FlowIT`). Probado en SUNAT beta: retención, percepción y reversión
RR aceptadas (servicio "otros CPE"); guía remitente y guía transportista aceptadas en el sandbox
GRE (API REST, que solo autoriza su RUC de prueba).

| # | Tipo | Tema | API-PRO | Esta API |
|---|---|---|---|---|
| 69 | C | Reglas de `vehiculo.nro_circulacion`, `vehiculo.tuc` y `vehiculo.secundarios.*.nro_circulacion` | Declaradas dos veces: la segunda (`max:15`) anulaba el formato alfanumérico de 10–15 | Se aplican formato y longitud |
| 70 | C | `PUT /guias-remision/{id}` | Solo actualizaba parte de los campos: se perdían remitente, documentos relacionados, subcontratador, pagador del flete, RUC/código de local de partida y llegada, indicadores y autorización especial; una GRT editada sin `tipo_documento` se validaba como GRR | Actualiza todos los campos; la GRT sigue siendo GRT |
| 71 | C | `tercero` y `comprador` de la guía | Se validaban pero no se guardaban (nunca llegaban al XML) | Se guardan (migración V3) y salen como `SellerSupplierParty` / `BuyerCustomerParty` |
| 72 | C | Respuesta de la guía | Sin el tipo de guía | Incluye `tipo_documento` (09 / 31) |
| 73 | C | `GET /guias-remision/{id}/estado` | El código 98 ("en proceso") marcaba la guía `rechazado`; un ticket procesado sin CDR producía un error interno | 98, 0, 187, 401 y los fallos de red son "en proceso" (202); sin CDR queda rechazada con el código de SUNAT |
| 74 | C | Nombres en CDATA inyectados en la GRT (remitente, subcontratador, pagador) | Se les aplicaba `htmlspecialchars`: SUNAT recibía `&amp;` literal | Van literales dentro del CDATA |
| 75 | C | Factura o boleta relacionada en una GRT sin RUC emisor | Se aceptaba y SUNAT la rechazaba (3380) | 422 con mensaje claro |
| 76 | C | Error de certificado o de credenciales GRE al enviar | La guía, retención o percepción quedaba `rechazado` | Queda `pendiente` con el código `CERT_ERROR` / `GRE_CREDENCIALES` (no es un rechazo de SUNAT) |
| 77 | C | `siguiente_accion` de una guía sin ticket | "php artisan queue:work --queue=default" | "Verifique que el worker de colas esté ejecutándose (cola sunat)." |
| 78 | C | Retención/percepción en moneda extranjera | No exigía tipo de cambio y aplicaba la tasa al importe en dólares, informado como PEN | Exige `tipo_cambio` y calcula lo retenido/percibido y el neto en soles con el factor |
| 79 | C | Mensaje del envío manual de retención/percepción | Ruta en inglés (`/retentions/{id}/enviar`) | Ruta real (`/retenciones/{id}/enviar`, `/percepciones/{id}/enviar`) |
| 80 | C | Webhook de retención/percepción | `tipo_documento` y `total` nulos | `20`/`40` y lo retenido/percibido; la guía informa su tipo real (API-PRO siempre "09") y el número con ceros |
| 81 | C | `consulta_estado` de la reversión | `/api/v1/voided/{id}/status` (no existe) | `/api/v1/anulaciones/{id}/estado` |
| 82 | C | Documentos de una reversión | Se marcaban `anulado` al recibir el ticket, aunque SUNAT luego la rechazara | Siguen `anulacion_en_proceso` hasta el resultado del ticket (consultado en "otros CPE") |
| 83 | C | Antiduplicado de la reversión | Comparaba el correlativo con su tipo JSON (`"1"` ≠ `1`) | Compara sin tipo ni ceros a la izquierda; numeración del día con bloqueo; `fecha_comunicacion` por defecto en hora de Lima; solo se guardan los campos validados de cada detalle |
| 84 | C | Errores de transporte (HTTP/red) al enviar retenciones, percepciones y RR | Se marcaban `rechazado` | Se reintentan |
| 85 | C | `per_page` / `por_pagina` de retenciones, percepciones y guías | Sin tope | Máximo 100 |
| 86 | C | Errores internos de creación/consulta | El mensaje de la excepción en la respuesta | Mensaje genérico con `error_id` (el detalle va al log) |

## Liquidación de compra (Fase 5B)

Módulo que API-PRO **no tenía** cuando se portó (2026-10): rutas `/api/v1/liquidaciones-compra/...`, tablas
`purchase_settlements` / `purchase_settlement_items` (migración V4) y pagos en `payments` con
`payable_type = 'purchase_settlement'`. Detalle, fuentes y reglas en
[`LIQUIDACION_COMPRA.md`](LIQUIDACION_COMPRA.md). Aceptada en SUNAT beta junto con su reversión.

**Actualización 2026-11-24.** La referencia actual ya tiene el módulo, con las **mismas 13 rutas**
(`plataform-api-sunat/routes/api.php:183–195`, `app/Http/Controllers/Api/V1/LiquidacionCompraController.php`; serie
`liquidacion_compra` = `04` en `app/Models/Serie.php:25`; XML con `app/Services/Greenter/Builders/LiquidacionCompraXmlBuilder.php`;
envío con `app/Jobs/SendLiquidacionCompraToSunat.php`). Las filas 87–89 ya no son "funcionalidad nueva". El módulo de
esta API se escribió con las fuentes de SUNAT y **nunca se comparó con el oráculo** de la referencia (respuesta JSON,
XML firmado ni PDF). Los pasos para hacerlo están en `AUDITORIA.md`, Revisión 2026-11-24. Las diferencias de abajo se
vieron leyendo el código de las dos API; el oráculo puede encontrar otras.

| # | Tipo | Tema | API-PRO (referencia actual) | Esta API |
|---|---|---|---|---|
| 87 | — | Series | ~~Sin tipo 04~~: ahora también tiene `liquidacion_compra` → `04` (`Serie.php:25,38`) | `liquidacion_compra` (04, prefijo `L`). Ya no es diferencia; el texto del mensaje de tipos válidos no se comparó |
| 88 | ? | Reversión RR de la liquidación | ~~Solo retenciones y percepciones~~: ahora también `POST liquidaciones-compra/{id}/reversion` (`LiquidacionCompraController.php:178`), con plazo `liquidacion.reversion_dias` (5) desde la emisión y los estados `anulacion_en_proceso` / `anulado` (línea 194) | Misma ruta y mismo RR ("otros CPE"), pero con los estados `reversion_en_proceso` / `revertido` (`SettlementEndpoints.java:399,561`). Estados distintos: a decidir tras el oráculo |
| 89 | ? | Webhooks | `purchase_settlement.sent` cuando SUNAT responde con CDR (`SendLiquidacionCompraToSunat.php:100`) y `purchase_settlement.rejected` (`:148`) | `purchase_settlement.accepted` / `purchase_settlement.rejected` (`SendSettlementJob.java:139,157`; payload con `tipo_documento` 04, neto y retención de renta). Nombre del evento de aceptación distinto: a decidir |
| 89b | ? | Reintentos agotados al enviar la liquidación | Queda `pendiente` con `SUNAT_TIMEOUT` ("por confirmar", nunca `rechazado`: reenviar a ciegas la duplicaría) (`SendLiquidacionCompraToSunat.php:121–132`) | Queda `rechazado` con `MAX_RETRIES` (`SendSettlementJob.java:144–146`). A decidir; el criterio de la referencia parece el más seguro |

## Representación impresa: PDF y QR (Fase 6)

Paridad verificada con el oráculo de API-PRO (`fixtures/oracle/fase6.json`): **193 PDF** de 48
comprobantes (facturas en todas sus variantes —crédito, exonerada, exportación, gratuita,
detracción, descuentos, ICBPER, ISC, IVAP, con guía, contingencia, percepción—, boletas, notas de
crédito y débito, guías 09/31 y retenciones) en A4, A5 y tickets de 80 y 58 mm. Se compara el texto
de cada PDF (extraído con PDFBox de los dos, palabra por palabra), el contenido del QR y las
cabeceras HTTP (`Fase6OracleIT`). Motor: plantillas Thymeleaf (port de las vistas Blade) → Flying
Saucer sobre OpenPDF; QR con ZXing.

| # | Tipo | Tema | API-PRO | Esta API |
|---|---|---|---|---|
| 90 | T | Motor de PDF | dompdf (Blade, QR en SVG) | Flying Saucer + OpenPDF (Thymeleaf, QR en PNG con el mismo contenido, corrección L y margen 4). El diseño y los textos son los mismos; la tipografía y el interlineado pueden variar unos puntos |
| 91 | C | Parámetro de formato | Facturas leían `formato`; boletas, notas, guías y retenciones solo `format` (con `formato` daban siempre A4) | `formato` y `format` en todos |
| 92 | C | Caché del PDF (1 hora) | Por documento y formato: podía servir un PDF sin el hash, el estado o los pagos nuevos | La clave incluye `updated_at`: si el documento cambia, se genera de nuevo |
| 93 | C | QR de la guía transportista | Tipo `09` | Tipo real `31` |
| 94 | N | PDF de percepción (40) y de liquidación de compra (04) | La percepción no tenía ruta de PDF (sigue sin tenerla, `routes/api.php:174–179`); la liquidación ahora tiene `GET liquidaciones-compra/{id}/pdf` (`api.php:189`) | `GET /percepciones/{id}/pdf` y `GET /liquidaciones-compra/{id}/pdf` (QR con lo percibido y cobrado; la liquidación con IGV y neto pagado al vendedor). Desde 2026-11-24 solo el PDF de percepción es nuevo; el de la liquidación no se comparó con el de la referencia |
| 95 | C | Rutas alias `/api/sunat/facturas` y `/api/sunat/boletas` (listar, emitir, PDF, XML, anular) | Existían | Faltaban desde la Fase 3: agregadas |
| 96 | T | Cortes de línea | dompdf corta solo en espacios y guiones | Igual (estrategia propia; Flying Saucer también cortaba tras "." y "/") |

## Colas, tareas programadas y operación (Fase 7)

| # | Tipo | Tema | API-PRO | Esta API |
|---|---|---|---|---|
| 97 | T | Planificador | `schedule:run` cada minuto (cron del sistema) + `onOneServer()` | `@Scheduled` dentro de la API con los mismos horarios (UTC); candado en Redis por tarea: con varias instancias corre en una sola y no se solapa. `SCHEDULER_ENABLED=false` lo apaga |
| 98 | T | Comandos `php artisan …` | Artisan | `docker compose run --rm api ops <tarea>` (o `java -jar app.jar ops <tarea>`): misma lista de tareas y opciones, sin servidor web ni worker |
| 99 | T | Trabajos fallidos | Tabla `failed_jobs` + `queue:retry` | Misma tabla (migración V5) llenada por la cola de Redis al agotar intentos; `ops jobs-fallidos:reintentar [--id=N]` reencola con los intentos en cero. Se guarda solo el mensaje de la excepción, no la traza |
| 100 | C | Renovación de suscripciones gratuitas | Carbon muta la fecha: el nuevo periodo quedaba con inicio = fin (un mes después) | El nuevo periodo empieza donde terminó el anterior |
| 101 | C | `sunat:reintentar-pendientes` con boletas | Reenviaba individualmente boletas en `enviado` que ya iban en un resumen diario (doble declaración) | Excluye las boletas con ticket de resumen; esas las sigue `summaries:poll-pending` |
| 102 | D2 | Pagos recurrentes de planes de pago | Cobro con Culqi | Sin cobro real: pasan a `past_due` y se avisa por correo (igual que API-PRO sin tarjeta registrada) |
| 103 | D2 | Correos de suscripción | Con enlaces al panel web | Mismo contenido, sin enlaces al panel (no hay panel en esta API) |
| 104 | T | Alertas operativas (`ops:salud`, `certificados:verificar`, `ops:jobs-fallidos`) | Webhook de Slack/Discord con deduplicación | Igual (`OPS_WEBHOOK_URL`, deduplicación en Redis por `OPS_THROTTLE_MINUTES`); sin URL solo se registran en el log |
| 105 | N | Tareas no portadas | Particiones de `api_logs`, respaldos, licencia, SIRE | Particiones y licencia fuera de alcance; respaldos con `pg_dump` (ver README); SIRE en una fase posterior |

## Panel, reportes, exportación y consultas (Fase 7B)

Rutas que faltaban (detectadas al generar la colección Postman contra el OpenAPI). Paridad
verificada con el oráculo de API-PRO (`fixtures/oracle/fase7b.json`, 118 pasos: 15 del panel, 26
reportes JSON y PDF, 11 exportaciones ZIP, 9 búsquedas de documento y 3 consultas de CPE) en
`Fase7bOracleIT`; las correcciones se prueban en `Fase7bFlowIT`. El oráculo se regenera con
`tools/oracle` (ver README).

| # | Tipo | Tema | API-PRO | Esta API |
|---|---|---|---|---|
| 106 | C | Fechas del panel | "Hoy", "semana", "mes" y "año" en UTC (5 h adelantado respecto a Lima) | En America/Lima |
| 107 | C | Mes anterior de `/panel` y meses de `/panel/indicadores` y `/ventas-mensuales` | El mes anterior excluía su último día; restar meses el día 31 caía en el mes actual (desborde de Carbon) | Mes completo y meses calendario |
| 108 | C | Periodo del panel inválido (`mes=2026-13`, `desde=ayer`) | 500 | 422 |
| 109 | D2 | Notas de venta en el panel | Sumaba las notas de venta en ventas, gráfico y comparación | `notas_venta` en cero (fuera de alcance); mismas claves |
| 110 | C | Rango de los reportes | `fecha_emision` (con hora) comparada con la fecha sola: los comprobantes del último día no aparecían en registro de ventas, consolidado, notas, cobranzas ni productos | El rango incluye el último día completo |
| 111 | C | PDF de registro de ventas, notas, cobranzas y estado de cuenta | Las vistas leían `sunat_status`/`payment_status` (claves que no existen): 500 siempre que había comprobantes | Funcionan; mismo contenido que las vistas (verificado con las vistas corregidas solo en esa clave) |
| 112 | C | `num_doc` en `top_clientes` y `resumen_por_cliente` | Número (PHP convierte las claves numéricas): un DNI "01234567" salía 1234567 | Texto |
| 113 | C | `dias_atraso` y antigüedad de deuda | Días con fracción (Carbon 3) desde la medianoche UTC | Días enteros en Lima |
| 114 | C | `sucursal_id` en reportes | `exists:sucursales,id` sin empresa: aceptaba la sucursal de otra | Solo sucursales de la empresa |
| 115 | C | Fechas de reportes y exportación | Cualquier texto que Carbon entendiera ("tomorrow"); en reportes, una fecha válida para Laravel pero no AAAA-MM-DD daba 500 al calcular el rango | AAAA-MM-DD; 422 en otro caso |
| 116 | C | Cobranzas: resumen por método | Incluía pagos de notas de venta | Solo pagos de facturas y boletas (no los pagos a vendedores de liquidaciones de compra) |
| 117 | C | Estado de cuenta sin comprobantes en el rango | El historial listaba los pagos de **todos** los clientes (where vacío) | Historial vacío |
| 118 | D2 | Detalle del estado de cuenta | Incluía cotizaciones y notas de venta | Solo facturas, boletas y notas |
| 119 | N | `GET /reportes/documentos-internos` | Reporte de cotizaciones y notas de venta | No existe (404) |
| 120 | C | Nombre del ZIP | Se armaba con el texto recibido en `fecha_desde`/`fecha_hasta` | Con las fechas validadas |
| 121 | T | ZIP | Archivo temporal que nunca se borraba | En memoria |
| 122 | C | `consultar-cpe`: error genérico | El mensaje incluía la URL de SUNAT con el `client_id` | `HTTP <código>` sin URL ni credenciales; token rechazado → se renueva y se reintenta una vez |
| 123 | C | `buscar-documento`: registros | Guardaba en el log la respuesta del proveedor (nombres, direcciones) | Solo el código HTTP; proveedor configurable (`LOOKUP_BASE_URL`) |
| 124 | T | Validador | — | Reglas `after`, `after_or_equal`, `before`, `before_or_equal` de Laravel |

## Auditoría final (Fase 8)

| # | Tipo | Tema | API-PRO | Esta API |
|---|---|---|---|---|
| 125 | C | `APP_DEBUG` en producción | Si quedaba activo, las respuestas 500 incluían la excepción, el mensaje y el archivo | El detalle nunca se envía con `APP_ENV=production` |
| 126 | T | Dependencias | — | Parches de seguridad aplicados por propiedad (Tomcat, Jackson, Netty, PostgreSQL JDBC, Log4j API, commons-lang3); detalle en `docs/AUDITORIA.md` |

## Corrida de la colección Postman de API-PRO

| # | Tipo | Tema | API-PRO | Esta API |
|---|---|---|---|---|
| 127 | C | `POST /retenciones/{id}/enviar` y `/percepciones/{id}/enviar` | Solo bloqueaba `aceptado`: una retención revertida se reenviaba y volvía a `aceptado` | 422 también si está `anulado` o `anulacion_en_proceso` (igual que facturas y notas) |
| 128 | C | `POST /series` (reactivar), `PUT /series/{id}`, `POST /series/init-defaults` | Permitían bajar el correlativo por debajo del último emitido → números duplicados en SUNAT | 422 si el correlativo es menor que el mayor emitido en la serie; en `init-defaults` no se modifica ninguna de las dos series |
| 129 | C | `POST /anulaciones` y `POST /reversiones`: `fecha_generacion` | Se usaba tal cual como ReferenceDate aunque no fuera la fecha de emisión de los comprobantes (producción la rechaza; un lote podía mezclar fechas) | 422 por documento si su fecha de emisión difiere de `fecha_generacion`; los de fechas distintas van en lotes separados |
| 130 | C | `PUT /{tipo}/{id}` de facturas, boletas y notas | Siempre encolaba el envío: editar un borrador manual o una boleta `solo_registro` la enviaba | Respeta el modo de creación (columna `modo_envio`, V6); `enviar_automatico` en el cuerpo decide esa edición |
| 131 | C | NC de anulación total aceptada (motivos 01, 02, 06) | La factura o boleta seguía como deuda en cobranzas y panel | `payment_status = anulado` (el estado SUNAT sigue `aceptado`): fuera de cobranzas y sin pagos; si la NC se da de baja, vuelve a su estado de pago |
| 132 | C | Baja de factura con NC asociada | Contaba NC anuladas o rechazadas: la factura ya no se podía anular nunca | Solo cuentan las NC vigentes |
| 133 | C | Pagos de facturas y boletas (`/{id}/pagos` y `pagos` al crear) | Aceptaba pagar más que el total (336.00 sobre 236.00) | 422 si los pagos superan el saldo (con percepción, el total con percepción); fila bloqueada contra pagos simultáneos |
| 134 | C | Detracción en facturas | El `monto` se tomaba del cliente sin revisar (37.76 por 40.00) y en BD/JSON quedaba `tipo_operacion` 0101 aunque el XML llevaba 1001 | 422 si el monto difiere en más de 1.00 del porcentaje del total (en PEN); se guarda el tipo del XML (1001 con detracción, 2001 con percepción) |
| 135 | C | ICBPER (`factor_icbper`, `icbper`) | Se usaba el factor del cliente (la colección manda 0.20, tasa de 2020) | 422 si no es la tasa del año de emisión (S/ 0.50 desde 2023) o si `icbper` no es tasa × cantidad |
| 136 | C | Boleta `solo_registro` con más de 7 días | Se aceptaba y quedaba pendiente para siempre (el resumen diario la rechaza por plazo) | 422 indicando la fecha mínima |
| 137 | C | Eliminar la empresa (`DELETE /empresa`, bridge) | Quedaban en disco el certificado (con la clave privada, cifrado) y el logo | Se borran `certificates/{ruc}` y `logos/{ruc}` al confirmar el borrado; los XML/CDR/PDF se conservan |
| 138 | C | Suscripción: cancelar y cambiar de plan | Cancelar pasaba a Free al instante aunque decía "acceso hasta el final del periodo"; cambiar al mismo plan creaba una "pending"; la "pending" nunca se activaba y la cancelación no la anulaba | La cancelación vale al fin del periodo (`acceso_hasta`); mismo plan → 422; una sola reducción programada, que la tarea de renovación aplica al empezar su periodo; cancelar o mejorar anula la programada |
| 139 | C | PDF del registro de ventas | Recorría por fecha y repetía "Subtotal ..." cada vez que cambiaba el tipo, con el total completo del tipo | Filas agrupadas por tipo (por fecha dentro de cada tipo): un subtotal por tipo |
| 140 | C | Baja/reversión con `enviar_automatico: false` | Dejaba los comprobantes en `anulacion_en_proceso` desde que se creaba, aunque nunca se enviara | Los comprobantes cambian al enviarla (`POST /anulaciones/{id}/enviar`) |
| 141 | C | Ticket 80/58 mm | Página de alto fijo de A4 (841.89 pt): papel en blanco en impresoras térmicas | El alto se ajusta al contenido (dos pasadas de maquetación) |
| 142 | C | `total_impuestos` por ítem enviado por el cliente | Se usaba tal cual aunque no cuadrara con IGV/IVAP + ISC + ICBPER del ítem | 422 en `items.N.total_impuestos` si difiere en más de 0.01 |
| 143 | C | NC/ND en moneda distinta al comprobante que modifica | Se aceptaba y además se saltaba la validación del importe (#110 de la colección: NC en USD sobre factura en PEN) | 422 en `tipo_moneda` cuando el comprobante modificado es de la empresa |
| 144 | C | `POST /credenciales/recuperar` con empresa sin email | 422 "No hay un email registrado": revelaba que el RUC existe | Mismo mensaje genérico que un RUC inexistente; aviso en el log con el id de la empresa |
| 145 | A | Plazo de la reversión (RR) de retenciones y percepciones | Sin plazo | Igual: el plazo de 7 días (RS 274-2015/SUNAT, num. 7.4) se cuenta desde que se efectúa la reversión y se comunica al proveedor o cliente, hecho que la API no conoce. La liquidación de compra sí aplica el plazo de la RS 000047-2026/SUNAT (5 días desde la emisión y desde el CDR, `LC_REVERSION_DIAS`) |
| 146 | C | `POST /facturas/masivo` sin ninguna factura creada | HTTP 422 con `"estado":"exito"` | `"estado":"error"` con el mismo detalle en `datos` |
| 147 | C | `GET /resumenes/{id}/xml` de un resumen pendiente | "Espera a que el worker lo envíe" (un resumen pendiente es manual o falló el certificado: la cola nunca lo envía) | Indica usar `POST /resumenes/{id}/enviar` (o corregir el certificado) |
| 148 | C | `GET /guias-remision` y `PUT /guias-remision/{id}` | El listado ignoraba los filtros; el PUT validaba el cuerpo antes de comprobar que la guía fuera de la empresa (422 en vez de 404) | Filtra por `sunat_status`, `tipo_documento`, `serie` y `fecha_desde`+`fecha_hasta` (422 si la fecha no es AAAA-MM-DD); el PUT responde 404 primero |

## Revisión de paridad 2026-11-24

Contra la referencia actual (`Source-code/plataform-api-sunat`). Detalle en `AUDITORIA.md`, Revisión 2026-11-24.

| # | Tipo | Tema | API-PRO (referencia actual) | Esta API |
|---|---|---|---|---|
| 149 | ? | `GET /liquidaciones-compra`: `por_pagina` ≤ 0 (o no numérico) | Lo lleva a **1** (`min(max((int) por_pagina, 1), 100)`, `LiquidacionCompraController.php:70`); máximo 100 | Lo lleva a **15** (`SettlementEndpoints.java:368–370`); máximo 100. Esta API se escribió antes de que existiera la ruta en la referencia, con el criterio de clientes (#23). **A decidir**: dejar 15 (como #23) o igualar a 1. Filtros (`sunat_status`, `serie`, `vendedor_num_doc`, `fecha_desde` / `fecha_hasta`) y `reporte-retenciones?periodo=AAAA-MM` coinciden |
| 150 | ? | Pila del alias `/api/sunat/**` | Solo `resolve.tenant` y `throttle:api` (`routes/api.php:407`): sin `check.limit:sunat`, `license` ni idempotencia; `POST sunat/facturas` emite sin el límite del plan | Mismo grupo que `/api/v1/**` (`WebMvcConfig.PROTECTED`: autenticación, idempotencia, rate limit y `api_logs`); `POST /api/sunat/{facturas\|boletas}` llama a `SaleDocumentEndpoints.store`, que aplica `EmissionPolicy.deny` (`SaleDocumentEndpoints.java:75–77`). Por lectura del código el límite del plan sí se aplica, lo que corrige la referencia, pero **no hay prueba** que lo cubra (`Fase6OracleIT` solo prueba listados y PDF del alias) |

## Plan y cupo desde el panel, rol soporte (2026-11-24)

Detalle en `Docs/api-sunat-panel-admin-diseno.md` §12. Las respuestas de `/api/v1` no cambian de forma; cambian datos de cupo.

| # | Tipo | Tema | API-PRO (referencia actual) | Esta API |
|---|---|---|---|---|
| 151 | C | Plan asignado desde el panel | `Admin\TenantController::asignarPlan` (alta y edición) cancela `active` / `trialing` y crea una suscripción `active` con `payment_gateway = 'admin'` por +1 año (o `duration_days`); no cancela la reducción `pending`; un plan de pago llega a fin de periodo sin tarjeta y `ProcessRecurringPayments` lo deja `past_due` (la empresa vuelve a Free sola) | `PUT /api/admin/tenants/{id}/plan` y `POST /api/admin/tenants`: misma suscripción `admin`, pero también cancela la `pending` y, sin `duration_days`, **sin vencimiento**; si la vigente ya es de ese plan, activa, sin cancelar y sin cobro, se conserva con su periodo (`PanelPlanService`) |
| 152 | C | `tenants.max_documents_month` | Lo escribe el panel y las acciones de suscripción, pero no lo aplica nadie (`Tenant::hasReachedDocumentLimit` no se usa): el cupo es el `documents_month` del plan | El cupo del panel (`documents_month_override`, V9) es el que aplican `check.limit:sunat`, `X-Usage-*` y el reporte de uso; sin él, el del plan como antes. `max_documents_month` es la copia del cupo efectivo (V9 la normalizó: `max_documentos_mes` de `GET /api/v1/empresa` deja de mostrar el 20 por defecto y muestra el cupo real). Un cambio de plan por `/api/v1` o por las tareas programadas borra el cupo del panel |
| 153 | C | Rol `soporte` de API-PRO al migrar la base | — (Laravel) | `V7` lo convertía en `lectura`; el callback `beforeMigrate.sql` + `V10` lo conservan si la base aún no aplicó V7. Si ya la aplicó, lo reasigna un superadmin (`INSTALACION.md` §10.4) |
| 154 | N | `datos_edicion` en el detalle del panel | El panel web de la referencia editaba con su propio formulario | El detalle de comprobantes y guías del panel agrega el cuerpo completo del `PUT` de corrección; `GET /api/v1/{tipo}/{id}` no cambia |

Ubigeo: la referencia ahora también tiene `GET catalogos/ubigeo/{regiones|provincias|distritos}` (`routes/api.php:130–132`,
`Api/V1/UbigeoController.php`), con las mismas rutas que `catalog/UbigeoController.java`. No hay diferencia de ruta; las
respuestas no se compararon con el oráculo (la búsqueda sin tildes está en #22).

## Pendiente de registrar en fases siguientes

SIRE (fase posterior).

Las correcciones funcionales de la tabla §12 de `ANALISIS_API_PRO.md` (consulta de CDR, bajas
y reversiones, fechas en UTC, reportes, guías, etc.) se anotarán aquí al implementarse.
