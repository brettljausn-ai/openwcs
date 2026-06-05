// A 2D top-down plan editor for the automation topology. It shares the SAME model/state as the 3D
// view: it reads the active level's placed equipment and the library map, and mutates them through
// the very same callbacks the 3D editor uses (`onPatch` = patchEquipment, `onDrawAt` =
// drawSectionAt, `onSelect` = setSelectedId). So an edit made here shows up in 3D and vice-versa.
//
// Coordinate convention matches 3D: world X → right, world Z → down, rotationDeg = yaw about Y.
// We map metres → pixels with a scale and a pan offset, and snap moves/draws to a metre grid.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { Equipment } from '../masterdata/api'
import type { AutomationEquipment, AutomationFunctionPoint } from './automationApi'
import {
  canEditPath,
  category,
  categoryBodyColor,
  colorFor,
  decisionPoints,
  effectiveSections,
  FUNCTION_SHORT,
  FUNCTION_TYPES,
  functionColor,
  functionColorForSet,
  functionShortForSet,
  fpFunctions,
  isConveyor,
  junctionPoints,
  pointAlong,
  snapToFootprintPerimeter,
  STUB_WIDTH_M,
} from './AutomationTopology3D'

const DEG = Math.PI / 180

const GRID_OPTIONS = [0.25, 0.5, 1] as const

// How close (world metres) the dragged marker must get to a conveyor centreline to snap onto it.
const SNAP_RANGE_M = 0.75

// Pixels the pointer must travel before an equipment "move" actually starts. Below this a pointer-up
// is treated as a pure click (select only, no move) — so clicking a block to select it never makes
// it jump.
const DRAG_THRESHOLD_PX = 4

interface PlanEditor2DProps {
  // The placed equipment on the ACTIVE level (the same array the 3D canvas renders).
  items: AutomationEquipment[]
  // Library map (master-data equipment by id) — drives type colours / conveyor detection.
  libById: Map<string, Equipment>
  // All function points (any level); we render the ones whose placedId is in `items`.
  functionPoints: AutomationFunctionPoint[]
  selectedId: string | null
  // Conveyor "Draw sections" mode is on (mirrors the 3D drawPath state).
  drawing: boolean
  // The current draw anchor: the selected conveyor's path index the next section starts from (or
  // null = the next click just places/anchors a start point). Drives the live pending-section preview.
  activeFromIdx: number | null
  onSelect: (id: string | null) => void
  // Partial update of a placed item — same as patchEquipment in the parent.
  onPatch: (id: string, patch: Partial<AutomationEquipment>) => void
  // A snapped world (x,z) click while drawing — same as drawSectionAt in the parent.
  onDrawAt: (id: string, x: number, z: number) => void
  // Create a function point — same as addFunctionPoint in the parent.
  onAddFunctionPoint: (fp: AutomationFunctionPoint) => void
  // Remove a function point — same as deleteFunctionPoint in the parent.
  onDeleteFunctionPoint: (id: string) => void
  // Partial-update an existing function point — same as updateFunctionPoint in the parent. Used to
  // re-position a placed marker (its placedId / offsetM / side) after dragging it onto a conveyor.
  onUpdateFunctionPoint: (id: string, patch: Partial<AutomationFunctionPoint>) => void
  // Open the config dialog for a function point (clicking a marker without dragging it).
  onEditFunctionPoint: (id: string) => void
  // Materialise a junction at (x,z) on a conveyor and drop a 1 m divert STUB in the divert direction
  // (LEFT/RIGHT) — same as addDivertBranch in the parent. Does NOT enter draw mode. `snapStep` is the
  // grid step to snap the branch endpoint to (null = no snap).
  onAddDivertBranch: (
    id: string,
    x: number,
    z: number,
    side: 'LEFT' | 'RIGHT',
    snapStep: number | null,
    // When true, an INFEED merge: the directed section runs stub → junction (feeder merges in).
    merge?: boolean,
  ) => void
  // Snap (x,z) to an ASRS footprint edge and add a 1 m IN/OUT conveyor stub owned by the ASRS, plus
  // the matching function point — same as addAsrsPortStub in the parent. INDUCT flows toward the
  // rack (stub → port); DISCHARGE flows away (port → stub).
  onAddAsrsPortStub: (
    id: string,
    x: number,
    z: number,
    kind: 'INDUCT' | 'DISCHARGE',
    side: string | null,
  ) => void
}

// The arc-length offset (metres from the path start) of the projection of world point (px,pz) onto
// a conveyor's centreline, plus the projected world position itself. For a polyline we walk each
// segment, clamp the projection to the segment, and keep the closest one (accumulating arc-length).
// For a straight box (no path) we project onto its centreline. Inverse of pointAlong().
function projectToPath(
  eq: AutomationEquipment,
  px: number,
  pz: number,
): { offsetM: number; x: number; z: number } {
  const path = Array.isArray(eq.path) ? eq.path : []
  if (path.length >= 2) {
    const pairCount = eq.closed ? path.length : path.length - 1
    let acc = 0
    let best = { offsetM: 0, x: path[0][0], z: path[0][1], dist: Infinity }
    for (let i = 0; i < pairCount; i++) {
      const a = path[i]
      const b = path[(i + 1) % path.length]
      const abx = b[0] - a[0]
      const abz = b[1] - a[1]
      const len2 = abx * abx + abz * abz
      const segLen = Math.sqrt(len2)
      if (len2 < 1e-9) continue
      let t = ((px - a[0]) * abx + (pz - a[1]) * abz) / len2
      t = Math.min(1, Math.max(0, t))
      const projx = a[0] + abx * t
      const projz = a[1] + abz * t
      const d = Math.hypot(projx - px, projz - pz)
      if (d < best.dist) {
        best = { offsetM: acc + t * segLen, x: projx, z: projz, dist: d }
      }
      acc += segLen
    }
    return { offsetM: +best.offsetM.toFixed(3), x: best.x, z: best.z }
  }
  // Straight box: project onto its centreline from the start endpoint along yaw.
  const yaw = eq.rotationDeg * DEG
  const ux = Math.cos(yaw)
  const uz = Math.sin(yaw)
  const startX = eq.posXM - (ux * eq.lengthM) / 2
  const startZ = eq.posZM - (uz * eq.lengthM) / 2
  let t = (px - startX) * ux + (pz - startZ) * uz
  t = Math.min(eq.lengthM, Math.max(0, t))
  return { offsetM: +t.toFixed(3), x: startX + ux * t, z: startZ + uz * t }
}

// The side a divert type sits on (null for non-divert types).
function sideForType(type: string): 'LEFT' | 'RIGHT' | null {
  if (type === 'DIVERT_LEFT') return 'LEFT'
  if (type === 'DIVERT_RIGHT') return 'RIGHT'
  return null
}

// The side implied by a function SET: the first divert member's side (null if the set has none).
function sideForSet(functionType: string): 'LEFT' | 'RIGHT' | null {
  for (const t of fpFunctions(functionType)) {
    const s = sideForType(t)
    if (s) return s
  }
  return null
}

