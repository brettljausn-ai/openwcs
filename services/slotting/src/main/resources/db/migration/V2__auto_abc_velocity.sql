-- ===========================================================================
-- slotting service — self-taught, recency-weighted ABC velocity.
-- The slotting engine learns each SKU's velocity class (A | B | C) from actual
-- pick/outbound movement instead of a hand-set value. Movements arrive on the
-- streamed transaction log (topic `txlog.stream`, build.md §9) and are folded
-- into a per-(warehouse, sku) recency-weighted EWMA score; an off-peak job ranks
-- SKUs by that decayed score and writes the class back onto storage_profile.
--
-- Additive only (migrations are additive): a new sku_velocity table + idempotency
-- inbox/cursor for this service's own consumer, plus a manual_override flag on
-- storage_profile and the EWMA/share knobs on block_policy.
-- ===========================================================================
SET search_path TO slotting;

-- Per-(warehouse, sku) recency-weighted pick-frequency score. The score is an
-- exponentially weighted moving average (EWMA): each recompute first decays the
-- stored score by exp(-Δt / tau) so recent activity dominates, then folds in the
-- picks counted since the last update. `class` is the A/B/C label last assigned by
-- the classifier (purely derived; storage_profile.velocity_class is authoritative
-- for the put-away engine).
CREATE TABLE sku_velocity (
    sku_velocity_id  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id     uuid        NOT NULL,
    sku_id           uuid        NOT NULL,
    score            numeric     NOT NULL DEFAULT 0,        -- decayed EWMA pick frequency
    pending_picks    numeric     NOT NULL DEFAULT 0,        -- picks counted since last decay/fold
    velocity_class   text,                                  -- A | B | C last classified (derived)
    decayed_at       timestamptz,                           -- when `score` was last decayed
    last_pick_at     timestamptz,                           -- most recent pick observed
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT sku_velocity_class_chk CHECK (velocity_class IS NULL OR velocity_class IN ('A', 'B', 'C')),
    UNIQUE (warehouse_id, sku_id)
);

CREATE INDEX sku_velocity_rank_idx ON sku_velocity (warehouse_id, score DESC);

-- Idempotency inbox for the velocity consumer (mirrors inventory.processed_event,
-- build.md §5.5). A redelivery/replay of the same transaction-log event_id is a no-op.
CREATE TABLE velocity_processed_event (
    event_id   uuid        PRIMARY KEY,
    event_type text        NOT NULL,
    stream_id  text,
    seq        bigint,
    applied_at timestamptz NOT NULL DEFAULT now()
);

-- Consumption cursor for the velocity projection (mirrors inventory.projection_offset).
CREATE TABLE velocity_offset (
    projection    text        PRIMARY KEY,
    last_event_id uuid,
    last_seq      bigint,
    updated_at    timestamptz NOT NULL DEFAULT now()
);

INSERT INTO velocity_offset (projection) VALUES ('sku-velocity')
ON CONFLICT (projection) DO NOTHING;

-- Manual override: when set, the auto-classifier leaves storage_profile.velocity_class
-- alone (an operator pinned the class). Default false so learning applies everywhere.
ALTER TABLE storage_profile
    ADD COLUMN manual_override boolean NOT NULL DEFAULT false;

-- Recency-weighted ABC knobs on the per-block policy (kept with the other slotting
-- tuning). half_life_days drives the EWMA decay; the A/B shares set the rank cutoffs
-- (A = top abc_a_share, B = next abc_b_share, C = the rest).
ALTER TABLE block_policy
    ADD COLUMN velocity_half_life_days numeric NOT NULL DEFAULT 14,
    ADD COLUMN abc_a_share             numeric NOT NULL DEFAULT 0.2,
    ADD COLUMN abc_b_share             numeric NOT NULL DEFAULT 0.3,
    ADD CONSTRAINT block_policy_abc_share_chk
        CHECK (abc_a_share >= 0 AND abc_b_share >= 0 AND abc_a_share + abc_b_share <= 1);
