import { useCallback, useEffect, useRef, useState } from 'react'
import Select from '../ui/Select'
import InfoTip from '../ui/InfoTip'
import { useWarehouse } from '../warehouse/WarehouseContext'
import {
  Workplace,
  WorkplaceSession,
  WorkCycle,
  PutInstruction,
  StationQueueEntry,
  activateStation,
  claimWorkplace,
  completeQueueEntry,
  closeCycle,
  confirmPut,
  deactivateStation,
  getStationQueue,
  heartbeat,
  listWorkplaces,
  presentStock,
  releaseWorkplace,
} from './api'

const HEARTBEAT_MS = 4000
const QUEUE_POLL_MS = 3000

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
  const { currentWarehouseId: warehouseId } = useWarehouse()
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

  // Auto-load workplaces whenever the active warehouse changes (load() early-returns when blank).
  useEffect(() => {
    if (warehouseId) load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [warehouseId])

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

      {!warehouseId.trim() && (
        <p style={{ color: 'var(--text-dim)', marginBottom: '1.25rem' }}>
          Select a warehouse in the top bar to load its GTP workplaces.
        </p>
      )}

      {error && (
        <p className="badge badge-danger" style={{ marginBottom: '1.25rem' }}>
          {error}
        </p>
      )}

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

  // Drain switch. Seed from the workplace if it happens to carry an acceptingWork flag, else default
  // to accepting work. A deactivated station finishes its queued totes but takes no new ones.
  const [acceptingWork, setAcceptingWork] = useState<boolean>(
    seedAcceptingWork(workplace),
  )
  const [drainBusy, setDrainBusy] = useState(false)
  const [drainError, setDrainError] = useState<string | null>(null)

  const takenOverRef = useRef(false)

  async function toggleAccepting() {
    setDrainBusy(true)
    setDrainError(null)
    try {
      const res = acceptingWork ? await deactivateStation(stationId) : await activateStation(stationId)
      setAcceptingWork(res.acceptingWork)
    } catch (e) {
      setDrainError(String(e instanceof Error ? e.message : e))
    } finally {
      setDrainBusy(false)
    }
  }

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
          <span className={`badge ${acceptingWork ? 'badge-success' : 'badge-warning'}`}>
            {acceptingWork ? 'Active' : 'Draining'}
          </span>
          <button
            className="btn btn-ghost"
            onClick={toggleAccepting}
            disabled={drainBusy}
            title={
              acceptingWork
                ? 'Stop taking new totes; finish the queued work already at this station'
                : 'Resume taking new totes at this station'
            }
          >
            {drainBusy ? 'Working…' : acceptingWork ? 'Deactivate (drain)' : 'Activate'}
          </button>
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

      {drainError && <p className="badge badge-danger" style={{ marginBottom: '1rem' }}>{drainError}</p>}

      {!acceptingWork && (
        <div
          className="glass"
          style={{
            padding: '.9rem 1.2rem',
            marginBottom: '1rem',
            borderColor: 'rgba(255, 193, 94, .4)',
            display: 'flex',
            alignItems: 'center',
            gap: '.6rem',
          }}
        >
          <span style={{ fontSize: '1.3rem' }}>⏸</span>
          <span>
            <strong>Draining</strong> — finishing queued work, no new totes. Reactivate to resume
            taking new totes.
          </span>
        </div>
      )}

      <InboundQueuePanel stationId={stationId} />

      {cycle ? (
        <CycleView cycle={cycle} onChange={setCycle} />
      ) : (
        <PresentPanel workplace={workplace} onStarted={setCycle} />
      )}
    </div>
  )
}

// The physical tote queue at the station. Polls the station queue while the console is open and lets
// the operator work the head QUEUED tote off in arrival sequence with a Done button.
function InboundQueuePanel({ stationId }: { stationId: string }) {
  const [entries, setEntries] = useState<StationQueueEntry[]>([])
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [completing, setCompleting] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())

  const refresh = useCallback(async () => {
    try {
      setEntries(await getStationQueue(stationId))
      setError(null)
    } catch (e) {
      setError(String(e instanceof Error ? e.message : e))
    } finally {
      setLoaded(true)
    }
  }, [stationId])

  // Poll the queue every ~3s while mounted; clean up on unmount.
  useEffect(() => {
    let cancelled = false
    const run = () => {
      if (!cancelled) refresh()
    }
    run()
    const timer = setInterval(run, QUEUE_POLL_MS)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [refresh])

  // Tick a local clock every second so in-transit ETAs count down smoothly between polls.
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])

  async function complete(entry: StationQueueEntry) {
    setCompleting(entry.id)
    try {
      await completeQueueEntry(entry.id)
      await refresh()
    } catch (e) {
      setError(String(e instanceof Error ? e.message : e))
    } finally {
      setCompleting(null)
    }
  }

  // The head QUEUED entry is the one the operator should work next.
  const headId = entries.find((e) => e.status === 'QUEUED')?.id ?? null

  return (
    <div className="glass" style={{ padding: '1.25rem', marginBottom: '1.25rem' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '.6rem' }}>
        <span className="eyebrow">Inbound queue</span>
        <span className="badge badge-info">{entries.length} inbound</span>
      </div>

      {error && <p className="badge badge-danger" style={{ marginTop: '.8rem' }}>{error}</p>}

      {loaded && entries.length === 0 ? (
        <p style={{ color: 'var(--text-dim)', marginTop: '.8rem', marginBottom: 0 }}>
          No totes inbound — the station queue is clear.
        </p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '.55rem', marginTop: '.9rem' }}>
          {entries.map((entry, i) => (
            <QueueRow
              key={entry.id}
              entry={entry}
              position={i + 1}
              isHead={entry.id === headId}
              now={now}
              completing={completing === entry.id}
              onComplete={() => complete(entry)}
            />
          ))}
        </div>
      )}
    </div>
  )
}

