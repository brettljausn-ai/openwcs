-- openWCS inventory / stock store (build.md §4.2, §16).
--
-- Service-local store — NOT in the shared DB. Stock is HARD-PERSISTED with the
-- current level as `qty` (durable authoritative state, not recomputed on demand),
-- while the transaction log (txlog service) stays the immutable audit trail that
-- can replay/rebuild this store on demand.
--
-- Batch/Lot and SerialUnit are INSTANCE data: created during receiving and tracked
-- through inventory + the tx log (build.md §6 — "NOT SKU master data"). They live
-- here, not in master_data. Cross-service references (sku_id, location_id, hu_id)
-- are NOT foreign keys — those rows are owned by other services (build.md §5.3).

CREATE SCHEMA IF NOT EXISTS inventory;
SET search_path TO inventory;

-- ---------------------------------------------------------------------------
-- Batch / Lot — captured at goods-in for batch/date-tracked SKUs.
-- ---------------------------------------------------------------------------
CREATE TABLE batch (
    batch_id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id      uuid        NOT NULL,
    sku_id            uuid        NOT NULL,        -- ref master_data.sku (no cross-service FK)
    batch_number      text        NOT NULL,
    supplier_lot      text,
    production_date   date,
    best_before_date  date,
    expiry_date       date,
    received_at       timestamptz,
    country_of_origin text,
    quality_status    text        NOT NULL DEFAULT 'RELEASED',  -- RELEASED | QUARANTINE | BLOCKED
    attributes        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT batch_quality_status_chk
        CHECK (quality_status IN ('RELEASED', 'QUARANTINE', 'BLOCKED')),
    UNIQUE (warehouse_id, sku_id, batch_number)
);

-- ---------------------------------------------------------------------------
-- SerialUnit — one row PER PHYSICAL PIECE, only for is_serial_tracked SKUs.
-- Gives full per-piece genealogy through the tx log.
-- ---------------------------------------------------------------------------
CREATE TABLE serial_unit (
    serial_id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id        uuid        NOT NULL,
    sku_id              uuid        NOT NULL,
    batch_id            uuid        REFERENCES batch (batch_id),
    serial_number       text        NOT NULL,
    status              text        NOT NULL DEFAULT 'IN_STOCK',  -- IN_STOCK | ALLOCATED | PICKED | SHIPPED | BLOCKED
    current_location_id uuid,                                     -- ref master_data.location
    current_hu_id       uuid,                                     -- handling-unit instance
    received_at         timestamptz,
    attributes          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint      NOT NULL DEFAULT 0,
    CONSTRAINT serial_unit_status_chk
        CHECK (status IN ('IN_STOCK', 'ALLOCATED', 'PICKED', 'SHIPPED', 'BLOCKED')),
    UNIQUE (warehouse_id, sku_id, serial_number)
);

-- ---------------------------------------------------------------------------
-- Stock — the durable authoritative current-state table. Keyed by
-- SKU x batch/lot x location x HU x status (§4.2/§16). qty is normalized to the
-- SKU base UoM. Stock marked non-AVAILABLE (LOCKED/QUARANTINE/DAMAGED) is excluded
-- from allocatable/available quantity reported to the host.
-- ---------------------------------------------------------------------------
CREATE TABLE stock (
    stock_id      uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id  uuid          NOT NULL,
    sku_id        uuid          NOT NULL,
    batch_id      uuid          REFERENCES batch (batch_id),   -- nullable: non-batch SKUs
    location_id   uuid          NOT NULL,                      -- ref master_data.location
    hu_id         uuid,                                        -- nullable: handling-unit instance
    status        text          NOT NULL DEFAULT 'AVAILABLE',  -- AVAILABLE | LOCKED | QUARANTINE | DAMAGED
    qty           numeric(18, 4) NOT NULL DEFAULT 0,           -- in SKU base UoM
    uom_code      text          NOT NULL DEFAULT 'EACH',       -- base unit label (build.md §12: all math in base UoM)
    last_event_id uuid,                                        -- last tx-log event applied (idempotency / projection cursor)
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    version       bigint        NOT NULL DEFAULT 0,
    CONSTRAINT stock_qty_nonneg_chk CHECK (qty >= 0),
    CONSTRAINT stock_status_chk
        CHECK (status IN ('AVAILABLE', 'LOCKED', 'QUARANTINE', 'DAMAGED')),
    -- one row per physical bucket; NULLS NOT DISTINCT (PG15+) treats null batch/hu as equal.
    CONSTRAINT stock_bucket_uniq
        UNIQUE NULLS NOT DISTINCT (warehouse_id, sku_id, batch_id, location_id, hu_id, status)
);

-- ---------------------------------------------------------------------------
-- Reservation — soft allocation of available stock to an outbound order/wave.
-- Available-to-promise = SUM(stock.qty WHERE status='AVAILABLE') - SUM(held reservations).
-- ---------------------------------------------------------------------------
CREATE TABLE reservation (
    reservation_id uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id   uuid          NOT NULL,
    sku_id         uuid          NOT NULL,
    batch_id       uuid          REFERENCES batch (batch_id),
    location_id    uuid,                                       -- optional pin to a source location
    hu_id          uuid,
    order_ref      text,                                       -- outbound order / wave id
    correlation_id uuid,                                       -- owning process instance (build.md §5.5)
    qty            numeric(18, 4) NOT NULL,
    status         text          NOT NULL DEFAULT 'HELD',      -- HELD | CONSUMED | RELEASED | EXPIRED
    created_at     timestamptz   NOT NULL DEFAULT now(),
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    expires_at     timestamptz,
    version        bigint        NOT NULL DEFAULT 0,
    CONSTRAINT reservation_qty_pos_chk CHECK (qty > 0),
    CONSTRAINT reservation_status_chk
        CHECK (status IN ('HELD', 'CONSUMED', 'RELEASED', 'EXPIRED'))
);

-- ---------------------------------------------------------------------------
-- Projection cursor — supports the rebuild/replay story (build.md §5.4). Records
-- how far this projection has consumed the transaction log so consumers are
-- idempotent (keyed on event_id) and replay can resume.
-- ---------------------------------------------------------------------------
CREATE TABLE projection_offset (
    projection    text        PRIMARY KEY,
    last_event_id uuid,
    last_seq      bigint,
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Indexes for common lookups.
-- ---------------------------------------------------------------------------
CREATE INDEX stock_sku_idx          ON stock (warehouse_id, sku_id);
CREATE INDEX stock_location_idx     ON stock (location_id);
CREATE INDEX stock_batch_idx        ON stock (batch_id);
CREATE INDEX stock_available_idx    ON stock (warehouse_id, sku_id) WHERE status = 'AVAILABLE';
CREATE INDEX batch_sku_idx          ON batch (warehouse_id, sku_id);
CREATE INDEX batch_expiry_idx       ON batch (expiry_date);          -- FEFO allocation (§6)
CREATE INDEX serial_unit_sku_idx    ON serial_unit (warehouse_id, sku_id);
CREATE INDEX reservation_sku_idx    ON reservation (warehouse_id, sku_id) WHERE status = 'HELD';
CREATE INDEX reservation_order_idx  ON reservation (order_ref);
