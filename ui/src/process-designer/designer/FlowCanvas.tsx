// The visual node-canvas (flowchart) editor for the process designer.
//
// Each step is a draggable React Flow node; the flow LINKS are the model's edges (default `next` =
// solid edge, each transition = a dashed, condition-labelled edge, a loop back-edge = amber/animated).
// You CREATE a link by dragging a connector from a source node to a target node (onConnect): the
// first link becomes the step's `next`, any further link becomes a branch transition the user then
// labels. Clicking a node selects it (drives the existing selectedId, so PropertiesPanel + the
// preview + the config dialogs keep working unchanged). Clicking an edge selects it; Delete (or the
// edge button) removes the underlying `next`/transition; a branch edge carries an inline `when`
// editor. Dragging a node persists its position into the step's `ui` via changeStep, so the layout
// is saved with the def. Orphan nodes (not reachable from start) still render, with "not connected"
// styling, so the user can drag-connect them into the flow.
//
// React Flow is lazy-loaded by the parent so the graph library stays out of the main bundle.
//
// Rules of Hooks: every hook is declared unconditionally at the top before any early return.

import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Background,
  BaseEdge,
  Controls,
  EdgeLabelRenderer,
  Handle,
  MiniMap,
  Position,
  ReactFlow,
  ReactFlowProvider,
  getBezierPath,
  MarkerType,
  useEdgesState,
  useNodesState,
  type Connection,
  type Edge,
  type EdgeProps,
  type Node,
  type NodeProps,
  type NodeTypes,
  type EdgeTypes,
  type OnConnect,
  type ReactFlowInstance,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useT } from '../../i18n/useT'
import {
  SCREEN_TYPE_ICONS,
  SCRIPT_TASK_TYPE,
  isComputeStep,
  isScreenStep,
  isTaskStep,
  type ComputeStep,
  type ProcessDefinition,
  type ScreenStep,
  type Step,
  type TaskStep,
} from '../model'
import { unknownExpressionVars, validateCondition } from '../condition'

// --- the analysis the parent already computes (passed in, not reimplemented) -----------------------
export interface FlowInfo {
  order: string[]
  unreachable: string[]
  loopBack: Set<string>
}

interface FlowCanvasProps {
  def: ProcessDefinition
  flow: FlowInfo
  selectedId: string | null
  simStepId: string | null // the current sim node when simulating, else null
  editable: boolean
  onSelect: (id: string | null) => void
  onChangeStep: (id: string, step: Step) => void
  onSetStart: (id: string) => void
  onDeleteStep: (id: string) => void
  onMoveStep: (id: string, x: number, y: number) => void
  // Overwrite every step's `ui` with a freshly computed auto-layout (the "Tidy" button).
  onLayout: (positions: Record<string, { x: number; y: number }>) => void
}

// --- node + edge payloads --------------------------------------------------------------------------
interface StepNodeData extends Record<string, unknown> {
  step: Step
  isStart: boolean
  isOrphan: boolean
  isSim: boolean
  onSetStart: () => void
  onDelete: () => void
  editable: boolean
  label: string
}

// A model edge: either the default `next` ("default") or a branch transition (index into transitions).
type EdgeKind = 'next' | 'branch'
interface StepEdgeData extends Record<string, unknown> {
  kind: EdgeKind
  from: string
  to: string
  when?: string // branch condition source
  branchIndex?: number // index in transitions[] for a branch
  isLoop: boolean
  knownVars: string[]
  editable: boolean
  onEditWhen: (from: string, index: number, when: string) => void
  onDelete: (edge: StepEdgeData) => void
}

const NODE_W = 210
const X_GAP = 210
const Y_GAP = 100

