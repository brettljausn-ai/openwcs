# openWCS — As-Built Documentation

_Last updated: 2026-06-08 (station-count adjustments attributed to the operator, not a literal "station")_

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
| inventory | 8082 | ✅ | Durable stock projection + availability/reservations (SKU- and location-scoped). |
| order-management | 8084 | ✅ | Orders (all types) + lifecycle + release management + line transactions; delegates allocation. |
| allocation | 8091 | ✅ | Pick-location allocation (UoM breakdown), cubing, batch picking. |
| slotting | 8093 | ✅ | Put-away assignment for automated rack/GTP blocks (weighted scorer: velocity-to-exit · same-SKU lane consolidation · aisle redundancy · fill balance), manual pick-face slotting + min/max replenishment (opportunistic top-off), and off-peak re-slotting. ADR 0003. |
| gtp | 8094 | ✅ | Goods-to-person station execution: configure stations + STOCK/ORDER nodes, open order destinations (bind order HU + demand), present a stock HU → put-to-light put-list across destinations (one HU serves many orders: the batch), confirm puts (incl. short), complete destinations. ORDER_LOCATION (conveyor HU-in-location) and PUT_WALL (lit rack cubbies, typical AMR) destination topology share one engine. Orthogonal **operating modes** (PICKING / DECANTING / STOCK_COUNT / QC / MAINTENANCE): each cycle runs one and carries mode-appropriate task lines (decant-moves / count entries+variance / PASS-FAIL-HOLD verdicts / OK-DEFECTIVE-REPAIR checks); seams to slotting put-away (decant) and inventory StockAdjusted (count). **Station inbound queue** (`station_queue_entry`): transports route HUs to the station via `POST /stations/{id}/queue`; conveyor HUs arrive `IN_TRANSIT` (timed by distance ÷ 0.5 m/s; when neither `family` nor `distanceM` are supplied the queue falls back to the STOCK node's topology-projected `inboundDistanceM`) then transition to `QUEUED`; ASRS/AMR/AutoStore HUs arrive immediately `QUEUED`; operator works entries FIFO and completes them (`POST /queue/{id}/complete` → `DONE`). **Deactivate/drain control**: `POST /stations/{id}/deactivate` flips `acceptingWork=false` — station finishes its queued work but rejects new inbound HUs; `POST /stations/{id}/activate` reopens it. The `WorkplaceView` response includes `acceptingWork`, letting the console restore the drain state on reload. **In-transit capacity caps** (`maxInTransitPicking`, `maxInTransitOther`, configured via `POST /stations/{id}/capacity`): max simultaneous active inbound transports per mode class (default 4 PICKING / 2 OTHER); enqueue rejected with 409 when the cap is reached. **Topology node sync** (`POST /stations/{id}/nodes/sync`): replaces the station's STOCK/ORDER nodes from a topology-projected node set — nodes matched by code preserve their id + bound demand; the feeding conveyor distance (`inboundDistanceM`, `numeric(12,3)`) is carried from the topology function-point's `offsetM` and drives emulator queue arrival timing; called by flow-orchestrator on every topology projection (best-effort — a failure never aborts the routing projection). ADR 0006. |
| counting | 8095 | ✅ | Cycle / stock counting: count tasks (scope LOCATION/SKU/ZONE/BLOCK, BLIND vs VARIANCE), ABC-cadence schedule generator, capture counts → variance vs an inventory-expected snapshot → within-tolerance auto-approve (posts a `StockAdjusted` event) or out-of-tolerance recount; blind hides expected/variance. **Delete OPEN tasks** (`DELETE /tasks/{taskId}`): removes a count task and its lines while still OPEN; 409 once counting has begun. Seams: GTP STOCK_COUNT station + cycle-count BPMN (by id), adjustment via txlog. **ASRS count-tote routing** (emulator mode only): when a count task is created, the counting service looks up each cell's storage block (master-data), and for ASRS-family blocks (`SHUTTLE_ASRS`, `CRANE_ASRS`, `AUTOSTORE`, `AMR_GTP`) it creates a flow transport task (ASRS RETRIEVE via flow-orchestrator) and enqueues the tote to an active GTP `STOCK_COUNT` station; best-effort (no-op when emulator is off, no active counting station found, or any downstream call fails — the count task is always created). **At-station blind count** (`POST /tasks/{taskId}/lines/{lineId}/station-count {countedQty}`): operator submits their counted qty blind (never sees the system qty). A state machine on `count_line` (`station_count_state`: PENDING → RECOUNT → ACCEPTED | ADJUSTED) drives reconciliation: first count == expected → `ACCEPTED` (no adjustment); first count ≠ expected → `RECOUNT` (hold the count); on recount, matches expected → `ACCEPTED`; matches held count → confirmed variance: posts `StockAdjusted` (delta = counted − expected, reason `COUNTING`, actor = `X-Auth-User` header or `?operator=` query param, falls back to `"system"`) → `ADJUSTED`; differs from both → update hold, `RECOUNT` again. When all lines are terminal the task transitions to `RECONCILED`. Schema: `count_line` gains `station_last_count` + `station_count_state` (`V4__count_line_station_count.sql`). **Demo quick-seed** (demo-mode only): `POST /api/counting/demo/seed {warehouseId, count?:1}` builds sample count tasks over existing demo stock (cells sourced from the inventory stock overview), returns `{created}`, guarded to demo mode ON; drives the "Add count task" button (one task per click) shown on the stock-counting screen only when demo mode is on. |
| txlog | 8086 | ✅ | Append-only event log + transactional outbox + relay to `txlog.stream`. |
| iam | 8087 | ✅ | openWCS authorization model: users → roles → coded permissions; **per-user warehouse access** (allowed warehouses + default; the gateway enforces scope). (Keycloak does auth.) |
| flow-orchestrator | 8085 | 🟡 | Device-task lifecycle over the uniform device contract; routes to adapters by family (below). |
| integration-host | 8092 | 🟡 | Canonical vendor-neutral **Host API** (`/api/host/**`): orders + ASNs in, confirmations (cursor feed) out. |
| integration-sap | 8089 | 🟡 | Host gateway (skeleton): `POST /labels` (per-shipper dispatch-label barcode), `POST /routes/sync` (→ master-data Route catalog), and `POST /orders` + `/asns` translating SAP messages into the canonical Host API. |
| integration-manhattan | 8090 | 🟡 | Host gateway (skeleton): `POST /orders` + `/asns` translating Manhattan Active messages into the canonical Host API. |
| process-engine | 8083 | 🟡 | Embedded **Flowable BPMN** engine: deploy process definitions, start/inspect instances; service-task delegates originate WCS work (dispatch device task, assign route, release order, **assign put-away**, **allocate order**). Sample processes: goods-in, goods-in-putaway, cycle-count, and a complete **outbound** process (release → allocate → gateway → pick/dispatch → route). |
| notification | 8088 | 🟦 | Scaffold (health/info only). |
| adapters/conveyor | 9091 | 🟡 | Go; health/readiness + stub loop + `POST /tasks` device-task simulator. |
| adapters/{asrs,amr-geekplus,autostore} | 9096, 9093, 9094 | 🟦 | Go; health/readiness + stub loop. (asrs on 9096 — 9092 is Kafka's.) |
| adapters/conveyor-sniffer | 9095 | 🟡 | Go; ingests scan telegrams from defined source IPs (allowlist + pluggable decoder) and posts observations to the WCS for topology learning. |
| ui | 5173 dev / 443 prod | 🟡 | React/Vite SPA with **Keycloak login** (password grant via `openwcs-web`), a sidebar **app shell** + **dashboard**, and a **screen permission catalog** (`auth/screens.ts`) gating nav/routes by role, **overridable per role/user** via the Access control screen (`iam` `screen-access` store). Built screens: dashboard; **inbound orders**, **outbound orders**, **stock counting** (in-app confirm dialog for OPEN-task delete), **GTP operator console** (single-active-session; **queue-driven + auto-present**: polls the station queue and auto-presents the arrived head tote in PICKING mode — no manual form; **operating mode persisted per station** (localStorage, restored on reload); **active-tote panel** (HU code, SKU code + description, qty, SKU image; **fills the viewport when no cycle is in progress** — larger image, centred layout; idle **waiting-for-totes** state also full-screen); STOCK_COUNT mode also queue-driven — when the queue entry carries `countTaskId`/`countLineId` a dedicated **`CountPanel`** takes the operator's blind counted quantity (never shows expected), calls `POST .../station-count`, clears on `RECOUNT` and advances on `ACCEPTED`/`ADJUSTED`; falls back to a "Done counting" button when the link is absent; queue surfaced as a right-side fold-out **drawer**), **transport overview** (HU code column; origin/destination/next-hop columns resolve UUIDs to human-readable codes — location codes via `useCatalog`, GTP station codes via `listWorkplaces`; unknown UUIDs fall back to a short id; next-hop falls back to destination for ASRS/AMR/AutoStore direct-delivery tasks), **stock transactions**; **conveyor topology** (React Flow), **BPMN process designer** (bpmn-js), **slotting**; **master data** (SKU/UoM/barcode read-only — host-owned), **GTP workplace config**, **settings**; **user management** (Keycloak admin API), **access control**, **warehouse access** (per-user allowed warehouses + default), **system info** (version, health **and logs** of every service/adapter — gateway-aggregated via `GET /api/system/services`; per-service **daily log files** (14-day retention) written to a shared `openwcs-logs` volume by every service — Java via libs:common `logback-spring.xml`, Go adapters via a daily writer — and read back at `GET /api/system/services/{name}/logs?date=` with a day list at `…/log-days`; full-page log view at `/system-info/logs/:name` with filtering). A global **top-bar warehouse switcher** (`warehouse/WarehouseContext`) auto-selects the user's default on login and scopes every warehouse-related screen — no UUID entry anywhere. A shared **`useCatalog`** hook fetches location, SKU, and equipment catalogs from master-data on mount and resolves ids to human-readable codes across the operations screens — counting (scope column + capture dialog), outbound pick detail, stock-transaction From/To, and transport equipment column all display codes (location code, SKU code + description, equipment code) rather than raw UUIDs; transport origin/destination/next-hop additionally resolve against GTP station codes (`listWorkplaces`). **Blind count** capture withholds the Expected-qty column until a task is reconciled (operators must not see expected quantities during a blind count). **Inbound/outbound are read-only** (the host system owns orders — received/released/fulfilled here, not created). Shared UI primitives: a styled `Select` (replaces every native `<select>`), a `DataTable` (client-side search/sort/pagination), and a Keycloak-backed user autocomplete (Access control allow-list; default-warehouse in the user dialog). Warehouse access searches/paginates users **server-side** (Keycloak) to scale. In compose (`--profile apps`) built + served by nginx on host **:443 (HTTPS forced, 80→443)** (proxies `/api`→gateway, `/realms`+`/admin`→Keycloak). |

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
| `master_data` | master-data | warehouse, attribute_schema, sku, sku_profile, dangerous_goods, unit_of_measure, barcode_type, barcode, handling_unit_type (+ compartments/storable-in-automation/conveyable), equipment, location (+ block/aisle/lane-depth/distance-to-exit + **cell coords side/pos_x/pos_y/pos_z**), **storage_block** (+ allowed_hu_types), **shipper**, **warehouse_fulfillment_config**, **shipping_service**, **route**, **label_template** |
| `transaction_log` | txlog | events (append-only; UPDATE/DELETE blocked by trigger), outbox |
| `inventory` | inventory | batch, serial_unit, stock, reservation, projection_offset, processed_event |
| `orders` | order-management | outbound_order (all order types), order_line, order_line_transaction, order_outbox |
| `allocation` | allocation | order_allocation, allocation_line, pick_batch |
| `slotting` | slotting | storage_profile, pick_slot, block_policy, putaway_assignment (+ sku_ids for multi-compartment), replenishment_task, reslot_recommendation, sku_velocity (+ velocity offset/processed-event for the auto-ABC EWMA) |
| `gtp` | gtp | gtp_station (+ supported_modes + accepting_work + max_in_transit_picking/other), station_node, destination_demand, work_cycle (+ operating_mode, target_hu_id), put_instruction, task_line, **station_queue_entry** |
| `counting` | counting | count_task, count_line, count_schedule |
| `iam` | iam | role, role_permission, app_user, user_role, screen_access (+ _role/_user), user_warehouse |
| `flow` | flow-orchestrator | device_task, conveyor_node, conveyor_edge, conveyor_loop, conveyor_controller, topology_observation, **warehouse_level**, **placed_equipment** (+ path/sections/category/**station_id**), **equipment_function_point**, **equipment_connection** |
| `host_integration` | integration-host | idempotency_key, webhook_subscription |
| `ACT_*` (public) | process-engine | Flowable's own engine tables (it manages its schema; a documented exception to schema-per-service) |

---

## 3. master-data (catalog + outbound config)

Full CRUD REST (`/api/master-data`, see `contracts/openapi/master-data.yaml`):

- **Catalog**: warehouses, SKUs (+ per-warehouse `SkuProfile` overlays, UoMs, barcodes,
  dangerous-goods), attribute-schemas, barcode-types, handling-unit-types, locations,
  equipment; SKU search/paging; bulk SKU import; soft-archive on delete.
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
    may only seed onto a fresh, host-free system.
  - **Hardware emulator** (`HARDWARE_EMULATOR_ENABLED`, **default OFF**): a global flag, same
    key/value table, read/flipped via `GET /api/master-data/emulator` → `{enabled}`,
    `POST /api/master-data/emulator/enable` and `/disable` (ADMIN-gated on `X-Auth-Roles`). The Go
    device adapters poll it to decide whether to simulate or fail device commands (see §7b). It lets
    the whole automation flow run end-to-end with no physical hardware; an admin flips it OFF once
    real adapters are configured.

## 4. inventory (stock)

Durable `stock` (qty per warehouse × SKU × batch × location × HU × status), kept current
by consuming `txlog.stream` (idempotent via `processed_event`, cursor in
`projection_offset`). REST (`/api/inventory`): stock list, **availability / ATP**
(SKU-wide *and* location-scoped via `?locationId=`), reservation create/release/consume,
**per-location occupancy** (`POST /locations/occupied` → which of a set of locations physically
hold any stock row or handling unit; consumed by slotting put-away to skip occupied slots).
Reservations check ATP under a pessimistic lock so concurrent allocations can't over-commit.

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
  via allocation. **`CUBING_FAILED`** (a SKU is larger than the biggest carton) carries a
  `statusDetail` reason for the UI and can be re-released after the carton/SKU master data is
  fixed.
  **Release management**: `GET /release-queue?warehouseId=` (priority desc, then dispatch
  time) and `POST /release-due?warehouseId=&withinMinutes=`.
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
- **Hardware emulator mode** (all four Go adapter families: conveyor, asrs, amr-geekplus,
  autostore): each adapter polls master-data's `HARDWARE_EMULATOR_ENABLED` flag (§3) and exposes its
  current mode at `GET /` (new `"emulator":"ON|OFF"` field).
  - **ON**: the adapter **simulates** its family's device commands (conveyor CONVEY/DIVERT/MERGE/SCAN,
    ASRS STORE/RETRIEVE, AMR TRANSPORT/MOVE, AutoStore BIN_STORE/BIN_RETRIEVE), returning simulated
    COMPLETED results, maintains **in-memory device state**, emits **synthetic telemetry** on its loop,
    and **never opens a hardware connection**. The in-memory state + telemetry are readable at a new
    `GET /state`.
  - **OFF** (the default): `/tasks` returns **FAILED** ("hardware not connected"), since no
    real-hardware protocol client exists yet. This OFF branch is the deliberate **seam** for future
    real device protocol clients; an admin flips emulator OFF once a real adapter is wired.
  - Purpose: run the entire automation flow with zero physical hardware (evaluation, onboarding, CI).
- **RBAC catalog**: `DEVICE_VIEW`/`DEVICE_OPERATE` added to `Permission` + `RoleCatalog`
  (VIEWER sees, OPERATOR operates) and seeded in IAM (`iam/V2__device_permissions.sql`).

Not yet wired: a BPMN process (process-engine) that *originates* these tasks — today they are
driven directly via the API.

**Conveyor routing** (vendor-neutral; `/api/flow/conveyor`): the topology is a directed graph —
**nodes** (scan/decision points, each with a `hardwareAddress` and layout `posX/posY` for the
admin schematic editor) and **edges** (segments labelled with the `exitCode` the hardware applies
to traverse them, plus a routing `cost`). A handling unit carries a **route plan** — an ordered
list of target node codes (`POST /conveyor/routes`). On a scan (`POST /conveyor/routing-requests
{node, barcode}`), the WCS finds the HU's current target, computes the **next hop** toward it by
shortest path (`RoutingEngine`, Dijkstra — recomputed per scan, so topology changes reroute
automatically), and replies `{action: ROUTE, exitCode, toNode}`; as each target is reached the
plan advances, ending in `COMPLETE`. Unknown node / unreachable target → `EXCEPTION`; unknown
barcode → `NO_ROUTE`. The whole graph is loaded/saved via `GET`/`PUT /conveyor/topology` for the
admin editor. **Loop capacity**: a node can belong to a named loop with a max HU count; when a
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

The **station inbound queue** (`station_queue_entry`) is the mechanism through which any transport
routes a handling unit to a station for work:

- `POST /api/gtp/stations/{id}/queue {huId, huCode, skuId, skuCode, qty, mode, family, distanceM, countTaskId?, countLineId?}` —
  enqueue an HU. STOCK_COUNT entries carry `countTaskId`/`countLineId` (set by the counting service when routing a count tote, `V8__station_queue_count_link.sql`) so the station UI can POST the operator's blind count against the correct task line; absent on manually-enqueued totes. Conveyor transports supply `distanceM` and arrive `IN_TRANSIT` (timed: arrival = distanceM ÷ 0.5 m/s);
  when neither `family` nor `distanceM` are given the service falls back to the station's STOCK node's
  topology-projected `inboundDistanceM` (assuming CONVEYOR family) so the emulator can time tote arrivals
  without the caller needing to carry the distance. ASRS / AMR / AutoStore supply `null` distance and are
  immediately `QUEUED`. Rejected with 409 when the station is inactive (`acceptingWork=false`), is draining,
  does not support the requested mode, or the mode-class in-transit cap is reached.
- `POST /api/gtp/stations/{id}/nodes/sync {nodes[{role,code,locationId,putLightId,inboundDistanceM}]}` —
  replace the station's STOCK/ORDER nodes from a topology-projected node set. Nodes are matched by `code`
  so an existing node keeps its id and any bound order HU / demand; nodes no longer in the topology are
  removed only when they carry no open demand. The feeding conveyor distance (`inboundDistanceM`) is stored
  on the node (`station_node.inbound_distance_m`, `V7__station_node_distance.sql`) and is used by the queue
  timing fallback above. Called by flow-orchestrator's routing projection (best-effort).
- `GET /api/gtp/stations/{id}/queue` — the station's live inbound queue in arrival order (all
  IN_TRANSIT + QUEUED entries).
- `POST /api/gtp/queue/{entryId}/complete` — mark the worked-off entry `DONE`.

**Deactivate / drain control** (`acceptingWork` flag, `V5__station_in_transit_caps.sql`):
- `POST /api/gtp/stations/{id}/deactivate` — sets `acceptingWork=false`: the station finishes its
  already-queued work but rejects new inbound HUs (409). Useful for a clean switchover.
- `POST /api/gtp/stations/{id}/activate` — restores `acceptingWork=true`.
- `WorkplaceView` (the session + workplace API responses) now includes `acceptingWork` so the operator console can seed the drain-toggle state on page reload without an extra request.

**In-transit capacity caps** (`maxInTransitPicking`, `maxInTransitOther`, defaults 4 / 2):
- `POST /api/gtp/stations/{id}/capacity {maxInTransitPicking, maxInTransitOther}` — replaces the
  station's caps. Controls how many HUs may have an active inbound transport at once, split by mode
  class (PICKING vs all others). Schema: `V5__station_in_transit_caps.sql`; queue entries: `V6__station_queue.sql`.

**ASRS count-tote wiring (counting service):** when the hardware emulator is ON and a count task
is created for ASRS-family stock cells, the counting service calls the GTP queue endpoint to pin
the tote to an active `STOCK_COUNT` station and calls flow-orchestrator to create the retrieval
transport — so the move appears on the Transport screen. This is the first end-to-end wiring of
the counting→GTP seam (emulator mode only).

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
  with a `side` and an optional PLC `nodeCode`), and `equipment_connection`. Load/save the whole graph
  via `GET`/`PUT /api/flow/automation/topology?warehouseId=` (`AutomationTopologyDtos`).
- **Editor** (`ui/src/topology/`): a 3D view (`AutomationTopology3D`) and a top-down **2D plan**
  (`PlanEditor2D`) share one model — an edit in either shows in the other. Place equipment from the
  library; draw conveyor sections; drop **function points** (click a conveyor to place one *anywhere*
  along a run, or drag a palette marker onto it — named points show their name on the plan); divert/
  infeed points materialise a junction + a 1 m branch stub; ASRS IN/OUT ports snap to the rack
  footprint as owned conveyor stubs. The 2D grid defaults to **1 m**. Place a GTP workplace as a
  connectable **"workstation"** box (it has no master-data equipment — it references its `gtp_station`
  via `station_id`) and link it to specific conveyor function points with a **role** (STOCK / ORDER /
  DECANT) in the Properties panel ("Conveyor interactions").
- **Routing projection** (`RoutingProjectionService`, `POST /api/flow/automation/topology/project`):
  turns the placement model into the routable conveyor graph of §7b, **fully replacing** the
  warehouse's nodes/edges/loops. Path waypoints become nodes, directed sections become edges, function
  points alias a layout node (when on-point) or split a section (mid-run) into named PLC node codes,
  and a closed/cyclic conveyor becomes a capacity loop. **Connections are auto-inferred from
  geometry**: a node of one equipment within ~1.5 m of a node of another is linked (both directions)
  — so an ASRS infeed stub meeting a conveyor, or a divert stub landing on another conveyor, merges
  automatically with no hand-drawn connection. Hand-drawn connections are still honoured if present,
  but the editor no longer offers a "Connect" tool; GTP workstation role-interactions stay explicit.
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
(`MasterDataPersistenceTest`, `MasterDataApiTest`), txlog
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
  **decant** step, HU live-location/on-conveyor booking, FEFO replenishment sourcing and txlog audit
  events (`PutawayAssigned`/`ReplenishmentPlanned`/`ReslotRecommended`) are pending.
- **GTP station execution** (`gtp`, ADR 0006): built — STOCK + ORDER/PUT_WALL nodes, batch
  pick-and-put with put-to-light, ORDER_LOCATION vs PUT_WALL destination topology, plus orthogonal
  operating modes (PICKING / DECANTING / STOCK_COUNT / QC / MAINTENANCE) on a generalised work-cycle
  with mode-appropriate task lines + outcomes. **Station inbound queue** (IN_TRANSIT → QUEUED → DONE
  lifecycle), **deactivate/drain control**, and **in-transit capacity caps** are built.
  **Topology-projected station nodes** (`POST /stations/{id}/nodes/sync`, `inboundDistanceM`): the
  automation-topology projection automatically syncs STOCK/ORDER nodes into GTP stations and carries
  the feeding conveyor distance for emulator queue timing — built. Physical
  put-lights + picking-HU retrieval + demand auto-wire from allocation, and the
  decant→slotting-putaway integration is a seam (not built). **At-station blind count** (`POST
  /tasks/.../station-count`) posts `StockAdjusted` directly via the counting service — wired ✅. The
  legacy GTP work-cycle STOCK_COUNT task-line variance seam (`stockCountAdjustment`) is still not
  wired to inventory. ASRS count-tote routing to the station queue is wired via the counting service (emulator mode only).
- **Automation topology** (flow-orchestrator §7f): built — 3D/2D placement editor (levels, placed
  equipment with conveyor path/sections, function points placeable anywhere on a run, ASRS port stubs,
  GTP workstations linked to conveyor function points by role), and a deterministic **routing
  projection** that generates the §7b conveyor graph from the layout with geometry-inferred
  connections. On projection, STOCK/ORDER conveyor interactions of each GTP workstation are also
  **projected into the GTP station's nodes** (function-point `offsetM` → `inboundDistanceM` on the
  node) — this part is built. Not yet built: equipment family/type is not visible to the projection
  (classification is structural/by category), the projection is a manual action (no auto-reproject on
  save), and the placed-equipment ↔ live-device binding for physical device moves (driving real conveyor/
  ASRS moves through these nodes) is still the flow-orchestrator/adapter seam.
- Scaffold-only: notification, integration-*. The asrs/amr/autostore device adapters now carry a
  **hardware emulator mode** (admin toggle, simulates their family + telemetry/state, OFF by default;
  §3, §7b) but still have **no real-hardware protocol client** (emulator OFF is the seam for that).
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
