import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Workplace,
  WorkplaceSession,
  WorkCycle,
  PutInstruction,
  claimWorkplace,
  closeCycle,
  confirmPut,
  heartbeat,
  listWorkplaces,
  presentStock,
  releaseWorkplace,
} from './api'

const HEARTBEAT_MS = 4000

// GTP operator console (ADR 0006). A launcher lists goods-to-person workplaces; opening one CLAIMS
// a single-active session for that workplace and shows the operator console (present a stock HU ->
// put-to-light tasks -> confirm puts). The console heartbeats; if the server reports the session was
// SUPERSEDED (the same workplace was opened elsewhere) it closes immediately with a clear takeover
// message. The session is released on unmount/close.
export default function GtpOpsScreen() {
  const [session, setSession] = useState<WorkplaceSession | null>(null)
  const [takenOver, setTakenOver] = useState(false)

  // Reset the takeover banner when leaving the console back to the launcher.
  const openWorkplace = useCallback((claimed: WorkplaceSession) => {
    setTakenOver(false)
    setSession(claimed)
  }, [])

  const leaveConsole = useCallback(() => {
    setSession(null)
    setTakenOver(false)
  }, [])

  if (session) {
    return (
      <OperatorConsole
        session={session}
        takenOver={takenOver}
        onTakenOver={() => setTakenOver(true)}
        onLeave={leaveConsole}
      />
    )
  }
  return <Launcher onOpen={openWorkplace} />
}

// --- Launcher: pick a workplace -------------------------------------------------------------------

function Launcher({ onOpen }: { onOpen: (s: WorkplaceSession) => void }) {
  const [warehouseId, setWarehouseId] = useState('')
  const [workplaces, setWorkplaces] = useState<Workplace[]>([])
  const [loading, setLoading] = useState(false)
  const [opening, setOpening] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function load() {
    if (!warehouseId.trim()) return
    setLoading(true)
    setError(null)
    try {
      setWorkplaces(await listWorkplaces(warehouseId.trim()))
    } catch (e) {
      setError(String(e instanceof Error ? e.message : e))
      setWorkplaces([])
    } finally {
      setLoading(false)
    }
  }

  async function open(w: Workplace) {
    setOpening(w.id)
    setError(null)
    try {
      onOpen(await claimWorkplace(w.id))
    } catch (e) {
      setError(String(e instanceof Error ? e.message : e))
      setOpening(null)
    }
  }

  return (
    <div className="app-content">
      <div className="page-head">
        <h1>GTP workplaces</h1>
        <p>
          Goods-to-person operator consoles. Open a workplace to claim it — only one operator can run
          a workplace at a time, so opening it elsewhere takes over this session.
        </p>
      </div>

      <div className="glass" style={{ padding: '1.25rem', marginBottom: '1.25rem' }}>
        <div style={{ display: 'flex', gap: '.75rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: '.35rem', flex: '1 1 340px' }}>
            <span className="eyebrow">Warehouse</span>
            <input
              className="form-control"
              value={warehouseId}
              placeholder="warehouse UUID"
              onChange={(e) => setWarehouseId(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') load()
              }}
            />
          </label>
          <button className="btn btn-primary" onClick={load} disabled={!warehouseId.trim() || loading}>
            {loading ? 'Loading…' : 'Load workplaces'}
          </button>
        </div>
        {error && (
          <p className="badge badge-danger" style={{ marginTop: '.9rem' }}>
            {error}
          </p>
        )}
      </div>

      {workplaces.length > 0 ? (
        <div
          style={{
            display: 'grid',
            gap: '1rem',
            gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))',
          }}
        >
          {workplaces.map((w) => (
            <WorkplaceCard key={w.id} workplace={w} opening={opening === w.id} onOpen={() => open(w)} />
          ))}
        </div>
      ) : (
        !loading &&
        warehouseId.trim() && (
          <p style={{ color: 'var(--text-dim)' }}>No GTP workplaces configured in this warehouse.</p>
        )
      )}
    </div>
  )
}

