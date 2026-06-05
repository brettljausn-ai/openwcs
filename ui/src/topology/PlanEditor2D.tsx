// A 2D top-down plan editor for the automation topology. It shares the SAME model/state as the 3D
// view: it reads the active level's placed equipment and the library map, and mutates them through
// the very same callbacks the 3D editor uses (`onPatch` = patchEquipment, `onDrawAt` =
// drawSectionAt, `onSelect` = setSelectedId). So an edit made here shows up in 3D and vice-versa.
//
// Coordinate convention matches 3D: world X → right, world Z → down, rotationDeg = yaw about Y.
// We map metres → pixels with a scale and a pan offset, and snap moves/draws to a metre grid.

import { useCallback, useMemo, useRef, useState } from 'react'
import type { Equipment } from '../masterdata/api'
import type { AutomationEquipment } from './automationApi'
import { colorFor, decisionPoints, effectiveSections, isConveyor, junctionPoints } from './AutomationTopology3D'

const DEG = Math.PI / 180

const GRID_OPTIONS = [0.25, 0.5, 1] as const

interface PlanEditor2DProps {
  // The placed equipment on the ACTIVE level (the same array the 3D canvas renders).
  items: AutomationEquipment[]
  // Library map (master-data equipment by id) — drives type colours / conveyor detection.
  libById: Map<string, Equipment>
  selectedId: string | null
  // Conveyor "Draw sections" mode is on (mirrors the 3D drawPath state).
  drawing: boolean
  onSelect: (id: string | null) => void
  // Partial update of a placed item — same as patchEquipment in the parent.
  onPatch: (id: string, patch: Partial<AutomationEquipment>) => void
  // A snapped world (x,z) click while drawing — same as drawSectionAt in the parent.
  onDrawAt: (id: string, x: number, z: number) => void
}

// Snap a metre value to the nearest multiple of `step` (no-op when snapping is off).
function snap(v: number, step: number, on: boolean): number {
  if (!on || step <= 0) return +v.toFixed(3)
  return +(Math.round(v / step) * step).toFixed(3)
}

