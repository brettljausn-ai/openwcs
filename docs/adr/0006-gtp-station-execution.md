# ADR 0006 — Goods-to-person (GTP) station execution service

Status: Accepted (2026-06)

## Context

openWCS needs **station execution logic** for goods-to-person (GTP) order fulfilment:
the act, at a manned workplace, of picking stock from a presented stock handling unit
(HU) and putting it into the correct order destinations, guided by lights. This is the
high-throughput core of any automated DC: an ASRS/AMR/conveyor brings goods *to* the
operator, who never walks.

### Research notes (industry anatomy)

A GTP workstation (a.k.a. pick station / port / put-wall station) has a small, fixed
ergonomic envelope and three functional zones:

- **Source / stock presentation** — one or more positions where a *stock HU* (tote, tray,
  bin, carton, pallet) holding stock of a single SKU is presented. Delivered by ASRS
  shuttle, AMR rack, or conveyor. Pick-to-light shows the operator how many to take.
- **Order destinations** — where picked stock is *put*. Two physical realisations, same
  logical shape:
  - **ORDER_LOCATION** (most non-AMR systems): order HUs sit in fixed positions, usually
    on a conveyor spur — "HU-in-location". The operator puts into the correct location(s)
    of the order HU(s). Often a two-sided **put-wall** of cubbies.
  - **PUT_WALL** (most AMR goods-to-rack systems): a rack/put-wall of cubbies, each with a
    **put-light** and a numeric display; the AMR-delivered (or fixed) rack holds order
    HUs. The lit cubbies show the qty to put.
- **Lights & confirm** — put-to-light: the operator scans/takes from the stock HU, the
  destinations needing that SKU illuminate with the qty, the operator puts and presses the
  light/button to confirm.

**Batch picking is the GTP efficiency**: one presented stock HU of SKU X typically serves
*many* order destinations in one visit (one source touch → many puts). The station's open
demand for SKU X across all its destinations is satisfied from the single stock HU before
it is sent away. Short-pick / exception handling: if the stock HU runs out before demand
is met, the remaining puts stay open for the next stock HU of that SKU (or are short-shipped).

References: Lightning Pick (put-to-light / put walls), Dematic / inVia / Toyota GTP &
AMR rack-to-person, Modern Materials Handling (light-directed), Brightpick / Ocado AMR.

## Decision

A new microservice `services/gtp` (port 8094, schema `gtp`) owns **station configuration**
and the **pick-and-put work cycle**. It mirrors the slotting/allocation service pattern
exactly (Java 21 / Spring Boot 3.3.2, UUID PKs, `Auditable`, JSONB via
`@JdbcTypeCode(SqlTypes.JSON)`, Flyway `V1`, `ddl-auto=validate`, Testcontainers).

### Model

- **`GtpStation`** — `(warehouse, code, mode)` where `mode ∈ {ORDER_LOCATION, PUT_WALL}`,
  plus status. The two modes share one execution shape; `mode` only documents the physical
  realisation and is echoed back on the put-list.
- **`StationNode`** — a position at the station, typed by `role`:
  - `STOCK` — a stock-HU presentation position (≥1 per station).
  - `ORDER` — an order destination (an order-HU node *or* a put-wall cubby). Carries an
    optional `putLightId` and the *current order HU* (`orderHuId`) bound to it.
- **`StationDestinationDemand`** — open demand pinned to an `ORDER` node: which `orderRef` /
  `orderLineId` / `skuId` and how many units still need to be put there
  (`requestedQty`, `puttedQty`). This is the per-destination work the station must clear.
- **`WorkCycle`** — created when a stock HU is *presented* against a STOCK node: captures
  the `stockHuId`, `skuId`, `presentedQty`, `remainingQty`, and the generated put-list.
- **`PutInstruction`** — one line of the put-list: `destinationNodeId`, resolved
  `orderRef`/`orderLineId`/`orderHuId`, `putLightId`, `qty`, and `status`
  (`OPEN | CONFIRMED | SHORT`). Confirming decrements the cycle's `remainingQty` and the
  destination demand's `puttedQty`; a destination demand fully putted is `COMPLETED`.

### Execution (the cycle)

1. **Configure** station + nodes; **open** order destinations by binding an order HU and
   posting its demand (skuId + qty) — REST seam; demand normally originates from
   allocation/order-management (referenced by UUID, no cross-schema FK).
2. **Present** a stock HU (SKU + qty) at the station → the service matches the SKU against
   all OPEN demand across the station's `ORDER` nodes and greedily allocates the available
   stock to them (most-needed first), emitting a **put-list** of `PutInstruction`s with the
   qty and the destination's put-light. One stock HU → many puts (batch).
3. **Confirm** a put (by instruction id, optionally a short qty) → decrement remaining stock
   + destination demand; mark instruction `CONFIRMED` (or `SHORT`), complete the demand when
   `puttedQty == requestedQty`.
