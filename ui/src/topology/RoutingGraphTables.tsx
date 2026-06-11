import { useCallback, useEffect, useMemo, useState } from 'react'
import DataTable, { type Column } from '../ui/DataTable'
import Select from '../ui/Select'
import InfoTip from '../ui/InfoTip'
import { useWarehouse } from '../warehouse/WarehouseContext'
import {
  discoverTopology,
  loadTopology,
  saveTopology,
  type ControllerDto,
  type EdgeDto,
  type LoopDto,
  type NodeDto,
  type Topology,
} from './api'

// Table-based inspector for the routing graph. The node/edge graph is a PROJECTION generated from
// the 2D/3D placement ("Generate routing" replaces it wholesale), so hand-editing nodes/edges here
// is pointless: those two tables are read-only. Loops and controllers are operator policy (not
// projection output) and stay editable. Save PUTs the full topology (the backend replaces the whole
// graph), so it re-sends the loaded nodes/edges unchanged together with the edited loops/controllers.
// Discover keeps the old semantics: observed-but-unconfigured nodes/edges from the learning endpoint
// are appended (badged "discovered") for review, then persisted with Save.

type NodeRow = NodeDto & { inDeg: number; outDeg: number; discovered: boolean }
type EdgeRow = EdgeDto & { id: string; discovered: boolean }

function fmtPos(v: number | null | undefined): string {
  return v === null || v === undefined ? '—' : String(Math.round(v * 100) / 100)
}

