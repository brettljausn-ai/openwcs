// Andon board (dashboards:andon). A full-screen control-room alert board for wall displays: big,
// high-contrast, readable from across the room. Still grey-base — colour is reserved for severity and
// is ALWAYS paired with an icon/shape (never hue alone, ≈8% of males are colour-blind). Auto-polls the
// notification alert stream every ~12 s. Critical alerts render large and first, then warnings; each
// tile shows area / metric / value-vs-threshold / age. When nothing is active it shows a calm "all
// clear" state. Read-only, no actions (operators act on the operational screens). No tables.
import { useMemo } from 'react'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useT } from '../i18n/useT'
import { usePoll } from './usePoll'
import { LastUpdated, severityState, stateColor, stateIcon, type DashState } from './components'
import { NoWarehouse } from './DashboardPage'
import { minsLabel } from './format'
import { loadAlerts, type AlertDto } from './api'

const POLL = 12_000

function ageMin(iso: string): number {
  const ms = Date.now() - new Date(iso).getTime()
  return Number.isFinite(ms) && ms > 0 ? Math.floor(ms / 60000) : 0
}

export default function AndonBoard() {
  const t = useT('dashboards')
  const { currentWarehouseId: wh } = useWarehouse()
  const alerts = usePoll(wh, loadAlerts, POLL)

  const sorted = useMemo(() => {
    const list = alerts.data ?? []
    const rank = (a: AlertDto) => {
      const st = severityState(a.severity)
      return st === 'critical' ? 0 : st === 'warning' ? 1 : 2
    }
    // Worst-first, then oldest-first within a severity (longest-running needs attention soonest).
    return [...list].sort((a, b) => rank(a) - rank(b) || ageMin(b.sinceIso) - ageMin(a.sinceIso))
  }, [alerts.data])

  if (!wh) return <NoWarehouse />

  const criticals = sorted.filter((a) => severityState(a.severity) === 'critical')
  const warnings = sorted.filter((a) => severityState(a.severity) === 'warning')
  const others = sorted.filter((a) => severityState(a.severity) === 'ok')
  const allClear = sorted.length === 0

  return (
    <div className="app-content" style={{ display: 'flex', flexDirection: 'column', gap: '1.2rem' }}>
      <div className="page-head" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', gap: '1rem' }}>
        <div>
          <span className="eyebrow">{t('eyebrow', 'Dashboards')}</span>
          <h1>{t('andonTitle', 'Andon board')}</h1>
          <p>{t('andonIntro', 'Live control-room alert board for wall displays. Critical first, then warnings; colour and shape mark severity. A quiet, grey board means a healthy floor.')}</p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: '.4rem', fontSize: '1.1rem', color: criticals.length ? stateColor('critical') : 'var(--text-dim)' }}>
            <span aria-hidden="true">{stateIcon('critical')}</span> {criticals.length}
          </span>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: '.4rem', fontSize: '1.1rem', color: warnings.length ? stateColor('warning') : 'var(--text-dim)' }}>
            <span aria-hidden="true">{stateIcon('warning')}</span> {warnings.length}
          </span>
          <LastUpdated at={alerts.lastUpdated} stale={alerts.stale} />
        </div>
      </div>

      {allClear ? (
        <div
          className="glass"
          style={{ flex: 1, minHeight: '40vh', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: '1rem', textAlign: 'center', padding: '3rem' }}
        >
          <span aria-hidden="true" style={{ fontSize: '4rem', color: 'var(--text-dim)' }}>{stateIcon('ok')}</span>
          <h2 style={{ margin: 0, fontSize: '2rem', color: 'var(--text)' }}>{t('andonAllClear', 'All clear')}</h2>
          <p className="muted" style={{ margin: 0, fontSize: '1.1rem' }}>{t('andonAllClearSub', 'No active alerts. A healthy floor looks quiet.')}</p>
        </div>
      ) : (
        <>
          {criticals.length > 0 && (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(420px, 1fr))', gap: '1rem' }}>
              {criticals.map((a) => (
                <AndonTile key={a.id} alert={a} state="critical" big t={t} />
              ))}
            </div>
          )}
          {warnings.length > 0 && (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: '.8rem' }}>
              {warnings.map((a) => (
                <AndonTile key={a.id} alert={a} state="warning" t={t} />
              ))}
            </div>
          )}
          {others.length > 0 && (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: '.8rem' }}>
              {others.map((a) => (
                <AndonTile key={a.id} alert={a} state="ok" t={t} />
              ))}
            </div>
          )}
        </>
      )}
    </div>
  )
}

function AndonTile({ alert, state, big, t }: { alert: AlertDto; state: DashState; big?: boolean; t: (k: string, e: string) => string }) {
  const color = stateColor(state)
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: big ? '.7rem' : '.5rem',
        padding: big ? '1.6rem 1.8rem' : '1rem 1.2rem',
        borderRadius: 12,
        border: '1px solid var(--glass-border)',
        borderLeft: `${big ? 8 : 5}px solid ${color}`,
        background: state === 'ok' ? 'rgba(255,255,255,.02)' : `color-mix(in srgb, ${color} 10%, transparent)`,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: '.6rem' }}>
        <span aria-hidden="true" style={{ color, fontSize: big ? '1.8rem' : '1.1rem' }}>{stateIcon(state)}</span>
        <span style={{ fontSize: big ? '1.3rem' : '.95rem', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.04em', color: state === 'ok' ? 'var(--text)' : color }}>
          {alert.area}
        </span>
        <span className="muted" style={{ marginLeft: 'auto', fontSize: big ? '1rem' : '.8rem', fontFamily: 'var(--font-mono)' }}>
          {minsLabel(ageMin(alert.sinceIso))}
        </span>
      </div>
      <div style={{ fontSize: big ? '1.5rem' : '1.05rem', fontWeight: 600, color: 'var(--text)' }}>{alert.metric}</div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: '.5rem' }}>
        <span style={{ fontSize: big ? '3rem' : '1.8rem', fontWeight: 800, lineHeight: 1, color: state === 'ok' ? 'var(--text)' : color, fontFamily: 'var(--font-mono)' }}>
          {alert.value}
        </span>
        <span className="muted" style={{ fontSize: big ? '1.1rem' : '.85rem' }}>
          / {alert.threshold} {t('threshold', 'threshold')}
        </span>
      </div>
    </div>
  )
}
