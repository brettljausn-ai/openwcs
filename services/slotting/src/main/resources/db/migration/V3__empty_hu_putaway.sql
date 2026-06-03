-- Empty handling units have no SKU (they're stored as a buffer and retrieved for decanting),
-- so a put-away assignment for an empty HU carries no sku_id.
ALTER TABLE putaway_assignment ALTER COLUMN sku_id DROP NOT NULL;