export default function PlanEditor2D({
  items,
  libById,
  selectedId,
  drawing,
  onSelect,
  onPatch,
  onDrawAt,
}: PlanEditor2DProps) {
  // View transform: pixels-per-metre and a pan offset (in pixels) of the world origin.
  const [pxPerM, setPxPerM] = useState(40)
  const [pan, setPan] = useState({ x: 0, y: 0 })
  const [gridStep, setGridStep] = useState<number>(0.5)
  const [snapOn, setSnapOn] = useState(true)

  const svgRef = useRef<SVGSVGElement | null>(null)
  // The current free-form drag (equipment move, waypoint move, or background pan).
  const drag = useRef<
    | { kind: 'pan'; startX: number; startY: number; panX: number; panY: number }
    | { kind: 'move'; id: string }
    | { kind: 'waypoint'; id: string; index: number; path: number[][] }
    | null
  >(null)

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
  const onWheel = useCallback(
    (e: React.WheelEvent<SVGSVGElement>) => {
      e.preventDefault()
      const rect = svgRef.current?.getBoundingClientRect()
      const cx = e.clientX - (rect?.left ?? 0)
      const cy = e.clientY - (rect?.top ?? 0)
      const factor = e.deltaY < 0 ? 1.12 : 1 / 1.12
      const next = Math.min(240, Math.max(6, pxPerM * factor))
      if (next === pxPerM) return
      // Keep the world point under the cursor fixed: solve for the new pan.
      const worldX = (cx - pan.x) / pxPerM
      const worldZ = (cy - pan.y) / pxPerM
      setPan({ x: cx - worldX * next, y: cy - worldZ * next })
      setPxPerM(next)
    },
    [pan, pxPerM],
  )

  // ---- background interactions ---------------------------------------------
  const onBackgroundPointerDown = useCallback(
    (e: React.PointerEvent<SVGRectElement>) => {
      if (e.button !== 0) return
      if (drawing && selectedId) {
        // Draw mode: a click on the plane draws a (snapped) section on the selected conveyor.
        const w = clientToWorld(e.clientX, e.clientY)
        onDrawAt(selectedId, snap(w.x, gridStep, snapOn), snap(w.z, gridStep, snapOn))
        return
      }
      // Otherwise begin a pan; an empty click (no drag) deselects on pointer-up.
      drag.current = { kind: 'pan', startX: e.clientX, startY: e.clientY, panX: pan.x, panY: pan.y }
      ;(e.target as Element).setPointerCapture?.(e.pointerId)
    },
    [drawing, selectedId, clientToWorld, gridStep, snapOn, onDrawAt, pan],
  )

  // A single pointer-move handler at the SVG level drives pan / move / waypoint drags.
  const onPointerMove = useCallback(
    (e: React.PointerEvent<SVGSVGElement>) => {
      const d = drag.current
      if (!d) return
      if (d.kind === 'pan') {
        setPan({ x: d.panX + (e.clientX - d.startX), y: d.panY + (e.clientY - d.startY) })
        return
      }
      const w = clientToWorld(e.clientX, e.clientY)
      const sx = snap(w.x, gridStep, snapOn)
      const sz = snap(w.z, gridStep, snapOn)
      if (d.kind === 'move') {
        onPatch(d.id, { posXM: sx, posZM: sz })
      } else if (d.kind === 'waypoint') {
        const next = d.path.map((p, i) => (i === d.index ? [sx, sz] : [p[0], p[1]]))
        onPatch(d.id, { path: next })
      }
    },
    [clientToWorld, gridStep, snapOn, onPatch],
  )

  const endDrag = useCallback(() => {
    drag.current = null
  }, [])

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
  const selected = useMemo(() => items.find((it) => it.id === selectedId) ?? null, [items, selectedId])
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

      <svg
        ref={svgRef}
        className="plan2d-svg"
        onWheel={onWheel}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerLeave={endDrag}
      >
        <defs>
          <marker
            id="plan2d-arrow"
            viewBox="0 0 10 10"
            refX="7"
            refY="5"
            markerWidth="2.2"
            markerHeight="2.2"
            orient="auto-start-reverse"
          >
            <path d="M 0 0 L 10 5 L 0 10 z" fill="#8DC63F" />
          </marker>
        </defs>

        {/* Background — pan / deselect / draw target. */}
        <rect
          x={0}
          y={0}
          width="100%"
          height="100%"
          fill="transparent"
          style={{ cursor: drawing ? 'crosshair' : 'grab' }}
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
            color={colorFor(eq, libById)}
            selected={eq.id === selectedId}
            drawing={drawing && eq.id === selectedId}
            pxPerM={pxPerM}
            toPx={toPx}
            onSelect={onSelect}
            onMoveStart={(id) => {
              drag.current = { kind: 'move', id }
            }}
            onWaypointStart={(id, index, path) => {
              drag.current = { kind: 'waypoint', id, index, path }
            }}
            onDrawAtPoint={onDrawAt}
          />
        ))}
      </svg>

      <div className="plan2d-hint">
        {drawing
          ? 'Draw mode: click the grid to add sections; click a point to branch from it'
          : 'Drag an item to move (snapped) · drag empty space to pan · scroll to zoom'}
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
  color,
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
  color: string
  selected: boolean
  drawing: boolean
  pxPerM: number
  toPx: (xM: number, zM: number) => [number, number]
  onSelect: (id: string | null) => void
  onMoveStart: (id: string) => void
  onWaypointStart: (id: string, index: number, path: number[][]) => void
  onDrawAtPoint: (id: string, x: number, z: number) => void
}) {
  const path = Array.isArray(eq.path) ? eq.path : []
  const isPathConveyor = conveyor && path.length >= 2

  if (isPathConveyor) {
    const sections = effectiveSections(eq)
    const decisions = decisionPoints(sections)
    const junctions = junctionPoints(sections)
    const [lx, ly] = toPx(path[0][0], path[0][1])
    return (
      <g>
        {/* Directed sections with travel arrows. */}
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
              stroke={color}
              strokeWidth={Math.max(2, eq.widthM * pxPerM * 0.5)}
              strokeOpacity={selected ? 0.9 : 0.65}
              strokeLinecap="round"
              markerEnd="url(#plan2d-arrow)"
              style={{ cursor: drawing ? 'crosshair' : 'pointer' }}
              onPointerDown={(e) => {
                if (drawing) return
                e.stopPropagation()
                onSelect(eq.id)
              }}
            />
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

  // Plain box: a rotated rect centred at (posXM, posZM).
  const [cx, cy] = toPx(eq.posXM, eq.posZM)
  const w = eq.lengthM * pxPerM
  const h = eq.widthM * pxPerM
  return (
    <g
      transform={`translate(${cx} ${cy}) rotate(${eq.rotationDeg})`}
      style={{ cursor: drawing ? 'default' : 'move' }}
      onPointerDown={(e) => {
        if (e.button !== 0 || drawing) return
        e.stopPropagation()
        onSelect(eq.id)
        onMoveStart(eq.id)
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
