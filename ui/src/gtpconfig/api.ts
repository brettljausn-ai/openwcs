// Client for GTP workplace/station configuration (admin) via the gateway at /api/gtp/stations/**.
//
// A GTP workplace is a station: a destination topology `mode` (ORDER_LOCATION | PUT_WALL), a set of
// supported operating modes, and its STOCK/ORDER nodes. This admin screen does CRUD on stations and
// their nodes and configures supported operating modes. The bearer token (with the admin's roles) is
// added by the global fetch interceptor.

export type OperatingMode = 'PICKING' | 'DECANTING' | 'DECANT_MULTI' | 'STOCK_COUNT' | 'QC' | 'MAINTENANCE'
export const OPERATING_MODES: OperatingMode[] = ['PICKING', 'DECANTING', 'DECANT_MULTI', 'STOCK_COUNT', 'QC', 'MAINTENANCE']
// Friendly labels for the mode codes (DECANT_MULTI = one source HU → many target HUs / compartments).
export const OPERATING_MODE_LABELS: Record<OperatingMode, string> = {
  PICKING: 'Picking',
  DECANTING: 'Decanting',
  DECANT_MULTI: 'Decant 1→N',
  STOCK_COUNT: 'Stock count',
  QC: 'QC',
  MAINTENANCE: 'Maintenance',
}

export type StationMode = 'ORDER_LOCATION' | 'PUT_WALL'
export const STATION_MODES: StationMode[] = ['ORDER_LOCATION', 'PUT_WALL']

export type NodeRole = 'STOCK' | 'ORDER'
export const NODE_ROLES: NodeRole[] = ['STOCK', 'ORDER']

export interface StationNode {
  id: string
  role: NodeRole
  code: string
  putLightId: string | null
  locationId: string | null
  orderHuId: string | null
  position: number
  status: string
}

export interface Station {
  id: string
  warehouseId: string
  code: string
  name: string | null
  mode: StationMode
  supportedModes: OperatingMode[]
  status: string
  nodes: StationNode[]
}

export interface CreateStationBody {
  warehouseId: string
  code: string
  name?: string | null
  mode: StationMode
  supportedModes: OperatingMode[]
}

export interface UpdateStationBody {
  code: string
  name?: string | null
  mode: StationMode
  status: string
  supportedModes: OperatingMode[]
}

export interface NodeBody {
  role: NodeRole
  code: string
  putLightId?: string | null
  locationId?: string | null
  orderHuId?: string | null
  position?: number | null
  status?: string | null
}

const json = { 'Content-Type': 'application/json' }

async function unwrap<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      const msg = body?.error || body?.detail || body?.message || body?.title
      if (msg) detail = String(msg)
    } catch {
      /* no JSON body */
    }
    throw new Error(detail)
  }
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

async function expectOk(res: Response): Promise<void> {
  await unwrap<void>(res)
}

const base = '/api/gtp/stations'

// ---- stations ----
export async function listStations(warehouseId: string): Promise<Station[]> {
  return unwrap(await fetch(`${base}?warehouseId=${encodeURIComponent(warehouseId)}`))
}

export async function createStation(body: CreateStationBody): Promise<Station> {
  return unwrap(await fetch(base, { method: 'POST', headers: json, body: JSON.stringify(body) }))
}

export async function updateStation(id: string, body: UpdateStationBody): Promise<Station> {
  return unwrap(await fetch(`${base}/${id}`, { method: 'PUT', headers: json, body: JSON.stringify(body) }))
}

export async function deleteStation(id: string): Promise<void> {
  await expectOk(await fetch(`${base}/${id}`, { method: 'DELETE' }))
}

// ---- nodes ----
export async function addNode(stationId: string, body: NodeBody): Promise<StationNode> {
  return unwrap(await fetch(`${base}/${stationId}/nodes`, { method: 'POST', headers: json, body: JSON.stringify(body) }))
}

export async function updateNode(nodeId: string, body: NodeBody): Promise<StationNode> {
  return unwrap(await fetch(`${base}/nodes/${nodeId}`, { method: 'PUT', headers: json, body: JSON.stringify(body) }))
}

export async function deleteNode(nodeId: string): Promise<void> {
  await expectOk(await fetch(`${base}/nodes/${nodeId}`, { method: 'DELETE' }))
}

// ---- operating modes ----
export async function setSupportedModes(stationId: string, supportedModes: OperatingMode[]): Promise<Station> {
  return unwrap(
    await fetch(`${base}/${stationId}/operating-modes`, {
      method: 'POST',
      headers: json,
      body: JSON.stringify({ supportedModes }),
    }),
  )
}
