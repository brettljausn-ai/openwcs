// Live "digital twin" derivation + geometry for the Hardware visualisation page.
//
// We do NOT add a backend endpoint for v1: the live picture is derived entirely from data the UI can
// already poll — the static automation topology (geometry) plus the flow-orchestrator device-task feed
// (`GET /api/flow/device-tasks?warehouseId=…`, newest-first). From those we compute, per poll:
//   - per-equipment runtime state (idle / running / faulted)        → colour the 3D equipment
//   - the handling units currently moving through the system + where → animated totes
//   - headline counters (in-transit, queued, throughput, recircs…)  → the live stats panel
//
// IMPORTANT honesty note: the device-task feed (and the HU transport trace behind it) records named
// waypoints — a CONVEY task runs "on" a conveyor, a result decision says DIVERTED/RECIRCULATED at the
// sorter, a completed CONVEY ARRIVES at a station — NOT a continuous metre-by-metre position. So tote
// motion is approximate: the 3D layer tweens a tote between the representative points of the equipment
// its successive tasks ran on. Equipment activity, by contrast, is exact (it keys off the task's
// equipmentId, which matches a placement's equipmentId).

import type { AutomationEquipment, AutomationLevel, AutomationTopology } from '../topology/automationApi'
import type { DeviceTask } from '../transport/api'

// ----------------------------------------------------------------------------------------------------
// Types (the contract the 3D scene + page render against)
// ----------------------------------------------------------------------------------------------------

export type EquipmentRuntimeState = 'idle' | 'running' | 'faulted'

export interface EquipmentActivity {
  placedId: string // AutomationEquipment.id
  state: EquipmentRuntimeState
  activeTasks: number
  lastCommand?: string
  lastTaskId?: string
  lastTs?: string
}

export type ToteRuntimeState = 'in-transit' | 'recirculating' | 'queued' | 'done'

/** A SCANNED row from the HU transport trace, reduced to what positioning needs (ADR-0008 §3). */
export interface ScanRow {
  event: string
  point?: string | null
  toPoint?: string | null
  ts: string
}

export interface ToteView {
  huId: string
  huCode?: string | null
  state: ToteRuntimeState
  // Where the tote is when it is NOT mid-walk — a placed-equipment id the 3D layer resolves to a
  // world point (the station it queues at / the equipment of its last task). Null when unknown — the
  // scene hides the tote rather than inventing a position.
  anchorPlacedId: string | null
  /** Observed live position (ADR-0008 replay): the last scanned node, the answered next node, and the
   *  scan timestamp. The 3D layer interpolates fromXZ → toXZ at the conveyor speed (0.5 m/s) from
   *  tsMs, per frame — continuous motion between polls, derived only from observed scans. */
  scan?: { fromXZ: [number, number]; toXZ: [number, number] | null; tsMs: number } | null
  lastCommand?: string
  lastTs: string
  correlationId?: string | null
  decisions?: { point?: string; event?: string; decision?: string }[]
}

export interface TwinStats {
  inTransit: number
  queued: number
  recirculations: number
  faults: number
  throughputPerMin: number // device tasks completed in the last 60s
  byFamily: Record<string, { running: number; completed: number; failed: number }>
}

export interface TwinSnapshot {
  activityByPlacedId: Record<string, EquipmentActivity>
  totes: ToteView[]
  stats: TwinStats
}

/** A handling unit at rest in storage, placed at its cell position inside the rendered ASRS rack
 *  (ADR-0009 §5). Pure representation of the HU registry (live since PR #218). */
export interface StoredTote {
  huId: string
  huCode: string
  pos: [number, number, number]
}

/** Minimal slices of the master-data location / inventory HU shapes the rack view needs. */
export interface StorageCell {
  id: string
  aisle?: string | null
  side?: string | null // LEFT/RIGHT or L/R
  posX?: number | null // cell X along the aisle
  posY?: number | null // shuttle level
  posZ?: number | null // channel depth, 1 = aisle face
}
export interface StoredHu {
  huId?: string
  code: string
  locationId?: string | null
  status: string
}

/** Map every stored HU to a world position inside the (first) placed ASRS rack: cell X spreads along
 *  the rack length, the shuttle level (posY) stacks up the rack height, the side picks the rack
 *  flanking the aisle, and the channel depth (posZ) pushes outward from the aisle. HUs without a
 *  resolvable located cell are skipped — never guessed. */
