// Read-only live "digital twin" 3D scene for the Hardware visualisation page.
//
// It renders the SAME scene as the Automation topology editor — reusing the editor's polished
// presentational meshes (EquipmentMesh + FunctionPointMarker from AutomationTopology3D), in a fully
// read-only mode (selected=false so no drag gizmos / waypoint handles render, all edit handlers are
// no-ops) — and overlays the live picture derived in `twin.ts`:
//   - a floating activity pip above equipment that is running (amber, pulsing) or faulted (red)
//   - a lime floor ring under the selected equipment
//   - handling-unit totes that glide between the equipment their successive tasks ran on
// The base look (two-tone conveyor bodies, racked ASRS, sorter/workstation boxes, SCAN/QRY/DIV
// markers, labels) is therefore identical to the editor — not a second, cruder renderer.

import { useEffect, useMemo, useRef, type MutableRefObject } from 'react'
import { Canvas, useFrame, type ThreeEvent } from '@react-three/fiber'
import { Grid, Html, OrbitControls, RoundedBox } from '@react-three/drei'
import * as THREE from 'three'
import type { AutomationEquipment, AutomationLevel, AutomationTopology } from '../topology/automationApi'
import {
  EquipmentMesh as TopoEquipmentMesh,
  FunctionPointMarker as TopoFunctionPointMarker,
  STUB_HEIGHT_M,
  category as topoCategory,
  colorFor as topoColorFor,
  effectiveSections,
  isConveyor as topoIsConveyor,
} from '../topology/AutomationTopology3D'
import type { Equipment } from '../masterdata/api'
import {
  SCAN_BELT_Y,
  SCAN_SPEED_MPS,
  anchorPoint,
  placementGeom,
  type PlacementGeom,
  type StoredTote,
  type ToteView,
  type TwinSnapshot,
} from './twin'
import {
  RENDER_DELAY_MS,
  buildBeltLocator,
  buildPathResolver,
  newSmoothState,
  sampleTimeline,
  smoothStep,
  type SmoothState,
  type ToteTimeline,
  type XZ,
} from './motion'
import { JAM_HOLD_MS, deriveConveyorJamIds } from './conveyorState'

const BG = '#081e16'
const LIME = '#8DC63F'
const AMBER = '#f4b860'
const RED = '#ff6b5e'
const BLUE = '#4FC3F7'
const GREY = '#7d8a82'
// Functional-conveyor skin green: calm and desaturated so a healthy floor stays readable.
// HardwareTwinScreen's legend duplicates the hex (the screen must NOT import this lazy chunk).
const CONVEYOR_GREEN = '#3fae6e'

const TOTE_SIZE = 0.35
const BELT_LIFT = 0.18

const noop = () => {}

export interface HardwareTwin3DProps {
  topology: AutomationTopology
  /** Master-data equipment library (id → Equipment) — drives the editor classification (conveyor vs
   *  rack vs sorter) so the scene looks exactly like the editor. */
  lib: Map<string, Equipment>
  snapshot: TwinSnapshot
  /** Per-tote interpolation buffers (motion.ts) — when provided, tote motion replays the buffered
   *  scan timeline RENDER_DELAY_MS behind real time, interpolated along the conveyor geometry. */
  timelines?: Map<string, ToteTimeline>
  /** HUs at rest in storage, rendered at their cell position inside the ASRS rack (ADR-0009 §5). */
  storedTotes?: StoredTote[]
  activeLevelId?: string | null
  selectedPlacedId?: string | null
  selectedHuId?: string | null
  /** Show equipment code + function-point (SCAN/QRY/DIV) text labels. Defaults true. */
  showLabels?: boolean
  onSelectEquipment?: (placedId: string | null) => void
  onSelectTote?: (huId: string | null) => void
}

