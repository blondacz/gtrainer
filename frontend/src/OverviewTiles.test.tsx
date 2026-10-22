import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { OverviewTiles } from './OverviewTiles'
import type { MetricSeries } from './ReviewMetrics'
import type { ScopedReport, ScopedRequest } from './ScopedReviewTypes'
import { factualReviewFor } from './scoped-review.test-fixtures'

const offset = (day: string, days: number) => new Date(Date.parse(day) + days * 86400000).toISOString().slice(0, 10)
function fixture(request: ScopedRequest): ScopedReport {
  const oldest = request.oldest!
  const newest = request.newest!
  const days = (Date.parse(newest) - Date.parse(oldest)) / 86400000 + 1
  function metric(key: string, unit: string, values: number[], first: string, aggregation: 'sum' | 'mean'): MetricSeries {
    const points = values.map((value, index) => ({ source: 'intervals.icu', sourceRecordId: `synthetic-${index}`, category: aggregation === 'sum' ? 'activities' : 'wellness',
      field: key, date: first, value, unit, upstreamSource: null, recordOrigins: [], timeContext: 'source_date', timeZone: null, startInstant: null }))
    return { key, label: key, unit, aggregation, value: values.length ? values.reduce((a, b) => a + b, 0) / (aggregation === 'mean' ? values.length : 1) : null,
      points, sampleCount: values.length, observedDays: values.length ? 1 : 0, periodDays: days, missingRecordValues: 0, rejectedValues: 0 }
  }
  function period(first: string, last: string, prior: boolean) {
    return { oldest: first, newest: last, days, activityRecords: 2, wellnessRecords: 2, flags: [],
      sports: [{ sport: 'Ride', metrics: [metric('movingTime', 'seconds', prior ? [3600] : [3600, 1800], first, 'sum'),
        metric('calories', 'kcal', prior ? [100] : [0, 0], first, 'sum')] }],
      wellness: [metric('weight', 'kg', [70, 72], first, 'mean'), metric('hrv', 'ms', [0], first, 'mean'),
        metric('sleepSecs', 'seconds', [28800, 25200], first, 'mean'), metric('vo2max', 'mL/kg/min', [], first, 'mean')] }
  }
  const selected = period(oldest, newest, false), previous = period(offset(oldest, -days), offset(oldest, -1), true)
  const pairs = [...selected.sports.flatMap(g => g.metrics.map(m => ({ sport: g.sport, metric: m }))), ...selected.wellness.map(metric => ({ sport: null, metric }))]
  const comparisons = pairs.map(({ sport, metric }) => {
    const prior = sport === null ? previous.wellness.find(m => m.key === metric.key)! : previous.sports.find(g => g.sport === sport)!.metrics.find(m => m.key === metric.key)!
    const delta = metric.value === null || prior.value === null ? null : metric.value - prior.value
    return { sport, key: metric.key, label: metric.label, unit: metric.unit, currentValue: metric.value, previousValue: prior.value,
      absoluteChange: delta, percentChange: delta === null || prior.value === 0 ? null : delta / prior.value! * 100, currentSamples: metric.sampleCount, previousSamples: prior.sampleCount, flags: [] }
  })
  const requestWithFocus = { ...request, focus: 'daily_combined' as const }
  const sourceStatus = ['activities', 'wellness'].map(category => ({ category, recordCount: 2, readStatus: 'KEY_REJECTED', rejected: 0, incomplete: 0,
    lastAttemptUtc: '2020-06-02T12:00:00Z', lastSuccessUtc: '2020-06-01T12:00:00Z', latestObservedDate: '2020-06-01', latestObservedAgeDays: 1, upstreamFreshness: 'UNKNOWN' }))
  return { schemaVersion: 1, scope: { request: requestWithFocus, asOf: '2020-06-02T23:30:00Z', timeZone: 'UTC', anchorDate: newest, status: 'available', reasons: [], soFar: false,
    dateBasis: 'Preserved source dates', evidenceRange: { oldest, newest }, fullCalendarRange: { oldest, newest }, wellnessPolicy: 'assessment', activityIds: [], activityDates: [],
    comparison: { policy: 'preceding_equal_length', comparableSelected: { oldest, newest }, comparablePrevious: { oldest: previous.oldest, newest: previous.newest }, priorCalendarFull: { oldest: previous.oldest, newest: previous.newest }, comparisonFull: null, reason: null, unequalDuration: false, overlapping: false, unmatchedSelectedDates: [], unmatchedPreviousDates: [], priorCalendarRemainderDates: [] } },
    selected, comparisonFull: null, priorCalendarFull: previous, comparableSelected: selected, comparablePrevious: previous, comparisons,
    factualReview: factualReviewFor(selected, previous, comparisons, sourceStatus, 'daily_combined'), sessions: [], sessionComparisons: [], wellnessContext: [], sourceStatus, unavailable: [], limitations: [] }
}
const sources = ['activities', 'wellness'].map(category => ({ category, recordCount: 2, readStatus: 'KEY_REJECTED', rejected: 0, incomplete: 0,
  lastAttemptUtc: '2020-06-02T12:00:00Z', lastSuccessUtc: '2020-06-01T12:00:00Z', latestObservedDate: '2020-06-01', latestObservedAgeDays: 1, upstreamFreshness: 'UNKNOWN' }))
