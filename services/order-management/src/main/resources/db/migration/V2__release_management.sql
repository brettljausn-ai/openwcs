-- Release management (priority + dispatch time) and the NOT_FULFILLABLE state (ADR 0002).
SET search_path TO orders;

ALTER TABLE outbound_order ADD COLUMN dispatch_by timestamptz;  -- required ship/cut-off time

ALTER TABLE outbound_order DROP CONSTRAINT outbound_order_status_chk;
ALTER TABLE outbound_order ADD CONSTRAINT outbound_order_status_chk CHECK (status IN
    ('CREATED', 'RELEASED', 'ALLOCATED', 'PARTIALLY_ALLOCATED', 'NOT_FULFILLABLE', 'SHIPPED', 'CANCELLED'));

-- Release queue ordering: highest priority first, then earliest dispatch time.
CREATE INDEX outbound_order_release_idx ON outbound_order (warehouse_id, status, priority DESC, dispatch_by);
