-- Configurable screen-access overrides (build.md §4.8). The UI owns the canonical screen
-- *catalog* (ui/src/auth/screens.ts) with each screen's default roles; this store only holds
-- per-screen *overrides* that replace those defaults. A screen with no row here falls back to
-- its code/UI default. The screen key is an opaque string owned by the UI; we persist whatever
-- is sent. Roles and the user allow-list are stored as element collections.
SET search_path TO iam;

CREATE TABLE screen_access (
    screen_key text        PRIMARY KEY,           -- opaque UI-owned screen key
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint      NOT NULL DEFAULT 0
);

CREATE TABLE screen_access_role (
    screen_key text NOT NULL REFERENCES screen_access (screen_key) ON DELETE CASCADE,
    role       text NOT NULL,
    PRIMARY KEY (screen_key, role)
);

CREATE TABLE screen_access_user (
    screen_key text NOT NULL REFERENCES screen_access (screen_key) ON DELETE CASCADE,
    username   text NOT NULL,
    PRIMARY KEY (screen_key, username)
);
