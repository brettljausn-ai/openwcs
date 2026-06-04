-- Automation-topology fields on equipment: type/subtype, default physical envelope (metres),
-- and the process/function types a section can carry. Underpins the Automation Topology editor.
SET search_path TO master_data;

ALTER TABLE equipment
    ADD COLUMN type             text,
    ADD COLUMN subtype          text,
    ADD COLUMN default_width_m  numeric,
    ADD COLUMN default_height_m numeric,
    ADD COLUMN default_length_m numeric,
    ADD COLUMN process_types    jsonb;
