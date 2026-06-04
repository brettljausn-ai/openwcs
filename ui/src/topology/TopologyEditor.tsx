import { lazy, Suspense, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
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
import Select from '../ui/Select'
import InfoTip from '../ui/InfoTip'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { discoverTopology, loadTopology, saveTopology, type ControllerDto, type EdgeDto, type LoopDto, type NodeDto, type Topology } from './api'
// Lazy so three.js / r3f are code-split and only fetched when the 3D layout tab is opened.
const AutomationTopology3D = lazy(() => import('./AutomationTopology3D'))

type NodeData = { name?: string; hardwareAddress?: string; loopCode?: string; controllerCode?: string; nodeAddress?: string; label?: string }
type EdgeData = { exitCode: string; cost: number }

type TopologyTab = '3d' | 'routing'

// Tabbed shell around the two topology views: the 3D physical automation layout (default) and the
// routing graph (the original node/edge editor, unchanged below in RoutingGraphEditor).
export default function TopologyEditor() {
  const [tab, setTab] = useState<TopologyTab>('3d')
  return (
    <div className="app-content">
      <div className="page-head">
        <span className="eyebrow">Configuration</span>
        <h1>Automation topology</h1>
      </div>
      <div className="topo-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={tab === '3d'}
          className={`topo-tab${tab === '3d' ? ' is-active' : ''}`}
          onClick={() => setTab('3d')}
        >
          3D layout
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'routing'}
          className={`topo-tab${tab === 'routing' ? ' is-active' : ''}`}
          onClick={() => setTab('routing')}
        >
          Routing graph
        </button>
      </div>
      {tab === '3d' ? (
        <Suspense fallback={<div className="glass card-pad">Loading 3D editor…</div>}>
          <AutomationTopology3D />
        </Suspense>
      ) : (
        <div className="topo-routing-wrap">
          <RoutingGraphEditor />
        </div>
      )}
      <style>{`
        .topo-tabs { display: flex; gap: .4rem; margin-bottom: .8rem; }
        .topo-tab {
          padding: .45rem 1rem; border-radius: 999px; cursor: pointer; font-size: .875rem;
          background: var(--glass-bg); color: var(--text); border: 1px solid var(--glass-border);
          font-family: var(--font-body); transition: all .15s;
        }
        .topo-tab:hover { border-color: var(--glass-border-bright); }
        .topo-tab.is-active {
          background: rgba(141, 198, 63, .15); color: var(--herbal-lime); border-color: var(--glass-border-bright);
        }
        .topo-routing-wrap { height: 72vh; border: 1px solid var(--glass-border); border-radius: 12px; overflow: hidden; }
      `}</style>
    </div>
  )
}

function nodeLabel(code: string, data: NodeData): string {
  const parts = [code]
  if (data.name) parts.push(data.name)
  if (data.loopCode) parts.push(`⟳${data.loopCode}`)
  return parts.join(' · ')
}

