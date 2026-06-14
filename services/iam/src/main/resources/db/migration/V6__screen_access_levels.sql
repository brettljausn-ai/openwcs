-- Three-level screen access (off / read / write). Until now a role/user row in
-- screen_access_role / screen_access_user meant simply "may open this screen" (a binary
-- toggle). We add a per-row access_level so an override can grant READ (view-only) or WRITE
-- (full) access; the *absence* of a row still means OFF. Existing overrides are backfilled to
-- WRITE so behaviour is unchanged on upgrade.
SET search_path TO iam;

ALTER TABLE screen_access_role ADD COLUMN access_level text NOT NULL DEFAULT 'WRITE';
ALTER TABLE screen_access_user ADD COLUMN access_level text NOT NULL DEFAULT 'WRITE';

-- The default was only for the backfill; the application always sets the level explicitly.
ALTER TABLE screen_access_role ALTER COLUMN access_level DROP DEFAULT;
ALTER TABLE screen_access_user ALTER COLUMN access_level DROP DEFAULT;

ALTER TABLE screen_access_role ADD CONSTRAINT screen_access_role_level_chk CHECK (access_level IN ('READ', 'WRITE'));
ALTER TABLE screen_access_user ADD CONSTRAINT screen_access_user_level_chk CHECK (access_level IN ('READ', 'WRITE'));
