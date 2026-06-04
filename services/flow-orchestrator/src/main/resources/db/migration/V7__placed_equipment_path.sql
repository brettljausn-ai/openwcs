-- Conveyors are polylines, not just straight boxes: a centreline path of waypoints (corners /
-- turns) with an optional closed flag for loops.
SET search_path TO flow;

ALTER TABLE placed_equipment
    ADD COLUMN path   jsonb,
    ADD COLUMN closed boolean NOT NULL DEFAULT false;
