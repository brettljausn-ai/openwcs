import { useCallback, useEffect, useMemo, useState } from 'react'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useDemoMode, seedDemoCountTasks } from '../demo/useDemoMode'
import { useCatalog, type Catalog } from '../lib/useCatalog'
import Select from '../ui/Select'
import InfoTip from '../ui/InfoTip'

// Stock counting (cycle counting) — drives the /api/counting service: ad-hoc + scheduled
// (ABC-cadence) count tasks, a count-capture form (counted qty per line), variance vs the
// inventory-expected snapshot, recount, and reconcile (within tolerance auto-posts a
// StockAdjusted adjustment; out-of-tolerance spawns a recount task). UI-only against the
// existing endpoints; Bearer is attached by the global fetch interceptor.

const BASE = '/api/counting'

type TaskStatus = 'OPEN' | 'COUNTED' | 'RECONCILED' | 'RECOUNT'
type LineStatus = 'PENDING' | 'COUNTED' | 'APPROVED' | 'RECOUNT' | 'ADJUSTED'

interface CountTask {
  id: string
  warehouseId: string
  scopeType?: string
  scopeRef?: string
  countType?: 'BLIND' | 'VARIANCE'
  origin?: 'AD_HOC' | 'SCHEDULED' | 'RECOUNT'
  scheduleId?: string
  parentTaskId?: string
  tolerance?: number
  gtpStationId?: string
  processInstanceId?: string
  status: TaskStatus
  assignedTo?: string
  countedBy?: string
  countedAt?: string
  reconciledBy?: string
  reconciledAt?: string
  routingStatus?: string
  routingReason?: string
}

interface CountLine {
  countLineId: string
  locationId?: string
  skuId?: string
  batchId?: string
  uomCode?: string
  expectedQty?: number | null
  countedQty?: number | null
  variance?: number | null
  status: LineStatus
  adjustmentEventId?: string
}

interface CountSchedule {
  id: string
  warehouseId: string
  name: string
  scopeType?: string
  scopeRef?: string
  abcClass?: 'A' | 'B' | 'C'
  countType?: 'BLIND' | 'VARIANCE'
  cadenceDays?: number
  tolerance?: number
  lastRunAt?: string
  nextDueAt?: string
  status?: 'ACTIVE' | 'PAUSED'
}

interface ReconciliationResult {
  taskId: string
  status: string
  approvedLines: number
  adjustedLines: number
  recountLines: number
  recountTaskId?: string
}

interface Problem {
  title?: string
  detail?: string
  status?: number
}

async function readError(res: Response): Promise<string> {
  try {
    const p = (await res.json()) as Problem
    return p.detail || p.title || `Request failed (${res.status})`
  } catch {
    return `Request failed (${res.status})`
  }
}

async function jsonOrThrow<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(await readError(res))
  return (await res.json()) as T
}

// --- API helpers (bare fetch) ---
function listTasks(warehouseId: string, status?: string): Promise<CountTask[]> {
  const q = new URLSearchParams({ warehouseId })
  if (status) q.set('status', status)
  return fetch(`${BASE}/tasks?${q}`).then((r) => jsonOrThrow<CountTask[]>(r))
}
function getLines(taskId: string): Promise<CountLine[]> {
  return fetch(`${BASE}/tasks/${taskId}/lines`).then((r) => jsonOrThrow<CountLine[]>(r))
}
async function deleteTask(taskId: string): Promise<void> {
  const res = await fetch(`${BASE}/tasks/${taskId}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(await readError(res))
}
function getResults(taskId: string): Promise<CountLine[]> {
  return fetch(`${BASE}/tasks/${taskId}/results`).then((r) => jsonOrThrow<CountLine[]>(r))
}
function claimTask(taskId: string): Promise<CountTask> {
  return fetch(`${BASE}/tasks/${taskId}/claim`, { method: 'POST' }).then((r) => jsonOrThrow<CountTask>(r))
}
function submitCounts(taskId: string, entries: { countLineId: string; countedQty: number }[]): Promise<CountTask> {
  return fetch(`${BASE}/tasks/${taskId}/counts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ entries }),
  }).then((r) => jsonOrThrow<CountTask>(r))
}
function reconcileTask(taskId: string): Promise<ReconciliationResult> {
  return fetch(`${BASE}/tasks/${taskId}/reconcile`, { method: 'POST' }).then((r) =>
    jsonOrThrow<ReconciliationResult>(r),
  )
}
function listSchedules(warehouseId: string): Promise<CountSchedule[]> {
  const q = new URLSearchParams({ warehouseId })
  return fetch(`${BASE}/schedules?${q}`).then((r) => jsonOrThrow<CountSchedule[]>(r))
}
function createSchedule(body: Record<string, unknown>): Promise<CountSchedule> {
  return fetch(`${BASE}/schedules`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }).then((r) => jsonOrThrow<CountSchedule>(r))
}
function generateDue(warehouseId?: string): Promise<CountTask[]> {
  const q = warehouseId ? `?${new URLSearchParams({ warehouseId })}` : ''
  return fetch(`${BASE}/schedules/generate${q}`, { method: 'POST' }).then((r) => jsonOrThrow<CountTask[]>(r))
}

