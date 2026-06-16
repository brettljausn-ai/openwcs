// Definition validation for the designer (spec §10 "Validate"): unreachable steps, dangling
// transition/next targets, unbound writes (writeTo not in the data schema), unknown placeholders,
// and malformed `when` expressions. Publish is blocked until this returns no errors.

import { validateCondition } from '../condition'
import { isScreenStep, type ProcessDefinition } from '../model'
import { placeholderRefs } from '../placeholders'
import { reachableSteps } from '../runtime/walker'

export interface ValidationIssue {
  level: 'error' | 'warning'
  stepId?: string
  message: string
}

export function validateDefinition(def: ProcessDefinition): ValidationIssue[] {
  const issues: ValidationIssue[] = []
  const ids = Object.keys(def.steps)
  const schemaNames = new Set(def.dataSchema.map((v) => v.name))

  if (!def.start) issues.push({ level: 'error', message: 'No start step set.' })
  else if (!def.steps[def.start]) issues.push({ level: 'error', message: `Start step "${def.start}" does not exist.` })

  if (ids.length === 0) issues.push({ level: 'error', message: 'The process has no steps.' })

  const reachable = reachableSteps(def)
  for (const id of ids) {
    if (!reachable.has(id)) issues.push({ level: 'warning', stepId: id, message: `Step "${id}" is unreachable.` })
  }

  for (const [id, step] of Object.entries(def.steps)) {
    // dangling next
    if (step.next && !def.steps[step.next]) {
      issues.push({ level: 'error', stepId: id, message: `"${id}" → next "${step.next}" does not exist.` })
    }
    // transitions
    for (const tr of step.transitions ?? []) {
      if (!def.steps[tr.to]) issues.push({ level: 'error', stepId: id, message: `"${id}" → transition to "${tr.to}" does not exist.` })
      const condErr = validateCondition(tr.when)
      if (condErr) issues.push({ level: 'error', stepId: id, message: `"${id}" condition "${tr.when}": ${condErr}` })
    }

    if (isScreenStep(step)) {
      const cfg = step.config
      // unbound write: a value-capturing screen with a writeTo that isn't a declared variable
      const captures = step.screen !== 'acknowledge'
      if (captures && cfg.writeTo && !schemaNames.has(cfg.writeTo)) {
        issues.push({ level: 'error', stepId: id, message: `"${id}" writes to undeclared variable "${cfg.writeTo}".` })
      }
      if (captures && !cfg.writeTo && step.screen !== 'questionYesNo' && step.screen !== 'questionChoice') {
        issues.push({ level: 'warning', stepId: id, message: `"${id}" does not store its captured value (no Write to).` })
      }
      // unknown placeholders in header/detail
      for (const ref of [...placeholderRefs(cfg.header), ...placeholderRefs(cfg.detail)]) {
        if (!schemaNames.has(ref)) issues.push({ level: 'warning', stepId: id, message: `"${id}" references unknown variable {{${ref}}}.` })
      }
      // questionChoice needs options
      if (step.screen === 'questionChoice' && (cfg.options ?? []).length === 0) {
        issues.push({ level: 'warning', stepId: id, message: `"${id}" is a choice question with no options.` })
      }
    } else {
      // task step: input/output mappings should reference declared variables
      for (const v of Object.values(step.input ?? {})) {
        if (v && !schemaNames.has(v)) issues.push({ level: 'warning', stepId: id, message: `"${id}" task input maps undeclared variable "${v}".` })
      }
      for (const v of Object.values(step.output ?? {})) {
        if (v && !schemaNames.has(v)) issues.push({ level: 'warning', stepId: id, message: `"${id}" task output writes undeclared variable "${v}".` })
      }
    }
  }

  return issues
}

export function hasErrors(issues: ValidationIssue[]): boolean {
  return issues.some((i) => i.level === 'error')
}
