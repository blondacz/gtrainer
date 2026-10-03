import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TrendPanel, type MetricSeries, type ObservedValue, type TrendReport } from './TrendPanel'

const date = (input: string, days: number) => new Date(Date.parse(input) + days * 86400000).toISOString().slice(0, 10)
function fixture(path: string, empty = false): TrendReport {
  const query = new URL(path, 'http://synthetic.test').searchParams
  const oldest = query.get('oldest') ?? '2020-05-06'
  const newest = query.get('newest') ?? '2020-06-02'
  const days = (Date.parse(newest) - Date.parse(oldest)) / 86400000 + 1
  const sport = query.get('sport')
  function metric(key: string, label: string, unit: string, values: number[], aggregation: 'sum' | 'mean'): MetricSeries {
    const points: ObservedValue[] = (empty ? [] : values).map((value, index) => ({
      source: 'intervals.icu', sourceRecordId: `synthetic-evidence-${index}`, category: aggregation === 'sum' ? 'activities' : 'wellness',
      field: key, date: index === 0 ? oldest : newest, value, unit, upstreamSource: key === 'weight' ? 'GARMIN' : null,
      recordOrigins: ['GARMIN'], timeContext: aggregation === 'sum' ? 'local_time_only' : 'source_date', timeZone: null, startInstant: null,
    }))
    return { key, label, unit, aggregation, points, value: points.length ? points.reduce((sum, p) => sum + p.value, 0) / (aggregation === 'mean' ? points.length : 1) : null,
      sampleCount: points.length, observedDays: new Set(points.map(p => p.date)).size, periodDays: days, missingRecordValues: 0, rejectedValues: 0 }
  }
  const metrics = [metric('activityCount', 'Imported activities', 'count', [1, 1], 'sum'),
    metric('movingTime', 'Recorded activity time (moving)', 'seconds', [1200, 600], 'sum'),
    metric('calories', 'Recorded activity calories', 'kcal', [100, 50], 'sum')]
  const wellness = [metric('weight', 'Weight', 'kg', [70, 72], 'mean'), metric('hrv', 'HRV', 'ms', [42, 44], 'mean'),
    metric('sleepSecs', 'Sleep duration', 'seconds', [28800, 25200], 'mean'), metric('vo2max', 'VO2 max', 'mL/kg/min', [], 'mean'),
    metric('atl', 'Intervals.icu ATL', 'Intervals.icu load', [12, 14], 'mean')]
  const sports = empty ? [] : [{ sport: sport ?? 'Ride', metrics }]
  return {
    evaluatedOnUtc: '2020-06-02', selectedSport: sport, availableSports: ['Ride', 'Run'],
    current: { oldest, newest, days, activityRecords: empty ? 0 : 2, wellnessRecords: empty ? 0 : 2, sports, wellness, flags: [] },
    previous: { oldest: date(oldest, -days), newest: date(oldest, -1), days, activityRecords: 0, wellnessRecords: 0, sports: [], wellness: [], flags: [] },
    comparisons: [...metrics.map(series => ({ ...series, sport: sport ?? 'Ride' })), ...wellness.map(series => ({ ...series, sport: null }))].map(series => ({
      sport: series.sport, key: series.key, label: series.label, unit: series.unit, currentValue: series.value, previousValue: null,
      absoluteChange: null, percentChange: null, currentSamples: series.sampleCount, previousSamples: 0, flags: ['sparse_comparison'],
    })),
    sourceStatus: [{ category: 'activities', readStatus: 'SUCCESS', latestObservedDate: newest, latestObservedAgeDays: 0 }],
    unavailable: ['fitness age', 'endurance score', 'training status'].map(label => ({ key: label, label: `Garmin ${label}`, reason: 'Not supplied; not estimated.' })),
    dateBasis: 'Activities use their preserved source-local start date; wellness uses its source date.',
  }
}
const response = (body: unknown) => ({ ok: true, json: async () => body })

