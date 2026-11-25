# 08 — Síntesis técnica de la documentación oficial SUNAT (Fase 0)

> Destinatarios: quienes implementan las APIs de facturación electrónica (7 tecnologías) que deben comportarse igual que la API Laravel+Greenter, más el módulo **nuevo** de Liquidación de Compra electrónica (tipo 04, raíz `SelfBilledInvoice`, serie `L###`).
> Regla editorial: cada afirmación lleva su fuente. Lo que no está en la documentación disponible aparece marcado como **[NO CUBIERTO]**. Si añado algo desde el estándar UBL/OASIS o como deducción mía, lo marco como **[INFERENCIA]** o **[ESTÁNDAR UBL — verificar]**.

## 0. Fuentes, convención de citas y verificación de la carpeta

### 0.1 Convención de citas

| Abreviatura | Documento original | Cómo se cita |
|---|---|---|
| **RV/‹Hoja› f.N** | `03 Reglas de Validación/Reglas de validación actualizado al 24.04.2026 (1).xlsx` | hoja + **fila Excel N** (número real de fila, re-exportado con `R=`) |
| **AX/‹Hoja› f.N** | `02 Catálogos/AnexosIyII_Formato1.3.4.xlsx` | hoja + fila Excel |
| **MP p.N** | `04 UBL 2.1 y XSD/manual_programador (1).pdf` (Manual del Programador, SEE-Del contribuyente, RS 097-2012, mayo 2021) | página (marcador `~N~` del PDF) |
| **GF p.N / GB / GNC / GND** | guías XML UBL 2.1 de Factura, Boleta, NC y ND (v1.0, mayo 2017) | página |
| **GRB p.N** | `GUIA_Resumen_de_Boletas_11-01-2018…pdf` (v2, enero 2018) | página |
| **GRC** | `guia_resumen_de_contingencia_0.pdf` (dic. 2016) | página |
| **CI p.N** | `Manual-de-Consulta-Integrada-de-Comprobante-de-Pago-por-ServicioWEB_v2_0.pdf` | página |
| **A7** | `02 Catálogos/anexoVII-117-2017.pdf` (Anexo N° 8, catálogos, RS 117-2017) | — |

Las filas RV se re-exportaron conservando el número real de fila de Excel (script `scratchpad/scripts/xlsx2tsv_r.php`, salida en `scratchpad/xlsx_r/` y su versión compacta en `scratchpad/xc/`). Los TSV originales de `xlsx_txt/` no conservan esa numeración.

### 0.2 Verificación de la carpeta de originales

`C:\Users\dev\Documents\CURSO DE FACTURACIÓ ELECTRÓNICA 2026\05 Documentación Oficial SUNAT`:

| Subcarpeta | Contenido real |
|---|---|
| `01 Resoluciones` | **vacía** (solo `desktop.ini`): no hay ninguna RS en PDF |
| `02 Catálogos` | `AnexosIyII_Formato1.3.4.xlsx`, `anexoVII-117-2017.pdf` |
| `03 Reglas de Validación` | `Reglas de validación actualizado al 24.04.2026 (1).xlsx` |
| `04 UBL 2.1 y XSD` | **solo PDFs** (8 guías y manuales) + `desktop.ini`. **No contiene ningún `.xsd`**, aunque el nombre de la carpeta lo sugiere. |
| `05 Guías Portal SOL` | **vacía** (solo `desktop.ini`) |

Consecuencia directa: **no podemos validar contra los XSD UBL 2.1**. El MP p.11 (§1.4.a) exige que "la estructura de cada documento deberá construirse de acuerdo a los esquemas (.xsd)", y la hoja General marca como ERROR 0306 un XML que no se puede parsear, "incluye validación de XSD" (RV/General f.16). Por eso el **orden de los elementos** depende de los XSD OASIS UBL 2.1 (Invoice, CreditNote, DebitNote, SelfBilledInvoice) y de los XSD SUNAT 2.0 (SummaryDocuments, VoidedDocuments), que hay que conseguir aparte (ver §8).

### 0.3 Advertencia sobre la vigencia de las guías PDF

Las guías XML de Factura/Boleta/NC/ND son la **v1.0 de mayo 2017** (GF p.1). Sus ejemplos usan estructuras que las Reglas de Validación vigentes (24.04.2026) ya no piden. Por ejemplo, GF p.19-20 muestra:
- `cbc:ProfileID` con el catálogo 17 para el tipo de operación;
- `cac:PartyTaxScheme/cbc:CompanyID` para el emisor y el receptor.

Las RV actuales exigen en cambio:
- el tipo de operación en `cbc:InvoiceTypeCode/@listID` con el catálogo 51 (RV/Factura2_0 f.435; RV/LiquidacionCompra2_0 f.14);
- el emisor en `cac:Party/cac:PartyIdentification/cbc:ID` y `cac:PartyLegalEntity/cbc:RegistrationName` (RV/Factura2_0 f.35-49).

**Las Reglas de Validación prevalecen.** Las guías sirven para la semántica y la firma, no para los XPath.

---

## 1. Reglas transversales (hoja General, Firma, Parámetros, Listados)

### 1.1 Nombre del archivo ZIP/XML (RV/General f.6, f.7, f.13; MP p.6-10)

- **Comprobantes**: `RRRRRRRRRRR-TT-SSSS-NNNNNNNN.zip` (o `.ZIP`). El XML interno lleva el mismo nombre con `.xml`/`.XML` (ERROR 0151 nombre ZIP; 0159 nombre XML; 0161 si no coinciden).
  - TT válidos: 01, 03, 04, 07, 08, 20, 25, 28, 30, 34, 40, 42, 56 (RV/General f.6).
  - Serie: tipos 01/03/07/08/30/34/42 → 4 dígitos numéricos o alfanumérica que empieza con F o B. **Tipo 04 → alfanumérica que empieza con `L`**. Tipo 20 → R o numérica; 40 → P o numérica; 25 → F o numérica; 28 → alfanumérica de 4; 56 → empieza con C.
  - Correlativo NNNNNNNN de 1 a 8 dígitos (hasta 9 para 28 y 56).
  - El RUC debe empezar con 1 o 2.
- **Resúmenes**: `RRRRRRRRRRR-TT-AAAAMMDD-NNNNN.zip`, con TT = **RA** (baja), **RC** (resumen diario) o **RR** (reversión). Correlativo de 1 a 99999 (RV/General f.7).
- El MP muestra ejemplos como `20100066603-RC-20110522-1.ZIP` y `20100066603-RR-20150522-1.ZIP` (MP p.8-10). En p.7 aparece un ejemplo sin correlativo (`…-RA-20110522.ZIP`), que es una errata del manual: la regla RV/General f.7 exige el correlativo.
- **CDR**: el ZIP de respuesta contiene `R-<nombre enviado sin extensión>.xml` (MP p.23).

### 1.2 Contenido del ZIP (MP p.10, §1.3)

Un único XML y una carpeta vacía `dummy`, para:
- comprobante individual (factura, notas, retención, percepción, guía);
- resumen diario y comunicación de baja.

Más de un archivo → ERROR 0158; ZIP vacío → 0155; corrupto → 0156; sin comprobantes → 0157 (RV/General f.9-12). El lote (`LT`) admite hasta 500 XML (MP p.10).

### 1.3 Codificación (MP p.11, §1.4.c)

Si se usan tildes o ñ, el manual pide ISO-8859-1 y declararlo: `<?xml version="1.0" encoding="ISO-8859-1" standalone="no" ?>`. La firma debe generarse con la misma codificación que el XML (MP p.27, §3.2.f).

### 1.4 Validaciones generales que generan excepción o rechazo (RV/General)

| Código | Condición | Fila |
|---|---|---|
| 0100 | problema con el servicio de recepción | f.3 |
| 0109 | servicio de autenticación no disponible | f.4 |
| 0111 | el usuario no es emisor ni PSE | f.5 |
| 0154 | el RUC del archivo no corresponde al usuario o no hay relación PSE vigente (vigente hasta el 7.º día del mes siguiente a la revocación) | f.8 |
| 0306 | no se puede parsear el XML (incluye XSD) | f.16 |
| 2325 | certificado no comunicado a SUNAT | f.17 |
| 2326 | certificado de baja | f.18 |
| 1078 | emisor no autorizado al SEE-Del Contribuyente | f.19 |
| 2335 | firma no coincide (documento alterado) | f.20 |

### 1.5 Parámetros y listados

- **Parámetros** (RV/Parámetros f.4-27): 001 tipo de cambio; 002 régimen de percepción; 003 régimen de retención; **004 plazo máximo de envío por tipo de comprobante (número de días)**; 012 tasa IGV por fecha; 024 tasa IVAP.
  - **[NO CUBIERTO]** El libro trae solo la definición de los parámetros, **no sus valores** (cuántos días por tipo, tasas por fecha).
- **Listados** (RV/Listados): contribuyentes (`ind_estado`, `ind_condicion`) f.5-7; padrones (`ind_padron` 01…14) f.14; certificados del emisor f.30-34; comprobantes electrónicos con `ind_estado_cpe` 0=Rechazado, 1=Aceptado, 2=Anulado (f.44); **padrones con vigencia** (01 = Autorizado IGV 10 %; **02 = Sujetos sin capacidad operativa, SSCO**) f.97.

### 1.6 Tipos de error y su tratamiento (MP p.28-29, §4.1)

| Rango | Tipo | Efecto |
|---|---|---|
| 0100–0999 | Excepciones propias de SUNAT | el servicio devuelve un **SOAP Fault** con código y descripción; el documento se considera **no informado** |
| 1000–1999 | Excepciones de formato/estructura del contribuyente | igual que la fila anterior (excepción) |
| 2000–3999 | Errores que generan **rechazo** | CDR con estado rechazado (ver detalle abajo) |
| 4000 en adelante | **Observaciones** | CDR "aceptada con advertencias"; el comprobante es válido |

Detalle del rechazo según el tipo de documento (MP p.28):
- **Factura y sus notas**: el número queda consumido; hay que emitir con número nuevo.
- **Retención, percepción y guía**: no se consume el número; se reenvía el documento completo, incluso con el mismo nombre de archivo.
- **RC, RA y RR**: se rechaza todo el archivo, no hay procesamiento parcial y se puede reenviar con el mismo nombre.

> En las hojas RV, la columna "TIPO DE RETORNO" dice explícitamente **ERROR** u **OBSERV**. Atención: hay códigos 2xxx/3xxx marcados OBSERV (p. ej. OBS-2266 en RV/Resumen_Diario1_1 f.115; OBS-2988 f.55; OBS-3219 en LC f.370) y algún 4xxx ligado a rechazo. **La columna de la hoja manda sobre el rango**: hay que implementar la severidad fila por fila, no por rango.

---

## 2. Servicios web (MP §2 y Manual de Consulta Integrada)

### 2.1 Endpoints SOAP

| Entorno | Servicio | URL | Fuente |
|---|---|---|---|
| Producción | Factura, notas, servicios públicos, resumen diario, baja, lotes | `https://e-factura.sunat.gob.pe/ol-ti-itcpfegem/billService?wsdl` | MP p.12 |
| Producción | Retención, percepción y resumen de reversión | `https://e-factura.sunat.gob.pe/ol-ti-itemision-otroscpe-gem/billService?wsdl` | MP p.12 (la tabla del PDF se desalineó al extraerla) |
| Producción | Guía de remisión remitente | `https://e-guiaremision.sunat.gob.pe/ol-ti-itemision-guia-gem/billService?wsdl` | MP p.12 |
| Beta | Factura | `https://e-beta.sunat.gob.pe/ol-ti-itcpfegem-beta/billService?wsdl` | MP p.12 |
| Beta | Guía | `https://e-beta.sunat.gob.pe/ol-ti-itemision-guia-gem-beta/billService?wsdl` | MP p.12 |
| Beta | Retenciones | `https://e-beta.sunat.gob.pe/ol-ti-itemision-otroscpe-gem-beta/billService?wsdl` | MP p.12 |
| Homologación | — | `https://www.sunat.gob.pe/ol-ti-itcpgem-sqa/billService` | MP p.13 |
| Producción | Consulta de validez | `https://e-factura.sunat.gob.pe/ol-it-wsconsvalidcpe/billValidService?wsdl` | MP p.25 |
| Producción | Consulta de CDR y estado | `https://e-factura.sunat.gob.pe/ol-it-wsconscpegem/billConsultService?wsdl` | MP p.25 |

**[NO CUBIERTO]** El MP (mayo 2021) **no menciona la Liquidación de Compra** en ningún endpoint. Ver §6.10.

### 2.2 Seguridad (MP p.13-14, §2.2)

- WS-Security con el perfil **UsernameToken**. `wsse:Username` = RUC concatenado con el usuario SOL (p. ej. `20100066603MODDATOS`); `wsse:Password` = clave SOL.
- Namespace: `http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd`.
- La clave SOL debe ser **secundaria** y tener el perfil "Envío de documentos electrónicos-Grandes emisores". El transporte va sobre SSL/HTTPS.
- **Beta**: usuario `[RUC]MODDATOS`, password `MODDATOS`. No requiere certificado registrado en SUNAT y solo valida estructura (MP p.14, §2.3).

