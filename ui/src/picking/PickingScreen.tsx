// Guided picking console (execution-layer epic, item 2). An RF-style "next pick" flow: the operator
// is shown ONE focused pick at a time — the location to walk to (large), the SKU (code + name +
// image) and the quantity to pick — and confirms it full or short. Confirming advances to the next
// task in the queue; a compact remaining-queue list gives at-a-glance context.
//
// This guided flow is the substrate every picking modality layers onto: pick-by-light, voice and an
// RF-scanner are HARDWARE SEAMS on top of exactly this queue + confirm contract (a light/voice/scan
// event simply drives the same "confirm the current pick" action). v1 ships the on-screen guided
// version; the hardware adapters plug in later without changing the queue model.
//
// Codes are resolved through the shared useCatalog hook (skuId -> code+name, locationId -> code) and
// the SKU card endpoint (image), exactly like the GTP console — the operator NEVER sees a UUID.
// Keyboard-friendly: Enter confirms the current pick (full). Warehouse-scoped via useWarehouse; the
// queue is refetched after every confirm.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useT } from '../i18n/useT'
import { useAuth } from '../auth/AuthContext'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useCatalog } from '../lib/useCatalog'
import { getSkuCard, type SkuCard } from '../masterdata/api'
import { confirmPick, listPickTasks, type PickTask } from './api'

const QUEUE_POLL_MS = 8000

// A task still owes units when it has remaining qty and isn't already closed out.
function isOpen(task: PickTask): boolean {
  const s = (task.status || '').toUpperCase()
  return task.remainingQty > 0 && s !== 'PICKED' && s !== 'SHORT' && s !== 'CANCELLED'
}

export default function PickingScreen() {
  const t = useT('picking')
  const { writeAllowed } = useAuth()
  const canWrite = writeAllowed('picking')
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const catalog = useCatalog(warehouseId)

  const [tasks, setTasks] = useState<PickTask[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  // The active pick = first still-owing task in queue order. A local "confirmedLineIds" set lets the
  // current pick disappear immediately on confirm (optimistic) without waiting for the refetch.
  const [confirmedLineIds, setConfirmedLineIds] = useState<Set<string>>(new Set())

  const openTasks = useMemo(
    () => tasks.filter((task) => isOpen(task) && !confirmedLineIds.has(task.lineId)),
    [tasks, confirmedLineIds],
  )
  const current = openTasks[0] ?? null
  const upcoming = openTasks.slice(1)

  const refetch = useCallback(async () => {
    if (!warehouseId) {
      setTasks([])
      return
    }
    setLoading(true)
    setError(null)
    try {
      const rows = await listPickTasks(warehouseId)
      setTasks(rows)
      // Drop optimistic markers the server now agrees are gone (keeps the set from growing forever).
      setConfirmedLineIds((prev) => {
        if (prev.size === 0) return prev
        const stillPresent = new Set(rows.filter((r) => isOpen(r)).map((r) => r.lineId))
        const next = new Set<string>()
        for (const id of prev) if (stillPresent.has(id)) next.add(id)
        return next.size === prev.size ? prev : next
      })
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [warehouseId])

  // Load + poll the queue. Re-run when the warehouse changes; first tick is immediate.
  useEffect(() => {
    let cancelled = false
    const tick = () => {
      if (!cancelled) void refetch()
    }
    tick()
    const timer = window.setInterval(tick, QUEUE_POLL_MS)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [refetch])

  // Resolve the active SKU's product card (image) — best effort, cached upstream by the browser.
  const [card, setCard] = useState<SkuCard | null>(null)
  const currentSkuId = current?.skuId ?? null
  useEffect(() => {
    if (!currentSkuId) {
      setCard(null)
      return
    }
    let cancelled = false
    setCard(null)
    getSkuCard(currentSkuId, warehouseId)
      .then((c) => !cancelled && setCard(c))
      .catch(() => {
        /* the image is decoration; the identity still renders from the catalog */
      })
    return () => {
      cancelled = true
    }
  }, [currentSkuId, warehouseId])

  const confirm = useCallback(
    async (task: PickTask, short: boolean) => {
      if (!canWrite || busy) return
      setBusy(true)
      setActionError(null)
      const pickedQty = short ? task.pickedQty : task.requestedQty
      // Optimistically advance so the next pick shows instantly (the refetch reconciles the truth).
      setConfirmedLineIds((prev) => new Set(prev).add(task.lineId))
      try {
        await confirmPick(task.lineId, pickedQty, short)
        await refetch()
      } catch (e) {
        setActionError(e instanceof Error ? e.message : String(e))
        // Roll back the optimistic advance so the operator can retry this pick.
        setConfirmedLineIds((prev) => {
          const next = new Set(prev)
          next.delete(task.lineId)
          return next
        })
      } finally {
        setBusy(false)
      }
    },
    [canWrite, busy, refetch],
  )

  // Enter = confirm the current pick (full). Bound at the document so the operator never has to find
  // a focused control first (RF-style). Ignores Enter while typing in an input/textarea.
  const currentRef = useRef<PickTask | null>(null)
  currentRef.current = current
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Enter') return
      const tag = (e.target as HTMLElement | null)?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return
      const task = currentRef.current
      if (task && canWrite && !busy) {
        e.preventDefault()
        void confirm(task, false)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [confirm, canWrite, busy])

  // --- Render (all hooks above; no hooks below this point) -----------------------------------------
  const remainingCount = openTasks.length

  return (
    <div className="app-content">
      <div
        className="page-head"
        style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '1rem', flexWrap: 'wrap' }}
      >
        <div>
          <span className="eyebrow">{t('eyebrow', 'Order fulfilment')}</span>
          <h1>{t('title', 'Picking')}</h1>
          <p>
            {t(
              'intro',
              'Guided picking — walk to the location, pick the quantity shown, then confirm. Pick-by-light, voice and RF scanners layer onto this same flow.',
            )}
          </p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '.75rem', flexWrap: 'wrap' }}>
          <span className="badge badge-info">
            {remainingCount} {remainingCount === 1 ? t('pickRemaining', 'pick remaining') : t('picksRemaining', 'picks remaining')}
          </span>
          <button className="btn btn-ghost btn-sm" onClick={() => void refetch()} disabled={loading || !warehouseId}>
            {loading ? t('refreshing', 'Refreshing…') : t('refresh', 'Refresh')}
          </button>
        </div>
      </div>

      {error && <p className="badge badge-danger" style={{ marginBottom: '1rem' }}>{error}</p>}
      {actionError && <p className="badge badge-danger" style={{ marginBottom: '1rem' }}>{actionError}</p>}

      {!warehouseId ? (
        <div className="glass" style={{ padding: '2.5rem', textAlign: 'center', color: 'var(--text-dim)' }}>
          {t('selectWarehouse', 'Select a warehouse in the top bar to load its pick queue.')}
        </div>
      ) : current ? (
        <div style={{ display: 'flex', gap: '1rem', alignItems: 'flex-start', flexWrap: 'wrap' }}>
          <NextPickCard
            task={current}
            card={card}
            catalog={catalog}
            canWrite={canWrite}
            busy={busy}
            onConfirmFull={() => void confirm(current, false)}
            onShort={() => void confirm(current, true)}
          />
          <QueuePanel tasks={upcoming} catalog={catalog} />
        </div>
      ) : (
        <AllDone loaded={!loading || tasks.length > 0} />
      )}
    </div>
  )
}

