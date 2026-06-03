-- ===========================================================================
-- gtp service — operating modes (ADR 0006, "Operating modes" section).
--
-- The station's existing `mode` (ORDER_LOCATION | PUT_WALL) is the destination
-- *topology* and is unchanged. This migration adds an orthogonal *operating
-- mode* = what the operator does when an HU is presented. A station supports a
-- set of operating modes; each work cycle runs exactly one.
--
--   PICKING      — the existing present-stock -> put-to-light flow (unchanged).
--   DECANTING    — move stock from a source HU into a target HU's compartments;
--                  the filled target is then ready for slotting put-away (seam).
--   STOCK_COUNT  — cycle counting: record counted qty per SKU, compute variance
--                  vs expected; emit a stock-count/adjustment intent (seam).
--   QC           — record an inspection verdict per HU/SKU: PASS | FAIL | HOLD.
--   MAINTENANCE  — request HUs/empty carriers for mechanical checks; record a
--                  condition outcome: OK | DEFECTIVE | REPAIR.
--
-- The cycle generalises: a work cycle has an `operating_mode` and a set of
-- mode-appropriate task lines (see `task_line`). PICKING keeps using
-- `put_instruction` unchanged; the other modes use `task_line`.
-- ===========================================================================

-- Supported operating modes for the station: a comma-separated set (text, like
-- the existing `mode` column). At least PICKING by default so existing stations
-- keep working unchanged.
ALTER TABLE gtp_station
    ADD COLUMN supported_modes text NOT NULL DEFAULT 'PICKING';

-- The operating mode a work cycle is running.
ALTER TABLE work_cycle
    ADD COLUMN operating_mode text NOT NULL DEFAULT 'PICKING';

-- DECANTING presents an empty target HU alongside the source (stock) HU; the
-- decant-moves fill the target's compartments. Null for the other modes.
ALTER TABLE work_cycle
    ADD COLUMN target_hu_id uuid;

ALTER TABLE work_cycle
    ADD CONSTRAINT work_cycle_op_mode_chk
        CHECK (operating_mode IN ('PICKING', 'DECANTING', 'STOCK_COUNT', 'QC', 'MAINTENANCE'));

-- PICKING cycles always present a stock HU of one SKU with a qty; the other
-- operating modes may not (e.g. MAINTENANCE requests an empty carrier; QC /
-- STOCK_COUNT may span several SKUs recorded on the task lines). Relax the
-- NOT NULL / range guards that were PICKING-specific so a non-PICKING cycle can
-- exist without a single presented SKU+qty. PICKING semantics are enforced in
-- the service exactly as before.
ALTER TABLE work_cycle ALTER COLUMN stock_hu_id   DROP NOT NULL;
ALTER TABLE work_cycle ALTER COLUMN sku_id        DROP NOT NULL;
ALTER TABLE work_cycle ALTER COLUMN presented_qty DROP NOT NULL;
ALTER TABLE work_cycle ALTER COLUMN remaining_qty DROP NOT NULL;
ALTER TABLE work_cycle DROP CONSTRAINT work_cycle_qty_chk;
ALTER TABLE work_cycle
    ADD CONSTRAINT work_cycle_qty_chk
        CHECK (remaining_qty IS NULL OR presented_qty IS NULL
               OR (remaining_qty >= 0 AND remaining_qty <= presented_qty));

-- A generalised mode-appropriate task line for a work cycle: the put-list shape
-- generalised to decant-moves (DECANTING), count entries (STOCK_COUNT), verdicts
-- (QC) and checks (MAINTENANCE). Each line carries an outcome/confirmation. The
-- PICKING flow keeps using `put_instruction`; this table covers the other modes.
--   line_type    = DECANT_MOVE | COUNT_ENTRY | QC_VERDICT | MAINTENANCE_CHECK
--   hu_id        = the HU this line concerns (target HU for DECANT_MOVE, the
--                  inspected/counted/checked HU otherwise)
--   sku_id       = SKU concerned (null for an HU-level MAINTENANCE check)
--   compartment  = target compartment for DECANT_MOVE (null otherwise)
--   expected_qty = expected/system qty (STOCK_COUNT); the qty to move (DECANT)
--   actual_qty   = counted qty (STOCK_COUNT); moved qty (DECANT, on confirm)
--   variance     = actual_qty - expected_qty (STOCK_COUNT), set on confirm
--   verdict      = PASS|FAIL|HOLD (QC) or OK|DEFECTIVE|REPAIR (MAINTENANCE)
--   status       = OPEN | CONFIRMED | CANCELLED
CREATE TABLE task_line (
    task_line_id  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    work_cycle_id uuid        NOT NULL REFERENCES work_cycle (work_cycle_id) ON DELETE CASCADE,
    line_type     text        NOT NULL,
    hu_id         uuid,
    sku_id        uuid,
    compartment   text,
    expected_qty  numeric,
    actual_qty    numeric,
    variance      numeric,
    verdict       text,
    put_light_id  text,
    status        text        NOT NULL DEFAULT 'OPEN',
    details       jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint      NOT NULL DEFAULT 0,
    CONSTRAINT task_line_type_chk CHECK (
        line_type IN ('DECANT_MOVE', 'COUNT_ENTRY', 'QC_VERDICT', 'MAINTENANCE_CHECK')),
    CONSTRAINT task_line_status_chk CHECK (status IN ('OPEN', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT task_line_verdict_chk CHECK (
        verdict IS NULL OR verdict IN ('PASS', 'FAIL', 'HOLD', 'OK', 'DEFECTIVE', 'REPAIR'))
);

CREATE INDEX task_line_cycle_idx ON task_line (work_cycle_id);

CREATE INDEX work_cycle_op_mode_idx ON work_cycle (gtp_station_id, operating_mode, status);
