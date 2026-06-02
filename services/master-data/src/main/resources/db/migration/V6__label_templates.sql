-- Dispatch-label templates designed in the admin UI: a sized canvas + an ordered list of
-- elements (text/address/barcode/image) stored as JSONB. Applied to a shipper at dispatch and
-- rendered to a print format (ZPL/PDF). Global reference data; code unique.
SET search_path TO master_data;

CREATE TABLE label_template (
    template_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    code        text        NOT NULL UNIQUE,
    name        text,
    width_mm    numeric(10, 3) NOT NULL,
    height_mm   numeric(10, 3) NOT NULL,
    dpi         int         NOT NULL DEFAULT 203,
    elements    jsonb       NOT NULL DEFAULT '[]'::jsonb,
    status      text        NOT NULL DEFAULT 'ACTIVE',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint      NOT NULL DEFAULT 0
);