// Auto-layout: a simple left-to-right layered layout. Depth (column) comes from the flow DFS order
// (BFS distance from start); siblings at a depth stack vertically. Orphans go in a trailing column.
function autoLayout(def: ProcessDefinition, flow: FlowInfo): Record<string, { x: number; y: number }> {
  const depth: Record<string, number> = {}
  // BFS from start for layered x; fall back to order index for anything BFS misses.
  if (def.start && def.steps[def.start]) {
    const queue: string[] = [def.start]
    depth[def.start] = 0
    const seen = new Set<string>([def.start])
    while (queue.length) {
      const id = queue.shift() as string
      const step = def.steps[id]
      if (!step) continue
      const outs: string[] = []
      for (const tr of step.transitions ?? []) if (tr.to) outs.push(tr.to)
      if (step.next) outs.push(step.next)
      for (const to of outs) {
        if (!def.steps[to] || seen.has(to)) continue
        seen.add(to)
        depth[to] = (depth[id] ?? 0) + 1
        queue.push(to)
      }
    }
  }
  // Orphans: place them in columns AFTER the deepest reachable column.
  const maxDepth = Object.values(depth).reduce((m, d) => Math.max(m, d), 0)
  flow.unreachable.forEach((id, i) => { depth[id] = maxDepth + 1 + Math.floor(i / 4) })

  // Stack within each column.
  const byCol: Record<number, string[]> = {}
  const allIds = [...flow.order, ...flow.unreachable]
  for (const id of allIds) {
    const d = depth[id] ?? 0
    ;(byCol[d] ??= []).push(id)
  }
  const pos: Record<string, { x: number; y: number }> = {}
  for (const [colStr, ids] of Object.entries(byCol)) {
    const col = Number(colStr)
    ids.forEach((id, row) => { pos[id] = { x: col * X_GAP, y: row * Y_GAP } })
  }
  return pos
}

function stepShortLabel(step: Step): string {
  if (isTaskStep(step)) {
    return step.task === SCRIPT_TASK_TYPE ? 'Script' : `Task: ${step.task}`
  }
  if (isComputeStep(step)) {
    const names = ((step as ComputeStep).set ?? []).filter((r) => r.var).map((r) => r.var)
    return names.length ? `Compute: ${names.join(', ')}` : 'Compute'
  }
  return (step as ScreenStep).screen
}

function stepIcon(step: Step): string {
  if (isTaskStep(step)) return SCREEN_TYPE_ICONS.task
  if (isComputeStep(step)) return SCREEN_TYPE_ICONS.compute
  return SCREEN_TYPE_ICONS[(step as ScreenStep).screen]
}

// --- custom node -----------------------------------------------------------------------------------
function StepNode({ data, selected }: NodeProps<Node<StepNodeData>>) {
  const t = useT('processDesign')
  const { step, isStart, isOrphan, isSim, onSetStart, onDelete, editable, label } = data
  const icon = stepIcon(step)
  const hasVerify = isScreenStep(step) && !!step.config.verify
  const isScript = isTaskStep(step) && step.task === SCRIPT_TASK_TYPE
  const isTask = isTaskStep(step) && !isScript
  const isCompute = isComputeStep(step)
  const cls =
    'op-fc-node' +
    (selected ? ' is-selected' : '') +
    (isOrphan ? ' is-orphan' : '') +
    (isSim ? ' is-sim' : '')
  return (
    <div className={cls} title={isOrphan ? t('notConnected', 'Not connected to the flow') : undefined}>
      <Handle type="target" position={Position.Left} className="op-fc-handle" />
      <div className="op-fc-node-head">
        <span className="op-fc-node-icon" aria-hidden="true">{icon}</span>
        <span className="op-fc-node-id">{label}</span>
        {isStart && <span className="op-fc-badge is-start">{t('startBadge', 'start')}</span>}
      </div>
      <div className="op-fc-node-type">{stepShortLabel(step)}</div>
      <div className="op-fc-node-badges">
        {hasVerify && <span className="op-fc-badge is-verify">✓ {t('verify', 'verify')}</span>}
        {isTask && <span className="op-fc-badge is-task">{t('taskBadge', 'task')}</span>}
        {isScript && <span className="op-fc-badge is-script">{t('scriptBadge', 'script')}</span>}
        {isCompute && <span className="op-fc-badge is-compute">ƒ {t('computeBadge', 'compute')}</span>}
        {isOrphan && <span className="op-fc-badge is-orphan-badge">⚠ {t('notConnectedShort', 'not connected')}</span>}
      </div>
      {editable && (
        <div className="op-fc-node-actions">
          {!isStart && (
            <button
              type="button"
              className="op-fc-node-btn nodrag"
              title={t('setStart', 'Set as start')}
              onClick={(e) => { e.stopPropagation(); onSetStart() }}
            >▶</button>
          )}
          <button
            type="button"
            className="op-fc-node-btn nodrag"
            title={t('delete', 'Delete')}
            onClick={(e) => { e.stopPropagation(); onDelete() }}
          >✕</button>
        </div>
      )}
      <Handle type="source" position={Position.Right} className="op-fc-handle" />
    </div>
  )
}

