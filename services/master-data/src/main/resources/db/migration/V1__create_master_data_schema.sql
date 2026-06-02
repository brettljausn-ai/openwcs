-- openWCS master data domain model (build.md §6).
-- Owned exclusively by the master-data service in the shared PostgreSQL instance
-- (build.md §5.1: only `master_data` + `transaction_log` live in the shared DB).
--
-- Multi-warehouse from the start (§16): the SKU carries only stable, global identity;
-- everything site-variable lives in a per-warehouse SkuProfile overlay validated
-- against an admin-defined AttributeSchema.

CREATE SCHEMA IF NOT EXISTS master_data;
SET search_path TO master_data;

-- gen_random_uuid() is built in from PostgreSQL 13+ (build.md §10: PostgreSQL 16).

-- ---------------------------------------------------------------------------
-- Warehouse / Site
-- ---------------------------------------------------------------------------
CREATE TABLE warehouse (
    warehouse_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    code         text        NOT NULL UNIQUE,
    name         text        NOT NULL,
    timezone     text        NOT NULL DEFAULT 'UTC',
    status       text        NOT NULL DEFAULT 'ACTIVE',
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------------
-- AttributeSchema — admin-defined, per warehouse + category; governs the JSONB
-- metadata blobs (SkuProfile.metadata, Location.allowed_sku_attrs, …) so flexible
-- JSON still has declared types/enums/required fields and indexed-field lists.
-- ---------------------------------------------------------------------------
CREATE TABLE attribute_schema (
    attribute_schema_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id        uuid        NOT NULL REFERENCES warehouse (warehouse_id),
    applies_to          text        NOT NULL,   -- SKU | LOCATION | HU | EQUIPMENT
    category            text        NOT NULL,
    json_schema         jsonb       NOT NULL,
    version             int         NOT NULL DEFAULT 1,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT attribute_schema_applies_to_chk
        CHECK (applies_to IN ('SKU', 'LOCATION', 'HU', 'EQUIPMENT')),
    UNIQUE (warehouse_id, applies_to, category, version)
);

-- ---------------------------------------------------------------------------
-- SKU — global core (stable, warehouse-independent identity). Tracking flags
-- declare WHAT must be captured at goods-in for this SKU (§6).
-- ---------------------------------------------------------------------------
CREATE TABLE sku (
    sku_id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    code              text        NOT NULL UNIQUE,
    description       text,
    status            text        NOT NULL DEFAULT 'ACTIVE',
    owner_client      text,                      -- owning client / 3PL tenant
    is_batch_tracked  boolean     NOT NULL DEFAULT false,
    is_serial_tracked boolean     NOT NULL DEFAULT false,
    is_date_tracked   boolean     NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint      NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------------
-- SkuProfile — per-warehouse overlay; ALL site-variable attributes + storage
-- "teach-in" strategy. One row per (sku, warehouse).
-- ---------------------------------------------------------------------------
CREATE TABLE sku_profile (
    sku_profile_id      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    sku_id              uuid        NOT NULL REFERENCES sku (sku_id) ON DELETE CASCADE,
    warehouse_id        uuid        NOT NULL REFERENCES warehouse (warehouse_id),
    metadata            jsonb       NOT NULL DEFAULT '{}'::jsonb,  -- brand/season/color/size/...
    storage_strategy    jsonb       NOT NULL DEFAULT '{}'::jsonb,  -- allowed/preferred storage, zones, min/max
    attribute_schema_id uuid        REFERENCES attribute_schema (attribute_schema_id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint      NOT NULL DEFAULT 0,
    UNIQUE (sku_id, warehouse_id)
);

-- ---------------------------------------------------------------------------
-- DangerousGoods — 0..1 per SKU, only for hazardous SKUs (drives DG segregation).
-- ---------------------------------------------------------------------------
CREATE TABLE dangerous_goods (
    dg_id                     uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    sku_id                    uuid          NOT NULL UNIQUE REFERENCES sku (sku_id) ON DELETE CASCADE,
    un_number                 text,
    hazard_class              text,
    packing_group             text,
    proper_shipping_name      text,
    adr_imdg_iata_codes       jsonb,        -- list of mode-specific codes
    flash_point               numeric(10, 2),
    net_explosive_qty         numeric(18, 4),
    storage_segregation_rules jsonb,
    handling_constraints      jsonb,
    created_at                timestamptz   NOT NULL DEFAULT now(),
    updated_at                timestamptz   NOT NULL DEFAULT now(),
    version                   bigint        NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------------
-- UnitOfMeasure ("bundle") — hierarchy with conversion factors; the base unit is
-- the smallest stockable unit. Stock math always normalizes to the base unit.
-- ---------------------------------------------------------------------------
CREATE TABLE unit_of_measure (
    uom_id        uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    sku_id        uuid        NOT NULL REFERENCES sku (sku_id) ON DELETE CASCADE,
    code          text        NOT NULL,                 -- EACH, INNER, CASE, PALLET …
    parent_uom_id uuid        REFERENCES unit_of_measure (uom_id),
    qty_in_parent numeric(18, 4),                       -- conversion factor into the parent UoM
    length_mm     numeric(12, 3),
    width_mm      numeric(12, 3),
    height_mm     numeric(12, 3),
    weight_g      numeric(12, 3),
    is_base_unit  boolean     NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint      NOT NULL DEFAULT 0,
    UNIQUE (sku_id, code)
);

-- At most one base unit per SKU.
CREATE UNIQUE INDEX uom_one_base_unit_per_sku
    ON unit_of_measure (sku_id) WHERE is_base_unit;

-- ---------------------------------------------------------------------------
-- BarcodeType — the type drives parsing/validation (e.g. GS1-128 AI parsing).
-- ---------------------------------------------------------------------------
CREATE TABLE barcode_type (
    barcode_type_id  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name             text        NOT NULL UNIQUE,       -- EAN13, GTIN14, CODE128, GS1-128, QR …
    symbology        text,
    length_rule      text,
    check_digit_rule text,
    gs1_ai_parsing   boolean     NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          bigint      NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------------
-- Barcode — identifies a SKU at a given packaging level (UoM).
-- ---------------------------------------------------------------------------
CREATE TABLE barcode (
    barcode_id      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    sku_id          uuid        NOT NULL REFERENCES sku (sku_id) ON DELETE CASCADE,
    uom_id          uuid        REFERENCES unit_of_measure (uom_id),
    value           text        NOT NULL,
    barcode_type_id uuid        REFERENCES barcode_type (barcode_type_id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0,
    UNIQUE (value, barcode_type_id)
);

-- ---------------------------------------------------------------------------
-- HandlingUnitType — TOTE, TRAY, CARTON, PALLET …
-- ---------------------------------------------------------------------------
CREATE TABLE handling_unit_type (
    hu_type_id      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name            text        NOT NULL UNIQUE,
    length_mm       numeric(12, 3),
    width_mm        numeric(12, 3),
    height_mm       numeric(12, 3),
    weight_limit_g  numeric(12, 3),
    nestable        boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------------
-- Equipment — CONVEYOR | ASRS | AMR | AUTOSTORE. Created before location because
-- locations may belong to a piece of equipment.
-- ---------------------------------------------------------------------------
CREATE TABLE equipment (
    equipment_id     uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id     uuid        NOT NULL REFERENCES warehouse (warehouse_id),
    family           text        NOT NULL,              -- CONVEYOR | ASRS | AMR | AUTOSTORE
    vendor           text,
    model            text,
    adapter_endpoint text,
    capabilities     jsonb,
    status           text        NOT NULL DEFAULT 'ACTIVE',
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT equipment_family_chk
        CHECK (family IN ('CONVEYOR', 'ASRS', 'AMR', 'AUTOSTORE'))
);

-- ---------------------------------------------------------------------------
-- Location / Topology — classified on two orthogonal axes (§6):
--   location_type = physical form / what it holds
--   purpose       = functional role in flows
-- ---------------------------------------------------------------------------
CREATE TABLE location (
    location_id        uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id       uuid        NOT NULL REFERENCES warehouse (warehouse_id),
    code               text        NOT NULL,
    location_type      text        NOT NULL,            -- BIN, PALLET, FREE_SPACE, SHELF, GRID_BIN, ASRS_SLOT, CONVEYOR_SEGMENT, ROBOT_PORT, STATION
    purpose            text        NOT NULL,            -- STORAGE, TRANSPORT, STAGING, PICK, PACK, INDUCT, RECEIVING, SHIPPING, QUARANTINE, RETURNS
    parent_id          uuid        REFERENCES location (location_id),  -- zone / aisle / grid
    coordinates        jsonb,
    equipment_id       uuid        REFERENCES equipment (equipment_id),
    status             text        NOT NULL DEFAULT 'ACTIVE',
    capacity           jsonb,                            -- qty / volume / weight limits
    allowed_hu_types   jsonb,                            -- list of hu_type names/ids accepted here
    allowed_sku_attrs  jsonb,                            -- attribute constraints (governed by an AttributeSchema)
    is_mixed_allowed   boolean     NOT NULL DEFAULT true,
    replenishment_class text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT location_type_chk CHECK (location_type IN
        ('BIN', 'PALLET', 'FREE_SPACE', 'SHELF', 'GRID_BIN', 'ASRS_SLOT',
         'CONVEYOR_SEGMENT', 'ROBOT_PORT', 'STATION')),
    CONSTRAINT location_purpose_chk CHECK (purpose IN
        ('STORAGE', 'TRANSPORT', 'STAGING', 'PICK', 'PACK', 'INDUCT',
         'RECEIVING', 'SHIPPING', 'QUARANTINE', 'RETURNS')),
    UNIQUE (warehouse_id, code)
);

-- ---------------------------------------------------------------------------
-- Indexes for common lookups.
-- ---------------------------------------------------------------------------
CREATE INDEX sku_profile_sku_idx        ON sku_profile (sku_id);
CREATE INDEX sku_profile_warehouse_idx  ON sku_profile (warehouse_id);
-- GIN on the JSONB overlay so frequent queries (brand/season/…) stay fast (§6 guardrail).
CREATE INDEX sku_profile_metadata_gin   ON sku_profile USING gin (metadata);
CREATE INDEX uom_sku_idx                ON unit_of_measure (sku_id);
CREATE INDEX barcode_sku_idx            ON barcode (sku_id);
CREATE INDEX barcode_value_idx          ON barcode (value);
CREATE INDEX location_warehouse_idx     ON location (warehouse_id);
CREATE INDEX location_parent_idx        ON location (parent_id);
CREATE INDEX location_purpose_idx       ON location (warehouse_id, purpose);
CREATE INDEX equipment_warehouse_idx    ON equipment (warehouse_id);
