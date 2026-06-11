// Client for the flow-orchestrator device-task API (via the gateway at /api/flow).
// The transport overview lists active and recent device tasks the orchestrator has
// dispatched to equipment adapters (build.md §8), with their lifecycle status.

export interface DeviceTask {
  id: string
  warehouseId: string
  family: string
  equipmentId?: string | null
  command: string
  payload?: Record<string, unknown> | null
  correlationId?: string | null
  status: string
  detail?: string | null
  result?: Record<string, unknown> | null
  actor?: string | null
  createdAt: string
}

export interface TaskFilters {
  warehouseId?: string
  status?: string
  family?: string
  equipmentId?: string
  limit?: number
}

// Lists recent device tasks (newest first) with optional filters. Empty filter
// values are omitted so the backend treats them as "any".
export async function listDeviceTasks(filters: TaskFilters): Promise<DeviceTask[]> {
  const params = new URLSearchParams()
  if (filters.warehouseId?.trim()) params.set('warehouseId', filters.warehouseId.trim())
  if (filters.status?.trim()) params.set('status', filters.status.trim())
  if (filters.family?.trim()) params.set('family', filters.family.trim())
  if (filters.equipmentId?.trim()) params.set('equipmentId', filters.equipmentId.trim())
  params.set('limit', String(filters.limit ?? 200))
  const res = await fetch(`/api/flow/device-tasks?${params.toString()}`)
  if (!res.ok) throw new Error(`Failed to load device tasks: ${res.status}`)
  return (await res.json()) as DeviceTask[]
}

// Fetches every device task that shares a correlation id, oldest-first — the "trace" of one
// logical transport (a process instance can dispatch several tasks under the same correlation).
// Backed by the same list endpoint, which returns the correlation group when `correlationId` is set.
// Kept as the fallback for the click-to-trace dialog when no HU id is resolvable (ADR-0007 3c-1 §6.3).
export async function listTaskTrace(correlationId: string): Promise<DeviceTask[]> {
  const res = await fetch(`/api/flow/device-tasks?correlationId=${encodeURIComponent(correlationId)}`)
  if (!res.ok) throw new Error(`Failed to load transport trace: ${res.status}`)
  return (await res.json()) as DeviceTask[]
}

// One row of an HU's transport-trace timeline (ADR-0007 §3.4, flow `hu_transport_trace`). The
// timeline is append-only: each row is a lifecycle event for the handling unit at a function point
// (REQUESTED → RETRIEVED → INDUCTED → ARRIVED → QUEUED → DONE; 3c-2 adds DIVERT/MERGE/RECIRCULATE).
// Shape mirrors flow's HuTraceView JSON exactly.
export interface HuTraceRow {
  id: string
  huId: string
  huCode?: string | null
  ts: string
  point?: string | null
  event: string
  decision?: string | null
  fromPoint?: string | null
  toPoint?: string | null
  workplaceId?: string | null
  correlationId?: string | null
  taskId?: string | null
  inductionEntryId?: string | null
}

// Reads an HU's transport-trace timeline (ts ASC) from flow — the per-HU lifecycle across the
// induction pipeline, replacing the coarse byCorrelation device-task grouping in the trace dialog
// (ADR-0007 3c-1 §6.3). `warehouseId` is optional; flow scopes by it when given.
export async function listHuTrace(huId: string, warehouseId?: string): Promise<HuTraceRow[]> {
  const params = new URLSearchParams({ huId })
  if (warehouseId?.trim()) params.set('warehouseId', warehouseId.trim())
  const res = await fetch(`/api/flow/hu-trace?${params.toString()}`)
  if (!res.ok) throw new Error(`Failed to load HU trace: ${res.status}`)
  return (await res.json()) as HuTraceRow[]
}
