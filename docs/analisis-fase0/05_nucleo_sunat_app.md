# 05 — Núcleo SUNAT de la APLICACIÓN API-PRO (Laravel 12 + Greenter)

Fase 0 (análisis), solo lectura. Base: `C:\laragon\www\API-PRO`. Las rutas son relativas a esa carpeta.
Las referencias `archivo:línea` son de la versión leída. No se copian secretos: donde el código tiene un valor por defecto
de credencial, solo se indica que existe.

Alcance: código de la aplicación (builders, servicio Greenter, jobs, estados, almacenamiento, PDF/QR, reglas de negocio).
Los internos de `vendor/greenter/*` los cubre otro analista; aquí solo se cita lo necesario para entender qué espera la app.

---

## 0. Mapa rápido

| Pieza | Archivo | Rol |
|---|---|---|
| Fachada Greenter | `app/Services/Greenter/GreenterService.php` (984 líneas) | Construye `See`/`Api`, firma, envía, consulta tickets, inyecta XML que Greenter no modela, extrae hash |
| Builders | `app/Services/Greenter/Builders/*.php` | `array` → modelo Greenter (Invoice, Note, Despatch, Summary, Voided, Reversion, Retention, Perception) |
| Cálculo | `app/Services/DocumentCalculationService.php`, `app/Services/TaxRateService.php` | Ítems, totales, leyenda en letras, tasas IGV |
| Acciones de alta | `app/Actions/Documents/Create*Action.php` | Persisten el documento + ítems y encolan el envío |
| Resumen/Baja | `app/Services/Documents/SummaryService.php`, `VoidedService.php` | Reglas de RC (resumen), RA (baja) y RR (reversión) |
| Jobs | `app/Jobs/Send*ToSunat.php`, `Check*Status.php`, `NotifyWebhookJob.php` | Envío, polling de tickets, webhooks |
| Circuit breaker | `app/Services/Sunat/SunatCircuitBreaker.php` | Solo lo usa `SendDocumentToSunat` |
| Almacenamiento | `app/Services/Storage/DocumentStorageService.php`, `app/Support/Storage/Comprobantes.php` | XML, CDR y PDF en disco privado `comprobantes` |
| PDF | `app/Services/Pdf/*`, `resources/views/pdf/**` | dompdf (barryvdh) + QR con bacon/bacon-qr-code (SVG) |
| Consultas | `app/Actions/Documents/ConsultCdrAction.php`, `app/Services/SunatCpeConsultaService.php`, `app/Services/Consulta/ConsultaDocumentoService.php`, `app/Services/DocumentLookupService.php` | CDR, consulta integrada de CPE (REST), RUC/DNI |
| Config | `config/facturacion.php`, `config/sunat_catalogs.php`, `config/comprobantes.php`, `config/pdf.php`, `config/regimenes.php`, `config/horizon.php` | Endpoints, catálogos, discos, formatos |

`greenter/report` está en `composer.json:17` pero **no se usa** en `app/` (no hay referencias). Los PDF se hacen con Blade + dompdf.

Flujo general (factura/boleta/nota):
`Controller → FormRequest (validación) → CreateXxxAction (DB::transaction: serie lockForUpdate, cálculo, leyenda, create, items, dispatch job, status 'enviado') → SendDocumentToSunat (cola 'sunat') → GreenterService::send → BillResult → update de estado + guardado XML/CDR/PDF → evento + webhook`.

---

## 1. Construcción de objetos Greenter, por tipo de documento

### 1.0 Origen de los datos (`documentToArray`)

`SendDocumentToSunat::documentToArray()` (`app/Jobs/SendDocumentToSunat.php:355-413`) convierte el modelo Eloquent en el `array $data` que reciben los builders:

- `$data = $document->toArray()` (todas las columnas, con los casts JSON → array: `cuotas`, `detraccion`, `percepcion`, `anticipos`, `descuentos_globales`, `guias`, `extras`, `contrato_colaboracion`; `app/Models/Invoice.php:33-46`).
- `tipo_documento` = `getTipoDocumento()` del modelo (`Invoice`→'01', `Boleta`→'03', `CreditNote`→'07', `DebitNote`→'08').
- `fecha_emision` = `fecha_emision->format('Y-m-d H:i:s')` (con hora, para que el XML lleve `IssueTime`) (`:360`).
- `fecha_vencimiento` = `Y-m-d` (`:361-363`).
- `cliente` = {`tipo_doc`=`client_tipo_doc`, `num_doc`=`client_num_doc`, `razon_social`=`client_razon_social`, `direccion`=`client_direccion`} (`:364-369`).
- `sum_otros_descuentos` = Σ `monto` de `descuentos_globales` con `cod_tipo` '03' o '04' (`:370-383`). Nota: el comentario dice que es para que Greenter emita `cbc:AllowanceTotalAmount` (SUNAT 3300).
- `items` (`:385-410`): `codigo`, `cod_producto_sunat`, `descripcion`, `unidad`, `cantidad`, `precio_unitario`←`mto_precio_unitario`, `mto_valor_unitario`, `mto_valor_venta`, `mto_base_igv`, `porcentaje_igv`, `igv`, `tip_afe_igv`, `total_impuestos`, `isc` (?? 0), `tip_sis_isc` (?? '01'; **la columna no existe en el ítem**, siempre '01'), `icbper` (?? 0), `factor_icbper` (?? 0; **tampoco existe**), y `descuentos` si hay.

Columnas que tiene el ítem (`app/Models/InvoiceItem.php:10-15`): `codigo, cod_producto_sunat, descripcion, unidad, cantidad, mto_valor_unitario (decimal:4), mto_valor_venta, mto_base_igv, porcentaje_igv, igv, tip_afe_igv, isc, icbper, total_impuestos, mto_precio_unitario, descuento, descuentos`. No se guardan `porcentaje_isc`, `tip_sis_isc`, `mto_base_isc` ni `factor_icbper`.

Los montos del documento se calculan ANTES, al crear (ver §1.12 `DocumentCalculationService`), y se guardan en la cabecera; el builder vuelve a recalcular partes por línea a partir de `precio_unitario` (y los valores guardados actúan como "override").

### 1.1 Emisor (Company) — común a Invoice/Note

`InvoiceBuilder::buildCompany()` (`app/Services/Greenter/Builders/InvoiceBuilder.php:596-627`), idéntico en `NoteBuilder.php:354-384`:

| Propiedad Greenter | Origen |
|---|---|
| `Company::setRuc` | `tenant->ruc` |
| `setRazonSocial` | `tenant->razon_social` |
| `setNombreComercial` | `tenant->nombre_comercial` o, si vacío, `razon_social` (evita obs. 4092 por `cac:PartyName` vacío) |
| `Address::setCodLocal` | `$data['cod_local'] ?? '0000'` (en la acción: `sucursal->cod_local ?? data.cod_local ?? '0000'`, `CreateInvoiceAction.php:61`) |
| `Address::setDireccion` | `tenant->direccion ?? '-'` |
| `setUbigueo` / `setDepartamento` / `setProvincia` / `setDistrito` | `tenant->ubigeo/departamento/provincia/distrito` (solo si no vacíos) |

Retención/Percepción (`RetentionBuilder.php:114-135`, `PerceptionBuilder.php:110-131`): igual pero `nombre_comercial` solo si existe (sin fallback) y la dirección solo con `codLocal`, `direccion`, `ubigeo`.
Resumen, Baja, Reversión y Guía: `Company` solo con `ruc` + `razon_social` (`SummaryBuilder.php:74-79`, `VoidedBuilder.php:45-50`, `ReversionBuilder.php:51-56`, `DespatchBuilder.php:337-342`).

### 1.2 Cliente (Client) — Invoice/Note

`buildClient()` (`InvoiceBuilder.php:629-644`): `setTipoDoc(cliente.tipo_doc)`, `setNumDoc(cliente.num_doc)`, `setRznSocial(cliente.razon_social)`; `Address::setDireccion(cliente.direccion)` solo si viene.

### 1.3 Factura (01) y Boleta (03) → `Greenter\Model\Sale\Invoice`

`InvoiceBuilder::build()` (`InvoiceBuilder.php:36-361`):

Cabecera:
| Setter | Origen / regla |
|---|---|
| `setUblVersion('2.1')` | fijo (`:42`) |
| `setTipoOperacion` | `data.tipo_operacion ?? '0101'` (`:43`); se SOBREESCRIBE con `detraccion.tipo_operacion ?? '1001'` si hay detracción (`:236`) y con `percepcion.tipo_operacion ?? '2001'` si hay percepción (`:250`). Boletas de régimen RUS: la acción pone '0113' por defecto (`CreateBoletaAction.php:67-68`) |
| `setTipoDoc` | `data.tipo_documento` ('01'/'03') |
| `setSerie` | `data.serie` |
| `setCorrelativo` | `(string) data.correlativo` (sin ceros a la izquierda) |
| `setFechaEmision` | `new DateTime(fecha_emision 'Y-m-d H:i:s', America/Lima)` (`:47`) |
| `setTipoMoneda` | `data.tipo_moneda ?? 'PEN'` |
| `setCompany` / `setClient` | §1.1 / §1.2 |
| `setFecVencimiento` | si `fecha_vencimiento` (`:52-54`) |

Forma de pago y cuotas (`:57-75`):
- `forma_pago === 'Credito'` → `FormaPagoCredito($totalCredito)` donde `totalCredito = Σ cuotas.monto` (si > 0) o `mto_imp_venta`. Cuotas: `Cuota::setMonto(cuota.monto)` y `setFechaPago(DateTime(cuota.fecha_pago, Lima))`, después `setCuotas`.
- Cualquier otro valor → `FormaPagoContado()`.
- Ojo: el monto de crédito NO descuenta detracción, retención ni percepción automáticamente; depende de las cuotas que envíe el cliente.

Montos (solo se setean si > 0 "para evitar nodos de tributo vacíos") (`:78-179`):
| Setter | Origen |
|---|---|
| `setMtoOperGravadas` | `mto_oper_gravadas` (ajustado por anticipos, ver más abajo) |
| `setMtoOperExoneradas` | `mto_oper_exoneradas` (ajustado si anticipo sobre exonerada) |
| `setMtoOperInafectas` | `mto_oper_inafectas` (NO se ajusta por anticipo, regla SUNAT 4296) |
| `setMtoOperGratuitas` | `mto_oper_gratuitas` |
| `setMtoOperExportacion` | `mto_oper_exportacion` |
| `setMtoIGV` | `mto_igv` (recalculado si hay anticipo gravado) |
| `setTotalImpuestos` | sin anticipo: `total_impuestos ?? mto_igv`; con anticipo: `mtoIgv(neto) + mto_isc + mto_icbper + mto_ivap` (SUNAT 3294) (`:117-119`, `:155`) |
| `setValorVenta` | `valor_venta` (siempre) |
| `setSubTotal` | `sub_total` (siempre) |
| `setMtoImpVenta` | `mto_imp_venta` (siempre) |
| `setMtoIGVGratuitas` | `mto_igv_gratuitas`; si no viene, se calcula como Σ `igv` de ítems 11-16 (`:121-133`) — no es columna del modelo, así que en la práctica siempre se calcula desde ítems |
| `setMtoISC` + `setMtoBaseIsc` | si `mto_isc`: base = `mto_base_isc ?? mto_oper_gravadas` (`:163-167`; `mto_base_isc` no se persiste → siempre = gravadas) |
| `setIcbper` | `mto_icbper` |
| `setMtoBaseIvap` / `setMtoIvap` | `mto_base_ivap` / `mto_ivap` |
| `setSumOtrosDescuentos` | `sum_otros_descuentos` (§1.0) |

