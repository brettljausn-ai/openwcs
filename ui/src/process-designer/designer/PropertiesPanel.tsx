// Right pane of the designer: edit the selected step's properties, and (below) the data-object
// schema. Every change calls back up so the centre live-preview updates instantly. For screen steps:
// header/detail with a placeholder picker, writeTo dropdown, validation builder, scan-binding toggle,
// options (choice), confirm/checkbox (acknowledge), and the transition editor (questions/branching).
// For task steps: a task-type picker + input/output variable mapping. The data-object panel
// declares/renames typed variables.
//
// All inputs are controlled; no hooks beyond useState for the placeholder-target field, declared at
// the top (Rules of Hooks).

import { useMemo, useState } from 'react'
import { useT } from '../../i18n/useT'
import { validateCondition } from '../condition'
import {
  isComputeStep,
  isScriptStep,
  taskTypeById,
  verifyFieldsForKind,
  type Capabilities,
  type ChoiceOption,
  type ComputeStep,
  type DataVar,
  type ProcessDefinition,
  type ScreenStep,
  type Step,
  type TaskStep,
  type TaskTypeDef,
  type Transition,
  type VarType,
  type VerifyConfig,
  type VerifyKind,
} from '../model'
import TaskDialog from './TaskDialog'
import VarCombobox from './VarCombobox'
import VerifyDialog from './VerifyDialog'

interface Props {
  def: ProcessDefinition
  selectedId: string | null
  /** The live server task catalog (falls back to the static library); drives the task picker. */
  tasks: TaskTypeDef[]
  /** Phase 3 server capabilities: gate the script-step editor and the AI assist UI. */
  capabilities: Capabilities
  onChangeStep: (id: string, step: Step) => void
  onChangeSchema: (schema: DataVar[]) => void
  /** Rename a step id (updates references). */
  onRenameStep: (oldId: string, newId: string) => void
}

const VAR_TYPES: VarType[] = ['string', 'number', 'boolean', 'date', 'sku', 'location', 'hu']

/** English fallback labels for the server-driven verify kinds (the i18n key still overrides these). */
const VERIFY_KIND_LABELS: Record<string, string> = {
  barcode: 'Barcode',
  sku: 'SKU code',
  location: 'Location',
  skuScan: 'Scan SKU code or barcode',
}
function verifyKindLabel(kind: string): string {
  return VERIFY_KIND_LABELS[kind] ?? kind
}

export default function PropertiesPanel({ def, selectedId, tasks, capabilities, onChangeStep, onChangeSchema, onRenameStep }: Props) {
  const step = selectedId ? def.steps[selectedId] : undefined
  const stepIds = Object.keys(def.steps)

  return (
    <aside className="op-pd-props">
      {step && selectedId ? (
        step.type === 'screen' ? (
          <ScreenProps
            def={def}
            id={selectedId}
            step={step}
            stepIds={stepIds}
            capabilities={capabilities}
            onChange={(s) => onChangeStep(selectedId, s)}
            onRename={(n) => onRenameStep(selectedId, n)}
          />
        ) : (
          <StepWorkProps
            def={def}
            id={selectedId}
            step={step}
            stepIds={stepIds}
            tasks={tasks}
            capabilities={capabilities}
            onChange={(s) => onChangeStep(selectedId, s)}
            onRename={(n) => onRenameStep(selectedId, n)}
          />
        )
      ) : (
        <p className="muted" style={{ padding: '1rem' }}>Select a step to edit its properties.</p>
      )}

      <DataObjectPanel schema={def.dataSchema} onChange={onChangeSchema} />
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
  stepIds,
  capabilities,
  onChange,
  onRename,
}: {
  def: ProcessDefinition
  id: string
  step: ScreenStep
  stepIds: string[]
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

      {/* Verify the scanned value exists (text/number inputs; only when the server advertises it). */}
      {(isText || isNum) && capabilities.verifyKinds.length > 0 && (
        <VerifyEditor
          verify={cfg.verify}
          kinds={capabilities.verifyKinds as VerifyKind[]}
          capabilities={capabilities}
          vars={def.dataSchema}
          stepIds={stepIds.filter((s) => s !== id)}
          onChange={(verify) => setCfg({ verify })}
        />
      )}

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

      {/* Default next + transitions for ALL screen steps */}
      <Field label="Default next step">
        <select value={step.next ?? ''} onChange={(e) => set({ next: e.target.value || undefined })}>
          <option value="">(end the process)</option>
          {stepIds.filter((s) => s !== id).map((s) => (<option key={s} value={s}>{s}</option>))}
        </select>
      </Field>

      <TransitionsEditor step={step} stepIds={stepIds.filter((s) => s !== id)} onChange={(transitions) => set({ transitions })} />

      <SkipWhenField value={step.skipWhen ?? ''} onChange={(v) => set({ skipWhen: v || undefined })} />
    </div>
  )
}

