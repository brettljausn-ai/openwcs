// Dashboards landing page (dashboards:overview, also the post-login home / index route). Level-1
// situation assessment per the spec: five tiles — Stock-blocking (top-left, the single most
// actionable number), Inbound, Outbound, Dispatch, Automation. Each carries a hero + one or two
// secondary figures + sparkline + a computed state colour and a drill-down link into its dashboard.
// Tiles poll every 15 s; a tile greys to "stale" after two consecutive failed polls.
import { useMemo } from 'react'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useT } from '../i18n/useT'
import { usePoll } from './usePoll'
import {
  AlertBadge,
  Hero,
  Tile,
  WorstFirstStrip,
  severityState,
  worstState,
  type DashState,
} from './components'
import { countdown, minsLabel, pct1, timeHm } from './format'
import {
  loadAlerts,
  loadAutomationSummary,
  loadDispatch,
  loadInventoryDashboard,
  loadOrdersDashboard,
  loadStockBlocking,
  getAlertThresholds,
  type AlertThresholds,
} from './api'

const POLL = 15_000
const DEFAULT_THRESHOLDS: AlertThresholds = {
  scanNoReadPct: 1,
  receiveErrorsDay: 5,
  asrsUtilisationPct: 85,
  blockedLinesPct: 5,
  putawayBacklogAgeMin: 120,
  replenishmentOldestAgeMin: 120,
}

