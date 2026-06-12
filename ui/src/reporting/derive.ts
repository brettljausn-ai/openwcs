// Pure derivations for the reporting heatmaps: kept OUT of the three.js chunk (screens compute
// totals/legends without loading 3D) and free of React.
//
// IMPORTANT: this module must not import AutomationTopology3D (it would drag three.js into the
// main bundle). The one-line conveyor classifier is therefore restated here, exactly like twin.ts
// stays three-free.

import type { AutomationEquipment, AutomationTopology } from '../topology/automationApi'
import type { Equipment, Location } from '../masterdata/api'
import { deriveStoredTotes, type StorageCell } from '../hardwaretwin/twin'
import type { HeatCell } from './ReportScene3D'
import type { StorageMovementRow, TrafficRow } from './api'
import { logT } from './heat'

/** Same classification the editor/twin use: library family CONVEYOR, or the denormalised category. */
function isConveyorPlacement(eq: AutomationEquipment, lib: Map<string, Equipment>): boolean {
  const meta = eq.equipmentId ? lib.get(eq.equipmentId) : undefined
  if (meta) return (meta.family ?? '').toUpperCase() === 'CONVEYOR'
  return (eq.category ?? '') === 'conveyor'
}

/** Distance from point p to segment a-b in the XZ plane. */
function segDist(p: [number, number], a: [number, number], b: [number, number]): number {
  const abx = b[0] - a[0]
  const abz = b[1] - a[1]
  const len2 = abx * abx + abz * abz
  let t = 0
  if (len2 > 0) t = Math.max(0, Math.min(1, ((p[0] - a[0]) * abx + (p[1] - a[1]) * abz) / len2))
  const dx = p[0] - (a[0] + abx * t)
  const dz = p[1] - (a[1] + abz * t)
  return Math.hypot(dx, dz)
}

/** A conveyor's centreline in world XZ: its path waypoints, or the rotated box's long axis. */
function centreline(eq: AutomationEquipment): Array<[number, number]> {
  const path = (eq.path ?? []) as number[][]
  if (path.length >= 2) return path.map((p) => [p[0], p[1]])
  // Box mode: rotation about Y by rotationDeg maps local +X to world (cos, -sin).
  const yaw = ((eq.rotationDeg || 0) * Math.PI) / 180
  const hx = ((eq.lengthM || 1) / 2) * Math.cos(yaw)
  const hz = -((eq.lengthM || 1) / 2) * Math.sin(yaw)
  return [
    [eq.posXM - hx, eq.posZM - hz],
    [eq.posXM + hx, eq.posZM + hz],
  ]
}

/** An edge midpoint farther than this from every conveyor centreline stays unattributed. */
const ATTRIBUTION_RADIUS_M = 4

export interface TrafficAttribution {
  /** placedId → total transports over the window. */
  totalsByPlaced: Map<string, number>
  /** placedId → log-scaled heat t in [0,1] (every placed conveyor present, 0 when no traffic). */
  heatByPlaced: Map<string, number>
  /** Busiest conveyor's total (legend max). */
  maxPerConveyor: number
  /** Total edge transports in the window. */
  edgeTotal: number
  /** Transports on edges whose nodes carry no usable position (not paintable on the scene). */
  unattributed: number
}

/**
 * Paintable traffic per conveyor: each routing edge's window total is attributed to the placed
 * conveyor nearest its midpoint (node positions come from the projected routing graph, which
 * stages path waypoints as node positions, so edges sit on their conveyor's centreline).
 */
export function attributeTraffic(
  rows: TrafficRow[],
  nodeXZ: Map<string, [number, number]>,
  topo: AutomationTopology,
  lib: Map<string, Equipment>,
): TrafficAttribution {
  const conveyors = topo.equipment
    .filter((eq) => isConveyorPlacement(eq, lib))
    .map((eq) => ({ id: eq.id, line: centreline(eq) }))

  // Sum the window per edge first (rows are per day).
  const perEdge = new Map<string, { from: string; to: string; count: number }>()
  let edgeTotal = 0
  for (const r of rows) {
    edgeTotal += r.count
    const key = `${r.fromNode}→${r.toNode}`
    const e = perEdge.get(key) ?? { from: r.fromNode, to: r.toNode, count: 0 }
    e.count += r.count
    perEdge.set(key, e)
  }

  const totalsByPlaced = new Map<string, number>()
  let unattributed = 0
  for (const e of perEdge.values()) {
    const a = nodeXZ.get(e.from)
    const b = nodeXZ.get(e.to)
    const mid: [number, number] | null = a && b ? [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2] : a ?? b ?? null
    if (!mid) {
      unattributed += e.count
      continue
    }
    let best: string | null = null
    let bestD = ATTRIBUTION_RADIUS_M
    for (const c of conveyors) {
      for (let i = 1; i < c.line.length; i++) {
        const d = segDist(mid, c.line[i - 1], c.line[i])
        if (d < bestD) {
          bestD = d
          best = c.id
        }
      }
    }
    if (!best) {
      unattributed += e.count
      continue
    }
    totalsByPlaced.set(best, (totalsByPlaced.get(best) ?? 0) + e.count)
  }

  const maxPerConveyor = Math.max(0, ...totalsByPlaced.values())
  const heatByPlaced = new Map<string, number>()
  for (const c of conveyors) heatByPlaced.set(c.id, logT(totalsByPlaced.get(c.id) ?? 0, maxPerConveyor))

  return { totalsByPlaced, heatByPlaced, maxPerConveyor, edgeTotal, unattributed }
}

export interface MovementCells {
  cells: HeatCell[]
  /** Busiest cell's stores+retrieves (legend max). */
  maxPerCell: number
  /** Movements on locations without a resolvable rack-cell position. */
  unplaced: number
}

/**
 * Storage movements → heat boxes at rack-cell world positions. Reuses the hardware twin's
 * stored-tote cell mapping (deriveStoredTotes) so the heat cells sit exactly where the twin
 * renders the stored totes.
 */
export function movementCells(
  rows: StorageMovementRow[],
  locations: Location[],
  topo: AutomationTopology,
): MovementCells {
  const perLocation = new Map<string, number>()
  for (const r of rows) perLocation.set(r.locationId, (perLocation.get(r.locationId) ?? 0) + r.stores + r.retrieves)

  const cells: StorageCell[] = locations.map((l) => ({
    id: l.id ?? '',
    aisle: l.aisle,
    side: l.side,
    posX: l.posX,
    posY: l.posY,
    posZ: l.posZ,
  }))
  // One pseudo-HU per moved location lets deriveStoredTotes resolve the cell's world position.
  const pseudo = [...perLocation.keys()].map((locationId) => ({
    huId: locationId,
    code: locationId,
    locationId,
    status: 'ACTIVE',
  }))
  const placed = deriveStoredTotes(pseudo, cells, topo)

  const maxPerCell = Math.max(0, ...perLocation.values())
  const out: HeatCell[] = placed.map((p) => ({ pos: p.pos, t: logT(perLocation.get(p.huId) ?? 0, maxPerCell) }))
  const placedIds = new Set(placed.map((p) => p.huId))
  let unplaced = 0
  for (const [loc, n] of perLocation) if (!placedIds.has(loc)) unplaced += n

  return { cells: out, maxPerCell, unplaced }
}