export default function HardwareTwin3D({
  topology,
  lib,
  snapshot,
  timelines,
  storedTotes = [],
  activeLevelId = null,
  selectedPlacedId = null,
  selectedHuId = null,
  showLabels = true,
  onSelectEquipment,
  onSelectTote,
}: HardwareTwin3DProps): JSX.Element {
  return (
    <Canvas camera={{ position: [12, 12, 12], fov: 50 }} style={{ width: '100%', height: '100%' }} shadows>
      <color attach="background" args={[BG]} />
      <ambientLight intensity={0.6} />
      <directionalLight position={[10, 18, 8]} intensity={0.9} castShadow />
      <Grid
        args={[60, 60]}
        cellSize={1}
        cellThickness={0.6}
        cellColor="#1d3b30"
        sectionSize={5}
        sectionThickness={1}
        sectionColor="#2f7d57"
        fadeDistance={70}
        fadeStrength={1}
        infiniteGrid
        position={[0, 0, 0]}
      />

      {/* Invisible ground: a click clears both selections. */}
      <mesh
        rotation={[-Math.PI / 2, 0, 0]}
        position={[0, -0.01, 0]}
        onPointerDown={(e: ThreeEvent<PointerEvent>) => {
          onSelectEquipment?.(null)
          onSelectTote?.(null)
          e.stopPropagation()
        }}
      >
        <planeGeometry args={[400, 400]} />
        <meshBasicMaterial transparent opacity={0} depthWrite={false} />
      </mesh>

      <SceneContent
        topology={topology}
        lib={lib}
        snapshot={snapshot}
        timelines={timelines}
        activeLevelId={activeLevelId}
        selectedPlacedId={selectedPlacedId}
        selectedHuId={selectedHuId}
        showLabels={showLabels}
        onSelectEquipment={onSelectEquipment}
        onSelectTote={onSelectTote}
      />

      <StoredTotes totes={storedTotes} selectedHuId={selectedHuId} onSelectTote={onSelectTote} />

      <OrbitControls
        makeDefault
        enableDamping
        enablePan
        enableZoom
        enableRotate
        screenSpacePanning
        minDistance={2}
        maxDistance={150}
        maxPolarAngle={Math.PI / 2.05}
        mouseButtons={{ LEFT: THREE.MOUSE.ROTATE, MIDDLE: THREE.MOUSE.DOLLY, RIGHT: THREE.MOUSE.PAN }}
      />
    </Canvas>
  )
}

interface SceneContentProps {
  topology: AutomationTopology
  lib: Map<string, Equipment>
  snapshot: TwinSnapshot
  timelines?: Map<string, ToteTimeline>
  activeLevelId: string | null
  selectedPlacedId: string | null
  selectedHuId: string | null
  showLabels: boolean
  onSelectEquipment?: (placedId: string | null) => void
  onSelectTote?: (huId: string | null) => void
}