// Snap a metre value to the nearest multiple of `step` (no-op when snapping is off).
function snap(v: number, step: number, on: boolean): number {
  if (!on || step <= 0) return +v.toFixed(3)
  return +(Math.round(v / step) * step).toFixed(3)
}

export default function PlanEditor2D({
  items,
  libById,
  functionPoints,
  selectedId,
  drawing,
  activeFromIdx,
  onSelect,
  onPatch,
  onDrawAt,
  onAddFunctionPoint,
  onDeleteFunctionPoint,
  onUpdateFunctionPoint,
  onEditFunctionPoint,
  onAddDivertBranch,
  onAddAsrsPortStub,
}: PlanEditor2DProps) {
  // View transform: pixels-per-metre and a pan offset (in pixels) of the world origin.
  const [pxPerM, setPxPerM] = useState(40)
  const [pan, setPan] = useState({ x: 0, y: 0 })
  const [gridStep, setGridStep] = useState<number>(0.5)
  const [snapOn, setSnapOn] = useState(true)

  // A PENDING (not-yet-placed) function point created from the palette. It floats at `world` (metres)
  // and is dragged onto a conveyor. `snap` holds the live snap preview (target conveyor + projected
  // position/offset) while the cursor is within range of a centreline; null = not snapped.
  // A snap target for a dragged point: either a CONVEYOR centreline (offsetM along it) or an ASRS
  // EDGE (a point snapped to the rack footprint perimeter, with the outward normal baked into x,z by
  // snapToFootprintPerimeter). `kind` discriminates how a drop is committed.
  type SnapTarget = {
    kind: 'conveyor' | 'asrs-edge'
    eqId: string
    offsetM: number
    x: number
    z: number
  }
  type Pending = {
    functionType: string
    world: { x: number; z: number }
    snap: SnapTarget | null
  }
  const [pending, setPending] = useState<Pending | null>(null)

  // Live draw preview: where the cursor's next click would land while in Draw-sections mode.
  // `onConveyor` is true when it snapped onto the selected conveyor's centreline (vs a free grid point).
  const [drawHover, setDrawHover] = useState<{ x: number; z: number; onConveyor: boolean } | null>(null)

  const svgRef = useRef<SVGSVGElement | null>(null)
  // The current free-form drag (equipment move, waypoint move, background pan, a pending FP being
  // dragged onto a conveyor, or an already-placed FP being re-positioned).
  const drag = useRef<
    | { kind: 'pan'; startX: number; startY: number; panX: number; panY: number }
    // Equipment move: a plain click only SELECTS; we don't move the item until the pointer crosses a
    // small pixel threshold (`active`). startX/startY = pointer-down screen point; startWorld = the
    // world point under the pointer at down; baseX/baseZ = the item's position at down. We translate
    // by the world delta from the drag start (not snap-to-cursor) so the item never jumps mid-screen.
    | {
        kind: 'move'
        id: string
        startX: number
        startY: number
        startWorld: { x: number; z: number }
        baseX: number
        baseZ: number
        active: boolean
      }
    | { kind: 'waypoint'; id: string; index: number; path: number[][] }
    | { kind: 'pending' }
    // FP marker: a plain click (no move past the threshold) opens its config dialog; a drag past the
    // threshold re-positions it along the nearest conveyor. moved gates which happens on pointer-up.
    | { kind: 'fp'; id: string; startX: number; startY: number; moved: boolean }
    | null
  >(null)
  // Live snap preview while dragging an already-PLACED marker (target conveyor + projection).
  const [fpSnap, setFpSnap] = useState<{ eqId: string; offsetM: number; x: number; z: number } | null>(
    null,
  )

  // ---- coordinate helpers (world metres <-> screen pixels) -----------------
  const toPx = useCallback(
    (xM: number, zM: number): [number, number] => [xM * pxPerM + pan.x, zM * pxPerM + pan.y],
    [pxPerM, pan],
  )

  // Convert a pointer event's client position into world (x,z) metres.
  const clientToWorld = useCallback(
    (clientX: number, clientY: number): { x: number; z: number } => {
      const svg = svgRef.current
      const rect = svg?.getBoundingClientRect()
      const localX = clientX - (rect?.left ?? 0)
      const localY = clientY - (rect?.top ?? 0)
      return { x: (localX - pan.x) / pxPerM, z: (localY - pan.y) / pxPerM }
    },
    [pan, pxPerM],
  )

  // ---- grid lines ----------------------------------------------------------
  const grid = useMemo(() => {
    const svg = svgRef.current
    const w = svg?.clientWidth ?? 1200
    const h = svg?.clientHeight ?? 800
    // World extent currently visible (with a small margin), snapped to the grid.
    const x0 = Math.floor((-pan.x / pxPerM) / gridStep) * gridStep - gridStep
    const x1 = Math.ceil(((w - pan.x) / pxPerM) / gridStep) * gridStep + gridStep
    const z0 = Math.floor((-pan.y / pxPerM) / gridStep) * gridStep - gridStep
    const z1 = Math.ceil(((h - pan.y) / pxPerM) / gridStep) * gridStep + gridStep
    const vert: { x: number; strong: boolean }[] = []
    const horiz: { y: number; strong: boolean }[] = []
    // Guard against pathological counts (e.g. zoomed way out with a fine grid).
    const maxLines = 600
    if ((x1 - x0) / gridStep <= maxLines) {
      for (let x = x0; x <= x1 + 1e-6; x += gridStep) {
        const [px] = toPx(x, 0)
        vert.push({ x: px, strong: Math.abs(x - Math.round(x)) < 1e-6 })
      }
    }
    if ((z1 - z0) / gridStep <= maxLines) {
      for (let z = z0; z <= z1 + 1e-6; z += gridStep) {
        const [, py] = toPx(0, z)
        horiz.push({ y: py, strong: Math.abs(z - Math.round(z)) < 1e-6 })
      }
    }
    const [ox, oy] = toPx(0, 0)
    return { vert, horiz, w, h, ox, oy }
  }, [pan, pxPerM, gridStep, toPx])

  // ---- wheel zoom (around the cursor) --------------------------------------
  // React registers onWheel as a PASSIVE root listener, so calling preventDefault() in a React
  // handler is rejected ("Unable to preventDefault inside passive event listener") and floods the
  // console on every tick. Attach a native NON-passive wheel listener instead. A ref carries the
  // current pan/zoom so the listener can stay attached once (no re-bind per pan/zoom).
  const viewRef = useRef({ pan, pxPerM })
  viewRef.current = { pan, pxPerM }
  useEffect(() => {
    const el = svgRef.current
    if (!el) return
    const handler = (e: WheelEvent) => {
      e.preventDefault()
      const rect = el.getBoundingClientRect()
      const cx = e.clientX - rect.left
      const cy = e.clientY - rect.top
      const { pan: p, pxPerM: scale } = viewRef.current
      const factor = e.deltaY < 0 ? 1.12 : 1 / 1.12
      const next = Math.min(240, Math.max(6, scale * factor))
      if (next === scale) return
      // Keep the world point under the cursor fixed: solve for the new pan.
      const worldX = (cx - p.x) / scale
      const worldZ = (cy - p.y) / scale
      setPan({ x: cx - worldX * next, y: cy - worldZ * next })
      setPxPerM(next)
    }
    el.addEventListener('wheel', handler, { passive: false })
    return () => el.removeEventListener('wheel', handler)
  }, [])

  // ---- selection -----------------------------------------------------------
  const selected = useMemo(() => items.find((it) => it.id === selectedId) ?? null, [items, selectedId])

  // Function points grouped by the placed equipment they sit on (only items on this level).
  const fpByPlaced = useMemo(() => {
    const onLevel = new Set(items.map((it) => it.id))
    const m = new Map<string, AutomationFunctionPoint[]>()
    for (const fp of functionPoints) {
      if (!onLevel.has(fp.placedId)) continue
      const arr = m.get(fp.placedId) ?? []
      arr.push(fp)
      m.set(fp.placedId, arr)
    }
    return m
  }, [functionPoints, items])

  // Project a world point onto the NEAREST conveyor centreline across all the level's conveyors,
  // returning the closest within `SNAP_M` (world metres) or null. Drives the snap preview + drop.
  const conveyors = useMemo(() => items.filter((it) => isConveyor(it, libById)), [items, libById])
  // ASRS placements on this level — IN/OUT ports snap to their footprint perimeter.
  const asrsItems = useMemo(
    () => items.filter((it) => category(it, libById) === 'asrs'),
    [items, libById],
  )
  const nearestConveyorSnap = useCallback(
    (wx: number, wz: number): SnapTarget | null => {
      let best: SnapTarget | null = null
      let bestDist = SNAP_RANGE_M
      for (const eq of conveyors) {
        const proj = projectToPath(eq, wx, wz)
        const d = Math.hypot(proj.x - wx, proj.z - wz)
        if (d < bestDist) {
          bestDist = d
          best = { kind: 'conveyor', eqId: eq.id, offsetM: proj.offsetM, x: proj.x, z: proj.z }
        }
      }
      return best
    },
    [conveyors],
  )

  // Snap a dragged ASRS port (INDUCT/DISCHARGE) to the nearest ASRS footprint perimeter within range.
  const nearestAsrsEdgeSnap = useCallback(
    (wx: number, wz: number): SnapTarget | null => {
      let best: SnapTarget | null = null
      let bestDist = SNAP_RANGE_M
      for (const eq of asrsItems) {
        const snap = snapToFootprintPerimeter(eq, wx, wz)
        const d = Math.hypot(snap.x - wx, snap.z - wz)
        if (d < bestDist) {
          bestDist = d
          best = { kind: 'asrs-edge', eqId: eq.id, offsetM: 0, x: snap.x, z: snap.z }
        }
      }
      return best
    },
    [asrsItems],
  )

  // The snap target for a PENDING function point of a given type. INDUCT/DISCHARGE prefer an ASRS edge
  // (and ONLY snap there); INFEED and all other types snap to a conveyor centreline.
  const pendingSnap = useCallback(
    (type: string, wx: number, wz: number): SnapTarget | null => {
      if (type === 'INDUCT' || type === 'DISCHARGE') return nearestAsrsEdgeSnap(wx, wz)
      return nearestConveyorSnap(wx, wz)
    },
    [nearestAsrsEdgeSnap, nearestConveyorSnap],
  )

  // Esc cancels a pending (un-placed) function point.
  useEffect(() => {
    if (!pending) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setPending(null)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [pending])

  // Clicking a palette type CREATES a pending function point floating at a staging spot near the
  // top-left of the canvas (in world metres). The user then drags it onto a conveyor to place it.
  const createPending = useCallback(
    (type: string) => {
      // Stage it a little in from the top-left corner of the current view.
      const world = clientToWorld(
        (svgRef.current?.getBoundingClientRect().left ?? 0) + 60,
        (svgRef.current?.getBoundingClientRect().top ?? 0) + 60,
      )
      setPending({ functionType: type, world, snap: pendingSnap(type, world.x, world.z) })
    },
    [clientToWorld, pendingSnap],
  )

  // Commit a pending function point at its current snap (called on drop). If snapped to a conveyor,
  // create the real point; a DIVERT_* additionally drops a 1 m branch stub in the divert direction.
  // If not snapped, discard (cancel) the pending point.
  const commitPending = useCallback(() => {
    setPending((cur) => {
      if (!cur) return null
      if (!cur.snap) return null // dropped off any valid target → discard
      const type = cur.functionType

      // ASRS IN/OUT port: snap to the rack edge and add a 1 m IN/OUT conveyor stub OWNED by the ASRS,
      // plus its function point (created together inside onAddAsrsPortStub).
      if (cur.snap.kind === 'asrs-edge' && (type === 'INDUCT' || type === 'DISCHARGE')) {
        onAddAsrsPortStub(cur.snap.eqId, cur.snap.x, cur.snap.z, type, null)
        return null
      }

      const side = sideForType(type)
      onAddFunctionPoint({
        id: crypto.randomUUID(),
        placedId: cur.snap.eqId,
        functionType: type,
        name: null,
        offsetM: cur.snap.offsetM,
        side,
        nodeCode: null,
        status: 'ACTIVE',
      })
      if (side) {
        // DIVERT_* → ensure a junction + drop a 1 m perpendicular stub in the divert direction.
        onAddDivertBranch(cur.snap.eqId, cur.snap.x, cur.snap.z, side, snapOn ? gridStep : null)
      } else if (type === 'INFEED') {
        // INFEED is the mirror of a divert: ensure a junction + drop a 1 m perpendicular stub whose
        // directed section runs INTO the junction (the feeder merges in). Default to the LEFT side.
        onAddDivertBranch(cur.snap.eqId, cur.snap.x, cur.snap.z, 'LEFT', snapOn ? gridStep : null, true)
      }
      return null
    })
  }, [onAddFunctionPoint, onAddDivertBranch, onAddAsrsPortStub, snapOn, gridStep])

  // Where a draw-mode click at world (wx,wz) would land: snapped onto the selected conveyor's
  // centreline when within range (so it lands ON the conveyor and connects), else the grid point.
  // Shared by the actual click and the live hover preview so they always agree.
  const drawSnapAt = useCallback(
    (wx: number, wz: number): { x: number; z: number; onConveyor: boolean } => {
      const selEq = items.find((it) => it.id === selectedId)
      if (selEq) {
        const proj = projectToPath(selEq, wx, wz)
        if (Math.hypot(proj.x - wx, proj.z - wz) <= SNAP_RANGE_M) {
          return { x: proj.x, z: proj.z, onConveyor: true }
        }
      }
      return { x: snap(wx, gridStep, snapOn), z: snap(wz, gridStep, snapOn), onConveyor: false }
    },
    [items, selectedId, gridStep, snapOn],
  )

  // ---- background interactions ---------------------------------------------
  const onBackgroundPointerDown = useCallback(
    (e: React.PointerEvent<SVGRectElement>) => {
      if (e.button !== 0) return
      if (drawing && selectedId) {
        // Draw mode: a click on the plane draws a section on the selected conveyor.
        const w = clientToWorld(e.clientX, e.clientY)
        const s = drawSnapAt(w.x, w.z)
        onDrawAt(selectedId, s.x, s.z)
        return
      }
      // Otherwise begin a pan; an empty click (no drag) deselects on pointer-up.
      drag.current = { kind: 'pan', startX: e.clientX, startY: e.clientY, panX: pan.x, panY: pan.y }
      ;(e.target as Element).setPointerCapture?.(e.pointerId)
    },
    [drawing, selectedId, clientToWorld, onDrawAt, pan, drawSnapAt],
  )

  // A single pointer-move handler at the SVG level drives pan / move / waypoint / FP-marker drags.
  const onPointerMove = useCallback(
    (e: React.PointerEvent<SVGSVGElement>) => {
      const d = drag.current
      if (!d) {
        // No drag in progress. While drawing, track where the next click would land (live preview).
        if (drawing && selectedId) {
          const w = clientToWorld(e.clientX, e.clientY)
          setDrawHover(drawSnapAt(w.x, w.z))
        }
        return
      }
      if (d.kind === 'pan') {
        setPan({ x: d.panX + (e.clientX - d.startX), y: d.panY + (e.clientY - d.startY) })
        return
      }
      const w = clientToWorld(e.clientX, e.clientY)
      if (d.kind === 'pending') {
        // Move the floating pending marker; live-snap it (conveyor centreline, or ASRS edge for
        // INDUCT/DISCHARGE) per its type.
        setPending((cur) =>
          cur ? { ...cur, world: w, snap: pendingSnap(cur.functionType, w.x, w.z) } : cur,
        )
        return
      }
      if (d.kind === 'fp') {
        // Gate movement behind a small pixel threshold so a plain click only opens the dialog (on
        // pointer-up) instead of nudging the point.
        if (!d.moved) {
          if (Math.hypot(e.clientX - d.startX, e.clientY - d.startY) < DRAG_THRESHOLD_PX) return
          d.moved = true
        }
        // Re-positioning an already-placed marker. Project the cursor onto the nearest conveyor
        // centreline and move the point there LIVE (constrained to the centreline → new offsetM).
        // Keep the same placedId unless the cursor snaps onto a DIFFERENT conveyor. Off any
        // conveyor → leave the point where it is (just clear the preview).
        const snapTarget = nearestConveyorSnap(w.x, w.z)
        setFpSnap(snapTarget)
        if (snapTarget) {
          const fp = functionPoints.find((f) => f.id === d.id)
          const patch: Partial<AutomationFunctionPoint> = { offsetM: snapTarget.offsetM }
          // Only rebind to a new conveyor when the cursor actually moved to a different one.
          if (fp && fp.placedId !== snapTarget.eqId) patch.placedId = snapTarget.eqId
          onUpdateFunctionPoint(d.id, patch)
        }
        return
      }
      if (d.kind === 'move') {
        // Gate the move behind a small pixel threshold so a plain click only selects (no jump).
        if (!d.active) {
          const moved = Math.hypot(e.clientX - d.startX, e.clientY - d.startY)
          if (moved < DRAG_THRESHOLD_PX) return
          d.active = true
        }
        // Translate by the world delta from the drag start (don't snap the item centre to the
        // cursor), then snap the resulting position to the grid.
        const dxWorld = w.x - d.startWorld.x
        const dzWorld = w.z - d.startWorld.z
        onPatch(d.id, {
          posXM: snap(d.baseX + dxWorld, gridStep, snapOn),
          posZM: snap(d.baseZ + dzWorld, gridStep, snapOn),
        })
        return
      }
      const sx = snap(w.x, gridStep, snapOn)
      const sz = snap(w.z, gridStep, snapOn)
      if (d.kind === 'waypoint') {
        const next = d.path.map((p, i) => (i === d.index ? [sx, sz] : [p[0], p[1]]))
        onPatch(d.id, { path: next })
      }
    },
    [clientToWorld, gridStep, snapOn, onPatch, nearestConveyorSnap, pendingSnap, functionPoints, onUpdateFunctionPoint, drawing, selectedId, drawSnapAt],
  )

  // Drop the live draw preview when we leave draw mode (or nothing's selected to draw on).
  useEffect(() => {
    if (!drawing || !selectedId) setDrawHover(null)
  }, [drawing, selectedId])

  // End any drag. A 'pending' drag commits/discards the pending point. An 'fp' drag has already moved
  // the point live during onPointerMove, so here we only finalise: re-assert the divert side (so a
  // DIVERT_* keeps its LEFT/RIGHT after a rebind) and clear the snap preview.
  const endDrag = useCallback(() => {
    const d = drag.current
    drag.current = null
    if (d?.kind === 'pending') {
      commitPending()
    } else if (d?.kind === 'fp') {
      setFpSnap(null)
      if (!d.moved) {
        // A click (no drag) opens this point's config dialog.
        onEditFunctionPoint(d.id)
      } else {
        const fp = functionPoints.find((f) => f.id === d.id)
        const side = sideForSet(fp?.functionType ?? '')
        // Keep the divert FPs' side; non-divert points keep whatever side they had.
        if (side && fp && fp.side !== side) onUpdateFunctionPoint(d.id, { side })
      }
    }
  }, [commitPending, functionPoints, onUpdateFunctionPoint, onEditFunctionPoint])

  // A background click that wasn't a meaningful pan deselects (only when not drawing).
  const onBackgroundPointerUp = useCallback(
    (e: React.PointerEvent<SVGRectElement>) => {
      const d = drag.current
      if (d && d.kind === 'pan') {
        const moved = Math.hypot(e.clientX - d.startX, e.clientY - d.startY)
        if (moved < 4 && !drawing) onSelect(null)
      }
      drag.current = null
    },
    [drawing, onSelect],
  )

  // ---- rotate the selected item -------------------------------------------
  const rotateBy = useCallback(
    (delta: number) => {
      if (!selected) return
      let next = (selected.rotationDeg + delta) % 360
      if (next < 0) next += 360
      onPatch(selected.id, { rotationDeg: +next.toFixed(2) })
    },
    [selected, onPatch],
  )

  return (
    <div className="plan2d">
      {/* ---- toolbar: grid step, snap toggle, zoom, rotate ---- */}
      <div className="plan2d-bar">
        <span className="plan2d-bar-label">Grid</span>
        <div className="plan2d-seg">
          {GRID_OPTIONS.map((g) => (
            <button
              key={g}
              type="button"
              className={`plan2d-segbtn${gridStep === g ? ' is-active' : ''}`}
              onClick={() => setGridStep(g)}
            >
              {g} m
            </button>
          ))}
        </div>
        <label className="plan2d-check">
          <input type="checkbox" checked={snapOn} onChange={(e) => setSnapOn(e.target.checked)} />
          Snap
        </label>
        <div className="plan2d-bar-spacer" />
        {selected && (
          <div className="plan2d-rotate">
            <span className="plan2d-bar-label">Rotate</span>
            <button type="button" className="plan2d-iconbtn" onClick={() => rotateBy(-15)} title="Rotate 15° CCW">
              ⟲ 15°
            </button>
            <button type="button" className="plan2d-iconbtn" onClick={() => rotateBy(15)} title="Rotate 15° CW">
              ⟳ 15°
            </button>
            <button type="button" className="plan2d-iconbtn" onClick={() => rotateBy(90)} title="Rotate 90°">
              90°
            </button>
          </div>
        )}
        <button
          type="button"
          className="plan2d-iconbtn"
          onClick={() => {
            setPan({ x: 0, y: 0 })
            setPxPerM(40)
          }}
          title="Reset view"
        >
          Reset view
        </button>
      </div>

      {/* ---- function-point palette: click a type to create a draggable point, then drag it onto a
              target (conveyor centreline, or an ASRS edge for IN/OUT ports). Shown when the level has
              at least one conveyor OR ASRS and we're not drawing. Each button is enabled only when its
              drop target exists: INDUCT/DISCHARGE need an ASRS; every other type needs a conveyor. ---- */}
      {(conveyors.length > 0 || asrsItems.length > 0) && !drawing && (
        <div className="plan2d-fpbar">
          <span className="plan2d-bar-label">New point</span>
          <div className="plan2d-fppalette">
            {FUNCTION_TYPES.map((t) => {
              const isDivert = t === 'DIVERT_LEFT' || t === 'DIVERT_RIGHT'
              const isPort = t === 'INDUCT' || t === 'DISCHARGE'
              const isInfeed = t === 'INFEED'
              // INDUCT/DISCHARGE drop onto an ASRS edge; everything else onto a conveyor.
              const enabled = isPort ? asrsItems.length > 0 : conveyors.length > 0
              const target = isPort ? 'an ASRS' : 'a conveyor'
              return (
                <button
                  key={t}
                  type="button"
                  className={`plan2d-fpbtn${isDivert ? ' is-divert' : ''}${
                    isPort ? ' is-port' : ''
                  }${isInfeed ? ' is-infeed' : ''}`}
                  onClick={() => createPending(t)}
                  disabled={!enabled}
                  title={
                    enabled
                      ? `Create a ${t} and drag it onto ${target}`
                      : `Add ${target} first to place a ${t}`
                  }
                >
                  {FUNCTION_SHORT[t] ?? t}
                </button>
              )
            })}
          </div>
          {pending && (
            <span className="plan2d-fphint">
              Drag the new point onto{' '}
              {pending.functionType === 'INDUCT' || pending.functionType === 'DISCHARGE'
                ? 'an ASRS'
                : 'a conveyor'}{' '}
              · Esc to cancel
            </span>
          )}
        </div>
      )}

      <svg
        ref={svgRef}
        className="plan2d-svg"
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerLeave={() => {
          endDrag()
          setDrawHover(null)
        }}
      >
        {/* Background — pan / deselect / draw target. */}
        <rect
          x={0}
          y={0}
          width="100%"
          height="100%"
          fill="transparent"
          style={{ cursor: drawing ? 'crosshair' : pending ? 'grabbing' : 'grab' }}
          onPointerDown={onBackgroundPointerDown}
          onPointerUp={onBackgroundPointerUp}
        />

        {/* Grid lines. */}
        <g className="plan2d-grid" pointerEvents="none">
          {grid.vert.map((v, i) => (
            <line
              key={`v-${i}`}
              x1={v.x}
              y1={0}
              x2={v.x}
              y2={grid.h}
              className={v.strong ? 'plan2d-gridline is-strong' : 'plan2d-gridline'}
            />
          ))}
          {grid.horiz.map((hl, i) => (
            <line
              key={`h-${i}`}
              x1={0}
              y1={hl.y}
              x2={grid.w}
              y2={hl.y}
              className={hl.strong ? 'plan2d-gridline is-strong' : 'plan2d-gridline'}
            />
          ))}
          {/* World origin crosshair. */}
          <line x1={grid.ox - 8} y1={grid.oy} x2={grid.ox + 8} y2={grid.oy} className="plan2d-origin" />
          <line x1={grid.ox} y1={grid.oy - 8} x2={grid.ox} y2={grid.oy + 8} className="plan2d-origin" />
        </g>

        {/* Equipment + conveyor paths. */}
        {items.map((eq) => (
          <PlanItem
            key={eq.id}
            eq={eq}
            conveyor={isConveyor(eq, libById)}
            // An ASRS that owns IN/OUT stubs also draws its path/sections as conveyor stubs (on top of
            // its rack box) and gets draggable waypoints to extend them.
            stubHost={canEditPath(eq, libById) && !isConveyor(eq, libById)}
            color={colorFor(eq, libById)}
            // Stub conveyors on a NON-conveyor (ASRS / manual storage) draw in the equipment's
            // category body colour (e.g. the mid-blue ASRS rack colour) so they match the recoloured
            // 3D stub; a real conveyor keeps its own colour for the path.
            pathColor={categoryBodyColor(category(eq, libById)) ?? colorFor(eq, libById)}
            selected={eq.id === selectedId}
            drawing={drawing && eq.id === selectedId}
            pxPerM={pxPerM}
            toPx={toPx}
            onSelect={onSelect}
            onMoveStart={(id, clientX, clientY) => {
              const startWorld = clientToWorld(clientX, clientY)
              drag.current = {
                kind: 'move',
                id,
                startX: clientX,
                startY: clientY,
                startWorld,
                baseX: eq.posXM,
                baseZ: eq.posZM,
                active: false,
              }
            }}
            onWaypointStart={(id, index, path) => {
              drag.current = { kind: 'waypoint', id, index, path }
            }}
            onDrawAtPoint={onDrawAt}
          />
        ))}

        {/* Snap preview: a ring on the conveyor centreline where the dragged point would land. */}
        {(() => {
          const preview = pending?.snap ?? fpSnap
          if (!preview) return null
          const [px, py] = toPx(preview.x, preview.z)
          return (
            <g pointerEvents="none">
              <circle cx={px} cy={py} r={9} className="plan2d-snapring" />
              <circle cx={px} cy={py} r={2.5} fill="#8DC63F" />
            </g>
          )
        })()}

        {/* Live DRAW preview: a dot where the next click lands (lime = snaps onto the conveyor and
            will connect; amber = a free grid point), plus a dashed line from the current anchor
            showing the pending section's direction (anchor → cursor). */}
        {drawing &&
          drawHover &&
          (() => {
            const [hx, hy] = toPx(drawHover.x, drawHover.z)
            const color = drawHover.onConveyor ? '#8DC63F' : '#f0a85a'
            const selEq = items.find((it) => it.id === selectedId)
            const anchorPt =
              selEq && activeFromIdx != null && Array.isArray(selEq.path)
                ? selEq.path[activeFromIdx]
                : null
            return (
              <g pointerEvents="none">
                {anchorPt &&
                  (() => {
                    const [ax, ay] = toPx(anchorPt[0], anchorPt[1])
                    return (
                      <line
                        x1={ax}
                        y1={ay}
                        x2={hx}
                        y2={hy}
                        stroke={color}
                        strokeWidth={1.5}
                        strokeDasharray="5 4"
                        strokeOpacity={0.85}
                      />
                    )
                  })()}
                <circle cx={hx} cy={hy} r={6} fill="none" stroke={color} strokeWidth={1.5} />
                <circle cx={hx} cy={hy} r={2.5} fill={color} />
              </g>
            )
          })()}

        {/* Function-point markers, drawn on top of the equipment they belong to. Draggable: a drag
            re-positions the marker onto (or along) the nearest conveyor centreline (snap). */}
        {items.map((eq) =>
          (fpByPlaced.get(eq.id) ?? []).map((fp) => (
            <PlanFunctionPoint
              key={fp.id}
              fp={fp}
              eq={eq}
              pxPerM={pxPerM}
              toPx={toPx}
              dragging={drag.current?.kind === 'fp' && drag.current.id === fp.id}
              onSelect={() => onSelect(eq.id)}
              onDragStart={(e) => {
                drag.current = { kind: 'fp', id: fp.id, startX: e.clientX, startY: e.clientY, moved: false }
                setFpSnap(null)
                ;(e.target as Element).setPointerCapture?.(e.pointerId)
              }}
            />
          )),
        )}

        {/* The PENDING (un-placed) function point — a floating, clearly-"unplaced" draggable marker. */}
        {pending && (
          <PendingMarker
            pending={pending}
            toPx={toPx}
            onDragStart={(e) => {
              drag.current = { kind: 'pending' }
              ;(e.target as Element).setPointerCapture?.(e.pointerId)
            }}
          />
        )}
      </svg>

      <div className="plan2d-hint">
        {pending
          ? pending.snap
            ? sideForType(pending.functionType)
              ? `Drop to place the divert — a junction + 1 m stub appears in the divert direction`
              : pending.functionType === 'INFEED'
                ? `Drop to place the infeed — a junction + 1 m merge stub appears (extend it back to its source)`
                : pending.snap.kind === 'asrs-edge'
                  ? `Drop to place the ${FUNCTION_SHORT[pending.functionType] ?? pending.functionType} port — a 1 m stub appears on the ASRS edge`
                  : `Drop to place the ${FUNCTION_SHORT[pending.functionType] ?? pending.functionType} on the conveyor`
            : pending.functionType === 'INDUCT' || pending.functionType === 'DISCHARGE'
              ? 'Drag onto an ASRS to place an IN/OUT port · drop off (or Esc) to cancel'
              : 'Drag onto a conveyor to place · drop off any conveyor (or Esc) to cancel'
          : drawing
            ? 'Draw a section: click a start point, then an end point — the section runs in that order. Click the conveyor (point or body) to start/end on it; click empty grid for a free point.'
            : 'Drag an item to move (snapped) · click a point to select it, drag it along the conveyor to reposition · drag empty space to pan · scroll to zoom'}
      </div>

      <PlanStyles />
    </div>
  )
}

// One placed item rendered top-down: a conveyor (path + directed sections + junction/decision dots
// + draggable waypoints) or a plain rotated rect. Mutates state via the shared callbacks.
function PlanItem({
  eq,
  conveyor,
  stubHost,
  color,
  pathColor,
  selected,
  drawing,
  pxPerM,
  toPx,
  onSelect,
  onMoveStart,
  onWaypointStart,
  onDrawAtPoint,
}: {
  eq: AutomationEquipment
  conveyor: boolean
  // An ASRS (non-conveyor) that owns IN/OUT conveyor stubs: render its path/sections on top of its box.
  stubHost: boolean
  color: string
  // Stroke colour for the conveyor path/sections. For a stub host this is the equipment's category
  // body colour (e.g. ASRS mid-blue) so the stub matches the recoloured 3D body; for a real conveyor
  // it's just the conveyor's own colour.
  pathColor: string
  selected: boolean
  drawing: boolean
  pxPerM: number
  toPx: (xM: number, zM: number) => [number, number]
  onSelect: (id: string | null) => void
  // Begin an equipment move drag. The pointer-down screen point (clientX/clientY) is passed so the
  // parent can record the drag-start anchor and gate the actual move behind a small pixel threshold.
  onMoveStart: (id: string, clientX: number, clientY: number) => void
  onWaypointStart: (id: string, index: number, path: number[][]) => void
  onDrawAtPoint: (id: string, x: number, z: number) => void
}) {
  const path = Array.isArray(eq.path) ? eq.path : []
  const isPathConveyor = conveyor && path.length >= 2
  // The path/sections overlay (directed sections + chevrons + junctions + waypoints + label). Shared
  // by a real conveyor (rendered alone) and an ASRS stub host (rendered on top of its rack box).
  const renderPath = () => {
    const sections = effectiveSections(eq)
    const decisions = decisionPoints(sections)
    const junctions = junctionPoints(sections)
    // A stub host (ASRS) draws its IN/OUT stubs at a conveyor width, not the rack footprint width.
    const wM = stubHost ? STUB_WIDTH_M : eq.widthM
    const [lx, ly] = toPx(path[0][0], path[0][1])
    return (
      <g>
        {/* Directed sections — the section body, plus small per-metre travel chevrons. */}
        {sections.map(([i, j], k) => {
          const a = path[i]
          const b = path[j]
          if (!a || !b) return null
          const [ax, ay] = toPx(a[0], a[1])
          const [bx, by] = toPx(b[0], b[1])
          return (
            <line
              key={`s-${k}`}
              x1={ax}
              y1={ay}
              x2={bx}
              y2={by}
              stroke={pathColor}
              strokeWidth={Math.max(2, wM * pxPerM * 0.5)}
              strokeOpacity={selected ? 0.95 : 0.8}
              strokeLinecap="round"
              style={{ cursor: drawing ? 'crosshair' : 'pointer' }}
              onPointerDown={(e) => {
                if (drawing) return
                e.stopPropagation()
                onSelect(eq.id)
              }}
            />
          )
        })}

        {/* Small, subtle travel-direction chevrons repeated every ~1 m along each section. */}
        {sections.map(([i, j], k) => {
          const a = path[i]
          const b = path[j]
          if (!a || !b) return null
          const dx = b[0] - a[0]
          const dz = b[1] - a[1]
          const len = Math.hypot(dx, dz)
          if (len < 1e-4) return null
          const ux = dx / len
          const uz = dz / len
          // Right-hand normal on the plane to (ux,uz) is (uz,-ux); barbs sweep back from each tip.
          const nx = uz
          const nz = -ux
          const count = Math.min(30, Math.max(1, Math.round(len)))
          const sizePx = Math.max(3, Math.min(7, wM * pxPerM * 0.22)) // small
          const sizeM = sizePx / pxPerM
          const chevrons = []
          for (let c = 1; c <= count; c++) {
            // Place chevrons at metre-spaced fractions, biased to sit between the endpoints.
            const dM = (c - 0.5) * (len / count)
            const cxM = a[0] + ux * dM
            const czM = a[1] + uz * dM
            const [tx, ty] = toPx(cxM + ux * sizeM, czM + uz * sizeM)
            const [lx2, ly2] = toPx(cxM - ux * sizeM + nx * sizeM, czM - uz * sizeM + nz * sizeM)
            const [rx2, ry2] = toPx(cxM - ux * sizeM - nx * sizeM, czM - uz * sizeM - nz * sizeM)
            chevrons.push(
              <polyline
                key={`ch-${k}-${c}`}
                points={`${lx2},${ly2} ${tx},${ty} ${rx2},${ry2}`}
                fill="none"
                stroke="#8DC63F"
                strokeWidth={1.2}
                strokeOpacity={0.28}
                strokeLinecap="round"
                strokeLinejoin="round"
              />,
            )
          }
          return (
            <g key={`chev-${k}`} pointerEvents="none">
              {chevrons}
            </g>
          )
        })}

        {/* Junction / decision dots. Decision points (from of 2+ sections) are red. */}
        {path.map((p, i) => {
          if (!junctions.has(i)) return null
          const [px, py] = toPx(p[0], p[1])
          const isDecision = decisions.has(i)
          return (
            <circle
              key={`jn-${i}`}
              cx={px}
              cy={py}
              r={isDecision ? 5 : 3.5}
              fill={isDecision ? '#e0563f' : '#9fb2a8'}
              stroke="#08120d"
              strokeWidth={1}
              pointerEvents="none"
            />
          )
        })}

        {/* Draggable waypoint handles (snapped). While drawing, clicking re-anchors via onDrawAt. */}
        {(selected || drawing) &&
          path.map((p, i) => {
            const [px, py] = toPx(p[0], p[1])
            return (
              <circle
                key={`wp-${i}`}
                cx={px}
                cy={py}
                r={6}
                className="plan2d-waypoint"
                fill="#8DC63F"
                stroke="#08120d"
                strokeWidth={1.5}
                onPointerDown={(e) => {
                  if (e.button !== 0) return
                  e.stopPropagation()
                  if (drawing) {
                    // Re-anchor / branch: delegate to the shared draw handler at this exact point.
                    // drawSectionAt snaps to this existing junction within SNAP_M, so it re-anchors
                    // (or draws a section to it) rather than creating a near-duplicate vertex.
                    onDrawAtPoint(eq.id, p[0], p[1])
                    return
                  }
                  onSelect(eq.id)
                  onWaypointStart(eq.id, i, path)
                  ;(e.target as Element).setPointerCapture?.(e.pointerId)
                }}
              />
            )
          })}

        <text x={lx + 8} y={ly - 8} className="plan2d-label" pointerEvents="none">
          {eq.code}
        </text>
      </g>
    )
  }

  // A real conveyor with a path renders just the path overlay.
  if (isPathConveyor) return renderPath()

  // Plain box: a rotated rect centred at (posXM, posZM).
  const [cx, cy] = toPx(eq.posXM, eq.posZM)
  const w = eq.lengthM * pxPerM
  const h = eq.widthM * pxPerM
  const box = (
    <g
      transform={`translate(${cx} ${cy}) rotate(${eq.rotationDeg})`}
      style={{ cursor: drawing ? 'default' : 'move' }}
      onPointerDown={(e) => {
        if (e.button !== 0 || drawing) return
        e.stopPropagation()
        onSelect(eq.id)
        onMoveStart(eq.id, e.clientX, e.clientY)
        ;(e.target as Element).setPointerCapture?.(e.pointerId)
      }}
    >
      <rect
        x={-w / 2}
        y={-h / 2}
        width={w}
        height={h}
        rx={2}
        fill={color}
        fillOpacity={0.85}
        stroke={selected ? '#8DC63F' : '#08120d'}
        strokeWidth={selected ? 2.5 : 1}
      />
      {/* A short tick showing the "forward" (length+) direction. */}
      <line x1={0} y1={0} x2={w / 2} y2={0} stroke="#08120d" strokeOpacity={0.5} strokeWidth={1} />
      <text x={0} y={-h / 2 - 4} className="plan2d-label" textAnchor="middle" transform={`rotate(${-eq.rotationDeg})`}>
        {eq.code}
      </text>
    </g>
  )

  // An ASRS stub host draws its IN/OUT conveyor stubs (path/sections) on top of its rack box.
  if (stubHost && path.length >= 2) {
    return (
      <g>
        {box}
        {renderPath()}
      </g>
    )
  }
  return box
}

// A function-point marker rendered top-down on its conveyor: a small dot nudged to its side
// (LEFT/RIGHT by ±widthM/2 perpendicular to travel), with a short type label. DIVERT_* render red.
// Clicking it selects the owning conveyor; dragging it re-positions it onto a conveyor centreline
// (snap). Delete is available in the Properties panel list.
function PlanFunctionPoint({
  fp,
  eq,
  pxPerM,
  toPx,
  dragging,
  onSelect,
  onDragStart,
}: {
  fp: AutomationFunctionPoint
  eq: AutomationEquipment
  pxPerM: number
  toPx: (xM: number, zM: number) => [number, number]
  // True while THIS marker is being dragged (we dim it; the live snap preview shows the target).
  dragging: boolean
  onSelect: () => void
  onDragStart: (e: React.PointerEvent<SVGGElement>) => void
}) {
  const at = pointAlong(eq, fp.offsetM)
  // Right-hand normal to travel (dx,dz) is (dz,-dx); left is its negation. Nudge by half-width.
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
  // The point can carry a combination of functions; derive the glyph/colour/label from the set.
  const fns = fpFunctions(fp.functionType)
  const isDivert = fns.some((t) => t === 'DIVERT_LEFT' || t === 'DIVERT_RIGHT')
  // Ports/merge (IN/OUT/FEED) render as a DIAMOND — distinct from a round SCAN or a square DIVERT.
  const isPort = fns.some((t) => t === 'INDUCT' || t === 'DISCHARGE' || t === 'INFEED')
  const color = functionColorForSet(fns)
  const short = functionShortForSet(fns) || fp.functionType
  const [mx, my] = toPx(at.x + ox, at.z + oz)
  // A short stem from the centreline to the side-nudged marker, so the point reads as attached.
  const [sx, sy] = toPx(at.x, at.z)
  return (
    <g
      style={{ cursor: 'grab', opacity: dragging ? 0.35 : 1 }}
      onPointerDown={(e) => {
        if (e.button !== 0) return
        e.stopPropagation()
        onSelect()
        onDragStart(e)
      }}
    >
      {(ox !== 0 || oz !== 0) && (
        <line x1={sx} y1={sy} x2={mx} y2={my} stroke={color} strokeWidth={1} strokeOpacity={0.6} />
      )}
      {/* Comfortable invisible hit-target around the marker so it's easy to grab and drag. */}
      <circle cx={mx} cy={my} r={11} fill="transparent" />
      <rect
        x={mx - 4.5}
        y={my - 4.5}
        width={9}
        height={9}
        transform={isPort ? `rotate(45 ${mx} ${my})` : undefined}
        rx={isDivert || isPort ? 0 : 4.5}
        fill={color}
        stroke="#08120d"
        strokeWidth={1.2}
        pointerEvents="none"
      />
      <text x={mx + 7} y={my + 3.5} className="plan2d-fplabel" style={{ fill: color }}>
        {short}
      </text>
    </g>
  )
}

// The PENDING (not-yet-placed) function point: a floating, clearly-"unplaced" draggable marker
// rendered at its current world staging position. A dashed ring + "unplaced" tint distinguish it
// from the solid placed markers; dragging it (then dropping on a conveyor) materialises the point.
function PendingMarker({
  pending,
  toPx,
  onDragStart,
}: {
  pending: { functionType: string; world: { x: number; z: number } }
  toPx: (xM: number, zM: number) => [number, number]
  onDragStart: (e: React.PointerEvent<SVGGElement>) => void
}) {
  const isDivert = pending.functionType === 'DIVERT_LEFT' || pending.functionType === 'DIVERT_RIGHT'
  const isPort =
    pending.functionType === 'INDUCT' ||
    pending.functionType === 'DISCHARGE' ||
    pending.functionType === 'INFEED'
  const color = isDivert ? '#e0563f' : functionColor(pending.functionType)
  const short = FUNCTION_SHORT[pending.functionType] ?? pending.functionType
  const [mx, my] = toPx(pending.world.x, pending.world.z)
  return (
    <g
      style={{ cursor: 'grabbing' }}
      onPointerDown={(e) => {
        if (e.button !== 0) return
        e.stopPropagation()
        onDragStart(e)
      }}
    >
      {/* Dashed "unplaced" halo. */}
      <circle cx={mx} cy={my} r={11} className="plan2d-pendingring" />
      <rect
        x={mx - 5.5}
        y={my - 5.5}
        width={11}
        height={11}
        transform={isPort ? `rotate(45 ${mx} ${my})` : undefined}
        rx={isDivert || isPort ? 0 : 5.5}
        fill={color}
        fillOpacity={0.85}
        stroke="#fff"
        strokeWidth={1.4}
      />
      <text x={mx + 9} y={my + 3.5} className="plan2d-fplabel" style={{ fill: color }}>
        {short} · unplaced
      </text>
    </g>
  )
}

function PlanStyles() {
  return (
    <style>{`
      .plan2d { height: 82vh; display: flex; flex-direction: column; position: relative; }
      .plan2d-bar {
        display: flex; align-items: center; gap: .6rem; flex-wrap: wrap;
        padding: .45rem .6rem; border-bottom: 1px solid var(--glass-border);
        background: rgba(8, 30, 22, .35);
      }
      .plan2d-bar-label { font-size: .72rem; text-transform: uppercase; letter-spacing: .08em; color: var(--text-dim); }
      .plan2d-bar-spacer { flex: 1; }
      .plan2d-seg { display: inline-flex; border: 1px solid var(--glass-border); border-radius: 8px; overflow: hidden; }
      .plan2d-segbtn {
        padding: .25rem .55rem; background: none; border: none; cursor: pointer;
        color: var(--text-dim); font-family: var(--font-body); font-size: .78rem;
        border-right: 1px solid var(--glass-border);
      }
      .plan2d-segbtn:last-child { border-right: none; }
      .plan2d-segbtn.is-active { background: rgba(141, 198, 63, .18); color: var(--herbal-lime); }
      .plan2d-check { display: inline-flex; align-items: center; gap: .35rem; font-size: .8rem; color: var(--text); margin: 0; }
      .plan2d-rotate { display: inline-flex; align-items: center; gap: .35rem; }
      .plan2d-iconbtn {
        padding: .25rem .5rem; border-radius: 8px; cursor: pointer; font-size: .76rem;
        background: var(--glass-bg); color: var(--text); border: 1px solid var(--glass-border);
        font-family: var(--font-body);
      }
      .plan2d-iconbtn:hover { border-color: var(--glass-border-bright); }
      .plan2d-fpbar {
        display: flex; align-items: center; gap: .55rem; flex-wrap: wrap;
        padding: .4rem .6rem; border-bottom: 1px solid var(--glass-border);
        background: rgba(8, 30, 22, .22);
      }
      .plan2d-fppalette { display: inline-flex; flex-wrap: wrap; gap: .3rem; }
      .plan2d-fpbtn {
        padding: .22rem .5rem; border-radius: 7px; cursor: pointer; font-size: .72rem;
        background: var(--glass-bg); color: var(--text-dim);
        border: 1px solid var(--glass-border); font-family: var(--font-mono); white-space: nowrap;
      }
      .plan2d-fpbtn:hover { border-color: var(--glass-border-bright); color: var(--text); }
      .plan2d-fpbtn:disabled { opacity: .4; cursor: not-allowed; }
      .plan2d-fpbtn.is-divert { color: #e0907f; }
      .plan2d-fpbtn.is-port { color: #8DC63F; }
      .plan2d-fpbtn.is-infeed { color: #5ee0c8; }
      .plan2d-fphint { font-size: .72rem; color: var(--herbal-lime); letter-spacing: .02em; }
      .plan2d-fplabel {
        font-family: var(--font-mono); font-size: 10px;
        paint-order: stroke; stroke: #08120d; stroke-width: 3px; stroke-linejoin: round;
      }
      .plan2d-snapring { fill: none; stroke: #8DC63F; stroke-width: 2; stroke-opacity: .9; }
      .plan2d-pendingring {
        fill: rgba(141, 198, 63, .08); stroke: #8DC63F; stroke-width: 1.5;
        stroke-dasharray: 3 3; stroke-opacity: .9;
      }
      .plan2d-svg { flex: 1; width: 100%; display: block; background: #081e16; touch-action: none; cursor: grab; }
      .plan2d-gridline { stroke: #173027; stroke-width: 1; }
      .plan2d-gridline.is-strong { stroke: #214a3a; stroke-width: 1.2; }
      .plan2d-origin { stroke: #3a6; stroke-width: 1.5; }
      .plan2d-label {
        font-family: var(--font-mono); font-size: 11px; fill: var(--text);
        paint-order: stroke; stroke: #08120d; stroke-width: 3px; stroke-linejoin: round;
      }
      .plan2d-waypoint { cursor: grab; }
      .plan2d-waypoint:hover { fill: #aee06a; }
      .plan2d-hint {
        position: absolute; left: 12px; bottom: 12px; z-index: 2; pointer-events: none;
        padding: .3rem .6rem; border-radius: 8px; font-size: .72rem; letter-spacing: .02em;
        color: rgba(214, 228, 220, .85); background: rgba(8, 30, 22, .6);
        border: 1px solid var(--glass-border);
      }
    `}</style>
  )
}
