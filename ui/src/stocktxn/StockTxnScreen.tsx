import { useCallback, useEffect, useMemo, useState } from 'react'
import Select from '../ui/Select'

// --- Transaction-log event shape (contracts/openapi/txlog.yaml → EventView) ---
type TxEvent = {
  position: number | null
  eventId: string
  streamId: string
  seq: number
  eventType: string
  occurredAt: string
  recordedAt: string
  actor: string | null
  correlationId: string | null
  payload: Record<string, unknown>
  payloadVersion: number
}

// Stock-affecting event types the projection reacts to
// (services/inventory → InventoryEventTypes). Used to drive the type filter
// and the from/to + qty-delta decoding; any other event type still shows up
// in the log as a raw entry.
const STOCK_TYPES = [
  'GoodsReceived',
  'PutawayCompleted',
  'StockMoved',
  'Picked',
  'StockAdjusted',
  'StockStatusChanged',
] as const

// How many of the largest position cursor we pull per page from the feed.
// The feed (GET /api/txlog/events) is a global position-ordered cursor with no
// server-side filtering, so we fetch a window and filter/paginate in-memory.
const FETCH_LIMIT = 1000
const PAGE_SIZE = 25

// Per-event-type badge styling.
function typeBadgeClass(type: string): string {
  switch (type) {
    case 'GoodsReceived':
      return 'badge-success'
    case 'Picked':
      return 'badge-danger'
    case 'StockAdjusted':
    case 'StockStatusChanged':
      return 'badge-warning'
    default:
      return 'badge-info'
  }
}

function str(v: unknown): string | null {
  if (v === null || v === undefined) return null
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  return null
}

function num(v: unknown): number | null {
  if (typeof v === 'number') return v
  if (typeof v === 'string' && v.trim() !== '' && !Number.isNaN(Number(v))) return Number(v)
  return null
}

// A short, human display for a UUID-ish id: keep the first segment.
function shortId(id: string | null): string {
  if (!id) return '—'
  return id.length > 12 ? `${id.slice(0, 8)}…` : id
}

// Decode a transaction-log event into the movement-log columns. Different
// event types carry different payload shapes (BucketQty / Move / Adjust /
// StatusChange — see services/inventory StockMovementPayloads).
function decode(e: TxEvent): {
  sku: string | null
  from: string | null
  to: string | null
  delta: number | null
  uom: string | null
} {
  const p = e.payload || {}
  const sku = str(p.skuId) ?? str(p.sku) ?? null
  const uom = str(p.uomCode)
  const qty = num(p.qty)
  const qtyDelta = num(p.qtyDelta)
  const fromLoc = str(p.fromLocationId)
  const toLoc = str(p.toLocationId)
  const loc = str(p.locationId)

  switch (e.eventType) {
    case 'GoodsReceived':
      return { sku, from: null, to: loc, delta: qty, uom }
    case 'Picked':
      return { sku, from: loc, to: null, delta: qty != null ? -qty : null, uom }
    case 'PutawayCompleted':
    case 'StockMoved':
      return { sku, from: fromLoc, to: toLoc, delta: qty, uom }
    case 'StockAdjusted':
      return { sku, from: null, to: loc, delta: qtyDelta, uom }
    case 'StockStatusChanged': {
      const fromStatus = str(p.fromStatus)
      const toStatus = str(p.toStatus)
      return {
        sku,
        from: fromStatus ? `${loc ?? '—'} · ${fromStatus}` : loc,
        to: toStatus ? `${loc ?? '—'} · ${toStatus}` : loc,
        delta: qty,
        uom,
      }
    }
    default:
      // Unknown / non-stock event — surface whatever location/qty we can find.
      return { sku, from: fromLoc ?? loc, to: toLoc ?? loc, delta: qty ?? qtyDelta, uom }
  }
}

function fmtTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

// datetime-local value (local time, no zone) → epoch ms, or null.
function dtToMs(v: string): number | null {
  if (!v) return null
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? null : d.getTime()
}

