-- ===========================================================================
-- notification service — own schema (Dashboards & alerting epic). Holds the
-- alert ledger produced by the periodic threshold evaluator. References
-- warehouses by UUID only (no cross-schema FKs — master-data owns them).
-- ===========================================================================

-- One row per (warehouse, area, metric) breach lifecycle. The evaluator dedupes by `dedupe_key`
-- so a metric that stays over threshold across runs updates the SAME open row rather than spawning
-- duplicates; when the metric drops back under threshold the open row is CLEARED.
CREATE TABLE alert_event (
    alert_event_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id   uuid        NOT NULL,
    area           text        NOT NULL,                  -- e.g. SCAN | RECEIVING | ASRS | ALLOCATION | PUTAWAY | REPLENISHMENT
    metric         text        NOT NULL,                  -- e.g. scanNoReadPct, receiveErrorsDay, asrsUtilisationPct
    severity       text        NOT NULL,                  -- WARNING | CRITICAL
    value          numeric,                               -- observed metric value at (re)open
    threshold      numeric,                               -- threshold it breached
    state          text        NOT NULL DEFAULT 'OPEN',   -- OPEN | ACKED | CLEARED
    dedupe_key     text        NOT NULL,                  -- warehouse|area|metric
    opened_at      timestamptz NOT NULL DEFAULT now(),
    cleared_at     timestamptz,
    acked_at       timestamptz,
    acked_by       text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT alert_event_state_chk CHECK (state IN ('OPEN', 'ACKED', 'CLEARED')),
    CONSTRAINT alert_event_severity_chk CHECK (severity IN ('WARNING', 'CRITICAL'))
);

-- At most ONE active (OPEN or ACKED) row per dedupe_key — this is what makes re-breaching idempotent.
-- A partial unique index leaves CLEARED history rows free to accumulate.
CREATE UNIQUE INDEX alert_event_active_dedupe_uq
    ON alert_event (dedupe_key)
    WHERE state IN ('OPEN', 'ACKED');

CREATE INDEX alert_event_warehouse_state_idx ON alert_event (warehouse_id, state);
