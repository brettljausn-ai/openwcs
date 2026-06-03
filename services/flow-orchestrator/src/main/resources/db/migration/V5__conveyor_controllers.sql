-- Conveyor controllers (PLCs). Reality: one PLC (one TCP/IP endpoint) often hosts MANY conveyor
-- nodes. A controller is identified per-warehouse by its code and reached at ip_address:port. A
-- node may reference its controller by code plus a node-local address, instead of carrying a full
-- hardware address of its own. Backward compatible: conveyor_node.hardware_address stays; the new
-- controller_code / node_address columns are additive and nullable so existing rows still validate.
SET search_path TO flow;

CREATE TABLE conveyor_controller (
    controller_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id  uuid        NOT NULL,
    code          text        NOT NULL,        -- the controller id referenced by nodes (per warehouse)
    name          text,
    ip_address    text        NOT NULL,        -- TCP/IP address of the PLC
    port          int,                         -- TCP port of the PLC (nullable)
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint      NOT NULL DEFAULT 0,
    UNIQUE (warehouse_id, code)
);

-- A node can be hosted by a controller (by code) at a node-local address (e.g. a slot/index on
-- the PLC). Both nullable: nodes may still carry their own hardware_address for backward compat.
ALTER TABLE conveyor_node ADD COLUMN controller_code text;
ALTER TABLE conveyor_node ADD COLUMN node_address    text;

-- Observations may now carry the source port alongside the source IP so discovery can group
-- observed nodes by ip:port and propose a controller per endpoint.
ALTER TABLE topology_observation ADD COLUMN source_port int;
