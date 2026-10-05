import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'

const topics = ['restriction', 'symptom', 'goal', 'preference', 'feedback', 'note'] as const
const sources = ['user_report', 'clinician_guidance', 'coach_guidance', 'review_feedback'] as const
const restrictionKinds = ['blocked_activity', 'blocked_sport', 'allowed_activity', 'allowed_sport', 'maximum_duration'] as const

type Topic = typeof topics[number]
type Source = typeof sources[number]
type RestrictionKind = typeof restrictionKinds[number]
type AthleteContext = {
  contextId: string
  revision: number
  category: Topic
  sourceCategory: Source
  authorAttribution: string
  enteredBy: string
  observedOn: string
  applicableFrom: string | null
  applicableUntil: string | null
  sport: string | null
  activityId: string | null
  reviewId: string | null
  content: string
  retired: boolean
  restrictionKind: RestrictionKind | null
  restrictionValue: string | null
  restrictionUnit: string | null
}
type Draft = Omit<AthleteContext, 'contextId' | 'revision' | 'enteredBy' | 'retired'>

const today = () => new Date().toISOString().slice(0, 10)
const blankDraft = (): Draft => ({ category: 'note', sourceCategory: 'user_report', authorAttribution: 'athlete',
  observedOn: today(), applicableFrom: null, applicableUntil: null, sport: null, activityId: null, reviewId: null,
  content: '', restrictionKind: null, restrictionValue: null, restrictionUnit: null })
const sourceLabel: Record<Source, string> = {
  user_report: 'User report', clinician_guidance: 'Clinician guidance, as entered by user',
  coach_guidance: 'Coach guidance, as entered by user', review_feedback: 'Review feedback',
}
const topicLabel = (topic: Topic) => topic[0].toUpperCase() + topic.slice(1)
const recordFrom = (value: unknown): AthleteContext => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid context record')
  const item = value as Record<string, unknown>
  if (typeof item.contextId !== 'string' || !/^[0-9a-f-]{36}$/.test(item.contextId) ||
      !Number.isInteger(item.revision) || !topics.includes(item.category as Topic) ||
      !sources.includes(item.sourceCategory as Source) || typeof item.content !== 'string' ||
      typeof item.observedOn !== 'string') throw new Error('Invalid context record')
  return item as AthleteContext
}

function validateDraft(draft: Draft) {
  const date = (value: string | null) => value === null ||
    (/^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(`${value}T00:00:00Z`)) &&
      new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value)
  if (!draft.authorAttribution.trim() || draft.authorAttribution.length > 160) return 'Add an author/source attribution (up to 160 characters).'
  if (!draft.content.trim() || draft.content.length > 4000) return 'Enter context text (up to 4000 characters).'
  if (!date(draft.observedOn) || !date(draft.applicableFrom) || !date(draft.applicableUntil)) return 'Enter real calendar dates.'
  if (draft.applicableFrom && draft.applicableUntil && draft.applicableUntil < draft.applicableFrom) return 'The end date must be on or after the start date.'
  if (draft.category === 'restriction') {
    if (!draft.restrictionKind || !draft.restrictionValue?.trim()) return 'Structured restrictions need a kind and value.'
    if (draft.restrictionKind === 'maximum_duration') {
      if (!Number.isFinite(Number(draft.restrictionValue)) || Number(draft.restrictionValue) <= 0 || draft.restrictionUnit !== 'minutes')
        return 'Maximum duration must be a positive number of minutes.'
    } else if (draft.restrictionUnit !== null) return 'Activity restrictions do not use a unit.'
  }
  return ''
}

