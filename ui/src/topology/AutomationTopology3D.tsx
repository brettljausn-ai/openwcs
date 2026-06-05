import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Canvas, type ThreeEvent } from '@react-three/fiber'
import { Grid, Html, Line, OrbitControls, PivotControls, RoundedBox, Text } from '@react-three/drei'
import * as THREE from 'three'
import Select from '../ui/Select'
import InfoTip from '../ui/InfoTip'
import { useWarehouse } from '../warehouse/WarehouseContext'
import {
  listEquipment,
  listStorageBlocks,
  updateStorageBlock,
  type Equipment,
  type StorageBlock,
} from '../masterdata/api'
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

const DEG = Math.PI / 180

// Snap radius (metres): a ground/plan click this close to an existing path point reuses that point
// (so junctions form) rather than creating a near-duplicate vertex. Shared by 3D + 2D editors.
export const SNAP_M = 0.5
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
export type EquipmentCategory = 'conveyor' | 'asrs' | 'manual-storage' | 'sorter' | 'other'

export function category(eq: AutomationEquipment, lib: Map<string, Equipment>): EquipmentCategory {
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
export function removeDivertBranch(eq: AutomationEquipment, offsetM: number): AutomationEquipment {
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
  // continues roughly straight; the divert turns off it).
  let branch: number[] | null = null
  let bestPerp = Infinity
  for (const s of outgoings) {
    let ox = path[s[1]][0] - path[jIdx][0]
    let oz = path[s[1]][1] - path[jIdx][1]
    const olen = Math.hypot(ox, oz) || 1
    ox /= olen
    oz /= olen
    const dot = Math.abs(ix * ox + iz * oz)
    if (dot < bestPerp) {
      bestPerp = dot
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
function worldCenter(eq: AutomationEquipment): [number, number, number] {
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
  const { currentWarehouseId: warehouseId } = useWarehouse()

  const [levels, setLevels] = useState<AutomationLevel[]>([])
  const [equipment, setEquipment] = useState<AutomationEquipment[]>([])
  // Connections + function points are not edited in this slice; we hold them so Save round-trips them.
  const [connections, setConnections] = useState<AutomationConnection[]>([])
  const [functionPoints, setFunctionPoints] = useState<AutomationFunctionPoint[]>([])

  const [library, setLibrary] = useState<Equipment[]>([])
  const [activeLevelId, setActiveLevelId] = useState<string>('')
  const [selectedId, setSelectedId] = useState<string | null>(null)
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
  const [selectedConnId, setSelectedConnId] = useState<string | null>(null)
  // The function point whose config dialog is open (clicking a marker in 2D/3D), or null.
  const [editFpId, setEditFpId] = useState<string | null>(null)
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
      const [topo, lib] = await Promise.all([
        loadAutomationTopology(warehouseId),
        listEquipment(warehouseId).catch(() => [] as Equipment[]),
      ])
      setLevels(topo.levels)
      setEquipment(topo.equipment)
      setConnections(topo.connections)
      setFunctionPoints(topo.functionPoints)
      setLibrary(lib)
      setActiveLevelId((prev) =>
        topo.levels.some((l) => l.id === prev) ? prev : topo.levels[0]?.id ?? '',
      )
      setSelectedId(null)
      setDirty(false)
      setInfo(`Loaded ${topo.levels.length} level(s), ${topo.equipment.length} equipment`)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [warehouseId])

  useEffect(() => {
    load()
  }, [load])

  const save = useCallback(async () => {
    if (!warehouseId) {
      setError('No active warehouse selected')
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
      setInfo('Saved')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }, [warehouseId, levels, equipment, connections, functionPoints])

  // Generate the conveyor routing graph (nodes/edges) from the current layout. Saves first so the
  // projection reads the persisted placement, then replaces the warehouse's routing graph.
  const projectGraph = useCallback(async () => {
    if (!warehouseId) {
      setError('No active warehouse selected')
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
      setDirty(false)
      const result = await projectRoutingGraph(warehouseId)
      const warn = result.warnings.length ? ` · ${result.warnings.length} warning(s)` : ''
      setInfo(`Routing graph generated: ${result.nodes} node(s), ${result.edges} edge(s)${warn}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setProjecting(false)
    }
  }, [warehouseId, levels, equipment, connections, functionPoints])

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

  // ---- function-point helpers --------------------------------------------
  const addFunctionPoint = useCallback((fp: AutomationFunctionPoint) => {
    setFunctionPoints((fps) => [...fps, fp])
    setDirty(true)
  }, [])

  const deleteFunctionPoint = useCallback(
    (id: string) => {
      const fp = functionPoints.find((f) => f.id === id)
      // Deleting a point whose function set INCLUDES a divert also removes the branch it spawned.
      if (fp && fpFunctions(fp.functionType).some(isDivertType)) {
        setEquipment((es) => es.map((e) => (e.id === fp.placedId ? removeDivertBranch(e, fp.offsetM) : e)))
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
    [],
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
        setError('Add a level first')
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
    [activeLevelId],
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

  const toggleConnectMode = useCallback(() => {
    setConnectMode((on) => {
      const next = !on
      setConnectFrom(null)
      if (next) {
        setSelectedId(null)
        setDrawPath(false)
      }
      return next
    })
  }, [])

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
              title={`Elevation ${l.elevationM} m`}
            >
              {l.number} · {l.name}
            </button>
          ))}
          <button type="button" className="btn btn-ghost btn-sm" onClick={addLevel}>
            + Add level
          </button>
        </div>
        <div className="atopo-actions">
          <div className="atopo-viewtoggle" role="group" aria-label="View">
            <button
              type="button"
              className={`atopo-viewbtn${view === '3d' ? ' is-active' : ''}`}
              onClick={() => setView('3d')}
            >
              3D
            </button>
            <button
              type="button"
              className={`atopo-viewbtn${view === '2d' ? ' is-active' : ''}`}
              onClick={() => setView('2d')}
            >
              2D plan
            </button>
          </div>
          {dirty && <span className="atopo-dirty">Unsaved changes</span>}
          <button
            type="button"
            className={`btn btn-sm ${connectMode ? 'btn-primary' : 'btn-ghost'}`}
            onClick={toggleConnectMode}
            disabled={loading || saving}
            title="Link two pieces of equipment: click a source, then a target."
          >
            {connectMode ? 'Connecting…' : 'Connect'}
          </button>
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={projectGraph}
            disabled={loading || saving || projecting}
            title="Generate the conveyor routing graph (nodes/edges) from this layout. Saves first, then replaces the Routing graph."
          >
            {projecting ? 'Generating…' : 'Generate routing'}
          </button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={load} disabled={loading || saving}>
            {loading ? 'Loading…' : 'Reload'}
          </button>
          <button type="button" className="btn btn-primary btn-sm" onClick={save} disabled={saving || loading}>
            {saving ? 'Saving…' : 'Save'}
          </button>
          {onToggleChrome && (
            <button
              type="button"
              className="atopo-fold"
              onClick={onToggleChrome}
              aria-pressed={collapsed}
              title={collapsed ? 'Unfold header (more controls)' : 'Fold header up (more canvas)'}
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
              Level name{' '}
              <InfoTip text="Display name for this floor/level of the automation layout." example="Mezzanine" />
            </span>
            <input
              className="form-control"
              value={activeLevel.name}
              onChange={(e) => patchActiveLevel({ name: e.target.value })}
            />
          </label>
          <label className="atopo-inline">
            <span>
              Elevation (m){' '}
              <InfoTip text="Height of this level's floor above the ground datum, in metres." example="5" />
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
          <h3>Equipment library</h3>
          {library.length > 0 && (
            <label className="md-check atopo-unplaced">
              <input
                type="checkbox"
                checked={unplacedOnly}
                onChange={(e) => setUnplacedOnly(e.target.checked)}
              />
              Unplaced only
            </label>
          )}
          {library.length === 0 ? (
            <p className="atopo-muted">
              No equipment — create some in Master data → Equipment.
            </p>
          ) : libraryGroups.length === 0 ? (
            <p className="atopo-muted">All equipment is placed.</p>
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
                          {placedCount > 0 ? `placed ${placedCount}` : 'not placed'}
                        </span>
                      </span>
                      <button
                        type="button"
                        className="btn btn-outline btn-sm"
                        onClick={() => addFromLibrary(e)}
                        disabled={!activeLevelId}
                      >
                        + Add
                      </button>
                    </div>
                  )
                })}
              </div>
            ))
          )}
        </aside>

        {/* ---- center: 3D canvas ---- */}
        <div className="atopo-canvas glass">
          {levels.length === 0 ? (
            <div className="atopo-empty">
              <p>This warehouse has no automation levels yet.</p>
              <button type="button" className="btn btn-primary btn-sm" onClick={addLevel}>
                + Add the first level
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
              onDrawAt={drawSectionAt}
              onAddFunctionPoint={addFunctionPoint}
              onDeleteFunctionPoint={deleteFunctionPoint}
              onUpdateFunctionPoint={updateFunctionPoint}
              onEditFunctionPoint={setEditFpId}
              onAddDivertBranch={addDivertBranch}
              onAddAsrsPortStub={addAsrsPortStub}
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
              {connectMode
                ? connectFrom
                  ? `Connect: from ${equipmentById.get(connectFrom)?.code ?? '?'} — click a target (or the source again to cancel)`
                  : 'Connect: click a source piece of equipment'
                : drawPath
                  ? 'Draw mode: click a start point then an end point — the section runs start → end'
                  : 'Drag to orbit · right-drag to pan · scroll to zoom'}
            </div>
            </>
          )}
        </div>

        {/* ---- right: properties ---- */}
        <aside className="atopo-panel atopo-props glass">
          <h3>Properties</h3>
          {!selected ? (
            <p className="atopo-muted">Select a piece of equipment to edit it, or add one from the library.</p>
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
                <span>Code</span>
                <input
                  className="form-control"
                  value={selected.code}
                  onChange={(e) => patchEquipment(selected.id, { code: e.target.value })}
                />
              </label>
              <label className="atopo-field">
                <span>Level</span>
                <Select
                  ariaLabel="Level"
                  value={selected.levelId}
                  onChange={(v) => patchEquipment(selected.id, { levelId: v })}
                  options={levels.map((l) => ({ value: l.id, label: `${l.number} · ${l.name}` }))}
                />
              </label>
              <div className="atopo-grid2">
                <NumField label="Pos X (m)" value={selected.posXM} onChange={(v) => patchEquipment(selected.id, { posXM: v })} />
                <NumField label="Pos Z (m)" value={selected.posZM} onChange={(v) => patchEquipment(selected.id, { posZM: v })} />
                <NumField label="Pos Y (m)" value={selected.posYM} onChange={(v) => patchEquipment(selected.id, { posYM: v })} />
                <NumField label="Rotation (°)" value={selected.rotationDeg} onChange={(v) => patchEquipment(selected.id, { rotationDeg: v })} />
                <NumField label="Tilt (°)" value={selected.tiltDeg} onChange={(v) => patchEquipment(selected.id, { tiltDeg: v })} />
              </div>
              <div className="atopo-grid2">
                {!hasPath(selected) && (
                  <NumField label="Length (m)" value={selected.lengthM} onChange={(v) => patchEquipment(selected.id, { lengthM: v })} />
                )}
                <NumField label="Width (m)" value={selected.widthM} onChange={(v) => patchEquipment(selected.id, { widthM: v })} />
                <NumField label="Height (m)" value={selected.heightM} onChange={(v) => patchEquipment(selected.id, { heightM: v })} />
              </div>

              <FunctionPointsPanel
                placedId={selected.id}
                points={selectedFunctionPoints}
                processTypes={selectedMeta?.processTypes ?? null}
                onAdd={addFunctionPoint}
                onUpdate={updateFunctionPoint}
                onDelete={deleteFunctionPoint}
              />

              {selectedIsAsrs && warehouseId && (
                <StorageAreasPanel
                  warehouseId={warehouseId}
                  equipmentId={selected.equipmentId ?? null}
                />
              )}

              <button type="button" className="btn btn-danger btn-sm atopo-delete" onClick={deleteSelected}>
                Delete equipment
              </button>
            </div>
          )}

          <ConnectionsPanel
            connections={connections}
            equipmentById={equipmentById}
            selectedConnId={selectedConnId}
            onSelect={(id) => setSelectedConnId((s) => (s === id ? null : id))}
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
          <button type="button" className="atopo-modal-x" onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>

        <label className="atopo-field">
          <span>
            Functions{' '}
            <InfoTip
              text="A point can combine functions — e.g. a scan + divert at the same spot. Toggle the ones that apply (at least one)."
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
          <span>Name</span>
          <input
            className="form-control"
            value={fp.name ?? ''}
            placeholder="optional"
            autoFocus
            onChange={(e) => onUpdate(fp.id, { name: e.target.value || null })}
          />
        </label>

        <div className="atopo-grid2">
          <NumField label="Offset (m)" value={fp.offsetM} onChange={(v) => onUpdate(fp.id, { offsetM: v })} />
          <label className="atopo-field">
            <span>Side</span>
            <Select
              ariaLabel="Side"
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
            Node code{' '}
            <InfoTip
              text="Optional — maps this point to a conveyor routing node so material-flow routes can reference it."
              example="DIV-12"
            />
          </span>
          <input
            className="form-control"
            value={fp.nodeCode ?? ''}
            placeholder="optional"
            onChange={(e) => onUpdate(fp.id, { nodeCode: e.target.value || null })}
          />
        </label>

        <div className="atopo-modal-actions">
          <button
            type="button"
            className="btn btn-danger btn-sm"
            onClick={() => {
              onDelete(fp.id)
              onClose()
            }}
          >
            Delete
          </button>
          <button type="button" className="btn btn-primary btn-sm" onClick={onClose}>
            Done
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
        Conveyor sections{' '}
        <InfoTip
          text="Draw this conveyor section by section as a directed graph: each section is a one-way run with a travel-direction arrow. Click a waypoint to branch from it — a point with 2+ outgoing sections becomes an automatic decision/divert point."
          example="a main line that diverts at a junction"
        />
      </div>
      <div className="atopo-pathcount">
        {count === 0
          ? 'No path — rendering as a straight box.'
          : `${count} point${count === 1 ? '' : 's'} · ${sections.length} section${sections.length === 1 ? '' : 's'}${
              explicitSections === 0 && count >= 2 ? ' (implicit sequential)' : ''
            }${decisions.size > 0 ? ` · ${decisions.size} decision point${decisions.size === 1 ? '' : 's'}` : ''}`}
      </div>

      <button
        type="button"
        className={`btn btn-sm ${drawPath ? 'btn-primary' : 'btn-outline'} atopo-pathbtn`}
        onClick={onToggleDraw}
        disabled={count === 0}
        title={count === 0 ? 'Seed a path first with “Start from box”.' : undefined}
      >
        {drawPath ? 'Drawing sections… (click to stop)' : 'Draw sections'}
      </button>

      {drawPath && (
        <div className="atopo-drawhint">
          {activeFromIdx == null
            ? 'Click a start point (a point/body on the conveyor, or empty floor), then an end point — the section runs start → end. Keep clicking to chain.'
            : `Anchored at point ${activeFromIdx + 1}. Click an end point to draw the section ${activeFromIdx + 1} → there; it then becomes the new anchor. Click point ${activeFromIdx + 1} to re-pick.`}
          {activeFromIdx != null && (
            <button type="button" className="atopo-linkbtn" onClick={onClearAnchor}>
              clear anchor
            </button>
          )}
        </div>
      )}

      {count === 0 && (
        <button type="button" className="btn btn-outline btn-sm atopo-pathbtn" onClick={startFromBox}>
          Start from box
        </button>
      )}

      <label className="md-check atopo-pathcheck">
        <input
          type="checkbox"
          checked={!!eq.closed}
          onChange={(e) => onPatch({ closed: e.target.checked })}
        />
        Closed loop{' '}
        <InfoTip
          text="Only affects implicit sequential paths (no explicit sections): when on, the path loops back from the last waypoint to the first."
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
          Remove last section
        </button>
        <button
          type="button"
          className="btn btn-outline btn-sm"
          onClick={() => onPatch({ path: null, sections: null })}
          disabled={count === 0}
        >
          Clear
        </button>
      </div>
    </div>
  )
}

// Collapsible list of equipment-to-equipment connections with per-row delete + highlight-select.
function ConnectionsPanel({
  connections,
  equipmentById,
  selectedConnId,
  onSelect,
  onDelete,
}: {
  connections: AutomationConnection[]
  equipmentById: Map<string, AutomationEquipment>
  selectedConnId: string | null
  onSelect: (id: string) => void
  onDelete: (id: string) => void
}) {
  const [open, setOpen] = useState(true)
  return (
    <div className="atopo-conns">
      <button
        type="button"
        className="atopo-conns-head"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
      >
        <span>Connections ({connections.length})</span>
        <span className="atopo-conns-chevron">{open ? '▾' : '▸'}</span>
      </button>
      {open &&
        (connections.length === 0 ? (
          <p className="atopo-muted atopo-conns-empty">
            None yet — use Connect to link two pieces of equipment.
          </p>
        ) : (
          <ul className="atopo-conns-list">
            {connections.map((c) => {
              const from = equipmentById.get(c.fromPlacedId)
              const to = equipmentById.get(c.toPlacedId)
              const dangling = !from || !to
              return (
                <li
                  key={c.id}
                  className={`atopo-conns-row${c.id === selectedConnId ? ' is-active' : ''}`}
                >
                  <button
                    type="button"
                    className="atopo-conns-label"
                    onClick={() => onSelect(c.id)}
                    title={dangling ? 'One endpoint is missing from this layout' : 'Highlight this link'}
                  >
                    {from?.code ?? '?'} → {to?.code ?? '?'}
                    {dangling ? <span className="atopo-muted"> · dangling</span> : null}
                  </button>
                  <button
                    type="button"
                    className="btn btn-danger btn-sm"
                    onClick={() => onDelete(c.id)}
                  >
                    Delete
                  </button>
                </li>
              )
            })}
          </ul>
        ))}
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
        Function points{' '}
        <InfoTip
          text="Process points on this equipment — scanners, label applicators, diverts, DWS, query points, wrappers, induct/discharge. Each sits at an offset along the equipment."
          example="a scanner 1.5 m in on the left"
        />
      </div>

      {points.length === 0 ? (
        <p className="atopo-muted atopo-fps-empty">None yet.</p>
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
                  Delete
                </button>
              </li>
            )
          })}
        </ul>
      )}

      <div className="atopo-fps-form">
        <label className="atopo-field">
          <span>Function type</span>
          <Select
            ariaLabel="Function type"
            value={functionType}
            onChange={setFunctionType}
            options={typeOptions.map((t) => ({ value: t, label: t }))}
          />
        </label>
        <label className="atopo-field">
          <span>Name</span>
          <input
            className="form-control"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="optional"
          />
        </label>
        <div className="atopo-grid2">
          <label className="atopo-field">
            <span>Offset (m)</span>
            <input
              className="form-control"
              type="number"
              step={0.1}
              value={offsetM}
              onChange={(e) => setOffsetM(e.target.value)}
            />
          </label>
          <label className="atopo-field">
            <span>Side</span>
            <Select
              ariaLabel="Side"
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
            Node code{' '}
            <InfoTip
              text="Optional — maps this point to a conveyor routing node so material-flow routes can reference it."
              example="DIV-12"
            />
          </span>
          <input
            className="form-control"
            value={nodeCode}
            onChange={(e) => setNodeCode(e.target.value)}
            placeholder="optional"
          />
        </label>
        <button type="button" className="btn btn-outline btn-sm" onClick={add}>
          + Add function point
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
        Storage areas{' '}
        <InfoTip
          text="Bind master-data storage blocks to this ASRS so stock in those blocks is handled by this system. Saves immediately — independent of the topology Save."
          example="link the AutoStore grid's block"
        />
      </div>

      {!equipmentId && (
        <p className="atopo-muted atopo-areas-empty">
          This placement isn't linked to a master-data equipment, so it can't own a storage area.
        </p>
      )}
      {error && <div className="alert alert-danger atopo-areas-error">{error}</div>}
      {loading ? (
        <p className="atopo-muted atopo-areas-empty">Loading storage blocks…</p>
      ) : blocks.length === 0 ? (
        <p className="atopo-muted atopo-areas-empty">
          No storage blocks — create some in Master data → Storage blocks.
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
                  <span className="atopo-muted atopo-areas-note">linked to another equipment</span>
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

      {/* Connection lines — drawn for every link whose endpoints both currently exist (any level). */}
      {connections.map((c) => {
        const from = byId.get(c.fromPlacedId)
        const to = byId.get(c.toPlacedId)
        if (!from || !to) return null
        return (
          <ConnectionLine
            key={c.id}
            a={worldCenter(from)}
            b={worldCenter(to)}
            // Size the arrowhead to the equipment it points at so it never dwarfs a small conveyor
            // (a fixed 0.5 m cone looked huge next to a 0.6 m-wide conveyor) nor vanishes on an ASRS.
            headSize={Math.max(0.08, Math.min(0.18, (to.widthM || 0.6) * 0.2))}
            active={c.id === selectedConnId}
          />
        )
      })}

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
            onSelect={() => (connectMode ? onConnectPick(eq.id) : onSelect(eq.id))}
            onMove={(x, z, rot) => onMove(eq.id, x, z, rot)}
            onMoveWaypoint={(index, x, z) => onMoveWaypoint(eq.id, index, x, z)}
            onAnchorWaypoint={onAnchorWaypoint}
            onHandleDragChange={onHandleDragChange}
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
        return (
          <FunctionPointMarker
            key={fp.id}
            fp={fp}
            eq={eq}
            onSelect={() => (connectMode ? onConnectPick(eq.id) : onEditFunctionPoint(fp.id))}
          />
        )
      })}
    </group>
  )
}

// A small marker (cone) with a short type label, placed at the function point's offset along its
// equipment, nudged to the requested side, and sitting at the top of the equipment. It rides along
// with the equipment because its position is recomputed from the live equipment each render.
function FunctionPointMarker({
  fp,
  eq,
  onSelect,
}: {
  fp: AutomationFunctionPoint
  eq: AutomationEquipment
  onSelect: () => void
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
  const top = eq.heightM + eq.posYM
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
        onPointerDown={(e: ThreeEvent<PointerEvent>) => {
          e.stopPropagation()
          onSelect()
        }}
      >
        {isPort ? <octahedronGeometry args={[0.26, 0]} /> : <coneGeometry args={[0.16, 0.5, 16]} />}
        <meshStandardMaterial color={color} emissive={color} emissiveIntensity={0.45} />
      </mesh>
      <Html position={[0, 0.7, 0]} center distanceFactor={16} occlude={false}>
        <div className="atopo-fpmarker" style={{ borderColor: color, color }}>
          {short}
        </div>
      </Html>
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
}

function EquipmentMesh({
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
        selected={highlight}
        editable={selected && !connectMode}
        drawing={drawing}
        activeFromIdx={activeFromIdx}
        onSelect={onSelect}
        onMoveWaypoint={onMoveWaypoint}
        onAnchorWaypoint={onAnchorWaypoint}
        onHandleDragChange={onHandleDragChange}
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
      <Html position={[0, eq.heightM / 2 + 0.4, 0]} center distanceFactor={18} occlude={false}>
        <div className="atopo-label">{eq.code}</div>
      </Html>
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
}: ConveyorPathProps) {
  const path = (eq.path ?? []) as number[][]
  const sections = effectiveSections(eq)
  const decisions = decisionPoints(sections)
  const junctions = junctionPoints(sections)
  // For a NON-conveyor path host (an ASRS / manual-storage stub), tint the conveyor body in the
  // equipment's own category colour so the stub reads as that equipment's IN/OUT piece — not the
  // default light-blue conveyor (which on an ASRS looks like a vague blur). A real conveyor keeps
  // the default look (tint = undefined).
  const bodyTint = cat === 'conveyor' ? undefined : categoryBodyColor(cat) ?? undefined
  // A real conveyor uses its own envelope; a stub host (ASRS / manual-storage) draws its IN/OUT
  // stubs at a CONVEYOR width AND height so they read as low, flat conveyor pieces — not the full
  // rack footprint width or its (often ~10 m) rack height (which rendered the stub as a tall wall).
  const wM = cat === 'conveyor' ? eq.widthM : STUB_WIDTH_M
  const hM = cat === 'conveyor' ? eq.heightM : STUB_HEIGHT_M
  // Sit the stub low (near the floor, where a conveyor is) rather than at the rack's mid-height.
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

      {/* Junction / decision markers — one per path point used by a section. */}
      {path.map((p, i) => {
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
      {first && (
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
      .atopo-empty {
        height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center;
        gap: .8rem; color: var(--text-dim); text-align: center; padding: 1rem;
      }
      .atopo-muted { color: var(--text-dim); font-size: .8125rem; }
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
