import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { ScopedReviewControls } from './ScopedReviewControls'
import { activityReport, activityRequest, id, options, period, periodReport, factualReviewFor } from './scoped-review.test-fixtures'
import { TrendPanel } from './TrendPanel'
import type { ScopedRequest } from './ScopedReviewTypes'

const response = (body: unknown) => ({ ok: true, json: async () => structuredClone(body) })
afterEach(() => vi.unstubAllGlobals())

it('reads options without POST, then submits a verified activity selection with CSRF', async () => {
  const fetch = vi.fn(async (path: string, _init?: RequestInit) => {
    if (!path.includes('/options')) return response(activityReport())
    const params = new URL(path, 'http://synthetic.test').searchParams
    return response({ ...options, browseRange: params.has('oldest') ? { oldest: params.get('oldest'), newest: params.get('newest') } : options.browseRange })
  })
  vi.stubGlobal('fetch', fetch)
  render(<ScopedReviewControls csrfToken={'c'.repeat(40)} />)
  await screen.findByText(/server date: 2026-03-30/)
  expect(fetch).toHaveBeenCalledTimes(1)
  fireEvent.change(screen.getByLabelText('Review mode'), { target: { value: 'activities' } })
  fireEvent.change(screen.getByLabelText('Activity browse first date'), { target: { value: '2026-03-29' } })
  fireEvent.change(screen.getByLabelText('Activity browse last date'), { target: { value: '2026-03-29' } })
  fireEvent.click(screen.getByRole('button', { name: 'Browse activities' }))
  await screen.findByText(/Activity options loaded/)
  fireEvent.click(screen.getByLabelText(/2026-03-29T09:00 · Run/))
  fireEvent.click(screen.getByRole('button', { name: 'Request factual report' }))
  await screen.findByText(/Factual report verified/)
   const post = fetch.mock.calls.find(([path]) => path === '/api/reviews/prepare')
  expect(post?.[1]).toMatchObject({ method: 'POST', headers: { 'X-CSRF-Token': 'c'.repeat(40) }, body: JSON.stringify(activityRequest) })
  expect(id).toHaveLength(64)
})

it('names rolling scopes, supports historical anchors and resets wellness defaults by mode without submitting', async () => {
  const fetch = vi.fn(async () => response(options)); vi.stubGlobal('fetch', fetch)
  render(<ScopedReviewControls csrfToken="csrf" />); await screen.findByText(/server date/)
  expect(screen.getByLabelText('Wellness policy')).toHaveValue('assessment')
  for (const days of [7, 30, 365]) expect(screen.getByRole('option', { name: `Last ${days} days` })).toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('Server-zone anchor date'), { target: { value: '2024-12-31' } })
  expect(screen.getByLabelText('Server-zone anchor date')).toHaveValue('2024-12-31')
  fireEvent.change(screen.getByLabelText('Review mode'), { target: { value: 'activities' } })
  expect(screen.getByLabelText('Wellness policy')).toHaveValue('off')
  fireEvent.change(screen.getByLabelText('Review mode'), { target: { value: 'period' } })
  expect(screen.getByLabelText('Wellness policy')).toHaveValue('assessment')
  expect(fetch).toHaveBeenCalledTimes(1)
})

