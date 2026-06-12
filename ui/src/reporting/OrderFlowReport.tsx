// Reporting → Inbound / Outbound: one screen shape for both directions. Headline figures
// (expected / started / active), daily received-started-completed volumes over the last 90 days,
// and the "day map": the window compiled into the 24 hours of the day with peaks highlighted.
import { useEffect, useMemo, useState } from 'react'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { loadOrderFlow, type FlowDirection, type OrderFlowReportDto } from './api'
import { addDays } from './forecast'
import {
  CHART_COLORS,
  ChartCard,
  DailyChart,
  EmptyHistoryNote,
  HourOfDayStrip,
  LoadingNote,
  StatChip,
} from './charts'

const DAYS = 90

const COPY: Record<FlowDirection, { title: string; intro: string; expected: string; expectedHint: string }> = {
  INBOUND: {
    title: 'Inbound',
    intro: 'Inbound orders over the last 90 days: what is expected, what is running, daily volumes and the hours of the day the goods actually arrive.',
    expected: 'Expected inbound',
    expectedHint: 'Received inbound orders that are not yet stock.',
  },
  OUTBOUND: {
    title: 'Outbound',
    intro: 'Outbound orders over the last 90 days: what is expected, what is running, daily volumes and the hours of the day the orders actually move.',
    expected: 'Expected outbound',
    expectedHint: 'Received outbound orders that are not yet released.',
  },
}

export default function OrderFlowReport({ direction }: { direction: FlowDirection }) {
  const { currentWarehouseId: warehouseId } = useWarehouse()

  const [report, setReport] = useState<OrderFlowReportDto | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!warehouseId) return
    let cancelled = false
    setLoading(true)
    setError(null)
    setReport(null)
    loadOrderFlow(warehouseId, direction, DAYS)
      .then((r) => !cancelled && setReport(r))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [warehouseId, direction])

  // Full 90-day window so quiet days render as zero instead of disappearing.
  const dailyData = useMemo(() => {
    const end = new Date().toISOString().slice(0, 10)
    const byDay = new Map((report?.perDay ?? []).map((d) => [d.day, d]))
    const out: Array<{ day: string; received: number; started: number; completed: number }> = []
    for (let i = DAYS - 1; i >= 0; i--) {
      const day = addDays(end, -i)
      const d = byDay.get(day)
      out.push({ day, received: d?.received ?? 0, started: d?.started ?? 0, completed: d?.completed ?? 0 })
    }
    return out
  }, [report])

  const hourCounts = useMemo(() => {
    const counts = new Array<number>(24).fill(0)
    for (const h of report?.hourOfDay ?? []) {
      if (h.hour >= 0 && h.hour < 24) counts[h.hour] += h.count
    }
    return counts
  }, [report])

  const hasHistory = (report?.perDay?.length ?? 0) > 0
  const copy = COPY[direction]

  if (!warehouseId) {
    return (
      <div className="app-content">
        <div className="glass" style={{ padding: '2.5rem', textAlign: 'center', color: 'var(--text-dim)' }}>
          Select a warehouse in the top bar to load its {copy.title.toLowerCase()} report.
        </div>
      </div>
    )
  }

  return (
    <div className="app-content">
      <div className="page-head">
        <span className="eyebrow">Reporting</span>
        <h1>{copy.title}</h1>
        <p>{copy.intro}</p>
      </div>

      {error && <p className="badge badge-danger" style={{ marginBottom: '1rem' }}>{error}</p>}

      <div style={{ display: 'flex', gap: '.5rem', flexWrap: 'wrap', marginBottom: '1rem' }} title={copy.expectedHint}>
        <StatChip label={copy.expected} value={report ? report.expected.toLocaleString() : '-'} color={CHART_COLORS.blue} />
        <StatChip label="Started" value={report ? report.started.toLocaleString() : '-'} color={CHART_COLORS.amber} />
        <StatChip label="Active" value={report ? report.active.toLocaleString() : '-'} color={CHART_COLORS.lime} />
      </div>

      <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
        <ChartCard
          title={`${copy.title} per day`}
          subtitle={`Orders received, started and completed per day over the last ${DAYS} days.`}
          minWidth={460}
        >
          {loading && !report ? (
            <LoadingNote />
          ) : !hasHistory ? (
            <EmptyHistoryNote what={`${copy.title.toLowerCase()} history`} />
          ) : (
            <DailyChart
              data={dailyData}
              height={280}
              series={[
                { key: 'received', name: 'Received', color: CHART_COLORS.blue },
                { key: 'started', name: 'Started', color: CHART_COLORS.amber },
                { key: 'completed', name: 'Completed', color: CHART_COLORS.lime },
              ]}
            />
          )}
        </ChartCard>
        <ChartCard
          title="Hours of the day"
          subtitle={`The last ${DAYS} days compiled into hours of the day: when the ${copy.title.toLowerCase()} volume actually runs.`}
          minWidth={380}
        >
          {loading && !report ? (
            <LoadingNote />
          ) : hourCounts.every((c) => c === 0) ? (
            <EmptyHistoryNote what={`${copy.title.toLowerCase()} activity`} />
          ) : (
            <div style={{ paddingTop: '.75rem' }}>
              <HourOfDayStrip counts={hourCounts} caption={`Each cell is one hour of the day (00-23), summed over the window.`} />
            </div>
          )}
        </ChartCard>
      </div>
    </div>
  )
}