function RoutingGraphEditor() {
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const [nodes, setNodes, onNodesChange] = useNodesState<NodeData>([])
  const [edges, setEdges, onEdgesChange] = useEdgesState<EdgeData>([])
  const [loops, setLoops] = useState<LoopDto[]>([])
  const [controllers, setControllers] = useState<ControllerDto[]>([])
  const [selectedNode, setSelectedNode] = useState<string | null>(null)
  const [selectedEdge, setSelectedEdge] = useState<string | null>(null)
  const [status, setStatus] = useState('')

  const selNode = useMemo(() => nodes.find((n) => n.id === selectedNode) ?? null, [nodes, selectedNode])
  const selEdge = useMemo(() => edges.find((e) => e.id === selectedEdge) ?? null, [edges, selectedEdge])

  const load = useCallback(async () => {
    if (!warehouseId) {
      setStatus('No active warehouse selected')
      return
    }
    try {
      const t = await loadTopology(warehouseId)
      setNodes(t.nodes.map((n) => ({
        id: n.code,
        position: { x: n.posX ?? 0, y: n.posY ?? 0 },
        data: {
          name: n.name ?? undefined,
          hardwareAddress: n.hardwareAddress ?? undefined,
          loopCode: n.loopCode ?? undefined,
          controllerCode: n.controllerCode ?? undefined,
          nodeAddress: n.nodeAddress ?? undefined,
        },
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
      setControllers(t.controllers)
      setStatus(`Loaded ${t.nodes.length} nodes, ${t.edges.length} edges, ${t.loops.length} loops, ${t.controllers.length} controllers`)
    } catch (err) {
      setStatus(String(err))
    }
  }, [warehouseId, setNodes, setEdges])

  // (Re)load topology for the globally-active warehouse whenever it changes.
  useEffect(() => {
    load()
  }, [load])

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
      controllerCode: n.data.controllerCode ?? null,
      nodeAddress: n.data.nodeAddress ?? null,
    }))
    const edgeDtos: EdgeDto[] = edges.map((e) => ({
      fromCode: e.source,
      toCode: e.target,
      exitCode: e.data?.exitCode ?? String(e.label ?? ''),
      cost: e.data?.cost ?? 1,
    }))
    const topology: Topology = { nodes: nodeDtos, edges: edgeDtos, loops, controllers }
    try {
      await saveTopology(warehouseId, topology)
      setStatus('Saved')
    } catch (err) {
      setStatus(String(err))
    }
  }, [warehouseId, nodes, edges, loops, controllers])

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
          <strong>Automation topology</strong>
          <button onClick={load}>Reload</button>
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
            <Field label={<>Name <InfoTip text="Human-readable label for this node, shown on the canvas next to its code. Optional and free-text." example="Infeed scanner 1" /></>} value={selNode.data.name ?? ''} onChange={(v) => patchNode({ name: v })} />
            <Field label={<>Controller (PLC) code <InfoTip text="Code of the controller (PLC) that drives this node. Links the node to one of the controllers defined below." example="PLC-01" /></>} value={selNode.data.controllerCode ?? ''} onChange={(v) => patchNode({ controllerCode: v || undefined })} />
            <Field label={<>Node-local address <InfoTip text="The node's address within its controller — how the PLC identifies this point internally (e.g. a scanner or divert index)." example="3" /></>} value={selNode.data.nodeAddress ?? ''} onChange={(v) => patchNode({ nodeAddress: v || undefined })} />
            <Field label={<>Hardware address (legacy) <InfoTip text="Older single-field hardware identifier, kept for backward compatibility. Prefer controller code + node-local address for new nodes." example="192.168.10.21:5001" /></>} value={selNode.data.hardwareAddress ?? ''} onChange={(v) => patchNode({ hardwareAddress: v })} />
            <Field label={<>Loop code <InfoTip text="Code of the recirculation loop this node belongs to, if any. Leave blank for nodes not on a loop." example="LOOP-A" /></>} value={selNode.data.loopCode ?? ''} onChange={(v) => patchNode({ loopCode: v || undefined })} />
          </section>
        )}
        {selEdge && (
          <section>
            <h3>Edge</h3>
            <div style={{ color: 'var(--text-dim,#666)' }}>{selEdge.source} → {selEdge.target}</div>
            <Field label={<>Exit code <InfoTip text="The decision/exit code the controller emits to route a load along this segment — chooses which outgoing path a HU takes at the source node." example="straight" /></>} value={selEdge.data?.exitCode ?? ''} onChange={(v) => patchEdge({ exitCode: v })} />
            <Field label={<>Cost <InfoTip text="Routing weight for this segment; lower-cost paths are preferred when the WCS computes a route. Use higher values to discourage a path." example="1" /></>} value={String(selEdge.data?.cost ?? 1)} onChange={(v) => patchEdge({ cost: Number(v) || 1 })} />
          </section>
        )}
        <section>
          <h3>Controllers (PLCs)</h3>
          <ControllersEditor controllers={controllers} setControllers={setControllers} />
        </section>
        <section>
          <h3>Loops</h3>
          <LoopsEditor loops={loops} setLoops={setLoops} />
        </section>
      </aside>
    </div>
  )
}