describe('factual private trends', () => {
  beforeEach(() => { vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2020-06-02T12:00:00Z')) })
  afterEach(() => vi.useRealTimers())

  it('compact tabs page metrics and every evidence record without initiating model work', async () => {
    const fetchMock = vi.fn(async (path: string) => response(fixture(path)))
    vi.stubGlobal('fetch', fetchMock)
    render(<TrendPanel compact csrfToken={'s'.repeat(43)} />)
    const select = await screen.findByLabelText('Metric / sport')
    fireEvent.change(select, { target: { value: '3' } })
    expect(screen.getByText('71 kg')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    const card = screen.getByRole('region', { name: 'Weight' })
    fireEvent.click(within(card).getByRole('button', { name: 'evidence' }))
    expect(within(card).getByText(/synthetic-evidence-0/)).toBeInTheDocument()
    expect(within(card).queryByText(/synthetic-evidence-1/)).not.toBeInTheDocument()
    fireEvent.click(within(card).getByRole('button', { name: 'Next record' }))
    expect(within(card).getByText(/synthetic-evidence-1/)).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: 'Next record' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'period' }))
    expect(screen.getByLabelText('Trend first date')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls.every(([path]) => path.startsWith('/api/trends?'))).toBe(true)
  })

  it('compact coverage retains unavailable Garmin labels and failures while every metric stays reachable', async () => {
    vi.stubGlobal('fetch', vi.fn(async (path: string) => response(fixture(path, true))))
    render(<TrendPanel compact />)
    const select = await screen.findByLabelText('Metric / sport')
    expect(within(select).getAllByRole('option')).toHaveLength(5)
    fireEvent.change(select, { target: { value: '3' } })
    expect(screen.getByRole('region', { name: 'VO2 max' })).toHaveTextContent('unavailable')
    fireEvent.click(screen.getByRole('button', { name: 'coverage' }))
    let coverage = ''
    for (;;) {
      coverage += document.querySelector('.private-text')!.textContent
      const next = screen.queryByRole('button', { name: 'Next coverage' })
      if (!next || next.hasAttribute('disabled')) break
      fireEvent.click(next)
    }
    expect(coverage).toContain('Garmin fitness age: unavailable')
    expect(coverage).toContain('Garmin endurance score: unavailable')
    expect(coverage).toContain('Garmin training status: unavailable')
    expect(coverage).toContain('Garmin-to-Intervals.icu freshness is unverified')
    expect(coverage).toContain('Missing imports do not prove missed activity')
  })

  it('compact metrics remain independently usable through a prototype model API failure', async () => {
    vi.stubGlobal('fetch', vi.fn(async (path: string) => path === '/api/models' ? { ok: false, status: 503 } :
      { ...response(fixture(path)), headers: new Headers({ 'X-Evidence-Report-Sha256': 'a'.repeat(64) }) }))
    render(<TrendPanel compact csrfToken={'s'.repeat(43)} />)
    await screen.findByLabelText('Metric / sport')
    fireEvent.click(screen.getByRole('button', { name: 'Experimental AI prototype' }))
    await screen.findByText(/AI analysis unavailable. Reconnect/)
    expect(screen.getByRole('button', { name: /Generate observations/ })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'metrics' }))
    fireEvent.change(screen.getByLabelText('Metric / sport'), { target: { value: '3' } })
    expect(screen.getByText('71 kg')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /Weight/ })).toBeInTheDocument()
  })

  it('shows observed totals and health units, source evidence, unavailable scores, and no AI transfer', async () => {
    const fetchMock = vi.fn(async (path: string) => response(fixture(path)))
    vi.stubGlobal('fetch', fetchMock)
    render(<TrendPanel />)
    expect(await screen.findByText('Observed total: 0.5 hours')).toBeInTheDocument()
    expect(screen.getByText('Observed total: 150 kcal')).toBeInTheDocument()
    expect(screen.getByText('Mean of measured records: 71 kg')).toBeInTheDocument()
    expect(screen.getByText('Mean of measured records: 43 ms')).toBeInTheDocument()
    expect(screen.getByText('Mean of measured records: 7.5 hours')).toBeInTheDocument()
    expect(screen.getByText(/Garmin fitness age: unavailable/)).toBeInTheDocument()
    expect(screen.getByText(/Garmin endurance score: unavailable/)).toBeInTheDocument()
    expect(screen.getByText(/Garmin training status: unavailable/)).toBeInTheDocument()
    expect(screen.getByText(/Recorded activity time is not Garmin intensity minutes/)).toBeInTheDocument()
    expect(screen.getByText(/AI analysis unavailable: no usable model is selected/)).toBeInTheDocument()
    const card = screen.getByRole('region', { name: 'Weight' })
    fireEvent.click(within(card).getByRole('button', { name: 'Show evidence: Weight' }))
    expect(within(card).getByRole('table')).toHaveTextContent('intervals.icu / synthetic-evidence-0')
    expect(within(card).getByRole('table')).toHaveTextContent('Metric origin: GARMIN')
    const activity = screen.getByRole('region', { name: 'Recorded activity time (moving)' })
    fireEvent.click(within(activity).getByRole('button', { name: /Show evidence/ }))
    expect(within(activity).getByRole('table')).toHaveTextContent(`${new Intl.NumberFormat().format(1200)} seconds`)
    expect(within(activity).getByRole('table')).toHaveTextContent('Metric origin: unknown; record labels: GARMIN')
    expect(screen.getAllByRole('img')).toHaveLength(7)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/trends\?/), expect.objectContaining({ cache: 'no-store', signal: expect.any(AbortSignal) }))
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
  })

  it('selects periods and activity sport without filtering wellness or fetching upstream', async () => {
    const fetchMock = vi.fn(async (path: string) => response(fixture(path)))
    vi.stubGlobal('fetch', fetchMock)
    render(<TrendPanel />)
    await screen.findByText('Observed total: 150 kcal')
    fireEvent.change(screen.getByLabelText('Trend first date'), { target: { value: '2020-06-01' } })
    fireEvent.change(screen.getByLabelText('Activity sport'), { target: { value: 'Run' } })
    fireEvent.click(screen.getByRole('button', { name: 'Show period / refresh' }))
    expect(await screen.findByText(/Selected: 2020-06-01–2020-06-02/)).toBeInTheDocument()
    expect(screen.getByText(/2020-05-30–2020-05-31/)).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Run trends' })).toBeInTheDocument()
    expect(screen.getByText('Mean of measured records: 71 kg')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenLastCalledWith('/api/trends?oldest=2020-06-01&newest=2020-06-02&sport=Run', expect.any(Object))
  })

  it('keeps empty data distinct from zero, inactivity, illness, and guessed scores', async () => {
    vi.stubGlobal('fetch', vi.fn(async (path: string) => response(fixture(path, true))))
    render(<TrendPanel />)
    expect(await screen.findByText(/No imported activities for this sport\/period/)).toBeInTheDocument()
    expect(screen.getByText(/No imported wellness records/)).toBeInTheDocument()
    expect(screen.getAllByText('Mean of measured records: unavailable')).toHaveLength(5)
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.getByText(/Missing imports do not prove missed activity; missing health values do not imply illness/)).toBeInTheDocument()
  })

  it('shows retained history through source failure and labels observed stale gaps without a cause', async () => {
    vi.stubGlobal('fetch', vi.fn(async (path: string) => {
      const body = fixture(path)
      body.sourceStatus[0] = { category: 'activities', readStatus: 'KEY_REJECTED', latestObservedDate: '2020-05-01', latestObservedAgeDays: 32 }
      body.current.flags = ['selected_period_observed_gap_over_7_days']
      return response(body)
    }))
    render(<TrendPanel />)
    expect(await screen.findByText('Observed total: 150 kcal')).toBeInTheDocument()
    expect(screen.getByText(/latest read status KEY_REJECTED/)).toBeInTheDocument()
    expect(screen.getByText(/no upstream cause is verified/)).toBeInTheDocument()
    expect(screen.getByText(/Latest stored record is over seven days old/)).toBeInTheDocument()
  })

  it.each([401, 503])('does not expose private charts on API error %s', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status }))
    render(<TrendPanel />)
    expect(await screen.findByText(/History unavailable/)).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.queryByText(/71 kg/)).not.toBeInTheDocument()
  })

  it('rejects malformed or mismatched period responses rather than displaying invented values', async () => {
    vi.stubGlobal('fetch', vi.fn(async (path: string) => {
      const body = fixture(path)
      body.current.wellness[0].value = Number.NaN
      return response(body)
    }))
    const view = render(<TrendPanel />)
    expect(await screen.findByText(/History unavailable/)).toBeInTheDocument()
    expect(screen.queryByText(/71 kg/)).not.toBeInTheDocument()
    vi.stubGlobal('fetch', vi.fn(async (path: string) => { const body = fixture(path); body.current.oldest = '1999-01-01'; return response(body) }))
    view.rerender(<TrendPanel revision={1} />)
    expect(await screen.findByText(/History unavailable/)).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('rejects oversized ranges locally without any extra network request', async () => {
    const fetchMock = vi.fn(async (path: string) => response(fixture(path)))
    vi.stubGlobal('fetch', fetchMock)
    render(<TrendPanel />)
    await screen.findByText('Observed total: 150 kcal')
    fireEvent.change(screen.getByLabelText('Trend first date'), { target: { value: '2018-01-01' } })
    fireEvent.click(screen.getByRole('button', { name: 'Show period / refresh' }))
    expect(await screen.findByText(/1–366 inclusive dates/)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('clears obsolete private values immediately when imports change and cancels old requests', async () => {
    const fetchMock = vi.fn(async (path: string) => response(fixture(path)))
    vi.stubGlobal('fetch', fetchMock)
    const view = render(<TrendPanel />)
    await screen.findByText('Mean of measured records: 71 kg')
    let finish!: (value: ReturnType<typeof response>) => void
    fetchMock.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    view.rerender(<TrendPanel revision={1} />)
    expect(screen.queryByText(/71 kg/)).not.toBeInTheDocument()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    view.unmount()
    const options = fetchMock.mock.calls[1] as unknown as [string, { signal: AbortSignal }]
    expect(options[1].signal.aborted).toBe(true)
    finish(response(fixture(options[0])))
  })

  it('shows real zeros on the chart baseline without filling unobserved dates', async () => {
    vi.stubGlobal('fetch', vi.fn(async (path: string) => {
      const body = fixture(path)
      const calories = body.current.sports[0].metrics[2]
      calories.value = 0
      calories.points = calories.points.map(point => ({ ...point, value: 0 }))
      return response(body)
    }))
    render(<TrendPanel />)
    expect(await screen.findByText('Observed total: 0 kcal')).toBeInTheDocument()
    const chart = within(screen.getByRole('region', { name: 'Recorded activity calories' })).getByRole('img')
    const bars = chart.querySelectorAll('rect')
    expect(bars).toHaveLength(2)
    expect([...bars].every(bar => bar.getAttribute('y') === '150' && bar.getAttribute('height') === '1')).toBe(true)
    expect(chart.querySelector('polyline')).toBeNull()
  })

  it('paginates all retained source evidence rather than silently truncating it', async () => {
    vi.stubGlobal('fetch', vi.fn(async (path: string) => {
      const body = fixture(path)
      const weight = body.current.wellness[0]
      weight.points = Array.from({ length: 28 }, (_, i) => ({ ...weight.points[0], sourceRecordId: `synthetic-page-${i}`,
        date: date(body.current.oldest, i), value: 70 + i }))
      weight.sampleCount = weight.observedDays = 28
      weight.value = 83.5
      return response(body)
    }))
    render(<TrendPanel />)
    await screen.findByText('Mean of measured records: 83.5 kg')
    const card = screen.getByRole('region', { name: 'Weight' })
    fireEvent.click(within(card).getByRole('button', { name: 'Show evidence: Weight' }))
    expect(within(card).getByRole('table').querySelectorAll('tbody tr')).toHaveLength(25)
    expect(within(card).queryByText(/synthetic-page-27/)).not.toBeInTheDocument()
    fireEvent.click(within(card).getByRole('button', { name: 'Next evidence page' }))
    expect(within(card).getByRole('table').querySelectorAll('tbody tr')).toHaveLength(3)
    expect(within(card).getByText(/synthetic-page-27/)).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: 'Next evidence page' })).toBeDisabled()
    fireEvent.click(within(card).getByRole('button', { name: 'Previous evidence page' }))
    expect(within(card).getByRole('table').querySelectorAll('tbody tr')).toHaveLength(25)
  })

  it('labels arithmetic changes and previous evidence counts without a confident causal claim', async () => {
    vi.stubGlobal('fetch', vi.fn(async (path: string) => {
      const body = fixture(path)
      const comparison = body.comparisons.find(item => item.key === 'weight')!
      Object.assign(comparison, { previousValue: 68, absoluteChange: 3, percentChange: 3 / 68 * 100, previousSamples: 2, flags: [] })
      return response(body)
    }))
    render(<TrendPanel />)
    await screen.findByText('Mean of measured records: 71 kg')
    expect(within(screen.getByRole('region', { name: 'Weight' })).getByText(/Observed change: \+3 kg/)).toHaveTextContent('2 current / 2 previous records')
    expect(screen.getByText(/not significance, diagnoses, causes, or prescriptions/)).toBeInTheDocument()
  })

  it('keeps charts and evidence usable when private model configuration is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn(async (path: string) => path === '/api/models' ? { ok: false, status: 503 } :
      { ...response(fixture(path)), headers: new Headers({ 'X-Evidence-Report-Sha256': 'a'.repeat(64) }) }))
    render(<TrendPanel csrfToken={'s'.repeat(43)} />)
    expect(await screen.findByText(/AI analysis unavailable. Reconnect/)).toBeInTheDocument()
    expect(screen.getByText('Mean of measured records: 71 kg')).toBeInTheDocument()
    const card = screen.getByRole('region', { name: 'Weight' })
    fireEvent.click(within(card).getByRole('button', { name: 'Show evidence: Weight' }))
    expect(within(card).getByRole('table')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Generate observations/ })).toBeDisabled()
  })
})
