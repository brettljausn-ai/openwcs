# openwcs — Build & Architecture Plan

An open-source **Warehouse Control System (WCS)** that orchestrates automated
material-handling equipment and manages the flow and storage of goods inside an
automated warehouse.

This document is the architectural blueprint and build plan: what we are
building, how the pieces fit, the technology choices, and the order in which to
deliver it.

---

## 1. Scope & Positioning

A WCS sits **between** the business-level Warehouse Management System (WMS) and
the **physical automation equipment** (PLCs, robot fleet managers, storage grid
controllers). It is real-time, equipment-aware, and responsible for *executing*
and *coordinating* physical movement — not for sales orders, billing, or yard
management.

```
        ┌────────────────────┐
        │   WMS / ERP / OMS   │   business orders, inventory ownership
        └─────────┬──────────┘
                  │  (orders, ASNs, stock sync)
        ┌─────────▼──────────┐
        │       openwcs       │   ← THIS SYSTEM
        │  flow + storage +   │   process execution, routing, equipment control
        │  equipment control  │
        └─────────┬──────────┘
                  │  (device protocols)
   ┌──────┬───────┼────────┬──────────┐
   ▼      ▼       ▼        ▼          ▼
Conveyor ASRS   AMR      AutoStore  Pick/Pack
(PLC)  (shuttle/ (Geek+) (grid)     stations
       crane)
```

### In scope
- Master data for SKUs, units of measure / bundles, barcodes & barcode types, locations.
- Stock management within the automated area (real-time inventory state).
- Goods-in (receiving), outbound order processing, cycle counting, and other
  **admin-designable** processes.
- Equipment integration: conveyors, ASRS (shuttles & cranes), AMRs (e.g. Geek+),
  storage systems (e.g. AutoStore).
- Material-flow routing and task orchestration across equipment.

### Out of scope (delegated to WMS/ERP)
- Order capture, customer/supplier master, billing, labor management,
  cross-site/network inventory ownership.

### Glossary
| Term | Meaning |
|------|---------|
| **WCS** | Warehouse Control System — this project |
| **WMS** | Warehouse Management System — business layer above the WCS |
| **ASRS** | Automated Storage & Retrieval System (shuttles, stacker cranes) |
| **AMR** | Autonomous Mobile Robot (e.g. Geek+ goods-to-person robots) |
| **SKU** | Stock Keeping Unit |
| **UoM** | Unit of Measure |
| **LU / HU** | Load Unit / Handling Unit (tote, tray, carton, pallet) |
| **ASN** | Advance Shipping Notice (inbound expectation) |
| **PLC** | Programmable Logic Controller (drives conveyors/cranes) |

---

## 2. Architecture Principles

1. **Microservices, independently deployable.** Each service owns one bounded
   context, its own lifecycle, and its own operational data store.
2. **Event-driven core.** Services communicate asynchronously over an event
   backbone; synchronous REST/gRPC only for queries and commands that need an
   immediate answer.
3. **Shared Postgres is deliberately narrow.** The common PostgreSQL database is
   used **only** for (a) **master data** and (b) the **transaction log**. All
   other operational/transient state lives in service-local stores. See §5.
4. **Transaction log as source of truth.** Every state-changing physical event
   (receipt, putaway, move, pick, count adjustment) is appended to an immutable
   transaction log. Inventory and other read models are **projections** rebuilt
   from this log → full auditability and replay.
5. **Equipment abstraction.** Each equipment family is hidden behind a uniform
   internal contract (a *Device Adapter*), so the flow/orchestration layer never
   speaks a vendor protocol directly.
6. **Processes are data, not code.** Goods-in / outbound / cycle-count flows are
   authored by an admin in a visual designer and executed by a workflow engine.
   Adding a process variant must not require a redeploy.
7. **Idempotency & at-least-once delivery.** All command/event handlers are
   idempotent (dedupe keys), because device and message delivery can repeat.
8. **Polyglot at the adapter boundary, opinionated core.** Core domain services
   are **Java** for consistency; **device adapters default to Go** (Rust for the
   rare latency-/safety-critical case). The uniform device contract (§8) keeps
   the orchestrator indifferent to an adapter's language. See §10.

---

## 3. High-Level Architecture

```
                         ┌─────────────────────────────────────────┐
                         │            Admin / Operator UI            │
                         │      (React SPA — process designer,       │
                         │       dashboards, master data mgmt)       │
                         └───────────────────┬───────────────────────┘
                                             │ HTTPS
                                  ┌──────────▼──────────┐
                                  │     API Gateway     │  authn/z, routing, rate-limit
                                  └──────────┬──────────┘
            ┌───────────────┬───────────────┼────────────────┬──────────────────┐
            ▼               ▼               ▼                ▼                  ▼
   ┌──────────────┐ ┌──────────────┐ ┌─────────────┐ ┌────────────────┐ ┌──────────────┐
   │ Master Data  │ │  Inventory   │ │  Process /  │ │ Order Mgmt     │ │  Material    │
   │   Service    │ │   Service    │ │  Workflow   │ │ (in/outbound)  │ │  Flow / Task │
   │              │ │ (read model) │ │   Engine    │ │                │ │ Orchestrator │
   └──────┬───────┘ └──────┬───────┘ └──────┬──────┘ └───────┬────────┘ └──────┬───────┘
          │                │                │                │                  │
          └────────────────┴────────────────┴────────────────┴──────────────────┘
                                             │
                            ┌────────────────▼────────────────┐
                            │       Event Backbone (Kafka)      │  events + transaction-log stream
                            └────────────────┬─────────────────┘
                                             │
            ┌──────────────┬─────────────────┼──────────────────┬─────────────────┐
            ▼              ▼                  ▼                  ▼                 ▼
   ┌─────────────┐ ┌──────────────┐  ┌───────────────┐ ┌──────────────┐ ┌────────────────┐
   │  Conveyor   │ │    ASRS      │  │     AMR       │ │  AutoStore   │ │  (future device│
   │  Adapter    │ │   Adapter    │  │   Adapter     │ │   Adapter    │ │   adapters…)   │
   │ (PLC/OPC-UA)│ │(shuttle/crane)│ │ (Geek+ RCS)   │ │ (grid API)   │ │                │
   └──────┬──────┘ └──────┬───────┘  └──────┬────────┘ └──────┬───────┘ └────────────────┘
          ▼               ▼                 ▼                 ▼
      PLC / fieldbus   WCS/SCADA        Robot fleet mgr   AutoStore controller

   Shared PostgreSQL ──► [ master_data schema ]  +  [ transaction_log schema ]   (only these two)
   Per-service stores ─► Inventory read model, workflow instances, order state, adapter state, …
```

