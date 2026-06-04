import { useCallback, useEffect, useMemo, useState } from 'react'
import { useWarehouse } from '../warehouse/WarehouseContext'

// Outbound Orders screen — UI-only against existing endpoints:
//   order-management:  GET/POST /api/orders, /api/orders/{id}, /api/orders/{id}/{release|cancel|ship}
//   allocation:        POST /api/allocation/orders, GET /api/allocation/orders/{orderRef},
//                      POST /api/allocation/orders/{orderRef}/cancel
//   master-data:       GET /api/master-data/warehouses, GET /api/master-data/skus
// All requests use bare fetch('/api/...'); the global interceptor attaches the Bearer token.

// ----------------------------------------------------------------- types
interface Warehouse { id: string; code: string; name: string }
interface Sku { id: string; code: string; description?: string }

interface ShipToAddress {
  name?: string; line1?: string; line2?: string; city?: string; region?: string
  postcode?: string; country?: string; contact?: string; phone?: string
}

interface OrderLine {
  id: string; lineNo: number; skuId: string
  qty: number; allocatedQty?: number; postedQty?: number
  status: string; reservationId?: string | null
}

interface Order {
  id: string; orderRef: string; orderType: string; warehouseId: string
  customerRef?: string; status: string; statusDetail?: string
  serviceCode?: string; routeCode?: string; shipTo?: ShipToAddress | null
  labelTemplateCode?: string; priority: number
  dispatchBy?: string | null; createdAt?: string
  lines: OrderLine[]
}

interface Pick {
  locationId?: string; qty?: number; reservationId?: string
  uomBreakdown?: Record<string, number>
}
interface AllocLine {
  lineNo: number; skuId: string; requestedQty?: number; allocatedQty?: number
  status: string; picks?: Pick[]
}
interface ShipperContent { lineNo: number; skuId: string; qty: number }
interface DispatchLabel { templateCode?: string; barcode?: string; fields?: Record<string, string> }
interface Shipper {
  shipperUnitId?: string; seqNo: number; shipperId?: string; shipperCode?: string
  contents?: ShipperContent[]; grossWeightG?: number; usedVolumeMm3?: number
  dispatchLabel?: DispatchLabel | null
}
interface Allocation {
  id: string; orderRef: string; warehouseId: string; status: string
  statusDetail?: string; cubingMode?: string; lines?: AllocLine[]; shippers?: Shipper[]
}

interface PageResponse<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }

// ----------------------------------------------------------------- constants
const STATUSES = [
  'CREATED', 'RELEASED', 'PARTIALLY_ALLOCATED', 'ALLOCATED',
  'NOT_FULFILLABLE', 'CUBING_FAILED', 'SHIPPED', 'CANCELLED',
]

function statusBadge(status: string): string {
  switch (status) {
    case 'ALLOCATED':
    case 'SHIPPED':
      return 'badge-success'
    case 'RELEASED':
    case 'PARTIALLY_ALLOCATED':
      return 'badge-info'
    case 'NOT_FULFILLABLE':
    case 'CUBING_FAILED':
    case 'CANCELLED':
      return 'badge-danger'
    case 'CREATED':
    default:
      return 'badge-warning'
  }
}
function lineBadge(status: string): string {
  switch (status) {
    case 'ALLOCATED': return 'badge-success'
    case 'SHORT': return 'badge-danger'
    case 'CANCELLED': return 'badge-danger'
    default: return 'badge-warning'
  }
}

function fmtDate(iso?: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return isNaN(d.getTime()) ? '—' : d.toLocaleString()
}
function fmtNum(n?: number | null): string {
  if (n === undefined || n === null) return '—'
  return String(n)
}

async function readError(res: Response): Promise<string> {
  try {
    const body = await res.json()
    return body?.detail || body?.message || body?.error || `Request failed (${res.status})`
  } catch {
    return `Request failed (${res.status})`
  }
}

