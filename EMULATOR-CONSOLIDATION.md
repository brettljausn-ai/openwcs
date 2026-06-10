# Hardware-emulator consolidation — Option B roadmap & progress

Working doc for consolidating hardware emulation into one layer. Lives on branch
`feat/dispatch-family-from-storage-type` (Phase 1). Check items off as phases land.

## Why (the finding)

In emulator mode the "emulate all hardware" behaviour is **not** one layer — it's split and
ASRS-skewed:

- The Go device adapters (`services/adapters/{asrs,conveyor,amr-geekplus,autostore}`) each carry
  their **own copy** of a shallow emulation (`emumode.go` flag poller + `tasks.go` that just acks
  `COMPLETED` instantly with counters). No shared library; `emumode.go` is byte-identical across all
  four. They emulate the *contract*, not the physics (no timing/movement/occupancy).
- The behavioural simulation that actually matters (tote travel time, arrival, store-back) lives in
  **GTP `StationQueueService`** (Java), not the adapters.
- Every device task the demo generates was dispatched with a **hardcoded `family = "ASRS"`**, so a
  conveyor/AutoStore/AMR-stored SKU was "handled" by the ASRS sim. That's why it *looked* ASRS-only.

**Decision: Option B** — a single dedicated emulator service sitting *at* the adapter boundary
(same `/tasks` contract, so the seam stays tested), eventually owning timing/physics. Reached in
phases so each is independently shippable.

Storage types (`master-data` `storage_block.storage_type`):
`SHUTTLE_ASRS | CRANE_ASRS | AUTOSTORE | AMR_GTP | MANUAL_PICK | RESERVE_RACK`.
Adapter families (`flow-orchestrator` adapter keys): `CONVEYOR | ASRS | AMR | AUTOSTORE`.
Mapping used everywhere: SHUTTLE/CRANE→`ASRS`, AUTOSTORE→`AUTOSTORE`, AMR_GTP→`AMR`,
MANUAL_PICK/RESERVE_RACK→none (operator-handled, not routed).

---

## Phase 1 — resolve dispatch family from storage type (no new service) — CODE COMPLETE, NOT YET VERIFIED

Branch: `feat/dispatch-family-from-storage-type`. Goal: stop hardcoding `ASRS`; dispatch each device
task to the adapter family that actually services the cell's storage.

- [x] Add `MasterDataClient.deviceFamilyOf(storageType)` mapping helper (counting)
      — `services/counting/.../client/MasterDataClient.java`
- [x] Counting `CountRoutingService`: resolve family per line from its storage type and thread it
      through `routeLine` → both `gtp.enqueue(...)` and `flow.createTransport(...)`; removed the
      `FAMILY="ASRS"` constant (kept `DEFAULT_FAMILY="ASRS"` fallback)
      — `services/counting/.../service/CountRoutingService.java`
- [x] GTP: new `MasterDataClient` interface + `HttpMasterDataClient` (resolves a location's storage
      type via `/api/master-data/locations/{id}` → `/api/master-data/storage-blocks/{id}`), with the
      same `deviceFamilyOf` mapping
      — `services/gtp/.../client/MasterDataClient.java`, `HttpMasterDataClient.java`
- [x] GTP `StationQueueService.storeBack`: resolve family from the slotting-chosen **destination**
      location's storage type; replaced hardcoded `"ASRS"`. Constructor gained `MasterDataClient`.
      — `services/gtp/.../service/StationQueueService.java`
- [x] GTP config: `openwcs.gtp.master-data-base-url` (default `http://localhost:8081`)
      — `services/gtp/src/main/resources/application.yml`
- [x] Tests: counting `autostoreCellRoutesToTheAutostoreFamily`; GTP
      `storeBackDispatchesToTheDestinationStorageFamily` (AUTOSTORE) + existing store-back test
      stubbed to SHUTTLE_ASRS; added `@MockBean MasterDataClient` to `StationExceptionTest`
      — `CountRoutingTest.java`, `StationExceptionTest.java`
- [ ] **Build & run tests** (`./gradlew :services:counting:test :services:gtp:test`) — BLOCKED
      locally: no JDK 17+ and no Docker on this machine; Testcontainers needs both. Must run in CI.
- [ ] **Open PR**, confirm CI (`CI / Java build & test`) green, review, merge.
      (Note: the red "Analyze (java-kotlin)" CodeQL check is a repo-settings issue — "Code quality not
      enabled" — NOT a code failure and NOT blocking. Separate from the test workflow.)

### Phase 1 verification checklist for when back online
1. Push branch, open PR against `main`.
2. Watch `CI / Java build & test` (Java 21 + Docker). Counting + GTP suites must pass.
3. If green: merge. If red: read the failing test; most likely a Mockito stub or an import.

---

## Phase 2 — emulator service skeleton at the boundary — NOT STARTED

- [ ] New `services/equipment-emulator` (Go) implementing the `/tasks` contract for all families.
- [ ] `flow-orchestrator`: when `HARDWARE_EMULATOR_ENABLED` is on, route ALL families to the emulator
      URL; when off, route to the real per-family adapters. (Flag-aware resolution in
      `HttpDeviceClient`/`FlowProperties`, single `OPENWCS_EMULATOR_URL`.)
- [ ] Lift the duplicated `emumode.go`/`tasks.go` emulation out of the four Go adapters; adapters
      become pure real-hardware drivers (or thin "not connected" stubs when emulator off).
- [ ] Keep behaviour-equivalent to today first (instant COMPLETED) for parity, just centralized.

## Phase 3 — realism: async completion + timing — NOT STARTED

- [ ] Emulator returns COMPLETED after a simulated per-family/command duration.
- [ ] Move device contract from sync-blocking (`DeviceTaskService` blocks on result) to **async**
      (record DISPATCHED; emulator posts result back — the eventual Kafka `device.results`, or a
      callback). THIS IS THE HIGH-IMPACT DECISION.
- [ ] Move GTP's arrival-timing physics (`StationQueueService` distance ÷ 0.5 m/s) into the emulator
      so there's a single owner of movement time. Careful: GTP's in-transit→queued state machine
      depends on arrival time.

## Phase 4 — failure injection + telemetry + control UI — NOT STARTED

- [ ] Per-family configurable fault rates; load-derived telemetry; emulator control panel.

## Open decisions to confirm before Phase 2/3
- (a) Emulator in Go (reuse adapter code) — leaning yes.
- (b) Sync→async device contract — the biggest call.
- (c) Does the emulator own movement timing (pull it out of GTP), or only device acks?

## Optional follow-up (separate from Option B)
- Consider an ADR in the repo capturing the Option B decision (problem / options / decision /
  consequences).
- The station in-transit cap (`max_in_transit_other` default 2) correctly back-pressures concurrent
  shuttle counts; if a count batch should never trip it, raise the demo station's cap or have routing
  defer instead of marking FAILED. (Came up during the PR #185 counting/HU fix.)
