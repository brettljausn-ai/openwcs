# openWCS — Development Status

_Last updated: 2026-06-03 (goods-to-person station execution — ADR 0006)_

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
| master-data | Java | 8081 | ✅ | Catalog CRUD + outbound config: shippers, fulfillment config (pick types, cubing mode, batch config); dispatch reference data: shipping-service + route catalogs, label templates (+ ZPL/PDF render). |
| inventory | Java | 8082 | ✅ | Stock projection + SKU- and location-scoped availability/reservations. |
| order-management | Java | 8084 | ✅ | Orders of all types (INBOUND/OUTBOUND/COUNT/ADJUSTMENT), lifecycle, release mgmt, dispatch service/route + ship-to + label-template (validated against master-data), line stock transactions via a local outbox → txlog (audit: actor required); delegates allocation. |
| allocation | Java | 8091 | ✅ | Pick-location allocation (UoM breakdown), cubing (APP multi-size largest-first / 1:1) with per-line carton traceability + per-carton dispatch labels (host barcode per shipper), batch picking. |
| slotting | Java | 8093 | ✅ | Put-away assignment for automated rack/GTP blocks (weighted scorer: velocity-to-exit · same-SKU lane consolidation · aisle redundancy cap/floor · fill balance; soft single-SKU-per-lane (mixing penalty, outweighable by balance) + hard lane-capacity/max-aisle constraints; direct-to-pick), manual pick-face slotting + min/max replenishment (opportunistic off-peak top-off), off-peak re-slotting. Goods-in `assignPutaway` BPMN delegate. ADR 0003. Move dispatch + txlog events pending. |
| gtp | Java | 8094 | ✅ | Goods-to-person station execution (ADR 0006): station + STOCK/ORDER node config, open order destinations (bind order HU + demand), present a stock HU → put-to-light put-list (greedy most-needed-first; one HU serves many orders = batch), confirm puts (full/short), complete destinations. ORDER_LOCATION (conveyor) + PUT_WALL (lit rack/AMR) modes share one engine. Seams: physical lights + HU retrieval (adapters/flow-orchestrator), demand auto-wire from allocation, stock→txlog — all follow-up. |
| txlog | Java | 8086 | ✅ | Append-only events + outbox + relay. |
| process-engine | Java | 8083 | 🟡 | Embedded Flowable BPMN: deploy definitions, start/inspect instances; service-task delegates originate WCS work (sample goods-in dispatches a device task). Process designer UI pending. |
| flow-orchestrator | Java | 8085 | 🟡 | Device-task lifecycle + **vendor-neutral conveyor routing** (topology graph w/ per-node hardware address, HU route plans, shortest-path next-hop, **loop capacity HOLD/OVERFLOW**, **topology learning** from observed scans). Schematic editor lives in `ui`. Sniffer capture front-end + BPMN origination pending. |
| iam | Java | 8087 | ✅ | Authorization model: users → roles → coded permissions; seeded roles; effective-permission resolution. (Keycloak does auth.) |
| notification | Java | 8088 | 🟦 | — |
| integration-sap | Java | 8089 | 🟡 | Host gateway: per-shipper dispatch-label barcode (`POST /labels`) + route feed (`POST /routes/sync`) + SAP order/ASN intake (`POST /orders`,`/asns`) translated into the canonical Host API (materials resolved to SKUs). |
| integration-manhattan | Java | 8090 | 🟡 | Host gateway: Manhattan order/ASN intake (`POST /orders`,`/asns`) translated into the canonical Host API (items resolved to SKUs). |
| integration-host | Java | 8092 | 🟡 | Canonical vendor-neutral Host API (`/api/host/**`): orders + ASNs + SKU upserts + inventory adjustments in; confirmations out via pull (cursor feed) **and** webhook push; idempotency keys. |
| adapters/conveyor | Go | 9091 | 🟡 | Health + stub loop + `POST /tasks` device-task simulator (CONVEY/DIVERT/MERGE/SCAN). |
| adapters/{asrs,amr-geekplus,autostore} | Go | 9092–9094 | 🟦 | Health + stub loop. |
| adapters/conveyor-sniffer | Go | 9095 | 🟡 | Ingests scan telegrams from defined source IPs (allowlist + decoder) → posts observations to the WCS for topology learning. |
| ui | React/TS | 5173 | 🟡 | React/Vite; React Flow conveyor topology editor + bpmn-js BPMN process designer (model/deploy, start instances, complete user tasks). |
| libs/common | Java | — | ✅ | `EventEnvelope`. |