export default function RoutingGraphTables() {
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const [nodes, setNodes] = useState<NodeDto[]>([])
  const [edges, setEdges] = useState<EdgeDto[]>([])
  const [loops, setLoops] = useState<LoopDto[]>([])
  const [controllers, setControllers] = useState<ControllerDto[]>([])
  // Codes/keys appended by Discover (not yet persisted) so the tables can badge them.
  const [discoveredNodes, setDiscoveredNodes] = useState<Set<string>>(new Set())
  const [discoveredEdges, setDiscoveredEdges] = useState<Set<string>>(new Set())
  const [dirty, setDirty] = useState(false)
  const [status, setStatus] = useState('')

  const load = useCallback(async () => {
    if (!warehouseId) {
      setStatus('No active warehouse selected')
      return
    }
    try {
      const t = await loadTopology(warehouseId)
      setNodes(t.nodes)
      setEdges(t.edges)
      setLoops(t.loops)
      setControllers(t.controllers)
      setDiscoveredNodes(new Set())
      setDiscoveredEdges(new Set())
      setDirty(false)
      setStatus(`Loaded ${t.nodes.length} nodes, ${t.edges.length} edges, ${t.loops.length} loops, ${t.controllers.length} controllers`)
    } catch (err) {
      setStatus(String(err))
    }
  }, [warehouseId])

  // (Re)load topology for the globally-active warehouse whenever it changes.
  useEffect(() => {
    load()
  }, [load])

  // Full-graph replace: nodes/edges go back exactly as loaded (plus any discovery additions),
  // only loops/controllers carry user edits.
  const save = useCallback(async () => {
    if (!warehouseId) {
      setStatus('No active warehouse selected')
      return
    }
    const topology: Topology = { nodes, edges, loops, controllers }
    try {
      await saveTopology(warehouseId, topology)
      setDiscoveredNodes(new Set())
      setDiscoveredEdges(new Set())
      setDirty(false)
      setStatus('Saved')
    } catch (err) {
      setStatus(String(err))
    }
  }, [warehouseId, nodes, edges, loops, controllers])

  // Learning: pull discovered (observed-but-unconfigured) nodes/edges into the tables to review.
  const runDiscovery = useCallback(async () => {
    if (!warehouseId) {
      setStatus('No active warehouse selected')
      return
    }
    try {
      const d = await discoverTopology(warehouseId)
      const existing = new Set(nodes.map((n) => n.code))
      const newNodes = d.nodes.filter((n) => !n.known && !existing.has(n.code))
      const nodeDtos: NodeDto[] = newNodes.map((n) => ({
        code: n.code,
        name: null,
        hardwareAddress: n.sourceIp ?? null,
        posX: null,
        posY: null,
        loopCode: null,
        controllerCode: null,
        nodeAddress: null,
      }))
      const codes = new Set<string>([...existing, ...newNodes.map((n) => n.code)])
      const have = new Set(edges.map((e) => `${e.fromCode} ${e.toCode}`))
      const newEdges: EdgeDto[] = d.edges
        .filter((e) => !e.known && codes.has(e.fromCode) && codes.has(e.toCode) && !have.has(`${e.fromCode} ${e.toCode}`))
        .map((e) => ({ fromCode: e.fromCode, toCode: e.toCode, exitCode: 'discovered', cost: 1 }))
      setNodes((ns) => ns.concat(nodeDtos))
      setEdges((es) => es.concat(newEdges))
      setDiscoveredNodes((s) => new Set([...s, ...nodeDtos.map((n) => n.code)]))
      setDiscoveredEdges((s) => new Set([...s, ...newEdges.map((e) => `${e.fromCode} ${e.toCode}`)]))
      if (nodeDtos.length || newEdges.length) setDirty(true)
      setStatus(`Discovered ${nodeDtos.length} new node(s) and ${newEdges.length} new edge(s); review and Save`)
    } catch (err) {
      setStatus(String(err))
    }
  }, [warehouseId, nodes, edges])

  // In/out degree per node, for spotting dead-ends (out-degree 0) and orphans at a glance.
  const nodeRows = useMemo<NodeRow[]>(() => {
    const inDeg = new Map<string, number>()
    const outDeg = new Map<string, number>()
    for (const e of edges) {
      outDeg.set(e.fromCode, (outDeg.get(e.fromCode) ?? 0) + 1)
      inDeg.set(e.toCode, (inDeg.get(e.toCode) ?? 0) + 1)
    }
    return nodes.map((n) => ({
      ...n,
      inDeg: inDeg.get(n.code) ?? 0,
      outDeg: outDeg.get(n.code) ?? 0,
      discovered: discoveredNodes.has(n.code),
    }))
  }, [nodes, edges, discoveredNodes])

  const edgeRows = useMemo<EdgeRow[]>(
    () => edges.map((e, i) => ({
      ...e,
      id: `${i}:${e.fromCode}->${e.toCode}:${e.exitCode}`,
      discovered: discoveredEdges.has(`${e.fromCode} ${e.toCode}`),
    })),
    [edges, discoveredEdges],
  )

  const nodeColumns: Column<NodeRow>[] = [
    {
      key: 'code',
      header: 'Code',
      sortable: true,
      render: (r) => (
        <>
          <span style={{ fontFamily: 'var(--font-mono)' }}>{r.code}</span>
          {r.discovered && <span className="badge badge-info" style={{ marginLeft: '.5rem' }}>discovered</span>}
        </>
      ),
    },
    { key: 'name', header: 'Name', sortable: true, sortValue: (r) => r.name ?? '', render: (r) => r.name ?? <span className="muted">—</span> },
    {
      key: 'pos',
      header: 'Position (x, y)',
      render: (r) => <span className="muted" style={{ fontFamily: 'var(--font-mono)' }}>{fmtPos(r.posX)}, {fmtPos(r.posY)}</span>,
    },
    { key: 'loopCode', header: 'Loop', sortable: true, sortValue: (r) => r.loopCode ?? '', render: (r) => r.loopCode ?? <span className="muted">—</span> },
    {
      key: 'controller',
      header: 'Controller / address',
      sortValue: (r) => r.controllerCode ?? r.hardwareAddress ?? '',
      render: (r) => {
        const ctl = r.controllerCode ? `${r.controllerCode}${r.nodeAddress ? ` @ ${r.nodeAddress}` : ''}` : null
        if (ctl) return ctl
        if (r.hardwareAddress) return <span className="muted">{r.hardwareAddress}</span>
        return <span className="muted">—</span>
      },
    },
    {
      key: 'degree',
      header: 'In / out',
      align: 'center',
      sortable: true,
      sortValue: (r) => r.outDeg,
      render: (r) => (
        <span style={{ fontFamily: 'var(--font-mono)' }}>
          {r.inDeg} / {r.outDeg === 0 ? <span className="badge badge-danger" title="No outgoing edge: loads routed here cannot leave (dead end)">0 dead end</span> : r.outDeg}
        </span>
      ),
    },
  ]

  const edgeColumns: Column<EdgeRow>[] = [
    {
      key: 'segment',
      header: 'Segment',
      sortable: true,
      sortValue: (r) => `${r.fromCode} ${r.toCode}`,
      render: (r) => (
        <span style={{ fontFamily: 'var(--font-mono)' }}>
          {r.fromCode} <span className="muted">→</span> {r.toCode}
          {r.discovered && <span className="badge badge-info" style={{ marginLeft: '.5rem' }}>discovered</span>}
        </span>
      ),
    },
    { key: 'exitCode', header: 'Exit code', sortable: true, render: (r) => <span style={{ fontFamily: 'var(--font-mono)' }}>{r.exitCode}</span> },
    { key: 'cost', header: 'Cost', align: 'right', sortable: true, sortValue: (r) => r.cost ?? 1, render: (r) => String(r.cost ?? 1) },
  ]

  const markLoops = (l: LoopDto[]) => { setLoops(l); setDirty(true) }
  const markControllers = (c: ControllerDto[]) => { setControllers(c); setDirty(true) }

  return (
    <div className="rg-inspector">
      <div className="glass card-pad rg-head">
        <div className="rg-head-row">
          <div className="rg-counts">
            <span className="badge badge-info">{nodes.length} nodes</span>
            <span className="badge badge-info">{edges.length} edges</span>
            <span className="badge badge-info">{loops.length} loops</span>
            <span className="badge badge-info">{controllers.length} controllers</span>
          </div>
          <div className="rg-actions">
            <button className="btn btn-ghost btn-sm" onClick={load}>Reload</button>
            <button className="btn btn-ghost btn-sm" onClick={runDiscovery} title="Pull observed-but-unconfigured nodes/edges from learning">
              Discover
            </button>
            <button className={`btn btn-sm ${dirty ? 'btn-primary' : 'btn-outline'}`} onClick={save}>Save</button>
          </div>
        </div>
        <p className="rg-note">
          This graph is generated from the 3D layout (Generate routing), so nodes and edges are
          read-only here: regenerate in the 3D layout tab after layout changes. Loops and controllers
          are operator policy and stay editable; Save re-sends the graph unchanged with your edits.
        </p>
        {status && <div className="muted rg-status">{status}</div>}
      </div>

      <section className="glass card-pad">
        <h2 className="rg-section-title">Nodes <span className="muted rg-ro">read-only</span></h2>
        <DataTable
          columns={nodeColumns}
          rows={nodeRows}
          rowKey={(r) => r.code}
          search={(r) => `${r.code} ${r.name ?? ''}`}
          searchPlaceholder="Filter by node code…"
          initialSort={{ key: 'code', dir: 'asc' }}
          empty="No nodes. Generate routing from the 3D layout tab."
        />
      </section>

      <section className="glass card-pad">
        <h2 className="rg-section-title">Edges <span className="muted rg-ro">read-only</span></h2>
        <DataTable
          columns={edgeColumns}
          rows={edgeRows}
          rowKey={(r) => r.id}
          search={(r) => `${r.fromCode} ${r.toCode}`}
          searchPlaceholder="Filter by node code (either end)…"
          initialSort={{ key: 'segment', dir: 'asc' }}
          empty="No edges. Generate routing from the 3D layout tab."
        />
      </section>

      <section className="glass card-pad">
        <h2 className="rg-section-title">Loops</h2>
        <p className="muted rg-hint">
          Recirculation loops are operator policy (capacity and overflow behaviour), not projection
          output, so they remain editable here. Nodes join a loop via their loop code.
        </p>
        <LoopsTable loops={loops} setLoops={markLoops} />
      </section>

      <section className="glass card-pad">
        <h2 className="rg-section-title">Controllers (PLCs)</h2>
        <p className="muted rg-hint">
          One PLC (IP:port) can host many nodes; a node references its controller via the controller
          code plus a node-local address. Controllers are also seeded automatically from the listener.
        </p>
        <ControllersTable controllers={controllers} setControllers={markControllers} />
      </section>

      <style>{`
        .rg-inspector { display: flex; flex-direction: column; gap: 1rem; }
        .rg-head-row { display: flex; align-items: center; gap: 1rem; flex-wrap: wrap; }
        .rg-counts { display: flex; gap: .5rem; flex-wrap: wrap; }
        .rg-actions { margin-left: auto; display: flex; gap: .5rem; }
        .rg-note { margin: .75rem 0 0; font-size: .85rem; color: var(--text-dim); }
        .rg-status { margin-top: .5rem; font-size: .8rem; }
        .rg-section-title { margin: 0 0 .75rem; font-size: 1.05rem; }
        .rg-ro { font-size: .7rem; font-weight: 400; text-transform: uppercase; letter-spacing: .06em; margin-left: .5rem; }
        .rg-hint { margin: 0 0 .75rem; font-size: .8rem; }
        .rg-edit-table input.form-control, .rg-edit-table .select-trigger { font-size: .85rem; }
        .rg-edit-table td { vertical-align: middle; }
        .rg-edit-table input.form-control { padding: .4rem .6rem; }
      `}</style>
    </div>
  )
}

