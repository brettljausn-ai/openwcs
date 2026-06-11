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

import { useMemo, useRef } from 'react'
import { Canvas, useFrame, type ThreeEvent } from '@react-three/fiber'
import { Grid, Html, OrbitControls, RoundedBox } from '@react-three/drei'
import * as THREE from 'three'
import type { AutomationEquipment, AutomationLevel, AutomationTopology } from '../topology/automationApi'
import {
  EquipmentMesh as TopoEquipmentMesh,
  FunctionPointMarker as TopoFunctionPointMarker,
  category as topoCategory,
  colorFor as topoColorFor,
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

const BG = '#081e16'
const LIME = '#8DC63F'
const AMBER = '#f4b860'
const RED = '#ff6b5e'
const BLUE = '#4FC3F7'
const GREY = '#7d8a82'

const TOTE_SIZE = 0.35
const BELT_LIFT = 0.18

const noop = () => {}

export interface HardwareTwin3DProps {
  topology: AutomationTopology
  /** Master-data equipment library (id → Equipment) — drives the editor classification (conveyor vs
   *  rack vs sorter) so the scene looks exactly like the editor. */
  lib: Map<string, Equipment>
  snapshot: TwinSnapshot
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
        />
      ))}

      {/* Function-point markers (SCAN / DIVERT / QRY …) for visible equipment. The Labels toggle
          hides these "hats" entirely (cone/diamond AND text), not just the text, so the scene is
          truly clean when labels are off. */}
      {showLabels &&
        topology.functionPoints.map((fp) => {
          const eq = byId.get(fp.placedId)
          if (!eq || !visibleIds.has(eq.id)) return null
          return (
            <TopoFunctionPointMarker key={fp.id} fp={fp} eq={eq} onSelect={() => onSelectEquipment?.(eq.id)} />
          )
        })}

      {/* Live overlays: activity pips + selection ring. */}
      {items.map((eq) => {
        const geom = geomById.get(eq.id)
        if (!geom) return null
        const state = snapshot.activityByPlacedId[eq.id]?.state ?? 'idle'
        return (
          <Overlay
            key={`ov-${eq.id}`}
            geom={geom}
            state={state}
            selected={eq.id === selectedPlacedId}
          />
        )
      })}

      <Totes totes={snapshot.totes} geomById={geomById} selectedHuId={selectedHuId} onSelectTote={onSelectTote} />
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
}: {
  geom: PlacementGeom
  state: 'idle' | 'running' | 'faulted'
  selected: boolean
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

  if (state === 'idle' && !selected) return null

  return (
    <group>
      {state !== 'idle' && (
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
  geomById: Map<string, PlacementGeom>
  selectedHuId: string | null
  onSelectTote?: (huId: string | null) => void
}

// ADR-0008 replay: a tote's live position is the OBSERVED scan trail — the last scanned node,
// interpolated toward the answered next node at the real conveyor speed (0.5 m/s) from the scan
// timestamp. Per-frame wall-clock interpolation gives continuous motion between polls without
// inventing a path. Totes without scans sit statically at their (strict) anchor.
function Totes({ totes, geomById, selectedHuId, onSelectTote }: TotesProps): JSX.Element {
  const groupRefs = useRef<Map<string, THREE.Group>>(new Map())
  const posRefs = useRef<Map<string, THREE.Vector3>>(new Map())

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
      if (!anchor && !tote.scan) continue // nothing observed to show
      out.push({ tote, anchor })
    }
    return out
  }, [totes, geomById])

  const liveIds = useMemo(() => new Set(resolved.map((r) => r.tote.huId)), [resolved])

  useFrame((_state, delta) => {
    const pos = posRefs.current
    const groups = groupRefs.current
    for (const id of Array.from(pos.keys())) {
      if (!liveIds.has(id)) {
        pos.delete(id)
        groups.delete(id)
      }
    }
    const nowMs = Date.now()
    for (const { tote, anchor } of resolved) {
      const g = groups.get(tote.huId)
      if (!g) continue

      // Where the observed state puts the tote THIS frame.
      let target: THREE.Vector3 | null = null
      if (tote.scan) {
        const { fromXZ, toXZ, tsMs } = tote.scan
        if (toXZ) {
          const dx = toXZ[0] - fromXZ[0]
          const dz = toXZ[1] - fromXZ[1]
          const dist = Math.hypot(dx, dz)
          const travelled = Math.max(0, (nowMs - tsMs) / 1000) * SCAN_SPEED_MPS
          const f = dist > 0 ? Math.min(1, travelled / dist) : 1
          target = new THREE.Vector3(fromXZ[0] + dx * f, SCAN_BELT_Y + BELT_LIFT, fromXZ[1] + dz * f)
        } else {
          target = new THREE.Vector3(fromXZ[0], SCAN_BELT_Y + BELT_LIFT, fromXZ[1])
        }
      } else if (anchor) {
        target = anchor
      }
      if (!target) continue

      let cur = pos.get(tote.huId)
      if (!cur) {
        cur = target.clone()
        pos.set(tote.huId, cur)
        g.position.copy(cur)
        continue
      }
      // Smooth toward the observed position (absorbs the jump when a new scan row lands).
      cur.lerp(target, Math.min(1, delta * 6))
      g.position.copy(cur)
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

const STORED_COLOR = '#6fa3c7' // muted steel-blue — visible against the mid-blue rack, calmer than live totes

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
              <boxGeometry args={[0.5, 0.26, 0.34]} />
              <meshStandardMaterial
                color={STORED_COLOR}
                metalness={0.1}
                roughness={0.6}
                emissive={selected ? STORED_COLOR : '#000000'}
                emissiveIntensity={selected ? 0.7 : 0}
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
