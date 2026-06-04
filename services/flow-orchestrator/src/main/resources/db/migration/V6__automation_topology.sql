-- Automation topology PLACEMENT model (Phase 1). The 3D placement editor describes a warehouse as
-- levels (floors at a given elevation), pieces of master-data equipment placed on a level with a
-- position/size/rotation, directed connections between placed equipment (how handling units flow),
-- and function points on placed equipment (scan / divert / induct / discharge / …). Function points
-- may map to a conveyor routing node (node_code) so the placement model and the routing graph stay
-- in sync. Equipment is referenced by id from master data, by reference only (no FK). The whole
-- model is loaded/saved as one graph per warehouse, mirroring the conveyor topology editor.
SET search_path TO flow;

CREATE TABLE warehouse_level (
    level_id     uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id uuid        NOT NULL,
    number       int         NOT NULL,        -- level number within the warehouse
    name         text,
    elevation_m  numeric,                     -- floor elevation in metres
    status       text        NOT NULL DEFAULT 'ACTIVE',
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 0,
    UNIQUE (warehouse_id, number)
);
CREATE INDEX warehouse_level_warehouse_idx ON warehouse_level (warehouse_id);

CREATE TABLE placed_equipment (
    placed_id    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id uuid        NOT NULL,
    level_id     uuid,                        -- the level it sits on
    equipment_id uuid,                        -- master-data equipment, by reference (no FK)
    code         text,                        -- instance label/identifier
    pos_x_m      numeric,                     -- position in metres
    pos_y_m      numeric,
    pos_z_m      numeric,
    rotation_deg numeric,                     -- yaw
    tilt_deg     numeric,                     -- incline/decline (e.g. conveyors between levels)
    length_m     numeric,                     -- actual placed envelope (length set by scaling)
    width_m      numeric,
    height_m     numeric,
    status       text        NOT NULL DEFAULT 'ACTIVE',
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 0
);
CREATE INDEX placed_equipment_warehouse_idx ON placed_equipment (warehouse_id);

CREATE TABLE equipment_function_point (
    point_id      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id  uuid        NOT NULL,
    placed_id     uuid        NOT NULL,       -- the placed equipment this point is on
    function_type text,                       -- SCAN/LABEL_APPLICATOR/DIVERT_LEFT/DIVERT_RIGHT/DWS/QUERY_POINT/WRAPPER/INDUCT/DISCHARGE
    name          text,
    offset_m      numeric,                    -- distance along the equipment from its origin
    side          text,                       -- LEFT/RIGHT, or null
    node_code     text,                       -- conveyor node code this maps to for routing, or null
    status        text        NOT NULL DEFAULT 'ACTIVE',
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint      NOT NULL DEFAULT 0
);
CREATE INDEX equipment_function_point_warehouse_idx ON equipment_function_point (warehouse_id);
CREATE INDEX equipment_function_point_placed_idx ON equipment_function_point (placed_id);

CREATE TABLE equipment_connection (
    connection_id  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id   uuid        NOT NULL,
    from_placed_id uuid        NOT NULL,
    to_placed_id   uuid        NOT NULL,
    from_point_id  uuid,                       -- optional source function point
    to_point_id    uuid,                       -- optional target function point
    label          text,
    status         text        NOT NULL DEFAULT 'ACTIVE',
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint      NOT NULL DEFAULT 0
);
CREATE INDEX equipment_connection_warehouse_idx ON equipment_connection (warehouse_id);
CREATE INDEX equipment_connection_from_idx ON equipment_connection (warehouse_id, from_placed_id);
