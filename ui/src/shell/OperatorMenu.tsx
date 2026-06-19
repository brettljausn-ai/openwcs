import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { SCREENS, ScreenDef } from '../auth/screens'
import { useT } from '../i18n/useT'
import { useProcesses } from '../process-designer/useProcesses'
import { listMyInstances } from '../process-designer/api'
import type { InstanceSummary } from '../process-designer/model'

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
  const { can, username } = useAuth()
  const tn = useT('nav')
  const to = useT('operator')
  const { processes: configurable } = useProcesses()
  const myWork = useMyRunningInstances(username)

  // Static operator processes, in catalog order, the user can access.
  const staticProcesses: ScreenDef[] = SCREENS.filter((s) => s.process && can(s))
  const configurableKeys = new Set(configurable.map((p) => p.processKey))
  // Hide a static tile when a configurable process with the same key is active (the live one wins).
  const visibleStatic = staticProcesses.filter((s) => !configurableKeys.has(s.key))

  const total = visibleStatic.length + configurable.length

  const processTitle = (key: string): string =>
    configurable.find((p) => p.processKey === key)?.title || key

  return (
    <div className="op-menu">
      {myWork.length > 0 && (
        <section className="op-menu-resume">
          <h1 className="op-menu-title">{to('resumeTitle', 'Resume in-progress work')}</h1>
          <p className="op-menu-resume-sub">
            {to('resumeSubtitle', 'Pick up where you left off, on this or any device.')}
          </p>
          <div className="op-tiles">
            {myWork.map((w) => (
              <Link
                key={w.instanceId}
                to={`/process/${w.processKey}?instance=${encodeURIComponent(w.instanceId)}`}
                className="op-tile op-tile-resume"
              >
                <span className="op-tile-icon" aria-hidden="true">⟳</span>
                <span className="op-tile-label">{processTitle(w.processKey)}</span>
                <span className="op-tile-sub">
                  {to('resumeStepPrefix', 'Step:')} {w.currentStep || '—'}
                </span>
              </Link>
            ))}
          </div>
        </section>
      )}

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

// The current operator's RUNNING configurable-process instances, for the "Resume in-progress work"
// launcher. Server-filtered on assignedTo + status (so a re-logged-in operator or a different device
// sees exactly their work). Degrades quietly to an empty list (offline / older server): the section
// simply does not render.
function useMyRunningInstances(username: string): InstanceSummary[] {
  const [rows, setRows] = useState<InstanceSummary[]>([])
  useEffect(() => {
    if (!username) return
    let cancelled = false
    listMyInstances(username)
      .then((data) => {
        if (!cancelled) setRows(data.filter((r) => r.status === 'RUNNING'))
      })
      .catch(() => {
        if (!cancelled) setRows([])
      })
    return () => {
      cancelled = true
    }
  }, [username])
  return rows
}
