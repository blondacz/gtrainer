export const topics = ['restriction', 'symptom', 'goal', 'preference', 'feedback', 'note'] as const
export const sources = ['user_report', 'clinician_guidance', 'coach_guidance', 'review_feedback'] as const
const restrictionKinds = ['blocked_activity', 'blocked_sport', 'allowed_activity', 'allowed_sport', 'maximum_duration'] as const
export type Topic = typeof topics[number]
export type Source = typeof sources[number]
type RestrictionKind = typeof restrictionKinds[number]
export type ContextDraft = { category: Topic; sourceCategory: Source; authorAttribution: string; observedOn: string;
  applicableFrom: string | null; applicableUntil: string | null; sport: string | null; activityId: string | null;
  reviewId: string | null; content: string; restrictionKind: RestrictionKind | null; restrictionValue: string | null; restrictionUnit: string | null }
export type ContextRecord = ContextDraft & { contextId: string; revision: number; enteredBy: string; retired: boolean }
export const today = () => new Date().toISOString().slice(0, 10)
export const blankDraft = (): ContextDraft => ({ category: 'note', sourceCategory: 'user_report', authorAttribution: 'athlete',
  observedOn: today(), applicableFrom: null, applicableUntil: null, sport: null, activityId: null, reviewId: null,
  content: '', restrictionKind: null, restrictionValue: null, restrictionUnit: null })
export const sourceLabel: Record<Source, string> = { user_report: 'User report', clinician_guidance: 'Clinician guidance, as entered by user',
  coach_guidance: 'Coach guidance, as entered by user', review_feedback: 'Review feedback' }
export const topicLabel = (topic: Topic) => topic[0].toUpperCase() + topic.slice(1)
export const realDate = (value: string | null) => value === null || (/^\d{4}-\d{2}-\d{2}$/.test(value) &&
  Number.isFinite(Date.parse(`${value}T00:00:00Z`)) && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value)
export function recordFrom(value: unknown): ContextRecord {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid context record')
  const item = value as Record<string, unknown>
  if (typeof item.contextId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(item.contextId) ||
    !Number.isInteger(item.revision) || Number(item.revision)<1 || Number(item.revision)>1000 ||
    !topics.includes(item.category as Topic) || !sources.includes(item.sourceCategory as Source) || typeof item.content !== 'string' ||
    typeof item.authorAttribution !== 'string' || typeof item.enteredBy !== 'string' || typeof item.retired !== 'boolean' ||
    typeof item.observedOn !== 'string' || !realDate(item.observedOn) ||
    !['applicableFrom','applicableUntil'].every(key => item[key]===null || typeof item[key]==='string' && realDate(item[key] as string)) ||
    !['sport','activityId','reviewId','restrictionValue','restrictionUnit'].every(key => item[key]===null || typeof item[key]==='string') ||
    !(item.restrictionKind===null || restrictionKinds.includes(item.restrictionKind as RestrictionKind))) throw new Error('Invalid context record')
  return item as ContextRecord
}
export function validateDraft(draft: ContextDraft) {
  if (!draft.authorAttribution.trim() || draft.authorAttribution.length > 160) return 'Add an author/source attribution (up to 160 characters).'
  if (!draft.content.trim() || draft.content.length > 4000) return 'Enter context text (up to 4000 characters).'
  if (!realDate(draft.observedOn) || !realDate(draft.applicableFrom) || !realDate(draft.applicableUntil)) return 'Enter real calendar dates.'
  if (draft.applicableFrom && draft.applicableUntil && draft.applicableUntil < draft.applicableFrom) return 'The end date must be on or after the start date.'
  if (draft.category === 'restriction') {
    if (!draft.restrictionKind || !draft.restrictionValue?.trim()) return 'Structured restrictions need a kind and value.'
    if (draft.restrictionKind === 'maximum_duration' && (!Number.isFinite(Number(draft.restrictionValue)) ||
      Number(draft.restrictionValue) <= 0 || draft.restrictionUnit !== 'minutes')) return 'Maximum duration must be a positive number of minutes.'
  }
  return ''
}

/** One field editor for direct legacy edits and confirmed intake drafts. No persistence or inference. */
export function ContextFields({ value, onChange, disabled, attribution = true }: {
  value: ContextDraft; onChange: (value: ContextDraft) => void; disabled: boolean; attribution?: boolean
}) {
  const update = <K extends keyof ContextDraft>(key: K, next: ContextDraft[K]) => onChange({ ...value, [key]: next })
  return <>
    <label>Topic<select value={value.category} disabled={disabled} onChange={e => onChange({ ...value, category: e.target.value as Topic,
      ...(e.target.value === 'restriction' ? {} : { restrictionKind: null, restrictionValue: null, restrictionUnit: null }) })}>
      {topics.map(topic => <option key={topic} value={topic}>{topicLabel(topic)}</option>)}</select></label>
    {attribution && <>
      <label>Source attribution<select value={value.sourceCategory} disabled={disabled} onChange={e => update('sourceCategory', e.target.value as Source)}>
        {sources.map(source => <option key={source} value={source}>{sourceLabel[source]}</option>)}</select></label>
      <label>Author/source details<input maxLength={160} value={value.authorAttribution} disabled={disabled} onChange={e => update('authorAttribution', e.target.value)} /></label>
    </>}
    <label>Observed on<input type="date" required value={value.observedOn} disabled={disabled} onChange={e => update('observedOn', e.target.value)} /></label>
    <label>Applicable from (optional)<input type="date" value={value.applicableFrom ?? ''} disabled={disabled} onChange={e => update('applicableFrom', e.target.value || null)} /></label>
    <label>Applicable until (optional)<input type="date" value={value.applicableUntil ?? ''} disabled={disabled} onChange={e => update('applicableUntil', e.target.value || null)} /></label>
    <label>Sport (optional)<input maxLength={80} value={value.sport ?? ''} disabled={disabled} onChange={e => update('sport', e.target.value || null)} /></label>
    <label>Activity ID (optional)<input maxLength={128} value={value.activityId ?? ''} disabled={disabled} onChange={e => update('activityId', e.target.value || null)} /></label>
    <label>Review ID (optional)<input maxLength={128} value={value.reviewId ?? ''} disabled={disabled} onChange={e => update('reviewId', e.target.value || null)} /></label>
    {value.category === 'restriction' && <fieldset><legend>Structured restriction</legend>
      <label>Restriction kind<select required value={value.restrictionKind ?? ''} disabled={disabled} onChange={e => onChange({ ...value,
        restrictionKind: (e.target.value || null) as RestrictionKind | null, restrictionUnit: e.target.value === 'maximum_duration' ? 'minutes' : null })}>
        <option value="">Select a kind</option>{restrictionKinds.map(kind => <option key={kind} value={kind}>{kind.replaceAll('_', ' ')}</option>)}</select></label>
      <label>{value.restrictionKind === 'maximum_duration' ? 'Maximum duration (minutes)' : 'Activity or sport'}<input required maxLength={80}
        value={value.restrictionValue ?? ''} disabled={disabled} onChange={e => update('restrictionValue', e.target.value || null)} /></label>
    </fieldset>}
    <label>Context text<textarea required maxLength={4000} rows={4} value={value.content} disabled={disabled} onChange={e => update('content', e.target.value)} /></label>
  </>
}
