import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { DevelopmentApp } from './DevelopmentApp'
import { DevelopmentPanel } from './DevelopmentPanel'
import { DevelopmentRunDetails } from './DevelopmentRunDetails'
import type { Catalogue, Diagnostics, Preview, Run, Task } from './development-types'

const id = '00000000-0000-0000-0000-000000000001'
const model = { providerId: 'ollama', modelId: 'synthetic', label: 'Synthetic model', tag: 'model:fixture', manifestDigest: 'a'.repeat(64),
  ollamaVersion: '0.35.0', contextWindow: 2048, tokenLimit: 1536, threads: 3, seed: 17, temperature: .1 }
const policy = { profile: 'connected-review-development-local-v1', attemptTimeoutMillis: 900000, totalTimeoutMillis: 1840000 }
const config: Catalogue = { syntheticOnly: true, qualified: false, executionEnabled: true, reason: 'synthetic_development_only',
  cases: ['synthetic-case'], models: [model], policy }
const task: Task = { id, previewId: id, queueOrder: 1, caseId: 'synthetic-case', slotKey: 'slot', inputRevision: 1, inputDigest: 'digest', model, policy,
  createdUtc: new Date().toISOString(), expiresUtc: new Date(Date.now() + 86400000).toISOString(), outcome: 'SUCCESSFUL', execution: 'STOPPED_CONFIRMED',
  retention: 'RETAINED', replacementId: null, reason: null, latestInput: true }
const prompt = { systemInstructions: 'Exact synthetic instructions', userPacketJson: JSON.stringify({ evidence: { facts: [{ id: 'fact-1' }] },
  context: [{ contextId: 'context-1', revision: 1, sourceCategory: 'user_report', authorAttribution: 'Synthetic user', observedOn: '2026-01-01', content: 'Synthetic report' }] }) }
const preview: Preview = { id, caseId: 'synthetic-case', model, policy, promptDigest: 'prompt-digest', exactPrompt: prompt }
const run: Run = { task, attempts: [{ number: 1, phase: 'ACCEPTED', reason: null, createdUtc: task.createdUtc }],
  inspection: { packetAvailable: true, exactPrompt: prompt, review: { interpretations: [], questions: [] } } }
const diagnostics: Diagnostics = { worker: { configured: true, executingRunId: null, blocked: false, reason: 'development_worker_ready',
  unresolvedBoundaries: 0, applianceMemoryLimitBytes: 5 * 1024 ** 3, minimumHostHeadroomBytes: 1024 ** 3, health: null }, summaries: [] }

function mockApi(options: { configured?: boolean; cancelled?: boolean; failure?: boolean; running?: boolean } = {}) {
  const mock = vi.fn(async (path: string | URL | Request, init?: RequestInit) => {
    if (options.failure) return new Response('private-error-marker', { status: 503 })
    const url = String(path)
    let body: unknown
    if (url === '/api/session') body = { csrfToken: 'csrf-synthetic' }
    else if (url === '/healthz') body = { status: 'ok' }
    else if (url.endsWith('/configuration')) body = { ...config, executionEnabled: options.configured !== false }
    else if (url.endsWith('/queue')) body = { tasks: [task], maintenance: [] }
    else if (url.endsWith('/diagnostics')) body = diagnostics
    else if (url.endsWith('/previews')) body = preview
    else if (url.endsWith('/cancel')) body = { accepted: options.cancelled !== false, task: { ...task, outcome: options.cancelled === false ? 'SUCCESSFUL' : 'CANCELLED' } }
    else if (url.endsWith('/maintenance')) body = { id, state: 'QUEUED' }
    else if (url.endsWith('/runs') && init?.method === 'POST') body = task
    else if (url.endsWith(`/runs/${id}`)) body = options.running ? { ...run, task: { ...task, outcome: 'RUNNING', execution: 'EXECUTING' } } : run
    else throw new Error(`unexpected route: ${url}`)
    return new Response(JSON.stringify(body), { status: init?.method === 'POST' ? 202 : 200, headers: { 'Content-Type': 'application/json' } })
  })
  vi.stubGlobal('fetch', mock)
  return mock
}
const writes = (mock: ReturnType<typeof mockApi>) => mock.mock.calls.filter(([, init]) => init?.method === 'POST')
async function selectPreview() {
  await screen.findByRole('option', { name: /Synthetic model/ })
  fireEvent.change(screen.getByLabelText('Synthetic case'), { target: { value: 'synthetic-case' } })
  fireEvent.change(screen.getByLabelText('Configured model'), { target: { value: JSON.stringify(['ollama', 'synthetic']) } })
  fireEvent.click(screen.getByRole('button', { name: 'Preview synthetic run' }))
  await screen.findByRole('article', { name: 'Explicit preview' })
}

