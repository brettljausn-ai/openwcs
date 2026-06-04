-- Directed conveyor sections over the path points: [[fromIdx,toIdx], …]. Lets a conveyor branch
-- (divert) at junctions; a point that starts 2+ sections is a decision point.
SET search_path TO flow;

ALTER TABLE placed_equipment ADD COLUMN sections jsonb;
