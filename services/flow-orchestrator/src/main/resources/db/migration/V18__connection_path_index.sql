-- Node-level anchoring for equipment connections. A connection can now reference the exact PATH
-- POINT (node) on either end: from_path_index / to_path_index are indices into the placed
-- equipment's `path` (for a straight box conveyor the projection's notional endpoints 0 and 1).
-- This lets the editor link a specific ASRS outfeed stub end to a specific conveyor infeed node;
-- the routing projection then stitches exactly those two nodes (falling back to the legacy
-- exit-of-from -> entry-of-to resolution when no index is set). Function-point anchoring
-- (from_point_id / to_point_id) is unchanged and still drives workstation STOCK/ORDER roles.
SET search_path TO flow;

ALTER TABLE equipment_connection ADD COLUMN from_path_index int;
ALTER TABLE equipment_connection ADD COLUMN to_path_index   int;
