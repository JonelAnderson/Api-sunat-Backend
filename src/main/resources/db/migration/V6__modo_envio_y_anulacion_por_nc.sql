-- V6: correcciones de la corrida de la colección Postman (P7 y P8).
-- Corrección: cómo se creó el comprobante (enviar_automatico / solo_registro). PUT /{id} lo respeta
-- en vez de encolar siempre el envío: un borrador manual o una boleta de resumen no se envían al editarlos.
-- automatico | manual | solo_registro. Las filas existentes quedan como "automatico" (comportamiento anterior).
ALTER TABLE invoices ADD COLUMN modo_envio varchar(20) NOT NULL DEFAULT 'automatico';
ALTER TABLE boletas ADD COLUMN modo_envio varchar(20) NOT NULL DEFAULT 'automatico';
ALTER TABLE credit_notes ADD COLUMN modo_envio varchar(20) NOT NULL DEFAULT 'automatico';
ALTER TABLE debit_notes ADD COLUMN modo_envio varchar(20) NOT NULL DEFAULT 'automatico';

-- Corrección: comprobante anulado por una NC de anulación total aceptada (catálogo 09: 01, 02, 06).
-- Deja de ser cuenta por cobrar; su estado SUNAT sigue "aceptado".
ALTER TABLE invoices DROP CONSTRAINT invoices_payment_status_check,
    ADD CONSTRAINT invoices_payment_status_check CHECK (payment_status IN ('pendiente', 'parcial', 'pagado', 'anulado'));
ALTER TABLE boletas DROP CONSTRAINT boletas_payment_status_check,
    ADD CONSTRAINT boletas_payment_status_check CHECK (payment_status IN ('pendiente', 'parcial', 'pagado', 'anulado'));
