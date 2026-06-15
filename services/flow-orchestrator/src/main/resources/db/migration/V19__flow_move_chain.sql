-- Follow-up to the generic physical-move dispatch (POST /api/flow/moves): a cross-system move
-- (source and destination served by different storage systems / transport families) cannot be a
-- single RELOCATE. It runs a multi-leg chain RETRIEVE -> CONVEY -> STORE, mirroring the induction
-- return-leg orchestration. `flow_move_chain` tracks the per-leg device-task ids so each leg's
-- completion callback advances to the next leg, and the final STORE completion books the HU's
-- destination location in inventory + fires the HandlingUnitMoved audit. The same-system case stays
-- a single RELOCATE and persists NO chain row (it is booked by DeviceTaskService.bookFlowMoveLocation
-- off the moveSource=flow-move payload tag, untouched here).
SET search_path TO flow;

CREATE TABLE flow_move_chain (
    move_chain_id      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id       uuid        NOT NULL,
    hu_id              uuid        NOT NULL,
    hu_code            text,
    from_location_id   uuid,                          -- source storage slot (RETRIEVE origin)
    to_location_id     uuid        NOT NULL,           -- destination slot the final STORE books
    source_family      text        NOT NULL,           -- equipment family of the source system
    dest_family        text        NOT NULL,           -- equipment family of the destination system
    reason             text,
    -- PENDING (RETRIEVE in flight) -> RETRIEVED (CONVEY in flight) -> CONVEYED (STORE in flight)
    -- -> STORED (done) ; FAILED on any leg failure (chain stops, recoverable/inspectable).
    status             text        NOT NULL DEFAULT 'PENDING',
    -- links to the device tasks orchestrating each leg (same pattern as induction_queue_entry):
    retrieve_task_id   uuid,                           -- the RETRIEVE/BIN_RETRIEVE device_task
    convey_task_id     uuid,                           -- the CONVEY device_task
    store_task_id      uuid,                           -- the STORE/BIN_STORE device_task
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT flow_move_chain_status_chk
        CHECK (status IN ('PENDING','RETRIEVED','CONVEYED','STORED','FAILED'))
);

CREATE INDEX flow_move_chain_retrieve_idx ON flow_move_chain (retrieve_task_id);
CREATE INDEX flow_move_chain_convey_idx   ON flow_move_chain (convey_task_id);
CREATE INDEX flow_move_chain_store_idx    ON flow_move_chain (store_task_id);
CREATE INDEX flow_move_chain_hu_idx       ON flow_move_chain (warehouse_id, hu_id);
