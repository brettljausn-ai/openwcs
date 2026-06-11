import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useCatalog, type Catalog } from '../lib/useCatalog'
import Select from '../ui/Select'
import DataTable from '../ui/DataTable'
import InfoTip from '../ui/InfoTip'
import { DeviceTask, HuTraceRow, listDeviceTasks, listHuTrace, listTaskTrace } from './api'
import { listWorkplaces } from '../gtpops/api'

// Transport overview (build.md §8): a live view of the device tasks the flow-orchestrator
// dispatches to equipment adapters. Lists active and recent tasks with their lifecycle
// status (REQUESTED → DISPATCHED → COMPLETED/FAILED), equipment family, origin → destination
// and route/next-hop pulled from the task payload, plus summary tiles and auto-refresh.
// UI-only against the existing /api/flow/device-tasks read endpoint.

const FAMILIES = ['CONVEYOR', 'ASRS', 'AMR', 'AUTOSTORE'] as const
const REFRESH_MS = 4000

type StatusKind = 'success' | 'warning' | 'danger' | 'info' | 'muted'

const STATUS_BADGE: Record<string, StatusKind> = {
  COMPLETED: 'success',
  DISPATCHED: 'info',
  REQUESTED: 'warning',
  FAILED: 'danger',
}

// Tasks that are not yet in a terminal state count as "active".
const ACTIVE_STATUSES = new Set(['REQUESTED', 'DISPATCHED'])

// Badge colour per HU-trace lifecycle event (ADR-0007 §2.2): the early/in-flight events are
// informational, arrival/queue is a warning (waiting to be worked), DONE is terminal/success.
const TRACE_EVENT_BADGE: Record<string, StatusKind> = {
  REQUESTED: 'warning',
  RETRIEVED: 'info',
  INDUCTED: 'info',
  ARRIVED: 'info',
  QUEUED: 'warning',
  DONE: 'success',
}

// What slice of transports to show. The screen opens on "open-today" — everything still running
// plus anything that finished today — which is the operator's working set. Switchable from the UI.
const SCOPES = [
  { value: 'open-today', label: 'Open + finished today' },
  { value: 'active', label: 'Open (active) only' },
  { value: 'completed', label: 'Completed' },
  { value: 'failed', label: 'Failed' },
  { value: 'all', label: 'All recent' },
] as const
type Scope = (typeof SCOPES)[number]['value']
const DEFAULT_SCOPE: Scope = 'open-today'

// What "correlation" means, surfaced as hover help and in the trace dialog.
const CORRELATION_HELP =
  'Correlation id groups every device task that belongs to one logical transport / process ' +
  'instance — e.g. a tote retrieved from the ASRS and then conveyed to a station share one id. ' +
  'Click a row to see the full trace.'

// Help for the per-HU transport-trace timeline (ADR-0007 §3.4).
const HU_TRACE_HELP =
  'The handling unit’s recorded transport timeline across the induction pipeline — requested, ' +
  'retrieved from storage, inducted onto the conveyor, arrived at the workplace, queued, and done. ' +
  'Each row is one event flow wrote as the tote moved.'

function isToday(iso: string): boolean {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return false
  const now = new Date()
  return (
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate()
  )
}

// Client-side scope filter — the backend list is a single newest-first window, so the working-set
// scopes (which span multiple statuses) are applied here.
function inScope(task: DeviceTask, scope: Scope): boolean {
  const active = ACTIVE_STATUSES.has(task.status)
  switch (scope) {
    case 'open-today':
      return active || isToday(task.createdAt)
    case 'active':
      return active
    case 'completed':
      return task.status === 'COMPLETED'
    case 'failed':
      return task.status === 'FAILED'
    case 'all':
    default:
      return true
  }
}

function badgeClass(status: string): string {
  const kind = STATUS_BADGE[status] ?? 'muted'
  return kind === 'muted' ? 'badge' : `badge badge-${kind}`
}

function traceEventBadgeClass(event: string): string {
  const kind = TRACE_EVENT_BADGE[event] ?? 'muted'
  return kind === 'muted' ? 'badge' : `badge badge-${kind}`
}

// Best-effort extraction of a field from the task payload/result, trying a few common keys.
function field(task: DeviceTask, keys: string[]): string | undefined {
  for (const source of [task.payload, task.result]) {
    if (!source) continue
    for (const key of keys) {
      const v = source[key]
      if (v != null && v !== '') return String(v)
    }
  }
  return undefined
}

