# ADR 0007 — Conveyor transport as a workplace-agnostic layer; arrival-driven workplace queueing

Status: Accepted (2026-06) — open questions resolved; see "Decisions" below.

## Context

This is the last piece of the hardware-emulator consolidation (Option B; see
`EMULATOR-CONSOLIDATION.md`). Phases 1–3b + 4 delivered: one `equipment-emulator` simulating all
device families, family-correct dispatch, simulated per-command processing time, fault injection,
and — crucially for this ADR — an **asynchronous device contract** (ADR-less PR #193): flow dispatches
a task, the device acks `ACCEPTED`, and the result is POSTed back to
`/api/flow/device-tasks/{id}/result`. That dispatch→callback primitive is the foundation here.

What remains is **conveyor movement of totes to workplaces**, and three product requirements force it
to be modelled as a transport layer rather than a GTP detail:

- **R1 — Workplace-agnostic.** Conveyors feed GTP stations today, but other layouts use other
  technologies / workplaces (put-walls, AMR rack-to-person, manual stations, packing). Conveyor
  transport must be a shared layer that *delivers to* a workplace, not logic embedded in `services/gtp`.
- **R2 — Loop re-sequencing.** A conveyor can be a recirculating **loop**: the order totes physically
  arrive at a workplace's induction point can differ from the order they were requested. The workplace
  must queue totes in **physical arrival order**. The emulator may deliver in request order for now —
  but the contract must **not assume** it, because physical reality re-sequences.
- **R3 — Full-pipeline queue view.** The workplace screen must show **all** totes currently required for
  the workplace — including totes **not yet retrieved** from the ASRS (still in storage / being
  retrieved) — not just the ones physically present at the induction point.
- **R4 — Transport trace.** Every **function point** an HU is seen at must record a **timestamped** trace
  event with the **decision** made there: retrieved from slot, inducted onto the conveyor, each conveyor
  decision point (divert / merge / sorter / recirculate-because-full), arrived at an induction point,
  queued, presented, stored back. Queryable by HU. This is the audit trail that makes loop recirculation
  and re-sequencing explainable, and it powers the existing Transport screen's click-to-trace. Today the
  only "trace" is the device tasks grouped by `correlationId` (`DeviceTaskService.byCorrelation`) — coarse
  (one row per dispatched task, no decision points); R4 makes it a true material-flow timeline.

### Current behaviour (and why it can't meet R1–R4)

`services/gtp` `StationQueueService`:

- Computes arrival **locally**: `arrivalAt = now + distance ÷ 0.5 m/s` for a `CONVEYOR` move with a
  distance, else `now`. (Count totes pass the storage family + no distance, so they land `QUEUED`
  instantly — no travel phase at all.)
- Creates the queue entry already `IN_TRANSIT`/`QUEUED` at enqueue time; states are
  `{IN_TRANSIT, QUEUED, DONE}`; the queue is ordered by the computed `arrivalAt`.

This violates **R1** (conveyor logic lives inside GTP), **R2** (a per-tote distance calc can't represent
a loop; order is computed, not observed), and **R3** (there is no pre-retrieval state, and the entry is
born already in transit).

## Decision

1. **Conveyor movement is owned by the equipment/transport layer (the emulator's `CONVEYOR` family),
   not by any workplace.** A conveyance is a transport task with a *destination induction point*; the
   transport layer simulates travel (and, in reality, loop recirculation) and emits an **arrival event**
   when the tote reaches that induction point.

2. **The flow controller owns the queue; workplaces request and read it.** The induction queue/pool of
   inbound totes lives in **flow-orchestrator** (the material-flow controller), keyed by destination
   workplace — *not* in the workplace. This is required because **the same HU may be requested by more
   than one workplace** (e.g. two GTP stations need the same SKU tote); only a central pool can arbitrate
   that contention and sequence retrievals. A workplace (GTP, put-wall, …) **sends a request** to flow
   and **reads its slice of the queue** back; it never owns the queue.

3. **A workplace-agnostic arrival contract, back to flow.** Conveyor movement is a device task (async,
   §3b), so its arrival is just the existing **result callback to flow** — and flow owns the queue, so it
   applies the state transition directly (no extra per-workplace arrival endpoint). The conveyor never
   knows what a "GTP station" is; flow routes the event to the right queue entry by destination.

4. **Arrival-driven; arrival order is authoritative (R2).** Flow marks a tote `QUEUED` only when its
   arrival event fires, ordered by **actual arrival time**, never request order — so a recirculating loop
   re-sequences correctly with zero workplace change.

5. **Full-pipeline lifecycle, capped on presence not plan (R3 + cap).** Flow creates the entry when work
   is *requested* (before retrieval) and advances it by transport events, not timers:

   ```
   REQUESTED ──(retrieve done; on conveyor)──▶ IN_TRANSIT ──(arrived at induction)──▶ QUEUED ──▶ DONE
   (in storage / being retrieved)              (on the conveyor)                      (workable)
   ```

   The workplace screen shows `{REQUESTED, IN_TRANSIT, QUEUED}` — the whole inbound pipeline, including
   totes still in the ASRS. The per-station **in-transit cap counts only `{IN_TRANSIT, QUEUED}`** (totes
   actually retrieved / present); the `REQUESTED` backlog is unbounded and flow meters retrievals into the
   cap. GTP stops computing `arrivalAt` — that timing is now an emulator simulation detail.

6. **Per-HU transport trace (R4).** Flow persists an append-only, timestamped **trace** of every function
   point an HU passes, each row carrying `{ ts, huId, point, event, decision, fromPoint→toPoint,
   correlationId/taskId, workplace }` — e.g. `RETRIEVED @ slot A01`, `INDUCTED @ conveyor`,
   `DIVERT @ sorter-3 → station-2`, `RECIRCULATE @ sorter-3 (station-2 cap full)`, `ARRIVED @ station-2`,
   `QUEUED`, `PRESENTED`, `STORED_BACK`. Flow records the points it decides (request, lifecycle
   transitions, cap/arbitration outcomes); the **emulator reports the conveyor decision points** it
   simulates (diverts/merges/recirculation) back to flow as trace events on the §3b callback channel.
   The trace is queryable by HU and **supersedes/extends** today's `byCorrelation` device-task grouping,
   feeding the existing Transport screen click-to-trace. Each lifecycle transition in decision #5 emits a
   trace row, so the timeline and the queue state stay consistent.

### The contract, concretely

The **workplace requests; flow owns the queue and orchestrates; the transport layer is a dumb, reusable
delivery mechanism:**

1. The requester/workplace asks flow: "present HU H (or SKU X) at workplace W." Flow creates a queue entry
   `REQUESTED` in its pool, keyed by W. Multiple workplaces requesting the same H are all visible to flow,
   which arbitrates.
2. Flow orchestrates the journey as device tasks (async, §3b): **RETRIEVE** (ASRS/AutoStore) then
   **CONVEY** (CONVEYOR), both handled by the emulator. The emulator's existing result callbacks drive the
   transitions:
   - RETRIEVE completes → tote is on the conveyor → flow sets `REQUESTED → IN_TRANSIT`.
   - CONVEY arrives at W's induction point → flow sets `IN_TRANSIT → QUEUED` (in arrival order).
3. The workplace **reads its queue** from flow and works the `QUEUED` entries in arrival order; on
   completion it tells flow → `DONE` → (for GTP) store-back as today.

Because flow holds queues for any destination and the conveyor just delivers, the same mechanism serves a
GTP station, a put-wall, or a packing desk — satisfying **R1**.

## Alternatives considered

- **A — Model conveyor timing inside GTP (the current/“just wire a distance” approach).** Rejected: GTP
  re-implements transport per workplace type (fails R1) and can only compute a per-tote arrival, never a
  loop re-sequence (fails R2).
- **B — Kafka `device.results` / a transport-events topic for arrivals.** Viable and closer to the
  documented production contract, but heavier (a broker round-trip + consumer per workplace). The
  callback-URL approach reuses the §3b primitive and is a smaller delta; a topic can replace the
  callback later without changing the workplace-side state machine.
- **C — Workplace polls flow for transport status.** Chatty, loses push semantics, and still needs the
  workplace to know about device tasks. Rejected.

## Consequences

- **The station queue relocates from `services/gtp` to `services/flow-orchestrator`** (the big one). Today
  GTP owns `station_queue_entry` (gtp schema) and `StationQueueService` (enqueue / queue / complete /
  store-back / caps). Per decision #2, the induction queue/pool becomes a **flow** concept (new table in
  the flow schema, keyed by destination workplace), with the `REQUESTED → IN_TRANSIT → QUEUED → DONE`
  lifecycle driven by the device-task callbacks flow already receives. GTP becomes a **client**: it
  requests presentation and reads its queue slice from flow; the workstation screen reads from flow.
- **Cap.** The per-station in-transit cap counts only `{IN_TRANSIT, QUEUED}` (decision #3); the
  `REQUESTED` backlog is unbounded and flow meters retrievals into the cap.
- **Test impact.** `StationQueueTest` / `StationExceptionTest` (gtp) assert the current immediate-`QUEUED`,
  computed-arrival, GTP-owned behaviour; they move/rewrite to flow and become **event-driven**. New tests
  (flow): RETRIEVE callback → `IN_TRANSIT`; CONVEY arrival → `QUEUED` in arrival order (incl. an
  out-of-request-order case for R2); the queue view includes `REQUESTED` totes (R3); cap counts only
  `{IN_TRANSIT, QUEUED}`.
- **Cross-service & contention.** Spans requester → flow → emulator, and lets flow arbitrate when two
  workplaces request the same HU. Highest-blast-radius change of the consolidation; **changes
  user-visible workstation timing**.
- **Transport trace (R4).** New append-only `hu_transport_trace` table in the flow schema; flow writes a
  row on every request/lifecycle/cap-decision, and the emulator reports conveyor decision points as trace
  events. New query-by-HU endpoint; the Transport screen's click-to-trace switches from the coarse
  `byCorrelation` device-task list to this timeline.
- **Upside.** Non-GTP workplaces reuse the same flow queue + transport + arrival contract; the emulator
  can later add loop recirculation with **zero** workplace changes.

## Phasing & verification

- **3c-1 (MVP):** stand up the flow-owned induction queue (`REQUESTED → IN_TRANSIT → QUEUED → DONE`,
  cap on `{IN_TRANSIT, QUEUED}`), driven by RETRIEVE + CONVEY device-task callbacks; the emulator runs a
  CONVEY leg and calls back on arrival (in request order); GTP requests presentation and reads its queue
  slice from flow; relocate the station queue out of `services/gtp`. Delivers R1 + R3 and the on-screen
  pipeline; R2 is *supported by the contract* even though the emulator doesn't yet re-sequence. Writes an
  `hu_transport_trace` row on each lifecycle transition (R4, the trace timeline). Given the size, this can
  itself be split (e.g. introduce the flow queue + lifecycle + trace first, switch the GTP screen + the
  click-to-trace to read flow second).
- **3c-2 (optional):** the emulator models loop recirculation, so arrival order visibly diverges from
  request order — R2 made real.

**Verification:** this cannot be built or run in the current authoring environment (no JDK/Go/Docker).
It must be implemented against this agreed contract and validated with a **real run-through** (emulator
on → request work → watch a tote go `REQUESTED → IN_TRANSIT → QUEUED` on the workstation screen, and the
first `QUEUED` presented) before merge.

## Decisions (signed off 2026-06)

1. **Arrival contract:** callback-URL (reusing the §3b device-result callback to flow). Kafka /
   transport-events topic can replace it later without changing the queue state machine.
2. **Granularity:** the MVP includes the full `REQUESTED → IN_TRANSIT → QUEUED` lifecycle (the
   RETRIEVE-completion → `IN_TRANSIT` transition is in scope, not deferred).
3. **Cap:** a `REQUESTED` (not-yet-retrieved) tote does **not** count against the station in-transit cap;
   the cap counts only `{IN_TRANSIT, QUEUED}`.
4. **Queue ownership:** **flow-orchestrator owns the queue/pool**; GTP (and other workplaces) only *send a
   request* and *read* their slice. Rationale: more than one workplace can request the same HU, so the
   queue must be central to arbitrate. (This relocates the existing GTP station queue into flow — see
   Consequences.)