export function deriveStoredTotes(
  hus: StoredHu[],
  cells: StorageCell[],
  topo: AutomationTopology,
): StoredTote[] {
  const rack = topo.equipment.find((e) => (e.category ?? '') === 'asrs')
  if (!rack) return []
  const cellById = new Map(cells.map((c) => [c.id, c]))
  const located = cells.filter((c) => typeof c.posX === 'number' && typeof c.posY === 'number')
  if (!located.length) return []
  const maxX = Math.max(...located.map((c) => c.posX as number), 1)
  const maxY = Math.max(...located.map((c) => c.posY as number), 1)

  const baseY = rack.posYM || 0
  const L = rack.lengthM || 1
  const W = rack.widthM || 1
  const H = rack.heightM || 1
  const yaw = ((rack.rotationDeg || 0) * Math.PI) / 180
  const cos = Math.cos(-yaw)
  const sin = Math.sin(-yaw)

  const out: StoredTote[] = []
  for (const hu of hus) {
    if (!hu.huId || !hu.locationId) continue
    const cell = cellById.get(hu.locationId)
    if (!cell || typeof cell.posX !== 'number' || typeof cell.posY !== 'number') continue
    // Local rack frame: X along the rack length, Z across (aisle in the middle, racks both sides).
    const lx = ((cell.posX + 0.5) / (maxX + 1) - 0.5) * L * 0.92
    const sideSign = (cell.side ?? '').toUpperCase().startsWith('L') ? -1 : 1
    const depth = Math.max(1, cell.posZ ?? 1)
    const lz = sideSign * (W * 0.18 + (depth - 1) * W * 0.14)
    const y = baseY + 0.35 + ((cell.posY - 0.5) / maxY) * (H * 0.82)
    // Rotate the local frame by the rack's yaw and translate to its centre.
    const wx = rack.posXM + lx * cos - lz * sin
    const wz = rack.posZM + lx * sin + lz * cos
    out.push({ huId: hu.huId, huCode: hu.code, pos: [wx, y, wz] })
  }
  return out
}

/** Optional live inputs for deriveTwin beyond the device-task feed (ADR-0008 replay). */
export interface TwinLiveInputs {
  /** Per-HU transport-trace rows (any order; only SCANNED rows are used). */
  tracesByHu?: Map<string, ScanRow[]>
  /** Routing-node code → world [x, z] (from the conveyor topology the walk runs on). */
  nodeXZ?: Map<string, [number, number]>
  /** TRUTH source for queued totes: workplace/station id → the entries actually QUEUED there (from
   *  flow's induction queue). When provided, "queued" is never inferred from stale completed CONVEY
   *  tasks — an HU whose work is DONE simply stops being shown as queued. */
  queuedByStation?: Map<string, Array<{ huId: string; huCode?: string | null }>>
}

// ----------------------------------------------------------------------------------------------------
// Geometry — placed equipment → world transform, and a point along a conveyor path
// ----------------------------------------------------------------------------------------------------
// World is the XZ plane (X right, Z depth), Y up, units metres. A box equipment sits at
// (posXM, elevation+heightM/2, posZM) rotated by rotationDeg about Y. A conveyor with a `path` carries
// absolute world-XZ waypoints, so we use them directly (Y = level elevation + a small belt height).

const BELT_Y = 0.5 // belt-surface height a tote rides at (metres above the level floor)

export interface PlacementGeom {
  id: string
  center: [number, number, number]
  yawRad: number
  size: [number, number, number] // [lengthM (local X), heightM (Y), widthM (local Z)]
  /** The placement's own vertical offset (posYM). IMPORTANT: the scene is FLOOR-RELATIVE like the
   *  topology editor — level elevation is deliberately NOT applied (the editor renders each level on
   *  the floor; adding elevationM floated the twin's overlays metres above the editor's meshes). */
  baseY: number
  category: string
  /** Conveyor centreline in world XZ (>= 2 points) when this is a path conveyor; else undefined. */
  worldPath?: Array<[number, number]>
  /** Cumulative arc length of worldPath (worldPath[i] reached at cumLen[i]); last entry = total. */
  cumLen?: number[]
  /** True when the path loops back from the last waypoint to the first (recirculating loop). */
  closed?: boolean
}

