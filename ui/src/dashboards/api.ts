// Typed fetchers for the Dashboards area. Every endpoint is warehouse-scoped and read-only; they
// are polled on a timer (15 s on the landing page, 60 s on the area dashboards) by the screens.
// Shapes mirror the backend dashboard contracts exactly. All calls go through the gateway (/api/**)
// and rely on the global Bearer interceptor (we just call fetch).

function enc(v: string): string {
  return encodeURIComponent(v)
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`Request failed: ${res.status}`)
  return (await res.json()) as T
}

// ---------------------------------------------------------------- orders dashboard (order-management)

export interface OrdersDashboardInbound {
  open: number
  expectedToday: number
  projectedFinishIso: string | null
}
export interface OrdersDashboardOutbound {
  open: number
  releasedToday: number
  linesPickedToday: number
  shortsToday: number
  projectedFinishIso: string | null
}
export interface OrdersDashboardHour {
  hour: number // 0..23
  inbound: number
  outbound: number
}
export interface OrdersDashboardDto {
  inbound: OrdersDashboardInbound
  outbound: OrdersDashboardOutbound
  perHour: OrdersDashboardHour[]
}

export async function loadOrdersDashboard(warehouseId: string): Promise<OrdersDashboardDto> {
  const b = await getJson<Partial<OrdersDashboardDto>>(`/api/orders/reports/dashboard?warehouseId=${enc(warehouseId)}`)
  return {
    inbound: { open: 0, expectedToday: 0, projectedFinishIso: null, ...(b.inbound ?? {}) },
    outbound: {
      open: 0,
      releasedToday: 0,
      linesPickedToday: 0,
      shortsToday: 0,
      projectedFinishIso: null,
      ...(b.outbound ?? {}),
    },
    perHour: b.perHour ?? [],
  }
}

// ---------------------------------------------------------------- dispatch (order-management)

export interface DispatchRoute {
  routeCode: string
  cutoffIso: string | null
  ordersAssigned: number
  ordersReady: number
  status: string // OPEN | LOADING | DEPARTED
}
export interface DispatchNextDeparture {
  routeCode: string
  cutoffIso: string | null
  secondsToCutoff: number
}
export interface DispatchDto {
  nextDeparture: DispatchNextDeparture | null
  routes: DispatchRoute[]
}

export async function loadDispatch(warehouseId: string): Promise<DispatchDto> {
  const b = await getJson<Partial<DispatchDto>>(`/api/orders/reports/dispatch?warehouseId=${enc(warehouseId)}`)
  return { nextDeparture: b.nextDeparture ?? null, routes: b.routes ?? [] }
}

// ---------------------------------------------------------------- inventory dashboard

export interface PutawayBacklog {
  count: number
  oldestAgeMin: number
}
export interface InventoryDashboardDto {
  husReceivedToday: number
  huCount: number
  skuCountWithStock: number
  utilisationPct: number
  asrsUtilisationPct: number
  putawayBacklog: PutawayBacklog
}

export async function loadInventoryDashboard(warehouseId: string): Promise<InventoryDashboardDto> {
  const b = await getJson<Partial<InventoryDashboardDto>>(
    `/api/inventory/reports/dashboard?warehouseId=${enc(warehouseId)}`,
  )
  return {
    husReceivedToday: b.husReceivedToday ?? 0,
    huCount: b.huCount ?? 0,
    skuCountWithStock: b.skuCountWithStock ?? 0,
    utilisationPct: b.utilisationPct ?? 0,
    asrsUtilisationPct: b.asrsUtilisationPct ?? 0,
    putawayBacklog: { count: 0, oldestAgeMin: 0, ...(b.putawayBacklog ?? {}) },
  }
}

// ---------------------------------------------------------------- stock-blocking (allocation)

export interface StockBlockingDto {
  blockedLines: number
  distinctSkusShort: number
  recoverableLines: number
  zeroStockLines: number
  openOutboundLines: number
}

export async function loadStockBlocking(warehouseId: string): Promise<StockBlockingDto> {
  const b = await getJson<Partial<StockBlockingDto>>(
    `/api/allocation/reports/stock-blocking?warehouseId=${enc(warehouseId)}`,
  )
  return {
    blockedLines: b.blockedLines ?? 0,
    distinctSkusShort: b.distinctSkusShort ?? 0,
    recoverableLines: b.recoverableLines ?? 0,
    zeroStockLines: b.zeroStockLines ?? 0,
    openOutboundLines: b.openOutboundLines ?? 0,
  }
}