// --- presentation helpers ---
const TASK_STATUSES: TaskStatus[] = ['OPEN', 'COUNTED', 'RECONCILED', 'RECOUNT']

function taskBadge(status: TaskStatus): string {
  switch (status) {
    case 'OPEN':
      return 'badge-info'
    case 'COUNTED':
      return 'badge-warning'
    case 'RECONCILED':
      return 'badge-success'
    case 'RECOUNT':
      return 'badge-danger'
    default:
      return 'badge'
  }
}

function lineBadge(status: LineStatus): string {
  switch (status) {
    case 'APPROVED':
    case 'ADJUSTED':
      return 'badge-success'
    case 'COUNTED':
      return 'badge-warning'
    case 'RECOUNT':
      return 'badge-danger'
    default:
      return 'badge'
  }
}

// ASRS count-tote routing status: ROUTED ok, FAILED bad (retried in the background), PENDING in
// flight, NOT_REQUIRED shown as a plain "n/a" badge. The routingReason rides along as a tooltip.
function routingBadge(status?: string): string {
  switch (status) {
    case 'ROUTED':
      return 'badge-success'
    case 'FAILED':
      return 'badge-danger'
    case 'PENDING':
      return 'badge-warning'
    default:
      return 'badge'
  }
}

function routingLabel(status?: string): string {
  if (!status) return '—'
  return status === 'NOT_REQUIRED' ? 'n/a' : status
}

// A variance of 0 is "ok"; anything non-zero is a discrepancy.
function varianceBadge(variance: number | null | undefined): string {
  if (variance == null) return 'badge'
  return variance === 0 ? 'badge-success' : 'badge-danger'
}

function short(id?: string): string {
  if (!id) return '—'
  return id.length > 10 ? `${id.slice(0, 8)}…` : id
}

function fmtTime(iso?: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString()
}

function num(v: number | null | undefined): string {
  return v == null ? '—' : String(v)
}