Leyendas (`:188-222`, clase `Greenter\Model\Sale\Legend`):
1. `1000` = `data.leyenda` (monto en letras guardado en la columna `leyenda`).
2. Las de `data.leyendas[]` ({code, value}) — no se persisten en el modelo (no hay columna), así que en el envío asíncrono normalmente no llegan.
3. `2006` "Operación sujeta a detracción" si hay `detraccion` y no venía ya.
4. `2000` "COMPROBANTE DE PERCEPCIÓN" si hay `percepcion`.
5. `2007` "Operación sujeta al IVAP" si algún ítem tiene `tip_afe_igv = '17'`.
No se agrega automáticamente `1002` (transferencia gratuita) ni `2001-2005/2008-2011`.

Guías relacionadas (`:225-231`): `Document::setTipoDoc(guia.tipo_doc)->setNroDoc(guia.nro_doc)` → `setGuias`.

Detracción (`:234-245`, `Greenter\Model\Sale\Detraction`):
`setCodBienDetraccion(cod_bien)`, `setCodMedioPago(cod_medio_pago ?? '001')`, `setCtaBanco(cta_banco)`, `setPercent((float) porcentaje)`, `setMount((float) monto)`. El monto y el porcentaje los manda el cliente (el catálogo 54 en `config/sunat_catalogs.php:229-266` solo valida el código). No hay conversión a PEN ni campos de detracción de transporte (1004: valor referencial, etc.).

Percepción (`:248-285`, `Greenter\Model\Sale\SalePerception`):
- `base = percepcion.mto_base ?? percepcion.base`, `monto = mto ?? monto`, `total = mto_total ?? base + monto`.
- `porcentaje` = `round(monto / base, 5)` (factor decimal, se deriva del monto para que base × factor = monto; evita la obs. 3114). Si base = 0, usa `porcentaje` y si > 1 lo divide entre 100.
- `codReg`: el cliente envía Cat. 22 (`01/02/03`), se mapea a Cat. 53 (`51/52/53`) (`:274-276`); si ya viene 51-53 se respeta.
- Setters: `setCodReg`, `setPorcentaje`, `setMtoBase`, `setMto`, `setMtoTotal`.
- La leyenda en letras se calcula sobre `percepcion.mto_total` cuando existe (`CreateInvoiceAction.php:51-56`).

Anticipos (`:85-113`, `:287-307`, `:337-355`):
- Cada anticipo → `Prepayment::setTipoDocRel(tipo_doc_rel ?? tipo_doc ?? '02')`, `setNroDocRel(nro_doc_rel ?? serie-correlativo)`, `setTotal(total ?? monto)`; `setAnticipos`, `setTotalAnticipos(total_anticipos ?? Σ total ?: Σ monto)`.
- Ajuste de base ANTES de setear montos:
  - Si hay gravadas: `anticipoBase = round(totalAnticipos / (1 + IGV%), 2)`; `gravadas = gravadas - anticipoBase`; `mtoIgv = round(gravadas × IGV%, 2)`; descuento global Cat.53 `04`.
  - Si solo inafecta/exonerada: `anticipoBase = totalAnticipos`, código `05`; resta solo de exoneradas (4297), no de inafectas (4296).
- Se agrega `Charge(codTipo=04|05, montoBase=anticipoBase, factor=1, monto=anticipoBase)` si no venía ya un descuento 04/05 (SUNAT 3287).
- Contradicción: `DocumentCalculationService.php:233-235` dice "NO se agrega AllowanceCharge 04"; el builder sí lo agrega. La cabecera guardada en BD tiene gravadas/IGV brutos y `mto_imp_venta` ya neto del anticipo (`DocumentCalculationService.php:298-306`).

Descuentos globales (`:310-358`, `Greenter\Model\Sale\Charge`), cada uno con `setCodTipo(cod_tipo ?? '02')`, `setMontoBase`, `setFactor`, `setMonto`:
- Si trae `porcentaje` (se trata como 0-100): `factor = porcentaje/100` (6 dec), `montoBase = gravadas`, `monto = round(base × factor, 2)`.
- Si trae `factor` y `monto_base`: se usan tal cual.
- Si solo `monto`: `montoBase = gravadas ?: monto`; `factor = round(monto/base, 6)`.
- Ver bug de escala de `porcentaje` en §9.

Ítems → `Greenter\Model\Sale\SaleDetail` (`buildItem`, `:363-594`):

| Setter | Regla |
|---|---|
| `setCodProducto` | `codigo ?? ''` |
| `setUnidad` | `unidad` |
| `setDescripcion` | `descripcion` |
| `setCantidad` | `cantidad` |
| `setMtoValorUnitario` | ver tabla de afectación; después `mto_valor_unitario` guardado lo pisa (salvo gratuitas) (`:469-471`) |
| `setMtoValorVenta` | idem; luego override con `mto_valor_venta` |
| `setMtoBaseIgv` | `mto_base_igv` guardado o la base calculada |
| `setPorcentajeIgv` | `TaxRateService::rateForItem` (prioriza `porcentaje_igv` del ítem); 0 en 20/30/40 y gratuitas inafectas |
| `setIgv` | `igv` guardado o calculado |
| `setTipAfeIgv` | `tip_afe_igv ?? (RUS ? '30' : '10')` |
| `setTotalImpuestos` | `total_impuestos ?? igv + isc + icbper` (SUNAT 3292) |
| `setCodProdSunat` | `cod_producto_sunat` si existe (UNSPSC, `cbc:ItemClassificationCode`) |
| `setMtoPrecioUnitario` | no gratuita: `precio_unitario`, o, si hubo descuento de línea, `(round(precio×cant,2) − descuentoConIgv)/cant` con 10 decimales (SUNAT 3270/3271). Gratuita: `0` (SUNAT 2640) |
| `setMtoValorGratuito` | gratuitas: `mto_valor_unitario`, o `mto_valor_venta/cantidad`, o `precio_unitario` |
| `setDescuentos` | Charges de línea (ver abajo) |
| `setIsc`, `setMtoBaseIsc`, `setPorcentajeIsc`, `setTipSisIsc` | si `isc > 0`: base = `mto_base_isc ?? valorVenta`; % = `porcentaje_isc` o `round(isc/base×100,2)`; sistema = `tip_sis_isc ?? '01'` |
| `setIcbper`, `setFactorIcbper` | si `icbper > 0`: factor = `factor_icbper` o `round(icbper/cantidad, 2)` |

Cálculo por tipo de afectación (Cat. 07):
| `tip_afe_igv` | valorUnitario | valorVenta | IGV |
|---|---|---|---|
| 10 (gravado) | `round(precio / (1+IGV%), 10)` | `round(vu × cant − descBase, 2)` | `round((round(precio×cant,2) − descConIgv) − valorVenta, 2)` (por diferencia, evita centavo) |
| 17 (IVAP) | `round(precio / 1.04, 10)` (tasa 4%) | idem | idem |
| 20, 30, 40 | `precio` | `round(vu × cant − descBase, 2)` | 0, % = 0 |
| 11-16 (gratuita gravada) | 0 | `round(valorRef × cant, 2)` (valor referencial) | `item.igv ?? round(base × IGV%, 2)` |
| 21, 31-36 (gratuita inafecta/exonerada) | 0 | `round(valorRefUnit × cant, 2)` (SUNAT 3224) | 0, % = 0; `baseIgv = valorVenta` |
| otro | `mto_valor_unitario ?? precio` | `round(vu × cant, 2)` | `item.igv ?? 0` |

ISC en la base del IGV (`:500-506`): si `isc > 0` y NO vienen `mto_base_igv` ni `igv`, `baseIgv = round(base + isc, 2)` e `igv = round(baseIgv × %, 2)`. En el flujo real siempre vienen (están guardados), así que el builder usa los valores calculados al crear (`DocumentCalculationService` hace la misma regla).

Descuentos de línea (Cat. 53 `00`/`01`) (`:389-431`, `:558-570`):
- `montoBase = round(valorUnitario × cantidad, 2)` (sin IGV).
- Con `porcentaje` (0-100): `factor = %/100` (6 dec); con `factor`: directo; con solo `monto`: `factor = monto/base`.
- `descuentoBase += monto`; `descuentoConIgv += round(round(precio×cant,2) × factor, 2)`.
- '01' se normaliza a **'00'** en el XML (`:419-426`), porque la app sí reduce la base (SUNAT 3271).
- Otros códigos (47/48 cargos, etc.) pasan sin recalcular.

### 1.4 Nota de crédito (07) / Nota de débito (08) → `Greenter\Model\Sale\Note`

`NoteBuilder::build()` (`NoteBuilder.php:32-149`):
- Igual que la factura: `setUblVersion('2.1')`, `setTipoDoc`, `setSerie`, `setCorrelativo`, `setFechaEmision`, `setTipoMoneda`, `setCompany`, `setClient`.
- **No** llama a `setTipoOperacion`, ni forma de pago, cuotas, detracción, percepción, anticipos, exportación, `valor_venta` ni `sub_total`.
- Documento afectado: si `doc_afectado_tipo` y `doc_afectado_serie` existen → `setTipDocAfectado(doc_afectado_tipo)`, `setNumDocfectado("{serie}-{correlativo}")` (`:49-52`). Para la ND motivo 13 (Penalidades) puede omitirse; entonces `GreenterService::stripEmptyNoteReference` elimina el `cac:BillingReference` vacío antes de firmar (`GreenterService.php:595-605`).
- Motivo: `setCodMotivo(cod_motivo)` (Cat. 09 NC / Cat. 10 ND), `setDesMotivo(des_motivo)`.
- Montos > 0: gravadas, exoneradas, inafectas, gratuitas, IGV; `setTotalImpuestos(total_impuestos ?? mtoIgv)`, `setMtoImpVenta`; `mto_igv_gratuitas` (solo si viene: aquí NO se calcula desde ítems), ISC + base ISC, ICBPER, IVAP, `sum_otros_descuentos`.
- Leyendas: solo `1000` (de `leyenda`) y `leyendas[]`. **No** agrega 2006/2000/2007.
- Guías: igual que la factura.
- Descuentos globales: `Charge(cod_tipo ?? '02', monto_base ?? 0, factor ?? porcentaje ?? 1, monto)` sin recalcular (`:136-146`).
- Ítems (`:151-352`): misma lógica que la factura con estas DIFERENCIAS:
  - Gratuitas (11-16 y 21/31-36): `valorVenta = 0` siempre (`:228-258`), mientras que la factura usa el valor referencial.
  - El código '01' de descuento de línea NO se normaliza a '00' (solo recalcula los '00') (`:187`).
  - Precio unitario efectivo con descuento: 2 decimales (`:308`) vs 10 en la factura.
  - `mtoValorGratuito = mto_valor_unitario ?? precio_unitario` (sin los fallbacks de la factura).

La NC/ND de boletas se envía de forma individual por `SendDocumentToSunat` (no por resumen).

### 1.5 Guía de Remisión Remitente (09) y Transportista (31) → `Greenter\Model\Despatch\Despatch`

`DespatchBuilder::build()` (`DespatchBuilder.php:45-133`). Los datos vienen de `SendDispatchGuideToSunat` (`app/Jobs/SendDispatchGuideToSunat.php:33-53`): `$guide->toArray()` con `fecha_emision`, `fecha_traslado` y `fecha_entrega_transportista` en `Y-m-d` (sin hora), y `destinatario` armado con las columnas `destinatario_*`. Para el tipo 31 sin `remitente`, usa `guide->remitente ??` {6, RUC del tenant, razón social del tenant}.

