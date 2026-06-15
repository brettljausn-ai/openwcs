import { useEffect, useRef, useState } from 'react'

/**
 * Polling hook for the dashboards. Calls `load` immediately and then every `intervalMs`, scoped to
 * `warehouseId` (re-fetches when it changes). It tracks a last-updated stamp and a "stale" flag that
 * flips on after two consecutive failed polls — the screens grey a tile out and say "stale" rather
 * than silently showing old numbers. The setInterval-in-useEffect pattern mirrors TransportScreen.
 */
export interface PollState<T> {
  data: T | null
  error: string | null
  loading: boolean
  /** True once two consecutive polls have failed (and we therefore distrust the last-seen data). */
  stale: boolean
  lastUpdated: Date | null
}

export function usePoll<T>(
  warehouseId: string,
  load: (warehouseId: string) => Promise<T>,
  intervalMs: number,
): PollState<T> {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [stale, setStale] = useState(false)
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)
  const fails = useRef(0)
  // Keep the latest loader without restarting the interval when an inline arrow is passed.
  const loadRef = useRef(load)
  loadRef.current = load

  useEffect(() => {
    if (!warehouseId) {
      setData(null)
      setLastUpdated(null)
      setStale(false)
      return
    }
    let cancelled = false
    fails.current = 0
    setStale(false)
    setData(null)
    setLastUpdated(null)

    const tick = (first: boolean) => {
      if (first) setLoading(true)
      loadRef
        .current(warehouseId)
        .then((r) => {
          if (cancelled) return
          fails.current = 0
          setData(r)
          setError(null)
          setStale(false)
          setLastUpdated(new Date())
        })
        .catch((e) => {
          if (cancelled) return
          fails.current += 1
          setError(e instanceof Error ? e.message : String(e))
          if (fails.current >= 2) setStale(true)
        })
        .finally(() => {
          if (!cancelled && first) setLoading(false)
        })
    }

    tick(true)
    const id = window.setInterval(() => tick(false), intervalMs)
    return () => {
      cancelled = true
      window.clearInterval(id)
    }
  }, [warehouseId, intervalMs])

  return { data, error, loading, stale, lastUpdated }
}
