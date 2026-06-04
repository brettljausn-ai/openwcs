import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { useLocation } from 'react-router-dom'
import { SCREENS } from '../auth/screens'
import { HELP, ScreenHelp } from './content'

// Resolve the screen the user is on from the current path (longest-prefix match; '/' = dashboard).
function currentScreenKey(pathname: string): string | null {
  if (pathname === '/') return 'dashboard'
  const match = SCREENS
    .filter((s) => s.path !== '/' && pathname.startsWith(s.path))
    .sort((a, b) => b.path.length - a.path.length)[0]
  return match?.key ?? null
}

// A "?" button in the app top bar that opens a guidance drawer for the current screen. Renders
// nothing when the current screen has no help entry.
export default function HelpButton() {
  const { pathname } = useLocation()
  const key = currentScreenKey(pathname)
  const help = key ? HELP[key] : null
  const label = SCREENS.find((s) => s.key === key)?.label ?? 'Help'
  const [open, setOpen] = useState(false)

  // Close the drawer when navigating to another screen.
  useEffect(() => setOpen(false), [pathname])

  if (!help) return null

  return (
    <>
      <button
        type="button"
        className="help-btn"
        aria-label={`Help for ${label}`}
        title={`Help — ${label}`}
        onClick={() => setOpen(true)}
      >
        ?
      </button>
      {open && createPortal(
        <HelpDrawer title={label} help={help} onClose={() => setOpen(false)} />,
        document.body,
      )}
    </>
  )
}

function HelpDrawer({ title, help, onClose }: { title: string; help: ScreenHelp; onClose: () => void }) {
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
      <aside className="help-drawer" role="dialog" aria-label={`Help: ${title}`} onMouseDown={(e) => e.stopPropagation()}>
        <div className="help-drawer-head">
          <div>
            <div className="eyebrow">Help</div>
            <h2 className="help-drawer-title">{title}</h2>
          </div>
          <button type="button" className="help-close" aria-label="Close help" onClick={onClose}>×</button>
        </div>
        <div className="help-drawer-body">
          <p className="help-summary">{help.summary}</p>
          {help.sections.map((s, i) => (
            <section key={i} className="help-section">
              <h3>{s.heading}</h3>
              <p>{s.body}</p>
            </section>
          ))}
          {help.tips && help.tips.length > 0 && (
            <section className="help-section">
              <h3>Tips</h3>
              <ul className="help-tips">
                {help.tips.map((t, i) => <li key={i}>{t}</li>)}
              </ul>
            </section>
          )}
        </div>
      </aside>
    </div>
  )
}
