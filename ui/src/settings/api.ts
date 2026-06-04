// Client for the Settings / Configuration screen. All calls go through the gateway
// (/api/**) and rely on the global Bearer interceptor; system status reads the gateway's
// own actuator (/actuator/health) which is permitted even when edge security is on.

async function ok<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  return (await res.json()) as T
}

const json = { 'Content-Type': 'application/json' }

// ---- master data (selectors) ----
export interface Warehouse {
  id: string
  code: string
  name: string
  status?: string
}

interface PageResponse<T> {
  content: T[]
}

export async function listWarehouses(): Promise<Warehouse[]> {
  const page = await ok<PageResponse<Warehouse>>(await fetch('/api/master-data/warehouses?size=200'))
  return page.content ?? []
}

export interface StorageBlock {
  id: string
  code: string
  storageType: string
  slottingGranularity?: string
  gtp?: boolean
}

export async function listStorageBlocks(warehouseId: string): Promise<StorageBlock[]> {
  return ok(await fetch(`/api/master-data/storage-blocks?warehouseId=${encodeURIComponent(warehouseId)}`))
}

// ---- slotting block policy (per-block put-away scoring) ----
// PUT /api/slotting/block-policies/{blockId} upserts. GET returns 404 when none exists yet.
// Note: velocityHalfLifeDays / abcAShare / abcBShare are returned by GET but the upsert
// endpoint does not persist edits to them, so they are surfaced read-only.
export interface BlockPolicy {
  blockId?: string
  warehouseId: string
  wVelocity: number
  wConsolidation: number
  wRedundancy: number
  wBalance: number
  defaultMaxAislePct: number
  minAislesA: number
  minAislesB: number
  minAislesC: number
  reslotEnabled: boolean
  reslotShiftPct: number
  offpeakCron?: string | null
  velocityHalfLifeDays?: number
  abcAShare?: number
  abcBShare?: number
}

export function defaultBlockPolicy(warehouseId: string): BlockPolicy {
  return {
    warehouseId,
    wVelocity: 1,
    wConsolidation: 1,
    wRedundancy: 1,
    wBalance: 1,
    defaultMaxAislePct: 0.5,
    minAislesA: 2,
    minAislesB: 1,
    minAislesC: 1,
    reslotEnabled: false,
    reslotShiftPct: 0.2,
    offpeakCron: '',
  }
}

/** Returns the saved policy, or null when none exists yet (404). */
export async function getBlockPolicy(blockId: string): Promise<BlockPolicy | null> {
  const res = await fetch(`/api/slotting/block-policies/${encodeURIComponent(blockId)}`)
  if (res.status === 404) return null
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  return (await res.json()) as BlockPolicy
}

export async function saveBlockPolicy(blockId: string, policy: BlockPolicy): Promise<BlockPolicy> {
  return ok(
    await fetch(`/api/slotting/block-policies/${encodeURIComponent(blockId)}`, {
      method: 'PUT',
      headers: json,
      body: JSON.stringify(policy),
    }),
  )
}

// ---- counting schedules (ABC cadence) ----
export interface CountSchedule {
  id?: string
  warehouseId: string
  name: string
  scopeType: string // LOCATION | SKU | ZONE | BLOCK | ABC_CLASS
  scopeRef?: string | null
  abcClass?: string | null // A | B | C (when scopeType = ABC_CLASS)
  countType: string // BLIND | VARIANCE
  cadenceDays: number
  tolerance: number
  lastRunAt?: string | null
  nextDueAt?: string | null
  status?: string
}

export async function listSchedules(warehouseId: string): Promise<CountSchedule[]> {
  return ok(await fetch(`/api/counting/schedules?warehouseId=${encodeURIComponent(warehouseId)}`))
}

export interface CreateSchedule {
  warehouseId: string
  name: string
  scopeType: string
  scopeRef?: string | null
  abcClass?: string | null
  countType?: string
  cadenceDays: number
  tolerance?: number
  nextDueAt?: string | null
}

