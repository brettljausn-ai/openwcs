// Guided picking console (execution-layer epic, item 2) + RF-handheld layer. The operator is shown
// ONE focused pick at a time — the location to walk to (large), the SKU (code + name + image) and
// the quantity — and confirms it full or short. Confirming advances to the next task.
//
// RF / scanner-first layer (this file): rugged Android handhelds (Zebra/Honeywell) in keyboard-wedge
// mode deliver a scan as a fast burst of keystrokes terminated by Enter. We keep an always-focused,
// minimal capture input on the pick screen so a wedge scan is captured WITHOUT the operator tapping
// anything. A scan is matched against the CURRENT pick: location code -> location confirmed; SKU code
// -> item confirmed. Once both are confirmed (or, when there is no location, just the item) the pick
// auto-confirms at full qty. A mismatch shows a big "wrong location/item" warning and buzzes. Manual
// Confirm/Short buttons and the document-level Enter still work for desktop/mouse use.
//
// Offline resilience (offlineQueue.ts): a confirm that fails for a network reason is enqueued
// durably and the operator advances optimistically; an "Offline — N pending" banner + a status
// indicator surface the queue, which drains on reconnect / interval / demand.
//
// Codes are resolved through the shared useCatalog hook; the operator NEVER sees a UUID.
// IMPORTANT: every hook is called before any early return (Rules of Hooks — see the #310 crash).

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useT } from '../i18n/useT'
import { useAuth } from '../auth/AuthContext'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useCatalog } from '../lib/useCatalog'
import { getSkuCard, type SkuCard } from '../masterdata/api'
import { confirmPick, listPickTasks, type PickTask } from './api'
import { enqueueConfirm } from './offlineQueue'
import { useOfflineQueue } from './useOfflineQueue'

const QUEUE_POLL_MS = 8000

// A task still owes units when it has remaining qty and isn't already closed out.
function isOpen(task: PickTask): boolean {
  const s = (task.status || '').toUpperCase()
  return task.remainingQty > 0 && s !== 'PICKED' && s !== 'SHORT' && s !== 'CANCELLED'
}

// A network-class failure (offline, fetch threw, or a 5xx) should go to the offline queue; an
// application rejection (4xx) should surface to the operator immediately. We can only see the
// message here (confirmPick throws Error), so we sniff the leading status code it embeds.
function isNetworkFailure(e: unknown): boolean {
  if (typeof navigator !== 'undefined' && navigator.onLine === false) return true
  if (e instanceof TypeError) return true // thrown fetch (connection refused / DNS / CORS-at-edge)
  const msg = e instanceof Error ? e.message : String(e)
  if (/^5\d\d\b/.test(msg)) return true // "502 Bad Gateway" etc.
  if (/failed to fetch|networkerror|load failed/i.test(msg)) return true
  return false
}

// Normalise a scanned/typed code for comparison: trim, uppercase, strip surrounding whitespace.
// Wedge scanners occasionally prepend/append control chars; we keep only the visible token.
function norm(s: string | null | undefined): string {
  return (s ?? '').trim().toUpperCase()
}

type ScanState = 'idle' | 'locOk' | 'itemOk' | 'bothOk'

