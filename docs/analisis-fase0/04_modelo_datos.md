# 04 — Modelo de datos final de API-PRO (Fase 0)

Fuente: `C:\laragon\www\API-PRO` (solo lectura). Esquema reconstruido aplicando en orden las **91 migraciones** de `database/migrations`.
Las referencias van como `archivo:línea`. Los nombres de migración se abrevian por su prefijo de fecha (p. ej. `2026_03_05_000001` = `2026_03_05_000001_create_invoices_table.php`).

Motor de producción actual: **MySQL** (`.env:28 DB_CONNECTION=mysql`, charset `utf8mb4`, collation `utf8mb4_unicode_ci`, `strict=true`, `config/database.php`). Hay bloques comentados para pgsql/sqlsrv (`.env:36`, `.env:44`). Algunas migraciones tienen ramas específicas por driver (MySQL/pgsql/sqlite), así que **el esquema final no es idéntico en todos los motores**; las diferencias se marcan con ⚠.

## 0. Convenciones de tipos Laravel → MySQL / PostgreSQL

| Blueprint Laravel | MySQL | PostgreSQL (lo que genera Laravel 12) |
|---|---|---|
| `id()` | `BIGINT UNSIGNED AUTO_INCREMENT PK` | `bigserial PK` (bigint + secuencia) |
| `foreignId()` / `unsignedBigInteger` | `BIGINT UNSIGNED` | `bigint` |
| `unsignedInteger` | `INT UNSIGNED` | `integer` (sin unsigned; tope 2 147 483 647) |
| `unsignedSmallInteger` | `SMALLINT UNSIGNED` (0..65535) | `smallint` (tope 32 767) |
| `unsignedTinyInteger` | `TINYINT UNSIGNED` | `smallint` |
| `string(n)` (por defecto n=255) | `VARCHAR(n)` | `varchar(n)` |
| `char(n)` | `CHAR(n)` | `char(n)` (bpchar, rellena con espacios) |
| `text` / `mediumText` / `longText` | `TEXT` / `MEDIUMTEXT` / `LONGTEXT` | `text` |
| `decimal(p,s)` | `DECIMAL(p,s)` | `numeric(p,s)` |
| `boolean` | `TINYINT(1)` | `boolean` |
| `json` | `JSON` | `json` (no `jsonb`; conviene pasar a `jsonb` si se consulta por clave) |
| `enum([...])` | `ENUM(...)` nativo | `varchar(255)` + `CHECK (col IN (...))` con nombre `{tabla}_{col}_check` |
| `timestamp` / `dateTime` | `TIMESTAMP` / `DATETIME` | `timestamp(0) without time zone` (ambos) |
| `timestamps()` | `created_at`, `updated_at` TIMESTAMP NULL | ídem, nullable |
| `softDeletes()` | `deleted_at` TIMESTAMP NULL | ídem |
| `binary` | `BLOB` | `bytea` |
| `->comment()` | `COMMENT` en la columna | `COMMENT ON COLUMN` |
| `useCurrent()` | `DEFAULT CURRENT_TIMESTAMP` | `DEFAULT CURRENT_TIMESTAMP` |

**Nombres de índices por defecto**: `{tabla}_{col1}_{col2}_{index|unique}` y FKs `{tabla}_{col}_foreign`. Más abajo se indica el nombre explícito cuando la migración lo define.
**No hay** `ON UPDATE CURRENT_TIMESTAMP` (ninguna migración usa `useCurrentOnUpdate`), ni índices FULLTEXT o espaciales, ni columnas generadas (búsqueda con grep en todas las migraciones).
⚠ **FKs e índices en PG**: MySQL/InnoDB crea un índice automáticamente en cada FK; PostgreSQL **no**. Las columnas `*_id` de las tablas de ítems (`invoice_items.invoice_id`, etc.) y `sucursal_id`/`client_id` quedan sin índice en PG si no se crean a mano. Recomendación: crearlos.

---

## 1. Línea de tiempo de las migraciones (resumen de efectos)

| # | Migración | Efecto |
|---|---|---|
| 1-3 | `0001_01_01_000000/1/2` | Framework: `users`, `password_reset_tokens`, `sessions`, `cache`, `cache_locks`, `jobs`, `job_batches`, `failed_jobs` |
| 4 | `2025_08_14_170933` | Columnas 2FA de Fortify en `users` |
| 5-11 | `2026_03_04_000001..7` | `tenants`, `clients`, `series`, **`documents`** (legacy), **`document_items`** (legacy), `dispatch_guides`, `api_logs` |
| 12 | `2026_03_04_133413` | `cod_local`, `xml_path`, `cdr_path`, `pdf_path` en documents y dispatch_guides |
| 13 | `2026_03_04_200001` | Índices: tenants(api_key,is_active), documents, dispatch_guides, api_logs(created_at) |
| 14 | `2026_03_04_200002` | **Particiona por mes (solo MySQL)** `documents` y `api_logs`; elimina FKs |
| 15-16 | `2026_03_04_221500/224200` | mto_oper_exportacion, mto_base_ivap y mto_ivap en documents |
| 17-26 | `2026_03_05_000001..10` | Tablas por tipo: invoices, boletas, credit_notes, debit_notes (+ items), summaries, voided_documents |
| 27 | `2026_03_05_000011` | Copia los datos de documents/document_items a las tablas por tipo |
| 28 | `2026_03_05_100000` | **DROP** `document_items` y `documents` (en pgsql con `CASCADE`) |
| 29-31 | `2026_03_05_120000/130000/130500` | `series.sucursal` → tabla `sucursales` → `sucursal_id` en series y comprobantes → renombre a `sucursal_nombre` |
| 32-33 | `2026_03_05_182154/200000` | Ubicación del tenant; credenciales GRE (client_id/secret) |
| 34-35 | `2026_03_09_000001/2` | `internal_documents` + items (NV/COT, **fuera de alcance**) |
| 36-37 | `2026_03_09_100001/2` | `payments` (polimórfica); `payment_status`/`monto_pagado` en invoices, boletas, internal_documents |
| 38 | `2026_03_09_200001` | Índices v2 |
| 39 | `2026_03_10_140826` | Enum sunat_status + `anulacion_en_proceso` (**solo MySQL**) |
| 40-41 | `2026_03_10_150523/183445` | summaries.cdr_path; dispatch_guides.sunat_code → varchar(20) |
| 42 | `2026_03_12_000001` | `plans`, `subscriptions`, `subscription_payments` + contadores de uso en tenants |
| 43-47 | `2026_03_15_*`, `2026_03_16_*` | Config de negocio del tenant; `descuentos` json en ítems; `mto_valor_unitario` → 18,10; `fecha_emision` → DATETIME |
| 48-49 | `2026_03_21_*` | Boletas: JSON avanzados + exportación/IVAP (igualan a invoices) |
| 50-51 | `2026_04_10_*` | `retentions`, `retention_items`, `perceptions`, `perception_items` |
| 52-53 | `2026_04_17_*` | dispatch_guides: indicadores, observacion, partida/llegada ruc + cod_local |
| 54-59 | `2026_04_18_10000x` | SIRE: periodos, tickets, comprobantes, upload_files, reconciliation_logs + flags en tenants |
| 60-62 | `2026_04_18_120000/130000/140000` | tax_regime, igv_rate_override, nrus_categoria; campos GRT (guía transportista) |
| 63-65 | `2026_04_19_*`, `2026_04_29_*` | max_documents_month pasa a signed; enums de suscripción → varchar; razon_social nullable |
| 66 | `2026_05_14_000001` | CHECK de sunat_status + `anulacion_en_proceso` (**solo pgsql**) |
| 67-71 | `2026_06_27`…`2026_07_13_000003` | fecha_entrega_transportista; users.is_admin; `settings`; emission_mode; plans.is_unlimited/duration_days |
| 72-78 | `2026_07_30_00000x` | `unspsc_codes`, cod_producto_sunat en ítems, obligado_codigo_producto, ND doc_afectado nullable, contrato_colaboracion, `unspsc_taxonomy`, users.role/is_active |
| 79-81 | `2026_07_30_140000/140100/160000` | UNIQUE summaries(tenant,identifier) (condicional); poll tracking; normaliza tax_regime |
| 82-85 | `2026_08_2x` | `tenant_user` (RBAC) + backfill; consulta_token; motivo/anulado_por; voided.cdr_path; favicon |
| 86-87 | `2026_09_09_*` | `ubigeo_regiones/provincias/distritos`; consulta_proveedor |
| 88-91 | `2026_10_03_*` | **Cifra `tenants.api_secret`** (→ TEXT); `idempotency_keys`; índices de reportes; `audit_logs` |

### Tablas legacy eliminadas (NO replicar)
- `documents` y `document_items`: creadas en `2026_03_04_000004` y `000005`, migradas por `2026_03_05_000011` y eliminadas en `2026_03_05_100000_drop_legacy_documents_tables.php:14-19`. Las migraciones posteriores que las tocan llevan `Schema::hasTable('documents')`, así que sobre una base nueva no dejan rastro.
- Consecuencia: `dispatch_guides.document_id` **sobrevive como columna huérfana sin FK**. En MySQL la FK se borró en `2026_03_04_200002:24`; en pgsql la borra el `DROP ... CASCADE`.

