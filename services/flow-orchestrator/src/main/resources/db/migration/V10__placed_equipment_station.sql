-- A placed "workstation" references the GTP station (gtp_station) it represents, so a GTP workplace
-- can be positioned on the automation topology and connected to conveyors. Null for all other
-- equipment. No FK (gtp_station lives in another service's schema; the id is a soft reference).
SET search_path TO flow;

ALTER TABLE placed_equipment ADD COLUMN station_id uuid;
