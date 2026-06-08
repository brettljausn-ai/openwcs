-- At-station blind count state machine. When a count tote is worked at a GTP station the operator
-- counts it blind (they never see the system qty or their prior count). The line tracks the prior
-- station count and a small state machine: PENDING -> RECOUNT -> ACCEPTED | ADJUSTED. A confirmed
-- variance (two counts agree and differ from the system qty) posts a host-visible stock adjustment.
-- Flyway default-schema = counting, so no SET search_path here — plain DDL.

ALTER TABLE count_line ADD COLUMN station_last_count numeric(18, 4);
ALTER TABLE count_line ADD COLUMN station_count_state text NOT NULL DEFAULT 'PENDING'; -- PENDING | RECOUNT | ACCEPTED | ADJUSTED