### 2.3 Operaciones (MP p.15-22, §2.5)

Namespace de las operaciones: `xmlns:ser="http://service.sunat.gob.pe"`.

| Operación | Entrada | Salida | Uso |
|---|---|---|---|
| `sendBill` | `fileName` (String), `contentFile` (byte[] del ZIP) | `applicationResponse`: ZIP con el CDR | **síncrono**; un comprobante (factura y notas) — MP p.14-16 |
| `sendSummary` | `fileName`, `contentFile` | `ticket` (String) | **asíncrono**: resumen diario (RC), baja (RA), reversión (RR) — MP p.15-17 |
| `sendPack` | `fileName` (`…-LT-AAAAMMDD-N.zip`), `contentFile` | `ticket` | lote de hasta 500 XML — MP p.18-19 |
| `getStatus` | `ticket` | `status{statusCode, content}` | `statusCode`: **0** = procesó correctamente, **98** = en proceso, **99** = proceso con errores; `content` (ZIP en Base64 con el CDR) solo viene con 0 o 99 — MP p.20-21 |
| `getStatusCdr` (billConsultService) | `rucComprobante`, `tipoComprobante`, `serieComprobante`, `numeroComprobante` | `statusCdr{statusCode, content, statusMessage}` | recupera el CDR de un comprobante; el manual solo lista los tipos 01, 07 y 08 — MP p.21-22, p.24 |

- Todos los parámetros son obligatorios; si falta alguno, el servicio lanza una excepción (MP p.19-21).
- Ticket inexistente → ERROR **0127** (RV/Resumen_Diario1_1 f.4; RV/Comunicación_de_Baja1_0 f.3; RV/Resumen_de_reversiones1_0 f.3); ticket de otro usuario → 0126 (RV/CódigosRetorno).

### 2.4 CDR (MP p.23-24, §2.6-2.7)

- Formato XML `ApplicationResponse` (el MP menciona UBL 2.0; el detalle está en el Anexo 1, MP p.34 y siguientes). Lo firma SUNAT.
- Hay CDR de factura/nota, de resumen diario, de baja y de reversión, todos con la misma estructura.
- Recuperación: `getStatusCdr` para facturas y notas; `getStatus` con el ticket para RC, RA, RR y LT.

### 2.5 Consulta Integrada (REST + OAuth2) — CI p.1-12

1. **Credenciales**: se crean en SOL → "Empresas / Comprobantes de pago / Consulta de Validez… / Credenciales de API SUNAT". Se registra la aplicación (nombre y URL) y se obtienen `client_id` (Id) y `client_secret` (Clave) (CI p.2-4).
2. **Token**: `POST https://api-seguridad.sunat.gob.pe/v1/clientesextranet/{client_id}/oauth2/token/`, body `x-www-form-urlencoded`:
   - `grant_type=client_credentials`
   - `scope=https://api.sunat.gob.pe/v1/contribuyente/contribuyentes` (grant_type y scope son fijos)
   - `client_id`, `client_secret`

   Respuesta: `access_token`, `token_type`, `expires_in` (en segundos) (CI p.5).
3. **Consulta**: `POST https://api.sunat.gob.pe/v1/contribuyente/contribuyentes/{RUC}/validarcomprobante` (RUC = quien consulta).
   - Header: `Authorization: Bearer <token>`.
   - Body JSON:
     - `numRuc` (String 11): RUC del emisor
     - `codComp` (an2)
     - `numeroSerie` (an4)
     - `numero` (Integer, hasta 8)
     - `fechaEmision` (dd/mm/yyyy)
     - `monto` (n(8,2), "solo para comprobante electrónico")
   - Respuesta: `success`, `message`, `data.estadoCp`, `data.estadoRuc`, `data.condDomiRuc`, `data.observaciones[]`, `errorCode` (CI p.7).
4. **Códigos de respuesta** (CI p.10-11):
   - `estadoCp`: 0 NO EXISTE, 1 ACEPTADO, 2 ANULADO, 3 AUTORIZADO, 4 NO AUTORIZADO.
   - `estadoRuc`: 00 ACTIVO, 01 BAJA PROVISIONAL, 02 BAJA PROV. POR OFICIO, 03 SUSPENSIÓN TEMPORAL, 10 BAJA DEFINITIVA, 11 BAJA DE OFICIO, 22 INHABILITADO-VENT.ÚNICA.
   - `condDomiRuc`: 00 HABIDO, 09 PENDIENTE, 11 POR VERIFICAR, 12 NO HABIDO, 20 NO HALLADO.
   - `codComp`: 01, 03, **04 LIQUIDACIÓN DE COMPRA**, 07, 08, R1, R7. **La consulta integrada sí admite el tipo 04.**

---

## 3. Firma digital

### 3.1 Requisitos del certificado (MP p.26, §3.1)

- X.509 v3, clave privada de al menos **1024 bits**.
- Debe identificar al titular (nombre, DNI) y llevar el **RUC en el campo OU** del Subject.
- Debe estar comunicado a SUNAT en SOL ("Actualización de certificado digital"), vigente y no revocado.
- Si no fue comunicado → ERROR 2325; si está de baja → ERROR 2326 (RV/General f.17-18).

### 3.2 Proceso de firma (MP p.26-27, §3.2; GF p.72-75)

- Se usa XMLDSig. La firma va **dentro de un único** `ext:UBLExtensions/ext:UBLExtension/ext:ExtensionContent`.
- Se firma **todo el documento** (raíz Invoice, CreditNote, DebitNote, SummaryDocuments, VoidedDocuments…), con `ds:Reference URI=""`.
- Antes de firmar, el XML debe estar completo, incluido `cac:Signature`. Después de firmar no se puede añadir nada.
- La firma usa la clave privada del X509, debe incluir `ds:KeyInfo/ds:X509Data/ds:X509Certificate` (GF p.75) y se genera con la misma codificación que el XML.
- **Algoritmos**: ni el MP ni la hoja Firma fijan un algoritmo obligatorio. Los ejemplos oficiales usan:
  - `CanonicalizationMethod` = `http://www.w3.org/TR/2001/REC-xml-c14n-20010315` (con o sin `#WithComments`);
  - `Transform` = `http://www.w3.org/2000/09/xmldsig#enveloped-signature`;
  - `DigestMethod` = `http://www.w3.org/2000/09/xmldsig#sha1`;
  - `SignatureMethod` = `xmldsig#rsa-sha1` (GRB, ejemplo; guía de boleta) o `xmldsig#dsa-sha1` (GF p.19, ejemplo).
  - **[INFERENCIA]** Para certificados RSA, lo coherente con los ejemplos es `rsa-sha1` (lo que usa Greenter). El uso de SHA-256 no está documentado aquí.

### 3.3 Hoja Firma: validaciones exactas (RV/Firma f.3-28)

Todas son de tipo **ERROR**.

| XPath | Validación | Códigos |
|---|---|---|
| `…/ds:Signature/@Id` | obligatorio; an..3000 | 2085, 2084 |
| `…/ds:SignedInfo/ds:CanonicalizationMethod/@Algorithm` | obligatorio | 2087, 2086 |
| `…/ds:SignedInfo/ds:SignatureMethod/@Algorithm` | obligatorio | 2089, 2088 |
| `…/ds:Reference/@URI` | debe existir; el texto del error dice "Debe estar vacío para id" | 2091, 2090 |
| `…/ds:Reference/ds:Transform/@Algorithm` | obligatorio | 2093, 2092 |
| `…/ds:Reference/ds:DigestMethod/@Algorithm` | obligatorio | 2095, 2094 |
| `…/ds:Reference/ds:DigestValue` | obligatorio | 2097 |
| `…/ds:SignatureValue` | Base64 (A-Z, a-z, 0-9, +, =), mínimo 2 caracteres | 2099, 2098 |
| `…/ds:KeyInfo/ds:X509Data/ds:X509Certificate` | Base64 | 2101, 2100 |
| `/‹raíz›/cac:Signature/cbc:ID` | obligatorio | 2076, 2077 |
| `cac:Signature/cac:SignatoryParty/cac:PartyIdentification/cbc:ID` | **igual al RUC del emisor** o al RUC que envía | 2079, 2078 |
| `cac:Signature/cac:SignatoryParty/cac:PartyName/cbc:Name` | obligatorio | 2081, 2080 |
| `cac:Signature/cac:DigitalSignatureAttachment/cac:ExternalReference/cbc:URI` | obligatorio | 2083, 2082 |

La hoja escribe las rutas como `/Invoice/cac:Signature`. Las demás hojas, incluida LiquidacionCompra2_0 f.8, remiten a ella ("REVISAR HOJA FIRMA"), así que aplican con su propia raíz.

---

## 4. Documentos

> Leyenda: **M** = mandatorio, **C** = condicional (columna "CONDICIÓN INFORMÁTICA"). **E** = ERROR (rechazo/excepción), **O** = OBSERV.

### 4.1 Factura (01) — `Invoice` (RV/Factura2_0)

**Cabecera**

| N° | Dato | XPath | M/C | Validaciones clave (código, tipo) | Fila |
|---|---|---|---|---|---|
| 1 | UBLVersionID | `/Invoice/cbc:UBLVersionID` = "2.1" | M | 2075 E (no existe), 2074 E (≠2.1) | f.5-6 |
| 2 | CustomizationID | `/Invoice/cbc:CustomizationID` = "2.0" | M | 2073 E, 2072 E | f.7-8 |
| 3 | Serie-número | `/Invoice/cbc:ID` | M | regex `[F][A-Z0-9]{3}-[0-9]{1,8}` o `[0-9]{1,4}-[0-9]{1,8}` (1001 E); 1035/1036 E (≠ nombre de archivo); 1033 E (ya registrado con otros datos); 1032 E (anulado/rechazado); 3207 E / 4204 O (serie física o contingencia no autorizada) | f.10-16 |
| 4 | Fecha de emisión | `cbc:IssueDate` | M | **2108 E** si supera el "plazo máximo vigente"; **2329 E** si es mayor a dos días respecto de la fecha de envío | f.17-18 |
| 5 | Hora | `cbc:IssueTime` | C | sin validación | f.19 |
| 6 | Tipo de documento | `cbc:InvoiceTypeCode` (cat. 01) | M | 1004 E, 1003 E | f.20-21 |
| 7 | Moneda | `cbc:DocumentCurrencyCode` (cat. 02) | M | 2070 E, 3088 E; 2071 E (moneda distinta en los totales) | f.25-27 |
| 8 | Vencimiento | `cbc:DueDate` | C | — | f.31 |
| 10 | RUC del emisor | `cac:AccountingSupplierParty/cac:Party/cac:PartyIdentification/cbc:ID` y `@schemeID="6"` | M | 3089 E (repetido), 1034 E, 2010 E (no activo), 2011 E (no habido), 1078 E, **1086 E (SSCO)**, 1008/1007 E (schemeID) | f.35-44 |
| 12 | Razón social del emisor | `…/cac:PartyLegalEntity/cbc:RegistrationName` | M | 1037 E; 4338 O | f.49-50 |
| 16 | Código de establecimiento | `…/cac:PartyLegalEntity/cac:RegistrationAddress/cbc:AddressTypeCode` (n4) | M | 3030 E si la serie no es numérica y falta; 3239 E | f.80-84 |
| 17 | Documento del adquirente | `cac:AccountingCustomerParty/cac:Party/cac:PartyIdentification/cbc:ID` + `@schemeID` (cat. 06) | M | 2014 E, 2017 E (RUC de 11), 1083 E (RUC no existe), 2800 E (por tipo de operación solo se admite '6', salvo 0112 → 1/6, 2106 → 7/B/G y exportación) | f.88-101 |
| 18 | Razón social del adquirente | `…/cac:PartyLegalEntity/cbc:RegistrationName` | M | 2021 E, 2022 E | f.105-106 |
| 58 | Tipo de operación | `cbc:InvoiceTypeCode/@listID` (cat. 51) | M | 3205 E, 3206 E | f.435 |
| 57 | Leyendas | `cbc:Note/@languageLocaleID` (cat. 52) | C | 3027 E | f.426 |

**Ítems**

| N° | Dato | XPath | Validaciones | Fila |
|---|---|---|---|---|
| 24 | Número de ítem | `cac:InvoiceLine/cbc:ID` | hasta 3 dígitos, distinto de 0 | f.145 |
| 25-26 | Unidad y cantidad | `cbc:InvoicedQuantity/@unitCode` (cat. 03), n(12,10) | — | f.147-151 |
| 28 | Código de producto SUNAT | `cac:Item/cac:CommodityClassification/cbc:ItemClassificationCode` | — | f.154 |
| 31 | Descripción | `cac:Item/cbc:Description` | M | f.175 |
| 32 | Valor unitario | `cac:Price/cbc:PriceAmount` | — | f.177 |
| 33 | Precio unitario | `cac:PricingReference/cac:AlternativeConditionPrice/cbc:PriceAmount` + `cbc:PriceTypeCode` (cat. 16) | — | f.181 |
| 35 | Tributos de la línea | `cac:InvoiceLine/cac:TaxTotal/cbc:TaxAmount` | 3195 E (no existe); **3292 E**: suma de los tributos 1000+1016+2000+7152+9999 | f.199-201 |
| 36 | Afectación IGV | `cac:TaxSubtotal` con `cbc:TaxableAmount`, `cbc:TaxAmount`, `cac:TaxCategory/cbc:Percent`, `cbc:TaxExemptionReasonCode` (cat. 07), `cac:TaxScheme/cbc:ID/Name/TaxTypeCode` (cat. 05) | 3111, 3103, 2992, 2040, 3105, 3223 E | f.204-231 |
| 38 | Valor de venta de la línea | `cbc:LineExtensionAmount` | — | f.278 |

