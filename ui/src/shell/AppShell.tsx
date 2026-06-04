import { useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { SCREENS, SECTION_ORDER, ScreenDef, Section } from '../auth/screens'
import WarehouseSwitcher from '../warehouse/WarehouseSwitcher'
import HelpButton from '../help/HelpButton'

function initials(name: string): string {
  const parts = name.trim().split(/\s+/)
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
  return name.slice(0, 2).toUpperCase()
}

export default function AppShell() {
  const { can, session, logout, roles } = useAuth()
  const { pathname } = useLocation()

  const dashboard = SCREENS.find((s) => s.key === 'dashboard')!
  const bySection = SECTION_ORDER.map((section) => ({
    section,
    items: SCREENS.filter((s) => s.section === section && can(s)),
  })).filter((g) => g.items.length > 0)

  // Which section holds the screen we're currently on (so we can reveal it).
  const activeSection = SCREENS.find(
    (s) => s.section && (s.path === pathname || (s.path !== '/' && pathname.startsWith(s.path))),
  )?.section

  // Categories are folded by default; only the active section starts open.
  const [open, setOpen] = useState<Section[]>(activeSection ? [activeSection] : [])
  const toggle = (section: Section) =>
    setOpen((o) => (o.includes(section) ? o.filter((x) => x !== section) : [...o, section]))

  // Keep the section containing the current screen revealed on navigation.
  useEffect(() => {
    if (activeSection) setOpen((o) => (o.includes(activeSection) ? o : [...o, activeSection]))
  }, [activeSection])

  const link = (s: ScreenDef) => (
    <NavLink key={s.key} to={s.path} end={s.path === '/'} className={({ isActive }) => (isActive ? 'active' : '')}>
      <span className="nav-ico" aria-hidden="true">{s.icon}</span>
      {s.label}
    </NavLink>
  )

  // An item with children renders a second menu level (e.g. Master data → its catalogs),
  // expanded while the user is on one of its sub-pages.
  const renderItem = (s: ScreenDef) => {
    if (!s.children?.length) return link(s)
    const expanded = pathname === s.path || pathname.startsWith(`${s.path}/`)
    return (
      <div key={s.key} className="sidebar-item-group">
        <NavLink to={s.children[0].path} className={`sidebar-parent${expanded ? ' active' : ''}`}>
          <span className="nav-ico" aria-hidden="true">{s.icon}</span>
          {s.label}
          <span className="sidebar-subchev" aria-hidden="true">{expanded ? '▾' : '▸'}</span>
        </NavLink>
        {expanded && (
          <div className="sidebar-subnav">
            {s.children.map((c) => (
              <NavLink
                key={c.path}
                to={c.path}
                className={({ isActive }) => `sidebar-subitem${isActive ? ' active' : ''}`}
              >
                {c.label}
              </NavLink>
            ))}
          </div>
        )}
      </div>
    )
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="sidebar-brand">
          <img src="/Logo_white_solo.png" alt="" />
          <span className="sidebar-wordmark">open<span className="accent">WCS</span></span>
        </div>

        <nav className="sidebar-nav">
          {can(dashboard) && link(dashboard)}
          {bySection.map((g) => {
            const isOpen = open.includes(g.section)
            return (
              <div key={g.section} className="sidebar-group">
                <button
                  type="button"
                  className={`sidebar-section${isOpen ? ' is-open' : ''}`}
                  aria-expanded={isOpen}
                  onClick={() => toggle(g.section)}
                >
                  <span className="sidebar-section-chev" aria-hidden="true">▸</span>
                  {g.section}
                </button>
                {isOpen && g.items.map(renderItem)}
              </div>
            )
          })}
        </nav>

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
        <div className="app-topbar">
          <HelpButton />
          <WarehouseSwitcher />
        </div>
        <div className="app-body">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
