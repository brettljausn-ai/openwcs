import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useCatalog } from '../lib/useCatalog'
import Select from '../ui/Select'
import DataTable from '../ui/DataTable'
import InfoTip from '../ui/InfoTip'
import { DeviceTask, listDeviceTasks } from './api'

// Transport overview (build.md §8): a live view of the device tasks the flow-orchestrator
// dispatches to equipment adapters. Lists active and recent tasks with their lifecycle
// status (REQUESTED → DISPATCHED → COMPLETED/FAILED), equipment family, origin → destination
// and route/next-hop pulled from the task payload, plus summary tiles and auto-refresh.
// UI-only against the existing /api/flow/device-tasks read endpoint.

const STATUSES = ['REQUESTED', 'DISPATCHED', 'COMPLETED', 'FAILED'] as const
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

function badgeClass(status: string): string {
  const kind = STATUS_BADGE[status] ?? 'muted'
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

function origin(task: DeviceTask): string {
  return field(task, ['from', 'origin', 'fromNode', 'source', 'sourceNode']) ?? '—'
}

function destination(task: DeviceTask): string {
  return field(task, ['to', 'destination', 'toNode', 'target', 'targetNode']) ?? '—'
}

function nextHop(task: DeviceTask): string {
  return field(task, ['nextHop', 'next', 'exitCode', 'route']) ?? '—'
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
  const [status, setStatus] = useState('')
  const [family, setFamily] = useState('')
  const [equipmentId, setEquipmentId] = useState('')
  const [equipment, setEquipment] = useState<{ id: string; code?: string; name?: string }[]>([])
  const [autoRefresh, setAutoRefresh] = useState(true)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)

  // Keep the latest filters in a ref so the polling interval always reads fresh values
  // without being torn down and recreated on every keystroke.
  const filtersRef = useRef({ warehouseId, status, family, equipmentId })
  filtersRef.current = { warehouseId, status, family, equipmentId }

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const data = await listDeviceTasks({ ...filtersRef.current, limit: 200 })
      setTasks(data)
      setError(null)
      setLastUpdated(new Date())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [])

  // Initial load + reload when filters change.
  useEffect(() => {
    refresh()
  }, [refresh, warehouseId, status, family, equipmentId])

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

  const counts = useMemo(() => {
    const c = { REQUESTED: 0, DISPATCHED: 0, COMPLETED: 0, FAILED: 0, active: 0, total: tasks.length }
    for (const t of tasks) {
      if (t.status in c) c[t.status as keyof typeof c] += 1
      if (ACTIVE_STATUSES.has(t.status)) c.active += 1
    }
    return c
  }, [tasks])

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
          <p>Live device-task / transport view across equipment — origin → destination, route and lifecycle status.</p>
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
        <Field label={<>Status <InfoTip text="Filter device tasks by lifecycle state: REQUESTED → DISPATCHED → COMPLETED or FAILED. Leave on 'All statuses' to see every task." example="DISPATCHED" /></>}>
          <Select
            ariaLabel="Status"
            value={status}
            onChange={(v) => setStatus(v)}
            options={[{ value: '', label: 'All statuses' }, ...STATUSES.map((s) => ({ value: s, label: s }))]}
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
        {(status || family || equipmentId) && (
          <button className="btn btn-ghost btn-sm" onClick={() => { setStatus(''); setFamily(''); setEquipmentId('') }}>
            Clear filters
          </button>
        )}
      </div>

      {error && <div className="alert" style={{ background: 'rgba(255,107,94,.15)', color: '#ff8a80', border: '1px solid rgba(255,107,94,.3)' }}>{error}</div>}

      {/* Task table */}
      <div className="glass" style={{ padding: 0, overflow: 'auto' }}>
        <DataTable<DeviceTask>
          rows={tasks}
          rowKey={(t) => t.id}
          search={(t) =>
            `${t.id} ${t.status} ${t.family} ${t.command} ${origin(t)} ${destination(t)} ${nextHop(t)} ${t.equipmentId ?? ''} ${t.correlationId ?? ''} ${t.detail ?? ''}`
          }
          searchPlaceholder="Search tasks…"
          initialSort={{ key: 'createdAt', dir: 'desc' }}
          empty={`No device tasks${(status || family || equipmentId) ? ' match the current filters' : ' yet'}.`}
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
              key: 'route',
              header: 'Origin → Destination',
              render: (t) => (
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: '.8rem' }}>{origin(t)} → {destination(t)}</span>
              ),
            },
            {
              key: 'nextHop',
              header: 'Next hop',
              render: (t) => (
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: '.8rem' }}>{nextHop(t)}</span>
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
                <span style={{ fontFamily: 'var(--font-mono)' }} title={t.correlationId ?? ''}>{shortId(t.correlationId)}</span>
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