// --- The focused "next pick" hero card -----------------------------------------------------------

interface Catalog {
  skuCode: (id?: string | null) => string
  skuLabel: (id?: string | null) => string
  locationCode: (id?: string | null) => string
}

function NextPickCard({
  task,
  card,
  catalog,
  canWrite,
  busy,
  onConfirmFull,
  onShort,
}: {
  task: PickTask
  card: SkuCard | null
  catalog: Catalog
  canWrite: boolean
  busy: boolean
  onConfirmFull: () => void
  onShort: () => void
}) {
  const t = useT('picking')
  const locationCode = task.locationId ? catalog.locationCode(task.locationId) : null
  const skuCode = card?.code ?? catalog.skuCode(task.skuId)
  const skuName = card?.description ?? null
  const imageUrl = card?.imageUrl ?? null
  const qty = task.remainingQty

  return (
    <div
      className="glass"
      style={{
        flex: '1 1 560px',
        minWidth: 0,
        padding: '2rem',
        display: 'flex',
        flexDirection: 'column',
        gap: '1.5rem',
        borderColor: 'rgba(141, 198, 63, .35)',
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: '.75rem', flexWrap: 'wrap' }}>
        <span className="eyebrow">{t('nextPick', 'Next pick')}</span>
        <span className="muted" style={{ fontSize: '.85rem' }}>
          {t('order', 'Order')} <strong style={{ color: 'var(--text)' }}>{task.orderCode}</strong> · {t('line', 'Line')} {task.lineNo}
        </span>
      </div>

      {/* Location — the biggest thing on the card: where to walk. */}
      <div>
        <div
          className="muted"
          style={{ fontFamily: 'var(--font-mono)', fontSize: '.65rem', letterSpacing: '.14em', textTransform: 'uppercase', marginBottom: '.3rem' }}
        >
          {t('location', 'Location')}
        </div>
        {locationCode ? (
          <div style={{ fontSize: 'clamp(2.4rem, 6vw, 4rem)', fontWeight: 700, lineHeight: 1, color: 'var(--herbal-lime)' }}>
            {locationCode}
          </div>
        ) : (
          <div style={{ fontSize: '1.4rem', color: 'var(--warning)' }}>{t('noLocation', 'No location assigned yet')}</div>
        )}
      </div>

      {/* SKU + image + quantity. */}
      <div style={{ display: 'flex', gap: '1.75rem', alignItems: 'center', flexWrap: 'wrap' }}>
        {imageUrl && (
          <img
            src={imageUrl}
            alt={skuCode}
            style={{ width: 140, height: 140, objectFit: 'cover', borderRadius: 14, border: '1px solid var(--glass-border)' }}
            onError={(e) => {
              ;(e.currentTarget as HTMLImageElement).style.display = 'none'
            }}
          />
        )}
        <div style={{ flex: 1, minWidth: 200, display: 'flex', flexDirection: 'column', gap: '.35rem' }}>
          <div
            className="muted"
            style={{ fontFamily: 'var(--font-mono)', fontSize: '.65rem', letterSpacing: '.14em', textTransform: 'uppercase' }}
          >
            {t('sku', 'SKU')}
          </div>
          <div style={{ fontSize: '1.6rem', fontWeight: 600, lineHeight: 1.1 }}>{skuCode}</div>
          {skuName && <div style={{ color: 'var(--text-dim)', fontSize: '1rem' }}>{skuName}</div>}
        </div>
        <div style={{ textAlign: 'center', minWidth: 120 }}>
          <div
            className="muted"
            style={{ fontFamily: 'var(--font-mono)', fontSize: '.65rem', letterSpacing: '.14em', textTransform: 'uppercase', marginBottom: '.2rem' }}
          >
            {t('pickQty', 'Pick qty')}
          </div>
          <div style={{ fontSize: 'clamp(2.4rem, 6vw, 3.6rem)', fontWeight: 700, lineHeight: 1, color: 'var(--herbal-lime)' }}>{qty}</div>
          {task.requestedQty !== qty && (
            <div className="muted" style={{ fontSize: '.8rem' }}>
              {task.pickedQty}/{task.requestedQty} {t('alreadyPicked', 'already picked')}
            </div>
          )}
        </div>
      </div>

      {/* Actions: Confirm (full) is primary; Short closes the line with whatever's on hand. */}
      {canWrite ? (
        <div style={{ display: 'flex', gap: '.75rem', flexWrap: 'wrap' }}>
          <button className="btn btn-primary btn-lg" style={{ flex: '1 1 240px' }} onClick={onConfirmFull} disabled={busy}>
            {busy ? t('confirming', 'Confirming…') : t('confirm', 'Confirm pick (Enter)')}
          </button>
          <button className="btn btn-ghost btn-lg" onClick={onShort} disabled={busy} title={t('shortTip', 'Could not pick the full quantity — close the line short')}>
            {t('short', 'Short')}
          </button>
        </div>
      ) : (
        <p className="muted" style={{ fontSize: '.85rem' }}>{t('readOnly', 'You have read-only access — picking is disabled.')}</p>
      )}
    </div>
  )
}

