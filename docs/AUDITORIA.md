# Auditoría final (Fase 8)

Fecha: 2026-10-26. Alcance: criterios de aceptación del prompt (§15), seguridad (§8), robustez
(§9) y pruebas (§11). Cada punto indica cómo se verificó y dónde quedó la prueba.

## Criterios de aceptación

| Criterio | Estado | Evidencia |
|---|---|---|
| Todos los endpoints de API-PRO (menos notas de venta y cotizaciones) existen y responden igual | ✅ (SIRE pendiente, fase posterior acordada) | Rutas leídas del router real de API-PRO (204 bajo `/api`) contra el OpenAPI de esta API: no falta ninguna fuera de SIRE (25), cotizaciones (6), notas de venta (8) y `reportes/documentos-internos` (1). Respuestas comparadas con el oráculo: `FacturaBoletaOracleIT`, `Fase4OracleIT`, `Fase5OracleIT`, `Fase6OracleIT` (193 PDF), `Fase7bOracleIT` (118 pasos), más las pruebas de cada módulo. Diferencias intencionales en `DIFERENCIAS.md` |
| Los XML pasan las pruebas doradas y validan contra los XSD | ✅ con una limitación | Doradas: XML firmado idéntico al de API-PRO (fact_bol, fase4, fase5). XSD OASIS UBL 2.1 (`src/test/resources/xsd`): 31 Invoice, 6 CreditNote, 6 DebitNote, 14 DespatchAdvice (`UblXsdTest`) y la liquidación de compra contra `SelfBilledInvoice` (`LiquidacionCompraFlowIT`). Los esquemas SUNAT 2.0 (resumen, baja, retención, percepción) no están en la documentación disponible (decisión D6): esos XML se cubren con las doradas y con SUNAT beta |
| Firma verificable con una herramienta externa | ✅ | Cada XML que llega al SUNAT simulado se verifica con `javax.xml.crypto` del JDK (`DOMValidateContext`), implementación distinta de la que firma (Santuario); SUNAT beta aceptó todos los tipos |
| Emisión aceptada en SUNAT BETA para cada tipo soportado | ✅ | Factura y boleta (Fase 3); NC, ND, resumen diario y baja (Fase 4); retención, percepción y reversión (Fase 5, "otros CPE"); guías 09 y 31 en el sandbox GRE de Nubefact (Fase 5) |
| Liquidación de compra (04) aceptada en BETA, con reversión y reporte de retenciones | ✅ | L001-1 aceptada sin observaciones y su RR aceptada (Fase 5B); reporte en `GET /liquidaciones-compra/reporte-retenciones` |
| Ninguna credencial ni certificado en el código o en el repositorio | ✅ | Búsqueda de claves privadas, asignaciones de secretos y datos reales: solo los certificados autofirmados de prueba (`fixtures/certs`, permitidos en `.gitignore`). `.env` no está versionado. El RUC real de API-PRO se quitó de los anexos de la Fase 0 (sigue en el historial del commit `b67cca5`, ver abajo) |
| Correlativos sin duplicados bajo carga concurrente | ✅ | `AuditoriaIT`: 20 facturas simultáneas en la misma serie → correlativos 1..20 sin repetir (bloqueo de fila) |
| README, Swagger, .env.example y Postman completos | ✅ | README (instalación, variables, beta, operación, réplicas, respaldos, oráculo); Swagger en `/api/docs`; `.env.example` sin secretos; colección con 289 peticiones generada contra el OpenAPI (`tools/build-postman.js`) |
| Todas las pruebas en verde | ✅ | `docker compose run --rm mvn test` |

## Seguridad

