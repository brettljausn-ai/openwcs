# ADR 0001 — Inventory data ownership: batch/lot & serial units live in the inventory store

- **Status:** Accepted
- **Date:** 2026-06-02
- **Context:** build.md §4.2, §5.1, §5.3, §6, §16

## Context

The master-data domain model (build.md §6) draws Batch/Lot and SerialUnit hanging
off the SKU, alongside genuine master data (UoM, barcodes, locations, equipment).
That placement is about the *relationship graph*, not storage ownership. The same
section is explicit that these are **instance** records, "captured at goods-in …
**NOT** SKU master data … created during receiving and tracked through inventory
and the transaction log."

Two hard constraints force a decision on where they physically live:

1. **§5.1** — the shared PostgreSQL holds **only** `master_data` and
   `transaction_log`, and **only the master-data service writes** the `master_data`
   schema.
2. Batch/lot and serial rows are **created during receiving** (a process flow), not
   via master-data CRUD, and their state mutates (quality status, serial status,
   current location/HU).

If they lived in `master_data`, every goods-in event would have to call the
master-data service to mint a batch — coupling a hot operational path to the catalog
service and writing operational state into the catalog schema.

## Decision

**Batch/Lot and SerialUnit are owned by the inventory service** and live in its
service-local `inventory` schema, next to the `stock` table — not in `master_data`.

- Cross-service references (`sku_id`, `location_id`, `hu_id`) are stored as plain
  UUIDs with **no database foreign key** to `master_data`; integrity across the
  boundary is by event/API, per §5.3.
- Stock is the durable authoritative current-state table keyed by
  SKU × batch/lot × location × HU × status with the level in `qty` (§16); the
  transaction log remains the immutable audit trail that can replay/rebuild it
  (a `projection_offset` cursor tracks replay position).

## Local-dev note

§4.2 sanctions a service-local store as "its own Postgres DB **or schema**." For
local dev the inventory service uses an isolated `inventory` **schema** inside the
same Postgres container as the shared DB — only the inventory service touches it.
In production it can be promoted to its own database with no model change (the
absence of cross-schema FKs is what makes that move free).

## Consequences

- Goods-in creates batches/serials through the inventory service, keeping the
  catalog service free of operational write traffic.
- The `master_data` schema stays small and stable (global identity only).
- Reporting that joins batch attributes to catalog attributes must compose data
  from two services (via API/events or a downstream read model), not a SQL join —
  an accepted cost of the bounded-context split.
