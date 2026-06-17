// The WYSIWYG process designer (desktop, Engineering; spec §10). Three panes:
//   LEFT   — the flow as an ordered, structured list with screen-type icons; Question steps show
//            their branches indented with arrows. Add from a palette (6 screen types + Task),
//            reorder, click to select.
//   CENTRE — the live handheld preview: the selected step rendered in a phone-sized frame using the
//            SAME ProcessScreenView the runtime uses, placeholders resolved against sample data,
//            updating instantly. A Simulate toggle steps through the flow with fake input (the safe
//            walker drives branches; no backend writes).
//   RIGHT  — PropertiesPanel: per-step config + the data-object editor.
// Plus a toolbar: pick/create a draft, Save (PUT/POST), Validate (lists issues), Publish (blocked
// until no errors).
//
// Rules of Hooks: every hook is declared unconditionally at the top before any early return.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useT } from '../../i18n/useT'
import ProcessScreenView from '../screens/ProcessScreenView'
import {
  SCREEN_TYPE_ICONS,
  SCREEN_TYPE_LABELS,
  SCRIPT_TASK_TYPE,
  isComputeStep,
  isScreenStep,
  isTaskStep,
  type ComputeStep,
  type DataVar,
  type ProcessDefinition,
  type ScreenStep,
  type ScreenType,
  type Step,
  type TaskStep,
  type VerifyConfig,
  type VerifyKind,
} from '../model'
import { createDef, duplicateDef, exportDef, getDef, importDef, listDefs, publishDef, updateDef } from '../api'
import { useCapabilities, useTasks } from '../useProcesses'
import { hasErrors, validateDefinition, type ValidationIssue } from './validate'
import VerifyDialog from './VerifyDialog'
import TaskDialog from './TaskDialog'
import { applyVerifyWrites, nextStepId, resolveLanding, writeValue } from '../runtime/walker'
import { PREVIEW_CATALOG, sampleDataFor } from './sampleData'
import PropertiesPanel from './PropertiesPanel'

const PALETTE: (ScreenType | 'task' | 'compute')[] = ['textInput', 'numberInput', 'dateInput', 'acknowledge', 'questionYesNo', 'questionChoice', 'task', 'compute']

function emptyDef(): ProcessDefinition {
  return {
    processKey: 'new-process',
    version: 1,
    status: 'DRAFT',
    title: 'New process',
    icon: '⚑',
    dataSchema: [],
    start: '',
    steps: {},
  }
}

function newStep(type: ScreenType | 'task' | 'compute', defaultTask: string): Step {
  if (type === 'task') {
    return { type: 'task', task: defaultTask, input: {}, output: {} } satisfies TaskStep
  }
  if (type === 'compute') {
    return { type: 'compute', set: [{ var: '', expr: '' }] } satisfies ComputeStep
  }
  const cfg: ScreenStep['config'] = { header: SCREEN_TYPE_LABELS[type] }
  return { type: 'screen', screen: type, config: cfg } satisfies ScreenStep
}

function uniqueId(base: string, taken: Set<string>): string {
  let id = base
  let n = 1
  while (taken.has(id)) id = `${base}${++n}`
  return id
}

// A step's outgoing edges, in render order: branch targets first, then the default `next`.
function flowEdges(step: Step): string[] {
  const out: string[] = []
  for (const tr of step.transitions ?? []) if (tr.to) out.push(tr.to)
  if (step.next) out.push(step.next)
  return out
}

interface FlowAnalysis {
  /** Reachable steps in flow order (DFS pre-order from start). */
  order: string[]
  /** Steps NOT reachable from start (orphans), in insertion order. */
  unreachable: string[]
  /** Back-edges that form a loop, keyed "from→to" (target is an ancestor on the DFS stack). */
  loopBack: Set<string>
}

// Walk the real execution graph from `start` (following branches + `next`), so the list mirrors how
// the process actually runs. A back-edge to a step still on the current path is a LOOP (the runtime
// would cycle there); we flag those so the designer can see and manage them. Steps the walk never
// reaches are orphans, surfaced separately.
function analyzeFlow(def: ProcessDefinition): FlowAnalysis {
  const order: string[] = []
  const reachable = new Set<string>()
  const stack = new Set<string>()
  const loopBack = new Set<string>()
  const dfs = (id: string) => {
    if (!id || !def.steps[id] || reachable.has(id)) return
    reachable.add(id)
    order.push(id)
    stack.add(id)
    for (const to of flowEdges(def.steps[id])) {
      if (!def.steps[to]) continue
      if (stack.has(to)) loopBack.add(`${id}→${to}`) // points back up the current path: a loop
      else if (!reachable.has(to)) dfs(to)
    }
    stack.delete(id)
  }
  if (def.start) dfs(def.start)
  const unreachable = Object.keys(def.steps).filter((id) => !reachable.has(id))
  return { order, unreachable, loopBack }
}