function SceneContent({
  topology,
  lib,
  snapshot,
  timelines,
  activeLevelId,
  selectedPlacedId,
  selectedHuId,
  showLabels,
  onSelectEquipment,
  onSelectTote,
}: SceneContentProps): JSX.Element {
  const levels: AutomationLevel[] = topology.levels

  // Equipment to render (optionally filtered to a single level).
  const items = useMemo(
    () => topology.equipment.filter((eq) => !activeLevelId || eq.levelId === activeLevelId),
    [topology.equipment, activeLevelId],
  )
  const visibleIds = useMemo(() => new Set(items.map((e) => e.id)), [items])

  // Geometry per placed id (for activity overlays + resolving tote anchor points).
  const geomById = useMemo(() => {
    const m = new Map<string, PlacementGeom>()
    for (const eq of items) m.set(eq.id, placementGeom(eq, levels))
    return m
  }, [items, levels])

  // Function points on visible equipment (id → equipment for the marker).
  const byId = useMemo(() => new Map(topology.equipment.map((e) => [e.id, e])), [topology.equipment])

  // Scene conveyor height — ASRS IN/OUT stubs render flush with the conveyors (mirrors the editor).
  const stubHeightM = useMemo(() => {
    let h = 0
    for (const eq of items) {
      if (topoIsConveyor(eq, lib)) h = Math.max(h, eq.heightM || 0)
    }
    return h > 0 ? h : undefined
  }, [items, lib])

  // --- Live conveyor state (green / orange / red skins) -----------------------------------------
  // Conveyor placements (the editor's own classification) and a belt locator over their polylines:
  // a tote's "current conveyor" is the belt its latest scan position projects onto (motion.ts).
  const conveyorGeoms = useMemo(
    () =>
      items
        .filter((eq) => topoIsConveyor(eq, lib))
        .map((eq) => geomById.get(eq.id))
        .filter((g): g is PlacementGeom => !!g),
    [items, lib, geomById],
  )
  const locateBelt = useMemo(() => buildBeltLocator(conveyorGeoms), [conveyorGeoms])

  // Jam hysteresis: each raw jam reading extends the belt's orange window by JAM_HOLD_MS, so a
  // single slow hop / borderline density poll cannot strobe the skin. The Map is read per-frame
  // by each skin (cheap), re-armed once per snapshot (poll) here.
  const jamUntilRef = useRef<Map<string, number>>(new Map())
  useEffect(() => {
    const nowMs = Date.now()
    const jams = deriveConveyorJamIds({
      totes: snapshot.totes,
      timelines,
      conveyorGeoms,
      locate: locateBelt,
      nowMs,
    })
    const jamUntil = jamUntilRef.current
    for (const id of jams) jamUntil.set(id, nowMs + JAM_HOLD_MS)
    for (const [id, until] of jamUntil) if (until <= nowMs && !jams.has(id)) jamUntil.delete(id)
  }, [snapshot, timelines, conveyorGeoms, locateBelt])

  return (
    <group>
      {/* Base scene — the editor's exact meshes, read-only (selected=false → no gizmo/handles). */}
      {items.map((eq) => (
        <TopoEquipmentMesh
          key={eq.id}
          eq={eq}
          conveyor={topoIsConveyor(eq, lib)}
          cat={topoCategory(eq, lib)}
          color={topoColorFor(eq, lib)}
          selected={false}
          connectMode={false}
          connectSource={false}
          drawing={false}
          activeFromIdx={null}
          onSelect={() => onSelectEquipment?.(eq.id)}
          onMove={noop}
          onMoveWaypoint={noop}
          onAnchorWaypoint={noop}
          onHandleDragChange={noop}
          showLabels={showLabels}
          stubHeightM={stubHeightM}
        />
      ))}

      {/* Function-point markers (SCAN / DIVERT / QRY …) for visible equipment. The Labels toggle
          hides these "hats" entirely (cone/diamond AND text), not just the text, so the scene is
          truly clean when labels are off. */}
      {showLabels &&
        topology.functionPoints.map((fp) => {
          const eq = byId.get(fp.placedId)
          if (!eq || !visibleIds.has(eq.id)) return null
          const stubHost = !topoIsConveyor(eq, lib) && !!(eq.path && eq.path.length >= 2)
          return (
            <TopoFunctionPointMarker
              key={fp.id}
              fp={fp}
              eq={eq}
              topM={stubHost ? stubHeightM ?? STUB_HEIGHT_M : undefined}
              onSelect={() => onSelectEquipment?.(eq.id)}
            />
          )
        })}

      {/* Live overlays. Conveyors wear their state as a belt skin (green functional / orange jam /
          red fault); the floating orb stays only for non-conveyor equipment (ASRS, stations,
          sorters without a skinnable belt body). Selection rings render for everything. */}
      {items.map((eq) => {
        const geom = geomById.get(eq.id)
        if (!geom) return null
        const state = snapshot.activityByPlacedId[eq.id]?.state ?? 'idle'
        const conveyor = topoIsConveyor(eq, lib)
        return (
          <group key={`ov-${eq.id}`}>
            {conveyor && (
              <ConveyorStateSkin eq={eq} faulted={state === 'faulted'} jamUntilRef={jamUntilRef} />
            )}
            <Overlay
              geom={geom}
              state={state}
              showPip={!conveyor}
              selected={eq.id === selectedPlacedId}
            />
          </group>
        )
      })}

      <Totes
        totes={snapshot.totes}
        timelines={timelines}
        geomById={geomById}
        selectedHuId={selectedHuId}
        onSelectTote={onSelectTote}
      />
    </group>
  )
}

// ----------------------------------------------------------------------------------------------------
// Activity overlay — a pulsing pip above running/faulted equipment + a lime ring under the selected one
// ----------------------------------------------------------------------------------------------------

