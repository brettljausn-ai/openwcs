// Inbound orders & ASNs.
//
// UI over EXISTING endpoints only:
//   - order-management (/api/orders**)  — list/get inbound orders, post line receipts
//   - integration-host Host API (/api/host/asns) — create an ASN (becomes an inbound order)
//   - master-data (/api/master-data/**) — warehouses, SKUs, receiving locations (for selectors)
//
// An ASN pushed via the Host API materialises as an INBOUND order, so this screen reads
// the order-management order list (filtered to orderType=INBOUND) as the single source of
// truth for what's expected/received, and writes receipts back as line transactions.

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { useWarehouse } from '../warehouse/WarehouseContext'
import Select from '../ui/Select'

// ---------------------------------------------------------------- API types
type OrderStatus =
  | 'CREATED'
  | 'RELEASED'
  | 'ALLOCATED'
  | 'PARTIALLY_ALLOCATED'
  | 'NOT_FULFILLABLE'
  | 'CUBING_FAILED'
  | 'SHIPPED'
  | 'CANCELLED'

interface Transaction {
  id: string
  txnType: string
  qty: number
  locationId: string
  postedAt: string
  actor?: string | null
}

interface OrderLine {
  id: string
  lineNo: number
  skuId: string
  qty: number
  allocatedQty?: number
  postedQty?: number
  status?: string
  transactions?: Transaction[]
}

interface Order {
  id: string
  orderRef: string
  orderType: string
  warehouseId: string
  customerRef?: string | null
  status: OrderStatus
  statusDetail?: string | null
  priority?: number
  createdAt?: string
  lines?: OrderLine[]
}

interface OrderPage {
  content: Order[]
  totalElements?: number
}

interface Warehouse {
  id: string
  code: string
  name: string
}

interface Sku {
  id: string
  code: string
  description?: string
}

interface Location {
  id: string
  code: string
  purpose?: string
}

// ---------------------------------------------------------------- helpers
const STATUS_FILTERS: OrderStatus[] = [
  'CREATED',
  'RELEASED',
  'ALLOCATED',
  'PARTIALLY_ALLOCATED',
  'NOT_FULFILLABLE',
  'SHIPPED',
  'CANCELLED',
]

function statusBadgeClass(status: OrderStatus): string {
  switch (status) {
    case 'SHIPPED':
    case 'ALLOCATED':
      return 'badge badge-success'
    case 'CANCELLED':
    case 'NOT_FULFILLABLE':
    case 'CUBING_FAILED':
      return 'badge badge-danger'
    case 'PARTIALLY_ALLOCATED':
    case 'RELEASED':
      return 'badge badge-warning'
    default:
      return 'badge badge-info'
  }
}

function fmtDate(iso?: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString()
}

async function problemMessage(res: Response): Promise<string> {
  try {
    const body = await res.json()
    return body.detail || body.title || body.message || `${res.status} ${res.statusText}`
  } catch {
    return `${res.status} ${res.statusText}`
  }
}

// A line is "fully received" once its posted (received) qty meets the expected qty.
function receivedQty(line: OrderLine): number {
  return line.postedQty ?? 0
}
function isLineReceived(line: OrderLine): boolean {
  return receivedQty(line) >= line.qty
}

