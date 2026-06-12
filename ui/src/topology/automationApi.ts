// Client for the flow-orchestrator *automation* topology API (via the gateway at /api/flow).
// This is the 3D physical layout of warehouse automation: levels (floors), placed equipment,
// the connections between equipment, and the function points on equipment. Mirrors the routing
// `topology/api.ts` style. On PUT the server remaps client-generated ids, so new rows may carry
// a `crypto.randomUUID()` id; connections + function points are round-tripped untouched.

export interface AutomationLevel {
  id: string
  number: number
  name: string
  elevationM: number
  status: string
}

export interface AutomationEquipment {
  id: string
  levelId: string
  // The master-data Equipment row this placement instantiates (its library entry).
  equipmentId?: string | null
  code: string
  posXM: number
  posYM: number
  posZM: number
  rotationDeg: number
  tiltDeg: number
  lengthM: number
  widthM: number
  heightM: number
  status: string
  // Conveyor centreline waypoints in world XZ metres on the level: [[x, z], ...]. When present
  // (>= 2 points) the conveyor renders as a directed section graph (or, with no `sections`, as a
  // sequential polyline of segments) instead of a single straight box.
  path?: number[][] | null
  // Directed edges over `path`: each `[fromIdx, toIdx]` is a ONE-WAY conveyor run from
  // path[fromIdx] → path[toIdx] (travel direction = from→to). A path point that is the `from` of
  // 2+ sections is a decision/divert point (automatic). When absent/empty but `path` has >= 2
  // points, the path renders as implicit sequential sections (i → i+1).
  sections?: number[][] | null
  // When true the path loops back from the last waypoint to the first.
  closed?: boolean
  // Denormalised category (conveyor|asrs|sorter|manual-storage|workstation|other) sent on save so the
  // routing projection can classify without an equipment-library lookup.
  category?: string | null
  // For a "workstation" placement: the GTP station (gtp_station) it represents. Set when the item was
  // placed from the GTP-workplaces library section; null for all other equipment.
  stationId?: string | null
}

// A directed link between two placed-equipment items. from/toPlacedId reference
// AutomationEquipment.id; the optional point ids reference function points on either end.
// fromPathIndex/toPathIndex optionally anchor the link at a specific PATH POINT (node) on either
// equipment, the editor's explicit node-to-node links (e.g. an ASRS outfeed stub end onto a
// conveyor infeed node); the routing projection then stitches exactly those nodes. The whole
// record round-trips on load/save (server remaps client temp-ids).
export interface AutomationConnection {
  id: string
  fromPlacedId: string
  toPlacedId: string
  fromPointId?: string | null
  toPointId?: string | null
  fromPathIndex?: number | null
  toPathIndex?: number | null
  label?: string | null
  status?: string
}

// A process point on a placed equipment (a scanner, label applicator, divert, DWS, query point,
// wrapper, induct/discharge, …). `placedId` references AutomationEquipment.id; `offsetM` is the
// distance along the equipment from its start; `side` nudges the marker left/right of the
// centreline; `nodeCode` optionally maps the point to a conveyor routing node. The whole record
// round-trips on load/save (the server remaps client temp-ids).
export interface AutomationFunctionPoint {
  id: string
  placedId: string
  functionType: string
  name?: string | null
  offsetM: number
  side?: string | null
  nodeCode?: string | null
  // For a divert: the default direction a tote takes when no route demands otherwise.
  // 'STRAIGHT' (continue the main line) | 'BRANCH' (take the divert's branch) | null (stop at the
  // divert until a route arrives).
  defaultExit?: string | null
  status?: string
}

export interface AutomationTopology {
  levels: AutomationLevel[]
  equipment: AutomationEquipment[]
  connections: AutomationConnection[]
  functionPoints: AutomationFunctionPoint[]
}

export async function loadAutomationTopology(warehouseId: string): Promise<AutomationTopology> {
  const res = await fetch(`/api/flow/automation/topology?warehouseId=${encodeURIComponent(warehouseId)}`)
  if (!res.ok) throw new Error(`Load failed: ${res.status}`)
  const body = (await res.json()) as Partial<AutomationTopology>
  return {
    levels: body.levels ?? [],
    equipment: body.equipment ?? [],
    connections: body.connections ?? [],
    functionPoints: body.functionPoints ?? [],
  }
}

export async function saveAutomationTopology(
  warehouseId: string,
  topology: AutomationTopology,
): Promise<AutomationTopology> {
  const res = await fetch(`/api/flow/automation/topology?warehouseId=${encodeURIComponent(warehouseId)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(topology),
  })
  if (!res.ok) throw new Error(`Save failed: ${res.status}`)
  const body = (await res.json()) as Partial<AutomationTopology>
  return {
    levels: body.levels ?? [],
    equipment: body.equipment ?? [],
    connections: body.connections ?? [],
    functionPoints: body.functionPoints ?? [],
  }
}

export interface RoutingProjectionResult {
  nodes: number
  edges: number
  warnings: string[]
}

/** Generate the conveyor routing graph (nodes/edges) from the placed equipment + sections +
 *  connections + function points. Replaces the warehouse's routing graph. */
export async function projectRoutingGraph(warehouseId: string): Promise<RoutingProjectionResult> {
  const res = await fetch(
    `/api/flow/automation/topology/project?warehouseId=${encodeURIComponent(warehouseId)}`,
    { method: 'POST' },
  )
  if (!res.ok) throw new Error(`Projection failed: ${res.status}`)
  const body = (await res.json()) as Partial<RoutingProjectionResult>
  return { nodes: body.nodes ?? 0, edges: body.edges ?? 0, warnings: body.warnings ?? [] }
}
