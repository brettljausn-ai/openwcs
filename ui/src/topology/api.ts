// Client for the flow-orchestrator conveyor topology API (via the gateway at /api/flow).

export interface NodeDto {
  code: string
  name?: string | null
  hardwareAddress?: string | null
  posX?: number | null
  posY?: number | null
  loopCode?: string | null
  controllerCode?: string | null
  nodeAddress?: string | null
}

// A conveyor controller (PLC): one TCP/IP endpoint hosting many nodes.
export interface ControllerDto {
  code: string
  name?: string | null
  ipAddress: string
  port?: number | null
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
  controllers: ControllerDto[]
}

export async function loadTopology(warehouseId: string): Promise<Topology> {
  const res = await fetch(`/api/flow/conveyor/topology?warehouseId=${encodeURIComponent(warehouseId)}`)
  if (!res.ok) throw new Error(`Load failed: ${res.status}`)
  const body = (await res.json()) as Partial<Topology>
  return { nodes: body.nodes ?? [], edges: body.edges ?? [], loops: body.loops ?? [], controllers: body.controllers ?? [] }
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

export interface DiscoveredNode { code: string; observedCount: number; sourceIp?: string | null; sourcePort?: number | null; known: boolean }
export interface DiscoveredEdge { fromCode: string; toCode: string; count: number; known: boolean }
export interface DiscoveredTarget { code: string; terminalCount: number }
export interface DiscoveredController { code: string; ipAddress: string; port?: number | null; nodeCodes: string[]; known: boolean }
export interface Discovery { nodes: DiscoveredNode[]; edges: DiscoveredEdge[]; targets: DiscoveredTarget[]; controllers: DiscoveredController[] }

export async function discoverTopology(warehouseId: string): Promise<Discovery> {
  const res = await fetch(`/api/flow/conveyor/discovery?warehouseId=${encodeURIComponent(warehouseId)}`)
  if (!res.ok) throw new Error(`Discovery failed: ${res.status}`)
  const body = (await res.json()) as Partial<Discovery>
  return { nodes: body.nodes ?? [], edges: body.edges ?? [], targets: body.targets ?? [], controllers: body.controllers ?? [] }
}
