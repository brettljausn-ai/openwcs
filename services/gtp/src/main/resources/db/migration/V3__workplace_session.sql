-- ===========================================================================
-- gtp service — workplace operator sessions (single-active-session-per-workplace).
--
-- A GTP station is an operator *workplace*. When an operator opens a workplace
-- in the ops console they CLAIM a session; the console then HEARTBEATs to keep
-- it alive and RELEASEs it on close. Only ONE session may be ACTIVE per
-- workplace at a time: a new claim SUPERSEDES the previous active session
-- (marks it SUPERSEDED), so the prior operator's UI can detect it was taken
-- over and stop work. This prevents two operators driving the same physical
-- put-wall / station at once.
--
--   status = ACTIVE      — the live session currently owning the workplace
--          | SUPERSEDED  — taken over by a newer claim on the same workplace
--          | RELEASED    — cleanly released by its own operator
-- ===========================================================================

CREATE TABLE workplace_session (
    workplace_session_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    gtp_station_id       uuid        NOT NULL REFERENCES gtp_station (gtp_station_id) ON DELETE CASCADE,
    operator             text,                                  -- who claimed it (from X-Auth-User), optional
    status               text        NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | SUPERSEDED | RELEASED
    superseded_reason    text,                                  -- why it closed (e.g. 'superseded', 'released')
    claimed_at           timestamptz NOT NULL DEFAULT now(),
    last_heartbeat_at    timestamptz NOT NULL DEFAULT now(),
    closed_at            timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    version              bigint      NOT NULL DEFAULT 0,
    CONSTRAINT workplace_session_status_chk
        CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'RELEASED'))
);

-- Enforce at most one ACTIVE session per workplace at the database level (a
-- partial unique index): the claim path supersedes the old session first, so
-- this is a safety net against races.
CREATE UNIQUE INDEX workplace_session_one_active_idx
    ON workplace_session (gtp_station_id)
    WHERE status = 'ACTIVE';

CREATE INDEX workplace_session_station_idx ON workplace_session (gtp_station_id, status);
