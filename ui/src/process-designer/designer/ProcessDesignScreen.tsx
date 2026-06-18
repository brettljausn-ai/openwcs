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

import { Suspense, lazy, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useT } from '../../i18n/useT'
import ProcessScreenView from '../screens/ProcessScreenView'
import {
  SCREEN_TYPE_ICONS,
  SCREEN_TYPE_LABELS,
  SCRIPT_TASK_TYPE,
  isComputeStep,
  isDecisionStep,
  isScreenStep,
  isTaskStep,
  type ComputeStep,
  type DataVar,
  type DecisionStep,
  type ProcessDefinition,
  type ScreenStep,
  type ScreenType,
  type Step,
  type TaskStep,
  type VerifyConfig,
  type VerifyKind,
} from '../model'
import { createDef, duplicateDef, exportDef, getDef, importDef, listDefs, publishDef, updateDef } from '../api'
import DataTable, { type Column } from '../../ui/DataTable'
import { useCapabilities, useTasks } from '../useProcesses'
import { hasErrors, validateDefinition, type ValidationIssue } from './validate'
import VerifyDialog from './VerifyDialog'
import TaskDialog from './TaskDialog'
import ScreenDialog from './ScreenDialog'
import DecisionDialog from './DecisionDialog'
import DataObjectDialog from './DataObjectDialog'
import { applyVerifyWrites, nextStepId, resolveLanding, writeValue } from '../runtime/walker'
import { PREVIEW_CATALOG, sampleDataFor } from './sampleData'
import PropertiesPanel from './PropertiesPanel'

// React Flow ships a sizeable graph runtime; lazy-load it so it stays out of the main bundle (the
// designer is admin-only and the Canvas view is what pulls it in).
const FlowCanvas = lazy(() => import('./FlowCanvas'))

type PaletteKind = ScreenType | 'task' | 'compute' | 'decision'
const PALETTE: PaletteKind[] = ['textInput', 'numberInput', 'dateInput', 'acknowledge', 'questionYesNo', 'questionChoice', 'task', 'compute', 'decision']

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

