// In-memory GTP pick engine for the public live demo (VITE_DEMO builds only).
//
// This is the ONLY live state in the demo. It arms a handful of totes for the pick station the
// operator has claimed, each carrying a real demo SKU (from the fixtures), a quantity and one or more
// destination puts. The totes walk REQUESTED -> IN_TRANSIT -> QUEUED on short timers so the induction
// queue visibly fills. The engine answers exactly the GTP endpoints the GtpOpsScreen console calls,
// with the exact shapes from gtpops/api.ts (Workplace, WorkplaceSession, StationQueueEntry, WorkCycle,
// PutInstruction) so the screen and the mock cannot drift.
//
// Pick layout (per ADR / config epic): the station's `pickLayout` decides how many puts a cycle has
// and how the operator screen renders the target side:
//   ONE_TO_ONE = one destination carton, one put (the classic flow).
//   ONE_TO_N   = a fixed row of N slots; a cycle fans the tote's units across a few of the slots
//                (several puts, varied qty, some slots unused), each put bound to an ORDER node.
//   PUT_WALL   = only the lit cubbies are shown; a cycle has several puts, each with a putLightId.
//
// Nothing here is persisted: a page reload re-imports the module and re-seeds from fixtures. reset()
// re-arms the current station. Claiming a different station re-seeds for that station.

