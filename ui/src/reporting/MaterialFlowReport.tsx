// Reporting → Material flow: scan quality per scan point and day (scans / no reads / unknowns),
// a "scanners needing attention" prediction table (error rate + rising-trend regression), the
// conveyor traffic heatmap on the real 3D topology scene, and daily transit times.
import { Suspense, lazy, useEffect, useMemo, useState } from 'react'
import { useWarehouse } from '../warehouse/WarehouseContext'
import Select from '../ui/Select'
import DataTable, { type Column } from '../ui/DataTable'
import { loadAutomationTopology, type AutomationTopology } from '../topology/automationApi'
import { listEquipment, type Equipment } from '../masterdata/api'
import { loadConveyorNodePositions } from '../hardwaretwin/api'
import { loadScanQuality, loadTraffic, loadTransitTimes, type ScanQualityRow, type TrafficRow, type TransitTimeRow } from './api'
import { addDays, regressionSlope } from './forecast'
import { attributeTraffic } from './derive'
import {
  CHART_COLORS,
  ChartCard,
  DailyChart,
  EmptyHistoryNote,
  HeatLegend,
  LoadingNote,
  Sparkline,
  StackedBars,
  StatChip,
} from './charts'

// The heavy three.js scene lives in its own chunk (shared with the ASRS report).
const ReportScene3D = lazy(() => import('./ReportScene3D'))

/** Error rate above this flags a scanner even without a rising trend. */
const ATTENTION_RATE = 0.02
/** Daily error-rate slope above this (0.1 %-points/day) flags a rising scanner. */
const ATTENTION_SLOPE = 0.001

const DAY_OPTIONS = [7, 14, 30, 90]

function todayDay(): string {
  return new Date().toISOString().slice(0, 10)
}

interface ScannerRow {
  node: string
  scans: number
  noReads: number
  unknowns: number
  rate: number
  slope: number
  spark: number[]
  attention: boolean
}