/** "Skip this step when…" — a condition (same grammar as a transition `when`). True at runtime ->
 *  the step is skipped without rendering. Live-validated so the designer sees a malformed expression. */
function SkipWhenField({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const t = useT('processDesign')
  const err = value ? validateCondition(value) : null
  return (
    <fieldset className="op-pd-fieldset op-pd-wide">
      <legend>{t('skipWhen', 'Skip this step when…')}</legend>
      <p className="muted" style={{ fontSize: '.75rem', margin: '0 0 .4rem' }}>
        {t('skipWhenHint', 'A condition over the data object (e.g. damaged == false). When true at runtime the step is skipped.')}
      </p>
      <input placeholder="e.g. qty == 0" value={value} onChange={(e) => onChange(e.target.value)} />
      {err && <span className="op-pd-issue-err" style={{ fontSize: '.75rem' }}>⚠ {err}</span>}
    </fieldset>
  )
}

/** First-class "Verify" control for a text/number input screen. Decluttered: a compact toggle + a
 *  one-line SUMMARY of the current config + an "Edit verification…" button that opens a guided,
 *  step-by-step dialog (kind -> per-kind fields to store -> not-found behaviour). The big inline form
 *  moved into VerifyDialog. Server is authoritative. */
function VerifyEditor({
  verify,
  kinds,
  capabilities,
  vars,
  stepIds,
  onChange,
}: {
  verify?: VerifyConfig
  kinds: VerifyKind[]
  capabilities: Capabilities
  vars: DataVar[]
  stepIds: string[]
  onChange: (v: VerifyConfig | undefined) => void
}) {
  const t = useT('processDesign')
  const [open, setOpen] = useState(false)
  const enabled = !!verify
  const v: VerifyConfig = verify ?? { kind: kinds[0] ?? 'barcode', onNotFound: { mode: 'reprompt' } }

  const toggle = (on: boolean) => {
    if (on) {
      onChange(v)
      setOpen(true) // turning it on goes straight into the guided dialog
    } else {
      setOpen(false)
      onChange(undefined)
    }
  }

  // One-line human summary, e.g. "Resolves as SKU; stores Description -> desc, Unit of measure ->
  // uom; re-prompts if not found".
  const summary = useMemo(() => {
    const kindName = t(`verifyKind_${v.kind}`, verifyKindLabel(v.kind))
    const fields = verifyFieldsForKind(v.kind, capabilities.verifyFields)
    const labelOf = (key: string) => fields.find((f) => f.key === key)?.label ?? key
    const stored = Object.entries(v.write ?? {})
      .filter(([, target]) => !!target)
      .map(([key, target]) => `${labelOf(key)} → ${target}`)
    const storedText = stored.length
      ? t('verifySummaryStores', 'stores {list}').replace('{list}', stored.join(', '))
      : t('verifySummaryNoStore', 'stores nothing')
    const notFound = v.onNotFound.mode === 'goto'
      ? t('verifySummaryGoto', 'goes to {step} if not found').replace('{step}', v.onNotFound.step || '?')
      : t('verifySummaryReprompt', 're-prompts if not found')
    return t('verifySummary', 'Resolves as {kind}; {stored}; {notFound}')
      .replace('{kind}', kindName)
      .replace('{stored}', storedText)
      .replace('{notFound}', notFound)
  }, [v, capabilities.verifyFields, t])

  return (
    <fieldset className="op-pd-fieldset">
      <legend>{t('verify', 'Verify')}</legend>
      <label className="op-pd-toggle">
        <input type="checkbox" checked={enabled} onChange={(e) => toggle(e.target.checked)} />
        {t('verifyEnable', 'Verify the scanned value exists')}
      </label>

      {enabled && (
        <div className="op-pd-verify-summary">
          <p className="muted op-pd-verify-summary-line">{summary}</p>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => setOpen(true)}>
            {t('verifyEdit', 'Edit verification…')}
          </button>
        </div>
      )}

      {open && enabled && (
        <VerifyDialog
          verify={v}
          kinds={kinds}
          capabilities={capabilities}
          vars={vars}
          stepIds={stepIds}
          onCancel={() => setOpen(false)}
          onDone={(next) => { onChange(next); setOpen(false) }}
        />
      )}
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

function TransitionsEditor({ step, stepIds, onChange }: { step: Step; stepIds: string[]; onChange: (t: Transition[]) => void }) {
  const transitions = step.transitions ?? []
  return (
    <fieldset className="op-pd-fieldset op-pd-wide">
      <legend>Branches (when → to)</legend>
      <p className="muted" style={{ fontSize: '.75rem', margin: '0 0 .5rem' }}>First matching condition wins; otherwise the default next is used.</p>
      {transitions.map((tr, i) => (
        <div key={i} style={{ display: 'flex', gap: '.4rem', marginBottom: '.4rem', flexWrap: 'wrap' }}>
          <input placeholder='e.g. damaged == true' value={tr.when} style={{ flex: '1 1 140px' }} onChange={(e) => onChange(transitions.map((x, j) => (j === i ? { ...x, when: e.target.value } : x)))} />
          <select value={tr.to} onChange={(e) => onChange(transitions.map((x, j) => (j === i ? { ...x, to: e.target.value } : x)))}>
            <option value="">(to…)</option>
            {stepIds.map((s) => (<option key={s} value={s}>{s}</option>))}
          </select>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => onChange(transitions.filter((_, j) => j !== i))}>✕</button>
        </div>
      ))}
      <button type="button" className="btn btn-ghost btn-sm" onClick={() => onChange([...transitions, { when: '', to: '' }])}>+ Add branch</button>
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
 *  what the step does + an "Edit task…" button opening the guided TaskDialog. The big inline config
 *  moved into TaskDialog. The common flow fields (default next, branches, skip-when) stay inline. */
function StepWorkProps({
  def,
  id,
  step,
  stepIds,
  tasks,
  capabilities,
  onChange,
  onRename,
}: {
  def: ProcessDefinition
  id: string
  step: TaskStep | ComputeStep
  stepIds: string[]
  tasks: TaskTypeDef[]
  capabilities: Capabilities
  onChange: (s: Step) => void
  onRename: (n: string) => void
}) {
  const t = useT('processDesign')
  const [open, setOpen] = useState(false)
  // Patch only the common flow fields (next / transitions / skipWhen) edited inline below; the
  // kind-specific config is edited in the dialog. Typed to the shared fields, not the union.
  const set = (patch: { next?: string; transitions?: Transition[]; skipWhen?: string }) =>
    onChange({ ...step, ...patch } as Step)

  const heading = isComputeStep(step)
    ? t('computeStep', 'Compute')
    : isScriptStep(step)
      ? t('scriptStep', 'Sandboxed script')
      : t('taskStep', 'Task')
  const summary = useMemo(() => workStepSummary(step, tasks, t), [step, tasks, t])

  return (
    <div className="op-pd-props-body">
      <h3>{heading}</h3>
      <StepIdField id={id} onRename={onRename} />

      <div className="op-pd-verify-summary">
        <p className="muted op-pd-verify-summary-line">{summary}</p>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setOpen(true)}>
          {t('editTask', 'Edit task…')}
        </button>
      </div>

      {open && (
        <TaskDialog
          step={step}
          tasks={tasks}
          capabilities={capabilities}
          vars={def.dataSchema}
          onCancel={() => setOpen(false)}
          onDone={(next) => { onChange(next); setOpen(false) }}
        />
      )}

      <Field label="Default next step">
        <select value={step.next ?? ''} onChange={(e) => set({ next: e.target.value || undefined })}>
          <option value="">(end the process)</option>
          {stepIds.filter((s) => s !== id).map((s) => (<option key={s} value={s}>{s}</option>))}
        </select>
      </Field>
      <TransitionsEditor step={step} stepIds={stepIds.filter((s) => s !== id)} onChange={(transitions) => set({ transitions })} />

      <SkipWhenField value={step.skipWhen ?? ''} onChange={(v) => set({ skipWhen: v || undefined })} />
    </div>
  )
}

// --- data-object panel ---------------------------------------------------------------------------

function DataObjectPanel({ schema, onChange }: { schema: DataVar[]; onChange: (s: DataVar[]) => void }) {
  return (
    <fieldset className="op-pd-fieldset op-pd-dataobj">
      <legend>Data object</legend>
      {schema.map((v, i) => (
        <div key={i} className="op-pd-dataobj-row">
          <input className="op-pd-dataobj-name" value={v.name} placeholder="name" onChange={(e) => onChange(schema.map((x, j) => (j === i ? { ...x, name: e.target.value.replace(/[^A-Za-z0-9_]/g, '') } : x)))} />
          <select className="op-pd-dataobj-type" value={v.type} onChange={(e) => onChange(schema.map((x, j) => (j === i ? { ...x, type: e.target.value as VarType } : x)))}>
            {VAR_TYPES.map((t) => (<option key={t} value={t}>{t}</option>))}
          </select>
          <button type="button" className="btn btn-ghost btn-sm op-pd-dataobj-del" onClick={() => onChange(schema.filter((_, j) => j !== i))}>✕</button>
        </div>
      ))}
      <button type="button" className="btn btn-ghost btn-sm" onClick={() => onChange([...schema, { name: `var${schema.length + 1}`, type: 'string' }])}>+ Add variable</button>
    </fieldset>
  )
}