export default function Overview() {
  const t = useT('dashboards')
  const { currentWarehouseId: wh } = useWarehouse()

  const orders = usePoll(wh, loadOrdersDashboard, POLL)
  const inv = usePoll(wh, loadInventoryDashboard, POLL)
  const blocking = usePoll(wh, loadStockBlocking, POLL)
  const dispatch = usePoll(wh, loadDispatch, POLL)
  const automation = usePoll(wh, loadAutomationSummary, POLL)
  const alerts = usePoll(wh, loadAlerts, POLL)
  // Thresholds rarely change — poll slowly (treated as static defaults if the call is missing).
  const thr = usePoll(wh ? 'global' : '', () => getAlertThresholds().catch(() => DEFAULT_THRESHOLDS), 60_000)
  const T = thr.data ?? DEFAULT_THRESHOLDS

  if (!wh) {
    return (
      <div className="app-content">
        <div className="glass" style={{ padding: '2.5rem', textAlign: 'center', color: 'var(--text-dim)' }}>
          {t('selectWarehouse', 'Select a warehouse in the top bar to load the dashboards.')}
        </div>
      </div>
    )
  }

  // ---- stock blocking (top-left, most actionable) ----
  const bl = blocking.data
  const blockedPct = bl && bl.openOutboundLines > 0 ? (bl.blockedLines / bl.openOutboundLines) * 100 : 0
  const blockingState: DashState = !bl
    ? 'ok'
    : bl.blockedLines === 0
      ? 'ok'
      : blockedPct > T.blockedLinesPct
        ? 'critical'
        : 'warning'

  // ---- inbound ----
  const ob = orders.data
  const inboundErrors = 0 // receive-error count is not in the orders dashboard contract; see note below
  const inboundState: DashState = !ob ? 'ok' : ob.inbound.open > 0 ? 'warning' : 'ok'

  // ---- outbound ----
  const outboundState: DashState = !ob ? 'ok' : ob.outbound.shortsToday > 0 ? 'warning' : 'ok'

  // ---- dispatch ----
  const dp = dispatch.data
  const dispatchState: DashState = !dp
    ? 'ok'
    : worstState(
        (dp.routes ?? []).map((r) =>
          r.status === 'DEPARTED' ? 'ok' : r.ordersReady < r.ordersAssigned ? 'warning' : 'ok',
        ),
      )

  // ---- automation (state from highest active alert severity) ----
  const au = automation.data
  const alertList = alerts.data ?? []
  const alertCounts = useMemo(() => {
    const c = { critical: 0, warning: 0, info: 0 }
    for (const a of alertList) {
      const st = severityState(a.severity)
      if (st === 'critical') c.critical++
      else if (st === 'warning') c.warning++
      else c.info++
    }
    return c
  }, [alertList])
  const automationState: DashState = alertCounts.critical > 0 ? 'critical' : alertCounts.warning > 0 ? 'warning' : 'ok'

  return (
    <div className="app-content">
      <div className="page-head">
        <span className="eyebrow">{t('eyebrow', 'Dashboards')}</span>
        <h1>{t('overviewTitle', 'Control room')}</h1>
        <p>{t('overviewIntro', 'At-a-glance state of the warehouse. A healthy floor looks quiet: colour means something needs a response. Each tile drills into its dashboard.')}</p>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: '1rem' }}>
        {/* Stock blocking — top-left, the single most actionable stock number */}
        <Tile title={t('tileStockBlocking', 'Stock blocking outbound')} to="/dashboards/replenishment" drillLabel={t('drillReplenishment', 'Replenishment')} stale={blocking.stale} updatedAt={blocking.lastUpdated}>
          <Hero
            big
            label={t('blockedLines', 'Unfulfillable order lines')}
            value={bl?.blockedLines ?? 0}
            state={blockingState}
            context={bl ? `${bl.distinctSkusShort} ${t('skusShort', 'SKUs short')} · ${bl.recoverableLines} ${t('recoverable', 'recoverable')} · ${bl.zeroStockLines} ${t('zeroStock', 'zero-stock')}` : undefined}
          />
          <div className="muted" style={{ fontSize: '.72rem', marginTop: '.4rem' }}>
            {bl ? `${pct1(blockedPct)}% ${t('ofOpenLines', 'of open outbound lines')}` : ''}
          </div>
        </Tile>

        {/* Inbound */}
        <Tile title={t('tileInbound', 'Inbound')} to="/dashboards/inbound" drillLabel={t('drillInbound', 'Inbound dashboard')} stale={orders.stale} updatedAt={orders.lastUpdated}>
          <Hero
            big
            label={t('openInbound', 'Open inbound orders')}
            value={ob?.inbound.open ?? 0}
            state={inboundState}
            context={ob ? `${ob.inbound.expectedToday} ${t('expectedToday', 'expected today')} · ${t('projFinish', 'finish ~')}${timeHm(ob.inbound.projectedFinishIso)}` : undefined}
            spark={(ob?.perHour ?? []).map((h) => h.inbound)}
          />
          <SecondaryRow
            items={[
              { label: t('husReceived', 'HUs received'), value: inv.data?.husReceivedToday ?? 0 },
              { label: t('receiveErrors', 'Receive errors'), value: inboundErrors },
            ]}
          />
        </Tile>

        {/* Outbound */}
        <Tile title={t('tileOutbound', 'Outbound')} to="/dashboards/outbound" drillLabel={t('drillOutbound', 'Outbound dashboard')} stale={orders.stale} updatedAt={orders.lastUpdated}>
          <Hero
            big
            label={t('openOutbound', 'Open outbound orders')}
            value={ob?.outbound.open ?? 0}
            state={outboundState}
            context={ob ? `${ob.outbound.releasedToday} ${t('releasedToday', 'released today')} · ${t('projFinish', 'finish ~')}${timeHm(ob.outbound.projectedFinishIso)}` : undefined}
            spark={(ob?.perHour ?? []).map((h) => h.outbound)}
          />
          <SecondaryRow
            items={[
              { label: t('linesPicked', 'Lines picked'), value: ob?.outbound.linesPickedToday ?? 0 },
              { label: t('shorts', 'Shorts'), value: ob?.outbound.shortsToday ?? 0, alert: (ob?.outbound.shortsToday ?? 0) > 0 },
            ]}
          />
        </Tile>

        {/* Dispatch — render whatever the endpoint returns; honest empty state when thin */}
        <Tile title={t('tileDispatch', 'Dispatch')} to="/dashboards/outbound" drillLabel={t('drillOutbound', 'Outbound dashboard')} stale={dispatch.stale} updatedAt={dispatch.lastUpdated}>
          {!dp || (!dp.nextDeparture && (dp.routes ?? []).length === 0) ? (
            <p className="muted" style={{ fontSize: '.85rem', margin: '.6rem 0' }}>
              {t('noDispatch', 'No dispatch routes scheduled. Routes appear here once outbound orders are assigned to departures.')}
            </p>
          ) : (
            <>
              {dp.nextDeparture ? (
                <Hero
                  big
                  label={`${t('nextDeparture', 'Next departure')} · ${dp.nextDeparture.routeCode}`}
                  value={countdown(dp.nextDeparture.secondsToCutoff)}
                  state={dispatchState}
                  context={`${t('cutoff', 'cutoff')} ${timeHm(dp.nextDeparture.cutoffIso)}`}
                />
              ) : (
                <Hero big label={t('routesToday', 'Routes today')} value={dp.routes.length} state={dispatchState} />
              )}
              <div style={{ marginTop: '.6rem' }}>
                <WorstFirstStrip
                  entries={[...dp.routes]
                    .sort((a, b) => a.ordersReady / Math.max(1, a.ordersAssigned) - b.ordersReady / Math.max(1, b.ordersAssigned))
                    .slice(0, 5)
                    .map((r) => ({
                      label: `${r.routeCode} (${r.status.toLowerCase()})`,
                      value: r.ordersAssigned > 0 ? (r.ordersReady / r.ordersAssigned) * 100 : 100,
                      display: `${r.ordersReady}/${r.ordersAssigned}`,
                      breach: r.status !== 'DEPARTED' && r.ordersReady < r.ordersAssigned,
                    }))}
                  max={100}
                />
              </div>
            </>
          )}
        </Tile>

        {/* Automation — drills into existing Reporting; hero is alert count by severity */}
        <Tile
          title={t('tileAutomation', 'Automation')}
          to="/reporting/material-flow"
          drillLabel={t('drillReporting', 'Reporting')}
          stale={automation.stale && alerts.stale}
          updatedAt={automation.lastUpdated}
          header={<AlertBadge counts={alertCounts} />}
        >
          <Hero
            big
            label={t('activeAlerts', 'Active alerts')}
            value={alertCounts.critical + alertCounts.warning + alertCounts.info}
            state={automationState}
            context={au ? `${pct1(au.scanNoReadPctToday)}% ${t('scanNoRead', 'scan no-read')} (≤ ${T.scanNoReadPct}%) · ${pct1(au.equipmentAvailabilityPct)}% ${t('available', 'available')}` : undefined}
          />
          <SecondaryRow
            items={[
              { label: t('equipment', 'Equipment'), value: au?.equipmentTotal ?? 0 },
              { label: t('inFault', 'In fault'), value: au?.equipmentInFault ?? 0, alert: (au?.equipmentInFault ?? 0) > 0 },
            ]}
          />
        </Tile>

        {/* Putaway backlog mini-tile shares the inbound feel but pulls from inventory */}
        <Tile title={t('tilePutaway', 'Putaway backlog')} to="/dashboards/inbound" drillLabel={t('drillInbound', 'Inbound dashboard')} stale={inv.stale} updatedAt={inv.lastUpdated}>
          <Hero
            big
            label={t('putawayBacklog', 'HUs awaiting putaway')}
            value={inv.data?.putawayBacklog.count ?? 0}
            state={!inv.data ? 'ok' : inv.data.putawayBacklog.oldestAgeMin > T.putawayBacklogAgeMin * 2 ? 'critical' : inv.data.putawayBacklog.oldestAgeMin > T.putawayBacklogAgeMin ? 'warning' : inv.data.putawayBacklog.count > 0 ? 'warning' : 'ok'}
            context={inv.data ? `${t('oldest', 'oldest')} ${minsLabel(inv.data.putawayBacklog.oldestAgeMin)}` : undefined}
          />
        </Tile>
      </div>
    </div>
  )
}

function SecondaryRow({ items }: { items: { label: string; value: number | string; alert?: boolean }[] }) {
  return (
    <div style={{ display: 'flex', gap: '1.2rem', marginTop: '.6rem' }}>
      {items.map((it, i) => (
        <div key={i} style={{ display: 'flex', flexDirection: 'column' }}>
          <span style={{ fontSize: '1.1rem', fontWeight: 600, color: it.alert ? 'var(--warning)' : 'var(--text)' }}>
            {it.alert && <span aria-hidden="true" style={{ fontSize: '.7rem', marginRight: 4 }}>▲</span>}
            {typeof it.value === 'number' ? it.value.toLocaleString() : it.value}
          </span>
          <span className="muted" style={{ fontSize: '.62rem', textTransform: 'uppercase', letterSpacing: '.08em', fontFamily: 'var(--font-mono)' }}>
            {it.label}
          </span>
        </div>
      ))}
    </div>
  )
}
