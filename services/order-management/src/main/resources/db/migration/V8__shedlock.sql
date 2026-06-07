-- ShedLock lock table: cluster-wide guard so @Scheduled jobs (the outbox relay) run on a single
-- replica when the service is scaled out. See ShedLockConfig. Created in this service's own schema
-- (Flyway default-schema = orders).
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
