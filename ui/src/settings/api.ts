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

// ---- demo mode (master-data catalog + inventory handling units & stock) ----
export interface DemoStatus {
  enabled: boolean
  canEnable: boolean // true only on a fresh system (no host data) that already has locations
  skuCount: number
}
export interface DemoResult {
  skus: number
  unitsOfMeasure: number
  barcodes: number
  shippers: number
  handlingUnitTypes: number
  handlingUnits?: number
  emptyHandlingUnits?: number
  stockRows?: number
}

export async function getDemoStatus(warehouseId: string): Promise<DemoStatus> {
  const q = warehouseId ? `?warehouseId=${encodeURIComponent(warehouseId)}` : ''
  return ok(await fetch(`/api/master-data/demo${q}`))
}

async function demoPost(url: string, body?: unknown): Promise<unknown> {
  const res = await fetch(url, {
    method: 'POST',
    ...(body !== undefined ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : {}),
  })
  if (!res.ok) {
    const b = (await res.json().catch(() => ({}))) as { detail?: string }
    throw new Error(b.detail || `${res.status} ${res.statusText}`)
  }
  return res.json().catch(() => ({}))
}

async function idsFrom(url: string): Promise<string[]> {
  const res = await fetch(url)
  if (!res.ok) return []
  const body = (await res.json()) as { content?: { id: string }[] } | { id: string }[]
  const list = Array.isArray(body) ? body : body.content ?? []
  return list.map((x) => x.id)
}

/**
 * Enable demo mode: seed the master-data catalog, then register handling units + stock in the
 * warehouse's existing locations. Rejected server-side unless the system is empty (no host data)
 * AND the warehouse already has storage locations.
 */
export async function enableDemo(warehouseId: string): Promise<DemoResult> {
  const q = warehouseId ? `?warehouseId=${encodeURIComponent(warehouseId)}` : ''
  const cat = (await demoPost(`/api/master-data/demo/enable${q}`)) as DemoResult
  const huTypes = await ok<{ id: string; name: string }[]>(await fetch('/api/master-data/handling-unit-types'))
  const huTypeId = huTypes.find((t) => t.name === 'DEMO-STORAGE-HU')?.id ?? null
  const locationIds = warehouseId
    ? await idsFrom(`/api/master-data/locations?warehouseId=${encodeURIComponent(warehouseId)}&size=500`)
    : []
  const skuIds = await idsFrom('/api/master-data/skus?ownerClient=DEMO&size=500')
  const inv = (await demoPost('/api/inventory/demo/seed', { warehouseId, huTypeId, locationIds, skuIds })) as {
    handlingUnits: number
    emptyHandlingUnits: number
    stockRows: number
  }
  return { ...cat, handlingUnits: inv.handlingUnits, emptyHandlingUnits: inv.emptyHandlingUnits, stockRows: inv.stockRows }
}

/**
 * Disable demo mode = full operational reset for the warehouse: purge all transactional data across
 * services (stock, reservations, handling units, orders, transports, counts, GTP work, and the
 * transaction journal), then remove the whole SKU catalog from master data. Infrastructure
 * (warehouses, blocks, locations, topology, GTP/station config, equipment, users) is kept.
 * All clears are attempted even if some fail, and the master-data catalog removal always runs;
 * a failed clear is then surfaced (not swallowed) so the admin knows the reset was partial and
 * can flip the toggle again once the service is back. Admin-only.
 */
export async function disableDemo(warehouseId: string): Promise<DemoResult> {
  const failed: string[] = []
  if (warehouseId) {
    const wh = encodeURIComponent(warehouseId)
    const clears: Array<[string, string]> = [
      ['inventory', `/api/inventory/demo/clear?warehouseId=${wh}`],
      ['orders', `/api/orders/demo/clear?warehouseId=${wh}`],
      ['counting', `/api/counting/demo/clear?warehouseId=${wh}`],
      ['flow', `/api/flow/demo/clear?warehouseId=${wh}`],
      ['gtp', `/api/gtp/demo/clear?warehouseId=${wh}`],
      ['txlog', '/api/txlog/demo/clear'], // the journal is global, not warehouse-scoped
    ]
    const settled = await Promise.allSettled(clears.map(([, url]) => demoPost(url)))
    settled.forEach((s, i) => {
      if (s.status === 'rejected') failed.push(clears[i][0])
    })
  }
  const result = (await demoPost('/api/master-data/demo/disable')) as DemoResult
  if (failed.length) {
    throw new Error(
      `Demo data was removed from master data, but the reset is incomplete: clearing failed for ${failed.join(
        ', ',
      )}. Check those services and toggle demo mode off again to retry.`,
    )
  }
  return result
}

