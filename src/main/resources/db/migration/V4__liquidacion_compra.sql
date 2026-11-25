-- Liquidación de compra electrónica (tipo 04): módulo nuevo, API-PRO no lo tiene.
-- Estructura según el Anexo N.° 9-A (literal e) y el Anexo N.° 27 de la RS 097-2012/SUNAT,
-- incorporados por la RS 244-2019/SUNAT; reglas de validación SUNAT del 24/04/2026.

CREATE TABLE purchase_settlements (
    id                      bigserial PRIMARY KEY,
    tenant_id               bigint        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    sucursal_id             bigint        NULL REFERENCES sucursales (id) ON DELETE SET NULL,
    cod_local               varchar(4)    NOT NULL DEFAULT '0000',
    serie                   varchar(4)    NOT NULL,
    correlativo             integer       NOT NULL CHECK (correlativo > 0),
    fecha_emision           timestamp(0)  NOT NULL,
    tipo_operacion          varchar(4)    NOT NULL DEFAULT '0501',
    tipo_moneda             varchar(3)    NOT NULL DEFAULT 'PEN',
    -- Vendedor (persona natural sin RUC): DNI o carné de extranjería
    vendedor_tipo_doc       varchar(1)    NOT NULL,
    vendedor_num_doc        varchar(15)   NOT NULL,
    vendedor_nombre         varchar(1500) NOT NULL,
    vendedor_direccion      varchar(250)  NOT NULL,
    vendedor_ubigeo         varchar(6)    NOT NULL,
    vendedor_tipo_domicilio varchar(2)    NOT NULL,  -- catálogo 60
    -- Lugar de la operación
    lugar_direccion         varchar(250)  NOT NULL,
    lugar_ubigeo            varchar(6)    NOT NULL,
    lugar_tipo              varchar(2)    NOT NULL,  -- catálogo 60
    productos_agropecuarios boolean       NOT NULL DEFAULT false,
    mto_oper_gravadas       numeric(12,2) NOT NULL DEFAULT 0,
    mto_oper_exoneradas     numeric(12,2) NOT NULL DEFAULT 0,
    mto_oper_inafectas      numeric(12,2) NOT NULL DEFAULT 0,
    mto_igv                 numeric(12,2) NOT NULL DEFAULT 0,
    porcentaje_igv          numeric(5,2)  NOT NULL DEFAULT 18,
    valor_venta             numeric(12,2) NOT NULL DEFAULT 0,
    mto_base_renta          numeric(12,2) NOT NULL DEFAULT 0,
    porcentaje_renta        numeric(5,2)  NOT NULL DEFAULT 0,
    mto_retencion_renta     numeric(12,2) NOT NULL DEFAULT 0,
    sub_total               numeric(12,2) NOT NULL DEFAULT 0,  -- TaxInclusiveAmount
    mto_imp_neto            numeric(12,2) NOT NULL DEFAULT 0,  -- PayableAmount: neto a pagar al vendedor
    leyenda                 varchar(500)  NULL,
    observacion             varchar(500)  NULL,
    guias                   jsonb         NULL,
    placa_vehiculo          varchar(8)    NULL,
    xml_path                varchar(500)  NULL,
    cdr_path                varchar(500)  NULL,
    pdf_path                varchar(500)  NULL,
    hash_cpe                varchar(100)  NULL,
    sunat_status            varchar(30)   NOT NULL DEFAULT 'pendiente',
    sunat_code              varchar(20)   NULL,
    sunat_description       text          NULL,
    sunat_notes             jsonb         NULL,
    cdr_fecha               timestamp(0)  NULL,  -- recepción del CDR aceptado: base del plazo de reversión
    payment_status          varchar(20)   NOT NULL DEFAULT 'pendiente',
    monto_pagado            numeric(12,2) NOT NULL DEFAULT 0,
    sent_at                 timestamp(0)  NULL,
    created_at              timestamp(0)  NULL,
    updated_at              timestamp(0)  NULL,
    deleted_at              timestamp(0)  NULL,
    CONSTRAINT purchase_settlements_tenant_serie_corr_unique UNIQUE (tenant_id, serie, correlativo),
    CONSTRAINT purchase_settlements_status_check CHECK (sunat_status IN
        ('pendiente', 'enviado', 'aceptado', 'rechazado', 'reversion_en_proceso', 'revertido'))
);
CREATE INDEX purchase_settlements_tenant_status_index ON purchase_settlements (tenant_id, sunat_status);
CREATE INDEX purchase_settlements_tenant_fecha_index ON purchase_settlements (tenant_id, fecha_emision);
CREATE INDEX purchase_settlements_tenant_vendedor_index ON purchase_settlements (tenant_id, vendedor_num_doc);

CREATE TABLE purchase_settlement_items (
    id                     bigserial PRIMARY KEY,
    purchase_settlement_id bigint        NOT NULL REFERENCES purchase_settlements (id) ON DELETE CASCADE,
    codigo                 varchar(30)   NULL,
    cod_producto_sunat     varchar(8)    NOT NULL,
    descripcion            varchar(500)  NOT NULL,
    unidad                 varchar(3)    NOT NULL,
    cantidad               numeric(22,10) NOT NULL,
    valor_unitario         numeric(22,10) NOT NULL,
    precio_unitario        numeric(22,10) NOT NULL,
    tip_afe_igv            varchar(2)    NOT NULL,
    valor_venta            numeric(12,2) NOT NULL,
    porcentaje_igv         numeric(5,2)  NOT NULL,
    igv                    numeric(12,2) NOT NULL,
    porcentaje_renta       numeric(5,2)  NOT NULL,
    renta                  numeric(12,2) NOT NULL,
    oro                    jsonb         NULL,  -- tipo de operación 0503 (catálogo 55: 6000, 6004, 6005, 6006)
    created_at             timestamp(0)  NULL,
    updated_at             timestamp(0)  NULL
);
CREATE INDEX purchase_settlement_items_parent_index ON purchase_settlement_items (purchase_settlement_id);
