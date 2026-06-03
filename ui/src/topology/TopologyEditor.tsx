import { useCallback, useMemo, useState } from 'react'
import ReactFlow, {
  addEdge,
  Background,
  Controls,
  useEdgesState,
  useNodesState,
  type Connection,
  type Edge,
  type Node,
} from 'reactflow'
import 'reactflow/dist/style.css'
import { discoverTopology, loadTopology, saveTopology, type EdgeDto, type LoopDto, type NodeDto, type Topology } from './api'

type NodeData = { name?: string; hardwareAddress?: string; loopCode?: string; label?: string }
type EdgeData = { exitCode: string; cost: number }

function nodeLabel(code: string, data: NodeData): string {
  const parts = [code]
  if (data.name) parts.push(data.name)
  if (data.loopCode) parts.push(`⟳${data.loopCode}`)
  return parts.join(' · ')
}

export default function TopologyEditor() {
  const [warehouseId, setWarehouseId] = useState('')
  const [nodes, setNodes, onNodesChange] = useNodesState<NodeData>([])
  const [edges, setEdges, onEdgesChange] = useEdgesState<EdgeData>([])
  const [loops, setLoops] = useState<LoopDto[]>([])
  const [selectedNode, setSelectedNode] = useState<string | null>(null)
  const [selectedEdge, setSelectedEdge] = useState<string | null>(null)
  const [status, setStatus] = useState('')

  const selNode = useMemo(() => nodes.find((n) => n.id === selectedNode) ?? null, [nodes, selectedNode])
  const selEdge = useMemo(() => edges.find((e) => e.id === selectedEdge) ?? null, [edges, selectedEdge])

  const load = useCallback(async () => {
    if (!warehouseId) {
      setStatus('Enter a warehouse id first')
      return
    }
    try {
      const t = await loadTopology(warehouseId)
      setNodes(t.nodes.map((n) => ({
        id: n.code,
        position: { x: n.posX ?? 0, y: n.posY ?? 0 },
        data: { name: n.name ?? undefined, hardwareAddress: n.hardwareAddress ?? undefined, loopCode: n.loopCode ?? undefined },
        type: 'default',
      })))
      setEdges(t.edges.map((e, i) => ({
        id: `e${i}-${e.fromCode}-${e.toCode}`,
        source: e.fromCode,
        target: e.toCode,
        label: e.exitCode,
        data: { exitCode: e.exitCode, cost: e.cost ?? 1 },
      })))
      setLoops(t.loops)
      setStatus(`Loaded ${t.nodes.length} nodes, ${t.edges.length} edges, ${t.loops.length} loops`)
    } catch (err) {
      setStatus(String(err))
    }
  }, [warehouseId, setNodes, setEdges])

  const save = useCallback(async () => {
    if (!warehouseId) {
      setStatus('Enter a warehouse id first')
      return
    }
    const nodeDtos: NodeDto[] = nodes.map((n) => ({
      code: n.id,
      name: n.data.name ?? null,
      hardwareAddress: n.data.hardwareAddress ?? null,
      posX: n.position.x,
      posY: n.position.y,
      loopCode: n.data.loopCode ?? null,
    }))
    const edgeDtos: EdgeDto[] = edges.map((e) => ({
      fromCode: e.source,
      toCode: e.target,
      exitCode: e.data?.exitCode ?? String(e.label ?? ''),
      cost: e.data?.cost ?? 1,
    }))
    const topology: Topology = { nodes: nodeDtos, edges: edgeDtos, loops }
    try {
      await saveTopology(warehouseId, topology)
      setStatus('Saved')
    } catch (err) {
      setStatus(String(err))
    }
  }, [warehouseId, nodes, edges, loops])

  // Learning: pull discovered (observed-but-unconfigured) nodes/edges onto the canvas to review.
  const runDiscovery = useCallback(async () => {
    if (!warehouseId) {
      setStatus('Enter a warehouse id first')
      return
    }
    try {
      const d = await discoverTopology(warehouseId)
      const existing = new Set(nodes.map((n) => n.id))
      const newNodes = d.nodes.filter((n) => !n.known && !existing.has(n.code))
      let y = 40
      const added: Node<NodeData>[] = newNodes.map((n) => {
        y += 70
        return { id: n.code, position: { x: 560, y }, data: { hardwareAddress: n.sourceIp ?? undefined }, type: 'default' }
      })
      setNodes((ns) => ns.concat(added))
      const codes = new Set<string>([...existing, ...newNodes.map((n) => n.code)])
      setEdges((es) => {
        const have = new Set(es.map((e) => `${e.source} ${e.target}`))
        const newEdges: Edge<EdgeData>[] = d.edges
          .filter((e) => !e.known && codes.has(e.fromCode) && codes.has(e.toCode) && !have.has(`${e.fromCode} ${e.toCode}`))
          .map((e, i) => ({
            id: `disc-${e.fromCode}-${e.toCode}-${i}`,
            source: e.fromCode,
            target: e.toCode,
            label: 'discovered',
            data: { exitCode: 'discovered', cost: 1 },
          }))
        return es.concat(newEdges)
      })
      setStatus(`Discovered ${newNodes.length} new node(s); set exit codes and Save`)
    } catch (err) {
      setStatus(String(err))
    }
  }, [warehouseId, nodes, setNodes, setEdges])

  const onConnect = useCallback((c: Connection) => {
    const exitCode = window.prompt('Exit / decision code for this segment', 'straight')
    if (exitCode === null) return
    const id = `e-${c.source}-${c.target}-${Math.round(Math.random() * 1e9)}`
    setEdges((eds) => addEdge(
      { ...c, id, label: exitCode, data: { exitCode, cost: 1 } } as Edge<EdgeData>,
      eds,
    ))
  }, [setEdges])

  const addNode = useCallback(() => {
    const code = window.prompt('New node code (the id the hardware sends on a scan)')
    if (!code) return
    if (nodes.some((n) => n.id === code)) {
      setStatus(`Node ${code} already exists`)
      return
    }
    const newNode: Node<NodeData> = { id: code, position: { x: 60, y: 60 }, data: {}, type: 'default' }
    setNodes((ns) => ns.concat({ ...newNode, data: { ...newNode.data } }))
  }, [nodes, setNodes])

  // Keep node labels in sync with their data.
  const displayNodes = useMemo(
    () => nodes.map((n) => ({ ...n, data: { ...n.data, label: nodeLabel(n.id, n.data) } })),
    [nodes],
  )

  const patchNode = (patch: Partial<NodeData>) => {
    if (!selectedNode) return
    setNodes((ns) => ns.map((n) => (n.id === selectedNode ? { ...n, data: { ...n.data, ...patch } } : n)))
  }
  const patchEdge = (patch: Partial<EdgeData>) => {
    if (!selectedEdge) return
    setEdges((es) => es.map((e) => {
      if (e.id !== selectedEdge) return e
      const data = { exitCode: e.data?.exitCode ?? '', cost: e.data?.cost ?? 1, ...patch }
      return { ...e, data, label: data.exitCode }
    }))
  }

  return (
    <div style={{ display: 'flex', height: '100%', fontFamily: 'var(--font-body, sans-serif)' }}>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        <div style={{ padding: '0.5rem', display: 'flex', gap: '0.5rem', alignItems: 'center', borderBottom: '1px solid #ddd' }}>
          <strong>Conveyor topology</strong>
          <input placeholder="warehouse id" value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)} style={{ width: 320 }} />
          <button onClick={load}>Load</button>
          <button onClick={addNode}>Add node</button>
          <button onClick={runDiscovery} title="Pull observed-but-unconfigured nodes/edges from learning">Discover</button>
          <button onClick={save}>Save</button>
          <span style={{ color: 'var(--text-dim, #666)' }}>{status}</span>
        </div>
        <div style={{ flex: 1 }}>
          <ReactFlow
            nodes={displayNodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeClick={(_, n) => { setSelectedNode(n.id); setSelectedEdge(null) }}
            onEdgeClick={(_, e) => { setSelectedEdge(e.id); setSelectedNode(null) }}
            fitView
          >
            <Background />
            <Controls />
          </ReactFlow>
        </div>
      </div>

      <aside style={{ width: 280, padding: '0.75rem', borderLeft: '1px solid #ddd', overflowY: 'auto' }}>
        {selNode && (
          <section>
            <h3>Node {selNode.id}</h3>
            <Field label="Name" value={selNode.data.name ?? ''} onChange={(v) => patchNode({ name: v })} />
            <Field label="Hardware address" value={selNode.data.hardwareAddress ?? ''} onChange={(v) => patchNode({ hardwareAddress: v })} />
            <Field label="Loop code" value={selNode.data.loopCode ?? ''} onChange={(v) => patchNode({ loopCode: v || undefined })} />
          </section>
        )}
        {selEdge && (
          <section>
            <h3>Edge</h3>
            <div style={{ color: 'var(--text-dim,#666)' }}>{selEdge.source} → {selEdge.target}</div>
            <Field label="Exit code" value={selEdge.data?.exitCode ?? ''} onChange={(v) => patchEdge({ exitCode: v })} />
            <Field label="Cost" value={String(selEdge.data?.cost ?? 1)} onChange={(v) => patchEdge({ cost: Number(v) || 1 })} />
          </section>
        )}
        <section>
          <h3>Loops</h3>
          <LoopsEditor loops={loops} setLoops={setLoops} />
        </section>
      </aside>
    </div>
  )
}

