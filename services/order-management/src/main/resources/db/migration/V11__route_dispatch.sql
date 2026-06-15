-- route_dispatch — a persisted dispatch wave: one row per (warehouse, route, cut-off) that
-- outbound orders are assigned to. Replaces the purely-derived dispatch board with real status
-- + timestamps. Lifecycle: OPEN (orders assigned) -> LOADING (first order ready/picked) ->
-- DEPARTED (all assigned orders shipped). The row is lazily upserted when an order is assigned
-- to a route at create time, and advanced best-effort as its orders progress (ready / shipped).
SET search_path TO orders;

CREATE TABLE route_dispatch (
    dispatch_id      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id     uuid        NOT NULL,
    route_code       text        NOT NULL,
    -- The route cut-off instant the assigned orders share (their dispatch_by). The dispatch board
    -- groups by this exact cut-off, so the persisted wave is keyed on it too.
    cutoff           timestamptz NOT NULL,
    status           text        NOT NULL DEFAULT 'OPEN',
    orders_assigned  int         NOT NULL DEFAULT 0,
    orders_ready     int         NOT NULL DEFAULT 0,
    opened_at        timestamptz NOT NULL DEFAULT now(),
    first_loaded_at  timestamptz,
    departed_at      timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT route_dispatch_status_chk CHECK (status IN ('OPEN', 'LOADING', 'DEPARTED')),
    CONSTRAINT route_dispatch_uk UNIQUE (warehouse_id, route_code, cutoff)
);

CREATE INDEX route_dispatch_board_idx ON route_dispatch (warehouse_id, cutoff, route_code);
