# 06 — Greenter por dentro: especificación para reimplementar (Fase 0)

> Alcance: SOLO la librería instalada en `C:\laragon\www\API-PRO\vendor\greenter\*` (más Twig, nelexa/zip, guzzle y bacon-qr-code en lo que afecta al resultado). El código propio de la app (`app/…`) lo cubre otro analista; aquí solo se menciona cuando condiciona cómo se usa la librería.
> Todas las rutas son relativas a `C:\laragon\www\API-PRO\vendor\` salvo que se indique otra cosa. Los números de línea corresponden a los archivos tal como están instalados.
> Verificación empírica: se ejecutaron en local (sin red) scripts PHP que usan este mismo `vendor/` para construir, firmar y verificar un XML y para capturar los sobres SOAP y las peticiones REST exactos (scripts en `…\scratchpad\exp\exp1.php … exp4.php`, salidas en `…\scratchpad\exp\out\`). Los fragmentos marcados como **[verificado]** salen de esa ejecución.

---

## 1. Versiones instaladas

Fuente: `vendor/composer/installed.json` (lo que de verdad está en disco) y `composer.lock`.

| Paquete | Versión instalada | Papel |
|---|---|---|
| greenter/lite | v5.2.0 | Fachadas `See` (SOAP) y `Api` (GRE REST), resolvers |
| greenter/core | v5.2.0 | Modelos (Invoice, Note, Despatch, Summary…), respuestas |
| greenter/xml | v5.2.0 | Builders Twig + plantillas `.xml.twig` |
| greenter/xmldsig | v5.0.3 | Firma: es un **fork embebido de robrichards/xmlseclibs** (namespace `Greenter\XMLSecLibs`). **No** existe `vendor/robrichards/` |
| greenter/ws | v5.2.0 | Cliente SOAP, WSDL locales, ZIP, lector de CDR, `GreSender` |
| greenter/gre-api | v1.0.2 | Cliente OpenAPI (Guzzle) del API REST de GRE |
| greenter/consulta-cpe | v1.1.0 | Cliente OpenAPI de “Consulta de validez de CPE” |
| greenter/report | v5.2.0 | Plantillas HTML del PDF + QR (la app NO lo usa, ver §8) |
| twig/twig | **v3.30.0** en `installed.json` / v3.23.0 en `composer.lock` | Motor de plantillas (vendor desincronizado con el lock; sin efecto en la salida XML) |
| nelexa/zip | 4.0.2 | ZIP (`PhpZip\ZipFile`) |
| guzzlehttp/guzzle | 7.15.5 | HTTP para GRE y consulta-cpe |
| bacon/bacon-qr-code | v3.0.3 | QR en greenter/report |
| dompdf/dompdf | v3.1.6 (vía barryvdh/laravel-dompdf v3.1.1) | PDF de la app (no es parte de Greenter) |

Extensiones PHP que exige Greenter: `ext-dom`, `ext-openssl`, `ext-soap`, `ext-zlib`, `ext-json`, `ext-curl`, `ext-mbstring`. PHP en el equipo: 8.3.30, `precision=14`, `serialize_precision=-1` (importa para cómo se imprimen los floats, §2.2).

---

## 2. Generación del XML (greenter/xml)

### 2.1 Mecánica general

* Selección de builder (`lite/src/Greenter/Factory/XmlBuilderResolver.php:243-259`): `Greenter\Xml\Builder\{NombreCortoDeClase}Builder`; excepción: `Reversion` → `VoidedBuilder` (misma plantilla `voided.xml.twig`).
* Selección de plantilla:
  * `InvoiceBuilder` (`xml/src/Xml/Builder/InvoiceBuilder.php:43-50`): `'invoice'.$invoice->getUblVersion().'.xml.twig'`. **Ojo: `BaseSale::$ublVersion` vale `'2.0'` por defecto** (`core/src/Core/Model/Sale/BaseSale.php:26`); hay que llamar `setUblVersion('2.1')` o se usa la plantilla 2.0 (obsoleta). La boleta (03) usa la misma clase `Invoice` y la misma plantilla.
  * `NoteBuilder` (`NoteBuilder.php:41-49`): `tipoDoc === '07'` → `notacr{ubl}.xml.twig`; cualquier otro → `notadb{ubl}.xml.twig`.
  * `DespatchBuilder` (`DespatchBuilder.php:29-36`): `getVersion() === '2022'` → `despatch2022.xml.twig`; si no → `despatch.xml.twig` (legado, CustomizationID 1.0).
  * `SummaryBuilder`→`summary.xml.twig`, `VoidedBuilder`→`voided.xml.twig`, `RetentionBuilder`→`retention.xml.twig`, `PerceptionBuilder`→`perception.xml.twig`.
* `TwigBuilder` (`xml/src/Xml/Builder/TwigBuilder.php`):
  * Loader de archivos sobre `Templates/` (l.57); contexto único `['doc' => $document]` (l.50-52).
  * Zona horaria de Twig fijada en **`America/Lima`** (l.66-72, `TimeZonePe::DEFAULT` en `core/src/Core/Model/TimeZonePe.php:12`). Consecuencia: `{{ fecha|date('Y-m-d') }}` y `|date('H:i:s')` **convierten** el `DateTime` a hora de Lima antes de formatear. **[verificado]** `2024-05-10 13:45:00 UTC` → `<cbc:IssueDate>2024-05-10</cbc:IssueDate><cbc:IssueTime>08:45:00</cbc:IssueTime>`; y un `new DateTime('2024-05-10')` creado en UTC sale como `2024-05-09`. Los IDs `RA-/RC-/RR-YYYYMMDD-n` también se calculan en hora Lima (`Summary.php:184-195`, `Voided.php:159-170`, `Reversion.php:23-33` vía `TimezonePeTrait::getDateWithTimezone`).
  * Filtros propios (l.77-83 → `xml/src/Xml/Filter/FormatFilter.php`):
    * `n_format(dec=2)` = `number_format($n, $dec, '.', '')` → punto decimal, sin separador de miles, siempre `dec` decimales, redondeo PHP HALF_UP con “pre-redondeo” (ver §9). `null` → `"0.00"`.
    * `n_format_limit(dec)` (l.16-22): convierte el número a string con la conversión nativa de PHP; si tiene **más** de `dec` decimales o contiene `"E"` → aplica `n_format(dec)`; si no, **imprime el valor tal cual** (sin rellenar ceros). Ej.: `50.0` → `50`; `59.0000000001` → `59.0000000001`; `12.123456789012` (12 dec, límite 10) → `12.1234567890`.
  * Función `getTributoAfect(tipAfeIgv)` (solo Invoice/Note) → `xml/src/Xml/Filter/TributoFunction.php:42-61`:

    | tipAfeIgv (int) | id | name | code (TaxTypeCode) |
    |---|---|---|---|
    | 10 | 1000 | IGV | VAT |
    | 17 | 1016 | IVAP | VAT |
    | 20 | 9997 | EXO | VAT |
    | 30 | 9998 | INA | FRE |
    | 40 | 9995 | EXP | FRE |
    | cualquier otro (11-16, 21, 31-37…) | 9996 | GRA | FRE |

    (Tabla completa de tributos en l.17-26; 2000/ISC/EXC y 9999/OTROS/OTH no se usan por afectación).
* Todas las plantillas van envueltas en `{% apply spaceless %}` → `trim(preg_replace('/>\s+</', '><', $xml))` (`twig/twig/src/Extension/CoreExtension.php:1244-1247`). Resultado: **todo el XML en una sola línea**, sin espacios entre etiquetas; el espacio *dentro* de texto se conserva. La salida sin firmar empieza `<?xml version="1.0" encoding="utf-8"?><Invoice …` (sin salto de línea) **[verificado]**.
* **Autoescape**: Twig por defecto usa `autoescape => 'html'` (`twig/src/Environment.php:123`), pero `See` y `Api` lo desactivan: `private $options = ['autoescape' => false]` (`lite/src/Greenter/See.php`, propiedad `$options`; `lite/src/Greenter/Api.php` idem) y la app también construye con `['autoescape' => false, 'cache' => …]` (`app/Services/Greenter/GreenterService.php:619`). Por tanto **en API-PRO los valores se imprimen crudos, sin escapar**:
  * dentro de `<![CDATA[ … ]]>`: correcto para `& < >` (rompe solo si el valor contiene `]]>`);
  * fuera de CDATA (p. ej. `cbc:Description` del motivo de nota, `cbc:Line` de las direcciones de la GRE, `cbc:FirstName`, `cbc:Name` de puerto, `cbc:Note` sin raw…): un `&` o `<` produce XML mal formado y la firma fallaría (`DOMDocument::loadXML` devuelve false).
  * Si alguien usara los builders con opciones por defecto (autoescape html), los valores *sin* `|raw` se escaparían con `htmlspecialchars(ENT_QUOTES|ENT_SUBSTITUTE)` (`&`→`&amp;`, `<`→`&lt;`, `>`→`&gt;`, `"`→`&quot;`, `'`→`&#039;`) **incluso dentro de CDATA** (p. ej. leyendas, `observacion`, `proveedor.rznSocial` de retención) — comportamiento a NO replicar.
  * **Recomendación para la reimplementación**: escapar XML siempre fuera de CDATA (`& < >` y `"` en atributos). Para texto “normal” el resultado canónico (C14N) es idéntico al de Greenter, y evita el fallo. Mantener CDATA exactamente en los mismos elementos que Greenter si se busca igualdad byte a byte.
  * Detalle de caché de Twig: el nombre de clase compilada no incluye la opción autoescape; en un mismo proceso PHP, el primer `Environment` que compila una plantilla fija su modo de escape para los demás (observado en el experimento). Irrelevante para otros lenguajes.
* Verdad/falsedad en los `{% if %}` (semántica PHP/Twig, hay que replicarla tal cual):
  * `{% if x %}` es falso para `null`, `0`, `0.0`, `"0"`, `""`, `[]`, `false`. → un importe **0 se omite**.
  * `{% if x is not null %}` incluye el bloque aunque valga 0.
  * Esta diferencia es deliberada y cambia por plantilla (ver tablas).
* Impresión de números sin filtro (`{{ detail.cantidad }}`, `{{ detail.porcentajeIgv }}`, `{{ cargo.monto }}` en líneas, `{{ detr.percent }}`…): conversión nativa PHP float→string con `precision=14`: `2.0`→`2`, `18.0`→`18`, `0.1+0.2`→`0.3`, `1e15`→`1.0E+15`, `0.00001`→`1.0E-5` **[verificado]**. Si el modelo recibe strings, salen tal cual.
* Valores por defecto de modelos que afectan al XML: `Address::$codigoPais = 'PE'`, `Address::$codLocal = '0000'` (`core/src/Core/Model/Company/Address.php:26,58`); `SaleDetail::$factorIcbper = 0.30` (`SaleDetail.php:135`); `Summary::$moneda = 'PEN'` (`Summary.php:47`); `FormaPagoContado` → `tipo='Contado'`, `monto=null`, `moneda=null`; `FormaPagoCredito(monto, moneda)` → `tipo='Credito'` (`core/src/Core/Model/Sale/FormaPagos/*`).
* Nombre de archivo (`getName()`, sin extensión) — se usa para `.xml` dentro del zip y para el `.zip`:
  * Invoice/Note: `{RUC}-{tipoDoc}-{serie}-{correlativo}` (`BaseSale.php:855-866`).
  * Despatch: `{RUC}-{tipoDoc}-{serie}-{correlativo}` (`Despatch.php:398-408`; tipoDoc 09 o 31).
  * Retention: `{RUC}-20-{serie}-{correlativo}` (`Retention.php:311-321`); Perception: `{RUC}-40-…` (`Perception.php:311`).
  * Summary: `{RUC}-RC-{YYYYMMDD fecResumen en Lima}-{correlativo}`; Voided `{RUC}-RA-…`; Reversion `{RUC}-RR-…` (fecComunicacion en Lima). **[verificado]** `20123456789-RA-20240509-1`.

### 2.2 Elementos comunes a todas las plantillas

1. Declaración: `<?xml version="1.0" encoding="utf-8"?>` (minúsculas `utf-8`; se mantiene así tras firmar).
2. Raíz con todos los namespaces declarados en la raíz (ningún otro elemento declara namespaces). Prefijos fijos: `cac`, `cbc`, `ds`, `ext`, y `sac` en los documentos SUNAT-1. Namespace por defecto = el del tipo de documento.
3. Primer hijo siempre: `<ext:UBLExtensions><ext:UBLExtension><ext:ExtensionContent/></ext:UBLExtension></ext:UBLExtensions>` (un solo UBLExtension; el firmador mete ahí la firma).
4. Bloque `cac:Signature` idéntico en todas:
   ```xml
   <cac:Signature>
     <cbc:ID>SIGN{RUC}</cbc:ID>
     <cac:SignatoryParty>
       <cac:PartyIdentification><cbc:ID>{RUC}</cbc:ID></cac:PartyIdentification>
       <cac:PartyName><cbc:Name><![CDATA[{razonSocial}]]></cbc:Name></cac:PartyName>
     </cac:SignatoryParty>
     <cac:DigitalSignatureAttachment>
       <cac:ExternalReference><cbc:URI>#GREENTER-SIGN</cbc:URI></cac:ExternalReference>
     </cac:DigitalSignatureAttachment>
   </cac:Signature>
   ```
   Nota: el URI es `#GREENTER-SIGN` pero el `Id` real de la firma es `GreenterSign` (§3). No coinciden; SUNAT no lo valida. Para equivalencia, copiar literal.
5. Ningún atributo `listAgencyName/listName/listURI/schemeName/schemeAgencyName/unitCodeListID/schemeURI` en Invoice/Note/Summary/Voided/Retention/Perception (las plantillas 2.1 de Greenter son “mínimas”). Las únicas excepciones: en factura `Delivery/…/cbc:IdentificationCode listID="ISO 3166-1" listAgencyName="United Nations Economic Commission for Europe" listName="Country"`; y la GRE 2022, que sí lleva catálogos (§2.6).
6. Monedas: `currencyID="{{ doc.tipoMoneda }}"` en importes; **forzado `PEN`** en detracción, percepción (y en retención/percepción para los totales).

### 2.3 Factura / Boleta — `xml/src/Xml/Templates/invoice2.1.xml.twig` (582 líneas, copia literal en el **Anexo A**)

Raíz (l.3): `<Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2" xmlns:cac="…CommonAggregateComponents-2" xmlns:cbc="…CommonBasicComponents-2" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xmlns:ext="…CommonExtensionComponents-2">` (orden de declaración: default, cac, cbc, ds, ext).

Orden exacto de hijos de `Invoice` y condiciones:

| # | Elemento | Contenido / formato | Condición | Líneas |
|---|---|---|---|---|
| 1 | ext:UBLExtensions | ver §2.2 | siempre | 4-8 |
| 2 | cbc:UBLVersionID | `2.1` | siempre | 10 |
| 3 | cbc:CustomizationID | `2.0` | siempre | 11 |
| 4 | cbc:ID | `{serie}-{correlativo}` | siempre | 12 |
| 5 | cbc:IssueDate | `fechaEmision` Y-m-d (Lima) | siempre | 13 |
| 6 | cbc:IssueTime | `fechaEmision` H:i:s (Lima) | siempre | 14 |
| 7 | cbc:DueDate | `fecVencimiento` Y-m-d | `if fecVencimiento` | 15-17 |
| 8 | cbc:InvoiceTypeCode `listID="{tipoOperacion}"` | `tipoDoc` | siempre | 18 |
| 9 | cbc:Note `languageLocaleID="{code}"` + CDATA(value) | una por leyenda | `for legends` | 19-21 |
| 10 | cbc:Note + CDATA(observacion) | sin atributo | `if observacion` | 22-24 |
| 11 | cbc:DocumentCurrencyCode | tipoMoneda | siempre | 25 |
| 12 | cac:OrderReference/cbc:ID | compra | `if compra` | 26-30 |
| 13 | cac:DespatchDocumentReference (ID, DocumentTypeCode) | por guía | `for guias` | 31-38 |
| 14 | cac:AdditionalDocumentReference (ID, DocumentTypeCode) | por relDoc | `for relDocs` | 39-46 |
| 15 | cac:AdditionalDocumentReference (ID=nroDocRel, DocumentTypeCode=tipoDocRel, DocumentStatusCode=loop.index (1..n), IssuerParty/PartyIdentification/ID schemeID="6"=RUC) | por anticipo | `for anticipos` | 47-60 |
| 16 | cac:Signature | §2.2 | siempre | 61-76 |
| 17 | cac:AccountingSupplierParty/cac:Party | PartyIdentification/ID schemeID="6"; PartyName/Name CDATA(nombreComercial) **solo si nombreComercial**; PartyLegalEntity/RegistrationName CDATA + RegistrationAddress: ID(ubigueo), AddressTypeCode(codLocal), CitySubdivisionName(urbanizacion, si hay), CityName(provincia), CountrySubentity(departamento), District(distrito), AddressLine/Line CDATA(direccion), Country/IdentificationCode(codigoPais); Contact (Telephone?, ElectronicMail?) si email o teléfono | siempre | 77-118 |
| 18 | cac:AccountingCustomerParty/cac:Party | PartyIdentification/ID schemeID="{client.tipoDoc}"; PartyLegalEntity/RegistrationName CDATA; RegistrationAddress (ID si ubigueo, AddressLine/Line CDATA, Country/IdentificationCode) si `client.address`; Contact igual que arriba | siempre | 119-153 |
| 19 | cac:SellerSupplierParty | misma estructura que cliente | `if seller` | 154-190 |
| 20 | cac:Delivery/cac:DeliveryLocation/cac:Address | ID, CitySubdivisionName?, CityName, CountrySubentity, District, AddressLine/Line CDATA, Country/IdentificationCode con listID/listAgencyName/listName (ver §2.2.5) | `if direccionEntrega` | 191-212 |
| 21 | cac:PaymentMeans (ID=`Detraccion`, PaymentMeansCode=codMedioPago, PayeeFinancialAccount/ID=ctaBanco) + cac:PaymentTerms (ID=`Detraccion`, PaymentMeansID=codBienDetraccion, PaymentPercent=percent **sin formato**, Amount currencyID="PEN" n_format(mount)) | | `if detraccion` | 213-228 |
| 22 | cac:PaymentTerms (ID=`Percepcion`, Amount PEN = perception.mtoTotal) | | `if perception` | 229-234 |
| 23 | cac:PaymentTerms (ID=`FormaPago`, PaymentMeansID=tipo, Amount currencyID=moneda\|default(tipoMoneda) si `monto`) | | `if formaPago` | 235-243 |
| 24 | cac:PaymentTerms por cuota (ID=`FormaPago`, PaymentMeansID=`Cuota%03d` (Cuota001…), Amount, PaymentDueDate Y-m-d) | | `for cuotas` | 244-253 |
| 25 | cac:PrepaidPayment (ID=loop.index, PaidAmount) | | `for anticipos` | 254-261 |
| 26 | cac:AllowanceCharge cargos globales (ChargeIndicator `true`, AllowanceChargeReasonCode, MultiplierFactorNumeric n_format_limit(5), Amount, BaseAmount) | | `for cargos` | 262-272 |
| 27 | cac:AllowanceCharge descuentos globales (ChargeIndicator `false`, …; MultiplierFactorNumeric **solo si factor is not null**) | | `for descuentos` | 273-285 |
| 28 | cac:AllowanceCharge percepción (true, codReg, porcentaje n_format_limit(5), Amount PEN mto, BaseAmount PEN mtoBase) | | `if perception` | 286-295 |
| 29 | cac:TaxTotal | ver abajo | siempre | 296-414 |
| 30 | cac:LegalMonetaryTotal | ver abajo | siempre | 415-433 |
| 31 | cac:InvoiceLine × n | ver abajo | `for details` | 434-580 |

`cac:TaxTotal` (cabecera): `TaxAmount`=n_format(totalImpuestos) y luego `TaxSubtotal` en este orden fijo:

| Orden | Tributo (ID/Name/TaxTypeCode) | TaxableAmount | TaxAmount | Condición |
|---|---|---|---|---|
| 1 | 2000/ISC/EXC | mtoBaseIsc | mtoISC | `if mtoISC` (truthy) |
| 2 | 1000/IGV/VAT | mtoOperGravadas | mtoIGV | `mtoOperGravadas is not null` |
| 3 | 9998/INA/FRE | mtoOperInafectas | literal `0` | `is not null` |
| 4 | 9997/EXO/VAT | mtoOperExoneradas | literal `0` | `is not null` |
| 5 | 9996/GRA/FRE | mtoOperGratuitas | mtoIGVGratuitas | `is not null` |
| 6 | 9995/EXP/FRE | mtoOperExportacion | literal `0` | `is not null` |
| 7 | 1016/IVAP/VAT | mtoBaseIvap | mtoIvap | `if mtoIvap` |
| 8 | 9999/OTROS/OTH | mtoBaseOth | mtoOtrosTributos | `if mtoOtrosTributos` |
| 9 | 7152/ICBPER/OTH | (sin TaxableAmount) | icbper | `if icbper` |

Cada TaxSubtotal de cabecera: `TaxableAmount`, `TaxAmount`, `TaxCategory/TaxScheme/(ID, Name, TaxTypeCode)` — sin `cbc:Percent` ni `TaxExemptionReasonCode` en cabecera. Ojo: el `TaxAmount` de INA/EXO/EXP es el literal `0` (no `0.00`).

`cac:LegalMonetaryTotal` (orden): `LineExtensionAmount`(valorVenta, siempre) → `TaxInclusiveAmount`(subTotal, si not null) → `AllowanceTotalAmount`(sumOtrosDescuentos, si not null) → `ChargeTotalAmount`(sumOtrosCargos, si not null) → `PrepaidAmount`(totalAnticipos, si not null) → `PayableRoundingAmount`(redondeo, si not null) → `PayableAmount`(mtoImpVenta, siempre). Todos n_format(2).

