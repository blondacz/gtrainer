import { useEffect, useState } from 'react'
import { developmentRead } from './development-api'
import type { Catalogue, Diagnostics, Queue, Run } from './development-types'

/** Read-only browser single flight. Aborted selection/visibility changes cannot restore stale payload. */
export function useDevelopmentInspection(runId: string, refresh: number) {
  const [catalogue, setCatalogue] = useState<Catalogue | null>(null)
  const [queue, setQueue] = useState<Queue>({ tasks: [], maintenance: [] })
  const [diagnostics, setDiagnostics] = useState<Diagnostics | null>(null)
  const [run, setRun] = useState<Run | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    let reading = false
    async function read() {
      if (reading) return
      reading = true
      try {
        const [config, tasks, summary] = await Promise.all([
          developmentRead<Catalogue>('/api/development/configuration', controller.signal),
          developmentRead<Queue>('/api/development/queue', controller.signal),
          developmentRead<Diagnostics>('/api/development/diagnostics', controller.signal),
        ])
        if (controller.signal.aborted) return
        setCatalogue(config); setQueue(tasks); setDiagnostics(summary)
        if (runId) {
          try { const result = await developmentRead<Run>(`/api/development/runs/${runId}`, controller.signal); if (!controller.signal.aborted) setRun(result) }
          catch { if (!controller.signal.aborted) { setRun(null); setError('This run is unavailable or its retained payload was deleted.') } }
        }
      } catch { if (!controller.signal.aborted) { setRun(null); setError('Development inspection unavailable.') } }
      finally { reading = false }
    }
    void read()
    // Read-only browser refresh, never a worker housekeeping timer or a POST.
    const timer = window.setInterval(() => void read(), 5_000)
    return () => { controller.abort(); window.clearInterval(timer) }
  }, [runId, refresh])
  return { catalogue, queue, diagnostics, run, setRun, error, setError }
}