// --- Compact remaining-queue list -----------------------------------------------------------------

function QueuePanel({ tasks, catalog }: { tasks: PickTask[]; catalog: Catalog }) {
  const t = useT('picking')
  return (
    <div className="glass" style={{ flex: '0 0 320px', minWidth: 280, padding: '1.1rem', alignSelf: 'flex-start' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '.75rem' }}>
        <span className="eyebrow">{t('upNext', 'Up next')}</span>
        <span className="badge">{tasks.length}</span>
      </div>
      {tasks.length === 0 ? (
        <p className="muted" style={{ fontSize: '.85rem', margin: 0 }}>{t('queueClear', 'Nothing else queued.')}</p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '.5rem' }}>
          {tasks.map((task, i) => (
            <div
              key={task.lineId}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '.7rem',
                padding: '.55rem .7rem',
                borderRadius: 9,
                border: '1px solid var(--glass-border)',
                background: 'rgba(255,255,255,.02)',
              }}
            >
              <span className="muted" style={{ fontSize: '.8rem', width: '1.4rem', textAlign: 'right', flexShrink: 0 }}>{i + 2}</span>
              <div style={{ minWidth: 0, flex: 1, display: 'flex', flexDirection: 'column', gap: '.1rem' }}>
                <strong style={{ fontSize: '.95rem' }}>{task.locationId ? catalog.locationCode(task.locationId) : t('noLoc', 'No loc')}</strong>
                <span className="muted" style={{ fontSize: '.78rem', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                  {catalog.skuCode(task.skuId)} · {task.orderCode}
                </span>
              </div>
              <span style={{ color: 'var(--herbal-lime)', fontWeight: 600, fontSize: '.95rem', flexShrink: 0 }}>×{task.remainingQty}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function AllDone({ loaded }: { loaded: boolean }) {
  const t = useT('picking')
  return (
    <div
      className="glass"
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        textAlign: 'center',
        padding: '3rem',
        minHeight: '50vh',
      }}
    >
      <div style={{ fontSize: '4rem', marginBottom: '1rem' }}>{loaded ? '✓' : '⏳'}</div>
      <h2 style={{ marginTop: 0, fontSize: '2rem' }}>
        {loaded ? t('allPicked', 'All caught up') : t('loadingQueue', 'Loading pick queue…')}
      </h2>
      <p style={{ color: 'var(--text-dim)', margin: 0, fontSize: '1.05rem' }}>
        {loaded ? t('noPicks', 'No open picks in this warehouse right now.') : t('checking', 'Checking the queue…')}
      </p>
    </div>
  )
}
