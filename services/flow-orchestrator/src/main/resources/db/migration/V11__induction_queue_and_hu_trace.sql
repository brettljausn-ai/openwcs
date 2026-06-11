-- ADR-0007 Phase 3c-1: the inbound induction / presentation queue relocates from gtp to flow.
-- `induction_queue_entry` is the REQUESTED → IN_TRANSIT → QUEUED → DONE pipeline of totes being
-- delivered to a workplace (driven by RETRIEVE + CONVEY device-task callbacks); `hu_transport_trace`
-- is the append-only per-HU timeline (R4). Both live in the service-local `flow` schema.
SET search_path TO flow;

CREATE TABLE induction_queue_entry (
    induction_entry_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id       uuid        NOT NULL,
    workplace_id       uuid        NOT NULL,        -- destination workplace (today: a GTP station id)
    workplace_kind     text        NOT NULL DEFAULT 'GTP_STATION', -- R1: GTP_STATION | PUT_WALL | ...
    hu_id              uuid,
    hu_code            text,
    sku_id             uuid,
    sku_code           text,
    qty                numeric,
    location_id        uuid,                         -- source storage slot (for store-back by gtp)
    mode               text        NOT NULL,         -- PICKING | STOCK_COUNT | ...
    status             text        NOT NULL DEFAULT 'REQUESTED',
    -- arrival sequence: monotonic per workplace, assigned when the entry becomes QUEUED (R2/R4).
    -- NULL until QUEUED. Queue order for QUEUED entries is ORDER BY arrival_seq ASC.
    arrival_seq        bigint,
    requested_at       timestamptz NOT NULL DEFAULT now(),
    in_transit_at      timestamptz,                  -- when RETRIEVE completed
    queued_at          timestamptz,                  -- actual arrival time (CONVEY completed)
    done_at            timestamptz,
    -- links to the device tasks orchestrating this entry's journey (decision #5, lifecycle wiring):
    retrieve_task_id   uuid,                         -- the RETRIEVE/BIN_RETRIEVE device_task
    convey_task_id     uuid,                         -- the CONVEY device_task
    -- counting linkage (carried through so the station can drive an at-station blind count):
    count_task_id      uuid,
    count_line_id      uuid,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT induction_entry_status_chk
        CHECK (status IN ('REQUESTED','IN_TRANSIT','QUEUED','DONE'))
);

CREATE INDEX induction_entry_workplace_idx ON induction_queue_entry (workplace_id, status);
CREATE INDEX induction_entry_hu_idx        ON induction_queue_entry (warehouse_id, hu_id, status);
CREATE INDEX induction_entry_convey_idx    ON induction_queue_entry (convey_task_id);
CREATE INDEX induction_entry_retrieve_idx  ON induction_queue_entry (retrieve_task_id);

CREATE TABLE hu_transport_trace (
    trace_id        uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id    uuid        NOT NULL,
    hu_id           uuid        NOT NULL,
    hu_code         text,
    ts              timestamptz NOT NULL DEFAULT now(),
    point           text,                            -- function point, e.g. 'slot:A01','conveyor','station-2'
    event           text        NOT NULL,            -- REQUESTED|RETRIEVED|INDUCTED|ARRIVED|QUEUED|DONE|RECIRCULATE...
    decision        text,                            -- human-readable decision at the point
    from_point      text,
    to_point        text,
    workplace_id    uuid,
    correlation_id  uuid,                            -- == hu_id today (mirrors device_task grouping)
    task_id         uuid,                            -- the driving device_task, when applicable
    induction_entry_id uuid,                         -- the induction entry this row belongs to, when applicable
    created_at      timestamptz NOT NULL DEFAULT now()
    -- append-only: no updated_at / version; rows are never mutated.
);

CREATE INDEX hu_transport_trace_hu_idx  ON hu_transport_trace (warehouse_id, hu_id, ts);
CREATE INDEX hu_transport_trace_ts_idx  ON hu_transport_trace (ts);
