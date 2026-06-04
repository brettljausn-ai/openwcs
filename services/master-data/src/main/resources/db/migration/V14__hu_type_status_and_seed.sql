-- Handling-unit types: add an archive status, correct the seeded tote, and seed the
-- 6 ISO standard pallet footprints (replacing the generic PALLET).
SET search_path TO master_data;

ALTER TABLE handling_unit_type ADD COLUMN status text NOT NULL DEFAULT 'ACTIVE';

-- Standard automation tote: 600×400×400 mm, 2 kg, 1 compartment, automation + conveyor OK.
UPDATE handling_unit_type
   SET length_mm = 600, width_mm = 400, height_mm = 400, weight_limit_g = 2000,
       compartments = 1, storable_in_automation = true, transportable_on_conveyor = true
 WHERE name = 'TOTE';

-- The 6 globally recognised ISO pallet footprints (length × width, mm). Pallets are NOT
-- automation- or conveyor-capable. Height/weight are sensible loaded defaults (editable per type);
-- the ISO standard fixes the footprint.
DELETE FROM handling_unit_type WHERE name = 'PALLET';
INSERT INTO handling_unit_type
    (name, length_mm, width_mm, height_mm, weight_limit_g, nestable, compartments,
     storable_in_automation, transportable_on_conveyor)
VALUES
    ('PALLET-EUR-1',  1200,  800, 1500, 1000000, false, 1, false, false), -- ISO1 / Euro (EUR/EPAL)
    ('PALLET-EUR-2',  1200, 1000, 1500, 1000000, false, 1, false, false), -- ISO2 / UK standard
    ('PALLET-NA',     1219, 1016, 1500, 1000000, false, 1, false, false), -- North American (48×40 in)
    ('PALLET-AU',     1165, 1165, 1500, 1000000, false, 1, false, false), -- Australian
    ('PALLET-ASIA',   1100, 1100, 1500, 1000000, false, 1, false, false), -- Asian
    ('PALLET-GLOBAL', 1067, 1067, 1500, 1000000, false, 1, false, false)  -- Global / interchangeable
ON CONFLICT (name) DO NOTHING;
