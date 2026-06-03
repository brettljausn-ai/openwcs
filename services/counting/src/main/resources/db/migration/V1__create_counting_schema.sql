-- ===========================================================================
-- counting service — own schema. Holds cycle / stock counting: count schedules
-- (ABC cadence), count tasks over a scope (location / SKU / zone / block), and
-- per-line capture (expected snapshot, counted, variance) + reconciliation.
-- References master-data / inventory entities by UUID only (no cross-schema FKs
-- — those services own their data). Adjustments are posted to inventory via the
-- transaction log (StockAdjusted); a GTP STOCK_COUNT station / the cycle-count
-- BPMN can execute a task (referenced by id only — seams, not wired here).
-- ===========================================================================

-- ABC-cadence config: how often a scope (a zone/block or a velocity class) is counted.
CREATE TABLE count_schedule (
    count_schedule_id  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id       uuid        NOT NULL,
    name               text        NOT NULL,
    scope_type         text        NOT NULL,                  -- LOCATION | SKU | ZONE | BLOCK | ABC_CLASS
    scope_ref          uuid,                                  -- the location/sku/zone/block id (null for ABC_CLASS)
    abc_class          text,                                  -- A | B | C when scope_type = ABC_CLASS
    count_type         text        NOT NULL DEFAULT 'BLIND',  -- BLIND | VARIANCE (default for emitted tasks)
    cadence_days       integer     NOT NULL,                  -- count this scope every N days
    tolerance          numeric     NOT NULL DEFAULT 0,        -- auto-approve variance window (base UoM)
    last_run_at        timestamptz,                           -- last sweep that emitted a task for this schedule
    next_due_at        timestamptz NOT NULL DEFAULT now(),    -- when the next task is due
    status             text        NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | PAUSED
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT count_schedule_scope_chk CHECK (scope_type IN ('LOCATION', 'SKU', 'ZONE', 'BLOCK', 'ABC_CLASS')),
    CONSTRAINT count_schedule_type_chk  CHECK (count_type IN ('BLIND', 'VARIANCE')),
    CONSTRAINT count_schedule_abc_chk   CHECK (abc_class IS NULL OR abc_class IN ('A', 'B', 'C')),
    CONSTRAINT count_schedule_status_chk CHECK (status IN ('ACTIVE', 'PAUSED')),
    CONSTRAINT count_schedule_cadence_chk CHECK (cadence_days > 0)
);

-- A scheduled or ad-hoc count over a scope.
CREATE TABLE count_task (
    count_task_id      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id       uuid        NOT NULL,
    scope_type         text        NOT NULL,                  -- LOCATION | SKU | ZONE | BLOCK
    scope_ref          uuid,                                  -- the counted location/sku/zone/block id
    count_type         text        NOT NULL DEFAULT 'BLIND',  -- BLIND | VARIANCE
    origin             text        NOT NULL DEFAULT 'AD_HOC',  -- AD_HOC | SCHEDULED | RECOUNT
    schedule_id        uuid,                                  -- the count_schedule that emitted it (if SCHEDULED)
    parent_task_id     uuid,                                  -- the original task (if origin = RECOUNT)
    tolerance          numeric     NOT NULL DEFAULT 0,        -- auto-approve variance window (base UoM)
    -- A task can be executed at a GTP station in STOCK_COUNT mode (referenced by id only — seam).
    gtp_station_id     uuid,
    -- The cycle-count BPMN process instance orchestrating this task, if any (referenced by id — seam).
    process_instance_id text,
    status             text        NOT NULL DEFAULT 'OPEN',   -- OPEN | COUNTED | RECONCILED | RECOUNT
    assigned_to        text,                                  -- operator who claimed the task
    counted_by         text,                                  -- operator who submitted the count
    counted_at         timestamptz,
    reconciled_by      text,                                  -- actor who reconciled
    reconciled_at      timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT count_task_scope_chk  CHECK (scope_type IN ('LOCATION', 'SKU', 'ZONE', 'BLOCK')),
    CONSTRAINT count_task_type_chk   CHECK (count_type IN ('BLIND', 'VARIANCE')),
    CONSTRAINT count_task_origin_chk CHECK (origin IN ('AD_HOC', 'SCHEDULED', 'RECOUNT')),
    CONSTRAINT count_task_status_chk CHECK (status IN ('OPEN', 'COUNTED', 'RECONCILED', 'RECOUNT'))
);

-- Per (location, SKU[, batch]) line within a count task.
CREATE TABLE count_line (
    count_line_id      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    count_task_id      uuid        NOT NULL REFERENCES count_task (count_task_id) ON DELETE CASCADE,
    warehouse_id       uuid        NOT NULL,
    location_id        uuid        NOT NULL,
    sku_id             uuid        NOT NULL,
    batch_id           uuid,
    uom_code           text,                                  -- base-UoM label carried onto the adjustment
    expected_qty       numeric     NOT NULL DEFAULT 0,        -- snapshot from inventory at task generation
    counted_qty        numeric,                               -- entered by the operator
    variance           numeric,                               -- counted - expected (computed on submit)
    status             text        NOT NULL DEFAULT 'PENDING', -- PENDING | COUNTED | APPROVED | RECOUNT | ADJUSTED
    -- The adjustment posted on approval (the StockAdjusted txlog event id — seam to inventory).
    adjustment_event_id uuid,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT count_line_status_chk CHECK (status IN ('PENDING', 'COUNTED', 'APPROVED', 'RECOUNT', 'ADJUSTED')),
    UNIQUE (count_task_id, location_id, sku_id, batch_id)
);

CREATE INDEX count_schedule_due_idx    ON count_schedule (warehouse_id, status, next_due_at);
CREATE INDEX count_task_status_idx     ON count_task (warehouse_id, status);
CREATE INDEX count_task_schedule_idx   ON count_task (schedule_id);
CREATE INDEX count_line_task_idx       ON count_line (count_task_id);