// --- custom edge (solid next / dashed labelled branch / amber loop) --------------------------------
function StepEdge(props: EdgeProps<Edge<StepEdgeData>>) {
  const t = useT('processDesign')
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, data, selected, markerEnd } = props
  const [editX, setEditX] = useState('')
  const [editing, setEditing] = useState(false)
  const [edgePath, labelX, labelY] = getBezierPath({
    sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition,
  })
  const d = data as StepEdgeData
  const isBranch = d?.kind === 'branch'
  const isLoop = !!d?.isLoop
  const when = d?.when ?? ''
  const badVars = isBranch && when ? unknownExpressionVars(when, d.knownVars) : []
  const syntaxErr = isBranch && when ? validateCondition(when) : null
  const invalid = isBranch && !!when && (syntaxErr != null || badVars.length > 0)

  const beginEdit = () => { setEditX(when); setEditing(true) }
  const commit = () => {
    if (d && d.branchIndex != null) d.onEditWhen(d.from, d.branchIndex, editX)
    setEditing(false)
  }

  const labelText = isBranch ? (when || t('branchLabel', '(branch)')) : ''
  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        markerEnd={markerEnd}
        className={
          'op-fc-edge' +
          (isBranch ? ' is-branch' : ' is-next') +
          (isLoop ? ' is-loop' : '') +
          (selected ? ' is-selected' : '') +
          (invalid ? ' is-invalid' : '')
        }
      />
      {(isBranch || selected) && (
        <EdgeLabelRenderer>
          <div
            className={'op-fc-edge-label nodrag nopan' + (invalid ? ' is-invalid' : '') + (isLoop ? ' is-loop' : '')}
            style={{ transform: `translate(-50%,-50%) translate(${labelX}px,${labelY}px)` }}
          >
            {editing && d?.editable && isBranch ? (
              <span className="op-fc-edge-edit">
                <input
                  autoFocus
                  value={editX}
                  placeholder={t('branchCondPlaceholder', 'condition, e.g. answer == true')}
                  onChange={(e) => setEditX(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') commit()
                    else if (e.key === 'Escape') setEditing(false)
                  }}
                  onBlur={commit}
                />
              </span>
            ) : (
              <>
                {isBranch && (
                  <button
                    type="button"
                    className="op-fc-edge-cond"
                    title={invalid ? (syntaxErr ?? `${t('condUnknownVar', 'Unknown variable')}: ${badVars.join(', ')}`) : t('editCondition', 'Edit branch condition')}
                    onClick={(e) => { e.stopPropagation(); if (d?.editable) beginEdit() }}
                  >
                    {labelText}{invalid ? ' ⚠' : ''}
                  </button>
                )}
                {selected && d?.editable && (
                  <button
                    type="button"
                    className="op-fc-edge-del"
                    title={t('deleteLink', 'Delete link')}
                    onClick={(e) => { e.stopPropagation(); d.onDelete(d) }}
                  >✕</button>
                )}
              </>
            )}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  )
}

const NODE_TYPES: NodeTypes = { step: StepNode }
const EDGE_TYPES: EdgeTypes = { step: StepEdge }

