-- An order whose SKU is too large for the biggest available carton cannot be cubed: it is
-- not shipped, its reservations are released, and the plan is kept in a CUBING_FAILED state
-- with a human-readable reason so an operator can resolve it in the UI (ADR 0002).
SET search_path TO allocation;

ALTER TABLE order_allocation DROP CONSTRAINT order_allocation_status_chk;
ALTER TABLE order_allocation ADD CONSTRAINT order_allocation_status_chk
    CHECK (status IN ('FULFILLABLE', 'NOT_FULFILLABLE', 'CANCELLED', 'CUBING_FAILED'));

-- Why the order is in its current status (e.g. the SKU/line that did not fit). Null when n/a.
ALTER TABLE order_allocation ADD COLUMN status_detail text;
