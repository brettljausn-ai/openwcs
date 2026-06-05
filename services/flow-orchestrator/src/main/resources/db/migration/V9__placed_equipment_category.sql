-- Denormalised equipment category supplied by the placement editor so the routing-graph projection
-- can classify equipment by TYPE (conveyor | asrs | sorter | manual-storage | other) instead of
-- guessing geometrically. Null/blank → the projection falls back to the geometric heuristic.
SET search_path TO flow;

ALTER TABLE placed_equipment ADD COLUMN category text;
