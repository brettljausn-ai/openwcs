# openwcs

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
| `gateway` | Java | 8080 | API gateway: ingress, auth, routing |
| `services/master-data` | Java | 8081 | SKUs, UoM/bundles, barcodes, locations, equipment, warehouses |
| `services/inventory` | Java | 8082 | Real-time stock: durable stock table (current qty) kept in lockstep with the tx log; lock/unavailable; reservations; FEFO/FIFO |
| `services/process-engine` | Java | 8083 | Admin-designed BPMN process definitions + execution (Flowable) |
| `services/order-management` | Java | 8084 | Inbound ASNs + outbound orders; fulfilment lifecycle |
| `services/flow-orchestrator` | Java | 8085 | Turns process steps into device tasks; routing, contention |
| `services/txlog` | Java | 8086 | Append-only transaction log (shared Postgres) |
| `services/iam` | Java | 8087 | Users, MS Entra SSO + local accounts, RBAC (coded permissions) |
| `services/notification` | Java | 8088 | Operator alerts, exceptions, andon |
| `services/integration-sap` | Java | 8089 | Host gateway: SAP S/4HANA / HANA (OData/BAPI/RFC/IDoc) |
| `services/integration-manhattan` | Java | 8090 | Host gateway: Manhattan Active (REST) |
| `services/adapters/conveyor` | Go | 9091 | PLC conveyor adapter (raw TCP / OPC-UA) |
| `services/adapters/asrs` | Go | 9092 | Shuttle/crane adapter (telegram) |
| `services/adapters/amr-geekplus` | Go | 9093 | Geek+ RCS adapter (REST + WebSocket) |
| `services/adapters/autostore` | Go | 9094 | AutoStore grid adapter (REST) |
| `ui` | React/TS | 5173 | Operator (legibility-first) + management (full design system) SPA |

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
> First time only: this repo ships `gradle-wrapper.properties` but not the
> wrapper jar. Generate it once with a local Gradle (`gradle wrapper`), then use
> `./gradlew` thereafter.
```bash
./gradlew :services:master-data:bootRun     # http://localhost:8081
curl localhost:8081/actuator/health
```

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