**Totales**

| N° | Dato | XPath | Validaciones | Fila |
|---|---|---|---|---|
| 40 | Total de tributos | `/Invoice/cac:TaxTotal/cbc:TaxAmount` | 2956 E; **3294 E** (suma 1000, 1016, 2000, 7152, 9999) | f.297-299 |
| 46-47 | Gravadas e IGV | — | **3277 E** (suma de líneas); **3291 E** (base × tasa); **3462 E** (todas las líneas con la misma tasa); 4439 O (tasa 10.5 sin padrón) | f.345-353 |
| 53 | Importe total | `cac:LegalMonetaryTotal/cbc:PayableAmount` | 2062 E, **3280 E** | f.411-412 |
| 54 | Total valor de venta | `cbc:LineExtensionAmount` | 3288, 2031, 3278 E | f.414-416 |
| 55 | Total precio de venta | `cbc:TaxInclusiveAmount` | 3305, 3019, 3279 E | f.418-421 |
| 56 | Redondeo | `cbc:PayableRoundingAmount` | 3303 E si el valor absoluto es mayor a 1 | f.423 |

**Forma de pago**: `cac:PaymentTerms/cbc:ID="FormaPago"` es **obligatoria desde el 01/01/2022** (ERROR 3244). Si es "Credito" y el adquirente tiene RUC, se exigen las cuotas `Cuota###` con monto y fecha mayor a la emisión (3249, 3251, 3252, 3253, 3256, 3266, 3267) (f.855-883).

**Otras secciones**: detracción (`PaymentTerms/ID="Detraccion"`, `PaymentMeans`, tipos 1001-1004) f.539-555; percepción (cargo 51/52/53) f.455-472; anticipos f.479-507; retención de IGV (descuento 62) f.885; retención de renta de segunda categoría (63) f.902; contrato de colaboración empresarial (nuevo) f.914-928.

**Orden XML**: GF p.18-24 da un ejemplo del orden (UBLExtensions → UBLVersionID → CustomizationID → ID → IssueDate → IssueTime → DueDate → InvoiceTypeCode → Note → DocumentCurrencyCode → DespatchDocumentReference → AdditionalDocumentReference → Signature → AccountingSupplierParty → AccountingCustomerParty → Delivery/DeliveryTerms → AllowanceCharge → TaxTotal → LegalMonetaryTotal → InvoiceLine). **El orden normativo es el del XSD UBL 2.1 Invoice, [NO CUBIERTO] en la carpeta.**

### 4.2 Boleta (03) — `Invoice` (RV/Boleta2_0)

Usa la misma estructura que la factura, con estas diferencias:
- Serie: regex `[B][A-Z0-9]{3}-[0-9]{1,8}` o `[0-9]{1,4}-[0-9]{1,8}` (1001 E, f.12).
- Fecha: **1079 E** si supera el plazo máximo vigente; 2329 E si es mayor a dos días respecto del envío (f.18-19).
- 1084 E: serie numérica ya informada en un resumen diario (f.15).
- Adquirente:
  - RUC → 2017 E y 1083 E (f.86-87);
  - DNI de distinto de 8 dígitos → solo **observación** 4207 (f.90);
  - otros tipos → 4208 O (f.91);
  - en exportación no se admite RUC (2800 E, f.93).
- Envío: individual con `sendBill` o mediante resumen diario (ver 4.5). Plazo del resumen: el día de emisión o hasta el **7.º día calendario** siguiente (GRB p.4).

### 4.3 Nota de crédito (07) — `CreditNote` (RV/NotaCredito2_0)

- Serie: regex `[B]…`, `[F]…` o numérica (1001 E, f.12).
- Fecha:
  - 2108 E (plazo vigente) si la serie no empieza con B (f.18);
  - **1079 E si la serie empieza con B y han pasado más de 5 días** (f.19);
  - 2329 E (f.20).
- Tipo de nota: `cac:DiscrepancyResponse/cbc:ResponseCode` (cat. 09), M: 2128 E (no existe), 2172 E (fuera de catálogo), 3203 E (repetido) (f.22-24).
- Sustento: `cac:DiscrepancyResponse/cbc:Description`, M: 2136 E (f.28).
- Documento que modifica: `cac:BillingReference/cac:InvoiceDocumentReference/cbc:ID` y `cbc:DocumentTypeCode`.
  - 2524 E si falta, salvo tipo 10 (f.89).
  - **3261 E**: los tipos 01-10, 12 y 13 solo admiten **un** documento referenciado (f.90).
  - 2117 E (regex según el tipo, f.92-97); 2119/2120/2121 E (no existe, anulado o rechazado, f.98-106); 2885 E (la NC es anterior al documento, f.108-110).
  - **3286 E** (importe mayor al original, f.111-113); **3503 E** (los subtotales no pueden superar a los del original, f.114-122); 3209 E (moneda distinta, f.127); 3260 E (tipo 13 solo sobre factura al crédito, f.129).
  - Tipos permitidos (2116/2399/2594, f.131-135). Serie F: 01, 05, 06, 12, 13, 15, 16, 18, 21, 28, 30, 34, 37, 42, 43, 45, 55, 11, 17, 23, 24, 56. Serie B: 03, 12, 16, 55. **Ninguna lista incluye el '04'** (ver §6.11).
- Líneas: `cac:CreditNoteLine` con `cbc:CreditedQuantity`; totales en `cac:LegalMonetaryTotal`.

### 4.4 Nota de débito (08) — `DebitNote` (RV/NotaDebito2_0)

- Igual que la NC, pero el tipo viene del cat. 10 (f.22).
- 2524 E si falta la referencia, salvo el tipo 13-Penalidades (f.89); 3194 E (más de un documento referenciado, f.90); 2204 E (tipos permitidos, f.113).
- Líneas: `cac:DebitNoteLine/cbc:DebitedQuantity`; totales en **`cac:RequestedMonetaryTotal`**.
- Desde el 01/08/2026: la ND tipo 13 (Penalidades) solo puede ser inafecta (ERR-3507, RV/Control_de_Cambios f.537).

### 4.5 Resumen diario (RC) — `SummaryDocuments` (RV/Resumen_Diario1_1)

| N° | Dato | XPath | Validaciones | Fila |
|---|---|---|---|---|
| 01 | UBLVersionID | `/SummaryDocuments/cbc:UBLVersionID` = **"2.0"** | 2075/2074 E | f.6-7 |
| 02 | CustomizationID | `cbc:CustomizationID` = **"1.1"** | 2072 E | f.8 |
| 03 | ID | `cbc:ID` con regex `[R][C]-[0-9]{8}-[0-9]{1,5}` | 2220 E (≠ nombre de archivo), 2223 E (ya presentado) | f.9-10 |
| 04 | IssueDate | fecha de generación | 2346 E (≠ nombre), 2236 E (futura) | f.11-12 |
| 05 | ReferenceDate | fecha de emisión de los documentos | 2671 E (mayor que IssueDate); **4443 O si supera 7 días** | f.13-14 |
| 07 | Emisor | `cac:AccountingSupplierParty/cbc:CustomerAssignedAccountID` (RUC) y `cbc:AdditionalAccountID` = 6; RegistrationName (3-100) | 1034, 2219/2218, 2229/2228 E | f.17-22 |

Línea `sac:SummaryDocumentsLine`:
- `cbc:LineID` (f.24-26).
- `cbc:ID` = serie-número de B o numérica (2513 E, f.28-29); `cbc:DocumentTypeCode` 03/07/08 (2241 E, f.33-34).
- `cac:AccountingCustomerParty`: obligatorio si el importe supera **700** soles (2514 E, f.41); incluye el nombre (10.3).
- `cac:BillingReference` solo para notas (f.49-56).
- `sac:SUNATPerceptionSummaryDocumentReference` (f.58-74).
- `cac:Status/cbc:ConditionCode` (cat. 19: 1 adicionar, 2 modificar, 3 anular) (f.76-77).
- `sac:TotalAmount` (f.78-80).
- `sac:BillingPayment` con `cbc:PaidAmount` y `cbc:InstructionID` (cat. 11; desde 2026 se usan 06/07/08/09 en lugar de 05) (f.81-110).
- `cac:AllowanceCharge` (f.111-114).
- `cac:TaxTotal` (IGV/IVAP obligatorio, tasa 10.5 o 18) (f.116-139).

Reglas de anulación: anular (estado 3) con más de 7 días desde la emisión → **2957 E** (f.37). Tampoco se puede adicionar y modificar, ni modificar y anular, el mismo comprobante en un envío (3095/3096 E, f.39-40).

Envío: `sendSummary` → ticket → `getStatus` (MP p.15-17). Bloques de **500 líneas** por archivo, envíos complementarios (MP p.8).

### 4.6 Comunicación de baja (RA) — `VoidedDocuments` (RV/Comunicación_de_Baja1_0)

- UBLVersionID **"2.0"** (f.5-6), CustomizationID **"1.0"** (f.7).
- `cbc:ID` = `RA-<Fecha>-#####` (2220 E; 2324 E si ya fue presentado) (f.8-9).
- `cbc:IssueDate` (2346, 2301 E) y `cbc:ReferenceDate` (2671 E) (f.10-12).
- Emisor: `cac:AccountingSupplierParty/cbc:CustomerAssignedAccountID`, `cbc:AdditionalAccountID` = 6, nombre (f.14-19); 2011 E si el emisor no está habido (f.15).

Línea `sac:VoidedDocumentsLine`:
- `cbc:LineID` (f.21-24).
- `cbc:DocumentTypeCode`: **01, 07, 08, 25, 28, 30, 34, 42, 56** (2308 E, f.26). **Ni 03 ni 04.**
- `sac:DocumentSerialID` (regex F o numérica, 2310 E, f.27-33); `sac:DocumentNumberID` (f.34-36).
- 2105 E: no registrado (f.37-41); 2398 E: rechazado (f.42); 2323 E: ya dado de baja (f.43).
- **2957 E si han pasado más de 7 días desde la emisión** (f.44).
- 2375 E: la fecha no coincide (f.45-50).
- `sac:VoidReasonDescription` (2315 E; 4203 O si tiene menos de 3 caracteres) (f.51-52).

Envío: `sendSummary` / `getStatus`.

### 4.7 Comprobante de retención (20) — `Retention` (RV/Retenciones1_0)

- UBLVersionID "2.0" (2111/2110 E), CustomizationID "1.0" (2113/2112 E) (f.5-8).
- `cbc:ID` con regex `[R][A-Z0-9]{3}-[0-9]{1,8}` o numérica (1049, 1001 E) (f.10-14).
- `cbc:IssueDate`: 2600 E fuera del plazo vigente (f.15).
- Emisor `cac:AgentParty`: RUC (1034 E; 2617 E si no es agente de retención, `ind_padron` 03) (f.18-21).
- Proveedor `cac:ReceiverParty`: RUC de 11 dígitos (2723/2724/2620/2621 E) (f.34-40).
- `sac:SUNATRetentionSystemCode` (cat. 23: 01 = 3 %, 02 = 6 %) y `sac:SUNATRetentionPercent` (2618/2619 E) (f.53-54).
- Totales en PEN: `cbc:TotalInvoiceAmount` (2669/2628), `sac:SUNATTotalPaid` (2730/2629), redondeo de hasta ±1 (3303) (f.56-63).
- `sac:SUNATRetentionDocumentReference`: tipos 01, 12, 07, 08, 20 (2692 E) (f.65-102), con `cac:Payment`, `sac:SUNATRetentionInformation` y `cac:ExchangeRate`. La tasa del 6 % solo para documentos emitidos hasta el 28/02/2014 (2985 E, f.70).
- Endpoint: otroscpe (MP p.12).

### 4.8 Comprobante de percepción (40) — `Perception` (RV/Percepciones1_0)

- Igual estructura base que la retención; serie `[P]…` (f.11).
- `sac:ExceptionalIndicator` "01" (3322 E) (f.17).
- Emisor con padrón 01/02, solo observación (4285 O) (f.20).
- Cliente: documento de hasta 15 caracteres (2680 E) (f.35-43).
- `sac:SUNATPerceptionSystemCode` (cat. 22: 01 = 2 %, 02 = 1 %, 03 = 0.5 %) (2602, 2603 E) (f.56-59).
- Documentos relacionados 01, 03, 12, 07, 08, 40 (2692 E) (f.72).
- Totales en PEN (f.61-68).

