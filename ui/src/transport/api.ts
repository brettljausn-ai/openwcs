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
