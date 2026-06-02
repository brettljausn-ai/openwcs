-- Grant the device permissions (added to the catalog for the flow-orchestrator, build.md
-- §4.5/§8) to the seeded roles, mirroring RoleCatalog: VIEWER gets DEVICE_VIEW; OPERATOR,
-- SUPERVISOR and ADMIN also get DEVICE_OPERATE.
SET search_path TO iam;

INSERT INTO role_permission (role_id, permission)
    SELECT r.role_id, p
    FROM role r
    CROSS JOIN unnest(ARRAY['DEVICE_VIEW', 'DEVICE_OPERATE']) AS p
    WHERE r.name IN ('ADMIN', 'SUPERVISOR', 'OPERATOR')
ON CONFLICT DO NOTHING;

INSERT INTO role_permission (role_id, permission)
    SELECT r.role_id, 'DEVICE_VIEW'
    FROM role r
    WHERE r.name = 'VIEWER'
ON CONFLICT DO NOTHING;