---

## 2. Tablas de dominio (alcance de la migración)

### 2.1 `tenants` (empresa emisora)
Creada en `2026_03_04_000001_create_tenants_table.php:11-33`. Es multi-tenant por columna `tenant_id` en el resto de tablas.

| Columna | Tipo final | Null | Default | Origen / notas |
|---|---|---|---|---|
| id | bigint PK autoinc | no | | 000001:12 |
| ruc | varchar(11) | no | | 000001:13 **UNIQUE** `tenants_ruc_unique` |
| razon_social | varchar(255) | **sí** | `''` | 000001:14; pasa a nullable con default '' en `2026_04_29_215621:15` |
| nombre_comercial | varchar(255) | sí | | 000001:15 |
| direccion | varchar(500) | sí | | 000001:16 |
| ubigeo | varchar(6) | sí | | 000001:17 |
| departamento / provincia / distrito | varchar(100) | sí | | `2026_03_05_182154:12-14` |
| telefonos, emails, cuentas_bancarias, billeteras_digitales | json | sí | | `2026_03_15_000001:12-15` (cast array) |
| mensaje_agradecimiento, mensaje_promocional | varchar(500) | sí | | `2026_03_15_000001:16-17` |
| sol_user | text | no | | 000001:18 — **cifrado** (cast `encrypted`, Tenant.php:84) |
| sol_pass | text | no | | 000001:19 — **cifrado** (Tenant.php:85) |
| client_id | varchar(100) | sí | | `2026_03_05_200000:12` comment "SUNAT GRE OAuth2 client_id…" (en claro) |
| client_secret | text | sí | | `2026_03_05_200000:14` — **cifrado** (Tenant.php:87) |
| certificate_path | varchar(500) | sí | | 000001:20 |
| certificate_password | text | sí | | 000001:21 — **cifrado** (Tenant.php:88) |
| environment | enum('beta','production') | no | 'beta' | 000001:22 |
| webhook_url | varchar(500) | sí | | 000001:23 |
| logo_path | varchar(500) | sí | | 000001:24 |
| favicon_path | varchar(255) | sí | | `2026_08_29_000001:17` |
| api_key | varchar(64) | no | | 000001:25 **UNIQUE**; lo genera `Str::random(64)` (Tenant.php:135) |
| api_secret | **text** | no | | 000001:26 varchar(64) → TEXT en `2026_10_03_120000:19`; valores existentes cifrados con `Crypt::encryptString` (:22-38). Cast `encrypted` (Tenant.php:92) |
| consulta_token | text | sí | | `2026_08_24_120000:14` — **cifrado** (Tenant.php:86) |
| consulta_proveedor | varchar(10) | no | 'json' | `2026_09_09_100000:14` |
| plan | enum('free','pro','business') | no | 'free' | 000001:27 (campo legacy; el plan real vive en subscriptions) |
| emission_mode | varchar(20) | no | 'plan' | `2026_07_13_000002:20`; valores `plan`/`unlimited` (Tenant.php:19-22) |
| tax_regime | varchar(32) | no | 'general' | `2026_04_18_120000:26`; normalizado a `general|mype|rer|rus` en `2026_07_30_160000:42-49` |
| obligado_codigo_producto | boolean | no | false | `2026_07_30_000003:20` |
| igv_rate_override | decimal(5,2) | sí | | `2026_04_18_120000:27` |
| nrus_categoria | tinyint unsigned | sí | | `2026_04_18_130000:25` |
| max_documents_month | **int (signed)** | no | 20 | 000001:28 unsigned → signed en `2026_04_19_160000:19` (-1 = ilimitado) |
| documents_this_month | int unsigned | no | 0 | `2026_03_12_000001:62` |
| ai_messages_this_month | int unsigned | no | 0 | `2026_03_12_000001:63` |
| usage_reset_month | varchar(7) | sí | | `2026_03_12_000001:64` ('YYYY-MM') |
| is_active | boolean | no | true | 000001:29 |
| sire_enabled | boolean | no | false | `2026_04_18_100006:13` |
| sire_last_period_synced | varchar(6) | sí | | `2026_04_18_100006:16` |
| sire_last_reconciliation_at | timestamp | sí | | `2026_04_18_100006:17` |
| sire_client_id | varchar(100) | sí | | `2026_04_18_100006:20` |
| sire_client_secret | **varchar(255)** | sí | | `2026_04_18_100006:21` — **cifrado** (Tenant.php:103) ⚠ ver Observaciones |
| user_id | bigint | sí | | 000001:30 FK → users.id **ON DELETE SET NULL** |
| created_at, updated_at, deleted_at | timestamp | sí | | 000001:31-32 (SoftDeletes) |

Índices: UNIQUE(ruc), UNIQUE(api_key), INDEX(api_key, is_active) `tenants_api_key_is_active_index` (`2026_03_04_200001:13`).

### 2.2 `clients`
`2026_03_04_000002_create_clients_table.php:11-24`

| Columna | Tipo | Null | Default | Notas |
|---|---|---|---|---|
| id | bigint PK | no | | |
| tenant_id | bigint | no | | FK tenants **CASCADE** (:13) |
| tipo_documento | varchar(1) | no | | catálogo 06 SUNAT (0,1,4,6,7,A…) |
| numero_documento | varchar(15) | no | | INDEX `clients_numero_documento_index` (:15) |
| razon_social | varchar(255) | no | | |
| nombre_comercial | varchar(255) | sí | | |
| direccion | varchar(500) | sí | | |
| email | varchar(255) | sí | | |
| telefono | varchar(20) | sí | | |
| created_at, updated_at | timestamp | sí | | |

UNIQUE `clients_tenant_doc_unique` (tenant_id, tipo_documento, numero_documento) (:23).

### 2.3 `sucursales`
`2026_03_05_130000_create_sucursales_table.php:11-25`

| Columna | Tipo | Null | Default |
|---|---|---|---|
| id | bigint PK | no | |
| tenant_id | bigint FK tenants **CASCADE** | no | |
| nombre | varchar(100) | no | |
| cod_local | varchar(4) | no | |
| direccion | varchar(500) | no | |
| ubigeo | varchar(6) | no | |
| telefono | varchar(20) | sí | |
| email | varchar(255) | sí | |
| is_principal | boolean | no | false |
| is_active | boolean | no | true |
| created_at, updated_at | timestamp | sí | |

UNIQUE `sucursales_tenant_cod_local_unique` (tenant_id, cod_local) (:24).
La misma migración agrega `sucursal_id` (bigint null, FK sucursales **SET NULL**) a `series` (:28-30) y a `invoices`, `boletas`, `credit_notes`, `debit_notes` y `dispatch_guides` (:33-41).

### 2.4 `series`
`2026_03_04_000003_create_series_table.php:11-21` + `2026_03_05_120000:12` + `2026_03_05_130000:29` + `2026_03_05_130500:12`

| Columna | Tipo | Null | Default | Notas |
|---|---|---|---|---|
| id | bigint PK | no | | |
| tenant_id | bigint FK tenants CASCADE | no | | |
| tipo_documento | varchar(2) | no | | '01','03','07','08','09','31','20','40' (Serie.php:16-37) |
| serie | varchar(4) | no | | |
| sucursal_nombre | varchar(100) | sí | | se creó como `sucursal` y luego se renombró (130500:12) |
| sucursal_id | bigint FK sucursales SET NULL | sí | | |
| correlativo | int unsigned | no | 0 | último número usado |
| is_active | boolean | no | true | |
| created_at, updated_at | timestamp | sí | | |

UNIQUE `series_tenant_tipo_serie_unique` (tenant_id, tipo_documento, serie) (:20).
Correlativo: `Serie::resolveCorrelativo()` usa `lockForUpdate` + `increment` dentro de una transacción (Serie.php:86-103). Esto hay que replicarlo con `SELECT … FOR UPDATE` en cada tecnología.

### 2.5 `invoices` (Factura 01) y `boletas` (Boleta 03): estructura final idéntica
- invoices: `2026_03_05_000001_create_invoices_table.php:11-79`
- boletas: `2026_03_05_000003_create_boletas_table.php:11-71`, más `2026_03_21_142856:12-16` (detraccion, percepcion, anticipos, descuentos_globales, extras) y `2026_03_21_144714:12-14` (mto_oper_exportacion, mto_base_ivap, mto_ivap)

