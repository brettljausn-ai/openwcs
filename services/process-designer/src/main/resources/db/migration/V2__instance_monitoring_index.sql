-- ===========================================================================
-- Phase 2: instance history / monitoring. The monitoring list
-- (GET /api/process-designer/instances?processKey=&status=&warehouseId=&limit=)
-- returns newest-first summaries; started_by + started_at/updated_at already
-- exist on process_instance (V1). Add an index to keep the newest-first,
-- optionally-filtered list query cheap as instances accumulate.
-- ===========================================================================

CREATE INDEX IF NOT EXISTS process_instance_started_at_idx
    ON process_instance (started_at DESC);

CREATE INDEX IF NOT EXISTS process_instance_status_idx
    ON process_instance (status);

CREATE INDEX IF NOT EXISTS process_instance_warehouse_idx
    ON process_instance (warehouse_id);
