import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { SCREENS, ScreenDef } from '../auth/screens'
import { useT } from '../i18n/useT'

// Handheld operator menu — a big-tile grid of the operator processes the signed-in user may access.
// Each tile is a large tappable card (icon + label). Coming-soon processes are dimmed and carry a
// small badge so operators know not to expect a working flow. Tapping routes to the process; the
// OperatorShell renders the process with a "← Menu" back button.
export default function OperatorMenu() {
  const { can } = useAuth()
  const tn = useT('nav')
  const to = useT('operator')

  // Only operator processes, in catalog order, that the user can access.
  const processes: ScreenDef[] = SCREENS.filter((s) => s.process && can(s))

  return (
    <div className="op-menu">
      <h1 className="op-menu-title">{to('menuTitle', 'Select a process')}</h1>
      {processes.length === 0 ? (
        <p className="op-menu-empty">{to('noProcesses', 'No processes available for your account.')}</p>
      ) : (
        <div className="op-tiles">
          {processes.map((s) => (
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
        </div>
      )}
    </div>
  )
}
