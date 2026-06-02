# ADR 0002 — Outbound allocation & cubing architecture

- **Status:** Accepted
- **Date:** 2026-06-02
- **Context:** build.md §4.2, §4.6, §6, §7 (outbound process); supersedes the
  interim SKU-level allocation drafted in order-management.

## Context

Outbound orders must be allocated against **pickable** stock and **cubed** into
shippers before fulfilment. Requirements:

1. **Pick-type config per warehouse** — which pick granularities are allowed:
   `CASE`, `SPLIT_CASE`, `EACH`.
2. **Fulfillability at receipt** — when an order is received, verify sufficient stock
   exists in **pick-able locations**. If yes, **reserve each line against the specific
   pick location(s)**. If no, the order becomes **`NOT_FULFILLABLE`** and waits for
   instructions (UI or API).
3. **Cubing** — determine the **shippers** (boxes, totes, bags, …) an order needs (an
   order may need several). Shippers and SKU dimensions/weights are warehouse-
   configurable. Also support **1:1 cubing**: the host (WMS) already cubed the order
   and the order carries the shipper-to-use instruction.

## Decisions

1. **New `allocation` service** owns outbound fulfilment prep — both the **allocation
   algorithm** and **cubing**. It is an orchestrator with its own store for allocation
   and cube plans; it calls **master-data** (fulfilment config, shippers, locations,
   SKU + UoM dimensions) and **inventory** (location-scoped availability + reservations).
   Keeps inventory a pure stock ledger and order-management a lifecycle owner.

2. **Pick-type config and shippers are master data.**
   - New **`WarehouseFulfillmentConfig`** (one per warehouse): `allowedPickTypes`
     (`CASE`/`SPLIT_CASE`/`EACH`), `cubingMode` (`APP`/`ONE_TO_ONE`), optional default
     shipper.
   - New **`Shipper`** (per warehouse): code, name, type (`BOX`/`TOTE`/`BAG`/`CARTON`/
     `PALLET`), inner dimensions (mm), tare weight (g), **max fill level** (0–1 fraction
     of usable volume), **max weight** (g), status.
   - SKU dimensions/weight already live on **`UnitOfMeasure`** (per packaging level).

3. **Full UoM breakdown — but stock is held in base UoM.**
   Stock quantities (`inventory.stock.qty`) are normalized to the SKU base unit
   (build.md §12). Allocation therefore checks/reserves **base-UoM quantity at
   PICK-purpose locations**, while the **pick-type breakdown** computes the UoM split of
   the pick list (e.g. 30 each → 2×CASE(12) + 6×EACH) from the SKU UoM hierarchy,
   **gated by the warehouse `allowedPickTypes`**. The split drives pick instructions; it
   does not change the reserved quantity.

4. **Fulfillability at receipt.** order-management, on receipt, requests allocation from
   the allocation service. For each line the allocator:
   - resolves candidate **PICK** locations (`location.purpose = PICK`) for the warehouse;
   - checks **location-scoped** available-to-promise from inventory and reserves against
     specific pick locations until the line qty is met;
   - if every line is fully reserved → order **`ALLOCATED`** with a pick plan + cube plan;
   - otherwise it **releases any partial reservations** and reports **`NOT_FULFILLABLE`**;
     order-management sets that status and the order **waits for instructions**
     (retry / cancel / manual allocation) via UI or API.

5. **Cubing.**
   - `APP` mode: greedy volumetric packing — usable shipper volume =
     `innerVolume × maxFillLevel`; fill by total item volume **and** weight (incl. shipper
     tare vs `maxWeight`), opening a new shipper when either limit is hit; choose the
     smallest configured shipper that fits the largest item, else escalate to a larger one.
   - `ONE_TO_ONE` mode: the order/line carries a `shipperCode`; cubing validates capacity
     and records the assignment without computing a packing.

6. **Batch (cluster) picking.** Small orders can be grouped into one **pick tote**
   (a shipper used as the pick container), picked as a single combined pick order, then
   **separated into each order's final shipper(s) at packing**. This compresses travel
   and raises pick throughput. Configurable per warehouse:
   - `batchEnabled` — on/off.
   - `batchMaxPieces` — eligibility threshold: an order is batchable when its total
     pieces ≤ this value (`1` ⇒ only single-item orders; `n` ⇒ orders up to n pieces).
   - `batchMaxOrders` — how many orders fit in one pick tote (tote capacity).
   - `pickToteShipperId` — which shipper is the pick tote.

   A **pick batch** references the member orders' existing allocations (this service's
   `OrderAllocation`s), merges their picks into one combined pick list (by location ×
   SKU), and records a **separation plan** (per member: tote position + which picked
   units go to that order's final shipper). The batch is built only from already
   **FULFILLABLE** orders, so batching does not change reservations — it reorganizes the
   pick work and defines the downstream separation.

## Assumptions (first build)

- "Pick-able" = `location.purpose = PICK` (build.md §6 functional axis).
- Stock is base-UoM throughout; the pick-type split is advisory pick instruction, not a
  separate reservation unit.
- Cubing is **volume + weight** (not true 3D bin-packing); `maxFillLevel` accounts for
  packing inefficiency. 3D packing is a future refinement.
- Location-scoped ATP at a pick location = `Σ AVAILABLE stock at location −
  Σ HELD reservations at location`.

## Consequences

- A new deployable service (`services/allocation`, port 8091) joins the gradle build,
  compose, gateway, and the service list in `build.md`/`README`.
- inventory gains location-scoped availability + reservation (reservations already carry
  `location_id`).
- order-management no longer reserves directly; it delegates to the allocation service
  and owns the order lifecycle incl. `NOT_FULFILLABLE` and the wait-for-instructions step.
- master-data grows the fulfilment config + shipper catalog and exposes them via REST.
