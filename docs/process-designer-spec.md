# Mobile Process Designer and Engine — Specification

Status: all three phases implemented (process-designer service :8097 + the WYSIWYG designer and
client-driven handheld runtime in the UI). Phase 2 added version management (duplicate/clone,
JSON import/export), step-level `skipWhen` conditions (validated at publish by a recursive-descent
ConditionParser, no eval), four more curated task types (`host.confirm`, `inventory.adjust`,
`counting.capture`, `order.lookup`) plus a live task catalog, and read-only instance
history/monitoring. Phase 3 (the final phase) added the two §7.2/§7.3 tiers: a sandboxed scripting
escape hatch (a `script` task type running in a locked-down GraalJS sandbox, gated by a config flag
plus the new `PROCESS_SCRIPT_AUTHOR` permission) and design-time AI task-assist (describe a task,
get a curated-task mapping or a draft sandboxed snippet for a human to review; never auto-deployed). A later increment added **scan
verification** (§4.1): a per-screen `verify` block that resolves a scanned/typed value against new
read-only master-data resolve endpoints, branches on not-found, and writes the resolved ids
(including the resolved UUID) into data-object variables, on the curated-API path (no raw SQL).
The feature is now complete. Author intent captured 2026-06-16. This is a build spec, the sibling of
[`dashboardScope.md`](./dashboardScope.md). It will be promoted to an ADR once the model decisions
below are accepted; the sections below remain the spec.

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

### 4.1 Scan verification (implemented)

The per-screen `validation` (regex / maxLength / mustEqual) only checks the **shape** of an entered
value. **Verification** confirms the code actually **EXISTS** in master data and pulls its linked
ids (so a later task that needs a UUID gets it). `textInput` / `numberInput` screens may carry an
optional `verify` block in their `config`:

```jsonc
"config": {
  "header": "Scan location",
  "writeTo": "locationCode",
  "verify": {
    "kind": "location",                 // "barcode" | "sku" | "location"
    "write": {                          // map resolved fields -> data-object variables
      "id":   "locationId",             // resolved UUID
      "code": "locationCode"
    },
    "onNotFound": { "mode": "reprompt" } // or { "mode": "goto", "step": "<stepId>" }
  }
}
```

- **`kind`** picks the resolve endpoint: `barcode` (resolve a scanned barcode → SKU + UOMs +
  attribute-schema graph), `sku` (resolve a SKU code), `location` (resolve a location code). The
  designer's kind picker is **server-driven**: `GET /api/process-designer/capabilities` returns
  `verifyKinds:["barcode","sku","location"]`.
- **`write`** maps the normalised resolved fields (`id`, `code`, `name`, `uomCode`,
  `schemaCategory`) to declared data-object variables. `id` is the resolved UUID. Validated at
  publish: each write key must be a known resolved field and each target must be a declared variable.
- **`onNotFound`** = `reprompt` (clear and ask again) or `goto` a named step. A `goto` target must
  exist and **counts toward step reachability** (so the validator does not flag it as unreachable).

**Resolve endpoints (master-data, read-only, RBAC `MASTER_DATA_VIEW`).** Each returns HTTP **200**
with `found:false` on a miss (never 404), so a flow can branch instead of erroring:
- `GET /api/master-data/resolve/sku-by-barcode?warehouseId=&code=` →
  `{found, ambiguous, matchedBarcode{value,uomId,uomCode,type}, sku{skuId,code,description,status},`
  ` uoms[{uomId,code,baseUnit,parentUomId,qtyInParent}], barcodes[{value,uomCode,type}],`
  ` attributeSchema{attributeSchemaId,category,version,jsonSchema}}` (the full barcode → UOMs → SKU
  → attribute-schema graph; `ambiguous:true` when a value spans more than one SKU).
- `GET /api/master-data/resolve/sku?warehouseId=&code=` → same body, resolved by SKU code
  (`matchedBarcode` null).
- `GET /api/master-data/resolve/location?warehouseId=&code=` →
  `{found, location{locationId,code,locationType,purpose,status}}`.

