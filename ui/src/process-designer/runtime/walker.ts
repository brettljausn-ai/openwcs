// The flow walker: pure logic for advancing a process instance through its step map (spec §3 + §6).
// Used by the runtime and by the designer's Simulate mode (no backend).
//
// Given a step and the current data object, nextStepId() evaluates the step's guarded transitions
// (first matching `when` wins, via the safe condition interpreter) then falls back to `next`. No
// match + no next = the instance ends (returns null).

import { evaluateExpression, safeEvaluate } from '../condition'
import { isComputeStep, isDecisionStep, type ComputeStep, type ProcessDefinition, type Step, type VerifyConfig, type VerifyResult } from '../model'

/** Write a captured screen value to the data object under config.writeTo (returns a NEW object). */
export function writeValue(
  data: Record<string, unknown>,
  writeTo: string | undefined,
  value: unknown,
): Record<string, unknown> {
  if (!writeTo) return data
  return { ...data, [writeTo]: value }
}

/** Read one resolved value from a /verify result by a write PATH. The path is split on '.': the root
 *  segment selects a top-level resolved field, each further segment drills into a nested object. A
 *  whole-object key (e.g. `uom`) returns the object; a dotted path (e.g. `uom.factor`) returns the
 *  nested property. A non-dotted key reads `result.fields[key]` first (new servers), falling back to
 *  the legacy top-level field of the same name (older servers, e.g. id/code/name/uomCode). Returns
 *  null when any segment is missing or a non-object is drilled into. */
function resolvedFieldValue(result: VerifyResult, path: string): unknown {
  const segs = path.split('.')
  const [root, ...rest] = segs
  // Root: prefer the per-kind fields map, else the legacy top-level field.
  let cur: unknown
  if (result.fields && root in result.fields) cur = result.fields[root]
  else {
    const legacy = result as unknown as Record<string, unknown>
    cur = root in legacy ? legacy[root] : null
  }
  // Drill the remaining segments into nested objects.
  for (const seg of rest) {
    if (cur == null || typeof cur !== 'object') return null
    cur = (cur as Record<string, unknown>)[seg]
  }
  return cur ?? null
}

/** Merge a successful /verify result's resolved fields into the data object per the verify block's
 *  `write` mappings (resolvedFieldKey -> variable). Returns a NEW object. Unmapped fields are
 *  ignored; this is how a later task that needs the resolved UUID/details gets it (write id ->
 *  someVar). Values are read from result.fields[key] first, falling back to the legacy top-level
 *  field for older servers. */
export function applyVerifyWrites(
  data: Record<string, unknown>,
  verify: VerifyConfig,
  result: VerifyResult,
  /** skuScan: the UOM the operator picked (or the auto-resolved one) — overrides the uomCode field. */
  chosenUomCode?: string | null,
): Record<string, unknown> {
  const write = verify.write
  if (!write) return data
  // skuScan: reflect the operator's chosen UOM into result.fields.uom (a shallow copy so callers'
  // objects are not mutated) so a `uom` whole-object or `uom.code` drill picks up the choice too.
  let effective = result
  if (chosenUomCode != null) {
    const baseUom = result.fields && typeof result.fields.uom === 'object' && result.fields.uom != null
      ? (result.fields.uom as Record<string, unknown>)
      : {}
    effective = {
      ...result,
      fields: { ...(result.fields ?? {}), uom: { ...baseUom, code: chosenUomCode } },
    }
  }
  const out = { ...data }
  for (const [key, target] of Object.entries(write)) {
    if (!target) continue
    // The chosen UOM still wins for the scalar `uomCode` mapping (legacy contract preserved).
    if (key === 'uomCode' && chosenUomCode != null) out[target] = chosenUomCode
    else out[target] = resolvedFieldValue(effective, key) ?? null
  }
  return out
}

/** The next step id after `step` given `data`, or null when the instance ends. */
export function nextStepId(step: Step, data: Record<string, unknown>): string | null {
  if (step.transitions) {
    for (const tr of step.transitions) {
      if (tr.when && safeEvaluate(tr.when, data)) return tr.to
    }
  }
  return step.next ?? null
}