**Contracts:** OpenAPI ✅ master-data, inventory, txlog, allocation, order-management, iam,
flow-orchestrator, integration-sap, integration-manhattan, host-api, process-engine; ⬜ master-data
shipper/fulfillment-config paths, other services. Avro/Schema-Registry ⬜.

**Platform:** docker-compose ✅ (incl. allocation; Keycloak imports the `openwcs` realm).
**CI ✅** (GitHub Actions: Java build+test with Testcontainers, Go adapters, UI build, OpenAPI
validation). **Gradle wrapper committed.** Helm/k8s ⬜.

---

## 2. Roadmap progress (build.md §15)

| Phase | Status | Detail |
|---|---|---|
| **0 — Foundations** | ✅ | Repo + compose + shared schemas + txlog/outbox/relay + Kafka ✅; IAM model + gateway JWT + per-endpoint RBAC (all services) + inter-service identity propagation ✅ (toggleable); **CI ✅ (green), Keycloak `openwcs` realm ✅, gradle wrapper ✅**; **JWT edge-auth path exercised end-to-end against a live Keycloak realm (Testcontainers) ✅**. Remaining hardening: mTLS between services. |
| **1 — Master data + inventory MVP** | ✅ | Master Data ✅, Inventory projection ✅, log→projection loop proven ✅. |
| **2 — Process engine + one equipment family** | ✅ | flow-orchestrator device-task lifecycle + uniform device contract ✅, conveyor adapter ✅, DEVICE RBAC ✅, **process-engine (Flowable BPMN) ✅ with a sample goods-in process that originates a device task** ✅. Gap: a process designer UI + richer processes. |
| **3 — Outbound + more equipment** | 🟡 | **order-management ✅, allocation + cubing + batch picking + release management ✅, inventory reservation/ATP ✅, dispatch labels/services/routes ✅ (incl. integration-sap label-barcode + route feed).** Gaps: host-integration gateways translate into the canonical Host API but the real SAP/Manhattan wire protocols (OData/BAPI/IDoc, Manhattan REST) are still skeletal ⬜; the *BPMN* outbound process ⬜; more adapters ⬜. |
| **3b — Inbound slotting & replenishment** | 🟡 | **Put-away assignment engine (block-level slotting for ASRS/AutoStore/AMR-GTP; velocity-to-exit, multi-deep same-SKU lanes, aisle redundancy + balance) ✅, manual pick-face slotting + min/max + opportunistic replenishment ✅, off-peak re-slotting ✅, slotting UI ✅, goods-in put-away delegate ✅** (ADR 0003). Gaps: physical move dispatch ⬜, txlog audit events ⬜, inventory-truth occupancy + auto-ABC ⬜. |
| **3c — Goods-to-person station execution** | 🟡 | **GTP station + node config, order-destination demand, present-stock → put-to-light put-list (batch: one stock HU → many orders), confirm/short puts, destination completion; ORDER_LOCATION + PUT_WALL modes ✅** (ADR 0006). Gaps: physical put-lights + stock/order-HU retrieval (device adapters/flow-orchestrator) ⬜, demand auto-wire from allocation batches ⬜, stock decrement → txlog audit ⬜, station operator UI ⬜. |
| **4 — Counting & operations** | 🟡 | `StockAdjusted` projection ✅; cycle-count process ⬜; dashboards/alerting ⬜. |
| **5 — Hardening & scale** | ⬜ | DLQs, circuit breakers, replay tooling, perf, security review. |

