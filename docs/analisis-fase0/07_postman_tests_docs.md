# 07 — Colección Postman, pruebas existentes y documentación interna de API-PRO

> Fase 0 de la migración multi-lenguaje. Fuente: `C:\laragon\www\API-PRO` (SOLO LECTURA — no se modificó nada).
> Fecha del análisis: 2026-10-24. Fuera de alcance: **Notas de Venta** y **Cotizaciones** (marcadas `[EXCLUIR]`).
> Convención: los secretos se muestran como `***`. Se citan solo datos públicos de prueba (RUC beta 20000000001, usuario MODDATOS) y datos ficticios de los ejemplos.

## Índice

1. Colección Postman
   - 1.1 Metadatos, variables, autenticación, scripts
   - 1.2 Estructura de carpetas
   - 1.3 Catálogo completo de requests (295)
   - 1.4 Cruce colección ↔ `routes/api.php`
   - 1.5 Hallazgos de calidad / incoherencias de la colección
   - 1.6 Bodies JSON representativos por tipo de documento (literal)
2. Pruebas existentes (`tests/`)
3. Documentación interna (resumen útil para la migración)
4. XML firmados / CDR de referencia (pruebas doradas)
5. Recomendaciones para la base común de pruebas
- Apéndice A — Todos los bodies JSON de la colección (literal, excepto SIRE/cotizaciones/notas de venta)

---

## 1. Colección Postman

Archivo: `C:\laragon\www\API-PRO\API FACTURACION SUNAT PERU ✅✅✅✅✅.postman_collection.json` (413 632 bytes; ~205 k espacios de indentación — el contenido útil es mucho menor).

### 1.1 Metadatos, variables, autenticación y scripts

- **Esquema**: Postman Collection v2.1.0 (`_postman_id` bb8d7266-…; exportada desde el workspace 20633485).
- **Descripción de la colección** (literal, resumida): "Colección completa en español para API SUNAT PRO — facturación electrónica Perú. **Cobertura**: todas las rutas /api/v1 con ejemplos realistas, agrupado por flujo de uso." Declara el **formato de respuesta** común:

```json
{
  "estado": "exito" | "error",
  "mensaje": "texto",
  "datos": {...} | [...],
  "meta": {...}         (paginación — opcional),
  "errores": {...}      (detalles de validación — solo en errores)
}
```

- **Variables de colección** (`variable[]`), sin valores secretos:

| Variable | Valor en la colección | Uso |
|---|---|---|
| `base_url` | `https://sunat.kodevo.es/api/v1` (prod). Doc. alternativa local: `http://api-pro.test/api/v1` | 590 usos |
| `api_key` | `***` | 295 usos (header `X-Api-Key`) |
| `api_secret` | `***` | 295 usos (header `X-Api-Secret`) |
| `ruc_empresa` | `20100000001` | declarada, **no se usa** en ninguna request |
| `periodo` | `202604` | 36 usos (SIRE) |
| `num_ticket` | (vacío) | 6 usos (SIRE) |

- **Variables usadas pero NO declaradas** (deben existir en un *environment* de Postman o se resuelven a literal `{{x}}` → 404): `id_factura` (16 usos), `id_boleta` (8), `id_NC` (4), `id_ND` (2), `id_resumen` (4), `id` (2), `id_anular` (6), `id_guia` (4), `id_guia_t` (4). No hay archivo de *environment* exportado en el repo.
- **Autenticación**: no hay bloque `auth` (ni a nivel colección, carpeta ni request). La autenticación es por **headers personalizados en cada request**: `X-Api-Key: {{api_key}}` + `X-Api-Secret: {{api_secret}}`. Única excepción: `POST /registro` (público) los tiene **deshabilitados**. `GET /planes` es público pero igual los envía (inofensivo). El backend **rechaza** credenciales por query string (test `la autenticación por query string ya no funciona`).
- **Headers estándar** por request: `Accept: application/json` (+ `Content-Type: application/json` cuando hay body JSON). 31 requests (facturas especiales, NC, ND, GRR, facturas con percepción) solo envían `Content-Type` sin `Accept`: funciona porque el backend antepone el middleware `ForceJsonAccept` a todo `/api/*` (las nuevas implementaciones **deben** devolver JSON aunque falte `Accept`).
- **Scripts**: **ninguno**. `event` = 0 ocurrencias (sin pre-request ni tests a nivel colección, carpeta o request). No hay encadenamiento automático (p. ej. guardar `id_factura` de la respuesta) → para usarla como suite automatizada (Newman) habrá que **añadir scripts de test** y de captura de IDs.
- **Respuestas guardadas** (`response[]` de Postman): **ninguna** (0 ejemplos de respuesta en las 295 requests). Los ejemplos de respuesta hay que tomarlos de la documentación interna (§3), de los tests (§2) y de `.scribe`.
- **Descripciones**: 12,7 KB en total; útiles (se resumen en §1.2/§1.5). Dos descripciones están copiadas por error (ver §1.5).
- **Uploads**: los `formdata` de tipo `file` apuntan a `postman-cloud:///…` (archivos en la nube de Postman, no portables): certificado PFX y logo. Para la suite común habrá que sustituirlos por rutas locales a un PFX de prueba.

### 1.2 Estructura de carpetas (23 carpetas raíz + 3 "subcarpetas" planas + 8 subcarpetas SIRE)

Los nombres "   - Sucursales (CRUD)", "   - Series (CRUD)", "   - Clientes (CRUD)" son **carpetas de primer nivel** con espacios iniciales (se ven como subcarpetas de la 01 pero no lo son).

| Carpeta | Requests | Descripción de la carpeta (resumen) | Alcance |
|---|---|---|---|
| 01. Configuración inicial | 9 | Registro de empresa + perfil + certificado + credenciales. | Sí |
| ␣␣␣- Sucursales (CRUD) | 6 | `cod_local` único; SUNAT exige "0000" para el establecimiento principal. | Sí |
| ␣␣␣- Series (CRUD) | 4 | Alta masiva de series. Prefijos F/B/FC/FD/T/V/R/P. Las series no se eliminan, se desactivan. | Sí |
| ␣␣␣- Clientes (CRUD) | 7 | Cat. 06: 0=Otros, 1=DNI, 4=CE, 6=RUC, 7=Pasaporte, A=Céd. diplomática. | Sí |
| 02. Régimen tributario | 6 | `tax_regime`: general, rer, mype, rus. Tasa restaurantes/hoteles (Ley 31556) vía `igv_rate_override`, ya NO es régimen. | Sí |
| 03. Suscripción y planes | 7 | `plan_slug` free/pro/business; `ciclo_facturacion` monthly/yearly; `prueba=true` = trial 14 días. | Sí (billing) |
| 04. Facturas (Tipo 01) | 38 | CRUD + 19 ejemplos (básica, crédito, exonerada, inafecta, gratuita, exportación, detracción, anticipo, descuentos, ICBPER, ISC, IVAP, guía, compleja, contingencia, envío manual) + pagos + descargas. | Sí |
| 05. Boletas (Tipo 03) | 22 | General, NRUS (0113 auto), MYPE restaurantes (override), solo_registro (va por RC), envío manual, anticipo. | Sí |
| 06. Notas de Crédito (Tipo 07) | 15 | Cat. 09 motivos 01…10. | Sí |
| 07. Notas de Débito (Tipo 08) | 13 | Cat. 10 motivos 01 mora, 02 aumento valor, 03 penalidades. | Sí |
| 08. Resumen Diario (RC) | 10 | Envío en lote de boletas y anulación de boletas aceptadas. Plazo: emisión + 7 días. | Sí |
| 09. Comunicación de Baja (RA / RR) | 11 | Anula facturas, NC, ND (y RR). Plazo 7 días. Boletas → RC. | Sí |
| 10. Guía Remisión Remitente (Tipo 09) | 14 | Modalidad 01 público / 02 privado; Cat. 20 motivos. GRE vía REST (ticket). | Sí |
| 11. Guía Remisión Transportista (Tipo 31) | 14 | Requiere remitente RUC + doc_relacionado. "Funciona en producción (no en beta SUNAT)". | Sí |
| 12. Envío Manual a SUNAT | 9 | Todos los `POST /{tipo}/{id}/enviar`. Flujo pendiente → enviado → aceptado/rechazado. | Sí |
| 13. Actualizar documentos | 6 | `PUT` sobre pendiente/rechazado, auto-reenvía. La guía exige payload COMPLETO. | Sí |
| 14. Consultar CPE / CDR | 6 | Consulta integrada CPE + CDR + exportación ZIP. | Sí |
| 15. Panel de Control | 11 | KPIs/dashboards. | Sí (secundario) |
| 16. Reportes | 7 | Todos con rango de fechas; JSON paginado. Incluye 1 reporte de documentos internos. | Parcial |
| 17. SIRE (Registro de Compras) | 26 | (la descripción dice "25 endpoints") 8 subcarpetas: 0 Activación, 1 Periodos (5.33), 2 RCE Flujo Principal, 3 Comprobantes locales, 4 Tickets, 5 Uploads TUS, 6 Ajustes Posteriores, 7 Reconciliación. | Sí (módulo aparte) |
| 18. Retenciones (Tipo 20) | 13 | Régimen 01 = 3%. Solo agentes de retención. | Sí |
| 19. Percepciones (Tipo 40) | 12 | 3 facturas con percepción (op. 2001) + 3 comprobantes 40. | Sí |
| 20. Reversión (RR) | 4 | Reversión de 20/40; se guarda en `voided_documents` con prefijo RR-. | Sí |
| 21. Cotizaciones (interno) | 7 | NO se envía a SUNAT. | **EXCLUIR** |
| 22. Notas de Venta (interno) | 8 | NO se envía a SUNAT. | **EXCLUIR** |
| 23. Catálogos: productos y ubigeo | 10 | Ubigeo región→provincia→distrito; UNSPSC (Cat. 25). | Sí |

Total: **295 requests** (15 excluidas: 7 cotizaciones + 8 notas de venta; más el reporte `documentos-internos`, que mezcla ambos → excluir o marcar opcional).

### 1.3 Catálogo completo de requests

Leyenda de headers: **A** = `Accept: application/json`; **CT** = `Content-Type: application/json`; **K** = `X-Api-Key: {{api_key}}`; **S** = `X-Api-Secret: {{api_secret}}`. URL relativa a `{{base_url}}` (= `/api/v1`). "Body (campos)" lista las claves usadas (anidadas entre llaves; `[]` = arreglo).


#### 01. Configuración inicial

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 1 | Registrar empresa (cert PFX) | POST | `/registro` | A; K (deshab.); S (deshab.) | multipart: ruc, razon_social, direccion, ubigeo, sol_user, sol_pass, certificado(file), contrasena_certificado, tax_regime, departamento, provincia, distrito, plan, entorno, client_id, client_secret |
| 2 | Listar planes (público) | GET | `/planes` | A; K; S | — |
| 3 | GET Empresa (perfil) | GET | `/empresa` | A; K; S | — |
| 4 | GET Empresa (perfil) Copy | GET | `/empresa` | A; K; S | — |
| 5 | PUT Empresa (actualizar datos) | PUT | `/empresa` | A; CT; K; S | ruc, razon_social, nombre_comercial, direccion, ubigeo, departamento, provincia, distrito, telefonos, emails, mensaje_agradecimiento, webhook_url, entorno, client_id, client_secret |
| 6 | Subir logo de empresa | POST | `/empresa/logo` | A; K; S | multipart: logo(file) |
| 7 | Subir / actualizar certificado SUNAT | POST | `/empresa/certificado` | A; K; S | multipart: certificado(file), contrasena_certificado |
| 8 | API key actual | GET | `/empresa/credenciales` | A; K; S | — |
| 9 | Genera uno nuevo api key y api secret | POST | `/empresa/credenciales/regenerar` | A; K; S | — |

#### - Sucursales (CRUD)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 10 | Crear sucursal | POST | `/sucursales` | A; CT; K; S | nombre, cod_local, direccion, ubigeo, es_principal, telefono, email |
| 11 | Crear sucursal secundaria | POST | `/sucursales` | A; CT; K; S | nombre, cod_local, direccion, ubigeo, es_principal |
| 12 | Listar sucursales | GET | `/sucursales` | A; K; S | — |
| 13 | Ver sucursal {id} | GET | `/sucursales/1` | A; K; S | — |
| 14 | Actualizar sucursal | PUT | `/sucursales/1` | A; CT; K; S | nombre, telefono |
| 15 | Eliminar sucursal | DELETE | `/sucursales/2` | A; K; S | — |

#### - Series (CRUD)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 16 | Crear series (todos los tipos) | POST | `/series` | A; CT; K; S | 1 |
| 17 | Listar series | GET | `/series` | A; K; S | — |
| 18 | Ver serie {id} | GET | `/series/1` | A; K; S | — |
| 19 | Activar/desactivar serie | PUT | `/series/1` | A; CT; K; S | is_active |

#### - Clientes (CRUD)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 20 | Crear cliente (RUC) | POST | `/clientes` | A; CT; K; S | tipo_documento, numero_documento, razon_social, nombre_comercial, direccion, email, telefono |
| 21 | Crear cliente persona natural (DNI) | POST | `/clientes` | A; CT; K; S | tipo_documento, numero_documento, razon_social, direccion, email |
| 22 | Listar clientes | GET | `/clientes` | A; K; S | — |
| 23 | Buscar cliente por nombre/documento | GET | `/clientes?buscar=demo` | A; K; S | — |
| 24 | Ver cliente por id | GET | `/clientes/1` | A; K; S | — |
| 25 | Actualizar cliente | PUT | `/clientes/1` | A; CT; K; S | razon_social, email |
| 26 | Eliminar cliente | DELETE | `/clientes/1` | A; K; S | — |

#### 02. Régimen tributario

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 27 | Cambiar a régimen GENERAL (IGV 18%) | PUT | `/empresa` | A; CT; K; S | tax_regime |
| 28 | Cambiar a Régimen MYPE Tributario | PUT | `/empresa` | A; CT; K; S | tax_regime |
| 29 | Cambiar a Nuevo RUS (rus) Categoría 1 (S/ 20) | PUT | `/empresa` | A; CT; K; S | tax_regime, nrus_categoria |
| 30 | Cambiar a Nuevo RUS (rus) Categoría 2 (S/ 50) | PUT | `/empresa` | A; CT; K; S | tax_regime, nrus_categoria |
| 31 | Override manual de tasa IGV (avanzado) | PUT | `/empresa` | A; CT; K; S | igv_rate_override |
| 32 | Ver régimen y tasa vigente | GET | `/empresa` | A; K; S | — |

#### 03. Suscripción y planes

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 33 | Ver suscripción actual | GET | `/suscripcion` | A; K; S | — |
| 34 | Crear / activar suscripción (con trial de 14 días) | POST | `/suscripcion` | A; CT; K; S | plan_slug, ciclo_facturacion, prueba |
| 35 | Crear suscripción pagada | POST | `/suscripcion` | A; CT; K; S | plan_slug, ciclo_facturacion, token |
| 36 | Cambiar de plan | PUT | `/suscripcion/cambiar-plan` | A; CT; K; S | plan_slug, ciclo_facturacion |
| 37 | Cancelar suscripción | PUT | `/suscripcion/cancelar` | A; CT; K; S | `[]` (vacío) |
| 38 | Historial de pagos | GET | `/suscripcion/pagos` | A; K; S | — |
| 39 | Uso del mes (límites del plan) | GET | `/suscripcion/uso` | A; K; S | — |

#### 04. Facturas (Tipo 01)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 40 | Crear factura básica | POST | `/facturas` | A; CT; K; S | serie, correlativo, fecha_emision, tipo_operacion, tipo_moneda, forma_pago, cliente{tipo_doc,num_doc,razon_social,direccion}, 1 |
| 41 | Crear factura, codigo producto | POST | `/facturas` | A; CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, forma_pago, cliente{tipo_doc,num_doc,razon_social,direccion}, 1 |
| 42 | Crear factura crédito (con cuotas) | POST | `/facturas` | A; CT; K; S | serie, fecha_emision, fecha_vencimiento, tipo_operacion, tipo_moneda, forma_pago, cliente{tipo_doc,num_doc,razon_social,direccion}, 1 |
| 43 | Crear factura exonerada (servicios médicos) | POST | `/facturas` | A; CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, forma_pago, cliente{tipo_doc,num_doc,razon_social,direccion}, 1 |
| 44 | Crear factura exportación (tipo 0200) | POST | `/facturas` | A; CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, forma_pago, cliente{tipo_doc,num_doc,razon_social,direccion}, 1 |
| 45 | Factura inafecta (Tipo 4) | POST | `/facturas` | CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social,direccion}, 1 |
| 46 | Factura gratuita | POST | `/facturas` | CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social,direccion}, 1 |
| 47 | Factura con detracción | POST | `/facturas` | CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social,direccion}, 1, detraccion{tipo_operacion,cod_bien,cod_medio_pago,cta_banco,porcentaje,monto} |
| 48 | Factura anticipo | POST | `/facturas` | CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social,direccion}, 1 |
| 49 | Factura descuento global | POST | `/facturas` | CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 50 | Factura descuento por línea | POST | `/facturas` | CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 51 | Factura descuento que NO afecta base | POST | `/facturas` | CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 52 | Factura ICBPER (bolsas plásticas) | POST | `/facturas` | CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 53 | Factura ISC (impuesto selectivo) | POST | `/facturas` | CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 54 | Factura IVAP (arroz pilado) | POST | `/facturas` | CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 55 | Factura + Guía de Remisión | POST | `/facturas` | CT; K; S | tipo_documento, serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 56 | Factura compleja (todos los campos) | POST | `/facturas` | CT; K; S | tipo_documento, serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 57 | Factura ICBPER + ítem gratuito | POST | `/facturas` | CT; K; S | tipo_documento, serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 58 | Factura con contingencia (modo offline) | POST | `/facturas` | CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, enviar_automatico, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 59 | Factura envío manual (enviar_automatico=false) | POST | `/facturas` | A; CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, forma_pago, cliente{tipo_doc,num_doc,razon_social}, 1, enviar_automatico |
| 60 | Enviar a SUNAT (manual) | POST | `/facturas/{{id_factura}}/enviar` | A; K; S | — |
| 61 | Reenviar a SUNAT (alias) | POST | `/facturas/1/reenviar` | A; K; S | — |
| 62 | Registrar pago en factura | POST | `/facturas/1/pagos` | A; CT; K; S | monto, fecha, metodo, referencia, observacion |
| 63 | Registrar pago parcial | POST | `/facturas/1/pagos` | A; CT; K; S | monto, fecha, metodo |
| 64 | Listar facturas | GET | `/facturas?sucursal_id=2&por_pagina=15` | A; K; S | — |
| 65 | Listar facturas con items y pagos | GET | `/facturas?con=items,payments&por_pagina=15` | A; K; S | — |
| 66 | Filtrar facturas rechazadas | GET | `/facturas?sunat_status=rechazado` | A; K; S | — |
| 67 | Buscar factura por correlativo | GET | `/facturas?serie=F001&correlativo=2` | A; K; S | — |
| 68 | Ver factura por ID | GET | `/facturas/{{id_factura}}` | A; K; S | — |
| 69 | Actualizar factura (corregir y reenviar) | PUT | `/facturas/1` | A; CT; K; S | cliente{tipo_doc,num_doc,razon_social}, 1 |
| 70 | Descargar XML | GET | `/facturas/{{id_factura}}/xml` | A; K; S | — |
| 71 | Descargar CDR (ZIP de SUNAT) | GET | `/facturas/{{id_factura}}/cdr` | A; K; S | — |
| 72 | Descargar PDF (A4) | GET | `/facturas/{{id_factura}}/pdf?formato=a4` | A; K; S | — |
| 73 | Descargar PDF (A5) | GET | `/facturas/{{id_factura}}/pdf?format=a5` | A; K; S | — |
| 74 | Descargar PDF (Ticket 80mm) | GET | `/facturas/{{id_factura}}/pdf?format=ticket-80` | A; K; S | — |
| 75 | Descargar PDF (Ticket 58mm) | GET | `/facturas/{{id_factura}}/pdf?format=ticket-58` | A; K; S | — |
| 76 | Listar pagos de la factura | GET | `/facturas/1/pagos` | A; K; S | — |
| 77 | Eliminar pago | DELETE | `/facturas/1/pagos/1` | A; K; S | — |

#### 05. Boletas (Tipo 03)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 78 | Boleta básica (DNI, contado) | POST | `/boletas` | A; CT; K; S | serie, fecha_emision, tipo_moneda, forma_pago, cliente{tipo_doc,num_doc,razon_social}, 1, solo_registro |
| 79 | Boleta con codigo productos sunat | POST | `/boletas` | A; CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social,direccion}, 1, solo_registro |
| 80 | Boleta consumidor final (sin DNI) | POST | `/boletas` | A; CT; K; S | serie, fecha_emision, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 81 | Boleta NRUS (tipo_operacion 0113 — auto) | POST | `/boletas` | A; CT; K; S | serie, fecha_emision, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 82 | Boleta MYPE Restaurantes (10.5% en 2026) | POST | `/boletas` | A; CT; K; S | serie, fecha_emision, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 83 | Boleta solo registro (envío vía resumen) | POST | `/boletas` | A; CT; K; S | serie, fecha_emision, cliente{tipo_doc,num_doc,razon_social}, 1, solo_registro |
| 84 | Boleta envío manual (enviar_automatico=false) | POST | `/boletas` | A; CT; K; S | serie, fecha_emision, cliente{tipo_doc,num_doc,razon_social}, 1, enviar_automatico |
| 85 | Boleta con anticipo | POST | `/boletas` | A; CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social,direccion}, 1 |
| 86 | Listar boletas | GET | `/boletas?por_pagina=15` | A; K; S | — |
| 87 | Listar boletas pendientes (para resumen) | GET | `/boletas?sunat_status=pendiente` | A; K; S | — |
| 88 | Ver boleta por ID | GET | `/boletas/{{id_boleta}}` | A; K; S | — |
| 89 | Actualizar boleta | PUT | `/boletas/1` | A; CT; K; S | cliente{tipo_doc,num_doc,razon_social} |
| 90 | Eliminar boleta (solo si no aceptada) | DELETE | `/boletas/1` | A; K; S | — |
| 91 | Descargar XML | GET | `/boletas/{{id_boleta}}/xml` | A; K; S | — |
| 92 | Descargar CDR | GET | `/boletas/72/cdr` | A; K; S | — |
| 93 | Descargar PDF A4 | GET | `/boletas/{{id_boleta}}/pdf?format=a4` | A; K; S | — |
| 94 | Descargar PDF Ticket 80mm | GET | `/boletas/{{id_boleta}}/pdf?format=ticket-80` | A; K; S | — |
| 95 | Enviar a SUNAT (manual) | POST | `/boletas/15/enviar` | A; K; S | — |
| 96 | Reenviar a SUNAT | POST | `/boletas/1/reenviar` | A; K; S | — |
| 97 | Registrar pago en boleta | POST | `/boletas/1/pagos` | A; CT; K; S | monto, fecha, metodo |
| 98 | Listar pagos de boleta | GET | `/boletas/1/pagos` | A; K; S | — |
| 99 | Eliminar pago | DELETE | `/boletas/1/pagos/1` | A; K; S | — |

#### 06. Notas de Crédito (Tipo 07)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 100 | NC - Anulación total de factura | POST | `/notas-credito` | CT; K; S | serie, fecha_emision, doc_afectado_tipo, doc_afectado_serie, doc_afectado_correlativo, cod_motivo, des_motivo, cliente{tipo_doc,num_doc,razon_social,direccion}, 1 |
| 101 | NC - Descuento parcial | POST | `/notas-credito` | CT; K; S | serie, fecha_emision, doc_afectado_tipo, doc_afectado_serie, doc_afectado_correlativo, cod_motivo, des_motivo, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 102 | NC - Corrección de precio / devolución parcial | POST | `/notas-credito` | CT; K; S | serie, fecha_emision, doc_afectado_tipo, doc_afectado_serie, doc_afectado_correlativo, cod_motivo, des_motivo, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 103 | NC - En dólares (USD) | POST | `/notas-credito` | CT; K; S | serie, fecha_emision, tipo_moneda, doc_afectado_tipo, doc_afectado_serie, doc_afectado_correlativo, cod_motivo, des_motivo, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 104 | NC - Anulación de boleta | POST | `/notas-credito` | CT; K; S | serie, fecha_emision, doc_afectado_tipo, doc_afectado_serie, doc_afectado_correlativo, cod_motivo, des_motivo, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 105 | NC envío manual (enviar_automatico=false) | POST | `/notas-credito` | A; CT; K; S | serie, fecha_emision, tipo_moneda, cliente{tipo_doc,num_doc,razon_social}, doc_afectado_tipo, doc_afectado_serie, doc_afectado_correlativo, cod_motivo, des_motivo, 1, enviar_automatico |
| 106 | Enviar a SUNAT (manual) | POST | `/notas-credito/1/enviar` | A; K; S | — |
| 107 | Reenviar a SUNAT | POST | `/notas-credito/1/reenviar` | A; K; S | — |
| 108 | Listar notas de crédito | GET | `/notas-credito?por_pagina=15` | A; K; S | — |
| 109 | Filtrar NC por documento afectado | GET | `/notas-credito?serie=FC01` | A; K; S | — |
| 110 | Ver NC por ID | GET | `/notas-credito/{{id_NC}}` | A; K; S | — |
| 111 | Actualizar NC rechazada | PUT | `/notas-credito/1` | A; CT; K; S | des_motivo |
| 112 | Descargar XML | GET | `/notas-credito/1/xml` | A; K; S | — |
| 113 | Descargar CDR | GET | `/notas-credito/3/cdr` | A; K; S | — |
| 114 | Descargar PDF | GET | `/notas-credito/{{id_NC}}/pdf?format=a4` | A; K; S | — |

#### 07. Notas de Débito (Tipo 08)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 115 | ND — Intereses por mora | POST | `/notas-debito` | CT; K; S | serie, fecha_emision, doc_afectado_tipo, doc_afectado_serie, doc_afectado_correlativo, cod_motivo, des_motivo, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 116 | ND — Aumento de valor | POST | `/notas-debito` | CT; K; S | serie, fecha_emision, doc_afectado_tipo, doc_afectado_serie, doc_afectado_correlativo, cod_motivo, des_motivo, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 117 | ND — Penalidad | POST | `/notas-debito` | CT; K; S | serie, fecha_emision, doc_afectado_tipo, doc_afectado_serie, doc_afectado_correlativo, cod_motivo, des_motivo, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 118 | ND — Sobre boleta | POST | `/notas-debito` | CT; K; S | serie, fecha_emision, doc_afectado_tipo, doc_afectado_serie, doc_afectado_correlativo, cod_motivo, des_motivo, cliente{tipo_doc,num_doc,razon_social}, 1 |
| 119 | ND envío manual | POST | `/notas-debito` | A; CT; K; S | serie, fecha_emision, tipo_moneda, cliente{tipo_doc,num_doc,razon_social}, doc_afectado_tipo, doc_afectado_serie, doc_afectado_correlativo, cod_motivo, des_motivo, 1, enviar_automatico |
| 120 | Enviar a SUNAT (manual) | POST | `/notas-debito/1/enviar` | A; K; S | — |
| 121 | Reenviar a SUNAT | POST | `/notas-debito/1/reenviar` | A; K; S | — |
| 122 | Listar notas de débito | GET | `/notas-debito?por_pagina=15` | A; K; S | — |
| 123 | Ver ND por ID | GET | `/notas-debito/1` | A; K; S | — |
| 124 | Actualizar ND rechazada | PUT | `/notas-debito/1` | A; CT; K; S | des_motivo |
| 125 | Descargar XML | GET | `/notas-debito/1/xml` | A; K; S | — |
| 126 | Descargar CDR | GET | `/notas-debito/1/cdr` | A; K; S | — |
| 127 | Descargar PDF | GET | `/notas-debito/{{id_ND}}/pdf?format=a4` | A; K; S | — |

#### 08. Resumen Diario (RC)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 128 | Crear resumen diario (envío de boletas pendientes) | POST | `/resumenes` | A; CT; K; S | fecha_resumen |
| 129 | Crear resumen ANULACIÓN (anular boletas aceptadas) | POST | `/resumenes` | A; CT; K; S | fecha_resumen, 1 |
| 130 | Resumen envío manual (enviar_automatico=false) | POST | `/resumenes` | A; CT; K; S | fecha_resumen, enviar_automatico |
| 131 | Listar resúmenes | GET | `/resumenes?por_pagina=15` | A; K; S | — |
| 132 | Filtrar por mes | GET | `/resumenes?mes=2026-04` | A; K; S | — |
| 133 | Filtrar solo anulaciones | GET | `/resumenes?tipo=anulacion` | A; K; S | — |
| 134 | Consultar estado SUNAT | GET | `/resumenes/{{id_resumen}}/estado` | A; K; S | — |
| 135 | Descargar XML | GET | `/resumenes/{{id_resumen}}/xml` | A; K; S | — |
| 136 | Descargar CDR | GET | `/resumenes/1/cdr` | A; K; S | — |
| 137 | Enviar a SUNAT (manual) | POST | `/resumenes/1/enviar` | A; K; S | — |

#### 09. Comunicación de Baja (RA / RR)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 138 | Anular factura (RA estándar) | POST | `/anulaciones` | A; CT; K; S | fecha_generacion, fecha_comunicacion, 1 |
| 139 | Anular múltiples documentos en lote | POST | `/anulaciones` | A; CT; K; S | fecha_generacion, fecha_comunicacion, 1 |
| 140 | Anular nota de crédito | POST | `/anulaciones` | A; CT; K; S | fecha_generacion, 1 |
| 141 | Anular envío manual (enviar_automatico=false) | POST | `/anulaciones` | A; CT; K; S | fecha_generacion, 1, enviar_automatico |
| 142 | Listar comunicaciones de baja | GET | `/anulaciones?por_pagina=15` | A; K; S | — |
| 143 | Filtrar por estado | GET | `/anulaciones?estado=aceptado` | A; K; S | — |
| 144 | Ver comunicación por ID | GET | `/anulaciones/{{id}}` | A; K; S | — |
| 145 | Consultar estado en SUNAT | GET | `/anulaciones/{{id_anular}}/estado` | A; K; S | — |
| 146 | Descargar XML de la anulación | GET | `/anulaciones/{{id_anular}}/xml` | A; K; S | — |
| 147 | Descargar CDR de la anulación | GET | `/anulaciones/{{id_anular}}/cdr` | A; K; S | — |
| 148 | Enviar a SUNAT (manual) | POST | `/anulaciones/1/enviar` | A; K; S | — |

#### 10. Guía Remisión Remitente (Tipo 09)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 149 | GRR — Transporte público | POST | `/guias-remision` | CT; K; S | serie, fecha_emision, destinatario{tipo_doc,num_doc,razon_social}, cod_traslado, mod_traslado, fecha_traslado, fecha_de_entrega_al_transportista, peso_total, und_peso_total, partida_ubigeo, partida_direccion, llegada_ubigeo, llegada_direccion, transportista{tipo_doc,num_doc,razon_social,nro_mtc}, 1 |
| 150 | GRR — Transporte privado | POST | `/guias-remision` | CT; K; S | serie, fecha_emision, destinatario{tipo_doc,num_doc,razon_social}, cod_traslado, mod_traslado, fecha_traslado, peso_total, und_peso_total, num_bultos, partida_ubigeo, partida_direccion, llegada_ubigeo, llegada_direccion, vehiculo{placa}, 1, conductor{tipo,tipo_doc,num_doc,nombres,apellidos,licencia} |
| 151 | GRR — Vehículo menor M1/L | POST | `/guias-remision` | CT; K; S | serie, fecha_emision, destinatario{tipo_doc,num_doc,razon_social}, cod_traslado, mod_traslado, fecha_traslado, peso_total, und_peso_total, indicadores, partida_ubigeo, partida_direccion, llegada_ubigeo, llegada_direccion, 1 |
| 152 | GRR — Entre establecimientos propios | POST | `/guias-remision` | CT; K; S | serie, fecha_emision, destinatario{tipo_doc,num_doc,razon_social}, cod_traslado, mod_traslado, indicadores, fecha_traslado, peso_total, und_peso_total, num_bultos, partida_ubigeo, partida_direccion, partida_ruc, partida_cod_local, llegada_ubigeo, llegada_direccion, llegada_ruc, llegada_cod_local, 1 |
| 153 | GRR — Múltiples conductores | POST | `/guias-remision` | CT; K; S | serie, fecha_emision, destinatario{tipo_doc,num_doc,razon_social}, cod_traslado, mod_traslado, fecha_traslado, peso_total, und_peso_total, num_bultos, partida_ubigeo, partida_direccion, llegada_ubigeo, llegada_direccion, vehiculo{placa}, 1 |
| 154 | GRR envío manual (enviar_automatico=false) | POST | `/guias-remision` | A; CT; K; S | serie, fecha_emision, destinatario{tipo_doc,num_doc,razon_social}, cod_traslado, mod_traslado, fecha_traslado, peso_total, und_peso_total, partida_ubigeo, partida_direccion, llegada_ubigeo, llegada_direccion, vehiculo{placa}, conductor{tipo_doc,num_doc,nombres,apellidos,licencia}, 1, enviar_automatico |
| 155 | Enviar a SUNAT (manual) | POST | `/guias-remision/1/enviar` | A; K; S | — |
| 156 | Listar guías de remisión | GET | `/guias-remision?por_pagina=15` | A; K; S | — |
| 157 | Filtrar guías por estado | GET | `/guias-remision?sunat_status=aceptado` | A; K; S | — |
| 158 | Actualizar guía rechazada | PUT | `/guias-remision/1` | A; CT; K; S | transportista{tipo_doc,num_doc,razon_social} |
| 159 | Descargar XML | GET | `/guias-remision/1/xml` | A; K; S | — |
| 160 | Descargar CDR | GET | `/guias-remision/1/cdr` | A; K; S | — |
| 161 | Descargar PDF | GET | `/guias-remision/{{id_guia}}/pdf?format=a4` | A; K; S | — |
| 162 | Consultar estado SUNAT (vía ticket) | GET | `/guias-remision/{{id_guia}}/estado` | A; K; S | — |

#### 11. Guía Remisión Transportista (Tipo 31)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 163 | GRT — Transporte estándar (atajo) | POST | `/guias-remision-transportista` | A; CT; K; S | serie, fecha_emision, remitente{tipo_doc,num_doc,razon_social}, destinatario{tipo_doc,num_doc,razon_social}, doc_relacionado{tipo_codigo,numero,ruc_emisor}, cod_traslado, mod_traslado, fecha_traslado, peso_total, und_peso_total, num_bultos, partida_ubigeo, partida_direccion, llegada_ubigeo, llegada_direccion, vehiculo{placa,nro_circulacion}, 1 |
| 164 | GRT — Vía endpoint general (tipo_documento=31) | POST | `/guias-remision` | A; CT; K; S | tipo_documento, serie, fecha_emision, remitente{tipo_doc,num_doc,razon_social}, destinatario{tipo_doc,num_doc,razon_social}, doc_relacionado{tipo_codigo,numero,ruc_emisor}, cod_traslado, mod_traslado, fecha_traslado, peso_total, und_peso_total, partida_ubigeo, partida_direccion, llegada_ubigeo, llegada_direccion, vehiculo{placa,nro_circulacion}, 1 |
| 165 | GRT — Subcontratado (yo subcontraté a otro transportista) | POST | `/guias-remision-transportista` | A; CT; K; S | serie, fecha_emision, remitente{tipo_doc,num_doc,razon_social}, destinatario{tipo_doc,num_doc,razon_social}, doc_relacionado{tipo_codigo,numero,ruc_emisor}, cod_traslado, mod_traslado, fecha_traslado, peso_total, und_peso_total, partida_ubigeo, partida_direccion, llegada_ubigeo, llegada_direccion, vehiculo{placa}, 1, datos_subcontratador{tipo_doc,num_doc,razon_social}, datos_pagador_flete{tipo} |
| 166 | GRT — Pagador del flete = tercero | POST | `/guias-remision-transportista` | A; CT; K; S | serie, fecha_emision, remitente{tipo_doc,num_doc,razon_social}, destinatario{tipo_doc,num_doc,razon_social}, doc_relacionado{tipo_codigo,numero,ruc_emisor}, cod_traslado, mod_traslado, fecha_traslado, peso_total, und_peso_total, partida_ubigeo, partida_direccion, llegada_ubigeo, llegada_direccion, vehiculo{placa}, 1, datos_pagador_flete{tipo,tipo_doc,num_doc,razon_social} |
| 167 | GRT — Múltiples conductores y vehículos secundarios | POST | `/guias-remision-transportista` | A; CT; K; S | serie, fecha_emision, remitente{tipo_doc,num_doc,razon_social}, destinatario{tipo_doc,num_doc,razon_social}, doc_relacionado{tipo_codigo,numero,ruc_emisor}, cod_traslado, mod_traslado, fecha_traslado, peso_total, und_peso_total, partida_ubigeo, partida_direccion, llegada_ubigeo, llegada_direccion, vehiculo{placa,nro_circulacion}, 1 |
| 168 | GRT — DAM (importación/exportación) | POST | `/guias-remision-transportista` | A; CT; K; S | serie, fecha_emision, remitente{tipo_doc,num_doc,razon_social}, destinatario{tipo_doc,num_doc,razon_social}, doc_relacionado{tipo_codigo,numero}, cod_traslado, mod_traslado, fecha_traslado, peso_total, und_peso_total, partida_ubigeo, partida_direccion, llegada_ubigeo, llegada_direccion, vehiculo{placa}, 1 |
| 169 | GRT — Envío manual | POST | `/guias-remision-transportista` | A; CT; K; S | serie, fecha_emision, remitente{tipo_doc,num_doc,razon_social}, destinatario{tipo_doc,num_doc,razon_social}, doc_relacionado{tipo_codigo,numero,ruc_emisor}, cod_traslado, mod_traslado, fecha_traslado, peso_total, und_peso_total, partida_ubigeo, partida_direccion, llegada_ubigeo, llegada_direccion, vehiculo{placa}, 1, enviar_automatico |
| 170 | Listar GRT | GET | `/guias-remision?tipo_documento=31` | A; K; S | — |
| 171 | Ver GRT por ID | GET | `/guias-remision/1` | A; K; S | — |
| 172 | Descargar XML | GET | `/guias-remision/1/xml` | A; K; S | — |
| 173 | Descargar CDR | GET | `/guias-remision/1/cdr` | A; K; S | — |
| 174 | Descargar PDF | GET | `/guias-remision/{{id_guia_t}}/pdf?format=a4` | A; K; S | — |
| 175 | Consultar estado SUNAT | GET | `/guias-remision/{{id_guia_t}}/estado` | A; K; S | — |
| 176 | Enviar a SUNAT (manual) | POST | `/guias-remision/1/enviar` | A; K; S | — |

#### 12. Envío Manual a SUNAT

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 177 | POST /facturas/{id}/enviar | POST | `/facturas/1/enviar` | A; K; S | — |
| 178 | POST /boletas/{id}/enviar | POST | `/boletas/1/enviar` | A; K; S | — |
| 179 | POST /notas-credito/{id}/enviar | POST | `/notas-credito/1/enviar` | A; K; S | — |
| 180 | POST /notas-debito/{id}/enviar | POST | `/notas-debito/1/enviar` | A; K; S | — |
| 181 | POST /guias-remision/{id}/enviar | POST | `/guias-remision/1/enviar` | A; K; S | — |
| 182 | POST /resumenes/{id}/enviar | POST | `/resumenes/1/enviar` | A; K; S | — |
| 183 | POST /anulaciones/{id}/enviar | POST | `/anulaciones/1/enviar` | A; K; S | — |
| 184 | POST /retenciones/{id}/enviar | POST | `/retenciones/1/enviar` | A; K; S | — |
| 185 | POST /percepciones/{id}/enviar | POST | `/percepciones/1/enviar` | A; K; S | — |

#### 13. Actualizar documentos

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 186 | Corregir factura rechazada (cliente) | PUT | `/facturas/1` | A; CT; K; S | cliente{tipo_doc,num_doc,razon_social,direccion} |
| 187 | Corregir factura rechazada (items) | PUT | `/facturas/1` | A; CT; K; S | 1 |
| 188 | Corregir boleta rechazada | PUT | `/boletas/1` | A; CT; K; S | cliente{tipo_doc,num_doc,razon_social} |
| 189 | Corregir nota de crédito | PUT | `/notas-credito/1` | A; CT; K; S | cod_motivo, des_motivo |
| 190 | Corregir nota de débito | PUT | `/notas-debito/1` | A; CT; K; S | des_motivo |
| 191 | Corregir guía de remisión | PUT | `/guias-remision/1` | A; CT; K; S | serie, fecha_emision, destinatario{tipo_doc,num_doc,razon_social}, cod_traslado, mod_traslado, fecha_traslado, fecha_de_entrega_al_transportista, peso_total, und_peso_total, partida_ubigeo, partida_direccion, llegada_ubigeo, llegada_direccion, transportista{tipo_doc,num_doc,razon_social,placa}, 1 |

#### 14. Consultar CPE / CDR

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 192 | Consultar CPE — venta propia | GET | `/consultar-cpe?ruc_emisor=20100000001&tipo_doc=01&serie=F001&correlativo=1&fecha_emision=2026-04-19&monto_total=118.00` | A; K; S | — |
| 193 | Consultar CPE — factura de proveedor | GET | `/consultar-cpe?ruc_emisor=20987654321&tipo_doc=01&serie=F001&correlativo=100&fecha_emision=2026-04-15&monto_total=590.00` | A; K; S | — |
| 194 | Consultar CPE — boleta | GET | `/consultar-cpe?ruc_emisor=20100000001&tipo_doc=03&serie=B001&correlativo=10&fecha_emision=2026-04-19&monto_total=35.00` | A; K; S | — |
| 195 | Consultar CPE — nota de crédito | GET | `/consultar-cpe?ruc_emisor=20100000001&tipo_doc=07&serie=FC01&correlativo=1&fecha_emision=2026-04-19&monto_total=118.00` | A; K; S | — |
| 196 | Consultar CDR (estado vía CDR) | POST | `/consultar-cdr` | A; CT; K; S | ruc_emisor, tipo_doc, serie, correlativo |
| 197 | Exportar comprobantes | GET | `/comprobantes/exportar-zip?fecha_desde=2026-05-01&fecha_hasta=2026-05-31&documentos=facturas` | K; S | (GET con body ignorado) ruc_emisor, tipo_doc, serie, correlativo |

#### 15. Panel de Control

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 198 | Panel completo (vista general) | GET | `/panel` | A; K; S | — |
| 199 | Indicadores (hoy / semana / mes / año) | GET | `/panel/indicadores` | A; K; S | — |
| 200 | Estado SUNAT (breakdown de aceptados/rechazados) | GET | `/panel/estado-sunat` | A; K; S | — |
| 201 | Cobranzas (aging de cuentas por cobrar) | GET | `/panel/cobranzas` | A; K; S | — |
| 202 | Ventas mensuales (gráfico 12 meses) | GET | `/panel/ventas-mensuales` | A; K; S | — |
| 203 | Ranking por sucursal | GET | `/panel/por-sucursal` | A; K; S | — |
| 204 | Desglose por moneda (PEN / USD) | GET | `/panel/por-moneda` | A; K; S | — |
| 205 | Clientes (top + nuevos + recurrentes) | GET | `/panel/clientes` | A; K; S | — |
| 206 | Productos (top ventas / cantidad) | GET | `/panel/productos` | A; K; S | — |
| 207 | Documentos recientes (feed últimos 20) | GET | `/panel/documentos-recientes` | A; K; S | — |
| 208 | Alertas del sistema | GET | `/panel/alertas` | A; K; S | — |

#### 16. Reportes

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 209 | Registro de Ventas (mensual SUNAT) | GET | `/reportes/registro-ventas?fecha_desde=2026-04-01&fecha_hasta=2026-04-30` | A; K; S | — |
| 210 | Ventas consolidado | GET | `/reportes/ventas-consolidado?fecha_desde=2026-04-01&fecha_hasta=2026-04-30` | A; K; S | — |
| 211 | Reporte de notas (NC + ND) | GET | `/reportes/notas?fecha_desde=2026-04-01&fecha_hasta=2026-04-30` | A; K; S | — |
| 212 | Cobranzas (aging) | GET | `/reportes/cobranzas?fecha_desde=2026-04-01&fecha_hasta=2026-04-30` | A; K; S | — |
| 213 | Documentos internos (cotizaciones + notas de venta) **[EXCLUIR]** | GET | `/reportes/documentos-internos?fecha_desde=2026-04-01&fecha_hasta=2026-04-30` | A; K; S | — |
| 214 | Por cliente (detalle de un cliente) | GET | `/reportes/por-cliente?fecha_desde=2026-04-01&fecha_hasta=2026-04-30&client_num_doc=20512345678` | A; K; S | — |
| 215 | Por sucursal (ranking) | GET | `/reportes/por-sucursal?fecha_desde=2026-04-01&fecha_hasta=2026-04-30` | A; K; S | — |

#### 17. SIRE (Registro de Compras)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|

#### 17. SIRE (Registro de Compras) / 0. Activación

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 216 | Activar SIRE | POST | `/sire/activar` | A; CT; K; S | sol_user, sol_pass, client_id, client_secret |
| 217 | Desactivar SIRE | POST | `/sire/desactivar` | A; K; S | — |

#### 17. SIRE (Registro de Compras) / 1. Periodos (5.33)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 218 | Listar periodos RCE disponibles | GET | `/sire/periodos` | A; K; S | — |

#### 17. SIRE (Registro de Compras) / 2. RCE Flujo Principal

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 219 | Descargar propuesta (5.34) | GET | `/sire/rce/{{periodo}}/propuesta` | A; K; S | — |
| 220 | Descargar resumen (5.35) | GET | `/sire/rce/{{periodo}}/resumen` | A; K; S | — |
| 221 | Descargar constancia PDF (5.49) | GET | `/sire/rce/constancia?periodo={{periodo}}` | A; K; S | — |
| 222 | Aceptar propuesta (5.2) | POST | `/sire/rce/{{periodo}}/aceptar-propuesta` | A; CT; K; S | `[]` (vacío) |
| 223 | Registrar preliminar (5.4) | POST | `/sire/rce/{{periodo}}/registrar-preliminar` | A; CT; K; S | `[]` (vacío) |

#### 17. SIRE (Registro de Compras) / 3. Comprobantes locales

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 224 | Listar comprobantes del periodo | GET | `/sire/rce/{{periodo}}/comprobantes` | A; K; S | — |
| 225 | Ver comprobante específico | GET | `/sire/rce/{{periodo}}/comprobantes/1` | A; K; S | — |

#### 17. SIRE (Registro de Compras) / 4. Tickets

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 226 | Listar tickets pendientes | GET | `/sire/tickets` | A; K; S | — |
| 227 | Ver ticket específico | GET | `/sire/tickets/{{num_ticket}}` | A; K; S | — |
| 228 | Refrescar estado del ticket | POST | `/sire/tickets/{{num_ticket}}/refrescar` | A; K; S | — |
| 229 | Descargar archivo del ticket (ZIP) | GET | `/sire/tickets/{{num_ticket}}/archivo` | A; K; S | — |

#### 17. SIRE (Registro de Compras) / 5. Uploads TUS

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 230 | Reemplazar propuesta (5.3) — JSON | POST | `/sire/rce/{{periodo}}/reemplazar-propuesta` | A; CT; K; S | filas |
| 231 | Reemplazar propuesta (5.3) — Multipart (TXT) | POST | `/sire/rce/{{periodo}}/reemplazar-propuesta` | A; K; S | multipart: archivo(file) |
| 232 | Cargar No Domiciliados (5.5) | POST | `/sire/rce/{{periodo}}/no-domiciliados` | A; CT; K; S | filas |
| 233 | Complementar propuesta (5.6) | POST | `/sire/rce/{{periodo}}/complementar-propuesta` | A; CT; K; S | filas |

#### 17. SIRE (Registro de Compras) / 6. Ajustes Posteriores

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 234 | Cargar ajustes (5.18/21/24/27) | POST | `/sire/rce/{{periodo}}/ajustes-posteriores/actual/cargar` | A; CT; K; S | filas |
| 235 | Enviar ajustes a SUNAT (5.19/22/25/28) | POST | `/sire/rce/{{periodo}}/ajustes-posteriores/actual/enviar` | A; CT; K; S | `[]` (vacío) |
| 236 | Descargar ajustes (5.45-5.48) | GET | `/sire/rce/{{periodo}}/ajustes-posteriores/actual/descargar` | A; K; S | — |
| 237 | Eliminar comprobantes del ajuste (5.20/23/26/29) | POST | `/sire/rce/{{periodo}}/ajustes-posteriores/actual/eliminar` | A; CT; K; S | filas |

#### 17. SIRE (Registro de Compras) / 7. Reconciliación

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 238 | Reconciliar (síncrono) | GET | `/sire/rce/{{periodo}}/reconciliar` | A; K; S | — |
| 239 | Reconciliar async (encola job) | POST | `/sire/rce/{{periodo}}/reconciliar-async` | A; K; S | — |
| 240 | Historial de reconciliaciones | GET | `/sire/rce/{{periodo}}/reconciliaciones` | A; K; S | — |
| 241 | Ver reconciliación específica | GET | `/sire/rce/reconciliaciones/1` | A; K; S | — |

#### 18. Retenciones (Tipo 20)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 242 | Crear retención (régimen general 3%) | POST | `/retenciones` | A; CT; K; S | serie, fecha_emision, regimen, tasa, proveedor{tipo_doc,num_doc,razon_social}, 1 |
| 243 | Crear retención múltiples documentos | POST | `/retenciones` | A; CT; K; S | serie, fecha_emision, cod_local, proveedor{tipo_doc,num_doc,razon_social}, regimen, tasa, 1 |
| 244 | Retención envío manual | POST | `/retenciones` | A; CT; K; S | serie, fecha_emision, cod_local, proveedor{tipo_doc,num_doc,razon_social}, regimen, tasa, 1, enviar_automatico |
| 245 | Listar retenciones | GET | `/retenciones?per_page=15` | A; K; S | — |
| 246 | Filtrar retenciones por proveedor | GET | `/retenciones?proveedor_num_doc=20987654321` | A; K; S | — |
| 247 | Ver retención por ID | GET | `/retenciones/3` | A; K; S | — |
| 248 | Descargar XML | GET | `/retenciones/2/xml` | A; K; S | — |
| 249 | Descargar CDR | GET | `/retenciones/2/cdr` | A; K; S | — |
| 250 | Descargar PDF a4 | GET | `/retenciones/3/pdf?format=a4` | A; K; S | — |
| 251 | Descargar PDF a5 | GET | `/retenciones/2/pdf?format=a5` | A; K; S | — |
| 252 | Descargar PDF 58 MM | GET | `/retenciones/2/pdf?format=ticket-58` | A; K; S | — |
| 253 | Descargar PDF 80 MM | GET | `/retenciones/2/pdf?format=ticket-80` | A; K; S | — |
| 254 | Enviar a SUNAT (manual) | POST | `/retenciones/1/enviar` | A; K; S | — |

#### 19. Percepciones (Tipo 40)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 255 | 0.5% — Entre agentes de percepción | POST | `/facturas` | CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social,direccion}, percepcion{cod_regimen,porcentaje,base,monto}, 1 |
| 256 | 1% — Venta de bienes (combustible, consumo masivo) | POST | `/facturas` | CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social}, percepcion{cod_regimen,porcentaje,base,monto}, 1 |
| 257 | 2% — Sujeto no designado / consumidor final | POST | `/facturas` | CT; K; S | serie, fecha_emision, tipo_operacion, tipo_moneda, cliente{tipo_doc,num_doc,razon_social,direccion}, percepcion{tipo_operacion,cod_regimen,porcentaje,base,monto}, 1 |
| 258 | Crear percepción venta interna (2%) | POST | `/percepciones` | A; CT; K; S | serie, fecha_emision, cod_local, cliente{tipo_doc,num_doc,razon_social,direccion}, regimen, tasa, observacion, 1 |
| 259 | Crear percepción combustible (1%) | POST | `/percepciones` | A; CT; K; S | serie, fecha_emision, cod_local, cliente{tipo_doc,num_doc,razon_social}, regimen, tasa, observacion, 1 |
| 260 | Percepción envío manual | POST | `/percepciones` | A; CT; K; S | serie, fecha_emision, cod_local, cliente{tipo_doc,num_doc,razon_social}, regimen, tasa, 1, enviar_automatico |
| 261 | Enviar a SUNAT (manual) | POST | `/percepciones/1/enviar` | A; K; S | — |
| 262 | Listar percepciones | GET | `/percepciones?per_page=15` | A; K; S | — |
| 263 | Filtrar percepciones por cliente | GET | `/percepciones?cliente_num_doc=20987654321` | A; K; S | — |
| 264 | Ver percepción por ID | GET | `/percepciones/1` | A; K; S | — |
| 265 | Descargar XML | GET | `/percepciones/1/xml` | A; K; S | — |
| 266 | Descargar CDR | GET | `/percepciones/1/cdr` | A; K; S | — |

#### 20. Reversión (RR)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 267 | Reversión de retención | POST | `/reversiones` | A; CT; K; S | fecha_generacion, fecha_comunicacion, 1 |
| 268 | Reversión de percepción | POST | `/reversiones` | A; CT; K; S | fecha_generacion, 1 |
| 269 | Reversión múltiple | POST | `/reversiones` | A; CT; K; S | fecha_generacion, 1 |
| 270 | Reversión envío manual | POST | `/reversiones` | A; CT; K; S | fecha_generacion, 1, enviar_automatico |

#### 21. Cotizaciones (interno)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 271 | Crear cotización **[EXCLUIR]** | POST | `/cotizaciones` | A; CT; K; S | fecha_emision, fecha_validez, tipo_moneda, cliente{tipo_doc,num_doc,razon_social,email}, 1, observacion |
| 272 | Listar cotizaciones **[EXCLUIR]** | GET | `/cotizaciones?por_pagina=15` | A; K; S | — |
| 273 | Filtrar por estado **[EXCLUIR]** | GET | `/cotizaciones?estado=aceptada` | A; K; S | — |
| 274 | Ver cotización por ID **[EXCLUIR]** | GET | `/cotizaciones/1` | A; K; S | — |
| 275 | Actualizar cotización **[EXCLUIR]** | PUT | `/cotizaciones/1` | A; CT; K; S | fecha_validez, observacion |
| 276 | Cambiar estado de cotización **[EXCLUIR]** | PUT | `/cotizaciones/1/estado` | A; CT; K; S | estado |
| 277 | Descargar PDF **[EXCLUIR]** | GET | `/cotizaciones/1/pdf?format=a4` | A; K; S | — |

#### 22. Notas de Venta (interno)

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 278 | Crear nota de venta **[EXCLUIR]** | POST | `/notas-venta` | A; CT; K; S | fecha_emision, tipo_moneda, forma_pago, cliente{tipo_doc,num_doc,razon_social}, 1, observacion |
| 279 | Listar notas de venta **[EXCLUIR]** | GET | `/notas-venta?por_pagina=15` | A; K; S | — |
| 280 | Ver nota de venta por ID **[EXCLUIR]** | GET | `/notas-venta/1` | A; K; S | — |
| 281 | Actualizar nota de venta **[EXCLUIR]** | PUT | `/notas-venta/1` | A; CT; K; S | observacion |
| 282 | Descargar PDF **[EXCLUIR]** | GET | `/notas-venta/1/pdf?format=a4` | A; K; S | — |
| 283 | Registrar pago en nota de venta **[EXCLUIR]** | POST | `/notas-venta/1/pagos` | A; CT; K; S | monto, fecha, metodo, referencia |
| 284 | Listar pagos de nota de venta **[EXCLUIR]** | GET | `/notas-venta/1/pagos` | A; K; S | — |
| 285 | Eliminar pago **[EXCLUIR]** | DELETE | `/notas-venta/1/pagos/1` | A; K; S | — |

#### 23. Catálogos: productos y ubigeo

| # | Nombre | Método | URL | Headers | Body (campos) |
|---|---|---|---|---|---|
| 286 | Ubigeo — Regiones (25 departamentos) | GET | `/catalogos/ubigeo/regiones` | A; CT; K; S | — |
| 287 | Ubigeo — Provincias por región | GET | `/catalogos/ubigeo/provincias?region_id=15` | A; CT; K; S | — |
| 288 | Ubigeo — Distritos por provincia | GET | `/catalogos/ubigeo/distritos?provincia_id=1501` | A; CT; K; S | — |
| 289 | Búsqueda por texto | GET | `/catalogos/producto-sunat?q=ropa` | A; CT; K; S | — |
| 290 | Búsqueda por código | GET | `/catalogos/producto-sunat?q=4321` | A; CT; K; S | — |
| 291 | Validar/detalle con jerarquía completa | GET | `/catalogos/producto-sunat/78181500` | A; CT; K; S | — |
| 292 | 56 segmentos | GET | `/catalogos/producto-sunat/segmentos` | A; CT; K; S | — |
| 293 | Familias del segmento | GET | `/catalogos/producto-sunat/familias?segmento=78` | A; CT; K; S | — |
| 294 | Clases de la familia | GET | `/catalogos/producto-sunat/clases?familia=7818` | A; CT; K; S | — |
| 295 | Productos de la clase | GET | `/catalogos/producto-sunat/productos?clase=781815` | A; CT; K; S | — |

### 1.4 Cruce colección ↔ `routes/api.php`

Método: se obtuvo el listado real de rutas con `php artisan route:list --path=api --json` (solo lectura; 224 líneas, incluye rutas de Horizon) y se comparó método + patrón de URI contra las 295 requests (sustituyendo `{{base_url}}` → `api/v1` y variables `{{x}}` → valor).

**Requests que apuntan a rutas inexistentes: NINGUNA.** Las 295 requests resuelven a una ruta existente (método + path).

Pero hay **desajustes semánticos** (la ruta existe, pero el parámetro o el nombre no corresponde):

| Request | Problema |
|---|---|
| 09 › Listar comunicaciones de baja `GET /anulaciones?por_pagina=15` | `VoidedController@index` lee `per_page`, no `por_pagina` → el parámetro se ignora (coincide por casualidad con el default 15). |
| 18 › Listar retenciones `?per_page=15`, 19 › Listar percepciones `?per_page=15` | Correcto para esos controladores (usan `per_page`), pero **inconsistente** con el resto de la API (`por_pagina`). Todas las respuestas devuelven `meta.por_pagina`. |
| 04 › Descargar PDF (A4) `?formato=a4` | Solo `InvoiceController@pdf` acepta `formato` (y `format`). Los demás controladores solo `format`. Valores: `a4`, `a5`, `ticket-80`, `ticket-58`. |
| **14 › Consultar CPE (4 requests)** `GET /consultar-cpe?…&fecha_emision=2026-04-19&monto_total=118.00` | **Fallan con 422**: `CpeConsultaController` exige `fecha_emision` con formato `dd/mm/yyyy` (regex `^\d{2}/\d{2}/\d{4}$`) y el parámetro se llama `monto` (no `monto_total`); `tipo_doc ∈ {01,03,04,07,08,R1,R7}`. Corregir la colección antes de usarla como base común. |
| **14 › Consultar CDR** `POST /consultar-cdr {ruc_emisor, tipo_doc, serie, correlativo}` | **Falla con 422**: `ConsultController@cdrStatus` exige `tipo_documento` (`in:01,03,07,08`), `serie` (size 4), `correlativo` (integer ≥1); `ruc_emisor`/`tipo_doc` no se leen. |
| **01 › PUT Empresa** `webhook_url` | `TenantController@update` (API) valida y lee **`url_webhook`** (lo mapea a la columna `webhook_url`); `webhook_url` en el body se ignora. (El panel admin sí usa `webhook_url`.) |
| 06 › Filtrar NC por documento afectado `?serie=FC01` | Filtra por la serie de la propia NC, no por documento afectado (nombre engañoso). |
| 14 › Exportar comprobantes `GET /comprobantes/exportar-zip?...` | Lleva un body JSON (copiado de consultar-cdr) que en GET se ignora; la descripción también está copiada de "Consultar CDR". Parámetros reales en query: `fecha_desde`, `fecha_hasta`, `documentos=facturas`. |
| 01 › "API key actual" (`GET /empresa/credenciales`) y "Genera uno nuevo…" (`POST /empresa/credenciales/regenerar`) | Su descripción está copiada de `buscar-documento` ("Consulta local + SUNAT/RENIEC… Params: tipo + numero") — no corresponde. |
| 01 › "GET Empresa (perfil) Copy" | Request duplicada. |
| 11 › Listar GRT `GET /guias-remision?tipo_documento=31` | No existe listado propio de GRT: es el listado general filtrado. |

**Rutas sin request en la colección** (excluyendo Horizon `horizon/api/*`, que es interno):

| Ruta | Observación |
|---|---|
| `GET /api/health` | Health check público (BD/Redis/storage/disco/SUNAT). Respuesta `{estado: ok|degradado, timestamp, checks:[{componente, estado, detalle}]}` (ver test). **Añadir a la suite común.** |
| `GET /api/v1/sunat/estado` | Estado del circuit breaker SUNAT: `{estado:"exito", sunat:{disponible, circuit: closed|open|half_open, fallas, entorno}}`. |
| `POST /api/v1/facturas/masivo` | Emisión masiva de facturas (con `license` + `check.limit:sunat`). Sin ejemplo. |
| `GET /api/v1/buscar-documento?tipo=&numero=` | Búsqueda RUC/DNI local + SUNAT/RENIEC (su descripción quedó pegada en "API key actual"). |
| `POST /api/v1/credenciales/recuperar`, `POST /api/v1/credenciales/recuperar/verificar` | Recuperación de credenciales por email (públicas). Cubiertas por `TenantFlowTest`. |
| `DELETE /api/v1/empresa` | Baja del tenant. |
| `POST /api/v1/series/init-defaults` | Crea series por defecto. |
| `PATCH /api/v1/sucursales/{id}`, `PATCH /clientes/{id}`, `PATCH /series/{id}`, `DELETE /series/{id}` | Generadas por `apiResource` (la colección usa PUT; la descripción de series dice que "no se eliminan", pero la ruta DELETE existe). |
| `POST /api/bridge/auth`, `POST /api/bridge/provision`, `DELETE /api/bridge/tenant` | Puente con el SaaS principal (header `X-Bridge-Key`). Fuera del contrato público. |
| `GET /api/debug/lookup` | Solo con `APP_DEBUG=true`. No migrar. |
| Alias `/api/sunat/*`: `GET/POST configuracion`, `GET/POST facturas`, `GET facturas/{id}/pdf|xml`, `POST facturas/{id}/anular`, mismos para `boletas` | Alias simplificados (mismo auth X-Api-Key/Secret, **sin** middleware idempotency/license/check.limit). Decidir si se replican. |
| `GET sunat/mi-api-key` (no `/api`, ruta web) | Fuera de alcance. |

Middlewares del grupo protegido `/api/v1` (contrato a replicar): `resolve.tenant` (auth por headers), `idempotency` (`Idempotency-Key`), `throttle:api`, `log.api`, `usage.headers`; en los `POST` de emisión: `license` + `check.limit:sunat` (cotizaciones/notas de venta: `check.limit:internal`). SIRE (salvo activar/desactivar): `sire.enabled`.

**Requests a excluir (fuera de alcance)**: carpeta 21 (7 requests `/cotizaciones…`), carpeta 22 (8 requests `/notas-venta…`, incluidos sus pagos) y `16 › Documentos internos (cotizaciones + notas de venta)` → `GET /reportes/documentos-internos`. Todas aparecen como `[EXCLUIR]` en §1.3.

### 1.5 Hallazgos de calidad / incoherencias de la colección (impacto en la suite común)

1. **Sin scripts ni aserciones**: no sirve todavía como test automatizado; hay que añadir tests Newman (status, `estado`, estructura) y capturar IDs en variables (`id_factura`, `id_boleta`, `id_NC`, `id_ND`, `id_resumen`, `id_anular`, `id_guia`, `id_guia_t`, `id`).
2. **IDs fijos** en muchas URLs (`/facturas/1`, `/boletas/72/cdr`, `/boletas/15/enviar`, `/notas-credito/3/cdr`, `/retenciones/2/xml`, `/retenciones/3`, `/sire/rce/{{periodo}}/comprobantes/1`, …) → dependen del estado de una BD concreta.
3. **Correlativos/fechas fijos**: "Crear factura básica" envía `"correlativo": 601` (el resto deja que el sistema asigne). NC/ND referencian `doc_afectado_correlativo` concretos (607, 611–622…) y fechas de 2026-03…09: en otra BD fallarán validaciones de existencia/plazo. Resumen/baja usan fechas fijas (el plazo SUNAT es 7 días).
4. **JSON con comentario**: el body de `PUT /empresa` contiene `"entorno": "beta", // production` (JSON inválido estricto; Postman puede limpiarlo, otros clientes no). Además envía `client_id`/`client_secret` de GRE en claro (enmascarado aquí).
5. **Descripciones erróneas**: "API key actual"/"Genera uno nuevo api key…" (copiadas de buscar-documento); "Exportar comprobantes" (copiada de consultar-cdr); "Crear percepción combustible (1%)" dice "Régimen 02 = Combustible. Tasa 2%" mientras el body usa `regimen:"02", tasa:1`; la descripción de "Crear percepción venta interna (2%)" dice "1% bienes consumo masivo, 2% combustible" (contradice a la anterior). Catálogo 22 SUNAT: 01 = venta interna 2%, 02 = combustible 1%, 03 = agentes de percepción 0.5%. En las facturas con percepción: `cod_regimen` 01 (2%), 02 (1%), 03 (0.5%) — coherente con el catálogo.
6. **Boleta básica**: el nombre dice "DNI, contado" pero envía `"solo_registro": true` (queda pendiente para RC) y `tip_afe_igv: "20"` (exonerado).
7. **Factura con detracción**: `detraccion.monto` = 37.76 sobre un total de 1000 (4 % de 944) — el backend **no recalcula** la detracción (`InvoiceBuilder` usa `porcentaje` y `monto` tal cual) y fuerza `tipo_operacion` = `detraccion.tipo_operacion ?? '1001'`. Las implementaciones deben replicar ese comportamiento (no recalcular) para equivalencia de XML.
8. **Percepción en factura**: el body manda `tipo_operacion:"0101"` pero el builder lo sobrescribe a `percepcion.tipo_operacion ?? '2001'`; el factor (`MultiplierFactorNumeric`) se deriva como `round(monto/base, 5)` para evitar la observación 3114.
9. **Factura "contingencia (modo offline)"**: en realidad solo es `enviar_automatico:false` (no hay campos de contingencia SUNAT).
10. **GRR "Corregir guía" (carpeta 13)**: manda `transportista.placa` (campo que no aparece en los demás ejemplos); y `mod_traslado:"01"` (público) con transportista; recordar que el PUT de guías exige payload completo.
11. **GRT**: `conductor` es un **arreglo** en GRT, mientras en GRR es `conductor` (objeto) o `conductores` (arreglo) → el contrato acepta ambas formas; documentar y testear las dos.
12. **Variables de entorno**: `ruc_empresa` declarada y no usada; `id_*` usadas y no declaradas.
13. **Archivos `postman-cloud:///`**: el PFX y el logo no viajan con la colección.
14. **Nombres de query inconsistentes** (`por_pagina` vs `per_page`; `format` vs `formato`) → decidir en el contrato común si se aceptan ambos (recomendado: aceptar ambos para compatibilidad).

### 1.6 Bodies JSON representativos por tipo de documento

Los bodies **literales** (copiados del `raw` de la colección, solo con secretos enmascarados) están en el **Apéndice A** (105 bodies, agrupados por carpeta). Índice por tipo de documento solicitado:

| Tipo | Request(s) de la colección (Apéndice A) | Endpoint | Notas de comportamiento |
|---|---|---|---|
| Factura gravada | 04 › Crear factura básica; Crear factura, codigo producto | `POST /facturas` | `precio_unitario` **incluye IGV** (2360 → valor 2000). `tip_afe_igv` 10. Opcional `cod_producto_sunat` (UNSPSC 8 dígitos). |
| Factura exonerada / inafecta | Crear factura exonerada (20); Factura inafecta (30) | `POST /facturas` | |
| Factura con detracción | Factura con detracción | `POST /facturas` | objeto `detraccion{tipo_operacion,cod_bien,cod_medio_pago,cta_banco,porcentaje,monto}` → op. 1001. |
| Factura con percepción | 19 › 0.5% / 1% / 2% | `POST /facturas` | objeto `percepcion{cod_regimen,porcentaje,base,monto[,tipo_operacion]}` → op. 2001. |
| Exportación | Crear factura exportación (tipo 0200) | `POST /facturas` | `tipo_operacion` 0200, `tip_afe_igv` 40, USD, cliente `tipo_doc:"0", num_doc:"0"`. |
| Gratuita | Factura gratuita (11); Factura compleja (13, 32); Factura ICBPER + ítem gratuito | `POST /facturas` | `precio_unitario: 0` + `mto_valor_unitario` (valor referencial) + `porcentaje_igv`. |
| Crédito / cuotas | Crear factura crédito (con cuotas) | `POST /facturas` | `forma_pago:"Credito"`, `fecha_vencimiento`, `cuotas[]{monto,fecha_pago}`. Formas válidas (test): Contado, Credito, Tarjeta, Billetera Digital, Deposito, Transferencia. |
| Anticipo | Factura anticipo (`anticipos[]{tipo_doc:"02",serie,correlativo,monto}`); Boleta con anticipo (`tipo_doc:"03"`) | | El sistema resta la base y agrega descuento global tipo 04 (regla 3287). |
| Descuentos | Factura descuento global (`descuentos_globales[]{cod_tipo:"02",monto}`), por línea (`items[].descuentos[]{cod_tipo:"00",monto}`), que NO afecta base (`cod_tipo:"03", monto_base, factor, monto`) | | |
| ICBPER / ISC / IVAP | Factura ICBPER (`factor_icbper`), ISC (`porcentaje_isc`, `tip_sis_isc`), IVAP (`tip_afe_igv:"17"`, `porcentaje_igv:4`) | | |
| Factura + guía | Factura + Guía de Remisión (`guias[]{tipo_doc:"09",nro_doc}`) | | |
| Envío manual | `…envío manual (enviar_automatico=false)` en cada tipo | | Queda `pendiente`; luego `POST /{tipo}/{id}/enviar`. |
| Boleta | 05 › Boleta básica; consumidor final (`tipo_doc:"0", num_doc:"99999999"`); NRUS; MYPE restaurantes; solo registro; anticipo | `POST /boletas` | `solo_registro:true` → no se envía individualmente (va por RC). |
| Nota de crédito | 06 › NC - Anulación total; Descuento parcial (04); Devolución parcial (06); USD; Anulación de boleta (serie BC01) | `POST /notas-credito` | `doc_afectado_tipo/serie/correlativo`, `cod_motivo`, `des_motivo`. |
| Nota de débito | 07 › ND intereses (01), aumento de valor (02), penalidad (usa 02), sobre boleta (BD01) | `POST /notas-debito` | |
| Guía remitente (09) | 10 › GRR transporte público, privado, M1/L, entre establecimientos, múltiples conductores | `POST /guias-remision` | Campos: `destinatario`, `cod_traslado`, `mod_traslado`, `fecha_traslado`, `peso_total`, `und_peso_total`, `num_bultos`, `partida_*`, `llegada_*` (+ `_ruc`, `_cod_local`), `transportista{…,nro_mtc}`, `vehiculo{placa,secundarios[]}`, `conductor{}`/`conductores[]`, `indicadores[]`. |
| Guía transportista (31) | 11 › GRT estándar (atajo), vía endpoint general (`tipo_documento:"31"`), subcontratado, pagador del flete tercero, múltiples conductores, DAM | `POST /guias-remision-transportista` o `POST /guias-remision` | `remitente`, `doc_relacionado{tipo_codigo,numero,ruc_emisor}`, `vehiculo{placa,nro_circulacion,secundarios[]}`, `conductor[]`, `datos_subcontratador`, `datos_pagador_flete{tipo: remitente|destinatario|tercero|subcontratador,…}`. |
| Resumen diario | 08 › Crear resumen diario (`{fecha_resumen}`); ANULACIÓN (`anular[]{id,motivo}`); envío manual | `POST /resumenes` | Toma TODAS las boletas pendientes de la fecha. `anular[].id` = id interno de boleta. |
| Baja (RA) | 09 › Anular factura; múltiples en lote (01/07/08); NC; envío manual | `POST /anulaciones` | `fecha_generacion`, `fecha_comunicacion` (opcional), `detalles[]{tipo_documento,serie,correlativo,motivo}`. Boletas → 422 `boletas_no_soportadas_en_ra`. |
| Retención (20) | 18 › régimen general 3%; múltiples documentos; envío manual | `POST /retenciones` | `regimen:"01"`, `tasa:3`, `proveedor`, `documentos[]{tipo_doc,num_doc,fecha_emision,moneda,imp_total,fecha_retencion,imp_retenido?,imp_pagar?,pagos[]{moneda?,importe,fecha}}`. `imp_retenido` se calcula si no viene. |
| Percepción (40) | 19 › venta interna 2%; combustible 1%; envío manual | `POST /percepciones` | `regimen`, `tasa`, `cliente`, `documentos[]{…,fecha_percepcion,cobros[]{fecha,importe}}`. |
| Reversión (RR) | 20 › de retención; de percepción; múltiple; envío manual | `POST /reversiones` | `detalles[].tipo_documento` 20/40. Se envía/consulta con `/anulaciones/{id}/enviar|estado`. |

**Respuestas guardadas**: la colección **no contiene ninguna** (`response: []` en todas). Ejemplos de respuesta: ver §2 (aserciones de tests) y §3 (documentación).

Ejemplos de respuesta que sí se pueden fijar desde el código/tests (contrato de errores, `bootstrap/app.php`):

```json
{ "estado": "error", "mensaje": "Error de validación", "errores": { "cliente.tipo_doc": ["…sugiere POST /boletas…"] } }
```
- 404 → `{"estado":"error","mensaje":"Recurso no encontrado."}`; 401 → `"No autenticado."`; 403 → `"No autorizado."`; 405 → `"Método HTTP no permitido para esta ruta."`; 429 → `"Demasiadas solicitudes. Intenta de nuevo más tarde."`; 500 → `"Error interno del servidor."` (+ `excepcion/detalle/archivo` solo con debug). Las claves de `errores` son **planas con punto** (`"cliente.tipo_doc"`, no anidadas).
- Errores de negocio con `codigo_error` + `siguiente_accion` (de `MejorasApiTest`): `boletas_no_soportadas_en_ra` → `siguiente_accion.endpoint = "POST /api/v1/resumenes"`; `documento_aceptado_no_editable` → `siguiente_accion.operacion = "emitir_nota_credito"`, `siguiente_accion.doc_afectado{tipo:"01",serie:"F001",correlativo:"1"}`.


---

## 2. Pruebas existentes (`C:\laragon\www\API-PRO\tests`)

### 2.1 Infraestructura

- **Framework**: Pest (sobre PHPUnit). `tests/Pest.php` aplica `Tests\TestCase` + `RefreshDatabase` a todo `Feature/`. `Unit/` mezcla Pest y clases PHPUnit puras.
- **`phpunit.xml`**: suites Unit y Feature; `DB_CONNECTION=sqlite`, `DB_DATABASE=:memory:`, `QUEUE_CONNECTION=sync`, `CACHE_STORE=array`, `MAIL_MAILER=array`, `SESSION_DRIVER=array`, `BCRYPT_ROUNDS=4`, Pulse/Telescope/Nightwatch desactivados.
- **Factories** (`database/factories`): `TenantFactory`, `UserFactory`, `SireTicketFactory`. Los tenants se crean con `api_key`/`api_secret` generados; los tests autentican con headers `X-Api-Key`/`X-Api-Secret`/`Accept`.
- **Mocks de SUNAT**: no hay llamadas reales. Se usan `Mockery` sobre `GreenterService::send` (devuelve arrays `['success'=>…, 'xml'=>…, 'ticket'=>…, 'error_code'=>…, 'error_message'=>…]`), `Http::fake()` (webhooks) y un `MockHandler` de Guzzle para SIRE (`SireTestHelper`: `mockAuthSuccess`, `mockAuthFailure`, `mockResponse`, `mockTicketResponse`).
- **Fixtures**: **no existe carpeta de fixtures** ni XML/CDR/certificados de prueba en `tests/`. Los certificados se generan en memoria (`openssl_pkey_new` + `openssl_csr_sign`, autofirmados "PRUEBA SAC") en `CertificateServiceTest`. Los "XML" de las pruebas son literales triviales (`'<xml/>'`, `'<Baja>contenido-xml</Baja>'`) sobre `Storage::fake('public')`. → **No hay pruebas doradas de XML** en el suite: ninguna prueba compara XML generado contra un XML de referencia ni valida firma o esquema UBL.
- **Cobertura del flujo de emisión**: no hay ningún test que emita una factura/boleta/NC/ND/guía completa (request → XML → firma → envío). Solo se testean validaciones, reglas de estado y servicios aislados.

### 2.2 Inventario por archivo

Leyenda relevancia migración: **C** = contrato HTTP de la API (replicar en las 7 implementaciones), **N** = regla de negocio/servicio (replicar como test unitario), **P** = panel web/Inertia o Laravel-específico (no aplica salvo que se migre el panel).

| Archivo (líneas) | Tests | Qué cubre | Rel. |
|---|---|---|---|
| `Feature/Api/V1/MejorasApiTest.php` (138) | 5 | `POST /clientes` acepta alias `tipo_doc`/`num_doc` y canónicos `tipo_documento`/`numero_documento` (201, `estado=exito`); `POST /facturas` con cliente DNI → 422, `errores["cliente.tipo_doc"]` sugiere `/boletas`; `POST /anulaciones` con boleta → 422 `codigo_error=boletas_no_soportadas_en_ra`, `siguiente_accion.endpoint="POST /api/v1/resumenes"`; `PUT /facturas/{id}` aceptada → 422 `documento_aceptado_no_editable`, `siguiente_accion.operacion=emitir_nota_credito`, `doc_afectado{tipo,serie,correlativo}`. | **C** |
| `Feature/Api/V1/FormaPagoValidationTest.php` (36) | 2 (dataset 2×6 + 2) | `forma_pago` válidas en `/facturas` y `/boletas`: Contado, Credito, Tarjeta, Billetera Digital, Deposito, Transferencia; `ValorInventado` → `errores.forma_pago`. | **C** |
| `Feature/Api/V1/AnulacionDescargaTest.php` (79) | 4 | `GET /anulaciones/{id}/xml` (Content-Type `application/xml`), `/cdr` (zip), 404 sin archivo, aislamiento multi-tenant (no descarga de otro tenant). Identificador `RA-20260918-001`. | **C** |
| `Feature/Api/V1/GuiaCdrDownloadTest.php` (19) | 1 | La ruta `guias-remision/{id}/cdr` existe → `DispatchGuideController@cdr`. | C (trivial) |
| `Feature/Seguridad/IdempotenciaSecretoWebhookTest.php` (112) | 7 | `Idempotency-Key`: misma clave+mismo body → replay (header `Idempotency-Replayed: true`, no duplica); misma clave+body distinto → 422. `api_secret` cifrado en reposo y auth OK; secret erróneo → 401. Headers de seguridad: `X-Content-Type-Options: nosniff`, `X-Frame-Options: SAMEORIGIN`, `Referrer-Policy: strict-origin-when-cross-origin`. Credenciales por query string → 401. Webhook firmado: header `X-Signature: sha256=<hmac_sha256(body, api_secret)>`. | **C** |
| `Feature/Ops/HealthEndpointTest.php` (30) | 2 | `GET /api/health` sin auth → 200, `{estado: ok|degradado, timestamp, checks:[{componente,estado,detalle}]}`, incluye componente "Base de datos"; cada check solo tiene esas 3 claves. | **C** |
| `Feature/Admin/TenantFlowTest.php` (404) | 11 | Panel admin (`/admin/empresas` web): crear/editar empresa, PUT parcial, multipart, regenerar credenciales, activar/desactivar (invalida cache API), plan inexistente. **API**: `POST /api/v1/credenciales/recuperar {ruc}` → 200 y encola mail con token de 64 chars; `POST /api/v1/credenciales/recuperar/verificar {token}` → `{estado:"exito", datos:{api_key, api_secret}}`, token de un solo uso (cache `credential_recovery:{token}`, TTL 600 s). | P + **C** (2 tests) |
| `Feature/Admin/TenantGreCredentialsTest.php` (32) | 1 | `TenantRequest` valida `client_id`/`client_secret` (antes se descartaban). | N |
| `Feature/Gre/GreCredentialsTest.php` (51) | 3 | Resolución de credenciales GRE: `client_id/secret` propios → `sire_client_id/secret` del panel → default de config beta (`facturacion.sunat.beta.gre_client_id/secret`). Bug histórico: 401 `unauthorized_client`. | **N** |
| `Feature/Bajas/AnulacionBajaTest.php` (99) | 5 | Comando `bajas:reparar-anulaciones` (idempotente); job `CheckVoidedTicketStatus`: baja aceptada → factura `anulado` sin importar estado; baja rechazada no des-anula una factura anulada por otra baja; baja rechazada revierte `anulacion_en_proceso`. | **N** (máquina de estados) |
| `Feature/Documentos/SendReversionToSunatTest.php` (73) | 2 | `SendReversionToSunat`: rechazo (`0151` "El nombre del archivo ZIP es incorrecto") → RR `rechazado` y retención vuelve a `aceptado`; envío OK con ticket `1790286421925` → retención `anulado` y se encola `CheckVoidedTicketStatus`. | **N** |
| `Feature/Documentos/LeyendaYObservacionTest.php` (24) | 3 | Leyenda monto en letras: 1013.35 PEN → contiene `MIL TRECE CON 35/100` (no "Y 35/100"); NC/ND aceptan `observacion`. | **N** (golden de leyenda) |
| `Feature/Fecha/FechaEmisionLimaTest.php` (63) | 4 | Fecha de emisión en zona `America/Lima`: 23:44 Lima (04:44Z día siguiente) → IssueDate del día de Lima; `…T04:44:00Z` → fecha Lima; madrugada no retrocede; builder IssueDate/IssueTime en hora Lima. | **N** (crítico para equivalencia) |
| `Feature/Sunat/EnvioEstadoTest.php` (55) | 2 | `sunat:reintentar-pendientes` reencola solo `enviado` atascados (>15 min), no `pendiente` (manual/resumen); `SendDocumentToSunat` no reenvía `aceptado` (idempotencia). | **N** |
| `Feature/Sunat/RepararRechazosTest.php` (46) | 2 | `comprobantes:reparar-rechazos`: `aceptado` con código 2108 (o 2335) → `rechazado`; código 0 se queda; 4000 (observación) se queda aceptado; `--dry-run` no modifica. Regla: códigos 2000–3999 = rechazo, ≥4000 = observación. | **N** |
| `Feature/Licencia/ActivarLicenciaPanelTest.php` (82) | 4 | Activación de licencia desde panel (secreto cifrado, auditoría, 422 si faltan datos, BD pisa `.env`). | P |
| `Feature/Ops/AlertasOpsTest.php` (89) | 7 | `OpsAlert` (webhook, throttle, nunca lanza), comandos `ops:jobs-fallidos`, `ops:salud`. | N (ops) |
| `Feature/Ops/AuditoriaTest.php` (59) | 3 | Regenerar credenciales devuelve un secret que autentica + auditoría; `PUT /empresa` auditado; `AuditLog::registrar` nunca lanza. | C/N |
| `Feature/Rbac/*.php` (11 archivos, ~620 líneas) | ~45 | RBAC del **panel web** multiusuario (owner/cajero/super admin, abilities `factura.emitir`, `boleta.emitir`, `nota_credito.emitir`, `anulacion.emitir`, `resumen.emitir`, `guia_remitente`, `serie.gestionar`, `consulta.cpe`, `exportar`, `reporte.ver`, `sire.gestionar`, `cotizacion.emitir`, `equipo.gestionar`…). | P |
| `Feature/Sire/*.php` (9 archivos + `SireTestHelper.php`) | ~45 | SIRE RCE: activar/desactivar (token OAuth SUNAT, 401), periodos 5.33 (`libro=rvie`, 422, bloqueo si `sire_enabled=false`), propuesta 5.34 (ticket + `PollTicketJob`, filtros, periodo inválido 422), aceptar 5.2, preliminar 5.4 (error 1008), resumen 5.35, constancia 5.49 (`nom_constancia` requerido), comprobantes locales (paginación `por_pagina`, filtros), tickets (paginado, filtros, aislamiento tenant, 404, refrescar, 409 sin archivo), uploads TUS 5.3/5.5/5.6 (POST 201 + Location, PATCH 204 → `numTicket`; 202; error 1024), ajustes posteriores 4 variants × 4 acciones (422 variant inválido), reconciliación (totales, agrupación, logs, async), webhook de reconcile firmado; username SIRE = RUC+usuario sin espacio. | **C** (módulo SIRE) |
| `Feature/Auth/*`, `Feature/Settings/*`, `DashboardTest.php`, `ExampleTest.php` | ~30 | Starter kit Laravel (login, 2FA, perfil, password). | P |
| `Unit/CertificateServiceTest.php` (115) | 5 | `CertificateService::convertToPem`: acepta PEM cert+clave; rechaza certificado sin clave privada (mensaje "NO la clave privada", sugiere `.p12` y `-nodes`); clave cifrada → sugiere `-nodes`; PFX con contraseña errónea → error. | **N** |
| `Unit/TaxRateServiceTest.php` (132) | 13 | `TaxRateService`: general 18 %; tenant null → 18; **`mype_restaurantes` por año: 2026 → 10.5, 2027 → 15, ≥2028 → 18**; override gana; `porcentaje_igv` del ítem tiene prioridad; IVAP (17) → 4 %; exonerado/inafecto/exportación (20/30/40) → 0; NRUS → 0 y `tip_afe_igv` default 30; general default 10. | **N** |
| `Unit/TaxRateRegimenTest.php` (77) | 5 | Regímenes actuales: `rus` → 0 % y tip_afe 30; general/rer/mype → 18 % y tip_afe 10; override manda (mype+10.5, rus+5); `tasaLey31556('2026-07-30')=10.5`, `('2027-01-15')=15`, `('2035-01-01')=null`; régimen desconocido → 18. | **N** |
| `Unit/Sire/SireUnitTest.php` (349) | ~25 | `PeriodoTributario` (YYYYMM), `Base64MetadataEncoder` TUS (reproduce el ejemplo del manual SUNAT: `numRuc MjAxMDAxNzY0NTA=`, `perTributario MjAyMzAy`, `codLibro MTQwMDAw`), `NombreArchivoBuilder` (error 1044), `PropuestaParser` (TXT/ZIP, fechas), enums (`EstadoTicket`, `CodLibro`, `CodTipoArchivo`, `VariantAjuste`, `FasePeriodo`), `SireErrorCatalog`, `ZipBuilder` (SHA256). | **N** (SIRE) |
| `Unit/ExampleTest.php` | 1 | trivial | — |

**Contradicción detectada en tests**: `TaxRateServiceTest` sigue probando el régimen `mype_restaurantes` (calendario 10.5/15/18), mientras `TaxRateRegimenTest`, la colección y la documentación dicen que **ese régimen ya no existe** y que la tasa Ley 31556 se aplica con `igv_rate_override` (con `tasaLey31556()` solo como sugerencia). Para la migración: el contrato vigente es `tax_regime ∈ {general, rer, mype, rus}` + `igv_rate_override`; decidir si se mantiene compatibilidad con valores legados `mype_restaurantes`/`nrus`.

### 2.3 ¿Hay XML de referencia aprovechables como "pruebas doradas" en `tests/`?

**No.** `tests/` no contiene XML, CDR ni certificados. Los únicos "valores dorados" reutilizables son:
- Leyenda: `generateLeyenda(1013.35,'PEN')` ⊇ `"MIL TRECE CON 35/100"`.
- Fechas Lima (4 casos de `FechaEmisionLimaTest`).
- Tasas IGV (`TaxRate*Test`).
- Firma webhook `sha256=HMAC(body, api_secret)`.
- Codificación TUS SIRE (ejemplo del manual).
- Clasificación de códigos SUNAT (0 = aceptado, 2000–3999 rechazo, ≥4000 observación; 2108/2335 rechazo).

Los XML de referencia reales están en `storage/` y en las carpetas de manuales GRE (§4).


---

## 3. Documentación interna — resumen útil para la migración

Se leyeron todos los `.md` de `documentacion/` (39 archivos), `docs/actualizaciones-sunat-2026.md`, `.scribe/` (auth, intro, `endpoints/custom.0.yaml`, y un muestreo de `endpoints/00.yaml`, 640 KB), los 5 documentos de la raíz (`PROMPT-Integrar-Facturacion-SUNAT.md`, `PLAN-PANEL-MULTIUSUARIO.md`, `LICENSING.md`, `SEGURIDAD-REPO.md`, `README.md`), las carpetas de manuales GRE (`MANUAL PARA GUIAS DE REMISION REMITENTE Y TRASNPORTISTA/`, `guia-trasportista/`) y `actualizaciones-sunat/` (texto extraído de los xlsx/docx/pdf). Esta sección es la **síntesis**; el detalle completo por archivo, con todos los ejemplos JSON de respuesta copiados literalmente, está en los **Apéndices B1–B3**.

### 3.1 Contrato HTTP transversal (lo que deben replicar las 7 implementaciones)

- **Base**: `/api/v1`. JSON siempre (también sin `Accept`; middleware `ForceJsonAccept`). Multipart solo en `POST /registro`, `POST /empresa/logo`, `POST /empresa/certificado` y el upload TXT de SIRE.
- **Auth**: headers `X-Api-Key` + `X-Api-Secret` (comparación timing-safe; secret **cifrado reversible** en BD porque también es la clave HMAC de webhooks; cache `tenant:key:{api_key}` con TTL corto). 401 literal: `"Las cabeceras X-Api-Key y X-Api-Secret son requeridas."` / `"Credenciales de API inválidas."`; 403 `"Empresa desactivada. Contacte al administrador."`.
  - ⚠ `PROMPT-Integrar…md` aún dice que se acepta `?api_key=&api_secret=` en query: **falso desde el fix S3** (test lo rechaza con 401).
- **Envelope** (`app/Http/Traits/ApiResponse.php`): éxito `{"estado":"exito","mensaje":"…","datos":…}` (+`meta` opcional, casi no usado); error `{"estado":"error","mensaje":"…","errores"?:{"campo.con.punto":[…]}}`; error accionable `{"estado":"error","mensaje","codigo_error","siguiente_accion"?}` (422). `created()` → 201 "Creado exitosamente". Errores de plan: 403 `codigo_error:"subscription_expired"`, 429 `codigo_error:"limit_reached"` (+`plan_actual, recurso, actual, limite, mejora_plan`). Excepciones de envelope: `GET /api/health` (`estado: ok|degradado`), `GET /v1/sunat/estado` (`{estado, sunat:{…}}` sin `datos`), y pagos (`/{tipo}/{id}/pagos` → JSON plano `{mensaje, estado_pago, monto_pagado, pagos[]}`).
- **Paginación — NO es uniforme** (hay que replicar tal cual o normalizar con decisión explícita):
  - Facturas/boletas/NC/ND/guías: `datos.datos[]` + `datos.paginacion{pagina_actual, ultima_pagina, por_pagina, total}`; parámetro `por_pagina` (default 15, máx 100), `page`.
  - Resúmenes: `datos[]` + `paginacion` **al mismo nivel** que `datos`.
  - Anulaciones: `meta` + parámetro `per_page`. Retenciones/percepciones: `per_page`. Catálogo producto: `per_page` y respuesta `{estado,total,pagina,por_pagina,datos}`. SIRE: `por_pagina` (comprobantes máx 200/def 50; tickets máx 100/def 20).
  - La descripción de la colección promete `meta` para paginación → no coincide con el código.
- **Montos**: strings con 2 decimales en comprobantes (`"mto_igv":"11.88"`), números en resúmenes y en el bloque `totales`; `correlativo` en respuesta con relleno a 8 dígitos y `numero_completo` sin ceros (`F001-123`) — salvo GRT (`V001-000001`).
- **Idempotencia**: header `Idempotency-Key` en POST/PUT/PATCH/DELETE; replay con `Idempotency-Replayed: true`; cuerpo distinto → 422 `"La Idempotency-Key ya se usó con un cuerpo distinto."`; en curso → 409 `"Ya hay una solicitud en proceso con esa Idempotency-Key."`; si la primera fue 5xx se libera.
- **Rate limit**: limiter `api` por tenant según plan (free 30, pro 120, business 300 req/min) → 429 + `Retry-After`; limiter de envío a SUNAT 120 comprobantes/min por RUC (`SUNAT_PER_TENANT_PER_MINUTE`); SIRE 30 req/min.
- **Headers de respuesta**: `X-Content-Type-Options: nosniff`, `X-Frame-Options: SAMEORIGIN`, `Referrer-Policy: strict-origin-when-cross-origin`, `X-Permitted-Cross-Domain-Policies: none`, HSTS bajo HTTPS, CORS; `usage.headers` → `X-Usage-Documents-Used`, `X-Usage-Documents-Limit`, `X-Usage-Percent`, `X-Usage-Warning`; `x-ratelimit-*`.
- **Descargas**: XML `application/xml` attachment `{SERIE}-{CORR}.xml`; CDR `application/zip` attachment `R-{SERIE}-{CORR}.zip`; PDF `application/pdf` **inline** (`Cache-Control: private, max-age=300`), `?format=a4|a5|ticket-80|ticket-58` (facturas también `formato`); 404 si aún no existe.
- **Webhooks** (`NotifyWebhookJob`, cola `webhooks`, 3 intentos, backoff 10/30/90 s, timeout 15 s): body `{"event","document":{id,tipo_documento,numero_completo,sunat_status,sunat_code,sunat_description,hash_cpe},"timestamp"}`; headers `X-Signature: sha256=<hmac_sha256(body, api_secret)>`, `X-Webhook-Event`. Eventos `document.sent|rejected|status_updated|timeout_sunat`, `summary.*`, `voided.*`; SIRE: `ticket.completed|failed`, `propuesta.processed`, `preliminar.registered`, `reconciliation.completed`. Campo de configuración en la API: **`url_webhook`** (en panel y BD: `webhook_url`). Protección SSRF sobre la URL.
- **Entorno**: por tenant, `entorno ∈ {beta, production}` (varios ejemplos de la doc dicen `produccion`: incorrecto).

### 3.2 Máquina de estados y envío (crítico)

- `sunat_status`: `pendiente → enviado → aceptado | rechazado`, más `anulado` y `anulacion_en_proceso` (boletas en RC de anulación / bajas). Resúmenes, bajas y guías además `procesando`.
- `pendiente` es **intencional** (`enviar_automatico:false`, `solo_registro:true`, o agotamiento de reintentos con `sunat_code=SUNAT_TIMEOUT`): el reintentador automático **no** lo toca; solo reencola `enviado` atascados > 15 min (`sunat:reintentar-pendientes`). Un `aceptado`/`anulado` nunca se reenvía (guard del job, test).
- Códigos CDR (fix 30, y tests): `0` aceptado; `1000–3999` rechazo (p. ej. 2108, 2325, 2335, 3127, 3265); `≥4000` aceptado con observaciones; 100–999 excepción temporal (reintentable). ⚠ `docs/actualizaciones-sunat-2026.md` aún dice que `GreenterService` trata 3xxx como observación (texto anterior al fix; el comando `comprobantes:reparar-rechazos` corrige datos históricos).
- Reintentos del job: `tries=20`, timeout 90 s, backoff ~15 h total; reintentables: SoapFault de red y códigos 0/100/109/500/1033/2800; `recoverFromCdr` consulta el CDR antes de reenviar para no duplicar (bug de ~40 jobs duplicados). `envio-manual.md` dice "10 intentos 15–600 s" (desactualizado). `WithoutOverlapping` por documento.
- Circuit breaker SUNAT por endpoint: 5 fallos/60 s → OPEN 300 s → HALF_OPEN (1 job de prueba) → CLOSED, o OPEN con TTL duplicado; en OPEN `release(300+rand(0,120))`.
- Estado inicial tras `POST` automático: la doc muestra a veces `pendiente` y a veces `enviado` (contradicción); el código encola y responde `enviado` en el bloque `sunat` (ver ejemplos en B1/B2). **Definir en el contrato común y testear.**
- `PUT` sobre aceptado → 422 `documento_aceptado_no_editable` + `siguiente_accion` (factura/boleta → NC; NC → baja). Facturas/boletas/NC/ND: PUT **parcial** (si vienen `items` se recalcula todo); guías: PUT **completo**. El PUT resetea a `pendiente`, limpia códigos y reencola.
- Bajas (job `CheckVoidedTicketStatus`): baja aceptada → documento `anulado` sin importar su estado; baja rechazada no des-anula un documento anulado por otra baja; sí revierte `anulacion_en_proceso`. RR rechazada → la retención/percepción vuelve a `aceptado`; RR enviada → `anulado` provisional y polling del ticket.
- Resumen rechazado → las boletas vuelven a `pendiente`. Resumen: `fecha_resumen` entre hoy-7 y hoy (Lima); modo anulación con `anular[]{id,motivo,tipo_documento?(03|07|08)}`.
- Asíncronos por ticket: RC, RA, RR (SOAP `sendSummary` + `getStatus`) y GRE 09/31 (REST, polling cada 15 s). Síncronos: 01/03/07/08 (SOAP `sendBill`), 20/40 (SOAP otroscpe).

### 3.3 Reglas de negocio y cálculo (resumen; detalle en B1)

- `precio_unitario` **incluye IGV**; el servidor recalcula totales siempre; redondeo 2 decimales; leyenda 1000 en letras con "CON nn/100" (test).
- Tasa IGV — precedencia: `items[].porcentaje_igv` → `tip_afe_igv` especial (17 IVAP 4 %; 20/30/40/gratuitas 0 %) → `tenant.igv_rate_override` → `tax_regime=rus` 0 % → 18 %. Regímenes válidos: `general`, `rer`, `mype`, `rus` (`mype_restaurantes` y `nrus` → 422 desde la migración 2026-07-30). Ley 31556 (referencia, no automática): 2026 10.5 %, 2027 15 %, 2028 18 %. SUNAT valida 10.5 % desde 13/02/2026 (ERR-3462 misma tasa en todas las líneas; OBS-4439 padrón).
- NRUS (`rus`): solo boletas (factura → 422), NC/ND solo contra 03; `tipo_operacion 0113`, `tip_afe_igv 30` por defecto, TaxScheme 9998/INA/FRE.
- Factura: serie `^F[A-Z0-9]{3}$`, cliente RUC (11 dígitos, prefijo 10/15/17/20) — con DNI → 422 sugiriendo `/boletas`. Boleta: `^B[A-Z0-9]{3}$`; > S/ 700 exige identificar al cliente. `forma_pago` ∈ {Contado, Credito, Tarjeta, Billetera Digital, Deposito, Transferencia}; `Credito` exige `cuotas[]` y `fecha_vencimiento`.
- Descuentos: por ítem (`00` afecta base, `01` no) con `monto` | `porcentaje` (0–100) | `factor`; globales (`02` afecta, `03` no) con `monto` obligatorio y `porcentaje` como fracción 0–1. Anticipos → descuento global 04 (regla 3287). `cargos_globales` se ignora en silencio.
- Detracción: objeto `detraccion` (op 1001), monto no recalculado; doc: solo si total > S/ 700. Percepción en factura: objeto `percepcion` (op 2001), factor = monto/base. Cat. 54 suma 038/042/043/046/047 desde 01/09/2026 (⚠ tasas 022/037 documentadas al 10 %, verificar vigencia).
- NC (`doc_afectado_tipo` 01/03/12; Cat. 09): importe ≤ documento afectado (factura estricto, boleta +1; ERR-3286/3503). ND (Cat. 10): motivo 13 "Penalidades" desde 01/01/2027, solo inafecto, doc. afectado opcional (hoy SUNAT rechaza 13 con 2172; hasta 31/12/2026 usar 03). ⚠ Discrepancias de catálogo con SUNAT: Cat. 09 SUNAT tiene 01–13 (la API no maneja 10–13); Cat. 10 SUNAT solo 01, 02, 03, 11, 12, 13 (la doc de la API lista 04–09); Cat. 06 SUNAT incluye B–H.
- Bajas RA: solo 01/07/08 (y 20/40 vía RR); boleta → 422 `boletas_no_soportadas_en_ra`. Plazo 7 días (retención/percepción: mismo mes). RA y RR comparten tabla/endpoint de envío/estado.
- GRR 09: `mod_traslado` 01 (exige `transportista` ≠ RUC emisor; desde 2026-06-01 `fecha_de_entrega_al_transportista`) / 02 (exige `vehiculo` + `conductor` con licencia); placa `^[A-Z0-9]{6,8}$` sin guion; indicador M1L permite omitir transportista/conductor (la API normaliza el typo "Categoria", error 3388). GRT 31: serie `V…`, `remitente` ≠ emisor, `doc_relacionado` obligatorio (01/03/09/31/50/52; DAM `[0-9]{3}-[0-9]{4}-[0-9]{2}-[0-9]{1,6}`; `ruc_emisor` si 09/31), pagador del flete obligatorio si hay subcontratación; el XML inyecta `DespatchParty` en `cac:Despatch` (Greenter no lo soporta nativo). En beta GRT: advertencia `grt_beta_no_soportado` según PROMPT, pero en `storage` hay GRT V001-1…18 **aceptadas** en el servidor de pruebas.
- Fecha de emisión en zona **America/Lima** (tests de borde nocturno).
- Código de producto SUNAT (`cod_producto_sunat`, UNSPSC v14, 8 dígitos, catálogo ~52 840 códigos sembrado por `UnspscCodeSeeder`): obligatorio para el padrón desde 01/01/2027 (ERR-3496).

### 3.4 Servicios SUNAT (URLs, del config y manuales)

| Servicio | Beta | Producción |
|---|---|---|
| SOAP FE `billService` (01/03/07/08, RC, RA) | `https://e-beta.sunat.gob.pe/ol-ti-itcpfegem-beta/billService` | `https://e-factura.sunat.gob.pe/ol-ti-itcpfegem/billService` |
| SOAP retención/percepción/RR | `https://e-beta.sunat.gob.pe/ol-ti-itemision-otroscpe-gem-beta/billService` | `https://e-factura.sunat.gob.pe/ol-ti-itemision-otroscpe-gem/billService` |
| Consulta CDR `billConsultService` | `https://e-beta.sunat.gob.pe/ol-it-wsconscpegem-beta/billConsultService` | `https://e-factura.sunat.gob.pe/ol-it-wsconscpegem/billConsultService` |
| GRE token OAuth2 (password grant, `scope=https://api-cpe.sunat.gob.pe`, `username=RUC+usuarioSOL`) | `https://gre-test.nubefact.com/v1/clientessol/{client_id}/oauth2/token` (servidor de pruebas Nubefact) | `https://api-seguridad.sunat.gob.pe/v1/clientessol/{client_id}/oauth2/token/` |
| GRE envío `POST …/comprobantes/{ruc}-{codCpe}-{serie}-{numero}` body `{"archivo":{"nomArchivo","arcGreZip"(base64),"hashZip"(SHA-256 hex del zip)}}` → `{numTicket, fecRecepcion}` | `https://gre-test.nubefact.com/v1/contribuyente/gem/…` | `https://api-cpe.sunat.gob.pe/v1/contribuyente/gem/…` |
| GRE ticket `GET …/comprobantes/envios/{numTicket}` → `{codRespuesta: 98 en proceso / 99 error / 0 OK, error{numError,desError}, arcCdr, indCdrGenerado}` | ídem Nubefact | ídem api-cpe |
| Consulta integrada CPE (client_credentials) | — | token `https://api-seguridad.sunat.gob.pe/v1/clientesextranet/{client_id}/oauth2/token/`; `https://api.sunat.gob.pe/v1/contribuyente/contribuyentes/{ruc}/validarcomprobante` |
| SIRE | — | token en api-seguridad (`clientessol`); API `https://api-sire.sunat.gob.pe/v1` |

Credenciales de prueba públicas: SOAP beta RUC `20000000001` / usuario `MODDATOS`; servidor GRE de pruebas: `client_id` `test-85e5b0ae-255c-4891-a595-0b98c65c9854` (público en el docx del manual), `client_secret` `***`, `username={RUC}MODDATOS`, `password=MODDATOS`. Errores GRE: envío 422 `{cod,msg,errors[{cod,msg}]}` (501–507, 155–161), consulta `{codError,desError}` (0508, 0509) → parsear ambas formas. Resolución de credenciales GRE por tenant: `client_id` propio → `sire_client_id` → default de config beta (test).

### 3.5 Novedades SUNAT 2026–2027 (de `actualizaciones-sunat/`, `docs/` y `referencia/`)

| Vigencia | Cambio | Impacto |
|---|---|---|
| 13/02/2026 | Tasa especial IGV 10.5 % restaurantes/hoteles (FAC/BOL/NC/ND/RC), ERR-3462, OBS-4439 | `igv_rate_override` |
| 01/06/2026 | R.S. 000075-2026: emisor electrónico desde la inscripción del RUC; SIRE obligatorio desde que nace la obligación; salida del NRUS → desde el 1.º del mes siguiente | onboarding / SIRE |
| 01/06/2026 | GRR transporte público exige fecha de entrega al transportista | validación GRR |
| 01/08/2026 | Cat. 01 nuevos 25/28/56 (DAE, DAE-SEAE); Cat. 51: 0101 = "Venta interna no sujeta a Detracción o Percepción"; RA admite 25/28/56; RR de liquidaciones 5 días | P2 (no implementado: DAE, LC) |
| 01/09/2026 | Cat. 54 nuevos 038, 042, 043, 046, 047 | detracción |
| 01/01/2027 (postergado desde 01/08/2026 por R.S. 000141-2026 / regla [562] del 24.07.2026) | ERR-3496 código de producto 8 dígitos válido (Cat. 25 UNSPSC v14); ERR-3261 NC un solo documento; ND motivo 13 (ERR-2524/3194/3507); contrato de colaboración `cac:ContractDocumentReference` (ERR-3497…3502); Cat. 11 RC gratuitas 06–09 (se desactiva 05) y nuevo campo "Tasa del IGV" en RC (ERR-2992/3504/3102); NC sobre boleta OBS-4028 → ERR-3286; ERR-3503 | P0/P1 ya implementados en API-PRO (salvo cambios de RC — verificar) |

Archivos fuente: `actualizaciones-sunat/Reglas de validación - actualizado al 24.07.2026.xlsx` (hojas por tipo: Factura2_0, Boleta2_0, NotaCredito2_0, NotaDebito2_0, Resumen Diario1_1, Comunicación de Baja1_0, Retenciones1_0, Percepciones1_0, Resumen de reversiones1_0, CódigosRetorno, Catálogos 01–65, Control de Cambios), `RESOLUCIÓN DE SUPERINTENDENCIA.pdf` (R.S. 000075-2026 del 29/04/2026), `CCNU_MOD_2.xlsm` (buscador UNSPSC v14, ~49 000 filas, base del catálogo 25).

### 3.6 Decisiones de diseño / arquitectura (para replicar o decidir)

- **Multi-tenant** por empresa (RUC); entorno por tenant; todo filtrado por `tenant_id` (global scope activo solo en API); correlativos con lock + índice único `(tenant_id, serie, correlativo)`; tablas separadas por tipo (`invoices`, `boletas`, `credit_notes`, `debit_notes`, `dispatch_guides`, `summaries`, `voided_documents` — RA y RR comparten esta última con prefijo de identificador `RA-`/`RR-`; `retentions`, `perceptions`).
- **Colas**: `sunat` (crítica, rate-limited), `webhooks`, `mail`, `default`, SIRE `sire-poll|sire-heavy|sire-process`. Scheduler: `sunat:reintentar-pendientes`, `summaries:poll-pending`, `sire:poll-pending` (1 min), `sire:reconcile-all`, `certificados:verificar --alertar`, `ops:salud` (10 min), `ops:jobs-fallidos` (1 h), backups, `ResetMonthlyUsage`.
- **Almacenamiento**: disco privado `comprobantes` (S3/MinIO opcional), sin URLs públicas; ruta `{ruc}/{cod_local}/{fecha}/{tipo}/{xml|cdr}/…` (ver §4); certificado en `tenants/{id}/certs/cert.pem` (PFX/P12 convertido a PEM; `.cer` sin clave → rechazo con mensaje guía `-nodes`).
- **Licencia** (LICENSING.md): dos barreras independientes (middleware `license` en las 12 rutas de emisión → 403, y guard en firmado/envío `guardEmisionAutorizada()`); solo en `production`; `machine_id`, fingerprint `license:stamp`, gracia 7 días, servidor de licencias externo. Síntoma: `sunat:{estado:"rechazado",codigo:"CERT_ERROR",descripcion:"Emisión no disponible: …"}`.
- **Seguridad** (SEGURIDAD-REPO.md, auditoría 2026-10-15, 23/25 corregidos): SSRF en webhook, timing-safe en bridge/auth, sin SVG en logos, secretos enmascarados en `api_logs`, 500 sin `getMessage()`, registro público sin plan de pago, CORS/CSP, `trustProxies` restringido, disco de certificados no servido. ⚠ SEGURIDAD-REPO afirma que los alias `/api/sunat/*` pasan por licencia y tope de plan, pero en `routes/api.php` ese grupo solo declara `resolve.tenant` + `throttle:api` → **verificar en controlador** antes de decidir si se replican.
- **Panel multiusuario** (PLAN-PANEL-MULTIUSUARIO.md): RBAC super_admin/owner/cajero con abilities `{recurso}.{acción}`; solo afecta al panel web (los tests Rbac). Fuera del contrato API.
- **Bugs documentados a no replicar**: recuperación de credenciales guardaba `hash(secret)` mientras el middleware compara en claro (credenciales recuperadas no autenticaban — revisar si sigue); GRE 401 por leer solo `client_id` (resuelto con fallback); CDR de guías 404 (ruta añadida); 3xxx como observación (fix 30); reintento de `pendiente` intencional (fix 29).

### 3.7 `.scribe/`

- `auth.md`/`intro.md`: auth por headers; base URL. `endpoints/custom.0.yaml`: endpoints añadidos a mano. `endpoints/00.yaml` (640 KB): documentación autogenerada de todas las rutas `/api/v1` con reglas de validación extraídas de los FormRequest y **algunas respuestas reales capturadas** (cabeceras incluidas: `x-ratelimit-limit: 30`, etc.). Útil como fuente secundaria de reglas de validación (ojo: Scribe muestra `tipo_documento in:1,3,7,8` por un problema de parseo; el real es `01,03,07,08`). Detalle en Apéndice B2.

### 3.8 Contradicciones entre fuentes (resumen)

1. Auth por query string: PROMPT sí / código no.
2. Paginación: `meta` (colección) vs `datos.paginacion` (docs) vs `paginacion` al nivel raíz (resúmenes) vs `meta` (anulaciones); `por_pagina` vs `per_page`.
3. Estado inicial al crear: `pendiente` vs `enviado`.
4. CDR 3xxx: rechazo (fix 30, tests) vs observación (texto de actualizaciones).
5. `entorno`: `production` (código) vs `produccion` (ejemplos).
6. Webhook: `url_webhook` (API) vs `webhook_url` (panel/colección/otros docs).
7. GRT en beta: "no funciona" (colección) vs advertencia `grt_beta_no_soportado` (PROMPT) vs aceptadas en el servidor de pruebas (storage).
8. NC anulable por baja: no (notas-credito.md) vs sí, tipo 07 en `/anulaciones` (anulaciones.md, colección, tests de bajas).
9. Régimen `mype_restaurantes`: eliminado (docs, `TaxRateRegimenTest`) vs aún testeado (`TaxRateServiceTest`).
10. Reintentos: 10 intentos 15–600 s (envio-manual.md) vs `tries=20` ~15 h (código/resiliencia).
11. Catálogos 06/09/10/61 de la API vs los oficiales SUNAT (ver 3.3).
12. Descripciones de percepción 1 %/2 % invertidas en la colección.

---


---

## 4. XML firmados y CDR de referencia (candidatos a "pruebas doradas")

Búsqueda en todo el repo excepto `vendor/`, `node_modules/` y `.git/`: **352** archivos `.xml`/`.zip`. `tests/`, `docs/` y `documentacion/` **no contienen** XML/CDR. Clasificación automática hecha con un script PHP (solo lectura) que lee el tipo raíz UBL, `InvoiceTypeCode`/`listID` (tipo de operación), códigos de afectación (`TaxExemptionReasonCode`), marcadores (detracción, percepción, ICBPER 7152, ISC 2000, IVAP 1016, gratuitas 9996, anticipos, descuentos, USD, guía relacionada), presencia de firma (`SignatureValue` no vacío) y el `ResponseCode` del CDR emparejado (`R-<nombre>.zip`).

> Advertencias: (1) estos archivos son **salida de API-PRO** (Greenter), no XML "oficiales" de SUNAT: sirven como referencia de **equivalencia** (la nueva implementación debe producir XML semánticamente equivalentes, salvo firma/fecha-hora/DigestValue), no como verdad absoluta; (2) todos son del **entorno beta** (CDR con código 0; en GRE el CDR trae `DocumentDescription` = `https://url-test?hashqr=test`); (3) contienen datos de clientes ficticios, pero el emisor `<RUC-REAL>` es un RUC real usado en pruebas → tratarlos como datos internos, no publicar; (4) para comparar hay que normalizar: quitar `ext:UBLExtensions/…/ds:Signature`, `cbc:IssueTime`, y comparar por XPath/valores, no texto.

### 4.1 Emisor `20123456789` (tenant de pruebas; firma `SIGN20123456789`), fecha 2026-08-24/26

Ruta base: `C:\laragon\www\API-PRO\storage\app\public\20123456789\0000\2026-08-24\` (XML en `facturas/xml`, `boletas/xml`, `xml/`; CDR en `…/cdr/R-*.zip`).

Este lote parece la **ejecución de la carpeta 04 de la colección** en orden (F001-1…25) — es el mejor candidato para emparejar request Postman ↔ XML esperado:

| XML | Tipo / op | Afectación | Marcadores | CDR |
|---|---|---|---|---|
| `facturas/xml/F001-000001…000005.xml` | 01 / 0101 | 10 | gravada básica | code 0 |
| `facturas/xml/F001-000006.xml`, `F001-000009.xml` | 01 / 0101 | 10 | CRÉDITO (cuotas) | 0 |
| `facturas/xml/F001-000007.xml`, `F001-000020.xml` | 01 / 0101 | 10 | ISC | 0 |
| `facturas/xml/F001-000008.xml`, `F001-000025.xml` | 01 / 0101 | 10 | — | 0 |
| `facturas/xml/F001-000010.xml` | 01 / 0101 | 20 | exonerada | 0 |
| `facturas/xml/F001-000012.xml` | 01 / 0101 | 30 | inafecta | 0 |
| `facturas/xml/F001-000013.xml` | 01 / 0101 | 11 | GRATUITA | 0 |
| `facturas/xml/F001-000014.xml` | 01 / **1001** | 10 | DETRACCIÓN | 0 |
| `facturas/xml/F001-000015.xml` | 01 / 0101 | 10 | ANTICIPO + descuento 04 | 0 |
| `facturas/xml/F001-000016.xml`, `F001-000017.xml` | 01 / 0101 | 10 | DESCUENTO (global / por línea) | 0 |
| `facturas/xml/F001-000018.xml` | 01 / 0101 | 10 | DESCUENTO (no afecta base) | **sin CDR** |
| `facturas/xml/F001-000019.xml` | 01 / 0101 | 10 | ICBPER | 0 |
| `facturas/xml/F001-000021.xml` | 01 / 0101 | 17 | IVAP | 0 |
| `facturas/xml/F001-000022.xml` | 01 / 0101 | 10 | GUÍA relacionada | 0 |
| `facturas/xml/F001-000023.xml` | 01 / 0101 | 10,20,30,13,32 | compleja + gratuitas | 0 |
| `facturas/xml/F001-000024.xml` | 01 / 0101 | 10,13 | ICBPER + gratuito | **sin CDR** |
| `boletas/xml/B001-000001,3,4,5.xml` (+ `2026-08-26/boletas/xml/B001-000006.xml`) | 03 / 0101 | 10 | boleta | 0 |
| `xml/RC-20260824-001.xml`, `xml/RC-20260824-002.xml` | SummaryDocuments (UBL 2.0) | | resumen diario | `cdr/R-RC-20260824-00{1,2}.zip` code 0 |
| `xml/RA-20260824-001.xml` | VoidedDocuments | | comunicación de baja | sin CDR |

### 4.2 Emisor `<RUC-REAL>`, beta (marzo–septiembre 2026) — cubre TODOS los tipos

Ruta base: `C:\laragon\www\API-PRO\storage\app\public\<RUC-REAL>\` → `0000\<fecha>\{facturas|boletas|notas_credito|notas_debito|guias}\{xml|cdr}\` y `0000\<fecha>\{xml|cdr}\` para RC/RA; retenciones/percepciones/reversiones en carpetas planas `retentions\`, `perceptions\`, `reversions\` (XML y `R-*.zip` juntos). Estructura de almacenamiento a replicar: `{ruc}/{cod_local}/{fecha_emision}/{tipo}/{xml|cdr}/{SERIE}-{correlativo 6 dígitos}.xml` y `R-{…}.zip`.

Mejor ejemplo por tipo de documento (todos firmados; CDR code 0 salvo indicación):

| Tipo pedido | Archivo(s) (relativo a `storage\app\public\<RUC-REAL>\`) | Detalle |
|---|---|---|
| Factura gravada | `0000\2026-08-19\facturas\xml\F001-000602.xml` (con `cod_producto_sunat`), `0000\2026-09-08\facturas\xml\F001-000635…639.xml`, `0000\2026-09-09\facturas\xml\F001-000640…642.xml` | op 0101, afe 10. `F001-000601` (2026-08-23) es la "factura básica" con `correlativo: 601` del Postman (marcada con ISC por la heurística, revisar). |
| Factura con detracción | `0000\2026-05-24\facturas\xml\F001-000610.xml` | op 1001 (cuerpo Postman "Factura con detracción", 2026-05-24) |
| Factura con percepción | `0000\2026-04-25\facturas\xml\F001-000630…634.xml` | op 2001 (Postman carpeta 19, 2026-04-25) |
| Exportación | `0000\2026-08-04\facturas\xml\F001-000606.xml`, `F001-000607.xml` | op 0200, afe 40, USD |
| Gratuita | `0000\2026-06-21\facturas\xml\F001-000609.xml` (afe 11); `0000\2026-04-17\facturas\xml\F001-000621.xml` (10,20,30,13,32) | |
| Crédito / cuotas | `0000\2026-08-04\facturas\xml\F001-000603.xml` | |
| Exonerada / inafecta | `0000\2026-08-04\facturas\xml\F001-000604.xml` (20); `0000\2026-05-07\facturas\xml\F001-000608.xml` (30) | |
| Anticipo | `0000\2026-05-24\facturas\xml\F001-000611.xml`; boletas `0000\2026-09-10\boletas\xml\B001-000017.xml`, `0000\2026-09-15\boletas\xml\B001-000019…026.xml` (afe 10/20/30) | |
| Descuentos | `0000\2026-05-13\facturas\xml\F001-000612.xml`, `F001-000613.xml`; `0000\2026-04-20\facturas\xml\F001-000614…616.xml` (614 sin CDR) | |
| ICBPER / ISC / IVAP | `0000\2026-04-20\facturas\xml\F001-000617.xml` (ICBPER); `0000\2026-04-17\facturas\xml\F001-000618.xml` (ISC); `0000\2026-05-14\facturas\xml\F001-000619.xml` (IVAP) | |
| Factura + guía | `0000\2026-04-17\facturas\xml\F001-000620.xml` | |
| ICBPER + gratuito | `0000\2026-03-04\facturas\xml\F001-000622…624.xml` (622/623 sin CDR — reintentos) | |
| USD | `0000\2026-09-04\facturas\xml\F001-000628.xml` | |
| Boleta | `0000\2026-04-19\boletas\xml\B001-000004.xml`, `B001-000005.xml`; `0000\2026-05-11\boletas\xml\B001-000003.xml` (consumidor final); `0000\2026-08-05\boletas\xml\B001-000002.xml` (afe 20, cod. producto); `0000\2026-09-10\boletas\xml\B001-000018.xml` (10+30) | |
| Nota de crédito | `0000\2026-04-17\notas_credito\xml\FC01-000001.xml` (motivo 01), `FC01-000003/000006.xml` (06), `BC01-000001.xml` (sobre boleta); `0000\2026-06-25\notas_credito\xml\FC01-000002.xml` (04); `0000\2026-03-05\notas_credito\xml\FC01-000004/000007.xml` (USD); `0000\2026-09-09\notas_credito\xml\FC01-000010.xml` (07), `FC01-000011` (01), `FC01-000012` (06) | |
| Nota de débito | `0000\2026-05-05\notas_debito\xml\FD01-000001.xml` (01), `0000\2026-05-06\notas_debito\xml\FD01-000002.xml` (02), `0000\2026-03-05\notas_debito\xml\BD01-000001.xml` (sobre boleta), `FD01-000003.xml` (02); `0000\2026-09-09\notas_debito\xml\FD01-000006/000007.xml` | |
| Guía remitente (09) | `0000\2026-09-04\guias\xml\T001-000002…014.xml` (CDR en `guias\cdr\R-T001-*.zip`; sin CDR: 005, 009, 010); `0000\2026-09-09\guias\xml\T001-000015…020.xml` (020 sin CDR); `0000\2026-07-01\guias\xml\T001-000001.xml` (sin CDR) | CDR GRE: `R-<RUC-REAL>-09-T001-N.xml` dentro del zip |
| Guía transportista (31) | `0000\2026-09-09\guias\xml\V001-000001…018.xml` (006 sin CDR) | CDR `R-<RUC-REAL>-31-V001-1.xml`, `Description=ACEPTADA` (contradice la nota de la colección "no funciona en beta") |
| Resumen diario (RC) | `0000\2026-09-09\xml\RC-20260909-001…004.xml` + `0000\2026-09-09\cdr\R-RC-20260909-00N.zip` ("El Resumen diario RC-20260909-001, ha sido aceptado"); `0000\2026-09-04\xml\RC-20260904-001.xml` (sin CDR) | UBL 2.0 |
| Comunicación de baja (RA) | `0000\2026-09-08\xml\RA-20260908-001.xml`, `-002.xml` + `0000\2026-09-08\cdr\R-RA-20260908-00{1,2}.zip`; `-003/-004` sin CDR | UBL 2.0 |
| Retención (20) | `retentions\R001-1.xml`, `R001-2.xml`, `R001-3.xml` + `retentions\R-R001-N.zip` ("El Comprobante numero R001-1 ha sido aceptado") | UBL 2.0 |
| Percepción (40) | `perceptions\P001-2.xml`, `P001-3.xml`, `P001-4.xml` + `perceptions\R-P001-N.zip` | UBL 2.0 |
| Reversión (RR) | `reversions\RR-20260908-003…006.xml` + `reversions\R-RR-20260908-00N.zip` (aceptados); `RR-20260908-001/002.xml` sin CDR | UBL 2.0 |

### 4.3 Otros XML de referencia

| Ruta | Contenido | Uso |
|---|---|---|
| `C:\laragon\www\API-PRO\MANUAL PARA GUIAS DE REMISION REMITENTE Y TRASNPORTISTA\NUEVAS GRE SUNAT\20000000001-09-TTT1-1.xml`, `…-09-TTT1-2.xml` | XML GRE remitente de **ejemplo oficial SUNAT** (emisor 20000000001, serie TTT1), firmados | Referencia de estructura GRR 2.0 |
| `…\NUEVAS GRE SUNAT\20000000001-31-VVV1-1.xml` | XML GRE transportista de ejemplo SUNAT (serie VVV1), firmado | Referencia de estructura GRT |
| `…\NUEVAS GRE SUNAT-20260115T205344Z-1-001.zip` | Mismos 3 XML + manuales/validaciones (copia zip) | |
| `…\Archivo XSD.zip` | `UBL-DespatchAdvice-2.1.xsd` | Validación de esquema GRE |
| `…\Archivo XSL.zip` | `ValidaExprRegGreRemitente-2.0.1.xsl`, `ValidaExprRegGreTransportista-2.0.1.xsl` | Validaciones de SUNAT (reglas XSLT) — se pueden ejecutar en CI para validar XML de cualquier implementación |
| `C:\laragon\www\API-PRO\guia-trasportista\20161515648-31-V001-150.xml` (+ `.zip` con el mismo XML) | GRT firmada generada por el prototipo PHP de `guia-trasportista/` (emisor 20161515648) | Referencia secundaria |
| `C:\laragon\www\API-PRO\guia-trasportista\---.xml` | Plantilla GRT sin firmar/vacía | descartar |
| `C:\laragon\www\API-PRO\storage\app\private\<11 RUCs>\reversions\RR-20260924-001.xml` | Contenido `<xml/>` (artefactos de tests con Storage real) | **descartar** |

### 4.4 Archivos que NO deben usarse / sensibles

- `storage/app/private/API SUNAT/2026-10-03-17-33-43.zip` → backup con `db-dumps/mysql-api-sunat-v2.sql.gz` (**volcado de BD**; sensible, no usar ni copiar).
- `storage/app/private/tenants/{1..5}/certs/cert.pem`, `guia-trasportista/certificado.pem`, `guia-trasportista/certificado.pfx`, `guia-trasportista/token.txt`: certificados/clave privada/token → **no copiar**; para la suite común generar un certificado de prueba autofirmado propio (como hace `CertificateServiceTest`) o usar el certificado demo público de Greenter.
- `storage/app/private/sire/1/202604/uploads/LE2010000000120260408…zip` (7 ZIP): archivos TUS de SIRE generados en pruebas (RUC 20100000001, periodo 202604) — útiles solo para el módulo SIRE (nombre de archivo posicional).
- `public/build.zip`: build del frontend, irrelevante.

## 5. Recomendaciones para la base común de pruebas (Fase 0 → Fase 1)

1. **Corregir la colección** antes de congelarla como contrato: consultar-cpe (`fecha_emision` dd/mm/yyyy, `monto`), consultar-cdr (`tipo_documento`), `url_webhook`, comentario JSON en PUT /empresa, descripciones copiadas, `por_pagina`/`per_page`, request duplicada.
2. **Añadir environment** con `base_url`, `api_key`, `api_secret` y todas las `id_*`, y **scripts de test** (Newman) que: verifiquen status + `estado`; capturen `datos.id` en `id_factura`, `id_boleta`, `id_NC`, `id_ND`, `id_resumen`, `id_anular`, `id_guia`, `id_guia_t`; esperen (polling) estados finales.
3. **Eliminar dependencias de BD concreta**: sin `correlativo` fijo, sin IDs fijos en URLs, fechas relativas (`{{$isoTimestamp}}`/pre-request que calcule hoy en Lima) para que RC/RA no caigan fuera del plazo de 7 días; NC/ND/RA deben referenciar documentos creados en la misma corrida.
4. **Excluir** carpetas 21 y 22 y el reporte `documentos-internos`.
5. **Añadir requests faltantes**: `/api/health`, `/v1/sunat/estado`, `/facturas/masivo`, `/buscar-documento`, `/series/init-defaults`, recuperación de credenciales, `DELETE /empresa` (en entorno desechable), y casos negativos de los tests (DNI en factura, boleta en RA, PUT sobre aceptado, `forma_pago` inválida, Idempotency-Key replay/conflicto, auth por query → 401, headers de seguridad).
6. **Pruebas doradas de XML**: emparejar cada request de emisión de la colección con el XML de `storage/app/public/20123456789/0000/2026-08-24/` o `<RUC-REAL>/…` (§4), normalizar (sin firma, sin IssueTime) y comparar por XPath (totales, `TaxTotal`, `TaxExemptionReasonCode`, `InvoiceTypeCode@listID`, leyendas, `PaymentTerms`, `AllowanceCharge`); validar GRE con `UBL-DespatchAdvice-2.1.xsd` + `ValidaExprRegGre*.xsl`.
7. **Portar a las 7 tecnologías los tests unitarios de reglas** (§2.3): tasas IGV, leyenda en letras, fecha Lima, clasificación de códigos SUNAT, máquina de estados de bajas/RR/reintentos, HMAC de webhooks, idempotencia, TUS SIRE.
8. **Certificado de pruebas**: generar uno autofirmado para la suite (no reutilizar los PEM/PFX de `storage/` ni de `guia-trasportista/`).


---

## Apéndice A — Bodies JSON literales de la colección

Copiados del campo `raw` de cada request (sin cambios salvo secretos → `***`). Se omiten SIRE (bodies `{"filas":[]}`/`[]`), cotizaciones y notas de venta. Nota: algunos tienen comentarios `//` o líneas en blanco, tal como en Postman.

**[01. Configuración inicial] PUT Empresa (actualizar datos)** — `PUT /empresa`

```json
{
    "ruc": "20161515648",
    "razon_social": "MI EMPRESA SAC",
    "nombre_comercial": "MI EMPRESA",
    "direccion": "AV. PRINCIPAL 456",
    "ubigeo": "150101",
    "departamento": "LIMA",
    "provincia": "LIMA",
    "distrito": "LIMA",
    "telefonos": [
        "+51 999 888 777"
    ],
    "emails": [
        "contacto@miempresa.pe"
    ],
    "mensaje_agradecimiento": "Gracias por su compra",
    "webhook_url": "https://miempresa.pe/sunat-webhook",
    "entorno": "beta", // production
    "client_id": "test-85e5b0ae-255c-4891-a595-0b98c65c9854",
    "client_secret": "***"
}
```

**[- Sucursales (CRUD)] Crear sucursal** — `POST /sucursales`

```json
{
    "nombre": "Sucursal Principal",
    "cod_local": "0002",
    "direccion": "AV. PRINCIPAL 123, LIMA",
    "ubigeo": "150101",
    "es_principal": true,
    "telefono": "+51 999 888 777",
    "email": "principal@miempresa.pe"
}
```

**[- Sucursales (CRUD)] Crear sucursal secundaria** — `POST /sucursales`

```json
{
    "nombre": "Sucursal Norte",
    "cod_local": "0001",
    "direccion": "AV. ARGENTINA 1234, LIMA",
    "ubigeo": "150101",
    "es_principal": false
}
```

**[- Sucursales (CRUD)] Actualizar sucursal** — `PUT /sucursales/1`

```json
{
    "nombre": "Sucursal Principal Lima Centro",
    "telefono": "+51 999 111 222"
}
```

**[- Series (CRUD)] Crear series (todos los tipos)** — `POST /series`

```json
{
  "series": [
    {
      "tipo": "factura",
      "serie": "F001",
      "sucursal_id": 1,
      "correlativo_inicial": 0
    },
    {
      "tipo": "boleta",
      "serie": "B001",
      "sucursal_id": 1,
      "correlativo_inicial": 0
    },
    {
      "tipo": "nota_credito",
      "serie": "FC01",
      "sucursal_id": 1
    },
    {
      "tipo": "nota_debito",
      "serie": "FD01",
      "sucursal_id": 1
    },
    {
      "tipo": "guia_remision",
      "serie": "T001",
      "sucursal_id": 1
    },
    {
      "tipo": "guia_transportista",
      "serie": "V001",
      "sucursal_id": 1
    },
    {
      "tipo": "retencion",
      "serie": "R001",
      "sucursal_id": 1
    },
    {
      "tipo": "percepcion",
      "serie": "P001",
      "sucursal_id": 1
    }
  ]
}
```

**[- Series (CRUD)] Activar/desactivar serie** — `PUT /series/1`

```json
{
    "is_active": false
}
```

**[- Clientes (CRUD)] Crear cliente (RUC)** — `POST /clientes`

```json
{
    "tipo_documento": "6",
    "numero_documento": "20512345678",
    "razon_social": "EMPRESA DEMO SAC",
    "nombre_comercial": "DEMO",
    "direccion": "AV. AREQUIPA 1234, LIMA",
    "email": "contacto@demo.pe",
    "telefono": "+51 999 111 222"
}
```

**[- Clientes (CRUD)] Crear cliente persona natural (DNI)** — `POST /clientes`

```json
{
    "tipo_documento": "1",
    "numero_documento": "12345678",
    "razon_social": "JUAN PEREZ LOPEZ",
    "direccion": "JR. AYACUCHO 456, LIMA",
    "email": "juan@gmail.com"
}
```

**[- Clientes (CRUD)] Actualizar cliente** — `PUT /clientes/1`

```json
{
    "razon_social": "EMPRESA DEMO SAC ACTUALIZADA",
    "email": "nuevo@demo.pe"
}
```

**[02. Régimen tributario] Cambiar a régimen GENERAL (IGV 18%)** — `PUT /empresa`

```json
{
    "tax_regime": "general"
}
```

**[02. Régimen tributario] Cambiar a Régimen MYPE Tributario** — `PUT /empresa`

```json
{
    "tax_regime": "mype"
}
```

**[02. Régimen tributario] Cambiar a Nuevo RUS (rus) Categoría 1 (S/ 20)** — `PUT /empresa`

```json
{
    "tax_regime": "rus",
    "nrus_categoria": "1"
}
```

**[02. Régimen tributario] Cambiar a Nuevo RUS (rus) Categoría 2 (S/ 50)** — `PUT /empresa`

```json
{
    "tax_regime": "rus",
    "nrus_categoria": "2"
}
```

**[02. Régimen tributario] Override manual de tasa IGV (avanzado)** — `PUT /empresa`

```json
{
    "igv_rate_override": 18
}
```

**[03. Suscripción y planes] Crear / activar suscripción (con trial de 14 días)** — `POST /suscripcion`

```json
{
    "plan_slug": "business",
    "ciclo_facturacion": "monthly",
    "prueba": true
}
```

**[03. Suscripción y planes] Crear suscripción pagada** — `POST /suscripcion`

```json
{
    "plan_slug": "pro",
    "ciclo_facturacion": "yearly",
    "token": "***"
}
```

**[03. Suscripción y planes] Cambiar de plan** — `PUT /suscripcion/cambiar-plan`

```json
{
    "plan_slug": "business",
    "ciclo_facturacion": "monthly"
}
```

**[04. Facturas (Tipo 01)] Crear factura básica** — `POST /facturas`

```json
{
    "serie": "F001",
    "correlativo": 601,
    "fecha_emision": "2026-08-23",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "forma_pago": "Contado",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20601234567",
        "razon_social": "DISTRIBUIDORA COMERCIAL DEL PACIFICO S.A.C.",
        "direccion": "AV. REPUBLICA DE PANAMA NRO. 3565, SURQUILLO - LIMA"
    },
    "items": [
        {
            "codigo": "PROD-001",
            "descripcion": "Laptop Lenovo ThinkPad E14",
            "unidad": "NIU",
            "cantidad": 1,
            "precio_unitario": 2360.00,
            "tip_afe_igv": "10"
        }
    ]
}
```

**[04. Facturas (Tipo 01)] Crear factura, codigo producto** — `POST /facturas`

```json
{
  "serie": "F001",
  "fecha_emision": "2026-08-19",
  "tipo_operacion": "0101",
  "tipo_moneda": "PEN",
  "forma_pago": "Contado",
  "cliente": {
    "tipo_doc": "6",
    "num_doc": "20605145648",
    "razon_social": "TRANSPORTES SAN MARTIN S.A.C.",
    "direccion": "AV. INDUSTRIAL 456 - LIMA"
  },
  "items": [
    {
      "codigo": "SERV-001",
      "cod_producto_sunat": "78181500",
      "descripcion": "Mantenimiento preve",
      "unidad": "ZZ",
      "cantidad": 1,
      "precio_unitario": 354,
      "tip_afe_igv": "10"
    },
    {
      "codigo": "REP-045",
      "cod_producto_sunat": "43211508",
      "descripcion": "Scanner automotriz",
      "unidad": "NIU",
      "cantidad": 1,
      "precio_unitario": 118,
      "tip_afe_igv": "10"
    }
  ]
}
```

**[04. Facturas (Tipo 01)] Crear factura crédito (con cuotas)** — `POST /facturas`

```json
{
    "serie": "F001",
    "fecha_emision": "2026-08-04",
    "fecha_vencimiento": "2026-10-04",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "forma_pago": "Credito",

    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20512345678",
        "razon_social": "CLIENTE SAC",
        "direccion": "AV. AREQUIPA 1234, LIMA"
    },

    "items": [
        {
            "codigo": "P001",
            "descripcion": "Producto",
            "unidad": "NIU",
            "cantidad": 10,
            "precio_unitario": 118,
            "tip_afe_igv": "10"
        }
    ],

    "cuotas": [
        {
            "monto": 590,
            "fecha_pago": "2026-09-04"
        },
        {
            "monto": 590,
            "fecha_pago": "2026-11-04"
        }
    ]
}
```

**[04. Facturas (Tipo 01)] Crear factura exonerada (servicios médicos)** — `POST /facturas`

```json
{
    "serie": "F001",
    "fecha_emision": "2026-08-04",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "forma_pago": "Contado",
    
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20512345678",
        "razon_social": "CLIENTE SAC",
        "direccion": "AV. AREQUIPA 1234, LIMA"
    },
    "items": [
        {
            "codigo": "SERV001",
            "descripcion": "Consulta médica especializada",
            "unidad": "ZZ",
            "cantidad": 1,
            "precio_unitario": 200,
            "tip_afe_igv": "20"
        }
    ]
}
```

**[04. Facturas (Tipo 01)] Crear factura exportación (tipo 0200)** — `POST /facturas`

```json
{
    "serie": "F001",
    "fecha_emision": "2026-08-04",
    "tipo_operacion": "0200",
    "tipo_moneda": "USD",
    "forma_pago": "Contado",
    
    "cliente": {
        "tipo_doc": "0",
        "num_doc": "0",
        "razon_social": "FOREIGN CLIENT INC",
        "direccion": "5TH AVENUE 1000, NEW YORK, USA"
    },
    "items": [
        {
            "codigo": "EXP001",
            "descripcion": "Producto exportación",
            "unidad": "NIU",
            "cantidad": 12,
            "precio_unitario": 50,
            "tip_afe_igv": "40"
        }
    ]
}
```

**[04. Facturas (Tipo 01)] Factura inafecta (Tipo 4)** — `POST /facturas`

```json
{
    "serie": "F001",
    "fecha_emision": "2026-05-07",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",

    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA X SAC",
        "direccion": "AV. AREQUIPA 1234, LIMA"
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "PROD 1",
            "unidad": "KGM",
            "cantidad": 2,
            "precio_unitario": 50,
            "tip_afe_igv": "30"
        }
    ]
}
```

**[04. Facturas (Tipo 01)] Factura gratuita** — `POST /facturas`

```json
{
    "serie": "F001",
    "fecha_emision": "2026-06-21",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA X SAC",
        "direccion": "AV. AREQUIPA 1234, LIMA"
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "Mause Razer",
            "unidad": "NIU",
            "cantidad": 2,
            "precio_unitario": 0,
            "mto_valor_unitario": 100,
            "tip_afe_igv": "11",
            "porcentaje_igv": 18
        }
    ]
}
```

**[04. Facturas (Tipo 01)] Factura con detracción** — `POST /facturas`

```json
{
    "serie": "F001",
    "fecha_emision": "2026-05-24",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA X SAC",
        "direccion": "AV. AREQUIPA 1234, LIMA"
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "Matenimiento preventivo de PC",
            "unidad": "ZZ",
            "cantidad": 1,
            "precio_unitario": 1000,
            "tip_afe_igv": "10"
        }
    ],
    "detraccion": {
        "tipo_operacion": "1001",
        "cod_bien": "014",
        "cod_medio_pago": "001",
        "cta_banco": "0004-3342343243",
        "porcentaje": 4.00,
        "monto": 37.76
    }
}
```

**[04. Facturas (Tipo 01)] Factura anticipo** — `POST /facturas`

```json
{
    "serie": "F001",
    "fecha_emision": "2026-05-24",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA X SAC",
        "direccion": "AV. AREQUIPA 1234, LIMA"
    },
    "anticipos": [
        {
            "tipo_doc": "02",
            "serie": "F001",
            "correlativo": "111",
            "monto": 100
        }
    ],
    "items": [
        {
            "codigo": "P001",
            "descripcion": "PROD 1",
            "unidad": "NIU",
            "cantidad": 2,
            "precio_unitario": 118,
            "tip_afe_igv": "10"
        }
    ]
}
```

**[04. Facturas (Tipo 01)] Factura descuento global** — `POST /facturas`

```json
{

    "serie": "F001",
    "fecha_emision": "2026-05-13",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA X SAC"
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "Cuadernos",
            "unidad": "NIU",
            "cantidad": 10,
            "precio_unitario": 2.36,
            "tip_afe_igv": "10"
        },
        {
            "codigo": "P002",
            "descripcion": "Radio",
            "unidad": "NIU",
            "cantidad": 1,
            "precio_unitario": 59.00,
            "tip_afe_igv": "10"
        }
    ],
    "descuentos_globales": [
        {
            "cod_tipo": "02",
            "monto": 3.00
        }
    ]
}
```

**[04. Facturas (Tipo 01)] Factura descuento por línea** — `POST /facturas`

```json
{
    "serie": "F001",
    "fecha_emision": "2026-05-13",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA X SAC"
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "Cuadernos",
            "unidad": "NIU",
            "cantidad": 10,
            "precio_unitario": 2.36,
            "tip_afe_igv": "10",
            "descuentos": [
                {
                    "cod_tipo": "00",
                    "monto": 4
                }
            ]
        },
        {
            "codigo": "P002",
            "descripcion": "Radio",
            "unidad": "NIU",
            "cantidad": 1,
            "precio_unitario": 59,
            "tip_afe_igv": "10"
        }
    ]
}
```

**[04. Facturas (Tipo 01)] Factura descuento que NO afecta base** — `POST /facturas`

```json
{
    "serie": "F001",
    "fecha_emision": "2026-04-20",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA X SAC"
    },
    "descuentos_globales": [
        {
            "cod_tipo": "03",
            "monto_base": 100,
            "factor": 0.1,
            "monto": 10
        }
    ],
    "items": [
        {
            "codigo": "P004",
            "descripcion": "PRODUCTO 4",
            "unidad": "NIU",
            "cantidad": 1,
            "precio_unitario": 118,
            "tip_afe_igv": "10"
        }
    ]
}
```

**[04. Facturas (Tipo 01)] Factura ICBPER (bolsas plásticas)** — `POST /facturas`

```json
{
    "serie": "F001",
    "fecha_emision": "2026-04-20",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA X SAC"
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "PRODUCTO 1",
            "unidad": "NIU",
            "cantidad": 2,
            "precio_unitario": 118,
            "tip_afe_igv": "10"
        },
        {
            "codigo": "P002",
            "descripcion": "BOLSA DE PLASTICO",
            "unidad": "NIU",
            "cantidad": 4,
            "precio_unitario": 0.059,
            "tip_afe_igv": "10",
            "mto_valor_unitario": 0.05,
            "factor_icbper": 0.20
        }
    ]
}
```

**[04. Facturas (Tipo 01)] Factura ISC (impuesto selectivo)** — `POST /facturas`

```json
{
    "serie": "F001",
    "fecha_emision": "2026-04-17",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA X SAC"
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "PROD CON ISC",
            "unidad": "NIU",
            "cantidad": 2,
            "precio_unitario": 138.06,
            "tip_afe_igv": "10",
            "porcentaje_isc": 17,
            "tip_sis_isc": "01"
        }
    ]
}
```

**[04. Facturas (Tipo 01)] Factura IVAP (arroz pilado)** — `POST /facturas`

```json
{
    "serie": "F001",
    "fecha_emision": "2026-05-14",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA X SAC"
    },
    "items": [
        {
            "codigo": "A001",
            "descripcion": "SACOS DE ARROZ",
            "unidad": "SA",
            "cantidad": 900,
            "precio_unitario": 104,
            "tip_afe_igv": "17",
            "porcentaje_igv": 4
        }
    ]
}
```

**[04. Facturas (Tipo 01)] Factura + Guía de Remisión** — `POST /facturas`

```json
 {
    "tipo_documento": "01",
    "serie": "F001",
    "fecha_emision": "2026-04-17",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "cliente": {
      "tipo_doc": "6",
      "num_doc": "20000000002",
      "razon_social": "EMPRESA X SAC"
    },
    "guias": [
      {
        "tipo_doc": "09",
        "nro_doc": "T001-2"
      }
    ],
    "items": [
      {
        "codigo": "P001",
        "descripcion": "PROD 1",
        "unidad": "NIU",
        "cantidad": 2,
        "precio_unitario": 118,
        "tip_afe_igv": "10"
      }
    ]
  }
```

**[04. Facturas (Tipo 01)] Factura compleja (todos los campos)** — `POST /facturas`

```json
{
    "tipo_documento": "01",
    "serie": "F001",
    "fecha_emision": "2026-04-17",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "cliente": {
      "tipo_doc": "6",
      "num_doc": "20000000002",
      "razon_social": "EMPRESA X SAC"
    },
    "items": [
      {
        "codigo": "P001",
        "descripcion": "PROD 1 - Gravado",
        "unidad": "NIU",
        "cantidad": 2,
        "precio_unitario": 118,
        "tip_afe_igv": "10"
      },
      {
        "codigo": "P002",
        "descripcion": "PROD 2 - Exonerado",
        "unidad": "KGM",
        "cantidad": 2,
        "precio_unitario": 50,
        "tip_afe_igv": "20"
      },
      {
        "codigo": "P003",
        "descripcion": "PROD 3 - Inafecto",
        "unidad": "NIU",
        "cantidad": 2,
        "precio_unitario": 100,
        "tip_afe_igv": "30"
      },
      {
        "codigo": "P004",
        "descripcion": "PROD 4 - Gratuita Gravada (Retiro)",
        "unidad": "NIU",
        "cantidad": 1,
        "precio_unitario": 0,
        "mto_valor_unitario": 100,
        "tip_afe_igv": "13",
        "porcentaje_igv": 18
      },
      {
        "codigo": "P005",
        "descripcion": "PROD 5 - Gratuita Inafecta (Retiro)",
        "unidad": "NIU",
        "cantidad": 2,
        "precio_unitario": 0,
        "mto_valor_unitario": 100,
        "tip_afe_igv": "32"
      }
    ]
  }
```

**[04. Facturas (Tipo 01)] Factura ICBPER + ítem gratuito** — `POST /facturas`

```json
{
    "tipo_documento": "01",
    "serie": "F001",
    "fecha_emision": "2026-03-04",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA X SAC"
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "PRODUCTO 1",
            "unidad": "NIU",
            "cantidad": 2,
            "precio_unitario": 118,
            "tip_afe_igv": "10"
        },
        {
            "codigo": "P002",
            "descripcion": "BOLSA - Gratuita",
            "unidad": "NIU",
            "cantidad": 2,
            "precio_unitario": 0,
            "mto_valor_unitario": 0.05,
            "tip_afe_igv": "13",
            "factor_icbper": 0.20
        }
    ]
}
```

**[04. Facturas (Tipo 01)] Factura con contingencia (modo offline)** — `POST /facturas`

```json
{
    "serie": "F001",
    "fecha_emision": "2026-03-04",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "enviar_automatico": false,
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA X SAC"
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "PROD 1",
            "unidad": "NIU",
            "cantidad": 2,
            "precio_unitario": 118,
            "tip_afe_igv": "10"
        }
    ]
}
```

**[04. Facturas (Tipo 01)] Factura envío manual (enviar_automatico=false)** — `POST /facturas`

```json
{
    "serie": "F001",
    "fecha_emision": "2026-04-19",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "forma_pago": "Contado",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20512345678",
        "razon_social": "CLIENTE SAC"
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "Producto",
            "unidad": "NIU",
            "cantidad": 1,
            "precio_unitario": 118,
            "tip_afe_igv": "10"
        }
    ],
    "enviar_automatico": false
}
```

**[04. Facturas (Tipo 01)] Registrar pago en factura** — `POST /facturas/1/pagos`

```json
{
    "monto": 118,
    "fecha": "2026-04-19",
    "metodo": "transferencia",
    "referencia": "OP 12345",
    "observacion": "Pago contra entrega"
}
```

**[04. Facturas (Tipo 01)] Registrar pago parcial** — `POST /facturas/1/pagos`

```json
{
    "monto": 50,
    "fecha": "2026-04-19",
    "metodo": "efectivo"
}
```

**[04. Facturas (Tipo 01)] Actualizar factura (corregir y reenviar)** — `PUT /facturas/1`

```json
{
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20512345678",
        "razon_social": "EMPRESA CORREGIDA SAC"
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "Producto corregido",
            "unidad": "NIU",
            "cantidad": 1,
            "precio_unitario": 118,
            "tip_afe_igv": "10"
        }
    ]
}
```

**[05. Boletas (Tipo 03)] Boleta básica (DNI, contado)** — `POST /boletas`

```json
{
    "serie": "B001",
    "fecha_emision": "2026-08-10",
    "tipo_moneda": "PEN",
    "forma_pago": "Contado",
    "cliente": {
        "tipo_doc": "1",
        "num_doc": "72243561",
        "razon_social": "CHAVEZ HUINCHO JORGE"
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "Lapicero",
            "unidad": "NIU",
            "cantidad": 2,
            "precio_unitario": 1.50,
            "tip_afe_igv": "20"
        }
    ],
    "solo_registro": true
}
```

**[05. Boletas (Tipo 03)] Boleta con codigo productos sunat** — `POST /boletas`

```json
{
    "serie": "B001",
    "fecha_emision": "2026-08-05",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "cliente": {
        "tipo_doc": "1",
        "num_doc": "45678912",
        "razon_social": "JUAN PEREZ ROJAS",
        "direccion": "JR. LOS OLIVOS 123"
    },
    "items": [
        {
            "codigo": "SERV-002",
            "cod_producto_sunat": "78181500",
            "descripcion": "Cambio de aceite y filtros",
            "unidad": "ZZ",
            "cantidad": 1,
            "precio_unitario": 90,
            "tip_afe_igv": "20"
        }
    ],
    "solo_registro": false
}
```

**[05. Boletas (Tipo 03)] Boleta consumidor final (sin DNI)** — `POST /boletas`

```json
{
    "serie": "B001",
    "fecha_emision": "2026-05-11",
    "cliente": {
        "tipo_doc": "0",
        "num_doc": "99999999",
        "razon_social": "CONSUMIDOR FINAL"
    },
    "items": [
        {
            "codigo": "P002",
            "descripcion": "Producto varios",
            "unidad": "NIU",
            "cantidad": 1,
            "precio_unitario": 12,
            "tip_afe_igv": "10"
        }
    ]
}
```

**[05. Boletas (Tipo 03)] Boleta NRUS (tipo_operacion 0113 — auto)** — `POST /boletas`

```json
{
    "serie": "B001",
    "fecha_emision": "2026-04-19",
    "cliente": {
        "tipo_doc": "1",
        "num_doc": "12345678",
        "razon_social": "CLIENTE NRUS"
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "Producto NRUS",
            "unidad": "NIU",
            "cantidad": 1,
            "precio_unitario": 50,
            "tip_afe_igv": "10"
        }
    ]
}
```

**[05. Boletas (Tipo 03)] Boleta MYPE Restaurantes (10.5% en 2026)** — `POST /boletas`

```json
{
    "serie": "B001",
    "fecha_emision": "2026-04-19",
    "cliente": {
        "tipo_doc": "1",
        "num_doc": "12345678",
        "razon_social": "CLIENTE RESTAURANTE"
    },
    "items": [
        {
            "codigo": "PLATO001",
            "descripcion": "Almuerzo ejecutivo",
            "unidad": "NIU",
            "cantidad": 1,
            "precio_unitario": 27.5,
            "tip_afe_igv": "10"
        }
    ]
}
```

**[05. Boletas (Tipo 03)] Boleta solo registro (envío vía resumen)** — `POST /boletas`

```json
{
    "serie": "B001",
    "fecha_emision": "2026-04-25",
    "cliente": {
        "tipo_doc": "1",
        "num_doc": "12345678",
        "razon_social": "CLIENTE"
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "Producto",
            "unidad": "NIU",
            "cantidad": 1,
            "precio_unitario": 10,
            "tip_afe_igv": "10"
        }
    ],
    "solo_registro": true
}
```

**[05. Boletas (Tipo 03)] Boleta envío manual (enviar_automatico=false)** — `POST /boletas`

```json
{
    "serie": "B001",
    "fecha_emision": "2026-04-19",
    "cliente": {
        "tipo_doc": "1",
        "num_doc": "12345678",
        "razon_social": "CLIENTE"
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "Producto",
            "unidad": "NIU",
            "cantidad": 1,
            "precio_unitario": 10,
            "tip_afe_igv": "10"
        }
    ],
    "enviar_automatico": false
}
```

**[05. Boletas (Tipo 03)] Boleta con anticipo** — `POST /boletas`

```json
{
    "serie": "B001",
    "fecha_emision": "2026-09-10",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "cliente": {
        "tipo_doc": "1",
        "num_doc": "12345678",
        "razon_social": "CLIENTE PRUEBA ANTICIPO",
        "direccion": "AV. LIMA 123"
    },
    "anticipos": [
        {
            "tipo_doc": "03",
            "serie": "B001",
            "correlativo": "50",
            "monto": 100
        }
    ],
    "items": [
        {
            "codigo": "P001",
            "descripcion": "PROD 1",
            "unidad": "NIU",
            "cantidad": 2,
            "precio_unitario": 118,
            "tip_afe_igv": "10"
        }
    ]
}
```

**[05. Boletas (Tipo 03)] Actualizar boleta** — `PUT /boletas/1`

```json
{
    "cliente": {
        "tipo_doc": "1",
        "num_doc": "12345678",
        "razon_social": "CLIENTE CORREGIDO"
    }
}
```

**[05. Boletas (Tipo 03)] Registrar pago en boleta** — `POST /boletas/1/pagos`

```json
{
    "monto": 35,
    "fecha": "2026-04-19",
    "metodo": "efectivo"
}
```

**[06. Notas de Crédito (Tipo 07)] NC - Anulación total de factura** — `POST /notas-credito`

```json
{
    "serie": "FC01",
    "fecha_emision": "2026-04-17",
    "doc_afectado_tipo": "01",
    "doc_afectado_serie": "F001",
    "doc_afectado_correlativo": "622",
    "cod_motivo": "01",
    "des_motivo": "Anulación de la operación",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20512345678",
        "razon_social": "EMPRESA DEMO SAC",
        "direccion": "Av. Los Olivos 123, Lima"
    },
    "items": [
        {
            "descripcion": "Producto de prueba",
            "unidad": "NIU",
            "cantidad": 2,
            "precio_unitario": 100.00,
            "tip_afe_igv": "10"
        }
    ]
}
```

**[06. Notas de Crédito (Tipo 07)] NC - Descuento parcial** — `POST /notas-credito`

```json
  {
    "serie": "FC01",
    "fecha_emision": "2026-06-25",

    "doc_afectado_tipo": "01",
    "doc_afectado_serie": "F001",
    "doc_afectado_correlativo": "618",

    "cod_motivo": "04",
    "des_motivo": "Descuento por volumen de compra",

    "cliente": {
      "tipo_doc": "6",
      "num_doc": "20000000001",
      "razon_social": "CLIENTE PRUEBA S.A.C."
    },
    "items": [
      {
        "descripcion": "Descuento Producto A",
        "unidad": "NIU",
        "cantidad": 1,
        "precio_unitario": 60.00,
        "tip_afe_igv": "10"
      }
    ]
  }
```

**[06. Notas de Crédito (Tipo 07)] NC - Corrección de precio / devolución parcial** — `POST /notas-credito`

```json
  {
    "serie": "FC01",
    "fecha_emision": "2026-04-17",
    "doc_afectado_tipo": "01",
    "doc_afectado_serie": "F001",
    "doc_afectado_correlativo": "621",
    "cod_motivo": "06",
    "des_motivo": "Devolución parcial de mercadería",
    "cliente": {
      "tipo_doc": "6",
      "num_doc": "20000000001",
      "razon_social": "CLIENTE PRUEBA S.A.C."
    },
    "items": [
      {
        "descripcion": "Producto devuelto X",
        "unidad": "NIU",
        "cantidad": 3,
        "precio_unitario": 25.00,
        "tip_afe_igv": "10"
      },
      {
        "descripcion": "Producto devuelto Y",
        "unidad": "NIU",
        "cantidad": 1,
        "precio_unitario": 80.00,
        "tip_afe_igv": "10"
      }
    ]
  }
```

**[06. Notas de Crédito (Tipo 07)] NC - En dólares (USD)** — `POST /notas-credito`

```json
{
    "serie": "FC01",
    "fecha_emision": "2026-03-05",
    "tipo_moneda": "USD",
    "doc_afectado_tipo": "01",
    "doc_afectado_serie": "F001",
    "doc_afectado_correlativo": "607",
    "cod_motivo": "01",
    "des_motivo": "Anulación de operación en dólares",
    "cliente": {
      "tipo_doc": "6",
      "num_doc": "20000000001",
      "razon_social": "CLIENTE PRUEBA S.A.C."
    },
    "items": [
      {
        "descripcion": "Consultoría internacional",
        "unidad": "ZZ",
        "cantidad": 1,
        "precio_unitario": 500.00,
        "tip_afe_igv": "10"
      }
    ]
  }
```

**[06. Notas de Crédito (Tipo 07)] NC - Anulación de boleta** — `POST /notas-credito`

```json
 {
    "serie": "BC01",
    "fecha_emision": "2026-04-17",
    "doc_afectado_tipo": "03",
    "doc_afectado_serie": "B001",
    "doc_afectado_correlativo": "4",
    "cod_motivo": "01",
    "des_motivo": "Anulación de la operación",
    "cliente": {
      "tipo_doc": "1",
      "num_doc": "12345678",
      "razon_social": "JUAN PEREZ"
    },
    "items": [
      {
        "descripcion": "Producto vendido",
        "unidad": "NIU",
        "cantidad": 1,
        "precio_unitario": 50.00,
        "tip_afe_igv": "10"
      }
    ]
  }
```

**[06. Notas de Crédito (Tipo 07)] NC envío manual (enviar_automatico=false)** — `POST /notas-credito`

```json
{
    "serie": "FC01",
    "fecha_emision": "2026-04-19",
    "tipo_moneda": "PEN",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20512345678",
        "razon_social": "CLIENTE SAC"
    },
    "doc_afectado_tipo": "01",
    "doc_afectado_serie": "F001",
    "doc_afectado_correlativo": "620",
    "cod_motivo": "01",
    "des_motivo": "Anulación de la operación",
    "items": [
        {
            "codigo": "P001",
            "descripcion": "Producto a anular",
            "unidad": "NIU",
            "cantidad": 1,
            "precio_unitario": 118,
            "tip_afe_igv": "10"
        }
    ],
    "enviar_automatico": false
}
```

**[06. Notas de Crédito (Tipo 07)] Actualizar NC rechazada** — `PUT /notas-credito/1`

```json
{
    "des_motivo": "Anulación corregida"
}
```

**[07. Notas de Débito (Tipo 08)] ND — Intereses por mora** — `POST /notas-debito`

```json
 {
    "serie": "FD01",
    "fecha_emision": "2026-05-05",
    "doc_afectado_tipo": "01",
    "doc_afectado_serie": "F001",
    "doc_afectado_correlativo": "611",
    "cod_motivo": "01",
    "des_motivo": "Intereses por mora",
    "cliente": {
      "tipo_doc": "6",
      "num_doc": "20000000001",
      "razon_social": "CLIENTE PRUEBA S.A.C."
    },
    "items": [
      {
        "descripcion": "Interés por pago fuera de plazo",
        "unidad": "ZZ",
        "cantidad": 1,
        "precio_unitario": 150.00,
        "tip_afe_igv": "10"
      }
    ]
  }
```

**[07. Notas de Débito (Tipo 08)] ND — Aumento de valor** — `POST /notas-debito`

```json
  {
    "serie": "FD01",
    "fecha_emision": "2026-05-06",

    "doc_afectado_tipo": "01",
    "doc_afectado_serie": "F001",
    "doc_afectado_correlativo": "612",


    "cod_motivo": "02",
    "des_motivo": "Aumento en el valor por servicio adicional",


    "cliente": {
      "tipo_doc": "6",
      "num_doc": "20000000001",
      "razon_social": "CLIENTE PRUEBA S.A.C."
    },

    "items": [
      {
        "descripcion": "Servicio de instalación adicional",
        "unidad": "ZZ",
        "cantidad": 1,
        "precio_unitario": 120.00,
        "tip_afe_igv": "10"
      }
    ]
  }
```

**[07. Notas de Débito (Tipo 08)] ND — Penalidad** — `POST /notas-debito`

```json
  {
    "serie": "FD01",
    "fecha_emision": "2026-03-05",
    "doc_afectado_tipo": "01",
    "doc_afectado_serie": "F001",
    "doc_afectado_correlativo": "613",
    "cod_motivo": "02",
    "des_motivo": "Aumento en el valor por servicio adicional",
    "cliente": {
      "tipo_doc": "6",
      "num_doc": "20000000001",
      "razon_social": "CLIENTE PRUEBA S.A.C."
    },
    "items": [
      {
        "descripcion": "Servicio de instalación adicional",
        "unidad": "ZZ",
        "cantidad": 1,
        "precio_unitario": 200.00,
        "tip_afe_igv": "10"
      }
    ]
  }
```

**[07. Notas de Débito (Tipo 08)] ND — Sobre boleta** — `POST /notas-debito`

```json
{
    "serie": "BD01",
    "fecha_emision": "2026-03-05",
    "doc_afectado_tipo": "03",
    "doc_afectado_serie": "B001",
    "doc_afectado_correlativo": "9",
    "cod_motivo": "01",
    "des_motivo": "Intereses por mora",
    "cliente": {
      "tipo_doc": "1",
      "num_doc": "12345678",
      "razon_social": "JUAN PEREZ"
    },
    "items": [
      {
        "descripcion": "Interés por pago tardío",
        "unidad": "ZZ",
        "cantidad": 1,
        "precio_unitario": 20.00,
        "tip_afe_igv": "10"
      }
    ]
  }
```

**[07. Notas de Débito (Tipo 08)] ND envío manual** — `POST /notas-debito`

```json
{
    "serie": "FD01",
    "fecha_emision": "2026-04-19",
    "tipo_moneda": "PEN",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20512345678",
        "razon_social": "CLIENTE SAC"
    },
    "doc_afectado_tipo": "01",
    "doc_afectado_serie": "F001",
    "doc_afectado_correlativo": "615",
    "cod_motivo": "01",
    "des_motivo": "Intereses por mora",
    "items": [
        {
            "codigo": "INT001",
            "descripcion": "Intereses por mora 30 días",
            "unidad": "ZZ",
            "cantidad": 1,
            "precio_unitario": 118,
            "tip_afe_igv": "10"
        }
    ],
    "enviar_automatico": false
}
```

**[07. Notas de Débito (Tipo 08)] Actualizar ND rechazada** — `PUT /notas-debito/1`

```json
{
    "des_motivo": "Intereses corregidos"
}
```

**[08. Resumen Diario (RC)] Crear resumen diario (envío de boletas pendientes)** — `POST /resumenes`

```json
{
    "fecha_resumen": "2026-08-10"
}
```

**[08. Resumen Diario (RC)] Crear resumen ANULACIÓN (anular boletas aceptadas)** — `POST /resumenes`

```json
{
    "fecha_resumen": "2026-04-19",
    "anular": [
        {
            "id": 15,
            "motivo": "Error en el monto del item"
        },
        {
            "id": 16,
            "motivo": "Cliente solicita anulación"
        }
    ]
}
```

**[08. Resumen Diario (RC)] Resumen envío manual (enviar_automatico=false)** — `POST /resumenes`

```json
{
    "fecha_resumen": "2026-04-19",
    "enviar_automatico": false
}
```

**[09. Comunicación de Baja (RA / RR)] Anular factura (RA estándar)** — `POST /anulaciones`

```json
{
    "fecha_generacion": "2026-08-05",
    "fecha_comunicacion": "2026-08-05",
    "detalles": [
        {
            "tipo_documento": "01",
            "serie": "F001",
            "correlativo": "13",
            "motivo": "Error en datos del cliente"
        }
    ]
}
```

**[09. Comunicación de Baja (RA / RR)] Anular múltiples documentos en lote** — `POST /anulaciones`

```json
{
    "fecha_generacion": "2026-04-19",
    "fecha_comunicacion": "2026-04-19",
    "detalles": [
        {
            "tipo_documento": "01",
            "serie": "F001",
            "correlativo": "5",
            "motivo": "Error cliente"
        },
        {
            "tipo_documento": "01",
            "serie": "F001",
            "correlativo": "6",
            "motivo": "Anulación solicitada"
        },
        {
            "tipo_documento": "07",
            "serie": "FC01",
            "correlativo": "2",
            "motivo": "NC errónea"
        },
        {
            "tipo_documento": "08",
            "serie": "FD01",
            "correlativo": "1",
            "motivo": "ND errónea"
        }
    ]
}
```

**[09. Comunicación de Baja (RA / RR)] Anular nota de crédito** — `POST /anulaciones`

```json
{
    "fecha_generacion": "2026-04-19",
    "detalles": [
        {
            "tipo_documento": "07",
            "serie": "FC01",
            "correlativo": "3",
            "motivo": "NC duplicada"
        }
    ]
}
```

**[09. Comunicación de Baja (RA / RR)] Anular envío manual (enviar_automatico=false)** — `POST /anulaciones`

```json
{
    "fecha_generacion": "2026-04-19",
    "detalles": [
        {
            "tipo_documento": "01",
            "serie": "F001",
            "correlativo": "7",
            "motivo": "Error"
        }
    ],
    "enviar_automatico": false
}
```

**[10. Guía Remisión Remitente (Tipo 09)] GRR — Transporte público** — `POST /guias-remision`

```json
{
    "serie": "T001",
    "fecha_emision": "2026-09-04",
    "destinatario": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA DESTINO SAC"
    },
    "cod_traslado": "01",
    "mod_traslado": "01",
    "fecha_traslado": "2026-09-04",
    "fecha_de_entrega_al_transportista": "2026-09-04",
    "peso_total": 12.5,
    "und_peso_total": "KGM",
    "partida_ubigeo": "150203",
    "partida_direccion": "AV ITALIA 123, RIMAC",
    "llegada_ubigeo": "150101",
    "llegada_direccion": "AV LIMA 456, CERCADO",
    "transportista": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "TRANSPORTES SAC",
        "nro_mtc": "0001"
    },
    "items": [
        {
            "codigo": "PROD1",
            "descripcion": "Producto de prueba",
            "cantidad": 2,
            "unidad": "NIU"
        }
    ]
}
```

**[10. Guía Remisión Remitente (Tipo 09)] GRR — Transporte privado** — `POST /guias-remision`

```json
{
    "serie": "T001",
    "fecha_emision": "2026-09-04",
    "destinatario": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA DESTINO SAC"
    },
    "cod_traslado": "01",
    "mod_traslado": "02",
    "fecha_traslado": "2026-09-04",
    "peso_total": 50,
    "und_peso_total": "KGM",
    "num_bultos": 5,
    "partida_ubigeo": "150203",
    "partida_direccion": "AV ITALIA 123, RIMAC",
    "llegada_ubigeo": "150101",
    "llegada_direccion": "AV LIMA 456, CERCADO",
    "vehiculo": {
        "placa": "ABC123",
        "secundarios": [
            {
                "placa": "XYZ789"
            }
        ]
    },
    "conductor": {
        "tipo": "Principal",
        "tipo_doc": "1",
        "num_doc": "44004400",
        "nombres": "ROBERTO",
        "apellidos": "RODRIGUEZ VALENCIA",
        "licencia": "0001122020"
    },
    "items": [
        {
            "codigo": "PROD1",
            "descripcion": "Laptop HP ProBook 450",
            "cantidad": 10,
            "unidad": "NIU"
        }
    ]
}
```

**[10. Guía Remisión Remitente (Tipo 09)] GRR — Vehículo menor M1/L** — `POST /guias-remision`

```json
{
    "serie": "T001",
    "fecha_emision": "2026-09-04",
    "destinatario": {
        "tipo_doc": "1",
        "num_doc": "44004400",
        "razon_social": "JUAN PEREZ GARCIA"
    },
    "cod_traslado": "01",
    "mod_traslado": "02",
    "fecha_traslado": "2026-09-04",
    "peso_total": 5,
    "und_peso_total": "KGM",
    "indicadores": [
        "SUNAT_Envio_IndicadorTrasladoVehiculoM1L"
    ],
    "partida_ubigeo": "150101",
    "partida_direccion": "AV LIMA 123, CERCADO",
    "llegada_ubigeo": "150203",
    "llegada_direccion": "JR COMERCIO 456, RIMAC",
    "items": [
        {
            "codigo": "PED001",
            "descripcion": "Paquete pequeño delivery",
            "cantidad": 1,
            "unidad": "ZZ"
        }
    ]
}
```

**[10. Guía Remisión Remitente (Tipo 09)] GRR — Entre establecimientos propios** — `POST /guias-remision`

```json
{
    "serie": "T001",
    "fecha_emision": "2026-09-04",
    "destinatario": {
        "tipo_doc": "6",
        "num_doc": "<RUC-REAL>",
        "razon_social": "<RAZÓN SOCIAL REAL>"
    },
    "cod_traslado": "04",
    "mod_traslado": "02",
    "indicadores": [
        "SUNAT_Envio_IndicadorTrasladoVehiculoM1L"
    ],
    "fecha_traslado": "2026-09-04",
    "peso_total": 200,
    "und_peso_total": "KGM",
    "num_bultos": 15,
    "partida_ubigeo": "150203",
    "partida_direccion": "Almacén Central - AV ITALIA 123, RIMAC",
    "partida_ruc": "<RUC-REAL>",
    "partida_cod_local": "0000",
    "llegada_ubigeo": "150101",
    "llegada_direccion": "Sucursal Lima - AV LIMA 456, CERCADO",
    "llegada_ruc": "<RUC-REAL>",
    "llegada_cod_local": "0001",
    "items": [
        {
            "codigo": "INV001",
            "descripcion": "Televisor Samsung 55 pulgadas",
            "cantidad": 10,
            "unidad": "NIU"
        },
        {
            "codigo": "INV002",
            "descripcion": "Refrigeradora LG 350L",
            "cantidad": 5,
            "unidad": "NIU"
        }
    ]
}
```

**[10. Guía Remisión Remitente (Tipo 09)] GRR — Múltiples conductores** — `POST /guias-remision`

```json
{
    "serie": "T001",
    "fecha_emision": "2026-09-04",
    "destinatario": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "DISTRIBUIDORA NORTE SAC"
    },
    "cod_traslado": "01",
    "mod_traslado": "02",
    "fecha_traslado": "2026-09-04",
    "peso_total": 500,
    "und_peso_total": "KGM",
    "num_bultos": 30,
    "partida_ubigeo": "150101",
    "partida_direccion": "AV ARGENTINA 1500, LIMA",
    "llegada_ubigeo": "130101",
    "llegada_direccion": "AV MANSICHE 500, TRUJILLO",
    "vehiculo": {
        "placa": "D4E567"
    },
    "conductores": [
        {
            "tipo": "Principal",
            "tipo_doc": "1",
            "num_doc": "44004400",
            "nombres": "CARLOS",
            "apellidos": "GOMEZ TORRES",
            "licencia": "Q44004400"
        },
        {
            "tipo": "Secundario",
            "tipo_doc": "1",
            "num_doc": "55005500",
            "nombres": "PEDRO",
            "apellidos": "LOPEZ RUIZ",
            "licencia": "Q55005500"
        }
    ],
    "items": [
        {
            "codigo": "FERT01",
            "descripcion": "Fertilizante NPK 50kg",
            "cantidad": 100,
            "unidad": "NIU"
        }
    ]
}
```

**[10. Guía Remisión Remitente (Tipo 09)] GRR envío manual (enviar_automatico=false)** — `POST /guias-remision`

```json
{
    "serie": "T001",
    "fecha_emision": "2026-09-04",
    "destinatario": {
        "tipo_doc": "6",
        "num_doc": "20512345678",
        "razon_social": "CLIENTE FINAL SAC"
    },
    "cod_traslado": "01",
    "mod_traslado": "02",
    "fecha_traslado": "2026-09-04",
    "peso_total": 100.5,
    "und_peso_total": "KGM",
    "partida_ubigeo": "150101",
    "partida_direccion": "AV. ALMACEN 100, LIMA",
    "llegada_ubigeo": "040101",
    "llegada_direccion": "AV. DESTINO 200, AREQUIPA",
    "vehiculo": {
        "placa": "ABC123"
    },
    "conductor": {
        "tipo_doc": "1",
        "num_doc": "12345678",
        "nombres": "JUAN",
        "apellidos": "PEREZ",
        "licencia": "Q12345678"
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "Cajas de productos",
            "unidad": "NIU",
            "cantidad": 10
        }
    ],
    "enviar_automatico": false
}
```

**[10. Guía Remisión Remitente (Tipo 09)] Actualizar guía rechazada** — `PUT /guias-remision/1`

```json
{
    "transportista": {
        "tipo_doc": "6",
        "num_doc": "20987654321",
        "razon_social": "TRANSPORTES CORREGIDO SAC"
    }
}
```

**[11. Guía Remisión Transportista (Tipo 31)] GRT — Transporte estándar (atajo)** — `POST /guias-remision-transportista`

```json
 {
    "serie": "V001",
    "fecha_emision": "2026-07-01",
    "remitente": {
      "tipo_doc": "6",
      "num_doc": "20512345678",
      "razon_social": "EMPRESA REMITENTE SAC"
    },
    "destinatario": {
      "tipo_doc": "6",
      "num_doc": "20987654321",
      "razon_social": "EMPRESA DESTINATARIA SAC"
    },
    "doc_relacionado": {
      "tipo_codigo": "09",
      "numero": "T001-2",
      "ruc_emisor": "20512345678"
    },
    "cod_traslado": "01",
    "mod_traslado": "01",
    "fecha_traslado": "2026-07-01",
    "peso_total": 500,
    "und_peso_total": "KGM",
    "num_bultos": 5,
    "partida_ubigeo": "150101",
    "partida_direccion": "AV. ALMACEN 100, LIMA",
    "llegada_ubigeo": "040101",
    "llegada_direccion": "AV. DESTINO 200, AREQUIPA",
    "vehiculo": {
      "placa": "ABC123",
      "nro_circulacion": "ABC321981R"
    },
    "conductor": [
      {
        "tipo_doc": "1",
        "num_doc": "12345678",
        "nombres": "JUAN",
        "apellidos": "PEREZ",
        "licencia": "Q12345678"
      }
    ],
    "items": [
      {
        "codigo": "M001",
        "descripcion": "Mercancia transportada",
        "unidad": "NIU",
        "cantidad": 10
      }
    ]
  }
```

**[11. Guía Remisión Transportista (Tipo 31)] GRT — Vía endpoint general (tipo_documento=31)** — `POST /guias-remision`

```json
{
    "tipo_documento": "31",
    "serie": "V001",
    "fecha_emision": "2026-04-19",
    "remitente": {
        "tipo_doc": "6",
        "num_doc": "20512345678",
        "razon_social": "EMPRESA REMITENTE SAC"
    },
    "destinatario": {
        "tipo_doc": "6",
        "num_doc": "20987654321",
        "razon_social": "EMPRESA DESTINATARIA SAC"
    },
    "doc_relacionado": {
        "tipo_codigo": "09",
        "numero": "T001-1",
        "ruc_emisor": "20512345678"
    },
    "cod_traslado": "01",
    "mod_traslado": "01",
    "fecha_traslado": "2026-04-20",
    "peso_total": 500,
    "und_peso_total": "KGM",
    "partida_ubigeo": "150101",
    "partida_direccion": "AV. ALMACEN 100, LIMA",
    "llegada_ubigeo": "040101",
    "llegada_direccion": "AV. DESTINO 200, AREQUIPA",
    "vehiculo": {
        "placa": "ABC123",
        "nro_circulacion": "ABC321981R"
    },
    "conductor": [
        {
            "tipo_doc": "1",
            "num_doc": "12345678",
            "nombres": "JUAN",
            "apellidos": "PEREZ",
            "licencia": "Q12345678"
        }
    ],
    "items": [
        {
            "codigo": "M001",
            "descripcion": "Mercancia",
            "unidad": "NIU",
            "cantidad": 10
        }
    ]
}
```

**[11. Guía Remisión Transportista (Tipo 31)] GRT — Subcontratado (yo subcontraté a otro transportista)** — `POST /guias-remision-transportista`

```json
{
    "serie": "V001",
    "fecha_emision": "2026-04-19",
    "remitente": {
        "tipo_doc": "6",
        "num_doc": "20512345678",
        "razon_social": "EMPRESA REMITENTE SAC"
    },
    "destinatario": {
        "tipo_doc": "6",
        "num_doc": "20987654321",
        "razon_social": "EMPRESA DESTINATARIA SAC"
    },
    "doc_relacionado": {
        "tipo_codigo": "09",
        "numero": "T001-1",
        "ruc_emisor": "20512345678"
    },
    "cod_traslado": "01",
    "mod_traslado": "01",
    "fecha_traslado": "2026-04-20",
    "peso_total": 500,
    "und_peso_total": "KGM",
    "partida_ubigeo": "150101",
    "partida_direccion": "AV. ALMACEN 100",
    "llegada_ubigeo": "040101",
    "llegada_direccion": "AV. DESTINO 200",
    "vehiculo": {
        "placa": "ABC123"
    },
    "conductor": [
        {
            "tipo_doc": "1",
            "num_doc": "12345678",
            "nombres": "JUAN",
            "apellidos": "PEREZ",
            "licencia": "Q12345678"
        }
    ],
    "items": [
        {
            "codigo": "M001",
            "descripcion": "Mercancia",
            "unidad": "NIU",
            "cantidad": 10
        }
    ],
    "datos_subcontratador": {
        "tipo_doc": "6",
        "num_doc": "20111222333",
        "razon_social": "TRANSPORTES SUBCONTRATADO SAC"
    },
    "datos_pagador_flete": {
        "tipo": "subcontratador"
    }
}
```

**[11. Guía Remisión Transportista (Tipo 31)] GRT — Pagador del flete = tercero** — `POST /guias-remision-transportista`

```json
{
    "serie": "V001",
    "fecha_emision": "2026-04-19",
    "remitente": {
        "tipo_doc": "6",
        "num_doc": "20512345678",
        "razon_social": "EMPRESA REMITENTE SAC"
    },
    "destinatario": {
        "tipo_doc": "6",
        "num_doc": "20987654321",
        "razon_social": "EMPRESA DESTINATARIA SAC"
    },
    "doc_relacionado": {
        "tipo_codigo": "09",
        "numero": "T001-1",
        "ruc_emisor": "20512345678"
    },
    "cod_traslado": "01",
    "mod_traslado": "01",
    "fecha_traslado": "2026-04-20",
    "peso_total": 500,
    "und_peso_total": "KGM",
    "partida_ubigeo": "150101",
    "partida_direccion": "AV. ALMACEN 100",
    "llegada_ubigeo": "040101",
    "llegada_direccion": "AV. DESTINO 200",
    "vehiculo": {
        "placa": "ABC123"
    },
    "conductor": [
        {
            "tipo_doc": "1",
            "num_doc": "12345678",
            "nombres": "JUAN",
            "apellidos": "PEREZ",
            "licencia": "Q12345678"
        }
    ],
    "items": [
        {
            "codigo": "M001",
            "descripcion": "Mercancia",
            "unidad": "NIU",
            "cantidad": 10
        }
    ],
    "datos_pagador_flete": {
        "tipo": "tercero",
        "tipo_doc": "6",
        "num_doc": "20444555666",
        "razon_social": "EMPRESA QUE PAGA EL FLETE SAC"
    }
}
```

**[11. Guía Remisión Transportista (Tipo 31)] GRT — Múltiples conductores y vehículos secundarios** — `POST /guias-remision-transportista`

```json
{
    "serie": "V001",
    "fecha_emision": "2026-04-19",
    "remitente": {
        "tipo_doc": "6",
        "num_doc": "20512345678",
        "razon_social": "EMPRESA REMITENTE SAC"
    },
    "destinatario": {
        "tipo_doc": "6",
        "num_doc": "20987654321",
        "razon_social": "EMPRESA DESTINATARIA SAC"
    },
    "doc_relacionado": {
        "tipo_codigo": "09",
        "numero": "T001-1",
        "ruc_emisor": "20512345678"
    },
    "cod_traslado": "01",
    "mod_traslado": "01",
    "fecha_traslado": "2026-04-20",
    "peso_total": 1000,
    "und_peso_total": "KGM",
    "partida_ubigeo": "150101",
    "partida_direccion": "AV. ALMACEN 100",
    "llegada_ubigeo": "040101",
    "llegada_direccion": "AV. DESTINO 200",
    "vehiculo": {
        "placa": "ABC123",
        "nro_circulacion": "ABC321981R",
        "secundarios": [
            {
                "placa": "XYZ456",
                "nro_circulacion": "ABC321981R"
            }
        ]
    },
    "conductor": [
        {
            "tipo_doc": "1",
            "num_doc": "12345678",
            "nombres": "JUAN",
            "apellidos": "PEREZ",
            "licencia": "Q12345678"
        },
        {
            "tipo_doc": "1",
            "num_doc": "87654321",
            "nombres": "PEDRO",
            "apellidos": "GOMEZ",
            "licencia": "Q87654321"
        }
    ],
    "items": [
        {
            "codigo": "M001",
            "descripcion": "Mercancia",
            "unidad": "NIU",
            "cantidad": 20
        }
    ]
}
```

**[11. Guía Remisión Transportista (Tipo 31)] GRT — DAM (importación/exportación)** — `POST /guias-remision-transportista`

```json
{
    "serie": "V001",
    "fecha_emision": "2026-04-19",
    "remitente": {
        "tipo_doc": "6",
        "num_doc": "20512345678",
        "razon_social": "IMPORTADORA SAC"
    },
    "destinatario": {
        "tipo_doc": "6",
        "num_doc": "20987654321",
        "razon_social": "CLIENTE NACIONAL SAC"
    },
    "doc_relacionado": {
        "tipo_codigo": "50",
        "numero": "118-2026-10-123456"
    },
    "cod_traslado": "08",
    "mod_traslado": "01",
    "fecha_traslado": "2026-04-20",
    "peso_total": 2000,
    "und_peso_total": "KGM",
    "partida_ubigeo": "070101",
    "partida_direccion": "PUERTO DEL CALLAO",
    "llegada_ubigeo": "150101",
    "llegada_direccion": "AV. ALMACEN CLIENTE 100",
    "vehiculo": {
        "placa": "ABC123"
    },
    "conductor": [
        {
            "tipo_doc": "1",
            "num_doc": "12345678",
            "nombres": "JUAN",
            "apellidos": "PEREZ",
            "licencia": "Q12345678"
        }
    ],
    "items": [
        {
            "codigo": "IMP001",
            "descripcion": "Mercancía importada",
            "unidad": "NIU",
            "cantidad": 50
        }
    ]
}
```

**[11. Guía Remisión Transportista (Tipo 31)] GRT — Envío manual** — `POST /guias-remision-transportista`

```json
{
    "serie": "V001",
    "fecha_emision": "2026-04-19",
    "remitente": {
        "tipo_doc": "6",
        "num_doc": "20512345678",
        "razon_social": "REMITENTE SAC"
    },
    "destinatario": {
        "tipo_doc": "6",
        "num_doc": "20987654321",
        "razon_social": "DESTINATARIO SAC"
    },
    "doc_relacionado": {
        "tipo_codigo": "09",
        "numero": "T001-1",
        "ruc_emisor": "20512345678"
    },
    "cod_traslado": "01",
    "mod_traslado": "01",
    "fecha_traslado": "2026-04-20",
    "peso_total": 500,
    "und_peso_total": "KGM",
    "partida_ubigeo": "150101",
    "partida_direccion": "AV. ALMACEN 100",
    "llegada_ubigeo": "040101",
    "llegada_direccion": "AV. DESTINO 200",
    "vehiculo": {
        "placa": "ABC123"
    },
    "conductor": [
        {
            "tipo_doc": "1",
            "num_doc": "12345678",
            "nombres": "JUAN",
            "apellidos": "PEREZ",
            "licencia": "Q12345678"
        }
    ],
    "items": [
        {
            "codigo": "M001",
            "descripcion": "Mercancia",
            "unidad": "NIU",
            "cantidad": 10
        }
    ],
    "enviar_automatico": false
}
```

**[13. Actualizar documentos] Corregir factura rechazada (cliente)** — `PUT /facturas/1`

```json
{
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20512345678",
        "razon_social": "EMPRESA CORREGIDA SAC",
        "direccion": "AV. NUEVA 456"
    }
}
```

**[13. Actualizar documentos] Corregir factura rechazada (items)** — `PUT /facturas/1`

```json
{
    "items": [
        {
            "codigo": "P001",
            "descripcion": "Producto corregido",
            "unidad": "NIU",
            "cantidad": 2,
            "precio_unitario": 118,
            "tip_afe_igv": "10"
        }
    ]
}
```

**[13. Actualizar documentos] Corregir boleta rechazada** — `PUT /boletas/1`

```json
{
    "cliente": {
        "tipo_doc": "1",
        "num_doc": "12345678",
        "razon_social": "JUAN PEREZ CORREGIDO"
    }
}
```

**[13. Actualizar documentos] Corregir nota de crédito** — `PUT /notas-credito/1`

```json
{
    "cod_motivo": "06",
    "des_motivo": "Devolución total — corregido"
}
```

**[13. Actualizar documentos] Corregir nota de débito** — `PUT /notas-debito/1`

```json
{
    "des_motivo": "Intereses recalculados"
}
```

**[13. Actualizar documentos] Corregir guía de remisión** — `PUT /guias-remision/1`

```json
{
    "serie": "T001",
    "fecha_emision": "2026-09-04",
    "destinatario": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA DESTINO SAC"
    },
    "cod_traslado": "01",
    "mod_traslado": "01",
    "fecha_traslado": "2026-09-04",
    "fecha_de_entrega_al_transportista": "2026-09-04",
    "peso_total": 12.5,
    "und_peso_total": "KGM",
    "partida_ubigeo": "150203",
    "partida_direccion": "AV ITALIA 123, RIMAC",
    "llegada_ubigeo": "150101",
    "llegada_direccion": "AV LIMA 456, CERCADO",
    "transportista": {
        "tipo_doc": "6",
        "num_doc": "20987654321",
        "razon_social": "TRANSPORTES CORREGIDO SAC",
        "placa": "XYZ789"
    },
    "items": [
        {
            "codigo": "PROD1",
            "descripcion": "Producto de prueba",
            "cantidad": 2,
            "unidad": "NIU"
        }
    ]
}
```

**[14. Consultar CPE / CDR] Consultar CDR (estado vía CDR)** — `POST /consultar-cdr`

```json
{
    "ruc_emisor": "20100000001",
    "tipo_doc": "01",
    "serie": "F001",
    "correlativo": "1"
}
```

**[14. Consultar CPE / CDR] Exportar comprobantes** — `GET /comprobantes/exportar-zip?fecha_desde=2026-05-01&fecha_hasta=2026-05-31&documentos=facturas`

```json
{
    "ruc_emisor": "20100000001",
    "tipo_doc": "01",
    "serie": "F001",
    "correlativo": "1"
}
```

**[18. Retenciones (Tipo 20)] Crear retención (régimen general 3%)** — `POST /retenciones`

```json
  {
      "serie": "R001",
      "fecha_emision": "2026-04-25",
      "regimen": "01",
      "tasa": 3,
      "proveedor": {
          "tipo_doc": "6",
          "num_doc": "20000000002",
          "razon_social": "EMPRESA X SAC"
      },
      "documentos": [
          {
              "tipo_doc": "01",
              "num_doc": "F001-000034",
              "fecha_emision": "2026-04-20",
              "moneda": "PEN",
              "imp_total": 236.00,
              "fecha_retencion": "2026-04-20",
              "imp_retenido": 7.08,
              "imp_pagar": 228.92,
              "pagos": [
                  {
                      "moneda": "PEN",
                      "importe": 236.00,
                      "fecha": "2026-04-20"
                  }
              ]
          }
      ]
  }
```

**[18. Retenciones (Tipo 20)] Crear retención múltiples documentos** — `POST /retenciones`

```json
{
    "serie": "R001",
    "fecha_emision": "2026-04-19",
    "cod_local": "0000",
    "proveedor": {
        "tipo_doc": "6",
        "num_doc": "20987654321",
        "razon_social": "PROVEEDOR SAC"
    },
    "regimen": "01",
    "tasa": 3,
    "documentos": [
        {
            "tipo_doc": "01",
            "num_doc": "F001-100",
            "fecha_emision": "2026-04-15",
            "imp_total": 590,
            "moneda": "PEN",
            "pagos": [
                {
                    "fecha": "2026-04-19",
                    "importe": 590
                }
            ],
            "fecha_retencion": "2026-04-19"
        },
        {
            "tipo_doc": "01",
            "num_doc": "F001-101",
            "fecha_emision": "2026-04-16",
            "imp_total": 1180,
            "moneda": "PEN",
            "pagos": [
                {
                    "fecha": "2026-04-19",
                    "importe": 1180
                }
            ],
            "fecha_retencion": "2026-04-19"
        }
    ]
}
```

**[18. Retenciones (Tipo 20)] Retención envío manual** — `POST /retenciones`

```json
{
    "serie": "R001",
    "fecha_emision": "2026-04-19",
    "cod_local": "0000",
    "proveedor": {
        "tipo_doc": "6",
        "num_doc": "20987654321",
        "razon_social": "PROVEEDOR SAC"
    },
    "regimen": "01",
    "tasa": 3,
    "documentos": [
        {
            "tipo_doc": "01",
            "num_doc": "F001-100",
            "fecha_emision": "2026-04-15",
            "imp_total": 1180,
            "moneda": "PEN",
            "pagos": [
                {
                    "fecha": "2026-04-19",
                    "importe": 1180
                }
            ],
            "fecha_retencion": "2026-04-19"
        }
    ],
    "enviar_automatico": false
}
```

**[19. Percepciones (Tipo 40)] 0.5% — Entre agentes de percepción** — `POST /facturas`

```json
{
    "serie": "F001",
    "fecha_emision": "2026-04-25",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA X SAC",
        "direccion": "AV. AREQUIPA 1234, LIMA"
    },
    "percepcion": {
        "cod_regimen": "03",
        "porcentaje": 0.5,
        "base": 236.00,
        "monto": 1.18
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "COMBUSTIBLE",
            "unidad": "GLL",
            "cantidad": 1,
            "precio_unitario": 236,
            "tip_afe_igv": "10"
        }
    ]
}
```

**[19. Percepciones (Tipo 40)] 1% — Venta de bienes (combustible, consumo masivo)** — `POST /facturas`

```json
{
    "serie": "F001",
    "fecha_emision": "2026-04-25",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA X SAC"
    },
    "percepcion": {
        "cod_regimen": "02",
        "porcentaje": 1,
        "base": 236.00,
        "monto": 2.36
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "BIEN DE CONSUMO MASIVO",
            "unidad": "NIU",
            "cantidad": 1,
            "precio_unitario": 236,
            "tip_afe_igv": "10"
        }
    ]
}
```

**[19. Percepciones (Tipo 40)] 2% — Sujeto no designado / consumidor final** — `POST /facturas`

```json
{
    "serie": "F001",
    "fecha_emision": "2026-04-25",
    "tipo_operacion": "0101",
    "tipo_moneda": "PEN",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20000000002",
        "razon_social": "EMPRESA X SAC",
        "direccion": "AV. AREQUIPA 1234, LIMA"
    },
    "percepcion": {
        "tipo_operacion": "2001",
        "cod_regimen": "01",
        "porcentaje": 2,
        "base": 236,
        "monto": 4.72
    },
    "items": [
        {
            "codigo": "P001",
            "descripcion": "MERCADERIA",
            "unidad": "NIU",
            "cantidad": 1,
            "precio_unitario": 236,
            "tip_afe_igv": "10"
        }
    ]
}
```

**[19. Percepciones (Tipo 40)] Crear percepción venta interna (2%)** — `POST /percepciones`

```json
{
    "serie": "P001",
    "fecha_emision": "2026-04-19",
    "cod_local": "0000",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20987654321",
        "razon_social": "CLIENTE SAC",
        "direccion": "AV. CLIENTE 100"
    },
    "regimen": "01",
    "tasa": 2,
    "observacion": "Percepción venta interna",
    "documentos": [
        {
            "tipo_doc": "01",
            "num_doc": "F001-100",
            "fecha_emision": "2026-04-15",
            "imp_total": 1180,
            "moneda": "PEN",
            "fecha_percepcion": "2026-04-19",
            "cobros": [
                {
                    "fecha": "2026-04-19",
                    "importe": 1180
                }
            ]
        }
    ]
}
```

**[19. Percepciones (Tipo 40)] Crear percepción combustible (1%)** — `POST /percepciones`

```json
{
    "serie": "P001",
    "fecha_emision": "2026-04-19",
    "cod_local": "0000",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20987654321",
        "razon_social": "CLIENTE SAC"
    },
    "regimen": "02",
    "tasa": 1,
    "observacion": "Percepción combustible",
    "documentos": [
        {
            "tipo_doc": "01",
            "num_doc": "F001-100",
            "fecha_emision": "2026-04-15",
            "imp_total": 5900,
            "moneda": "PEN",
            "fecha_percepcion": "2026-04-19",
            "cobros": [
                {
                    "fecha": "2026-04-19",
                    "importe": 5900
                }
            ]
        }
    ]
}
```

**[19. Percepciones (Tipo 40)] Percepción envío manual** — `POST /percepciones`

```json
{
    "serie": "P001",
    "fecha_emision": "2026-04-19",
    "cod_local": "0000",
    "cliente": {
        "tipo_doc": "6",
        "num_doc": "20987654321",
        "razon_social": "CLIENTE SAC"
    },
    "regimen": "01",
    "tasa": 2,
    "documentos": [
        {
            "tipo_doc": "01",
            "num_doc": "F001-100",
            "fecha_emision": "2026-04-15",
            "imp_total": 1180,
            "moneda": "PEN",
            "fecha_percepcion": "2026-04-19",
            "cobros": [
                {
                    "fecha": "2026-04-19",
                    "importe": 1180
                }
            ]
        }
    ],
    "enviar_automatico": false
}
```

**[20. Reversión (RR)] Reversión de retención** — `POST /reversiones`

```json
{
    "fecha_generacion": "2026-04-20",
    "fecha_comunicacion": "2026-04-20",
    "detalles": [
        {
            "tipo_documento": "20",
            "serie": "R001",
            "correlativo": "1",
            "motivo": "Error en datos del proveedor"
        }
    ]
}
```

**[20. Reversión (RR)] Reversión de percepción** — `POST /reversiones`

```json
{
    "fecha_generacion": "2026-04-19",
    "detalles": [
        {
            "tipo_documento": "40",
            "serie": "P001",
            "correlativo": "1",
            "motivo": "Cliente solicita anulación"
        }
    ]
}
```

**[20. Reversión (RR)] Reversión múltiple** — `POST /reversiones`

```json
{
    "fecha_generacion": "2026-04-19",
    "detalles": [
        {
            "tipo_documento": "20",
            "serie": "R001",
            "correlativo": "5",
            "motivo": "Error monto"
        },
        {
            "tipo_documento": "40",
            "serie": "P001",
            "correlativo": "3",
            "motivo": "Cliente anuló compra"
        }
    ]
}
```

**[20. Reversión (RR)] Reversión envío manual** — `POST /reversiones`

```json
{
    "fecha_generacion": "2026-04-19",
    "detalles": [
        {
            "tipo_documento": "20",
            "serie": "R001",
            "correlativo": "1",
            "motivo": "Error datos"
        }
    ],
    "enviar_automatico": false
}
```

---

## Apéndice B1 — Detalle de documentacion/emision, setup, referencia y docs/actualizaciones-sunat-2026.md

(Resumen exhaustivo por archivo con ejemplos JSON literales de las docs; secretos enmascarados.)

Leí completos los 18 archivos (unas 6.600 líneas) y no modifiqué nada en API-PRO. Abajo va el resumen por archivo y al final las contradicciones (§19) y los huecos (§20). Los secretos que aparecen en las docs van enmascarados como ***.

Antes de empezar, cuatro cosas que importan para reimplementar:
- **`codigo_error` y `siguiente_accion` no aparecen en ninguno de estos documentos.** El envelope de error documentado es solo `estado`, `mensaje` y `errores`. Si existen, están en el código o en otras docs (por ejemplo la "guía de primeros pasos (doc 37)", que no estaba en la lista).
- No se documentan endpoints de **emisión** de Retenciones/Percepciones. Solo aparecen como tipos anulables (20/40) en `/anulaciones`, como series R/P y como rutas `/enviar`.
- Base URL: `https://tu-api.com/api/v1`. Todo es JSON, salvo `/registro` y la subida de certificado/logo, que van en multipart.
- Envelope de éxito: `{"estado":"exito","mensaje":"...","datos":{...}}`. Envelope de error: `{"estado":"error","mensaje":"..."}`, más `errores` cuando hay 422.

---

#### 1. emision/facturas.md (Tipo 01)

##### Endpoints
| Método | Ruta | Uso |
|---|---|---|
| POST | `/facturas` | Crear |
| POST | `/facturas/masivo` | Lote: array `facturas`, máximo 100 |
| GET | `/facturas` | Listar |
| GET | `/facturas/{id}` | Ver (incluye `items` y `payments`) |
| PUT | `/facturas/{id}` | Actualizar (solo si no está aceptada) |
| GET | `/facturas/{id}/xml` | XML firmado |
| GET | `/facturas/{id}/cdr` | CDR |
| GET | `/facturas/{id}/pdf` | PDF |
| POST | `/facturas/{id}/reenviar` | Reenviar |
| POST | `/facturas/{id}/enviar` | Enviar (modo manual) |
| POST / GET | `/facturas/{id}/pagos` | Registrar / listar pagos |
| DELETE | `/facturas/{id}/pagos/{paymentId}` | Anular pago |

**Serie:** regex `^F[A-Z0-9]{3}$`. Una serie numérica como `0001` devuelve 422.

##### Campos raíz del POST
| Campo | Tipo | Oblig. | Default / valores |
|---|---|---|---|
| `serie` | string(4) | ✅ | Regex anterior |
| `correlativo` | integer | ❌ | `min:1`; si se omite, se autoasigna por serie y tenant |
| `sucursal_id` | integer | ❌ | Sin él, la factura no se asocia a ninguna sucursal |
| `cod_local` | string(4) | ❌ | Default: el de la sucursal principal |
| `fecha_emision` | date `yyyy-mm-dd` | ✅ | |
| `fecha_vencimiento` | date | Si `forma_pago=Credito` | |
| `tipo_operacion` | string | ❌ | Cat. 51, default `0101` |
| `tipo_moneda` | string | ❌ | `PEN` (default), `USD`, `EUR` |
| `forma_pago` | string | ❌ | `Contado` (default), `Credito` |
| `leyenda` | string(500) | ❌ | |
| `observacion` | string(500) | ❌ | |
| `contrato_colaboracion` | object | ❌ | Ver §17 |

Campos que se usan en los ejemplos pero no están en esa tabla:
- `tipo_documento: "01"` en el body.
- Pagos: `pagos[] {metodo, monto, referencia}`.
- Crédito: `cuotas[] {monto, fecha_pago}`, obligatorias si `forma_pago=Credito`.
- Guías relacionadas: `guias[] {tipo_doc:"09", nro_doc:"T001-123"}`.
- Detracción: `detraccion {cod_bien, porcentaje, cta_banco, cod_medio_pago, monto}`, con `tipo_operacion: "1001"`.
- Percepción: `percepcion {cod_regimen, porcentaje, monto, base}`, con `tipo_operacion: "2001"`.
- Anticipos: `anticipos[] {tipo_doc:"02", serie, correlativo, monto}` más `total_anticipos`.
- Leyendas: `leyendas[] {code, value}`, por ejemplo 1000 (monto en letras) y 2006 ("Operación sujeta a detracción").
- `descuentos_globales[]`.
- `cargos_globales[]`: **no está implementado y se ignora en silencio**. El doc recomienda cargar el flete como un ítem.
- `enviar_automatico` (ver §6).

##### Cliente
- `tipo_doc` ✅ (Cat. 06, normalmente `6`).
- `num_doc` ✅ (máximo 15).
- `razon_social` ✅ (máximo 1500).
- `direccion` ❌ (máximo 500).
- `email` y `telefono` ❌.
- Regla: el RUC tiene 11 dígitos y empieza con 10, 15, 17 o 20. En factura el cliente "normalmente" es RUC; las reglas finales dicen "Cliente RUC: obligatorio en factura".

##### Items
| Campo | Tipo | Oblig. | Notas |
|---|---|---|---|
| `codigo` | string(30) | ❌ | |
| `cod_producto_sunat` | string(8) | ❌ | UNSPSC; ver §17 |
| `descripcion` | string(500) | ✅ | |
| `unidad` | string | ✅ | UN/CEFACT |
| `cantidad` | numeric | ✅ | > 0 |
| `precio_unitario` | numeric | ✅ | **Con IGV** |
| `tip_afe_igv` | string | ❌ | Cat. 07, default `10` |
| `porcentaje_igv` | numeric | ❌ | Default 18 |
| `isc`, `porcentaje_isc`, `tip_sis_isc` | | ❌ | Sin más detalle en la doc |
| `icbper`, `factor_icbper` | numeric | ❌ | |
| `descuentos[]` | array | ❌ | |

Unidades listadas: NIU, KGM, LTR, MTR, ZZ (servicios), BG, BO, BX, DZN, PK, SET, HUR, DAY, MON.

##### Tributos según los ejemplos
- **Exonerado:** `tip_afe_igv: "20"`.
- **Gratuita / bonificación:** `tip_afe_igv: "15"`.
- **Exportación:** `tipo_operacion: "0200"`, `tip_afe_igv: "40"`, moneda USD.
- **ICBPER:** 5 bolsas, `unidad: "BG"`, `precio_unitario: 0.50`, `icbper: 2.50`, `factor_icbper: 0.50`. Es decir, `icbper = cantidad × factor`.
- **IVAP:** según tasas-igv, `tip_afe_igv=17` → 4%.

##### Descuentos por ítem (`items[].descuentos[]`)
Tres formas equivalentes:
- Solo `monto`.
- `porcentaje` en escala 0–100.
- Completa: `monto_base`, `factor`, `monto`.

| Campo | Tipo | Oblig. | Notas |
|---|---|---|---|
| `cod_tipo` | string | ❌ | `00` (default, afecta la base del IGV) o `01` (no la afecta) |
| `monto` | numeric | ✅* | Sin IGV; requerido si no se envía `porcentaje` ni `factor` |
| `porcentaje` | numeric | ✅* | 0–100 |
| `factor` | numeric | ✅* | Decimal (0.20 = 20%) |
| `monto_base` | numeric | ❌ | Lo calcula la API desde el valor de venta del ítem |

- Si llegan varios descuentos, se aplican en orden.
- Siempre debe cumplirse `monto = monto_base × factor`.
- La base es el valor de venta: `precio_unitario ÷ 1.18`.

Ejemplos de cálculo literales:
- Cuadernos: 10 × 2.36 → valor 20.00; descuento 20% = 4.00; neto 16.00; IGV 2.88; total 18.88.
- Radio: 59 → 50.00; IGV 9.00.
- Totales: gravadas 66.00, IGV 11.88, total 77.88.
- Con `cod_tipo 01`: IGV 3.60 (no cambia); total 23.60 − 4 = 19.60.
- Dos descuentos encadenados: 100 → −10 → 90 → −4.50 → 85.50; IGV 15.39; total 100.89.

Respuesta esperada (literal):
```json
{
  "mto_oper_gravadas": "66.00",
  "mto_igv":           "11.88",
  "mto_imp_venta":     "77.88"
}
```

##### Descuentos globales (`descuentos_globales[]`)
- `cod_tipo` ✅ (Cat. 53, normalmente `02`).
- `monto` ✅ **siempre** (`required_with`), sin IGV.
- `porcentaje` ❌, como **fracción 0–1** (`max:1`): 0.05 = 5%.
- `monto_base` y `factor` ❌ (los calcula la API).
- Enviar `{ "cod_tipo": "02", "porcentaje": 5 }` devuelve 422.

Ejemplo de cálculo:
- Base 70.00; descuento `02` de 3.00; factor 0.0429.
- Base gravada 67.00; IGV 12.06; total 79.06.
- Con `03` (no afecta la base): IGV 12.60; bruto 82.60; total 79.60.

Respuesta literal:
```json
{
  "mto_oper_gravadas": "67.00",
  "mto_igv":           "12.06",
  "mto_imp_venta":     "79.06"
}
```

**Cat. 53**
| Código | Descripción | Afecta IGV |
|---|---|---|
| 00 | Descuento ítem | Sí |
| 01 | Descuento ítem financiero | No |
| 02 | Descuento global | Sí |
| 03 | Descuento global financiero/comercial | No |
| 04 | Anticipo gravado | Sí |
| 05 | Anticipo exonerado | No |
| 06 | Anticipo inafecto | No |
| 47 | Cargo ítem | Sí |
| 48 | Cargo ítem financiero | No |
| 49 | Cargo global (flete/seguro) | Sí |
| 50 | Cargo global (intereses) | No |
| 51–53 | Percepciones | — |

Hay una contradicción interna en el propio doc: la tabla "Solo `porcentaje` → `"porcentaje": 10`" de la sección 11 choca con la regla de que en los globales `monto` es obligatorio y `porcentaje` va en 0–1.

##### Catálogos de detracción y percepción citados
- **Cat. 54:**
  - 019 Arrendamiento de bienes muebles: 10%.
  - 022 Otros servicios empresariales: 10%.
  - 027 Transporte de carga: 4%.
  - 030 Contratos de construcción: 4%.
  - 037 Demás servicios gravados: 10%.
  - 040 Bien inmueble gravado: 4%.
  - Ojo: tengo entendido que la tasa SPOT actual de 022 y 037 es **12%**; conviene verificarlo contra la norma SUNAT.
- **Cat. 59 (medios de pago):** 003 transferencia, 005 tarjeta débito, 006 tarjeta crédito, 008 efectivo, 009 efectivo demás casos.
- **Cat. 22 (percepción):** 01 venta interna 2%, 02 combustible 1%, 03 agente con tasa especial 0.5%.

##### Respuesta 201 del POST (literal)
```json
{
  "estado": "exito",
  "mensaje": "Factura creada y encolada para envío a SUNAT.",
  "datos": {
    "id": 123,
    "tipo_documento": "01",
    "serie": "F001",
    "correlativo": "00000123",
    "numero_completo": "F001-123",
    "fecha_emision": "2026-04-18",
    "cliente": {
      "tipo_doc": "6",
      "num_doc": "20555666777",
      "razon_social": "ACME CORP SAC"
    },
    "tipo_moneda": "PEN",
    "forma_pago": "Contado",
    "mto_oper_gravadas": "5977.00",
    "mto_igv": "1075.86",
    "mto_imp_venta": "7052.86",
    "leyenda": "SON SIETE MIL CINCUENTA Y DOS CON 86/100 SOLES",
    "sunat_status": "pendiente",
    "sunat_code": null,
    "sunat_description": null,
    "items": [...]
  }
}
```
- Los montos van como **strings** con 2 decimales.
- `correlativo` va relleno a 8 dígitos, pero `numero_completo` no lleva ceros.
- Este ejemplo **no cuadra** con el body: 2×2950 + 3×59 = 6077 con IGV, es decir gravadas 5150.00 e IGV 927.00.

**Estados (`sunat_status`):** `pendiente`, `enviado`, `aceptado`, `rechazado`, `anulado`, `anulacion_en_proceso`.

##### GET /facturas — query params
- Búsqueda: `search` / `q`, `serie` (exacta o con comodín `F0%`), `correlativo`, `correlativo_desde`, `correlativo_hasta`.
- Cliente: `client_num_doc`, `client_tipo_doc`, `cliente` (LIKE sobre razón social).
- Estados: `sunat_status` (CSV), `estado_pago` = `pendiente` | `parcial` | `pagado` (CSV).
- Otros filtros: `tipo_moneda`, `tipo_operacion`, `forma_pago`, `fecha_desde`, `fecha_hasta`, `vencimiento_desde`, `vencimiento_hasta`, `monto_min`, `monto_max`, `sucursal_id`, `con_cdr` (boolean).
- Relaciones: `con` = `items,payments`.
- Orden: `ordenar_por` = `fecha_emision` | `correlativo` | `mto_imp_venta` | `client_razon_social` | `sunat_status` | `created_at` (default); `orden` = `desc` (default) | `asc`.
- Paginación: `por_pagina` (default 15, máximo 100).

Respuesta (literal):
```json
{
  "estado": "exito",
  "datos": {
    "datos": [/* facturas */],
    "paginacion": {
      "pagina_actual": 1,
      "ultima_pagina": 8,
      "por_pagina": 15,
      "total": 112
    }
  }
}
```

##### PUT /facturas/{id}
- Solo si `sunat_status != aceptado`.
- Es parcial. Si llegan `items`, se recalculan los totales y se reemplazan los ítems.
- Resetea `sunat_status` a `pendiente` y reencola el envío.
- Error al editar una aceptada: `{"estado":"error","mensaje":"No se puede editar una factura aceptada por SUNAT."}`. La salida es emitir una NC.

##### Descargas y reenvío
- **XML:** `Content-Type: application/xml`, `Content-Disposition: attachment; filename="F001-123.xml"`.
- **CDR:** ZIP. Devuelve 404 si aún no hay CDR (estado `pendiente` o `enviado`).
- **PDF:** parámetro `format` = `a4` (default) | `a5` | `ticket-80` | `ticket-58`. Cabeceras: `Content-Type: application/pdf`, `Content-Disposition: inline; filename="F001-123.pdf"`, `Cache-Control: private, max-age=300`.
- **Reenviar:** resetea a `pendiente` y encola. Si ya está aceptada: `{"estado":"error","mensaje":"Esta factura ya fue aceptada por SUNAT."}`.

##### Pagos
- Body: `metodo`, `monto`, `referencia`, `monto_recibido`, `notas`.
- Métodos: efectivo, yape, plin, tunki, transferencia, tarjeta_credito, tarjeta_debito, deposito, cheque, otro.

##### Sucursales
`GET /sucursales` (respuesta literal):
```json
{
  "datos": [
    { "id": 1, "nombre": "Sede Principal",      "cod_local": "0000", "is_principal": true  },
    { "id": 2, "nombre": "Sucursal Lima Norte",  "cod_local": "0001", "is_principal": false },
    { "id": 3, "nombre": "Sucursal Miraflores",  "cod_local": "0002", "is_principal": false }
  ]
}
```
- El filtro `?sucursal_id=` aplica también a boletas, notas-credito, notas-debito y guias-remision.
- Cada sucursal tiene sus propias series (F001/B001, F002/B002, …).

##### Reglas finales del doc
- Correlativo autoasignado por serie y tenant.
- Recalcula totales desde los items aunque el cliente los envíe.
- IGV 18% configurable.
- Redondeo a 2 decimales.
- Detracción solo si el total supera S/ 700.
- Flujo feliz: `pendiente` → `enviado` → `aceptado`.
- Corrección tras rechazo (por ejemplo, código 2325): PUT, reencola y vuelve a `pendiente` → `enviado` → `aceptado`.

---

#### 2. emision/boletas.md (Tipo 03)

- **Serie:** `^B[A-Z0-9]{3}$`.
- **Endpoints:** los mismos que facturas, más `DELETE /boletas/{id}` (borrado local).
  - La tabla dice "si está `pendiente` o `rechazado` sin CDR".
  - La sección 8 dice "solo `pendiente`" (contradicción).
  - No se documenta `/masivo` para boletas.
- **Cliente** (`tipo_doc`):
  - 0: "Doc. trib. no dom. sin RUC"; se usa sin identificación con `num_doc: "-"` y `"CLIENTES VARIOS"`.
  - 1: DNI de 8 dígitos.
  - 4: carnet de extranjería.
  - 6: RUC de 11 dígitos con prefijo 10/15/17/20.
  - 7: pasaporte.
  - Si el total es menor a S/ 700 no es obligatorio identificar al cliente.
- **Items:** idénticos a factura, incluidos `cod_producto_sunat` y `contrato_colaboracion`.
- Se admiten pagos mixtos (`pagos[]` con varios métodos).
- **Flags:** `enviar_automatico` y `solo_registro: true`. Con `solo_registro` la boleta queda pendiente para ir en un resumen RC y nunca se envía individualmente.

Respuesta 201 (literal):
```json
{
  "estado": "exito",
  "mensaje": "Boleta creada y encolada para envío a SUNAT.",
  "datos": {
    "id": 345,
    "tipo_documento": "03",
    "serie": "B001",
    "correlativo": "00000345",
    "numero_completo": "B001-345",
    "fecha_emision": "2026-04-18",
    "cliente": { "tipo_doc": "1", "num_doc": "12345678", "razon_social": "JUAN PEREZ LOPEZ" },
    "tipo_moneda": "PEN",
    "mto_imp_venta": "50.00",
    "sunat_status": "pendiente",
    "items": [...]
  }
}
```

**Listado:** usa los mismos filtros que facturas (`AppliesDocumentFilters`). El doc advierte que los nombres antiguos (`buscar`, `cliente_doc`, `estado`, `payment_status`, `moneda`, `desde`, `hasta`) **no filtran nada**.

**Estados:** los mismos 6 de factura.

**Relacionados:** para anular una boleta aceptada se usa el resumen RC (si está dentro de los 7 días) o una NC.

---

#### 3. emision/notas-credito.md (Tipo 07)

- **Endpoints:** CRUD sin DELETE, más xml, cdr, pdf, enviar y reenviar.
- **Body:** los campos de factura más los del documento afectado:
  - `doc_afectado_tipo` ✅: `01`, `03` o `12` (ticket).
  - `doc_afectado_serie` ✅.
  - `doc_afectado_correlativo` ✅ (con o sin ceros; los ejemplos usan ambos).
  - `cod_motivo` ✅ (Cat. 09).
  - `des_motivo` ✅ (máximo 250).
- **Serie:** `FC##` o `F…` para NC de facturas; `BC##` o `B…` para NC de boletas.
- **Cat. 09:**
  - 01 Anulación de la operación.
  - 02 Anulación por error en el RUC.
  - 03 Corrección por error en la descripción (mismos montos, solo cambia el texto).
  - 04 Descuento global.
  - 05 Descuento por ítem.
  - 06 Devolución total o parcial.
  - 07 Bonificación.
  - 08 Disminución en el valor.
  - 09 Otros.
  - Los motivos 01 y 02 usan los mismos items que el original; 04, 05, 07 y 08 usan items que describen el ajuste.
- **Items:** siempre en positivo; el IGV se calcula igual que en factura.
- **Validación de monto (ERR-3286/3503):** la NC no puede superar al documento emitido por esta API.
  - Factura: estricto.
  - Boleta: tolerancia de +1.
  - No aplica si la moneda difiere o si el documento se emitió en otro sistema.

Error 422 (literal):
```json
{
  "estado": "error",
  "mensaje": "Error de validación",
  "errores": {
    "doc_afectado_correlativo": ["El importe total de la nota de crédito (500) no puede superar el del documento que modifica F001-1 (118) (ERR-3286/3503)."]
  }
}
```

Respuesta 201 (literal):
```json
{
  "estado": "exito",
  "mensaje": "Nota de crédito creada y encolada para envío a SUNAT.",
  "datos": {
    "id": 67,
    "tipo_documento": "07",
    "serie": "FC01",
    "correlativo": "00000067",
    "numero_completo": "FC01-67",
    "fecha_emision": "2026-04-18",
    "doc_afectado": {
      "tipo": "01",
      "serie": "F001",
      "correlativo": "00000123",
      "numero_completo": "F001-123"
    },
    "cod_motivo": "06",
    "des_motivo": "Devolución total por defecto",
    "mto_imp_venta": "7052.86",
    "sunat_status": "pendiente"
  }
}
```

**Listado:** solo admite `sunat_status`, `fecha_desde` + `fecha_hasta` (van juntos), `serie`, `client_num_doc`, `sucursal_id` y `tipo_moneda`. Se ignoran `doc_afectado_*`, `cod_motivo`, `search`/`q`, `correlativo`, `estado_pago`, los montos, `ordenar_por` y `con`.

**Otras reglas:**
- El documento afectado puede no existir en la API.
- El doc dice "plazos SUNAT: 1 año para anular".
- Estados: `pendiente`, `enviado`, `aceptado`, `rechazado`.
- "Las NC no se anulan con comunicación de baja; se revierten con una ND." Esto contradice anulaciones.md (ver §19).
- Con régimen NRUS, una NC o ND contra factura devuelve 422.

---

#### 4. emision/notas-debito.md (Tipo 08)

- **Endpoints y campos:** análogos a la NC.
- **Serie:** `FD##` para facturas, `BD##` para boletas.
- `doc_afectado_*` es obligatorio salvo para el motivo **13**.
- **Cat. 10:**
  - 01 Intereses por mora.
  - 02 Aumento en el valor.
  - 03 "Otros conceptos" (ya no incluye penalidades).
  - 04 Ajustes de valor de exportación.
  - 05 Corrección de moneda.
  - 06 Corrección de cantidad (solo la cantidad adicional).
  - 07 Descuentos no aplicados.
  - 08 Cargos adicionales.
  - 09 Otros.
  - 11 Ajustes de operaciones de exportación.
  - 12 Ajustes afectos al IVAP.
  - **13 Penalidades**, vigente desde el 01/01/2027.
- **Motivo 13:**
  - Solo ítems inafectos (`tip_afe_igv: "30"`) y con IGV 0; si hay IGV, ERR-3507.
  - El documento afectado es opcional.
  - Hasta el 31/12/2026 las penalidades van con el motivo `03`.
  - Hoy SUNAT rechaza el 13 con `2172: Valor no se encuentra en el catalogo: 10 valor '13'`.
- Los items reflejan solo el monto **adicional**.

Ejemplo motivo 13 (literal):
```json
{
  "serie": "FD01",
  "fecha_emision": "2027-01-15",
  "tipo_moneda": "PEN",
  "cod_motivo": "13",
  "des_motivo": "Penalidad por incumplimiento contractual",
  "cliente": { "tipo_doc":"6", "num_doc":"20555666777", "razon_social":"ACME SAC" },
  "items": [
    {
      "descripcion": "Penalidad contractual",
      "unidad": "ZZ",
      "cantidad": 1,
      "precio_unitario": 500.00,
      "tip_afe_igv": "30"
    }
  ]
}
```

Respuesta 201 (literal):
```json
{
  "estado": "exito",
  "mensaje": "Nota de débito creada y encolada para envío a SUNAT.",
  "datos": {
    "id": 42,
    "tipo_documento": "08",
    "serie": "FD01",
    "correlativo": "00000042",
    "numero_completo": "FD01-42",
    "fecha_emision": "2026-04-18",
    "doc_afectado": {
      "tipo": "01",
      "serie": "F001",
      "correlativo": "00000123",
      "numero_completo": "F001-123"
    },
    "cod_motivo": "01",
    "des_motivo": "Intereses por mora 15 días",
    "mto_imp_venta": "118.00",
    "sunat_status": "pendiente"
  }
}
```

- **Listado:** el mismo subconjunto reducido que las NC.
- **Estados:** `pendiente`, `enviado`, `aceptado`, `rechazado`, `anulado`.
- Para anular una ND errónea se emite una NC contra la ND.

---

#### 5. emision/resumen-diario.md (RC)

**Dos modos con un mismo endpoint, `POST /resumenes`:**
- **Envío:** solo `fecha_resumen`. Toma todas las boletas del tenant con esa fecha de emisión y `sunat_status=pendiente`, las agrupa en `RC-yyyymmdd-NNN` y encola. Cuando SUNAT acepta, las boletas pasan a `aceptado`.
- **Anulación:** `fecha_resumen` más `anular[]`:
  - `id` ✅ (entero).
  - `motivo` ✅ (máximo 255).
  - `tipo_documento` ❌ (default `03`).
  - Las boletas pasan a `anulacion_en_proceso` y, al aceptarse, a `anulado`.

**Plazos:** tanto el envío como la anulación deben hacerse dentro de los 7 días calendario desde la emisión ("estricto").

**Endpoints:** `POST /resumenes`, `GET /resumenes`, `GET /resumenes/{id}/estado`, `/xml`, `/cdr` y `POST /resumenes/{id}/enviar`.

Respuesta 201 de envío (literal):
```json
{
  "estado": "exito",
  "mensaje": "Resumen diario encolado para envío a SUNAT.",
  "datos": {
    "id_resumen": 5,
    "identifier": "RC-20260418-001",
    "fecha_envio": "2026-04-18",
    "fecha_documentos": "2026-04-18",
    "correlativo": "001",
    "accion": "envio",
    "total_documentos": 25,
    "estado_sunat": "enviado",
    "documentos": [
      {
        "id": 100,
        "tipo_documento": "03",
        "serie_nro": "B001-100",
        "estado": "1",
        "cliente": { "tipo_doc": "1", "num_doc": "12345678" },
        "mto_oper_gravadas": 42.37,
        "mto_igv": 7.63,
        "total": 50.00,
        "estado_sunat": "enviado"
      }
    ],
    "consulta_estado": "https://tu-api.com/api/v1/resumenes/5/estado"
  }
}
```
Aquí los montos van como **números**, no strings, a diferencia de las facturas. El campo `estado` del ítem es el del XML: `1` = adicionar, `3` = anulado.

Respuesta 201 de anulación (literal):
```json
{
  "estado": "exito",
  "mensaje": "Resumen de anulación encolado para envío a SUNAT.",
  "datos": {
    "id_resumen": 6,
    "identifier": "RC-20260418-002",
    "accion": "anulacion",
    "total_documentos": 2,
    "estado_sunat": "enviado",
    "documentos": [
      { "id": 100, "tipo_documento": "03", "serie_nro": "B001-100", "estado": "3", "total": 50.00, "estado_sunat": "enviado" },
      { "id": 102, "tipo_documento": "03", "serie_nro": "B001-102", "estado": "3", "total": 75.00, "estado_sunat": "enviado" }
    ]
  }
}
```

**Errores (422) en modo envío:**
- "No hay boletas pendientes para la fecha 2026-04-18".
- "SUNAT solo permite resumen diario hasta 7 días después...".

**Errores en modo anulación:**
- "No se encontraron boletas aceptadas por SUNAT para anular".
- "Boleta B001-100 ya pasó el plazo de 7 días para anulación".
- "La boleta B001-100 tiene una nota de crédito asociada. Usa la NC en vez de anularla".
- "Ya existe un resumen de anulación para la boleta B001-100".

**`GET /resumenes`:** params `mes` (`yyyy-mm`), `tipo` (`envio` | `anulacion`), `por_pagina` (default 15).

Respuesta (literal):
```json
{
  "estado": "exito",
  "datos": [
    {
      "id": 5,
      "identifier": "RC-20260418-001",
      "tipo": "envio",
      "fecha_referencia": "2026-04-18",
      "fecha_envio": "2026-04-18",
      "total_documentos": 25,
      "ticket": "1717182000",
      "estado_sunat": "aceptado",
      "codigo_sunat": "0",
      "descripcion_sunat": "El Resumen ha sido aceptado",
      "creado_en": "2026-04-18T14:30:00-05:00"
    }
  ],
  "paginacion": {
    "total": 12,
    "pagina_actual": 1,
    "ultima_pagina": 1
  }
}
```
Ojo: aquí `paginacion` va al mismo nivel que `datos`, mientras que en facturas va dentro de `datos`.

`GET /resumenes/{id}/estado` (literal):
```json
{
  "estado": "exito",
  "datos": {
    "id_resumen": 5,
    "identifier": "RC-20260418-001",
    "ticket": "1717182000",
    "estado_sunat": "aceptado",
    "codigo_sunat": "0",
    "descripcion_sunat": "El Resumen ha sido aceptado",
    "notas_sunat": [],
    "tipo": "envio",
    "total_documentos": 25,
    "documentos": [
      {
        "id": 100,
        "tipo_documento": "03",
        "serie_nro": "B001-100",
        "estado": "1",
        "cliente": { "tipo_doc": "1", "num_doc": "12345678" },
        "mto_oper_gravadas": 42.37,
        "mto_igv": 7.63,
        "total": 50.00,
        "estado_sunat": "aceptado"
      }
    ]
  }
}
```
La clave es `documentos`, no `boletas`.

**Estados del resumen:**
- `pendiente`.
- `enviado` (esperando ticket).
- `procesando`.
- `aceptado`.
- `rechazado`: las boletas **vuelven a `pendiente`**.

**Reglas:**
- No se puede incluir una boleta que ya tiene NC.
- No puede haber dos resúmenes de anulación para la misma boleta.
- El correlativo es diario (001, 002, …).
- El proceso es asíncrono (ticket).

---

#### 6. emision/envio-manual.md

**Alcance:** aplica a 01, 03, 07, 08, 09/31, RC, RA, RR (reversiones), 20 y 40.

- Por defecto, o con `enviar_automatico: true`, se encola el job `SendDocumentToSunat`.
- Con `enviar_automatico: false` el documento queda `pendiente` y no se encola.

Respuesta en modo automático (literal):
```json
{
  "estado": "exito",
  "mensaje": "Factura creada y encolada para envío a SUNAT.",
  "datos": {
    "id": 123,
    "numero_completo": "F001-1234",
    "sunat_status": "enviado",
    ...
  }
}
```

Respuesta en modo manual (literal):
```json
{
  "estado": "exito",
  "mensaje": "Factura creada en estado pendiente. Use POST /facturas/{id}/enviar para enviarla a SUNAT.",
  "datos": {
    "id": 124,
    "numero_completo": "F001-1235",
    "sunat_status": "pendiente",
    ...
  }
}
```

Respuesta de `POST /facturas/124/enviar` (literal):
```json
{
  "estado": "exito",
  "mensaje": "Factura enviada a SUNAT.",
  "datos": {
    "id": 124,
    "sunat_status": "enviado",
    ...
  }
}
```

**Rutas `/enviar`:**
- `/facturas`, `/boletas`, `/notas-credito`, `/notas-debito`, `/guias-remision`, `/resumenes`, `/retenciones` y `/percepciones` → `/{id}/enviar`.
- Las comunicaciones de baja y las reversiones RR usan las dos `/anulaciones/{id}/enviar`.
- `/reenviar` es un alias retrocompatible: marca el documento como `pendiente` y encola.

**Reglas:**
- `/enviar` acepta documentos `pendiente` o `rechazado`; si están `aceptado` devuelve 422.
- Limpia `sunat_code` y `sunat_description` antes de reencolar.
- Reintentos: **10 intentos con backoff exponencial de 15 s a 600 s**.
- El límite del plan (`check.limit:sunat`) se cuenta al **crear** el documento, no al enviarlo.
- Webhooks: `document.sent` y `document.rejected`.
- `solo_registro` (boletas destinadas a un RC) y `enviar_automatico: false` son flags distintos.

---

#### 7. emision/anulaciones.md (Comunicación de Baja RA y resúmenes)

**`POST /anulaciones`:**
| Campo | Tipo | Oblig. | Notas |
|---|---|---|---|
| `fecha_generacion` | date | ✅ | Fecha de emisión del documento a anular |
| `fecha_comunicacion` | date | ❌ | Default hoy |
| `detalles[]` | array | ✅ | Mínimo 1 |
| `detalles[].tipo_documento` | string | ✅ | 01, 07, 08, 20 o 40; **no** 03 |
| `detalles[].serie` | string(4) | ✅ | |
| `detalles[].correlativo` | string | ✅ | |
| `detalles[].motivo` | string(255) | ✅ | |

**Plazos:**
- Factura, NC y ND: 7 días calendario.
- Retención y percepción: dentro del mismo mes de emisión.

Respuesta 201 (literal):
```json
{
  "estado": "exito",
  "mensaje": "Comunicación de baja encolada para envío a SUNAT.",
  "datos": {
    "id_anulacion": 7,
    "identifier": "RA-20260418-001",
    "correlativo": "001",
    "fecha_comunicacion": "2026-04-18",
    "total_documentos": 1,
    "estado_sunat": "enviado",
    "consulta_estado": "https://tu-api.com/api/v1/anulaciones/7/estado"
  }
}
```
Con `enviar_automatico=false`, `estado_sunat` queda en `pendiente` y el mensaje indica usar `/enviar`.

**`GET /anulaciones`:** filtros `fecha_desde` y `fecha_hasta` (sobre `fecha_comunicacion`), `estado` y **`per_page`** (no `por_pagina`).

`GET /anulaciones/{id}/estado` (el mismo shape que `GET /anulaciones/{id}`; literal):
```json
{
  "estado": "exito",
  "datos": {
    "id_anulacion": 7,
    "identifier": "RA-20260418-001",
    "correlativo": "001",
    "ticket": "1604394234123",
    "fecha_generacion": "2026-04-17",
    "fecha_comunicacion": "2026-04-18",
    "estado_sunat": "aceptado",
    "codigo_sunat": "0",
    "descripcion_sunat": "La Comunicación de baja ha sido aceptada",
    "notas_sunat": [],
    "total_documentos": 1,
    "detalles": [
      { "tipo_documento": "01", "serie": "F001", "correlativo": "123", "motivo": "..." }
    ],
    "creado_en": "2026-04-18T10:00:00-05:00",
    "actualizado_en": "2026-04-18T10:05:00-05:00"
  }
}
```
Cuando la baja queda aceptada, los documentos referenciados pasan a `anulado`.

**Resúmenes (lo que añade este doc):**
- En modo anulación, `anular[].tipo_documento` admite **03, 07 y 08** (NC y ND de boletas).
- La respuesta de `checkStatus` de ejemplo dice `"descripcion_sunat":"Resumen aceptado"`, con ticket `1604394234456`.
- `GET /resumenes` no tiene filtros `fecha_desde`, `fecha_hasta` ni `estado`.

**Reglas:**
- No se anula un documento `rechazado` (se corrige con PUT).
- No se anula dos veces.
- La baja genera su propio CDR.
- Una baja puede mezclar los tipos 01, 07, 08, 20 y 40.
- Es asíncrona.

**Estados:** `pendiente`, `enviado`, `procesando`, `aceptado`, `rechazado`.

---

#### 8. emision/guia-remision-remitente.md (GRE 09, formato 2022)

**Endpoints:**
- `POST /guias-remision`, `GET` (lista), `GET {id}`, `PUT {id}` (solo `pendiente` o `rechazado`).
- `/xml`, `/cdr`, `/pdf` (`a4`, `a5`).
- `GET {id}/estado` (consulta el ticket y actualiza el estado local).
- `POST {id}/enviar`.

**Catálogos:**
- `mod_traslado`: 01 público (requiere `transportista`), 02 privado (requiere `vehiculo` y `conductor`).
- Cat. 20 `cod_traslado`:
  - 01 venta, 02 compra.
  - 04 traslado entre establecimientos.
  - 08 importación, 09 exportación.
  - 13 otros.
  - 14 venta sujeta a confirmación.
  - 18 emisor itinerante.
  - 19 traslado a zona primaria.

**Campos obligatorios:**
- `serie` (string(4), por ejemplo T001 o EG01) y `fecha_emision`.
- `destinatario.tipo_doc` (1 o 6), `destinatario.num_doc` (máximo 15), `destinatario.razon_social` (máximo 255).
- `cod_traslado`, `mod_traslado` y `fecha_traslado`.
- `peso_total` (> 0) y `und_peso_total` (`KGM` por defecto, o `TNE`).
- `llegada_ubigeo` (6) y `llegada_direccion` (máximo 500); lo mismo para `partida_*`.
- `items[].descripcion` (máximo 500) y `items[].cantidad` (> 0); opcionales `items[].unidad` y `codigo`.

**Opcionales:**
- `observacion`, `num_bultos`, `llegada_cod_local`, `partida_cod_local`.
- `vehiculo {placa, secundarios[{placa}]}`.
- `conductor {tipo_doc, num_doc, tipo: Principal|Secundario, nombres, apellidos, licencia}` o, alternativamente, `conductores[]`.
- `transportista {tipo_doc, num_doc, razon_social, nro_mtc}`.
- `tercero {...}`, `comprador {...}` e `indicadores[]`.

**Excepción M1L:**
- Con `indicadores: ["SUNAT_Envio_IndicadorTrasladoVehiculoM1L"]` se pueden omitir el transportista o el conductor.
- El valor con "Categoria" da el error SUNAT **3388**; la API normaliza ese typo.

**Catálogo de indicadores:** M1L, TransbordoProgramado, RetornoVehiculoEnvaseVacio, RetornoVehiculoVacio, TrasladoTotalDAMoDS, VehiculoConductoresTransp, PagadorFlete_Remitente, PagadorFlete_Subcontratador, PagadorFlete_Tercero, TrasporteSubcontratado. Los de pagador y subcontratado se derivan automáticamente.

**Reglas:**
- Placa: `^[A-Z0-9]{6,8}$`, sin guion; `ABC-123` devuelve 422.
- La licencia es obligatoria en transporte privado.
- El RUC del transportista tiene 11 dígitos.
- No hay validación cruzada con la factura.
- No existe anulación de guía: se corrige con PUT o se emite una nueva.

Respuesta 201 (literal):
```json
{
  "estado": "exito",
  "mensaje": "Guía creada y encolada para envío a SUNAT.",
  "datos": {
    "id": 88,
    "tipo_documento": "09",
    "serie": "T001",
    "correlativo": "00000088",
    "numero_completo": "T001-88",
    "fecha_emision": "2026-04-18",
    "fecha_traslado": "2026-04-20",
    "destinatario": {...},
    "mod_traslado": "02",
    "peso_total": "150.50",
    "sunat_status": "pendiente",
    "sunat_ticket": null
  }
}
```

`GET /guias-remision/{id}/estado` (literal):
```json
{
  "estado": "exito",
  "datos": {
    "id": 88,
    "numero_completo": "T001-88",
    "sunat_ticket": "1604394234789",
    "sunat_status": "aceptado",
    "sunat_code": "0",
    "sunat_description": "La guía ha sido aceptada"
  }
}
```

**PUT:** **no es parcial**; revalida con el request completo. Enviar solo `{fecha_traslado, vehiculo}` devuelve 422. Error si ya está aceptada: `{"estado":"error","mensaje":"Solo guías pendientes o rechazadas pueden editarse."}`.

**Listado:** **sin filtros**, solo `por_pagina`.

**Estados:** `pendiente`, `enviado`, `procesando`, `aceptado`, `rechazado`.

---

#### 9. emision/guia-remision-transportista.md (GRT 31)

**Rutas:**
- `POST /guias-remision-transportista` fuerza `tipo_documento=31`.
- `POST /guias-remision` con `"tipo_documento":"31"` hace lo mismo.
- Los GET, PUT, estado y enviar comparten las rutas de `/guias-remision`.

**Datos obligatorios:**
- `serie` con formato `V[A-Z0-9]{3}`, `fecha_emision`.
- `remitente {tipo_doc 1|6, num_doc, razon_social}`: no puede ser el RUC del tenant.
- `destinatario`.
- `doc_relacionado`: objeto o array, **obligatorio**, con `{tipo_codigo, numero, tipo_descripcion, ruc_emisor}`.
  - Tipos aceptados: 01, 03, 09, 31, 50 (DAM) y 52 (DS); otros (por ejemplo 65–69) devuelven 422.
  - Máximo 2 documentos, salvo que uno sea un 09 de serie electrónica.
  - Formato DAM: `[0-9]{3}-[0-9]{4}-[0-9]{2}-[0-9]{1,6}`.
- `cod_traslado`, `mod_traslado` y `fecha_traslado` (≥ `fecha_emision`).
- `peso_total` y `und_peso_total`; `partida_*` y `llegada_*`.
- `vehiculo.placa` y `conductor`.
- `items` (al menos uno con descripción, cantidad y unidad).

**Opcionales:**
- `vehiculo.nro_circulacion`, `vehiculo.cod_emisor` (Cat. D-37: 01 MTC, 02 OSINERGMIN, 03 SUTRAN, 04 OEFA, 05 OSIPTEL) y `vehiculo.nro_autorizacion`.
- `vehiculo.secundarios[]` (hasta 2) y `conductores[]`.
- `datos_subcontratador {num_doc, razon_social}`.
- `datos_pagador_flete {tipo: remitente|subcontratador|tercero, tipo_doc, num_doc, razon_social}`: si es tercero, `num_doc` y `razon_social` son obligatorios. Si hay subcontratación, el pagador es obligatorio.
- La API añade automáticamente los indicadores de subcontratado y pagador.

**Series y credenciales:**
- La serie se crea con `POST /series {"tipo":"guia_transportista","serie":"V001","correlativo_inicial":0}`.
- Para cualquier GRE el tenant necesita `client_id` y `client_secret` de OAuth2 SUNAT GRE (distintos de la Clave SOL).

Respuesta (literal):
```json
{
  "estado": "exito",
  "mensaje": "Guía de Remisión Transportista creada y encolada para envío a SUNAT.",
  "datos": {
    "numero_completo": "V001-000001",
    "tipo_documento": "31",
    "sunat_status": "enviado",
    "ticket": "20260418000000001"
  }
}
```

**Errores 422 (literal):**
```json
{"errores": {
  "remitente": ["La Guía de Remisión Transportista requiere los datos del remitente (quien envía la carga)."],
  "doc_relacionado": ["La Guía de Remisión Transportista requiere al menos un documento relacionado (factura, boleta, DAM, GRR, etc.)."],
  "vehiculo": ["La Guía Transportista requiere datos del vehículo."],
  "conductor": ["La Guía Transportista requiere al menos un conductor."]
}}
```
Otros mensajes:
- `remitente.num_doc`: "El remitente no puede ser el mismo que el transportista emisor (RUC del tenant)."
- `datos_pagador_flete.num_doc`: "Si el pagador es tercero, debe informar su número de documento."
- `datos_pagador_flete`: "Si existe transporte subcontratado, debe informar quién paga el flete (remitente/subcontratador/tercero)."

**Diferencias en el XML respecto a la GRR:**
- `DespatchAdviceTypeCode` = 31.
- `DespatchSupplierParty` = el transportista (tenant).
- El remitente va en `Shipment/Delivery/Despatch/DespatchParty`.
- No se duplica `CarrierParty`.
- `AdditionalDocumentReference` es obligatorio.

**Flujo GRE por REST (aplica a 09 y 31):**
1. Construir el documento.
2. Generar el XML sin firma.
3. Inyectar `DespatchParty` dentro de `cac:Despatch`, que Greenter no soporta de forma nativa.
4. Firmar con el certificado.
5. `sendXml` al endpoint REST OAuth2.
6. Un job hace polling del ticket **cada 15 s** hasta obtener el CDR.

**Endpoints SUNAT:**
- Producción: `https://api-cpe.sunat.gob.pe/v1`.
- Beta: `https://gre-test.nubefact.com/v1` (es de Nubefact, no de SUNAT).
- En beta pueden salir rechazos 3383 por validaciones cruzadas (RENIEC, MTC); la validación completa solo ocurre en producción.

**Catálogo 61:** 01, 03, 04, 09, 12, 48, 50, 52, 65–69, 80, 82. El ejemplo de registro de este doc trae una contraseña de certificado de ejemplo (***).

---

#### 10. emision/actualizar-comprobantes.md

**PUT:**
- Facturas, boletas, NC y ND: si no están aceptadas, PUT **parcial**.
- Guías: solo `pendiente` o `rechazado`, con payload **completo** en la raíz (sin wrapper `envio`) y la placa en `vehiculo.placa`.

**Qué hace internamente:**
- Valida que `sunat_status != aceptado`; si no, 422 "No se puede editar".
- Permite cambiar cliente, fechas, ítems, moneda, forma de pago y observación.
- Si llegan `items`, recalcula IGV, ISC, ICBPER, totales y leyenda.
- Resetea a `pendiente`, limpia los códigos de error y despacha `SendDocumentToSunat`.

**Matriz por estado:**
- `pendiente` o `enviado`: PUT.
- `rechazado` (2xxx): PUT.
- `aceptado` (código 0): NC.
- Aceptado con observaciones: NC y nueva emisión.
- `anulado`: nada.

**Ejemplos literales:**
- Respuesta del GET de una factura rechazada: `{"sunat_status":"rechazado","sunat_code":"2325","client_num_doc":"2060012345"}`. Muestra un campo plano `client_num_doc`.
- Respuesta del PUT: `{"mensaje":"Factura actualizada y reenviada a SUNAT.","sunat_status":"pendiente"}`.
- Estado final: `{"sunat_status":"aceptado","sunat_code":"0"}`.
- El ejemplo 2 usa `mto_valor_unitario` (valor sin IGV) en los items y omite `unidad`; ese campo no está documentado en facturas.md.
- Ejemplo de boleta: `{"fecha_vencimiento":"2026-05-15","forma_pago":"Credito"}`.

---

#### 11. emision/buscar-codigo-producto.md (UNSPSC)

Todos con autenticación, solo lectura, catálogo en español.

`GET /catalogos/producto-sunat?q=&per_page=20&page=1`:
- Si `q` es solo dígitos, busca por prefijo de código (2–8 dígitos).
- Si es texto, todas las palabras deben aparecer; ordena por relevancia (primero las que empiezan con el término).

Respuesta (literal):
```json
{
  "estado": "exito",
  "total": 3,
  "pagina": 1,
  "por_pagina": 5,
  "datos": [
    { "codigo": "15121504", "descripcion": "Aceites de motor", "clase": "151215" }
  ]
}
```

`GET /catalogos/producto-sunat/{codigo}` (8 dígitos; literal):
```json
{
  "estado": "exito",
  "valido": true,
  "datos": {
    "codigo": "78181500",
    "descripcion": "Servicios de mantenimiento y reparación de vehículos",
    "jerarquia": {
      "segmento": { "codigo": "78", "nombre": "Servicios de Transporte, Almacenaje y Correo" },
      "familia":  { "codigo": "7818", "nombre": "Servicios de mantenimiento o reparaciones de transportes" },
      "clase":    { "codigo": "781815", "nombre": "Servicios de mantenimiento y reparación de vehículos" }
    }
  }
}
```
- Si el código no existe: 404 con `"valido": false`.
- Si no tiene 8 dígitos: 422.

**Navegación jerárquica:**
- `/segmentos`: 56 segmentos `{codigo(2), nombre}`.
- `/familias?segmento=78`: `{codigo(4), nombre}`.
- `/clases?familia=7818`: `{codigo(6), nombre}`.
- `/productos?clase=781815`: `{codigo(8), descripcion}`, con `total`.

---

#### 12. setup/configuracion.md

**`POST /registro`:** público, multipart.
| Campo | Oblig. | Notas |
|---|---|---|
| `ruc` (11) | ✅ | |
| `razon_social` | ✅ | Máximo 255 |
| `nombre_comercial` | ❌ | |
| `direccion` | ✅ | Máximo 500 |
| `ubigeo` | ✅ | 4–6 dígitos |
| `departamento`, `provincia`, `distrito` | ❌ | |
| `sol_user`, `sol_pass` | ✅ | |
| `entorno` | ❌ | `beta` (default) o `production` |
| `plan` | ❌ | `free`, `pro` o `business` |
| `tax_regime` | ❌ | `general` (default), `rus`, `rer` o `mype` |
| `igv_rate_override` | ❌ | 0–30 |
| `nrus_categoria` | ❌ | 1 o 2, solo con `rus` |
| `client_id`, `client_secret` | ❌ | |
| `certificado` | ✅ | .pfx, .p12, .pem, .cer o .crt |
| `contrasena_certificado` | Si .pfx/.p12 | |
| `logo` | ❌ | jpg/png, máximo 2 MB |

Respuesta 201: `{"estado":"exito","mensaje":"Creado exitosamente","datos":{"tenant_id":15,"ruc":"20100000001","razon_social":"MI EMPRESA SAC","entorno":"beta","plan":"pro","api_key":"***","api_secret":"***","importante":"Guarde sus credenciales. El api_secret NO se puede recuperar."}}`.

**`GET /empresa`** devuelve: id, ruc, razon_social, nombre_comercial, direccion, ubigeo, entorno, plan, activo, `telefonos[]`, `emails[]`, `cuentas_bancarias[{banco, tipo_cuenta, moneda, numero, cci, titular}]`, `billeteras_digitales[{tipo: yape|plin|tunki|otro, numero, titular}]`. Según tasas-igv.md también trae `tax_regime`, `igv_rate_override` y `tasa_igv_vigente`.

**`PUT /empresa`** (todo opcional):
- Datos fiscales y credenciales: los mismos campos de registro, más `sol_*`, `client_*` y `entorno`.
- `url_webhook`.
- `telefonos` y `emails` (máximo 5 cada uno), cuentas y billeteras.
- `mensaje_agradecimiento` y `mensaje_promocional`.

**Logo y certificado:**
- `POST /empresa/logo`.
- `POST /empresa/certificado` (multipart con `certificado` y `contrasena_certificado`).

**Sucursales:**
- `GET`, `POST`, `GET {id}`, `PUT {id}`, `DELETE {id}`.
- POST: `nombre` ✅ (100), `cod_local` ✅ (0000–9999), `direccion` ✅, `ubigeo` ✅ (6), `telefono` ❌, `email` ❌, `es_principal` ❌.
- El listado devuelve `es_principal` y `total_series`; el ejemplo de facturas.md usa `is_principal`.

**Series:**
| Tipo | Código SUNAT | Prefijo |
|---|---|---|
| factura | 01 | F |
| boleta | 03 | B |
| nota_credito | 07 | F o B |
| nota_debito | 08 | F o B |
| guia_remision | 09 | T |
| guia_transportista | 31 | V |
| retencion | 20 | R |
| percepcion | 40 | P |

- `GET /series?sucursal_id=&tipo=`.
- `POST /series` con una serie `{tipo, serie, sucursal_id, correlativo_inicial}` o con `{series:[...]}`.
- Regla: 4 caracteres que empiezan con letra mayúscula.
- `POST /series/init-defaults` (idempotente, `firstOrCreate`):
  - `serie_factura` (`^F[A-Z0-9]{3}$`, default F001) y `correlativo_factura` (≥ 0, default 0).
  - `serie_boleta` (default B001) y `correlativo_boleta`.
  - Si la serie ya existe y se envía su correlativo explícito, lo actualiza.

**Clientes:**
- `GET /clientes?buscar=&tipo_documento=&activo=`.
- POST: `{tipo_documento, numero_documento, razon_social, direccion, email, telefono, ubigeo}`. Ojo: aquí los nombres difieren de los usados en los comprobantes (`tipo_doc` / `num_doc`).
- Cat. 06 completo: 0, 1, 4, 6, 7, A (cédula diplomática), B (doc. del país de residencia), C (TIN), D (IN), E (TAM).

**`GET /buscar-documento?tipo=&numero=`:**
- Busca primero en local y después en SUNAT/RENIEC; devuelve `{tipo_doc, num_doc, razon_social, direccion, fuente: "local"|"sunat"}`.
- 404 si no existe.

**Planes:**
- `GET /planes` es público. Límites: free 30 documentos/mes; pro 500 a S/ 49; business −1 (ilimitado) a S/ 99.
- `GET` y `POST /suscripcion {plan_slug, ciclo_facturacion: monthly|yearly, prueba (trial de 14 días), token}`.
- `PUT /suscripcion/cambiar-plan`, `PUT /suscripcion/cancelar`, `GET /suscripcion/pagos`.
- `GET /suscripcion/uso` devuelve `{documentos_usados, limite_mensual, porcentaje, resetea_en}`.

---

#### 13. setup/credenciales.md

**Autenticación:** cabeceras `X-Api-Key` y `X-Api-Secret`.
- El secret se guarda **cifrado en reposo** (cast `encrypted`).
- El middleware `ResolveTenant` lo compara en claro con `hash_equals`.
- Hay caché de autenticación con clave `tenant:key:{api_key}`.

**Bug documentado:** `CredentialRecoveryController::verificar` guarda `hash('sha256', $secret)` pero devuelve el secret en claro. Por eso un secret obtenido por recuperación **no autentica**; el flujo de regenerar sí funciona. Al reimplementar conviene unificar el criterio.

**`GET /empresa/credenciales`:** `{"estado":"exito","datos":{"api_key":"***","api_secret":"*** oculto — use POST /empresa/credenciales/regenerar para obtener uno nuevo ***"},"mensaje":"El api_secret no puede mostrarse. Si lo perdiste, regenera tus credenciales."}`. El api_key del ejemplo real está enmascarado.

**`POST /empresa/credenciales/regenerar`:**
- Responde `{"estado":"exito","mensaje":"Credenciales regeneradas exitosamente.","datos":{"api_key":"***","api_secret":"***","aviso":"Guarda estas credenciales ahora. El api_secret NO se volverá a mostrar."}}`.
- Invalida de inmediato las credenciales anteriores y limpia la caché.

**Recuperación (pública):**
- `POST /credenciales/recuperar {ruc}` o `{email}`.
  - Responde siempre igual: "Si el RUC o email está registrado, recibirás las instrucciones en breve.".
  - Envía por email un token de un solo uso, válido 30 minutos.
  - Rate limit: 3 solicitudes por IP cada 10 minutos.
- `POST /credenciales/recuperar/verificar {token}` (64 caracteres):
  - Devuelve credenciales nuevas.
  - Si el token venció o ya se usó: `{"estado":"error","mensaje":"Token inválido o expirado."}`.
- Los secrets son `Str::random(64)`.

---

#### 14. setup/nrus.md

**Régimen:**
- Valor `tax_regime=rus`; `nrus` devuelve 422.
- `nrus_categoria`: 1 (hasta S/ 5,000, cuota S/ 20) o 2 (hasta S/ 8,000, cuota S/ 50); límite anual S/ 96,000.
- Al cambiar a otro régimen se limpia `nrus_categoria`.

**Lo que hace la API automáticamente:**
- Boletas con `tipo_operacion=0113` y `tip_afe_igv=30` por defecto; los montos van a `mto_oper_inafectas`.
- XML con `Percent 0`, `TaxExemptionReasonCode 30` y TaxScheme `9998`/`INA`/`FRE`.
- `tasa_igv_vigente: 0`.

**Bloqueos (422):**
- Factura: "Los contribuyentes del régimen NRUS solo pueden emitir boletas de venta, no facturas".
- NC o ND contra factura: "...solo pueden emitir notas de crédito contra boletas de venta (tipo 03)".
- Permitido: boleta, NC/ND contra 03, guía y resumen.
- SUNAT 2020: `tip_afe_igv=10` con `0113` es incoherente; la solución es no enviar `tip_afe_igv`.

Respuesta de boleta NRUS (literal; nótese el bloque `totales`, distinto del shape de facturas):
```json
{
  "estado": "exito",
  "mensaje": "Boleta creada y encolada para envío a SUNAT.",
  "datos": {
    "numero_completo": "B001-001509",
    "tipo_operacion": "0113",
    "totales": {
      "inafectas": 53.00,
      "total_impuestos": 0,
      "valor_venta": 53.00,
      "sub_total": 53.00,
      "total": 53.00
    }
  }
}
```

CDR real (literal):
```xml
<cbc:ReferenceID>B001-1509</cbc:ReferenceID>
<cbc:ResponseCode>0</cbc:ResponseCode>
<cbc:Description>La Boleta numero B001-1509, ha sido aceptada</cbc:Description>
```

**Otros datos:**
- Boleta con total mayor a S/ 700 exige identificar al cliente.
- La cuenta de prueba es el tenant beta `20161515648`.
- La tabla de archivos menciona la constante `REGIMEN_NRUS`, que según tasas-igv.md ya no existe (ahora `REGIMEN_RUS`).

---

#### 15. setup/tasas-igv.md

**Regímenes** (`config/regimenes.php`):
- `general`: 18% (16% IGV + 2% IPM).
- `mype`: 18%, salvo override.
- `rer`: 18%.
- `rus`: 0%.
- Los valores antiguos `mype_restaurantes` y `nrus` devuelven 422 (migración `2026_07_30_160000`).

**Ley 31556 (restaurantes, hoteles y alojamientos MYPE):** tabla de **referencia** para `igv_rate_override`; no se aplica automáticamente.
| Año | Tasa | Desglose |
|---|---|---|
| 2022–2024 | 10% | 8% IGV + 2% IPM |
| 2025 | 10% | Transición |
| **2026** | **10.5%** | 8% + 2.5% IPM |
| **2027** | **15%** | 12% + 3% |
| **2028** | **18%** | 14.5% + 3.5% |
| 2029+ | 18% | Régimen general |

- La constante es `TaxRateService::LEY_31556 = [2026=>10.5, 2027=>15.0, 2028=>18.0]` y el método `tasaLey31556()`.
- En el XML la tasa combinada va en un único `cbc:Percent` bajo el código 1000 (IGV); el IPM no se declara aparte. SUNAT aceptó 10.5% (F001-8, código 0).

**Precedencia** (`rateForItem` / `defaultIgvRate`):
1. `item.porcentaje_igv` explícito.
2. `tip_afe_igv` especial: 17 (IVAP) → 4%; exonerado, inafecto, exportación o gratuita → 0%.
3. `tenant.igv_rate_override`.
4. `tax_regime=rus` → 0%.
5. 18%.

**Configuración:**
- `PUT /empresa {"tax_regime":"mype","igv_rate_override":10.5}` responde `{"estado":"exito","mensaje":"Empresa actualizada.","datos":{"tax_regime":"mype","igv_rate_override":10.5,"tasa_igv_vigente":10.5,...}}`.
- Para limpiar: `igv_rate_override: null`.

**Casos probados:**
- General: 23.60 → valor 20.00, IGV 3.60.
- 10.5%: 22.10 → 20.00 + 2.10.
- 15%: 115 → 100 + 15.
- 12%: 112 → 100 + 12.

**Requisitos MYPE:** actividad de restaurante u hotel e ingresos ≤ 1700 UIT. La API no los valida.

---

#### 16. referencia/catalogo-errores.md

**HTTP y mensajes literales:**
| Código | Mensaje |
|---|---|
| 401 | `Las cabeceras X-Api-Key y X-Api-Secret son requeridas.` / `Credenciales de API inválidas.` |
| 403 | `Empresa desactivada. Contacte al administrador.` / `No autorizado.` |
| 404 | `Recurso no encontrado.` |
| 405 | `Método HTTP no permitido para esta ruta.` |
| 409 | `Ya hay una solicitud en proceso con esa Idempotency-Key.` |
| 422 | `Error de validación` (más `errores` con claves tipo `items.0.cantidad`) / `La Idempotency-Key ya se usó con un cuerpo distinto.` |
| 429 | `Demasiadas solicitudes. Intenta de nuevo más tarde.` (respetar `Retry-After`; límite por plan) |
| 500 | `Error interno del servidor.` (en modo debug añade `excepcion`, `detalle` y `archivo`) |

**Idempotencia** (POST, PUT, PATCH y DELETE con cabecera `Idempotency-Key`):
- Primera vez: se procesa y responde con `Idempotency-Replayed: false`.
- Misma key y mismo cuerpo: replay de la respuesta guardada, con `Idempotency-Replayed: true`.
- Misma key y cuerpo distinto: 422.
- Primera solicitud aún en curso: 409.
- Si la primera respondió 5xx, la key se libera.

**`sunat_status`:** `pendiente`, `enviado`, `aceptado` (código 0 u observaciones), `rechazado` (no se reintenta) y `anulado`.

**Rangos de código CDR:**
- 0: aceptado.
- 100–999: excepción temporal; se reintenta.
- 1000–1999 y 2000–3999: `rechazado`.
- 4000+: observaciones; queda aceptado.
- Ejemplos de rechazo: 2335, 3127, 3265.

**Webhook:**
- Se configura con `webhook_url`.
- Cabecera `X-Signature: sha256=<hmac>`, donde el HMAC es `hash_hmac('sha256', cuerpo_crudo, api_secret)`; se verifica con `hash_equals`.

**Buenas prácticas:** reintentar solo 429 y 5xx con backoff e Idempotency-Key; no reintentar 4xx.

---

#### 17. referencia/novedades-sunat-2026-2027.md

Base normativa: R.S. 000048-2026 y Reglas de Validación al 24.07.2026. Todos los campos nuevos son **opcionales**.

**Vigencias según este doc:**
| Cambio | Vigencia |
|---|---|
| Código de producto | Disponible ya, aceptado |
| Contrato de colaboración | Disponible ya, aceptado |
| NC ≤ documento | Ya |
| Cat. 54, nuevos códigos | 01/09/2026 |
| Cat. 51, descripción del 0101 | 01/08/2026 |
| ND motivo 13 | 01/01/2027 |
| Código de producto obligatorio (padrón) / ERR-3496 | 01/01/2027 |

**Código de producto (UNSPSC v14, Cat. 25):**
- Campo `items[].cod_producto_sunat`, que va al XML como `cbc:ItemClassificationCode`.
- Exactamente 8 dígitos y debe existir en el catálogo (unos 52.800 códigos).
- No puede terminar en `0000` (OBS-4337).
- Será obligatorio desde el 01/01/2027 para los RUC del padrón `ind_padron = 12` (flag de tenant `obligado_codigo_producto`).
- `tipo_operacion 0112` exige al menos un ítem con `84121901` o `80131501`.
- Aplica a facturas, boletas, NC y ND.

**Contrato de colaboración** (va al XML como `cac:ContractDocumentReference`):
- `tipo`: 1 ventas o 2 adquisiciones (ERR-3497).
- `numero`: string(50) (ERR-3501).
- `descripcion`: string(250) (ERR-3502).
- `porcentaje`: 0–99.99 (ERR-3500).
- Si se envía `numero`, los otros tres son obligatorios (ERR-3499).

**Cat. 54, nuevos códigos:** 038 espectáculos públicos, 042 ladrillos y cerámica, 043 estructuras metálicas, 046 cobre, 047 plata.

**Cat. 51:** el 0101 pasa a llamarse "Venta interna no sujeta a Detracción o Percepción".

---

#### 18. docs/actualizaciones-sunat-2026.md (plan al 30/07/2026)

**Postergación:**
- La actualización del 24.07.2026 (entrada [562]) postergó del 01/08/2026 al **01/01/2027** casi todos los cambios críticos y retiró los anexos 25.1–25.3.
- Lo formalizó la R.S. 000141-2026 (29/07/2026).

**Cronograma:**
| Cambio | Vigencia |
|---|---|
| Tasa especial 10.5% (OBS-4439, padrón de restaurantes/hoteles) | Ya vigente desde el 13/02/2026 |
| Emisor electrónico desde la inscripción del RUC + SIRE (R.S. 000075-2026) | 01/06/2026 |
| Tipos de documento 25, 28 y 56 (DAE, DAEE, DAE-SEAE) | 01/08/2026 |
| Cat. 51 | 01/08/2026 |
| Cat. 54 | 01/09/2026 |
| Código de producto obligatorio (ERR-3496) | 01/01/2027 |
| NC solo puede modificar un documento (ERR-3261) | 01/01/2027 |
| ND motivo 13 (ERR-2524, 3194, 3507) | 01/01/2027 |
| Contrato de colaboración (ERR-3497..3502) | 01/01/2027 |
| Código de producto en liquidación de compra (ERR-3506) | 01/01/2027 (no hay LC en la API) |

**Estado de la API:** todo el P0 y P1 está implementado. Despliegue: `migrate` más `db:seed --class=UnspscCodeSeeder` (52.840 códigos). Queda pendiente el P2 (DAE, liquidación de compra y onboarding de la R.S. 000075-2026, con salida del NRUS obligada desde el primer día del mes siguiente).

**Códigos nuevos:**
- ERR-3496: código de producto inválido.
- ERR-3506: LC sin ItemClassificationCode.
- ERR-3261: más de un comprobante en la NC.
- ERR-3286 y 3503: montos de la NC.
- ERR-2524: falta el documento afectado en la ND.
- ERR-3194: solo se permite un documento que modifica.
- ERR-3507: penalidades inafectas.
- ERR-3497..3502: contrato de colaboración.
- OBS-4439: emisor fuera del padrón de tasa especial.

**Nota técnica:** `GreenterService.php:371-416` trata los códigos 3xxx como **observación aceptada**, y los `ERR-xxxx` no se parsean por prefijo (quedan como `error_code` / `error_message` genéricos).

---

#### 19. Contradicciones e inconsistencias

1. **Estado inicial tras crear en modo automático:** facturas, boletas, NC, ND y GRR muestran `sunat_status: "pendiente"`; envio-manual y GRT muestran `"enviado"`.
2. **Códigos CDR 3xxx:** catalogo-errores los trata como `rechazado`; según actualizaciones, el código los toma como observación aceptada.
3. **Qué hacer con un rechazo:** catalogo-errores dice "no se reintenta; reemitir con otro correlativo"; facturas, actualizar y envío manual dicen PUT sobre el mismo documento y `/enviar`.
4. **Anular una NC:** notas-credito dice que no se anulan con baja; anulaciones.md acepta el 07 en `/anulaciones` y también 07/08 en `/resumenes`; resumen-diario solo menciona el 03.
5. **Plazo de la NC:** notas-credito dice "1 año para anular"; la baja de factura, NC y ND es de 7 días.
6. **DELETE de boleta:** la tabla dice `pendiente` o `rechazado` sin CDR; la sección 8 dice solo `pendiente`.
7. **Contrato de colaboración:** novedades dice "ya aceptado por SUNAT"; actualizaciones dice que se postergó al 01/01/2027.
8. **Tamaño del catálogo UNSPSC:** ~52.800, 52.840 y ~49k según el documento.
9. **Nombre del webhook:** `url_webhook` en configuración frente a `webhook_url` en catalogo-errores.
10. **Paginación:** `por_pagina` en casi todo; `per_page` en anulaciones y en el buscador. Además el objeto `paginacion` va dentro de `datos` en facturas y al mismo nivel en resúmenes.
11. **Montos:** strings en comprobantes, números en resúmenes y en el bloque `totales` del NRUS.
12. **Ticket de guía:** `sunat_ticket` en la GRR frente a `ticket` en la GRT. El correlativo en `numero_completo` va sin ceros (T001-88) o con relleno (V001-000001).
13. **Filtros de guías:** la GRR dice que el listado no admite filtros; la GRT sugiere `?tipo_documento=31`.
14. **Serie de la GRR:** "cualquiera de 4 caracteres" frente al prefijo T obligatorio en configuración.
15. **Respuesta 201 de factura:** los totales (5977 / 1075.86 / 7052.86) no corresponden al body del ejemplo.
16. **Descuentos globales:** la tabla resumen admite "solo porcentaje: 10", lo que contradice que `monto` es obligatorio y que `porcentaje` va en 0–1.
17. **`mto_valor_unitario`:** aparece en actualizar-comprobantes y no está documentado en facturas.
18. **Sucursal principal:** `is_principal` en el ejemplo de facturas frente a `es_principal` en configuración.
19. **Tasas de detracción:** 022 y 037 al 10% posiblemente desactualizadas (creo que la tasa SPOT vigente es 12%; verificar).
20. **Constante NRUS:** `REGIMEN_NRUS` en nrus.md frente a la afirmación de tasas-igv.md de que las constantes son `REGIMEN_GENERAL` y `REGIMEN_RUS`.
21. **Validación del régimen MYPE:** tasas-igv.md dice que la API "garantiza" la tasa si `tax_regime` está bien configurado, pero la tasa reducida solo sale del override.
22. **Cargos globales:** `cargos_globales` aparece en ejemplos pero se ignora en silencio.
23. **Recuperación de credenciales:** guarda un hash pero el middleware compara en claro (bug conocido).
24. **Endpoint beta de GRE:** apunta a Nubefact, no a SUNAT.
25. **Estado `procesando`:** existe en resúmenes, bajas y guías, pero no en facturas ni boletas.

#### 20. Lo que no está en estas docs
- `codigo_error` y `siguiente_accion`.
- Formatos detallados de ISC (`tip_sis_isc`, Cat. 08), de anticipos más allá del ejemplo y de las cuotas (solo hay ejemplo).
- La validación "suma de cuotas = total".
- La emisión de retenciones y percepciones.
- Las reversiones RR (solo se nombran).
- El payload exacto de los webhooks.
- El contrato de `/facturas/masivo` más allá de "array `facturas`, hasta 100".
- No se menciona SOAP explícitamente. Lo que sí se describe es que factura, boleta, NC y ND se envían por un job en cola; RC, RA y las guías van por ticket asíncrono; la GRE va por REST con OAuth2 y polling cada 15 s.

---

## Apéndice B2 — Detalle de rutas.md, consultas-reportes, operaciones, arquitectura, despliegue, _interno, sire y .scribe

No modifiqué ni creé nada en `C:\laragon\www\API-PRO`; solo lo leí. También abrí 5 archivos de código para contrastar la documentación con el código real (los marco como **[código]**). Los secretos están enmascarados como ***.

### Resumen técnico para reimplementar API-PRO (sin Notas de Venta ni Cotizaciones)

#### A. Lo que hay que saber primero (comparando todas las fuentes)

##### A.1 Formato de respuesta
**[código] `app/Http/Traits/ApiResponse.php`:**
- Éxito: `{"estado":"exito","mensaje":"OK","datos":...}`. Se agrega `"meta"` solo si se pasa; en la práctica las listas no lo usan.
- Error: `{"estado":"error","mensaje":"..."}` más `"errores":{...}` solo cuando hay detalle.
- Variante `errorAccionable`: `{"estado":"error","mensaje","codigo_error","siguiente_accion"?}`, HTTP 422 por defecto.
- `created()` devuelve 201 con mensaje por defecto "Creado exitosamente".
- `plan-sire.md` dice que el formato es `{success, message, data}`. **Es falso**; el real es estado/mensaje/datos.

**Excepciones al envelope:**
- `GET /api/v1/sunat/estado` es un closure en `routes/api.php`. Devuelve `{"estado":"exito","sunat":{...}}`, sin `mensaje` ni `datos`.
- `GET /api/health` devuelve `{"estado":"ok","timestamp","checks":[...]}`.

##### A.2 Paginación
**[código] `InvoiceController@index`.** El mismo patrón aplica a facturas, boletas y notas, porque comparten el trait `AppliesDocumentFilters`.
- Parámetro `por_pagina`: por defecto 15, máximo 100. La página es la estándar de Laravel (`page`).
- La respuesta va anidada así:
```
{"estado":"exito","mensaje":"OK","datos":{"datos":[...InvoiceResource],"paginacion":{"pagina_actual","ultima_pagina","por_pagina","total"}}}
```
- `?con=items,payments` carga relaciones (lista blanca). Si se filtra por `correlativo` exacto, los items se cargan automáticamente.
- Filtros del trait: `serie`, `correlativo`, `correlativo_desde`, `correlativo_hasta`, `fecha_desde`, `fecha_hasta`, `vencimiento_desde`, `vencimiento_hasta`, `sunat_status`, `estado_pago` (sobre la columna `payment_status`), `client_num_doc`, `client_tipo_doc`, `client_razon_social`, `cliente` (LIKE).

**SIRE:**
- [código] usa `por_pagina`: máximo 200 en comprobantes (por defecto 50) y máximo 100 en tickets (por defecto 20).
- **Discrepancia:** `sire.md` documenta `per_page`, pero el código y Scribe usan `por_pagina`.
- La forma de la respuesta en `sire.md` es `datos.{pagina_actual, datos[], total, por_pagina}`. En tickets se usa `paginate()->through()`, así que probablemente es el paginador de Laravel con claves en español. Hay que verificarlo.

##### A.3 Cabeceras
**Autenticación:**
- `X-Api-Key` y `X-Api-Secret`, **solo por cabecera**. Desde el fix S3 ya no se aceptan `?api_key=`.
- La comparación es timing-safe (`ResolveTenant`).
- El secreto está cifrado en reposo con el cast `encrypted`, no hasheado. `ResolveTenant` compara el valor en claro tras descifrar.

**`Idempotency-Key` (opcional):**
- Middleware `idempotency` sobre todo el grupo protegido `/v1`, con tabla `idempotency_keys` (tenant + key + hash del body → respuesta).
- Si la key se repite, devuelve la misma respuesta sin volver a emitir.

**Cabeceras de respuesta observadas (Scribe):**
```
cache-control: no-cache, private
content-type: application/json
x-ratelimit-limit: '30'
x-ratelimit-remaining: '29'
x-content-type-options: nosniff
x-frame-options: SAMEORIGIN
referrer-policy: strict-origin-when-cross-origin
x-permitted-cross-domain-policies: none
access-control-allow-origin: '*'
access-control-expose-headers: 'X-Usage-Documents-Used, X-Usage-Documents-Limit, X-Usage-Percent, X-Usage-Warning'
```
- HSTS solo se envía bajo HTTPS.
- `usage.headers` publica `X-Usage-Documents-Used`, `X-Usage-Documents-Limit`, `X-Usage-Percent` y `X-Usage-Warning`.

**Webhooks salientes:** `X-Signature: sha256=<hmac>` y `X-Webhook-Event: <evento>` (ver A.6).

**Descargas:**

| Archivo | Content-Type | Disposition y nombre |
|---|---|---|
| XML | `application/xml` | attachment, `{SERIE}-{CORRELATIVO}.xml` |
| CDR | `application/zip` | attachment, `R-{SERIE}-{CORRELATIVO}.zip` |
| PDF | `application/pdf` | **inline**, `{SERIE}-{CORRELATIVO}.pdf` |

Las descargas devuelven 404 si el archivo aún no existe.

##### A.4 Rate limiting
- **Limiter `api`, por tenant y según plan:** free 30, pro 120, business 300 req/min. Existe un bypass interno.
- **429:** `{"estado":"error","mensaje":"Demasiadas solicitudes. Intenta de nuevo más tarde."}`. En Scribe esa respuesta no traía `x-ratelimit-*`.
- **Limiter `sunat-tenant` (en los jobs):** `SUNAT_PER_TENANT_PER_MINUTE`, por defecto 120 comprobantes/min por RUC.
- **SIRE:** 30 req/min por tenant hacia SUNAT (`config/sire.php`, `RateLimited('sunat-sire')` con clave `by('sire:'.tenantId)`).

##### A.5 Middlewares por grupo
| Grupo | Middlewares |
|---|---|
| Público `/v1` | `throttle:api` |
| Protegido `/v1` | `resolve.tenant`, `idempotency`, `throttle:api`, `log.api` (tabla `api_logs`), `usage.headers` |
| Rutas que crean documentos SUNAT | además `license` + `check.limit:sunat` |

- Rutas de creación con esos dos middlewares: POST de facturas, `facturas/masivo`, boletas, notas-credito, notas-debito, guias-remision, `guias-remision-transportista`, resumenes, anulaciones, retenciones, percepciones y reversiones.
- `check.limit:internal` solo se usa en cotizaciones y notas de venta, que están fuera de alcance.
- `sire.enabled` aplica a todo `/sire` excepto `activar` y `desactivar`. Sin SIRE activo responde 403 con `error_code: sire_not_enabled`.

##### A.6 Webhooks firmados con HMAC
**[código] `app/Jobs/NotifyWebhookJob.php`:**
- Cola `webhooks`, `tries=3`, `backoff=[10,30,90]`, timeout HTTP 15 s.
- El body es `{"event","document","timestamp"(ISO8601)}`.
- La firma es `'sha256='.hash_hmac('sha256', $body, tenant->api_secret)`.

Ejemplo literal de payload (`00-primeros-pasos.md`):
```json
{
  "event": "document.sent",
  "document": {
    "id": 123, "tipo_documento": "01", "numero_completo": "F001-45",
    "sunat_status": "aceptado", "sunat_code": "0", "sunat_description": "...", "hash_cpe": "..."
  },
  "timestamp": "2026-10-03T10:21:05-05:00"
}
```
- Eventos: `document.sent`, `document.rejected`, `document.status_updated`, `document.timeout_sunat`, y los equivalentes `summary.*`, `voided.*`, etc.
- El campo del tenant se llama `url_webhook` en `PUT /empresa`, mientras que `plan-sire` lo llama `webhook_url`. Hay que verificar el nombre real.
- **Inconsistencia:** el handler de ejemplo en `resiliencia-sunat.md` lee `$payload['evento']` y `$payload['datos']['id']`. Eso es incorrecto; el payload real usa `event` y `document`.

**SIRE tiene su propio formato de payload:**
```json
{
  "event": "reconciliation.completed",
  "tenant_id": 1,
  "ruc": "20100000001",
  "timestamp": "2026-04-18T03:15:00-05:00",
  "payload": {
    "per_tributario": "202604",
    "total_sunat": 120,
    "suma_total": 29500.00,
    "proveedores_unicos": 35,
    "duplicados": 0,
    "sin_contraparte": 23
  }
}
```
- Eventos SIRE: `ticket.completed`, `ticket.failed`, `propuesta.processed`, `preliminar.registered`, `reconciliation.completed`.
- `reconciliation.completed` solo se dispara si se encuentra algo relevante.

##### A.7 Máquina de estados de envío (lo más importante para no repetir bugs)
- Estados de `sunat_status`: `pendiente → enviado → aceptado | rechazado`, más `anulado`.
- Emisión asíncrona: `POST` responde 201 con `sunat.estado="enviado"` y encola `SendDocumentToSunat` en la cola `sunat`.
- `enviar_automatico=false` o `solo_registro=true` deja el documento en `pendiente`. Se envía después con `POST /{tipo}/{id}/enviar`, o en el caso de boletas, mediante resumen diario.
- **Semántica de cada estado (fix 29):**

| Estado | Significado | ¿Se reintenta? |
|---|---|---|
| `pendiente` | Intencional: boleta para resumen o envío manual | **No** debe reencolarse automáticamente |
| `enviado` | En vuelo | Sí, lo recupera `sunat:reintentar-pendientes` (solo toca `enviado`) |
| `aceptado` / `rechazado` | Final | **Nunca** se reenvía un aceptado |

- **Códigos SUNAT (fix 30):**

| Código | Resultado |
|---|---|
| `0` | Aceptado |
| `1000–3999` | **Rechazo** |
| `>=4000` | Aceptado con observaciones |

  El bug original trataba cualquier código 3xxx como observación y marcaba aceptado.
- **Guards del job:**
  - Si el documento ya está `aceptado` o `anulado`, el job retorna sin enviar (idempotencia).
  - `WithoutOverlapping` por documento.
  - `recoverFromCdr`: antes de reenviar un `pendiente/SUNAT_TIMEOUT` y al agotar los reintentos, consulta el CDR en SUNAT y fija el estado real. Este fix resolvió el caso de unos 40 jobs duplicados de una misma boleta.
- **Reintentos:**
  - `tries=20`, timeout 90 s, backoff total de unas 15 h; los últimos 10 reintentos esperan 1 h cada uno.
  - Son retryables el `SoapFault` de red y los códigos SUNAT 0, 100, 109, 500, 1033 y 2800.
  - Un código permanente o un certificado inválido rechazan de inmediato.
- **Al agotar los reintentos:** el documento queda `pendiente` (no `rechazado`) con `sunat_code=SUNAT_TIMEOUT` y se emite el webhook `document.timeout_sunat`. Se reenvía con `POST /{tipo}/{id}/reenviar`.
- **Workers:** `queue:work --tries=1`, porque el job maneja sus propios `release()`.

##### A.8 Circuit breaker de SUNAT
- Clase `SunatCircuitBreaker`. Guarda el estado en Cache (Redis en producción) con clave por endpoint SUNAT (beta o producción).
- Parámetros: `failureThreshold=5`, `failureWindowSeconds=60`, `openTtlSeconds=300`, `halfOpenTtlSeconds=30`.
- Transiciones: CLOSED → (5 SoapFaults en 60 s) → OPEN → (al vencer el TTL) → HALF_OPEN, donde pasa un solo job de prueba. Si la prueba va bien vuelve a CLOSED; si falla vuelve a OPEN con el TTL duplicado.
- En OPEN el job no toca SUNAT y hace `release(300 + random(0,120))` (jitter).
- CLI de operación: `sunat:circuit status|open|close [--entorno=]`.
- Respuestas literales de `/api/v1/sunat/estado`:
```json
{
    "estado": "exito",
    "sunat": {
        "disponible": true,
        "circuit": "closed",
        "fallas": 0,
        "entorno": "produccion"
    }
}
```
```json
{
    "estado": "exito",
    "sunat": {
        "disponible": false,
        "circuit": "open",
        "fallas": 7,
        "entorno": "produccion"
    }
}
```
- `circuit` puede ser `closed`, `open` o `half_open`.
- **Discrepancia:** el valor real del entorno es `production`, no `produccion`. [código] `RegisterController` valida `entorno` con `in:beta,production`, guarda `tenants.environment` y las claves de `config/facturacion.php` son `beta` y `production`. Los ejemplos de la documentación, que dicen `produccion`, están mal.

##### A.9 Colas y Horizon
- Colas dedicadas: `sunat`, `webhooks`, `mail`, `default`, y para SIRE `sire-poll`, `sire-heavy`, `sire-process`.
- Un worker que solo escuche `default` deja los comprobantes sin enviar.
- Horizon tiene dos supervisores:
  - `sunat`: cola crítica con rate limit.
  - `otros`: `webhooks`, `mail`, las colas SIRE y `default`.
  - Sus `timeout` (85 y 60 s) quedan por debajo de `retry_after=90` de la conexión Redis.
- El dashboard `/horizon` solo es accesible para administradores.
- Redis con `maxmemory-policy noeviction`.
- Instalación local de Windows: `QUEUE_CONNECTION=sync` más una tarea programada que corre `schedule:run` cada minuto (`summaries:poll-pending`, `sunat:reintentar-pendientes`).
- Hosting compartido: `cron-jobs.php` ejecuta `schedule:run` y luego `queue:work --stop-when-empty` sobre `sunat,webhooks,mail,default` (máximo 45 s), con lock file que se considera obsoleto a los 70 s.
- Jobs: `SendDocumentToSunat`, `SendDispatchGuideToSunat`, `SendSummaryToSunat`, `SendVoidedToSunat`, `SendReversionToSunat`, `SendRetentionToSunat`, `SendPerceptionToSunat`, `CheckTicketStatus`, `CheckSummaryTicketStatus`, `NotifyWebhookJob`.

**Scheduler (`routes/console.php`):**

| Tarea | Frecuencia |
|---|---|
| `sire:poll-pending` | cada minuto |
| `ProcessRecurringPayments` | diario 06:00 |
| `CheckTrialExpiration` | diario 07:00 |
| `ResetMonthlyUsage` | día 1 a las 00:05 |
| `partitions:create --months=3` | día 1 a las 02:00 |
| `logs:purge --days=90` | domingos 03:00 |
| `sire:reconcile-all` | diario 03:00 |
| `backup:clean` / `backup:run` / `backup:monitor` | 01:00 / 01:30 / 09:00 |
| `certificados:verificar --alertar` | diario 08:00 |
| `ops:jobs-fallidos --alertar` | cada hora |
| `ops:salud --alertar` | cada 10 min |

- **Inconsistencia:** se agenda `partitions:create`, pero `escalamiento-horizon.md` dice que las tablas ya no están particionadas.

##### A.10 Multi-tenant y datos
- Un tenant equivale a una empresa (RUC) con un juego de credenciales. Todo se filtra por `tenant_id` (`forTenant`).
- El entorno se define por empresa (`tenants.environment`); no hay una variable global tipo `SUNAT_ENV`.
- Tablas de comprobantes separadas por tipo: `invoices`, `boletas`, `credit_notes`, `debit_notes`, `dispatch_guides`, `summaries`, `voided_documents`. Antes era una sola tabla `documents`.
- Índices:
  - `(tenant_id, sunat_status)`, `(tenant_id, fecha_emision)`, `(tenant_id, client_num_doc)`, `(tenant_id, created_at)`.
  - UNIQUE `(tenant_id, serie, correlativo)`.
  - `(tenant_id, cod_local, fecha_emision)` y `(tenant_id, payment_status, fecha_vencimiento)`.
  - `dispatch_guides (tenant_id, destinatario_num_doc)`.
- Producción usa PostgreSQL en un entorno y MySQL en otro. Hay diferencias conocidas, por ejemplo `whereJsonContains` en anulaciones.
- Credenciales del tenant cifradas: `sol_pass`, `client_secret`, `sire_client_secret`, `certificate_password` y `api_secret`.
- Auditoría append-only en `audit_logs` con `tenant_id`, `user_id`, `actor_tipo` (usuario/api/sistema), `actor_etiqueta`, `accion`, `subject_type`/`subject_id`, `descripcion`, `ip`, `user_agent`, `metadata`.
  - Acciones registradas: `credenciales.regeneradas`, `empresa.actualizada`, `certificado.actualizado`, `empresa.eliminada`, `comprobante.anulado`, `licencia.activada`, más acciones de equipo y usuarios.
  - El registro nunca lanza excepción.

##### A.11 Almacenamiento de XML, CDR y PDF
- Helper `App\Support\Storage\Comprobantes` con `put/get/exists/download/path/delete`. Escribe siempre en el disco privado `comprobantes` (`storage/app/private`, sin URL pública).
- Lee con fallback a `public` para archivos antiguos.
- El disco se elige con `COMPROBANTES_DISK`; S3 o MinIO están soportados.
- No hay URLs públicas de comprobantes: se eliminaron `getXmlUrl`, `getCdrUrl` y `getPdfUrl`.
- Certificado: `storage/app/private/tenants/{id}/certs/cert.pem`, con ruta relativa y lectura por disco. Se convierte de .p12/.pfx a PEM.
- Logos: disco `public`; en el PDF se incrustan como data URI base64 (dompdf).
- Estructura del ZIP de exportación: `{tipo}/{YYYY-MM}/{SERIE}-{CORRELATIVO}.{ext}`, por ejemplo `facturas/2026-09/F001-000123.xml` (correlativo a 6 dígitos en el ejemplo).
- SIRE guarda en `storage/sire/{tenant_id}/{periodo}/...`; el plan dice `storage/app/sire/...` y el disco se elige con `SIRE_STORAGE_DISK`.
- La documentación no detalla las rutas exactas en disco de XML y CDR por comprobante (solo el certificado y el ZIP). Habría que sacarlas de `DocumentStorageService`.
- Comandos: `comprobantes:mover-a-privado [--dry-run]` y `comprobantes:migrar-a-s3 [--dry-run] [--borrar-origen] [--incluir-certificados] [--incluir-logos] [--destino-publico=]`.

##### A.12 Cache
- Token OAuth de CPE y SIRE: cacheado por tenant (`sire_token:{tenant_id}`), TTL unos 59 min, margen de seguridad de 60 s.
- `/api/health`: cacheado unos 15 s.
- Circuit breaker y rate limiters: en Cache.
- Store: `database` por defecto; Redis en producción. Con varios nodos nunca debe usarse `file`.

##### A.13 Licencia
- Middleware `license`: solo bloquea a empresas en `production`. En beta no se pide licencia.
- `LICENSE_ENABLED=false` solo se respeta con `APP_ENV=local`.
- Activación: `POST {LICENSE_SERVER_URL}/api/v1/licencias/activar` con cabeceras `X-License-Key: ***` y `X-License-Secret: ***`, y body `{machine_id, domain, app_version, fingerprint}`.
- Servidor por defecto: `https://agenda.kodevo.es`.
- `LICENSE_GRACE_DAYS=7`: días que sigue operando si el servidor de licencias cae.
- Las credenciales también se pueden guardar en BD desde `/admin/licencia` (secreto cifrado). Las de BD tienen prioridad sobre `.env`.
- CLI: `license:activate`, `license:status`.
- Síntoma si no está activada (literal):
```json
"sunat": {
  "estado": "rechazado",
  "codigo": "CERT_ERROR",
  "descripcion": "Emisión no disponible: Esta instalación no está activada. Activa la licencia primero."
}
```
- Causas de fallo documentadas:

| Causa | Cómo se ve |
|---|---|
| URL del servidor equivocada | "no encontrada" / "inválida" |
| Config cacheada | "Faltan LICENSE_KEY/SECRET" |
| Dominio no coincide | "dominio no autorizado" |
| Cupo de instalaciones lleno | "máximo de instalaciones" |
| Servidor caído o sin red | `sin_conexion` |

#### B. Rutas: `rutas.md` frente a `routes/api.php`

En general coinciden. Estas son las discrepancias y matices:

1. **Total de rutas.** `rutas.md` dice 224 (`route:list --path=api`). Al contar `routes/api.php` salen unas 202 (con `debug/lookup`):
   - 5 públicas + 181 protegidas `/v1` + 3 bridge + 12 alias `/api/sunat` + 1 debug.
   - La diferencia probablemente son las rutas `horizon/api/*` de Horizon, que también caen en `--path=api`. No lo verifiqué, para no ejecutar artisan.
2. **El alias `/api/sunat/*` solo lleva `resolve.tenant` y `throttle:api`.** No pasa por `license`, `check.limit:sunat`, `idempotency`, `log.api` ni `usage.headers`. Con `POST /api/sunat/facturas` y `/boletas` se emite **saltándose la licencia y el límite del plan**. Hay que decidir si se replica tal cual o se corrige.
3. `POST /api/sunat/{facturas|boletas}/{id}/anular` apunta a `VoidedController@store`. Lo más probable es que ignore `{id}` y espere el body de anulaciones. Hay que verificarlo.
4. `POST /api/sunat/configuracion` corresponde a `TenantController@update` (en `/v1` es PUT). El comentario del código dice "facturas + boletas unificados", pero son rutas separadas.
5. **`/api/debug/lookup`** solo existe con `APP_DEBUG=true`. Prueba 5 formatos de token contra el servicio de RUC/DNI y **devuelve `token_preview` con los primeros 12 caracteres del token**. No conviene replicarlo.
6. **`/api/bridge/*`** no tiene middleware en el archivo de rutas, ni siquiera throttle. La validación de `X-Bridge-Key` (variable `SAAS_BRIDGE_SECRET`) ocurre dentro de `SaasAuthController`.
7. **Asimetrías reales (no son errores de la documentación, pero hay que respetarlas):**
   - Solo las boletas tienen `DELETE /{id}`; facturas, notas de crédito y notas de débito no.
   - Pagos existen solo en facturas y boletas (fuera de alcance, también en notas de venta).
   - Percepciones no tienen PDF; retenciones sí.
   - Resúmenes no tienen `GET /{id}`, solo `/estado`, `/xml`, `/cdr` y `/enviar`. Anulaciones sí tienen show.
   - Reversiones solo tienen POST.
   - Guías no tienen `reenviar`; tienen `/estado` y `/enviar`.
8. Parámetros de ruta de `apiResource`: `{sucursale}`, `{cliente}`, `{series}`. `rutas.md` los cita bien; Scribe los muestra como `{id}`.
9. `catalogos/producto-sunat/{codigo}` exige `[0-9]{8}`.
10. Ruta del CDR de guías (`GET guias-remision/{id}/cdr`): agregada en el fix 30; antes daba 404.
11. **Plan SIRE frente a lo real:**
    - El middleware real es `sire.enabled`; el plan lo llama `check.plan:sire`.
    - La constancia es `/sire/rce/constancia` con `?nom_constancia=`, sin `{periodo}`.
    - La ruta real es `complementar-propuesta`; el plan dice `complementar`.
    - `PUT /v1/empresa/sire-credenciales` **no existe**.
12. **Scribe frente a la documentación:**
    - `consultar-cdr` valida `tipo_documento` con `in:01,03,07,08`. Scribe muestra `1,3,7,8` por un problema de parseo del YAML.
    - `buscar-documento` valida `tipo` con `in:1,4,6,7,0` y `numero` de 8 a 15 caracteres. Son parámetros GET.

#### C. Resumen por archivo

##### `documentacion/rutas.md`
- Inventario completo, base `/api` y `/api/v1`. Las rutas GET aceptan HEAD.
- Grupos: público, bridge, debug, empresa y suscripción, sucursales, clientes, series, facturas (01), boletas (03), NC (07), ND (08), catálogos (UNSPSC y ubigeo), guías (09 y 31), resúmenes y anulaciones, retenciones (20), percepciones (40), reversiones, consultas, panel (11), exportación y reportes (1 + 7), SIRE (25), alias `/api/sunat` (12).
- El mapa contra `api.php` está en la sección B.

##### `documentacion/README.md`
- Índice general.
- Referencia rápida: `/api/v1`, alias `/api/sunat/*`, cabeceras de autenticación, envelope `{estado, mensaje, datos}` y health público.
- Se apoya en `referencia/catalogo-errores.md` para códigos HTTP, idempotencia, CDR y webhooks. Ese archivo no estaba en mi lista y no lo leí.

##### `documentacion/00-primeros-pasos.md`
- Flujo: credenciales → beta → `POST /facturas` (asíncrono, 201) → polling o webhook → descargas.
- Campos mínimos: `serie` (empieza con F, o B para boletas), `fecha_emision`, `cliente`, `items`.
- Los totales se calculan solos y `precio_unitario` incluye IGV.
- Respuesta 201 literal:
```json
{
  "estado": "exito",
  "mensaje": "Factura creada y encolada para envío a SUNAT.",
  "datos": {
    "id": 123,
    "serie": "F001", "correlativo": 45, "numero_completo": "F001-45",
    "totales": { "gravadas": 100.0, "igv": 18.0, "total": 118.0 },
    "sunat": { "estado": "enviado", "codigo": null, "descripcion": null, "hash_cpe": null },
    "archivos": { "xml": null, "cdr": null, "pdf": null }
  }
}
```
- PDF con `?formato=a4|a5|ticket-80|ticket-58`.
- Webhook y verificación HMAC: ver A.6. El receptor debe responder 2xx; se reintenta 3 veces.
- SDKs en `sdk/php` (`JorgeChavez\ApiSunat\Client`: `crearFactura`, `nuevaIdempotencyKey`, `estadoComprobante`, `descargarPdf`) y `sdk/ts` (`@jorge-chavez/api-sunat-sdk`).
- Masivo con `POST /facturas/masivo`. [código] responde 201 si se creó al menos una factura, 422 si ninguna, y `datos` trae `{resumen:{total_enviadas, creadas, errores}, facturas[], errores[]}`.

##### `documentacion/consultas-reportes/consultar-cpe.md`
**`GET /consultar-cpe`:**
- Usa la API oficial "Consulta Integrada CPE" de SUNAT (no SIRE), con OAuth `client_id`/`client_secret` del tenant configurados por `PUT /empresa`. El token se cachea 1 h.
- **No hay entorno beta**, solo producción.
- Parámetros: `ruc_emisor` (opcional, por defecto el RUC del tenant), `tipo_doc` (`01|03|04|07|08|R1|R7`), `serie` (4), `correlativo` (int), `fecha_emision` (**dd/mm/yyyy**), `monto`.
- Solo se pueden consultar comprobantes donde el RUC participa como emisor o receptor.
- Respuesta literal:
```json
{
  "estado": "exito",
  "datos": {
    "encontrado": true,
    "estado_cp": "1",
    "estado_cp_descripcion": "ACEPTADO",
    "estado_ruc": "00",
    "estado_ruc_descripcion": "ACTIVO",
    "cond_domi_ruc": "00",
    "cond_domi_ruc_descripcion": "HABIDO",
    "observaciones": [],
    "consultado_por_ruc": "20100000001",
    "ruc_emisor": "20555666777",
    "tipo_doc": "01",
    "serie": "F001",
    "correlativo": 1
  }
}
```
- Errores literales:
```json
{
  "estado": "error",
  "mensaje": "SUNAT rechazó las credenciales (client_id/client_secret). Verifica que las generaste desde Clave SOL → Empresas → Credenciales de API SUNAT → Consulta Integrada CPE. No hay entorno beta, solo producción.",
  "errores": { "sunat_code": "unauthorized_client" }
}
```
(401)
```json
{
  "estado": "error",
  "mensaje": "El tenant no tiene configurado client_id/client_secret para consultar SUNAT."
}
```
(422)
- Catálogos:
  - `estado_cp`: 0 NO EXISTE, 1 ACEPTADO, 2 ANULADO, 3 AUTORIZADO, 4 NO AUTORIZADO.
  - `estado_ruc`: 00 ACTIVO, 01 BAJA PROVISIONAL, 02 BAJA PROV. POR OFICIO, 03 SUSPENSION TEMPORAL, 10 BAJA DEFINITIVA, 11 BAJA DE OFICIO, 22 INHABILITADO-VENT.UNICA.
  - `cond_domi_ruc`: 00 HABIDO, 09 PENDIENTE, 11 POR VERIFICAR, 12 NO HABIDO, 20 NO HALLADO.
- El ejemplo en JavaScript de la documentación usa `data.data.estado_cp`. Es un error: debe ser `datos`.

**`POST /consultar-cdr`:**
- Body `{tipo_documento: 01|03|07|08, serie, correlativo}`; usa las credenciales SOL del tenant.
- Respuesta literal:
```json
{
  "estado": "exito",
  "datos": {
    "accepted": true,
    "code": "0",
    "description": "La Factura numero F001-123 ha sido aceptada",
    "notes": [],
    "cdr_base64": "UEsDBBQAAAgI..."
  }
}
```
- Las claves de `datos` están en inglés.
- Comparación de las tres consultas: CPE da el estado actual en SUNAT, `consultar-cdr` da la respuesta al envío en tiempo real, y `/{tipo}/{id}/cdr` devuelve el archivo guardado localmente.

##### `documentacion/consultas-reportes/descargas.md`
- `GET /comprobantes/exportar-zip`:
  - Parámetros: `fecha_desde` y `fecha_hasta` obligatorios (Y-m-d; desde 00:00:00, hasta 23:59:59), `tipo=xml|pdf|ambos` (por defecto xml), `documentos=facturas,boletas,notas-credito,notas-debito` (por defecto todos), `sucursal_id`, `estado=aceptado|pendiente|rechazado|todos`.
  - Rango máximo 366 días.
  - Nombre del archivo: `comprobantes_{RUC}_{desde}_{hasta}_{tipo}.zip`.
  - Omite en silencio los documentos sin archivo; si no hay ninguno devuelve **404 JSON**. Validaciones fallidas devuelven 422.
  - **No incluye CDR** ni guías, resúmenes, retenciones o percepciones.
  - Los PDF solo entran si se generaron antes: se generan bajo demanda y quedan cacheados.
  - Usa `addFromString(Comprobantes::get())`, así que funciona con S3.
- La tabla de descargas individuales y los Content-Type están en A.3. `?formato=` o `?format=` inválido devuelve 422.
- El alias `/api/sunat` solo expone pdf y xml de facturas y boletas.

##### `documentacion/consultas-reportes/panel.md`
- 11 endpoints del panel. Período por `?mes=YYYY-MM` o `?desde=&hasta=`; si no se envía nada, el mes actual.
- `indicadores` **ignora `?mes`**: los rangos son fijos.
- `documentos-recientes` acepta `?limit=` (por defecto 20, máximo 100).
- Implementado con SQL crudo y `UNION ALL` entre tablas; el objetivo de rendimiento es menos de 200 ms.
- La documentación incluye notas que corrigen las claves de versiones anteriores, así que estos ejemplos son la referencia actual. JSON literales:

`GET /panel`
```json
{
  "estado": "exito",
  "datos": {
    "periodo": {
      "desde": "2026-04-01",
      "hasta": "2026-04-30",
      "label": "Abril 2026"
    },
    "facturas": { "count": 45, "total": 85000.00 },
    "boletas": { "count": 128, "total": 12500.00 },
    "notas_credito": { "count": 3, "total": 1500.00 },
    "notas_debito": { "count": 1, "total": 200.00 },
    "notas_venta": { "count": 12, "total": 3400.00 },
    "grafico": [
      { "fecha": "2026-04-01", "ventas": 1200.00 },
      { "fecha": "2026-04-02", "ventas": 2300.00 }
    ],
    "top_productos": [
      { "nombre": "LAPTOP HP", "total": 44250.00 }
    ],
    "top_clientes": [
      { "nombre": "ACME SAC", "total": 25000.00, "documentos": 8 }
    ],
    "comparacion": {
      "ventas_actuales": 98400.00,
      "ventas_anteriores": 87200.00,
      "cambio_ventas_porcentaje": 12.8,
      "documentos_actuales": 185,
      "documentos_anteriores": 165,
      "cambio_documentos_porcentaje": 12.1
    }
  }
}
```
Este endpoint devuelve `notas_venta` aunque el módulo esté fuera de alcance; para mantener el contrato hay que conservar la clave.

`GET /panel/indicadores`
```json
{
  "estado": "exito",
  "datos": {
    "hoy":          { "documentos": 8,   "ventas": 3200.00,   "igv": 488.14,   "gratuitas": 0, "icbper": 0, "ticket_promedio": 400.00 },
    "ayer":         { "documentos": 10,  "ventas": 4100.00,   "igv": 625.42,   "gratuitas": 0, "icbper": 0, "ticket_promedio": 410.00 },
    "semana":       { "documentos": 45,  "ventas": 22500.00,  "igv": 3432.20,  "gratuitas": 0, "icbper": 0, "ticket_promedio": 500.00 },
    "mes_actual":   { "documentos": 173, "ventas": 98400.00,  "igv": 15010.17, "gratuitas": 0, "icbper": 0, "ticket_promedio": 568.79 },
    "mes_anterior": { "documentos": 165, "ventas": 87200.00,  "igv": 13301.69, "gratuitas": 0, "icbper": 0, "ticket_promedio": 528.48 },
    "anio_actual":  { "documentos": 820, "ventas": 450000.00, "igv": 68644.07, "gratuitas": 0, "icbper": 0, "ticket_promedio": 548.78 },
    "anio_anterior":{ "documentos": 780, "ventas": 420000.00, "igv": 64067.80, "gratuitas": 0, "icbper": 0, "ticket_promedio": 538.46 },
    "crecimiento": {
      "vs_ayer": -22.0,
      "vs_mes_anterior": 12.8,
      "vs_anio_anterior": 7.1
    }
  }
}
```

`GET /panel/estado-sunat`
```json
{
  "estado": "exito",
  "datos": {
    "periodo": { "desde": "2026-04-01", "hasta": "2026-04-30" },
    "total_documentos": 630,
    "por_estado": {
      "pendiente": 10,
      "enviado": 18,
      "aceptado": 592,
      "rechazado": 4,
      "anulado": 6
    },
    "por_tipo": {
      "facturas":      { "aceptado": 120, "enviado": 5, "pendiente": 2, "rechazado": 3, "anulado": 1 },
      "boletas":       { "aceptado": 450, "enviado": 12, "pendiente": 8, "rechazado": 1, "anulado": 5 },
      "notas_credito": { "aceptado": 18, "enviado": 1 },
      "notas_debito":  { "aceptado": 4 }
    },
    "observados": 2,
    "tasa_aceptacion": 94.0,
    "ultimos_rechazos": [
      {
        "tipo": "Factura",
        "numero": "F001-00000300",
        "codigo_sunat": "2325",
        "motivo": "El documento electrónico ingresado ha sido alterado",
        "fecha": "2026-04-18"
      }
    ]
  }
}
```
- `por_tipo` solo trae los estados presentes.
- `numero` lleva el correlativo a 8 dígitos.
- **Bug probable:** `observados` cuenta los aceptados con `sunat_code` 3xxx. Eso choca con el fix 30, donde 3xxx es rechazo y la observación es >=4000. Al reimplementar conviene usar >=4000 y validar con el dueño.

`GET /panel/cobranzas`
```json
{
  "estado": "exito",
  "datos": {
    "resumen": {
      "pagado":    { "documentos": 150, "monto_total": 85000.00, "monto_pagado": 85000.00, "saldo": 0.00 },
      "parcial":   { "documentos": 12,  "monto_total": 15000.00, "monto_pagado": 8000.00,  "saldo": 7000.00 },
      "pendiente": { "documentos": 25,  "monto_total": 32000.00, "monto_pagado": 0.00,     "saldo": 32000.00 }
    },
    "aging": {
      "por_vencer": 18000.00,
      "vencido_0_30": 8500.00,
      "vencido_31_60": 3000.00,
      "vencido_61_90": 1500.00,
      "vencido_mas_90": 0.00,
      "documentos_con_saldo": 25
    },
    "top_deudores": [
      {
        "ruc": "20555666777",
        "cliente": "ACME SAC",
        "documentos": 3,
        "saldo": 8500.00
      }
    ]
  }
}
```
`panel-version-anterior.md` dice "top 10 deudores".

`GET /panel/ventas-mensuales`
```json
{
  "estado": "exito",
  "datos": {
    "meses": [
      { "mes": "2025-05", "mes_label": "Mayo 2025", "ventas": 75000.00, "ventas_anio_anterior": 68000.00 },
      { "mes": "2025-06", "mes_label": "Junio 2025", "ventas": 82000.00, "ventas_anio_anterior": 71000.00 }
    ],
    "total_12_meses": 1024800.00,
    "total_12_meses_anterior": 940000.00,
    "crecimiento_porcentaje": 9.0,
    "promedio_mensual": 85400.00
  }
}
```

`GET /panel/por-sucursal`
```json
{
  "estado": "exito",
  "datos": {
    "periodo": { "desde": "2026-04-01", "hasta": "2026-04-30" },
    "total_general": 98400.00,
    "sucursales": [
      {
        "cod_local": "0000",
        "nombre": "Sede Principal",
        "facturas": { "cantidad": 45, "monto": 65000.00 },
        "boletas": { "cantidad": 135, "monto": 0.00 },
        "total": 65000.00,
        "share_porcentaje": 66.1
      },
      {
        "cod_local": "0001",
        "nombre": "Sucursal Norte",
        "facturas": { "cantidad": 20, "monto": 20900.00 },
        "boletas": { "cantidad": 75, "monto": 12500.00 },
        "total": 33400.00,
        "share_porcentaje": 33.9
      }
    ]
  }
}
```

`GET /panel/por-moneda`
```json
{
  "estado": "exito",
  "datos": {
    "periodo": { "desde": "2026-04-01", "hasta": "2026-04-30" },
    "monedas": [
      { "moneda": "PEN", "documentos": 265, "total": 85000.00, "igv": 12960.00 },
      { "moneda": "USD", "documentos": 10, "total": 3500.00, "igv": 534.00 }
    ]
  }
}
```

`GET /panel/clientes`
```json
{
  "estado": "exito",
  "datos": {
    "periodo": { "desde": "2026-04-01", "hasta": "2026-04-30" },
    "total_registrados": 137,
    "clientes_unicos_periodo": 92,
    "clientes_nuevos": [
      { "ruc": "20123456789", "nombre": "NUEVO SAC", "primera_compra": "2026-04-05" }
    ],
    "clientes_recurrentes": 85,
    "top_20": [
      { "ruc": "20555666777", "tipo_documento": "6", "nombre": "ACME SAC", "documentos": 12, "total": 35000.00 }
    ]
  }
}
```

`GET /panel/productos`
```json
{
  "estado": "exito",
  "datos": {
    "periodo": { "desde": "2026-04-01", "hasta": "2026-04-30" },
    "top_por_venta": [
      { "codigo": "P001", "descripcion": "LAPTOP HP PAVILION 15", "cantidad": 15, "total": 44250.00 }
    ],
    "top_por_cantidad": [
      { "codigo": "P002", "descripcion": "MOUSE LOGITECH", "cantidad": 230, "total": 13570.00 }
    ],
    "por_tipo_igv": [
      { "codigo": "10", "descripcion": "Gravado - Operación Onerosa", "items": 420, "total": 88000.00 },
      { "codigo": "20", "descripcion": "Exonerado - Operación Onerosa", "items": 35, "total": 2500.00 }
    ]
  }
}
```

`GET /panel/documentos-recientes`
```json
{
  "estado": "exito",
  "datos": {
    "documentos": [
      {
        "tipo": "factura",
        "id": 123,
        "numero": "F001-00000123",
        "fecha_emision": "2026-04-18",
        "cliente_documento": "20555666777",
        "cliente": "ACME SAC",
        "moneda": "PEN",
        "total": 7052.86,
        "estado_sunat": "aceptado",
        "emitido_en": "2026-04-18T14:30:00-05:00"
      }
    ]
  }
}
```

`GET /panel/alertas`
```json
{
  "estado": "exito",
  "datos": {
    "rechazados_7_dias": { "cantidad": 3 },
    "vencen_proximos_7_dias": { "cantidad": 5, "monto": 8500.00 },
    "vencidos": { "cantidad": 12, "monto": 15000.00 },
    "pendientes_envio_sunat": { "cantidad": 8 },
    "series_por_agotar": [
      { "tipo_documento": "01", "serie": "F001", "correlativo": 90500000 }
    ]
  }
}
```
Una serie se considera "por agotar" cuando su correlativo supera 90 000 000.

**Reportes (`ReportRequest`):**
- `fecha_desde` y `fecha_hasta` obligatorios (Y-m-d, máximo 366 días).
- Opcionales: `sucursal_id`, `serie`, `client_num_doc`, `estado_sunat` (`pendiente|enviado|aceptado|rechazado`), `estado_pago` (`pendiente|parcial|pagado`), `tipo_moneda` (`PEN|USD`), `agrupacion` (`dia|semana|mes`), `vencido` (bool), `formato=json|pdf` (por defecto json).
- `por-cliente` usa `client_num_doc`. Los nombres `cliente_doc`, `desde` y `hasta` no existen y devuelven 422.
- La documentación no trae ejemplos JSON de los reportes y Scribe tampoco.
- `documentos-internos` cubre cotizaciones y notas de venta, que están fuera de alcance.

##### `documentacion/operaciones/auditoria-acciones.md`
- `audit_logs` (ver A.10), panel `/admin/bitacora` y `ops:auditoria [--tenant] [--accion] [--limit]`.
- **Bug corregido:** `regenerarCredenciales` por API guardaba `sha256(secret)` y devolvía el secreto en claro, lo que rompía la autenticación. Ahora guarda lo mismo que entrega.

##### `documentacion/operaciones/backups-y-alertas.md`
- `OpsAlert` (crítico, aviso, info): siempre escribe en log y publica en `OPS_ALERT_WEBHOOK_URL` (Slack `text`, Discord `content` o campos propios).
- Deduplicación con `OPS_ALERT_THROTTLE_MINUTES=60`; nunca lanza excepción. El SMTP saliente está bloqueado en el VPS.
- Chequeos: certificados (SIN CERT, ROTO, VENCIDO, POR VENCER a 30 días), `failed_jobs` sobre umbral, BD, Redis, disco (mínimo 10% libre), storage escribible y circuit breaker. SUNAT no se pinguea directamente.
- `GET /api/health` usa el servicio `HealthChecks`. Devuelve 200 aunque esté degradado y **503** solo si cae un componente propio (BD, Redis, almacenamiento). Cacheado unos 15 s y sin datos sensibles. Literal:
```json
{
  "estado": "ok",
  "timestamp": "2026-10-03T12:00:00-05:00",
  "checks": [
    {"componente": "Base de datos", "estado": "ok", "detalle": "mysql"},
    {"componente": "SUNAT beta", "estado": "ok", "detalle": ""}
  ]
}
```
- Backups con spatie: BD, `storage/app/private` y `.env`, cifrados con `BACKUP_ARCHIVE_PASSWORD`. Retención de 7 días completos y luego diario, semanal y mensual. Destino `BACKUP_DISK`, S3 en producción. Requiere `mysqldump` o `pg_dump`.

##### `documentacion/operaciones/resiliencia-sunat.md`
- Reintentos, circuit breaker, `/sunat/estado` y webhooks: ver A.7 y A.8.
- Throughput: unos 700 ms por envío.

| Clientes | Workers sugeridos | Driver de cola |
|---|---|---|
| < 500 | 4 | database |
| 500–5k | 10 | database o redis |
| 5k–50k | 20 | redis |
| 50k+ | 30–50 en varios servidores | redis |

- Más de 50 workers simultáneos puede contar como abuso para SUNAT.
- Supervisor: `--max-jobs=500`, `queue:restart` en cada deploy.
- Playbook de incidente: el circuito abre solo, pasa a half_open a los 5 min y se puede cerrar a mano. Los `SUNAT_TIMEOUT` se reencolan en masa con `SendDocumentToSunat::dispatch(Invoice::class, $id)`.
- Nota: se restauró `languageLocaleID` en la plantilla Twig de Greenter `invoice2.1.xml.twig`, editada dentro de vendor. Hay que replicar ese detalle del XML.

##### `documentacion/arquitectura/arquitectura-distribuida.md`
- Regla: nada de estado en el disco local del nodo. BD, Redis (cache, cola, sesión) y archivos en S3 o MinIO.
- MySQL o PostgreSQL con TLS; read/write split configurable (réplica de lectura sin usar).
- Los huecos de rutas locales ya están resueltos: ZIP por contenido, logo en base64, certificado por disco y SIRE con `tempnam`.
- Variables S3: `AWS_*`, `AWS_ENDPOINT`, `AWS_USE_PATH_STYLE_ENDPOINT=true` para MinIO.

##### `documentacion/despliegue/despliegue-y-licencia.md`
- Licencia: ver A.13.
- Docker: imagen PHP 8.3-fpm-alpine; `start.sh` corre `migrate` y cachea. `storage/` debe ir en un volumen.
- Reconstruir la imagen puede cambiar el fingerprint y consumir un cupo de activación.
- Certificado: se sube .p12/.pfx con contraseña y se convierte a PEM. En beta se usa el certificado demo de SUNAT. `certificados:verificar`.
- El certificado y el usuario secundario SOL pueden tardar hasta 24 h en activarse en SUNAT.

##### `documentacion/despliegue/escalamiento-horizon.md`
- Colas, Horizon, `sunat-tenant` e índices: ver A.4, A.9 y A.10.
- Las boletas de alto volumen deben ir por resumen diario (unas 99% menos llamadas).
- Sentry: no-op si no hay DSN; `SENTRY_SEND_DEFAULT_PII=false`.

##### `documentacion/despliegue/hosting-compartido.md`
- `cron-jobs.php`: constantes `QUEUE_NAME='sunat,webhooks,mail,default'`, `MAX_EXECUTION_TIME=55`, `QUEUE_MAX_TIME=45`, `QUEUE_SLEEP=3`, `QUEUE_TIMEOUT=30`, `QUEUE_TRIES=3`, `LOCK_STALE_AFTER=70`.
- Logs en `cron-jobs.log` y `cron-jobs-error.log`.
- La lista de tareas del scheduler está en A.9.

##### `documentacion/despliegue/runbook-deploy.md`
- Hay dos entornos de producción: A (Hetzner, MySQL, Supervisor y cron) y B (Docker, Postgres, servicios `horizon` y `schedule:work`, uptime-kuma).
- La migración crea `idempotency_keys`, cifra `api_secret` y agrega los índices de reportes.
- Advertencias: nunca usar `git clean -fd` en el VPS. Recomienda rotar las credenciales compartidas por chat (`client_secret` y la contraseña del certificado). Valores ***.

##### `documentacion/despliegue/vps-desde-cero.md`
- Ubuntu 24 con PHP 8.3 (`soap`, `redis`, `bcmath`, `gd`, `intl`, `zip`), MySQL 8 (base `api_pro_v2`, utf8mb4), Nginx con `client_max_body_size 50M` y `fastcgi_read_timeout 120`, certbot.
- Worker: `--queue=sunat,webhooks,mail,default --tries=20 --max-time=3600`, `numprocs=2`. Scheduler por cron.
- Variables de GRE REST: `SUNAT_GRE_CLIENT_ID` / `SECRET` para producción y `SUNAT_BETA_GRE_CLIENT_ID` / `SECRET` para beta (valores ***).
- No existe `SUNAT_ENV`.

##### `documentacion/_interno/auditoria-roadmap.md`
- Estado: todo el roadmap marcado como hecho.
  - Seguridad: S1 (secreto cifrado), S2 (HMAC), S3 (autenticación solo por cabecera), S4 (security headers), S5 (auditoría).
  - Robustez: R1 (Idempotency-Key).
  - Escalabilidad: E1 (Horizon), E2 (índices).
  - Observabilidad: O1 (backups), O2 (Sentry), O3 (health), O4 (alertas).
  - Integración: D1 (Scribe en `/docs` y `/docs/openapi.yaml`, generado con `php artisan scribe:generate`), D3 (SDKs), D4 (primeros pasos), D5 (catálogo de errores).
- Datos útiles: CORS en `config/cors.php`, `/up` (health superficial de Laravel), RBAC, particionado mensual (contradice escalamiento-horizon).
- Queda pendiente S6: scopes por API key y rotación programada.
- Inexactitudes del propio documento: dice "68 endpoints documentados" en Scribe, pero hoy hay 185. Dice "response calls desactivadas (`only: []`)", pero el `00.yaml` sí tiene respuestas reales (ver más abajo). Además, las secciones 4 y 5 del roadmap aparecen duplicadas.

##### `documentacion/_interno/fix-envio-estados.md`
- Boleta que oscilaba entre aceptado, pendiente y rechazado: se agregaron el guard de idempotencia y `WithoutOverlapping`.
- Boletas que "se enviaban solas" pese a estar destinadas al resumen: el comando de reintento ahora solo toca `enviado`.
- Flujo de resumen diario:
  1. `POST /boletas` con `"solo_registro": true` o `"enviar_automatico": false`.
  2. Revisar con `GET /boletas?sunat_status=pendiente`.
  3. Generar el resumen.
- Nota de crédito desde el panel: no era un bug; faltaba el permiso RBAC `nota_credito.emitir`.
- Tests: `tests/Feature/Sunat/EnvioEstadoTest.php`.

##### `documentacion/_interno/fix-seguridad-storage.md`
- Los 3xxx se marcaban como aceptados: corregido con el umbral >=4000.
- Recuperación por CDR y CDR de guías.
- Migración de los comprobantes del disco público al privado. Antes eran accesibles sin autenticación en `/storage/...`.
- Detalle en A.7 y A.11. Tests: `GuiaCdrDownloadTest.php`.

##### `documentacion/_interno/panel-version-anterior.md`
- Versión anterior del documento del panel. Mismos 11 endpoints con sus parámetros por endpoint:
  - Admiten `?mes=` o `?desde=&hasta=`: `estado-sunat`, `por-sucursal`, `por-moneda`, `clientes`, `productos`.
  - No reciben período: `indicadores`, `cobranzas`, `ventas-mensuales`, `alertas`.
- Las claves de este documento están desactualizadas; las válidas son las de `panel.md`.

##### `documentacion/_interno/plan-sire.md`
- Diseño del módulo SIRE: namespace aislado `App\Sire\*`. Facturación no conoce a SIRE; SIRE solo lee de facturación.
- Tablas con prefijo `sire_`:
  - `sire_periodos`: unique `(tenant, per_tributario, cod_libro)`, `fase` (propuesta, preliminar o generado).
  - `sire_tickets`: unique `(tenant, num_ticket)`, `cod_estado_proceso`, `poll_attempts`, `last_polled_at`, `finished_at`, `nom_archivo_reporte`, `cnt_cp_*`, `archivo_local_path`, JSON de request y última respuesta.
  - `sire_comprobantes`: unique `(tenant, per_tributario, car_sunat)`, montos `decimal(15,2)`, `incluido`, `cod_inconsistencia`, `raw_line`.
  - `sire_upload_files`: guarda `sha256` y tamaño.
  - `sire_reconciliation_logs`.
- Columnas en `tenants`: `sire_enabled`, `sire_last_period_synced`, `sire_last_reconciliation_at`, `sire_client_id`, `sire_client_secret` (cifrada). Si las credenciales SIRE propias son null, se usan las generales del tenant.
- OAuth de SUNAT:
  - Grant `password` con `username="{RUC} {SOL_USER}"` y `password=sol_pass`.
  - Ruta `/clientessol/{client_id}/oauth2/token/` en `https://api-seguridad.sunat.gob.pe/v1`.
  - API en `https://api-sire.sunat.gob.pe/v1`, scope `https://api-sire.sunat.gob.pe`.
  - `cod_origen_envio=2`.
- Polling: intervalo de 10 s, máximo 180 intentos (unos 30 min), backoff x1.5 con tope de 60 s.
- Jobs: `PollTicketJob` (sire-poll) → `DownloadTicketFileJob` (sire-heavy) → `ProcessPropuestaJob` (sire-process); también `ReconcilePeriodoJob`.
- Comandos: `sire:poll-pending`, `sire:retry-failed`, `sire:reconcile-all`, `sire:cleanup-stale`.
- Uploads TUS con `ankitpokhrel/tus-php`.
- Gating por plan: `facturacion.plans.*.features`, donde business incluye `sire`.
- Las discrepancias entre el plan y lo implementado están en B.11.

##### `documentacion/sire/sire.md`
- 25 endpoints, solo el libro RCE `080000` (RVIE `140000` aparece listado).
- Toda operación pesada es asíncrona por ticket de 16 dígitos.
- Estados de ticket: 01 Pendiente, 02 En proceso, 03 Procesando, 05 Terminado, 06 Terminado con errores, 07 Error. Son finales 05, 06 y 07.
- Códigos HTTP:

| Código | Significado |
|---|---|
| 200 | Operación síncrona |
| 202 | Asíncrona, devuelve `num_ticket` |
| 401 | Credenciales API inválidas o rechazo de SUNAT |
| 403 | SIRE no activado (`sire_not_enabled`) |
| 404 | Recurso inexistente |
| 409 | Archivo de ticket aún no disponible |
| 422 | Validación local o código SUNAT |
| 500 | Error interno |
| 502 | SUNAT no disponible |

**JSON literales:**

Registro (aquí `api_key` y `api_secret` se enmascaran):
```json
{
  "estado": "exito",
  "datos": {
    "tenant_id": 1,
    "api_key": "***",
    "api_secret": "***"
  }
}
```
Ejemplo de registro con datos beta: `ruc=20100000001`, `sol_user=MODDATOS`, `sol_pass=MODDATOS`, `plan=business`, multipart con `certificado` y `contrasena_certificado`.

Activar (200):
```json
{
  "estado": "exito",
  "datos": {
    "sire_enabled": true,
    "ruc": "20100000001",
    "razon_social": "MI EMPRESA SAC",
    "mensaje": "SIRE activado. Ya puedes consumir los endpoints /v1/sire/*.",
    "token_preview": "***"
  }
}
```
`token_preview` expone un prefijo del token OAuth; mejor no replicarlo.

Activar, credenciales rechazadas (401):
```json
{
  "estado": "error",
  "mensaje": "Credenciales SIRE rechazadas por SUNAT. Verifica que client_id/client_secret tengan seleccionada la URI \"MIGE RCE y RVIE - SIRE\" en Menú SOL, y que sol_user/sol_pass sean correctos.",
  "errores": { "sunat_code": "unauthorized_client" }
}
```

`GET /sire/periodos?libro=rce|rvie`:
```json
{
  "estado": "exito",
  "datos": {
    "libro": "080000",
    "libro_desc": "Registro de Compras Electrónico",
    "ejercicios": [
      {
        "anio": "2026",
        "des_estado": "ACTIVO",
        "periodos": [
          { "per_tributario": "202601", "cod_estado": "01", "des_estado": "Pendiente" },
          { "per_tributario": "202602", "cod_estado": "02", "des_estado": "Generado" },
          { "per_tributario": "202603", "cod_estado": "01", "des_estado": "Pendiente" }
        ]
      }
    ]
  }
}
```

`GET /sire/rce/{periodo}/propuesta`:
- Es un **GET que responde 202** y dispara una acción.
- Parámetros: `formato=txt|csv|excel`, `fec_emision_ini`, `fec_emision_fin`, `cod_tipo_cdp`, `num_serie_cdp`, `num_cdp`, `num_doc_adquiriente`, `mto_desde`, `mto_hasta`.
```json
{
  "estado": "exito",
  "mensaje": "Solicitud aceptada",
  "datos": {
    "num_ticket": "2026040012345678",
    "per_tributario": "202604",
    "estado": "01",
    "mensaje": "Propuesta solicitada. Consulta el ticket para obtener el archivo."
  }
}
```

`POST /sire/rce/{periodo}/aceptar-propuesta` (202):
```json
{
  "estado": "exito",
  "datos": {
    "num_ticket": "2026040011111111",
    "per_tributario": "202604",
    "estado": "01",
    "mensaje": "Propuesta aceptada. El sistema cambiará al estado \"Preliminar registrado\" cuando el ticket termine."
  }
}
```

`POST /sire/rce/{periodo}/registrar-preliminar` (200, síncrono):
```json
{
  "estado": "exito",
  "datos": {
    "exitoso": true,
    "per_tributario": "202604",
    "respuesta_sunat": { "respuesta": "T" }
  }
}
```
Error cuando ya existe preliminar (422):
```json
{
  "estado": "error",
  "mensaje": "El registro electrónico ya se encuentra en el módulo de preliminar.",
  "errores": { "sunat_code": "1008" }
}
```

`GET /sire/rce/{periodo}/resumen`:
- Parámetros: `tipo=propuesta|preliminar|registro|preliminar_registrado|ajustes_posteriores|no_domiciliados|no_incluidos_excluidos` y `formato=txt|csv|excel`.
- Devuelve el archivo binario con `Content-Disposition: attachment; filename="resumen-202604-1.csv"`.

`GET /sire/rce/constancia?nom_constancia=LE...pdf`: devuelve el PDF.

`GET /sire/rce/{periodo}/comprobantes`:
- Parámetros: `fase=propuesta|preliminar|registrado`, `cod_tipo_cdp`, `num_doc_proveedor`, `incluido`, `por_pagina` (la documentación dice `per_page`), `sort_by=fec_emision|mto_total|razon_social_proveedor`, `sort_dir`.
```json
{
  "estado": "exito",
  "datos": {
    "totales": {
      "total_comprobantes": 120,
      "suma_bi_gravada": 25000.00,
      "suma_igv": 4500.00,
      "suma_total": 29500.00
    },
    "comprobantes": {
      "pagina_actual": 1,
      "datos": [
        {
          "id": 1,
          "car_sunat": "11-202604-0001",
          "fec_emision": "2026-04-15",
          "cod_tipo_cdp": "01",
          "num_serie_cdp": "F001",
          "num_cdp": "123",
          "num_doc_proveedor": "20100000002",
          "razon_social_proveedor": "PROVEEDOR SAC",
          "mto_bi_gravada": "1000.00",
          "mto_igv": "180.00",
          "mto_total": "1180.00",
          "cod_moneda": "PEN"
        }
      ],
      "total": 120,
      "por_pagina": 100
    }
  }
}
```
Los totales son números, pero los montos de cada fila van como **string**.

`GET /sire/tickets`:
- Parámetros: `per_tributario`, `estado`, `cod_proceso`, `finalizado`, `por_pagina`.
```json
{
  "estado": "exito",
  "datos": {
    "pagina_actual": 1,
    "datos": [
      {
        "num_ticket": "2026040012345678",
        "per_tributario": "202604",
        "cod_proceso": "10",
        "estado": "05",
        "estado_descripcion": "Terminado",
        "estado_enum": "TERMINADO",
        "finalizado": true,
        "exitoso": true,
        "archivo_disponible": true,
        "nom_archivo_reporte": "LE20100000001202604080000010000001.zip",
        "cnt_cp_informados": 120,
        "cnt_cp_error": 0,
        "poll_attempts": 3,
        "created_at": "2026-04-15T10:30:00Z",
        "last_polled_at": "2026-04-15T10:32:15Z",
        "finished_at": "2026-04-15T10:32:15Z"
      }
    ]
  }
}
```
- `POST /tickets/{n}/refrescar` consulta SUNAT al momento (servicio 5.31).
- `GET /tickets/{n}/archivo` devuelve 200 con el archivo, 409 si aún no está disponible o 404 si no existe en storage.

Uploads TUS (`reemplazar-propuesta` CodProceso 61, `no-domiciliados` 56, `complementar-propuesta` 54):
- Entrada en multipart (`archivo` .zip + `secuencia`) o en JSON (`{"txt": "...", "secuencia": 1}`; el sistema arma el ZIP con nombre posicional SUNAT).
- Límites: tamaño mayor a 0 KB y hasta 6 GB, solo .zip.
- Respuesta (202):
```json
{
  "estado": "exito",
  "datos": {
    "num_ticket": "2026040055555555",
    "per_tributario": "202604",
    "estado": "01",
    "archivo": "LE20100000001202604080000061000000001.zip",
    "mensaje": "Propuesta reemplazada. Siga el ticket."
  }
}
```

Ajustes posteriores:
- `{variant}` puede ser `actual`, `no-domiciliados`, `periodos-anteriores` o `periodos-anteriores-nd`.
- Acciones: `cargar` (TUS), `enviar` (body `{num_ticket_carga}` de 16 caracteres), `descargar` (`?formato=`) y `eliminar`.
- Respuesta de `cargar` (202):
```json
{
  "estado": "exito",
  "datos": {
    "num_ticket": "2026040088888888",
    "variant": "actual",
    "archivo": "LE20100000001202604080000059000000001.zip",
    "mensaje": "Ajustes cargados. Siga el ticket para confirmar."
  }
}
```
- Body de `eliminar`: `{"cod_ajuste_posterior":"AP-202604-001","detalles":[{"cod_tipo_cdp","num_serie_cdp","num_cdp","cod_car"}]}`. Respuesta:
```json
{
  "estado": "exito",
  "datos": {
    "variant": "actual",
    "per_tributario": "202604",
    "eliminados": 2,
    "respuesta_sunat": { "resultado": "OK" }
  }
}
```

`GET /sire/rce/{periodo}/reconciliar` (síncrono). Existen también `reconciliar-async` (job), el historial por período y el detalle por `{id}`.
```json
{
  "estado": "exito",
  "datos": {
    "tenant_id": 1,
    "per_tributario": "202604",
    "run_at": "2026-04-18T14:30:00Z",
    "totales": {
      "total_comprobantes": 120,
      "total_incluidos": 118,
      "total_excluidos": 2,
      "suma_bi_gravada": 25000.00,
      "suma_igv": 4500.00,
      "suma_total": 29500.00,
      "promedio_monto": 245.83,
      "fecha_min": "2026-04-01",
      "fecha_max": "2026-04-30"
    },
    "por_proveedor": [
      {
        "num_doc": "20100000002",
        "tipo_doc": "6",
        "razon_social": "PROVEEDOR SAC",
        "cantidad_cp": 15,
        "suma_bi": 10000.00,
        "suma_igv": 1800.00,
        "suma_total": 11800.00
      }
    ],
    "por_tipo": [
      { "cod": "01", "nombre": "Factura", "cantidad": 100, "monto": 28000 },
      { "cod": "07", "nombre": "Nota de Crédito", "cantidad": 20, "monto": 1500 }
    ],
    "cruce_local": {
      "proveedores_totales": 35,
      "proveedores_en_clients": 12,
      "proveedores_no_en_clients": 23,
      "relacion_bidireccional": [
        {
          "num_doc": "20100000002",
          "razon_social": "PROVEEDOR SAC",
          "me_emitio_en_periodo": true,
          "le_emiti_en_periodo": true,
          "cnt_facturas_venta": 3,
          "monto_ventas": 5000.00
        }
      ]
    },
    "alertas": {
      "duplicados": [],
      "outliers_monto": [
        {
          "id": 45,
          "car_sunat": "11-202604-0045",
          "proveedor": "20100000007",
          "razon_social": "OUTLIER SAC",
          "mto_total": 50000,
          "veces_promedio": 203.4
        }
      ],
      "con_inconsistencia_sunat": 2
    }
  }
}
```

Error genérico de SIRE:
```json
{
  "estado": "error",
  "mensaje": "Mensaje legible en español",
  "errores": {
    "sunat_code": "1007"
  }
}
```

Códigos de error SUNAT mapeados:

| Código | Mensaje |
|---|---|
| 1001 | El campo "numRuc" no fue enviado o está vacío |
| 1002 | El número de RUC debe tener 11 dígitos |
| 1005 | El campo "perTributario" no fue enviado |
| 1006 | Formato de perTributario no cumple con "yyyymm" |
| 1007 | El perTributario no debe ser mayor a la fecha actual |
| 1008 | El registro ya se encuentra en el módulo preliminar |
| 1009 | El registro ya ha sido generado |
| 1022 | Nombre del archivo vacío |
| 1024 | El archivo fue previamente enviado |
| 1044 | Formato del nombre del archivo incorrecto |
| 1346 | Archivo mayor a 6 GB |
| 1348 | La extensión debe ser .zip |
| 1350 | Tamaño del archivo debe ser mayor a 0 KB |
| 1518 | No existen documentos para exportar |
| 2270 | fecEmisionIni debe tener formato yyyy-mm-dd |
| `unauthorized_client` | Credenciales SIRE rechazadas |

- Workers SIRE: `sire-poll` (tries 1, timeout 60), `sire-heavy` (tries 3, timeout 600), `sire-process` (tries 3, timeout 300).
- Seguridad: el token se cachea por tenant, el storage está aislado por `tenant_id` y SIRE no toca los módulos de facturación. Tiene 84 tests.

##### `.scribe/auth.md` y `.scribe/intro.md`
- Autenticación con `X-Api-Key` más `X-Api-Secret`, sin query string.
- `base_url` de generación: `http://api-pro.test`. Entornos `beta` y `produccion` (el valor real es `production`, ver A.8). Recomienda `Idempotency-Key`.

##### `.scribe/endpoints/custom.0.yaml`
- Solo contiene la plantilla comentada de Scribe. **No define endpoints propios.**

##### `.scribe/endpoints/00.yaml` (unos 640 KB; muestreado)
- Un solo grupo "Endpoints", sin títulos ni descripciones. Tiene **185 endpoints, todos `api/v1/*`**, y coincide 1 a 1 con las rutas `/v1` de `api.php` (incluye cotizaciones y notas de venta).
- **No incluye** `/api/health`, `/api/bridge/*`, `/api/debug/lookup` ni los alias `/api/sunat/*`.
- Marca como `authenticated: true` incluso rutas públicas (`registro`, `planes`, `credenciales/recuperar*`), lo cual es incorrecto.
- El valor de ejemplo de `X-Api-Key` en `auth` es ***.
- Los parámetros salen de los FormRequest. Los de los GET aparecen como `bodyParameters`; por ejemplo, los filtros de reportes y SIRE tickets y comprobantes con `por_pagina`.
- Lo más útil está en `POST /facturas`, que trae el body completo con `cleanBodyParameters`:
  - Cabecera: `contrato_colaboracion{numero,tipo,descripcion,porcentaje}`, `serie`, `correlativo`, `cod_local`, `fecha_emision`, `fecha_vencimiento`, `tipo_operacion`, `tipo_moneda`, `forma_pago`.
  - Cliente: `cliente{tipo_doc,num_doc,razon_social,direccion,email,telefono}`.
  - Montos: `mto_oper_gravadas`, `mto_oper_exoneradas`, `mto_oper_inafectas`, `mto_oper_exportacion`, `mto_oper_gratuitas`, `mto_igv`, `total_impuestos`, `valor_venta`, `sub_total`, `mto_imp_venta`, `sum_otros_descuentos`.
  - Items: `codigo`, `cod_producto_sunat`, `descripcion`, `unidad`, `cantidad`, `precio_unitario`, `porcentaje_igv`, `tip_afe_igv`, `igv`, `isc`, `porcentaje_isc`, `tip_sis_isc`, `icbper`, `factor_icbper`, `mto_valor_unitario`, `mto_valor_venta`, `mto_base_igv`, `total_impuestos`, `descuentos[]{cod_tipo,monto_base,factor,porcentaje,monto}`.
  - Resto: `leyenda`, `detraccion{cod_bien,porcentaje,cta_banco,cod_medio_pago,monto}`, `percepcion{cod_regimen,porcentaje,monto,base}`, `total_anticipos`, `total_descuentos`, `observacion`, `leyendas[]{code,value}`, `cuotas[]{monto,fecha_pago}`, `guias[]{tipo_doc,nro_doc}`, `anticipos[]{tipo_doc,serie,correlativo,monto}`, `descuentos_globales[]{cod_tipo,porcentaje,monto,monto_base}`, `pagos[]{metodo,monto,referencia,monto_recibido,notas}`.
- **`registro`** (multipart): `ruc` (11), `razon_social`, `nombre_comercial`, `direccion`, `ubigeo` (6), `departamento`, `provincia`, `distrito`, `sol_user` (máx. 20), `sol_pass` (máx. 50), `entorno` (beta|production), `plan` (free|pro|business), `tax_regime` (general|rer|mype|rus), `igv_rate_override` (0–30), `nrus_categoria` (1|2), `client_id`, `client_secret`, más el certificado.
- **Respuestas:** a diferencia de lo que dice el roadmap, sí se ejecutaron response calls, pero casi no sirven:

| Estado | Cantidad |
|---|---|
| 429 | 155 |
| 401 | 26 |
| 422 | 2 |
| 200 | 2 |

  - Respuestas literales capturadas:
    - `{"estado":"error","mensaje":"Credenciales de API inválidas."}` (401)
    - `{"estado":"error","mensaje":"Demasiadas solicitudes. Intenta de nuevo más tarde."}` (429)
    - `{"estado":"error","mensaje":"Formato de certificado no soportado. Use .pfx, .p12, .pem, .cer o .crt"}` (422, registro)
    - `{"estado":"error","mensaje":"Token inválido o expirado."}` (422, `credenciales/recuperar/verificar`)
    - `{"estado":"exito","mensaje":"Si el RUC o email está registrado, recibirás las instrucciones en breve.","datos":null}` (200, `credenciales/recuperar`; no revela si el RUC existe)
  - `GET /planes` (200), literal:
```json
{"estado":"exito","mensaje":"OK","datos":[{"slug":"free","nombre":"Gratis","precio_mensual":"0.00","precio_anual":"0.00","limites":{"productos":100,"rfqs_month":1,"sucursales":1,"team_members":1,"reviews_month":1,"documents_month":30,"catalog_listings":5,"feed_posts_month":2,"ai_messages_month":10,"b2b_requests_month":3,"internal_documents_month":20},"caracteristicas":["facturacion","boletas","notas","guias_remision","cotizaciones","notas_venta","pagos","inventario_basico","reportes_basicos","feed_view","marketplace_browse","b2b_basic"]},{"slug":"pro","nombre":"Profesional","precio_mensual":"49.00","precio_anual":"490.00","limites":{"productos":-1,"rfqs_month":10,"sucursales":3,"team_members":5,"reviews_month":10,"documents_month":500,"catalog_listings":20,"feed_posts_month":20,"ai_messages_month":100,"b2b_requests_month":15,"internal_documents_month":100},"caracteristicas":["facturacion","boletas","notas","guias_remision","cotizaciones","notas_venta","pagos","inventario_basico","reportes_basicos","feed_view","marketplace_browse","b2b_basic","compras","inventario_avanzado","reportes_avanzados","crm","citas","feed_posts","marketplace_advanced","score_analytics"]},{"slug":"business","nombre":"Empresarial","precio_mensual":"99.00","precio_anual":"990.00","limites":{"productos":-1,"rfqs_month":-1,"sucursales":10,"team_members":15,"reviews_month":-1,"documents_month":-1,"catalog_listings":-1,"feed_posts_month":-1,"ai_messages_month":-1,"b2b_requests_month":-1,"internal_documents_month":-1},"caracteristicas":["facturacion","boletas","notas","guias_remision","cotizaciones","notas_venta","pagos","inventario_basico","reportes_basicos","feed_view","marketplace_browse","b2b_basic","compras","inventario_avanzado","reportes_avanzados","crm","citas","feed_posts","marketplace_advanced","score_analytics","finanzas","rrhh","contratos","produccion","whatsapp_business","custom_roles","audit_logs","soporte_prioritario","b2b_invoicing","b2b_unlimited","b2b_templates","feed_promoted","marketplace_unlimited","marketplace_promoted_listings","score_export"]}]}
```
  - En los planes, `-1` significa ilimitado y los precios van como string.
- En resumen, Scribe sirve para nombres y validaciones de parámetros, pero no aporta ejemplos de éxito de los endpoints de negocio.

#### D. Pendiente de verificar en código (no lo resuelve la documentación revisada)
- Forma exacta de `InvoiceResource` (show y list) más allá del ejemplo 201.
- Rutas en disco de XML y CDR por comprobante (`DocumentStorageService`).
- Respuesta de `exportar-zip` cuando no hay archivos (404 JSON, texto exacto).
- Qué hace `/api/sunat/*/{id}/anular` con `{id}`.
- Nombre del campo de webhook (`url_webhook` o `webhook_url`).
- Forma real de la paginación de SIRE.
- Claves de `ReportController` en JSON.
- `referencia/catalogo-errores.md`, que tiene el detalle de códigos HTTP e idempotencia, no estaba en la lista y no lo leí.
---

## Apéndice B3 — Documentos raíz, manuales GRE, guia-trasportista y actualizaciones-sunat

### B3.1 PROMPT-Integrar-Facturacion-SUNAT.md (guía de integración = contrato HTTP de referencia)

- Base `https://{dominio}/api/v1`; headers `X-Api-Key`/`X-Api-Secret` (el documento todavía dice que también se aceptan por query: **obsoleto**). Públicas: `POST /registro`, `GET /planes`, recuperación de credenciales.
- Envelope `{estado, mensaje, datos, meta?, errores? (422), codigo_error?, siguiente_accion?}`. Excepciones: listados con `datos.datos` + `datos.paginacion` (`GET /anulaciones` usa `meta`); pagos con JSON plano `{mensaje, estado_pago, monto_pagado, pagos[{…,vuelto}]}`; resúmenes/anulaciones/retenciones/percepciones con `datos` plano (sin `sunat{}`/`archivos`).
- Errores: 401 credenciales; 403 `codigo_error:"subscription_expired"` (+`plan_actual, recurso`); 422 `"Error de validación"` con claves con punto; 429 `codigo_error:"limit_reached"` (+`plan_actual, recurso, actual, limite, mejora_plan`). `datos.sunat.estado` `pendiente`/`enviado` no es error.
- `POST /registro` (multipart): `ruc` 11 único, `razon_social` ≤255, `direccion` ≤500, `ubigeo` 6, `sol_user` ≤20, `sol_pass` ≤50, `entorno` beta|production, `plan` free|pro|business (el registro público no puede tomar plan de pago), `tax_regime` rus|rer|mype|general, `nrus_categoria` 1|2, `certificado` ≤100 KB (pfx,p12,pem,cer,crt; `contrasena_certificado` obligatoria con pfx/p12), `logo` jpg/png ≤2 MB, `client_id`/`client_secret` GRE. 201 devuelve `api_key`/`api_secret` en claro una sola vez.
- Respuesta de comprobante: `id, tipo_documento, serie, correlativo, numero_completo, fecha_emision, tipo_moneda, forma_pago, cliente, totales{…}, items (?con=items), sunat{estado,codigo,descripcion,notas,hash_cpe}, archivos{xml,cdr,pdf}, estado_pago, monto_pagado, pagos (?con=payments), enviado_en, creado_en`.
- Estados `pendiente, enviado, aceptado, rechazado, anulado, anulacion_en_proceso`. PUT sobre aceptado → 422 `documento_aceptado_no_editable` + `siguiente_accion` (factura/boleta → NC; NC → baja).
- Listados: `por_pagina` (≤100), `con`, `fecha_desde/hasta`, `sunat_status` (CSV), `estado_pago`, `serie`, `correlativo`, `sucursal_id`, `cliente`, `client_num_doc`, `search`/`q`, `ordenar_por`, `orden`.
- `POST /facturas/masivo`: máx 100; 201 si se crea ≥1, 422 si ninguna; `resumen{total_enviadas,creadas,errores}`. `DELETE /boletas/{id}` solo pendiente/rechazado sin `hash_cpe`; facturas sin DELETE.
- NC: `doc_afectado_tipo` 01|03|12, Cat. 09, importe ≤ afectado (factura estricto, boleta +1), NRUS solo contra boletas. ND: Cat. 10; doc. afectado opcional solo con motivo 13 (IGV 0).
- RC: `fecha_resumen` hoy-7…hoy (Lima), `anular[]{id,motivo}`; identificador `RC-YYYYMMDD-001`. RA: solo 01/07/08 (03 → 422 `boletas_no_soportadas_en_ra`); plazo 7 días; códigos 0 y 187 = "aún procesando".
- Guías: `destinatario.tipo_doc` 1|6, `mod_traslado` 01/02, Cat. 20, `peso_total` > 0, ubigeos 6; público → `transportista` ≠ emisor y desde 2026-06-01 fecha de entrega al transportista; privado → `vehiculo` + ≥1 `conductor`. GRT: `remitente` ≠ emisor, `doc_relacionado[]` (01/03/09/31/50/52, `ruc_emisor` si 09/31), `vehiculo`, `conductor`, `datos_pagador_flete`; en beta `advertencias:[{codigo:"grt_beta_no_soportado"}]`; sin credenciales OAuth → 201 y luego `rechazado`; `GET /{id}/estado` → 202 si aún no hay ticket.
- Retención Cat. 23 (01 = 3 %, 02 = 6 %); percepción Cat. 22 (01 = 2 %, 02 = 1 %, 03 = 0.5 %), sin `/pdf`; anulación de ambas con `POST /reversiones`.
- Consultas: `GET /consultar-cpe` (`tipo_doc` 01/03/04/07/08/R1/R7, `fecha_emision` dd/mm/yyyy, `monto`); `POST /consultar-cdr` devuelve `cdr_zip` base64; `GET /comprobantes/exportar-zip` máx 366 días, ZIP síncrono.
- No funcionales: timeout 30–60 s, reintentos con backoff solo red/5xx + Idempotency-Key, nunca loguear `api_secret`, zona America/Lima. Enums de series: factura F, boleta B, guia_remision T, guia_transportista V, retencion R, percepcion P.

### B3.2 PLAN-PANEL-MULTIUSUARIO.md

RBAC del panel: `super_admin` (bypass), owner (su empresa), cajero (abilities granulares). Pivote `tenant_user(tenant_id, user_id, role owner|cajero, abilities json, is_active, UNIQUE)`. Permisos `{recurso}.{acción}` (emitir/editar/anular/reenviar/descargar sobre factura, boleta, nota_credito, nota_debito, guia_remitente, guia_transportista, resumen, anulacion, retencion, percepcion, reversion) y transversales (`cliente.gestionar, serie.gestionar, sucursal.gestionar, reporte.ver, exportar, consulta.cpe, config.editar, apikey.ver, equipo.gestionar, sire.gestionar`). "Empresa activa" en sesión; middleware `tenant.member`; controladores web reutilizan Actions/FormRequests de la API; contraseña temporal mostrada una vez. No afecta al contrato API.

### B3.3 LICENSING.md

Validación contra `LICENSE_SERVER_URL` solo al emitir en production. Dos barreras: middleware `license` (12 rutas de emisión, 403) y guard en firmado/envío (`guardEmisionAutorizada()` en `send` y `sendDespatch`). `LICENSE_ENABLED=false` solo con `APP_ENV=local`. Identidad por instalación (`storage/app/license/machine-id`), fingerprint sellado con `license:stamp` ("Copia ajena" si no coincide). Comandos `license:status`, `license:activate`. Ofuscación (yakpro-po / ionCube / SourceGuardian) del candado y de GreenterService. Replicar en cada stack (binarios compilados ayudan).

### B3.4 SEGURIDAD-REPO.md (auditoría OWASP 2026-10-15)

23/25 corregidos. Controles: no escalar admin→super_admin; nada de rutas de logs/caché sin auth; alias `/api/sunat/*` con licencia y tope de plan (⚠ no visible en `routes/api.php`, verificar); SSRF en `webhook_url`; throttle + `hash_equals` en bridge, `X-Internal-Token` y auth; gate `apikey.ver`; validación en `masivo` y `PUT`; 500 sin `getMessage()`; sin SVG en logos; secretos enmascarados en `api_logs`; token `/auth/from-saas` de un uso; registro público sin plan de pago; TTL corto en cache de tenant; `sol_user` oculto a solo-lectura; disco de certificados no servido; `trustProxies` restringido (`TRUSTED_PROXIES`); CSP report-only; CORS limitado (`CORS_ALLOWED_ORIGINS`); global scope de tenant solo en API; correlativos con lock + índice único. Decisiones aceptadas: SEC-009 `api_secret` cifrado reversible (es clave HMAC); SEC-024 ruta del logo predecible. Pendientes: `BACKUP_ARCHIVE_PASSWORD`, CSP enforcing.

### B3.5 README.md

Producción `https://apisunatv2.kodevo.es`. Colas `sunat, webhooks, mail, default`. Stack Laravel 12, PHP 8.2, MySQL, Greenter, React 19 + Inertia. `config/facturacion.php`: 120 comprobantes/min por RUC (`SUNAT_PER_TENANT_PER_MINUTE`), lookup RUC/DNI con `api.apis.net.pe`. Software propietario.

### B3.6 MANUAL PARA GUIAS DE REMISION REMITENTE Y TRASNPORTISTA

- `links.txt`: carpeta Google Drive, Google Doc y blog llama.pe sobre el servidor de pruebas GRE.
- `Manual URL – GRE.xlsx` (= `NUEVAS GRE SUNAT/ServicioREST_GRE-SEECont_v1.2_0.xlsx`): hoja REST1 (envío `POST` Bearer, body `{"archivo":{"nomArchivo":"RUC-TT-SSSS-N.zip","arcGreZip":"<base64>","hashZip":"<sha256>"}}`; codCpe 09/31; serie `T###`/`V###`; número 1–8 dígitos; `.zip` minúsculas; 200 `{numTicket (UUID), fecRecepcion}`; 422 `{cod,msg,errors[{cod,msg}]}` con 501 codCpe, 502 serie, 503/504 nomArchivo, 505 arcGreZip, 506/507 hash, 155–161 ZIP/XML; 500 `{cod,msg,exc}`) y REST2 (consultarTicket `GET`: `{codRespuesta 98|99|0, error{numError,desError}, arcCdr, indCdrGenerado}`; 0508 formato ticket, 0509 ticket inexistente/ajeno).
- `Manual_Servicios_GRE (1)_0.pdf` (= `NUEVAS GRE SUNAT/Manual_Servicios_GRE - Autenticación.pdf` = `guia-trasportista/manual/01…pdf`): credenciales API en menú SOL; token `POST` x-www-form-urlencoded `grant_type=password`, `scope=https://api-cpe.sunat.gob.pe`, `client_id`, `client_secret`, `username=RUC+UsuarioSOL`, `password=ClaveSOL` → `access_token`, `token_type`, `expires_in` 3600 (cachear). Errores 400/401/403/404/405/406/415/422/500/503; en ticket `errors[{codError,desError}]`.
- `Servidor de pruebas para GRE - API REST.docx`: los 3 servicios del servidor de pruebas Nubefact (`gre-test.nubefact.com`), enlace `https://cpe.sunat.gob.pe/node/116`. Solo valida XSD y parte del XSL.
- `ValidacionesGREv20250421_0.xlsx`: hojas General (0154, 0155–0161, 0306, 2325, 2335), Guía-Remitente2_0 (84 campos; `UBLVersionID 2.1`, `CustomizationID 2.0`, ID `T[A-Z0-9]{3}-[0-9]{1,8}`, IssueDate/IssueTime, tipo 09, remitente, destinatario, motivo, peso, modalidad, partida, firma; condicionales: docs relacionados Cat. 61, bultos, contenedores, fechas, indicadores, transportista/MTC, vehículos, conductores, georreferencia, puertos, ítems con código SUNAT/GTIN; ERR-2108 fecha > 1 día, 2329 futura; duplicados 1032/1033), Guía-Transportista2_0 (64 campos), Catálogos (01, 06 con B–G, 18, 20 motivos 01–09/13/14/17/18/19, 61, 62, 63, 64, 65), CódigosRetorno, Control de Cambios (2024-11-14 motivo 19, Cat. 61 código 91, ERR-3489–3495; 2025-04 Cat. 62; textos en mayúsculas).
- ZIPs: `Archivo XSD.zip` (`UBL-DespatchAdvice-2.1.xsd`), `Archivo XSL.zip` (`ValidaExprRegGreRemitente-2.0.1.xsl`, `ValidaExprRegGreTransportista-2.0.1.xsl`), `NUEVAS GRE SUNAT-…zip` (= carpeta `NUEVAS GRE SUNAT/`: XML de ejemplo `20000000001-09-TTT1-1.xml` GRR público, `-09-TTT1-2.xml` GRR privado, `-31-VVV1-1.xml` GRT; PNG de baja/consulta con clave SOL; `IMPORTANTE.docx`: GRE solo por REST, el orden de tags importa).

### B3.7 guia-trasportista/ (prototipo PHP sin Greenter; carpeta en `.gitignore`)

Flujo: `token.php` (password grant → `token.txt`) → `generar_xml.php` (DespatchAdvice UBL 2.1, `CustomizationID 2.0`, tipo 09/31, `DespatchSupplierParty` schemeID 6, `DeliveryCustomerParty`, `Shipment` con `GrossWeightMeasure`, `ShipmentStage/TransitPeriod`, `CarrierParty`, `RoadTransport`, `DriverPerson`, direcciones, `DespatchLine` con `unitCodeListID="UN/ECE rec 20"`) → `firmar_xml.php` (XMLDSig enveloped, exc-c14n, rsa-sha256/sha256, `ds:Signature Id="Sign"` en `ext:UBLExtensions`; Greenter usa rsa-sha1 por defecto, ambos aceptados) → `enviar_gre_completo.php` (zip `RUC-TT-SERIE-NUM.zip` con un único XML, base64, `hash('sha256', zipBytes)` hex, POST Bearer, guarda `numTicket` en `ultimo_ticket.txt`) → `consultar_ticket.php` (GET ticket, `codRespuesta`, `arcCdr` zip base64 con `R-*.xml`). `gre_test.json`: GRT V001-150, emisor 20161515648, motivo 01, modalidad 01, 150.5 KGM, 8 bultos, ubigeos 150101→150131, 2 ítems NIU (campos `numRuc, tipoDoc, serie, correlativo, envio{partida{ubigueo…}, vehiculo, chofer}, detalles[]`).
**Riesgo**: `config.php` contiene en claro credenciales SOL/GRE de producción de una persona real (`***`), y la carpeta tiene `certificado.pem/.pfx` y `token.txt` → no migrar ni copiar; en las nuevas implementaciones, secretos siempre en env/vault y cifrados por tenant.

### B3.8 actualizaciones-sunat/

- `RESOLUCIÓN DE SUPERINTENDENCIA.pdf`: R.S. N.° 000075-2026/SUNAT (29/04/2026), vigente 01/06/2026; modifica R.S. 155-2017, 279-2019 y 000112-2021: nuevos inscritos en RUC (MYPE, Especial, General) son emisores electrónicos desde la inscripción; salida del NRUS → desde el 1.º del mes siguiente; RV/RC por SIRE desde que nace la obligación. No cambia formatos XML.
- `Reglas de validación - actualizado al 24.07.2026.xlsx`: hojas General, Firma, Retenciones1_0, Percepciones1_0, Comunicación de Baja1_0, Resumen Diario1_1, Resumen de reversiones1_0, Factura2_0, Boleta2_0, NotaCredito2_0, NotaDebito2_0, LiquidacionCompra2_0, DAE-*, DOAE-Atribución, CDR-OSE-*, CódigosRetorno, Catálogos (01–65), Listados, Parámetros, Control de Cambios. Cambios 2026: 13/02 tasa 10.5 % (±1 de tolerancia; ERR-3462; OBS-4439; ventana 13–20/02 para rechazados); 01/08 Cat. 01 25/56, Cat. 51 0101 renombrado, RA admite 25/28/56 (ERR-2308/2310/2105/2375), ERR-0151/0159 nombres, NC/ND sobre 28/56 (ERR-3209), RR de liquidaciones 5 días (ERR-2958), Cat. 25 vuelve a UNSPSC v14 (retira 25.1–25.3); 01/09 Cat. 54 + 038/042/043/046/047; postergado a 01/01/2027: ERR-3496 (código producto 8 dígitos en Cat. 25; `00000000`/`99999999` admitidos), Cat. 11 RC gratuitas (05 desactivado; 06–09 nuevos), RC "Tasa del IGV" (ERR-2992/3504/3102) + IGV gratuitas + razón social adquiriente (ERR-2022 observación hasta 31/12/2026), Cat. 09 código 03 renombrado, Cat. 10 código 13 Penalidades (ERR-2524/3194/3507/2204/2400/2594), ERR-3261, OBS-4028→ERR-3286 en NC sobre boleta, ERR-3503, contrato de colaboración ERR-3497–3502, DAE-Operador ERR-3508–3514.
- `CCNU_MOD_2.xlsm`: buscador con macros del clasificador UNSPSC v14 (Cat. 25) — hojas Busqueda, Bienes y Servicios (~49 000 filas), Grupo, Segmento, Familia, Clase, Producto, Auxiliar. Fuente para el catálogo/validador de código de producto.
- Diferencias detectadas con el contrato actual: Cat. 09 SUNAT 01–13 (API sin 10–13; el 13 es necesario para NC de ventas a crédito/cuotas); Cat. 10 SUNAT solo 01/02/03/11/12/13 (API lista 04–09); Cat. 06 incluye B–H; Cat. 61 admite más códigos que los 6 que acepta la GRT de la API.

Textos extraídos (para consulta): `C:\Users\dev\AppData\Local\Temp\claude\C--laragon-www-API-SUNAT-MULTILENGUAJE\e17c130e-af6c-429b-8cde-6fe8cb396047\scratchpad\out\` (`reglas.txt`, `valgre.txt`, `urlgre.txt`, `manualgre.txt`, `resol.txt`, `servidor.txt`, `importante.txt`, `ccnu.txt`, `seecont.txt`, `cat.txt`).
