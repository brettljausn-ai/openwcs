# ADR 0007 — Conveyor transport as a workplace-agnostic layer; arrival-driven workplace queueing

Status: Proposed (2026-06)

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

### Current behaviour (and why it can't meet R1–R3)

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

2. **A workplace-agnostic arrival contract.** The transport request carries an `arrivalCallbackUrl` —
   the destination workplace's induction endpoint — mirroring the §3b result callback. On arrival the
   transport layer POSTs an "arrived" event there. GTP implements
   `POST /api/gtp/stations/{id}/queue/arrived` (body: `huId`, correlation); other workplace types
   implement the same hook. **The conveyor never knows what a "GTP station" is** — it delivers to a URL.

3. **Arrival-driven queueing; arrival order is authoritative.** A workplace marks a tote `QUEUED` only
   when its arrival event fires, ordered by **actual arrival time**, never by request order. This makes
   R2 correct by construction: if the emulator (or, later, real hardware) re-sequences on a loop, the
   queue reflects it with no workplace change.

4. **Full-pipeline entry lifecycle (R3).** The queue entry is created when the work is *requested*
   (before retrieval) and advances by events, not timers:

   ```
   REQUESTED ──(retrieve done; on conveyor)──▶ IN_TRANSIT ──(arrived at induction)──▶ QUEUED ──▶ DONE
   (in storage / being retrieved)              (travelling on the conveyor)            (workable)
   ```

   The workplace screen shows the ACTIVE set = `{REQUESTED, IN_TRANSIT, QUEUED}`, so an operator sees
   the whole inbound pipeline — including totes still in the ASRS.

5. **GTP stops computing `arrivalAt` locally.** It consumes transport events. Any distance/speed timing
   becomes an emulator-side simulation detail, not workplace logic.

### The contract, concretely

The **workplace orchestrates its own inbound totes** and stays the owner of its queue; the transport
layer is a dumb, reusable delivery mechanism:

1. The requester (counting routing, order fulfilment, …) tells the workplace "expect tote H for this
   work" → the workplace creates a queue entry in `REQUESTED` and requests a **delivery** of H to its
   induction point, passing `arrivalCallbackUrl = <its own induction endpoint>`.
2. The transport layer executes the journey as device tasks (async, §3b): **RETRIEVE** (ASRS/AutoStore)
   then **CONVEY** (CONVEYOR). It emits up to two lifecycle signals to the workplace:
   - **DEPARTED** (left storage, now on the conveyor) → workplace `REQUESTED → IN_TRANSIT`. *(Optional
     granularity; MVP may skip it.)*
   - **ARRIVED** (reached the induction point) → workplace `IN_TRANSIT/REQUESTED → QUEUED`.
3. The workplace works its `QUEUED` entries in arrival order; completing one marks it `DONE` and (for
   GTP) triggers store-back as today.

Because the only thing the transport layer needs is the destination's `arrivalCallbackUrl`, the same
mechanism serves a GTP station, a put-wall, or a packing desk — satisfying **R1**.

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

- **GTP queue rework.** New `REQUESTED` state and an `arrived` endpoint; `enqueue` creates `REQUESTED`
  and no longer computes `arrivalAt`; `queue()`/the active set includes `REQUESTED`. The per-mode
  in-transit cap semantics must be revisited (does a `REQUESTED`, not-yet-retrieved tote count against
  the cap? — likely yes, it's committed inbound work).
- **Migration.** `station_queue_entry.status` gains `REQUESTED`; existing rows are unaffected.
- **Test impact.** `StationQueueTest`/`StationExceptionTest` assert the *current* immediate-`QUEUED`,
  computed-arrival behaviour; they must become **arrival-event driven**. New tests: the `arrived`
  endpoint promotes in arrival order (incl. an out-of-request-order case for R2); the screen/active set
  includes `REQUESTED` totes (R3).
- **Cross-service.** A new transport "destination + arrival callback" concept spanning the requester,
  flow, and the emulator; this is the highest-blast-radius change of the consolidation and **changes
  user-visible GTP timing**.
- **Upside.** Non-GTP workplaces reuse the same transport + arrival contract; the emulator can later add
  loop recirculation / re-sequencing with **zero** workplace changes.

## Phasing & verification

- **3c-1 (this ADR's MVP):** `REQUESTED` state + arrival endpoint + arrival-driven `QUEUED`; the
  emulator emits `ARRIVED` on the convey leg (in request order). Delivers R1 + R3 and the on-screen
  pipeline; R2 is *supported by the contract* even though the emulator doesn't yet re-sequence.
- **3c-2 (optional):** the emulator models loop recirculation, so arrival order visibly diverges from
  request order — R2 made real.

**Verification:** this cannot be built or run in the current authoring environment (no JDK/Go/Docker).
It must be implemented against this agreed contract and validated with a **real run-through** (emulator
on → request work → watch a tote go `REQUESTED → IN_TRANSIT → QUEUED` on the workstation screen, and the
first `QUEUED` presented) before merge.

## Open questions for sign-off

1. Confirm the **callback-URL** arrival contract (Alternative B/Kafka can come later) — OK?
2. Is the **DEPARTED** signal (the `IN_TRANSIT` granularity) wanted in the MVP, or is `REQUESTED → QUEUED`
   on arrival enough to start?
3. Does a `REQUESTED` (not-yet-retrieved) tote **count against the station in-transit cap**?
4. Who creates the queue entry — the **workplace on request** (recommended, keeps it the queue owner) or
   the requester directly?