export default function PickingScreen() {
  const t = useT('picking')
  const { writeAllowed } = useAuth()
  const canWrite = writeAllowed('picking')
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const catalog = useCatalog(warehouseId)
  const offline = useOfflineQueue()

  const [tasks, setTasks] = useState<PickTask[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  // The active pick = first still-owing task in queue order. A local "confirmedLineIds" set lets the
  // current pick disappear immediately on confirm (optimistic) without waiting for the refetch.
  const [confirmedLineIds, setConfirmedLineIds] = useState<Set<string>>(new Set())

  // Scanner state for the CURRENT pick: which of location / item have been scan-verified, plus a
  // transient mismatch warning ("wrong location/item").
  const [scanState, setScanState] = useState<ScanState>('idle')
  const [scanWarn, setScanWarn] = useState<string | null>(null)

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

  // Reset scan progress whenever the active pick changes (advanced, refetched, warehouse switch).
  const currentLineId = current?.lineId ?? null
  useEffect(() => {
    setScanState('idle')
    setScanWarn(null)
  }, [currentLineId])

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
        if (isNetworkFailure(e)) {
          // Network-class failure: enqueue durably and STAY advanced. The drainer retries; the
          // banner shows the pending count. Picking is not blocked by the dropout.
          const label = `${task.orderCode} · ${catalog.skuCode(task.skuId)}${
            task.locationId ? ` @ ${catalog.locationCode(task.locationId)}` : ''
          } ×${pickedQty}${short ? ' (short)' : ''}`
          await enqueueConfirm({ lineId: task.lineId, pickedQty, short, label })
          // keep the optimistic advance — do NOT roll back
        } else {
          // Application rejection (4xx): surface it and roll back so the operator can retry/adjust.
          setActionError(e instanceof Error ? e.message : String(e))
          setConfirmedLineIds((prev) => {
            const next = new Set(prev)
            next.delete(task.lineId)
            return next
          })
        }
      } finally {
        setBusy(false)
      }
    },
    [canWrite, busy, refetch, catalog],
  )

  // --- Scan handling --------------------------------------------------------------------------------
  // Match a scanned token against the current pick. Updates scanState; on the FINAL needed match,
  // auto-confirms at full qty. A non-matching scan shows a transient "wrong location/item" warning
  // and buzzes the device (Vibration API — no-op on desktop / unsupported handhelds).
  const buzz = useCallback((pattern: number | number[]) => {
    try {
      navigator.vibrate?.(pattern)
    } catch {
      /* unsupported */
    }
  }, [])

  const onScan = useCallback(
    (raw: string) => {
      const token = norm(raw)
      if (!token) return
      const task = current
      if (!task) return
      const expectedLoc = task.locationId ? norm(catalog.locationCode(task.locationId)) : ''
      const expectedSku = norm(card?.code ?? catalog.skuCode(task.skuId))
      const hasLoc = expectedLoc !== '' && expectedLoc !== '—'

      setScanWarn(null)

      // Location match.
      if (hasLoc && token === expectedLoc) {
        buzz(40)
        setScanState((s) => (s === 'itemOk' || s === 'bothOk' ? 'bothOk' : 'locOk'))
        return
      }
      // Item match (accept the SKU code; backends may later add barcode aliases on the card).
      if (token === expectedSku) {
        buzz(40)
        setScanState((s) => {
          const next: ScanState = !hasLoc || s === 'locOk' || s === 'bothOk' ? 'bothOk' : 'itemOk'
          return next
        })
        return
      }
      // Mismatch.
      buzz([60, 50, 60])
      const looksLikeLoc = hasLoc && /[A-Z]-?\d/.test(token) // heuristic only for the message
      setScanWarn(
        looksLikeLoc
          ? t('wrongLocation', 'Wrong location — scan {code}').replace('{code}', expectedLoc)
          : t('wrongItem', 'Wrong item — expected {code}').replace('{code}', expectedSku),
      )
    },
    [current, card, catalog, buzz, t],
  )

  // When both location + item are confirmed, auto-confirm the pick at full qty (once).
  useEffect(() => {
    if (scanState === 'bothOk' && current && canWrite && !busy) {
      buzz([40, 30, 40])
      void confirm(current, false)
    }
    // confirm advancing changes current -> scanState resets via the line-change effect.
  }, [scanState, current, canWrite, busy, confirm, buzz])

  // --- Wedge capture input: stays focused so scans are always captured ------------------------------
  const captureRef = useRef<HTMLInputElement | null>(null)
  // True while the operator is typing in the Short-qty field; we must NOT steal focus then.
  const [qtyEditing, setQtyEditing] = useState(false)
  const qtyEditingRef = useRef(false)
  qtyEditingRef.current = qtyEditing

  const focusCapture = useCallback(() => {
    if (qtyEditingRef.current) return
    const el = captureRef.current
    if (el && document.activeElement !== el) el.focus({ preventScroll: true })
  }, [])

  // Refocus the capture input after renders / when the active pick changes, and on a gentle interval
  // (covers focus lost to browser chrome, soft-keyboard dismissal, etc.). Skipped while editing qty.
  useEffect(() => {
    if (!current || !canWrite) return
    focusCapture()
    const id = window.setInterval(focusCapture, 1200)
    const onVisible = () => focusCapture()
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      window.clearInterval(id)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [current, canWrite, focusCapture, currentLineId])

  // Enter = confirm the current pick (full). Bound at the document so the operator never has to find
  // a focused control first (RF-style). Ignores Enter while typing in a non-capture input/textarea
  // (e.g. the Short-qty field), and lets the capture input's own onKeyDown handle scan Enters.
  const currentRef = useRef<PickTask | null>(null)
  currentRef.current = current
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Enter') return
      const target = e.target as HTMLElement | null
      if (target === captureRef.current) return // the capture input handles its own Enter
      const tag = target?.tagName
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
      {/* Always-present wedge capture input. Visually hidden but focusable & on-screen (display:none
          can't hold focus). It collects the scan burst; Enter fires onScan and clears it. */}
      {canWrite && (
        <input
          ref={captureRef}
          aria-hidden="true"
          autoComplete="off"
          autoCorrect="off"
          autoCapitalize="off"
          spellCheck={false}
          inputMode="none"
          onBlur={() => {
            // Refocus on the next tick unless the operator moved to the qty field.
            window.setTimeout(focusCapture, 0)
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              const val = (e.currentTarget.value || '').trim()
              e.currentTarget.value = ''
              if (val) onScan(val)
            }
          }}
          style={{
            position: 'fixed',
            opacity: 0,
            width: 1,
            height: 1,
            top: 0,
            left: 0,
            border: 0,
            padding: 0,
            pointerEvents: 'none',
          }}
        />
      )}

      <OfflineBanner offline={offline} t={t} />

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
            scanState={scanState}
            scanWarn={scanWarn}
            onConfirmFull={() => void confirm(current, false)}
            onShort={() => void confirm(current, true)}
            onQtyEditing={setQtyEditing}
          />
          <QueuePanel tasks={upcoming} catalog={catalog} />
        </div>
      ) : (
        <AllDone loaded={!loading || tasks.length > 0} />
      )}
    </div>
  )
}

