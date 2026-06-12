// Client-side mirror of the routing projection's node + adjacency rules (flow-orchestrator
// RoutingProjectionService), so the editor can SHOW, live, while dragging, whether two pieces of
// equipment will be linked where they meet, before "Generate routing" runs:
//
//   - equipmentNodes(): the endpoint nodes the projection stages for a placed item (path points
//     used by sections; a straight box conveyor's two ends; a single centre node otherwise), with
//     the same node codes the projection generates (sanitised equipment code + '#' + index).
//   - computeMeetingPoints(): per equipment PAIR, the closest node pair, linked when within
//     ADJACENCY_M (exactly the projection's auto-inference), a near-miss when within NEAR_MISS_M,
//     and overridden by an explicit node-level connection when one is drawn.
//   - nodeLinkStatuses() / linkCandidates(): the per-node link picture for the properties panel,
//     with candidates of OTHER equipment sorted closest first.
//
// Pure module, no React, no three.js, so the rules stay testable and readable in one place.
// Keep the constants and classification in lock-step with RoutingProjectionService.

import type { AutomationConnection, AutomationEquipment } from './automationApi'

const DEG = Math.PI / 180

/** Two nodes of DIFFERENT equipment this close (m) are auto-linked by the routing projection. */
export const ADJACENCY_M = 1.5
/** Beyond ADJACENCY_M but within this (m), the editor flags a near-miss: "not linked: too far". */
export const NEAR_MISS_M = 3.0
/** A box conveyor needs at least this length for the projection to stage start/end nodes. */
const MIN_BOX_LENGTH_M = 0.05

/** An equipment endpoint node as the routing projection will stage it. */
export interface EquipNode {
  equipId: string
  /** Path index on the equipment (a box conveyor uses 0 and a notional last index). */
  index: number
  /** The projected routing-node code, e.g. `BIN_CONVEYOR-1#0`. (The projection may suffix a
   *  collision counter on duplicate equipment codes; this client mirror does not.) */
  code: string
  x: number
  z: number
}

/** A meeting point between two pieces of equipment: the closest node pair of the pair, or an
 *  explicitly drawn node-level connection. */
export interface MeetingPoint {
  /** The explicit connection behind this link, when state === 'explicit' (clickable/removable). */
  connectionId?: string
  a: EquipNode
  b: EquipNode
  distM: number
  /** auto = within ADJACENCY_M, the projection will link them by proximity;
   *  explicit = a drawn connection from the model's connections list (any distance);
   *  near = within NEAR_MISS_M but beyond ADJACENCY_M, "not linked: too far". */
  state: 'auto' | 'explicit' | 'near'
}

// ---- node staging (mirrors RoutingProjectionService.project's per-equipment branch) ------------

function sanitise(raw: string): string {
  const s = raw.replace(/\s+/g, '_')
  return s === '' ? 'EQ' : s
}

function eqCode(eq: AutomationEquipment): string {
  return eq.code && eq.code.trim() !== '' ? eq.code.trim() : `EQ-${eq.id.slice(0, 8)}`
}

/** The projected node code for an equipment path point: equipment code + '#' + index. */
export function projectedNodeCode(eq: AutomationEquipment, index: number): string {
  return `${sanitise(eqCode(eq))}#${index}`
}

/** Categories that, with no routable path, collapse to a single node (same as the projection). */
function noPathKind(cat: string): boolean {
  return cat === 'asrs' || cat === 'sorter' || cat === 'manual-storage' || cat === 'other'
}

/** Path indices referenced by valid sections, ascending, the points that become nodes. */
function sectionIndices(path: number[][], sections: number[][] | null | undefined): number[] {
  const used = new Set<number>()
  for (const s of sections ?? []) {
    if (!Array.isArray(s) || s.length < 2) continue
    const [i, j] = s
    if (i >= 0 && i < path.length && j >= 0 && j < path.length) {
      used.add(i)
      used.add(j)
    }
  }
  return [...used].sort((a, b) => a - b)
}

/**
 * The endpoint nodes the routing projection will stage for this placed item. `cat` is the
 * editor's computed category (the same value Save sends, so the projection sees it too):
 *   - a path with explicit sections → one node per path index a section references;
 *   - a `conveyor` box with a usable length (no path/sections) → its two end nodes;
 *   - everything else → a single node at the placement centre.
 */
