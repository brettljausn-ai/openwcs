// Loads the active configurable processes (GET /api/process-designer/processes) for the operator
// menu tiles + the designer list. Degrades quietly: if the backend is unreachable (offline / not
// deployed) it returns an empty list and the static tiles still render. Cached briefly across mounts.

import { useEffect, useState } from 'react'
import { listProcesses, listTasks } from './api'
import { normalizeServerTask, TASK_LIBRARY, type ProcessSummary, type TaskTypeDef } from './model'

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
