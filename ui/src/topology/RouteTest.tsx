// Route test mode for the 3D topology editor.
//
// The "Test route" toolbar toggle lets the user click a START and a TARGET point in the 3D scene.
// Each click resolves to the NEAREST projected routing node (by world XZ — node posX/posY are in
// the same floor-relative coordinate system as the scene) and we run Dijkstra over the DIRECTED
// routing edges, i.e. the exact graph flow-orchestrator routes on (loadTopology in ./api).
//
// The verdict renders in 3D: a glowing lime polyline + hop spheres when a path exists, with a
// ghost tote travelling it at 0.5 m/s on loop, or — when there is NO path — the set of nodes
// reachable from the start lit dimly, so the user can see exactly where connectivity ends.

import { useMemo, useRef, useState } from 'react'
import { useFrame, type ThreeEvent } from '@react-three/fiber'
import { Line } from '@react-three/drei'
import * as THREE from 'three'
import type { NodeDto, Topology } from './api'

/** Tote travel speed used for the ETA verdict and the ghost-tote animation (m/s). */
export const TEST_SPEED_MPS = 0.5

export type RouteResult =
  /** A path exists: the node codes start→…→target and the summed edge cost (metres). */
  | { found: true; codes: string[]; costM: number }
  /** No path: every node reachable from the start (start included) for the "where does
   *  connectivity end" visualisation. */
  | { found: false; reachable: string[] }

/**
 * Dijkstra over the DIRECTED routing edges (edge cost in metres, defaulting to 1 when absent),
 * mirroring the flow-orchestrator's path computation. The O(n²) extract-min is plenty for
 * editor-sized graphs (tens to hundreds of nodes).
 */
export function findRoute(topo: Topology, startCode: string, targetCode: string): RouteResult {
  const adj = new Map<string, { to: string; cost: number }[]>()
  for (const e of topo.edges) {
    const list = adj.get(e.fromCode) ?? []
    list.push({ to: e.toCode, cost: Math.max(0, e.cost ?? 1) })
    adj.set(e.fromCode, list)
  }
  const dist = new Map<string, number>([[startCode, 0]])
  const prev = new Map<string, string>()
  const done = new Set<string>()
  for (;;) {
    // Extract the unsettled node with the smallest tentative distance.
    let u: string | null = null
    let best = Infinity
    for (const [code, d] of dist) {
      if (!done.has(code) && d < best) {
        best = d
        u = code
      }
    }
    if (u === null || u === targetCode) break
    done.add(u)
    for (const { to, cost } of adj.get(u) ?? []) {
      const nd = best + cost
      if (nd < (dist.get(to) ?? Infinity)) {
        dist.set(to, nd)
        prev.set(to, u)
      }
    }
  }
  const total = dist.get(targetCode)
  if (total !== undefined) {
    const codes = [targetCode]
    while (codes[0] !== startCode) {
      const p = prev.get(codes[0])
      if (!p) break // defensive: a broken prev chain should be impossible for a settled node
      codes.unshift(p)
    }
    return { found: true, codes, costM: total }
  }
  return { found: false, reachable: [...dist.keys()] }
}

/** The routing node nearest to a world XZ point (no snap cap — the user explicitly asked "from
 *  here"); nodes without a stored position are skipped. */
export function nearestNode(nodes: NodeDto[], x: number, z: number): NodeDto | null {
  let bestNode: NodeDto | null = null
  let bestD = Infinity
  for (const n of nodes) {
    if (n.posX == null || n.posY == null) continue
    const dx = n.posX - x
    const dz = n.posY - z
    const d = dx * dx + dz * dz
    if (d < bestD) {
      bestD = d
      bestNode = n
    }
  }
  return bestNode
}

/** Human verdict for the hint-bar chip. */
export function routeVerdict(
  result: RouteResult,
  startCode: string,
  targetCode: string,
): { ok: boolean; text: string } {
  if (result.found) {
    const hops = result.codes.length - 1
    const secs = Math.round(result.costM / TEST_SPEED_MPS)
    return {
      ok: true,
      text: `Path: ${hops} hop${hops === 1 ? '' : 's'} · ${result.costM.toFixed(1)} m · ≈${secs}s @ ${TEST_SPEED_MPS} m/s`,
    }
  }
  return { ok: false, text: `No path from ${startCode} to ${targetCode}` }
}

