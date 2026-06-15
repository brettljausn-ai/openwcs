-- openWCS dock-to-stock timing: when an HU first lands in a real storage slot.
--
-- `stored_at` is the moment an HU was booked into a STORAGE location (a non-receiving,
-- non-UNKNOWN slot) for the first time. The dashboard's dock-to-stock KPI measures the
-- span created_at -> stored_at for HUs stored today. It is nullable: HUs still sitting at
-- receiving / UNKNOWN (the put-away backlog) have no stored_at yet. History accrues from
-- this deployment forward — rows stored before this migration keep a null stored_at.

SET search_path TO inventory;

ALTER TABLE handling_unit ADD COLUMN stored_at timestamptz;

-- Stored-today queries scan by warehouse + stored_at; index the timing lookup.
CREATE INDEX handling_unit_stored_at_idx ON handling_unit (warehouse_id, stored_at);
