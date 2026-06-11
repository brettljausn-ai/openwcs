# equipment-emulator

One service that simulates **all** device families (ASRS, Conveyor, AMR, AutoStore) behind the
uniform internal device contract (build.md §8), so hardware emulation lives in a single place
instead of being duplicated inside each per-family Go adapter.

- Language: Go (stdlib only)
- Port: `9097` (`PORT` to override)
- Simulated latency: each command sleeps a modest per-family/command time before completing, and the
  result payload reports `durationMs`. `OPENWCS_EMULATOR_LATENCY_MS` overrides every command (`0` =
  instant). The device contract is still synchronous (flow blocks on the response), so the defaults
  are kept sub-second; a non-blocking async contract is the follow-up (Phase 3b).
- Fault injection: `OPENWCS_EMULATOR_FAULT_RATE=N` fails 1 in every N tasks with a simulated
  equipment fault (deterministic, `0`/unset = none). The failed result carries `fault: true`.
- Loop recirculation: `OPENWCS_EMULATOR_RECIRC_EVERY=N` makes every Nth `CONVEY` task recirculate the
  conveyor loop once before diverting to its destination (deterministic, `0`/unset = none), adding loop
  time so **arrival order diverges from dispatch order** (ADR-0007 R2). The result payload reports
  `recirculations` and the `decisions` (sorter `RECIRCULATED`/`DIVERTED` points) which flow writes to the
  HU transport trace (R4). Applies only to *atomic* CONVEYs (no `entryNode`), see below.
- **Live conveyor walk (ADR-0008 3d-2):** a `CONVEY` whose payload carries a non-empty `entryNode`
  (plus a `warehouseId` and the async `callbackUrl`) is executed as real hardware would: starting at
  `entryNode`, the tote is **scanned at every node** (`POST {flow}/api/flow/conveyor/routing-requests`
  with `{warehouseId, node, barcode}`, barcode = payload `huCode`, falling back to `huId`) and the
  emulator **obeys** flow's `RoutingDecision`: it decides nothing itself. `ROUTE` → travel the edge
  at `speedMps` (edge `cost` metres ÷ speed; topology fetched once per walk from
  `GET {flow}/api/flow/conveyor/topology`, missing edge = 1 m); `HOLD` → dwell ~1 s and rescan the
  same node (recirculation now *emerges* from flow's decision; `recircEvery` does not apply);
  `COMPLETE` → arrival; `NO_ROUTE`/`EXCEPTION` → task FAILED. The flow base URL is derived from the
  task's `callbackUrl` (scheme+host of `.../api/flow/device-tasks/{id}/result`). The COMPLETED result
  payload reports `walked: true`, `scans`, `recirculations` (= holds) and the `decisions` trail
  (`ROUTED`/`HELD`/`ARRIVED` per node) in the same shape flow's trace writer already parses. Safety
  rails: max 500 scans per walk, 5 s per HTTP call, one retry then FAIL. `latencyOverrideMs >= 0`
  overrides each edge travel / hold dwell (ms), so `0` makes walks instantaneous for tests/demos.
  Without an `entryNode` the atomic simulation above runs unchanged.
- Conveyor speed: `OPENWCS_EMULATOR_SPEED_MPS` (default `0.5` m/s per ADR-0008) sets the live-walk
  speed; live-tunable via `/config` (`speedMps`) for impatient demos.
- Live control: `GET /config` reports `{latencyOverrideMs, faultEvery, recircEvery, speedMps}`;
  `POST /config` (any field optional) changes them at runtime, e.g.
  `curl -XPOST .../config -d '{"speedMps":5}'`.
- Telemetry: `GET /state` reports real per-family completed/failed tallies (not synthetic).
- Endpoints: `POST /tasks` (device contract, all families), `GET`/`POST /config`, `GET /state`,
  `GET /healthz`, `GET /readyz`, `GET /` (info)

## How it fits

When the hardware-emulator flag (`HARDWARE_EMULATOR_ENABLED`, owned by master-data) is **ON**,
flow-orchestrator routes every device task here instead of to the real per-family adapters. When
it is **OFF**, flow routes to the real adapters (which hold the actual protocol drivers in
production). The emulator therefore always simulates and never opens a hardware connection — flow
only sends it traffic while the flag is on.

flow includes the task `family` in the `POST /tasks` body so the emulator can pick the right
command set and per-family state. Command sets are the union of the per-family adapters'.

## Run

```
go run .            # listens on :9097
go test ./...
```

See `EMULATOR-CONSOLIDATION.md` at the repo root for the consolidation roadmap (this is Phase 2).
