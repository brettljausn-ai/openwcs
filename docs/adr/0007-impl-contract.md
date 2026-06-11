# ADR-0007 Phase 3c-1 — Implementation Contract (binding)

Status: Binding for 3c-1. Derived from `0007-conveyor-transport-and-workplace-induction.md`
(Accepted + Decisions). This file removes ambiguity so independent implementers cannot drift.
Where this contract and the ADR prose disagree, **this contract wins for 3c-1 scope**; anything it
marks OUT OF SCOPE is 3c-2.

3c-1 goal: stand up the **flow-owned inbound induction queue**
(`REQUESTED → IN_TRANSIT → QUEUED → DONE`, cap on `{IN_TRANSIT, QUEUED}`), driven by RETRIEVE +
CONVEY device-task callbacks; emulator runs a CONVEY leg whose completion = arrival; GTP/counting
become clients that request presentation and read the slice from flow; per-HU `hu_transport_trace`
written on each lifecycle transition. Delivers R1 + R3 + R4-timeline; R2 is contract-supported.

---

## 1. What MOVES to flow vs what STAYS (minimal blast radius)

**MOVES to flow-orchestrator (new source of truth):** the inbound induction / presentation queue —
the `REQUESTED → IN_TRANSIT → QUEUED → DONE` pipeline of totes being delivered to a workplace, plus
the per-station in-transit cap, plus the per-HU transport trace. This is decision #2/#4 and the lead
Consequence ("the station queue relocates from `services/gtp` to `services/flow-orchestrator`").

**STAYS in gtp (untouched by 3c-1):**
- `WorkCycleService` (present / put-list / confirm / task lines / close) — the pick-and-put work
  cycle. No change.
- Store-back: `StationQueueService.storeBack(...)` logic and its `flow.createTransport(... "STORE" ...)`
  call. **Moves out of `StationQueueService` but the behaviour stays in gtp** (see §6) — it is a
  gtp-owned reaction to a completed presentation, not part of the inbound queue.
- `GtpStation`, `StationNode`, sessions, exceptions (dirty-tote / broken), demand, put instructions.

**Minimal-blast-radius path (chosen):**
- **Additive in flow:** add new tables, a new `InductionQueueService` + `HuTraceService`, a new
  controller, and new branches in `DeviceTaskService.completeFromCallback` keyed off command. The
  existing `device_task` table and async §3b callback primitive are **reused unchanged**.
- **gtp keeps its `station_queue_entry` table and code physically present but DEMOTED to dead/legacy:**
  the `POST /api/gtp/stations/{id}/queue` **inbound enqueue is deprecated and made a no-op-equivalent
  that callers stop using** (counting stops calling it; see §6). Do **not** drop the gtp table or the
  `enqueue`/`queue`/`complete` Java in 3c-1 — removing it is destructive and out of scope. Mark
  `@Deprecated`. The gtp *read* path (`GET /stations/{id}/queue`) is left in place but the GTP screen
  switches to reading flow (§6), so gtp's queue becomes unused, not deleted.
- This satisfies "the inbound queue's source of truth MUST become flow" (flow is the only thing
  written/read for the live pipeline) while leaving the destructive gtp removal to a later cleanup.

Out of scope for 3c-1 (3c-2): emulator loop recirculation/re-sequencing; conveyor decision-point
(DIVERT/MERGE/RECIRCULATE) traces reported by the emulator; deletion of the gtp `station_queue_entry`
table/code.

---

## 2. Flow DB schema

**Flyway migration filename (NEXT version):**
`services/flow-orchestrator/src/main/resources/db/migration/V11__induction_queue_and_hu_trace.sql`
(existing max is `V10__placed_equipment_station.sql`). All objects in schema `flow`
(`SET search_path TO flow;` as in V1). Use `gen_random_uuid()`, `timestamptz … DEFAULT now()`,
`version bigint NOT NULL DEFAULT 0`, matching the V1 / `Auditable` convention.

### 2.1 `induction_queue_entry` (the relocated inbound queue, keyed by destination workplace)