export default function StockTxnScreen() {
  const [events, setEvents] = useState<TxEvent[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [expanded, setExpanded] = useState<string | null>(null)
  const [page, setPage] = useState(0)

  // Filters
  const [fSku, setFSku] = useState('')
  const [fLocation, setFLocation] = useState('')
  const [fType, setFType] = useState('')
  const [fFrom, setFFrom] = useState('')
  const [fTo, setFTo] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch(`/api/txlog/events?afterPosition=0&limit=${FETCH_LIMIT}`)
      if (!res.ok) throw new Error(`Feed request failed (${res.status})`)
      const data: TxEvent[] = await res.json()
      setEvents(Array.isArray(data) ? data : [])
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load transactions')
      setEvents([])
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  // Newest first for the log view; decode payload columns once.
  const decorated = useMemo(
    () =>
      events
        .map((e) => ({ e, d: decode(e) }))
        .sort((a, b) => {
          const pa = a.e.position ?? 0
          const pb = b.e.position ?? 0
          return pb - pa
        }),
    [events],
  )

  const filtered = useMemo(() => {
    const sku = fSku.trim().toLowerCase()
    const loc = fLocation.trim().toLowerCase()
    const fromMs = dtToMs(fFrom)
    const toMs = dtToMs(fTo)
    return decorated.filter(({ e, d }) => {
      if (fType && e.eventType !== fType) return false
      if (sku && !(d.sku ?? '').toLowerCase().includes(sku)) return false
      if (loc) {
        const hay = `${d.from ?? ''} ${d.to ?? ''}`.toLowerCase()
        if (!hay.includes(loc)) return false
      }
      if (fromMs != null || toMs != null) {
        const t = new Date(e.occurredAt).getTime()
        if (Number.isNaN(t)) return false
        if (fromMs != null && t < fromMs) return false
        if (toMs != null && t > toMs) return false
      }
      return true
    })
  }, [decorated, fSku, fLocation, fType, fFrom, fTo])

  // Reset to first page whenever the filtered set changes.
  useEffect(() => {
    setPage(0)
  }, [fSku, fLocation, fType, fFrom, fTo])

  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const safePage = Math.min(page, pageCount - 1)
  const pageRows = filtered.slice(safePage * PAGE_SIZE, safePage * PAGE_SIZE + PAGE_SIZE)

  const hasFilters = !!(fSku || fLocation || fType || fFrom || fTo)
  const clearFilters = () => {
    setFSku('')
    setFLocation('')
    setFType('')
    setFFrom('')
    setFTo('')
  }

  return (
    <div className="app-content">
      <div className="page-head">
        <div className="eyebrow">openWCS</div>
        <h1>Stock transactions</h1>
        <p>
          Event-sourced movement log from the immutable transaction log. Filter by SKU,
          location, type or time range, and expand any row to inspect the raw event.
        </p>
      </div>

      <div className="glass card-pad" style={{ marginBottom: '1.25rem' }}>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
            gap: '.75rem',
            alignItems: 'end',
          }}
        >
          <label style={{ display: 'block' }}>
            <span className="muted" style={{ fontSize: '.75rem' }}>SKU</span>
            <input
              className="form-control"
              placeholder="SKU id contains…"
              value={fSku}
              onChange={(ev) => setFSku(ev.target.value)}
            />
          </label>
          <label style={{ display: 'block' }}>
            <span className="muted" style={{ fontSize: '.75rem' }}>Location</span>
            <input
              className="form-control"
              placeholder="From / to location…"
              value={fLocation}
              onChange={(ev) => setFLocation(ev.target.value)}
            />
          </label>
          <label style={{ display: 'block' }}>
            <span className="muted" style={{ fontSize: '.75rem' }}>Type</span>
            <Select
              value={fType}
              onChange={(v) => setFType(v)}
              ariaLabel="Type"
              options={[
                { value: '', label: 'All types' },
                ...STOCK_TYPES.map((t) => ({ value: t, label: t })),
              ]}
            />
          </label>
          <label style={{ display: 'block' }}>
            <span className="muted" style={{ fontSize: '.75rem' }}>From</span>
            <input
              type="datetime-local"
              className="form-control"
              value={fFrom}
              onChange={(ev) => setFFrom(ev.target.value)}
            />
          </label>
          <label style={{ display: 'block' }}>
            <span className="muted" style={{ fontSize: '.75rem' }}>To</span>
            <input
              type="datetime-local"
              className="form-control"
              value={fTo}
              onChange={(ev) => setFTo(ev.target.value)}
            />
          </label>
        </div>
        <div className="toolbar" style={{ marginTop: '1rem', marginBottom: 0 }}>
          <span className="muted" style={{ fontSize: '.8125rem' }}>
            {loading
              ? 'Loading…'
              : `${filtered.length} transaction${filtered.length === 1 ? '' : 's'}` +
                (hasFilters ? ` (of ${decorated.length})` : '')}
          </span>
          <span className="spacer" />
          {hasFilters && (
            <button type="button" className="btn btn-ghost btn-sm" onClick={clearFilters}>
              Clear filters
            </button>
          )}
          <button
            type="button"
            className="btn btn-outline btn-sm"
            onClick={() => void load()}
            disabled={loading}
          >
            Refresh
          </button>
        </div>
      </div>

      {error && (
        <div className="alert badge-danger" style={{ marginBottom: '1rem' }}>
          {error}
        </div>
      )}

      <div className="glass" style={{ overflow: 'hidden' }}>
        <div style={{ overflowX: 'auto' }}>
          <table>
            <thead>
              <tr>
                <th style={{ width: '1%' }} aria-label="Expand" />
                <th>Time</th>
                <th>Type</th>
                <th>SKU</th>
                <th>From</th>
                <th>To</th>
                <th style={{ textAlign: 'right' }}>Qty Δ</th>
                <th>Actor</th>
                <th>Ref</th>
              </tr>
            </thead>
            <tbody>
              {!loading && pageRows.length === 0 && (
                <tr>
                  <td colSpan={9} className="muted" style={{ textAlign: 'center', padding: '2rem' }}>
                    {hasFilters ? 'No transactions match the filters.' : 'No transactions recorded yet.'}
                  </td>
                </tr>
              )}
              {pageRows.map(({ e, d }) => {
                const isOpen = expanded === e.eventId
                const ref = e.correlationId ?? e.streamId
                return (
                  <FragmentRow
                    key={e.eventId}
                    event={e}
                    decoded={d}
                    open={isOpen}
                    refLabel={ref}
                    onToggle={() => setExpanded(isOpen ? null : e.eventId)}
                  />
                )
              })}
            </tbody>
          </table>
        </div>
      </div>

      {filtered.length > PAGE_SIZE && (
        <div className="toolbar" style={{ marginTop: '1rem' }}>
          <span className="muted" style={{ fontSize: '.8125rem' }}>
            Page {safePage + 1} of {pageCount}
          </span>
          <span className="spacer" />
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={safePage === 0}
          >
            Previous
          </button>
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={() => setPage((p) => Math.min(pageCount - 1, p + 1))}
            disabled={safePage >= pageCount - 1}
          >
            Next
          </button>
        </div>
      )}
    </div>
  )
}