---

## 4. Services (Bounded Contexts)

Each service below lists its **responsibility**, **data ownership**, key
**APIs**, and the **events** it publishes/consumes.

### 4.1 Master Data Service
- **Responsibility:** Authoritative catalog: SKUs, units of measure & bundle
  hierarchies, barcodes & barcode types, location/topology master (each location
  classified by **type** — bin, pallet, free space, … — and **purpose** —
  storage, transport, staging, …), equipment registry, handling-unit types.
- **Data:** Shared Postgres `master_data` schema (this is one of the only two
  things allowed in the shared DB).
- **APIs:** CRUD + bulk import (CSV/JSON), validation, versioning of master data.
- **Events:** `SkuCreated/Updated`, `BarcodeRegistered`, `LocationChanged`,
  `EquipmentRegistered` (so other services can cache/react).
- See §6 for the domain model.

### 4.2 Inventory / Stock Service
- **Responsibility:** Real-time stock within the automated area — quantity by
  SKU × **batch/lot** × location × handling-unit × status (available, allocated,
  **locked/unavailable**, blocked, in-transit). Stock can be explicitly
  **locked/made unavailable** (e.g. hold, inspection, manual block) so it stays
  on the books but is excluded from allocatable/available quantity reported to
  the host. Allocation/reservation for outbound, with **FEFO/FIFO**
  strategies driven by batch expiry/production dates.
- **Data:** **Service-local** store (its own Postgres DB or schema), *not* in the
  shared DB. Stock is **hard-persisted in a stock table** keyed by SKU × batch/lot
  × location × HU × status with the **current level as `qty`** — a durable,
  authoritative current-state row, not recomputed on demand. It is kept in lockstep
  with the **transaction log**, which is **safely retained as the immutable audit
  trail** of every movement and can rebuild/replay the stock table on demand.
- **APIs:** Stock queries, reservations, availability checks.
- **Events:** Consumes movement events; publishes `StockReserved`,
  `StockAdjusted`, `StockSyncToWms`.
- Audit & truth: the persisted stock table is the fast query surface; the
  transaction log is the system of record for audit and can replay the table.

### 4.3 Process / Workflow Engine Service
- **Responsibility:** Stores process **definitions** authored by admins and
  **executes** running instances (goods-in, outbound order processing, cycle
  count, returns, replenishment, …). Drives the orchestrator by emitting the
  next step.
- **Data:** Service-local store for definitions + running instance state.
- **APIs:** Definition CRUD/publish/version; instance start/query; task callbacks.
- **Events:** `ProcessStepRequested`, `ProcessCompleted`; consumes
  `StepCompleted`, equipment results. See §7.

### 4.4 Order Management Service (inbound + outbound)
- **Responsibility:** Receives inbound expectations (ASNs) and outbound orders
  from the WMS, tracks their fulfilment lifecycle, and kicks off the relevant
  process. Translates between WMS vocabulary and internal events.
- **Data:** Service-local order/lifecycle state.
- **APIs:** WMS integration endpoints (REST + message), order status queries.
- **Events:** `InboundExpected`, `OutboundOrderReleased`, `OrderFulfilled`.

### 4.5 Material Flow / Task Orchestrator
- **Responsibility:** The "traffic controller." Turns process steps into
  concrete **device tasks**, decides routing (which conveyor branch, which
  storage system, which robot), sequences tasks, resolves contention, handles
  exceptions/retries, and aggregates device results back to the workflow.
- **Data:** Service-local task/queue state.
- **APIs:** Task submit/cancel/query.
- **Events:** Publishes `DeviceTaskRequested`; consumes `DeviceTaskResult`.

### 4.6 Device Adapter Services (one per equipment family)
A separate deployable per family, each translating the **uniform internal device
contract** to/from a vendor protocol. See §8.
- **Conveyor Adapter** — PLC via OPC-UA / fieldbus; segment routing, scan points.
- **ASRS Adapter** — shuttle & crane store/retrieve commands.
- **AMR Adapter** — integrates a robot fleet manager / RCS (e.g. Geek+).
- **AutoStore Adapter** — bin store/retrieve, port management via grid controller.
- **Data:** Service-local connection/session and in-flight command state.
- **Events:** Consume `DeviceTaskRequested`, publish `DeviceTaskResult` +
  telemetry; append physical movements to the transaction log.

### 4.7 Cross-cutting / platform services
- **API Gateway** — single ingress, authn/z, routing, rate limiting.
- **Transaction Log Service** — owns the append-only log in shared Postgres,
  exposes append + query + replay (see §5.2).
- **Notification/Alert Service** — operator alerts, exceptions, andon.

### 4.8 Identity & Access Management (IAM) Service
- **Responsibility:** Users, authentication (local password **and** Microsoft
  SSO), and **role-based access control (RBAC)** — users get roles, roles carry
  **coded permissions** that gate screens and actions across every service.
- **Data:** Service-local store (its own Postgres DB/schema). Holds users, roles,
  role↔permission grants, and user↔role assignments. The **permission catalog
  itself is code-defined** (see below), not user-editable data.
- **APIs:** User CRUD & lifecycle (invite/disable/lock), role management, role↔
  permission assignment, "my permissions" introspection, token issuance (OIDC).
