-- Outbound fulfilment master data (ADR 0002): per-warehouse pick-type config and the
-- shipper catalog used for cubing. SKU/UoM dimensions for cubing already live on
-- unit_of_measure (length/width/height/weight per packaging level).
SET search_path TO master_data;

-- ---------------------------------------------------------------------------
-- Shipper — a dispatch container (box, tote, bag, …) configurable per warehouse.
-- ---------------------------------------------------------------------------
CREATE TABLE shipper (
    shipper_id     uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id   uuid        NOT NULL REFERENCES warehouse (warehouse_id),
    code           text        NOT NULL,
    name           text,
    shipper_type   text        NOT NULL,              -- BOX | TOTE | BAG | CARTON | PALLET
    length_mm      numeric(12, 3),
    width_mm       numeric(12, 3),
    height_mm      numeric(12, 3),
    tare_weight_g  numeric(12, 3),
    max_fill_level numeric(5, 4) NOT NULL DEFAULT 1.0,  -- usable fraction of inner volume (0–1]
    max_weight_g   numeric(12, 3),                    -- max gross weight (incl. tare)
    status         text        NOT NULL DEFAULT 'ACTIVE',
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT shipper_type_chk CHECK (shipper_type IN ('BOX', 'TOTE', 'BAG', 'CARTON', 'PALLET')),
    CONSTRAINT shipper_fill_chk CHECK (max_fill_level > 0 AND max_fill_level <= 1),
    UNIQUE (warehouse_id, code)
);

CREATE INDEX shipper_warehouse_idx ON shipper (warehouse_id);

-- ---------------------------------------------------------------------------
-- WarehouseFulfillmentConfig — one per warehouse: allowed pick types + cubing mode.
-- ---------------------------------------------------------------------------
CREATE TABLE warehouse_fulfillment_config (
    config_id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id       uuid        NOT NULL UNIQUE REFERENCES warehouse (warehouse_id),
    allowed_pick_types jsonb       NOT NULL DEFAULT '["EACH"]'::jsonb,  -- CASE | SPLIT_CASE | EACH
    cubing_mode        text        NOT NULL DEFAULT 'APP',              -- APP | ONE_TO_ONE
    default_shipper_id uuid        REFERENCES shipper (shipper_id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT wfc_cubing_mode_chk CHECK (cubing_mode IN ('APP', 'ONE_TO_ONE'))
);
