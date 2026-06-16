// The configurable process-designer model (spec §3). Mirrors the backend definition JSON exactly.
//
// IMPORTANT base path: this feature lives at /api/process-designer (NOT /api/process — the Flowable
// BPMN engine owns /api/process). See process-designer/api.ts.
//
// A definition is a step map + transitions (deliberately NOT BPMN): mostly-linear handheld flows
// with simple Yes/No + choice branching. A running instance carries a typed "data object" that
// screens write to (config.writeTo), placeholders ({{var}}) and conditions (when) read from, and
// task steps read/write.

/** The six handheld screen types (spec §4) plus the work-doing task step. */
export type ScreenType =
  | 'textInput'
  | 'numberInput'
  | 'dateInput'
  | 'acknowledge'
  | 'questionYesNo'
  | 'questionChoice'

/** A data-object variable's declared type. Primitives + domain types resolved to codes/labels. */
export type VarType = 'string' | 'number' | 'boolean' | 'date' | 'sku' | 'location' | 'hu'

export interface DataVar {
  name: string
  type: VarType
  /** Optional human label shown in the designer picker (not required by the runtime). */
  label?: string
}

/** Per-screen validation. Which fields apply depends on the screen type (spec §4). */
export interface Validation {
  required?: boolean
  /** textInput: a JS regex source string the value must fully match. */
  regex?: string
  /** textInput. */
  maxLength?: number
  /** number: bounds. */
  min?: number
  max?: number
  /** numberInput: only whole numbers. */
  integerOnly?: boolean
  /** text/number: the captured value must equal this {{var}} (e.g. confirm scanned == expected). */
  mustEqual?: string
}

export interface ChoiceOption {
  key: string
  label: string
}

/** The kinds of value a Verify block can resolve against master-data. Mirrors the server's
 *  capabilities.verifyKinds; the picker is populated from the live list, this is only the type.
 *  `skuScan` resolves a scan as either a product BARCODE (UOM pinned, no prompt) or a SKU CODE (when
 *  the SKU has several UOMs the operator picks one at runtime). */
export type VerifyKind = 'barcode' | 'sku' | 'location' | 'skuScan'

/** The resolved fields a successful /verify returns and that a Verify block may write into a
 *  data-object variable (so a later task that needs the UUID gets it from `id`, etc.). */
export const VERIFY_FIELDS = ['id', 'code', 'name', 'uomCode', 'schemaCategory'] as const
export type VerifyField = (typeof VERIFY_FIELDS)[number]

/** What to do when /verify reports the scanned value does not exist (or the call errors). */
export interface VerifyOnNotFound {
  /** "reprompt" = clear + refocus the input and ask again; "goto" = jump to `step`. */
  mode: 'reprompt' | 'goto'
  /** Target step id when mode === 'goto'. */
  step?: string
}

/** First-class "Verify" attached to a text/number input screen (spec: resolve the scan, branch on
 *  not-found, write resolved ids). On submit the runtime POSTs /verify {warehouseId, kind, code};
 *  on `found` it merges the `write` mappings into the data object and continues; otherwise it
 *  applies `onNotFound`. */
export interface VerifyConfig {
  kind: VerifyKind
  /** Map each resolved field (id/code/name/uomCode/schemaCategory) to a data-object variable to
   *  store it into. Only mapped fields are written. This is how the resolved UUID is captured. */
  write?: Partial<Record<VerifyField, string>>
  onNotFound: VerifyOnNotFound
}

/** One unit of measure a scanned SKU can be booked in (skuScan). */
export interface VerifyUom {
  code: string
  /** True when this is the SKU's base unit. */
  baseUnit?: boolean
}

/** The /verify response (POST /api/process-designer/verify). Read-only; needs connectivity. */
export interface VerifyResult {
  found: boolean
  /** Multiple master-data matches resolved the same code; the runtime still proceeds. */
  ambiguous?: boolean
  id: string | null
  code: string | null
  name: string | null
  uomCode: string | null
  schemaCategory: string | null
  detail: Record<string, unknown>
  /** skuScan: how the scan resolved — pinned via a product barcode, or via a SKU code. */
  matchedAs?: 'barcode' | 'sku' | null
  /** skuScan: the SKU's available units of measure (drives the runtime UOM picker). */
  uoms?: VerifyUom[]
  /** skuScan: true when the SKU has >1 UOM and the operator must pick one before continuing. */
  needsUomChoice?: boolean
}

/** A screen step's config (the bit a designer edits). Every screen has header/detail + writeTo. */
export interface ScreenConfig {
  /** Big prompt; supports {{placeholder}}. */
  header?: string
  /** Smaller supporting text; supports {{placeholder}}. */
  detail?: string
  /** The data-object variable the captured value is written to. */
  writeTo?: string
  /** True = value captured from a keyboard-wedge scan rather than typed. */
  scanBinding?: boolean
  validation?: Validation
  /** text/number input: verify the scanned value EXISTS against master-data on submit, write the
   *  resolved ids into variables, and branch when it is not found. */
  verify?: VerifyConfig
  /** acknowledge: the continue button label + an optional required checkbox. */
  confirmLabel?: string
  requireCheckbox?: boolean
  checkboxLabel?: string
  /** questionChoice: the configurable answers. */
  options?: ChoiceOption[]
}

