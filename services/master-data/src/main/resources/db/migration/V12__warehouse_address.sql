-- Physical address for a warehouse / site (all optional).
SET search_path TO master_data;

ALTER TABLE warehouse
    ADD COLUMN address_line1 text,
    ADD COLUMN address_line2 text,
    ADD COLUMN city          text,
    ADD COLUMN region        text,
    ADD COLUMN postal_code   text,
    ADD COLUMN country       text;
