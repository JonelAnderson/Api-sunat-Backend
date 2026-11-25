-- =====================================================================
-- V1 · Esquema inicial equivalente a API-PRO (91 migraciones Laravel consolidadas)
-- Fuente: docs/analisis-fase0/04_modelo_datos.md
-- Excluye: internal_documents(+items) (notas de venta / cotizaciones), tablas
-- legacy documents/document_items, tablas de framework del panel web y SIRE
-- (SIRE va en su propia migración).
-- Convenciones PostgreSQL:
--   * enums Laravel  -> varchar + CHECK "{tabla}_{columna}_check"
--   * json           -> jsonb
--   * timestamps     -> timestamp(0) sin zona (created_at/updated_at en UTC;
--                       fecha_emision en hora de Lima, igual que API-PRO)
--   * índices en FK  -> explícitos (PostgreSQL no los crea solo)
-- =====================================================================

-- ---------------------------------------------------------------------
-- Usuarios y empresas
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id                        bigserial PRIMARY KEY,
    name                      varchar(255) NOT NULL,
    email                     varchar(255) NOT NULL,
    email_verified_at         timestamp(0) NULL,
    password                  varchar(255) NOT NULL,
    is_admin                  boolean      NOT NULL DEFAULT false,
    role                      varchar(20)  NULL,
    is_active                 boolean      NOT NULL DEFAULT true,
    two_factor_secret         text         NULL,
    two_factor_recovery_codes text         NULL,
    two_factor_confirmed_at   timestamp(0) NULL,
    remember_token            varchar(100) NULL,
    created_at                timestamp(0) NULL,
    updated_at                timestamp(0) NULL,
    CONSTRAINT users_email_unique UNIQUE (email)
);

CREATE TABLE tenants (
    id                          bigserial PRIMARY KEY,
    ruc                         varchar(11)  NOT NULL,
    razon_social                varchar(255) NULL DEFAULT '',
    nombre_comercial            varchar(255) NULL,
    direccion                   varchar(500) NULL,
    ubigeo                      varchar(6)   NULL,
    departamento                varchar(100) NULL,
    provincia                   varchar(100) NULL,
    distrito                    varchar(100) NULL,
    telefonos                   jsonb        NULL,
    emails                      jsonb        NULL,
    cuentas_bancarias           jsonb        NULL,
    billeteras_digitales        jsonb        NULL,
    mensaje_agradecimiento      varchar(500) NULL,
    mensaje_promocional         varchar(500) NULL,
    sol_user                    text         NOT NULL,           -- cifrado
    sol_pass                    text         NOT NULL,           -- cifrado
    client_id                   varchar(100) NULL,
    client_secret               text         NULL,               -- cifrado
    certificate_path            varchar(500) NULL,
    certificate_password        text         NULL,               -- cifrado
    environment                 varchar(255) NOT NULL DEFAULT 'beta',
    webhook_url                 varchar(500) NULL,
    logo_path                   varchar(500) NULL,
    favicon_path                varchar(255) NULL,
    api_key                     varchar(64)  NOT NULL,
    api_secret                  text         NOT NULL,           -- cifrado (reversible: firma webhooks)
    consulta_token              text         NULL,               -- cifrado
    consulta_proveedor          varchar(10)  NOT NULL DEFAULT 'json',
    plan                        varchar(255) NOT NULL DEFAULT 'free',
    emission_mode               varchar(20)  NOT NULL DEFAULT 'plan',
    tax_regime                  varchar(32)  NOT NULL DEFAULT 'general',
    obligado_codigo_producto    boolean      NOT NULL DEFAULT false,
    igv_rate_override           numeric(5,2) NULL,
    nrus_categoria              integer      NULL,
    max_documents_month         integer      NOT NULL DEFAULT 20,
    documents_this_month        integer      NOT NULL DEFAULT 0,
    ai_messages_this_month      integer      NOT NULL DEFAULT 0,
    usage_reset_month           varchar(7)   NULL,
    is_active                   boolean      NOT NULL DEFAULT true,
    sire_enabled                boolean      NOT NULL DEFAULT false,
    sire_last_period_synced     varchar(6)   NULL,
    sire_last_reconciliation_at timestamp(0) NULL,
    sire_client_id              varchar(100) NULL,
    sire_client_secret          text         NULL,               -- cifrado (API-PRO: varchar(255), no alcanza)
    user_id                     bigint       NULL REFERENCES users (id) ON DELETE SET NULL,
    created_at                  timestamp(0) NULL,
    updated_at                  timestamp(0) NULL,
    deleted_at                  timestamp(0) NULL,
    CONSTRAINT tenants_ruc_unique UNIQUE (ruc),
    CONSTRAINT tenants_api_key_unique UNIQUE (api_key),
    CONSTRAINT tenants_environment_check CHECK (environment IN ('beta', 'production')),
    CONSTRAINT tenants_plan_check CHECK (plan IN ('free', 'pro', 'business'))
);
CREATE INDEX tenants_api_key_is_active_index ON tenants (api_key, is_active);
CREATE INDEX tenants_user_id_index ON tenants (user_id);
COMMENT ON COLUMN tenants.client_id IS 'SUNAT GRE OAuth2 client_id';
COMMENT ON COLUMN tenants.client_secret IS 'SUNAT GRE OAuth2 client_secret (cifrado)';

CREATE TABLE tenant_user (
    id         bigserial PRIMARY KEY,
    tenant_id  bigint       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id    bigint       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role       varchar(20)  NOT NULL DEFAULT 'cajero',
    abilities  jsonb        NULL,
    is_active  boolean      NOT NULL DEFAULT true,
    created_at timestamp(0) NULL,
    updated_at timestamp(0) NULL,
    CONSTRAINT tenant_user_unique UNIQUE (tenant_id, user_id)
);
CREATE INDEX tenant_user_user_id_index ON tenant_user (user_id);

