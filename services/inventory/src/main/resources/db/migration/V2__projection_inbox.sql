-- Idempotency inbox for the stock projection (build.md §5.5: "idempotent consumers
-- keyed on event_id"). Every stock-affecting transaction-log event is recorded here
-- exactly once; a replay/redelivery of the same event_id is a no-op.
SET search_path TO inventory;

CREATE TABLE processed_event (
    event_id   uuid        PRIMARY KEY,
    event_type text        NOT NULL,
    stream_id  text,
    seq        bigint,
    applied_at timestamptz NOT NULL DEFAULT now()
);

-- Seed the cursor row the stock projection advances as it consumes the log (§5.4).
INSERT INTO projection_offset (projection) VALUES ('stock')
ON CONFLICT (projection) DO NOTHING;
