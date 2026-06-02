-- openWCS transaction log (build.md §5.2). The immutable, append-only system of
-- record for every business/physical event. Owned by the txlog service; one of the
-- only two schemas allowed in the shared PostgreSQL instance (§5.1).

CREATE SCHEMA IF NOT EXISTS transaction_log;
SET search_path TO transaction_log;

-- ---------------------------------------------------------------------------
-- events — append-only; never updated or deleted (corrections are compensating
-- events). `position` gives a global total order for replay; (stream_id, seq) is
-- the per-stream ordering / optimistic-concurrency guarantee.
-- ---------------------------------------------------------------------------
CREATE TABLE events (
    position        bigserial   NOT NULL,
    event_id        uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    stream_id       text        NOT NULL,        -- handling-unit / order / location id …
    seq             bigint      NOT NULL,        -- per-stream sequence
    event_type      text        NOT NULL,        -- GoodsReceived, PutawayCompleted, Picked …
    occurred_at     timestamptz NOT NULL,        -- device / business time
    recorded_at     timestamptz NOT NULL DEFAULT now(),  -- system time
    actor           text,                        -- user / service / device
    correlation_id  uuid,                        -- process instance / task
    payload         jsonb       NOT NULL,        -- event-specific, schema-versioned
    payload_version int         NOT NULL DEFAULT 1,
    CONSTRAINT events_stream_seq_uniq UNIQUE (stream_id, seq)
);

CREATE UNIQUE INDEX events_position_idx ON events (position);
CREATE INDEX events_stream_idx          ON events (stream_id, seq);
CREATE INDEX events_recorded_at_idx     ON events (recorded_at);
CREATE INDEX events_type_idx            ON events (event_type);

-- Append-only guard: block UPDATE/DELETE at the database so the log stays immutable
-- regardless of application bugs (§5.2). Corrections must be new compensating events.
CREATE OR REPLACE FUNCTION transaction_log.forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'transaction_log.events is append-only (attempted %)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER events_no_update_delete
    BEFORE UPDATE OR DELETE ON events
    FOR EACH ROW EXECUTE FUNCTION transaction_log.forbid_mutation();

-- ---------------------------------------------------------------------------
-- outbox — transactional outbox (build.md §5.5). Written in the SAME transaction
-- as the event append; a relay publishes unsent rows to Kafka (topic txlog.stream)
-- and stamps published_at. Keeping it separate leaves `events` strictly immutable.
-- ---------------------------------------------------------------------------
CREATE TABLE outbox (
    id           bigserial   PRIMARY KEY,
    event_id     uuid        NOT NULL REFERENCES events (event_id),
    topic        text        NOT NULL,
    message_key  text,                        -- Kafka partition key (= stream_id)
    payload      text        NOT NULL,        -- serialized EventEnvelope JSON (sent verbatim)
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,                 -- null until relayed
    attempts     int         NOT NULL DEFAULT 0
);

-- Fast scan of the unpublished backlog in insertion order.
CREATE INDEX outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;