-- ---------------------------------------------------------------------
-- Clientes, sucursales y series
-- ---------------------------------------------------------------------
CREATE TABLE clients (
    id               bigserial PRIMARY KEY,
    tenant_id        bigint       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    tipo_documento   varchar(1)   NOT NULL,
    numero_documento varchar(15)  NOT NULL,
    razon_social     varchar(255) NOT NULL,
    nombre_comercial varchar(255) NULL,
    direccion        varchar(500) NULL,
    email            varchar(255) NULL,
    telefono         varchar(20)  NULL,
    created_at       timestamp(0) NULL,
    updated_at       timestamp(0) NULL,
    CONSTRAINT clients_tenant_doc_unique UNIQUE (tenant_id, tipo_documento, numero_documento)
);
CREATE INDEX clients_numero_documento_index ON clients (numero_documento);

CREATE TABLE sucursales (
    id           bigserial PRIMARY KEY,
    tenant_id    bigint       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    nombre       varchar(100) NOT NULL,
    cod_local    varchar(4)   NOT NULL,
    direccion    varchar(500) NOT NULL,
    ubigeo       varchar(6)   NOT NULL,
    telefono     varchar(20)  NULL,
    email        varchar(255) NULL,
    is_principal boolean      NOT NULL DEFAULT false,
    is_active    boolean      NOT NULL DEFAULT true,
    created_at   timestamp(0) NULL,
    updated_at   timestamp(0) NULL,
    CONSTRAINT sucursales_tenant_cod_local_unique UNIQUE (tenant_id, cod_local)
);

CREATE TABLE series (
    id              bigserial PRIMARY KEY,
    tenant_id       bigint       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    tipo_documento  varchar(2)   NOT NULL,
    serie           varchar(4)   NOT NULL,
    sucursal_nombre varchar(100) NULL,
    sucursal_id     bigint       NULL REFERENCES sucursales (id) ON DELETE SET NULL,
    correlativo     integer      NOT NULL DEFAULT 0 CHECK (correlativo >= 0),
    is_active       boolean      NOT NULL DEFAULT true,
    created_at      timestamp(0) NULL,
    updated_at      timestamp(0) NULL,
    CONSTRAINT series_tenant_tipo_serie_unique UNIQUE (tenant_id, tipo_documento, serie)
);
CREATE INDEX series_sucursal_id_index ON series (sucursal_id);

-- ---------------------------------------------------------------------
-- Facturas (01) y boletas (03): estructura idéntica
-- ---------------------------------------------------------------------
CREATE TABLE invoices (
    id                    bigserial PRIMARY KEY,
    tenant_id             bigint        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    sucursal_id           bigint        NULL REFERENCES sucursales (id) ON DELETE SET NULL,
    client_id             bigint        NULL REFERENCES clients (id) ON DELETE SET NULL,
    serie                 varchar(4)    NOT NULL,
    correlativo           integer       NOT NULL CHECK (correlativo >= 0),
    cod_local             varchar(4)    NOT NULL DEFAULT '0000',
    fecha_emision         timestamp(0)  NOT NULL,
    fecha_vencimiento     date          NULL,
    tipo_operacion        varchar(4)    NOT NULL DEFAULT '0101',
    tipo_moneda           varchar(3)    NOT NULL DEFAULT 'PEN',
    forma_pago            varchar(20)   NOT NULL DEFAULT 'Contado',
    client_tipo_doc       varchar(1)    NOT NULL,
    client_num_doc        varchar(15)   NOT NULL,
    client_razon_social   varchar(255)  NOT NULL,
    client_direccion      varchar(500)  NULL,
    mto_oper_gravadas     numeric(12,2) NOT NULL DEFAULT 0,
    mto_oper_exoneradas   numeric(12,2) NOT NULL DEFAULT 0,
    mto_oper_inafectas    numeric(12,2) NOT NULL DEFAULT 0,
    mto_oper_exportacion  numeric(12,2) NOT NULL DEFAULT 0,
    mto_oper_gratuitas    numeric(12,2) NOT NULL DEFAULT 0,
    mto_igv               numeric(12,2) NOT NULL DEFAULT 0,
    mto_base_ivap         numeric(12,2) NOT NULL DEFAULT 0,
    mto_ivap              numeric(12,2) NOT NULL DEFAULT 0,
    mto_isc               numeric(12,2) NOT NULL DEFAULT 0,
    mto_icbper            numeric(12,2) NOT NULL DEFAULT 0,
    total_impuestos       numeric(12,2) NOT NULL DEFAULT 0,
    valor_venta           numeric(12,2) NOT NULL DEFAULT 0,
    sub_total             numeric(12,2) NOT NULL DEFAULT 0,
    mto_imp_venta         numeric(12,2) NOT NULL DEFAULT 0,
    total_anticipos       numeric(12,2) NOT NULL DEFAULT 0,
    total_descuentos      numeric(12,2) NOT NULL DEFAULT 0,
    leyenda               varchar(500)  NULL,
    observacion           text          NULL,
    payment_status        varchar(255)  NOT NULL DEFAULT 'pendiente',
    monto_pagado          numeric(12,2) NOT NULL DEFAULT 0,
    cuotas                jsonb         NULL,
    detraccion            jsonb         NULL,
    percepcion            jsonb         NULL,
    anticipos             jsonb         NULL,
    descuentos_globales   jsonb         NULL,
    guias                 jsonb         NULL,
    extras                jsonb         NULL,
    leyendas              jsonb         NULL,   -- API-PRO valida 'leyendas' pero no las guarda (bug corregido)
    xml_path              varchar(255)  NULL,
    cdr_path              varchar(255)  NULL,
    pdf_path              varchar(255)  NULL,
    hash_cpe              varchar(255)  NULL,
    sunat_status          varchar(255)  NOT NULL DEFAULT 'pendiente',
    sunat_code            varchar(255)  NULL,
    sunat_description     text          NULL,
    sunat_notes           jsonb         NULL,
    ticket                varchar(255)  NULL,
    sent_at               timestamp(0)  NULL,
    created_at            timestamp(0)  NULL,
    updated_at            timestamp(0)  NULL,
    deleted_at            timestamp(0)  NULL,
    contrato_colaboracion jsonb         NULL,
    CONSTRAINT invoices_tenant_id_serie_correlativo_unique UNIQUE (tenant_id, serie, correlativo),
    CONSTRAINT invoices_payment_status_check CHECK (payment_status IN ('pendiente', 'parcial', 'pagado')),
    CONSTRAINT invoices_sunat_status_check CHECK (sunat_status IN ('pendiente', 'enviado', 'aceptado', 'rechazado', 'anulado', 'anulacion_en_proceso'))
);
CREATE INDEX invoices_tenant_id_sunat_status_index ON invoices (tenant_id, sunat_status);
CREATE INDEX invoices_tenant_id_fecha_emision_index ON invoices (tenant_id, fecha_emision);
CREATE INDEX invoices_tenant_id_client_num_doc_index ON invoices (tenant_id, client_num_doc);
CREATE INDEX inv_tenant_created ON invoices (tenant_id, created_at);
CREATE INDEX inv_tenant_local_fecha ON invoices (tenant_id, cod_local, fecha_emision);
CREATE INDEX inv_tenant_pago_venc ON invoices (tenant_id, payment_status, fecha_vencimiento);
CREATE INDEX invoices_sucursal_id_index ON invoices (sucursal_id);
CREATE INDEX invoices_client_id_index ON invoices (client_id);