// ----------------------------------------------------------------- component
export default function OutboundScreen() {
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const [statusFilter, setStatusFilter] = useState<string>('')

  const [orders, setOrders] = useState<Order[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string>('')

  const [selected, setSelected] = useState<Order | null>(null)
  const [showCreate, setShowCreate] = useState(false)

  const skuById = useMemo(() => {
    const m = new Map<string, Sku>()
    return m // populated lazily by detail/create via a shared cache below
  }, [])

  // -- load orders
  const loadOrders = useCallback(() => {
    if (!warehouseId) { setOrders([]); return }
    setLoading(true)
    setError('')
    const params = new URLSearchParams({ warehouseId, size: '100' })
    if (statusFilter) params.set('status', statusFilter)
    fetch(`/api/orders?${params.toString()}`)
      .then(async (r) => (r.ok ? r.json() : Promise.reject(await readError(r))))
      .then((data: PageResponse<Order>) => setOrders(data.content || []))
      .catch((e) => setError(typeof e === 'string' ? e : 'Could not load orders.'))
      .finally(() => setLoading(false))
  }, [warehouseId, statusFilter])

  useEffect(() => { loadOrders() }, [loadOrders])

  const outbound = orders.filter((o) => o.orderType === 'OUTBOUND')

  return (
    <div className="app-content">
      <div className="page-head">
        <div className="eyebrow">Outbound</div>
        <h1>Outbound orders</h1>
        <p>Release, allocate, cube and dispatch customer orders.</p>
      </div>

      {error && <div className="alert alert-danger">{error}</div>}

      <div className="glass" style={{ padding: '1rem 1.25rem', marginBottom: '1.25rem' }}>
        <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <div style={{ minWidth: 200 }}>
            <label>Status</label>
            <select className="form-control" value={statusFilter}
                    onChange={(e) => setStatusFilter(e.target.value)}>
              <option value="">All statuses</option>
              {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </div>
          <div style={{ flex: 1 }} />
          <button className="btn btn-ghost" onClick={loadOrders} disabled={!warehouseId || loading}>
            Refresh
          </button>
          <button className="btn btn-primary" onClick={() => setShowCreate(true)} disabled={!warehouseId}>
            New order
          </button>
        </div>
      </div>

      <div className="glass" style={{ padding: '0', overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: '2rem', textAlign: 'center' }}>
            <span className="spin" /> <span className="muted" style={{ marginLeft: '.5rem' }}>Loading…</span>
          </div>
        ) : outbound.length === 0 ? (
          <div className="muted" style={{ padding: '2rem', textAlign: 'center' }}>
            No outbound orders {statusFilter ? `with status ${statusFilter}` : ''} in this warehouse.
          </div>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Order ref</th>
                <th>Customer</th>
                <th>Status</th>
                <th style={{ textAlign: 'right' }}>Priority</th>
                <th style={{ textAlign: 'right' }}>Lines</th>
                <th>Service</th>
                <th>Route</th>
                <th>Dispatch by</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              {outbound.map((o) => (
                <tr key={o.id} style={{ cursor: 'pointer' }} onClick={() => setSelected(o)}>
                  <td><strong>{o.orderRef}</strong></td>
                  <td>{o.customerRef || '—'}</td>
                  <td><span className={`badge ${statusBadge(o.status)}`}>{o.status}</span></td>
                  <td style={{ textAlign: 'right' }}>{o.priority}</td>
                  <td style={{ textAlign: 'right' }}>{o.lines?.length ?? 0}</td>
                  <td>{o.serviceCode || '—'}</td>
                  <td>{o.routeCode || '—'}</td>
                  <td>{fmtDate(o.dispatchBy)}</td>
                  <td>{fmtDate(o.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {selected && (
        <OrderDetail
          orderId={selected.id}
          onClose={() => setSelected(null)}
          onChanged={() => { loadOrders() }}
          skuCache={skuById}
        />
      )}

      {showCreate && warehouseId && (
        <CreateOrderDialog
          warehouseId={warehouseId}
          onClose={() => setShowCreate(false)}
          onCreated={() => { setShowCreate(false); loadOrders() }}
        />
      )}
    </div>
  )
}

// ----------------------------------------------------------------- SKU label helper
function useSkuLabels(skuCache: Map<string, Sku>, skuIds: string[]) {
  const [, force] = useState(0)
  useEffect(() => {
    const missing = skuIds.filter((id) => id && !skuCache.has(id))
    if (missing.length === 0) return
    let cancelled = false
    fetch('/api/master-data/skus?size=500')
      .then((r) => (r.ok ? r.json() : Promise.reject(r)))
      .then((data: PageResponse<Sku>) => {
        if (cancelled) return
        for (const s of data.content || []) skuCache.set(s.id, s)
        force((n) => n + 1)
      })
      .catch(() => { /* fall back to raw ids */ })
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [skuIds.join(',')])
  return (id: string) => skuCache.get(id)?.code || id
}

// ----------------------------------------------------------------- detail modal
function OrderDetail({ orderId, onClose, onChanged, skuCache }: {
  orderId: string
  onClose: () => void
  onChanged: () => void
  skuCache: Map<string, Sku>
}) {
  const [order, setOrder] = useState<Order | null>(null)
  const [alloc, setAlloc] = useState<Allocation | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')

  const loadOrder = useCallback(() => {
    setLoading(true)
    return fetch(`/api/orders/${orderId}`)
      .then(async (r) => (r.ok ? r.json() : Promise.reject(await readError(r))))
      .then((o: Order) => setOrder(o))
      .catch((e) => setErr(typeof e === 'string' ? e : 'Could not load order.'))
      .finally(() => setLoading(false))
  }, [orderId])

  // allocation is keyed by orderRef; load it once we know the ref
  const loadAlloc = useCallback((orderRef: string) => {
    fetch(`/api/allocation/orders/${encodeURIComponent(orderRef)}`)
      .then((r) => (r.ok ? r.json() : r.status === 404 ? null : Promise.reject(r)))
      .then((a: Allocation | null) => setAlloc(a))
      .catch(() => setAlloc(null))
  }, [])

  useEffect(() => { loadOrder() }, [loadOrder])
  useEffect(() => { if (order?.orderRef) loadAlloc(order.orderRef) }, [order?.orderRef, loadAlloc])

  const skuIds = useMemo(() => {
    const ids = new Set<string>()
    order?.lines?.forEach((l) => ids.add(l.skuId))
    alloc?.lines?.forEach((l) => ids.add(l.skuId))
    alloc?.shippers?.forEach((s) => s.contents?.forEach((c) => ids.add(c.skuId)))
    return [...ids]
  }, [order, alloc])
  const skuLabel = useSkuLabels(skuCache, skuIds)

  function act(kind: 'release' | 'allocate' | 'cancel' | 'ship') {
    if (!order) return
    setBusy(true)
    setErr('')
    let p: Promise<Response>
    if (kind === 'allocate') {
      const body = {
        orderRef: order.orderRef,
        warehouseId: order.warehouseId,
        lines: (order.lines || []).map((l) => ({ lineNo: l.lineNo, skuId: l.skuId, qty: l.qty })),
        dispatch: order.shipTo || order.serviceCode || order.routeCode ? {
          shipTo: order.shipTo || undefined,
          serviceCode: order.serviceCode,
          routeCode: order.routeCode,
          labelTemplateCode: order.labelTemplateCode,
        } : undefined,
      }
      p = fetch('/api/allocation/orders', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      })
    } else {
      p = fetch(`/api/orders/${order.id}/${kind}`, { method: 'POST' })
    }
    p.then(async (r) => {
      if (!r.ok) throw await readError(r)
      await loadOrder()
      loadAlloc(order.orderRef)
      onChanged()
    })
      .catch((e) => setErr(typeof e === 'string' ? e : `Could not ${kind} order.`))
      .finally(() => setBusy(false))
  }

  const status = order?.status
  const canRelease = status === 'CREATED'
  const canAllocate = status === 'RELEASED' || status === 'PARTIALLY_ALLOCATED' || status === 'NOT_FULFILLABLE'
  const canShip = status === 'ALLOCATED'
  const canCancel = status && !['SHIPPED', 'CANCELLED'].includes(status)

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="dialog" style={{ maxWidth: 860, maxHeight: '90vh', overflow: 'auto' }}
           onClick={(e) => e.stopPropagation()}>
        {loading || !order ? (
          <div style={{ padding: '1rem', textAlign: 'center' }}>
            <span className="spin" /> <span className="muted">Loading order…</span>
          </div>
        ) : (
          <>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '1rem' }}>
              <div>
                <h2 style={{ marginBottom: '.35rem' }}>{order.orderRef}</h2>
                <span className={`badge ${statusBadge(order.status)}`}>{order.status}</span>
                {order.statusDetail && <span className="muted" style={{ marginLeft: '.6rem' }}>{order.statusDetail}</span>}
              </div>
              <button className="btn btn-ghost btn-sm" onClick={onClose}>Close</button>
            </div>

            {err && <div className="alert alert-danger">{err}</div>}

            {/* summary grid */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: '.75rem', margin: '1rem 0' }}>
              <Field label="Customer" value={order.customerRef} />
              <Field label="Priority" value={String(order.priority)} />
              <Field label="Service" value={order.serviceCode} />
              <Field label="Route" value={order.routeCode} />
              <Field label="Dispatch by" value={fmtDate(order.dispatchBy)} />
              <Field label="Created" value={fmtDate(order.createdAt)} />
              <Field label="Label template" value={order.labelTemplateCode} />
            </div>

            {order.shipTo && (
              <div className="muted" style={{ marginBottom: '1rem', fontSize: '.85rem' }}>
                <strong>Ship to:</strong>{' '}
                {[order.shipTo.name, order.shipTo.line1, order.shipTo.city, order.shipTo.postcode, order.shipTo.country]
                  .filter(Boolean).join(', ') || '—'}
              </div>
            )}

            {/* lines */}
            <h3 style={{ margin: '1rem 0 .5rem' }}>Lines</h3>
            <table>
              <thead>
                <tr>
                  <th>#</th><th>SKU</th>
                  <th style={{ textAlign: 'right' }}>Qty</th>
                  <th style={{ textAlign: 'right' }}>Allocated</th>
                  <th style={{ textAlign: 'right' }}>Posted</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {order.lines.map((l) => (
                  <tr key={l.id}>
                    <td>{l.lineNo}</td>
                    <td>{skuLabel(l.skuId)}</td>
                    <td style={{ textAlign: 'right' }}>{fmtNum(l.qty)}</td>
                    <td style={{ textAlign: 'right' }}>{fmtNum(l.allocatedQty)}</td>
                    <td style={{ textAlign: 'right' }}>{fmtNum(l.postedQty)}</td>
                    <td><span className={`badge ${lineBadge(l.status)}`}>{l.status}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>

            {/* allocation + cubing */}
            {alloc ? (
              <>
                <h3 style={{ margin: '1.25rem 0 .5rem' }}>
                  Allocation{' '}
                  <span className={`badge ${statusBadge(alloc.status)}`}>{alloc.status}</span>
                  {alloc.cubingMode && <span className="muted" style={{ marginLeft: '.6rem', fontSize: '.8rem' }}>cubing: {alloc.cubingMode}</span>}
                </h3>
                {alloc.statusDetail && <div className="muted" style={{ fontSize: '.85rem', marginBottom: '.5rem' }}>{alloc.statusDetail}</div>}
                {alloc.lines && alloc.lines.length > 0 && (
                  <table>
                    <thead>
                      <tr>
                        <th>#</th><th>SKU</th>
                        <th style={{ textAlign: 'right' }}>Requested</th>
                        <th style={{ textAlign: 'right' }}>Allocated</th>
                        <th>Status</th>
                        <th>Picks</th>
                      </tr>
                    </thead>
                    <tbody>
                      {alloc.lines.map((l) => (
                        <tr key={l.lineNo}>
                          <td>{l.lineNo}</td>
                          <td>{skuLabel(l.skuId)}</td>
                          <td style={{ textAlign: 'right' }}>{fmtNum(l.requestedQty)}</td>
                          <td style={{ textAlign: 'right' }}>{fmtNum(l.allocatedQty)}</td>
                          <td><span className={`badge ${lineBadge(l.status)}`}>{l.status}</span></td>
                          <td className="muted" style={{ fontSize: '.8rem' }}>
                            {(l.picks && l.picks.length) ? l.picks.map((p, i) => (
                              <div key={i}>
                                {fmtNum(p.qty)} @ loc {p.locationId ? p.locationId.slice(0, 8) : '—'}
                                {p.uomBreakdown && Object.keys(p.uomBreakdown).length
                                  ? ` (${Object.entries(p.uomBreakdown).map(([u, q]) => `${q} ${u}`).join(', ')})`
                                  : ''}
                              </div>
                            )) : '—'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}

                {/* cartons / shippers */}
                {alloc.shippers && alloc.shippers.length > 0 && (
                  <>
                    <h3 style={{ margin: '1.25rem 0 .5rem' }}>Cartons ({alloc.shippers.length})</h3>
                    <table>
                      <thead>
                        <tr>
                          <th>Seq</th><th>Shipper</th>
                          <th>Contents</th>
                          <th style={{ textAlign: 'right' }}>Weight (g)</th>
                          <th style={{ textAlign: 'right' }}>Volume (mm³)</th>
                          <th>Dispatch label</th>
                        </tr>
                      </thead>
                      <tbody>
                        {alloc.shippers.map((s) => (
                          <tr key={s.shipperUnitId || s.seqNo}>
                            <td>{s.seqNo}</td>
                            <td>{s.shipperCode || '—'}</td>
                            <td className="muted" style={{ fontSize: '.8rem' }}>
                              {(s.contents && s.contents.length)
                                ? s.contents.map((c, i) => (
                                  <div key={i}>{fmtNum(c.qty)} × {skuLabel(c.skuId)} (line {c.lineNo})</div>
                                )) : '—'}
                            </td>
                            <td style={{ textAlign: 'right' }}>{fmtNum(s.grossWeightG)}</td>
                            <td style={{ textAlign: 'right' }}>{fmtNum(s.usedVolumeMm3)}</td>
                            <td className="muted" style={{ fontSize: '.8rem' }}>
                              {s.dispatchLabel?.barcode
                                ? <>{s.dispatchLabel.barcode}{s.dispatchLabel.templateCode ? ` (${s.dispatchLabel.templateCode})` : ''}</>
                                : '—'}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </>
                )}
              </>
            ) : (
              <div className="muted" style={{ margin: '1rem 0', fontSize: '.85rem' }}>
                No allocation yet. Release the order, then allocate to reserve stock and cube into cartons.
              </div>
            )}

            {/* actions */}
            <div className="dialog-actions" style={{ flexWrap: 'wrap' }}>
              {canRelease && <button className="btn btn-primary" disabled={busy} onClick={() => act('release')}>Release</button>}
              {canAllocate && <button className="btn btn-primary" disabled={busy} onClick={() => act('allocate')}>Allocate &amp; cube</button>}
              {canShip && <button className="btn btn-primary" disabled={busy} onClick={() => act('ship')}>Dispatch</button>}
              {canCancel && <button className="btn btn-danger" disabled={busy} onClick={() => act('cancel')}>Cancel</button>}
              {busy && <span className="spin" />}
            </div>
          </>
        )}
      </div>
    </div>
  )
}

function Field({ label, value }: { label: string; value?: string | null }) {
  return (
    <div>
      <div className="muted" style={{ fontSize: '.7rem', textTransform: 'uppercase', letterSpacing: '.05em' }}>{label}</div>
      <div>{value || '—'}</div>
    </div>
  )
}

// ----------------------------------------------------------------- create dialog
interface DraftLine { skuId: string; qty: string }

function CreateOrderDialog({ warehouseId, onClose, onCreated }: {
  warehouseId: string
  onClose: () => void
  onCreated: () => void
}) {
  const [skus, setSkus] = useState<Sku[]>([])
  const [orderRef, setOrderRef] = useState('')
  const [customerRef, setCustomerRef] = useState('')
  const [priority, setPriority] = useState('5')
  const [serviceCode, setServiceCode] = useState('')
  const [routeCode, setRouteCode] = useState('')
  const [dispatchBy, setDispatchBy] = useState('')
  const [lines, setLines] = useState<DraftLine[]>([{ skuId: '', qty: '' }])
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')

  useEffect(() => {
    let cancelled = false
    fetch('/api/master-data/skus?size=500')
      .then((r) => (r.ok ? r.json() : Promise.reject(r)))
      .then((data: PageResponse<Sku>) => { if (!cancelled) setSkus(data.content || []) })
      .catch(() => { if (!cancelled) setErr('Could not load SKUs.') })
    return () => { cancelled = true }
  }, [])

  function setLine(i: number, patch: Partial<DraftLine>) {
    setLines((ls) => ls.map((l, idx) => (idx === i ? { ...l, ...patch } : l)))
  }
  function addLine() { setLines((ls) => [...ls, { skuId: '', qty: '' }]) }
  function removeLine(i: number) { setLines((ls) => ls.filter((_, idx) => idx !== i)) }

  function submit() {
    setErr('')
    if (!orderRef.trim()) { setErr('Order ref is required.'); return }
    const validLines = lines.filter((l) => l.skuId && l.qty)
    if (validLines.length === 0) { setErr('Add at least one line with a SKU and quantity.'); return }
    for (const l of validLines) {
      const q = Number(l.qty)
      if (!(q > 0)) { setErr('Each line quantity must be greater than zero.'); return }
    }
    const body = {
      orderRef: orderRef.trim(),
      warehouseId,
      orderType: 'OUTBOUND',
      customerRef: customerRef.trim() || undefined,
      priority: priority ? Number(priority) : undefined,
      dispatchBy: dispatchBy ? new Date(dispatchBy).toISOString() : undefined,
      serviceCode: serviceCode.trim() || undefined,
      routeCode: routeCode.trim() || undefined,
      lines: validLines.map((l) => ({ skuId: l.skuId, qty: Number(l.qty) })),
    }
    setBusy(true)
    fetch('/api/orders', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    })
      .then(async (r) => { if (!r.ok) throw await readError(r); onCreated() })
      .catch((e) => setErr(typeof e === 'string' ? e : 'Could not create order.'))
      .finally(() => setBusy(false))
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="dialog" style={{ maxWidth: 720, maxHeight: '90vh', overflow: 'auto' }}
           onClick={(e) => e.stopPropagation()}>
        <h2>New outbound order</h2>
        {err && <div className="alert alert-danger">{err}</div>}

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '.75rem' }}>
          <div>
            <label>Order ref *</label>
            <input className="form-control" value={orderRef} onChange={(e) => setOrderRef(e.target.value)} placeholder="SO-1001" />
          </div>
          <div>
            <label>Customer ref</label>
            <input className="form-control" value={customerRef} onChange={(e) => setCustomerRef(e.target.value)} />
          </div>
          <div>
            <label>Priority</label>
            <input className="form-control" type="number" value={priority} onChange={(e) => setPriority(e.target.value)} />
          </div>
          <div>
            <label>Dispatch by</label>
            <input className="form-control" type="datetime-local" value={dispatchBy} onChange={(e) => setDispatchBy(e.target.value)} />
          </div>
          <div>
            <label>Service code</label>
            <input className="form-control" value={serviceCode} onChange={(e) => setServiceCode(e.target.value)} placeholder="EXPRESS" />
          </div>
          <div>
            <label>Route code</label>
            <input className="form-control" value={routeCode} onChange={(e) => setRouteCode(e.target.value)} placeholder="CENTRAL_LONDON" />
          </div>
        </div>

        <h3 style={{ margin: '1.25rem 0 .5rem' }}>Lines</h3>
        <table>
          <thead>
            <tr>
              <th style={{ width: '55%' }}>SKU</th>
              <th>Qty</th>
              <th style={{ width: 40 }} />
            </tr>
          </thead>
          <tbody>
            {lines.map((l, i) => (
              <tr key={i}>
                <td>
                  <select className="form-control" value={l.skuId} onChange={(e) => setLine(i, { skuId: e.target.value })}>
                    <option value="">Select a SKU…</option>
                    {skus.map((s) => (
                      <option key={s.id} value={s.id}>{s.code}{s.description ? ` — ${s.description}` : ''}</option>
                    ))}
                  </select>
                </td>
                <td>
                  <input className="form-control" type="number" min="0" step="any" value={l.qty}
                         onChange={(e) => setLine(i, { qty: e.target.value })} />
                </td>
                <td>
                  <button className="btn btn-ghost btn-sm" disabled={lines.length === 1}
                          onClick={() => removeLine(i)} title="Remove line">✕</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <button className="btn btn-ghost btn-sm" style={{ marginTop: '.5rem' }} onClick={addLine}>+ Add line</button>

        <div className="dialog-actions">
          <button className="btn btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button className="btn btn-primary" onClick={submit} disabled={busy}>
            {busy ? 'Creating…' : 'Create order'}
          </button>
        </div>
      </div>
    </div>
  )
}
