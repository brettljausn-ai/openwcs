-- ===========================================================================
-- gtp service — link a routed station queue entry to its count task + line.
--
-- When the counting service routes a stock-count tote to a workplace, it now
-- carries the count task + line ids so the station UI can drive the at-station
-- blind count straight from the queue entry (operator counts the tote in front
-- of them and posts the count back to the line). Both are nullable: only
-- STOCK_COUNT entries carry them.
-- ===========================================================================

ALTER TABLE station_queue_entry ADD COLUMN count_task_id uuid;
ALTER TABLE station_queue_entry ADD COLUMN count_line_id uuid;