function huCode(task: DeviceTask): string {
  return field(task, ['huCode', 'hu', 'handlingUnitCode']) ?? '—'
}

function origin(task: DeviceTask): string {
  return field(task, ['from', 'origin', 'fromNode', 'source', 'sourceNode', 'locationId', 'fromLocationId', 'sourceLocationId']) ?? '—'
}

function destination(task: DeviceTask): string {
  return field(task, ['to', 'destination', 'toNode', 'target', 'targetNode', 'destinationStationId', 'toStationId', 'stationId']) ?? '—'
}

function nextHop(task: DeviceTask): string {
  // For conveyor routes this is the exit/route hop; ASRS/AMR/AutoStore deliver straight to the
  // destination, so fall back to that so the column is never blank for them.
  return field(task, ['nextHop', 'next', 'exitCode', 'route']) ?? destination(task)
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

// The handling-unit id a task is moving, used to pull flow's per-HU transport trace (ADR-0007 §3.4).
// Prefer an explicit huId in the payload/result; fall back to correlationId, which == huId for
// induction transports today (the contract sets correlationId = huId). Only return a value that
// looks like a UUID so we never query hu-trace with a non-id. Undefined → the dialog falls back to
// the byCorrelation device-task trace.
function huId(task: DeviceTask): string | undefined {
  const explicit = field(task, ['huId', 'handlingUnitId'])
  if (explicit && UUID_RE.test(explicit)) return explicit
  if (task.correlationId && UUID_RE.test(task.correlationId)) return task.correlationId
  return undefined
}

function shortId(id?: string | null): string {
  return id ? id.slice(0, 8) : '—'
}

function formatTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString()
}

