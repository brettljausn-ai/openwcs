import { useCallback, useEffect, useRef, useState } from 'react'
import { loadAutomationTopology, type AutomationTopology } from '../topology/automationApi'
import { listEquipment, type Equipment } from '../masterdata/api'
import { listDeviceTasks, listHuTrace } from '../transport/api'
import { getStationQueue } from '../gtpops/api'
import { listLocations } from '../masterdata/api'
import { listHandlingUnits } from '../inventory/api'
import { loadConveyorNodePositions } from './api'
import {
  deriveStoredTotes,
  deriveTwin,
  type ScanRow,
  type StorageCell,
  type StoredTote,
  type TwinSnapshot,
} from './twin'

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
const TRACE_FANOUT_CAP = 12 // max per-tick HU trace fetches (live transports are cap-metered anyway)
const ACTIVE_STATUSES = new Set(['REQUESTED', 'DISPATCHED', 'IN_PROGRESS', 'PENDING'])

export interface UseLiveTwinOptions {
  intervalMs?: number
  autoRefresh?: boolean
}

export interface UseLiveTwinResult {
  topology: AutomationTopology | null
  /** Master-data equipment library (id → Equipment) — lets the 3D scene classify each placement the
   *  same way the topology editor does (conveyor vs rack vs sorter). Loaded once per warehouse. */
  lib: Map<string, Equipment>
  snapshot: TwinSnapshot | null
  /** HUs at rest in storage, positioned at their cell inside the ASRS rack (ADR-0009 §5). */
  storedTotes: StoredTote[]
  loading: boolean
  error: string | null
  lastUpdated: Date | null
  refresh: () => void
}

const EMPTY_LIB: Map<string, Equipment> = new Map()

export function useLiveTwin(warehouseId: string, opts?: UseLiveTwinOptions): UseLiveTwinResult {
  const intervalMs = opts?.intervalMs ?? DEFAULT_INTERVAL_MS
  const autoRefresh = opts?.autoRefresh ?? true

  const [topology, setTopology] = useState<AutomationTopology | null>(null)
  const [lib, setLib] = useState<Map<string, Equipment>>(EMPTY_LIB)
  const [snapshot, setSnapshot] = useState<TwinSnapshot | null>(null)
  const [storedTotes, setStoredTotes] = useState<StoredTote[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)

  // Latest topology in a ref so the poll can derive against it without listing topology as a dep
  // (the topology is reloaded only when the warehouse changes).
  const topologyRef = useRef<AutomationTopology | null>(null)
  topologyRef.current = topology
  // Routing-node world positions (code → [x,z]) — loaded once per warehouse alongside the topology;
  // non-fatal when absent (no projected graph → no scan replay, totes degrade to strict anchors).
  const nodeXZRef = useRef<Map<string, [number, number]>>(new Map())
  // Storage cells (master-data locations with cell coordinates) — loaded once per warehouse.
  const cellsRef = useRef<StorageCell[]>([])

  // Load the static topology ONCE per warehouse.
  useEffect(() => {
    if (!warehouseId) {
      setTopology(null)
      setLib(EMPTY_LIB)
      setSnapshot(null)
      setError(null)
      setLastUpdated(null)
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    // Topology (geometry) + the equipment library (classification) load together, once per warehouse.
    // The lib is non-fatal: if it fails the scene still renders, just with coarser classification.
    Promise.all([
      loadAutomationTopology(warehouseId),
      listEquipment(warehouseId).catch(() => [] as Equipment[]),
      loadConveyorNodePositions(warehouseId).catch(() => new Map<string, [number, number]>()),
      listLocations(warehouseId).catch(() => []),
    ])
      .then(([topo, equipment, nodeXZ, locations]) => {
        if (cancelled) return
        const map = new Map<string, Equipment>()
        for (const e of equipment) if (e.id) map.set(e.id, e)
        setLib(map)
        nodeXZRef.current = nodeXZ
        cellsRef.current = locations.map((l) => ({
          id: l.id ?? '',
          aisle: l.aisle,
          side: l.side,
          posX: l.posX,
          posY: l.posY,
          posZ: l.posZ,
        }))
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

  // A single poll tick: list recent device tasks, fetch the transport trace for the (few) HUs with a
  // RUNNING task (their SCANNED rows position the totes — ADR-0008 replay), and re-derive the
  // snapshot. Keeps the last good snapshot on a transient failure (only surfaces the error).
  const refresh = useCallback(async () => {
    if (!warehouseId) return
    const topo = topologyRef.current
    if (!topo) return
    setLoading(true)
    try {
      const tasks = await listDeviceTasks({ warehouseId, limit: TASK_WINDOW })
      // HUs with live transports: trace them for scan positions (cap to keep the fan-out tiny).
      const activeHus: string[] = []
      for (const t of tasks) {
        const status = (t.status || '').toUpperCase()
        const huId = typeof t.payload?.huId === 'string' ? (t.payload.huId as string) : t.correlationId
        if (!huId || !ACTIVE_STATUSES.has(status)) continue
        if (!activeHus.includes(huId)) activeHus.push(huId)
        if (activeHus.length >= TRACE_FANOUT_CAP) break
      }
      const traces = await Promise.all(
        activeHus.map((huId) =>
          listHuTrace(huId, warehouseId)
            .then((rows) => [huId, rows] as const)
            .catch(() => [huId, []] as const),
        ),
      )
      const tracesByHu = new Map<string, ScanRow[]>()
      for (const [huId, rows] of traces) {
        tracesByHu.set(
          huId,
          rows.map((r) => ({ event: r.event, point: r.point, toPoint: r.toPoint, ts: r.ts })),
        )
      }
      // The REAL induction queue per placed workstation — the truth source for "queued" totes
      // (a tote whose work is DONE stops being shown; no phantom queued from stale tasks).
      const stationIds = (topo.equipment ?? [])
        .map((e) => e.stationId)
        .filter((s): s is string => !!s)
      const queues = await Promise.all(
        stationIds.map((sid) =>
          getStationQueue(sid)
            .then((entries) => [sid, entries] as const)
            .catch(() => [sid, []] as const),
        ),
      )
      const queuedByStation = new Map<string, Array<{ huId: string; huCode?: string | null }>>()
      for (const [sid, entries] of queues) {
        queuedByStation.set(
          sid,
          entries
            .filter((e) => e.status === 'QUEUED' && e.huId)
            .map((e) => ({ huId: e.huId as string, huCode: e.huCode ?? null })),
        )
      }
      const snap = deriveTwin(tasks, topo, Date.now(), {
        tracesByHu,
        nodeXZ: nodeXZRef.current,
        queuedByStation,
      })
      setSnapshot(snap)
      // Rack contents: every registry HU at rest, excluding HUs currently shown as live totes.
      try {
        const hus = await listHandlingUnits(warehouseId)
        const liveIds = new Set(snap.totes.map((t) => t.huId))
        setStoredTotes(deriveStoredTotes(hus, cellsRef.current, topo).filter((t) => !liveIds.has(t.huId)))
      } catch {
        /* keep the last rack snapshot on a transient failure */
      }
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

  return { topology, lib, snapshot, storedTotes, loading, error, lastUpdated, refresh: refreshNow }
}