function WorkplaceCard({
  workplace,
  opening,
  onOpen,
}: {
  workplace: Workplace
  opening: boolean
  onOpen: () => void
}) {
  const orderNodes = workplace.nodes.filter((n) => n.role === 'ORDER').length
  const stockNodes = workplace.nodes.filter((n) => n.role === 'STOCK').length
  return (
    <div className="glass" style={{ padding: '1.25rem', display: 'flex', flexDirection: 'column', gap: '.75rem' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '.5rem' }}>
        <h3 style={{ margin: 0, fontSize: '1.3rem' }}>{workplace.code}</h3>
        <span className={`badge ${workplace.inUse ? 'badge-warning' : 'badge-success'}`}>
          {workplace.inUse ? 'In use' : 'Free'}
        </span>
      </div>
      <div style={{ display: 'flex', gap: '.4rem', flexWrap: 'wrap' }}>
        <span className="badge badge-info">{workplace.mode === 'PUT_WALL' ? 'Put-wall' : 'Order locations'}</span>
        {workplace.supportedModes.map((m) => (
          <span key={m} className="badge">
            {m}
          </span>
        ))}
      </div>
      <p style={{ margin: 0, color: 'var(--text-dim)', fontSize: '.85rem' }}>
        {stockNodes} stock · {orderNodes} destinations
      </p>
      <button className="btn btn-primary btn-lg btn-block" onClick={onOpen} disabled={opening}>
        {opening ? 'Opening…' : workplace.inUse ? 'Take over & open' : 'Open workplace'}
      </button>
    </div>
  )
}

// --- Operator console: claimed session ------------------------------------------------------------

function OperatorConsole({
  session,
  takenOver,
  onTakenOver,
  onLeave,
}: {
  session: WorkplaceSession
  takenOver: boolean
  onTakenOver: () => void
  onLeave: () => void
}) {
  const { stationId, sessionId } = { stationId: session.stationId, sessionId: session.sessionId }
  const workplace = session.workplace
  const [cycle, setCycle] = useState<WorkCycle | null>(null)

  const takenOverRef = useRef(false)

  // Heartbeat loop: keep the session alive and detect a takeover (superseded) / release.
  useEffect(() => {
    let cancelled = false
    const timer = setInterval(async () => {
      try {
        const status = await heartbeat(stationId, sessionId)
        if (!cancelled && !status.active) {
          takenOverRef.current = true
          onTakenOver()
        }
      } catch {
        /* transient network error — try again next tick */
      }
    }, HEARTBEAT_MS)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [stationId, sessionId, onTakenOver])

  // Release on unmount / page close — unless we were already taken over (that session is closed).
  useEffect(() => {
    const onUnload = () => {
      if (!takenOverRef.current) releaseWorkplace(stationId, sessionId)
    }
    window.addEventListener('pagehide', onUnload)
    return () => {
      window.removeEventListener('pagehide', onUnload)
      if (!takenOverRef.current) releaseWorkplace(stationId, sessionId)
    }
  }, [stationId, sessionId])

  if (takenOver) {
    return (
      <div className="app-content">
        <div
          className="glass"
          style={{
            padding: '2.5rem',
            maxWidth: 560,
            margin: '3rem auto',
            textAlign: 'center',
            borderColor: 'rgba(255, 107, 94, .4)',
          }}
        >
          <div style={{ fontSize: '2.5rem', marginBottom: '.5rem' }}>⚠</div>
          <h2 style={{ marginTop: 0 }}>Session taken over</h2>
          <p style={{ color: 'var(--text-dim)' }}>
            This workplace (<strong>{workplace.code}</strong>) was opened in another window — this
            session was taken over and is no longer active. Any unconfirmed work here was not saved.
          </p>
          <button className="btn btn-primary btn-lg" onClick={onLeave}>
            Back to workplaces
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="app-content">
      <div className="page-head" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '1rem', flexWrap: 'wrap' }}>
        <div>
          <h1>{workplace.code}</h1>
          <p>
            Operator console · {workplace.mode === 'PUT_WALL' ? 'put-wall' : 'order locations'} ·
            session live
          </p>
        </div>
        <div style={{ display: 'flex', gap: '.5rem', alignItems: 'center' }}>
          <span className="badge badge-success">Active</span>
          <button
            className="btn btn-ghost"
            onClick={() => {
              releaseWorkplace(stationId, sessionId)
              takenOverRef.current = true // suppress the unmount release (already released)
              onLeave()
            }}
          >
            Release & exit
          </button>
        </div>
      </div>

      {cycle ? (
        <CycleView cycle={cycle} onChange={setCycle} />
      ) : (
        <PresentPanel workplace={workplace} onStarted={setCycle} />
      )}
    </div>
  )
}

