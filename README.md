# openwcs

[![CI](https://github.com/brettljausn-ai/openwcs/actions/workflows/ci.yml/badge.svg)](https://github.com/brettljausn-ai/openwcs/actions/workflows/ci.yml)

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
- **Equipment & host abstraction.** Uniform contracts mean the core never speaks
  a vendor protocol, an ERP dialect, or a WMS API directly.

---

## Repository layout

```
openwcs/
├── build.md              # architecture & build plan (read this first)
├── styling.md            # UI design system (tokens, components)
├── settings.gradle       # Gradle multi-project (Java services + gateway + libs)
├── build.gradle
├── platform/
│   └── docker-compose.yml   # local infra (postgres, kafka, schema-registry, keycloak) + app profile
├── contracts/
│   ├── events/           # Avro event schemas (shared, versioned)
│   └── openapi/          # REST API specs (generate clients/stubs)
├── libs/common/          # shared Java library (event envelope, domain types)
├── gateway/              # API gateway (Spring Cloud Gateway)
├── services/             # backend microservices (one dir per service)
│   ├── master-data/  inventory/  process-engine/  order-management/
│   ├── flow-orchestrator/  txlog/  iam/  notification/
│   ├── integration-sap/  integration-manhattan/     # host gateways
│   └── adapters/         # Go device adapters (one per equipment family)
│       ├── conveyor/  asrs/  amr-geekplus/  autostore/
└── ui/                   # React + TypeScript SPA (operator + management screens)
```

---

## Services & ports

| Service | Lang | Port | Responsibility |
|---|---|---|---|
| `gateway` | Java | 8080 | API gateway: routing + JWT validation (toggleable) + forwards `X-Auth-User`/`X-Auth-Roles` |
| `services/master-data` | Java | 8081 | SKUs, UoM/bundles, barcodes, locations, equipment, warehouses |
| `services/inventory` | Java | 8082 | Real-time stock: durable stock table (current qty) kept in lockstep with the tx log; lock/unavailable; location-scoped reservations; FEFO/FIFO |
| `services/process-engine` | Java | 8083 | Admin-designed BPMN process definitions + execution (Flowable) |
| `services/order-management` | Java | 8084 | Outbound orders + fulfilment lifecycle + release management (priority/dispatch-time); delegates allocation |
| `services/allocation` | Java | 8091 | Outbound prep: pick-location allocation (UoM breakdown), cubing (shippers / 1:1), batch picking |
| `services/flow-orchestrator` | Java | 8085 | Dispatches device tasks to adapters by equipment family over the uniform device contract (REQUESTED→DISPATCHED→COMPLETED/FAILED) |
| `services/txlog` | Java | 8086 | Append-only transaction log (shared Postgres) |
| `services/iam` | Java | 8087 | Authorization model: users → roles → coded permissions (Keycloak does auth) |
| `services/notification` | Java | 8088 | Operator alerts, exceptions, andon |
| `services/integration-sap` | Java | 8089 | Host gateway: SAP S/4HANA / HANA (OData/BAPI/RFC/IDoc) |
| `services/integration-manhattan` | Java | 8090 | Host gateway: Manhattan Active (REST) |
| `services/adapters/conveyor` | Go | 9091 | PLC conveyor adapter (raw TCP / OPC-UA); `POST /tasks` device-task simulator |
| `services/adapters/asrs` | Go | 9092 | Shuttle/crane adapter (telegram) |
| `services/adapters/amr-geekplus` | Go | 9093 | Geek+ RCS adapter (REST + WebSocket) |
| `services/adapters/autostore` | Go | 9094 | AutoStore grid adapter (REST) |
| `ui` | React/TS | 5173 | Operator (legibility-first) + management (full design system) SPA |

---

## Data model & schemas

Persistent state is split by ownership (build.md §5–§6, §16):

| Schema | Owner | Holds |
|---|---|---|
| `master_data` | master-data | Warehouse, SKU (global core) + per-warehouse `SkuProfile` overlays, `AttributeSchema`, DangerousGoods, UoM/bundles, Barcode + BarcodeType, Location, HandlingUnitType, Equipment |
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

## License

See [`LICENSE`](./LICENSE).
