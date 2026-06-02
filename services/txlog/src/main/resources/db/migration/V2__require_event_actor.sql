-- Audit: every logged event must record who/what caused it (user / service / device,
-- build.md §5.2). Enforce actor at the system of record.
SET search_path TO transaction_log;

ALTER TABLE events ALTER COLUMN actor SET NOT NULL;