---

## 3. Test coverage (implemented; not yet run here)

| Service | Tests | Kind |
|---|---|---|
| master-data | `MasterDataPersistenceTest`, `MasterDataApiTest`, `MasterDataRbacTest`, `DispatchCatalogApiTest`, `LabelTemplateApiTest` | Testcontainers + MockMvc (incl. RBAC: read=VIEW, write=EDIT; shipping-service + route catalogs; label-template CRUD + ZPL/PDF render) |
| txlog | `TransactionLogServiceTest`, `OutboxRelayTest` | Testcontainers + Mockito |
| inventory | `InventoryPersistenceTest`, `StockProjectionServiceTest`, `InventoryServiceTest` | Testcontainers |
| allocation | `AllocationEngineTest`, `AllocationServiceTest` | Pure logic (incl. multi-size cubing: largest-first + line split across cartons with `lineNo`/`shipperUnitId` links) + Testcontainers (allocate → cancel releases reservations; oversized SKU → `CUBING_FAILED` + reservation released; per-carton dispatch labels with a host barcode per shipper) |
| order-management | `OrderTransactionTest`, `OrderTransactionRelayTest`, `OrderAuthorizationTest` | Testcontainers + Mockito (outbox, relay, and per-endpoint RBAC: VIEWER 403 / SUPERVISOR 201) |
| iam | `IamServiceTest` | Testcontainers (seeded roles, effective permissions, catalog validation) |
| flow-orchestrator | `DeviceTaskServiceTest`, `RoutingEngineTest`, `RoutingServiceTest`, `DiscoveryServiceTest` | Testcontainers + Mockito (device tasks; pure next-hop; routing through targets; loop HOLD/OVERFLOW; topology learning infers nodes/edges/targets from observations) |
| adapters/conveyor | `main_test.go` | Go httptest (`POST /tasks`: COMPLETED, FAILED on unknown command, 405 on GET) |
| adapters/conveyor-sniffer | `sniffer_test.go` | Go (decoder; IP allowlist; ingest→decode→forward end-to-end; HTTP observation post) |
| gateway | `GatewayAuthEndToEndTest` | Testcontainers (live Keycloak + imported `openwcs` realm): no token → 401, realm JWT → 200 + identity propagated, client-supplied `X-Auth-*` stripped (anti-spoof) |
| integration-sap | `LabelControllerTest`, `RouteFeedControllerTest`, `SapOrderControllerTest` | MockMvc (label-barcode; route-feed upsert; SAP order → Host API translation with material→SKU + unknown-material 422) |
| integration-manhattan | `ManhattanOrderControllerTest` | MockMvc (Manhattan order → Host API translation with item→SKU + unknown-item 422) |
| integration-host | `HostControllerTest`, `ConfirmationControllerTest`, `HostReferenceControllerTest`, `HostInventoryControllerTest`, `IdempotencyFilterTest`, `WebhookDispatcherTest` | Testcontainers + MockMvc + mocked clients (order/ASN mapping; confirmations cursor feed; SKU upsert; adjustment → StockAdjusted append; `Idempotency-Key` replay; webhook push advances cursor) |
| process-engine | `ProcessEngineTest`, `OutboundProcessTest` | Testcontainers + Flowable (goods-in dispatches a device task; outbound releases → user task → dispatch, exercising delegates + a wait task) |
| gtp | `GtpContextTest`, `WorkCycleExecutionTest` | Testcontainers (entity↔schema round-trip; one stock HU serves many destinations = batch; confirmations decrement + complete; short HU leaves surplus demand OPEN; short confirm → SHORT; BOTH modes — ORDER_LOCATION + PUT_WALL — produce correct lit put instructions) |

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
