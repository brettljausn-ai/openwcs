-- ===========================================================================
-- gtp service — give a GTP station a human-readable display name (admin config).
--
-- The station already has a machine `code` (unique per warehouse); admins also
-- want a friendly label (e.g. "Aisle 3 Put-wall") shown in the configuration and
-- operator consoles. Nullable for backward compatibility with existing rows.
-- ===========================================================================

ALTER TABLE gtp_station ADD COLUMN name text;
