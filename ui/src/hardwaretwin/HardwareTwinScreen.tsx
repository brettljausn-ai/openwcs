import { Suspense, lazy, useEffect, useMemo, useState } from 'react'
import { useWarehouse } from '../warehouse/WarehouseContext'
import Select from '../ui/Select'
import { listDeviceTasks, listHuTrace, type DeviceTask, type HuTraceRow } from '../transport/api'
import type { AutomationEquipment, AutomationTopology } from '../topology/automationApi'
import type { EquipmentActivity, ToteView, TwinSnapshot } from './twin'

// Hardware visualisation — a live 3D view of the automation hardware. The geometry comes from the
// Automation topology; the live overlay (equipment activity + handling units moving through the
// system) is re-derived each poll from the flow device-task feed (see twin.ts / useLiveTwin). The
// three.js scene is code-split behind React.lazy so it never enters the main bundle.

import { useLiveTwin } from './useLiveTwin'

// The heavy three.js scene lives in its own chunk (built in parallel). Lazy-import keeps it out of
// the main bundle; it default-exports a component matching HardwareTwin3DProps.
const HardwareTwin3D = lazy(() => import('./HardwareTwin3D'))

// 2 s poll: cheap (one device-task read + capped trace fan-out) and keeps the interpolation
// buffer fed so the delayed render clock (motion.ts RENDER_DELAY_MS) rarely underruns.
const POLL_MS = 2000

// Tote-state colours — must match the 3D component's palette.
const TOTE_COLOURS: Record<ToteView['state'], string> = {
  'in-transit': 'var(--herbal-lime)',
  recirculating: 'var(--warning)',
  queued: '#5aa9ff',
  done: 'var(--text-dim)',
}

// Equipment-state colours — must match the 3D component's palette.
const EQUIPMENT_COLOURS: Record<EquipmentActivity['state'], string> = {
  idle: 'var(--text-dim)',
  running: 'var(--warning)',
  faulted: 'var(--danger)',
}

// Conveyor live-state skin colours: must match the 3D component's SKIN_TARGETS palette
// (duplicated by design: this screen must not import the lazy three.js chunk).
const CONVEYOR_COLOURS = {
  ok: '#3fae6e', // functional: gentle green tint over the belt
  jam: '#f4b860', // jam / heavy traffic: stalled tote, dense totes or a HELD divert
  fault: '#ff6b5e', // stopped / error: an active device task reported FAILED
}

function formatTime(iso?: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString()
}

function shortId(id?: string | null): string {
  return id ? id.slice(0, 8) : '—'
}