`cac:InvoiceLine` (orden):
1. `cbc:ID` = loop.index (1..n).
2. `cbc:InvoicedQuantity unitCode="{unidad}"` = cantidad **sin formato** (float nativo).
3. `cbc:LineExtensionAmount` = n_format(mtoValorVenta).
4. `cac:PricingReference/cac:AlternativeConditionPrice`: si `mtoValorGratuito` truthy → `PriceAmount` = n_format_limit(mtoValorGratuito,10) + `PriceTypeCode` `02`; si no → n_format_limit(mtoPrecioUnitario,10) + `01`. (Exactamente uno.)
5. `cac:AllowanceCharge` por cargo de línea (true, codTipo, factor n_format_limit(5), **Amount y BaseAmount sin formato**), luego por descuento de línea (false, …, factor siempre presente, Amount/BaseAmount sin formato).
6. `cac:TaxTotal`: `TaxAmount`=n_format(totalImpuestos); TaxSubtotal ISC si `isc` (TaxableAmount mtoBaseIsc, TaxAmount isc, TaxCategory: `Percent`=porcentajeIsc sin formato, `TierRange`=tipSisIsc, TaxScheme 2000/ISC/EXC); **siempre** TaxSubtotal IGV/afectación (TaxableAmount mtoBaseIgv, TaxAmount igv, TaxCategory: `Percent`=porcentajeIgv sin formato, `TaxExemptionReasonCode`=tipAfeIgv, TaxScheme según `getTributoAfect`); OTROS si `otroTributo` (Percent=porcentajeOth, 9999/OTROS/OTH); ICBPER si `icbper` (TaxAmount n_format(icbper), `BaseUnitMeasure unitCode="NIU"` = cantidad, TaxCategory/`PerUnitAmount` n_format(factorIcbper), 7152/ICBPER/OTH).
7. `cac:Item`: `Description` CDATA(descripcion) → `SellersItemIdentification/ID` (si codProducto) → `StandardItemIdentification/ID` (si codProdGS1) → `CommodityClassification/ItemClassificationCode` (si codProdSunat) → por atributo `cac:AdditionalItemProperty` (Name, NameCode, Value si `is not null`, `UsabilityPeriod` con StartDate/EndDate/`DurationMeasure unitCode="DAY"` si alguno existe). En la plantilla aparece `<cac:AdditionalItemProperty >` (espacio antes de `>`): se conserva en el XML sin firmar y desaparece al re-serializar con DOM tras firmar; no afecta al digest.
8. `cac:Price/cbc:PriceAmount` = n_format_limit(mtoValorUnitario,10).

### 2.4 Nota de crédito — `notacr2.1.xml.twig` (414 l.) y Nota de débito — `notadb2.1.xml.twig` (401 l.)

Raíz: `<CreditNote xmlns="urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2" …cac cbc ds ext…>` / `<DebitNote xmlns="urn:oasis:names:specification:ubl:schema:xsd:DebitNote-2" …>`.

Orden (crédito, l.4-414):
UBLExtensions → `UBLVersionID 2.1` → `CustomizationID 2.0` → `ID` → `IssueDate` → `IssueTime` → `cbc:Note languageLocaleID` por leyenda (**no** hay DueDate, InvoiceTypeCode ni Note de observación) → `DocumentCurrencyCode` → **`cac:DiscrepancyResponse`** (`ReferenceID`=numDocfectado, `ResponseCode`=codMotivo, `Description`=desMotivo **sin CDATA**) → `cac:OrderReference` (si compra) → **`cac:BillingReference/cac:InvoiceDocumentReference`** (`ID`=numDocfectado, `DocumentTypeCode`=tipDocAfectado) → DespatchDocumentReference* → AdditionalDocumentReference* (relDocs) → `cac:Signature` → AccountingSupplierParty → AccountingCustomerParty → [solo NC] PaymentTerms FormaPago + cuotas (l.142-160, idéntico a factura) → `cac:TaxTotal` → total → líneas.

Diferencias relevantes respecto a factura (para no copiar a ciegas):
* No hay anticipos, seller, delivery, detracción, percepción, cargos/descuentos (cabecera ni línea).
* `cac:PartyName` del emisor **siempre** se emite (aunque `nombreComercial` sea vacío → `<cbc:Name><![CDATA[]]></cbc:Name>`) (notacr l.72-74).
* TaxSubtotal de cabecera usan **truthy** (`{% if doc.mtoOperGravadas %}` etc., l.176-240): un 0 se omite (en factura se incluye con `is not null`).
* Totales:
  * NC: `cac:LegalMonetaryTotal` = `ChargeTotalAmount`(si sumOtrosCargos not null) → `PayableRoundingAmount`(si redondeo not null) → `PayableAmount` (l.280-288). **Sin `LineExtensionAmount` ni `TaxInclusiveAmount`.**
  * ND: `cac:RequestedMonetaryTotal` = `LineExtensionAmount`(si valorVenta not null) → `TaxInclusiveAmount`(si subTotal not null) → `ChargeTotalAmount`? → `PayableRoundingAmount`? → `PayableAmount` (notadb l.261-275).
* Línea: `cac:CreditNoteLine`/`cbc:CreditedQuantity` o `cac:DebitNoteLine`/`cbc:DebitedQuantity`; ICBPER de línea usa `BaseUnitMeasure unitCode="{{ detail.unidad }}"` (en factura es fijo `NIU`); `AdditionalItemProperty/Value` con condición truthy (en factura `is not null`).
* ND no tiene bloque FormaPago/cuotas.

### 2.5 Resumen diario — `summary.xml.twig` (191 l., copia completa en Anexo B)

Raíz: `<SummaryDocuments xmlns="urn:sunat:names:specification:ubl:peru:schema:xsd:SummaryDocuments-1" xmlns:cac xmlns:cbc xmlns:ds xmlns:ext xmlns:sac="urn:sunat:names:specification:ubl:peru:schema:xsd:SunatAggregateComponents-1">`.
Cabecera: UBLExtensions → `UBLVersionID 2.0` → `CustomizationID 1.1` → `ID`=xmlId (`RC-YYYYMMDD-n`) → `ReferenceDate`=fecGeneracion → `IssueDate`=fecResumen → `cac:Signature` → `cac:AccountingSupplierParty` (`CustomerAssignedAccountID`=RUC, `AdditionalAccountID`=`6`, `Party/PartyLegalEntity/RegistrationName` CDATA).
Cada `sac:SummaryDocumentsLine`: `LineID` → `DocumentTypeCode` → `cbc:ID`=serieNro → `cac:AccountingCustomerParty` (`CustomerAssignedAccountID`=clienteNro, `AdditionalAccountID`=clienteTipo) → `cac:BillingReference/InvoiceDocumentReference` (si docReferencia) → `sac:SUNATPerceptionSummaryDocumentReference` (si percepcion: SystemCode, Percent n_format(tasa), TotalInvoiceAmount PEN, SUNATTotalCashed PEN, TaxableAmount PEN) → `cac:Status/cbc:ConditionCode`=estado → `sac:TotalAmount currencyID="{doc.moneda}"` → `sac:BillingPayment` (PaidAmount + InstructionID): `01` gravadas (**is not null**), `02` exoneradas, `03` inafectas, `04` exportación, `05` gratuitas (truthy) → `cac:AllowanceCharge` (ChargeIndicator true + Amount) si mtoOtrosCargos → **un `cac:TaxTotal` por tributo**: IVAP (si mtoIvap) *o si no* IGV (siempre en el `else`, con `cbc:Percent` n_format(porcentajeIgv) si not null); luego ISC (si mtoISC), OTROS (si mtoOtrosTributos), ICBPER (si mtoIcbper). Cada TaxTotal: TaxAmount + TaxSubtotal/TaxAmount (mismo valor) + TaxCategory/[Percent]/TaxScheme. Moneda = `doc.moneda` (por defecto PEN).

### 2.6 Comunicación de baja y Reversión — `voided.xml.twig` (49 l., completa en Anexo B)

Raíz `<VoidedDocuments xmlns="urn:sunat:names:specification:ubl:peru:schema:xsd:VoidedDocuments-1" xmlns:cac xmlns:cbc xmlns:ext xmlns:sac xmlns:ds>` (ojo: aquí el orden de declaración es cac, cbc, ext, sac, ds — irrelevante tras C14N pero visible en bytes). `UBLVersionID 2.0`, `CustomizationID 1.0`, `ID`=xmlId (`RA-…` o `RR-…` para Reversion), `ReferenceDate`=fecGeneracion, `IssueDate`=fecComunicacion, Signature, AccountingSupplierParty (igual que resumen), y por detalle `sac:VoidedDocumentsLine`: `LineID`, `DocumentTypeCode`, `sac:DocumentSerialID`, `sac:DocumentNumberID`, `sac:VoidReasonDescription` CDATA. La reversión (retenciones/percepciones) usa exactamente esta plantilla con ID `RR-`.

### 2.7 GRE 2022 — `despatch2022.xml.twig` (249 l., completa en Anexo B)

Raíz `<DespatchAdvice xmlns="urn:oasis:names:specification:ubl:schema:xsd:DespatchAdvice-2" xmlns:ds xmlns:cac xmlns:cbc xmlns:ext>` (orden de declaración: default, ds, cac, cbc, ext). Indentación con tabs en la plantilla (desaparece con spaceless).

Orden:
1. UBLExtensions; `UBLVersionID 2.1`; `CustomizationID 2.0`; `ID`; `IssueDate`; `IssueTime`.
2. `cbc:DespatchAdviceTypeCode listAgencyName="PE:SUNAT" listName="Tipo de Documento" listURI="urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo01"` = tipoDoc.
3. `cbc:Note` CDATA(observacion) si hay.
4. Por `addDocs`: `cac:AdditionalDocumentReference` → `ID`=nro, `DocumentTypeCode listAgencyName="PE:SUNAT" listName="Documento relacionado al transporte" listURI="…catalogo61"`=tipo, `DocumentType`=tipoDesc, `IssuerParty/PartyIdentification/ID schemeID="6" schemeName="Documento de Identidad" schemeAgencyName="PE:SUNAT" schemeURI="…catalogo06"`=emisor (si emisor).
5. `cac:Signature`.
6. `cac:DespatchSupplierParty/Party`: PartyIdentification/ID (schemeID="6" + schemeName/schemeAgencyName/schemeURI catálogo06) + PartyLegalEntity/RegistrationName CDATA.
7. `cac:DeliveryCustomerParty` (destinatario; schemeID={tipoDoc} + atributos cat06).
8. `cac:BuyerCustomerParty` (si comprador), `cac:SellerSupplierParty` (si tercero) — misma forma.
9. `cac:Shipment`: `cbc:ID`=`SUNAT_Envio` → `HandlingCode listAgencyName="PE:SUNAT" listName="Motivo de traslado" listURI="…catalogo20"`=codTraslado → `HandlingInstructions`(desTraslado?) → `Information`(sustentoPeso?) → `GrossWeightMeasure unitCode="{undPesoTotal}"` = n_format(pesoTotal,**3**) → `NetWeightMeasure unitCode="KGM"` n_format(pesoItems,3) (si) → `TotalTransportHandlingUnitQuantity`(numBultos?) → `SpecialInstructions` por indicador → `cac:ShipmentStage` [`TransportModeCode listName="Modalidad de traslado" listAgencyName="PE:SUNAT" listURI="…catalogo18"` (ojo: orden de atributos distinto al resto), `TransitPeriod/StartDate`(fecTraslado?), `CarrierParty` (si transportista: ID schemeID={tipoDoc} sin atributos de catálogo, RegistrationName CDATA, `CompanyID`=nroMtc), `DriverPerson`* (ID schemeID + cat06 attrs, FirstName, FamilyName, JobTitle=tipo, IdentityDocumentReference/ID=licencia)] → `cac:Delivery` [`DeliveryAddress`: `ID schemeAgencyName="PE:INEI" schemeName="Ubigeos"`, `AddressTypeCode listID="{ruc}"`=codLocal (si codLocal), `AddressLine/Line` **sin CDATA**; `cac:Despatch/cac:DespatchAddress` igual con partida] → `cac:TransportHandlingUnit/cac:Package` por contenedor (ID=loop.index, TraceID) → `cac:TransportHandlingUnit/cac:TransportEquipment` si vehiculo (ID=placa, `ApplicableTransportMeans/RegistrationNationalityID`=nroCirculacion?, `AttachedTransportEquipment`* secundarios con su ID/ApplicableTransportMeans?/`ShipmentDocumentReference/ID schemeID="{codEmisor}" schemeName="Entidad Autorizadora" schemeAgencyName="PE:SUNAT"`?, y el ShipmentDocumentReference del principal al final) → `cac:FirstArrivalPortLocation` (puerto: `ID schemeAgencyName="PE:SUNAT" schemeName="Puertos" schemeURI="…catalogo63"`, `LocationTypeCode 1`, `Name`; o aeropuerto: `schemeName="Aeropuertos"`, `catalogo64`, `LocationTypeCode 2`).
10. `cac:DespatchLine`* : `ID`=loop.index, `DeliveredQuantity unitCode` = n_format_limit(cantidad,10), `OrderLineReference/LineID`=loop.index, `Item` (Description CDATA, `SellersItemIdentification/ID`=codigo **siempre**, `CommodityClassification/ItemClassificationCode listID="UNSPSC" listAgencyName="GS1 US" listName="Item Classification"` si codProdSunat, `AdditionalItemProperty`* Name/NameCode/Value?).

La app post-procesa este XML para GRE transportista (31) y fecha de entrega (ver `app/Services/Greenter/GreenterService.php:300-320`) — fuera de este informe.

Plantilla legado `despatch.xml.twig` (151 l.): `UBLVersionID 2.1`, `CustomizationID 1.0`; solo si `version !== '2022'`.

### 2.8 Retención — `retention.xml.twig` (99 l.) y Percepción — `perception.xml.twig` (99 l.) (completas en Anexo B)

Raíz `<Retention xmlns="urn:sunat:names:specification:ubl:peru:schema:xsd:Retention-1" …cac cbc ds ext sac>` / `<Perception xmlns="…:Perception-1" …>`. `UBLVersionID 2.0`, `CustomizationID 1.0`.
**Orden atípico**: `cac:Signature` va **antes** de `cbc:ID` (l.12-27 y luego 28): UBLExtensions → UBLVersionID → CustomizationID → Signature → `ID` → `IssueDate` → `IssueTime` → `cac:AgentParty` (PartyIdentification/ID schemeID="6", PartyName/Name CDATA(nombreComercial) siempre, `cac:PostalAddress`: ID=ubigueo, `StreetName` CDATA(direccion), **`CityName`=departamento**, **`CountrySubentity`=provincia** (invertido respecto a la factura), District, Country/IdentificationCode; PartyLegalEntity/RegistrationName) → `cac:ReceiverParty` (ID schemeID={proveedor.tipoDoc}, RegistrationName CDATA — en retención **sin `|raw`**, en percepción con raw) → `sac:SUNATRetentionSystemCode`/`sac:SUNATPerceptionSystemCode`=regimen → `…Percent` n_format(tasa) → `cbc:Note` CDATA(observacion) si hay → `cbc:TotalInvoiceAmount currencyID="PEN"` (impRetenido / impPercibido) → `sac:SUNATTotalPaid` (impPagado) / `sac:SUNATTotalCashed` (impCobrado) PEN → por detalle `sac:SUNATRetentionDocumentReference` / `sac:SUNATPerceptionDocumentReference`: `ID schemeID="{tipoDoc}"`=numDoc, `IssueDate`, `TotalInvoiceAmount currencyID="{det.moneda}"`=impTotal, `cac:Payment`* (ID=loop.index, PaidAmount currencyID={moneda}, PaidDate) [pagos / cobros], y si (`impRetenido and impPagar and fechaRetencion`) / (`impPercibido and impCobrar and fechaPercepcion`) — los tres truthy — `sac:SUNATRetentionInformation` (SUNATRetentionAmount PEN, SUNATRetentionDate, SUNATNetTotalPaid PEN, `cac:ExchangeRate` si tipoCambio: SourceCurrencyCode=monedaRef, TargetCurrencyCode=monedaObj, `CalculationRate` n_format(factor,**6**), Date) / `sac:SUNATPerceptionInformation` (SUNATPerceptionAmount, SUNATPerceptionDate, SUNATNetTotalCashed, ExchangeRate).

---

## 3. Firma (greenter/xmldsig)

Clases: `xmldsig/src/Sunat/SignedXml.php` (fachada) → `xmldsig/src/XMLSecurityDSig.php`, `xmldsig/src/XMLSecurityKey.php` (fork de xmlseclibs 3.x).

### 3.1 Certificado / clave
* `SignedXml::setCertificate($pem)` (l.92-96) guarda el MISMO string como clave privada y pública. Se espera un PEM que contenga **la clave privada y el certificado** concatenados (`X509Certificate::export(X509ContentType::PEM)` devuelve `cert . pkey`, `xmldsig/src/Certificate/X509Certificate.php:150-160`; el PFX se abre con `openssl_pkcs12_read`, l.167-176). La clave se carga con `openssl_get_privatekey($pem, passphrase=null)` (`XMLSecurityKey.php:276-326`) — la clave PEM debe estar **sin contraseña**.
* Solo se usa `cert` del PKCS#12; `extracerts` (cadena) se ignoran.

### 3.2 Algoritmos (fijos por defecto, la app no los cambia)
* `SignatureMethod`: `http://www.w3.org/2000/09/xmldsig#rsa-sha1` (`SignedXml.php:39`); firma `openssl_sign(..., OPENSSL_ALGO_SHA1)` = RSASSA-PKCS1-v1_5 + SHA-1 (`XMLSecurityKey.php:455-466`).
* `DigestMethod`: `http://www.w3.org/2000/09/xmldsig#sha1` (`SignedXml.php:48`).
* `CanonicalizationMethod`: `http://www.w3.org/TR/2001/REC-xml-c14n-20010315` = **C14N 1.0 inclusiva, sin comentarios** (`SignedXml.php:57`).
* Transform única: `http://www.w3.org/2000/09/xmldsig#enveloped-signature` (`SignedXml.php:18,143`). No se añade transform C14N explícita.
* `Reference URI=""` (opción `force_uri => true`, `SignedXml.php:143`; `XMLSecurityDSig.php:589-600`). Sin `Id` en Reference.

### 3.3 Algoritmo exacto (`SignedXml::sign`, l.125-150)
1. `DOMDocument::loadXML($xml)` sin flags (conserva espacios en blanco; `SignedXml.php:289-295`).
2. Se crea el nodo plantilla (`XMLSecurityDSig.php:31,66-80`): `<ds:Signature Id="GreenterSign" xmlns:ds="http://www.w3.org/2000/09/xmldsig#"><ds:SignedInfo><ds:SignatureMethod/></ds:SignedInfo></ds:Signature>` (el `Id` es **`GreenterSign`**; prefijo `ds`).
3. `setCanonicalMethod(C14N)` inserta `<ds:CanonicalizationMethod Algorithm="…c14n-20010315"/>` como primer hijo de SignedInfo (`XMLSecurityDSig.php:176-202`; hay un bug en la XPath `'./secdsig'.'CanonicalizationMethod'` sin `:` que hace que siempre lo cree — inocuo).
4. `addReference(doc, SHA1, [enveloped], force_uri)` (`XMLSecurityDSig.php:564-646`): crea `ds:Reference URI=""` → `ds:Transforms/ds:Transform Algorithm=enveloped` → calcula el digest **del documento completo ANTES de insertar la firma**: `processTransforms` (l.334-417) no encuentra transform C14N, así que usa C14N inclusiva por defecto sobre el `DOMDocument` (`canonicalizeData`, l.210-246 → `$doc->C14N(false,false)`), luego `base64(sha1(c14n))` (l.280-310) → añade `ds:DigestMethod` y `ds:DigestValue`. Como la firma aún no está en el documento, esto equivale a aplicar enveloped-signature; el C14N contiene `<ext:ExtensionContent></ext:ExtensionContent>` vacío.
5. `sign($key, $nodoDestino)` (`XMLSecurityDSig.php:780-806`): el destino es el primer `ext:ExtensionContent` (namespace `…CommonExtensionComponents-2`) cuyo `nodeValue` recortado está vacío; si no hay, la raíz (`SignedXml.php:262-283`). Se importa la firma como **último hijo** del destino (`insertSignature`, l.825-841), se pone `SignatureMethod/@Algorithm`, se canonicaliza `ds:SignedInfo` **ya dentro del documento** con C14N inclusiva y se firma (RSA-SHA1); se inserta `ds:SignatureValue` (base64 en una sola línea) justo después de SignedInfo.
6. `add509Cert($pem)` (`XMLSecurityDSig.php:1024-1030` → `staticAdd509Cert`, l.905-1022): crea `ds:KeyInfo` (al final de Signature) → `ds:X509Data` → un `ds:X509Certificate` por cada bloque `-----BEGIN CERTIFICATE-----` del PEM (`staticGet509XCerts`, l.868-898: concatena las líneas base64 recortadas, sin saltos de línea). Sin X509SubjectName/IssuerSerial.
7. `saveXML()` (`SignedXml.php:71`).

