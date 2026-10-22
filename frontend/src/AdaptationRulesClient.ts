import { object } from './ContextIntakeClient'
import { realDate } from './ContextFields'
import { utc, dateCount } from './ScopedReviewTypes'
import { activityFrom, type Activity } from './PlanActivity'

const invalid = (): never => { throw new Error('Invalid private response') }
const finite = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v)
const integer = (v: unknown, min: number, max: number): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= min && v <= max
const text = (v: unknown, max: number): v is string => typeof v === 'string' && v.length > 0 && v.length <= max && v.trim().length > 0
const date = (v: unknown): v is string => typeof v === 'string' && realDate(v)
const zone = (v: unknown): v is string => {
  if (typeof v !== 'string') return false
  try { new Intl.DateTimeFormat('en', { timeZone: v }); return true } catch { return false }
}
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v)
const bounded = (v: unknown, maxBytes = 2_097_152): void => {
  try { if (new TextEncoder().encode(JSON.stringify(v)).length > maxBytes) invalid() } catch { invalid() }
}
const nonblank = (v: unknown, max: number): v is string => text(v, max)

export type SignalConfiguration = { timeZone: string; selector: { field: string; unit: string; intermediary: string; upstreamOrigin: string; method: string; basis: string }; baseline: { trailingDays: number; minimumSamples: number; minimumCoverage: number; maximumAgeDays: number }; direction: 'low' | 'high'; boundaryType: 'absolute_limit' | 'absolute_deviation' | 'percent_deviation'; triggerMagnitude: number; resetMagnitude: number; triggerPersistenceDays: number; resetPersistenceDays: number; cooldownSeconds: number }
export type WorkloadConfiguration = { aggregation: 'per_record' | 'cumulative'; metric: 'duration' | 'distance' | 'intervals_training_load'; unit: string; basis: string; sport: string; source: string; upstreamOrigin: string | null; timeZone: string; windowDays: number; comparator: 'greater_than' | 'at_least'; threshold: number }
type RuleBase<C> = { ruleId: string; revision: number; enabled: boolean; configuration: C | null; recordedUtc: string; recordedBy: 'authenticated_user' }
export type SignalRule = RuleBase<SignalConfiguration>
export type WorkloadRule = RuleBase<WorkloadConfiguration>
export type AdaptationRule = SignalRule | WorkloadRule
export type SignalDay = { date: string; status: string; reasons: string[]; value: number | null; baselineValue: number | null; baselineOldest: string; baselineNewest: string; baselineSelectedDays: number; baselineExpectedDays: number; triggerBoundary: number | null; resetBoundary: number | null; triggerCount: number; resetCount: number }
export type SignalSupportDay = { date: string; status: string; value: number | null; evidenceIds: string[]; candidateCount: number }
export type WorkloadItem = { activity: Activity; status: string; reason: string; value: number | null; field: string | null; unit: string | null; basis: string | null; source: string | null; upstreamOrigin: string | null }
export type SignalReport = { rule: SignalRule; query: { oldest: string; newest: string }; asOf: string; status: string; days: SignalDay[]; support: SignalSupportDay[]; sourceStatus: SourceStatus[]; admission: string; priorHistory: string; temporalBasis: string; upstreamFreshness: string }
export type WorkloadReport = { rule: WorkloadRule; oldest: string | null; newest: string | null; status: string; value: number | null; items: WorkloadItem[]; supportingActivityIds: string[]; admission: string; temporalBasis: string; completeness: string }
export type SourceStatus = { category: string; recordCount: number; lastAttemptUtc: string | null; lastSuccessUtc: string | null; readStatus: string; rejected: number; incomplete: number; latestObservedDate: string | null; latestObservedAgeDays: number | null; upstreamFreshness: string }