// ---------------------------------------------------------------- component
export default function InboundScreen() {
  const { roles } = useAuth()
  const canReceive = roles.includes('ADMIN') || roles.includes('SUPERVISOR') || roles.includes('OPERATOR')

  const { currentWarehouseId: warehouseId, current: currentWarehouse } = useWarehouse()
  const [statusFilter, setStatusFilter] = useState<string>('')
  const [orders, setOrders] = useState<Order[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [skus, setSkus] = useState<Sku[]>([])
  const [locations, setLocations] = useState<Location[]>([])

  const [detail, setDetail] = useState<Order | null>(null)

  const skuLabel = useCallback(
    (id: string) => {
      const s = skus.find((x) => x.id === id)
      return s ? `${s.code}${s.description ? ` — ${s.description}` : ''}` : id
    },
    [skus],
  )

  // --- load SKUs once (for line labels + create form)
  useEffect(() => {
    let alive = true
    fetch('/api/master-data/skus?size=500')
      .then((r) => (r.ok ? r.json() : Promise.reject(r)))
      .then((page) => {
        if (alive) setSkus(page.content ?? [])
      })
      .catch(() => {
        /* labels fall back to raw ids */
      })
    return () => {
      alive = false
    }
  }, [])

  // --- load receiving locations whenever the warehouse changes
  useEffect(() => {
    if (!warehouseId) {
      setLocations([])
      return
    }
    let alive = true
    fetch(`/api/master-data/locations?warehouseId=${encodeURIComponent(warehouseId)}&size=500`)
      .then((r) => (r.ok ? r.json() : Promise.reject(r)))
      .then((page) => {
        if (alive) setLocations(page.content ?? [])
      })
      .catch(() => {
        if (alive) setLocations([])
      })
    return () => {
      alive = false
    }
  }, [warehouseId])

  // --- load inbound orders
  const loadOrders = useCallback(async () => {
    if (!warehouseId) {
      setOrders([])
      return
    }
    setLoading(true)
    setError(null)
    try {
      const params = new URLSearchParams({ warehouseId, size: '200' })
      if (statusFilter) params.set('status', statusFilter)
      const res = await fetch(`/api/orders?${params.toString()}`)
      if (!res.ok) throw new Error(await problemMessage(res))
      const page: OrderPage = await res.json()
      // Order-management lists every order type; this screen is inbound only.
      setOrders((page.content ?? []).filter((o) => o.orderType === 'INBOUND'))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load inbound orders.')
      setOrders([])
    } finally {
      setLoading(false)
    }
  }, [warehouseId, statusFilter])

  useEffect(() => {
    loadOrders()
  }, [loadOrders])

  // --- refresh a single order (after receiving) and keep the detail pane in sync
  const refreshOrder = useCallback(async (id: string): Promise<Order | null> => {
    const res = await fetch(`/api/orders/${id}`)
    if (!res.ok) return null
    const fresh: Order = await res.json()
    setOrders((prev) => prev.map((o) => (o.id === id ? fresh : o)))
    setDetail((prev) => (prev && prev.id === id ? fresh : prev))
    return fresh
  }, [])

  const warehouseLabel = currentWarehouse ? `${currentWarehouse.code} — ${currentWarehouse.name}` : warehouseId

  return (
    <div className="app-content">
      <div className="page-head">
        <div className="eyebrow">Operations</div>
        <h1>Inbound orders</h1>
        <p>Expected receipts (ASNs) and inbound orders — track lines, quantities and receive goods.</p>
      </div>

      {/* Filters + actions */}
      <div className="toolbar">
        <Select
          style={{ maxWidth: 220 }}
          value={statusFilter}
          onChange={(v) => setStatusFilter(v)}
          ariaLabel="Status filter"
          options={[
            { value: '', label: 'All statuses' },
            ...STATUS_FILTERS.map((s) => ({ value: s, label: s.replace(/_/g, ' ') })),
          ]}
        />

        <button type="button" className="btn btn-ghost btn-sm" onClick={loadOrders} disabled={!warehouseId || loading}>
          Refresh
        </button>

        <div className="spacer" />

        <span className="muted" style={{ fontSize: '.82rem' }}>
          Inbound orders &amp; ASNs are owned by the host system — received here, not created.
        </span>
      </div>

      {error && <div className="alert alert-danger">{error}</div>}

      {!warehouseId && !error && (
        <div className="glass card-pad" style={{ maxWidth: 560 }}>
          <p className="muted" style={{ margin: 0 }}>
            Select a warehouse to view inbound orders. None are available yet — create one under Master data.
          </p>
        </div>
      )}

      {/* Orders table */}
      {warehouseId && (
        <div className="glass" style={{ overflow: 'auto' }}>
          <table>
            <thead>
              <tr>
                <th>Reference</th>
                <th>Status</th>
                <th>Supplier / Cust.</th>
                <th style={{ textAlign: 'right' }}>Lines</th>
                <th style={{ textAlign: 'right' }}>Expected</th>
                <th style={{ textAlign: 'right' }}>Received</th>
                <th>Created</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr>
                  <td colSpan={8} style={{ textAlign: 'center', padding: '1.5rem' }}>
                    <span className="spin" />
                  </td>
                </tr>
              )}
              {!loading && orders.length === 0 && (
                <tr>
                  <td colSpan={8} className="muted" style={{ textAlign: 'center', padding: '1.5rem' }}>
                    No inbound orders for {warehouseLabel}.
                  </td>
                </tr>
              )}
              {!loading &&
                orders.map((o) => {
                  const lines = o.lines ?? []
                  const expected = lines.reduce((s, l) => s + (l.qty || 0), 0)
                  const received = lines.reduce((s, l) => s + receivedQty(l), 0)
                  return (
                    <tr key={o.id} style={{ cursor: 'pointer' }} onClick={() => setDetail(o)}>
                      <td>
                        <strong>{o.orderRef}</strong>
                      </td>
                      <td>
                        <span className={statusBadgeClass(o.status)}>{o.status.replace(/_/g, ' ')}</span>
                      </td>
                      <td className="muted">{o.customerRef || '—'}</td>
                      <td style={{ textAlign: 'right' }}>{lines.length}</td>
                      <td style={{ textAlign: 'right' }}>{expected}</td>
                      <td style={{ textAlign: 'right' }}>{received}</td>
                      <td className="muted">{fmtDate(o.createdAt)}</td>
                      <td style={{ textAlign: 'right' }}>
                        <button
                          type="button"
                          className="btn btn-ghost btn-sm"
                          onClick={(e) => {
                            e.stopPropagation()
                            setDetail(o)
                          }}
                        >
                          View
                        </button>
                      </td>
                    </tr>
                  )
                })}
            </tbody>
          </table>
        </div>
      )}

      {detail && (
        <DetailDialog
          order={detail}
          skuLabel={skuLabel}
          locations={locations}
          canReceive={canReceive}
          onClose={() => setDetail(null)}
          onReceived={refreshOrder}
        />
      )}

    </div>
  )
}

