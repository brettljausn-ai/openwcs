# Mobile Process Designer and Engine — Specification

Status: draft (Phase 1 scope). Author intent captured 2026-06-16. This is a build spec, the
sibling of [`dashboardScope.md`](./dashboardScope.md). It will be promoted to an ADR once the
model decisions below are accepted.

## 1. Context and goal

Warehouse operator processes (goods-in, packing, stock check, ad-hoc moves) differ per site and
per customer. Today each operator screen is hand-coded React. The goal is to make an operator
process a **configurable, versioned definition**: a non-developer builds a flow of handheld screens
and tasks in a visual designer, assigns one version as **active** for a process type (e.g. Goods
In), and the handheld PWA runs it. This complements, and does not replace, the existing Flowable
BPMN engine, which stays for heavy backend orchestration.

The runtime is the handheld **PWA operator shell** already built: the operator menu lists active
processes, and tapping one runs its definition. See [`AS-BUILT.md`](./AS-BUILT.md) (ui handheld
operator shell) and the offline confirm-queue work.

### Goals
- A visual, **WYSIWYG** designer where each step is shown as the actual handheld screen the operator
  will see (look-and-feel preview), so a non-developer can reason about the flow.
- Screen step types: Text Input, Number Input, Date Input, Acknowledge, Question (Yes/No), Question
  (configurable answers). Plus per-screen validation and barcode-scan binding.
- A **data object** (typed process variables) written across screens, with `{{placeholder}}`
  interpolation in screen header/detail text.
- **Task steps** that do real work by calling existing, audited services (a curated task library),
  with a controlled scripting escape hatch.
- **Draft and active versioning** per process key, with rollback and in-flight pinning.
- **Offline-first** execution on the handheld.

### Non-goals (Phase 1)
- Replacing the Flowable BPMN backend-orchestration designer.
- Arbitrary free-form Java authored in the designer and hot-deployed (explicitly rejected, see §7).
- Parallel branches / multi-instance / timers (later phase; Phase 1 is linear + simple branching).
- AI that auto-deploys generated server code (assistive only, developer-reviewed, see §7.3).

## 2. Concepts

| Concept | Meaning |
|---|---|
| **Process key** | Stable id of a process type, e.g. `goods-in`, `packing`, `stock-check`. The operator menu shows the active version per key. |
| **Process definition (version)** | One immutable version of a flow: ordered steps + transitions + the data-object schema. Has a status: `DRAFT`, `ACTIVE`, `ARCHIVED`. Exactly one `ACTIVE` per process key. |
| **Step** | A node in the flow: a **screen step** (renders a handheld screen, captures input) or a **task step** (runs server-side work). |
| **Transition** | A directed edge between steps; may be unconditional or guarded by a condition over the data object (for branching). |
| **Data object** | The typed key/value context for one running instance; screens write to it, placeholders and conditions read from it, task steps read/write it. |
| **Instance** | One run of a process by an operator (one goods-in receipt). Pinned to the definition version it started on. |

## 3. The process model (JSON)

A definition is a JSON document (stored as JSONB). Phase 1 shape:

```jsonc
{
  "processKey": "goods-in",
  "version": 3,
  "status": "DRAFT",                       // DRAFT | ACTIVE | ARCHIVED
  "title": "Goods In",
  "icon": "inbound",
  "dataSchema": [                          // the typed data object for this process
    { "name": "asn",        "type": "string" },
    { "name": "sku",        "type": "sku" },        // domain types resolve to codes/labels
    { "name": "location",   "type": "location" },
    { "name": "qty",        "type": "number" },
    { "name": "damaged",    "type": "boolean" }
  ],
  "start": "scanAsn",
  "steps": {
    "scanAsn":   { "type": "screen", "screen": "textInput",  "config": { ... }, "next": "scanSku" },
    "scanSku":   { "type": "screen", "screen": "textInput",  "config": { ... }, "next": "enterQty" },
    "enterQty":  { "type": "screen", "screen": "numberInput","config": { ... }, "next": "askDamaged" },
    "askDamaged":{ "type": "screen", "screen": "questionYesNo","config": { ... },
                   "transitions": [ { "when": "damaged == true", "to": "quarantine" } ],
                   "next": "putaway" },
    "putaway":   { "type": "task",   "task": "slotting.putaway", "input": { ... }, "next": "done" },
    "quarantine":{ "type": "task",   "task": "inventory.move",   "input": { ... }, "next": "done" },
    "done":      { "type": "screen", "screen": "acknowledge", "config": { ... } }
  }
}
```

