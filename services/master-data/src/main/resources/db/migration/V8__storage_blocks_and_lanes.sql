-- ---------------------------------------------------------------------------
-- Storage blocks + rack-lane attributes for the slotting subsystem (ADR 0003).
--
-- A *storage block* groups storage locations that are slotted as one pool. For
-- automated systems (shuttle/crane ASRS, AutoStore, AMR-GTP) a SKU is slotted to
-- the BLOCK as a whole — the put-away engine chooses an actual location at
-- put-away time. Manual pick faces use LOCATION granularity (one SKU+UoM per
-- fixed location). The lane attributes added to `location` let the put-away
-- engine reason about velocity-to-exit, multi-deep lanes, and aisle balancing.
-- ---------------------------------------------------------------------------

CREATE TABLE storage_block (
    block_id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id         uuid        NOT NULL REFERENCES warehouse (warehouse_id),
    code                 text        NOT NULL,
    storage_type         text        NOT NULL,   -- SHUTTLE_ASRS | CRANE_ASRS | AUTOSTORE | AMR_GTP | MANUAL_PICK | RESERVE_RACK
    slotting_granularity text        NOT NULL DEFAULT 'BLOCK',  -- BLOCK (automated pool) | LOCATION (fixed pick face)
    equipment_id         uuid        REFERENCES equipment (equipment_id),
    is_gtp               boolean     NOT NULL DEFAULT false,     -- goods-to-person (picked at a station, not in-aisle)
    status               text        NOT NULL DEFAULT 'ACTIVE',
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    version              bigint      NOT NULL DEFAULT 0,
    CONSTRAINT storage_block_type_chk CHECK (storage_type IN
        ('SHUTTLE_ASRS', 'CRANE_ASRS', 'AUTOSTORE', 'AMR_GTP', 'MANUAL_PICK', 'RESERVE_RACK')),
    CONSTRAINT storage_block_granularity_chk CHECK (slotting_granularity IN ('BLOCK', 'LOCATION')),
    UNIQUE (warehouse_id, code)
);

-- Rack-lane attributes on location (all nullable / defaulted so existing rows validate).
ALTER TABLE location ADD COLUMN block_id         uuid    REFERENCES storage_block (block_id);
ALTER TABLE location ADD COLUMN aisle            text;                       -- aisle identifier within the block
ALTER TABLE location ADD COLUMN rack_level       integer;                    -- tier / level
ALTER TABLE location ADD COLUMN lane_depth       integer NOT NULL DEFAULT 1; -- multi-deep capacity in HUs (1 = single-deep, 3 = triple-deep)
ALTER TABLE location ADD COLUMN distance_to_exit numeric;                    -- travel-time/sequence to the aisle port/P&D; lower = faster-mover zone

CREATE INDEX storage_block_warehouse_idx ON storage_block (warehouse_id);
CREATE INDEX location_block_idx          ON location (block_id);
CREATE INDEX location_block_aisle_idx    ON location (warehouse_id, block_id, aisle);
