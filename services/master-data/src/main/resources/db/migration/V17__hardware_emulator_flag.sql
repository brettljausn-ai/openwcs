-- Hardware emulator mode: a global on/off flag (default OFF). When ON, the device adapters
-- simulate all equipment and never open a connection to physical hardware; when OFF, the real
-- adapter connection path is used (see HardwareEmulatorService / EmulatorController and the Go
-- adapters' emulator poller).
SET search_path TO master_data;

INSERT INTO system_configuration (config_key, config_value)
VALUES ('HARDWARE_EMULATOR_ENABLED', 'false')
ON CONFLICT (config_key) DO NOTHING;
