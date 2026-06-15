// Replenishment dashboard (dashboards:replenishment). Heroes: urgent replenishments, projected
// stockouts within ~2 h, open tasks with oldest age. Chart: the time-to-stockout urgency strip,
// worst-first (ascending minutes), normalised. Polls every 60 s. The time-to-stockout ranking is
// our own derivation (no verified industry precedent — flagged in the spec).
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useT } from '../i18n/useT'
import { useCatalog } from '../lib/useCatalog'
import { usePoll } from './usePoll'
import { Hero, WorstFirstStrip, type DashState } from './components'
import { ChartCard, ChartRow, HeroRow, NoWarehouse, PageHead } from './DashboardPage'
import { minsLabel } from './format'
import { getAlertThresholds, loadReplenishmentDashboard, type AlertThresholds } from './api'

const POLL = 60_000
const HORIZON_MIN = 120 // "within the next 2 h" stockout horizon
const DEFAULTS: AlertThresholds = { scanNoReadPct: 1, receiveErrorsDay: 5, asrsUtilisationPct: 85, blockedLinesPct: 5, putawayBacklogAgeMin: 120, replenishmentOldestAgeMin: 120 }

export default function ReplenishmentDashboard() {
  const t = useT('dashboards')
  const { currentWarehouseId: wh } = useWarehouse()
  const cat = useCatalog(wh)
  const rep = usePoll(wh, loadReplenishmentDashboard, POLL)
  const thr = usePoll(wh ? 'g' : '', () => getAlertThresholds().catch(() => DEFAULTS), 120_000)
  const T = thr.data ?? DEFAULTS

  if (!wh) return <NoWarehouse />

  const d = rep.data
  const urgentState: DashState = (d?.urgent ?? 0) > 0 ? 'critical' : 'ok'
  const oldest = d?.oldestAgeMin ?? 0
  const taskState: DashState = oldest > T.replenishmentOldestAgeMin * 2 ? 'critical' : oldest > T.replenishmentOldestAgeMin ? 'warning' : (d?.openTasks ?? 0) > 0 ? 'warning' : 'ok'
  const soon = (d?.projectedStockouts ?? []).filter((s) => s.minutesToStockout <= HORIZON_MIN).length
  const stockoutState: DashState = soon > 0 ? 'critical' : 'ok'

  // Worst-first by time-to-stockout (soonest first); the bar is inverted so the most urgent is longest.
  const stockouts = [...(d?.projectedStockouts ?? [])].sort((a, b) => a.minutesToStockout - b.minutesToStockout).slice(0, 12)
  const maxMin = Math.max(HORIZON_MIN, ...stockouts.map((s) => s.minutesToStockout), 1)

  return (
    <div className="app-content">
      <PageHead
        title={t('replenishmentTitle', 'Replenishment')}
        intro={t('replenishmentIntro', 'Current demand and the pick faces about to run dry. Urgent replenishments and projected stockouts first; the strip ranks locations by time-to-stockout, soonest at the top.')}
        updatedAt={rep.lastUpdated}
        stale={rep.stale}
      />

      <HeroRow>
        <Hero label={t('urgent', 'Urgent replenishments')} value={d?.urgent ?? 0} state={urgentState} context={t('belowMin', 'pick-face below minimum with open demand')} />
        <Hero label={t('projStockouts', 'Stockouts within 2 h')} value={soon} state={stockoutState} />
        <Hero label={t('openTasks', 'Open tasks')} value={d?.openTasks ?? 0} state={taskState} context={`${t('oldest', 'oldest')} ${minsLabel(oldest)}`} fill={Math.min(1, oldest / (T.replenishmentOldestAgeMin * 2))} ranges={{ warnAt: 0.5, critAt: 1 }} />
      </HeroRow>

      <ChartRow>
        <ChartCard title={t('urgencyStrip', 'Time to stockout')} subtitle={t('urgencyStripSub', 'Affected pick locations, soonest stockout first. Colour marks locations projected to run dry within 2 h.')} minWidth={460}>
          <div style={{ paddingTop: '.5rem' }}>
            <WorstFirstStrip
              entries={stockouts.map((s) => ({
                label: cat.locationCode(s.locationCode), // backend sends the location id here; show its code
                value: maxMin - s.minutesToStockout, // shorter time-to-stockout → longer (more urgent) bar
                display: minsLabel(s.minutesToStockout),
                breach: s.minutesToStockout <= HORIZON_MIN,
              }))}
              max={maxMin}
            />
          </div>
        </ChartCard>
      </ChartRow>
    </div>
  )
}
