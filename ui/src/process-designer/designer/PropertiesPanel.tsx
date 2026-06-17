// Right pane of the designer: edit the SELECTED step's properties. Every change calls back up so the
// centre live-preview updates instantly. The panel is deliberately decluttered: both screen steps and
// work steps show a one-line summary of what the step does (its full config is edited in a guided
// dialog — ScreenDialog for screens, TaskDialog for tasks/compute, both opened from buttons by the
// live preview). The flow itself (default `next` + branches) is drawn/edited entirely on the canvas,
// so the panel no longer duplicates it; only skip-when (a per-step condition, not a link) stays here.
// The data-object editor lives in its own dialog (DataObjectDialog), opened from the left pane.
//
// All inputs are controlled; no hooks beyond useT/useMemo, declared at the top (Rules of Hooks).

import { useMemo } from 'react'
import { useT } from '../../i18n/useT'
import { validateCondition, unknownExpressionVars } from '../condition'
import {
  SCREEN_TYPE_LABELS,
  isComputeStep,
  isScriptStep,
  taskTypeById,
  type Capabilities,
  type ComputeStep,
  type DataVar,
  type ProcessDefinition,
  type ScreenStep,
  type Step,
  type TaskStep,
  type TaskTypeDef,
} from '../model'

interface Props {
  def: ProcessDefinition
  selectedId: string | null
  /** The live server task catalog (falls back to the static library); drives the task picker. */
  tasks: TaskTypeDef[]
  /** Phase 3 server capabilities: gate the script-step editor and the AI assist UI. */
  capabilities: Capabilities
  onChangeStep: (id: string, step: Step) => void
  /** Rename a step id (updates references). */
  onRenameStep: (oldId: string, newId: string) => void
}

export default function PropertiesPanel({ def, selectedId, tasks, onChangeStep }: Props) {
  const step = selectedId ? def.steps[selectedId] : undefined

  return (
    <aside className="op-pd-props">
      {step && selectedId ? (
        step.type === 'screen' ? (
          <ScreenProps
            def={def}
            step={step}
            onChange={(s) => onChangeStep(selectedId, s)}
          />
        ) : (
          <StepWorkProps
            def={def}
            step={step}
            tasks={tasks}
            onChange={(s) => onChangeStep(selectedId, s)}
          />
        )
      ) : (
        <p className="muted" style={{ padding: '1rem' }}>Select a step to edit its properties.</p>
      )}
    </aside>
  )
}

// --- screen step properties ----------------------------------------------------------------------

/** One-line summary of a screen step, e.g. "Text input → writes qty; scan binding on" or
 *  "Question (yes/no) → writes answer". The full config (header/detail/validation/etc.) AND the step
 *  name now live in ScreenDialog ("Edit screen", by the live preview). Only skip-when stays inline. */
function screenSummary(step: ScreenStep, t: (k: string, e: string) => string): string {
  const cfg = step.config
  const kind = t(`screenType_${step.screen}`, SCREEN_TYPE_LABELS[step.screen])
  const parts: string[] = []
  if (cfg.writeTo) parts.push(t('summaryWritesTo', '→ writes {var}').replace('{var}', cfg.writeTo))
  if ((step.screen === 'textInput' || step.screen === 'numberInput') && cfg.scanBinding) {
    parts.push(t('summaryScanBinding', 'scan binding on'))
  }
  if (cfg.verify) parts.push(t('summaryVerifyOn', 'verify on'))
  return parts.length ? `${kind} ${parts.join('; ')}` : kind
}

/** Decluttered properties for a SCREEN step: a compact one-line summary of the screen + the step's
 *  skip-when (a per-step condition, not a link). The screen's full config + name are edited in the
 *  guided ScreenDialog ("Edit screen", by the live preview). Flow is drawn on the canvas. */
