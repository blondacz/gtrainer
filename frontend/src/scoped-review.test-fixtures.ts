import type { ScopedOptions, ScopedRequest, PeriodDto, FactualReview, CategoryStatusDto } from './ScopedReviewTypes'

export const id = 'a'.repeat(64)
export const asOf = '2026-03-30T12:00:00Z'
export const fixtureStatus: CategoryStatusDto = { category: 'activities', recordCount: 1, lastAttemptUtc: asOf, lastSuccessUtc: asOf, readStatus: 'SUCCESS', rejected: 0, incomplete: 0, latestObservedDate: '2026-03-01', latestObservedAgeDays: 29, upstreamFreshness: 'unknown' }
export const options: ScopedOptions = {
  schemaVersion: 1, timeZone: 'Europe/Prague', configurationVersion: 1, asOf, today: '2026-03-30', nextOffset: null,
  browseRange: { oldest: '2026-03-02', newest: '2026-03-31' },
  activities: [{ activityId: id, sport: 'Run', startLocal: '2026-03-29T09:00', startInstant: '2026-03-29T08:00:00Z', timeContext: 'source-local' }],
}
export const activityRequest: ScopedRequest = { schemaVersion: 1, mode: 'activities', intent: 'review', scope: 'activities', activityIds: [id], wellnessPolicy: 'off' }
export function activityReport(request: ScopedRequest = activityRequest, status: 'available'|'needs_confirmation' = 'available') {
  const activityIds = request.activityIds ?? []
  const selected = status === 'available' ? activityIds : []
  const sessions = selected.map(activityId => ({ activityId, record: { source: 'garmin', sourceRecordId: 'record-1', sport: 'Run', startLocal: '2026-03-29T09:00', startInstant: '2026-03-29T08:00:00Z', timeContext: 'source-local', timeZone: 'Europe/Prague', upstreamSources: ['garmin'] }, metrics: [{ key: 'movingTime', label: 'Moving time', value: 1200, unit: 'seconds', basis: 'moving', upstreamSource: null }] }))
  return {
    schemaVersion: 1, scope: { request, asOf, timeZone: 'Europe/Prague', anchorDate: '2026-03-29', status, reasons: status === 'available' ? [] : ['activity_time_needs_confirmation'], soFar: false, dateBasis: 'source dates', evidenceRange: null, fullCalendarRange: null, wellnessPolicy: request.wellnessPolicy ?? 'off', activityIds, activityDates: activityIds.map(() => '2026-03-29'), comparison: { policy: request.intent === 'compare' ? 'selected_sessions' : 'none', comparableSelected: null, comparablePrevious: null, priorCalendarFull: null, comparisonFull: null, reason: null, unequalDuration: false, overlapping: false, unmatchedSelectedDates: [], unmatchedPreviousDates: [], priorCalendarRemainderDates: [] } },
     selected: null, comparisonFull: null, priorCalendarFull: null, comparableSelected: null, comparablePrevious: null, comparisons: [], factualReview: null, sessions, sessionComparisons: [], wellnessContext: [], sourceStatus: [fixtureStatus], unavailable: [], limitations: [],
  }
}

export function period(oldest: string, newest: string, minutes = 10): PeriodDto {
  const days = (Date.parse(newest) - Date.parse(oldest)) / 86400000 + 1
  return { oldest, newest, days, activityRecords: 1, wellnessRecords: 0, flags: ['upstream_freshness_unknown'],
    sports: [{ sport: 'Run', metrics: [{ key: 'movingTime', label: 'Moving duration', unit: 'seconds', aggregation: 'sum', value: minutes * 60,
      sampleCount: 1, observedDays: 1, periodDays: days, missingRecordValues: 0, rejectedValues: 0,
      points: [{ source: 'intervals.icu', sourceRecordId: oldest, category: 'activities', field: 'movingTime', date: oldest, value: minutes * 60,
        unit: 'seconds', upstreamSource: null, recordOrigins: ['GARMIN'], timeContext: 'source_local', timeZone: null, startInstant: null }] }] }], wellness: [] }
}

