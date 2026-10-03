import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { FactualReviewPanel } from './FactualReviewPanel'
import { TrendPanel, type MetricSeries, type TrendReport } from './TrendPanel'

const digest = 'a'.repeat(64)
const date = (input: string, days: number) => new Date(Date.parse(input) + days * 86400000).toISOString().slice(0, 10)
function reportFixture(oldest = '2020-06-01', newest = '2020-06-02'): TrendReport {
  const days = (Date.parse(newest) - Date.parse(oldest)) / 86400000 + 1
  function period(first: string, last: string, current: boolean): TrendReport['current'] {
    const metric = (key: string, label: string, unit: string, value: number | null, sum = false): MetricSeries => ({
      key, label, unit, value, aggregation: sum ? 'sum' : 'mean', sampleCount: value === null ? 0 : 2, observedDays: value === null ? 0 : 2,
      periodDays: days, missingRecordValues: value === null ? 2 : 0, rejectedValues: 0,
      points: value === null ? [] : [first, last].map((day, index) => ({ source: 'synthetic-fixture', sourceRecordId: `synthetic-${key}-${index}`,
        category: sum ? 'activities' : 'wellness', field: key, date: day, value: sum ? value / 2 : value, unit, upstreamSource: null,
        recordOrigins: [], timeContext: 'source_date', timeZone: null, startInstant: null })),
    })
    return { oldest: first, newest: last, days, activityRecords: 4, wellnessRecords: 2, flags: [],
      sports: ['Ride', 'Run', 'Swim'].map(sport => ({ sport, metrics: [metric('movingTime', 'Recorded activity time (moving)', 'seconds', current ? 3600 : 0, true)] })),
      wellness: [metric('hrv', 'HRV', 'ms', 42), metric('sleepSecs', 'Sleep duration', 'seconds', current ? 25200 : 28800),
        metric('weight', 'Weight', 'kg', null)] }
  }
  const current = period(oldest, newest, true)
  const previous = period(date(oldest, -days), date(oldest, -1), false)
  const comparisons = [...current.sports.flatMap(g => g.metrics.map(m => ({ sport: g.sport, series: m }))), ...current.wellness.map(m => ({ sport: null, series: m }))]
    .map(({ sport, series }) => {
      const before = sport === null ? previous.wellness.find(m => m.key === series.key)! : previous.sports.find(g => g.sport === sport)!.metrics[0]
      const absoluteChange = series.value === null || before.value === null ? null : series.value - before.value
      return { sport, key: series.key, label: series.label, unit: series.unit, currentValue: series.value, previousValue: before.value,
        absoluteChange, percentChange: absoluteChange === null || before.value === 0 ? null : absoluteChange / before.value! * 100,
        currentSamples: series.sampleCount, previousSamples: before.sampleCount, flags: absoluteChange === null ? ['unavailable_comparison'] : [] }
    })
  return { evaluatedOnUtc: '2020-06-02', selectedSport: null, availableSports: ['Ride', 'Run', 'Swim'], current, previous, comparisons,
    sourceStatus: [], unavailable: [{ key: 'fitnessAge', label: 'Garmin fitness age', reason: 'Not supplied; not estimated.' }], dateBasis: 'Synthetic source dates.' }
}
function reviewFixture(report: TrendReport, focus = 'daily_combined', binding = digest) {
  const groups = ['available', 'sparse', 'unavailable'].map((coverage, index) => ({ id: `g${index}`, coverage,
    periods: { previousOldest: report.previous.oldest, previousNewest: report.previous.newest, currentOldest: report.current.oldest, currentNewest: report.current.newest },
    facts: report.comparisons.flatMap((comparison, index) => {
      const state = comparison.absoluteChange === null ? 'unavailable' : comparison.absoluteChange > 0 ? 'increased' : comparison.absoluteChange < 0 ? 'decreased' : 'unchanged'
      const expectedCoverage = state === 'unavailable' ? 'unavailable' : comparison.currentSamples < 2 || comparison.previousSamples < 2 ||
        comparison.flags.includes('sparse_comparison') ? 'sparse' : 'available'
      if (coverage !== expectedCoverage) return []
      return [{ id: `r${index}`, state, text: `Code fact: ${comparison.sport ?? 'Wellness'} ${comparison.label}; ${state}.`, comparison,
        supportingMetrics: ['previous', 'current'].map(name => {
          const period = report[name as 'previous' | 'current']
          const series = comparison.sport === null ? period.wellness.find(m => m.key === comparison.key)! : period.sports.find(g => g.sport === comparison.sport)!.metrics[0]
          return { evidenceId: `${name}/${comparison.sport ?? 'wellness'}/${comparison.key}`, period: name, oldest: period.oldest, newest: period.newest,
            sport: comparison.sport, metric: comparison.key, label: series.label, unit: series.unit, aggregation: series.aggregation, value: series.value,
            sampleCount: series.sampleCount, observedDays: series.observedDays, periodDays: period.days,
            sources: series.value === null ? [] : ['synthetic-fixture'], metricOrigins: [] as string[], recordOrigins: [] as string[], unknownMetricOriginCount: series.points.length,
            firstObservedDate: series.points[0]?.date ?? null, lastObservedDate: series.points[series.points.length - 1]?.date ?? null,
            flags: [...period.flags, ...(series.value === null ? ['metric_unavailable', 'partial_metric_coverage'] : ['metric_origin_unknown']),
              ...(series.sampleCount < 2 ? ['sparse_metric'] : [])] }
        }) }]
    }) })).filter(g => g.facts.length)
  const usableWellness = groups.some(g => g.coverage === 'available' && g.facts.some(f => f.comparison.sport === null))
  const focuses = groups.flatMap(g => {
    const activity = g.facts.filter(f => f.comparison.sport !== null)
    const wellness = g.facts.filter(f => f.comparison.sport === null)
    const chosen = focus === 'daily_combined' && g.coverage === 'available' && activity.length && wellness.length ? { kind: 'cross_metric_pattern', facts: [...activity, ...wellness] } :
      focus === 'activity_balance' && g.coverage === 'available' && activity.length >= 2 ? { kind: 'sport_mix', facts: activity } :
      focus === 'wellness' && g.coverage === 'available' ? { kind: 'wellness_pattern', facts: wellness } :
      (focus === 'missing_wellness' || (focus === 'wellness' && !usableWellness)) && g.coverage !== 'available' ? { kind: 'coverage_gap', facts: wellness } : null
    return chosen && chosen.facts.length ? [{ groupId: g.id, kind: chosen.kind, factIds: chosen.facts.map(f => f.id), text: `Code focus: ${chosen.kind}.` }] : []
  })
  return { profile: 'factual-review-v1', applicationGenerated: true, requestedFocus: focus, evidenceReportSha256: binding, groups, focuses,
    reason: focuses.length ? 'supported_focus' : 'no_supported_focus', limitations: ['Missing measurements are unknown, not zero or illness.'] }
}
const response = (body: unknown) => ({ ok: true, json: async () => body, headers: new Headers({ 'X-Evidence-Report-Sha256': digest }) })