/** Resolve a step id against the definition (undefined when the id is dangling). */
export function stepOf(def: ProcessDefinition, id: string | null): Step | undefined {
  return id ? def.steps[id] : undefined
}

/** Evaluate a compute step's `set` rows in order against `data`, returning a NEW data object with the
 *  computed values written in. Each row's expression is evaluated with the safe expression grammar
 *  (evaluateExpression, no eval); a parse/runtime error writes `undefined` for that row rather than
 *  throwing, so a malformed expression never crashes the runtime. Later rows see earlier writes. */
export function applyCompute(
  step: ComputeStep,
  data: Record<string, unknown>,
): Record<string, unknown> {
  let out = { ...data }
  for (const row of step.set ?? []) {
    if (!row.var) continue
    let value: unknown
    try {
      value = evaluateExpression(row.expr, out)
    } catch {
      value = undefined
    }
    out = { ...out, [row.var]: value }
  }
  return out
}

/** The resolved landing of a walk: the step id to render (null = the instance ends) and the data
 *  object after any compute steps along the way have been evaluated. */
export interface Landing {
  stepId: string | null
  data: Record<string, unknown>
}

/**
 * Resolve the step we should actually LAND on, honouring `skipWhen` (Phase 2 conditional skips) AND
 * passing THROUGH any pass-through steps along the way (they render no screen — like a skip-resolution):
 *   - a `compute` step writes its `set` rows into the data object, then routes onward;
 *   - a `decision` step is a pure router (if/elseif/else): it writes NOTHING, it just evaluates its
 *     `transitions` (first matching `when` over the data object wins) then `next` (the else target).
 * starting from `id`, while a step is compute/decision OR its `skipWhen` is true, process it without
 * rendering and advance to its next/first-matching transition. Compute steps write their `set` rows
 * into the data object first (so transitions/next see the computed values). Returns the first
 * non-skipped, renderable step id together with the (possibly updated) data object.
 *
 * Guards against infinite loops: revisiting an already-seen id terminates the chain (stepId: null)
 * rather than spinning. Used by both the runtime and the designer simulate; fully offline.
 */
export function resolveLanding(
  def: ProcessDefinition,
  id: string | null,
  data: Record<string, unknown>,
): Landing {
  const seen = new Set<string>()
  let cur = id
  let d = data
  while (cur) {
    const step = def.steps[cur]
    if (!step) return { stepId: null, data: d } // dangling id ends the instance
    const isCompute = isComputeStep(step)
    const isDecision = isDecisionStep(step)
    const passThrough = isCompute || isDecision // renders no screen; routes straight through
    const skips = !!step.skipWhen && safeEvaluate(step.skipWhen, d)
    if (!passThrough && !skips) return { stepId: cur, data: d }
    if (seen.has(cur)) return { stepId: null, data: d } // loop guard: would cycle forever
    seen.add(cur)
    // A compute step that is NOT skipped writes its computed values before we resolve its onward path.
    // A decision step assigns nothing; it only routes (nextStepId evaluates transitions then next).
    if (isCompute && !skips) d = applyCompute(step, d)
    cur = nextStepId(step, d) // no onward path -> null (instance ends)
  }
  return { stepId: null, data: d }
}

/**
 * Backwards-compatible thin wrapper returning ONLY the landing step id. NOTE: it discards the
 * compute-updated data — callers that may walk through a compute step must use resolveLanding and
 * adopt its returned `data`. Retained for skip-only walks where no compute is involved.
 */
export function resolveLandingStep(
  def: ProcessDefinition,
  id: string | null,
  data: Record<string, unknown>,
): string | null {
  return resolveLanding(def, id, data).stepId
}

/** Step ids reachable from start (BFS over next + transition targets). For validation + simulate. */
export function reachableSteps(def: ProcessDefinition): Set<string> {
  const seen = new Set<string>()
  const queue: string[] = def.start ? [def.start] : []
  while (queue.length) {
    const id = queue.shift()!
    if (seen.has(id)) continue
    seen.add(id)
    const step = def.steps[id]
    if (!step) continue
    if (step.next) queue.push(step.next)
    for (const tr of step.transitions ?? []) queue.push(tr.to)
  }
  return seen
}