-- Misma estructura que invoices (LIKE copia columnas, tipos, NOT NULL y defaults;
-- la PK, la secuencia propia, las FK y los CHECK se definen a continuación)
CREATE TABLE boletas (LIKE invoices INCLUDING DEFAULTS);
CREATE SEQUENCE boletas_id_seq OWNED BY boletas.id;
ALTER TABLE boletas ALTER COLUMN id SET DEFAULT nextval('boletas_id_seq');
ALTER TABLE boletas
    ADD PRIMARY KEY (id),
    ADD CONSTRAINT boletas_tenant_id_foreign FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE,
    ADD CONSTRAINT boletas_sucursal_id_foreign FOREIGN KEY (sucursal_id) REFERENCES sucursales (id) ON DELETE SET NULL,
    ADD CONSTRAINT boletas_client_id_foreign FOREIGN KEY (client_id) REFERENCES clients (id) ON DELETE SET NULL,
    ADD CONSTRAINT boletas_tenant_id_serie_correlativo_unique UNIQUE (tenant_id, serie, correlativo),
    ADD CONSTRAINT boletas_correlativo_check CHECK (correlativo >= 0),
    ADD CONSTRAINT boletas_payment_status_check CHECK (payment_status IN ('pendiente', 'parcial', 'pagado')),
    ADD CONSTRAINT boletas_sunat_status_check CHECK (sunat_status IN ('pendiente', 'enviado', 'aceptado', 'rechazado', 'anulado', 'anulacion_en_proceso'));
CREATE INDEX boletas_tenant_id_sunat_status_index ON boletas (tenant_id, sunat_status);
CREATE INDEX boletas_tenant_id_fecha_emision_index ON boletas (tenant_id, fecha_emision);
CREATE INDEX boletas_tenant_id_client_num_doc_index ON boletas (tenant_id, client_num_doc);
CREATE INDEX bol_tenant_created ON boletas (tenant_id, created_at);
CREATE INDEX bol_tenant_local_fecha ON boletas (tenant_id, cod_local, fecha_emision);
CREATE INDEX bol_tenant_pago_venc ON boletas (tenant_id, payment_status, fecha_vencimiento);
CREATE INDEX boletas_sucursal_id_index ON boletas (sucursal_id);
CREATE INDEX boletas_client_id_index ON boletas (client_id);

-- ---------------------------------------------------------------------
-- Notas de crédito (07) y débito (08)
-- ---------------------------------------------------------------------
CREATE TABLE credit_notes (
    id                       bigserial PRIMARY KEY,
    tenant_id                bigint        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    sucursal_id              bigint        NULL REFERENCES sucursales (id) ON DELETE SET NULL,
    client_id                bigint        NULL REFERENCES clients (id) ON DELETE SET NULL,
    serie                    varchar(4)    NOT NULL,
    correlativo              integer       NOT NULL CHECK (correlativo >= 0),
    cod_local                varchar(4)    NOT NULL DEFAULT '0000',
    fecha_emision            timestamp(0)  NOT NULL,
    tipo_moneda              varchar(3)    NOT NULL DEFAULT 'PEN',
    client_tipo_doc          varchar(1)    NOT NULL,
    client_num_doc           varchar(15)   NOT NULL,
    client_razon_social      varchar(255)  NOT NULL,
    client_direccion         varchar(500)  NULL,
    doc_afectado_tipo        varchar(2)    NOT NULL,
    doc_afectado_serie       varchar(4)    NOT NULL,
    doc_afectado_correlativo varchar(10)   NOT NULL,
    cod_motivo               varchar(2)    NOT NULL,
    des_motivo               varchar(255)  NOT NULL,
    mto_oper_gravadas        numeric(12,2) NOT NULL DEFAULT 0,
    mto_oper_exoneradas      numeric(12,2) NOT NULL DEFAULT 0,
    mto_oper_inafectas       numeric(12,2) NOT NULL DEFAULT 0,
    mto_oper_gratuitas       numeric(12,2) NOT NULL DEFAULT 0,
    mto_igv                  numeric(12,2) NOT NULL DEFAULT 0,
    mto_isc                  numeric(12,2) NOT NULL DEFAULT 0,
    mto_icbper               numeric(12,2) NOT NULL DEFAULT 0,
    total_impuestos          numeric(12,2) NOT NULL DEFAULT 0,
    valor_venta              numeric(12,2) NOT NULL DEFAULT 0,
    sub_total                numeric(12,2) NOT NULL DEFAULT 0,
    mto_imp_venta            numeric(12,2) NOT NULL DEFAULT 0,
    total_anticipos          numeric(12,2) NOT NULL DEFAULT 0,
    total_descuentos         numeric(12,2) NOT NULL DEFAULT 0,
    descuentos_globales      jsonb         NULL,   -- API-PRO los valida y usa pero no los guarda (bug corregido)
    leyendas                 jsonb         NULL,
    leyenda                  varchar(500)  NULL,
    observacion              text          NULL,
    guias                    jsonb         NULL,
    xml_path                 varchar(255)  NULL,
    cdr_path                 varchar(255)  NULL,
    pdf_path                 varchar(255)  NULL,
    hash_cpe                 varchar(255)  NULL,
    sunat_status             varchar(255)  NOT NULL DEFAULT 'pendiente',
    sunat_code               varchar(255)  NULL,
    sunat_description        text          NULL,
    sunat_notes              jsonb         NULL,
    ticket                   varchar(255)  NULL,
    sent_at                  timestamp(0)  NULL,
    created_at               timestamp(0)  NULL,
    updated_at               timestamp(0)  NULL,
    deleted_at               timestamp(0)  NULL,
    contrato_colaboracion    jsonb         NULL,
    CONSTRAINT credit_notes_tenant_id_serie_correlativo_unique UNIQUE (tenant_id, serie, correlativo),
    CONSTRAINT credit_notes_sunat_status_check CHECK (sunat_status IN ('pendiente', 'enviado', 'aceptado', 'rechazado', 'anulado', 'anulacion_en_proceso'))
);
CREATE INDEX credit_notes_tenant_id_sunat_status_index ON credit_notes (tenant_id, sunat_status);
CREATE INDEX credit_notes_tenant_id_fecha_emision_index ON credit_notes (tenant_id, fecha_emision);
CREATE INDEX cn_tenant_client_doc ON credit_notes (tenant_id, client_num_doc);
CREATE INDEX cn_tenant_created ON credit_notes (tenant_id, created_at);
CREATE INDEX credit_notes_sucursal_id_index ON credit_notes (sucursal_id);
CREATE INDEX credit_notes_client_id_index ON credit_notes (client_id);

