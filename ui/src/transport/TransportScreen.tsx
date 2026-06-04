import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useWarehouse } from '../warehouse/WarehouseContext'
import Select from '../ui/Select'
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
  const [tasks, setTasks] = useState<DeviceTask[]>([])
  const [status, setStatus] = useState('')
  const [family, setFamily] = useState('')
  const [equipmentId, setEquipmentId] = useState('')
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
            Auto-refresh
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
        <Field label="Status">
          <Select
            ariaLabel="Status"
            value={status}
            onChange={(v) => setStatus(v)}
            options={[{ value: '', label: 'All statuses' }, ...STATUSES.map((s) => ({ value: s, label: s }))]}
          />
        </Field>
        <Field label="Equipment family">
          <Select
            ariaLabel="Equipment family"
            value={family}
            onChange={(v) => setFamily(v)}
            options={[{ value: '', label: 'All families' }, ...FAMILIES.map((f) => ({ value: f, label: f }))]}
          />
        </Field>
        <Field label="Equipment ID">
          <input className="form-control" style={{ width: 280 }} placeholder="any equipment (UUID)" value={equipmentId} onChange={(e) => setEquipmentId(e.target.value)} />
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
        <table>
          <thead>
            <tr>
              <th>Task</th>
              <th>Status</th>
              <th>Family</th>
              <th>Command</th>
              <th>Origin → Destination</th>
              <th>Next hop</th>
              <th>Equipment</th>
              <th>Correlation</th>
              <th>Detail</th>
              <th>Created</th>
            </tr>
          </thead>
          <tbody>
            {tasks.map((t) => (
              <tr key={t.id}>
                <td style={{ fontFamily: 'var(--font-mono)' }} title={t.id}>{shortId(t.id)}</td>
                <td><span className={badgeClass(t.status)}>{t.status}</span></td>
                <td>{t.family}</td>
                <td style={{ fontFamily: 'var(--font-mono)', fontSize: '.8rem' }}>{t.command}</td>
                <td><span style={{ fontFamily: 'var(--font-mono)', fontSize: '.8rem' }}>{origin(t)} → {destination(t)}</span></td>
                <td style={{ fontFamily: 'var(--font-mono)', fontSize: '.8rem' }}>{nextHop(t)}</td>
                <td style={{ fontFamily: 'var(--font-mono)' }} title={t.equipmentId ?? ''}>{shortId(t.equipmentId)}</td>
                <td style={{ fontFamily: 'var(--font-mono)' }} title={t.correlationId ?? ''}>{shortId(t.correlationId)}</td>
                <td className="muted" style={{ maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={t.detail ?? ''}>{t.detail ?? '—'}</td>
                <td className="muted" style={{ fontSize: '.8rem', whiteSpace: 'nowrap' }}>{formatTime(t.createdAt)}</td>
              </tr>
            ))}
            {tasks.length === 0 && !loading && (
              <tr>
                <td colSpan={10} className="muted" style={{ textAlign: 'center', padding: '2rem' }}>
                  No device tasks{(status || family || equipmentId) ? ' match the current filters' : ' yet'}.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: '.3rem' }}>
      <span className="muted" style={{ fontFamily: 'var(--font-mono)', fontSize: '.65rem', letterSpacing: '.12em', textTransform: 'uppercase' }}>{label}</span>
      {children}
    </label>
  )
}
