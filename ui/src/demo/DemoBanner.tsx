// Slim, dismissable banner for the public live demo (VITE_DEMO builds only). States plainly that
// this is sample data that is never saved, points at the pick station, and offers a Restart that
// re-arms the 5 totes and reloads so the whole snapshot is fresh.
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { reset as resetPickEngine } from './pickEngine'

export default function DemoBanner() {
  const [dismissed, setDismissed] = useState(false)
  if (dismissed) return null

  function restart() {
    resetPickEngine()
    // A reload rebuilds every fixture-backed screen from scratch and re-seeds the pick engine, so the
    // demo returns to its exact starting point (5 fresh totes, original data).
    window.location.reload()
  }

  return (
    <div
      role="status"
      style={{
        position: 'sticky',
        top: 0,
        zIndex: 60,
        display: 'flex',
        alignItems: 'center',
        gap: '.75rem',
        flexWrap: 'wrap',
        padding: '.5rem 1rem',
        background: 'rgba(141, 198, 63, .14)',
        borderBottom: '1px solid var(--herbal-lime, #8dc63f)',
        backdropFilter: 'blur(8px)',
        fontSize: '.88rem',
        color: 'var(--text, #e9f3df)',
      }}
    >
      <span style={{ fontSize: '1.05rem' }} aria-hidden="true">
        ◑
      </span>
      <span>
        <strong>Live demo</strong>, sample data, nothing is saved.
      </span>
      <Link
        to="/gtp"
        style={{ color: 'var(--herbal-lime, #8dc63f)', fontWeight: 600, textDecoration: 'none' }}
      >
        Try the pick station, 5 totes are arriving.
      </Link>
      <span style={{ marginLeft: 'auto', display: 'flex', gap: '.5rem', alignItems: 'center' }}>
        <button className="btn btn-ghost btn-sm" onClick={restart} title="Re-arm the 5 totes and reset all data">
          Restart demo
        </button>
        <button
          className="btn btn-ghost btn-sm"
          onClick={() => setDismissed(true)}
          aria-label="Dismiss demo banner"
          title="Dismiss"
        >
          ✕
        </button>
      </span>
    </div>
  )
}
