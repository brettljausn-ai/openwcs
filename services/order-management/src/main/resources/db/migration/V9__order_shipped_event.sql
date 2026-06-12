-- Short allocate and release: ship now stages an order-level OrderShipped event on the
-- outbox (per-line orderedQty vs shippedQty, so a short ship is visible to the host via
-- the confirmation feed). Order-level events have no source line transaction, so the
-- outbox link becomes optional.
SET search_path TO orders;

ALTER TABLE order_outbox ALTER COLUMN line_txn_id DROP NOT NULL;
