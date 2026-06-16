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
  isScreenStep,
  isTaskStep,
  type DataVar,
  type ProcessDefinition,
  type ScreenStep,
  type ScreenType,
  type Step,
  type TaskStep,
} from '../model'
import { createDef, duplicateDef, exportDef, getDef, importDef, listDefs, publishDef, updateDef } from '../api'
import { useTasks } from '../useProcesses'
import { hasErrors, validateDefinition, type ValidationIssue } from './validate'
import { nextStepId, resolveLandingStep, writeValue } from '../runtime/walker'
import { PREVIEW_CATALOG, sampleDataFor } from './sampleData'
import PropertiesPanel from './PropertiesPanel'

const PALETTE: (ScreenType | 'task')[] = ['textInput', 'numberInput', 'dateInput', 'acknowledge', 'questionYesNo', 'questionChoice', 'task']

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

function newStep(type: ScreenType | 'task', defaultTask: string): Step {
  if (type === 'task') {
    return { type: 'task', task: defaultTask, input: {}, output: {} } satisfies TaskStep
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

// Ordered step ids: start first, then BFS so branches sit near their parent; orphans appended.
function orderedStepIds(def: ProcessDefinition): string[] {
  const all = Object.keys(def.steps)
  const out: string[] = []
  const seen = new Set<string>()
  const visit = (id: string) => {
    if (!id || seen.has(id) || !def.steps[id]) return
    seen.add(id)
    out.push(id)
    const step = def.steps[id]
    for (const tr of step.transitions ?? []) visit(tr.to)
    if (step.next) visit(step.next)
  }
  if (def.start) visit(def.start)
  for (const id of all) if (!seen.has(id)) out.push(id)
  return out
}

export default function ProcessDesignScreen() {
  const t = useT('processDesign')
  const { tasks } = useTasks()

  const [def, setDef] = useState<ProcessDefinition>(() => emptyDef())
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [defs, setDefs] = useState<ProcessDefinition[]>([])
  const [status, setStatus] = useState<string>('')
  const [issues, setIssues] = useState<ValidationIssue[] | null>(null)
  const [dirty, setDirty] = useState(false)
  const [persisted, setPersisted] = useState(false) // has a server version (created/loaded)
  const fileInputRef = useRef<HTMLInputElement>(null)

  // Simulate mode state.
  const [simulating, setSimulating] = useState(false)
  const [simStep, setSimStep] = useState<string>('')
  const [simData, setSimData] = useState<Record<string, unknown>>({})

  const sampleData = useMemo(() => sampleDataFor(def.dataSchema), [def.dataSchema])
  const orderedIds = useMemo(() => orderedStepIds(def), [def])

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

  const addStep = useCallback((type: ScreenType | 'task') => {
    const defaultTask = tasks[0]?.id ?? 'inventory.lookup'
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

  const moveStep = useCallback((id: string, dir: -1 | 1) => {
    // Reorder only affects the visual order map. We keep step ids; the underlying steps object is
    // already a map, so we persist order by rebuilding next-chains is too invasive — instead we sort
    // by an explicit order array kept on the def via a hidden convention: reorder swaps the default
    // `next` chain for adjacent linear steps. For Phase 1 simplicity we reorder the object insertion
    // order (which orderedStepIds falls back to for orphans) by rebuilding the steps map.
    setDef((d) => {
      const ids = orderedStepIds(d)
      const idx = ids.indexOf(id)
      const swap = idx + dir
      if (idx < 0 || swap < 0 || swap >= ids.length) return d
      ;[ids[idx], ids[swap]] = [ids[swap], ids[idx]]
      const steps: Record<string, Step> = {}
      for (const k of ids) steps[k] = d.steps[k]
      return { ...d, steps }
    })
    setDirty(true)
  }, [])

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
    setSimData(data)
    // Honour skipWhen from the very first step too.
    setSimStep(resolveLandingStep(def, def.start, data) ?? '')
  }, [def])

  const stopSim = useCallback(() => { setSimulating(false); setSimStep('') }, [])

  const simSubmit = useCallback((value: unknown) => {
    const step = def.steps[simStep]
    if (!step || !isScreenStep(step)) return
    const data = writeValue(simData, step.config.writeTo, value)
    setSimData(data)
    setSimStep(resolveLandingStep(def, nextStepId(step, data), data) ?? '')
  }, [def, simStep, simData])

  const simAdvanceTask = useCallback(() => {
    const step = def.steps[simStep]
    if (!step) return
    // dry-run: no backend, just follow next/transitions, skipping any skipWhen steps.
    setSimStep(resolveLandingStep(def, nextStepId(step, simData), simData) ?? '')
  }, [def, simStep, simData])

  // --- render (all hooks above) -------------------------------------------------------------------

  const selectedStep = selectedId ? def.steps[selectedId] : undefined
  const previewStepId = simulating ? simStep : selectedId
  const previewStep = previewStepId ? def.steps[previewStepId] : undefined
  const previewData = simulating ? simData : sampleData

  return (
    <div className="app-content op-pd-designer">
      {/* Toolbar */}
      <div className="op-pd-toolbar">
        <input className="op-pd-key" value={def.processKey} onChange={(e) => patchDef({ processKey: e.target.value.replace(/[^a-z0-9-]/gi, '').toLowerCase() })} placeholder="process-key" title={t('processKey', 'Process key')} />
        <input className="op-pd-title" value={def.title} onChange={(e) => patchDef({ title: e.target.value })} placeholder={t('title', 'Title')} />
        <input className="op-pd-icon" value={def.icon ?? ''} onChange={(e) => patchDef({ icon: e.target.value })} placeholder="icon" title={t('icon', 'Icon')} />
        <span className="op-pd-status-badge">{def.status ?? 'DRAFT'}{dirty ? ' *' : ''}</span>
        {!editable && <span className="op-pd-readonly-badge" title={t('readonlyHint', 'Only DRAFT versions are editable. Duplicate to a new draft to change this.')}>{t('readonly', 'read-only')}</span>}
        <span style={{ flex: 1 }} />
        <button className="btn btn-ghost btn-sm" onClick={newDraft}>{t('new', 'New')}</button>
        <button className="btn btn-ghost btn-sm" onClick={() => void save()} disabled={!editable}>{t('save', 'Save')}</button>
        <button className="btn btn-ghost btn-sm" onClick={() => void duplicate()} disabled={!persisted}>{t('duplicate', 'Duplicate')}</button>
        <button className="btn btn-ghost btn-sm" onClick={() => void exportJson()} disabled={!persisted}>{t('export', 'Export')}</button>
        <button className="btn btn-ghost btn-sm" onClick={() => fileInputRef.current?.click()}>{t('import', 'Import')}</button>
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
        <button className="btn btn-ghost btn-sm" onClick={runValidate}>{t('validate', 'Validate')}</button>
        <button className="btn btn-ghost btn-sm" onClick={simulating ? stopSim : startSim} disabled={!def.start}>{simulating ? t('stopSim', 'Stop simulate') : t('simulate', 'Simulate')}</button>
        <button className="btn btn-primary btn-sm" onClick={() => void publish()} disabled={!editable}>{t('publish', 'Publish')}</button>
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

          <ul className="op-pd-steplist">
            {orderedIds.length === 0 && <li className="muted" style={{ padding: '.5rem' }}>{t('noSteps', 'Add a step from the palette above.')}</li>}
            {orderedIds.map((id) => (
              <FlowRow
                key={id}
                id={id}
                step={def.steps[id]}
                isStart={def.start === id}
                selected={(simulating ? simStep : selectedId) === id}
                onSelect={() => setSelectedId(id)}
                onUp={() => moveStep(id, -1)}
                onDown={() => moveStep(id, 1)}
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
          {simulating && <div className="op-pd-sim-banner">{t('simulating', 'Simulating')} — {simStep || t('simEnd', 'flow ended')}</div>}
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
                  <div style={{ fontSize: '2rem' }}>⚙</div>
                  <h3 style={{ margin: '.5rem 0' }}>{t('taskStep', 'Task')}: {previewStep.task}</h3>
                  <p className="muted" style={{ fontSize: '.85rem' }}>{t('taskRunsServer', 'Runs on the server (checkpoint).')}</p>
                  {simulating && <button className="btn btn-primary" onClick={simAdvanceTask}>{t('simRunTask', 'Run task (dry-run) →')}</button>}
                </div>
              ) : (
                <div className="muted" style={{ padding: '2rem', textAlign: 'center' }}>
                  {simulating ? t('simComplete', 'Simulation complete.') : t('selectToPreview', 'Select a step to preview it here.')}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* RIGHT: properties + data object */}
        {simulating ? (
          <aside className="op-pd-props"><p className="muted" style={{ padding: '1rem' }}>{t('simHint', 'Step through the flow in the phone frame. Stop simulate to edit.')}</p></aside>
        ) : (
          <PropertiesPanel def={def} selectedId={selectedId} tasks={tasks} onChangeStep={changeStep} onChangeSchema={changeSchema} onRenameStep={renameStep} />
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

function FlowRow({
  id,
  step,
  isStart,
  selected,
  onSelect,
  onUp,
  onDown,
  onDelete,
  onSetStart,
}: {
  id: string
  step: Step
  isStart: boolean
  selected: boolean
  onSelect: () => void
  onUp: () => void
  onDown: () => void
  onDelete: () => void
  onSetStart: () => void
}) {
  const icon = step.type === 'task' ? SCREEN_TYPE_ICONS.task : SCREEN_TYPE_ICONS[(step as ScreenStep).screen]
  const label = step.type === 'task' ? `Task: ${(step as TaskStep).task}` : (step as ScreenStep).screen
  const branches = step.transitions ?? []
  return (
    <li>
      <div className={`op-pd-step${selected ? ' is-selected' : ''}`} onClick={onSelect}>
        <span className="op-pd-step-icon" aria-hidden="true">{icon}</span>
        <span className="op-pd-step-id">{id}{isStart && <span className="op-pd-start-badge">start</span>}</span>
        <span className="op-pd-step-type muted">{label}</span>
        <span style={{ flex: 1 }} />
        <button className="op-pd-mini" title="Up" onClick={(e) => { e.stopPropagation(); onUp() }}>↑</button>
        <button className="op-pd-mini" title="Down" onClick={(e) => { e.stopPropagation(); onDown() }}>↓</button>
        {!isStart && <button className="op-pd-mini" title="Set as start" onClick={(e) => { e.stopPropagation(); onSetStart() }}>▶</button>}
        <button className="op-pd-mini" title="Delete" onClick={(e) => { e.stopPropagation(); onDelete() }}>✕</button>
      </div>
      {branches.length > 0 && (
        <ul className="op-pd-branches">
          {branches.map((tr, i) => (
            <li key={i} className="op-pd-branch"><span className="op-pd-branch-arrow">└▶</span> <code>{tr.when || '(empty)'}</code> → <strong>{tr.to || '?'}</strong></li>
          ))}
          {step.next && <li className="op-pd-branch"><span className="op-pd-branch-arrow">└▶</span> <em>else</em> → <strong>{step.next}</strong></li>}
        </ul>
      )}
    </li>
  )
}