4. **Query** station/cycle/destination state.

If the stock HU is exhausted while demand remains, the surplus demand simply stays OPEN for
the next presentation (short-pick handling); the cycle's put-list reflects only what the
presented qty could cover.

### Both modes, one engine

ORDER_LOCATION and PUT_WALL produce put instructions through the *same* matching code; the
only difference is what an `ORDER` node represents physically (a conveyor order-HU location
vs. a lit rack cubby). Tests assert both modes yield correct instructions.

## Operating modes (2026-06)

The station's `mode` (`ORDER_LOCATION` vs `PUT_WALL`) is the destination **topology** and is
unchanged. We add an **orthogonal operating mode** = *what the operator does when an HU is
presented*. A station **supports a set** of operating modes (`GtpStation.supportedModes`, a
comma-separated set, ≥ `PICKING`); each `WorkCycle` carries the one `operatingMode` it runs.

The five operating modes (`OperatingMode` enum):

- **PICKING** — the original present-stock → put-to-light flow, unchanged. Its cycle still produces
  `PutInstruction`s against open `DestinationDemand`; it is simply `operatingMode = PICKING`.
- **DECANTING** — present a source (stock) HU **and** an empty `targetHuId`; the cycle's task lines
  are *decant-moves* (move `qty` of a SKU into a target compartment). On confirm the moved qty is
  recorded; the filled target HU + its compartment SKUs are exposed for slotting **put-away**
  (`decantedTargetReady` — a seam, see below).
- **STOCK_COUNT** (cycle counting) — present an HU; each task line is a *count entry* (a SKU with an
  expected/system qty). On confirm the **counted qty** is recorded and **variance = counted −
  expected** is computed; non-zero variances surface as **StockAdjusted intents**
  (`stockCountAdjustment` — a seam).
- **QC** — present an HU; each task line is a *verdict slot* per HU/SKU. On confirm a verdict
  `PASS | FAIL | HOLD` (quarantine) is recorded.
- **MAINTENANCE** — request specific HUs/empty carriers; each task line is a *check slot*. On confirm
  a condition outcome `OK | DEFECTIVE | REPAIR` is recorded.

### Generalising the cycle

The put-list **generalises** to a set of mode-appropriate **task lines** (`TaskLine`, table
`task_line`): decant-moves, count entries, QC verdicts, maintenance checks — each with an
**outcome/confirmation** (`actualQty`/`variance` or `verdict`, `status ∈ OPEN | CONFIRMED |
CANCELLED`). PICKING keeps using `put_instruction` unchanged (no breaking change); the other modes
use `task_line`. The PICKING-specific `WorkCycle` columns (`stockHuId`, `skuId`, `presentedQty`,
`remainingQty`) become nullable for the modes that don't carry a single presented SKU+qty.

REST (additive, existing PICKING endpoints unchanged):

- `POST /api/gtp/stations/{id}/operating-modes {supportedModes[]}` — configure supported modes.
- `POST /api/gtp/stations/{id}/cycles {operatingMode, stockHuId?, targetHuId?, skuId?, qty?, lines[]}`
  — open a cycle in any mode and present its HU(s); PICKING delegates to `present`.
- `POST /api/gtp/tasks/{taskLineId}/outcome {actualQty?, verdict?}` — submit a per-line outcome.
- The existing `POST /stations/{id}/present`, `POST /puts/{id}/confirm`, `GET /cycles/{id}`,
  `POST /cycles/{id}/close` are unchanged (present is now a PICKING shorthand for `cycles`).

Migration `V2__operating_modes.sql` adds `gtp_station.supported_modes`,
`work_cycle.operating_mode` + `work_cycle.target_hu_id`, relaxes the PICKING-only NOT NULL/range
guards on `work_cycle`, and creates `task_line`.

### Mode seams (not hard-wired)

- **DECANTING → slotting put-away** — a confirmed decant cycle exposes the filled target HU + its
  compartment SKUs (`WorkCycleService.decantedTargetReady`) for a slotting put-away call; GTP does
  **not** invoke slotting itself.
- **STOCK_COUNT → inventory `StockAdjusted`** — non-zero count variances are exposed
  (`WorkCycleService.stockCountAdjustment`) as adjustment intents; GTP records them and does **not**
  adjust inventory directly.

### Seams (fast-follow, not built here)

- **Physical hardware** — actual put-lights, and retrieving stock/order HUs to the station
  via ASRS/AMR/conveyor — is an *adapter* concern (Go device adapters + flow-orchestrator).
  GTP only records `putLightId`/`stockHuId`/`orderHuId` and exposes the instructions; a
  fast-follow light adapter and a flow-orchestrator binding drive the real devices.
- **Demand origination** — demand is posted over REST; a follow-up wires it from allocation
  pick batches / order lines automatically.
- **Stock decrement** — confirmations are local to the cycle; a follow-up appends the moves
  to the transaction log (txlog) like the other services.
