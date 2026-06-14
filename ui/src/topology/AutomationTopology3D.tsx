import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Canvas, type ThreeEvent } from '@react-three/fiber'
import { Grid, Html, Line, OrbitControls, PivotControls, RoundedBox, Text } from '@react-three/drei'
import * as THREE from 'three'
import Select, { type SelectOption } from '../ui/Select'
import InfoTip from '../ui/InfoTip'
import { useT } from '../i18n/useT'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useAuth } from '../auth/AuthContext'
import {
  listEquipment,
  listStorageBlocks,
  updateStorageBlock,
  type Equipment,
  type StorageBlock,
} from '../masterdata/api'
import { listStations, type Station } from '../gtpconfig/api'
import {
  loadAutomationTopology,
  projectRoutingGraph,
  saveAutomationTopology,
  type AutomationConnection,
  type AutomationEquipment,
  type AutomationFunctionPoint,
  type AutomationLevel,
} from './automationApi'
import PlanEditor2D from './PlanEditor2D'
import { loadTopology, type Topology } from './api'
import { findRoute, routeVerdict, RouteTestOverlay, type RouteResult } from './RouteTest'
import {
  ADJACENCY_M,
  computeMeetingPoints,
  equipmentNodes,
  linkCandidates,
  nodeLinkStatuses,
  type EquipNode,
  type MeetingPoint,
} from './nodeLinks'

const DEG = Math.PI / 180

// Snap radius (metres): a ground/plan click this close to an existing path point reuses that point
// (so junctions form) rather than creating a near-duplicate vertex. Shared by 3D + 2D editors.
export const SNAP_M = 0.5
// When a divert branch's stub endpoint lands within this distance (m) of ANOTHER conveyor's
// centreline, it snaps exactly onto that line so the divert meets the existing conveyor at one shared
// coordinate (rather than overlaying a parallel/offset stub).
const DIVERT_MERGE_M = 0.75
// Render width (m) for an IN/OUT conveyor stub owned by a non-conveyor (e.g. an ASRS). The stub is
// physically a piece of conveyor — it just wears the host's colour — so it draws at a conveyor width,
// NOT the host's footprint width (a 5 m ASRS rack would otherwise render as a giant blob).
export const STUB_WIDTH_M = 0.6
// Render height (m) for an IN/OUT conveyor stub owned by a non-conveyor (e.g. an ASRS). A conveyor-
// like height so the stub is a low flat piece near the floor, not a wall as tall as the rack.
export const STUB_HEIGHT_M = 0.5

// The process types a function point can carry. Used as the default Select options when the
// placed equipment's library entry doesn't declare its own `processTypes`.
export const FUNCTION_TYPES = [
  'SCAN',
  'LABEL_APPLICATOR',
  'DIVERT_LEFT',
  'DIVERT_RIGHT',
  'DWS',
  'QUERY_POINT',
  'WRAPPER',
  'INDUCT',
  'DISCHARGE',
  'INFEED',
] as const

// A function point can carry a COMBINATION of functions (e.g. SCAN + DIVERT_LEFT), stored in the
// single `functionType` string as a comma-separated set the backend just round-trips. These two
// helpers convert between the string and a clean list.
//
// fpFunctions: split on comma, trim, drop empties, dedupe (preserving first-seen order).
export function fpFunctions(functionType: string): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const raw of (functionType ?? '').split(',')) {
    const t = raw.trim()
    if (t && !seen.has(t)) {
      seen.add(t)
      out.push(t)
    }
  }
  return out
}

// joinFunctions: dedupe and join with commas, in a stable order (by FUNCTION_TYPES position, with
// any unknown types appended in their given order). Inverse of fpFunctions for storage.
export function joinFunctions(list: string[]): string {
  const seen = new Set<string>()
  const clean: string[] = []
  for (const raw of list) {
    const t = (raw ?? '').trim()
    if (t && !seen.has(t)) {
      seen.add(t)
      clean.push(t)
    }
  }
  const order = (t: string) => {
    const i = (FUNCTION_TYPES as readonly string[]).indexOf(t)
    return i === -1 ? FUNCTION_TYPES.length : i
  }
  clean.sort((a, b) => order(a) - order(b))
  return clean.join(',')
}

// True for the divert function types (left/right).
function isDivertType(t: string): boolean {
  return t === 'DIVERT_LEFT' || t === 'DIVERT_RIGHT'
}

// True for the port-style function types (rendered as a diamond): induct / discharge / infeed.
function isPortType(t: string): boolean {
  return t === 'INDUCT' || t === 'DISCHARGE' || t === 'INFEED'
}

// Short marker labels per function type for the 3D <Html> tag. Falls back to the raw type.
export const FUNCTION_SHORT: Record<string, string> = {
  SCAN: 'SCAN',
  LABEL_APPLICATOR: 'LBL',
  DIVERT_LEFT: '◀ DIV',
  DIVERT_RIGHT: 'DIV ▶',
  DWS: 'DWS',
  QUERY_POINT: 'QRY',
  WRAPPER: 'WRAP',
  INDUCT: '▶ IN',
  DISCHARGE: 'OUT ▶',
  INFEED: '⇥ FEED',
}

// Distinct marker colour per function type, so points read at a glance in the scene.
export function functionColor(type: string): string {
  switch (type) {
    case 'SCAN':
      return '#5ec8e0'
    case 'LABEL_APPLICATOR':
      return '#e0c45e'
    case 'DIVERT_LEFT':
    case 'DIVERT_RIGHT':
      return '#e07a5e'
    case 'DWS':
      return '#b65ee0'
    case 'QUERY_POINT':
      return '#5ee08a'
    case 'WRAPPER':
      return '#e05e9c'
    case 'INDUCT':
      return '#8DC63F'
    case 'DISCHARGE':
      return '#f0a85a'
    case 'INFEED':
      return '#5ee0c8'
    default:
      return '#cfd8d2'
  }
}

// Representative colour for a SET of functions: a divert wins (red), then a port colour, else the
// first function's colour. Empty falls back to functionColor's default.
export function functionColorForSet(list: string[]): string {
  if (list.some(isDivertType)) return '#e0563f'
  const port = list.find(isPortType)
  if (port) return functionColor(port)
  return functionColor(list[0] ?? '')
}

// Combined short label for a SET of functions, e.g. "SCAN · ◀ DIV". Empty → ''.
export function functionShortForSet(list: string[]): string {
  return list.map((t) => FUNCTION_SHORT[t] ?? t).join(' · ')
}

// True when a placed item's library entry marks it as an ASRS-style storage system that a
// storage block can be bound to (so we offer the storage-area linking panel).
function isAsrs(eq: AutomationEquipment, lib: Map<string, Equipment>): boolean {
  const meta = eq.equipmentId ? lib.get(eq.equipmentId) : undefined
  const family = (meta?.family ?? '').toUpperCase()
  const type = (meta?.type ?? '').toUpperCase()
  return family === 'ASRS' || family === 'AUTOSTORE' || family === 'AMR' || type === 'ASRS'
}

// Position of a point at `offsetM` along an equipment, returned in level-local world XZ plus the
// unit direction (dx,dz) of travel at that offset (used to push the marker to a side). For a
// polyline conveyor this walks the path by arc-length; for a box it projects along the box length
// from its start endpoint (posX/posZ − length/2 rotated by yaw).
export function pointAlong(
  eq: AutomationEquipment,
  offsetM: number,
): { x: number; z: number; dx: number; dz: number } {
  if (Array.isArray(eq.path) && eq.path.length >= 2) {
    const path = eq.path
    const pairCount = eq.closed ? path.length : path.length - 1
    let remaining = Math.max(0, offsetM)
    let last = { x: path[0][0], z: path[0][1], dx: 1, dz: 0 }
    for (let i = 0; i < pairCount; i++) {
      const a = path[i]
      const b = path[(i + 1) % path.length]
      const dx = b[0] - a[0]
      const dz = b[1] - a[1]
      const segLen = Math.hypot(dx, dz)
      if (segLen < 1e-6) continue
      const ux = dx / segLen
      const uz = dz / segLen
      if (remaining <= segLen) {
        return { x: a[0] + ux * remaining, z: a[1] + uz * remaining, dx: ux, dz: uz }
      }
      remaining -= segLen
      last = { x: b[0], z: b[1], dx: ux, dz: uz }
    }
    return last
  }
  // Straight box: start endpoint is posX/posZ − (length/2) along yaw; walk forward by offset.
  const yaw = eq.rotationDeg * DEG
  const ux = Math.cos(yaw)
  const uz = Math.sin(yaw)
  const startX = eq.posXM - (ux * eq.lengthM) / 2
  const startZ = eq.posZM - (uz * eq.lengthM) / 2
  const t = Math.min(Math.max(offsetM, 0), eq.lengthM)
  return { x: startX + ux * t, z: startZ + uz * t, dx: ux, dz: uz }
}

// Three muted, distinct colours keyed off the equipment family/type. Anything that isn't a
// recognisable conveyor / storage(ASRS) / sorter falls back to a neutral slate.
export function colorFor(eq: AutomationEquipment, lib: Map<string, Equipment>): string {
  if (eq.stationId) return '#3ea66a' // green — GTP workstation
  const meta = eq.equipmentId ? lib.get(eq.equipmentId) : undefined
  const key = `${meta?.family ?? ''} ${meta?.type ?? ''} ${meta?.subtype ?? ''} ${eq.code}`.toLowerCase()
  if (/conveyor|roller|belt|transport/.test(key)) return '#4f8a8b' // teal — transport
  if (/asrs|storage|shuttle|rack|crane|stacker|autostore/.test(key)) return '#7a6cc0' // violet — storage
  if (/sort|divert|merge|switch/.test(key)) return '#c08a4f' // amber — sortation
  return '#6b7a85' // slate — other
}

// The solid body colour a category's 3D mesh uses (the mid-blue ASRS rack, burnt-orange manual rack,
// amber sorter). Used to TINT a non-conveyor's IN/OUT conveyor stubs so they render in the same
// colour as the equipment they belong to (rather than the default light-blue conveyor look). Returns
// null for a real conveyor (stubs there keep the default conveyor body) and the `other` bucket.
export function categoryBodyColor(cat: EquipmentCategory): string | null {
  switch (cat) {
    case 'asrs':
      return '#1E88E5' // mid-blue — matches the ASRS rack frame
    case 'manual-storage':
      return '#C75B12' // burnt-orange — matches the manual-storage rack frame
    case 'sorter':
      return '#E0A33A' // amber — matches the sorter box
    case 'workstation':
      return '#3ea66a' // green — GTP workstation
    default:
      return null
  }
}

function equipmentTypeLabel(meta?: Equipment): string {
  if (!meta) return 'Equipment'
  return meta.type || meta.subtype || meta.family || 'Equipment'
}

// A placement is a conveyor (polyline-capable) when its library family is CONVEYOR. That family
// covers straight conveyors, curves, and sorters. Everything else stays a single box.
export function isConveyor(eq: AutomationEquipment, lib: Map<string, Equipment>): boolean {
  const meta = eq.equipmentId ? lib.get(eq.equipmentId) : undefined
  return (meta?.family ?? '').toUpperCase() === 'CONVEYOR'
}

// Coarse visual category for a placed item, derived from its library family/type. Drives which 3D
// body is drawn (two-tone conveyor body, mid-blue rack, burnt-orange rack, amber sorter box).
//   conveyor       — family CONVEYOR with a *_CONVEYOR type (or a conveyor-ish type).
//   sorter         — type SORTER.
//   asrs           — automated storage: family ASRS/AUTOSTORE/AMR, or type ASRS.
//   manual-storage — anything else recognisable (generic/other storage equipment).
//   other          — fallback bucket (rendered like manual-storage).
export type EquipmentCategory = 'conveyor' | 'asrs' | 'manual-storage' | 'sorter' | 'workstation' | 'other'

export function category(eq: AutomationEquipment, lib: Map<string, Equipment>): EquipmentCategory {
  // A GTP workstation is identified by its station reference (it has no master-data equipment).
  if (eq.stationId || eq.category === 'workstation') return 'workstation'
  const meta = eq.equipmentId ? lib.get(eq.equipmentId) : undefined
  const family = (meta?.family ?? '').toUpperCase()
  const type = (meta?.type ?? '').toUpperCase()
  if (type === 'SORTER') return 'sorter'
  if (family === 'ASRS' || family === 'AUTOSTORE' || family === 'AMR' || type === 'ASRS') return 'asrs'
  if (family === 'CONVEYOR' || /_CONVEYOR$/.test(type)) return 'conveyor'
  // Generic / unrecognised equipment is treated as manual storage. `other` stays a distinct bucket
  // for anything we can't classify at all (no library meta), rendered like manual storage.
  if (meta) return 'manual-storage'
  return 'other'
}

// A usable polyline needs at least two waypoints.
function hasPath(eq: AutomationEquipment): boolean {
  return Array.isArray(eq.path) && eq.path.length >= 2
}

// True when a placed item carries any path waypoints at all (even one) — i.e. it already owns, or is
// in the middle of growing, conveyor stubs. An ASRS uses this to opt into the conveyor path tools.
export function hasAnyPath(eq: AutomationEquipment): boolean {
  return Array.isArray(eq.path) && eq.path.length >= 1
}

// True when this placed item should be edited with the conveyor path tools: either a real conveyor,
// or an ASRS that owns (or is being given) IN/OUT conveyor stubs. Shared by 3D + 2D gates.
export function canEditPath(eq: AutomationEquipment, lib: Map<string, Equipment>): boolean {
  if (isConveyor(eq, lib)) return true
  return category(eq, lib) === 'asrs' && hasAnyPath(eq)
}

// Snap a world point (px,pz) onto the nearest point of a box-footprint perimeter for a placed item,
// using its centre (posXM/posZM), length/width and yaw (rotationDeg). Returns the snapped world XZ
// plus the OUTWARD unit normal at that edge (perpendicular to the edge, pointing away from centre) —
// used to push an ASRS IN/OUT stub 1 m perpendicular out of the rack. We project the point into the
// box's local frame, clamp to the rectangle's border (the nearer of the two axes is pinned to its
// edge), then rotate back to world.
export function snapToFootprintPerimeter(
  eq: AutomationEquipment,
  px: number,
  pz: number,
): { x: number; z: number; nx: number; nz: number } {
  const yaw = eq.rotationDeg * DEG
  const cos = Math.cos(yaw)
  const sin = Math.sin(yaw)
  // World → local (rotate by −yaw about the centre). Local +X is along length, +Z along width.
  const rx = px - eq.posXM
  const rz = pz - eq.posZM
  let lx = rx * cos + rz * sin
  let lz = -rx * sin + rz * cos
  const hx = eq.lengthM / 2
  const hz = eq.widthM / 2
  // Clamp to the rectangle, then pin whichever axis is closest to its edge to that edge (so the
  // result sits ON the perimeter), and record the outward local normal for that edge.
  const cx = Math.min(hx, Math.max(-hx, lx))
  const cz = Math.min(hz, Math.max(-hz, lz))
  const dToXEdge = hx - Math.abs(cx)
  const dToZEdge = hz - Math.abs(cz)
  let nlx = 0
  let nlz = 0
  if (dToXEdge <= dToZEdge) {
    const sgn = cx >= 0 ? 1 : -1
    lx = sgn * hx
    lz = cz
    nlx = sgn
  } else {
    const sgn = cz >= 0 ? 1 : -1
    lz = sgn * hz
    lx = cx
    nlz = sgn
  }
  // Local → world.
  const wx = eq.posXM + lx * cos - lz * sin
  const wz = eq.posZM + lx * sin + lz * cos
  const nx = nlx * cos - nlz * sin
  const nz = nlx * sin + nlz * cos
  const nlen = Math.hypot(nx, nz) || 1
  return { x: +wx.toFixed(3), z: +wz.toFixed(3), nx: nx / nlen, nz: nz / nlen }
}

// The directed sections of a conveyor, as `[fromIdx, toIdx]` pairs into `eq.path`. When the
// equipment carries explicit `sections` we use them; otherwise (legacy / freshly-seeded path) we
// derive implicit sequential sections from the path (i → i+1, plus last → first when closed),
// so old path-only conveyors keep rendering exactly as before — now with travel arrows.
export function effectiveSections(eq: AutomationEquipment): number[][] {
  const path = Array.isArray(eq.path) ? eq.path : []
  if (path.length < 2) return []
  if (Array.isArray(eq.sections) && eq.sections.length > 0) {
    // Keep only sections whose endpoints are valid, distinct path indices.
    return eq.sections.filter(
      (s) =>
        Array.isArray(s) &&
        s.length === 2 &&
        s[0] >= 0 &&
        s[1] >= 0 &&
        s[0] < path.length &&
        s[1] < path.length &&
        s[0] !== s[1],
    )
  }
  const seq: number[][] = []
  const pairCount = eq.closed ? path.length : path.length - 1
  for (let i = 0; i < pairCount; i++) seq.push([i, (i + 1) % path.length])
  return seq
}

// Indices of path points that are the `from` of 2+ sections — automatic decision / divert points.
export function decisionPoints(sections: number[][]): Set<number> {
  const fromCount = new Map<number, number>()
  for (const [f] of sections) fromCount.set(f, (fromCount.get(f) ?? 0) + 1)
  const out = new Set<number>()
  for (const [idx, n] of fromCount) if (n >= 2) out.add(idx)
  return out
}

// Remove the branch a divert function point spawned: find the junction at the point's offset, drop
// the outgoing section that runs most perpendicular to the through-line (the divert branch), then
// cascade-remove any sections/points that become unreachable from the conveyor start, and GC the
// orphaned points (re-indexing). Returns the equipment unchanged when there's no branch to remove.
// When `side` is given (the FP's LEFT/RIGHT), branches on that side are strongly preferred — so a
// junction carrying BOTH a left and a right stub (the L+R direction picker) loses the right one.
export function removeDivertBranch(
  eq: AutomationEquipment,
  offsetM: number,
  side: 'LEFT' | 'RIGHT' | null = null,
): AutomationEquipment {
  const path = Array.isArray(eq.path) ? eq.path.map((p) => [p[0], p[1]]) : []
  if (path.length < 2) return eq
  let sections = effectiveSections(eq).map((s) => [s[0], s[1]])
  const pos = pointAlong(eq, offsetM)
  let jIdx = -1
  let jDist = Infinity
  for (let i = 0; i < path.length; i++) {
    const d = Math.hypot(path[i][0] - pos.x, path[i][1] - pos.z)
    if (d < jDist) {
      jDist = d
      jIdx = i
    }
  }
  if (jIdx < 0) return eq
  const outgoings = sections.filter((s) => s[0] === jIdx)
  if (outgoings.length < 2) return eq // no branch hanging off this junction
  // Incoming travel direction at the junction (else the conveyor's overall direction).
  const incoming = sections.find((s) => s[1] === jIdx)
  let ix = 1
  let iz = 0
  if (incoming && path[incoming[0]]) {
    ix = path[jIdx][0] - path[incoming[0]][0]
    iz = path[jIdx][1] - path[incoming[0]][1]
  }
  const ilen = Math.hypot(ix, iz) || 1
  ix /= ilen
  iz /= ilen
  // The branch = the outgoing section most perpendicular to the incoming travel (the through-line
  // continues roughly straight; the divert turns off it). With a known side, a wrong-side candidate
  // takes a large score penalty so the matching stub wins whenever one exists on each side.
  let branch: number[] | null = null
  let bestScore = Infinity
  for (const s of outgoings) {
    let ox = path[s[1]][0] - path[jIdx][0]
    let oz = path[s[1]][1] - path[jIdx][1]
    const olen = Math.hypot(ox, oz) || 1
    ox /= olen
    oz /= olen
    const dot = Math.abs(ix * ox + iz * oz)
    // Side of this outgoing relative to incoming travel. Matches the addDivertBranch convention
    // (LEFT endpoint = j + (dz, -dx)): a LEFT branch has cross = ix*oz - iz*ox < 0.
    const branchSide = ix * oz - iz * ox < 0 ? 'LEFT' : 'RIGHT'
    const score = dot + (side && branchSide !== side ? 10 : 0)
    if (score < bestScore) {
      bestScore = score
      branch = s
    }
  }
  if (!branch) return eq
  const [bf, bt] = branch
  sections = sections.filter((s) => !(s[0] === bf && s[1] === bt))
  // Cascade: drop any section whose source is now unreachable (no incoming edge, and not the start).
  for (let changed = true; changed; ) {
    const hasIncoming = new Set(sections.map((s) => s[1]))
    const before = sections.length
    sections = sections.filter((s) => s[0] === 0 || hasIncoming.has(s[0]))
    changed = sections.length !== before
  }
  // GC orphan points and re-index the survivors (preserves main-line order, so FP offsets hold).
  const used = new Set<number>()
  for (const s of sections) {
    used.add(s[0])
    used.add(s[1])
  }
  const keep: number[][] = []
  const remap = new Map<number, number>()
  for (let i = 0; i < path.length; i++) {
    if (used.has(i)) {
      remap.set(i, keep.length)
      keep.push(path[i])
    }
  }
  const newSections = sections.map((s) => [remap.get(s[0]) ?? 0, remap.get(s[1]) ?? 0])
  return { ...eq, path: keep, sections: newSections }
}

// Indices of path points that take part in at least one section (the junction/node markers).
export function junctionPoints(sections: number[][]): Set<number> {
  const out = new Set<number>()
  for (const [f, t] of sections) {
    out.add(f)
    out.add(t)
  }
  return out
}

function num(v: string, fallback = 0): number {
  const n = Number(v)
  return Number.isFinite(n) ? n : fallback
}

// World-space centre of a placed item, including its level's elevation so cross-level links
// render at the right height. For a polyline conveyor the centre is the mean of its waypoints.
// World centre of a piece of equipment, used to anchor connection lines. Y is floor-relative
// (heightM/2 + posYM) to match the bodies/paths — the scene does not apply level elevation, so
// adding it here floated connection lines above the equipment they link.
export function worldCenter(eq: AutomationEquipment): [number, number, number] {
  if (Array.isArray(eq.path) && eq.path.length >= 1) {
    let sx = 0
    let sz = 0
    for (const p of eq.path) {
      sx += p[0]
      sz += p[1]
    }
    const n = eq.path.length
    return [sx / n, eq.heightM / 2 + eq.posYM, sz / n]
  }
  return [eq.posXM, eq.heightM / 2 + eq.posYM, eq.posZM]
}