```sql
CREATE TABLE induction_queue_entry (
    induction_entry_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id       uuid        NOT NULL,
    workplace_id       uuid        NOT NULL,        -- destination workplace (today: a GTP station id)
    workplace_kind     text        NOT NULL DEFAULT 'GTP_STATION', -- R1: GTP_STATION | PUT_WALL | ...
    hu_id              uuid,
    hu_code            text,
    sku_id             uuid,
    sku_code           text,
    qty                numeric,
    location_id        uuid,                         -- source storage slot (for store-back by gtp)
    mode               text        NOT NULL,         -- PICKING | STOCK_COUNT | ...
    status             text        NOT NULL DEFAULT 'REQUESTED',
    -- arrival sequence: monotonic per workplace, assigned when the entry becomes QUEUED (R2/R4).
    -- NULL until QUEUED. Queue order for QUEUED entries is ORDER BY arrival_seq ASC.
    arrival_seq        bigint,
    requested_at       timestamptz NOT NULL DEFAULT now(),
    in_transit_at      timestamptz,                  -- when RETRIEVE completed
    queued_at          timestamptz,                  -- actual arrival time (CONVEY completed)
    done_at            timestamptz,
    -- links to the device tasks orchestrating this entry's journey (decision #5, lifecycle wiring):
    retrieve_task_id   uuid,                         -- the RETRIEVE/BIN_RETRIEVE device_task
    convey_task_id     uuid,                         -- the CONVEY device_task
    -- counting linkage (carried through so the station can drive an at-station blind count):
    count_task_id      uuid,
    count_line_id      uuid,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT induction_entry_status_chk
        CHECK (status IN ('REQUESTED','IN_TRANSIT','QUEUED','DONE'))
);

CREATE INDEX induction_entry_workplace_idx ON induction_queue_entry (workplace_id, status);
CREATE INDEX induction_entry_hu_idx        ON induction_queue_entry (warehouse_id, hu_id, status);
CREATE INDEX induction_entry_convey_idx    ON induction_queue_entry (convey_task_id);
CREATE INDEX induction_entry_retrieve_idx  ON induction_queue_entry (retrieve_task_id);
```

`arrival_seq` is assigned at QUEUED time as `1 + max(arrival_seq) for that workplace` (or a per-
workplace sequence). It is **arrival order, never request order** (R2). 3c-1 emulator delivers in
request order, so today seq == request order, but the contract MUST sort QUEUED by `arrival_seq` and
assign it at the CONVEY callback, so 3c-2 re-sequencing needs zero further change here.

### 2.2 `hu_transport_trace` (R4 append-only timeline)

```sql
CREATE TABLE hu_transport_trace (
    trace_id        uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    warehouse_id    uuid        NOT NULL,
    hu_id           uuid        NOT NULL,
    hu_code         text,
    ts              timestamptz NOT NULL DEFAULT now(),
    point           text,                            -- function point, e.g. 'slot:A01','conveyor','station-2'
    event           text        NOT NULL,            -- REQUESTED|RETRIEVED|INDUCTED|ARRIVED|QUEUED|DONE|RECIRCULATE...
    decision        text,                            -- human-readable decision at the point
    from_point      text,
    to_point        text,
    workplace_id    uuid,
    correlation_id  uuid,                            -- == hu_id today (mirrors device_task grouping)
    task_id         uuid,                            -- the driving device_task, when applicable
    induction_entry_id uuid,                         -- the induction entry this row belongs to, when applicable
    created_at      timestamptz NOT NULL DEFAULT now()
    -- append-only: no updated_at / version; rows are never mutated.
);

CREATE INDEX hu_transport_trace_hu_idx  ON hu_transport_trace (warehouse_id, hu_id, ts);
CREATE INDEX hu_transport_trace_ts_idx  ON hu_transport_trace (ts);
```

`event` enum for 3c-1: `REQUESTED, RETRIEVED, INDUCTED, ARRIVED, QUEUED, DONE`. (3c-2 adds
`DIVERT, MERGE, RECIRCULATE` reported by the emulator — DO NOT implement now, but the row shape
already supports them.)

---

## 3. Flow REST API

