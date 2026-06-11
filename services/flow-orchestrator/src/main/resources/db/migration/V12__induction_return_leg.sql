-- ADR-0007 return-to-storage transport leg: when the operator marks an induction entry DONE, flow
-- dispatches the return CONVEY (station → storage) and, on its arrival, the STORE/BIN_STORE back
-- into the source slot. These columns link the entry to the device tasks orchestrating the return
-- leg, mirroring retrieve_task_id / convey_task_id on the outbound leg.
SET search_path TO flow;

ALTER TABLE induction_queue_entry
    ADD COLUMN return_convey_task_id uuid,            -- the return CONVEY device_task (station → storage)
    ADD COLUMN return_store_task_id  uuid;            -- the STORE/BIN_STORE device_task (back into the slot)

CREATE INDEX induction_entry_return_convey_idx ON induction_queue_entry (return_convey_task_id);
CREATE INDEX induction_entry_return_store_idx  ON induction_queue_entry (return_store_task_id);
