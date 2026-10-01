import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AnalysisPanel } from './AnalysisPanel'
import type { TrendReport } from './TrendPanel'

const csrf = 's'.repeat(43)
const digest = 'd'.repeat(64)
const model = { id: 'synthetic-local', label: 'Synthetic local', tag: 'synthetic:3b', digest: 'a'.repeat(64), experimental: true }
const second = { ...model, id: 'synthetic-other', label: 'Synthetic other', tag: 'other:3b', digest: 'b'.repeat(64) }
const catalogue = (selected: string | null = null, selectionVersion = 0) => ({ selectedModelId: selected, selectionVersion,
  models: [model, second], hostedEnabled: false, reason: selected ? 'experimental_local_selected' : 'model_not_selected' })
const report: TrendReport = { evaluatedOnUtc: '2020-06-10', selectedSport: 'Ride', availableSports: ['Ride'], comparisons: [], sourceStatus: [], unavailable: [], dateBasis: 'source dates',
  current: { oldest: '2020-06-01', newest: '2020-06-02', days: 2, activityRecords: 2, wellnessRecords: 2, sports: [], wellness: [], flags: [] },
  previous: { oldest: '2020-05-30', newest: '2020-05-31', days: 2, activityRecords: 2, wellnessRecords: 2, sports: [], wellness: [], flags: [] } }
const support = (period: 'previous' | 'current') => ({ evidenceId: `${period}/Ride/movingTime`, period,
  oldest: report[period].oldest, newest: report[period].newest, label: 'Recorded activity time (moving)', value: period === 'current' ? 3600 : 2400,
  unit: 'seconds', sampleCount: 2, observedDays: 2, periodDays: 2, sources: ['intervals.icu'], metricOrigins: [], flags: ['metric_origin_unknown'] })
const result = () => ({ status: 'available', reason: 'validated_typed_observations', model, evidenceReportSha256: digest,
  observations: [{ text: 'Ride recorded time increased; sleep decreased over the same periods. This does not establish recovery.',
    evidenceIds: ['previous/Ride/movingTime', 'current/Ride/movingTime'], supportingMetrics: [support('previous'), support('current')] }],
  unavailable: [{ key: 'fitnessAge', label: 'Garmin fitness age', reason: 'Not supplied; not estimated.' }],
  sourceStatus: [{ category: 'wellness', readStatus: 'KEY_REJECTED' }], limitations: ['Missing imports do not prove missed activity.'] })
const response = (body: unknown) => ({ ok: true, json: async () => body })
afterEach(() => vi.unstubAllGlobals())