// ---- cubing config (warehouse fulfillment config + shipper catalog) ----
export interface FulfillmentConfig {
  id?: string
  warehouseId?: string
  allowedPickTypes: string[]
  cubingMode: string // APP | ONE_TO_ONE
  defaultShipperId?: string | null
  batchEnabled: boolean
  batchMaxPieces: number
  batchMaxOrders: number
  pickToteShipperId?: string | null
  defaultLabelTemplateCode?: string | null
}

export interface Shipper {
  id?: string
  warehouseId?: string
  code: string
  name?: string | null
  shipperType?: string | null
  lengthMm?: number | null
  widthMm?: number | null
  heightMm?: number | null
  tareWeightG?: number | null
  maxFillLevel?: number | null // usable fraction 0..1
  maxWeightG?: number | null
  status?: string
}

export function defaultFulfillmentConfig(warehouseId: string): FulfillmentConfig {
  return {
    warehouseId,
    allowedPickTypes: ['CASE', 'SPLIT_CASE', 'EACH'],
    cubingMode: 'APP',
    defaultShipperId: null,
    batchEnabled: false,
    batchMaxPieces: 1,
    batchMaxOrders: 12,
    pickToteShipperId: null,
    defaultLabelTemplateCode: null,
  }
}

export async function getFulfillmentConfig(warehouseId: string): Promise<FulfillmentConfig | null> {
  const res = await fetch(`/api/master-data/warehouses/${encodeURIComponent(warehouseId)}/fulfillment-config`)
  if (res.status === 404) return null
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  return (await res.json()) as FulfillmentConfig
}

export async function saveFulfillmentConfig(
  warehouseId: string,
  config: FulfillmentConfig,
): Promise<FulfillmentConfig> {
  return ok(
    await fetch(`/api/master-data/warehouses/${encodeURIComponent(warehouseId)}/fulfillment-config`, {
      method: 'PUT',
      headers: json,
      body: JSON.stringify({ ...config, warehouseId }),
    }),
  )
}

export async function listShippers(warehouseId: string): Promise<Shipper[]> {
  return ok(await fetch(`/api/master-data/shippers?warehouseId=${encodeURIComponent(warehouseId)}`))
}

export async function createShipper(warehouseId: string, shipper: Shipper): Promise<Shipper> {
  return ok(
    await fetch('/api/master-data/shippers', {
      method: 'POST',
      headers: json,
      body: JSON.stringify({ ...shipper, warehouseId }),
    }),
  )
}

export async function updateShipper(warehouseId: string, id: string, shipper: Shipper): Promise<Shipper> {
  return ok(
    await fetch(`/api/master-data/shippers/${encodeURIComponent(id)}`, {
      method: 'PUT',
      headers: json,
      body: JSON.stringify({ ...shipper, warehouseId }),
    }),
  )
}

/** Soft-delete (sets status ARCHIVED). */
export async function archiveShipper(id: string): Promise<void> {
  const res = await fetch(`/api/master-data/shippers/${encodeURIComponent(id)}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
}

// ---- hardware emulator mode (global admin toggle) ----
export interface EmulatorStatus {
  enabled: boolean
}

export async function getEmulatorStatus(): Promise<EmulatorStatus> {
  return ok(await fetch('/api/master-data/emulator'))
}

export async function enableEmulator(): Promise<EmulatorStatus> {
  return (await demoPost('/api/master-data/emulator/enable')) as EmulatorStatus
}

export async function disableEmulator(): Promise<EmulatorStatus> {
  return (await demoPost('/api/master-data/emulator/disable')) as EmulatorStatus
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