/** Authenticated, attributed context lifecycle UI; corrections create new revisions rather than overwriting. */
export function AthleteContextPanel({ csrfToken }: { csrfToken: string }) {
  const [records, setRecords] = useState<AthleteContext[]>([])
  const [draft, setDraft] = useState<Draft>(blankDraft)
  const [editing, setEditing] = useState<string | null>(null)
  const [history, setHistory] = useState<Record<string, AthleteContext[]>>({})
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null)
  const [feedbackSnapshotId, setFeedbackSnapshotId] = useState('')
  const [feedbackRating, setFeedbackRating] = useState('')
  const [feedbackCorrection, setFeedbackCorrection] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function load(signal?: AbortSignal) {
    setError('')
    try {
      const response = await fetch('/api/contexts', { cache: 'no-store', credentials: 'same-origin', signal })
      if (response.status === 401) { setRecords([]); setError('Sign in again to manage private context.'); return }
      if (!response.ok) throw new Error()
      const body: unknown = await response.json()
      if (!Array.isArray(body)) throw new Error()
      setRecords(body.map(recordFrom))
    } catch {
      if (!signal?.aborted) setError('Context is unavailable. Reload to try again.')
    }
  }

  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [])

  function resetForm() {
    setDraft(blankDraft())
    setEditing(null)
    setError('')
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    const validation = validateDraft(draft)
    if (validation) { setError(validation); return }
    setBusy(true); setError(''); setMessage('')
    try {
      const response = await fetch(editing ? `/api/contexts/${editing}` : '/api/contexts', {
        method: editing ? 'PUT' : 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken }, body: JSON.stringify(draft),
      })
      if (response.status === 401) { setRecords([]); throw new Error('Sign in again to manage private context.') }
      if (!response.ok) throw new Error('Could not save context. Check the fields and retry.')
      const saved = recordFrom(await response.json())
      await load()
      setMessage(editing ? `Correction saved as revision ${saved.revision}.` : 'Context saved.')
      resetForm()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not save context.')
    } finally { setBusy(false) }
  }

  function beginCorrection(item: AthleteContext) {
    setEditing(item.contextId)
    setDraft({ category: item.category, sourceCategory: item.sourceCategory, authorAttribution: item.authorAttribution,
      observedOn: item.observedOn, applicableFrom: item.applicableFrom, applicableUntil: item.applicableUntil,
      sport: item.sport, activityId: item.activityId, reviewId: item.reviewId, content: item.content,
      restrictionKind: item.restrictionKind, restrictionValue: item.restrictionValue, restrictionUnit: item.restrictionUnit })
    setMessage(''); setError('')
  }

  async function retire(item: AthleteContext) {
    setBusy(true); setError(''); setMessage('')
    try {
      const response = await fetch(`/api/contexts/${item.contextId}/retire`, {
        method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-Token': csrfToken }, cache: 'no-store',
      })
      if (!response.ok) throw new Error('Could not retire context. Reload and try again.')
      await load()
      setMessage('Context retired and removed from current retrieval.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not retire context.') }
    finally { setBusy(false) }
  }

  async function remove(item: AthleteContext) {
    setBusy(true); setError(''); setMessage('')
    try {
      const response = await fetch(`/api/contexts/${item.contextId}`, {
        method: 'DELETE', credentials: 'same-origin', headers: { 'X-CSRF-Token': csrfToken }, cache: 'no-store',
      })
      if (!response.ok && response.status !== 404) throw new Error('Could not delete context. Reload and try again.')
      await load()
      setHistory(({ [item.contextId]: _removed, ...remaining }) => remaining)
      setConfirmDelete(null)
      setMessage('Context and dependent saved review content were deleted from active storage. Retained encrypted backups may expire later under normal retention.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not delete context.') }
    finally { setBusy(false) }
  }

  async function toggleHistory(item: AthleteContext) {
    if (history[item.contextId]) {
      setHistory(({ [item.contextId]: _removed, ...remaining }) => remaining)
      return
    }
    try {
      const response = await fetch(`/api/contexts/${item.contextId}/history`, { cache: 'no-store', credentials: 'same-origin' })
      if (!response.ok) throw new Error()
      const body: unknown = await response.json()
      if (!Array.isArray(body)) throw new Error()
      setHistory(current => ({ ...current, [item.contextId]: body.map(recordFrom) }))
    } catch { setError('Context revision history is unavailable.') }
  }

  async function submitFeedback(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(feedbackSnapshotId)) {
      setError('Enter the connected review snapshot ID shown with the review.')
      return
    }
    if (!feedbackRating && !feedbackCorrection.trim()) {
      setError('Choose a usefulness rating or enter a correction.')
      return
    }
    if (feedbackCorrection.length > 4000) { setError('Correction must be 4000 characters or fewer.'); return }
    setBusy(true); setError(''); setMessage('')
    try {
      const response = await fetch(`/api/connected-reviews/${feedbackSnapshotId}/feedback`, {
        method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
        body: JSON.stringify({ rating: feedbackRating || null, correction: feedbackCorrection.trim() || null }),
      })
      if (response.status === 401) { setRecords([]); throw new Error('Sign in again to record review feedback.') }
      if (!response.ok) throw new Error('Could not save feedback. Confirm the snapshot ID and try again.')
      setFeedbackRating(''); setFeedbackCorrection('')
      setMessage('Feedback saved as attributed user context linked to this immutable review. It is not a measurement or model training.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not save feedback.') }
    finally { setBusy(false) }
  }

  const update = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft(current => ({ ...current, [key]: value }))
  return <section aria-labelledby="context-heading">
    <h2 id="context-heading">Athlete context</h2>
    <p>Context is user-entered information, not independently verified. Measurements remain imported evidence.</p>
    <form className="context-form" onSubmit={event => void save(event)}>
      <h3>{editing ? 'Correct context' : 'Add context'}</h3>
      <label>Topic
        <select value={draft.category} disabled={busy} onChange={event => {
          const category = event.target.value as Topic
          setDraft(current => ({ ...current, category, ...(category === 'restriction' ? {} :
            { restrictionKind: null, restrictionValue: null, restrictionUnit: null }) }))
        }}>{topics.map(topic => <option key={topic} value={topic}>{topicLabel(topic)}</option>)}</select>
      </label>
      <label>Source attribution
        <select value={draft.sourceCategory} disabled={busy} onChange={event => update('sourceCategory', event.target.value as Source)}>
          {sources.map(source => <option key={source} value={source}>{sourceLabel[source]}</option>)}
        </select>
      </label>
      <label>Author/source details<input maxLength={160} value={draft.authorAttribution} disabled={busy}
        onChange={event => update('authorAttribution', event.target.value)} /></label>
      <label>Observed on<input type="date" required value={draft.observedOn} disabled={busy}
        onChange={event => update('observedOn', event.target.value)} /></label>
      <label>Applicable from (optional)<input type="date" value={draft.applicableFrom ?? ''} disabled={busy}
        onChange={event => update('applicableFrom', event.target.value || null)} /></label>
      <label>Applicable until (optional)<input type="date" value={draft.applicableUntil ?? ''} disabled={busy}
        onChange={event => update('applicableUntil', event.target.value || null)} /></label>
      <label>Sport (optional)<input maxLength={80} value={draft.sport ?? ''} disabled={busy}
        onChange={event => update('sport', event.target.value || null)} /></label>
      <label>Activity ID (optional)<input maxLength={128} value={draft.activityId ?? ''} disabled={busy}
        onChange={event => update('activityId', event.target.value || null)} /></label>
      <label>Review ID (optional)<input maxLength={128} value={draft.reviewId ?? ''} disabled={busy}
        onChange={event => update('reviewId', event.target.value || null)} /></label>
      {draft.category === 'restriction' && <fieldset>
        <legend>Structured restriction</legend>
        <label>Restriction kind<select required value={draft.restrictionKind ?? ''} disabled={busy}
          onChange={event => {
            const restrictionKind = (event.target.value || null) as RestrictionKind | null
            setDraft(current => ({ ...current, restrictionKind, restrictionUnit: restrictionKind === 'maximum_duration' ? 'minutes' : null }))
          }}>
          <option value="">Select a kind</option>{restrictionKinds.map(kind => <option key={kind} value={kind}>{kind.replaceAll('_', ' ')}</option>)}
        </select></label>
        <label>{draft.restrictionKind === 'maximum_duration' ? 'Maximum duration (minutes)' : 'Activity or sport'}
          <input required maxLength={80} inputMode={draft.restrictionKind === 'maximum_duration' ? 'decimal' : undefined}
            value={draft.restrictionValue ?? ''} disabled={busy} onChange={event => update('restrictionValue', event.target.value || null)} />
        </label>
      </fieldset>}
      <label>Context text<textarea required maxLength={4000} rows={4} value={draft.content} disabled={busy}
        onChange={event => update('content', event.target.value)} /></label>
      {error && <p role="alert" className="access-message">{error}</p>}
      {message && <p role="status">{message}</p>}
      <div className="context-actions">
        <button type="submit" disabled={busy}>{busy ? 'Saving…' : editing ? 'Save correction' : 'Save context'}</button>
        {editing && <button type="button" className="secondary" disabled={busy} onClick={resetForm}>Cancel correction</button>}
      </div>
    </form>
    <form className="context-form" onSubmit={event => void submitFeedback(event)} aria-labelledby="feedback-heading">
      <h3 id="feedback-heading">Review feedback</h3>
      <p>Feedback is saved as dated, attributed user context linked to the immutable review snapshot. It is not a measurement or model training.</p>
      <label>Connected review snapshot ID<input required maxLength={36} value={feedbackSnapshotId} disabled={busy}
        onChange={event => setFeedbackSnapshotId(event.target.value)} /></label>
      <label>Usefulness rating<select value={feedbackRating} disabled={busy} onChange={event => setFeedbackRating(event.target.value)}>
        <option value="">No rating</option><option value="useful">Useful</option><option value="not_useful">Not useful</option>
      </select></label>
      <label>Correction or note (optional)<textarea maxLength={4000} rows={3} value={feedbackCorrection} disabled={busy}
        onChange={event => setFeedbackCorrection(event.target.value)} /></label>
      <div className="context-actions"><button type="submit" disabled={busy}>{busy ? 'Saving…' : 'Save review feedback'}</button></div>
    </form>
    <h3>Current context</h3>
    {records.length === 0 ? <p>{error ? 'Context could not be loaded.' : 'No current context entries.'}</p> : <ul className="context-list">
      {records.map(item => <li key={item.contextId} className="context-record">
        <h4>{topicLabel(item.category)} <span>· revision {item.revision}</span></h4>
        <p><strong>Source:</strong> {sourceLabel[item.sourceCategory]} ({item.authorAttribution})</p>
        <p><strong>Observed:</strong> {item.observedOn}{item.applicableFrom || item.applicableUntil ?
          ` · Applicable ${item.applicableFrom ?? 'any date'} to ${item.applicableUntil ?? 'no end date'}` : ''}</p>
        {(item.sport || item.activityId || item.reviewId) && <p><strong>Scope:</strong> {[item.sport, item.activityId && `activity ${item.activityId}`,
          item.reviewId && `review ${item.reviewId}`].filter(Boolean).join(' · ')}</p>}
        {item.restrictionKind && <p><strong>Restriction:</strong> {item.restrictionKind.replaceAll('_', ' ')} — {item.restrictionValue}{item.restrictionUnit ? ` ${item.restrictionUnit}` : ''}</p>}
        <p className="context-content">{item.content}</p>
        <div className="context-actions">
          <button type="button" className="secondary" disabled={busy} onClick={() => beginCorrection(item)}>Correct</button>
          <button type="button" className="secondary" disabled={busy} onClick={() => void toggleHistory(item)}>
            {history[item.contextId] ? 'Hide revision history' : 'View revision history'}
          </button>
          <button type="button" className="secondary" disabled={busy} onClick={() => void retire(item)}>Retire</button>
          <button type="button" className="danger" disabled={busy} onClick={() => setConfirmDelete(item.contextId)}>Delete</button>
        </div>
        {confirmDelete === item.contextId && <div role="group" aria-label={`Confirm deletion of ${topicLabel(item.category)}`}>
          <p>Delete this entry and dependent saved review copies? Retained encrypted backups may expire later under normal retention.</p>
          <button type="button" className="danger" disabled={busy} onClick={() => void remove(item)}>Confirm deletion</button>
          <button type="button" className="secondary" disabled={busy} onClick={() => setConfirmDelete(null)}>Keep context</button>
        </div>}
        {history[item.contextId] && <ol className="context-history" aria-label="Context revision history">
          {history[item.contextId].map(revision => <li key={`${revision.contextId}-${revision.revision}`}>
            <strong>Revision {revision.revision}{revision.revision === item.revision ? ' (current)' : ' (superseded)'}</strong>
            <span> — {revision.observedOn}; {sourceLabel[revision.sourceCategory]}</span>
            <p className="context-content">{revision.content}</p>
          </li>)}
        </ol>}
      </li>)}
    </ul>}
  </section>
}
