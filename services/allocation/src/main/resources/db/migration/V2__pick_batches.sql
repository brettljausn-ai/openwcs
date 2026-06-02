-- Batch (cluster) picking (ADR 0002 §6): group small orders into one pick tote, picked
-- as one combined pick list, then separated into each order's final shippers at packing.
SET search_path TO allocation;

CREATE TABLE pick_batch (
    batch_id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id         uuid        NOT NULL,
    pick_tote_shipper_id uuid,
    status               text        NOT NULL DEFAULT 'OPEN',
    members              jsonb       NOT NULL DEFAULT '[]'::jsonb,   -- [{orderRef, totePosition, finalShippers:[...]}]
    pick_lines           jsonb       NOT NULL DEFAULT '[]'::jsonb,   -- combined [{locationId, skuId, qty, reservationIds:[...]}]
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    version              bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pick_batch_status_chk CHECK (status IN ('OPEN', 'PICKED', 'SEPARATED', 'CLOSED'))
);

CREATE INDEX pick_batch_warehouse_idx ON pick_batch (warehouse_id, status);
