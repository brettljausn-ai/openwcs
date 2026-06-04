import { useCallback, useEffect, useMemo, useState } from 'react'
import Select from '../ui/Select'
import DatePicker from '../ui/DatePicker'
import DataTable, { Column } from '../ui/DataTable'

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
  const [skuCodes, setSkuCodes] = useState<Record<string, string>>({})

  // SKU id → code, so the log shows readable codes and the filter matches by code.
  useEffect(() => {
    fetch('/api/master-data/skus?size=1000')
      .then((r) => (r.ok ? r.json() : { content: [] }))
      .then((p: { content?: { id: string; code: string }[] }) => {
        const m: Record<string, string> = {}
        for (const s of p.content ?? []) m[s.id] = s.code
        setSkuCodes(m)
      })
      .catch(() => { /* codes fall back to short ids */ })
  }, [])
  const codeFor = useCallback(
    (id: string | null): string => (id ? skuCodes[id] ?? shortId(id) : '—'),
    [skuCodes],
  )

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
      if (sku) {
        const hay = `${codeFor(d.sku)} ${d.sku ?? ''}`.toLowerCase()
        if (!hay.includes(sku)) return false
      }
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
  }, [decorated, fSku, fLocation, fType, fFrom, fTo, codeFor])

  const hasFilters = !!(fSku || fLocation || fType || fFrom || fTo)
  const clearFilters = () => {
    setFSku('')
    setFLocation('')
    setFType('')
    setFFrom('')
    setFTo('')
  }

  const columns: Column<{ e: TxEvent; d: ReturnType<typeof decode> }>[] = [
    {
      key: 'time',
      header: 'Time',
      sortable: true,
      sortValue: ({ e }) => e.position ?? new Date(e.occurredAt).getTime(),
      render: ({ e }) => (
        <span style={{ whiteSpace: 'nowrap', fontFamily: 'var(--font-mono)', fontSize: '.8125rem' }}>
          {fmtTime(e.occurredAt)}
        </span>
      ),
    },
    {
      key: 'type',
      header: 'Type',
      sortable: true,
      sortValue: ({ e }) => e.eventType,
      render: ({ e }) => <span className={`badge ${typeBadgeClass(e.eventType)}`}>{e.eventType}</span>,
    },
    {
      key: 'sku',
      header: 'SKU',
      render: ({ d }) => (
        <span title={d.sku ?? undefined} style={{ fontFamily: 'var(--font-mono)' }}>
          {codeFor(d.sku)}
        </span>
      ),
    },
    {
      key: 'from',
      header: 'From',
      render: ({ d }) => <span title={d.from ?? undefined}>{d.from ?? '—'}</span>,
    },
    {
      key: 'to',
      header: 'To',
      render: ({ d }) => <span title={d.to ?? undefined}>{d.to ?? '—'}</span>,
    },
    {
      key: 'qty',
      header: 'Qty Δ',
      align: 'right',
      render: ({ d }) => {
        const delta = d.delta
        const deltaText =
          delta == null ? '—' : `${delta > 0 ? '+' : ''}${delta}${d.uom ? ` ${d.uom}` : ''}`
        const deltaColor =
          delta == null ? undefined : delta > 0 ? '#8DC63F' : delta < 0 ? '#ff8a80' : undefined
        return <span style={{ fontFamily: 'var(--font-mono)', color: deltaColor }}>{deltaText}</span>
      },
    },
    {
      key: 'actor',
      header: 'Actor',
      render: ({ e }) => <span title={e.actor ?? undefined}>{e.actor ?? '—'}</span>,
    },
    {
      key: 'ref',
      header: 'Ref',
      render: ({ e }) => {
        const ref = e.correlationId ?? e.streamId
        return (
          <span title={ref ?? undefined} style={{ fontFamily: 'var(--font-mono)' }}>
            {shortId(ref)}
          </span>
        )
      },
    },
  ]

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
              placeholder="SKU code contains…"
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
            <DatePicker withTime value={fFrom} onChange={setFFrom} ariaLabel="From" placeholder="Any start" />
          </label>
          <label style={{ display: 'block' }}>
            <span className="muted" style={{ fontSize: '.75rem' }}>To</span>
            <DatePicker withTime value={fTo} onChange={setFTo} ariaLabel="To" placeholder="Any end" />
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
        <DataTable
          columns={columns}
          rows={filtered}
          rowKey={(row) => row.e.eventId}
          pageSize={25}
          initialSort={{ key: 'time', dir: 'desc' }}
          empty={hasFilters ? 'No transactions match the filters.' : 'No transactions recorded yet.'}
          renderExpanded={({ e }) => (
            <>
              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
                  gap: '.5rem 1.5rem',
                  marginBottom: '.75rem',
                }}
              >
                <Meta label="Event id" value={e.eventId} />
                <Meta label="Stream id" value={e.streamId} />
                <Meta label="Seq" value={String(e.seq)} />
                <Meta label="Position" value={e.position != null ? String(e.position) : '—'} />
                <Meta label="Correlation id" value={e.correlationId ?? '—'} />
                <Meta label="Payload version" value={String(e.payloadVersion)} />
                <Meta label="Occurred at" value={fmtTime(e.occurredAt)} />
                <Meta label="Recorded at" value={fmtTime(e.recordedAt)} />
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
                {JSON.stringify(e.payload, null, 2)}
              </pre>
            </>
          )}
        />
      </div>
    </div>
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
