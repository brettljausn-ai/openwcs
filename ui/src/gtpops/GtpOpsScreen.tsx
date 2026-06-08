import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
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
  markProductBroken,
  markToteDirty,
  presentStock,
  releaseWorkplace,
  submitStationCount,
} from './api'
import { useDemoMode } from '../demo/useDemoMode'

const HEARTBEAT_MS = 4000
const QUEUE_POLL_MS = 3000

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

// Remember the operator's chosen mode per station on this device so a reload keeps it.
const MODE_KEY = 'openwcs.gtp.mode'

function readMode(stationId: string, modes: OperatingMode[]): OperatingMode {
  try {
    const saved = localStorage.getItem(`${MODE_KEY}.${stationId}`)
    if (saved && modes.includes(saved as OperatingMode)) return saved as OperatingMode
  } catch {
    /* storage unavailable */
  }
  return defaultMode(modes)
}

function writeMode(stationId: string, mode: OperatingMode): void {
  try {
    localStorage.setItem(`${MODE_KEY}.${stationId}`, mode)
  } catch {
    /* best effort */
  }
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
  const [activeMode, setActiveMode] = useState<OperatingMode>(() => readMode(stationId, modes))
  function chooseMode(mode: OperatingMode) {
    setActiveMode(mode)
    writeMode(stationId, mode)
  }
  const [remembered, setRemembered] = useState<boolean>(() => readRemembered()?.stationId === stationId)

  // --- Inbound queue (lifted from the old top panel). We poll the station queue here so the console
  // can auto-present the arrived head tote, and pass the entries down to the right-side drawer.
  const [queue, setQueue] = useState<StationQueueEntry[]>([])
  const [queueLoaded, setQueueLoaded] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [exceptionsOpen, setExceptionsOpen] = useState(false)
  const [presentError, setPresentError] = useState<string | null>(null)

  // The head tote = first QUEUED entry (a tote that has physically arrived and waits to be worked).
  const head = useMemo(() => queue.find((e) => e.status === 'QUEUED') ?? null, [queue])
  const inboundCount = queue.length

  // Guard so we present each head exactly once: holds the entry id we have already presented (or are
  // presenting). Cleared when the cycle closes so the next head auto-presents.
  const presentedIdRef = useRef<string | null>(null)
  // Guards a single in-flight present call (the poll fires every 3s; don't stack presents).
  const presentingRef = useRef(false)

  const refreshQueue = useCallback(async () => {
    try {
      setQueue(await getStationQueue(stationId))
    } catch {
      /* transient — keep the last good snapshot, try again next tick */
    } finally {
      setQueueLoaded(true)
    }
  }, [stationId])

  useEffect(() => {
    let cancelled = false
    const run = () => {
      if (!cancelled) refreshQueue()
    }
    run()
    const timer = setInterval(run, QUEUE_POLL_MS)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [refreshQueue])

  // Auto-present the arrived head tote for PICKING: when no cycle is active and we have not already
  // presented this head, start a cycle from it. The presented-id ref makes this run once per head.
  useEffect(() => {
    if (activeMode !== 'PICKING') return
    if (cycle) return
    if (!head) return
    if (presentedIdRef.current === head.id) return
    if (presentingRef.current) return
    presentingRef.current = true
    presentedIdRef.current = head.id
    setPresentError(null)
    let cancelled = false
    presentStock(stationId, { stockNodeId: null, stockHuId: head.huId, skuId: head.skuId, qty: head.qty })
      .then((c) => {
        if (!cancelled) setCycle(c)
      })
      .catch((e) => {
        // No open demand in the demo, etc. Surface it but still show the tote so the operator can
        // mark it done (which completes the entry and advances). Keep the presented-id set so we do
        // not loop re-presenting the same failing head every tick.
        if (!cancelled) setPresentError(String(e instanceof Error ? e.message : e))
      })
      .finally(() => {
        presentingRef.current = false
      })
    return () => {
      cancelled = true
    }
  }, [activeMode, cycle, head, stationId])

  // Complete the current head entry and reset the present guards so the next QUEUED head auto-presents.
  const completeHeadAndAdvance = useCallback(async () => {
    const id = head?.id ?? presentedIdRef.current
    setCycle(null)
    presentedIdRef.current = null
    setPresentError(null)
    if (id) {
      try {
        await completeQueueEntry(id)
      } catch {
        /* best effort; the next poll reconciles the queue */
      }
    }
    await refreshQueue()
  }, [head, refreshQueue])

  // When a PICKING cycle is closed (CycleView signals onChange(null)), complete + advance.
  const handleCycleChange = useCallback(
    (c: WorkCycle | null) => {
      if (c) {
        setCycle(c)
        return
      }
      completeHeadAndAdvance()
    },
    [completeHeadAndAdvance],
  )

  // Drain switch. Seed from the workplace if it happens to carry an acceptingWork flag, else default
  // to accepting work. A deactivated station finishes its queued totes but takes no new ones.
  const [acceptingWork, setAcceptingWork] = useState<boolean>(
    seedAcceptingWork(workplace),
  )
  const [drainBusy, setDrainBusy] = useState(false)
  const [drainError, setDrainError] = useState<string | null>(null)

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
        <div style={{ display: 'flex', gap: '.75rem', alignItems: 'center', flexWrap: 'wrap' }}>
          <label
            style={{ display: 'flex', alignItems: 'center', gap: '.4rem', color: 'var(--text-dim)', fontSize: '.85rem', cursor: 'pointer' }}
            title="Auto-open this workstation on this device next time"
          >
            <input type="checkbox" checked={remembered} onChange={(e) => toggleRemember(e.target.checked)} />
            Remember this workstation on this device
          </label>
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
                onClick={() => chooseMode(m)}
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
            <strong>Draining:</strong> finishing queued work, no new totes. Reactivate to resume
            taking new totes.
          </span>
        </div>
      )}

      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 'calc(100vh - 16rem)' }}>
      {activeMode === 'PICKING' ? (
        head || cycle ? (
          <>
            <ActiveTotePanel
              head={head}
              cycle={cycle}
              warehouseId={workplace.warehouseId}
              error={presentError}
              fill={!cycle}
            />
            {cycle && (
              <CycleView cycle={cycle} onChange={handleCycleChange} warehouseId={workplace.warehouseId} />
            )}
            {!cycle && presentError && (
              <button
                className="btn btn-primary btn-lg"
                style={{ alignSelf: 'flex-start' }}
                onClick={completeHeadAndAdvance}
              >
                Mark tote done & advance
              </button>
            )}
          </>
        ) : (
          <WaitingForTotes loaded={queueLoaded} />
        )
      ) : activeMode === 'STOCK_COUNT' ? (
        head ? (
          head.countTaskId && head.countLineId ? (
            <CountPanel
              key={head.id}
              head={head}
              warehouseId={workplace.warehouseId}
              onCounted={completeHeadAndAdvance}
            />
          ) : (
            <>
              <ActiveTotePanel head={head} cycle={null} warehouseId={workplace.warehouseId} error={null} fill />
              <button
                className="btn btn-primary btn-lg"
                style={{ alignSelf: 'flex-start' }}
                onClick={completeHeadAndAdvance}
              >
                Done counting
              </button>
            </>
          )
        ) : (
          <WaitingForTotes loaded={queueLoaded} />
        )
      ) : (
        <ModePlaceholder mode={activeMode} />
      )}
      </div>

      <QueueDrawer
        entries={queue}
        headId={head?.id ?? null}
        count={inboundCount}
        open={drawerOpen}
        onToggle={() => setDrawerOpen((v) => !v)}
      />

      <ExceptionsDrawer
        stationId={stationId}
        head={head}
        open={exceptionsOpen}
        onToggle={() => setExceptionsOpen((v) => !v)}
        onToteRemoved={() => {
          setExceptionsOpen(false)
          completeHeadAndAdvance()
        }}
      />
    </div>
  )
}