CREATE TABLE debit_notes (LIKE credit_notes INCLUDING DEFAULTS);
CREATE SEQUENCE debit_notes_id_seq OWNED BY debit_notes.id;
ALTER TABLE debit_notes ALTER COLUMN id SET DEFAULT nextval('debit_notes_id_seq');
-- En la ND el documento afectado es opcional (motivo 13, ajuste de montos)
ALTER TABLE debit_notes
    ALTER COLUMN doc_afectado_tipo DROP NOT NULL,
    ALTER COLUMN doc_afectado_serie DROP NOT NULL,
    ALTER COLUMN doc_afectado_correlativo DROP NOT NULL,
    ADD PRIMARY KEY (id),
    ADD CONSTRAINT debit_notes_tenant_id_foreign FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE,
    ADD CONSTRAINT debit_notes_sucursal_id_foreign FOREIGN KEY (sucursal_id) REFERENCES sucursales (id) ON DELETE SET NULL,
    ADD CONSTRAINT debit_notes_client_id_foreign FOREIGN KEY (client_id) REFERENCES clients (id) ON DELETE SET NULL,
    ADD CONSTRAINT debit_notes_tenant_id_serie_correlativo_unique UNIQUE (tenant_id, serie, correlativo),
    ADD CONSTRAINT debit_notes_correlativo_check CHECK (correlativo >= 0),
    ADD CONSTRAINT debit_notes_sunat_status_check CHECK (sunat_status IN ('pendiente', 'enviado', 'aceptado', 'rechazado', 'anulado', 'anulacion_en_proceso'));
CREATE INDEX debit_notes_tenant_id_sunat_status_index ON debit_notes (tenant_id, sunat_status);
CREATE INDEX debit_notes_tenant_id_fecha_emision_index ON debit_notes (tenant_id, fecha_emision);
CREATE INDEX dn_tenant_client_doc ON debit_notes (tenant_id, client_num_doc);
CREATE INDEX dn_tenant_created ON debit_notes (tenant_id, created_at);
CREATE INDEX debit_notes_sucursal_id_index ON debit_notes (sucursal_id);
CREATE INDEX debit_notes_client_id_index ON debit_notes (client_id);

-- ---------------------------------------------------------------------
-- Ítems de comprobantes (misma estructura para los 4 tipos)
-- ---------------------------------------------------------------------
CREATE TABLE invoice_items (
    id                  bigserial PRIMARY KEY,
    invoice_id          bigint         NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    codigo              varchar(50)    NULL,
    cod_producto_sunat  varchar(8)     NULL,
    descripcion         varchar(500)   NOT NULL,
    unidad              varchar(5)     NOT NULL,
    cantidad            numeric(12,4)  NOT NULL,
    mto_valor_unitario  numeric(18,10) NOT NULL DEFAULT 0,
    mto_valor_venta     numeric(12,2)  NOT NULL DEFAULT 0,
    mto_base_igv        numeric(12,2)  NOT NULL DEFAULT 0,
    porcentaje_igv      numeric(5,2)   NOT NULL DEFAULT 18,
    igv                 numeric(12,2)  NOT NULL DEFAULT 0,
    tip_afe_igv         varchar(2)     NOT NULL DEFAULT '10',
    isc                 numeric(12,2)  NOT NULL DEFAULT 0,
    icbper              numeric(12,2)  NOT NULL DEFAULT 0,
    total_impuestos     numeric(12,2)  NOT NULL DEFAULT 0,
    mto_precio_unitario numeric(12,4)  NOT NULL DEFAULT 0,
    descuento           numeric(12,2)  NOT NULL DEFAULT 0,
    descuentos          jsonb          NULL,
    -- API-PRO no persiste estos tres y el XML pierde datos (bug corregido)
    tip_sis_isc         varchar(2)     NULL,
    porcentaje_isc      numeric(8,4)   NULL,
    factor_icbper       numeric(8,4)   NULL,
    created_at          timestamp(0)   NULL,
    updated_at          timestamp(0)   NULL
);
CREATE INDEX invoice_items_invoice_id_index ON invoice_items (invoice_id);

