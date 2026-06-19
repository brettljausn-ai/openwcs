// Supervisor "RF Users" screen (Engineering): the active operators on configurable handheld
// processes, derived from the RUNNING instances grouped by assignedTo. Each row shows the operator,
// the process, the current workstep, and the start/updated times, with a per-row Reassign action
// (move the work to another operator) and a Replay action (step through the recorded trail). Reuses
// the instances-monitor table CSS + the app's standard modal dialog (no window.alert/confirm).
//
// Rules of Hooks: every hook is declared unconditionally at the top before any early return.

import { useCallback, useEffect, useMemo, useState } from 'react'
import { createPortal } from 'react-dom'
import { useT } from '../../i18n/useT'
import { listInstances, listProcesses, reassignInstance } from '../api'
import type { InstanceSummary, ProcessSummary } from '../model'
import ReplayDialog from '../replay/ReplayDialog'

function fmtTime(iso?: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso)
  return d.toLocaleString()
}

/** The operator an instance is assigned to (falls back to startedBy for older servers). */
function operatorOf(r: InstanceSummary): string {
  return r.assignedTo || r.startedBy || '—'
}

export default function RfUsersScreen() {
  const t = useT('rfUsers')

  const [rows, setRows] = useState<InstanceSummary[]>([])
  const [processes, setProcesses] = useState<ProcessSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const [reassigning, setReassigning] = useState<InstanceSummary | null>(null)
  const [replayId, setReplayId] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await listInstances({ status: 'RUNNING', limit: 500 })
      setRows(data.filter((r) => r.status === 'RUNNING'))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setRows([])
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  // Process titles for nicer labels (degrade quietly to the key).
  useEffect(() => {
    let cancelled = false
    listProcesses()
      .then((p) => { if (!cancelled) setProcesses(p) })
      .catch(() => {})
    return () => { cancelled = true }
  }, [])

  const processTitle = useCallback(
    (key: string): string => processes.find((p) => p.processKey === key)?.title || key,
    [processes],
  )

  // Active operators (distinct, sorted) for context + the reassign dialog's quick-pick list.
  const operators = useMemo(() => {
    const set = new Set<string>()
    for (const r of rows) set.add(operatorOf(r))
    return Array.from(set).filter((o) => o && o !== '—').sort((a, b) => a.localeCompare(b))
  }, [rows])

  // Rows sorted by operator then most-recently-updated, so each operator's work groups together.
  const sorted = useMemo(
    () =>
      [...rows].sort(
        (a, b) =>
          operatorOf(a).localeCompare(operatorOf(b)) ||
          (b.updatedAt || '').localeCompare(a.updatedAt || ''),
      ),
    [rows],
  )

  const onReassigned = useCallback(
    async (toUser: string) => {
      if (!reassigning) return
      try {
        await reassignInstance(reassigning.instanceId, toUser)
        setNotice(
          t('reassignedTo', 'Reassigned to {user}.').replace('{user}', toUser),
        )
        setReassigning(null)
        await load()
      } catch (e) {
        // Re-throw shape to the dialog so it can show the error inline (it owns its busy state).
        throw e
      }
    },
    [reassigning, load, t],
  )

  // --- render (all hooks above) -------------------------------------------------------------------

  return (
    <div className="app-content op-pd-instances">
      <header className="op-pd-instances-head">
        <div>
          <p className="eyebrow">{t('eyebrow', 'Engineering · Process designer')}</p>
          <h1 style={{ margin: '.1rem 0' }}>{t('title', 'RF users')}</h1>
          <p className="muted" style={{ margin: 0 }}>
            {t('subtitle', 'Active operators on handheld processes. Reassign in-progress work or replay a run.')}
          </p>
        </div>
        <div className="op-pd-instances-filters">
          <span className="op-pd-statusline">
            {t('activeCount', '{n} active').replace('{n}', String(operators.length))}
          </span>
          <button className="btn btn-ghost btn-sm" onClick={() => void load()}>{t('refresh', 'Refresh')}</button>
        </div>
      </header>

      {error && <div className="op-pd-statusline op-pd-issue-err">{error}</div>}
      {notice && <div className="op-pd-statusline" style={{ color: 'var(--herbal-lime)' }}>{notice}</div>}

      <div className="op-pd-instances-table glass">
        {loading ? (
          <p className="muted" style={{ padding: '1rem' }}>{t('loading', 'Loading…')}</p>
        ) : sorted.length === 0 ? (
          <p className="muted" style={{ padding: '1rem' }}>{t('none', 'No operators are running a handheld process right now.')}</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>{t('operator', 'Operator')}</th>
                <th>{t('process', 'Process')}</th>
                <th>{t('currentStep', 'Current workstep')}</th>
                <th>{t('started', 'Started')}</th>
                <th>{t('updated', 'Updated')}</th>
                <th>{t('actions', 'Actions')}</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((r) => (
                <tr key={r.instanceId}>
                  <td>{operatorOf(r)}</td>
                  <td>{processTitle(r.processKey)}</td>
                  <td>{r.currentStep || '—'}</td>
                  <td>{fmtTime(r.startedAt)}</td>
                  <td>{fmtTime(r.updatedAt)}</td>
                  <td>
                    <div style={{ display: 'flex', gap: '.4rem' }}>
                      <button className="btn btn-ghost btn-sm" onClick={() => { setNotice(null); setReassigning(r) }}>
                        {t('reassign', 'Reassign')}
                      </button>
                      <button className="btn btn-ghost btn-sm" onClick={() => setReplayId(r.instanceId)}>
                        {t('replay', 'Replay')}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {reassigning && (
        <ReassignDialog
          instance={reassigning}
          operators={operators.filter((o) => o !== operatorOf(reassigning))}
          processTitle={processTitle}
          onSubmit={onReassigned}
          onClose={() => setReassigning(null)}
        />
      )}

      {replayId && (
        <ReplayDialog
          instanceId={replayId}
          title={processTitle(sorted.find((r) => r.instanceId === replayId)?.processKey || '')}
          onClose={() => setReplayId(null)}
        />
      )}
    </div>
  )
}

// Styled reassign dialog (no window.prompt/confirm): pick an existing active operator OR type a
// username, then confirm. Owns its own busy + error state.
function ReassignDialog({
  instance,
  operators,
  processTitle,
  onSubmit,
  onClose,
}: {
  instance: InstanceSummary
  operators: string[]
  processTitle: (key: string) => string
  onSubmit: (toUser: string) => Promise<void>
  onClose: () => void
}) {
  const t = useT('rfUsers')
  const [toUser, setToUser] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async () => {
    const target = toUser.trim()
    if (!target) {
      setError(t('pickTarget', 'Choose or enter an operator to reassign to.'))
      return
    }
    setBusy(true)
    setError(null)
    try {
      await onSubmit(target)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return createPortal(
    <div className="modal-backdrop" onClick={onClose}>
      <div className="dialog glass" onClick={(e) => e.stopPropagation()}>
        <h2>{t('reassignTitle', 'Reassign work')}</h2>
        <p className="muted" style={{ marginTop: 0 }}>
          {t('reassignBody', 'Move {process} (currently with {operator}) to another operator.')
            .replace('{process}', processTitle(instance.processKey))
            .replace('{operator}', operatorOf(instance))}
        </p>

        {operators.length > 0 && (
          <div className="md-field">
            <label>{t('pickOperator', 'Active operators')}</label>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '.4rem' }}>
              {operators.map((o) => (
                <button
                  key={o}
                  type="button"
                  className={`btn btn-sm ${toUser === o ? 'btn-primary' : 'btn-ghost'}`}
                  onClick={() => setToUser(o)}
                >
                  {o}
                </button>
              ))}
            </div>
          </div>
        )}

        <div className="md-field">
          <label>{t('targetUser', 'Operator username')}</label>
          <input
            type="text"
            value={toUser}
            autoFocus
            placeholder={t('targetUserPlaceholder', 'Type a username')}
            onChange={(e) => setToUser(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') void submit() }}
          />
        </div>

        {error && <p className="op-pd-issue-err" style={{ marginBottom: 0 }}>{error}</p>}

        <div className="dialog-actions">
          <button className="btn btn-ghost" onClick={onClose} disabled={busy}>{t('cancel', 'Cancel')}</button>
          <button className="btn btn-primary" onClick={() => void submit()} disabled={busy}>
            {busy ? t('reassigning', 'Reassigning…') : t('confirmReassign', 'Reassign')}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  )
}