describe('code-owned complete factual review', () => {
  afterEach(() => vi.useRealTimers())

  it('compact view retains every comparison including zero and unavailable and separates focus from values', async () => {
    const report = reportFixture()
    vi.stubGlobal('fetch', vi.fn(async () => response(reviewFixture(report))))
    render(<FactualReviewPanel compact report={report} digest={digest} />)
    const select = await screen.findByLabelText(/Complete factual comparisons/)
    expect(within(select).getAllByRole('option')).toHaveLength(6)
    fireEvent.click(screen.getByRole('button', { name: 'Previous period' }))
    expect(screen.getByText(/previous: 0 seconds/)).toBeInTheDocument()
    fireEvent.change(select, { target: { value: '5' } })
    expect(screen.getByText(/Weight; unavailable/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'coverage' }))
    expect(screen.getByText(/0 populated records/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'focus' }))
    expect(screen.getByText(/Code focus: cross_metric_pattern/)).toBeInTheDocument()
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
  })

  it('shows all sports, missing and unchanged values, sources, real zeros and complete support without inference', async () => {
    const report = reportFixture()
    const fetchMock = vi.fn(async () => response(reviewFixture(report)))
    vi.stubGlobal('fetch', fetchMock)
    render(<FactualReviewPanel report={report} digest={digest} />)
    expect(await screen.findByText('Code focus: cross_metric_pattern.')).toBeInTheDocument()
    const review = screen.getByRole('region', { name: 'Factual review — no AI' })
    expect(within(review).getAllByText(/Code fact:/)).toHaveLength(6)
    expect(within(review).getByText(/Swim.*increased/)).toBeInTheDocument()
    expect(within(review).getByText(/HRV; unchanged/)).toBeInTheDocument()
    expect(within(review).getByText(/Weight; unavailable/)).toBeInTheDocument()
    expect(within(review).getAllByText(/previous: Recorded activity time \(moving\) 0 seconds;/)).toHaveLength(3)
    expect(within(review).getAllByText(/Sources: synthetic-fixture/)).toHaveLength(10)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining('/api/review-facts?'), expect.objectContaining({ cache: 'no-store', signal: expect.any(AbortSignal) }))
    const path = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(new URL(path[0], 'http://synthetic.test').searchParams.get('evidenceReportSha256')).toBe(digest)
    expect(path[1].method).toBeUndefined()
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
  })

  it('changing focus clears obsolete focus without hiding complete factual coverage', async () => {
    const report = reportFixture()
    vi.stubGlobal('fetch', vi.fn(async (path: string) => response(reviewFixture(report, new URL(path, 'http://synthetic.test').searchParams.get('focus')!))))
    render(<FactualReviewPanel report={report} digest={digest} />)
    await screen.findByText('Code focus: cross_metric_pattern.')
    fireEvent.change(screen.getByLabelText('Factual review focus'), { target: { value: 'missing_wellness' } })
    expect(screen.queryByText('Code focus: cross_metric_pattern.')).not.toBeInTheDocument()
    expect(await screen.findByText('Code focus: coverage_gap.')).toBeInTheDocument()
    expect(screen.getAllByText(/Code fact:/)).toHaveLength(6)
    fireEvent.change(screen.getByLabelText('Factual review focus'), { target: { value: 'activity_balance' } })
    expect(await screen.findByText('Code focus: sport_mix.')).toBeInTheDocument()
    expect(screen.getAllByText(/Code fact:/)).toHaveLength(6)
  })

  it.each(['binding', 'missing', 'duplicate', 'support', 'period', 'focus', 'source', 'provenance', 'flags', 'recordOrigins', 'unknownOrigins', 'firstDate', 'lastDate'])('rejects %s mismatch whole without repairing facts', async defect => {
    const report = reportFixture()
    const body = reviewFixture(report)
    if (defect === 'binding') body.evidenceReportSha256 = 'b'.repeat(64)
    if (defect === 'missing') body.groups[0].facts.pop()
    if (defect === 'duplicate') body.groups[0].facts.push(body.groups[0].facts[0])
    if (defect === 'support') body.groups[0].facts[0].supportingMetrics[0].value = 999
    if (defect === 'period') body.groups[0].periods.previousOldest = '1999-01-01'
    if (defect === 'focus') body.focuses[0].kind = 'wellness_pattern'
    if (defect === 'source') body.applicationGenerated = false
    if (defect === 'provenance') body.groups[0].facts[0].supportingMetrics[0].sources = ['invented-source']
    if (defect === 'flags') body.groups[0].facts[0].supportingMetrics[0].flags = []
    if (defect === 'recordOrigins') body.groups[0].facts[0].supportingMetrics[0].recordOrigins = ['invented-origin']
    if (defect === 'unknownOrigins') body.groups[0].facts[0].supportingMetrics[0].unknownMetricOriginCount = 0
    if (defect === 'firstDate') body.groups[0].facts[0].supportingMetrics[0].firstObservedDate = '1999-01-01'
    if (defect === 'lastDate') body.groups[0].facts[0].supportingMetrics[0].lastObservedDate = '1999-01-01'
    vi.stubGlobal('fetch', vi.fn(async () => response(body)))
    render(<FactualReviewPanel report={report} digest={digest} />)
    expect(await screen.findByText(/Factual review unavailable or snapshot changed/)).toBeInTheDocument()
    expect(screen.queryByText(/Code fact:/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Code focus:/)).not.toBeInTheDocument()
  })

  it('no snapshot or API error cannot trigger inference or expose unverified facts', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 409 })
    vi.stubGlobal('fetch', fetchMock)
    const view = render(<FactualReviewPanel report={reportFixture()} digest={null} />)
    expect(await screen.findByText(/requires a verified snapshot/)).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
    view.rerender(<FactualReviewPanel report={reportFixture()} digest={digest} />)
    expect(await screen.findByText(/Factual review unavailable or snapshot changed/)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(screen.queryByText(/Code fact:/)).not.toBeInTheDocument()
  })

  it('no supported sport pattern does not substitute wellness focus or suppress other facts', async () => {
    const report = reportFixture()
    report.current.sports = report.current.sports.slice(0, 1)
    report.previous.sports = report.previous.sports.slice(0, 1)
    report.comparisons = report.comparisons.filter(c => c.sport === null || c.sport === 'Ride')
    vi.stubGlobal('fetch', vi.fn(async (path: string) => response(reviewFixture(report, new URL(path, 'http://synthetic.test').searchParams.get('focus')!))))
    render(<FactualReviewPanel report={report} digest={digest} />)
    await screen.findByText('Code focus: cross_metric_pattern.')
    fireEvent.change(screen.getByLabelText('Factual review focus'), { target: { value: 'activity_balance' } })
    expect(await screen.findByText(/No supported pattern for this focus/)).toBeInTheDocument()
    expect(screen.getAllByText(/Code fact:/)).toHaveLength(4)
    expect(screen.queryByText(/Code focus:/)).not.toBeInTheDocument()
  })

  it('sparse arithmetic remains visible but is not promoted into a combined pattern', async () => {
    const report = reportFixture()
    for (const period of [report.previous, report.current]) {
      for (const metric of [...period.sports.flatMap(g => g.metrics), ...period.wellness]) {
        if (metric.value !== null) { metric.sampleCount = metric.observedDays = 1; metric.points = metric.points.slice(-1) }
      }
    }
    report.comparisons = report.comparisons.map(c => c.absoluteChange === null ? c : { ...c, currentSamples: 1, previousSamples: 1, flags: ['sparse_comparison'] })
    vi.stubGlobal('fetch', vi.fn(async () => response(reviewFixture(report))))
    render(<FactualReviewPanel report={report} digest={digest} />)
    expect(await screen.findByText(/No supported pattern for this focus/)).toBeInTheDocument()
    expect(screen.getAllByText(/Code fact:/)).toHaveLength(6)
    expect(screen.getByText(/sparse comparisons \(5\)/)).toBeInTheDocument()
  })

  it('snapshot changes and unmount abort old responses before publication', async () => {
    const report = reportFixture()
    let finish!: (value: ReturnType<typeof response>) => void
    const fetchMock = vi.fn().mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
      .mockResolvedValue(response(reviewFixture(report, 'daily_combined', 'b'.repeat(64))))
    vi.stubGlobal('fetch', fetchMock)
    const view = render(<FactualReviewPanel report={report} digest={digest} />)
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    view.rerender(<FactualReviewPanel report={report} digest={'b'.repeat(64)} />)
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true)
    await screen.findByText('Code focus: cross_metric_pattern.')
    await act(async () => finish(response(reviewFixture(report, 'daily_combined', digest))))
    expect(screen.getAllByText(/Code fact:/)).toHaveLength(6)
    view.unmount()
    expect(fetchMock.mock.calls[1][1].signal.aborted).toBe(true)
  })

  it('charts and deterministic facts stay available through model outage and disappear on import refresh', async () => {
    vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2020-06-02T12:00:00Z'))
    let report: TrendReport
    const fetchMock = vi.fn(async (path: string) => {
      if (path === '/api/models') return { ok: false, status: 503 }
      const params = new URL(path, 'http://synthetic.test').searchParams
      if (path.startsWith('/api/trends?')) { report = reportFixture(params.get('oldest')!, params.get('newest')!); return response(report) }
      if (path.startsWith('/api/review-facts?')) return response(reviewFixture(report!, params.get('focus')!))
      throw new Error('Unexpected model or upstream call')
    })
    vi.stubGlobal('fetch', fetchMock)
    const view = render(<TrendPanel csrfToken={'s'.repeat(43)} />)
    expect(await screen.findByText('Code focus: cross_metric_pattern.')).toBeInTheDocument()
    expect(await screen.findByText(/AI analysis unavailable. Reconnect/)).toBeInTheDocument()
    expect(screen.getAllByText('Observed total: 1 hours')).toHaveLength(3)
    expect(screen.getByRole('button', { name: /Generate observations/ })).toBeDisabled()
    expect(fetchMock.mock.calls.every(([path]) => path.startsWith('/api/trends?') || path.startsWith('/api/review-facts?') || path === '/api/models')).toBe(true)
    fetchMock.mockImplementation(() => new Promise(() => {}))
    view.rerender(<TrendPanel revision={1} csrfToken={'s'.repeat(43)} />)
    expect(screen.queryByText(/Code fact:/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Code focus:/)).not.toBeInTheDocument()
    expect(screen.queryByText('Observed total: 1 hours')).not.toBeInTheDocument()
  })
})