### 4.9 Resumen de reversiones (RR) — `VoidedDocuments` (RV/Resumen_de_reversiones1_0)

Ver el detalle completo en §6.9 (incluye la reversión de liquidaciones de compra).

### 4.10 Plazos de envío: lo que dice la documentación

| Documento | Regla | Fuente |
|---|---|---|
| Factura/NC/ND (serie no numérica) | ERROR 2108 si supera el "plazo máximo vigente según normativa"; desde el 11/10/2024 se retiró la referencia al parámetro 004. **[NO CUBIERTO] el número de días.** | RV/Factura2_0 f.17; RV/Control_de_Cambios f.500 |
| NC/ND serie B | 1079 E con más de 5 días | RV/NotaCredito2_0 f.19 |
| Boleta | 1079 E "plazo máximo vigente" | RV/Boleta2_0 f.18 |
| Todos | 2329 E si IssueDate es mayor a 2 días respecto de la fecha de envío | f.18 de cada hoja |
| Resumen diario | hasta el 7.º día calendario siguiente (GRB p.4); 4443 O fuera de plazo | RV/Resumen_Diario1_1 f.14 |
| Baja | 7 días desde la emisión (2957 E) | RV/Comunicación_de_Baja1_0 f.44 |
| Retención/percepción | 2600 E "plazo máximo vigente" | RV/Retenciones1_0 f.15 |
| **Liquidación de compra** | ver §6.8 | — |

---

## 5. Códigos de retorno (RV/CódigosRetorno: 2 077 códigos, filas 3-2079)

La hoja solo trae código y descripción. **No trae el tipo**: el tipo depende del rango (MP p.29) y de la hoja de cada documento.

Distribución por primer dígito:

| Primer dígito | Cantidad | Tipo según rango |
|---|---|---|
| 0 | 59 | excepciones SUNAT |
| 1 | 87 | excepciones de formato |
| 2 | 964 | rechazos |
| 3 | 540 | rechazos |
| 4 | 427 | observaciones |

Códigos más frecuentes (descripción literal de la hoja):

| Código | Descripción |
|---|---|
| 0100 | El sistema no puede responder su solicitud. Intente nuevamente… |
| 0101 | El encabezado de seguridad es incorrecto |
| 0102 | Usuario o contraseña incorrectos |
| 0103 / 0104 / 0105 | Usuario no existe / Clave incorrecta / Usuario no activo |
| 0109 | Servicio de autenticación no disponible |
| 0111 | No tiene el perfil para enviar comprobantes electrónicos |
| 0125 | No se pudo obtener la constancia |
| 0126 / 0127 | El ticket no le pertenece al usuario / El ticket no existe |
| 0130–0138, 0200 | Errores internos (ticket, storage, batch, BD) → reintentar |
| 0151 / 0159 / 0161 | Nombre ZIP incorrecto / Nombre XML incorrecto / Nombre XML ≠ ZIP |
| 0154 | RUC del archivo no corresponde al usuario o PSE no autorizado |
| 0155–0158 | ZIP vacío / corrupto / sin comprobantes / demasiados comprobantes |
| 0160 | XML vacío |
| 0300 / 0306 | No se encontró la raíz / No se puede parsear el XML |
| 1001 | ID SERIE-CORRELATIVO no cumple el formato |
| 1003 | InvoiceTypeCode no coincide con el nombre del archivo |
| 1032 / 1033 | Ya informado y anulado/rechazado / Registrado previamente con otros datos |
| 1034 / 1035 / 1036 | RUC / serie / número del nombre ≠ XML |
| 1078 | Emisor no autorizado en SEE-Del contribuyente |
| 1083 | RUC del receptor no existe |
| 1086 | Emisor es Sujeto sin capacidad operativa (SSCO) |
| 2010 / 2011 | Contribuyente no activo / no habido |
| 2017 | El documento del receptor debe ser RUC |
| 2108 | Presentación fuera de fecha |
| 2220 / 2223 / 2324 | ID ≠ nombre / ya presentado / baja ya presentada |
| 2282 | Documento ya informado anteriormente (RC) |
| 2325 / 2326 / 2335 | Certificado no comunicado / de baja / documento alterado |
| 2329 | Fecha de emisión fuera del límite permitido |
| 2800 | Tipo de documento de identidad del receptor no permitido |
| 2957 / 2958 | No se puede dar de baja por exceder el plazo desde la emisión / recepción |
| 3205 / 3206 | Falta tipo de operación / no corresponde al cat. 51 |
| 3207 / 4204 | Comprobante físico no autorizado / no autorizado como contingencia |
| 3244 | Debe consignar la información del tipo de transacción (FormaPago) |
| 4287 / 4290 / 4309 / 4310 / 4312 | Diferencias de cálculo (precio, IGV, valor de venta, subtotal, importe total) |
| **2451 / 4210** | **Vendedor supera el monto permitido para la emisión de una liquidación de compra** |
| **2463** | **Emisor no se encuentra afecto a Renta de tercera categoría** |
| **2471** | **La liquidación de compra a dar de baja no debe tener pagos registrados** |
| **4345 / 4346** | **Monto base / importe de la retención de renta no coincide** |
| **4347** | **Enviado fuera del plazo permitido** (LC con serie numérica) |

---

## 6. LIQUIDACIÓN DE COMPRA ELECTRÓNICA (tipo 04) — sección prioritaria

### 6.1 Marco y alcance

- La hoja nace con la **RS N° 244-2019** ("Se agrega la hoja de Liquidación de compra. **Este comprobante no se recibe por SEE-OSE**", RV/Control_de_Cambios f.284, vigencia serial 43832 = 2020-01-01).
- Cabecera de la hoja: "NOTA: COMPROBANTE NO APLICA PARA SEE-OSE" (RV/LiquidacionCompra2_0 f.1). **Se envía directamente a SUNAT (SEE-Del contribuyente), no a través de un OSE.**
- Raíz XML: `SelfBilledInvoice` (todas las filas de la hoja). UBLVersionID **"2.1"** (2075/2074 E, f.9-10) y CustomizationID **"2.0"** (2073/2072 E, f.11-12).
- Catálogo 01: código **04 = Liquidación de compra** (RV/Catálogos f.9).
- **[NO CUBIERTO]** No hay guía XML de LC en la carpeta. La RS 244-2019 y su Anexo (la estructura "9-A"/UBL de LC) **no están** en `01 Resoluciones` (vacía), y el Anexo VII (RS 117-2017) no menciona la LC.

### 6.2 Roles (invertidos respecto de la factura)

| Rol | Nodo UBL | Fuente |
|---|---|---|
| **Emisor electrónico = comprador** (tiene RUC y emite la LC) | `cac:AccountingCustomerParty` | sección "Datos del emisor electrónico (comprador)", f.35-61 |
| **Vendedor** (persona natural sin RUC o con RUC no activo) | `cac:AccountingSupplierParty` | sección "Datos del vendedor", f.62-93 |

### 6.3 Estructura completa fila por fila (RV/LiquidacionCompra2_0)

Abreviatura: `SBI` = `/SelfBilledInvoice`. M = mandatorio; C = condicional.

#### A. Cabecera

| N° | Dato | XPath | M/C | Formato | Validación → código (tipo) | Fila |
|---|---|---|---|---|---|---|
| 1 | Fecha de emisión | `SBI/cbc:IssueDate` | M | YYYY-MM-DD | serie no numérica: más de **1 día** entre la recepción y la emisión ("límite del listado", Parámetros 004 / Plazos excepcionales) → **2108 E**; serie numérica: más de **7 días** → **4347 O**; IssueDate mayor que la fecha de envío → **2329 E** | f.4-6 |
| 2 | Hora | `SBI/cbc:IssueTime` | C | hh:mm:ss | sin validación | f.7 |
| 3 | Firma | (hoja Firma) | M | — | ver §3.3 | f.8 |
| 4 | UBLVersionID | `SBI/cbc:UBLVersionID` | M | "2.1" | 2075 E, 2074 E | f.9-10 |
| 5 | CustomizationID | `SBI/cbc:CustomizationID` | M | "2.0" | 2073 E, 2072 E; `@schemeAgencyName="PE:SUNAT"` (4256 O) | f.11-13 |
| 6 | Tipo de operación | `SBI/cbc:InvoiceTypeCode/@listID` | M | an4, cat. 51 | no existe → **3205 E**; no corresponde al cat. 51 según el tipo de documento → **3206 E**; `@name="Tipo de Operacion"` (4260 O); `@listSchemeURI=urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo51` (4261 O) | f.14-17 |
| 7 | Moneda | `SBI/cbc:DocumentCurrencyCode` | M | an3, cat. 02 | 2070 E, 3088 E; `@listID="ISO 4217 Alpha"`, `@listName="Currency"`, `@listAgencyName="United Nations Economic Commission for Europe"` (4254/4252/4251 O) | f.18-22 |
| 8 | Tipo de documento | `SBI/cbc:InvoiceTypeCode` | M | **"04"** | 1004 E, 1003 E (≠ nombre de archivo); `@listAgencyName="PE:SUNAT"`, `@listName="Tipo de Documento"`, `@listURI=…catalogo01` (O) | f.23-27 |
| 9 | Serie-número | `SBI/cbc:ID` | M | an..13 | 1035 E / 1036 E (≠ nombre); regex **`[L][A-Z0-9]{3}-[0-9]{1,8}`** o **`[0-9]{1,4}-[0-9]{1,8}`** → **1001 E**; 1033 E (serie L ya aceptada, estado 1); 1032 E (anulado/rechazado); serie numérica no autorizada → 4204 O (contingencia) / 3207 E (física) | f.28-34 |

#### B. Emisor electrónico (comprador) — `SBI/cac:AccountingCustomerParty/cac:Party`

| N° | Dato | XPath relativo | M/C | Validación → código | Fila |
|---|---|---|---|---|---|
| 10 | Razón social | `cac:PartyLegalEntity/cbc:RegistrationName` | M | 1037 E; formato de 3-1500 caracteres → 4338 O | f.36-37 |
| 11 | Nombre comercial | `cac:PartyName/cbc:Name` | C | 4092 O | f.38 |
| 12 | Domicilio fiscal | `cac:PartyLegalEntity/cac:RegistrationAddress/cac:AddressLine/cbc:Line`, `cbc:CitySubdivisionName`, `cbc:CityName`, `cbc:ID` (ubigeo, cat. 13, `@schemeAgencyName="PE:INEI"`, `@schemeName="Ubigeos"`), `cbc:CountrySubentity`, `cbc:District`, `cac:Country/cbc:IdentificationCode` = PE | C | todas observaciones: 4094, 4095, 4096, 4093, 4097, 4098, 4041 | f.39-50 |
| 13 | RUC | `cac:PartyIdentification/cbc:ID` | M | 3089 E (repetido); 1034 E (≠ nombre de archivo); **2010 E** no activo; **2011 E** no habido; **2463 E: no afecto a Renta de 3.ª categoría** (el NRUS no cuenta); **1086 E SSCO** | f.51-56 |
| 13 | Tipo de documento | `cac:PartyIdentification/cbc:ID/@schemeID` | M | debe ser **"6"**: 1008 E (no existe), 1007 E (≠6); `@schemeName="Documento de Identidad"`, `@schemeAgencyName="PE:SUNAT"`, `@schemeURI=…catalogo06` (O) | f.57-61 |

#### C. Vendedor — `SBI/cac:AccountingSupplierParty/cac:Party`

| N° | Dato | XPath relativo | M/C | Validación → código | Fila |
|---|---|---|---|---|---|
| 14 | Número de documento | `cac:PartyIdentification/cbc:ID` | M (an..15) | 3090 E (repetido); 2014 E (no existe). Si es DNI ('1'): **2450 E** no existe; **2460 E** fallecido a la fecha de emisión; **2461 E** menor de 18 años; **2462 E** "Número de DNI tiene un Número de RUC asignado activo" (el RUC asociado tiene un estado distinto de 10, 11, 12, 20, 30 y 31). Si es '4' o '7': alfanumérico de hasta 15 sin espacios → 2802 E | f.63-69 |
| 14 | Tipo de documento | `cac:PartyIdentification/cbc:ID/@schemeID` | M (cat. 06) | 2015 E (no existe); **distinto de '1', '4' y '7' → 2800 E** (Parámetros 006); atributos schemeName/AgencyName/URI (O) | f.70-74 |
| 15 | Nombres y apellidos | `cac:PartyLegalEntity/cbc:RegistrationName` | M | 2021 E; 2022 E (hasta 1500, sin saltos ni tabulaciones) | f.75-76 |
| 16 | Domicilio — dirección | `cac:PartyLegalEntity/cac:RegistrationAddress/cac:AddressLine/cbc:Line` | **M** (an..250) | **2452 E** no existe; 2593 E (3-250 caracteres o empieza con espacio/tabulación) | f.77-79 |
| 16 | Urbanización / provincia | `cbc:CitySubdivisionName` / `cbc:CityName` | C | 4341 / 4342 O | f.80-81 |
| 16 | Ubigeo | `cac:RegistrationAddress/cbc:ID` (cat. 13) | **M** | **2453 E** no existe; 4339 O fuera de catálogo; atributos PE:INEI/Ubigeos (O) | f.82-85 |
| 16 | Departamento / distrito / país | `cbc:CountrySubentity`, `cbc:District`, `cac:Country/cbc:IdentificationCode` = PE | C | 4343, 4344, 4041 O | f.86-91 |
| 17 | **Tipo de domicilio del vendedor** | `cac:PartyLegalEntity/cac:RegistrationAddress/cbc:AddressTypeCode` | **M** (an2, **cat. 60**) | **2456 E** no existe; **2457 E** fuera de catálogo | f.92-93 |

