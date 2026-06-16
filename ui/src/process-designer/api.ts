// Client for the configurable process-designer backend. Base path is /api/process-designer
// (NOT /api/process — that path is owned by the Flowable BPMN engine). The global authFetch
// interceptor attaches the Bearer token, so plain fetch is fine here.

import type {
  CheckpointResult,
  ProcessDefinition,
  ProcessInstance,
  ProcessSummary,
} from './model'

const BASE = '/api/process-designer'

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.text().catch(() => '')
    const err = new Error(body || `${res.status} ${res.statusText}`) as Error & { httpStatus?: number }
    err.httpStatus = res.status
    throw err
  }
  return (await res.json()) as T
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
