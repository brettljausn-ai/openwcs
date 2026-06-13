import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { useLocation } from 'react-router-dom'
import { SCREENS } from '../auth/screens'
import { useT } from '../i18n/useT'
import { HELP, ScreenHelp } from './content'

// Resolve the help key + drawer title for the current path: a longest-prefix match on the screen
// catalog ('/' = dashboard). The master-data catalog screens carry keys like 'master-data:warehouses'
// that line up with the help content keys.
function resolveHelp(pathname: string): { key: string; label: string } | null {
  if (pathname === '/') return { key: 'dashboard', label: 'Dashboard' }
  const match = SCREENS
    .filter((s) => s.path !== '/' && pathname.startsWith(s.path))
    .sort((a, b) => b.path.length - a.path.length)[0]
  return match ? { key: match.key, label: match.label } : null
}

// A "?" button in the app top bar that opens a guidance drawer for the current screen. Renders
// nothing when the current screen has no help entry.
export default function HelpButton() {
  const t = useT('help')
  const { pathname } = useLocation()
  const resolved = resolveHelp(pathname)
  // The specific entry, falling back to the generic master-data help for any sub-page lacking one.
  const helpKey = resolved
    ? HELP[resolved.key]
      ? resolved.key
      : resolved.key.startsWith('master-data:')
        ? 'master-data'
        : null
    : null
  const help = helpKey ? HELP[helpKey] : null
  const label = resolved?.label ?? t('help', 'Help')
  const [open, setOpen] = useState(false)

  // Close the drawer when navigating to another screen.
  useEffect(() => setOpen(false), [pathname])

  if (!help || !helpKey) return null

  return (
    <>
      <button
        type="button"
        className="help-btn"
        aria-label={t('helpFor', 'Help for {screen}').replace('{screen}', label)}
        title={`${t('help', 'Help')} — ${label}`}
        onClick={() => setOpen(true)}
      >
        ?
      </button>
      {open && createPortal(
        <HelpDrawer title={label} helpKey={helpKey} help={help} onClose={() => setOpen(false)} />,
        document.body,
      )}
    </>
  )
}

function HelpDrawer({ title, helpKey, help, onClose }: { title: string; helpKey: string; help: ScreenHelp; onClose: () => void }) {
  const t = useT('help')
  // Close on Escape.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="help-overlay" onMouseDown={onClose}>
      <aside className="help-drawer" role="dialog" aria-label={`${t('help', 'Help')}: ${title}`} onMouseDown={(e) => e.stopPropagation()}>
        <div className="help-drawer-head">
          <div>
            <div className="eyebrow">{t('help', 'Help')}</div>
            <h2 className="help-drawer-title">{title}</h2>
          </div>
          <button type="button" className="help-close" aria-label={t('closeHelp', 'Close help')} onClick={onClose}>×</button>
        </div>
        <div className="help-drawer-body">
          <p className="help-summary">{t(`${helpKey}.summary`, help.summary)}</p>
          {help.sections.map((s, i) => (
            <section key={i} className="help-section">
              <h3>{t(`${helpKey}.s${i}.heading`, s.heading)}</h3>
              <p>{t(`${helpKey}.s${i}.body`, s.body)}</p>
            </section>
          ))}
          {help.tips && help.tips.length > 0 && (
            <section className="help-section">
              <h3>{t('tips', 'Tips')}</h3>
              <ul className="help-tips">
                {help.tips.map((tip, i) => <li key={i}>{t(`${helpKey}.tip${i}`, tip)}</li>)}
              </ul>
            </section>
          )}
        </div>
      </aside>
    </div>
  )
}
