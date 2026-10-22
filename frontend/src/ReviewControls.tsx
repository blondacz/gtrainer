import { useEffect, useRef, useState } from 'react'
import { ScopedReviewControls } from './ScopedReviewControls'
import type { ScopeDto } from './ScopedReviewTypes'
import { dateCount } from './ScopedReviewTypes'
import { loadEvidence, type EvidenceSnapshot } from './ScopedEvidenceClient'
import { ScopedEvidenceInspection } from './ScopedEvidenceInspection'

const PROFILE = 'review-focus-app-v1'
const focuses = ['daily_combined', 'activity_balance', 'wellness', 'missing_wellness']
const types = ['daily_combined', 'after_activity', 'weekly']
const levels = ['interim', 'thorough']
const kinds = ['changed_data', 'daily_time', 'weekly_time', 'activity_arrival', 'wellness_arrival', 'sleep_arrival', 'record_count', 'daily_steps']
const names: Record<string, string> = {
  daily_combined: 'Combined activity and wellness', activity_balance: 'Recorded time across sports', wellness: 'Wellness comparisons',
  missing_wellness: 'Wellness coverage gaps', after_activity: 'After a received activity', weekly: 'Weekly review',
  changed_data: 'Changed-data check', daily_time: 'Daily local time', weekly_time: 'Weekly local time', activity_arrival: 'Activity arrival',
  wellness_arrival: 'Wellness arrival', sleep_arrival: 'Sleep arrival', record_count: 'Received-record count', daily_steps: 'Verified daily steps',
  day: 'Today', week: 'Current week', rolling: 'Rolling dates', activity: 'Received activity', interim: 'Interim', thorough: 'Thorough',
}
const reasonText: Record<string, string> = {
  schedules_disabled: 'Schedules are off.', schedules_paused: 'Reviews are paused.', preset_disabled: 'This preset is off.',
  explicit_local_selection_required: 'Explicitly enable a local model before requesting reviews.',
  runtime_guard_unavailable: 'Runtime qualification guard is unavailable. Generation is disabled; factual charts remain available.',
  runtime_guard_failed: 'Runtime qualification failed. No optional AI result is displayed.',
  verified_daily_steps_unavailable: 'Verified daily steps are unavailable; this rule cannot run.',
  usable_daily_steps_unavailable: 'No usable verified daily steps for this date; this rule cannot run.',
  waiting_for_trigger: 'Waiting for the configured condition.', scheduler_ready: 'Schedule routing is ready.',
  review_configuration_invalid: 'Local review configuration is unavailable.', review_not_configured: 'No local review models configured.',
  interpretation_disabled: 'Optional AI interpretation is off.', model_not_selected: 'No local model selected.',
  experimental_review_selected: 'Experimental local model explicitly enabled.', model_selection_changed: 'Model selection changed; refresh before retrying.',
  local_model_unavailable: 'Local model unavailable. Factual charts remain available.', model_unavailable: 'Local model unavailable. Factual charts remain available.',
  review_due: 'A review condition was met.', review_running: 'A queued review is running.', review_queued: 'Review queued.', review_idle: 'No review is running.',
  manual_request: 'Explicit immediate request', changed_data: 'Imported data changed', daily_time: 'Daily local-time rule', weekly_time: 'Weekly local-time rule',
  activity_arrival: 'Received activity', wellness_arrival: 'Received wellness', sleep_arrival: 'Received sleep', record_count: 'Received-record threshold', daily_steps: 'Verified daily-step threshold',
  validated_focus_selection: 'Optional model selection recorded; its content is not displayed here.',
  insufficient_input: 'Insufficient supported comparisons.', unusable_model_output: 'Optional AI output rejected.',
  restart_requires_explicit_request: 'Interrupted by restart; an explicit new request is required.',
  profile_execution_closed_plan_unchanged: 'Execution closed; the plan is unchanged.', evidence_preparation_blocked: 'Evidence preparation was blocked.',
  factual_preparation_running: 'Local factual evidence preparation is running; no inference.', factual_preparation_queued: 'Local factual evidence preparation is queued; no inference.',
  preview_not_prepared: 'Exact preview was not prepared; inspection and restart will not retry it.',
  preview_preparation_unavailable: 'Exact preview preparation was blocked; factual evidence remains separate.',
  exact_consent_required_execution_closed: 'Exact-packet consent would be required; execution remains closed.',
  runtime_admission_closed: 'Personal runtime admission is closed.', preview_not_current: 'This preview is no longer current.', preview_not_available: 'Preview was discarded or is unavailable.',
}
const reason = (code: string) => reasonText[code] ?? 'Status unavailable or unsupported; no review time is inferred.'
type Budget = { callTimeoutMillis: number; jobTimeoutMillis: number; allowCorrectiveAttempt: boolean }
type Policy = { debounceSeconds: number; cooldownSeconds: number; maximumDeferralSeconds: number }
type Scope = { kind: string; days: number; sport: string | null }
type Trigger = { id: string; kind: string; intervalMinutes: number; localTime: string | null; dayOfWeek: number | null; threshold: number | null; period: string; settleSeconds: number }
type Preset = { id: string; reviewType: string; enabled: boolean; scope: Scope; level: string; focus: string; budget: Budget; triggers: Trigger[] }
type ProviderSelection = { providerId: string; modelId: string; providerKind: 'local'|'hosted' }
type PurposeSlot = { slotId: string; purpose: 'morning'|'evening'; enabled: boolean; localTime: string; profile: 'scoped-descriptive-v1'; providerSelection: ProviderSelection|null }
type PurposeStatus = { slotId: string; purpose: 'morning'|'evening'; state: string; nextUtc: string|null; limitation: string }
type Configuration = { enabled: boolean; paused: boolean; timeZone: string; presets: Preset[]; queuePolicy: Policy; purposeSlots?: PurposeSlot[] }
type Model = { id: string; label: string; tag: string; digest: string; experimental: true }
type Models = { profile: string; selectedModelId: string | null; selectionVersion: number; enabled: boolean; correctionEnabled: boolean;
  runtimeGuardConfigured: boolean; models: Model[]; reason: string; hostedEnabled: false; executionPolicy: Budget }
type ModelDraft = { modelId: string | null; enabled: boolean; allowCorrectiveAttempt: boolean }
type Intent = { occurrenceId: string; presetId: string; configurationVersion: number; modelId: string; selectionVersion: number;
  reviewType: string; scope: Scope; oldest: string; newest: string; activitySha256: string | null; level: string; focus: string;
  budget: Budget; reasons: string[]; dueUtc: string; notBeforeUtc: string | null }
type TriggerStatus = { presetId: string; triggerId: string; kind: string; reason: string; nextCheckUtc: string | null; nextReviewUtc: string | null }
type Schedule = { configuration: Configuration; configurationVersion: number; reason: string; triggers: TriggerStatus[]; due: Intent[]; executionAvailable: boolean; purposeSlots?: PurposeStatus[] }
type ScopedIntent = { profile: 'scoped-descriptive-v1'; purpose: 'morning'|'evening'|'activity_reflection'|'period_reflection'; configurationVersion: number; scope: ScopeDto; sourceRevision: number; planReasonRevision: number; providerSelection: ProviderSelection|null }
type Job = { id: string; key: string; intent: Intent | null; scopedIntent?: ScopedIntent | null; reasons: string[]; occurrenceIds: string[]; firstQueuedUtc: string; lastQueuedUtc: string;
  eligibleUtc: string; maximumDeferralUtc: string; manual: boolean; startedUtc: string | null }
type Preview = { state: 'not_prepared'|'awaiting_consent'|'blocked_admission'|'blocked'|'unavailable'|'stale'; reason: string; executionId: string|null; createdUtc: string|null }
type Outcome = { id: string; intent: Intent | null; scopedIntent?: ScopedIntent | null; status: string; reason: string; finishedUtc: string; startedUtc: string | null; facts: unknown; interpretation: unknown; stale: boolean; snapshotId?: string | null; evidenceCutoffUtc?: string|null; preview?: Preview|null }
type Queue = { reason: string; executionAvailable: boolean; policy: Policy; pending: Job[]; running: Job | null; outcomes: Outcome[]; lastSuccess: Outcome | null }
const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const text = (v: unknown, maximum = 256): v is string => typeof v === 'string' && v.length <= maximum
const uuid = (v: unknown): v is string => typeof v==='string' && /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(v)
const strings = (v: unknown): v is string[] => Array.isArray(v) && v.length <= 50_000 && v.every(x=>text(x))
const integer = (v: unknown, min: number, max: number) => typeof v === 'number' && Number.isSafeInteger(v) && v >= min && v <= max
const version = (v: unknown) => integer(v, 0, Number.MAX_SAFE_INTEGER)
const id = (v: unknown) => typeof v === 'string' && /^[a-z0-9-]{1,40}$/.test(v)
const digest = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
const date = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v) &&
  Number.isFinite(Date.parse(v)) && new Date(v).toISOString().slice(0, 10) === v