// Editable loops table: code, capacity and the when-full policy, with the overflow target shown
// only for OVERFLOW. Small lists, so a plain app-styled table (no search/sort) keeps editing simple.
function LoopsTable(props: { loops: LoopDto[]; setLoops: (l: LoopDto[]) => void }) {
  const { loops, setLoops } = props
  const update = (i: number, patch: Partial<LoopDto>) =>
    setLoops(loops.map((l, idx) => (idx === i ? { ...l, ...patch } : l)))
  return (
    <div className="data-table rg-edit-table">
      <div className="data-table-scroll">
        <table>
          <thead>
            <tr>
              <th>Code <InfoTip text="Unique identifier for this recirculation loop. Nodes join it via their Loop code field." example="LOOP-A" /></th>
              <th>Max HUs <InfoTip text="Maximum number of handling units (loads/totes) allowed circulating on this loop before it counts as full." example="10" /></th>
              <th>When full <InfoTip text="What happens when the loop reaches Max HUs: HOLD stops feeding new loads upstream; OVERFLOW diverts them to the overflow target node." example="OVERFLOW" /></th>
              <th>Overflow target <InfoTip text="Node code that loads are diverted to when the loop is full and When-full is OVERFLOW." example="N-BUFFER" /></th>
              <th style={{ width: '1%' }} />
            </tr>
          </thead>
          <tbody>
            {loops.length === 0 && (
              <tr>
                <td colSpan={5} className="muted" style={{ textAlign: 'center', padding: '1.25rem' }}>No loops defined.</td>
              </tr>
            )}
            {loops.map((l, i) => (
              <tr key={i}>
                <td><input className="form-control" value={l.code} onChange={(e) => update(i, { code: e.target.value })} /></td>
                <td style={{ width: 110 }}>
                  <input className="form-control" inputMode="numeric" value={String(l.maxHus)} onChange={(e) => update(i, { maxHus: Number(e.target.value) || 0 })} />
                </td>
                <td style={{ width: 160 }}>
                  <Select
                    ariaLabel="When full"
                    value={l.whenFull}
                    onChange={(v) => update(i, { whenFull: v })}
                    options={[
                      { value: 'HOLD', label: 'HOLD' },
                      { value: 'OVERFLOW', label: 'OVERFLOW' },
                    ]}
                  />
                </td>
                <td>
                  {l.whenFull === 'OVERFLOW'
                    ? <input className="form-control" value={l.overflowTarget ?? ''} onChange={(e) => update(i, { overflowTarget: e.target.value || null })} />
                    : <span className="muted">—</span>}
                </td>
                <td>
                  <button className="btn btn-ghost btn-sm" onClick={() => setLoops(loops.filter((_, idx) => idx !== i))}>Remove</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div>
        <button
          className="btn btn-outline btn-sm"
          onClick={() => setLoops(loops.concat({ code: 'LOOP', maxHus: 10, whenFull: 'HOLD', overflowTarget: null }))}
        >
          Add loop
        </button>
      </div>
    </div>
  )
}

// Editable controllers table, replacing the old side-panel cards (same fields: code, name, IP, port).
function ControllersTable(props: { controllers: ControllerDto[]; setControllers: (c: ControllerDto[]) => void }) {
  const { controllers, setControllers } = props
  const update = (i: number, patch: Partial<ControllerDto>) =>
    setControllers(controllers.map((c, idx) => (idx === i ? { ...c, ...patch } : c)))
  return (
    <div className="data-table rg-edit-table">
      <div className="data-table-scroll">
        <table>
          <thead>
            <tr>
              <th>Code <InfoTip text="Unique identifier for this controller (PLC). Nodes reference it via their controller code." example="PLC-01" /></th>
              <th>Name <InfoTip text="Optional human-readable name for the controller, for display only." example="Sorter PLC east" /></th>
              <th>IP address <InfoTip text="Network address the WCS uses to reach this controller's listener." example="192.168.10.21" /></th>
              <th>Port <InfoTip text="TCP port on the controller for the WCS connection. Leave blank to use the listener's default." example="5001" /></th>
              <th style={{ width: '1%' }} />
            </tr>
          </thead>
          <tbody>
            {controllers.length === 0 && (
              <tr>
                <td colSpan={5} className="muted" style={{ textAlign: 'center', padding: '1.25rem' }}>No controllers defined.</td>
              </tr>
            )}
            {controllers.map((c, i) => (
              <tr key={i}>
                <td><input className="form-control" value={c.code} onChange={(e) => update(i, { code: e.target.value })} /></td>
                <td><input className="form-control" value={c.name ?? ''} onChange={(e) => update(i, { name: e.target.value || null })} /></td>
                <td><input className="form-control" value={c.ipAddress} onChange={(e) => update(i, { ipAddress: e.target.value })} /></td>
                <td style={{ width: 110 }}>
                  <input className="form-control" inputMode="numeric" value={String(c.port ?? '')} onChange={(e) => update(i, { port: e.target.value ? Number(e.target.value) : null })} />
                </td>
                <td>
                  <button className="btn btn-ghost btn-sm" onClick={() => setControllers(controllers.filter((_, idx) => idx !== i))}>Remove</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div>
        <button
          className="btn btn-outline btn-sm"
          onClick={() => setControllers(controllers.concat({ code: 'PLC', name: null, ipAddress: '', port: null }))}
        >
          Add controller
        </button>
      </div>
    </div>
  )
}
