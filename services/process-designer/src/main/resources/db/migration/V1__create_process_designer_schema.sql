-- ===========================================================================
-- process-designer service — own schema. Storage + versioning for configurable
-- handheld operator processes (the WYSIWYG designer + client-driven runtime).
-- The CLIENT walks the screen flow and evaluates branch conditions; the SERVER
-- owns definition storage/versioning, instance checkpointing, and task-step
-- execution (the curated task library calls existing audited services with the
-- operator's forwarded identity). References master-data / inventory entities
-- by UUID only (no cross-schema FKs — those services own their data).
-- See docs/process-designer-spec.md.
-- ===========================================================================

-- One immutable version of a process flow (steps + transitions + data-object schema),
-- stored as the JSON model document. Status DRAFT -> ACTIVE -> ARCHIVED. Exactly one
-- ACTIVE per process_key (partial unique index below). Versions auto-increment per key.
CREATE TABLE process_definition (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    process_key   text        NOT NULL,
    version       integer     NOT NULL,
    status        text        NOT NULL DEFAULT 'DRAFT',  -- DRAFT | ACTIVE | ARCHIVED
    title         text        NOT NULL,
    icon          text,
    json          jsonb       NOT NULL,                  -- the full process model document
    published_at  timestamptz,
    published_by  text,                                  -- X-Auth-User who published (audit)
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version_lock  bigint      NOT NULL DEFAULT 0,        -- JPA optimistic lock (distinct from model version)
    CONSTRAINT process_definition_status_chk CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT process_definition_key_version_uq UNIQUE (process_key, version)
);

-- Enforce exactly one ACTIVE definition per process key (partial unique index).
CREATE UNIQUE INDEX process_definition_one_active_per_key
    ON process_definition (process_key)
    WHERE status = 'ACTIVE';

CREATE INDEX process_definition_status_idx ON process_definition (status);

-- One run of a process by an operator. Pinned to the definition version it started on
-- (in-flight pinning: publishing a new active version never mutates running instances).
-- The data object + current step are checkpointed at each task step for device-swap resume.
CREATE TABLE process_instance (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    process_key   text        NOT NULL,
    def_version   integer     NOT NULL,
    status        text        NOT NULL DEFAULT 'RUNNING', -- RUNNING | DONE
    data          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    current_step  text,
    started_by    text,                                   -- X-Auth-User who started it
    warehouse_id  uuid,
    started_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version_lock  bigint      NOT NULL DEFAULT 0,
    CONSTRAINT process_instance_status_chk CHECK (status IN ('RUNNING', 'DONE'))
);

CREATE INDEX process_instance_key_idx ON process_instance (process_key);

-- Idempotency ledger for task-step checkpoints. A (instance, step) is processed at most
-- once; re-posting the same checkpoint returns the stored result without re-running the
-- task's side effects. Holds the data object as it was after the task merged its outputs.
CREATE TABLE process_step_result (
    instance_id   uuid        NOT NULL REFERENCES process_instance (id) ON DELETE CASCADE,
    step_id       text        NOT NULL,
    result_data   jsonb       NOT NULL,                   -- the merged data object after the task ran
    next_step     text,                                   -- the step the instance advanced to (null = done)
    done          boolean     NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (instance_id, step_id)
);

-- ---------------------------------------------------------------------------
-- Seed a sample ACTIVE process so the feature is demoable end-to-end: a Stock
-- Check that scans a location, captures a counted quantity, and posts the count
-- as a stock transaction via the curated `txlog.post` task (StockCounted event).
-- Linear flow: scanLocation -> enterQty -> postCount (task) -> done (ack).
-- ---------------------------------------------------------------------------
INSERT INTO process_definition (process_key, version, status, title, icon, json, published_at, published_by)
VALUES (
    'stock-check', 1, 'ACTIVE', 'Stock Check', 'inventory',
    $json$
    {
      "processKey": "stock-check",
      "version": 1,
      "status": "ACTIVE",
      "title": "Stock Check",
      "icon": "inventory",
      "dataSchema": [
        { "name": "location",        "type": "location" },
        { "name": "sku",             "type": "sku" },
        { "name": "qty",             "type": "number" },
        { "name": "skuBarcode",      "type": "string" },
        { "name": "LocationCode",    "type": "string" },
        { "name": "LocationBarcode", "type": "string" },
        { "name": "HUBarcode",       "type": "string" },
        { "name": "HUCode",          "type": "string" }
      ],
      "start": "scanLocation",
      "steps": {
        "scanLocation": {
          "type": "screen", "screen": "textInput",
          "config": {
            "header": "Scan location",
            "detail": "Scan the bin location to count.",
            "scanBinding": true,
            "writeTo": "location",
            "validation": { "required": true }
          },
          "next": "scanSku"
        },
        "scanSku": {
          "type": "screen", "screen": "textInput",
          "config": {
            "header": "Scan SKU at {{location}}",
            "detail": "Scan the SKU you are counting.",
            "scanBinding": true,
            "writeTo": "sku",
            "validation": { "required": true }
          },
          "next": "enterQty"
        },
        "enterQty": {
          "type": "screen", "screen": "numberInput",
          "config": {
            "header": "Count {{sku}}",
            "detail": "Enter the counted quantity at {{location}}.",
            "writeTo": "qty",
            "validation": { "required": true, "min": 0, "integerOnly": true }
          },
          "next": "postCount"
        },
        "postCount": {
          "type": "task", "task": "txlog.post",
          "input": {
            "streamId": "location",
            "eventType": "literal:StockCounted",
            "qty": "qty",
            "sku": "sku",
            "location": "location"
          },
          "output": { "txEventId": "eventId" },
          "next": "done"
        },
        "done": {
          "type": "screen", "screen": "acknowledge",
          "config": {
            "header": "Count recorded",
            "detail": "Counted {{qty}} of {{sku}} at {{location}}.",
            "confirmLabel": "Done"
          }
        }
      }
    }
    $json$::jsonb,
    now(), 'system'
);
