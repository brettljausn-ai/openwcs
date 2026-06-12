-- Slotting-only store-back: only slotting decides where a tote goes after its workplace visit.
-- storage_location_id records the slotting-chosen destination for the return-leg STORE (the source
-- slot in location_id is NEVER a return destination anymore). awaiting_slot marks an entry whose
-- return CONVEY was dispatched without a destination because slotting errored / had no answer: the
-- tote stays on the conveyor and a scheduled sweep retries slotting until it answers.
SET search_path TO flow;

ALTER TABLE induction_queue_entry
    ADD COLUMN storage_location_id uuid,                       -- slotting-chosen return destination
    ADD COLUMN awaiting_slot boolean NOT NULL DEFAULT false;   -- return leg waiting for slotting

CREATE INDEX induction_entry_awaiting_slot_idx
    ON induction_queue_entry (awaiting_slot) WHERE awaiting_slot;
