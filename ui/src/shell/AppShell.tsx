import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { SCREENS, SECTION_ORDER, ScreenDef } from '../auth/screens'

function initials(name: string): string {
  const parts = name.trim().split(/\s+/)
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
  return name.slice(0, 2).toUpperCase()
}

export default function AppShell() {
  const { can, session, logout, roles } = useAuth()

  const dashboard = SCREENS.find((s) => s.key === 'dashboard')!
  const bySection = SECTION_ORDER.map((section) => ({
    section,
    items: SCREENS.filter((s) => s.section === section && can(s)),
  })).filter((g) => g.items.length > 0)

  const link = (s: ScreenDef) => (
    <NavLink key={s.key} to={s.path} end={s.path === '/'} className={({ isActive }) => (isActive ? 'active' : '')}>
      <span className="nav-ico" aria-hidden="true">{s.icon}</span>
      {s.label}
    </NavLink>
  )

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="sidebar-brand">
          <img src="/Logo_white_solo.png" alt="" />
          openWCS
        </div>

        <nav className="sidebar-nav">
          {can(dashboard) && link(dashboard)}
          {bySection.map((g) => (
            <div key={g.section}>
              <div className="sidebar-section">{g.section}</div>
              {g.items.map(link)}
            </div>
          ))}
        </nav>

        <div className="sidebar-spacer" />

        <div className="sidebar-user">
          <span className="avatar">{initials(session?.name || 'U')}</span>
          <div className="who">
            <div className="name">{session?.name}</div>
            <div className="role">{roles[0] || 'user'}</div>
          </div>
          <button className="btn btn-ghost btn-sm" onClick={logout} title="Sign out">Sign out</button>
        </div>
      </aside>

      <main className="app-main">
        <div className="app-topbar" />
        <div className="app-body">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