// ---------------------------------------------------------------- detail + receive
function DetailDialog({
  order,
  skuLabel,
  locations,
  canReceive,
  onClose,
  onReceived,
}: {
  order: Order
  skuLabel: (id: string) => string
  locations: Location[]
  canReceive: boolean
  onClose: () => void
  onReceived: (id: string) => Promise<Order | null>
}) {
  const [receiveLine, setReceiveLine] = useState<OrderLine | null>(null)

  const lines = order.lines ?? []
  const terminal = order.status === 'CANCELLED' || order.status === 'SHIPPED'

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className="dialog"
        style={{ maxWidth: 'min(820px, calc(100vw - 2rem))' }}
        onClick={(e) => e.stopPropagation()}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '.75rem', marginBottom: '1rem' }}>
          <h2 style={{ margin: 0, flex: 1 }}>{order.orderRef}</h2>
          <span className={statusBadgeClass(order.status)}>{order.status.replace(/_/g, ' ')}</span>
        </div>

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '.25rem 1.5rem', marginBottom: '1rem' }} className="muted">
          <span>Type: INBOUND</span>
          {order.customerRef && <span>Supplier / Cust.: {order.customerRef}</span>}
          <span>Created: {fmtDate(order.createdAt)}</span>
          {order.statusDetail && <span>Note: {order.statusDetail}</span>}
        </div>

        <table>
          <thead>
            <tr>
              <th style={{ width: 40 }}>#</th>
              <th>SKU</th>
              <th style={{ textAlign: 'right' }}>Expected</th>
              <th style={{ textAlign: 'right' }}>Received</th>
              <th>Line status</th>
              {canReceive && <th />}
            </tr>
          </thead>
          <tbody>
            {lines.length === 0 && (
              <tr>
                <td colSpan={canReceive ? 6 : 5} className="muted" style={{ textAlign: 'center', padding: '1rem' }}>
                  This order has no lines.
                </td>
              </tr>
            )}
            {lines.map((l) => {
              const done = isLineReceived(l)
              return (
                <tr key={l.id ?? l.lineNo}>
                  <td>{l.lineNo}</td>
                  <td>{skuLabel(l.skuId)}</td>
                  <td style={{ textAlign: 'right' }}>{l.qty}</td>
                  <td style={{ textAlign: 'right' }}>{receivedQty(l)}</td>
                  <td>
                    <span className={done ? 'badge badge-success' : 'badge badge-info'}>
                      {done ? 'Received' : 'Open'}
                    </span>
                  </td>
                  {canReceive && (
                    <td style={{ textAlign: 'right' }}>
                      <button
                        type="button"
                        className="btn btn-outline btn-sm"
                        disabled={terminal}
                        onClick={() => setReceiveLine(l)}
                      >
                        Receive
                      </button>
                    </td>
                  )}
                </tr>
              )
            })}
          </tbody>
        </table>

        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            Close
          </button>
        </div>

        {receiveLine && (
          <ReceiveDialog
            order={order}
            line={receiveLine}
            skuLabel={skuLabel}
            locations={locations}
            onClose={() => setReceiveLine(null)}
            onDone={async () => {
              setReceiveLine(null)
              await onReceived(order.id)
            }}
          />
        )}
      </div>
    </div>
  )
}

