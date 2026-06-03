-- Loop capacity for conveyor routing. A looping conveyor section is a named loop with a max
-- number of handling units; nodes that are part of the loop carry its code. When an HU would
-- enter a loop that is already at capacity, the WCS either HOLDs it (wait upstream) or diverts
-- it to the loop's OVERFLOW target. A route's current_loop tracks where the HU was last seen,
-- so occupancy = count of active routes whose current_loop is that loop.
SET search_path TO flow;

CREATE TABLE conveyor_loop (
    loop_id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id         uuid        NOT NULL,
    code                 text        NOT NULL,
    max_hus              int         NOT NULL,
    when_full            text        NOT NULL DEFAULT 'HOLD',   -- HOLD | OVERFLOW
    overflow_target_code text,                                  -- target node code when OVERFLOW
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    version              bigint      NOT NULL DEFAULT 0,
    UNIQUE (warehouse_id, code),
    CONSTRAINT conveyor_loop_whenfull_chk CHECK (when_full IN ('HOLD', 'OVERFLOW'))
);

ALTER TABLE conveyor_node ADD COLUMN loop_code text;   -- which loop this node belongs to (nullable)
ALTER TABLE hu_route ADD COLUMN current_loop text;     -- loop of the node where the HU was last scanned
CREATE INDEX hu_route_loop_idx ON hu_route (warehouse_id, current_loop, status);
