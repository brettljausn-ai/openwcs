import { useEffect, useState } from 'react'
import { Link, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import WarehouseSwitcher from '../warehouse/WarehouseSwitcher'
import { useT } from '../i18n/useT'

// Minimal full-screen shell for the installed-PWA handheld operator. NO sidebar: a slim top bar
// (compact warehouse switcher, the signed-in user, an online/offline dot, logout) plus the routed
// process content. When a process (not the menu) is showing, a prominent "← Menu" back button lets
// the operator leave it. Large touch targets, high contrast, fits a small handheld viewport.
//
// Rules of Hooks: every hook runs unconditionally at the top — there is no early return in this
// component (see the #310 hook-after-early-return crash).
export default function OperatorShell() {
  const { session, logout, roles } = useAuth()
  const tc = useT('common')
  const to = useT('operator')
  const { pathname } = useLocation()

  const [online, setOnline] = useState<boolean>(() =>
    typeof navigator !== 'undefined' ? navigator.onLine : true,
  )
  useEffect(() => {
    const up = () => setOnline(true)
    const down = () => setOnline(false)
    window.addEventListener('online', up)
    window.addEventListener('offline', down)
    return () => {
      window.removeEventListener('online', up)
      window.removeEventListener('offline', down)
    }
  }, [])

  // The menu is the index ("/"); anything else is a process and gets the back button.
  const onMenu = pathname === '/' || pathname === ''

  return (
    <div className="op-shell">
      <header className="op-topbar">
        <div className="op-topbar-left">
          {onMenu ? (
            <span className="op-brand">open<span className="accent">WCS</span></span>
          ) : (
            <Link to="/" className="op-back">← {to('menu', 'Menu')}</Link>
          )}
        </div>
        <div className="op-topbar-right">
          <WarehouseSwitcher />
          <span
            className={`op-net${online ? ' is-online' : ' is-offline'}`}
            title={online ? tc('online', 'Online') : tc('offline', 'Offline')}
            aria-label={online ? tc('online', 'Online') : tc('offline', 'Offline')}
          >
            <span className="op-net-dot" aria-hidden="true" />
          </span>
          <span className="op-user" title={session?.name}>
            <span className="op-user-name">{session?.name}</span>
            <span className="op-user-role">{roles[0] || 'user'}</span>
          </span>
          <button type="button" className="op-logout" onClick={logout}>
            {tc('signOut', 'Sign out')}
          </button>
        </div>
      </header>
      <main className="op-body">
        <Outlet />
      </main>
    </div>
  )
}
