-- Dispatch reference data: the shipping-service catalog (service levels by carrier) and the
-- route catalog (regions/depots). Outbound orders reference these by code; routes are fed from
-- a host system (host_ref = the route's id there). Global (not warehouse-scoped); code unique.
SET search_path TO master_data;

CREATE TABLE shipping_service (
    service_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    code       text        NOT NULL UNIQUE,
    name       text,
    carrier    text,
    status     text        NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version    bigint      NOT NULL DEFAULT 0
);

CREATE TABLE route (
    route_id   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    code       text        NOT NULL UNIQUE,
    name       text,
    region     text,
    host_ref   text,
    status     text        NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version    bigint      NOT NULL DEFAULT 0
);
