# openWCS — As-Built Documentation

_Last updated: 2026-06-02_

What is **actually implemented** today (not the target architecture). Design intent:
[`build.md`](../build.md); decisions: [`docs/adr/`](./adr); live progress:
[`DEVELOPMENT-STATUS.md`](./DEVELOPMENT-STATUS.md).

> **Scope.** Six backend services are functional — **master-data**, **txlog**,
> **inventory**, **allocation**, **order-management**, and the **gateway**. Together they
> implement two vertical slices: (1) goods-in → stock (event log → projection), and
> (2) outbound order → release → pick-location allocation + cubing (+ batch picking). All
> other services and the device adapters are scaffolds (health endpoint only). **None of
> this has been compiled/run in the authoring environment** — see Testing.

---

## 1. Services

| Service | Port | Status | What it does |
|---|---|---|---|
| gateway | 8080 | ✅ | Spring Cloud Gateway; routes `/api/<service>/**` (env-overridable URIs). |
| master-data | 8081 | ✅ | Catalog + outbound config (below). |
| inventory | 8082 | ✅ | Durable stock projection + availability/reservations (SKU- and location-scoped). |
| order-management | 8084 | ✅ | Outbound orders + lifecycle + release management; delegates allocation. |
| allocation | 8091 | ✅ | Pick-location allocation (UoM breakdown), cubing, batch picking. |
| txlog | 8086 | ✅ | Append-only event log + transactional outbox + relay to `txlog.stream`. |
| process-engine / flow-orchestrator / iam / notification / integration-sap / integration-manhattan | 8083/8085/8087–8090 | 🟦 | Scaffold (health/info only). |
| adapters/{conveyor,asrs,amr-geekplus,autostore} | 9091–9094 | 🟦 | Go; health/readiness + stub loop. |
| ui | 5173 | 🟦 | Vite skeleton. |

All Java services: Java 21 / Spring Boot 3.3.2, PostgreSQL 16 via Flyway + JPA/Hibernate 6
(`ddl-auto: validate` — migrations own the schema), UUID keys, JSONB via `@JdbcTypeCode`.

---

## 2. Data ownership (schemas)

Cross-service references are **UUID columns with no cross-schema foreign keys** (build.md
§5.3); each service's store is independently ownable. Batch/lot & serial placement:
[ADR 0001](./adr/0001-inventory-data-ownership.md). Outbound allocation & cubing:
[ADR 0002](./adr/0002-outbound-allocation-and-cubing.md).

| Schema | Owner | Tables |
|---|---|---|
| `master_data` | master-data | warehouse, attribute_schema, sku, sku_profile, dangerous_goods, unit_of_measure, barcode_type, barcode, handling_unit_type, equipment, location, **shipper**, **warehouse_fulfillment_config** |
| `transaction_log` | txlog | events (append-only; UPDATE/DELETE blocked by trigger), outbox |
| `inventory` | inventory | batch, serial_unit, stock, reservation, projection_offset, processed_event |
| `orders` | order-management | outbound_order, order_line |
| `allocation` | allocation | order_allocation, allocation_line, pick_batch |

---

## 3. master-data (catalog + outbound config)

Full CRUD REST (`/api/master-data`, see `contracts/openapi/master-data.yaml`):

- **Catalog**: warehouses, SKUs (+ per-warehouse `SkuProfile` overlays, UoMs, barcodes,
  dangerous-goods), attribute-schemas, barcode-types, handling-unit-types, locations,
  equipment; SKU search/paging; bulk SKU import; soft-archive on delete.
- **Outbound config** (ADR 0002): **shippers** (`/shippers` — boxes/totes/bags with
  dims, tare, max fill level, max weight, per warehouse) and
  **`WarehouseFulfillmentConfig`** (`/warehouses/{id}/fulfillment-config` — allowed pick
  types CASE/SPLIT_CASE/EACH, cubing mode APP/ONE_TO_ONE, default shipper, and batch
  config: `batchEnabled`, `batchMaxPieces`, `batchMaxOrders`, `pickToteShipperId`).
- SKU/UoM **dimensions & weight** (per packaging level) drive cubing.

## 4. inventory (stock)