export default function CountingScreen() {
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const { enabled: demoEnabled } = useDemoMode()
  const catalog = useCatalog(warehouseId)

  // Resolve a scope reference (a location/SKU id) to its code based on the scope type.
  function scopeLabel(scopeType?: string, scopeRef?: string): string {
    if (!scopeRef) return scopeType || '—'
    const ref = scopeType === 'SKU' ? catalog.skuCode(scopeRef) : scopeType === 'LOCATION' ? catalog.locationCode(scopeRef) : short(scopeRef)
    return `${scopeType || '—'} · ${ref}`
  }
  const [statusFilter, setStatusFilter] = useState('')
  const [tasks, setTasks] = useState<CountTask[]>([])
  const [schedules, setSchedules] = useState<CountSchedule[]>([])
  const [loading, setLoading] = useState(false)
  const [seeding, setSeeding] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const [captureTask, setCaptureTask] = useState<CountTask | null>(null)
  const [pendingDelete, setPendingDelete] = useState<CountTask | null>(null)
  const [scheduleOpen, setScheduleOpen] = useState(false)

  const loadTasks = useCallback(async () => {
    if (!warehouseId.trim()) {
      setError('No warehouse selected.')
      return
    }
    setLoading(true)
    setError(null)
    try {
      const [t, s] = await Promise.all([
        listTasks(warehouseId.trim(), statusFilter || undefined),
        listSchedules(warehouseId.trim()).catch(() => [] as CountSchedule[]),
      ])
      setTasks(t)
      setSchedules(s)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [warehouseId, statusFilter])

  // Reload when the selected warehouse (top bar) or status filter changes.
  useEffect(() => {
    if (warehouseId.trim()) loadTasks()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [statusFilter, warehouseId])

  function flash(msg: string) {
    setNotice(msg)
    window.setTimeout(() => setNotice(null), 6000)
  }

  async function onGenerate() {
    setError(null)
    try {
      const emitted = await generateDue(warehouseId.trim() || undefined)
      flash(`ABC sweep emitted ${emitted.length} due task(s).`)
      await loadTasks()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  // Demo-only: create one sample count task over existing demo stock.
  async function addDemoCountTask() {
    if (!warehouseId.trim()) return
    setSeeding(true)
    setError(null)
    try {
      const res = await seedDemoCountTasks(warehouseId.trim(), 1)
      flash(`Added ${res.created} demo count task.`)
      await loadTasks()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSeeding(false)
    }
  }

  async function onReconcile(task: CountTask) {
    setError(null)
    try {
      const r = await reconcileTask(task.id)
      const parts = [`${r.approvedLines} approved`, `${r.adjustedLines} adjusted`, `${r.recountLines} for recount`]
      flash(
        `Reconciled ${short(task.id)} → ${r.status}: ${parts.join(', ')}.` +
          (r.recountTaskId ? ` Recount task ${short(r.recountTaskId)} spawned.` : ''),
      )
      await loadTasks()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  async function onDelete(task: CountTask) {
    setPendingDelete(null)
    setError(null)
    try {
      await deleteTask(task.id)
      flash(`Deleted count task ${short(task.id)}.`)
      await loadTasks()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  return (
    <div className="app-content">
      <div className="page-head">
        <div className="eyebrow">openWCS</div>
        <h1>Stock counting</h1>
        <p>
          Cycle / stock counting — schedule ABC-cadence sweeps and ad-hoc count tasks, capture counted
          quantities, review variance against the system snapshot, recount out-of-tolerance cells, and
          reconcile (within tolerance auto-posts a stock adjustment).
        </p>
      </div>

      <div className="toolbar">
        <Select
          ariaLabel="Status"
          style={{ width: 170 }}
          value={statusFilter}
          onChange={(v) => setStatusFilter(v)}
          options={[
            { value: '', label: 'All statuses' },
            ...TASK_STATUSES.map((s) => ({ value: s, label: s })),
          ]}
        />
        <button className="btn btn-primary" onClick={loadTasks} disabled={loading}>
          {loading ? 'Loading…' : 'Load'}
        </button>
        <span className="spacer" />
        <button className="btn btn-outline" onClick={() => setScheduleOpen(true)}>
          New schedule
        </button>
        <button className="btn btn-ghost" onClick={onGenerate} title="Run the ABC-cadence sweep for due schedules">
          Run ABC sweep
        </button>
        {demoEnabled && (
          <button
            className="btn btn-outline"
            onClick={addDemoCountTask}
            disabled={!warehouseId.trim() || seeding}
            title="Demo mode: create a sample count task over existing demo stock"
          >
            {seeding ? 'Adding…' : 'Add count task'}
          </button>
        )}
      </div>

      {error && <div className="alert alert-danger">{error}</div>}
      {notice && (
        <div
          className="alert"
          style={{ background: 'rgba(141,198,63,.12)', color: '#8DC63F', border: '1px solid rgba(141,198,63,.3)' }}
        >
          {notice}
        </div>
      )}

      <SchedulesPanel schedules={schedules} />

      <section className="glass card-pad" style={{ marginTop: '1.25rem' }}>
        <h3 style={{ marginTop: 0 }}>
          Count tasks {tasks.length > 0 && <span className="muted">({tasks.length})</span>}
        </h3>
        {tasks.length === 0 ? (
          <p className="muted" style={{ margin: 0 }}>
            {warehouseId.trim()
              ? 'No count tasks for this warehouse / filter.'
              : 'Load a warehouse to see its count tasks.'}
          </p>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table>
              <thead>
                <tr>
                  <th>Task</th>
                  <th>Status</th>
                  <th>Routing</th>
                  <th>Type</th>
                  <th>Origin</th>
                  <th>Scope</th>
                  <th>Tol.</th>
                  <th>Assigned</th>
                  <th>Counted</th>
                  <th style={{ textAlign: 'right' }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {tasks.map((t) => (
                  <tr key={t.id}>
                    <td title={t.id}>{short(t.id)}</td>
                    <td>
                      <span className={`badge ${taskBadge(t.status)}`}>{t.status}</span>
                    </td>
                    <td title={t.routingReason || undefined}>
                      <span className={`badge ${routingBadge(t.routingStatus)}`}>
                        {routingLabel(t.routingStatus)}
                      </span>
                    </td>
                    <td>{t.countType || '—'}</td>
                    <td>{t.origin || '—'}</td>
                    <td title={t.scopeRef}>{scopeLabel(t.scopeType, t.scopeRef)}</td>
                    <td>{num(t.tolerance)}</td>
                    <td>{t.assignedTo || '—'}</td>
                    <td title={fmtTime(t.countedAt)}>{t.countedBy || '—'}</td>
                    <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                      {(t.status === 'OPEN' || t.status === 'RECOUNT') && (
                        <button className="btn btn-sm btn-primary" onClick={() => setCaptureTask(t)}>
                          Capture
                        </button>
                      )}
                      {t.status === 'COUNTED' && (
                        <button
                          className="btn btn-sm btn-primary"
                          onClick={() => onReconcile(t)}
                          style={{ marginLeft: 6 }}
                        >
                          Reconcile
                        </button>
                      )}
                      <button className="btn btn-sm btn-ghost" onClick={() => setCaptureTask(t)} style={{ marginLeft: 6 }}>
                        Lines
                      </button>
                      {t.status === 'OPEN' && (
                        <button
                          className="btn btn-sm btn-ghost"
                          onClick={() => setPendingDelete(t)}
                          style={{ marginLeft: 6 }}
                          title="Delete this count task (only allowed before it is counted)"
                        >
                          Delete
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {pendingDelete && (
        <ConfirmDialog
          title="Delete count task"
          message={`Delete count task ${short(pendingDelete.id)}? This is only allowed before it is counted, and cannot be undone.`}
          confirmLabel="Delete"
          onCancel={() => setPendingDelete(null)}
          onConfirm={() => onDelete(pendingDelete)}
        />
      )}

      {captureTask && (
        <CaptureDialog
          task={captureTask}
          catalog={catalog}
          onClose={() => setCaptureTask(null)}
          onDone={async (msg) => {
            setCaptureTask(null)
            if (msg) flash(msg)
            await loadTasks()
          }}
          onError={(m) => setError(m)}
        />
      )}

      {scheduleOpen && (
        <ScheduleDialog
          warehouseId={warehouseId.trim()}
          onClose={() => setScheduleOpen(false)}
          onCreated={async (s) => {
            setScheduleOpen(false)
            flash(`Schedule "${s.name}" created (every ${s.cadenceDays ?? '—'} day(s)).`)
            await loadTasks()
          }}
          onError={(m) => setError(m)}
        />
      )}
    </div>
  )
}

function SchedulesPanel({ schedules }: { schedules: CountSchedule[] }) {
  if (schedules.length === 0) return null
  return (
    <section className="glass card-pad">
      <h3 style={{ marginTop: 0 }}>
        Count schedules <span className="muted">({schedules.length})</span>
      </h3>
      <div style={{ overflowX: 'auto' }}>
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Status</th>
              <th>Scope</th>
              <th>ABC</th>
              <th>Type</th>
              <th>Cadence</th>
              <th>Tol.</th>
              <th>Last run</th>
              <th>Next due</th>
            </tr>
          </thead>
          <tbody>
            {schedules.map((s) => (
              <tr key={s.id}>
                <td>{s.name}</td>
                <td>
                  <span className={`badge ${s.status === 'PAUSED' ? 'badge-warning' : 'badge-success'}`}>
                    {s.status || 'ACTIVE'}
                  </span>
                </td>
                <td title={s.scopeRef}>
                  {s.scopeType || '—'}
                  {s.scopeRef ? ` · ${short(s.scopeRef)}` : ''}
                </td>
                <td>{s.abcClass || '—'}</td>
                <td>{s.countType || '—'}</td>
                <td>{s.cadenceDays != null ? `${s.cadenceDays}d` : '—'}</td>
                <td>{num(s.tolerance)}</td>
                <td>{fmtTime(s.lastRunAt)}</td>
                <td>{fmtTime(s.nextDueAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

function CaptureDialog({
  task,
  catalog,
  onClose,
  onDone,
  onError,
}: {
  task: CountTask
  catalog: Catalog
  onClose: () => void
  onDone: (msg?: string) => void
  onError: (m: string) => void
}) {
  const [lines, setLines] = useState<CountLine[]>([])
  const [counts, setCounts] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [localError, setLocalError] = useState<string | null>(null)

  // A reconciled task shows final results (expected + variance + adjustment); an open/recount task
  // shows count-capture inputs; a counted task shows the recorded counts read-only.
  const reconciled = task.status === 'RECONCILED'
  // Blind counts withhold the expected qty until reconciled — the operator must not see it.
  const showExpected = task.countType !== 'BLIND' || reconciled
  const editable = task.status === 'OPEN' || task.status === 'RECOUNT'

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    const fetcher = reconciled ? getResults(task.id) : getLines(task.id)
    fetcher
      .then((ls) => {
        if (cancelled) return
        setLines(ls)
        const seed: Record<string, string> = {}
        ls.forEach((l) => {
          if (l.countedQty != null) seed[l.countLineId] = String(l.countedQty)
        })
        setCounts(seed)
      })
      .catch((e) => !cancelled && setLocalError(e instanceof Error ? e.message : String(e)))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [task.id, reconciled])

  const enteredCount = useMemo(
    () => Object.values(counts).filter((v) => v.trim() !== '' && !Number.isNaN(Number(v))).length,
    [counts],
  )

  async function onSubmit() {
    const entries = Object.entries(counts)
      .filter(([, v]) => v.trim() !== '' && !Number.isNaN(Number(v)))
      .map(([countLineId, v]) => ({ countLineId, countedQty: Number(v) }))
    if (entries.length === 0) {
      setLocalError('Enter at least one counted quantity.')
      return
    }
    setBusy(true)
    setLocalError(null)
    try {
      if (task.status === 'OPEN') {
        // Claim the open task so it is assigned to the acting operator before counts land.
        await claimTask(task.id).catch(() => undefined)
      }
      await submitCounts(task.id, entries)
      onDone(`Submitted ${entries.length} count(s) for task ${short(task.id)} → COUNTED.`)
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      setLocalError(msg)
      onError(msg)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="dialog-backdrop" style={backdrop} onClick={onClose}>
      <div className="dialog" style={{ maxWidth: 880, width: '92%' }} onClick={(e) => e.stopPropagation()}>
        <h2>
          {editable ? 'Capture count' : reconciled ? 'Reconciled results' : 'Count lines'} · {short(task.id)}{' '}
          <span className={`badge ${taskBadge(task.status)}`} style={{ marginLeft: 8 }}>
            {task.status}
          </span>
        </h2>
        <p className="muted" style={{ marginTop: 0 }}>
          {task.countType === 'BLIND'
            ? 'Blind count — expected quantity and variance are withheld until reconciled.'
            : 'Variance count — expected quantity is shown alongside your count.'}
        </p>

        {localError && <div className="alert alert-danger">{localError}</div>}

        {loading ? (
          <p className="muted">Loading lines…</p>
        ) : lines.length === 0 ? (
          <p className="muted">No lines on this task.</p>
        ) : (
          <div style={{ overflowX: 'auto', maxHeight: '50vh' }}>
            <table>
              <thead>
                <tr>
                  <th>Location</th>
                  <th>SKU</th>
                  <th>Batch</th>
                  <th>UoM</th>
                  {showExpected && <th>Expected</th>}
                  <th>
                    Counted{' '}
                    <InfoTip
                      text="The physical quantity you actually counted at this location/SKU/batch, in the shown UoM. Compared to the system snapshot to derive variance."
                      example="48"
                    />
                  </th>
                  <th>Variance</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {lines.map((l) => (
                  <tr key={l.countLineId}>
                    <td title={l.locationId}>{catalog.locationCode(l.locationId)}</td>
                    <td title={l.skuId}>{catalog.skuLabel(l.skuId)}</td>
                    <td title={l.batchId}>{l.batchId ? short(l.batchId) : '—'}</td>
                    <td>{l.uomCode || '—'}</td>
                    {showExpected && <td>{num(l.expectedQty)}</td>}
                    <td>
                      {editable ? (
                        <input
                          className="form-control"
                          style={{ width: 90, padding: '.3rem .5rem' }}
                          type="number"
                          step="any"
                          value={counts[l.countLineId] ?? ''}
                          onChange={(e) => setCounts({ ...counts, [l.countLineId]: e.target.value })}
                          placeholder="qty"
                        />
                      ) : (
                        num(l.countedQty)
                      )}
                    </td>
                    <td>
                      {l.variance == null ? (
                        '—'
                      ) : (
                        <span className={`badge ${varianceBadge(l.variance)}`}>
                          {l.variance > 0 ? `+${l.variance}` : l.variance}
                        </span>
                      )}
                    </td>
                    <td>
                      <span className={`badge ${lineBadge(l.status)}`}>{l.status}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="dialog-actions">
          <button className="btn btn-ghost" onClick={onClose} disabled={busy}>
            Close
          </button>
          {editable && (
            <button className="btn btn-primary" onClick={onSubmit} disabled={busy || enteredCount === 0}>
              {busy ? 'Submitting…' : `Submit counts (${enteredCount})`}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

function ScheduleDialog({
  warehouseId,
  onClose,
  onCreated,
  onError,
}: {
  warehouseId: string
  onClose: () => void
  onCreated: (s: CountSchedule) => void
  onError: (m: string) => void
}) {
  const [form, setForm] = useState({
    name: '',
    scopeType: 'ABC_CLASS',
    scopeRef: '',
    abcClass: 'A',
    countType: 'VARIANCE',
    cadenceDays: 30,
    tolerance: '',
    nextDueAt: '',
  })
  const [busy, setBusy] = useState(false)
  const [localError, setLocalError] = useState<string | null>(null)

  const needsAbc = form.scopeType === 'ABC_CLASS'

  async function onCreate() {
    if (!warehouseId) {
      setLocalError('Load a warehouse first (the schedule is created for it).')
      return
    }
    if (!form.name.trim()) {
      setLocalError('Name is required.')
      return
    }
    if (!form.cadenceDays || form.cadenceDays < 1) {
      setLocalError('Cadence must be at least 1 day.')
      return
    }
    setBusy(true)
    setLocalError(null)
    try {
      const body: Record<string, unknown> = {
        warehouseId,
        name: form.name.trim(),
        scopeType: form.scopeType,
        countType: form.countType,
        cadenceDays: Number(form.cadenceDays),
      }
      if (needsAbc) body.abcClass = form.abcClass
      else if (form.scopeRef.trim()) body.scopeRef = form.scopeRef.trim()
      if (form.tolerance.trim() !== '') body.tolerance = Number(form.tolerance)
      if (form.nextDueAt) body.nextDueAt = new Date(form.nextDueAt).toISOString()
      const created = await createSchedule(body)
      onCreated(created)
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      setLocalError(msg)
      onError(msg)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="dialog-backdrop" style={backdrop} onClick={onClose}>
      <div className="dialog" style={{ maxWidth: 540, width: '92%' }} onClick={(e) => e.stopPropagation()}>
        <h2>New count schedule</h2>
        <p className="muted" style={{ marginTop: 0 }}>
          ABC-cadence sweep — emits a count task each time the cadence comes due.
        </p>

        {localError && <div className="alert alert-danger">{localError}</div>}

        <div style={{ display: 'grid', gap: '.75rem' }}>
          <label style={fieldLabel}>
            Name{' '}
            <InfoTip
              text="A human-readable name for this count schedule, shown in the schedules list. Pick something that identifies its scope and cadence."
              example="A-class weekly"
            />
            <input
              className="form-control"
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="e.g. A-class weekly"
            />
          </label>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '.75rem' }}>
            <label style={fieldLabel}>
              Scope{' '}
              <InfoTip
                text="What this schedule sweeps: an ABC class, or a specific zone, block, location, or SKU. Non-ABC scopes use the Scope ref below to target an entity."
                example="ABC_CLASS"
              />
              <Select
                ariaLabel="Scope"
                value={form.scopeType}
                onChange={(v) => setForm({ ...form, scopeType: v })}
                options={['ABC_CLASS', 'LOCATION', 'SKU', 'ZONE', 'BLOCK'].map((s) => ({
                  value: s,
                  label: s,
                }))}
              />
            </label>
            {needsAbc ? (
              <label style={fieldLabel}>
                ABC class{' '}
                <InfoTip
                  text="Which ABC velocity class to count. A items move fastest and are counted most often; C items are slow movers counted least often."
                  example="A"
                />
                <Select
                  ariaLabel="ABC class"
                  value={form.abcClass}
                  onChange={(v) => setForm({ ...form, abcClass: v })}
                  options={['A', 'B', 'C'].map((c) => ({ value: c, label: c }))}
                />
              </label>
            ) : (
              <label style={fieldLabel}>
                Scope ref (UUID){' '}
                <InfoTip
                  text="The UUID of the specific zone, block, location, or SKU to count for the chosen scope. Leave blank to count all entities of that scope type."
                  example="3f2a9c10-7b4e-4d21-9f88-12ab34cd56ef"
                />
                <input
                  className="form-control"
                  value={form.scopeRef}
                  onChange={(e) => setForm({ ...form, scopeRef: e.target.value })}
                  placeholder="optional"
                />
              </label>
            )}
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '.75rem' }}>
            <label style={fieldLabel}>
              Count type{' '}
              <InfoTip
                text="BLIND hides the expected quantity from the counter (more accurate); VARIANCE shows the system quantity alongside the count for quick reconciliation."
                example="VARIANCE"
              />
              <Select
                ariaLabel="Count type"
                value={form.countType}
                onChange={(v) => setForm({ ...form, countType: v })}
                options={['VARIANCE', 'BLIND'].map((c) => ({ value: c, label: c }))}
              />
            </label>
            <label style={fieldLabel}>
              Cadence (days){' '}
              <InfoTip
                text="How often (in days) this schedule comes due and emits a new count task. Shorter cadences mean more frequent counts. Must be at least 1."
                example="30"
              />
              <input
                className="form-control"
                type="number"
                min={1}
                value={form.cadenceDays}
                onChange={(e) => setForm({ ...form, cadenceDays: Number(e.target.value) })}
              />
            </label>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '.75rem' }}>
            <label style={fieldLabel}>
              Tolerance{' '}
              <InfoTip
                text="Allowed count-vs-system variance. Lines within tolerance auto-post a stock adjustment on reconcile; out-of-tolerance lines spawn a recount. Blank = exact match required."
                example="2"
              />
              <input
                className="form-control"
                type="number"
                step="any"
                value={form.tolerance}
                onChange={(e) => setForm({ ...form, tolerance: e.target.value })}
                placeholder="optional"
              />
            </label>
            <label style={fieldLabel}>
              Next due (optional){' '}
              <InfoTip
                text="When this schedule should first come due. Leave blank to start the cadence clock from now; set a date/time to delay the first sweep."
                example="2026-06-15 08:00"
              />
              <input
                className="form-control"
                type="datetime-local"
                value={form.nextDueAt}
                onChange={(e) => setForm({ ...form, nextDueAt: e.target.value })}
              />
            </label>
          </div>
        </div>

        <div className="dialog-actions">
          <button className="btn btn-ghost" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button className="btn btn-primary" onClick={onCreate} disabled={busy}>
            {busy ? 'Creating…' : 'Create schedule'}
          </button>
        </div>
      </div>
    </div>
  )
}

const backdrop: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(0,0,0,.55)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 1000,
  padding: '1rem',
}

// In-app confirm dialog (matches the CaptureDialog/ScheduleDialog modal styling) — replaces native confirm().
function ConfirmDialog({
  title,
  message,
  confirmLabel,
  onConfirm,
  onCancel,
}: {
  title: string
  message: string
  confirmLabel: string
  onConfirm: () => void
  onCancel: () => void
}) {
  return (
    <div className="dialog-backdrop" style={backdrop} onClick={onCancel}>
      <div className="dialog" style={{ maxWidth: 460, width: '92%' }} onClick={(e) => e.stopPropagation()}>
        <h2>{title}</h2>
        <p className="muted" style={{ marginTop: 0 }}>
          {message}
        </p>
        <div className="dialog-actions">
          <button className="btn btn-ghost" onClick={onCancel}>
            Cancel
          </button>
          <button className="btn btn-danger" onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}

const fieldLabel: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: '.35rem',
  fontSize: '.8125rem',
  color: 'var(--text-dim)',
}
