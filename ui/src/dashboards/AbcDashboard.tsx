// ABC movers dashboard (dashboards:abc). Canonical Pareto presentation (verified): picks per SKU
// over the trailing 90 days ranked descending, coloured by A/B/C class, with the cumulative-% curve
// and the 80% cut line. Heroes: A/B/C counts, risers, fallers. Plus top-10 and bottom-10 movers as
// ranked bars and riser/faller strips (no tables). Polls every 60 s. Riser drill-down would link to
// slotting (an A-class SKU slotted far from outfeeds is what slotting optimises).
import { useMemo } from 'react'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useT } from '../i18n/useT'
import { usePoll } from './usePoll'
import { Hero, ParetoChart, WorstFirstStrip, type ParetoDatum } from './components'
import { ChartCard, ChartRow, HeroRow, NoWarehouse, PageHead } from './DashboardPage'
import { loadAbc } from './api'

const POLL = 60_000

export default function AbcDashboard() {
  const t = useT('dashboards')
  const { currentWarehouseId: wh } = useWarehouse()
  const abc = usePoll(wh, loadAbc, POLL)

  // Classify each Pareto point by its cumulative-% position (A ≤ ~80%, B ≤ ~95%, C the rest).
  const pareto: ParetoDatum[] = useMemo(() => {
    const pts = abc.data?.pareto ?? []
    return pts.map((p) => ({
      label: p.skuId,
      picks: p.picks,
      cumPct: p.cumPct,
      cls: p.cumPct <= 80 ? 'A' : p.cumPct <= 95 ? 'B' : 'C',
    }))
  }, [abc.data])

  if (!wh) return <NoWarehouse />

  const d = abc.data
  const topMax = Math.max(1, ...(d?.top ?? []).map((m) => m.picks))

  return (
    <div className="app-content">
      <PageHead
        title={t('abcTitle', 'ABC movers')}
        intro={t('abcIntro', 'Movement concentration over the last 90 days, ranked by picks per SKU (openWCS has no item costs). The Pareto shows the A/B/C split; risers and fallers flag SKUs whose 14-day rate is pulling away from their 90-day norm.')}
        updatedAt={abc.lastUpdated}
        stale={abc.stale}
      />

      <HeroRow>
        <Hero label={t('aSkus', 'A-class SKUs')} value={d?.a ?? 0} state="ok" context={t('aShare', 'top ~80% of movement')} />
        <Hero label={t('bSkus', 'B-class SKUs')} value={d?.b ?? 0} state="ok" />
        <Hero label={t('cSkus', 'C-class SKUs')} value={d?.c ?? 0} state="ok" context={t('cShare', '~5% of movement')} />
        <Hero label={t('risers', 'Risers')} value={d?.risers.length ?? 0} state="ok" context={t('risersHint', '14-day rate above 90-day')} />
        <Hero label={t('fallers', 'Fallers')} value={d?.fallers.length ?? 0} state="ok" context={t('fallersHint', '14-day rate below 90-day')} />
      </HeroRow>

      <ChartRow>
        <ChartCard title={t('paretoTitle', 'Pareto — picks per SKU')} subtitle={t('paretoSub', 'SKUs ranked by picks, coloured by class; the line is cumulative %, the dashed line marks 80%.')} minWidth={560}>
          <ParetoChart data={pareto} />
        </ChartCard>
      </ChartRow>

      <ChartRow>
        <ChartCard title={t('topMovers', 'Top 10 movers')} subtitle={t('topMoversSub', 'Most-picked SKUs over the window.')} minWidth={340}>
          <div style={{ paddingTop: '.5rem' }}>
            <WorstFirstStrip entries={(d?.top ?? []).slice(0, 10).map((m) => ({ label: m.skuId, value: m.picks, display: String(m.picks) }))} max={topMax} />
          </div>
        </ChartCard>
        <ChartCard title={t('bottomMovers', 'Bottom 10 movers')} subtitle={t('bottomMoversSub', 'Least-picked SKUs with any movement.')} minWidth={340}>
          <div style={{ paddingTop: '.5rem' }}>
            <WorstFirstStrip entries={(d?.bottom ?? []).slice(0, 10).map((m) => ({ label: m.skuId, value: m.picks, display: String(m.picks) }))} max={Math.max(1, ...(d?.bottom ?? []).map((m) => m.picks))} />
          </div>
        </ChartCard>
      </ChartRow>

      <ChartRow>
        <ChartCard title={t('risers', 'Risers')} subtitle={t('risersSub', 'SKUs whose 14-day pick rate most exceeds their 90-day rate.')} minWidth={340}>
          <div style={{ paddingTop: '.5rem' }}>
            <WorstFirstStrip
              entries={(d?.risers ?? []).slice(0, 10).map((r) => ({ label: r.skuId, value: Math.max(0, r.pct14d - r.pct90d), display: `+${Math.round(r.pct14d - r.pct90d)}%`, state: 'warning' }))}
            />
          </div>
        </ChartCard>
        <ChartCard title={t('fallers', 'Fallers')} subtitle={t('fallersSub', 'SKUs whose 14-day pick rate most lags their 90-day rate.')} minWidth={340}>
          <div style={{ paddingTop: '.5rem' }}>
            <WorstFirstStrip
              entries={(d?.fallers ?? []).slice(0, 10).map((r) => ({ label: r.skuId, value: Math.max(0, r.pct90d - r.pct14d), display: `−${Math.round(r.pct90d - r.pct14d)}%` }))}
            />
          </div>
        </ChartCard>
      </ChartRow>
    </div>
  )
}