function Overlay({
  geom,
  state,
  selected,
  showPip = true,
}: {
  geom: PlacementGeom
  state: 'idle' | 'running' | 'faulted'
  selected: boolean
  /** Conveyors pass false: their state shows as a belt skin, not a floating orb. */
  showPip?: boolean
}): JSX.Element | null {
  const pipRef = useRef<THREE.Mesh>(null)
  const matRef = useRef<THREE.MeshStandardMaterial>(null)
  const anchor = anchorPoint(geom) // belt-height representative point
  const top = geom.baseY + (geom.size[1] || 0.5) + 0.6

  useFrame(({ clock }) => {
    const mat = matRef.current
    const pip = pipRef.current
    if (!mat || !pip) return
    if (state === 'running') {
      const p = 0.5 + 0.5 * Math.sin(clock.elapsedTime * 4)
      mat.emissiveIntensity = 0.4 + 0.6 * p
      pip.scale.setScalar(0.85 + 0.25 * p)
    } else {
      mat.emissiveIntensity = 0.6
      pip.scale.setScalar(1)
    }
  })

  if ((state === 'idle' || !showPip) && !selected) return null

  return (
    <group>
      {state !== 'idle' && showPip && (
        <mesh ref={pipRef} position={[anchor[0], top, anchor[2]]}>
          <sphereGeometry args={[0.22, 16, 16]} />
          <meshStandardMaterial
            ref={matRef}
            color={state === 'running' ? AMBER : RED}
            emissive={state === 'running' ? AMBER : RED}
            emissiveIntensity={0.6}
            toneMapped={false}
          />
        </mesh>
      )}
      {selected && (
        <mesh rotation={[-Math.PI / 2, 0, 0]} position={[geom.center[0], geom.baseY + 0.02, geom.center[2]]}>
          <ringGeometry args={[Math.max(0.6, geom.size[0] * 0.55), Math.max(0.8, geom.size[0] * 0.62), 48]} />
          <meshBasicMaterial color={LIME} transparent opacity={0.85} side={THREE.DoubleSide} />
        </mesh>
      )}
    </group>
  )
}

// ----------------------------------------------------------------------------------------------------
// Conveyor state skin: the belt wears its live state (green functional / orange jam / red fault)
// ----------------------------------------------------------------------------------------------------
// Same technique as the reporting traffic heatmap (ReportScene3D's ConveyorHeatSkin): a thin
// emissive overlay riding just above the belt surface: one skin per directed section for path
// conveyors (effectiveSections, the editor's own segment geometry), a single body skin for
// box-mode conveyors. State changes EASE (a brief colour lerp in useFrame) instead of snapping;
// the jam signal itself is hysteresis-held via jamUntilRef (see conveyorState.ts JAM_HOLD_MS).

const DEG = Math.PI / 180

/** Visual targets per state: green is a subtle tint so a healthy floor stays readable; orange and
 *  red are deliberately louder. Colours match the orb palette (AMBER/RED) and the screen legend. */
const SKIN_TARGETS: Record<'ok' | 'jam' | 'fault', { color: THREE.Color; emissive: number; opacity: number }> = {
  ok: { color: new THREE.Color(CONVEYOR_GREEN), emissive: 0.18, opacity: 0.35 },
  jam: { color: new THREE.Color(AMBER), emissive: 0.65, opacity: 0.85 },
  fault: { color: new THREE.Color(RED), emissive: 0.8, opacity: 0.9 },
}

/** Colour-lerp time constant (seconds): a state change settles in well under a second. */
const SKIN_LERP_TAU_S = 0.35

/** Pointer rays pass through the skin so clicking a belt still selects the conveyor body. */
const noRaycast = () => null

