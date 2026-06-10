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
- Live control: `GET /config` reports the current `{latencyOverrideMs, faultEvery}`; `POST /config`
  (either field optional) changes them at runtime — e.g. `curl -XPOST .../config -d '{"faultEvery":4}'`.
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