function newStep(type: PaletteKind, defaultTask: string): Step {
  if (type === 'task') {
    return { type: 'task', task: defaultTask, input: {}, output: {} } satisfies TaskStep
  }
  if (type === 'compute') {
    return { type: 'compute', set: [{ var: '', expr: '' }] } satisfies ComputeStep
  }
  if (type === 'decision') {
    return { type: 'decision' } satisfies DecisionStep
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
  // Entry point: a table of existing processes. Selecting one (or "New process") opens the editor.
  const [mode, setMode] = useState<'list' | 'editor'>('list')
  const [status, setStatus] = useState<string>('')
  const [issues, setIssues] = useState<ValidationIssue[] | null>(null)
  const [dirty, setDirty] = useState(false)
  const [persisted, setPersisted] = useState(false) // has a server version (created/loaded)
  // Which configuration dialog is open (triggered from buttons by the live preview, not the panel).
  const [dialog, setDialog] = useState<null | 'verify' | 'task' | 'screen' | 'decision'>(null)
  // Styled confirm for leaving the editor with unsaved changes (no native window.confirm).
  const [confirmBack, setConfirmBack] = useState(false)
  // The data-object editor is its own modal, opened from the left flow pane.
  const [dataObjectOpen, setDataObjectOpen] = useState(false)
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

  // One row per process key for the landing table: the ACTIVE version (or the latest) represents it.
  const processRows = useMemo(() => {
    const byKey = new Map<string, ProcessDefinition[]>()
    for (const d of defs) {
      const arr = byKey.get(d.processKey) ?? []
      arr.push(d)
      byKey.set(d.processKey, arr)
    }
    return [...byKey.entries()].map(([key, versions]) => {
      const sorted = versions.slice().sort((a, b) => (b.version ?? 0) - (a.version ?? 0))
      const rep = sorted.find((v) => v.status === 'ACTIVE') ?? sorted[0]
      return {
        key,
        title: rep?.title || key,
        icon: rep?.icon ?? '',
        status: rep?.status ?? 'DRAFT',
        openVersion: rep?.version ?? 1,
        versionCount: versions.length,
      }
    }).sort((a, b) => a.title.localeCompare(b.title))
  }, [defs])
  type ProcessRow = (typeof processRows)[number]

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

  // Persist a canvas node's position into the step's (designer-only) `ui`. The backend stores the
  // model verbatim and publish validation ignores unknown step fields, so positions persist with no
  // server change.
  const moveStep = useCallback((id: string, x: number, y: number) => {
    setDef((d) => {
      const step = d.steps[id]
      if (!step) return d
      return { ...d, steps: { ...d.steps, [id]: { ...step, ui: { x, y } } } }
    })
    setDirty(true)
  }, [])

  // Tidy: overwrite every step's (designer-only) `ui` with a freshly computed auto-layout in one
  // update. Layout is non-behavioural, so it persists with the model and needs no server change.
  const layoutSteps = useCallback((positions: Record<string, { x: number; y: number }>) => {
    setDef((d) => {
      const steps: Record<string, Step> = {}
      for (const [id, step] of Object.entries(d.steps)) {
        const pos = positions[id]
        steps[id] = pos ? { ...step, ui: { x: pos.x, y: pos.y } } : step
      }
      return { ...d, steps }
    })
    setDirty(true)
  }, [])

  const addStep = useCallback((type: PaletteKind) => {
    // Default a new Task step to the first NON-script curated task (the script type is opt-in via the
    // task-type picker / AI assist, gated by capabilities).
    const defaultTask = tasks.find((tt) => tt.id !== SCRIPT_TASK_TYPE)?.id ?? 'inventory.lookup'
    setDef((d) => {
      const id = uniqueId(type === 'task' ? 'task' : type, new Set(Object.keys(d.steps)))
      const step = newStep(type, defaultTask)
      // Place the node at a sensible free spot below the lowest positioned node so it never lands on
      // top of an existing one (it appears as an orphan until the user drag-connects it).
      const placed = Object.values(d.steps).filter((s) => s.ui)
      const maxY = placed.reduce((m, s) => Math.max(m, s.ui?.y ?? 0), 0)
      step.ui = placed.length ? { x: 40, y: maxY + 150 } : { x: 40, y: 40 }
      const steps = { ...d.steps, [id]: step }
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
      // A write key may be a scalar (`code`), an object whole key (`uom`), or an object sub-field
      // path (`uom.factor`). Produce a plausible sample for each path, building nested objects so an
      // object whole-key resolves to a populated object and a dotted path resolves to its leaf.
      const sampleLeaf = (leaf: string): unknown =>
        leaf === 'id' || leaf === 'skuId' || leaf === 'uomId' || leaf === 'locationId' ? `sim-${code || leaf}`
        : leaf === 'code' ? (code || 'SAMPLE')
        : leaf === 'name' || leaf === 'description' ? (code ? `Sample ${code}` : 'Sample')
        : leaf === 'uomCode' ? 'EA'
        : leaf === 'factor' ? 1
        : leaf === 'baseUnit' ? true
        : leaf === 'status' ? 'ACTIVE'
        : `sim-${leaf}`
      const simFields: Record<string, unknown> = {}
      for (const key of Object.keys(verify.write ?? {})) {
        const segs = key.split('.')
        if (segs.length === 1) {
          // Scalar or whole-object key: if there is a sibling dotted path we will fill the object
          // below; seed a generic object for a whole-object key, else a scalar leaf.
          const isObject = Object.keys(verify.write ?? {}).some((k) => k.startsWith(`${key}.`))
            || key === 'uom' || key === 'sku' || key === 'location'
          if (isObject && typeof simFields[key] !== 'object') {
            simFields[key] = { ...(typeof simFields[key] === 'object' ? simFields[key] : {}), code: code || 'SAMPLE' }
          } else if (!(key in simFields)) {
            simFields[key] = sampleLeaf(key)
          }
        } else {
          const [root, sub] = segs
          const obj = (typeof simFields[root] === 'object' && simFields[root] != null
            ? simFields[root]
            : {}) as Record<string, unknown>
          obj[sub] = sampleLeaf(sub)
          simFields[root] = obj
        }
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
  const decisionStepSelected = canEdit && !!selectedStep && isDecisionStep(selectedStep)
  const screenStepSelected = canEdit && !!selectedStep && isScreenStep(selectedStep)
  const setStepVerify = (verify: VerifyConfig | undefined) => {
    if (selectedId && selectedStep && isScreenStep(selectedStep)) {
      changeStep(selectedId, { ...selectedStep, config: { ...selectedStep.config, verify } })
    }
    setDialog(null)
  }

  // --- landing: a table of existing processes (the entry point to the designer) ---------------------
  if (mode === 'list') {
    const statusBadge = (s: string) => (
      <span className={`op-pd-deftable-status is-${s.toLowerCase()}`}>{s}</span>
    )
    const columns: Column<ProcessRow>[] = [
      { key: 'title', header: t('colProcess', 'Process'), sortValue: (r) => r.title,
        render: (r) => <span className="op-pd-deftable-title">{r.icon ? `${r.icon} ` : ''}{r.title}</span> },
      { key: 'key', header: t('colKey', 'Key'), sortValue: (r) => r.key,
        render: (r) => <code className="muted">{r.key}</code> },
      { key: 'status', header: t('colStatus', 'Status'), sortValue: (r) => r.status, render: (r) => statusBadge(r.status) },
      { key: 'version', header: t('colVersion', 'Version'), align: 'right', sortValue: (r) => r.openVersion, render: (r) => <>v{r.openVersion}</> },
      { key: 'versions', header: t('colVersions', 'Versions'), align: 'right', sortValue: (r) => r.versionCount, render: (r) => <span className="muted">{r.versionCount}</span> },
    ]
    return (
      <div className="app-content op-pd-designer">
        <div className="op-pd-toolbar">
          <h2 style={{ margin: 0 }}>{t('processesTitle', 'Processes')}</h2>
          <span style={{ flex: 1 }} />
          <button className="btn btn-ghost btn-sm" onClick={() => void loadList()}>{t('refresh', 'Refresh')}</button>
          <button className="btn btn-primary btn-sm" onClick={() => { newDraft(); setMode('editor') }}>+ {t('newProcess', 'New process')}</button>
        </div>
        <p className="muted" style={{ fontSize: '.85rem', margin: '0 0 .6rem' }}>
          {t('processesIntro', 'Select a process to open it in the designer, or create a new one.')}
        </p>
        <DataTable
          columns={columns}
          rows={processRows}
          rowKey={(r) => r.key}
          search={(r) => `${r.title} ${r.key} ${r.status}`}
          searchPlaceholder={t('searchProcesses', 'Search processes…')}
          initialSort={{ key: 'title', dir: 'asc' }}
          onRowClick={(r) => { void loadDef(r.key, r.openVersion); setMode('editor') }}
          empty={t('noDefs', 'No definitions yet.')}
        />
      </div>
    )
  }

  return (
    <div className="app-content op-pd-designer">
      {/* Toolbar: identity on the left, a compact action group on the right (secondary actions
          tucked into a "More" menu so the bar is not a wall of buttons). */}
      <div className="op-pd-toolbar">
        <button className="btn btn-ghost btn-sm" onClick={() => {
          if (dirty) { setConfirmBack(true); return }
          setMode('list'); void loadList()
        }}>← {t('processesTitle', 'Processes')}</button>
        <div className="op-pd-toolbar-id">
          <input className="op-pd-title" value={def.title} onChange={(e) => patchDef({ title: e.target.value })} placeholder={t('title', 'Title')} />
          <input className="op-pd-key" value={def.processKey} onChange={(e) => patchDef({ processKey: e.target.value.replace(/[^a-z0-9-]/gi, '').toLowerCase() })} placeholder="process-key" title={t('processKey', 'Process key')} />
          <input className="op-pd-icon" value={def.icon ?? ''} onChange={(e) => patchDef({ icon: e.target.value })} placeholder="icon" title={t('icon', 'Icon')} />
          <span className="op-pd-status-badge">{def.status ?? 'DRAFT'}{dirty ? ' *' : ''}</span>
          {!editable && <span className="op-pd-readonly-badge" title={t('readonlyHint', 'Only DRAFT versions are editable. Duplicate to a new draft to change this.')}>{t('readonly', 'read-only')}</span>}
          {!editable && persisted && <button className="btn btn-outline btn-sm" onClick={() => void duplicate()} title={t('editAsDraftHint', 'This is a published version. Make an editable draft copy to change it.')}>{t('editAsDraft', 'Edit as draft')}</button>}
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

      <div className="op-pd-grid is-canvas">
        {/* MAIN: the visual node-canvas (the single, authoritative flow editor) + palette + def list */}
        <div className="op-pd-flow">
          <div className="op-pd-palette">
            {PALETTE.map((p) => (
              <button key={p} type="button" className="op-pd-pal-btn" title={SCREEN_TYPE_LABELS[p]} onClick={() => addStep(p)}>
                <span aria-hidden="true">{SCREEN_TYPE_ICONS[p]}</span> {SCREEN_TYPE_LABELS[p]}
              </button>
            ))}
          </div>

          {/* The visual node-canvas: the single, authoritative flow editor (drag nodes, draw links). */}
          <Suspense fallback={<div className="op-fc-canvas op-fc-loading muted">{t('canvasLoading', 'Loading canvas…')}</div>}>
            <FlowCanvas
              def={def}
              flow={flow}
              selectedId={simulating ? simStep : selectedId}
              simStepId={simulating ? simStep : null}
              editable={editable && !simulating}
              onSelect={(id) => setSelectedId(id)}
              onChangeStep={changeStep}
              onSetStart={(id) => patchDef({ start: id })}
              onDeleteStep={deleteStep}
              onMoveStep={moveStep}
              onLayout={layoutSteps}
            />
          </Suspense>

          {/* The typed variables the process reads/writes; edited in its own dialog. */}
          <div className="op-pd-dataobj-bar">
            <button
              type="button"
              className="btn btn-ghost btn-sm op-pd-dataobj-btn"
              onClick={() => setDataObjectOpen(true)}
              title={t('dataObjectOpen', 'Edit the process data object')}
            >
              <span aria-hidden="true">{'{ }'}</span> {t('dataObject', 'Data object')} ({def.dataSchema.length})
            </button>
          </div>

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
                ) : previewStep && isDecisionStep(previewStep) ? (
                  <div className="glass" style={{ padding: '1.5rem', textAlign: 'center' }}>
                    <div style={{ fontSize: '2rem' }}>{SCREEN_TYPE_ICONS.decision}</div>
                    <h3 style={{ margin: '.5rem 0' }}>{t('decisionStep', 'Decision')}</h3>
                    <p className="muted" style={{ fontSize: '.85rem' }}>
                      {t('decisionRoutesByRules', 'Routes by rules (no screen).')}
                    </p>
                    {decisionStepSelected && <button className="btn btn-ghost btn-sm" onClick={() => setDialog('decision')}>{t('editDecision', 'Edit decision…')}</button>}
                    {simulating && <button className="btn btn-primary" onClick={simAdvanceTask}>{t('simRunDecision', 'Route (dry-run) →')}</button>}
                  </div>
                ) : (
                  <div className="muted" style={{ padding: '2rem', textAlign: 'center' }}>
                    {simulating ? t('simComplete', 'Simulation complete.') : t('selectToPreview', 'Select a step to preview it here.')}
                  </div>
                )}
              </div>
            </div>

            {/* Screen-config + verify-flow actions sit beside the mockup. "Edit screen" opens the
                guided screen-config dialog; "Verify flow" (scan-capable input screens only) sits below. */}
            {(screenStepSelected || verifyCapable) && (
              <aside className="op-pd-stage-actions">
                {screenStepSelected && (
                  <button className="btn btn-outline btn-sm" onClick={() => setDialog('screen')}>
                    {t('editScreen', 'Edit screen')}
                  </button>
                )}
                {verifyCapable && (
                  <>
                    <button className="btn btn-outline btn-sm" onClick={() => setDialog('verify')}>
                      {verifyConfigured ? t('verifyEditFlow', 'Edit verify flow') : t('verifyFlow', 'Verify flow')}
                    </button>
                    {verifyConfigured && <span className="op-pd-stage-hint muted">{t('verifyConfigured', 'Verification on')}</span>}
                  </>
                )}
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
          {dialog === 'screen' && screenStepSelected && selectedStep && isScreenStep(selectedStep) && selectedId && (
            <ScreenDialog
              step={selectedStep}
              vars={def.dataSchema}
              stepId={selectedId}
              stepIds={Object.keys(def.steps)}
              onRename={renameStep}
              onCancel={() => setDialog(null)}
              onDone={(s) => { changeStep(selectedId, s); setDialog(null) }}
            />
          )}
          {dialog === 'task' && workStepSelected && selectedStep && selectedId && (
            <TaskDialog
              step={selectedStep}
              stepId={selectedId}
              stepIds={Object.keys(def.steps)}
              tasks={tasks}
              capabilities={capabilities}
              vars={def.dataSchema}
              onRename={renameStep}
              onCancel={() => setDialog(null)}
              onDone={(s) => { changeStep(selectedId, s); setDialog(null) }}
            />
          )}
          {dialog === 'decision' && decisionStepSelected && selectedStep && isDecisionStep(selectedStep) && selectedId && (
            <DecisionDialog
              step={selectedStep}
              vars={def.dataSchema}
              stepId={selectedId}
              stepIds={Object.keys(def.steps)}
              onRename={renameStep}
              onCancel={() => setDialog(null)}
              onDone={(s) => { changeStep(selectedId, s); setDialog(null) }}
            />
          )}
        </div>

        {/* RIGHT: properties + data object */}
        {simulating ? (
          <aside className="op-pd-props"><p className="muted" style={{ padding: '1rem' }}>{t('simHint', 'Step through the flow in the phone frame. Stop simulate to edit.')}</p></aside>
        ) : (
          <PropertiesPanel def={def} selectedId={selectedId} tasks={tasks} capabilities={capabilities} onChangeStep={changeStep} onRenameStep={renameStep} />
        )}
      </div>

      {/* Styled confirm for leaving with unsaved changes. */}
      {confirmBack && (
        <div className="modal-backdrop" onMouseDown={() => setConfirmBack(false)}>
          <div className="dialog op-pd-confirm" role="alertdialog" aria-modal="true" onMouseDown={(e) => e.stopPropagation()}>
            <h2 style={{ marginBottom: '.4rem' }}>{t('discardTitle', 'Discard changes?')}</h2>
            <p className="muted" style={{ margin: '0 0 1rem' }}>{t('discardChanges', 'Discard unsaved changes and return to the process list?')}</p>
            <div className="dialog-actions" style={{ justifyContent: 'flex-end' }}>
              <button className="btn btn-ghost" onClick={() => setConfirmBack(false)}>{t('cancel', 'Cancel')}</button>
              <button className="btn btn-danger" onClick={() => { setConfirmBack(false); setMode('list'); void loadList() }}>{t('discard', 'Discard')}</button>
            </div>
          </div>
        </div>
      )}

      {/* Data-object editor (its own modal, opened from the left flow pane). */}
      {dataObjectOpen && (
        <DataObjectDialog
          schema={def.dataSchema}
          onChange={changeSchema}
          onClose={() => setDataObjectOpen(false)}
        />
      )}

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