const event = { id: '00000000-0000-0000-0000-000000000001', startDate: '2020-06-03', endDate: '2020-06-04', sport: 'Ride', goal: 'Enjoy the ride', notes: null }
const events = { events: [event], nextUpcoming: event, ongoing: [], evaluatedOn: '2020-06-02' }
const queue = { reason: 'review_idle', executionAvailable: false, policy: { debounceSeconds: 1, cooldownSeconds: 1, maximumDeferralSeconds: 2 }, pending: [], running: null,
  outcomes: [], lastSuccess: { id: 'synthetic-outcome', intent: { oldest: '2020-05-25', newest: '2020-06-01' }, status: 'available',
    finishedUtc: '2020-06-02T01:00:00Z', startedUtc: null, stale: true, interpretation: 'PRIVATE MODEL CLAIM: ready to train', facts: {} } }
const response = (body: unknown) => ({ ok: true, json: async () => body })
const options = { schemaVersion: 1, timeZone: 'UTC', configurationVersion: 1, asOf: '2020-06-02T23:30:00Z', today: '2020-06-02', nextOffset: null, browseRange: { oldest: '2019-06-03', newest: '2020-06-02' }, activities: [] }
function body(path: string, init?: RequestInit): unknown { if (path === '/api/reviews/options') return options; if (path === '/api/reviews/prepare') return fixture(JSON.parse(String(init?.body))); return path === '/api/imports' ? sources : path === '/api/events' ? events : queue }
function mockFetch() { const mock = vi.fn(async (path: string, init?: RequestInit) => response(body(path, init))); vi.stubGlobal('fetch', mock); return mock }
const card = (label: string) => screen.getByRole('button', { name: new RegExp(`^${label}:`) })
function next() { fireEvent.click(screen.getByRole('button', { name: 'Next cards' })) }
function resize(height: number) { Object.defineProperty(window, 'innerHeight', { value: height, configurable: true }); fireEvent(window, new Event('resize')) }

