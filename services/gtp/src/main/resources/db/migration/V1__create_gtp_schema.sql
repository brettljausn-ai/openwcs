-- ===========================================================================
-- gtp service — own schema (ADR 0006). Goods-to-person (GTP) station execution:
-- station + node configuration, open per-destination demand, and the
-- pick-and-put work cycle (present a stock HU -> put-list -> confirmations).
-- References master-data / inventory / order-management / allocation entities
-- by UUID only (no cross-schema FKs — those services own their data).
-- ===========================================================================

-- A goods-to-person workplace/station.
--   mode = ORDER_LOCATION  -> order HUs in fixed (usually conveyor) locations
--   mode = PUT_WALL        -> a rack/put-wall of lit cubbies (typical AMR goods-to-rack)
-- The execution shape is identical; mode documents the physical realisation.
CREATE TABLE gtp_station (
    gtp_station_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id   uuid        NOT NULL,
    code           text        NOT NULL,
    mode           text        NOT NULL DEFAULT 'ORDER_LOCATION',
    status         text        NOT NULL DEFAULT 'ACTIVE',
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT gtp_station_mode_chk CHECK (mode IN ('ORDER_LOCATION', 'PUT_WALL')),
    UNIQUE (warehouse_id, code)
);

-- A position at a station.
--   role = STOCK  -> where a stock HU (one SKU) is presented to the operator
--   role = ORDER  -> an order destination: an order-HU location (ORDER_LOCATION mode)
--                    or a put-wall cubby (PUT_WALL mode). Holds a current order HU and
--                    optionally a put-light id.
CREATE TABLE station_node (
    station_node_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    gtp_station_id  uuid        NOT NULL REFERENCES gtp_station (gtp_station_id) ON DELETE CASCADE,
    role            text        NOT NULL,
    code            text        NOT NULL,
    put_light_id    text,                                     -- physical light/display id (ORDER role)
    location_id     uuid,                                     -- master-data location, when fixed
    order_hu_id     uuid,                                     -- order HU currently bound (ORDER role)
    position        integer     NOT NULL DEFAULT 0,           -- display ordering within the station
    status          text        NOT NULL DEFAULT 'ACTIVE',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT station_node_role_chk CHECK (role IN ('STOCK', 'ORDER')),
    UNIQUE (gtp_station_id, code)
);

-- Open demand pinned to an ORDER node: how much of a SKU still needs to be put there
-- for a given order/line. The station clears this from presented stock HUs (the batch:
-- one stock HU of a SKU serves many of these across the station).
CREATE TABLE destination_demand (
    destination_demand_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    station_node_id       uuid        NOT NULL REFERENCES station_node (station_node_id) ON DELETE CASCADE,
    order_ref             text        NOT NULL,
    order_line_id         uuid,
    sku_id                uuid        NOT NULL,
    requested_qty         numeric     NOT NULL,
    putted_qty            numeric     NOT NULL DEFAULT 0,
    status                text        NOT NULL DEFAULT 'OPEN',  -- OPEN | COMPLETED | CANCELLED
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT dest_demand_status_chk CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT dest_demand_qty_chk    CHECK (putted_qty >= 0 AND putted_qty <= requested_qty)
);

-- A pick-and-put work cycle: a stock HU of one SKU presented at a STOCK node, the demand
-- it was matched against (the put-list lives in put_instruction), and the remaining stock.
CREATE TABLE work_cycle (
    work_cycle_id   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    gtp_station_id  uuid        NOT NULL REFERENCES gtp_station (gtp_station_id) ON DELETE CASCADE,
    stock_node_id   uuid        NOT NULL REFERENCES station_node (station_node_id),
    stock_hu_id     uuid        NOT NULL,
    sku_id          uuid        NOT NULL,
    presented_qty   numeric     NOT NULL,
    remaining_qty   numeric     NOT NULL,
    status          text        NOT NULL DEFAULT 'OPEN',     -- OPEN | COMPLETED | CLOSED
    details         jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT work_cycle_status_chk CHECK (status IN ('OPEN', 'COMPLETED', 'CLOSED')),
    CONSTRAINT work_cycle_qty_chk    CHECK (remaining_qty >= 0 AND remaining_qty <= presented_qty)
);

-- One line of a cycle's put-list: put `qty` of the cycle's SKU into a destination node,
-- lighting its put-light. Confirming decrements the cycle remaining + the demand putted.
CREATE TABLE put_instruction (
    put_instruction_id    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    work_cycle_id         uuid        NOT NULL REFERENCES work_cycle (work_cycle_id) ON DELETE CASCADE,
    destination_node_id   uuid        NOT NULL REFERENCES station_node (station_node_id),
    destination_demand_id uuid        NOT NULL REFERENCES destination_demand (destination_demand_id),
    order_ref             text        NOT NULL,
    order_line_id         uuid,
    order_hu_id           uuid,
    put_light_id          text,
    qty                   numeric     NOT NULL,
    confirmed_qty         numeric     NOT NULL DEFAULT 0,
    status                text        NOT NULL DEFAULT 'OPEN',  -- OPEN | CONFIRMED | SHORT | CANCELLED
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT put_instr_status_chk CHECK (status IN ('OPEN', 'CONFIRMED', 'SHORT', 'CANCELLED')),
    CONSTRAINT put_instr_qty_chk    CHECK (qty > 0 AND confirmed_qty >= 0 AND confirmed_qty <= qty)
);

CREATE INDEX station_node_station_idx     ON station_node (gtp_station_id);
CREATE INDEX dest_demand_node_idx         ON destination_demand (station_node_id);
CREATE INDEX dest_demand_open_idx         ON destination_demand (sku_id, status);
CREATE INDEX work_cycle_station_idx       ON work_cycle (gtp_station_id, status);
CREATE INDEX put_instruction_cycle_idx    ON put_instruction (work_cycle_id);
CREATE INDEX put_instruction_demand_idx   ON put_instruction (destination_demand_id);