#### D. Lugar de la operación — `SBI/cac:DeliveryTerms/cac:DeliveryLocation`

| N° | Dato | XPath relativo | M/C | Validación → código | Fila |
|---|---|---|---|---|---|
| 18 | Dirección | `cac:Address/cac:AddressLine/cbc:Line` | **M** (an..250) | **2454 E** no existe; 2778 E formato o espacio inicial | f.94-96 |
| 18 | Urbanización / provincia | `cac:Address/cbc:CitySubdivisionName` / `cbc:CityName` | C | 4238 / 4239 O | f.97-98 |
| 18 | Ubigeo | `cac:Address/cbc:ID` | **M** | **2455 E** no existe; **2917 E** fuera del cat. 13 (aquí es ERROR, no observación) | f.99-102 |
| 18 | Departamento / distrito / país | `cbc:CountrySubentity`, `cbc:District`, `cac:Country/cbc:IdentificationCode` = PE | C | 4240, 4241, 4041 O | f.103-108 |
| 19 | **Tipo de ubicación** | `cac:DeliveryLocation/cbc:LocationTypeCode` | **M** (cat. 60) | **2458 E** no existe; **2459 E** fuera de catálogo | f.109-110 |

Catálogo 60 "Código de tipo de dirección" (RV/Catálogos f.829-836): **01 Punto de venta, 02 Producción, 03 Extracción, 04 Explotación, 05 Otros**. Lo creó la RS 244-2019 (RV/Control_de_Cambios f.287).

#### E. Detalle por ítem — `SBI/cac:InvoiceLine`

| N° | Dato | XPath | M/C | Formato | Validación → código | Fila |
|---|---|---|---|---|---|---|
| 20 | Número de ítem | `cac:InvoiceLine/cbc:ID` | M | n..3, distinto de 0 | 2023 E; 2752 E duplicado (máximo 999 líneas, Control_de_Cambios f.352) | f.112-113 |
| 21 | Cantidad | `cbc:InvoicedQuantity` | M | n(12,10) | 2024 E (no existe o es 0); 2025 E | f.114-115 |
| 22 | Unidad | `cbc:InvoicedQuantity/@unitCode` | M | cat. 03 | 2883 E; 2936 E; `@unitCodeListID="UN/ECE rec 20"`, `@unitCodeListAgencyName` (O) | f.116-119 |
| 23 | Descripción | `cac:Item/cbc:Description` | M | an..500 | 2026 E; 2027 E | f.120-121 |
| 24 | Código de producto | `cac:Item/cac:SellersItemIdentification/cbc:ID` | C | an..30 | 4269 O | f.122 |
| 25 | **Código de producto SUNAT** | `cac:Item/cac:CommodityClassification/cbc:ItemClassificationCode` | C, pero en la práctica **obligatorio** | n8, cat. 25, 25.1, 25.2, 25.3 | **3506 E si no existe** (nueva, vigente desde el 01/08/2026 según Control_de_Cambios f.529); 3496 E formato o fuera de lista (salvo 00000000/99999999); 4332 O; 4337 O (mínimo nivel de clase); atributos `@listID="UNSPSC"`, `@listAgencyName="GS1 US"`, `@listName="Item Classification"` (O) | f.123-130 |
| 26 | Valor unitario | `cac:Price/cbc:PriceAmount` | M | n(12,10) | 2068 E; 2369 E (sin ceros a la izquierda en enteros); 2640 E si es gratuita (9996) y el valor es mayor a 0; `@currencyID` = moneda → 2337 E | f.131-134 |
| 27 | Precio de compra unitario / valor referencial | `cac:PricingReference/cac:AlternativeConditionPrice/cbc:PriceAmount` y `cbc:PriceTypeCode` (cat. 16) | M | n(12,10) | 2028 E; 2367 E; **4287 O**: precio ≠ (LineExtensionAmount + TaxTotal de la línea) / cantidad, tolerancia ±1; 3224 E / 3234 E (código '02' solo en gratuitas); 2410 E; 2409 E | f.135-145 |
| 28 | Valor de venta del ítem | `cbc:LineExtensionAmount` | M | n(12,2) | 2370 E; **4288 O**: ≠ valor unitario × cantidad (±1) | f.146-149 |
| 29 | Oro: propiedades | `cac:Item/cac:AdditionalItemProperty/cbc:Name`, `cbc:NameCode` (cat. 55), `cbc:Value` | C | — | si el tipo de operación es **0503**, son obligatorios los NameCode **6000** (código único de concesión minera, 2466 E), **6004** (ley mineral n(3,2), 2467 E / 4280 O), **6005** (naturaleza del mineral, 2468 E), **6006** (nombre del derecho minero, 2469 E); Value vacío → 3064 E | f.151-163 |
| 30 | Total de tributos del ítem | `cac:InvoiceLine/cac:TaxTotal/cbc:TaxAmount` | M | n(12,2) | 3195 E no existe; 3021 E; **4293 O: debe ser la suma de los tributos '1000' y '9999' (NO incluye el '3000')**; 3026 E (TaxTotal repetido) | f.164-168 |

**IGV por ítem (N° 31)**, `cac:InvoiceLine/cac:TaxTotal/cac:TaxSubtotal` (f.169-199):

| Elemento | Validaciones | Filas |
|---|---|---|
| `cbc:TaxableAmount` | 3031 E; 4294 O (≠ LineExtensionAmount) | f.169-170 |
| `cbc:TaxAmount` | 2033 E; 3110 E (9997/9998 deben ser 0); 3111 E (gravado con base > 0.06 y monto 0); **3103 E (≠ tasa × base, ±1)** | f.172-177 |
| `cac:TaxCategory/cbc:Percent` | 2992 E (no existe); 3102 E; 2993 E (tasa 0 en gravado) | f.179-182 |
| `cac:TaxCategory/cbc:TaxExemptionReasonCode` (cat. 07) | 2371 E si falta (salvo los tributos 9999/3000); **3050 E si se informa en las líneas de 9999 o 3000**; 2040 E | f.183-188 |
| `cac:TaxScheme/cbc:ID` (cat. 05) | 2037 E, 2036 E, 3067 E (repetido en el ítem); **3105 E: cada línea debe tener al menos un tributo 1000, 9996, 9997 o 9998 con base > 0** | f.189-192 |
| Combinaciones por línea | **3223 E**: `1000 + 3000 y/o 9999`; `9996 + 3000 y/o 9999`; `9997 + 3000 y/o 9999`; `9998 + 3000 y/o 9999` | f.193 |
| `cbc:Name` y `cbc:TaxTypeCode` según cat. 05 | 2996, 3051, 2377 E | f.197-199 |

**Retención de Impuesto a la Renta por ítem (N° 32)**, otro `cac:TaxSubtotal` en la misma línea, **condición M** (f.200-217):

| Elemento | Validaciones | Filas |
|---|---|---|
| `cbc:TaxableAmount` | 3031 E; 4294 O (≠ valor de venta del ítem) | f.200-201 |
| `cbc:TaxAmount` | 2033 E; **2464 E: ≠ tasa × base (±1)** | f.203-204 |
| `cac:TaxCategory/cbc:Percent` | 2992 E (no existe); **4237 O: si el tributo es '3000' con base > 0, la tasa debe ser "alguna de las tasas vigentes de Retención de renta a la fecha de emisión"**; 3102 E | f.206-208 |
| `cac:TaxScheme/cbc:ID` | **'3000'**; 2037, 2036, 3067 E | f.209-211 |
| `cbc:Name` | **"IR"** | f.215-216 |
| `cbc:TaxTypeCode` | **"TOX"** (según cat. 05) | f.217 |

**Otros tributos por ítem (N° 33)**, `9999`, condicional (f.218-233): base y monto; 3109 E (tasa × base); mismos controles de código y nombre.

#### F. Totales — `SBI/cac:TaxTotal` y `SBI/cac:LegalMonetaryTotal`

| N° | Dato | XPath | M/C | Validación → código | Fila |
|---|---|---|---|---|---|
| 34 | Monto total de tributos | `SBI/cac:TaxTotal/cbc:TaxAmount` | M | 2956 E no existe; 3020 E; **4301 O: = suma de los TaxAmount globales de '1000' y '9999'** (no incluye el 3000); 3024 E (TaxTotal repetido) | f.235-239 |
| 35-36 | Gravadas e IGV | `cac:TaxTotal/cac:TaxSubtotal` (ID 1000) con `cbc:TaxableAmount`, `cbc:TaxAmount` | M | 3003 E; 2999 E; **4299 O** (base = suma de los LineExtensionAmount de las líneas 1000 − anticipos gravados '04'); 2048 E; **4290 O** (IGV = (suma de las bases 1000 − anticipos '04') × tasa vigente); 3059/3007/3068 E (código); 2054/2964 E (Name); 2052/2961 E (TaxTypeCode) | f.240-256 |
| 37-38 | Inafectas (9998) / exoneradas (9997) | ídem | C | 3003, 2999 E; 4297 / 4296 O (sumas − anticipos 05/06); 4022/4023/4024/4244 O (leyendas 2001/2002/2003/2008 requieren exoneradas > 0); 3000 E (TaxAmount ≠ 0) | f.257-278 |
| 39-40 | Gratuitas (9996) | ídem | C | 3003, 2999 E; 4298 O; 2641 E; 2416 E (leyenda 1002); 4311 O | f.279-297 |
| **41** | **Retención de Impuesto a la Renta** | `cac:TaxTotal/cac:TaxSubtotal` (ID 3000) con `cbc:TaxableAmount`, `cbc:TaxAmount` | **M** | 3003 E (no existe); **2470 E** (formato de la base); **4345 O: base = suma de los LineExtensionAmount de las líneas con 3000 y base > 0, sin contar las líneas gratuitas (9996)**; 2048 E; **4346 O: importe = suma de los TaxAmount 3000 de las líneas (sin gratuitas) − retención de anticipos (descuento '61')**; código, Name y TaxTypeCode según cat. 05 (3059/3007/3068/2054/2964/2052/2961 E) | f.298-314 |
| 42 | Otros tributos (9999) | ídem | C | 4304 O, 4306 O | f.315-331 |
| **43** | Total valor de venta | `cac:LegalMonetaryTotal/cbc:LineExtensionAmount` | M | 2591 E; 2031 E; **4309 O** (= suma de los LineExtensionAmount de las líneas 1000, 9997 y 9998); **2451 E (serie L) / 4210 O (serie numérica): el vendedor supera el monto máximo anual de 75 UIT** (acumulado desde el 1 de enero hasta el día anterior a la emisión) | f.332-337 |
| **44** | "Sub total de la liquidación de compra" | `cac:LegalMonetaryTotal/cbc:TaxInclusiveAmount` | M | 2592 E; 3019 E; **4310 O: = Total valor de venta + Otros tributos + (suma de las bases 1000 × tasa IGV vigente)** | f.338-341 |
| 45 | Redondeo | `cac:LegalMonetaryTotal/cbc:PayableRoundingAmount` | C | 4314 O si el valor absoluto es mayor a 1 | f.342-343 |
| **46** | **Importe total neto** | `cac:LegalMonetaryTotal/cbc:PayableAmount` | M | 2062 E; **4312 O** (fórmula abajo) | f.344-346 |

#### G. Anticipos

- Solo con el tipo de operación 0502 en la práctica. **[INFERENCIA]**: las reglas no atan los anticipos al tipo 0502 de forma explícita.
- `SBI/cac:PrepaidPayment/cbc:ID` (n2), `cbc:PaidAmount`, `cbc:PaidDate` (f.348-356): 3211, 3212, 3213, 2503, 3220 E.
- `SBI/cac:AdditionalDocumentReference` con `cbc:DocumentStatusCode` (identificador de pago), `cbc:ID` y `cbc:DocumentTypeCode` **= '10'** (cat. 12, "Liquidación de compra – emitida por anticipos"), más `cac:IssuerParty/cac:PartyIdentification/cbc:ID` con `@schemeID` = 6 (f.357-374).
  - Errores: 3214, 3215, 3216, 2521, **2465 E** (tipo ≠ 10), 3217, 2529, 3218 E, 3219 O, 2520 E.
  - Regex de la serie del anticipo: `[L][A-Z0-9]{3}-[0-9]{1,8}`, `(E001)-[0-9]{1,8}` o `[0-9]{1,4}-[0-9]{1,8}` (f.362).
