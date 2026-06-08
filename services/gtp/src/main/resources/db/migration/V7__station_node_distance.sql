-- The conveyor travel distance (metres) from the feeding conveyor point to a station node, carried
-- from the automation-topology STOCK/ORDER conveyor interactions. Used to time a tote's arrival in
-- the station queue when the emulator is on (arrival = distance / 0.5 m/s for conveyor feeds).
ALTER TABLE station_node
    ADD COLUMN inbound_distance_m numeric(12, 3);