export default function HardwareTwinScreen() {
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const [autoRefresh, setAutoRefresh] = useState(true)
  const { topology, lib, snapshot, timelines, clockOffsetMsRef, storedTotes, loading, error, lastUpdated, refresh } = useLiveTwin(
    warehouseId,
    { intervalMs: POLL_MS, autoRefresh },
  )

  const [showLabels, setShowLabels] = useState(false)
  const [activeLevelId, setActiveLevelId] = useState<string | null>(null)
  const [selectedPlacedId, setSelectedPlacedId] = useState<string | null>(null)
  const [selectedHuId, setSelectedHuId] = useState<string | null>(null)

  const levels = topology?.levels ?? []

  // Default the active level to the first level once topology loads; reset selection if the warehouse
  // (and therefore the topology) changes.
  useEffect(() => {
    setSelectedPlacedId(null)
    setSelectedHuId(null)
    setActiveLevelId(levels.length ? levels[0].id : null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [warehouseId])

  useEffect(() => {
    if (activeLevelId == null && levels.length) setActiveLevelId(levels[0].id)
  }, [levels, activeLevelId])

  // Selecting one kind of thing clears the other (the detail panel shows a single selection).
  const selectEquipment = (placedId: string | null) => {
    setSelectedPlacedId(placedId)
    if (placedId) setSelectedHuId(null)
  }
  const selectTote = (huId: string | null) => {
    setSelectedHuId(huId)
    if (huId) setSelectedPlacedId(null)
  }
  const clearSelection = () => {
    setSelectedPlacedId(null)
    setSelectedHuId(null)
  }

  const stats = snapshot?.stats
  const isEmpty = !!topology && topology.equipment.length === 0

  return (
    <div className="app-content">
      <div
        className="page-head"
        style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '1rem', flexWrap: 'wrap' }}
      >
        <div>
          <span className="eyebrow">Flow orchestrator</span>
          <h1>Hardware visualisation</h1>
          <p>
            Live 3D view of the automation hardware — equipment activity and handling units moving
            through the system, derived from the device-task feed.
          </p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '.75rem', flexWrap: 'wrap' }}>
          {lastUpdated && (
            <span className="muted" style={{ fontSize: '.75rem' }}>
              Updated {lastUpdated.toLocaleTimeString()}
            </span>
          )}
          <label style={{ display: 'inline-flex', alignItems: 'center', gap: '.4rem', fontSize: '.85rem' }}>
            <input type="checkbox" checked={showLabels} onChange={(e) => setShowLabels(e.target.checked)} />
            Labels
          </label>
          <label style={{ display: 'inline-flex', alignItems: 'center', gap: '.4rem', fontSize: '.85rem' }}>
            <input type="checkbox" checked={autoRefresh} onChange={(e) => setAutoRefresh(e.target.checked)} />
            Auto-refresh
          </label>
          <button className="btn btn-ghost btn-sm" onClick={refresh} disabled={loading || !topology}>
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </div>

      {/* Control / stats bar */}
      <div
        className="glass"
        style={{
          padding: '.85rem 1.1rem',
          marginBottom: '1rem',
          display: 'flex',
          alignItems: 'center',
          gap: '1.25rem',
          flexWrap: 'wrap',
        }}
      >
        <div style={{ display: 'flex', gap: '.5rem', flexWrap: 'wrap' }}>
          <StatChip label="In transit" value={stats?.inTransit ?? 0} kind="info" />
          <StatChip label="Queued" value={stats?.queued ?? 0} kind="warning" />
          <StatChip label="Throughput /min" value={stats?.throughputPerMin ?? 0} kind="success" />
          <StatChip label="Recirculations" value={stats?.recirculations ?? 0} kind="warning" />
          <StatChip label="Faults" value={stats?.faults ?? 0} kind={stats && stats.faults > 0 ? 'danger' : 'muted'} />
          <StatChip label="In storage" value={storedTotes.length} kind="muted" />
        </div>

        {levels.length > 1 && (
          <label style={{ display: 'inline-flex', alignItems: 'center', gap: '.5rem', fontSize: '.85rem' }}>
            <span className="muted" style={{ fontFamily: 'var(--font-mono)', fontSize: '.65rem', letterSpacing: '.12em', textTransform: 'uppercase' }}>
              Level
            </span>
            <Select
              ariaLabel="Level"
              value={activeLevelId ?? ''}
              onChange={(v) => setActiveLevelId(v || null)}
              style={{ width: 200 }}
              options={[
                { value: '', label: 'All levels' },
                ...levels.map((l) => ({ value: l.id, label: l.name || `Level ${l.number}` })),
              ]}
            />
          </label>
        )}

        <div style={{ display: 'flex', gap: '.9rem', flexWrap: 'wrap', marginLeft: 'auto', alignItems: 'center' }}>
          {/* Conveyor belts wear their state as a skin (no orb on conveyors). */}
          <LegendSwatch colour={CONVEYOR_COLOURS.ok} label="Functional" />
          <LegendSwatch colour={CONVEYOR_COLOURS.jam} label="Jam / heavy traffic" />
          <LegendSwatch colour={CONVEYOR_COLOURS.fault} label="Stopped / error" />
          <span className="muted" style={{ opacity: 0.4 }}>|</span>
          {/* Non-conveyor equipment (ASRS, stations) still shows the floating activity orb. */}
          <LegendSwatch colour={EQUIPMENT_COLOURS.running} label="Running (ASRS / stations)" round />
          <LegendSwatch colour={EQUIPMENT_COLOURS.faulted} label="Faulted (ASRS / stations)" round />
          <span className="muted" style={{ opacity: 0.4 }}>|</span>
          <LegendSwatch colour={TOTE_COLOURS['in-transit']} label="In transit" round />
          <LegendSwatch colour={TOTE_COLOURS.recirculating} label="Recirculating" round />
          <LegendSwatch colour={TOTE_COLOURS.queued} label="Queued" round />
        </div>
      </div>

      {error && (
        <p className="badge badge-danger" style={{ marginBottom: '1rem' }}>
          {error}
        </p>
      )}

      {/* Viewer + detail panel */}
      {!warehouseId ? (
        <div className="glass" style={{ padding: '2.5rem', textAlign: 'center', color: 'var(--text-dim)' }}>
          Select a warehouse in the top bar to load its automation hardware.
        </div>
      ) : isEmpty ? (
        <div className="glass" style={{ padding: '2.5rem', textAlign: 'center' }}>
          <div style={{ fontSize: '2.5rem', marginBottom: '.5rem' }}>◳</div>
          <h2 style={{ marginTop: 0 }}>No automation topology placed yet</h2>
          <p style={{ color: 'var(--text-dim)', margin: 0 }}>
            Build one in <strong>Automation topology</strong>, then equipment and handling units will
            appear here live.
          </p>
        </div>
      ) : (
        <div style={{ display: 'flex', gap: '1rem', alignItems: 'stretch', flexWrap: 'wrap' }}>
          <div
            className="glass"
            style={{
              flex: '1 1 600px',
              minWidth: 0,
              minHeight: 'calc(100vh - 22rem)',
              padding: 0,
              overflow: 'hidden',
              position: 'relative',
            }}
          >
            {topology ? (
              <Suspense
                fallback={
                  <div
                    style={{
                      position: 'absolute',
                      inset: 0,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      color: 'var(--text-dim)',
                    }}
                  >
                    Loading 3D scene…
                  </div>
                }
              >
                <HardwareTwin3D
                  topology={topology}
                  lib={lib}
                  snapshot={snapshot ?? EMPTY_SNAPSHOT}
                  timelines={timelines}
                  clockOffsetMsRef={clockOffsetMsRef}
                  storedTotes={storedTotes}
                  showLabels={showLabels}
                  activeLevelId={activeLevelId}
                  selectedPlacedId={selectedPlacedId}
                  selectedHuId={selectedHuId}
                  onSelectEquipment={selectEquipment}
                  onSelectTote={selectTote}
                />
              </Suspense>
            ) : (
              <div style={{ padding: '2.5rem', textAlign: 'center', color: 'var(--text-dim)' }}>Loading topology…</div>
            )}
          </div>

          {(selectedPlacedId || selectedHuId) && (
            <div className="glass" style={{ flex: '0 0 340px', minWidth: 300, padding: '1.1rem', alignSelf: 'flex-start' }}>
              {selectedPlacedId && topology && (
                <EquipmentDetail
                  topology={topology}
                  placedId={selectedPlacedId}
                  snapshot={snapshot}
                  warehouseId={warehouseId}
                  onClose={clearSelection}
                />
              )}
              {selectedHuId && (
                <ToteDetail
                  huId={selectedHuId}
                  snapshot={snapshot}
                  warehouseId={warehouseId}
                  onClose={clearSelection}
                />
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

const EMPTY_SNAPSHOT: TwinSnapshot = {
  activityByPlacedId: {},
  totes: [],
  stats: { inTransit: 0, queued: 0, recirculations: 0, faults: 0, throughputPerMin: 0, byFamily: {} },
}

type StatKind = 'success' | 'warning' | 'danger' | 'info' | 'muted'

const STAT_COLOUR: Record<StatKind, string> = {
  success: 'var(--herbal-lime)',
  warning: 'var(--warning)',
  danger: 'var(--danger)',
  info: '#5aa9ff',
  muted: 'var(--text)',
}

function StatChip({ label, value, kind }: { label: string; value: number; kind: StatKind }) {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: '.1rem',
        padding: '.35rem .7rem',
        borderRadius: 8,
        border: '1px solid var(--glass-border)',
        background: 'rgba(255,255,255,.02)',
        minWidth: 78,
      }}
    >
      <span style={{ fontSize: '1.25rem', fontWeight: 600, color: STAT_COLOUR[kind], lineHeight: 1 }}>{value}</span>
      <span
        className="muted"
        style={{ fontFamily: 'var(--font-mono)', fontSize: '.6rem', letterSpacing: '.1em', textTransform: 'uppercase' }}
      >
        {label}
      </span>
    </div>
  )
}

function LegendSwatch({ colour, label, round }: { colour: string; label: string; round?: boolean }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: '.35rem', fontSize: '.75rem', color: 'var(--text-dim)' }}>
      <span
        aria-hidden
        style={{ width: 11, height: 11, borderRadius: round ? '50%' : 3, background: colour, display: 'inline-block' }}
      />
      {label}
    </span>
  )
}

