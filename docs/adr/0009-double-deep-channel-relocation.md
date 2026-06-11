# ADR 0009 — Double-deep channel management: WCS-owned relocation (dig-out) moves

Status: Proposed (2026-06). Builds on ADR-0008 (scan-driven conveyance), the return-to-storage leg
(PR #212) and HU location booking (PR #218).

## Context

Shuttle channels can be multi-deep: the location model already carries the exact cell coordinate —
`aisle`, `side`, `cellX` (along the aisle), `cellY` (vertical shuttle level), `cellZ`
("1 = aisle face … N = deepest"), `lane_depth`. A retrieve of an HU at `cellZ 2` is physically
impossible while another HU occupies `cellZ 1` of the same channel (same `aisle+side+cellX+cellY`):
the shuttle must first **relocate the blocker** — and because a shuttle serves one level and a lift
move is expensive, the relocation target must share the blocker's **`cellY`** (one shuttle move, no
lift), preferably in the same aisle.

Today the emulator's `RETRIEVE` is a single simulated latency: no occupancy check, no relocation —
a blocked retrieve "succeeds". And no component could do better, because until PR #218 nobody kept
HU locations true during transport.

Two industry patterns exist:

- **Vendor-style (black box):** the shuttle subsystem digs out internally; the WCS just sees a slower
  retrieve. Rejected for openWCS: the blocker would move *without inventory knowing* (breaking the
  registry truth we just established) and the move would be invisible in the transport trace.
- **WCS-owned channel management (chosen):** the WCS knows occupancy (inventory HU registry), decides
  the relocation target (slotting owns placement policy and the z-coordinates), creates the relocation
  transport (flow), books the move, and traces it. A future *real* vendor adapter that digs out
  internally can still **report** its relocations through the same contract so truth is preserved.

## Decision

1. **Slotting plans the dig-out.** New endpoint
   `POST /api/slotting/relocation-plan` `{warehouseId, locationId}` (the retrieve's source slot) →
   `{steps: [{huId, huCode?, fromLocationId, toLocationId}, …]}` ordered front-to-back, empty when
   the channel is clear. Occupancy comes from the inventory HU registry (`locationId` per HU);
   channel siblings from the master-data cell coordinates. Target choice constraints, in order:
   **same `cellY` (hard — no lift move)**, same aisle preferred, then slotting's normal placement
   preferences; never a target that itself creates a deeper blockage for the moved HU's velocity
   class than necessary.

2. **Flow executes the chain.** `dispatchRetrieve` first asks slotting for the plan. If non-empty it
   dispatches a **`RELOCATE`** device task (family ASRS; `BIN_RELOCATE` for AutoStore) for the FIRST
   step (`payload: huId, huCode, fromLocationId, toLocationId`) instead of the retrieve, stamping a
   new `relocate_task_id` on the induction entry. On the relocate callback flow **books the blocker's
   new location** (the PR-#218 `InventoryClient`), writes a **`RELOCATED`** HU-trace row for the
   *blocker* (point `slot:<to>`, decision "relocated out of channel for <target hu>"), and
   **re-plans**: more blockers → next `RELOCATE`; channel clear → the original `RETRIEVE` goes out.
   Re-planning each step (rather than persisting the whole plan) is stateless and self-healing when
   occupancy changed meanwhile. A failed relocate leaves the entry `REQUESTED` (retryable by the
   meter pass), mirroring failed-retrieve semantics.

3. **The emulator executes a `RELOCATE` like hardware:** one shuttle-move latency (its own entry in
   the latency table), then the result callback. No internal decision-making — the WCS told it
   exactly from→to.

4. **Demo seed gains a double-deep channel** (a few `cellZ 2` locations behind existing `cellZ 1`
   ones, with HUs in both) so a dig-out is reproducible on the demo: request the deep tote and watch
   the blocker hop channels in the trace/visu before the target comes out.

## Consequences

- A blocked retrieve becomes *visibly* slower (relocate + retrieve), with the reason in the HU trace
  — the audit answers "why did this take 12s?".
- Inventory stays true at every step: the blocker's registry row moves to its new channel.
- The visu needs nothing new: relocations surface as ASRS activity + trace rows (and the blocker HU,
  if rendered, jumps to its new slot anchor).
- Cap metering is unaffected: the entry occupies its retrieval slot from the first relocate (the
  `retrieve` commitment now means "the chain toward retrieving this HU has started").

5. **The visu shows the rack's contents.** The hardware visualisation renders every STORED handling
   unit inside the ASRS rack at its cell position — mapping the registry's location (`aisle`, `side`,
   `cellX`, `cellY`, `cellZ`) into the placed rack's local frame (X along the rack length, Y by
   level, side = which rack flanks the aisle, Z = depth into the rack). Pure representation of the
   HU registry; with relocations (above) a dig-out becomes directly visible — the blocker hops to
   its new channel before the target tote leaves the rack.

## Implementation phases

- **0009-1 (slotting):** relocation-plan endpoint (occupancy + same-level target choice) + tests.
- **0009-2 (flow + emulator):** `relocate_task_id` migration, plan-driven dispatch chain, RELOCATED
  trace + location booking, callback routing; emulator `RELOCATE`/`BIN_RELOCATE` latency + tests.
- **0009-3 (ui):** stored-tote rack view — registry HUs rendered at their cell positions in the
  placed ASRS rack (needs only #218 data; independent of 0009-1/2).
- **0009-4 (seed/demo):** double-deep demo locations + HUs; end-to-end validation on the demo.
