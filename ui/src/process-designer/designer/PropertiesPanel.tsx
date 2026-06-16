// Right pane of the designer: edit the selected step's properties, and (below) the data-object
// schema. Every change calls back up so the centre live-preview updates instantly. For screen steps:
// header/detail with a placeholder picker, writeTo dropdown, validation builder, scan-binding toggle,
// options (choice), confirm/checkbox (acknowledge), and the transition editor (questions/branching).
// For task steps: a task-type picker + input/output variable mapping. The data-object panel
// declares/renames typed variables.
//
// All inputs are controlled; no hooks beyond useState for the placeholder-target field, declared at
// the top (Rules of Hooks).

import { useState } from 'react'
import { useT } from '../../i18n/useT'
import { validateCondition } from '../condition'
import {
  taskTypeById,
  type ChoiceOption,
  type DataVar,
  type ProcessDefinition,
  type ScreenStep,
  type Step,
  type TaskStep,
  type TaskTypeDef,
  type Transition,
  type VarType,
} from '../model'

interface Props {
  def: ProcessDefinition
  selectedId: string | null
  /** The live server task catalog (falls back to the static library); drives the task picker. */
  tasks: TaskTypeDef[]
  onChangeStep: (id: string, step: Step) => void
  onChangeSchema: (schema: DataVar[]) => void
  /** Rename a step id (updates references). */
  onRenameStep: (oldId: string, newId: string) => void
}

const VAR_TYPES: VarType[] = ['string', 'number', 'boolean', 'date', 'sku', 'location', 'hu']

export default function PropertiesPanel({ def, selectedId, tasks, onChangeStep, onChangeSchema, onRenameStep }: Props) {
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
            onChange={(s) => onChangeStep(selectedId, s)}
            onRename={(n) => onRenameStep(selectedId, n)}
          />
        ) : (
          <TaskProps
            def={def}
            id={selectedId}
            step={step}
            stepIds={stepIds}
            tasks={tasks}
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

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="op-pd-field">
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
    <Field label={label}>
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
  onChange,
  onRename,
}: {
  def: ProcessDefinition
  id: string
  step: ScreenStep
  stepIds: string[]
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
          <select value={cfg.writeTo ?? ''} onChange={(e) => setCfg({ writeTo: e.target.value || undefined })}>
            <option value="">(none)</option>
            {def.dataSchema.map((v) => (
              <option key={v.name} value={v.name}>{v.name} ({v.type})</option>
            ))}
          </select>
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
          <select value={cfg.writeTo ?? ''} onChange={(e) => setCfg({ writeTo: e.target.value || undefined })}>
            <option value="">(none)</option>
            {def.dataSchema.map((v) => (<option key={v.name} value={v.name}>{v.name} ({v.type})</option>))}
          </select>
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
    <fieldset className="op-pd-fieldset">
      <legend>{t('skipWhen', 'Skip this step when…')}</legend>
      <p className="muted" style={{ fontSize: '.75rem', margin: '0 0 .4rem' }}>
        {t('skipWhenHint', 'A condition over the data object (e.g. damaged == false). When true at runtime the step is skipped.')}
      </p>
      <input placeholder="e.g. qty == 0" value={value} onChange={(e) => onChange(e.target.value)} />
      {err && <span className="op-pd-issue-err" style={{ fontSize: '.75rem' }}>⚠ {err}</span>}
    </fieldset>
  )
}

function ChoiceOptionsEditor({ options, onChange }: { options: ChoiceOption[]; onChange: (o: ChoiceOption[]) => void }) {
  return (
    <fieldset className="op-pd-fieldset">
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
    <fieldset className="op-pd-fieldset">
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

// --- task step properties ------------------------------------------------------------------------

function TaskProps({
  def,
  id,
  step,
  stepIds,
  tasks,
  onChange,
  onRename,
}: {
  def: ProcessDefinition
  id: string
  step: TaskStep
  stepIds: string[]
  tasks: TaskTypeDef[]
  onChange: (s: TaskStep) => void
  onRename: (n: string) => void
}) {
  // The selected task may not be in the live catalog (e.g. a def authored against a newer server);
  // resolve from the catalog, else show the raw id so it is still editable.
  const taskDef = taskTypeById(step.task, tasks)
  const set = (patch: Partial<TaskStep>) => onChange({ ...step, ...patch })

  return (
    <div className="op-pd-props-body">
      <h3>Task</h3>
      <StepIdField id={id} onRename={onRename} />

      <Field label="Task type">
        <select value={step.task} onChange={(e) => set({ task: e.target.value, input: {}, output: {} })}>
          {/* Keep the current value selectable even if the catalog no longer lists it. */}
          {!taskDef && <option value={step.task}>{step.task}</option>}
          {tasks.map((tt) => (<option key={tt.id} value={tt.id}>{tt.label}</option>))}
        </select>
      </Field>
      {taskDef?.description && <p className="muted" style={{ fontSize: '.78rem', margin: '0 0 .5rem' }}>{taskDef.description}</p>}

      {taskDef && taskDef.inputs.length > 0 && (
        <fieldset className="op-pd-fieldset">
          <legend>Inputs (task ← variable)</legend>
          {taskDef.inputs.map((inp) => (
            <Field key={inp.name} label={inp.required ? `${inp.name} *` : inp.name}>
              <select value={step.input?.[inp.name] ?? ''} onChange={(e) => set({ input: { ...step.input, [inp.name]: e.target.value } })}>
                <option value="">(none)</option>
                {def.dataSchema.map((v) => (<option key={v.name} value={v.name}>{v.name}</option>))}
              </select>
            </Field>
          ))}
        </fieldset>
      )}

      {taskDef && taskDef.outputs.length > 0 && (
        <fieldset className="op-pd-fieldset">
          <legend>Outputs (task → variable)</legend>
          {taskDef.outputs.map((out) => (
            <Field key={out.name} label={out.name}>
              <select value={step.output?.[out.name] ?? ''} onChange={(e) => set({ output: { ...step.output, [out.name]: e.target.value } })}>
                <option value="">(none)</option>
                {def.dataSchema.map((v) => (<option key={v.name} value={v.name}>{v.name}</option>))}
              </select>
            </Field>
          ))}
        </fieldset>
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
        <div key={i} style={{ display: 'flex', gap: '.4rem', marginBottom: '.4rem' }}>
          <input value={v.name} placeholder="name" style={{ flex: 1 }} onChange={(e) => onChange(schema.map((x, j) => (j === i ? { ...x, name: e.target.value.replace(/[^A-Za-z0-9_]/g, '') } : x)))} />
          <select value={v.type} onChange={(e) => onChange(schema.map((x, j) => (j === i ? { ...x, type: e.target.value as VarType } : x)))}>
            {VAR_TYPES.map((t) => (<option key={t} value={t}>{t}</option>))}
          </select>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => onChange(schema.filter((_, j) => j !== i))}>✕</button>
        </div>
      ))}
      <button type="button" className="btn btn-ghost btn-sm" onClick={() => onChange([...schema, { name: `var${schema.length + 1}`, type: 'string' }])}>+ Add variable</button>
    </fieldset>
  )
}
