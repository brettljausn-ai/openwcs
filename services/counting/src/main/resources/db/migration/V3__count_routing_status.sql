-- ASRS count-tote routing resilience: persist a routing outcome (status + reason) per count task so
-- the background retry sweep (CountRoutingScheduler) can re-attempt failed routing every minute and
-- the UI can surface why a task did or did not route. count_line.routed makes a retry idempotent —
-- only unrouted lines create transports, so re-routing never duplicates a move.
-- Flyway default-schema = counting, so no SET search_path here — plain DDL.

ALTER TABLE count_task ADD COLUMN routing_status text NOT NULL DEFAULT 'PENDING'; -- PENDING | ROUTED | NOT_REQUIRED | FAILED
ALTER TABLE count_task ADD COLUMN routing_reason text;
ALTER TABLE count_task ADD COLUMN routing_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE count_task ADD COLUMN routing_attempt_at timestamptz;

ALTER TABLE count_line ADD COLUMN routed boolean NOT NULL DEFAULT false;
