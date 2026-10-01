import { useEffect, useRef, useState } from 'react'
import type { TrendReport } from './TrendPanel'

type Model = { id: string; label: string; tag: string; digest: string; experimental: boolean }
type Models = { selectedModelId: string | null; selectionVersion: number; models: Model[]; reason: string; hostedEnabled: false }
type Support = { evidenceId: string; period: string; oldest: string; newest: string; label: string; value: number | null; unit: string
  sampleCount: number; observedDays: number; periodDays: number; sources: string[]; metricOrigins: string[]; flags: string[] }
type Result = { status: 'available' | 'unavailable'; reason: string; model: Model | null; evidenceReportSha256: string
  observations: { text: string; evidenceIds: string[]; supportingMetrics: Support[] }[]; limitations: string[]
  unavailable: { key: string; label: string; reason: string }[]; sourceStatus: { category: string; readStatus: string }[] }
const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(v => typeof v === 'string')
const hash = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
const reasons: Record<string, string> = {
  local_not_configured: 'Local model service is not configured. Charts remain available.',
  local_configuration_invalid: 'Local model configuration is invalid. No inference is enabled.',
  model_not_selected: 'No model selected. No data is sent to a model.',
  experimental_local_selected: 'Experimental local model selected. Generation still requires your request.',
  insufficient_input: 'Too few populated activity and wellness comparisons. Missing data does not prove inactivity or illness.',
  model_unavailable: 'Selected local model is unavailable or its exact artifact could not be verified.',
  model_timeout: 'Local inference exceeded its time budget.',
  unusable_model_output: 'Model output failed validation. No unsupported observation is displayed.',
  model_selection_changed: 'Model selection changed. Refresh this panel before trying again.',
  evidence_changed: 'Imported evidence or status changed. Refresh the factual charts before generating again.',
  analysis_busy: 'Another analysis is running. Charts remain available; retry later.',
}

function modelsFrom(value: unknown): Models {
  const data = value as Models
  if (!data || data.hostedEnabled !== false || !Number.isSafeInteger(data.selectionVersion) || data.selectionVersion < 0 ||
      !Array.isArray(data.models) || data.models.length > 4 || !data.models.every(m => m && typeof m.id === 'string' &&
        typeof m.label === 'string' && typeof m.tag === 'string' && hash(m.digest) && typeof m.experimental === 'boolean') ||
      (data.selectedModelId !== null && !data.models.some(m => m.id === data.selectedModelId)) || typeof data.reason !== 'string') {
    throw new Error('Invalid private model configuration')
  }
  return data
}

function resultFrom(value: unknown, digest: string, selected: Model, report: TrendReport): Result {
  const data = value as Result
  if (!data || !['available', 'unavailable'].includes(data.status) || !hash(data.evidenceReportSha256) || typeof data.reason !== 'string' ||
      !strings(data.limitations) ||
      !Array.isArray(data.unavailable) || !data.unavailable.every(m => m && typeof m.label === 'string' && typeof m.reason === 'string') ||
      !Array.isArray(data.sourceStatus) || !data.sourceStatus.every(s => s && typeof s.category === 'string' && typeof s.readStatus === 'string') ||
      !Array.isArray(data.observations) || data.observations.length > 2 ||
      (data.status === 'available' ? data.observations.length === 0 : data.observations.length !== 0)) throw new Error('Invalid private analysis')
  if (data.status === 'unavailable') return data // Only fixed reason text is displayed; no stale supporting data is rendered.
  if (data.evidenceReportSha256 !== digest || !data.model || data.model.id !== selected.id || data.model.digest !== selected.digest ||
      data.reason !== 'validated_typed_observations') throw new Error('Mismatched private analysis')
  for (const observation of data.observations) {
    if (!observation || typeof observation.text !== 'string' || observation.text.length > 6000 || !strings(observation.evidenceIds) || !observation.evidenceIds.length ||
        !Array.isArray(observation.supportingMetrics) || !observation.supportingMetrics.length) throw new Error('Invalid private evidence')
    for (const metric of observation.supportingMetrics) {
      const period = metric?.period === 'current' ? report.current : metric?.period === 'previous' ? report.previous : null
      if (!metric || !period || metric.oldest !== period.oldest || metric.newest !== period.newest ||
          !observation.evidenceIds.includes(metric.evidenceId) || typeof metric.label !== 'string' || typeof metric.unit !== 'string' ||
          (metric.value !== null && (typeof metric.value !== 'number' || !Number.isFinite(metric.value))) ||
          !Number.isSafeInteger(metric.sampleCount) || metric.sampleCount < 0 || !Number.isSafeInteger(metric.observedDays) || metric.observedDays < 0 ||
          !Number.isSafeInteger(metric.periodDays) || metric.periodDays !== period.days ||
          !strings(metric.sources) || !strings(metric.metricOrigins) || !strings(metric.flags)) throw new Error('Invalid private evidence')
    }
    if (!observation.evidenceIds.every(id => observation.supportingMetrics.some(metric => metric.evidenceId === id))) throw new Error('Missing private evidence')
  }
  return data
}

