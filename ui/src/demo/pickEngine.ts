// In-memory GTP pick engine for the public live demo (VITE_DEMO builds only).
//
// This is the ONLY live state in the demo. On start it arms 5 totes for the PP1 pick station, each
// carrying a real demo SKU (from the fixtures), a quantity and a destination order. The totes walk
// REQUESTED -> IN_TRANSIT -> QUEUED on short timers so the induction queue visibly fills. The engine
// answers exactly the GTP endpoints the GtpOpsScreen console calls, with the exact shapes from
// gtpops/api.ts (Workplace, WorkplaceSession, StationQueueEntry, WorkCycle, PutInstruction) so the
// screen and the mock cannot drift.
//
// Nothing here is persisted: a page reload re-imports the module and re-seeds from fixtures. reset()
// re-arms all 5 totes (used by the Restart action).

import type {
  Workplace,
  WorkplaceSession,
  StationQueueEntry,
  WorkCycle,
  PutInstruction,
  SessionStatus,
} from '../gtpops/api'
import gtpWorkplaces from './fixtures/gtp-workplaces.json'
import skusFixture from './fixtures/skus.json'

// Lifecycle timers (ms): how long after arming a tote moves to IN_TRANSIT, then QUEUED. Staggered per
// tote so the queue fills one at a time and the demo feels live.
const TO_IN_TRANSIT_MS = 1500
const TO_QUEUED_MS = 4000
const STAGGER_MS = 2500

interface DemoSku {
  id: string
  code: string
  description?: string
}

function demoSkus(): DemoSku[] {
  const list = (skusFixture as { content?: DemoSku[] }).content ?? []
  return list.length ? list : [{ id: 'sku-demo', code: 'DEMO-SKU', description: 'Demo SKU' }]
}

// The station the demo drives: the first PICKING-capable workplace from the snapshot (PP1).
function pickStation(): Workplace {
  const all = gtpWorkplaces as Workplace[]
  const picking = all.find((w) => w.supportedModes.includes('PICKING'))
  return picking ?? all[0]
}

// One armed tote and its derived demand. confirmedQty/exception track operator actions.
interface DemoTote {
  entryId: string
  huId: string
  huCode: string
  skuId: string
  skuCode: string
  qty: number
  orderRef: string
  destinationHuId: string
  status: StationQueueEntry['status']
  arrivalSeq: number | null
  requestedAt: string
  inTransitAt: string | null
  queuedAt: string | null
  // Per-cycle working state once presented:
  cycleId: string | null
  putId: string
  confirmedQty: number | null
  putStatus: PutInstruction['status']
  done: boolean
}

interface EngineState {
  station: Workplace
  sessionId: string | null
  totes: DemoTote[]
  timers: ReturnType<typeof setTimeout>[]
  arrivalCounter: number
}

let state: EngineState | null = null

function nowIso(): string {
  return new Date().toISOString()
}

function uid(prefix: string): string {
  return `${prefix}-${Math.random().toString(36).slice(2, 10)}`
}

// Build the 5 starting totes from real demo SKUs. Quantities are small whole numbers; one tote is a
// larger qty so a short pick reads naturally.
function armTotes(station: Workplace): DemoTote[] {
  const skus = demoSkus()
  const qtys = [3, 5, 2, 8, 4]
  const totes: DemoTote[] = []
  for (let i = 0; i < 5; i++) {
    const sku = skus[i % skus.length]
    totes.push({
      entryId: uid('entry'),
      huId: uid('hu'),
      huCode: `TOTE-${String(i + 1).padStart(3, '0')}`,
      skuId: sku.id,
      skuCode: sku.code,
      qty: qtys[i],
      orderRef: `SO-${5200 + i}`,
      destinationHuId: uid('dhu'),
      status: 'REQUESTED',
      arrivalSeq: null,
      requestedAt: nowIso(),
      inTransitAt: null,
      queuedAt: null,
      cycleId: null,
      putId: uid('put'),
      confirmedQty: null,
      putStatus: 'OPEN',
      done: false,
    })
  }
  return totes
}

function clearTimers(): void {
  if (!state) return
  for (const t of state.timers) clearTimeout(t)
  state.timers = []
}

