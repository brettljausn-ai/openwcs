// Read-only live "digital twin" 3D scene for the Hardware visualisation page.
//
// Renders the static automation topology (conveyors, racks, sorters, workstations) and overlays the
// live picture derived in `twin.ts`: equipment is coloured by runtime activity (idle / running /
// faulted) and handling-unit totes are drawn as small markers that glide between the equipment their
// successive tasks ran on. Everything here is presentation-only — no editing, no drag/pivot controls.
//
// Rendering style (Canvas / camera / grid / lighting / conveyor + box look) mirrors
// AutomationTopology3D.tsx, keeping the dark palette (background #081e16, lime #8DC63F accent).

import { useMemo, useRef } from 'react'
import { Canvas, useFrame, type ThreeEvent } from '@react-three/fiber'
import { Grid, Html, OrbitControls, RoundedBox } from '@react-three/drei'
import * as THREE from 'three'
import type {
  AutomationEquipment,
  AutomationLevel,
  AutomationTopology,
} from '../topology/automationApi'
import {
  anchorPoint,
  placementGeom,
  type EquipmentActivity,
  type PlacementGeom,
  type ToteView,
  type TwinSnapshot,
} from './twin'

// ----------------------------------------------------------------------------------------------------
// Palette
// ----------------------------------------------------------------------------------------------------

const BG = '#081e16'
const LIME = '#8DC63F'
const AMBER = '#f4b860'
const RED = '#ff6b5e'
const BLUE = '#4FC3F7'
const GREY = '#7d8a82'

// Idle base body colour per category — muted, distinct, matching AutomationTopology3D's families.
const CATEGORY_BASE: Record<string, string> = {
  conveyor: '#4f8a8b', // teal — transport
  asrs: '#1E88E5', // mid-blue — automated storage rack
  'manual-storage': '#C75B12', // burnt-orange — manual rack
  sorter: '#E0A33A', // amber — sortation
  workstation: '#3ea66a', // green — GTP workstation
  other: '#6b7a85', // slate — fallback
}

function baseColorFor(category: string): string {
  return CATEGORY_BASE[category] ?? CATEGORY_BASE.other
}

const TOTE_SIZE = 0.35
const BELT_LIFT = 0.18 // ride a little above the belt/anchor height

// ----------------------------------------------------------------------------------------------------
// Top-level scene
// ----------------------------------------------------------------------------------------------------

export interface HardwareTwin3DProps {
  topology: AutomationTopology
  snapshot: TwinSnapshot
  activeLevelId?: string | null // when set, only render equipment on this level
  selectedPlacedId?: string | null
  selectedHuId?: string | null
  onSelectEquipment?: (placedId: string | null) => void
  onSelectTote?: (huId: string | null) => void
}