export async function createSchedule(req: CreateSchedule): Promise<CountSchedule> {
  return ok(
    await fetch('/api/counting/schedules', {
      method: 'POST',
      headers: json,
      body: JSON.stringify(req),
    }),
  )
}

/** Manually run the ABC-cadence sweep (optionally one warehouse); returns the emitted tasks. */
export async function generateDueTasks(warehouseId?: string): Promise<unknown[]> {
  const q = warehouseId ? `?warehouseId=${encodeURIComponent(warehouseId)}` : ''
  return ok(await fetch(`/api/counting/schedules/generate${q}`, { method: 'POST' }))
}

// ---- integration / host adapters (read-only config + status) ----
// Each adapter exposes a root info document (service / description / status). Routed paths:
//   host       -> /api/host/        (canonical openWCS Host API)
//   SAP        -> /api/integration/sap/
//   Manhattan  -> /api/integration/manhattan/
export interface AdapterInfo {
  key: string
  label: string
  path: string
  reachable: boolean
  service?: string
  description?: string
  status?: string
  error?: string
}

const ADAPTERS: { key: string; label: string; path: string }[] = [
  { key: 'host', label: 'Canonical Host API', path: '/api/host/' },
  { key: 'sap', label: 'SAP S/4HANA adapter', path: '/api/integration/sap/' },
  { key: 'manhattan', label: 'Manhattan Active adapter', path: '/api/integration/manhattan/' },
]

export async function listAdapters(): Promise<AdapterInfo[]> {
  return Promise.all(
    ADAPTERS.map(async (a) => {
      try {
        const res = await fetch(a.path)
        if (!res.ok) {
          return { ...a, reachable: false, error: `${res.status} ${res.statusText}` }
        }
        const body = (await res.json()) as Record<string, string>
        return {
          ...a,
          reachable: true,
          service: body.service,
          description: body.description,
          status: body.status,
        }
      } catch (e) {
        return { ...a, reachable: false, error: String(e) }
      }
    }),
  )
}

// ---- system status (read-only) ----
export interface HealthStatus {
  reachable: boolean
  status?: string
  components?: Record<string, { status?: string }>
  error?: string
}

// ---- demo mode (master-data) ----
export interface DemoStatus {
  enabled: boolean
  canEnable: boolean // true only when the catalog is empty (no host data)
  skuCount: number
}
export interface DemoResult {
  skus: number
  unitsOfMeasure: number
  barcodes: number
  shippers: number
  handlingUnitTypes: number
}

export async function getDemoStatus(): Promise<DemoStatus> {
  return ok(await fetch('/api/master-data/demo'))
}

/** Seed the demo catalog (admin-only; rejected unless the system is empty). */
export async function enableDemo(warehouseId: string): Promise<DemoResult> {
  const q = warehouseId ? `?warehouseId=${encodeURIComponent(warehouseId)}` : ''
  const res = await fetch(`/api/master-data/demo/enable${q}`, { method: 'POST' })
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { detail?: string }
    throw new Error(body.detail || `${res.status} ${res.statusText}`)
  }
  return res.json()
}

/** Remove all demo data (admin-only). */
export async function disableDemo(): Promise<DemoResult> {
  const res = await fetch('/api/master-data/demo/disable', { method: 'POST' })
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { detail?: string }
    throw new Error(body.detail || `${res.status} ${res.statusText}`)
  }
  return res.json()
}

/** Reads the gateway's own actuator health (not proxied; permitted under edge security). */
export async function getGatewayHealth(): Promise<HealthStatus> {
  try {
    const res = await fetch('/actuator/health')
    if (!res.ok) return { reachable: false, error: `${res.status} ${res.statusText}` }
    const body = (await res.json()) as { status?: string; components?: Record<string, { status?: string }> }
    return { reachable: true, status: body.status, components: body.components }
  } catch (e) {
    return { reachable: false, error: String(e) }
  }
}

