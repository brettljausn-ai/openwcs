-- ===========================================================================
-- gtp service — per-station in-transit handling-unit caps (admin config).
--
-- An operator workplace can only have so many handling units (totes/HUs)
-- usefully en route to it at once before the inbound buffer/conveyor backs up.
-- These two columns cap how many HUs may have an active transport inbound to a
-- station, split by mode class so picking (the high-throughput case) and the
-- other operating modes (decant / count / QC / maintenance) can be tuned apart.
--
--   max_in_transit_picking — cap for PICKING transports inbound to the station.
--   max_in_transit_other   — cap for all non-PICKING (other) transports.
--
-- This migration is config only; the enforcement is built separately. Sensible
-- defaults keep existing stations working unchanged.
-- ===========================================================================

ALTER TABLE gtp_station
    ADD COLUMN max_in_transit_picking int NOT NULL DEFAULT 4;

ALTER TABLE gtp_station
    ADD COLUMN max_in_transit_other int NOT NULL DEFAULT 2;
