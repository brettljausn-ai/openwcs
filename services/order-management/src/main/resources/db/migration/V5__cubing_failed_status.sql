-- Propagate the allocation CUBING_FAILED outcome to the order: a SKU too large for the
-- biggest carton parks the order in CUBING_FAILED with a human-readable reason for the UI
-- (ADR 0002). The order can be re-released once master data (carton sizes / SKU dims) is fixed.
SET search_path TO orders;

ALTER TABLE outbound_order DROP CONSTRAINT outbound_order_status_chk;
ALTER TABLE outbound_order ADD CONSTRAINT outbound_order_status_chk CHECK (status IN
    ('CREATED', 'RELEASED', 'ALLOCATED', 'PARTIALLY_ALLOCATED', 'NOT_FULFILLABLE',
     'CUBING_FAILED', 'SHIPPED', 'CANCELLED'));

-- Why the order is in its current status (e.g. the SKU/line that could not be cubed). Null when n/a.
ALTER TABLE outbound_order ADD COLUMN status_detail text;