it.each(['display_only', 'assessment'] as const)('renders actual map-shaped %s wellness without changing session comparison subjects', async (usage) => {
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (path.includes('/options')) return response(options)
    const request = JSON.parse(String(init?.body)) as ScopedRequest
    const report = activityReport(request)
    return response({ ...report, wellnessContext: [{ record: { source: 'intervals.icu', sourceRecordId: 'day', upstreamSources: ['GARMIN'], date: '2026-03-29', measurements: { hrv: { value: 55, unit: 'ms', upstreamSource: null }, sleepSecs: { value: 28_800, unit: 'seconds', upstreamSource: null } } }, activityIds: [id], usage, flags: ['prior_night_mapping_unverified'] }] })
  })
  vi.stubGlobal('fetch', fetch); render(<ScopedReviewControls csrfToken="csrf" />); await screen.findByText(/server date/)
  fireEvent.change(screen.getByLabelText('Review mode'), { target: { value: 'activities' } })
  fireEvent.click(screen.getByLabelText(/2026-03-29T09:00 · Run/))
  fireEvent.change(screen.getByLabelText('Wellness policy'), { target: { value: usage } })
  fireEvent.click(screen.getByRole('button', { name: 'Request factual report' }))
  await screen.findByText(/hrv 55 ms/)
  expect(screen.getByText(/sleepSecs 28800 seconds/)).toBeInTheDocument()
  expect(screen.getByText(usage === 'display_only' ? /excluded from provider packets and comparisons/ : /no inference or transfer is authorized/)).toBeInTheDocument()
  const submitted = JSON.parse(String(fetch.mock.calls.at(-1)?.[1]?.body))
  expect(submitted.wellnessPolicy).toBe(usage)
  expect(submitted.intent).toBe('review'); expect(submitted.activityIds).toEqual([id])
   expect(fetch.mock.calls.map(call => call[0]).every(path => path.startsWith('/api/reviews/'))).toBe(true)
})

it('shows selected totals separately from calendar-compatible prefixes, unequal dates and cutoff', async () => {
  const fetch = vi.fn(async (path: string, init?: RequestInit) => path.includes('/options') ? response(options) : response(periodReport(JSON.parse(String(init?.body)))))
  vi.stubGlobal('fetch', fetch); render(<ScopedReviewControls csrfToken="csrf" />); await screen.findByText(/server date/)
  fireEvent.change(screen.getByLabelText('Period scope'), { target: { value: 'calendar_month' } })
  fireEvent.click(screen.getByRole('button', { name: 'Request factual report' }))
  await screen.findByText(/so far at the captured cutoff/)
  expect(screen.getByRole('heading', { name: /Selected period totals: 2026-03-01–2026-03-30/ })).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: /Prior calendar full totals: 2026-02-01–2026-02-28/ })).toBeInTheDocument()
  expect(screen.getByText(/Moving duration: 1200 seconds/)).toBeInTheDocument()
   fireEvent.click(screen.getByRole('button', {name:'Comparison'}))
   expect(screen.getByText(/Comparable current: 0.17 hours; previous: 0.17 hours/)).toBeInTheDocument()
  expect(screen.getByText(/durations unequal.*2026-03-29, 2026-03-30/)).toBeInTheDocument()
  expect(screen.getByText(/Garmin training readiness: unavailable/)).toBeInTheDocument()
})

it('requires two explicitly selected activities for comparison and retains selected earlier pages', async () => {
  const second = 'b'.repeat(64)
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (path.includes('offset=200')) return response({ ...options, activities: [{ ...options.activities[0], activityId: second, sport: 'Ride' }], nextOffset: null })
    if (path.includes('/options')) return response({ ...options, nextOffset: 200 })
    return response(activityReport(JSON.parse(String(init?.body)), 'needs_confirmation'))
  })
  vi.stubGlobal('fetch', fetch); render(<ScopedReviewControls csrfToken="csrf" />); await screen.findByText(/server date/)
  fireEvent.change(screen.getByLabelText('Review mode'), { target: { value: 'activities' } })
  fireEvent.change(screen.getByLabelText('Intent'), { target: { value: 'compare' } })
  fireEvent.click(screen.getByLabelText(/2026-03-29T09:00 · Run/))
  fireEvent.click(screen.getByRole('button', { name: 'Request factual report' }))
  await screen.findByText(/comparison needs at least two/)
   expect(fetch.mock.calls.some(([path]) => path === '/api/reviews/prepare')).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: 'Next activity page' }))
  await screen.findByLabelText(/2026-03-29T09:00 · Ride/)
  const query = new URL(String(fetch.mock.calls.find(([path]) => path.includes('offset=200'))?.[0]), 'http://synthetic.test').searchParams
  expect(query.get('oldest')).toBe('2026-03-02'); expect(query.get('newest')).toBe('2026-03-31')
  expect(screen.getByLabelText(/2026-03-29T09:00 · Run/)).toBeChecked()
  fireEvent.click(screen.getByLabelText(/2026-03-29T09:00 · Ride/))
  fireEvent.click(screen.getByRole('button', { name: 'Request factual report' }))
   await waitFor(() => expect(fetch.mock.calls.some(([path]) => path === '/api/reviews/prepare')).toBe(true))
   const body = JSON.parse(String(fetch.mock.calls.find(([path]) => path === '/api/reviews/prepare')?.[1]?.body))
  expect(body.activityIds).toEqual([id, second]); expect(body.wellnessPolicy).toBe('off')
})