export function equipmentNodes(eq: AutomationEquipment, cat: string): EquipNode[] {
  const path = Array.isArray(eq.path) ? eq.path : []
  const hasPath = path.length >= 2
  const hasSections = Array.isArray(eq.sections) && eq.sections.length > 0

  const pointKind = noPathKind(cat) && !(hasPath && hasSections)

  if (!pointKind && hasPath && hasSections) {
    const used = sectionIndices(path, eq.sections)
    if (used.length > 0) {
      return used.map((idx) => ({
        equipId: eq.id,
        index: idx,
        code: projectedNodeCode(eq, idx),
        x: path[idx][0],
        z: path[idx][1],
      }))
    }
    // Sections referencing no valid path point degrade to a single node (projection warning case).
    return [centreNode(eq)]
  }

  if (!pointKind && cat === 'conveyor' && eq.lengthM > MIN_BOX_LENGTH_M) {
    // Straight box conveyor: start/end = centre ± (length/2) along yaw.
    const yaw = eq.rotationDeg * DEG
    const ux = Math.cos(yaw)
    const uz = Math.sin(yaw)
    const half = eq.lengthM / 2
    const last = hasPath ? path.length - 1 : 1
    return [
      {
        equipId: eq.id,
        index: 0,
        code: projectedNodeCode(eq, 0),
        x: eq.posXM - ux * half,
        z: eq.posZM - uz * half,
      },
      {
        equipId: eq.id,
        index: last,
        code: projectedNodeCode(eq, last),
        x: eq.posXM + ux * half,
        z: eq.posZM + uz * half,
      },
    ]
  }

  return [centreNode(eq)]
}

function centreNode(eq: AutomationEquipment): EquipNode {
  return { equipId: eq.id, index: 0, code: projectedNodeCode(eq, 0), x: eq.posXM, z: eq.posZM }
}

// ---- adjacency / meeting points -----------------------------------------------------------------

function dist(a: EquipNode, b: EquipNode): number {
  return Math.hypot(a.x - b.x, a.z - b.z)
}

/** True when the connection is a workstation role-interaction (STOCK/ORDER/DECANT onto a function
 *  point), those are managed in the workstation panel, not as node links. */
function isWorkstationRole(c: AutomationConnection): boolean {
  return c.toPointId != null || c.fromPointId != null
}

/** Resolve a connection end to a node: by path index when anchored, else the equipment-level
 *  fallback the projection uses (exit = last node of FROM, entry = first node of TO). */
function connectionNode(
  nodes: EquipNode[] | undefined,
  pathIndex: number | null | undefined,
  fallback: 'exit' | 'entry',
): EquipNode | null {
  if (!nodes || nodes.length === 0) return null
  if (pathIndex != null) return nodes.find((n) => n.index === pathIndex) ?? null
  return fallback === 'exit' ? nodes[nodes.length - 1] : nodes[0]
}

/**
 * The meeting points to indicate in the scene. For every pair of equipment the closest node pair
 * is linked when within ADJACENCY_M (the projection's auto-inference, one pair per equipment
 * pair), or flagged a near-miss within NEAR_MISS_M. A drawn node-level connection between two
 * pieces REPLACES the pair's proximity entry (state 'explicit'), it links regardless of distance.
 */