CREATE TABLE boleta_items (LIKE invoice_items INCLUDING DEFAULTS);
ALTER TABLE boleta_items RENAME COLUMN invoice_id TO boleta_id;
CREATE SEQUENCE boleta_items_id_seq OWNED BY boleta_items.id;
ALTER TABLE boleta_items ALTER COLUMN id SET DEFAULT nextval('boleta_items_id_seq');
ALTER TABLE boleta_items
    ADD PRIMARY KEY (id),
    ADD CONSTRAINT boleta_items_boleta_id_foreign FOREIGN KEY (boleta_id) REFERENCES boletas (id) ON DELETE CASCADE;
CREATE INDEX boleta_items_boleta_id_index ON boleta_items (boleta_id);

CREATE TABLE credit_note_items (LIKE invoice_items INCLUDING DEFAULTS);
ALTER TABLE credit_note_items RENAME COLUMN invoice_id TO credit_note_id;
CREATE SEQUENCE credit_note_items_id_seq OWNED BY credit_note_items.id;
ALTER TABLE credit_note_items ALTER COLUMN id SET DEFAULT nextval('credit_note_items_id_seq');
ALTER TABLE credit_note_items
    ADD PRIMARY KEY (id),
    ADD CONSTRAINT credit_note_items_credit_note_id_foreign FOREIGN KEY (credit_note_id) REFERENCES credit_notes (id) ON DELETE CASCADE;
CREATE INDEX credit_note_items_credit_note_id_index ON credit_note_items (credit_note_id);

CREATE TABLE debit_note_items (LIKE invoice_items INCLUDING DEFAULTS);
ALTER TABLE debit_note_items RENAME COLUMN invoice_id TO debit_note_id;
CREATE SEQUENCE debit_note_items_id_seq OWNED BY debit_note_items.id;
ALTER TABLE debit_note_items ALTER COLUMN id SET DEFAULT nextval('debit_note_items_id_seq');
ALTER TABLE debit_note_items
    ADD PRIMARY KEY (id),
    ADD CONSTRAINT debit_note_items_debit_note_id_foreign FOREIGN KEY (debit_note_id) REFERENCES debit_notes (id) ON DELETE CASCADE;
CREATE INDEX debit_note_items_debit_note_id_index ON debit_note_items (debit_note_id);

-- ---------------------------------------------------------------------
-- Guías de remisión electrónicas (09 remitente / 31 transportista)
-- ---------------------------------------------------------------------
CREATE TABLE dispatch_guides (
    id                          bigserial PRIMARY KEY,
    tenant_id                   bigint        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    sucursal_id                 bigint        NULL REFERENCES sucursales (id) ON DELETE SET NULL,
    cod_local                   varchar(4)    NOT NULL DEFAULT '0000',
    tipo_documento              varchar(2)    NOT NULL DEFAULT '09',
    serie                       varchar(4)    NOT NULL,
    correlativo                 integer       NOT NULL CHECK (correlativo >= 0),
    fecha_emision               timestamp(0)  NOT NULL,
    destinatario_tipo_doc       varchar(1)    NOT NULL,
    destinatario_num_doc        varchar(15)   NOT NULL,
    destinatario_razon_social   varchar(255)  NOT NULL,
    cod_traslado                varchar(2)    NOT NULL,
    mod_traslado                varchar(2)    NOT NULL,
    fecha_traslado              date          NOT NULL,
    fecha_entrega_transportista date          NULL,
    peso_total                  numeric(12,3) NOT NULL,
    und_peso_total              varchar(3)    NOT NULL DEFAULT 'KGM',
    num_bultos                  integer       NULL,
    remitente                   jsonb         NULL,
    doc_relacionado             jsonb         NULL,
    datos_subcontratador        jsonb         NULL,
    datos_pagador_flete         jsonb         NULL,
    autorizacion_especial       jsonb         NULL,
    llegada_ubigeo              varchar(6)    NOT NULL,
    llegada_direccion           varchar(500)  NOT NULL,
    llegada_ruc                 varchar(11)   NULL,
    llegada_cod_local           varchar(4)    NULL,
    partida_ubigeo              varchar(6)    NOT NULL,
    partida_direccion           varchar(500)  NOT NULL,
    partida_ruc                 varchar(11)   NULL,
    partida_cod_local           varchar(4)    NULL,
    transportista               jsonb         NULL,
    vehiculo                    jsonb         NULL,
    conductor                   jsonb         NULL,
    indicadores                 jsonb         NULL,
    observacion                 varchar(500)  NULL,
    items                       jsonb         NOT NULL,
    xml_content                 text          NULL,
    xml_path                    varchar(500)  NULL,
    cdr_content                 bytea         NULL,
    cdr_path                    varchar(500)  NULL,
    pdf_path                    varchar(500)  NULL,
    hash_cpe                    varchar(100)  NULL,
    sunat_status                varchar(255)  NOT NULL DEFAULT 'pendiente',
    sunat_code                  varchar(20)   NULL,
    sunat_description           text          NULL,
    ticket                      varchar(100)  NULL,
    sent_at                     timestamp(0)  NULL,
    created_at                  timestamp(0)  NULL,
    updated_at                  timestamp(0)  NULL,
    deleted_at                  timestamp(0)  NULL,
    CONSTRAINT dispatch_guides_tenant_serie_corr_unique UNIQUE (tenant_id, serie, correlativo),
    CONSTRAINT dispatch_guides_sunat_status_check CHECK (sunat_status IN ('pendiente', 'enviado', 'aceptado', 'rechazado'))
);
CREATE INDEX dispatch_guides_tenant_id_sunat_status_index ON dispatch_guides (tenant_id, sunat_status);
CREATE INDEX dispatch_guides_tenant_id_fecha_emision_index ON dispatch_guides (tenant_id, fecha_emision);
CREATE INDEX dg_tenant_created ON dispatch_guides (tenant_id, created_at);
CREATE INDEX dg_tenant_dest_doc ON dispatch_guides (tenant_id, destinatario_num_doc);
CREATE INDEX dispatch_guides_sucursal_id_index ON dispatch_guides (sucursal_id);

