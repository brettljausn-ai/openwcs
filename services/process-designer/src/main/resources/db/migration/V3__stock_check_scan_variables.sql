-- Add the scanned-code variables (skuBarcode, LocationCode, LocationBarcode, HUBarcode, HUCode) to
-- the seeded "Stock Check" sample's data object. V1 already carries them for fresh installs; this
-- updates an already-seeded definition (e.g. the live demo) so the new variables show up there too.
-- Idempotent: only adds variables whose names are not already present in the dataSchema. Adding
-- data-object variables is non-breaking (running instances pin their own definition copy).
UPDATE process_definition pd
SET json = jsonb_set(
        pd.json,
        '{dataSchema}',
        pd.json->'dataSchema' || (
            SELECT COALESCE(jsonb_agg(v.var), '[]'::jsonb)
            FROM (
                VALUES
                    ('skuBarcode'),
                    ('LocationCode'),
                    ('LocationBarcode'),
                    ('HUBarcode'),
                    ('HUCode')
            ) AS names(name)
            CROSS JOIN LATERAL (
                SELECT jsonb_build_object('name', names.name, 'type', 'string') AS var
            ) v
            WHERE NOT EXISTS (
                SELECT 1
                FROM jsonb_array_elements(pd.json->'dataSchema') AS existing
                WHERE existing->>'name' = names.name
            )
        )
    )
WHERE pd.process_key = 'stock-check'
  AND pd.json ? 'dataSchema';