// Schedule the REQUESTED -> IN_TRANSIT -> QUEUED walk for a tote, staggered by its index.
function scheduleArrival(tote: DemoTote, index: number): void {
  if (!state) return
  const base = index * STAGGER_MS
  state.timers.push(
    setTimeout(() => {
      if (!state) return
      if (tote.status === 'REQUESTED') {
        tote.status = 'IN_TRANSIT'
        tote.inTransitAt = nowIso()
      }
    }, base + TO_IN_TRANSIT_MS),
  )
  state.timers.push(
    setTimeout(() => {
      if (!state) return
      if (tote.status === 'IN_TRANSIT' || tote.status === 'REQUESTED') {
        tote.status = 'QUEUED'
        tote.inTransitAt = tote.inTransitAt ?? nowIso()
        tote.queuedAt = nowIso()
        tote.arrivalSeq = ++state.arrivalCounter
      }
    }, base + TO_QUEUED_MS),
  )
}

// (Re)seed the engine: arm 5 totes and start their arrival timers. Idempotent (safe to call on first
// access and on reset).
export function seed(): void {
  if (state) clearTimers()
  const station = pickStation()
  state = { station, sessionId: null, totes: armTotes(station), timers: [], arrivalCounter: 0 }
  state.totes.forEach((t, i) => scheduleArrival(t, i))
}

function ensure(): EngineState {
  if (!state) seed()
  return state as EngineState
}

export function reset(): void {
  seed()
}

// --- GTP endpoint handlers (return the exact gtpops/api.ts shapes) --------------------------------

export function listWorkplaces(_warehouseId: string): Workplace[] {
  const s = ensure()
  // The demo is scoped to a single warehouse, so return every snapshot workplace (the launcher looks
  // real); the demo drives the PICKING station, shown free and accepting work.
  const all = gtpWorkplaces as Workplace[]
  return all.map((w) => (w.id === s.station.id ? { ...w, inUse: false, acceptingWork: true } : w))
}

export function claimWorkplace(stationId: string): WorkplaceSession {
  const s = ensure()
  s.sessionId = uid('sess')
  return {
    sessionId: s.sessionId,
    stationId,
    operator: 'demo',
    status: 'ACTIVE',
    claimedAt: nowIso(),
    lastHeartbeatAt: nowIso(),
    workplace: { ...s.station, id: stationId, inUse: true, acceptingWork: true },
  }
}

export function heartbeat(): SessionStatus {
  return { active: true, reason: null }
}

// The induction queue slice for the station: QUEUED first (by arrivalSeq), then IN_TRANSIT, then
// REQUESTED. DONE never appears (matches the real endpoint).
export function getStationQueue(stationId: string): StationQueueEntry[] {
  const s = ensure()
  const live = s.totes.filter((t) => !t.done && t.status !== 'DONE')
  const order = { QUEUED: 0, IN_TRANSIT: 1, REQUESTED: 2, DONE: 3 } as const
  const sorted = [...live].sort((a, b) => {
    if (a.status !== b.status) return order[a.status] - order[b.status]
    return (a.arrivalSeq ?? 1e9) - (b.arrivalSeq ?? 1e9)
  })
  return sorted.map((t) => ({
    id: t.entryId,
    workplaceId: stationId,
    workplaceKind: 'GTP',
    huId: t.huId,
    huCode: t.huCode,
    skuId: t.skuId,
    skuCode: t.skuCode,
    qty: t.qty,
    mode: 'PICKING',
    status: t.status,
    arrivalSeq: t.arrivalSeq,
    requestedAt: t.requestedAt,
    inTransitAt: t.inTransitAt,
    queuedAt: t.queuedAt,
    countTaskId: null,
    countLineId: null,
  }))
}

function toteByHu(huId: string): DemoTote | undefined {
  return state?.totes.find((t) => t.huId === huId)
}
function toteByEntry(entryId: string): DemoTote | undefined {
  return state?.totes.find((t) => t.entryId === entryId)
}
function toteByPut(putId: string): DemoTote | undefined {
  return state?.totes.find((t) => t.putId === putId)
}
function toteByCycle(cycleId: string): DemoTote | undefined {
  return state?.totes.find((t) => t.cycleId === cycleId)
}

function buildCycle(stationId: string, tote: DemoTote): WorkCycle {
  const put: PutInstruction = {
    id: tote.putId,
    destinationNodeId: uid('node'),
    destinationDemandId: uid('demand'),
    orderRef: tote.orderRef,
    orderLineId: uid('line'),
    orderHuId: tote.destinationHuId,
    putLightId: `L${(tote.arrivalSeq ?? 1)}`,
    qty: tote.qty,
    confirmedQty: tote.confirmedQty ?? 0,
    status: tote.putStatus,
  }
  return {
    id: tote.cycleId as string,
    stationId,
    operatingMode: 'PICKING',
    stockNodeId: uid('stocknode'),
    stockHuId: tote.huId,
    targetHuId: tote.destinationHuId,
    skuId: tote.skuId,
    mode: 'ORDER_LOCATION',
    presentedQty: tote.qty,
    remainingQty: tote.qty - (tote.confirmedQty ?? 0),
    status: tote.putStatus === 'OPEN' ? 'OPEN' : 'COMPLETED',
    puts: [put],
    taskLines: [],
  }
}

