-- Carry the handling unit (tote) a count cell sits on, captured from the inventory snapshot when the
-- task is generated. ASRS-family stock always lives on an HU; without it the StockAdjusted event the
-- reconcile/at-station-count path posts cannot target the tote's bucket, so the inventory projection
-- mints a phantom hu_id IS NULL bucket at the shuttle location. The column is nullable: bin stock has
-- no HU (hu_id stays null, which is correct for those cells).
-- Flyway default-schema = counting, so no SET search_path here — plain DDL.

ALTER TABLE count_line ADD COLUMN hu_id uuid;
