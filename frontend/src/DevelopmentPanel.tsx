import { useState } from 'react'
import { developmentWrite } from './development-api'
import { DevelopmentBinding, DevelopmentRunDetails } from './DevelopmentRunDetails'
import { DevelopmentDiagnostics } from './DevelopmentDiagnostics'
import { useDevelopmentInspection } from './useDevelopmentInspection'
import type { Preview, Task } from './development-types'

const idPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const savedRun = () => { const id = new URLSearchParams(window.location.search).get('run') ?? ''; return idPattern.test(id) ? id : '' }

export function DevelopmentPanel({ csrfToken }: { csrfToken: string }) {
  const [caseId, setCaseId] = useState('')
  const [modelKey, setModelKey] = useState('')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [runId, setRunId] = useState(savedRun)
  const [busy, setBusy] = useState(false)
  const [refresh, setRefresh] = useState(0)
  const { catalogue, queue, diagnostics, run, setRun, error, setError } = useDevelopmentInspection(runId, refresh)
  const selected = catalogue?.models.find(model => JSON.stringify([model.providerId, model.modelId]) === modelKey)

  function selectRun(id: string) {
    if (!idPattern.test(id)) return
    setRun(null); setError(''); setRunId(id)
    setRefresh(value => value + 1)
    window.history.replaceState(null, '', `?run=${id}`) // Only the opaque run ID, never packets or settings.
  }
  async function action(operation: () => Promise<void>) {
    setBusy(true); setError('')
    try { await operation() } catch { setError('Development action refused or unavailable. No automatic inference retry was started.') }
    finally { setBusy(false) }
  }
  async function createPreview() {
    if (!selected || !caseId) return
    setPreview(null)
    await action(async () => { setPreview(await developmentWrite<Preview>('/api/development/previews',
      { caseId, providerId: selected.providerId, modelId: selected.modelId }, csrfToken)) })
  }
  async function submit() {
    if (!preview || !catalogue?.executionEnabled) return
    await action(async () => {
      const task = await developmentWrite<Task>('/api/development/runs', { previewId: preview.id }, csrfToken)
      selectRun(task.id); setPreview(null)
    })
  }
  async function cancel() {
    if (!run) return
    await action(async () => {
      const result = await developmentWrite<{ accepted: boolean; task: Task }>(`/api/development/runs/${run.task.id}/cancel`, {}, csrfToken)
      setRun({ ...run, task: result.task })
      if (!result.accepted) setError('Cancellation did not win: the existing outcome is unchanged.')
    })
  }
  return <section aria-labelledby="developer-heading">
    <h2 id="developer-heading">Developer</h2><p>Synthetic only · Unqualified · Results are not a completed benchmark.</p>
    {error && <p role="alert">{error}</p>}
    <p>Execution permission: {catalogue?.executionEnabled ? 'Explicit dedicated worker configured; safety gates still apply.' : 'Disabled: dedicated worker not configured.'}</p>
    <p>Payload expires 24 hours after run creation. Physical deletion occurs on later queue events, not an idle timer. Cancellation is distinct from confirmed stop.</p>
    <button onClick={() => setRefresh(value => value + 1)}>Refresh inspection only</button>
    <label>Synthetic case<select disabled={busy} value={caseId} onChange={event => { setCaseId(event.target.value); setPreview(null) }}>
      <option value="">Select a case</option>{catalogue?.cases.map(id => <option key={id}>{id}</option>)}</select></label>
    <label>Configured model<select disabled={busy} value={modelKey} onChange={event => { setModelKey(event.target.value); setPreview(null) }}>
      <option value="">Select a model</option>{catalogue?.models.map(model => <option key={JSON.stringify([model.providerId, model.modelId])} value={JSON.stringify([model.providerId, model.modelId])}>{model.label} · {model.providerId}/{model.modelId}</option>)}</select></label>
    <button disabled={busy || !caseId || !selected} onClick={() => void createPreview()}>Preview synthetic run</button>
    {preview && <article aria-label="Explicit preview"><h3>Preview {preview.caseId}</h3><DevelopmentBinding model={preview.model} policy={preview.policy} />
      <p>Prompt digest: {preview.promptDigest}</p><pre>{preview.exactPrompt.systemInstructions}</pre><pre>{preview.exactPrompt.userPacketJson}</pre>
      <button disabled={busy || !catalogue?.executionEnabled} onClick={() => void submit()}>Submit preview to queue</button></article>}
    <h3>Durable queue</h3>{queue.tasks.length ? <table><thead><tr><th>Order / case</th><th>Outcome</th><th>Execution</th><th>Retention</th><th>Inspect</th></tr></thead>
      <tbody>{queue.tasks.map(task => <tr key={task.id}><td>{task.queueOrder} · {task.caseId}</td><td>{task.outcome}</td><td>{task.execution}</td><td>{task.retention}</td>
        <td><button onClick={() => selectRun(task.id)}>Inspect {task.id}</button></td></tr>)}</tbody></table> : <p>No retained runs.</p>}
    {run && <><DevelopmentRunDetails run={run} /><button disabled={busy || !['QUEUED', 'RUNNING'].includes(run.task.outcome)} onClick={() => void cancel()}>Cancel run</button></>}
    {diagnostics && <DevelopmentDiagnostics diagnostics={diagnostics} />}
    <button disabled={busy} onClick={() => void action(async () => { await developmentWrite('/api/development/maintenance', {}, csrfToken) })}>Submit maintenance only</button>
    <p>Maintenance shares the inference owner, waits for a healthy active run, and makes no model request.</p>
    <ul>{queue.maintenance.map(task => <li key={task.id}>{task.id} · {task.state} · expires {task.expiresUtc}{task.reason && ` · ${task.reason.replaceAll('_', ' ')}`}</li>)}</ul>
  </section>
}
