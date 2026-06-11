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
import { anchorPoint, placementGeom, type PlacementGeom, type ToteView, type TwinSnapshot } from './twin'

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

      {/* Function-point markers (SCAN / DIVERT / QRY …) for visible equipment. */}
      {topology.functionPoints.map((fp) => {
        const eq = byId.get(fp.placedId)
        if (!eq || !visibleIds.has(eq.id)) return null
        return (
          <TopoFunctionPointMarker
            key={fp.id}
            fp={fp}
            eq={eq}
            showLabels={showLabels}
            onSelect={() => onSelectEquipment?.(eq.id)}
          />
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
  const top = geom.elevationM + (geom.size[1] || 0.5) + 0.6

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
        <mesh rotation={[-Math.PI / 2, 0, 0]} position={[geom.center[0], geom.elevationM + 0.02, geom.center[2]]}>
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

function Totes({ totes, geomById, selectedHuId, onSelectTote }: TotesProps): JSX.Element {
  const groupRefs = useRef<Map<string, THREE.Group>>(new Map())
  const posRefs = useRef<Map<string, THREE.Vector3>>(new Map())

  const resolved = useMemo(() => {
    const out: Array<{ tote: ToteView; target: THREE.Vector3; start: THREE.Vector3 }> = []
    for (const tote of totes) {
      if (!tote.anchorPlacedId) continue
      const geom = geomById.get(tote.anchorPlacedId)
      if (!geom) continue
      const a = anchorPoint(geom)
      const target = new THREE.Vector3(a[0], a[1] + BELT_LIFT, a[2])
      let start = target.clone()
      if (tote.prevPlacedId) {
        const prevGeom = geomById.get(tote.prevPlacedId)
        if (prevGeom) {
          const p = anchorPoint(prevGeom)
          start = new THREE.Vector3(p[0], p[1] + BELT_LIFT, p[2])
        }
      }
      out.push({ tote, target, start })
    }
    return out
  }, [totes, geomById])

  const liveIds = useMemo(() => new Set(resolved.map((r) => r.tote.huId)), [resolved])

  useFrame((stateObj, delta) => {
    const pos = posRefs.current
    const groups = groupRefs.current
    for (const id of Array.from(pos.keys())) {
      if (!liveIds.has(id)) {
        pos.delete(id)
        groups.delete(id)
      }
    }
    const k = 1 - Math.pow(1 - 0.08, delta * 60)
    const t = stateObj.clock.elapsedTime
    for (const { tote, target } of resolved) {
      const g = groups.get(tote.huId)
      if (!g) continue
      let cur = pos.get(tote.huId)
      if (!cur) {
        cur = target.clone()
        pos.set(tote.huId, cur)
      }
      cur.lerp(target, k)
      if (tote.state === 'recirculating') {
        const r = 0.6
        g.position.set(cur.x + Math.cos(t * 2) * r, cur.y, cur.z + Math.sin(t * 2) * r)
      } else {
        g.position.copy(cur)
      }
    }
  })

  return (
    <group>
      {resolved.map(({ tote, start }) => (
        <ToteMesh
          key={tote.huId}
          tote={tote}
          start={start}
          selected={tote.huId === selectedHuId}
          registerGroup={(g) => {
            const groups = groupRefs.current
            if (g) {
              groups.set(tote.huId, g)
              if (!posRefs.current.has(tote.huId)) {
                posRefs.current.set(tote.huId, start.clone())
                g.position.copy(start)
              }
            }
          }}
          onSelect={() => onSelectTote?.(tote.huId)}
        />
      ))}
    </group>
  )
}

interface ToteMeshProps {
  tote: ToteView
  start: THREE.Vector3
  selected: boolean
  registerGroup: (g: THREE.Group | null) => void
  onSelect: () => void
}

function ToteMesh({ tote, start, selected, registerGroup, onSelect }: ToteMeshProps): JSX.Element {
  const color = toteColor(tote.state)
  const size = selected ? TOTE_SIZE * 1.3 : TOTE_SIZE
  return (
    <group
      ref={registerGroup}
      position={start}
      onPointerDown={(e: ThreeEvent<PointerEvent>) => {
        e.stopPropagation()
        onSelect()
      }}
    >
      <RoundedBox args={[size, size, size]} radius={0.06} smoothness={3} castShadow>
        <meshStandardMaterial
          color={color}
          metalness={0.2}
          roughness={0.5}
          emissive={selected ? color : '#000000'}
          emissiveIntensity={selected ? 0.6 : 0}
        />
      </RoundedBox>
      {selected && tote.huCode && (
        <Html position={[0, size + 0.2, 0]} center distanceFactor={18} occlude={false}>
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
