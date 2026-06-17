// Right pane of the designer: edit the SELECTED step's properties. Every change calls back up so the
// centre live-preview updates instantly. For screen steps: header/detail with a placeholder picker,
// writeTo dropdown, validation builder, scan-binding toggle, options (choice), confirm/checkbox
// (acknowledge), plus the screen's step id. For work steps: a one-line summary (the kind-specific
// config + the step name are edited in TaskDialog). The flow itself (default `next` + branches) is
// drawn/edited entirely on the canvas, so the panel no longer duplicates it; only skip-when (a
// per-step condition, not a link) stays here. The data-object editor lives in its own dialog
// (DataObjectDialog), opened from the "Data object" button in the left pane.
//
// All inputs are controlled; no hooks beyond useState for the placeholder-target field, declared at
// the top (Rules of Hooks).

import { useMemo, useState } from 'react'
import { useT } from '../../i18n/useT'
import { validateCondition, unknownExpressionVars } from '../condition'
import {
  isComputeStep,
  isScriptStep,
  taskTypeById,
  type Capabilities,
  type ChoiceOption,
  type ComputeStep,
  type DataVar,
  type ProcessDefinition,
  type ScreenStep,
  type Step,
  type TaskStep,
  type TaskTypeDef,
} from '../model'
import VarCombobox from './VarCombobox'

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

