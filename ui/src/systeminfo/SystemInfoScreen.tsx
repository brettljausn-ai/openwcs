import { useCallback, useEffect, useRef, useState } from 'react'
import DataTable from '../ui/DataTable'
import InfoTip from '../ui/InfoTip'
import { listServices, type ServiceStatus } from './api'

const REFRESH_MS = 10000

// Map a health status to a badge class. UP → success, DOWN/unreachable → danger, else warning.
function statusBadge(status: string): string {
  const s = (status || '').toUpperCase()
  if (s === 'UP') return 'badge badge-success'
  if (s === 'DOWN' || s === 'UNREACHABLE' || s === 'OUT_OF_SERVICE') return 'badge badge-danger'
  return 'badge badge-warning'
}

function fmtTime(iso: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return isNaN(d.getTime()) ? iso : d.toLocaleString()
}

export default function SystemInfoScreen() {
  const [services, setServices] = useState<ServiceStatus[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [autoRefresh, setAutoRefresh] = useState(true)
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const data = await listServices()
      // Stable order: unhealthy first, then by name, so problems surface at the top.
      data.sort((a, b) => {
        const au = a.status?.toUpperCase() === 'UP' ? 1 : 0
        const bu = b.status?.toUpperCase() === 'UP' ? 1 : 0
        return au - bu || a.name.localeCompare(b.name)
      })
      setServices(data)
      setError(null)
      setLastUpdated(new Date())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [])

  // Keep refresh stable for the interval without re-subscribing each tick.
  const refreshRef = useRef(refresh)
  refreshRef.current = refresh
  useEffect(() => {
    refresh()
  }, [refresh])
  useEffect(() => {
    if (!autoRefresh) return
    const id = window.setInterval(() => refreshRef.current(), REFRESH_MS)
    return () => window.clearInterval(id)
  }, [autoRefresh])

  const up = services.filter((s) => s.status?.toUpperCase() === 'UP').length
  const total = services.length
  const allUp = total > 0 && up === total

  return (
    <div className="app-content">
      <div className="page-head">
        <div className="eyebrow">openWCS · Administration</div>
        <h1>System info</h1>
        <p>Version, health and build of every service and device adapter.</p>
      </div>

      {error && <div className="alert alert-danger" style={{ marginBottom: '1rem' }}>{error}</div>}

      <section className="glass card-pad" style={{ marginBottom: '1rem' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '.6rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '.6rem' }}>
            <span className={`badge ${allUp ? 'badge-success' : up === 0 ? 'badge-danger' : 'badge-warning'}`}>
              {up}/{total} up
            </span>
            <span style={{ color: 'var(--text-dim)', fontSize: '.8rem' }}>
              {lastUpdated ? `Checked ${lastUpdated.toLocaleTimeString()}` : 'Checking…'}
            </span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '.8rem' }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: '.35rem', fontSize: '.8rem' }}>
              <input type="checkbox" checked={autoRefresh} onChange={(e) => setAutoRefresh(e.target.checked)} />
              Auto-refresh
              <InfoTip text={`Re-checks every ${REFRESH_MS / 1000}s.`} />
            </label>
            <button type="button" className="btn btn-outline btn-sm" onClick={refresh} disabled={loading}>
              {loading ? 'Checking…' : 'Refresh'}
            </button>
          </div>
        </div>
      </section>

      <DataTable<ServiceStatus>
        columns={[
          { key: 'name', header: 'Service', sortable: true, sortValue: (s) => s.name, render: (s) => <strong>{s.name}</strong> },
          {
            key: 'kind',
            header: 'Kind',
            sortable: true,
            sortValue: (s) => s.kind,
            render: (s) => <span className="badge">{s.kind === 'go' ? 'Go adapter' : 'Java'}</span>,
          },
          {
            key: 'status',
            header: 'Health',
            sortable: true,
            sortValue: (s) => s.status,
            render: (s) => <span className={statusBadge(s.status)}>{s.status || 'UNKNOWN'}</span>,
          },
          { key: 'version', header: 'Version', render: (s) => s.version || '—' },
          {
            key: 'commit',
            header: 'Commit',
            render: (s) => (s.commit ? <code style={{ fontSize: '.78rem' }}>{s.commit}</code> : '—'),
          },
          { key: 'buildTime', header: 'Built', sortable: true, sortValue: (s) => s.buildTime, render: (s) => fmtTime(s.buildTime) },
          {
            key: 'latencyMs',
            header: 'Probe',
            align: 'right',
            sortable: true,
            sortValue: (s) => s.latencyMs,
            render: (s) => <span style={{ color: 'var(--text-dim)' }}>{s.latencyMs} ms</span>,
          },
        ]}
        rows={services}
        rowKey={(s) => s.name}
        search={(s) => `${s.name} ${s.kind} ${s.status} ${s.version} ${s.commit}`}
        searchPlaceholder="Search services…"
        pageSize={50}
        empty={loading ? 'Checking services…' : 'No services reported.'}
      />
    </div>
  )
}