function QueueRow({
  entry,
  position,
  isHead,
  now,
  completing,
  onComplete,
}: {
  entry: StationQueueEntry
  position: number
  isHead: boolean
  now: number
  completing: boolean
  onComplete: () => void
}) {
  const inTransit = entry.status === 'IN_TRANSIT'
  const etaSeconds = Math.max(0, Math.round((new Date(entry.arrivalAt).getTime() - now) / 1000))
  const statusText = inTransit ? `arriving in ${etaSeconds}s` : 'waiting'
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: '.85rem',
        padding: '.65rem .85rem',
        borderRadius: 10,
        border: isHead ? '1px solid var(--herbal-lime)' : '1px solid var(--border, rgba(255,255,255,.08))',
        background: isHead ? 'rgba(168, 230, 108, .08)' : 'rgba(255,255,255,.02)',
        opacity: inTransit ? 0.7 : 1,
      }}
    >
      <span
        style={{
          fontSize: '.85rem',
          color: 'var(--text-faint)',
          width: '1.6rem',
          textAlign: 'right',
          flexShrink: 0,
        }}
      >
        {position}
      </span>
      <div style={{ display: 'flex', flexDirection: 'column', gap: '.15rem', minWidth: 0, flex: 1 }}>
        <div style={{ display: 'flex', gap: '.5rem', alignItems: 'baseline', flexWrap: 'wrap' }}>
          <strong style={{ fontSize: '1rem' }}>{entry.huCode}</strong>
          <span style={{ color: 'var(--text-dim)', fontSize: '.85rem' }}>{entry.skuCode}</span>
          <span style={{ color: 'var(--herbal-lime)', fontWeight: 600, fontSize: '.9rem' }}>×{entry.qty}</span>
        </div>
        <div style={{ display: 'flex', gap: '.4rem', alignItems: 'center', flexWrap: 'wrap' }}>
          <span className="badge">{entry.mode}</span>
          <span className={`badge ${inTransit ? 'badge-info' : isHead ? 'badge-success' : 'badge-warning'}`}>
            {statusText}
          </span>
          {isHead && <span style={{ fontSize: '.75rem', color: 'var(--herbal-lime)' }}>next</span>}
        </div>
      </div>
      {isHead && (
        <button className="btn btn-primary" onClick={onComplete} disabled={completing} style={{ flexShrink: 0 }}>
          {completing ? 'Done…' : 'Done'}
        </button>
      )}
    </div>
  )
}

// A workplace may carry an acceptingWork flag at runtime; read it defensively without widening types.
function seedAcceptingWork(workplace: Workplace): boolean {
  const flag = (workplace as { acceptingWork?: unknown }).acceptingWork
  return typeof flag === 'boolean' ? flag : true
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
          <Field label={<>Stock node <InfoTip text="The station feed location where the presented stock handling unit sits. Only shown when the workplace has more than one stock node." example="STOCK-IN-1" /></>}>
            <Select
              ariaLabel="Stock node"
              value={stockNodeId}
              onChange={(v) => setStockNodeId(v)}
              options={stockNodes.map((n) => ({ value: n.id, label: n.code }))}
            />
          </Field>
        )}
        <Field label={<>Stock HU <InfoTip text="Identifier of the stock handling unit (carton/tote/pallet) presented at the station to be distributed across the open destinations." example="a1b2c3d4-5e6f-7890-abcd-ef1234567890" /></>}>
          <input className="form-control" value={stockHuId} onChange={(e) => setStockHuId(e.target.value)} placeholder="HU UUID" />
        </Field>
        <Field label={<>SKU <InfoTip text="The article contained in the presented stock HU. Its open demand drives which put-to-light tasks are generated." example="3f9a7c2b-1d4e-4a6f-8b3c-0e5d2f1a9c8b" /></>}>
          <input className="form-control" value={skuId} onChange={(e) => setSkuId(e.target.value)} placeholder="SKU UUID" />
        </Field>
        <Field label={<>Quantity <InfoTip text="How many units of this SKU are available in the presented stock HU to distribute across the puts." example="24" /></>}>
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
          <div style={{ display: 'flex', gap: '.4rem', alignItems: 'center' }}>
            <input
              className="form-control"
              type="number"
              min="0"
              max={put.qty}
              value={short}
              placeholder="short qty"
              onChange={(e) => setShort(e.target.value)}
            />
            <InfoTip text="Enter the actual quantity put when you cannot complete the full amount — confirms a short put for the remaining units. Must be less than the requested quantity." example="2" />
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

function Field({ label, children }: { label: React.ReactNode; children: React.ReactNode }) {
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