| Setter | Origen |
|---|---|
| `setVersion('2022')` | fijo |
| `setTipoDoc` | `tipo_documento ?? '09'` |
| `setSerie`, `setCorrelativo` | `serie`, `(string) correlativo` |
| `setFechaEmision` | `DateTime(fecha_emision, Lima)` (solo fecha → hora 00:00) |
| `setCompany` | RUC + razón social |
| `setObservacion` | `observacion` si existe |
| `setDestinatario` | `Client(destinatario.tipo_doc, num_doc, razon_social)` |
| `setTercero` | `tercero` (opcional) |
| `setComprador` | `comprador` (opcional) |
| `setAddDocs` | `doc_relacionado` (objeto o lista) → `AdditionalDoc::setTipo(tipo_codigo)->setNro(numero)`, `setTipoDesc(tipo_descripcion)`, `setEmisor(ruc_emisor)` (`:93-111`, `:138-147`) |
| `setEnvio` | `Shipment` (abajo) |
| `setDetails` | por ítem `DespatchDetail::setCantidad`, `setUnidad(unidad ?? 'ZZ')`, `setDescripcion`, `setCodigo(codigo ?? '')`, `setCodProdSunat(cod_prod_sunat)` (`:117-130`) |

`Shipment` (`:149-273`):
- `setCodTraslado(cod_traslado)` (Cat. 20), `setModTraslado(mod_traslado)` (Cat. 18), `setFecTraslado(DateTime(fecha_traslado))`, `setPesoTotal`, `setUndPesoTotal(und_peso_total ?? 'KGM')`, `setNumBultos` (si existe).
- Indicadores: `indicadores[]` + derivados: `datos_pagador_flete.tipo` remitente|subcontratador|tercero → `SUNAT_Envio_IndicadorPagadorFlete_*`; `datos_subcontratador` → `SUNAT_Envio_IndicadorTrasporteSubcontratado`. Alias corregido: `...TrasladoVehiculoCategoriaM1L` → `...TrasladoVehiculoM1L`. Los que no están en el catálogo interno solo dejan un warning en el log (riesgo 3388) (`:164-221`).
- `Direction(llegada_ubigeo, llegada_direccion)` + `setRuc`/`setCodLocal` opcionales; lo mismo para partida (`:224-233`).
- Transportista (solo si tipo ≠ 31): `Transportist::setTipoDoc`, `setNumDoc`, `setRznSocial`, `setNroMtc` (`:238-240`, `:275-287`).
- Vehículo: `Vehicle::setPlaca`, `setNroCirculacion(nro_circulacion ?? tuc)`, `setCodEmisor`, `setNroAutorizacion`, `setSecundarios([...])` (`:289-321`).
- Conductores: `conductores[]` o `conductor` (objeto o lista). El primero es `Principal` y los demás `Secundario` si no se indica `tipo` (SUNAT 3358). `Driver::setTipoDoc`, `setNroDoc`, `setTipo`, `setNombres`, `setApellidos`, `setLicencia` (`:249-270`, `:323-335`).

Post-procesado del XML ANTES de firmar (`GreenterService::sendDespatch`, `GreenterService.php:278-353`):
1. XML sin firmar con `XmlBuilderResolver` (twig `strict_variables`, sin caché) (`:285-292`).
2. Se eliminan los `<cbc:CompanyID/>` vacíos (obs. 4391) (`:297-298`).
3. GRR 09 con `mod_traslado = '01'` y `fecha_entrega_transportista`: se inyecta `<cac:LoadingTransportEvent><cbc:OccurrenceDate>Y-m-d</cbc:OccurrenceDate></cac:LoadingTransportEvent>` justo después de `</cac:CarrierParty>` (`:304-309`, `:363-378`). Exigido desde 2026-06-01.
4. GRT 31 (`:311-321`):
   - `stripGrtMotivoModalidad`: quita el primer `cbc:HandlingCode` y el primer `cbc:TransportModeCode` (`:397-411`).
   - `injectGrtShipmentStageNodes`: tras `</cac:TransitPeriod>` inserta `cac:CarrierParty` (ID con `schemeID=""` vacío, `RegistrationName` vacío, `CompanyID` = MTC si existe) y `cac:TransportMeans/cac:RoadTransport/cbc:LicensePlateID` = placa (`:478-504`).
   - `injectGrtSubcontratadorPagador`: `cac:Consignment` (ID `SUNAT_Envio`) + `cac:LogisticsOperatorParty` (RUC schemeID 6 + razón social) antes de `cac:ShipmentStage`; y si el pagador del flete es tercero, `cac:OriginatorCustomerParty` antes de `cac:Shipment` (`:431-476`).
   - `injectDespatchPartyGrt`: `cac:DespatchParty` (remitente: tipo/num doc con atributos del Cat. 06 y razón social en CDATA) después de `cac:Despatch/cac:DespatchAddress` (`:506-536`). Sin remitente → `RuntimeException`.
5. Firma con `Greenter\XMLSecLibs\Sunat\SignedXml` y el PEM del tenant (`:324-326`).
6. Envío `Api::sendXml($despatch->getName(), $signedXml)`, con reintento si la respuesta es "Token NO existe" (401) (`:330-347`).

### 1.6 Resumen diario (RC) → `Greenter\Model\Summary\Summary`

Datos desde `SendSummaryToSunat` (`app/Jobs/SendSummaryToSunat.php:32-53`): las boletas de `summary.document_ids`, con `estado = '3'` si `summary.tipo === 'anulacion'` y `'1'` en otro caso. **Nunca usa estado '2' (modificar).**

`SummaryBuilder::build()` (`SummaryBuilder.php:22-72`):
| Setter | Origen |
|---|---|
| `setFecGeneracion` | `fecha_referencia` (fecha de emisión de las boletas; Greenter la emite como `ReferenceDate`) |
| `setFecResumen` | `fecha_envio` (`IssueDate`) |
| `setCorrelativo` | `summary.correlativo` (3 dígitos con ceros) |
| `setCompany` | RUC + razón social |
| Por detalle `SummaryDetail` | `setTipoDoc('03')`, `setSerieNro("serie-correlativo")`, `setEstado`, `setClienteTipo(client_tipo_doc ?? '0')`, `setClienteNro(client_num_doc ?? '00000000')`, `setTotal(mto_imp_venta)`, `setMtoOperGravadas`, `setMtoOperInafectas`, `setMtoOperExoneradas`, `setMtoOperGratuitas`, `setMtoIGV`, `setPorcentajeIgv(porcentaje_igv ?? 18)`; opcionales `setMtoOperExportacion`, `setMtoOtrosCargos`, `setDocReferencia` |

El job **no pasa** `porcentaje_igv`, ISC, ICBPER, IVAP, exportación, otros cargos, percepción ni documento de referencia: el porcentaje sale siempre 18 (aunque el tenant tenga 10.5% o sea RUS) y faltan los tributos ISC/ICBPER/IVAP en el resumen. El builder lo soporta, pero el job no lo usa. Solo se incluyen boletas (las NC/ND de boletas no van en el resumen).

Identificador: `RC-{fecha_envio YYYYMMDD}-{correlativo}` (`SummaryService.php:84-87`, `SendSummaryToSunat.php:70-71`).

### 1.7 Comunicación de baja (RA) → `Greenter\Model\Voided\Voided`

`VoidedBuilder::build()` (`VoidedBuilder.php:21-43`): `setCorrelativo`, `setFecGeneracion(fecha_generacion)` (fecha de los documentos), `setFecComunicacion(fecha_comunicacion)`, `setCompany`; por cada detalle `VoidedDetail::setTipoDoc(tipo_documento)`, `setSerie`, `setCorrelativo`, `setDesMotivoBaja(motivo)`.
Datos: `voided.detalles` (JSON tal como llegó en la petición: `tipo_documento`, `serie`, `correlativo`, `motivo`) (`SendVoidedToSunat.php:31-36`). Identificador `RA-{fecha_comunicacion YYYYMMDD}-{NNN}` (`VoidedService.php:59-65`).

### 1.8 Reversión (RR) → `Greenter\Model\Voided\Reversion`

`ReversionBuilder::build()` (`ReversionBuilder.php:27-49`): igual que la baja pero con la clase `Reversion`; detalles con tipo 20/40. Identificador `RR-{YYYYMMDD}-{NNN}` (`VoidedService.php:213-219`). Se envía por el endpoint "otros CPE" (§2.2).

### 1.9 Retención (20) → `Greenter\Model\Retention\Retention`

`RetentionBuilder::build()` (`RetentionBuilder.php:27-66`), datos de `SendRetentionToSunat::retentionToArray()` (`app/Jobs/SendRetentionToSunat.php:256-287`; la percepción, en `SendPerceptionToSunat::perceptionToArray`, es análoga con `cliente_*`, `cobros`, `fecha_percepcion`, `imp_percibido`, `imp_cobrar`):
| Setter | Origen |
|---|---|
| `setSerie`, `setCorrelativo` | columnas |
| `setFechaEmision` | `fecha_emision->format('Y-m-d')` |
| `setCompany` | §1.1 (variante retención) |
| `setProveedor` | `Client(proveedor_tipo_doc, proveedor_num_doc, proveedor_razon_social, direccion)` |
| `setRegimen` | `regimen` (Cat. 23: 01 = 3%, 02 = 6%) |
| `setTasa` | `(float) tasa` (porcentaje, p. ej. 3) |
| `setImpRetenido` / `setImpPagado` | columnas; si alguno es 0/vacío se recalculan como Σ de detalles, redondeado a 2 |
| `setObservacion` | si existe |
| `RetentionDetail` | `setTipoDoc`, `setNumDoc`, `setFechaEmision(fecha_emision_doc)`, `setImpTotal`, `setMoneda(moneda ?? 'PEN')`, `setFechaRetencion`, `setImpRetenido(imp_retenido ?? round(impTotal×tasa/100, 2))`, `setImpPagar(imp_pagar ?? round(impTotal − retenido, 2))`, `setPagos([Payment(moneda, importe, fecha)])`, `setTipoCambio(Exchange(moneda_ref, moneda_obj, factor, fecha))` si existe |

El cálculo por defecto NO aplica el tipo de cambio (ver §9).

### 1.10 Percepción (40) → `Greenter\Model\Perception\Perception`

`PerceptionBuilder::build()` (`PerceptionBuilder.php:27-65`): como la retención, con `setProveedor(cliente)`, `setRegimen` (Cat. 22), `setTasa`, `setImpPercibido`, `setImpCobrado`; detalles `PerceptionDetail` con `setFechaPercepcion`, `setImpPercibido(?? round(total×tasa/100,2))`, `setImpCobrar(?? round(total + percibido, 2))`, `setCobros([Payment...])`, `setTipoCambio`.

### 1.11 Contrato de colaboración empresarial (todas las facturas/boletas/notas)

`GreenterService::setContratoColaboracion($document->contrato_colaboracion)` (`SendDocumentToSunat.php:119`) → `injectContratoColaboracion()` (`GreenterService.php:561-588`) inserta antes de `cac:AdditionalDocumentReference` o de `cac:Signature`:
`cac:ContractDocumentReference/{cbc:ID=numero, cbc:DocumentTypeCode=tipo, cbc:DocumentDescription=descripcion, cac:IssuerParty/cac:PartyLegalEntity/cac:ShareholderParty/cbc:PartecipationPercent=porcentaje (2 dec)}`. Solo si hay `numero`. RS 048-2026 (vigente desde 01/01/2027).

### 1.12 Cálculo al crear (`DocumentCalculationService`) y tasas (`TaxRateService`)

`calculateItems()` (`DocumentCalculationService.php:16-186`): la misma lógica que el builder, más:
- Con ISC y sin `mto_valor_unitario`: `vu = precio / ((1+ISC%)(1+IGV%))` (`:38-39`).
- ISC automático: `isc = round(valorVenta × porcentaje_isc/100, 2)` (`:138-140`).
- ICBPER automático: `icbper = round(factor_icbper × cantidad, 2)` (`:143-145`). El factor anual de `config/sunat_catalogs.php:295-304` (0.50 desde 2023) solo lo usa `SunatCatalog::icbperFactor()` (`app/Rules/SunatCatalog.php:91-97`); el cálculo no lo aplica solo.
- Gratuitas (11-16 y 21/31-36): `valorVenta = round(valorRef × cant, 2)`.

