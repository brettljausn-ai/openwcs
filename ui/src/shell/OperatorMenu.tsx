import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { SCREENS, ScreenDef } from '../auth/screens'
import { useT } from '../i18n/useT'
import { useProcesses } from '../process-designer/useProcesses'

// Handheld operator menu — a big-tile grid of the operator processes the signed-in user may access.
// Two sources, both as big tappable cards (icon + label):
//   1. Static, hand-coded processes from the screen catalog (Picking, Stock Check, …); coming-soon
//      ones are dimmed with a badge.
//   2. The ACTIVE configurable processes from GET /api/process-designer/processes — each launches the
//      client-driven runtime at /process/:key. These render only when the backend answers; offline /
//      not-deployed degrades silently to just the static tiles.
// A static catalog process whose key matches a configurable process is shown once (the configurable
// one wins, so a published Goods In replaces the dimmed coming-soon tile).
//
// Rules of Hooks: all hooks run unconditionally at the top before any branch.
export default function OperatorMenu() {
  const { can } = useAuth()
  const tn = useT('nav')
  const to = useT('operator')
  const { processes: configurable } = useProcesses()

  // Static operator processes, in catalog order, the user can access.
  const staticProcesses: ScreenDef[] = SCREENS.filter((s) => s.process && can(s))
  const configurableKeys = new Set(configurable.map((p) => p.processKey))
  // Hide a static tile when a configurable process with the same key is active (the live one wins).
  const visibleStatic = staticProcesses.filter((s) => !configurableKeys.has(s.key))

  const total = visibleStatic.length + configurable.length

  return (
    <div className="op-menu">
      <h1 className="op-menu-title">{to('menuTitle', 'Select a process')}</h1>
      {total === 0 ? (
        <p className="op-menu-empty">{to('noProcesses', 'No processes available for your account.')}</p>
      ) : (
        <div className="op-tiles">
          {visibleStatic.map((s) => (
            <Link
              key={s.key}
              to={s.path}
              className={`op-tile${s.comingSoon ? ' is-soon' : ''}`}
              aria-disabled={s.comingSoon || undefined}
            >
              <span className="op-tile-icon" aria-hidden="true">{s.icon}</span>
              <span className="op-tile-label">{tn(s.key, s.label)}</span>
              {s.comingSoon && <span className="op-tile-badge">{to('comingSoon', 'Coming soon')}</span>}
            </Link>
          ))}
          {configurable.map((p) => (
            <Link key={`cfg:${p.processKey}`} to={`/process/${p.processKey}`} className="op-tile">
              <span className="op-tile-icon" aria-hidden="true">{p.icon || '⚑'}</span>
              <span className="op-tile-label">{p.title}</span>
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}