function ScreenProps({
  def,
  step,
  onChange,
}: {
  def: ProcessDefinition
  step: ScreenStep
  onChange: (s: ScreenStep) => void
}) {
  const t = useT('processDesign')
  const set = (patch: Partial<ScreenStep>) => onChange({ ...step, ...patch })
  const heading = t(`screenType_${step.screen}`, SCREEN_TYPE_LABELS[step.screen])
  const summary = useMemo(() => screenSummary(step, t), [step, t])

  return (
    <div className="op-pd-props-body">
      <h3>{heading}</h3>
      <p className="muted op-pd-verify-summary-line">{summary}</p>

      <SkipWhenField value={step.skipWhen ?? ''} vars={def.dataSchema} onChange={(v) => set({ skipWhen: v || undefined })} />
    </div>
  )
}

/** "Skip this step when…" — a condition (same grammar as a transition `when`). True at runtime ->
 *  the step is skipped without rendering. Live-validated so the designer sees a malformed expression. */
function SkipWhenField({ value, vars, onChange }: { value: string; vars: DataVar[]; onChange: (v: string) => void }) {
  const t = useT('processDesign')
  const err = value ? validateCondition(value) : null
  const unknown = value && !err ? unknownExpressionVars(value, vars.map((v) => v.name)) : []
  return (
    <fieldset className="op-pd-fieldset op-pd-wide">
      <legend>{t('skipWhen', 'Skip this step when…')}</legend>
      <p className="muted" style={{ fontSize: '.75rem', margin: '0 0 .4rem' }}>
        {t('skipWhenHint', 'A condition over the data object (e.g. damaged == false). When true at runtime the step is skipped.')}
      </p>
      <input placeholder="e.g. qty == 0" value={value} onChange={(e) => onChange(e.target.value)} />
      {err && <span className="op-pd-issue-err" style={{ fontSize: '.75rem' }}>⚠ {err}</span>}
      {unknown.length > 0 && <span className="op-pd-issue-err" style={{ fontSize: '.75rem' }}>⚠ {t('condUnknownVars', 'Unknown variable(s): {list}. Add them in Data object.').replace('{list}', unknown.join(', '))}</span>}
    </fieldset>
  )
}

// --- work step properties (task / compute / script) ----------------------------------------------

/** One-line summary of what a work step does, e.g. "Server action: inventory.lookup",
 *  "Compute: sets match, prevCount", "Sandboxed script". Used in the decluttered properties panel. */
function workStepSummary(step: TaskStep | ComputeStep, tasks: TaskTypeDef[], t: (k: string, e: string) => string): string {
  if (isComputeStep(step)) {
    const names = (step.set ?? []).filter((r) => r.var).map((r) => r.var)
    return names.length
      ? t('summaryCompute', 'Compute: sets {list}').replace('{list}', names.join(', '))
      : t('summaryComputeEmpty', 'Compute (no values set)')
  }
  if (isScriptStep(step)) return t('summaryScript', 'Sandboxed script')
  const taskDef = taskTypeById(step.task, tasks)
  return t('summaryServerAction', 'Server action: {task}').replace('{task}', taskDef?.label ?? step.task)
}

/** Decluttered properties for a "work" step (task / compute / script): a compact one-line summary of
 *  what the step does + an "Edit task…" button opening the guided TaskDialog. The kind-specific config
 *  AND the step name (id) live in TaskDialog now. Default next + branches are drawn on the canvas.
 *  Only skip-when (a per-step condition, not a link) stays inline here. */
function StepWorkProps({
  def,
  step,
  tasks,
  onChange,
}: {
  def: ProcessDefinition
  step: TaskStep | ComputeStep
  tasks: TaskTypeDef[]
  onChange: (s: Step) => void
}) {
  const t = useT('processDesign')
  const set = (patch: { skipWhen?: string }) => onChange({ ...step, ...patch } as Step)

  const heading = isComputeStep(step)
    ? t('computeStep', 'Compute')
    : isScriptStep(step)
      ? t('scriptStep', 'Sandboxed script')
      : t('taskStep', 'Task')
  const summary = useMemo(() => workStepSummary(step, tasks, t), [step, tasks, t])

  return (
    <div className="op-pd-props-body">
      <h3>{heading}</h3>
      <p className="muted op-pd-verify-summary-line">{summary}</p>

      <SkipWhenField value={step.skipWhen ?? ''} vars={def.dataSchema} onChange={(v) => set({ skipWhen: v || undefined })} />
    </div>
  )
}