`calculateTotals()` (`:188-327`):
- Suma por afectación: 10 → gravadas + IGV; 17 → base IVAP + IVAP; 20 → exoneradas; 30 → inafectas; 40 → exportación; gratuitas → gratuitas + IGV gratuitas; ISC e ICBPER siempre.
- Descuento global '02' (con `porcentaje` 0-100 sobre gravadas si no viene monto): `gravadas −= descuento` y `IGV = round(gravadas × (IGV/gravadas), 2)`; '03' suma a `sumDescuentosNoBase`. Se normaliza cada descuento con `monto`, `monto_base`, `factor`.
- `total_impuestos = IGV + IVAP + ISC + ICBPER`; `valor_venta = gravadas + exoneradas + inafectas + exportación + baseIVAP`; `sub_total = valor_venta + total_impuestos`; `mto_imp_venta = max(0, sub_total − total_anticipos − sumDescuentosNoBase)`. Todo con `round(…, 2)` y `max(0, …)`.
- Los valores que mande el cliente en `$data` (p. ej. `mto_igv`) tienen prioridad sobre los calculados (`:308-326`).

`TaxRateService` (`app/Services/TaxRateService.php`):
- `defaultIgvRate`: `tenant.igv_rate_override` si existe; RUS → 0; resto → 18 (`:464-481`). La tabla Ley 31556 (10/10.5/15/18) solo se usa para sugerir valores (`:445-454`, `:489-492`).
- `rateForItem`: `item.porcentaje_igv` > IVAP 17 = 4% > gravado 10 y gratuitas 11-16 = tasa por defecto > resto 0 (`:522-542`).
- `defaultTipAfeIgv`: RUS → '30'; resto → '10' (`:505-508`).

Monto en letras (leyenda 1000), `generateLeyenda()` (`:329-343`):
- Formato exacto: `"{ENTERO EN LETRAS} CON {DD}/100 {SOLES|DOLARES AMERICANOS}"`. Ejemplo: 1180.50 PEN → `MIL CIENTO OCHENTA CON 50/100 SOLES`.
- `entero = (int) total`, `decimales = round((total − entero) × 100)`.
- Moneda: 'PEN' → `SOLES`; **cualquier otra** (incluido EUR) → `DOLARES AMERICANOS`.
- Reglas de `numberToWords` (`:345-399`): 0 → CERO, 100 → CIEN, 1.000 → MIL, 1.000.000 → UN MILLON, 10-15 especiales, 16-19 `DIECI…`, 21-29 `VEINTI…`, resto `DECENA Y UNIDAD`. Sin tildes (DIECISEIS, VEINTIDOS…).
- La leyenda se guarda en la columna `leyenda` y se envía como código 1000. La acción usa `percepcion.mto_total` como total cuando hay percepción (`CreateInvoiceAction.php:51-56`, `CreateBoletaAction.php:51-55`).

Redondeos (resumen):
- Valor unitario: 10 decimales al calcular, pero la columna `mto_valor_unitario` es `decimal:4` y en el envío el builder usa el valor guardado → el XML lleva 4 decimales.
- IGV de línea por diferencia (`total con IGV − valor venta`) para que cuadre el centavo.
- Factores de descuento: 6 decimales; percepción: 5 decimales; precio unitario efectivo: 10 (factura) / 2 (nota).
- Totales: `round(…, 2)` y montos negativos a 0.

---

## 2. Envío: See/Api, endpoints, credenciales, timeouts, circuit breaker, excepciones

### 2.1 Construcción de `See` (SOAP)

`GreenterService::createSee(?endpoint)` (`GreenterService.php:63-89`):
- `new See`; `setService(endpoint ?? config("facturacion.sunat.{env}.fe"))`, con `env = tenant.environment` ('beta' | 'production').
- `setCertificate(resolveCertificatePem())`.
- `setClaveSOL(tenant.ruc, tenant.sol_user, tenant.sol_pass)` (en Greenter el usuario SOL es RUC + usuario; las columnas están cifradas con el cast `encrypted`, `app/Models/Tenant.php:84-88`).
- `setCachePath(storage/app/cache/greenter)`.
- No se configuran timeouts ni opciones de SoapClient (se usan los valores por defecto de Greenter/PHP). El límite lo ponen el job (`$timeout = 90`, `SendDocumentToSunat.php:31`) y Horizon (`timeout` 85 para la cola `sunat`, 60 para las demás; `config/horizon.php` supervisores `sunat`/`otros`), con `retry_after = 90` en redis (`config/queue.php:71`).

`resolveSee(document)` (`:884-899`): las clases que contienen `Retention`, `Perception` o `Reversion` van a `createSeeRetention()` → endpoint `retention`; todo lo demás (Invoice, Note, Summary, Voided) va al endpoint `fe`.

### 2.2 Endpoints (`config/facturacion.php:35-56`)

| Clave | beta | production |
|---|---|---|
| `fe` (facturas, boletas, notas, RC, RA) | `https://e-beta.sunat.gob.pe/ol-ti-itcpfegem-beta/billService` | `https://e-factura.sunat.gob.pe/ol-ti-itcpfegem/billService` |
| `retention` (20, 40, RR) | `https://e-beta.sunat.gob.pe/ol-ti-itemision-otroscpe-gem-beta/billService` | `https://e-factura.sunat.gob.pe/ol-ti-itemision-otroscpe-gem/billService` |
| `consulta_cdr` | `https://e-beta.sunat.gob.pe/ol-it-wsconscpegem-beta/billConsultService` | `https://e-factura.sunat.gob.pe/ol-it-wsconscpegem/billConsultService` |
| `guias_auth` (OAuth2 GRE) | env `SUNAT_BETA_GRE_AUTH`, por defecto `https://gre-test.nubefact.com/v1` | env `SUNAT_GRE_AUTH`, por defecto `https://api-seguridad.sunat.gob.pe/v1` |
| `guias_cpe` (envío GRE) | env `SUNAT_BETA_GRE_CPE`, por defecto `https://gre-test.nubefact.com/v1` | env `SUNAT_GRE_CPE`, por defecto `https://api-cpe.sunat.gob.pe/v1` |
| `gre_client_id/secret` | env `SUNAT_BETA_GRE_CLIENT_ID/SECRET` (hay valores por defecto en el código; no se copian) | env `SUNAT_GRE_CLIENT_ID/SECRET` (por defecto '') |

En beta, la GRE pasa por el proxy de Nubefact (no por SUNAT).

### 2.3 Construcción de `Api` (GRE REST + OAuth2)

`createApi()` (`GreenterService.php:119-165`):
- `new GuzzleHttp\Client` (sin timeout configurado) y `Configuration` de `Greenter\Sunat\GRE`.
- `ApiFactory(new AuthApi($client, $config->setHost(guias_auth)), $client, new LaravelCacheTokenStore(ns), guias_cpe)`.
- `new \Greenter\Api(['auth'=>…, 'cpe'=>…], $factory)` con builder options twig `strict_variables=true, optimizations=0, debug=false, cache=false`.
- `setApiCredentials(clientId, clientSecret)`, por prioridad (`greCredentials`, `:200-208`): `tenant.client_id/client_secret` → `tenant.sire_client_id/sire_client_secret` (credenciales del panel) → config del entorno. Si faltan → `RuntimeException`.
- `setClaveSOL(ruc, sol_user, sol_pass)` y `setCertificate(pem)`.

Token OAuth2 (`app/Services/Greenter/LaravelCacheTokenStore.php`): implementa `TokenStoreInterface` de Greenter; guarda el `BasicToken` en la caché de Laravel con la clave `greenter:gre:token:` + `sha1(namespace|clientId)` y TTL = `token->getExpire()`. `namespace = env|guias_auth|guias_cpe|ruc|sol_user` (`GreenterService.php:167-178`).
Reintento por token: si hay `ApiException` 401 con "Token NO existe" (`:215-220`) o un resultado con error `code='API'` y ese mensaje (`:222-237`), se borra el token (`forgetGreToken`) y se reintenta una vez (envío `:330-347`, estado `:778-838`).

### 2.4 Firma y envío SOAP (`GreenterService::send`, `:607-725`)

1. `guardEmisionAutorizada()` (`:45-56`): en `production` exige licencia válida (`LicenseClient::check()`); si no, `RuntimeException('Emisión no disponible: …')`. En beta no aplica.
2. `resolveSee`.
3. XML sin firmar: `XmlBuilderResolver(['autoescape'=>false, 'cache'=>storage/app/cache/greenter])->find(class)->build(doc)`.
4. `stripEmptyNoteReference` y `injectContratoColaboracion`.
5. Firma `SignedXml::signXml` con el PEM; se guarda en `lastXml`.
6. `See::sendXml(get_class($doc), $doc->getName(), $signedXml)` (Greenter arma el ZIP y llama a `sendBill` o `sendSummary`).
7. Interpretación (ver §3).

Certificado (`resolveCertificatePem`/`toPem`, `:906-983`): `tenant->getCertificateContent()` lee, en orden, `certificate_path`, `certificates/{ruc}/cert.pem` y `tenants/{id}/certs/cert.pem` en el disco `local` (o una ruta absoluta) (`app/Models/Tenant.php:269-293`). Si es PEM, comprueba que tenga clave privada. Si es PKCS#12, prueba las contraseñas: `config('facturacion.certificate.password')`, `tenant.certificate_password` (cifrada), `config('facturacion.certificate.pse_ruc')` y vacío; devuelve `pkey . cert` en PEM. Nunca registra contraseñas. La subida y conversión las hace `CertificateService::convertToPem` (`app/Services/CertificateService.php:19-60`): pem/cer/crt (o DER), pfx/p12 con `openssl_pkcs12_read` y fallback al binario `openssl pkcs12 -legacy`; rechaza archivos sin clave privada.

`getXmlSigned()` (`:840-845`) existe pero no se usa en los flujos principales.

### 2.5 Consultas

- **Estado de ticket** (RC/RA/RR): `getStatus(ticket, ?endpoint)` (`:727-776`) → `See::getStatus(ticket)` (SOAP `getStatus` del billService). Respuesta normalizada: éxito → `{success, cdr_zip, code, description, notes, accepted = cdr->isAccepted()}`; error → `{success:false, error_code, error_message}`. Un `SoapFault`, un código no numérico o mensajes "failed to process / could not connect / connection refused / timed out" se convierten en `error_code = 'NETWORK_ERROR'`.
- **Estado GRE**: `getGreStatus(ticket)` (`:778-838`) → `Api::getStatus(ticket)`; `accepted = cdr ? cdr->isAccepted() : true`.
- **"Consulta CDR"** (`ConsultCdrAction`, `app/Actions/Documents/ConsultCdrAction.php:11-18`): llama a `getStatus("{ruc}-{tipo}-{serie}-{numero}", consulta_cdr)`, o sea manda esa cadena como *ticket* a `getStatus` del **billConsultService**. Ese servicio espera `rucComprobante/tipoComprobante/serieComprobante/numeroComprobante` (`getStatus`/`getStatusCdr`), y Greenter envía `['ticket'=>…]` (`vendor/greenter/ws/src/Ws/Services/ExtService.php:48-52`). **Probable defecto**: ver §9. La usa `POST /api/v1/consultar-cdr` (`app/Http/Controllers/Api/V1/ConsultController.php:17-39`) y `SendDocumentToSunat::recoverFromCdr` (`SendDocumentToSunat.php:280-336`).
- **Consulta integrada de CPE** (REST): `SunatCpeConsultaService::consultar` (`app/Services/SunatCpeConsultaService.php:44-90`), con `greenter/consulta-cpe`. Token OAuth2 `client_credentials` con scope `https://api.sunat.gob.pe/v1/contribuyente/contribuyentes`, usando `tenant.client_id/client_secret` (NO usa el fallback a `sire_*`). El token se guarda en caché con la clave `sunat_cpe_token:{tenant_id}` y TTL = `expires_in − 60` (mínimo 60). El filtro `CpeFilter` lleva `numRuc` (emisor), `codComp`, `numeroSerie`, `numero`, `fechaEmision`, `monto` (2 decimales), y la consulta se hace con el RUC del tenant. Mapas: `estadoCp` 0 NO EXISTE, 1 ACEPTADO, 2 ANULADO, 3 AUTORIZADO, 4 NO AUTORIZADO; `estadoRuc` 00/01/02/03/10/11/22; `condDomiRuc` 00/09/11/12/20 (`:18-42`).
- **RUC/DNI**: `DocumentLookupService` (apis.net.pe v1 `GET /v1/ruc?numero=` y `/v1/dni?numero=`, Bearer, timeout 8 s, caché 24 h solo si hay éxito) y `ConsultaDocumentoService` (api.json.pe POST, timeout 8 s; api.migo.pe GET v2, timeout 12 s), normalizados a {razon_social, direccion, departamento, provincia, distrito, ubigeo, estado, condicion, tipo_documento} (`app/Services/Consulta/ConsultaDocumentoService.php:154-294`).