function DetailHeader({ eyebrow, title, onClose }: { eyebrow: string; title: string; onClose: () => void }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '.75rem', marginBottom: '.75rem' }}>
      <div>
        <span className="eyebrow">{eyebrow}</span>
        <h3 style={{ margin: '.1rem 0 0' }}>{title}</h3>
      </div>
      <button className="btn btn-ghost btn-sm" onClick={onClose} aria-label="Close">
        ✕
      </button>
    </div>
  )
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: '.75rem', padding: '.25rem 0' }}>
      <span className="muted" style={{ fontSize: '.78rem' }}>
        {label}
      </span>
      <span style={{ fontSize: '.82rem', textAlign: 'right', wordBreak: 'break-word' }}>{value}</span>
    </div>
  )
}

function eqStateBadge(state: EquipmentActivity['state']): string {
  return state === 'running' ? 'badge badge-warning' : state === 'faulted' ? 'badge badge-danger' : 'badge'
}

// Equipment detail: placement code/category + live activity + its recent device tasks. The device
// tasks are keyed by the master-data equipmentId of the placement (placedId → equipment lookup).
function EquipmentDetail({
  topology,
  placedId,
  snapshot,
  warehouseId,
  onClose,
}: {
  topology: AutomationTopology
  placedId: string
  snapshot: TwinSnapshot | null
  warehouseId: string
  onClose: () => void
}) {
  const eq: AutomationEquipment | undefined = useMemo(
    () => topology.equipment.find((e) => e.id === placedId),
    [topology, placedId],
  )
  const activity = snapshot?.activityByPlacedId[placedId]
  const equipmentId = eq?.equipmentId ?? null

  const [tasks, setTasks] = useState<DeviceTask[]>([])
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    if (!equipmentId) {
      setTasks([])
      return
    }
    let cancelled = false
    setLoading(true)
    setErr(null)
    listDeviceTasks({ warehouseId, equipmentId, limit: 20 })
      .then((rows) => !cancelled && setTasks(rows))
      .catch((e) => !cancelled && setErr(e instanceof Error ? e.message : String(e)))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [warehouseId, equipmentId])

  return (
    <div>
      <DetailHeader eyebrow="Equipment" title={eq?.code ?? shortId(placedId)} onClose={onClose} />
      <Row label="Category" value={eq?.category ?? '—'} />
      <Row
        label="State"
        value={
          activity ? <span className={eqStateBadge(activity.state)}>{activity.state}</span> : <span className="badge">idle</span>
        }
      />
      <Row label="Active tasks" value={activity?.activeTasks ?? 0} />
      <Row label="Last command" value={activity?.lastCommand ?? '—'} />
      <Row label="Last activity" value={formatTime(activity?.lastTs)} />

      <h4 style={{ margin: '1rem 0 .4rem', fontSize: '.85rem' }}>Recent device tasks</h4>
      {!equipmentId && <p className="muted" style={{ fontSize: '.8rem' }}>This placement isn't linked to a master-data equipment.</p>}
      {err && <p className="badge badge-danger" style={{ fontSize: '.78rem' }}>{err}</p>}
      {loading && tasks.length === 0 && <p className="muted" style={{ fontSize: '.8rem' }}>Loading tasks…</p>}
      {equipmentId && !loading && tasks.length === 0 && !err && (
        <p className="muted" style={{ fontSize: '.8rem' }}>No recent tasks for this equipment.</p>
      )}
      <ol style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: '.35rem' }}>
        {tasks.map((t) => (
          <li
            key={t.id}
            style={{ display: 'flex', alignItems: 'center', gap: '.5rem', flexWrap: 'wrap', padding: '.4rem .5rem', borderRadius: 8, background: 'rgba(255,255,255,.02)' }}
          >
            <span className={taskBadge(t.status)}>{t.status}</span>
            <span style={{ fontFamily: 'var(--font-mono)', fontSize: '.75rem' }}>{t.command}</span>
            <span className="muted" style={{ fontSize: '.7rem', marginLeft: 'auto', whiteSpace: 'nowrap' }}>{formatTime(t.createdAt)}</span>
          </li>
        ))}
      </ol>
    </div>
  )
}