function Field(props: { label: string; value: string; onChange: (v: string) => void }) {
  return (
    <label style={{ display: 'block', marginBottom: '0.5rem', fontSize: 13 }}>
      <div style={{ color: 'var(--text-dim,#666)' }}>{props.label}</div>
      <input value={props.value} onChange={(e) => props.onChange(e.target.value)} style={{ width: '100%' }} />
    </label>
  )
}

function LoopsEditor(props: { loops: LoopDto[]; setLoops: (l: LoopDto[]) => void }) {
  const { loops, setLoops } = props
  const update = (i: number, patch: Partial<LoopDto>) =>
    setLoops(loops.map((l, idx) => (idx === i ? { ...l, ...patch } : l)))
  return (
    <div>
      {loops.map((l, i) => (
        <div key={i} style={{ border: '1px solid #eee', padding: '0.4rem', marginBottom: '0.4rem', fontSize: 13 }}>
          <Field label="Code" value={l.code} onChange={(v) => update(i, { code: v })} />
          <Field label="Max HUs" value={String(l.maxHus)} onChange={(v) => update(i, { maxHus: Number(v) || 0 })} />
          <label style={{ display: 'block', marginBottom: '0.5rem' }}>
            <div style={{ color: 'var(--text-dim,#666)' }}>When full</div>
            <select value={l.whenFull} onChange={(e) => update(i, { whenFull: e.target.value })}>
              <option value="HOLD">HOLD</option>
              <option value="OVERFLOW">OVERFLOW</option>
            </select>
          </label>
          {l.whenFull === 'OVERFLOW' && (
            <Field label="Overflow target node" value={l.overflowTarget ?? ''} onChange={(v) => update(i, { overflowTarget: v || null })} />
          )}
          <button onClick={() => setLoops(loops.filter((_, idx) => idx !== i))}>Remove</button>
        </div>
      ))}
      <button onClick={() => setLoops(loops.concat({ code: 'LOOP', maxHus: 10, whenFull: 'HOLD', overflowTarget: null }))}>
        Add loop
      </button>
    </div>
  )
}