// FLOOR-RELATIVE, exactly like the topology editor's meshes (EquipmentMesh renders boxes at
// y = heightM/2 + posYM with no level elevation — see the editor's own comment that adding
// elevation floats things above the conveyor). `levels` is accepted for signature stability but
// deliberately unused.
export function placementGeom(eq: AutomationEquipment, _levels: AutomationLevel[]): PlacementGeom {
  const baseY = eq.posYM || 0
  const category = eq.category ?? 'other'
  const geom: PlacementGeom = {
    id: eq.id,
    center: [eq.posXM, baseY + (eq.heightM || 0.5) / 2, eq.posZM],
    yawRad: ((eq.rotationDeg || 0) * Math.PI) / 180,
    size: [eq.lengthM || 1, eq.heightM || 0.5, eq.widthM || 0.5],
    baseY,
    category,
  }
  const path = eq.path
  if (path && path.length >= 2) {
    const world = path.map((p) => [p[0], p[1]] as [number, number])
    const cum: number[] = [0]
    for (let i = 1; i < world.length; i++) {
      const dx = world[i][0] - world[i - 1][0]
      const dz = world[i][1] - world[i - 1][1]
      cum.push(cum[i - 1] + Math.hypot(dx, dz))
    }
    geom.worldPath = world
    geom.cumLen = cum
    geom.closed = !!eq.closed
  }
  return geom
}

/** Representative world point of a placement: a conveyor's path midpoint, else ON TOP of the box.
 *  Used as the tote anchor when we only know "the tote is on equipment X". Box equipment anchors at
 *  its top surface (not belt height) so a tote at a 1m-tall workstation sits visibly ON it rather
 *  than being swallowed inside the solid mesh. */
export function anchorPoint(geom: PlacementGeom): [number, number, number] {
  if (geom.worldPath && geom.cumLen) {
    const at = pointAlongPath(geom, 0.5)
    return at.pos
  }
  // Top surface of the box (floor-relative): baseY + height.
  return [geom.center[0], geom.baseY + (geom.size[1] || 0.5) + 0.12, geom.center[2]]
}

/** World Y a tote rides at during scan replay: the top of a standard 1m conveyor (floor-relative,
 *  matching the editor's conveyor body whose top sits at heightM). */
export const SCAN_BELT_Y = 1.0

/** ADR-0008 conveyor speed — the same 0.5 m/s the emulator walks at; used to interpolate between
 *  a scan's node and the answered next node from the scan timestamp. */
export const SCAN_SPEED_MPS = 0.5

/** Point at arc-length fraction t in [0,1] along a path conveyor (world coords + planar direction). */
export function pointAlongPath(
  geom: PlacementGeom,
  t: number,
): { pos: [number, number, number]; dir: [number, number] } {
  const path = geom.worldPath
  const cum = geom.cumLen
  if (!path || !cum || path.length < 2) {
    const c = anchorPoint(geom)
    return { pos: c, dir: [1, 0] }
  }
  const total = cum[cum.length - 1] || 1
  const target = Math.max(0, Math.min(1, t)) * total
  let i = 1
  while (i < cum.length && cum[i] < target) i++
  const a = path[i - 1]
  const b = path[i] ?? path[i - 1]
  const segLen = (cum[i] ?? cum[i - 1]) - cum[i - 1] || 1
  const f = (target - cum[i - 1]) / segLen
  const x = a[0] + (b[0] - a[0]) * f
  const z = a[1] + (b[1] - a[1]) * f
  const dx = b[0] - a[0]
  const dz = b[1] - a[1]
  const len = Math.hypot(dx, dz) || 1
  // Ride on TOP of the conveyor body (floor-relative): baseY + heightM.
  return { pos: [x, geom.baseY + (geom.size[1] || BELT_Y), z], dir: [dx / len, dz / len] }
}

// ----------------------------------------------------------------------------------------------------
// Derivation — DeviceTask[] (+ topology) → TwinSnapshot
// ----------------------------------------------------------------------------------------------------

const RUNNING_STATUSES = new Set(['REQUESTED', 'DISPATCHED', 'IN_PROGRESS', 'PENDING'])
const FAULT_WINDOW_MS = 20_000 // a FAILED task lights its equipment red for this long
const THROUGHPUT_WINDOW_MS = 60_000
const TOTE_DONE_LINGER_MS = 8_000 // keep a completed tote visible briefly, then drop it

function tsMs(s?: string | null): number {
  if (!s) return 0
  const n = Date.parse(s)
  return Number.isNaN(n) ? 0 : n
}

function payloadStr(t: DeviceTask, key: string): string | undefined {
  const v = t.payload?.[key]
  return typeof v === 'string' ? v : undefined
}

function resultDecisions(t: DeviceTask): { point?: string; event?: string; decision?: string }[] {
  const d = t.result?.['decisions']
  if (!Array.isArray(d)) return []
  return d.filter((x): x is Record<string, unknown> => !!x && typeof x === 'object').map((x) => ({
    point: typeof x.point === 'string' ? x.point : undefined,
    event: typeof x.event === 'string' ? x.event : undefined,
    decision: typeof x.decision === 'string' ? x.decision : undefined,
  }))
}