-- ---------------------------------------------------------------------
-- Resúmenes diarios (RC) y comunicaciones de baja / reversiones (RA / RR)
-- ---------------------------------------------------------------------
CREATE TABLE summaries (
    id                bigserial PRIMARY KEY,
    tenant_id         bigint       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    identifier        varchar(255) NOT NULL,
    correlativo       varchar(5)   NOT NULL,
    fecha_referencia  date         NOT NULL,
    fecha_envio       date         NOT NULL,
    total_documentos  integer      NOT NULL DEFAULT 0,
    tipo              varchar(255) NOT NULL,
    document_ids      jsonb        NULL,
    motivo            varchar(255) NULL,
    anulado_por       bigint       NULL,
    xml_path          varchar(255) NULL,
    cdr_path          varchar(255) NULL,
    ticket            varchar(255) NULL,
    last_polled_at    timestamp(0) NULL,
    poll_attempts     integer      NOT NULL DEFAULT 0,
    sunat_status      varchar(255) NOT NULL DEFAULT 'enviado',
    sunat_code        varchar(255) NULL,
    sunat_description text         NULL,
    sunat_notes       jsonb        NULL,
    created_at        timestamp(0) NULL,
    updated_at        timestamp(0) NULL,
    CONSTRAINT summaries_tenant_identifier_unique UNIQUE (tenant_id, identifier),
    CONSTRAINT summaries_tipo_check CHECK (tipo IN ('envio', 'anulacion')),
    CONSTRAINT summaries_sunat_status_check CHECK (sunat_status IN ('pendiente', 'enviado', 'aceptado', 'rechazado'))
);
CREATE INDEX summaries_tenant_id_ticket_index ON summaries (tenant_id, ticket);
CREATE INDEX summaries_tenant_id_fecha_referencia_index ON summaries (tenant_id, fecha_referencia);
CREATE INDEX summaries_poll_idx ON summaries (sunat_status, last_polled_at);

CREATE TABLE voided_documents (
    id                 bigserial PRIMARY KEY,
    tenant_id          bigint       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    identifier         varchar(255) NOT NULL,
    correlativo        varchar(5)   NOT NULL,
    fecha_generacion   date         NOT NULL,
    fecha_comunicacion date         NOT NULL,
    total_documentos   integer      NOT NULL DEFAULT 0,
    detalles           jsonb        NULL,
    motivo             varchar(255) NULL,
    anulado_por        bigint       NULL,
    xml_path           varchar(255) NULL,
    cdr_path           varchar(255) NULL,
    ticket             varchar(255) NULL,
    sunat_status       varchar(255) NOT NULL DEFAULT 'enviado',
    sunat_code         varchar(255) NULL,
    sunat_description  text         NULL,
    sunat_notes        jsonb        NULL,
    created_at         timestamp(0) NULL,
    updated_at         timestamp(0) NULL,
    -- API-PRO no tiene este UNIQUE (correlativos RA/RR duplicables): corregido
    CONSTRAINT voided_documents_tenant_identifier_unique UNIQUE (tenant_id, identifier),
    CONSTRAINT voided_documents_sunat_status_check CHECK (sunat_status IN ('pendiente', 'enviado', 'aceptado', 'rechazado'))
);
CREATE INDEX voided_documents_tenant_id_ticket_index ON voided_documents (tenant_id, ticket);
CREATE INDEX voided_documents_tenant_id_fecha_comunicacion_index ON voided_documents (tenant_id, fecha_comunicacion);

-- ---------------------------------------------------------------------
-- Retenciones (20) y percepciones (40)
-- ---------------------------------------------------------------------
CREATE TABLE retentions (
    id                     bigserial PRIMARY KEY,
    tenant_id              bigint        NOT NULL REFERENCES tenants (id),
    serie                  varchar(4)    NOT NULL,
    correlativo            integer       NOT NULL CHECK (correlativo >= 0),
    cod_local              varchar(4)    NOT NULL DEFAULT '0000',
    fecha_emision          timestamp(0)  NOT NULL,
    proveedor_tipo_doc     varchar(2)    NOT NULL,
    proveedor_num_doc      varchar(15)   NOT NULL,
    proveedor_razon_social varchar(1500) NOT NULL,
    proveedor_direccion    varchar(500)  NULL,
    regimen                varchar(2)    NOT NULL,
    tasa                   numeric(5,2)  NOT NULL,
    imp_retenido           numeric(12,2) NOT NULL DEFAULT 0,
    imp_pagado             numeric(12,2) NOT NULL DEFAULT 0,
    observacion            text          NULL,
    xml_path               varchar(255)  NULL,
    cdr_path               varchar(255)  NULL,
    pdf_path               varchar(255)  NULL,
    hash_cpe               varchar(255)  NULL,
    sunat_status           varchar(30)   NOT NULL DEFAULT 'pendiente',
    sunat_code             varchar(20)   NULL,
    sunat_description      varchar(500)  NULL,
    sunat_notes            jsonb         NULL,
    ticket                 varchar(100)  NULL,
    sent_at                timestamp(0)  NULL,
    created_at             timestamp(0)  NULL,
    updated_at             timestamp(0)  NULL,
    deleted_at             timestamp(0)  NULL,
    -- API-PRO: índice NO único; corregido para impedir correlativos duplicados
    CONSTRAINT retentions_tenant_id_serie_correlativo_unique UNIQUE (tenant_id, serie, correlativo)
);
CREATE INDEX retentions_tenant_id_sunat_status_index ON retentions (tenant_id, sunat_status);