// Present a stock HU to start a PICKING cycle (the put-to-light batch).
function PresentPanel({ workplace, onStarted }: { workplace: Workplace; onStarted: (c: WorkCycle) => void }) {
  const stockNodes = workplace.nodes.filter((n) => n.role === 'STOCK')
  const [stockNodeId, setStockNodeId] = useState(stockNodes[0]?.id ?? '')
  const [stockHuId, setStockHuId] = useState('')
  const [skuId, setSkuId] = useState('')
  const [qty, setQty] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function present() {
    setBusy(true)
    setError(null)
    try {
      const cycle = await presentStock(workplace.id, {
        stockNodeId: stockNodeId || null,
        stockHuId: stockHuId.trim(),
        skuId: skuId.trim(),
        qty: Number(qty),
      })
      onStarted(cycle)
    } catch (e) {
      setError(String(e instanceof Error ? e.message : e))
    } finally {
      setBusy(false)
    }
  }

  const ready = stockHuId.trim() && skuId.trim() && Number(qty) > 0
  return (
    <div className="glass" style={{ padding: '1.5rem', maxWidth: 640 }}>
      <span className="eyebrow">Present stock</span>
      <p style={{ color: 'var(--text-dim)', marginTop: '.5rem' }}>
        Scan a stock HU presented at the station to build its put-to-light list across the open
        destinations.
      </p>
      <div style={{ display: 'grid', gap: '.9rem', marginTop: '1rem' }}>
        {stockNodes.length > 1 && (
          <Field label="Stock node">
            <select className="form-control" value={stockNodeId} onChange={(e) => setStockNodeId(e.target.value)}>
              {stockNodes.map((n) => (
                <option key={n.id} value={n.id}>
                  {n.code}
                </option>
              ))}
            </select>
          </Field>
        )}
        <Field label="Stock HU">
          <input className="form-control" value={stockHuId} onChange={(e) => setStockHuId(e.target.value)} placeholder="HU UUID" />
        </Field>
        <Field label="SKU">
          <input className="form-control" value={skuId} onChange={(e) => setSkuId(e.target.value)} placeholder="SKU UUID" />
        </Field>
        <Field label="Quantity">
          <input className="form-control" type="number" min="0" value={qty} onChange={(e) => setQty(e.target.value)} placeholder="0" />
        </Field>
      </div>
      {error && (
        <p className="badge badge-danger" style={{ marginTop: '.9rem' }}>
          {error}
        </p>
      )}
      <button className="btn btn-primary btn-lg btn-block" style={{ marginTop: '1.1rem' }} onClick={present} disabled={!ready || busy}>
        {busy ? 'Presenting…' : 'Present & light puts'}
      </button>
    </div>
  )
}

