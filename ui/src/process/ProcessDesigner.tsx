import { useCallback, useEffect, useRef, useState } from 'react'
import Modeler from 'bpmn-js/lib/Modeler'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css'
import {
  completeTask,
  deployDefinition,
  listDefinitions,
  listTasks,
  startInstance,
  type Instance,
  type ProcessDefinition,
  type Task,
} from './api'

export default function ProcessDesigner() {
  const canvasRef = useRef<HTMLDivElement | null>(null)
  // bpmn-js modeler instance (loosely typed; the lib ships limited deep-path types).
  const modelerRef = useRef<any>(null)

  const [name, setName] = useState('process')
  const [status, setStatus] = useState('')
  const [definitions, setDefinitions] = useState<ProcessDefinition[]>([])
  const [processKey, setProcessKey] = useState('')
  const [variablesJson, setVariablesJson] = useState('{\n  "warehouseId": ""\n}')
  const [instance, setInstance] = useState<Instance | null>(null)
  const [tasks, setTasks] = useState<Task[]>([])

  useEffect(() => {
    const modeler = new Modeler({ container: canvasRef.current })
    modelerRef.current = modeler
    modeler.createDiagram().catch((e: unknown) => setStatus(String(e)))
    return () => modeler.destroy()
  }, [])

  const refreshDefinitions = useCallback(async () => {
    try {
      setDefinitions(await listDefinitions())
    } catch (e) {
      setStatus(String(e))
    }
  }, [])

  useEffect(() => {
    void refreshDefinitions()
  }, [refreshDefinitions])

  const deploy = useCallback(async () => {
    try {
      const { xml } = await modelerRef.current.saveXML({ format: true })
      const defs = await deployDefinition(name, xml as string)
      setStatus(`Deployed ${defs.map((d) => `${d.key} v${d.version}`).join(', ')}`)
      await refreshDefinitions()
    } catch (e) {
      setStatus(String(e))
    }
  }, [name, refreshDefinitions])

  const refreshTasks = useCallback(async (instanceId: string) => {
    try {
      setTasks(await listTasks(instanceId))
    } catch (e) {
      setStatus(String(e))
    }
  }, [])

  const start = useCallback(async () => {
    if (!processKey) {
      setStatus('Pick a process to start')
      return
    }
    let variables: Record<string, unknown> = {}
    try {
      variables = JSON.parse(variablesJson || '{}')
    } catch {
      setStatus('Variables must be valid JSON')
      return
    }
    try {
      const inst = await startInstance({ processKey, variables })
      setInstance(inst)
      setStatus(inst.ended ? `Instance ${inst.id} completed` : `Instance ${inst.id} running`)
      await refreshTasks(inst.id)
    } catch (e) {
      setStatus(String(e))
    }
  }, [processKey, variablesJson, refreshTasks])

  const complete = useCallback(async (taskId: string) => {
    if (!instance) return
    try {
      await completeTask(taskId)
      await refreshTasks(instance.id)
      setStatus('Task completed')
    } catch (e) {
      setStatus(String(e))
    }
  }, [instance, refreshTasks])

  return (
    <div style={{ display: 'flex', height: '100%', fontFamily: 'var(--font-body, sans-serif)' }}>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        <div style={{ padding: '0.5rem', display: 'flex', gap: '0.5rem', alignItems: 'center', borderBottom: '1px solid #ddd' }}>
          <strong>BPMN designer</strong>
          <input placeholder="process name" value={name} onChange={(e) => setName(e.target.value)} />
          <button onClick={deploy}>Deploy</button>
          <span style={{ color: 'var(--text-dim,#666)' }}>{status}</span>
        </div>
        <div ref={canvasRef} style={{ flex: 1, minHeight: 0 }} />
      </div>

      <aside style={{ width: 320, padding: '0.75rem', borderLeft: '1px solid #ddd', overflowY: 'auto', fontSize: 13 }}>
        <section>
          <h3>Definitions</h3>
          <button onClick={refreshDefinitions}>Refresh</button>
          <ul style={{ paddingLeft: '1rem' }}>
            {definitions.map((d) => (
              <li key={d.id}>
                <button onClick={() => setProcessKey(d.key)} style={{ all: 'unset', cursor: 'pointer', color: '#06c' }}>
                  {d.key}
                </button> v{d.version}{d.name ? ` — ${d.name}` : ''}
              </li>
            ))}
          </ul>
        </section>
        <section>
          <h3>Start instance</h3>
          <label style={{ display: 'block' }}>
            <div style={{ color: 'var(--text-dim,#666)' }}>Process key</div>
            <input value={processKey} onChange={(e) => setProcessKey(e.target.value)} style={{ width: '100%' }} />
          </label>
          <label style={{ display: 'block', marginTop: '0.5rem' }}>
            <div style={{ color: 'var(--text-dim,#666)' }}>Variables (JSON)</div>
            <textarea value={variablesJson} onChange={(e) => setVariablesJson(e.target.value)} rows={6} style={{ width: '100%', fontFamily: 'monospace' }} />
          </label>
          <button onClick={start} style={{ marginTop: '0.5rem' }}>Start</button>
        </section>
        {instance && (
          <section>
            <h3>Instance</h3>
            <div style={{ color: 'var(--text-dim,#666)' }}>{instance.id} {instance.ended ? '(ended)' : '(running)'}</div>
            <button onClick={() => refreshTasks(instance.id)}>Refresh tasks</button>
            <ul style={{ paddingLeft: '1rem' }}>
              {tasks.map((t) => (
                <li key={t.id}>
                  {t.name ?? t.id} <button onClick={() => complete(t.id)}>Complete</button>
                </li>
              ))}
              {tasks.length === 0 && <li style={{ color: 'var(--text-dim,#666)' }}>no open tasks</li>}
            </ul>
          </section>
        )}
      </aside>
    </div>
  )
}