function ConveyorStateSkin({
  eq,
  faulted,
  jamUntilRef,
}: {
  eq: AutomationEquipment
  faulted: boolean
  jamUntilRef: MutableRefObject<Map<string, number>>
}): JSX.Element | null {
  // ONE shared material instance across all section meshes (a JSX element would clone per mesh),
  // so the per-frame lerp animates every section of the belt in lockstep.
  const mat = useMemo(() => {
    const m = new THREE.MeshStandardMaterial({
      color: new THREE.Color(CONVEYOR_GREEN),
      emissive: new THREE.Color(CONVEYOR_GREEN),
      emissiveIntensity: SKIN_TARGETS.ok.emissive,
      transparent: true,
      opacity: SKIN_TARGETS.ok.opacity,
      toneMapped: false,
    })
    return m
  }, [])
  useEffect(() => () => mat.dispose(), [mat])

  useFrame((_state, delta) => {
    // Priority: red (fault) over orange (jam, hysteresis-held) over green.
    const jammed = Date.now() < (jamUntilRef.current.get(eq.id) ?? 0)
    const tgt = SKIN_TARGETS[faulted ? 'fault' : jammed ? 'jam' : 'ok']
    const k = 1 - Math.exp(-Math.max(0, delta) / SKIN_LERP_TAU_S)
    mat.color.lerp(tgt.color, k)
    mat.emissive.lerp(tgt.color, k)
    mat.emissiveIntensity += (tgt.emissive - mat.emissiveIntensity) * k
    mat.opacity += (tgt.opacity - mat.opacity) * k
  })

  const skinH = 0.07
  const path = (eq.path ?? []) as number[][]

  // Per-section skins along the drawn path (the editor's exact segment geometry).
  const segs = useMemo(() => {
    if (path.length < 2) return []
    return effectiveSections(eq)
      .map(([i, j]) => {
        const a = path[i]
        const b = path[j]
        if (!a || !b) return null
        const dx = b[0] - a[0]
        const dz = b[1] - a[1]
        const len = Math.hypot(dx, dz)
        if (len < 1e-4) return null
        return { mx: (a[0] + b[0]) / 2, mz: (a[1] + b[1]) / 2, len, yaw: Math.atan2(dz, dx) }
      })
      .filter((s): s is NonNullable<typeof s> => s !== null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [eq])

  if (path.length >= 2) {
    const y = eq.posYM + (eq.heightM || 0.5) + skinH / 2 + 0.01
    const w = Math.max(0.2, (eq.widthM || 0.5) * 0.9)
    return (
      <group>
        {segs.map((s, k) => (
          <mesh key={k} position={[s.mx, y, s.mz]} rotation={[0, -s.yaw, 0]} material={mat} raycast={noRaycast}>
            <boxGeometry args={[s.len, skinH, w]} />
          </mesh>
        ))}
      </group>
    )
  }

  // Box-mode conveyor: a single skin over the body, same transform as the editor's box group.
  const y = eq.posYM + (eq.heightM || 0.5) + skinH / 2 + 0.01
  return (
    <mesh position={[eq.posXM, y, eq.posZM]} rotation={[0, eq.rotationDeg * DEG, 0]} material={mat} raycast={noRaycast}>
      <boxGeometry args={[Math.max(0.2, eq.lengthM || 1), skinH, Math.max(0.2, (eq.widthM || 0.5) * 0.9)]} />
    </mesh>
  )
}

// ----------------------------------------------------------------------------------------------------
// Totes — one child owns all per-tote animation refs (no hooks-in-a-loop)
// ----------------------------------------------------------------------------------------------------

function toteColor(state: ToteView['state']): string {
  switch (state) {
    case 'in-transit':
      return LIME
    case 'recirculating':
      return AMBER
    case 'queued':
      return BLUE
    default:
      return GREY
  }
}

interface TotesProps {
  totes: ToteView[]
  timelines?: Map<string, ToteTimeline>
  geomById: Map<string, PlacementGeom>
  selectedHuId: string | null
  onSelectTote?: (huId: string | null) => void
}

// Smooth, honest tote motion (motion.ts): the per-frame target is the buffered scan timeline
// sampled at `now - RENDER_DELAY_MS` and interpolated along the conveyor polylines — only ever
// between KNOWN points, so polling cadence never shows (no freeze-then-jump). Buffer underruns
// dead-reckon gently toward the known next node; revised data blends in over ~0.5 s; genuine
// discontinuities (rack store/retrieve, induction) still teleport. The animation itself is pure
// rAF/delta-time (useFrame) and fully independent of poll timing. Totes without a timeline fall
// back to the single latest scan, then to their (strict) anchor.
function Totes({ totes, timelines, geomById, selectedHuId, onSelectTote }: TotesProps): JSX.Element {
  const groupRefs = useRef<Map<string, THREE.Group>>(new Map())
  const smoothers = useRef<Map<string, SmoothState>>(new Map())

  // Conveyor-geometry path resolver (memoised per endpoint pair inside).
  const pathBetween = useMemo(() => buildPathResolver(Array.from(geomById.values())), [geomById])

  const resolved = useMemo(() => {
    const out: Array<{ tote: ToteView; anchor: THREE.Vector3 | null }> = []
    for (const tote of totes) {
      let anchor: THREE.Vector3 | null = null
      if (tote.anchorPlacedId) {
        const geom = geomById.get(tote.anchorPlacedId)
        if (geom) {
          const a = anchorPoint(geom)
          anchor = new THREE.Vector3(a[0], a[1] + BELT_LIFT, a[2])
        }
      }
      if (!anchor && !tote.scan && !timelines?.get(tote.huId)?.points.length) continue // nothing observed
      out.push({ tote, anchor })
    }
    return out
  }, [totes, geomById, timelines])

  const liveIds = useMemo(() => new Set(resolved.map((r) => r.tote.huId)), [resolved])

  useFrame((_state, delta) => {
    const groups = groupRefs.current
    const sm = smoothers.current
    for (const id of Array.from(sm.keys())) {
      if (!liveIds.has(id)) {
        sm.delete(id)
        groups.delete(id)
      }
    }
    // Render BEHIND real time so the next scan is (almost) always already buffered — see motion.ts.
    const renderT = Date.now() - RENDER_DELAY_MS
    for (const { tote, anchor } of resolved) {
      const g = groups.get(tote.huId)
      if (!g) continue

      // Where the observed state puts the tote THIS frame. Queued totes sit at their station
      // anchor (the real induction queue is the truth there) — the timeline only drives motion.
      let xz: XZ | null = null
      let y = SCAN_BELT_Y + BELT_LIFT
      if (tote.state === 'queued' && anchor) {
        xz = [anchor.x, anchor.z]
        y = anchor.y
      }
      const tl = timelines?.get(tote.huId)
      if (!xz && tl && tl.points.length) {
        xz = sampleTimeline(tl, renderT, pathBetween)
      }
      if (!xz && tote.scan) {
        // Legacy single-scan fallback (no buffered timeline available).
        const { fromXZ, toXZ, tsMs } = tote.scan
        if (toXZ) {
          const dx = toXZ[0] - fromXZ[0]
          const dz = toXZ[1] - fromXZ[1]
          const dist = Math.hypot(dx, dz)
          const travelled = Math.max(0, (renderT - tsMs) / 1000) * SCAN_SPEED_MPS
          const f = dist > 0 ? Math.min(1, travelled / dist) : 1
          xz = [fromXZ[0] + dx * f, fromXZ[1] + dz * f]
        } else {
          xz = [fromXZ[0], fromXZ[1]]
        }
      }
      if (!xz && anchor) {
        xz = [anchor.x, anchor.z]
        y = anchor.y
      }
      if (!xz) continue

      let st = sm.get(tote.huId)
      if (!st) {
        st = newSmoothState()
        sm.set(tote.huId, st)
        smoothStep(st, xz, delta)
        g.position.set(xz[0], y, xz[1])
        continue
      }
      const out = smoothStep(st, xz, delta)
      // Y rarely changes (belt ↔ station top); ease it independently of the planar smoothing.
      const ny = g.position.y + (y - g.position.y) * Math.min(1, delta * 8)
      g.position.set(out[0], ny, out[1])
    }
  })

  return (
    <group>
      {resolved.map(({ tote }) => (
        <ToteMesh
          key={tote.huId}
          tote={tote}
          selected={tote.huId === selectedHuId}
          registerGroup={(g) => {
            if (g) groupRefs.current.set(tote.huId, g)
          }}
          onSelect={() => onSelectTote?.(tote.huId)}
        />
      ))}
    </group>
  )
}

interface ToteMeshProps {
  tote: ToteView
  selected: boolean
  registerGroup: (g: THREE.Group | null) => void
  onSelect: () => void
}

// Real warehouse-tote proportions (600×400 mm footprint, ~320 mm tall) — an open-top container:
// a base plate, four slightly raised walls and a brighter top rim, rather than an abstract cube.
const TOTE_L = 0.6
const TOTE_W = 0.4
const TOTE_H = 0.32
const TOTE_WALL = 0.04

function ToteMesh({ tote, selected, registerGroup, onSelect }: ToteMeshProps): JSX.Element {
  const color = toteColor(tote.state)
  const s = selected ? 1.25 : 1
  const l = TOTE_L * s
  const w = TOTE_W * s
  const h = TOTE_H * s
  const wall = TOTE_WALL * s
  const mat = (
    <meshStandardMaterial
      color={color}
      metalness={0.15}
      roughness={0.55}
      emissive={selected ? color : '#000000'}
      emissiveIntensity={selected ? 0.5 : 0}
    />
  )
  return (
    <group
      ref={registerGroup}
      onPointerDown={(e: ThreeEvent<PointerEvent>) => {
        e.stopPropagation()
        onSelect()
      }}
    >
      {/* Base plate */}
      <mesh position={[0, wall / 2, 0]} castShadow>
        <boxGeometry args={[l, wall, w]} />
        {mat}
      </mesh>
      {/* Long walls (slight outward lean for the classic stacking-tote taper) */}
      <mesh position={[0, h / 2, w / 2 - wall / 2]} rotation={[0.07, 0, 0]} castShadow>
        <boxGeometry args={[l, h, wall]} />
        {mat}
      </mesh>
      <mesh position={[0, h / 2, -(w / 2 - wall / 2)]} rotation={[-0.07, 0, 0]} castShadow>
        <boxGeometry args={[l, h, wall]} />
        {mat}
      </mesh>
      {/* Short walls */}
      <mesh position={[l / 2 - wall / 2, h / 2, 0]} rotation={[0, 0, -0.07]} castShadow>
        <boxGeometry args={[wall, h, w]} />
        {mat}
      </mesh>
      <mesh position={[-(l / 2 - wall / 2), h / 2, 0]} rotation={[0, 0, 0.07]} castShadow>
        <boxGeometry args={[wall, h, w]} />
        {mat}
      </mesh>
      {/* Brighter top rim — reads as the tote lip and makes the state colour pop from above. */}
      <mesh position={[0, h, 0]}>
        <boxGeometry args={[l + 0.04, 0.03, w + 0.04]} />
        <meshStandardMaterial
          color={color}
          metalness={0.1}
          roughness={0.4}
          emissive={color}
          emissiveIntensity={selected ? 0.8 : 0.35}
        />
      </mesh>
      {selected && tote.huCode && (
        <Html position={[0, h + 0.25, 0]} center distanceFactor={18} occlude={false}>
          <div
            style={{
              padding: '0.15rem 0.45rem',
              borderRadius: 4,
              fontSize: 11,
              whiteSpace: 'nowrap',
              background: 'rgba(8, 30, 22, 0.85)',
              color: '#d6e4dc',
              border: `1px solid ${LIME}`,
            }}
          >
            {tote.huCode}
          </div>
        </Html>
      )}
    </group>
  )
}

// ----------------------------------------------------------------------------------------------------
// Stored totes — the rack's contents (ADR-0009 §5): one small tote per registry HU at its cell
// position inside the ASRS rack. Pure representation of the HU registry; clicking selects the HU.
// ----------------------------------------------------------------------------------------------------

const STORED_COLOR = '#8fd3ff' // bright ice-blue — must remain visible inside the dark rack frame

function StoredTotes({
  totes,
  selectedHuId,
  onSelectTote,
}: {
  totes: StoredTote[]
  selectedHuId: string | null
  onSelectTote?: (huId: string | null) => void
}): JSX.Element {
  return (
    <group>
      {totes.map((t) => {
        const selected = t.huId === selectedHuId
        return (
          <group
            key={t.huId}
            position={t.pos}
            onPointerDown={(e: ThreeEvent<PointerEvent>) => {
              e.stopPropagation()
              onSelectTote?.(t.huId)
            }}
          >
            <mesh castShadow>
              <boxGeometry args={[0.7, 0.36, 0.5]} />
              <meshStandardMaterial
                color={STORED_COLOR}
                metalness={0.1}
                roughness={0.55}
                emissive={STORED_COLOR}
                emissiveIntensity={selected ? 0.9 : 0.3}
              />
            </mesh>
            {selected && (
              <Html position={[0, 0.4, 0]} center distanceFactor={18} occlude={false}>
                <div
                  style={{
                    padding: '0.15rem 0.45rem',
                    borderRadius: 4,
                    fontSize: 11,
                    whiteSpace: 'nowrap',
                    background: 'rgba(8, 30, 22, 0.85)',
                    color: '#d6e4dc',
                    border: `1px solid ${LIME}`,
                  }}
                >
                  {t.huCode}
                </div>
              </Html>
            )}
          </group>
        )
      })}
    </group>
  )
}