CREATE TABLE retention_items (
    id                bigserial PRIMARY KEY,
    retention_id      bigint        NOT NULL REFERENCES retentions (id) ON DELETE CASCADE,
    tipo_doc          varchar(2)    NOT NULL,
    num_doc           varchar(20)   NOT NULL,
    fecha_emision_doc date          NOT NULL,
    imp_total         numeric(12,2) NOT NULL,
    moneda            varchar(3)    NOT NULL DEFAULT 'PEN',
    pagos             jsonb         NOT NULL,
    fecha_retencion   date          NOT NULL,
    imp_retenido      numeric(12,2) NOT NULL,
    imp_pagar         numeric(12,2) NOT NULL,
    tipo_cambio       jsonb         NULL,
    created_at        timestamp(0)  NULL,
    updated_at        timestamp(0)  NULL
);
CREATE INDEX retention_items_retention_id_index ON retention_items (retention_id);

CREATE TABLE perceptions (
    id                   bigserial PRIMARY KEY,
    tenant_id            bigint        NOT NULL REFERENCES tenants (id),
    serie                varchar(4)    NOT NULL,
    correlativo          integer       NOT NULL CHECK (correlativo >= 0),
    cod_local            varchar(4)    NOT NULL DEFAULT '0000',
    fecha_emision        timestamp(0)  NOT NULL,
    cliente_tipo_doc     varchar(2)    NOT NULL,
    cliente_num_doc      varchar(15)   NOT NULL,
    cliente_razon_social varchar(1500) NOT NULL,
    cliente_direccion    varchar(500)  NULL,
    regimen              varchar(2)    NOT NULL,
    tasa                 numeric(5,2)  NOT NULL,
    imp_percibido        numeric(12,2) NOT NULL DEFAULT 0,
    imp_cobrado          numeric(12,2) NOT NULL DEFAULT 0,
    observacion          text          NULL,
    xml_path             varchar(255)  NULL,
    cdr_path             varchar(255)  NULL,
    pdf_path             varchar(255)  NULL,
    hash_cpe             varchar(255)  NULL,
    sunat_status         varchar(30)   NOT NULL DEFAULT 'pendiente',
    sunat_code           varchar(20)   NULL,
    sunat_description    varchar(500)  NULL,
    sunat_notes          jsonb         NULL,
    ticket               varchar(100)  NULL,
    sent_at              timestamp(0)  NULL,
    created_at           timestamp(0)  NULL,
    updated_at           timestamp(0)  NULL,
    deleted_at           timestamp(0)  NULL,
    CONSTRAINT perceptions_tenant_id_serie_correlativo_unique UNIQUE (tenant_id, serie, correlativo)
);
CREATE INDEX perceptions_tenant_id_sunat_status_index ON perceptions (tenant_id, sunat_status);

CREATE TABLE perception_items (
    id                bigserial PRIMARY KEY,
    perception_id     bigint        NOT NULL REFERENCES perceptions (id) ON DELETE CASCADE,
    tipo_doc          varchar(2)    NOT NULL,
    num_doc           varchar(20)   NOT NULL,
    fecha_emision_doc date          NOT NULL,
    imp_total         numeric(12,2) NOT NULL,
    moneda            varchar(3)    NOT NULL DEFAULT 'PEN',
    cobros            jsonb         NOT NULL,
    fecha_percepcion  date          NOT NULL,
    imp_percibido     numeric(12,2) NOT NULL,
    imp_cobrar        numeric(12,2) NOT NULL,
    tipo_cambio       jsonb         NULL,
    created_at        timestamp(0)  NULL,
    updated_at        timestamp(0)  NULL
);
CREATE INDEX perception_items_perception_id_index ON perception_items (perception_id);

-- ---------------------------------------------------------------------
-- Pagos de comprobantes (polimórfica). payable_type usa valores neutros
-- ('invoice', 'boleta', ...) en lugar del nombre de clase PHP de API-PRO.
-- ---------------------------------------------------------------------
CREATE TABLE payments (
    id             bigserial PRIMARY KEY,
    tenant_id      bigint        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    payable_type   varchar(255)  NOT NULL,
    payable_id     bigint        NOT NULL,
    metodo         varchar(20)   NOT NULL,
    monto          numeric(12,2) NOT NULL,
    referencia     varchar(100)  NULL,
    monto_recibido numeric(12,2) NULL,
    notas          varchar(255)  NULL,
    created_at     timestamp(0)  NULL,
    updated_at     timestamp(0)  NULL
);
CREATE INDEX payments_payable_type_payable_id_index ON payments (payable_type, payable_id);
CREATE INDEX payments_tenant_id_created_at_index ON payments (tenant_id, created_at);

-- ---------------------------------------------------------------------
-- Planes y suscripciones
-- ---------------------------------------------------------------------
CREATE TABLE plans (
    id            bigserial PRIMARY KEY,
    slug          varchar(50)   NOT NULL,
    name          varchar(100)  NOT NULL,
    price_monthly numeric(10,2) NOT NULL DEFAULT 0,
    price_yearly  numeric(10,2) NOT NULL DEFAULT 0,
    limits        jsonb         NOT NULL,
    features      jsonb         NOT NULL,
    is_unlimited  boolean       NOT NULL DEFAULT false,
    duration_days integer       NULL,
    sort_order    integer       NOT NULL DEFAULT 0,
    is_active     boolean       NOT NULL DEFAULT true,
    created_at    timestamp(0)  NULL,
    updated_at    timestamp(0)  NULL,
    CONSTRAINT plans_slug_unique UNIQUE (slug)
);