export default function TransportScreen() {
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const catalog = useCatalog(warehouseId)
  const [tasks, setTasks] = useState<DeviceTask[]>([])
  const [scope, setScope] = useState<Scope>(DEFAULT_SCOPE)
  const [family, setFamily] = useState('')
  const [equipmentId, setEquipmentId] = useState('')
  const [selected, setSelected] = useState<DeviceTask | null>(null)
  const [equipment, setEquipment] = useState<{ id: string; code?: string; name?: string }[]>([])
  const [autoRefresh, setAutoRefresh] = useState(true)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)
  const [stationsById, setStationsById] = useState<Record<string, string>>({})

  // GTP stations (workplaces) so a destinationStationId (e.g. an ASRS retrieval to a pick station)
  // shows the station code instead of a UUID.
  useEffect(() => {
    if (!warehouseId) {
      setStationsById({})
      return
    }
    let cancelled = false
    listWorkplaces(warehouseId)
      .then((ws) => {
        if (cancelled) return
        const m: Record<string, string> = {}
        ws.forEach((w) => (m[w.id] = w.code))
        setStationsById(m)
      })
      .catch(() => !cancelled && setStationsById({}))
    return () => {
      cancelled = true
    }
  }, [warehouseId])

  // Resolve a node reference to a readable code: a UUID becomes its location/station code (or a short
  // id if unknown); an already-readable code/name passes through unchanged.
  const resolveNode = useCallback(
    (raw: string): string => {
      if (!raw || raw === '—') return '—'
      if (!UUID_RE.test(raw)) return raw
      const lc = catalog.locationCode(raw)
      if (lc && lc !== '—' && lc !== raw) return lc
      if (stationsById[raw]) return stationsById[raw]
      return shortId(raw)
    },
    [catalog, stationsById],
  )

  // Keep the latest server-side filters in a ref so the polling interval always reads fresh values
  // without being torn down and recreated on every keystroke. Scope is applied client-side, so it
  // is not part of the backend query (which is a single newest-first window).
  const filtersRef = useRef({ warehouseId, family, equipmentId })
  filtersRef.current = { warehouseId, family, equipmentId }

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      // Pull a wide window so the "finished today" scope isn't truncated by the newest-first cap.
      const data = await listDeviceTasks({ ...filtersRef.current, limit: 500 })
      setTasks(data)
      setError(null)
      setLastUpdated(new Date())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [])

  // Initial load + reload when the server-side filters change.
  useEffect(() => {
    refresh()
  }, [refresh, warehouseId, family, equipmentId])

  // Load the equipment list for the active warehouse so the filter is a pick-list, not a raw UUID.
  useEffect(() => {
    if (!warehouseId) {
      setEquipment([])
      return
    }
    let cancelled = false
    ;(async () => {
      try {
        const res = await fetch(`/api/master-data/equipment?warehouseId=${encodeURIComponent(warehouseId)}`)
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        const data = await res.json()
        if (!cancelled) setEquipment(Array.isArray(data) ? data : [])
      } catch {
        if (!cancelled) setEquipment([])
      }
    })()
    return () => {
      cancelled = true
    }
  }, [warehouseId])

  // Auto-refresh poll.
  useEffect(() => {
    if (!autoRefresh) return
    const id = window.setInterval(refresh, REFRESH_MS)
    return () => window.clearInterval(id)
  }, [autoRefresh, refresh])

  // The visible rows: backend window narrowed to the chosen scope, client-side.
  const scoped = useMemo(() => tasks.filter((t) => inScope(t, scope)), [tasks, scope])

  const counts = useMemo(() => {
    const c = { REQUESTED: 0, DISPATCHED: 0, COMPLETED: 0, FAILED: 0, active: 0, total: scoped.length }
    for (const t of scoped) {
      if (t.status in c) c[t.status as keyof typeof c] += 1
      if (ACTIVE_STATUSES.has(t.status)) c.active += 1
    }
    return c
  }, [scoped])

  const tiles: { label: string; value: number; kind: StatusKind }[] = [
    { label: 'Active', value: counts.active, kind: 'info' },
    { label: 'Requested', value: counts.REQUESTED, kind: 'warning' },
    { label: 'Dispatched', value: counts.DISPATCHED, kind: 'info' },
    { label: 'Completed', value: counts.COMPLETED, kind: 'success' },
    { label: 'Failed', value: counts.FAILED, kind: 'danger' },
    { label: 'Total', value: counts.total, kind: 'muted' },
  ]

  const tileColor: Record<StatusKind, string> = {
    success: '#8DC63F',
    warning: '#f4b860',
    danger: '#ff8a80',
    info: '#8DC63F',
    muted: 'var(--text)',
  }

  return (
    <div className="app-content">
      <div className="page-head" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '1rem', flexWrap: 'wrap' }}>
        <div>
          <span className="eyebrow">Flow orchestrator</span>
          <h1>Transport overview</h1>
          <p>Live device-task / transport view across equipment — origin → destination, route and lifecycle status. Click a transport for its full trace.</p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '.75rem' }}>
          {lastUpdated && (
            <span className="muted" style={{ fontSize: '.75rem' }}>
              Updated {lastUpdated.toLocaleTimeString()}
            </span>
          )}
          <label style={{ display: 'inline-flex', alignItems: 'center', gap: '.4rem', fontSize: '.85rem' }}>
            <input type="checkbox" checked={autoRefresh} onChange={(e) => setAutoRefresh(e.target.checked)} />
            Auto-refresh <InfoTip text={`When on, the task list automatically reloads from the flow orchestrator every ${REFRESH_MS / 1000} seconds; turn off to freeze the view.`} example="on" />
          </label>
          <button className="btn btn-outline btn-sm" onClick={refresh} disabled={loading}>
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </div>

      {/* Summary stat tiles */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))', gap: '1rem', marginBottom: '1.5rem' }}>
        {tiles.map((t) => (
          <div key={t.label} className="glass" style={{ padding: '1.1rem 1.25rem' }}>
            <div style={{ fontSize: '2rem', fontWeight: 600, color: tileColor[t.kind] }}>{t.value}</div>
            <div className="muted" style={{ fontFamily: 'var(--font-mono)', fontSize: '.7rem', letterSpacing: '.12em', textTransform: 'uppercase' }}>
              {t.label}
            </div>
          </div>
        ))}
      </div>

      {/* Filters */}
      <div className="glass" style={{ padding: '1rem 1.25rem', marginBottom: '1.25rem', display: 'flex', flexWrap: 'wrap', gap: '.75rem', alignItems: 'flex-end' }}>
        <Field label={<>Show <InfoTip text="Which transports to list. Opens on 'Open + finished today' — everything still running plus whatever finished today, the live working set. Switch to a single status or 'All recent' to widen the view." example="Open + finished today" /></>}>
          <Select
            ariaLabel="Show"
            value={scope}
            onChange={(v) => setScope(v as Scope)}
            style={{ width: 220 }}
            options={SCOPES.map((s) => ({ value: s.value, label: s.label }))}
          />
        </Field>
        <Field label={<>Equipment family <InfoTip text="Filter by the type of transport equipment handling the task. Each family maps to its own adapter." example="ASRS" /></>}>
          <Select
            ariaLabel="Equipment family"
            value={family}
            onChange={(v) => setFamily(v)}
            options={[{ value: '', label: 'All families' }, ...FAMILIES.map((f) => ({ value: f, label: f }))]}
          />
        </Field>
        <Field label={<>Equipment <InfoTip text="Filter to a single piece of equipment in the active warehouse, picked by its code/name. Choose 'Any equipment' for all." example="CONV-01 — Inbound conveyor" /></>}>
          <Select
            ariaLabel="Equipment"
            value={equipmentId}
            onChange={(v) => setEquipmentId(v)}
            placeholder="Any equipment"
            style={{ width: 280 }}
            options={[
              { value: '', label: 'Any equipment' },
              ...equipment.map((e) => ({
                value: e.id,
                label: e.code ? (e.name ? `${e.code} — ${e.name}` : e.code) : e.id,
              })),
            ]}
          />
        </Field>
        {(scope !== DEFAULT_SCOPE || family || equipmentId) && (
          <button className="btn btn-ghost btn-sm" onClick={() => { setScope(DEFAULT_SCOPE); setFamily(''); setEquipmentId('') }}>
            Reset filters
          </button>
        )}
      </div>

      {error && <div className="alert" style={{ background: 'rgba(255,107,94,.15)', color: '#ff8a80', border: '1px solid rgba(255,107,94,.3)' }}>{error}</div>}

      {/* Task table */}
      <div className="glass" style={{ padding: 0, overflow: 'auto' }}>
        <DataTable<DeviceTask>
          rows={scoped}
          rowKey={(t) => t.id}
          onRowClick={(t) => setSelected(t)}
          search={(t) =>
            `${t.id} ${t.status} ${t.family} ${t.command} ${huCode(t)} ${resolveNode(origin(t))} ${resolveNode(destination(t))} ${catalog.equipmentLabel(t.equipmentId)} ${t.correlationId ?? ''} ${t.detail ?? ''}`
          }
          searchPlaceholder="Search tasks…"
          initialSort={{ key: 'createdAt', dir: 'desc' }}
          empty={`No transports${(scope !== DEFAULT_SCOPE || family || equipmentId) ? ' match the current filters' : ' yet'}.`}
          columns={[
            {
              key: 'id',
              header: 'Task',
              sortable: true,
              sortValue: (t) => t.id,
              render: (t) => (
                <span style={{ fontFamily: 'var(--font-mono)' }} title={t.id}>{shortId(t.id)}</span>
              ),
            },
            {
              key: 'status',
              header: 'Status',
              sortable: true,
              sortValue: (t) => t.status,
              render: (t) => <span className={badgeClass(t.status)}>{t.status}</span>,
            },
            {
              key: 'family',
              header: 'Family',
              sortable: true,
              sortValue: (t) => t.family,
              render: (t) => t.family,
            },
            {
              key: 'command',
              header: 'Command',
              sortable: true,
              sortValue: (t) => t.command,
              render: (t) => (
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: '.8rem' }}>{t.command}</span>
              ),
            },
            {
              key: 'hu',
              header: 'HU',
              sortable: true,
              sortValue: (t) => huCode(t),
              render: (t) => (
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: '.8rem' }}>{huCode(t)}</span>
              ),
            },
            {
              key: 'route',
              header: 'Origin → Destination',
              render: (t) => (
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: '.8rem' }}>{resolveNode(origin(t))} → {resolveNode(destination(t))}</span>
              ),
            },
            {
              key: 'nextHop',
              header: 'Next hop',
              render: (t) => (
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: '.8rem' }}>{resolveNode(nextHop(t))}</span>
              ),
            },
            {
              key: 'equipmentId',
              header: 'Equipment',
              render: (t) => <span title={t.equipmentId ?? ''}>{catalog.equipmentLabel(t.equipmentId)}</span>,
            },
            {
              key: 'correlationId',
              header: 'Correlation',
              render: (t) => (
                <span style={{ fontFamily: 'var(--font-mono)' }} title={t.correlationId ? `${t.correlationId}\n\n${CORRELATION_HELP}` : CORRELATION_HELP}>{shortId(t.correlationId)}</span>
              ),
            },
            {
              key: 'detail',
              header: 'Detail',
              render: (t) => (
                <span className="muted" style={{ display: 'inline-block', maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', verticalAlign: 'bottom' }} title={t.detail ?? ''}>{t.detail ?? '—'}</span>
              ),
            },
            {
              key: 'createdAt',
              header: 'Created',
              sortable: true,
              sortValue: (t) => t.createdAt,
              render: (t) => (
                <span className="muted" style={{ fontSize: '.8rem', whiteSpace: 'nowrap' }}>{formatTime(t.createdAt)}</span>
              ),
            },
          ]}
        />
      </div>

      {selected && (
        <TransportTraceDialog
          task={selected}
          warehouseId={warehouseId}
          resolveNode={resolveNode}
          equipmentLabel={catalog.equipmentLabel}
          onClose={() => setSelected(null)}
        />
      )}
    </div>
  )
}

