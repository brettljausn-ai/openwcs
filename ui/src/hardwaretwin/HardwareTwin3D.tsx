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

import { type MutableRefObject, useCallback, useEffect, useMemo, useRef, useState } from 'react'
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
  chordPath,
  makePath,
  newSmoothState,
  pointAtLen,
  projectPointOnPath,
  sampleTimeline,
  smoothStep,
  type PathSample,
  type PathBetween,
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
/** The conveyor BODY wears its state colour directly (no overlay): green ok / orange jam / red fault. */
const BELT_BODY_COLORS: Record<'ok' | 'jam' | 'fault', string> = {
  ok: CONVEYOR_GREEN,
  jam: AMBER,
  fault: RED,
}

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
  clockOffsetMsRef?: MutableRefObject<number | null>
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
  clockOffsetMsRef,
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
        clockOffsetMsRef={clockOffsetMsRef}
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
  clockOffsetMsRef?: MutableRefObject<number | null>
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
  clockOffsetMsRef,
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

  // --- Queue lineup geometry -----------------------------------------------------------------------
  // Walkable PathSample per conveyor placement (queue slots are arc-length stations on these).
  const beltPaths = useMemo(() => {
    const m = new Map<string, PathSample>()
    for (const g of conveyorGeoms) {
      if (g.worldPath && g.worldPath.length >= 2) m.set(g.id, makePath(g.worldPath))
    }
    return m
  }, [conveyorGeoms])

  // Where each NON-conveyor placement's queue forms: the point on its linked conveyor where the two
  // meet (the editor's explicit node links). When a station has SEVERAL conveyor links (PP1 links
  // an infeed AND an outfeed), inbound-ness is decided by GEOMETRY, never by which way round the
  // user happened to draw the connection: the belt that DELIVERS to the station has directed
  // sections ARRIVING at the link node and the node sits deep into the belt's run, while an
  // outfeed link sits at the belt's start with only departing sections. (Trusting the record's
  // direction put the whole queue on the wrong belt, clamped to its start — totes overlapped
  // there instead of queueing on the approach belt.)
  const queueHeads = useMemo(() => {
    const m = new Map<string, { beltId: string; s: number; score: number }>()
    const consider = (
      stationPlacedId: string,
      beltId: string,
      pathIdx: number | null | undefined,
      explicitInbound: boolean,
    ) => {
      const path = beltPaths.get(beltId)
      if (!path || path.total <= 0) return
      let s: number
      if (pathIdx != null && pathIdx >= 0 && pathIdx < path.cum.length) {
        s = path.cum[pathIdx]
      } else {
        const g = geomById.get(stationPlacedId)
        if (!g) return
        s = projectPointOnPath(path, [g.center[0], g.center[2]]).s
      }
      // Any directed section arriving at the link node? (No sections ⇒ sequential path, so every
      // node but the first is arrived-at. No path index ⇒ "not at the very start" approximation.)
      const eq = byId.get(beltId)
      const secs = eq?.sections && eq.sections.length ? eq.sections : null
      const arrives =
        pathIdx != null ? (secs ? secs.some(([, to]) => to === pathIdx) : pathIdx > 0) : s > 0.5
      const score = (arrives ? 2 : 0) + s / path.total + (explicitInbound ? 0.5 : 0)
      const prev = m.get(stationPlacedId)
      if (!prev || score > prev.score) m.set(stationPlacedId, { beltId, s, score })
    }
    for (const c of topology.connections) {
      const fromConv = beltPaths.has(c.fromPlacedId)
      const toConv = beltPaths.has(c.toPlacedId)
      if (fromConv && !toConv) consider(c.toPlacedId, c.fromPlacedId, c.fromPathIndex, true)
      else if (!fromConv && toConv) consider(c.fromPlacedId, c.toPlacedId, c.toPathIndex, false)
    }
    return m
  }, [topology.connections, beltPaths, geomById, byId])

  // Jam hysteresis: each raw jam reading extends the belt's orange window by JAM_HOLD_MS, so a
  // single slow hop / borderline density poll cannot strobe the skin. The Map is read per-frame
  // by each skin (cheap), re-armed once per snapshot (poll) here.
  const jamUntilRef = useRef<Map<string, number>>(new Map())
  // Belt body state per conveyor ('ok' | 'jam' | 'fault'): drives the conveyor's OWN body colour
  // (the body is tinted green/orange/red directly; no translucent overlay). Recomputed on every
  // snapshot and on a 1 s tick so the jam hysteresis expiry shows without a poll.
  const [beltStates, setBeltStates] = useState<Map<string, 'ok' | 'jam' | 'fault'>>(new Map())
  const recomputeBeltStates = useCallback(() => {
    const nowMs = Date.now()
    const next = new Map<string, 'ok' | 'jam' | 'fault'>()
    for (const eq of topology.equipment) {
      if (!topoIsConveyor(eq, lib)) continue
      const faulted = snapshot.activityByPlacedId[eq.id]?.state === 'faulted'
      const jammed = nowMs < (jamUntilRef.current.get(eq.id) ?? 0)
      next.set(eq.id, faulted ? 'fault' : jammed ? 'jam' : 'ok')
    }
    setBeltStates((prev: Map<string, 'ok' | 'jam' | 'fault'>) => {
      if (prev.size === next.size && [...next].every(([k, v]) => prev.get(k) === v)) return prev
      return next
    })
  }, [topology, lib, snapshot])
  useEffect(() => {
    recomputeBeltStates()
    const t = window.setInterval(recomputeBeltStates, 1000)
    return () => window.clearInterval(t)
  }, [recomputeBeltStates])
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
    recomputeBeltStates()
  }, [snapshot, timelines, conveyorGeoms, locateBelt, recomputeBeltStates])

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
          /* The conveyor BODY wears the live state colour. This must go through bodyTint — the
             editor's ConveyorPath ignores `color` for real conveyors (it always drew the default
             light blue, which is why the state colours never showed on the floor). */
          bodyTint={
            topoIsConveyor(eq, lib) ? BELT_BODY_COLORS[beltStates.get(eq.id) ?? 'ok'] : undefined
          }
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
        clockOffsetMsRef={clockOffsetMsRef}
        geomById={geomById}
        beltPaths={beltPaths}
        queueHeads={queueHeads}
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
  clockOffsetMsRef?: MutableRefObject<number | null>
  geomById: Map<string, PlacementGeom>
  /** Walkable polyline per conveyor placement (queue slots live on these). */
  beltPaths: Map<string, PathSample>
  /** Per non-conveyor placement: where its induction queue forms on the linked inbound conveyor. */
  queueHeads: Map<string, { beltId: string; s: number }>
  selectedHuId: string | null
  onSelectTote?: (huId: string | null) => void
}

