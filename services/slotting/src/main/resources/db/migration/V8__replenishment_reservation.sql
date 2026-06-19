-- Master Data Areas epic: per-operator reservation of replenishment (collection) tasks. An operator
-- claims the next eligible PLANNED task in their area (scoped by the destination pick face), which
-- reserves it to them; the reservation is released on demand, or swept after a TTL if the operator
-- never commits to the move (started_at stays null). started_at is set on dispatch (the moment the
-- operator commits): a started task is never released or swept.
ALTER TABLE replenishment_task ADD COLUMN reserved_by          text;
ALTER TABLE replenishment_task ADD COLUMN reserved_at          timestamptz;
ALTER TABLE replenishment_task ADD COLUMN reserved_by_instance text;
ALTER TABLE replenishment_task ADD COLUMN started_at           timestamptz;

-- Supports the atomic claim query: filter PLANNED + unreserved within an area's pick-face subtree
-- (to_location_id IN (...)), scoped by warehouse.
CREATE INDEX replenishment_task_claim_idx
    ON replenishment_task (warehouse_id, status, reserved_by, to_location_id);
