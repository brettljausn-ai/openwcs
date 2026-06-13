# openWCS — As-Built Documentation

_Last updated: 2026-06-13 (logging convention applied to all services; slotting assignment lifecycle + single-aisle block starvation fix; inventory stock-follows-HU with bucket merge on collision; digital twin live conveyor state colours + smooth tote motion)_

What is **actually implemented** today (not the target architecture). Design intent:
[`build.md`](../build.md); decisions: [`docs/adr/`](./adr); live progress:
[`DEVELOPMENT-STATUS.md`](./DEVELOPMENT-STATUS.md).

> **Scope.** Six backend services are functional — **master-data**, **txlog**,
> **inventory**, **allocation**, **order-management**, and the **gateway**. Together they
> implement two vertical slices: (1) goods-in → stock (event log → projection), and
> (2) outbound order → release → pick-location allocation + cubing (+ batch picking). All
> other services and the device adapters are scaffolds (health endpoint only). The code is
> **not compiled in the authoring environment**; **GitHub Actions CI is the build/test gate**
> — see Testing & CI.

---

## 1. Services

| Service | Port | Status | What it does |
|---|---|---|---|
| gateway | 8080 | ✅ | Spring Cloud Gateway; routes `/api/<service>/**`; JWT validation (toggleable) + forwards `X-Auth-User`/`X-Auth-Roles`. |
| master-data | 8081 | ✅ | Catalog + outbound config (below). |
| inventory | 8082 | ✅ | Durable stock projection + availability/reservations (SKU- and location-scoped). **HU location registry** (`PUT /api/inventory/handling-units/{id}/location`): records a handling unit's current storage location; bookings made by flow-orchestrator at each transport milestone (REQUESTED, IN_TRANSIT, ARRIVED, STORED, RELOCATED) so the registry stays live; consumed by ADR-0009 dig-out occupancy checks. **Null bookings go to the warehouse's UNKNOWN operational location** (HU + riding stock rows; resolved from master-data, 503 if unreachable); stock at UNKNOWN is visible in the overview but excluded from availability/ATP and can never be reserved. **Stock follows the HU on every booking and buckets MERGE on collision**: rows of the same (sku, batch, status) that differ only by location become one summed row before the location update (the unique bucket key would otherwise kill the whole booking — observed live when a counting adjustment landed on a stale location). The stock projection books HU-bound events (GOODS_RECEIVED, PICKED, STOCK_ADJUSTED, STOCK_STATUS_CHANGED) to the HU's CURRENT location, not the location stamped in the event (count lines capture their location at task creation; the tote has long moved by the time a station count confirms); deliberate from→to moves keep their explicit locations. **Reporting aggregates**: `GET /api/inventory/reports/stock-by-sku?warehouseId=` (per-SKU split available = AVAILABLE stock minus active holds floored at 0 / allocated = HELD reservations / unavailable = non-AVAILABLE stock plus anything at the UNKNOWN location) and `GET /api/inventory/reports/storage-density?warehouseId=&days=90` (per storage block per day: occupied vs total cells + pct; total cells = the block's master-data locations, occupied = cells holding any stock row or HU). Snapshots (`storage_density_snapshot`, unique warehouse+block+day) are written by a daily ShedLock-guarded sweep (`StorageDensitySweeper`, 00:10 UTC, `inventory.shedlock`) and on demand when today is missing, so the report answers right after deploy. |
| order-management | 8084 | ✅ | Orders (all types) + lifecycle + release management + line transactions; delegates allocation. **Short allocate and release** (`POST /api/orders/{id}/release-short`, SUPERVISOR/ADMIN): a NOT_FULFILLABLE order picks the available qty and ships short (`PARTIALLY_ALLOCATED`, per-line `allocatedQty`/`SHORT`); ship stages an **OrderShipped** confirmation (ordered vs shipped per line). **Order-flow report** `GET /api/orders/reports/flow?warehouseId=&direction=INBOUND|OUTBOUND&days=90`: expected (received, no work yet: INBOUND = no stock posted; OUTBOUND = status CREATED), active (INBOUND = lines not fully received, incl. expected; OUTBOUND = RELEASED/PARTIALLY_ALLOCATED/ALLOCATED/NOT_FULFILLABLE/CUBING_FAILED), started (active subset with ≥1 posted transaction), plus zero-filled per-UTC-day buckets (received by `created_at`, started by first `posted_at`, completed: INBOUND = last receipt when every line fully posted, OUTBOUND = SHIPPED by `updated_at`, an approximation as no dispatch timestamp exists) and an hour-of-day intake histogram. Pure SQL aggregation, no new tables. |
| allocation | 8091 | ✅ | Pick-location allocation (UoM breakdown), cubing, batch picking. **Allow-short mode** (`allowShort` on `POST /api/allocation/orders`): a short order keeps what it could reserve, cubes only the allocated quantities, returns `FULFILLABLE_SHORT` (supervisor "short allocate and release"). |
| slotting | 8093 | ✅ | Put-away assignment for automated rack/GTP blocks (weighted scorer: velocity-to-exit · same-SKU lane consolidation · aisle redundancy · fill balance; the exit distance uses `location.distance_to_exit` when set, else a fallback derived from the cell coordinate — (posX−1)+(posY−1)+(posZ−1), port assumed at position 1, ground level — so generated racks without maintained distances still place fast movers near the port), manual pick-face slotting + min/max replenishment (opportunistic top-off), and off-peak re-slotting. ADR 0003. **Profile-less put-away fallback**: a SKU with no storage profile resolves to the warehouse's ONLY automated storage block (`SHUTTLE_ASRS`/`CRANE_ASRS`/`AUTOSTORE`/`AMR_GTP`, via `GET /api/master-data/storage-blocks?warehouseId=`); several automated blocks remain a 400 (a profile must disambiguate) — keeps flow's slotting-only return leg answerable for demo SKUs. **Relocation plan** (`POST /api/slotting/relocation-plan {warehouseId, locationId}`): for ADR-0009 multi-deep shuttle channels, returns an ordered `[{huId, fromLocationId, toLocationId}]` dig-out sequence for blockers in front of the target; same-`cellY` hard constraint (no lift move), same-aisle preferred; empty when the channel is clear.  **Assignment lifecycle**: a fresh putaway SUPERSEDEs the HU's open assignments (one live plan per tote); flow confirms the completed ASRS STORE via `POST /api/slotting/putaway/stored` → status STORED (open assignments are planned occupancy); the hard aisle-share cap is skipped in single-aisle blocks (no alternative aisle exists — it previously rejected every SKU's second putaway there). |
| gtp | 8094 | ✅ | Goods-to-person station execution: configure stations + STOCK/ORDER nodes, open order destinations (bind order HU + demand), present a stock HU → put-to-light put-list across destinations (one HU serves many orders: the batch), confirm puts (incl. short), complete destinations. ORDER_LOCATION (conveyor HU-in-location) and PUT_WALL (lit rack cubbies, typical AMR) destination topology share one engine. Orthogonal **operating modes** (PICKING / DECANTING / STOCK_COUNT / QC / MAINTENANCE): each cycle runs one and carries mode-appropriate task lines (decant-moves / count entries+variance / PASS-FAIL-HOLD verdicts / OK-DEFECTIVE-REPAIR checks); seams to slotting put-away (decant) and inventory StockAdjusted (count). **Station inbound queue** (`station_queue_entry`): **ADR-0007 Phase 3c-1: the inbound queue's source of truth has relocated to `flow-orchestrator`** (`induction_queue_entry`); gtp's `POST /stations/{id}/queue` inbound enqueue is deprecated and no longer called by counting; `POST /queue/{entryId}/complete` now fans out to flow `POST /api/flow/induction/entries/{id}/done` + gtp store-back. The legacy `station_queue_entry` table and enqueue code remain physically but are marked `@Deprecated` and unused for the inbound path. Previously: conveyor HUs arrived `IN_TRANSIT` (distance-timed) then `QUEUED`; ASRS/AMR/AutoStore arrived immediately `QUEUED`; operator completed entries FIFO. **Deactivate/drain control**: `POST /stations/{id}/deactivate` flips `acceptingWork=false` — station finishes its queued work but rejects new inbound HUs; `POST /stations/{id}/activate` reopens it. The `WorkplaceView` response includes `acceptingWork`, letting the console restore the drain state on reload. **In-transit capacity caps** (`maxInTransitPicking`, `maxInTransitOther`, configured via `POST /stations/{id}/capacity`): max simultaneous active inbound transports per mode class (default 4 PICKING / 2 OTHER); enqueue rejected with 409 when the cap is reached. **Topology node sync** (`POST /stations/{id}/nodes/sync`): replaces the station's STOCK/ORDER nodes from a topology-projected node set — nodes matched by code preserve their id + bound demand; the feeding conveyor distance (`inboundDistanceM`, `numeric(12,3)`) is carried from the topology function-point's `offsetM` and drives emulator queue arrival timing; called by flow-orchestrator on every topology projection (best-effort — a failure never aborts the routing projection). ADR 0006. |
| counting | 8095 | ✅ | Cycle / stock counting: count tasks (scope LOCATION/SKU/ZONE/BLOCK, BLIND vs VARIANCE), ABC-cadence schedule generator, capture counts → variance vs an inventory-expected snapshot → within-tolerance auto-approve (posts a `StockAdjusted` event) or out-of-tolerance recount; blind hides expected/variance. **Delete OPEN tasks** (`DELETE /tasks/{taskId}`): removes a count task and its lines while still OPEN; 409 once counting has begun. Seams: GTP STOCK_COUNT station + cycle-count BPMN (by id), adjustment via txlog. **ASRS count-tote routing** (emulator mode only): when a count task is created, the counting service looks up each cell's storage block (master-data), and for ASRS-family blocks (`SHUTTLE_ASRS`, `CRANE_ASRS`, `AUTOSTORE`, `AMR_GTP`) it issues a single `flow.requestPresentation(...)` call — flow creates a `REQUESTED` induction entry and orchestrates the RETRIEVE + CONVEY journey itself (dispatching to the adapter family that services the cell — `AUTOSTORE`, `AMR`, or `ASRS` — resolved from the storage type; not hardcoded). The cap is metered by flow at RETRIEVE dispatch; `REQUESTED` is uncapped so routing always succeeds at request time; best-effort (no-op when emulator is off or no active counting station found — the count task is always created). **At-station blind count** (`POST /tasks/{taskId}/lines/{lineId}/station-count {countedQty}`): operator submits their counted qty blind (never sees the system qty). A state machine on `count_line` (`station_count_state`: PENDING → RECOUNT → ACCEPTED | ADJUSTED) drives reconciliation: first count == expected → `ACCEPTED` (no adjustment); first count ≠ expected → `RECOUNT` (hold the count); on recount, matches expected → `ACCEPTED`; matches held count → confirmed variance: posts `StockAdjusted` (delta = counted − expected, reason `COUNTING`, actor = `X-Auth-User` header or `?operator=` query param, falls back to `"system"`) → `ADJUSTED`; differs from both → update hold, `RECOUNT` again. When all lines are terminal the task transitions to `RECONCILED`. Schema: `count_line` gains `station_last_count` + `station_count_state` (`V4__count_line_station_count.sql`) and `hu_id uuid` (`V5__count_line_hu.sql`) — the tote the cell's stock sits on, snapshotted from inventory at task generation and carried onto every `StockAdjusted` event so reconciled variances target the tote's bucket rather than minting a phantom HU-less bucket at the ASRS cell; null for bin stock (no HU at the cell). **Demo quick-seed** (demo-mode only): `POST /api/counting/demo/seed {warehouseId, count?:1}` builds sample count tasks over existing demo stock (cells sourced from the inventory stock overview), returns `{created}`, guarded to demo mode ON; drives the "Add count task" button (one task per click) shown on the stock-counting screen only when demo mode is on. |
| txlog | 8086 | ✅ | Append-only event log + transactional outbox + relay to `txlog.stream`. |
| iam | 8087 | ✅ | openWCS authorization model: users → roles → coded permissions; **per-user warehouse access** (allowed warehouses + default; the gateway enforces scope). (Keycloak does auth.) |
| flow-orchestrator | 8085 | 🟡 | Device-task lifecycle over the uniform device contract; routes to adapters by family (below). **Flow-owned induction queue** (ADR-0007 Phase 3c-1): `POST /api/flow/induction/requests` — counting requests presentation of an HU at a workplace; flow creates a `REQUESTED` entry and orchestrates the RETRIEVE + CONVEY journey itself (cap metered at RETRIEVE dispatch; `REQUESTED` backlog is uncapped). `GET /api/flow/induction/queue?workplaceId=` returns the full `{REQUESTED, IN_TRANSIT, QUEUED}` pipeline ordered by `arrival_seq`. `POST /api/flow/induction/entries/{id}/done` marks an entry DONE (idempotent). **Per-HU transport trace**: `GET /api/flow/hu-trace?huId=` returns the append-only `hu_transport_trace` timeline (REQUESTED → RETRIEVED → INDUCTED → [SCANNED …] → ARRIVED → QUEUED → DONE). **Phase 3d (ADR-0008):** on each CONVEY dispatch flow resolves entry/destination nodes via `TransportNodeResolver` and assigns a conveyor route plan (`assignRoute`); `decide()` appends `SCANNED` trace rows at every scan query. **Live-twin tote paths** (`GET /api/flow/twin/tote-paths?warehouseId=`, DEVICE_VIEW; `TwinPathService`): the "visu master" read model for the Hardware visualisation page — per in-transit HU, its actual traversed-node polyline in world XZ metres (node positions from `conveyor_node`, ordered by the running CONVEY leg's SCANNED trace, with the routed-to lead node) plus the server clock, so the 3D scene plays a backend-resolved path and never reconstructs motion by projecting scan positions onto drawn belts (which was ambiguous at diverts and flung totes to a conveyor's start). **Return-to-storage (slotting-only store-back):** workplace-done asks slotting for the destination — only slotting slots a tote, the source slot is never a fallback; slotting answered → routed return CONVEY + STORE into the slotting-chosen location; slotting errored → return CONVEY with no destination/route (tote stays on the conveyor, `awaiting_slot`), a ~30s ShedLock sweep retries and assigns the slot mid-journey. Every CONVEY books the HU to the entry conveyor's operational location; QUEUED books the workplace's; STORE books the final slot. **ADR-0009 dig-out chain:** `dispatchRetrieve` calls `POST /api/slotting/relocation-plan`; when blockers exist, a `RELOCATE` (or `BIN_RELOCATE`) goes out for the front-most step; callback books the blocker's new inventory location, writes a `RELOCATED` trace row, re-plans until clear — then RETRIEVE.**Reporting** (`GET /api/flow/reports/scan-quality|traffic|storage-movements|device-movements|transit-times?warehouseId&days`, DEVICE_VIEW, days default 90 / cap 180): daily `scan_stat` (scans/no-reads/unknowns per scan point) and `edge_traffic` (ROUTE answers per directed edge) counters bumped by atomic upserts, fed asynchronously by `decide()` (isolated; never break the scan path); storage movements per location/day and completed/failed device tasks per equipment/day aggregated from `device_task`; induct→arrival transit times (count + p50/p95 ms per day) from `hu_transport_trace`. **Hard-real-time routing fast path** (300 scans/s, ~10 ms answer budget): `decide()` routes over a per-warehouse in-memory graph snapshot (`RoutingGraphCache`: nodes + adjacency + loop config, immutable, ConcurrentHashMap) with reverse-Dijkstra next-hop tables memoised per target, so a warm scan does NO edge fetch and NO per-scan Dijkstra; synchronous DB work is one indexed route-plan read (deliberately NOT cached: plans are dynamic state shared across the stateless replicas) + one route-position UPDATE (kept sync: loop occupancy and plan progression depend on it) + the loop row lock/occupancy count only when a hop would enter a loop. Counters and `SCANNED` trace rows ride an in-process FIFO queue worked by one background thread (`ScanSideEffects`; bounded, failures WARN and drop, the answer is never blocked). Snapshot invalidation: topology PUT (editor replace) and the routing projection evict after commit, plus a defensive 60 s TTL that self-heals missed/foreign-replica writes. **Decision-latency metric**: `GET /api/flow/reports/decision-latency` (DEVICE_VIEW) serves {count, p50Ms, p95Ms, p99Ms, maxMs} over the last 4096 decisions of this instance (in-memory ring buffer, per replica); any single decision over 10 ms WARNs with its phase breakdown. |
| integration-host | 8092 | 🟡 | Canonical vendor-neutral **Host API** (`/api/host/**`): orders + ASNs in, confirmations (cursor feed) out. |
| integration-sap | 8089 | 🟡 | Host gateway (skeleton): `POST /labels` (per-shipper dispatch-label barcode), `POST /routes/sync` (→ master-data Route catalog), and `POST /orders` + `/asns` translating SAP messages into the canonical Host API. |
| integration-manhattan | 8090 | 🟡 | Host gateway (skeleton): `POST /orders` + `/asns` translating Manhattan Active messages into the canonical Host API. |
| process-engine | 8083 | 🟡 | Embedded **Flowable BPMN** engine: deploy process definitions, start/inspect instances; service-task delegates originate WCS work (dispatch device task, assign route, release order, **assign put-away**, **allocate order**). Sample processes: goods-in, goods-in-putaway, cycle-count, and a complete **outbound** process (release → allocate → gateway → pick/dispatch → route; the gateway passes `FULFILLABLE` and `FULFILLABLE_SHORT`, and an instance started with `allowShort=true` short-releases a NOT_FULFILLABLE order back onto the happy path). |
| notification | 8088 | 🟦 | Scaffold (health/info only). |
| equipment-emulator | 9097 | 🟡 | Go; simulates all four device families (conveyor CONVEY/DIVERT/MERGE/SCAN, ASRS STORE/RETRIEVE/RELOCATE, AMR TRANSPORT/MOVE, AutoStore BIN_STORE/BIN_RETRIEVE/BIN_RELOCATE) when `HARDWARE_EMULATOR_ENABLED` is ON; flow-orchestrator routes device tasks here instead of the real adapters. Per-family/command simulated latency (`OPENWCS_EMULATOR_LATENCY_MS` overrides all, `0` = instant). Deterministic fault injection (`OPENWCS_EMULATOR_FAULT_RATE=N`: 1 in every N tasks returns FAILED with `fault: true`). **Loop recirculation** (`OPENWCS_EMULATOR_RECIRC_EVERY=N`): every Nth CONVEY task recirculates the loop once before diverting — arrival order visibly diverges from dispatch order (ADR-0007 R2); the result payload reports `recirculations` and `decisions` (sorter `RECIRCULATED`/`DIVERTED` points) which flow writes to the HU transport trace (R4). **Live conveyor walk** (ADR-0008 Phase 3d-2, `walk.go`): a CONVEY with an `entryNode` payload runs in live-walk mode — fetches the routing topology from flow, then loops: POST a scan to `POST /api/flow/conveyor/route`, travel the replied edge at `0.5 m/s` (`speedMps`, live-tunable), HOLD → dwell and rescan, COMPLETE → callback; safety cap at 500 scans; `recircEvery` does not apply; falls back to atomic sleep when no `entryNode`. Real per-family completed/failed tallies on `GET /state`. Latency, fault rate, recirc rate, ASRS handover spacing, and conveyor speed tunable at runtime via `GET`/`POST /config` (`{latencyOverrideMs, faultEvery, recircEvery, asrsHandoverMs, speedMps}`). **ASRS shuttle/lift/handover serialisation** (`handover.go`): an ASRS (and AutoStore) moves ONE tote at a time, so consecutive tote-moving commands (STORE/RETRIEVE/RELOCATE, BIN_*) on the same device are serialised and spaced by `asrsHandoverMs` (default 1800 ms, env `OPENWCS_EMULATOR_ASRS_HANDOVER_MS`, live-tunable via `/config`) — totes leave storage staggered instead of two retrievals completing at once and entering the conveyor on the same spot (which rendered as a single tote on the twin). **Decision-grade daily logs**: every task line pairs the HU code (never a bare UUID when the payload carries one) with route, equipment, and the why (recirculation policy hit, fault injection hit, hold reason from flow, no-route rescans); `/config` changes log old -> new; degraded paths (rejections, lost callbacks) at WARNING with consequence. |
| adapters/conveyor | 9091 | 🟡 | Go; health/readiness + stub loop; `POST /tasks` returns FAILED ("hardware not connected") — real-hardware seam. Refusals logged at WARNING with HU code, route, equipment, reason (emulator flag OFF, no live PLC) and consequence. |
| adapters/{asrs,amr-geekplus,autostore} | 9096, 9093, 9094 | 🟦 | Go; health/readiness + stub loop; `POST /tasks` returns FAILED ("hardware not connected") — real-hardware seam. (asrs on 9096 — 9092 is Kafka's.) Refusals logged at WARNING with HU code, equipment, reason and consequence. |
| adapters/conveyor-sniffer | 9095 | 🟡 | Go; ingests scan telegrams from defined source IPs (allowlist + pluggable decoder) and posts observations to the WCS for topology learning. Logs stream connect/close with per-session tallies, each forwarded scan (barcode + node + controller), and WARNINGs with the raw line for undecodable telegrams / lost observations. |
| ui | 5173 dev / 443 prod | 🟡 | React/Vite SPA with **Keycloak login** (password grant via `openwcs-web`), a sidebar **app shell** + **dashboard**, and a **screen permission catalog** (`auth/screens.ts`) gating nav/routes by role, **overridable per role/user** via the Access control screen (`iam` `screen-access` store). Built screens: dashboard; **inbound orders**, **outbound orders**, **stock counting** (in-app confirm dialog for OPEN-task delete), **GTP operator console** (single-active-session; **queue-driven + auto-present**: polls the station queue and auto-presents the arrived head tote in PICKING mode — no manual form; **operating mode persisted per station** (localStorage, restored on reload); **per-mode accent hue** (counting orange, QC + maintenance red, picking/decanting green: mode pills + mode panel borders, via `modeAccent()` in `GtpOpsScreen.tsx` reusing the `--warning`/`--danger`/`--herbal-lime` tokens); **active-tote panel** (tote glyph + HU code hero line, SKU code wipth the SKU name on its own line, qty, SKU image, and a **metadata chip strip**: base-UoM item dimensions/weight + `sku_profile` metadata via the one-call `GET /api/master-data/skus/{id}/card?warehouseId=`, session-cached per SKU and never delaying the tote presentation; the same identity block renders in the blind-count `CountPanel`; **fills the viewport when no cycle is in progress** — larger image, centred layout; idle **waiting-for-totes** state also full-screen); STOCK_COUNT mode also queue-driven — when the queue entry carries `countTaskId`/`countLineId` a dedicated **`CountPanel`** takes the operator's blind counted quantity (never shows expected), calls `POST .../station-count`, clears on `RECOUNT` and advances on `ACCEPTED`/`ADJUSTED`; falls back to a "Done counting" button when the link is absent; queue surfaced as a right-side fold-out **drawer** — now shows `REQUESTED` (in-storage, not yet retrieved), `IN_TRANSIT`, and `QUEUED` entries from `GET /api/flow/induction/queue`, giving operators visibility of the full inbound pipeline including totes still in the ASRS), **transport overview** (HU code column; origin/destination/next-hop columns resolve UUIDs to human-readable codes — location codes via `useCatalog`, GTP station codes via `listWorkplaces`; unknown UUIDs fall back to a short id; next-hop falls back to destination for ASRS/AMR/AutoStore direct-delivery tasks; **click-to-trace dialog**: when the clicked task has an HU id (`payload.huId` / `correlationId`), shows the per-HU transport trace from `GET /api/flow/hu-trace?huId=` (REQUESTED → RETRIEVED → INDUCTED → ARRIVED → QUEUED → DONE timeline with point, decision and timestamps); falls back to the device-task correlation trace from `GET /api/flow/device-tasks?correlationId=` when no HU id is resolvable — nothing regresses; **scope filter** replaces the old single-status filter: "Open + finished today" (default working-set — active tasks plus anything completed today), "Open (active) only", "Completed", "Failed", "All recent" — applied client-side on a 500-task backend window), **stock transactions**; **hardware twin** (`/hardware-twin`, Operations: a read-only live 3D digital twin reusing the topology editor's scene — equipment coloured idle/running/faulted from the device-task feed, conveyor bodies **tinted directly with their live state colour** (green functional / orange jam or heavy traffic (stalled or dense totes or a HELD divert, hysteresis-held) / red fault; `hardwaretwin/conveyorState.ts` derives the state, the body material carries it via the editor mesh's `bodyTint` prop (ConveyorPath ignores `color` for real conveyors) — no translucent overlay; the floating orb stays only on non-conveyor equipment), totes moving along a **backend-resolved conveyor path** — the flow-orchestrator "visu master" endpoint `GET /api/flow/twin/tote-paths?warehouseId=` resolves each in-transit HU's ACTUAL traversed-node polyline (node positions from `conveyor_node`, the same world metres the scene renders) plus the running CONVEY leg's scan timestamps and the routed-to lead node, all keyed off the SCANNED transport trace it already writes; `TwinPathService`. The frontend renders by playing that path a few seconds behind the **server** clock (returned in the payload, so client/server skew cannot push sampling out of the buffer), connecting consecutive waypoints with straight segments — they are graph-adjacent, so the segment IS the belt section. This replaced the old client-side reconstruction that PROJECTED scan positions onto the nearest drawn belt to guess arc-length: that projection was ambiguous at diverts (two branches meet at one point) and flung totes to a conveyor's start, then snapped back — the recurring "divert jump". `hardwaretwin/motion.ts` still owns the buffered interpolation, gentle dead reckoning on underrun, ~0.5 s error blending, and teleports for genuine discontinuities (rack store/retrieve). Queued totes wait ON the station's inbound conveyor: the active/head tote sits at the editor's geometric link point (held there until the workplace releases it — a GTP tote dwells on the conveyor at the pick position, it does not move onto the workstation box), slot i sits i × 0.8 m upstream; two MOVING totes that share a belt run are spaced nose-to-tail by a per-frame anti-stack pass that pushes the trailing tote back along its OWN heading (never a belt re-projection, so it cannot snap to a crossing belt at a divert), fixing simultaneously-retrieved totes rendering as a single tote), stored totes shown at ASRS cell positions, live stats bar (in-transit / queued / throughput / recirculations / faults), level selector + labels toggle, click-through to a machine's recent device tasks or a tote's trace; polled like the GTP/transport screens over `GET /api/flow/device-tasks` (equipment activity + tote states), `GET /api/flow/twin/tote-paths` (the backend-resolved motion paths) and the saved automation topology); **automation topology** (React Flow + react-three-fiber; 2D plan editor: **divert direction picker** (L/S/R, min 2 outlets), double-click-to-draw corners, click-to-place ASRS IN/OUT ports, waypoint delete; **click-select workstation conveyor function-points in 3D**; **routing graph table** — generated graph as a read-mostly inspector tab (nodes + edges + costs); **route test mode** — toggle "Test route" in the toolbar, click start/target in the 3D scene → Dijkstra over the exact directed graph: renders a glowing path polyline + ghost tote at 0.5 m/s, or the reachable frontier when no path exists); **BPMN process designer** (bpmn-js), **slotting**; **master data** (SKU/UoM/barcode read-only — host-owned), **GTP workplace config**, **settings**; **user management** (Keycloak admin API), **access control**, **warehouse access** (per-user allowed warehouses + default), **system info** (version, health **and logs** of every service/adapter — gateway-aggregated via `GET /api/system/services`; per-service **daily log files** (14-day retention) written to a shared `openwcs-logs` volume by every service — Java via libs:common `logback-spring.xml`, Go adapters via a daily writer — and read back at `GET /api/system/services/{name}/logs?date=` with a day list at `…/log-days`; full-page log view at `/system-info/logs/:name` with filtering), **database console** (`/admin/database`, Administration → Database, ADMIN-only: schema → table tree from `GET /api/master-data/admin/db/schemas` (click a table → runs `select * … limit 100`, columns + types shown under the selected table), monospace SQL editor with Cmd/Ctrl+Enter, results in the shared `DataTable` with row count + `truncated` badge + execution time, errors surfaced from PostgreSQL, last query kept in localStorage; backend accepts a single read-only SELECT only — see §3). A global **top-bar warehouse switcher** (`warehouse/WarehouseContext`) auto-selects the user's default on login and scopes every warehouse-related screen — no UUID entry anywhere. A shared **`useCatalog`** hook fetches location, SKU, and equipment catalogs from master-data on mount and resolves ids to human-readable codes across the operations screens — counting (scope column + capture dialog), outbound pick detail, stock-transaction From/To, and transport equipment column all display codes (location code, SKU code + description, equipment code) rather than raw UUIDs; transport origin/destination/next-hop additionally resolve against GTP station codes (`listWorkplaces`). **Blind count** capture withholds the Expected-qty column until a task is reconciled (operators must not see expected quantities during a blind count). **Inbound/outbound are read-only** (the host system owns orders — received/released/fulfilled here, not created). Shared UI primitives: a styled `Select` (replaces every native `<select>`), a `DataTable` (client-side search/sort/pagination), and a Keycloak-backed user autocomplete (Access control allow-list; default-warehouse in the user dialog). Warehouse access searches/paginates users **server-side** (Keycloak) to scale. **Reporting** (its own sidebar section, five read-only screens at `/reporting/*`, default roles ADMIN/SUPERVISOR/VIEWER, each with a help entry: **Material flow** (scan quality per scan point/day as stacked bars, a regression-based "scanners needing attention" table with error rate + trend arrow + sparkline, a conveyor **traffic heatmap** tinting the reused 3D topology scene (`reporting/ReportScene3D.tsx`, lazy chunk) from `GET /api/flow/reports/traffic` attributed to the nearest placed conveyor, and daily transit p50/p95), **ASRS** (storage density in figures and % with 90-day history + dashed 14-day weekday-seasonal forecast (`reporting/forecast.ts`, pure), a rack-cell **storage-movement heatmap** reusing the twin's `deriveStoredTotes` cell mapping, movements per device as stacked bars + table), **Stock** (per-SKU available/allocated/unavailable from `GET /api/inventory/reports/stock-by-sku`, SKU codes via `useCatalog`), and **Inbound**/**Outbound** (expected/started/active chips, 90-day dailies, hour-of-day **day map** strip with peak highlighting, from `GET /api/orders/reports/flow`); charts via **recharts**; honest empty states everywhere (history accumulates from deployment day). Per-screen **in-app help** (`help/content.ts`, drawer via `HelpButton`) follows a task-first operational template on every screen: "What you do here" steps, "On the floor" worked examples with demo identifiers, and "If something goes wrong" if/then troubleshooting. In compose (`--profile apps`) built + served by nginx on host **:443 (HTTPS forced, 80→443)** (proxies `/api`→gateway, `/realms`+`/admin`→Keycloak). |

All Java services: Java 21 / Spring Boot 3.3.2, PostgreSQL 16 via Flyway + JPA/Hibernate 6
(`ddl-auto: validate` — migrations own the schema), UUID keys, JSONB via `@JdbcTypeCode`.

**Logging convention** (applied across all services): decisions and state changes log at INFO
with the business object, its human-readable code where the row at hand carries one (SKU code,
HU code, location code, order ref) next to the UUID, and the trigger ("because ...");
skipped/degraded/best-effort-failed paths log at WARN with reason and consequence; per-item
loop detail at DEBUG; ERROR only where a human must act. SLF4J parameterized messages, each
line readable in isolation in the per-service daily logs.

---

## 2. Data ownership (schemas)

Cross-service references are **UUID columns with no cross-schema foreign keys** (build.md
§5.3); each service's store is independently ownable. Batch/lot & serial placement:
[ADR 0001](./adr/0001-inventory-data-ownership.md). Outbound allocation & cubing:
[ADR 0002](./adr/0002-outbound-allocation-and-cubing.md).

| Schema | Owner | Tables |
|---|---|---|
| `master_data` | master-data | warehouse, attribute_schema, sku, sku_profile, dangerous_goods, unit_of_measure, barcode_type, barcode, handling_unit_type (+ compartments/storable-in-automation/conveyable), equipment, location (+ block/aisle/lane-depth/distance-to-exit + **cell coords side/pos_x/pos_y/pos_z**), **storage_block** (+ allowed_hu_types), **shipper**, **warehouse_fulfillment_config**, **shipping_service**, **route**, **label_template** |
| `transaction_log` | txlog | events (append-only; UPDATE/DELETE blocked by trigger), outbox |
| `inventory` | inventory | batch, serial_unit, stock, reservation, projection_offset, processed_event |
| `orders` | order-management | outbound_order (all order types), order_line, order_line_transaction, order_outbox |
| `allocation` | allocation | order_allocation, allocation_line, pick_batch |
| `slotting` | slotting | storage_profile, pick_slot, block_policy, putaway_assignment (+ sku_ids for multi-compartment), replenishment_task, reslot_recommendation, sku_velocity (+ velocity offset/processed-event for the auto-ABC EWMA) |
| `gtp` | gtp | gtp_station (+ supported_modes + accepting_work + max_in_transit_picking/other), station_node, destination_demand, work_cycle (+ operating_mode, target_hu_id), put_instruction, task_line, **station_queue_entry** |
| `counting` | counting | count_task, count_line, count_schedule |
| `iam` | iam | role, role_permission, app_user, user_role, screen_access (+ _role/_user), user_warehouse |
| `flow` | flow-orchestrator | device_task, conveyor_node, conveyor_edge, conveyor_loop, conveyor_controller, topology_observation, **warehouse_level**, **placed_equipment** (+ path/sections/category/**station_id**), **equipment_function_point**, **equipment_connection**, **induction_queue_entry**, **hu_transport_trace**, **scan_stat**, **edge_traffic** |
| `host_integration` | integration-host | idempotency_key, webhook_subscription |
| `ACT_*` (public) | process-engine | Flowable's own engine tables (it manages its schema; a documented exception to schema-per-service) |

---

## 3. master-data (catalog + outbound config)

Full CRUD REST (`/api/master-data`, see `contracts/openapi/master-data.yaml`):

- **Catalog**: warehouses, SKUs (+ per-warehouse `SkuProfile` overlays, UoMs, barcodes,
  dangerous-goods), attribute-schemas, barcode-types, handling-unit-types, locations,
  equipment; SKU search/paging; bulk SKU import; soft-archive on delete. **SKU card**
  (`GET /skus/{id}/card?warehouseId=`): one-call read of the SKU identity + base-UoM item
  dimensions/weight + the warehouse profile's metadata blob, for operator screens (GTP tote panel).
- **Operational locations** (`GET /locations/operational?warehouseId=&kind=EQUIPMENT|WORKPLACE|UNKNOWN&name=`):
  every conveyor and workplace automatically has a location carrying its name, and each warehouse
  has an `UNKNOWN` catch-all, so HUs are always booked to a real location. Resolved lazily
  (created on first use; idempotent under concurrency via the `(warehouse_id, code)` unique
  constraint with a retry-fetch on conflict). Mapping: EQUIPMENT → `CONVEYOR_SEGMENT`/`TRANSPORT`
  (code = equipment name), WORKPLACE → `STATION`/`STAGING` (code = workplace name), UNKNOWN →
  `FREE_SPACE`/`QUARANTINE` (code `UNKNOWN`, no name). All created ACTIVE and unblocked.
- **Host SKU sync** (`POST /skus/sync`, host-sync only): a list of SKUs each carrying their
  **UoM hierarchy and barcodes inline**, referenced by code (UoM `parentCode`, barcode `uomCode`,
  barcode type by name). This is an **upsert, not a full-catalog replace** — SKUs absent from the
  batch are left untouched (and so are *their* UoMs/barcodes). Within a synced SKU the host is
  authoritative over its nested data: that SKU's stored UoMs/barcodes **fully replace** to match the
  payload (omitted ones removed); UoMs are matched by `(sku, code)` so their ids (and any stock
  referencing them) survive a re-sync. Whole batch in one transaction. This is the engine behind the
  Host API's `POST /api/host/masterdata/skus`.
- **Outbound config** (ADR 0002): **shippers** (`/shippers` — boxes/totes/bags with
  dims, tare, max fill level, max weight, per warehouse) and
  **`WarehouseFulfillmentConfig`** (`/warehouses/{id}/fulfillment-config` — allowed pick
  types CASE/SPLIT_CASE/EACH, cubing mode APP/ONE_TO_ONE, default shipper, and batch
  config: `batchEnabled`, `batchMaxPieces`, `batchMaxOrders`, `pickToteShipperId`).
- SKU/UoM **dimensions & weight** (per packaging level) drive cubing.
- **Cubing config UI**: a **Settings → Cubing** tab edits the warehouse's cubing rules
  (cubing mode, allowed pick types, default shipper) and provides full **shipper CRUD**
  (add/edit/archive), showing each shipper's fill rate and usable volume. This is the first
  UI surface for the fulfillment-config and shipper endpoints.
- **Dispatch reference data**: the **shipping-service** catalog (`/shipping-services` —
  service levels like EXPRESS/STANDARD, by carrier) and the **route** catalog (`/routes` —
  regions/depots, with `hostRef`; routes are fed from a host system). Both are global,
  unique by `code`, soft-archived on delete, and looked up by code (`?code=`) by other
  services (e.g. order validation).
- **Label templates** (`/label-templates`): admin-designed dispatch labels — a sized canvas
  (mm, dpi) + an ordered list of elements (TEXT/ADDRESS/BARCODE/IMAGE, positioned in mm, with
  static `value` or a data-binding `key`). `POST /{id}/render` renders a template + field
  values to a print payload (**ZPL** by default, or a minimal **PDF**), returned base64.
  Template selection inputs: a **shipping-service** carries a `labelTemplateCode` and a
  warehouse a `defaultLabelTemplateCode` (effective template = order override → service →
  warehouse default, resolved at release).
- **System configuration** (`system_configuration` key/value table): global runtime flags.
  - **Demo mode** (`DEMO_MODE_ENABLED`, `/api/master-data/demo`): seeds/removes a sample catalog
    (`POST /demo/enable?warehouseId=`, `POST /demo/disable`, `GET /demo?warehouseId=`), ADMIN-gated,
    may only seed onto a fresh, host-free system. On enable the UI also calls
    `POST /api/inventory/demo/seed`, which registers stocked DEMO handling units **plus 50 empty
    HUs** (no stock) so the empty-HU flows (ASRS empty-HU management, GTP order totes) have totes
    to work with. The UI seeds into **`purpose=STORAGE` locations only** (`?purpose=STORAGE` on the
    locations fetch), so demo HUs land in ASRS slots — never on the operational locations (a
    conveyor segment, a station, the UNKNOWN catch-all) that the unfiltered list used to put
    DEMO-HU-000 / DEMO-HU-001 onto. **Disable = full reset** orchestrated by the UI in **two ordered phases**:
    Phase 1 tears down the transport PRODUCERS (`/api/flow|gtp|counting|orders/demo/clear?warehouseId=`),
    the only services that issue handling-unit location bookings as a tote is retrieved, conveyed and
    stored back, and only once those are torn down does Phase 2 wipe the registry and journal
    (`/api/inventory/demo/clear?warehouseId=` + the global `/api/txlog/demo/clear`). Then the master-data
    disable removes the WHOLE SKU catalog (UoMs/barcodes cascade), demo shippers and the demo HU type,
    all via bulk DELETE statements (never loads rows), unsetting cubing-config `default_shipper_id`
    references first (that FK used to 409 the disable). The phase order matters: firing inventory in
    parallel with flow/gtp let an in-flight transport callback re-book a handling unit's location at the
    moment the registry was cleared, so a tote parked at an operational location (conveyor segment or
    station) could appear to survive the reset; tearing the producers down first closes that race. The
    inventory clear itself is location-blind (warehouse-scoped bulk DELETE; `handling_unit` carries no
    inbound FK), so it never leaves operational-location HUs behind. Failed clears are surfaced to the
    admin, not swallowed. Warehouses, locations, blocks, topology and GTP/station config are kept.
  - **Stock rules** (`SINGLE_SKU_PER_COMPARTMENT_ENABLED`, **default ON**, `/api/master-data/stock-rules`
    get + `/single-sku-per-compartment/enable|disable` ADMIN-gated): one HU compartment holds exactly
    one SKU, so an HU never carries more distinct SKUs than its type has compartments. GTP decanting
    reads the flag and rejects cycles that would violate it (two SKUs into one compartment, or more
    distinct SKUs than the target HU type's compartment count — HU type via inventory `/handling-units/{id}`
    + master-data `/handling-unit-types/{id}`); admins can switch it off to allow mixing. Settings →
    Stock rules.
  - **Hardware emulator** (`HARDWARE_EMULATOR_ENABLED`, **default OFF**): a global flag, same
    key/value table, read/flipped via `GET /api/master-data/emulator` → `{enabled}`,
    `POST /api/master-data/emulator/enable` and `/disable` (ADMIN-gated on `X-Auth-Roles`). Polled
    by the `equipment-emulator` service; when ON, flow-orchestrator routes device tasks to it
    instead of the real adapters (see §7b). Lets the whole automation flow run end-to-end with no
    physical hardware; an admin flips it OFF once real adapters are configured.
- **Admin database console** (`/api/master-data/admin/db`, ADMIN-gated on `X-Auth-Roles`):
  read-only SQL access to the whole shared database (all service schemas live in the one
  PostgreSQL instance the master-data datasource reaches). `GET /schemas` lists non-system
  schemas with tables + column metadata (information_schema); `POST /query {sql, maxRows?}`
  executes exactly ONE `SELECT`/`WITH … SELECT` statement. Safety is layered: a validator masks
  literals/comments and rejects multi-statements and any data-modifying/DDL keyword (including
  CTE-smuggled writes) with a clear 400; the query then runs with autocommit off on a read-only
  connection after `SET TRANSACTION READ ONLY` (always rolled back) so smuggled writes fail at
  the database; `SET LOCAL statement_timeout` 10 s; row cap default 200 / max 1000 with a
  `truncated` flag. Values serialize as string/number/boolean/null (timestamps ISO). Full SQL is
  only logged at DEBUG; INFO carries user + duration + row count. UI: Administration → Database.

## 4. inventory (stock)

Durable `stock` (qty per warehouse × SKU × batch × location × HU × status), kept current
by consuming `txlog.stream` (idempotent via `processed_event`, cursor in
`projection_offset`). REST (`/api/inventory`): stock list, **availability / ATP**
(SKU-wide *and* location-scoped via `?locationId=`), reservation create/release/consume,
**per-location occupancy** (`POST /locations/occupied` → which of a set of locations physically
hold any stock row or handling unit; consumed by slotting put-away to skip occupied slots).
Reservations check ATP under a pessimistic lock so concurrent allocations can't over-commit.

**HU location bookings never write null**: `PUT /handling-units/{id}/location` with
`locationId: null` (the caller does not know the position) books the HU *and the stock rows
riding in it* to the warehouse's **UNKNOWN** operational location, resolved through a small
`MasterDataClient` against master-data's `GET /locations/operational` (base URL
`openwcs.inventory.master-data-base-url`, default `http://localhost:8081`; resolved id cached
per warehouse in-memory). `stock.location_id` stays NOT NULL, so the old null-booking 500 is
gone. If master-data is unreachable the booking is rejected with **503** (callers treat it as
best-effort). **Allocation guard**: stock at UNKNOWN contributes ZERO to availability/ATP and
can never be reserved (excluded in `InventoryService` availability + reservation paths, logged
at DEBUG), but stays fully visible in the stock overview so admins can see and resolve it.

## 5. txlog (system of record)

`POST /api/txlog/events` writes the immutable event + an outbox row in one tx; a scheduled
`OutboxRelay` (**ShedLock-guarded** — runs on one replica only when scaled out) publishes to
`txlog.stream` in order. Query/replay by stream or global position.

## 6. order-management (orders, release, line transactions)

`/api/orders` (see `contracts/openapi/order-management.yaml`). Orders carry an
**`orderType`** — INBOUND | OUTBOUND | COUNT | ADJUSTMENT — with lines. Outbound orders may
also carry a **`serviceCode`** (dispatch service level), **`routeCode`** (dispatch route,
host-fed), a **`shipTo`** address (JSONB), and an optional **`labelTemplateCode`** override —
the service/route/template codes validated at create time against the master-data catalogs
(unknown code → 400); order-management resolves them via a `MasterDataClient` (identity-forwarded,
like allocation). These are the **shared** dispatch-label fields; the per-shipper barcode is
**not** held on the order — shippers only exist after cubing, so each shipper's label barcode is
requested from the host system per shipper at that point (see §7, dispatch labels). At release,
order-management resolves the effective label template (order override → service → warehouse
default) and passes the dispatch context to allocation.

- **Lifecycle / release** (OUTBOUND): create → **release** (delegates to allocation →
  `ALLOCATED` / `NOT_FULFILLABLE` / `CUBING_FAILED`) → ship; cancel releases held reservations
  via allocation. A successful release copies the per-line allocation outcome onto the order
  lines (`allocatedQty` + line status `ALLOCATED`/`SHORT`). **`CUBING_FAILED`** (a SKU is
  larger than the biggest carton) carries a
  `statusDetail` reason for the UI and can be re-released after the carton/SKU master data is
  fixed.
  **Release management**: `GET /release-queue?warehouseId=` (priority desc, then dispatch
  time) and `POST /release-due?warehouseId=&withinMinutes=`.
- **Short allocate and release** (`POST /orders/{id}/release-short`, permission
  `ORDER_RELEASE` = SUPERVISOR/ADMIN): the explicit supervisor decision on a
  `NOT_FULFILLABLE` order to pick what is available and ship short. Re-runs allocation in
  **allow-short** mode; on `FULFILLABLE_SHORT` the order becomes **`PARTIALLY_ALLOCATED`**
  with per-line `allocatedQty` + `SHORT` statuses and a `statusDetail` naming the deciding
  user (also logged at INFO). Rejected (409) when the order is not `NOT_FULFILLABLE` or when
  nothing at all is available. **Ship** accepts `ALLOCATED` and `PARTIALLY_ALLOCATED` and
  stages an order-level **`OrderShipped`** outbox event (stream = orderRef; per-line
  `orderedQty`/`shippedQty`/`shortQty` + `shortShipped` flag) that the relay appends to the
  transaction log, so the host confirmation feed reports the short ship per line
  (`order_outbox.line_txn_id` is now nullable for order-level events; `V9`).
- **Line stock transactions** (every type): `POST /orders/{id}/lines/{lineNo}/transactions`
  records a receipt / pick / count / adjustment (type derived from `orderType`) and, in the
  **same local transaction**, writes an `order_outbox` row. A **ShedLock-guarded** scheduled
  relay then **appends the matching event** (`GoodsReceived` / `Picked` / `StockAdjusted`) to
  the transaction log (correlation = order, stream = line) — the lock ensures only one replica
  drains the outbox per tick — and records the `event_id` back on the line transaction. So the audit record + publish-intent commit atomically; the physical stock
  change is applied by the inventory projection. `postedQty` rolls up the signed quantities.
- **Audit:** `actor` (who) is **required** on every line transaction and on every logged
  event (`events.actor` is NOT NULL); until IAM/JWT is wired it is caller-asserted.
- The `outbound_order` table now holds all order types (legacy name retained; a rename is a
  documented follow-up).
- **Demo quick-seed** (demo-mode only): `POST /api/orders/demo/seed {warehouseId, type:"INBOUND"|"OUTBOUND", count?:10}`
  bulk-builds sample orders from the seeded DEMO catalog and returns `{created}`. Guarded so it only
  works while demo mode is ON (§3). Drives the "Add 10 Orders" buttons that appear on the inbound and
  outbound screens only when demo mode is on (the UI bulk-creates 10 records, then refreshes the list).

## 7. allocation (pick-location allocation + cubing + batching) — ADR 0002

`/api/allocation`:
- `POST /orders` — for each line, reserve against **PICK-purpose** locations
  (inventory location-scoped ATP) until met; compute the **pick-type UoM breakdown**
  (cases/eaches, gated by allowed pick types). If every line is reserved → **FULFILLABLE**
  with a pick plan + **cube plan**; else release all reservations → **NOT_FULFILLABLE**.
  With **`allowShort: true`** (the supervisor "short allocate and release" decision relayed
  by order-management) a short order instead **keeps** what it could reserve, marks short
  lines `SHORT` (shortfall = `requestedQty − allocatedQty`; zero-stock lines allocate
  nothing), cubes **only the allocated quantities** (fully-short lines are skipped), and
  returns **`FULFILLABLE_SHORT`** with a shortfall summary in `statusDetail` (`V5`). An order
  with nothing available on any line stays `NOT_FULFILLABLE` even in allow-short mode.
  Idempotency covers both reservation-holding statuses (`FULFILLABLE`/`FULFILLABLE_SHORT`);
  batch picking accepts both.
- **Cubing**: `APP` (greedy volume + weight across the warehouse's **active shipper sizes** —
  packs the **largest** carton that fits while a lot remains, then **downsizes** the final
  carton to the remainder; honours max fill level / max weight / tare) or `ONE_TO_ONE`
  (validate & record host-supplied shippers). The cube plan is a list of `ShipperAssignment`
  cartons on `order_allocation.shippers` (JSONB); each carton has a stable **`shipperUnitId`**
  (its identity within the order — the carton→order link is the owning `order_ref`) and its
  **contents carry the order `lineNo`**, so a line split across several cartons (and a carton
  holding several lines) is fully traceable. **Dispatch labels**: when the order supplies dispatch
  context, each carton gets a `DispatchLabel` — the resolved label template, the shared fields
  (ship-to name/address block, service, route, `carton seq/total`, orderRef), and a **barcode
  requested from the host system per shipper** (the barcode is only knowable once cubing has
  produced the cartons; via a `HostLabelClient` port → the **integration-sap** gateway when
  `openwcs.allocation.host-label-base-url` is set (compose), or a built-in simulator otherwise).
  If a SKU is larger than the **biggest** available
  carton the order cannot be cubed: no shippers are produced, any held reservations are
  released, and the plan is parked in **`CUBING_FAILED`** with a `statusDetail` reason (the
  offending line/SKU) for an operator to resolve in the UI.
- `POST /orders/{orderRef}/cancel` — release every held reservation for the order and
  mark the plan CANCELLED (kept for audit). order-management's cancel calls this.
- `POST /batches` — **batch (cluster) picking**: group eligible small orders
  (FULFILLABLE, pieces ≤ `batchMaxPieces`) into pick totes (≤ `batchMaxOrders`), merge
  their picks into one combined pick list, and record the per-order separation plan.

---

## 7a. IAM & edge security

- **IAM service** (`/api/iam`, `contracts/openapi/iam.yaml`): the openWCS authorization
  model — users → roles → **code-defined permissions**
  (`org.openwcs.common.security.Permission`). Flyway seeds ADMIN/SUPERVISOR/OPERATOR/VIEWER.
  Manage users/roles, assign roles, read a user's **effective permissions** (union across
  roles). Authentication itself is Keycloak's job; this layers RBAC on top (build.md §4.8).
- **Per-user warehouse access** (`/api/iam/warehouse-access`, table `iam.user_warehouse`): maps
  each user to the warehouses they may work in and **one default** (partial-unique index enforces
  at-most-one default per user). `GET /me` returns the signed-in user's allowed set + default (the
  UI's top-bar switcher auto-selects the default on login); `GET`/`PUT /{username}` list/replace a
  user's mapping and are **ADMIN-only, enforced server-side** on `X-Auth-Roles`. A network-only
  `/internal/warehouse-access/{username}` (off `/api/**`, so unreachable through nginx/the gateway's
  public routes) feeds the gateway's scope enforcement.
- **Gateway JWT + warehouse scope** (build.md §12): with `openwcs.security.enabled=true` the gateway
  validates the JWT against the Keycloak realm, requires auth on `/api/**`, forwards the identity
  downstream as `X-Auth-User`/`X-Auth-Roles`/`X-Auth-Warehouses`, and **always strips client-supplied**
  versions (anti-spoofing). For non-admins it resolves the user's allowed warehouses from IAM
  (short-TTL cache; fails open if IAM is unavailable) and **rejects with 403** any request naming a
  `warehouseId` (query param or `/warehouses/{id}` path) outside that set. Admins are never scoped.
  Writes that carry the warehouse only in a JSON **body** are guarded per-endpoint downstream:
  `AccessControl.warehouseAllowed(header, warehouseId)` (libs/common; null header = unscoped/admin →
  allowed) is enforced in the user-facing write controllers — order create; master-data
  location/storage-block/equipment create+update; slotting pick-slot/storage-profile create+update,
  block-policy upsert, put-away; counting schedule/task create — returning 403 on a mismatch. The **compose `--profile apps` demo enables it on the gateway** (validating
  by `jwk-set-uri` so tokens minted through the UI's nginx proxy verify regardless of public
  hostname); downstream services keep their per-service toggle off so internal calls are
  unaffected (the gateway is the trust boundary). It remains off by default for bare host-run dev.
- **Keycloak realm**: compose imports `platform/keycloak/openwcs-realm.json` — realm
  `openwcs` with roles ADMIN/SUPERVISOR/OPERATOR/VIEWER, the `openwcs-web` public client, and
  users (admin `admIn1!` with realm-management roles for UI user-management; supervisor/operator/
  viewer). The UI signs in via the password grant; README documents getting a token for the API.
- **Authenticated actor**: order-management records a stock transaction's `actor` from the
  gateway-forwarded `X-Auth-User` (the request-body actor is only a fallback). So once
  security is on, every stock change is attributed to the authenticated user.
- **Per-endpoint RBAC (all services)**: `libs/common` carries a pure role→permission catalog
  (`RoleCatalog`, mirroring the IAM seed) + `AccessControl`. Each service enforces a coded
  `Permission` against the forwarded `X-Auth-Roles`, gated by `openwcs.security.enabled`
  (no-op off, 403 on a missing permission when on):
  - order-management — per-endpoint via `AccessGuard` (`ORDER_CREATE`/`VIEW`/`RELEASE`/
    `CANCEL`/`SHIP`/`POST_TRANSACTION`).
  - master-data / inventory / allocation / txlog — an `RbacFilter` mapping method+path to a
    permission (master-data: VIEW/EDIT; inventory: INVENTORY_VIEW + ALLOCATION_RUN for
    reservations; allocation: ALLOCATION_RUN / BATCH_BUILD / ORDER_VIEW; txlog: TXLOG_VIEW on
    reads — append is left internal, see below).
- **Inter-service identity propagation**: allocation and order-management add a
  `RestClientCustomizer` that forwards `X-Auth-User`/`X-Auth-Roles` from the incoming request
  onto outbound calls, so a downstream service (e.g. allocation→inventory) authorizes against
  the **original user**. Background calls with no request context (the order outbox relay →
  txlog append) forward nothing — which is why **txlog append is not user-RBAC enforced**
  (it's internal infrastructure, authorized upstream at the action that produced the event).

## 7b. flow-orchestrator & the uniform device contract (Phase 2)

The flow-orchestrator dispatches **device tasks** to equipment adapters over the **uniform
internal device contract** (build.md §8). A task moves through REQUESTED → DISPATCHED →
COMPLETED/FAILED and is persisted in `flow.device_task` (warehouse, equipment **family**,
optional equipment id, command, JSONB payload, correlation id, status, detail, JSONB result,
actor).

- **API** (`/api/flow/device-tasks`, `contracts/openapi/flow-orchestrator.yaml`):
  `POST` dispatches a task (DEVICE_OPERATE); `GET /{id}` and `GET ?correlationId=` read tasks
  (DEVICE_VIEW). The actor is taken from the gateway-forwarded `X-Auth-User`. `RbacFilter`
  enforces DEVICE_VIEW on reads / DEVICE_OPERATE on writes, gated by `openwcs.security.enabled`.
- **Routing**: `HttpDeviceClient` resolves the adapter base URL by the task's **family** from
  `openwcs.flow.adapters` (e.g. `CONVEYOR → conveyor-adapter:9091`) and `POST`s `/tasks`.
  An unknown family → 422; an unreachable adapter is recorded as **FAILED** (the task is never
  lost) and surfaced as 502.
- **Transport**: synchronous HTTP for now (simulator-friendly); the production target is
  **asynchronous Kafka** (`device.tasks` / `device.results`, build.md §9). `DeviceClient` is
  the seam — swapping transports doesn't touch the lifecycle service.
- **Conveyor adapter** (`services/adapters/conveyor`, Go): `POST /tasks` simulates a move,
  accepting CONVEY/DIVERT/MERGE/SCAN (→ COMPLETED with a result payload) and rejecting unknown
  commands (→ FAILED).
- **Hardware emulator mode**: a dedicated `equipment-emulator` service (port 9097) simulates all
  four device families. When `HARDWARE_EMULATOR_ENABLED` is ON, flow-orchestrator routes device
  tasks to it instead of the real adapters; the service accepts CONVEY/DIVERT/MERGE/SCAN,
  STORE/RETRIEVE, TRANSPORT/MOVE, and BIN_STORE/BIN_RETRIEVE, maintaining **in-memory device
  state** — never opening a hardware connection. Each command sleeps a realistic per-family/command
  duration before responding (e.g. ASRS STORE/RETRIEVE ≈ 900 ms, AMR TRANSPORT ≈ 1.2 s, conveyor
  moves ≈ 400–600 ms); the result payload includes `durationMs`. `OPENWCS_EMULATOR_LATENCY_MS`
  overrides every command (`0` = instant; useful in tests and CI). **Fault injection**
  (`OPENWCS_EMULATOR_FAULT_RATE=N`): 1 in every N tasks is failed deterministically — the result
  carries `"fault": true` and counters increment the per-family `failed` tally. **Loop
  recirculation** (`OPENWCS_EMULATOR_RECIRC_EVERY=N`): every Nth CONVEY task recirculates the
  conveyor loop once before diverting to its destination, adding a loop's worth of travel time so
  **arrival order visibly diverges from dispatch order** (ADR-0007 R2). The result payload reports
  `recirculations` (count of missed-divert passes) and `decisions` (ordered list of sorter decision
  points: `RECIRCULATED` per missed pass + a final `DIVERTED`), which flow writes to the HU
  transport trace before the ARRIVED event (R4). **Live control** (`GET`/`POST /config`): latency
  override, fault rate, and recirc rate are atomics readable and writable at runtime
  (e.g. `curl -XPOST .../config -d '{"recircEvery":3}'`) — no restart required. `GET /config`
  returns `{latencyOverrideMs, faultEvery, recircEvery}`. **Real telemetry** (`GET /state`):
  per-family `completed`/`failed` tallies derived from actual task load (not synthetic); the
  snapshot also echoes the live config. When OFF (the default), flow routes to the real adapters,
  each of which returns FAILED ("hardware not connected") — the deliberate **seam** for future
  real-hardware protocol clients. Purpose: run the entire automation flow with zero physical
  hardware (evaluation, onboarding, CI).
- **RBAC catalog**: `DEVICE_VIEW`/`DEVICE_OPERATE` added to `Permission` + `RoleCatalog`
  (VIEWER sees, OPERATOR operates) and seeded in IAM (`iam/V2__device_permissions.sql`).

Not yet wired: a BPMN process (process-engine) that *originates* these tasks — today they are
driven directly via the API.

**Induction queue** (ADR-0007 Phase 3c-1): flow-orchestrator now owns the inbound
presentation queue for GTP workplaces. Schema: `induction_queue_entry`
(`V11__induction_queue_and_hu_trace.sql`) with status `REQUESTED → IN_TRANSIT → QUEUED → DONE`,
`arrival_seq` (monotonic per workplace, assigned at CONVEY-callback time for proper arrival
ordering), links to `retrieve_task_id` / `convey_task_id`, and counting linkage
(`count_task_id` / `count_line_id`). The per-HU append-only trace lives in `hu_transport_trace`.
Transitions are driven from `DeviceTaskService.completeFromCallback`: RETRIEVE COMPLETED →
`REQUESTED → IN_TRANSIT` + dispatches CONVEY; CONVEY COMPLETED (= arrival) →
`IN_TRANSIT → QUEUED` + assigns `arrival_seq`. **Decision-point trace** (Phase 3c-2): when the
emulator reports conveyor decision points in the CONVEY result (`decisions`), flow writes them to
the HU trace (sorter `RECIRCULATED` / `DIVERTED` events) **before** the ARRIVED event — so the
timeline explains why arrival diverged from request order (ADR-0007 R4). Cap (`{IN_TRANSIT, QUEUED}`
≤ workplace cap) is metered at RETRIEVE dispatch via a `WorkplaceClient` reading GTP station caps;
`REQUESTED` backlog is uncapped — requests always succeed. New controllers:
`InductionQueueController` (`/api/flow/induction/**`) and `HuTraceController`
(`/api/flow/hu-trace`).

**Phase 3d — live scan-driven conveyance** (ADR-0008): on each CONVEY dispatch (induction-outbound
and the return-to-storage leg) `TransportNodeResolver` maps the workplace/storage endpoints to
routing-graph nodes, then `assignRoute(huCode, [destinationNode])` assigns a conveyor route plan and
puts `entryNode` in the task payload. When the emulator live-walks, flow's `decide()` is called at
every node scan; it appends a **`SCANNED`** `hu_transport_trace` row (point = node code, decision =
routed-to / held / completed) so the HU trace is a live material-flow timeline. The return-to-storage
CONVEY is resolved the same way — entry = station's discharge node, destination = storage location's
nearest graph node. If neither endpoint resolves (no projected graph) the task falls back to the
atomic-sleep behaviour — nothing breaks on an un-projected warehouse.

**Slotting-only store-back** (return leg): on workplace DONE, flow asks slotting
(`POST /api/slotting/putaway`) where the tote goes — **only slotting is allowed to slot a tote;
the source slot is never a fallback**. Slotting answered → `storage_location_id` is stamped on
the induction entry, the return CONVEY is routed to the storage entry and arrival dispatches the
STORE into the slotting-chosen location. Slotting errored / no answer → the return CONVEY still
goes out (the tote must leave the workplace) but with **no destination and no route plan**: the
tote stays on the conveyor (recirculating), the entry is flagged `awaiting_slot`
(`V15__induction_slotting_storeback.sql`) and a ShedLock-guarded sweep (`InductionSlotSweeper`,
~30s, `flow.shedlock` via `V16__shedlock.sql`) retries slotting; on an answer the destination +
route plan are assigned mid-journey (routing is per-scan, the tote adapts at its next scan; a
`SLOT_ASSIGNED` trace row is written) — or the STORE fires directly when the plan-less CONVEY
already completed. **HU location bookings**: every CONVEY dispatch books the HU to the entry
conveyor's *operational location* (master-data `GET /api/master-data/locations/operational?kind=EQUIPMENT&name=<conveyor>`,
conveyor name = the entry node code before `#`); QUEUED books the workplace's operational
location (`kind=WORKPLACE`, name = gtp station code); the STORE completion books the final slot.
Unresolvable → book `null` (inventory maps it to UNKNOWN). Bookings stay best-effort (WARN).

**ADR-0009 dig-out chain**: `dispatchRetrieve` first calls `POST /api/slotting/relocation-plan
{warehouseId, locationId}`. When the plan is non-empty, a **`RELOCATE`** device task (family ASRS;
`BIN_RELOCATE` for AutoStore) is dispatched for the front-most step, and `relocate_task_id` is
stamped on the induction entry. On the RELOCATE callback, flow books the blocker's new inventory
location (`PUT /api/inventory/handling-units/{id}/location`), writes a **`RELOCATED`** HU-trace row
(point `slot:<to>`, decision `"relocated out of channel for <target>"`) for the blocker, then
re-plans: next blocker → next RELOCATE; channel clear → the original RETRIEVE goes out. A failed
RELOCATE leaves the entry `REQUESTED` (retryable), mirroring failed-retrieve semantics.

**Conveyor routing** (vendor-neutral; `/api/flow/conveyor`): the topology is a directed graph —
**nodes** (scan/decision points, each with a `hardwareAddress` and layout `posX/posY` for the
admin schematic editor) and **edges** (segments labelled with the `exitCode` the hardware applies
to traverse them, plus a routing `cost`). A handling unit carries a **route plan** — an ordered
list of target node codes (`POST /conveyor/routes`). On a scan (`POST /conveyor/routing-requests
{node, barcode}`), the WCS finds the HU's current target, computes the **next hop** toward it by
shortest path (`RoutingEngine`, Dijkstra — recomputed per scan, so topology changes reroute
automatically), and replies `{action: ROUTE, exitCode, toNode}`; as each target is reached the
plan advances, ending in `COMPLETE`. Unknown node → `EXCEPTION`. **Divert defaults** (V14):
routing is asked fresh at every divert, so it adapts to the physical situation: when an HU has
**no route plan** (or its plan has **no path** from the scanned node), it follows the node's
`default_exit_code` (the divert's topology-configured default direction) when present; with no
default it continues along a **single** out-edge (a plain conveyor segment never strands a tote)
or `HOLD`s at a multi-exit divert (the tote stops and is re-evaluated on the next scan; a plan
assigned mid-journey takes over immediately). A planned HU whose path exists is never affected:
the plan always wins over the default. Unrouted barcode at a dead end → `NO_ROUTE`; a planned HU
with no path and no fallback → `EXCEPTION`. The whole graph is loaded/saved via
`GET`/`PUT /conveyor/topology` for the admin editor. **Loop capacity**: a node can belong to a named loop with a max HU count; when a
scan would route an HU into a loop that is at capacity, the WCS either `HOLD`s it (wait upstream,
re-evaluated next scan) or diverts it to the loop's `OVERFLOW` target — configurable per loop.
Occupancy is the count of active routes whose last-scanned node is in that loop. The
check-and-enter step uses a **pessimistic row lock** (`lockByWarehouseIdAndCode`) so the
occupancy count and the decision to enter are atomic across replicas — the capacity limit
cannot be exceeded by a check-then-act race when flow-orchestrator runs scaled out. An **admin
schematic editor** (the `ui` app, React Flow) loads/saves the whole graph — drag nodes, draw
edges, set per-node hardware address, and define loops. (The routing graph is now usually
**generated from the automation-topology layout** — see §7f — rather than drawn by hand.)
**Topology learning**: a sniffer posts
observed scans (`POST /conveyor/observations {node, barcode, sourceIp}`); the WCS infers a
candidate topology — nodes seen, segments (consecutive scans of the same HU), and likely targets
(terminal nodes) — flagged against the configured graph (`GET /conveyor/discovery`), which the
editor's **Discover** button pulls onto the canvas for an admin to confirm. The capture front-end
is the **conveyor-sniffer** adapter (Go): it ingests scan telegrams from the defined source IPs
(allowlist + a pluggable per-vendor decoder) and posts them as observations. Today it ingests a
controller telegram stream over TCP; a passive libpcap mirror-port tap is a drop-in source later.

## 7c. Host API (integration-host)

The canonical, **vendor-neutral** integration surface (`/api/host`, see
`contracts/openapi/host-api.yaml`). A host (WMS/ERP) integrates against this one contract; the
vendor adapters translate their native protocols into it — **both integration-sap and
integration-manhattan** do this (`POST .../orders`,`/asns` reshape the vendor message and
resolve materials/items to SKUs, then call `/api/host/orders`,`/asns`).

- `POST /api/host/orders` — outbound order (ship-to, service, route, label template, lines) →
  translated to an order-management OUTBOUND order.
- `POST /api/host/asns` — ASN / expected receipt → order-management INBOUND order.
- `POST /api/host/masterdata/skus` — upsert a **list** of SKUs into master-data by code, each
  carrying its **unit-of-measure hierarchy and barcodes inline** (host-driven reference-data sync).
  Intra-SKU references are by code (a UoM names its parent by `parentCode`, a barcode names its
  packaging level by `uomCode`; the barcode type by name). The host is authoritative: the nested
  UoM/barcode lists **fully replace** what is stored for the SKU (master-data `/skus/sync`,
  reconciled in one transaction; UoMs are matched by `(sku, code)` so their ids survive a re-sync).
  Returns a per-SKU created/updated report.
- `POST /api/host/inventory/adjustments` — a signed stock adjustment → appended to the txlog as
  a **StockAdjusted** event (the inventory projection applies the delta).
- `GET /api/host/confirmations?cursor=` — pull confirmations (receipts, picks, shipments, stock
  changes) as a **cursor feed over the transaction log** (`txlog` global replay): returns the
  events after the cursor plus `nextCursor`. No host endpoint required; the host controls the
  pace.
- **Webhook (push)** (`/api/host/webhooks`): a host registers a callback URL; a scheduled
  dispatcher streams confirmations to it, advancing the subscription's cursor only past
  successfully-delivered (2xx) events — at-least-once, with a failing endpoint retried from its
  cursor on the next pass. Enabled by `openwcs.host.webhook.enabled` (on in compose; off in
  dev/test, where the pull feed is used).
- **Idempotency**: any host POST may send an `Idempotency-Key` header; a repeat of the same key
  replays the stored 2xx response instead of re-processing (an `IdempotencyFilter` over a small
  `host_integration` store), so a host's retry never double-creates an order/ASN/adjustment.

Mostly a translation layer over order-management + master-data + txlog; its only state is the
small `host_integration` schema (idempotency keys + webhook subscriptions/cursors).

## 7d. Process engine (Flowable BPMN)

An embedded **Flowable** BPMN engine (build.md §7) runs admin-designed processes (`/api/process`,
see `contracts/openapi/process-engine.yaml`). The engine manages its own `ACT_*` tables on the
shared datasource (`database-schema-update`; async executor off — steps run inline for now;
audit history).

- `GET/POST /process/definitions` — list / deploy BPMN 2.0 definitions (raw XML); processes on
  the classpath under `processes/*.bpmn20.xml` are auto-deployed at startup.
- `POST /process/instances` — start an instance (`processKey`, optional `businessKey` + variables);
  `GET /process/instances/{id}` — running or historic status.
- **Service tasks originate WCS work** via Spring-bean delegates referenced as
  `flowable:delegateExpression="${...}"`: `dispatchDeviceTask` (→ flow-orchestrator device task),
  `assignRoute` (→ flow-orchestrator conveyor route plan), `releaseOrder` (→ order-management
  release/allocate). So a BPMN process can drive equipment and orders.
- **User/wait tasks**: `GET /process/tasks?processInstanceId=|assignee=` lists them;
  `POST /process/tasks/{id}/complete` completes one (with optional variables).
- **Sample processes** (auto-deployed): `goods-in` (start → dispatch device task → end);
  `outbound` (release order → **user task** confirm pick → dispatch move → end); `cycle-count`
  (operator **user task** to count a location).

This closes the Phase 2 gap where device tasks/routes were driven only directly via the API:
a process now originates them, including operator wait-steps. A process **designer UI** is the
remaining follow-up.

## 7e. gtp (goods-to-person station execution) — ADR 0006

A GTP **station** (`gtp_station`) has a `mode` — `ORDER_LOCATION` (order HUs in fixed/conveyor
locations) or `PUT_WALL` (a rack of lit cubbies, typical for AMR goods-to-rack) — and a set of
**nodes** (`station_node`): `STOCK` (≥1, where a stock HU is presented) and `ORDER` (the order
destinations, each with an optional `put_light_id` and a currently bound order HU). The two modes
share one execution engine; `mode` only documents the physical realisation of an ORDER node.

**Open demand** (`destination_demand`) is posted against an ORDER node (an order/line, a SKU, and
the qty to put there) — the REST seam where allocation/order-management feed work in (by UUID).

The **pick-and-put work cycle** (`work_cycle` + `put_instruction`):

- `POST /api/gtp/stations/{id}/present {stockHuId, skuId, qty}` — matches the SKU against open
  demand across the station's ORDER nodes and **greedily fills most-needed-first**, emitting a
  **put-list** (`put_instruction`: destination node + resolved order/HU + put-light + qty). One
  stock HU serves many destinations — the goods-to-person **batch**.
- `POST /api/gtp/puts/{id}/confirm {qty?}` — decrements the cycle's remaining stock and the
  destination demand's putted qty; a smaller qty is a **short put** (instruction → `SHORT`, the
  destination's remaining demand stays `OPEN` for a later cycle). A fully-putted destination →
  `COMPLETED`; a cycle with no `OPEN` puts left → `COMPLETED`.
- `GET /api/gtp/cycles/{id}` / `POST /api/gtp/cycles/{id}/close` / `GET /api/gtp/stations/{id}/demand`.

**Short-pick / exception handling:** if the presented qty can't cover all demand, the surplus
demand simply stays OPEN for the next stock HU of that SKU.

### Operating modes

Orthogonal to the destination topology (`mode`), a station **supports a set of operating modes**
(`gtp_station.supported_modes`, ≥ `PICKING`) = what the operator does with a presented HU. Each
`work_cycle` carries the one `operating_mode` it runs, and (for non-PICKING modes) a set of
mode-appropriate **task lines** (`task_line`) — the put-list generalised — each with an
outcome/confirmation.

- **PICKING** — the put-to-light flow above, unchanged (`put_instruction`).
- **DECANTING** — present a source HU + an empty `target_hu_id`; task lines are *decant-moves*
  (SKU + qty into a target compartment). On confirm the moved qty is recorded; the filled target +
  its SKUs are exposed for slotting **put-away** (seam).
- **STOCK_COUNT** — present an HU; *count entries* (SKU + expected qty). On confirm the counted qty
  is recorded and **variance = counted − expected**; non-zero variances surface as **StockAdjusted**
  intents (seam to inventory).
- **QC** — present an HU; *verdict slots* per HU/SKU recording `PASS | FAIL | HOLD`.
- **MAINTENANCE** — request HUs/empty carriers; *check slots* recording `OK | DEFECTIVE | REPAIR`.

REST (additive; PICKING endpoints unchanged): `POST /stations/{id}/operating-modes` (configure
supported modes), `POST /stations/{id}/cycles {operatingMode, …, lines[]}` (open a cycle in any
mode — PICKING delegates to `present`), `POST /tasks/{taskLineId}/outcome {actualQty?, verdict?}`
(submit a per-line outcome). Migration `V2__operating_modes.sql`.

**Mode seams (not hard-wired):** DECANTING exposes the filled target HU + its compartment SKUs for
a slotting put-away call (`decantedTargetReady`); STOCK_COUNT exposes non-zero count variances as
`StockAdjusted` intents (`stockCountAdjustment`). GTP records both; it does not call slotting or
adjust inventory itself.

### Station inbound queue, drain control & capacity

> **ADR-0007 Phase 3c-1:** the inbound queue's source of truth has relocated to
> `flow-orchestrator`. Counting now calls `POST /api/flow/induction/requests` (one call; flow
> orchestrates RETRIEVE + CONVEY). GTP's `POST /stations/{id}/queue` inbound enqueue is
> **deprecated** and no longer used by counting; `POST /queue/{entryId}/complete` fans out to
> flow `done` — and nothing else: **flow owns the return-to-storage leg, where only slotting
> decides the destination** (gtp's old parallel store-back was removed — it double-dispatched
> against flow's return leg). The legacy `station_queue_entry` table and enqueue code are kept
> physically but marked `@Deprecated`. See §7b (flow-orchestrator induction queue) for the
> authoritative description.

The **station inbound queue** used to be owned entirely by gtp (`station_queue_entry`).
The endpoints below remain present:

- `POST /api/gtp/stations/{id}/queue` — **deprecated** (inbound enqueue no longer called by counting).
- `POST /api/gtp/stations/{id}/nodes/sync {nodes[{role,code,locationId,putLightId,inboundDistanceM}]}` —
  replace the station's STOCK/ORDER nodes from a topology-projected node set. Nodes are matched by `code`
  so an existing node keeps its id and any bound order HU / demand; nodes no longer in the topology are
  removed only when they carry no open demand. The feeding conveyor distance (`inboundDistanceM`) is stored
  on the node (`station_node.inbound_distance_m`, `V7__station_node_distance.sql`). Called by flow-orchestrator's routing projection (best-effort).
- `GET /api/gtp/stations/{id}/queue` — the station's legacy queue (gtp-table backed; now superseded by `GET /api/flow/induction/queue?workplaceId=`).
- `POST /api/gtp/queue/{entryId}/complete` — operator completion; **fans out to `POST /api/flow/induction/entries/{id}/done` (flow marks the entry DONE) and dispatches NO transport of its own** — flow's return leg owns the store-back and only slotting picks the destination.

**Deactivate / drain control** (`acceptingWork` flag, `V5__station_in_transit_caps.sql`):
- `POST /api/gtp/stations/{id}/deactivate` — sets `acceptingWork=false`: the station finishes its
  already-queued work but rejects new inbound HUs (409). Useful for a clean switchover.
- `POST /api/gtp/stations/{id}/activate` — restores `acceptingWork=true`.
- `WorkplaceView` (the session + workplace API responses) now includes `acceptingWork` so the operator console can seed the drain-toggle state on page reload without an extra request.

**In-transit capacity caps** (`maxInTransitPicking`, `maxInTransitOther`, defaults 4 / 2):
- `POST /api/gtp/stations/{id}/capacity {maxInTransitPicking, maxInTransitOther}` — replaces the
  station's caps. Controls how many HUs may have an active inbound transport at once, split by mode
  class (PICKING vs all others). Schema: `V5__station_in_transit_caps.sql`; queue entries: `V6__station_queue.sql`.

**Count-tote routing (counting service, ADR-0007 Phase 3c-1):** when the hardware emulator is ON and a count task
is created for cells in an automated storage block (`SHUTTLE_ASRS`, `CRANE_ASRS`, `AUTOSTORE`,
`AMR_GTP`), the counting service issues a single `flow.requestPresentation(...)` call — flow
creates a `REQUESTED` induction entry and orchestrates the RETRIEVE + CONVEY journey itself
(dispatching to the adapter family that services the cell: `ASRS`, `AUTOSTORE`, or `AMR`). No
separate `gtp.enqueue()` call is made; the cap is metered by flow at RETRIEVE dispatch time.
**Store-back ownership:** gtp's parallel best-effort store-back (and its `SlottingClient`) was
**removed** — it raced flow's return leg (two STOREs for one completion, observed live). The
return decision now lives entirely in flow-orchestrator's return leg, where ONLY slotting picks
the destination and a slotting failure leaves the tote circulating on the conveyor (see §7b).

**Seams (fast-follow, not built):** physical put-lights and retrieving stock/order HUs for picking
(ASRS/AMR/conveyor device tasks) are still flow-orchestrator concerns. Demand origination
(auto-wire from allocation batches) and stock decrement → txlog are follow-ups.

Per-endpoint RBAC (`RbacFilter`, gated by `openwcs.security.enabled`): reads → `ORDER_VIEW`,
present/confirm → `DEVICE_OPERATE` (OPERATOR role). Contract: `contracts/openapi/gtp.yaml`.

---

## 7f. Automation topology — placement model + routing projection (flow-orchestrator)

A second model in flow-orchestrator describes **where equipment physically sits** and drives an
admin **3D/2D layout editor** (the `ui` app, react-three-fiber + an SVG plan view). It is the
authoring source the conveyor routing graph (§7b) is now generated from.

- **Model** (`flow` schema, V6/V10): `warehouse_level` (floors with elevation), `placed_equipment`
  (a placed master-data equipment instance — position/rotation/tilt + envelope `lengthM/widthM/heightM`,
  a conveyor `path` polyline + directed `sections`, `closed` flag, `category`, and a soft
  `station_id` linking a placed **GTP workstation** to its `gtp_station`), `equipment_function_point`
  (named points on a conveyor — scan/divert/induct/discharge/infeed — at an arc-length `offsetM`,
  with a `side`, an optional PLC `nodeCode` and, for diverts, an optional **default direction**
  `default_exit` (STRAIGHT = continue the main line / BRANCH = take the divert's branch / null =
  an unrouted tote stops at the divert; set via the function-point dialog in the editor)), and
  `equipment_connection` (now also **node-anchored**, V18: optional `from_path_index`/`to_path_index`
  reference the exact path point on either equipment, the editor's explicit node-to-node links;
  a connection LINKS the two systems and projects the touchpoint in both directions, the direction
  of travel always comes from each conveyor's own section edges).
  Load/save the whole graph
  via `GET`/`PUT /api/flow/automation/topology?warehouseId=` (`AutomationTopologyDtos`).
- **Editor** (`ui/src/topology/`): a 3D view (`AutomationTopology3D`) and a top-down **2D plan**
  (`PlanEditor2D`) share one model — an edit in either shows in the other. Place equipment from the
  library; draw conveyor sections; drop **function points** (click a conveyor to place one *anywhere*
  along a run, or drag a palette marker onto it — named points show their name on the plan); divert/
  infeed points materialise a junction + a 1 m branch stub with a **divert direction picker** (L/S/R,
  min 2 outlets); ASRS IN/OUT ports snap to the rack footprint as owned conveyor stubs (placed by
  clicking the desired conveyor in the 3D view); **double-click-to-draw corners** in the 2D plan;
  **waypoint delete**. The 2D grid defaults to **1 m**. Place a GTP workplace as a connectable
  **"workstation"** box and link it to specific conveyor function points with a **role** (STOCK / ORDER /
  DECANT) in the Properties panel ("Conveyor interactions"); the interaction's function point can be
  **selected by clicking it in the 3D scene**. **Node-link visibility** (`nodeLinks.ts`, a pure
  client mirror of the projection's adjacency rules): where two pieces of equipment meet (an ASRS
  outfeed stub on a conveyor infeed), both the 3D scene and the 2D plan show a live indicator at the
  closest node pair, green ring = the projection will link them (auto by proximity within 1.5 m, or
  an explicit connection, labelled so), amber dashed = within 3 m but too far to link ("not linked",
  with the gap in metres); indicators follow drags. A selected equipment's Properties panel gains a
  **Connections section**: per endpoint node it shows the linked counterpart (code, distance, and HOW:
  auto vs explicit), or "not linked" with the nearest candidate, and offers an explicit **node-to-node
  link** from a candidate list of other equipment's nodes sorted **closest first**; Unlink removes the
  explicit connection and says honestly when proximity still auto-links the pair. The Properties panel
  also lists **every explicit connection** (`Connections (N)`) with a per-row Delete; **clicking a row
  opens a connection detail/edit dialog** (Delete stays inline, it does not open the dialog) that shows
  the link un-truncated, both endpoints with their full equipment code AND the exact node each end is
  anchored to (`CODE#index`), whether it is a hand-drawn node link or an explicit link with
  auto-inferred endpoints, the gap in metres between the resolved nodes, and the status; the **anchored
  path point of each end, the label and the status are editable** (path-point Selects offer Auto, the
  projection exit/entry fallback, or any routable node on that end), with edits mutating the in-memory
  connection so the editor's Save round-trips them. A **routing graph
  table** tab shows the generated graph
  (nodes, edges, costs) as a read-mostly inspector. **Route test mode** (`RouteTest.tsx`): toggle "Test
  route" in the toolbar, then click a start and a target point in the 3D scene (resolved to the nearest
  projected routing node); runs Dijkstra over the directed routing graph — the same algorithm and graph
  that flow-orchestrator routes on — and renders a glowing polyline + sphere hops with a ghost tote
  animating the path at 0.5 m/s, or lights the reachable frontier dimly when no path exists so the
  user can see exactly where connectivity ends.
- **Routing projection** (`RoutingProjectionService`, `POST /api/flow/automation/topology/project`):
  turns the placement model into the routable conveyor graph of §7b, **fully replacing** the
  warehouse's nodes/edges/loops. Path waypoints become nodes, directed sections become edges, function
  points alias a layout node (when on-point) or split a section (mid-run) into named PLC node codes,
  and a closed/cyclic conveyor becomes a capacity loop. A divert FP's **default direction** is
  resolved geometrically (its straight out-edge is the one best aligned with the travel direction
  at its offset, the branch the least aligned) and stored as the projected node's
  `default_exit_code`, which per-scan routing falls back to for unrouted totes. **Connections are auto-inferred from
  geometry**: a node of one equipment within ~1.5 m of a node of another is linked (both directions)
  — so an ASRS infeed stub meeting a conveyor, or a divert stub landing on another conveyor, merges
  automatically with no hand-drawn connection. **Explicit connections are honoured at node level**:
  a connection carrying `fromPathIndex`/`toPathIndex` stitches exactly those two nodes (any
  distance); without indices the legacy exit-of-FROM → entry-of-TO resolution applies. A single
  edge-dedup set spans section edges, explicit connections and auto-inference, so an explicit link
  that duplicates an auto-inferred edge projects exactly one edge. The editor offers explicit links
  from the per-node Connections section (no scene-wide "Connect" tool); GTP workstation
  role-interactions stay explicit.
  The projection returns a `ProjectionResult` (node/edge counts + non-fatal warnings). As a side-effect of
  projection, **every GTP workstation's STOCK/ORDER conveyor interactions** (connections tagged with a role
  in the topology editor's Properties panel) are projected into the corresponding `gtp_station`'s nodes via
  `GtpClient.syncStationNodes` — the function-point's `offsetM` is carried as `inboundDistanceM` so the
  emulator can time tote arrivals without caller-supplied distances. This is best-effort: a gtp call
  failing never aborts the routing projection.

---

## 8. The two working vertical slices

**Goods-in → stock:** `POST /api/txlog/events {GoodsReceived}` → outbox relay →
`txlog.stream` → inventory projection → `stock`.

**Outbound:** `POST /api/orders` → `POST /api/orders/{id}/release` (or `/release-due`,
priority/dispatch-time ordered) → order-management calls allocation → allocation reserves
at PICK locations (inventory) + cubes → order `ALLOCATED`/`NOT_FULFILLABLE` →
`POST /api/allocation/batches` clusters small orders for picking.

**Line transactions (all order types):** `POST /api/orders/{id}/lines/{lineNo}/transactions`
→ order-management appends `GoodsReceived` / `Picked` / `StockAdjusted` to txlog → outbox
relay → `txlog.stream` → inventory projection moves `stock.qty`. INBOUND = receipts (+),
OUTBOUND = picks (−), COUNT / ADJUSTMENT = signed adjustments.

---

## 9. Testing & CI

**Platform:** `platform/docker-compose.yml` (local dev + `--profile apps` full stack). Starter
Kubernetes manifests in `deploy/k8s/` (`Deployment`/`Service` for every service, `HPA` for
high-traffic services, `ConfigMap` + `Secret` placeholders); horizontal scaling safety covered
by ShedLock (scheduled jobs) and a pessimistic row lock (conveyor loop capacity) — see
[`docs/SCALING.md`](./SCALING.md).

**CI** runs on GitHub Actions (`.github/workflows/ci.yml`): Java `./gradlew build`
(Testcontainers tests on the runner's Docker), Go adapter build/vet/test, UI build, and
OpenAPI structural validation. The Gradle wrapper is committed (Gradle 8.10).

Testcontainers + JUnit 5 + Mockito. Run locally with `./gradlew build` or
`./gradlew :services:<name>:test` (Docker required). Present: master-data
(`MasterDataPersistenceTest`, `MasterDataApiTest`, `AdminDbConsoleTest` — admin DB console:
ADMIN gating, schema listing, SELECT happy path, write/multi-statement/CTE rejection, row cap,
smuggled write blocked by the READ ONLY transaction), txlog
(`TransactionLogServiceTest`, `OutboxRelayTest`), inventory (`InventoryPersistenceTest`,
`StockProjectionServiceTest`, `InventoryServiceTest`), allocation (`AllocationEngineTest`
— pure pick-breakdown / cubing / batch-merge logic; `AllocationServiceTest` — Testcontainers
+ mocked clients covering allocate → cancel-releases-reservations), order-management
(`OrderTransactionTest` — record + stage outbox atomically; `OrderTransactionRelayTest` —
relay appends + stamps event id; `OrderAuthorizationTest` — MockMvc: VIEWER blocked / SUPERVISOR
allowed to create with security on), master-data (`MasterDataRbacTest` — read needs VIEW,
write needs EDIT), iam (`IamServiceTest` — Testcontainers: seeded roles, effective-permission
resolution, catalog validation), flow-orchestrator (`DeviceTaskServiceTest` — Testcontainers +
mocked `DeviceClient`: COMPLETED on success, FAILED on adapter error without losing the task,
query by id/correlation). Go: conveyor `main_test.go` (`POST /tasks` COMPLETED / FAILED / 405).
The gateway has `GatewayAuthEndToEndTest` — a **live Keycloak Testcontainer** that imports the
canonical `openwcs` realm, mints a real JWT via the password grant, and drives a route to an
in-test echo server: no token → 401, a realm JWT → 200 with the identity forwarded as
`X-Auth-User`/`X-Auth-Roles`, and client-supplied identity headers stripped (anti-spoof).
Not compiled in the authoring environment (no local JVM/Gradle) — **CI is the gate** (it has
run green); the first run surfaced one test-isolation bug, now fixed.

## 10. Not built / known gaps

- **Slotting (ADR 0003 + its Refinements section):** built — soft single-SKU-per-lane, HU type
  capabilities + per-area allowed-HU-types, empty-HU far placement + LOW transport priority,
  multi-compartment HUs (dominant velocity + SKU-set affinity), cell-as-location coordinates
  (aisle/side/x/y/z), and **self-taught recency-weighted ABC** (EWMA from `txlog.stream`,
  off-peak classify, `manual_override`). **Fast-follows:** the engine computes put-away/
  replenishment/re-slot *plans* and exposes them over REST (+ the goods-in `assignPutaway`
  delegate) but does not yet **dispatch** moves as device tasks via flow-orchestrator;
  **put-away candidate locations are now filtered against live inventory truth** — before scoring,
  the engine asks inventory `POST /api/inventory/locations/occupied` (which of these locations hold
  any stock row or handling unit) and drops the physically-occupied ones, so a seeded/occupied slot
  with no slotting assignment can no longer be chosen (best-effort: if inventory is unreachable the
  call is logged and skipped so put-away still proceeds). Lane/aisle *depth* occupancy still comes
  from the service's own assignment ledger (not yet fully reconciled against live inventory); the
  engine doesn't yet group cells into deep lanes (deepest-empty-first); the goods-in
  **decant** step, FEFO replenishment sourcing and txlog audit
  events (`PutawayAssigned`/`ReplenishmentPlanned`/`ReslotRecommended`) are pending.
- **GTP station execution** (`gtp`, ADR 0006): built — STOCK + ORDER/PUT_WALL nodes, batch
  pick-and-put with put-to-light, ORDER_LOCATION vs PUT_WALL destination topology, plus orthogonal
  operating modes (PICKING / DECANTING / STOCK_COUNT / QC / MAINTENANCE) on a generalised work-cycle
  with mode-appropriate task lines + outcomes. **Station inbound queue relocated to flow-orchestrator**
  (ADR-0007 Phase 3c-1): `REQUESTED → IN_TRANSIT → QUEUED → DONE` with `arrival_seq` and per-HU
  transport trace; gtp's `station_queue_entry` and inbound enqueue are deprecated/legacy.
  **Deactivate/drain control** and **in-transit capacity caps** remain in gtp.
  **Topology-projected station nodes** (`POST /stations/{id}/nodes/sync`, `inboundDistanceM`): the
  automation-topology projection automatically syncs STOCK/ORDER nodes into GTP stations and carries
  the feeding conveyor distance for emulator queue timing — built. Physical
  put-lights + picking-HU retrieval + demand auto-wire from allocation, and the
  decant→slotting-putaway integration is a seam (not built). **At-station blind count** (`POST
  /tasks/.../station-count`) posts `StockAdjusted` directly via the counting service — wired ✅. The
  legacy GTP work-cycle STOCK_COUNT task-line variance seam (`stockCountAdjustment`) is still not
  wired to inventory. Count-tote routing via `flow.requestPresentation` (one call; flow orchestrates RETRIEVE + CONVEY, metering the cap at RETRIEVE dispatch) is wired via the counting service (emulator mode only); GTP store-back also resolves the STORE transport's adapter family from the destination's storage type. **ADR-0009 multi-deep channel relocation (Phases 1 + 2) is built**: slotting plans dig-out, flow executes the RELOCATE chain, inventory HU location registry is kept live through every transport milestone.
- **Automation topology** (flow-orchestrator §7f): built — 3D/2D placement editor (levels, placed
  equipment with conveyor path/sections, function points placeable anywhere on a run, ASRS port stubs,
  GTP workstations linked to conveyor function points by role; divert direction picker, click-to-place
  ports, waypoint delete, routing graph table inspector, route test mode), and a deterministic **routing
  projection** that generates the §7b conveyor graph from the layout with geometry-inferred
  connections. On projection, STOCK/ORDER conveyor interactions of each GTP workstation are also
  **projected into the GTP station's nodes** (function-point `offsetM` → `inboundDistanceM` on the
  node) — this part is built. Not yet built: equipment family/type is not visible to the projection
  (classification is structural/by category), the projection is a manual action (no auto-reproject on
  save), and the placed-equipment ↔ live-device binding for physical device moves (driving real conveyor/
  ASRS moves through these nodes) is still the flow-orchestrator/adapter seam.
- Scaffold-only: notification, integration-*. All four Go device adapters (conveyor, asrs,
  amr-geekplus, autostore) are real-hardware skeletons — `POST /tasks` returns FAILED ("hardware
  not connected"); **no real-hardware protocol client** exists yet. Emulation is handled by the
  `equipment-emulator` service (§7b).
- flow-orchestrator dispatches device tasks but **no BPMN process originates them yet**
  (process-engine is still a scaffold); the device contract is synchronous HTTP, not the
  production Kafka transport.
- **Auth is built but off by default** — gateway JWT validation + per-endpoint RBAC across all
  six REST services + inter-service identity propagation, all toggled by
  `openwcs.security.enabled`. The `openwcs` realm is imported by compose, and the edge-auth
  path is now exercised end-to-end in CI (`GatewayAuthEndToEndTest`, live Keycloak container).
  `RoleCatalog` reflects the shipped seed roles only — custom IAM roles would need a runtime
  IAM lookup. No mTLS yet (inter-service trust rides on forwarded headers behind the edge).
- `actor` is authenticated (from the gateway-forwarded identity) when security is on, and
  caller-asserted when off (the default).
- Cubing is volume+weight (not 3D bin-packing); it now uses multiple shipper sizes
  (largest-first, downsizing the final carton), but carton-size ranking is by usable volume
  then net weight — it does not try alternative packings to minimise carton count.
- Pick-type breakdown assumes stock is base-UoM and reads case size from the "CASE" UoM.
- Events only on `txlog.stream` (no Avro/Schema-Registry, no master-data catalog events, no
  DLQs); no consumer-driven contract tests (CI validates the OpenAPI specs structurally).
- Order status is not auto-advanced by postings (no auto-complete when `postedQty` meets
  `qty`); lifecycle orchestration is a follow-up.
- OpenAPI: master-data shipper/fulfillment-config paths not yet added to `master-data.yaml`.