// --- Exceptions drawer: a second right-edge fold-out, stacked below the Queue handle --------------
// Folded by default; opens over a backdrop like QueueDrawer. Shown in every work mode while a session
// is live. Two operator-raised actions on the current head tote: mark the tote dirty (sent to
// maintenance, then advance) or mark some units broken (damage adjustment, tote stays in place).
function ExceptionsDrawer({
  stationId,
  head,
  open,
  onToggle,
  onToteRemoved,
}: {
  stationId: string
  head: StationQueueEntry | null
  open: boolean
  onToggle: () => void
  onToteRemoved: () => void
}) {
  const hasHead = head != null

  const [dirtyBusy, setDirtyBusy] = useState(false)
  const [dirtyError, setDirtyError] = useState<string | null>(null)
  const [dirtyNote, setDirtyNote] = useState<string | null>(null)

  const [brokenOpen, setBrokenOpen] = useState(false)
  const [brokenQty, setBrokenQty] = useState('')
  const [brokenBusy, setBrokenBusy] = useState(false)
  const [brokenError, setBrokenError] = useState<string | null>(null)
  const [brokenNote, setBrokenNote] = useState<string | null>(null)

  // Digits-only quantity, no spinner (mirrors the count panel's input). 1..N where N is on the tote.
  const parsedBroken = Number(brokenQty)
  const validBroken =
    brokenQty.trim() !== '' && Number.isInteger(parsedBroken) && parsedBroken > 0

  async function onDirty() {
    if (!head || dirtyBusy) return
    setDirtyBusy(true)
    setDirtyError(null)
    setDirtyNote(null)
    try {
      await markToteDirty(stationId, head.id)
      setDirtyNote('Tote sent to maintenance.')
      onToteRemoved()
    } catch (e) {
      setDirtyError(String(e instanceof Error ? e.message : e))
    } finally {
      setDirtyBusy(false)
    }
  }

  async function onBroken() {
    if (!head || !validBroken || brokenBusy) return
    setBrokenBusy(true)
    setBrokenError(null)
    setBrokenNote(null)
    try {
      const res = await markProductBroken(stationId, head.id, parsedBroken)
      setBrokenNote(`Adjusted ${res.adjusted} damaged unit(s).`)
      setBrokenQty('')
    } catch (e) {
      setBrokenError(String(e instanceof Error ? e.message : e))
    } finally {
      setBrokenBusy(false)
    }
  }

  return (
    <>
      {/* Slim handle pinned to the right edge, stacked below the Queue handle so the two don't overlap. */}
      {!open && (
        <button
          type="button"
          onClick={onToggle}
          aria-label="Exceptions"
          style={{
            position: 'fixed',
            top: 'calc(50% + 120px)',
            right: 0,
            transform: 'translateY(-50%)',
            zIndex: 40,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: '.4rem',
            padding: '.9rem .55rem',
            border: '1px solid var(--herbal-lime)',
            borderRight: 'none',
            borderRadius: '12px 0 0 12px',
            background: 'rgba(14, 20, 12, .85)',
            backdropFilter: 'blur(10px)',
            color: 'var(--text)',
            cursor: 'pointer',
            writingMode: 'vertical-rl',
            fontSize: '.85rem',
            fontWeight: 600,
            letterSpacing: '.04em',
            boxShadow: '0 0 18px rgba(141, 198, 63, .25)',
          }}
        >
          <span style={{ transform: 'rotate(180deg)' }}>Exceptions</span>
        </button>
      )}

      {/* Backdrop + sliding panel. */}
      {open && (
        <div
          onClick={onToggle}
          style={{
            position: 'fixed',
            inset: 0,
            zIndex: 45,
            background: 'rgba(0, 0, 0, .4)',
          }}
        />
      )}
      <aside
        aria-hidden={!open}
        style={{
          position: 'fixed',
          top: 0,
          right: 0,
          bottom: 0,
          zIndex: 46,
          width: 'min(380px, 92vw)',
          transform: open ? 'translateX(0)' : 'translateX(100%)',
          transition: 'transform .25s ease',
          background: 'rgba(14, 20, 12, .96)',
          backdropFilter: 'blur(14px)',
          borderLeft: '1px solid var(--herbal-lime)',
          boxShadow: '-12px 0 40px rgba(0, 0, 0, .45)',
          display: 'flex',
          flexDirection: 'column',
          padding: '1.25rem',
          overflowY: 'auto',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '.6rem', marginBottom: '1rem' }}>
          <span className="eyebrow">Exceptions</span>
          <button className="btn btn-ghost btn-sm" onClick={onToggle} aria-label="Close exceptions">
            ✕
          </button>
        </div>

        {hasHead ? (
          <div style={{ marginBottom: '1rem', color: 'var(--text-dim)', fontSize: '.85rem' }}>
            Current tote <strong style={{ color: 'var(--text)' }}>{head?.huCode ?? 'Tote'}</strong>
            {head?.skuCode ? ` · ${head.skuCode}` : ''}
          </div>
        ) : (
          <p className="badge badge-warning" style={{ marginBottom: '1rem' }}>
            No tote at the station.
          </p>
        )}

        {/* Mark tote as dirty */}
        <div
          className="glass"
          style={{ padding: '1rem', display: 'flex', flexDirection: 'column', gap: '.6rem', marginBottom: '1rem' }}
        >
          <strong style={{ fontSize: '1.05rem' }}>Mark tote as dirty</strong>
          <p style={{ margin: 0, color: 'var(--text-dim)', fontSize: '.85rem' }}>
            Sends this tote to maintenance and advances to the next tote.
          </p>
          <button
            className="btn btn-primary btn-block"
            disabled={!hasHead || dirtyBusy}
            onClick={onDirty}
          >
            {dirtyBusy ? 'Working…' : 'Mark tote as dirty'}
          </button>
          {dirtyNote && (
            <p className="badge badge-success" style={{ margin: 0 }}>
              {dirtyNote}
            </p>
          )}
          {dirtyError && (
            <p className="badge badge-danger" style={{ margin: 0 }}>
              {dirtyError}
            </p>
          )}
        </div>

        {/* Mark product as broken */}
        <div
          className="glass"
          style={{ padding: '1rem', display: 'flex', flexDirection: 'column', gap: '.6rem' }}
        >
          <strong style={{ fontSize: '1.05rem' }}>Mark product as broken</strong>
          <p style={{ margin: 0, color: 'var(--text-dim)', fontSize: '.85rem' }}>
            Posts a damage adjustment for the broken units. The tote stays so you keep working it.
          </p>
          {!brokenOpen ? (
            <button
              className="btn btn-ghost btn-block"
              disabled={!hasHead}
              onClick={() => {
                setBrokenOpen(true)
                setBrokenError(null)
                setBrokenNote(null)
              }}
            >
              Mark product as broken
            </button>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '.6rem' }}>
              <div style={{ display: 'flex', gap: '.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
                <input
                  className="form-control"
                  type="text"
                  inputMode="numeric"
                  pattern="[0-9]*"
                  value={brokenQty}
                  placeholder="Broken qty"
                  disabled={brokenBusy || !hasHead}
                  autoFocus
                  onChange={(e) => setBrokenQty(e.target.value.replace(/[^0-9]/g, ''))}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') onBroken()
                  }}
                  style={{ fontSize: '1.2rem', width: 140, padding: '.5rem .7rem' }}
                />
                <button
                  className="btn btn-primary"
                  disabled={!hasHead || !validBroken || brokenBusy}
                  onClick={onBroken}
                >
                  {brokenBusy ? 'Sending…' : 'Send adjustment'}
                </button>
              </div>
            </div>
          )}
          {brokenNote && (
            <p className="badge badge-success" style={{ margin: 0 }}>
              {brokenNote}
            </p>
          )}
          {brokenError && (
            <p className="badge badge-danger" style={{ margin: 0 }}>
              {brokenError}
            </p>
          )}
        </div>
      </aside>
    </>
  )
}