describe('private factual overview tiles', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2020-06-02T23:30:00Z'))
    Object.defineProperty(window, 'innerHeight', { value: 900, configurable: true })
  })
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

  it('shows observed totals, zero means, population counts, source failures and coverage without model prose', async () => {
    const fetch = mockFetch()
    // Flush the options -> initial range -> preparation effects before querying
    // the populated cards; no wall-clock delay or relaxed assertion is needed.
    await act(async () => { render(<OverviewTiles revision={0} csrfToken={'s'.repeat(43)} onNavigate={vi.fn()} />) })
    await waitFor(() => expect(card('Ride moving time')).toHaveTextContent('1.5 h'))
    expect(card('Ride moving time')).toHaveTextContent('Observed total • 2 activity records')
    expect(card('Ride moving time')).toHaveTextContent('Prior 1 h • 1 records')
    expect(card('Weight')).toHaveTextContent('71 kg')
    expect(card('HRV')).toHaveTextContent('0 ms')
    expect(card('Activity imports')).toHaveTextContent('Key rejected')
    expect(card('Activity imports')).toHaveTextContent('upstream freshness unknown')
    expect(card('Next manual event')).toHaveTextContent('2020-06-03')
    expect(card('Next manual event')).toHaveTextContent('Your goal: Enjoy the ride')
    expect(card('Review queue')).toHaveTextContent('0 queued')
    expect(card('Review queue')).toHaveTextContent('Last coverage 2020-05-25–2020-06-01 • stale snapshot')
    expect(screen.queryByText(/PRIVATE MODEL CLAIM/)).not.toBeInTheDocument()
    next()
    expect(card('Sleep duration')).toHaveTextContent('7.5 h')
    expect(card('VO2 max')).toHaveTextContent('unavailable mL/kg/min')
    expect(card('Ride calories')).toHaveTextContent('0 kcal')
    expect(card('Garmin training readiness')).toHaveTextContent('Not estimated')
    expect(fetch).toHaveBeenCalledTimes(9)
    expect(fetch).toHaveBeenCalledWith('/api/reviews/prepare', expect.objectContaining({ method: 'POST', cache: 'no-store', signal: expect.any(AbortSignal), headers: expect.objectContaining({ 'X-CSRF-Token': 's'.repeat(43) }), body: expect.stringContaining('2020-05-20') }))
    expect(fetch.mock.calls.filter(([path]) => path === '/api/reviews/prepare')).toHaveLength(1)
    expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0)
  })

  it('routes every category to its detail section with accessible buttons', async () => {
    mockFetch(); const navigate = vi.fn()
    render(<OverviewTiles revision={0} csrfToken={'s'.repeat(43)} onNavigate={navigate} />)
    await waitFor(() => expect(card('Weight')).toHaveTextContent('71 kg'))
    for (const label of ['Activity imports', 'Weight', 'Next manual event', 'Review queue']) fireEvent.click(card(label))
    expect(navigate.mock.calls).toEqual([['imports'], ['charts'], ['events'], ['reviews']])
    expect(card('Weight')).toHaveClass('dashboard-tile--wellness')
    expect(card('HRV')).toHaveClass('dashboard-tile--wellness')
    expect(screen.getByText(/Colors identify categories, not health or readiness/)).toBeInTheDocument()
  })

  it.each(['unconfirmed', 'unavailable'])('never prepares facts when saved-zone options are %s', async state => {
    const fetch = vi.fn(async (path: string, init?: RequestInit) => path !== '/api/reviews/options' ? response(body(path,init)) :
      state === 'unavailable' ? {ok:false,status:503} : response({...options,timeZone:null,today:null,browseRange:null,configurationVersion:0}))
    vi.stubGlobal('fetch',fetch)
    render(<OverviewTiles revision={0} csrfToken="csrf" onNavigate={vi.fn()}/>)
    await waitFor(()=>expect(card('Weight').querySelector('.tile-value')).toHaveTextContent(/^Unavailable kg$/))
    expect(fetch.mock.calls.some(([,init])=>init?.method==='POST')).toBe(false)
    expect(screen.getByLabelText('Last date')).toHaveValue('')
    expect(screen.getByRole('button',{name:'Show range'})).toBeDisabled()
  })

  it('uses the saved-zone server date rather than the browser UTC day', async () => {
    const fetch = vi.fn(async (path:string,init?:RequestInit) => {
      if(path==='/api/reviews/options')return response({...options,timeZone:'Europe/Prague',today:'2020-06-03'})
      const value=body(path,init)
      if(path==='/api/reviews/prepare')(value as ScopedReport).scope.timeZone='Europe/Prague'
      return response(value)
    })
    vi.stubGlobal('fetch',fetch)
    render(<OverviewTiles revision={0} csrfToken="csrf" onNavigate={vi.fn()}/>)
    await waitFor(()=>expect(card('Weight')).toHaveTextContent('71 kg'))
    expect(screen.getByLabelText('Last date')).toHaveValue('2020-06-03')
    const post=fetch.mock.calls.find(([path])=>path==='/api/reviews/prepare')
    expect(JSON.parse(String(post?.[1]?.body)).newest).toBe('2020-06-03')
    expect(JSON.parse(String(post?.[1]?.body)).oldest).toBe('2020-05-21')
  })

  it('keeps factual values visible when other endpoints fail independently', async () => {
    vi.stubGlobal('fetch', vi.fn(async (path: string, init?: RequestInit) => path === '/api/reviews/options' ? response(options) : path === '/api/reviews/prepare' ? response(fixture(JSON.parse(String(init?.body)))) : { ok: false, status: 503 }))
    render(<OverviewTiles revision={0} csrfToken={'s'.repeat(43)} onNavigate={vi.fn()} />)
    await waitFor(() => expect(card('Weight')).toHaveTextContent('71 kg'))
    expect(card('Activity imports')).toHaveTextContent('Unavailable')
    expect(card('Next manual event')).toHaveTextContent('Unavailable')
    expect(card('Next manual event')).not.toHaveTextContent('0 ongoing')
    expect(card('Review queue')).toHaveTextContent('Unavailable')
  })

  it.each([401, 503])('uses safe fixed text for API error %s without diagnostics', async status => {
    vi.stubGlobal('fetch', vi.fn(async (path: string) => path === '/api/reviews/options' ? response(options) : { ok: false, status, json: async () => ({ secret: 'PRIVATE DIAGNOSTIC' }) }))
    render(<OverviewTiles revision={0} csrfToken={'s'.repeat(43)} onNavigate={vi.fn()} />)
    await waitFor(() => expect(card('Weight')).toHaveTextContent('Unavailable'))
    expect(card('Weight')).toHaveTextContent('record population unavailable')
    expect(screen.queryByText(/PRIVATE DIAGNOSTIC/)).not.toBeInTheDocument()
    expect(screen.queryByText(/71 kg/)).not.toBeInTheDocument()
  })

  it('validates whole reports and source/event/review dates before displaying them', async () => {
    vi.stubGlobal('fetch', vi.fn(async (path: string, init?: RequestInit) => {
      if (path === '/api/reviews/options') return response(options); if (path === '/api/reviews/prepare') { const report = fixture(JSON.parse(String(init?.body))); report.selected!.wellness[0].points[0].value = Number.NaN; return response(report) }
      if (path === '/api/imports') return response(sources.map(s => ({ ...s, latestObservedDate: '2020-02-30' })))
      if (path === '/api/events') return response({ ...events, nextUpcoming: { ...event, startDate: '2020-02-30' } })
      return response({ ...queue, lastSuccess: { ...queue.lastSuccess, finishedUtc: '2020-02-30T00:00:00Z' } })
    }))
    render(<OverviewTiles revision={0} csrfToken={'s'.repeat(43)} onNavigate={vi.fn()} />)
    await waitFor(() => expect(card('Weight')).toHaveTextContent('Unavailable'))
    for (const label of ['Activity imports', 'Next manual event', 'Review queue']) expect(card(label)).toHaveTextContent('Unavailable')
  })

  it('uses explicit selected dates and rejects future or oversized ranges without fetching', async () => {
    const fetch = mockFetch()
    render(<OverviewTiles revision={0} csrfToken={'s'.repeat(43)} onNavigate={vi.fn()} />)
    await waitFor(() => expect(card('Weight')).toHaveTextContent('71 kg'))
    fireEvent.change(screen.getByLabelText('First date'), { target: { value: '2020-06-01' } })
    fireEvent.click(screen.getByRole('button', { name: 'Show range' }))
    await waitFor(() => expect(fetch.mock.calls.some(([path, init]) => path === '/api/reviews/prepare' && JSON.parse(String((init as RequestInit).body)).oldest === '2020-06-01' && (init as RequestInit).method === 'POST')).toBe(true))
    fireEvent.change(screen.getByLabelText('First date'), { target: { value: '2018-01-01' } })
    fireEvent.click(screen.getByRole('button', { name: 'Show range' }))
    expect(screen.getByRole('status')).toHaveTextContent('Choose 1–366 ordered dates')
    expect(fetch).toHaveBeenCalledTimes(14)
  })

  it('paginates all cards and adapts to very short through tall viewports without hidden overflow', async () => {
    mockFetch()
    const view = render(<OverviewTiles revision={0} csrfToken={'s'.repeat(43)} onNavigate={vi.fn()} />)
    await waitFor(() => expect(card('Weight')).toHaveTextContent('71 kg'))
    const grid = screen.getByLabelText('Overview cards')
    expect(within(grid).getAllByRole('button')).toHaveLength(6)
    next(); expect(screen.getByText('Card page 2 of 2')).toBeInTheDocument()
    expect(card('Garmin training readiness')).toBeInTheDocument()
    for (const [height, size] of [[300, 1], [480, 2], [575, 2], [700, 4], [1100, 8]]) {
      resize(height)
      expect(within(grid).getAllByRole('button')).toHaveLength(size)
      expect(screen.getByRole('button', { name: 'Previous cards' })).toBeDisabled()
    }
    expect(view.container.querySelector('[style*="overflow"]')).toBeNull()
  })

  it('reduces card count to fit measured layout without clipping or refetching data', async () => {
    const fetch = mockFetch()
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      return { height: this.tagName === 'MAIN' && this.querySelectorAll('.overview-grid>button').length > 2 ? 1000 : 700 } as DOMRect
    })
    render(<main className="dashboard-main"><OverviewTiles revision={0} csrfToken={'s'.repeat(43)} onNavigate={vi.fn()} /></main>)
    const grid = screen.getByLabelText('Overview cards')
    await waitFor(() => expect(within(grid).getAllByRole('button')).toHaveLength(2))
    await waitFor(() => expect(card('Ride moving time')).toHaveTextContent('1.5 h'))
    expect(fetch).toHaveBeenCalledTimes(9)
    expect(screen.getByRole('button', { name: 'Next cards' })).not.toBeDisabled()
    expect(document.querySelector('[style*="overflow: hidden"]')).toBeNull()
  })

  it('does not silently truncate a long user goal and never renders notes', async () => {
    const longEvent = { ...event, goal: 'Long user goal '.repeat(50), notes: 'PRIVATE EVENT NOTES' }
    vi.stubGlobal('fetch', vi.fn(async (path: string, init?: RequestInit) => response(path === '/api/events' ? { ...events, events: [longEvent], nextUpcoming: longEvent } : body(path, init))))
    const navigate = vi.fn()
    render(<OverviewTiles revision={0} csrfToken={'s'.repeat(43)} onNavigate={navigate} />)
    await waitFor(() => expect(card('Next manual event')).toHaveTextContent('View event details for full goal'))
    expect(screen.queryByText(/PRIVATE EVENT NOTES/)).not.toBeInTheDocument()
    fireEvent.click(card('Next manual event')); expect(navigate).toHaveBeenCalledWith('events')
  })

  it('keeps absent activity and wellness unknown instead of manufacturing zero or inactivity', async () => {
    vi.stubGlobal('fetch', vi.fn(async (path: string, init?: RequestInit) => {
      const value = body(path, init)
      if (path === '/api/reviews/prepare') {
        const report = value as ScopedReport
        for (const period of [report.selected!, report.comparableSelected!, report.comparablePrevious!]) {
          period.activityRecords = 0; period.wellnessRecords = 0; period.sports = []; period.wellness = []
        }
        report.comparisons = []
        report.factualReview = factualReviewFor(report.comparableSelected!, report.comparablePrevious!, [], report.sourceStatus)
      }
      return response(value)
    }))
    render(<OverviewTiles revision={0} csrfToken={'s'.repeat(43)} onNavigate={vi.fn()} />)
    await waitFor(() => expect(card('All sports moving time').querySelector('.tile-value')).toHaveTextContent(/^unavailable h$/))
    await waitFor(() => expect(screen.getByRole('button',{name:'Show range'})).toBeEnabled())
    expect(card('Weight')).toHaveTextContent('unavailable kg')
    next()
    expect(card('All sports calories')).toHaveTextContent('unavailable kcal')
    expect(card('Sleep duration')).toHaveTextContent('unavailable h')
    expect(screen.queryByText(/^0 (h|kg|kcal)$/)).not.toBeInTheDocument()
  })

  it('shows queued/running metadata without inference requests or stored interpretation', async () => {
    const job = { id: 'synthetic-job', intent: { oldest: '2020-05-25', newest: '2020-06-01' }, firstQueuedUtc: '2020-06-02T01:00:00Z',
      lastQueuedUtc: '2020-06-02T01:00:00Z', eligibleUtc: '2020-06-02T01:00:00Z', maximumDeferralUtc: '2020-06-02T01:00:00Z', manual: true, startedUtc: '2020-06-02T01:00:00Z' }
    const fetch = vi.fn(async (path: string, init?: RequestInit) => response(path === '/api/review-queue' ? { ...queue, pending: [job], running: job } : body(path, init)))
    vi.stubGlobal('fetch', fetch)
    render(<OverviewTiles revision={0} csrfToken={'s'.repeat(43)} onNavigate={vi.fn()} />)
    await waitFor(() => expect(card('Review queue')).toHaveTextContent('1 queued'))
    expect(card('Review queue')).toHaveTextContent('Running')
    expect(screen.queryByText(/PRIVATE MODEL CLAIM/)).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(9)
    expect(fetch.mock.calls.some(([path]) => path.includes('review-now'))).toBe(false)
  })

  it('discards previous generations even if fetch ignores abort and aborts on unmount', async () => {
    const pending: { path: string; options: RequestInit; resolve: (value: ReturnType<typeof response>) => void }[] = []
    vi.stubGlobal('fetch', vi.fn((path: string, options: RequestInit) => path === '/api/reviews/prepare' ? new Promise(resolve => pending.push({ path, options, resolve })) : Promise.resolve(response(body(path, options)))))
    const view = render(<OverviewTiles revision={0} csrfToken={'s'.repeat(43)} onNavigate={vi.fn()} />)
    await waitFor(() => expect(pending).toHaveLength(1))
    view.rerender(<OverviewTiles revision={1} csrfToken={'s'.repeat(43)} onNavigate={vi.fn()} />)
    expect(pending[0].options.signal?.aborted).toBe(true)
    await waitFor(() => expect(pending).toHaveLength(2))
    await act(async () => { pending[1].resolve(response(body(pending[1].path, pending[1].options))) })
    expect(card('Weight')).toHaveTextContent('71 kg')
    await act(async () => {
      const p = pending[0], value = body(p.path, p.options) as ScopedReport
      for (const period of [value.selected!, value.comparableSelected!]) {
        const metric = period.wellness[0]; metric.value = 999
        metric.points.forEach(point => { point.value = 999 })
      }
      const comparison = value.comparisons.find(c=>c.key==='weight')!
      comparison.currentValue = 999
      comparison.absoluteChange = 999 - comparison.previousValue!
      comparison.percentChange = comparison.absoluteChange / comparison.previousValue! * 100
      value.factualReview = factualReviewFor(value.comparableSelected!,value.comparablePrevious!,value.comparisons,value.sourceStatus)
      p.resolve(response(value))
    })
    expect(card('Weight')).toHaveTextContent('71 kg'); expect(screen.queryByText(/999/)).not.toBeInTheDocument()
    view.unmount()
    expect(pending.every(p => p.options.signal?.aborted)).toBe(true)
  })
})
