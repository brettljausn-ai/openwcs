// In-browser mock service layer for the public live demo (VITE_DEMO builds only).
//
// authFetch routes every /api/** and /admin/** request here when VITE_DEMO is set, so NO request
// ever leaves the browser. GETs resolve from the committed fixture snapshot (or a benign empty
// payload the calling screen renders as an empty state). Mutations are a no-op success, EXCEPT the
// GTP pick-station endpoints, which are driven by the in-memory pick engine. A small artificial
// latency keeps spinners and polling feeling live. Everything is module memory: a reload re-seeds.

import * as engine from './pickEngine'

// --- fixtures (eager imports so they bundle; small snapshot, ~280 KB) -----------------------------
import warehouses from './fixtures/warehouses.json'
import skus from './fixtures/skus.json'
import huTypes from './fixtures/handling-unit-types.json'
import locations from './fixtures/locations.json'
import areas from './fixtures/areas.json'
import storageBlocks from './fixtures/storage-blocks.json'
import alertThresholds from './fixtures/alert-thresholds.json'
import invStockOverview from './fixtures/inventory-stock-overview.json'
import invHandlingUnits from './fixtures/inventory-handling-units.json'
import invDashboard from './fixtures/inventory-dashboard.json'
import ordersDashboard from './fixtures/orders-dashboard.json'
import ordersSla from './fixtures/orders-sla.json'
import ordersDispatch from './fixtures/orders-dispatch.json'
import ordersPickTasks from './fixtures/orders-pick-tasks.json'
import allocationStockBlocking from './fixtures/allocation-stock-blocking.json'
import slottingReplenishment from './fixtures/slotting-replenishment-dashboard.json'
import slottingAbc from './fixtures/slotting-velocity-abc.json'
import flowAutomation from './fixtures/flow-automation-summary.json'
import notificationAlerts from './fixtures/notification-alerts.json'
import notificationAlertsHealth from './fixtures/notification-alerts-health.json'
import iamWarehouseAccessMe from './fixtures/iam-warehouse-access-me.json'
import skuCards from './fixtures/sku-cards.json'
import repStockBySku from './fixtures/reporting-stock-by-sku.json'
import repStorageDensity from './fixtures/reporting-storage-density.json'
import repScanQuality from './fixtures/reporting-scan-quality.json'
import repTraffic from './fixtures/reporting-traffic.json'
import repTransitTimes from './fixtures/reporting-transit-times.json'
import repStorageMovements from './fixtures/reporting-storage-movements.json'
import repDeviceMovements from './fixtures/reporting-device-movements.json'
import ordersFlowInbound from './fixtures/orders-flow-inbound.json'
import ordersFlowOutbound from './fixtures/orders-flow-outbound.json'
import countingTasks from './fixtures/counting-tasks.json'
import countingSchedules from './fixtures/counting-schedules.json'
import flowDeviceTasks from './fixtures/flow-device-tasks.json'
import processDesignerDefs from './fixtures/process-designer-defs.json'
import slottingPickSlots from './fixtures/slotting-pick-slots.json'
import slottingStorageProfiles from './fixtures/slotting-storage-profiles.json'
import gtpStations from './fixtures/gtp-stations.json'
import iamWarehouseAccess from './fixtures/iam-warehouse-access.json'
import adminDbSchemas from './fixtures/admin-db-schemas.json'
import kcUsers from './fixtures/kc-users.json'
import kcUsersCount from './fixtures/kc-users-count.json'

type Json = unknown

const LATENCY_MIN = 150
const LATENCY_MAX = 400

function latency(): number {
  return LATENCY_MIN + Math.random() * (LATENCY_MAX - LATENCY_MIN)
}

function delay<T>(value: T): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), latency()))
}