| Columna | Tipo final | Null | Default | Origen / notas |
|---|---|---|---|---|
| id | bigint PK | no | | |
| tenant_id | bigint FK tenants **CASCADE** | no | | :13 |
| sucursal_id | bigint FK sucursales **SET NULL** | sí | | `2026_03_05_130000:38` |
| client_id | bigint FK clients **SET NULL** | sí | | :14 |
| serie | varchar(4) | no | | |
| correlativo | int unsigned | no | | |
| cod_local | varchar(4) | no | '0000' | |
| fecha_emision | **DATETIME** | no | | era `date`; `2026_03_16_200000:22` |
| fecha_vencimiento | date | sí | | |
| tipo_operacion | varchar(4) | no | '0101' | catálogo 51 |
| tipo_moneda | varchar(3) | no | 'PEN' | |
| forma_pago | varchar(20) | no | 'Contado' | Contado/Credito |
| client_tipo_doc | varchar(1) | no | | cliente desnormalizado |
| client_num_doc | varchar(15) | no | | |
| client_razon_social | varchar(255) | no | | |
| client_direccion | varchar(500) | sí | | |
| mto_oper_gravadas, mto_oper_exoneradas, mto_oper_inafectas, mto_oper_exportacion, mto_oper_gratuitas | decimal(12,2) | no | 0 | |
| mto_igv, mto_base_ivap, mto_ivap, mto_isc, mto_icbper | decimal(12,2) | no | 0 | |
| total_impuestos, valor_venta, sub_total, mto_imp_venta, total_anticipos, total_descuentos | decimal(12,2) | no | 0 | |
| leyenda | varchar(500) | sí | | |
| observacion | text | sí | | |
| payment_status | enum('pendiente','parcial','pagado') | no | 'pendiente' | `2026_03_09_100002:13` |
| monto_pagado | decimal(12,2) | no | 0 | `2026_03_09_100002:14` |
| cuotas, detraccion, percepcion, anticipos, descuentos_globales, guias, extras | json | sí | | cast `array` |
| xml_path, cdr_path, pdf_path | varchar(255) | sí | | (en documents eran de 500) |
| hash_cpe | varchar(255) | sí | | |
| sunat_status | enum('pendiente','enviado','aceptado','rechazado','anulado','anulacion_en_proceso') | no | 'pendiente' | :65; `anulacion_en_proceso` lo agregan `2026_03_10_140826:21` (MySQL) y `2026_05_14_000001:26-27` (pgsql, CHECK) |
| sunat_code | varchar(255) | sí | | |
| sunat_description | text | sí | | |
| sunat_notes | json | sí | | |
| ticket | varchar(255) | sí | | |
| sent_at | timestamp | sí | | |
| created_at, updated_at, deleted_at | timestamp | sí | | SoftDeletes |
| contrato_colaboracion | json | sí | | `2026_07_30_000005:20` (queda al final, no usa `after`) |

Índices de **invoices**:
- UNIQUE `invoices_tenant_id_serie_correlativo_unique` (tenant_id, serie, correlativo) (:75)
- `invoices_tenant_id_sunat_status_index`, `invoices_tenant_id_fecha_emision_index`, `invoices_tenant_id_client_num_doc_index` (:76-78)
- `inv_tenant_created` (tenant_id, created_at) (`2026_03_09_200001:25`)
- `inv_tenant_local_fecha` (tenant_id, cod_local, fecha_emision) y `inv_tenant_pago_venc` (tenant_id, payment_status, fecha_vencimiento) (`2026_10_03_130000:29-30`)

Índices de **boletas**: los mismos con prefijo `boletas_…` (:67-70), `bol_tenant_created` (`2026_03_09_200001:30`), `bol_tenant_local_fecha` y `bol_tenant_pago_venc` (`2026_10_03_130000:33-34`).

### 2.6 `credit_notes` (NC 07) y `debit_notes` (ND 08)
`2026_03_05_000005_create_credit_notes_table.php:11-72` y `2026_03_05_000007_create_debit_notes_table.php:11-72`. **No tienen**: fecha_vencimiento, tipo_operacion, forma_pago, mto_oper_exportacion, mto_base_ivap, mto_ivap, cuotas, detraccion, percepcion, anticipos, descuentos_globales, extras, payment_status ni monto_pagado.

| Columna | Tipo | Null | Default | Notas |
|---|---|---|---|---|
| id, tenant_id (FK CASCADE), sucursal_id (FK SET NULL), client_id (FK SET NULL) | bigint | | | igual que en invoices |
| serie varchar(4), correlativo int unsigned, cod_local varchar(4) default '0000' | | no | | |
| fecha_emision | DATETIME | no | | `2026_03_16_200000` |
| tipo_moneda | varchar(3) | no | 'PEN' | |
| client_tipo_doc(1), client_num_doc(15), client_razon_social(255), client_direccion(500) null | varchar | | | |
| doc_afectado_tipo | varchar(2) | **NC: no / ND: sí** | | ND nullable desde `2026_07_30_000004:17` |
| doc_afectado_serie | varchar(4) | NC: no / ND: sí | | `2026_07_30_000004:18` |
| doc_afectado_correlativo | varchar(10) | NC: no / ND: sí | | `2026_07_30_000004:19` |
| cod_motivo | varchar(2) | no | | catálogo 09 (NC) / 10 (ND) |
| des_motivo | varchar(255) | no | | |
| mto_oper_gravadas…total_descuentos (13 montos, sin exportación/IVAP) | decimal(12,2) | no | 0 | :33-45 |
| leyenda varchar(500) null; observacion text null; guias json null | | | | |
| xml_path, cdr_path, pdf_path, hash_cpe, sunat_code, ticket | varchar(255) | sí | | |
| sunat_status | enum(6 valores, igual que invoices) | no | 'pendiente' | |
| sunat_description text, sunat_notes json, sent_at timestamp | | sí | | |
| created_at, updated_at, deleted_at | timestamp | sí | | |
| contrato_colaboracion | json | sí | | `2026_07_30_000005` |

Índices: UNIQUE (tenant_id, serie, correlativo); (tenant_id, sunat_status); (tenant_id, fecha_emision); `cn_tenant_client_doc` / `dn_tenant_client_doc` (tenant_id, client_num_doc); `cn_tenant_created` / `dn_tenant_created` (tenant_id, created_at) (`2026_03_09_200001:13-20`).

### 2.7 Tablas de ítems: `invoice_items`, `boleta_items`, `credit_note_items`, `debit_note_items`
`2026_03_05_000002/4/6/8` (:11-30) + `2026_03_15_154921:15` + `2026_03_15_200000:15` + `2026_07_30_000002:19`

| Columna | Tipo final | Null | Default | Notas |
|---|---|---|---|---|
| id | bigint PK | no | | |
| invoice_id / boleta_id / credit_note_id / debit_note_id | bigint FK padre **CASCADE** | no | | ⚠ sin índice explícito (en PG crearlo) |
| codigo | varchar(50) | sí | | |
| cod_producto_sunat | char(8) | sí | | UNSPSC; `2026_07_30_000002:19` (sin FK a unspsc_codes) |
| descripcion | varchar(500) | no | | |
| unidad | varchar(5) | no | (sin default) | en document_items era 'NIU' |
| cantidad | decimal(12,4) | no | | |
| mto_valor_unitario | **decimal(18,10)** | no | 0 | `2026_03_15_200000:15` |
| mto_valor_venta, mto_base_igv | decimal(12,2) | no | 0 | |
| porcentaje_igv | decimal(5,2) | no | 18 | |
| igv | decimal(12,2) | no | 0 | |
| tip_afe_igv | varchar(2) | no | '10' | catálogo 07 |
| isc, icbper, total_impuestos | decimal(12,2) | no | 0 | |
| mto_precio_unitario | decimal(12,4) | no | 0 | |
| descuento | decimal(12,2) | no | 0 | |
| descuentos | json | sí | | `2026_03_15_154921:15` |
| created_at, updated_at | timestamp | sí | | |

### 2.8 `dispatch_guides` (GRE remitente 09 / transportista 31)
`2026_03_04_000006_create_dispatch_guides_table.php:11-58` más 9 migraciones posteriores.

| Columna | Tipo final | Null | Default | Origen |
|---|---|---|---|---|
| id | bigint PK | no | | |
| tenant_id | bigint FK tenants CASCADE | no | | :13 |
| sucursal_id | bigint FK sucursales SET NULL | sí | | `2026_03_05_130000:38` |
| cod_local | varchar(4) | no | '0000' | `2026_03_04_133413:21` |
| document_id | bigint | sí | | :14. **FK eliminada**, columna huérfana (ver §1) |
| tipo_documento | varchar(2) | no | '09' | `2026_04_18_140000:31` ('09' remitente, '31' transportista) |
| serie | varchar(4) | no | | |
| correlativo | int unsigned | no | | |
| fecha_emision | DATETIME | no | | `2026_03_16_200000` |
| destinatario_tipo_doc | varchar(1) | no | | |
| destinatario_num_doc | varchar(15) | no | | |
| destinatario_razon_social | varchar(255) | no | | |
| cod_traslado | varchar(2) | no | | catálogo 20 |
| mod_traslado | varchar(2) | no | | catálogo 18 |
| fecha_traslado | date | no | | |
| fecha_entrega_transportista | date | sí | | `2026_06_27_120000:12` |
| peso_total | decimal(12,3) | no | | |
| und_peso_total | varchar(3) | no | 'KGM' | |
| num_bultos | int unsigned | sí | | |
| remitente, doc_relacionado, datos_subcontratador, datos_pagador_flete, autorizacion_especial | json | sí | | `2026_04_18_140000:32-36` (GRT) |
| llegada_ubigeo | varchar(6) | no | | |
| llegada_direccion | varchar(500) | no | | |
| llegada_ruc | varchar(11) | sí | | `2026_04_17_211401:17` |
| llegada_cod_local | varchar(4) | sí | | `2026_04_17_211401:18` |
| partida_ubigeo | varchar(6) | no | | |
| partida_direccion | varchar(500) | no | | |
| partida_ruc | varchar(11) | sí | | `2026_04_17_211401:15` |
| partida_cod_local | varchar(4) | sí | | `2026_04_17_211401:16` |
| transportista, vehiculo, conductor | json | sí | | |
| indicadores | json | sí | | `2026_04_17_210643:15` |
| observacion | varchar(500) | sí | | `2026_04_17_210643:16` |
| items | json | **no** | | los ítems de la guía van en JSON, no hay tabla hija |
| xml_content | longtext | sí | | |
| xml_path | varchar(500) | sí | | `2026_03_04_133413:22` |
| cdr_content | binary (BLOB / bytea) | sí | | |
| cdr_path, pdf_path | varchar(500) | sí | | `2026_03_04_133413:23-24` |
| hash_cpe | varchar(100) | sí | | |
| sunat_status | enum('pendiente','enviado','aceptado','rechazado') | no | 'pendiente' | :48. **Sin** anulado ni anulacion_en_proceso |
| sunat_code | varchar(20) | sí | | 10 → 20 en `2026_03_10_183445:12` |
| sunat_description | text | sí | | |
| ticket | varchar(100) | sí | | |
| sent_at | timestamp | sí | | |
| created_at, updated_at, deleted_at | timestamp | sí | | |