export function signalConfigurationFrom(v: unknown): SignalConfiguration {
  const c = object(v), s = object(c.selector), b = object(c.baseline)
  const field = s.field
  if (!zone(c.timeZone) || !['sleepSecs', 'hrv', 'hrvSDNN'].includes(String(field)) || s.unit !== (field === 'sleepSecs' ? 'seconds' : 'ms') ||
    typeof s.intermediary !== 'string' || !/^[a-zA-Z0-9_.-]{1,64}$/.test(s.intermediary) ||
    ![s.upstreamOrigin, s.method, s.basis].every(x => nonblank(x, 128) && x !== 'unknown') ||
    !integer(b.trailingDays, 1, 366) || !integer(b.minimumSamples, 1, b.trailingDays as number) || !finite(b.minimumCoverage) || b.minimumCoverage < 0 || b.minimumCoverage > 1 || !integer(b.maximumAgeDays, 0, 366) ||
    !['low', 'high'].includes(String(c.direction)) || !['absolute_limit', 'absolute_deviation', 'percent_deviation'].includes(String(c.boundaryType)) ||
    !finite(c.triggerMagnitude) || c.triggerMagnitude < 0 || !finite(c.resetMagnitude) || c.resetMagnitude < 0 ||
    (c.boundaryType === 'absolute_limit' ? c.direction === 'low' ? c.resetMagnitude <= c.triggerMagnitude : c.resetMagnitude >= c.triggerMagnitude : c.resetMagnitude >= c.triggerMagnitude) ||
    !integer(c.triggerPersistenceDays, 1, 366) || !integer(c.resetPersistenceDays, 1, 366) || !integer(c.cooldownSeconds, 0, 31_622_400)) invalid()
  return c as unknown as SignalConfiguration
}
export function workloadConfigurationFrom(v: unknown): WorkloadConfiguration {
  const c = object(v)
  if (!['per_record', 'cumulative'].includes(String(c.aggregation)) || !zone(c.timeZone) || !integer(c.windowDays, 1, 366) || !['greater_than', 'at_least'].includes(String(c.comparator)) || !finite(c.threshold) || c.threshold < 0 ||
    !nonblank(c.sport, 80) || typeof c.source !== 'string' || !/^[a-zA-Z0-9_.-]{1,64}$/.test(c.source) || !(c.upstreamOrigin === null || nonblank(c.upstreamOrigin, 128))) invalid()
  const valid = c.metric === 'duration' ? c.unit === 'seconds' && ['moving', 'elapsed'].includes(String(c.basis)) : c.metric === 'distance' ? c.unit === 'metres' && c.basis === 'session_total' : c.metric === 'intervals_training_load' && c.unit === 'Intervals.icu load' && c.basis === 'intervals_session_load' && c.source === 'intervals.icu'
  if (!valid) invalid()
  return c as unknown as WorkloadConfiguration
}
function rule<C>(v: unknown, parse: (x: unknown) => C): RuleBase<C> {
  const r = object(v)
  if (!uuid(r.ruleId) || !integer(r.revision, 1, 1000) || typeof r.enabled !== 'boolean' || !(r.configuration === null || parse(r.configuration)) || r.enabled && r.configuration === null || typeof r.recordedUtc !== 'string' || !utc(r.recordedUtc) || r.recordedBy !== 'authenticated_user') invalid()
  return r as RuleBase<C>
}
export const signalRuleFrom = (v: unknown): SignalRule => rule(v, signalConfigurationFrom)
export const workloadRuleFrom = (v: unknown): WorkloadRule => rule(v, workloadConfigurationFrom)
function rules<T>(v: unknown, parse: (x: unknown) => T): T[] {
  bounded(v); if (!Array.isArray(v) || v.length > 20) invalid()
  const result = (v as unknown[]).map(parse), ids = result.map(x => object(x).ruleId)
  if (new Set(ids).size !== ids.length) invalid()
  return result
}
export const signalRulesFrom = (v: unknown): SignalRule[] => rules(v, signalRuleFrom)
export const workloadRulesFrom = (v: unknown): WorkloadRule[] => rules(v, workloadRuleFrom)

