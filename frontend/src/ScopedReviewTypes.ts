import { metricUnits, sessionUnits, type Period, type Comparison, type MetricSeries } from './ReviewMetrics'
export type PeriodDto = Period
export type MetricSeriesDto = MetricSeries
export type ScopedActivity = { activityId: string; sport: string; startLocal: string; startInstant: string | null; timeContext: string }
export type ScopedOptions = { schemaVersion: 1; timeZone: string | null; configurationVersion: number; asOf: string; today: string | null; activities: ScopedActivity[]; nextOffset: number | null; browseRange: DateRange | null }
export type WellnessPolicy = 'off' | 'display_only' | 'assessment'
export type DateRange = { oldest: string; newest: string }
export type ScopedRequest = { schemaVersion: 1; mode: 'period' | 'activities'; intent: 'review' | 'compare'; scope: string; anchorDate?: string; days?: number; oldest?: string; newest?: string; activityIds?: string[]; comparison?: DateRange; wellnessPolicy?: WellnessPolicy; sport?: string | null; focus?: 'daily_combined'|'activity_balance'|'wellness'|'missing_wellness' }
export type MeasurementDto = { value: number; unit: string; upstreamSource: string | null }
export type ScopedMetric = { key: string; label: string; value: number | null; unit: string; basis: string; upstreamSource: string | null }
export type ScopedSession = { activityId: string; record: { source: string; sourceRecordId: string; sport: string; startLocal: string; startInstant: string | null; timeContext: string; timeZone: string | null; upstreamSources: string[] }; metrics: ScopedMetric[] }
export type ScopeDto = { request: ScopedRequest; asOf: string; timeZone: string; anchorDate: string; status: 'available' | 'needs_confirmation' | 'unavailable'; reasons: string[]; soFar: boolean; dateBasis: string; evidenceRange: DateRange | null; fullCalendarRange: DateRange | null; wellnessPolicy: WellnessPolicy; activityIds: string[]; activityDates: string[]; comparison: {
  policy: string; comparableSelected: DateRange | null; comparablePrevious: DateRange | null; priorCalendarFull: DateRange | null; comparisonFull: DateRange | null; reason: string | null
  unequalDuration: boolean; overlapping: boolean; unmatchedSelectedDates: string[]; unmatchedPreviousDates: string[]; priorCalendarRemainderDates: string[]
} }
export type ScopedComparisonDto = { key: string; label: string; unit: string; basis: string; sport: string | null; values: { activityId: string; value: number | null }[]; flags: string[] }
export type WellnessContextDto = { record: { source: string; sourceRecordId: string; upstreamSources: string[]; date: string; measurements: Record<string, MeasurementDto> }; activityIds: string[]; usage: WellnessPolicy; flags: string[] }
export type FactualSummary = { evidenceId: string; period: string; oldest: string; newest: string; sport: string | null; metric: string; label: string; unit: string; aggregation: string; value: number | null; sampleCount: number; observedDays: number; periodDays: number; firstObservedDate: string | null; lastObservedDate: string | null; sources: string[]; metricOrigins: string[]; recordOrigins: string[]; unknownMetricOriginCount: number; flags: string[] }
export type FactualReview = { evidenceReportSha256: string; requestedFocus: string; groups: { id: string; periods: { previousOldest: string; previousNewest: string; currentOldest: string; currentNewest: string }; coverage: string; facts: { id: string; state: string; text: string; comparison: Comparison; supportingMetrics: FactualSummary[] }[] }[]; focuses: { kind: string; groupId: string; factIds: string[]; text: string }[]; reason: string; unavailable: { key: string; label: string; reason: string }[]; limitations: string[]; sourceStatus: CategoryStatusDto[]; profile: 'factual-review-v1'; applicationGenerated: true }
export type CategoryStatusDto = { category: string; recordCount: number; lastAttemptUtc: string | null; lastSuccessUtc: string | null; readStatus: string; rejected: number; incomplete: number; latestObservedDate: string | null; latestObservedAgeDays: number | null; upstreamFreshness: string }
export type ScopedReport = { schemaVersion: 1; scope: ScopeDto; selected: Period | null; comparisonFull: Period | null; priorCalendarFull: Period | null; comparableSelected: Period | null; comparablePrevious: Period | null; comparisons: Comparison[]; factualReview: FactualReview | null; sessions: ScopedSession[]; sessionComparisons: ScopedComparisonDto[]; wellnessContext: WellnessContextDto[]; sourceStatus: CategoryStatusDto[]; unavailable: { key: string; label: string; reason: string }[]; limitations: string[] }