it('hosts one factual form alongside read-only charts, never auto-prepares, and requests daily focus', async () => {
  const fetch = vi.fn(async (path: string, init?: RequestInit) => path === '/api/reviews/options'
    ? response(options) : response(periodReport(JSON.parse(String(init?.body)))))
  vi.stubGlobal('fetch', fetch)
  render(<TrendPanel csrfToken="csrf" />)
  await screen.findByText(/server date/)
  expect(screen.getAllByRole('button', { name: 'Request factual report' })).toHaveLength(1)
  expect(fetch.mock.calls.map(([path]) => path)).toEqual(['/api/reviews/options'])
  fireEvent.click(screen.getByRole('button', { name: 'Request factual report' }))
  await screen.findByText(/Factual report verified/)
  const post = fetch.mock.calls.find(([path]) => path === '/api/reviews/prepare')
  expect(JSON.parse(String(post?.[1]?.body))).toMatchObject({ focus: 'daily_combined' })
  expect(screen.getByRole('region', { name: 'Application-generated facts' })).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: /Selected period totals/ })).toBeInTheDocument()
  expect(fetch.mock.calls.every(([path]) => path.startsWith('/api/reviews/'))).toBe(true)
})

it('disables stale pagination offsets when the browse filter changes', async () => {
  const fetch = vi.fn(async () => response({ ...options, nextOffset: 200 })); vi.stubGlobal('fetch', fetch)
  render(<ScopedReviewControls csrfToken="csrf" />); await screen.findByText(/server date/)
  fireEvent.change(screen.getByLabelText('Review mode'), { target: { value: 'activities' } })
  expect(screen.getByLabelText('Activity browse first date')).toHaveValue('2026-03-02')
  expect(screen.getByRole('button', { name: 'Next activity page' })).toBeEnabled()
  fireEvent.change(screen.getByLabelText('Activity browse first date'), { target: { value: '2026-03-29' } })
  expect(screen.getByRole('button', { name: 'Next activity page' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: 'Next activity page' }))
  expect(fetch).toHaveBeenCalledTimes(1)
})

it('accepts unknown night mapping only as confirmation with no observed period', async () => {
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (path.includes('/options')) return response(options)
    const request = JSON.parse(String(init?.body)) as ScopedRequest, report = periodReport(request)
    return response({ ...report, factualReview: null, selected: null, comparableSelected: null, comparablePrevious: null, priorCalendarFull: null, comparisons: [], scope: { ...report.scope, status: 'needs_confirmation', reasons: ['night_mapping_unverified'], evidenceRange: null, fullCalendarRange: null, soFar: false, comparison: { ...report.scope.comparison, policy: 'unavailable', comparableSelected: null, comparablePrevious: null, priorCalendarFull: null, reason: 'selected_range_unavailable', unmatchedSelectedDates: [], unequalDuration: false } } })
  })
  vi.stubGlobal('fetch', fetch); render(<ScopedReviewControls csrfToken="csrf" />); await screen.findByText(/server date/)
  fireEvent.change(screen.getByLabelText('Period scope'), { target: { value: 'night' } })
  fireEvent.click(screen.getByRole('button', { name: 'Request factual report' }))
  await screen.findByText(/Needs confirmation: night_mapping_unverified/)
  expect(screen.queryByRole('heading', { name: /Selected period totals/ })).not.toBeInTheDocument()
})

