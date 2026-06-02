-- Dispatch service + route on the outbound order. Both reference master-data catalogs by code
-- (shipping_service / route); routes are fed there from a host system. Stored as codes (no
-- cross-schema FK, per build.md §5.3); validated against master-data at create time.
SET search_path TO orders;

ALTER TABLE outbound_order ADD COLUMN service_code text;
ALTER TABLE outbound_order ADD COLUMN route_code   text;
