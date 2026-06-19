-- ===========================================================================
-- gtp service: configurable picking layout per station (configurable GTP picking).
-- pick_layout is how the operator console renders a station's pick UI and how
-- pick-to-light is driven:
--   ONE_TO_ONE -> one stock HU serves one destination at a time (classic GTP)
--   ONE_TO_N   -> one stock HU fans out to N lit slots (batch put, pick_slots = N)
--   PUT_WALL   -> a rack of lit cubbies (only valid when mode = PUT_WALL)
-- pick_slots is the slot count for ONE_TO_N (>= 2); null for the other layouts.
-- ===========================================================================
ALTER TABLE gtp_station
    ADD COLUMN pick_layout text NOT NULL DEFAULT 'ONE_TO_ONE',
    ADD COLUMN pick_slots  int  NULL;

ALTER TABLE gtp_station
    ADD CONSTRAINT gtp_station_pick_layout_chk
        CHECK (pick_layout IN ('ONE_TO_ONE', 'ONE_TO_N', 'PUT_WALL'));

-- Existing put-wall stations adopt the PUT_WALL layout (was implicit in mode).
UPDATE gtp_station SET pick_layout = 'PUT_WALL' WHERE mode = 'PUT_WALL';