/** A guarded edge: when the `when` expression (spec §6 grammar) is true, go `to` that step. */
export interface Transition {
  when: string
  to: string
}

export interface ScreenStep {
  type: 'screen'
  screen: ScreenType
  config: ScreenConfig
  /** Default next step when no transition matches. Absent + no transition match = instance ends. */
  next?: string
  /** Optional guarded edges; first matching `when` wins, evaluated before `next`. */
  transitions?: Transition[]
  /** When this condition (same grammar as transition `when`) is true at runtime, the step is skipped
   *  (the runtime advances past it without rendering). Phase 2 conditional-skip. */
  skipWhen?: string
}

/** The built-in task type id for the sandboxed-script escape hatch (spec §7.2). A task step whose
 *  `task` equals this carries its snippet + declared outputs in `config` (NOT in input/output). */
export const SCRIPT_TASK_TYPE = 'script'

/** One declared output of a script step (its field on the returned object → a data-object var). */
export interface ScriptOutput {
  name: string
}

/** Config for a sandboxed-script task step (spec §7.2). The snippet runs server-side with a
 *  read-only `data` (the process data object) in scope and returns/assigns an object whose fields
 *  become the declared `outputs`. No network/host/filesystem; CPU/time limited (enforced server-side). */
export interface ScriptConfig {
  script: string
  outputs: ScriptOutput[]
}

/** A task step: server-side work via the curated task library (spec §7.1), OR the sandboxed-script
 *  escape hatch (spec §7.2) when `task === SCRIPT_TASK_TYPE` (then `config` carries the snippet).
 *  Runs as a checkpoint. */
export interface TaskStep {
  type: 'task'
  /** Curated task type id, e.g. "slotting.putaway", or SCRIPT_TASK_TYPE for a sandboxed snippet. */
  task: string
  /** Maps data-object variable names to the task's named inputs. */
  input?: Record<string, string>
  /** Maps the task's named outputs back to data-object variable names. */
  output?: Record<string, string>
  /** Script-step payload (only when task === SCRIPT_TASK_TYPE): the JS snippet + declared outputs. */
  config?: ScriptConfig
  next?: string
  transitions?: Transition[]
  /** Conditional-skip (same grammar as transition `when`); true = the step is skipped at runtime. */
  skipWhen?: string
}

/** True when the step is the sandboxed-script escape hatch. NOTE: returns a plain boolean (NOT a
 *  type guard) so it never narrows the union away — a script step IS a TaskStep, not a subtype. */
export function isScriptStep(step: Step): boolean {
  return step.type === 'task' && step.task === SCRIPT_TASK_TYPE
}

export type Step = ScreenStep | TaskStep

export interface ProcessDefinition {
  processKey: string
  version?: number
  status?: 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
  title: string
  icon?: string
  dataSchema: DataVar[]
  /** The id of the first step. */
  start: string
  /** Step id -> step. */
  steps: Record<string, Step>
}

/** Operator-menu tile + designer list row (GET /processes). */
export interface ProcessSummary {
  processKey: string
  activeVersion: number
  title: string
  icon?: string
}

/** A running instance (POST /instances, GET /instances/{id}). */
export interface ProcessInstance {
  instanceId: string
  def: ProcessDefinition
  data: Record<string, unknown>
  currentStep: string
}

/** Checkpoint response (POST /instances/{id}/checkpoint). */
export interface CheckpointResult {
  data: Record<string, unknown>
  currentStep: string
  done: boolean
}

/** A task input as advertised by the server task catalog (GET /tasks). */
export interface TaskInputDef {
  name: string
  required?: boolean
}

/** A task output as advertised by the server task catalog. */
export interface TaskOutputDef {
  name: string
}

/** The curated task library shown in the designer's task picker. The authoritative source is the
 *  server catalog (GET /api/process-designer/tasks → [{ type, label, inputs:[{name,required}],
 *  outputs:[{name}] }]); the constant below is only a graceful fallback when that fetch fails.
 *
 *  `id` mirrors the server `type`; inputs/outputs are normalised to {name,required}/{name}. */
export interface TaskTypeDef {
  id: string
  label: string
  inputs: TaskInputDef[]
  outputs: TaskOutputDef[]
  description?: string
}

/** Wire shape of one entry from GET /api/process-designer/tasks. */
export interface ServerTaskType {
  type: string
  label?: string
  description?: string
  inputs?: TaskInputDef[]
  outputs?: TaskOutputDef[]
}

/** Normalise a server task entry to the client TaskTypeDef the designer UI consumes. */
export function normalizeServerTask(s: ServerTaskType): TaskTypeDef {
  return {
    id: s.type,
    label: s.label || s.type,
    description: s.description,
    inputs: (s.inputs ?? []).map((i) => ({ name: i.name, required: i.required })),
    outputs: (s.outputs ?? []).map((o) => ({ name: o.name })),
  }
}

