// Handheld / PWA operator-mode detection.
//
// When openWCS runs as an installed PWA on a warehouse handheld (a rugged Android scanner, an iPad
// kiosk, etc.) the operator should see the stripped operator shell — a big-tile process menu — not
// the full desktop app (sidebar + dashboards + master data). We detect "installed PWA" via the
// standalone display mode (and iOS's legacy navigator.standalone), and allow a manual override
// (?handheld=1 / ?desktop=1, persisted to localStorage) so the operator shell can be previewed in
// a normal browser without installing anything.

import { useEffect, useState } from 'react'

const OVERRIDE_KEY = 'openwcs.handheld' // localStorage: '1' = force handheld, '0' = force desktop

type Override = '1' | '0' | null

/** Read (and persist, when a query param is present) the manual override. */
function readOverride(): Override {
  try {
    const params = new URLSearchParams(window.location.search)
    if (params.has('handheld')) {
      const on = params.get('handheld') !== '0'
      localStorage.setItem(OVERRIDE_KEY, on ? '1' : '0')
      return on ? '1' : '0'
    }
    if (params.has('desktop')) {
      const on = params.get('desktop') !== '0'
      localStorage.setItem(OVERRIDE_KEY, on ? '0' : '1')
      return on ? '0' : '1'
    }
    const stored = localStorage.getItem(OVERRIDE_KEY)
    return stored === '1' || stored === '0' ? stored : null
  } catch {
    return null
  }
}

/** True when the app is running as an installed PWA (standalone display mode / iOS home-screen). */
function isStandalone(): boolean {
  try {
    if (typeof window === 'undefined') return false
    if (window.matchMedia?.('(display-mode: standalone)').matches) return true
    // iOS Safari home-screen apps report this non-standard flag instead of the media query.
    if ((window.navigator as Navigator & { standalone?: boolean }).standalone) return true
  } catch {
    /* matchMedia unavailable */
  }
  return false
}

/** Resolve handheld mode: a manual override wins; otherwise fall back to standalone detection. */
export function isHandheldMode(): boolean {
  const override = readOverride()
  if (override === '1') return true
  if (override === '0') return false
  return isStandalone()
}

/**
 * Reactive handheld-mode flag. Re-evaluates when the display-mode media query changes (e.g. the user
 * installs the PWA, or the OS transitions the window into/out of standalone). The override is read
 * once on mount (it's set from the URL on load, then sticky), so this stays cheap.
 */
export function useHandheldMode(): boolean {
  const [handheld, setHandheld] = useState<boolean>(() => isHandheldMode())

  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return
    const mq = window.matchMedia('(display-mode: standalone)')
    const onChange = () => setHandheld(isHandheldMode())
    // addEventListener is the modern API; addListener is the Safari/legacy fallback.
    if (mq.addEventListener) mq.addEventListener('change', onChange)
    else mq.addListener(onChange)
    return () => {
      if (mq.removeEventListener) mq.removeEventListener('change', onChange)
      else mq.removeListener(onChange)
    }
  }, [])

  return handheld
}