function FragmentRow({
  event,
  decoded,
  open,
  refLabel,
  onToggle,
}: {
  event: TxEvent
  decoded: ReturnType<typeof decode>
  open: boolean
  refLabel: string | null
  onToggle: () => void
}) {
  const delta = decoded.delta
  const deltaText =
    delta == null ? '—' : `${delta > 0 ? '+' : ''}${delta}${decoded.uom ? ` ${decoded.uom}` : ''}`
  const deltaColor =
    delta == null ? undefined : delta > 0 ? '#8DC63F' : delta < 0 ? '#ff8a80' : undefined

  return (
    <>
      <tr
        onClick={onToggle}
        style={{ cursor: 'pointer' }}
        aria-expanded={open}
      >
        <td style={{ color: 'var(--herbal-lime)', fontFamily: 'var(--font-mono)' }}>
          {open ? '▾' : '▸'}
        </td>
        <td style={{ whiteSpace: 'nowrap', fontFamily: 'var(--font-mono)', fontSize: '.8125rem' }}>
          {fmtTime(event.occurredAt)}
        </td>
        <td>
          <span className={`badge ${typeBadgeClass(event.eventType)}`}>{event.eventType}</span>
        </td>
        <td title={decoded.sku ?? undefined} style={{ fontFamily: 'var(--font-mono)' }}>
          {shortId(decoded.sku)}
        </td>
        <td title={decoded.from ?? undefined}>{decoded.from ?? '—'}</td>
        <td title={decoded.to ?? undefined}>{decoded.to ?? '—'}</td>
        <td style={{ textAlign: 'right', fontFamily: 'var(--font-mono)', color: deltaColor }}>
          {deltaText}
        </td>
        <td title={event.actor ?? undefined}>{event.actor ?? '—'}</td>
        <td title={refLabel ?? undefined} style={{ fontFamily: 'var(--font-mono)' }}>
          {shortId(refLabel)}
        </td>
      </tr>
      {open && (
        <tr>
          <td colSpan={9} style={{ background: 'rgba(8, 30, 22, .35)' }}>
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
                gap: '.5rem 1.5rem',
                marginBottom: '.75rem',
              }}
            >
              <Meta label="Event id" value={event.eventId} />
              <Meta label="Stream id" value={event.streamId} />
              <Meta label="Seq" value={String(event.seq)} />
              <Meta label="Position" value={event.position != null ? String(event.position) : '—'} />
              <Meta label="Correlation id" value={event.correlationId ?? '—'} />
              <Meta label="Payload version" value={String(event.payloadVersion)} />
              <Meta label="Occurred at" value={fmtTime(event.occurredAt)} />
              <Meta label="Recorded at" value={fmtTime(event.recordedAt)} />
            </div>
            <div className="muted" style={{ fontSize: '.7rem', fontFamily: 'var(--font-mono)', letterSpacing: '.08em', textTransform: 'uppercase', marginBottom: '.35rem' }}>
              Raw payload
            </div>
            <pre
              style={{
                margin: 0,
                padding: '.75rem',
                background: 'rgba(0, 0, 0, .25)',
                borderRadius: 'var(--radius)',
                overflowX: 'auto',
                fontFamily: 'var(--font-mono)',
                fontSize: '.8rem',
                lineHeight: 1.5,
              }}
            >
              {JSON.stringify(event.payload, null, 2)}
            </pre>
          </td>
        </tr>
      )}
    </>
  )
}

function Meta({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div
        className="muted"
        style={{
          fontSize: '.65rem',
          fontFamily: 'var(--font-mono)',
          letterSpacing: '.08em',
          textTransform: 'uppercase',
        }}
      >
        {label}
      </div>
      <div style={{ fontFamily: 'var(--font-mono)', fontSize: '.8125rem', overflowWrap: 'anywhere' }}>
        {value}
      </div>
    </div>
  )
}
