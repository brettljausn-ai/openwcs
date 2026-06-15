-- ===========================================================================
-- slotting service — exact per-SKU daily pick tallies.
-- The ABC movers dashboard needs *true* trailing 14-day vs 90-day windowed pick
-- counts to label risers/fallers honestly (not the EWMA short-vs-long proxy it
-- shipped with). The EWMA score keeps driving classification; this table records
-- one bucket per (warehouse, sku, calendar day) so any trailing window can be
-- summed exactly.
--
-- Populated by the same velocity consumer path that increments the EWMA pending
-- picks (VelocityProjectionService), guarded by the existing velocity_processed_event
-- inbox so a redelivery/replay does not double count.
--
-- Additive only.
-- ===========================================================================
SET search_path TO slotting;

CREATE TABLE sku_pick_daily (
    warehouse_id uuid    NOT NULL,
    sku_id       uuid    NOT NULL,
    day          date    NOT NULL,                 -- calendar day of the pick (UTC)
    picks        bigint  NOT NULL DEFAULT 0,       -- pick occurrences booked that day
    PRIMARY KEY (warehouse_id, sku_id, day)
);

-- The dashboard sums trailing windows per warehouse over a date range; this index
-- serves both the per-warehouse window total and the per-SKU window sum.
CREATE INDEX sku_pick_daily_window_idx ON sku_pick_daily (warehouse_id, day);
