# openWCS — Development Status

_Last updated: 2026-06-02_

Live status of the build against the roadmap in [`build.md` §15](../build.md). For what
the implemented parts actually do, see [`AS-BUILT.md`](./AS-BUILT.md).

**Legend:** ✅ functional · 🟡 partial · 🟦 scaffold (health/info only) · ⬜ not started

> ⚠️ Nothing in this changeset has been compiled or run in the authoring environment
> (no JVM/Gradle). The Testcontainers/Mockito tests are the intended verification gate —
> run `./gradlew build` with Docker available.

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

**Platform:** docker-compose ✅ (incl. allocation). CI ⬜. Helm/k8s ⬜. Gradle wrapper jar
not committed (`gradle wrapper` once).

---

## 2. Roadmap progress (build.md §15)

| Phase | Status | Detail |
|---|---|---|
| **0 — Foundations** | 🟡 | Repo + compose + shared schemas + txlog/outbox/relay + Kafka ✅; **IAM authorization model ✅, gateway JWT validation + identity propagation ✅ (toggleable)**. Gaps: Keycloak realm + per-endpoint RBAC enforcement in services, CI ⬜. |
| **1 — Master data + inventory MVP** | ✅ | Master Data ✅, Inventory projection ✅, log→projection loop proven ✅. |
| **2 — Process engine + one equipment family** | ⬜ | process-engine, flow-orchestrator, first adapter, goods-in-via-BPMN ⬜. |
| **3 — Outbound + more equipment** | 🟡 | **order-management ✅, allocation + cubing + batch picking + release management ✅, inventory reservation/ATP ✅.** Gaps: host-integration gateways ⬜; the *BPMN* outbound process ⬜; more adapters ⬜. |
| **4 — Counting & operations** | 🟡 | `StockAdjusted` projection ✅; cycle-count process ⬜; dashboards/alerting ⬜. |
| **5 — Hardening & scale** | ⬜ | DLQs, circuit breakers, replay tooling, perf, security review. |

---

## 3. Test coverage (implemented; not yet run here)

| Service | Tests | Kind |
|---|---|---|
| master-data | `MasterDataPersistenceTest`, `MasterDataApiTest` | Testcontainers (persistence + MockMvc) |
| txlog | `TransactionLogServiceTest`, `OutboxRelayTest` | Testcontainers + Mockito |
| inventory | `InventoryPersistenceTest`, `StockProjectionServiceTest`, `InventoryServiceTest` | Testcontainers |
| allocation | `AllocationEngineTest`, `AllocationServiceTest` | Pure logic + Testcontainers (allocate → cancel releases reservations) |
| order-management | `OrderTransactionTest`, `OrderTransactionRelayTest` | Testcontainers (post → record + stage outbox atomically) + Mockito (relay appends + stamps event id) |
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
- Events only on `txlog.stream`; no contract tests; no CI.

---

## 5. Suggested next steps

1. **Per-endpoint RBAC enforcement** in services — read `X-Auth-Roles` / call IAM effective
   permissions and check the coded `Permission` per endpoint (catalog + gateway auth exist;
   enforcement does not). Plus a Keycloak `openwcs` realm + `gradle wrapper` so the JWT path
   is exercisable end-to-end.
2. **End-to-end MockMvc tests** across the outbound slice (release → allocate → ship → cancel)
   and the inbound/count/adjust posting + relay flow.
3. **master-data catalog events** + shipper/fulfillment-config paths in `master-data.yaml`.
4. **Order auto-complete** when a line is fully posted (`postedQty` ≥ `qty`).
5. **process-engine + flow-orchestrator + first adapter** (Phase 2): goods-in/outbound via BPMN.

> Done since last revision: **IAM service** (users → roles → coded permissions, seeded roles,
> effective permissions; `IamServiceTest`); **gateway JWT validation + identity propagation**
> (toggleable, forwards/strips `X-Auth-*`); order-management records the **authenticated
> actor** from the forwarded identity.
