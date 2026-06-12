-- Reporting daily counters (Reporting screens). One row per (warehouse, scan point | edge, day),
-- bumped by an atomic SQL upsert on every scan decision; history accumulates from deployment day
-- (the UI reads the history and forecasts there). scan_stat answers "scans / no-reads / unknowns
-- per scan point per day"; edge_traffic answers the conveyor traffic heatmap.
SET search_path TO flow;

CREATE TABLE scan_stat (
    warehouse_id uuid   NOT NULL,
    node_code    text   NOT NULL,
    day          date   NOT NULL,
    scans        bigint NOT NULL DEFAULT 0,   -- every scan answered at the node
    no_reads     bigint NOT NULL DEFAULT 0,   -- scanner read errors (blank / NOREAD barcode)
    unknowns     bigint NOT NULL DEFAULT 0,   -- barcode read fine but no active route plan
    PRIMARY KEY (warehouse_id, node_code, day)
);

CREATE TABLE edge_traffic (
    warehouse_id uuid   NOT NULL,
    from_node    text   NOT NULL,
    to_node      text   NOT NULL,
    day          date   NOT NULL,
    count        bigint NOT NULL DEFAULT 0,   -- ROUTE answers sending an HU over this edge
    PRIMARY KEY (warehouse_id, from_node, to_node, day)
);
