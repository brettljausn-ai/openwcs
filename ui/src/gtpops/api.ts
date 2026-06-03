// Client for the GTP operator console (via the gateway at /api/gtp).
//
// Workplaces are GTP stations. Opening one CLAIMS a session (superseding any session already on
// that workplace); the console HEARTBEATs to keep it alive + learn if it was taken over, and
// RELEASEs it on close. Work-cycle endpoints (present / confirm puts / task outcomes) drive the
// per-mode operator flow once a session is held.

export type OperatingMode = 'PICKING' | 'DECANTING' | 'STOCK_COUNT' | 'QC' | 'MAINTENANCE'

export interface WorkplaceNode {
  id: string
  role: 'STOCK' | 'ORDER'
  code: string
  putLightId: string | null
  locationId: string | null
  orderHuId: string | null
  position: number
  status: string
}

export interface Workplace {
  id: string
  warehouseId: string
  code: string
  mode: 'ORDER_LOCATION' | 'PUT_WALL'
  supportedModes: OperatingMode[]
  status: string
  inUse: boolean
  nodes: WorkplaceNode[]
}

export interface WorkplaceSession {
  sessionId: string
  stationId: string
  operator: string | null
  status: 'ACTIVE' | 'SUPERSEDED' | 'RELEASED'
  claimedAt: string
  lastHeartbeatAt: string
  workplace: Workplace
}

export interface SessionStatus {
  active: boolean
  reason: 'superseded' | 'released' | null
}

export interface PutInstruction {
  id: string
  destinationNodeId: string
  destinationDemandId: string
  orderRef: string
  orderLineId: string | null
  orderHuId: string | null
  putLightId: string | null
  qty: number
  confirmedQty: number
  status: 'OPEN' | 'CONFIRMED' | 'SHORT' | 'CANCELLED'
}

export interface TaskLine {
  id: string
  lineType: 'DECANT_MOVE' | 'COUNT_ENTRY' | 'QC_VERDICT' | 'MAINTENANCE_CHECK'
  huId: string | null
  skuId: string | null
  compartment: string | null
  expectedQty: number | null
  actualQty: number | null
  variance: number | null
  verdict: string | null
  putLightId: string | null
  status: 'OPEN' | 'CONFIRMED' | 'CANCELLED'
}

export interface WorkCycle {
  id: string
  stationId: string
  operatingMode: OperatingMode
  stockNodeId: string
  stockHuId: string | null
  targetHuId: string | null
  skuId: string | null
  mode: 'ORDER_LOCATION' | 'PUT_WALL'
  presentedQty: number | null
  remainingQty: number | null
  status: 'OPEN' | 'COMPLETED' | 'CLOSED'
  puts: PutInstruction[]
  taskLines: TaskLine[]
}

const json = { 'Content-Type': 'application/json' }

async function ok<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      if (body && body.error) detail = String(body.error)
    } catch {
      /* keep status line */
    }
    throw new Error(detail)
  }
  return (await res.json()) as T
}

// ---- workplaces + session lifecycle ----
export async function listWorkplaces(warehouseId: string): Promise<Workplace[]> {
  return ok(await fetch(`/api/gtp/workplaces?warehouseId=${encodeURIComponent(warehouseId)}`))
}

export async function claimWorkplace(stationId: string): Promise<WorkplaceSession> {
  return ok(await fetch(`/api/gtp/workplaces/${stationId}/session`, { method: 'POST', headers: json }))
}

export async function heartbeat(stationId: string, sessionId: string): Promise<SessionStatus> {
  return ok(
    await fetch(`/api/gtp/workplaces/${stationId}/session/${sessionId}/heartbeat`, {
      method: 'POST',
      headers: json,
    }),
  )
}

// Best-effort release; used on unmount/close so it must not throw.
export function releaseWorkplace(stationId: string, sessionId: string): void {
  try {
    fetch(`/api/gtp/workplaces/${stationId}/session/${sessionId}`, { method: 'DELETE', keepalive: true }).catch(
      () => {},
    )
  } catch {
    /* ignore — page is unloading */
  }
}

// ---- work cycle (per operating mode) ----
export async function presentStock(
  stationId: string,
  body: { stockNodeId?: string | null; stockHuId: string; skuId: string; qty: number },
): Promise<WorkCycle> {
  return ok(
    await fetch(`/api/gtp/stations/${stationId}/present`, {
      method: 'POST',
      headers: json,
      body: JSON.stringify(body),
    }),
  )
}

export async function confirmPut(putInstructionId: string, qty?: number): Promise<PutInstruction> {
  return ok(
    await fetch(`/api/gtp/puts/${putInstructionId}/confirm`, {
      method: 'POST',
      headers: json,
      body: JSON.stringify(qty == null ? {} : { qty }),
    }),
  )
}

export async function closeCycle(cycleId: string): Promise<WorkCycle> {
  return ok(await fetch(`/api/gtp/cycles/${cycleId}/close`, { method: 'POST', headers: json }))
}