Índices: UNIQUE `dispatch_guides_tenant_serie_corr_unique` (tenant_id, serie, correlativo) (:57, **no incluye tipo_documento**); (tenant_id, sunat_status), (tenant_id, fecha_emision) (`2026_03_04_200001:27-28`); `dg_tenant_created` (`2026_03_09_200001:35`); `dg_tenant_dest_doc` (tenant_id, destinatario_num_doc) (`2026_10_03_130000:37`).

### 2.9 `summaries` (Resumen diario RC)
`2026_03_05_000009:11-31` + `2026_03_10_150523:15` + `2026_07_30_140000:49` + `2026_07_30_140100:21-26` + `2026_08_24_130000:22-23`

| Columna | Tipo | Null | Default | Notas |
|---|---|---|---|---|
| id | bigint PK | no | | |
| tenant_id | bigint FK CASCADE | no | | |
| identifier | varchar(255) | no | | p. ej. RC-20260305-001 |
| correlativo | varchar(5) | no | | |
| fecha_referencia | date | no | | |
| fecha_envio | date | no | | |
| total_documentos | int unsigned | no | 0 | |
| tipo | enum('envio','anulacion') | no | | |
| document_ids | json | sí | | |
| motivo | varchar(255) | sí | | `2026_08_24_130000:22` |
| anulado_por | bigint unsigned | sí | | id de usuario, **sin FK** |
| xml_path | varchar(255) | sí | | |
| cdr_path | varchar(255) | sí | | `2026_03_10_150523:15` |
| ticket | varchar(255) | sí | | |
| last_polled_at | timestamp | sí | | `2026_07_30_140100:21` |
| poll_attempts | smallint unsigned | no | 0 | `2026_07_30_140100:22` |
| sunat_status | enum('pendiente','enviado','aceptado','rechazado') | no | **'enviado'** | |
| sunat_code | varchar(255) | sí | | |
| sunat_description | text | sí | | |
| sunat_notes | json | sí | | |
| created_at, updated_at | timestamp | sí | | |

Índices: (tenant_id, ticket), (tenant_id, fecha_referencia) (:29-30); UNIQUE `summaries_tenant_identifier_unique` (**condicional**: si hay duplicados la migración no lo crea y termina sin error, `2026_07_30_140000:36-45`); `summaries_poll_idx` (sunat_status, last_polled_at).

### 2.10 `voided_documents` (Comunicación de baja RA)
`2026_03_05_000010:11-30` + `2026_08_24_130000:17-18` + `2026_08_24_140000:16`

| Columna | Tipo | Null | Default |
|---|---|---|---|
| id | bigint PK | no | |
| tenant_id | FK CASCADE | no | |
| identifier | varchar(255) | no | |
| correlativo | varchar(5) | no | |
| fecha_generacion | date | no | |
| fecha_comunicacion | date | no | |
| total_documentos | int unsigned | no | 0 |
| detalles | json | sí | |
| motivo | varchar(255) | sí | |
| anulado_por | bigint unsigned (sin FK) | sí | |
| xml_path, cdr_path, ticket | varchar(255) | sí | |
| sunat_status | enum('pendiente','enviado','aceptado','rechazado') | no | 'enviado' |
| sunat_code varchar(255), sunat_description text, sunat_notes json | | sí | |
| created_at, updated_at | timestamp | sí | |

Índices: (tenant_id, ticket), (tenant_id, fecha_comunicacion). **No hay UNIQUE** en identifier.

### 2.11 `retentions` (CRE 20) y `retention_items`
`2026_04_10_100000_create_retentions_table.php:13-80`

| retentions | Tipo | Null | Default |
|---|---|---|---|
| id | bigint PK | no | |
| tenant_id | bigint unsigned, FK tenants **sin ON DELETE** (RESTRICT / NO ACTION) (:52) | no | |
| serie varchar(4), correlativo int unsigned | | no | |
| cod_local | varchar(4) | no | '0000' |
| fecha_emision | DATETIME | no | |
| proveedor_tipo_doc | varchar(2) | no | |
| proveedor_num_doc | varchar(15) | no | |
| proveedor_razon_social | **varchar(1500)** | no | |
| proveedor_direccion | varchar(500) | sí | |
| regimen | varchar(2) | no | |
| tasa | decimal(5,2) | no | |
| imp_retenido, imp_pagado | decimal(12,2) | no | 0 |
| observacion | text | sí | |
| xml_path, cdr_path, pdf_path, hash_cpe | varchar(255) | sí | |
| sunat_status | **varchar(30)** (no es enum) | no | 'pendiente' |
| sunat_code | varchar(20) | sí | |
| sunat_description | varchar(500) | sí | |
| sunat_notes | json | sí | |
| ticket | varchar(100) | sí | |
| sent_at, created_at, updated_at, deleted_at | timestamp | sí | |

Índices: (tenant_id, serie, correlativo) **NO único** (:53); (tenant_id, sunat_status).

| retention_items | Tipo | Null | Default |
|---|---|---|---|
| id | bigint PK | | |
| retention_id | FK retentions CASCADE | no | |
| tipo_doc | varchar(2) | no | |
| num_doc | varchar(20) | no | |
| fecha_emision_doc | date | no | |
| imp_total | decimal(12,2) | no | |
| moneda | varchar(3) | no | 'PEN' |
| pagos | json | **no** | |
| fecha_retencion | date | no | |
| imp_retenido, imp_pagar | decimal(12,2) | no | |
| tipo_cambio | json | sí | |
| created_at, updated_at | timestamp | sí | |

### 2.12 `perceptions` (CPE 40) y `perception_items`
`2026_04_10_100100_create_perceptions_table.php:13-80`. Estructura análoga a retentions con estos cambios: `cliente_tipo_doc` (2), `cliente_num_doc` (15), `cliente_razon_social` (1500), `cliente_direccion` (500, null), `imp_percibido` e `imp_cobrado` decimal(12,2) default 0. FK tenant_id sin ON DELETE (:52). Índices no únicos (tenant_id, serie, correlativo) y (tenant_id, sunat_status).
`perception_items`: perception_id FK CASCADE, tipo_doc(2), num_doc(20), fecha_emision_doc date, imp_total 12,2, moneda(3) 'PEN', **cobros json NOT NULL**, fecha_percepcion date, imp_percibido 12,2, imp_cobrar 12,2, tipo_cambio json null, timestamps.

### 2.13 `payments` (pagos de documentos, polimórfica)
`2026_03_09_100001_create_payments_table.php:11-25`

| Columna | Tipo | Null | Notas |
|---|---|---|---|
| id | bigint PK | no | |
| tenant_id | FK tenants CASCADE | no | |
| payable_type | varchar(255) | no | **FQCN de la clase PHP**: `App\Models\Invoice`, `App\Models\Boleta`, `App\Models\SaleNote` (no hay morphMap; PaymentController.php:18-20) |
| payable_id | bigint unsigned | no | sin FK (polimórfica) |
| metodo | varchar(20) | no | efectivo, yape, plin, transferencia, tarjeta, cheque, otro (Payment.php:11-19) |
| monto | decimal(12,2) | no | |
| referencia | varchar(100) | sí | |
| monto_recibido | decimal(12,2) | sí | solo efectivo |
| notas | varchar(255) | sí | |
| created_at, updated_at | timestamp | sí | |

Índices: (payable_type, payable_id), (tenant_id, created_at).
**Se mantiene, recortada**: también la usan facturas y boletas. Hay que quitar el tipo `notas-venta`/SaleNote. Ojo: si se quiere conservar compatibilidad de datos, los nuevos backends deben guardar/leer el string `App\Models\Invoice` tal cual, o migrar los datos a un alias (`invoice`/`boleta`).

### 2.14 Suscripciones: `plans`, `subscriptions`, `subscription_payments`
`2026_03_12_000001_create_subscription_tables.php`

**plans** (:11-22 + `2026_07_13_000003:18-19`)