export function periodReport(request: ScopedRequest) {
  const selected = period('2026-03-01', '2026-03-30', 20)
  const current = period('2026-03-01', '2026-03-28')
  const previous = period('2026-02-01', '2026-02-28')
  const comparisons = [{ sport: 'Run', key: 'movingTime', label: 'Moving duration', unit: 'seconds', currentValue: 600, previousValue: 600, absoluteChange: 0, percentChange: 0, currentSamples: 1, previousSamples: 1, flags: ['sparse_comparison'] }]
  return { schemaVersion: 1, scope: { request, asOf, timeZone: 'Europe/Prague', anchorDate: '2026-03-30', status: 'available', reasons: [], soFar: true,
    dateBasis: 'Activities source-local; wellness source date.', evidenceRange: { oldest: selected.oldest, newest: selected.newest }, fullCalendarRange: { oldest: '2026-03-01', newest: '2026-03-31' },
    wellnessPolicy: request.wellnessPolicy ?? 'assessment', activityIds: [], activityDates: [], comparison: { policy: 'preceding_calendar', comparableSelected: { oldest: current.oldest, newest: current.newest },
      comparablePrevious: { oldest: previous.oldest, newest: previous.newest }, priorCalendarFull: { oldest: previous.oldest, newest: previous.newest }, comparisonFull: null,
      unmatchedSelectedDates: ['2026-03-29', '2026-03-30'], unmatchedPreviousDates: [], priorCalendarRemainderDates: [], unequalDuration: true, overlapping: false, reason: null } },
     selected, comparableSelected: current, comparablePrevious: previous, priorCalendarFull: previous, comparisonFull: null,
     comparisons, sessions: [], sessionComparisons: [], wellnessContext: [], sourceStatus: [fixtureStatus], unavailable: [{ key: 'trainingReadiness', label: 'Garmin training readiness', reason: 'Not supplied; not reconstructed.' }], limitations: ['No inference or source sync.'], factualReview: factualReviewFor(current, previous, comparisons, [fixtureStatus], request.focus) }
}

/** Build a factual DTO from the exact paired periods/comparisons used by the envelope. */
export function factualReviewFor(selected: PeriodDto, previous: PeriodDto, comparisons: any[], sourceStatus: CategoryStatusDto[], focus = 'daily_combined'): FactualReview {
  const facts = comparisons.map((comparison, index) => {
    const summarize = (period: PeriodDto, which: string) => {
      const metric = comparison.sport === null ? period.wellness.find(m => m.key === comparison.key) : period.sports.find(s => s.sport === comparison.sport)?.metrics.find(m => m.key === comparison.key)
      const points = metric?.points ?? [], dates = points.map(p => p.date).sort()
      return { evidenceId: `${which}/${comparison.sport??'wellness'}/${comparison.key}`, period: which, oldest: period.oldest, newest: period.newest, sport: comparison.sport, metric: comparison.key, label: comparison.label, unit: comparison.unit, aggregation: metric?.aggregation ?? 'sum', value: metric?.value ?? null, sampleCount: metric?.sampleCount ?? 0, observedDays: metric?.observedDays ?? 0, periodDays: period.days, firstObservedDate: dates[0] ?? null, lastObservedDate: dates.at(-1) ?? null, sources: [...new Set(points.map(p => p.source))].sort(), metricOrigins: [...new Set(points.flatMap(p=>p.upstreamSource===null?[]:[p.upstreamSource]))].sort(), recordOrigins: [...new Set(points.flatMap(p => p.recordOrigins))].sort(), unknownMetricOriginCount: points.filter(p => p.upstreamSource === null).length, flags: [] }
    }
    const state = comparison.previousValue === null || comparison.currentValue === null || comparison.absoluteChange === null ? 'unavailable' : comparison.absoluteChange > 0 ? 'increased' : comparison.absoluteChange < 0 ? 'decreased' : 'unchanged'
    return { id: `r${index}`, state, text: 'Observed comparison.', comparison, supportingMetrics: [summarize(previous, 'previous'), summarize(selected, 'current')] }
  })
  const groups = ['available','sparse','unavailable'].flatMap((coverage,i) => {
    const covered = facts.filter(f => (f.state === 'unavailable' ? 'unavailable' : f.supportingMetrics.some(m=>m.sampleCount<2) || f.comparison.flags.includes('sparse_comparison') ? 'sparse' : 'available') === coverage)
    return covered.length ? [{id:`g${i}`,periods:{previousOldest:previous.oldest,previousNewest:previous.newest,currentOldest:selected.oldest,currentNewest:selected.newest},coverage,facts:covered}] : []
  })
  return { evidenceReportSha256: 'f'.repeat(64), requestedFocus: focus, groups, focuses: [], reason: 'no_supported_focus', unavailable: [], limitations: ['Fixture facts.'], sourceStatus, profile: 'factual-review-v1', applicationGenerated: true }
}
