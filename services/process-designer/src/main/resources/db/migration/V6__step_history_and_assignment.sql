-- ===========================================================================
-- Step history (per-screen) + assignment, for exact resume / replay and for
-- reassigning a running instance to another operator (the "Fulfilment integrity
-- + RF resilience" epic).
--
--  1. assigned_to: who currently owns the running instance. Starts as the
--     starter (started_by stays the immutable audit of who created the run); a
--     supervisor can reassign it. Backfilled from started_by for existing rows.
--  2. process_step_event: an ordered, append-only log of every advance (each
--     screen step the client posts, each task-step checkpoint, plus audit rows
--     like a reassign). Lets the server hold the exact current step + data for a
--     mid-flow device swap, and lets a supervisor replay what an operator did.
-- ===========================================================================

ALTER TABLE process_instance ADD COLUMN assigned_to text;

-- Existing running instances are owned by whoever started them.
UPDATE process_instance SET assigned_to = started_by WHERE assigned_to IS NULL;

-- Append-only, per-instance ordered step log (screen advances + task checkpoints
-- + audit rows). seq is the client/server-assigned monotonic position within the
-- instance; the (instance_id, seq) uniqueness makes a re-posted advance idempotent.
CREATE TABLE process_step_event (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id uuid        NOT NULL REFERENCES process_instance(id) ON DELETE CASCADE,
    seq         bigint      NOT NULL,
    step_id     text,
    step_type   text,
    data        jsonb,
    entered_by  text,                                  -- X-Auth-User who posted the advance (audit)
    at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT process_step_event_instance_seq_uq UNIQUE (instance_id, seq)
);

-- Replay / resume reads the events of one instance in order.
CREATE INDEX process_step_event_instance_seq_idx ON process_step_event (instance_id, seq);
