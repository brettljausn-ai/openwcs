// The step-by-step "Edit task" configuration dialog (replaces the old cramped inline task block in
// the properties panel, mirroring the Verify dialog pattern). A lightweight modal (the shared
// .modal-backdrop + .dialog CSS) walking the designer through two guided steps:
//   1. What should this step do?  -> choose the step KIND:
//        - "Run a server action"     (a curated task from the live catalog)         -> task step
//        - "Compute values & decisions" (the no-code compute step)                  -> compute step
//        - "Run a sandboxed script"  (ONLY when scriptingEnabled && canAuthorScript) -> script step
//      Selecting a kind CONVERTS the step accordingly, preserving id/next/transitions/skipWhen
//      (those live on the step object the parent owns, not in this dialog).
//   2. Configure the chosen kind:
//        - server action: task-type picker + input mapping (task input <- variable) + output mapping
//          (task output -> variable) + the task description; plus the AI task-assist (when enabled).
//        - compute: an ordered list of assignment rows (target variable + expression) with live
//          parse validation + a short helper with examples; add/remove/reorder; plus AI assist.
//        - script: the existing ScriptEditor.
//
// Rules of Hooks: every hook runs unconditionally at the top, BEFORE any early return.

import { useEffect, useMemo, useState } from 'react'
import { useT } from '../../i18n/useT'
import { validateExpression, unknownExpressionVars } from '../condition'
import {
  SCRIPT_TASK_TYPE,
  isComputeStep,
  isScriptStep,
  taskTypeById,
  type Capabilities,
  type ComputeAssignment,
  type ComputeStep,
  type DataVar,
  type ScriptConfig,
  type Step,
  type TaskStep,
  type TaskTypeDef,
} from '../model'
import ScriptEditor from './ScriptEditor'
import TaskAssist from './TaskAssist'
import TaskCombobox from './TaskCombobox'
import VarCombobox from './VarCombobox'

/** The three step kinds the dialog offers (gated below by capabilities). */
type Kind = 'task' | 'compute' | 'script'

function kindOf(step: Step): Kind {
  if (isComputeStep(step)) return 'compute'
  if (isScriptStep(step)) return 'script'
  return 'task'
}

interface Props {
  /** The step being edited (task / compute / script). The dialog converts between kinds in place. */
  step: Step
  /** The live server task catalog (drives the server-action picker). */
  tasks: TaskTypeDef[]
  /** Server capabilities: gate the script kind + the AI assist. */
  capabilities: Capabilities
  /** Data-object variables for the input/output/compute pickers. */
  vars: DataVar[]
  /** Apply the edited step (Done). */
  onDone: (s: Step) => void
  /** Discard and close (Cancel / Esc / click-outside). */
  onCancel: () => void
}