| Columna | Tipo | Null | Default |
|---|---|---|---|
| id | bigint PK | | |
| slug | varchar(50) UNIQUE | no | |
| name | varchar(100) | no | |
| price_monthly, price_yearly | decimal(10,2) | no | 0 |
| limits | json | **no** | |
| features | json | **no** | |
| is_unlimited | boolean | no | false |
| duration_days | smallint unsigned | sí | |
| sort_order | tinyint unsigned | no | 0 |
| is_active | boolean | no | true |
| created_at, updated_at | | sí | |

**subscriptions** (:24-42 + `2026_04_19_180000:34-36`)

| Columna | Tipo final | Null | Default |
|---|---|---|---|
| id | bigint PK | | |
| tenant_id | FK tenants CASCADE | no | |
| plan_id | FK plans **RESTRICT** | no | |
| status | **varchar(20)** (antes enum) | no | 'trialing' |
| billing_cycle | **varchar(10)** (antes enum) | no | 'monthly' |
| trial_ends_at, current_period_start, current_period_end, cancelled_at | timestamp | sí | |
| payment_gateway | varchar(50) | no | 'culqi' |
| gateway_customer_id, gateway_card_id | varchar(100) | sí | |
| card_last_four | varchar(4) | sí | |
| card_brand | varchar(20) | sí | |
| created_at, updated_at | | sí | |

Índice (tenant_id, status). Valores válidos: trialing, active, past_due, cancelled, expired, pending (Subscription.php:12-26); monthly, yearly (:28-30).

**subscription_payments** (:44-58 + `2026_04_19_180000:41`): subscription_id FK CASCADE; amount decimal(10,2); currency varchar(3) 'PEN'; status **varchar(20)** default 'pending' (pending, completed, failed, refunded); gateway_charge_id varchar(100) null; gateway_response json null; paid_at timestamp null; period_start/period_end date null; timestamps. Índice (subscription_id, status).

### 2.15 `api_logs`
`2026_03_04_000007:11-25` + `2026_03_04_200001:33` + `2026_03_04_200002:62-88`

| Columna | Tipo | Null | Default | Notas |
|---|---|---|---|---|
| id | bigint autoinc | no | | ⚠ MySQL: PK compuesta (id, created_at) |
| tenant_id | bigint | sí | | FK SET NULL en pgsql/sqlite; ⚠ **en MySQL la FK se eliminó** (200002:64) |
| endpoint | varchar(255) | no | | |
| method | varchar(10) | no | | |
| request_body, response_body | json | sí | | |
| status_code | smallint unsigned | no | | |
| ip_address | varchar(45) | no | | |
| user_agent | varchar(500) | sí | | |
| duration_ms | int unsigned | sí | | |
| created_at | TIMESTAMP (pgsql) / **DATETIME** (MySQL) | no | CURRENT_TIMESTAMP | sin updated_at (`$timestamps=false`, ApiLog.php:12) |

Índices: (tenant_id, created_at); `api_logs_created_at_index`.

### 2.16 `idempotency_keys`
`2026_10_03_120100:16-27`: id; tenant_id bigint unsigned **sin FK**; idempotency_key varchar(255); request_hash varchar(64) (sha256 de method|path|body); response_status smallint unsigned null; response_body longtext null; created_at timestamp null (sin updated_at). UNIQUE (tenant_id, idempotency_key); INDEX(created_at) para la purga. No tiene modelo Eloquent (se usa `DB::table` desde el middleware `EnsureIdempotency`, `bootstrap/app.php:56`).

### 2.17 `audit_logs`
`2026_10_03_140000:17-40`: id; tenant_id bigint null INDEX (sin FK); user_id bigint null INDEX (sin FK); actor_tipo varchar(20) default 'sistema' (usuario/api/sistema); actor_etiqueta varchar(255) null; accion varchar(80) INDEX; subject_type varchar(255) null; subject_id **varchar(255)** null; descripcion varchar(255) null; ip varchar(45) null; user_agent varchar(512) null; metadata json null; created_at timestamp null INDEX (sin updated_at).

### 2.18 `settings`
`2026_07_13_000001:17-22`: id; key varchar(100) UNIQUE; value json null; timestamps. Configuración global key/value.

### 2.19 Catálogos UNSPSC
- **unspsc_codes** (`2026_07_30_000001:18-22`): `codigo char(8) PK`; descripcion varchar(255) no null; clase char(6) INDEX. Sin timestamps.
- **unspsc_taxonomy** (`2026_07_30_000006:15-22`): nivel varchar(10) (segmento/familia/clase); codigo varchar(6); nombre varchar(255); parent varchar(4) null INDEX; **PK compuesta (nivel, codigo)**.

### 2.20 Ubigeo
`2026_09_09_000000_create_ubigeo_tables.php:15-31` (sin FKs ni timestamps):
- `ubigeo_regiones`: id char(2) PK, nombre varchar(120)
- `ubigeo_provincias`: id char(4) PK, nombre varchar(120), region_id char(2) INDEX
- `ubigeo_distritos`: id char(6) PK, nombre varchar(120), provincia_id char(4) INDEX, region_id char(2) INDEX

### 2.21 SIRE (`app/Sire`)
- **sire_periodos** (`2026_04_18_100001:11-33`): tenant_id FK CASCADE; per_tributario varchar(6); cod_libro varchar(6) default '080000'; fase varchar(20) default 'propuesta'; cod_estado varchar(10) null; des_estado varchar(100) null; fec_cierre timestamp null; timestamps. UNIQUE `sire_per_unique` (tenant_id, per_tributario, cod_libro); INDEX (tenant_id, fase).
- **sire_tickets** (`2026_04_18_100002:11-54`): tenant_id FK CASCADE; num_ticket varchar(20); per_tributario(6); cod_proceso(10); des_proceso(200) null; cod_estado_proceso(10) null; des_estado_proceso(200) null; nom_archivo_importacion(200) null; nom_archivo_reporte(200) null; cod_tipo_archivo_reporte(10) null; cnt_filas_validadas, cnt_cp_informados, cnt_cp_error int unsigned null; archivo_local_path(500) null; poll_attempts int unsigned default 0; last_polled_at, finished_at timestamp null; sunat_request_payload, sunat_last_response json null; timestamps. UNIQUE `sire_tickets_unique` (tenant_id, num_ticket); `sire_tickets_estado_idx` (tenant_id, cod_estado_proceso); `sire_tickets_periodo_idx` (tenant_id, per_tributario, cod_proceso).
- **sire_comprobantes** (`2026_04_18_100003:11-63`): tenant_id FK CASCADE; sire_periodo_id FK sire_periodos CASCADE; origen_ticket_id FK sire_tickets SET NULL (null); per_tributario(6); fase(20); car_sunat(30) null; fec_emision date; fec_vencimiento date null; cod_tipo_cdp(2); num_serie_cdp(20); num_cdp(20); num_doc_proveedor(20); tipo_doc_proveedor(2); razon_social_proveedor(255); cod_moneda(3) 'PEN'; mto_bi_gravada, mto_igv, mto_bi_no_gravada decimal(15,2) default 0; mto_total decimal(15,2) NOT NULL; tipo_cambio decimal(10,4) null; cod_inconsistencia(10) null; incluido boolean default true; raw_line text null; timestamps. UNIQUE `sire_cp_unique` (tenant_id, per_tributario, car_sunat) (car_sunat es nullable: en MySQL y PG varios NULL no chocan); `sire_cp_fase_idx`; `sire_cp_proveedor_idx`.
- **sire_upload_files** (`2026_04_18_100004:11-30`): tenant_id FK CASCADE; sire_ticket_id FK SET NULL null; per_tributario(6); cod_proceso(10); nom_archivo(200); local_path(500); size_bytes bigint unsigned; sha256 varchar(64); uploaded_at timestamp **NOT NULL** (⚠ en MySQL strict un TIMESTAMP NOT NULL sin default exige que siempre se envíe valor); timestamps. INDEX `sire_uploads_periodo_idx`.
- **sire_reconciliation_logs** (`2026_04_18_100005:11-31`): tenant_id FK CASCADE; per_tributario(6); total_sunat, total_local, match_count, only_sunat_count, only_local_count, diff_amount_count int unsigned default 0; diff_total_monto decimal(15,2) default 0; details json null; run_at timestamp NOT NULL; timestamps. INDEX `sire_recon_periodo_idx`.

### 2.22 `users` y `tenant_user`
**users** (`0001_01_01_000000:14-22` + `2025_08_14_170933:15-17` + `2026_07_03_151900:12` + `2026_07_30_000007:21-22`)

| Columna | Tipo | Null | Default |
|---|---|---|---|
| id | bigint PK | | |
| name | varchar(255) | no | |
| email | varchar(255) UNIQUE | no | |
| email_verified_at | timestamp | sí | |
| password | varchar(255) | no | bcrypt (cast `hashed`) |
| is_admin | boolean | no | false |
| role | varchar(20) | sí | super_admin, admin, soporte, lectura, cliente (User.php:31-48). Backfill: is_admin=1 → 'super_admin' (`2026_07_30_000007:26`) |
| is_active | boolean | no | true |
| two_factor_secret | text | sí | (Fortify, cifrado) |
| two_factor_recovery_codes | text | sí | (Fortify, cifrado) |
| two_factor_confirmed_at | timestamp | sí | |
| remember_token | varchar(100) | sí | |
| created_at, updated_at | | sí | |