- **Events:** `UserCreated/Disabled`, `RoleAssigned`, `PermissionsChanged` (so
  the SPA can refresh a user's effective permissions live).

**RBAC model**
```
User
  user_id, username, email, display_name, status (ACTIVE|DISABLED|LOCKED)
  auth_source (LOCAL | MS_SSO)         -- how this user signs in
  external_subject_id                  -- Entra/Azure AD object id (SSO users)
  password_hash (LOCAL only)           -- Argon2id; never plaintext (see below)
  password_updated_at, failed_attempts, locked_until, last_login_at
  mfa_enabled
  ──< has many UserRole

Role
  role_id, code, name, description, is_system (built-in vs custom)
  -- seeded examples: ADMIN, SUPERVISOR, OPERATOR, VIEWER, MAINTENANCE
  ──< has many RolePermission

UserRole        (assignment)   user_id, role_id, granted_at, granted_by
RolePermission  (grant)        role_id, permission_code

Permission                     -- CODED in the application, not a DB-managed list
  permission_code (e.g. SCREEN_INVENTORY_VIEW, SCREEN_PROCESS_DESIGNER_EDIT,
                   ORDER_RELEASE, STOCK_ADJUST, MASTER_DATA_EDIT,
                   EQUIPMENT_COMMAND_OVERRIDE)
  -- defined as a typed enum/registry in code, versioned with releases;
  -- the DB only stores which permission_codes a role has been granted.
```

**Coded permissions.** Permissions are an application-defined catalog (a typed
enum/registry shipped with the code), grouped by area (screen access, data
edit, operational actions, equipment override). This keeps authorization checks
compile-time-safe and consistent everywhere (`@RequiresPermission("STOCK_ADJUST")`
on the backend, route/element guards in the SPA driven by the user's effective
permission set). Admins compose **roles** from this fixed catalog and assign
roles to **users** — they never invent raw permissions. Effective permissions =
union of all the user's roles' grants.

**Authentication**
- **Microsoft SSO (primary for staff):** OIDC / OAuth2 against **Microsoft Entra
  ID (Azure AD)** — authorization-code + PKCE flow. SSO users are auto-provisioned
  on first login (JIT) and mapped to openwcs roles via **Entra group → role
  mapping** (or default role on first login, then adjusted by an admin). No
  password is stored for SSO users. Supports the org's existing MFA/Conditional
  Access.
- **Local accounts (fallback / shop-floor / service):** for shared floor
  terminals or sites without Entra. Passwords are **hashed with Argon2id**
  (bcrypt acceptable), per-user salt, never stored or logged in plaintext;
  enforced password policy (length/complexity/rotation as configured), lockout
  after N failed attempts, and optional TOTP MFA.
- **Tokens:** the IAM service issues short-lived JWT access tokens carrying the
  user's roles + effective permission codes; the API Gateway validates them and
  services enforce per-permission checks. Refresh tokens rotate.
- **Implementation note:** **Keycloak** (already in the stack, §10) is the
  recommended engine — it brokers Microsoft Entra ID as an identity provider,
  handles OIDC token issuance, password hashing, MFA, and lockout out of the box.
  The IAM service then owns the openwcs-specific **role↔coded-permission** mapping
  and group→role rules layered on top.

### 4.9 Host / external-system integration gateways
Dedicated **anti-corruption-layer** services, one per external business system,
that translate a host's vocabulary and protocol to/from openwcs internal events
and APIs. They keep host-specific quirks out of the core domain — and because
each host gets its own service, integrations can grow independently without
touching one another (the orchestrator/order-management core never speaks SAP or
Manhattan directly).

**Stock ownership & sync model (decided).** Within the automated area, **openWCS
is the master/source of truth for stock** — the WMS/ERP does not direct moves
there. Sync to the host is **hybrid per host**: a **full daily reconciliation
sync** plus **intraday event-driven deltas** (publishing `StockSyncToWms` as
movements occur), so the host stays near-real-time without a constant full feed.
Stock the WCS marks **locked/unavailable** (§4.2) is reported as non-allocatable
so the host's available quantity matches what the WCS will actually ship.

- **SAP integration gateway** (`integration-sap`)
  - **Responsibility:** Integrate **SAP S/4HANA / SAP HANA** — inbound master
    data (materials/SKUs, UoM, batches) and orders (deliveries, transfer/production
    orders), outbound goods-movement confirmations and stock sync.
  - **Protocols:** OData V2/V4, BAPI/RFC (JCo), and/or IDoc — whichever the SAP
    landscape exposes; optionally direct read replicas on HANA for reporting.
  - **Maps to:** Master Data Service (catalog sync) and Order Management (orders
    in / confirmations out); publishes/consumes internal events, never lets SAP
    types leak inward.
  - **Data:** Service-local mapping/staging + idempotency keys.

- **Manhattan Active integration gateway** (`integration-manhattan`)
  - **Responsibility:** Integrate **Manhattan Active (Warehouse Management / Omni)**
    — inbound ASNs, outbound orders/waves, master data; outbound stock,
    confirmations, and status callbacks.
  - **Protocols:** Manhattan Active **REST APIs** (+ webhooks where available);
    OAuth2 client credentials.
  - **Maps to:** Order Management (orders/ASNs) and Inventory (stock sync);
    publishes/consumes internal events.
  - **Data:** Service-local mapping/staging + idempotency keys.

> **Pattern, not a pair.** SAP and Manhattan Active are the first two host
> gateways; the same anti-corruption-layer template applies to any future ERP/
> WMS/host (Blue Yonder, Oracle, a customer's bespoke WMS, …) — add a new
> `integration-*` service, don't extend an existing one. This is why the WMS
> boundary (§16) is mediated through these gateways rather than wired into the
> core.

---

## 5. Data Architecture

> **Constraint:** The shared PostgreSQL instance is used **only** for master data
> and the transaction log. Everything else is service-local.

### 5.1 Shared Postgres — two schemas only
- `master_data` — owned by the Master Data Service (§6).
- `transaction_log` — owned by the Transaction Log Service (§5.2).

No other service writes to the shared DB. This keeps the shared surface small,
auditable, and avoids a distributed monolith.

### 5.2 Transaction log (append-only)
Immutable record of every business/physical event for persistence, audit, and
projection rebuild.

```
transaction_log.events
  event_id        UUID            (PK)
  stream_id       TEXT            (e.g. handling-unit id, order id, location id)
  seq             BIGINT          (per-stream ordering)
  event_type      TEXT            (GoodsReceived, PutawayCompleted, Picked, …)
  occurred_at     TIMESTAMPTZ
  recorded_at     TIMESTAMPTZ
  actor           TEXT            (user / service / device)
  correlation_id  UUID            (process instance / task)
  payload         JSONB           (event-specific, schema-versioned)
  payload_version INT
  UNIQUE (stream_id, seq)         -- optimistic concurrency / ordering guarantee
```
- Append-only; never updated or deleted (corrections are compensating events).
- Streamed onto Kafka (e.g. via Debezium / outbox) so services build read models.
- Partition/retention strategy: monthly partitions; archive cold partitions.

### 5.3 Service-local stores
Each service persists its own operational state independently (own Postgres DB,
or a fit-for-purpose store). Examples: Inventory read model, workflow instances,
order lifecycle, adapter session state. Services exchange data **only** via
events/APIs — never by reaching into another service's DB.

### 5.4 Read models / projections
Inventory and dashboards are **projections** of the transaction log. A projection
can be dropped and rebuilt by replaying the log — this is the recovery and
audit story.

### 5.5 Consistency
- Within a service: ACID (Postgres transactions).
- Across services: eventual consistency via events + the **transactional outbox**
  pattern (write business state + outbox row in one local tx; relay publishes).
- Idempotent consumers keyed on `event_id` / `correlation_id`.

---

## 6. Master Data Domain Model

```
Warehouse / Site
  warehouse_id, code, name, timezone, status
  -- master data is scoped per warehouse; the global SKU carries only stable identity

SKU                       (global core — stable, warehouse-independent identity)
  sku_id, code, description, status, owner/client
  ──< has many UnitOfMeasure (bundle hierarchy)
  ──< has many Barcode
  ──< has many Batch/Lot (lot-tracked SKUs)
  ──< has many SkuProfile (one per warehouse — the variable metadata)
  -- tracking flags drive what is captured at goods-in:
  is_batch_tracked, is_serial_tracked, is_date_tracked

SkuProfile                (warehouse-scoped overlay — the part that varies per site)
  sku_id, warehouse_id
  metadata(JSONB)         -- ALL site-variable attributes live here, e.g.:
                          --   fashion: { brand, style, season:"SS26", color,
                          --              size, size_scale, model_year, gender }
                          --   handling/slotting/velocity class, client-specific fields
  -- storage strategy ("teach-in"): where THIS sku may/should live in THIS
  -- warehouse. The same globally-shared SKU stores differently per site.
  storage_strategy(JSONB) -- allowed/preferred storage types (PALLET, ASRS,
                          --   AUTOSTORE, MANUAL_BIN, SHELF …), zones, putaway
                          --   rules, min/max levels, mixing constraints
  attribute_schema_id     -- which schema validates this metadata (below)
  UNIQUE (sku_id, warehouse_id)

AttributeSchema           (admin-defined, per warehouse + category — governs the JSON)
  attribute_schema_id, warehouse_id, applies_to (SKU|LOCATION|HU|…), category
  json_schema(JSONB)      -- field names, types, enums, required, indexed-flags
  version
  -- the Master Data Service validates every metadata(JSONB) write against this

DangerousGoods            (0..1 per SKU — only for hazardous SKUs)
  dg_id, sku_id
  un_number, hazard_class, packing_group, proper_shipping_name
  adr_imdg_iata_codes, flash_point, net_explosive_qty
  storage_segregation_rules(JSONB), handling_constraints(JSONB)

Batch / Lot              (instance-level — captured at goods-in, NOT master)
  batch_id, sku_id, batch_number, supplier_lot
  production_date, best_before_date, expiry_date, received_at
  country_of_origin, quality_status (RELEASED|QUARANTINE|BLOCKED)
  attributes(JSONB)

SerialUnit               (instance-level — one row PER PHYSICAL PIECE; optional)
  serial_id, sku_id, batch_id (nullable), serial_number
  status (IN_STOCK|ALLOCATED|PICKED|SHIPPED|BLOCKED)
  current_location_id, current_hu_id, received_at
  attributes(JSONB)
  -- created only when the SKU's is_serial_tracked flag is set; gives full
  -- per-piece genealogy (where it came from, where it went) via the tx log

UnitOfMeasure (a.k.a. "bundle")
  uom_id, sku_id, code (EACH, INNER, CASE, PALLET …)
  parent_uom_id            -- bundle hierarchy: 1 CASE = 12 EACH, etc.
  qty_in_parent            -- conversion factor
  dimensions (l×w×h), weight, is_base_unit

Barcode
  barcode_id, sku_id, uom_id (which packaging level it identifies)
  value
  barcode_type_id

BarcodeType
  type_id, name (EAN13, GTIN14, CODE128, GS1-128, QR, DATAMATRIX, SSCC …)
  symbology, length_rule, check_digit_rule, gs1_ai_parsing(bool)

Location / Topology
  location_id, code
  location_type   (BIN, PALLET, FREE_SPACE, SHELF, GRID_BIN, ASRS_SLOT,
                   CONVEYOR_SEGMENT, ROBOT_PORT, STATION)   -- physical form/holding type
  purpose         (STORAGE, TRANSPORT, STAGING, PICK, PACK, INDUCT,
                   RECEIVING, SHIPPING, QUARANTINE, RETURNS)  -- functional role
  parent_id (zone/aisle/grid), coordinates, equipment_id, status
  capacity (qty/volume/weight limits), allowed_hu_types[], allowed_sku_attrs(JSONB)
  is_mixed_allowed, replenishment_class

HandlingUnitType
  hu_type_id, name (TOTE, TRAY, CARTON, PALLET), dimensions, nestable, weight_limit

Equipment
  equipment_id, family (CONVEYOR|ASRS|AMR|AUTOSTORE), vendor, model,
  adapter_endpoint, capabilities(JSONB), status
```

Key points:
- **Bundles = UoM hierarchy** with conversion factors; the base unit is the
  smallest stockable unit. Stock math always normalizes to the base unit.
- **Barcodes carry a type**; the type drives parsing/validation (e.g. GS1-128
  application-identifier parsing to extract batch/expiry/SSCC).
- **Global core vs. warehouse-scoped metadata.** Master data may legitimately
  differ per warehouse (different clients, slotting strategies, even which
  fashion/merchandising fields are relevant). So the **SKU global record holds
  only stable, warehouse-independent identity**; everything site-variable lives
  in a **`SkuProfile.metadata(JSONB)` overlay, one row per warehouse**. This
  keeps the core small and lets a SKU mean slightly different things in different
  buildings without schema churn. The same pattern applies to locations and
  handling units where useful.
  - **Guardrail — schema-governed JSON.** Each `metadata` blob is validated on
    write against an admin-defined **`AttributeSchema`** (per warehouse +
    category). This gives the flexibility of JSON *and* type/enum/required
    validation, plus a declared list of **indexed fields** so common queries
    (e.g. by `brand`/`season`) stay fast via Postgres JSONB GIN / expression
    indexes. Avoids the "unqueryable dumping ground" failure mode of free JSON.
- **SKU vs. batch/lot — what is master vs. instance data:**
  - **Master (on the SKU):** merchandising/fashion attributes (`brand`, `style`,
    `season`, `color`, `size`, …), dangerous-goods classification, and the
    **tracking flags** (`is_batch_tracked`, `is_serial_tracked`,
    `is_date_tracked`) that declare *what must be captured* for that SKU.
  - **Instance (on the Batch/Lot, captured at goods-in):** `batch_number`,
    `production_date`, `best_before_date`/`expiry_date`, supplier lot, quality
    status. These vary per received unit, so they are **not** SKU master data —
    they are created during receiving and tracked through inventory and the
    transaction log. Serial-tracked SKUs additionally get a record per piece.
  - At goods-in the engine reads the SKU's tracking flags to decide which
    fields the operator/scanner must provide; barcode parsing (e.g. GS1-128 AIs)
    can auto-populate batch/expiry. Stock is then keyed by SKU **and** batch/lot.
  - **Serial-number tracking is an opt-in per SKU** (`is_serial_tracked`). When
    set, goods-in captures a serial number per piece and creates a `SerialUnit`
    row, giving full per-piece genealogy through the transaction log
    (receipt → location moves → pick → ship). When unset, no serial overhead is
    incurred — so the heavyweight per-piece model only applies where needed.
- **Dangerous-goods** classification on the SKU drives storage segregation and
  handling constraints — the Flow Orchestrator (§4.5) uses it to exclude
  incompatible destinations (e.g. segregation rules, station eligibility), and
  expiry/best-before dates enable **FEFO** (First-Expired-First-Out) allocation
  in outbound.
- **Locations are classified on two independent axes:**
  - **`location_type`** — the physical form / what it holds: `BIN`, `PALLET`,
    `FREE_SPACE` (bulk floor area, no fixed slot), `SHELF`, `GRID_BIN`,
    `ASRS_SLOT`, `CONVEYOR_SEGMENT`, `ROBOT_PORT`, `STATION`.
  - **`purpose`** — the functional role it plays in flows: `STORAGE`,
    `TRANSPORT`, `STAGING`, `PICK`, `PACK`, `INDUCT`, `RECEIVING`, `SHIPPING`,
    `QUARANTINE`, `RETURNS`.

  The two axes are orthogonal — e.g. a `PALLET` location can be `STORAGE` or
  `STAGING`; a `FREE_SPACE` area can be `RECEIVING` or `STAGING`. The Flow
  Orchestrator (§4.5) uses `purpose` to pick valid sources/destinations for a
  step, and `location_type` + capacity/`allowed_hu_types` to validate that a
  given handling unit physically fits. Locations are versioned; changes emit
  events so caches refresh.
- Master data is versioned; changes emit events so caches refresh.

---

## 7. Process Designer & Workflow Engine

Admin users design processes visually; the engine executes them. This is the
"processes are data" principle in §2.

### Approach
- **Decided: BPMN 2.0 via Flowable** (embeddable, Apache-2.0, actively
  maintained — preferred over Camunda 7, whose open-source Community Edition is
  on a sunset track). A purpose-built JSON/YAML step DSL was considered and
  rejected as too limited; BPMN gives a mature visual editor, versioning, and
  execution semantics out of the box.
- The **designer UI** (BPMN modeler, e.g. `bpmn-js`) runs in the admin SPA.
- Each process step maps to a **service task** that emits an internal command
  (e.g. `RequestPutaway`, `RequestPick`, `RequestCount`, `Scan`, `Weigh`,
  `OperatorConfirm`) handled by the orchestrator/services.
- Versioned & published: running instances pin to the version they started on.

### Built-in process templates (shipped, then customizable)
1. **Goods-in / Receiving:** ASN match → unload → scan/identify (barcode type
   parsing) → **capture batch/lot & dates** per the SKU's tracking flags
   (auto-filled from GS1 AIs where present) → quality/anomaly check (+ dangerous
   -goods routing) → assign handling unit → **putaway** (orchestrator routes to
   ASRS / AutoStore / AMR, honoring DG segregation) → stock increment by
   SKU×batch (logged).
2. **Outbound order processing:** order release → allocate stock (reservation)
   → generate retrieval tasks → goods-to-person / route to pick station → pick
   confirm → pack → consolidate → ship → stock decrement (logged).
3. **Cycle count:** trigger (scheduled/ad-hoc/blind) → retrieve unit or count in
   place → operator/auto count → compare to read model → **adjustment event**
   (logged as compensating transaction) → optional recount.
4. **Replenishment, returns, relocation, station maintenance** — added as
   templates over time.

### Step types available to designers
`equipment-task` (store/retrieve/transport), `scan`, `weigh/measure`,
`operator-confirm`, `decision/branch`, `parallel`, `wait-for-event`,
`call-process` (sub-process), `notify`, `stock-adjust`.

---

## 8. Equipment Integration Layer

### Uniform internal device contract
Every adapter implements the same command/result/telemetry contract so the
orchestrator is vendor-agnostic:

```
Command:  { taskId, equipmentId, op, source, destination, payload, deadline }
            op ∈ { STORE, RETRIEVE, TRANSPORT, MOVE, SCAN, RELEASE, CANCEL }
Result:   { taskId, status (ACCEPTED|DONE|FAILED|CANCELLED), at, detail }
Telemetry:{ equipmentId, metric, value, at }   // throughput, faults, occupancy
Event:    physical movements appended to the transaction log
```

### Per-family adapters
| Family | Typical interface | Notes |
|--------|-------------------|-------|
| **Conveyor** | PLC via **OPC-UA** (Eclipse Milo), Modbus/TCP, or fieldbus | Segment routing, divert/merge, scan points, barcode read events, jam handling. |
| **ASRS (shuttle/crane)** | Vendor TCP/telegram protocol or OPC-UA | Store/retrieve by slot, double-deep handling, sequencing, deadlock avoidance. |
| **AMR (Geek+)** | Robot Control System / fleet-manager **REST + webhook/socket** | Submit transport jobs, station/rack assignment, robot status callbacks. |
| **AutoStore** | Grid controller API (e.g. order/port REST API) | Bin store/retrieve, port presentation, bin inventory, digital-twin sync. |

### Two integration styles (adapters are not uniform)
Adapters fall into two broad transport categories, and the right language differs
per category:
- **(a) Raw TCP socket / telegram** — legacy PLC-driven equipment (many
  conveyors, stacker cranes). Custom binary framing, persistent connection,
  heartbeat/reconnect. **Go is the recommended default** here (precise byte-level
  framing, goroutine-per-connection); Rust for the rare latency-/safety-critical
  case. **Some PLC comms have a hard ~10 ms response budget** — those paths must
  avoid GC/runtime pauses, so they run on Go (or Rust), never a JVM adapter
  (see §16: real-time latency).
- **(b) REST + WebSocket** — newer/vendor-managed systems (AMR fleet managers
  like Geek+ RCS, AutoStore grid controller, some modern PLC gateways). The work
  is HTTP request/response for commands plus a **WebSocket (or webhook) stream**
  for asynchronous status/telemetry callbacks. Here the byte-framing advantage is
  moot, so **language is a consistency choice — Java (Spring WebClient/WebSocket)
  or Go both fit well**; pick per team/library support. Expect **many adapters to
  be of this REST+WebSocket kind.**

### Adapter implementation guidance
- **Language by transport:** Go is the default for raw-socket adapters; for
  REST/WebSocket adapters either Java or Go is fine (favor Java when sharing
  core libraries/contracts matters). Java + Netty/Milo remains the alternative
  when an OPC-UA stack is needed. Rust only for latency-/safety-critical cases.
  The uniform device contract above keeps the orchestrator indifferent to both
  the transport and the language.
- **Hard real-time stays in the PLC, not the adapter.** The PLC/RCS owns
  millisecond-level interlocks and safety logic. The WCS adapter is a
  **supervisory/coordination layer** (route this tote, store this bin, report
  faults) — so runtime/GC pauses are never on a safety-critical path.

### Adapter responsibilities
- Maintain the connection/session and translate protocols both directions.
- Map vendor identifiers ↔ internal location/equipment IDs (via master data).
- Be **idempotent** and reconcile in-flight commands after reconnect.
- Emit telemetry and append movement events to the transaction log.
- Degrade safely: buffer/queue, surface faults as exceptions to the orchestrator.

---

## 9. Messaging / Event Backbone

- **Apache Kafka** as the durable event backbone and transaction-log stream.
  - Topics per domain: `master-data.events`, `inventory.events`,
    `process.commands`, `device.tasks`, `device.results`, `txlog.stream`.
  - Keyed by stream/aggregate id for ordering; partitioned for throughput.
- **MQTT** (optional) for high-frequency device telemetry where lighter-weight
  pub/sub fits better than Kafka.
- Schema management: **Avro/Protobuf + Schema Registry** for event contracts;
  events are versioned and backward-compatible.
- **Transactional outbox + CDC (Debezium)** to publish reliably from local
  service transactions without dual-write bugs.

---

## 10. Recommended Technology Stack

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Service language (core domain) | **Java 21 + Spring Boot 3** (or **Quarkus**) | Mature industrial/integration ecosystem, OPC-UA (Eclipse Milo), workflow engines, virtual threads, strong typing for safety-relevant logic. Use for master data, inventory, workflow, orchestrator, order mgmt. |
| Service language (raw-socket adapters) | **Go** (recommended) | For PLC/telegram TCP adapters: goroutines/channels map cleanly to "one persistent connection + heartbeat + writer pump"; precise byte-level framing for proprietary protocols; static binary, tiny container. Strong Kafka/Modbus/OPC-UA libs. |
| Service language (REST/WebSocket adapters) | **Java or Go** (either fits) | Many adapters (Geek+ RCS, AutoStore, modern gateways) are HTTP + WebSocket/webhook clients — no byte-framing edge, so choose for consistency/library support. Favor Java when sharing core contracts/libs matters. |
| Service language (perf/safety-critical adapter) | **Rust** (`tokio`) — only when justified | No-GC, memory-safe option for genuinely latency-sensitive or safety-adjacent edge components. Over-engineering for typical adapters — reserve for the rare case. |
| Database | **PostgreSQL 16** | Shared (master data + tx log) and per-service stores. JSONB for flexible payloads. |
| Event backbone | **Apache Kafka** (+ Schema Registry) | Durable, ordered, log-shaped — fits the transaction-log model. |
| Workflow engine | **Flowable** (BPMN 2.0, Apache-2.0) + `bpmn-js` editor | Visual admin-designable processes, versioning, execution. |
| Device protocols | **Eclipse Milo (OPC-UA)**, Modbus, vendor REST/TCP | Standard industrial connectivity. |
| API style | REST (OpenAPI) for queries/commands; gRPC optional internal | Tooling + clarity. |
| API gateway | **Spring Cloud Gateway** or **Kong** | Ingress, authn/z, routing. |
| Identity / SSO | **Keycloak** (OIDC/OAuth2) brokering **Microsoft Entra ID** | MS SSO for staff + local accounts (Argon2id) for floor terminals; openwcs RBAC = users→roles→**coded permissions** layered on top (§4.8). |
| Frontend | **React 18 + TypeScript + Vite** SPA | Admin process designer, dashboards, master-data mgmt. |
| — Visual design system | **`styling.md`** (dark/glassy/futuristic tokens) | Source of truth for colors, type, spacing & component primitives; implemented as the React theme. Full aesthetic on management screens, legibility-first subset on operator screens (§11). |
| — UI components | **MUI or Mantine** | Dense tables/forms/dashboards for operator & admin views without hand-rolling; themed from the `styling.md` tokens. |
| — Process designer | **bpmn-js** | Embedded BPMN modeler for admin-designed processes (§7); the reason React is the natural pick. |
| — Server state | **TanStack Query** | Caching/refetch for status-heavy dashboards; generated OpenAPI typed client keeps SPA in lockstep with backend contracts. |
| — Real-time | **WebSocket / SSE** (via gateway, fed by Kafka) | Push equipment telemetry, task progress, and andon/alerts instead of polling. |
| Containerization | **Docker** | Standard packaging. |
| Orchestration | **Kubernetes** (Helm) | Scaling, rolling deploys, resilience. |
| Observability | **OpenTelemetry → Prometheus + Grafana + Loki/Tempo** | Metrics, logs, traces across services. |
| CI/CD | **GitHub Actions** | Build, test, image push, deploy. |
| Local dev | **Docker Compose** | One-command spin-up of the platform. |

> The stack is a recommendation to keep contributions coherent — service
> boundaries permit a different language where it is clearly justified.

---

## 11. UI / UX Design

The frontend serves **two very different audiences** that must not be designed
to the same brief. Both ship from the one React SPA (§10), but they use distinct
design modes — a **shared design system** with two "skins"/layout densities, not
two codebases.

> **Visual design system: see [`styling.md`](./styling.md).** That guide defines
> the project's visual language — the dark / glassy / futuristic aesthetic
> (forest-abyss base, herbal-lime accent, glassmorphic surfaces, three-font
> typography) — as **design tokens + component primitives**. Its `:root` token
> block (`styling.md` §1) is the **single source of truth**; implement it as the
> React theme so every component reads from the same tokens. The two modes below
> are applied *on top of* that system — same tokens, different density and
> restraint per audience. **Important:** apply the decorative parts (heavy
> backdrop-blur, glow halos, translucent low-contrast glass) **only on management
> screens**; operator screens take the tokens but favor legibility (see §11.1).

### 11.1 Operator / working screens — *clean & functional*
Used at pick/pack stations, induction points, the control room, and on the
warehouse floor (often touch, gloves, fast glances, sometimes ruggedized
terminals). Optimized for **speed, clarity, and error-proofing**, not decoration.

- **High contrast, large hit targets, big type** — readable at a distance and
  usable with gloves/touch; minimum tap target sizing.
- **One primary task per screen.** Show only what the current step needs; the
  next action is the most prominent element. No competing chrome.
- **Status by color + shape + text** (never color alone — colorblind-safe,
  and works under warehouse lighting). Clear OK / WARN / FAULT states.
- **Big, unambiguous feedback** for scans, confirmations, and errors —
  audible/visual cues; a wrong scan must be impossible to miss.
- **Minimal navigation, keyboard/scanner-first.** Workflows driven by barcode
  scans and a few large buttons; avoid deep menus and mouse-precision controls.
- **Low latency & resilient.** Instant optimistic feedback, graceful handling of
  brief disconnects (these screens lean on the WebSocket/SSE real-time channel).
- Restrained, near-monochrome palette with strong accent colors reserved for
  state — the screen should look calm until something needs attention.
- **Apply `styling.md` legibility-first here.** Use its tokens (colors, type
  scale, spacing) and the **opaque** surfaces it already defines (`.dialog` on
  `--forest-deep`, the solid sidebar) rather than the translucent `.glass`
  primitive; **drop decorative `backdrop-blur`, glow halos, and aurora layers** —
  they reduce contrast and cost GPU on cheaper floor terminals. Lean on the
  status-tint table for OK/WARN/FAULT, but verify contrast under warehouse
  lighting. Glassmorphism is for the office, not the production line.

### 11.2 Management screens — *modern & rich*
Used by supervisors, planners, and admins for **reports, statistics, dashboards,
KPIs, master-data management, and the process designer** (§7). Desktop-first,
analytical, exploratory.

- **The full `styling.md` aesthetic shines here** — the glassmorphic surfaces,
  aurora background layers, glow accents, italic gradient hero text, and mono
  eyebrow/stat treatments are all appropriate for these analytical, desktop
  views (refined typography, spacing, contemporary look on top of the
  MUI/Mantine component system, §10).
- **Rich data visualization** — charts, trends, heatmaps, throughput/occupancy
  KPIs; a charting lib (e.g. Recharts/visx/ECharts) on top of the component kit.
- **Information density with hierarchy** — dense tables with sorting, filtering,
  grouping, drill-down; progressive disclosure rather than oversimplification.
- **Exploration tools** — date ranges, filters, export (CSV/PDF), saved views.
- Light/dark themes, responsive down to tablet for "walking the floor" review.

### 11.3 Shared foundation
- **One design system, two modes.** The `styling.md` tokens (color, type scale,
  spacing) + component library + OpenAPI-typed client are shared; an **"operator"
  layout/density mode** (legibility-first, opaque, no decorative blur) and a
  **"management" mode** (full glassy aesthetic) select the right ergonomics per
  context. Both consume the *same* token block so themes stay in sync.
- **Accessibility throughout** — WCAG-minded contrast, focus order, scanner &
  keyboard navigation; operator screens additionally tuned for distance/gloves.
- **Consistent domain vocabulary & iconography** across both modes so equipment,
  locations, and statuses read the same everywhere.
- **i18n & units** — localized labels; quantities always shown in the SKU's base
  UoM with explicit unit metadata (ties to §6).

---

## 12. Cross-Cutting Concerns

- **Security:** OIDC via Keycloak brokering **Microsoft Entra ID (MS SSO)** for
  staff, with local Argon2id-hashed accounts as fallback; the gateway validates
  JWTs and services enforce **coded-permission** RBAC (users→roles→permissions,
  §4.8). mTLS between services; secrets via K8s secrets / Vault. User/role/
  permission changes, master-data edits, and process-definition changes are all
  audit-logged (to the transaction log where relevant).
- **Reliability:** idempotent handlers, retries with backoff, dead-letter
  topics, circuit breakers (Resilience4j) around device adapters, health/readiness
  probes.
- **Observability:** correlation/trace IDs propagated from process instance →
  task → device command; per-equipment throughput & fault dashboards; alerting
  on stalled tasks and stock discrepancies.
- **Time & ordering:** events carry both `occurred_at` (device/business) and
  `recorded_at` (system); per-stream `seq` guarantees ordering.
- **Configuration:** externalized config per environment; feature flags for
  enabling equipment families.
- **Internationalization & units:** all stock math in base UoM; explicit unit
  metadata on dimensions/weights.

---

## 13. Repository Structure

Recommend a **monorepo** initially (simpler dependency/versioning of shared
contracts), splitting out later if needed.

```
openwcs/
├── build.md                      # this document
├── README.md
├── docs/                         # ADRs, event catalog, API specs
├── platform/
│   ├── docker-compose.yml        # local dev: postgres, kafka, keycloak, …
│   └── helm/                     # k8s charts
├── contracts/
│   ├── events/                   # Avro/Protobuf event schemas (shared)
│   └── openapi/                   # REST API specs
├── services/
│   ├── master-data/
│   ├── inventory/
│   ├── allocation/             # outbound prep: pick-location allocation, cubing, batch picking (ADR 0002)
│   ├── process-engine/
│   ├── order-management/
│   ├── flow-orchestrator/
│   ├── txlog/
│   └── adapters/
│       ├── conveyor/
│       ├── asrs/
│       ├── amr-geekplus/
│       └── autostore/
├── gateway/
├── ui/                           # React admin SPA + bpmn-js designer
└── libs/                         # shared libs (event client, outbox, domain types)
```

---

## 14. Build & Developer Workflow

1. `docker compose -f platform/docker-compose.yml up` — Postgres, Kafka, Schema
   Registry, Keycloak, observability stack.
2. Apply DB migrations (Flyway/Liquibase) for `master_data` + `transaction_log`.
3. `./gradlew build` (or per-service) — build & test each service; produce images.
4. Contract-first: generate event/API stubs from `contracts/`.
5. Run a service locally against the compose backbone; or `skaffold`/Helm for k8s.
6. **Testing layers:**
   - Unit tests per service.
   - Contract tests on event/API schemas (consumer-driven).
   - Integration tests with Testcontainers (Postgres + Kafka).
   - **Equipment simulators** (mock adapters) so the full flow can run with no
     physical hardware — essential for CI and onboarding.
   - End-to-end "golden path" tests for goods-in / outbound / cycle-count.

---

## 15. Delivery Roadmap (phased)

**Phase 0 — Foundations**
- Repo scaffolding, contracts, platform compose stack, CI.
- Shared Postgres schemas: `master_data` + `transaction_log`.
- Transaction Log Service + outbox/CDC + Kafka wiring.
- **IAM Service + Keycloak**: MS Entra ID SSO + local accounts, the coded
  permission catalog, seed roles (ADMIN/SUPERVISOR/OPERATOR/VIEWER), gateway
  JWT validation — auth is needed before any real screen ships.

**Phase 1 — Master data + inventory MVP**
- Master Data Service (SKU, UoM/bundles, barcodes + types, locations, equipment).
- Inventory Service as a projection of the transaction log.
- Manual movement events to prove the log → projection loop end-to-end.

**Phase 2 — Process engine + one equipment family**
- Process/Workflow engine + admin designer (BPMN).
- Flow Orchestrator + uniform device contract.
- **First adapter** (recommend Conveyor or a simulator) + simulators for the rest.
- Deliver the **Goods-in** process end-to-end against simulated equipment.

**Phase 3 — Outbound + more equipment**
- Order Management + first **host-integration gateway** (`integration-sap` or
  `integration-manhattan`, §4.9) for real orders/master-data sync.
- **Outbound order processing** process.
- Add **AutoStore** and/or **AMR (Geek+)** adapters; routing/contention logic.

**Phase 4 — Counting & operations**
- **Cycle count** process and stock adjustments (compensating events).
- ASRS (shuttle/crane) adapter.
- Operator dashboards, alerting, exception handling.

**Phase 5 — Hardening & scale**
- Resilience (circuit breakers, DLQs, replay tooling), performance tuning,
  multi-instance scaling, security review, documentation & ADRs.

---

## 16. Open Questions / Decisions to Confirm

- **Workflow engine** — **decided: full BPMN 2.0 via Flowable** (§7), not a
  custom DSL. Flowable is embeddable, Apache-2.0, and actively maintained
  (preferred over Camunda 7, whose Community Edition is sunsetting).
- **WMS/ERP boundary** — **decided.** Within the automated area **openWCS is the
  master of stock**; the host does not direct moves there. Host sync is **hybrid
  per host: a full daily reconciliation plus intraday event-driven deltas**
  (§4.9). Stock can be **locked/unavailable** and is then reported as
  non-allocatable to the host (§4.2).
- **Real-time latency budget** — **decided.** Hard real-time stays in the
  **PLC/RCS**; the WCS is supervisory. However **some PLC comms carry a hard
  ~10 ms response budget**, so those raw-socket adapters must avoid GC/runtime
  pauses — implemented in **Go (or Rust), never a JVM adapter** on that path
  (§9 integration styles).
- **Multi-tenancy / multi-site** — **decided: multi-warehouse from the start.**
  SKUs carry a stable global core; site-variable master data lives in
  per-warehouse `SkuProfile.metadata` overlays governed by an `AttributeSchema`
  (§6). **Sub-question decided: SKU master data IS shared globally** (one SKU
  identity across warehouses). Warehouse-specific SKU information — notably the
  **storage strategy / "teach-in"** (where it may/should be stored: pallet,
  ASRS, AutoStore, manual bin …) — lives in the per-warehouse `SkuProfile`
  (`storage_strategy`, §6).
- **Inventory store** — **decided.** Stock is **hard-persisted in a stock table
  with the current level as `qty`** (durable authoritative current state, not
  recomputed on demand), while the **transaction log is safely retained as the
  immutable audit trail** for every movement. The stock table is the fast query
  surface; the log gives auditability and replay/rebuild (§4.2).

---

*This is a living document. Record significant decisions as ADRs under
`docs/adr/` and keep the service list and roadmap here in sync.*