// Calm idle state shown when no tote is at the station and nothing is in progress.
function WaitingForTotes({ loaded }: { loaded: boolean }) {
  return (
    <div
      className="glass"
      style={{
        flex: 1,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        textAlign: 'center',
        padding: '3rem',
        minHeight: '60vh',
      }}
    >
      <div style={{ fontSize: '4rem', marginBottom: '1rem' }}>📦</div>
      <h2 style={{ marginTop: 0, fontSize: '2rem' }}>Waiting for totes</h2>
      <p style={{ color: 'var(--text-dim)', margin: 0, fontSize: '1.05rem' }}>
        {loaded
          ? 'None inbound at this station yet.'
          : 'Checking the station queue…'}
      </p>
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

// --- Inbound queue drawer: a right-side fold-out, folded by default -------------------------------
// A slim handle on the right edge shows the inbound count; clicking folds the panel out from the
// right over a backdrop. It lists the inbound totes in arrival order with the head highlighted.
function QueueDrawer({
  entries,
  headId,
  count,
  open,
  onToggle,
}: {
  entries: StationQueueEntry[]
  headId: string | null
  count: number
  open: boolean
  onToggle: () => void
}) {
  const [now, setNow] = useState(() => Date.now())

  // Tick a local clock every second so in-transit ETAs count down smoothly between polls.
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])

  return (
    <>
      {/* Slim handle pinned to the right edge. Hidden behind the panel when open. */}
      {!open && (
        <button
          type="button"
          onClick={onToggle}
          aria-label={`Inbound queue, ${count} totes`}
          style={{
            position: 'fixed',
            top: '50%',
            right: 0,
            transform: 'translateY(-50%)',
            zIndex: 40,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: '.4rem',
            padding: '.9rem .55rem',
            border: '1px solid var(--herbal-lime)',
            borderRight: 'none',
            borderRadius: '12px 0 0 12px',
            background: 'rgba(14, 20, 12, .85)',
            backdropFilter: 'blur(10px)',
            color: 'var(--text)',
            cursor: 'pointer',
            writingMode: 'vertical-rl',
            fontSize: '.85rem',
            fontWeight: 600,
            letterSpacing: '.04em',
            boxShadow: '0 0 18px rgba(141, 198, 63, .25)',
          }}
        >
          <span style={{ transform: 'rotate(180deg)' }}>Queue ({count})</span>
        </button>
      )}

      {/* Backdrop + sliding panel. */}
      {open && (
        <div
          onClick={onToggle}
          style={{
            position: 'fixed',
            inset: 0,
            zIndex: 45,
            background: 'rgba(0, 0, 0, .4)',
          }}
        />
      )}
      <aside
        aria-hidden={!open}
        style={{
          position: 'fixed',
          top: 0,
          right: 0,
          bottom: 0,
          zIndex: 46,
          width: 'min(380px, 92vw)',
          transform: open ? 'translateX(0)' : 'translateX(100%)',
          transition: 'transform .25s ease',
          background: 'rgba(14, 20, 12, .96)',
          backdropFilter: 'blur(14px)',
          borderLeft: '1px solid var(--herbal-lime)',
          boxShadow: '-12px 0 40px rgba(0, 0, 0, .45)',
          display: 'flex',
          flexDirection: 'column',
          padding: '1.25rem',
          overflowY: 'auto',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '.6rem', marginBottom: '1rem' }}>
          <span className="eyebrow">Inbound queue</span>
          <div style={{ display: 'flex', alignItems: 'center', gap: '.6rem' }}>
            <span className="badge badge-info">{count} inbound</span>
            <button className="btn btn-ghost btn-sm" onClick={onToggle} aria-label="Close inbound queue">
              ✕
            </button>
          </div>
        </div>

        {entries.length === 0 ? (
          <p style={{ color: 'var(--text-dim)', margin: 0 }}>
            No totes inbound. The station queue is clear.
          </p>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '.55rem' }}>
            {entries.map((entry, i) => (
              <QueueRow key={entry.id} entry={entry} position={i + 1} isHead={entry.id === headId} now={now} />
            ))}
          </div>
        )}
      </aside>
    </>
  )
}

