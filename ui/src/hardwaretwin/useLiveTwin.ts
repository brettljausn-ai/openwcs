import { useCallback, useEffect, useRef, useState } from 'react'
import { loadAutomationTopology, type AutomationTopology } from '../topology/automationApi'
import { listDeviceTasks } from '../transport/api'
import { deriveTwin, type TwinSnapshot } from './twin'

// Live "digital twin" for the Hardware visualisation page. The geometry (automation topology) is
// loaded ONCE per warehouse; the live picture (equipment activity + moving totes) is re-derived on a
// poll of the flow-orchestrator device-task feed. No dedicated backend endpoint exists — see twin.ts.
//
// Polling follows the same shape used across the app (TransportScreen / GtpOpsScreen): a `cancelled`
// flag per effect, a try/catch that keeps the last good snapshot on a transient error, clearInterval
// on cleanup, and a `refreshRef` so the interval reads the freshest poll fn without being recreated
// on every render.

const DEFAULT_INTERVAL_MS = 3000
const TASK_WINDOW = 500 // wide window so totes/throughput aren't truncated by the newest-first cap

export interface UseLiveTwinOptions {
  intervalMs?: number
  autoRefresh?: boolean
}

export interface UseLiveTwinResult {
  topology: AutomationTopology | null
  snapshot: TwinSnapshot | null
  loading: boolean
  error: string | null
  lastUpdated: Date | null
  refresh: () => void
}

export function useLiveTwin(warehouseId: string, opts?: UseLiveTwinOptions): UseLiveTwinResult {
  const intervalMs = opts?.intervalMs ?? DEFAULT_INTERVAL_MS
  const autoRefresh = opts?.autoRefresh ?? true

  const [topology, setTopology] = useState<AutomationTopology | null>(null)
  const [snapshot, setSnapshot] = useState<TwinSnapshot | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)

  // Latest topology in a ref so the poll can derive against it without listing topology as a dep
  // (the topology is reloaded only when the warehouse changes).
  const topologyRef = useRef<AutomationTopology | null>(null)
  topologyRef.current = topology

  // Load the static topology ONCE per warehouse.
  useEffect(() => {
    if (!warehouseId) {
      setTopology(null)
      setSnapshot(null)
      setError(null)
      setLastUpdated(null)
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    loadAutomationTopology(warehouseId)
      .then((topo) => {
        if (cancelled) return
        setTopology(topo)
      })
      .catch((e) => {
        if (cancelled) return
        setError(e instanceof Error ? e.message : String(e))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [warehouseId])

  // A single poll tick: list recent device tasks and re-derive the snapshot against the topology.
  // Keeps the last good snapshot on a transient failure (only surfaces the error).
  const refresh = useCallback(async () => {
    if (!warehouseId) return
    const topo = topologyRef.current
    if (!topo) return
    setLoading(true)
    try {
      const tasks = await listDeviceTasks({ warehouseId, limit: TASK_WINDOW })
      setSnapshot(deriveTwin(tasks, topo, Date.now()))
      setError(null)
      setLastUpdated(new Date())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [warehouseId])

  // Keep the freshest poll fn in a ref so the interval below isn't torn down and recreated on every
  // render (only when warehouseId / interval / autoRefresh change).
  const refreshRef = useRef(refresh)
  refreshRef.current = refresh

  // Poll once the topology is loaded; re-run when the warehouse, topology presence, interval or the
  // auto-refresh toggle change. The first tick runs immediately so the page isn't blank for `intervalMs`.
  useEffect(() => {
    if (!warehouseId || !topology) return
    let cancelled = false
    const tick = () => {
      if (!cancelled) void refreshRef.current()
    }
    tick()
    if (!autoRefresh) return () => {
      cancelled = true
    }
    const timer = window.setInterval(tick, intervalMs)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [warehouseId, topology, autoRefresh, intervalMs])

  const refreshNow = useCallback(() => {
    void refreshRef.current()
  }, [])

  return { topology, snapshot, loading, error, lastUpdated, refresh: refreshNow }
}