// ---------------------------------------------------------------- replenishment (slotting)

export interface ProjectedStockout {
  locationCode: string
  minutesToStockout: number
}
export interface ReplenishmentDashboardDto {
  urgent: number
  openTasks: number
  oldestAgeMin: number
  projectedStockouts: ProjectedStockout[]
}

export async function loadReplenishmentDashboard(warehouseId: string): Promise<ReplenishmentDashboardDto> {
  const b = await getJson<Partial<ReplenishmentDashboardDto>>(
    `/api/slotting/replenishment/dashboard?warehouseId=${enc(warehouseId)}`,
  )
  return {
    urgent: b.urgent ?? 0,
    openTasks: b.openTasks ?? 0,
    oldestAgeMin: b.oldestAgeMin ?? 0,
    projectedStockouts: b.projectedStockouts ?? [],
  }
}

// ---------------------------------------------------------------- ABC velocity (slotting)

export interface ParetoPoint {
  skuId: string
  picks: number
  cumPct: number
}
export interface MoverRow {
  skuId: string
  picks: number
}
export interface TrendRow {
  skuId: string
  pct14d: number
  pct90d: number
}
export interface AbcDto {
  a: number
  b: number
  c: number
  pareto: ParetoPoint[]
  top: MoverRow[]
  bottom: MoverRow[]
  risers: TrendRow[]
  fallers: TrendRow[]
}

export async function loadAbc(warehouseId: string): Promise<AbcDto> {
  const b = await getJson<Partial<AbcDto>>(`/api/slotting/velocity/abc?warehouseId=${enc(warehouseId)}`)
  return {
    a: b.a ?? 0,
    b: b.b ?? 0,
    c: b.c ?? 0,
    pareto: b.pareto ?? [],
    top: b.top ?? [],
    bottom: b.bottom ?? [],
    risers: b.risers ?? [],
    fallers: b.fallers ?? [],
  }
}

// ---------------------------------------------------------------- automation summary (flow)

export interface AutomationSummaryDto {
  scanNoReadPctToday: number
  equipmentAvailabilityPct: number
  equipmentTotal: number
  equipmentInFault: number
}

export async function loadAutomationSummary(warehouseId: string): Promise<AutomationSummaryDto> {
  const b = await getJson<Partial<AutomationSummaryDto>>(
    `/api/flow/reports/automation-summary?warehouseId=${enc(warehouseId)}`,
  )
  return {
    scanNoReadPctToday: b.scanNoReadPctToday ?? 0,
    equipmentAvailabilityPct: b.equipmentAvailabilityPct ?? 0,
    equipmentTotal: b.equipmentTotal ?? 0,
    equipmentInFault: b.equipmentInFault ?? 0,
  }
}

// ---------------------------------------------------------------- alerts (notification)

export type AlertSeverity = 'CRITICAL' | 'WARNING' | 'INFO' | string

export interface AlertDto {
  id: string
  area: string
  severity: AlertSeverity
  metric: string
  value: number
  threshold: number
  sinceIso: string
  state: string
}

export async function loadAlerts(warehouseId: string): Promise<AlertDto[]> {
  const b = await getJson<AlertDto[] | null>(`/api/notification/alerts?warehouseId=${enc(warehouseId)}`)
  return Array.isArray(b) ? b : []
}

// ---------------------------------------------------------------- alert thresholds (master-data)

export interface AlertThresholds {
  scanNoReadPct: number
  receiveErrorsDay: number
  asrsUtilisationPct: number
  blockedLinesPct: number
  putawayBacklogAgeMin: number
  replenishmentOldestAgeMin: number
}

export async function getAlertThresholds(): Promise<AlertThresholds> {
  return getJson('/api/master-data/alert-thresholds')
}

export async function saveAlertThresholds(t: AlertThresholds): Promise<AlertThresholds> {
  const res = await fetch('/api/master-data/alert-thresholds', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(t),
  })
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  return (await res.json()) as AlertThresholds
}
