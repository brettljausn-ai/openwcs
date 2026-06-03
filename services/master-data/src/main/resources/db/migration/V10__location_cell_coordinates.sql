-- ---------------------------------------------------------------------------
-- Explicit cell coordinates so a handling unit's position is always exactly known
-- (ADR 0003, option 2 / cell-as-location). A storage location row is one cell,
-- uniquely identified by (warehouse, aisle, side, pos_x, pos_y, pos_z). A multi-deep
-- lane is the set of cells sharing (aisle, side, pos_x, pos_y) with pos_z = 1..N
-- (z1 = aisle face … zN = deepest). Conveyors/stations are locations too (a function
-- group, optionally a parent of child segment locations via parent_id).
--
-- Additive + nullable so existing rows validate; the legacy rack_level / lane_depth
-- columns are kept for compatibility (pos_y supersedes rack_level as the vertical level).
-- ---------------------------------------------------------------------------

ALTER TABLE location ADD COLUMN side  text;     -- e.g. LEFT | RIGHT (the two racks of an aisle)
ALTER TABLE location ADD COLUMN pos_x integer;  -- horizontal position along the aisle
ALTER TABLE location ADD COLUMN pos_y integer;  -- vertical level (supersedes rack_level)
ALTER TABLE location ADD COLUMN pos_z integer;  -- depth position (1 = face, N = deepest)

-- Each physical cell is unique within its block; partial index over rows that carry coordinates.
CREATE UNIQUE INDEX location_cell_uk
    ON location (warehouse_id, block_id, aisle, side, pos_x, pos_y, pos_z)
    WHERE pos_x IS NOT NULL;
