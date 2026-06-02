-- openWCS order-management store (build.md §4.6). Service-local `orders` schema —
-- NOT in the shared DB. Outbound orders and their lines; allocation against stock is
-- delegated to the inventory service (reservation ids are recorded per line).

CREATE SCHEMA IF NOT EXISTS orders;
SET search_path TO orders;

-- ---------------------------------------------------------------------------
-- outbound_order — a fulfilment order released to the automated area.
-- ---------------------------------------------------------------------------
CREATE TABLE outbound_order (
    order_id     uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    order_ref    text        NOT NULL UNIQUE,    -- host order id / wave
    warehouse_id uuid        NOT NULL,
    customer_ref text,
    status       text        NOT NULL DEFAULT 'CREATED',
    priority     int         NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 0,
    CONSTRAINT outbound_order_status_chk CHECK (status IN
        ('CREATED', 'RELEASED', 'ALLOCATED', 'PARTIALLY_ALLOCATED', 'SHIPPED', 'CANCELLED'))
);

-- ---------------------------------------------------------------------------
-- order_line — a SKU + quantity on an order. `reservation_id` links to the
-- inventory reservation created on release.
-- ---------------------------------------------------------------------------
CREATE TABLE order_line (
    line_id        uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id       uuid          NOT NULL REFERENCES outbound_order (order_id) ON DELETE CASCADE,
    line_no        int           NOT NULL,
    sku_id         uuid          NOT NULL,        -- ref master_data.sku (no cross-service FK)
    qty            numeric(18, 4) NOT NULL,
    allocated_qty  numeric(18, 4) NOT NULL DEFAULT 0,
    status         text          NOT NULL DEFAULT 'PENDING',
    reservation_id uuid,                          -- ref inventory.reservation
    created_at     timestamptz   NOT NULL DEFAULT now(),
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    version        bigint        NOT NULL DEFAULT 0,
    CONSTRAINT order_line_qty_pos_chk CHECK (qty > 0),
    CONSTRAINT order_line_status_chk CHECK (status IN
        ('PENDING', 'ALLOCATED', 'SHORT', 'CANCELLED')),
    UNIQUE (order_id, line_no)
);

CREATE INDEX outbound_order_status_idx ON outbound_order (warehouse_id, status);
CREATE INDEX order_line_order_idx      ON order_line (order_id);
