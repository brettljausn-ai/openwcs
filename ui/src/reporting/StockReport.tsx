// Reporting → Stock: current stock per SKU in single quantities, split between available,
// allocated and unavailable. Figures table plus a stacked view of the biggest SKUs.
import { useEffect, useMemo, useState } from 'react'
import { useWarehouse } from '../warehouse/WarehouseContext'
import DataTable, { type Column } from '../ui/DataTable'
import { useCatalog } from '../lib/useCatalog'
import { loadStockBySku, type StockBySkuRow } from './api'
import { CHART_COLORS, ChartCard, EmptyHistoryNote, LoadingNote, StackedBars, StatChip } from './charts'

const TOP_N = 15

export default function StockReport() {
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const catalog = useCatalog(warehouseId)

  const [rows, setRows] = useState<StockBySkuRow[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!warehouseId) return
    let cancelled = false
    setLoading(true)
    setError(null)
    loadStockBySku(warehouseId)
      .then((r) => !cancelled && setRows(r))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [warehouseId])

  const totals = useMemo(() => {
    let available = 0
    let allocated = 0
    let unavailable = 0
    for (const r of rows) {
      available += r.available
      allocated += r.allocated
      unavailable += r.unavailable
    }
    return { available, allocated, unavailable, total: available + allocated + unavailable }
  }, [rows])

  const sorted = useMemo(
    () => [...rows].sort((a, b) => b.available + b.allocated + b.unavailable - (a.available + a.allocated + a.unavailable)),
    [rows],
  )

  const topChartData = useMemo(
    () =>
      sorted.slice(0, TOP_N).map((r) => ({
        sku: catalog.skuCode(r.skuId),
        available: r.available,
        allocated: r.allocated,
        unavailable: r.unavailable,
      })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [sorted, catalog.loading],
  )

  const columns: Column<StockBySkuRow>[] = [
    { key: 'sku', header: 'SKU', sortable: true, sortValue: (r) => catalog.skuCode(r.skuId), render: (r) => catalog.skuLabel(r.skuId) },
    {
      key: 'available',
      header: 'Available',
      align: 'right',
      sortable: true,
      sortValue: (r) => r.available,
      render: (r) => <span style={{ color: CHART_COLORS.lime }}>{r.available.toLocaleString()}</span>,
    },
    {
      key: 'allocated',
      header: 'Allocated',
      align: 'right',
      sortable: true,
      sortValue: (r) => r.allocated,
      render: (r) => <span style={{ color: CHART_COLORS.blue }}>{r.allocated.toLocaleString()}</span>,
    },
    {
      key: 'unavailable',
      header: 'Unavailable',
      align: 'right',
      sortable: true,
      sortValue: (r) => r.unavailable,
      render: (r) => <span style={{ color: r.unavailable > 0 ? CHART_COLORS.red : undefined }}>{r.unavailable.toLocaleString()}</span>,
    },
    {
      key: 'total',
      header: 'Total',
      align: 'right',
      sortable: true,
      sortValue: (r) => r.available + r.allocated + r.unavailable,
      render: (r) => <strong>{(r.available + r.allocated + r.unavailable).toLocaleString()}</strong>,
    },
  ]

  if (!warehouseId) {
    return (
      <div className="app-content">
        <div className="glass" style={{ padding: '2.5rem', textAlign: 'center', color: 'var(--text-dim)' }}>
          Select a warehouse in the top bar to load its stock report.
        </div>
      </div>
    )
  }

  return (
    <div className="app-content">
      <div className="page-head">
        <span className="eyebrow">Reporting</span>
        <h1>Stock</h1>
        <p>Current stock per SKU in single quantities, split between available, allocated and unavailable.</p>
      </div>

      {error && <p className="badge badge-danger" style={{ marginBottom: '1rem' }}>{error}</p>}

      <div style={{ display: 'flex', gap: '.5rem', flexWrap: 'wrap', marginBottom: '1rem' }}>
        <StatChip label="SKUs in stock" value={rows.length.toLocaleString()} />
        <StatChip label="Total units" value={totals.total.toLocaleString()} />
        <StatChip label="Available" value={totals.available.toLocaleString()} color={CHART_COLORS.lime} />
        <StatChip label="Allocated" value={totals.allocated.toLocaleString()} color={CHART_COLORS.blue} />
        <StatChip label="Unavailable" value={totals.unavailable.toLocaleString()} color={totals.unavailable > 0 ? CHART_COLORS.red : undefined} />
      </div>

      <div style={{ marginBottom: '1rem', display: 'flex' }}>
        <ChartCard title={`Top ${TOP_N} SKUs by stock`} subtitle="Available / allocated / unavailable units, stacked.">
          {loading && rows.length === 0 ? (
            <LoadingNote />
          ) : rows.length === 0 ? (
            <EmptyHistoryNote what="stock" />
          ) : (
            <StackedBars
              data={topChartData}
              xKey="sku"
              series={[
                { key: 'available', name: 'Available', color: CHART_COLORS.lime },
                { key: 'allocated', name: 'Allocated', color: CHART_COLORS.blue },
                { key: 'unavailable', name: 'Unavailable', color: CHART_COLORS.red },
              ]}
            />
          )}
        </ChartCard>
      </div>

      <div className="glass" style={{ padding: '1rem 1.1rem' }}>
        <h3 style={{ margin: '0 0 .75rem', fontSize: '.95rem' }}>Stock per SKU</h3>
        {loading && rows.length === 0 ? (
          <LoadingNote />
        ) : (
          <DataTable
            columns={columns}
            rows={sorted}
            rowKey={(r) => r.skuId}
            search={(r) => catalog.skuLabel(r.skuId)}
            searchPlaceholder="Search SKUs…"
            pageSize={25}
            empty="No stock in this warehouse yet."
          />
        )}
      </div>
    </div>
  )
}