function ReceiveDialog({
  order,
  line,
  skuLabel,
  locations,
  onClose,
  onDone,
}: {
  order: Order
  line: OrderLine
  skuLabel: (id: string) => string
  locations: Location[]
  onClose: () => void
  onDone: () => Promise<void>
}) {
  const remaining = Math.max(0, line.qty - receivedQty(line))
  const [qty, setQty] = useState<string>(remaining ? String(remaining) : '1')
  const [locationId, setLocationId] = useState<string>(locations[0]?.id ?? '')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  async function submit() {
    setErr(null)
    const n = Number(qty)
    if (!Number.isFinite(n) || n <= 0) {
      setErr('Enter a quantity greater than zero.')
      return
    }
    if (!locationId) {
      setErr('Select a receiving location.')
      return
    }
    setBusy(true)
    try {
      const res = await fetch(`/api/orders/${order.id}/lines/${line.lineNo}/transactions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ qty: n, locationId, uomCode: 'EACH' }),
      })
      if (!res.ok) throw new Error(await problemMessage(res))
      await onDone()
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to post receipt.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="dialog" onClick={(e) => e.stopPropagation()}>
        <h2>Receive line {line.lineNo}</h2>
        <p className="muted" style={{ marginTop: '-.5rem' }}>
          {skuLabel(line.skuId)} · expected {line.qty}, received {receivedQty(line)}
        </p>

        {err && <div className="alert alert-danger">{err}</div>}

        <label className="muted" style={{ display: 'block', marginBottom: '.25rem' }}>
          Quantity
        </label>
        <input
          className="form-control"
          type="number"
          min="0"
          step="any"
          value={qty}
          onChange={(e) => setQty(e.target.value)}
        />

        <label className="muted" style={{ display: 'block', margin: '.75rem 0 .25rem' }}>
          Receiving location
        </label>
        <Select
          value={locationId}
          onChange={(v) => setLocationId(v)}
          ariaLabel="Receiving location"
          options={
            locations.length === 0
              ? [{ value: '', label: 'No locations available' }]
              : locations.map((l) => ({
                  value: l.id,
                  label: `${l.code}${l.purpose ? ` (${l.purpose})` : ''}`,
                }))
          }
        />

        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button type="button" className="btn btn-primary" onClick={submit} disabled={busy}>
            {busy ? 'Posting…' : 'Post receipt'}
          </button>
        </div>
      </div>
    </div>
  )
}
