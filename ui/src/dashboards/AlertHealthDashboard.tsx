// Alert-system-health view (dashboards:alert-health). Admin-leaning Level-2 view that keeps the
// ALERT LOAD itself actionable (ISA-18.2: measuring the alarm system is mandatory in process
// industries — here it is the backlog item promised in the dashboard spec). Reads the notification
// alert-health endpoint: heroes for active warning/critical counts, an opened-vs-cleared per-day
// chart (reusing the reporting StackedBars), and chattering + stale alerts as worst-first strips.
// No tables. Polls every 60 s.
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useT } from '../i18n/useT'
import { usePoll } from './usePoll'
import { Hero, WorstFirstStrip, type DashState } from './components'
import { ChartCard, ChartRow, HeroRow, NoWarehouse, PageHead } from './DashboardPage'
import { StackedBars, CHART_COLORS } from '../reporting/charts'
import { minsLabel } from './format'
import { loadAlertHealth } from './api'

const POLL = 60_000

export default function AlertHealthDashboard() {
  const t = useT('dashboards')
  const { currentWarehouseId: wh } = useWarehouse()
  const health = usePoll(wh, (id) => loadAlertHealth(id, 14), POLL)

  if (!wh) return <NoWarehouse />

  const h = health.data
  const crit = h?.activeBySeverity.critical ?? 0
  const warn = h?.activeBySeverity.warning ?? 0
  const critState: DashState = crit > 0 ? 'critical' : 'ok'
  const warnState: DashState = warn > 0 ? 'warning' : 'ok'

  const perDay = (h?.perDay ?? []).map((d) => ({ day: d.day, opened: d.opened, cleared: d.cleared }))
  const chattering = h?.chattering ?? []
  const stale = h?.stale ?? []

  return (
    <div className="app-content">
      <PageHead
        title={t('alertHealthTitle', 'Alert-system health')}
        intro={t('alertHealthIntro', 'How the alerting system itself is behaving. An alert is only useful if it needs a response, so we watch the load: active counts, the daily opened-vs-cleared balance, alerts that keep flapping (chattering) and alerts that have gone stale.')}
        updatedAt={health.lastUpdated}
        stale={health.stale}
      />

      <HeroRow>
        <Hero label={t('activeCritical', 'Active critical')} value={crit} state={critState} />
        <Hero label={t('activeWarning', 'Active warning')} value={warn} state={warnState} />
        <Hero label={t('chatteringCount', 'Chattering alerts')} value={chattering.length} state={chattering.length > 0 ? 'warning' : 'ok'} context={t('chatteringHint', 'flapping open/clear')} />
        <Hero label={t('staleCount', 'Stale alerts')} value={stale.length} state={stale.length > 0 ? 'warning' : 'ok'} context={t('staleHint', 'open a long time')} />
      </HeroRow>

      <ChartRow>
        <ChartCard title={t('openedCleared', 'Opened vs cleared per day')} subtitle={t('openedClearedSub', 'A healthy system clears about as many alerts as it opens; a growing gap means alert debt is building.')} minWidth={560}>
          {perDay.length === 0 ? (
            <p className="muted" style={{ padding: '1.4rem', textAlign: 'center' }}>{t('noAlertHistory', 'No alert history in this window yet.')}</p>
          ) : (
            <StackedBars
              data={perDay}
              xKey="day"
              series={[
                { key: 'opened', name: t('opened', 'Opened'), color: CHART_COLORS.amber },
                { key: 'cleared', name: t('cleared', 'Cleared'), color: CHART_COLORS.grey },
              ]}
              xTick={(d) => (typeof d === 'string' && d.length >= 10 ? d.slice(5, 10) : d)}
            />
          )}
        </ChartCard>
      </ChartRow>

      <ChartRow>
        <ChartCard title={t('chatteringTitle', 'Chattering alerts')} subtitle={t('chatteringSub', 'Alerts flapping between open and clear; the worst offenders waste attention and should be retuned.')} minWidth={340}>
          <div style={{ paddingTop: '.5rem' }}>
            <WorstFirstStrip
              entries={[...chattering]
                .sort((a, b) => b.flaps - a.flaps)
                .slice(0, 10)
                .map((c) => ({ label: `${c.area} · ${c.metric}`, value: c.flaps, display: `${c.flaps}×`, state: 'warning' as DashState }))}
            />
          </div>
        </ChartCard>
        <ChartCard title={t('staleTitle', 'Stale alerts')} subtitle={t('staleSub', 'Alerts open the longest without clearing; oldest first. Long-lived alerts usually mean a missing response or a mis-set threshold.')} minWidth={340}>
          <div style={{ paddingTop: '.5rem' }}>
            <WorstFirstStrip
              entries={[...stale]
                .sort((a, b) => b.ageMin - a.ageMin)
                .slice(0, 10)
                .map((s) => ({ label: `${s.area} · ${s.metric}`, value: s.ageMin, display: minsLabel(s.ageMin), state: 'warning' as DashState }))}
            />
          </div>
        </ChartCard>
      </ChartRow>
    </div>
  )
}
