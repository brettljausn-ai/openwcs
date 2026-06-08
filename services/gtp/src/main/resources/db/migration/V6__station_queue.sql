-- ===========================================================================
-- gtp service — station inbound work queue + deactivate/drain.
--
-- When a transport routes a handling unit to a workplace, an entry is created
-- here. While the emulator simulates the move it is IN_TRANSIT (with a computed
-- arrival_at); once due it becomes QUEUED and the operator works the queue in
-- arrival order (physical FIFO). Completing the head marks it DONE.
--
-- accepting_work is the deactivate/drain switch: a deactivated station finishes
-- the work already queued/assigned to it but accepts no new inbound units. It
-- does NOT close the operator session (the browser stays linked).
-- ===========================================================================

ALTER TABLE gtp_station
    ADD COLUMN accepting_work boolean NOT NULL DEFAULT true;

CREATE TABLE station_queue_entry (
    station_queue_entry_id uuid          PRIMARY KEY,
    gtp_station_id         uuid          NOT NULL,
    warehouse_id           uuid          NOT NULL,
    hu_id                  uuid,
    hu_code                text,
    sku_id                 uuid,
    sku_code               text,
    qty                    numeric(18, 4),
    mode                   text          NOT NULL,
    status                 text          NOT NULL DEFAULT 'IN_TRANSIT',
    arrival_at             timestamptz   NOT NULL,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    version                bigint        NOT NULL DEFAULT 0
);

CREATE INDEX station_queue_station_status_idx ON station_queue_entry (gtp_station_id, status);
