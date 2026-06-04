import { useCallback, useEffect, useState } from 'react'
import { useWarehouse } from '../warehouse/WarehouseContext'
import DataTable from '../ui/DataTable'
import { Location, Sku, StorageBlock, listLocations, listSkus, listStorageBlocks } from '../masterdata/api'
import { StockOverviewRow, listStockOverview } from './api'

// ---------------------------------------------------------------------------
// Stock overview — a read-only roll-up of what is currently in stock, by handling
// unit, for the warehouse selected in the top bar. Joins against SKUs, locations and
// storage blocks (the "area") so the rows read in human terms rather than UUIDs.
// ---------------------------------------------------------------------------

function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

function StatusBadge({ status }: { status?: string | null }) {
  const s = (status ?? '').toUpperCase()
  const cls =
    s === 'ACTIVE' || s === 'AVAILABLE' ? 'badge-success' : s === 'RETIRED' ? 'badge-danger' : 'badge-warning'
  return <span className={`badge ${cls}`}>{status ?? '—'}</span>
}

// Quantities arrive as decimal numbers or strings; render them readably.
function fmtQty(v: number | string | null | undefined): string {
  if (v == null || v === '') return '—'
  const n = typeof v === 'number' ? v : Number(v)
  if (Number.isNaN(n)) return String(v)
  return n.toLocaleString(undefined, { maximumFractionDigits: 3 })
}

function qtyNum(v: number | string | null | undefined): number {
  if (v == null || v === '') return 0
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isNaN(n) ? 0 : n
}

export default function StockOverviewScreen() {
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const [rows, setRows] = useState<StockOverviewRow[]>([])
  const [skus, setSkus] = useState<Sku[]>([])
  const [locations, setLocations] = useState<Location[]>([])
  const [blocks, setBlocks] = useState<StorageBlock[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!warehouseId) {
      setRows([])
      return
    }
    setLoading(true)
    try {
      setError(null)
      const [ov, sk, locs, blks] = await Promise.all([
        listStockOverview(warehouseId),
        listSkus(),
        listLocations(warehouseId),
        listStorageBlocks(warehouseId),
      ])
      setRows(ov)
      setSkus(sk)
      setLocations(locs)
      setBlocks(blks)
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setLoading(false)
    }
  }, [warehouseId])

  useEffect(() => {
    load()
  }, [load])

  const skuCode = (id?: string | null) => skus.find((s) => s.id === id)?.code ?? (id ? '—' : '—')
  const locationCode = (id?: string | null) => locations.find((l) => l.id === id)?.code ?? (id ? '—' : '—')
  const areaCode = (locationId?: string | null) => {
    const loc = locations.find((l) => l.id === locationId)
    if (!loc?.blockId) return '—'
    return blocks.find((b) => b.id === loc.blockId)?.code ?? '—'
  }

  return (
    <div className="app-content">
      <div className="page-head">
        <span className="eyebrow">Operations</span>
        <h1>Stock overview</h1>
        <p>
          What is currently in stock, by handling unit — quantities and availability. Scoped to the
          warehouse selected in the top bar.
        </p>
      </div>

      {!warehouseId ? (
        <div className="glass card-pad">
          <div className="alert">Select a warehouse above to view its stock.</div>
        </div>
      ) : (
        <div className="glass card-pad so-panel">
          {error && <div className="alert alert-danger">{error}</div>}
          <DataTable
            rows={rows}
            rowKey={(r) => `${r.huId ?? '·'}|${r.locationId ?? '·'}|${r.skuId ?? '·'}`}
            search={(r) =>
              `${r.huCode ?? ''} ${skuCode(r.skuId)} ${locationCode(r.locationId)} ${areaCode(r.locationId)} ${r.status ?? ''}`
            }
            searchPlaceholder="Search by HU code / SKU / location…"
            initialSort={{ key: 'huCode', dir: 'asc' }}
            empty={loading ? 'Loading…' : 'No stock in this warehouse.'}
            columns={[
              {
                key: 'huCode',
                header: 'HU',
                sortable: true,
                sortValue: (r) => r.huCode ?? '',
                render: (r) => (r.huCode ? <code>{r.huCode}</code> : '—'),
              },
              {
                key: 'area',
                header: 'Area',
                sortable: true,
                sortValue: (r) => areaCode(r.locationId),
                render: (r) => areaCode(r.locationId),
              },
              {
                key: 'location',
                header: 'Location',
                sortable: true,
                sortValue: (r) => locationCode(r.locationId),
                render: (r) => locationCode(r.locationId),
              },
              {
                key: 'sku',
                header: 'SKU',
                sortable: true,
                sortValue: (r) => skuCode(r.skuId),
                render: (r) => skuCode(r.skuId),
              },
              {
                key: 'qty',
                header: 'Qty in stock',
                align: 'right',
                sortable: true,
                sortValue: (r) => qtyNum(r.qty),
                render: (r) => fmtQty(r.qty),
              },
              {
                key: 'available',
                header: 'Qty available',
                align: 'right',
                sortable: true,
                sortValue: (r) => qtyNum(r.available),
                render: (r) => fmtQty(r.available),
              },
              {
                key: 'status',
                header: 'Status',
                sortable: true,
                sortValue: (r) => r.status ?? '',
                render: (r) => <StatusBadge status={r.status} />,
              },
            ]}
          />
        </div>
      )}

      <Styles />
    </div>
  )
}

function Styles() {
  return (
    <style>{`
      .so-panel { margin-bottom: 1rem; }
    `}</style>
  )
}
