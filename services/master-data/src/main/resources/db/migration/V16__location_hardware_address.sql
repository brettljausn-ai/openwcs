-- Per-location hardware/controller address (e.g. an ASRS bin address). Optional, populated later.
SET search_path TO master_data;

ALTER TABLE location ADD COLUMN hardware_address text;
