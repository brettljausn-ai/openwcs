-- Global key/value system configuration (e.g. the demo-mode flag). Demo mode seeds a sample
-- catalog onto an empty, host-free system and removes it again when switched off
-- (see DemoSeedService / DemoController).
SET search_path TO master_data;

CREATE TABLE system_configuration (
    config_key   text        PRIMARY KEY,
    config_value text        NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now()
);

INSERT INTO system_configuration (config_key, config_value) VALUES ('DEMO_MODE_ENABLED', 'false');
