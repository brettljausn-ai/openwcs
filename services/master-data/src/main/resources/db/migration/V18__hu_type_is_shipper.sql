-- A handling-unit type can double as a shipper: a HU that leaves the warehouse with the goods
-- (e.g. a shipping carton or tote) and is therefore a candidate target for outbound cubing.
-- Defaults false (most HU types stay inside the four walls).
SET search_path TO master_data;

ALTER TABLE handling_unit_type
    ADD COLUMN is_shipper boolean NOT NULL DEFAULT false;