**tenant_user** (`2026_08_23_120000:21-31`): id; tenant_id FK CASCADE; user_id FK users CASCADE; role varchar(20) default 'cajero' (owner, cajero); abilities json null (solo cajero); is_active boolean default true; timestamps. UNIQUE `tenant_user_unique` (tenant_id, user_id). Backfill: un owner por cada tenant con user_id (:34-51).

---

## 3. Tablas de framework (Laravel), de replicación opcional

| Tabla | Migración | Uso en API-PRO | ¿Replicar? |
|---|---|---|---|
| `password_reset_tokens` (email PK, token, created_at) | `0001_01_01_000000:24-28` | Reset de contraseña del panel (Fortify) | Solo si se migra el panel |
| `sessions` (id varchar PK, user_id bigint null idx, ip_address(45), user_agent text, payload longtext, last_activity int idx) | `0001_01_01_000000:30-37` | `SESSION_DRIVER=database` (.env:51) | Solo si se usan sesiones web |
| `cache` (key PK, value mediumtext, expiration int idx), `cache_locks` (key PK, owner, expiration) | `0001_01_01_000001:14-24` | `CACHE_STORE=file` (.env:61), **sin uso** en la config actual | No (o usar Redis) |
| `jobs` (id, queue idx, payload longtext, attempts tinyint unsigned, reserved_at / available_at / created_at int unsigned) | `0001_01_01_000002:14-22` | `QUEUE_CONNECTION=database` (.env:59); envío asíncrono y polling | Cada stack usará su propio mecanismo de colas |
| `job_batches` | `0001_01_01_000002:24-35` | Batches de colas | No |
| `failed_jobs` (uuid UNIQUE, connection, queue, payload, exception, failed_at useCurrent) | `0001_01_01_000002:37-45` | `database-uuids` (config/queue.php:124); también se consulta desde app (3 referencias) | Opcional |

Laravel crea además la tabla `migrations` automáticamente.

---

## 4. Particionado por mes

- `2026_03_04_200002_partition_tables_by_month.php` **solo corre en MySQL** (:13).
  - `documents`: PARTITION BY RANGE (YEAR*100+MONTH) p202601..p202612 + p_future (:43-57). **Ya no existe**: la tabla se borra en `2026_03_05_100000`.
  - `api_logs`: **sigue particionada en MySQL**. `created_at` pasa a DATETIME, PK (id, created_at), PARTITION BY RANGE COLUMNS(created_at) p202601..p202612 + p_future (:66-88). Se elimina la FK a tenants (:64).
- Mantenimiento: el comando `partitions:create --months=3` (app/Console/Commands/ManagePartitions.php:10, hace `REORGANIZE PARTITION p_future`) se programa el día 1 de cada mes a las 02:00 (routes/console.php:12). `logs:purge --days=90` (PurgeOldApiLogs.php:10) hace DROP PARTITION si la tabla está particionada (semanal, routes/console.php:15).
- En pgsql y sqlite `api_logs` **no** queda particionada (PK simple, FK SET NULL, created_at timestamp).
- **Cómo llevarlo a PostgreSQL** (recomendado, porque `api_logs` crece rápido):
  ```sql
  CREATE TABLE api_logs (
    id bigint GENERATED BY DEFAULT AS IDENTITY,
    tenant_id bigint NULL,            -- sin FK (o FK, PG ≥12 permite FK desde tabla particionada)
    endpoint varchar(255) NOT NULL, method varchar(10) NOT NULL,
    request_body jsonb, response_body jsonb,
    status_code smallint NOT NULL, ip_address varchar(45) NOT NULL,
    user_agent varchar(500), duration_ms integer,
    created_at timestamp(0) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, created_at)
  ) PARTITION BY RANGE (created_at);
  CREATE TABLE api_logs_p202610 PARTITION OF api_logs FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
  CREATE TABLE api_logs_default PARTITION OF api_logs DEFAULT;   -- equivalente a p_future
  CREATE INDEX ON api_logs (tenant_id, created_at);
  ```
  Las particiones futuras se crean con un job mensual o con `pg_partman`; la purga se hace con `DETACH PARTITION` + `DROP TABLE`. En MySQL (la tecnología que usa MySQL) se puede replicar tal cual el DDL original.
  Alternativa simple: no particionar y purgar con `DELETE … WHERE created_at < now() - interval '90 days'`, apoyándose en el índice `created_at`.

---

## 5. Específico de MySQL que hay que adaptar a PostgreSQL

1. **ENUM nativos** que siguen vivos: `tenants.environment`, `tenants.plan`; `invoices`/`boletas`/`credit_notes`/`debit_notes`.`sunat_status` (6 valores); `invoices`/`boletas`.`payment_status`; `dispatch_guides.sunat_status` (4); `summaries.tipo`; `summaries.sunat_status` y `voided_documents.sunat_status` (4); `internal_documents.payment_status` (fuera de alcance). En PG: `varchar` + CHECK (como hace Laravel), o `CREATE TYPE`. Lo más portable es varchar + CHECK, o validar en la aplicación (es lo que hizo `2026_04_19_180000` con las suscripciones).
   - Añadir `anulacion_en_proceso` se hizo **dos veces**, una por motor: `2026_03_10_140826` (MySQL `MODIFY ENUM`) y `2026_05_14_000001` (pgsql CHECK `{tabla}_sunat_status_check`). Ambas solo para las 4 tablas de comprobantes.
2. **UNSIGNED**: PG no lo tiene. `int unsigned` → `integer` (o `bigint` si hay que conservar el rango), y se puede añadir `CHECK (col >= 0)` en correlativo y contadores. `smallint unsigned` (poll_attempts, status_code, response_status, duration_days) cabe en `smallint` o `integer`.
3. **JSON** → `json` en Laravel/PG; se recomienda `jsonb` (indexable y con operadores). Ninguna consulta usa `JSON_EXTRACT` en migraciones; revisar el código en la fase de servicios.
4. **DATETIME vs TIMESTAMP**: en PG ambos son `timestamp(0) without time zone`. Hay que definir la zona: la app trabaja en hora de Lima. Revisar `config/app.php timezone` en otra nota.
5. **BLOB** (`dispatch_guides.cdr_content`) → `bytea`. **LONGTEXT** (`xml_content`, `idempotency_keys.response_body`, framework) → `text`.
6. **Particiones** (api_logs): ver §4.
7. **FK implícitas con índice**: en PG hay que crear los índices de las columnas FK a mano (§0).
8. **`CAST(... AS UNSIGNED)`** en `InternalDocument::generateNumero` (InternalDocument.php:137) no es válido en PG. Afecta solo a NV/COT, que están fuera de alcance.
9. **Comentarios de columna** (`tenants.client_id`, `client_secret`): `COMMENT ON COLUMN`.
10. **Collation** `utf8mb4_unicode_ci` es case-insensitive en MySQL. En PG las comparaciones y los UNIQUE de `varchar` distinguen mayúsculas (p. ej. `users.email`, `clients.numero_documento`, `series.serie`). Usar `citext` o `lower()` si se quiere el mismo comportamiento.
11. **Orden de columnas** (`->after()`): en PG se ignora. Las columnas se crean en el orden de las migraciones (p. ej. `contrato_colaboracion` al final).
12. **`TIMESTAMP NOT NULL` sin default** (`sire_upload_files.uploaded_at`, `sire_reconciliation_logs.run_at`): en MySQL strict no hay problema mientras se envíe el valor; en PG tampoco.

---

## 6. Modelos (app/Models, Concerns, Traits, app/Sire/Models)

### 6.1 Infraestructura común
- **Contrato `App\Contracts\Documentable`** (app/Contracts/Documentable.php:7-14): `getTipoDocumento(): string`, `getNumeroCompletoAttribute(): string`, `items(): HasMany`. Lo implementan Invoice, Boleta, CreditNote, DebitNote e InternalDocument.
- **Trait `BelongsToTenant`** (Concerns/BelongsToTenant.php:9-20): relación `tenant()` y scope local `forTenant($tenantId)`. **No hay global scope de tenant** (grep `addGlobalScope`: solo Quotation y SaleNote). El aislamiento por tenant se hace a mano en controladores/acciones con `where('tenant_id', …)`, lo que hay que replicar con cuidado.
- **Trait `HasDocumentFields`** (Traits/HasDocumentFields.php): usa BelongsToTenant + SoftDeletes (:14); accessor `numero_completo` = `serie-` + correlativo con 6 ceros (:16-19); relaciones `client()` y `sucursal()`; scopes `status($s)` y `fechas($desde,$hasta)` (whereBetween 00:00:00–23:59:59, :36-42); `sharedCasts()` (fecha_emision datetime, fecha_vencimiento date, correlativo int, 13 montos decimal:2, sunat_notes array, sent_at datetime) (:44-66); `sharedFillable()` (:68-112).
- **Trait `HasPayments`**: `payments()` morphMany a Payment por `payable` (Traits/HasPayments.php:10-13).
- **Trait `HasSunatIntegration`**: scopes `pendiente`, `aceptado`, `enviado` (Traits/HasSunatIntegration.php:9-22).