### 2.6 Circuit breaker (`app/Services/Sunat/SunatCircuitBreaker.php`)

- Estados: `closed`, `open`, `half_open` (`:26-28`).
- Parámetros por defecto (`:31-34`): `failureThreshold = 5`, `failureWindowSeconds = 60`, `openTtlSeconds = 300`, `halfOpenTtlSeconds = 30`.
- Almacenamiento: caché de Laravel (Redis en producción). Claves `sunat_cb:{md5(endpoint)[0..8]}:{sufijo}` con los sufijos `failures`, `open_state`, `was_open`, `probe` (`:129-132`).
- `getState` (`:88-97`): si existe `open_state` → OPEN; si existe `was_open` → HALF_OPEN; si no → CLOSED.
- `isAvailable` (`:41-52`): CLOSED → true; OPEN → false; HALF_OPEN → `Cache::add(probe, 1, 30)` (solo el primero que llega pasa).
- `recordFailure` (`:68-80`): `Cache::increment(failures)`; en la 1.ª falla fija el TTL a 60 s; con ≥ 5 → `transition(OPEN, 300)`.
- `transition` (`:115-127`): `open_state` con TTL `ttl` y `was_open` con TTL `ttl + 30`. HALF_OPEN dura, por lo tanto, solo 30 s después de que expira OPEN.
- `recordSuccess` (`:54-66`): borra las 4 claves.
- `recordProbeFailure` (OPEN con TTL × 2) **no se usa en ningún sitio**.
- Uso: SOLO `SendDocumentToSunat` (`:95-125`), con el endpoint `fe` del entorno. Si no está disponible → `release(300 + random_int(0,120))`. `recordSuccess` se llama después de CUALQUIER respuesta de `send()` (aunque sea un error HTTP), y `recordFailure` solo en el `catch (\SoapFault)`.
- Exposición: `GET /api/v1/sunat/estado` (`routes/api.php:48-60`), el comando `sunat:circuit status|open|close` y `ops:salud`.

### 2.7 Manejo de excepciones (resumen)

| Lugar | Excepción | Tratamiento |
|---|---|---|
| `SendDocumentToSunat:122-134` | `\SoapFault` | `cb->recordFailure` + reintento con backoff |
| idem | `XmlSignException` | rechazado `CERT_ERROR` (sin reintento) |
| idem | `\RuntimeException` (incluye licencia, certificado ilegible, builder) | rechazado `CERT_ERROR` con el mensaje |
| Otros jobs SOAP (Summary/Voided/Reversion/Retention/Perception) | `SoapFault` → reintento; `XmlSignException` → rechazado `CERT_ERROR`; cualquier otra excepción se propaga (el job falla y Laravel lo reintenta según `tries`) | |
| `SendDispatchGuideToSunat:60-69` | `\Throwable`: si el mensaje contiene curl/timeout/connection/could not resolve o es `ConnectException` → reintento; si no → rechazado `BUILD_ERROR` | |
| `GreenterService::getStatus` | `SoapFault` → `NETWORK_ERROR` | |

Greenter captura internamente los `SoapFault` de `sendXml`/`getStatus` y los devuelve como error (código 'HTTP' u otro), por eso el `catch (\SoapFault)` de los jobs casi nunca se ejecuta (ver §9).

---

## 3. Lectura del CDR y estados

### 3.1 Qué se extrae

`GreenterService::send` (`:637-669`) con `BillResult`:
- `code` = `cdr->getCode()` (string), `description` = `cdr->getDescription()` (pasada a UTF-8 desde ISO-8859-1 si hace falta, `:870-882`), `notes` = `cdr->getNotes()` (array), `cdr_zip` = `getCdrZip()` (binario del ZIP `R-….zip`), `hash` = `DigestValue` del XML firmado que enviamos (XPath `//ds:Signature/ds:SignedInfo/ds:Reference/ds:DigestValue`, `:847-868`). El hash NO sale del CDR.
- Persistencia (`SendDocumentToSunat.php:137-147`): `sunat_status`, `sunat_code`, `sunat_description`, `sunat_notes` (cast array → JSON), `hash_cpe`, `sent_at`.

### 3.2 Mapeo de códigos (comprobantes con CDR síncrono 01/03/07/08, 20, 40)

Reglas de `GreenterService::send`:
| Caso | Resultado |
|---|---|
| Hay CDR y el código es numérico ≥ 4000 | aceptado con observación → `success=true, accepted=true` |
| Hay CDR y el código está entre 1000 y 3999 | **rechazo** (aunque Greenter diga `isSuccess()`), `success=false, error_code=code` (`:644`, `:661-668`) |
| Hay CDR, código 0 (o `isSuccess()` y no es rechazo) | aceptado |
| Sin éxito (fault): código numérico ≥ 4000 | aceptado con observación, `description='Aceptado con observación'`, `notes=[mensaje]` (`:695-706`) |
| Sin éxito: otro código | `success=false, error_code, error_message` |
| `SummaryResult` con éxito | `{success, xml, ticket}` |

En el job (`SendDocumentToSunat.php:136-215`):
- `success` → `sunat_status = accepted ? 'aceptado' : 'rechazado'`.
- Error reintentable (`isRetryableError`, `:218-239`): códigos `'0','100','109','500','1033','2800'` y de transporte `'HTTP'`, `'NETWORK_ERROR'` → backoff. (La rama de 4000+ en el job, `:179-209`, no se alcanza porque el servicio ya los devuelve como éxito.)
- Cualquier otro código → `markAsRejected` (`rechazado` + XML guardado + webhook `document.rejected`) y `event(DocumentRejected)`.
- Para resumen, baja, reversión, retención y percepción los reintentables son `'0','100','109','500','1033','2800'` (no incluyen 'HTTP' ni 'NETWORK_ERROR'); en GRE son `'0','100','109','500','1033'`.

Interpretación textual del propio código (`SendDocumentToSunat.php:220-224`): 0 aceptado; 100-1999 excepciones (100 timeout, 109 servicio caído, 500 interno); 2000-3999 validación (permanente salvo 2800); 4000+ observaciones. La regla que de verdad aplica `send()` es la de la tabla (1000-3999 = rechazo).

### 3.3 Valores de `sunat_status`

Columna enum/CHECK en `invoices, boletas, credit_notes, debit_notes`: `pendiente, enviado, aceptado, rechazado, anulado, anulacion_en_proceso` (`database/migrations/2026_03_10_140826_*.php:10`, `2026_05_14_000001_*.php:15` para PostgreSQL).
`dispatch_guides`: `pendiente, enviado, aceptado, rechazado` (`2026_03_04_000006_*.php:48`). `summaries` y `voided_documents`: `pendiente, enviado, aceptado, rechazado` (por defecto 'enviado'). `retentions`/`perceptions`: `string(30)` (admiten `anulado` y `anulacion_en_proceso`).

Transiciones (documentos 01/03/07/08):
```
create → 'pendiente' ──dispatch──► 'enviado' (CreateXxxAction; boleta solo_registro queda 'pendiente')
'enviado' ──CDR 0 / 4000+──► 'aceptado'
'enviado' ──CDR 1000-3999 o error permanente──► 'rechazado'
'enviado' ──20 intentos de error transitorio──► 'pendiente' + sunat_code='SUNAT_TIMEOUT' (webhook document.timeout_sunat)
'pendiente'(SUNAT_TIMEOUT) ──recoverFromCdr OK──► 'aceptado' | 'rechazado'
POST /{tipo}/{id}/enviar|reenviar (cualquier estado ≠ aceptado) ──► 'pendiente' → 'enviado'
PUT /facturas/{id} ──► recalcula y vuelve a 'pendiente' + dispatch
'aceptado' ──RA/RR creada──► 'anulacion_en_proceso'
'anulacion_en_proceso' ──ticket aceptado──► 'anulado'
'anulacion_en_proceso' ──ticket rechazado──► 'aceptado'
Boleta: 'pendiente' ──RC envío con ticket──► 'enviado'(+ticket) ──RC aceptado──► 'aceptado' | ──rechazado──► 'rechazado'
Boleta: 'aceptado' ──RC anulación creado──► 'anulacion_en_proceso' ──RC con ticket──► 'anulado' (¡antes del resultado!) ──RC rechazado──► 'aceptado'
```
Idempotencia: el job no reenvía documentos en `aceptado` o `anulado` (`SendDocumentToSunat.php:77-79`).

---

## 4. Tickets: resumen (RC), baja (RA), reversión (RR) y GRE

### 4.1 Resumen diario

Creación (`SummaryService::crear`, `app/Services/Documents/SummaryService.php:25-125`):
- Rango de fecha: `fecha_resumen ∈ [hoy − 7 días, hoy]` (hoy en Lima) (`:27-33`).
- Envío: boletas del tenant con `whereDate(fecha_emision, fecha)` y `sunat_status = 'pendiente'` (`:53-56`); se descartan las que ya están en otro RC de **envío** de la misma fecha en `pendiente|enviado|aceptado` (anti 2282) (`:66-80`).
- Anulación: boletas indicadas en estado `aceptado`; `validarAnulacion` (`:132-173`): emitidas hace ≤ 7 días, sin NC asociada y sin otro RC de anulación vigente (`whereJsonContains`). Las boletas pasan a `anulacion_en_proceso` (`:103-106`).
- Correlativo: 1 + máximo `correlativo` del día de envío, con `Cache::lock("summary_correlativo:{tenant}:{fecha}", 10)->block(5)`, rellenado a 3 dígitos (`:176-188`).
- `Summary` se crea con `tipo` = 'envio' | 'anulacion', `document_ids`, `fecha_referencia`, `fecha_envio` (hoy en Lima), `motivo`, `anulado_por`; con `enviarAuto` → `SendSummaryToSunat::dispatch` y `sunat_status = 'enviado'`.

Envío (`SendSummaryToSunat`, `tries = 10`, `backoff = [15,30,60,60,120,120,300,300,600,600]`):
- Guarda el XML en `{ruc}/0000/{fecha_referencia}/xml/RC-{YYYYMMDD}-{NNN}.xml` (también tras un `SoapFault`, `:113-130`).
- Con ticket: `summary.ticket`, `sunat_status = 'enviado'`; las boletas pasan a `anulado` (si es anulación) o `enviado` (si es envío), con `ticket` y `sent_at` (`:81-93`). `CheckSummaryTicketStatus` con un retraso de 15 s; webhook `summary.sent`.
- Error: reintentable (0/100/109/500/1033/2800) → backoff; con los intentos agotados → `rechazado` `MAX_RETRIES`. Si no es reintentable → `rechazado` + webhook `summary.rejected`. **No se revierten las boletas.**