### 3.4 Resultado **[verificado]** (firma válida según `verifyXml`)
```xml
<?xml version="1.0" encoding="utf-8"?>
<Invoice xmlns="…Invoice-2" xmlns:cac="…" xmlns:cbc="…" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xmlns:ext="…"><ext:UBLExtensions><ext:UBLExtension><ext:ExtensionContent><ds:Signature Id="GreenterSign"><ds:SignedInfo><ds:CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/><ds:SignatureMethod Algorithm="http://www.w3.org/2000/09/xmldsig#rsa-sha1"/><ds:Reference URI=""><ds:Transforms><ds:Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature"/></ds:Transforms><ds:DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1"/><ds:DigestValue>2nxO9nrUy9nr0CLyrMr46/W779M=</ds:DigestValue></ds:Reference></ds:SignedInfo><ds:SignatureValue>…base64…</ds:SignatureValue><ds:KeyInfo><ds:X509Data><ds:X509Certificate>…base64 DER…</ds:X509Certificate></ds:X509Data></ds:KeyInfo></ds:Signature></ext:ExtensionContent></ext:UBLExtension></ext:UBLExtensions><cbc:UBLVersionID>2.1</cbc:UBLVersionID>…</Invoice>
```
* Tras firmar: declaración XML + **`\n`** + raíz en una línea + **`\n` final** (`saveXML`). El `ds:Signature` NO repite `xmlns:ds` (libxml lo reconcilia con el de la raíz). Elementos vacíos de la firma serializados como `<…/>`. Las secciones CDATA se conservan.
* El SignedInfo que realmente se firma (C14N inclusiva de un subárbol) **arrastra todas las declaraciones de namespace en ámbito**, ordenadas (default primero, luego por prefijo) **[verificado]**:
  ```xml
  <ds:SignedInfo xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2" xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2" xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xmlns:ext="urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2"><ds:CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"></ds:CanonicalizationMethod><ds:SignatureMethod Algorithm="http://www.w3.org/2000/09/xmldsig#rsa-sha1"></ds:SignatureMethod><ds:Reference URI=""><ds:Transforms><ds:Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature"></ds:Transform></ds:Transforms><ds:DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1"></ds:DigestMethod><ds:DigestValue>…</ds:DigestValue></ds:Reference></ds:SignedInfo>
  ```
  (en Summary/Voided/Retention/Perception se añade `xmlns:sac=…` al final).
* Digest = `base64(SHA1(C14N_inclusiva(documento_sin_firma)))`; el C14N omite la declaración XML, expande vacíos (`<a></a>`), convierte CDATA en texto escapado (`&amp; &lt; &gt;`, `\r`→`&#xD;`), ordena atributos y namespaces. **[verificado]** con `$doc->C14N()` independiente: mismo DigestValue.

### 3.5 Hash para PDF/QR
`report/src/Report/XmlUtils.php:29-100`: carga el XML firmado, recorre `ext:UBLExtensions/ext:UBLExtension` **del último al primero** y devuelve el primer `ext:ExtensionContent/ds:Signature/ds:SignedInfo/ds:Reference/ds:DigestValue` (string base64 de 28 caracteres, SHA-1). Es el “Resumen”/hash que greenter/report muestra (`params.system.hash`). **[verificado]** coincide con el DigestValue.

---

## 4. ZIP y SOAP (greenter/ws)

### 4.1 ZIP (`ws/src/Zip/ZipFly.php:27-43`)
* `compress($filename.'.xml', $xml)` con `PhpZip\ZipFile::addFromString(..., DEFLATED)` → `outputAsString()`. Un único entry, sin carpetas, nombre `{getName()}.xml`; el zip se llama `{getName()}.zip`.
* **[verificado]** cabecera: `PK\3\4`, version needed 20, made-by Unix 3.20, flags 0 (sin data descriptor, nombre no marcado UTF-8), método 8 (deflate), fecha/hora DOS actuales, atributos externos 0644 (`0x81A4<<16`), sin comentario.
* Descompresión del CDR (`BaseSunat::getXmlResponse`, `ws/src/Ws/Services/BaseSunat.php:196-218`): abre el zip, filtra entradas cuyo nombre termina en `.xml` (insensible a mayúsculas; ignora carpetas como `dummy/`), toma **la primera**. Si el zip está vacío/corrupto → `[]` → `''` → `DomCdrReader` lanza `XmlReaderException('XML del CDR no puede estar vacío.')` (`DomCdrReader.php:41-52`).

### 4.2 Cliente SOAP (`ws/src/Ws/Services/SoapClient.php`)
* Extiende `\SoapClient` de PHP con el **WSDL local** `ws/src/Resources/wsdl/billService.wsdl` (+ `billService.ns1.wsdl`, `billService.xsd2.xsd`) por defecto (`WsdlProvider::getBillPath()`), o `billConsultService.wsdl` (+ `.ns1.wsdl`, `.xsd`) para consulta de CDR (`WsdlProvider::getConsultPath()`).
* Contexto SSL: `verify_peer=false`, `verify_peer_name=false`, `allow_self_signed=true` (l.29-51) — no valida el certificado de SUNAT.
* `setService($url)` = `__setLocation` (el endpoint real sustituye al del WSDL). `setCredentials(user, pass)` agrega cabecera WS-Security. `See::setClaveSOL($ruc,$user,$pass)` usa usuario `RUC . usuarioSOL` (`See.php:115-118`).
* SOAP 1.1, document/literal. SOAPAction: `urn:sendBill`, `urn:sendSummary`, `urn:getStatus`, `urn:getStatusCdr`. Content-Type `text/xml; charset=utf-8`. Namespace de operación `http://service.sunat.gob.pe`; los hijos (`fileName`, `contentFile`, `ticket`, `rucComprobante`…) van **sin namespace** (esquema sin `elementFormDefault`). `contentFile` es `xs:base64Binary`: PHP SoapClient codifica el zip a base64 automáticamente; igualmente decodifica `applicationResponse`/`content` (en otros lenguajes hay que hacer base64 explícito).
* Sobre exacto **[verificado]** (sendBill; en una sola línea, aquí partido):
  ```xml
  <?xml version="1.0" encoding="UTF-8"?>
  <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns1="http://service.sunat.gob.pe" xmlns:ns2="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
   <SOAP-ENV:Header><ns2:Security><ns2:UsernameToken><ns2:Username>20123456789MODDATOS</ns2:Username><o:Password xmlns:o="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText">CLAVE</o:Password></ns2:UsernameToken></ns2:Security></SOAP-ENV:Header>
   <SOAP-ENV:Body><ns1:sendBill><fileName>20123456789-01-F001-1.zip</fileName><contentFile>UEsDBBQ…</contentFile></ns1:sendBill></SOAP-ENV:Body>
  </SOAP-ENV:Envelope>
  ```
  * `sendSummary`: igual con `<ns1:sendSummary><fileName>…RC-…zip</fileName><contentFile>…</contentFile></ns1:sendSummary>`.
  * `getStatus`: `<ns1:getStatus><ticket>…</ticket></ns1:getStatus>`.
  * `getStatusCdr` / `getStatus` (consulta): `<ns1:getStatusCdr><rucComprobante>…</rucComprobante><tipoComprobante>01</tipoComprobante><serieComprobante>F001</serieComprobante><numeroComprobante>1</numeroComprobante></ns1:getStatusCdr>` (numero como entero).
  * Cabecera WS-Security (`ws/src/Ws/Header/WSSESecurityHeader.php:19-41`): `Security` con `mustUnderstand=false` (no se emite atributo), sin `wsu:Timestamp`, sin Nonce. **El password se inserta como XML crudo sin escapar** (`XSD_ANYXML`, l.21-29): una clave SOL con `&` o `<` produce un sobre inválido — en la reimplementación escapar.
* Operaciones y uso:
  * `BillSender::send` (`BillSender.php:30-57`): sendBill → `applicationResponse` (bytes del zip CDR). Si vacío → `Error('CDR', 'CDR no encontrado. La respuesta del servicio de SUNAT no incluyó el CDR.')`. Si hay → `success=true` + CdrResponse **aunque el ResponseCode sea de rechazo** (2xxx-3xxx); el llamador decide con `CdrResponse::isAccepted()` = `code === 0 || code >= 4000` (`core/src/Core/Model/Response/CdrResponse.php:144-149`).
  * `SummarySender::send` (`SummarySender.php:29-49`): sendSummary → `ticket`. Se usa para Summary, Voided y Reversion (`WsSenderResolver.php`).
  * Factura, boleta, notas, retención, percepción y GRE por SOAP (legado) → BillSender.
  * `ExtService::getStatus($ticket)` (`ExtService.php:29-125`): `status.statusCode`: `98` → error "El procesamiento del comprobante aún no ha terminado" (pendiente); `0` o `99` → requiere `status.content` (zip CDR) o `Error('CDR','CDR no encontrado en código estado: 98 (Procesado).')`; luego `code = CDR.ResponseCode`; si `100 <= code <= 1999` → `success=false` + error con ese código. Con 99 y CDR 2xxx: `success=true` (decide `isAccepted`).
  * `ConsultCdrService` (`ConsultCdrService.php:31-90`): `getStatus` (respuesta `status`) y `getStatusCdr` (respuesta `statusCdr`), con `statusCode`, `statusMessage` y `content` opcional (zip CDR). Si hay content, el código pasa a ser el ResponseCode del CDR. Endpoint `FE_CONSULTA_CDR`.

### 4.3 Lectura del CDR (`ws/src/Ws/Reader/DomCdrReader.php` + `XmlReader.php`)
* Raíz registrada como prefijo `x` con el namespace de la raíz del CDR (`ApplicationResponse-2`); `cac`/`cbc` = UBL 2 (`UblNs.php`).
* XPaths (relativas a `/x:ApplicationResponse/`):
  * `id` = `cac:DocumentResponse/cac:Response/cbc:ReferenceID`
  * `code` = `cac:DocumentResponse/cac:Response/cbc:ResponseCode` (string; ej. `0`, `2335`, `4252`)
  * `description` = `cac:DocumentResponse/cac:Response/cbc:Description`
  * `notes[]` = todos los `/x:ApplicationResponse/cbc:Note` (textos, ej. `4252 - El dato ingresado…`)
  * `reference` = `cac:DocumentResponse/cac:DocumentReference/cbc:DocumentDescription`
  Se toma el primer nodo de cada XPath (`nodeValue`). **[verificado]** con un CDR sintético.

### 4.4 soap:Fault → código (`BaseSunat.php:93-131`)
* `code` = dígitos de `faultcode` (`preg_replace('/[^0-9]+/','',…)`); si queda vacío, dígitos de `faultstring`. Ej. **[verificado]** `soap-env:Client.0111` → `0111` (se conserva el cero inicial, es string); `soap-env:Server` + faultstring `2335` → `2335`.
* `message` = texto del `ErrorCodeProvider` si hay (See no trae uno por defecto → vacío) y si está vacío → `faultstring` + (`' | ' . json_encode(detail)` si hay `detail`). Ej.: `2335 | {"message":"El documento electronico ingresado ha sido alterado"}`.
* Errores de transporte (HTTP 401/500 sin Fault, timeouts) también llegan como `SoapFault` (faultcode `HTTP`, etc.) → mismo tratamiento (código vacío si no hay dígitos).

---

## 5. GRE 2022 — API REST (greenter/gre-api + `ws/src/Api/*` + `lite/src/Greenter/Api.php`)

* Endpoints por defecto (`lite/src/Greenter/Api.php`, `$defaaultEndpoints`): `auth` = `https://api-seguridad.sunat.gob.pe/v1`, `cpe` = `https://api-cpe.sunat.gob.pe/v1`. (`gre-api/src/Configuration.php:472-485` define los mismos dos hosts.)
* Token (`gre-api/src/Api/AuthApi.php:404-500`; `ws/src/Api/ApiFactory.php:66-94`) **[verificado]**:
  ```
  POST https://api-seguridad.sunat.gob.pe/v1/clientessol/{client_id}/oauth2/token/
  Accept: application/json
  Content-Type: application/x-www-form-urlencoded
  User-Agent: Greenter-Client/1.0.0/PHP

  grant_type=password&scope=https%3A%2F%2Fapi-cpe.sunat.gob.pe&client_id={id}&client_secret={secret}&username={RUC}{USUARIO_SOL}&password={CLAVE_SOL}
  ```
  Respuesta `{"access_token":"…","token_type":"JWT","expires_in":3600}`. Si `access_token` vacío → `InvalidServiceResponseException('Cliente No autorizado')`. Caché por `client_id` (`TokenStoreInterface`; `InMemoryStore` por defecto; la app usa su `LaravelCacheTokenStore`); se reutiliza si caduca dentro de **más de 600 s** (`ApiFactory.php:68`).
* Envío (`gre-api/src/Api/CpeApi.php:649-735`; `ws/src/Api/GreSender.php:31-51`) **[verificado]**:
  ```
  POST https://api-cpe.sunat.gob.pe/v1/contribuyente/gem/comprobantes/{RUC-09-SERIE-NUMERO}      (nombre SIN extensión)
  Authorization: Bearer {token}
  Accept: application/json
  Content-Type: application/json

  {"archivo":{"nomArchivo":"{name}.zip","arcGreZip":"{base64(zip)}","hashZip":"{sha256(zip) hex minúsculas}"}}
  ```
  El zip es el mismo `ZipFly` con `{name}.xml` dentro; el hash es del **zip binario** (no del base64 ni del XML). Guzzle serializa con `json_encode` estándar (escapa `/` como `\/`, equivalente en JSON). Respuesta 200 `{"numTicket":"uuid","fecRecepcion":"…"}` → `SummaryResult` con ticket.
* Consulta de ticket (`CpeApi.php:349-425`; `GreSender.php:53-106`):
  ```
  GET https://api-cpe.sunat.gob.pe/v1/contribuyente/gem/comprobantes/envios/{numTicket}
  Authorization: Bearer {token}
  ```
  Respuesta `{"codRespuesta":"98|99|0","error":{"numError":"…","desError":"…"},"arcCdr":"base64 zip","indCdrGenerado":"1|0"}`:
  * `"98"` → `Error('98','En proceso')`.
  * `"0"` o `"99"` → si existe `error` → `Error(numError, desError)`; si `indCdrGenerado === "1"` → `success=true`, `cdrZip = base64_decode(arcCdr)`, CdrResponse con el lector de §4.3. (Con 99 puede haber error y CDR a la vez.)
  * Comparaciones de cadena estrictas (`'98'`, `'0'`, `'99'`, `'1'`).
* Errores HTTP (`GreSender::processException`, l.108-121; `CpeApi.php:231-252`): status fuera de 2xx → `ApiException`; `422` → cuerpo `CpeErrorValidation {cod,msg,errors:[{cod,msg}]}` → se usa el **primer** `errors[]` (`Error(err.cod, err.msg)`) **[verificado]** (`1033: El comprobante fue registrado previamente`); `500` → `CpeError {cod,msg}` → `Error(cod,msg)`; cualquier otro (401, 404, red) → `Error('API', mensaje de la excepción)`.

---

## 6. Consulta CPE y consulta de CDR

* **Consulta de CDR (SOAP, con Clave SOL)**: `ConsultCdrService` contra `https://e-factura.sunat.gob.pe/ol-it-wsconscpegem/billConsultService` con WSDL local `billConsultService.wsdl` (namespace de mensajes `http://service.sunat.gob.pe`, SOAPAction `urn:getStatus`/`urn:getStatusCdr`). Ver §4.2.
* **Consulta de validez de CPE (REST, `greenter/consulta-cpe`)**:
  * Token: `POST https://api-seguridad.sunat.gob.pe/v1/clientesextranet/{client_id}/oauth2/token/` (form: `grant_type`, `scope`, `client_id`, `client_secret`; `consulta-cpe/src/Api/AuthApi.php:276-330`). La app usa `grant_type=client_credentials`, `scope=https://api.sunat.gob.pe/v1/contribuyente/contribuyentes` (`app/Services/SunatCpeConsultaService.php:15,103`).
  * Consulta: `POST https://api.sunat.gob.pe/v1/contribuyente/contribuyentes/{ruc}/validarcomprobante` con `Authorization: Bearer` y JSON `{"numRuc","codComp","numeroSerie","numero","fechaEmision","monto"}` (`ConsultaApi.php:276-335`, `Model/CpeFilter.php:76-83`). Host 2 = `https://api.sunat.gob.pe/v1` (`consulta-cpe/src/Configuration.php:409-413`).
  * Respuesta `{"success":bool,"message":"…","data":{"estadoCp":"…","estadoRuc":"…","condDomiRuc":"…","observaciones":[…]},"errorCode":"…"}` (`Model/CpeResponse.php:72-77`, `Model/CpeStatus.php:72-77`).

---

## 7. Endpoints SUNAT definidos en Greenter

`ws/src/Ws/Services/SunatEndpoints.php:6-14`:

| Constante | URL |
|---|---|
| FE_BETA | `https://e-beta.sunat.gob.pe/ol-ti-itcpfegem-beta/billService` |
| FE_HOMOLOGACION | `https://www.sunat.gob.pe/ol-ti-itcpgem-sqa/billService` |
| FE_PRODUCCION | `https://e-factura.sunat.gob.pe/ol-ti-itcpfegem/billService` |
| FE_CONSULTA_CDR | `https://e-factura.sunat.gob.pe/ol-it-wsconscpegem/billConsultService` |
| GUIA_BETA (SOAP legado) | `https://e-beta.sunat.gob.pe/ol-ti-itemision-guia-gem-beta/billService` |
| GUIA_PRODUCCION (SOAP legado) | `https://e-guiaremision.sunat.gob.pe/ol-ti-itemision-guia-gem/billService` |
| RETENCION_BETA (retención, percepción, reversión) | `https://e-beta.sunat.gob.pe/ol-ti-itemision-otroscpe-gem-beta/billService` |
| RETENCION_PRODUCCION | `https://e-factura.sunat.gob.pe/ol-ti-itemision-otroscpe-gem/billService` |
| WSDL_ENDPOINT | `https://e-beta.sunat.gob.pe/ol-ti-itcpfegem-beta/billService?wsdl` |

REST: `https://api-seguridad.sunat.gob.pe/v1` (tokens GRE y consulta), `https://api-cpe.sunat.gob.pe/v1` (GRE), `https://api.sunat.gob.pe/v1` (consulta validez). El `soap:address` del WSDL local apunta a beta (`https://e-beta.sunat.gob.pe:443/ol-ti-itcpfegem-beta/billService`) y al de consulta de producción; siempre se sobreescribe con `setService`. En API-PRO la URL concreta sale de `config("facturacion.sunat.{env}.fe")` (`GreenterService.php:63-73`), no de estas constantes.

---

## 8. greenter/report (PDF / QR)

* La librería **solo genera HTML**: `report/src/Report/HtmlReport.php:19-60` (Twig, zona horaria Lima, plantillas en `report/src/Report/Templates/*.html.twig` con CSS incrustado vía `{% include 'assets/style.css' %}`). El PDF lo haría `greenter/htmltopdf` (wkhtmltopdf), que **no está instalado**. `DefaultTemplateResolver.php:15-43`: Invoice/Note→`invoice.html.twig`, Retention→`retention`, Perception→`perception`, Despatch→`despatch`, Summary→`summary`, Voided/Reversion→`voided`.
* **API-PRO no usa greenter/report** (sin referencias a `Greenter\Report`, `HtmlReport`, `XmlUtils` ni `QrRender` en `app/` ni `resources/`); su PDF va con dompdf (barryvdh/laravel-dompdf) — fuera de alcance. Aun así, si se quiere replicar el comportamiento de la librería:
  * Parámetros: `params.system.logo` (bytes → `data:image/{png|gif|jpeg};base64,…` por cabecera mágica, `Filter/ImageFilter.php`), `params.system.hash` (se imprime “Resumen: {hash}”), `params.user.header|raw`, `params.user.footer|raw`, `params.user.extras[]`, `params.user.numIGV`.
  * Catálogos de texto (`Filter/DocumentFilter.php:6-115`): tipos de documento, símbolo/nombre de moneda, tipo doc identidad (0 N/D, 1 DNI, 6 RUC), cat. 18/19/20/54/59.
  * QR (`report/src/Report/Render/QrRender.php`): BaconQrCode, SVG, `RendererStyle(120, margin 0)`, ECC **Q**, UTF-8.
    * Comprobantes (l.29-51): `RUC|TIPO|SERIE|CORRELATIVO|IGV|TOTAL|FECHA|TIPODOC_CLIENTE|NUMDOC_CLIENTE|` — IGV y TOTAL con `number_format(x,2,'.','')`, FECHA `Y-m-d` **sin** convertir a Lima, y **termina en `|`**. **No incluye el hash** (a diferencia del formato SUNAT que suele añadir el DigestValue).
    * Guía (l.53-73): `RUC|TIPO|SERIE|CORRELATIVO|FECHA|TIPODOC_DEST|NUMDOC_DEST|`.
    * `qrUrl(url)` codifica una URL arbitraria.

---

## 9. Riesgos de portabilidad