export default function AutomationTopology3D({
  collapsed = false,
  onToggleChrome,
}: {
  // When true the surrounding page chrome (title + level meta) is folded away for more canvas height.
  collapsed?: boolean
  onToggleChrome?: () => void
} = {}) {
  const t = useT('topology')
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const { writeAllowed } = useAuth()
  // Topology persistence (Save / projection) is gated to write level; the gateway also rejects
  // writes to /api/flow/*/topology for read-only users.
  const canWrite = writeAllowed('topology')

  const [levels, setLevels] = useState<AutomationLevel[]>([])
  const [equipment, setEquipment] = useState<AutomationEquipment[]>([])
  // Connections + function points are not edited in this slice; we hold them so Save round-trips them.
  const [connections, setConnections] = useState<AutomationConnection[]>([])
  const [functionPoints, setFunctionPoints] = useState<AutomationFunctionPoint[]>([])

  const [library, setLibrary] = useState<Equipment[]>([])
  // GTP stations in this warehouse — placeable as "workstation" equipment in the topology.
  const [stations, setStations] = useState<Station[]>([])
  const [activeLevelId, setActiveLevelId] = useState<string>('')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  // The 2D plan's selected waypoint: scopes the Connections panel to that node.
  const [planSelectedWp, setPlanSelectedWp] = useState<{ eqId: string; index: number } | null>(null)
  // Which editor surface is shown in the centre column. Both share all the state above.
  const [view, setView] = useState<'3d' | '2d'>('3d')

  // When ON, clicking the ground plane draws conveyor sections on the selected conveyor: each
  // click resolves (or appends) a path point and, when an anchor is set, pushes a directed section
  // from the anchor to that point. Clicking an existing waypoint re-anchors (to branch/divert).
  const [drawPath, setDrawPath] = useState(false)
  // The anchor path index for the next section while drawing (null = next click just adds a point).
  const [activeFromIdx, setActiveFromIdx] = useState<number | null>(null)
  // While a waypoint handle is being dragged we disable OrbitControls so the camera stays put.
  const [orbitEnabled, setOrbitEnabled] = useState(true)

  // Connect mode: while ON, equipment clicks pick a connection source then target instead of
  // selecting/dragging. connectFrom holds the chosen source's placed id (null = pick source next).
  const [connectMode, setConnectMode] = useState(false)
  const [connectFrom, setConnectFrom] = useState<string | null>(null)
  // Route test mode: pick a start + target routing node in the 3D scene and check whether the
  // PROJECTED routing graph (what flow actually routes on) connects them. Exclusive with the
  // editing modes — entering it exits connect/draw and suppresses selection/drag.
  const [testMode, setTestMode] = useState(false)
  // The loaded routing graph, cached while the editor lives; cleared on reload / re-projection.
  const [testTopo, setTestTopo] = useState<Topology | null>(null)
  const [testLoading, setTestLoading] = useState(false)
  const [testStart, setTestStart] = useState<string | null>(null)
  const [testTarget, setTestTarget] = useState<string | null>(null)
  // Pick-in-3D mode for a workstation's conveyor interaction: while ON, clicking a conveyor
  // function-point MARKER in the 3D scene chooses that point for the pending interaction row
  // (instead of opening its config dialog). Mutually exclusive with connect/draw/test; the
  // callback (set when the panel arms the mode) receives the picked function point's id.
  const [pickFpMode, setPickFpMode] = useState(false)
  const pickFpHandlerRef = useRef<((fpId: string) => void) | null>(null)
  const [selectedConnId, setSelectedConnId] = useState<string | null>(null)
  // The function point whose config dialog is open (clicking a marker in 2D/3D), or null.
  const [editFpId, setEditFpId] = useState<string | null>(null)
  // The connection whose detail/edit dialog is open (clicking a Connections-panel row), or null.
  const [detailConnId, setDetailConnId] = useState<string | null>(null)
  // Library filter: show only equipment with no placement anywhere in the editor.
  const [unplacedOnly, setUnplacedOnly] = useState(false)

  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [projecting, setProjecting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)
  const [dirty, setDirty] = useState(false)

  // Monotonic counter for default codes within a session, so two quick adds don't collide.
  const counter = useRef(0)

  const libById = useMemo(() => {
    const m = new Map<string, Equipment>()
    for (const e of library) if (e.id) m.set(e.id, e)
    return m
  }, [library])

  // How many placements (across all levels) reference each master-data equipment id. Drives the
  // library's placed/unplaced badges and the "Unplaced only" filter; recomputes as equipment changes.
  const placementCounts = useMemo(() => {
    const m = new Map<string, number>()
    for (const e of equipment) {
      if (!e.equipmentId) continue
      m.set(e.equipmentId, (m.get(e.equipmentId) ?? 0) + 1)
    }
    return m
  }, [equipment])

  const load = useCallback(async () => {
    // A (re)load invalidates the cached routing graph and any in-progress route test.
    setTestMode(false)
    setTestTopo(null)
    setTestStart(null)
    setTestTarget(null)
    if (!warehouseId) {
      setLevels([])
      setEquipment([])
      setConnections([])
      setFunctionPoints([])
      setActiveLevelId('')
      setError(null)
      setInfo(null)
      return
    }
    setLoading(true)
    setError(null)
    try {
      const [topo, lib, sts] = await Promise.all([
        loadAutomationTopology(warehouseId),
        listEquipment(warehouseId).catch(() => [] as Equipment[]),
        listStations(warehouseId).catch(() => [] as Station[]),
      ])
      setLevels(topo.levels)
      setEquipment(topo.equipment)
      setConnections(topo.connections)
      setFunctionPoints(topo.functionPoints)
      setLibrary(lib)
      setStations(sts)
      setActiveLevelId((prev) =>
        topo.levels.some((l) => l.id === prev) ? prev : topo.levels[0]?.id ?? '',
      )
      setSelectedId(null)
      setDirty(false)
      setInfo(
        t('loadedLevels', 'Loaded {levels} level(s), {equipment} equipment')
          .replace('{levels}', String(topo.levels.length))
          .replace('{equipment}', String(topo.equipment.length)),
      )
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [warehouseId, t])

  useEffect(() => {
    load()
  }, [load])

  const save = useCallback(async () => {
    if (!warehouseId) {
      setError(t('noActiveWarehouse', 'No active warehouse selected'))
      return
    }
    setSaving(true)
    setError(null)
    try {
      const saved = await saveAutomationTopology(warehouseId, {
        levels,
        equipment: equipment.map((e) => ({ ...e, category: category(e, libById) })),
        connections,
        functionPoints,
      })
      setLevels(saved.levels)
      setEquipment(saved.equipment)
      setConnections(saved.connections)
      setFunctionPoints(saved.functionPoints)
      setActiveLevelId((prev) =>
        saved.levels.some((l) => l.id === prev) ? prev : saved.levels[0]?.id ?? '',
      )
      setSelectedId(null)
      setDirty(false)
      setInfo(t('saved', 'Saved'))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }, [warehouseId, levels, equipment, connections, functionPoints, t])

  // Generate the conveyor routing graph (nodes/edges) from the current layout. Saves first so the
  // projection reads the persisted placement, then replaces the warehouse's routing graph.
  const projectGraph = useCallback(async () => {
    if (!warehouseId) {
      setError(t('noActiveWarehouse', 'No active warehouse selected'))
      return
    }
    setProjecting(true)
    setError(null)
    try {
      const saved = await saveAutomationTopology(warehouseId, {
        levels,
        equipment: equipment.map((e) => ({ ...e, category: category(e, libById) })),
        connections,
        functionPoints,
      })
      setLevels(saved.levels)
      setEquipment(saved.equipment)
      setConnections(saved.connections)
      setFunctionPoints(saved.functionPoints)
      // The full-replace save remaps level ids server-side; re-point the active level like the Save
      // button does, or the scene filters against a dead id and the whole model "disappears".
      setActiveLevelId((prev) =>
        saved.levels.some((l) => l.id === prev) ? prev : saved.levels[0]?.id ?? '',
      )
      setSelectedId(null)
      setDirty(false)
      const result = await projectRoutingGraph(warehouseId)
      const warn = result.warnings.length
        ? ' · ' + t('warningsCount', '{n} warning(s)').replace('{n}', String(result.warnings.length))
        : ''
      setInfo(
        t('routingGenerated', 'Routing graph generated: {nodes} node(s), {edges} edge(s)')
          .replace('{nodes}', String(result.nodes))
          .replace('{edges}', String(result.edges)) + warn,
      )
      // The projection replaced the routing graph — drop the route-test cache so the next test
      // loads the fresh graph.
      setTestTopo(null)
      setTestStart(null)
      setTestTarget(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setProjecting(false)
    }
  }, [warehouseId, levels, equipment, connections, functionPoints, t])

  // ---- level helpers -----------------------------------------------------
  const activeLevel = useMemo(
    () => levels.find((l) => l.id === activeLevelId) ?? null,
    [levels, activeLevelId],
  )

  const addLevel = useCallback(() => {
    const maxNumber = levels.reduce((m, l) => Math.max(m, l.number), 0)
    const number = maxNumber + 1
    const level: AutomationLevel = {
      id: crypto.randomUUID(),
      number,
      name: `Level ${number}`,
      elevationM: number * 5,
      status: 'ACTIVE',
    }
    setLevels((ls) => [...ls, level])
    setActiveLevelId(level.id)
    setDirty(true)
  }, [levels])

  const patchActiveLevel = useCallback(
    (patch: Partial<AutomationLevel>) => {
      if (!activeLevelId) return
      setLevels((ls) => ls.map((l) => (l.id === activeLevelId ? { ...l, ...patch } : l)))
      setDirty(true)
    },
    [activeLevelId],
  )

  // ---- equipment helpers -------------------------------------------------
  const levelEquipment = useMemo(
    () => equipment.filter((e) => e.levelId === activeLevelId),
    [equipment, activeLevelId],
  )

  const selected = useMemo(
    () => equipment.find((e) => e.id === selectedId) ?? null,
    [equipment, selectedId],
  )

  const selectedIsConveyor = selected ? isConveyor(selected, libById) : false
  // An ASRS that owns IN/OUT stubs (or a real conveyor) gets the conveyor path tools so the user can
  // extend a stub with Draw sections / waypoint drag.
  const selectedCanEditPath = selected ? canEditPath(selected, libById) : false
  const selectedMeta = selected?.equipmentId ? libById.get(selected.equipmentId) : undefined
  const selectedIsAsrs = selected ? isAsrs(selected, libById) : false
  const selectedIsWorkstation = selected ? category(selected, libById) === 'workstation' : false

  // Function points belonging to the currently-selected placed equipment.
  const selectedFunctionPoints = useMemo(
    () => (selected ? functionPoints.filter((f) => f.placedId === selected.id) : []),
    [functionPoints, selected],
  )

  // Draw mode only makes sense for a selected conveyor (or an ASRS with stubs) — drop it otherwise.
  useEffect(() => {
    if (!selected || !selectedCanEditPath) setDrawPath(false)
  }, [selected, selectedCanEditPath])

  // Reset the section anchor whenever draw mode turns off or the selection changes.
  useEffect(() => {
    if (!drawPath) setActiveFromIdx(null)
  }, [drawPath, selectedId])

  const patchEquipment = useCallback((id: string, patch: Partial<AutomationEquipment>) => {
    setEquipment((es) => es.map((e) => (e.id === id ? { ...e, ...patch } : e)))
    setDirty(true)
  }, [])

  // Delete one path waypoint from a conveyor (used by the 2D plan's select-then-Delete flow).
  // Semantics: drop path[index]; drop every section touching that index (no auto-bridging); re-index
  // the surviving section pairs (indices > index shift down by 1). When fewer than 2 points remain
  // the path and sections are cleared entirely (the item renders as a plain box again); `closed` is
  // only kept while at least 3 points remain (a 2-point loop is degenerate).
  const deleteWaypoint = useCallback((id: string, index: number) => {
    setEquipment((es) =>
      es.map((e) => {
        if (e.id !== id) return e
        const path = Array.isArray(e.path) ? e.path.map((p) => [p[0], p[1]]) : []
        if (index < 0 || index >= path.length) return e
        // Materialise the CURRENT connectivity first (implicit sequential paths included) so the
        // deletion behaves identically whether or not explicit sections were stored.
        const sections = effectiveSections(e)
          .filter((s) => s[0] !== index && s[1] !== index)
          .map((s) => [s[0] > index ? s[0] - 1 : s[0], s[1] > index ? s[1] - 1 : s[1]])
        path.splice(index, 1)
        if (path.length < 2) return { ...e, path: [], sections: [], closed: false }
        return { ...e, path, sections, closed: !!e.closed && path.length >= 3 }
      }),
    )
    setDirty(true)
  }, [])

  // ---- function-point helpers --------------------------------------------
  const addFunctionPoint = useCallback((fp: AutomationFunctionPoint) => {
    setFunctionPoints((fps) => [...fps, fp])
    setDirty(true)
  }, [])

  const deleteFunctionPoint = useCallback(
    (id: string) => {
      const fp = functionPoints.find((f) => f.id === id)
      // Deleting a point whose function set INCLUDES a divert also removes the branch it spawned.
      // The FP's side picks WHICH branch when the junction carries both a left and a right stub.
      if (fp && fpFunctions(fp.functionType).some(isDivertType)) {
        const side = fp.side === 'LEFT' || fp.side === 'RIGHT' ? fp.side : null
        setEquipment((es) =>
          es.map((e) => (e.id === fp.placedId ? removeDivertBranch(e, fp.offsetM, side) : e)),
        )
      }
      setFunctionPoints((fps) => fps.filter((f) => f.id !== id))
      setDirty(true)
    },
    [functionPoints],
  )

  // Partial update of an existing function point (its placedId / offsetM / side / …). Used by the
  // 2D plan when a placed marker is dragged onto (or along) a conveyor to re-position it.
  const updateFunctionPoint = useCallback((id: string, patch: Partial<AutomationFunctionPoint>) => {
    setFunctionPoints((fps) => fps.map((f) => (f.id === id ? { ...f, ...patch } : f)))
    setDirty(true)
  }, [])

  // A ground-plane / waypoint click in draw mode. This is the two-click section draw:
  //   first click  → resolves to a path point and becomes the anchor (no section yet);
  //   second click → resolves to another point and draws a directed section ANCHOR → here, so the
  //                  section's travel direction follows the CLICK ORDER:
  //     • click an existing point, then a grid spot → section runs existing → new (outward);
  //     • click a grid spot, then a point on the conveyor → section runs new → existing (inward).
  // The resolved point becomes the new anchor so consecutive clicks chain into a run.
  //
  // A click resolves (in order): to an existing vertex within SNAP_M (reuse → junction); else onto
  // the nearest section within SNAP_M (insert a junction, splitting it, so a click on the conveyor
  // BODY connects to it); else to a brand-new free point.
  //
  // Everything is computed up-front from the CURRENT equipment (NOT inside the state updater) so the
  // new anchor is set deterministically. The old code stashed the resolved index via a side-effect
  // inside setEquipment, which only ran when React happened to evaluate the updater eagerly — so the
  // anchor sometimes never advanced and the next click "didn't draw".
  const drawSectionAt = useCallback(
    (id: string, x: number, z: number) => {
      const px = +x.toFixed(3)
      const pz = +z.toFixed(3)
      const eq = equipment.find((e) => e.id === id)
      if (!eq) return
      const path = Array.isArray(eq.path) ? eq.path.map((p) => [p[0], p[1]]) : []
      // Materialise CURRENT connectivity (sequential for an implicit path) so we can snap onto a
      // section interior and preserve existing sections when we append. Mirrors startBranchAt.
      const sections = effectiveSections(eq).map((s) => [s[0], s[1]])

      // 1) Snap to the nearest existing point within the snap radius (reuse it → forms junctions).
      let nearIdx = -1
      let nearDist = SNAP_M
      for (let i = 0; i < path.length; i++) {
        const d = Math.hypot(path[i][0] - px, path[i][1] - pz)
        if (d <= nearDist) {
          nearDist = d
          nearIdx = i
        }
      }
      let resolvedIdx: number
      if (nearIdx >= 0) {
        resolvedIdx = nearIdx
      } else {
        // 2) Else snap onto the nearest SECTION within the snap radius and INSERT a junction (split
        //    it) so a click ON the conveyor body connects to it instead of dropping a loose point.
        let bestSec = -1
        let bestDist = SNAP_M
        let bjx = 0
        let bjz = 0
        for (let k = 0; k < sections.length; k++) {
          const a = path[sections[k][0]]
          const b = path[sections[k][1]]
          if (!a || !b) continue
          const abx = b[0] - a[0]
          const abz = b[1] - a[1]
          const len2 = abx * abx + abz * abz
          if (len2 < 1e-9) continue
          let t = ((px - a[0]) * abx + (pz - a[1]) * abz) / len2
          t = Math.min(1, Math.max(0, t))
          const jx = a[0] + abx * t
          const jz = a[1] + abz * t
          const d = Math.hypot(jx - px, jz - pz)
          if (d <= bestDist) {
            bestDist = d
            bestSec = k
            bjx = jx
            bjz = jz
          }
        }
        if (bestSec >= 0) {
          const [a, b] = sections[bestSec]
          const m = path.length
          path.push([+bjx.toFixed(3), +bjz.toFixed(3)])
          // m is the new highest index, so no existing section index needs shifting.
          sections.splice(bestSec, 1, [a, m], [m, b])
          resolvedIdx = m
        } else {
          // 3) Nowhere near the conveyor → a free point (start/extend a fresh run).
          path.push([px, pz])
          resolvedIdx = path.length - 1
        }
      }

      // With an anchor that isn't the resolved point, add a directed section ANCHOR → resolved
      // (travel direction follows the click order) — unless an identical one already exists.
      if (
        activeFromIdx != null &&
        activeFromIdx !== resolvedIdx &&
        activeFromIdx < path.length &&
        !sections.some((s) => s[0] === activeFromIdx && s[1] === resolvedIdx)
      ) {
        sections.push([activeFromIdx, resolvedIdx])
      }

      setEquipment((es) => es.map((e) => (e.id === id ? { ...e, path, sections } : e)))
      // Re-anchor to the resolved point so consecutive clicks chain into a connected run.
      setActiveFromIdx(resolvedIdx)
      setDirty(true)
    },
    [activeFromIdx, equipment],
  )

  // Start a divert branch at a world position (x,z) on a conveyor: ensure a junction path point
  // exists there, then anchor + enable draw-sections mode so the user's next clicks lay the branch.
  //
  // Junction resolution mirrors the spec: if the projected position is within SNAP_M of an existing
  // path point, reuse that index; otherwise INSERT a new point and split the section [a,b] whose
  // segment contains it into [a, m] and [m, b], shifting every other section index >= m by +1. We
  // materialise effectiveSections() to an explicit sections array so even implicit-sequential paths
  // keep their topology after the split.
  const startBranchAt = useCallback((id: string, x: number, z: number) => {
    const px = +x.toFixed(3)
    const pz = +z.toFixed(3)
    let junctionIdx: number | null = null
    setEquipment((es) =>
      es.map((e) => {
        if (e.id !== id) return e
        const path = Array.isArray(e.path) ? e.path.map((p) => [p[0], p[1]]) : []
        if (path.length < 2) return e
        // Reuse a nearby existing point (forms the junction on it) within the snap radius.
        let nearIdx = -1
        let nearDist = SNAP_M
        for (let i = 0; i < path.length; i++) {
          const d = Math.hypot(path[i][0] - px, path[i][1] - pz)
          if (d <= nearDist) {
            nearDist = d
            nearIdx = i
          }
        }
        const sections = effectiveSections(e).map((s) => [s[0], s[1]])
        if (nearIdx >= 0) {
          junctionIdx = nearIdx
          return { ...e, path, sections }
        }
        // No nearby point: find the section whose segment best contains the projection, insert a new
        // point there, and split that section. We pick the section with the smallest perpendicular
        // distance to its segment (projection clamped to the segment).
        let bestSec = -1
        let bestDist = Infinity
        for (let k = 0; k < sections.length; k++) {
          const a = path[sections[k][0]]
          const b = path[sections[k][1]]
          if (!a || !b) continue
          const abx = b[0] - a[0]
          const abz = b[1] - a[1]
          const len2 = abx * abx + abz * abz
          if (len2 < 1e-9) continue
          let t = ((px - a[0]) * abx + (pz - a[1]) * abz) / len2
          t = Math.min(1, Math.max(0, t))
          const projx = a[0] + abx * t
          const projz = a[1] + abz * t
          const d = Math.hypot(projx - px, projz - pz)
          if (d < bestDist) {
            bestDist = d
            bestSec = k
          }
        }
        if (bestSec < 0) {
          // Genuinely ambiguous (no usable section) — fall back to snapping to the nearest point.
          let fIdx = 0
          let fDist = Infinity
          for (let i = 0; i < path.length; i++) {
            const d = Math.hypot(path[i][0] - px, path[i][1] - pz)
            if (d < fDist) {
              fDist = d
              fIdx = i
            }
          }
          junctionIdx = fIdx
          return { ...e, path, sections }
        }
        // Insert the new point at the projected position on the chosen segment, split [a,b]→[a,m],[m,b].
        const [a, b] = sections[bestSec]
        const ax = path[a][0]
        const az = path[a][1]
        const abx = path[b][0] - ax
        const abz = path[b][1] - az
        const len2 = abx * abx + abz * abz
        let t = len2 < 1e-9 ? 0 : ((px - ax) * abx + (pz - az) * abz) / len2
        t = Math.min(1, Math.max(0, t))
        const m = path.length
        path.push([+(ax + abx * t).toFixed(3), +(az + abz * t).toFixed(3)])
        // Shift every existing index >= m by +1. Since m === old path.length, no existing index can
        // be >= m, so the shift is a no-op here — but we keep it explicit for correctness/clarity.
        const shifted = sections.map((s) => [s[0] >= m ? s[0] + 1 : s[0], s[1] >= m ? s[1] + 1 : s[1]])
        shifted.splice(bestSec, 1, [a, m], [m, b])
        junctionIdx = m
        return { ...e, path, sections: shifted }
      }),
    )
    if (junctionIdx != null) {
      setSelectedId(id)
      setActiveFromIdx(junctionIdx)
      setDrawPath(true)
    }
    setDirty(true)
  }, [])

  // Drop a divert STUB at a world position (x,z) on a conveyor, in the divert direction. Unlike
  // startBranchAt (which enters draw mode for a free-hand branch), this materialises a junction AND a
  // single perpendicular branch point + directed section, then leaves draw mode OFF so the user can
  // extend it later. The junction becomes a red decision point automatically (it gains a 2nd outgoing
  // section). `side` LEFT rotates the conveyor's travel direction +90°, RIGHT −90° (in the X/Z plane).
  // When `snapStep` is given (Snap on) the branch stub LENGTH is snapped to that metre grid (min ~1 m
  // so the stub is always clearly visible), but its DIRECTION stays exactly perpendicular.
  //
  // Junction rule (this is the important bit): a drop on the *interior* of a section must INSERT a new
  // junction and SPLIT that section so the main line stays straight (the stub then sprouts to the
  // side). We only REUSE an existing path point when the projection lands almost exactly on it
  // (within JUNCTION_REUSE_M — far tighter than the SNAP_M used for free-hand drawing). The old code
  // reused any point within SNAP_M (0.5 m), so on a short conveyor the junction snapped to an
  // endpoint, no split happened, and the "branch" came off the end as an L-bend — the reported bug.
  const addDivertBranch = useCallback(
    (
      id: string,
      x: number,
      z: number,
      side: 'LEFT' | 'RIGHT',
      snapStep: number | null,
      // When true this is an INFEED merge, not a divert: the directed section runs stub → junction
      // (the feeder merges INTO the line) instead of junction → stub.
      merge = false,
    ) => {
      const px = +x.toFixed(3)
      const pz = +z.toFixed(3)
      // Only collapse onto an existing waypoint when the drop is essentially ON it.
      const JUNCTION_REUSE_M = 0.12
      setEquipment((es) =>
        es.map((e) => {
          if (e.id !== id) return e
          const path = Array.isArray(e.path) ? e.path.map((p) => [p[0], p[1]]) : []
          if (path.length < 2) return e

          let sections = effectiveSections(e).map((s) => [s[0], s[1]])
          let junctionIdx: number

          // --- 1) Resolve / insert the junction point. ---
          // (a) Find the section whose segment the drop projects onto most closely.
          let bestSec = -1
          let bestDist = Infinity
          let bestT = 0
          for (let k = 0; k < sections.length; k++) {
            const a = path[sections[k][0]]
            const b = path[sections[k][1]]
            if (!a || !b) continue
            const abx = b[0] - a[0]
            const abz = b[1] - a[1]
            const len2 = abx * abx + abz * abz
            if (len2 < 1e-9) continue
            let t = ((px - a[0]) * abx + (pz - a[1]) * abz) / len2
            t = Math.min(1, Math.max(0, t))
            const projx = a[0] + abx * t
            const projz = a[1] + abz * t
            const d = Math.hypot(projx - px, projz - pz)
            if (d < bestDist) {
              bestDist = d
              bestSec = k
              bestT = t
            }
          }

          if (bestSec < 0) {
            // No usable section — fall back to the nearest existing point as the junction.
            let fIdx = 0
            let fDist = Infinity
            for (let i = 0; i < path.length; i++) {
              const d = Math.hypot(path[i][0] - px, path[i][1] - pz)
              if (d < fDist) {
                fDist = d
                fIdx = i
              }
            }
            junctionIdx = fIdx
          } else {
            const [a, b] = sections[bestSec]
            const ax = path[a][0]
            const az = path[a][1]
            const abx = path[b][0] - ax
            const abz = path[b][1] - az
            const jxOnSeg = ax + abx * bestT
            const jzOnSeg = az + abz * bestT
            // (b) Reuse an endpoint of this section ONLY when the projection is right on it; otherwise
            // INSERT a junction and split the section so the main line stays straight.
            const dToA = Math.hypot(ax - jxOnSeg, az - jzOnSeg)
            const dToB = Math.hypot(path[b][0] - jxOnSeg, path[b][1] - jzOnSeg)
            if (dToA <= JUNCTION_REUSE_M) {
              junctionIdx = a
            } else if (dToB <= JUNCTION_REUSE_M) {
              junctionIdx = b
            } else {
              const m = path.length
              path.push([+jxOnSeg.toFixed(3), +jzOnSeg.toFixed(3)])
              sections = sections.map((s) => [s[0] >= m ? s[0] + 1 : s[0], s[1] >= m ? s[1] + 1 : s[1]])
              sections.splice(bestSec, 1, [a, m], [m, b])
              junctionIdx = m
            }
          }

          // --- 2) Travel direction at the junction, then the perpendicular branch endpoint. ---
          // Use the direction of the section that ENDS at the junction (incoming travel) if any,
          // else the section that STARTS at it (outgoing), else the path-segment tangent.
          const jx = path[junctionIdx][0]
          const jz = path[junctionIdx][1]
          let tx = 0
          let tz = 0
          const incoming = sections.find((s) => s[1] === junctionIdx)
          const outgoing = sections.find((s) => s[0] === junctionIdx)
          if (incoming && path[incoming[0]]) {
            tx = jx - path[incoming[0]][0]
            tz = jz - path[incoming[0]][1]
          } else if (outgoing && path[outgoing[1]]) {
            tx = path[outgoing[1]][0] - jx
            tz = path[outgoing[1]][1] - jz
          } else if (junctionIdx + 1 < path.length) {
            tx = path[junctionIdx + 1][0] - jx
            tz = path[junctionIdx + 1][1] - jz
          } else if (junctionIdx - 1 >= 0) {
            tx = jx - path[junctionIdx - 1][0]
            tz = jz - path[junctionIdx - 1][1]
          }
          const tlen = Math.hypot(tx, tz) || 1
          // Unit travel direction (dx,dz). LEFT = rotate +90° → (dz, −dx); RIGHT = −90° → (−dz, dx).
          const dx = tx / tlen
          const dz = tz / tlen
          // Stub length: ~1 m, or the grid step rounded up to at least 1 m when Snap is on, so the
          // stub is always a clearly-visible perpendicular piece (never collapsed to ~0).
          let stub = 1
          if (snapStep && snapStep > 0) stub = Math.max(1, Math.round(1 / snapStep) * snapStep)
          // Perpendicular branch endpoint (direction stays exact; only the length is snapped).
          let bx: number
          let bz: number
          if (side === 'LEFT') {
            bx = jx + dz * stub
            bz = jz - dx * stub
          } else {
            bx = jx - dz * stub
            bz = jz + dx * stub
          }
          bx = +bx.toFixed(3)
          bz = +bz.toFixed(3)

          // --- 2b) Merge onto an overlapping conveyor. ---
          // If the stub endpoint lands within DIVERT_MERGE_M of ANOTHER conveyor's centreline, snap it
          // exactly onto that line so the divert MEETS the existing conveyor at one shared coordinate
          // (there is only ever one conveyor line per point) instead of overlaying a parallel/offset
          // stub. The routing projection's adjacency inference then connects the two.
          {
            let md = DIVERT_MERGE_M
            for (const other of es) {
              if (other.id === id || !isConveyor(other, libById)) continue
              const op = Array.isArray(other.path) ? other.path : []
              if (op.length < 2) continue
              const pc = other.closed ? op.length : op.length - 1
              for (let i = 0; i < pc; i++) {
                const oa = op[i]
                const ob = op[(i + 1) % op.length]
                const abx = ob[0] - oa[0]
                const abz = ob[1] - oa[1]
                const len2 = abx * abx + abz * abz
                if (len2 < 1e-9) continue
                let t = ((bx - oa[0]) * abx + (bz - oa[1]) * abz) / len2
                t = Math.min(1, Math.max(0, t))
                const jx2 = oa[0] + abx * t
                const jz2 = oa[1] + abz * t
                const d = Math.hypot(jx2 - bx, jz2 - bz)
                if (d <= md) {
                  md = d
                  bx = +jx2.toFixed(3)
                  bz = +jz2.toFixed(3)
                }
              }
            }
          }

          // --- 3) Add the branch endpoint + a directed section. ---
          // Divert: junction → branch (material leaves the line). INFEED merge: branch → junction
          // (the feeder runs into the line). The branch endpoint stays the same perpendicular stub.
          const branchIdx = path.length
          path.push([bx, bz])
          const sec = merge ? [branchIdx, junctionIdx] : [junctionIdx, branchIdx]
          if (!sections.some((s) => s[0] === sec[0] && s[1] === sec[1])) {
            sections.push(sec)
          }
          return { ...e, path, sections }
        }),
      )
      setSelectedId(id)
      setDirty(true)
    },
    [libById],
  )

  // Add a 1 m IN/OUT conveyor stub to an ASRS, owned by the ASRS itself (its own `path`/`sections`,
  // the same fields conveyors use). (x,z) is the drop point; we snap it to the ASRS footprint edge to
  // get the port point + the outward edge normal, then add a second point 1 m perpendicular OUT from
  // the port, plus a directed section. Direction of travel: INDUCT (IN) flows toward the rack
  // (stub → port); DISCHARGE (OUT) flows away (port → stub). Returns the port point's path index so
  // the caller can record it (e.g. as the function point's offset reference) if needed.
  const addAsrsPortStub = useCallback(
    (id: string, x: number, z: number, kind: 'INDUCT' | 'DISCHARGE', side: string | null) => {
      let portOffsetM = 0
      setEquipment((es) =>
        es.map((e) => {
          if (e.id !== id) return e
          const snap = snapToFootprintPerimeter(e, x, z)
          const path = Array.isArray(e.path) ? e.path.map((p) => [p[0], p[1]]) : []
          const sections = Array.isArray(e.sections) ? e.sections.map((s) => [s[0], s[1]]) : []
          // Port point on the edge.
          const portIdx = path.length
          path.push([snap.x, snap.z])
          // Stub point 1 m outward along the edge normal.
          const sx = +(snap.x + snap.nx * 1).toFixed(3)
          const sz = +(snap.z + snap.nz * 1).toFixed(3)
          const stubIdx = path.length
          path.push([sx, sz])
          // INDUCT (IN): stub → port (toward the rack). DISCHARGE (OUT): port → stub (away).
          const sec = kind === 'INDUCT' ? [stubIdx, portIdx] : [portIdx, stubIdx]
          sections.push(sec)
          // Sequential arc-length up to the port point — so the FP marker (pointAlong) lands on it.
          let acc = 0
          for (let i = 0; i < portIdx; i++) {
            acc += Math.hypot(path[i + 1][0] - path[i][0], path[i + 1][1] - path[i][1])
          }
          portOffsetM = +acc.toFixed(3)
          return { ...e, path, sections }
        }),
      )
      // The function point sits ON the port edge; its marker rides the stub's port point.
      setFunctionPoints((fps) => [
        ...fps,
        {
          id: crypto.randomUUID(),
          placedId: id,
          functionType: kind,
          name: null,
          offsetM: portOffsetM,
          side: side || null,
          nodeCode: null,
          status: 'ACTIVE',
        },
      ])
      setSelectedId(id)
      setDirty(true)
    },
    [],
  )

  // Clicking an existing waypoint handle while drawing re-anchors WITHOUT creating a section — this
  // is how a branch / divert starts (anchor at a junction, then click a new direction).
  const anchorWaypoint = useCallback((idx: number) => {
    setActiveFromIdx(idx)
  }, [])

  // Enter draw-sections mode anchored at one of a conveyor's waypoints — the 2D plan's
  // double-click-to-draw entry (same machinery as the Draw sections button + a re-anchor click,
  // without the side-panel trip). Selecting the item keeps the can-edit-path guard effect happy.
  const startDrawAtWaypoint = useCallback((id: string, index: number) => {
    setSelectedId(id)
    setDrawPath(true)
    setActiveFromIdx(index)
  }, [])

  // Leave draw-sections mode (the 2D plan's Escape / double-click-again exit).
  const exitDraw = useCallback(() => {
    setDrawPath(false)
  }, [])

  // Remove the most recently added section (no-op when there are none). Leaves any now-orphan path
  // points in place (harmless; Clear resets everything).
  const removeLastSection = useCallback((id: string) => {
    setEquipment((es) =>
      es.map((e) => {
        if (e.id !== id) return e
        const sections = Array.isArray(e.sections) ? e.sections : []
        if (sections.length === 0) return e
        return { ...e, sections: sections.slice(0, -1) }
      }),
    )
    setActiveFromIdx(null)
    setDirty(true)
  }, [])

  // Move a single waypoint (live, during a handle drag).
  const moveWaypoint = useCallback((id: string, index: number, x: number, z: number) => {
    setEquipment((es) =>
      es.map((e) => {
        if (e.id !== id || !Array.isArray(e.path) || index < 0 || index >= e.path.length) return e
        const next = e.path.map((p, i) => (i === index ? [+x.toFixed(3), +z.toFixed(3)] : p))
        return { ...e, path: next }
      }),
    )
    setDirty(true)
  }, [])

  const addFromLibrary = useCallback(
    (meta: Equipment) => {
      if (!activeLevelId) {
        setError(t('addLevelFirst', 'Add a level first'))
        return
      }
      counter.current += 1
      const typeLabel = equipmentTypeLabel(meta)
      const placed: AutomationEquipment = {
        id: crypto.randomUUID(),
        levelId: activeLevelId,
        equipmentId: meta.id ?? null,
        code: `${typeLabel}-${counter.current}`,
        posXM: 0,
        posYM: 0,
        posZM: 0,
        rotationDeg: 0,
        tiltDeg: 0,
        lengthM: meta.defaultLengthM ?? 1.0,
        widthM: meta.defaultWidthM ?? 1.0,
        heightM: meta.defaultHeightM ?? 1.0,
        status: 'ACTIVE',
      }
      setEquipment((es) => [...es, placed])
      setSelectedId(placed.id)
      setDirty(true)
    },
    [activeLevelId, t],
  )

  // Place a GTP station as a "workstation" box (no master-data equipment — it references the station
  // via stationId). Positionable and connectable to conveyors like any other equipment.
  const placeWorkstation = useCallback(
    (station: Station) => {
      if (!activeLevelId) {
        setError(t('addLevelFirst', 'Add a level first'))
        return
      }
      counter.current += 1
      const placed: AutomationEquipment = {
        id: crypto.randomUUID(),
        levelId: activeLevelId,
        equipmentId: null,
        stationId: station.id,
        category: 'workstation',
        code: station.code || `WS-${counter.current}`,
        posXM: 0,
        posYM: 0,
        posZM: 0,
        rotationDeg: 0,
        tiltDeg: 0,
        lengthM: 1.2,
        widthM: 1.2,
        heightM: 1.0,
        status: 'ACTIVE',
      }
      setEquipment((es) => [...es, placed])
      setSelectedId(placed.id)
      setDirty(true)
    },
    [activeLevelId, t],
  )

  const deleteSelected = useCallback(() => {
    if (!selectedId) return
    setEquipment((es) => es.filter((e) => e.id !== selectedId))
    // Drop any connections that referenced the removed item so we don't keep dangling links.
    setConnections((cs) =>
      cs.filter((c) => c.fromPlacedId !== selectedId && c.toPlacedId !== selectedId),
    )
    // Drop function points that lived on the removed item.
    setFunctionPoints((fps) => fps.filter((f) => f.placedId !== selectedId))
    if (connectFrom === selectedId) setConnectFrom(null)
    setSelectedId(null)
    setDirty(true)
  }, [selectedId, connectFrom])

  // ---- connection helpers ------------------------------------------------
  // A click on a piece of equipment while connect mode is on. First pick = source; second pick =
  // target (creates the connection). Clicking the same item cancels the in-progress pick.
  const handleConnectPick = useCallback(
    (placedId: string) => {
      // Side-effects OUTSIDE any state updater (an updater may run more than once → duplicate links).
      if (!connectFrom) {
        setConnectFrom(placedId)
        return
      }
      if (connectFrom === placedId) {
        setConnectFrom(null) // clicking the source again cancels
        return
      }
      setConnections((cs) => [
        ...cs,
        {
          id: crypto.randomUUID(),
          fromPlacedId: connectFrom,
          toPlacedId: placedId,
          fromPointId: null,
          toPointId: null,
          label: null,
          status: 'ACTIVE',
        },
      ])
      setConnectFrom(null) // ready to chain another from-pick
      setDirty(true)
    },
    [connectFrom],
  )

  const deleteConnection = useCallback((id: string) => {
    setConnections((cs) => cs.filter((c) => c.id !== id))
    setSelectedConnId((s) => (s === id ? null : s))
    setDirty(true)
  }, [])

  const addConnection = useCallback((conn: AutomationConnection) => {
    setConnections((cs) => [...cs, conn])
    setDirty(true)
  }, [])

  // Edit an existing connection in place (anchored path points, label, status). Mutates the
  // in-memory model so the existing Save (PUT /automation/topology) round-trips the change, exactly
  // like the other editor edits.
  const updateConnection = useCallback((id: string, patch: Partial<AutomationConnection>) => {
    setConnections((cs) => cs.map((c) => (c.id === id ? { ...c, ...patch } : c)))
    setDirty(true)
  }, [])

  const toggleConnectMode = useCallback(() => {
    setConnectMode((on) => {
      const next = !on
      setConnectFrom(null)
      if (next) {
        setSelectedId(null)
        setDrawPath(false)
        // Connect / draw / route-test are mutually exclusive — entering one exits the others.
        setTestMode(false)
        setTestStart(null)
        setTestTarget(null)
      }
      return next
    })
  }, [])

  // ---- route test mode -----------------------------------------------------
  const exitTestMode = useCallback(() => {
    setTestMode(false)
    setTestStart(null)
    setTestTarget(null)
  }, [])

  // Toggle the "Test route" mode. Entering loads (and caches) the projected routing graph; a
  // warehouse without one gets an error chip telling the user to generate it first.
  const toggleTestMode = useCallback(async () => {
    if (testMode) {
      exitTestMode()
      return
    }
    if (!warehouseId) {
      setError(t('noActiveWarehouse', 'No active warehouse selected'))
      return
    }
    // Exclusive with the editing modes — entering test exits connect/draw and drops selection.
    setConnectMode(false)
    setConnectFrom(null)
    setDrawPath(false)
    setSelectedId(null)
    setTestLoading(true)
    setError(null)
    try {
      const topo = testTopo ?? (await loadTopology(warehouseId))
      if (topo.nodes.length === 0) {
        setError(t('noRoutingGraph', 'No routing graph for this warehouse — Generate routing first.'))
        return
      }
      setTestTopo(topo)
      setTestStart(null)
      setTestTarget(null)
      setView('3d') // the test overlay lives in the 3D scene
      setTestMode(true)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setTestLoading(false)
    }
  }, [testMode, testTopo, warehouseId, exitTestMode, t])

  // A scene click in test mode, already resolved to the nearest routing node. First pick = start,
  // second = target; a third pick restarts with a new start.
  const handleTestPick = useCallback(
    (code: string) => {
      if (!testStart || testTarget) {
        setTestStart(code)
        setTestTarget(null)
      } else {
        setTestTarget(code)
      }
    },
    [testStart, testTarget],
  )

  // Esc exits test mode (like leaving draw mode in the 2D plan).
  useEffect(() => {
    if (!testMode) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') exitTestMode()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [testMode, exitTestMode])

  // Safety net for mutual exclusion: if an editing mode turns on while testing, leave test mode.
  useEffect(() => {
    if (testMode && (drawPath || connectMode)) exitTestMode()
  }, [testMode, drawPath, connectMode, exitTestMode])

  // ---- pick-in-3D mode (workstation conveyor interactions) -----------------
  const exitPickFp = useCallback(() => {
    setPickFpMode(false)
    pickFpHandlerRef.current = null
  }, [])

  // Arm pick mode for ONE pending interaction row: the panel hands us the callback that will
  // receive the picked function point id (the same value its Select would have set). Exclusive
  // with connect/draw/test — entering pick exits the others (but KEEPS the selection, so the
  // workstation's panel stays open to receive the pick).
  const armPickFp = useCallback(
    (onPicked: (fpId: string) => void) => {
      setConnectMode(false)
      setConnectFrom(null)
      setDrawPath(false)
      exitTestMode()
      setView('3d') // the markers to click live in the 3D scene
      pickFpHandlerRef.current = onPicked
      setPickFpMode(true)
    },
    [exitTestMode],
  )

  // A function-point marker clicked while pick mode is armed: deliver the id and disarm.
  const handlePickFp = useCallback(
    (fpId: string) => {
      pickFpHandlerRef.current?.(fpId)
      exitPickFp()
    },
    [exitPickFp],
  )

  // Esc cancels the pick (like leaving connect/test).
  useEffect(() => {
    if (!pickFpMode) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') exitPickFp()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [pickFpMode, exitPickFp])

  // Safety nets: another mode turning on disarms the pick, and a selection change (the panel that
  // armed it unmounts) drops the now-stale callback.
  useEffect(() => {
    if (pickFpMode && (drawPath || connectMode || testMode)) exitPickFp()
  }, [pickFpMode, drawPath, connectMode, testMode, exitPickFp])
  useEffect(() => {
    exitPickFp()
  }, [selectedId, exitPickFp])

  // Dijkstra over the directed routing edges — recomputed when both endpoints are picked.
  const testResult = useMemo<RouteResult | null>(() => {
    if (!testMode || !testTopo || !testStart || !testTarget) return null
    return findRoute(testTopo, testStart, testTarget)
  }, [testMode, testTopo, testStart, testTarget])

  const testVerdict = useMemo(
    () => (testResult && testStart && testTarget ? routeVerdict(testResult, testStart, testTarget, t) : null),
    [testResult, testStart, testTarget, t],
  )

  // Conveyor-top height in the scene: route-test overlays render slightly above it.
  const conveyorTopM = useMemo(() => {
    let h = 0
    for (const e of equipment) {
      if (isConveyor(e, libById)) h = Math.max(h, e.heightM + e.posYM)
    }
    return h > 0 ? h : 1
  }, [equipment, libById])

  // Lookup of placed item (any level) by id, for resolving connection endpoints to codes.
  const equipmentById = useMemo(() => {
    const m = new Map<string, AutomationEquipment>()
    for (const e of equipment) m.set(e.id, e)
    return m
  }, [equipment])

  // Group the equipment library by type for the left panel. "Unplaced only" hides any library
  // entry that already has at least one placement in the editor.
  const libraryGroups = useMemo(() => {
    const groups = new Map<string, Equipment[]>()
    for (const e of library) {
      if (unplacedOnly && e.id && (placementCounts.get(e.id) ?? 0) > 0) continue
      const key = equipmentTypeLabel(e)
      const arr = groups.get(key) ?? []
      arr.push(e)
      groups.set(key, arr)
    }
    return [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]))
  }, [library, unplacedOnly, placementCounts])

  if (!warehouseId) {
    return (
      <div className="alert alert-danger" style={{ margin: '1rem 0' }}>
        No active warehouse selected — pick one in the top-bar switcher.
      </div>
    )
  }

  return (
    <div className={`atopo${collapsed ? ' is-collapsed' : ''}`}>
      {/* ---- top toolbar: level tabs + actions ---- */}
      <div className="atopo-toolbar">
        <div className="atopo-levels">
          {levels.map((l) => (
            <button
              key={l.id}
              type="button"
              className={`atopo-leveltab${l.id === activeLevelId ? ' is-active' : ''}`}
              onClick={() => {
                setActiveLevelId(l.id)
                setSelectedId(null)
              }}
              title={t('elevationTitle', 'Elevation {m} m').replace('{m}', String(l.elevationM))}
            >
              {l.number} · {l.name}
            </button>
          ))}
          <button type="button" className="btn btn-ghost btn-sm" onClick={addLevel}>
            {t('addLevel', '+ Add level')}
          </button>
        </div>
        <div className="atopo-actions">
          <div className="atopo-viewtoggle" role="group" aria-label={t('viewGroup', 'View')}>
            <button
              type="button"
              className={`atopo-viewbtn${view === '3d' ? ' is-active' : ''}`}
              onClick={() => setView('3d')}
            >
              {t('view3d', '3D')}
            </button>
            <button
              type="button"
              className={`atopo-viewbtn${view === '2d' ? ' is-active' : ''}`}
              onClick={() => {
                exitTestMode() // the test overlay only lives in the 3D scene
                exitPickFp() // ...and so do the pickable function-point markers
                setView('2d')
              }}
            >
              {t('view2dPlan', '2D plan')}
            </button>
          </div>
          {dirty && <span className="atopo-dirty">{t('unsavedChanges', 'Unsaved changes')}</span>}
          {/* Manual Connect removed: physical connections are inferred from geometry by the routing
              projection; GTP workstation role-interactions are set in the Properties panel. */}
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={projectGraph}
            disabled={loading || saving || projecting}
            title={t('generateRoutingTip', 'Generate the conveyor routing graph (nodes/edges) from this layout. Saves first, then replaces the Routing graph.')}
          >
            {projecting ? t('generating', 'Generating…') : t('generateRouting', 'Generate routing')}
          </button>
          <button
            type="button"
            className={`btn btn-ghost btn-sm${testMode ? ' atopo-testbtn-on' : ''}`}
            onClick={toggleTestMode}
            disabled={loading || saving || projecting || testLoading}
            aria-pressed={testMode}
            title={t('testRouteTip', 'Test the routing graph: click a start point and a target in the 3D view to see whether (and how) a tote can travel between them.')}
          >
            {testLoading ? t('loadingGraph', 'Loading graph…') : testMode ? t('exitTest', 'Exit test') : t('testRoute', 'Test route')}
          </button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={load} disabled={loading || saving}>
            {loading ? t('loading', 'Loading…') : t('reload', 'Reload')}
          </button>
          {canWrite ? (
            <button type="button" className="btn btn-primary btn-sm" onClick={save} disabled={saving || loading}>
              {saving ? t('saving', 'Saving…') : t('save', 'Save')}
            </button>
          ) : (
            <span className="badge badge-info" title={t('viewOnlyNote', 'You have read access to the topology. Saving is disabled.')}>
              {t('viewOnly', 'View only')}
            </span>
          )}
          {onToggleChrome && (
            <button
              type="button"
              className="atopo-fold"
              onClick={onToggleChrome}
              aria-pressed={collapsed}
              title={collapsed ? t('unfoldHeader', 'Unfold header (more controls)') : t('foldHeader', 'Fold header up (more canvas)')}
            >
              {collapsed ? '▾' : '▴'}
            </button>
          )}
        </div>
      </div>

      {error && <div className="alert alert-danger">{error}</div>}
      {!error && info && <div className="atopo-info">{info}</div>}

      {/* inline editor for the active level's name + elevation (hidden when chrome is folded) */}
      {activeLevel && !collapsed && (
        <div className="atopo-levelmeta glass">
          <label className="atopo-inline">
            <span>
              {t('levelName', 'Level name')}{' '}
              <InfoTip text={t('levelNameTip', 'Display name for this floor/level of the automation layout.')} example="Mezzanine" />
            </span>
            <input
              className="form-control"
              value={activeLevel.name}
              onChange={(e) => patchActiveLevel({ name: e.target.value })}
            />
          </label>
          <label className="atopo-inline">
            <span>
              {t('elevationM', 'Elevation (m)')}{' '}
              <InfoTip text={t('elevationTip', "Height of this level's floor above the ground datum, in metres.")} example="5" />
            </span>
            <input
              className="form-control"
              type="number"
              step={0.1}
              value={activeLevel.elevationM}
              onChange={(e) => patchActiveLevel({ elevationM: num(e.target.value) })}
            />
          </label>
        </div>
      )}

      <div className="atopo-body">
        {/* ---- left: equipment library ---- */}
        <aside className="atopo-panel atopo-library glass">
          <h3>{t('equipmentLibrary', 'Equipment library')}</h3>
          {library.length > 0 && (
            <label className="md-check atopo-unplaced">
              <input
                type="checkbox"
                checked={unplacedOnly}
                onChange={(e) => setUnplacedOnly(e.target.checked)}
              />
              {t('unplacedOnly', 'Unplaced only')}
            </label>
          )}
          {library.length === 0 ? (
            <p className="atopo-muted">
              {t('noEquipment', 'No equipment — create some in Master data → Equipment.')}
            </p>
          ) : libraryGroups.length === 0 ? (
            <p className="atopo-muted">{t('allEquipmentPlaced', 'All equipment is placed.')}</p>
          ) : (
            libraryGroups.map(([type, items]) => (
              <div key={type} className="atopo-libgroup">
                <div className="atopo-libgroup-head">{type}</div>
                {items.map((e) => {
                  const placedCount = e.id ? placementCounts.get(e.id) ?? 0 : 0
                  return (
                    <div key={e.id} className="atopo-librow">
                      <span className="atopo-librow-label">
                        {e.model || e.subtype || e.family}
                        {e.vendor ? <span className="atopo-muted"> · {e.vendor}</span> : null}
                        <span
                          className={`atopo-badge ${placedCount > 0 ? 'is-placed' : 'is-unplaced'}`}
                        >
                          {placedCount > 0 ? t('placedN', 'placed {n}').replace('{n}', String(placedCount)) : t('notPlaced', 'not placed')}
                        </span>
                      </span>
                      <button
                        type="button"
                        className="btn btn-outline btn-sm"
                        onClick={() => addFromLibrary(e)}
                        disabled={!activeLevelId}
                      >
                        {t('add', '+ Add')}
                      </button>
                    </div>
                  )
                })}
              </div>
            ))
          )}

          {/* GTP workplaces — placeable as "workstation" boxes that link to a gtp_station and can be
              connected to conveyors. Sourced from the GTP config, not the equipment library. */}
          {stations.length > 0 && (
            <div className="atopo-libgroup">
              <div className="atopo-libgroup-head">{t('gtpWorkplaces', 'GTP workplaces')}</div>
              {stations.map((s) => {
                const placedCount = equipment.filter((e) => e.stationId === s.id).length
                return (
                  <div key={s.id} className="atopo-librow">
                    <span className="atopo-librow-label">
                      {s.code}
                      {s.name ? <span className="atopo-muted"> · {s.name}</span> : null}
                      <span className={`atopo-badge ${placedCount > 0 ? 'is-placed' : 'is-unplaced'}`}>
                        {placedCount > 0 ? t('placedN', 'placed {n}').replace('{n}', String(placedCount)) : t('notPlaced', 'not placed')}
                      </span>
                    </span>
                    <button
                      type="button"
                      className="btn btn-outline btn-sm"
                      onClick={() => placeWorkstation(s)}
                      disabled={!activeLevelId}
                    >
                      {t('add', '+ Add')}
                    </button>
                  </div>
                )
              })}
            </div>
          )}
        </aside>

        {/* ---- center: 3D canvas ---- */}
        <div className="atopo-canvas glass">
          {levels.length === 0 ? (
            <div className="atopo-empty">
              <p>{t('noLevelsYet', 'This warehouse has no automation levels yet.')}</p>
              <button type="button" className="btn btn-primary btn-sm" onClick={addLevel}>
                {t('addFirstLevel', '+ Add the first level')}
              </button>
            </div>
          ) : view === '2d' ? (
            <PlanEditor2D
              items={levelEquipment}
              libById={libById}
              functionPoints={functionPoints}
              selectedId={selectedId}
              drawing={drawPath}
              activeFromIdx={activeFromIdx}
              connectMode={connectMode}
              connectFrom={connectFrom}
              onConnectPick={handleConnectPick}
              connections={connections}
              onSelect={setSelectedId}
              onPatch={patchEquipment}
              onDeleteWaypoint={deleteWaypoint}
              onDrawAt={drawSectionAt}
              onStartDrawAt={startDrawAtWaypoint}
              onExitDraw={exitDraw}
              onAddFunctionPoint={addFunctionPoint}
              onDeleteFunctionPoint={deleteFunctionPoint}
              onUpdateFunctionPoint={updateFunctionPoint}
              onEditFunctionPoint={setEditFpId}
              onAddDivertBranch={addDivertBranch}
              onAddAsrsPortStub={addAsrsPortStub}
              onWaypointSelect={setPlanSelectedWp}
              onDeleteConnection={deleteConnection}
            />
          ) : (
            <>
            <Canvas camera={{ position: [12, 12, 12], fov: 50 }}>
              <color attach="background" args={['#081e16']} />
              <ambientLight intensity={0.6} />
              <directionalLight position={[10, 18, 8]} intensity={0.9} castShadow />
              <Grid
                args={[60, 60]}
                cellSize={1}
                cellThickness={0.6}
                cellColor="#234"
                sectionSize={5}
                sectionThickness={1}
                sectionColor="#3a6"
                fadeDistance={70}
                fadeStrength={1}
                infiniteGrid
                position={[0, 0, 0]}
              />
              <SceneContent
                items={levelEquipment}
                allItems={equipment}
                levels={levels}
                connections={connections}
                functionPoints={functionPoints}
                selectedConnId={selectedConnId}
                connectMode={connectMode}
                connectFrom={connectFrom}
                testMode={testMode}
                pickFpMode={pickFpMode}
                onPickFunctionPoint={handlePickFp}
                onPickCancel={exitPickFp}
                lib={libById}
                selectedId={selectedId}
                drawPath={drawPath}
                activeFromIdx={activeFromIdx}
                onSelect={setSelectedId}
                onEditFunctionPoint={setEditFpId}
                onConnectPick={handleConnectPick}
                onMove={(id, x, z, rotDeg) =>
                  patchEquipment(id, { posXM: x, posZM: z, rotationDeg: rotDeg })
                }
                onDrawSectionAt={drawSectionAt}
                onAnchorWaypoint={anchorWaypoint}
                onMoveWaypoint={moveWaypoint}
                onHandleDragChange={(active) => setOrbitEnabled(!active)}
              />
              {testMode && testTopo && (
                <RouteTestOverlay
                  topo={testTopo}
                  startCode={testStart}
                  targetCode={testTarget}
                  result={testResult}
                  surfaceM={conveyorTopM}
                  onPick={handleTestPick}
                />
              )}
              <OrbitControls
                makeDefault
                enabled={orbitEnabled}
                enableDamping
                enablePan
                enableZoom
                enableRotate
                screenSpacePanning
                panSpeed={1.2}
                zoomSpeed={1.1}
                minDistance={2}
                maxDistance={150}
                maxPolarAngle={Math.PI / 2.05}
                mouseButtons={{ LEFT: THREE.MOUSE.ROTATE, MIDDLE: THREE.MOUSE.DOLLY, RIGHT: THREE.MOUSE.PAN }}
              />
            </Canvas>
            <div className="atopo-hint">
              {pickFpMode ? (
                t('hintPickFp', 'Click a function point in the scene (Esc to cancel)')
              ) : testMode ? (
                <>
                  {!testStart
                    ? t('hintTestStart', 'Route test: click a start point, then a target — Esc to exit')
                    : !testTarget
                      ? t('hintTestTarget', 'Route test: start {start} — click a target — Esc to exit').replace('{start}', testStart)
                      : t('hintTestRestart', 'Route test: click to pick a new start — Esc to exit')}
                  {testVerdict && (
                    <span className={`atopo-route-chip ${testVerdict.ok ? 'is-ok' : 'is-bad'}`}>
                      {testVerdict.text}
                    </span>
                  )}
                </>
              ) : connectMode ? (
                connectFrom
                  ? t('hintConnectTarget', 'Connect: from {code} — click a target (or the source again to cancel)').replace('{code}', equipmentById.get(connectFrom)?.code ?? '?')
                  : t('hintConnectSource', 'Connect: click a source piece of equipment')
              ) : drawPath ? (
                t('hintDrawMode', 'Draw mode: click a start point then an end point — the section runs start → end')
              ) : (
                t('hintOrbit', 'Drag to orbit · right-drag to pan · scroll to zoom')
              )}
            </div>
            </>
          )}
        </div>

        {/* ---- right: properties ---- */}
        <aside className="atopo-panel atopo-props glass">
          <h3>{t('properties', 'Properties')}</h3>
          {!selected ? (
            <p className="atopo-muted">{t('selectEquipmentHint', 'Select a piece of equipment to edit it, or add one from the library.')}</p>
          ) : (
            <div className="atopo-fields">
              {/* Conveyor section tools first — the most-used drawing controls, reachable without
                  scrolling past the geometry fields. */}
              {selectedCanEditPath && (
                <ConveyorPathTools
                  eq={selected}
                  drawPath={drawPath}
                  activeFromIdx={activeFromIdx}
                  onToggleDraw={() => setDrawPath((d) => !d)}
                  onPatch={(patch) => patchEquipment(selected.id, patch)}
                  onRemoveLastSection={() => removeLastSection(selected.id)}
                  onClearAnchor={() => setActiveFromIdx(null)}
                />
              )}
              <label className="atopo-field">
                <span>{t('code', 'Code')}</span>
                <input
                  className="form-control"
                  value={selected.code}
                  onChange={(e) => patchEquipment(selected.id, { code: e.target.value })}
                />
              </label>
              <label className="atopo-field">
                <span>{t('level', 'Level')}</span>
                <Select
                  ariaLabel={t('level', 'Level')}
                  value={selected.levelId}
                  onChange={(v) => patchEquipment(selected.id, { levelId: v })}
                  options={levels.map((l) => ({ value: l.id, label: `${l.number} · ${l.name}` }))}
                />
              </label>
              <div className="atopo-grid2">
                <NumField label={t('posX', 'Pos X (m)')} value={selected.posXM} onChange={(v) => patchEquipment(selected.id, { posXM: v })} />
                <NumField label={t('posZ', 'Pos Z (m)')} value={selected.posZM} onChange={(v) => patchEquipment(selected.id, { posZM: v })} />
                <NumField label={t('posY', 'Pos Y (m)')} value={selected.posYM} onChange={(v) => patchEquipment(selected.id, { posYM: v })} />
                <NumField label={t('rotationDeg', 'Rotation (°)')} value={selected.rotationDeg} onChange={(v) => patchEquipment(selected.id, { rotationDeg: v })} />
                <NumField label={t('tiltDeg', 'Tilt (°)')} value={selected.tiltDeg} onChange={(v) => patchEquipment(selected.id, { tiltDeg: v })} />
              </div>
              <div className="atopo-grid2">
                {!hasPath(selected) && (
                  <NumField label={t('lengthM', 'Length (m)')} value={selected.lengthM} onChange={(v) => patchEquipment(selected.id, { lengthM: v })} />
                )}
                <NumField label={t('widthM', 'Width (m)')} value={selected.widthM} onChange={(v) => patchEquipment(selected.id, { widthM: v })} />
                <NumField label={t('heightM', 'Height (m)')} value={selected.heightM} onChange={(v) => patchEquipment(selected.id, { heightM: v })} />
              </div>

              <FunctionPointsPanel
                placedId={selected.id}
                points={selectedFunctionPoints}
                processTypes={selectedMeta?.processTypes ?? null}
                onAdd={addFunctionPoint}
                onUpdate={updateFunctionPoint}
                onDelete={deleteFunctionPoint}
              />

              <NodeLinksPanel
                eq={selected}
                equipment={equipment}
                lib={libById}
                connections={connections}
                focusIndex={planSelectedWp && planSelectedWp.eqId === selected.id ? planSelectedWp.index : null}
                onAdd={addConnection}
                onDelete={deleteConnection}
              />

              {selectedIsAsrs && warehouseId && (
                <StorageAreasPanel
                  warehouseId={warehouseId}
                  equipmentId={selected.equipmentId ?? null}
                />
              )}

              {selectedIsWorkstation && (
                <label className="atopo-field">
                  <span>
                    {t('gtpWorkplace', 'GTP workplace')}{' '}
                    <InfoTip
                      text={t('gtpWorkplaceTip', 'The goods-to-person workplace this box represents. Connect it to conveyors to model how work reaches it.')}
                      example="PP1 — Pick Place 1"
                    />
                  </span>
                  <Select
                    ariaLabel={t('gtpWorkplace', 'GTP workplace')}
                    value={selected.stationId ?? ''}
                    onChange={(v) => patchEquipment(selected.id, { stationId: v || null })}
                    options={[
                      { value: '', label: t('unlinked', '— unlinked —') },
                      ...stations.map((s) => ({ value: s.id, label: s.name ? `${s.code} — ${s.name}` : s.code })),
                    ]}
                  />
                </label>
              )}

              {selectedIsWorkstation && (
                <WorkstationConveyorPanel
                  workstationId={selected.id}
                  connections={connections}
                  functionPoints={functionPoints}
                  equipment={equipment}
                  libById={libById}
                  onAdd={addConnection}
                  onDelete={deleteConnection}
                  pickArmed={pickFpMode}
                  onArmPick={armPickFp}
                  onCancelPick={exitPickFp}
                />
              )}

              <button type="button" className="btn btn-danger btn-sm atopo-delete" onClick={deleteSelected}>
                {t('deleteEquipment', 'Delete equipment')}
              </button>
            </div>
          )}

          <ConnectionsPanel
            connections={connections}
            equipmentById={equipmentById}
            selectedConnId={selectedConnId}
            onSelect={(id) => setSelectedConnId((s) => (s === id ? null : id))}
            onOpen={(id) => {
              setSelectedConnId(id)
              setDetailConnId(id)
            }}
            onDelete={deleteConnection}
          />
        </aside>
      </div>

      {/* Function-point config dialog — opened by clicking a marker in the 2D plan or 3D view. */}
      {editFpId &&
        (() => {
          const fp = functionPoints.find((f) => f.id === editFpId)
          if (!fp) return null
          const eq = equipment.find((e) => e.id === fp.placedId)
          const meta = eq?.equipmentId ? libById.get(eq.equipmentId) : undefined
          const typeOptions =
            meta?.processTypes && meta.processTypes.length > 0 ? meta.processTypes : [...FUNCTION_TYPES]
          return (
            <FunctionPointDialog
              fp={fp}
              typeOptions={typeOptions}
              onUpdate={updateFunctionPoint}
              onDelete={deleteFunctionPoint}
              onClose={() => setEditFpId(null)}
            />
          )
        })()}

      {/* Connection detail / edit dialog, opened by clicking a row in the Connections panel. */}
      {detailConnId &&
        (() => {
          const conn = connections.find((c) => c.id === detailConnId)
          if (!conn) return null
          return (
            <ConnectionDetailDialog
              conn={conn}
              equipment={equipment}
              lib={libById}
              onUpdate={updateConnection}
              onDelete={deleteConnection}
              onClose={() => setDetailConnId(null)}
            />
          )
        })()}

      <Styles />
    </div>
  )
}

// A small modal to configure a single function point: which functions it combines, its name, offset,
// side and routing node code. Opened by clicking a marker in the 2D plan or 3D view. All edits apply
// live (the fp is read fresh from state each render), so there's no separate Save — just Done/Delete.
function FunctionPointDialog({
  fp,
  typeOptions,
  onUpdate,
  onDelete,
  onClose,
}: {
  fp: AutomationFunctionPoint
  typeOptions: string[]
  onUpdate: (id: string, patch: Partial<AutomationFunctionPoint>) => void
  onDelete: (id: string) => void
  onClose: () => void
}) {
  const t = useT('topology')
  const active = fpFunctions(fp.functionType)
  // Toggle one function on/off; never let the set go empty.
  const toggle = (t: string) => {
    const next = active.includes(t) ? active.filter((x) => x !== t) : [...active, t]
    if (next.length === 0) return
    onUpdate(fp.id, { functionType: joinFunctions(next) })
  }
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="atopo-modal-backdrop" onPointerDown={onClose}>
      <div className="atopo-modal glass" onPointerDown={(e) => e.stopPropagation()}>
        <div className="atopo-modal-head">
          <h3 style={{ color: functionColorForSet(active) }}>
            {functionShortForSet(active) || fp.functionType}
          </h3>
          <button type="button" className="atopo-modal-x" onClick={onClose} aria-label={t('close', 'Close')}>
            ×
          </button>
        </div>

        <label className="atopo-field">
          <span>
            {t('functions', 'Functions')}{' '}
            <InfoTip
              text={t('functionsTip', 'A point can combine functions, e.g. a scan + divert at the same spot. Toggle the ones that apply (at least one).')}
              example="SCAN + DIV ◀"
            />
          </span>
          <div className="atopo-fps-chips" style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
            {typeOptions.map((t) => {
              const on = active.includes(t)
              const c = functionColor(t)
              return (
                <button
                  key={t}
                  type="button"
                  className={`atopo-fps-chip${on ? ' is-on' : ''}`}
                  aria-pressed={on}
                  title={t}
                  style={{
                    fontSize: 12,
                    lineHeight: 1.2,
                    padding: '3px 8px',
                    borderRadius: 999,
                    cursor: 'pointer',
                    border: `1px solid ${on ? c : 'var(--glass-border)'}`,
                    color: on ? c : 'var(--text-dim)',
                    background: on ? 'rgba(255,255,255,0.06)' : 'transparent',
                    opacity: on ? 1 : 0.7,
                  }}
                  onClick={() => toggle(t)}
                >
                  {FUNCTION_SHORT[t] ?? t}
                </button>
              )
            })}
          </div>
        </label>

        <label className="atopo-field">
          <span>{t('name', 'Name')}</span>
          <input
            className="form-control"
            value={fp.name ?? ''}
            placeholder={t('optional', 'optional')}
            autoFocus
            onChange={(e) => onUpdate(fp.id, { name: e.target.value || null })}
          />
        </label>

        <div className="atopo-grid2">
          <NumField label={t('offsetM', 'Offset (m)')} value={fp.offsetM} onChange={(v) => onUpdate(fp.id, { offsetM: v })} />
          <label className="atopo-field">
            <span>{t('side', 'Side')}</span>
            <Select
              ariaLabel={t('side', 'Side')}
              value={fp.side ?? ''}
              onChange={(v) => onUpdate(fp.id, { side: v || null })}
              options={[
                { value: '', label: '—' },
                { value: 'LEFT', label: 'LEFT' },
                { value: 'RIGHT', label: 'RIGHT' },
              ]}
            />
          </label>
        </div>

        <label className="atopo-field">
          <span>
            {t('nodeCode', 'Node code')}{' '}
            <InfoTip
              text={t('nodeCodeTip', 'Optional, maps this point to a conveyor routing node so material-flow routes can reference it.')}
              example="DIV-12"
            />
          </span>
          <input
            className="form-control"
            value={fp.nodeCode ?? ''}
            placeholder={t('optional', 'optional')}
            onChange={(e) => onUpdate(fp.id, { nodeCode: e.target.value || null })}
          />
        </label>

        {active.some(isDivertType) && (
          <label className="atopo-field">
            <span>
              {t('defaultDirection', 'Default direction')}{' '}
              <InfoTip
                text={t('defaultDirectionTip', 'Where a tote goes at this divert when no route tells it otherwise: Straight keeps it on the main line, Branch sends it down the divert. With None it stops and waits at the divert until a route arrives.')}
                example="Straight"
              />
            </span>
            <Select
              ariaLabel={t('defaultDirection', 'Default direction')}
              value={fp.defaultExit ?? ''}
              onChange={(v) => onUpdate(fp.id, { defaultExit: v || null })}
              options={[
                { value: '', label: t('noneStop', 'None (stop)') },
                { value: 'STRAIGHT', label: t('straightMainLine', 'Straight (main line)') },
                { value: 'BRANCH', label: t('branchDivert', 'Branch (divert)') },
              ]}
            />
          </label>
        )}

        <div className="atopo-modal-actions">
          <button
            type="button"
            className="btn btn-danger btn-sm"
            onClick={() => {
              onDelete(fp.id)
              onClose()
            }}
          >
            {t('delete', 'Delete')}
          </button>
          <button type="button" className="btn btn-primary btn-sm" onClick={onClose}>
            {t('done', 'Done')}
          </button>
        </div>
      </div>
    </div>
  )
}

function NumField({
  label,
  value,
  onChange,
}: {
  label: string
  value: number
  onChange: (v: number) => void
}) {
  return (
    <label className="atopo-field">
      <span>{label}</span>
      <input
        className="form-control"
        type="number"
        step={0.1}
        value={value}
        onChange={(e) => onChange(num(e.target.value))}
      />
    </label>
  )
}

// Section-graph editing tools shown in the properties panel for a selected conveyor. A conveyor is
// a DIRECTED section graph: `path` holds the waypoints, `sections` the one-way runs `[from,to]`
// between them. A point that is the `from` of 2+ sections is an automatic decision/divert point.
function ConveyorPathTools({
  eq,
  drawPath,
  activeFromIdx,
  onToggleDraw,
  onPatch,
  onRemoveLastSection,
  onClearAnchor,
}: {
  eq: AutomationEquipment
  drawPath: boolean
  activeFromIdx: number | null
  onToggleDraw: () => void
  onPatch: (patch: Partial<AutomationEquipment>) => void
  onRemoveLastSection: () => void
  onClearAnchor: () => void
}) {
  const t = useT('topology')
  const path = Array.isArray(eq.path) ? eq.path : []
  const count = path.length
  const sections = effectiveSections(eq)
  const explicitSections = Array.isArray(eq.sections) ? eq.sections.length : 0
  const decisions = decisionPoints(sections)

  // Seed a two-point path from the current straight box plus a single forward section: the two
  // centreline endpoints, posX/posZ ± length/2 projected along the box rotation (yaw about Y).
  const startFromBox = () => {
    const yaw = eq.rotationDeg * DEG
    const hx = (Math.cos(yaw) * eq.lengthM) / 2
    const hz = (Math.sin(yaw) * eq.lengthM) / 2
    onPatch({
      path: [
        [+(eq.posXM - hx).toFixed(3), +(eq.posZM - hz).toFixed(3)],
        [+(eq.posXM + hx).toFixed(3), +(eq.posZM + hz).toFixed(3)],
      ],
      sections: [[0, 1]],
    })
  }

  return (
    <div className="atopo-pathtools">
      <div className="atopo-pathtools-head">
        {t('conveyorSections', 'Conveyor sections')}{' '}
        <InfoTip
          text={t('conveyorSectionsTip', 'Draw this conveyor section by section as a directed graph: each section is a one-way run with a travel-direction arrow. Click a waypoint to branch from it, a point with 2+ outgoing sections becomes an automatic decision/divert point.')}
          example="a main line that diverts at a junction"
        />
      </div>
      <div className="atopo-pathcount">
        {count === 0
          ? t('noPathStraightBox', 'No path, rendering as a straight box.')
          : `${count === 1 ? t('pointOne', '{n} point') : t('pointMany', '{n} points')}`.replace('{n}', String(count)) +
            ' · ' +
            `${sections.length === 1 ? t('sectionOne', '{n} section') : t('sectionMany', '{n} sections')}`.replace('{n}', String(sections.length)) +
            (explicitSections === 0 && count >= 2 ? ' ' + t('implicitSequential', '(implicit sequential)') : '') +
            (decisions.size > 0
              ? ' · ' +
                `${decisions.size === 1 ? t('decisionPointOne', '{n} decision point') : t('decisionPointMany', '{n} decision points')}`.replace('{n}', String(decisions.size))
              : '')}
      </div>

      <button
        type="button"
        className={`btn btn-sm ${drawPath ? 'btn-primary' : 'btn-outline'} atopo-pathbtn`}
        onClick={onToggleDraw}
        disabled={count === 0}
        title={count === 0 ? t('seedPathFirst', 'Seed a path first with “Start from box”.') : undefined}
      >
        {drawPath ? t('drawingSections', 'Drawing sections… (click to stop)') : t('drawSections', 'Draw sections')}
      </button>

      {drawPath && (
        <div className="atopo-drawhint">
          {activeFromIdx == null
            ? t('drawHintStart', 'Click a start point (a point/body on the conveyor, or empty floor), then an end point, the section runs start → end. Keep clicking to chain.')
            : t('drawHintAnchored', 'Anchored at point {n}. Click an end point to draw the section {n} → there; it then becomes the new anchor. Click point {n} to re-pick.').replace(/\{n\}/g, String(activeFromIdx + 1))}
          {activeFromIdx != null && (
            <button type="button" className="atopo-linkbtn" onClick={onClearAnchor}>
              {t('clearAnchor', 'clear anchor')}
            </button>
          )}
        </div>
      )}

      {count === 0 && (
        <button type="button" className="btn btn-outline btn-sm atopo-pathbtn" onClick={startFromBox}>
          {t('startFromBox', 'Start from box')}
        </button>
      )}

      <label className="md-check atopo-pathcheck">
        <input
          type="checkbox"
          checked={!!eq.closed}
          onChange={(e) => onPatch({ closed: e.target.checked })}
        />
        {t('closedLoop', 'Closed loop')}{' '}
        <InfoTip
          text={t('closedLoopTip', 'Only affects implicit sequential paths (no explicit sections): when on, the path loops back from the last waypoint to the first.')}
          example="a recirculating sorter loop"
        />
      </label>

      <div className="atopo-pathrow">
        <button
          type="button"
          className="btn btn-outline btn-sm"
          onClick={onRemoveLastSection}
          disabled={explicitSections === 0}
        >
          {t('removeLastSection', 'Remove last section')}
        </button>
        <button
          type="button"
          className="btn btn-outline btn-sm"
          onClick={() => onPatch({ path: null, sections: null })}
          disabled={count === 0}
        >
          {t('clear', 'Clear')}
        </button>
      </div>
    </div>
  )
}

// The "Connections" block of the selected equipment's properties: for each of its endpoint nodes
// (conveyor ends, ASRS stub ends), whether, and HOW, it is linked where it meets other equipment:
// auto-inferred by proximity (the routing projection links nodes of DIFFERENT equipment within
// 1.5 m, closest pair per equipment pair) or an explicit node-level connection from the model.
// From here the user draws an explicit link (candidates of other equipment sorted CLOSEST FIRST)
// or unlinks one. Mirrors the projection via the pure nodeLinks module, so what this panel shows
// is exactly what "Generate routing" will stitch.
function NodeLinksPanel({
  eq,
  equipment,
  lib,
  connections,
  focusIndex = null,
  onAdd,
  onDelete,
}: {
  eq: AutomationEquipment
  // ALL placed equipment (any level): the projection ignores levels, adjacency is world XZ.
  equipment: AutomationEquipment[]
  lib: Map<string, Equipment>
  connections: AutomationConnection[]
  // When a single node (2D waypoint) is selected, show only that node's connection options.
  focusIndex?: number | null
  onAdd: (conn: AutomationConnection) => void
  onDelete: (id: string) => void
}) {
  const t = useT('topology')
  // Honest feedback after link/unlink (e.g. "explicit link removed; still auto-linked by proximity").
  const [note, setNote] = useState<string | null>(null)

  const nodesByEquip = useMemo(
    () => equipment.map((e) => equipmentNodes(e, category(e, lib))),
    [equipment, lib],
  )
  const allRows = useMemo(
    () => nodeLinkStatuses(eq.id, nodesByEquip, connections),
    [eq.id, nodesByEquip, connections],
  )
  // A selected waypoint scopes the list to its node; an override shows all again. A waypoint that
  // is not a routable node (a mid-path corner) keeps the full list.
  const [showAll, setShowAll] = useState(false)
  useEffect(() => {
    setShowAll(false)
  }, [eq.id, focusIndex])
  const focusedRows = focusIndex == null ? allRows : allRows.filter((r) => r.node.index === focusIndex)
  const focused = !showAll && focusIndex != null && focusedRows.length > 0
  const rows = focused ? focusedRows : allRows
  // Clear the feedback note when the selection moves to another equipment.
  useEffect(() => {
    setNote(null)
  }, [eq.id])

  return (
    <div className="atopo-links">
      <div className="atopo-links-head">
        {t('connections', 'Connections')}{' '}
        <InfoTip
          text={t('connectionsTip', 'Whether each end node of this equipment is linked where it meets another (an ASRS outfeed onto a conveyor infeed). Nodes of different equipment within 1.5 m link automatically when the routing is generated; an explicit link forces one at any distance. A link joins the two systems; it does not set a direction of travel: each conveyor\u2019s own section arrows govern which way totes actually move across the touchpoint.')}
          example="OUT stub end ↔ BIN_CONVEYOR-1#0 · 0.3 m · auto"
        />
      </div>
      {focused && (
        <p className="atopo-muted atopo-links-scope">
          {t('selectedNodeOnly', 'Selected node only')} ·{' '}
          <button type="button" className="atopo-linklike" onClick={() => setShowAll(true)}>
            {t('showAllNodes', 'show all nodes')}
          </button>
        </p>
      )}
      {rows.length === 0 ? (
        <p className="atopo-muted atopo-fps-empty">{t('noRoutableNodes', 'No routable nodes on this equipment.')}</p>
      ) : (
        <ul className="atopo-links-list">
          {rows.map((row) => {
            const candidates = linkCandidates(row.node, nodesByEquip)
            return (
              <li key={row.node.index} className="atopo-links-row">
                <div className="atopo-links-node">{row.node.code}</div>

                {row.explicit.map((x) => (
                  <div key={x.connectionId} className="atopo-links-state is-linked">
                    <span>
                      ↔ {x.other.code} · {x.distM.toFixed(1)} m · {t('explicit', 'explicit')}
                      {x.distM <= ADJACENCY_M ? ' ' + t('alsoInAutoRange', '(also in auto-link range)') : ''}
                    </span>
                    <button
                      type="button"
                      className="btn btn-danger btn-sm"
                      onClick={() => {
                        onDelete(x.connectionId)
                        setNote(
                          x.distM <= ADJACENCY_M
                            ? t('noteUnlinkedStillAuto', 'Explicit link removed, {a} and {b} are still auto-linked by proximity ({m} m).')
                                .replace('{a}', row.node.code)
                                .replace('{b}', x.other.code)
                                .replace('{m}', x.distM.toFixed(1))
                            : t('noteUnlinked', 'Explicit link removed, {a} is no longer linked to {b}.')
                                .replace('{a}', row.node.code)
                                .replace('{b}', x.other.code),
                        )
                      }}
                    >
                      {t('unlink', 'Unlink')}
                    </button>
                  </div>
                ))}

                {row.auto && (
                  <div className="atopo-links-state is-linked">
                    <span>
                      ↔ {row.auto.other.code} · {row.auto.distM.toFixed(1)} m · {t('autoProximity', 'auto (proximity)')}
                    </span>
                  </div>
                )}

                {!row.auto &&
                  row.explicit.length === 0 &&
                  (row.nearest ? (
                    <div className="atopo-links-state is-unlinked">
                      <span>
                        {t('notLinkedNearest', 'not linked · nearest {code} at {m} m')
                          .replace('{code}', row.nearest.other.code)
                          .replace('{m}', row.nearest.distM.toFixed(1))}
                      </span>
                    </div>
                  ) : (
                    <div className="atopo-links-state">
                      <span className="atopo-muted">{t('noOtherEquipNodes', 'no other equipment nodes')}</span>
                    </div>
                  ))}

                <Select
                  ariaLabel={t('linkNodeTo', 'Link {code} to').replace('{code}', row.node.code)}
                  value=""
                  placeholder={candidates.length ? t('linkToClosest', 'Link to… (closest first)') : t('noOtherNodesToLink', 'No other nodes to link')}
                  disabled={candidates.length === 0}
                  onChange={(v) => {
                    const cand = candidates.find(
                      (c) => `${c.other.equipId}@${c.other.index}` === v,
                    )
                    if (!cand) return
                    onAdd({
                      id: crypto.randomUUID(),
                      fromPlacedId: eq.id,
                      toPlacedId: cand.other.equipId,
                      fromPointId: null,
                      toPointId: null,
                      fromPathIndex: row.node.index,
                      toPathIndex: cand.other.index,
                      label: null,
                      status: 'ACTIVE',
                    })
                    setNote(
                      t('noteLinkAdded', 'Explicit link added: {a} → {b} ({m} m). Save, then Generate routing.')
                        .replace('{a}', row.node.code)
                        .replace('{b}', cand.other.code)
                        .replace('{m}', cand.distM.toFixed(1)),
                    )
                  }}
                  options={candidates.slice(0, 25).map((c) => ({
                    value: `${c.other.equipId}@${c.other.index}`,
                    label: `${c.other.code} · ${c.distM.toFixed(1)} m`,
                  }))}
                />
              </li>
            )
          })}
        </ul>
      )}
      {note && <div className="atopo-links-note">{note}</div>}
    </div>
  )
}

// Collapsible list of equipment-to-equipment connections. Clicking a row opens the detail/edit
// dialog (and highlights the link in the scene); the inline Delete button removes the link without
// opening the dialog (stops propagation).
function ConnectionsPanel({
  connections,
  equipmentById,
  selectedConnId,
  onOpen,
  onDelete,
}: {
  connections: AutomationConnection[]
  equipmentById: Map<string, AutomationEquipment>
  selectedConnId: string | null
  onSelect: (id: string) => void
  onOpen: (id: string) => void
  onDelete: (id: string) => void
}) {
  const t = useT('topology')
  const [open, setOpen] = useState(true)
  return (
    <div className="atopo-conns">
      <button
        type="button"
        className="atopo-conns-head"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
      >
        <span>{t('connectionsCount', 'Connections ({n})').replace('{n}', String(connections.length))}</span>
        <span className="atopo-conns-chevron">{open ? '▾' : '▸'}</span>
      </button>
      {open &&
        (connections.length === 0 ? (
          <p className="atopo-muted atopo-conns-empty">
            {t('connsEmpty', 'None yet, touching conveyors link automatically; select a piece of equipment to draw explicit node links in its Connections section.')}
          </p>
        ) : (
          <ul className="atopo-conns-list">
            {connections.map((c) => {
              const from = equipmentById.get(c.fromPlacedId)
              const to = equipmentById.get(c.toPlacedId)
              const dangling = !from || !to
              // Node-level links show the exact path point they anchor at (CODE#index).
              const fromLabel = `${from?.code ?? '?'}${c.fromPathIndex != null ? `#${c.fromPathIndex}` : ''}`
              const toLabel = `${to?.code ?? '?'}${c.toPathIndex != null ? `#${c.toPathIndex}` : ''}`
              return (
                <li
                  key={c.id}
                  className={`atopo-conns-row${c.id === selectedConnId ? ' is-active' : ''}`}
                >
                  <button
                    type="button"
                    className="atopo-conns-label"
                    onClick={() => onOpen(c.id)}
                    title={
                      dangling
                        ? t('endpointMissingShort', 'One endpoint is missing from this layout, click for details')
                        : t('openConnDetails', 'Open connection details')
                    }
                  >
                    {fromLabel} ↔ {toLabel}
                    {dangling ? <span className="atopo-muted"> · {t('dangling', 'dangling')}</span> : null}
                  </button>
                  <button
                    type="button"
                    className="btn btn-danger btn-sm"
                    onClick={(e) => {
                      e.stopPropagation()
                      onDelete(c.id)
                    }}
                  >
                    {t('delete', 'Delete')}
                  </button>
                </li>
              )
            })}
          </ul>
        ))}
    </div>
  )
}

// The detail / edit dialog for a single equipment-to-equipment connection, opened by clicking its
// row in the Connections panel. It shows the link un-truncated: both endpoints with their full
// equipment code AND the specific node each end is anchored to (CODE#index), whether the link is
// hand-drawn (explicit) or auto-inferred fallback, the gap in metres between the resolved nodes
// (when both ends resolve), and the status. The anchored path point of each end, the label and the
// status are editable: edits mutate the in-memory connection and round-trip through the editor's
// Save, exactly like the other editor edits. The link can also be deleted from here.
function ConnectionDetailDialog({
  conn,
  equipment,
  lib,
  onUpdate,
  onDelete,
  onClose,
}: {
  conn: AutomationConnection
  equipment: AutomationEquipment[]
  lib: Map<string, Equipment>
  onUpdate: (id: string, patch: Partial<AutomationConnection>) => void
  onDelete: (id: string) => void
  onClose: () => void
}) {
  const t = useT('topology')
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const fromEq = equipment.find((e) => e.id === conn.fromPlacedId)
  const toEq = equipment.find((e) => e.id === conn.toPlacedId)
  const fromNodes = useMemo(
    () => (fromEq ? equipmentNodes(fromEq, category(fromEq, lib)) : []),
    [fromEq, lib],
  )
  const toNodes = useMemo(() => (toEq ? equipmentNodes(toEq, category(toEq, lib)) : []), [toEq, lib])

  // Resolve each end to its node: by anchored path index when set, else the projection's
  // equipment-level fallback (exit = last node of FROM, entry = first node of TO).
  const fromNode =
    conn.fromPathIndex != null
      ? fromNodes.find((n) => n.index === conn.fromPathIndex) ?? null
      : fromNodes[fromNodes.length - 1] ?? null
  const toNode =
    conn.toPathIndex != null
      ? toNodes.find((n) => n.index === conn.toPathIndex) ?? null
      : toNodes[0] ?? null

  const fromCode = fromNode?.code ?? `${fromEq?.code ?? '?'}#?`
  const toCode = toNode?.code ?? `${toEq?.code ?? '?'}#?`
  const distM =
    fromNode && toNode ? Math.hypot(fromNode.x - toNode.x, fromNode.z - toNode.z) : null

  // An explicit row anchors a specific path point on either end; with both unset the projection
  // falls back to the equipment-level exit/entry nodes (so the link "follows" the geometry).
  const anchored = conn.fromPathIndex != null || conn.toPathIndex != null
  const isWorkstationRole = conn.fromPointId != null || conn.toPointId != null
  const dangling = !fromEq || !toEq

  // Path-point options for an end: "Auto" (null = use the projection fallback) plus every routable
  // node index on that equipment, labelled with its projected node code.
  const indexOptions = (nodes: EquipNode[], fallback: 'exit' | 'entry'): SelectOption[] => {
    const fallbackNode = fallback === 'exit' ? nodes[nodes.length - 1] : nodes[0]
    return [
      {
        value: '',
        label: fallbackNode ? t('autoWith', 'Auto ({code})').replace('{code}', fallbackNode.code) : t('auto', 'Auto'),
      },
      ...nodes.map((n) => ({ value: String(n.index), label: n.code })),
    ]
  }

  return (
    <div className="atopo-modal-backdrop" onPointerDown={onClose}>
      <div className="atopo-modal glass" onPointerDown={(e) => e.stopPropagation()}>
        <div className="atopo-modal-head">
          <h3>{t('connection', 'Connection')}</h3>
          <button type="button" className="atopo-modal-x" onClick={onClose} aria-label={t('close', 'Close')}>
            ×
          </button>
        </div>

        <div className="atopo-conn-endpoints">
          <span className="atopo-conn-code">{fromCode}</span>
          <span className="atopo-conn-arrow">↔</span>
          <span className="atopo-conn-code">{toCode}</span>
        </div>

        <dl className="atopo-conn-meta">
          <div>
            <dt>{t('type', 'Type')}</dt>
            <dd>
              {isWorkstationRole
                ? t('typeWorkstationRole', 'Workstation role interaction')
                : anchored
                  ? t('typeExplicitHandDrawn', 'Explicit node link (hand-drawn)')
                  : t('typeExplicitAuto', 'Explicit link (auto-inferred endpoints)')}
            </dd>
          </div>
          <div>
            <dt>{t('distance', 'Distance')}</dt>
            <dd>{distM != null ? `${distM.toFixed(2)} m` : t('unknownEndpointMissing', 'unknown (endpoint missing)')}</dd>
          </div>
          <div>
            <dt>{t('status', 'Status')}</dt>
            <dd>{conn.status ?? 'ACTIVE'}</dd>
          </div>
        </dl>

        {dangling && (
          <p className="atopo-links-note">
            {t('danglingNote', 'One endpoint is missing from this layout, so this link is dangling. It will be dropped on the next Generate routing. Delete it or restore the equipment.')}
          </p>
        )}

        {!isWorkstationRole && (
          <div className="atopo-grid2">
            <label className="atopo-field">
              <span>{t('fromPathPoint', 'From path point')}</span>
              <Select
                ariaLabel={t('fromPathPoint', 'From path point')}
                value={conn.fromPathIndex != null ? String(conn.fromPathIndex) : ''}
                onChange={(v) => onUpdate(conn.id, { fromPathIndex: v === '' ? null : Number(v) })}
                options={indexOptions(fromNodes, 'exit')}
              />
            </label>
            <label className="atopo-field">
              <span>{t('toPathPoint', 'To path point')}</span>
              <Select
                ariaLabel={t('toPathPoint', 'To path point')}
                value={conn.toPathIndex != null ? String(conn.toPathIndex) : ''}
                onChange={(v) => onUpdate(conn.id, { toPathIndex: v === '' ? null : Number(v) })}
                options={indexOptions(toNodes, 'entry')}
              />
            </label>
          </div>
        )}

        <label className="atopo-field">
          <span>
            {t('label', 'Label')}{' '}
            <InfoTip
              text={t('labelTip', 'Optional human-readable name for this link, shown in the Connections list and the routing graph.')}
              example="ASRS outfeed to pick line"
            />
          </span>
          <input
            className="form-control"
            value={conn.label ?? ''}
            placeholder={t('optional', 'optional')}
            onChange={(e) => onUpdate(conn.id, { label: e.target.value || null })}
          />
        </label>

        <label className="atopo-field">
          <span>{t('status', 'Status')}</span>
          <Select
            ariaLabel={t('status', 'Status')}
            value={conn.status ?? 'ACTIVE'}
            onChange={(v) => onUpdate(conn.id, { status: v })}
            options={[
              { value: 'ACTIVE', label: 'ACTIVE' },
              { value: 'INACTIVE', label: 'INACTIVE' },
            ]}
          />
        </label>

        <p className="atopo-muted atopo-conn-savehint">
          {t('savehintMemory', 'Changes apply to the layout in memory; press Save to persist them.')}
        </p>

        <div className="atopo-modal-actions">
          <button
            type="button"
            className="btn btn-danger btn-sm"
            onClick={() => {
              onDelete(conn.id)
              onClose()
            }}
          >
            {t('delete', 'Delete')}
          </button>
          <button type="button" className="btn btn-primary btn-sm" onClick={onClose}>
            {t('done', 'Done')}
          </button>
        </div>
      </div>
    </div>
  )
}

// Function points (process points) on the selected placed equipment: list + add form.
function FunctionPointsPanel({
  placedId,
  points,
  processTypes,
  onAdd,
  onUpdate,
  onDelete,
}: {
  placedId: string
  points: AutomationFunctionPoint[]
  // The library equipment's declared process types (preferred Select options), or null.
  processTypes: string[] | null
  onAdd: (fp: AutomationFunctionPoint) => void
  onUpdate: (id: string, patch: Partial<AutomationFunctionPoint>) => void
  onDelete: (id: string) => void
}) {
  const t = useT('topology')
  const typeOptions = processTypes && processTypes.length > 0 ? processTypes : [...FUNCTION_TYPES]
  const [functionType, setFunctionType] = useState<string>(typeOptions[0] ?? FUNCTION_TYPES[0])
  const [name, setName] = useState('')
  const [offsetM, setOffsetM] = useState('0')
  const [side, setSide] = useState('')
  const [nodeCode, setNodeCode] = useState('')

  // Keep the selected functionType valid if the options change (e.g. selecting a different item).
  useEffect(() => {
    if (!typeOptions.includes(functionType)) setFunctionType(typeOptions[0] ?? FUNCTION_TYPES[0])
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [placedId])

  const add = () => {
    onAdd({
      id: crypto.randomUUID(),
      placedId,
      functionType,
      name: name.trim() || null,
      offsetM: num(offsetM),
      side: side || null,
      nodeCode: nodeCode.trim() || null,
      status: 'ACTIVE',
    })
    setName('')
    setOffsetM('0')
    setSide('')
    setNodeCode('')
  }

  return (
    <div className="atopo-fps">
      <div className="atopo-fps-head">
        {t('functionPoints', 'Function points')}{' '}
        <InfoTip
          text={t('functionPointsTip', 'Process points on this equipment, scanners, label applicators, diverts, DWS, query points, wrappers, induct/discharge. Each sits at an offset along the equipment.')}
          example="a scanner 1.5 m in on the left"
        />
      </div>

      {points.length === 0 ? (
        <p className="atopo-muted atopo-fps-empty">{t('noneYet', 'None yet.')}</p>
      ) : (
        <ul className="atopo-fps-list">
          {points.map((fp) => {
            const active = fpFunctions(fp.functionType)
            // Toggle a single function on/off for this point. Require at least one to remain.
            const toggle = (t: string) => {
              const next = active.includes(t)
                ? active.filter((x) => x !== t)
                : [...active, t]
              if (next.length === 0) return // don't allow emptying
              onUpdate(fp.id, { functionType: joinFunctions(next) })
            }
            return (
              <li key={fp.id} className="atopo-fps-row">
                <span className="atopo-fps-label">
                  <span
                    className="atopo-fps-type"
                    style={{ color: functionColorForSet(active) }}
                  >
                    {functionShortForSet(active) || fp.functionType}
                  </span>
                  {fp.name ? <span> · {fp.name}</span> : null}
                  <span className="atopo-muted">
                    {' '}
                    @ {fp.offsetM} m{fp.side ? ` · ${fp.side}` : ''}
                  </span>
                  <span
                    className="atopo-fps-chips"
                    style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginTop: 4 }}
                  >
                    {typeOptions.map((t) => {
                      const on = active.includes(t)
                      const c = functionColor(t)
                      return (
                        <button
                          key={t}
                          type="button"
                          className={`atopo-fps-chip${on ? ' is-on' : ''}`}
                          aria-pressed={on}
                          title={t}
                          style={{
                            fontSize: 11,
                            lineHeight: 1.2,
                            padding: '2px 6px',
                            borderRadius: 999,
                            cursor: 'pointer',
                            border: `1px solid ${on ? c : 'var(--border, #44504a)'}`,
                            color: on ? c : 'var(--muted, #9aa6a0)',
                            background: on ? 'rgba(255,255,255,0.06)' : 'transparent',
                            opacity: on ? 1 : 0.7,
                          }}
                          onClick={() => toggle(t)}
                        >
                          {FUNCTION_SHORT[t] ?? t}
                        </button>
                      )
                    })}
                  </span>
                </span>
                <button
                  type="button"
                  className="btn btn-danger btn-sm"
                  onClick={() => onDelete(fp.id)}
                >
                  {t('delete', 'Delete')}
                </button>
              </li>
            )
          })}
        </ul>
      )}

      <div className="atopo-fps-form">
        <label className="atopo-field">
          <span>{t('functionType', 'Function type')}</span>
          <Select
            ariaLabel={t('functionType', 'Function type')}
            value={functionType}
            onChange={setFunctionType}
            options={typeOptions.map((opt) => ({ value: opt, label: opt }))}
          />
        </label>
        <label className="atopo-field">
          <span>{t('name', 'Name')}</span>
          <input
            className="form-control"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('optional', 'optional')}
          />
        </label>
        <div className="atopo-grid2">
          <label className="atopo-field">
            <span>{t('offsetM', 'Offset (m)')}</span>
            <input
              className="form-control"
              type="number"
              step={0.1}
              value={offsetM}
              onChange={(e) => setOffsetM(e.target.value)}
            />
          </label>
          <label className="atopo-field">
            <span>{t('side', 'Side')}</span>
            <Select
              ariaLabel={t('side', 'Side')}
              value={side}
              onChange={setSide}
              options={[
                { value: '', label: '—' },
                { value: 'LEFT', label: 'LEFT' },
                { value: 'RIGHT', label: 'RIGHT' },
              ]}
            />
          </label>
        </div>
        <label className="atopo-field">
          <span>
            {t('nodeCode', 'Node code')}{' '}
            <InfoTip
              text={t('nodeCodeTip', 'Optional, maps this point to a conveyor routing node so material-flow routes can reference it.')}
              example="DIV-12"
            />
          </span>
          <input
            className="form-control"
            value={nodeCode}
            onChange={(e) => setNodeCode(e.target.value)}
            placeholder={t('optional', 'optional')}
          />
        </label>
        <button type="button" className="btn btn-outline btn-sm" onClick={add}>
          {t('addFunctionPoint', '+ Add function point')}
        </button>
      </div>
    </div>
  )
}

// Roles a GTP workplace's conveyor interaction can play. STOCK = stock-feed conveyor (e.g. from an
// ASRS), ORDER = order conveyor/destination, DECANT = a decant point. Stored on the connection label.
const WORKSTATION_ROLES = ['STOCK', 'ORDER', 'DECANT']

// A workplace's conveyor interactions: connections from this workstation to a SPECIFIC function point
// on a conveyor, each tagged with a role. A pick-place typically has two (STOCK + ORDER); a decant
// station one or none. Reuses the existing connection model (toPointId + label).
function WorkstationConveyorPanel({
  workstationId,
  connections,
  functionPoints,
  equipment,
  libById,
  onAdd,
  onDelete,
  pickArmed,
  onArmPick,
  onCancelPick,
}: {
  workstationId: string
  connections: AutomationConnection[]
  functionPoints: AutomationFunctionPoint[]
  equipment: AutomationEquipment[]
  libById: Map<string, Equipment>
  onAdd: (conn: AutomationConnection) => void
  onDelete: (id: string) => void
  // Pick-in-3D: arm a scene mode where clicking a conveyor function-point marker delivers its id
  // here (same value the Select sets). Only one panel exists at a time, so the armed flag is ours.
  pickArmed: boolean
  onArmPick: (onPicked: (fpId: string) => void) => void
  onCancelPick: () => void
}) {
  const t = useT('topology')
  const [role, setRole] = useState(WORKSTATION_ROLES[0])
  const [pointId, setPointId] = useState('')

  const eqById = useMemo(() => {
    const m = new Map<string, AutomationEquipment>()
    for (const e of equipment) m.set(e.id, e)
    return m
  }, [equipment])

  const fpLabel = (fp: AutomationFunctionPoint): string => {
    const e = eqById.get(fp.placedId)
    const short = functionShortForSet(fpFunctions(fp.functionType)) || fp.functionType
    // Prefer the point's given name (set in its config dialog); fall back to its function type.
    const who = fp.name?.trim() ? fp.name.trim() : short
    return `${e?.code ?? '?'} · ${who} @ ${fp.offsetM}m`
  }

  // Selectable points: function points that live on a conveyor.
  const fpOptions = useMemo(
    () =>
      functionPoints
        .filter((fp) => {
          const e = eqById.get(fp.placedId)
          return e && isConveyor(e, libById)
        })
        .map((fp) => ({ value: fp.id, label: fpLabel(fp) })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [functionPoints, eqById, libById],
  )

  const myConns = connections.filter((c) => c.fromPlacedId === workstationId && c.toPointId)

  const add = () => {
    const fp = functionPoints.find((f) => f.id === pointId)
    if (!fp) return
    onAdd({
      id: crypto.randomUUID(),
      fromPlacedId: workstationId,
      toPlacedId: fp.placedId,
      fromPointId: null,
      toPointId: fp.id,
      label: role,
      status: 'ACTIVE',
    })
    setPointId('')
  }

  return (
    <div className="atopo-areas">
      <div className="atopo-areas-head">
        {t('conveyorInteractions', 'Conveyor interactions')}{' '}
        <InfoTip
          text={t('conveyorInteractionsTip', 'Link this workplace to specific conveyor function points, each with a role. A pick-place usually has two (a STOCK feed + an ORDER conveyor); a decant station one or none.')}
          example="STOCK · CONV-1 · SCAN @ 3m"
        />
      </div>
      {myConns.length === 0 ? (
        <p className="atopo-muted atopo-areas-empty">{t('noConveyorInteractions', 'No conveyor interactions yet.')}</p>
      ) : (
        <ul className="atopo-areas-list">
          {myConns.map((c) => {
            const fp = functionPoints.find((f) => f.id === c.toPointId)
            return (
              <li key={c.id} className="atopo-areas-row">
                <span className="atopo-areas-code">
                  <strong>{c.label ?? '—'}</strong>
                  {' · '}
                  {fp ? fpLabel(fp) : t('pointRemoved', 'point removed')}
                </span>
                <button type="button" className="btn btn-danger btn-sm" onClick={() => onDelete(c.id)}>
                  {t('remove', 'Remove')}
                </button>
              </li>
            )
          })}
        </ul>
      )}
      {/* Stacked full-width so the (often long) point list isn't squeezed into half the narrow panel
          and clipped at the screen edge. */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '.4rem', marginTop: '.4rem' }}>
        <Select
          ariaLabel={t('interactionRole', 'Interaction role')}
          value={role}
          onChange={setRole}
          options={WORKSTATION_ROLES.map((r) => ({ value: r, label: r }))}
        />
        <div style={{ display: 'flex', gap: '.4rem', alignItems: 'center' }}>
          <Select
            ariaLabel={t('conveyorFunctionPoint', 'Conveyor function point')}
            value={pointId}
            onChange={setPointId}
            style={{ flex: 1, minWidth: 0 }}
            options={[
              { value: '', label: fpOptions.length ? t('pickConveyorPoint', 'Pick a conveyor point…') : t('noConveyorPoints', 'No conveyor points yet') },
              ...fpOptions,
            ]}
          />
          <button
            type="button"
            className={`btn btn-sm ${pickArmed ? 'btn-primary' : 'btn-outline'}`}
            style={{ whiteSpace: 'nowrap' }}
            disabled={fpOptions.length === 0}
            title={t('pickIn3dTip', 'Pick the conveyor point by clicking its marker in the 3D scene (Esc to cancel)')}
            aria-pressed={pickArmed}
            onClick={() => (pickArmed ? onCancelPick() : onArmPick(setPointId))}
          >
            {pickArmed ? t('picking', 'Picking… (Esc)') : t('pickIn3d', 'Pick in 3D')}
          </button>
        </div>
        {/* The full (often long) label of the chosen point — the Select trigger truncates it. */}
        {pointId &&
          (() => {
            const fp = functionPoints.find((f) => f.id === pointId)
            return fp ? <div className="atopo-fp-picked">{fpLabel(fp)}</div> : null
          })()}
        <button type="button" className="btn btn-outline btn-sm atopo-pathbtn" disabled={!pointId} onClick={add}>
          {t('addInteraction', '+ Add interaction')}
        </button>
      </div>
    </div>
  )
}

// ASRS → storage-area linking. Lists the warehouse's storage blocks with a checkbox that binds
// each block's master-data `equipmentId` to this placed equipment's library equipment id. Persists
// immediately to master-data (independent of the topology Save).
function StorageAreasPanel({
  warehouseId,
  equipmentId,
}: {
  warehouseId: string
  // The selected placed equipment's master-data (library) equipment id.
  equipmentId: string | null
}) {
  const t = useT('topology')
  const [blocks, setBlocks] = useState<StorageBlock[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setBlocks(await listStorageBlocks(warehouseId))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [warehouseId])

  useEffect(() => {
    refresh()
  }, [refresh])

  const toggle = async (block: StorageBlock, checked: boolean) => {
    if (!block.id) return
    setBusyId(block.id)
    setError(null)
    try {
      await updateStorageBlock(block.id, {
        ...block,
        equipmentId: checked ? equipmentId : null,
      })
      await refresh()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="atopo-areas">
      <div className="atopo-areas-head">
        {t('storageAreas', 'Storage areas')}{' '}
        <InfoTip
          text={t('storageAreasTip', "Bind master-data storage blocks to this ASRS so stock in those blocks is handled by this system. Saves immediately, independent of the topology Save.")}
          example="link the AutoStore grid's block"
        />
      </div>

      {!equipmentId && (
        <p className="atopo-muted atopo-areas-empty">
          {t('placementNotLinked', "This placement isn't linked to a master-data equipment, so it can't own a storage area.")}
        </p>
      )}
      {error && <div className="alert alert-danger atopo-areas-error">{error}</div>}
      {loading ? (
        <p className="atopo-muted atopo-areas-empty">{t('loadingStorageBlocks', 'Loading storage blocks…')}</p>
      ) : blocks.length === 0 ? (
        <p className="atopo-muted atopo-areas-empty">
          {t('noStorageBlocks', 'No storage blocks — create some in Master data → Storage blocks.')}
        </p>
      ) : (
        <ul className="atopo-areas-list">
          {blocks.map((b) => {
            const linkedHere = !!equipmentId && b.equipmentId === equipmentId
            const linkedElsewhere = !!b.equipmentId && b.equipmentId !== equipmentId
            return (
              <li key={b.id} className="atopo-areas-row">
                <label className={`md-check atopo-areas-check${linkedElsewhere ? ' is-disabled' : ''}`}>
                  <input
                    type="checkbox"
                    checked={linkedHere}
                    disabled={!equipmentId || linkedElsewhere || busyId === b.id}
                    onChange={(e) => toggle(b, e.target.checked)}
                  />
                  <span className="atopo-areas-code">{b.code}</span>
                  <span className="atopo-muted"> · {b.storageType}</span>
                </label>
                {linkedElsewhere && (
                  <span className="atopo-muted atopo-areas-note">{t('linkedToAnother', 'linked to another equipment')}</span>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}

// =========================================================================
// 3D scene
// =========================================================================

interface SceneContentProps {
  items: AutomationEquipment[]
  // Every placed item across all levels — used to resolve connection endpoints (incl. cross-level).
  allItems: AutomationEquipment[]
  levels: AutomationLevel[]
  connections: AutomationConnection[]
  // All function points; we render the ones whose placedId is on the active level.
  functionPoints: AutomationFunctionPoint[]
  selectedConnId: string | null
  connectMode: boolean
  connectFrom: string | null
  // Route test mode: editing interactions (select/drag/draw/connect) are suppressed; clicks are
  // handled by the RouteTestOverlay's pick plane (rendered above the conveyor tops).
  testMode: boolean
  // Pick-in-3D mode: clicking a CONVEYOR function-point marker delivers its id to the workstation
  // panel (instead of opening the config dialog); equipment selection is suppressed; a ground
  // click cancels (like connect-cancel).
  pickFpMode: boolean
  onPickFunctionPoint: (fpId: string) => void
  onPickCancel: () => void
  lib: Map<string, Equipment>
  selectedId: string | null
  drawPath: boolean
  // The current section-draw anchor index on the selected conveyor (highlighted), or null.
  activeFromIdx: number | null
  onSelect: (id: string | null) => void
  // Open a function point's config dialog (clicking its 3D marker).
  onEditFunctionPoint: (id: string) => void
  onConnectPick: (id: string) => void
  onMove: (id: string, x: number, z: number, rotationDeg: number) => void
  // A ground-plane click while drawing: resolve/append a point and (with an anchor) add a section.
  onDrawSectionAt: (id: string, x: number, z: number) => void
  // Click an existing waypoint while drawing: re-anchor to it (branch) without adding a section.
  onAnchorWaypoint: (index: number) => void
  onMoveWaypoint: (id: string, index: number, x: number, z: number) => void
  onHandleDragChange: (active: boolean) => void
}

function SceneContent({
  items,
  allItems,
  levels,
  connections,
  functionPoints,
  selectedConnId,
  connectMode,
  connectFrom,
  testMode,
  pickFpMode,
  onPickFunctionPoint,
  onPickCancel,
  lib,
  selectedId,
  drawPath,
  activeFromIdx,
  onSelect,
  onEditFunctionPoint,
  onConnectPick,
  onMove,
  onDrawSectionAt,
  onAnchorWaypoint,
  onMoveWaypoint,
  onHandleDragChange,
}: SceneContentProps) {
  const byId = useMemo(() => {
    const m = new Map<string, AutomationEquipment>()
    for (const e of allItems) m.set(e.id, e)
    return m
  }, [allItems])

  // Scene conveyor height: ASRS IN/OUT stubs render flush with the conveyors they feed.
  const stubHeightM = useMemo(() => {
    let h = 0
    for (const e of allItems) {
      if (isConveyor(e, lib)) h = Math.max(h, e.heightM || 0)
    }
    return h > 0 ? h : undefined
  }, [allItems, lib])

  // Link state where equipment meets, recomputed live while dragging: per equipment pair the
  // closest node pair, green when the routing projection will link them (auto by proximity, or an
  // explicit node-level connection), amber when near but out of range. Mirrors the projection's
  // rules via the pure nodeLinks module.
  const meetingPoints = useMemo(
    () => computeMeetingPoints(items.map((e) => equipmentNodes(e, category(e, lib))), connections),
    [items, lib, connections],
  )

  return (
    <group>
      {/* The (invisible) ground plane. In draw mode it appends a waypoint to the selected
          conveyor; in connect mode an empty-space click cancels the in-progress pick; otherwise
          a plain left-click deselects. */}
      <mesh
        rotation-x={-Math.PI / 2}
        position={[0, -0.001, 0]}
        onPointerDown={(e: ThreeEvent<PointerEvent>) => {
          if (e.button !== 0) return
          // In test mode the RouteTestOverlay's pick plane (above the conveyors) handles clicks;
          // anything that still reaches the ground is ignored so editing stays suppressed.
          if (testMode) {
            e.stopPropagation()
            return
          }
          if (pickFpMode) {
            // Empty-ground click cancels the pick (like connect-cancel); keep the selection so
            // the workstation panel that armed the pick stays open.
            e.stopPropagation()
            onPickCancel()
            return
          }
          if (connectMode) {
            e.stopPropagation()
            if (connectFrom) onConnectPick(connectFrom) // re-pick source id => cancel
            return
          }
          if (drawPath && selectedId) {
            // Suppress deselect while drawing; draw a section to the clicked floor point.
            e.stopPropagation()
            onDrawSectionAt(selectedId, e.point.x, e.point.z)
            return
          }
          onSelect(null)
        }}
      >
        <planeGeometry args={[200, 200]} />
        <meshBasicMaterial transparent opacity={0} depthWrite={false} />
      </mesh>

      {/* Connections are no longer drawn as lines — physical links are inferred from geometry by
          the routing projection; workstation role-interactions live in the Properties panel. */}

      {items.map((eq) => {
        const isSel = eq.id === selectedId
        // Draw mode applies only to the selected conveyor; it suppresses the move gizmo and routes
        // waypoint clicks to re-anchoring (branch) instead of dragging.
        const drawing = drawPath && isSel
        return (
          <EquipmentMesh
            key={eq.id}
            eq={eq}
            conveyor={isConveyor(eq, lib)}
            cat={category(eq, lib)}
            color={colorFor(eq, lib)}
            selected={isSel}
            // In connect mode highlight the chosen source and route clicks to the connect picker.
            connectMode={connectMode}
            connectSource={eq.id === connectFrom}
            drawing={drawing}
            activeFromIdx={drawing ? activeFromIdx : null}
            onSelect={() => {
              if (testMode || pickFpMode) return // selection suppressed while route-testing/picking
              if (connectMode) onConnectPick(eq.id)
              else onSelect(eq.id)
            }}
            onMove={(x, z, rot) => onMove(eq.id, x, z, rot)}
            onMoveWaypoint={(index, x, z) => onMoveWaypoint(eq.id, index, x, z)}
            onAnchorWaypoint={onAnchorWaypoint}
            onHandleDragChange={onHandleDragChange}
            stubHeightM={stubHeightM}
          />
        )
      })}

      {/* Function-point markers — one per point on an equipment visible on the active level.
          Clicking a marker selects its equipment (or, in connect mode, picks it as an endpoint). */}
      {functionPoints.map((fp) => {
        const eq = byId.get(fp.placedId)
        if (!eq) return null
        // Only render markers for equipment shown on the active level.
        if (!items.some((it) => it.id === eq.id)) return null
        // Pick-in-3D only accepts points that live on a CONVEYOR (the same filter the panel's
        // Select applies); other markers stay inert while picking.
        const pickable = pickFpMode && isConveyor(eq, lib)
        return (
          <FunctionPointMarker
            key={fp.id}
            fp={fp}
            eq={eq}
            // An FP on a stub host (ASRS with a path) rides at the STUB's height, not the rack top.
            topM={!isConveyor(eq, lib) && hasPath(eq) ? stubHeightM ?? STUB_HEIGHT_M : undefined}
            attention={pickable}
            onSelect={() => {
              if (pickFpMode) {
                if (pickable) onPickFunctionPoint(fp.id)
                return
              }
              if (testMode) return // FP dialogs suppressed while route-testing
              if (connectMode) onConnectPick(eq.id)
              else onEditFunctionPoint(fp.id)
            }}
          />
        )
      })}

      {/* Node-link indicators where equipment meets: linked (green) vs near-but-out-of-range
          (amber, "not linked"). Both endpoints must be on the active level to draw. */}
      {meetingPoints.map((m, i) => {
        const eqA = byId.get(m.a.equipId)
        const eqB = byId.get(m.b.equipId)
        if (!eqA || !eqB) return null
        if (!items.some((it) => it.id === eqA.id) || !items.some((it) => it.id === eqB.id)) {
          return null
        }
        const y =
          Math.max(
            linkSurfaceY(eqA, lib, stubHeightM),
            linkSurfaceY(eqB, lib, stubHeightM),
          ) + 0.08
        return <NodeLinkMarker key={`meet-${i}`} meet={m} y={y} />
      })}
    </group>
  )
}

// The Y of an equipment's conveying surface, for link indicators: a conveyor's own top; a stub
// host's (ASRS etc. with a path) stub top; otherwise the body top.
function linkSurfaceY(
  eq: AutomationEquipment,
  lib: Map<string, Equipment>,
  stubHeightM?: number,
): number {
  const h = isConveyor(eq, lib)
    ? eq.heightM
    : hasPath(eq)
      ? stubHeightM ?? STUB_HEIGHT_M
      : eq.heightM
  return h + eq.posYM
}

// A link-state indicator at a meeting point between two pieces of equipment: a small flat ring at
// the midpoint of the closest node pair plus a line between the two nodes (dashed while unlinked).
// Green = the routing projection WILL link these nodes (auto-inferred by proximity, or an explicit
// connection, labelled so); amber = near but out of range ("not linked", with the gap in metres).
// Positions recompute from live equipment state each render, so the indicator follows drags.
function NodeLinkMarker({ meet, y }: { meet: MeetingPoint; y: number }) {
  const linked = meet.state !== 'near'
  const color = linked ? '#8DC63F' : '#f4b860'
  const mx = (meet.a.x + meet.b.x) / 2
  const mz = (meet.a.z + meet.b.z) / 2
  const label =
    meet.state === 'near'
      ? `not linked · ${meet.distM.toFixed(1)} m`
      : meet.state === 'explicit'
        ? 'linked · explicit'
        : 'linked'
  return (
    <group>
      {meet.distM > 0.05 && (
        <Line
          points={[
            [meet.a.x, y, meet.a.z],
            [meet.b.x, y, meet.b.z],
          ]}
          color={color}
          lineWidth={2}
          dashed={!linked}
          dashSize={0.18}
          gapSize={0.12}
          transparent
          opacity={0.85}
        />
      )}
      <mesh position={[mx, y, mz]} rotation={[-Math.PI / 2, 0, 0]}>
        <ringGeometry args={[0.13, 0.22, 24]} />
        <meshBasicMaterial
          color={color}
          transparent
          opacity={0.9}
          side={THREE.DoubleSide}
          depthTest={false}
        />
      </mesh>
      <Html position={[mx, y + 0.45, mz]} center distanceFactor={16} occlude={false}>
        <div className="atopo-fpmarker" style={{ borderColor: color, color }}>
          {label}
        </div>
      </Html>
    </group>
  )
}

// A small marker (cone) with a short type label, placed at the function point's offset along its
// equipment, nudged to the requested side, and sitting at the top of the equipment. It rides along
// with the equipment because its position is recomputed from the live equipment each render.
export function FunctionPointMarker({
  fp,
  eq,
  onSelect,
  showLabels = true,
  topM,
  attention = false,
}: {
  fp: AutomationFunctionPoint
  eq: AutomationEquipment
  onSelect: () => void
  // When false the short type label (SCAN/QRY/DIV…) is hidden — only the cone/diamond glyph shows.
  // Defaults true so the editor is unchanged; the read-only hardware twin toggles it off to declutter.
  showLabels?: boolean
  /** Override the marker's surface height. An FP on an ASRS sits on its IN/OUT STUB (conveyor
   *  height), not on the 10 m rack top that eq.heightM would imply. */
  topM?: number
  /** Subtle "click me" cue while the editor's pick-in-3D mode is armed: slightly larger glyph
   *  with a brighter emissive. Cheap — no extra geometry. */
  attention?: boolean
}) {
  const at = pointAlong(eq, fp.offsetM)
  // Left/right is perpendicular to travel direction on the ground plane. With dir (dx,dz),
  // the right-hand normal is (dz, -dx); left is the negation.
  const half = eq.widthM / 2
  let ox = 0
  let oz = 0
  if (fp.side === 'LEFT') {
    ox = -at.dz * half
    oz = at.dx * half
  } else if (fp.side === 'RIGHT') {
    ox = at.dz * half
    oz = -at.dx * half
  }
  // Sit on the equipment's top surface. Matches the body/path Y (heightM/2 + posYM, centred), whose
  // top is heightM + posYM — the scene draws everything floor-relative, so NO level elevation here
  // (adding it floated the markers ~elev metres above the conveyor).
  const top = (topM ?? eq.heightM) + eq.posYM
  // The point can carry a combination of functions; pick a representative colour and show the
  // combined short labels.
  const fns = fpFunctions(fp.functionType)
  const color = functionColorForSet(fns)
  const short = functionShortForSet(fns) || fp.functionType
  // Ports/merge (IN/OUT/FEED) render as a diamond (octahedron) — distinct from the SCAN/DIVERT cones.
  // If the set includes any port type, use the diamond glyph.
  const isPort = fns.some(isPortType)
  return (
    <group position={[at.x + ox, top, at.z + oz]}>
      <mesh
        position={[0, 0.25, 0]}
        rotation={isPort ? [0, Math.PI / 4, 0] : [0, 0, 0]}
        scale={attention ? 1.3 : 1}
        onPointerDown={(e: ThreeEvent<PointerEvent>) => {
          e.stopPropagation()
          onSelect()
        }}
      >
        {isPort ? <octahedronGeometry args={[0.26, 0]} /> : <coneGeometry args={[0.16, 0.5, 16]} />}
        <meshStandardMaterial color={color} emissive={color} emissiveIntensity={attention ? 0.9 : 0.45} />
      </mesh>
      {showLabels && (
        <Html position={[0, 0.7, 0]} center distanceFactor={16} occlude={false}>
          <div className="atopo-fpmarker" style={{ borderColor: color, color }}>
            {short}
          </div>
        </Html>
      )}
    </group>
  )
}

// A line between two world points with a small cone arrowhead at the "to" end.
function ConnectionLine({
  a,
  b,
  active,
  headSize = 0.18,
}: {
  a: [number, number, number]
  b: [number, number, number]
  active: boolean
  // Arrowhead radius in metres; height is derived from it. Scaled by the caller to the target.
  headSize?: number
}) {
  const color = active ? '#8DC63F' : '#f0a85a'
  const va = new THREE.Vector3(a[0], a[1], a[2])
  const vb = new THREE.Vector3(b[0], b[1], b[2])
  const dir = new THREE.Vector3().subVectors(vb, va)
  const len = dir.length()
  // Orient a +Y cone to point along the link direction (arrowhead at the "to" end).
  const quat = new THREE.Quaternion()
  if (len > 1e-4) {
    quat.setFromUnitVectors(new THREE.Vector3(0, 1, 0), dir.clone().normalize())
  }
  return (
    <group>
      <Line points={[a, b]} color={color} lineWidth={active ? 3 : 2} />
      {len > 1e-4 && (
        <mesh position={[b[0], b[1], b[2]]} quaternion={quat}>
          <coneGeometry args={[headSize, headSize * 2.4, 12]} />
          <meshStandardMaterial color={color} emissive={color} emissiveIntensity={0.3} />
        </mesh>
      )}
    </group>
  )
}

// A two-tone, slightly rounded conveyor body, centred on its group origin and sized to one piece
// (length × height × width). Built from two stacked RoundedBoxes: a translucent cool-tinted lower
// half and a modern light-blue opaque upper half. The selection highlight (emissive) is applied to
// the upper half. Used by both the box-mode conveyor (EquipmentMesh) and each conveyor section
// (ConveyorPath), so they share one look. The group origin is the centre, with y spanning −H/2..H/2.
function ConveyorBody({
  length,
  height,
  width,
  selected,
  highlightColor = '#8DC63F',
  // Optional body tint. Default is the standard light-blue conveyor look (upper #4FC3F7 over a
  // translucent #8fd3ff lower band). When given (e.g. an ASRS's IN/OUT stub), the upper band takes
  // this colour and the lower band a translucent version of it — keeping the rounded 80/20 two-tone
  // shape, just recoloured, so a stub reads as a conveyor in the equipment's own colour, not a blur.
  tint,
}: {
  length: number
  height: number
  width: number
  selected: boolean
  highlightColor?: string
  tint?: string
}) {
  const lowerH = height * 0.8 // bottom 80% transparent
  const upperH = height * 0.2 // top 20% light blue
  const radius = Math.min(0.04, upperH * 0.4, Math.min(length, width) * 0.25)
  // Upper band = the tint (or the default light blue); lower band = a translucent version of the
  // same colour. We keep the lower band a touch more opaque than the default light-blue body so a
  // tinted stub reads as a solid conveyor (in its colour) rather than a faint blur.
  const upperColor = tint ?? '#4FC3F7'
  const lowerColor = tint ?? '#8fd3ff'
  const lowerOpacity = tint ? 0.45 : 0.18
  return (
    <group>
      {/* Lower 80%: translucent cool tint (y from −H/2 to +0.3H). */}
      <RoundedBox
        args={[length, lowerH, width]}
        radius={Math.min(0.04, lowerH * 0.2, Math.min(length, width) * 0.25)}
        smoothness={3}
        position={[0, -height / 2 + lowerH / 2, 0]}
        castShadow
      >
        <meshStandardMaterial
          color={lowerColor}
          transparent
          opacity={lowerOpacity}
          metalness={0.2}
          roughness={0.5}
          depthWrite={false}
        />
      </RoundedBox>
      {/* Top 20%: opaque modern light blue (or the tint). Carries the selection highlight. */}
      <RoundedBox
        args={[length, upperH, width]}
        radius={radius}
        smoothness={3}
        position={[0, height / 2 - upperH / 2, 0]}
        castShadow
      >
        <meshStandardMaterial
          color={upperColor}
          emissive={selected ? highlightColor : '#000000'}
          emissiveIntensity={selected ? 0.5 : 0}
          metalness={0.35}
          roughness={0.4}
        />
      </RoundedBox>
    </group>
  )
}

// A rack-like storage frame, centred on its group origin and sized to [L,H,W]. Modelled as a real
// aisle: storage racks on both sides of the width with an EMPTY central aisle (where a crane/shuttle
// would run) down the length. Each side rack has four corner uprights and one shelf level per ~1 m of
// height. Reused for automated storage (mid blue) and manual storage (burnt orange) via `color`;
// selection highlight = emissive on every member.
function Rack({
  size,
  color,
  selected,
  highlightColor = '#8DC63F',
}: {
  size: [number, number, number]
  color: string
  selected: boolean
  highlightColor?: string
}) {
  const [L, H, W] = size
  const post = Math.min(0.08, L * 0.25, W * 0.2) // square upright cross-section
  const slab = Math.min(0.04, H * 0.1) // shelf slab thickness
  const aisle = W * 0.4 // central empty strip (the crane/shuttle aisle), down the length
  const sideW = Math.max((W - aisle) / 2, post) // depth of each side rack
  const sideZ = aisle / 2 + sideW / 2 // centre of each side rack in Z
  const hx = L / 2 - post / 2
  const zInner = aisle / 2 + post / 2 // upright on the aisle-facing edge
  const zOuter = W / 2 - post / 2 // upright on the outer edge
  // One shelf level per ~1 m of height (at least two: bottom and top).
  const shelfCount = Math.max(2, Math.round(H))
  const shelfYs = Array.from({ length: shelfCount }, (_, i) => -H / 2 + slab / 2 + (i / (shelfCount - 1)) * (H - slab))
  const sides = [1, -1] // +Z and -Z rack faces, with the aisle between them
  const mat = (
    <meshStandardMaterial
      color={color}
      emissive={selected ? highlightColor : '#000000'}
      emissiveIntensity={selected ? 0.5 : 0}
      metalness={0.3}
      roughness={0.6}
      transparent
      opacity={0.9}
    />
  )
  return (
    <group>
      {sides.flatMap((s) =>
        [zInner, zOuter].flatMap((z, zi) =>
          [hx, -hx].map((x, xi) => (
            <mesh key={`post-${s}-${zi}-${xi}`} position={[x, 0, s * z]} castShadow>
              <boxGeometry args={[post, H, post]} />
              {mat}
            </mesh>
          )),
        ),
      )}
      {sides.flatMap((s) =>
        shelfYs.map((y, i) => (
          <mesh key={`shelf-${s}-${i}`} position={[0, y, s * sideZ]} castShadow>
            <boxGeometry args={[L, slab, sideW]} />
            {mat}
          </mesh>
        )),
      )}
    </group>
  )
}

interface EquipmentMeshProps {
  eq: AutomationEquipment
  conveyor: boolean
  // Visual category — picks which body is drawn (conveyor two-tone / rack / sorter box).
  cat: EquipmentCategory
  color: string
  selected: boolean
  // Connect mode on → clicks pick connection endpoints; the chosen source is highlighted.
  connectMode: boolean
  connectSource: boolean
  // Section-draw mode is active on this (selected) conveyor: suppress the move gizmo and route
  // waypoint clicks to re-anchoring. activeFromIdx is the current anchor (highlighted), or null.
  drawing: boolean
  activeFromIdx: number | null
  onSelect: () => void
  onMove: (x: number, z: number, rotationDeg: number) => void
  onMoveWaypoint: (index: number, x: number, z: number) => void
  onAnchorWaypoint: (index: number) => void
  onHandleDragChange: (active: boolean) => void
  // When false the equipment's code label is hidden (declutter). Defaults true (editor unchanged).
  showLabels?: boolean
  /** Scene conveyor height for ASRS IN/OUT stub bodies (flush with the conveyors they feed). */
  stubHeightM?: number
  /** Overrides the conveyor BODY colour (the twin's live state tint). Editor leaves it unset. */
  bodyTint?: string
}

export function EquipmentMesh({
  eq,
  conveyor,
  cat,
  color,
  selected,
  connectMode,
  connectSource,
  drawing,
  activeFromIdx,
  onSelect,
  onMove,
  onMoveWaypoint,
  onAnchorWaypoint,
  onHandleDragChange,
  showLabels = true,
  stubHeightM,
  bodyTint,
}: EquipmentMeshProps) {
  // Highlight either the editor selection or (in connect mode) the chosen source.
  const highlight = connectMode ? connectSource : selected
  const highlightColor = connectMode ? '#f0a85a' : '#8DC63F'

  // Conveyor with a usable section graph → render directed sections + arrows + junction/decision
  // markers + (when editable) draggable handles. While connecting we suppress handles so clicks
  // register as connect picks.
  if (conveyor && hasPath(eq)) {
    return (
      <ConveyorPath
        eq={eq}
        cat={cat}
        color={color}
        bodyTint={bodyTint}
        selected={highlight}
        editable={selected && !connectMode}
        drawing={drawing}
        activeFromIdx={activeFromIdx}
        onSelect={onSelect}
        onMoveWaypoint={onMoveWaypoint}
        onAnchorWaypoint={onAnchorWaypoint}
        onHandleDragChange={onHandleDragChange}
        showLabels={showLabels}
        stubHeightM={stubHeightM}
      />
    )
  }

  // Captured at drag start so cumulative gizmo deltas apply to a fixed base, not live state.
  const dragBase = useRef<{ x: number; z: number; rotDeg: number } | null>(null)
  const y = eq.heightM / 2 + eq.posYM
  // The per-category body, all origin-centred (y spans −H/2..H/2) like the old boxGeometry:
  //   conveyor       — two-tone rounded body.
  //   asrs           — mid-blue rack frame.
  //   manual-storage / other — burnt-orange rack frame.
  //   sorter         — lightly rounded amber box.
  let body: JSX.Element
  if (cat === 'conveyor') {
    body = (
      <ConveyorBody
        length={eq.lengthM}
        height={eq.heightM}
        width={eq.widthM}
        selected={highlight}
        highlightColor={highlightColor}
        tint={bodyTint}
      />
    )
  } else if (cat === 'asrs') {
    body = (
      <Rack
        size={[eq.lengthM, eq.heightM, eq.widthM]}
        color="#1E88E5"
        selected={highlight}
        highlightColor={highlightColor}
      />
    )
  } else if (cat === 'manual-storage' || cat === 'other') {
    body = (
      <Rack
        size={[eq.lengthM, eq.heightM, eq.widthM]}
        color="#C75B12"
        selected={highlight}
        highlightColor={highlightColor}
      />
    )
  } else if (cat === 'workstation') {
    // GTP workstation — a rounded green box (a person-facing station, not a rack).
    body = (
      <RoundedBox
        args={[eq.lengthM, eq.heightM, eq.widthM]}
        radius={Math.min(0.06, Math.min(eq.lengthM, eq.heightM, eq.widthM) * 0.2)}
        smoothness={3}
        castShadow
      >
        <meshStandardMaterial
          color="#3ea66a"
          emissive={highlight ? highlightColor : '#000000'}
          emissiveIntensity={highlight ? 0.5 : 0}
          metalness={0.2}
          roughness={0.6}
        />
      </RoundedBox>
    )
  } else {
    // sorter — a lightly rounded amber box.
    body = (
      <RoundedBox
        args={[eq.lengthM, eq.heightM, eq.widthM]}
        radius={Math.min(0.04, Math.min(eq.lengthM, eq.heightM, eq.widthM) * 0.2)}
        smoothness={3}
        castShadow
      >
        <meshStandardMaterial
          color="#E0A33A"
          emissive={highlight ? highlightColor : '#000000'}
          emissiveIntensity={highlight ? 0.5 : 0}
          metalness={0.2}
          roughness={0.6}
        />
      </RoundedBox>
    )
  }

  const box = (
    <group
      onPointerDown={(e: ThreeEvent<PointerEvent>) => {
        e.stopPropagation()
        onSelect()
      }}
    >
      {body}
      {highlight && (
        // Simple wireframe outline on the highlighted body.
        <lineSegments>
          <edgesGeometry args={[new THREE.BoxGeometry(eq.lengthM * 1.02, eq.heightM * 1.02, eq.widthM * 1.02)]} />
          <lineBasicMaterial color={highlightColor} />
        </lineSegments>
      )}
      {showLabels && (
        <Html position={[0, eq.heightM / 2 + 0.4, 0]} center distanceFactor={18} occlude={false}>
          <div className="atopo-label">{eq.code}</div>
        </Html>
      )}
    </group>
  )

  // An ASRS that owns IN/OUT conveyor stubs renders them as a conveyor path in world space, ALONGSIDE
  // its rack body (the stubs look like conveyors coming out of the rack). They live in world XZ (not
  // the rack's local frame), so they sit OUTSIDE the rotated/gizmo'd box group. Extendable with the
  // path tools (Draw sections / waypoint drag) when this ASRS is selected.
  const asrsStubs =
    cat === 'asrs' && hasPath(eq) ? (
      <ConveyorPath
        eq={eq}
        // Pass the ASRS category (not 'conveyor') so the stub bodies render tinted in the ASRS
        // colour instead of the default light-blue conveyor look.
        cat={cat}
        color={color}
        selected={highlight}
        editable={selected && !connectMode}
        drawing={drawing}
        activeFromIdx={activeFromIdx}
        onSelect={onSelect}
        onMoveWaypoint={onMoveWaypoint}
        onAnchorWaypoint={onAnchorWaypoint}
        onHandleDragChange={onHandleDragChange}
        showLabels={showLabels}
        stubHeightM={stubHeightM}
      />
    ) : null

  // When selected (and not connecting / drawing), wrap in PivotControls for drag-move + rotate.
  if (selected && !connectMode && !drawing) {
    return (
      <group>
        {asrsStubs}
      <PivotControls
        anchor={[0, 0, 0]}
        // Re-mount per equipment so the gizmo resets cleanly when selection changes.
        key={eq.id}
        // `fixed` keeps the gizmo a constant on-screen size (scale = pixels) instead of scaling with
        // the equipment — an 80 m ASRS otherwise produced a giant arrow/axis gizmo that filled the view.
        fixed
        scale={90}
        lineWidth={2.5}
        depthTest={false}
        disableScaling
        // autoTransform off: our React state is the source of truth for the group's transform,
        // so the gizmo's local matrix `l` is cumulative-from-drag-start and we add it to the base
        // captured at drag start — no runaway accumulation as state updates each frame.
        autoTransform={false}
        activeAxes={[true, false, true]}
        onDragStart={() => {
          dragBase.current = { x: eq.posXM, z: eq.posZM, rotDeg: eq.rotationDeg }
          onHandleDragChange(true) // disable OrbitControls so the gizmo (not the camera) gets the drag
        }}
        onDragEnd={() => onHandleDragChange(false)}
        onDrag={(l) => {
          const base = dragBase.current
          if (!base) return
          const pos = new THREE.Vector3()
          const quat = new THREE.Quaternion()
          const scl = new THREE.Vector3()
          l.decompose(pos, quat, scl)
          const euler = new THREE.Euler().setFromQuaternion(quat, 'YXZ')
          const rotDeg = base.rotDeg + euler.y / DEG
          onMove(
            +(base.x + pos.x).toFixed(3),
            +(base.z + pos.z).toFixed(3),
            +rotDeg.toFixed(2),
          )
        }}
      >
        <group
          position={[eq.posXM, y, eq.posZM]}
          rotation={[eq.tiltDeg * DEG, eq.rotationDeg * DEG, 0]}
          rotation-order="YXZ"
        >
          {box}
        </group>
      </PivotControls>
      </group>
    )
  }

  return (
    <group>
      {asrsStubs}
      <group
        position={[eq.posXM, y, eq.posZM]}
        rotation={[eq.tiltDeg * DEG, eq.rotationDeg * DEG, 0]}
        rotation-order="YXZ"
      >
        {box}
      </group>
    </group>
  )
}

// =========================================================================
// Conveyor polyline (corners / turns / loops)
// =========================================================================

interface ConveyorPathProps {
  eq: AutomationEquipment
  // Visual category (currently always 'conveyor' for path-mode items, but threaded for parity).
  cat: EquipmentCategory
  color: string
  selected: boolean
  // When true, draggable waypoint handles are shown (suppressed while in connect mode).
  editable: boolean
  // Section-draw mode active on this conveyor: waypoint clicks re-anchor (branch) instead of drag.
  drawing: boolean
  activeFromIdx: number | null
  onSelect: () => void
  onMoveWaypoint: (index: number, x: number, z: number) => void
  onAnchorWaypoint: (index: number) => void
  onHandleDragChange: (active: boolean) => void
  showLabels?: boolean
  /** Height for NON-conveyor stub bodies (an ASRS's IN/OUT pieces) — the scene's conveyor height. */
  stubHeightM?: number
  /** Overrides the conveyor BODY colour (the twin's live state tint). Editor leaves it unset. */
  bodyTint?: string
}

// Renders a conveyor as a DIRECTED section graph: one box per section `[i,j]` (length = |path[i]
// path[j]|, oriented from→to) with a green travel-direction arrow at its midpoint, plus markers at
// each junction point (red for decision/divert points — the `from` of 2+ sections). When no
// explicit sections exist it falls back to implicit sequential sections (i → i+1). When editable,
// draggable sphere handles move each waypoint (all sections referencing it follow).
function ConveyorPath({
  eq,
  cat,
  color,
  selected,
  editable,
  drawing,
  activeFromIdx,
  onSelect,
  onMoveWaypoint,
  onAnchorWaypoint,
  onHandleDragChange,
  showLabels = true,
  stubHeightM,
  bodyTint: bodyTintProp,
}: ConveyorPathProps) {
  const path = (eq.path ?? []) as number[][]
  const sections = effectiveSections(eq)
  const decisions = decisionPoints(sections)
  const junctions = junctionPoints(sections)
  // For a NON-conveyor path host (an ASRS / manual-storage stub), tint the conveyor body in the
  // equipment's own category colour so the stub reads as that equipment's IN/OUT piece — not the
  // default light-blue conveyor (which on an ASRS looks like a vague blur). A real conveyor keeps
  // the default look (tint = undefined) unless the caller overrides it (the twin's live state tint).
  const bodyTint = bodyTintProp ?? (cat === 'conveyor' ? undefined : categoryBodyColor(cat) ?? undefined)
  // A real conveyor uses its own envelope; a stub host (ASRS / manual-storage) draws its IN/OUT
  // stubs at a CONVEYOR width AND height so they read as low, flat conveyor pieces — not the full
  // rack footprint width or its (often ~10 m) rack height (which rendered the stub as a tall wall).
  const wM = cat === 'conveyor' ? eq.widthM : STUB_WIDTH_M
  // Stub bodies adopt the scene's conveyor height so an ASRS IN/OUT piece sits FLUSH with the
  // conveyor it feeds (this is the body-placement copy of the calc — the first fix only caught the
  // sibling site, leaving the rendered stubs at the old 0.5 m constant).
  const hM = cat === 'conveyor' ? eq.heightM : stubHeightM ?? STUB_HEIGHT_M
  const y = hM / 2 + eq.posYM

  // One box + arrow per directed section.
  const segs = sections
    .map(([i, j]) => {
      const a = path[i]
      const b = path[j]
      if (!a || !b) return null
      const dx = b[0] - a[0]
      const dz = b[1] - a[1]
      const len = Math.hypot(dx, dz)
      if (len < 1e-4) return null
      return {
        i,
        j,
        ax: a[0],
        az: a[1],
        bx: b[0],
        bz: b[1],
        mx: (a[0] + b[0]) / 2,
        mz: (a[1] + b[1]) / 2,
        len,
        // z maps to world Z: yaw about Y rotates the box's X length into the segment direction.
        yaw: Math.atan2(dz, dx),
        ux: dx / len,
        uz: dz / len,
      }
    })
    .filter((s): s is NonNullable<typeof s> => s !== null)

  const top = y + hM / 2
  const first = path[0]

  return (
    <group>
      {segs.map((s, k) => (
        <group key={`seg-${k}`}>
          <group
            position={[s.mx, y, s.mz]}
            rotation={[0, -s.yaw, 0]}
            onPointerDown={(e: ThreeEvent<PointerEvent>) => {
              e.stopPropagation()
              onSelect()
            }}
          >
            {/* Two-tone rounded conveyor body for this section (shared with box-mode conveyors). */}
            <ConveyorBody
              length={s.len}
              height={hM}
              width={wM}
              selected={selected}
              tint={bodyTint}
            />
          </group>
          {/* Small, subtle travel-direction chevrons repeated every ~1 m along the section
              (capped for very long sections), each pointing from → to. */}
          {(() => {
            const count = Math.min(30, Math.max(1, Math.round(s.len)))
            const size = Math.max(0.12, Math.min(0.28, wM * 0.28)) // small
            return Array.from({ length: count }, (_, c) => {
              const dM = (c + 0.5) * (s.len / count)
              const cx = s.ax + s.ux * dM
              const cz = s.az + s.uz * dM
              return (
                <DirectionArrow
                  key={`arr-${k}-${c}`}
                  mx={cx}
                  mz={cz}
                  y={top + 0.06}
                  ux={s.ux}
                  uz={s.uz}
                  size={size}
                />
              )
            })
          })()}
        </group>
      ))}

      {/* Corner / joint fillers so turns look connected (not clunky): a square body block at each
          interior point shared by 2+ sections fills the wedge gap a turn would otherwise leave. */}
      {path.map((p, i) => {
        const degree = sections.reduce((n, s) => n + (s[0] === i || s[1] === i ? 1 : 0), 0)
        if (degree < 2) return null
        return (
          <group
            key={`joint-${i}`}
            position={[p[0], y, p[1]]}
            onPointerDown={(e: ThreeEvent<PointerEvent>) => {
              e.stopPropagation()
              onSelect()
            }}
          >
            <ConveyorBody length={wM} height={hM} width={wM} selected={selected} tint={bodyTint} />
          </group>
        )
      })}

      {/* Junction / decision markers — one per path point used by a section. Hidden together with
          the labels (the twin's "Labels" toggle declutters both the text and these marker "hats"). */}
      {showLabels && path.map((p, i) => {
        if (!junctions.has(i)) return null
        const isDecision = decisions.has(i)
        const isAnchor = drawing && activeFromIdx === i
        return (
          <JunctionMarker
            key={`jn-${i}`}
            x={p[0]}
            z={p[1]}
            y={top + 0.06}
            decision={isDecision}
            anchor={isAnchor}
            radius={Math.max(0.14, wM * 0.28)}
          />
        )
      })}

      {/* Code label near the first waypoint. */}
      {first && showLabels && (
        <Html
          position={[first[0], top + 0.4, first[1]]}
          center
          distanceFactor={18}
          occlude={false}
        >
          <div className="atopo-label">{eq.code}</div>
        </Html>
      )}

      {/* Waypoint handles. When editable but not drawing: drag to move the junction. When drawing:
          a click re-anchors (branch) without moving. */}
      {(editable || drawing) &&
        path.map((p, i) => (
          <WaypointHandle
            key={`wp-${i}`}
            x={p[0]}
            z={p[1]}
            y={y}
            radius={Math.max(0.2, wM * 0.35)}
            drawing={drawing}
            anchor={drawing && activeFromIdx === i}
            onDrag={(x, z) => onMoveWaypoint(i, x, z)}
            onAnchor={() => onAnchorWaypoint(i)}
            onDragChange={onHandleDragChange}
          />
        ))}
    </group>
  )
}

// A small, subtle flat chevron (two short lines meeting at a point) on the ground plane, marking
// travel direction at a point along a section. (ux,uz) is the unit travel direction in world XZ.
// Drawn low-contrast / semi-transparent so the repeated per-metre chevrons read as a background aid.
function DirectionArrow({
  mx,
  mz,
  y,
  ux,
  uz,
  size,
}: {
  mx: number
  mz: number
  y: number
  ux: number
  uz: number
  size: number
}) {
  // Tip ahead of the point; two barbs swept back from it.
  const tip: [number, number, number] = [mx + ux * size, y, mz + uz * size]
  // Right-hand normal on the ground plane to (ux,uz) is (uz,-ux).
  const nx = uz
  const nz = -ux
  const back = size * 0.9
  const spread = size * 0.7
  const left: [number, number, number] = [
    mx - ux * back + nx * spread,
    y,
    mz - uz * back + nz * spread,
  ]
  const right: [number, number, number] = [
    mx - ux * back - nx * spread,
    y,
    mz - uz * back - nz * spread,
  ]
  return (
    <Line points={[left, tip, right]} color="#8DC63F" lineWidth={1.5} transparent opacity={0.3} />
  )
}

// A flat marker disc at a junction point. Decision/divert points (the `from` of 2+ sections) are
// red and slightly larger; plain junctions are a subtle neutral. The draw anchor pulses lime.
function JunctionMarker({
  x,
  z,
  y,
  decision,
  anchor,
  radius,
}: {
  x: number
  z: number
  y: number
  decision: boolean
  anchor: boolean
  radius: number
}) {
  const color = anchor ? '#8DC63F' : decision ? '#e0563f' : '#9fb2a8'
  const r = decision ? radius * 1.15 : radius * 0.85
  return (
    <mesh position={[x, y, z]} rotation={[-Math.PI / 2, 0, 0]}>
      <ringGeometry args={[r * 0.55, r, 24]} />
      <meshBasicMaterial color={color} transparent opacity={anchor ? 0.95 : decision ? 0.9 : 0.6} side={THREE.DoubleSide} depthTest={false} />
    </mesh>
  )
}

// A draggable sphere at a single waypoint. Dragging raycasts against a horizontal plane at the
// waypoint's y and updates the point live. Disables OrbitControls for the duration of the drag.
function WaypointHandle({
  x,
  z,
  y,
  radius,
  drawing,
  anchor,
  onDrag,
  onAnchor,
  onDragChange,
}: {
  x: number
  z: number
  y: number
  radius: number
  // In drawing mode the handle is a click target: clicking re-anchors (branch) instead of dragging.
  drawing: boolean
  anchor: boolean
  onDrag: (x: number, z: number) => void
  onAnchor: () => void
  onDragChange: (active: boolean) => void
}) {
  const dragging = useRef(false)
  // Horizontal plane at the handle's height; we intersect the pointer ray with it each move.
  const plane = useRef(new THREE.Plane(new THREE.Vector3(0, 1, 0), -y))
  const hit = useRef(new THREE.Vector3())

  const color = anchor ? '#f4b860' : '#8DC63F'
  const emissive = anchor ? '#b07a10' : '#3a6'

  return (
    <mesh
      position={[x, y, z]}
      onPointerDown={(e: ThreeEvent<PointerEvent>) => {
        if (e.button !== 0) return
        e.stopPropagation()
        // In drawing mode a click on a handle re-anchors to this point (start a branch); it must NOT
        // also fall through to the ground-plane handler (which would add a section/point).
        if (drawing) {
          onAnchor()
          return
        }
        dragging.current = true
        onDragChange(true)
        ;(e.target as Element).setPointerCapture?.(e.pointerId)
      }}
      onPointerMove={(e: ThreeEvent<PointerEvent>) => {
        if (!dragging.current) return
        e.stopPropagation()
        // Keep the plane in sync with the current y, then intersect the pointer ray.
        plane.current.set(new THREE.Vector3(0, 1, 0), -y)
        const p = e.ray.intersectPlane(plane.current, hit.current)
        if (p) onDrag(p.x, p.z)
      }}
      onPointerUp={(e: ThreeEvent<PointerEvent>) => {
        if (!dragging.current) return
        e.stopPropagation()
        dragging.current = false
        onDragChange(false)
        ;(e.target as Element).releasePointerCapture?.(e.pointerId)
      }}
    >
      <sphereGeometry args={[radius, 16, 16]} />
      <meshStandardMaterial color={color} emissive={emissive} emissiveIntensity={anchor ? 0.6 : 0.4} depthTest={false} />
    </mesh>
  )
}

// =========================================================================
// Scoped styles
// =========================================================================

function Styles() {
  return (
    <style>{`
      .atopo { display: flex; flex-direction: column; gap: .6rem; }
      .atopo-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 1rem; flex-wrap: wrap; }
      .atopo-levels { display: flex; align-items: center; gap: .4rem; flex-wrap: wrap; }
      .atopo-actions { display: flex; align-items: center; gap: .5rem; }
      .atopo-dirty { font-size: .75rem; color: #f4b860; }
      .atopo-viewtoggle { display: inline-flex; border: 1px solid var(--glass-border); border-radius: 999px; overflow: hidden; }
      .atopo-viewbtn {
        padding: .3rem .7rem; background: none; border: none; cursor: pointer;
        color: var(--text-dim); font-family: var(--font-body); font-size: .78rem;
        border-right: 1px solid var(--glass-border);
      }
      .atopo-viewbtn:last-child { border-right: none; }
      .atopo-viewbtn:hover { color: var(--text); }
      .atopo-viewbtn.is-active { background: rgba(141, 198, 63, .15); color: var(--herbal-lime); }
      .atopo-leveltab {
        padding: .35rem .8rem; border-radius: 999px; cursor: pointer; font-size: .8125rem;
        background: var(--glass-bg); color: var(--text); border: 1px solid var(--glass-border);
        font-family: var(--font-body); transition: all .15s;
      }
      .atopo-leveltab:hover { border-color: var(--glass-border-bright); }
      .atopo-leveltab.is-active {
        background: rgba(141, 198, 63, .15); color: var(--herbal-lime); border-color: var(--glass-border-bright);
      }
      .atopo-info { font-size: .8rem; color: var(--text-dim); }
      .atopo-fold {
        display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px;
        border-radius: 8px; cursor: pointer; line-height: 1; font-size: .8rem;
        background: var(--glass-bg); color: var(--text-dim); border: 1px solid var(--glass-border);
      }
      .atopo-fold:hover { color: var(--text); border-color: var(--glass-border-bright); }
      /* When the page chrome is folded, give the canvas (and side panels) the reclaimed height. */
      .atopo.is-collapsed .atopo-canvas { height: 90vh; }
      .atopo.is-collapsed .atopo-panel { max-height: 90vh; }
      .atopo-levelmeta { display: flex; gap: 1rem; padding: .6rem .8rem; flex-wrap: wrap; }
      .atopo-inline { display: flex; flex-direction: column; gap: .25rem; min-width: 200px; flex: 1; margin: 0; }
      .atopo-inline > span { font-size: .8125rem; color: var(--text-dim); }
      .atopo-body { display: grid; grid-template-columns: 230px 1fr 250px; gap: .6rem; align-items: stretch; }
      .atopo-panel { padding: .8rem; overflow-y: auto; max-height: 82vh; }
      .atopo-panel h3 { font-size: .95rem; margin: 0 0 .6rem; }
      .atopo-canvas { padding: 0; height: 82vh; overflow: hidden; position: relative; }
      .atopo-canvas canvas { display: block; }
      .atopo-hint {
        position: absolute; left: 12px; bottom: 12px; z-index: 2; pointer-events: none;
        padding: .3rem .6rem; border-radius: 8px; font-size: .72rem; letter-spacing: .02em;
        color: rgba(214, 228, 220, .85); background: rgba(8, 30, 22, .6);
        border: 1px solid var(--glass-border);
      }
      /* "Test route" toolbar toggle while the mode is on. */
      .atopo-testbtn-on {
        color: var(--herbal-lime); border-color: rgba(141, 198, 63, .5);
        background: rgba(141, 198, 63, .12);
      }
      /* Route-test verdict chip in the hint bar: green for a found path, red for no path. */
      .atopo-route-chip {
        display: inline-block; margin-left: .5rem; padding: .1rem .5rem; border-radius: 999px;
        font-family: var(--font-mono); font-size: .7rem; letter-spacing: .02em;
      }
      .atopo-route-chip.is-ok {
        color: var(--herbal-lime); border: 1px solid rgba(141, 198, 63, .5);
        background: rgba(141, 198, 63, .12);
      }
      .atopo-route-chip.is-bad {
        color: #ff8a7e; border: 1px solid rgba(255, 122, 110, .5);
        background: rgba(255, 122, 110, .12);
      }
      /* The full label of a chosen conveyor point in the workstation panel — the Select trigger
         truncates long labels, so we repeat the whole thing here and let it wrap. */
      .atopo-fp-picked {
        padding: .3rem .5rem; border-radius: 6px; font-family: var(--font-mono); font-size: .72rem;
        line-height: 1.35; color: var(--herbal-lime); background: rgba(141, 198, 63, .1);
        border: 1px solid rgba(141, 198, 63, .35);
        white-space: normal; overflow-wrap: anywhere;
      }
      .atopo-empty {
        height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center;
        gap: .8rem; color: var(--text-dim); text-align: center; padding: 1rem;
      }
      .atopo-muted { color: var(--text-dim); font-size: .8125rem; }
      .atopo-links-scope { margin: .25rem 0 .5rem; }
      .atopo-linklike { background: none; border: none; padding: 0; color: var(--herbal-lime); cursor: pointer; font-size: inherit; text-decoration: underline; }
      .atopo-linklike:hover { opacity: .85; }
      .atopo-libgroup { margin-bottom: .8rem; }
      .atopo-libgroup-head {
        font-family: var(--font-mono); font-size: .7rem; text-transform: uppercase; letter-spacing: .1em;
        color: var(--herbal-lime); margin-bottom: .3rem;
      }
      .atopo-librow {
        display: flex; align-items: center; justify-content: space-between; gap: .5rem;
        padding: .35rem .5rem; border-radius: 8px; font-size: .8125rem;
        border: 1px solid var(--glass-border); margin-bottom: .3rem;
      }
      .atopo-librow-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: flex; align-items: center; gap: .35rem; }
      .atopo-unplaced { margin: 0 0 .6rem; font-size: .8rem; }
      .atopo-badge {
        font-family: var(--font-mono); font-size: .62rem; text-transform: uppercase; letter-spacing: .05em;
        padding: 1px 6px; border-radius: 999px; border: 1px solid var(--glass-border); white-space: nowrap;
      }
      .atopo-badge.is-placed { color: var(--herbal-lime); border-color: rgba(141, 198, 63, .4); background: rgba(141, 198, 63, .12); }
      .atopo-badge.is-unplaced { color: var(--text-dim); }
      .atopo-conns { margin-top: .8rem; border-top: 1px solid var(--glass-border); padding-top: .6rem; }
      .atopo-conns-head {
        width: 100%; display: flex; align-items: center; justify-content: space-between;
        background: none; border: none; cursor: pointer; padding: 0; color: var(--text);
        font-family: var(--font-body); font-size: .9rem; font-weight: 600;
      }
      .atopo-conns-chevron { color: var(--text-dim); }
      .atopo-conns-empty { margin: .5rem 0 0; }
      .atopo-conns-list { list-style: none; margin: .5rem 0 0; padding: 0; display: flex; flex-direction: column; gap: .3rem; }
      .atopo-conns-row {
        display: flex; align-items: center; justify-content: space-between; gap: .5rem;
        padding: .3rem .45rem; border-radius: 8px; border: 1px solid var(--glass-border);
      }
      .atopo-conns-row.is-active { border-color: var(--glass-border-bright); background: rgba(141, 198, 63, .1); }
      .atopo-conns-label {
        flex: 1; text-align: left; background: none; border: none; cursor: pointer; padding: 0;
        color: var(--text); font-family: var(--font-mono); font-size: .8rem;
        overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      }
      .atopo-fields { display: flex; flex-direction: column; gap: .6rem; }
      .atopo-field { display: flex; flex-direction: column; gap: .25rem; margin: 0; }
      .atopo-field > span { font-size: .8125rem; color: var(--text-dim); }
      .atopo-grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: .5rem; }
      .atopo-delete { margin-top: .4rem; }
      .atopo-pathtools {
        display: flex; flex-direction: column; gap: .5rem; margin-top: .4rem;
        padding: .6rem .7rem; border-radius: 10px;
        border: 1px solid var(--glass-border); background: rgba(141, 198, 63, .05);
      }
      .atopo-pathtools-head {
        font-family: var(--font-mono); font-size: .7rem; text-transform: uppercase; letter-spacing: .1em;
        color: var(--herbal-lime);
      }
      .atopo-pathcount { font-size: .8rem; color: var(--text-dim); }
      .atopo-pathbtn { width: 100%; }
      .atopo-pathcheck { margin: .1rem 0; }
      .atopo-pathrow { display: grid; grid-template-columns: 1fr 1fr; gap: .5rem; }
      .atopo-drawhint {
        font-size: .72rem; line-height: 1.35; color: var(--text-dim);
        padding: .4rem .5rem; border-radius: 8px;
        border: 1px dashed rgba(141, 198, 63, .4); background: rgba(141, 198, 63, .06);
      }
      .atopo-linkbtn {
        display: inline; margin-left: .4rem; padding: 0; background: none; border: none;
        color: var(--herbal-lime); cursor: pointer; font-size: .72rem; text-decoration: underline;
        font-family: var(--font-body);
      }
      .atopo-label {
        font-family: var(--font-mono); font-size: 11px; padding: 1px 6px; border-radius: 6px;
        background: rgba(8, 30, 22, .85); color: var(--text); border: 1px solid var(--glass-border-bright);
        white-space: nowrap; pointer-events: none; transform: translateY(-2px);
      }
      .atopo-fpmarker {
        font-family: var(--font-mono); font-size: 9px; line-height: 1; letter-spacing: .04em;
        padding: 1px 5px; border-radius: 999px; white-space: nowrap; pointer-events: none;
        background: rgba(8, 30, 22, .85); border: 1px solid currentColor; transform: translateY(-2px);
      }
      .atopo-fps {
        display: flex; flex-direction: column; gap: .5rem; margin-top: .4rem;
        padding: .6rem .7rem; border-radius: 10px;
        border: 1px solid var(--glass-border); background: rgba(94, 200, 224, .05);
      }
      .atopo-fps-head {
        font-family: var(--font-mono); font-size: .7rem; text-transform: uppercase; letter-spacing: .1em;
        color: #5ec8e0;
      }
      .atopo-fps-empty { margin: 0; }
      .atopo-fps-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: .3rem; }
      .atopo-fps-row {
        display: flex; align-items: center; justify-content: space-between; gap: .5rem;
        padding: .3rem .45rem; border-radius: 8px; border: 1px solid var(--glass-border);
      }
      .atopo-fps-label {
        flex: 1; font-size: .78rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      }
      .atopo-fps-type { font-family: var(--font-mono); font-size: .72rem; font-weight: 600; }
      .atopo-fps-form { display: flex; flex-direction: column; gap: .5rem; margin-top: .2rem; }
      /* Node-links ("Connections") block in the properties panel. */
      .atopo-links {
        display: flex; flex-direction: column; gap: .5rem; margin-top: .4rem;
        padding: .6rem .7rem; border-radius: 10px;
        border: 1px solid var(--glass-border); background: rgba(141, 198, 63, .05);
      }
      .atopo-links-head {
        font-family: var(--font-mono); font-size: .7rem; text-transform: uppercase; letter-spacing: .1em;
        color: var(--herbal-lime);
      }
      .atopo-links-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: .45rem; }
      .atopo-links-row {
        display: flex; flex-direction: column; gap: .35rem;
        padding: .4rem .5rem; border-radius: 8px; border: 1px solid var(--glass-border);
      }
      .atopo-links-node { font-family: var(--font-mono); font-size: .78rem; font-weight: 600; }
      .atopo-links-state {
        display: flex; align-items: center; justify-content: space-between; gap: .4rem;
        font-size: .74rem; line-height: 1.35;
      }
      .atopo-links-state.is-linked { color: var(--herbal-lime); }
      .atopo-links-state.is-unlinked { color: #f4b860; }
      .atopo-links-note {
        font-size: .72rem; line-height: 1.35; color: var(--text-dim);
        padding: .4rem .5rem; border-radius: 8px;
        border: 1px dashed rgba(141, 198, 63, .4); background: rgba(141, 198, 63, .06);
      }
      .atopo-modal-backdrop {
        position: fixed; inset: 0; z-index: 50; display: flex; align-items: center; justify-content: center;
        background: rgba(4, 14, 10, .55); backdrop-filter: blur(2px); padding: 1rem;
      }
      .atopo-modal {
        width: 320px; max-width: 100%; max-height: 90vh; overflow-y: auto;
        display: flex; flex-direction: column; gap: .7rem; padding: 1rem 1.1rem; border-radius: 14px;
      }
      .atopo-modal-head { display: flex; align-items: center; justify-content: space-between; }
      .atopo-modal-head h3 { margin: 0; font-size: 1rem; font-family: var(--font-mono); letter-spacing: .03em; }
      .atopo-modal-x {
        background: none; border: none; cursor: pointer; color: var(--text-dim); font-size: 1.3rem;
        line-height: 1; padding: 0 .2rem;
      }
      .atopo-modal-x:hover { color: var(--text); }
      .atopo-modal-actions { display: flex; justify-content: space-between; gap: .5rem; margin-top: .3rem; }
      .atopo-conn-endpoints {
        display: flex; align-items: center; flex-wrap: wrap; gap: .4rem;
        padding: .5rem .6rem; border-radius: 10px;
        border: 1px solid var(--glass-border); background: rgba(141, 198, 63, .06);
      }
      .atopo-conn-code { font-family: var(--font-mono); font-size: .82rem; font-weight: 600; word-break: break-all; }
      .atopo-conn-arrow { color: var(--herbal-lime); }
      .atopo-conn-meta { display: flex; flex-direction: column; gap: .3rem; margin: 0; }
      .atopo-conn-meta > div { display: flex; justify-content: space-between; gap: .6rem; align-items: baseline; }
      .atopo-conn-meta dt { margin: 0; font-size: .74rem; color: var(--text-dim); }
      .atopo-conn-meta dd { margin: 0; font-size: .78rem; text-align: right; }
      .atopo-conn-savehint { font-size: .72rem; margin: 0; }
      .atopo-areas {
        display: flex; flex-direction: column; gap: .5rem; margin-top: .4rem;
        padding: .6rem .7rem; border-radius: 10px;
        border: 1px solid var(--glass-border); background: rgba(122, 108, 192, .06);
      }
      .atopo-areas-head {
        font-family: var(--font-mono); font-size: .7rem; text-transform: uppercase; letter-spacing: .1em;
        color: #9d8fe0;
      }
      .atopo-areas-empty, .atopo-areas-error { margin: 0; }
      .atopo-areas-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: .25rem; }
      .atopo-areas-row {
        display: flex; align-items: center; justify-content: space-between; gap: .5rem;
        padding: .25rem .4rem; border-radius: 8px; border: 1px solid var(--glass-border);
      }
      .atopo-areas-check { margin: 0; font-size: .8rem; display: flex; align-items: center; gap: .4rem; }
      .atopo-areas-check.is-disabled { opacity: .55; }
      .atopo-areas-code { font-family: var(--font-mono); font-size: .78rem; }
      .atopo-areas-note { font-size: .68rem; white-space: nowrap; }
      @media (max-width: 1100px) {
        .atopo-body { grid-template-columns: 1fr; }
        .atopo-panel { max-height: none; }
      }
    `}</style>
  )
}