// --- Offline / queue status banner ----------------------------------------------------------------

function OfflineBanner({
  offline,
  t,
}: {
  offline: ReturnType<typeof useOfflineQueue>
  t: (key: string, english: string) => string
}) {
  const { online, pending, failed, retryNow } = offline
  if (online && pending === 0 && failed === 0) return null

  const bg = !online ? 'rgba(244,184,96,.14)' : failed > 0 ? 'rgba(255,107,94,.14)' : 'rgba(141,198,63,.12)'
  const border = !online ? 'var(--warning)' : failed > 0 ? 'var(--danger)' : 'var(--herbal-lime)'

  return (
    <div
      role="status"
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: '.9rem',
        flexWrap: 'wrap',
        padding: '.7rem 1rem',
        marginBottom: '1rem',
        borderRadius: 10,
        background: bg,
        border: `1px solid ${border}`,
      }}
    >
      <span
        style={{
          width: 10,
          height: 10,
          borderRadius: '50%',
          background: online ? 'var(--herbal-lime)' : 'var(--warning)',
          flexShrink: 0,
          boxShadow: online ? '0 0 6px var(--herbal-lime)' : '0 0 6px var(--warning)',
        }}
      />
      <strong style={{ fontSize: '1rem' }}>
        {online ? t('online', 'Online') : t('offline', 'Offline')}
      </strong>
      {pending > 0 && (
        <span style={{ fontSize: '.95rem' }}>
          {t('pendingConfirms', '{n} pending').replace('{n}', String(pending))}
        </span>
      )}
      {failed > 0 && (
        <span style={{ fontSize: '.95rem', color: 'var(--danger)' }}>
          {t('failedConfirms', '{n} failed — needs attention').replace('{n}', String(failed))}
        </span>
      )}
      <span style={{ flex: 1 }} />
      {(pending > 0 || failed > 0) && (
        <button className="btn btn-ghost btn-sm" onClick={retryNow}>
          {t('retryNow', 'Retry now')}
        </button>
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
  scanState,
  scanWarn,
  onConfirmFull,
  onShort,
  onQtyEditing,
}: {
  task: PickTask
  card: SkuCard | null
  catalog: Catalog
  canWrite: boolean
  busy: boolean
  scanState: ScanState
  scanWarn: string | null
  onConfirmFull: () => void
  onShort: () => void
  onQtyEditing: (editing: boolean) => void
}) {
  const t = useT('picking')
  const locationCode = task.locationId ? catalog.locationCode(task.locationId) : null
  const skuCode = card?.code ?? catalog.skuCode(task.skuId)
  const skuName = card?.description ?? null
  const imageUrl = card?.imageUrl ?? null
  const qty = task.remainingQty
  const hasLoc = !!locationCode && locationCode !== '—'

  const locConfirmed = scanState === 'locOk' || scanState === 'bothOk'
  const itemConfirmed = scanState === 'itemOk' || scanState === 'bothOk'

  // The scan prompt: what to scan next. "Scan location" -> "Scan item" -> "Confirm N".
  let prompt: string
  let promptKind: 'loc' | 'item' | 'confirm'
  if (hasLoc && !locConfirmed) {
    prompt = t('scanLocation', 'Scan location {code}').replace('{code}', locationCode!)
    promptKind = 'loc'
  } else if (!itemConfirmed) {
    prompt = t('scanItem', 'Scan item {code}').replace('{code}', skuCode)
    promptKind = 'item'
  } else {
    prompt = t('scanConfirm', 'Confirm {n}').replace('{n}', String(qty))
    promptKind = 'confirm'
  }

  return (
    <div
      className="glass"
      style={{
        flex: '1 1 560px',
        minWidth: 0,
        padding: '2rem',
        display: 'flex',
        flexDirection: 'column',
        gap: '1.25rem',
        borderColor: scanWarn ? 'var(--danger)' : 'rgba(141, 198, 63, .35)',
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: '.75rem', flexWrap: 'wrap' }}>
        <span className="eyebrow">{t('nextPick', 'Next pick')}</span>
        <span className="muted" style={{ fontSize: '.85rem' }}>
          {t('order', 'Order')} <strong style={{ color: 'var(--text)' }}>{task.orderCode}</strong> · {t('line', 'Line')} {task.lineNo}
        </span>
      </div>

      {/* Scan prompt / mismatch banner — big, high-contrast, glove-readable. */}
      {canWrite && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '.75rem',
            padding: '.85rem 1.1rem',
            borderRadius: 12,
            background: scanWarn
              ? 'rgba(255,107,94,.16)'
              : promptKind === 'confirm'
                ? 'rgba(141,198,63,.16)'
                : 'rgba(255,255,255,.04)',
            border: `2px solid ${scanWarn ? 'var(--danger)' : promptKind === 'confirm' ? 'var(--herbal-lime)' : 'var(--glass-border)'}`,
          }}
        >
          <span style={{ fontSize: '1.6rem', lineHeight: 1 }}>{scanWarn ? '⚠' : promptKind === 'confirm' ? '✓' : '⤷'}</span>
          <span style={{ fontSize: '1.25rem', fontWeight: 700, color: scanWarn ? 'var(--danger)' : 'var(--text)' }}>
            {scanWarn ?? prompt}
          </span>
        </div>
      )}

      {/* Location — the biggest thing on the card: where to walk. */}
      <div>
        <div
          className="muted"
          style={{ fontFamily: 'var(--font-mono)', fontSize: '.65rem', letterSpacing: '.14em', textTransform: 'uppercase', marginBottom: '.3rem', display: 'flex', alignItems: 'center', gap: '.4rem' }}
        >
          {t('location', 'Location')}
          {hasLoc && locConfirmed && <span style={{ color: 'var(--herbal-lime)' }}>✓</span>}
        </div>
        {locationCode ? (
          <div style={{ fontSize: 'clamp(2.4rem, 6vw, 4rem)', fontWeight: 700, lineHeight: 1, color: locConfirmed ? 'var(--herbal-lime)' : 'var(--text)' }}>
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
            style={{ fontFamily: 'var(--font-mono)', fontSize: '.65rem', letterSpacing: '.14em', textTransform: 'uppercase', display: 'flex', alignItems: 'center', gap: '.4rem' }}
          >
            {t('sku', 'SKU')}
            {itemConfirmed && <span style={{ color: 'var(--herbal-lime)' }}>✓</span>}
          </div>
          <div style={{ fontSize: '1.6rem', fontWeight: 600, lineHeight: 1.1, color: itemConfirmed ? 'var(--herbal-lime)' : 'var(--text)' }}>{skuCode}</div>
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

      {/* Actions: Confirm (full) is primary; Short closes the line with whatever's on hand. The
          manual buttons coexist with scanning — they are the desktop/mouse path and the fallback. */}
      {canWrite ? (
        <ActionRow task={task} qty={qty} busy={busy} onConfirmFull={onConfirmFull} onShort={onShort} onQtyEditing={onQtyEditing} />
      ) : (
        <p className="muted" style={{ fontSize: '.85rem' }}>{t('readOnly', 'You have read-only access — picking is disabled.')}</p>
      )}
    </div>
  )
}

