-- Batch (cluster) picking configuration per warehouse (ADR 0002 §6): group small orders
-- into one pick tote, pick once, separate into final shippers at packing.
SET search_path TO master_data;

ALTER TABLE warehouse_fulfillment_config
    ADD COLUMN batch_enabled        boolean NOT NULL DEFAULT false,
    ADD COLUMN batch_max_pieces     int     NOT NULL DEFAULT 1,   -- order is batchable when total pieces <= this
    ADD COLUMN batch_max_orders     int     NOT NULL DEFAULT 12,  -- orders per pick tote
    ADD COLUMN pick_tote_shipper_id uuid    REFERENCES shipper (shipper_id);