function InnerCanvas({
  def, flow, selectedId, simStepId, editable,
  onSelect, onChangeStep, onSetStart, onDeleteStep, onMoveStep, onLayout,
}: FlowCanvasProps) {
  const t = useT('processDesign')
  const knownVars = useMemo(() => def.dataSchema.map((v) => v.name), [def.dataSchema])

  // --- edit a branch condition in place ----------------------------------------------------------
  const editWhen = useCallback((from: string, index: number, when: string) => {
    const step = def.steps[from]
    if (!step || !step.transitions) return
    const transitions = step.transitions.map((tr, i) => (i === index ? { ...tr, when } : tr))
    onChangeStep(from, { ...step, transitions } as Step)
  }, [def.steps, onChangeStep])

  // --- delete a link (clear next, or drop a transition) ------------------------------------------
  const deleteLink = useCallback((edge: StepEdgeData) => {
    const step = def.steps[edge.from]
    if (!step) return
    if (edge.kind === 'next') {
      onChangeStep(edge.from, { ...step, next: undefined } as Step)
    } else if (edge.branchIndex != null && step.transitions) {
      const transitions = step.transitions.filter((_, i) => i !== edge.branchIndex)
      onChangeStep(edge.from, { ...step, transitions: transitions.length ? transitions : undefined } as Step)
    }
  }, [def.steps, onChangeStep])

  // --- build nodes from the model ----------------------------------------------------------------
  const layout = useMemo(() => autoLayout(def, flow), [def, flow])
  const modelNodes = useMemo<Node<StepNodeData>[]>(() => {
    const orphanSet = new Set(flow.unreachable)
    return Object.entries(def.steps).map(([id, step]) => {
      const pos = step.ui ?? layout[id] ?? { x: 0, y: 0 }
      return {
        id,
        type: 'step',
        position: { x: pos.x, y: pos.y },
        data: {
          step,
          isStart: def.start === id,
          isOrphan: orphanSet.has(id),
          isSim: simStepId === id,
          editable,
          label: id,
          onSetStart: () => onSetStart(id),
          onDelete: () => onDeleteStep(id),
        },
        selected: selectedId === id,
      } satisfies Node<StepNodeData>
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [def.steps, def.start, flow.unreachable, layout, simStepId, editable, selectedId])

  // --- build edges from the model ----------------------------------------------------------------
  const modelEdges = useMemo<Edge<StepEdgeData>[]>(() => {
    const out: Edge<StepEdgeData>[] = []
    for (const [from, step] of Object.entries(def.steps)) {
      ;(step.transitions ?? []).forEach((tr, i) => {
        if (!tr.to || !def.steps[tr.to]) return
        out.push({
          id: `b:${from}:${i}`,
          source: from,
          target: tr.to,
          type: 'step',
          markerEnd: { type: MarkerType.ArrowClosed, color: flow.loopBack.has(`${from}→${tr.to}`) ? '#f4b860' : 'rgba(141,198,63,.7)' },
          animated: flow.loopBack.has(`${from}→${tr.to}`),
          data: {
            kind: 'branch', from, to: tr.to, when: tr.when, branchIndex: i,
            isLoop: flow.loopBack.has(`${from}→${tr.to}`), knownVars, editable,
            onEditWhen: editWhen, onDelete: deleteLink,
          },
        })
      })
      if (step.next && def.steps[step.next]) {
        out.push({
          id: `n:${from}`,
          source: from,
          target: step.next,
          type: 'step',
          markerEnd: { type: MarkerType.ArrowClosed, color: flow.loopBack.has(`${from}→${step.next}`) ? '#f4b860' : 'rgba(141,198,63,.7)' },
          animated: flow.loopBack.has(`${from}→${step.next}`),
          data: {
            kind: 'next', from, to: step.next,
            isLoop: flow.loopBack.has(`${from}→${step.next}`), knownVars, editable,
            onEditWhen: editWhen, onDelete: deleteLink,
          },
        })
      }
    }
    return out
  }, [def.steps, flow.loopBack, knownVars, editable, editWhen, deleteLink])

  const [nodes, setNodes, onNodesChange] = useNodesState(modelNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState(modelEdges)
  const [rfInstance, setRfInstance] = useState<ReactFlowInstance<Node<StepNodeData>, Edge<StepEdgeData>> | null>(null)

  // Keep the canvas in sync with the model (positions preserved while dragging by React Flow itself;
  // structural changes flow back in here). We re-derive on every model change.
  useEffect(() => { setNodes(modelNodes) }, [modelNodes, setNodes])
  useEffect(() => { setEdges(modelEdges) }, [modelEdges, setEdges])

  // Fit view once, after the first nodes are present.
  const [didFit, setDidFit] = useState(false)
  useEffect(() => {
    if (!didFit && rfInstance && nodes.length > 0) {
      rfInstance.fitView({ padding: 0.2, maxZoom: 1 })
      setDidFit(true)
    }
  }, [didFit, rfInstance, nodes.length])

  // --- create a link by dragging a connector -----------------------------------------------------
  const onConnect: OnConnect = useCallback((conn: Connection) => {
    if (!editable) return
    const from = conn.source
    const to = conn.target
    if (!from || !to || from === to) return
    const step = def.steps[from]
    if (!step) return
    // Prevent duplicate identical edges (same source -> target via next or an existing branch).
    if (step.next === to) return
    if ((step.transitions ?? []).some((tr) => tr.to === to)) return
    if (!step.next) {
      onChangeStep(from, { ...step, next: to } as Step)
    } else {
      const transitions = [...(step.transitions ?? []), { when: '', to }]
      onChangeStep(from, { ...step, transitions } as Step)
    }
  }, [editable, def.steps, onChangeStep])

  // --- persist node position on drag stop --------------------------------------------------------
  // Node position is pure layout (non-behavioural), so arranging the canvas is allowed even on a
  // read-only version; it persists when the version is an editable draft.
  const onNodeDragStop = useCallback((_e: unknown, node: Node) => {
    onMoveStep(node.id, Math.round(node.position.x), Math.round(node.position.y))
  }, [onMoveStep])

  // --- Tidy: recompute the layered auto-layout for ALL steps, overwriting each step's `ui` in one
  // update, then fit the view. Layout is non-behavioural, so this is allowed even on a read-only
  // version. ---------------------------------------------------------------------------------------
  const onTidy = useCallback(() => {
    onLayout(autoLayout(def, flow))
    // Fit once the re-positioned nodes have flowed back in.
    window.requestAnimationFrame(() => rfInstance?.fitView({ padding: 0.2, maxZoom: 1 }))
  }, [def, flow, onLayout, rfInstance])

  // --- selection ---------------------------------------------------------------------------------
  const onNodeClick = useCallback((_e: unknown, node: Node) => { onSelect(node.id) }, [onSelect])
  const onPaneClick = useCallback(() => { onSelect(null) }, [onSelect])

  // Delete-key removal of the selected edge.
  const onEdgesDelete = useCallback((removed: Edge[]) => {
    if (!editable) return
    for (const e of removed) {
      const d = e.data as StepEdgeData | undefined
      if (d) deleteLink(d)
    }
  }, [editable, deleteLink])

  return (
    <div className="op-fc-canvas">
      <div className="op-fc-toolbar">
        <button
          type="button"
          className="btn btn-ghost op-fc-tidy nodrag nopan"
          title={t('tidyHelp', 'Auto-arrange the nodes left to right')}
          onClick={onTidy}
        >
          {t('tidy', 'Tidy')}
        </button>
      </div>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={NODE_TYPES}
        edgeTypes={EDGE_TYPES}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        onNodeDragStop={onNodeDragStop}
        onNodeClick={onNodeClick}
        onPaneClick={onPaneClick}
        onEdgesDelete={onEdgesDelete}
        onInit={setRfInstance}
        defaultEdgeOptions={{ type: 'step' }}
        nodesDraggable={true}
        nodesConnectable={editable}
        elementsSelectable
        proOptions={{ hideAttribution: true }}
        fitView
        minZoom={0.2}
        maxZoom={1.6}
      >
        <Background gap={18} color="rgba(141,198,63,.08)" />
        <Controls showInteractive={false} />
        <MiniMap
          pannable
          zoomable
          nodeColor={(n) => (n.id === def.start ? '#8DC63F' : 'rgba(141,198,63,.35)')}
          maskColor="rgba(6,24,18,.7)"
          className="op-fc-minimap"
        />
      </ReactFlow>
      {nodes.length === 0 && (
        <div className="op-fc-empty muted">{t('canvasEmpty', 'Add a step from the palette to start the flow.')}</div>
      )}
    </div>
  )
}

export default function FlowCanvas(props: FlowCanvasProps) {
  return (
    <ReactFlowProvider>
      <InnerCanvas {...props} />
    </ReactFlowProvider>
  )
}