export function AnalysisPanel({ csrfToken, report, digest }: { csrfToken: string; report: TrendReport; digest: string | null }) {
  const [models, setModels] = useState<Models | null>(null)
  const [result, setResult] = useState<Result | null>(null)
  const [message, setMessage] = useState('Loading local model configuration…')
  const [choosing, setChoosing] = useState(false)
  const [busy, setBusy] = useState(false)
  const lifetime = useRef<AbortController | null>(null)
  const generation = useRef<AbortController | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    lifetime.current = controller
    async function load() {
      try {
        const response = await fetch('/api/models', { cache: 'no-store', signal: controller.signal })
        if (!response.ok) throw new Error('Configuration unavailable')
        const data = modelsFrom(await response.json())
        if (!controller.signal.aborted) { setModels(data); setMessage(reasons[data.reason] ?? 'Model configuration loaded.') }
      } catch {
        if (!controller.signal.aborted) setMessage('AI analysis unavailable. Reconnect or sign in again; factual charts remain available.')
      }
    }
    void load()
    return () => { controller.abort(); generation.current?.abort() }
  }, [])

  async function select(modelId: string) {
    generation.current?.abort(); generation.current = null
    setResult(null); setBusy(false); setChoosing(true)
    try {
      const response = await fetch('/api/models', { method: 'PUT', cache: 'no-store', signal: lifetime.current?.signal,
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken }, body: JSON.stringify({ modelId: modelId || null }) })
      if (!response.ok) throw new Error('Selection unavailable')
      const data = modelsFrom(await response.json())
      if (!lifetime.current?.signal.aborted) { setModels(data); setMessage(reasons[data.reason] ?? 'Model selection updated.') }
    } catch {
      if (!lifetime.current?.signal.aborted) { setModels(null); setMessage('Could not verify model selection. No analysis can be requested from this panel.') }
    } finally { if (!lifetime.current?.signal.aborted) setChoosing(false) }
  }

  async function generate() {
    const selected = models?.models.find(m => m.id === models.selectedModelId)
    if (!selected || !digest || !hash(digest) || !models) return
    const controller = new AbortController()
    generation.current = controller
    setResult(null); setBusy(true); setMessage('Generating experimental local observations… Cold startup may take up to two minutes.')
    try {
      const response = await fetch('/api/analysis', { method: 'POST', cache: 'no-store', signal: controller.signal,
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
        body: JSON.stringify({ oldest: report.current.oldest, newest: report.current.newest, sport: report.selectedSport,
          evidenceReportSha256: digest, modelId: selected.id, selectionVersion: models.selectionVersion }) })
      if (!response.ok) throw new Error('Analysis unavailable')
      const data = resultFrom(await response.json(), digest, selected, report)
      if (!controller.signal.aborted && !lifetime.current?.signal.aborted) {
        setResult(data); setMessage(data.status === 'available' ? 'Validated model-selected observations. Personal wording and numbers are rendered by code.' : reasons[data.reason] ?? 'AI analysis unavailable.')
      }
    } catch {
      if (!controller.signal.aborted && !lifetime.current?.signal.aborted) setMessage('AI analysis unavailable or response could not be verified. Charts remain available.')
    } finally {
      if (generation.current === controller && !lifetime.current?.signal.aborted) { setBusy(false); generation.current = null }
    }
  }

  return <section aria-labelledby="analysis-heading" className="analysis-panel">
    <h3 id="analysis-heading">Experimental AI observations</h3>
    <p>Local inference only, with no hosted fallback. Select a configured model, then explicitly request analysis.
      Bookkeeping and facts stay in code; unsupported model output is rejected. Selection resets to off when the backend restarts.</p>
    <label htmlFor="analysis-model">Local model</label>
    <select id="analysis-model" value={models?.selectedModelId ?? ''} disabled={!models || choosing} onChange={event => void select(event.target.value)}>
      <option value="">Off — no inference</option>{models?.models.map(model => <option key={model.id} value={model.id}>{model.label}{model.experimental ? ' (experimental)' : ''}</option>)}
    </select>
    <button type="button" disabled={!models?.selectedModelId || choosing || busy || !digest || !hash(digest)} onClick={() => void generate()}>Generate observations for displayed period</button>
    {!digest && <p>Analysis requires a verified report snapshot. Refresh the factual charts.</p>}
    <p aria-live="polite">{message}</p>
    {result?.status === 'available' && <div>
      <p>AI model: {result.model?.label}; artifact {result.model?.digest}. Evidence snapshot: {result.evidenceReportSha256}.</p>
      {result.observations.map((observation, index) => <article key={index}>
        <h4>AI-selected observation {index + 1}</h4><p>{observation.text}</p>
        <details><summary>Supporting metrics and periods</summary><ul>{observation.supportingMetrics.map(metric => <li key={metric.evidenceId}>
          {metric.label}: {metric.value ?? 'unavailable'} {metric.unit}; {metric.oldest}–{metric.newest}; {metric.sampleCount} populated records across {metric.observedDays}/{metric.periodDays} dates.
          {' '}Sources: {metric.sources.join(', ') || 'unknown'}; metric origins: {metric.metricOrigins.join(', ') || 'unknown'}.
          {' '}Evidence: {metric.evidenceId}. Flags: {metric.flags.join(', ') || 'none'}.
        </li>)}</ul></details>
      </article>)}
      <details><summary>Missing data, source status, and analysis limits</summary>
        <ul>{result.unavailable.map(metric => <li key={metric.key}>{metric.label}: unavailable. {metric.reason}</li>)}</ul>
        <ul>{result.sourceStatus.map(status => <li key={status.category}>{status.category}: {status.readStatus}; upstream Garmin freshness remains unknown.</li>)}</ul>
        <ul>{result.limitations.map((limitation, index) => <li key={index}>{limitation}</li>)}</ul>
      </details>
    </div>}
  </section>
}
