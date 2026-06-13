// Reporting → Stock: current stock per SKU in single quantities, split between available,
// allocated and unavailable. Figures table plus a stacked view of the biggest SKUs.
import { useEffect, useMemo, useState } from 'react'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useT } from '../i18n/useT'
import DataTable, { type Column } from '../ui/DataTable'
import { useCatalog } from '../lib/useCatalog'
import { loadStockBySku, type StockBySkuRow } from './api'
import { CHART_COLORS, ChartCard, EmptyHistoryNote, LoadingNote, StackedBars, StatChip } from './charts'

const TOP_N = 15

export default function StockReport() {
  const t = useT('reporting')
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
    { key: 'sku', header: t('colSku', 'SKU'), sortable: true, sortValue: (r) => catalog.skuCode(r.skuId), render: (r) => catalog.skuLabel(r.skuId) },
    {
      key: 'available',
      header: t('colAvailable', 'Available'),
      align: 'right',
      sortable: true,
      sortValue: (r) => r.available,
      render: (r) => <span style={{ color: CHART_COLORS.lime }}>{r.available.toLocaleString()}</span>,
    },
    {
      key: 'allocated',
      header: t('colAllocated', 'Allocated'),
      align: 'right',
      sortable: true,
      sortValue: (r) => r.allocated,
      render: (r) => <span style={{ color: CHART_COLORS.blue }}>{r.allocated.toLocaleString()}</span>,
    },
    {
      key: 'unavailable',
      header: t('colUnavailable', 'Unavailable'),
      align: 'right',
      sortable: true,
      sortValue: (r) => r.unavailable,
      render: (r) => <span style={{ color: r.unavailable > 0 ? CHART_COLORS.red : undefined }}>{r.unavailable.toLocaleString()}</span>,
    },
    {
      key: 'total',
      header: t('colTotal', 'Total'),
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
          {t('selectWarehouseStock', 'Select a warehouse in the top bar to load its stock report.')}
        </div>
      </div>
    )
  }

  return (
    <div className="app-content">
      <div className="page-head">
        <span className="eyebrow">{t('eyebrow', 'Reporting')}</span>
        <h1>{t('stockTitle', 'Stock')}</h1>
        <p>{t('stockIntro', 'Current stock per SKU in single quantities, split between available, allocated and unavailable.')}</p>
      </div>

      {error && <p className="badge badge-danger" style={{ marginBottom: '1rem' }}>{error}</p>}

      <div style={{ display: 'flex', gap: '.5rem', flexWrap: 'wrap', marginBottom: '1rem' }}>
        <StatChip label={t('chipSkusInStock', 'SKUs in stock')} value={rows.length.toLocaleString()} />
        <StatChip label={t('chipTotalUnits', 'Total units')} value={totals.total.toLocaleString()} />
        <StatChip label={t('colAvailable', 'Available')} value={totals.available.toLocaleString()} color={CHART_COLORS.lime} />
        <StatChip label={t('colAllocated', 'Allocated')} value={totals.allocated.toLocaleString()} color={CHART_COLORS.blue} />
        <StatChip label={t('colUnavailable', 'Unavailable')} value={totals.unavailable.toLocaleString()} color={totals.unavailable > 0 ? CHART_COLORS.red : undefined} />
      </div>

      <div style={{ marginBottom: '1rem', display: 'flex' }}>
        <ChartCard title={t('topSkusByStock', 'Top {n} SKUs by stock').replace('{n}', String(TOP_N))} subtitle={t('topSkusByStockSub', 'Available / allocated / unavailable units, stacked.')}>
          {loading && rows.length === 0 ? (
            <LoadingNote />
          ) : rows.length === 0 ? (
            <EmptyHistoryNote what={t('whatStock', 'stock')} />
          ) : (
            <StackedBars
              data={topChartData}
              xKey="sku"
              series={[
                { key: 'available', name: t('colAvailable', 'Available'), color: CHART_COLORS.lime },
                { key: 'allocated', name: t('colAllocated', 'Allocated'), color: CHART_COLORS.blue },
                { key: 'unavailable', name: t('colUnavailable', 'Unavailable'), color: CHART_COLORS.red },
              ]}
            />
          )}
        </ChartCard>
      </div>

      <div className="glass" style={{ padding: '1rem 1.1rem' }}>
        <h3 style={{ margin: '0 0 .75rem', fontSize: '.95rem' }}>{t('stockPerSku', 'Stock per SKU')}</h3>
        {loading && rows.length === 0 ? (
          <LoadingNote />
        ) : (
          <DataTable
            columns={columns}
            rows={sorted}
            rowKey={(r) => r.skuId}
            search={(r) => catalog.skuLabel(r.skuId)}
            searchPlaceholder={t('searchSkus', 'Search SKUs…')}
            pageSize={25}
            empty={t('emptyStock', 'No stock in this warehouse yet.')}
          />
        )}
      </div>
    </div>
  )
}
