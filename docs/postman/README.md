# Colecciones Postman

Hay dos colecciones:

| Archivo | Para qué |
|---|---|
| `API-SUNAT-Spring-Boot.postman_collection.json` | **Referencia**: un ejemplo de cada endpoint (304 peticiones), incluida la liquidación de compra, el alias `/api/sunat` y el bridge. Se genera con `tools/build-postman.js` (ver el README del proyecto). |
| `API-SUNAT-Spring-Boot.flujo-completo.postman_collection.json` | **Flujo de punta a punta**: la colección de API-PRO corregida para ejecutarse en orden de principio a fin (fechas de hoy, ids capturados, documentos de apoyo). Es la que se describe abajo. |

`API-SUNAT-Spring-Boot.flujo-completo.cambios.txt` lista cada cambio respecto de la colección de API-PRO.

## Uso

1. Importe la colección y rellene `api_key`, `api_secret` y `ruc_empresa` en sus variables (o en un entorno).
   `base_url` apunta a `http://localhost:8081/api/v1` (Docker, SUNAT beta).
2. Ejecútela en orden. El script de la colección calcula en cada petición las fechas de hoy en hora de Lima
   (`hoy`, `ahora`, `hoy_dmy`, `inicio_mes`, `mes`, `vence`, `periodo`) y guarda el `id` y el `correlativo` de
   cada documento creado (`id_factura`, `correlativo_factura`, `id_boleta`…), que usan las peticiones siguientes.
3. Desde la línea de comandos (sin publicar nada en Postman cloud):

   ```
   postman collection run docs/postman/API-SUNAT-Spring-Boot.flujo-completo.postman_collection.json -e <entorno.json> \
     -i "04. Facturas (Tipo 01)" ... --delay-request 1500 --no-report-events
   ```

   Las guías (carpetas 10 y 11) necesitan la empresa del sandbox GRE (RUC 20161515648); el resto, cualquier
   empresa en beta. Las peticiones sueltas de "01. Configuración inicial" registran o **eliminan** la empresa y
   regeneran sus credenciales: no las ejecute sobre una empresa que quiera conservar.

## Qué se corrigió respecto de la colección de API-PRO

| Hallazgo | Problema en la colección original | En esta copia |
|---|---|---|
| C1, C3 | Archivos (certificado, logo, TXT de SIRE) en `postman-cloud:///…`, inaccesibles fuera de Postman | Campo sin ruta, con la indicación de elegir el archivo local |
| C2 | `PUT /empresa` con un comentario `//` (JSON inválido: la API recibía un cuerpo vacío) y `webhook_url` | Comentario quitado; `url_webhook` |
| C4 | Token de ejemplo de 68 caracteres | Variable `{{token_recuperacion}}` (el token llega por correo) |
| C5 | `is_active` al activar/desactivar una serie | `activo` |
| C7 | Fechas fijas de 2026-03/04 (SUNAT rechaza emisiones fuera de plazo) y rangos de reportes de abril | Fechas de hoy calculadas en cada petición; reportes del mes en curso |
| — | Ids fijos de la base de producción (`/facturas/1`, `/boletas/72`, `/notas-credito/3`…) y `correlativo` manual (601, 701…) | Variables capturadas al crear; autonumeración |
| — | Bajas, reversiones y el resumen de anulación apuntaban al "último creado" (con NC asociada, pendiente o ya anulado) | Documentos de apoyo "Crear … (para x)" al inicio de las carpetas 08, 09 y 20 |
| — | Detracción 37.76 sobre 1000 (4 % = 40.00) e ICBPER 0.20 (tasa de 2020) | 40.00 y 0.50 |
| — | `consultar-cpe` con fecha ISO y `monto_total`; `consultar-cdr` con `tipo_doc` | `dd/mm/aaaa` y `monto`; `tipo_documento` |
| — | RUC de empresas reales en clientes/destinatarios y en la variable `ruc_empresa` | RUC ficticios; variable vacía. En la guía "Entre establecimientos propios" (motivo 04) el destinatario, la partida y la llegada son el propio emisor: `{{ruc_empresa}}` |
| — | Carpetas 21 (cotizaciones) y 22 (notas de venta) | Quitadas (fuera de alcance); SIRE (17) marcado como fase posterior (404) |
| — | "ND — Penalidad" con el motivo 02 | Renombrada "ND — Aumento en el valor (motivo 02)" |

C6, C8 y C10 eran de la API y están corregidos en ella (DIFERENCIAS 146–148).

## Respuestas 4xx esperadas

Varias peticiones muestran reglas de negocio y responden 4xx a propósito, por ejemplo: reenviar, editar o
eliminar un comprobante ya aceptado; pagar más que el saldo; la NC en dólares sobre una factura en soles;
crear una serie que ya existe; `consultar-cpe` sin `client_id`/`client_secret` de la API de SUNAT en la empresa;
`reportes/documentos-internos` y SIRE (no implementados). El detalle está en *Última ejecución*.

## Última ejecución (2026-10-26, Postman CLI 1.76, empresa beta 20123456789)

Carpetas 01 (sucursales, series, clientes), 04–09, 12–16, 18–20 y 23; las carpetas 10 y 11 (guías) requieren la
empresa del sandbox GRE y 17 (SIRE) no está implementada. **223 peticiones: 176 con 2xx y 47 con 4xx, todas esperadas:**

| Respuestas | Motivo |
|---|---|
| 409/422 al crear sucursales y series, y 404 en las peticiones que usan su id | La empresa ya tenía esas sucursales y series (en una empresa nueva se crean) |
| 422 al reenviar, editar, eliminar o enviar a mano un comprobante | Ya está aceptado por SUNAT: se corrige con NC o se anula |
| 422 "Registrar pago parcial" | La factura ya se pagó completa en la petición anterior (no se admite sobrepago) |
| 422 "NC - En dólares sobre factura en soles" | La nota debe ir en la moneda del comprobante (ejemplo de error) |
| 404 XML/CDR del último resumen, baja y retención | En ese momento eran los de envío manual, aún pendientes; la colección los envía después |
| 400 `/guias-remision//enviar`, `/retenciones//enviar`, `/percepciones//enviar` (carpeta 12) | La carpeta 12 va antes de las de guías, retenciones y percepciones: todavía no hay id |
| 422 consultar-cpe | La empresa de pruebas no tiene `client_id`/`client_secret` de la API de consulta de SUNAT |
| 404 `reportes/documentos-internos` | Cotizaciones y notas de venta están fuera de alcance |

SUNAT beta no rechazó ningún documento de la ejecución: facturas, boletas, notas, resúmenes (envío y anulación),
bajas (incluido el lote), retenciones, percepciones y reversiones quedaron aceptados o anulados por su baja o
reversión. Solo quedan pendientes, a propósito, la "Factura envío manual" y la "Reversión envío manual"
(creadas con `enviar_automatico=false`; la colección no trae una petición que envíe esa reversión).

Guías (carpetas 10 y 11) con la empresa del sandbox GRE (20161515648): 28 peticiones, 24 con 2xx. Las 13 guías
remitente (T001) y transportista (V001) emitidas fueron aceptadas, salvo la de envío manual, que queda pendiente
a propósito. Las 4 respuestas 4xx son esperadas: editar y volver a enviar una guía ya aceptada (422), el CDR
pedido antes de que SUNAT respondiera el ticket (404) y la guía "Entre establecimientos propios" (422), que se
corrigió después en el generador y se aceptó en un reintento (T001-13).
