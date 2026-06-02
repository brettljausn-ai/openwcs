-- Generalize orders to all warehouse order types and record the stock transactions that
-- post against each line (inbound receipts, outbound picks, cycle counts, manual adjusts).
-- order-management appends the matching event to the transaction log; the event_id is kept
-- on each line transaction. (The `outbound_order` table now holds every order type; the
-- legacy name is retained to avoid a disruptive rename.)
SET search_path TO orders;

ALTER TABLE outbound_order ADD COLUMN order_type text NOT NULL DEFAULT 'OUTBOUND';
ALTER TABLE outbound_order ADD CONSTRAINT outbound_order_type_chk
    CHECK (order_type IN ('INBOUND', 'OUTBOUND', 'COUNT', 'ADJUSTMENT'));

-- Progress rollup on the line: signed sum of posted transaction quantities.
ALTER TABLE order_line ADD COLUMN posted_qty numeric(18, 4) NOT NULL DEFAULT 0;

-- One row per posted stock transaction beneath a line.
CREATE TABLE order_line_transaction (
    txn_id      uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    line_id     uuid          NOT NULL REFERENCES order_line (line_id) ON DELETE CASCADE,
    txn_type    text          NOT NULL,                 -- RECEIPT | PICK | COUNT | ADJUSTMENT
    qty         numeric(18, 4) NOT NULL,                -- line-progress contribution (signed for COUNT/ADJUSTMENT)
    location_id uuid,
    hu_id       uuid,
    batch_id    uuid,
    event_id    uuid,                                   -- the transaction-log event appended for this posting
    actor       text,
    posted_at   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT order_line_transaction_type_chk CHECK (txn_type IN ('RECEIPT', 'PICK', 'COUNT', 'ADJUSTMENT'))
);

CREATE INDEX order_line_transaction_line_idx ON order_line_transaction (line_id);
