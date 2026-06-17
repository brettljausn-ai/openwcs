// Definition validation for the designer (spec §10 "Validate"): unreachable steps, dangling
// transition/next targets, unbound writes (writeTo not in the data schema), unknown placeholders,
// and malformed `when` expressions. Publish is blocked until this returns no errors.

import { validateCondition } from '../condition'
import { isScreenStep, isScriptStep, taskTypeById, type ProcessDefinition, type TaskTypeDef } from '../model'
import { placeholderRefs } from '../placeholders'
import { reachableSteps } from '../runtime/walker'

/** A valid JS identifier (the only allowed shape for a declared script-step output name). */
const IDENT_RE = /^[A-Za-z_$][A-Za-z0-9_$]*$/

export interface ValidationIssue {
  level: 'error' | 'warning'
  stepId?: string
  message: string
}

/**
 * Validate a definition before publish. `catalog` is the live server task catalog (falls back to the
 * static library) so task steps can be checked for missing REQUIRED inputs.
 */
export function validateDefinition(def: ProcessDefinition, catalog?: TaskTypeDef[]): ValidationIssue[] {
  const issues: ValidationIssue[] = []
  const ids = Object.keys(def.steps)
  const schemaNames = new Set(def.dataSchema.map((v) => v.name))

  // Duplicate step ids: the step map keys are unique by construction, but a duplicate variable name
  // in the data object is the realistic "duplicate id" mistake — flag it (a later var shadows the
  // earlier one in lookups).
  const seenVars = new Set<string>()
  for (const v of def.dataSchema) {
    if (seenVars.has(v.name)) issues.push({ level: 'error', message: `Duplicate data-object variable "${v.name}".` })
    seenVars.add(v.name)
  }

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

    // skipWhen: must be a valid condition; a step that can be skipped must have somewhere to go,
    // otherwise a true skipWhen silently ends the instance (likely a mistake).
    if (step.skipWhen != null && step.skipWhen !== '') {
      const skipErr = validateCondition(step.skipWhen)
      if (skipErr) issues.push({ level: 'error', stepId: id, message: `"${id}" skip-when "${step.skipWhen}": ${skipErr}` })
      const hasOnward = !!step.next || (step.transitions ?? []).some((tr) => !!tr.to)
      if (!hasOnward) issues.push({ level: 'warning', stepId: id, message: `"${id}" is skippable but has no onward step — skipping it ends the process.` })
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
      // verify block (light client-side checks; the server is authoritative).
      if (cfg.verify) {
        const ver = cfg.verify
        if (step.screen !== 'textInput' && step.screen !== 'numberInput') {
          issues.push({ level: 'error', stepId: id, message: `"${id}" has a verify block but is not a text/number input.` })
        }
        if (!ver.kind) {
          issues.push({ level: 'error', stepId: id, message: `"${id}" verify is missing a kind.` })
        }
        if (ver.onNotFound?.mode === 'goto') {
          if (!ver.onNotFound.step) {
            issues.push({ level: 'error', stepId: id, message: `"${id}" verify "go to step" has no target step.` })
          } else if (!def.steps[ver.onNotFound.step]) {
            issues.push({ level: 'error', stepId: id, message: `"${id}" verify go-to step "${ver.onNotFound.step}" does not exist.` })
          }
        }
        for (const [field, target] of Object.entries(ver.write ?? {})) {
          if (target && !schemaNames.has(target)) {
            issues.push({ level: 'error', stepId: id, message: `"${id}" verify writes "${field}" to undeclared variable "${target}".` })
          }
        }
      }
    } else if (isScriptStep(step)) {
      // Sandboxed-script step (spec §7.2). Light client-side checks only — the server does the
      // authoritative parse + sandbox-limit enforcement.
      const sc = step.config
      if (!sc || !sc.script || !sc.script.trim()) {
        issues.push({ level: 'error', stepId: id, message: `"${id}" script step has an empty script.` })
      }
      const seenOut = new Set<string>()
      for (const out of sc?.outputs ?? []) {
        const name = (out.name ?? '').trim()
        if (!name) {
          issues.push({ level: 'error', stepId: id, message: `"${id}" script step has an unnamed output.` })
          continue
        }
        if (!IDENT_RE.test(name)) {
          issues.push({ level: 'error', stepId: id, message: `"${id}" script output "${name}" is not a valid identifier.` })
        }
        if (seenOut.has(name)) {
          issues.push({ level: 'error', stepId: id, message: `"${id}" script step declares output "${name}" twice.` })
        }
        seenOut.add(name)
        // A declared output that is not mapped to a data-object variable is silently dropped at runtime.
        if (!schemaNames.has(name)) {
          issues.push({ level: 'warning', stepId: id, message: `"${id}" script output "${name}" is not a declared data-object variable.` })
        }
      }
    } else {
      // task step: input/output mappings should reference declared variables
      for (const v of Object.values(step.input ?? {})) {
        if (v && !schemaNames.has(v)) issues.push({ level: 'warning', stepId: id, message: `"${id}" task input maps undeclared variable "${v}".` })
      }
      for (const v of Object.values(step.output ?? {})) {
        if (v && !schemaNames.has(v)) issues.push({ level: 'warning', stepId: id, message: `"${id}" task output writes undeclared variable "${v}".` })
      }
      // required inputs per the live catalog: every required input must be mapped to a variable.
      const taskDef = taskTypeById(step.task, catalog)
      if (!taskDef) {
        issues.push({ level: 'warning', stepId: id, message: `"${id}" uses unknown task type "${step.task}".` })
      } else {
        for (const inp of taskDef.inputs) {
          if (inp.required && !step.input?.[inp.name]) {
            issues.push({ level: 'error', stepId: id, message: `"${id}" task "${step.task}" is missing required input "${inp.name}".` })
          }
        }
      }
    }
  }

  return issues
}

export function hasErrors(issues: ValidationIssue[]): boolean {
  return issues.some((i) => i.level === 'error')
}
