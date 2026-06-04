// Client for the inventory service (via the gateway at /api/inventory/**).
// Handling units are the physical containers that hold stock; the stock overview is a
// read-only roll-up of what is currently in stock by handling unit. Both are scoped to a
// warehouse. The app installs a global auth fetch interceptor, so no manual headers here.

const json = { 'Content-Type': 'application/json' }

async function unwrap<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      const msg = body?.detail || body?.message || body?.error || body?.title
      if (msg) detail = msg
    } catch {
      /* no JSON body */
    }
    throw new Error(detail)
  }
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

const base = '/api/inventory'

// --------------------------------------------------------------- Handling units
// A handling unit always belongs to one warehouse, has a type (carton/pallet/tote…) and a
// current location, and holds stock. Status is ACTIVE / EMPTY / IN_TRANSIT / RETIRED.
export type HandlingUnitStatus = 'ACTIVE' | 'EMPTY' | 'IN_TRANSIT' | 'RETIRED'

export interface HandlingUnit {
  huId?: string
  warehouseId: string
  code: string
  huTypeId?: string | null
  locationId?: string | null
  status: HandlingUnitStatus
}

export async function listHandlingUnits(warehouseId: string): Promise<HandlingUnit[]> {
  return unwrap(await fetch(`${base}/handling-units?warehouseId=${encodeURIComponent(warehouseId)}`))
}
export async function createHandlingUnit(h: HandlingUnit): Promise<HandlingUnit> {
  return unwrap(await fetch(`${base}/handling-units`, { method: 'POST', headers: json, body: JSON.stringify(h) }))
}
export async function updateHandlingUnit(id: string, h: HandlingUnit): Promise<HandlingUnit> {
  return unwrap(await fetch(`${base}/handling-units/${id}`, { method: 'PUT', headers: json, body: JSON.stringify(h) }))
}

// ---------------------------------------------------------------- Stock overview
// Read-only roll-up: what is currently in stock, by handling unit. qty/reserved/available
// are decimal values from the backend (may arrive as strings).
export interface StockOverviewRow {
  skuId?: string | null
  locationId?: string | null
  huId?: string | null
  huCode?: string | null
  status?: string | null
  qty: number | string
  reserved: number | string
  available: number | string
}

export async function listStockOverview(warehouseId: string): Promise<StockOverviewRow[]> {
  return unwrap(await fetch(`${base}/stock/overview?warehouseId=${encodeURIComponent(warehouseId)}`))
}
