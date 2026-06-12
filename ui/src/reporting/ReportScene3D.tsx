// Read-only 3D scene for the reporting heatmaps. It renders the SAME base scene as the Automation
// topology editor / Hardware visualisation: reusing the editor's presentational meshes
// (EquipmentMesh + FunctionPointMarker from AutomationTopology3D) read-only (selected=false, all
// edit handlers no-ops): and overlays report data on top (repo rule: reuse renderers, never build
// a cruder parallel one):
//   - conveyorHeat: per placed conveyor, a thin emissive "heat skin" over each belt section tinted
//     by the conveyor's traffic intensity (log scale; t in [0,1] computed by the screen)
//   - cells: rack-cell heat boxes (the storage-movement heatmap) at the same world positions the
//     hardware twin renders stored totes at (deriveStoredTotes mapping)
//
// Heavy three.js: keep this module behind React.lazy (like HardwareTwin3D) so it never enters the
// main bundle.

import { useMemo } from 'react'
import { Canvas } from '@react-three/fiber'
import { Grid, OrbitControls } from '@react-three/drei'
import * as THREE from 'three'
import type { AutomationEquipment, AutomationTopology } from '../topology/automationApi'
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
import { heatColor } from './heat'

const BG = '#081e16'
const DEG = Math.PI / 180

const noop = () => {}

export interface HeatCell {
  /** World position (the twin's stored-tote cell mapping). */
  pos: [number, number, number]
  /** Heat intensity in [0,1] (log-scaled by the screen). */
  t: number
}

export interface ReportScene3DProps {
  topology: AutomationTopology
  /** Master-data equipment library (id → Equipment): same classification as the editor/twin. */
  lib: Map<string, Equipment>
  /** placedId → heat t in [0,1]; conveyors absent from the map render untinted. */
  conveyorHeat?: Map<string, number> | null
  /** Rack-cell heat boxes (ASRS storage-movement heatmap). */
  cells?: HeatCell[] | null
  showLabels?: boolean
}

export default function ReportScene3D({
  topology,
  lib,
  conveyorHeat = null,
  cells = null,
  showLabels = false,
}: ReportScene3DProps): JSX.Element {
  const items = topology.equipment
  const byId = useMemo(() => new Map(items.map((e) => [e.id, e])), [items])

  // Scene conveyor height: ASRS IN/OUT stubs render flush with the conveyors (mirrors the twin).
  const stubHeightM = useMemo(() => {
    let h = 0
    for (const eq of items) {
      if (topoIsConveyor(eq, lib)) h = Math.max(h, eq.heightM || 0)
    }
    return h > 0 ? h : undefined
  }, [items, lib])

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

      {/* Base scene: the editor's exact meshes, read-only. */}
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
          onSelect={noop}
          onMove={noop}
          onMoveWaypoint={noop}
          onAnchorWaypoint={noop}
          onHandleDragChange={noop}
          showLabels={showLabels}
          stubHeightM={stubHeightM}
        />
      ))}

      {/* Function-point markers (SCAN / DIV / QRY hats), labels-toggled like the twin. */}
      {showLabels &&
        topology.functionPoints.map((fp) => {
          const eq = byId.get(fp.placedId)
          if (!eq) return null
          const stubHost = !topoIsConveyor(eq, lib) && !!(eq.path && eq.path.length >= 2)
          return (
            <TopoFunctionPointMarker
              key={fp.id}
              fp={fp}
              eq={eq}
              topM={stubHost ? stubHeightM ?? STUB_HEIGHT_M : undefined}
              onSelect={noop}
            />
          )
        })}

      {/* Traffic heat skins over the conveyor belts. */}
      {conveyorHeat &&
        items.map((eq) => {
          const t = conveyorHeat.get(eq.id)
          if (t === undefined) return null
          return <ConveyorHeatSkin key={`heat-${eq.id}`} eq={eq} t={t} />
        })}

      {/* Rack-cell heat boxes (storage-movement heatmap). */}
      {cells?.map((c, i) => (
        <mesh key={`cell-${i}`} position={c.pos}>
          <boxGeometry args={[0.7, 0.36, 0.5]} />
          <meshStandardMaterial
            color={heatColor(c.t)}
            emissive={heatColor(c.t)}
            emissiveIntensity={0.35 + 0.55 * c.t}
            metalness={0.1}
            roughness={0.5}
            toneMapped={false}
          />
        </mesh>
      ))}

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

// A thin emissive overlay riding just above the belt surface, tinted by the conveyor's traffic
// intensity. Path conveyors get one skin per directed section (the same segment geometry the
// editor's ConveyorPath computes); box conveyors get a single skin over the body.
function ConveyorHeatSkin({ eq, t }: { eq: AutomationEquipment; t: number }): JSX.Element | null {
  const color = heatColor(t)
  const mat = (
    <meshStandardMaterial
      color={color}
      emissive={color}
      emissiveIntensity={0.3 + 0.6 * t}
      transparent
      opacity={0.85}
      toneMapped={false}
    />
  )
  const skinH = 0.07
  const path = (eq.path ?? []) as number[][]

  if (path.length >= 2) {
    const y = eq.posYM + (eq.heightM || 0.5) + skinH / 2 + 0.01
    const w = Math.max(0.2, (eq.widthM || 0.5) * 0.9)
    const segs = effectiveSections(eq)
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
    return (
      <group>
        {segs.map((s, k) => (
          <mesh key={k} position={[s.mx, y, s.mz]} rotation={[0, -s.yaw, 0]}>
            <boxGeometry args={[s.len, skinH, w]} />
            {mat}
          </mesh>
        ))}
      </group>
    )
  }

  // Box-mode conveyor: a single skin over the body, same transform as the editor's box group.
  const y = eq.posYM + (eq.heightM || 0.5) + skinH / 2 + 0.01
  return (
    <mesh position={[eq.posXM, y, eq.posZM]} rotation={[0, eq.rotationDeg * DEG, 0]}>
      <boxGeometry args={[Math.max(0.2, eq.lengthM || 1), skinH, Math.max(0.2, (eq.widthM || 0.5) * 0.9)]} />
      {mat}
    </mesh>
  )
}