function ControllersEditor(props: { controllers: ControllerDto[]; setControllers: (c: ControllerDto[]) => void }) {
  const { controllers, setControllers } = props
  const update = (i: number, patch: Partial<ControllerDto>) =>
    setControllers(controllers.map((c, idx) => (idx === i ? { ...c, ...patch } : c)))
  return (
    <div>
      <p style={{ color: 'var(--text-dim,#666)', fontSize: 12, marginTop: 0 }}>
        One PLC (IP:port) can host many nodes — set a node's controller + node-local address in its
        properties. Controllers are also seeded automatically from the listener.
      </p>
      {controllers.map((c, i) => (
        <div key={i} style={{ border: '1px solid #eee', padding: '0.4rem', marginBottom: '0.4rem', fontSize: 13 }}>
          <Field label={<>Code <InfoTip text="Unique identifier for this controller (PLC). Nodes reference it via their Controller (PLC) code field." example="PLC-01" /></>} value={c.code} onChange={(v) => update(i, { code: v })} />
          <Field label={<>Name <InfoTip text="Optional human-readable name for the controller, for display only." example="Sorter PLC east" /></>} value={c.name ?? ''} onChange={(v) => update(i, { name: v || null })} />
          <Field label={<>IP address <InfoTip text="Network address the WCS uses to reach this controller's listener." example="192.168.10.21" /></>} value={c.ipAddress} onChange={(v) => update(i, { ipAddress: v })} />
          <Field label={<>Port <InfoTip text="TCP port on the controller for the WCS connection. Leave blank to use the listener's default." example="5001" /></>} value={String(c.port ?? '')} onChange={(v) => update(i, { port: v ? Number(v) : null })} />
          <button onClick={() => setControllers(controllers.filter((_, idx) => idx !== i))}>Remove</button>
        </div>
      ))}
      <button onClick={() => setControllers(controllers.concat({ code: 'PLC', name: null, ipAddress: '', port: null }))}>
        Add controller
      </button>
    </div>
  )
}

function Field(props: { label: ReactNode; value: string; onChange: (v: string) => void }) {
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
          <Field label={<>Code <InfoTip text="Unique identifier for this recirculation loop. Nodes join it via their Loop code field." example="LOOP-A" /></>} value={l.code} onChange={(v) => update(i, { code: v })} />
          <Field label={<>Max HUs <InfoTip text="Maximum number of handling units (loads/totes) allowed circulating on this loop before it counts as full." example="10" /></>} value={String(l.maxHus)} onChange={(v) => update(i, { maxHus: Number(v) || 0 })} />
          <label style={{ display: 'block', marginBottom: '0.5rem' }}>
            <div style={{ color: 'var(--text-dim,#666)' }}>When full <InfoTip text="What happens when the loop reaches Max HUs: HOLD stops feeding new loads upstream; OVERFLOW diverts them to the overflow target node." example="OVERFLOW" /></div>
            <Select
              ariaLabel="When full"
              value={l.whenFull}
              onChange={(v) => update(i, { whenFull: v })}
              options={[
                { value: 'HOLD', label: 'HOLD' },
                { value: 'OVERFLOW', label: 'OVERFLOW' },
              ]}
            />
          </label>
          {l.whenFull === 'OVERFLOW' && (
            <Field label={<>Overflow target node <InfoTip text="Node code that loads are diverted to when the loop is full and When-full is OVERFLOW." example="N-BUFFER" /></>} value={l.overflowTarget ?? ''} onChange={(v) => update(i, { overflowTarget: v || null })} />
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
