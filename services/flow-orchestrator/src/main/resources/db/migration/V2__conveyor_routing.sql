-- Vendor-neutral conveyor routing (build.md §8). The topology is a directed graph: nodes are
-- scan/decision points (each with its own hardware address + a layout position for the admin
-- schematic editor), edges are conveyor segments labelled with the exit/decision the hardware
-- takes to traverse them. A handling unit carries an ordered route plan of target nodes; on a
-- scan the WCS computes the next hop toward the current target via shortest path.
SET search_path TO flow;

CREATE TABLE conveyor_node (
    node_id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id     uuid        NOT NULL,
    code             text        NOT NULL,        -- the node id the hardware sends on a scan
    name             text,
    -- how the hardware/equipment is reached/identified (PLC/OPC-UA address, IP:port, …)
    hardware_address text,
    pos_x            double precision,            -- layout coordinates for the schematic editor
    pos_y            double precision,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          bigint      NOT NULL DEFAULT 0,
    UNIQUE (warehouse_id, code)
);

CREATE TABLE conveyor_edge (
    edge_id      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id uuid        NOT NULL,
    from_node_id uuid        NOT NULL REFERENCES conveyor_node (node_id) ON DELETE CASCADE,
    to_node_id   uuid        NOT NULL REFERENCES conveyor_node (node_id) ON DELETE CASCADE,
    exit_code    text        NOT NULL,            -- the decision the hardware applies to take this edge
    cost         int         NOT NULL DEFAULT 1,  -- routing cost (shortest path)
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 0
);
CREATE INDEX conveyor_edge_from_idx ON conveyor_edge (warehouse_id, from_node_id);

-- One active route plan per handling unit (barcode): the ordered target node codes it must
-- visit, and how far it has progressed.
CREATE TABLE hu_route (
    route_id      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id  uuid        NOT NULL,
    barcode       text        NOT NULL,
    targets       jsonb       NOT NULL DEFAULT '[]'::jsonb,   -- ordered list of node codes
    current_index int         NOT NULL DEFAULT 0,
    status        text        NOT NULL DEFAULT 'ACTIVE',      -- ACTIVE | COMPLETED | EXCEPTION
    detail        text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint      NOT NULL DEFAULT 0,
    CONSTRAINT hu_route_status_chk CHECK (status IN ('ACTIVE', 'COMPLETED', 'EXCEPTION'))
);
CREATE INDEX hu_route_lookup_idx ON hu_route (warehouse_id, barcode, status);