describe('isolated development controls', () => {
  beforeEach(() => { window.history.replaceState(null, '', '/'); vi.unstubAllGlobals() })

  it('visibility reveals read-only diagnostics, never grants permission or submits work', async () => {
    const mock = mockApi({ configured: false })
    render(<DevelopmentApp />)
    const toggle = await screen.findByLabelText('Developer options')
    expect(screen.queryByRole('heading', { name: 'Developer' })).not.toBeInTheDocument()
    fireEvent.click(toggle)
    await screen.findByText('Disabled: dedicated worker not configured.', { exact: false })
    expect(writes(mock)).toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Preview synthetic run' })).toBeDisabled()
  })

  it('normal personal application exposes no developer switch or execution tab', async () => {
    mockApi()
    render(<App />)
    await screen.findByText('Backend available')
    expect(screen.queryByLabelText('Developer options')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Developer' })).not.toBeInTheDocument()
  })

  it('explicit case and model preview precedes authenticated submission and stores only run ID in URL', async () => {
    const mock = mockApi()
    render(<DevelopmentPanel csrfToken="csrf-synthetic" />)
    await selectPreview()
    expect(writes(mock)).toHaveLength(1)
    expect(screen.getByText('Exact synthetic instructions')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Submit preview to queue' }))
    await screen.findByRole('article', { name: 'Run details' })
    const submit = writes(mock).find(([url]) => String(url).endsWith('/runs'))!
    expect(JSON.parse(submit[1]!.body as string)).toEqual({ previewId: id })
    expect(submit[1]!.headers).toMatchObject({ 'X-CSRF-Token': 'csrf-synthetic' })
    expect(window.location.search).toBe(`?run=${id}`)
    expect(window.localStorage.length).toBe(0)
  })

  it('configuration refusal leaves preview visible without an enabled submit control', async () => {
    mockApi({ configured: false })
    render(<DevelopmentPanel csrfToken="csrf-synthetic" />)
    await selectPreview()
    expect(screen.getByRole('button', { name: 'Submit preview to queue' })).toBeDisabled()
  })

  it('refresh and read-only reconnect restore a run without any submission', async () => {
    window.history.replaceState(null, '', `?run=${id}`)
    const mock = mockApi()
    render(<DevelopmentPanel csrfToken="csrf-synthetic" />)
    await screen.findByRole('article', { name: 'Run details' })
    fireEvent.click(screen.getByRole('button', { name: 'Refresh inspection only' }))
    await waitFor(() => expect(mock.mock.calls.filter(([url]) => String(url).endsWith(`/runs/${id}`)).length).toBeGreaterThan(1))
    expect(writes(mock)).toHaveLength(0)
  })

  it('empty validated results remain explicitly unqualified and preserve independent dimensions', () => {
    render(<DevelopmentRunDetails run={run} />)
    expect(screen.getByText(/No connected insights were provided/)).toBeInTheDocument()
    expect(screen.getByText(/Outcome: SUCCESSFUL · Execution: STOPPED_CONFIRMED · Retention: RETAINED/)).toBeInTheDocument()
  })

  it('expired payload is hidden even if a stale response still contains prompt and validated text', () => {
    const expired = { ...run, task: { ...task, expiresUtc: '2020-01-01T00:00:00Z', retention: 'DELETION_DUE' },
      inspection: { ...run.inspection, exactPrompt: { systemInstructions: 'private-prompt-marker', userPacketJson: 'not even valid JSON' } } }
    render(<DevelopmentRunDetails run={expired} />)
    expect(screen.getByText(/Expired or removed payload is hidden/)).toBeInTheDocument()
    expect(screen.queryByText('private-prompt-marker')).not.toBeInTheDocument()
    expect(screen.queryByText(/No connected insights/)).not.toBeInTheDocument()
  })

  it('validated claims retain scope, source attribution and uncertainty', () => {
    const claim = { text: 'Synthetic interpretation', uncertainty: 'insufficient evidence', scope: { from: '2026-01-01', until: '2026-01-02', sport: 'running' },
      sources: { evidenceIds: ['fact-1'], context: [{ contextId: 'context-1', revision: 1 }] } }
    render(<DevelopmentRunDetails run={{ ...run, inspection: { ...run.inspection, review: { interpretations: [claim], questions: [] } } }} />)
    expect(screen.getByText('Uncertainty: insufficient evidence')).toBeInTheDocument()
    expect(screen.getByText(/Scope: 2026-01-01 to 2026-01-02/)).toBeInTheDocument()
    expect(screen.getByText(/user report · Synthetic user · observed/)).toBeInTheDocument()
  })

  it('timeout, uncertain stop and older-input result are explained separately', () => {
    render(<DevelopmentRunDetails run={{ ...run, task: { ...task, outcome: 'FAILED', execution: 'UNKNOWN', reason: 'total_timeout', latestInput: false } }} />)
    expect(screen.getByText('Reason: total timeout')).toBeInTheDocument()
    expect(screen.getByText(/Runtime stop is unconfirmed/)).toBeInTheDocument()
    expect(screen.getByText(/result is not the latest-input view/)).toBeInTheDocument()
  })

  it('maintenance is a single explicit csrf-protected write and does not submit inference', async () => {
    const mock = mockApi()
    render(<DevelopmentPanel csrfToken="csrf-synthetic" />)
    fireEvent.click(screen.getByRole('button', { name: 'Submit maintenance only' }))
    await waitFor(() => expect(writes(mock)).toHaveLength(1))
    expect(writes(mock)[0][0]).toBe('/api/development/maintenance')
    expect(writes(mock)[0][1]!.body).toBe('{}')
  })

  it('explicit cancellation reports already-published outcome instead of pretending the process stopped', async () => {
    window.history.replaceState(null, '', `?run=${id}`)
    const mock = mockApi({ running: true, cancelled: false })
    render(<DevelopmentPanel csrfToken="csrf-synthetic" />)
    const cancel = await screen.findByRole('button', { name: 'Cancel run' })
    fireEvent.click(cancel)
    expect(await screen.findByRole('alert')).toHaveTextContent('Cancellation did not win: the existing outcome is unchanged.')
    expect(screen.getByText(/Outcome: SUCCESSFUL/)).toBeInTheDocument()
    expect(writes(mock)).toHaveLength(1)
    expect(writes(mock)[0][0]).toBe(`/api/development/runs/${id}/cancel`)
  })

  it('raw errors do not enter rendered failures', async () => {
    mockApi({ failure: true })
    render(<DevelopmentPanel csrfToken="csrf-synthetic" />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Development inspection unavailable.')
    expect(screen.queryByText('private-error-marker')).not.toBeInTheDocument()
  })
})