function taskBadge(status: string): string {
  const s = status.toUpperCase()
  if (s === 'COMPLETED') return 'badge badge-success'
  if (s === 'FAILED') return 'badge badge-danger'
  if (s === 'REQUESTED' || s === 'PENDING') return 'badge badge-warning'
  return 'badge'
}

function traceEventBadge(event: string): string {
  const e = event.toUpperCase()
  if (e === 'DONE') return 'badge badge-success'
  if (e === 'QUEUED' || e === 'REQUESTED') return 'badge badge-warning'
  if (e === 'RECIRCULATED' || e === 'DIVERTED') return 'badge badge-danger'
  return 'badge'
}

// Tote detail: the selected handling unit's code + live state + its HU transport-trace timeline.
function ToteDetail({
  huId,
  snapshot,
  warehouseId,
  onClose,
}: {
  huId: string
  snapshot: TwinSnapshot | null
  warehouseId: string
  onClose: () => void
}) {
  const tote = snapshot?.totes.find((t) => t.huId === huId) ?? null
  const [trace, setTrace] = useState<HuTraceRow[]>([])
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setErr(null)
    listHuTrace(huId, warehouseId)
      .then((rows) => !cancelled && setTrace(rows))
      .catch((e) => !cancelled && setErr(e instanceof Error ? e.message : String(e)))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [huId, warehouseId])

  return (
    <div>
      <DetailHeader eyebrow="Handling unit" title={tote?.huCode ?? shortId(huId)} onClose={onClose} />
      <Row
        label="State"
        value={
          tote ? (
            <span
              className={
                tote.state === 'queued'
                  ? 'badge badge-warning'
                  : tote.state === 'recirculating'
                    ? 'badge badge-warning'
                    : tote.state === 'in-transit'
                      ? 'badge badge-success'
                      : 'badge'
              }
            >
              {tote.state}
            </span>
          ) : (
            '—'
          )
        }
      />
      <Row label="Last command" value={tote?.lastCommand ?? '—'} />
      <Row label="Last seen" value={formatTime(tote?.lastTs)} />
      <Row label="Correlation" value={shortId(tote?.correlationId)} />

      <h4 style={{ margin: '1rem 0 .4rem', fontSize: '.85rem' }}>Transport trace</h4>
      {err && <p className="badge badge-danger" style={{ fontSize: '.78rem' }}>{err}</p>}
      {loading && trace.length === 0 && <p className="muted" style={{ fontSize: '.8rem' }}>Loading trace…</p>}
      {!loading && trace.length === 0 && !err && (
        <p className="muted" style={{ fontSize: '.8rem' }}>No recorded transport events for this handling unit.</p>
      )}
      <ol style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: '.35rem' }}>
        {trace.map((row, i) => (
          <li
            key={row.id}
            style={{ display: 'flex', alignItems: 'center', gap: '.5rem', flexWrap: 'wrap', padding: '.4rem .5rem', borderRadius: 8, background: 'rgba(255,255,255,.02)' }}
          >
            <span className="muted" style={{ fontFamily: 'var(--font-mono)', fontSize: '.7rem', minWidth: '1.2rem' }}>{i + 1}</span>
            <span className={traceEventBadge(row.event)}>{row.event}</span>
            {row.point && <span style={{ fontFamily: 'var(--font-mono)', fontSize: '.75rem' }}>{row.point}</span>}
            {row.decision && <span className="muted" style={{ fontSize: '.72rem' }}>{row.decision}</span>}
            <span className="muted" style={{ fontSize: '.7rem', marginLeft: 'auto', whiteSpace: 'nowrap' }}>{formatTime(row.ts)}</span>
          </li>
        ))}
      </ol>
    </div>
  )
}