const nullableFinite = (v: unknown): v is number | null => v === null || finite(v)
const statuses = ['missing', 'ambiguous', 'unique_compatible', 'unverified', 'incompatible']
export function signalSupportFrom(v: unknown): SignalSupportDay {
  const s = object(v)
  if (!date(s.date) || !statuses.includes(String(s.status)) || !nullableFinite(s.value) || s.value!==null&&Number(s.value)<0 || !Array.isArray(s.evidenceIds) || s.evidenceIds.length > 50_000 || s.evidenceIds.some(x => !nonblank(x, 4096)) || new Set(s.evidenceIds).size !== s.evidenceIds.length || !integer(s.candidateCount, 0, 50_000) || s.evidenceIds.length > s.candidateCount || (s.status === 'unique_compatible') !== (s.value !== null) || (s.status==='missing' ? s.candidateCount!==0 : s.candidateCount===0)) invalid()
  return s as SignalSupportDay
}
export function signalDayFrom(v: unknown): SignalDay {
  const d = object(v)
  if (!date(d.date) || !['unevaluable', 'trigger', 'reset', 'below_persistence', 'hysteresis_band'].includes(String(d.status)) || !Array.isArray(d.reasons) || d.reasons.length > 64 || d.reasons.some(x => !nonblank(x, 128)) ||
    !nullableFinite(d.value) || d.value!==null&&Number(d.value)<0 || !nullableFinite(d.baselineValue) || d.baselineValue!==null&&Number(d.baselineValue)<0 || !date(d.baselineOldest) || !date(d.baselineNewest) || d.baselineNewest>=d.date || dateCount(d.baselineOldest,d.baselineNewest)<1 || !integer(d.baselineSelectedDays, 0, 366) || !integer(d.baselineExpectedDays, 1, 366) || d.baselineSelectedDays > d.baselineExpectedDays || !nullableFinite(d.triggerBoundary) || !nullableFinite(d.resetBoundary) || !integer(d.triggerCount, 0, 366) || !integer(d.resetCount, 0, 366)) invalid()
  if(d.status!=='unevaluable'&&(d.value===null||d.baselineValue===null||d.triggerBoundary===null||d.resetBoundary===null||!Array.isArray(d.reasons)||d.reasons.length!==0))invalid()
  return d as SignalDay
}
function sourceStatusFrom(v: unknown): SourceStatus {
  const s = object(v)
  if (s.category !== 'wellness' || !integer(s.recordCount, 0, Number.MAX_SAFE_INTEGER) || !(s.lastAttemptUtc === null || utc(s.lastAttemptUtc)) || !(s.lastSuccessUtc === null || utc(s.lastSuccessUtc)) || !text(s.readStatus, 64) || !integer(s.rejected, 0, Number.MAX_SAFE_INTEGER) || !integer(s.incomplete, 0, Number.MAX_SAFE_INTEGER) || !(s.latestObservedDate === null || date(s.latestObservedDate)) || !(s.latestObservedAgeDays === null || integer(s.latestObservedAgeDays, -4_000_000, 4_000_000)) || !text(s.upstreamFreshness, 64)) invalid()
  return s as SourceStatus
}
const dayDistance = (a: string, b: string): number => (Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / 86_400_000
export function signalReportFrom(v: unknown): SignalReport {
  bounded(v)
  const r = object(v), q = object(r.query)
  if (!Array.isArray(r.days) || r.days.length > 366 || !Array.isArray(r.support) || r.support.length > 732 || !Array.isArray(r.sourceStatus) || r.sourceStatus.length > 16 || !date(q.oldest) || !date(q.newest) || dayDistance(q.oldest, q.newest) < 0 || dayDistance(q.oldest, q.newest) > 365 || typeof r.asOf !== 'string' || !utc(r.asOf) || !['disabled', 'evaluated', 'unevaluable'].includes(String(r.status)) || r.admission !== 'not_integrated' || r.priorHistory !== 'unknown_before_evaluated_horizon' || r.temporalBasis !== 'source_date_window_using_records_available_at_preparation_not_point_in_time_replay' || !text(r.upstreamFreshness, 64)) invalid()
  const oldest = q.oldest as string, newest = q.newest as string
  const days = (r.days as unknown[]).map(signalDayFrom), support = (r.support as unknown[]).map(signalSupportFrom), sourceStatus = (r.sourceStatus as unknown[]).map(sourceStatusFrom)
  const ruleValue = signalRuleFrom(r.rule)
  const dayDates = days.map(d => d.date), supportDates = support.map(d => d.date)
  const trailing = ruleValue.configuration?.baseline.trailingDays ?? 0
  const supportEarliest = shift(oldest, -trailing)
  if (new Set(dayDates).size !== days.length || new Set(supportDates).size !== support.length || dayDates.some((d,i)=>i>0&&d<=dayDates[i-1]) || supportDates.some((d,i)=>i>0&&d<=supportDates[i-1]) ||
    (r.status==='disabled' ? ruleValue.enabled&&ruleValue.configuration!==null || days.length!==0 || support.length!==0 :
      !ruleValue.enabled||ruleValue.configuration===null||days.length!==dateCount(oldest,newest)||support.length!==dateCount(supportEarliest,newest)) ||
    days.some(d => d.date < oldest || d.date > newest || d.baselineOldest !== shift(d.date, -trailing) || d.baselineNewest !== shift(d.date, -1) || d.baselineExpectedDays !== trailing) || support.some(d => d.date < supportEarliest || d.date > newest) ||
    (r.status==='unevaluable'&&days.some(d=>d.status!=='unevaluable')||r.status==='evaluated'&&!days.some(d=>d.status!=='unevaluable'))) invalid()
  return { rule: ruleValue, query: { oldest, newest }, asOf: r.asOf as string, status: r.status as string, days, support, sourceStatus, admission: r.admission as string, priorHistory: r.priorHistory as string, temporalBasis: r.temporalBasis as string, upstreamFreshness: r.upstreamFreshness as string }
}
function shift(d: string, days: number): string { const dateValue = new Date(`${d}T00:00:00Z`); dateValue.setUTCDate(dateValue.getUTCDate() + days); return dateValue.toISOString().slice(0, 10) }

const itemStatuses = ['clear', 'contributing', 'triggered', 'unevaluable', 'needs_confirmation']
const itemReasons = ['activity_evidence_unavailable', 'matched_to_selected_plan', 'recording_not_verified_past', 'outside_configured_window', 'timing_or_match_unresolved', 'base_plan_required', 'match_unresolved', 'measurement_missing', 'unit_mismatch', 'unsupported_source_basis', 'invalid_measurement', 'assessed', 'origin_mismatch', 'configured_workload_exceeded', 'within_configured_limit', 'compatible_extra_evidence']
export function workloadRuleFromItem(v: unknown): WorkloadItem {
  const i = object(v)
  if (!itemStatuses.includes(String(i.status)) || !itemReasons.includes(String(i.reason)) || !nullableFinite(i.value) || i.value!==null&&Number(i.value)<0 || ![i.field, i.unit, i.basis, i.source, i.upstreamOrigin].every(x => x === null || nonblank(x, 128))) invalid()
  return { activity: activityFrom(i.activity), status: i.status as string, reason: i.reason as string, value: i.value as number | null, field: i.field as string | null, unit: i.unit as string | null, basis: i.basis as string | null, source: i.source as string | null, upstreamOrigin: i.upstreamOrigin as string | null }
}
export function workloadReportFrom(v: unknown): WorkloadReport {
  bounded(v)
  const r = object(v)
  if (!Array.isArray(r.items) || r.items.length > 50_000 || !Array.isArray(r.supportingActivityIds) || r.supportingActivityIds.length > 50_000 || !['disabled', 'base_plan_required', 'triggered', 'unevaluable', 'clear'].includes(String(r.status)) || !nullableFinite(r.value) || !(r.oldest === null || date(r.oldest)) || !(r.newest === null || date(r.newest)) || r.admission !== 'not_integrated' || r.temporalBasis !== 'verified_start_in_configured_zone_window_through_captured_cutoff' || r.completeness !== 'bounded_local_evidence_not_upstream_completeness') invalid()
  const ruleValue = workloadRuleFrom(r.rule), items = (r.items as unknown[]).map(workloadRuleFromItem), ids = items.map(i => i.activity.activityId), supported = r.supportingActivityIds as unknown[]
  const oldest = r.oldest as string | null, newest = r.newest as string | null
  const config=ruleValue.configuration
  if (new Set(ids).size !== ids.length || !supported.every(x => typeof x === 'string' && /^[a-f0-9]{64}$/.test(x) && ids.includes(x)) || new Set(supported).size !== supported.length ||
    (r.status === 'disabled' ? ruleValue.enabled||oldest!==null||newest!==null||items.length!==0||supported.length!==0||r.value!==null :
      !ruleValue.enabled||!config||oldest===null||newest===null||dateCount(oldest,newest)!==config.windowDays)) invalid()
  const expectedSupport=items.filter(i=>['configured_workload_exceeded','within_configured_limit','compatible_extra_evidence'].includes(i.reason))
  if(expectedSupport.length!==supported.length||expectedSupport.some(i=>!supported.includes(i.activity.activityId))||
    expectedSupport.some(i=>i.value===null||i.unit!==config?.unit||i.basis!==config?.basis||i.source!==config?.source||i.upstreamOrigin!==config?.upstreamOrigin)||
    config?.aggregation==='per_record'&&r.value!==null)invalid()
  return { rule: ruleValue, oldest, newest, status: r.status as string, value: r.value as number | null, items, supportingActivityIds: supported as string[], admission: r.admission as string, temporalBasis: r.temporalBasis as string, completeness: r.completeness as string }
}
