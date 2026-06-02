-- openWCS flow-orchestrator store (build.md §4.5). Service-local `flow` schema. Tracks the
-- device tasks the orchestrator dispatches to equipment adapters over the uniform device
-- contract (build.md §8), and their results.

CREATE SCHEMA IF NOT EXISTS flow;
SET search_path TO flow;

CREATE TABLE device_task (
    task_id        uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id   uuid        NOT NULL,
    family         text        NOT NULL,        -- CONVEYOR | ASRS | AMR | AUTOSTORE
    equipment_id   uuid,                         -- specific equipment (optional)
    command        text        NOT NULL,        -- CONVEY | STORE | RETRIEVE | MOVE …
    payload        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    correlation_id uuid,                         -- owning process instance / order (build.md §5.5)
    status         text        NOT NULL DEFAULT 'REQUESTED',  -- REQUESTED | DISPATCHED | COMPLETED | FAILED
    detail         text,
    result         jsonb,
    actor          text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT device_task_status_chk CHECK (status IN ('REQUESTED', 'DISPATCHED', 'COMPLETED', 'FAILED'))
);

CREATE INDEX device_task_correlation_idx ON device_task (correlation_id);
CREATE INDEX device_task_status_idx      ON device_task (warehouse_id, status);
