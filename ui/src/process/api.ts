// Client for the process-engine API (via the gateway at /api/process).

export interface ProcessDefinition {
  id: string
  key: string
  name?: string | null
  version: number
}

export interface Instance {
  id: string
  processDefinitionKey: string
  businessKey?: string | null
  ended: boolean
}

export interface Task {
  id: string
  name?: string | null
  assignee?: string | null
  processInstanceId: string
}

export async function deployDefinition(name: string, bpmnXml: string): Promise<ProcessDefinition[]> {
  const res = await fetch(`/api/process/definitions?name=${encodeURIComponent(name)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/xml' },
    body: bpmnXml,
  })
  if (!res.ok) throw new Error(`Deploy failed: ${res.status}`)
  return (await res.json()) as ProcessDefinition[]
}

export async function listDefinitions(): Promise<ProcessDefinition[]> {
  const res = await fetch('/api/process/definitions')
  if (!res.ok) throw new Error(`List failed: ${res.status}`)
  return (await res.json()) as ProcessDefinition[]
}

export async function startInstance(
  req: { processKey: string; businessKey?: string; variables?: Record<string, unknown> },
): Promise<Instance> {
  const res = await fetch('/api/process/instances', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  })
  if (!res.ok) throw new Error(`Start failed: ${res.status}`)
  return (await res.json()) as Instance
}

export async function listTasks(processInstanceId: string): Promise<Task[]> {
  const res = await fetch(`/api/process/tasks?processInstanceId=${encodeURIComponent(processInstanceId)}`)
  if (!res.ok) throw new Error(`Tasks failed: ${res.status}`)
  return (await res.json()) as Task[]
}

export async function completeTask(id: string, variables?: Record<string, unknown>): Promise<void> {
  const res = await fetch(`/api/process/tasks/${encodeURIComponent(id)}/complete`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(variables ?? {}),
  })
  if (!res.ok) throw new Error(`Complete failed: ${res.status}`)
}
