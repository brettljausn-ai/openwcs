import { useCallback, useEffect, useMemo, useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useDemoMode, seedDemoOrders } from '../demo/useDemoMode'
import { useCatalog } from '../lib/useCatalog'
import Select from '../ui/Select'
import DataTable from '../ui/DataTable'
import InfoTip from '../ui/InfoTip'
import { useT } from '../i18n/useT'

// Outbound Orders screen — UI-only against existing endpoints:
//   order-management:  GET/POST /api/orders, /api/orders/{id}, /api/orders/{id}/{release|release-short|cancel|ship}
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
      return 'badge-info'
    case 'PARTIALLY_ALLOCATED': // short released: picks what is available, ships short
      return 'badge-warning'
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
  const t = useT('outbound')
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const { enabled: demoEnabled } = useDemoMode()
  const catalog = useCatalog(warehouseId)
  const [statusFilter, setStatusFilter] = useState<string>('')

  const [orders, setOrders] = useState<Order[]>([])
  const [loading, setLoading] = useState(false)
  const [seeding, setSeeding] = useState(false)
  const [error, setError] = useState<string>('')

  const [selected, setSelected] = useState<Order | null>(null)

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
      .catch((e) => setError(typeof e === 'string' ? e : t('errLoad', 'Could not load orders.')))
      .finally(() => setLoading(false))
  }, [warehouseId, statusFilter, t])

  useEffect(() => { loadOrders() }, [loadOrders])

  // Demo-only: bulk-create 10 small sample outbound orders.
  async function addDemoOrders() {
    if (!warehouseId) return
    setSeeding(true)
    setError('')
    try {
      await seedDemoOrders(warehouseId, 'OUTBOUND', 10)
      loadOrders()
    } catch (e) {
      setError(e instanceof Error ? e.message : t('errAddDemo', 'Could not add demo orders.'))
    } finally {
      setSeeding(false)
    }
  }

  const outbound = orders.filter((o) => o.orderType === 'OUTBOUND')

  return (
    <div className="app-content">
      <div className="page-head">
        <div className="eyebrow">{t('eyebrow', 'Outbound')}</div>
        <h1>{t('title', 'Outbound orders')}</h1>
        <p>{t('subtitle', 'Release, allocate, cube and dispatch customer orders.')}</p>
      </div>

      {error && <div className="alert alert-danger">{error}</div>}

      <div className="glass" style={{ padding: '1rem 1.25rem', marginBottom: '1.25rem' }}>
        <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <div style={{ minWidth: 200 }}>
            <label>{t('status', 'Status')} <InfoTip text={t('statusTip', "Filter the outbound order list by fulfilment status. Leave on 'All statuses' to see every order in this warehouse.")} example="RELEASED" /></label>
            <Select
              value={statusFilter}
              onChange={(v) => setStatusFilter(v)}
              ariaLabel={t('status', 'Status')}
              options={[
                { value: '', label: t('allStatuses', 'All statuses') },
                ...STATUSES.map((s) => ({ value: s, label: s })),
              ]}
            />
          </div>
          <div style={{ flex: 1 }} />
          <span className="muted" style={{ fontSize: '.82rem' }}>
            {t('hostOwnedNote', 'Outbound orders are owned by the host system — released & fulfilled here, not created.')}
          </span>
          {demoEnabled && (
            <button
              className="btn btn-outline"
              onClick={addDemoOrders}
              disabled={!warehouseId || seeding}
              title={t('addDemoTitle', 'Demo mode: create 10 small sample outbound orders')}
            >
              {seeding ? t('adding', 'Adding…') : t('add10Orders', 'Add 10 Orders')}
            </button>
          )}
          <button className="btn btn-ghost" onClick={loadOrders} disabled={!warehouseId || loading}>
            {t('refresh', 'Refresh')}
          </button>
        </div>
      </div>

      {loading ? (
        <div className="glass" style={{ padding: '2rem', textAlign: 'center' }}>
          <span className="spin" /> <span className="muted" style={{ marginLeft: '.5rem' }}>{t('loading', 'Loading…')}</span>
        </div>
      ) : (
        <div className="glass card-pad">
          <DataTable
            rows={outbound}
            rowKey={(o) => o.id}
            onRowClick={(o) => setSelected(o)}
            search={(o) => `${o.orderRef} ${o.customerRef ?? ''} ${o.status} ${o.serviceCode ?? ''} ${o.routeCode ?? ''}`}
            searchPlaceholder={t('searchPlaceholder', 'Search orders…')}
            initialSort={{ key: 'createdAt', dir: 'desc' }}
            empty={
              statusFilter
                ? t('emptyWithStatus', 'No outbound orders with status {status} in this warehouse.').replace('{status}', statusFilter)
                : t('empty', 'No outbound orders in this warehouse.')
            }
            columns={[
              { key: 'orderRef', header: t('colOrderRef', 'Order ref'), sortable: true, sortValue: (o) => o.orderRef, render: (o) => <strong>{o.orderRef}</strong> },
              { key: 'customerRef', header: t('colCustomer', 'Customer'), sortable: true, sortValue: (o) => o.customerRef ?? '', render: (o) => o.customerRef || '—' },
              { key: 'status', header: t('colStatus', 'Status'), sortable: true, sortValue: (o) => o.status, render: (o) => <span className={`badge ${statusBadge(o.status)}`}>{o.status}</span> },
              { key: 'priority', header: t('colPriority', 'Priority'), align: 'right', sortable: true, sortValue: (o) => o.priority, render: (o) => o.priority },
              { key: 'lines', header: t('colLines', 'Lines'), align: 'right', sortable: true, sortValue: (o) => o.lines?.length ?? 0, render: (o) => o.lines?.length ?? 0 },
              { key: 'serviceCode', header: t('colService', 'Service'), render: (o) => o.serviceCode || '—' },
              { key: 'routeCode', header: t('colRoute', 'Route'), render: (o) => o.routeCode || '—' },
              { key: 'dispatchBy', header: t('colDispatchBy', 'Dispatch by'), sortable: true, sortValue: (o) => o.dispatchBy ?? '', render: (o) => fmtDate(o.dispatchBy) },
              { key: 'createdAt', header: t('colCreated', 'Created'), sortable: true, sortValue: (o) => o.createdAt ?? '', render: (o) => fmtDate(o.createdAt) },
            ]}
          />
        </div>
      )}

      {selected && (
        <OrderDetail
          orderId={selected.id}
          onClose={() => setSelected(null)}
          onChanged={() => { loadOrders() }}
          skuCache={skuById}
          locationCode={catalog.locationCode}
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
function OrderDetail({ orderId, onClose, onChanged, skuCache, locationCode }: {
  orderId: string
  onClose: () => void
  onChanged: () => void
  skuCache: Map<string, Sku>
  locationCode: (id?: string | null) => string
}) {
  const t = useT('outbound')
  const { roles } = useAuth()
  const [order, setOrder] = useState<Order | null>(null)
  const [alloc, setAlloc] = useState<Allocation | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const [confirmShort, setConfirmShort] = useState(false)

  const loadOrder = useCallback(() => {
    setLoading(true)
    return fetch(`/api/orders/${orderId}`)
      .then(async (r) => (r.ok ? r.json() : Promise.reject(await readError(r))))
      .then((o: Order) => setOrder(o))
      .catch((e) => setErr(typeof e === 'string' ? e : t('errLoadOrder', 'Could not load order.')))
      .finally(() => setLoading(false))
  }, [orderId, t])

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

  function act(kind: 'release' | 'release-short' | 'allocate' | 'cancel' | 'ship') {
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
      .catch((e) => setErr(typeof e === 'string' ? e : t('errAction', 'Could not complete the requested action on this order.')))
      .finally(() => setBusy(false))
  }

  const status = order?.status
  const supervisor = roles.includes('ADMIN') || roles.includes('SUPERVISOR')
  const canRelease = status === 'CREATED'
  const canAllocate = status === 'RELEASED' || status === 'PARTIALLY_ALLOCATED' || status === 'NOT_FULFILLABLE'
  // Short allocate and release: a supervisor decision on an order that came back short.
  const canReleaseShort = status === 'NOT_FULFILLABLE' && supervisor
  const canShip = status === 'ALLOCATED' || status === 'PARTIALLY_ALLOCATED'
  const canCancel = status && !['SHIPPED', 'CANCELLED'].includes(status)
  const shortLines = (alloc?.lines || []).filter((l) => l.status === 'SHORT')

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="dialog" style={{ maxWidth: 860, maxHeight: '90vh', overflow: 'auto' }}
           onClick={(e) => e.stopPropagation()}>
        {loading || !order ? (
          <div style={{ padding: '1rem', textAlign: 'center' }}>
            <span className="spin" /> <span className="muted">{t('loadingOrder', 'Loading order…')}</span>
          </div>
        ) : (
          <>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '1rem' }}>
              <div>
                <h2 style={{ marginBottom: '.35rem' }}>{order.orderRef}</h2>
                <span className={`badge ${statusBadge(order.status)}`}>{order.status}</span>
                {order.statusDetail && <span className="muted" style={{ marginLeft: '.6rem' }}>{order.statusDetail}</span>}
              </div>
              <button className="btn btn-ghost btn-sm" onClick={onClose}>{t('close', 'Close')}</button>
            </div>

            {err && <div className="alert alert-danger">{err}</div>}

            {/* summary grid */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: '.75rem', margin: '1rem 0' }}>
              <Field label={t('fieldCustomer', 'Customer')} value={order.customerRef} />
              <Field label={t('fieldPriority', 'Priority')} value={String(order.priority)} />
              <Field label={t('fieldService', 'Service')} value={order.serviceCode} />
              <Field label={t('fieldRoute', 'Route')} value={order.routeCode} />
              <Field label={t('fieldDispatchBy', 'Dispatch by')} value={fmtDate(order.dispatchBy)} />
              <Field label={t('fieldCreated', 'Created')} value={fmtDate(order.createdAt)} />
              <Field label={t('fieldLabelTemplate', 'Label template')} value={order.labelTemplateCode} />
            </div>

            {order.shipTo && (
              <div className="muted" style={{ marginBottom: '1rem', fontSize: '.85rem' }}>
                <strong>{t('shipTo', 'Ship to:')}</strong>{' '}
                {[order.shipTo.name, order.shipTo.line1, order.shipTo.city, order.shipTo.postcode, order.shipTo.country]
                  .filter(Boolean).join(', ') || '—'}
              </div>
            )}

            {/* lines */}
            <h3 style={{ margin: '1rem 0 .5rem' }}>{t('lines', 'Lines')}</h3>
            <table>
              <thead>
                <tr>
                  <th>#</th><th>{t('sku', 'SKU')}</th>
                  <th style={{ textAlign: 'right' }}>{t('qty', 'Qty')}</th>
                  <th style={{ textAlign: 'right' }}>{t('allocated', 'Allocated')}</th>
                  <th style={{ textAlign: 'right' }}>{t('posted', 'Posted')}</th>
                  <th>{t('colStatus', 'Status')}</th>
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
                  {t('allocation', 'Allocation')}{' '}
                  <span className={`badge ${statusBadge(alloc.status)}`}>{alloc.status}</span>
                  {alloc.cubingMode && <span className="muted" style={{ marginLeft: '.6rem', fontSize: '.8rem' }}>{t('cubing', 'cubing')}: {alloc.cubingMode}</span>}
                </h3>
                {alloc.statusDetail && <div className="muted" style={{ fontSize: '.85rem', marginBottom: '.5rem' }}>{alloc.statusDetail}</div>}
                {alloc.lines && alloc.lines.length > 0 && (
                  <table>
                    <thead>
                      <tr>
                        <th>#</th><th>{t('sku', 'SKU')}</th>
                        <th style={{ textAlign: 'right' }}>{t('requested', 'Requested')}</th>
                        <th style={{ textAlign: 'right' }}>{t('allocated', 'Allocated')}</th>
                        <th>{t('colStatus', 'Status')}</th>
                        <th>{t('picks', 'Picks')}</th>
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
                                {fmtNum(p.qty)} @ {locationCode(p.locationId)}
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
                    <h3 style={{ margin: '1.25rem 0 .5rem' }}>{t('cartons', 'Cartons')} ({alloc.shippers.length})</h3>
                    <table>
                      <thead>
                        <tr>
                          <th>{t('seq', 'Seq')}</th><th>{t('shipper', 'Shipper')}</th>
                          <th>{t('contents', 'Contents')}</th>
                          <th style={{ textAlign: 'right' }}>{t('weightG', 'Weight (g)')}</th>
                          <th style={{ textAlign: 'right' }}>{t('volumeMm3', 'Volume (mm³)')}</th>
                          <th>{t('dispatchLabel', 'Dispatch label')}</th>
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
                                  <div key={i}>{fmtNum(c.qty)} × {skuLabel(c.skuId)} ({t('line', 'line')} {c.lineNo})</div>
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
                {t('noAllocation', 'No allocation yet. Release the order, then allocate to reserve stock and cube into cartons.')}
              </div>
            )}

            {/* actions */}
            <div className="dialog-actions" style={{ flexWrap: 'wrap' }}>
              {canRelease && <button className="btn btn-primary" disabled={busy} onClick={() => act('release')}>{t('release', 'Release')}</button>}
              {canAllocate && <button className="btn btn-primary" disabled={busy} onClick={() => act('allocate')}>{t('allocateAndCube', 'Allocate & cube')}</button>}
              {canReleaseShort && (
                <button className="btn btn-primary" disabled={busy} onClick={() => setConfirmShort(true)}
                        title={t('shortReleaseTitle', 'Supervisor decision: pick the available quantity and ship the order short')}>
                  {t('shortAllocateRelease', 'Short allocate and release')}
                </button>
              )}
              {canShip && <button className="btn btn-primary" disabled={busy} onClick={() => act('ship')}>{t('dispatch', 'Dispatch')}</button>}
              {canCancel && <button className="btn btn-danger" disabled={busy} onClick={() => act('cancel')}>{t('cancel', 'Cancel')}</button>}
              {busy && <span className="spin" />}
            </div>

            {/* short allocate + release confirmation */}
            {confirmShort && (
              <div className="modal-backdrop" onClick={() => setConfirmShort(false)}>
                <div className="dialog" style={{ maxWidth: 620 }} onClick={(e) => e.stopPropagation()}>
                  <h3 style={{ marginBottom: '.5rem' }}>{t('shortConfirmTitle', 'Short allocate and release {ref}?').replace('{ref}', order.orderRef)}</h3>
                  <p className="muted" style={{ fontSize: '.85rem' }}>
                    {t('shortCannotAllocate', 'These lines cannot be fully allocated:')}
                  </p>
                  {shortLines.length > 0 ? (
                    <table>
                      <thead>
                        <tr>
                          <th>#</th><th>{t('sku', 'SKU')}</th>
                          <th style={{ textAlign: 'right' }}>{t('ordered', 'Ordered')}</th>
                          <th style={{ textAlign: 'right' }}>{t('allocatable', 'Allocatable')}</th>
                          <th style={{ textAlign: 'right' }}>{t('shortfall', 'Shortfall')}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {shortLines.map((l) => {
                          const ordered = l.requestedQty ?? 0
                          const allocatable = l.allocatedQty ?? 0
                          const sku = skuCache.get(l.skuId)
                          return (
                            <tr key={l.lineNo}>
                              <td>{l.lineNo}</td>
                              <td>
                                {skuLabel(l.skuId)}
                                {sku?.description && (
                                  <span className="muted" style={{ marginLeft: '.4rem', fontSize: '.8rem' }}>
                                    {sku.description}
                                  </span>
                                )}
                              </td>
                              <td style={{ textAlign: 'right' }}>{fmtNum(ordered)}</td>
                              <td style={{ textAlign: 'right' }}>{fmtNum(allocatable)}</td>
                              <td style={{ textAlign: 'right' }}><strong>{fmtNum(ordered - allocatable)}</strong></td>
                            </tr>
                          )
                        })}
                      </tbody>
                    </table>
                  ) : (
                    <p className="muted" style={{ fontSize: '.85rem' }}>
                      {t('shortNoDetail', 'No allocation detail is available for this order; the shortfall per line will be determined when the short allocation runs.')}
                    </p>
                  )}
                  <p style={{ margin: '.75rem 0' }}>
                    {t('shortWillShip', 'The available quantity will be picked and the order will be short shipped.')}
                  </p>
                  <div className="dialog-actions">
                    <button className="btn btn-ghost" disabled={busy} onClick={() => setConfirmShort(false)}>{t('keepWaiting', 'Keep waiting for stock')}</button>
                    <button className="btn btn-primary" disabled={busy}
                            onClick={() => { setConfirmShort(false); act('release-short') }}>
                      {t('shortAllocateRelease', 'Short allocate and release')}
                    </button>
                  </div>
                </div>
              </div>
            )}
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