New controller `InductionQueueController` under `@RequestMapping("/api/flow")`. Actor from
`X-Auth-User` header (as `DeviceTaskController`). Gateway exposes `/api/flow/**` already.

### 3.1 Request presentation of an HU at a workplace

`POST /api/flow/induction/requests`

Request JSON:
```json
{
  "warehouseId": "uuid",
  "workplaceId": "uuid",
  "workplaceKind": "GTP_STATION",
  "huId": "uuid",
  "huCode": "TOTE-1",
  "skuId": "uuid",
  "skuCode": "SKU-1",
  "qty": 12,
  "locationId": "uuid",
  "mode": "STOCK_COUNT",
  "family": "ASRS",
  "countTaskId": "uuid|null",
  "countLineId": "uuid|null"
}
```
Behaviour: creates an `induction_queue_entry` in `REQUESTED`, writes a `REQUESTED` trace row, then
**flow orchestrates the journey itself**: it dispatches the RETRIEVE device task (family from
`family`, command `RETRIEVE` / `BIN_RETRIEVE` for AUTOSTORE), records its id in `retrieve_task_id`,
correlationId = `huId`. The CONVEY task is dispatched later, by the RETRIEVE callback (§4). `REQUESTED`
is **uncapped**; the cap is metered when flow decides to dispatch RETRIEVE (§4).

Response `201`:
```json
{
  "id": "uuid", "warehouseId": "uuid", "workplaceId": "uuid", "huId": "uuid",
  "huCode": "TOTE-1", "skuId": "uuid", "skuCode": "SKU-1", "qty": 12, "mode": "STOCK_COUNT",
  "status": "REQUESTED", "arrivalSeq": null,
  "requestedAt": "iso", "inTransitAt": null, "queuedAt": null,
  "retrieveTaskId": "uuid", "conveyTaskId": null,
  "countTaskId": "uuid|null", "countLineId": "uuid|null", "locationId": "uuid"
}
```
On cap-full at dispatch time the entry still persists in `REQUESTED` (uncapped backlog); response is
still `201` with `status:"REQUESTED"` and `retrieveTaskId:null` (retrieval deferred). The old gtp
`409 QueueRejectedException` semantics are **gone** for inbound — REQUESTED never rejects.

### 3.2 Read a workplace's queue slice

`GET /api/flow/induction/queue?workplaceId={uuid}`

Returns the whole inbound pipeline `{REQUESTED, IN_TRANSIT, QUEUED}` for the workplace (R3 — includes
totes still in the ASRS), **DONE excluded**. Ordering: `QUEUED` first **in `arrival_seq` ASC**, then
`IN_TRANSIT` (by `in_transit_at`), then `REQUESTED` (by `requested_at`). Each element is the §3.1
response shape. Optional `?status=QUEUED` filter for callers wanting only the workable head.

### 3.3 Mark an entry DONE

`POST /api/flow/induction/entries/{entryId}/done`

Sets `status=DONE`, `done_at=now()`, writes a `DONE` trace row, returns the entry. Idempotent: a DONE
entry is returned unchanged. **Flow does NOT store back** — store-back stays a gtp reaction (§6).

### 3.4 Read an HU's transport trace timeline

`GET /api/flow/hu-trace?huId={uuid}` (optionally `&warehouseId={uuid}`)

Returns `hu_transport_trace` rows for the HU, **`ts` ASC** (timeline order):
```json
[
  { "id":"uuid","huId":"uuid","huCode":"TOTE-1","ts":"iso","point":"slot:A01",
    "event":"RETRIEVED","decision":"retrieved from slot A01","fromPoint":null,"toPoint":"conveyor",
    "workplaceId":"uuid","correlationId":"uuid","taskId":"uuid","inductionEntryId":"uuid" }
]
```

---

## 4. Lifecycle — exactly which callback drives each transition

All transitions are driven from `DeviceTaskService.completeFromCallback(...)` in flow (the existing
§3b channel). Extend it: after applying the device-task terminal state, branch on
`(task.command, task.status==COMPLETED)` and call `InductionQueueService`. Link entries to tasks via
the `retrieve_task_id` / `convey_task_id` columns (§2.1) — the callback looks the entry up by the
incoming `taskId`.