Durable `stock` (qty per warehouse × SKU × batch × location × HU × status), kept current
by consuming `txlog.stream` (idempotent via `processed_event`, cursor in
`projection_offset`). REST (`/api/inventory`): stock list, **availability / ATP**
(SKU-wide *and* location-scoped via `?locationId=`), reservation create/release/consume.
Reservations check ATP under a pessimistic lock so concurrent allocations can't over-commit.

## 5. txlog (system of record)

`POST /api/txlog/events` writes the immutable event + an outbox row in one tx; a scheduled
`OutboxRelay` publishes to `txlog.stream` in order. Query/replay by stream or global
position.

## 6. order-management (outbound lifecycle + release)

`/api/orders`: create order (lines, `priority`, `dispatchBy`), get/list, **release**,
cancel, ship. **Release management**: `GET /release-queue?warehouseId=` (most-urgent-first
— priority desc, then dispatch time) and `POST /release-due?warehouseId=&withinMinutes=`.
Release **delegates to the allocation service**; the order becomes `ALLOCATED` or
`NOT_FULFILLABLE` (the latter waits for instructions — re-release or cancel). Lifecycle:
CREATED → RELEASED → ALLOCATED / PARTIALLY_ALLOCATED / NOT_FULFILLABLE → SHIPPED / CANCELLED.

## 7. allocation (pick-location allocation + cubing + batching) — ADR 0002

`/api/allocation`:
- `POST /orders` — for each line, reserve against **PICK-purpose** locations
  (inventory location-scoped ATP) until met; compute the **pick-type UoM breakdown**
  (cases/eaches, gated by allowed pick types). If every line is reserved → **FULFILLABLE**
  with a pick plan + **cube plan**; else release all reservations → **NOT_FULFILLABLE**.
- **Cubing**: `APP` (greedy volume + weight against a chosen shipper, honouring max fill
  level / max weight / tare) or `ONE_TO_ONE` (validate & record host-supplied shippers).
- `POST /orders/{orderRef}/cancel` — release every held reservation for the order and
  mark the plan CANCELLED (kept for audit). order-management's cancel calls this.
- `POST /batches` — **batch (cluster) picking**: group eligible small orders
  (FULFILLABLE, pieces ≤ `batchMaxPieces`) into pick totes (≤ `batchMaxOrders`), merge
  their picks into one combined pick list, and record the per-order separation plan.

---

## 8. The two working vertical slices

**Goods-in → stock:** `POST /api/txlog/events {GoodsReceived}` → outbox relay →
`txlog.stream` → inventory projection → `stock`.

**Outbound:** `POST /api/orders` → `POST /api/orders/{id}/release` (or `/release-due`,
priority/dispatch-time ordered) → order-management calls allocation → allocation reserves
at PICK locations (inventory) + cubes → order `ALLOCATED`/`NOT_FULFILLABLE` →
`POST /api/allocation/batches` clusters small orders for picking.

---

## 9. Testing (not yet executed here)

Testcontainers + JUnit 5 + Mockito. Run with `./gradlew :services:<name>:test` (Docker
required). Present: master-data (`MasterDataPersistenceTest`, `MasterDataApiTest`), txlog
(`TransactionLogServiceTest`, `OutboxRelayTest`), inventory (`InventoryPersistenceTest`,
`StockProjectionServiceTest`, `InventoryServiceTest`), allocation (`AllocationEngineTest`
— pure pick-breakdown / cubing / batch-merge logic; `AllocationServiceTest` — Testcontainers
+ mocked clients covering allocate → cancel-releases-reservations). No JVM/Gradle in the
authoring environment, so nothing has been compiled; treat the test suite as the gate.

## 10. Not built / known gaps

- Scaffold-only: process-engine, flow-orchestrator, iam, notification, integration-*,
  Go adapters, UI.
- **No auth** (Keycloak runs in compose; no JWT/RBAC enforcement).
- Cubing is volume+weight (not 3D bin-packing); shipper selection is default/first.
- Pick-type breakdown assumes stock is base-UoM and reads case size from the "CASE" UoM.
- Allocation/order REST lack integration tests (only pure-logic + per-service persistence
  tests); no contract tests; no CI; events only on `txlog.stream` (no Avro/Schema-Registry,
  no master-data catalog events, no DLQs).
- OpenAPI: `allocation.yaml` present; master-data shipper/config paths and an
  order-management spec are not yet written.
