// Read-only instance monitoring (desktop, Engineering; spec §14 Phase 2 "instance history /
// monitoring"). Lists process instances from GET /api/process-designer/instances (newest-first,
// filterable by process key + status); clicking a row opens a detail view of the data object, the
// current step, and a best-effort step trail derived from the pinned definition.
//
// Rules of Hooks: every hook is declared unconditionally at the top before any early return.

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useT } from '../../i18n/useT'
import { getInstance, listInstances, listProcesses } from '../api'
import { isComputeStep, isScreenStep, type InstanceSummary, type ProcessInstance, type ProcessSummary } from '../model'

const STATUS_OPTIONS = ['', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED']

function fmtTime(iso?: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso)
  return d.toLocaleString()
}

export default function ProcessInstancesScreen() {
  const t = useT('processInstances')

  const [rows, setRows] = useState<InstanceSummary[]>([])
  const [processes, setProcesses] = useState<ProcessSummary[]>([])
  const [keyFilter, setKeyFilter] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [selected, setSelected] = useState<string | null>(null)
  const [detail, setDetail] = useState<ProcessInstance | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await listInstances({
        processKey: keyFilter || undefined,
        status: statusFilter || undefined,
        limit: 200,
      })
      setRows(data)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setRows([])
    } finally {
      setLoading(false)
    }
  }, [keyFilter, statusFilter])

  useEffect(() => { void load() }, [load])

  // Process keys for the filter dropdown (degrade quietly).
  useEffect(() => {
    let cancelled = false
    listProcesses()
      .then((p) => { if (!cancelled) setProcesses(p) })
      .catch(() => {})
    return () => { cancelled = true }
  }, [])

  // Load the selected instance detail.
  useEffect(() => {
    if (!selected) { setDetail(null); return }
    let cancelled = false
    setDetailLoading(true)
    setDetailError(null)
    getInstance(selected)
      .then((inst) => { if (!cancelled) setDetail(inst) })
      .catch((e) => { if (!cancelled) setDetailError(e instanceof Error ? e.message : String(e)) })
      .finally(() => { if (!cancelled) setDetailLoading(false) })
    return () => { cancelled = true }
  }, [selected])

  // Best-effort step trail from the pinned definition: the linear/default-next chain up to the
  // current step (branches not taken are omitted; this is a readable approximation, not a replay).
  const trail = useMemo(() => {
    if (!detail?.def) return []
    const def = detail.def
    const order: string[] = []
    const seen = new Set<string>()
    let id: string | undefined = def.start
    while (id && def.steps[id] && !seen.has(id)) {
      seen.add(id)
      order.push(id)
      if (id === detail.currentStep) break
      id = def.steps[id].next
    }
    if (detail.currentStep && !order.includes(detail.currentStep)) order.push(detail.currentStep)
    return order
  }, [detail])

  // --- render (all hooks above) -------------------------------------------------------------------

  const detailKey = keyOf(rows, selected)

  return (
    <div className="app-content op-pd-instances">
      <header className="op-pd-instances-head">
        <div>
          <p className="eyebrow">{t('eyebrow', 'Engineering · Process designer')}</p>
          <h1 style={{ margin: '.1rem 0' }}>{t('title', 'Process instances')}</h1>
          <p className="muted" style={{ margin: 0 }}>{t('subtitle', 'Monitor runs of configurable handheld processes (read-only).')}</p>
        </div>
        <div className="op-pd-instances-filters">
          <label className="op-pd-field">
            <span className="op-pd-field-label">{t('processKey', 'Process key')}</span>
            <select value={keyFilter} onChange={(e) => setKeyFilter(e.target.value)}>
              <option value="">{t('allProcesses', 'All processes')}</option>
              {processes.map((p) => (<option key={p.processKey} value={p.processKey}>{p.title || p.processKey}</option>))}
            </select>
          </label>
          <label className="op-pd-field">
            <span className="op-pd-field-label">{t('status', 'Status')}</span>
            <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
              {STATUS_OPTIONS.map((s) => (<option key={s} value={s}>{s || t('allStatuses', 'All statuses')}</option>))}
            </select>
          </label>
          <button className="btn btn-ghost btn-sm" onClick={() => void load()}>{t('refresh', 'Refresh')}</button>
        </div>
      </header>

      {error && <div className="op-pd-statusline op-pd-issue-err">{error}</div>}

      <div className="op-pd-instances-grid">
        <div className="op-pd-instances-table glass">
          {loading ? (
            <p className="muted" style={{ padding: '1rem' }}>{t('loading', 'Loading…')}</p>
          ) : rows.length === 0 ? (
            <p className="muted" style={{ padding: '1rem' }}>{t('none', 'No instances match.')}</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>{t('process', 'Process')}</th>
                  <th>{t('version', 'Ver')}</th>
                  <th>{t('status', 'Status')}</th>
                  <th>{t('currentStep', 'Current step')}</th>
                  <th>{t('startedBy', 'Started by')}</th>
                  <th>{t('started', 'Started')}</th>
                  <th>{t('updated', 'Updated')}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr
                    key={r.instanceId}
                    className={selected === r.instanceId ? 'is-selected' : ''}
                    onClick={() => setSelected(r.instanceId)}
                    style={{ cursor: 'pointer' }}
                  >
                    <td>{r.processKey}</td>
                    <td>v{r.defVersion}</td>
                    <td><span className={`op-pd-instance-status status-${(r.status || '').toLowerCase()}`}>{r.status}</span></td>
                    <td>{r.currentStep || '—'}</td>
                    <td>{r.startedBy || '—'}</td>
                    <td>{fmtTime(r.startedAt)}</td>
                    <td>{fmtTime(r.updatedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        <aside className="op-pd-instance-detail glass">
          {!selected ? (
            <p className="muted" style={{ padding: '1rem' }}>{t('selectRow', 'Select an instance to inspect its data and step trail.')}</p>
          ) : detailLoading ? (
            <p className="muted" style={{ padding: '1rem' }}>{t('loading', 'Loading…')}</p>
          ) : detailError ? (
            <p className="op-pd-issue-err" style={{ padding: '1rem' }}>{detailError}</p>
          ) : detail ? (
            <div style={{ padding: '1rem' }}>
              <h3 style={{ marginTop: 0 }}>{detail.def?.title || detailKey || selected}</h3>
              <p className="muted" style={{ marginTop: 0, fontSize: '.8rem', wordBreak: 'break-all' }}>{selected}</p>

              <h4>{t('currentStep', 'Current step')}</h4>
              <p><code>{detail.currentStep || t('completed', '(completed)')}</code></p>

              {trail.length > 0 && (
                <>
                  <h4>{t('stepTrail', 'Step trail')}</h4>
                  <ol className="op-pd-trail">
                    {trail.map((id) => {
                      const step = detail.def?.steps[id]
                      const isCurrent = id === detail.currentStep
                      const kind = step
                        ? isScreenStep(step) ? step.screen
                          : isComputeStep(step) ? 'compute'
                          : `task: ${step.task}`
                        : '?'
                      return (
                        <li key={id} className={isCurrent ? 'is-current' : ''}>
                          <code>{id}</code> <span className="muted">{kind}</span>
                          {isCurrent && <span className="op-pd-trail-here"> ◀ {t('here', 'here')}</span>}
                        </li>
                      )
                    })}
                  </ol>
                </>
              )}

              <h4>{t('dataObject', 'Data object')}</h4>
              {Object.keys(detail.data ?? {}).length === 0 ? (
                <p className="muted">{t('noData', 'No data captured yet.')}</p>
              ) : (
                <table className="op-pd-dataobj-table">
                  <tbody>
                    {Object.entries(detail.data).map(([k, v]) => (
                      <tr key={k}>
                        <th>{k}</th>
                        <td>{v == null ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          ) : null}
        </aside>
      </div>
    </div>
  )
}

function keyOf(rows: InstanceSummary[], id: string | null): string | undefined {
  return rows.find((r) => r.instanceId === id)?.processKey
}