Lifecycle:

1. **request (§3.1)** → entry `REQUESTED`. Trace `REQUESTED`. Flow then meters the cap (§4.1): if
   `count{IN_TRANSIT,QUEUED for workplace+modeclass} < cap`, dispatch RETRIEVE now and store
   `retrieve_task_id`; else leave `REQUESTED` with no retrieve task (backlog).
2. **RETRIEVE device task COMPLETED → callback**: find entry by `retrieve_task_id`. Transition
   `REQUESTED → IN_TRANSIT`, set `in_transit_at=now()`. Trace `RETRIEVED` (point = source slot,
   to_point = conveyor) **and** `INDUCTED` (point = conveyor). **Dispatch the CONVEY device task**
   (family `CONVEYOR`, command `CONVEY`, payload carries `destinationWorkplaceId` /
   induction point + huId), store `convey_task_id`, correlationId = `huId`.
   - RETRIEVE FAILED → entry stays/returns to `REQUESTED` (or a `FAILED`-flavoured detail; do not
     invent a new status in 3c-1 — leave `REQUESTED` so a retry can re-meter). No CONVEY dispatched.
3. **CONVEY device task COMPLETED → callback** (= **arrival** at the induction point): find entry by
   `convey_task_id`. Transition `IN_TRANSIT → QUEUED`, set `queued_at=now()`, assign `arrival_seq`
   (next per-workplace sequence — **arrival order**, R2/R4). Trace `ARRIVED` (point = workplace
   induction point) then `QUEUED`.
4. **DONE (§3.3)** is operator-driven via the workplace, not a device callback.

Idempotency: reuse the existing guard — a duplicate/late callback for an already-terminal device task
is ignored, so the induction transition runs **once**. The induction transition itself must also be
idempotent (only advance from the expected prior state).

### 4.1 Cap (decision #3)

