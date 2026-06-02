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
| gateway | 8080 | ✅ | Spring Cloud Gateway; routes `/api/<service>/**`; JWT validation (toggleable) + forwards `X-Auth-User`/`X-Auth-Roles`. |
| master-data | 8081 | ✅ | Catalog + outbound config (below). |
| inventory | 8082 | ✅ | Durable stock projection + availability/reservations (SKU- and location-scoped). |
| order-management | 8084 | ✅ | Orders (all types) + lifecycle + release management + line transactions; delegates allocation. |
| allocation | 8091 | ✅ | Pick-location allocation (UoM breakdown), cubing, batch picking. |
| txlog | 8086 | ✅ | Append-only event log + transactional outbox + relay to `txlog.stream`. |
| iam | 8087 | ✅ | openWCS authorization model: users → roles → coded permissions (Keycloak does auth). |
| process-engine / flow-orchestrator / notification / integration-sap / integration-manhattan | 8083/8085/8088–8090 | 🟦 | Scaffold (health/info only). |
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
| `orders` | order-management | outbound_order (all order types), order_line, order_line_transaction, order_outbox |
| `allocation` | allocation | order_allocation, allocation_line, pick_batch |
| `iam` | iam | role, role_permission, app_user, user_role |

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

## 6. order-management (orders, release, line transactions)

`/api/orders` (see `contracts/openapi/order-management.yaml`). Orders carry an
**`orderType`** — INBOUND | OUTBOUND | COUNT | ADJUSTMENT — with lines.

- **Lifecycle / release** (OUTBOUND): create → **release** (delegates to allocation →
  `ALLOCATED` / `NOT_FULFILLABLE`) → ship; cancel releases held reservations via allocation.
  **Release management**: `GET /release-queue?warehouseId=` (priority desc, then dispatch
  time) and `POST /release-due?warehouseId=&withinMinutes=`.
- **Line stock transactions** (every type): `POST /orders/{id}/lines/{lineNo}/transactions`
  records a receipt / pick / count / adjustment (type derived from `orderType`) and, in the
  **same local transaction**, writes an `order_outbox` row. A scheduled relay then **appends
  the matching event** (`GoodsReceived` / `Picked` / `StockAdjusted`) to the transaction log
  (correlation = order, stream = line) and records the `event_id` back on the line
  transaction. So the audit record + publish-intent commit atomically; the physical stock
  change is applied by the inventory projection. `postedQty` rolls up the signed quantities.
- **Audit:** `actor` (who) is **required** on every line transaction and on every logged
  event (`events.actor` is NOT NULL); until IAM/JWT is wired it is caller-asserted.
- The `outbound_order` table now holds all order types (legacy name retained; a rename is a
  documented follow-up).

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

## 7a. IAM & edge security

- **IAM service** (`/api/iam`, `contracts/openapi/iam.yaml`): the openWCS authorization
  model — users → roles → **code-defined permissions**
  (`org.openwcs.common.security.Permission`). Flyway seeds ADMIN/SUPERVISOR/OPERATOR/VIEWER.
  Manage users/roles, assign roles, read a user's **effective permissions** (union across
  roles). Authentication itself is Keycloak's job; this layers RBAC on top (build.md §4.8).
- **Gateway JWT** (build.md §12): with `openwcs.security.enabled=true` the gateway validates
  the JWT against the Keycloak realm, requires auth on `/api/**`, forwards the identity
  downstream as `X-Auth-User`/`X-Auth-Roles`, and **always strips client-supplied** versions
  (anti-spoofing). Off by default so the stack runs before a realm exists.
- **Authenticated actor**: order-management records a stock transaction's `actor` from the
  gateway-forwarded `X-Auth-User` (the request-body actor is only a fallback). So once
  security is on, every stock change is attributed to the authenticated user.
- **Per-endpoint RBAC**: `libs/common` carries a pure role→permission catalog (`RoleCatalog`,
  mirroring the IAM seed) + `AccessControl`. **order-management enforces** a coded
  `Permission` per endpoint (`ORDER_CREATE`, `ORDER_VIEW`, `ORDER_RELEASE`, `ORDER_CANCEL`,
  `ORDER_SHIP`, `ORDER_POST_TRANSACTION`) against the forwarded `X-Auth-Roles` via an
  `AccessGuard` — a no-op when `openwcs.security.enabled=false`, a 403 on a missing permission
  when on. This is the reference; other services adopt the same guard as a follow-up.

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

## 9. Testing (not yet executed here)

Testcontainers + JUnit 5 + Mockito. Run with `./gradlew :services:<name>:test` (Docker
required). Present: master-data (`MasterDataPersistenceTest`, `MasterDataApiTest`), txlog
(`TransactionLogServiceTest`, `OutboxRelayTest`), inventory (`InventoryPersistenceTest`,
`StockProjectionServiceTest`, `InventoryServiceTest`), allocation (`AllocationEngineTest`
— pure pick-breakdown / cubing / batch-merge logic; `AllocationServiceTest` — Testcontainers
+ mocked clients covering allocate → cancel-releases-reservations), order-management
(`OrderTransactionTest` — record + stage outbox atomically; `OrderTransactionRelayTest` —
relay appends + stamps event id; `OrderAuthorizationTest` — MockMvc: VIEWER blocked / SUPERVISOR
allowed to create with security on), iam (`IamServiceTest` — Testcontainers: seeded roles,
effective-permission resolution, catalog validation). No JVM/Gradle in the authoring
environment, so nothing has been compiled; treat the test suite as the gate. The gateway
JWT path needs a running Keycloak realm to exercise end-to-end.

## 10. Not built / known gaps

- Scaffold-only: process-engine, flow-orchestrator, notification, integration-*,
  Go adapters, UI.
- **Auth is scaffolded but off by default** — gateway JWT validation + per-endpoint RBAC are
  toggled by `openwcs.security.enabled` and need a Keycloak realm to exercise. **Enforcement
  is wired in order-management (reference); the other services still need the same
  `AccessGuard` applied** (mechanical follow-up). `RoleCatalog` reflects the shipped seed
  roles only — custom IAM roles would need a runtime IAM lookup. No mTLS yet.
- Cubing is volume+weight (not 3D bin-packing); shipper selection is default/first.
- Pick-type breakdown assumes stock is base-UoM and reads case size from the "CASE" UoM.
- No CI; no contract tests; events only on `txlog.stream` (no Avro/Schema-Registry, no
  master-data catalog events, no DLQs).
- Order status is not auto-advanced by postings (no auto-complete when `postedQty` meets
  `qty`); lifecycle orchestration is a follow-up.
- `actor` is recorded everywhere but is **caller-asserted** until IAM/JWT is wired (then the
  gateway/IAM supplies the authenticated principal and services read it from the security
  context rather than the request body).
- OpenAPI: `allocation.yaml` + `order-management.yaml` present; master-data
  shipper/fulfillment-config paths not yet added to `master-data.yaml`.