- `SBI/cac:AllowanceCharge` con `cbc:ChargeIndicator="false"` (3114 E):
  - `cbc:AllowanceChargeReasonCode` (cat. 53) = 04, 05 o 06 para el valor de venta del anticipo (N° 48, f.375-382);
  - **'61' = Retención de renta por anticipos** (N° 49, f.383-390);
  - `cbc:Amount`: 2968 E.
- `cac:LegalMonetaryTotal/cbc:PrepaidAmount` (N° 50): 2509 E si no cuadra con la suma (f.391-392).

#### H. Información adicional

- Leyendas: `SBI/cbc:Note/@languageLocaleID` (cat. 52): 3027 E, 3014 E (repetida), 3006 E (texto de 1-200) (f.394-396).
- Guía de remisión: `SBI/cac:DespatchDocumentReference/cbc:ID` (regex de guías, 4006 O; 2364 E repetida) y `cbc:DocumentTypeCode` = 09 o 31 (4005 O) (f.397-402).
- Otros documentos: `SBI/cac:AdditionalDocumentReference/cbc:ID` (4010 O; 2365 E) y `cbc:DocumentTypeCode` = **'10' o '99'** (4009 O) (f.403-408).

**Lo que la hoja de LC NO contiene, a diferencia de la factura**: forma de pago (FormaPago/cuotas), detracciones, percepciones, ISC (2000), ICBPER (7152), IVAP (1016), exportación (9995), código de establecimiento anexo (AddressTypeCode del emisor) y `cbc:DueDate`. No hay validación para esos campos en la hoja LC.

### 6.4 Tributos de la LC y cálculo de los totales

**Códigos del catálogo 05 que admite la LC** (RV/Catálogos f.72-81; RV/LC f.193):

| Código | Nombre | Código internacional | Nombre corto | Rol en la LC |
|---|---|---|---|---|
| 1000 | IGV Impuesto General a las Ventas | VAT | IGV | IGV de la compra |
| **3000** | **Impuesto a la Renta** | **TOX** | **IR** | **retención del IR** |
| 9996 | Gratuito | FRE | GRA | gratuitas |
| 9997 | Exonerado | VAT | EXO | exoneradas |
| 9998 | Inafecto | FRE | INA | inafectas |
| 9999 | Otros tributos | OTH | OTROS | otros tributos |

**Porcentajes**:
- **[NO CUBIERTO]** La regla solo dice "alguna de las tasas vigentes de Retención de renta a la fecha de emisión" (f.207, 4237 O) para el 3000, y "tasa vigente del IGV a la fecha de emisión" para el 1000 (f.245, f.340). Las tasas numéricas de retención de IR en LC y el parámetro 012 de tasa IGV **no están** en la documentación.
- La hoja Parámetros define el parámetro 012 (tasa IGV) pero no trae su valor (RV/Parámetros f.15).

**Fórmulas** (todas con tolerancia ±1 y de tipo **OBSERVACIÓN**: no rechazan):

```
Por línea:
  LineExtensionAmount        = PriceAmount × InvoicedQuantity                         (4288, f.148)
  TaxSubtotal[1000].TaxAmount = Percent × TaxableAmount                               (3103 E, f.177)
  TaxSubtotal[3000].TaxAmount = Percent × TaxableAmount                               (2464 E, f.204)
  TaxSubtotal[*].TaxableAmount = LineExtensionAmount                                  (4294, f.170/f.201)
  TaxTotal.TaxAmount (línea)  = Σ TaxAmount de 1000 + 9999   ← SIN 3000               (4293, f.166)
  AlternativeConditionPrice   = (LineExtensionAmount + TaxTotal línea) / cantidad     (4287, f.137)

Globales:
  TaxTotal.TaxAmount          = Σ TaxAmount globales 1000 + 9999  ← SIN 3000          (4301, f.237)
  Base IR (3000)              = Σ LineExtension de líneas con 3000 (excluye gratuitas)(4345, f.300)
  Retención IR (3000)         = Σ TaxAmount 3000 de líneas (excl. gratuitas) − AllowanceCharge '61' (4346, f.303)
  LineExtensionAmount (LMT)   = Σ LineExtension de líneas 1000, 9997, 9998            (4309, f.334)
  TaxInclusiveAmount          = LineExtensionAmount + Otros tributos(9999)
                                + Σ bases 1000 × tasa IGV                             (4310, f.340)
  PayableAmount ("Importe total neto") =
        TaxInclusiveAmount
      − Σ TaxAmount de líneas 1000   (IGV)
      − Σ TaxAmount de líneas 3000   (Retención IR)
      − Σ TaxAmount de líneas 9999   (Otros tributos)
      − PrepaidAmount (anticipos), de corresponder
      + PayableRoundingAmount                                                         (4312, f.345)
```

**Lectura para implementadores**: el importe neto que el comprador **paga** al vendedor es el valor de venta menos la retención del IR (menos los anticipos). El IGV y los otros tributos se suman en el subtotal y se restan en el neto: el comprador emisor **no paga el IGV al vendedor**. **[INFERENCIA]**: es coherente con que el comprador asuma el pago del IGV (régimen de la RS 244-2019, no disponible en la carpeta). La fórmula en sí es literal de f.345.

**Ejemplo numérico** **[INFERENCIA]**. Las tasas son supuestas para ilustrar y deben confirmarse con la norma:
- 1 ítem gravado: valor de venta 1 000.00; IGV 18 % = 180.00; retención IR de r % = 1 000 × r.
- TaxTotal.TaxAmount = 180.00.
- TaxInclusiveAmount = 1 180.00.
- PayableAmount = 1 180 − 180 − 1 000 × r = 1 000 × (1 − r).

### 6.5 Tipos de operación (catálogo 51) para el tipo 04

RV/Catálogos f.551-553 (columna "Tipo de Comprobante asociado" = Liquidación de compra):

| Código | Descripción |
|---|---|
| **0501** | Compra interna |
| **0502** | Anticipos |
| **0503** | Compra de oro (exige las propiedades del ítem 6000/6004/6005/6006, f.152-155) |

Cualquier otro código del cat. 51 para el tipo 04 → **3206 E** (f.15).

### 6.6 Tipos de documento permitidos del vendedor

Solo **1 (DNI), 4 (Carnet de extranjería) y 7 (Pasaporte)**. Otro valor → 2800 E (f.71).

Controles adicionales sobre el DNI (f.65-68):
- debe existir (2450);
- no fallecido (2460);
- mayor de edad (2461);
- sin RUC activo asociado (2462): el RUC asociado debe estar en estado 10, 11, 12, 20, 30 o 31.

### 6.7 Serie y numeración

- Electrónica: **`^L[A-Z0-9]{3}-[0-9]{1,8}$`** (f.30, 1001 E).
- Física o contingencia: `^[0-9]{1,4}-[0-9]{1,8}$`, que debe estar autorizada (3207 E / 4204 O, f.33-34).
- Nombre de archivo: `RUC-04-LXXX-N.zip`, con la serie del tipo 04 "alfanumérico que empieza con L" (RV/General f.6, f.13).

### 6.8 Plazos

| Plazo | Regla | Fuente |
|---|---|---|
| Envío (serie L) | **1 día** entre la emisión y la recepción ("mayor al límite del listado (1 día)", Parámetros 004 / Plazos Excepcionales) → **2108 E** | f.4 |
| Envío (serie numérica, contingencia) | 7 días → 4347 O | f.5 |
| Emisión futura | IssueDate mayor que la fecha de envío → 2329 E (más estricto que en la factura, donde se toleran 2 días) | f.6 |
| Límite anual | **75 UIT** anuales por vendedor (1 de enero hasta IssueDate − 1) → 2451 E / 4210 O | f.335-336 |
| Reversión | ver §6.9 | — |

Nota: el cambio del 11/10/2024 que retiró el parámetro 004 de las validaciones de plazo (RV/Control_de_Cambios f.500) lista FAC, BOL, NC, ND, DAE, Retenciones y Percepciones. **No incluye la LC**, cuya fila 4 sigue diciendo "1 día". **[NO CUBIERTO]** El valor de la UIT por año no está en la carpeta.

### 6.9 Reversión de la LC: Resumen de Reversiones (RR)

**Sí aplica al tipo 04.** No se usa la comunicación de baja (RA), cuya lista de tipos (01, 07, 08, 25, 28, 30, 34, 42, 56) **no incluye el 04** (RV/Comunicación_de_Baja1_0 f.26).

Pruebas de que la reversión cubre la LC:
- RV/Control_de_Cambios f.285 (20/12/2019, RS 244-2019): "Se agregan validaciones por Liquidación de compra [ERR-2308, ERR-2345, ERR-2958, ERR-2398, ERR-2471] … no aplican para SEE-OSE", hoja "Resumen de reversiones".
- RV/Control_de_Cambios f.545 (versión 24/04/2026, **vigencia serial 46235 = 01/08/2026**): "**Se modifica el plazo de 7 días a 5 días para informar las bajas de Liquidaciones de compra**" (ERR-2958).

**Estructura** (RV/Resumen_de_reversiones1_0). Raíz `VoidedDocuments` (la misma que la baja), enviada con **sendSummary**:

| N° | Dato | XPath | Regla → código | Fila |
|---|---|---|---|---|
| — | ticket | — | 0127 E (no existe) | f.3 |
| 1 | UBLVersionID | `/VoidedDocuments/cbc:UBLVersionID` = **"2.0"** | 2075, 2074 E | f.5-6 |
| 2 | CustomizationID | `cbc:CustomizationID` = **"1.0"** | 2072 E | f.7 |
| 3 | ID | `cbc:ID` = **`RR-<YYYYMMDD>-#####`** (an..17) | 2220 E (≠ nombre de archivo), 2223 E (ya presentado) | f.8-9 |
| 4 | Fecha de generación | `cbc:IssueDate` | 2346 E (≠ nombre), 2301 E (futura) | f.10-11 |
| 5 | Fecha de los documentos | `cbc:ReferenceDate` | 2671 E (mayor que IssueDate) | f.12 |
| 6 | Firma | hoja Firma | — | f.13 |
| 7 | RUC del emisor | `cac:AccountingSupplierParty/cbc:CustomerAssignedAccountID` | 1034 E | f.14 |
| 7 | Tipo de documento del emisor | `cac:AccountingSupplierParty/cbc:AdditionalAccountID` = 6 | 2288, 2287 E | f.15-16 |
| 8 | Razón social | `cac:AccountingSupplierParty/cac:Party/cac:PartyLegalEntity/cbc:RegistrationName` (3-100) | 2229, 2228 E | f.17-18 |
| 9 | Ítem | `sac:VoidedDocumentsLine/cbc:LineID` | 2307, 2305, 2306, 2752 E | f.20-23 |
| 10 | Tipo de documento | `sac:VoidedDocumentsLine/cbc:DocumentTypeCode` | 2309 E; **2308 E si no es '20', '40' o '04'** (el '04' no aplica en SEE-OSE) | f.24-26 |
| 11 | Serie | `sac:VoidedDocumentsLine/sac:DocumentSerialID` (an4) | 2311 E; 20 → `[R]…`/numérica (2674); 40 → `[P]…`/numérica (2675); **04 → `[L][A-Z0-9]{3}` o `[0-9]{1,4}` (2345 E)**; **04 con serie L: más de 5 días entre la recepción del RR y la "Fecha del CDR aceptado" de la LC → 2958 E** | f.27-31 |
| 12 | Número | `sac:VoidedDocumentsLine/sac:DocumentNumberID` (n..8) | 2313, 2312 E; 2348 E (duplicado); **2750 E** (no existe); **2398 E** (LC rechazada); **2751 E** (ya revertida); **2471 E: la LC no debe tener pagos registrados** | f.32-38 |
| 13 | Motivo | `sac:VoidedDocumentsLine/sac:VoidReasonDescription` (an..100) | 2315 E; 4203 O (menos de 3 caracteres) | f.39-40 |

Nombre de archivo: `RUC-RR-YYYYMMDD-N.zip` (RV/General f.7; MP p.8-9). Endpoint según el MP: el de retención/percepción/reversión (otroscpe) (MP p.12). **[NO CUBIERTO]** El MP no dice si el RR de una LC va a `ol-ti-itcpfegem` o a `otroscpe`, porque el manual es anterior a la LC.

Formato texto/JSON del Anexo II (AX/Reversión f.7-13), para el facturador SUNAT y no para XML: archivo `RUC-RR-YYYYMMDD-CCC.RDR` con `fecGeneracion`, `fecComunicacion`, `tipDocBaja` (cat. 01, **solo "20 ó 40"**), `numDocBaja` (`RXXX-…`/`PXXX-…`) y `desMotivoBaja`. Este anexo (v1.3.4) **no contempla el 04**: está desactualizado frente a las RV.

### 6.10 Envío de la LC (servicio y operación)

