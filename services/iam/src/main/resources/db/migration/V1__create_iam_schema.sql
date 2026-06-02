-- openWCS authorization model (build.md §4.8): users → roles → coded permissions.
-- Authentication is handled by Keycloak (OIDC); this schema layers openWCS RBAC on top.
-- The permission *catalog* is code-defined (org.openwcs.common.security.Permission); the
-- role → permission and user → role *assignments* are data.

CREATE SCHEMA IF NOT EXISTS iam;
SET search_path TO iam;

CREATE TABLE role (
    role_id     uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text        NOT NULL UNIQUE,
    description text,
    is_system   boolean     NOT NULL DEFAULT false,   -- shipped seed role
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint      NOT NULL DEFAULT 0
);

CREATE TABLE role_permission (
    role_id    uuid NOT NULL REFERENCES role (role_id) ON DELETE CASCADE,
    permission text NOT NULL,                          -- a Permission catalog code
    PRIMARY KEY (role_id, permission)
);

CREATE TABLE app_user (
    user_id      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    username     text        NOT NULL UNIQUE,
    display_name text,
    external_id  text,                                 -- Keycloak subject (sub claim)
    status       text        NOT NULL DEFAULT 'ACTIVE',
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 0
);

CREATE TABLE user_role (
    user_id uuid NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE,
    role_id uuid NOT NULL REFERENCES role (role_id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- ---------------------------------------------------------------------------
-- Seed the shipped roles (build.md §15 Phase 0) and their permission sets.
-- ---------------------------------------------------------------------------
INSERT INTO role (name, description, is_system) VALUES
    ('ADMIN',      'Full access, including user/role administration', true),
    ('SUPERVISOR', 'Operations management: orders, release, allocation, master-data edit', true),
    ('OPERATOR',   'Floor operations: post stock transactions, stock adjust, view', true),
    ('VIEWER',     'Read-only access', true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO role_permission (role_id, permission)
    SELECT r.role_id, p
    FROM role r
    CROSS JOIN unnest(ARRAY[
        'MASTER_DATA_VIEW','MASTER_DATA_EDIT','INVENTORY_VIEW','STOCK_ADJUST',
        'ORDER_VIEW','ORDER_CREATE','ORDER_RELEASE','ORDER_CANCEL','ORDER_SHIP','ORDER_POST_TRANSACTION',
        'ALLOCATION_RUN','BATCH_BUILD','TXLOG_VIEW','TXLOG_APPEND','IAM_ADMIN']) AS p
    WHERE r.name = 'ADMIN';

INSERT INTO role_permission (role_id, permission)
    SELECT r.role_id, p
    FROM role r
    CROSS JOIN unnest(ARRAY[
        'MASTER_DATA_VIEW','MASTER_DATA_EDIT','INVENTORY_VIEW','STOCK_ADJUST',
        'ORDER_VIEW','ORDER_CREATE','ORDER_RELEASE','ORDER_CANCEL','ORDER_SHIP','ORDER_POST_TRANSACTION',
        'ALLOCATION_RUN','BATCH_BUILD','TXLOG_VIEW','TXLOG_APPEND']) AS p
    WHERE r.name = 'SUPERVISOR';

INSERT INTO role_permission (role_id, permission)
    SELECT r.role_id, p
    FROM role r
    CROSS JOIN unnest(ARRAY[
        'MASTER_DATA_VIEW','INVENTORY_VIEW','ORDER_VIEW','TXLOG_VIEW',
        'ORDER_POST_TRANSACTION','STOCK_ADJUST']) AS p
    WHERE r.name = 'OPERATOR';

INSERT INTO role_permission (role_id, permission)
    SELECT r.role_id, p
    FROM role r
    CROSS JOIN unnest(ARRAY[
        'MASTER_DATA_VIEW','INVENTORY_VIEW','ORDER_VIEW','TXLOG_VIEW']) AS p
    WHERE r.name = 'VIEWER';
