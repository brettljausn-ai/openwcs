import { useNavigate } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import { SECTION_ORDER } from './auth/screens'
import { useT } from './i18n/useT'

export default function Dashboard() {
  const t = useT('dashboard')
  const { myScreens, session } = useAuth()
  const navigate = useNavigate()
  const screens = myScreens().filter((s) => s.key !== 'dashboard')

  return (
    <div className="app-content">
      <div className="page-head">
        <div className="eyebrow">{t('welcomeBack', 'Welcome back')}</div>
        <h1>{t('hello', 'Hello, {name}').replace('{name}', session?.name?.split(' ')[0] || t('there', 'there'))}</h1>
        <p>{t('intro', 'Your warehouse control system, jump into any area you have access to.')}</p>
      </div>

      {screens.some((s) => !s.section) && (
        <section style={{ marginBottom: '2rem' }}>
          <div className="dash-grid">
            {screens.filter((s) => !s.section).map((s) => (
              <div key={s.key} className="glass dash-card" onClick={() => navigate(s.path)}
                   role="button" tabIndex={0}
                   onKeyDown={(e) => { if (e.key === 'Enter') navigate(s.path) }}>
                <div className="dash-ico" aria-hidden="true">{s.icon}</div>
                <h3>{s.label}</h3>
                <p>{s.description}</p>
              </div>
            ))}
          </div>
        </section>
      )}

      {SECTION_ORDER.map((section) => {
        const items = screens.filter((s) => s.section === section)
        if (items.length === 0) return null
        return (
          <section key={section} style={{ marginBottom: '2rem' }}>
            <div className="sidebar-section" style={{ padding: '0 0 .75rem' }}>{section}</div>
            <div className="dash-grid">
              {items.map((s) => (
                <div key={s.key} className="glass dash-card" onClick={() => navigate(s.path)}
                     role="button" tabIndex={0}
                     onKeyDown={(e) => { if (e.key === 'Enter') navigate(s.path) }}>
                  <div className="dash-ico" aria-hidden="true">{s.icon}</div>
                  <div className="feature-tag">{section}</div>
                  <h3>{s.label}</h3>
                  <p>{s.description}</p>
                </div>
              ))}
            </div>
          </section>
        )
      })}
    </div>
  )
}
