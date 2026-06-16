// Client-driven runtime for a configurable process on the handheld (spec §9). Reads :key from the
// route (/process/:key), starts an instance (requires connectivity), then walks the flow locally:
//
//  - SCREEN steps render via the shared ProcessScreenView; the captured value is written to the data
//    object (config.writeTo) and the walker picks the next step from transitions/next — all offline,
//    no server round-trip per screen.
//  - TASK steps are SERVER CHECKPOINTS: POST /instances/{id}/checkpoint {stepId, data}, merge the
//    returned data, advance to the returned currentStep. If the POST fails on the NETWORK, the
//    checkpoint is queued durably and the instance HOLDS at the task showing "pending sync" (the
//    task's outputs may feed downstream screens/branches, so we never guess past it). The drainer
//    posts it on reconnect and applies the result to advance the live instance.
//  - On `done` (no next step), a completion card + "← Menu" returns the operator to the menu.
//  - Resume: a persisted instance id (sessionStorage) is re-fetched via GET /instances/{id}.
//
// Rules of Hooks: every hook runs unconditionally at the top, before any early return (the #310
// crash). The branchy render below reads from already-computed state.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useT } from '../../i18n/useT'
import { useWarehouse } from '../../warehouse/WarehouseContext'
import { useCatalog } from '../../lib/useCatalog'
import ProcessScreenView from '../screens/ProcessScreenView'
import { isScreenStep, isTaskStep, type CheckpointResult, type ProcessInstance, type VerifyResult } from '../model'
import { getActiveDef, getInstance, startInstance, verifyCode } from '../api'
import { applyVerifyWrites, nextStepId, resolveLandingStep, stepOf, writeValue } from './walker'
import {
  enqueueCheckpoint,
  failedFor,
  pendingFor,
  retryFailed,
  setCheckpointApplier,
  startQueueDrainer,
  subscribe,
  type QueuedCheckpoint,
} from './checkpointQueue'

function isNetworkFailure(e: unknown): boolean {
  if (typeof navigator !== 'undefined' && navigator.onLine === false) return true
  if (e instanceof TypeError) return true
  const status = (e as { httpStatus?: number }).httpStatus
  if (status !== undefined) return status >= 500
  const msg = e instanceof Error ? e.message : String(e)
  return /failed to fetch|networkerror|load failed/i.test(msg)
}

function sessionKey(key: string): string {
  return `owcs.process.instance.${key}`
}

