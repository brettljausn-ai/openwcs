-- ADR-0009 double-deep channel relocation (dig-out): before a blocked RETRIEVE, flow dispatches a
-- RELOCATE/BIN_RELOCATE device task per blocker (plan from slotting). This column links the
-- induction entry to the in-flight RELOCATE, mirroring retrieve_task_id; it is cleared on the
-- relocate callback so the next chain step (another RELOCATE or the real RETRIEVE) can be stamped.
SET search_path TO flow;

ALTER TABLE induction_queue_entry
    ADD COLUMN relocate_task_id uuid;             -- the in-flight RELOCATE/BIN_RELOCATE device_task

CREATE INDEX induction_entry_relocate_idx ON induction_queue_entry (relocate_task_id);