Polling (`CheckSummaryTicketStatus`, `tries = 15`, `backoff = [15,30,30,60,60,60,120,120,120,300,300,300,600,600,600]` ≈ 55 min):
- Sale si no hay ticket o si el estado es `aceptado|rechazado`.
- `getStatus(ticket)` (endpoint `fe`).
- `success` (hay CDR): resumen `aceptado|rechazado` según `isAccepted()`, con code, description y notes; CDR en `…/cdr/R-RC-….zip` (`:220-230`). Boletas:
  - envío aceptado → `aceptado`;
  - envío rechazado → `rechazado` (solo las que no están `aceptado|anulado`);
  - anulación aceptada → `anulado`;
  - anulación rechazada → `aceptado` (sin pisar code/description).
  Genera los PDF de las boletas aceptadas. Webhook `summary.status_updated`.
- Error: código no numérico o 0/98/187 → `release(backoff)` (en proceso). Otro código → resumen `rechazado`; anulación → solo las boletas `anulacion_en_proceso` vuelven a `aceptado`; envío → boletas no finalizadas pasan a `rechazado` con el código.
- Códigos de ticket de SUNAT: 0 = procesado correctamente (con CDR), 98 = en proceso (Greenter lo devuelve como error con código 98), 99 = procesado con errores (con CDR de rechazo → rama `success` con `accepted=false`).
- Rescate: `summaries:poll-pending` cada 5 min (`routes/console.php:51-54`; `app/Console/Commands/SummariesPollPendingCommand.php`): resúmenes no finales con ticket y de ≤ 15 días; 6 consultas cada 10 min y luego cada 4 h (`last_polled_at`, `poll_attempts`). Los que no tienen ticket solo se registran.

### 4.2 Comunicación de baja (RA)

Creación (`VoidedService::crear`, `VoidedService.php:37-88`):
- Boletas (03) rechazadas: van por el RC de anulación.
- `validarDetalles` (`:96-174`): tipo en 01/03/07/08, el documento existe, está `aceptado`, `fecha_emision ≥ hoy − 7 días` (Lima), una factura no puede tener NC asociada, y no hay otra RA vigente que lo contenga (filtrado en PHP por la diferencia de JSON entre MySQL y PostgreSQL, `:310-330`).
- `fecha_comunicacion = request ?: now()->format('Y-m-d')` (**UTC**, ver §9).
- Correlativo: `max(correlativo)` de `voided_documents` con esa `fecha_comunicacion` + 1, 3 dígitos, sin lock (`:59-64`).
- `marcarEnProceso` → documentos a `anulacion_en_proceso` (`:177-190`).

Envío (`SendVoidedToSunat`, `tries = 10`, mismo backoff que el RC): XML en `{ruc}/0000/{fecha_comunicacion}/xml/{RA-…}.xml`; con ticket → `enviado` + `CheckVoidedTicketStatus` a los 15 s + webhook `voided.sent`; error → reintento o `rechazado` + `voided.rejected` (**sin revertir los documentos**).

Polling (`CheckVoidedTicketStatus`, `tries = 15`, mismo backoff que el RC):
- Endpoint: si el identificador empieza por `RR-` → `retention`; si no → `fe` (`:152-159`).
- `success`: baja `aceptado|rechazado`; CDR en `…/cdr/R-{identifier}.zip`; aceptada → los documentos pasan a `anulado` (siempre, salvo que ya lo estén); rechazada → solo los `anulacion_en_proceso` vuelven a `aceptado` (`:232-270`). Webhook `voided.status_updated`.
- Error: no numérico o 0/98/187 → release; otro código → `rechazado` y revertir a `aceptado`.
- Modelos que actualiza: 01 Invoice, 03 Boleta, 07 CreditNote, 08 DebitNote, 20 Retention, 40 Perception.

### 4.3 Reversión (RR)

Creación (`VoidedService::crearReversion`, `:204-248`): `validarReversion` (tipo 20/40, existe, está `aceptado`, sin otra RR vigente; **sin plazo de días**); correlativo = máximo de los identificadores `RR-{fecha}-%` + 1; documentos a `anulacion_en_proceso`.
Envío (`SendReversionToSunat`, `tries = 10`): `app(GreenterService::class, ['tenant'=>…])` + `ReversionBuilder`; XML en `{ruc}/reversions/{RR-…}.xml`; con ticket → documentos a **`anulado` de inmediato** (`:68-79`) + `CheckVoidedTicketStatus` + webhook `reversion.sent`. En `markAsRejected` revierte los `anulacion_en_proceso` a `aceptado` + webhook `reversion.rejected`.

### 4.4 GRE (09/31)

Envío (`SendDispatchGuideToSunat`, `tries = 10`, backoff de 10 pasos): el XML firmado se guarda siempre (`storeXml`), también si hay error; con `isSuccess()` → `ticket`, `enviado`, PDF, `CheckTicketStatus($tenantId, $ticket, DispatchGuide::class, $id)` a los 15 s, webhook `document.sent`. Si no: reintentables 0/100/109/500/1033 o `rechazado` con el código y mensaje de Greenter (puede ser 'API').
Polling (`CheckTicketStatus`, `tries = 15`): `getGreStatus` para guías y `getStatus` para el resto. Con `success` → `aceptado|rechazado`, code, description, `sent_at`, CDR con `storeCdr` (`…/guias/cdr/R-{serie}-{000000}.zip`), webhook `document.status_updated`. Error: no numérico o 0/98/187/401 → release mientras queden intentos; luego `rechazado` (aunque sea "en proceso").
Consulta manual: `GET /api/v1/guias-remision/{id}/estado` (`DispatchGuideController.php:221+`), que vuelve a consultar incluso los `rechazado` por "Token NO existe".

---

## 5. Jobs, eventos, listeners, mail, webhooks, colas y schedule

### 5.1 Jobs SUNAT

| Job | Cola | tries | backoff (s) | timeout | Middleware/locks |
|---|---|---|---|---|---|
| `SendDocumentToSunat(modelClass, id)` | `sunat` | 20 | 15,30,60,120,300,600,1200,1800 y 12 × 3600 (≈ 15 h) | 90 | `RateLimited('sunat-tenant')` (120/min por tenant, `config/facturacion.php:31-33`, `AppServiceProvider.php:207-213`) + `WithoutOverlapping("sunat-send:{class}:{id}")->expireAfter(180)` |
| `SendSummaryToSunat(id)` | default | 10 | 15,30,60,60,120,120,300,300,600,600 | — | ninguno |
| `CheckSummaryTicketStatus(id)` | default | 15 | 15,30,30,60,60,60,120,120,120,300,300,300,600,600,600 | — | ninguno |
| `SendVoidedToSunat(id)` | default | 10 | igual que el RC | — | — |
| `CheckVoidedTicketStatus(id)` | default | 15 | igual que el check | — | — |
| `SendReversionToSunat(id)` | default | 10 | igual | — | — |
| `SendRetentionToSunat(id)` / `SendPerceptionToSunat(id)` | default | 10 | igual | — | — |
| `SendDispatchGuideToSunat(id)` | default | 10 | igual | — | — |
| `CheckTicketStatus(tenantId, ticket, modelClass, modelId)` | default | 15 | igual que el check | — | — |
| `NotifyWebhookJob` | `webhooks` | 3 | 10, 30, 90 | HTTP 15 s | — |

Horizon (`config/horizon.php`): supervisor `sunat` (cola `sunat`, 5 procesos en producción, timeout 85, `tries=1` del supervisor pero manda el `$tries` del job) y supervisor `otros` (`webhooks, mail, sire-process, sire-heavy, sire-poll, default`, 10 procesos, timeout 60). `after_commit = false` en todas las conexiones (`config/queue.php`).

Reintentos: los errores transitorios se reintentan con `$this->release($delay)` según el array `backoff` y `attempts()`. Los permanentes (códigos de validación, certificado) no se reintentan. `RateLimited`, `WithoutOverlapping` y el circuit breaker (`release(300+jitter)`) también consumen intentos.

### 5.2 Webhooks (`app/Jobs/NotifyWebhookJob.php`)

- Dos modos: por documento `(modelClass, documentId, event)` o por tenant `(tenantId:, event:, payload:)` (`:38-47`).
- Cuerpo JSON (`JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES`):
  ```json
  {"event": "<evento>", "document": { ...payload... }, "timestamp": "<ISO-8601>"}
  ```
- Firma: `X-Signature: sha256=` + `hash_hmac('sha256', rawBody, tenant.api_secret)`; también `X-Webhook-Event: <evento>` y `Content-Type: application/json`. `POST tenant.webhook_url`, timeout 15 s (`:64-76`).
- Payload de documento (`:116-135`): `id, tipo_documento, serie, correlativo, numero_completo, fecha_emision (Y-m-d), total (mto_imp_venta), sunat_status, sunat_code, sunat_description, hash_cpe`.
- Guía (`:137-152`): `id, tipo_documento='09' (fijo), serie, correlativo, numero_completo (serie-correlativo SIN padding), fecha_emision, destinatario (razón social), ticket, sunat_status, sunat_code, sunat_description`.
- Resumen (`:154-169`): `id, tipo_documento='resumen', identifier, tipo, fecha_referencia, fecha_envio, total_documentos, ticket, sunat_status, sunat_code, sunat_description`.
- Baja/Reversión (`:171-185`): `id, tipo_documento='comunicacion_baja' (también para RR), identifier, fecha_generacion, fecha_comunicacion, total_documentos, ticket, sunat_status, sunat_code, sunat_description`.
- Retención/Percepción usan el payload de documento (`total` = `mto_imp_venta`, que no existe en esos modelos → null).
- Eventos emitidos: `document.sent`, `document.rejected`, `document.timeout_sunat`, `document.status_updated`, `summary.sent`, `summary.rejected`, `summary.status_updated`, `voided.sent`, `voided.rejected`, `voided.status_updated`, `reversion.sent`, `reversion.rejected`, `retention.sent`, `retention.rejected`, `perception.sent`, `perception.rejected` (+ SIRE `app/Sire/Jobs/ReconcilePeriodoJob.php:49`).
- Reintentos: `Http::post` no lanza excepción con 4xx/5xx → **solo se reintenta con errores de conexión** (no se llama a `->throw()`).

### 5.3 Eventos y listeners

- `DocumentCreated` (en las acciones), `DocumentSent(document, sunatResponse)` y `DocumentRejected(document, errorData)` (en el job). No hay listeners explícitos para Sent/Rejected.
- `AppServiceProvider::configureEventListeners` (`app/Providers/AppServiceProvider.php:118-130`) registra a mano `SubscriptionCreated→SendWelcomeEmail`, `PaymentFailed→SendPaymentFailedEmail`, `TrialExpiring→SendTrialEndingEmail` y dice que `IncrementDocumentUsage` está "DESCONECTADO". Pero Laravel 12 tiene **auto-discovery** activo (`Application::configure()->withEvents()` por defecto, `vendor/laravel/framework/src/Illuminate/Foundation/Application.php:241-245`): `app/Listeners/IncrementDocumentUsage` (type-hint `DocumentCreated`) se registra solo, y los listeners de suscripción quedan registrados dos veces (ver §9; conviene comprobarlo con `php artisan event:list`).
- Mail (`app/Mail/*`): solo cuentas y suscripciones (bienvenida, trial, pago fallido, invitación, credenciales…). **No hay envío de comprobantes por correo.**

### 5.4 Schedule (`routes/console.php`)