// Confirm / Short actions. Short reveals a qty field; while it is focused the parent suspends the
// wedge-capture refocus so the operator can type a number without the scanner input stealing focus.
function ActionRow({
  task,
  qty,
  busy,
  onConfirmFull,
  onShort,
  onQtyEditing,
}: {
  task: PickTask
  qty: number
  busy: boolean
  onConfirmFull: () => void
  onShort: () => void
  onQtyEditing: (editing: boolean) => void
}) {
  const t = useT('picking')
  void task
  void qty
  return (
    <div style={{ display: 'flex', gap: '.75rem', flexWrap: 'wrap' }}>
      <button className="btn btn-primary btn-lg" style={{ flex: '1 1 240px', fontSize: '1.15rem', minHeight: 64 }} onClick={onConfirmFull} disabled={busy}>
        {busy ? t('confirming', 'Confirming…') : t('confirm', 'Confirm pick (Enter)')}
      </button>
      <button
        className="btn btn-ghost btn-lg"
        style={{ minHeight: 64 }}
        onClick={onShort}
        disabled={busy}
        // Pausing the wedge refocus on focus/blur so a future inline-qty entry isn't fought over.
        onFocus={() => onQtyEditing(true)}
        onBlur={() => onQtyEditing(false)}
        title={t('shortTip', 'Could not pick the full quantity — close the line short')}
      >
        {t('short', 'Short')}
      </button>
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
