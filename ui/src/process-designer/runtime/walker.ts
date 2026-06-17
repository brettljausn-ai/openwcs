// The flow walker: pure logic for advancing a process instance through its step map (spec §3 + §6).
// Used by the runtime and by the designer's Simulate mode (no backend).
//
// Given a step and the current data object, nextStepId() evaluates the step's guarded transitions
// (first matching `when` wins, via the safe condition interpreter) then falls back to `next`. No
// match + no next = the instance ends (returns null).

import { safeEvaluate } from '../condition'
import { type ProcessDefinition, type Step, type VerifyConfig, type VerifyResult } from '../model'

/** Write a captured screen value to the data object under config.writeTo (returns a NEW object). */
export function writeValue(
  data: Record<string, unknown>,
  writeTo: string | undefined,
  value: unknown,
): Record<string, unknown> {
  if (!writeTo) return data
  return { ...data, [writeTo]: value }
}

/** Read one resolved field value from a /verify result: the per-kind `fields` map (new servers) is
 *  authoritative; fall back to the legacy top-level field of the same name (older servers). */
function resolvedFieldValue(result: VerifyResult, key: string): unknown {
  if (result.fields && key in result.fields) return result.fields[key]
  // Legacy top-level fields (id/code/name/uomCode/schemaCategory) for older servers.
  const legacy = result as unknown as Record<string, unknown>
  return key in legacy ? legacy[key] : null
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
  const out = { ...data }
  for (const [key, target] of Object.entries(write)) {
    if (!target) continue
    if (key === 'uomCode' && chosenUomCode != null) out[target] = chosenUomCode
    else out[target] = resolvedFieldValue(result, key) ?? null
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

/**
 * Resolve the step id we should actually LAND on, honouring `skipWhen` (spec/Phase 2 conditional
 * skips): starting from `id`, while the step's `skipWhen` evaluates true against `data`, advance to
 * its next/first-matching transition WITHOUT rendering it. Returns the first id whose step does not
 * skip (or null when the chain ends / a skipped step has no onward path).
 *
 * Guards against infinite loops: a step that skips back onto an already-seen id terminates the chain
 * (returns null) rather than spinning. Used by both the runtime and the designer simulate.
 */
export function resolveLandingStep(
  def: ProcessDefinition,
  id: string | null,
  data: Record<string, unknown>,
): string | null {
  const seen = new Set<string>()
  let cur = id
  while (cur) {
    const step = def.steps[cur]
    if (!step) return null // dangling id ends the instance
    if (!step.skipWhen || !safeEvaluate(step.skipWhen, data)) return cur
    if (seen.has(cur)) return null // skip-loop guard: would cycle forever
    seen.add(cur)
    cur = nextStepId(step, data) // a skipped step with no onward path -> null (instance ends)
  }
  return null
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