| Comando/job | Frecuencia | Relación con SUNAT |
|---|---|---|
| `sunat:reintentar-pendientes` | cada 15 min, withoutOverlapping, background | Vuelve a encolar facturas y boletas en `enviado` con `updated_at ≤ now−15min` y `created_at ≥ now−7d` (`app/Console/Commands/ReintentarComprobantesPendientes.php:43-76`). Solo Invoice y Boleta (no notas) |
| `summaries:poll-pending` | cada 5 min | §4.1 |
| `certificados:verificar --alertar` | diario 08:00 | Firma de prueba, aviso a 30 días |
| `ops:jobs-fallidos --alertar` | cada hora | Revisa failed_jobs |
| `ops:salud --alertar` | cada 10 min | BD, Redis, disco y circuit breaker |
| `partitions:create`, `logs:purge`, `backup:*`, `sire:*`, `ProcessRecurringPayments`, `CheckTrialExpiration`, `ResetMonthlyUsage` | varios | Fuera del núcleo SUNAT |

Comandos manuales de reparación: `bajas:reconsultar` (bajas `rechazado` con código 98 → `enviado` + volver a consultar), `bajas:reparar-anulaciones` (RA/RR aceptadas cuyos documentos no quedaron `anulado`), `comprobantes:reparar-boletas-resumen` (boletas `rechazado` que están en un RC de envío aceptado → `aceptado`, code '0'), `comprobantes:reparar-rechazos` (`aceptado` con código 1000-3999 → `rechazado`), `sunat:circuit status|open|close`, `pdf:regenerar`, `pdf-cache:clear`, `comprobantes:mover-a-privado`, `comprobantes:migrar-a-s3`, `empresas:auditar-credenciales`.

---

## 6. Almacenamiento (XML, ZIP, CDR, PDF) y hash

Disco: `App\Support\Storage\Comprobantes` escribe siempre en `config('comprobantes.disk')` (por defecto `comprobantes` = `storage/app/private`; admite s3/MinIO) y lee con fallback al disco `public` (legacy) (`app/Support/Storage/Comprobantes.php:22-88`). Se descargan solo por endpoints autenticados.

Rutas (`DocumentStorageService::buildPath`, `app/Services/Storage/DocumentStorageService.php:308-337`):
`{ruc}/{cod_local|0000}/{fecha_emision Y-m-d}/{carpeta}/{xml|cdr|pdf}/`, con `carpeta` = `facturas | boletas | notas_credito | notas_debito | guias | otros`.

| Artefacto | Nombre | Ejemplo |
|---|---|---|
| XML firmado (01/03/07/08/09/31) | `{numero_completo}.xml` | `20123456789/0000/2026-03-04/facturas/xml/F001-000001.xml` |
| CDR | `R-{numero_completo}.zip` (ZIP tal como lo devuelve SUNAT) | `…/facturas/cdr/R-F001-000001.zip` |
| PDF | `{numero_completo}.pdf` (formato `pdf.store_format` = a4) | `…/facturas/pdf/F001-000001.pdf` |
| RC | `{ruc}/0000/{fecha_referencia}/xml/RC-YYYYMMDD-NNN.xml` y `…/cdr/R-RC-….zip` | |
| RA | `{ruc}/0000/{fecha_comunicacion}/xml/RA-….xml` y `…/cdr/R-RA-….zip` | |
| RR | `{ruc}/reversions/RR-….xml` y CDR `{ruc}/reversions/R-RR-….zip` (por el `str_replace('/xml/','/cdr/')`, que aquí no cambia nada) | |
| Retención | `{ruc}/retentions/{serie}-{correlativo}.xml`, `{ruc}/retentions/R-{serie}-{correlativo}.zip` | |
| Percepción | `{ruc}/perceptions/{serie}-{correlativo}.xml`, `R-….zip` | |
| Caché de PDF por formato | `storage/app/pdf-cache/{tenant}/{tabla}_{id}_{formato}.pdf` (TTL 1 h) (`app/Http/Traits/CachesPdf.php:10-46`) | |
| Certificado | `certificates/{ruc}/cert.pem` (disco `local`) | |

- `numero_completo`: `serie-` + correlativo con 6 dígitos (`HasDocumentFields.php`, `DispatchGuide.php:88-91`); en la retención (y probablemente la percepción) son 8 dígitos (`Retention.php:44-47`). **El nombre del archivo NO sigue el formato SUNAT `{RUC}-{TIPO}-{SERIE}-{NUMERO}`**: ese nombre (`$document->getName()`) solo lo usa Greenter dentro del ZIP que envía.
- El ZIP que se envía a SUNAT **no se guarda**: Greenter lo arma en memoria.
- Columnas: `xml_path`, `cdr_path`, `pdf_path`, `hash_cpe` (DigestValue en base64 del XML firmado) en los documentos; `xml_path`/`cdr_path` en summaries y voided_documents. En las guías no se calcula `hash_cpe`.
- El XML de un rechazado también se guarda (`markAsRejected` con `$xml`).

---

## 7. PDF y QR

- Motor: `barryvdh/laravel-dompdf` (`Pdf::loadView(view, data)->setPaper(...)->setOption('isRemoteEnabled', true)`) (`app/Services/Pdf/PdfGeneratorService.php:17-31`). `config/dompdf.php`: CPDF, fuente serif, dpi 96, `enable_remote=false` (se fuerza `true` al generar), `enable_php=false`.
- Formatos (`app/Services/Pdf/PdfFormatConfig.php`): `a4`, `a5`, `ticket-80` (papel `[0,0,226.77,841.89]` pt ≈ 80 mm), `ticket-58` (`[0,0,164.41,841.89]` pt). Vista `pdf.formats.{a4|a5|ticket_80|ticket_58}`. `config/pdf.php`: `default_format=a4`, `auto_generate=true`, `store_format=a4`.
- Plantillas: `resources/views/pdf/formats/*.blade.php`, secciones `resources/views/pdf/sections/*.blade.php` (header, emitter, receiver, note_reference, dispatch_info, items_table/items_list, totals/totals_compact, leyenda, payment_info, bank_accounts, qr_code, footer, retention_*), estilos `resources/views/pdf/styles/{base,a4,ticket}.blade.php`.
- Secciones por tipo (`app/Services/Pdf/DocumentTypeConfig.php:100-179`): títulos "FACTURA ELECTRÓNICA", "BOLETA DE VENTA ELECTRÓNICA", "NOTA DE CRÉDITO ELECTRÓNICA", "NOTA DE DÉBITO ELECTRÓNICA", "GUÍA DE REMISIÓN ELECTRÓNICA", "GUÍA DE REMISIÓN TRANSPORTISTA ELECTRÓNICA", "COMPROBANTE DE RETENCIÓN ELECTRÓNICA", "COMPROBANTE DE PERCEPCIÓN ELECTRÓNICA", "COTIZACIÓN", "NOTA DE VENTA". Si la serie es solo numérica (contingencia) en 01/03, cambia "ELECTRÓNICA" por "DE CONTINGENCIA" (`DocumentDataMapper.php:84-90`).
- Datos (`app/Services/Pdf/DocumentDataMapper.php`): emisor (RUC, razón social, nombre comercial, dirección, ubigeo, cod_local), receptor, ítems (valor unitario, precio con IGV, IGV, importe con IGV), totales, `igv_pct` (tomado del primer ítem gravado con IGV > 0), `leyenda` ("SON: …" salvo en 09/31), leyendas SUNAT de texto (detracción, percepción, IVAP), hash, estado SUNAT, pagos, cuotas, detracción y percepción normalizadas, anticipos, guías, referencia de la nota, logo en base64, QR, símbolo de moneda (S/, $, €) y datos comerciales del tenant (teléfonos, emails, cuentas bancarias, billeteras, mensajes de agradecimiento y promoción).
- Pie: "Representación impresa del comprobante electrónico" + "Hash CPE: {hash}" (`resources/views/pdf/sections/footer.blade.php`). Totales: Op. Gravadas, Exoneradas, Inafectas, Gratuitas, IVAP, IGV (x%), ISC, ICBPER, Anticipo aplicado, Percepción, TOTAL (no hay línea de Op. Exportación).
- QR (`generateQrBase64`, `:478-527`): bacon-qr-code `ImageRenderer(RendererStyle(150), SvgImageBackEnd)` → `data:image/svg+xml;base64,…`, con caché estática por cadena.
  - Estándar (01/03/07/08 y guías): `RUC|TIPO|SERIE|CORRELATIVO|MTO_IGV|MTO_IMP_VENTA|FECHA(Y-m-d)|TIPO_DOC_RECEPTOR|NUM_DOC_RECEPTOR`. Los montos salen tal cual de la BD (decimal:2 → "18.00"); correlativo sin padding; **sin el hash final** (el formato SUNAT admite `|DigestValue|` opcional).
  - Guía: `TIPO = '09'` (el modelo no tiene `getTipoDocumento`, así que también sale 09 para la GRT), IGV y total vacíos/0, documento del destinatario.
  - Retención: `RUC|20|SERIE|CORRELATIVO|IMP_RETENIDO|IMP_PAGADO|FECHA|TIPO_DOC_PROV|NUM_DOC_PROV`.
  - Documentos internos (cotización/nota de venta): sin QR.
- Logo: `tenant.logo_path` desde el disco `public` como data URI. `BrandingService` (`app/Services/Branding/BrandingService.php`) solo da URLs del logo/favicon de la plataforma y del tenant para la web, con cache-buster `?v=mtime`; no participa en el PDF.
- Generación automática: después de aceptar (01/03/07/08), en el RC aceptado (boletas) y al recibir el ticket de la GRE. Los errores se ignoran (`try/catch (\Throwable) {}`).

---

## 8. Reglas de negocio de validación propias

Comunes (FormRequests en `app/Http/Requests/Api/V1/*`, traits en `app/Http/Requests/Concerns/*`):
- Serie registrada y activa para el tipo (`ValidatesSerieRegistrada`). Factura `^F[A-Z0-9]{3}$`, boleta `^B…` (regex), NC/ND `size:4`.
- Correlativo manual opcional: no puede existir ya en la serie (`ValidatesManualCorrelativo`); si no se envía, `Serie::resolveCorrelativo` con `lockForUpdate` dentro de la transacción (`CreateInvoiceAction.php:30-39`).
- Código de producto SUNAT obligatorio por ítem si `tenant.obligado_codigo_producto` (padrón, OBS-4331); tipo de operación 0112 exige un ítem 84121901 u 80131501 (ERR-3181) (`ValidatesCodProductoSunat`).
- Contrato de colaboración: tipo 1|2, número ≤ 50, descripción ≤ 250, porcentaje 0-99.99 (`ValidatesContratoColaboracion`).
- Catálogos con `App\Rules\SunatCatalog` (01, 06, 07, 08, 09, 10, 12, 22, 51, 52, 53, 54, 59) sobre `config/sunat_catalogs.php`.
- `fecha_emision` solo `required|date`: **no hay límite de antigüedad ni de fecha futura** en la app (SUNAT valida 2108 "presentación fuera de fecha"; la app lo interpreta como rechazo). La hora se normaliza a Lima (`ResolvesEmisionDateTime`, `app/Actions/Documents/Concerns/ResolvesEmisionDateTime.php:145-156`) para evitar el 2329.