function Field({ label, children }: { label: React.ReactNode; children: React.ReactNode }) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: '.3rem' }}>
      <span className="muted" style={{ fontFamily: 'var(--font-mono)', fontSize: '.65rem', letterSpacing: '.12em', textTransform: 'uppercase' }}>{label}</span>
      {children}
    </label>
  )
}

const dialogBackdrop: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(0,0,0,.55)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 1000,
  padding: '1rem',
}

// One labelled value in the detail grid.
function Detail({ label, value, mono = true }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div>
      <div className="muted" style={{ fontFamily: 'var(--font-mono)', fontSize: '.62rem', letterSpacing: '.1em', textTransform: 'uppercase', marginBottom: '.15rem' }}>{label}</div>
      <div style={{ fontFamily: mono ? 'var(--font-mono)' : 'inherit', fontSize: '.85rem', wordBreak: 'break-word' }}>{value ?? '—'}</div>
    </div>
  )
}

// Detail + trace dialog for a single transport. Shows the clicked task's full, code-resolved
// fields and its trace. The trace prefers flow's per-HU transport-trace timeline (ADR-0007 §3.4) —
// the lifecycle of the handling unit across the induction pipeline — whenever an HU id is resolvable
// from the task; it falls back to the coarse byCorrelation device-task list when no HU id is
// available, so nothing regresses. Pretty-prints the raw payload/result for the technical detail an
// operator may need when a task fails.
function TransportTraceDialog({
  task,
  warehouseId,
  resolveNode,
  equipmentLabel,
  onClose,
}: {
  task: DeviceTask
  warehouseId: string
  resolveNode: (raw: string) => string
  equipmentLabel: Catalog['equipmentLabel']
  onClose: () => void
}) {
  // HU-trace timeline (preferred) and the device-task fallback are mutually exclusive: when the task
  // carries an HU id we render the per-HU timeline, otherwise the byCorrelation device-task list.
  const hu = huId(task)
  const [huTrace, setHuTrace] = useState<HuTraceRow[] | null>(null)
  const [trace, setTrace] = useState<DeviceTask[] | null>(null)
  const [traceError, setTraceError] = useState<string | null>(null)
  const [loadingTrace, setLoadingTrace] = useState(false)

  useEffect(() => {
    let cancelled = false
    setHuTrace(null)
    setTrace(null)
    setTraceError(null)

    // Preferred path: flow's per-HU transport-trace timeline.
    if (hu) {
      setLoadingTrace(true)
      listHuTrace(hu, warehouseId)
        .then((rows) => {
          if (cancelled) return
          // An empty timeline (e.g. a non-induction transport) falls back to the device-task trace.
          if (rows.length) {
            setHuTrace(rows)
          } else if (task.correlationId) {
            return loadTaskTrace()
          } else {
            setTrace([task])
          }
        })
        .catch((e) => {
          if (cancelled) return
          // hu-trace failed — degrade to the device-task trace rather than show nothing.
          if (task.correlationId) {
            void loadTaskTrace()
          } else {
            setTraceError(e instanceof Error ? e.message : String(e))
            setTrace([task])
          }
        })
        .finally(() => !cancelled && setLoadingTrace(false))
      return () => {
        cancelled = true
      }
    }

    // Fallback path: the byCorrelation device-task trace.
    if (!task.correlationId) {
      setTrace([task])
      return
    }
    void loadTaskTrace()
    return () => {
      cancelled = true
    }

    function loadTaskTrace(): Promise<void> {
      setLoadingTrace(true)
      setTraceError(null)
      return listTaskTrace(task.correlationId as string)
        .then((rows) => {
          if (cancelled) return
          setTrace(rows.length ? rows : [task])
        })
        .catch((e) => {
          if (cancelled) return
          setTraceError(e instanceof Error ? e.message : String(e))
          setTrace([task])
        })
        .finally(() => !cancelled && setLoadingTrace(false))
    }
  }, [task, hu, warehouseId])

  // Close on Escape.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const payload = task.payload && Object.keys(task.payload).length ? task.payload : null
  const result = task.result && Object.keys(task.result).length ? task.result : null

  return (
    <div className="dialog-backdrop" style={dialogBackdrop} onClick={onClose}>
      <div className="dialog" style={{ maxWidth: 720, width: '94%', maxHeight: '88vh', overflow: 'auto' }} onClick={(e) => e.stopPropagation()}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '1rem' }}>
          <div>
            <span className="eyebrow">Transport</span>
            <h2 style={{ margin: '.1rem 0 .25rem' }}>
              {task.command} · {task.family}
            </h2>
            <span className={badgeClass(task.status)}>{task.status}</span>
          </div>
          <button className="btn btn-ghost btn-sm" onClick={onClose} aria-label="Close">✕</button>
        </div>

        {/* Code-resolved fields */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))', gap: '.9rem', marginTop: '1.1rem' }}>
          <Detail label="Task id" value={task.id} />
          <Detail label="HU" value={huCode(task)} />
          <Detail label="Origin" value={resolveNode(origin(task))} />
          <Detail label="Destination" value={resolveNode(destination(task))} />
          <Detail label="Next hop" value={resolveNode(nextHop(task))} />
          <Detail label="Equipment" value={equipmentLabel(task.equipmentId)} mono={false} />
          <Detail label="Actor" value={task.actor || '—'} />
          <Detail label="Created" value={formatTime(task.createdAt)} mono={false} />
        </div>

        {task.detail && (
          <div style={{ marginTop: '1rem' }}>
            <Detail label="Detail" value={task.detail} mono={false} />
          </div>
        )}

        {/* Trace: prefer flow's per-HU transport-trace timeline, fall back to byCorrelation tasks. */}
        <div style={{ marginTop: '1.4rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '.5rem', flexWrap: 'wrap' }}>
            <h3 style={{ margin: 0 }}>{huTrace ? 'HU timeline' : 'Trace'}</h3>
            <InfoTip
              text={huTrace ? HU_TRACE_HELP : CORRELATION_HELP}
              example={huTrace ? 'Requested → Retrieved → Arrived → Queued → Done' : 'Retrieve → Convey → Store'}
            />
            {huTrace && hu ? (
              <span className="muted" style={{ fontFamily: 'var(--font-mono)', fontSize: '.72rem' }}>
                HU {task.payload && huCode(task) !== '—' ? `${huCode(task)} · ` : ''}{hu}
              </span>
            ) : (
              task.correlationId && (
                <span className="muted" style={{ fontFamily: 'var(--font-mono)', fontSize: '.72rem' }}>
                  correlation {task.correlationId}
                </span>
              )
            )}
          </div>
          <p className="muted" style={{ marginTop: '.35rem', fontSize: '.8rem' }}>
            {huTrace
              ? 'The handling unit’s lifecycle across the induction pipeline, in time order — each row is a recorded transport event.'
              : task.correlationId
                ? 'Every device task that shares this transport’s correlation id, in dispatch order.'
                : 'This task has no correlation id, so it stands on its own — no linked steps.'}
          </p>

          {loadingTrace && <p className="muted">Loading trace…</p>}
          {traceError && (
            <div className="alert" style={{ background: 'rgba(255,107,94,.15)', color: '#ff8a80', border: '1px solid rgba(255,107,94,.3)' }}>
              Couldn’t load the full trace: {traceError}
            </div>
          )}

          {/* Preferred: per-HU transport-trace timeline. */}
          {huTrace && (
            <ol style={{ listStyle: 'none', margin: '.5rem 0 0', padding: 0, display: 'flex', flexDirection: 'column', gap: '.4rem' }}>
              {huTrace.map((row, i) => {
                const isCurrent = row.taskId != null && row.taskId === task.id
                const from = row.fromPoint ? resolveNode(row.fromPoint) : null
                const to = row.toPoint ? resolveNode(row.toPoint) : null
                return (
                  <li
                    key={row.id}
                    className="glass"
                    style={{
                      padding: '.6rem .8rem',
                      display: 'flex',
                      alignItems: 'center',
                      gap: '.75rem',
                      flexWrap: 'wrap',
                      outline: isCurrent ? '1px solid var(--accent, #8DC63F)' : 'none',
                    }}
                  >
                    <span className="muted" style={{ fontFamily: 'var(--font-mono)', fontSize: '.75rem', minWidth: '1.4rem' }}>{i + 1}</span>
                    <span className={traceEventBadgeClass(row.event)}>{row.event}</span>
                    {row.point && (
                      <span style={{ fontFamily: 'var(--font-mono)', fontSize: '.8rem' }}>{resolveNode(row.point)}</span>
                    )}
                    {(from || to) && (
                      <span style={{ fontFamily: 'var(--font-mono)', fontSize: '.78rem' }}>
                        {from ?? '—'} → {to ?? '—'}
                      </span>
                    )}
                    {row.decision && (
                      <span className="muted" style={{ fontSize: '.75rem' }}>{row.decision}</span>
                    )}
                    <span className="muted" style={{ fontSize: '.75rem', marginLeft: 'auto', whiteSpace: 'nowrap' }}>{formatTime(row.ts)}</span>
                    {isCurrent && <span className="muted" style={{ fontSize: '.7rem' }}>(this task)</span>}
                  </li>
                )
              })}
            </ol>
          )}

          {/* Fallback: byCorrelation device-task list. */}
          {!huTrace && trace && (
            <ol style={{ listStyle: 'none', margin: '.5rem 0 0', padding: 0, display: 'flex', flexDirection: 'column', gap: '.4rem' }}>
              {trace.map((step, i) => {
                const isCurrent = step.id === task.id
                return (
                  <li
                    key={step.id}
                    className="glass"
                    style={{
                      padding: '.6rem .8rem',
                      display: 'flex',
                      alignItems: 'center',
                      gap: '.75rem',
                      flexWrap: 'wrap',
                      outline: isCurrent ? '1px solid var(--accent, #8DC63F)' : 'none',
                    }}
                  >
                    <span className="muted" style={{ fontFamily: 'var(--font-mono)', fontSize: '.75rem', minWidth: '1.4rem' }}>{i + 1}</span>
                    <span className={badgeClass(step.status)}>{step.status}</span>
                    <span style={{ fontFamily: 'var(--font-mono)', fontSize: '.8rem' }}>{step.family} · {step.command}</span>
                    <span style={{ fontFamily: 'var(--font-mono)', fontSize: '.78rem' }}>
                      {resolveNode(origin(step))} → {resolveNode(destination(step))}
                    </span>
                    <span className="muted" style={{ fontSize: '.75rem', marginLeft: 'auto', whiteSpace: 'nowrap' }}>{formatTime(step.createdAt)}</span>
                    {isCurrent && <span className="muted" style={{ fontSize: '.7rem' }}>(this task)</span>}
                  </li>
                )
              })}
            </ol>
          )}
        </div>

        {/* Raw technical payload/result */}
        {(payload || result) && (
          <div style={{ marginTop: '1.4rem', display: 'grid', gap: '1rem' }}>
            {payload && (
              <div>
                <div className="muted" style={{ fontFamily: 'var(--font-mono)', fontSize: '.62rem', letterSpacing: '.1em', textTransform: 'uppercase', marginBottom: '.3rem' }}>Payload</div>
                <pre style={{ margin: 0, padding: '.7rem .8rem', background: 'rgba(0,0,0,.25)', borderRadius: 8, fontSize: '.75rem', overflow: 'auto' }}>{JSON.stringify(payload, null, 2)}</pre>
              </div>
            )}
            {result && (
              <div>
                <div className="muted" style={{ fontFamily: 'var(--font-mono)', fontSize: '.62rem', letterSpacing: '.1em', textTransform: 'uppercase', marginBottom: '.3rem' }}>Result</div>
                <pre style={{ margin: 0, padding: '.7rem .8rem', background: 'rgba(0,0,0,.25)', borderRadius: 8, fontSize: '.75rem', overflow: 'auto' }}>{JSON.stringify(result, null, 2)}</pre>
              </div>
            )}
          </div>
        )}

        <div className="dialog-actions" style={{ marginTop: '1.4rem' }}>
          <button className="btn btn-ghost" onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  )
}
