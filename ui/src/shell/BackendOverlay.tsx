// App-wide "reconnecting" screen. While the backend (gateway) is unreachable — typically a restart /
// redeploy, surfaced as 502/503/504 or a thrown fetch by authFetch.ts — this overlays the whole UI
// with a calm waiting screen instead of letting every panel flash its own error. It clears itself the
// moment the gateway answers again: the interceptor flips the flag back on the next good response, and
// while down this component also actively probes so recovery doesn't depend on a screen still polling.

import { useEffect, useState } from 'react'
import { isBackendDown, setBackendDown, subscribeBackend } from '../lib/backendStatus'

const PROBE_MS = 2500

export default function BackendOverlay() {
  const [down, setDown] = useState(isBackendDown())

  useEffect(() => subscribeBackend(setDown), [])

  // While down, poll a lightweight API path so we recover even if no screen is actively polling. Any
  // response that isn't a gateway error means the backend is reachable again (even a 401/403). The
  // interceptor also flips the flag on success, so this is belt-and-suspenders.
  useEffect(() => {
    if (!down) return
    let cancelled = false
    const probe = async () => {
      try {
        const res = await fetch('/api/system/services', { method: 'GET', cache: 'no-store' })
        if (!cancelled && res.status !== 502 && res.status !== 503 && res.status !== 504) {
          setBackendDown(false)
        }
      } catch {
        /* still unreachable — keep waiting */
      }
    }
    const t = window.setInterval(probe, PROBE_MS)
    return () => {
      cancelled = true
      window.clearInterval(t)
    }
  }, [down])

  if (!down) return null

  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 9999,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: '1.4rem',
        textAlign: 'center',
        padding: '2rem',
        background: 'rgba(6, 24, 18, 0.92)',
        backdropFilter: 'blur(8px)',
        color: 'var(--text, #f7f9f8)',
      }}
    >
      <style>{`@keyframes owcs-spin { to { transform: rotate(360deg); } }`}</style>
      <div
        style={{
          width: 54,
          height: 54,
          borderRadius: '50%',
          border: '3px solid rgba(141, 198, 63, 0.2)',
          borderTopColor: 'var(--herbal-lime, #8DC63F)',
          animation: 'owcs-spin 0.9s linear infinite',
        }}
      />
      <div>
        <h2 style={{ margin: '0 0 .4rem', fontSize: '1.5rem' }}>Reconnecting…</h2>
        <p style={{ margin: 0, maxWidth: 420, color: 'var(--text-dim, rgba(247,249,248,.62))', fontSize: '.95rem' }}>
          openWCS is temporarily unavailable — the server may be restarting or deploying. This screen
          will clear automatically the moment it&rsquo;s back.
        </p>
      </div>
    </div>
  )
}
