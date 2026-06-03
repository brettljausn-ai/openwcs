// Client for the flow-orchestrator conveyor topology API (via the gateway at /api/flow).

export interface NodeDto {
  code: string
  name?: string | null
  hardwareAddress?: string | null
  posX?: number | null
  posY?: number | null
  loopCode?: string | null
}

export interface EdgeDto {
  fromCode: string
  toCode: string
  exitCode: string
  cost?: number | null
}

export interface LoopDto {
  code: string
  maxHus: number
  whenFull: string
  overflowTarget?: string | null
}

export interface Topology {
  nodes: NodeDto[]
  edges: EdgeDto[]
  loops: LoopDto[]
}

export async function loadTopology(warehouseId: string): Promise<Topology> {
  const res = await fetch(`/api/flow/conveyor/topology?warehouseId=${encodeURIComponent(warehouseId)}`)
  if (!res.ok) throw new Error(`Load failed: ${res.status}`)
  const body = (await res.json()) as Partial<Topology>
  return { nodes: body.nodes ?? [], edges: body.edges ?? [], loops: body.loops ?? [] }
}

export async function saveTopology(warehouseId: string, topology: Topology): Promise<Topology> {
  const res = await fetch(`/api/flow/conveyor/topology?warehouseId=${encodeURIComponent(warehouseId)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(topology),
  })
  if (!res.ok) throw new Error(`Save failed: ${res.status}`)
  return (await res.json()) as Topology
}
