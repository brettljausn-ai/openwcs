// Reporting → ASRS: storage density in figures and % (90-day history + 14-day forecast), the
// storage-movement heatmap on the real 3D rack (history; forecast shown as the totals line), and
// storage movements per device (shuttle, crane, …).
import { Suspense, lazy, useEffect, useMemo, useState } from 'react'
import { useWarehouse } from '../warehouse/WarehouseContext'
import DataTable, { type Column } from '../ui/DataTable'
import { loadAutomationTopology, type AutomationTopology } from '../topology/automationApi'
import { listEquipment, listLocations, listStorageBlocks, type Equipment, type Location, type StorageBlock } from '../masterdata/api'
import { useCatalog } from '../lib/useCatalog'
import {
  loadDeviceMovements,
  loadStorageDensity,
  loadStorageMovements,
  type DeviceMovementRow,
  type StorageDensityRow,
  type StorageMovementRow,
} from './api'
import { forecastDaily } from './forecast'
import { movementCells } from './derive'
import {
  CHART_COLORS,
  ChartCard,
  DailyChart,
  EmptyHistoryNote,
  HeatLegend,
  LoadingNote,
  StackedBars,
  StatChip,
} from './charts'

const ReportScene3D = lazy(() => import('./ReportScene3D'))

const DAYS = 90
const FORECAST_DAYS = 14
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

interface DeviceRow {
  equipment: string
  family: string
  completed: number
  failed: number
}

