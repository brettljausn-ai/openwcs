-- openWCS allocation store (ADR 0002). Service-local `allocation` schema. Records the
-- allocation + cube plan per order: which pick locations/reservations fulfil each line
-- (with the pick-type UoM breakdown), and which shippers the order is cubed into.

CREATE SCHEMA IF NOT EXISTS allocation;
SET search_path TO allocation;

-- ---------------------------------------------------------------------------
-- order_allocation — one per order. `shippers` is the cube plan (list of shipper
-- assignments). status FULFILLABLE means every line was fully reserved.
-- ---------------------------------------------------------------------------
CREATE TABLE order_allocation (
    allocation_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    order_ref     text        NOT NULL UNIQUE,
    warehouse_id  uuid        NOT NULL,
    status        text        NOT NULL,                 -- FULFILLABLE | NOT_FULFILLABLE
    cubing_mode   text        NOT NULL,                 -- APP | ONE_TO_ONE
    shippers      jsonb       NOT NULL DEFAULT '[]'::jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint      NOT NULL DEFAULT 0,
    CONSTRAINT order_allocation_status_chk CHECK (status IN ('FULFILLABLE', 'NOT_FULFILLABLE')),
    CONSTRAINT order_allocation_cubing_chk CHECK (cubing_mode IN ('APP', 'ONE_TO_ONE'))
);

-- ---------------------------------------------------------------------------
-- allocation_line — one per order line. `picks` lists the per-location reservations
-- (with the UoM breakdown) that fulfil the line.
-- ---------------------------------------------------------------------------
CREATE TABLE allocation_line (
    line_alloc_id uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    allocation_id uuid          NOT NULL REFERENCES order_allocation (allocation_id) ON DELETE CASCADE,
    line_no       int           NOT NULL,
    sku_id        uuid          NOT NULL,
    requested_qty numeric(18, 4) NOT NULL,
    allocated_qty numeric(18, 4) NOT NULL DEFAULT 0,
    status        text          NOT NULL,               -- ALLOCATED | SHORT
    picks         jsonb         NOT NULL DEFAULT '[]'::jsonb,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    version       bigint        NOT NULL DEFAULT 0,
    CONSTRAINT allocation_line_status_chk CHECK (status IN ('ALLOCATED', 'SHORT')),
    UNIQUE (allocation_id, line_no)
);

CREATE INDEX allocation_line_alloc_idx ON allocation_line (allocation_id);
