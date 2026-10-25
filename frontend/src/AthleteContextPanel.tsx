import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { blankDraft, recordFrom, sourceLabel, topicLabel, validateDraft, ContextFields } from './ContextFields'
import type { ContextDraft as Draft, ContextRecord as AthleteContext } from './ContextFields'
import { ContextIntakeControls } from './ContextIntakeControls'
import { ContextDeletionControls } from './ContextDeletionControls'

/** Authenticated, attributed context lifecycle UI; corrections create new revisions rather than overwriting. */
export function AthleteContextPanel({ csrfToken }: { csrfToken: string }) {
  const [generation, setGeneration] = useState(0)
  return <AthleteContextContent key={generation} csrfToken={csrfToken} reload={() => setGeneration(current => current+1)} />
}

function AthleteContextContent({ csrfToken, reload }: { csrfToken: string; reload: () => void }) {
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

  // A successful purge or an uncertain-outcome reload remounts the entire subtree:
  // intake notes, drafts, feedback and revision-history caches cannot retain deleted prose.
  if(confirmDelete) return <ContextDeletionControls contextId={confirmDelete} csrfToken={csrfToken} onDeleted={reload} onCancel={reload} />
  return <section aria-labelledby="context-heading">
    <h2 id="context-heading">Athlete context</h2>
    <p>Save notes, preferences, restrictions, or guidance you received from a coach or clinician. These are attributed reports, not independently verified facts or imported measurements.</p>
    <ContextIntakeControls csrfToken={csrfToken} onConfirmed={() => void load()} />
    <details open={editing !== null || undefined}>
      <summary>Direct context entry / legacy corrections</summary>
      <p>For an intake-linked item use its source's update/reconfirm controls above. Direct updates cannot bypass source confirmation or shared-source deletion.</p>
    <form className="context-form" onSubmit={event => void save(event)}>
      <h3>{editing ? 'Correct context' : 'Add context'}</h3>
      <ContextFields value={draft} onChange={setDraft} disabled={busy} />
      {error && <p role="alert" className="access-message">{error}</p>}
      {message && <p role="status">{message}</p>}
      <div className="context-actions">
        <button type="submit" disabled={busy}>{busy ? 'Saving…' : editing ? 'Save correction' : 'Save context'}</button>
        {editing && <button type="button" className="secondary" disabled={busy} onClick={resetForm}>Cancel correction</button>}
      </div>
    </form>
    </details>
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