function QueueRow({
  entry,
  position,
  isHead,
  now,
}: {
  entry: StationQueueEntry
  position: number
  isHead: boolean
  now: number
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
    </div>
  )
}

// Seed the drain switch from the station's real acceptingWork flag so a reload restores it.
function seedAcceptingWork(workplace: Workplace): boolean {
  return workplace.acceptingWork ?? true
}

// --- Active tote at the station: the focal element of the queue-driven console --------------------
// Shows the SKU that is "on the tote" so the operator reads what to do; they do not pick it. The HU
// code, qty and SKU come from the head queue entry; the SKU code/description/image are resolved from
// master data. When a cycle is running we prefer the cycle's HU/SKU/qty so the panel stays correct
// after the head entry has been worked off the queue.
function ActiveTotePanel({
  head,
  cycle,
  warehouseId,
  error,
  fill,
}: {
  head: StationQueueEntry | null
  cycle: WorkCycle | null
  warehouseId: string
  error: string | null
  fill?: boolean
}) {
  const [skus, setSkus] = useState<Sku[]>([])
  const [hus, setHus] = useState<HandlingUnit[]>([])

  useEffect(() => {
    let cancelled = false
    listSkus()
      .then((l) => !cancelled && setSkus(l))
      .catch(() => !cancelled && setSkus([]))
    listHandlingUnits(warehouseId)
      .then((l) => !cancelled && setHus(l))
      .catch(() => !cancelled && setHus([]))
    return () => {
      cancelled = true
    }
  }, [warehouseId])

  // Prefer the live cycle values once a cycle is running; otherwise read from the head entry.
  const skuId = cycle?.skuId ?? head?.skuId ?? null
  const huId = cycle?.stockHuId ?? head?.huId ?? null
  const qty = cycle?.presentedQty ?? head?.qty ?? null

  const sku = useMemo(() => (skuId ? skus.find((s) => s.id === skuId) ?? null : null), [skus, skuId])
  const huCode = useMemo(() => {
    if (head?.huId === huId && head?.huCode) return head.huCode
    const match = hus.find((h) => h.huId === huId)
    return match?.code ?? null
  }, [hus, huId, head])

  const skuCode = sku?.code ?? head?.skuCode ?? null

  return (
    <div
      className="glass"
      style={{
        padding: fill ? '2.5rem' : '1.5rem',
        marginBottom: '1.25rem',
        display: 'flex',
        gap: fill ? '2.5rem' : '1.75rem',
        flexWrap: 'wrap',
        alignItems: 'center',
        justifyContent: fill ? 'center' : 'flex-start',
        borderColor: 'rgba(141, 198, 63, .35)',
        ...(fill ? { flex: 1, minHeight: '60vh' } : {}),
      }}
    >
      {sku?.imageUrl && (
        <img
          src={sku.imageUrl}
          alt={skuCode ?? 'SKU'}
          style={{
            width: fill ? 240 : 132,
            height: fill ? 240 : 132,
            objectFit: 'cover',
            borderRadius: 12,
            border: '1px solid var(--glass-border)',
          }}
          onError={(e) => {
            ;(e.currentTarget as HTMLImageElement).style.display = 'none'
          }}
        />
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '.45rem', minWidth: 220, flex: 1 }}>
        <span className="eyebrow">Active tote at station</span>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: '.6rem', flexWrap: 'wrap' }}>
          <strong style={{ fontSize: '1.4rem' }}>{huCode ?? 'Tote'}</strong>
          {qty != null && (
            <span style={{ color: 'var(--herbal-lime)', fontWeight: 700, fontSize: '1.1rem' }}>×{qty}</span>
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: '.5rem', flexWrap: 'wrap' }}>
          <span style={{ fontSize: '1.1rem', fontWeight: 600 }}>{skuCode ?? 'SKU'}</span>
          {sku?.description && (
            <span style={{ color: 'var(--text-dim)', fontSize: '.9rem' }}>{sku.description}</span>
          )}
        </div>
        {qty != null && (
          <div style={{ color: 'var(--text-dim)', fontSize: '.85rem' }}>
            {qty} {qty === 1 ? 'unit' : 'units'} on the tote
          </div>
        )}
        {error && (
          <p className="badge badge-danger" style={{ marginTop: '.4rem', marginBottom: 0 }}>
            {error}
          </p>
        )}
      </div>
    </div>
  )
}

