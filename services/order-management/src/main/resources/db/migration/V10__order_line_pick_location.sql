-- Allocated pick location threaded onto the order line.
--
-- The allocation service assigns each outbound line a primary pick location (the
-- reservation/pick face). order-management now persists it on release/allocate so the
-- operator pick queue (GET /api/orders/pick-tasks) can show a real location before any
-- stock is picked. Nullable: lines allocated before this column existed, or whose
-- allocation reserved nothing, carry null (the pick queue then falls back to the latest
-- Picked-transaction location, else null — no regression).
ALTER TABLE order_line
    ADD COLUMN pick_location_id uuid;          -- ref inventory pick location (allocated face)