const utc = (v: unknown): v is string => typeof v === 'string' &&
  /^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?Z$/.test(v) && date(v.slice(0, 10)) && Number.isFinite(Date.parse(v))
const optionalUtc = (v: unknown) => v === null || utc(v)
const bool = (v: unknown) => typeof v === 'boolean'
function budgetValid(v: unknown): v is Budget {
  return object(v) && integer(v.callTimeoutMillis, 1, 240_000) && integer(v.jobTimeoutMillis, Number(v.callTimeoutMillis), 600_000) && bool(v.allowCorrectiveAttempt)
}
function policyValid(v: unknown): v is Policy {
  return object(v) && integer(v.debounceSeconds, 0, 3600) && integer(v.cooldownSeconds, 0, 86400) &&
    integer(v.maximumDeferralSeconds, 1, 86400) && Number(v.debounceSeconds) <= Number(v.maximumDeferralSeconds)
}
function scopeValid(v: unknown): v is Scope {
  return object(v) && ['day', 'week', 'rolling', 'activity'].includes(String(v.kind)) && integer(v.days, 1, 90) &&
    (v.sport === null || typeof v.sport === 'string' && /^[a-zA-Z0-9 _-]{1,64}$/.test(v.sport)) &&
    (v.kind === 'rolling' || v.days === (v.kind === 'week' ? 7 : 1))
}
function triggerValid(v: unknown): v is Trigger {
  if (!object(v) || !id(v.id) || !kinds.includes(String(v.kind)) || !integer(v.intervalMinutes, 1, 10080) ||
      !integer(v.settleSeconds, 0, 3600) || !['day', 'week'].includes(String(v.period))) return false
  const time = typeof v.localTime === 'string' && /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(v.localTime)
  if (v.kind === 'daily_time') return time && v.dayOfWeek === null && v.threshold === null
  if (v.kind === 'weekly_time') return time && integer(v.dayOfWeek, 1, 7) && v.threshold === null
  if (v.kind === 'record_count' || v.kind === 'daily_steps') return integer(v.threshold, 1, 1_000_000) && v.localTime === null &&
    v.dayOfWeek === null && (v.kind !== 'daily_steps' || v.period === 'day')
  return v.localTime === null && v.dayOfWeek === null && v.threshold === null
}
function presetValid(v: unknown): v is Preset {
  return object(v) && id(v.id) && types.includes(String(v.reviewType)) && bool(v.enabled) && scopeValid(v.scope) &&
    levels.includes(String(v.level)) && focuses.includes(String(v.focus)) && budgetValid(v.budget) && Array.isArray(v.triggers) &&
    v.triggers.length <= 8 && v.triggers.every(triggerValid) && new Set(v.triggers.map(t => t.id)).size === v.triggers.length &&
    (v.scope.kind !== 'activity' || v.triggers.every(t => t.kind === 'activity_arrival'))
}
function configurationValid(v: unknown): v is Configuration {
  if (!object(v) || !bool(v.enabled) || !bool(v.paused) || typeof v.timeZone !== 'string' || !v.timeZone || v.timeZone.length > 80 ||
      !Array.isArray(v.presets) || v.presets.length < 1 || v.presets.length > 12 || !v.presets.every(presetValid) ||
      new Set(v.presets.map(p => p.id)).size !== v.presets.length || !policyValid(v.queuePolicy) ||
      !(v.purposeSlots===undefined || Array.isArray(v.purposeSlots) && v.purposeSlots.length<=2 && v.purposeSlots.every(slotValid) &&
        new Set(v.purposeSlots.map(s=>s.slotId)).size===v.purposeSlots.length && new Set(v.purposeSlots.map(s=>s.purpose)).size===v.purposeSlots.length)) return false
  try { new Intl.DateTimeFormat('en', { timeZone: v.timeZone }); return true } catch { return false }
}
function slotValid(v: unknown): v is PurposeSlot {
  return object(v) && uuid(v.slotId) && (v.purpose==='morning' || v.purpose==='evening') && bool(v.enabled) &&
    v.profile==='scoped-descriptive-v1' && typeof v.localTime==='string' && /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(v.localTime) &&
    (v.providerSelection===undefined || v.providerSelection===null || providerSelectionValid(v.providerSelection))
}
function providerSelectionValid(v: unknown): v is ProviderSelection {
  return object(v) && typeof v.providerId==='string' && /^[A-Za-z0-9_.-]{1,64}$/.test(v.providerId) &&
    typeof v.modelId==='string' && /^[A-Za-z0-9_.:-]{1,128}$/.test(v.modelId) && (v.providerKind==='local'||v.providerKind==='hosted')
}
function modelsFrom(v: unknown): Models {
  if (!object(v) || v.profile !== PROFILE || v.hostedEnabled !== false || !version(v.selectionVersion) || !bool(v.enabled) ||
      !bool(v.correctionEnabled) || !bool(v.runtimeGuardConfigured) || !text(v.reason) || !budgetValid(v.executionPolicy) ||
      !Array.isArray(v.models) || v.models.length > 4 || !v.models.every(m => object(m) && typeof m.id === 'string' && /^[a-z0-9-]{1,64}$/.test(m.id) &&
        typeof m.label === 'string' && /^[A-Za-z0-9 .()-]{1,80}$/.test(m.label) && typeof m.tag === 'string' &&
        /^[a-z0-9][a-z0-9_.-]{0,63}:[a-z0-9][a-z0-9_.-]{0,63}$/.test(m.tag) && !m.tag.includes('cloud') && digest(m.digest) && m.experimental === true) ||
      new Set(v.models.map(m => m.id)).size !== v.models.length ||
      (v.selectedModelId !== null && !v.models.some(m => m.id === v.selectedModelId)) ||
      (v.enabled && v.selectedModelId === null) || (v.correctionEnabled && !v.executionPolicy.allowCorrectiveAttempt)) throw new Error('Invalid configuration')
  return v as unknown as Models
}
function intentValid(v: unknown): v is Intent {
  return object(v) && digest(v.occurrenceId) && id(v.presetId) && version(v.configurationVersion) && text(v.modelId) && version(v.selectionVersion) &&
    types.includes(String(v.reviewType)) && scopeValid(v.scope) && date(v.oldest) && date(v.newest) && v.oldest <= v.newest &&
    (v.activitySha256 === null || digest(v.activitySha256)) && (v.scope.kind !== 'activity' || digest(v.activitySha256)) &&
    levels.includes(String(v.level)) && focuses.includes(String(v.focus)) && budgetValid(v.budget) && strings(v.reasons) && utc(v.dueUtc) && optionalUtc(v.notBeforeUtc)
}
function scheduleFrom(v: unknown): Schedule {
  if (!object(v) || !configurationValid(v.configuration) || !version(v.configurationVersion) || !text(v.reason) || !bool(v.executionAvailable) ||
      !Array.isArray(v.due) || !v.due.every(intentValid) || !Array.isArray(v.triggers) || v.triggers.length > 96 || !v.triggers.every(t =>
        object(t) && id(t.presetId) && id(t.triggerId) && text(t.kind) && text(t.reason) && optionalUtc(t.nextCheckUtc) && optionalUtc(t.nextReviewUtc)) ||
      !(v.purposeSlots===undefined || Array.isArray(v.purposeSlots) && v.purposeSlots.length<=2 && v.purposeSlots.every(s=>object(s) && uuid(s.slotId) &&
        (s.purpose==='morning' || s.purpose==='evening') && typeof s.state==='string' && ['slot_disabled','schedules_paused','awaiting_explicit_rearm','factual_preparation_due','waiting_for_slot'].includes(s.state) && optionalUtc(s.nextUtc) && text(s.limitation)))) throw new Error('Invalid schedule')
  return v as unknown as Schedule
}
function scopedIntentValid(v: unknown): v is ScopedIntent {
  if (!object(v) || v.profile !== 'scoped-descriptive-v1' || typeof v.purpose!=='string' || !['morning','evening','activity_reflection','period_reflection'].includes(v.purpose) ||
       !version(v.configurationVersion) || !version(v.sourceRevision) || !version(v.planReasonRevision) || !('providerSelection' in v) || !(v.providerSelection===null || providerSelectionValid(v.providerSelection)) || !object(v.scope)) return false
  const s = v.scope
  const range = (x: unknown) => x === null || object(x) && date(x.oldest) && date(x.newest) && dateCount(x.oldest,x.newest)>=1 && dateCount(x.oldest,x.newest)<=366
  const r = s.request
  const c=s.comparison
  if(!object(c) || !['comparableSelected','comparablePrevious','priorCalendarFull','comparisonFull'].every(k=>range(c[k])) ||
    !bool(c.unequalDuration) || !bool(c.overlapping) || !(c.reason===null || text(c.reason)) || typeof c.policy!=='string' ||
    !['none','selected_sessions','preceding_calendar','preceding_equal_length','explicit','unavailable'].includes(c.policy) ||
    !['unmatchedSelectedDates','unmatchedPreviousDates','priorCalendarRemainderDates'].every(k=>Array.isArray(c[k]) && (c[k] as unknown[]).length<=366 && (c[k] as unknown[]).every(date))) return false
  try { if(!text(s.timeZone,80)) return false;new Intl.DateTimeFormat('en',{timeZone:s.timeZone}) } catch { return false }
  return object(r) && r.schemaVersion === 1 && (r.mode==='period' || r.mode==='activities') && (r.intent==='review' || r.intent==='compare') &&
    text(r.scope) && utc(s.asOf) && text(s.timeZone, 80) && !!s.timeZone && date(s.anchorDate) &&
    (s.wellnessPolicy==='off' || s.wellnessPolicy==='display_only' || s.wellnessPolicy==='assessment') && s.status === 'available' && strings(s.reasons) &&
    range(s.evidenceRange) && range(s.fullCalendarRange) && Array.isArray(s.activityIds) && s.activityIds.length<=1000 && s.activityIds.every(digest) && new Set(s.activityIds).size===s.activityIds.length &&
    Array.isArray(s.activityDates) && s.activityDates.length<=1000 && s.activityDates.every(date) && bool(s.soFar) && text(s.dateBasis, 4096)
}
function queueIntentValid(v: Record<string, unknown>): boolean {
  const legacy = v.intent === null || v.intent === undefined || intentValid(v.intent)
  const scoped = v.scopedIntent === null || v.scopedIntent === undefined || scopedIntentValid(v.scopedIntent)
  return legacy && scoped && (v.intent == null) !== (v.scopedIntent == null)
}
function jobValid(v: unknown): v is Job {
  return object(v) && (v.scopedIntent==null ? digest(v.id) : uuid(v.id)) && digest(v.key) && queueIntentValid(v) && strings(v.reasons) && strings(v.occurrenceIds) &&
    utc(v.firstQueuedUtc) && utc(v.lastQueuedUtc) && utc(v.eligibleUtc) && utc(v.maximumDeferralUtc) && bool(v.manual) && optionalUtc(v.startedUtc)
}
function outcomeValid(v: unknown): v is Outcome {
  // Facts/interpretation are opaque stored payloads: never render their text or numbers here.
  if (!object(v) || !(v.scopedIntent==null ? digest(v.id) : uuid(v.id)) || !queueIntentValid(v) || typeof v.status!=='string' || !['available', 'factual_only', 'unavailable', 'cancelled', 'interrupted', 'failed', 'factual_prepared', 'blocked'].includes(v.status) ||
     !text(v.reason) || !utc(v.finishedUtc) || !optionalUtc(v.startedUtc) || !bool(v.stale) || !('facts' in v) || !('interpretation' in v)) return false
   if (v.scopedIntent != null) { const scoped = v.scopedIntent as unknown as ScopedIntent; return v.facts === null && v.interpretation === null &&
     (['factual_prepared','blocked'].includes(v.status) ? uuid(v.snapshotId) : v.snapshotId===null || v.snapshotId===undefined) && !['available','factual_only'].includes(v.status) &&
       (v.evidenceCutoffUtc===undefined || v.evidenceCutoffUtc===null || utc(v.evidenceCutoffUtc)) &&
     (v.preview===undefined || v.preview===null || object(v.preview) && typeof v.preview.state==='string' && ['not_prepared','awaiting_consent','blocked_admission','blocked','unavailable','stale'].includes(v.preview.state) &&
       typeof v.preview.reason==='string' && /^[a-z][a-z0-9_]{1,63}$/.test(v.preview.reason) &&
       ((v.preview.executionId===null && v.preview.createdUtc===null) || uuid(v.preview.executionId) && utc(v.preview.createdUtc)) &&
        (!['awaiting_consent','blocked_admission'].includes(String(v.preview.state)) || uuid(v.preview.executionId)) && scoped.providerSelection!==null) &&
      (scoped.providerSelection!==null || v.preview==null) &&
      (!object(v.preview) || v.preview.state!=='awaiting_consent' || scoped.providerSelection?.providerKind==='hosted' && !v.stale) &&
      (!object(v.preview) || v.preview.state!=='blocked_admission' || scoped.providerSelection?.providerKind==='local' && !v.stale) }
  return ['available', 'factual_only', 'unavailable', 'cancelled', 'interrupted', 'failed'].includes(String(v.status)) &&
    v.preview==null && v.evidenceCutoffUtc==null
}
function queueFrom(v: unknown): Queue {
  if (!object(v) || !text(v.reason) || !bool(v.executionAvailable) || !policyValid(v.policy) || !Array.isArray(v.pending) || v.pending.length > 256 ||
      !v.pending.every(jobValid) || (v.running !== null && !jobValid(v.running)) || !Array.isArray(v.outcomes) || v.outcomes.length > 24 ||
      !v.outcomes.every(outcomeValid) || (v.lastSuccess !== null && (!outcomeValid(v.lastSuccess) || !['available', 'factual_only'].includes(v.lastSuccess.status)))) throw new Error('Invalid queue')
  return v as unknown as Queue
}
function snapshotDigest(outcome: Outcome): string | null {
  const v = outcome.facts
  return object(v) && v.profile === 'factual-review-v1' && v.applicationGenerated === true && digest(v.evidenceReportSha256) &&
    v.requestedFocus === outcome.intent?.focus && Array.isArray(v.groups) && Array.isArray(v.focuses) ? v.evidenceReportSha256 : null
}
const local = (value: string, zone: string) => {
  try { return `${new Intl.DateTimeFormat('en-GB', { timeZone: zone, dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value))} (${zone}); UTC ${value}` }
  catch { return `UTC ${value}; local time unavailable` }
}
const numeric = (value: string) => value.trim() === '' ? Number.NaN : Number(value)
const budgetDefault = (): Budget => ({ callTimeoutMillis: 120_000, jobTimeoutMillis: 130_000, allowCorrectiveAttempt: false })
function newTrigger(triggerId: string, kind = 'changed_data'): Trigger {
  return { id: triggerId, kind, intervalMinutes: 120, localTime: ['daily_time', 'weekly_time'].includes(kind) ? '18:00' : null,
    dayOfWeek: kind === 'weekly_time' ? 1 : null, threshold: ['record_count', 'daily_steps'].includes(kind) ? 1 : null, period: 'day', settleSeconds: 120 }
}
function NumberField({ label, value, min, max, onChange }: { label: string; value: number; min: number; max: number; onChange: (v: number) => void }) {
  return <label>{label}<input type="number" min={min} max={max} step="1" value={Number.isFinite(value) ? value : ''}
    onChange={event => onChange(numeric(event.target.value))} /></label>
}
function Choices({ label, value, options, onChange }: { label: string; value: string; options: string[]; onChange: (v: string) => void }) {
  return <label>{label}<select value={value} onChange={event => onChange(event.target.value)}>
    {options.map(option => <option key={option} value={option}>{names[option] ?? option}</option>)}
  </select></label>
}
function Tabs({ label, value, options, onChange }: { label: string; value: string; options: Record<string, string>; onChange: (v: string) => void }) {
  return <nav className="review-tabs" aria-label={label}>{Object.entries(options).map(([key, name]) =>
    <button type="button" className="secondary" key={key} aria-pressed={value === key} onClick={() => onChange(key)}>{name}</button>)}</nav>
}
function Pager({ label, index, count, onChange }: { label: string; index: number; count: number; onChange: (v: number) => void }) {
  return <nav className="review-pagination" aria-label={label}>
    <button type="button" className="secondary" disabled={index <= 0} onClick={() => onChange(index - 1)}>Previous {label}</button>
    <span>{count ? `${index + 1} of ${count}` : 'None'}</span>
    <button type="button" className="secondary" disabled={index + 1 >= count} onClick={() => onChange(index + 1)}>Next {label}</button>
  </nav>
}