export default function HardwareTwin3D({
  topology,
  snapshot,
  activeLevelId = null,
  selectedPlacedId = null,
  selectedHuId = null,
  onSelectEquipment,
  onSelectTote,
}: HardwareTwin3DProps): JSX.Element {
  return (
    <Canvas camera={{ position: [14, 14, 14], fov: 50 }} style={{ width: '100%', height: '100%' }}>
      <color attach="background" args={[BG]} />
      <ambientLight intensity={0.6} />
      <directionalLight position={[10, 18, 8]} intensity={0.9} />
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

      {/* Invisible ground plane: a click here clears both selections. */}
      <mesh
        rotation={[-Math.PI / 2, 0, 0]}
        position={[0, -0.01, 0]}
        onPointerDown={(e: ThreeEvent<PointerEvent>) => {
          // Only treat as a background click when nothing in front handled it first.
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
        snapshot={snapshot}
        activeLevelId={activeLevelId}
        selectedPlacedId={selectedPlacedId}
        selectedHuId={selectedHuId}
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
        panSpeed={1.2}
        zoomSpeed={1.1}
        minDistance={2}
        maxDistance={150}
        maxPolarAngle={Math.PI / 2.05}
        mouseButtons={{ LEFT: THREE.MOUSE.ROTATE, MIDDLE: THREE.MOUSE.DOLLY, RIGHT: THREE.MOUSE.PAN }}
      />
    </Canvas>
  )
}

// ----------------------------------------------------------------------------------------------------
// Scene contents — equipment + totes (kept out of <Canvas> JSX so the hooks below are R3F-scoped)
// ----------------------------------------------------------------------------------------------------

interface SceneContentProps {
  topology: AutomationTopology
  snapshot: TwinSnapshot
  activeLevelId: string | null
  selectedPlacedId: string | null
  selectedHuId: string | null
  onSelectEquipment?: (placedId: string | null) => void
  onSelectTote?: (huId: string | null) => void
}

function SceneContent({
  topology,
  snapshot,
  activeLevelId,
  selectedPlacedId,
  selectedHuId,
  onSelectEquipment,
  onSelectTote,
}: SceneContentProps): JSX.Element {
  const levels: AutomationLevel[] = topology.levels

  // Equipment to render (optionally filtered to a single level), each with its precomputed geometry.
  const placed = useMemo(() => {
    const out: Array<{ eq: AutomationEquipment; geom: PlacementGeom }> = []
    for (const eq of topology.equipment) {
      if (activeLevelId && eq.levelId !== activeLevelId) continue
      out.push({ eq, geom: placementGeom(eq, levels) })
    }
    return out
  }, [topology.equipment, levels, activeLevelId])

  // Geometry per placed id (for resolving tote anchor points) + the set of placements on this level.
  const geomById = useMemo(() => {
    const m = new Map<string, PlacementGeom>()
    for (const p of placed) m.set(p.eq.id, p.geom)
    return m
  }, [placed])

  return (
    <group>
      {placed.map(({ eq, geom }) => (
        <EquipmentMesh
          key={eq.id}
          geom={geom}
          activity={snapshot.activityByPlacedId[eq.id]}
          selected={eq.id === selectedPlacedId}
          onSelect={() => onSelectEquipment?.(eq.id)}
        />
      ))}

      <Totes
        totes={snapshot.totes}
        geomById={geomById}
        selectedHuId={selectedHuId}
        onSelectTote={onSelectTote}
      />
    </group>
  )
}

// ----------------------------------------------------------------------------------------------------
// Equipment mesh — one per placement (hooks are stable per element, so useFrame is safe here)
// ----------------------------------------------------------------------------------------------------

interface EquipmentMeshProps {
  geom: PlacementGeom
  activity?: EquipmentActivity
  selected: boolean
  onSelect: () => void
}

function EquipmentMesh({ geom, activity, selected, onSelect }: EquipmentMeshProps): JSX.Element {
  const state = activity?.state ?? 'idle'
  const base = baseColorFor(geom.category)

  // Activity colour: idle keeps the category base, running goes amber, faulted goes red.
  const color = state === 'running' ? AMBER : state === 'faulted' ? RED : base
  const pulsing = state === 'running'

  const matRef = useRef<THREE.MeshStandardMaterial>(null)

  useFrame(({ clock }) => {
    const mat = matRef.current
    if (!mat) return
    if (selected) {
      // Selection wins: a steady lime emissive outline glow.
      mat.emissive.set(LIME)
      mat.emissiveIntensity = 0.55
    } else if (state === 'faulted') {
      mat.emissive.set(RED)
      mat.emissiveIntensity = 0.45
    } else if (pulsing) {
      mat.emissive.set(AMBER)
      mat.emissiveIntensity = 0.25 + 0.25 * (0.5 + 0.5 * Math.sin(clock.elapsedTime * 4))
    } else {
      mat.emissive.set('#000000')
      mat.emissiveIntensity = 0
    }
  })

  const handleDown = (e: ThreeEvent<PointerEvent>) => {
    e.stopPropagation()
    onSelect()
  }

  // Path conveyor: a ribbon of thin boxes along the centreline (+ joint fillers at shared points).
  if (geom.worldPath && geom.worldPath.length >= 2) {
    return (
      <ConveyorRibbon
        geom={geom}
        color={color}
        matRef={matRef}
        onPointerDown={handleDown}
      />
    )
  }

  // Box equipment: a single rounded box at the placement centre, rotated by yaw.
  const [lx, hy, wz] = geom.size
  return (
    <group position={geom.center} rotation={[0, -geom.yawRad, 0]} onPointerDown={handleDown}>
      <RoundedBox args={[lx, hy, wz]} radius={Math.min(0.08, hy / 3)} smoothness={3}>
        <meshStandardMaterial
          ref={matRef}
          color={color}
          metalness={0.1}
          roughness={0.7}
          emissive="#000000"
          emissiveIntensity={0}
        />
      </RoundedBox>
    </group>
  )
}

// A conveyor centreline drawn as a sequence of thin boxes per segment, with small fillers at the
// joints so turns read as connected. The whole ribbon shares one material (so the activity pulse /
// selection glow animates uniformly). The matRef is attached to the first segment's material — drei
// reuses it across instances via the shared node, but to keep it simple every mesh gets its own
// material clone driven by the same colour, and the ref drives the emissive on the lead segment.
interface ConveyorRibbonProps {
  geom: PlacementGeom
  color: string
  matRef: React.RefObject<THREE.MeshStandardMaterial>
  onPointerDown: (e: ThreeEvent<PointerEvent>) => void
}

function ConveyorRibbon({ geom, color, matRef, onPointerDown }: ConveyorRibbonProps): JSX.Element {
  const path = geom.worldPath ?? []
  const y = geom.elevationM + Math.max(0.25, geom.size[1] / 2)
  const widthM = Math.max(0.4, Math.min(1.2, geom.size[2] || 0.6))
  const heightM = 0.3

  const segs = useMemo(() => {
    const out: Array<{ mx: number; mz: number; len: number; yaw: number }> = []
    for (let i = 0; i < path.length - 1; i++) {
      const a = path[i]
      const b = path[i + 1]
      const dx = b[0] - a[0]
      const dz = b[1] - a[1]
      const len = Math.hypot(dx, dz)
      if (len < 1e-6) continue
      out.push({ mx: (a[0] + b[0]) / 2, mz: (a[1] + b[1]) / 2, len, yaw: Math.atan2(dz, dx) })
    }
    return out
  }, [path])

  return (
    <group onPointerDown={onPointerDown}>
      {segs.map((s, k) => (
        <group key={`seg-${k}`} position={[s.mx, y, s.mz]} rotation={[0, -s.yaw, 0]}>
          <RoundedBox args={[s.len, heightM, widthM]} radius={0.06} smoothness={2}>
            {k === 0 ? (
              <meshStandardMaterial
                ref={matRef}
                color={color}
                metalness={0.1}
                roughness={0.7}
                emissive="#000000"
                emissiveIntensity={0}
              />
            ) : (
              <meshStandardMaterial color={color} metalness={0.1} roughness={0.7} />
            )}
          </RoundedBox>
        </group>
      ))}
      {/* Joint fillers at interior waypoints so corners look connected. */}
      {path.slice(1, -1).map((p, i) => (
        <mesh key={`joint-${i}`} position={[p[0], y, p[1]]}>
          <boxGeometry args={[widthM, heightM, widthM]} />
          <meshStandardMaterial color={color} metalness={0.1} roughness={0.7} />
        </mesh>
      ))}
    </group>
  )
}

// ----------------------------------------------------------------------------------------------------
// Totes — one child component owns all the per-tote animation refs (no hooks-in-a-loop)
// ----------------------------------------------------------------------------------------------------

interface TotesProps {
  totes: ToteView[]
  geomById: Map<string, PlacementGeom>
  selectedHuId: string | null
  onSelectTote?: (huId: string | null) => void
}

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

function Totes({ totes, geomById, selectedHuId, onSelectTote }: TotesProps): JSX.Element {
  // Per-tote rendered group + its material, kept across frames so we can lerp toward the live target.
  const groupRefs = useRef<Map<string, THREE.Group>>(new Map())
  const posRefs = useRef<Map<string, THREE.Vector3>>(new Map())

  // Resolve the renderable totes (anchored to a placement that's on the active level) + targets.
  const resolved = useMemo(() => {
    const out: Array<{
      tote: ToteView
      target: THREE.Vector3
      start: THREE.Vector3
    }> = []
    for (const tote of totes) {
      if (!tote.anchorPlacedId) continue
      const geom = geomById.get(tote.anchorPlacedId)
      if (!geom) continue // anchor not on this level (or not rendered)
      const a = anchorPoint(geom)
      const target = new THREE.Vector3(a[0], a[1] + BELT_LIFT, a[2])
      // Start a brand-new tote at its previous equipment's anchor (if available + on this level),
      // else at the target itself, so it glides in on the first frame it appears.
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

  // GC: drop stored positions for totes that are no longer present.
  const liveIds = useMemo(() => new Set(resolved.map((r) => r.tote.huId)), [resolved])

  useFrame((_state, delta) => {
    const pos = posRefs.current
    const groups = groupRefs.current
    // Remove stale entries.
    for (const id of Array.from(pos.keys())) {
      if (!liveIds.has(id)) {
        pos.delete(id)
        groups.delete(id)
      }
    }
    // Frame-rate-independent smoothing toward each target (~0.08 per 60fps frame).
    const k = 1 - Math.pow(1 - 0.08, delta * 60)
    const t = _state.clock.elapsedTime
    for (const { tote, target } of resolved) {
      const g = groups.get(tote.huId)
      if (!g) continue
      let cur = pos.get(tote.huId)
      if (!cur) {
        cur = new THREE.Vector3()
        cur.copy(target) // initial copy; the real start was set on the group below
        pos.set(tote.huId, cur)
      }
      cur.lerp(target, k)
      // Recirculating totes loop a small circle around the anchor so they read as "stuck at the sorter".
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
              // Seed the rendered position at the start point on first mount.
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
      <RoundedBox args={[size, size, size]} radius={0.06} smoothness={3}>
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