// present: the console presents the arrived head tote's stock HU. Open a work cycle with one put.
export function presentStock(
  stationId: string,
  body: { stockHuId: string; skuId: string; qty: number },
): WorkCycle {
  ensure()
  const tote = toteByHu(body.stockHuId)
  if (!tote) throw new Error('No tote at the station for this stock HU.')
  if (!tote.cycleId) tote.cycleId = uid('cycle')
  tote.putStatus = 'OPEN'
  tote.confirmedQty = null
  return buildCycle(stationId, tote)
}

// confirm-put: full (no qty) or short (qty < requested). Records the outcome on the tote's put.
export function confirmPut(putId: string, qty?: number): PutInstruction {
  ensure()
  const tote = toteByPut(putId)
  if (!tote) throw new Error('Unknown put instruction.')
  if (qty != null && qty < tote.qty) {
    tote.confirmedQty = qty
    tote.putStatus = 'SHORT'
  } else {
    tote.confirmedQty = tote.qty
    tote.putStatus = 'CONFIRMED'
  }
  return {
    id: tote.putId,
    destinationNodeId: uid('node'),
    destinationDemandId: uid('demand'),
    orderRef: tote.orderRef,
    orderLineId: uid('line'),
    orderHuId: tote.destinationHuId,
    putLightId: `L${(tote.arrivalSeq ?? 1)}`,
    qty: tote.qty,
    confirmedQty: tote.confirmedQty ?? 0,
    status: tote.putStatus,
  }
}

// close-cycle: complete the cycle. The console then completes the queue entry (below) to advance.
export function closeCycle(cycleId: string): WorkCycle {
  ensure()
  const tote = toteByCycle(cycleId)
  if (!tote) throw new Error('Unknown cycle.')
  const cycle = buildCycle(tote.cycleId as string, tote)
  cycle.status = 'CLOSED'
  return cycle
}

// complete a queue entry: marks the tote done and removes it from the queue so the next head presents.
export function completeQueueEntry(entryId: string): StationQueueEntry {
  ensure()
  const tote = toteByEntry(entryId)
  if (!tote) throw new Error('Unknown queue entry.')
  tote.done = true
  tote.status = 'DONE'
  return {
    id: tote.entryId,
    workplaceId: state!.station.id,
    workplaceKind: 'GTP',
    huId: tote.huId,
    huCode: tote.huCode,
    skuId: tote.skuId,
    skuCode: tote.skuCode,
    qty: tote.qty,
    mode: 'PICKING',
    status: 'DONE',
    arrivalSeq: tote.arrivalSeq,
    requestedAt: tote.requestedAt,
    inTransitAt: tote.inTransitAt,
    queuedAt: tote.queuedAt,
  }
}

// --- exceptions -----------------------------------------------------------------------------------

// dirty tote: remove the head tote from the station (sent to maintenance) and mark it done.
export function markToteDirty(_stationId: string, entryId: string): void {
  ensure()
  const tote = toteByEntry(entryId)
  if (!tote) throw new Error('No tote at the station.')
  tote.done = true
  tote.status = 'DONE'
}

// broken units: post a damage adjustment. The tote stays at the station so the operator keeps working.
export function markProductBroken(_stationId: string, entryId: string, qty: number): { adjusted: number } {
  ensure()
  const tote = toteByEntry(entryId)
  if (!tote) throw new Error('No tote at the station.')
  return { adjusted: Math.max(0, Math.min(qty, tote.qty)) }
}

export function activateStation(): { acceptingWork: boolean } {
  return { acceptingWork: true }
}
export function deactivateStation(): { acceptingWork: boolean } {
  return { acceptingWork: false }
}

// Progress snapshot (optional dashboard polish / tests).
export function progress(): { total: number; picked: number; short: number; remaining: number } {
  const s = ensure()
  let picked = 0
  let short = 0
  let remaining = 0
  for (const t of s.totes) {
    if (t.done) {
      if (t.putStatus === 'SHORT') short++
      else picked++
    } else {
      remaining++
    }
  }
  return { total: s.totes.length, picked, short, remaining }
}
