# equipment-emulator

One service that simulates **all** device families (ASRS, Conveyor, AMR, AutoStore) behind the
uniform internal device contract (build.md §8), so hardware emulation lives in a single place
instead of being duplicated inside each per-family Go adapter.

- Language: Go (stdlib only)
- Port: `9097` (`PORT` to override)
- Endpoints: `POST /tasks` (device contract, all families), `GET /state` (simulated telemetry),
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
