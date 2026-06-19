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
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { useT } from '../../i18n/useT'
import { useWarehouse } from '../../warehouse/WarehouseContext'
import { useCatalog } from '../../lib/useCatalog'
import ProcessScreenView from '../screens/ProcessScreenView'
import {
  isComputeStep,
  isDecisionStep,
  isScreenStep,
  isTaskStep,
  type CheckpointResult,
  type ProcessInstance,
  type Step,
  type VerifyConfig,
  type VerifyResult,
} from '../model'
import { getActiveDef, getInstance, getInstanceSteps, startInstance, verifyCode } from '../api'
import { applyVerifyWrites, nextStepId, resolveLanding, stepOf, writeValue } from './walker'
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
import { enqueueStep, flushStepsForInstance, startStepDrainer } from './stepQueue'

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

// The next per-instance step-event seq, persisted so a refresh on the SAME device keeps numbering
// monotonic without a server round-trip. On a NEW device / re-login we derive it from the server's
// recorded steps (max seq + 1). The server dedups on (instanceId, seq), so a slightly stale local seq
// is self-correcting.
function seqKey(instanceId: string): string {
  return `owcs.process.seq.${instanceId}`
}
function readSeq(instanceId: string): number {
  const raw = sessionStorage.getItem(seqKey(instanceId))
  const n = raw == null ? NaN : Number(raw)
  return Number.isFinite(n) ? n : 0
}
function writeSeq(instanceId: string, seq: number): void {
  try {
    sessionStorage.setItem(seqKey(instanceId), String(seq))
  } catch {
    /* best effort */
  }
}

/** The step kind string recorded on a step event (mirrors the definition: screen type, or
 *  task/compute/decision). Kept here so the runtime and the replay viewer agree. */
export function stepTypeOf(step: Step | undefined): string {
  if (!step) return 'unknown'
  if (isScreenStep(step)) return step.screen
  if (isTaskStep(step)) return `task:${step.task}`
  if (isComputeStep(step)) return 'compute'
  if (isDecisionStep(step)) return 'decision'
  return 'unknown'
}

