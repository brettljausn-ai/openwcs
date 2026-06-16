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
}

/** A task step: server-side work via the curated task library (spec §7.1). Runs as a checkpoint. */
export interface TaskStep {
  type: 'task'
  /** Curated task type id, e.g. "slotting.putaway". */
  task: string
  /** Maps data-object variable names to the task's named inputs. */
  input?: Record<string, string>
  /** Maps the task's named outputs back to data-object variable names. */
  output?: Record<string, string>
  next?: string
  transitions?: Transition[]
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

/** The curated task library shown in the designer's task picker. Mirrors spec §7.1. Display-only on
 *  the client; the server holds the authoritative registry and runs them. */
export interface TaskTypeDef {
  id: string
  label: string
  /** Named inputs the task reads (designer maps data vars -> these). */
  inputs: string[]
  /** Named outputs the task writes (designer maps these -> data vars). */
  outputs: string[]
  description: string
}

export const TASK_LIBRARY: TaskTypeDef[] = [
  { id: 'slotting.putaway', label: 'Putaway (slotting)', inputs: ['sku', 'qty', 'hu'], outputs: ['location'], description: 'Score + dispatch a putaway move.' },
  { id: 'inventory.move', label: 'Move stock', inputs: ['hu', 'from', 'to'], outputs: ['moveId'], description: 'Create an inventory move.' },
  { id: 'picking.confirm', label: 'Confirm pick', inputs: ['lineId', 'qty', 'short'], outputs: [], description: 'Confirm a pick task.' },
  { id: 'counting.capture', label: 'Capture count', inputs: ['location', 'sku', 'qty'], outputs: ['variance'], description: 'Capture a stock count.' },
  { id: 'inventory.lookup', label: 'Lookup inventory', inputs: ['sku', 'location'], outputs: ['qty', 'hu'], description: 'Read inventory into a variable.' },
  { id: 'host.confirm', label: 'Host confirm', inputs: ['ref'], outputs: [], description: 'Confirm to the host API.' },
  { id: 'txlog.post', label: 'Post stock transaction', inputs: ['sku', 'qty', 'location'], outputs: ['txId'], description: 'Append a stock transaction.' },
]

export function taskTypeById(id: string): TaskTypeDef | undefined {
  return TASK_LIBRARY.find((t) => t.id === id)
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