export default function TaskDialog({ step, tasks, capabilities, vars, onDone, onCancel }: Props) {
  const t = useT('processDesign')
  // Local draft so Cancel discards; Done commits. We keep the whole Step so id/next/transitions/
  // skipWhen carry through every kind conversion untouched.
  const [draft, setDraft] = useState<Step>(step)
  const [stepIdx, setStepIdx] = useState(0)

  // Esc closes the dialog.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.stopPropagation(); onCancel() }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onCancel])

  const scriptAllowed = capabilities.scriptingEnabled && capabilities.canAuthorScript
  const kind = kindOf(draft)

  // Safeguard step 2: a compute step may only be committed when every row targets a declared
  // variable and its expression parses with only declared variables. Done is disabled otherwise.
  const varNames = useMemo(() => vars.map((v) => v.name), [vars])
  const computeValid = useMemo(() => {
    if (kind !== 'compute') return true
    const rows = (draft as ComputeStep).set ?? []
    if (rows.length === 0) return false
    return rows.every((r) => {
      const e = (r.expr ?? '').trim()
      return !!r.var && varNames.includes(r.var) && !!e
        && validateExpression(e) == null && unknownExpressionVars(e, varNames).length === 0
    })
  }, [kind, draft, varNames])
  const doneDisabled = kind === 'compute' && !computeValid

  // Defaults for the curated server-action picker (the first NON-script catalog task).
  const defaultTask = useMemo(
    () => tasks.find((tt) => tt.id !== SCRIPT_TASK_TYPE)?.id ?? 'inventory.lookup',
    [tasks],
  )

  // Convert the draft to a new kind, PRESERVING the common step fields (next/transitions/skipWhen).
  const setKind = (next: Kind) => {
    if (next === kind) return
    const common = { next: draft.next, transitions: draft.transitions, skipWhen: draft.skipWhen }
    if (next === 'compute') {
      setDraft({ type: 'compute', set: [{ var: '', expr: '' }], ...common } satisfies ComputeStep)
    } else if (next === 'script') {
      const cfg = (draft.type === 'task' ? draft.config : undefined) ?? { script: '', outputs: [] }
      setDraft({ type: 'task', task: SCRIPT_TASK_TYPE, input: {}, output: {}, config: cfg, ...common } satisfies TaskStep)
    } else {
      setDraft({ type: 'task', task: defaultTask, input: {}, output: {}, ...common } satisfies TaskStep)
    }
  }

  const steps = [
    t('taskDialogStep1', 'What should this step do?'),
    t('taskDialogStep2', 'Configure the step'),
  ]

  return (
    <div className="modal-backdrop" onMouseDown={onCancel}>
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-label={t('taskDialogTitle', 'Set up this step')}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <h2 style={{ marginBottom: '.4rem' }}>{t('taskDialogTitle', 'Set up this step')}</h2>
        <p className="muted" style={{ fontSize: '.8rem', margin: '0 0 1rem' }}>
          {t('taskDialogIntro', 'Choose what this step does, then configure it. Branches, default next and skip stay as set in the panel.')}
        </p>

        {/* Step indicator (reuses the verify-dialog styling) */}
        <ol className="op-pd-verify-steps">
          {steps.map((label, i) => (
            <li
              key={i}
              className={`op-pd-verify-step${i === stepIdx ? ' is-active' : ''}${i < stepIdx ? ' is-done' : ''}`}
            >
              <button type="button" onClick={() => setStepIdx(i)}>
                <span className="op-pd-verify-step-num">{i + 1}</span>
                <span className="op-pd-verify-step-label">{label}</span>
              </button>
            </li>
          ))}
        </ol>

        <div className="op-pd-verify-pane">
          {stepIdx === 0 && (
            <KindStep
              kind={kind}
              scriptAllowed={scriptAllowed}
              onPick={(k) => { setKind(k); setStepIdx(1) }}
            />
          )}

          {stepIdx === 1 && kind === 'task' && (
            <ServerActionStep
              draft={draft as TaskStep}
              tasks={tasks}
              vars={vars}
              capabilities={capabilities}
              scriptAllowed={scriptAllowed}
              onChange={setDraft}
            />
          )}

          {stepIdx === 1 && kind === 'compute' && (
            <ComputeStepConfig
              draft={draft as ComputeStep}
              vars={vars}
              capabilities={capabilities}
              scriptAllowed={scriptAllowed}
              onChange={setDraft}
            />
          )}

          {stepIdx === 1 && kind === 'script' && (
            <ScriptStepConfig draft={draft as TaskStep} scriptAllowed={scriptAllowed} onChange={setDraft} />
          )}
        </div>

        <div className="dialog-actions" style={{ justifyContent: 'space-between' }}>
          <div style={{ display: 'flex', gap: '.5rem' }}>
            <button
              type="button"
              className="btn btn-ghost"
              disabled={stepIdx === 0}
              onClick={() => setStepIdx((i) => Math.max(0, i - 1))}
            >
              {t('verifyBack', 'Back')}
            </button>
            {stepIdx < steps.length - 1 && (
              <button type="button" className="btn btn-ghost" onClick={() => setStepIdx((i) => Math.min(steps.length - 1, i + 1))}>
                {t('verifyNext', 'Next')}
              </button>
            )}
          </div>
          <div style={{ display: 'flex', gap: '.5rem' }}>
            <button type="button" className="btn btn-ghost" onClick={onCancel}>{t('cancel', 'Cancel')}</button>
            <button
              type="button"
              className="btn btn-primary"
              disabled={doneDisabled}
              title={doneDisabled ? t('computeFixFirst', 'Fix the highlighted rows first: each must set a declared variable from a valid expression.') : undefined}
              onClick={() => onDone(draft)}
            >
              {t('done', 'Done')}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

// --- step 1: choose the kind ---------------------------------------------------------------------

function KindStep({
  kind,
  scriptAllowed,
  onPick,
}: {
  kind: Kind
  scriptAllowed: boolean
  onPick: (k: Kind) => void
}) {
  const t = useT('processDesign')
  const options: { k: Kind; label: string; help: string; show: boolean }[] = [
    {
      k: 'task',
      label: t('kindTask', 'Run a server action'),
      help: t('kindTaskHelp', 'Pick a curated task (putaway, move, lookup…). It runs on the server as a checkpoint.'),
      show: true,
    },
    {
      k: 'compute',
      label: t('kindCompute', 'Compute values & decisions'),
      help: t('kindComputeHelp', 'No-code: set variables from expressions (e.g. a match flag for branching). Runs on the device, works offline.'),
      show: true,
    },
    {
      k: 'script',
      label: t('kindScript', 'Run a sandboxed script'),
      help: t('kindScriptHelp', 'For advanced logic: a small JavaScript snippet run in a server sandbox.'),
      show: scriptAllowed,
    },
  ]
  return (
    <>
      <h3 className="op-pd-verify-h">{t('taskDialogStep1', 'What should this step do?')}</h3>
      <p className="muted op-pd-verify-guide">
        {t('taskDialogStep1Guide', 'Pick the kind of step. You can change it later; switching keeps the step id, next and branches.')}
      </p>
      <div className="op-pd-verify-kinds">
        {options.filter((o) => o.show).map((o) => (
          <label key={o.k} className={`op-pd-verify-kind${kind === o.k ? ' is-selected' : ''}`}>
            <input type="radio" name="task-kind" checked={kind === o.k} onChange={() => onPick(o.k)} />
            <span className="op-pd-verify-kind-label">{o.label}</span>
            <span className="op-pd-verify-kind-help muted">{o.help}</span>
          </label>
        ))}
      </div>
    </>
  )
}

// --- step 2a: server action (curated task) -------------------------------------------------------

function ServerActionStep({
  draft,
  tasks,
  vars,
  capabilities,
  scriptAllowed,
  onChange,
}: {
  draft: TaskStep
  tasks: TaskTypeDef[]
  vars: DataVar[]
  capabilities: Capabilities
  scriptAllowed: boolean
  onChange: (s: Step) => void
}) {
  const t = useT('processDesign')
  const taskDef = taskTypeById(draft.task, tasks)
  // The picker lists every catalog task except the built-in "script" type (that is its own kind).
  const pickableTasks = tasks.filter((tt) => tt.id !== SCRIPT_TASK_TYPE)

  const changeTaskType = (next: string) => onChange({ ...draft, task: next, input: {}, output: {}, config: undefined })
  const setInput = (name: string, varName: string) => onChange({ ...draft, input: { ...draft.input, [name]: varName } })
  const setOutput = (name: string, varName: string) => onChange({ ...draft, output: { ...draft.output, [name]: varName } })

  // AI assist: apply a curated suggestion (switch task + fill mappings) or insert a script draft
  // (converts this step to a script step).
  const applyCurated = (taskType: string, inputs: Record<string, string>, outputs: Record<string, string>) =>
    onChange({ ...draft, task: taskType, input: { ...inputs }, output: { ...outputs }, config: undefined })
  const insertScript = (config: ScriptConfig) =>
    onChange({ ...draft, task: SCRIPT_TASK_TYPE, input: {}, output: {}, config })

  return (
    <>
      <h3 className="op-pd-verify-h">{t('kindTask', 'Run a server action')}</h3>
      <label className="op-pd-field">
        <span className="op-pd-field-label">{t('taskType', 'Task type')}</span>
        <TaskCombobox value={draft.task} options={pickableTasks} onChange={changeTaskType} placeholder={t('taskPickPlaceholder', 'Search tasks…')} />
      </label>
      {taskDef?.description && (
        <p className="op-pd-task-desc" style={{ margin: '.4rem 0 .6rem' }}>{taskDef.description}</p>
      )}

      {taskDef && taskDef.inputs.length > 0 && (
        <fieldset className="op-pd-fieldset">
          <legend>{t('taskInputs', 'Inputs (task ← variable)')}</legend>
          {taskDef.inputs.map((inp) => (
            <label key={inp.name} className="op-pd-field">
              <span className="op-pd-field-label">{inp.required ? `${inp.name} *` : inp.name}</span>
              {inp.description && <span className="op-pd-io-hint muted">{inp.description}</span>}
              <VarCombobox value={draft.input?.[inp.name] ?? ''} options={vars} onChange={(name) => setInput(inp.name, name)} />
            </label>
          ))}
        </fieldset>
      )}

      {taskDef && taskDef.outputs.length > 0 && (
        <fieldset className="op-pd-fieldset">
          <legend>{t('taskOutputs', 'Outputs (task → variable)')}</legend>
          {taskDef.outputs.map((out) => (
            <label key={out.name} className="op-pd-field">
              <span className="op-pd-field-label">{out.name}</span>
              {out.description && <span className="op-pd-io-hint muted">{out.description}</span>}
              <VarCombobox value={draft.output?.[out.name] ?? ''} options={vars} onChange={(name) => setOutput(out.name, name)} />
            </label>
          ))}
        </fieldset>
      )}

      {capabilities.aiAssistEnabled && (
        <TaskAssist schema={vars} canInsertScript={scriptAllowed} onApplyCurated={applyCurated} onInsertScript={insertScript} />
      )}
    </>
  )
}

// --- step 2b: compute (no-code assignments) ------------------------------------------------------

function ComputeStepConfig({
  draft,
  vars,
  capabilities,
  scriptAllowed,
  onChange,
}: {
  draft: ComputeStep
  vars: DataVar[]
  capabilities: Capabilities
  scriptAllowed: boolean
  onChange: (s: Step) => void
}) {
  const t = useT('processDesign')
  const rows = draft.set ?? []
  const varNames = vars.map((v) => v.name)
  const setRows = (next: ComputeAssignment[]) => onChange({ ...draft, set: next })
  const update = (i: number, patch: Partial<ComputeAssignment>) =>
    setRows(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)))
  const remove = (i: number) => setRows(rows.filter((_, j) => j !== i))
  const move = (i: number, dir: -1 | 1) => {
    const j = i + dir
    if (j < 0 || j >= rows.length) return
    const next = rows.slice()
    ;[next[i], next[j]] = [next[j], next[i]]
    setRows(next)
  }
  const add = () => setRows([...rows, { var: '', expr: '' }])

  // AI assist on the compute step: a curated suggestion converts to a server-action task; a script
  // draft converts to a script step (both preserve the common step fields).
  const applyCurated = (taskType: string, inputs: Record<string, string>, outputs: Record<string, string>) =>
    onChange({ type: 'task', task: taskType, input: { ...inputs }, output: { ...outputs }, next: draft.next, transitions: draft.transitions, skipWhen: draft.skipWhen })
  const insertScript = (config: ScriptConfig) =>
    onChange({ type: 'task', task: SCRIPT_TASK_TYPE, input: {}, output: {}, config, next: draft.next, transitions: draft.transitions, skipWhen: draft.skipWhen })

  return (
    <>
      <h3 className="op-pd-verify-h">{t('kindCompute', 'Compute values & decisions')}</h3>
      <p className="muted op-pd-verify-guide">
        {t('computeGuide', 'Each row sets a variable to the value of an expression, top to bottom. Use them to derive flags for branching. Examples: ')}
        <code>qty == expected or qty == prevCount</code>{', '}<code>expected - qty</code>
      </p>

      <div className="op-pd-compute-rows">
        {rows.map((row, i) => {
          const expr = row.expr ?? ''
          const err = expr.trim() ? validateExpression(expr) : null
          // Variables must actually exist in the data object (both the target and any referenced).
          const targetErr = row.var && !varNames.includes(row.var)
          const unknownVars = !err && expr.trim() ? unknownExpressionVars(expr, varNames) : []
          return (
            <div key={i} className="op-pd-compute-row">
              <div className="op-pd-compute-row-main">
                <div className="op-pd-compute-target">
                  <VarCombobox value={row.var ?? ''} options={vars} placeholder={t('computeTargetPlaceholder', 'set variable…')} onChange={(name) => update(i, { var: name })} />
                </div>
                <span className="op-pd-compute-eq" aria-hidden="true">=</span>
                <input
                  className="op-pd-compute-expr"
                  value={expr}
                  spellCheck={false}
                  autoCorrect="off"
                  autoCapitalize="off"
                  placeholder={t('computeExprPlaceholder', 'e.g. qty == expected')}
                  onChange={(e) => update(i, { expr: e.target.value })}
                />
                <div className="op-pd-compute-row-actions">
                  <button type="button" className="op-pd-mini" title={t('moveUp', 'Move up')} disabled={i === 0} onClick={() => move(i, -1)}>↑</button>
                  <button type="button" className="op-pd-mini" title={t('moveDown', 'Move down')} disabled={i === rows.length - 1} onClick={() => move(i, 1)}>↓</button>
                  <button type="button" className="op-pd-mini" title={t('delete', 'Delete')} onClick={() => remove(i)}>✕</button>
                </div>
              </div>
              {targetErr && <span className="op-pd-issue-err" style={{ fontSize: '.74rem' }}>⚠ {t('computeTargetUnknown', 'Target is not a data-object variable, add it in Data object.')}</span>}
              {err && <span className="op-pd-issue-err" style={{ fontSize: '.74rem' }}>⚠ {err}</span>}
              {unknownVars.length > 0 && <span className="op-pd-issue-err" style={{ fontSize: '.74rem' }}>⚠ {t('computeUnknownVars', 'Unknown variable(s): {list}. Add them in Data object.').replace('{list}', unknownVars.join(', '))}</span>}
            </div>
          )
        })}
      </div>
      <button type="button" className="btn btn-ghost btn-sm" style={{ marginTop: '.4rem' }} onClick={add}>
        {t('computeAddRow', '+ Add assignment')}
      </button>

      {capabilities.aiAssistEnabled && (
        <div style={{ marginTop: '.6rem' }}>
          <TaskAssist schema={vars} canInsertScript={scriptAllowed} onApplyCurated={applyCurated} onInsertScript={insertScript} />
        </div>
      )}
    </>
  )
}

// --- step 2c: sandboxed script -------------------------------------------------------------------

function ScriptStepConfig({
  draft,
  scriptAllowed,
  onChange,
}: {
  draft: TaskStep
  scriptAllowed: boolean
  onChange: (s: Step) => void
}) {
  const t = useT('processDesign')
  if (!scriptAllowed) {
    return <p className="muted op-pd-assist-note">{t('scriptDisabled', 'Scripting is disabled on this server. This script step cannot be edited here.')}</p>
  }
  return <ScriptEditor config={draft.config} onChange={(config) => onChange({ ...draft, config })} />
}