export default function ProcessRuntimeScreen() {
  const { key = '' } = useParams<{ key: string }>()
  const [searchParams] = useSearchParams()
  // Cross-device / re-login resume: the operator's "Resume in-progress work" launcher opens
  // /process/:key?instance=<id>. When present it pins which instance to resume (taking precedence over
  // the sessionStorage pointer), so a different device or a fresh login continues that exact work.
  const resumeInstanceId = searchParams.get('instance') || ''
  const t = useT('processRuntime')
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const catalog = useCatalog(warehouseId)

  // Next step-event seq for the live instance (monotonic per instance). Held in a ref so the screen
  // submit handler always reads the latest without re-creating callbacks; mirrored into sessionStorage.
  const seqRef = useRef(0)

  const [instance, setInstance] = useState<ProcessInstance | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [queue, setQueue] = useState<QueuedCheckpoint[]>([])

  // Verify-on-submit state for the current input screen (screens carrying a `verify` block).
  const [verifyState, setVerifyState] = useState<'idle' | 'checking' | 'notfound' | 'offline'>('idle')
  const [verifyNote, setVerifyNote] = useState<string | null>(null)
  const [resetSignal, setResetSignal] = useState(0)
  const verifyingRef = useRef(false)

  // skuScan: when a resolved SKU has several UOMs the operator must pick one BEFORE we advance. We
  // hold the resolved result + the post-write data here and render a UOM picker over the screen.
  const [uomChoice, setUomChoice] = useState<{ result: VerifyResult; verify: VerifyConfig; baseData: Record<string, unknown> } | null>(null)

  // Subscribe to the checkpoint queue + start both drainers once (checkpoints AND step events).
  useEffect(() => {
    startQueueDrainer()
    startStepDrainer()
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

  // Resume an existing instance by id: FLUSH any queued step events for it first (so the server has
  // applied every advance), then GET the instance and adopt the server's EXACT current_step + data,
  // and seed the next seq from the server's recorded steps (max seq + 1). This is what makes a refresh
  // / new device / re-login continue exactly where the operator left off.
  const resume = useCallback(async (instanceId: string): Promise<ProcessInstance> => {
    await flushStepsForInstance(instanceId).catch(() => {})
    const resumed = await getInstance(instanceId)
    // Derive the next seq: prefer the server step trail, fall back to the local sessionStorage value.
    let nextSeq = readSeq(instanceId)
    try {
      const steps = await getInstanceSteps(instanceId)
      const maxSeq = steps.reduce((m, s) => Math.max(m, s.seq ?? 0), 0)
      nextSeq = Math.max(nextSeq, maxSeq + 1)
    } catch {
      // Older server / offline: keep the local seq. The server dedups on (instanceId, seq) anyway.
    }
    if (nextSeq < 1) nextSeq = 1
    seqRef.current = nextSeq
    writeSeq(instanceId, nextSeq)
    return resumed
  }, [])

  // Start (or resume) the instance when the key / warehouse is ready.
  const begin = useCallback(async () => {
    if (!warehouseId) return
    setLoading(true)
    setError(null)
    try {
      // 1) An explicit ?instance=<id> (from the resume launcher / a different device) wins.
      const pinned = resumeInstanceId || sessionStorage.getItem(sessionKey(key))
      if (pinned) {
        try {
          const resumed = await resume(pinned)
          sessionStorage.setItem(sessionKey(key), resumed.instanceId)
          setInstance(resumed)
          setLoading(false)
          return
        } catch {
          if (!resumeInstanceId) sessionStorage.removeItem(sessionKey(key)) // stale local id — start fresh
        }
      }
      // 2) No instance to resume: start a fresh one. Requires connectivity (spec §9). Prefetch the
      //    active def too (SW-cached). A new instance starts numbering step events at seq 1.
      await getActiveDef(key).catch(() => null)
      const inst = await startInstance(key, warehouseId)
      sessionStorage.setItem(sessionKey(key), inst.instanceId)
      seqRef.current = 1
      writeSeq(inst.instanceId, 1)
      setInstance(inst)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [key, warehouseId, resumeInstanceId, resume])

  useEffect(() => {
    void begin()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, warehouseId, resumeInstanceId])

  const def = instance?.def ?? null
  const currentStep = instance?.currentStep ?? ''
  const step = useMemo(() => (def && currentStep ? stepOf(def, currentStep) : undefined), [def, currentStep])
  const done = !!instance && (currentStep === '' || !step)

  const pending = instance ? pendingFor(instance.instanceId) : undefined
  const failed = instance ? failedFor(instance.instanceId) : undefined
  void queue // re-render trigger via subscription

  // Advance helper: set currentStep to id (or done) — shared by screen submit + task advance.
  // Honours `skipWhen` AND evaluates any `compute` steps along the way (they render no screen): the
  // walker resolves through them, writing their computed values into the data object, so we adopt the
  // returned data. Loop-guarded by resolveLanding.
  const advanceTo = useCallback((nextId: string | null, data: Record<string, unknown>) => {
    setInstance((prev) => {
      if (!prev) return prev
      const landed = resolveLanding(prev.def, nextId, data)
      return { ...prev, data: landed.data, currentStep: landed.stepId ?? '' }
    })
  }, [])

  // Record one screen advance durably (offline-first): allocate the next per-instance seq, enqueue a
  // step event with the step we are LEAVING + its stepType + the post-write data, then return. The
  // queue drains in the background; the operator is never blocked. The server keeps the exact
  // current_step + data from the latest event, so a refresh / new device / re-login resumes here.
  const recordStep = useCallback(
    (instanceId: string, leftStep: Step | undefined, leftStepId: string, data: Record<string, unknown>) => {
      const seq = seqRef.current
      seqRef.current = seq + 1
      writeSeq(instanceId, seqRef.current)
      void enqueueStep({
        instanceId,
        seq,
        stepId: leftStepId,
        stepType: stepTypeOf(leftStep),
        data,
      })
    },
    [],
  )

  // When an instance is first started/resumed, the server-provided currentStep may itself carry a
  // true skipWhen or be a compute step — resolve it once so the first rendered screen is never a
  // skipped/compute one, and adopt any values a compute step writes.
  useEffect(() => {
    if (!instance || !instance.currentStep) return
    const landed = resolveLanding(instance.def, instance.currentStep, instance.data)
    if ((landed.stepId ?? '') !== instance.currentStep) {
      setInstance((prev) => (prev ? { ...prev, data: landed.data, currentStep: landed.stepId ?? '' } : prev))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [instance?.instanceId])

  // Reset verify feedback whenever we land on a new step.
  useEffect(() => {
    setVerifyState('idle')
    setVerifyNote(null)
    setUomChoice(null)
  }, [currentStep])

  // SCREEN submit: write value, then either advance (no verify) or resolve the scan against
  // master-data and branch on the outcome (verify block present).
  const onScreenSubmit = useCallback(
    (value: unknown) => {
      if (!instance || !step || !isScreenStep(step)) return
      const cfg = step.config
      const baseData = writeValue(instance.data, cfg.writeTo, value)

      // No verify block: the original purely-local advance. Record the advance durably first.
      if (!cfg.verify) {
        recordStep(instance.instanceId, step, currentStep, baseData)
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
          recordStep(instance.instanceId, step, currentStep, baseData)
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
          // skuScan with several UOMs: hold and let the operator pick one before advancing.
          if (res.needsUomChoice && (res.uoms?.length ?? 0) > 0) {
            setVerifyState('idle')
            setVerifyNote(res.ambiguous ? '__ambiguous__' : null)
            setUomChoice({ result: res, verify, baseData })
            return
          }
          const data = applyVerifyWrites(baseData, verify, res)
          setVerifyState('idle')
          setVerifyNote(res.ambiguous ? '__ambiguous__' : null)
          recordStep(instance.instanceId, step, currentStep, data)
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
    [instance, step, currentStep, advanceTo, recordStep, warehouseId],
  )

  // skuScan UOM picker: the operator picked a unit — write it into the uomCode-mapped variable, apply
  // the other resolved write mappings, then continue to the next step. Idempotent: a no-op if there
  // is no pending choice / the step changed underneath us.
  const onPickUom = useCallback(
    (uomCode: string) => {
      if (!uomChoice || !step || !isScreenStep(step)) return
      const { result, verify, baseData } = uomChoice
      const data = applyVerifyWrites(baseData, verify, result, uomCode)
      setUomChoice(null)
      if (instance) recordStep(instance.instanceId, step, currentStep, data)
      advanceTo(nextStepId(step, data), data)
    },
    [uomChoice, step, currentStep, instance, advanceTo, recordStep],
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
      const merged = { ...instance.data, ...result.data }
      // Record the task step in the durable trail too (for the replay viewer). The checkpoint endpoint
      // already persisted + advanced server-side; this step event is idempotent on (instanceId, seq)
      // and only adds the task to the recorded trail.
      recordStep(instance.instanceId, step, currentStep, merged)
      advanceTo(result.done ? null : result.currentStep, merged)
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
  }, [instance, step, currentStep, advanceTo, recordStep])

  // Auto-run a task step when we land on one and there is no pending/failed queue item for it.
  useEffect(() => {
    if (step && isTaskStep(step) && instance && !pending && !failed) {
      void runTask()
    }
  }, [step, instance, pending, failed, runTask])

  // Clear the resume pointer + seq counter once the instance completes.
  useEffect(() => {
    if (done && instance) {
      sessionStorage.removeItem(sessionKey(key))
      sessionStorage.removeItem(seqKey(instance.instanceId))
    }
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

      {step && isScreenStep(step) && !uomChoice && (
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

      {uomChoice && (
        <div className="op-pd-uom-picker glass" style={{ marginTop: '1rem', padding: '1.5rem', display: 'flex', flexDirection: 'column', gap: '1rem' }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '.3rem' }}>
            <h2 style={{ margin: 0, fontSize: '1.4rem' }}>{t('uomTitle', 'Choose a unit of measure')}</h2>
            <p className="muted" style={{ margin: 0 }}>
              {t('uomBody', 'This item can be booked in several units. Pick the one you are handling.')}
            </p>
            {uomChoice.result.name && (
              <p className="muted" style={{ margin: 0, fontSize: '.9rem' }}>{uomChoice.result.name}</p>
            )}
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '.6rem' }}>
            {(uomChoice.result.uoms ?? []).map((u) => (
              <button
                key={u.code}
                type="button"
                className="btn btn-ghost btn-lg"
                style={{ minHeight: 60, fontSize: '1.1rem', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}
                onClick={() => onPickUom(u.code)}
              >
                <span>{u.code}</span>
                {u.baseUnit && <span className="badge" style={{ fontSize: '.75rem' }}>{t('uomBase', 'base unit')}</span>}
              </button>
            ))}
          </div>
        </div>
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