export default function PropertiesPanel({ def, selectedId, tasks, capabilities, onChangeStep, onRenameStep }: Props) {
  const step = selectedId ? def.steps[selectedId] : undefined

  return (
    <aside className="op-pd-props">
      {step && selectedId ? (
        step.type === 'screen' ? (
          <ScreenProps
            def={def}
            id={selectedId}
            step={step}
            capabilities={capabilities}
            onChange={(s) => onChangeStep(selectedId, s)}
            onRename={(n) => onRenameStep(selectedId, n)}
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

function Field({ label, children, wide }: { label: string; children: React.ReactNode; wide?: boolean }) {
  return (
    <label className={wide ? 'op-pd-field op-pd-wide' : 'op-pd-field'}>
      <span className="op-pd-field-label">{label}</span>
      {children}
    </label>
  )
}

function StepIdField({ id, onRename }: { id: string; onRename: (n: string) => void }) {
  const [val, setVal] = useState(id)
  return (
    <Field label="Step id">
      <input
        value={val}
        onChange={(e) => setVal(e.target.value.replace(/[^A-Za-z0-9_]/g, ''))}
        onBlur={() => { if (val && val !== id) onRename(val) }}
      />
    </Field>
  )
}

// --- header/detail with placeholder picker -------------------------------------------------------

function TextWithPlaceholders({
  label,
  value,
  vars,
  onChange,
}: {
  label: string
  value: string
  vars: DataVar[]
  onChange: (v: string) => void
}) {
  return (
    <Field label={label} wide>
      <textarea value={value} rows={2} onChange={(e) => onChange(e.target.value)} />
      {vars.length > 0 && (
        <div className="op-pd-chips">
          <span className="muted" style={{ fontSize: '.75rem' }}>Insert:</span>
          {vars.map((v) => (
            <button
              key={v.name}
              type="button"
              className="op-pd-chip"
              onClick={() => onChange(`${value}{{${v.name}}}`)}
              title={`${v.name} (${v.type})`}
            >
              {`{{${v.name}}}`}
            </button>
          ))}
        </div>
      )}
    </Field>
  )
}

// --- screen step properties ----------------------------------------------------------------------

function ScreenProps({
  def,
  id,
  step,
  capabilities,
  onChange,
  onRename,
}: {
  def: ProcessDefinition
  id: string
  step: ScreenStep
  capabilities: Capabilities
  onChange: (s: ScreenStep) => void
  onRename: (n: string) => void
}) {
  const cfg = step.config
  const set = (patch: Partial<ScreenStep>) => onChange({ ...step, ...patch })
  const setCfg = (patch: Partial<typeof cfg>) => onChange({ ...step, config: { ...cfg, ...patch } })
  const setVal = (patch: Partial<NonNullable<typeof cfg.validation>>) =>
    setCfg({ validation: { ...cfg.validation, ...patch } })

  const captures = step.screen !== 'acknowledge'
  const isText = step.screen === 'textInput'
  const isNum = step.screen === 'numberInput'
  const isDate = step.screen === 'dateInput'
  const isChoice = step.screen === 'questionChoice'
  const isQuestion = step.screen === 'questionYesNo' || isChoice
  const isAck = step.screen === 'acknowledge'

  return (
    <div className="op-pd-props-body">
      <h3>{step.screen}</h3>
      <StepIdField id={id} onRename={onRename} />

      <TextWithPlaceholders label="Header" value={cfg.header ?? ''} vars={def.dataSchema} onChange={(v) => setCfg({ header: v })} />
      <TextWithPlaceholders label="Detail" value={cfg.detail ?? ''} vars={def.dataSchema} onChange={(v) => setCfg({ detail: v })} />

      {captures && !isQuestion && (
        <Field label="Write to (variable)">
          <VarCombobox value={cfg.writeTo ?? ''} options={def.dataSchema} onChange={(name) => setCfg({ writeTo: name || undefined })} />
        </Field>
      )}

      {(isText || isNum) && (
        <label className="op-pd-toggle">
          <input type="checkbox" checked={!!cfg.scanBinding} onChange={(e) => setCfg({ scanBinding: e.target.checked })} />
          Scan binding (capture from a scanner)
        </label>
      )}

      {/* Validation builder */}
      {(isText || isNum || isDate) && (
        <fieldset className="op-pd-fieldset">
          <legend>Validation</legend>
          <label className="op-pd-toggle">
            <input type="checkbox" checked={!!cfg.validation?.required} onChange={(e) => setVal({ required: e.target.checked })} />
            Required
          </label>
          {isText && (
            <>
              <Field label="Regex (optional)">
                <input value={cfg.validation?.regex ?? ''} onChange={(e) => setVal({ regex: e.target.value || undefined })} />
              </Field>
              <Field label="Max length">
                <input type="number" value={cfg.validation?.maxLength ?? ''} onChange={(e) => setVal({ maxLength: e.target.value ? Number(e.target.value) : undefined })} />
              </Field>
              <Field label="Must equal ({{var}})">
                <input value={cfg.validation?.mustEqual ?? ''} onChange={(e) => setVal({ mustEqual: e.target.value || undefined })} />
              </Field>
            </>
          )}
          {isNum && (
            <>
              <Field label="Min"><input type="number" value={cfg.validation?.min ?? ''} onChange={(e) => setVal({ min: e.target.value ? Number(e.target.value) : undefined })} /></Field>
              <Field label="Max"><input type="number" value={cfg.validation?.max ?? ''} onChange={(e) => setVal({ max: e.target.value ? Number(e.target.value) : undefined })} /></Field>
              <label className="op-pd-toggle">
                <input type="checkbox" checked={!!cfg.validation?.integerOnly} onChange={(e) => setVal({ integerOnly: e.target.checked })} />
                Whole numbers only
              </label>
              <Field label="Must equal ({{var}})"><input value={cfg.validation?.mustEqual ?? ''} onChange={(e) => setVal({ mustEqual: e.target.value || undefined })} /></Field>
            </>
          )}
          {isDate && (
            <>
              <Field label="Min date"><input type="date" value={cfg.validation?.min != null ? String(cfg.validation.min) : ''} onChange={(e) => setVal({ min: e.target.value as unknown as number })} /></Field>
              <Field label="Max date"><input type="date" value={cfg.validation?.max != null ? String(cfg.validation.max) : ''} onChange={(e) => setVal({ max: e.target.value as unknown as number })} /></Field>
            </>
          )}
        </fieldset>
      )}

      {/* Scan verification is configured from the "Verify flow" button next to the live preview
          (not here), so the panel stays focused on the screen's own fields. */}

      {isAck && (
        <>
          <Field label="Confirm button label"><input value={cfg.confirmLabel ?? ''} onChange={(e) => setCfg({ confirmLabel: e.target.value || undefined })} placeholder="Continue" /></Field>
          <label className="op-pd-toggle">
            <input type="checkbox" checked={!!cfg.requireCheckbox} onChange={(e) => setCfg({ requireCheckbox: e.target.checked })} />
            Require a confirmation checkbox
          </label>
          {cfg.requireCheckbox && (
            <Field label="Checkbox label"><input value={cfg.checkboxLabel ?? ''} onChange={(e) => setCfg({ checkboxLabel: e.target.value || undefined })} placeholder="I confirm" /></Field>
          )}
        </>
      )}

      {isQuestion && (
        <Field label="Question writes to (variable)">
          <VarCombobox value={cfg.writeTo ?? ''} options={def.dataSchema} onChange={(name) => setCfg({ writeTo: name || undefined })} />
        </Field>
      )}

      {isChoice && <ChoiceOptionsEditor options={cfg.options ?? []} onChange={(options) => setCfg({ options })} />}

      {/* Default next + branches are drawn/edited on the canvas, not here. Skip-when is a per-step
          condition (not a link), so it stays in the panel. */}
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

function ChoiceOptionsEditor({ options, onChange }: { options: ChoiceOption[]; onChange: (o: ChoiceOption[]) => void }) {
  return (
    <fieldset className="op-pd-fieldset op-pd-wide">
      <legend>Answers</legend>
      {options.map((o, i) => (
        <div key={i} style={{ display: 'flex', gap: '.4rem', marginBottom: '.4rem' }}>
          <input placeholder="key" value={o.key} style={{ width: 90 }} onChange={(e) => onChange(options.map((x, j) => (j === i ? { ...x, key: e.target.value } : x)))} />
          <input placeholder="label" value={o.label} onChange={(e) => onChange(options.map((x, j) => (j === i ? { ...x, label: e.target.value } : x)))} />
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => onChange(options.filter((_, j) => j !== i))}>✕</button>
        </div>
      ))}
      <button type="button" className="btn btn-ghost btn-sm" onClick={() => onChange([...options, { key: `opt${options.length + 1}`, label: '' }])}>+ Add answer</button>
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