1. **C14N inclusiva 1.0 sobre SignedInfo dentro del documento**. El valor firmado incluye en `ds:SignedInfo` las declaraciones `xmlns`, `xmlns:cac`, `xmlns:cbc`, `xmlns:ds`, `xmlns:ext` (+`xmlns:sac`) heredadas de la raíz. Las librerías estándar (Java `javax.xml.crypto.dsig`/Santuario, .NET `SignedXml`, `xmlsec1`/lxml-xmlsec en Python, `xml-crypto` en Node) lo hacen bien **solo si se firma con la firma ya insertada en el árbol real** (o con el contexto de namespaces correcto). Si se construye SignedInfo como string aparte o se usa exc-c14n, la firma no verificará. No cambiar a `xml-exc-c14n#` si se busca equivalencia (SUNAT acepta ambos, pero el XML ya no sería idéntico).
2. **Digest del documento**: enveloped + C14N inclusiva implícita (sin Transform C14N declarada). Implementaciones como .NET `XmlDsigEnvelopedSignatureTransform` añaden C14N por defecto también, OK; en Rust no hay librería madura de C14N: habrá que implementarla (o usar libxml2 vía FFI / `xmlsec`). Cuidado con: orden de atributos (namespace decls primero, default el primero, luego por prefijo; atributos por URI de namespace y nombre local), escape en texto (`&amp; &lt; &gt; &#xD;`) y atributos (`&amp; &lt; &quot; &#x9; &#xA; &#xD;`), CDATA → texto, elementos vacíos expandidos, eliminación de la declaración XML, normalización de saltos de línea a `\n`.
3. **SHA-1 + RSA-SHA1**: algunos runtimes lo bloquean por defecto (Java 17+ en políticas `jdk.xml.dsig.secureValidation`, .NET Core en ciertas plataformas, OpenSSL 3 con nivel de seguridad alto). Habilitarlo explícitamente (o `secureValidation=false` en Java). PKCS#1 v1.5.
4. **Serialización final**: Greenter produce `<?xml version="1.0" encoding="utf-8"?>\n` + documento en una línea + `\n`. Mantener `encoding="utf-8"` en minúsculas, prefijo `ds`, `Id="GreenterSign"`, sin `xmlns:ds` repetido en Signature, `<…/>` para vacíos, CDATA en los mismos elementos, sin BOM. Serializadores (XmlWriter .NET, Java Transformer, lxml) tienden a: añadir `standalone`, cambiar a `UTF-8`, re-declarar namespaces, convertir CDATA, o meter saltos de línea. Esto no rompe la firma (se firma lo canónico), pero sí la igualdad byte a byte y el hash del zip.
5. **Formato decimal**: `number_format` de PHP = redondeo HALF_UP **con pre-redondeo a 15 cifras significativas**: `1.005→1.01`, `1.015→1.02`, `2.675→2.68` **[verificado]**. En Java usar `BigDecimal.valueOf(double)` (no `new BigDecimal(double)`) + `RoundingMode.HALF_UP`; en .NET `decimal` + `MidpointRounding.AwayFromZero` (no `double`); en Python `Decimal(repr(x)).quantize(..., ROUND_HALF_UP)`; en JS/TS no usar `toFixed` (falla con 1.005); en Rust `rust_decimal` con `RoundingStrategy::MidpointAwayFromZero`. Siempre `.` decimal, sin miles, independiente de la cultura (cuidado con `es-PE` en .NET/Java).
6. **Números sin filtro y `n_format_limit`**: replicar el string de PHP con `precision=14` (máx. 14 cifras significativas, `%.14G` sin ceros finales, notación `1.0E+15` / `1.0E-5`). Cantidades, porcentajes (`18`, no `18.00`), `PaymentPercent`, cargos/descuentos de línea y `PriceAmount` dependen de ello. Recomendación práctica: trabajar con decimales y emitir la representación mínima (sin ceros finales) para estos campos, y validar con casos límite (enteros, 0.5, 1e-5, >1e15).
7. **Truthiness PHP vs. `is not null`**: un 0 (o `"0"`) omite bloques en unas plantillas y no en otras (factura: gravadas/inafectas/exoneradas/gratuitas/exportación con `is not null`; NC/ND: truthy; resumen: gravadas `is not null`, resto truthy). Modelar los importes como opcionales (`null` ≠ 0) en todos los lenguajes.
8. **Zona horaria**: todas las fechas/horas del XML y los IDs RC/RA/RR se formatean en `America/Lima` (UTC−05:00, sin horario de verano). Convertir antes de formatear; fechas “solo día” deben construirse ya en Lima para no retroceder un día.
9. **Escape de texto**: Greenter (con `autoescape=false`, como en API-PRO) no escapa: fuera de CDATA un `&` rompe el XML; dentro de CDATA, `]]>` lo rompe. La reimplementación debe escapar fuera de CDATA y partir `]]>` si aparece. Igual con el password del UsernameToken (se inyecta crudo).
10. **Orden de elementos**: es el de las plantillas, con particularidades (Signature antes de ID en retención/percepción; `PartyName` condicional en factura pero obligatorio en notas; PricingReference siempre uno; TaxSubtotal de línea IGV siempre). Los XSD de UBL validan el orden: usar las tablas de §2 y el Anexo como fuente de verdad.
11. **ZIP**: un solo entry `{name}.xml`, deflate, sin data descriptor, sin directorios. Algunas librerías (Java `ZipOutputStream`, Node `archiver`) ponen data descriptor (flag bit 3) o entradas de directorio; SUNAT lo acepta en general, pero para GRE el `hashZip` se calcula sobre los bytes exactos que se envían: calcularlo del mismo buffer.
12. **SOAP**: hijos de operación sin namespace; `contentFile` en base64 sin saltos de línea (algunas pilas MTOM/XOP —CXF, WCF— intentan optimizar `base64Binary`; desactivar MTOM). Envío `SOAPAction: "urn:sendBill"` etc. El TLS de SUNAT no se valida en Greenter (`verify_peer=false`); decidir explícitamente en cada lenguaje.
13. **Lectura de respuestas**: PHP decodifica automáticamente el base64 de `applicationResponse`/`content`; en otros lenguajes hacerlo a mano. El CDR puede venir en zip con carpeta `dummy/` y el XML `R-….xml`: tomar el primer `.xml`. Los códigos de error son strings (conservar ceros a la izquierda: `0111`).
14. **Token GRE**: margen de 600 s antes de expirar; caché por `client_id`; la app reintenta si SUNAT invalida el token (lógica de la app, no de la librería).
15. **Twig desincronizado** (lock 3.23 vs instalado 3.30): no afecta a la salida XML, pero conviene fijar el XML de referencia generando con el `vendor/` actual.

---

## Anexo A — `xml/src/Xml/Templates/invoice2.1.xml.twig` (literal, Greenter v5.2.0, licencia MIT)

````twig
{% apply spaceless %}
<?xml version="1.0" encoding="utf-8"?>
<Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2" xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2" xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xmlns:ext="urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2">
    <ext:UBLExtensions>
        <ext:UBLExtension>
            <ext:ExtensionContent/>
        </ext:UBLExtension>
    </ext:UBLExtensions>
    {% set emp = doc.company %}
    <cbc:UBLVersionID>2.1</cbc:UBLVersionID>
    <cbc:CustomizationID>2.0</cbc:CustomizationID>
    <cbc:ID>{{ doc.serie }}-{{ doc.correlativo }}</cbc:ID>
    <cbc:IssueDate>{{ doc.fechaEmision|date('Y-m-d') }}</cbc:IssueDate>
    <cbc:IssueTime>{{ doc.fechaEmision|date('H:i:s') }}</cbc:IssueTime>
    {% if doc.fecVencimiento %}
    <cbc:DueDate>{{ doc.fecVencimiento|date('Y-m-d') }}</cbc:DueDate>
    {% endif %}
    <cbc:InvoiceTypeCode listID="{{ doc.tipoOperacion }}">{{ doc.tipoDoc }}</cbc:InvoiceTypeCode>
    {% for leg in doc.legends %}
    <cbc:Note languageLocaleID="{{ leg.code }}"><![CDATA[{{ leg.value }}]]></cbc:Note>
    {% endfor %}
    {% if doc.observacion %}
    <cbc:Note><![CDATA[{{ doc.observacion }}]]></cbc:Note>
    {% endif %}
    <cbc:DocumentCurrencyCode>{{ doc.tipoMoneda }}</cbc:DocumentCurrencyCode>
    {% if doc.compra %}
    <cac:OrderReference>
        <cbc:ID>{{ doc.compra }}</cbc:ID>
    </cac:OrderReference>
    {% endif %}
    {% if doc.guias %}
    {% for guia in doc.guias %}
    <cac:DespatchDocumentReference>
        <cbc:ID>{{ guia.nroDoc }}</cbc:ID>
        <cbc:DocumentTypeCode>{{ guia.tipoDoc }}</cbc:DocumentTypeCode>
    </cac:DespatchDocumentReference>
    {% endfor %}
    {% endif %}
    {% if doc.relDocs %}
    {% for rel in doc.relDocs %}
    <cac:AdditionalDocumentReference>
        <cbc:ID>{{ rel.nroDoc }}</cbc:ID>
        <cbc:DocumentTypeCode>{{ rel.tipoDoc }}</cbc:DocumentTypeCode>
    </cac:AdditionalDocumentReference>
    {% endfor %}
    {% endif %}
    {% if doc.anticipos %}
    {% for ant in doc.anticipos %}
    <cac:AdditionalDocumentReference>
        <cbc:ID>{{ ant.nroDocRel }}</cbc:ID>
        <cbc:DocumentTypeCode>{{ ant.tipoDocRel }}</cbc:DocumentTypeCode>
        <cbc:DocumentStatusCode>{{ loop.index }}</cbc:DocumentStatusCode>
        <cac:IssuerParty>
            <cac:PartyIdentification>
                <cbc:ID schemeID="6">{{ emp.ruc }}</cbc:ID>
            </cac:PartyIdentification>
        </cac:IssuerParty>
    </cac:AdditionalDocumentReference>
    {% endfor %}
    {% endif %}
    <cac:Signature>
        <cbc:ID>SIGN{{ emp.ruc }}</cbc:ID>
        <cac:SignatoryParty>
            <cac:PartyIdentification>
                <cbc:ID>{{ emp.ruc }}</cbc:ID>
            </cac:PartyIdentification>
            <cac:PartyName>
                <cbc:Name><![CDATA[{{ emp.razonSocial|raw }}]]></cbc:Name>
            </cac:PartyName>
        </cac:SignatoryParty>
        <cac:DigitalSignatureAttachment>
            <cac:ExternalReference>
                <cbc:URI>#GREENTER-SIGN</cbc:URI>
            </cac:ExternalReference>
        </cac:DigitalSignatureAttachment>
    </cac:Signature>
    <cac:AccountingSupplierParty>
        <cac:Party>
            <cac:PartyIdentification>
                <cbc:ID schemeID="6">{{ emp.ruc }}</cbc:ID>
            </cac:PartyIdentification>
			{% if emp.nombreComercial %}
            <cac:PartyName>
                <cbc:Name><![CDATA[{{ emp.nombreComercial|raw }}]]></cbc:Name>
            </cac:PartyName>
			{% endif %}
            <cac:PartyLegalEntity>
                <cbc:RegistrationName><![CDATA[{{ emp.razonSocial|raw }}]]></cbc:RegistrationName>
                {% set addr = emp.address %}
                <cac:RegistrationAddress>
                    <cbc:ID>{{ addr.ubigueo }}</cbc:ID>
                    <cbc:AddressTypeCode>{{ addr.codLocal }}</cbc:AddressTypeCode>
                    {% if addr.urbanizacion %}
                    <cbc:CitySubdivisionName>{{ addr.urbanizacion }}</cbc:CitySubdivisionName>
                    {% endif %}
                    <cbc:CityName>{{ addr.provincia }}</cbc:CityName>
                    <cbc:CountrySubentity>{{ addr.departamento }}</cbc:CountrySubentity>
                    <cbc:District>{{ addr.distrito }}</cbc:District>
                    <cac:AddressLine>
                        <cbc:Line><![CDATA[{{ addr.direccion|raw }}]]></cbc:Line>
                    </cac:AddressLine>
                    <cac:Country>
                        <cbc:IdentificationCode>{{ addr.codigoPais }}</cbc:IdentificationCode>
                    </cac:Country>
                </cac:RegistrationAddress>
            </cac:PartyLegalEntity>
            {% if emp.email or emp.telephone %}
            <cac:Contact>
                {% if emp.telephone %}
                <cbc:Telephone>{{ emp.telephone }}</cbc:Telephone>
                {% endif %}
                {% if emp.email %}
                <cbc:ElectronicMail>{{ emp.email }}</cbc:ElectronicMail>
                {% endif %}
            </cac:Contact>
            {% endif %}
        </cac:Party>
    </cac:AccountingSupplierParty>
    {% set client = doc.client %}
    <cac:AccountingCustomerParty>
        <cac:Party>
            <cac:PartyIdentification>
                <cbc:ID schemeID="{{ client.tipoDoc }}">{{ client.numDoc }}</cbc:ID>
            </cac:PartyIdentification>
            <cac:PartyLegalEntity>
                <cbc:RegistrationName><![CDATA[{{ client.rznSocial|raw }}]]></cbc:RegistrationName>
                {% if client.address %}
                {% set addr = client.address %}
                <cac:RegistrationAddress>
                    {% if addr.ubigueo %}
                    <cbc:ID>{{ addr.ubigueo }}</cbc:ID>
                    {% endif %}
                    <cac:AddressLine>
                        <cbc:Line><![CDATA[{{ addr.direccion|raw }}]]></cbc:Line>
                    </cac:AddressLine>
                    <cac:Country>
                        <cbc:IdentificationCode>{{ addr.codigoPais }}</cbc:IdentificationCode>
                    </cac:Country>
                </cac:RegistrationAddress>
                {% endif %}
            </cac:PartyLegalEntity>
            {% if client.email or client.telephone %}
            <cac:Contact>
                {% if client.telephone %}
                <cbc:Telephone>{{ client.telephone }}</cbc:Telephone>
                {% endif %}
                {% if client.email %}
                <cbc:ElectronicMail>{{ client.email }}</cbc:ElectronicMail>
                {% endif %}
            </cac:Contact>
            {% endif %}
        </cac:Party>
    </cac:AccountingCustomerParty>
    {% set seller = doc.seller %}
    {% if seller %}
    <cac:SellerSupplierParty>
        <cac:Party>
            <cac:PartyIdentification>
                <cbc:ID schemeID="{{ seller.tipoDoc }}">{{ seller.numDoc }}</cbc:ID>
            </cac:PartyIdentification>
            <cac:PartyLegalEntity>
                <cbc:RegistrationName><![CDATA[{{ seller.rznSocial|raw }}]]></cbc:RegistrationName>
                {% if seller.address %}
                {% set addr = seller.address %}
                <cac:RegistrationAddress>
                    {% if addr.ubigueo %}
                    <cbc:ID>{{ addr.ubigueo }}</cbc:ID>
                    {% endif %}
                    <cac:AddressLine>
                        <cbc:Line><![CDATA[{{ addr.direccion|raw }}]]></cbc:Line>
                    </cac:AddressLine>
                    <cac:Country>
                        <cbc:IdentificationCode>{{ addr.codigoPais }}</cbc:IdentificationCode>
                    </cac:Country>
                </cac:RegistrationAddress>
                {% endif %}
            </cac:PartyLegalEntity>
            {% if seller.email or seller.telephone %}
            <cac:Contact>
                {% if seller.telephone %}
                <cbc:Telephone>{{ seller.telephone }}</cbc:Telephone>
                {% endif %}
                {% if seller.email %}
                <cbc:ElectronicMail>{{ seller.email }}</cbc:ElectronicMail>
                {% endif %}
            </cac:Contact>
            {% endif %}
        </cac:Party>
    </cac:SellerSupplierParty>
    {% endif %}
    {% if doc.direccionEntrega %}
        {% set addr = doc.direccionEntrega %}
        <cac:Delivery>
            <cac:DeliveryLocation>
                <cac:Address>
                    <cbc:ID>{{ addr.ubigueo }}</cbc:ID>
                    {% if addr.urbanizacion %}
                    <cbc:CitySubdivisionName>{{ addr.urbanizacion }}</cbc:CitySubdivisionName>
                    {% endif %}
                    <cbc:CityName>{{ addr.provincia }}</cbc:CityName>
                    <cbc:CountrySubentity>{{ addr.departamento }}</cbc:CountrySubentity>
                    <cbc:District>{{ addr.distrito }}</cbc:District>
                    <cac:AddressLine>
                        <cbc:Line><![CDATA[{{ addr.direccion|raw }}]]></cbc:Line>
                    </cac:AddressLine>
                    <cac:Country>
                        <cbc:IdentificationCode listID="ISO 3166-1" listAgencyName="United Nations Economic Commission for Europe" listName="Country">{{ addr.codigoPais }}</cbc:IdentificationCode>
                    </cac:Country>
                </cac:Address>
            </cac:DeliveryLocation>
        </cac:Delivery>
    {% endif %}
    {% if doc.detraccion %}
    {% set detr = doc.detraccion %}
    <cac:PaymentMeans>
        <cbc:ID>Detraccion</cbc:ID>
        <cbc:PaymentMeansCode>{{ detr.codMedioPago }}</cbc:PaymentMeansCode>
        <cac:PayeeFinancialAccount>
            <cbc:ID>{{ detr.ctaBanco }}</cbc:ID>
        </cac:PayeeFinancialAccount>
    </cac:PaymentMeans>
    <cac:PaymentTerms>
        <cbc:ID>Detraccion</cbc:ID>
        <cbc:PaymentMeansID>{{ detr.codBienDetraccion }}</cbc:PaymentMeansID>
        <cbc:PaymentPercent>{{ detr.percent }}</cbc:PaymentPercent>
        <cbc:Amount currencyID="PEN">{{ detr.mount|n_format }}</cbc:Amount>
    </cac:PaymentTerms>
    {% endif %}
    {% if doc.perception %}
    <cac:PaymentTerms>
        <cbc:ID>Percepcion</cbc:ID>
        <cbc:Amount currencyID="PEN">{{ doc.perception.mtoTotal|n_format }}</cbc:Amount>
    </cac:PaymentTerms>
    {% endif %}
    {% if doc.formaPago %}
    <cac:PaymentTerms>
        <cbc:ID>FormaPago</cbc:ID>
        <cbc:PaymentMeansID>{{ doc.formaPago.tipo }}</cbc:PaymentMeansID>
        {% if doc.formaPago.monto %}
        <cbc:Amount currencyID="{{ doc.formaPago.moneda|default(doc.tipoMoneda) }}">{{ doc.formaPago.monto|n_format }}</cbc:Amount>
        {% endif %}
    </cac:PaymentTerms>
    {% endif %}
    {% if doc.cuotas %}
    {% for cuota in doc.cuotas %}
    <cac:PaymentTerms>
        <cbc:ID>FormaPago</cbc:ID>
        <cbc:PaymentMeansID>Cuota{{ "%03d"|format(loop.index) }}</cbc:PaymentMeansID>
        <cbc:Amount currencyID="{{ cuota.moneda|default(doc.tipoMoneda) }}">{{ cuota.monto|n_format }}</cbc:Amount>
        <cbc:PaymentDueDate>{{ cuota.fechaPago|date('Y-m-d') }}</cbc:PaymentDueDate>
    </cac:PaymentTerms>
    {% endfor %}
    {% endif %}
    {% if doc.anticipos %}
    {% for ant in doc.anticipos %}
    <cac:PrepaidPayment>
        <cbc:ID>{{ loop.index }}</cbc:ID>
        <cbc:PaidAmount currencyID="{{ doc.tipoMoneda }}">{{ ant.total|n_format }}</cbc:PaidAmount>
    </cac:PrepaidPayment>
    {% endfor %}
    {% endif %}
    {% if doc.cargos %}
    {% for cargo in doc.cargos %}
    <cac:AllowanceCharge>
        <cbc:ChargeIndicator>true</cbc:ChargeIndicator>
        <cbc:AllowanceChargeReasonCode>{{ cargo.codTipo }}</cbc:AllowanceChargeReasonCode>
        <cbc:MultiplierFactorNumeric>{{ cargo.factor|n_format_limit(5) }}</cbc:MultiplierFactorNumeric>
        <cbc:Amount currencyID="{{ doc.tipoMoneda }}">{{ cargo.monto|n_format }}</cbc:Amount>
        <cbc:BaseAmount currencyID="{{ doc.tipoMoneda }}">{{ cargo.montoBase|n_format }}</cbc:BaseAmount>
    </cac:AllowanceCharge>
    {% endfor %}
    {% endif %}
    {% if doc.descuentos %}
    {% for desc in doc.descuentos %}
    <cac:AllowanceCharge>
        <cbc:ChargeIndicator>false</cbc:ChargeIndicator>
        <cbc:AllowanceChargeReasonCode>{{ desc.codTipo }}</cbc:AllowanceChargeReasonCode>
        {% if desc.factor is not null %}
        <cbc:MultiplierFactorNumeric>{{ desc.factor|n_format_limit(5) }}</cbc:MultiplierFactorNumeric>
        {% endif %}
        <cbc:Amount currencyID="{{ doc.tipoMoneda }}">{{ desc.monto|n_format }}</cbc:Amount>
        <cbc:BaseAmount currencyID="{{ doc.tipoMoneda }}">{{ desc.montoBase|n_format }}</cbc:BaseAmount>
    </cac:AllowanceCharge>
    {% endfor %}
    {% endif %}
    {% if doc.perception %}
    {% set perc = doc.perception %}
    <cac:AllowanceCharge>
        <cbc:ChargeIndicator>true</cbc:ChargeIndicator>
        <cbc:AllowanceChargeReasonCode>{{ perc.codReg }}</cbc:AllowanceChargeReasonCode>
        <cbc:MultiplierFactorNumeric>{{ perc.porcentaje|n_format_limit(5) }}</cbc:MultiplierFactorNumeric>
        <cbc:Amount currencyID="PEN">{{ perc.mto|n_format }}</cbc:Amount>
        <cbc:BaseAmount currencyID="PEN">{{ perc.mtoBase|n_format }}</cbc:BaseAmount>
    </cac:AllowanceCharge>
    {% endif %}
    <cac:TaxTotal>
        <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.totalImpuestos|n_format }}</cbc:TaxAmount>
        {% if doc.mtoISC %}
        <cac:TaxSubtotal>
            <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoBaseIsc|n_format }}</cbc:TaxableAmount>
            <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoISC|n_format }}</cbc:TaxAmount>
            <cac:TaxCategory>
                <cac:TaxScheme>
                    <cbc:ID>2000</cbc:ID>
                    <cbc:Name>ISC</cbc:Name>
                    <cbc:TaxTypeCode>EXC</cbc:TaxTypeCode>
                </cac:TaxScheme>
            </cac:TaxCategory>
        </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOperGravadas is not null %}
        <cac:TaxSubtotal>
            <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOperGravadas|n_format }}</cbc:TaxableAmount>
            <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoIGV|n_format }}</cbc:TaxAmount>
            <cac:TaxCategory>
                <cac:TaxScheme>
                    <cbc:ID>1000</cbc:ID>
                    <cbc:Name>IGV</cbc:Name>
                    <cbc:TaxTypeCode>VAT</cbc:TaxTypeCode>
                </cac:TaxScheme>
            </cac:TaxCategory>
        </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOperInafectas is not null %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOperInafectas|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">0</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>9998</cbc:ID>
                        <cbc:Name>INA</cbc:Name>
                        <cbc:TaxTypeCode>FRE</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOperExoneradas is not null %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOperExoneradas|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">0</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>9997</cbc:ID>
                        <cbc:Name>EXO</cbc:Name>
                        <cbc:TaxTypeCode>VAT</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOperGratuitas is not null %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOperGratuitas|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoIGVGratuitas|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>9996</cbc:ID>
                        <cbc:Name>GRA</cbc:Name>
                        <cbc:TaxTypeCode>FRE</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOperExportacion is not null %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOperExportacion|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">0</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>9995</cbc:ID>
                        <cbc:Name>EXP</cbc:Name>
                        <cbc:TaxTypeCode>FRE</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoIvap %}
        <cac:TaxSubtotal>
            <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoBaseIvap|n_format }}</cbc:TaxableAmount>
            <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoIvap|n_format }}</cbc:TaxAmount>
            <cac:TaxCategory>
                <cac:TaxScheme>
                    <cbc:ID>1016</cbc:ID>
                    <cbc:Name>IVAP</cbc:Name>
                    <cbc:TaxTypeCode>VAT</cbc:TaxTypeCode>
                </cac:TaxScheme>
            </cac:TaxCategory>
        </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOtrosTributos %}
        <cac:TaxSubtotal>
            <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoBaseOth|n_format }}</cbc:TaxableAmount>
            <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOtrosTributos|n_format }}</cbc:TaxAmount>
            <cac:TaxCategory>
                <cac:TaxScheme>
                    <cbc:ID>9999</cbc:ID>
                    <cbc:Name>OTROS</cbc:Name>
                    <cbc:TaxTypeCode>OTH</cbc:TaxTypeCode>
                </cac:TaxScheme>
            </cac:TaxCategory>
        </cac:TaxSubtotal>
        {% endif %}
        {% if doc.icbper %}
            <cac:TaxSubtotal>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.icbper|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>7152</cbc:ID>
                        <cbc:Name>ICBPER</cbc:Name>
                        <cbc:TaxTypeCode>OTH</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
    </cac:TaxTotal>
    <cac:LegalMonetaryTotal>
        <cbc:LineExtensionAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.valorVenta|n_format }}</cbc:LineExtensionAmount>
        {% if doc.subTotal is not null %}
        <cbc:TaxInclusiveAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.subTotal|n_format }}</cbc:TaxInclusiveAmount>
        {% endif %}
        {% if doc.sumOtrosDescuentos is not null %}
        <cbc:AllowanceTotalAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.sumOtrosDescuentos|n_format }}</cbc:AllowanceTotalAmount>
        {% endif %}
        {% if doc.sumOtrosCargos is not null %}
        <cbc:ChargeTotalAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.sumOtrosCargos|n_format }}</cbc:ChargeTotalAmount>
        {% endif %}
        {% if doc.totalAnticipos is not null %}
        <cbc:PrepaidAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.totalAnticipos|n_format }}</cbc:PrepaidAmount>
        {% endif %}
        {% if doc.redondeo is not null %}
        <cbc:PayableRoundingAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.redondeo|n_format }}</cbc:PayableRoundingAmount>
        {% endif %}
        <cbc:PayableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoImpVenta|n_format }}</cbc:PayableAmount>
    </cac:LegalMonetaryTotal>
    {% for detail in doc.details %}
    <cac:InvoiceLine>
        <cbc:ID>{{ loop.index }}</cbc:ID>
        <cbc:InvoicedQuantity unitCode="{{ detail.unidad }}">{{ detail.cantidad }}</cbc:InvoicedQuantity>
        <cbc:LineExtensionAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoValorVenta|n_format }}</cbc:LineExtensionAmount>
        <cac:PricingReference>
            {% if detail.mtoValorGratuito %}
            <cac:AlternativeConditionPrice>
                <cbc:PriceAmount currencyID="{{ doc.tipoMoneda }}">{{  detail.mtoValorGratuito|n_format_limit(10) }}</cbc:PriceAmount>
                <cbc:PriceTypeCode>02</cbc:PriceTypeCode>
            </cac:AlternativeConditionPrice>
            {% else %}
            <cac:AlternativeConditionPrice>
                <cbc:PriceAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoPrecioUnitario|n_format_limit(10) }}</cbc:PriceAmount>
                <cbc:PriceTypeCode>01</cbc:PriceTypeCode>
            </cac:AlternativeConditionPrice>
            {% endif %}
        </cac:PricingReference>
        {% if detail.cargos %}
        {% for cargo in detail.cargos %}
        <cac:AllowanceCharge>
            <cbc:ChargeIndicator>true</cbc:ChargeIndicator>
            <cbc:AllowanceChargeReasonCode>{{ cargo.codTipo }}</cbc:AllowanceChargeReasonCode>
            <cbc:MultiplierFactorNumeric>{{ cargo.factor|n_format_limit(5) }}</cbc:MultiplierFactorNumeric>
            <cbc:Amount currencyID="{{ doc.tipoMoneda }}">{{ cargo.monto }}</cbc:Amount>
            <cbc:BaseAmount currencyID="{{ doc.tipoMoneda }}">{{ cargo.montoBase }}</cbc:BaseAmount>
        </cac:AllowanceCharge>
        {% endfor %}
        {% endif %}
        {% if detail.descuentos %}
        {% for desc in detail.descuentos %}
        <cac:AllowanceCharge>
            <cbc:ChargeIndicator>false</cbc:ChargeIndicator>
            <cbc:AllowanceChargeReasonCode>{{ desc.codTipo }}</cbc:AllowanceChargeReasonCode>
            <cbc:MultiplierFactorNumeric>{{ desc.factor|n_format_limit(5) }}</cbc:MultiplierFactorNumeric>
            <cbc:Amount currencyID="{{ doc.tipoMoneda }}">{{ desc.monto }}</cbc:Amount>
            <cbc:BaseAmount currencyID="{{ doc.tipoMoneda }}">{{ desc.montoBase }}</cbc:BaseAmount>
        </cac:AllowanceCharge>
        {% endfor %}
        {% endif %}
        <cac:TaxTotal>
            <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.totalImpuestos|n_format }}</cbc:TaxAmount>
            {% if detail.isc %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoBaseIsc|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.isc|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cbc:Percent>{{ detail.porcentajeIsc }}</cbc:Percent>
                    <cbc:TierRange>{{ detail.tipSisIsc }}</cbc:TierRange>
                    <cac:TaxScheme>
                        <cbc:ID>2000</cbc:ID>
                        <cbc:Name>ISC</cbc:Name>
                        <cbc:TaxTypeCode>EXC</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
            {% endif %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoBaseIgv|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.igv|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cbc:Percent>{{ detail.porcentajeIgv }}</cbc:Percent>
                    <cbc:TaxExemptionReasonCode>{{ detail.tipAfeIgv }}</cbc:TaxExemptionReasonCode>
                    {% set afect = getTributoAfect(detail.tipAfeIgv) %}
                    <cac:TaxScheme>
                        <cbc:ID>{{ afect.id }}</cbc:ID>
                        <cbc:Name>{{ afect.name }}</cbc:Name>
                        <cbc:TaxTypeCode>{{ afect.code }}</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
            {% if detail.otroTributo %}
                <cac:TaxSubtotal>
                    <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoBaseOth|n_format }}</cbc:TaxableAmount>
                    <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.otroTributo|n_format }}</cbc:TaxAmount>
                    <cac:TaxCategory>
                        <cbc:Percent>{{ detail.porcentajeOth }}</cbc:Percent>
                        <cac:TaxScheme>
                            <cbc:ID>9999</cbc:ID>
                            <cbc:Name>OTROS</cbc:Name>
                            <cbc:TaxTypeCode>OTH</cbc:TaxTypeCode>
                        </cac:TaxScheme>
                    </cac:TaxCategory>
                </cac:TaxSubtotal>
            {% endif %}
            {% if detail.icbper %}
            <cac:TaxSubtotal>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.icbper|n_format }}</cbc:TaxAmount>
                <cbc:BaseUnitMeasure unitCode="NIU">{{ detail.cantidad }}</cbc:BaseUnitMeasure>
                <cac:TaxCategory>
                    <cbc:PerUnitAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.factorIcbper|n_format }}</cbc:PerUnitAmount>
                    <cac:TaxScheme>
                        <cbc:ID>7152</cbc:ID>
                        <cbc:Name>ICBPER</cbc:Name>
                        <cbc:TaxTypeCode>OTH</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
            {% endif %}
        </cac:TaxTotal>
        <cac:Item>
            <cbc:Description><![CDATA[{{ detail.descripcion|raw }}]]></cbc:Description>
            {% if detail.codProducto %}
            <cac:SellersItemIdentification>
                <cbc:ID>{{ detail.codProducto }}</cbc:ID>
            </cac:SellersItemIdentification>
            {% endif %}
            {% if detail.codProdGS1 %}
            <cac:StandardItemIdentification>
                <cbc:ID>{{ detail.codProdGS1 }}</cbc:ID>
            </cac:StandardItemIdentification>
            {% endif %}
            {% if detail.codProdSunat %}
            <cac:CommodityClassification>
                <cbc:ItemClassificationCode>{{ detail.codProdSunat }}</cbc:ItemClassificationCode>
            </cac:CommodityClassification>
            {% endif %}
            {% if detail.atributos %}
                {% for atr in detail.atributos %}
                    <cac:AdditionalItemProperty >
                        <cbc:Name>{{ atr.name }}</cbc:Name>
                        <cbc:NameCode>{{ atr.code }}</cbc:NameCode>
                        {% if atr.value is not null %}
                        <cbc:Value>{{ atr.value }}</cbc:Value>
                        {% endif %}
                        {% if atr.fecInicio or atr.fecFin or atr.duracion %}
                            <cac:UsabilityPeriod>
                                {% if atr.fecInicio %}
                                <cbc:StartDate>{{ atr.fecInicio|date('Y-m-d') }}</cbc:StartDate>
                                {% endif %}
                                {% if atr.fecFin %}
                                <cbc:EndDate>{{ atr.fecFin|date('Y-m-d') }}</cbc:EndDate>
                                {% endif %}
                                {% if atr.duracion %}
                                <cbc:DurationMeasure unitCode="DAY">{{ atr.duracion }}</cbc:DurationMeasure>
                                {% endif %}
                            </cac:UsabilityPeriod>
                        {% endif %}
                    </cac:AdditionalItemProperty>
                {% endfor %}
            {% endif %}
        </cac:Item>
        <cac:Price>
            <cbc:PriceAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoValorUnitario|n_format_limit(10) }}</cbc:PriceAmount>
        </cac:Price>
    </cac:InvoiceLine>
    {% endfor %}
