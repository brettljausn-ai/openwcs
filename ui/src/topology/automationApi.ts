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
  // (>= 2 points) the conveyor renders as a polyline of segments instead of a single straight box.
  path?: number[][] | null
  // When true the path loops back from the last waypoint to the first.
  closed?: boolean
}

// A directed link between two placed-equipment items. from/toPlacedId reference
// AutomationEquipment.id; the optional point ids reference function points on either end.
// The whole record round-trips on load/save (server remaps client temp-ids).
export interface AutomationConnection {
  id: string
  fromPlacedId: string
  toPlacedId: string
  fromPointId?: string | null
  toPointId?: string | null
  label?: string | null
  status?: string
}

// Opaque round-tripped payload — this slice doesn't edit function points,
// it only preserves whatever the server returned so a save doesn't drop them.
export type AutomationFunctionPoint = Record<string, unknown>

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
