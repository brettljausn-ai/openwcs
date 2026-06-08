-- ===========================================================================
-- gtp service — carry the tote's source storage location onto the queue entry,
-- and add a maintenance order register for the dirty-tote exception.
--
-- The source location lets the station auto store-back a tote (returns it to the
-- storage location it came from) once all its station work is done. The
-- maintenance_order table records totes pulled out of circulation for cleaning
-- (the dirty-tote operator exception): they go to maintenance, not back to stock.
-- ===========================================================================

ALTER TABLE station_queue_entry ADD COLUMN location_id uuid;

CREATE TABLE maintenance_order (
    maintenance_order_id uuid PRIMARY KEY,
    warehouse_id         uuid        NOT NULL,
    hu_id                uuid,
    hu_code              text,
    gtp_station_id       uuid,
    sku_id               uuid,
    sku_code             text,
    reason               text        NOT NULL DEFAULT 'CLEANING',
    status               text        NOT NULL DEFAULT 'OPEN',
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    version              bigint      NOT NULL DEFAULT 0
);
