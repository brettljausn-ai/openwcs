-- ---------------------------------------------------------------------------
-- Handling-unit capabilities + per-block allowed HU types (ADR 0003).
--
-- Capabilities let admins express e.g. "conveyable but not storable in automation"
-- and multi-compartment carriers (1–8). The actual gate on what may be stored in an
-- automated area is the per-block allowed-HU-types list (different per area); an empty
-- list means the block accepts any HU type.
-- ---------------------------------------------------------------------------

ALTER TABLE handling_unit_type ADD COLUMN compartments              integer NOT NULL DEFAULT 1;
ALTER TABLE handling_unit_type ADD COLUMN storable_in_automation    boolean NOT NULL DEFAULT true;
ALTER TABLE handling_unit_type ADD COLUMN transportable_on_conveyor boolean NOT NULL DEFAULT true;
ALTER TABLE handling_unit_type ADD CONSTRAINT hu_type_compartments_chk CHECK (compartments BETWEEN 1 AND 8);

ALTER TABLE storage_block ADD COLUMN allowed_hu_types jsonb;  -- list of HU type names; null/empty = accept any