it('submits explicit historical comparison dates and shows their overlap and actual totals', async () => {
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (path.includes('/options')) return response(options)
    const request = JSON.parse(String(init?.body)) as ScopedRequest
    const report = periodReport(request)
    const previous = period('2026-03-14', '2026-03-30')
    const comparisons = [{ ...report.comparisons[0], currentValue: 1200, absoluteChange: 600, percentChange: 100 }]
    return response({ ...report, priorCalendarFull: null, comparisonFull: previous, comparableSelected: report.selected, comparablePrevious: previous,
      comparisons, factualReview: factualReviewFor(report.selected, previous, comparisons, report.sourceStatus, request.focus),
      scope: { ...report.scope, comparison: { ...report.scope.comparison, policy: 'explicit', comparableSelected: { oldest: '2026-03-01', newest: '2026-03-30' },
        comparablePrevious: { oldest: '2026-03-14', newest: '2026-03-30' }, comparisonFull: { oldest: '2026-03-14', newest: '2026-03-30' },
        unmatchedSelectedDates: [], priorCalendarFull: null, overlapping: true } } })
  })
  vi.stubGlobal('fetch', fetch); render(<ScopedReviewControls csrfToken="csrf" />); await screen.findByText(/server date/)
  fireEvent.change(screen.getByLabelText('Period scope'), { target: { value: 'calendar_month' } })
  fireEvent.click(screen.getByLabelText('Use explicit comparison dates'))
  fireEvent.change(screen.getByLabelText('Comparison first date'), { target: { value: '2026-03-14' } })
  fireEvent.change(screen.getByLabelText('Comparison last date'), { target: { value: '2026-03-30' } })
  fireEvent.click(screen.getByRole('button', { name: 'Request factual report' }))
  await screen.findByText(/windows overlap/)
  expect(screen.getByRole('heading', { name: /Full comparison totals: 2026-03-14–2026-03-30/ })).toBeInTheDocument()
  expect(JSON.parse(String(fetch.mock.calls.at(-1)?.[1]?.body)).comparison).toEqual({ oldest: '2026-03-14', newest: '2026-03-30' })
})

it('fences a delayed response after an edit even if fetch ignores abort', async () => {
  let resolve: (value: ReturnType<typeof response>) => void = () => {}
  const fetch = vi.fn((path: string, _init?: RequestInit) => path.includes('/options') ? Promise.resolve(response(options)) : new Promise<ReturnType<typeof response>>(r => { resolve = r }))
  vi.stubGlobal('fetch', fetch); render(<ScopedReviewControls csrfToken="csrf" />); await screen.findByText(/server date/)
  fireEvent.change(screen.getByLabelText('Review mode'), { target: { value: 'activities' } })
  fireEvent.click(screen.getByLabelText(/2026-03-29T09:00 · Run/)); fireEvent.click(screen.getByRole('button', { name: 'Request factual report' }))
  const signal = fetch.mock.calls.at(-1)?.[1]?.signal
  fireEvent.change(screen.getByLabelText('Wellness policy'), { target: { value: 'display_only' } })
  expect(signal?.aborted).toBe(true)
  await act(async () => resolve(response(activityReport())))
  expect(screen.queryByText('Verified factual report')).not.toBeInTheDocument()
})

it('clears private choices on a session change and aborts a pending report on unmount', async () => {
  const fetch = vi.fn((path: string, _init?: RequestInit) => path.includes('/options') ? Promise.resolve(response(options)) : new Promise<ReturnType<typeof response>>(() => {}))
  vi.stubGlobal('fetch', fetch); const view = render(<ScopedReviewControls csrfToken="old" />); await screen.findByText(/server date/)
  fireEvent.change(screen.getByLabelText('Review mode'), { target: { value: 'activities' } }); fireEvent.click(screen.getByLabelText(/2026-03-29T09:00 · Run/))
  view.rerender(<ScopedReviewControls csrfToken="new" />); await screen.findByText(/server date/)
  expect(screen.getByLabelText('Review mode')).toHaveValue('period')
  fireEvent.click(screen.getByRole('button', { name: 'Request factual report' })); const signal = fetch.mock.calls.at(-1)?.[1]?.signal
  view.unmount(); expect(signal?.aborted).toBe(true)
})
