-- Service-local store for the canonical Host API: idempotency keys for safe retries of host
-- POSTs (the stored response is replayed on a repeat key). Webhook delivery state is added in
-- a later migration.
CREATE SCHEMA IF NOT EXISTS host_integration;
SET search_path TO host_integration;

CREATE TABLE idempotency_key (
    idempotency_key text        PRIMARY KEY,
    http_status     int         NOT NULL,
    response_body   text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