// --- At-station blind count (STOCK_COUNT) ---------------------------------------------------------
// A small pool of neutral product photos used only in demo mode when a SKU has no image of its own,
// so the count panel still has something to show. Picked deterministically per SKU code (below) so a
// given SKU always shows the same image across totes and reloads.
const DEMO_IMAGES: string[] = [
  'https://images.unsplash.com/photo-1553062407-98eeb64c6a62?auto=format&fit=crop&w=400&q=60',
  'https://images.unsplash.com/photo-1556909212-d5b604d0c90d?auto=format&fit=crop&w=400&q=60',
  'https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=400&q=60',
  'https://images.unsplash.com/photo-1523275335684-37898b6baf30?auto=format&fit=crop&w=400&q=60',
  'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=400&q=60',
  'https://images.unsplash.com/photo-1572635196237-14b3f281503f?auto=format&fit=crop&w=400&q=60',
]

// Stable, simple string hash so the same SKU code always maps to the same demo image.
function hashString(s: string): number {
  let h = 0
  for (let i = 0; i < s.length; i++) {
    h = (h * 31 + s.charCodeAt(i)) | 0
  }
  return Math.abs(h)
}

function demoImageFor(skuCode: string | null): string {
  if (!skuCode) return DEMO_IMAGES[0]
  return DEMO_IMAGES[hashString(skuCode) % DEMO_IMAGES.length]
}

