// Stock dashboard (dashboards:stock). Levels vs the 90-day norm: location & ASRS utilisation drawn
// inside a shaded 90-day normal band (verified presentation pattern), the 90-day utilisation line,
// plus value-free counts (HU count, SKUs with stock). Polls every 60 s for the live figures and
// loads the 90-day density history once (reusing the reporting storage-density report).
import { useEffect, useMemo, useState } from 'react'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useT } from '../i18n/useT'
import { usePoll } from './usePoll'
import { Hero, type DashState } from './components'
import { ChartCard, ChartRow, HeroRow, NoWarehouse, PageHead } from './DashboardPage'
import { pct1 } from './format'
import { getAlertThresholds, loadInventoryDashboard, type AlertThresholds } from './api'
import { loadStorageDensity, type StorageDensityRow } from '../reporting/api'
import { CHART_COLORS, DailyChart, EmptyHistoryNote, LoadingNote } from '../reporting/charts'
import { addDays } from '../reporting/forecast'

const POLL = 60_000
const DAYS = 90
const DEFAULTS: AlertThresholds = { scanNoReadPct: 1, receiveErrorsDay: 5, asrsUtilisationPct: 85, blockedLinesPct: 5, putawayBacklogAgeMin: 120, replenishmentOldestAgeMin: 120 }

function utilState(pct: number, T: AlertThresholds): DashState {
  if (pct >= 95) return 'critical'
  if (pct >= T.asrsUtilisationPct) return 'warning'
  return 'ok'
}

export default function StockDashboard() {
  const t = useT('dashboards')
  const { currentWarehouseId: wh } = useWarehouse()
  const inv = usePoll(wh, loadInventoryDashboard, POLL)
  const thr = usePoll(wh ? 'g' : '', () => getAlertThresholds().catch(() => DEFAULTS), 120_000)
  const T = thr.data ?? DEFAULTS

  const [history, setHistory] = useState<StorageDensityRow[] | null>(null)
  const [histLoading, setHistLoading] = useState(false)
  useEffect(() => {
    if (!wh) return
    let cancelled = false
    setHistLoading(true)
    setHistory(null)
    loadStorageDensity(wh, DAYS)
      .then((r) => !cancelled && setHistory(r))
      .catch(() => !cancelled && setHistory([]))
      .finally(() => !cancelled && setHistLoading(false))
    return () => {
      cancelled = true
    }
  }, [wh])

  // Daily mean utilisation across blocks, plus a 90-day normal band (mean ± 1σ) for the shaded zone.
  const { dailyData, band } = useMemo(() => {
    const rows = history ?? []
    const byDay = new Map<string, number[]>()
    for (const r of rows) {
      const arr = byDay.get(r.day) ?? []
      arr.push(r.pct)
      byDay.set(r.day, arr)
    }
    const end = new Date().toISOString().slice(0, 10)
    const series: Array<{ day: string; util?: number }> = []
    const all: number[] = []
    for (let i = DAYS - 1; i >= 0; i--) {
      const day = addDays(end, -i)
      const vals = byDay.get(day)
      const mean = vals && vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : undefined
      if (mean != null) all.push(mean)
      series.push({ day, util: mean })
    }
    const mu = all.length ? all.reduce((a, b) => a + b, 0) / all.length : 0
    const variance = all.length ? all.reduce((a, b) => a + (b - mu) ** 2, 0) / all.length : 0
    const sd = Math.sqrt(variance)
    return { dailyData: series, band: { low: Math.max(0, mu - sd), high: Math.min(100, mu + sd), mu } }
  }, [history])

  if (!wh) return <NoWarehouse />

  const iv = inv.data
  const overall = iv?.utilisationPct ?? 0
  const asrs = iv?.asrsUtilisationPct ?? 0
  const hasHistory = (history?.length ?? 0) > 0

  // Where overall utilisation sits relative to the 90-day band (in-band = ok).
  const inBand = overall >= band.low && overall <= band.high
  const overallState: DashState = utilState(overall, T)

  return (
    <div className="app-content">
      <PageHead
        title={t('stockTitle', 'Stock levels')}
        intro={t('stockIntro', 'How full the warehouse is, today against the last 90 days. Current utilisation is shown inside its 90-day normal band; colour appears only when capacity gets tight.')}
        updatedAt={inv.lastUpdated}
        stale={inv.stale}
      />

      <HeroRow>
        <Hero
          big
          label={t('locationUtil', 'Location utilisation')}
          value={overall}
          unit="%"
          state={overallState}
          fill={overall / 100}
          ranges={{ warnAt: T.asrsUtilisationPct / 100, critAt: 0.95 }}
          target={band.mu / 100}
          context={hasHistory ? `${t('normalBand', '90-day band')} ${pct1(band.low)}–${pct1(band.high)}% · ${inBand ? t('inBand', 'in band') : t('outOfBand', 'outside band')}` : undefined}
        />
        <Hero
          big
          label={t('asrsUtil', 'ASRS utilisation')}
          value={asrs}
          unit="%"
          state={utilState(asrs, T)}
          fill={asrs / 100}
          ranges={{ warnAt: T.asrsUtilisationPct / 100, critAt: 0.95 }}
          context={`${t('warnAt', 'warn at')} ${T.asrsUtilisationPct}% · ${t('critAt', 'crit at')} 95%`}
        />
        <Hero label={t('huCount', 'Handling units')} value={iv?.huCount ?? 0} state="ok" />
        <Hero label={t('skuCount', 'SKUs with stock')} value={iv?.skuCountWithStock ?? 0} state="ok" />
      </HeroRow>

      <ChartRow>
        <ChartCard title={t('util90', '90-day utilisation')} subtitle={t('util90Sub', 'Mean storage-block occupancy per day over the last 90 days. The dashed line is the 90-day mean (the centre of the normal band).')} minWidth={520}>
          {histLoading ? (
            <LoadingNote />
          ) : !hasHistory ? (
            <EmptyHistoryNote what={t('whatUtilHistory', 'utilisation history')} />
          ) : (
            <DailyChart
              data={dailyData.map((d) => ({ day: d.day, util: d.util, mean: band.mu }))}
              height={280}
              yTick={(v) => `${v}%`}
              series={[
                { key: 'util', name: t('utilisation', 'Utilisation %'), color: CHART_COLORS.blue },
                { key: 'mean', name: t('mean90', '90-day mean'), color: CHART_COLORS.grey, dashed: true },
              ]}
            />
          )}
        </ChartCard>
      </ChartRow>
    </div>
  )
}