| Punto | Resultado |
|---|---|
| Aislamiento entre empresas | `AuditoriaIT` recorre **todas** las rutas con `{id}` del OpenAPI (GET, PUT, POST, DELETE) con las credenciales de otra empresa e ids del demo: ninguna responde 2xx. Listados, panel, reportes y exportación tampoco muestran datos ajenos. `ConfiguracionIT` cubre sucursales y series |
| Logs | Ningún `log.*` imprime valores de contraseñas, tokens, claves SOL ni certificados (solo ids, tamaños y códigos); revisión de los logs reales del contenedor: 0 coincidencias de claves SOL, secretos, `Authorization`, `Bearer` o PEM |
| Errores al cliente | 500 genérico con `error_id`; el detalle solo con `APP_DEBUG=true` y nunca con `APP_ENV=production` (corrección de esta fase) |
| Inyección | SQL con parámetros; las únicas concatenaciones son constantes (tablas del enum, fragmentos fijos) y el orden de los listados usa lista blanca. Plantillas PDF fijas del classpath, todo con `th:text` (escapado), sin `th:utext` |
| Dependencias | OSV (osv.dev) sobre las 150 dependencias de ejecución: se aplicaron parches de Tomcat 10.1.61, Jackson 2.21.8, Netty 4.1.139, PostgreSQL JDBC 42.7.14, Log4j API 2.25.5 y commons-lang3 3.18.0 (Spring Boot 3.5.x ya no los trae). Quedan dos avisos que no aplican: Spring Framework 6.2 (SSE con fragmentos y `XsltView`, no se usan; corregido solo en 7.0.9) y OGNL (evasión de lista de bloqueo con expresiones del atacante; las plantillas son propias) |
| Contenedor | Usuario sin privilegios (`app`), `.dockerignore` excluye `.env`, Actuator expone solo `health` e `info` |
| Cabeceras, CORS, rate limit, idempotencia | Cubiertos desde la Fase 1 (`ApiPipelineIT`) |

## Pendientes y recomendaciones

1. **SIRE** (25 endpoints): fase posterior acordada.
2. **Historial de git**: el commit `b67cca5` contiene los anexos de la Fase 0 con el RUC real de
   API-PRO. Antes de publicar el repositorio conviene reescribir ese commit (o publicar uno nuevo
   desde cero).
3. **Esquemas SUNAT 2.0** (D6): si se consiguen, agregarlos a `src/test/resources/xsd` y extender
   `UblXsdTest`.
4. ~~**Spring Boot 3.5** ya no recibe parches de dependencias~~ — **Hecho 2026-11-23**: migrado a Spring Boot 4.2.0
   (Spring Framework 7.1, Jackson 3, Hibernate 7.4, Tomcat 11, Spring Security 7.2, Flyway 12, Testcontainers 2) y
   PostgreSQL 18; sin versiones fijadas a mano en el `pom.xml`. `PhpJson` fija los valores por defecto de Jackson 2
   (orden de propiedades, enums, `/`) para conservar la salida byte a byte. Repetir el análisis OSV periódicamente.
5. **Fase 4**: la prueba de paridad omite "Resúmenes del mes" porque el oráculo de entonces no
   emulaba `DATE_FORMAT`; el oráculo actual (`tools/oracle`) ya lo hace y la fixture se puede
   regenerar.

## Revisión 2026-11-24

Nueva comprobación de paridad de rutas contra la referencia actual, `Source-code/plataform-api-sunat` (copia de API-PRO
del 2026-11-23). Detalle ruta por ruta en `Docs/api-sunat-comparativa-panel-laravel.md` §1. El contenido de arriba
(2026-10-26) se conserva tal cual.

### Resultado

