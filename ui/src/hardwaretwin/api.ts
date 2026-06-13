// Twin-specific data fetches (beyond what transport/api.ts already provides).
//
// The conveyor ROUTING topology is the graph the live walk actually runs on (ADR-0008): node codes
// are what the hardware scans, and node posX/posY are world XZ metres in the same coordinate system
// as the placed equipment (the projection stages path waypoints / placement centres as node
// positions). The twin uses it to turn a SCANNED trace row into a world position — replaying
// observed transport state rather than inventing motion.

export interface ConveyorNodeXZ {
  code: string
  x: number
  z: number
}

/** Node code → world [x, z] for every routing node that carries a position. */
export async function loadConveyorNodePositions(warehouseId: string): Promise<Map<string, [number, number]>> {
  const res = await fetch(`/api/flow/conveyor/topology?warehouseId=${encodeURIComponent(warehouseId)}`)
  if (!res.ok) throw new Error(`Failed to load conveyor topology: ${res.status}`)
  const body = (await res.json()) as { nodes?: Array<{ code?: string; posX?: number | null; posY?: number | null }> }
  const map = new Map<string, [number, number]>()
  for (const n of body.nodes ?? []) {
    if (n.code && typeof n.posX === 'number' && typeof n.posY === 'number') {
      map.set(n.code, [n.posX, n.posY])
    }
  }
  return map
}

// ----------------------------------------------------------------------------------------------------
// Live-twin tote paths — the backend "visu master" read model (ADR-0008 successor).
//
// The flow-orchestrator owns the routing graph AND the scan trace, so it resolves each moving tote's
// ACTUAL traversed-node polyline (positions baked in, world XZ metres) plus the server clock. The
// frontend renders motion by playing this path, instead of reconstructing it from a scan feed and
// guessing which belt each point sits on (the projection ambiguity that flung totes to a conveyor's
// start at every divert). Consecutive waypoints are graph-adjacent, so a straight segment between
// them IS the belt section.
// ----------------------------------------------------------------------------------------------------

export interface TwinWaypoint {
  code: string
  x: number
  z: number
  /** Scan time (epoch ms). Null on the not-yet-reached `next` node. */
  tMs: number | null
}

export interface TwinTotePath {
  huId: string
  huCode: string | null
  state: 'in-transit' | 'recirculating'
  /** Traversed nodes in order, each with its world position and the time it was scanned there. */
  waypoints: TwinWaypoint[]
  /** The routed-to node the tote is heading for but has not reached (the lead dead-reckon edge). */
  next: TwinWaypoint | null
}

export interface TwinPaths {
  /** Server clock at response time — anchors the delayed render clock so skew cannot misalign it. */
  serverNowMs: number
  totes: TwinTotePath[]
}

/** Every in-transit tote's backend-resolved conveyor path plus the server clock. */
export async function listTotePaths(warehouseId: string): Promise<TwinPaths> {
  const res = await fetch(`/api/flow/twin/tote-paths?warehouseId=${encodeURIComponent(warehouseId)}`)
  if (!res.ok) throw new Error(`Failed to load tote paths: ${res.status}`)
  return (await res.json()) as TwinPaths
}
