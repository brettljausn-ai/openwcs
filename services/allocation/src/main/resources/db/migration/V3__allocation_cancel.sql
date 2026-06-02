-- Allow an allocation to be cancelled: its held reservations are released and the plan
-- is retained for audit (ADR 0002). Closes the order-cancel → release-reservations gap.
SET search_path TO allocation;

ALTER TABLE order_allocation DROP CONSTRAINT order_allocation_status_chk;
ALTER TABLE order_allocation ADD CONSTRAINT order_allocation_status_chk
    CHECK (status IN ('FULFILLABLE', 'NOT_FULFILLABLE', 'CANCELLED'));