- **Operación**: la LC es un comprobante individual de envío síncrono. **[INFERENCIA]**: se envía con **`sendBill`** (un ZIP con un XML y nombre `RUC-04-Lxxx-N`). Así trata el MP a los "comprobantes" (MP p.15-16), y la hoja General admite el tipo 04 en el nombre del ZIP/XML (f.6, f.13). El MP no nombra la LC de forma explícita.
- **Endpoint**: **[NO CUBIERTO]**. El MP (mayo 2021) no lista la LC. **[INFERENCIA]**: es el `billService` de comprobantes (`ol-ti-itcpfegem`, beta `ol-ti-itcpfegem-beta`), que es lo que usa Greenter para el tipo 04. Hay que confirmarlo en beta.
- **No pasa por OSE** (RV/LC f.1; Control_de_Cambios f.284).
- **Recuperar el CDR**: `getStatusCdr` documenta solo 01, 07 y 08 (MP p.22). Para el 04, **[NO CUBIERTO]**. La Consulta Integrada REST **sí** admite codComp 04 (CI p.11).
- **Reversión**: RR con `sendSummary` + `getStatus` (§6.9).

### 6.11 Otros hallazgos sobre la LC

- **Notas de crédito y débito sobre una LC**: en las listas de "Tipo de documento que modifica" de NC (RV/NotaCredito2_0 f.131-135) y ND (RV/NotaDebito2_0 f.113) **no aparece el '04'**. Según las RV vigentes, una NC/ND electrónica no puede referenciar una LC. La corrección se hace revirtiendo (RR) y emitiendo de nuevo. **[INFERENCIA]** sobre el procedimiento; la ausencia del '04' en las listas es un hecho.
- **Código de producto SUNAT obligatorio** en la LC desde el 01/08/2026 (ERR-3506/3496, Control_de_Cambios f.529; RV/LC f.123).
- **SSCO**: la ERR-1086 aplica a la LC (Control_de_Cambios f.494, vigente desde el 29/02/2024).
- Totales en LC: siguen como **OBSERVACIONES** (4290, 4309, 4310, 4312). El cambio OBS→ERR del 24/04/2026 (Control_de_Cambios f.523) aplica **solo a DAE-OP**, no a la LC.
- Catálogo 53 '61' (Retención de renta por anticipos) y catálogo 60 se crearon para la LC (Control_de_Cambios f.286-287).

### 6.12 Orden de los elementos en SelfBilledInvoice

**[NO CUBIERTO]** No hay XSD en la carpeta. El orden de la hoja RV **no** es el orden del esquema.

**[ESTÁNDAR UBL — verificar con `UBL-SelfBilledInvoice-2.1.xsd`]** Según OASIS UBL 2.1, el orden aplicable es:

```
ext:UBLExtensions, cbc:UBLVersionID, cbc:CustomizationID, cbc:ProfileID?, cbc:ID, cbc:IssueDate,
cbc:IssueTime, cbc:DueDate?, cbc:InvoiceTypeCode, cbc:Note*, cbc:DocumentCurrencyCode, …,
cac:OrderReference?, cac:BillingReference*, cac:DespatchDocumentReference*,
cac:AdditionalDocumentReference*, cac:Signature,
cac:AccountingCustomerParty, cac:AccountingSupplierParty   ← en SelfBilledInvoice el CUSTOMER va ANTES que el SUPPLIER
…, cac:Delivery*, cac:DeliveryTerms*, cac:PaymentMeans*, cac:PaymentTerms*, cac:PrepaidPayment*,
cac:AllowanceCharge*, …, cac:TaxTotal*, cac:LegalMonetaryTotal, cac:InvoiceLine+
```

Namespace raíz: `urn:oasis:names:specification:ubl:schema:xsd:SelfBilledInvoice-2`.

---

## 7. Catálogos clave (RV/Catálogos, versión 24/04/2026)

- **01 Tipo de documento** (f.7-47): 01 Factura; 03 Boleta; **04 Liquidación de compra**; 05 Boleto aéreo; 06 Carta de porte aéreo; 07 NC; 08 ND; 09 GRE remitente; 11; 12 Ticket de máquina registradora; 13; 14 Recibo de servicios públicos; 15; 16 Boleto interprovincial; 18; 19; 20 Comprobante de retención; 21; 23; 24; **25 Documento de atribución** (nuevo en 2026); 28 TUUA; 29; 30; 31 GRE transportista; 32; 34; 35; 36; 37; 40 Comprobante de percepción; 41; 42; 43; 45; 55 BVME ferroviario; 56 Comprobante SEAE/DAE-SEAE; 71; 72; 87 NC especial; 88 ND especial.
- **03 Unidad de medida**: UN/ECE Recommendation 20 Rev. 13 (f.57-61); solo trae la referencia, sin lista. Atributo `@unitCodeListID="UN/ECE rec 20"`. (El cat. 65 es para la GRE.)
- **05 Tributos** (TODOS, f.72-81):

  | Código | Descripción | Internacional | Nombre |
  |---|---|---|---|
  | 1000 | IGV Impuesto General a las Ventas | VAT | IGV |
  | 1016 | Impuesto a la Venta Arroz Pilado | VAT | IVAP |
  | 2000 | ISC Impuesto Selectivo al Consumo | EXC | ISC |
  | 3000 | Impuesto a la Renta | TOX | IR |
  | 7152 | Impuesto a la bolsa plástica | OTH | ICBPER |
  | 9995 | Exportación | FRE | EXP |
  | 9996 | Gratuito | FRE | GRA |
  | 9997 | Exonerado | VAT | EXO |
  | 9998 | Inafecto | FRE | INA |
  | 9999 | Otros tributos | OTH | OTROS |

- **06 Documento de identidad** (f.86-98): 0 DOC.TRIB.NO.DOM.SIN.RUC; 1 DNI; 4 Carnet de extranjería; 6 RUC; 7 Pasaporte; A Cédula diplomática; B Doc. de identidad del país de residencia (no domiciliado); C TIN; D IN; E TAM; F PTP; G Salvoconducto; H CPP.
- **07 Afectación del IGV** (TODOS, f.103-121; entre paréntesis el tributo asociado):

  | Código | Descripción | Tributo |
  |---|---|---|
  | 10 | Gravado – Operación onerosa | 1000 |
  | 11 | Gravado – Retiro por premio | 9996 |
  | 12 | Gravado – Retiro por donación | 9996 |
  | 13 | Gravado – Retiro | 9996 |
  | 14 | Gravado – Retiro por publicidad | 9996 |
  | 15 | Gravado – Bonificaciones | 9996 |
  | 16 | Gravado – Retiro por entrega a trabajadores | 9996 |
  | 17 | Gravado – IVAP | 1016 o 9996 |
  | 20 | Exonerado – Operación onerosa | 9997 |
  | 21 | Exonerado – Transferencia gratuita | 9996 |
  | 30 | Inafecto – Operación onerosa | 9998 |
  | 31 | Inafecto – Retiro por bonificación | 9996 |
  | 32 | Inafecto – Retiro | 9996 |
  | 33 | Inafecto – Retiro por muestras médicas | 9996 |
  | 34 | Inafecto – Retiro por convenio colectivo | 9996 |
  | 35 | Inafecto – Retiro por premio | 9996 |
  | 36 | Inafecto – Retiro por publicidad | 9996 |
  | 37 | Inafecto – Transferencia gratuita | 9996 |
  | 40 | Exportación de bienes o servicios | 9995 o 9996 |

- **09 Tipo de NC** (f.133-145):
  - 01 Anulación de la operación
  - 02 Anulación por error en el RUC
  - 03 Corrección por error en la descripción o atención de reclamo / corrección por error en la descripción y/o código de producto SUNAT
  - 04 Descuento global
  - 05 Descuento por ítem
  - 06 Devolución total
  - 07 Devolución por ítem
  - 08 Bonificación
  - 09 Disminución en el valor
  - 10 Otros conceptos
  - 11 Ajustes de operaciones de exportación
  - 12 Ajustes afectos al IVAP
  - 13 Corrección o modificación del monto neto pendiente de pago y/o fechas de vencimiento o cuotas
- **10 Tipo de ND** (f.150-155): 01 Intereses por mora; 02 Aumento en el valor; 03 Penalidades / otros conceptos; 11 Ajustes de exportación; 12 Ajustes afectos al IVAP; **13 Penalidades** (nuevo).
- **12 Documentos relacionados** (f.175-185): 01 Factura – error en RUC; 02 Factura – anticipos; 03 Boleta – anticipos; 04 Ticket de salida ENAPU; 05 Código SCOP; 06 Factura electrónica remitente; 07 GR remitente; 08 Declaración de salida del depósito franco; 09 Declaración simplificada de importación; **10 Liquidación de compra – emitida por anticipos**; 99 Otros.
- **16 Tipo de precio** (f.261-263): 01 Precio unitario (incluye el IGV); 02 Valor referencial unitario en operaciones no onerosas; 03 Tarifas reguladas.
- **17 Tipo de operación** (f.268-287; antiguo, el de la guía 2017): 01 Venta interna; 02 Exportación de bienes; 03 No domiciliados; 04 Venta interna – anticipos; 05 Venta itinerante; 06 Factura guía; 07 Venta arroz pilado; 08 Factura – comprobante de percepción; 10 Factura – guía remitente; 11 Factura – guía transportista; 12 Boleta – comprobante de percepción; 13 Gasto deducible persona natural; 14-21 Exportación de servicios (varios). **En UBL 2.1 se usa el cat. 51.**
- **51 Tipo de operación** (TODOS, f.536-566; entre paréntesis el comprobante asociado):

  | Código | Descripción | Comprobante |
  |---|---|---|
  | 0101 | Venta interna no sujeta a detracción, retención o percepción | F, B |
  | 0112 | Venta interna – sustenta gastos deducibles persona natural | F |
  | 0113 | Venta interna – NRUS | B |
  | 0200 | Exportación de bienes | F, B |
  | 0201 | Exportación de servicios – prestados íntegramente en el país | F, B |
  | 0202 | Exportación de servicios – hospedaje no domiciliado | F |
  | 0203 | Exportación de servicios – transporte de navieras | F, B |
  | 0204 | Exportación de servicios – naves y aeronaves de bandera extranjera | F, B |
  | 0205 | Exportación de servicios – paquete turístico | F |
  | 0206 | Exportación de servicios – complementarios al transporte de carga | F, B |
  | 0207 | Exportación de servicios – suministro de energía eléctrica en ZED | F, B |
  | 0208 | Exportación de servicios – prestados parcialmente en el extranjero | F, B |
  | 0301 | Carta de porte aéreo | F, B |
  | 0302 | Transporte ferroviario de pasajeros | F, B |
  | 0401 | Ventas a no domiciliados que no califican como exportación | F, B |
  | **0501** | **Compra interna** | **LC** |
  | **0502** | **Anticipos** | **LC** |
  | **0503** | **Compra de oro** | **LC** |
  | 1001 | Operación sujeta a detracción | F, B |
  | 1002 | Detracción – recursos hidrobiológicos | F, B |
  | 1003 | Detracción – transporte de pasajeros | F, B |
  | 1004 | Detracción – transporte de carga | F, B |
  | 2001 | Operación sujeta a percepción | F, B |
  | 2002 | Retención de renta de segunda categoría | F |
  | 2100 | Créditos a empresas | F, B |
  | 2101 | Créditos de consumo revolvente | F, B |
  | 2102 | Créditos de consumo no revolvente | F, B |
  | 2103 | Otras operaciones no gravadas – sistema financiero y cooperativas | F, B |
  | 2104 | Otras operaciones no gravadas – sistema de seguros | F, B |
  | 2105 | Comprobante emitido por AFP | B |
  | 2106 | Venta nacional a turistas – Tax Free | F |

- **52 Leyendas** (TODOS, f.571-585):

  | Código | Descripción |
  |---|---|
  | 1000 | Monto en letras |
  | 1002 | "TRANSFERENCIA GRATUITA DE UN BIEN Y/O SERVICIO PRESTADO GRATUITAMENTE" |
  | 2000 | "COMPROBANTE DE PERCEPCIÓN" |
  | 2001 | "BIENES TRANSFERIDOS EN LA AMAZONÍA REGIÓN SELVA PARA SER CONSUMIDOS EN LA MISMA" |
  | 2002 | "SERVICIOS PRESTADOS EN LA AMAZONÍA REGIÓN SELVA PARA SER CONSUMIDOS EN LA MISMA" |
  | 2003 | "CONTRATOS DE CONSTRUCCIÓN EJECUTADOS EN LA AMAZONÍA REGIÓN SELVA" |
  | 2004 | "Agencia de Viaje – Paquete turístico" |
  | 2005 | "Venta realizada por emisor itinerante" |
  | 2006 | "Operación sujeta a detracción" |
  | 2007 | "Operación sujeta al IVAP" |
  | 2008 | "VENTA EXONERADA DEL IGV-ISC-IPM. PROHIBIDA LA VENTA FUERA DE LA ZONA COMERCIAL DE TACNA" |
  | 2009 | "PRIMERA VENTA DE MERCANCÍA IDENTIFICABLE ENTRE USUARIOS DE LA ZONA COMERCIAL" |
  | 2010 | Restitución simplificado de derechos arancelarios |
  | 2011 | "EXPORTACIÓN DE SERVICIOS – DECRETO LEGISLATIVO Nº 919" |
  | 2012 | Observaciones relacionadas con el traslado de mercaderías |