// ---------------------------------------------------------------------------
// 3D overlay
// ---------------------------------------------------------------------------

// Y offsets above the conveyor top so the overlay never z-fights the conveyor bodies.
const PLANE_LIFT = 0.02
const PATH_LIFT = 0.08
const MARKER_LIFT = 0.16

const COLOR_START = '#2ecc71' // green — first pick
const COLOR_TARGET = '#5ec8e0' // blue — second pick
const COLOR_PATH = '#8DC63F' // herbal lime — the found path
const COLOR_HOVER = '#ffffff'

/**
 * Everything route-test renders inside the Canvas: an invisible pick plane just above the conveyor
 * tops (it wins the raycast over the conveyors below, so equipment never sees test-mode clicks),
 * the start/target/hover markers, the found-path polyline + ghost tote, and the dim reachable-set
 * highlight when there is no path.
 */
export function RouteTestOverlay({
  topo,
  startCode,
  targetCode,
  result,
  surfaceM,
  onPick,
}: {
  topo: Topology
  startCode: string | null
  targetCode: string | null
  result: RouteResult | null
  /** Conveyor-top height (m) — overlays render slightly above it. */
  surfaceM: number
  onPick: (code: string) => void
}) {
  const nodeByCode = useMemo(() => {
    const m = new Map<string, NodeDto>()
    for (const n of topo.nodes) m.set(n.code, n)
    return m
  }, [topo])

  // The nearest node under the cursor — a cheap preview of what a click would pick.
  const [hoverCode, setHoverCode] = useState<string | null>(null)

  const posOf = (code: string | null): { x: number; z: number } | null => {
    const n = code ? nodeByCode.get(code) : undefined
    if (!n || n.posX == null || n.posY == null) return null
    return { x: n.posX, z: n.posY }
  }

  // World-space points through the found path, lifted above the conveyor top.
  const pathPoints = useMemo<THREE.Vector3[] | null>(() => {
    if (!result?.found) return null
    const pts: THREE.Vector3[] = []
    for (const code of result.codes) {
      const n = nodeByCode.get(code)
      if (n && n.posX != null && n.posY != null) {
        pts.push(new THREE.Vector3(n.posX, surfaceM + PATH_LIFT, n.posY))
      }
    }
    return pts.length > 0 ? pts : null
  }, [result, nodeByCode, surfaceM])

  const start = posOf(startCode)
  const target = posOf(targetCode)
  const hover = hoverCode !== startCode && hoverCode !== targetCode ? posOf(hoverCode) : null

  return (
    <group>
      {/* Pick plane: sits just above the conveyor tops so it is the FIRST raycast hit for clicks
          on/near the conveyors and the floor — stopPropagation keeps the editor's selection and
          ground-plane handlers out of the loop while testing. */}
      <mesh
        rotation-x={-Math.PI / 2}
        position={[0, surfaceM + PLANE_LIFT, 0]}
        onPointerDown={(e: ThreeEvent<PointerEvent>) => {
          if (e.button !== 0) return
          e.stopPropagation()
          const n = nearestNode(topo.nodes, e.point.x, e.point.z)
          if (n) onPick(n.code)
        }}
        onPointerMove={(e: ThreeEvent<PointerEvent>) => {
          const n = nearestNode(topo.nodes, e.point.x, e.point.z)
          setHoverCode(n?.code ?? null) // setState bails out when unchanged — cheap per-move
        }}
        onPointerOut={() => setHoverCode(null)}
      >
        <planeGeometry args={[400, 400]} />
        <meshBasicMaterial transparent opacity={0} depthWrite={false} />
      </mesh>

      {/* Hover preview: the node a click here would snap to. */}
      {hover && (
        <mesh position={[hover.x, surfaceM + MARKER_LIFT, hover.z]}>
          <sphereGeometry args={[0.14, 12, 12]} />
          <meshBasicMaterial color={COLOR_HOVER} transparent opacity={0.45} />
        </mesh>
      )}

      {start && <PickMarker x={start.x} z={start.z} surfaceM={surfaceM} color={COLOR_START} />}
      {target && <PickMarker x={target.x} z={target.z} surfaceM={surfaceM} color={COLOR_TARGET} />}

      {/* Found path: glow underlay + crisp line + a small sphere per hop. */}
      {pathPoints && pathPoints.length >= 2 && (
        <group>
          <Line points={pathPoints} color={COLOR_PATH} lineWidth={9} transparent opacity={0.25} />
          <Line points={pathPoints} color={COLOR_PATH} lineWidth={3} transparent opacity={0.95} />
          {pathPoints.map((p, i) => (
            <mesh key={i} position={p}>
              <sphereGeometry args={[0.09, 10, 10]} />
              <meshStandardMaterial color={COLOR_PATH} emissive={COLOR_PATH} emissiveIntensity={0.6} />
            </mesh>
          ))}
          <GhostTote points={pathPoints} />
        </group>
      )}

      {/* No path: dimly light every node reachable from the start so the user SEES where
          connectivity ends (the target marker stays blue, outside the lit set). */}
      {result && !result.found &&
        result.reachable.map((code) => {
          const p = posOf(code)
          return p ? (
            <mesh key={code} position={[p.x, surfaceM + PATH_LIFT, p.z]}>
              <sphereGeometry args={[0.12, 10, 10]} />
              <meshBasicMaterial color={COLOR_PATH} transparent opacity={0.3} />
            </mesh>
          ) : null
        })}
    </group>
  )
}

