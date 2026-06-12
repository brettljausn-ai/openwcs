-- Storage-density history for the Reporting screens: one row per storage block per day with
-- how many of the block's cells (locations) were physically occupied. Written by the daily
-- ShedLock-guarded sweeper (StorageDensitySweeper) and on demand by the report endpoint when
-- today has no snapshot yet. Block/location ownership lives in master-data (no cross-service FK).
SET search_path TO inventory;

CREATE TABLE storage_density_snapshot (
    snapshot_id    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id   uuid        NOT NULL,
    block_id       uuid        NOT NULL,        -- ref master_data.storage_block (no cross-service FK)
    day            date        NOT NULL,
    occupied_cells int         NOT NULL,
    total_cells    int         NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint      NOT NULL DEFAULT 0,
    UNIQUE (warehouse_id, block_id, day)
);

CREATE INDEX storage_density_snapshot_wh_day_idx ON storage_density_snapshot (warehouse_id, day);
