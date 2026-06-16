// Loads the active configurable processes (GET /api/process-designer/processes) for the operator
// menu tiles + the designer list. Degrades quietly: if the backend is unreachable (offline / not
// deployed) it returns an empty list and the static tiles still render. Cached briefly across mounts.

import { useEffect, useState } from 'react'
import { getCapabilities, listProcesses, listTasks } from './api'
import {
  DISABLED_CAPABILITIES,
  normalizeServerTask,
  TASK_LIBRARY,
  type Capabilities,
  type ProcessSummary,
  type TaskTypeDef,
} from './model'

export function useProcesses(): { processes: ProcessSummary[]; loading: boolean; error: string | null } {
  const [processes, setProcesses] = useState<ProcessSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    listProcesses()
      .then((rows) => {
        if (!cancelled) setProcesses(rows)
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
  }, [])

  return { processes, loading, error }
}

/**
 * The live server task catalog (GET /api/process-designer/tasks) for the designer's task picker +
 * input/output mapping. Degrades gracefully: if the fetch fails (offline / not deployed) it falls
 * back to the static {@link TASK_LIBRARY} so the designer is still usable. `fallback` flags which
 * source is in effect.
 */
export function useTasks(): { tasks: TaskTypeDef[]; loading: boolean; fallback: boolean; error: string | null } {
  const [tasks, setTasks] = useState<TaskTypeDef[]>(TASK_LIBRARY)
  const [loading, setLoading] = useState(true)
  const [fallback, setFallback] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    listTasks()
      .then((rows) => {
        if (cancelled) return
        if (Array.isArray(rows) && rows.length > 0) {
          setTasks(rows.map(normalizeServerTask))
          setFallback(false)
        }
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e)) // keep the fallback list
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return { tasks, loading, fallback, error }
}

/**
 * Phase 3 server capabilities (GET /api/process-designer/capabilities) gating the new UI: the
 * script-step editor and the AI "describe the task" assist. Degrades gracefully: if the fetch fails
 * (offline / older server / not deployed) it stays at {@link DISABLED_CAPABILITIES} so the new
 * features are simply hidden rather than crashing the designer.
 */
export function useCapabilities(): { capabilities: Capabilities; loading: boolean } {
  const [capabilities, setCapabilities] = useState<Capabilities>(DISABLED_CAPABILITIES)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    getCapabilities()
      .then((caps) => {
        if (!cancelled && caps) setCapabilities({ ...DISABLED_CAPABILITIES, ...caps })
      })
      .catch(() => {
        /* keep DISABLED_CAPABILITIES — features hidden, designer still works */
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return { capabilities, loading }
}