CREATE TABLE subscriptions (
    id                   bigserial PRIMARY KEY,
    tenant_id            bigint       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    plan_id              bigint       NOT NULL REFERENCES plans (id) ON DELETE RESTRICT,
    status               varchar(20)  NOT NULL DEFAULT 'trialing',
    billing_cycle        varchar(10)  NOT NULL DEFAULT 'monthly',
    trial_ends_at        timestamp(0) NULL,
    current_period_start timestamp(0) NULL,
    current_period_end   timestamp(0) NULL,
    cancelled_at         timestamp(0) NULL,
    payment_gateway      varchar(50)  NOT NULL DEFAULT 'culqi',
    gateway_customer_id  varchar(100) NULL,
    gateway_card_id      varchar(100) NULL,
    card_last_four       varchar(4)   NULL,
    card_brand           varchar(20)  NULL,
    created_at           timestamp(0) NULL,
    updated_at           timestamp(0) NULL
);
CREATE INDEX subscriptions_tenant_id_status_index ON subscriptions (tenant_id, status);
CREATE INDEX subscriptions_plan_id_index ON subscriptions (plan_id);

CREATE TABLE subscription_payments (
    id                bigserial PRIMARY KEY,
    subscription_id   bigint        NOT NULL REFERENCES subscriptions (id) ON DELETE CASCADE,
    amount            numeric(10,2) NOT NULL,
    currency          varchar(3)    NOT NULL DEFAULT 'PEN',
    status            varchar(20)   NOT NULL DEFAULT 'pending',
    gateway_charge_id varchar(100)  NULL,
    gateway_response  jsonb         NULL,
    paid_at           timestamp(0)  NULL,
    period_start      date          NULL,
    period_end        date          NULL,
    created_at        timestamp(0)  NULL,
    updated_at        timestamp(0)  NULL
);
CREATE INDEX subscription_payments_subscription_id_status_index ON subscription_payments (subscription_id, status);

-- ---------------------------------------------------------------------
-- Operación: logs de API, idempotencia, auditoría y configuración global
-- ---------------------------------------------------------------------
CREATE TABLE api_logs (
    id            bigserial PRIMARY KEY,
    tenant_id     bigint       NULL REFERENCES tenants (id) ON DELETE SET NULL,
    endpoint      varchar(255) NOT NULL,
    method        varchar(10)  NOT NULL,
    request_body  jsonb        NULL,
    response_body jsonb        NULL,
    status_code   smallint     NOT NULL,
    ip_address    varchar(45)  NOT NULL,
    user_agent    varchar(500) NULL,
    duration_ms   integer      NULL,
    created_at    timestamp(0) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX api_logs_tenant_id_created_at_index ON api_logs (tenant_id, created_at);
CREATE INDEX api_logs_created_at_index ON api_logs (created_at);

CREATE TABLE idempotency_keys (
    id              bigserial PRIMARY KEY,
    tenant_id       bigint       NOT NULL,
    idempotency_key varchar(255) NOT NULL,
    request_hash    varchar(64)  NOT NULL,
    response_status smallint     NULL,
    response_body   text         NULL,
    created_at      timestamp(0) NULL,
    CONSTRAINT idempotency_keys_tenant_id_idempotency_key_unique UNIQUE (tenant_id, idempotency_key)
);
CREATE INDEX idempotency_keys_created_at_index ON idempotency_keys (created_at);

CREATE TABLE audit_logs (
    id             bigserial PRIMARY KEY,
    tenant_id      bigint       NULL,
    user_id        bigint       NULL,
    actor_tipo     varchar(20)  NOT NULL DEFAULT 'sistema',
    actor_etiqueta varchar(255) NULL,
    accion         varchar(80)  NOT NULL,
    subject_type   varchar(255) NULL,
    subject_id     varchar(255) NULL,
    descripcion    varchar(255) NULL,
    ip             varchar(45)  NULL,
    user_agent     varchar(512) NULL,
    metadata       jsonb        NULL,
    created_at     timestamp(0) NULL
);
CREATE INDEX audit_logs_tenant_id_index ON audit_logs (tenant_id);
CREATE INDEX audit_logs_user_id_index ON audit_logs (user_id);
CREATE INDEX audit_logs_accion_index ON audit_logs (accion);
CREATE INDEX audit_logs_created_at_index ON audit_logs (created_at);

CREATE TABLE settings (
    id         bigserial PRIMARY KEY,
    key        varchar(100) NOT NULL,
    value      jsonb        NULL,
    created_at timestamp(0) NULL,
    updated_at timestamp(0) NULL,
    CONSTRAINT settings_key_unique UNIQUE (key)
);

-- ---------------------------------------------------------------------
-- Catálogos: código de producto SUNAT (UNSPSC v14, cat. 25) y ubigeo
-- ---------------------------------------------------------------------
CREATE TABLE unspsc_codes (
    codigo      varchar(8)   PRIMARY KEY,
    descripcion varchar(255) NOT NULL,
    clase       varchar(6)   NOT NULL
);
CREATE INDEX unspsc_codes_clase_index ON unspsc_codes (clase);

CREATE TABLE unspsc_taxonomy (
    nivel  varchar(10)  NOT NULL,
    codigo varchar(6)   NOT NULL,
    nombre varchar(255) NOT NULL,
    parent varchar(4)   NULL,
    PRIMARY KEY (nivel, codigo)
);
CREATE INDEX unspsc_taxonomy_parent_index ON unspsc_taxonomy (parent);

CREATE TABLE ubigeo_regiones (
    id     varchar(2)   PRIMARY KEY,
    nombre varchar(120) NOT NULL
);

CREATE TABLE ubigeo_provincias (
    id        varchar(4)   PRIMARY KEY,
    nombre    varchar(120) NOT NULL,
    region_id varchar(2)   NOT NULL
);
CREATE INDEX ubigeo_provincias_region_id_index ON ubigeo_provincias (region_id);

CREATE TABLE ubigeo_distritos (
    id           varchar(6)   PRIMARY KEY,
    nombre       varchar(120) NOT NULL,
    provincia_id varchar(4)   NOT NULL,
    region_id    varchar(2)   NOT NULL
);
CREATE INDEX ubigeo_distritos_provincia_id_index ON ubigeo_distritos (provincia_id);
CREATE INDEX ubigeo_distritos_region_id_index ON ubigeo_distritos (region_id);