### 6.2 Estados (no hay constantes PHP; valores sacados de migraciones y de código)
- `sunat_status` de comprobantes: `pendiente` → `enviado` → `aceptado` | `rechazado`; `anulacion_en_proceso` → `anulado`. Se filtra con `sunat_status NOT IN ('anulado','rechazado','anulacion_en_proceso')` (unas 20 veces en app/).
- `sunat_status` de guías, resúmenes y bajas: pendiente/enviado/aceptado/rechazado (en summaries y voided el default es `enviado`).
- `sunat_status` de retenciones y percepciones: varchar(30) libre, default `pendiente`.
- `payment_status`: `pendiente` | `parcial` | `pagado`, calculado en `RegisterPaymentAction::recalculate` (app/Actions/Payments/RegisterPaymentAction.php:29-45): suma de `payments.monto` comparada con `mto_imp_venta`.
- Suscripción: constantes `STATUS_*` y `BILLING_*` (Subscription.php:12-30). Membresía: `ROLE_OWNER='owner'`, `ROLE_CAJERO='cajero'` (TenantMembership.php:22-30). Usuario: `ROLE_*` (User.php:31-48). Tenant: `EMISSION_PLAN`, `EMISSION_UNLIMITED` (Tenant.php:19-22). Serie: `TIPOS`, `TIPOS_NOMBRE`, `PREFIJOS` (Serie.php:16-49).
- SIRE: enum `FasePeriodo` = propuesta | preliminar | generado (cast en SirePeriodo.php:29); `EstadoTicket` = '01' PENDIENTE, '02' EN_PROCESO, '03' PROCESANDO, '05' TERMINADO, '06' TERMINADO_ERRORES, '07' ERROR (app/Sire/Enums/EstadoTicket.php:10-15).

### 6.3 Ficha por modelo

| Modelo | Tabla | Traits | Fillable (resumen) | Casts destacados | Relaciones / otros |
|---|---|---|---|---|---|
| **Invoice** (Invoice.php) | invoices | HasDocumentFields, HasPayments, HasSunatIntegration; implements Documentable | todas las columnas de §2.5 salvo id y fechas de sistema (:16-31), incluye payment_status y monto_pagado | sharedCasts + exportacion/ivap decimal:2; cuotas, detraccion, percepcion, anticipos, descuentos_globales, guias, extras, contrato_colaboracion → array (:33-48) | `getTipoDocumento()='01'`; items → InvoiceItem; tenant, client, sucursal, payments |
| **Boleta** (Boleta.php) | boletas | ídem | ídem (:16-31) | ídem (:33-48) | '03'; items → BoletaItem |
| **CreditNote** (CreditNote.php) | credit_notes | HasDocumentFields, HasSunatIntegration (**sin HasPayments**) | :15-28 (doc_afectado_*, cod_motivo, des_motivo, guias, contrato_colaboracion) | guias, contrato_colaboracion array | '07'; items → CreditNoteItem |
| **DebitNote** (DebitNote.php) | debit_notes | ídem | :15-28 | ídem | '08'; items → DebitNoteItem |
| **InvoiceItem / BoletaItem / CreditNoteItem / DebitNoteItem** | *_items | — | fk padre, codigo, cod_producto_sunat, descripcion, unidad, cantidad, mto_valor_unitario, mto_valor_venta, mto_base_igv, porcentaje_igv, igv, tip_afe_igv, isc, icbper, total_impuestos, mto_precio_unitario, descuento, descuentos (InvoiceItem.php:10-15) | cantidad decimal:4, **mto_valor_unitario decimal:4** (⚠ la columna es 18,10), montos decimal:2, mto_precio_unitario decimal:4, descuentos array (:17-33) | belongsTo del padre |
| **DispatchGuide** (DispatchGuide.php) | dispatch_guides | BelongsToTenant, HasFactory, SoftDeletes | todas las columnas (:14-63), incluye xml_content y cdr_content | fecha_emision datetime; fecha_traslado y fecha_entrega_transportista date; peso_total decimal:3; los 10 JSON → array; sent_at (:65-86) | accessor numero_completo (6 ceros, :88-91). **No implementa Documentable** y no define relaciones `sucursal()` ni `document()` |
| **Summary** (Summary.php) | summaries | BelongsToTenant | :12-18 | fechas date, document_ids/sunat_notes array, last_polled_at datetime | — |
| **VoidedDocument** | voided_documents | BelongsToTenant | :12-18 | fechas date, detalles/sunat_notes array | — |
| **Retention** / **Perception** | retentions / perceptions | BelongsToTenant, SoftDeletes | Retention.php:17-24 / Perception.php:17-24 | fecha_emision datetime, tasa e importes decimal:2, sunat_notes array | items(); numero_completo con **8 ceros** (:44-47); scope status |
| **RetentionItem / PerceptionItem** | retention_items / perception_items | — | :12-16 | fechas date, importes decimal:2, pagos/cobros y tipo_cambio array | belongsTo padre |
| **Tenant** (Tenant.php) | tenants | HasFactory, SoftDeletes | :24-70 (todas salvo id y fechas) | **encrypted**: sol_user, sol_pass, consulta_token, client_secret, certificate_password, api_secret, sire_client_secret; array: telefonos, emails, cuentas_bancarias, billeteras_digitales; bool: is_active, obligado_codigo_producto, sire_enabled; igv_rate_override decimal:2 (:81-106) | hidden: secretos (:72-79); al crear genera api_key y api_secret (:133-140); los eventos created/updated sincronizan el owner en tenant_user (:144-165); user, miembros (belongsToMany por tenant_user con TenantMembership), invoices, boletas, creditNotes, debitNotes, clients, sucursales, series, dispatchGuides, apiLogs, subscription (latestOfMany), activeSubscription (active/trialing), sirePeriodos, sireTickets; `documentsThisMonth()` suma las 4 tablas por created_at (:244-253); `getCertificateContent()` (:269-293) |
| **Client** | clients | BelongsToTenant, HasFactory | :14-23 | — | invoices, boletas, creditNotes, debitNotes |
| **Serie** | series | BelongsToTenant, HasFactory | :51-59 | correlativo int, is_active bool | sucursal; resolveCorrelativo/nextCorrelativo con bloqueo |
| **Sucursal** | sucursales (`$table`) | BelongsToTenant, HasFactory | :16-26 | booleans | series, invoices, boletas, creditNotes, debitNotes, dispatchGuides |
| **Payment** | payments | — | :21-30 | monto y monto_recibido decimal:2 | payable (morphTo), tenant; const METODOS |
| **Plan** | plans | — | :10-21 | limits/features array, precios decimal:2, is_unlimited bool | subscriptions; getLimit, hasFeature, isUnlimited (is_unlimited o documents_month == -1); scope active (ordena por sort_order) |
| **Subscription** | subscriptions | — | :32-46 | 4 fechas datetime | tenant, plan, payments (SubscriptionPayment); scopes active, dueForRenewal, trialExpiring |
| **SubscriptionPayment** | subscription_payments | — | :10-20 | amount decimal:2, gateway_response array, paid_at, period_* date | subscription; scope completed |
| **User** | users | HasFactory, Notifiable, TwoFactorAuthenticatable (Fortify) | name, email, password, is_admin, role, is_active | password hashed, booleans | tenants (hasMany por user_id), empresas (belongsToMany tenant_user); puede(), hasPanelAccess(), isSuperAdmin()… |
| **TenantMembership** (Pivot) | tenant_user | — | — | abilities array, is_active bool | `$incrementing=true`; permite($ability) (el owner puede todo) |
| **ApiLog** | api_logs | BelongsToTenant | :14-25 | request/response array | `$timestamps=false` |
| **AuditLog** | audit_logs | — | `$guarded=[]` | metadata array | `UPDATED_AT=null`; `registrar()` nunca lanza excepción (:52-87) |
| **Setting** | settings | — | key, value | value array | — |
| **UnspscCode** | unspsc_codes | — | codigo, descripcion, clase | — | PK string 'codigo', no incremental, sin timestamps |
| **UnspscTaxonomy** | unspsc_taxonomy | — | nivel, codigo, nombre, parent | — | sin timestamps; ⚠ no declara PK compuesta (Eloquent asume `id`) |
| **SirePeriodo** | sire_periodos | — | :15-23 | fec_cierre datetime, fase → enum FasePeriodo | tenant, comprobantes |
| **SireTicket** | sire_tickets | HasFactory | :22-42 | fechas, payloads array | tenant, comprobantes (origen_ticket_id); estadoEnum/isFinal/isSuccess |
| **SireComprobante** | sire_comprobantes | — | :13-37 | fechas date, montos decimal:2, tipo_cambio decimal:4, incluido bool | tenant, periodo, ticketOrigen |
| **SireUploadFile** | sire_upload_files | — | :13-23 | uploaded_at, size_bytes | tenant, ticket |
| **SireReconciliationLog** | sire_reconciliation_logs | — | :13-25 | run_at, details array, diff_total_monto | tenant |

Ningún modelo define accessors relevantes aparte de `numero_completo`. Tampoco hay `$appends`.

---

## 7. Fuera de alcance: Notas de Venta y Cotizaciones

