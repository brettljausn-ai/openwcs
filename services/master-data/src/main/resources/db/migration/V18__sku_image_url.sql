-- Optional product image for a SKU, shown e.g. on the GTP operator console when an HU carrying
-- the SKU is active. Host-owned like the rest of the SKU core; null when not provided.
SET search_path TO master_data;

ALTER TABLE sku ADD COLUMN image_url text;
