-- Per-user warehouse access (build.md §4.8). Maps a user to the warehouses they may work in
-- and which one is their default. Warehouses are master-data (a different service/schema), so
-- they're referenced by UUID only — no cross-schema foreign key. Username matches the gateway's
-- forwarded X-Auth-User (Keycloak preferred_username), consistent with screen_access_user.
--
-- A user with no rows here has no warehouse access configured; the gateway treats that as
-- "all warehouses" only for ADMIN (who is never scoped), and as "none" for everyone else.
SET search_path TO iam;

CREATE TABLE user_warehouse (
    username     text        NOT NULL,
    warehouse_id uuid        NOT NULL,
    is_default   boolean     NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (username, warehouse_id)
);

-- At most one default warehouse per user.
CREATE UNIQUE INDEX ux_user_warehouse_default
    ON user_warehouse (username)
    WHERE is_default;
