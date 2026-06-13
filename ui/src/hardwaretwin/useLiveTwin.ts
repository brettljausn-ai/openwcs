import { useCallback, useEffect, useRef, useState, type MutableRefObject } from 'react'
import { loadAutomationTopology, type AutomationTopology } from '../topology/automationApi'
import { listEquipment, type Equipment } from '../masterdata/api'
import { listDeviceTasks } from '../transport/api'
import { getStationQueue } from '../gtpops/api'
import { listLocations } from '../masterdata/api'
import { listHandlingUnits } from '../inventory/api'
import { loadConveyorNodePositions, listTotePaths, type TwinPaths } from './api'
import { insertPoint, pruneBefore, type ToteTimeline } from './motion'
import {
  deriveStoredTotes,
  deriveTwin,
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

// 2 s: one device-task read plus one tote-paths read per tick is cheap, and a shorter poll lets the
// render delay (RENDER_DELAY_MS in motion.ts) stay short — fresher smooth motion.
const DEFAULT_INTERVAL_MS = 2000
const TIMELINE_RETENTION_MS = 60_000 // history a tote keeps behind the delayed render clock
const TIMELINE_IDLE_DROP_MS = 30_000 // drop a timeline this long after its HU left the live picture
const TASK_WINDOW = 500 // wide window so totes/throughput aren't truncated by the newest-first cap

export interface UseLiveTwinOptions {
  intervalMs?: number
  autoRefresh?: boolean
}

export interface UseLiveTwinResult {
  clockOffsetMsRef: MutableRefObject<number | null>
  topology: AutomationTopology | null
  /** Master-data equipment library (id → Equipment) — lets the 3D scene classify each placement the
   *  same way the topology editor does (conveyor vs rack vs sorter). Loaded once per warehouse. */
  lib: Map<string, Equipment>
  snapshot: TwinSnapshot | null
  /** Per-tote interpolation buffers (huId → timestamped scan waypoints) for smooth motion. The Map
   *  identity is STABLE; its contents are mutated on each poll and read per-frame by the 3D layer. */
  timelines: Map<string, ToteTimeline>
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
  // Per-tote interpolation buffers (motion.ts). One stable Map for the component's lifetime —
  // mutated on each poll, read per-frame by the 3D layer; cleared when the warehouse changes.
  const timelinesRef = useRef<Map<string, ToteTimeline>>(new Map())
  // Server-vs-client clock offset estimate (newest trace timestamp minus client now at receipt).
  // The renderer anchors its delayed clock to DATA time, not the wall clock, so a skewed demo-box
  // clock cannot push the sample point outside the buffered window (observed live: totes bouncing
  // between a node and a dead-reckoned phantom position every poll).
  const clockOffsetMsRef = useRef<number | null>(null)

  // Load the static topology ONCE per warehouse.
  useEffect(() => {
    timelinesRef.current.clear() // buffered motion belongs to the previous warehouse
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
      // The backend "visu master" resolves every in-transit tote's ACTUAL conveyor polyline (world
      // positions baked in) plus the server clock — no client-side belt guessing, no clock skew.
      let totePaths: TwinPaths = { serverNowMs: Date.now(), totes: [] }
      try {
        totePaths = await listTotePaths(warehouseId)
      } catch {
        /* keep the last motion on a transient failure (equipment activity still updates) */
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
      const nowMs = Date.now()
      // Anchor the delayed render clock to the SERVER clock: waypoint timestamps are server-stamped,
      // so playing them in server time (Date.now() + offset) keeps sampling inside the buffer
      // regardless of any client/server skew (the old bounce source).
      clockOffsetMsRef.current = totePaths.serverNowMs - nowMs
      // In-transit HUs the backend gave a path: deriveTwin keeps their tote view alive even without a
      // local scan/anchor, since the path (not a re-derived position) drives their motion.
      const pathHuIds = new Set(totePaths.totes.map((tp) => tp.huId))
      const snap = deriveTwin(tasks, topo, nowMs, {
        nodeXZ: nodeXZRef.current,
        queuedByStation,
        pathHuIds,
      })
      // Feed the per-tote interpolation buffers (motion.ts) from the backend path: each waypoint is a
      // timestamped world position, its next-node the following waypoint (graph-adjacent, so a
      // straight segment between them is the belt). insertPoint is idempotent — polls overlap.
      const timelines = timelinesRef.current
      for (const tp of totePaths.totes) {
        const wps = tp.waypoints
        let tl = timelines.get(tp.huId)
        for (let i = 0; i < wps.length; i++) {
          const w = wps[i]
          if (w.tMs == null) continue
          if (!tl) {
            tl = { points: [] }
            timelines.set(tp.huId, tl)
          }
          const nextW = i + 1 < wps.length ? wps[i + 1] : tp.next
          insertPoint(tl, {
            tMs: w.tMs,
            xz: [w.x, w.z],
            nextXZ: nextW ? [nextW.x, nextW.z] : null,
          })
        }
      }
      // Prune: bound per-tote history, and drop timelines whose HU left the live picture a while ago.
      const liveHuIds = new Set(snap.totes.map((t) => t.huId))
      for (const [huId, tl] of timelines) {
        pruneBefore(tl, nowMs - TIMELINE_RETENTION_MS)
        const newest = tl.points[tl.points.length - 1]
        if (!liveHuIds.has(huId) && (!newest || nowMs - newest.tMs > TIMELINE_IDLE_DROP_MS)) {
          timelines.delete(huId)
        }
      }
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

  return {
    clockOffsetMsRef,
    topology,
    lib,
    snapshot,
    timelines: timelinesRef.current,
    storedTotes,
    loading,
    error,
    lastUpdated,
    refresh: refreshNow,
  }
}
