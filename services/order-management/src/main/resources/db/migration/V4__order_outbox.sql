-- Local transactional outbox (build.md §5.5): a line transaction and the intent to append
-- its event to the transaction log commit in ONE local transaction, so the audit record is
-- never lost. A relay then appends to txlog and stamps published_at + the event id.
SET search_path TO orders;

CREATE TABLE order_outbox (
    id             bigserial   PRIMARY KEY,
    line_txn_id    uuid        NOT NULL REFERENCES order_line_transaction (txn_id) ON DELETE CASCADE,
    stream_id      text        NOT NULL,        -- the order line id
    event_type     text        NOT NULL,        -- GoodsReceived | Picked | StockAdjusted
    correlation_id uuid,                         -- the order id
    actor          text        NOT NULL,        -- who caused it (required for audit)
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz,
    attempts       int         NOT NULL DEFAULT 0
);

CREATE INDEX order_outbox_unpublished_idx ON order_outbox (id) WHERE published_at IS NULL;