- **Branching**: a step lists optional `transitions` (first matching `when` wins) and a default
  `next`. `when` is a restricted boolean expression over data-object variables (no arbitrary eval,
  see §6). A step with neither `next` nor a matching transition ends the instance.
- The model is deliberately a **step map + transitions**, not full BPMN: handheld flows are mostly
  linear with a few branches, and this keeps both the JSON and the designer simple.

## 4. Screen step types

Common fields on every screen `config`: `header` (string, supports placeholders), `detail` (string,
placeholders), optional `scanBinding` (true = value captured from a wedge scan, not typed),
`validation` (per type), and `writeTo` (the data-object variable the captured value is written to).

| Screen type | Captures | Type-specific config |
|---|---|---|
| `textInput` | string | `scanBinding`, validation: `required`, `regex`, `maxLength`, `mustEqual` (a `{{var}}` to match, e.g. confirm scanned location equals the expected) |
| `numberInput` | number | validation: `required`, `min`, `max`, `integerOnly`, `mustEqual` |
| `dateInput` | ISO date | validation: `required`, `min`/`max` (date or relative), default = today |
| `acknowledge` | nothing (continue) | `confirmLabel`; optional required checkbox |
| `questionYesNo` | boolean | `writeTo`; the Yes/No drives `transitions` |
| `questionChoice` | string (chosen key) | `options: [{ key, label }]` (configurable answers); choice drives `transitions` |

All screens render glove-friendly, high-contrast, one-handed, consistent with the picking screen,
and support an optional scanner capture (keyboard-wedge) where `scanBinding` is set.

## 5. Data object and placeholders

- The data object is the typed variable set declared in `dataSchema`. `type` may be a primitive
  (`string`, `number`, `boolean`, `date`) or a **domain type** (`sku`, `location`, `hu`) which the
  runtime resolves to codes/labels via `useCatalog` (so placeholders show `SKU-1 — Widget`, never a
  UUID).
- **Placeholders**: header/detail text uses `{{var}}` (and `{{var.code}}`, `{{var.name}}` for
  domain types). Resolution is over the **whitelisted** data-object fields only, HTML-escaped, no
  expression evaluation. An unknown placeholder renders as a visible empty marker, not an error.
- Captured input is written to `config.writeTo`; task steps read inputs from and write outputs back
  to the data object by variable name.

## 6. Conditions (branching)

`when` expressions are a **restricted, safe grammar**, not a scripting language: comparisons
(`==`, `!=`, `<`, `<=`, `>`, `>=`), boolean `and`/`or`/`not`, against data-object variables and
literals. Parsed and evaluated by a small interpreter (no `eval`, no host access). Anything more
complex belongs in a task step.

## 7. Task steps (the work)

A task step runs server-side work and reads/writes the data object. Three tiers, in order of
preference:

### 7.1 Curated task library (default, safe)
A registry of **pre-built, parameterized task types** that call existing audited endpoints, with the
caller's identity + warehouse scope forwarded (RBAC applies). The designer picks a task type and
maps data-object variables to its inputs/outputs. openWCS already exposes most of what is needed:

| Task type | Calls |
|---|---|
| `slotting.putaway` | `POST /api/slotting/decant/putaway` (scorer + dispatch a move) |
| `inventory.move` | `POST /api/flow/moves` |
| `picking.confirm` | `POST /api/orders/pick-tasks/{lineId}/confirm` |
| `counting.capture` | counting capture endpoint |
| `inventory.lookup` | `GET /api/inventory/...` (read into a variable) |
| `host.confirm` | Host API confirmation |
| `txlog.post` | a stock transaction |

