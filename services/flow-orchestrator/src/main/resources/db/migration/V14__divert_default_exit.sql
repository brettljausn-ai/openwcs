-- Divert default directions (product requirement): each conveyor divert function point can carry a
-- default direction (STRAIGHT = continue the main line, BRANCH = take the divert's branch) chosen in
-- the topology screen. The routing projection resolves that choice to a concrete neighbour node and
-- stores it on the projected divert node, so per-scan routing can send unrouted totes along the
-- default (or stop them at the divert when no default is configured).
SET search_path TO flow;

-- STRAIGHT | BRANCH, or null (no default: an unrouted tote stops at the divert).
ALTER TABLE equipment_function_point ADD COLUMN default_exit text;

-- The node code of the resolved default exit for this (divert) node, or null.
ALTER TABLE conveyor_node ADD COLUMN default_exit_code text;
