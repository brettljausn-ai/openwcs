// The flow walker: pure logic for advancing a process instance through its step map (spec §3 + §6).
// Used by the runtime and by the designer's Simulate mode (no backend).
//
// Given a step and the current data object, nextStepId() evaluates the step's guarded transitions
// (first matching `when` wins, via the safe condition interpreter) then falls back to `next`. No
// match + no next = the instance ends (returns null).

import { safeEvaluate } from '../condition'
import type { ProcessDefinition, Step } from '../model'

/** Write a captured screen value to the data object under config.writeTo (returns a NEW object). */
export function writeValue(
  data: Record<string, unknown>,
  writeTo: string | undefined,
  value: unknown,
): Record<string, unknown> {
  if (!writeTo) return data
  return { ...data, [writeTo]: value }
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