</Invoice>
{% endapply %}

````

## Anexo B — Resto de plantillas 2.1/2022/SUNAT-1 (literales)

### B.notacr2.1 — `xml/src/Xml/Templates/notacr2.1.xml.twig`

````twig
{% apply spaceless %}
<?xml version="1.0" encoding="utf-8"?>
<CreditNote xmlns="urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2" xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2" xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xmlns:ext="urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2">
    <ext:UBLExtensions>
        <ext:UBLExtension>
            <ext:ExtensionContent/>
        </ext:UBLExtension>
    </ext:UBLExtensions>
    <cbc:UBLVersionID>2.1</cbc:UBLVersionID>
    <cbc:CustomizationID>2.0</cbc:CustomizationID>
    <cbc:ID>{{ doc.serie }}-{{ doc.correlativo }}</cbc:ID>
    <cbc:IssueDate>{{ doc.fechaEmision|date('Y-m-d') }}</cbc:IssueDate>
    <cbc:IssueTime>{{ doc.fechaEmision|date('H:i:s') }}</cbc:IssueTime>
    {% for leg in doc.legends %}
    <cbc:Note languageLocaleID="{{ leg.code }}"><![CDATA[{{ leg.value }}]]></cbc:Note>
    {% endfor %}
    <cbc:DocumentCurrencyCode>{{ doc.tipoMoneda }}</cbc:DocumentCurrencyCode>
    <cac:DiscrepancyResponse>
        <cbc:ReferenceID>{{ doc.numDocfectado }}</cbc:ReferenceID>
        <cbc:ResponseCode>{{ doc.codMotivo }}</cbc:ResponseCode>
        <cbc:Description>{{ doc.desMotivo }}</cbc:Description>
    </cac:DiscrepancyResponse>
    {% if doc.compra %}
    <cac:OrderReference>
        <cbc:ID>{{ doc.compra }}</cbc:ID>
    </cac:OrderReference>
    {% endif %}
    <cac:BillingReference>
        <cac:InvoiceDocumentReference>
            <cbc:ID>{{ doc.numDocfectado }}</cbc:ID>
            <cbc:DocumentTypeCode>{{ doc.tipDocAfectado }}</cbc:DocumentTypeCode>
        </cac:InvoiceDocumentReference>
    </cac:BillingReference>
    {% if doc.guias %}
    {% for guia in doc.guias %}
    <cac:DespatchDocumentReference>
        <cbc:ID>{{ guia.nroDoc }}</cbc:ID>
        <cbc:DocumentTypeCode>{{ guia.tipoDoc }}</cbc:DocumentTypeCode>
    </cac:DespatchDocumentReference>
    {% endfor %}
    {% endif %}
    {% if doc.relDocs %}
    {% for rel in doc.relDocs %}
    <cac:AdditionalDocumentReference>
        <cbc:ID>{{ rel.nroDoc }}</cbc:ID>
        <cbc:DocumentTypeCode>{{ rel.tipoDoc }}</cbc:DocumentTypeCode>
    </cac:AdditionalDocumentReference>
    {% endfor %}
    {% endif %}
    {% set emp = doc.company %}
    <cac:Signature>
        <cbc:ID>SIGN{{ emp.ruc }}</cbc:ID>
        <cac:SignatoryParty>
            <cac:PartyIdentification>
                <cbc:ID>{{ emp.ruc }}</cbc:ID>
            </cac:PartyIdentification>
            <cac:PartyName>
                <cbc:Name><![CDATA[{{ emp.razonSocial|raw }}]]></cbc:Name>
            </cac:PartyName>
        </cac:SignatoryParty>
        <cac:DigitalSignatureAttachment>
            <cac:ExternalReference>
                <cbc:URI>#GREENTER-SIGN</cbc:URI>
            </cac:ExternalReference>
        </cac:DigitalSignatureAttachment>
    </cac:Signature>
    <cac:AccountingSupplierParty>
        <cac:Party>
            <cac:PartyIdentification>
                <cbc:ID schemeID="6">{{ emp.ruc }}</cbc:ID>
            </cac:PartyIdentification>
            <cac:PartyName>
                <cbc:Name><![CDATA[{{ emp.nombreComercial|raw }}]]></cbc:Name>
            </cac:PartyName>
            <cac:PartyLegalEntity>
                <cbc:RegistrationName><![CDATA[{{ emp.razonSocial|raw }}]]></cbc:RegistrationName>
                {% set addr = emp.address %}
                <cac:RegistrationAddress>
                    <cbc:ID>{{ addr.ubigueo }}</cbc:ID>
                    <cbc:AddressTypeCode>{{ addr.codLocal }}</cbc:AddressTypeCode>
                    {% if addr.urbanizacion %}
                    <cbc:CitySubdivisionName>{{ addr.urbanizacion }}</cbc:CitySubdivisionName>
                    {% endif %}
                    <cbc:CityName>{{ addr.provincia }}</cbc:CityName>
                    <cbc:CountrySubentity>{{ addr.departamento }}</cbc:CountrySubentity>
                    <cbc:District>{{ addr.distrito }}</cbc:District>
                    <cac:AddressLine>
                        <cbc:Line><![CDATA[{{ addr.direccion|raw }}]]></cbc:Line>
                    </cac:AddressLine>
                    <cac:Country>
                        <cbc:IdentificationCode>{{ addr.codigoPais }}</cbc:IdentificationCode>
                    </cac:Country>
                </cac:RegistrationAddress>
            </cac:PartyLegalEntity>
            {% if emp.email or emp.telephone %}
                <cac:Contact>
                    {% if emp.telephone %}
                        <cbc:Telephone>{{ emp.telephone }}</cbc:Telephone>
                    {% endif %}
                    {% if emp.email %}
                        <cbc:ElectronicMail>{{ emp.email }}</cbc:ElectronicMail>
                    {% endif %}
                </cac:Contact>
            {% endif %}
        </cac:Party>
    </cac:AccountingSupplierParty>
    {% set client = doc.client %}
    <cac:AccountingCustomerParty>
        <cac:Party>
            <cac:PartyIdentification>
                <cbc:ID schemeID="{{ client.tipoDoc }}">{{ client.numDoc }}</cbc:ID>
            </cac:PartyIdentification>
            <cac:PartyLegalEntity>
                <cbc:RegistrationName><![CDATA[{{ client.rznSocial|raw }}]]></cbc:RegistrationName>
                {% if client.address %}
                    {% set addr = client.address %}
                    <cac:RegistrationAddress>
                        {% if addr.ubigueo %}
                            <cbc:ID>{{ addr.ubigueo }}</cbc:ID>
                        {% endif %}
                        <cac:AddressLine>
                            <cbc:Line><![CDATA[{{ addr.direccion|raw }}]]></cbc:Line>
                        </cac:AddressLine>
                        <cac:Country>
                            <cbc:IdentificationCode>{{ addr.codigoPais }}</cbc:IdentificationCode>
                        </cac:Country>
                    </cac:RegistrationAddress>
                {% endif %}
            </cac:PartyLegalEntity>
            {% if client.email or client.telephone %}
                <cac:Contact>
                    {% if client.telephone %}
                        <cbc:Telephone>{{ client.telephone }}</cbc:Telephone>
                    {% endif %}
                    {% if client.email %}
                        <cbc:ElectronicMail>{{ client.email }}</cbc:ElectronicMail>
                    {% endif %}
                </cac:Contact>
            {% endif %}
        </cac:Party>
    </cac:AccountingCustomerParty>
    {% if doc.formaPago %}
    <cac:PaymentTerms>
        <cbc:ID>FormaPago</cbc:ID>
        <cbc:PaymentMeansID>{{ doc.formaPago.tipo }}</cbc:PaymentMeansID>
        {% if doc.formaPago.monto %}
        <cbc:Amount currencyID="{{ doc.formaPago.moneda|default(doc.tipoMoneda) }}">{{ doc.formaPago.monto|n_format }}</cbc:Amount>
        {% endif %}
    </cac:PaymentTerms>
    {% endif %}
    {% if doc.cuotas %}
    {% for cuota in doc.cuotas %}
    <cac:PaymentTerms>
        <cbc:ID>FormaPago</cbc:ID>
        <cbc:PaymentMeansID>Cuota{{ "%03d"|format(loop.index) }}</cbc:PaymentMeansID>
        <cbc:Amount currencyID="{{ cuota.moneda|default(doc.tipoMoneda) }}">{{ cuota.monto|n_format }}</cbc:Amount>
        <cbc:PaymentDueDate>{{ cuota.fechaPago|date('Y-m-d') }}</cbc:PaymentDueDate>
    </cac:PaymentTerms>
    {% endfor %}
    {% endif %}
    <cac:TaxTotal>
        <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.totalImpuestos|n_format }}</cbc:TaxAmount>
        {% if doc.mtoISC %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoBaseIsc|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoISC|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>2000</cbc:ID>
                        <cbc:Name>ISC</cbc:Name>
                        <cbc:TaxTypeCode>EXC</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOperGravadas %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOperGravadas|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoIGV|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>1000</cbc:ID>
                        <cbc:Name>IGV</cbc:Name>
                        <cbc:TaxTypeCode>VAT</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOperInafectas %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOperInafectas|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">0</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>9998</cbc:ID>
                        <cbc:Name>INA</cbc:Name>
                        <cbc:TaxTypeCode>FRE</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOperExoneradas %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOperExoneradas|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">0</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>9997</cbc:ID>
                        <cbc:Name>EXO</cbc:Name>
                        <cbc:TaxTypeCode>VAT</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOperGratuitas %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOperGratuitas|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoIGVGratuitas|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>9996</cbc:ID>
                        <cbc:Name>GRA</cbc:Name>
                        <cbc:TaxTypeCode>FRE</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOperExportacion %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOperExportacion|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">0</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>9995</cbc:ID>
                        <cbc:Name>EXP</cbc:Name>
                        <cbc:TaxTypeCode>FRE</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoIvap %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoBaseIvap|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoIvap|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>1016</cbc:ID>
                        <cbc:Name>IVAP</cbc:Name>
                        <cbc:TaxTypeCode>VAT</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOtrosTributos %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoBaseOth|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOtrosTributos|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>9999</cbc:ID>
                        <cbc:Name>OTROS</cbc:Name>
                        <cbc:TaxTypeCode>OTH</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.icbper %}
            <cac:TaxSubtotal>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.icbper|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>7152</cbc:ID>
                        <cbc:Name>ICBPER</cbc:Name>
                        <cbc:TaxTypeCode>OTH</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
    </cac:TaxTotal>
    <cac:LegalMonetaryTotal>
        {% if doc.sumOtrosCargos is not null %}
        <cbc:ChargeTotalAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.sumOtrosCargos|n_format }}</cbc:ChargeTotalAmount>
        {% endif %}
        {% if doc.redondeo is not null %}
        <cbc:PayableRoundingAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.redondeo|n_format }}</cbc:PayableRoundingAmount>
        {% endif %}
        <cbc:PayableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoImpVenta|n_format }}</cbc:PayableAmount>
    </cac:LegalMonetaryTotal>
    {% for detail in doc.details %}
    <cac:CreditNoteLine>
        <cbc:ID>{{ loop.index }}</cbc:ID>
        <cbc:CreditedQuantity unitCode="{{ detail.unidad }}">{{ detail.cantidad }}</cbc:CreditedQuantity>
        <cbc:LineExtensionAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoValorVenta|n_format }}</cbc:LineExtensionAmount>
        <cac:PricingReference>
            {% if detail.mtoValorGratuito %}
            <cac:AlternativeConditionPrice>
                <cbc:PriceAmount currencyID="{{ doc.tipoMoneda }}">{{  detail.mtoValorGratuito|n_format_limit(10) }}</cbc:PriceAmount>
                <cbc:PriceTypeCode>02</cbc:PriceTypeCode>
            </cac:AlternativeConditionPrice>
            {% else %}
            <cac:AlternativeConditionPrice>
                <cbc:PriceAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoPrecioUnitario|n_format_limit(10) }}</cbc:PriceAmount>
                <cbc:PriceTypeCode>01</cbc:PriceTypeCode>
            </cac:AlternativeConditionPrice>
            {% endif %}
        </cac:PricingReference>
        <cac:TaxTotal>
            <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.totalImpuestos|n_format }}</cbc:TaxAmount>
            {% if detail.isc %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoBaseIsc|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.isc|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cbc:Percent>{{ detail.porcentajeIsc }}</cbc:Percent>
                    <cbc:TierRange>{{ detail.tipSisIsc }}</cbc:TierRange>
                    <cac:TaxScheme>
                        <cbc:ID>2000</cbc:ID>
                        <cbc:Name>ISC</cbc:Name>
                        <cbc:TaxTypeCode>EXC</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
            {% endif %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoBaseIgv|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.igv|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cbc:Percent>{{ detail.porcentajeIgv }}</cbc:Percent>
                    <cbc:TaxExemptionReasonCode>{{ detail.tipAfeIgv }}</cbc:TaxExemptionReasonCode>
                    {% set afect = getTributoAfect(detail.tipAfeIgv) %}
                    <cac:TaxScheme>
                        <cbc:ID>{{ afect.id }}</cbc:ID>
                        <cbc:Name>{{ afect.name }}</cbc:Name>
                        <cbc:TaxTypeCode>{{ afect.code }}</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
            {% if detail.otroTributo %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoBaseOth|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.otroTributo|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cbc:Percent>{{ detail.porcentajeOth }}</cbc:Percent>
                    <cac:TaxScheme>
                        <cbc:ID>9999</cbc:ID>
                        <cbc:Name>OTROS</cbc:Name>
                        <cbc:TaxTypeCode>OTH</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
            {% endif %}
            {% if detail.icbper %}
            <cac:TaxSubtotal>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.icbper|n_format }}</cbc:TaxAmount>
                <cbc:BaseUnitMeasure unitCode="{{ detail.unidad }}">{{ detail.cantidad }}</cbc:BaseUnitMeasure>
                <cac:TaxCategory>
                    <cbc:PerUnitAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.factorIcbper|n_format }}</cbc:PerUnitAmount>
                    <cac:TaxScheme>
                        <cbc:ID>7152</cbc:ID>
                        <cbc:Name>ICBPER</cbc:Name>
                        <cbc:TaxTypeCode>OTH</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
            {% endif %}
        </cac:TaxTotal>
        <cac:Item>
            <cbc:Description><![CDATA[{{ detail.descripcion|raw }}]]></cbc:Description>
            {% if detail.codProducto %}
                <cac:SellersItemIdentification>
                    <cbc:ID>{{ detail.codProducto }}</cbc:ID>
                </cac:SellersItemIdentification>
            {% endif %}
            {% if detail.codProdGS1 %}
                <cac:StandardItemIdentification>
                    <cbc:ID>{{ detail.codProdGS1 }}</cbc:ID>
                </cac:StandardItemIdentification>
            {% endif %}
            {% if detail.codProdSunat %}
                <cac:CommodityClassification>
                    <cbc:ItemClassificationCode>{{ detail.codProdSunat }}</cbc:ItemClassificationCode>
                </cac:CommodityClassification>
            {% endif %}
            {% if detail.atributos %}
                {% for atr in detail.atributos %}
                    <cac:AdditionalItemProperty >
                        <cbc:Name>{{ atr.name }}</cbc:Name>
                        <cbc:NameCode>{{ atr.code }}</cbc:NameCode>
                        {% if atr.value %}
                            <cbc:Value>{{ atr.value }}</cbc:Value>
                        {% endif %}
                        {% if atr.fecInicio or atr.fecFin or atr.duracion %}
                            <cac:UsabilityPeriod>
                                {% if atr.fecInicio %}
                                    <cbc:StartDate>{{ atr.fecInicio|date('Y-m-d') }}</cbc:StartDate>
                                {% endif %}
                                {% if atr.fecFin %}
                                    <cbc:EndDate>{{ atr.fecFin|date('Y-m-d') }}</cbc:EndDate>
                                {% endif %}
                                {% if atr.duracion %}
                                    <cbc:DurationMeasure unitCode="DAY">{{ atr.duracion }}</cbc:DurationMeasure>
                                {% endif %}
                            </cac:UsabilityPeriod>
                        {% endif %}
                    </cac:AdditionalItemProperty>
                {% endfor %}
            {% endif %}
        </cac:Item>
        <cac:Price>
            <cbc:PriceAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoValorUnitario|n_format_limit(10) }}</cbc:PriceAmount>
        </cac:Price>
    </cac:CreditNoteLine>
    {% endfor %}