/** A start/target pick marker: a small sphere on a thin pin, colour-coded green/blue. */
function PickMarker({ x, z, surfaceM, color }: { x: number; z: number; surfaceM: number; color: string }) {
  return (
    <group position={[x, surfaceM, z]}>
      <mesh position={[0, MARKER_LIFT + 0.22, 0]}>
        <sphereGeometry args={[0.18, 16, 16]} />
        <meshStandardMaterial color={color} emissive={color} emissiveIntensity={0.7} />
      </mesh>
      <mesh position={[0, (MARKER_LIFT + 0.22) / 2, 0]}>
        <cylinderGeometry args={[0.025, 0.025, MARKER_LIFT + 0.22, 8]} />
        <meshBasicMaterial color={color} transparent opacity={0.8} />
      </mesh>
    </group>
  )
}

/**
 * A small tote-ish lime box that travels the found path at TEST_SPEED_MPS, restarting at the start
 * when it arrives — makes the route legible at a glance. Pure useFrame animation: no React state,
 * position/heading are written straight to the group ref each frame.
 */
function GhostTote({ points }: { points: THREE.Vector3[] }) {
  const ref = useRef<THREE.Group>(null)
  const travelled = useRef(0)

  // Cumulative geometric length along the polyline (animation follows the drawn geometry; the
  // verdict's metres come from the edge costs, which the projection derives from the same geometry).
  const { cum, total } = useMemo(() => {
    const cum = [0]
    let total = 0
    for (let i = 1; i < points.length; i++) {
      total += points[i].distanceTo(points[i - 1])
      cum.push(total)
    }
    return { cum, total }
  }, [points])

  useFrame((_, delta) => {
    const g = ref.current
    if (!g || total <= 0) return
    travelled.current = (travelled.current + delta * TEST_SPEED_MPS) % total
    const d = travelled.current
    let i = 1
    while (i < cum.length - 1 && cum[i] < d) i++
    const a = points[i - 1]
    const b = points[i]
    const seg = cum[i] - cum[i - 1]
    const t = seg > 0 ? (d - cum[i - 1]) / seg : 0
    g.position.set(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t + 0.15, a.z + (b.z - a.z) * t)
    g.rotation.y = Math.atan2(b.x - a.x, b.z - a.z)
  })

  if (points.length < 2) return null
  return (
    <group ref={ref}>
      <mesh castShadow>
        {/* depth × height × width; the group's Y-rotation points +Z along the travel direction */}
        <boxGeometry args={[0.35, 0.3, 0.5]} />
        <meshStandardMaterial
          color={COLOR_PATH}
          emissive={COLOR_PATH}
          emissiveIntensity={0.35}
          metalness={0.1}
          roughness={0.5}
        />
      </mesh>
    </group>
  )
}
