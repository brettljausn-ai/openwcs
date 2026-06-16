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
  SCRIPT_TASK_TYPE,
  VERIFY_FIELDS,
  isScriptStep,
  taskTypeById,
  type Capabilities,
  type ChoiceOption,
  type DataVar,
  type ProcessDefinition,
  type ScreenStep,
  type ScriptConfig,
  type Step,
  type TaskStep,
  type TaskTypeDef,
  type Transition,
  type VarType,
  type VerifyConfig,
  type VerifyField,
  type VerifyKind,
} from '../model'
import ScriptEditor from './ScriptEditor'
import TaskAssist from './TaskAssist'
import VarCombobox from './VarCombobox'

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
          <TaskProps
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

/** First-class "Verify" editor for a text/number input screen: toggle it on, pick the kind, map the
 *  resolved fields into data-object variables (this is how the resolved UUID is captured), and choose
 *  what happens when the scan is not found (re-prompt or go to a step). Server is authoritative. */
function VerifyEditor({
  verify,
  kinds,
  vars,
  stepIds,
  onChange,
}: {
  verify?: VerifyConfig
  kinds: VerifyKind[]
  vars: DataVar[]
  stepIds: string[]
  onChange: (v: VerifyConfig | undefined) => void
}) {
  const t = useT('processDesign')
  const enabled = !!verify
  const v: VerifyConfig = verify ?? { kind: kinds[0] ?? 'barcode', onNotFound: { mode: 'reprompt' } }
  const setV = (patch: Partial<VerifyConfig>) => onChange({ ...v, ...patch })
  const setWrite = (field: VerifyField, varName: string) => {
    const write = { ...(v.write ?? {}) }
    if (varName) write[field] = varName
    else delete write[field]
    onChange({ ...v, write })
  }

  return (
    <fieldset className="op-pd-fieldset">
      <legend>{t('verify', 'Verify')}</legend>
      <label className="op-pd-toggle">
        <input
          type="checkbox"
          checked={enabled}
          onChange={(e) => onChange(e.target.checked ? v : undefined)}
        />
        {t('verifyEnable', 'Verify the scanned value exists')}
      </label>
      <p className="muted" style={{ fontSize: '.75rem', margin: '.2rem 0 .4rem' }}>
        {t('verifyHint', 'Verification needs connectivity and runs on submit. Resolved ids can be written into variables for later steps.')}
      </p>

      {enabled && (
        <>
          <Field label={t('verifyKind', 'Resolve as')}>
            <select value={v.kind} onChange={(e) => setV({ kind: e.target.value as VerifyKind })}>
              {kinds.map((k) => (
                <option key={k} value={k}>{t(`verifyKind_${k}`, verifyKindLabel(k))}</option>
              ))}
            </select>
          </Field>

          {v.kind === 'skuScan' && (
            <p className="muted op-pd-wide" style={{ fontSize: '.75rem', margin: '0 0 .2rem' }}>
              {t('verifySkuScanHelp', 'A barcode pins the unit of measure; a SKU code with several units prompts the operator to pick one. Map "uomCode" to a variable to store the chosen/resolved unit.')}
            </p>
          )}

          <div className="op-pd-field">
            <span className="op-pd-field-label">{t('verifyWrite', 'Store resolved values into variables')}</span>
            {vars.length === 0 ? (
              <p className="muted" style={{ fontSize: '.75rem', margin: 0 }}>{t('verifyNoVars', 'Add data-object variables below to store resolved ids.')}</p>
            ) : (
              VERIFY_FIELDS.map((field) => (
                <div key={field} style={{ display: 'flex', gap: '.4rem', alignItems: 'center', marginBottom: '.3rem' }}>
                  <code style={{ flex: '0 0 110px', fontSize: '.8rem' }}>{field}</code>
                  <span aria-hidden="true">→</span>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <VarCombobox
                      value={v.write?.[field] ?? ''}
                      options={vars}
                      placeholder={t('verifyDontStore', '(do not store)')}
                      onChange={(name) => setWrite(field, name)}
                    />
                  </div>
                </div>
              ))
            )}
          </div>

          <Field label={t('verifyOnNotFound', 'When not found')}>
            <select
              value={v.onNotFound.mode}
              onChange={(e) =>
                setV({
                  onNotFound:
                    e.target.value === 'goto'
                      ? { mode: 'goto', step: v.onNotFound.step }
                      : { mode: 'reprompt' },
                })
              }
            >
              <option value="reprompt">{t('verifyReprompt', 'Re-prompt the scan')}</option>
              <option value="goto">{t('verifyGoto', 'Go to step')}</option>
            </select>
          </Field>
          {v.onNotFound.mode === 'goto' && (
            <Field label={t('verifyGotoStep', 'Go to step')}>
              <select
                value={v.onNotFound.step ?? ''}
                onChange={(e) => setV({ onNotFound: { mode: 'goto', step: e.target.value || undefined } })}
              >
                <option value="">{t('verifyPickStep', '(pick a step)')}</option>
                {stepIds.map((s) => (<option key={s} value={s}>{s}</option>))}
              </select>
            </Field>
          )}
        </>
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

// --- task step properties ------------------------------------------------------------------------

function TaskProps({
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
  step: TaskStep
  stepIds: string[]
  tasks: TaskTypeDef[]
  capabilities: Capabilities
  onChange: (s: TaskStep) => void
  onRename: (n: string) => void
}) {
  const t = useT('processDesign')
  // The selected task may not be in the live catalog (e.g. a def authored against a newer server);
  // resolve from the catalog, else show the raw id so it is still editable.
  const taskDef = taskTypeById(step.task, tasks)
  const set = (patch: Partial<TaskStep>) => onChange({ ...step, ...patch })

  // Capability gating (spec §7.2/§7.3): only offer script authoring when the server allows it, and
  // only show the AI assist when it is configured.
  const scriptAllowed = capabilities.scriptingEnabled && capabilities.canAuthorScript
  const isScript = isScriptStep(step)
  // The picker lists every catalog task EXCEPT the built-in "script" type, which only appears when
  // scripting is allowed (otherwise it is simply not an option).
  const pickableTasks = tasks.filter((tt) => tt.id !== SCRIPT_TASK_TYPE || scriptAllowed)

  // Switching task type clears the curated mappings AND any leftover script config (and vice versa).
  const changeTaskType = (next: string) => {
    if (next === SCRIPT_TASK_TYPE) onChange({ ...step, task: next, input: {}, output: {}, config: step.config ?? { script: '', outputs: [] } })
    else onChange({ ...step, task: next, input: {}, output: {}, config: undefined })
  }

  // Apply an AI curated suggestion: switch task type + fill the input/output mappings it proposed.
  const applyCurated = (taskType: string, inputs: Record<string, string>, outputs: Record<string, string>) => {
    onChange({ ...step, task: taskType, input: { ...inputs }, output: { ...outputs }, config: undefined })
  }
  // Insert an AI script draft: make this step a script step carrying the drafted snippet.
  const insertScript = (config: ScriptConfig) => {
    onChange({ ...step, task: SCRIPT_TASK_TYPE, input: {}, output: {}, config })
  }

  return (
    <div className="op-pd-props-body">
      <h3>{isScript ? t('scriptStep', 'Sandboxed script') : t('taskStep', 'Task')}</h3>
      <StepIdField id={id} onRename={onRename} />

      <Field label={t('taskType', 'Task type')}>
        <select value={step.task} onChange={(e) => changeTaskType(e.target.value)}>
          {/* Keep the current value selectable even if the catalog no longer lists it. */}
          {!taskDef && <option value={step.task}>{step.task}</option>}
          {pickableTasks.map((tt) => (<option key={tt.id} value={tt.id}>{tt.label}</option>))}
        </select>
      </Field>
      {!isScript && taskDef?.description && <p className="muted op-pd-wide" style={{ fontSize: '.78rem', margin: '0 0 .5rem' }}>{taskDef.description}</p>}

      {/* Sandboxed-script editor (only reachable when scripting is allowed). */}
      {isScript && scriptAllowed && (
        <div className="op-pd-wide">
          <ScriptEditor config={step.config} onChange={(config) => set({ config })} />
        </div>
      )}
      {/* A script step authored elsewhere but scripting is now disabled: show it read-only-ish note. */}
      {isScript && !scriptAllowed && (
        <p className="muted op-pd-assist-note op-pd-wide">{t('scriptDisabled', 'Scripting is disabled on this server. This script step cannot be edited here.')}</p>
      )}

      {/* Curated input/output mapping (not for script steps). */}
      {!isScript && taskDef && taskDef.inputs.length > 0 && (
        <fieldset className="op-pd-fieldset">
          <legend>Inputs (task ← variable)</legend>
          {taskDef.inputs.map((inp) => (
            <Field key={inp.name} label={inp.required ? `${inp.name} *` : inp.name}>
              <VarCombobox value={step.input?.[inp.name] ?? ''} options={def.dataSchema} onChange={(name) => set({ input: { ...step.input, [inp.name]: name } })} />
            </Field>
          ))}
        </fieldset>
      )}

      {!isScript && taskDef && taskDef.outputs.length > 0 && (
        <fieldset className="op-pd-fieldset">
          <legend>Outputs (task → variable)</legend>
          {taskDef.outputs.map((out) => (
            <Field key={out.name} label={out.name}>
              <VarCombobox value={step.output?.[out.name] ?? ''} options={def.dataSchema} onChange={(name) => set({ output: { ...step.output, [out.name]: name } })} />
            </Field>
          ))}
        </fieldset>
      )}

      {/* AI "describe the task" assist (spec §7.3); only when configured server-side. */}
      {capabilities.aiAssistEnabled && (
        <div className="op-pd-wide">
          <TaskAssist
            schema={def.dataSchema}
            canInsertScript={scriptAllowed}
            onApplyCurated={applyCurated}
            onInsertScript={insertScript}
          />
        </div>
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
