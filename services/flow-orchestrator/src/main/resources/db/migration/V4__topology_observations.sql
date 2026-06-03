-- Topology learning/discovery. A sniffer watching the conveyor network (defined IPs / a mirror
-- port) decodes scan telegrams and posts them here as observations {node, barcode, sourceIp}.
-- The WCS infers candidate nodes, segments (from consecutive scans of the same barcode) and
-- likely targets (terminal nodes) that an admin can confirm into the topology.
SET search_path TO flow;

CREATE TABLE topology_observation (
    obs_id       uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id uuid        NOT NULL,
    node         text        NOT NULL,
    barcode      text        NOT NULL,
    source_ip    text,
    observed_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX topology_observation_seq_idx ON topology_observation (warehouse_id, barcode, observed_at);
