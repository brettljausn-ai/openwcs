import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Select from '../ui/Select'
import InfoTip from '../ui/InfoTip'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useSidebar } from '../shell/SidebarContext'
import { HandlingUnit, listHandlingUnits } from '../inventory/api'
import { HandlingUnitType, Sku, listHandlingUnitTypes, listSkus } from '../masterdata/api'
import {
  OperatingMode,
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

// Remembered workstation (per device): so an operator who always runs the same station can have it
// auto-opened on arrival. We keep both id and code so we can show the code without a lookup.
const REMEMBER_KEY = 'openwcs.gtp.rememberedStation'

interface RememberedStation {
  stationId: string
  code: string
}

function readRemembered(): RememberedStation | null {
  try {
    const raw = localStorage.getItem(REMEMBER_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as RememberedStation
    if (parsed && typeof parsed.stationId === 'string' && parsed.stationId) return parsed
  } catch {
    /* corrupt value, treat as none */
  }
  return null
}

function writeRemembered(s: RememberedStation): void {
  try {
    localStorage.setItem(REMEMBER_KEY, JSON.stringify(s))
  } catch {
    /* storage unavailable, best effort */
  }
}

function clearRemembered(): void {
  try {
    localStorage.removeItem(REMEMBER_KEY)
  } catch {
    /* ignore */
  }
}

// Pick the default active mode for a multi-mode workplace: prefer PICKING (the only mode with a
// guided flow today), otherwise the first supported mode.
function defaultMode(modes: OperatingMode[]): OperatingMode {
  return modes.includes('PICKING') ? 'PICKING' : modes[0]
}

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
  const [remembered, setRemembered] = useState<RememberedStation | null>(() => readRemembered())
  const autoTried = useRef(false)

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

  function forget() {
    clearRemembered()
    setRemembered(null)
  }

  // Auto-open the remembered station once (per Launcher mount), after workplaces have loaded so we
  // can confirm it still exists. If the claim fails or the station is gone, clear the key and fall
  // back to the launcher so the operator is never stuck.
  useEffect(() => {
    if (autoTried.current || !remembered) return
    if (loading || workplaces.length === 0) return
    autoTried.current = true
    const match = workplaces.find((w) => w.id === remembered.stationId)
    if (!match) {
      forget()
      return
    }
    let cancelled = false
    setOpening(match.id)
    claimWorkplace(match.id)
      .then((s) => {
        if (!cancelled) onOpen(s)
      })
      .catch(() => {
        if (cancelled) return
        forget()
        setOpening(null)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workplaces, loading, remembered])

  return (
    <div className="app-content">
      <div className="page-head">
        <h1>GTP workplaces</h1>
        <p>
          Goods-to-person operator consoles. Open a workplace to claim it — only one operator can run
          a workplace at a time, so opening it elsewhere takes over this session.
        </p>
      </div>

      {remembered && (
        <div
          className="glass"
          style={{
            padding: '.75rem 1rem',
            marginBottom: '1.25rem',
            display: 'flex',
            alignItems: 'center',
            gap: '.75rem',
            flexWrap: 'wrap',
          }}
        >
          <span style={{ color: 'var(--text-dim)', fontSize: '.9rem' }}>
            This device remembers workstation <strong style={{ color: 'var(--text)' }}>{remembered.code}</strong> and opens it automatically.
          </span>
          <button className="btn btn-ghost btn-sm" style={{ marginLeft: 'auto' }} onClick={forget}>
            Forget this device's workstation
          </button>
        </div>
      )}

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
  const modes = workplace.supportedModes
  const [activeMode, setActiveMode] = useState<OperatingMode>(() => defaultMode(modes))
  const [remembered, setRemembered] = useState<boolean>(() => readRemembered()?.stationId === stationId)

  const takenOverRef = useRef(false)

  // Focus screen: collapse the app sidebar while the console is mounted, restore the prior choice on
  // exit (captured once on mount so we don't fight the operator's manual toggles in between).
  const { collapsed, setCollapsed } = useSidebar()
  const collapsedOnMount = useRef(collapsed)
  useEffect(() => {
    collapsedOnMount.current = collapsed
    setCollapsed(true)
    return () => setCollapsed(collapsedOnMount.current)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function toggleRemember(next: boolean) {
    setRemembered(next)
    if (next) writeRemembered({ stationId, code: workplace.code })
    else clearRemembered()
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
        <div style={{ display: 'flex', gap: '.75rem', alignItems: 'center', flexWrap: 'wrap' }}>
          <label
            style={{ display: 'flex', alignItems: 'center', gap: '.4rem', color: 'var(--text-dim)', fontSize: '.85rem', cursor: 'pointer' }}
            title="Auto-open this workstation on this device next time"
          >
            <input type="checkbox" checked={remembered} onChange={(e) => toggleRemember(e.target.checked)} />
            Remember this workstation on this device
          </label>
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

      {modes.length > 1 && (
        <div style={{ display: 'flex', gap: '.4rem', flexWrap: 'wrap', marginBottom: '1rem' }} role="group" aria-label="Operating mode">
          {modes.map((m) => {
            const on = m === activeMode
            return (
              <button
                key={m}
                type="button"
                className={`badge ${on ? 'badge-info' : ''}`}
                aria-pressed={on}
                onClick={() => setActiveMode(m)}
                style={{
                  cursor: 'pointer',
                  border: on ? '1px solid var(--herbal-lime)' : '1px solid var(--glass-border)',
                  background: on ? undefined : 'transparent',
                  color: on ? undefined : 'var(--text-dim)',
                  fontWeight: on ? 600 : 400,
                }}
              >
                {m}
              </button>
            )
          })}
        </div>
      )}

      {activeMode === 'PICKING' ? (
        cycle ? (
          <CycleView cycle={cycle} onChange={setCycle} warehouseId={workplace.warehouseId} />
        ) : (
          <PresentPanel workplace={workplace} onStarted={setCycle} />
        )
      ) : (
        <ModePlaceholder mode={activeMode} />
      )}
    </div>
  )
}

function ModePlaceholder({ mode }: { mode: OperatingMode }) {
  return (
    <div className="glass" style={{ padding: '2.5rem', maxWidth: 560, textAlign: 'center' }}>
      <div style={{ fontSize: '2rem', marginBottom: '.5rem' }}>🛠</div>
      <h2 style={{ marginTop: 0 }}>{mode} mode is active.</h2>
      <p style={{ color: 'var(--text-dim)', margin: 0 }}>Guided flow coming soon.</p>
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
  const [hus, setHus] = useState<HandlingUnit[]>([])
  const [skus, setSkus] = useState<Sku[]>([])

  // Load the warehouse's handling units and SKUs so the operator picks by code (not raw UUIDs).
  useEffect(() => {
    let cancelled = false
    listHandlingUnits(workplace.warehouseId)
      .then((list) => {
        if (!cancelled) setHus(list)
      })
      .catch(() => {
        if (!cancelled) setHus([])
      })
    listSkus()
      .then((list) => {
        if (!cancelled) setSkus(list)
      })
      .catch(() => {
        if (!cancelled) setSkus([])
      })
    return () => {
      cancelled = true
    }
  }, [workplace.warehouseId])

  const huOptions = useMemo(
    () =>
      hus
        .filter((h): h is HandlingUnit & { huId: string } => !!h.huId)
        .map((h) => ({ value: h.huId, label: h.code })),
    [hus],
  )
  const skuOptions = useMemo(
    () =>
      skus
        .filter((s): s is Sku & { id: string } => !!s.id)
        .map((s) => ({ value: s.id, label: s.code + (s.description ? ' (' + s.description + ')' : '') })),
    [skus],
  )

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
        <Field label={<>Stock HU <InfoTip text="The stock handling unit (carton/tote/pallet) presented at the station to be distributed across the open destinations. Pick it by its code." example="HU-000123" /></>}>
          <Select
            ariaLabel="Stock HU"
            value={stockHuId}
            onChange={(v) => setStockHuId(v)}
            options={huOptions}
            placeholder="Select a handling unit…"
          />
        </Field>
        <Field label={<>SKU <InfoTip text="The article contained in the presented stock HU. Its open demand drives which put-to-light tasks are generated. Pick it by its code." example="SKU-1001" /></>}>
          <Select
            ariaLabel="SKU"
            value={skuId}
            onChange={(v) => setSkuId(v)}
            options={skuOptions}
            placeholder="Select a SKU…"
          />
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

function CycleView({
  cycle,
  onChange,
  warehouseId,
}: {
  cycle: WorkCycle
  onChange: (c: WorkCycle | null) => void
  warehouseId: string
}) {
  const openPuts = cycle.puts.filter((p) => p.status === 'OPEN')
  const done = cycle.puts.length > 0 && openPuts.length === 0
  const [error, setError] = useState<string | null>(null)

  // The active put is the next OPEN instruction; we visualise its destination tote.
  const activePut = openPuts[0] ?? null
  const activeIndex = activePut ? cycle.puts.findIndex((p) => p.id === activePut.id) : -1

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

      {activePut && (
        <ToteView
          put={activePut}
          putIndex={activeIndex < 0 ? 0 : activeIndex}
          skuId={cycle.skuId}
          warehouseId={warehouseId}
        />
      )}

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

// --- Tote top-view: the focal element of the active cycle -----------------------------------------
// Renders a top-down graphic of the destination tote for the active put. The tote's compartment
// count comes from its HU type (orderHuId -> huTypeId -> compartments); we highlight the active
// compartment in lime. The backend does not expose which compartment is the target, so we pick one
// deterministically (active put index modulo compartments) and label it clearly.
function ToteView({
  put,
  putIndex,
  skuId,
  warehouseId,
}: {
  put: PutInstruction
  putIndex: number
  skuId: string | null
  warehouseId: string
}) {
  const [hus, setHus] = useState<HandlingUnit[]>([])
  const [huTypes, setHuTypes] = useState<HandlingUnitType[]>([])
  const [skus, setSkus] = useState<Sku[]>([])

  useEffect(() => {
    let cancelled = false
    listHandlingUnits(warehouseId)
      .then((l) => !cancelled && setHus(l))
      .catch(() => !cancelled && setHus([]))
    listHandlingUnitTypes()
      .then((l) => !cancelled && setHuTypes(l))
      .catch(() => !cancelled && setHuTypes([]))
    listSkus()
      .then((l) => !cancelled && setSkus(l))
      .catch(() => !cancelled && setSkus([]))
    return () => {
      cancelled = true
    }
  }, [warehouseId])

  const destHu = useMemo(() => hus.find((h) => h.huId === put.orderHuId) ?? null, [hus, put.orderHuId])
  const huType = useMemo(
    () => (destHu?.huTypeId ? huTypes.find((t) => t.id === destHu.huTypeId) ?? null : null),
    [huTypes, destHu],
  )
  const sku = useMemo(() => (skuId ? skus.find((s) => s.id === skuId) ?? null : null), [skus, skuId])

  // Compartments clamp to 1..8 (single-compartment totes show one cell). Default to 1 when unknown.
  const compartments = Math.max(1, Math.min(8, huType?.compartments ?? 1))
  // Deterministic active compartment (backend exposes no target slot): stable per put.
  const activeCompartment = (putIndex % compartments) + 1
  const { cols, rows } = gridFor(compartments)

  return (
    <div
      className="glass"
      style={{
        padding: '1.5rem',
        display: 'flex',
        gap: '1.75rem',
        flexWrap: 'wrap',
        alignItems: 'center',
        borderColor: 'rgba(141, 198, 63, .35)',
      }}
    >
      <div>
        <div style={{ fontSize: '.7rem', textTransform: 'uppercase', letterSpacing: '.1em', color: 'var(--text-faint)', marginBottom: '.5rem' }}>
          Destination tote {destHu ? `· ${destHu.code}` : ''}
        </div>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: `repeat(${cols}, 1fr)`,
            gridTemplateRows: `repeat(${rows}, 1fr)`,
            gap: 6,
            width: Math.min(360, 84 * cols),
            aspectRatio: `${cols} / ${rows}`,
            padding: 8,
            borderRadius: 12,
            border: '2px solid var(--glass-border)',
            background: 'rgba(255,255,255,.03)',
          }}
        >
          {Array.from({ length: compartments }, (_, i) => {
            const slot = i + 1
            const on = slot === activeCompartment
            return (
              <div
                key={slot}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  borderRadius: 8,
                  fontWeight: 600,
                  fontSize: '.95rem',
                  minHeight: 56,
                  color: on ? '#0b0f0a' : 'var(--text-dim)',
                  background: on ? 'var(--herbal-lime)' : 'rgba(255,255,255,.04)',
                  border: on ? '2px solid var(--herbal-lime)' : '1px solid var(--glass-border)',
                  boxShadow: on ? '0 0 18px rgba(141, 198, 63, .45)' : 'none',
                }}
              >
                {slot}
              </div>
            )
          })}
        </div>
        <div style={{ marginTop: '.5rem', fontSize: '.8rem', color: 'var(--text-dim)' }}>
          Put into compartment <strong style={{ color: 'var(--herbal-lime)' }}>{activeCompartment}</strong>
          {compartments > 1 ? ` of ${compartments}` : ''}
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '.6rem', minWidth: 200 }}>
        <span className="eyebrow">Active put</span>
        {sku?.imageUrl && (
          <img
            src={sku.imageUrl}
            alt={sku.code}
            style={{ width: 132, height: 132, objectFit: 'cover', borderRadius: 12, border: '1px solid var(--glass-border)' }}
            onError={(e) => {
              ;(e.currentTarget as HTMLImageElement).style.display = 'none'
            }}
          />
        )}
        <div style={{ fontSize: '1.1rem', fontWeight: 600 }}>{sku ? sku.code : 'SKU'}</div>
        {sku?.description && <div style={{ color: 'var(--text-dim)', fontSize: '.85rem' }}>{sku.description}</div>}
        <div style={{ display: 'flex', alignItems: 'baseline', gap: '.6rem', marginTop: '.25rem' }}>
          <span style={{ fontSize: '.8rem', textTransform: 'uppercase', letterSpacing: '.08em', color: 'var(--text-faint)' }}>PUT</span>
          <span style={{ fontSize: '2.4rem', fontWeight: 700, color: 'var(--herbal-lime)', lineHeight: 1 }}>{put.qty}</span>
        </div>
        <div style={{ color: 'var(--text-dim)', fontSize: '.8rem' }}>
          Order {put.orderRef}
          {put.putLightId ? ` · Light ${put.putLightId}` : ''}
        </div>
      </div>
    </div>
  )
}

// Lay out 1..8 compartments in a sensible top-view grid (cols x rows).
function gridFor(n: number): { cols: number; rows: number } {
  switch (n) {
    case 1: return { cols: 1, rows: 1 }
    case 2: return { cols: 2, rows: 1 }
    case 3: return { cols: 3, rows: 1 }
    case 4: return { cols: 2, rows: 2 }
    case 5:
    case 6: return { cols: 3, rows: 2 }
    case 7:
    case 8: return { cols: 4, rows: 2 }
    default: return { cols: 4, rows: 2 }
  }
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
