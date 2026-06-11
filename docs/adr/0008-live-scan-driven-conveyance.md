# ADR 0008 — Live scan-driven conveyance: the adapter scans, the flow controller answers

Status: Proposed (2026-06). Builds on ADR-0007 (induction pipeline, HU transport trace, async device
contract) and PR #212 (return-to-storage leg).

## Context

ADR-0007 separated the roles correctly on paper — and the flow-orchestrator already exposes the whole
control protocol: **route plans** (`assignRoute(barcode, targets)`), and the per-scan decision endpoint
`POST /api/flow/conveyor/route` (`ScanRequest{warehouseId, node, barcode}` →
`RoutingDecision{ROUTE|HOLD|COMPLETE|NO_ROUTE|EXCEPTION, exitCode, toNode, …}`), including loop-capacity
`HOLD`. **Nothing calls it.** The equipment-emulator simulates a `CONVEY` as one atomic sleep
(~1 s), invents its own recirculation (`recircEvery`), and reports "decisions" post-hoc in the result
payload.

The consequences surfaced in the hardware visualisation (PR #211): transports complete faster than any
poll, no equipment is attributable (tasks carry no node/equipment identity), and the UI had to *invent*
motion (family fallback + synthetic glide). The deeper issue is architectural, not visual — the contract
each component should honour is:

- **Flow controller** — receives "bring HU to target(s)", creates the transports, and **answers the
  adapters at scan/query points**. It is the only component that decides routes.
- **Adapter / emulator (hardware)** — physically moves HUs. A conveyor moves at **0.5 m/s**. At each
  scan/query/divert point it reports the barcode and obeys the controller's answer. It decides nothing.
- **Workplace (GTP, …)** — works with HUs physically presented to it.
- **Visualisation** — represents observed transport state; creates nothing.

This is also exactly how real conveyor PLCs integrate with a WCS (scan telegram in → routing telegram
out), so the emulator exercising this loop validates the contract real adapters will use.

## Decision

1. **Flow assigns a route plan per transport.** When dispatching a `CONVEY` (induction outbound *and*
   the ADR-0007/PR-#212 return leg), flow resolves the transport's **entry node** and **destination
   node** from the projected routing graph, calls `assignRoute(huCode, [destinationNode])`, and puts
   `entryNode` (+ `destinationNode`, informational) in the task payload. If either node cannot be
   resolved (no projected graph), the payload carries no `entryNode` and the emulator falls back to
   today's atomic behaviour — nothing breaks on an un-projected warehouse.

2. **The emulator walks the graph and scans.** A `CONVEY` with an `entryNode` runs in *live walk* mode
   (async, via the existing ACCEPTED + callback contract): starting at `entryNode`, it POSTs a scan to
   flow at every node, obeys the `RoutingDecision` — `ROUTE` → travel the edge to `toNode` taking
   `edge cost (m) ÷ speed` seconds; `HOLD` → wait ~1 s and rescan (loop-full recirculation now *emerges*
   from flow's decision instead of being simulated); `COMPLETE` → POST the result callback — and fails
   the task on `NO_ROUTE`/`EXCEPTION`. **Speed is 0.5 m/s** (`speedMps` in `/config`, live-tunable like
   latency/fault injection). The internal `recircEvery` simulation does not apply to live walks.

3. **Flow records every scan in the HU transport trace.** `decide()` appends a `SCANNED` trace row
   (point = node code, decision = the answer: routed to X / held: loop full / completed) when the
   barcode belongs to an active transport. The trace becomes a true, *live* material-flow timeline —
   R4 of ADR-0007 observed rather than reconstructed.

4. **The visualisation replays; it never invents.** Totes are positioned from the scan trail: at the
   last scanned node, interpolating toward the answered `toNode` at 0.5 m/s. The family fallback and
   synthetic glide from PR #211 are removed once this lands.

## Consequences

- Transport duration becomes physical (a 30 m route takes 60 s), so the induction pipeline's
  `IN_TRANSIT` state is finally observable — in the queue drawer, the transport screen and the twin.
- Flow's `decide()` is exercised continuously, surfacing routing-graph gaps (missing projection,
  unreachable nodes) as visible `NO_ROUTE` failures instead of silently "working".
- The emulator↔flow chatter (one scan per node) is the realistic load profile for real PLC adapters.
- Demo timings change: a count tote takes ~tens of seconds to arrive instead of ~1 s. That is the
  point — the emulator emulates hardware, and hardware takes time. `speedMps` can be raised for
  impatient demos.

## Implementation phases

- **3d-1 (flow):** node resolution (workplace → station node; storage → discharge node), route-plan
  assignment on both CONVEY dispatches, `entryNode` payload, `SCANNED` trace rows in `decide()`.
- **3d-2 (emulator):** live-walk CONVEY (graph fetch, scan loop, edge-time travel at `speedMps`,
  HOLD/COMPLETE handling, atomic fallback when no `entryNode`).
- **3d-3 (ui):** twin replays the scan trail; remove fallback/synthetic glide; queue drawer + transport
  screen gain nothing new but finally *show* IN_TRANSIT for its real duration.