</CreditNote>
{% endapply %}
````

### B.notadb2.1 — `xml/src/Xml/Templates/notadb2.1.xml.twig`

````twig
{% apply spaceless %}
<?xml version="1.0" encoding="utf-8"?>
<DebitNote xmlns="urn:oasis:names:specification:ubl:schema:xsd:DebitNote-2" xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2" xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xmlns:ext="urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2">
    <ext:UBLExtensions>
        <ext:UBLExtension>
            <ext:ExtensionContent/>
        </ext:UBLExtension>
    </ext:UBLExtensions>
    <cbc:UBLVersionID>2.1</cbc:UBLVersionID>
    <cbc:CustomizationID>2.0</cbc:CustomizationID>
    <cbc:ID>{{ doc.serie }}-{{ doc.correlativo }}</cbc:ID>
    <cbc:IssueDate>{{ doc.fechaEmision|date('Y-m-d') }}</cbc:IssueDate>
    <cbc:IssueTime>{{ doc.fechaEmision|date('H:i:s') }}</cbc:IssueTime>
    {% for leg in doc.legends %}
        <cbc:Note languageLocaleID="{{ leg.code }}"><![CDATA[{{ leg.value }}]]></cbc:Note>
    {% endfor %}
    <cbc:DocumentCurrencyCode>{{ doc.tipoMoneda }}</cbc:DocumentCurrencyCode>
    <cac:DiscrepancyResponse>
        <cbc:ReferenceID>{{ doc.numDocfectado }}</cbc:ReferenceID>
        <cbc:ResponseCode>{{ doc.codMotivo }}</cbc:ResponseCode>
        <cbc:Description>{{ doc.desMotivo }}</cbc:Description>
    </cac:DiscrepancyResponse>
    {% if doc.compra %}
    <cac:OrderReference>
        <cbc:ID>{{ doc.compra }}</cbc:ID>
    </cac:OrderReference>
    {% endif %}
    <cac:BillingReference>
        <cac:InvoiceDocumentReference>
            <cbc:ID>{{ doc.numDocfectado }}</cbc:ID>
            <cbc:DocumentTypeCode>{{ doc.tipDocAfectado }}</cbc:DocumentTypeCode>
        </cac:InvoiceDocumentReference>
    </cac:BillingReference>
    {% if doc.guias %}
    {% for guia in doc.guias %}
    <cac:DespatchDocumentReference>
        <cbc:ID>{{ guia.nroDoc }}</cbc:ID>
        <cbc:DocumentTypeCode>{{ guia.tipoDoc }}</cbc:DocumentTypeCode>
    </cac:DespatchDocumentReference>
    {% endfor %}
    {% endif %}
    {% if doc.relDocs %}
    {% for rel in doc.relDocs %}
    <cac:AdditionalDocumentReference>
        <cbc:ID>{{ rel.nroDoc }}</cbc:ID>
        <cbc:DocumentTypeCode>{{ rel.tipoDoc }}</cbc:DocumentTypeCode>
    </cac:AdditionalDocumentReference>
    {% endfor %}
    {% endif %}
    {% set emp = doc.company %}
    <cac:Signature>
        <cbc:ID>SIGN{{ emp.ruc }}</cbc:ID>
        <cac:SignatoryParty>
            <cac:PartyIdentification>
                <cbc:ID>{{ emp.ruc }}</cbc:ID>
            </cac:PartyIdentification>
            <cac:PartyName>
                <cbc:Name><![CDATA[{{ emp.razonSocial|raw }}]]></cbc:Name>
            </cac:PartyName>
        </cac:SignatoryParty>
        <cac:DigitalSignatureAttachment>
            <cac:ExternalReference>
                <cbc:URI>#GREENTER-SIGN</cbc:URI>
            </cac:ExternalReference>
        </cac:DigitalSignatureAttachment>
    </cac:Signature>
    <cac:AccountingSupplierParty>
        <cac:Party>
            <cac:PartyIdentification>
                <cbc:ID schemeID="6">{{ emp.ruc }}</cbc:ID>
            </cac:PartyIdentification>
            <cac:PartyName>
                <cbc:Name><![CDATA[{{ emp.nombreComercial|raw }}]]></cbc:Name>
            </cac:PartyName>
            <cac:PartyLegalEntity>
                <cbc:RegistrationName><![CDATA[{{ emp.razonSocial|raw }}]]></cbc:RegistrationName>
                {% set addr = emp.address %}
                <cac:RegistrationAddress>
                    <cbc:ID>{{ addr.ubigueo }}</cbc:ID>
                    <cbc:AddressTypeCode>{{ addr.codLocal }}</cbc:AddressTypeCode>
                    {% if addr.urbanizacion %}
                        <cbc:CitySubdivisionName>{{ addr.urbanizacion }}</cbc:CitySubdivisionName>
                    {% endif %}
                    <cbc:CityName>{{ addr.provincia }}</cbc:CityName>
                    <cbc:CountrySubentity>{{ addr.departamento }}</cbc:CountrySubentity>
                    <cbc:District>{{ addr.distrito }}</cbc:District>
                    <cac:AddressLine>
                        <cbc:Line><![CDATA[{{ addr.direccion|raw }}]]></cbc:Line>
                    </cac:AddressLine>
                    <cac:Country>
                        <cbc:IdentificationCode>{{ addr.codigoPais }}</cbc:IdentificationCode>
                    </cac:Country>
                </cac:RegistrationAddress>
            </cac:PartyLegalEntity>
            {% if emp.email or emp.telephone %}
                <cac:Contact>
                    {% if emp.telephone %}
                        <cbc:Telephone>{{ emp.telephone }}</cbc:Telephone>
                    {% endif %}
                    {% if emp.email %}
                        <cbc:ElectronicMail>{{ emp.email }}</cbc:ElectronicMail>
                    {% endif %}
                </cac:Contact>
            {% endif %}
        </cac:Party>
    </cac:AccountingSupplierParty>
    {% set client = doc.client %}
    <cac:AccountingCustomerParty>
        <cac:Party>
            <cac:PartyIdentification>
                <cbc:ID schemeID="{{ client.tipoDoc }}">{{ client.numDoc }}</cbc:ID>
            </cac:PartyIdentification>
            <cac:PartyLegalEntity>
                <cbc:RegistrationName><![CDATA[{{ client.rznSocial|raw }}]]></cbc:RegistrationName>
                {% if client.address %}
                    {% set addr = client.address %}
                    <cac:RegistrationAddress>
                        {% if addr.ubigueo %}
                            <cbc:ID>{{ addr.ubigueo }}</cbc:ID>
                        {% endif %}
                        <cac:AddressLine>
                            <cbc:Line><![CDATA[{{ addr.direccion|raw }}]]></cbc:Line>
                        </cac:AddressLine>
                        <cac:Country>
                            <cbc:IdentificationCode>{{ addr.codigoPais }}</cbc:IdentificationCode>
                        </cac:Country>
                    </cac:RegistrationAddress>
                {% endif %}
            </cac:PartyLegalEntity>
            {% if client.email or client.telephone %}
                <cac:Contact>
                    {% if client.telephone %}
                        <cbc:Telephone>{{ client.telephone }}</cbc:Telephone>
                    {% endif %}
                    {% if client.email %}
                        <cbc:ElectronicMail>{{ client.email }}</cbc:ElectronicMail>
                    {% endif %}
                </cac:Contact>
            {% endif %}
        </cac:Party>
    </cac:AccountingCustomerParty>
    <cac:TaxTotal>
        <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.totalImpuestos|n_format }}</cbc:TaxAmount>
        {% if doc.mtoISC %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoBaseIsc|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoISC|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>2000</cbc:ID>
                        <cbc:Name>ISC</cbc:Name>
                        <cbc:TaxTypeCode>EXC</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOperGravadas %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOperGravadas|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoIGV|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>1000</cbc:ID>
                        <cbc:Name>IGV</cbc:Name>
                        <cbc:TaxTypeCode>VAT</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOperInafectas %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOperInafectas|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">0</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>9998</cbc:ID>
                        <cbc:Name>INA</cbc:Name>
                        <cbc:TaxTypeCode>FRE</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOperExoneradas %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOperExoneradas|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">0</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>9997</cbc:ID>
                        <cbc:Name>EXO</cbc:Name>
                        <cbc:TaxTypeCode>VAT</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOperGratuitas %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOperGratuitas|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoIGVGratuitas|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>9996</cbc:ID>
                        <cbc:Name>GRA</cbc:Name>
                        <cbc:TaxTypeCode>FRE</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOperExportacion %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOperExportacion|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">0</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>9995</cbc:ID>
                        <cbc:Name>EXP</cbc:Name>
                        <cbc:TaxTypeCode>FRE</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoIvap %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoBaseIvap|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoIvap|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>1016</cbc:ID>
                        <cbc:Name>IVAP</cbc:Name>
                        <cbc:TaxTypeCode>VAT</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.mtoOtrosTributos %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoBaseOth|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoOtrosTributos|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>9999</cbc:ID>
                        <cbc:Name>OTROS</cbc:Name>
                        <cbc:TaxTypeCode>OTH</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
        {% if doc.icbper %}
            <cac:TaxSubtotal>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.icbper|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>7152</cbc:ID>
                        <cbc:Name>ICBPER</cbc:Name>
                        <cbc:TaxTypeCode>OTH</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        {% endif %}
    </cac:TaxTotal>
    <cac:RequestedMonetaryTotal>
        {% if doc.valorVenta is not null %}
            <cbc:LineExtensionAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.valorVenta|n_format }}</cbc:LineExtensionAmount>
        {% endif %}
        {% if doc.subTotal is not null %}
            <cbc:TaxInclusiveAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.subTotal|n_format }}</cbc:TaxInclusiveAmount>
        {% endif %}
        {% if doc.sumOtrosCargos is not null %}
        <cbc:ChargeTotalAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.sumOtrosCargos|n_format }}</cbc:ChargeTotalAmount>
        {% endif %}
        {% if doc.redondeo is not null %}
        <cbc:PayableRoundingAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.redondeo|n_format }}</cbc:PayableRoundingAmount>
        {% endif %}
        <cbc:PayableAmount currencyID="{{ doc.tipoMoneda }}">{{ doc.mtoImpVenta|n_format }}</cbc:PayableAmount>
    </cac:RequestedMonetaryTotal>
    {% for detail in doc.details %}
    <cac:DebitNoteLine>
        <cbc:ID>{{ loop.index }}</cbc:ID>
        <cbc:DebitedQuantity unitCode="{{ detail.unidad }}">{{ detail.cantidad }}</cbc:DebitedQuantity>
        <cbc:LineExtensionAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoValorVenta|n_format }}</cbc:LineExtensionAmount>
        <cac:PricingReference>
            {% if detail.mtoValorGratuito %}
            <cac:AlternativeConditionPrice>
                <cbc:PriceAmount currencyID="{{ doc.tipoMoneda }}">{{  detail.mtoValorGratuito|n_format_limit(10) }}</cbc:PriceAmount>
                <cbc:PriceTypeCode>02</cbc:PriceTypeCode>
            </cac:AlternativeConditionPrice>
            {% else %}
            <cac:AlternativeConditionPrice>
                <cbc:PriceAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoPrecioUnitario|n_format_limit(10) }}</cbc:PriceAmount>
                <cbc:PriceTypeCode>01</cbc:PriceTypeCode>
            </cac:AlternativeConditionPrice>
            {% endif %}
        </cac:PricingReference>
        <cac:TaxTotal>
            <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.totalImpuestos|n_format }}</cbc:TaxAmount>
            {% if detail.isc %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoBaseIsc|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.isc|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cbc:Percent>{{ detail.porcentajeIsc }}</cbc:Percent>
                    <cbc:TierRange>{{ detail.tipSisIsc }}</cbc:TierRange>
                    <cac:TaxScheme>
                        <cbc:ID>2000</cbc:ID>
                        <cbc:Name>ISC</cbc:Name>
                        <cbc:TaxTypeCode>EXC</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
            {% endif %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoBaseIgv|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.igv|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cbc:Percent>{{ detail.porcentajeIgv }}</cbc:Percent>
                    <cbc:TaxExemptionReasonCode>{{ detail.tipAfeIgv }}</cbc:TaxExemptionReasonCode>
                    {% set afect = getTributoAfect(detail.tipAfeIgv) %}
                    <cac:TaxScheme>
                        <cbc:ID>{{ afect.id }}</cbc:ID>
                        <cbc:Name>{{ afect.name }}</cbc:Name>
                        <cbc:TaxTypeCode>{{ afect.code }}</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
            {% if detail.otroTributo %}
            <cac:TaxSubtotal>
                <cbc:TaxableAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoBaseOth|n_format }}</cbc:TaxableAmount>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.otroTributo|n_format }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cbc:Percent>{{ detail.porcentajeOth }}</cbc:Percent>
                    <cac:TaxScheme>
                        <cbc:ID>9999</cbc:ID>
                        <cbc:Name>OTROS</cbc:Name>
                        <cbc:TaxTypeCode>OTH</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
            {% endif %}
            {% if detail.icbper %}
            <cac:TaxSubtotal>
                <cbc:TaxAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.icbper|n_format }}</cbc:TaxAmount>
                <cbc:BaseUnitMeasure unitCode="{{ detail.unidad }}">{{ detail.cantidad }}</cbc:BaseUnitMeasure>
                <cac:TaxCategory>
                    <cbc:PerUnitAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.factorIcbper|n_format }}</cbc:PerUnitAmount>
                    <cac:TaxScheme>
                        <cbc:ID>7152</cbc:ID>
                        <cbc:Name>ICBPER</cbc:Name>
                        <cbc:TaxTypeCode>OTH</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
            {% endif %}
        </cac:TaxTotal>
        <cac:Item>
            <cbc:Description><![CDATA[{{ detail.descripcion|raw }}]]></cbc:Description>
            {% if detail.codProducto %}
                <cac:SellersItemIdentification>
                    <cbc:ID>{{ detail.codProducto }}</cbc:ID>
                </cac:SellersItemIdentification>
            {% endif %}
            {% if detail.codProdGS1 %}
                <cac:StandardItemIdentification>
                    <cbc:ID>{{ detail.codProdGS1 }}</cbc:ID>
                </cac:StandardItemIdentification>
            {% endif %}
            {% if detail.codProdSunat %}
                <cac:CommodityClassification>
                    <cbc:ItemClassificationCode>{{ detail.codProdSunat }}</cbc:ItemClassificationCode>
                </cac:CommodityClassification>
            {% endif %}
            {% if detail.atributos %}
                {% for atr in detail.atributos %}
                    <cac:AdditionalItemProperty >
                        <cbc:Name>{{ atr.name }}</cbc:Name>
                        <cbc:NameCode>{{ atr.code }}</cbc:NameCode>
                        {% if atr.value %}
                            <cbc:Value>{{ atr.value }}</cbc:Value>
                        {% endif %}
                        {% if atr.fecInicio or atr.fecFin or atr.duracion %}
                            <cac:UsabilityPeriod>
                                {% if atr.fecInicio %}
                                    <cbc:StartDate>{{ atr.fecInicio|date('Y-m-d') }}</cbc:StartDate>
                                {% endif %}
                                {% if atr.fecFin %}
                                    <cbc:EndDate>{{ atr.fecFin|date('Y-m-d') }}</cbc:EndDate>
                                {% endif %}
                                {% if atr.duracion %}
                                    <cbc:DurationMeasure unitCode="DAY">{{ atr.duracion }}</cbc:DurationMeasure>
                                {% endif %}
                            </cac:UsabilityPeriod>
                        {% endif %}
                    </cac:AdditionalItemProperty>
                {% endfor %}
            {% endif %}
        </cac:Item>
        <cac:Price>
            <cbc:PriceAmount currencyID="{{ doc.tipoMoneda }}">{{ detail.mtoValorUnitario|n_format_limit(10) }}</cbc:PriceAmount>
        </cac:Price>
    </cac:DebitNoteLine>
   {% endfor %}