export function ReviewControls({ csrfToken, revision }: { csrfToken: string; revision: number }) {
  const [models, setModels] = useState<Models | null>(null)
  const [modelDraft, setModelDraft] = useState<ModelDraft | null>(null)
  const [schedule, setSchedule] = useState<Schedule | null>(null)
  const [draft, setDraft] = useState<Configuration | null>(null)
  const [draftVersion, setDraftVersion] = useState(0)
  const [queue, setQueue] = useState<Queue | null>(null)
  const [evidence,setEvidence]=useState<EvidenceSnapshot|null>(null)
  const [executionPreview,setExecutionPreview]=useState<{id:string;data:Record<string,unknown>}|null>(null)
  const [selected, setSelected] = useState(0)
  const [runLevel, setRunLevel] = useState('interim')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('Loading private review controls…')
  const [dirty, setDirty] = useState(false)
  const [modelDirty, setModelDirty] = useState(false)
  const [view, setView] = useState('overview')
  const [editor, setEditor] = useState('basic')
  const [basicPage, setBasicPage] = useState('identity')
  const [overview, setOverview] = useState('request')
  const [triggerIndex, setTriggerIndex] = useState(0)
  const [statusIndex, setStatusIndex] = useState(0)
  const [jobIndex, setJobIndex] = useState(0)
  const [outcomeIndex, setOutcomeIndex] = useState(0)
  const dirtyRef = useRef(false)
  const modelDirtyRef = useRef(false)
  const generation = useRef(0)
  const previewGeneration=useRef(0)
  const controllers = useRef(new Set<AbortController>())
  const mounted = useRef(false)
  const reading = useRef(false)
  const writing = useRef(false)
  const previousToken = useRef(csrfToken)
  function invalidate() {
    generation.current++
    for (const controller of controllers.current) controller.abort()
    controllers.current.clear()
    reading.current = false
  }
  function current(epoch: number, controller: AbortController) {
    return mounted.current && generation.current === epoch && !controller.signal.aborted
  }
  async function request(path: string, controller: AbortController, method = 'GET', body?: unknown): Promise<unknown> {
    const response = await fetch(path, { method, credentials: 'same-origin', cache: 'no-store', signal: controller.signal,
      ...(method === 'GET' ? {} : { headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken }, body: JSON.stringify(body) }) })
    if (!response.ok) throw new Error('Review request unavailable')
    return response.json()
  }
  function acceptModel(data: Models) {
    setModels(data)
    if (!modelDirtyRef.current) setModelDraft({ modelId: data.selectedModelId, enabled: data.enabled, allowCorrectiveAttempt: data.correctionEnabled })
  }
  function acceptSchedule(data: Schedule) {
    setSchedule(data)
    if (!dirtyRef.current) { setDraft(data.configuration); setDraftVersion(data.configurationVersion) }
  }
  async function load(announce = false) {
    if (!mounted.current || reading.current || writing.current) return
    reading.current = true
    const epoch = generation.current
    const controller = new AbortController()
    controllers.current.add(controller)
    let failed = false
    async function read<T>(path: string, parse: (v: unknown) => T, accept: (v: T) => void, fail: () => void) {
      try {
        const data = parse(await request(path, controller))
        if (current(epoch, controller)) accept(data)
      } catch {
        failed = true
        if (current(epoch, controller)) fail()
      }
    }
    await Promise.all([
      read('/api/review-models', modelsFrom, acceptModel, () => setModels(null)),
      read('/api/review-schedules', scheduleFrom, acceptSchedule, () => setSchedule(null)),
      read('/api/review-queue', queueFrom, setQueue, () => setQueue(null)),
    ])
    if (current(epoch, controller)) {
      reading.current = false
      if (failed || announce) setMessage(failed ? 'Some review controls are unavailable or could not be verified. Reconnect or sign in again; factual charts remain available.' : 'Review status loaded. Reading does not request inference or save edits.')
    }
    controllers.current.delete(controller)
  }
  useEffect(() => {
    mounted.current = true
    invalidate()
    writing.current = false
    setBusy(false)
    setModels(null); setSchedule(null); setQueue(null);setEvidence(null);setExecutionPreview(null);previewGeneration.current++
    if (previousToken.current !== csrfToken) {
      previousToken.current = csrfToken
      dirtyRef.current = false; modelDirtyRef.current = false
      setDirty(false); setModelDirty(false); setDraft(null); setModelDraft(null); setSelected(0)
    }
    void load(true)
    return () => { mounted.current = false; invalidate() }
    // A new session/revision is a new request generation, even if fetch ignores abort.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [csrfToken, revision])
  const active = !!queue?.running || !!queue?.pending.length || !!(schedule?.configuration.enabled && !schedule.configuration.paused)
  useEffect(() => {
    if (!active) return
    const timer = window.setInterval(() => { void load() }, 10_000)
    return () => window.clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, csrfToken, revision])
  function edit(value: Configuration) { dirtyRef.current = true; setDirty(true); setDraft(value) }
  function editModel(value: ModelDraft) { modelDirtyRef.current = true; setModelDirty(true); setModelDraft(value) }
  function editPreset(value: Preset) {
    if (draft) edit({ ...draft, presets: draft.presets.map((p, index) => index === selected ? value : p) })
  }
  async function write(path: string, method: string, body: unknown, accept: (v: unknown) => void) {
    if (writing.current) return
    invalidate()
    const epoch = generation.current
    const controller = new AbortController()
    controllers.current.add(controller)
    writing.current = true; setBusy(true); setQueue(null)
    try {
      const value = await request(path, controller, method, body)
      if (current(epoch, controller)) { accept(value); setMessage(path === '/api/review-now' ? 'Immediate review enqueued, not a synchronous model call. Eligibility is not a guaranteed start time.' : 'Explicit changes saved.') }
    } catch {
      if (current(epoch, controller)) {
        if (path === '/api/review-models') setModels(null)
        if (path === '/api/review-schedules') setSchedule(null)
        setMessage('Review request failed or response could not be verified. Refresh status before retrying; no server diagnostics are displayed.')
      }
    } finally {
      controllers.current.delete(controller)
      if (current(epoch, controller)) { writing.current = false; setBusy(false); void load() }
    }
  }
  function saveModel(value: ModelDraft) {
    if (!models || (value.enabled && (!value.modelId || !models.runtimeGuardConfigured)) ||
        (value.allowCorrectiveAttempt && !models.executionPolicy.allowCorrectiveAttempt)) return
    void write('/api/review-models', 'PUT', { profile: PROFILE, ...value }, data => {
      const saved = modelsFrom(data)
      modelDirtyRef.current = false; setModelDirty(false); acceptModel(saved)
    })
  }
  function saveConfiguration(value: Configuration, expectedVersion: number, immediate = false) {
    if (!configurationValid(value)) { setMessage('Invalid review configuration. Nothing was saved; values are never clamped or repaired.'); return }
    void write('/api/review-schedules', 'PUT', { expectedVersion, configuration: value }, data => {
      const saved = scheduleFrom(data)
      if (immediate && dirtyRef.current && draft) {
        setSchedule(saved); setDraft({ ...draft, enabled: saved.configuration.enabled, paused: saved.configuration.paused })
        if (draftVersion === expectedVersion) setDraftVersion(saved.configurationVersion)
      } else { dirtyRef.current = false; setDirty(false); acceptSchedule(saved) }
    })
  }
  const preset = draft?.presets[selected]
  const savedPreset = schedule?.configuration.presets.find(p => p.id === preset?.id)
  const withinCaps = !!(draft && models && draft.presets.every(p => p.budget.callTimeoutMillis <= models.executionPolicy.callTimeoutMillis &&
    p.budget.jobTimeoutMillis <= models.executionPolicy.jobTimeoutMillis && (!p.budget.allowCorrectiveAttempt || models.executionPolicy.allowCorrectiveAttempt)))
  const validDraft = !!draft && configurationValid(draft) && withinCaps
  const canArm = !!(models?.enabled && models.selectedModelId && models.runtimeGuardConfigured)
  const canSave = !!(schedule && validDraft && (!draft?.enabled || draft.paused || !draft.presets.some(p=>p.enabled) || canArm))
  const canRun = !!(queue?.executionAvailable && canArm && schedule && !schedule.configuration.paused && savedPreset && savedPreset.scope.kind !== 'activity' && !dirty && !modelDirty && !busy)
  const zone = schedule?.configuration.timeZone ?? 'UTC'
  const jobs = queue ? [...(queue.running ? [queue.running] : []), ...queue.pending] : []
  const jobPage = Math.min(jobIndex, Math.max(0, jobs.length - 1))
  const outcomes = queue ? [...(queue.lastSuccess ? [queue.lastSuccess] : []), ...queue.outcomes.filter(o => o.id !== queue.lastSuccess?.id)] : []
  const outcomePage = Math.min(outcomeIndex, Math.max(0, outcomes.length - 1))
  const statusPage = Math.min(statusIndex, Math.max(0, (schedule?.triggers.length ?? 0) - 1))
  const triggerPage = Math.min(triggerIndex, Math.max(0, (preset?.triggers.length ?? 0) - 1))
  async function inspectSnapshot(snapshotId: string) {
    if(writing.current) return
    const epoch=generation.current,controller=new AbortController();controllers.current.add(controller);setEvidence(null)
    try { const next=await loadEvidence(snapshotId,controller.signal);if(current(epoch,controller)) setEvidence(next) }
    catch { if(current(epoch,controller)) setMessage('Factual snapshot unavailable or removed. No inference or retry was requested.') }
    finally { controllers.current.delete(controller) }
  }
  async function inspectExecution(outcome: Outcome) {
    if(writing.current) return
    const preview=outcome.preview, selection=outcome.scopedIntent?.providerSelection
    if (!preview?.executionId || !selection || preview.state==='stale' || preview.state==='unavailable') return
    const epoch=generation.current,ticket=++previewGeneration.current,controller=new AbortController();controllers.current.add(controller);setExecutionPreview(null)
    try {
      const data=await request(`/api/review-executions/${encodeURIComponent(preview.executionId)}`,controller)
      if(!object(data)||!object(data.binding)||!object(data.binding.source)||!object(data.binding.providerSelection)) throw new Error()
      const b=data.binding, source=b.source as Record<string,unknown>, ps=b.providerSelection as Record<string,unknown>
      const prompt=(v:unknown)=>object(v)&&typeof v.systemInstructions==='string'&&typeof v.userPacketJson==='string'&&
        new TextEncoder().encode(JSON.stringify(v)).length<=32*1024
      if(b.executionId!==preview.executionId||b.profile!=='scoped-descriptive-v1'||source.snapshotId!==outcome.snapshotId||
        ps.providerId!==selection.providerId||ps.modelId!==selection.modelId||ps.providerKind!==selection.providerKind||
        !digest(b.consentSha256)||!prompt(b.exactPrompt)||!prompt(b.exactCorrectionPrompt)||
        !object(data.admission)||data.admission.profile!==b.profile||data.admission.supported!==true||data.admission.executionAllowed!==false||
        !strings(data.admission.blockingReasons)||typeof data.state!=='string'||
        !['IN_FLIGHT','RUNNING','PUBLISHED','FAILED','STALE','INTERRUPTED'].includes(data.state)) throw new Error()
      if(data.state!=='IN_FLIGHT') throw new Error()
      if(current(epoch,controller)&&ticket===previewGeneration.current) setExecutionPreview({id:preview.executionId,data})
    } catch { if(current(epoch,controller)&&ticket===previewGeneration.current) {
      setQueue(q=>q ? {...q,outcomes:q.outcomes.map(o=>o.id===outcome.id && o.preview ? {...o,preview:{...o.preview,state:'unavailable',reason:'preview_not_available'}} : o)} : q)
      setMessage('Execution preview is unavailable, stale, discarded, or did not match this outcome. No inference or retry was requested.')
    } }
    finally {controllers.current.delete(controller)}
  }
  function renderJob(job: Job, running = false) {
    if (job.scopedIntent) return <li key={job.id}><p>{running ? 'Running' : 'Pending'} scoped factual preparation — {job.scopedIntent.purpose}.</p>
      <p>Routing cutoff: {job.scopedIntent.scope.asOf} (UTC). No plan change or model execution is implied.</p></li>
    if (!job.intent) return null
    return <li key={job.id}><p>{running ? 'Running' : 'Pending'}: {names[job.intent.reviewType]}; {names[job.intent.scope.kind]}, {job.intent.scope.days} date(s)
      {job.intent.scope.sport ? `; sport ${job.intent.scope.sport}` : '; all sports'}; {names[job.intent.level]}.</p>
      <p>Metric coverage dates: {job.intent.oldest}–{job.intent.newest}. Reasons: {job.reasons.map(reason).join('; ')}.</p>
      {running ? <p>Started UTC: {job.startedUtc ?? 'Unavailable'}; completion time unknown.</p> : <>
        <p>Eligible: {local(job.eligibleUtc, zone)}. Maximum deferral: {local(job.maximumDeferralUtc, zone)}.</p>
        <p>Estimated eligibility only, not a guaranteed start or completion time.</p>
      </>}
    </li>
  }
  function renderOutcome(outcome: Outcome, last = false) {
    if (outcome.scopedIntent) return <article key={outcome.id}><h4>{outcome.status === 'factual_prepared' ? 'Factual evidence prepared' : outcome.status === 'blocked' ? 'Evidence preparation blocked' : 'Scoped review outcome'}</h4>
      <p>Purpose: {outcome.scopedIntent.purpose}. Routing cutoff: {outcome.scopedIntent.scope.asOf} (UTC).</p>
      <p>Actual evidence read cutoff: {outcome.evidenceCutoffUtc ?? 'not recorded'} (UTC). Preparation finished: {outcome.finishedUtc} (UTC).</p>
      {outcome.preview && <><p>Preview: {outcome.preview.state} ({reason(outcome.preview.reason)}). {outcome.preview.createdUtc ? `Created ${outcome.preview.createdUtc} (UTC).` : ''}</p>{outcome.preview.state==='awaiting_consent' && <p>Awaiting consent only; this execution is unavailable/closed. Inspection is read-only; no consent, run, or send action is offered.</p>}{outcome.preview.executionId && outcome.scopedIntent.providerSelection && !['stale','unavailable'].includes(outcome.preview.state) && <button type="button" disabled={busy} onClick={()=>void inspectExecution(outcome)}>Inspect exact preview (read only)</button>}</>}
      <p>{outcome.stale ? 'Stale evidence or settings; inspect before using this historical snapshot.' : 'Stored factual snapshot; upstream completeness remains unknown.'}</p>
      {outcome.snapshotId && <><p>Factual snapshot ID: {outcome.snapshotId}.</p><button type="button" disabled={busy} onClick={()=>void inspectSnapshot(outcome.snapshotId!)}>Inspect scheduled evidence (read only)</button></>}
      <p>{reason(outcome.reason)} No plan change or new model execution is implied.</p></article>
    if (!outcome.intent) return null
    const hash = snapshotDigest(outcome)
    const stale = outcome.stale || !!schedule && outcome.intent.configurationVersion !== schedule.configurationVersion ||
      !!models && (outcome.intent.selectionVersion !== models.selectionVersion || outcome.intent.modelId !== models.selectedModelId)
    return <article key={outcome.id}><h4>{last ? 'Last successful review' : 'Completed review'}</h4>
      <p>Finished UTC: {outcome.finishedUtc}. Metric coverage dates: {outcome.intent.oldest}–{outcome.intent.newest}; {names[outcome.intent.scope.kind]}; {names[outcome.intent.level]}.</p>
      <p>{stale ? 'Older than the latest data or settings — stale snapshot, not current coverage.' : 'Stored coverage snapshot; source freshness is not guaranteed.'}</p>
      <p>Factual snapshot: {hash ? 'recorded (metadata only; contents unverified here)' : 'unavailable or unverified'}. {hash && <>Evidence digest: {hash}.</>}</p>
      <p>Optional AI result: {outcome.interpretation === null ? 'not recorded' : 'withheld — stored support is not independently verified in this metadata view'}.</p>
      <p>{reason(outcome.reason)} Factual comparisons and metric values remain in the separate charts and factual review; no stored prose is rendered here.</p>
    </article>
  }
  return <section className="analysis-panel" aria-labelledby="review-controls-heading">
    <h3 id="review-controls-heading">Review controls — experimental local AI</h3>
    <p>Experimental local review; off after restart. No hosted fallback or host transfer. Facts and charts stay independent of optional AI.</p>
    <p aria-live="polite">{message}</p>
    <div className="review-toolbar"><Tabs label="Review sections" value={view} options={{ overview: 'Review overview', scoped: 'Scoped factual review / compare', slots: 'Morning / evening slots', preset: 'Preset editor', model: 'Model selection' }} onChange={setView} />
    <button type="button" className="secondary" disabled={busy} onClick={() => void load(true)}>Refresh review status</button>
    </div>
    {view === 'scoped' && <ScopedReviewControls csrfToken={csrfToken} revision={revision} idPrefix="review-scoped" />}
    {view==='slots' && <fieldset disabled={!draft || !schedule || busy}><legend>Explicit daily factual preparation</legend>
      <p>No default times or enrollment. These slots prepare evidence locally; model selection, qualification, runtime admission and exact hosted consent remain separate. No source sync is requested. The plan stays unchanged.</p>
      <p>Provider/model identifiers are preview preferences, not proof that a provider is configured or qualified.</p>
      <label>Daily slot timezone<input value={draft?.timeZone ?? ''} maxLength={80} placeholder="Europe/Prague" onChange={e=>draft && edit({...draft,timeZone:e.target.value})}/></label>
      {(['morning','evening'] as const).map(purpose=>{
        const slot=draft?.purposeSlots?.find(s=>s.purpose===purpose)
        return <section key={purpose} aria-label={`${purpose} purpose slot`}><h4>{purpose==='morning' ? 'Morning briefing' : 'Evening reflection'}</h4>
         {!slot ? <button type="button" onClick={()=>draft && edit({...draft,purposeSlots:[...(draft.purposeSlots ?? []),{slotId:crypto.randomUUID(),purpose,enabled:true,localTime:'',profile:'scoped-descriptive-v1',providerSelection:null}]})}>Configure {purpose} slot</button> : <>
            <label><input type="checkbox" checked={slot.enabled} onChange={e=>draft && edit({...draft,purposeSlots:draft.purposeSlots?.map(s=>s.slotId===slot.slotId ? {...s,enabled:e.target.checked} : s)})}/>Enable {purpose} factual slot</label>
           <label>{purpose} local time<input required type="time" value={slot.localTime} onChange={e=>draft && edit({...draft,purposeSlots:draft.purposeSlots?.map(s=>s.slotId===slot.slotId ? {...s,localTime:e.target.value} : s)})}/></label>
           <label>{purpose} preview preference<select aria-label={`${purpose} preview preference`} value={slot.providerSelection ? `${slot.providerSelection.providerKind}-preview` : 'factual-only'} onChange={e=>draft && edit({...draft,purposeSlots:draft.purposeSlots?.map(s=>s.slotId===slot.slotId ? {...s,providerSelection:e.target.value==='factual-only'?null:{providerKind:e.target.value==='hosted-preview'?'hosted':'local',providerId:s.providerSelection?.providerId??'',modelId:s.providerSelection?.modelId??''}} : s)})}><option value="factual-only">Factual only</option><option value="local-preview">Local preview preference</option><option value="hosted-preview">Hosted preview preference</option></select></label>
           {slot.providerSelection && <><label>{purpose} provider ID<input maxLength={64} value={slot.providerSelection.providerId} onChange={e=>draft && edit({...draft,purposeSlots:draft.purposeSlots?.map(s=>s.slotId===slot.slotId ? {...s,providerSelection:{...slot.providerSelection!,providerId:e.target.value}} : s)})}/></label><label>{purpose} model ID<input maxLength={128} value={slot.providerSelection.modelId} onChange={e=>draft && edit({...draft,purposeSlots:draft.purposeSlots?.map(s=>s.slotId===slot.slotId ? {...s,providerSelection:{...slot.providerSelection!,modelId:e.target.value}} : s)})}/></label></>}
          </>}
        </section>
      })}
      <p>Morning includes separately dated latest available wellness and today's existing plan/constraints. Evening is day so far at its actual preparation cutoff; missing imports never prove skipped training. No automatic amendment is applied.</p>
      <button type="button" disabled={!canSave || !draft?.purposeSlots?.some(s=>s.enabled) || (!canArm && !!draft?.presets.some(p=>p.enabled))}
        onClick={()=>draft && saveConfiguration({...draft,enabled:true,paused:false},draftVersion)}>Save and explicitly re-arm daily slots</button>
      <button type="button" disabled={!schedule || schedule.configuration.paused} onClick={()=>schedule && saveConfiguration({...schedule.configuration,paused:true},schedule.configurationVersion,true)}>Pause daily slots and reviews</button>
       <p>Pause or restart removes execution authority. Re-arm explicitly; missed days and already consumed local dates are not replayed. Queued/running means local preparation only, not inference. No source sync, fallback, or replay. Unexecuted automatic preview copies may expire with evicted outcomes; original data, manual/executed previews, and unresolved ownership are protected. DST gaps move forward and overlaps use the earlier offset once.</p>
      {schedule?.purposeSlots?.map(s=><p key={s.slotId}>{s.purpose}: {s.state}; next slot {s.nextUtc ? local(s.nextUtc,zone) : 'not armed'}. Preparation is not guaranteed source freshness.</p>)}
    </fieldset>}
    {evidence && <ScopedEvidenceInspection snapshot={evidence} onSnapshot={setEvidence} onRemoved={()=>setEvidence(null)}/>}
    {executionPreview && queue?.outcomes.some(o=>!o.stale && o.preview?.executionId===executionPreview.id && ['awaiting_consent','blocked_admission'].includes(o.preview.state)) && <article aria-label="Read-only execution preview"><h4>Exact preview inspection — read only</h4><p>Execution state: {String(executionPreview.data.state)} means a prepared binding, not running inference. Admission and consent remain independent; inspection is not authorization to execute.</p><p>Exact packet SHA-256: {object(executionPreview.data.binding) ? String(executionPreview.data.binding.consentSha256) : ''}</p><pre>{JSON.stringify({ exactPrompt: executionPreview.data.binding && object(executionPreview.data.binding) ? executionPreview.data.binding.exactPrompt : null, exactCorrectionPrompt: executionPreview.data.binding && object(executionPreview.data.binding) ? executionPreview.data.binding.exactCorrectionPrompt : null }, null, 2)}</pre></article>}
    {view === 'model' && <div className="review-tile"><fieldset disabled={!models || busy}><legend>Explicit local model consent</legend>
      <label>Review local model<select value={modelDraft?.modelId ?? ''} onChange={event => modelDraft && editModel({ ...modelDraft, modelId: event.target.value || null, enabled: false })}>
        <option value="">Off — no model selected</option>{models?.models.map(m => <option key={m.id} value={m.id}>{m.label} (experimental)</option>)}
      </select></label>
      <p>Changing models turns the draft enable choice off. Selection alone does not enable or generate anything.</p>
      <label><input type="checkbox" checked={modelDraft?.enabled ?? false} disabled={!models?.runtimeGuardConfigured || !modelDraft?.modelId}
        onChange={event => modelDraft && editModel({ ...modelDraft, enabled: event.target.checked })} />Enable optional local AI interpretation</label>
      <label><input type="checkbox" checked={modelDraft?.allowCorrectiveAttempt ?? false} disabled={!models?.executionPolicy.allowCorrectiveAttempt}
        onChange={event => modelDraft && editModel({ ...modelDraft, allowCorrectiveAttempt: event.target.checked })} />Allow one corrective attempt (operator permission required)</label>
      <button type="button" disabled={!modelDirty || !modelDraft || (modelDraft.enabled && (!modelDraft.modelId || !models?.runtimeGuardConfigured))}
        onClick={() => modelDraft && saveModel(modelDraft)}>Save model choices</button>
      <button type="button" className="secondary" disabled={!models?.enabled} onClick={() => models && saveModel({ modelId: models.selectedModelId, enabled: false, allowCorrectiveAttempt: false })}>Disable optional AI now</button>
    </fieldset>
    {models && <><p>{reason(models.reason)}</p><p>Operator caps: call {models.executionPolicy.callTimeoutMillis} ms; job {models.executionPolicy.jobTimeoutMillis} ms;
      corrective attempt {models.executionPolicy.allowCorrectiveAttempt ? 'permitted only by explicit choice' : 'not permitted'}.</p></>}
    {models && !models.runtimeGuardConfigured && <p>{reason('runtime_guard_unavailable')}</p>}
    </div>}
    {view === 'preset' && <div className="review-tile">
    <Tabs label="Preset editor sections" value={editor} options={{ basic: 'Preset and scope', schedule: 'Consent and time zone', budgets: 'Level and budgets', triggers: 'Trigger editor', policy: 'Queue policy' }} onChange={setEditor} />
    <fieldset disabled={!draft || !schedule || busy}><legend>Schedule configuration — explicit save only</legend>
      <label>Review preset<select value={preset ? String(selected) : ''} onChange={event => { setSelected(Number(event.target.value)); setTriggerIndex(0) }}>
        {draft?.presets.map((p, index) => <option key={index} value={index}>{p.id} — {names[p.reviewType]}</option>)}
      </select></label>
      {editor === 'schedule' && <>
      <label><input type="checkbox" checked={draft?.enabled ?? false} disabled={!canArm} onChange={event => draft && edit({ ...draft, enabled: event.target.checked })} />Enable scheduled reviews</label>
      <label><input type="checkbox" checked={draft?.paused ?? false} onChange={event => draft && edit({ ...draft, paused: event.target.checked })} />Pause all reviews</label>
      <label>Schedule time zone<input type="text" maxLength={80} value={draft?.timeZone ?? ''} placeholder="UTC or Europe/Prague" onChange={event => draft && edit({ ...draft, timeZone: event.target.value })} /></label>
      <p>Times use this saved zone, never your browser travel zone. Day/week scopes assign 1/7 dates; rolling scopes accept 1–90 dates. Source dates are not inferred physiology.</p>
      </>}
       {editor === 'basic' && <>
       <Tabs label="Preset and scope pages" value={basicPage} options={{ identity: 'Preset identity', scope: 'Coverage and focus' }} onChange={setBasicPage} />
        {preset && <div className="preset-basic-fields">
        {basicPage === 'identity' && <>
         <label>Preset ID<input value={preset.id} maxLength={40} onChange={event => editPreset({ ...preset, id: event.target.value })} /></label>
        <Choices label="Review type" value={preset.reviewType} options={types} onChange={reviewType => editPreset({ ...preset, reviewType })} />
         <label><input type="checkbox" checked={preset.enabled} onChange={event => editPreset({ ...preset, enabled: event.target.checked })} />Enable this preset</label>
        </>}
        {basicPage === 'scope' && <>
        <Choices label="Scope" value={preset.scope.kind} options={['day', 'week', 'rolling', 'activity']} onChange={kind => editPreset({ ...preset, scope: { ...preset.scope, kind, days: kind === 'week' ? 7 : 1 } })} />
        {preset.scope.kind === 'rolling' && <NumberField label="Rolling dates" value={preset.scope.days} min={1} max={90} onChange={days => editPreset({ ...preset, scope: { ...preset.scope, days } })} />}
        <label>Sport filter (blank means all sports)<input maxLength={64} value={preset.scope.sport ?? ''} onChange={event => editPreset({ ...preset, scope: { ...preset.scope, sport: event.target.value || null } })} /></label>
         <Choices label="Review focus" value={preset.focus} options={focuses} onChange={focus => editPreset({ ...preset, focus })} />
        </>}
       </div>}
      </>}
      {preset && editor === 'budgets' && <>
          <h4>Advanced review level and execution budgets</h4>
          <Choices label="Scheduled review level" value={preset.level} options={levels} onChange={level => editPreset({ ...preset, level })} />
          <NumberField label="Call timeout (milliseconds)" value={preset.budget.callTimeoutMillis} min={1} max={models?.executionPolicy.callTimeoutMillis ?? 240_000} onChange={callTimeoutMillis => editPreset({ ...preset, budget: { ...preset.budget, callTimeoutMillis } })} />
          <NumberField label="Job timeout (milliseconds)" value={preset.budget.jobTimeoutMillis} min={preset.budget.callTimeoutMillis} max={models?.executionPolicy.jobTimeoutMillis ?? 600_000} onChange={jobTimeoutMillis => editPreset({ ...preset, budget: { ...preset.budget, jobTimeoutMillis } })} />
          <label><input type="checkbox" checked={preset.budget.allowCorrectiveAttempt} disabled={!models?.executionPolicy.allowCorrectiveAttempt} onChange={event => editPreset({ ...preset, budget: { ...preset.budget, allowCorrectiveAttempt: event.target.checked } })} />Permit preset corrective attempt (also requires model consent)</label>
      </>}
      {preset && editor === 'triggers' && <>
        <h4>Typed triggers</h4><p>At most eight per preset. Activity scope requires only activity-arrival rules. Daily steps require a verified upstream source; unsupported steps never become zero or guessed data.</p>
        <Pager label="trigger" index={triggerPage} count={preset.triggers.length} onChange={setTriggerIndex} />
        {preset.triggers.slice(triggerPage, triggerPage + 1).map((trigger) => {
          const index = triggerPage
          const update = (value: Trigger) => editPreset({ ...preset, triggers: preset.triggers.map((t, i) => i === index ? value : t) })
          return <fieldset key={index}><legend>Trigger {index + 1}</legend>
            <label>Trigger {index + 1} ID<input maxLength={40} value={trigger.id} onChange={event => update({ ...trigger, id: event.target.value })} /></label>
            <Choices label={`Trigger ${index + 1} type`} value={trigger.kind} options={kinds} onChange={kind => update({ ...newTrigger(trigger.id, kind), intervalMinutes: trigger.intervalMinutes, settleSeconds: trigger.settleSeconds })} />
            {['daily_time', 'weekly_time'].includes(trigger.kind) && <label>Trigger {index + 1} local time<input type="time" value={trigger.localTime ?? ''} onChange={event => update({ ...trigger, localTime: event.target.value })} /></label>}
            {trigger.kind === 'weekly_time' && <label>Trigger {index + 1} weekday<select value={trigger.dayOfWeek ?? ''} onChange={event => update({ ...trigger, dayOfWeek: numeric(event.target.value) })}>
              {['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'].map((day, i) => <option key={day} value={i + 1}>{day}</option>)}
            </select></label>}
            {['record_count', 'daily_steps'].includes(trigger.kind) && <NumberField label={`Trigger ${index + 1} threshold`} value={trigger.threshold ?? Number.NaN} min={1} max={1_000_000} onChange={threshold => update({ ...trigger, threshold })} />}
            {trigger.kind === 'record_count' && <Choices label={`Trigger ${index + 1} count period`} value={trigger.period} options={['day', 'week']} onChange={period => update({ ...trigger, period })} />}
            {trigger.kind === 'changed_data' && <NumberField label={`Trigger ${index + 1} check interval (minutes)`} value={trigger.intervalMinutes} min={1} max={10080} onChange={intervalMinutes => update({ ...trigger, intervalMinutes })} />}
            {['activity_arrival', 'wellness_arrival', 'sleep_arrival'].includes(trigger.kind) && <NumberField label={`Trigger ${index + 1} settle delay (seconds)`} value={trigger.settleSeconds} min={0} max={3600} onChange={settleSeconds => update({ ...trigger, settleSeconds })} />}
            <button type="button" className="secondary" onClick={() => editPreset({ ...preset, triggers: preset.triggers.filter((_, i) => i !== index) })}>Remove trigger {index + 1}</button>
          </fieldset>
        })}
        <button type="button" className="secondary" disabled={preset.triggers.length >= 8} onClick={() => {
          let n = 1; while (preset.triggers.some(t => t.id === `trigger-${n}`)) n++
          editPreset({ ...preset, triggers: [...preset.triggers, newTrigger(`trigger-${n}`, preset.scope.kind === 'activity' ? 'activity_arrival' : 'changed_data')] })
          setTriggerIndex(preset.triggers.length)
        }}>Add trigger</button>
      </>}
       {editor === 'basic' && basicPage === 'identity' && <>
        <button type="button" className="secondary" disabled={(draft?.presets.length ?? 0) <= 1} onClick={() => {
          if (draft) { edit({ ...draft, presets: draft.presets.filter((_, i) => i !== selected) }); setSelected(0) }
        }}>Remove preset</button>
      <button type="button" className="secondary" disabled={(draft?.presets.length ?? 0) >= 12} onClick={() => {
        if (!draft) return
        let n = 1; while (draft.presets.some(p => p.id === `preset-${n}`)) n++
        edit({ ...draft, presets: [...draft.presets, { id: `preset-${n}`, reviewType: 'daily_combined', enabled: false, scope: { kind: 'day', days: 1, sport: null }, level: 'interim', focus: 'daily_combined', budget: budgetDefault(), triggers: [] }] }); setSelected(draft.presets.length)
      }}>Add preset</button>
      </>}
      {draft && editor === 'policy' && <>
        <h4>Advanced queue policy</h4>
        <NumberField label="Debounce (seconds)" value={draft.queuePolicy.debounceSeconds} min={0} max={3600} onChange={debounceSeconds => edit({ ...draft, queuePolicy: { ...draft.queuePolicy, debounceSeconds } })} />
        <NumberField label="Cooldown (seconds)" value={draft.queuePolicy.cooldownSeconds} min={0} max={86400} onChange={cooldownSeconds => edit({ ...draft, queuePolicy: { ...draft.queuePolicy, cooldownSeconds } })} />
        <NumberField label="Maximum deferral (seconds)" value={draft.queuePolicy.maximumDeferralSeconds} min={1} max={86400} onChange={maximumDeferralSeconds => edit({ ...draft, queuePolicy: { ...draft.queuePolicy, maximumDeferralSeconds } })} />
        <p>Debounce cannot exceed maximum deferral. Defaults: 120 / 300 / 600 seconds. Eligibility is not a guaranteed start.</p>
      </>}
      {dirty && <p role="status">Unsaved schedule edits.{!validDraft && ' Invalid values or operator-budget limit exceeded; nothing will be saved. No clamping or automatic repair.'}
        {schedule && draftVersion !== schedule.configurationVersion && ' Configuration changed remotely; saving uses the original version and may conflict.'}</p>}
      <button type="button" disabled={!dirty || !canSave} onClick={() => draft && saveConfiguration(draft, draftVersion)}>Save schedule changes</button>
      {editor === 'schedule' && <>
      <p>Immediate controls explicitly save only the on/pause flags; other unsaved schedule edits are retained.</p>
      <button type="button" className="secondary" disabled={!schedule?.configuration.enabled} onClick={() => schedule && saveConfiguration({ ...schedule.configuration, enabled: false }, schedule.configurationVersion, true)}>Disable schedules now</button>
      <button type="button" className="secondary" disabled={!schedule || schedule.configuration.paused} onClick={() => schedule && saveConfiguration({ ...schedule.configuration, paused: true }, schedule.configurationVersion, true)}>Pause reviews now</button>
      </>}
    </fieldset>
    </div>}
    {view === 'overview' && <div className="review-tile">
    <Tabs label="Review overview sections" value={overview} options={{ request: 'Request now', queue: 'Queue status', timing: 'Schedule timing', coverage: 'Completed coverage' }} onChange={setOverview} />
    {overview === 'request' && <>
    <h4>Immediate review request</h4>
    <Choices label="Immediate review level" value={runLevel} options={levels} onChange={setRunLevel} />
    <button type="button" disabled={!canRun} onClick={() => schedule && savedPreset && void write('/api/review-now', 'POST',
      { expectedVersion: schedule.configurationVersion, presetId: savedPreset.id, level: runLevel }, data => setQueue(queueFrom(data)))}>Run review now — enqueue</button>
    <p>Immediate interim requests do not wait for an evening rule and do not suppress later scheduled thorough reviews. Enqueue only, not a synchronous model call.</p>
    {preset?.scope.kind === 'activity' && <p>Activity-scope review needs a received activity identity; Run now is disabled.</p>}
    <label>Immediate review preset<select value={preset ? String(selected) : ''} onChange={event => { setSelected(Number(event.target.value)); setTriggerIndex(0) }} disabled={!schedule || dirty}>
      {draft?.presets.map((p, index) => <option key={index} value={index}>{p.id} — {names[p.reviewType]}</option>)}
    </select></label>
    {models && !models.runtimeGuardConfigured && <p>{reason('runtime_guard_unavailable')}</p>}
    {queue && <p>{reason(queue.reason)}</p>}
    </>}
    {overview === 'queue' && <>
    <h4>Queue metadata — no stored AI prose</h4>
    {queue ? <><p>{reason(queue.reason)}</p><p>Pending: {queue.pending.length}. Running: {queue.running ? '1' : 'none'}.</p>
      <Pager label="queued job" index={jobPage} count={jobs.length} onChange={setJobIndex} />
      {jobs[jobPage] && <ul>{renderJob(jobs[jobPage], jobs[jobPage].id === queue.running?.id)}</ul>}
    </> : <p>Queue status unavailable. Factual charts remain independent.</p>}
    </>}
    {overview === 'timing' && <>
    <h4>Schedule status</h4>
    {schedule ? <><p>{reason(schedule.reason)} Saved time zone: {schedule.configuration.timeZone}.</p>
      <Pager label="trigger status" index={statusPage} count={schedule.triggers.length} onChange={setStatusIndex} />
      <ul>{schedule.triggers.slice(statusPage, statusPage + 1).map(t => <li key={`${t.presetId}/${t.triggerId}`}>
      <p>{t.presetId} / {t.triggerId}: {names[t.kind] ?? 'Unsupported rule'}. {reason(t.reason)}</p>
      {kinds.includes(t.kind) && ['waiting_for_trigger', 'review_due'].includes(t.reason) && t.kind === 'changed_data' && t.nextCheckUtc && <p>Next check: {local(t.nextCheckUtc, zone)} — conditional on changed data, not a scheduled review.</p>}
      {kinds.includes(t.kind) && ['waiting_for_trigger', 'review_due'].includes(t.reason) && t.kind !== 'changed_data' && t.nextReviewUtc && <p>Next review eligible: {local(t.nextReviewUtc, zone)} — not a guaranteed start.</p>}
      {(!kinds.includes(t.kind) || !['waiting_for_trigger', 'review_due'].includes(t.reason) || !(t.kind === 'changed_data' ? t.nextCheckUtc : t.nextReviewUtc)) && <p>No known next review time; none is fabricated.</p>}
    </li>)}</ul></> : <p>Schedule status unavailable. No next review time is inferred.</p>}
    </>}
    {overview === 'coverage' && <>
      <h4>Completed coverage metadata — no stored AI prose</h4>
      <Pager label="completed review" index={outcomePage} count={outcomes.length} onChange={setOutcomeIndex} />
      {outcomes[outcomePage] ? renderOutcome(outcomes[outcomePage], outcomes[outcomePage].id === queue?.lastSuccess?.id) : <p>No last successful review recorded.</p>}
    </>}
    </div>}
    {(dirty || modelDirty) && <p>Save or discard edits before requesting a review of the saved preset.</p>}
    {(dirty || modelDirty) && <button type="button" className="secondary" disabled={busy} onClick={() => {
      dirtyRef.current = false; modelDirtyRef.current = false; setDirty(false); setModelDirty(false)
      if (schedule) acceptSchedule(schedule)
      if (models) acceptModel(models)
      setSelected(0)
    }}>Discard unsaved review edits</button>}
  </section>
}
