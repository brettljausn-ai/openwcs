-- One-time data fix for the counting-adjustment bug: the StockAdjusted event posted on a reconciled
-- count variance did not carry the handling-unit id, so the projection's bucket upsert keyed on
-- hu_id IS NULL and minted a phantom HU-less stock row at the (HU-managed) shuttle/ASRS cell instead
-- of correcting the tote's bucket. The producer now carries hu_id (counting V5), so no new phantoms
-- are created; this removes the ones already projected.
--
-- A null-HU row is treated as a phantom only when a real HU bucket exists at the same cell (same
-- warehouse + SKU + location): that proves the cell is HU-managed, so the HU-less row can only be the
-- adjustment artifact. Legitimate bin stock — the sole row at its cell, with no co-located HU — is
-- never matched. No-op on a clean database.

DELETE FROM stock s
WHERE s.hu_id IS NULL
  AND EXISTS (
      SELECT 1 FROM stock t
      WHERE t.warehouse_id = s.warehouse_id
        AND t.sku_id = s.sku_id
        AND t.location_id = s.location_id
        AND t.hu_id IS NOT NULL
  );