| Punto | Resultado |
|---|---|
| Rutas de la referencia | **214** en `routes/api.php` (`/api/v1`, `/api/sunat`, `/api/bridge`), sin contar `debug/lookup`. La auditoría anterior contó 204: la diferencia son las rutas nuevas de la referencia (fila siguiente) |
| Rutas nuevas en la referencia | **Liquidación de compra** (13 rutas, `routes/api.php:183–195`, `Api/V1/LiquidacionCompraController.php`) y **ubigeo** (3, `catalogos/ubigeo/{regiones\|provincias\|distritos}`, `api.php:130–132`). Spring ya las tenía, con las mismas rutas (`settlement/LiquidacionCompraController`, `catalog/UbigeoController`) |
| Rutas en Spring con el mismo método y ruta | **174** |
| Rutas que faltan en Spring | **40**, las mismas acordadas: SIRE 25, cotizaciones 6, notas de venta 8 y `reportes/documentos-internos` 1. No apareció ninguna faltante nueva |
| No portada a propósito | `GET debug/lookup` (`api.php:339`): solo existe con `APP_DEBUG` y muestra parte del token del proveedor |
| Diferencias de método o ruta | Ninguna |
| Diferencias de parámetros | Una nueva: `GET liquidaciones-compra` lleva `por_pagina` ≤ 0 a **1** en la referencia (`LiquidacionCompraController.php:70`) y a **15** en Spring (`SettlementEndpoints.java:368–370`). Registrada en `DIFERENCIAS.md` #149, a decidir. Filtros y `reporte-retenciones` coinciden |
| Diferencias de comportamiento vistas al leer el código de la liquidación | Estados de la reversión, nombre del webhook de aceptación y reintentos agotados (`DIFERENCIAS.md` #88, #89 y #89b, a decidir). `DIFERENCIAS.md` §87–89 decía "API-PRO no lo tiene": corregido |
| Panel de administración | Ya existe: API `/api/admin/**` en esta API y el frontend `Source-code/Admin-api-sunat-Frontend`; diseño en `Docs/api-sunat-panel-admin-diseno.md` |
| Plan del panel y cupo (§12 del diseño) | Al leer `Admin\TenantController::asignarPlan` y `Tenant::hasReachedDocumentLimit`: la referencia crea una suscripción `admin` al asignar el plan, pero nunca aplica `max_documents_month`. Aquí el plan del panel crea / conserva la suscripción y su cupo es el que se aplica; V9 no cambia el cupo de ninguna empresa. Registrado en `DIFERENCIAS.md` #151–#154 |

### Comprobaciones abiertas

1. **Liquidación de compra contra el oráculo**: pendiente. El módulo se escribió con las fuentes de SUNAT
   (`LIQUIDACION_COMPRA.md`) cuando la referencia no lo tenía, y su paridad (JSON, XML firmado y PDF) nunca se comparó.
   Solo hay pruebas de flujo propias (`LiquidacionCompraFlowIT`). Pasos en la sección siguiente.
2. **Límite del plan en el alias `/api/sunat/**`**: pendiente de una prueba. En la referencia, el grupo `/api/sunat`
   (`api.php:407`) solo lleva `resolve.tenant` y `throttle:api`, sin `check.limit:sunat`. En Spring, `/api/sunat/**`
   está en `WebMvcConfig.PROTECTED` y `POST /api/sunat/{facturas|boletas}` pasa por `SaleDocumentEndpoints.store`, que
   llama a `EmissionPolicy.deny` (`SaleDocumentEndpoints.java:75–77`). Por lectura del código, el límite sí se aplica
   (`DIFERENCIAS.md` #150), pero ninguna prueba lo cubre: `Fase6OracleIT` solo prueba los listados y el PDF del alias.
   Falta un caso de integración: empresa con el cupo del mes agotado → `POST /api/sunat/facturas` debe responder igual
   que `POST /api/v1/facturas`.

### Cómo comparar la liquidación de compra con el oráculo

Documentado, no ejecutado: necesita PHP y Docker. El oráculo (`tools/oracle/oracle.php` + `boot.php`, uso en el
README, sección "Oráculo de API-PRO") ejecuta un escenario JSON contra el código real de la referencia y guarda, por
cada paso, el HTTP y el cuerpo. Los PDF los guarda en base64 y los ZIP como lista de nombres con su SHA-1. Lo hace con
SQLite en memoria, colas y correo falsos, HTTP saliente simulado y reloj fijo.

1. **Copia de la referencia con dependencias.**
   - `Source-code/plataform-api-sunat` no tiene `vendor/` y el oráculo no debe escribir en ella. Copiarla a una
     carpeta fuera del proyecto y ejecutar ahí `composer install` (PHP 8.2 o superior según `composer.json`; el README
     pide 8.3).
   - Apuntar la variable `API_PRO` a esa copia. `boot.php` usa por defecto `C:/laragon/www/API-PRO`, que es la versión
     anterior, sin liquidación de compra.
2. **Rutas del certificado.**
   - Los escenarios guardados (`scenario_fase5.json` y los demás) traen `tenant.cert_pem` con una ruta absoluta
     antigua (`C:/laragon/www/API-SUNAT-MULTILENGUAJE/spring-boot/...`).
   - El escenario nuevo debe usar la ruta absoluta de `src/test/resources/fixtures/certs/cert-con-clave.pem` en esta
     carpeta.
3. **Escenario.**
   - Crear `tools/oracle/scenarios/liquidacion-compra.js`, siguiendo el modelo de `fase7b.js`, que escriba
     `src/test/resources/fixtures/oracle/scenario_lc.json`.
   - Datos base: `now` fijo, el `tenant` de `scenario_fase5.json` (RUC `20000000001`, `MODDATOS`, plan `business`) y
     `series: [{tipo: '04', serie: 'L001'}]`.
   - Pasos:
     - `POST /api/v1/liquidaciones-compra` con los casos de `LiquidacionCompraFlowIT` y `LIQUIDACION_COMPRA.md`, y las
       validaciones 422;
     - `GET` del listado con cada filtro y con `por_pagina=0` (#149), y del detalle;
     - pagos: `POST`, `GET` y `DELETE`;
     - `reporte-retenciones?periodo=AAAA-MM`;
     - reversión: validaciones de estado, plazo y pagos;
     - `pdf` en cada formato (`formato` / `format`) y `xml` / `cdr` (con un paso `file` si deben existir).
   - Como la cola es falsa, los estados SUNAT (`aceptado`, rechazado, con CDR) se ponen con pasos `sql`, como en
     `fase7b.js`.
4. **Generar la fixture**:
   `php tools/oracle/oracle.php src/test/resources/fixtures/oracle/scenario_lc.json src/test/resources/fixtures/oracle/lc.json`.
5. **Prueba de paridad.**
   - Crear `LiquidacionCompraOracleIT`: HTTP y JSON como en `Fase5OracleIT` (ids y hosts normalizados); texto del PDF
     y QR como en `Fase6OracleIT`.
   - Cada diferencia que aparezca se corrige o se registra en `DIFERENCIAS.md`, empezando por #88, #89, #89b y #149.
6. **XML firmado.**
   - El `oracle.php` actual **no guarda XML firmado**: no hay ningún `xml_signed` en el script, aunque `fact_bol.json`,
     `fase4.json` y `fase5.json` sí lo traen. No se encontró en `tools/oracle` el paso que los generó.
   - Para la liquidación hay que agregar al oráculo un tipo de paso que, para una liquidación guardada, arme el XML con
     `(new LiquidacionCompraXmlBuilder)->build($settlement, $tenant)` (lo que hace `SendLiquidacionCompraToSunat.php:58`)
     y lo firme con el certificado del tenant como lo hace `GreenterService::sendRawBill`, sin enviarlo.
   - Ese XML se guarda como `xml_signed` y se compara byte a byte, como en `Fase5OracleIT` (línea 162).
7. **Riesgo no verificado**: el oráculo corre las migraciones de la referencia en SQLite. No se comprobó que
   `database/migrations/2026_10_27_000001_create_purchase_settlements_table.php` y
   `2026_09_09_000000_create_ubigeo_tables.php` migren en SQLite. Si fallan, se ajustan como las funciones MySQL que ya
   emula `oracle.php`, sin tocar la copia.