**Proxy.** `POST /api/process-designer/verify {warehouseId, kind, code}` (RBAC
`PROCESS_DESIGN_VIEW`) proxies to the matching master-data endpoint with the **operator's forwarded
identity** (`X-Auth-*`, so master-data RBAC + warehouse scope apply) and returns a normalised
`{found, ambiguous, id, code, name, uomCode, schemaCategory, detail}`. A clean `found:false` is a
200 passthrough; a downstream transport/4xx/5xx failure becomes a 502. Configured by
`OPENWCS_MASTER_DATA_BASE_URL` (`openwcs.process-designer.master-data-base-url`).

**Runtime.** On submit the client resolves via the proxy: **offline holds** ("verification needs a
connection"); `found:false` re-prompts or routes per `onNotFound`; `found:true` merges the `write`
mappings into the data object and continues; `ambiguous:true` shows a subtle note. **Simulate mode**
resolves locally with no backend (a "simulate not found" toggle).

**Why curated-no-SQL.** Verification stays on the curated-API path: resolve endpoints reuse the same
master-data repositories the SKU/location card reads use and the proxy forwards the operator's
identity, so RBAC + audit + service boundaries stay intact (no raw SQL, no direct DB access).

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

### 7.2 Sandboxed script (controlled escape hatch — implemented in Phase 3)
For logic not covered by the library: a built-in **`script`** task type. The step config is
`{ "script": "<js>", "outputs": [{ "name": "..." }] }`. The snippet runs server-side in a
**locked-down GraalJS sandbox** (org.graalvm.polyglot community 24.1.1): `allowAllAccess(false)`,
`HostAccess.NONE`, no host class lookup/loading, no native, no threads, no process, `IOAccess.NONE`,
no environment, `PolyglotAccess.NONE`. It is **interpreted guest JS, never compiled to host JVM
bytecode**. Resource limits: a `ResourceLimits` statement limit (default 100000), a watchdog
wall-clock timeout (default 2000 ms), and an output size cap (default 65536 bytes). The script sees
only a **deep-frozen, read-only `data` global** (the process data object) and returns an object
whose fields become the declared outputs. Tunable via
`openwcs.process-designer.scripting.{statement-limit,timeout-ms,max-output-bytes}`.

Governance is **defense in depth**: a new permission `PROCESS_SCRIPT_AUTHOR` (granted to ADMIN only)
and a config flag `openwcs.process-designer.scripting.enabled` (default **false**). A definition
containing a `script` step can be saved or published **only when BOTH the flag is on AND the caller
holds `PROCESS_SCRIPT_AUTHOR`** (else 422 when scripting is disabled / 403 when the permission is
missing). Each script is **parse-validated in the sandbox at publish** (malformed → 422). Never
free-form Java compiled into the running JVM.

### 7.3 AI task-assist (assistive, never auto-deploy — implemented in Phase 3, design-time only)
"Describe what the task should achieve" is a **design-time assistant**, not a runtime code generator.
`POST /api/process-designer/assist/task` `{description, variables:[{name,type}]}` returns
`{kind:"curated"|"script"|"none", taskType?, inputs?, outputs?, script?, rationale, confidence}`
(gated by `PROCESS_DESIGN_EDIT`). It uses the Anthropic Java SDK
(`com.anthropic:anthropic-java`, default model `claude-haiku-4-5`, key from the `ANTHROPIC_API_KEY`
env), grounded with the **live task catalog plus the available variables**, and either:
1. Maps the description to an **existing curated task type + variable mappings** (`kind:"curated"`,
   no new code runs); or
2. **Drafts a sandboxed JS snippet** (`kind:"script"`) for a **human to review** and insert into a
   `script` step. The suggestion is never saved, compiled, or executed by the server.

Absent key → 503 (the context still starts). The designer surfaces a drafted snippet clearly
labelled as an AI draft for review; nothing is deployed automatically.

Explicitly rejected and permanently out of scope: AI text -> compiled Java -> hot-deployed to a
live server. That is remote code execution by configuration. The AI never auto-deploys.

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
- No designer-authored Java executes in-process (§7). A `script` step runs only **interpreted guest
  JS in a locked-down GraalJS sandbox** (no host/Java access, no IO/threads/process/native/env,
  statement + wall-clock + output limits, a deep-frozen read-only `data` global), never compiled to
  host bytecode (§7.2).
- A `script` step can be saved/published only when the `scripting.enabled` flag is on **and** the
  caller holds `PROCESS_SCRIPT_AUTHOR` (ADMIN only); else 422 (disabled) / 403 (no permission). Each
  script is parse-validated in the sandbox at publish (malformed → 422).
- AI task-assist is **design-time only**: it returns a suggestion the designer reviews; it never
  saves, compiles, or executes code, and never auto-deploys (§7.3).
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
- **Scan verification** (§4.1): `POST /api/process-designer/verify {warehouseId, kind, code}`
  (proxies the read-only master-data resolve endpoints with forwarded identity);
  `GET /api/process-designer/capabilities` also returns `verifyKinds:["barcode","sku","location"]`.

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
2. **Phase 2 (implemented)**: richer designer (more validation: unreachable steps, skip-with-no-onward-path,
   malformed conditions, task steps missing required inputs per the live catalog, duplicate ids),
   copy/duplicate version (`POST /defs/{key}/{version}/duplicate` -> new DRAFT), JSON import/export
   (`POST /defs/import` -> DRAFT; export = `GET /defs/{key}/{version}`), step-level conditional skips
   (`skipWhen`, validated at publish by a recursive-descent ConditionParser, 422 on malformed or a
   skippable step with no onward path), four more curated task types (`host.confirm`,
   `inventory.adjust`, `counting.capture`, `order.lookup`) plus a live task catalog
   (`GET /api/process-designer/tasks`) driving the designer's task picker, and instance
   history/monitoring (`GET /api/process-designer/instances` list + existing detail; migration V2
   monitoring indexes; read-only desktop "Process instances" screen at `/process-instances`).
3. **Phase 3 (implemented — final phase)**: the **sandboxed scripting escape hatch** (§7.2 — a
   `script` task type running in a locked-down GraalJS sandbox with statement/wall-clock/output
   limits over a deep-frozen read-only `data` global, never compiled to host bytecode; gated by the
   `openwcs.process-designer.scripting.enabled` flag (default false) AND the new `PROCESS_SCRIPT_AUTHOR`
   permission (ADMIN only), parse-validated at publish), **design-time AI task-assist** (§7.3 —
   `POST /api/process-designer/assist/task` returns a curated-task mapping or a draft sandboxed
   snippet for a human to review; Anthropic Java SDK, default `claude-haiku-4-5`, `ANTHROPIC_API_KEY`;
   503 when no key; the AI never auto-deploys), a **capabilities** endpoint
   (`GET /api/process-designer/capabilities` → `{scriptingEnabled, aiAssistEnabled, canAuthorScript}`,
   `PROCESS_DESIGN_VIEW`) driving the designer's show/hide of the script editor + assist panel, and
   the frontend script-step code editor (with a declared-outputs editor + sandbox-limit help) plus
   the "describe what this task should do" assist panel (Apply a curated suggestion, or Insert an
   AI-drafted snippet as a `script` step clearly labelled "AI draft, for your review"). Compose env on
   process-designer: `OPENWCS_PROCESS_SCRIPTING_ENABLED` (default false), `ANTHROPIC_API_KEY` (opt-in),
   `OPENWCS_PROCESS_ASSIST_MODEL`. i18n de/fr/es/zh. All three phases are now implemented.

## 15. Open decisions
- **Engine home**: extend `process-engine` (Flowable service) vs a new `process-designer` service.
  Leaning new service: the model is custom (not BPMN), and it keeps the Flowable orchestration engine
  uncluttered. Decide before Phase 1 build.
- **Flowable reuse**: whether task steps optionally trigger Flowable backend processes (e.g. a
  goods-in screen flow that, at putaway, kicks the existing goods-in BPMN). Likely yes via the
  curated `host`/`flow` task types, not by embedding the screen flow in BPMN.
- **Definition portability**: export/import a definition JSON between environments (implemented in
  Phase 2 via `POST /defs/import` and `GET /defs/{key}/{version}`).

## 16. Verification (when built)
- Designer: build a Goods In flow (scan ASN -> scan SKU -> qty -> damaged? -> putaway/quarantine ->
  ack), preview each screen, simulate both branches, publish; confirm exactly one ACTIVE version.
- Handheld: run the active process offline through the screen steps, reconnect, confirm the task
  checkpoints post and the txlog/inventory effects land; kill+reload mid-instance and resume.
- Security: confirm a `when`/placeholder cannot execute code; confirm a task step is rejected when
  the operator lacks the underlying RBAC.