describe('explicit experimental local observations', () => {
  it('starts off, selection never generates and explicit generation carries exact displayed range binding and CSRF', async () => {
    const fetchMock = vi.fn(async (path: string, options?: RequestInit) => {
      if (path === '/api/models') return response(options?.method === 'PUT' ? catalogue(model.id, 1) : catalogue())
      return response(result())
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<AnalysisPanel csrfToken={csrf} report={report} digest={digest} />)
    expect(await screen.findByText('No model selected. No data is sent to a model.')).toBeInTheDocument()
    const button = screen.getByRole('button', { name: /Generate observations/ })
    expect(button).toBeDisabled()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    fireEvent.change(screen.getByLabelText('Local model'), { target: { value: model.id } })
    await waitFor(() => expect(button).not.toBeDisabled())
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/models', expect.objectContaining({ method: 'PUT', cache: 'no-store',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf }, body: JSON.stringify({ modelId: model.id }) }))
    fireEvent.click(button)
    expect(await screen.findByText(/Validated model-selected observations/)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/analysis', expect.objectContaining({ method: 'POST', cache: 'no-store',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf }, body: JSON.stringify({ oldest: report.current.oldest,
        newest: report.current.newest, sport: 'Ride', evidenceReportSha256: digest, modelId: model.id, selectionVersion: 1 }) }))
    expect(screen.getByText(result().observations[0].text)).toBeInTheDocument()
    expect(screen.getByText(/artifact a{64}/)).toBeInTheDocument()
    expect(screen.getByText(/Garmin fitness age: unavailable/)).toBeInTheDocument()
    expect(screen.getByText(/wellness: KEY_REJECTED/)).toBeInTheDocument()
    expect(screen.getByText(/Missing imports do not prove missed activity/)).toBeInTheDocument()
    expect(screen.getAllByText(/populated records across 2\/2 dates/)).toHaveLength(2)
    fireEvent.change(screen.getByLabelText('Local model'), { target: { value: '' } })
    await waitFor(() => expect(screen.queryByText(result().observations[0].text)).not.toBeInTheDocument())
  })

  it.each(['local_not_configured', 'local_configuration_invalid'])('disabled when service reports %s', async reason => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ ...catalogue(), models: [], reason })))
    render(<AnalysisPanel csrfToken={csrf} report={report} digest={digest} />)
    await waitFor(() => expect(screen.queryByText('Loading local model configuration…')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: /Generate observations/ })).toBeDisabled()
    expect(screen.getByRole('option', { name: /Off/ })).toBeInTheDocument()
  })

  it.each([null, 'invalid'])('refuses generation without valid report digest %s', async value => {
    const fetchMock = vi.fn(async () => response(catalogue(model.id, 1)))
    vi.stubGlobal('fetch', fetchMock)
    render(<AnalysisPanel csrfToken={csrf} report={report} digest={value} />)
    await screen.findByText(/Experimental local model selected/)
    expect(screen.getByRole('button', { name: /Generate observations/ })).toBeDisabled()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it.each(['model_unavailable', 'model_timeout', 'insufficient_input', 'unusable_model_output', 'evidence_changed', 'analysis_busy', 'model_selection_changed', 'model_not_selected'])(
    'shows AI unavailable and no observations for %s', async reason => {
      vi.stubGlobal('fetch', vi.fn(async path => response(path === '/api/models' ? catalogue(model.id, 1) :
        { ...result(), status: 'unavailable', reason, observations: [], evidenceReportSha256: 'e'.repeat(64), model: null })))
      render(<AnalysisPanel csrfToken={csrf} report={report} digest={digest} />)
      await screen.findByText(/Experimental local model selected/)
      fireEvent.click(screen.getByRole('button', { name: /Generate observations/ }))
      await waitFor(() => expect(screen.queryByText(/Generating experimental local/)).not.toBeInTheDocument())
      expect(screen.queryByText(result().observations[0].text)).not.toBeInTheDocument()
      expect(screen.queryByText(/AI-selected observation/)).not.toBeInTheDocument()
    })

  it.each(['hash', 'period', 'model', 'evidence', 'number', 'empty', 'bad-status'])('rejects a malformed/mismatched successful response: %s', async field => {
    const malformed = result()
    if (field === 'hash') malformed.evidenceReportSha256 = 'b'.repeat(64)
    if (field === 'period') malformed.observations[0].supportingMetrics[0].oldest = '2020-02-01'
    if (field === 'model') malformed.model = second
    if (field === 'evidence') malformed.observations[0].evidenceIds = ['unknown']
    if (field === 'number') malformed.observations[0].supportingMetrics[0].value = Number.NaN
    if (field === 'empty') malformed.observations = []
    if (field === 'bad-status') malformed.status = 'invented'
    vi.stubGlobal('fetch', vi.fn(async path => response(path === '/api/models' ? catalogue(model.id, 1) : malformed)))
    render(<AnalysisPanel csrfToken={csrf} report={report} digest={digest} />)
    await screen.findByText(/Experimental local model selected/)
    fireEvent.click(screen.getByRole('button', { name: /Generate observations/ }))
    expect(await screen.findByText(/response could not be verified/)).toBeInTheDocument()
    expect(screen.queryByText(result().observations[0].text)).not.toBeInTheDocument()
  })

  it('network and authentication failures never display provider diagnostics', async () => {
    vi.stubGlobal('fetch', vi.fn(async path => path === '/api/models' ? response(catalogue(model.id, 1)) : { ok: false, status: 401 }))
    render(<AnalysisPanel csrfToken={csrf} report={report} digest={digest} />)
    await screen.findByText(/Experimental local model selected/)
    fireEvent.click(screen.getByRole('button', { name: /Generate observations/ }))
    expect(await screen.findByText(/response could not be verified/)).toBeInTheDocument()
    expect(screen.queryByText(/AI-selected observation/)).not.toBeInTheDocument()
  })

  it('invalid hosted catalogue fails closed and never enables inference', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ ...catalogue(), hostedEnabled: true })))
    render(<AnalysisPanel csrfToken={csrf} report={report} digest={digest} />)
    expect(await screen.findByText(/AI analysis unavailable. Reconnect/)).toBeInTheDocument()
    expect(screen.getByLabelText('Local model')).toBeDisabled()
  })

  it('model switching aborts stale client generation and no stale observation survives', async () => {
    let finish!: (value: unknown) => void
    let signal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn(async (path: string, options?: RequestInit) => {
      if (path === '/api/models') return response(options?.method === 'PUT' ? catalogue(second.id, 2) : catalogue(model.id, 1))
      signal = options?.signal as AbortSignal
      return { ok: true, json: async () => new Promise(resolve => { finish = resolve }) }
    }))
    render(<AnalysisPanel csrfToken={csrf} report={report} digest={digest} />)
    await screen.findByText(/Experimental local model selected/)
    fireEvent.click(screen.getByRole('button', { name: /Generate observations/ }))
    await waitFor(() => expect(finish).toBeDefined())
    expect(screen.getByRole('button', { name: /Generate observations/ })).toBeDisabled()
    expect(screen.getByLabelText('Local model')).not.toBeDisabled()
    fireEvent.change(screen.getByLabelText('Local model'), { target: { value: second.id } })
    await waitFor(() => expect(screen.getByLabelText('Local model')).toHaveValue(second.id))
    expect(signal?.aborted).toBe(true)
    finish(result())
    await waitFor(() => expect(screen.queryByText(/AI-selected observation/)).not.toBeInTheDocument())
  })

  it('unmount on logout or refreshed charts aborts inference and cannot restore a discarded snapshot', async () => {
    let signal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn(async (path: string, options?: RequestInit) => {
      if (path === '/api/models') return response(catalogue(model.id, 1))
      signal = options?.signal as AbortSignal
      return new Promise(() => {})
    }))
    const view = render(<AnalysisPanel csrfToken={csrf} report={report} digest={digest} />)
    await screen.findByText(/Experimental local model selected/)
    fireEvent.click(screen.getByRole('button', { name: /Generate observations/ }))
    await waitFor(() => expect(signal).toBeDefined())
    view.unmount()
    expect(signal?.aborted).toBe(true)
  })
})
