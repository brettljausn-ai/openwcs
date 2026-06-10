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

## Phase 1 — resolve dispatch family from storage type (no new service) — DONE (merged, PR #186)

Branch: `feat/dispatch-family-from-storage-type` (merged). Goal: stop hardcoding `ASRS`; dispatch
each device task to the adapter family that actually services the cell's storage.

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

## Phase 2 — emulator service at the boundary — CODE COMPLETE in PR (branch `feat/equipment-emulator-service`), NOT VERIFIED

Split into 2 (this PR) and 2b (follow-up). This PR stands up the single emulator and routes to it;
stripping the now-bypassed adapter emu is 2b (kept separate — reviewable, and harmless to leave:
when the flag is on the adapters get no traffic, when off they behave the same as a stripped one).

- [x] New `services/equipment-emulator` (Go, stdlib only) implementing `POST /tasks` for ALL families
      (union of the adapters' command sets), `/state`, health probes. Always simulates (no flag gate —
      flow only routes here while the flag is on). Tests: `tasks_test.go`. Dockerfile + README.
- [x] `flow-orchestrator` flag-aware routing: emulator ON → all families to `emulator-url`; OFF →
      per-family adapter. New `EmulatorModeClient`/`HttpEmulatorModeClient` (TTL-cached read of
      master-data `/api/master-data/emulator`); `HttpDeviceClient.resolveBaseUrl`; flow now puts
      `family` in the `/tasks` body (emulator needs it; real adapters ignore it).
      `FlowProperties.emulatorUrl`, `application.yml` `emulator-url` + `master-data-base-url`.
      Test: `HttpDeviceClientTest` (routing decision).
- [x] Deploy: compose `equipment-emulator` service (port 9097) + flow env `OPENWCS_EMULATOR_URL`/
      `OPENWCS_MASTER_DATA_BASE_URL` + gateway `OPENWCS_URI_EQUIPMENT_EMULATOR`; k8s Deployment+Service
      in `adapters.yaml` + `OPENWCS_EMULATOR_URL` in `config.yaml`. CI `go` job now also builds
      `services/equipment-emulator`.
- [ ] **Verify in CI** (`CI`: Go job builds/tests emulator; Java job builds flow + runs
      `HttpDeviceClientTest`). No JDK/Go/Docker locally — relies on CI. Then merge.
- [ ] Manual smoke (optional): emulator ON, run a stock count → tote retrieval COMPLETED via emulator;
      check `equipment-emulator` `/state` shows the ASRS command.

### Phase 2b — strip emulation from the four Go adapters — CODE COMPLETE in PR (branch `feat/strip-adapter-emulation`), NOT VERIFIED
- [x] Deleted `emumode.go` + `state.go` from all four adapters (asrs/conveyor/amr-geekplus/autostore)
      — the byte-identical flag poller and the sim state/telemetry. Realizes the de-duplication.
- [x] Each handler now validates the command then returns FAILED "hardware not connected (no live
      <family> adapter configured)" — real protocol path is still the TODO. `main.go`: dropped the
      `/state` route, the `emulator` info field, `StartEmulatorPoller`, and the emulator branch in
      `deviceLoop` (now just a stub heartbeat).
- [x] Compose: removed the adapters' now-dead `WCS_MASTER_DATA_URL` env + `master-data` dependency
      (they no longer poll the flag). Tests rewritten to assert not-connected behaviour.
- [x] Safe ordering: built on main *after* Phase 2 (#187) merged, so emulator mode keeps working
      (flow routes to equipment-emulator when the flag is on; adapters only get traffic when off).
- [ ] **Verify in CI** (`CI` Go job builds/tests all four adapters) and merge.

## Phase 3 — realism: simulated processing time — CODE COMPLETE in PR (branch `feat/emulator-simulated-latency`), NOT VERIFIED

Scoped to the safe, self-contained first slice (emulator-only). The async-contract + GTP-timing work
is split into Phase 3b below because it's a large cross-service change with a flagged decision, and
this repo can't be compiled/run locally.

- [x] Emulator simulates a per-family/command processing time (`latency.go`): each command sleeps a
      modest, sub-second default before COMPLETED; result payload now reports `durationMs`.
      `OPENWCS_EMULATOR_LATENCY_MS` overrides every command (`0` = instant); tests run at 0.
      Exposed in compose. Tests: `TestReportsSimulatedDuration` + `TestMain` zeroes latency.
- [ ] **Verify in CI** (Go job builds/tests the emulator) and merge.

### Phase 3b — async device contract — CODE COMPLETE in PR (branch `feat/async-device-contract`), NOT VERIFIED
Decision taken: **callback-on-flow** (not Kafka) — keeps the emulator dependency-free (stdlib http.Post)
and is a smaller delta. Backward-compatible: real adapters stay synchronous; the emulator only goes
async when flow supplies a `callbackUrl`.

- [x] Verified all consumers are fire-and-forget — counting `routeLine`, GTP store-back, and
      process-engine's delegate all read only the task **id**, never the completion. So async changes
      no consumer behaviour; tasks just go DISPATCHED → COMPLETED over time (more realistic).
- [x] flow: `DeviceTaskService.request` treats an `ACCEPTED` device response as "stay DISPATCHED";
      new idempotent `completeFromCallback` + `POST /api/flow/device-tasks/{id}/result`
      (`DeviceTaskResultCallback`). `HttpDeviceClient` sends a `callbackUrl` built from new
      `FlowProperties.selfBaseUrl` (`OPENWCS_FLOW_SELF_BASE_URL`, wired in compose + k8s).
- [x] emulator: with `callbackUrl` it acks `ACCEPTED` then runs the task in a goroutine and POSTs the
      terminal result back (`callback.go`); `runTask` extracted; sync fallback kept for no-callback.
      Tests: flow async-then-callback + idempotency; emulator async-calls-back.
- [ ] **Verify in CI** (flow Java tests + emulator Go tests). This is the highest-blast-radius PR and
      the one most worth a real run-through before merge — no JDK/Go/Docker locally.

### Phase 3c — move GTP arrival-timing physics into the emulator — NOT STARTED
- [ ] Move GTP's arrival-timing (`StationQueueService` distance ÷ 0.5 m/s) into the emulator so there's
      a single owner of movement time. Careful: GTP's in-transit→queued state machine depends on arrival
      time, so this needs its own design + verification. Now unblocked by the async contract above.

## Phase 4 — fault injection + telemetry + live control — CODE COMPLETE in PR (branch `feat/emulator-fault-injection`), NOT VERIFIED

Emulator-only, fully Go-unit-tested (safe to ship via CI). Chosen ahead of Phase 3b because async is
the risky, decision-gated change and this is high demo value at low risk.

- [x] Fault injection (`faults.go`): deterministic — fail 1 in every N tasks (`OPENWCS_EMULATOR_FAULT_RATE`,
      0 = none). Failed result carries `fault: true`. Counters in `state.go`.
- [x] Real telemetry (`state.go`): `/state` reports per-family completed/failed tallies + totals
      (replaced the synthetic tick-faults). Snapshot also echoes the live config.
- [x] Live control (`config.go`): latency + fault rate are atomics, seeded from env at startup and
      tunable at runtime via `GET`/`POST /config` (e.g. `curl -XPOST .../config -d '{"faultEvery":4}'`).
- [x] Compose exposes `OPENWCS_EMULATOR_FAULT_RATE`. Tests: fault-every-Nth + /config endpoint.
- [ ] **Verify in CI** (Go job) and merge.
- Deferred (optional follow-up): a UI control panel — can't run the React app locally to verify, so
      `POST /config` (curl-able) is the interim control surface.

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