New task types are added in code, reviewed, and shipped via the normal PR/CI pipeline. This is the
90% path and the only one needed for Phase 1.

### 7.2 Sandboxed script (controlled escape hatch, later phase)
For logic not covered by the library: a **sandboxed Groovy/GraalVM** snippet with a whitelisted API
surface, CPU/memory/time limits, and no filesystem/network beyond the exposed client. Never
free-form Java compiled into the running JVM.

### 7.3 AI task-assist (assistive, never auto-deploy)
"Describe what the task should achieve" is supported as a **design-time assistant**, not a runtime
code generator:
1. Map the description to **existing curated task types + variable mappings** (deterministic, no new
   code runs). This is the target for Phase 3.
2. If the library is insufficient, the assistant **drafts** a new task type or a sandboxed snippet
   that a **developer reviews, tests, and lands via PR/CI**. Human-in-the-loop, gated by the same
   CI we use everywhere.

Explicitly rejected: AI text -> compiled Java -> hot-deployed to a live server. That is remote code
execution by configuration and is out of scope permanently.

## 8. Versioning and activation
- A process key has many versions; each is immutable once published.
- Designer flow: edit a `DRAFT` -> validate -> **publish**, which sets it `ACTIVE` and archives the
  previously active version. Exactly one `ACTIVE` per key.
- **Rollback** = publish (re-activate) a prior version, creating a new version pointer.
- **In-flight pinning**: a running instance keeps the version it started on; publishing a new active
  version never mutates running instances.

## 9. Runtime and offline execution

Handhelds drop Wi-Fi, so the engine is **client-driven with server checkpoints**:
- On starting a process, the handheld fetches the **active definition** (cached by the PWA service
  worker) and creates an instance.
- The client drives screen steps locally: render, validate, capture into the data object. No server
  round-trip per screen.
- **Task steps are server checkpoints**: the client posts the current data object + the task step id;
  the server runs the curated task (forwarding identity), writes outputs back, and returns the
  updated data object + the next step. If offline, the task call is queued (the existing offline
  queue) and the instance shows "pending sync"; screen-only stretches keep working offline.
- **Idempotency**: each instance + step has a stable id; re-posting a task checkpoint is safe
  (server dedupes on instance+step). Instance state is persisted server-side at each checkpoint so a
  device swap / reload resumes.
- Reconciliation: a completed instance writes an append-only record (and the relevant txlog events
  via the task steps it ran).

## 10. The Designer UI (look and feel — WYSIWYG)

The designer must let a non-developer "see" the process as the operator will. Layout: a three-pane
screen.

```
┌───────────────┬───────────────────────────────┬──────────────────────┐
│ Flow (steps)  │   LIVE HANDHELD PREVIEW         │  Properties          │
│ ▢ Scan ASN    │   ┌───────────────────────┐    │  Step: Scan SKU      │
│ ▢ Scan SKU ◀  │   │  [handheld frame]      │    │  Header: Scan {{asn}}│
│ ▢ Enter qty   │   │   Scan SKU             │    │  Detail: ...         │
│ ◇ Damaged?    │   │   ASN: A123            │    │  Write to: sku       │
│   ├▶ Quaran.  │   │   [ scan field ]       │    │  Scan binding: on    │
│   └▶ Putaway  │   │   [ Confirm ]          │    │  Validation: required│
│ ✓ Done        │   └───────────────────────┘    │  + add validation    │
└───────────────┴───────────────────────────────┴──────────────────────┘
```

Requirements:
- **Live handheld preview (centre)**: the selected step rendered in a phone-sized frame using the
  **same components the real handheld runtime uses**, with placeholders resolved against sample data.
  Editing properties updates the preview instantly. This is the core "get your head around it" win:
  the designer is literally previewing the operator screen.
- **Flow list / mini-canvas (left)**: ordered steps with screen-type icons; branches shown as
  indented sub-paths under a Question step. Drag to reorder; add a step from a palette (the six
  screen types + "Task"). Click a step to edit + preview it. Keep it a structured list with
  visible branch arrows rather than a free-form node graph (simpler for non-developers; matches the
  mostly-linear reality).
