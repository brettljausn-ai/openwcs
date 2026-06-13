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
> 🌐 **Public product site:** [`public/`](./public) (static; deployable to GitHub Pages)
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
│   ├── flow-orchestrator/  txlog/  iam/  notification/
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
| `services/master-data` | Java | 8081 | SKUs, UoM/bundles, barcodes, locations, equipment, warehouses; one-call SKU card read (`GET /skus/{id}/card?warehouseId=`: identity + base-UoM dimensions + profile metadata) for operator screens; admin read-only DB console (`/admin/db`) |
| `services/inventory` | Java | 8082 | Real-time stock: durable stock table (current qty) kept in lockstep with the tx log; lock/unavailable; location-scoped reservations; FEFO/FIFO; reporting aggregates (stock-by-SKU available/allocated/unavailable split + per-block storage-density history with a daily ShedLock-guarded snapshot sweep) |
| `services/process-engine` | Java | 8083 | Admin-designed BPMN process definitions + execution (Flowable) |
| `services/order-management` | Java | 8084 | Outbound orders + fulfilment lifecycle + release management (priority/dispatch-time); delegates allocation; short allocate and release (supervisor decision: a short order picks the available qty and ships short, reported per line to the host via an OrderShipped confirmation); order-flow report (expected/active/started + per-day and hour-of-day intake histograms per direction) |
| `services/allocation` | Java | 8091 | Outbound prep: pick-location allocation (UoM breakdown), multi-size cubing into shippers (largest-first, per-line carton traceability) or host 1:1, batch picking; allow-short mode keeps partial reservations and cubes only the allocated quantities (FULFILLABLE_SHORT) |
| `services/slotting` | Java | 8093 | Inbound slotting & replenishment (ADR 0003): put-away assignment for automated rack/GTP blocks (velocity-to-exit, multi-deep same-SKU lanes, aisle redundancy + balancing), manual pick-face slotting with min/max + opportunistic replenishment, off-peak re-slotting |
| `services/gtp` | Java | 8094 | Goods-to-person station execution (ADR 0006): configure GTP stations + STOCK/ORDER nodes, present a stock HU to generate a put-to-light put-list across order destinations (ORDER_LOCATION conveyor / PUT_WALL rack topology), confirm puts, complete destinations — one stock HU serving many orders (batch); orthogonal **operating modes** freely enabled per station (PICKING / DECANTING / DECANT_MULTI / STOCK_COUNT / QC / MAINTENANCE) on a generalised task-line work-cycle; **ADR-0007 Phase 3c-1**: inbound queue source of truth relocated to flow-orchestrator — `POST /stations/{id}/queue` inbound enqueue deprecated (counting no longer calls it); `POST /queue/{id}/complete` fans out to flow `done` only — flow owns the return-to-storage leg and ONLY slotting picks the destination (gtp's parallel store-back removed) |
| `services/counting` | Java | 8095 | Cycle / stock counting: count tasks (location/SKU/zone/block scope, blind or variance), ABC-cadence scheduling, capture → variance vs inventory → reconcile (auto-approve within tolerance / recount) → `StockAdjusted`; **at-station blind count** (`POST /tasks/{id}/lines/{id}/station-count`): blind recount-until-two-agree state machine; confirmed variance posts `StockAdjusted` with reason `COUNTING`, actor attributed to the operator (`X-Auth-User` / `?operator=`, fallback `"system"`); **ADR-0007 Phase 3c-1**: count-tote routing issues a single `flow.requestPresentation(...)` — flow orchestrates RETRIEVE + CONVEY and meters the cap; no separate gtp-enqueue call |
| `services/flow-orchestrator` | Java | 8085 | Dispatches device tasks to adapters over the uniform device contract; vendor-neutral conveyor routing (topology graph + HU route plans + shortest-path next-hop on scan; diverts carry an optional topology-set default direction that unrouted totes follow, stopping at the divert when none is set); **hard-real-time scan path**: a per-warehouse in-memory graph snapshot with precomputed next-hop tables answers each scan in low single-digit milliseconds (counters/trace persist asynchronously; per-instance latency percentiles at `GET /api/flow/reports/decision-latency`); **automation-topology placement** (levels, placed equipment with envelopes + conveyor polyline sections, function points, ASRS ports; `GET`/`PUT /api/flow/automation/topology`) which drives the 2D/3D editor and projects the routing graph (connections auto-inferred from geometry, plus explicit node-level links anchored at exact path points, deduplicated against the inference); **flow-owned induction queue** (ADR-0007 Phase 3c-1): `POST /api/flow/induction/requests` (counting requests HU presentation; flow orchestrates RETRIEVE + CONVEY, cap metered at RETRIEVE dispatch; `REQUESTED` backlog uncapped), `GET /api/flow/induction/queue?workplaceId=` (full `{REQUESTED,IN_TRANSIT,QUEUED}` pipeline ordered by `arrival_seq`), `POST /api/flow/induction/entries/{id}/done`; **per-HU transport trace** (`GET /api/flow/hu-trace?huId=`): append-only timeline REQUESTED → RETRIEVED → INDUCTED → [SCANNED … RECIRCULATED/DIVERTED …] → ARRIVED → QUEUED → DONE — every conveyor scan from the live walk is recorded (Phase 3d); **return-to-storage (slotting-only)** CONVEY after workplace completion only slotting decides the destination (never the source slot); a slotting failure leaves the tote circulating on the conveyor (`awaiting_slot`) with a ~30s ShedLock retry sweep that assigns the slot mid-journey; HUs are booked to conveyor/workplace operational locations as they move; **ADR-0009 dig-out chain**: RELOCATE chain before a blocked RETRIEVE (slotting plans, flow executes, inventory location booked per step) |
| `services/txlog` | Java | 8086 | Append-only transaction log (shared Postgres) |
| `services/iam` | Java | 8087 | Authorization model: users → roles → coded permissions; per-user warehouse access (allowed warehouses + default; gateway-enforced scope) (Keycloak does auth) |
| `services/notification` | Java | 8088 | Operator alerts, exceptions, andon |
| `services/integration-sap` | Java | 8089 | Host gateway: SAP S/4HANA / HANA (OData/BAPI/RFC/IDoc) |
| `services/integration-manhattan` | Java | 8090 | Host gateway: Manhattan Active (REST) |
| `services/integration-host` | Java | 8092 | Canonical vendor-neutral Host API (`/api/host/**`): orders + ASNs + SKU sync (a list of SKUs with their UoM hierarchy and barcodes inline) in, confirmations out; vendor adapters translate into it |
| `services/equipment-emulator` | Go | 9097 | Single simulator for all device families; active when `HARDWARE_EMULATOR_ENABLED` is ON — flow-orchestrator routes device tasks here instead of the real adapters. Each command sleeps a realistic per-family duration before returning COMPLETED; `OPENWCS_EMULATOR_LATENCY_MS` overrides all commands (`0` = instant). `OPENWCS_EMULATOR_FAULT_RATE=N` injects deterministic faults (1 in every N tasks returns FAILED, result carries `fault: true`). `OPENWCS_EMULATOR_RECIRC_EVERY=N` recirculates every Nth CONVEY once before diverting, so arrival order visibly diverges from dispatch order (ADR-0007 R2); the result carries `recirculations` + `decisions` (sorter `RECIRCULATED`/`DIVERTED`) written to the HU trace by flow (R4). Latency, fault rate, and recirc rate are tunable at runtime via `GET`/`POST /config` (`{latencyOverrideMs, faultEvery, recircEvery}`). Daily logs are decision-grade: every line pairs the HU code with route, equipment and the why (recirculation policy, fault injection, hold reason); adapters log refused tasks at WARNING with reason and consequence. |
| `services/adapters/conveyor` | Go | 9091 | Conveyor adapter; `POST /tasks` returns FAILED ("hardware not connected") — real-hardware seam (emulation in `equipment-emulator`). |
| `services/adapters/asrs` | Go | 9096 | Shuttle/crane adapter; `POST /tasks` returns FAILED ("hardware not connected") — real-hardware seam. |
| `services/adapters/amr-geekplus` | Go | 9093 | Geek+ AMR adapter; `POST /tasks` returns FAILED ("hardware not connected") — real-hardware seam. |
| `services/adapters/autostore` | Go | 9094 | AutoStore grid adapter; `POST /tasks` returns FAILED ("hardware not connected") — real-hardware seam. |
| `services/adapters/conveyor-sniffer` | Go | 9095 | Captures scan telegrams from defined IPs → posts observations to the WCS for conveyor topology learning |
| `ui` | React/TS | 5173 dev / 443 prod | Operator + management SPA: Keycloak login, dashboard, and role/user-gated screens organised into five top-level sections — **Master data** (warehouses, SKUs, storage blocks, locations, handling-unit types, label templates — each catalog its own access-controllable screen), **Operations** (inbound/outbound orders, stock counting, GTP workplaces, transport, hardware twin — a live 3D view of the floor with conveyor bodies tinted with their live state colour (green functional / orange jam or heavy traffic / red fault), totes replaying the real scan trail in smooth interpolated motion along the conveyor geometry (queueing as a spaced line on the station's inbound conveyor, never overlapping) and ASRS storage fill at cell level, stock transactions, stock overview, handling-unit registry), **Engineering** (automation topology — 2D/3D editor with live link indicators where conveyors meet and a per-node Connections panel (closest-first explicit links) plus a click-to-open connection detail/edit dialog (full endpoint codes with the anchored node, distance, editable path points/label/status, delete), routing graph table, route test mode, BPMN processes, slotting, equipment), **Configuration** (GTP workplaces, settings incl. demo mode), **Administration** (user management, access control, warehouse access, system info — version + health for every service/adapter, a live log viewer and a searchable full-page per-service daily log view, and a read-only **database console** — browse every service schema/table and run SELECT-only queries, guarded by a validator plus a READ ONLY transaction, timeout and row cap). A **collapsible** sidebar; a per-screen **help drawer** ("?" in the top bar) plus hover-help on editable fields. A global top-bar warehouse switcher auto-selects each user's default warehouse on login and scopes every warehouse-related screen (no UUID entry). **Multilanguage** (English, German, French, Spanish, Chinese): a top-bar language switcher, the whole SPA translated (lightweight dependency-free i18n in `ui/src/i18n/`, ~1,700 keys, English as the inline fallback), the choice remembered on the user's IAM account; backend output, server logs and the login screen stay English. Inbound/outbound are read-only (host owns orders). GTP queue drawer shows `REQUESTED` (in-storage), `IN_TRANSIT`, and `QUEUED` entries from flow, giving operators visibility of the full inbound pipeline; the GTP tote panel presents a full product card (tote glyph + HU code hero, SKU code with the name on its own line, item dimensions/weight and profile-metadata chips, via the master-data SKU card read). Transport click-to-trace dialog shows the per-HU transport trace timeline from flow when an HU id is available. Shared styled Select + searchable/sortable/paginated DataTable; warehouse-access user list paginates server-side. In compose served by nginx on host `:443` (HTTPS forced). |

---

## Data model & schemas

Persistent state is split by ownership (build.md §5–§6, §16):

| Schema | Owner | Holds |
|---|---|---|
| `master_data` | master-data | Warehouse, SKU (global core) + per-warehouse `SkuProfile` overlays, `AttributeSchema`, DangerousGoods, UoM/bundles, Barcode + BarcodeType (incl. **GS1 barcode rules**), Location (+ cell coords), **StorageBlock** (+ allowed HU types), HandlingUnitType, Equipment, **Shipper**, **ShippingService**, **Route**, **LabelTemplate** |
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
