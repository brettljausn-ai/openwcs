# openwcs

[![CI](https://github.com/brettljausn-ai/openwcs/actions/workflows/ci.yml/badge.svg)](https://github.com/brettljausn-ai/openwcs/actions/workflows/ci.yml)
[![Sponsor on Patreon](https://img.shields.io/badge/Patreon-sponsor-8DC63F?logo=patreon&logoColor=white)](https://www.patreon.com/c/karlfriesenbichler)

An **open-source Warehouse Control System (WCS)** that orchestrates automated
material-handling equipment — conveyors, ASRS (shuttles & cranes), AMRs (e.g.
Geek+), and storage systems (e.g. AutoStore) — and manages the flow and storage
of goods inside an automated warehouse.

A WCS sits **between** the business-level WMS/ERP and the **physical equipment**:
it executes and coordinates physical movement, manages real-time stock in the
automated area, and lets admins design the processes (goods-in, outbound,
cycle count) that run the building.

> 📐 **Architecture & design rationale:** [`build.md`](./build.md)
> 🎨 **UI design system & tokens:** [`styling.md`](./styling.md)
> 🏗️ **As-built (what's actually implemented):** [`docs/AS-BUILT.md`](./docs/AS-BUILT.md)
> 📊 **Development status:** [`docs/DEVELOPMENT-STATUS.md`](./docs/DEVELOPMENT-STATUS.md)
> 🌐 **Public product site:** [openwcs.ai](https://openwcs.ai) — source in [`public/`](./public) (Express + EJS; the old GitHub Pages mirror now redirects here)
> 🗺️ **Product roadmap:** [`public/roadmap.md`](./public/roadmap.md) (edit this file to update the roadmap page)

---

## Highlights

- **Microservices, event-driven.** Independently deployable services over a
  Kafka backbone. Designed to grow fast — new equipment and new host systems are
  added as *new services*, never by bloating existing ones.
- **Transaction log as source of truth.** Every physical event is appended to an
  immutable log; inventory and dashboards are projections that can be replayed.
- **Narrow shared database.** A common PostgreSQL holds **only** master data and
  the transaction log; every other service owns its own store.
- **Processes are data.** Admins design goods-in / outbound / cycle-count flows
  in a visual (BPMN) designer — no redeploy to add a variant.
- **Area-scoped mobile work.** Master data carries first-class **hierarchical areas**
  (zones that nest); an operator signs into an area on the handheld and the server hands
  out only that area's work, with a **per-operator reservation** (Postgres `FOR UPDATE
  SKIP LOCKED`) so two people never grab the same count line or replenishment task.
- **Equipment & host abstraction.** Uniform contracts mean the core never speaks
  a vendor protocol, an ERP dialect, or a WMS API directly.
- **Design the automation in the app.** A visual **Automation Topology** editor places,
  sizes and connects equipment (conveyors as polyline section graphs, ASRS with IN/OUT
  ports, diverts/merges, GTP workstations) on warehouse levels in 2D/3D — and projects a
  vendor-neutral routing graph (nodes/edges) the orchestrator routes HUs over.
- **Scales horizontally.** Every service is stateless; ShedLock prevents duplicate
  scheduled-job execution across replicas; the conveyor loop-capacity check uses a
  pessimistic row lock so it stays correct under concurrent scans. Ready-to-apply
  Kubernetes manifests and HPA config live in `deploy/k8s/` — see [`docs/SCALING.md`](./docs/SCALING.md).

---

## Repository layout

```
openwcs/
├── build.md              # architecture & build plan (read this first)
├── styling.md            # UI design system (tokens, components)
├── settings.gradle       # Gradle multi-project (Java services + gateway + libs)
├── build.gradle
├── deploy/
│   └── k8s/              # Kubernetes starter manifests (horizontal scaling — see docs/SCALING.md)
├── platform/
│   └── docker-compose.yml   # local infra (postgres, kafka, schema-registry, keycloak) + app profile
├── contracts/
│   ├── events/           # Avro event schemas (shared, versioned)
│   └── openapi/          # REST API specs (generate clients/stubs)
├── libs/common/          # shared Java library (event envelope, domain types)
├── gateway/              # API gateway (Spring Cloud Gateway)
├── services/             # backend microservices (one dir per service)
│   ├── master-data/  inventory/  process-engine/  order-management/
│   ├── flow-orchestrator/  txlog/  iam/  notification/  assistant/
│   ├── integration-sap/  integration-manhattan/     # host gateways
│   └── adapters/         # Go device adapters (one per equipment family)
│       ├── conveyor/  asrs/  amr-geekplus/  autostore/  conveyor-sniffer/
└── ui/                   # React + TypeScript SPA (operator + management screens)
```

---

## Services & ports

| Service | Lang | Port | Responsibility |
|---|---|---|---|
| `gateway` | Java | 8080 | API gateway: routing + JWT validation (toggleable) + forwards `X-Auth-User`/`X-Auth-Roles` |
| `services/master-data` | Java | 8081 | SKUs, UoM/bundles, barcodes, locations, equipment, warehouses; **hierarchical Areas** (`/api/master-data/areas`: first-class nestable warehouse zones (an area can nest inside an area via `parentAreaId`, a location carries an optional `areaId`); cycle + cross-warehouse parent guards; `GET /areas/{id}/location-ids?recursive=true` resolves an area to every location UUID in its subtree via a recursive CTE; `GET /areas/resolve` for scan/verify), the scoping primitive behind area-scoped mobile work (counting + replenishment claim-next); one-call SKU card read (`GET /skus/{id}/card?warehouseId=`: identity + base-UoM dimensions + profile metadata) for operator screens; read-only **scan resolve** endpoints (`GET /resolve/{sku-by-barcode,sku,location}`, `MASTER_DATA_VIEW`, 200 `found:false` on a miss, never 404) that confirm a scanned code exists and return its linked graph for handheld scan verification; admin read-only DB console (`/admin/db`) |
| `services/inventory` | Java | 8082 | Real-time stock: durable stock table (current qty) kept in lockstep with the tx log; lock/unavailable; location-scoped reservations; FEFO/FIFO; reporting aggregates (stock-by-SKU available/allocated/unavailable split + per-block storage-density history with a daily ShedLock-guarded snapshot sweep) |
| `services/process-engine` | Java | 8083 | Admin-designed BPMN process definitions + execution (Flowable) |
| `services/order-management` | Java | 8084 | Outbound orders + fulfilment lifecycle + release management (priority/dispatch-time); delegates allocation; short allocate and release (supervisor decision: a short order picks the available qty and ships short, reported per line to the host via an OrderShipped confirmation); order-flow report (expected/active/started + per-day and hour-of-day intake histograms per direction); persisted dispatch waves (`route_dispatch`: one row per route + cut-off, OPEN/LOADING/DEPARTED with real departure timestamps) backing the dispatch board + the SLA report (on-time uses the wave's actual `departed_at` vs cut-off); operator picking execution (`GET /api/orders/pick-tasks` pick queue + `POST /api/orders/pick-tasks/{lineId}/confirm` confirm/short, posting a `Picked` line transaction through the existing outbox->txlog so stock decrements); the pick confirm now applies an **available-at-location guard** (reads `GET /api/inventory/availability?...&locationId=` and clamps the booked qty to what is pickable at the face so a pick can never drive stock negative, marking the line SHORT when it clamps), stamps each transaction with the operator (`X-Auth-User`), the driving handheld process instance (`processInstanceId` body field or `X-Process-Instance` header) and the order type, and carries `{reservationId (when known), orderType, processInstanceId, actor}` on the `Picked` event so inventory can consume the reservation; the allocated pick location is threaded from allocation onto the order line (`pick_location_id`) so the pick queue returns a real location code; **scan-verify resolve** (`GET /api/orders/resolve?warehouseId=&ref=`, `ORDER_VIEW`): resolves an order/ASN by its reference (an outbound picksheet or inbound ASN barcode) to `{found, order{orderId,orderRef,orderType,status,customerRef,lineCount}}`, 200 `found:false` on a miss (never 404), used by the handheld process designer's `order`/`asn` verify kinds |
| `services/allocation` | Java | 8091 | Outbound prep: pick-location allocation (UoM breakdown), multi-size cubing into shippers (largest-first, per-line carton traceability) or host 1:1, batch picking; allow-short mode keeps partial reservations and cubes only the allocated quantities (FULFILLABLE_SHORT) |
| `services/slotting` | Java | 8093 | Inbound slotting & replenishment (ADR 0003): put-away assignment for automated rack/GTP blocks (velocity-to-exit, multi-deep same-SKU lanes, aisle redundancy + balancing), manual pick-face slotting with min/max + opportunistic replenishment, off-peak re-slotting; **move dispatch**: a FlowClient + dispatch endpoints (`reslot/{id}/dispatch`, `replenishment/{taskId}/dispatch`, `decant/putaway`) turn plans into real physical moves via flow `POST /api/flow/moves` and flip them to a DISPATCHED status (the goods-in decant-to-putaway seam is wired); **lane-depth reconciliation** (`POST /api/slotting/lanes/reconcile` + an off-peak ShedLock sweep): compares the lane-depth ledger (active put-away assignments in multi-deep lanes) against inventory occupancy, reports per-lane drift and closes ghost assignments (RECONCILED); **area-scoped per-operator replenishment reservation** (`POST /api/slotting/replenishment/claim-next {warehouseId, areaId, instanceId}`): atomically reserves the next PLANNED replenishment task whose destination pick face is inside a Master Data Area subtree (EMERGENCY first, Postgres `FOR UPDATE SKIP LOCKED` so two operators never grab the same task), `POST /api/slotting/replenishment/release {instanceId}` frees not-yet-started reservations, `started_at` stamped on dispatch, a ShedLock TTL sweeper reclaims stale ones |
| `services/gtp` | Java | 8094 | Goods-to-person station execution (ADR 0006): configure GTP stations + STOCK/ORDER nodes, present a stock HU to generate a put-to-light put-list across order destinations (ORDER_LOCATION conveyor / PUT_WALL rack topology), confirm puts, complete destinations — one stock HU serving many orders (batch); **configurable pick layout** per station (`V10`: ONE_TO_ONE single carton / ONE_TO_N N lit pick slots / PUT_WALL lit cubbies) that the operator console renders by layout; **pick-to-light driving** — the gtp service ILLUMINATEs/CLEARs put lights on present/confirm/close (best-effort) via the PTL adapter or the emulator; orthogonal **operating modes** freely enabled per station (PICKING / DECANTING / DECANT_MULTI / STOCK_COUNT / QC / MAINTENANCE) on a generalised task-line work-cycle; **ADR-0007 Phase 3c-1**: inbound queue source of truth relocated to flow-orchestrator — `POST /stations/{id}/queue` inbound enqueue deprecated (counting no longer calls it); `POST /queue/{id}/complete` fans out to flow `done` only — flow owns the return-to-storage leg and ONLY slotting picks the destination (gtp's parallel store-back removed) |
| `services/assistant` | Java | 8096 | AI assistant: an in-app chat over your warehouse data. `POST /api/assistant/chat` runs an Anthropic Claude Messages-API tool-use agentic loop over a fixed set of **read-only** WCS endpoints (orders, stock-by-SKU, inventory + situation dashboards, HU trace, transport tasks, stock-blocking), each forwarding the caller's identity and warehouse scope so RBAC + warehouse scope apply and the model answers only from tool results; `GET /api/assistant/status` reports enabled/configured/model. Default model Haiku 4.5, Opus 4.8 selectable. The Anthropic key, model and enable flag live in master-data system config (write-only on the public API; read by this service over a network-only internal endpoint, never the gateway). **Needs an Anthropic API key configured by an admin** (Settings → AI Assistant); disabled until then |
| `services/counting` | Java | 8095 | Cycle / stock counting: count tasks (location/SKU/zone/block scope, blind or variance), ABC-cadence scheduling, capture → variance vs inventory → reconcile (auto-approve within tolerance / recount) → `StockAdjusted`; **at-station blind count** (`POST /tasks/{id}/lines/{id}/station-count`): blind recount-until-two-agree state machine; confirmed variance posts `StockAdjusted` with reason `COUNTING`, actor attributed to the operator (`X-Auth-User` / `?operator=`, fallback `"system"`); **ADR-0007 Phase 3c-1**: count-tote routing issues a single `flow.requestPresentation(...)` — flow orchestrates RETRIEVE + CONVEY and meters the cap; no separate gtp-enqueue call; **area-scoped per-operator line reservation** (`POST /api/counting/lines/claim-next {warehouseId, areaId, instanceId}`): atomically reserves the next PENDING count line whose location is inside a Master Data Area subtree (Postgres `FOR UPDATE SKIP LOCKED` so two operators never claim the same line), `POST /api/counting/lines/release {instanceId}` frees not-yet-started reservations, the first recorded count stamps `started_at` (immune to release/sweep), a ShedLock TTL sweeper reclaims stale ones |
| `services/process-designer` | Java | 8097 | Configurable handheld operator processes (Phase 1 + Phase 2 + Phase 3, feature complete; spec `docs/process-designer-spec.md`): engine + storage for versioned process definitions (a JSON flow of handheld screens + task steps, exactly one ACTIVE version per process key, draft/active versioning + in-flight pinning, publish validates reachability + step `skipWhen` conditions). Base path `/api/process-designer/**` (the Flowable process-engine owns `/api/process/**`). **Version management** (Phase 2): `POST /defs/{key}/{version}/duplicate` clones a version into a new DRAFT, and JSON import/export (`POST /defs/import` → DRAFT, export = `GET /defs/{key}/{version}`) moves defs between environments. **Step-level `skipWhen`** (Phase 2): a condition the client runtime evaluates to skip a step, validated at publish by a recursive-descent `ConditionParser` (no eval; malformed → 422, a skippable step must keep an onward path). Instance start/checkpoint/resume (checkpoint idempotent per `(instance, step)`) plus **instance history/monitoring** (`GET /api/process-designer/instances`, Phase 2). A **curated server-side task library** whose task types call existing endpoints with the operator's forwarded identity (`inventory.move` → `/api/flow/moves`, `slotting.putaway` → `/api/slotting/decant/putaway`, `picking.confirm` → `/api/orders/pick-tasks/{id}/confirm`, `inventory.lookup` (on-hand by location OR handling unit, `warehouseId` auto-injected from the instance), `txlog.post`, plus Phase 2 `host.confirm`, `inventory.adjust`, `counting.capture`, `order.lookup`); no arbitrary Java. A task-catalog endpoint `GET /api/process-designer/tasks` (each task + per-input/output with descriptions) drives the designer's searchable task picker. A no-code **`compute` step** assigns variables from a safe value-returning expression grammar (superset of the condition grammar + arithmetic), client-evaluated, parsed at publish (`ExpressionParser`, 422 on malformed); branch `when` / `skipWhen` / compute expressions are validated to reference only declared data-object variables (designer + publish). **Phase 3 (final)**: a **sandboxed scripting escape hatch** — a built-in `script` task type running server-side in a locked-down GraalJS sandbox (no host/Java access, statement + wall-clock + output limits, interpreted JS never compiled to host bytecode, a deep-frozen read-only `data` global), gated by both the `OPENWCS_PROCESS_SCRIPTING_ENABLED` flag (default false) **and** the new `PROCESS_SCRIPT_AUTHOR` permission (ADMIN only; else 422/403), parse-validated at publish; and **design-time AI task-assist** (`POST /api/process-designer/assist/task` → a curated-task mapping or a draft sandboxed snippet for a human to review, Anthropic Java SDK, `ANTHROPIC_API_KEY`, default `claude-haiku-4-5`; 503 when no key; **never auto-deploys**); plus a **capabilities** endpoint (`GET /api/process-designer/capabilities`, also returns `verifyKinds`). **Scan verification**: text/number input screens may carry a `config.verify` block that confirms a scanned code EXISTS via `POST /api/process-designer/verify {warehouseId, kind, code}` (forwards the operator identity), branching on not-found (re-prompt / go-to-step). Kinds: `barcode`, `sku`, `location`, `skuScan` (code-or-barcode + UOM prompt) via the master-data resolve endpoints (base url `OPENWCS_MASTER_DATA_BASE_URL`), plus `order` (outbound picksheet) and `asn` (inbound ASN) via order-management `GET /api/orders/resolve` (`openwcs.process-designer.orders-base-url`). Resolved fields are per-kind (`capabilities.verifyFields`) and may be **scalars or objects**: a `verify.write` stores a scalar (incl. the resolved UUID), a whole resolved object (`uom`/`sku`/`location`/`order`/`asn`), or one drilled property (`uom.factor`) into data-object variables. **Area-scoped mobile work**: three curated tasks plus `instanceId` auto-injection (alongside `warehouseId`) into task inputs (`stockcheck.next` (input `areaId` → counting claim-next), `replenishment.next` (input `areaId` → slotting claim-next), and `work.release` (no inputs, uses `instanceId`, calls both counting + slotting release best-effort, safe to call twice)), plus a new `area` verify kind (resolves via master-data `GET /areas/resolve`, writes `areaId`/`areaCode`). A line/task is reserved when `*.next` hands it to an operator, becomes a firm claim once the first count/move posts, and is freed by an explicit `work.release` on the exit branch or by the per-service TTL sweeper if the app closes/crashes. Seeds an ACTIVE **Stock Check** sample process plus a reference **`stock-check-by-area`** process (area pick → `stockcheck.next` → count loop → `work.release`). **RF resilience**: per-screen **step history** for exact resume + replay (`process_step_event` + an `assigned_to` column on `process_instance`, migration `V6`): the mobile client posts every screen advance to `POST /instances/{id}/step {seq, stepId, stepType, data}` (idempotent on `instanceId+seq`) so the server holds the exact `current_step` + data and a refresh / device switch / RF re-login resumes there; `GET /instances/{id}/steps` is the ordered replay log; the instance list takes an `assignedTo` filter; `POST /instances/{id}/reassign {toUser}` (supervisor-gated, `PROCESS_DESIGN_EDIT`, audited) moves in-progress work to another operator; and the engine injects the operator **username** into every task's inputs so it is persisted on every resulting transaction. Three more curated fulfilment tasks: **`picking.book`** (book picked stock from a location to an order line, ties the booking to the running instance, surfaces the clamped `bookedQty` + `pickStatus`), **`inventory.reduce`** (exception: a negative `StockAdjusted` with a `reason`, after a pre-check that rejects an over-reduction so stock never goes negative), and **`order.load`** (resolve an order from a scanned/typed code → `found`/`orderId`/`orderRef`/`orderType`/`orderStatus`/`lineCount`). Permissions `PROCESS_DESIGN_VIEW` / `PROCESS_DESIGN_EDIT` / `PROCESS_SCRIPT_AUTHOR` |
| `services/flow-orchestrator` | Java | 8085 | Dispatches device tasks to adapters over the uniform device contract; vendor-neutral conveyor routing (topology graph + HU route plans + shortest-path next-hop on scan; diverts carry an optional topology-set default direction that unrouted totes follow, stopping at the divert when none is set); **hard-real-time scan path**: a per-warehouse in-memory graph snapshot with precomputed next-hop tables answers each scan in low single-digit milliseconds (counters/trace persist asynchronously; per-instance latency percentiles at `GET /api/flow/reports/decision-latency`); **automation-topology placement** (levels, placed equipment with envelopes + conveyor polyline sections, function points, ASRS ports; `GET`/`PUT /api/flow/automation/topology`) which drives the 2D/3D editor and projects the routing graph (connections auto-inferred from geometry, plus explicit node-level links anchored at exact path points, deduplicated against the inference); **flow-owned induction queue** (ADR-0007 Phase 3c-1): `POST /api/flow/induction/requests` (counting requests HU presentation; flow orchestrates RETRIEVE + CONVEY, cap metered at RETRIEVE dispatch; `REQUESTED` backlog uncapped), `GET /api/flow/induction/queue?workplaceId=` (full `{REQUESTED,IN_TRANSIT,QUEUED}` pipeline ordered by `arrival_seq`), `POST /api/flow/induction/entries/{id}/done`; **per-HU transport trace** (`GET /api/flow/hu-trace?huId=`): append-only timeline REQUESTED → RETRIEVED → INDUCTED → [SCANNED … RECIRCULATED/DIVERTED …] → ARRIVED → QUEUED → DONE — every conveyor scan from the live walk is recorded (Phase 3d); **return-to-storage (slotting-only)** CONVEY after workplace completion only slotting decides the destination (never the source slot); a slotting failure leaves the tote circulating on the conveyor (`awaiting_slot`) with a ~30s ShedLock retry sweep that assigns the slot mid-journey; HUs are booked to conveyor/workplace operational locations as they move; **ADR-0009 dig-out chain**: RELOCATE chain before a blocked RETRIEVE (slotting plans, flow executes, inventory location booked per step); **physical-move audit**: every completed/failed STORE/RETRIEVE/RELOCATE (+ AutoStore BIN_* variants) appends an append-only `HandlingUnitMoved` event to the txlog system-of-record (stream `hu-<huId>`, from/to location + actor) — the durable move trail beside the flow-local HU trace; CONVEY transport is not audited; audit-only (inventory does not project it, so no double-apply); **physical move dispatch** (`POST /api/flow/moves`): picks the transport by the two locations' storage block — a same-system move dispatches a single RELOCATE (AutoStore BIN_RELOCATE), a cross-system move runs a RETRIEVE→CONVEY→STORE chain through the existing routing machinery (tracked leg-by-leg in `flow_move_chain`, idempotent per leg; the STORE leg's device family is resolved from the destination location's storage block, so a cross-system move into an AutoStore correctly issues BIN_STORE); the terminal completion books the new inventory location and fires the `HandlingUnitMoved` audit (stock follows the HU, so no `StockMoved` for HU-bound moves); **twin AMR/AutoStore/ASRS live views** (`GET /api/flow/twin/amr-fleet`, `GET /api/flow/twin/autostore`, `GET /api/flow/twin/asrs-cranes`): twin read models mapping the emulator's AMR-fleet, AutoStore-grid + ASRS-crane telemetry to world coordinates for the hardware twin, best-effort empty when the emulator is off |
| `services/txlog` | Java | 8086 | Append-only transaction log (shared Postgres) |
| `services/iam` | Java | 8087 | Authorization model: users → roles → coded permissions; per-user warehouse access (allowed warehouses + default; gateway-enforced scope) (Keycloak does auth) |
| `services/notification` | Java | 8088 | Operator alerting: a ShedLock-guarded scheduled evaluator (~60 s) pulls the dashboard metric endpoints + per-warehouse thresholds, opens WARNING/CRITICAL alerts on a breach (deduped per warehouse/area/metric, cleared when back under), and delivers each open/clear by email (Spring Mail, degrades to log when SMTP unset) and webhook. `GET /api/notification/alerts?warehouseId=`, `POST /api/notification/alerts/{id}/ack` (SUPERVISOR/ADMIN), and `GET /api/notification/alerts/health` (ISA-18.2 alarm-system measurement: active-by-severity, opened/cleared per day, chattering and stale alerts) |
| `services/integration-sap` | Java | 8089 | Host gateway: SAP S/4HANA / HANA (OData/BAPI/RFC/IDoc) |
| `services/integration-manhattan` | Java | 8090 | Host gateway: Manhattan Active (REST) |
| `services/integration-host` | Java | 8092 | Canonical vendor-neutral Host API (`/api/host/**`): orders + ASNs + SKU sync (a list of SKUs with their UoM hierarchy and barcodes inline) in, confirmations out; vendor adapters translate into it |
| `services/equipment-emulator` | Go | 9097 | Single simulator for all device families; active when `HARDWARE_EMULATOR_ENABLED` is ON — flow-orchestrator routes device tasks here instead of the real adapters. Each command sleeps a realistic per-family duration before returning COMPLETED; `OPENWCS_EMULATOR_LATENCY_MS` overrides all commands (`0` = instant). `OPENWCS_EMULATOR_FAULT_RATE=N` injects deterministic faults (1 in every N tasks returns FAILED, result carries `fault: true`). `OPENWCS_EMULATOR_RECIRC_EVERY=N` recirculates every Nth CONVEY once before diverting, so arrival order visibly diverges from dispatch order (ADR-0007 R2); the result carries `recirculations` + `decisions` (sorter `RECIRCULATED`/`DIVERTED`) written to the HU trace by flow (R4). Latency, fault rate, and recirc rate are tunable at runtime via `GET`/`POST /config` (`{latencyOverrideMs, faultEvery, recircEvery}`). Exposes live twin telemetry: `GET /amr/fleet` (simulated AMR robot positions, status and carried HU), `GET /autostore/grid` (grid fill + per-port activity) and `GET /asrs/cranes` (simulated storage/retrieval cranes, one per aisle, travelling + lifting during ASRS tasks), all updated by AMR/AutoStore/ASRS task processing so the hardware twin's AMR-fleet, AutoStore and ASRS-crane views animate against real task load. Daily logs are decision-grade: every line pairs the HU code with route, equipment and the why (recirculation policy, fault injection, hold reason); adapters log refused tasks at WARNING with reason and consequence. |
| `services/adapters/conveyor` | Go | 9091 | Conveyor adapter; `POST /tasks` returns FAILED ("hardware not connected") — real-hardware seam (emulation in `equipment-emulator`). |
| `services/adapters/asrs` | Go | 9096 | Shuttle/crane adapter; `POST /tasks` returns FAILED ("hardware not connected") — real-hardware seam. |
| `services/adapters/amr-geekplus` | Go | 9093 | Geek+ AMR adapter; `POST /tasks` returns FAILED ("hardware not connected") — real-hardware seam. |
| `services/adapters/autostore` | Go | 9094 | AutoStore grid adapter; `POST /tasks` returns FAILED ("hardware not connected") — real-hardware seam. |
| `services/adapters/ptl` | Go | 9098 | Pick-to-light adapter (family PTL); uniform device contract `POST /tasks` ILLUMINATE/CLEAR + `/state`, driven by the gtp service to light put-lights (real-hardware seam) |
| `services/adapters/conveyor-sniffer` | Go | 9095 | Captures scan telegrams from defined IPs → posts observations to the WCS for conveyor topology learning |
| `ui` | React/TS | 5173 dev / 443 prod | Operator + management SPA, shipped as an **installable PWA** (offline app shell so a Wi-Fi blip never white-screens a handheld; when installed on a handheld it swaps the full sidebar/dashboards for a stripped **operator-process menu** of big tiles — Picking, Stock Check, etc., now also listing the **active configurable processes** built in the process designer) with a **scanner-first, RF-ready Picking** screen (keyboard-wedge scanning + an offline confirm queue for rugged Android handhelds): Keycloak login (with a self-service **change-password** form that also rescues "account is not fully set up" / temporary-password accounts); a **landing dashboard** at `/` (the post-login home; the old quick-links dashboard moved to `/home`) with state-aware situation tiles (Stock-blocking, Inbound, Outbound, Dispatch, Automation, Putaway) as heroes (big number + bullet/limit bar vs target + sparkline + ok/warning/critical colour, drill-down) and a **Dashboards** sidebar section with five deeper screens (`/dashboards/inbound`, `/outbound`, `/replenishment`, `/stock`, `/abc`: heroes + charts, no tables, ABC has a Pareto chart; Outbound carries SLA heroes (on-time-to-cutoff, order-cycle-time median), Inbound carries dock-to-stock + receive-errors; polls 15 s landing / 60 s menu, last-updated stamp + stale grey-out, grey base with `--warning`/`--danger` the single alert family; thresholds edited in **Settings → Alerts**, admin-gated), plus a full-screen **andon board** (`/dashboards/andon`, critical-first alert wall with a calm all-clear state) and an **alert-system-health** screen (`/dashboards/alert-health`, ISA-18.2). Turning demo mode on/off also seeds/clears representative dashboard sample data across the services (part of the demo switch, no separate button); and role/user-gated screens organised into five top-level sections — **Master data** (warehouses, SKUs, storage blocks, **areas** (a nested-area tree with an `AreaDialog` whose parent select excludes the area itself and its descendants), locations (with an **Area** select), handling-unit types, label templates — each catalog its own access-controllable screen), **Operations** (inbound/outbound orders, stock counting, **picking** (a scanner-first RF operator console at `/picking` over the order-management pick queue: a big glove-friendly next-pick card (location code, SKU code + name + image, qty), an auto-refocusing capture input that reads keyboard-wedge scans and matches them against the expected location then SKU (mismatch warns + vibrates) and auto-confirms at full qty, manual Confirm / Short still available, an advancing queue, codes not UUIDs, plus an offline confirm queue that holds confirmations through a Wi-Fi blip; pick-by-light / voice are further hardware seams layered on it), GTP workplaces, transport, hardware twin — a live 3D view of the floor with conveyor bodies tinted with their live state colour (green functional / orange jam or heavy traffic / red fault), totes replaying the real scan trail in smooth interpolated motion along the conveyor geometry (queueing as a spaced line on the station's inbound conveyor, never overlapping), ASRS storage fill at cell level, plus a **live AMR fleet** (robots coloured by status, carried HU on hover, interpolated per frame so they glide continuously between polls instead of snapping), **live ASRS cranes** (gliding + lifting in the rack, status-coloured, carried HU on hover, also interpolated per frame) and **AutoStore ports + grid fill %** fed by the emulator twin telemetry, stock transactions, stock overview, handling-unit registry), **Engineering** (automation topology — 2D/3D editor with live link indicators where conveyors meet and a per-node Connections panel (closest-first explicit links) plus a click-to-open connection detail/edit dialog (full endpoint codes with the anchored node, distance, editable path points/label/status, delete), routing graph table, route test mode, BPMN processes, a **process designer** for configurable handheld processes (`/process-design`, ADMIN/SUPERVISOR): it opens to a **definitions landing table** (search + New process), then a WYSIWYG editor built around a **visual node canvas** (React Flow — the sole flow view: drag step nodes (positions persist), draw the flow by dragging connectors (first link = default next, further links = `when`-branch edges edited inline on the edge), delete edges, loops shown, a "Tidy" auto-layout) alongside a LIVE handheld preview using the same `ProcessScreenView` as the runtime, with config in **guided dialogs** near the phone preview — Edit screen, Edit task (server task / compute / sandboxed script, searchable task picker with descriptions, AI task-assist), Verify flow, Data object (the properties panel is now a per-step summary + skip-when); plus Simulate, validate and publish-active, a version-management pane (drafts/active/archived, only DRAFT editable), duplicate/clone, JSON export/import, richer pre-publish validation (unreachable steps, skip-with-no-onward-path, malformed conditions/expressions, undeclared variables, missing required task inputs, duplicate ids), the **Phase 3** sandboxed-script step code editor (shown only when scripting is enabled and the caller may author scripts) and an AI "describe what this task should do" assist panel (Apply a curated suggestion, or Insert an AI-drafted snippet as a script step labelled "AI draft, for your review" — nothing is auto-deployed); the operator runtime is **client-driven** — `/process/:key` walks the screens locally, evaluates branch conditions + step skip conditions with a safe interpreter and posts only task-step checkpoints (offline-queued, holding at a task when offline); plus a read-only **Process instances** monitoring screen (`/process-instances`, ADMIN/SUPERVISOR) listing instances + a detail pane (data object, current step, step trail), and an **RF Users** screen (`/rf-users`, ADMIN/SUPERVISOR) listing the active operators on configurable handheld processes with their process + current workstep, a **Reassign** action (move in-progress work to another operator) and a **Replay** viewer that steps through what an operator saw and entered by reusing the real handheld screen renderer; the configurable-process mobile runtime persists **every screen advance** to a durable IndexedDB step queue and advances immediately, so a refresh / device switch / RF re-login resumes on the EXACT step with data intact, and the handheld operator menu has a **"Resume in-progress work"** launcher for the operator's own running instances, slotting, equipment), **Configuration** (GTP workplaces, settings incl. demo mode), **Administration** (user management, access control — per-screen **off / read / write** access by role and by individual user, with READ rendering a screen view-only and the gateway rejecting read-only writes, warehouse access, system info — version + health for every service/adapter, a live log viewer and a searchable full-page per-service daily log view, and a read-only **database console** — browse every service schema/table and run SELECT-only queries, guarded by a validator plus a READ ONLY transaction, timeout and row cap). A **collapsible** sidebar; a per-screen **help drawer** ("?" in the top bar) plus hover-help on editable fields. A global top-bar warehouse switcher auto-selects each user's default warehouse on login and scopes every warehouse-related screen (no UUID entry). **Multilanguage** (English, German, French, Spanish, Chinese): a top-bar language switcher, the whole SPA translated (lightweight dependency-free i18n in `ui/src/i18n/`, ~1,700 keys, English as the inline fallback), the choice remembered on the user's IAM account; backend output, server logs and the login screen stay English. Inbound/outbound are read-only (host owns orders). GTP queue drawer shows `REQUESTED` (in-storage), `IN_TRANSIT`, and `QUEUED` entries from flow, giving operators visibility of the full inbound pipeline; the GTP tote panel presents a full product card (tote glyph + HU code hero, SKU code with the name on its own line, item dimensions/weight and profile-metadata chips, via the master-data SKU card read). Transport click-to-trace dialog shows the per-HU transport trace timeline from flow when an HU id is available. Shared styled Select + searchable/sortable/paginated DataTable; warehouse-access user list paginates server-side. A global floating **AI assistant** chat widget (shown only when the feature is enabled) answers questions about your orders, transports, stock and handling units by calling the WCS's own read APIs under your permissions; a **Settings → AI Assistant** admin tab sets the Anthropic key, picks the model and enables it. In compose served by nginx on host `:443` (HTTPS forced). |

---

## Data model & schemas

Persistent state is split by ownership (build.md §5–§6, §16):

| Schema | Owner | Holds |
|---|---|---|
| `master_data` | master-data | Warehouse, SKU (global core) + per-warehouse `SkuProfile` overlays, `AttributeSchema`, DangerousGoods, UoM/bundles, Barcode + BarcodeType (incl. **GS1 barcode rules**), Location (+ cell coords + optional **`area_id`**), **`Area`** (first-class hierarchical zone, self-referential `parent_area_id`), **StorageBlock** (+ allowed HU types), HandlingUnitType, Equipment, **Shipper**, **ShippingService**, **Route**, **LabelTemplate** |
| `transaction_log` | txlog | Append-only event log — system of record (scaffolded separately) |
| `inventory` | inventory | Durable `stock` table (qty per SKU × batch × location × HU × status), `reservation`, and the **instance** data created at goods-in: `batch`/lot + `serial_unit`; `projection_offset` replay cursor |

Each owning service ships its schema as **Flyway** migrations and references rows in
other services' schemas by **UUID only — no cross-schema foreign keys** (§5.3), so a
service-local schema can later move to its own database unchanged. Batch/lot & serial
ownership is recorded in [`docs/adr/0001-inventory-data-ownership.md`](./docs/adr/0001-inventory-data-ownership.md).

The `inventory` service keeps `stock` in lockstep with the log by **consuming the
streamed transaction log** (Kafka topic `txlog.stream`, §9): movement events
(`GoodsReceived`, `PutawayCompleted`/`StockMoved`, `Picked`, `StockAdjusted`,
`StockStatusChanged`) move `stock.qty` and advance the `stock` projection cursor.
Application is **idempotent** — every applied event is recorded in a `processed_event`
inbox keyed on `event_id` (§5.5), so redelivery/replay is a no-op and the read model
can be rebuilt from the log.

---

## REST contracts & the goods-in → stock loop

API contracts live in [`contracts/openapi/`](./contracts/openapi/) (`txlog.yaml`,
`inventory.yaml`, `master-data.yaml`). All three are implemented (master-data exposes
full catalog CRUD; inventory exposes stock/availability/reservations; txlog exposes
append/query/replay) and are reachable directly on their service ports or through the
**gateway** at `:8080` (which routes `/api/<service>/**` — see `gateway/`). The first
end-to-end slice runs through three services:

1. **Append** a movement event to the log — `POST /api/txlog/events` (txlog writes the
   immutable event + an outbox row in one transaction).
2. The **outbox relay** publishes it to `txlog.stream` (Kafka).
3. The **inventory projection** consumes it and moves `stock.qty`.

```bash
# 1. record a goods-in (qty in the SKU base UoM; actor = who/what caused it, required for audit)
curl -X POST localhost:8086/api/txlog/events -H 'Content-Type: application/json' -d '{
  "streamId":"HU-1","eventType":"GoodsReceived","actor":"receiving-station-3",
  "payload":{"warehouseId":"<wh-uuid>","skuId":"<sku-uuid>","locationId":"<loc-uuid>","qty":12,"uomCode":"EACH"}}'

# 2. (relay publishes automatically) then read the projected stock / availability
curl "localhost:8082/api/inventory/availability?warehouseId=<wh-uuid>&skuId=<sku-uuid>"

# 3. allocate against available-to-promise
curl -X POST localhost:8082/api/inventory/reservations -H 'Content-Type: application/json' -d '{
  "warehouseId":"<wh-uuid>","skuId":"<sku-uuid>","qty":5,"orderRef":"ORD-1"}'
```

### Authentication (optional, off by default)

Auth is built but disabled so the stack runs without setup. The compose Keycloak imports an
**`openwcs` realm** (`platform/keycloak/openwcs-realm.json`) with the roles
`ADMIN`/`SUPERVISOR`/`OPERATOR`/`VIEWER`, a public client `openwcs-web`, and demo users
(`admin`/`admin`, `supervisor`/`supervisor`, `operator`/`operator`, `viewer`/`viewer` — **dev only**).

To turn it on, set `OPENWCS_SECURITY_ENABLED=true` on the gateway + services and point the
gateway's resource server at the realm
(`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=http://localhost:8180/realms/openwcs`).
Then the gateway validates the JWT, forwards `X-Auth-User`/`X-Auth-Roles`, and services
enforce the coded permissions (build.md §4.8). Get a token via password grant:
```bash
curl -s -d client_id=openwcs-web -d grant_type=password -d username=supervisor -d password=supervisor \
  http://localhost:8180/realms/openwcs/protocol/openid-connect/token | jq -r .access_token
```

**Self-service password change at login.** The login screen has a "Change password" form
(it also pops up automatically when a sign-in reports "account is not fully set up", i.e. a
temporary/forced password). It posts to the public `POST /api/iam/change-password`, which proves
identity with the current password and sets a new permanent one via a confidential Keycloak
service-account client `openwcs-iam` (least-privilege `manage-users`). This is the one
unauthenticated `/api/**` route, because such an account cannot obtain a token to change its
password in-app. The admin **Set password** dialog defaults **Temporary off** to avoid creating
that locked state by accident.

---

## Getting started

### Prerequisites
- **JDK 21** (for the Java services & gateway)
- **Go 1.25+** (for device adapters)
- **Node 18+ / npm** (for the UI)
- **Docker + Docker Compose** (for local infra)

### 1. Start local infrastructure
```bash
docker compose -f platform/docker-compose.yml up      # postgres, kafka, schema-registry, keycloak
```
Optionally start everything (infra + all app services, built from their Dockerfiles):
```bash
docker compose -f platform/docker-compose.yml --profile apps up --build
```

### 2. Run a Java service
The Gradle wrapper is committed, so use `./gradlew` directly (Gradle 8.10, JDK 21).
```bash
./gradlew :services:master-data:bootRun     # http://localhost:8081
curl localhost:8081/actuator/health
```
Build & test everything (Testcontainers tests need Docker — same as CI):
```bash
./gradlew build
```
On startup each persistent service applies its own **Flyway migrations**
(`src/main/resources/db/migration/`) against the Postgres from step 1, then
Hibernate runs in `validate` mode — migrations own the schema, never auto-DDL.
Datasource host/credentials are overridable via `SPRING_DATASOURCE_URL` /
`SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` (defaults target the
local compose Postgres).

### 3. Run a Go adapter
```bash
cd services/adapters/conveyor && go run .    # http://localhost:9091/healthz
```

### 4. Run the UI
```bash
cd ui && npm install && npm run dev          # http://localhost:5173 (proxies /api -> gateway)
```
The `--profile apps` compose stack also builds and serves the UI with nginx over
**HTTPS on `https://localhost/`** (port 443; HTTP/80 301-redirects to 443), proxying
`/api` to the gateway and `/realms`+`/admin` to Keycloak — no dev server needed when
running the full stack. With no real cert mounted, nginx self-signs one on startup
(expect a one-time browser warning on a raw IP); mount `tls.crt`/`tls.key` for a real
domain (see [`deploy/README.md`](./deploy/README.md)).

**Sign in** with `admin` / `admIn1!` (seeded in the `openwcs` realm). The compose
stack enables **edge security**: the gateway requires a Keycloak JWT on every
`/api/**` call. The UI logs in via the `openwcs-web` client and attaches the token
automatically. For direct API calls, fetch a token first:
```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=openwcs-web \
  -d username=admin -d 'password=admIn1!' \
  http://localhost:8180/realms/openwcs/protocol/openid-connect/token | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/master-data/warehouses
```
Every screen is registered in a **permission catalog** (`ui/src/auth/screens.ts`) and
gated by role (ADMIN/SUPERVISOR/OPERATOR/VIEWER), overridable per role/user via the
Access control screen.

### Try it with demo data

With no host connected, open **Settings → Demo mode** (ADMIN) to **seed a sample
catalog plus handling units and stock** for the current warehouse, then explore every
screen end-to-end. Turning demo mode off performs a **full operational reset** —
purging transactional data across services while keeping config, equipment and users.

### Public sandboxed live demo (no backend)

The marketing site ships a **fully client-side, read-only sandbox** of the real app at
**`/live-demo`**: no login, nothing saved (a reload resets it). It is a `VITE_DEMO=true` build of
this same `/ui` SPA, so it reuses every component, menu and screen. Behind the flag the SPA runs a
synthetic read-only session (Keycloak skipped, all menus visible, every write disabled) and a **mock
service layer (`ui/src/demo/mockApi.ts`) wired into the single `authFetch` chokepoint**, so **no
request ever leaves the browser**: GET responses come from committed fixtures snapshotted from the
demo box (`ui/src/demo/fixtures/`), unknown GETs return clean empty states, and writes are no-ops
except an in-memory pick engine that arms **5 totes at the goods-to-person station** (full pick, short
pick, exceptions). The demo carries a station of each pick layout (PP1 ONE_TO_ONE, PP2 ONE_TO_N with
7 slots, PW1 PUT_WALL with 6 cubbies) and renders the slot/light states (the PTL adapter is not in
the frontend-only demo). A reload restarts it. The **Dashboards and Reporting areas carry a fuller
curated sample dataset** (the fixtures are deterministically generated with ids that resolve to the
demo SKUs/locations) so those screens look populated rather than sparse, and the `/live-demo` page
opens with a short **"where to go" tour** pointing first-time visitors at the PP1 pick station and the
data-backed menus. **Operations → Hardware visualisation** is driven too: `ui/src/demo/demoTwin.ts`
single-sources a fixed layout (a recirculating conveyor loop around an ASRS rack, a sorter and two
pick stations) and fabricates the automation topology + device-task feed + twin read-models (tote
paths / AMR fleet / ASRS cranes) as pure functions of the wall clock, so the production 3D twin
renderer shows **totes driving round the loop**, robots roaming the aisles and a crane working the
rack, all in-browser.

Build and run it locally:
```bash
cd ui && npm run build:demo        # VITE_DEMO build, asset base /demo-app/, output dist/
# then copy dist/* into public/static/demo/, OR do both in one step from /public:
cd public && npm run build:demo-app
```
The page is **`/live-demo`** and the public Express server serves the bundle at **`/demo-app/`**
(static + SPA fallback) from `public/static/demo`. That bundle is **committed** to the repo (the
openwcs.ai host serves only `public/` and has no `ui/` build toolchain, so it cannot build it):
rebuild and re-commit it with `npm run build:demo-app` whenever a product change affects the demo.
CI also runs `npm run build:demo` so the demo build cannot silently break. Spec:
[`docs/public-live-demo-spec.md`](./docs/public-live-demo-spec.md).

### 5. Stand up a demo server (Ubuntu)
One command on a fresh Ubuntu 22.04/24.04 box installs Docker + JDK 21, clones,
builds the jars, and starts the whole stack:
```bash
curl -fsSL https://raw.githubusercontent.com/brettljausn-ai/openwcs/main/scripts/setup-demo.sh | sudo bash
```
To keep the server automatically up to date with `main` (poll-based timer or a
CI-gated GitHub Actions runner), see [`deploy/README.md`](./deploy/README.md).

---

## Contributing

Welcome! This is an open-source project and contributions are encouraged.

**Before you start:** read [`build.md`](./build.md) — it explains the bounded
contexts, data ownership rules, and the conventions that keep the system
coherent as it grows.

### Ground rules
- **One bounded context per service.** Don't fold responsibilities together —
  this app is expected to grow fast; new equipment → new adapter, new host system
  → new `integration-*` service.
- **Services share data only via events/APIs** — never by reaching into another
  service's database. Only master data + the transaction log live in the shared
  Postgres.
- **Contract-first.** Define/extend the event schema (`contracts/events/`) or
  OpenAPI spec (`contracts/openapi/`) before implementing.
- **Idempotent handlers.** Message and device delivery can repeat — dedupe on
  `eventId`/`correlationId`.

### Conventions
- **Java:** Java 21 + Spring Boot 3, package `org.openwcs.<service>`, actuator
  health probes, port from the table above.
- **Go adapters:** stdlib-first, implement the uniform device contract
  (build.md §8), expose `/healthz` + `/readyz`.
- **UI:** React + TypeScript; theme from `styling.md` tokens. Operator screens
  are legibility-first (no decorative blur/glow); management screens use the full
  aesthetic (build.md §11).

### Adding a new microservice
1. Create `services/<name>/` (Java) or `services/adapters/<name>/` (Go adapter).
2. Java: add `include 'services:<name>'` to `settings.gradle`; copy an existing
   service's `build.gradle` + `Application.java` + `application.yml`.
3. Add a route in `gateway/src/main/resources/application.yml`.
4. Add it to `platform/docker-compose.yml` (under the `apps` profile).
5. **Document it in [`build.md`](./build.md) and add a row to the table above.**

> 📌 **Keep this README current.** It's the welcome file for everyone touching
> the code — update the service table, ports, and getting-started steps whenever
> the architecture changes.

---

## Support openWCS

openWCS is independent, open-source software. If it's useful to you or your business, you can fund
its development on Patreon. Sponsorship pays for new features, documentation, testing, and support,
and keeps the project vendor-neutral and free for everyone.

**[Sponsor openWCS on Patreon »](https://www.patreon.com/c/karlfriesenbichler)**

Tiers run from **Backer** (individuals and developers) through **Sponsor** and **Business Sponsor**
to **Partner** (advisory and roadmap influence for integrators and enterprises). Sponsors are
credited in [`SPONSORS.md`](./SPONSORS.md); the full breakdown lives on the Patreon page.

---

## License

[GNU Affero General Public License v3.0](./LICENSE) (AGPL-3.0). Because a WCS is typically
run as a network service, AGPL's network-use clause ensures that anyone who operates a modified
openWCS for others must also make their changes available — keeping the platform open end to end.
