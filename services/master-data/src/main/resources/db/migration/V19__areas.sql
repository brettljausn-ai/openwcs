-- ---------------------------------------------------------------------------
-- Areas (Master Data Areas epic): a first-class, hierarchical grouping of
-- locations within a warehouse (zone / sub-zone / aisle group, etc.). An area
-- can nest under a parent area in the same warehouse, forming a tree. Locations
-- point at the area they belong to via location.area_id. Other services resolve
-- an area reference (code) and expand an area to the set of location ids in its
-- subtree (the area plus all descendant areas) for filtering / scoping.
-- ---------------------------------------------------------------------------

SET search_path TO master_data;

CREATE TABLE area (
    area_id        uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id   uuid        NOT NULL REFERENCES warehouse (warehouse_id),
    code           text        NOT NULL,
    name           text,
    parent_area_id uuid        REFERENCES area (area_id),   -- self-ref: parent in the area tree (null at the root)
    status         text        NOT NULL DEFAULT 'ACTIVE',
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint      NOT NULL DEFAULT 0,
    UNIQUE (warehouse_id, code)
);

CREATE INDEX area_warehouse_idx   ON area (warehouse_id);
CREATE INDEX area_parent_idx      ON area (parent_area_id);

-- Link a location to its area (nullable / no default so existing rows validate).
ALTER TABLE location ADD COLUMN area_id uuid REFERENCES area (area_id);

CREATE INDEX location_area_idx ON location (area_id);