- **53 Cargos, descuentos y otras deducciones** (TODOS, f.590-611):

  | Código | Descripción | Nivel |
  |---|---|---|
  | 00 | Descuentos que afectan la base imponible del IGV/IVAP | Ítem |
  | 01 | Descuentos que no afectan la base imponible del IGV/IVAP | Ítem |
  | 02 | Descuentos globales que afectan la base imponible del IGV/IVAP | Global |
  | 03 | Descuentos globales que no afectan la base imponible del IGV/IVAP | Global |
  | 04 | Descuentos globales por anticipos gravados que afectan la base imponible del IGV/IVAP | Global |
  | 05 | Descuentos globales por anticipos exonerados | Global |
  | 06 | Descuentos globales por anticipos inafectos | Global |
  | 07 | Factor de compensación – DU 010-2004 | Ítem |
  | 20 | Anticipo de ISC | Global |
  | 45 | FISE | Global |
  | 46 | Recargo al consumo y/o propinas | Global |
  | 47 | Cargos que afectan la base imponible del IGV/IVAP | Ítem |
  | 48 | Cargos que no afectan la base imponible del IGV/IVAP | Ítem |
  | 49 | Cargos globales que afectan la base imponible del IGV/IVAP | Global |
  | 50 | Cargos globales que no afectan la base imponible del IGV/IVAP | Global |
  | 51 | Percepción venta interna | Global |
  | 52 | Percepción a la adquisición de combustible | Global |
  | 53 | Percepción realizada al agente de percepción con tasa especial | Global |
  | 54 | Factor de aportación – DU 010-2004 | Ítem |
  | **61** | **Retención de renta por anticipos** | Global |
  | 62 | Retención del IGV | Global |
  | 63 | Retención de renta de segunda categoría | Global |

- **54 Bienes y servicios sujetos a detracción** (f.617-655):
  001 Azúcar y melaza de caña; 002 Arroz; 003 Alcohol etílico; 004 Recursos hidrobiológicos; 005 Maíz amarillo duro; 007 Caña de azúcar; 008 Madera; 009 Arena y piedra; 010 Residuos, subproductos y desechos; 011 Bienes gravados con el IGV o renuncia a la exoneración; 012 Intermediación laboral y tercerización; 013 Animales vivos; 014 Carnes y despojos comestibles; 015 Abonos, cueros y pieles; 016 Aceite de pescado; 017 Harina, polvo y pellets de pescado; 019 Arrendamiento de bienes muebles; 020 Mantenimiento y reparación de bienes muebles; 021 Movimiento de carga; 022 Otros servicios empresariales; 023 Leche; 024 Comisión mercantil; 025 Fabricación de bienes por encargo; 026 Servicio de transporte de personas; 027 Servicio de transporte de carga; 028 Transporte de pasajeros; 030 Contratos de construcción; 031 Oro gravado con el IGV; 032 Páprika y otros frutos de los géneros capsicum o pimienta; 034 Minerales metálicos no auríferos; 035 Bienes exonerados del IGV; 036 Oro y demás minerales metálicos exonerados del IGV; 037 Demás servicios gravados con el IGV; 039 Minerales no metálicos; 040 Bien inmueble gravado con IGV; 041 Plomo; 044 Servicio de beneficio de minerales metálicos gravado con el IGV; 045 Minerales de oro y sus concentrados gravados con el IGV; 099 Ley 30737.
  Este catálogo cambió en abril de 2025 (Control_de_Cambios f.504, f.506).
- **59 Medios de pago** (TODOS, f.805-826):

  | Código | Descripción |
  |---|---|
  | 001 | Depósito en cuenta |
  | 002 | Giro |
  | 003 | Transferencia de fondos |
  | 004 | Orden de pago |
  | 005 | Tarjeta de débito |
  | 006 | Tarjeta de crédito emitida en el país por una empresa del sistema financiero |
  | 007 | Cheques con cláusula "NO NEGOCIABLE", "INTRANSFERIBLES", "NO A LA ORDEN" u otra equivalente (inciso g, art. 5 de la ley) |
  | 008 | Efectivo, por operaciones en las que no existe obligación de utilizar medio de pago |
  | 009 | Efectivo, en los demás casos |
  | 010 | Medios de pago usados en comercio exterior |
  | 011 | Documentos emitidos por EDPYMES y cooperativas de ahorro y crédito no autorizadas a captar depósitos |
  | 012 | Tarjeta de crédito emitida en el país o en el exterior por una empresa no perteneciente al sistema financiero |
  | 013 | Tarjetas de crédito emitidas en el exterior por empresas bancarias o financieras no domiciliadas |
  | 101 | Transferencias – Comercio exterior |
  | 102 | Cheques bancarios – Comercio exterior |
  | 103 | Orden de pago simple – Comercio exterior |
  | 104 | Orden de pago documentario – Comercio exterior |
  | 105 | Remesa simple – Comercio exterior |
  | 106 | Remesa documentaria – Comercio exterior |
  | 107 | Carta de crédito simple – Comercio exterior |
  | 108 | Carta de crédito documentario – Comercio exterior |
  | 999 | Otros medios de pago |

- **60 Tipo de dirección** (TODOS, f.832-836): **01 Punto de venta; 02 Producción; 03 Extracción; 04 Explotación; 05 Otros.**
- Otros relevantes: 11 Tipo de valor de venta del RC (01 Gravado, 02 Exonerado, 03 Inafecto, 04 Exportación, 05 Gratuitas [desactivado en 2026], 06 Gratuitas-Gravada, 07 Gratuitas-Exoneradas, 08 Gratuitas-Inafectas, 09 Gratuitas-Exportación; f.160-168); 19 Estado del ítem (1 Adicionar, 2 Modificar, 3 Anulado); 22 Percepción (01 2 %, 02 1 %, 03 0.5 %); 23 Retención (01 3 %, 02 6 %); 55 Concepto del ítem (incluye 6000-6006 de oro; f.660-774).

---

## 8. Cambios recientes (RV/Control_de_Cambios, últimos ~2 años)

Las columnas A (versión) y H (vigencia) vienen en número serial de Excel; aquí van convertidas.

| Versión | Vigencia | Cambio | Afecta | Fila |
|---|---|---|---|---|
| 05/02/2024 | 29/02/2024 | **ERR-1086**: rechazo a emisores SSCO; nuevo padrón 02 SSCO | FAC, NC, ND, **LC**, DAE | f.494-495 |
| 05/02/2024 | 29/02/2024 | ERR-2410 (PriceTypeCode obligatorio si hay PriceAmount) | NC, ND | f.496 |
| 05/02/2024 | 29/02/2024 | ERR-2369: sin ceros a la izquierda | FAC, BOL, NC, ND, **LC** | f.497 |
| 05/02/2024 | 29/02/2024 | ERR-2228: mínimo 3 caracteres | RC, RA, RR | f.498 |
| 11/10/2024 | 01/11/2024 | 2108/1079/2600: plazo "según normativa vigente"; se retira el parámetro 004 | FAC, BOL, NC, ND, DAE, Ret., Perc. (**no LC**) | f.500 |
| 11/10/2024 | 11/11/2024 | **OBS-4443**: RC fuera de plazo | RC | f.501 |
| 11/10/2024 y 27/11/2024 | — | OBS-2853 pasa a ERR-2853 y luego se revierte | CDR-OSE | f.502-503 |
| 27/01/2025 | 31/01/2026 | Se eliminan OBS-4332/4337 y se reemplazan por OBS-3496 | FAC, BOL, NC, ND | f.508-509 |
| 02/04/2025 y 21/04/2025 | 01/04/2025 | Altas y bajas en los catálogos 54 y 62 | FAC, BOL, ND | f.504-507 |
| 09/02/2026 | 13/02/2026 | **Tasa del IGV de 10 % a 10.5 %** (MYPE, padrón 01) en OBS-4019/4290/4310/4439 y ERR-3279/3291; ERR-3462: rechazos recibidos del 13 al 20/02/2026 | RC, FAC, BOL, NC, ND | f.510-514 |
| 24/04/2026 | **01/08/2026** | Cat. 01: se agrega 25 (y cambia la descripción del 56); cat. 11: 06-09 y se desactiva 05; cat. 09 código 03; cat. 10 código 13; cat. 51 descripción de 0101; cat. 25 → 25.1/25.2/25.3 | Catálogos | f.515-520 |
| 24/04/2026 | 01/08/2026 | **RS N° 048-2026/SUNAT**: la baja admite 25, 28 y 56 | RA | f.521-522 |
| 24/04/2026 | 01/08/2026 | **ERR-3506/3496: Código de producto SUNAT obligatorio** | **LC** | f.529 |
| 24/04/2026 | 01/08/2026 | ERR-3496 reemplaza a las OBS 4332/4337 | FAC, BOL, NC, ND | f.526 |
| 24/04/2026 | 01/08/2026 | NC: ERR-3261 (una sola referencia); ERR-3286 en boletas; **ERR-3503** (montos no mayores al original) | NC | f.530-534 |
| 24/04/2026 | 01/08/2026 | ND: tipo 13 Penalidades solo inafectas (ERR-3507); ERR-2524 y ERR-3194 | ND | f.535-539 |
| 24/04/2026 | 01/08/2026 | RC: gratuitas desagregadas, tasa del IGV, IGV de gratuitas, nombre del adquirente (ERR-2022 queda como OBS hasta el 31/07/2026) | RC | f.540-544 |
| 24/04/2026 | 01/08/2026 | **ERR-2958: plazo de reversión de LC de 7 a 5 días** | **RR / LC** | f.545 |
| 24/04/2026 | 01/08/2026 | OBS→ERR en totales (4309, 4290, 4301, 4312…) → ERR-3508…3514 | **solo DAE-OP** | f.523 |
| 24/04/2026 | 01/08/2026 | **RS N.° 000048-2026/SUNAT**: nuevas hojas DAE-SEAE y DOAE-Atribución | DAE | f.547 |
| 24/04/2026 | 01/08/2026 | ERR-0151/0159: nombres de archivo para 25, 28 y 56 | General | f.528 |

Historial de la LC: RS 244-2019 (f.284-287, 20/12/2019); OBS-4345/4346 excluyen las gratuitas (f.308, 21/02/2020); hasta 999 líneas (f.352); OBS-4204 de contingencia (f.371).

> Nota: el control de cambios cita la **RS 048-2026** y la **RS 000048-2026**. La "RS 000047-2026" que mencionaste **no aparece** en ningún documento de la carpeta (búsqueda de texto con resultado negativo).

---

## 9. Lo que la documentación disponible NO cubre (pedir al usuario)

1. **XSD UBL 2.1** (Invoice, CreditNote, DebitNote, **SelfBilledInvoice**, ApplicationResponse, CommonAggregate/Basic/Extension) y **XSD SUNAT 2.0** (SummaryDocuments, VoidedDocuments, Retention, Perception, SUNATAggregateComponents). La carpeta "04 UBL 2.1 y XSD" no tiene ningún `.xsd`.
2. **RS 244-2019/SUNAT** (Liquidación de compra electrónica) y sus anexos: la estructura de la LC ("Anexo 9-A" o equivalente), los requisitos mínimos, los plazos normativos y el tratamiento del IGV y del IR. La carpeta `01 Resoluciones` está vacía.
3. **RS 000047-2026** (no aparece en ningún documento), **RS 048-2026** y **RS 000048-2026** (solo citadas en el control de cambios), RS 097-2012 y su texto consolidado, RS 117-2017, RS 340-2017.
4. **Tasas de retención del IR en la LC** (la regla 4237 solo dice "tasas vigentes"), tasas vigentes del IGV e IVAP por fecha (parámetros 012/024), **valor de la UIT** (límite de 75 UIT) y "Plazos excepcionales".
5. **Valores del parámetro 004** (plazo máximo de envío por tipo de comprobante) y la normativa vigente de plazos para factura y boleta (las RV dicen "según normativa vigente").
6. **Guía XML de la Liquidación de Compra** (ejemplo XML oficial de `SelfBilledInvoice`).
7. **Endpoint y operación oficiales para enviar la LC** y si `getStatusCdr`/`billConsultService` aceptan el tipo 04. El MP de mayo 2021 no los trata.
8. **Guía de Comunicación de Baja** y **guía del Resumen de Reversiones** en PDF (solo tenemos las hojas RV y el Anexo II en formato texto/JSON, desactualizado para el 04).
9. **Anexo 1 del MP** (estructura del CDR `ApplicationResponse`), legible en las páginas 34-51 del PDF pero no analizado a fondo aquí; y el WSDL real de `billService`.
10. Guías actualizadas de Factura/Boleta/NC/ND. Las que hay son de 2017 y su XML de ejemplo contradice las RV vigentes (ProfileID/cat. 17, PartyTaxScheme).
11. Contenido de `05 Guías Portal SOL` (carpeta vacía).