function CycleView({ cycle, onChange }: { cycle: WorkCycle; onChange: (c: WorkCycle | null) => void }) {
  const openPuts = cycle.puts.filter((p) => p.status === 'OPEN')
  const done = cycle.puts.length > 0 && openPuts.length === 0
  const [error, setError] = useState<string | null>(null)

  async function confirm(p: PutInstruction, qty?: number) {
    setError(null)
    try {
      const updated = await confirmPut(p.id, qty)
      onChange({ ...cycle, puts: cycle.puts.map((x) => (x.id === p.id ? updated : x)) })
    } catch (e) {
      setError(String(e instanceof Error ? e.message : e))
    }
  }

  async function finish() {
    setError(null)
    try {
      await closeCycle(cycle.id)
      onChange(null)
    } catch (e) {
      setError(String(e instanceof Error ? e.message : e))
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
      <div className="glass" style={{ padding: '1.1rem 1.4rem', display: 'flex', gap: '2rem', flexWrap: 'wrap', alignItems: 'center' }}>
        <Stat label="Operating mode" value={cycle.operatingMode} />
        <Stat label="Remaining stock" value={cycle.remainingQty == null ? '—' : String(cycle.remainingQty)} />
        <Stat label="Puts" value={`${cycle.puts.length - openPuts.length}/${cycle.puts.length}`} />
        <span className={`badge ${done ? 'badge-success' : 'badge-info'}`} style={{ marginLeft: 'auto' }}>
          {cycle.status}
        </span>
      </div>

      {error && <p className="badge badge-danger">{error}</p>}

      {cycle.puts.length === 0 ? (
        <p style={{ color: 'var(--text-dim)' }}>No matching demand — nothing to put for this stock HU.</p>
      ) : (
        <div style={{ display: 'grid', gap: '.85rem', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))' }}>
          {cycle.puts.map((p) => (
            <PutCard key={p.id} put={p} onConfirm={confirm} />
          ))}
        </div>
      )}

      <button className="btn btn-ghost btn-lg" style={{ alignSelf: 'flex-start' }} onClick={finish}>
        {done ? 'Finish & close cycle' : 'Close cycle (send HU away)'}
      </button>
    </div>
  )
}

function PutCard({ put, onConfirm }: { put: PutInstruction; onConfirm: (p: PutInstruction, qty?: number) => void }) {
  const [short, setShort] = useState('')
  const open = put.status === 'OPEN'
  const badge =
    put.status === 'CONFIRMED'
      ? 'badge-success'
      : put.status === 'SHORT'
        ? 'badge-warning'
        : put.status === 'CANCELLED'
          ? 'badge-danger'
          : 'badge-info'
  return (
    <div
      className="glass"
      style={{ padding: '1.1rem', display: 'flex', flexDirection: 'column', gap: '.6rem', opacity: open ? 1 : 0.7 }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <strong style={{ fontSize: '1.05rem' }}>{put.orderRef}</strong>
        <span className={`badge ${badge}`}>{put.status}</span>
      </div>
      <div style={{ fontSize: '2.2rem', fontWeight: 600, color: 'var(--herbal-lime)', lineHeight: 1 }}>
        {put.qty}
      </div>
      <p style={{ margin: 0, color: 'var(--text-dim)', fontSize: '.8rem' }}>
        {put.putLightId ? `Light ${put.putLightId}` : 'Destination'}
        {put.orderHuId ? ` · HU ${put.orderHuId.slice(0, 8)}` : ''}
      </p>
      {open && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '.5rem', marginTop: '.25rem' }}>
          <button className="btn btn-primary btn-lg btn-block" onClick={() => onConfirm(put)}>
            Confirm put ({put.qty})
          </button>
          <div style={{ display: 'flex', gap: '.4rem' }}>
            <input
              className="form-control"
              type="number"
              min="0"
              max={put.qty}
              value={short}
              placeholder="short qty"
              onChange={(e) => setShort(e.target.value)}
            />
            <button
              className="btn btn-ghost"
              disabled={!(Number(short) > 0 && Number(short) < put.qty)}
              onClick={() => onConfirm(put, Number(short))}
            >
              Short
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

// --- small helpers --------------------------------------------------------------------------------

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: '.35rem' }}>
      <span style={{ fontSize: '.8rem', color: 'var(--text-dim)' }}>{label}</span>
      {children}
    </label>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '.2rem' }}>
      <span style={{ fontSize: '.7rem', textTransform: 'uppercase', letterSpacing: '.1em', color: 'var(--text-faint)' }}>
        {label}
      </span>
      <span style={{ fontSize: '1.1rem' }}>{value}</span>
    </div>
  )
}