const obj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const text = (v: unknown): v is string => typeof v === 'string' && v.length <= 4096
const strings = (v: unknown, maximum = 50_000): v is string[] => Array.isArray(v) && v.length <= maximum && v.every(text)
const integer = (v: unknown, maximum = 50_000) => Number.isSafeInteger(v) && Number(v) >= 0 && Number(v) <= maximum
const finite = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v)
const nullable = (v: unknown) => v === null || finite(v)
const arithmetic = (a: unknown, b: number | null) => a === b || finite(a) && b !== null && Math.abs(a - b) <= Math.max(1, Math.abs(b)) * 1e-12
const id = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
const policy = (v: unknown): v is WellnessPolicy => ['off', 'display_only', 'assessment'].includes(String(v))
export const validDate = (v: unknown): v is string => typeof v === 'string' && /^[0-9]{4}-\d\d-\d\d$/.test(v) && v.slice(0, 4) !== '0000' && Number.isFinite(Date.parse(v)) && new Date(v).toISOString().slice(0, 10) === v
export const utc = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d\d-\d\dT(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?Z$/.test(v) && validDate(v.slice(0, 10)) && Number.isFinite(Date.parse(v))
const local = (v: unknown) => text(v) && validDate(v.slice(0, 10)) && /^\d{4}-\d\d-\d\dT(?:[01]\d|2[0-3]):[0-5]\d/.test(v)
const zone = (v: unknown): v is string => { if (!text(v) || !v || v.length > 80) return false; try { new Intl.DateTimeFormat('en', { timeZone: v }); return true } catch { return false } }
export const dateCount = (a: string, b: string) => (Date.parse(b) - Date.parse(a)) / 86400000 + 1
const range = (v: unknown): v is Record<string, unknown> & DateRange => obj(v) && validDate(v.oldest) && validDate(v.newest) && dateCount(v.oldest, v.newest) >= 1 && dateCount(v.oldest, v.newest) <= 366
const optionalRange = (v: unknown) => v === null || range(v)
const sameRange = (a: DateRange | null, b: { oldest: string; newest: string } | null) => a === null ? b === null : b !== null && a.oldest === b.oldest && a.newest === b.newest

export function parseOptions(v: unknown): ScopedOptions {
  if (!obj(v) || v.schemaVersion !== 1 || !integer(v.configurationVersion, Number.MAX_SAFE_INTEGER) || !utc(v.asOf) ||
      !(v.timeZone === null ? v.today === null && v.browseRange === null : zone(v.timeZone) && validDate(v.today) && range(v.browseRange) && Number(v.configurationVersion) > 0) ||
      !Array.isArray(v.activities) || v.activities.length > 200 || !v.activities.every(a => obj(a) && id(a.activityId) && text(a.sport) && a.sport.length <= 64 && local(a.startLocal) && (a.startInstant === null || utc(a.startInstant)) && text(a.timeContext)) ||
      new Set(v.activities.map(a => a.activityId)).size !== v.activities.length || !(v.nextOffset === null || integer(v.nextOffset, Number.MAX_SAFE_INTEGER))) throw new Error('Invalid options')
  if (v.timeZone === null && (v.activities.length || v.nextOffset !== null)) throw new Error('Invalid unconfigured options')
  return v as unknown as ScopedOptions
}

function metricValid(v: unknown): boolean {
  if (!obj(v) || !text(v.key) || metricUnits[v.key] !== v.unit || !text(v.label) || !['sum', 'mean'].includes(String(v.aggregation)) ||
      !nullable(v.value) || finite(v.value) && v.value < 0 || !['sampleCount', 'observedDays', 'missingRecordValues', 'rejectedValues'].every(k => integer(v[k])) ||
      !integer(v.periodDays, 366) || Number(v.periodDays) < 1 || !Array.isArray(v.points) || v.points.length !== v.sampleCount) return false
  return v.points.every(p => obj(p) && validDate(p.date) && finite(p.value) && p.value >= 0 && p.unit === v.unit && text(p.source) && text(p.sourceRecordId) && text(p.category) && p.field === v.key && strings(p.recordOrigins) && text(p.timeContext) && (p.upstreamSource === null || text(p.upstreamSource)) && (p.timeZone === null || text(p.timeZone)) && (p.startInstant === null || utc(p.startInstant))) &&
    new Set(v.points.map(p => p.date)).size === v.observedDays && Number(v.observedDays) <= Number(v.periodDays) &&
    (v.points.length > 0 || v.value === null || v.key === 'activityCount' && v.value === 0)
}
function periodValid(v: unknown): v is Period {
  if (!obj(v) || !range(v) || v.days !== dateCount(v.oldest, v.newest) || !integer(v.activityRecords) || !integer(v.wellnessRecords) || !strings(v.flags) || !Array.isArray(v.sports) || !Array.isArray(v.wellness) ||
      !v.sports.every(g => obj(g) && text(g.sport) && Array.isArray(g.metrics) && g.metrics.every(metricValid)) || !v.wellness.every(metricValid)) return false
  const metrics = [...v.sports.flatMap(g => g.metrics), ...v.wellness] as MetricSeries[]
  return metrics.every(m => m.periodDays === v.days && m.points.every(p => p.date >= v.oldest && p.date <= v.newest))
}
const sessionBasis: Record<string, string> = { movingTime: 'moving', elapsedTime: 'elapsed', distance: 'distance', calories: 'source_calories', averageHeartRate: 'session_average', intervalsTrainingLoad: 'intervals_activity_load' }
function sessionValid(v: unknown): v is ScopedSession {
  return obj(v) && id(v.activityId) && obj(v.record) && text(v.record.source) && text(v.record.sourceRecordId) && text(v.record.sport) && local(v.record.startLocal) && text(v.record.timeContext) && strings(v.record.upstreamSources) &&
    (v.record.startInstant === null || utc(v.record.startInstant)) && (v.record.timeZone === null || text(v.record.timeZone)) && Array.isArray(v.metrics) &&
    v.metrics.every(m => obj(m) && text(m.key) && sessionUnits[m.key] === m.unit && m.basis === sessionBasis[m.key] && text(m.label) && nullable(m.value) && (m.value === null || Number(m.value) >= 0) && (m.upstreamSource === null || text(m.upstreamSource))) && new Set(v.metrics.map(m => m.key)).size === v.metrics.length
}

function canonicalRequest(request: Record<string, unknown>): string {
  const defaults: Record<string, unknown> = { schemaVersion: 1, mode: 'period', intent: 'review', scope: 'rolling', anchorDate: null, days: null, oldest: null, newest: null, activityIds: [], comparison: null, wellnessPolicy: null, sport: null, focus: 'daily_combined' }
  const fields = Object.keys(defaults).map(k => {
    let value = request[k] === undefined ? defaults[k] : request[k]
    if (k === 'comparison' && obj(value)) value = { oldest: value.oldest, newest: value.newest }
    return [k, value]
  })
  return JSON.stringify(Object.fromEntries(fields))
}

export function parseReport(v: unknown, submitted: ScopedRequest, expectedZone?: string): ScopedReport {
  if (!obj(v) || v.schemaVersion !== 1 || !obj(v.scope) || !obj(v.scope.request) || !utc(v.scope.asOf) || !zone(v.scope.timeZone) || expectedZone !== undefined && v.scope.timeZone !== expectedZone ||
      !validDate(v.scope.anchorDate) || !['available', 'needs_confirmation', 'unavailable'].includes(String(v.scope.status)) || typeof v.scope.soFar !== 'boolean' || !text(v.scope.dateBasis) || !strings(v.scope.reasons) ||
      !optionalRange(v.scope.evidenceRange) || !optionalRange(v.scope.fullCalendarRange) || !policy(v.scope.wellnessPolicy) || !strings(v.scope.activityIds, 1000) || !v.scope.activityIds.every(id) || !strings(v.scope.activityDates, 1000) || !v.scope.activityDates.every(validDate) ||
      !obj(v.scope.comparison) || !Array.isArray(v.sessions) || v.sessions.length > 1000 || !v.sessions.every(sessionValid) || !Array.isArray(v.sessionComparisons) || !Array.isArray(v.comparisons) || !Array.isArray(v.wellnessContext) || v.wellnessContext.length > 50_000 ||
       !Array.isArray(v.sourceStatus) || !v.sourceStatus.every(statusValid) || !Array.isArray(v.unavailable) || !v.unavailable.every(m => obj(m) && text(m.key) && text(m.label) && text(m.reason)) || !strings(v.limitations)) throw new Error('Invalid factual report')
  if (canonicalRequest(v.scope.request) !== canonicalRequest(submitted as unknown as Record<string, unknown>) || v.scope.wellnessPolicy !== (submitted.wellnessPolicy ?? (submitted.mode === 'activities' ? 'off' : 'assessment'))) throw new Error('Request mismatch')
  const windows = v.scope.comparison
  if (!['none', 'selected_sessions', 'preceding_calendar', 'preceding_equal_length', 'explicit', 'unavailable'].includes(String(windows.policy)) || typeof windows.unequalDuration !== 'boolean' || typeof windows.overlapping !== 'boolean' ||
      !(windows.reason === null || text(windows.reason)) || !['comparableSelected', 'comparablePrevious', 'priorCalendarFull', 'comparisonFull'].every(k => optionalRange(windows[k])) ||
      !['unmatchedSelectedDates', 'unmatchedPreviousDates', 'priorCalendarRemainderDates'].every(k => strings(windows[k], 366) && windows[k].every(validDate))) throw new Error('Invalid comparison windows')
  for (const field of ['selected', 'comparisonFull', 'priorCalendarFull', 'comparableSelected', 'comparablePrevious']) if (!(v[field] === null || periodValid(v[field]))) throw new Error('Invalid period')
  const report = v as unknown as ScopedReport
  const scope = report.scope
  if (submitted.mode === 'activities') {
    if (scope.activityIds.length < (submitted.intent === 'compare' ? 2 : 1) || new Set(scope.activityIds).size !== scope.activityIds.length || JSON.stringify(scope.activityIds) !== JSON.stringify(submitted.activityIds ?? []) || scope.activityDates.length !== scope.activityIds.length || report.selected !== null || report.comparisons.length || scope.comparison.policy !== (submitted.intent === 'compare' ? 'selected_sessions' : 'none')) throw new Error('Activity selection mismatch')
    const expected = scope.status === 'available' ? scope.activityIds : []
    if (JSON.stringify(report.sessions.map(s => s.activityId)) !== JSON.stringify(expected)) throw new Error('Session identity mismatch')
    if (scope.status === 'available' && report.sessions.some((session, index) => session.record.startLocal.slice(0, 10) !== scope.activityDates[index])) throw new Error('Session date mismatch')
  } else if (scope.activityIds.length || scope.activityDates.length || report.sessions.length || report.sessionComparisons.length || report.wellnessContext.length) throw new Error('Period subject mismatch')
  const expectedRanges: [DateRange | null, Period | null][] = [[scope.evidenceRange, report.selected], [scope.comparison.comparisonFull, report.comparisonFull], [scope.comparison.priorCalendarFull, report.priorCalendarFull], [scope.comparison.comparableSelected, report.comparableSelected], [scope.comparison.comparablePrevious, report.comparablePrevious]]
  if (submitted.mode === 'period' && !expectedRanges.every(([r, p]) => sameRange(scope.status === 'available' ? r : null, p))) throw new Error('Resolved period mismatch')
  if (submitted.mode === 'activities' && expectedRanges.some(([, p]) => p !== null)) throw new Error('Fabricated activity period')
  for (const c of report.comparisons) {
    if (!obj(c) || !text(c.key) || metricUnits[c.key] !== c.unit || !text(c.label) || !(c.sport === null || text(c.sport)) || ![c.currentValue, c.previousValue, c.absoluteChange, c.percentChange].every(nullable) || !integer(c.currentSamples) || !integer(c.previousSamples) || !strings(c.flags)) throw new Error('Invalid metric comparison')
    const metric = (p: Period | null) => c.sport === null ? p?.wellness.find(m => m.key === c.key) : p?.sports.find(s => s.sport === c.sport)?.metrics.find(m => m.key === c.key)
    const a = metric(report.comparableSelected), b = metric(report.comparablePrevious)
    if (!a || !b || a.value !== c.currentValue || b.value !== c.previousValue || a.sampleCount !== c.currentSamples || b.sampleCount !== c.previousSamples || c.sport === null && scope.wellnessPolicy !== 'assessment') throw new Error('Wrong comparison subjects')
    const delta = a.value === null || b.value === null ? null : a.value - b.value
    const percent = delta === null || b.value === 0 ? null : delta / b.value! * 100
    if (!arithmetic(c.absoluteChange, delta !== null && Number.isFinite(delta) ? delta : null) || !arithmetic(c.percentChange, percent !== null && Number.isFinite(percent) ? percent : null)) throw new Error('Invalid comparison arithmetic')
  }
  const hasPair = report.comparableSelected !== null && report.comparablePrevious !== null
  if (submitted.mode === 'activities' || scope.status !== 'available' || !hasPair) {
    if (v.factualReview !== null) throw new Error('Unexpected factual review')
  } else {
    const f = v.factualReview
    if (!factualReviewValid(f, report, submitted)) throw new Error('Invalid factual review')
  }
  if (hasPair) {
    const expected = new Set(report.comparisons.map(c => `${c.sport ?? ''}\u0000${c.key}`))
    if (expected.size !== report.comparisons.length) throw new Error('Duplicate comparisons')
    for (const period of [report.comparableSelected!, report.comparablePrevious!]) {
      const keys = period.sports.flatMap(s => s.metrics.map(m => `${s.sport}\u0000${m.key}`)).concat(scope.wellnessPolicy === 'assessment' ? period.wellness.map(m => `\u0000${m.key}`) : [])
      if (keys.length !== expected.size || keys.some(k => !expected.has(k)) || new Set(keys).size !== keys.length) throw new Error('Comparison key mismatch')
    }
  }
  for (const c of report.sessionComparisons) {
    if (!obj(c) || !text(c.key) || !['movingTime', 'elapsedTime', 'distance'].includes(c.key) || sessionUnits[c.key] !== c.unit || c.basis !== sessionBasis[c.key] || !text(c.label) || !(c.sport === null || text(c.sport)) || !strings(c.flags) || !Array.isArray(c.values) || c.values.length < 2 || c.values.length > 1000 || new Set(c.values.map(x => x?.activityId)).size !== c.values.length || submitted.intent !== 'compare') throw new Error('Invalid session comparison')
    for (const value of c.values) {
      const session = report.sessions.find(s => s.activityId === value?.activityId), metric = session?.metrics.find(m => m.key === c.key)
      if (!obj(value) || !id(value.activityId) || !nullable(value.value) || !session || !metric || metric.value !== value.value || c.sport !== null && session.record.sport !== c.sport || c.key === 'distance' && c.sport === null) throw new Error('Incompatible session subjects')
    }
  }
  for (const w of report.wellnessContext) {
    if (!obj(w) || !obj(w.record) || !text(w.record.source) || !text(w.record.sourceRecordId) || !strings(w.record.upstreamSources) || !validDate(w.record.date) || !obj(w.record.measurements) || !strings(w.activityIds, 1000) || !w.activityIds.length || !strings(w.flags) || w.usage !== scope.wellnessPolicy || w.usage === 'off') throw new Error('Invalid wellness context')
    if (!w.activityIds.every(activityId => report.sessions.some(s => s.activityId === activityId && s.record.startLocal.slice(0, 10) === w.record.date))) throw new Error('Wrong wellness association')
    for (const [key, m] of Object.entries(w.record.measurements)) if (!obj(m) || !finite(m.value) || m.value < 0 || metricUnits[key] !== m.unit || !(m.upstreamSource === null || text(m.upstreamSource))) throw new Error('Invalid wellness measurement')
  }
  return report
}

const statusValid = (s: unknown): boolean => obj(s) && text(s.category) && integer(s.recordCount) && (s.lastAttemptUtc === null || utc(s.lastAttemptUtc)) && (s.lastSuccessUtc === null || utc(s.lastSuccessUtc)) && text(s.readStatus) && integer(s.rejected) && integer(s.incomplete) && (s.latestObservedDate === null || validDate(s.latestObservedDate)) && (s.latestObservedAgeDays === null || Number.isSafeInteger(s.latestObservedAgeDays)) && text(s.upstreamFreshness)
function factualReviewValid(value: unknown, report: ScopedReport, request: ScopedRequest): boolean {
  if (!obj(value) || !id(value.evidenceReportSha256) || value.requestedFocus !== (request.focus ?? 'daily_combined') || value.profile !== 'factual-review-v1' || value.applicationGenerated !== true || !text(value.reason) || !strings(value.limitations) || !Array.isArray(value.unavailable) || !Array.isArray(value.sourceStatus) || !value.sourceStatus.every(statusValid) || !Array.isArray(value.groups) || !Array.isArray(value.focuses)) return false
  const comparisons = new Map(report.comparisons.map(c => [`${c.sport ?? ''}\u0000${c.key}`, c]))
  const factIds = new Set<string>(), groupIds = new Set<string>(), represented = new Set<string>()
  for (const g of value.groups) {
    if (!obj(g) || !text(g.id) || groupIds.has(g.id) || !obj(g.periods) || !['previousOldest','previousNewest','currentOldest','currentNewest'].every(k => validDate((g.periods as Record<string, unknown>)[k])) || !['available','sparse','unavailable'].includes(String(g.coverage)) || !Array.isArray(g.facts)) return false
    const periods = g.periods as Record<string, unknown>
    groupIds.add(g.id)
    for (const fact of g.facts) {
      if (!obj(fact) || !text(fact.id) || factIds.has(fact.id) || !['increased','decreased','unchanged','unavailable'].includes(String(fact.state)) || !text(fact.text) || !obj(fact.comparison) || !Array.isArray(fact.supportingMetrics) || fact.supportingMetrics.length !== 2) return false
      const c = fact.comparison, key = `${c.sport ?? ''}\u0000${c.key}`, actual = comparisons.get(key)
      if (!actual || represented.has(key) || Object.keys(actual).some(k => JSON.stringify(c[k]) !== JSON.stringify(actual[k as keyof Comparison]))) return false
      const [prev, curr] = fact.supportingMetrics
      if (!summaryValid(prev, report.comparablePrevious!, 'previous') || !summaryValid(curr, report.comparableSelected!, 'current') || prev.sport !== c.sport || curr.sport !== c.sport || prev.metric !== c.key || curr.metric !== c.key || prev.unit !== c.unit || curr.unit !== c.unit || prev.label !== c.label || curr.label !== c.label || prev.value !== c.previousValue || curr.value !== c.currentValue || prev.sampleCount !== c.previousSamples || curr.sampleCount !== c.currentSamples) return false
      if (periods.previousOldest !== prev.oldest || periods.previousNewest !== prev.newest || periods.currentOldest !== curr.oldest || periods.currentNewest !== curr.newest) return false
      const state = c.previousValue === null || c.currentValue === null || c.absoluteChange === null ? 'unavailable' : Number(c.absoluteChange) > 0 ? 'increased' : Number(c.absoluteChange) < 0 ? 'decreased' : 'unchanged'
      const coverage = state === 'unavailable' ? 'unavailable' : prev.sampleCount < 2 || curr.sampleCount < 2 || actual.flags.includes('sparse_comparison') ? 'sparse' : 'available'
      if (fact.state !== state || g.coverage !== coverage || !g.facts.length) return false
      factIds.add(fact.id); represented.add(key)
    }
  }
  if (represented.size !== comparisons.size) return false
  for (const focus of value.focuses) {
    if (!obj(focus) || !['activity_pattern','wellness_pattern','cross_metric_pattern','sport_mix','coverage_gap'].includes(String(focus.kind)) || !groupIds.has(String(focus.groupId)) || !Array.isArray(focus.factIds) || !focus.factIds.length || !strings(focus.factIds) || new Set(focus.factIds).size !== focus.factIds.length || !text(focus.text)) return false
    const group = value.groups.find(g => g.id === focus.groupId)
    if (focus.factIds.some(x => !group.facts.some((f: {id:string}) => f.id === x))) return false
  }
  return true
}
function summaryValid(s: unknown, period: Period, which: 'previous'|'current'): s is FactualSummary {
  if (!obj(s) || s.evidenceId !== `${which}/${s.sport ?? 'wellness'}/${s.metric}` || s.period !== which || !validDate(s.oldest) || !validDate(s.newest) || !(s.sport === null || text(s.sport)) || !text(s.metric) || !text(s.label) || !text(s.unit) || !['sum','mean'].includes(String(s.aggregation)) || !nullable(s.value) || !integer(s.sampleCount) || !integer(s.observedDays) || !integer(s.periodDays) || !(s.firstObservedDate === null || validDate(s.firstObservedDate)) || !(s.lastObservedDate === null || validDate(s.lastObservedDate)) || !strings(s.sources) || !strings(s.metricOrigins) || !strings(s.recordOrigins) || !integer(s.unknownMetricOriginCount) || !strings(s.flags)) return false
  const metric = s.sport === null ? period.wellness.find(m => m.key === s.metric) : period.sports.find(g => g.sport === s.sport)?.metrics.find(m => m.key === s.metric)
  if (!metric || s.oldest !== period.oldest || s.newest !== period.newest || s.periodDays !== period.days || s.aggregation !== metric.aggregation || s.value !== metric.value || s.sampleCount !== metric.sampleCount || s.observedDays !== metric.observedDays) return false
  const points = metric.points, sorted = points.map(p => p.date).sort()
  const union = (xs: string[]) => [...new Set(xs)].sort()
  return s.firstObservedDate === (sorted[0] ?? null) && s.lastObservedDate === (sorted.at(-1) ?? null) && s.sources.join('\0') === union(points.map(p => p.source)).join('\0') && s.metricOrigins.join('\0') === union(points.flatMap(p => p.upstreamSource === null ? [] : [p.upstreamSource])).join('\0') && s.recordOrigins.join('\0') === union(points.flatMap(p => p.recordOrigins)).join('\0') && s.unknownMetricOriginCount === points.filter(p => p.upstreamSource === null).length
}