function jsonResponse(body: Json, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function pathOf(url: string): { path: string; query: URLSearchParams } {
  // Tolerate absolute and relative URLs. The basename (/demo-app) never appears on /api calls.
  const u = url.startsWith('http') ? new URL(url) : new URL(url, 'http://demo.local')
  return { path: u.pathname, query: u.searchParams }
}

function methodOf(input: RequestInfo | URL, init?: RequestInit): string {
  if (init?.method) return init.method.toUpperCase()
  if (input instanceof Request) return input.method.toUpperCase()
  return 'GET'
}

async function bodyOf(input: RequestInfo | URL, init?: RequestInit): Promise<unknown> {
  try {
    let raw: string | null = null
    if (init?.body && typeof init.body === 'string') raw = init.body
    else if (input instanceof Request) raw = await input.clone().text()
    if (!raw) return {}
    return JSON.parse(raw)
  } catch {
    return {}
  }
}

// --- GET resolution -------------------------------------------------------------------------------
// Exact-path table for the headline read endpoints. Each value is the snapshot to return. Paged
// list endpoints (master-data/skus, warehouses, locations) already ship in {content:[…]} form.
const GET_EXACT: Record<string, Json> = {
  '/api/master-data/warehouses': warehouses,
  '/api/master-data/skus': skus,
  '/api/master-data/handling-unit-types': huTypes,
  '/api/master-data/locations': locations,
  '/api/master-data/areas': areas,
  '/api/master-data/storage-blocks': storageBlocks,
  '/api/master-data/alert-thresholds': alertThresholds,
  '/api/master-data/label-templates': [],
  '/api/master-data/equipment': [],
  '/api/master-data/shippers': [],
  '/api/master-data/stock-rules': [],

  '/api/inventory/stock/overview': invStockOverview,
  '/api/inventory/handling-units': invHandlingUnits,
  '/api/inventory/reports/dashboard': invDashboard,
  '/api/inventory/reports/stock-by-sku': repStockBySku,
  '/api/inventory/reports/storage-density': repStorageDensity,

  // Reporting section (the /reporting/* screens): array datasets snapshotted from the demo box.
  '/api/flow/reports/scan-quality': repScanQuality,
  '/api/flow/reports/traffic': repTraffic,
  '/api/flow/reports/transit-times': repTransitTimes,
  '/api/flow/reports/storage-movements': repStorageMovements,
  '/api/flow/reports/device-movements': repDeviceMovements,

  '/api/orders/reports/dashboard': ordersDashboard,
  '/api/orders/reports/sla': ordersSla,
  '/api/orders/reports/dispatch': ordersDispatch,
  '/api/orders/pick-tasks': ordersPickTasks,

  '/api/allocation/reports/stock-blocking': allocationStockBlocking,

  '/api/slotting/replenishment/dashboard': slottingReplenishment,
  '/api/slotting/velocity/abc': slottingAbc,

  '/api/flow/reports/automation-summary': flowAutomation,

  '/api/notification/alerts': notificationAlerts,
  '/api/notification/alerts/health': notificationAlertsHealth,

  '/api/iam/warehouse-access/me': iamWarehouseAccessMe,
  // Per-user warehouse-access map (Warehouse access admin screen). Object keyed by username.
  '/api/iam/warehouse-access': iamWarehouseAccess,
  '/api/iam/screen-access': {},
  '/api/iam/me/language': { language: 'en' },

  // Screens that previously blanked the demo. Each lists from a service the SPA reads as an array
  // (counting / transport / process-designer / slotting / gtp-config / admin database / users).
  '/api/counting/tasks': countingTasks,
  '/api/counting/schedules': countingSchedules,
  '/api/flow/device-tasks': flowDeviceTasks,
  '/api/process-designer/defs': processDesignerDefs,
  '/api/slotting/pick-slots': slottingPickSlots,
  '/api/slotting/storage-profiles': slottingStorageProfiles,
  '/api/gtp/stations': gtpStations,
  '/api/master-data/admin/db/schemas': adminDbSchemas,
  // Keycloak admin (Warehouse access + User management screens) go same-origin via /admin/realms/**.
  '/admin/realms/openwcs/users': kcUsers,
  '/admin/realms/openwcs/users/count': kcUsersCount,

  // Backend demo-seeding status: the demo SPA's curated harness, not the backend seeder. Report off
  // so the in-app "seed demo data" buttons stay hidden (they would be no-ops anyway).
  '/api/master-data/demo': { enabled: false },

  '/api/assistant/status': { enabled: false },
}

// SKU product card: /api/master-data/skus/{id}/card -> the captured card for that SKU (or a minimal
// card synthesised from the SKU list so the GTP tote panel always has something to show).
function skuCard(skuId: string): Json {
  const cards = skuCards as Record<string, Json>
  if (cards[skuId]) return cards[skuId]
  const list = (skus as { content?: Array<{ id: string; code: string; description?: string; imageUrl?: string | null }> }).content ?? []
  const s = list.find((x) => x.id === skuId)
  if (s) return { id: s.id, code: s.code, description: s.description ?? null, imageUrl: s.imageUrl ?? null, baseUom: null, metadata: {} }
  return { id: skuId, code: skuId.slice(0, 8), description: null, imageUrl: null, baseUom: null, metadata: {} }
}

// Empty payload for an unmapped GET so the screen renders an empty state, never an error. The
// failing-screen audit showed every list endpoint these screens hit reads the body as a bare array
// and calls .map/.filter/.find on it directly, so an object ({}) crashes the whole SPA. The safe
// default for an unmapped GET is therefore [] (an empty list). Only two narrow cases differ:
//   - a clearly-paged master-data list the app reads as a page -> {content:[]}
//   - a clearly-single-object resource (path ends in an /{id}-looking segment) -> {}
// Known endpoints are matched in GET_EXACT first; this is the catch-all safety net.
function emptyFor(path: string): Json {
  // Paged master-data list endpoints the app reads as {content:[...]} pages (e.g. SKUs, warehouses,
  // locations). The known ones ship full fixtures above; an unmapped one still needs the page shape.
  if (/^\/api\/master-data\/(warehouses|skus|locations|areas|storage-blocks|equipment|shippers)\b/.test(path)) {
    return { content: [], totalElements: 0, totalPages: 0, number: 0, size: 0 }
  }
  // A single-object resource: the last path segment looks like an id (uuid / long hex / numeric),
  // not a collection name. These callers read one object, so {} is the right empty shape.
  const last = path.split('/').filter(Boolean).pop() ?? ''
  const looksLikeId =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(last) ||
    /^[0-9a-f]{16,}$/i.test(last) ||
    /^\d+$/.test(last)
  if (looksLikeId) return {}
  // Everything else (a collection / list endpoint) defaults to an empty array, so .map/.filter/.find
  // never throw and the screen renders its empty state instead of blanking the app.
  return []
}

function resolveGet(path: string, query: URLSearchParams): Json {
  if (GET_EXACT[path] !== undefined) return GET_EXACT[path]

  // /api/master-data/skus/{id}/card
  const card = path.match(/^\/api\/master-data\/skus\/([^/]+)\/card$/)
  if (card) return skuCard(card[1])
  // /api/master-data/skus/{id}/uoms or /barcodes -> empty list
  if (/^\/api\/master-data\/skus\/[^/]+\/(uoms|barcodes)$/.test(path)) return []

  // GTP induction queue lives in flow: /api/flow/induction/queue?workplaceId=
  if (path === '/api/flow/induction/queue') {
    const wid = query.get('workplaceId') ?? ''
    return engine.getStationQueue(wid)
  }
  // GTP workplaces (GET): /api/gtp/workplaces?warehouseId=
  if (path === '/api/gtp/workplaces') {
    return engine.listWorkplaces(query.get('warehouseId') ?? '')
  }

  // Order flow report is direction-scoped: /api/orders/reports/flow?direction=INBOUND|OUTBOUND
  if (path === '/api/orders/reports/flow') {
    return query.get('direction') === 'OUTBOUND' ? ordersFlowOutbound : ordersFlowInbound
  }

  return emptyFor(path)
}

// --- mutation routing -----------------------------------------------------------------------------
// Only the GTP pick-station endpoints mutate (the in-memory engine). Everything else is a benign
// no-op success so any stray write control in the read-only UI is harmless.
function routeMutation(method: string, path: string, body: unknown): Json {
  // claim session: POST /api/gtp/workplaces/{id}/session
  let m = path.match(/^\/api\/gtp\/workplaces\/([^/]+)\/session$/)
  if (m && method === 'POST') return engine.claimWorkplace(m[1])

  // heartbeat: POST /api/gtp/workplaces/{id}/session/{sid}/heartbeat
  if (/^\/api\/gtp\/workplaces\/[^/]+\/session\/[^/]+\/heartbeat$/.test(path) && method === 'POST') {
    return engine.heartbeat()
  }
  // release: DELETE /api/gtp/workplaces/{id}/session/{sid}
  if (/^\/api\/gtp\/workplaces\/[^/]+\/session\/[^/]+$/.test(path) && method === 'DELETE') {
    return { ok: true }
  }

  // present: POST /api/gtp/stations/{id}/present
  m = path.match(/^\/api\/gtp\/stations\/([^/]+)\/present$/)
  if (m && method === 'POST') {
    const b = body as { stockHuId: string; skuId: string; qty: number }
    return engine.presentStock(m[1], b)
  }

  // confirm put: POST /api/gtp/puts/{putId}/confirm
  m = path.match(/^\/api\/gtp\/puts\/([^/]+)\/confirm$/)
  if (m && method === 'POST') {
    const b = (body as { qty?: number }) ?? {}
    return engine.confirmPut(m[1], b.qty)
  }

  // close cycle: POST /api/gtp/cycles/{id}/close
  m = path.match(/^\/api\/gtp\/cycles\/([^/]+)\/close$/)
  if (m && method === 'POST') return engine.closeCycle(m[1])

  // complete queue entry: POST /api/gtp/queue/{entryId}/complete
  m = path.match(/^\/api\/gtp\/queue\/([^/]+)\/complete$/)
  if (m && method === 'POST') return engine.completeQueueEntry(m[1])

  // exceptions: dirty tote / broken units
  m = path.match(/^\/api\/gtp\/stations\/([^/]+)\/exceptions\/dirty-tote$/)
  if (m && method === 'POST') {
    const b = body as { queueEntryId: string }
    engine.markToteDirty(m[1], b.queueEntryId)
    return { ok: true }
  }
  m = path.match(/^\/api\/gtp\/stations\/([^/]+)\/exceptions\/broken$/)
  if (m && method === 'POST') {
    const b = body as { queueEntryId: string; qty: number }
    return engine.markProductBroken(m[1], b.queueEntryId, b.qty)
  }

  // station drain switches
  if (/^\/api\/gtp\/stations\/[^/]+\/activate$/.test(path) && method === 'POST') return engine.activateStation()
  if (/^\/api\/gtp\/stations\/[^/]+\/deactivate$/.test(path) && method === 'POST') return engine.deactivateStation()

  // Everything else: no-op success. Echo the body so optimistic-update callers do not choke.
  return body && typeof body === 'object' && Object.keys(body).length ? { ok: true, ...(body as object) } : { ok: true }
}

// --- public entry point ---------------------------------------------------------------------------
// Called by authFetch in demo mode for every /api/** and /admin/** request.
export async function mockFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
  const method = methodOf(input, init)
  const { path, query } = pathOf(url)

  if (method === 'GET' || method === 'HEAD') {
    if (GET_EXACT[path] === undefined && !isKnownDynamicGet(path)) {
      // Coverage gap: a screen called an endpoint with no fixture. Surface it (dev only) so we can
      // add it; the screen still renders its empty state.
      if (import.meta.env.DEV) console.warn(`[demo mock] unhandled GET ${path} -> empty payload`)
    }
    return delay(jsonResponse(resolveGet(path, query)))
  }

  const body = await bodyOf(input, init)
  return delay(jsonResponse(routeMutation(method, path, body)))
}

// Dynamic GET paths we DO handle (so they don't trip the coverage warning).
function isKnownDynamicGet(path: string): boolean {
  return (
    /^\/api\/master-data\/skus\/[^/]+\/(card|uoms|barcodes)$/.test(path) ||
    path === '/api/flow/induction/queue' ||
    path === '/api/gtp/workplaces'
  )
}