export default function ProcessDesignScreen() {
  const t = useT('processDesign')
  const { tasks } = useTasks()
  const { capabilities } = useCapabilities()

  const [def, setDef] = useState<ProcessDefinition>(() => emptyDef())
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [defs, setDefs] = useState<ProcessDefinition[]>([])
  const [status, setStatus] = useState<string>('')
  const [issues, setIssues] = useState<ValidationIssue[] | null>(null)
  const [dirty, setDirty] = useState(false)
  const [persisted, setPersisted] = useState(false) // has a server version (created/loaded)
  // Which configuration dialog is open (triggered from buttons by the live preview, not the panel).
  const [dialog, setDialog] = useState<null | 'verify' | 'task'>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  // Simulate mode state.
  const [simulating, setSimulating] = useState(false)
  const [simStep, setSimStep] = useState<string>('')
  const [simData, setSimData] = useState<Record<string, unknown>>({})
  // Simulate has no backend: a verify screen normally treats the scan as found; toggle this to
  // exercise the onNotFound (re-prompt / goto) path instead.
  const [simVerifyNotFound, setSimVerifyNotFound] = useState(false)

  const sampleData = useMemo(() => sampleDataFor(def.dataSchema), [def.dataSchema])
  const flow = useMemo(() => analyzeFlow(def), [def])

  // Version management: all versions of the currently-edited process key, newest first, with status.
  const versionsForKey = useMemo(
    () =>
      defs
        .filter((d) => d.processKey === def.processKey)
        .slice()
        .sort((a, b) => (b.version ?? 0) - (a.version ?? 0)),
    [defs, def.processKey],
  )
  // Only a DRAFT version is editable; viewing an ACTIVE/ARCHIVED version is read-only.
  const editable = (def.status ?? 'DRAFT') === 'DRAFT'

  const loadList = useCallback(async () => {
    try {
      setDefs(await listDefs())
    } catch (e) {
      setStatus(String(e instanceof Error ? e.message : e))
    }
  }, [])

  useEffect(() => { void loadList() }, [loadList])

  // mutate helpers
  const patchDef = useCallback((patch: Partial<ProcessDefinition>) => {
    setDef((d) => ({ ...d, ...patch }))
    setDirty(true)
    setIssues(null)
  }, [])

  const changeStep = useCallback((id: string, step: Step) => {
    setDef((d) => ({ ...d, steps: { ...d.steps, [id]: step } }))
    setDirty(true)
    setIssues(null)
  }, [])

  const changeSchema = useCallback((schema: DataVar[]) => patchDef({ dataSchema: schema }), [patchDef])

  const addStep = useCallback((type: ScreenType | 'task' | 'compute') => {
    // Default a new Task step to the first NON-script curated task (the script type is opt-in via the
    // task-type picker / AI assist, gated by capabilities).
    const defaultTask = tasks.find((tt) => tt.id !== SCRIPT_TASK_TYPE)?.id ?? 'inventory.lookup'
    setDef((d) => {
      const id = uniqueId(type === 'task' ? 'task' : type, new Set(Object.keys(d.steps)))
      const steps = { ...d.steps, [id]: newStep(type, defaultTask) }
      const start = d.start || id
      return { ...d, steps, start }
    })
    setDirty(true)
    setIssues(null)
  }, [tasks])

  const renameStep = useCallback((oldId: string, newId: string) => {
    setDef((d) => {
      if (d.steps[newId] || !d.steps[oldId]) return d
      const steps: Record<string, Step> = {}
      for (const [k, v] of Object.entries(d.steps)) {
        const step = { ...v }
        if (step.next === oldId) step.next = newId
        if (step.transitions) step.transitions = step.transitions.map((tr) => (tr.to === oldId ? { ...tr, to: newId } : tr))
        steps[k === oldId ? newId : k] = step
      }
      return { ...d, steps, start: d.start === oldId ? newId : d.start }
    })
    setSelectedId(newId)
    setDirty(true)
  }, [])

  const deleteStep = useCallback((id: string) => {
    setDef((d) => {
      const steps = { ...d.steps }
      delete steps[id]
      for (const k of Object.keys(steps)) {
        const step = { ...steps[k] }
        if (step.next === id) step.next = undefined
        if (step.transitions) step.transitions = step.transitions.filter((tr) => tr.to !== id)
        steps[k] = step
      }
      const start = d.start === id ? (Object.keys(steps)[0] ?? '') : d.start
      return { ...d, steps, start }
    })
    setSelectedId(null)
    setDirty(true)
  }, [])

  // (Manual up/down reordering was removed: the list now follows the real execution graph, so list
  // position is derived from start + next/branches, not an arbitrary order. Flow is managed via the
  // edges and "set as start".)

  // --- load / save / publish ----------------------------------------------------------------------

  const loadDef = useCallback(async (key: string, version: number) => {
    try {
      const loaded = await getDef(key, version)
      setDef(loaded)
      setSelectedId(loaded.start || Object.keys(loaded.steps)[0] || null)
      setPersisted(true)
      setDirty(false)
      setIssues(null)
      setStatus(`${t('loaded', 'Loaded')} ${key} v${version}`)
    } catch (e) {
      setStatus(String(e instanceof Error ? e.message : e))
    }
  }, [t])

  const newDraft = useCallback(() => {
    setDef(emptyDef())
    setSelectedId(null)
    setPersisted(false)
    setDirty(true)
    setIssues(null)
    setStatus(t('newDraft', 'New draft'))
  }, [t])

  const save = useCallback(async () => {
    try {
      let saved: ProcessDefinition
      if (persisted && def.version != null) {
        saved = await updateDef(def.processKey, def.version, def)
      } else {
        saved = await createDef({ processKey: def.processKey, title: def.title, icon: def.icon, dataSchema: def.dataSchema, steps: def.steps, start: def.start })
      }
      setDef(saved)
      setPersisted(true)
      setDirty(false)
      setStatus(`${t('saved', 'Saved')} ${saved.processKey} v${saved.version}`)
      await loadList()
    } catch (e) {
      setStatus(String(e instanceof Error ? e.message : e))
    }
  }, [def, persisted, loadList, t])

  const runValidate = useCallback(() => {
    const found = validateDefinition(def, tasks)
    setIssues(found)
    setStatus(found.length === 0 ? t('valid', 'No issues') : `${found.length} ${t('issues', 'issues')}`)
    return found
  }, [def, tasks, t])

  const publish = useCallback(async () => {
    const found = runValidate()
    if (hasErrors(found)) {
      setStatus(t('fixErrors', 'Fix the errors before publishing'))
      return
    }
    try {
      let version = def.version
      if (!persisted || dirty || version == null) {
        const saved = persisted && version != null
          ? await updateDef(def.processKey, version, def)
          : await createDef({ processKey: def.processKey, title: def.title, icon: def.icon, dataSchema: def.dataSchema, steps: def.steps, start: def.start })
        setDef(saved); setPersisted(true); setDirty(false); version = saved.version
      }
      if (version == null) throw new Error('No version to publish')
      const published = await publishDef(def.processKey, version)
      setDef(published)
      setStatus(`${t('published', 'Published')} ${published.processKey} v${published.version}`)
      await loadList()
    } catch (e) {
      setStatus(String(e instanceof Error ? e.message : e))
    }
  }, [def, persisted, dirty, runValidate, loadList, t])

  // --- Phase 2: duplicate / export / import -------------------------------------------------------

  // Clone the current (e.g. published) version into a new DRAFT and switch to it for editing.
  const duplicate = useCallback(async () => {
    if (!persisted || def.version == null) {
      setStatus(t('saveFirst', 'Save the definition first.'))
      return
    }
    try {
      const summary = await duplicateDef(def.processKey, def.version)
      await loadList()
      await loadDef(summary.processKey, summary.version)
      setStatus(`${t('duplicated', 'Duplicated to draft')} ${summary.processKey} v${summary.version}`)
    } catch (e) {
      setStatus(String(e instanceof Error ? e.message : e))
    }
    // loadDef is defined below; declared via the ref pattern is unnecessary — it is stable.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [def.processKey, def.version, persisted, loadList, t])

  // Download the current (saved) version as an importable JSON file.
  const exportJson = useCallback(async () => {
    if (!persisted || def.version == null) {
      setStatus(t('saveFirst', 'Save the definition first.'))
      return
    }
    try {
      const full = await exportDef(def.processKey, def.version)
      const blob = new Blob([JSON.stringify(full, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `${full.processKey}-v${full.version}.json`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
      setStatus(`${t('exported', 'Exported')} ${full.processKey} v${full.version}`)
    } catch (e) {
      setStatus(String(e instanceof Error ? e.message : e))
    }
  }, [def.processKey, def.version, persisted, t])

  // Upload a JSON file → POST /defs/import → switch to the created draft.
  const onImportFile = useCallback(async (file: File) => {
    try {
      const text = await file.text()
      let parsed: unknown
      try {
        parsed = JSON.parse(text)
      } catch {
        setStatus(t('importBadJson', 'That file is not valid JSON.'))
        return
      }
      const summary = await importDef(parsed)
      await loadList()
      await loadDef(summary.processKey, summary.version)
      setStatus(`${t('imported', 'Imported as draft')} ${summary.processKey} v${summary.version}`)
    } catch (e) {
      const err = e as Error & { httpStatus?: number }
      if (err.httpStatus === 422) setStatus(`${t('importInvalid', 'Import rejected — the model is invalid')}: ${err.message}`)
      else if (err.httpStatus === 400) setStatus(`${t('importMissing', 'Import rejected — missing processKey or title')}: ${err.message}`)
      else setStatus(String(err instanceof Error ? err.message : err))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loadList, t])

  // --- simulate -----------------------------------------------------------------------------------

  const startSim = useCallback(() => {
    const data = sampleDataFor(def.dataSchema)
    setSimulating(true)
    setSimVerifyNotFound(false)
    // Honour skipWhen + evaluate any compute steps from the very first step too (adopt their writes).
    const landed = resolveLanding(def, def.start, data)
    setSimData(landed.data)
    setSimStep(landed.stepId ?? '')
  }, [def])

  const stopSim = useCallback(() => { setSimulating(false); setSimStep('') }, [])

  const simSubmit = useCallback((value: unknown) => {
    const step = def.steps[simStep]
    if (!step || !isScreenStep(step)) return
    const baseData = writeValue(simData, step.config.writeTo, value)
    const verify = step.config.verify

    // Verify screen: no backend in simulate. By default treat the scan as found (and apply the write
    // mappings using fake resolved values) so the flow walks; the "simulate not found" toggle drives
    // the onNotFound path (re-prompt holds; goto jumps) so the designer can exercise both.
    if (verify) {
      if (simVerifyNotFound) {
        if (verify.onNotFound.mode === 'goto' && verify.onNotFound.step) {
          const landed = resolveLanding(def, verify.onNotFound.step, baseData)
          setSimData(landed.data)
          setSimStep(landed.stepId ?? '')
        }
        // reprompt: hold on this step (no advance) — mirrors the runtime.
        return
      }
      const code = value == null ? '' : String(value)
      // Build a per-kind `fields` map (sample values) for every field the verify block stores, so the
      // simulate populates location purpose/type/status, SKU description/unit/category, etc. The
      // legacy top-level fields stay for older readers.
      const simFields: Record<string, unknown> = {}
      for (const key of Object.keys(verify.write ?? {})) {
        simFields[key] =
          key === 'id' ? `sim-${code || 'id'}`
          : key === 'code' ? code
          : key === 'name' ? (code ? `Sample ${code}` : 'Sample')
          : key === 'uomCode' ? 'EA'
          : `sim-${key}`
      }
      const data = applyVerifyWrites(baseData, verify, {
        found: true,
        id: `sim-${code || 'id'}`,
        code,
        name: code ? `Sample ${code}` : 'Sample',
        uomCode: 'EA',
        schemaCategory: 'SAMPLE',
        detail: {},
        fields: simFields,
      })
      const landed = resolveLanding(def, nextStepId(step, data), data)
      setSimData(landed.data)
      setSimStep(landed.stepId ?? '')
      return
    }

    const landed = resolveLanding(def, nextStepId(step, baseData), baseData)
    setSimData(landed.data)
    setSimStep(landed.stepId ?? '')
  }, [def, simStep, simData, simVerifyNotFound])

  const simAdvanceTask = useCallback(() => {
    const step = def.steps[simStep]
    if (!step) return
    // dry-run: no backend, just follow next/transitions, skipping skipWhen + evaluating compute steps.
    const landed = resolveLanding(def, nextStepId(step, simData), simData)
    setSimData(landed.data)
    setSimStep(landed.stepId ?? '')
  }, [def, simStep, simData])

  // --- render (all hooks above) -------------------------------------------------------------------

  const selectedStep = selectedId ? def.steps[selectedId] : undefined
  const previewStepId = simulating ? simStep : selectedId
  const previewStep = previewStepId ? def.steps[previewStepId] : undefined
  const previewData = simulating ? simData : sampleData

  // The selected step's editable configuration is reached from buttons next to / on the preview.
  const canEdit = !simulating && !!selectedStep && !!selectedId
  const verifyCapable = canEdit && !!selectedStep && isScreenStep(selectedStep)
    && (selectedStep.screen === 'textInput' || selectedStep.screen === 'numberInput')
    && capabilities.verifyKinds.length > 0
  const verifyConfigured = !!selectedStep && isScreenStep(selectedStep) && !!selectedStep.config.verify
  const workStepSelected = canEdit && !!selectedStep && (isTaskStep(selectedStep) || isComputeStep(selectedStep))
  const setStepVerify = (verify: VerifyConfig | undefined) => {
    if (selectedId && selectedStep && isScreenStep(selectedStep)) {
      changeStep(selectedId, { ...selectedStep, config: { ...selectedStep.config, verify } })
    }
    setDialog(null)
  }

  return (
    <div className="app-content op-pd-designer">
      {/* Toolbar: identity on the left, a compact action group on the right (secondary actions
          tucked into a "More" menu so the bar is not a wall of buttons). */}
      <div className="op-pd-toolbar">
        <div className="op-pd-toolbar-id">
          <input className="op-pd-title" value={def.title} onChange={(e) => patchDef({ title: e.target.value })} placeholder={t('title', 'Title')} />
          <input className="op-pd-key" value={def.processKey} onChange={(e) => patchDef({ processKey: e.target.value.replace(/[^a-z0-9-]/gi, '').toLowerCase() })} placeholder="process-key" title={t('processKey', 'Process key')} />
          <input className="op-pd-icon" value={def.icon ?? ''} onChange={(e) => patchDef({ icon: e.target.value })} placeholder="icon" title={t('icon', 'Icon')} />
          <span className="op-pd-status-badge">{def.status ?? 'DRAFT'}{dirty ? ' *' : ''}</span>
          {!editable && <span className="op-pd-readonly-badge" title={t('readonlyHint', 'Only DRAFT versions are editable. Duplicate to a new draft to change this.')}>{t('readonly', 'read-only')}</span>}
        </div>
        <span style={{ flex: 1 }} />
        <div className="op-pd-toolbar-actions">
          <button className="btn btn-ghost btn-sm" onClick={runValidate}>{t('validate', 'Validate')}</button>
          <button className="btn btn-ghost btn-sm" onClick={simulating ? stopSim : startSim} disabled={!def.start}>{simulating ? t('stopSim', 'Stop simulate') : t('simulate', 'Simulate')}</button>
          <ToolbarMenu label={t('more', 'More')}>
            <button className="btn btn-ghost btn-sm" onClick={newDraft}>{t('new', 'New')}</button>
            <button className="btn btn-ghost btn-sm" onClick={() => void duplicate()} disabled={!persisted}>{t('duplicate', 'Duplicate')}</button>
            <button className="btn btn-ghost btn-sm" onClick={() => void exportJson()} disabled={!persisted}>{t('export', 'Export')}</button>
            <button className="btn btn-ghost btn-sm" onClick={() => fileInputRef.current?.click()}>{t('import', 'Import')}</button>
          </ToolbarMenu>
          <button className="btn btn-ghost btn-sm" onClick={() => void save()} disabled={!editable}>{t('save', 'Save')}</button>
          <button className="btn btn-primary btn-sm" onClick={() => void publish()} disabled={!editable}>{t('publish', 'Publish')}</button>
        </div>
        <input
          ref={fileInputRef}
          type="file"
          accept="application/json,.json"
          style={{ display: 'none' }}
          onChange={(e) => {
            const f = e.target.files?.[0]
            if (f) void onImportFile(f)
            e.target.value = '' // allow re-importing the same file
          }}
        />
      </div>
      {status && <div className="op-pd-statusline">{status}</div>}

      <div className="op-pd-grid">
        {/* LEFT: flow list + palette + def list */}
        <div className="op-pd-flow">
          <div className="op-pd-palette">
            {PALETTE.map((p) => (
              <button key={p} type="button" className="op-pd-pal-btn" title={SCREEN_TYPE_LABELS[p]} onClick={() => addStep(p)}>
                <span aria-hidden="true">{SCREEN_TYPE_ICONS[p]}</span> {SCREEN_TYPE_LABELS[p]}
              </button>
            ))}
          </div>

          {flow.loopBack.size > 0 && (
            <div className="op-pd-flow-loops" title={t('loopHint', 'A step sends the flow back to an earlier step. Click to jump to the step that loops, then edit its next / branches.')}>
              <span aria-hidden="true">↺</span> {t('loopBanner', 'This flow loops')}:
              {[...flow.loopBack].map((edge) => {
                const [from, to] = edge.split('→')
                return (
                  <button key={edge} type="button" className="op-pd-loop-link" onClick={() => setSelectedId(from)}>
                    {from} → {to}
                  </button>
                )
              })}
            </div>
          )}

          <ul className="op-pd-steplist">
            {flow.order.length === 0 && flow.unreachable.length === 0 && (
              <li className="muted" style={{ padding: '.5rem' }}>{t('noSteps', 'Add a step from the palette above.')}</li>
            )}
            {flow.order.map((id) => (
              <FlowRow
                key={id}
                id={id}
                step={def.steps[id]}
                isStart={def.start === id}
                selected={(simulating ? simStep : selectedId) === id}
                loopBack={flow.loopBack}
                onSelect={() => setSelectedId(id)}
                onSelectStep={(target) => setSelectedId(target)}
                onDelete={() => deleteStep(id)}
                onSetStart={() => patchDef({ start: id })}
              />
            ))}
            {flow.unreachable.length > 0 && (
              <li className="op-pd-unreachable-head" title={t('unreachableHint', 'These steps are not reached from the start. Point a next / branch at them, or make one the start.')}>
                ⚠ {t('unreachable', 'Not connected to the flow')}
              </li>
            )}
            {flow.unreachable.map((id) => (
              <FlowRow
                key={id}
                id={id}
                step={def.steps[id]}
                isStart={def.start === id}
                selected={(simulating ? simStep : selectedId) === id}
                loopBack={flow.loopBack}
                orphan
                onSelect={() => setSelectedId(id)}
                onSelectStep={(target) => setSelectedId(target)}
                onDelete={() => deleteStep(id)}
                onSetStart={() => patchDef({ start: id })}
              />
            ))}
          </ul>

          {/* Version management for the current process key. */}
          {versionsForKey.length > 0 && (
            <div className="op-pd-versions">
              <div className="op-pd-versions-head">{t('versions', 'Versions of')} <code>{def.processKey}</code></div>
              <ul>
                {versionsForKey.map((d) => {
                  const isCurrent = d.version === def.version
                  return (
                    <li key={d.version}>
                      <button
                        className={`op-pd-version-btn${isCurrent ? ' is-current' : ''}`}
                        onClick={() => void loadDef(d.processKey, d.version ?? 1)}
                        title={d.status === 'DRAFT' ? t('editThis', 'Edit this draft') : t('viewThis', 'View (read-only)')}
                      >
                        v{d.version}
                        <span className={`op-pd-version-status status-${(d.status ?? 'DRAFT').toLowerCase()}`}>{d.status}</span>
                        {d.status !== 'DRAFT' && <span className="op-pd-version-lock" aria-hidden="true">🔒</span>}
                      </button>
                    </li>
                  )
                })}
              </ul>
            </div>
          )}

          <details className="op-pd-defs">
            <summary>{t('existing', 'Existing definitions')}</summary>
            <button className="btn btn-ghost btn-sm" onClick={() => void loadList()} style={{ margin: '.4rem 0' }}>{t('refresh', 'Refresh')}</button>
            <ul>
              {defs.map((d) => (
                <li key={`${d.processKey}-${d.version}`}>
                  <button className="op-pd-link" onClick={() => void loadDef(d.processKey, d.version ?? 1)}>{d.processKey} v{d.version} <span className="muted">{d.status}</span></button>
                </li>
              ))}
              {defs.length === 0 && <li className="muted">{t('noDefs', 'No definitions yet.')}</li>}
            </ul>
          </details>
        </div>

        {/* CENTRE: live handheld preview in a phone frame */}
        <div className="op-pd-preview-pane">
          {simulating && (
            <div className="op-pd-sim-banner" style={{ display: 'flex', alignItems: 'center', gap: '.75rem', flexWrap: 'wrap' }}>
              <span>{t('simulating', 'Simulating')} — {simStep || t('simEnd', 'flow ended')}</span>
              {previewStep && isScreenStep(previewStep) && previewStep.config.verify && (
                <label className="op-pd-toggle" style={{ margin: 0, fontSize: '.8rem' }}>
                  <input type="checkbox" checked={simVerifyNotFound} onChange={(e) => setSimVerifyNotFound(e.target.checked)} />
                  {t('simVerifyNotFound', 'Simulate "not found"')}
                </label>
              )}
            </div>
          )}
          {previewStep && isScreenStep(previewStep) && previewStep.config.verify && (
            <div className="op-pd-verify-badge" style={{ alignSelf: 'flex-start', margin: '0 0 .4rem' }}>
              <span className="badge" style={{ fontSize: '.72rem', background: 'rgba(141,198,63,.18)', border: '1px solid rgba(141,198,63,.5)' }}>
                ✓ {t('verifyBadge', 'Verify')}: {t(`verifyKind_${previewStep.config.verify.kind}`, previewStep.config.verify.kind)}
              </span>
            </div>
          )}
          <div className="op-pd-stage">
            <div className="op-pd-phone">
              <div className="op-pd-phone-screen">
                {previewStep && isScreenStep(previewStep) ? (
                  <ProcessScreenView
                    key={previewStepId + (simulating ? 's' : 'p')}
                    step={previewStep}
                    config={previewStep.config}
                    data={previewData}
                    schema={def.dataSchema}
                    catalog={PREVIEW_CATALOG}
                    onSubmit={simulating ? simSubmit : () => {}}
                    disabled={!simulating}
                    compact
                  />
                ) : previewStep && isTaskStep(previewStep) ? (
                  <div className="glass" style={{ padding: '1.5rem', textAlign: 'center' }}>
                    <div style={{ fontSize: '2rem' }}>{previewStep.task === SCRIPT_TASK_TYPE ? '〈/〉' : '⚙'}</div>
                    <h3 style={{ margin: '.5rem 0' }}>{previewStep.task === SCRIPT_TASK_TYPE ? t('scriptStep', 'Sandboxed script') : `${t('taskStep', 'Task')}: ${previewStep.task}`}</h3>
                    <p className="muted" style={{ fontSize: '.85rem' }}>{t('taskRunsServer', 'Runs on the server (checkpoint).')}</p>
                    {/* Configure the task right on the mockup screen. */}
                    {workStepSelected && <button className="btn btn-ghost btn-sm" onClick={() => setDialog('task')}>{t('editTask', 'Edit task…')}</button>}
                    {simulating && <button className="btn btn-primary" onClick={simAdvanceTask}>{t('simRunTask', 'Run task (dry-run) →')}</button>}
                  </div>
                ) : previewStep && isComputeStep(previewStep) ? (
                  <div className="glass" style={{ padding: '1.5rem', textAlign: 'center' }}>
                    <div style={{ fontSize: '2rem' }}>{SCREEN_TYPE_ICONS.compute}</div>
                    <h3 style={{ margin: '.5rem 0' }}>{t('computeStep', 'Compute')}</h3>
                    <p className="muted" style={{ fontSize: '.85rem' }}>
                      {(previewStep.set ?? []).filter((r) => r.var).length > 0
                        ? t('computePreview', 'Sets {list} (no screen).').replace('{list}', (previewStep.set ?? []).filter((r) => r.var).map((r) => r.var).join(', '))
                        : t('computeEmpty', 'No values set yet.')}
                    </p>
                    {workStepSelected && <button className="btn btn-ghost btn-sm" onClick={() => setDialog('task')}>{t('editTask', 'Edit task…')}</button>}
                    {simulating && <button className="btn btn-primary" onClick={simAdvanceTask}>{t('simRunCompute', 'Compute (dry-run) →')}</button>}
                  </div>
                ) : (
                  <div className="muted" style={{ padding: '2rem', textAlign: 'center' }}>
                    {simulating ? t('simComplete', 'Simulation complete.') : t('selectToPreview', 'Select a step to preview it here.')}
                  </div>
                )}
              </div>
            </div>

            {/* Verify-flow action sits beside the mockup (only for scan-capable input screens). */}
            {verifyCapable && (
              <aside className="op-pd-stage-actions">
                <button className="btn btn-outline btn-sm" onClick={() => setDialog('verify')}>
                  {verifyConfigured ? t('verifyEditFlow', 'Edit verify flow') : t('verifyFlow', 'Verify flow')}
                </button>
                {verifyConfigured && <span className="op-pd-stage-hint muted">{t('verifyConfigured', 'Verification on')}</span>}
              </aside>
            )}
          </div>

          {/* Configuration dialogs, triggered from the buttons above (modals, position-independent). */}
          {dialog === 'verify' && verifyCapable && selectedStep && isScreenStep(selectedStep) && (
            <VerifyDialog
              verify={selectedStep.config.verify ?? { kind: (capabilities.verifyKinds[0] ?? 'barcode') as VerifyKind, onNotFound: { mode: 'reprompt' } }}
              kinds={capabilities.verifyKinds as VerifyKind[]}
              capabilities={capabilities}
              vars={def.dataSchema}
              stepIds={Object.keys(def.steps).filter((s) => s !== selectedId)}
              onCancel={() => setDialog(null)}
              onDone={(v) => setStepVerify(v)}
              onRemove={verifyConfigured ? () => setStepVerify(undefined) : undefined}
            />
          )}
          {dialog === 'task' && workStepSelected && selectedStep && selectedId && (
            <TaskDialog
              step={selectedStep}
              tasks={tasks}
              capabilities={capabilities}
              vars={def.dataSchema}
              onCancel={() => setDialog(null)}
              onDone={(s) => { changeStep(selectedId, s); setDialog(null) }}
            />
          )}
        </div>

        {/* RIGHT: properties + data object */}
        {simulating ? (
          <aside className="op-pd-props"><p className="muted" style={{ padding: '1rem' }}>{t('simHint', 'Step through the flow in the phone frame. Stop simulate to edit.')}</p></aside>
        ) : (
          <PropertiesPanel def={def} selectedId={selectedId} tasks={tasks} capabilities={capabilities} onChangeStep={changeStep} onChangeSchema={changeSchema} onRenameStep={renameStep} />
        )}
      </div>

      {/* Validation issue list */}
      {issues && issues.length > 0 && (
        <div className="op-pd-issues">
          <strong>{t('validation', 'Validation')}</strong>
          <ul>
            {issues.map((iss, i) => (
              <li key={i} className={iss.level === 'error' ? 'op-pd-issue-err' : 'op-pd-issue-warn'}>
                {iss.level === 'error' ? '✕' : '⚠'} {iss.stepId ? `[${iss.stepId}] ` : ''}{iss.message}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

/** A compact overflow menu for secondary toolbar actions. Closes on outside click or after an
 *  action inside it is chosen, so the toolbar stays uncluttered. */
function ToolbarMenu({ label, children }: { label: string; children: React.ReactNode }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])
  return (
    <div className="op-pd-menu" ref={ref}>
      <button className="btn btn-ghost btn-sm" onClick={() => setOpen((o) => !o)} aria-haspopup="menu" aria-expanded={open}>
        {label} <span aria-hidden="true">▾</span>
      </button>
      {open && (
        <div className="op-pd-menu-pop" role="menu" onClick={() => setOpen(false)}>
          {children}
        </div>
      )}
    </div>
  )
}

function FlowRow({
  id,
  step,
  isStart,
  selected,
  loopBack,
  orphan,
  onSelect,
  onSelectStep,
  onDelete,
  onSetStart,
}: {
  id: string
  step: Step
  isStart: boolean
  selected: boolean
  loopBack: Set<string>
  orphan?: boolean
  onSelect: () => void
  onSelectStep: (target: string) => void
  onDelete: () => void
  onSetStart: () => void
}) {
  const t = useT('processDesign')
  const icon =
    step.type === 'task' ? SCREEN_TYPE_ICONS.task
    : step.type === 'compute' ? SCREEN_TYPE_ICONS.compute
    : SCREEN_TYPE_ICONS[(step as ScreenStep).screen]
  const label =
    step.type === 'task' ? `Task: ${(step as TaskStep).task}`
    : step.type === 'compute'
      ? (() => {
          const names = ((step as ComputeStep).set ?? []).filter((r) => r.var).map((r) => r.var)
          return names.length ? `Compute: ${names.join(', ')}` : 'Compute'
        })()
    : (step as ScreenStep).screen
  const hasVerify = step.type === 'screen' && !!(step as ScreenStep).config.verify
  const branches = step.transitions ?? []
  const isEnd = branches.length === 0 && !step.next

  // One outgoing edge: a clickable jump to the target, with a loop badge when it points back up
  // the current path (the runtime would cycle there).
  const Edge = ({ when, to }: { when?: string; to: string }) => {
    const isLoop = loopBack.has(`${id}→${to}`)
    return (
      <li className={`op-pd-edge${isLoop ? ' is-loop' : ''}`}>
        <span className="op-pd-branch-arrow" aria-hidden="true">└▶</span>{' '}
        {when != null ? <><code>{when || '(empty)'}</code> → </> : <><em>{t('elseEdge', 'else')}</em> → </>}
        <button
          type="button"
          className="op-pd-edge-target"
          onClick={(e) => { e.stopPropagation(); onSelectStep(to) }}
          title={t('jumpTo', 'Go to this step')}
        >
          {to || '?'}
        </button>
        {isLoop && <span className="op-pd-loop-badge" title={t('loopHint', 'Loops back to an earlier step.')}>↺ {t('loop', 'loop')}</span>}
      </li>
    )
  }

  return (
    <li>
      <div className={`op-pd-step${selected ? ' is-selected' : ''}${orphan ? ' is-orphan' : ''}`} onClick={onSelect}>
        <span className="op-pd-step-icon" aria-hidden="true">{icon}</span>
        <span className="op-pd-step-id">{id}{isStart && <span className="op-pd-start-badge">start</span>}</span>
        <span className="op-pd-step-type muted">{label}</span>
        {hasVerify && <span className="op-pd-verify-tag">✓ {t('verify', 'verify')}</span>}
        <span style={{ flex: 1 }} />
        {!isStart && <button className="op-pd-mini" title={t('setStart', 'Set as start')} onClick={(e) => { e.stopPropagation(); onSetStart() }}>▶</button>}
        <button className="op-pd-mini" title={t('delete', 'Delete')} onClick={(e) => { e.stopPropagation(); onDelete() }}>✕</button>
      </div>
      <ul className="op-pd-edges">
        {branches.map((tr, i) => (<Edge key={i} when={tr.when} to={tr.to} />))}
        {step.next && <Edge to={step.next} />}
        {isEnd && <li className="op-pd-edge op-pd-edge-end muted">{t('endsFlow', '→ ends the process')}</li>}
      </ul>
    </li>
  )
}
