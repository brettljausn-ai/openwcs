-- Static reference catalogs enumerated in build.md §6. These are stable, code-known
-- types (not customer data), so they ship as a baseline seed. Idempotent on `name`.
SET search_path TO master_data;

INSERT INTO barcode_type (name, symbology, gs1_ai_parsing) VALUES
    ('EAN13',     'EAN13',     false),
    ('GTIN14',    'ITF14',     false),
    ('CODE128',   'CODE128',   false),
    ('GS1-128',   'CODE128',   true),
    ('QR',        'QR',        true),
    ('DATAMATRIX','DATAMATRIX',true),
    ('SSCC',      'CODE128',   true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO handling_unit_type (name, nestable) VALUES
    ('TOTE',   true),
    ('TRAY',   true),
    ('CARTON', false),
    ('PALLET', false)
ON CONFLICT (name) DO NOTHING;