import type {
  Workplace,
  WorkplaceNode,
  WorkplaceSession,
  StationQueueEntry,
  WorkCycle,
  PutInstruction,
  PickLayout,
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

function allWorkplaces(): Workplace[] {
  return gtpWorkplaces as Workplace[]
}

// The default station the demo arms before anything is claimed: the first PICKING-capable workplace.
function defaultStation(): Workplace {
  const all = allWorkplaces()
  return all.find((w) => w.supportedModes.includes('PICKING')) ?? all[0]
}

function stationById(id: string): Workplace {
  return allWorkplaces().find((w) => w.id === id) ?? defaultStation()
}

function layoutOf(station: Workplace): PickLayout {
  return station.pickLayout ?? 'ONE_TO_ONE'
}

// One put in a demo cycle: the destination + the running confirm state.
interface DemoPut {
  id: string
  destinationNodeId: string
  orderHuId: string
  putLightId: string | null
  qty: number
  confirmedQty: number
  status: PutInstruction['status']
}

// One armed tote and its derived demand. The puts array carries one (1-to-1) or several (1-to-N /
// put wall) destinations. done tracks completion of the whole tote.
interface DemoTote {
  entryId: string
  huId: string
  huCode: string
  skuId: string
  skuCode: string
  qty: number
  orderRef: string
  status: StationQueueEntry['status']
  arrivalSeq: number | null
  requestedAt: string
  inTransitAt: string | null
  queuedAt: string | null
  cycleId: string | null
  puts: DemoPut[]
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

function orderNodes(station: Workplace): WorkplaceNode[] {
  return station.nodes.filter((n) => n.role === 'ORDER').sort((a, b) => a.position - b.position)
}

// Build the puts for one tote, given the station's pick layout. The tote's total qty is split across
// the chosen destinations so the source "units to move" reads naturally.
function buildPuts(station: Workplace, tote: DemoTote, index: number): DemoPut[] {
  const layout = layoutOf(station)
  const nodes = orderNodes(station)

  if (layout === 'ONE_TO_ONE' || nodes.length === 0) {
    // Classic single carton: one put for the whole tote (unchanged behaviour).
    return [
      {
        id: uid('put'),
        destinationNodeId: nodes[0]?.id ?? uid('node'),
        orderHuId: uid('dhu'),
        putLightId: nodes[0]?.putLightId ?? null,
        qty: tote.qty,
        confirmedQty: 0,
        status: 'OPEN',
      },
    ]
  }

  // Multi-target: pick a few destinations (varied per tote so some slots/cubbies stay unused) and
  // split the tote's qty across them. Pattern lengths rotate by tote index for visible variety.
  const patterns = [
    [0, 2, 4],
    [1, 3],
    [0, 1, 5],
    [2, 6],
    [0, 3, 4, 6],
  ]
  const chosen = patterns[index % patterns.length].filter((i) => i < nodes.length)
  const targets = chosen.length ? chosen : [0]
  const qtys = splitQty(tote.qty, targets.length)
  return targets.map((nodeIdx, i) => {
    const node = nodes[nodeIdx]
    return {
      id: uid('put'),
      destinationNodeId: node.id,
      orderHuId: uid('dhu'),
      putLightId: node.putLightId,
      qty: qtys[i],
      confirmedQty: 0,
      status: 'OPEN' as const,
    }
  })
}

// Split a total into `parts` positive whole numbers (each >= 1), as even as possible.
function splitQty(total: number, parts: number): number[] {
  const n = Math.max(1, Math.min(parts, total))
  const base = Math.floor(total / n)
  const out = Array.from({ length: n }, () => Math.max(1, base))
  let used = out.reduce((s, v) => s + v, 0)
  let i = 0
  // Distribute the remainder one unit at a time.
  while (used < total) {
    out[i % n] += 1
    used += 1
    i += 1
  }
  return out
}

// Build the starting totes for a station from real demo SKUs. A couple carry a larger qty so a short
// pick reads naturally and so the multi-target split has units to spread.
function armTotes(station: Workplace): DemoTote[] {
  const skus = demoSkus()
  const qtys = [6, 5, 9, 4, 7]
  const totes: DemoTote[] = []
  for (let i = 0; i < 5; i++) {
    const sku = skus[i % skus.length]
    const tote: DemoTote = {
      entryId: uid('entry'),
      huId: uid('hu'),
      huCode: `TOTE-${String(i + 1).padStart(3, '0')}`,
      skuId: sku.id,
      skuCode: sku.code,
      qty: qtys[i],
      orderRef: `SO-${5200 + i}`,
      status: 'REQUESTED',
      arrivalSeq: null,
      requestedAt: nowIso(),
      inTransitAt: null,
      queuedAt: null,
      cycleId: null,
      puts: [],
      done: false,
    }
    tote.puts = buildPuts(station, tote, i)
    totes.push(tote)
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

// (Re)seed the engine for a station: arm 5 totes and start their arrival timers. Idempotent.
export function seed(stationId?: string): void {
  if (state) clearTimers()
  const station = stationId ? stationById(stationId) : defaultStation()
  state = { station, sessionId: null, totes: armTotes(station), timers: [], arrivalCounter: 0 }
  state.totes.forEach((t, i) => scheduleArrival(t, i))
}

function ensure(): EngineState {
  if (!state) seed()
  return state as EngineState
}

export function reset(): void {
  seed(state?.station.id)
}

// --- GTP endpoint handlers (return the exact gtpops/api.ts shapes) --------------------------------

export function listWorkplaces(_warehouseId: string): Workplace[] {
  const s = ensure()
  // The demo is scoped to a single warehouse, so return every snapshot workplace (the launcher looks
  // real); the currently-armed station is shown free and accepting work.
  return allWorkplaces().map((w) => (w.id === s.station.id ? { ...w, inUse: false, acceptingWork: true } : w))
}

export function claimWorkplace(stationId: string): WorkplaceSession {
  // Claiming a different pick station re-seeds the engine for it so its layout's totes/puts are armed.
  if (!state || state.station.id !== stationId) {
    const station = stationById(stationId)
    if (station.supportedModes.includes('PICKING')) seed(stationId)
  }
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
  return state?.totes.find((t) => t.puts.some((p) => p.id === putId))
}
function toteByCycle(cycleId: string): DemoTote | undefined {
  return state?.totes.find((t) => t.cycleId === cycleId)
}

function toPutInstruction(tote: DemoTote, p: DemoPut): PutInstruction {
  return {
    id: p.id,
    destinationNodeId: p.destinationNodeId,
    destinationDemandId: `demand-${p.id}`,
    orderRef: tote.orderRef,
    orderLineId: `line-${p.id}`,
    orderHuId: p.orderHuId,
    putLightId: p.putLightId,
    qty: p.qty,
    confirmedQty: p.confirmedQty,
    status: p.status,
  }
}

function buildCycle(stationId: string, tote: DemoTote): WorkCycle {
  const station = state?.station ?? stationById(stationId)
  const open = tote.puts.filter((p) => p.status === 'OPEN')
  const remaining = open.reduce((s, p) => s + p.qty, 0)
  return {
    id: tote.cycleId as string,
    stationId,
    operatingMode: 'PICKING',
    stockNodeId: uid('stocknode'),
    stockHuId: tote.huId,
    targetHuId: tote.puts[0]?.orderHuId ?? null,
    skuId: tote.skuId,
    mode: station.mode === 'PUT_WALL' ? 'PUT_WALL' : 'ORDER_LOCATION',
    presentedQty: tote.qty,
    remainingQty: remaining,
    status: open.length === 0 ? 'COMPLETED' : 'OPEN',
    pickLayout: layoutOf(station),
    pickSlots: station.pickSlots ?? null,
    puts: tote.puts.map((p) => toPutInstruction(tote, p)),
    taskLines: [],
  }
}

// present: the console presents the arrived head tote's stock HU. Open a work cycle with its puts.
export function presentStock(
  stationId: string,
  body: { stockHuId: string; skuId: string; qty: number },
): WorkCycle {
  ensure()
  const tote = toteByHu(body.stockHuId)
  if (!tote) throw new Error('No tote at the station for this stock HU.')
  if (!tote.cycleId) tote.cycleId = uid('cycle')
  return buildCycle(stationId, tote)
}

// confirm-put: full (no qty) or short (qty < requested). Records the outcome on the matching put.
export function confirmPut(putId: string, qty?: number): PutInstruction {
  ensure()
  const tote = toteByPut(putId)
  if (!tote) throw new Error('Unknown put instruction.')
  const put = tote.puts.find((p) => p.id === putId)
  if (!put) throw new Error('Unknown put instruction.')
  if (qty != null && qty < put.qty) {
    put.confirmedQty = qty
    put.status = 'SHORT'
  } else {
    put.confirmedQty = put.qty
    put.status = 'CONFIRMED'
  }
  return toPutInstruction(tote, put)
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
      if (t.puts.some((p) => p.status === 'SHORT')) short++
      else picked++
    } else {
      remaining++
    }
  }
  return { total: s.totes.length, picked, short, remaining }
}
