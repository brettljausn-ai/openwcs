// Inbound dashboard (dashboards:inbound). Inbound-centric Level-2 view: open orders & expected,
// putaway backlog with oldest age, HUs received, plus receipts-per-hour today. Polls every 60 s.
// NetSuite practitioner guidance (spec §5) splits dock-to-stock into receive + putaway stages;
// putaway backlog is the actionable stage we can compute from the contracts.
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useT } from '../i18n/useT'
import { usePoll } from './usePoll'
import { Hero, HourBars, type DashState } from './components'
import { ChartCard, ChartRow, HeroRow, NoWarehouse, PageHead } from './DashboardPage'
import { minsLabel, timeHm } from './format'
import {
  getAlertThresholds,
  loadInventoryDashboard,
  loadOrdersDashboard,
  type AlertThresholds,
} from './api'

const POLL = 60_000
const DEFAULTS: AlertThresholds = { scanNoReadPct: 1, receiveErrorsDay: 5, asrsUtilisationPct: 85, blockedLinesPct: 5, putawayBacklogAgeMin: 120, replenishmentOldestAgeMin: 120 }

export default function InboundDashboard() {
  const t = useT('dashboards')
  const { currentWarehouseId: wh } = useWarehouse()
  const orders = usePoll(wh, loadOrdersDashboard, POLL)
  const inv = usePoll(wh, loadInventoryDashboard, POLL)
  const thr = usePoll(wh ? 'g' : '', () => getAlertThresholds().catch(() => DEFAULTS), 120_000)
  const T = thr.data ?? DEFAULTS

  if (!wh) return <NoWarehouse />

  const ob = orders.data
  const iv = inv.data
  const oldest = iv?.putawayBacklog.oldestAgeMin ?? 0
  const backlogState: DashState = oldest > T.putawayBacklogAgeMin * 2 ? 'critical' : oldest > T.putawayBacklogAgeMin ? 'warning' : (iv?.putawayBacklog.count ?? 0) > 0 ? 'warning' : 'ok'
  const openState: DashState = (ob?.inbound.open ?? 0) > 0 ? 'warning' : 'ok'

  const hourData = (ob?.perHour ?? []).map((h) => ({ hour: h.hour, inbound: h.inbound }))

  return (
    <div className="app-content">
      <PageHead
        title={t('inboundTitle', 'Inbound')}
        intro={t('inboundIntro', 'Receiving and putaway at a glance: what is open and expected, the putaway backlog and how it is ageing, and when receipts actually land today.')}
        updatedAt={orders.lastUpdated}
        stale={orders.stale && inv.stale}
      />

      <HeroRow>
        <Hero label={t('openInbound', 'Open inbound orders')} value={ob?.inbound.open ?? 0} state={openState} spark={(ob?.perHour ?? []).map((h) => h.inbound)} />
        <Hero label={t('expectedToday', 'Expected today')} value={ob?.inbound.expectedToday ?? 0} state="ok" />
        <Hero label={t('projFinishLabel', 'Projected finish')} value={timeHm(ob?.inbound.projectedFinishIso)} state="ok" />
        <Hero label={t('husReceived', 'HUs received today')} value={iv?.husReceivedToday ?? 0} state="ok" />
        <Hero
          label={t('putawayBacklog', 'Putaway backlog')}
          value={iv?.putawayBacklog.count ?? 0}
          state={backlogState}
          context={`${t('oldest', 'oldest')} ${minsLabel(oldest)}`}
          fill={Math.min(1, oldest / (T.putawayBacklogAgeMin * 2))}
          ranges={{ warnAt: 0.5, critAt: 1 }}
        />
      </HeroRow>

      <ChartRow>
        <ChartCard title={t('receiptsPerHour', 'Receipts per hour today')} subtitle={t('receiptsPerHourSub', 'Inbound order activity by hour of the day.')} minWidth={460}>
          {hourData.length === 0 ? (
            <p className="muted" style={{ padding: '1.4rem', textAlign: 'center' }}>{t('noHourData', 'No activity recorded today yet.')}</p>
          ) : (
            <HourBars data={hourData} keys={[{ key: 'inbound', name: t('receipts', 'Receipts'), color: '#5aa9ff' }]} />
          )}
        </ChartCard>
      </ChartRow>
    </div>
  )
}
