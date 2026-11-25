# Liquidación de compra electrónica (tipo 04)

Módulo nuevo: API-PRO no lo tiene y Greenter no modela el tipo 04, así que el XML lo genera esta
API. Sigue la misma arquitectura, autenticación, formato de respuesta, cola y estados que el resto.

**Estado:** probado en SUNAT beta (2026-10-24). La liquidación `L001-1` fue aceptada sin
observaciones ("El comprobante de liquidacion de compra numero L001-1, ha sido aceptada") y su
reversión `RR-20261024-002` también fue aceptada.

## 1. Fuentes

| Tema | Fuente |
|---|---|
| Normativa, requisitos, envío y CDR | [RS 244-2019/SUNAT](https://www.sunat.gob.pe/legislacion/superin/2019/244-2019.pdf): arts. 12.1 b), 13, 15.6, 29-D a 29-H de la RS 097-2012/SUNAT |
| Estructura UBL | [Anexos de la RS 244-2019](https://www.sunat.gob.pe/legislacion/superin/2019/anexo-244-2019.pdf): Anexo N.° 9-A literal e) (estándar UBL 2.1 de la liquidación), Anexo N.° 17 (resumen diario de reversiones), Anexo N.° 27 (requisitos mínimos), catálogos 05, 51, 53 y 60 |
| Orden de elementos | Esquema OASIS [`UBL-SelfBilledInvoice-2.1.xsd`](https://docs.oasis-open.org/ubl/os-UBL-2.1/xsd/maindoc/UBL-SelfBilledInvoice-2.1.xsd) |
| Validaciones y totales | Reglas de validación SUNAT del 24/04/2026, hoja `LiquidacionCompra2_0` y `Resumen_de_reversiones1_0` (carpeta oficial; síntesis en `analisis-fase0/08_documentacion_sunat.md` §6) |
| Plazo de reversión | [RS 000047-2026/SUNAT](https://www.sunat.gob.pe/legislacion/superin/2026/000047-2026.pdf) (vigente desde el 01/07/2026): modifica el art. 26 de la RS 188-2010/SUNAT y el art. 29-F de la RS 097-2012/SUNAT |
| Retención del Impuesto a la Renta | [RS 234-2005/SUNAT](https://www.sunat.gob.pe/legislacion/superin/2005/234.htm): art. 1 d) (base = importe sin tributos), art. 2 (exclusión de productos primarios agropecuarios), art. 5 (1.5 %), art. 6 (sin retención si el importe es igual o inferior a S/ 700) |
| UIT 2026 | S/ 5 500 (DS 301-2025-EF) |

Lo que no sale de esas fuentes y viene del pedido del proyecto: la tasa de 4 % para la compra de oro
y la vigencia de la exclusión agropecuaria hasta el 31/12/2026. Ambas están en configuración.

## 2. Qué es

La emite el **comprador** (empresa con RUC, afecta a la renta de 3.ª categoría) cuando compra a una
**persona natural sin RUC** que no puede entregar comprobante: productos primarios, pesca artesanal,
madera, minería aurífera artesanal, desechos metálicos, etc. El comprador queda como agente de
retención: **retiene el IGV** (lo paga él a SUNAT) y **la renta**. Al vendedor le paga el neto.

## 3. Endpoints

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/v1/liquidaciones-compra` | Emite y encola el envío (`enviar_automatico=false` la deja pendiente) |
| GET | `/api/v1/liquidaciones-compra` | Lista: `sunat_status`, `fecha_desde`, `fecha_hasta`, `serie`, `vendedor_num_doc`, `por_pagina` (≤ 100), `page` |
| GET | `/api/v1/liquidaciones-compra/{id}` | Detalle con estado SUNAT, fecha del CDR y disponibilidad de reversión |
| GET | `/api/v1/liquidaciones-compra/{id}/xml` · `/cdr` | Descargas (el PDF llega en la Fase 6) |
| POST | `/api/v1/liquidaciones-compra/{id}/enviar` · `/reenviar` | Envío manual o reenvío de una rechazada |
| POST/GET | `/api/v1/liquidaciones-compra/{id}/pagos` | Registrar/listar pagos al vendedor (bloquean la reversión) |
| DELETE | `/api/v1/liquidaciones-compra/{id}/pagos/{pagoId}` | Eliminar un pago |
| POST | `/api/v1/liquidaciones-compra/{id}/reversion` | Revertir: genera el Resumen diario de reversiones (RR) |
| GET | `/api/v1/liquidaciones-compra/reporte-retenciones?periodo=AAAA-MM` | IGV y renta retenidos del periodo |

La serie se registra en `/api/v1/series` con `tipo = liquidacion_compra` (prefijo `L`).

### Ejemplo

```json
POST /api/v1/liquidaciones-compra
{
  "serie": "L001",
  "fecha_emision": "2026-10-24",
  "tipo_operacion": "0501",
  "productos_agropecuarios": false,
  "vendedor": { "tipo_doc": "1", "num_doc": "45678912", "nombre": "QUISPE MAMANI JUAN",
                "direccion": "CASERIO SAN JUAN S/N", "ubigeo": "080101", "tipo_domicilio": "02" },
  "lugar":    { "direccion": "CHACRA LOS ANDES KM 5", "ubigeo": "080101", "tipo": "02" },
  "items": [ { "codigo": "CAFE01", "cod_producto_sunat": "50131700", "descripcion": "Café pergamino",
               "unidad": "KGM", "cantidad": 100, "valor_unitario": 10, "tip_afe_igv": "10" } ],
  "placa_vehiculo": "ABC123"
}
```

Totales calculados: valor de venta 1 000.00, IGV 180.00, subtotal 1 180.00, retención de renta 1.5 % =
15.00, **neto a pagar al vendedor 985.00**.

Campos: `tipo_operacion` 0501 compra interna o 0503 compra de oro (cada ítem exige `oro.codigo_concesion`,
`oro.ley_mineral`, `oro.naturaleza_mineral` y `oro.nombre_derecho_minero`, catálogo 55: 6000/6004/6005/6006);
`tipo_domicilio` y `lugar.tipo` del catálogo 60 (01 punto de venta, 02 producción, 03 extracción,
04 explotación, 05 otros); `tip_afe_igv` 10 gravado, 20 exonerado, 30 inafecto; `guias` opcional
(`[{tipo: 09|31, numero}]`); `placa_vehiculo` cuando la liquidación sustenta el traslado (art. 29-D).

## 4. XML (`SelfBilledInvoice`)

- UBL 2.1, CustomizationID 2.0, `InvoiceTypeCode` 04 con el tipo de operación en `@listID`.
- **Roles invertidos**: `cac:AccountingCustomerParty` = emisor/comprador (RUC, `schemeID` 6) y va
  **antes** que `cac:AccountingSupplierParty` = vendedor (DNI o carné, `RegistrationAddress` con
  `AddressTypeCode` del catálogo 60), como manda el esquema OASIS.
- Lugar de la operación: `cac:DeliveryTerms/cac:DeliveryLocation` con `LocationTypeCode` y dirección.
- Por ítem, en `cac:TaxTotal`, dos subtotales: IGV (1000 / 9997 / 9998 con `TaxExemptionReasonCode`)
  y **retención de renta, tributo 3000 (`IR`, `TOX`)** con su porcentaje y sin código de afectación.
- El `TaxTotal/TaxAmount` (por ítem y global) **no incluye** el 3000 (reglas 4293 y 4301).
- Placa: `cac:Delivery/cac:Shipment` (motivo 02) `/cac:ShipmentStage/cac:TransportMeans/cac:RoadTransport/cbc:LicensePlateID`.
- Envío: `sendBill` al **servicio de comprobantes** (el mismo de la factura, art. 10 num. 10.5 b),
  CDR síncrono. Archivo `RUC-04-L001-1.zip`. No pasa por OSE.

## 5. Cálculo

```
ítem:    valor de venta = cantidad × valor unitario
         IGV            = valor de venta × 18 %               (solo gravado)
         renta          = valor de venta × tasa de retención
         precio         = (valor de venta + IGV) / cantidad
totales: IGV       = Σ bases gravadas × 18 %
         retención = Σ retención de los ítems
         subtotal  = valor de venta + IGV                     (TaxInclusiveAmount)
         neto      = subtotal − IGV − retención               (PayableAmount)
```

Tasa de retención (`app.lc.*` / `LC_*` en `.env`):

| Caso | Tasa |
|---|---|
| Productos primarios agropecuarios (`productos_agropecuarios: true`) con emisión hasta `LC_AGRO_EXENTO_HASTA` (2026-12-31) | 0 % |
| Importe de la operación sin tributos ≤ `LC_RETENCION_RENTA_MINIMO` (S/ 700) | 0 % |
| Compra de oro (0503) | `LC_RETENCION_RENTA_TASA_ORO` (4 %) |
| Resto | `LC_RETENCION_RENTA_TASA` (1.5 %) |

## 6. Validaciones propias (antes de enviar)

| Regla | Código SUNAT |
|---|---|
| Serie `L` + 3 alfanuméricos registrada para el tipo 04; correlativo hasta 8 dígitos y no repetido | 1001, 1033 |
| Emisor afecto a la renta de 3.ª categoría (el Nuevo RUS no emite) | 2463 |
| Vendedor con DNI (8 dígitos) o carné de extranjería (alfanumérico ≤ 15) | 2800, 2802 |
| Domicilio del vendedor y lugar de la operación con dirección, ubigeo y tipo (cat. 60) | 2452–2459 |
| Código de producto SUNAT obligatorio en cada ítem | 3506 |
| Fecha de emisión: hoy o ayer (Lima), no futura | 2108, 2329 |
| Compras del año al vendedor ≤ 75 UIT (se suman las liquidaciones de esta empresa; SUNAT valida el total global) | 2451 |
| Compra de oro con las cuatro propiedades del ítem | 2466–2469 |

## 7. Reversión

Procede si (art. 29-F y RS 000047-2026/SUNAT):

- la liquidación tiene **CDR aceptado** (2398) y no tiene otra reversión (2751);
- la causal es error en el nombre del vendedor, en su documento o en producto/cantidad/unidad
  (`causal`: `vendedor_nombre`, `vendedor_documento`, `producto`; `motivo` de 3 a 100 caracteres);
- **no hay pagos registrados** (2471);
- es hasta el **5.º día calendario siguiente a la emisión** y el resumen se envía dentro de los
  **5 días siguientes al CDR aceptado** (2958). Las dos condiciones se validan.

Se genera un Resumen diario de reversiones `RR-AAAAMMDD-NNN` (`VoidedDocuments`, línea con tipo 04,
serie `L…`, `ReferenceDate` = fecha de emisión) que va por `sendSummary` al servicio "otros CPE"
(anexo N.° 22) y se consulta con `getStatus` en ese mismo servicio. La liquidación queda
`reversion_en_proceso` y pasa a `revertido` cuando SUNAT acepta el ticket (si lo rechaza, vuelve a
`aceptado`). El estado y el XML/CDR del RR se consultan en `/api/v1/anulaciones/{voided_id}/...`.
El número de la liquidación revertida no se reutiliza.

## 8. Reporte de retenciones

`GET /api/v1/liquidaciones-compra/reporte-retenciones?periodo=2026-10` lista, por fecha de emisión,
cada liquidación aceptada o en reversión con base imponible, **IGV retenido**, base y **renta
retenida** y neto pagado, más los totales del periodo. Desde el periodo julio de 2026 el IGV
retenido se paga por SUNAT Virtual vinculado a cada liquidación y se declara en el Formulario
Virtual 617 (no en bancos con NPS); la API solo informa, no paga.

## 9. Pendiente

- Anticipos (tipo de operación 0502: `PrepaidPayment`, documento 10, descuento 61): responde 422.
- Operaciones gratuitas (9996), otros tributos (9999) y moneda distinta de PEN.
- Pasaporte (tipo 7): las reglas de SUNAT lo admiten, pero el pedido del proyecto limita al DNI y
  al carné de extranjería; se habilita agregando `7` a `vendedor.tipo_doc`.
- PDF/representación impresa con QR y hash: Fase 6.
