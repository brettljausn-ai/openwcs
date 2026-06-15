// Outbound dashboard (dashboards:outbound). Outbound-centric Level-2 view: open orders with
// projected completion, lines picked, shorts (accuracy proxy), released-today, plus pick rate by
// hour and the dispatch-route readiness strip (worst-first). Polls every 60 s.
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useT } from '../i18n/useT'
import { usePoll } from './usePoll'
import { Hero, HourBars, WorstFirstStrip, type DashState } from './components'
import { ChartCard, ChartRow, HeroRow, NoWarehouse, PageHead } from './DashboardPage'
import { minsLabel, timeHm } from './format'
import { loadDispatch, loadOrdersDashboard, loadSla } from './api'

const POLL = 60_000
// On-time-to-cutoff target: warn below 95 %, critical below 90 % (our default, configurable thinking
// in the spec — there is no published industry threshold). Drawn as a bullet bar where higher is better.
const ONTIME_WARN = 95
const ONTIME_CRIT = 90

export default function OutboundDashboard() {
  const t = useT('dashboards')
  const { currentWarehouseId: wh } = useWarehouse()
  const orders = usePoll(wh, loadOrdersDashboard, POLL)
  const dispatch = usePoll(wh, loadDispatch, POLL)
  const sla = usePoll(wh, (id) => loadSla(id, 14), POLL)

  if (!wh) return <NoWarehouse />

  const ob = orders.data?.outbound
  const dp = dispatch.data
  const sl = sla.data
  const openState: DashState = (ob?.open ?? 0) > 0 ? 'warning' : 'ok'
  const shortsState: DashState = (ob?.shortsToday ?? 0) > 0 ? 'warning' : 'ok'
  const onTime = sl?.onTimeToCutoffPctToday ?? 0
  const onTimeState: DashState = !sl ? 'ok' : onTime < ONTIME_CRIT ? 'critical' : onTime < ONTIME_WARN ? 'warning' : 'ok'

  const hourData = (orders.data?.perHour ?? []).map((h) => ({ hour: h.hour, outbound: h.outbound }))

  return (
    <div className="app-content">
      <PageHead
        title={t('outboundTitle', 'Outbound')}
        intro={t('outboundIntro', 'Fulfilment at a glance: open orders with a projected completion, pick throughput, shorts, and how each dispatch route is tracking toward its cutoff.')}
        updatedAt={orders.lastUpdated}
        stale={orders.stale}
      />

      <HeroRow>
        <Hero label={t('openOutbound', 'Open outbound orders')} value={ob?.open ?? 0} state={openState} spark={(orders.data?.perHour ?? []).map((h) => h.outbound)} />
        <Hero label={t('releasedToday', 'Released today')} value={ob?.releasedToday ?? 0} state="ok" />
        <Hero label={t('projFinishLabel', 'Projected finish')} value={timeHm(ob?.projectedFinishIso)} state="ok" />
        <Hero label={t('linesPicked', 'Lines picked today')} value={ob?.linesPickedToday ?? 0} state="ok" />
        <Hero label={t('shorts', 'Shorts today')} value={ob?.shortsToday ?? 0} state={shortsState} context={t('accuracyProxy', 'order-accuracy proxy')} />
        <Hero
          label={t('onTimeToCutoff', 'On-time to cutoff')}
          value={onTime}
          unit="%"
          state={onTimeState}
          fill={Math.min(1, onTime / 100)}
          context={`${t('target', 'target')} ≥ ${ONTIME_WARN}%`}
          spark={(sl?.perDay ?? []).map((d) => d.onTimePct)}
        />
        <Hero
          label={t('cycleTimeMedian', 'Order cycle time (median)')}
          value={minsLabel(sl?.orderCycleTimeMedianMinToday ?? 0)}
          state="ok"
          spark={(sl?.perDay ?? []).map((d) => d.cycleMedianMin)}
        />
      </HeroRow>

      <ChartRow>
        <ChartCard title={t('pickRatePerHour', 'Pick rate by hour today')} subtitle={t('pickRatePerHourSub', 'Outbound order activity by hour of the day.')} minWidth={460}>
          {hourData.length === 0 ? (
            <p className="muted" style={{ padding: '1.4rem', textAlign: 'center' }}>{t('noHourData', 'No activity recorded today yet.')}</p>
          ) : (
            <HourBars data={hourData} keys={[{ key: 'outbound', name: t('picks', 'Picks'), color: '#8DC63F' }]} />
          )}
        </ChartCard>
        <ChartCard title={t('routeReadiness', 'Dispatch route readiness')} subtitle={t('routeReadinessSub', 'Routes worst-first; colour marks routes not ready against their cutoff.')} minWidth={380}>
          <div style={{ paddingTop: '.5rem' }}>
            <WorstFirstStrip
              entries={[...(dp?.routes ?? [])]
                .sort((a, b) => a.ordersReady / Math.max(1, a.ordersAssigned) - b.ordersReady / Math.max(1, b.ordersAssigned))
                .map((r) => ({
                  label: `${r.routeCode} · ${timeHm(r.cutoffIso)}`,
                  value: r.ordersAssigned > 0 ? (r.ordersReady / r.ordersAssigned) * 100 : 100,
                  display: `${r.ordersReady}/${r.ordersAssigned}`,
                  breach: r.status !== 'DEPARTED' && r.ordersReady < r.ordersAssigned,
                }))}
              max={100}
            />
          </div>
        </ChartCard>
      </ChartRow>
    </div>
  )
}