/** Spacing between queue slots along the belt (tote 0.6 m + a visible gap). */
const QUEUE_SPACING_M = 0.8
/** A queued tote is parked at the last trail node within this distance of a belt (the link point). */
const QUEUE_BELT_TOL_M = 0.6
/** Anti-stack: minimum nose-to-tail gap between two MOVING totes sharing a belt run. */
const TOTE_MIN_SEP_M = 0.8
/** How many relaxation passes the anti-stack separation runs (resolves short chains of totes). */
const SEP_PASSES = 2
/** Two totes count as "on the same run" (so they should not overlap) when their headings align. */
const SAME_DIR_DOT = 0.7
/** Look-back window used to estimate a tote's heading from its timeline. */
const HEADING_LOOKBACK_MS = 250

// Smooth, honest tote motion (motion.ts): the per-frame target is the buffered scan timeline
// sampled at `now - RENDER_DELAY_MS` and interpolated along the conveyor polylines — only ever
// between KNOWN points, so polling cadence never shows (no freeze-then-jump). Buffer underruns
// dead-reckon gently toward the known next node; revised data blends in over ~0.5 s; genuine
// discontinuities (rack store/retrieve, induction) still teleport. The animation itself is pure
// rAF/delta-time (useFrame) and fully independent of poll timing. Totes without a timeline fall
// back to the single latest scan, then to their (strict) anchor.
function Totes({
  totes,
  timelines,
  clockOffsetMsRef,
  geomById,
  beltPaths,
  queueHeads,
  selectedHuId,
  onSelectTote,
}: TotesProps): JSX.Element {
  // The delayed render clock (data-anchored, monotonic): survives across frames.
  const renderClockRef = useRef<number | null>(null)
  const groupRefs = useRef<Map<string, THREE.Group>>(new Map())
  const smoothers = useRef<Map<string, SmoothState>>(new Map())

  // Tote-motion path resolver: a STRAIGHT segment between consecutive waypoints. The backend
  // "visu master" emits the tote's actual traversed-node sequence (graph-adjacent), so the chord
  // between two consecutive waypoints IS the belt section. We deliberately do NOT project onto the
  // drawn belts anymore: that projection was ambiguous at diverts (two branches meet at one point),
  // which flung totes to a conveyor's start and snapped them back every frame. No projection, no
  // jump. (Belt-state jam derivation still uses buildBeltLocator over conveyorGeoms — separate.)
  const pathBetween = useMemo<PathBetween>(() => chordPath, [])

  // Is a world point ON a conveyor belt? (within ~0.6 m of some belt's polyline). Used to park a
  // queued tote at the last node of its trail that sits on a conveyor — the link point — rather
  // than on the station node it scanned last (which is inside the workstation box).
  const onAnyBelt = useCallback(
    (p: XZ): boolean => {
      for (const bp of beltPaths.values()) {
        if (projectPointOnPath(bp, p).d <= QUEUE_BELT_TOL_M) return true
      }
      return false
    },
    [beltPaths],
  )

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
    // Render BEHIND DATA time so the next scan is (almost) always already buffered. Anchoring to
    // the data's own timestamps (server clock) instead of the wall clock makes the delay immune to
    // server-vs-client skew (observed live: a skewed demo-box clock pushed the sample point outside
    // the buffered window, so totes dead-reckoned to phantom positions and bounced back each poll).
    // Monotonic: never steps backwards; catches up at most 2x real time when the offset grows.
    const offset = clockOffsetMsRef?.current ?? 0
    const raw = Date.now() + offset - RENDER_DELAY_MS
    const prevT = renderClockRef.current
    const renderT = prevT == null ? raw : Math.max(prevT, Math.min(raw, prevT + delta * 2000))
    renderClockRef.current = renderT
    // Phase 1 — resolve each tote's target position (+ heading, for the anti-stack pass).
    type Frame = { tote: ToteView; g: THREE.Group; xz: XZ; y: number; heading: XZ | null }
    const frame: Frame[] = []
    for (const { tote, anchor } of resolved) {
      const g = groups.get(tote.huId)
      if (!g) continue

      let xz: XZ | null = null
      let y = SCAN_BELT_Y + BELT_LIFT
      let heading: XZ | null = null
      const tl = timelines?.get(tote.huId)
      const hasTimeline = !!(tl && tl.points.length)
      if (tote.state === 'queued') {
        // A queued tote waits ON the inbound conveyor, parked at the CONVEYOR LINK POINT, not on the
        // workstation. Its last scanned node is the station's own node (e.g. PP1#0, which sits in the
        // middle of the 1.2 m workstation box), so parking there put it on the station. Walk back
        // through the buffered waypoints to the last one that lies on a conveyor belt (the link
        // point, e.g. BIN_CONVEYOR-1#17 just outside the box) and park there. The anti-stack pass
        // then queues the ones behind it nose-to-tail upstream on that same belt.
        if (hasTimeline) {
          const pts = tl.points
          let pi = -1
          for (let i = pts.length - 1; i >= 0; i--) {
            if (onAnyBelt(pts[i].xz)) {
              pi = i
              break
            }
          }
          if (pi >= 0) {
            xz = pts[pi].xz
            if (pi >= 1) {
              const a = pts[pi - 1].xz
              const dx = xz[0] - a[0]
              const dz = xz[1] - a[1]
              const m = Math.hypot(dx, dz)
              if (m > 1e-3) heading = [dx / m, dz / m]
            }
          }
        }
        if (!xz) {
          // No arrival path on a belt (page just loaded, or no conveyor under the trail): fall back
          // to the editor's inbound link point, then the station anchor.
          const qi = tote.queueIndex ?? 0
          const head = tote.anchorPlacedId ? queueHeads.get(tote.anchorPlacedId) : undefined
          const beltPath = head ? beltPaths.get(head.beltId) : undefined
          if (head && beltPath) {
            xz = pointAtLen(beltPath, Math.max(0, head.s - qi * QUEUE_SPACING_M))
          } else if (anchor) {
            xz = [anchor.x, anchor.z]
            y = anchor.y
          }
        }
      }
      // In-transit totes follow their motion timeline (queued ones were parked above).
      if (!xz && hasTimeline) {
        xz = sampleTimeline(tl, renderT, pathBetween)
        if (xz) {
          const back = sampleTimeline(tl, renderT - HEADING_LOOKBACK_MS, pathBetween)
          if (back) {
            const dx = xz[0] - back[0]
            const dz = xz[1] - back[1]
            const m = Math.hypot(dx, dz)
            if (m > 1e-3) heading = [dx / m, dz / m]
          }
        }
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
      frame.push({ tote, g, xz, y, heading })
    }

    // Phase 2 — anti-stack: totes are solid, so when two of them heading the same way overlap, push
    // the trailing one BACK along its own heading until they sit nose-to-tail. This covers two cases
    // with one pass: (a) two totes retrieved together share a near-identical path and would render as
    // one; (b) totes QUEUED at a workplace all arrive at the same link point and must line up behind
    // each other ON the belt they came in on. It works in each tote's own travel direction (never a
    // belt re-projection), so it can't snap a tote to a crossing belt's start at a divert (where
    // headings diverge and the pass disengages).
    const movers = frame.filter((f) => f.heading)
    for (let pass = 0; pass < SEP_PASSES; pass++) {
      for (let i = 0; i < movers.length; i++) {
        for (let k = i + 1; k < movers.length; k++) {
          const a = movers[i]
          const b = movers[k]
          const dx = b.xz[0] - a.xz[0]
          const dz = b.xz[1] - a.xz[1]
          const d = Math.hypot(dx, dz)
          if (d >= TOTE_MIN_SEP_M || d < 1e-4) continue
          const ah = a.heading as XZ
          const bh = b.heading as XZ
          if (ah[0] * bh[0] + ah[1] * bh[1] < SAME_DIR_DOT) continue // not on the same run
          const h = ah // ~equal to bh on a shared belt
          const behind = a.xz[0] * h[0] + a.xz[1] * h[1] < b.xz[0] * h[0] + b.xz[1] * h[1] ? a : b
          const push = TOTE_MIN_SEP_M - d
          behind.xz = [behind.xz[0] - h[0] * push, behind.xz[1] - h[1] * push]
        }
      }
    }

    // Phase 3 — smooth toward the (possibly separated) target and apply.
    for (const { tote, g, xz, y } of frame) {
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
