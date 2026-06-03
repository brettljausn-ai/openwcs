-- A handling unit can have 1–8 compartments, each holding a different SKU. The full compartment
-- SKU set is the lane-affinity key (single-SKU HUs have a one-element set; empty HUs an empty set).
-- sku_id remains the dominant compartment SKU (drives velocity / redundancy / the aisle cap).
ALTER TABLE putaway_assignment ADD COLUMN sku_ids jsonb;
