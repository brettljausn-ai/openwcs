-- Shared dispatch label info on outbound orders (ADR 0002): a ship-to address (JSONB) and an
-- optional label-template override (master-data label-template code). The per-shipper barcode
-- is NOT held here — shippers only exist after cubing, so each shipper's barcode is requested
-- from the host system per shipper at that point.
SET search_path TO orders;

ALTER TABLE outbound_order ADD COLUMN ship_to             jsonb;
ALTER TABLE outbound_order ADD COLUMN label_template_code text;
