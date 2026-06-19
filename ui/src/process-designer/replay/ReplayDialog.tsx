// Step-by-step replay of a recorded process instance (supervisor tool). Loads the ordered step
// events (GET /instances/{id}/steps) plus the pinned instance definition, then lets a supervisor walk
// prev/next through each recorded step. For SCREEN steps it REUSES the real handheld renderer
// (ProcessScreenView, disabled) with the data captured at that step, so the supervisor sees exactly
// what the operator saw — there is no second, cruder screen renderer. For task/compute/decision steps
// (which have no screen) it shows a clean structured panel. Below either, the full data object at that
// step. Rendered as the app's standard modal dialog (createPortal + .modal-backdrop + .dialog).
//
// Rules of Hooks: all hooks run unconditionally at the top before any early return.

import { useEffect, useMemo, useState } from 'react'
import { createPortal } from 'react-dom'
import { useT } from '../../i18n/useT'
import { getInstance, getInstanceSteps } from '../api'
import { isScreenStep, type ProcessInstance, type StepEvent } from '../model'
import ProcessScreenView from '../screens/ProcessScreenView'

function fmtTime(iso?: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso)
  return d.toLocaleString()
}

export default function ReplayDialog({
  instanceId,
  title,
  onClose,
}: {
  instanceId: string
  title?: string
  onClose: () => void
}) {
  const t = useT('replay')
  const [steps, setSteps] = useState<StepEvent[]>([])
  const [instance, setInstance] = useState<ProcessInstance | null>(null)
  const [idx, setIdx] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    Promise.all([getInstanceSteps(instanceId), getInstance(instanceId).catch(() => null)])
      .then(([s, inst]) => {
        if (cancelled) return
        const ordered = [...s].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
        setSteps(ordered)
        setInstance(inst)
        setIdx(0)
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [instanceId])

  const current = steps[idx]
  const def = instance?.def ?? null
  // The definition step for the current event (when the def is available and the id still resolves).
  const defStep = useMemo(
    () => (def && current ? def.steps[current.stepId] : undefined),
    [def, current],
  )

  const body = (
    <div className="dialog glass dialog-lg op-pd-replay" onClick={(e) => e.stopPropagation()}>
      <h2>{title ? `${t('title', 'Replay')}: ${title}` : t('title', 'Replay')}</h2>

      {loading ? (
        <p className="muted">{t('loading', 'Loading…')}</p>
      ) : error ? (
        <p className="op-pd-issue-err">{error}</p>
      ) : steps.length === 0 ? (
        <p className="muted">{t('none', 'No recorded steps for this instance yet.')}</p>
      ) : (
        <>
          <div className="op-pd-replay-bar">
            <button
              className="btn btn-ghost btn-sm"
              onClick={() => setIdx((i) => Math.max(0, i - 1))}
              disabled={idx === 0}
            >
              {t('prev', '← Prev')}
            </button>
            <span className="op-pd-replay-pos">
              {t('stepOf', 'Step {n} of {total}').replace('{n}', String(idx + 1)).replace('{total}', String(steps.length))}
            </span>
            <button
              className="btn btn-ghost btn-sm"
              onClick={() => setIdx((i) => Math.min(steps.length - 1, i + 1))}
              disabled={idx >= steps.length - 1}
            >
              {t('next', 'Next →')}
            </button>
          </div>

          {current && (
            <>
              <div className="op-pd-replay-meta">
                <span><strong>{t('stepId', 'Step')}:</strong> <code>{current.stepId}</code></span>
                <span><strong>{t('stepType', 'Type')}:</strong> <code>{current.stepType}</code></span>
                <span><strong>{t('enteredBy', 'By')}:</strong> {current.enteredBy || '—'}</span>
                <span><strong>{t('at', 'At')}:</strong> {fmtTime(current.at)}</span>
              </div>

              {/* SCREEN steps: reuse the real handheld renderer, disabled (what the operator saw). */}
              {defStep && isScreenStep(defStep) ? (
                <div className="op-pd-replay-screen">
                  <ProcessScreenView
                    step={defStep}
                    config={defStep.config}
                    data={current.data ?? {}}
                    schema={def?.dataSchema ?? []}
                    onSubmit={() => {}}
                    disabled
                    compact
                  />
                </div>
              ) : (
                <div className="op-pd-replay-nonscreen glass">
                  <p className="muted" style={{ margin: 0 }}>
                    {t('nonScreen', 'This step has no operator screen (task / compute / decision). The values captured at this step are below.')}
                  </p>
                </div>
              )}

              <h4 className="op-pd-replay-h">{t('dataObject', 'Data at this step')}</h4>
              {Object.keys(current.data ?? {}).length === 0 ? (
                <p className="muted">{t('noData', 'No data captured at this step.')}</p>
              ) : (
                <table className="op-pd-dataobj-table">
                  <tbody>
                    {Object.entries(current.data).map(([k, v]) => (
                      <tr key={k}>
                        <th>{k}</th>
                        <td>{v == null ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </>
          )}
        </>
      )}

      <div className="dialog-actions">
        <button className="btn btn-ghost" onClick={onClose}>{t('close', 'Close')}</button>
      </div>
    </div>
  )

  return createPortal(
    <div className="modal-backdrop" onClick={onClose}>
      {body}
    </div>,
    document.body,
  )
}
