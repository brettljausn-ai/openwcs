// Client for the configurable process-designer backend. Base path is /api/process-designer
// (NOT /api/process — that path is owned by the Flowable BPMN engine). The global authFetch
// interceptor attaches the Bearer token, so plain fetch is fine here.

import type {
  AssistSuggestion,
  AssistVariable,
  Capabilities,
  CheckpointResult,
  DefinitionSummary,
  InstanceSummary,
  ProcessDefinition,
  ProcessInstance,
  ProcessSummary,
  ServerTaskType,
  VerifyKind,
  VerifyResult,
} from './model'

const BASE = '/api/process-designer'

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.text().catch(() => '')
    const err = new Error(humanError(body, res.status, res.statusText)) as Error & { httpStatus?: number }
    err.httpStatus = res.status
    throw err
  }
  return (await res.json()) as T
}

// Turn a server error body into a short, readable message. Spring returns a JSON envelope
// ({timestamp, status, error, message, path}); we surface its message/error, never the raw JSON.
// A 5xx with no useful message becomes a generic "server error" rather than a wall of text.
function humanError(body: string, status: number, statusText: string): string {
  let msg = ''
  try {
    const j = JSON.parse(body) as { message?: string; error?: string; detail?: string }
    msg = j.message || j.detail || j.error || ''
  } catch {
    // not JSON: a short plain-text body is usable as-is, a long one is not
    msg = body.length > 0 && body.length <= 200 ? body : ''
  }
  if (!msg) msg = status >= 500 ? `Server error (${status})` : statusText || `Request failed (${status})`
  return msg
}

// --- Operator menu + runtime ---------------------------------------------------------------------

/** Active processes for the operator menu + designer list. */
export async function listProcesses(): Promise<ProcessSummary[]> {
  return json(await fetch(`${BASE}/processes`))
}

/** The full active definition for a key (runtime fetch; cached by the SW). */
export async function getActiveDef(key: string): Promise<ProcessDefinition> {
  return json(await fetch(`${BASE}/defs/${encodeURIComponent(key)}/active`))
}

/** Start an instance (requires connectivity). */
export async function startInstance(processKey: string, warehouseId: string): Promise<ProcessInstance> {
  return json(
    await fetch(`${BASE}/instances`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ processKey, warehouseId }),
    }),
  )
}

/** Run a task step server-side (a checkpoint). Returns updated data + the next step. */
export async function checkpoint(
  instanceId: string,
  stepId: string,
  data: Record<string, unknown>,
): Promise<CheckpointResult> {
  return json(
    await fetch(`${BASE}/instances/${encodeURIComponent(instanceId)}/checkpoint`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ stepId, data }),
    }),
  )
}

/** Resume a running instance (device swap / reload). */
export async function getInstance(instanceId: string): Promise<ProcessInstance> {
  return json(await fetch(`${BASE}/instances/${encodeURIComponent(instanceId)}`))
}

// --- Designer -------------------------------------------------------------------------------------

export async function listDefs(status?: string): Promise<ProcessDefinition[]> {
  const q = status ? `?status=${encodeURIComponent(status)}` : ''
  return json(await fetch(`${BASE}/defs${q}`))
}

export async function getDef(key: string, version: number): Promise<ProcessDefinition> {
  return json(await fetch(`${BASE}/defs/${encodeURIComponent(key)}/${version}`))
}

export interface CreateDefReq {
  processKey: string
  title: string
  icon?: string
  dataSchema?: ProcessDefinition['dataSchema']
  steps?: ProcessDefinition['steps']
  start?: string
}

export async function createDef(req: CreateDefReq): Promise<ProcessDefinition> {
  return json(
    await fetch(`${BASE}/defs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    }),
  )
}

export async function updateDef(
  key: string,
  version: number,
  def: ProcessDefinition,
): Promise<ProcessDefinition> {
  return json(
    await fetch(`${BASE}/defs/${encodeURIComponent(key)}/${version}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(def),
    }),
  )
}

export async function publishDef(key: string, version: number): Promise<ProcessDefinition> {
  return json(
    await fetch(`${BASE}/defs/${encodeURIComponent(key)}/${version}/publish`, { method: 'POST' }),
  )
}

export async function archiveDef(key: string, version: number): Promise<ProcessDefinition> {
  return json(
    await fetch(`${BASE}/defs/${encodeURIComponent(key)}/${version}/archive`, { method: 'POST' }),
  )
}

// --- Phase 2: duplicate / import / export -------------------------------------------------------

/** Clone {key}/{version} into a NEW DRAFT (e.g. iterate from a published version). */
export async function duplicateDef(key: string, version: number): Promise<DefinitionSummary> {
  return json(
    await fetch(`${BASE}/defs/${encodeURIComponent(key)}/${version}/duplicate`, { method: 'POST' }),
  )
}

/** Import a full definition JSON as a new DRAFT. 422 = model invalid, 400 = missing key/title. */
export async function importDef(def: unknown): Promise<DefinitionSummary> {
  return json(
    await fetch(`${BASE}/defs/import`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(def),
    }),
  )
}

/** The full importable definition JSON for a version (used for the EXPORT download). */
export async function exportDef(key: string, version: number): Promise<ProcessDefinition> {
  return getDef(key, version)
}

// --- Phase 2: server task catalog ---------------------------------------------------------------

/** The REAL server task catalog (drives the designer task picker + input/output mapping). */
export async function listTasks(): Promise<ServerTaskType[]> {
  return json(await fetch(`${BASE}/tasks`))
}

// --- Phase 3: capabilities + AI task-assist -----------------------------------------------------

/** Server feature flags (scripting / AI assist / can-author-script). Gates the Phase 3 UI. */
export async function getCapabilities(): Promise<Capabilities> {
  return json(await fetch(`${BASE}/capabilities`))
}

/** AI "describe the task" assist. Maps a description (+ the data-object schema) to a curated task
 *  type, a drafted script, or "none". Throws with httpStatus 503 when AI is not configured. */
export async function assistTask(
  description: string,
  variables: AssistVariable[],
): Promise<AssistSuggestion> {
  return json(
    await fetch(`${BASE}/assist/task`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ description, variables }),
    }),
  )
}

// --- Verify: resolve a scanned value against master-data ----------------------------------------

/** Resolve a scanned `code` of `kind` against master-data for the current warehouse. Read-only;
 *  requires connectivity. The server may return 502 on a downstream error (treat as not-verified).
 *  On success `found` says whether it exists; the resolved fields (id/code/name/uomCode/
 *  schemaCategory) can be written into data-object variables by the screen's verify block. */
export async function verifyCode(
  warehouseId: string,
  kind: VerifyKind,
  code: string,
): Promise<VerifyResult> {
  return json(
    await fetch(`${BASE}/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ warehouseId, kind, code }),
    }),
  )
}

// --- Phase 2: instance monitoring ---------------------------------------------------------------

export interface InstanceQuery {
  processKey?: string
  status?: string
  warehouseId?: string
  limit?: number
}

/** Instances newest-first, optionally filtered (read-only monitoring screen). */
export async function listInstances(q: InstanceQuery = {}): Promise<InstanceSummary[]> {
  const params = new URLSearchParams()
  if (q.processKey) params.set('processKey', q.processKey)
  if (q.status) params.set('status', q.status)
  if (q.warehouseId) params.set('warehouseId', q.warehouseId)
  if (q.limit != null) params.set('limit', String(q.limit))
  const qs = params.toString()
  return json(await fetch(`${BASE}/instances${qs ? `?${qs}` : ''}`))
}
