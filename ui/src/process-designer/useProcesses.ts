// Loads the active configurable processes (GET /api/process-designer/processes) for the operator
// menu tiles + the designer list. Degrades quietly: if the backend is unreachable (offline / not
// deployed) it returns an empty list and the static tiles still render. Cached briefly across mounts.

import { useEffect, useState } from 'react'
import { listProcesses } from './api'
import type { ProcessSummary } from './model'

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
