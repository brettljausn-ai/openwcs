# ADR 0003 — Slotting & replenishment

- **Status:** Accepted
- **Date:** 2026-06-03
- **Context:** build.md §6 (locations, `SkuProfile.storage_strategy`), §7 (goods-in/put-away
  template); the inbound counterpart to outbound allocation (ADR 0002).

## Context

openWCS had no inbound storage logic: nothing decided **where** a received handling unit (HU)
goes, and there was no pick-face slotting or replenishment. Two distinct worlds must be served:

1. **Automated rack / goods-to-person (shuttle ASRS, crane ASRS, AutoStore, AMR-GTP).** A SKU is
   slotted to the **block as a whole** — a pool of locations across all aisles — not a fixed
   location; the system retrieves HUs to a GTP station to be picked. The engine chooses the actual
   storage location at put-away time. It must reconcile competing objectives: **velocity-to-exit**
   (fast movers near the aisle port), **same-SKU lane consolidation** in multi-deep lanes (avoid
   honeycombing/reshuffles), **aisle redundancy** (a SKU survives an aisle/crane outage and can be
   picked in parallel), and **aisle fill/throughput balancing**.
2. **Manual pick faces (pick-by-light / racking).** A SKU+UoM is slotted to a **specific location**
   with **min/max**, kept stocked by **replenishment** that is **opportunistic / top-off in off-peak
   windows**, with **direct-to-pick** (cross-dock) of inbound when a face has headroom.

These objectives genuinely conflict (consolidation wants one aisle; redundancy wants several;
balancing wants the emptiest), so the design needs a deliberate reconciliation, not ad-hoc rules.

## Decisions

1. **New `slotting` service** (port 8093, own `slotting` schema) owns put-away assignment,
   pick-face slotting config, replenishment, and re-slotting. It reads master-data (blocks,
   candidate locations) and inventory (pick-face on-hand) over REST — the inbound mirror of the
   `allocation` service.
2. **Block vs location granularity.** master-data gains a `storage_block` (type
   SHUTTLE_ASRS/CRANE_ASRS/AUTOSTORE/AMR_GTP/MANUAL_PICK/RESERVE_RACK) with
   `slotting_granularity` = BLOCK (automated pool) or LOCATION (fixed pick face), and a `gtp` flag.
   `location` gains `block_id`, `aisle`, `rack_level`, `lane_depth` (multi-deep capacity), and
   `distance_to_exit` (velocity-to-exit).
3. **One weighted scorer reconciles the conflicts.** A pure `PutawayScorer` applies the **hard
   constraints** (lane capacity, single-SKU-per-lane, **max-%-of-SKU-per-aisle cap**) then ranks
   survivors by `wV·velocity + wC·consolidation + wR·redundancy + wB·balance`. Weights are per-block
   (`block_policy`); the cap + a min-aisle floor are how redundancy is reconciled with
   consolidation. Tie-break favours filling a started same-SKU lane (least honeycombing).
4. **Put-away order:** direct-to-pick (cross-dock to a forward face with headroom) is tried first,
   else the SKU's block pool is scored.
5. **Replenishment** = min/max. Below-min raises EMERGENCY (empty) / SCHEDULED tasks; an off-peak
   cron tops every face up to max (OPPORTUNISTIC). Tasks dedup per face.
6. **Re-slotting** re-runs the same scorer over a block's current contents on the off-peak cron and
   recommends moves when a materially better location exists (gain beyond `reslot_shift_pct`).
7. **Velocity/ABC class is teach-in** (set on `storage_profile`); auto-computing it from pick
   history is a future enhancement.
8. **Process integration:** a goods-in BPMN `assignPutaway` delegate calls the engine and sets the
   target-location process variable for the downstream move.

## Consequences

- Occupancy is currently derived from the slotting service's own **active assignment ledger**
  (`putaway_assignment`); reconciling it against live inventory truth comes with physical move
  execution.
- **Fast-follows:** dispatching put-away/replenishment/re-slot moves as real device tasks via
  flow-orchestrator; txlog audit events (`PutawayAssigned` / `ReplenishmentPlanned` /
  `ReslotRecommended`); inventory-truth occupancy; auto-ABC from history; FEFO source selection for
  replenishment.
- Granularity matches reality: automated blocks slot at the pool level (random/dynamic storage),
  manual faces stay fixed so pickers/pick-by-light rely on stable locations.
