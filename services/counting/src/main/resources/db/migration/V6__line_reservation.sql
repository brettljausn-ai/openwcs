-- Per-operator reservation on a count line. An operator working a Master Data Area claims the next
-- eligible line (status PENDING, not yet reserved, location inside the area subtree) atomically via
-- FOR UPDATE SKIP LOCKED, which stamps reserved_by (the operator), reserved_at (now), and
-- reserved_by_instance (the handheld/runtime instance that holds it). started_at is set the moment
-- the operator first records a count for the line: a started line is immune to release and to the
-- stale-reservation sweeper (the operator is mid-count, do not yank it away). A reservation with
-- started_at IS NULL is releasable (explicit release on app close) and reclaimable by the sweeper
-- once it has aged past the TTL.
-- Flyway default-schema = counting, so no SET search_path here, plain DDL.

ALTER TABLE count_line ADD COLUMN reserved_by          text;
ALTER TABLE count_line ADD COLUMN reserved_at          timestamptz;
ALTER TABLE count_line ADD COLUMN reserved_by_instance text;
ALTER TABLE count_line ADD COLUMN started_at           timestamptz;

-- Supports the claim query: filter by warehouse + status, skip already-reserved rows, and restrict
-- to the area subtree's location ids.
CREATE INDEX count_line_claim_idx ON count_line (warehouse_id, status, reserved_by, location_id);
