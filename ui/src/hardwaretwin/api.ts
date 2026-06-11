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
