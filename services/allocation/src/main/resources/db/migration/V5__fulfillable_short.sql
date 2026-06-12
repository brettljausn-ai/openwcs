-- Allow-short allocation (short allocate and release): a short order can, on an explicit
-- supervisor decision, keep what it could reserve and proceed with status FULFILLABLE_SHORT.
-- The per-line shortfall stays visible via allocation_line (requested_qty vs allocated_qty,
-- status SHORT).
SET search_path TO allocation;

ALTER TABLE order_allocation DROP CONSTRAINT order_allocation_status_chk;
ALTER TABLE order_allocation ADD CONSTRAINT order_allocation_status_chk
    CHECK (status IN ('FULFILLABLE', 'FULFILLABLE_SHORT', 'NOT_FULFILLABLE', 'CANCELLED', 'CUBING_FAILED'));