</DebitNote>
{% endapply %}
````

### B.summary — `xml/src/Xml/Templates/summary.xml.twig`

````twig
{% apply spaceless %}
<?xml version="1.0" encoding="utf-8"?>
<SummaryDocuments xmlns="urn:sunat:names:specification:ubl:peru:schema:xsd:SummaryDocuments-1" xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2" xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xmlns:ext="urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2" xmlns:sac="urn:sunat:names:specification:ubl:peru:schema:xsd:SunatAggregateComponents-1">
    <ext:UBLExtensions>
        <ext:UBLExtension>
            <ext:ExtensionContent/>
        </ext:UBLExtension>
    </ext:UBLExtensions>
    <cbc:UBLVersionID>2.0</cbc:UBLVersionID>
    <cbc:CustomizationID>1.1</cbc:CustomizationID>
    <cbc:ID>{{ doc.xmlId }}</cbc:ID>
    <cbc:ReferenceDate>{{ doc.fecGeneracion|date('Y-m-d') }}</cbc:ReferenceDate>
    <cbc:IssueDate>{{ doc.fecResumen|date('Y-m-d') }}</cbc:IssueDate>
    {% set emp = doc.company %}
    <cac:Signature>
        <cbc:ID>SIGN{{ emp.ruc }}</cbc:ID>
        <cac:SignatoryParty>
            <cac:PartyIdentification>
                <cbc:ID>{{ emp.ruc }}</cbc:ID>
            </cac:PartyIdentification>
            <cac:PartyName>
                <cbc:Name><![CDATA[{{ emp.razonSocial|raw }}]]></cbc:Name>
            </cac:PartyName>
        </cac:SignatoryParty>
        <cac:DigitalSignatureAttachment>
            <cac:ExternalReference>
                <cbc:URI>#GREENTER-SIGN</cbc:URI>
            </cac:ExternalReference>
        </cac:DigitalSignatureAttachment>
    </cac:Signature>
    <cac:AccountingSupplierParty>
        <cbc:CustomerAssignedAccountID>{{ emp.ruc }}</cbc:CustomerAssignedAccountID>
        <cbc:AdditionalAccountID>6</cbc:AdditionalAccountID>
        <cac:Party>
            <cac:PartyLegalEntity>
                <cbc:RegistrationName><![CDATA[{{ emp.razonSocial|raw }}]]></cbc:RegistrationName>
            </cac:PartyLegalEntity>
        </cac:Party>
    </cac:AccountingSupplierParty>
    {% for det in doc.details %}
    <sac:SummaryDocumentsLine>
        <cbc:LineID>{{ loop.index }}</cbc:LineID>
        <cbc:DocumentTypeCode>{{ det.tipoDoc }}</cbc:DocumentTypeCode>
        <cbc:ID>{{ det.serieNro }}</cbc:ID>
        <cac:AccountingCustomerParty>
            <cbc:CustomerAssignedAccountID>{{ det.clienteNro }}</cbc:CustomerAssignedAccountID>
            <cbc:AdditionalAccountID>{{ det.clienteTipo }}</cbc:AdditionalAccountID>
        </cac:AccountingCustomerParty>
        {% if det.docReferencia %}
        <cac:BillingReference>
            <cac:InvoiceDocumentReference>
                <cbc:ID>{{ det.docReferencia.nroDoc }}</cbc:ID>
                <cbc:DocumentTypeCode>{{ det.docReferencia.tipoDoc }}</cbc:DocumentTypeCode>
            </cac:InvoiceDocumentReference>
        </cac:BillingReference>
        {% endif %}
        {% if det.percepcion %}
        {% set perc = det.percepcion %}
        <sac:SUNATPerceptionSummaryDocumentReference>
            <sac:SUNATPerceptionSystemCode>{{ perc.codReg }}</sac:SUNATPerceptionSystemCode>
            <sac:SUNATPerceptionPercent>{{ perc.tasa|n_format }}</sac:SUNATPerceptionPercent>
            <cbc:TotalInvoiceAmount currencyID="PEN">{{ perc.mto|n_format }}</cbc:TotalInvoiceAmount>
            <sac:SUNATTotalCashed currencyID="PEN">{{ perc.mtoTotal|n_format }}</sac:SUNATTotalCashed>
            <cbc:TaxableAmount currencyID="PEN">{{ perc.mtoBase|n_format }}</cbc:TaxableAmount>
        </sac:SUNATPerceptionSummaryDocumentReference>
        {% endif %}
        <cac:Status>
            <cbc:ConditionCode>{{ det.estado }}</cbc:ConditionCode>
        </cac:Status>
        <sac:TotalAmount currencyID="{{ doc.moneda }}">{{ det.total|n_format }}</sac:TotalAmount>
        {% if det.mtoOperGravadas is not null  %}
        <sac:BillingPayment>
            <cbc:PaidAmount currencyID="{{ doc.moneda }}">{{ det.mtoOperGravadas|n_format }}</cbc:PaidAmount>
            <cbc:InstructionID>01</cbc:InstructionID>
        </sac:BillingPayment>
        {% endif %}
        {% if det.mtoOperExoneradas  %}
        <sac:BillingPayment>
            <cbc:PaidAmount currencyID="{{ doc.moneda }}">{{ det.mtoOperExoneradas|n_format }}</cbc:PaidAmount>
            <cbc:InstructionID>02</cbc:InstructionID>
        </sac:BillingPayment>
        {% endif %}
        {% if det.mtoOperInafectas %}
        <sac:BillingPayment>
            <cbc:PaidAmount currencyID="{{ doc.moneda }}">{{ det.mtoOperInafectas|n_format }}</cbc:PaidAmount>
            <cbc:InstructionID>03</cbc:InstructionID>
        </sac:BillingPayment>
        {% endif %}
        {% if det.mtoOperExportacion %}
        <sac:BillingPayment>
            <cbc:PaidAmount currencyID="{{ doc.moneda }}">{{ det.mtoOperExportacion|n_format }}</cbc:PaidAmount>
            <cbc:InstructionID>04</cbc:InstructionID>
        </sac:BillingPayment>
        {% endif %}
        {% if det.mtoOperGratuitas %}
        <sac:BillingPayment>
            <cbc:PaidAmount currencyID="{{ doc.moneda }}">{{ det.mtoOperGratuitas|n_format }}</cbc:PaidAmount>
            <cbc:InstructionID>05</cbc:InstructionID>
        </sac:BillingPayment>
        {% endif %}
        {% if det.mtoOtrosCargos %}
        <cac:AllowanceCharge>
            <cbc:ChargeIndicator>true</cbc:ChargeIndicator>
            <cbc:Amount currencyID="{{ doc.moneda }}">{{ det.mtoOtrosCargos|n_format }}</cbc:Amount>
        </cac:AllowanceCharge>
        {% endif %}
        {% if det.mtoIvap %}
        {% set ivap = det.mtoIvap|n_format %}
        <cac:TaxTotal>
            <cbc:TaxAmount currencyID="{{ doc.moneda }}">{{ ivap }}</cbc:TaxAmount>
            <cac:TaxSubtotal>
                <cbc:TaxAmount currencyID="{{ doc.moneda }}">{{ ivap }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>1016</cbc:ID>
                        <cbc:Name>IVAP</cbc:Name>
                        <cbc:TaxTypeCode>VAT</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        </cac:TaxTotal>
        {% else %}
        {% set igv = det.mtoIGV|n_format %}
        <cac:TaxTotal>
            <cbc:TaxAmount currencyID="{{ doc.moneda }}">{{ igv }}</cbc:TaxAmount>
            <cac:TaxSubtotal>
                <cbc:TaxAmount currencyID="{{ doc.moneda }}">{{ igv }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    {% if det.porcentajeIgv is not null %}
                        <cbc:Percent>{{ det.porcentajeIgv|n_format }}</cbc:Percent>
                    {% endif %}
                    <cac:TaxScheme>
                        <cbc:ID>1000</cbc:ID>
                        <cbc:Name>IGV</cbc:Name>
                        <cbc:TaxTypeCode>VAT</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        </cac:TaxTotal>
        {% endif %}
        {% if det.mtoISC %}
        {% set isc = det.mtoISC|n_format %}
        <cac:TaxTotal>
            <cbc:TaxAmount currencyID="{{ doc.moneda }}">{{ isc }}</cbc:TaxAmount>
            <cac:TaxSubtotal>
                <cbc:TaxAmount currencyID="{{ doc.moneda }}">{{isc }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>2000</cbc:ID>
                        <cbc:Name>ISC</cbc:Name>
                        <cbc:TaxTypeCode>EXC</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        </cac:TaxTotal>
        {% endif %}
        {% if det.mtoOtrosTributos %}
        {% set oth = det.mtoOtrosTributos|n_format %}
        <cac:TaxTotal>
            <cbc:TaxAmount currencyID="{{ doc.moneda }}">{{ oth }}</cbc:TaxAmount>
            <cac:TaxSubtotal>
                <cbc:TaxAmount currencyID="{{ doc.moneda }}">{{ oth }}</cbc:TaxAmount>
                <cac:TaxCategory>
                    <cac:TaxScheme>
                        <cbc:ID>9999</cbc:ID>
                        <cbc:Name>OTROS</cbc:Name>
                        <cbc:TaxTypeCode>OTH</cbc:TaxTypeCode>
                    </cac:TaxScheme>
                </cac:TaxCategory>
            </cac:TaxSubtotal>
        </cac:TaxTotal>
        {% endif %}
        {% if det.mtoIcbper %}
            {% set icbper = det.mtoIcbper|n_format %}
            <cac:TaxTotal>
                <cbc:TaxAmount currencyID="{{ doc.moneda }}">{{ icbper }}</cbc:TaxAmount>
                <cac:TaxSubtotal>
                    <cbc:TaxAmount currencyID="{{ doc.moneda }}">{{ icbper }}</cbc:TaxAmount>
                    <cac:TaxCategory>
                        <cac:TaxScheme>
                            <cbc:ID>7152</cbc:ID>
                            <cbc:Name>ICBPER</cbc:Name>
                            <cbc:TaxTypeCode>OTH</cbc:TaxTypeCode>
                        </cac:TaxScheme>
                    </cac:TaxCategory>
                </cac:TaxSubtotal>
            </cac:TaxTotal>
        {% endif %}
    </sac:SummaryDocumentsLine>
    {% endfor %}
</SummaryDocuments>
{% endapply %}
````

### B.voided — `xml/src/Xml/Templates/voided.xml.twig`

````twig
{% apply spaceless %}
<?xml version="1.0" encoding="utf-8"?>
<VoidedDocuments xmlns="urn:sunat:names:specification:ubl:peru:schema:xsd:VoidedDocuments-1" xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2" xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2" xmlns:ext="urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2" xmlns:sac="urn:sunat:names:specification:ubl:peru:schema:xsd:SunatAggregateComponents-1" xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
    <ext:UBLExtensions>
        <ext:UBLExtension>
            <ext:ExtensionContent/>
        </ext:UBLExtension>
    </ext:UBLExtensions>
    <cbc:UBLVersionID>2.0</cbc:UBLVersionID>
    <cbc:CustomizationID>1.0</cbc:CustomizationID>
    <cbc:ID>{{ doc.xmlId }}</cbc:ID>
    <cbc:ReferenceDate>{{ doc.fecGeneracion|date('Y-m-d') }}</cbc:ReferenceDate>
    <cbc:IssueDate>{{ doc.fecComunicacion|date('Y-m-d') }}</cbc:IssueDate>
    {% set emp = doc.company %}
    <cac:Signature>
        <cbc:ID>SIGN{{ emp.ruc }}</cbc:ID>
        <cac:SignatoryParty>
            <cac:PartyIdentification>
                <cbc:ID>{{ emp.ruc }}</cbc:ID>
            </cac:PartyIdentification>
            <cac:PartyName>
                <cbc:Name><![CDATA[{{ emp.razonSocial|raw }}]]></cbc:Name>
            </cac:PartyName>
        </cac:SignatoryParty>
        <cac:DigitalSignatureAttachment>
            <cac:ExternalReference>
                <cbc:URI>#GREENTER-SIGN</cbc:URI>
            </cac:ExternalReference>
        </cac:DigitalSignatureAttachment>
    </cac:Signature>
    <cac:AccountingSupplierParty>
        <cbc:CustomerAssignedAccountID>{{ emp.ruc }}</cbc:CustomerAssignedAccountID>
        <cbc:AdditionalAccountID>6</cbc:AdditionalAccountID>
        <cac:Party>
            <cac:PartyLegalEntity>
                <cbc:RegistrationName><![CDATA[{{ emp.razonSocial|raw }}]]></cbc:RegistrationName>
            </cac:PartyLegalEntity>
        </cac:Party>
    </cac:AccountingSupplierParty>
    {% for det in doc.details %}
    <sac:VoidedDocumentsLine>
        <cbc:LineID>{{ loop.index }}</cbc:LineID>
        <cbc:DocumentTypeCode>{{ det.tipoDoc }}</cbc:DocumentTypeCode>
        <sac:DocumentSerialID>{{ det.serie }}</sac:DocumentSerialID>
        <sac:DocumentNumberID>{{ det.correlativo }}</sac:DocumentNumberID>
        <sac:VoidReasonDescription><![CDATA[{{ det.desMotivoBaja|raw }}]]></sac:VoidReasonDescription>
    </sac:VoidedDocumentsLine>
    {% endfor %}
</VoidedDocuments>
{% endapply %}
````

### B.despatch2022 — `xml/src/Xml/Templates/despatch2022.xml.twig`

````twig
{% apply spaceless %}
<?xml version="1.0" encoding="utf-8"?>
<DespatchAdvice xmlns="urn:oasis:names:specification:ubl:schema:xsd:DespatchAdvice-2" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2" xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2" xmlns:ext="urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2">
	<ext:UBLExtensions>
		<ext:UBLExtension>
			<ext:ExtensionContent/>
		</ext:UBLExtension>
	</ext:UBLExtensions>
	<cbc:UBLVersionID>2.1</cbc:UBLVersionID>
	<cbc:CustomizationID>2.0</cbc:CustomizationID>
	<cbc:ID>{{ doc.serie }}-{{ doc.correlativo }}</cbc:ID>
	<cbc:IssueDate>{{ doc.fechaEmision|date('Y-m-d') }}</cbc:IssueDate>
	<cbc:IssueTime>{{ doc.fechaEmision|date('H:i:s') }}</cbc:IssueTime>
	<cbc:DespatchAdviceTypeCode listAgencyName="PE:SUNAT" listName="Tipo de Documento" listURI="urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo01">{{ doc.tipoDoc }}</cbc:DespatchAdviceTypeCode>
    {% if doc.observacion %}
	<cbc:Note><![CDATA[{{ doc.observacion|raw }}]]></cbc:Note>
    {% endif %}
	{% for rel in doc.addDocs  %}
	<cac:AdditionalDocumentReference>
		<cbc:ID>{{ rel.nro }}</cbc:ID>
		<cbc:DocumentTypeCode listAgencyName="PE:SUNAT" listName="Documento relacionado al transporte" listURI="urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo61">{{ rel.tipo }}</cbc:DocumentTypeCode>
		<cbc:DocumentType>{{ rel.tipoDesc }}</cbc:DocumentType>
		{% if rel.emisor %}
		<cac:IssuerParty>
			<cac:PartyIdentification>
				<cbc:ID schemeID="6" schemeName="Documento de Identidad" schemeAgencyName="PE:SUNAT" schemeURI="urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo06">{{ rel.emisor }}</cbc:ID>
			</cac:PartyIdentification>
		</cac:IssuerParty>
		{% endif %}
	</cac:AdditionalDocumentReference>
    {% endfor %}
    {% set emp = doc.company %}
	<cac:Signature>
		<cbc:ID>SIGN{{ emp.ruc }}</cbc:ID>
		<cac:SignatoryParty>
			<cac:PartyIdentification>
				<cbc:ID>{{ emp.ruc }}</cbc:ID>
			</cac:PartyIdentification>
			<cac:PartyName>
				<cbc:Name><![CDATA[{{ emp.razonSocial|raw }}]]></cbc:Name>
			</cac:PartyName>
		</cac:SignatoryParty>
		<cac:DigitalSignatureAttachment>
			<cac:ExternalReference>
				<cbc:URI>#GREENTER-SIGN</cbc:URI>
			</cac:ExternalReference>
		</cac:DigitalSignatureAttachment>
	</cac:Signature>
	<cac:DespatchSupplierParty>
		<cac:Party>
			<cac:PartyIdentification>
				<cbc:ID schemeID="6" schemeName="Documento de Identidad" schemeAgencyName="PE:SUNAT" schemeURI="urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo06">{{ emp.ruc }}</cbc:ID>
			</cac:PartyIdentification>
			<cac:PartyLegalEntity>
				<cbc:RegistrationName><![CDATA[{{ emp.razonSocial|raw }}]]></cbc:RegistrationName>
			</cac:PartyLegalEntity>
		</cac:Party>
	</cac:DespatchSupplierParty>
	<cac:DeliveryCustomerParty>
		<cac:Party>
			<cac:PartyIdentification>
				<cbc:ID schemeID="{{ doc.destinatario.tipoDoc }}" schemeName="Documento de Identidad" schemeAgencyName="PE:SUNAT" schemeURI="urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo06">{{ doc.destinatario.numDoc }}</cbc:ID>
			</cac:PartyIdentification>
			<cac:PartyLegalEntity>
				<cbc:RegistrationName><![CDATA[{{ doc.destinatario.rznSocial|raw }}]]></cbc:RegistrationName>
			</cac:PartyLegalEntity>
		</cac:Party>
	</cac:DeliveryCustomerParty>
	{% if doc.comprador %}
	<cac:BuyerCustomerParty>
		<cac:Party>
			<cac:PartyIdentification>
				<cbc:ID schemeID="{{ doc.comprador.tipoDoc }}" schemeName="Documento de Identidad" schemeAgencyName="PE:SUNAT" schemeURI="urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo06">{{ doc.comprador.numDoc }}</cbc:ID>
			</cac:PartyIdentification>
			<cac:PartyLegalEntity>
				<cbc:RegistrationName><![CDATA[{{ doc.comprador.rznSocial|raw }}]]></cbc:RegistrationName>
			</cac:PartyLegalEntity>
		</cac:Party>
	</cac:BuyerCustomerParty>
	{% endif %}
	{% if doc.tercero %}
	<cac:SellerSupplierParty>
		<cac:Party>
			<cac:PartyIdentification>
				<cbc:ID schemeID="{{ doc.tercero.tipoDoc }}" schemeName="Documento de Identidad" schemeAgencyName="PE:SUNAT" schemeURI="urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo06">{{ doc.tercero.numDoc }}</cbc:ID>
			</cac:PartyIdentification>
			<cac:PartyLegalEntity>
				<cbc:RegistrationName><![CDATA[{{ doc.tercero.rznSocial|raw }}]]></cbc:RegistrationName>
			</cac:PartyLegalEntity>
		</cac:Party>
	</cac:SellerSupplierParty>
	{% endif %}
    {% set envio = doc.envio %}
	<cac:Shipment>
		<cbc:ID>SUNAT_Envio</cbc:ID>
		<cbc:HandlingCode listAgencyName="PE:SUNAT" listName="Motivo de traslado" listURI="urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo20">{{ envio.codTraslado }}</cbc:HandlingCode>
        {% if envio.desTraslado %}
		<cbc:HandlingInstructions>{{ envio.desTraslado }}</cbc:HandlingInstructions>
		{% endif %}
		{% if envio.sustentoPeso %}
		<cbc:Information>{{ envio.sustentoPeso }}</cbc:Information>
		{% endif %}
		<cbc:GrossWeightMeasure unitCode="{{ envio.undPesoTotal }}">{{ envio.pesoTotal|n_format(3) }}</cbc:GrossWeightMeasure>
		{% if envio.pesoItems %}
		<cbc:NetWeightMeasure unitCode="KGM">{{ envio.pesoItems|n_format(3) }}</cbc:NetWeightMeasure>
		{% endif %}
        {% if envio.numBultos %}
		<cbc:TotalTransportHandlingUnitQuantity>{{ envio.numBultos }}</cbc:TotalTransportHandlingUnitQuantity>
		{% endif %}
		{% for indicador in envio.indicadores %}
		<cbc:SpecialInstructions>{{ indicador }}</cbc:SpecialInstructions>
		{% endfor %}
		<cac:ShipmentStage>
			<cbc:TransportModeCode listName="Modalidad de traslado" listAgencyName="PE:SUNAT" listURI="urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo18">{{ envio.modTraslado }}</cbc:TransportModeCode>
			{% if envio.fecTraslado %}
			<cac:TransitPeriod>
				<cbc:StartDate>{{ envio.fecTraslado|date('Y-m-d') }}</cbc:StartDate>
			</cac:TransitPeriod>
			{% endif %}
            {% if envio.transportista %}
			<cac:CarrierParty>
				<cac:PartyIdentification>
					<cbc:ID schemeID="{{ envio.transportista.tipoDoc }}">{{ envio.transportista.numDoc }}</cbc:ID>
				</cac:PartyIdentification>
				<cac:PartyLegalEntity>
					<cbc:RegistrationName><![CDATA[{{ envio.transportista.rznSocial|raw }}]]></cbc:RegistrationName>
					<cbc:CompanyID>{{ envio.transportista.nroMtc }}</cbc:CompanyID>
				</cac:PartyLegalEntity>
			</cac:CarrierParty>
            {% endif %}
			{% for chofer in envio.choferes %}
			<cac:DriverPerson>
				<cbc:ID schemeID="{{ chofer.tipoDoc }}" schemeName="Documento de Identidad" schemeAgencyName="PE:SUNAT" schemeURI="urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo06">{{ chofer.nroDoc }}</cbc:ID>
				<cbc:FirstName>{{ chofer.nombres }}</cbc:FirstName>
				<cbc:FamilyName>{{ chofer.apellidos }}</cbc:FamilyName>
				<cbc:JobTitle>{{ chofer.tipo }}</cbc:JobTitle>
				<cac:IdentityDocumentReference>
					<cbc:ID>{{ chofer.licencia }}</cbc:ID>
				</cac:IdentityDocumentReference>
			</cac:DriverPerson>
			{% endfor %}
		</cac:ShipmentStage>
		<cac:Delivery>
			<cac:DeliveryAddress>
				<cbc:ID schemeAgencyName="PE:INEI" schemeName="Ubigeos">{{ envio.llegada.ubigueo }}</cbc:ID>
				{% if envio.llegada.codLocal %}
				<cbc:AddressTypeCode listID="{{ envio.llegada.ruc }}">{{ envio.llegada.codLocal }}</cbc:AddressTypeCode>
				{% endif %}
				<cac:AddressLine>
					<cbc:Line>{{ envio.llegada.direccion }}</cbc:Line>
				</cac:AddressLine>
			</cac:DeliveryAddress>
			<cac:Despatch>
				<cac:DespatchAddress>
					<cbc:ID schemeAgencyName="PE:INEI" schemeName="Ubigeos">{{ envio.partida.ubigueo }}</cbc:ID>
					{% if envio.partida.codLocal %}
					<cbc:AddressTypeCode listID="{{ envio.partida.ruc }}">{{ envio.partida.codLocal }}</cbc:AddressTypeCode>
					{% endif %}
					<cac:AddressLine>
						<cbc:Line>{{ envio.partida.direccion }}</cbc:Line>
					</cac:AddressLine>
				</cac:DespatchAddress>
			</cac:Despatch>
		</cac:Delivery>
		{% for precinto in envio.contenedores %}
		<cac:TransportHandlingUnit>
			<cac:Package>
				<cbc:ID>{{ loop.index }}</cbc:ID>
				<cbc:TraceID>{{ precinto }}</cbc:TraceID>
			</cac:Package>
		</cac:TransportHandlingUnit>
		{% endfor %}
		{% if envio.vehiculo %}
		<cac:TransportHandlingUnit>
			<cac:TransportEquipment>
				<cbc:ID>{{ envio.vehiculo.placa }}</cbc:ID>
				{% if envio.vehiculo.nroCirculacion %}
				<cac:ApplicableTransportMeans>
					<cbc:RegistrationNationalityID>{{ envio.vehiculo.nroCirculacion }}</cbc:RegistrationNationalityID>
				</cac:ApplicableTransportMeans>
				{% endif %}
				{% for sec in envio.vehiculo.secundarios %}
				<cac:AttachedTransportEquipment>
					<cbc:ID>{{ sec.placa }}</cbc:ID>
					{% if sec.nroCirculacion %}
					<cac:ApplicableTransportMeans>
						<cbc:RegistrationNationalityID>{{ sec.nroCirculacion }}</cbc:RegistrationNationalityID>
					</cac:ApplicableTransportMeans>
					{% endif %}
					{% if sec.nroAutorizacion %}
					<cac:ShipmentDocumentReference>
						<cbc:ID schemeID="{{ sec.codEmisor }}" schemeName="Entidad Autorizadora" schemeAgencyName="PE:SUNAT">{{ sec.nroAutorizacion }}</cbc:ID>
					</cac:ShipmentDocumentReference>
					{% endif %}
				</cac:AttachedTransportEquipment>
				{% endfor %}
				{% if envio.vehiculo.nroAutorizacion %}
				<cac:ShipmentDocumentReference>
					<cbc:ID schemeID="{{ envio.vehiculo.codEmisor }}" schemeName="Entidad Autorizadora" schemeAgencyName="PE:SUNAT">{{ envio.vehiculo.nroAutorizacion }}</cbc:ID>
				</cac:ShipmentDocumentReference>
				{% endif %}
			</cac:TransportEquipment>
		</cac:TransportHandlingUnit>
		{% endif %}
        {% if envio.puerto %}
		<cac:FirstArrivalPortLocation>
			<cbc:ID schemeAgencyName="PE:SUNAT" schemeName="Puertos" schemeURI="urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo63">{{ envio.puerto.codigo }}</cbc:ID>
			<cbc:LocationTypeCode>1</cbc:LocationTypeCode>
			<cbc:Name>{{ envio.puerto.nombre }}</cbc:Name>
		</cac:FirstArrivalPortLocation>
        {% elseif envio.aeropuerto %}
		<cac:FirstArrivalPortLocation>
			<cbc:ID schemeAgencyName="PE:SUNAT" schemeName="Aeropuertos" schemeURI="urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo64">{{ envio.aeropuerto.codigo }}</cbc:ID>
			<cbc:LocationTypeCode>2</cbc:LocationTypeCode>
			<cbc:Name>{{ envio.aeropuerto.nombre }}</cbc:Name>
		</cac:FirstArrivalPortLocation>
		{% endif %}
	</cac:Shipment>
    {% for det in doc.details %}
	<cac:DespatchLine>
		<cbc:ID>{{ loop.index }}</cbc:ID>
		<cbc:DeliveredQuantity unitCode="{{ det.unidad }}">{{ det.cantidad|n_format_limit(10) }}</cbc:DeliveredQuantity>
		<cac:OrderLineReference>
			<cbc:LineID>{{ loop.index }}</cbc:LineID>
		</cac:OrderLineReference>
		<cac:Item>
			<cbc:Description><![CDATA[{{ det.descripcion|raw }}]]></cbc:Description>
			<cac:SellersItemIdentification>
				<cbc:ID>{{ det.codigo }}</cbc:ID>
			</cac:SellersItemIdentification>
			{% if det.codProdSunat %}
			<cac:CommodityClassification>
				<cbc:ItemClassificationCode listID="UNSPSC" listAgencyName="GS1 US" listName="Item Classification">{{ det.codProdSunat }}</cbc:ItemClassificationCode>
			</cac:CommodityClassification>
			{% endif %}
			{% for atr in det.atributos %}
			<cac:AdditionalItemProperty >
				<cbc:Name>{{ atr.name }}</cbc:Name>
				<cbc:NameCode>{{ atr.code }}</cbc:NameCode>
				{% if atr.value %}
					<cbc:Value>{{ atr.value }}</cbc:Value>
				{% endif %}
			</cac:AdditionalItemProperty>
			{% endfor %}
		</cac:Item>
	</cac:DespatchLine>
    {% endfor %}
</DespatchAdvice>
{% endapply %}

````

### B.retention — `xml/src/Xml/Templates/retention.xml.twig`

````twig
{% apply spaceless %}
<?xml version="1.0" encoding="utf-8"?>
<Retention xmlns="urn:sunat:names:specification:ubl:peru:schema:xsd:Retention-1" xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2" xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xmlns:ext="urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2" xmlns:sac="urn:sunat:names:specification:ubl:peru:schema:xsd:SunatAggregateComponents-1">
    <ext:UBLExtensions>
        <ext:UBLExtension>
            <ext:ExtensionContent/>
        </ext:UBLExtension>
    </ext:UBLExtensions>
    <cbc:UBLVersionID>2.0</cbc:UBLVersionID>
    <cbc:CustomizationID>1.0</cbc:CustomizationID>
    {% set emp = doc.company %}
    <cac:Signature>
        <cbc:ID>SIGN{{ emp.ruc }}</cbc:ID>
        <cac:SignatoryParty>
            <cac:PartyIdentification>
                <cbc:ID>{{ emp.ruc }}</cbc:ID>
            </cac:PartyIdentification>
            <cac:PartyName>
                <cbc:Name><![CDATA[{{ emp.razonSocial|raw }}]]></cbc:Name>
            </cac:PartyName>
        </cac:SignatoryParty>
        <cac:DigitalSignatureAttachment>
            <cac:ExternalReference>
                <cbc:URI>#GREENTER-SIGN</cbc:URI>
            </cac:ExternalReference>
        </cac:DigitalSignatureAttachment>
    </cac:Signature>
    <cbc:ID>{{ doc.serie }}-{{ doc.correlativo }}</cbc:ID>
    <cbc:IssueDate>{{ doc.fechaEmision|date('Y-m-d') }}</cbc:IssueDate>
    <cbc:IssueTime>{{ doc.fechaEmision|date('H:i:s') }}</cbc:IssueTime>
    <cac:AgentParty>
        <cac:PartyIdentification>
            <cbc:ID schemeID="6">{{ emp.ruc }}</cbc:ID>
        </cac:PartyIdentification>
        <cac:PartyName>
            <cbc:Name><![CDATA[{{ emp.nombreComercial|raw }}]]></cbc:Name>
        </cac:PartyName>
        {% set addr = emp.address %}
        <cac:PostalAddress>
            <cbc:ID>{{ addr.ubigueo }}</cbc:ID>
            <cbc:StreetName><![CDATA[{{ addr.direccion|raw }}]]></cbc:StreetName>
            <cbc:CityName>{{ addr.departamento }}</cbc:CityName>
            <cbc:CountrySubentity>{{ addr.provincia }}</cbc:CountrySubentity>
            <cbc:District>{{ addr.distrito }}</cbc:District>
            <cac:Country>
                <cbc:IdentificationCode>{{ addr.codigoPais }}</cbc:IdentificationCode>
            </cac:Country>
        </cac:PostalAddress>
        <cac:PartyLegalEntity>
            <cbc:RegistrationName><![CDATA[{{ emp.razonSocial|raw }}]]></cbc:RegistrationName>
        </cac:PartyLegalEntity>
    </cac:AgentParty>
    <cac:ReceiverParty>
        <cac:PartyIdentification>
            <cbc:ID schemeID="{{ doc.proveedor.tipoDoc }}">{{ doc.proveedor.numDoc }}</cbc:ID>
        </cac:PartyIdentification>
        <cac:PartyLegalEntity>
            <cbc:RegistrationName><![CDATA[{{ doc.proveedor.rznSocial }}]]></cbc:RegistrationName>
        </cac:PartyLegalEntity>
    </cac:ReceiverParty>
    <sac:SUNATRetentionSystemCode>{{ doc.regimen }}</sac:SUNATRetentionSystemCode>
    <sac:SUNATRetentionPercent>{{ doc.tasa|n_format }}</sac:SUNATRetentionPercent>
    {% if doc.observacion %}
    <cbc:Note><![CDATA[{{ doc.observacion|raw }}]]></cbc:Note>
    {% endif %}
    <cbc:TotalInvoiceAmount currencyID="PEN">{{ doc.impRetenido|n_format }}</cbc:TotalInvoiceAmount>
    <sac:SUNATTotalPaid currencyID="PEN">{{ doc.impPagado|n_format }}</sac:SUNATTotalPaid>
    {% for det in doc.details %}
    <sac:SUNATRetentionDocumentReference>
        <cbc:ID schemeID="{{ det.tipoDoc }}">{{ det.numDoc }}</cbc:ID>
        <cbc:IssueDate>{{ det.fechaEmision|date('Y-m-d') }}</cbc:IssueDate>
        <cbc:TotalInvoiceAmount currencyID="{{ det.moneda }}">{{ det.impTotal|n_format }}</cbc:TotalInvoiceAmount>
        {% if det.pagos %}
        {% for pay in det.pagos %}
        <cac:Payment>
            <cbc:ID>{{ loop.index }}</cbc:ID>
            <cbc:PaidAmount currencyID="{{ pay.moneda }}">{{ pay.importe|n_format }}</cbc:PaidAmount>
            <cbc:PaidDate>{{ pay.fecha|date('Y-m-d') }}</cbc:PaidDate>
        </cac:Payment>
        {% endfor %}
        {% endif %}
        {% if det.impRetenido and det.impPagar and det.fechaRetencion %}
        <sac:SUNATRetentionInformation>
            <sac:SUNATRetentionAmount currencyID="PEN">{{ det.impRetenido|n_format }}</sac:SUNATRetentionAmount>
            <sac:SUNATRetentionDate>{{ det.fechaRetencion|date('Y-m-d') }}</sac:SUNATRetentionDate>
            <sac:SUNATNetTotalPaid currencyID="PEN">{{ det.impPagar|n_format }}</sac:SUNATNetTotalPaid>
            {% if det.tipoCambio %}
            <cac:ExchangeRate>
                <cbc:SourceCurrencyCode>{{ det.tipoCambio.monedaRef }}</cbc:SourceCurrencyCode>
                <cbc:TargetCurrencyCode>{{ det.tipoCambio.monedaObj }}</cbc:TargetCurrencyCode>
                <cbc:CalculationRate>{{ det.tipoCambio.factor|n_format(6)}}</cbc:CalculationRate>
                <cbc:Date>{{ det.tipoCambio.fecha|date('Y-m-d') }}</cbc:Date>
            </cac:ExchangeRate>
            {% endif %}
        </sac:SUNATRetentionInformation>
        {% endif %}
    </sac:SUNATRetentionDocumentReference>
    {% endfor %}
</Retention>
{% endapply %}
````

### B.perception — `xml/src/Xml/Templates/perception.xml.twig`

````twig
{% apply spaceless %}
<?xml version="1.0" encoding="utf-8"?>
<Perception xmlns="urn:sunat:names:specification:ubl:peru:schema:xsd:Perception-1" xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2" xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" xmlns:ext="urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2" xmlns:sac="urn:sunat:names:specification:ubl:peru:schema:xsd:SunatAggregateComponents-1">
	<ext:UBLExtensions>
		<ext:UBLExtension>
			<ext:ExtensionContent/>
		</ext:UBLExtension>
	</ext:UBLExtensions>
	<cbc:UBLVersionID>2.0</cbc:UBLVersionID>
	<cbc:CustomizationID>1.0</cbc:CustomizationID>
    {% set emp = doc.company %}
	<cac:Signature>
		<cbc:ID>SIGN{{ emp.ruc }}</cbc:ID>
		<cac:SignatoryParty>
			<cac:PartyIdentification>
				<cbc:ID>{{ emp.ruc }}</cbc:ID>
			</cac:PartyIdentification>
			<cac:PartyName>
				<cbc:Name><![CDATA[{{ emp.razonSocial|raw }}]]></cbc:Name>
			</cac:PartyName>
		</cac:SignatoryParty>
		<cac:DigitalSignatureAttachment>
			<cac:ExternalReference>
				<cbc:URI>#GREENTER-SIGN</cbc:URI>
			</cac:ExternalReference>
		</cac:DigitalSignatureAttachment>
	</cac:Signature>
	<cbc:ID>{{ doc.serie }}-{{ doc.correlativo }}</cbc:ID>
	<cbc:IssueDate>{{ doc.fechaEmision|date('Y-m-d') }}</cbc:IssueDate>
	<cbc:IssueTime>{{ doc.fechaEmision|date('H:i:s') }}</cbc:IssueTime>
	<cac:AgentParty>
		<cac:PartyIdentification>
			<cbc:ID schemeID="6">{{ emp.ruc }}</cbc:ID>
		</cac:PartyIdentification>
		<cac:PartyName>
			<cbc:Name><![CDATA[{{ emp.nombreComercial|raw }}]]></cbc:Name>
		</cac:PartyName>
        {% set addr = emp.address %}
		<cac:PostalAddress>
			<cbc:ID>{{ addr.ubigueo }}</cbc:ID>
			<cbc:StreetName><![CDATA[{{ addr.direccion|raw }}]]></cbc:StreetName>
			<cbc:CityName>{{ addr.departamento }}</cbc:CityName>
			<cbc:CountrySubentity>{{ addr.provincia }}</cbc:CountrySubentity>
			<cbc:District>{{ addr.distrito }}</cbc:District>
			<cac:Country>
				<cbc:IdentificationCode>{{ addr.codigoPais }}</cbc:IdentificationCode>
			</cac:Country>
		</cac:PostalAddress>
		<cac:PartyLegalEntity>
			<cbc:RegistrationName><![CDATA[{{ emp.razonSocial|raw }}]]></cbc:RegistrationName>
		</cac:PartyLegalEntity>
	</cac:AgentParty>
	<cac:ReceiverParty>
		<cac:PartyIdentification>
			<cbc:ID schemeID="{{ doc.proveedor.tipoDoc }}">{{ doc.proveedor.numDoc }}</cbc:ID>
		</cac:PartyIdentification>
		<cac:PartyLegalEntity>
			<cbc:RegistrationName><![CDATA[{{ doc.proveedor.rznSocial|raw }}]]></cbc:RegistrationName>
		</cac:PartyLegalEntity>
	</cac:ReceiverParty>
	<sac:SUNATPerceptionSystemCode>{{ doc.regimen }}</sac:SUNATPerceptionSystemCode>
	<sac:SUNATPerceptionPercent>{{ doc.tasa|n_format }}</sac:SUNATPerceptionPercent>
    {% if doc.observacion %}
	<cbc:Note><![CDATA[{{ doc.observacion|raw }}]]></cbc:Note>
	{% endif %}
	<cbc:TotalInvoiceAmount currencyID="PEN">{{ doc.impPercibido|n_format }}</cbc:TotalInvoiceAmount>
	<sac:SUNATTotalCashed currencyID="PEN">{{ doc.impCobrado|n_format }}</sac:SUNATTotalCashed>
	{% for det in doc.details %}
	<sac:SUNATPerceptionDocumentReference>
		<cbc:ID schemeID="{{ det.tipoDoc }}">{{ det.numDoc }}</cbc:ID>
		<cbc:IssueDate>{{ det.fechaEmision|date('Y-m-d') }}</cbc:IssueDate>
		<cbc:TotalInvoiceAmount currencyID="{{ det.moneda }}">{{ det.impTotal|n_format }}</cbc:TotalInvoiceAmount>
		{% if det.cobros %}
        {% for cob in det.cobros %}
		<cac:Payment>
			<cbc:ID>{{ loop.index }}</cbc:ID>
			<cbc:PaidAmount currencyID="{{ cob.moneda }}">{{ cob.importe|n_format }}</cbc:PaidAmount>
			<cbc:PaidDate>{{ cob.fecha|date('Y-m-d') }}</cbc:PaidDate>
		</cac:Payment>
        {% endfor %}
		{% endif %}
		{% if det.impPercibido and det.impCobrar and det.fechaPercepcion %}
		<sac:SUNATPerceptionInformation>
			<sac:SUNATPerceptionAmount currencyID="PEN">{{ det.impPercibido|n_format }}</sac:SUNATPerceptionAmount>
			<sac:SUNATPerceptionDate>{{ det.fechaPercepcion|date('Y-m-d') }}</sac:SUNATPerceptionDate>
			<sac:SUNATNetTotalCashed currencyID="PEN">{{ det.impCobrar|n_format }}</sac:SUNATNetTotalCashed>
            {% if det.tipoCambio %}
			<cac:ExchangeRate>
				<cbc:SourceCurrencyCode>{{ det.tipoCambio.monedaRef }}</cbc:SourceCurrencyCode>
				<cbc:TargetCurrencyCode>{{ det.tipoCambio.monedaObj }}</cbc:TargetCurrencyCode>
				<cbc:CalculationRate>{{ det.tipoCambio.factor|n_format(6)}}</cbc:CalculationRate>
				<cbc:Date>{{ det.tipoCambio.fecha|date('Y-m-d') }}</cbc:Date>
			</cac:ExchangeRate>
            {% endif %}
		</sac:SUNATPerceptionInformation>
		{% endif %}
	</sac:SUNATPerceptionDocumentReference>
	{% endfor %}
</Perception>
{% endapply %}
````

## Anexo C — Filtros y función de tributos (literales)

### `xml/src/Xml/Filter/FormatFilter.php`

```php
<?php

namespace Greenter\Xml\Filter;

/**
 * Class FormatFilter.
 * @internal
 */
class FormatFilter
{
    public function number($number, $decimals = 2): ?string
    {
        return number_format($number, $decimals, '.', '');
    }

    public function numberLimit($number, $decimals): ?string
    {
        $numString = (string)$number;
        $applyFormat = $this->getDecimalsLength($numString) > $decimals || strpos($numString, "E") !== false;

        return $applyFormat ? $this->number($number, $decimals) : $number;
    }

    private function getDecimalsLength(string $number): int
    {
        $lasPosition = strrchr($number, '.');
        if ($lasPosition === false) {
            return 0;
        }

        return strlen(substr($lasPosition, 1));
    }
}

```

### `xml/src/Xml/Filter/TributoFunction.php`

```php
<?php
/**
 * Created by PhpStorm.
 * User: Soporte
 * Date: 11/10/2018
 * Time: 16:40
 */

namespace Greenter\Xml\Filter;

/**
 * Class TributoFunction
 * @internal
 */
class TributoFunction
{
    private static $tributos = [
        '1000' => ['VAT', 'IGV'],
        '1016' => ['VAT', 'IVAP'],
        '2000' => ['EXC', 'ISC'],
        '9995' => ['FRE', 'EXP'],
        '9996' => ['FRE', 'GRA'],
        '9997' => ['VAT', 'EXO'],
        '9998' => ['FRE', 'INA'],
        '9999' => ['OTH', 'OTROS'],
    ];

    public static function getByTributo(?string $code)
    {
        if (isset(self::$tributos[$code])) {
            $values = self::$tributos[$code];
            return [
              'id' => $code,
              'code' => $values[0],
              'name' => $values[1],
            ];
        }

        return null;
    }

    public static function getByAfectacion($afectacion)
    {
        $code = self::getCode($afectacion);

        return self::getByTributo($code);
    }

    private static function getCode($afectacion): string
    {
        $value = (int)$afectacion;

        switch ($value) {
            case 10: return '1000';
            case 17: return '1016';
            case 20: return '9997';
            case 30: return '9998';
            case 40: return '9995';
            default: return '9996';
        }
    }
}
```
