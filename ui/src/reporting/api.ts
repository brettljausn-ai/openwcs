// Clients for the reporting endpoints (flow-orchestrator, inventory and orders, via the gateway).
// All reports are warehouse-scoped and windowed in whole days; days counts back from today.
// Shapes mirror the backend reporting contracts exactly: screens resolve ids (skuId, locationId,
// blockId, equipment) to human-friendly codes via useCatalog / master-data, never showing raw UUIDs.

function enc(v: string): string {
  return encodeURIComponent(v)
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`Request failed: ${res.status}`)
  return (await res.json()) as T
}

// ---------------------------------------------------------------- Material flow (flow-orchestrator)

/** Scan outcomes at one scan point on one day. `node` is the routing-node code of the scanner. */
export interface ScanQualityRow {
  node: string
  day: string // YYYY-MM-DD
  scans: number
  noReads: number
  unknowns: number
}

export async function loadScanQuality(warehouseId: string, days: number): Promise<ScanQualityRow[]> {
  return getJson(`/api/flow/reports/scan-quality?warehouseId=${enc(warehouseId)}&days=${days}`)
}

/** Observed conveyor traffic on one routing edge on one day. */
export interface TrafficRow {
  fromNode: string
  toNode: string
  day: string
  count: number
}

export async function loadTraffic(warehouseId: string, days: number): Promise<TrafficRow[]> {
  return getJson(`/api/flow/reports/traffic?warehouseId=${enc(warehouseId)}&days=${days}`)
}

/** Daily transit-time distribution across completed transports. */
export interface TransitTimeRow {
  day: string
  count: number
  p50Ms: number
  p95Ms: number
}

export async function loadTransitTimes(warehouseId: string, days: number): Promise<TransitTimeRow[]> {
  return getJson(`/api/flow/reports/transit-times?warehouseId=${enc(warehouseId)}&days=${days}`)
}

// ---------------------------------------------------------------- ASRS (flow + inventory)

/** Stores/retrieves per storage location per day. */
export interface StorageMovementRow {
  locationId: string
  day: string
  stores: number
  retrieves: number
}

export async function loadStorageMovements(warehouseId: string, days: number): Promise<StorageMovementRow[]> {
  return getJson(`/api/flow/reports/storage-movements?warehouseId=${enc(warehouseId)}&days=${days}`)
}

/** Completed/failed device tasks per equipment per day (shuttle, crane, …). */
export interface DeviceMovementRow {
  equipment: string
  family: string
  day: string
  completed: number
  failed: number
}

export async function loadDeviceMovements(warehouseId: string, days: number): Promise<DeviceMovementRow[]> {
  return getJson(`/api/flow/reports/device-movements?warehouseId=${enc(warehouseId)}&days=${days}`)
}

/** Occupied vs total cells per storage block per day. */
export interface StorageDensityRow {
  blockId: string
  day: string
  occupiedCells: number
  totalCells: number
  pct: number
}

export async function loadStorageDensity(warehouseId: string, days: number): Promise<StorageDensityRow[]> {
  return getJson(`/api/inventory/reports/storage-density?warehouseId=${enc(warehouseId)}&days=${days}`)
}

// ---------------------------------------------------------------- Stock (inventory)

/** Current stock per SKU in single quantities, split by availability. */
export interface StockBySkuRow {
  skuId: string
  available: number
  allocated: number
  unavailable: number
}

export async function loadStockBySku(warehouseId: string): Promise<StockBySkuRow[]> {
  return getJson(`/api/inventory/reports/stock-by-sku?warehouseId=${enc(warehouseId)}`)
}

// ---------------------------------------------------------------- Inbound / outbound (orders)

export type FlowDirection = 'INBOUND' | 'OUTBOUND'

export interface OrderFlowDay {
  day: string
  received: number
  started: number
  completed: number
}

export interface OrderFlowHour {
  hour: number // 0..23
  count: number
}

/** Order-flow report for one direction: headline figures + 90-day dailies + hour-of-day profile. */
export interface OrderFlowReportDto {
  /** Orders received from the host but not yet stock (inbound) / not yet released (outbound). */
  expected: number
  active: number
  started: number
  perDay: OrderFlowDay[]
  hourOfDay: OrderFlowHour[]
}

export async function loadOrderFlow(
  warehouseId: string,
  direction: FlowDirection,
  days: number,
): Promise<OrderFlowReportDto> {
  const body = await getJson<Partial<OrderFlowReportDto>>(
    `/api/orders/reports/flow?warehouseId=${enc(warehouseId)}&direction=${direction}&days=${days}`,
  )
  return {
    expected: body.expected ?? 0,
    active: body.active ?? 0,
    started: body.started ?? 0,
    perDay: body.perDay ?? [],
    hourOfDay: body.hourOfDay ?? [],
  }
}