export default function MaterialFlowReport() {
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const [days, setDays] = useState(30)
  const [showLabels, setShowLabels] = useState(false)

  const [scanRows, setScanRows] = useState<ScanQualityRow[]>([])
  const [trafficRows, setTrafficRows] = useState<TrafficRow[]>([])
  const [transitRows, setTransitRows] = useState<TransitTimeRow[]>([])
  const [topology, setTopology] = useState<AutomationTopology | null>(null)
  const [lib, setLib] = useState<Map<string, Equipment>>(new Map())
  const [nodeXZ, setNodeXZ] = useState<Map<string, [number, number]>>(new Map())
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!warehouseId) return
    let cancelled = false
    setLoading(true)
    setError(null)
    Promise.all([
      loadScanQuality(warehouseId, days),
      loadTraffic(warehouseId, days),
      loadTransitTimes(warehouseId, days).catch(() => [] as TransitTimeRow[]),
      loadAutomationTopology(warehouseId).catch(() => null),
      listEquipment(warehouseId).catch(() => [] as Equipment[]),
      loadConveyorNodePositions(warehouseId).catch(() => new Map<string, [number, number]>()),
    ])
      .then(([scans, traffic, transit, topo, equipment, nodes]) => {
        if (cancelled) return
        setScanRows(scans)
        setTrafficRows(traffic)
        setTransitRows(transit)
        setTopology(topo)
        setLib(new Map(equipment.filter((e) => e.id).map((e) => [e.id as string, e])))
        setNodeXZ(nodes)
      })
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [warehouseId, days])

  // Window day list (today back `days` days): charts always span the full window.
  const windowDays = useMemo(() => {
    const end = todayDay()
    const out: string[] = []
    for (let i = days - 1; i >= 0; i--) out.push(addDays(end, -i))
    return out
  }, [days])

  // Totals + per-day stacked scan-quality data.
  const totals = useMemo(() => {
    let scans = 0
    let noReads = 0
    let unknowns = 0
    for (const r of scanRows) {
      scans += r.scans
      noReads += r.noReads
      unknowns += r.unknowns
    }
    return { scans, noReads, unknowns, rate: scans > 0 ? (noReads + unknowns) / scans : 0 }
  }, [scanRows])

  const dailyScanData = useMemo(() => {
    const byDay = new Map<string, { scans: number; noReads: number; unknowns: number }>()
    for (const r of scanRows) {
      const d = byDay.get(r.day) ?? { scans: 0, noReads: 0, unknowns: 0 }
      d.scans += r.scans
      d.noReads += r.noReads
      d.unknowns += r.unknowns
      byDay.set(r.day, d)
    }
    return windowDays.map((day) => {
      const d = byDay.get(day) ?? { scans: 0, noReads: 0, unknowns: 0 }
      return {
        day,
        good: Math.max(0, d.scans - d.noReads - d.unknowns),
        noReads: d.noReads,
        unknowns: d.unknowns,
      }
    })
  }, [scanRows, windowDays])

  // Per-scanner history → error rate, regression trend, attention flag (the error prediction).
  const scannerRows = useMemo<ScannerRow[]>(() => {
    const byNode = new Map<string, ScanQualityRow[]>()
    for (const r of scanRows) {
      const list = byNode.get(r.node) ?? []
      list.push(r)
      byNode.set(r.node, list)
    }
    const out: ScannerRow[] = []
    for (const [node, rows] of byNode) {
      const sorted = [...rows].sort((a, b) => (a.day < b.day ? -1 : 1))
      let scans = 0
      let noReads = 0
      let unknowns = 0
      const dailyRates: number[] = []
      for (const r of sorted) {
        scans += r.scans
        noReads += r.noReads
        unknowns += r.unknowns
        if (r.scans > 0) dailyRates.push((r.noReads + r.unknowns) / r.scans)
      }
      const rate = scans > 0 ? (noReads + unknowns) / scans : 0
      const slope = regressionSlope(dailyRates)
      out.push({
        node,
        scans,
        noReads,
        unknowns,
        rate,
        slope,
        spark: dailyRates,
        attention: rate > ATTENTION_RATE || slope > ATTENTION_SLOPE,
      })
    }
    // Scanners needing attention first, then by error rate.
    out.sort((a, b) => Number(b.attention) - Number(a.attention) || b.rate - a.rate)
    return out
  }, [scanRows])

  // Traffic → per-conveyor heat for the 3D scene + legend figures.
  const traffic = useMemo(
    () => (topology ? attributeTraffic(trafficRows, nodeXZ, topology, lib) : null),
    [trafficRows, nodeXZ, topology, lib],
  )

  const transitData = useMemo(() => {
    const byDay = new Map(transitRows.map((r) => [r.day, r]))
    return windowDays.map((day) => {
      const r = byDay.get(day)
      return {
        day,
        p50: r ? +(r.p50Ms / 1000).toFixed(1) : undefined,
        p95: r ? +(r.p95Ms / 1000).toFixed(1) : undefined,
      }
    })
  }, [transitRows, windowDays])

  const columns: Column<ScannerRow>[] = [
    { key: 'node', header: 'Scan point', sortable: true, render: (r) => <span style={{ fontFamily: 'var(--font-mono)', fontSize: '.8rem' }}>{r.node}</span> },
    { key: 'scans', header: 'Scans', align: 'right', sortable: true, sortValue: (r) => r.scans },
    { key: 'noReads', header: 'No reads', align: 'right', sortable: true, sortValue: (r) => r.noReads },
    { key: 'unknowns', header: 'Unknowns', align: 'right', sortable: true, sortValue: (r) => r.unknowns },
    {
      key: 'rate',
      header: 'Error rate',
      align: 'right',
      sortable: true,
      sortValue: (r) => r.rate,
      render: (r) => <span style={{ color: r.rate > ATTENTION_RATE ? 'var(--danger)' : undefined }}>{(r.rate * 100).toFixed(2)} %</span>,
    },
    {
      key: 'trend',
      header: 'Trend',
      align: 'center',
      sortable: true,
      sortValue: (r) => r.slope,
      render: (r) => {
        const rising = r.slope > ATTENTION_SLOPE
        const falling = r.slope < -ATTENTION_SLOPE
        return (
          <span title={`${(r.slope * 100).toFixed(3)} %-points/day`} style={{ color: rising ? 'var(--danger)' : falling ? 'var(--herbal-lime)' : 'var(--text-dim)' }}>
            {rising ? '↗' : falling ? '↘' : '→'}
          </span>
        )
      },
    },
    { key: 'spark', header: 'Daily error rate', render: (r) => <Sparkline values={r.spark} color={r.attention ? CHART_COLORS.red : CHART_COLORS.lime} /> },
    {
      key: 'attention',
      header: 'Status',
      sortable: true,
      sortValue: (r) => Number(r.attention),
      render: (r) =>
        r.attention ? <span className="badge badge-danger">needs attention</span> : <span className="badge badge-success">ok</span>,
    },
  ]

  if (!warehouseId) {
    return (
      <div className="app-content">
        <div className="glass" style={{ padding: '2.5rem', textAlign: 'center', color: 'var(--text-dim)' }}>
          Select a warehouse in the top bar to load its material-flow report.
        </div>
      </div>
    )
  }

  return (
    <div className="app-content">
      <div className="page-head" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '1rem', flexWrap: 'wrap' }}>
        <div>
          <span className="eyebrow">Reporting</span>
          <h1>Material flow</h1>
          <p>Scan quality at every scan point, scanners predicted to need attention, and where the conveyor traffic runs.</p>
        </div>
        <label style={{ display: 'inline-flex', alignItems: 'center', gap: '.5rem', fontSize: '.85rem' }}>
          <span className="muted" style={{ fontFamily: 'var(--font-mono)', fontSize: '.65rem', letterSpacing: '.12em', textTransform: 'uppercase' }}>Window</span>
          <Select
            ariaLabel="Report window"
            value={String(days)}
            onChange={(v) => setDays(Number(v))}
            style={{ width: 140 }}
            options={DAY_OPTIONS.map((d) => ({ value: String(d), label: `Last ${d} days` }))}
          />
        </label>
      </div>

      {error && <p className="badge badge-danger" style={{ marginBottom: '1rem' }}>{error}</p>}

      <div style={{ display: 'flex', gap: '.5rem', flexWrap: 'wrap', marginBottom: '1rem' }}>
        <StatChip label="Scans" value={totals.scans.toLocaleString()} color={CHART_COLORS.lime} />
        <StatChip label="No reads" value={totals.noReads.toLocaleString()} color={CHART_COLORS.amber} />
        <StatChip label="Unknowns" value={totals.unknowns.toLocaleString()} color={CHART_COLORS.red} />
        <StatChip label="Error rate" value={`${(totals.rate * 100).toFixed(2)} %`} color={totals.rate > ATTENTION_RATE ? 'var(--danger)' : undefined} />
        <StatChip label="Scan points" value={scannerRows.length} />
      </div>

      <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', marginBottom: '1rem' }}>
        <ChartCard title="Scan quality per day" subtitle="Good reads vs no reads vs unknown barcodes across all scan points.">
          {loading && scanRows.length === 0 ? (
            <LoadingNote />
          ) : scanRows.length === 0 ? (
            <EmptyHistoryNote what="scans" />
          ) : (
            <StackedBars
              data={dailyScanData}
              xKey="day"
              xTick={(d) => String(d).slice(5)}
              series={[
                { key: 'good', name: 'Good reads', color: CHART_COLORS.lime },
                { key: 'noReads', name: 'No reads', color: CHART_COLORS.amber },
                { key: 'unknowns', name: 'Unknowns', color: CHART_COLORS.red },
              ]}
            />
          )}
        </ChartCard>
        <ChartCard title="Transit times" subtitle="Daily p50 / p95 transport time in seconds (completed transports).">
          {loading && transitRows.length === 0 ? (
            <LoadingNote />
          ) : transitRows.length === 0 ? (
            <EmptyHistoryNote what="completed transports" />
          ) : (
            <DailyChart
              data={transitData}
              series={[
                { key: 'p50', name: 'p50 (s)', color: CHART_COLORS.blue },
                { key: 'p95', name: 'p95 (s)', color: CHART_COLORS.violet },
              ]}
            />
          )}
        </ChartCard>
      </div>

      <div className="glass" style={{ padding: '1rem 1.1rem', marginBottom: '1rem' }}>
        <h3 style={{ margin: '0 0 .15rem', fontSize: '.95rem' }}>Scanners needing attention</h3>
        <p className="muted" style={{ margin: '0 0 .75rem', fontSize: '.75rem' }}>
          History-based prediction: a scanner is flagged when its no-read/unknown rate exceeds {ATTENTION_RATE * 100} % or its
          daily error rate is rising (regression slope over the window).
        </p>
        {loading && scannerRows.length === 0 ? (
          <LoadingNote />
        ) : scannerRows.length === 0 ? (
          <EmptyHistoryNote what="scan-point history" />
        ) : (
          <DataTable
            columns={columns}
            rows={scannerRows}
            rowKey={(r) => r.node}
            search={(r) => r.node}
            searchPlaceholder="Search scan points…"
            pageSize={10}
            empty="No scan points in this window."
          />
        )}
      </div>

      <div className="glass" style={{ padding: '1rem 1.1rem' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '.75rem', flexWrap: 'wrap' }}>
          <div>
            <h3 style={{ margin: '0 0 .15rem', fontSize: '.95rem' }}>Traffic heatmap</h3>
            <p className="muted" style={{ margin: '0 0 .75rem', fontSize: '.75rem' }}>
              The real 3D topology with each conveyor tinted by its transports over the window (edge traffic attributed to the
              nearest conveyor, log scale).
            </p>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap' }}>
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: '.4rem', fontSize: '.85rem' }}>
              <input type="checkbox" checked={showLabels} onChange={(e) => setShowLabels(e.target.checked)} />
              Labels
            </label>
            {traffic && <HeatLegend min={0} max={traffic.maxPerConveyor} unit="transports" />}
          </div>
        </div>
        {!topology || topology.equipment.length === 0 ? (
          <EmptyHistoryNote what="placed automation topology (build one under Engineering → Automation topology)" />
        ) : trafficRows.length === 0 ? (
          <EmptyHistoryNote what="conveyor traffic" />
        ) : (
          <div style={{ height: 'min(60vh, 540px)', borderRadius: 10, overflow: 'hidden' }}>
            <Suspense fallback={<LoadingNote>Loading 3D scene…</LoadingNote>}>
              <ReportScene3D topology={topology} lib={lib} conveyorHeat={traffic?.heatByPlaced ?? null} showLabels={showLabels} />
            </Suspense>
          </div>
        )}
        {traffic && traffic.unattributed > 0 && (
          <p className="muted" style={{ margin: '.5rem 0 0', fontSize: '.72rem' }}>
            {traffic.unattributed.toLocaleString()} transports ran on routing edges that could not be mapped to a placed conveyor
            (no node position within 4 m) and are not painted.
          </p>
        )}
      </div>
    </div>
  )
}
