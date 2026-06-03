-- Webhook (push) delivery of confirmations: a host registers a callback URL; openWCS streams
-- transaction-log confirmations to it, advancing a per-subscription cursor as deliveries
-- succeed (at-least-once). Pull (GET /confirmations) remains available alongside this.
SET search_path TO host_integration;

CREATE TABLE webhook_subscription (
    subscription_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    callback_url    text        NOT NULL,
    cursor          bigint      NOT NULL DEFAULT 0,
    active          boolean     NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0
);
