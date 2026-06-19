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
  '/api/iam/screen-access': {},
  '/api/iam/me/language': { language: 'en' },

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

// Heuristic empty payload for an unmapped GET so the screen renders an empty state, never an error.
// List-ish paths get []; paged-ish get {content:[]}; everything else {}.
function emptyFor(path: string): Json {
  if (/\/(list|tasks|alerts|events|services|definitions|instances|areas|locations|skus|warehouses|equipment|shippers|templates|blocks|units|orders|pick-tasks|queue)\b/.test(path)) {
    // Master-data list endpoints that the app reads as pages return {content:[]}; the simpler
    // services return bare arrays. Defaulting to [] is safe for both (callers tolerate arrays or
    // {content}); the known paged ones are handled in GET_EXACT above.
    return []
  }
  return {}
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
