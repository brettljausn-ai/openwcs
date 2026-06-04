-- openWCS handling-unit registry (build.md §4.2, §6).
--
-- Handling-unit INSTANCE data: the physical unit (pallet, tote, carton, ...) identified
-- by a barcode (`code`), of a given handling-unit type, parked in a location and holding
-- stock. Created/tracked by the inventory service — NOT master data. Cross-service
-- references (hu_type_id, location_id) are NOT foreign keys; those rows are owned by
-- master_data (build.md §5.3). Stock rows reference this via stock.hu_id = handling_unit.hu_id.

SET search_path TO inventory;

-- ---------------------------------------------------------------------------
-- HandlingUnit — one row per physical unit, identified by its barcode within a warehouse.
-- ---------------------------------------------------------------------------
CREATE TABLE handling_unit (
    hu_id        uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id uuid        NOT NULL,
    code         text        NOT NULL,                      -- HU barcode / identifier
    hu_type_id   uuid,                                       -- ref master_data.handling_unit_type
    location_id  uuid,                                       -- ref master_data.location (current parking)
    status       text        NOT NULL DEFAULT 'ACTIVE',      -- ACTIVE | EMPTY | IN_TRANSIT | RETIRED
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 0,
    CONSTRAINT handling_unit_status_chk
        CHECK (status IN ('ACTIVE', 'EMPTY', 'IN_TRANSIT', 'RETIRED')),
    UNIQUE (warehouse_id, code)
);

CREATE INDEX handling_unit_warehouse_idx ON handling_unit (warehouse_id);
CREATE INDEX handling_unit_location_idx  ON handling_unit (location_id);
