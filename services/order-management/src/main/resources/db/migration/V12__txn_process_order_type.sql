-- Fulfilment integrity: stamp each stock transaction with the process instance that drove it
-- and the order type it was posted under, so a pick (and any other posting) is traceable back
-- to the handheld process run and the order kind without a join. Both are nullable for backward
-- compatibility (existing rows, and callers that do not run inside a process).
SET search_path TO orders;

ALTER TABLE order_line_transaction ADD COLUMN process_instance_id text;
ALTER TABLE order_line_transaction ADD COLUMN order_type text;