export function computeMeetingPoints(
  nodesByEquip: EquipNode[][],
  connections: AutomationConnection[],
): MeetingPoint[] {
  const byPair = new Map<string, MeetingPoint[]>()
  const pairKey = (x: string, y: string) => (x < y ? `${x}|${y}` : `${y}|${x}`)

  for (let ai = 0; ai < nodesByEquip.length; ai++) {
    for (let bi = ai + 1; bi < nodesByEquip.length; bi++) {
      let best: MeetingPoint | null = null
      for (const na of nodesByEquip[ai]) {
        for (const nb of nodesByEquip[bi]) {
          const d = dist(na, nb)
          if (d <= NEAR_MISS_M && (!best || d < best.distM)) {
            best = { a: na, b: nb, distM: d, state: d <= ADJACENCY_M ? 'auto' : 'near' }
          }
        }
      }
      if (best) byPair.set(pairKey(best.a.equipId, best.b.equipId), [best])
    }
  }

  // Explicit node-level connections override the pair's proximity entry.
  const nodesById = new Map<string, EquipNode[]>()
  for (const nodes of nodesByEquip) {
    if (nodes.length > 0) nodesById.set(nodes[0].equipId, nodes)
  }
  const explicitPairs = new Set<string>()
  for (const c of connections) {
    if (isWorkstationRole(c)) continue
    const a = connectionNode(nodesById.get(c.fromPlacedId), c.fromPathIndex, 'exit')
    const b = connectionNode(nodesById.get(c.toPlacedId), c.toPathIndex, 'entry')
    if (!a || !b || a.equipId === b.equipId) continue
    const key = pairKey(a.equipId, b.equipId)
    if (!explicitPairs.has(key)) {
      explicitPairs.add(key)
      byPair.set(key, [])
    }
    byPair.get(key)!.push({ a, b, distM: dist(a, b), state: 'explicit', connectionId: c.id })
  }

  return [...byPair.values()].flat()
}

// ---- per-node link picture (the properties panel's Connections section) -------------------------

/** One explicit connection touching a node, as shown (and unlinked) in the panel. */
export interface ExplicitLink {
  connectionId: string
  other: EquipNode
  distM: number
  /** true when this node is the FROM end (totes flow out of it over this link). */
  outgoing: boolean
}

export interface NodeLinkStatus {
  node: EquipNode
  /** Explicit connections anchored at (or resolving to) this node. */
  explicit: ExplicitLink[]
  /** The counterpart the projection will auto-link this node to by proximity, or null. */
  auto: { other: EquipNode; distM: number } | null
  /** The closest node of any OTHER equipment (suggestion seed; null when this is the only item). */
  nearest: { other: EquipNode; distM: number } | null
}

/**
 * The link picture for every endpoint node of equipment `equipId`. `nodesByEquip` should cover ALL
 * equipment (the projection ignores levels, adjacency is world XZ).
 */
export function nodeLinkStatuses(
  equipId: string,
  nodesByEquip: EquipNode[][],
  connections: AutomationConnection[],
): NodeLinkStatus[] {
  const mine = nodesByEquip.find((nodes) => nodes.length > 0 && nodes[0].equipId === equipId) ?? []
  const meets = computeMeetingPoints(nodesByEquip, connections)
  return mine.map((node) => {
    const explicit: ExplicitLink[] = []
    let auto: NodeLinkStatus['auto'] = null
    for (const m of meets) {
      const isA = m.a.equipId === node.equipId && m.a.index === node.index
      const isB = m.b.equipId === node.equipId && m.b.index === node.index
      if (!isA && !isB) continue
      const other = isA ? m.b : m.a
      if (m.state === 'explicit') {
        // Re-find the connection id(s) for this node pair so the panel can unlink.
        for (const c of connections) {
          const from = isA ? node : other
          const to = isA ? other : node
          if (isWorkstationRole(c)) continue
          if (c.fromPlacedId !== from.equipId || c.toPlacedId !== to.equipId) continue
          const fromIdx = c.fromPathIndex
          const toIdx = c.toPathIndex
          if ((fromIdx == null || fromIdx === from.index) && (toIdx == null || toIdx === to.index)) {
            explicit.push({ connectionId: c.id, other, distM: m.distM, outgoing: isA })
          }
        }
      } else if (m.state === 'auto') {
        auto = { other, distM: m.distM }
      }
    }
    const candidates = linkCandidates(node, nodesByEquip)
    return {
      node,
      explicit,
      auto,
      nearest: candidates.length > 0 ? candidates[0] : null,
    }
  })
}

/** All nodes of OTHER equipment, sorted CLOSEST FIRST, the link suggestion list. */
export function linkCandidates(
  node: EquipNode,
  nodesByEquip: EquipNode[][],
): { other: EquipNode; distM: number }[] {
  const out: { other: EquipNode; distM: number }[] = []
  for (const nodes of nodesByEquip) {
    for (const other of nodes) {
      if (other.equipId === node.equipId) continue
      out.push({ other, distM: dist(node, other) })
    }
  }
  out.sort((a, b) => a.distM - b.distM)
  return out
}