Factura (`StoreInvoiceRequest.php:161-224`): un emisor RUS no puede emitir facturas; crédito exige cuotas; exportación (tipo de operación 02xx) no admite RUC (tipo 6) para el cliente; venta interna exige RUC (6) de 11 dígitos con prefijo 10/15/17/20 (no hay validación del dígito verificador módulo 11).
Boleta (`StoreBoletaRequest.php:163-200`): si Σ cantidad × precio > 700 y el tipo de documento es '0' → error (documento obligatorio); DNI de 8 dígitos y RUC de 11; crédito exige cuotas. El RUS usa tipo de operación 0113.
NC (`StoreCreditNoteRequest.php`): documento afectado en 01|03|12; motivo Cat. 09; RUS solo contra boletas; total de la NC ≤ total del documento (+1 si es boleta), salvo motivo 10 o moneda distinta (ERR-3286/3503, `ValidatesNotaCreditoMonto`).
ND (`StoreDebitNoteRequest.php:108-138`): motivo 13 (Penalidades) con IGV/IVAP 0 (ERR-3507); RUS solo contra boletas.
Resumen: fecha dentro de los últimos 7 días; anti-duplicado 2282; anulación de boletas solo `aceptado`, ≤ 7 días, sin NC y sin otro RC de anulación (§4.1).
Baja RA: no boletas; documento `aceptado`, ≤ 7 días desde la emisión, factura sin NC, sin duplicado (§4.2).
Reversión RR: 20/40 `aceptado`, sin duplicado, sin plazo.
GRE (`StoreDispatchGuideRequest.php`): motivo y modalidad obligatorios salvo en la 31; placas `^[A-Z0-9]{6,8}$`; TUC `^[A-Z0-9]{10,15}$`. GRT: remitente obligatorio y distinto del tenant, documento relacionado obligatorio (01/03/09/31/50/52; para 09/31 hace falta el RUC del emisor), vehículo y conductor obligatorios, subcontratación exige el pagador del flete, un tercero pagador exige documento y razón social. GRR: público (01) exige transportista distinto del tenant (salvo M1L); privado (02) exige vehículo y conductor; motivos 06/17/19 → destinatario con RUC (3417); motivos 02/04/07 → destinatario = emisor (2554); motivos 01/03/05/06/09/14/17 → destinatario ≠ emisor (2555); `fecha_entrega_transportista` requerida desde 2026-06-01 en ciertos casos (`:298-299`).
Retención/Percepción: `tasa`, `regimen` y documentos con pagos/cobros (`StoreRetentionRequest`, `StorePerceptionRequest`).
Plan/licencia: middleware `license` y `check.limit:sunat` en los POST de emisión (`routes/api.php`), más `guardEmisionAutorizada` en producción.

---

## 9. Observaciones / posibles bugs

Ordenados por impacto aproximado. "Confirmado" = se ve directamente en el código; "probable" = depende del entorno o del comportamiento de vendor.

1. **`ConsultCdrAction` usa un método que no corresponde** (probable, alto): manda `"{ruc}-{tipo}-{serie}-{numero}"` como *ticket* a `See::getStatus` contra el **billConsultService** (`app/Actions/Documents/ConsultCdrAction.php:15-17`). Ese servicio necesita `getStatusCdr(ruc, tipo, serie, numero)` (Greenter tiene `ConsultCdrService` para eso). Con esto, `POST /consultar-cdr` y `SendDocumentToSunat::recoverFromCdr` (recuperar timeouts) casi seguro nunca resuelven nada. Además usa la Clave SOL del tenant sin el RUC del emisor como parámetro. En la migración hay que implementar bien `getStatusCdr`.
2. **El circuit breaker casi nunca se abre** (confirmado): Greenter captura el `SoapFault` y devuelve un error 'HTTP'; `recordFailure` solo está en `catch (\SoapFault)` (`SendDocumentToSunat.php:122-126`) y `recordSuccess` se llama con cualquier resultado, incluidos los errores de transporte (`:121`). `recordProbeFailure` no se usa. Solo protege el endpoint `fe` y solo en ese job.
3. **Boletas de un RC de envío pueden reenviarse solas** (probable): `SendSummaryToSunat` pone las boletas en `enviado` (`:87-93`) y `sunat:reintentar-pendientes` vuelve a encolar las boletas `enviado` con más de 15 min (`ReintentarComprobantesPendientes.php:57-68`) sin mirar `ticket`. Si el RC tarda más de 15 min, `SendDocumentToSunat` manda cada boleta por separado (duplicado o estados cruzados).
4. **Anulación por RC: las boletas pasan a `anulado` antes del resultado** (`SendSummaryToSunat.php:87`) y, si el ticket termina con un error definitivo, `CheckSummaryTicketStatus.php:300-303` solo revierte las `anulacion_en_proceso` → quedan `anulado` aunque SUNAT rechazó. Igual en RR: `SendReversionToSunat.php:68-79` las marca `anulado` al recibir el ticket; `CheckVoidedTicketStatus` en un rechazo solo revierte `anulacion_en_proceso` → quedan anuladas por error.
5. **RA rechazada antes del ticket no revierte los documentos** (confirmado): `SendVoidedToSunat::markAsRejected` (`:96-107`) no devuelve `anulacion_en_proceso` → `aceptado` (la reversión sí lo hace). El RC con `MAX_RETRIES` o rechazado al enviar tampoco revierte las boletas.
6. **`VoidedController::checkStatus`** (`app/Http/Controllers/Api/V1/VoidedController.php:129-160`): consulta una RR por el endpoint `fe` (no por `retention`), trata el código **98** como rechazo definitivo (solo excluye 0 y 187; es el bug que ya se corrigió en el job y por el que existe `bajas:reconsultar`) y, si hay rechazo, no revierte los documentos.
7. **`fecha_comunicacion` de RA/RR en UTC**: `now()->format('Y-m-d')` con `app.timezone = UTC` (`config/app.php:68`; `VoidedService.php:46`, `:206`). Desde las 19:00 de Lima la fecha es la del día siguiente → identificador y `IssueDate` con fecha futura (posible rechazo). El RC sí usa Lima.
8. **El job convierte la falta de licencia en un rechazo permanente**: `guardEmisionAutorizada` lanza `RuntimeException` y `SendDocumentToSunat` la captura como `CERT_ERROR` → `rechazado` (`:131-134`). También cualquier `RuntimeException` del builder (p. ej. un dato que falta) aparece como "CERT_ERROR".
9. **Datos que no se persisten y cambian el XML**: `tip_sis_isc` (siempre '01'), `porcentaje_isc`, `mto_base_isc` (la cabecera usa `mto_oper_gravadas` como base de ISC → riesgo 3296 si no todas las líneas tienen ISC), `factor_icbper` (se deriva redondeado a 2), `leyendas[]` adicionales (no hay columna) — `SendDocumentToSunat.php:385-410`, `InvoiceBuilder.php:163-167`.
10. **RC incompleto** (`SendSummaryToSunat.php:40-52`): no envía `porcentaje_igv` (18 fijo aunque haya override 10.5% o sea RUS), ISC, ICBPER, IVAP, exportación, otros cargos ni percepción; solo tipo 03 (no las notas de boletas); nunca usa estado 2. Un resumen que se reenvía (`SummaryController::enviar`) reutiliza el mismo `fecha_envio`/correlativo.
11. **Descuento global `porcentaje` con escala incoherente**: la validación lo pide como factor 0..1 (`StoreInvoiceRequest.php:139`), pero `calculateTotals` y `InvoiceBuilder` lo tratan como 0-100 (`DocumentCalculationService.php:247-249`, `InvoiceBuilder.php:315-319`). Como el normalizado conserva `porcentaje`, el builder vuelve a calcular el monto con /100 y lo hace 100 veces menor que el guardado.
12. **NoteBuilder distinto de InvoiceBuilder**: no lleva exportación (`mto_oper_exportacion`), su `valorVenta` de gratuitas es 0 (en la factura es el valor referencial), no normaliza el descuento '01'→'00', redondea el precio efectivo a 2 decimales y no calcula `mto_igv_gratuitas` desde los ítems. Las NC de exportación/gratuitas pueden recibir observaciones o rechazos.
13. **`generateLeyenda`**: cualquier moneda distinta de PEN se escribe "DOLARES AMERICANOS" (EUR); "VEINTIUNO MIL/MILLONES" (lo correcto es VEINTIÚN); `round` puede dar 100 céntimos (p. ej. x.995 → "CON 100/100"); sin tildes; no hay leyenda 1002 automática para operaciones gratuitas.
14. **`InvoiceController::update`** (`app/Http/Controllers/Api/V1/InvoiceController.php:211-245`): recalcula sin tenant ni fecha (IGV 18 fijo aunque el tenant sea RUS o tenga override) y no copia `cod_producto_sunat` ni `descuentos` a los ítems nuevos.
15. **`enviar`/`reenviar` permite volver a enviar documentos `anulado` o `anulacion_en_proceso`**: solo bloquea `aceptado` (`InvoiceController.php:278-289`); pone `pendiente` → `enviado` y el job (que solo salta aceptado/anulado leyendo el estado nuevo) lo manda otra vez.
16. **Dispatch dentro de `DB::transaction` con `after_commit=false`**: el job puede ejecutarse antes del commit (`findOrFail` falla → el job reintenta o falla). Además el estado se pone en `enviado` después del dispatch.
17. **Doble registro de listeners por auto-discovery** (probable): `IncrementDocumentUsage` (doble conteo de uso) y los de correos de suscripción (dos correos). Comprobar con `event:list`.
18. **Webhooks sin reintento ante 4xx/5xx** (no hay `->throw()`); la guía siempre dice `tipo_documento '09'`; la RR dice `comunicacion_baja`; `total` es null en retención y percepción.
19. **`sunat:circuit --entorno=produccion`** busca `facturacion.sunat.produccion.fe` pero la clave es `production` → "no configurado" (`SunatCircuitCommand.php:24`).
20. **QR**: la GRT sale como tipo 09; las guías usan el formato de comprobante con montos vacíos (SUNAT da un QR propio para la GRE en el CDR); no lleva hash; la percepción (40) pasa por `mapStandardDocument` (no tiene ítems de venta, se rompe o queda vacío); falta "Op. Exportación" en los totales; el ICBPER por ítem del PDF lee `mto_icbper` (no existe, siempre 0).
21. **Retención/Percepción en moneda extranjera**: el cálculo por defecto `imp_total × tasa` no aplica el tipo de cambio (SUNAT exige el importe retenido o percibido en PEN).
22. **Correlativos RA/RR sin lock** (`VoidedService.php:60-64`, `:214-218`): posibles duplicados con concurrencia; `max('correlativo')` es de un string.
23. **`SummaryService::validarAnulacion` usa `whereJsonContains`** (`:161-165`), justo el problema MySQL vs PostgreSQL que `VoidedService` documenta y evita → en PostgreSQL el anti-duplicado de anulaciones de boletas puede no funcionar.
24. **Agotar reintentos en el polling**: `CheckTicketStatus` marca `rechazado` una GRE que sigue en 98 tras 15 intentos (falso rechazo); `CheckVoidedTicketStatus` y `CheckSummaryTicketStatus` mueren por MaxAttempts (el RC tiene rescate con `summaries:poll-pending`, la RA no).
25. **`sunat:reintentar-pendientes`** no cubre NC/ND, guías, retenciones, percepciones, RA ni RR.
26. **Los rate limiters, `WithoutOverlapping` y el circuit breaker consumen `attempts`** en `SendDocumentToSunat` (20 intentos): con mucho volumen, un documento puede agotar intentos sin llegar a SUNAT (queda `enviado`, lo rescata el comando cada 15 min).
27. **SunatCpeConsultaService** solo usa `tenant.client_id/secret` (no el fallback `sire_*` que sí usa la GRE): inconsistente con el panel.
28. **Detracción**: tipo de operación por defecto 1001 aunque el servicio sea transporte (1004 exige datos que el builder no modela); el monto no se convierte a PEN si la factura es en USD; la forma de pago a crédito no descuenta la detracción del monto pendiente.
29. **Inconsistencia documental sobre anticipos**: el comentario de `DocumentCalculationService.php:233-235` dice que no se agrega el AllowanceCharge 04, pero `InvoiceBuilder.php:337-355` sí lo agrega. Hay que respetar el comportamiento del builder (el validado contra SUNAT, según los comentarios 3277/3287/3291/3294/4296/4297).

Recomendación para la migración: reproducir **el comportamiento del builder** (es el que llega a SUNAT), incluidos los post-procesados XML de §1.5 y §1.11, la regla "código CDR 1000-3999 = rechazo aunque `isSuccess()`", y corregir los puntos 1-7 en las nuevas implementaciones, documentando cada diferencia.
