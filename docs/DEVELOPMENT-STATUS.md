# openWCS — Development Status

_Last updated: 2026-06-08 (operations screens show codes not UUIDs; blind counts hide expected qty)_

Live status of the build against the roadmap in [`build.md` §15](../build.md). For what
the implemented parts actually do, see [`AS-BUILT.md`](./AS-BUILT.md).

**Legend:** ✅ functional · 🟡 partial · 🟦 scaffold (health/info only) · ⬜ not started

> ⚠️ Code is authored without a local JVM/Gradle, so it is not compiled here. **GitHub
> Actions CI** (`.github/workflows/ci.yml`) is now the verification gate: it runs
> `./gradlew build` (Testcontainers tests on Docker), builds the Go adapters + UI, and
> validates the OpenAPI specs. Watch the first run for compile errors that couldn't be
> caught locally.

---

## 1. Component status

| Component | Lang | Port | Status | Notes |
|---|---|---|---|---|
| gateway | Java | 8080 | ✅ | Routes `/api/<service>/**`; JWT validation (toggleable) + forwards/strips X-Auth-* identity. |
| master-data | Java | 8081 | ✅ | Catalog CRUD + outbound config: shippers, fulfillment config (pick types, cubing mode, batch config); dispatch reference data: shipping-service + route catalogs, label templates (+ ZPL/PDF render). **Host SKU sync** (`POST /skus/sync`): upsert (not full-catalog replace) — SKUs absent from the batch are left untouched; within each synced SKU the host is authoritative and its nested UoMs/barcodes fully replace what is stored. Whole batch in one transaction. **System config** flags in `system_configuration`: demo mode (`/api/master-data/demo`) and **hardware emulator** (`HARDWARE_EMULATOR_ENABLED`, `/api/master-data/emulator` get/enable/disable, ADMIN-gated, **OFF by default**); the Go adapters poll the emulator flag. |
| inventory | Java | 8082 | ✅ | Stock projection + SKU- and location-scoped availability/reservations. |
| order-management | Java | 8084 | ✅ | Orders of all types (INBOUND/OUTBOUND/COUNT/ADJUSTMENT), lifecycle, release mgmt, dispatch service/route + ship-to + label-template (validated against master-data), line stock transactions via a local outbox → txlog (audit: actor required); delegates allocation. **Demo quick-seed** `POST /api/orders/demo/seed {type INBOUND/OUTBOUND, count?:10}` (demo-mode-only; outbound seeds are small single-line orders that fit one shipper; drives the demo-only "Add 10 Orders" buttons on inbound/outbound). |
| allocation | Java | 8091 | ✅ | Pick-location allocation (UoM breakdown), cubing (APP multi-size largest-first / 1:1) with per-line carton traceability + per-carton dispatch labels (host barcode per shipper), batch picking. |
| slotting | Java | 8093 | ✅ | Put-away assignment for automated rack/GTP blocks (weighted scorer: velocity-to-exit · same-SKU lane consolidation · aisle redundancy cap/floor · fill balance; soft single-SKU-per-lane (mixing penalty, outweighable by balance) + hard lane-capacity/max-aisle constraints; direct-to-pick), manual pick-face slotting + min/max replenishment (opportunistic off-peak top-off), off-peak re-slotting. Goods-in `assignPutaway` BPMN delegate. ADR 0003. Move dispatch + txlog events pending. |
| gtp | Java | 8094 | ✅ | Goods-to-person station execution (ADR 0006): station + STOCK/ORDER node config, open order destinations (bind order HU + demand), present a stock HU → put-to-light put-list (greedy most-needed-first; one HU serves many orders = batch), confirm puts (full/short), complete destinations. ORDER_LOCATION (conveyor) + PUT_WALL (lit rack/AMR) destination topology share one engine. Orthogonal operating modes (PICKING / DECANTING / STOCK_COUNT / QC / MAINTENANCE): generalised work-cycle with mode-appropriate task lines + outcomes (decant-moves, count entries + variance, PASS/FAIL/HOLD verdicts, OK/DEFECTIVE/REPAIR checks). Seams: physical lights + HU retrieval (adapters/flow-orchestrator), demand auto-wire from allocation, stock→txlog, decant→slotting-putaway, count→inventory-StockAdjusted — all follow-up. |
| counting | Java | 8095 | ✅ | Cycle/stock counting: count tasks (LOCATION/SKU/ZONE/BLOCK scope, BLIND/VARIANCE), ABC-cadence schedule generator, capture → variance vs inventory snapshot → within-tolerance auto-approve (posts `StockAdjusted`) / out-of-tolerance recount. Seams (by id): GTP STOCK_COUNT station, cycle-count BPMN; adjustment via txlog. **Demo quick-seed** `POST /api/counting/demo/seed {count?:1}` (demo-mode-only; drives the demo-only "Add count task" button, one task per click). Operator count UI pending. |
| txlog | Java | 8086 | ✅ | Append-only events + outbox + relay. |
| process-engine | Java | 8083 | 🟡 | Embedded Flowable BPMN: deploy definitions, start/inspect instances; service-task delegates originate WCS work (sample goods-in dispatches a device task). Process designer UI pending. |
| flow-orchestrator | Java | 8085 | 🟡 | Device-task lifecycle + **vendor-neutral conveyor routing** (topology graph w/ per-node hardware address, HU route plans, shortest-path next-hop, **loop capacity HOLD/OVERFLOW**, **topology learning** from observed scans). **Automation-topology placement** (levels, placed equipment w/ envelope + conveyor path/sections, connections, function points; `GET`/`PUT /api/flow/automation/topology`) drives the 3D/2D editor in `ui` and the routing-graph projection; placed **GTP workstations** carry a `station_id` link to their gtp_station. The projection **auto-infers connections from geometry** (equipment nodes within ~1.5 m are linked — no hand-drawn connections; the editor's manual "Connect" tool was removed); function points can be placed **anywhere along a conveyor run** (split mid-section into named PLC node codes), and a divert/infeed stub that lands on another conveyor **merges onto it** automatically. GTP workstation role-interactions (STOCK/ORDER/DECANT, set in Properties) stay explicit. The 2D plan grid defaults to 1 m. Sniffer capture front-end + BPMN origination pending. |
| iam | Java | 8087 | ✅ | Authorization model: users → roles → coded permissions; seeded roles; effective-permission resolution; **per-user warehouse access** (allowed warehouses + one default; admin-only writes; the gateway enforces warehouse scope). (Keycloak does auth.) |
| notification | Java | 8088 | 🟦 | — |
| integration-sap | Java | 8089 | 🟡 | Host gateway: per-shipper dispatch-label barcode (`POST /labels`) + route feed (`POST /routes/sync`) + SAP order/ASN intake (`POST /orders`,`/asns`) translated into the canonical Host API (materials resolved to SKUs). |
| integration-manhattan | Java | 8090 | 🟡 | Host gateway: Manhattan order/ASN intake (`POST /orders`,`/asns`) translated into the canonical Host API (items resolved to SKUs). |
| integration-host | Java | 8092 | 🟡 | Canonical vendor-neutral Host API (`/api/host/**`): orders + ASNs + **SKU sync (list of SKUs with UoMs + barcodes inline)** + inventory adjustments in; confirmations out via pull (cursor feed) **and** webhook push; idempotency keys. |
| adapters/conveyor | Go | 9091 | 🟡 | Health + stub loop + `POST /tasks` device-task simulator (CONVEY/DIVERT/MERGE/SCAN). **Hardware emulator mode**: polls master-data's `HARDWARE_EMULATOR_ENABLED`; ON simulates commands + in-memory state + synthetic telemetry (never connects), OFF → FAILED ("hardware not connected") = the real-hardware seam; mode at `GET /` (`emulator` field) + `GET /state`. |
| adapters/{asrs,amr-geekplus,autostore} | Go | 9096, 9093, 9094 | 🟡 | Health + stub loop + **hardware emulator mode** (same flag): ON simulates each family (ASRS STORE/RETRIEVE, AMR TRANSPORT/MOVE, AutoStore BIN_STORE/BIN_RETRIEVE) with state + telemetry; OFF → FAILED (real-hardware seam); `GET /` mode + `GET /state`. (asrs on 9096 — 9092 is Kafka's.) |
| adapters/conveyor-sniffer | Go | 9095 | 🟡 | Ingests scan telegrams from defined source IPs (allowlist + decoder) → posts observations to the WCS for topology learning. |
| ui | React/TS | 5173 dev / 443 prod | 🟡 | React/Vite SPA: **Keycloak login** + sidebar **app shell** + **dashboard** + **screen permission catalog** (role/user-gated nav & routes, overridable via Access control). Built screens: inbound, outbound, counting, GTP operator console (single-active-session) + GTP workplace config (node locations searched by **location code**, not UUID; destination topology asked only for PICKING workplaces), transport, stock transactions, topology (React Flow), BPMN designer (bpmn-js), slotting, master data (SKU/UoM/barcode read-only), settings (incl. a **Cubing** tab: edit cubing rules + full shipper CRUD with fill rates, and a **Hardware emulator** toggle), user management, access control, **warehouse access** (per-user allowed warehouses + default), **system info** (version, health **and logs** of every service/adapter via the gateway's `GET /api/system/services` + `…/{name}/logs?date=` + `…/log-days`; build-info stamped into each jar/adapter at build; per-service **daily log files**, 14-day retention, on a shared `openwcs-logs` volume — Java via libs:common logback, Go via a daily writer). A global **top-bar warehouse switcher** auto-selects the user's default on login and scopes every warehouse-related screen (no UUID entry). A shared **`useCatalog`** hook resolves entity ids to human-readable codes across operations screens — counting (scope + capture dialog), outbound pick detail, stock-transaction From/To, and transport equipment column all display location codes, SKU codes (with description), and equipment codes rather than raw UUIDs. **Blind count** capture hides the Expected-qty column until reconciled. Inbound/outbound are **read-only** (host owns orders). Shared styled `Select` + searchable/sortable/paginated `DataTable`; warehouse-access user list paginates server-side (Keycloak). Dockerised (nginx) on host **:443 (HTTPS forced)** under compose `--profile apps` (proxies `/api`→gateway, `/realms`+`/admin`→Keycloak); **gateway JWT + warehouse scope enforced**. |
| libs/common | Java | — | ✅ | `EventEnvelope`. |

**Contracts:** OpenAPI ✅ master-data, inventory, txlog, allocation, order-management, iam,
flow-orchestrator, integration-sap, integration-manhattan, host-api, process-engine; ⬜ master-data
shipper/fulfillment-config paths, other services. Avro/Schema-Registry ⬜.

**Platform:** docker-compose ✅ (incl. allocation; Keycloak imports the `openwcs` realm).
**CI ✅** (GitHub Actions: Java build+test with Testcontainers, Go adapters, UI build, OpenAPI
validation). **Gradle wrapper committed.** **k8s starter manifests ✅** (`deploy/k8s/`; horizontal
scaling — see [`SCALING.md`](./SCALING.md)); Helm ⬜.

---

## 2. Roadmap progress (build.md §15)

| Phase | Status | Detail |
|---|---|---|
| **0 — Foundations** | ✅ | Repo + compose + shared schemas + txlog/outbox/relay + Kafka ✅; IAM model + gateway JWT + per-endpoint RBAC (all services) + inter-service identity propagation ✅ (toggleable); **CI ✅ (green), Keycloak `openwcs` realm ✅, gradle wrapper ✅**; **JWT edge-auth path exercised end-to-end against a live Keycloak realm (Testcontainers) ✅**. Remaining hardening: mTLS between services. |
| **1 — Master data + inventory MVP** | ✅ | Master Data ✅, Inventory projection ✅, log→projection loop proven ✅. |
| **2 — Process engine + one equipment family** | ✅ | flow-orchestrator device-task lifecycle + uniform device contract ✅, conveyor adapter ✅, DEVICE RBAC ✅, **process-engine (Flowable BPMN) ✅ with a sample goods-in process that originates a device task** ✅. Gap: a process designer UI + richer processes. |
| **3 — Outbound + more equipment** | 🟡 | **order-management ✅, allocation + cubing + batch picking + release management ✅, inventory reservation/ATP ✅, dispatch labels/services/routes ✅ (incl. integration-sap label-barcode + route feed).** Gaps: host-integration gateways translate into the canonical Host API but the real SAP/Manhattan wire protocols (OData/BAPI/IDoc, Manhattan REST) are still skeletal ⬜; more adapters ⬜. The **BPMN outbound process** is now ✅ (process-engine: release → allocate → gateway on fulfillability → pick/dispatch → route). |
| **3b — Inbound slotting & replenishment** | 🟡 | **Put-away engine ✅ (velocity-to-exit, soft single-SKU-per-lane, aisle redundancy + balance), HU type capabilities + per-area allowed-HU-types ✅, empty-HU far placement + LOW transport priority ✅, multi-compartment HUs (dominant velocity + SKU-set affinity) ✅, cell-as-location coords aisle/side/x/y/z ✅, manual pick-face min/max + opportunistic replenishment ✅, off-peak re-slotting ✅, self-taught recency-weighted ABC ✅, slotting UI ✅, goods-in put-away delegate ✅** (ADR 0003). Gaps: physical move dispatch ⬜, txlog audit events ⬜, inventory-truth occupancy + HU on-conveyor booking ⬜, goods-in decant step ⬜, deep-lane cell grouping ⬜. |
| **3c — Goods-to-person station execution** | 🟡 | **GTP station + node config, order-destination demand, present-stock → put-to-light put-list (batch: one stock HU → many orders), confirm/short puts, destination completion; ORDER_LOCATION + PUT_WALL topology ✅; orthogonal operating modes (PICKING / DECANTING / STOCK_COUNT / QC / MAINTENANCE) with a generalised task-line work-cycle + outcomes ✅** (ADR 0006). Gaps: physical put-lights + stock/order-HU retrieval (device adapters/flow-orchestrator) ⬜, demand auto-wire from allocation batches ⬜, stock decrement → txlog audit ⬜, decant→slotting-putaway + count→inventory-StockAdjusted wiring ⬜, station operator UI ⬜. |
| **4 — Counting & operations** | 🟡 | `StockAdjusted` projection ✅; **cycle/stock counting service ✅** (`counting`: count tasks, ABC-cadence schedule, blind/variance, recount + reconciliation → `StockAdjusted`); dashboards/alerting ⬜; operator count UI ⬜. |
| **5 — Hardening & scale** | ⬜ | DLQs, circuit breakers, replay tooling, perf, security review. |

---

## 3. Test coverage (implemented; not yet run here)

| Service | Tests | Kind |
|---|---|---|
| master-data | `MasterDataPersistenceTest`, `MasterDataApiTest`, `MasterDataRbacTest`, `DispatchCatalogApiTest`, `LabelTemplateApiTest`, `HostManagedMasterDataTest`, `SkuSyncApiTest`, `EmulatorModeTest` | Testcontainers + MockMvc (incl. RBAC: read=VIEW, write=EDIT; shipping-service + route catalogs; label-template CRUD + ZPL/PDF render; host SKU sync upsert semantics (absent SKUs untouched; within a synced SKU nested UoMs/barcodes fully replaced); **hardware-emulator flag**: defaults OFF, enable/disable ADMIN-gated, `GET /emulator` reflects state) |
| txlog | `TransactionLogServiceTest`, `OutboxRelayTest` | Testcontainers + Mockito |
| inventory | `InventoryPersistenceTest`, `StockProjectionServiceTest`, `InventoryServiceTest` | Testcontainers |
| allocation | `AllocationEngineTest`, `AllocationServiceTest` | Pure logic (incl. multi-size cubing: largest-first + line split across cartons with `lineNo`/`shipperUnitId` links) + Testcontainers (allocate → cancel releases reservations; oversized SKU → `CUBING_FAILED` + reservation released; per-carton dispatch labels with a host barcode per shipper) |
| order-management | `OrderTransactionTest`, `OrderTransactionRelayTest`, `OrderAuthorizationTest`, `DemoSeedTest` | Testcontainers + Mockito (outbox, relay, and per-endpoint RBAC: VIEWER 403 / SUPERVISOR 201; **demo seed** creates 10 sample INBOUND/OUTBOUND orders when demo mode on, rejected when off) |
| iam | `IamServiceTest` | Testcontainers (seeded roles, effective permissions, catalog validation) |
| flow-orchestrator | `DeviceTaskServiceTest`, `RoutingEngineTest`, `RoutingServiceTest`, `DiscoveryServiceTest` | Testcontainers + Mockito (device tasks; pure next-hop; routing through targets; loop HOLD/OVERFLOW; topology learning infers nodes/edges/targets from observations) |
| adapters/conveyor | `main_test.go` | Go httptest (`POST /tasks`: COMPLETED, FAILED on unknown command, 405 on GET; **emulator mode**: ON simulates + emits state/telemetry at `GET /state`, OFF → FAILED "hardware not connected", `GET /` reports the `emulator` field) |
| adapters/{asrs,amr-geekplus,autostore} | `main_test.go` | Go httptest (**emulator mode** per family: ON simulates STORE/RETRIEVE · TRANSPORT/MOVE · BIN_STORE/BIN_RETRIEVE with state/telemetry, OFF → FAILED) |
| adapters/conveyor-sniffer | `sniffer_test.go` | Go (decoder; IP allowlist; ingest→decode→forward end-to-end; HTTP observation post) |
| gateway | `GatewayAuthEndToEndTest` | Testcontainers (live Keycloak + imported `openwcs` realm): no token → 401, realm JWT → 200 + identity propagated, client-supplied `X-Auth-*` stripped (anti-spoof) |
| integration-sap | `LabelControllerTest`, `RouteFeedControllerTest`, `SapOrderControllerTest` | MockMvc (label-barcode; route-feed upsert; SAP order → Host API translation with material→SKU + unknown-material 422) |
| integration-manhattan | `ManhattanOrderControllerTest` | MockMvc (Manhattan order → Host API translation with item→SKU + unknown-item 422) |
| integration-host | `HostControllerTest`, `ConfirmationControllerTest`, `HostReferenceControllerTest`, `HostInventoryControllerTest`, `IdempotencyFilterTest`, `WebhookDispatcherTest` | Testcontainers + MockMvc + mocked clients (order/ASN mapping; confirmations cursor feed; SKU sync with UoMs + barcodes; adjustment → StockAdjusted append; `Idempotency-Key` replay; webhook push advances cursor) |
| process-engine | `ProcessEngineTest`, `OutboundProcessTest` | Testcontainers + Flowable (goods-in dispatches a device task; outbound releases → user task → dispatch, exercising delegates + a wait task) |
| gtp | `GtpContextTest`, `WorkCycleExecutionTest`, `OperatingModesTest` | Testcontainers (entity↔schema round-trip; one stock HU serves many destinations = batch; confirmations decrement + complete; short HU leaves surplus demand OPEN; short confirm → SHORT; BOTH topologies — ORDER_LOCATION + PUT_WALL — produce correct lit put instructions; **each operating mode** — DECANTING moves source→target + exposes put-away seam, STOCK_COUNT computes variance + exposes StockAdjusted seam, QC records PASS/FAIL/HOLD, MAINTENANCE records OK/DEFECTIVE/REPAIR; unsupported-mode rejected; setSupportedModes retains PICKING) |
| counting | `DemoSeedTest` | Testcontainers (**demo seed** creates sample count tasks over demo stock when demo mode on, rejected when no stock) |

---

## 4. Known gaps, caveats & tech debt

- **Uncompiled** — biggest caveat; run the test suite before trusting any of it.
- **`ddl-auto: validate` + JSONB** — usually fine on Hibernate 6; fallback is
  `ddl-auto: none` (tests still cover mappings).
- **No auth** anywhere.
- **Cubing** is volume+weight greedy (not 3D); it packs across multiple shipper sizes
  (largest-first, downsizing the last carton) but doesn't optimise for fewest cartons.
- **Pick-type breakdown** assumes base-UoM stock and reads case size from the "CASE" UoM;
  SPLIT_CASE is treated as eaches for quantity.
- **Allocation↔services** calls are synchronous REST; partial-failure compensation exists
  in the allocator but cross-service consistency is best-effort (no saga/outbox there yet).
- **Audit `actor`** is required on stock transactions + every logged event, but is
  caller-asserted until IAM/JWT supplies an authenticated principal.
- Order status is not auto-advanced by postings (no auto-complete on `postedQty` ≥ `qty`).
- Events only on `txlog.stream`; no consumer-driven contract tests (CI does validate the
  OpenAPI specs structurally). First CI run may surface compile errors not catchable locally.

---

## 5. Suggested next steps

1. **Stand up a Keycloak `openwcs` realm** + `gradle wrapper` so the JWT + RBAC path is
   exercisable end-to-end (enforcement is wired across all services but only verifiable with a
   realm). Consider resolving custom IAM roles at runtime and mTLS between services.
2. **End-to-end MockMvc tests** across the outbound slice (release → allocate → ship → cancel)
   and the inbound/count/adjust posting + relay flow.
3. **master-data catalog events** + shipper/fulfillment-config paths in `master-data.yaml`.
4. **Order auto-complete** when a line is fully posted (`postedQty` ≥ `qty`).
5. **process-engine (Flowable BPMN)** + goods-in/outbound processes that drive the
   flow-orchestrator device tasks (Phase 2 increment 2).

> Done since last revision: **Phase 2 increment 1** — flow-orchestrator now owns the device-task
> lifecycle over the uniform device contract (build.md §8): `POST/GET /api/flow/device-tasks`,
> the `flow.device_task` store, family→adapter routing via `HttpDeviceClient`, and DEVICE_VIEW /
> DEVICE_OPERATE RBAC. The **conveyor adapter** gained a `POST /tasks` simulator. `DeviceTaskServiceTest`
> (Testcontainers) and `main_test.go` (Go) added; `flow-orchestrator.yaml` OpenAPI spec added.
> The device contract is synchronous HTTP for now; async Kafka (`device.tasks`/`device.results`)
> is the production target.