/** Fallback catalog used only when GET /tasks is unreachable; mirrors spec §7.1. */
export const TASK_LIBRARY: TaskTypeDef[] = [
  { id: 'slotting.putaway', label: 'Putaway (slotting)', inputs: [{ name: 'sku', required: true }, { name: 'qty', required: true }, { name: 'hu' }], outputs: [{ name: 'location' }], description: 'Score + dispatch a putaway move.' },
  { id: 'inventory.move', label: 'Move stock', inputs: [{ name: 'hu', required: true }, { name: 'from' }, { name: 'to', required: true }], outputs: [{ name: 'moveId' }], description: 'Create an inventory move.' },
  { id: 'picking.confirm', label: 'Confirm pick', inputs: [{ name: 'lineId', required: true }, { name: 'qty', required: true }, { name: 'short' }], outputs: [], description: 'Confirm a pick task.' },
  { id: 'counting.capture', label: 'Capture count', inputs: [{ name: 'location', required: true }, { name: 'sku', required: true }, { name: 'qty', required: true }], outputs: [{ name: 'variance' }], description: 'Capture a stock count.' },
  { id: 'inventory.lookup', label: 'Lookup inventory', inputs: [{ name: 'sku' }, { name: 'location' }], outputs: [{ name: 'qty' }, { name: 'hu' }], description: 'Read inventory into a variable.' },
  { id: 'host.confirm', label: 'Host confirm', inputs: [{ name: 'ref', required: true }], outputs: [], description: 'Confirm to the host API.' },
  { id: 'txlog.post', label: 'Post stock transaction', inputs: [{ name: 'sku', required: true }, { name: 'qty', required: true }, { name: 'location' }], outputs: [{ name: 'txId' }], description: 'Append a stock transaction.' },
]

export function taskTypeById(id: string, catalog: TaskTypeDef[] = TASK_LIBRARY): TaskTypeDef | undefined {
  return catalog.find((t) => t.id === id)
}

/** A running/finished instance row (GET /api/process-designer/instances). */
export interface InstanceSummary {
  instanceId: string
  processKey: string
  defVersion: number
  status: string
  currentStep?: string | null
  startedBy?: string | null
  warehouseId?: string | null
  startedAt?: string | null
  updatedAt?: string | null
}

/** A definition list row (GET /defs, duplicate/import responses). */
export interface DefinitionSummary {
  processKey: string
  version: number
  status: 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
  title?: string
  icon?: string
}

/** Phase 3: server-advertised capabilities (GET /capabilities). Gates the new UI; degrade gracefully
 *  (feature hidden) when a field is false or the fetch fails. */
export interface Capabilities {
  /** The sandboxed-script runtime is available server-side. */
  scriptingEnabled: boolean
  /** The AI "describe the task" assist is configured (an Anthropic key is present). */
  aiAssistEnabled: boolean
  /** The caller is allowed to author script steps (RBAC). */
  canAuthorScript: boolean
  /** The verify kinds the server can resolve (e.g. ["barcode","sku","location"]). Empty/absent =
   *  the Verify feature is hidden in the designer. */
  verifyKinds: string[]
}

/** Conservative default when /capabilities is unreachable: everything off (features simply hidden). */
export const DISABLED_CAPABILITIES: Capabilities = {
  scriptingEnabled: false,
  aiAssistEnabled: false,
  canAuthorScript: false,
  verifyKinds: [],
}

/** Phase 3: a data-object variable as sent to POST /assist/task ({ name, type }). */
export interface AssistVariable {
  name: string
  type: VarType
}

/** Phase 3: the AI task-assist suggestion (POST /assist/task response). */
export interface AssistSuggestion {
  /** "curated" = map to a curated task type; "script" = a drafted snippet; "none" = nothing fits. */
  kind: 'curated' | 'script' | 'none'
  /** curated: the chosen curated task type id. */
  taskType?: string
  /** curated: proposed input mappings (inputName → data-object var name or literal). */
  inputs?: Record<string, string>
  /** curated: proposed output mappings (outputName → data-object var name). */
  outputs?: Record<string, string>
  /** script: the drafted JS snippet (for review, never auto-deployed). */
  script?: string
  /** Human-readable explanation of the suggestion (always present). */
  rationale?: string
  /** 0..1 confidence the model reports. */
  confidence?: number
}

export const SCREEN_TYPE_ICONS: Record<ScreenType | 'task', string> = {
  textInput: '⌨',
  numberInput: '#',
  dateInput: '▦',
  acknowledge: '✓',
  questionYesNo: '⤙',
  questionChoice: '☰',
  task: '⚙',
}

export const SCREEN_TYPE_LABELS: Record<ScreenType | 'task', string> = {
  textInput: 'Text input',
  numberInput: 'Number input',
  dateInput: 'Date input',
  acknowledge: 'Acknowledge',
  questionYesNo: 'Question (Yes/No)',
  questionChoice: 'Question (choices)',
  task: 'Task',
}

export function isScreenStep(step: Step): step is ScreenStep {
  return step.type === 'screen'
}
export function isTaskStep(step: Step): step is TaskStep {
  return step.type === 'task'
}