export default function ProcessRuntimeScreen() {
  const { key = '' } = useParams<{ key: string }>()
  const t = useT('processRuntime')
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const catalog = useCatalog(warehouseId)

  const [instance, setInstance] = useState<ProcessInstance | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [queue, setQueue] = useState<QueuedCheckpoint[]>([])

  // Verify-on-submit state for the current input screen (screens carrying a `verify` block).
  const [verifyState, setVerifyState] = useState<'idle' | 'checking' | 'notfound' | 'offline'>('idle')
  const [verifyNote, setVerifyNote] = useState<string | null>(null)
  const [resetSignal, setResetSignal] = useState(0)
  const verifyingRef = useRef(false)

  // Subscribe to the checkpoint queue + start the drainer once.
  useEffect(() => {
    startQueueDrainer()
    return subscribe(setQueue)
  }, [])

  // Register an applier so a queued checkpoint that drains later advances THIS live instance.
  useEffect(() => {
    setCheckpointApplier((instanceId: string, result: CheckpointResult) => {
      setInstance((prev) =>
        prev && prev.instanceId === instanceId
          ? { ...prev, data: { ...prev.data, ...result.data }, currentStep: result.done ? '' : result.currentStep }
          : prev,
      )
    })
    return () => setCheckpointApplier(null)
  }, [])

  // Start (or resume) the instance when the key / warehouse is ready.
  const begin = useCallback(async () => {
    if (!warehouseId) return
    setLoading(true)
    setError(null)
    try {
      const stored = sessionStorage.getItem(sessionKey(key))
      if (stored) {
        try {
          const resumed = await getInstance(stored)
          setInstance(resumed)
          setLoading(false)
          return
        } catch {
          sessionStorage.removeItem(sessionKey(key)) // stale id — start fresh
        }
      }
      // Starting requires connectivity (spec §9). Prefetch the active def too (SW-cached).
      await getActiveDef(key).catch(() => null)
      const inst = await startInstance(key, warehouseId)
      sessionStorage.setItem(sessionKey(key), inst.instanceId)
      setInstance(inst)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [key, warehouseId])

  useEffect(() => {
    void begin()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, warehouseId])

  const def = instance?.def ?? null
  const currentStep = instance?.currentStep ?? ''
  const step = useMemo(() => (def && currentStep ? stepOf(def, currentStep) : undefined), [def, currentStep])
  const done = !!instance && (currentStep === '' || !step)

  const pending = instance ? pendingFor(instance.instanceId) : undefined
  const failed = instance ? failedFor(instance.instanceId) : undefined
  void queue // re-render trigger via subscription

  // Advance helper: set currentStep to id (or done) — shared by screen submit + task advance.
  // Honours `skipWhen`: resolve through any steps whose skip condition is true so we never render a
  // skipped step (loop-guarded by resolveLandingStep).
  const advanceTo = useCallback((nextId: string | null, data: Record<string, unknown>) => {
    setInstance((prev) => {
      if (!prev) return prev
      const landed = resolveLandingStep(prev.def, nextId, data)
      return { ...prev, data, currentStep: landed ?? '' }
    })
  }, [])

  // When an instance is first started/resumed, the server-provided currentStep may itself carry a
  // true skipWhen — resolve it once so the first rendered screen is never a skipped one.
  useEffect(() => {
    if (!instance || !instance.currentStep) return
    const landed = resolveLandingStep(instance.def, instance.currentStep, instance.data)
    if ((landed ?? '') !== instance.currentStep) {
      setInstance((prev) => (prev ? { ...prev, currentStep: landed ?? '' } : prev))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [instance?.instanceId])

  // Reset verify feedback whenever we land on a new step.
  useEffect(() => {
    setVerifyState('idle')
    setVerifyNote(null)
  }, [currentStep])

  // SCREEN submit: write value, then either advance (no verify) or resolve the scan against
  // master-data and branch on the outcome (verify block present).
  const onScreenSubmit = useCallback(
    (value: unknown) => {
      if (!instance || !step || !isScreenStep(step)) return
      const cfg = step.config
      const baseData = writeValue(instance.data, cfg.writeTo, value)

      // No verify block: the original purely-local advance.
      if (!cfg.verify) {
        advanceTo(nextStepId(step, baseData), baseData)
        return
      }

      const verify = cfg.verify
      const code = value == null ? '' : String(value)

      // Offline: hold (mirror task-step offline behaviour) — do NOT advance.
      if (typeof navigator !== 'undefined' && navigator.onLine === false) {
        setVerifyState('offline')
        setVerifyNote(null)
        return
      }
      if (verifyingRef.current) return
      verifyingRef.current = true
      setVerifyState('checking')
      setVerifyNote(null)

      const finish = () => { verifyingRef.current = false }
      const onNotVerified = () => {
        if (verify.onNotFound.mode === 'goto' && verify.onNotFound.step) {
          setVerifyState('idle')
          advanceTo(verify.onNotFound.step, baseData)
        } else {
          setVerifyState('notfound')
          setResetSignal((n) => n + 1) // clear + refocus the input
        }
      }

      void verifyCode(warehouseId, verify.kind, code)
        .then((res: VerifyResult) => {
          if (!res.found) {
            onNotVerified()
            return
          }
          const data = applyVerifyWrites(baseData, verify, res)
          setVerifyState('idle')
          setVerifyNote(res.ambiguous ? '__ambiguous__' : null)
          advanceTo(nextStepId(step, data), data)
        })
        .catch((e) => {
          // 502 / downstream error / any failure = treat as not-verified (offline if no connection).
          if (isNetworkFailure(e)) {
            setVerifyState('offline')
          } else {
            onNotVerified()
          }
        })
        .finally(finish)
    },
    [instance, step, advanceTo, warehouseId],
  )

  // TASK run: POST checkpoint; on success merge + advance; on network failure queue + HOLD.
  const runTaskRef = useRef(false)
  const runTask = useCallback(async () => {
    if (!instance || !step || !isTaskStep(step)) return
    if (runTaskRef.current) return
    runTaskRef.current = true
    setError(null)
    try {
      const res = await fetch(`/api/process-designer/instances/${encodeURIComponent(instance.instanceId)}/checkpoint`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ stepId: currentStep, data: instance.data }),
      })
      if (!res.ok) {
        const body = await res.text().catch(() => '')
        const err = new Error(body || `${res.status} ${res.statusText}`) as Error & { httpStatus?: number }
        err.httpStatus = res.status
        throw err
      }
      const result = (await res.json()) as CheckpointResult
      advanceTo(result.done ? null : result.currentStep, { ...instance.data, ...result.data })
    } catch (e) {
      if (isNetworkFailure(e)) {
        // Hold at the task: queue durably + show pending sync (do NOT advance — downstream may read
        // this task's outputs). The drainer posts it on reconnect and the applier advances us.
        await enqueueCheckpoint({ instanceId: instance.instanceId, stepId: currentStep, data: instance.data })
      } else {
        setError(e instanceof Error ? e.message : String(e)) // 4xx (e.g. RBAC) — surface it
      }
    } finally {
      runTaskRef.current = false
    }
  }, [instance, step, currentStep, advanceTo])

  // Auto-run a task step when we land on one and there is no pending/failed queue item for it.
  useEffect(() => {
    if (step && isTaskStep(step) && instance && !pending && !failed) {
      void runTask()
    }
  }, [step, instance, pending, failed, runTask])

  // Clear the resume pointer once the instance completes.
  useEffect(() => {
    if (done && instance) sessionStorage.removeItem(sessionKey(key))
  }, [done, instance, key])

  // --- render (all hooks above) --------------------------------------------------------------------

  if (!warehouseId) {
    return <Centered>{t('selectWarehouse', 'Select a warehouse in the top bar to start this process.')}</Centered>
  }
  if (loading && !instance) {
    return <Centered>{t('starting', 'Starting…')}</Centered>
  }
  if (error && !instance) {
    return (
      <Centered>
        <p className="badge badge-danger">{error}</p>
        <button className="btn btn-primary" onClick={() => void begin()} style={{ marginTop: '1rem' }}>
          {t('retry', 'Retry')}
        </button>
      </Centered>
    )
  }
  if (!instance || !def) {
    return <Centered>{t('starting', 'Starting…')}</Centered>
  }

  if (done) {
    return (
      <div className="app-content op-pd-runtime">
        <div className="glass" style={{ textAlign: 'center', padding: '3rem', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '1rem' }}>
          <div style={{ fontSize: '4rem' }}>✓</div>
          <h2 style={{ margin: 0 }}>{t('completed', 'Process complete')}</h2>
          <p className="muted" style={{ margin: 0 }}>{def.title}</p>
          <Link to="/" className="btn btn-primary btn-lg" style={{ marginTop: '.5rem' }}>{t('backToMenu', '← Menu')}</Link>
        </div>
      </div>
    )
  }

  return (
    <div className="app-content op-pd-runtime">
      {(pending || failed || error) && (
        <RuntimeBanner pending={pending} failed={failed} error={error} t={t} />
      )}

      {step && isScreenStep(step) && (
        <ProcessScreenView
          step={step}
          config={step.config}
          data={instance.data}
          schema={def.dataSchema}
          catalog={catalog}
          onSubmit={onScreenSubmit}
          verifyState={verifyState}
          verifyNote={verifyNote === '__ambiguous__' ? t('verifyAmbiguous', 'Multiple matches found.') : verifyNote}
          resetSignal={resetSignal}
          verifyLabels={{
            checking: t('verifyChecking', 'Checking…'),
            notFound: t('verifyNotFound', 'Not found, scan again'),
            offline: t('verifyOffline', 'Verification needs a connection'),
          }}
        />
      )}

      {step && isTaskStep(step) && (
        <div className="glass" style={{ textAlign: 'center', padding: '2.5rem', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '.85rem' }}>
          <div className="op-pd-spinner" aria-hidden="true" />
          <h2 style={{ margin: 0 }}>{pending ? t('pendingSync', 'Pending sync') : t('runningTask', 'Working…')}</h2>
          <p className="muted" style={{ margin: 0 }}>
            {pending
              ? t('willSyncWhenOnline', 'This step runs on the server. It will sync automatically when back online.')
              : t('runningTaskBody', 'Running {task}…').replace('{task}', step.task)}
          </p>
          {failed && (
            <button className="btn btn-primary" onClick={() => void retryFailed(failed.id)}>{t('retry', 'Retry')}</button>
          )}
        </div>
      )}
    </div>
  )
}

function RuntimeBanner({
  pending,
  failed,
  error,
  t,
}: {
  pending?: QueuedCheckpoint
  failed?: QueuedCheckpoint
  error: string | null
  t: (k: string, e: string) => string
}) {
  const danger = !!failed || !!error
  return (
    <div
      role="status"
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: '.7rem',
        padding: '.7rem 1rem',
        marginBottom: '1rem',
        borderRadius: 10,
        background: danger ? 'rgba(255,107,94,.14)' : 'rgba(244,184,96,.14)',
        border: `1px solid ${danger ? 'var(--danger)' : 'var(--warning)'}`,
      }}
    >
      <strong>{failed ? t('syncFailed', 'Sync failed') : error ? t('taskRejected', 'Task rejected') : t('pendingSync', 'Pending sync')}</strong>
      <span className="muted" style={{ fontSize: '.9rem' }}>{error ?? failed?.lastError ?? t('willSyncWhenOnline', 'Will sync when back online.')}</span>
    </div>
  )
}

function Centered({ children }: { children: React.ReactNode }) {
  return (
    <div className="app-content op-pd-runtime">
      <div className="glass" style={{ textAlign: 'center', padding: '3rem', minHeight: '40vh', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
        {children}
      </div>
    </div>
  )
}
