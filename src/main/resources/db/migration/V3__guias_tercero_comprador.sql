-- Guías de remisión: API-PRO valida "tercero" (proveedor) y "comprador" pero no los guarda,
-- así que nunca llegaban al XML (SellerSupplierParty / BuyerCustomerParty). Corregido.
ALTER TABLE dispatch_guides ADD COLUMN tercero jsonb NULL;
ALTER TABLE dispatch_guides ADD COLUMN comprador jsonb NULL;