function resultRecirc(t: DeviceTask): number {
  const r = t.result?.['recirculations']
  return typeof r === 'number' ? r : 0
}

/** The handling-unit id a task belongs to (induction tasks correlate by huId; fall back to correlationId). */
function huOf(t: DeviceTask): string | undefined {
  return payloadStr(t, 'huId') ?? t.correlationId ?? undefined
}

export function deriveTwin(
  tasks: DeviceTask[],
  topo: AutomationTopology,
  nowMs: number,
  live?: TwinLiveInputs,
): TwinSnapshot {
  // equipmentId (master-data) → placement id. First placement wins if several share the equipment.
  const placedByEquipmentId = new Map<string, string>()
  for (const eq of topo.equipment) {
    if (eq.equipmentId && !placedByEquipmentId.has(eq.equipmentId)) placedByEquipmentId.set(eq.equipmentId, eq.id)
  }
  // workplace/station id → workstation placement id (destination of a CONVEY).
  const placedByStationId = new Map<string, string>()
  for (const eq of topo.equipment) {
    if (eq.stationId && !placedByStationId.has(eq.stationId)) placedByStationId.set(eq.stationId, eq.id)
  }
  // Family fallback — ACTIVITY ONLY. Flow dispatches device tasks without an equipmentId, so the
  // truthful statement "an ASRS / a conveyor task is running" is attributed to the representative
  // placement of that family (the storage placement / the longest-path conveyor). Tote POSITIONS are
  // never derived from this: they come from observed scans (ADR-0008) or real id correlation.
  let asrsPlacedId: string | null = null
  let mainConveyorPlacedId: string | null = null
  let longest = -1
  for (const eq of topo.equipment) {
    const cat = eq.category ?? ''
    if (cat === 'asrs' && !asrsPlacedId) asrsPlacedId = eq.id
    if (cat === 'conveyor') {
      const len = eq.path?.length ?? 0
      if (len > longest) {
        longest = len
        mainConveyorPlacedId = eq.id
      }
    }
  }
  const fallbackFor = (family?: string | null): string | null => {
    const f = (family ?? '').toUpperCase()
    if (f === 'ASRS' || f === 'AUTOSTORE' || f === 'AMR') return asrsPlacedId
    if (f === 'CONVEYOR') return mainConveyorPlacedId
    return null
  }
  const placedOf = (equipmentId?: string | null, family?: string | null): string | null =>
    (equipmentId && placedByEquipmentId.get(equipmentId)) || fallbackFor(family)
  /** Strict resolution for tote anchoring — no family guessing. */
  const placedOfStrict = (equipmentId?: string | null): string | null =>
    (equipmentId && placedByEquipmentId.get(equipmentId)) || null

  // Latest SCANNED row per HU → observed live position (node + answered next node + timestamp).
  const scanOf = (huId: string): ToteView['scan'] => {
    const rows = live?.tracesByHu?.get(huId)
    const nodeXZ = live?.nodeXZ
    if (!rows || !nodeXZ) return null
    let best: ScanRow | null = null
    for (const r of rows) {
      if (r.event !== 'SCANNED' || !r.point) continue
      if (!best || tsMs(r.ts) > tsMs(best.ts)) best = r
    }
    if (!best || !best.point) return null
    const from = nodeXZ.get(best.point)
    if (!from) return null
    const to = best.toPoint ? nodeXZ.get(best.toPoint) ?? null : null
    return { fromXZ: from, toXZ: to, tsMs: tsMs(best.ts) }
  }

  // --- Equipment activity -----------------------------------------------------------------------
  const activityByPlacedId: Record<string, EquipmentActivity> = {}
  const ensure = (placedId: string): EquipmentActivity =>
    (activityByPlacedId[placedId] ??= { placedId, state: 'idle', activeTasks: 0 })

  const byFamily: TwinStats['byFamily'] = {}
  let recirculations = 0
  let faults = 0
  let throughput = 0

  for (const t of tasks) {
    const fam = (byFamily[t.family] ??= { running: 0, completed: 0, failed: 0 })
    const created = tsMs(t.createdAt)
    const status = (t.status || '').toUpperCase()
    if (status === 'COMPLETED' && nowMs - created <= THROUGHPUT_WINDOW_MS) throughput++
    if (status === 'COMPLETED') fam.completed++
    if (status === 'FAILED') fam.failed++
    if (RUNNING_STATUSES.has(status)) fam.running++
    recirculations += resultRecirc(t)

    const placedId = placedOf(t.equipmentId, t.family)
    if (!placedId) continue
    const act = ensure(placedId)
    // Newest task per equipment wins for lastCommand/lastTs (tasks arrive newest-first, so only set once).
    if (!act.lastTs) {
      act.lastCommand = t.command
      act.lastTaskId = t.id
      act.lastTs = t.createdAt
    }
    if (RUNNING_STATUSES.has(status)) {
      act.activeTasks++
      if (act.state !== 'faulted') act.state = 'running'
    } else if (status === 'FAILED' && nowMs - created <= FAULT_WINDOW_MS) {
      act.state = 'faulted'
      faults++
    }
  }

  // --- Totes: latest task per HU drives state; position = observed scans or strict anchors -------
  const latestByHu = new Map<string, DeviceTask>()
  for (const t of tasks) {
    // tasks are newest-first, so the first time we see a HU it's the latest.
    const hu = huOf(t)
    if (hu && !latestByHu.has(hu)) latestByHu.set(hu, t)
  }

  const totes: ToteView[] = []
  let inTransit = 0
  let queued = 0
  for (const [hu, t] of latestByHu) {
    const status = (t.status || '').toUpperCase()
    const created = tsMs(t.createdAt)
    const decisions = resultDecisions(t)
    const held = decisions.some((d) => d.event === 'RECIRCULATED' || d.event === 'HELD') || resultRecirc(t) > 0
    const destWp = payloadStr(t, 'destinationWorkplaceId')

    let state: ToteRuntimeState
    let anchorPlacedId: string | null = null
    let scan: ToteView['scan'] = null
    if (RUNNING_STATUSES.has(status)) {
      state = held ? 'recirculating' : 'in-transit'
      // Position comes from the observed scan trail (ADR-0008 replay). Without scans (atomic mode /
      // un-projected warehouse) the tote has no honest position mid-transit: anchor strictly by the
      // task's real equipmentId or not at all — never by family guess.
      scan = scanOf(hu)
      if (!scan) anchorPlacedId = placedOfStrict(t.equipmentId)
      inTransit++
    } else if (status === 'COMPLETED') {
      if (live?.queuedByStation) {
        // The real induction queue is the truth for "queued" (added below) — a completed CONVEY only
        // earns a brief done-linger at its last observed position, then disappears. This kills the
        // phantom "queued forever" totes whose work finished before the return leg existed.
        if (nowMs - created > TOTE_DONE_LINGER_MS) continue
        state = 'done'
        scan = scanOf(hu)
        if (!scan) anchorPlacedId = placedOfStrict(t.equipmentId)
      } else if (destWp && placedByStationId.has(destWp)) {
        // No queue data available — fall back to inferring queued from the arrival task.
        state = 'queued'
        anchorPlacedId = placedByStationId.get(destWp) ?? null
        queued++
      } else if (nowMs - created <= TOTE_DONE_LINGER_MS) {
        state = 'done'
        scan = scanOf(hu)
        if (!scan) anchorPlacedId = placedOfStrict(t.equipmentId)
      } else {
        continue // stale completed task — the tote has left the system, don't render it
      }
    } else {
      // FAILED / unknown — skip (the equipment fault shows separately).
      continue
    }

    if (!anchorPlacedId && !scan) continue // no observed position — hide rather than invent

    totes.push({
      huId: hu,
      huCode: payloadStr(t, 'huCode') ?? null,
      state,
      anchorPlacedId,
      scan,
      lastCommand: t.command,
      lastTs: t.createdAt,
      correlationId: t.correlationId ?? null,
      decisions: decisions.length ? decisions : undefined,
    })
  }

  // Queued totes from the REAL induction queue (per workstation), replacing any task-derived view of
  // the same HU. This is pure representation: flow's queue says the tote is physically at the station.
  if (live?.queuedByStation) {
    for (const [stationId, entries] of live.queuedByStation) {
      const placedId = placedByStationId.get(stationId)
      if (!placedId) continue
      for (const e of entries) {
        queued++
        const existing = totes.findIndex((tv) => tv.huId === e.huId)
        const view: ToteView = {
          huId: e.huId,
          huCode: e.huCode ?? null,
          state: 'queued',
          anchorPlacedId: placedId,
          scan: null,
          lastTs: new Date(nowMs).toISOString(),
          correlationId: e.huId,
        }
        if (existing >= 0) {
          const prior = totes[existing]
          // A RUNNING task (the return leg starting) outranks the queue row; otherwise queue wins.
          if (prior.state !== 'in-transit' && prior.state !== 'recirculating') totes[existing] = view
        } else {
          totes.push(view)
        }
      }
    }
  }

  return {
    activityByPlacedId,
    totes,
    stats: { inTransit, queued, recirculations, faults, throughputPerMin: throughput, byFamily },
  }
}