- Cap counts **only `{IN_TRANSIT, QUEUED}`** entries for the workplace (split by mode class
  PICKING-vs-other, matching today's gtp `maxInTransitPicking` / `maxInTransitOther`). `REQUESTED` is
  **uncapped**.
- Flow **meters retrievals into the cap**: a `REQUESTED` entry is only promoted by *dispatching its
  RETRIEVE* while `count{IN_TRANSIT,QUEUED} < cap`. The cap thus gates the `REQUESTED → IN_TRANSIT`
  edge, not the request.
- Cap values: flow reads the destination workplace's caps. 3c-1: fetch them from gtp
  (`GET /api/gtp/stations/{id}` exposes `maxInTransitPicking`/`maxInTransitOther`) via a small
  `WorkplaceClient`, or default if unavailable. (A station-config copy into flow is 3c-2; do not build
  it now.)
- Re-metering of the `REQUESTED` backlog happens (a) when a new request arrives and (b) when an entry
  reaches DONE (a slot frees). Both call the same `meterRetrievals(workplaceId)` pass.

---

## 5. Trace — row shape and exact write points

Row shape: §2.2. `HuTraceService.record(...)` appends one row; never updates. Exactly these write
points in 3c-1 (one row each unless noted):

| When (code site) | event | point / from→to |
| --- | --- | --- |
| request created (§3.1) | `REQUESTED` | point = `request`, workplace set |
| RETRIEVE callback COMPLETED (§4.2) | `RETRIEVED` | point = source slot (`slot:<locationId>`), to = `conveyor` |
| RETRIEVE callback COMPLETED, same call | `INDUCTED` | point = `conveyor` |
| CONVEY callback COMPLETED = arrival (§4.3) | `ARRIVED` | point = workplace induction point |
| CONVEY callback COMPLETED, same call | `QUEUED` | point = workplace; decision carries `arrival_seq` |
| DONE (§3.3) | `DONE` | point = workplace |

Each row sets `hu_id`, `correlation_id = hu_id`, `task_id` (the driving device task where applicable),
`induction_entry_id`, `workplace_id`. Cap/recirculate decisions: **3c-1 writes no recirculate trace**
(no recirculation yet); cap deferral may optionally annotate the `REQUESTED` row's `decision`
("retrieval deferred: cap full") but MUST NOT create a separate event type. DIVERT/MERGE/RECIRCULATE
emitted by the emulator are **3c-2, OUT OF SCOPE**.

---

## 6. Exact call-site changes (files + new client methods)

### 6.1 counting — `routeLine` → ONE call to flow induction request
File `services/counting/src/main/java/org/openwcs/counting/service/CountRoutingService.java`,
method `routeLine(...)`:
- **Remove** the `gtp.enqueue(stationId, ...)` call and the separate
  `flow.createTransport(warehouseId, family, "RETRIEVE", payload, huId)` call.
- **Replace with one call:** `flow.requestPresentation(...)` carrying
  `{warehouseId, workplaceId=stationId, workplaceKind:"GTP_STATION", huId, huCode, skuId, skuCode,
  qty, locationId, mode:"STOCK_COUNT", family, countTaskId, countLineId}`. Flow now owns
  retrieve+convey dispatch, so counting no longer dispatches the RETRIEVE itself.
- The capacity gate moves to flow (REQUESTED is uncapped; flow meters), so counting's "secure the
  slot first" comment is obsolete — routing always succeeds at request time; `routeLine` returns true
  when an HU exists. The `routed` idempotency flag semantics are unchanged.
- Client changes:
  - `services/counting/src/main/java/org/openwcs/counting/client/FlowClient.java`: **add**
    `UUID requestPresentation(InductionRequest req)` (a record mirroring §3.1); keep
    `createTransport` (still used elsewhere if any — verify; if unused after this change, leave
    `@Deprecated`).
  - `services/counting/.../client/HttpFlowClient.java`: implement `requestPresentation` →
    `POST /api/flow/induction/requests`, return the entry id.
  - `services/counting/.../client/GtpClient.java` + `HttpGtpClient.java`: the `enqueue(...)` method
    becomes **unused**; mark `@Deprecated` and stop calling it. Keep `findActiveCountingStation`
    (still needed to pick the destination workplace).

### 6.2 gtp — screen/queue feed reads flow; deprecate inbound enqueue; keep work cycle + store-back; DONE → flow
- `services/gtp/.../service/StationQueueService.java`:
  - `enqueue(...)`, `queue(...)`, `complete(...)`, `arrivalAt(...)`, `stockNodeDistance(...)`,
    in-transit-cap counting: **deprecated/legacy**, no longer the source of truth. Mark `@Deprecated`.
    Do not delete (out of scope). The `CONVEYOR_MPS` arrival computation is dead.
  - **Store-back stays in gtp** but is now triggered by the DONE flow, not by gtp's local `complete`.
    Extract `storeBack(...)` into a method invoked when the operator completes work (see below). It
    still calls `flow.createTransport(... "STORE" ...)` — unchanged.
- `services/gtp/.../api/StationQueueController.java`:
  - `POST /stations/{id}/queue` (inbound enqueue): **deprecated**; counting no longer calls it.
  - `GET /stations/{id}/queue`: the GTP screen stops using it (UI reads flow, §6.3). Leave in place.
  - `POST /queue/{entryId}/complete`: **re-point completion to flow.** On operator completion, gtp
    calls flow `POST /api/flow/induction/entries/{entryId}/done`, then runs gtp store-back. Introduce
    a new gtp `FlowInductionClient` for this (see below). Exceptions (dirty-tote/broken) keep working
    against the flow entry id (dirty-tote = DONE-without-store-back: call flow `done` then skip
    store-back).
- gtp client changes:
  - `services/gtp/.../client/FlowClient.java` + `HttpFlowClient.java`: **add** induction read/write
    methods used by the GTP backend where it still needs them:
    `List<InductionEntry> readQueue(UUID workplaceId)` → `GET /api/flow/induction/queue`,
    `void markDone(UUID entryId)` → `POST /api/flow/induction/entries/{id}/done`. Keep
    `createTransport` (store-back uses it).
- **Do NOT touch** `WorkCycleService.java` (present/put/confirm/close/task lines) — unchanged.

### 6.3 ui — GTP queue view reads flow; click-to-trace reads flow hu-trace
- `ui/src/gtpops/api.ts`:
  - `getStationQueue(stationId)`: re-point from `/api/gtp/stations/{id}/queue` to
    `/api/flow/induction/queue?workplaceId={stationId}`. Extend `StationQueueEntry` with
    `'REQUESTED'` status and `arrivalSeq`, and render `REQUESTED` (in-storage) rows (R3).
  - `completeQueueEntry(entryId)`: keep calling the gtp endpoint `POST /api/gtp/queue/{id}/complete`
    (gtp now fans out to flow + store-back). Do NOT call flow directly from the UI for completion.
  - exceptions/activate/deactivate: unchanged (still gtp).
- `ui/src/transport/api.ts` + `TransportScreen.tsx`:
  - Add `listHuTrace(huId): Promise<HuTraceRow[]>` → `GET /api/flow/hu-trace?huId={huId}`.
  - In the click-to-trace dialog, when the clicked task carries an HU id (derive from
    `payload.huId` / `correlationId`), call `listHuTrace` and render the `hu_transport_trace`
    timeline **instead of** the `byCorrelation` device-task list. Fall back to `listTaskTrace`
    (existing `byCorrelation`) when no HU id is resolvable, so nothing regresses.
  - `listTaskTrace` / `byCorrelation` stays (fallback); not removed in 3c-1.

### 6.4 flow — new code
- `domain/InductionQueueEntry.java`, `domain/HuTransportTrace.java` (extends/append-only),
  repos `InductionQueueEntryRepository`, `HuTransportTraceRepository`.
- `service/InductionQueueService.java` (request, meterRetrievals, onRetrieveCompleted,
  onConveyCompleted, markDone, readQueue) and `service/HuTraceService.java`.
- `api/InductionQueueController.java`, `api/HuTraceController.java`, DTOs.
- `service/DeviceTaskService.completeFromCallback`: add the command-keyed branch that calls
  `InductionQueueService` (RETRIEVE/BIN_RETRIEVE → onRetrieveCompleted; CONVEY → onConveyCompleted).
  The §3.1 request and §4 dispatch use the existing `DeviceClient`/`HttpDeviceClient` unchanged.
- `client/FlowProperties.java`: add `gtpBaseUrl` is already present (reuse it for the cap lookup /
  `WorkplaceClient`).

---

## 7. Emulator's role (confirm; no change needed in 3c-1)

- A **CONVEY device task already works** as the conveyor leg: `commandsByFamily["CONVEYOR"]` includes
  `CONVEY`, and `handleTask` runs the async §3b path (ack `ACCEPTED`, sleep the simulated latency,
  POST the terminal `COMPLETED` result to the flow `callbackUrl`) in `tasks.go` / `callback.go`.
- **CONVEY completion == arrival** at the induction point. Flow's CONVEY callback (§4.3) is the
  arrival event; the emulator delivers in **request order** in 3c-1, which is fine because flow sorts
  QUEUED by `arrival_seq` assigned at callback time — re-sequencing is contract-ready (R2) with no
  emulator change.
- **OUT OF SCOPE (3c-2):** the emulator modelling loop recirculation and reporting conveyor
  decision-point traces (DIVERT/MERGE/RECIRCULATE) back to flow. No emulator code changes in 3c-1.

---

## Binding test obligations (3c-1)

- flow: RETRIEVE callback → `IN_TRANSIT` (+ dispatches CONVEY); CONVEY callback → `QUEUED` with
  `arrival_seq` in arrival order (incl. an out-of-request-order case proving R2 sorts by arrival_seq);
  queue read includes `REQUESTED` (R3); cap counts only `{IN_TRANSIT, QUEUED}`, `REQUESTED` uncapped;
  one trace row per §5 write point; DONE endpoint idempotent.
- gtp: `StationQueueTest` / `StationExceptionTest` inbound assertions move to flow / become
  event-driven; gtp completion fans out to flow `done` + store-back.
- counting: `routeLine` issues exactly one flow `requestPresentation` and no `gtp.enqueue`.
</content>
</invoke>
