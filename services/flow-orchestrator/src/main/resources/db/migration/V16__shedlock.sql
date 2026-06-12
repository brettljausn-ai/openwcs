-- ShedLock lock table: cluster-wide guard so the @Scheduled jobs (the awaiting-slot retry sweep)
-- run on a single replica when the service is scaled out. See ShedLockConfig. Created in this
-- service's own schema (Flyway default-schema = flow), mirroring slotting's V5__shedlock.sql.
SET search_path TO flow;

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
