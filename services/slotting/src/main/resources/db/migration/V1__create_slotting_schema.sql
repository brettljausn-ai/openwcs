-- ===========================================================================
-- slotting service — own schema (ADR 0003). Holds slotting configuration
-- (teach-in), put-away decisions, replenishment tasks, and re-slot
-- recommendations. References master-data / inventory entities by UUID only
-- (no cross-schema FKs — those services own their data).
-- ===========================================================================

-- Per-SKU teach-in: which block a SKU is slotted into + its slotting policy knobs.
CREATE TABLE storage_profile (
    storage_profile_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id       uuid        NOT NULL,
    sku_id             uuid        NOT NULL,
    block_id           uuid        NOT NULL,
    velocity_class     text        NOT NULL DEFAULT 'B',     -- A | B | C (teach-in)
    consolidate        boolean     NOT NULL DEFAULT true,    -- prefer same-SKU lanes
    min_aisles         integer     NOT NULL DEFAULT 1,       -- redundancy floor
    max_aisle_pct      numeric     NOT NULL DEFAULT 1.0,     -- redundancy cap (share of SKU per aisle)
    status             text        NOT NULL DEFAULT 'ACTIVE',
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT storage_profile_velocity_chk CHECK (velocity_class IN ('A', 'B', 'C')),
    UNIQUE (warehouse_id, sku_id, block_id)
);

-- Fixed manual/forward pick face: one SKU+UoM per location, with min/max.
CREATE TABLE pick_slot (
    pick_slot_id   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id   uuid        NOT NULL,
    location_id    uuid        NOT NULL,
    sku_id         uuid        NOT NULL,
    uom_id         uuid        NOT NULL,
    min_qty        numeric     NOT NULL DEFAULT 0,
    max_qty        numeric     NOT NULL,
    direct_to_pick boolean     NOT NULL DEFAULT false,   -- allow inbound straight to this face
    status         text        NOT NULL DEFAULT 'ACTIVE',
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pick_slot_minmax_chk CHECK (max_qty >= min_qty),
    UNIQUE (location_id, sku_id, uom_id)
);

-- Scoring weights + constraints per storage block (put-away tuning).
CREATE TABLE block_policy (
    block_policy_id       uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    block_id              uuid        NOT NULL,
    warehouse_id          uuid        NOT NULL,
    w_velocity            numeric     NOT NULL DEFAULT 1.0,
    w_consolidation       numeric     NOT NULL DEFAULT 1.0,
    w_redundancy          numeric     NOT NULL DEFAULT 1.0,
    w_balance             numeric     NOT NULL DEFAULT 1.0,
    default_max_aisle_pct numeric     NOT NULL DEFAULT 0.5,
    min_aisles_a          integer     NOT NULL DEFAULT 2,
    min_aisles_b          integer     NOT NULL DEFAULT 1,
    min_aisles_c          integer     NOT NULL DEFAULT 1,
    reslot_enabled        boolean     NOT NULL DEFAULT false,
    reslot_shift_pct      numeric     NOT NULL DEFAULT 0.2,
    offpeak_cron          text,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    version               bigint      NOT NULL DEFAULT 0,
    UNIQUE (block_id)
);

-- Audit of each put-away decision the engine makes.
CREATE TABLE putaway_assignment (
    putaway_assignment_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id          uuid        NOT NULL,
    hu_id                 uuid,
    sku_id                uuid        NOT NULL,
    block_id              uuid,
    chosen_location_id    uuid,
    mode                  text        NOT NULL DEFAULT 'RESERVE',  -- RESERVE | DIRECT_TO_PICK
    score                 numeric,
    factors               jsonb,
    status                text        NOT NULL DEFAULT 'PLANNED',
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT putaway_mode_chk CHECK (mode IN ('RESERVE', 'DIRECT_TO_PICK'))
);

-- Generated replenishment moves (reserve/block -> pick face).
CREATE TABLE replenishment_task (
    replenishment_task_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id          uuid        NOT NULL,
    sku_id                uuid        NOT NULL,
    uom_id                uuid        NOT NULL,
    from_location_id      uuid,
    to_location_id        uuid        NOT NULL,
    qty                   numeric     NOT NULL,
    priority              text        NOT NULL DEFAULT 'SCHEDULED',  -- EMERGENCY | SCHEDULED | OPPORTUNISTIC
    trigger_type          text        NOT NULL DEFAULT 'BELOW_MIN',  -- BELOW_MIN | TOP_OFF
    status                text        NOT NULL DEFAULT 'PLANNED',    -- PLANNED | DISPATCHED | DONE | CANCELLED
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT replen_priority_chk CHECK (priority IN ('EMERGENCY', 'SCHEDULED', 'OPPORTUNISTIC')),
    CONSTRAINT replen_trigger_chk  CHECK (trigger_type IN ('BELOW_MIN', 'TOP_OFF'))
);

-- Off-peak re-slot recommendations (reposition stored HUs as velocity drifts).
CREATE TABLE reslot_recommendation (
    reslot_recommendation_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id             uuid        NOT NULL,
    hu_id                    uuid,
    sku_id                   uuid        NOT NULL,
    from_location_id         uuid        NOT NULL,
    to_location_id           uuid        NOT NULL,
    reason                   text,
    score_gain               numeric,
    status                   text        NOT NULL DEFAULT 'RECOMMENDED',  -- RECOMMENDED | DISPATCHED | DONE | DISMISSED
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    version                  bigint      NOT NULL DEFAULT 0
);

CREATE INDEX storage_profile_sku_idx        ON storage_profile (warehouse_id, sku_id);
CREATE INDEX storage_profile_block_idx      ON storage_profile (block_id);
CREATE INDEX pick_slot_sku_idx              ON pick_slot (warehouse_id, sku_id);
CREATE INDEX pick_slot_location_idx         ON pick_slot (location_id);
CREATE INDEX block_policy_warehouse_idx     ON block_policy (warehouse_id);
CREATE INDEX putaway_assignment_sku_idx     ON putaway_assignment (warehouse_id, sku_id);
CREATE INDEX replenishment_task_status_idx  ON replenishment_task (warehouse_id, status);
CREATE INDEX reslot_recommendation_status_idx ON reslot_recommendation (warehouse_id, status);