export default function AsrsReport() {
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const catalog = useCatalog(warehouseId)
  const [showLabels, setShowLabels] = useState(false)

  const [density, setDensity] = useState<StorageDensityRow[]>([])
  const [movements, setMovements] = useState<StorageMovementRow[]>([])
  const [devices, setDevices] = useState<DeviceMovementRow[]>([])
  const [blocks, setBlocks] = useState<StorageBlock[]>([])
  const [locations, setLocations] = useState<Location[]>([])
  const [topology, setTopology] = useState<AutomationTopology | null>(null)
  const [lib, setLib] = useState<Map<string, Equipment>>(new Map())
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!warehouseId) return
    let cancelled = false
    setLoading(true)
    setError(null)
    Promise.all([
      loadStorageDensity(warehouseId, DAYS),
      loadStorageMovements(warehouseId, DAYS),
      loadDeviceMovements(warehouseId, DAYS),
      listStorageBlocks(warehouseId).catch(() => [] as StorageBlock[]),
      listLocations(warehouseId).catch(() => [] as Location[]),
      loadAutomationTopology(warehouseId).catch(() => null),
      listEquipment(warehouseId).catch(() => [] as Equipment[]),
    ])
      .then(([den, mov, dev, blk, loc, topo, equipment]) => {
        if (cancelled) return
        setDensity(den)
        setMovements(mov)
        setDevices(dev)
        setBlocks(blk)
        setLocations(loc)
        setTopology(topo)
        setLib(new Map(equipment.filter((e) => e.id).map((e) => [e.id as string, e])))
      })
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [warehouseId])

  // ---- Density: daily totals across blocks, latest figures, per-block table, 14-day forecast ----
  const densityDaily = useMemo(() => {
    const byDay = new Map<string, { occ: number; tot: number }>()
    for (const r of density) {
      const d = byDay.get(r.day) ?? { occ: 0, tot: 0 }
      d.occ += r.occupiedCells
      d.tot += r.totalCells
      byDay.set(r.day, d)
    }
    return [...byDay.entries()]
      .sort((a, b) => (a[0] < b[0] ? -1 : 1))
      .map(([day, d]) => ({ day, occ: d.occ, tot: d.tot, pct: d.tot > 0 ? (d.occ / d.tot) * 100 : 0 }))
  }, [density])

  const latest = densityDaily.length > 0 ? densityDaily[densityDaily.length - 1] : null

  const densityChartData = useMemo(() => {
    const history = densityDaily.map((d) => ({ day: d.day, value: d.pct }))
    const forecast = forecastDaily(history, FORECAST_DAYS)
    const rows: Array<{ day: string; pct?: number; forecast?: number }> = history.map((p) => ({ day: p.day, pct: +p.value.toFixed(1) }))
    if (rows.length > 0 && forecast.length > 0) {
      // Join the dashed line to the last actual point.
      rows[rows.length - 1].forecast = rows[rows.length - 1].pct
      for (const p of forecast) rows.push({ day: p.day, forecast: +Math.min(100, Math.max(0, p.value)).toFixed(1) })
    }
    return rows
  }, [densityDaily])

  const blockRows = useMemo(() => {
    const blockCode = new Map(blocks.filter((b) => b.id).map((b) => [b.id as string, b.code]))
    // Latest day per block.
    const latestByBlock = new Map<string, StorageDensityRow>()
    for (const r of density) {
      const prev = latestByBlock.get(r.blockId)
      if (!prev || r.day > prev.day) latestByBlock.set(r.blockId, r)
    }
    return [...latestByBlock.values()]
      .map((r) => ({
        blockId: r.blockId,
        block: blockCode.get(r.blockId) ?? r.blockId.slice(0, 8),
        occupied: r.occupiedCells,
        total: r.totalCells,
        pct: r.pct,
      }))
      .sort((a, b) => b.pct - a.pct)
  }, [density, blocks])

  // ---- Storage movements: daily totals + forecast, rack-cell heat for the 3D scene -------------
  const movementChartData = useMemo(() => {
    const byDay = new Map<string, number>()
    for (const r of movements) byDay.set(r.day, (byDay.get(r.day) ?? 0) + r.stores + r.retrieves)
    const history = [...byDay.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1)).map(([day, value]) => ({ day, value }))
    const forecast = forecastDaily(history, FORECAST_DAYS)
    const rows: Array<{ day: string; movements?: number; forecast?: number }> = history.map((p) => ({ day: p.day, movements: p.value }))
    if (rows.length > 0 && forecast.length > 0) {
      rows[rows.length - 1].forecast = rows[rows.length - 1].movements
      for (const p of forecast) rows.push({ day: p.day, forecast: Math.max(0, Math.round(p.value)) })
    }
    return rows
  }, [movements])

  const heat = useMemo(
    () => (topology ? movementCells(movements, locations, topology) : null),
    [movements, locations, topology],
  )

  // ---- Device movements ------------------------------------------------------------------------
  const deviceLabel = (equipment: string): string =>
    UUID_RE.test(equipment) ? catalog.equipmentLabel(equipment) : equipment

  const deviceRows = useMemo<DeviceRow[]>(() => {
    const byDevice = new Map<string, DeviceRow>()
    for (const r of devices) {
      const d = byDevice.get(r.equipment) ?? { equipment: r.equipment, family: r.family, completed: 0, failed: 0 }
      d.completed += r.completed
      d.failed += r.failed
      byDevice.set(r.equipment, d)
    }
    return [...byDevice.values()].sort((a, b) => b.completed + b.failed - (a.completed + a.failed))
  }, [devices])

  const deviceChartData = useMemo(
    () =>
      deviceRows.map((d) => ({
        device: deviceLabel(d.equipment),
        completed: d.completed,
        failed: d.failed,
      })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [deviceRows, catalog.loading],
  )

  const deviceColumns: Column<DeviceRow>[] = [
    { key: 'equipment', header: 'Device', sortable: true, render: (r) => deviceLabel(r.equipment) },
    { key: 'family', header: 'Family', sortable: true, render: (r) => <span className="badge">{r.family}</span> },
    { key: 'completed', header: 'Completed', align: 'right', sortable: true, sortValue: (r) => r.completed },
    {
      key: 'failed',
      header: 'Failed',
      align: 'right',
      sortable: true,
      sortValue: (r) => r.failed,
      render: (r) => <span style={{ color: r.failed > 0 ? 'var(--danger)' : undefined }}>{r.failed}</span>,
    },
    { key: 'total', header: 'Total', align: 'right', sortable: true, sortValue: (r) => r.completed + r.failed, render: (r) => r.completed + r.failed },
  ]

  if (!warehouseId) {
    return (
      <div className="app-content">
        <div className="glass" style={{ padding: '2.5rem', textAlign: 'center', color: 'var(--text-dim)' }}>
          Select a warehouse in the top bar to load its ASRS report.
        </div>
      </div>
    )
  }

  const totalMovements = movements.reduce((s, r) => s + r.stores + r.retrieves, 0)

  return (
    <div className="app-content">
      <div className="page-head">
        <span className="eyebrow">Reporting</span>
        <h1>ASRS</h1>
        <p>Storage density over the last {DAYS} days with a {FORECAST_DAYS}-day forecast, where the storage movements happen, and movements per device.</p>
      </div>

      {error && <p className="badge badge-danger" style={{ marginBottom: '1rem' }}>{error}</p>}

      <div style={{ display: 'flex', gap: '.5rem', flexWrap: 'wrap', marginBottom: '1rem' }}>
        <StatChip label="Occupied cells" value={latest ? latest.occ.toLocaleString() : '-'} color={CHART_COLORS.blue} />
        <StatChip label="Total cells" value={latest ? latest.tot.toLocaleString() : '-'} />
        <StatChip label="Density" value={latest ? `${latest.pct.toFixed(1)} %` : '-'} color={CHART_COLORS.lime} />
        <StatChip label="Movements (window)" value={totalMovements.toLocaleString()} color={CHART_COLORS.amber} />
        <StatChip label="Devices" value={deviceRows.length} />
      </div>

      <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', marginBottom: '1rem' }}>
        <ChartCard title="Storage density" subtitle={`Occupied cells as % of total, ${DAYS}-day history with a dashed ${FORECAST_DAYS}-day forecast (weekday-seasonal average).`}>
          {loading && density.length === 0 ? (
            <LoadingNote />
          ) : density.length === 0 ? (
            <EmptyHistoryNote what="density history" />
          ) : (
            <DailyChart
              data={densityChartData}
              yTick={(v) => `${v}%`}
              series={[
                { key: 'pct', name: 'Density %', color: CHART_COLORS.lime },
                { key: 'forecast', name: 'Forecast', color: CHART_COLORS.blue, dashed: true },
              ]}
            />
          )}
        </ChartCard>
        <ChartCard title="Density by block" subtitle="Latest reported day per storage block.">
          {loading && blockRows.length === 0 ? (
            <LoadingNote />
          ) : blockRows.length === 0 ? (
            <EmptyHistoryNote what="density history" />
          ) : (
            <DataTable
              columns={[
                { key: 'block', header: 'Block', sortable: true },
                { key: 'occupied', header: 'Occupied', align: 'right', sortable: true, sortValue: (r) => r.occupied },
                { key: 'total', header: 'Total', align: 'right', sortable: true, sortValue: (r) => r.total },
                { key: 'pct', header: 'Density', align: 'right', sortable: true, sortValue: (r) => r.pct, render: (r) => `${r.pct.toFixed(1)} %` },
              ]}
              rows={blockRows}
              rowKey={(r) => r.blockId}
              pageSize={8}
              empty="No storage blocks reported."
            />
          )}
        </ChartCard>
      </div>

      <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', marginBottom: '1rem' }}>
        <div className="glass" style={{ padding: '1rem 1.1rem', flex: '2 1 480px', minWidth: 0 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '.75rem', flexWrap: 'wrap' }}>
            <div>
              <h3 style={{ margin: '0 0 .15rem', fontSize: '.95rem' }}>Storage-movement heatmap</h3>
              <p className="muted" style={{ margin: '0 0 .75rem', fontSize: '.75rem' }}>
                Rack cells coloured by stores + retrieves over the window, at the same cell positions the hardware twin renders
                stored totes (log scale).
              </p>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap' }}>
              <label style={{ display: 'inline-flex', alignItems: 'center', gap: '.4rem', fontSize: '.85rem' }}>
                <input type="checkbox" checked={showLabels} onChange={(e) => setShowLabels(e.target.checked)} />
                Labels
              </label>
              {heat && <HeatLegend min={0} max={heat.maxPerCell} unit="movements/cell" />}
            </div>
          </div>
          {!topology || topology.equipment.length === 0 ? (
            <EmptyHistoryNote what="placed automation topology (build one under Engineering → Automation topology)" />
          ) : movements.length === 0 ? (
            <EmptyHistoryNote what="storage movements" />
          ) : (
            <div style={{ height: 'min(60vh, 520px)', borderRadius: 10, overflow: 'hidden' }}>
              <Suspense fallback={<LoadingNote>Loading 3D scene…</LoadingNote>}>
                <ReportScene3D topology={topology} lib={lib} cells={heat?.cells ?? null} showLabels={showLabels} />
              </Suspense>
            </div>
          )}
          {heat && heat.unplaced > 0 && (
            <p className="muted" style={{ margin: '.5rem 0 0', fontSize: '.72rem' }}>
              {heat.unplaced.toLocaleString()} movements hit locations without rack-cell coordinates and are not painted.
            </p>
          )}
        </div>
        <ChartCard title="Storage movements per day" subtitle={`Stores + retrieves, with a dashed ${FORECAST_DAYS}-day forecast of the totals.`} minWidth={320}>
          {loading && movements.length === 0 ? (
            <LoadingNote />
          ) : movements.length === 0 ? (
            <EmptyHistoryNote what="storage movements" />
          ) : (
            <DailyChart
              data={movementChartData}
              height={300}
              series={[
                { key: 'movements', name: 'Movements', color: CHART_COLORS.amber },
                { key: 'forecast', name: 'Forecast', color: CHART_COLORS.blue, dashed: true },
              ]}
            />
          )}
        </ChartCard>
      </div>

      <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
        <ChartCard title="Movements per device" subtitle="Completed vs failed device tasks per shuttle / crane / device over the window.">
          {loading && deviceRows.length === 0 ? (
            <LoadingNote />
          ) : deviceRows.length === 0 ? (
            <EmptyHistoryNote what="device movements" />
          ) : (
            <StackedBars
              data={deviceChartData}
              xKey="device"
              series={[
                { key: 'completed', name: 'Completed', color: CHART_COLORS.lime },
                { key: 'failed', name: 'Failed', color: CHART_COLORS.red },
              ]}
            />
          )}
        </ChartCard>
        <ChartCard title="Device details" subtitle="Totals per device over the window.">
          {loading && deviceRows.length === 0 ? (
            <LoadingNote />
          ) : (
            <DataTable
              columns={deviceColumns}
              rows={deviceRows}
              rowKey={(r) => r.equipment}
              search={(r) => `${deviceLabel(r.equipment)} ${r.family}`}
              searchPlaceholder="Search devices…"
              pageSize={8}
              empty="No device movements in this window."
            />
          )}
        </ChartCard>
      </div>
    </div>
  )
}