| Elemento | Decisión |
|---|---|
| Tabla `internal_documents` (`2026_03_09_000001`) + `payment_status`/`monto_pagado` (`2026_03_09_100002`) + `fecha_emision` DATETIME (`2026_03_16_200000`) | **Excluir** |
| Tabla `internal_document_items` (`2026_03_09_000002`; recibe `descuentos` y 18,10 pero **no** cod_producto_sunat) | **Excluir** |
| Modelos `InternalDocument` (STI por `type`: quotation/sale_note, numeración COT-/NV-YYYYMM-000001), `InternalDocumentItem`, `Quotation` (global scope type=quotation; estados vigente/aceptada/rechazada/vencida), `SaleNote` (global scope type=sale_note; estados emitida/anulada) | **Excluir** |
| `payments` | **Mantener**, quitando el payable `App\Models\SaleNote` (PaymentController.php:20) |
| `plans.limits.internal_documents_month` y `features` `cotizaciones` / `notas_venta` (PlanSeeder.php) | Recortar en el seed o dejarlos sin efecto |
| Contrato `Documentable` | Se mantiene (lo implementan los 4 comprobantes SUNAT) |

---

## 8. Seeders y factories

`DatabaseSeeder` (database/seeders/DatabaseSeeder.php:16-21) llama a: PlanSeeder → UnspscCodeSeeder → UnspscTaxonomySeeder → UbigeoSeeder.
**No siembra usuario inicial ni empresa demo.** El admin se crea con el comando `php artisan admin:make {email} --name= --password=` (app/Console/Commands/MakeAdmin.php:12-57), que pone `is_admin=true` y `email_verified_at`.

| Seeder | Fuente | Volumen | Detalle |
|---|---|---|---|
| **PlanSeeder** | inline | 3 planes | `updateOrCreate` por slug. **free** (S/0; documents_month 30, internal_documents_month 20, ai 10, team 1, sucursales 1, productos 100…), **pro** (S/49 al mes / S/490 al año; 500 docs, sucursales 3, productos -1…), **business** (S/99 / S/990; docs -1 = ilimitado, team 15, sucursales 10). Features con muchos módulos ajenos a la API (crm, marketplace, rrhh…). Desactiva los slugs `starter` y `growth` (:160) |
| **UnspscCodeSeeder** | `database/data/unspsc_v14.csv` (**~2.6 MB, 52 841 líneas ≈ 52 840 códigos**; columnas `codigo,descripcion,clase`) | lotes de 2000 | `TRUNCATE` + insert; descripción cortada a 255 caracteres |
| **UnspscTaxonomySeeder** | `database/data/unspsc_taxonomy.csv` (**~216 KB, 4 295 líneas**: 56 segmentos, 420 familias, 3 818 clases; `nivel,codigo,nombre,parent`) | lotes de 1000 | `TRUNCATE` + insert |
| **UbigeoSeeder** | `public/data_ubi.txt` (**~120 KB, 1 874 líneas**, separado por `|`: `id|nombre|info_busqueda|provincia_id|region_id`) | 25 regiones, 196 provincias, ~1 873 distritos | Deriva región y provincia de los 2/4 primeros dígitos y los nombres de `info_busqueda` ("Distrito / Provincia / Región"); upsert en lotes de 500. Si el archivo no existe, avisa y no hace nada |
| `seed_comprobantes_para_tests.php` | script suelto (no es un Seeder) | 1 factura + 1 boleta | Para tenant_id=1: F001-892 (aceptada) y B001-4521 (pendiente) con ítems. Consulta `information_schema … table_schema='public'`, o sea que está pensado para **PostgreSQL** |

Factories:
- `UserFactory`: name, email únicos, password 'password', 2FA null; estados `unverified` y `withTwoFactor`.
- `TenantFactory`: user → User::factory(), ruc '20'+9 dígitos, ubigeo 150101, sol MODDATOS/MODDATOS (credenciales beta de SUNAT), client_id uuid, environment beta, plan free, api_key 'key_'+uuid, api_secret 'sec_'+hex, sire_enabled false.
- `SireTicketFactory`: per_tributario 202604, cod_proceso '10', estado '01'.
No hay factories para invoices ni clientes (aunque Client/Serie/Sucursal/DispatchGuide usan `HasFactory`).

---

## 9. Observaciones / posibles problemas

1. **Cifrado Laravel** en `tenants` (sol_user, sol_pass, client_secret, certificate_password, api_secret, consulta_token, sire_client_secret) y en `users.two_factor_*`: el formato es base64(JSON{iv,value,mac,tag}) con AES-256-CBC, HMAC-SHA256 y `APP_KEY`. Las 7 tecnologías tienen que implementar un decrypt/encrypt compatible y compartir `APP_KEY` si van a reutilizar los datos, o hay que re-cifrar durante la migración. Además, `password` usa bcrypt (`$2y$`), que es compatible con la mayoría de librerías.
2. **`sire_client_secret` es varchar(255) y se guarda cifrado**: un secreto de más de ~60 bytes produce un payload de más de 255 caracteres. En MySQL strict falla (o se trunca) y en PG falla. Conviene usar `text` en los nuevos esquemas (el mismo problema que `2026_10_03_120000` ya corrigió para api_secret).
3. **Esquema distinto según el motor**: en MySQL `api_logs` está particionada, sin FK a tenants y con `created_at` DATETIME y PK compuesta; en pgsql no. Hay que elegir un esquema canónico.
4. **Posible CHECK residual en pgsql**: `2026_04_19_180000` cambia enum → string con `->change()`. En PostgreSQL Laravel no elimina el `CHECK subscriptions_status_check` ni `subscription_payments_status_check` creados por el enum, así que el valor `pending` (Subscription.php:17) podría ser rechazado. Hay que verificarlo en una base pgsql real. En el esquema nuevo, no crear esos CHECK.
5. **`summaries_tenant_identifier_unique` es condicional** (`2026_07_30_140000:36-45`): en bases con duplicados no existe. En el esquema nuevo debe existir.
6. **`voided_documents` no tiene UNIQUE** (tenant_id, identifier), a diferencia de summaries. **retentions/perceptions** solo tienen índice NO único en (tenant_id, serie, correlativo), así que no hay protección contra correlativos duplicados en BD. Recomendación: UNIQUE.
7. **`dispatch_guides`**: el UNIQUE (tenant_id, serie, correlativo) no incluye `tipo_documento` (funciona porque los prefijos T y V difieren). `document_id` es una columna huérfana sin FK que apuntaba a la tabla legacy. `sunat_status` no admite anulado. Guarda XML/CDR en BD (`xml_content`, `cdr_content`) además de en paths.
8. **FKs sin ON DELETE** en `retentions`/`perceptions` → tenants (RESTRICT): impiden el borrado físico de un tenant, a diferencia del resto (CASCADE). `idempotency_keys`, `audit_logs`, `summaries.anulado_por` y `voided_documents.anulado_por` no tienen FK.
9. **Precisión**: `*_items.mto_valor_unitario` es decimal(18,10) pero el cast es `decimal:4`, así que el modelo redondea a 4 decimales al leer. Hay que decidir qué replicar; lo más correcto es leer los 10 decimales.
10. **`payments.payable_type` guarda nombres de clase PHP** (`App\Models\Invoice`). Hace falta un mapeo explícito en los demás lenguajes.
11. **Sin global scope de tenant**: el aislamiento depende de filtros manuales. Al portar conviene centralizarlo (filtro global/RLS en PG).
12. **`MakeAdmin` no asigna `role`**: crea `is_admin=true` con `role=NULL`, y `hasPanelAccess()` exige un role en `ROLES` (User.php:108-111). El admin recién creado no entra al panel salvo que se vuelva a ejecutar la migración de backfill. Es un bug a no replicar: el seed inicial debe poner `role='super_admin'`.
13. **Plan/limits duplicados**: `tenants.plan` (enum), `tenants.max_documents_month`, `emission_mode` y `plans/subscriptions` conviven. `max_documents_month=-1` significa ilimitado. `documentsThisMonth()` cuenta por `created_at` en 4 tablas, mientras que `tenants.documents_this_month` es un contador aparte.
14. **`unspsc_taxonomy`** tiene PK compuesta, pero el modelo no la declara (los `save()`/`find()` de Eloquent fallarían). Solo se usa en lectura. `parent` es varchar(4), suficiente para familia (4 dígitos) pero no para un hijo de clase (no aplica).
15. **`cod_producto_sunat` no tiene FK** a `unspsc_codes` (se valida en la aplicación con `UnspscCode::existe`).
16. **Timestamps sin zona horaria**: todas las fechas son `timestamp without time zone`/DATETIME. En la nueva implementación hay que fijar la zona (America/Lima) de forma explícita en conexión y app.
17. **CHAR en PG** (`unspsc_codes.codigo`, `cod_producto_sunat`, ubigeo `id`) rellena con espacios. Los valores son de longitud fija, así que no debería afectar, pero mejor usar `varchar(n)` en PG.
18. **`tenants.razon_social`** es nullable con default '' y `ruc` es el único dato obligatorio de identidad. La validación de negocio debe quedar en la aplicación.
19. Hay muchas columnas varchar(255) sin longitud explícita (sunat_code, ticket, hash_cpe, xml_path en las tablas nuevas). En documents/dispatch_guides se usaban 10/100/500, y no son homogéneas entre tablas.