- **Properties panel (right)**: per-step config (header/detail with a placeholder picker that lists
  the data-object variables, `writeTo`, validation builder, scan-binding toggle; for tasks, the task
  type picker + variable mapping; for questions, the answer/transition editor).
- **Data object panel**: define/rename the typed variables; the placeholder picker and `writeTo`
  dropdowns read from it.
- **Simulate / test mode**: step through the flow in the preview frame with fake input, exercising
  branches, before publishing. No backend writes (task steps are stubbed/dry-run).
- **Validate + publish**: validation lists unreachable steps, unbound writes, dangling transitions,
  unknown placeholders; publish is blocked until clean.
- The designer is a **desktop** screen (Engineering section), not a handheld screen. It is gated by a
  new `process-design` screen permission (admin/engineer).

## 11. Security model
- No designer-authored Java executes in-process (§7).
- Task steps run with the operator's forwarded identity + warehouse scope; they can only do what
  that user is allowed to do via the existing service RBAC.
- `when` conditions and `{{placeholders}}` are parsed/whitelisted, never `eval`'d.
- Publishing a definition is an audited, permissioned action.

## 12. API surface (backend, new `process` capability)
Lives in a new `process-designer` service or the existing `process-engine` (decision in §15).

- `GET /api/process/defs?status=` , `GET /api/process/defs/{key}/active`
- `POST /api/process/defs` (create draft), `PUT /api/process/defs/{key}/{version}` (edit draft),
  `POST /api/process/defs/{key}/{version}/publish`, `POST .../archive`
- `POST /api/process/instances {processKey}` (start; returns instance + active def),
  `POST /api/process/instances/{id}/checkpoint {stepId, data}` (run a task step, returns updated
  data + next step), `GET /api/process/instances/{id}` (resume)
- Curated task registry is server-side code (not an API to author tasks).

## 13. Persistence
- `process_definition` (process_key, version, status, json, published_at, published_by) — one ACTIVE
  per key enforced by a partial unique index.
- `process_instance` (id, process_key, def_version, status, data jsonb, current_step, started_by,
  warehouse_id, timestamps) — checkpointed at each task step for resume.

## 14. Phasing
1. **Phase 1**: the JSON model; the six screen types + validation + scan-binding; the data object +
   placeholders; linear flow + simple Yes/No + choice branching; a small curated task library; the
   client-driven offline runtime in the handheld PWA; draft/active versioning; the WYSIWYG designer
   (live preview + flow list + properties + simulate + publish). Wire one real process end-to-end
   (Goods In or Stock Check).
2. **Phase 2**: richer designer (more validation, copy/duplicate version, conditional skips), more
   task types, instance history/monitoring.
3. **Phase 3**: AI task-assist (description -> curated task mapping; developer-reviewed snippet
   generation), sandboxed scripting escape hatch.

## 15. Open decisions
- **Engine home**: extend `process-engine` (Flowable service) vs a new `process-designer` service.
  Leaning new service: the model is custom (not BPMN), and it keeps the Flowable orchestration engine
  uncluttered. Decide before Phase 1 build.
- **Flowable reuse**: whether task steps optionally trigger Flowable backend processes (e.g. a
  goods-in screen flow that, at putaway, kicks the existing goods-in BPMN). Likely yes via the
  curated `host`/`flow` task types, not by embedding the screen flow in BPMN.
- **Definition portability**: export/import a definition JSON between environments (useful; Phase 2).

## 16. Verification (when built)
- Designer: build a Goods In flow (scan ASN -> scan SKU -> qty -> damaged? -> putaway/quarantine ->
  ack), preview each screen, simulate both branches, publish; confirm exactly one ACTIVE version.
- Handheld: run the active process offline through the screen steps, reconnect, confirm the task
  checkpoints post and the txlog/inventory effects land; kill+reload mid-instance and resume.
- Security: confirm a `when`/placeholder cannot execute code; confirm a task step is rejected when
  the operator lacks the underlying RBAC.
