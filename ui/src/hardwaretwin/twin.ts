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

export interface ToteView {
  huId: string
  huCode?: string | null
  state: ToteRuntimeState
  // Where the tote is now / where it came from — both as placed-equipment ids the 3D layer resolves to
  // a world point. `anchorPlacedId` may be null when the tote's equipment isn't placed in the topology
  // (the scene then parks it at the induction origin / hides it).
  anchorPlacedId: string | null
  prevPlacedId: string | null
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
  /** The placement id of the "main" conveyor (longest path) — the 3D layer glides totes along it. */
  mainConveyorPlacedId: string | null
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
  elevationM: number
  category: string
  /** Conveyor centreline in world XZ (>= 2 points) when this is a path conveyor; else undefined. */
  worldPath?: Array<[number, number]>
  /** Cumulative arc length of worldPath (worldPath[i] reached at cumLen[i]); last entry = total. */
  cumLen?: number[]
  /** True when the path loops back from the last waypoint to the first (recirculating loop). */
  closed?: boolean
}

function levelElevation(eq: AutomationEquipment, levels: AutomationLevel[]): number {
  const lvl = levels.find((l) => l.id === eq.levelId)
  return lvl ? lvl.elevationM : 0
}

export function placementGeom(eq: AutomationEquipment, levels: AutomationLevel[]): PlacementGeom {
  const elevationM = levelElevation(eq, levels)
  const category = eq.category ?? 'other'
  const geom: PlacementGeom = {
    id: eq.id,
    center: [eq.posXM, elevationM + (eq.heightM || 0.5) / 2, eq.posZM],
    yawRad: ((eq.rotationDeg || 0) * Math.PI) / 180,
    size: [eq.lengthM || 1, eq.heightM || 0.5, eq.widthM || 0.5],
    elevationM,
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
  return [geom.center[0], geom.elevationM + (geom.size[1] || 0.5) + 0.12, geom.center[2]]
}

/** Waypoints (world, belt height) along a conveyor's path from the vertex nearest `from` to the
 *  vertex nearest `to`, walking the path in index order (wrapping when `closed`). Lets the 3D layer
 *  glide a tote ALONG the conveyor between two equipment anchors instead of cutting straight across
 *  the floor. Returns [] when the geom has no usable path. */
export function routeAlong(
  geom: PlacementGeom,
  from: [number, number],
  to: [number, number],
  closed = false,
): Array<[number, number, number]> {
  const path = geom.worldPath
  if (!path || path.length < 2) return []
  const nearest = (p: [number, number]): number => {
    let best = 0
    let bestD = Infinity
    for (let i = 0; i < path.length; i++) {
      const d = (path[i][0] - p[0]) ** 2 + (path[i][1] - p[1]) ** 2
      if (d < bestD) {
        bestD = d
        best = i
      }
    }
    return best
  }
  const a = nearest(from)
  const b = nearest(to)
  const y = geom.elevationM + BELT_Y
  const out: Array<[number, number, number]> = []
  if (a === b) return [[path[a][0], y, path[a][1]]]
  if (closed) {
    // Walk forward with wrap-around (conveyor loops run one way).
    for (let i = a; ; i = (i + 1) % path.length) {
      out.push([path[i][0], y, path[i][1]])
      if (i === b) break
    }
  } else {
    const step = a < b ? 1 : -1
    for (let i = a; ; i += step) {
      out.push([path[i][0], y, path[i][1]])
      if (i === b) break
    }
  }
  return out
}

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
  return { pos: [x, geom.elevationM + BELT_Y, z], dir: [dx / len, dz / len] }
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

export function deriveTwin(tasks: DeviceTask[], topo: AutomationTopology, nowMs: number): TwinSnapshot {
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
  // Family fallback: today the flow dispatches device tasks WITHOUT an equipmentId (by family only),
  // so direct correlation never matches. Until the backend attributes equipment, anchor a task on the
  // representative placement of its family: ASRS/AUTOSTORE/AMR → the (first) storage placement;
  // CONVEYOR → the conveyor with the LONGEST path (the main loop).
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

  // --- Totes: latest task per HU drives state + anchor ------------------------------------------
  const latestByHu = new Map<string, DeviceTask>()
  const prevByHu = new Map<string, DeviceTask>() // the task before the latest (for the tween origin)
  for (const t of tasks) {
    // tasks are newest-first, so the first time we see a HU it's the latest; the second is the previous.
    const hu = huOf(t)
    if (!hu) continue
    if (!latestByHu.has(hu)) latestByHu.set(hu, t)
    else if (!prevByHu.has(hu)) prevByHu.set(hu, t)
  }

  const totes: ToteView[] = []
  let inTransit = 0
  let queued = 0
  for (const [hu, t] of latestByHu) {
    const status = (t.status || '').toUpperCase()
    const created = tsMs(t.createdAt)
    const decisions = resultDecisions(t)
    const recirculated = decisions.some((d) => d.event === 'RECIRCULATED') || resultRecirc(t) > 0
    const destWp = payloadStr(t, 'destinationWorkplaceId')
    const prev = prevByHu.get(hu)
    const prevPlacedId = placedOf(prev?.equipmentId, prev?.family)

    let state: ToteRuntimeState
    let anchorPlacedId: string | null
    if (RUNNING_STATUSES.has(status)) {
      state = recirculated ? 'recirculating' : 'in-transit'
      anchorPlacedId = placedOf(t.equipmentId, t.family)
      inTransit++
    } else if (status === 'COMPLETED') {
      // A completed CONVEY with a destination => the tote has arrived and is queued at that station.
      if (destWp && placedByStationId.has(destWp)) {
        state = 'queued'
        anchorPlacedId = placedByStationId.get(destWp) ?? null
        queued++
      } else if (nowMs - created <= TOTE_DONE_LINGER_MS) {
        state = 'done'
        anchorPlacedId = placedOf(t.equipmentId, t.family)
      } else {
        continue // stale completed task — the tote has left the system, don't render it
      }
    } else {
      // FAILED / unknown — skip (the equipment fault shows separately).
      continue
    }

    totes.push({
      huId: hu,
      huCode: payloadStr(t, 'huCode') ?? null,
      state,
      anchorPlacedId,
      prevPlacedId: prevPlacedId && prevPlacedId !== anchorPlacedId ? prevPlacedId : null,
      lastCommand: t.command,
      lastTs: t.createdAt,
      correlationId: t.correlationId ?? null,
      decisions: decisions.length ? decisions : undefined,
    })
  }

  return {
    activityByPlacedId,
    totes,
    stats: { inTransit, queued, recirculations, faults, throughputPerMin: throughput, byFamily },
    mainConveyorPlacedId,
  }
}