// The focal full-screen element of STOCK_COUNT mode. Shows the tote barcode, the SKU (code + name)
// and an image, then takes a blind counted quantity. It never displays any quantity from the system
// (no expected, no "units on the tote"), and on a RECOUNT outcome it clears the input and keeps the
// same tote so the operator counts again without seeing their previous entry.
function CountPanel({
  head,
  warehouseId,
  onCounted,
}: {
  head: StationQueueEntry
  warehouseId: string
  onCounted: () => void
}) {
  const { enabled: demoEnabled } = useDemoMode()
  const [skus, setSkus] = useState<Sku[]>([])
  const [hus, setHus] = useState<HandlingUnit[]>([])

  useEffect(() => {
    let cancelled = false
    listSkus()
      .then((l) => !cancelled && setSkus(l))
      .catch(() => !cancelled && setSkus([]))
    listHandlingUnits(warehouseId)
      .then((l) => !cancelled && setHus(l))
      .catch(() => !cancelled && setHus([]))
    return () => {
      cancelled = true
    }
  }, [warehouseId])

  const sku = useMemo(() => skus.find((s) => s.id === head.skuId) ?? null, [skus, head.skuId])
  const huCode = useMemo(() => {
    if (head.huCode) return head.huCode
    return hus.find((h) => h.huId === head.huId)?.code ?? null
  }, [hus, head])

  const skuCode = sku?.code ?? head.skuCode ?? null
  const imageUrl = sku?.imageUrl ?? (demoEnabled ? demoImageFor(skuCode) : null)

  const [qtyText, setQtyText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<{ tone: 'ok' | 'recount'; text: string } | null>(null)

  const parsed = Number(qtyText)
  const validQty = qtyText.trim() !== '' && Number.isFinite(parsed) && parsed >= 0

  async function submit() {
    if (!validQty || busy) return
    if (!head.countTaskId || !head.countLineId) return
    setBusy(true)
    setError(null)
    try {
      const res = await submitStationCount(head.countTaskId, head.countLineId, parsed)
      if (res.outcome === 'RECOUNT') {
        // Stay blind: clear the input, keep the same tote, prompt the operator to count again.
        setQtyText('')
        setNote({ tone: 'recount', text: res.message })
      } else {
        // ACCEPTED or ADJUSTED: show the host message briefly, then complete + advance.
        setNote({ tone: 'ok', text: res.message })
        setQtyText('')
        onCounted()
      }
    } catch (e) {
      setError(String(e instanceof Error ? e.message : e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div
      className="glass"
      style={{
        padding: '2.5rem',
        marginBottom: '1.25rem',
        display: 'flex',
        gap: '2.5rem',
        flexWrap: 'wrap',
        alignItems: 'center',
        justifyContent: 'center',
        borderColor: 'rgba(141, 198, 63, .35)',
        flex: 1,
        minHeight: '60vh',
      }}
    >
      {imageUrl && (
        <img
          src={imageUrl}
          alt={skuCode ?? 'SKU'}
          style={{
            width: 'min(42vw, 460px)',
            height: 'min(42vw, 460px)',
            objectFit: 'cover',
            borderRadius: 18,
            border: '1px solid var(--glass-border)',
          }}
          onError={(e) => {
            ;(e.currentTarget as HTMLImageElement).style.display = 'none'
          }}
        />
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '1.1rem', minWidth: 320, flex: 1, maxWidth: 620 }}>
        <span className="eyebrow">Count this tote</span>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: '.6rem', flexWrap: 'wrap' }}>
          <strong style={{ fontSize: '3rem', lineHeight: 1.05 }}>{huCode ?? 'Tote'}</strong>
        </div>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: '.6rem', flexWrap: 'wrap' }}>
          <span style={{ fontSize: '1.9rem', fontWeight: 600 }}>{skuCode ?? 'SKU'}</span>
          {sku?.description && (
            <span style={{ color: 'var(--text-dim)', fontSize: '1.2rem' }}>{sku.description}</span>
          )}
        </div>

        <div style={{ color: 'var(--text-dim)', fontSize: '1.1rem', marginTop: '.25rem' }}>
          Count every unit in this tote and enter the total.
        </div>

        {note && note.tone === 'recount' && (
          <div
            className="glass"
            style={{
              padding: '.9rem 1.1rem',
              borderColor: 'rgba(255, 193, 94, .5)',
              display: 'flex',
              alignItems: 'center',
              gap: '.6rem',
            }}
          >
            <span style={{ fontSize: '1.3rem' }}>↻</span>
            <strong>{note.text}</strong>
          </div>
        )}
        {note && note.tone === 'ok' && (
          <p className="badge badge-success" style={{ marginBottom: 0 }}>
            {note.text}
          </p>
        )}

        <div style={{ display: 'flex', gap: '.8rem', alignItems: 'center', marginTop: '.6rem', flexWrap: 'wrap' }}>
          <input
            className="form-control"
            type="text"
            inputMode="numeric"
            value={qtyText}
            placeholder="Counted quantity"
            disabled={busy}
            autoFocus
            onChange={(e) => setQtyText(e.target.value.replace(/[^0-9.]/g, ''))}
            onKeyDown={(e) => {
              if (e.key === 'Enter') submit()
            }}
            style={{ fontSize: '2rem', width: 240, padding: '.8rem 1rem' }}
          />
          <button
            className="btn btn-primary btn-lg"
            disabled={!validQty || busy}
            onClick={submit}
            style={{ fontSize: '1.2rem', padding: '.9rem 1.6rem' }}
          >
            {busy ? 'Submitting…' : 'Submit count'}
          </button>
        </div>

        {error && (
          <p className="badge badge-danger" style={{ marginTop: '.4rem', marginBottom: 0 }}>
            {error}
          </p>
        )}
      </div>
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
