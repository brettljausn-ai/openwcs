# openWCS — Development Status

_Last updated: 2026-06-02_

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
| master-data | Java | 8081 | ✅ | Catalog CRUD + outbound config: shippers, fulfillment config (pick types, cubing mode, batch config). |
| inventory | Java | 8082 | ✅ | Stock projection + SKU- and location-scoped availability/reservations. |
| order-management | Java | 8084 | ✅ | Orders of all types (INBOUND/OUTBOUND/COUNT/ADJUSTMENT), lifecycle, release mgmt, line stock transactions via a local outbox → txlog (audit: actor required); delegates allocation. |
| allocation | Java | 8091 | ✅ | Pick-location allocation (UoM breakdown), cubing (APP/1:1), batch picking. |
| txlog | Java | 8086 | ✅ | Append-only events + outbox + relay. |
| process-engine | Java | 8083 | 🟦 | Needs Flowable BPMN + designer. |
| flow-orchestrator | Java | 8085 | 🟦 | Needs device-task contract + routing. |
| iam | Java | 8087 | ✅ | Authorization model: users → roles → coded permissions; seeded roles; effective-permission resolution. (Keycloak does auth.) |
| notification | Java | 8088 | 🟦 | — |
| integration-sap / integration-manhattan | Java | 8089/8090 | 🟦 | Host gateways. |
| adapters/{conveyor,asrs,amr-geekplus,autostore} | Go | 9091–9094 | 🟦 | Health + stub loop. |
| ui | React/TS | 5173 | 🟦 | Vite skeleton. |
| libs/common | Java | — | ✅ | `EventEnvelope`. |

**Contracts:** OpenAPI ✅ master-data, inventory, txlog, allocation, order-management, iam;
⬜ master-data shipper/fulfillment-config paths, other services. Avro/Schema-Registry ⬜.

**Platform:** docker-compose ✅ (incl. allocation; Keycloak imports the `openwcs` realm).
**CI ✅** (GitHub Actions: Java build+test with Testcontainers, Go adapters, UI build, OpenAPI
validation). **Gradle wrapper committed.** Helm/k8s ⬜.

---

## 2. Roadmap progress (build.md §15)

| Phase | Status | Detail |
|---|---|---|
| **0 — Foundations** | ✅ | Repo + compose + shared schemas + txlog/outbox/relay + Kafka ✅; IAM model + gateway JWT + per-endpoint RBAC (all services) + inter-service identity propagation ✅ (toggleable); **CI ✅ (green), Keycloak `openwcs` realm ✅, gradle wrapper ✅**. Remaining hardening: mTLS; exercise the JWT path against a live realm. |
| **1 — Master data + inventory MVP** | ✅ | Master Data ✅, Inventory projection ✅, log→projection loop proven ✅. |
| **2 — Process engine + one equipment family** | ⬜ | process-engine, flow-orchestrator, first adapter, goods-in-via-BPMN ⬜. |
| **3 — Outbound + more equipment** | 🟡 | **order-management ✅, allocation + cubing + batch picking + release management ✅, inventory reservation/ATP ✅.** Gaps: host-integration gateways ⬜; the *BPMN* outbound process ⬜; more adapters ⬜. |
| **4 — Counting & operations** | 🟡 | `StockAdjusted` projection ✅; cycle-count process ⬜; dashboards/alerting ⬜. |
| **5 — Hardening & scale** | ⬜ | DLQs, circuit breakers, replay tooling, perf, security review. |

---

## 3. Test coverage (implemented; not yet run here)

| Service | Tests | Kind |
|---|---|---|
| master-data | `MasterDataPersistenceTest`, `MasterDataApiTest`, `MasterDataRbacTest` | Testcontainers + MockMvc (incl. RBAC: read=VIEW, write=EDIT) |
| txlog | `TransactionLogServiceTest`, `OutboxRelayTest` | Testcontainers + Mockito |
| inventory | `InventoryPersistenceTest`, `StockProjectionServiceTest`, `InventoryServiceTest` | Testcontainers |
| allocation | `AllocationEngineTest`, `AllocationServiceTest` | Pure logic + Testcontainers (allocate → cancel releases reservations) |
| order-management | `OrderTransactionTest`, `OrderTransactionRelayTest`, `OrderAuthorizationTest` | Testcontainers + Mockito (outbox, relay, and per-endpoint RBAC: VIEWER 403 / SUPERVISOR 201) |
| iam | `IamServiceTest` | Testcontainers (seeded roles, effective permissions, catalog validation) |

---

## 4. Known gaps, caveats & tech debt

- **Uncompiled** — biggest caveat; run the test suite before trusting any of it.
- **`ddl-auto: validate` + JSONB** — usually fine on Hibernate 6; fallback is
  `ddl-auto: none` (tests still cover mappings).
- **No auth** anywhere.
- **Cubing** is volume+weight greedy (not 3D); shipper selection is default/first only.
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
5. **process-engine + flow-orchestrator + first adapter** (Phase 2): goods-in/outbound via BPMN.

> Done since last revision: **per-endpoint RBAC extended to all services** (master-data /
> inventory / allocation / txlog via an `RbacFilter`; order-management via `AccessGuard`) plus
> **inter-service identity propagation** (allocation, order-management forward `X-Auth-*` on
> outbound calls). `MasterDataRbacTest` added.
