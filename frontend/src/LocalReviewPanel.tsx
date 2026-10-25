import { useEffect, useId, useRef, useState } from 'react'
import { loadEvidence, type EvidenceSnapshot } from './ScopedEvidenceClient'
import { ScopedEvidenceInspection } from './ScopedEvidenceInspection'
import { cancelLocalReview, loadLocalHistory, loadLocalJob, loadLocalPolicy, loadLocalReview, localCitation,
  prepareLocalReview, rearmLocalReview, requestLocalReview, saveLocalFeedback, LocalReviewReadError,
  type LocalJob, type LocalPolicy, type LocalReview, type LocalReviewHistory, type LocalReviewItem, type LocalReviewSummary } from './LocalReviewClient'

type Props = { csrfToken: string; snapshot: EvidenceSnapshot | null }

/** Opening/reading never prepares or generates a review. Browser loss never cancels a server-owned job. */
export function LocalReviewPanel({ csrfToken, snapshot }: Props) {
  const [open, setOpen] = useState(false)
  return <section aria-label="Experimental local model reviews">
    <h4>Experimental local model reviews</h4>
    <p>Review selected evidence with the installed local model, or open saved results. Factual reports work without AI.</p>
    {!open ? <button type="button" onClick={() => setOpen(true)}>Open local model reviews</button> :
      <LocalReviewWorkspace csrfToken={csrfToken} snapshot={snapshot} />}
  </section>
}

function LocalReviewWorkspace({ csrfToken, snapshot }: Props) {
  const [policy, setPolicy] = useState<LocalPolicy | null>(null), [history, setHistory] = useState<LocalReviewHistory | null>(null)
  const [review, setReview] = useState<LocalReview | null>(null), [job, setJob] = useState<LocalJob | null>(null)
  const [selected, setSelected] = useState<LocalReviewSummary | null>(null), [evidence, setEvidence] = useState<EvidenceSnapshot | null>(null)
  const [message, setMessage] = useState('Reading local review status…'), [busy, setBusy] = useState(false)
  const [uncertain, setUncertain] = useState(false), [feedbackUncertain, setFeedbackUncertain] = useState(false)
  const [rating, setRating] = useState(''), [correction, setCorrection] = useState('')
  const generation = useRef(0), active = useRef<AbortController | null>(null), inflight = useRef(false)
  const id = useId()
  useEffect(() => {
    active.current?.abort(); generation.current++; inflight.current = false
    setReview(null); setSelected(null); setJob(null); setEvidence(null); setRating(''); setCorrection(''); setFeedbackUncertain(false); setUncertain(false)
    const controller = new AbortController(), epoch = generation.current; active.current = controller
    setBusy(true)
    if (csrfToken) Promise.all([loadLocalPolicy(controller.signal), loadLocalHistory(controller.signal)]).then(([p, h]) => {
      if (generation.current !== epoch) return
      setPolicy(p); setHistory(h); setMessage('Status and saved reviews read only. No model request was sent.')
    }).catch(() => { if (!controller.signal.aborted && generation.current === epoch) { setPolicy(null); setHistory(null); setMessage('Local review status unavailable. Factual reports and manual editing remain usable.') } })
      .finally(() => { if (generation.current === epoch) setBusy(false) })
    return () => { controller.abort(); active.current?.abort(); generation.current++ }
  }, [csrfToken, snapshot?.snapshotId, snapshot?.state])

  async function perform(work: (signal: AbortSignal) => Promise<() => void>, mutation = false, feedback = false) {
    if (inflight.current || !csrfToken) return
    inflight.current = true; setBusy(true); active.current?.abort()
    const controller = new AbortController(), epoch = ++generation.current; active.current = controller
    try { const apply = await work(controller.signal); if (epoch === generation.current) apply() }
    catch (cause) {
      if (controller.signal.aborted || epoch !== generation.current) return
      setReview(null); setJob(null); setEvidence(null); setHistory(null); setSelected(null); setPolicy(null)
      if (feedback) { setFeedbackUncertain(true); setMessage('Feedback outcome could not be verified. Check Context before creating another entry; no automatic retry.') }
      else if (mutation) { setUncertain(true); setMessage('Action outcome could not be verified. Read saved reviews before another action; no automatic retry or replacement request.') }
      else setMessage(cause instanceof LocalReviewReadError && cause.status === 404 ? 'This saved review or its source was removed. Cached values are not displayed.' : 'Review inspection unavailable or changed. Cached values are not displayed; no model request was sent.')
    } finally { if (generation.current === epoch) { inflight.current = false; setBusy(false) } }
  }
  function refreshHistory(offset = 0) {
    void perform(async signal => {
      const [p, h] = await Promise.all([loadLocalPolicy(signal), loadLocalHistory(signal, offset)])
      return () => { setPolicy(p); setHistory(h); setReview(null); setSelected(null); setJob(null); setEvidence(null); setUncertain(false); setMessage('Saved reviews read only. Select a result to inspect its current source binding.') }
    })
  }
  function openResult(item: LocalReviewSummary) {
    void perform(async signal => {
      const [r, e] = await Promise.all([loadLocalReview(item.executionId, signal, item.source.snapshotId), loadEvidence(item.source.snapshotId, signal)])
      return () => { setReview(r); setSelected(item); setEvidence(e); setJob(null); setRating(''); setCorrection(''); setFeedbackUncertain(false); setUncertain(false); setMessage('Saved result opened without generation. Dates and source snapshot remain attached to this result.') }
    })
  }
  function prepare() {
    if (snapshot?.state !== 'PREPARED' || !policy?.selectedProviderModel || policy.selectedProviderModel.providerKind !== 'local' || uncertain) return
    void perform(async signal => {
      const r = await prepareLocalReview(snapshot.snapshotId, policy.selectedProviderModel!, csrfToken, signal)
      return () => { setReview(r); setSelected(null); setEvidence(null); setJob(null); setRating(''); setCorrection(''); setFeedbackUncertain(false); setMessage('Local review preview saved. Inspect its exact scope and model; no generation was requested.') }
    }, true)
  }
  function refreshReview() {
    if (!review) return
    const current = review
    void perform(async signal => {
      const r = await loadLocalReview(current.binding.executionId, signal, current.binding.source.snapshotId)
      const j = r.manualRequestAdmission.requestAdmissionState !== 'service_unavailable' ? await loadLocalJob(r.binding.executionId, signal) : null
      return () => { setReview(r); setJob(j); setUncertain(false); setMessage('Review and job status refreshed. No request was resent or deadline renewed.') }
    })
  }
  function rearm() {
    const digest = policy?.ownerUat?.policySha256
    if (!digest || !review || uncertain) return
    const current = review
    void perform(async signal => {
      await rearmLocalReview(digest, csrfToken, signal)
      const [p, r] = await Promise.all([loadLocalPolicy(signal), loadLocalReview(current.binding.executionId, signal)])
      return () => { setPolicy(p); setReview(r); setMessage('Manual local jobs re-armed for this app session. No generation was requested.') }
    }, true)
  }
  function request() {
    if (!review || !review.manualRequestAdmission.manualRequestAllowed || uncertain) return
    const current = review
    void perform(async signal => {
      await requestLocalReview(current, csrfToken, signal)
      const [j, r] = await Promise.all([loadLocalJob(current.binding.executionId, signal), loadLocalReview(current.binding.executionId, signal, current.binding.source.snapshotId)])
      return () => { setJob(j); setReview(r); setMessage('Explicit request recorded. Runtime checks apply on submission; refresh status to inspect progress. Leaving this page does not cancel the job.') }
    }, true)
  }
  function cancel() {
    if (!review) return
    const current = review
    void perform(async signal => {
      const j = await cancelLocalReview(current, csrfToken, signal)
      const r = await loadLocalReview(current.binding.executionId, signal)
      return () => { setJob(j); setReview(r); setMessage('Cancellation inspected. Only confirmed stopping releases runtime capacity.') }
    }, true)
  }
  const outputSha = review?.validatedOutputSha256 ?? (selected?.executionId === review?.binding.executionId ? selected?.outputSha256 : null)
  function saveFeedback() {
    if (!review || !outputSha || feedbackUncertain || !rating && !correction.trim()) return
    const current = review, sha = outputSha
    void perform(async signal => {
      await saveLocalFeedback(current.binding.executionId, sha, rating, correction, csrfToken, signal)
      let refreshed: LocalReview | null = null
      try { refreshed = await loadLocalReview(current.binding.executionId, signal) } catch { /* Withhold cached prose if currentness cannot be checked. */ }
      return () => { setReview(refreshed); setRating(''); setCorrection(''); setMessage('Feedback saved as your attributed report. The original model result is unchanged; no new job was started.' + (refreshed ? '' : ' Reopen saved reviews to inspect status.')) }
    }, true, true)
  }
  const model = policy?.selectedProviderModel
  const canCancel = review && ['IN_FLIGHT', 'RUNNING'].includes(review.state) && (job?.runtimePhase != null || review.manualRequestAdmission.blockingReasons.includes('already_requested'))
  return <div aria-label="Local review workspace">
    <p aria-live="polite">{message}</p>
    <p>Experimental and unqualified. No hosted transfer, automatic model schedule, training prescription or plan approval is enabled here.</p>
    <p>{model ? `Installed selection: ${model.modelId} (${model.providerKind}).` : 'No local model policy is installed.'} {policy?.ownerUat ? `Owner policy: ${policy.ownerUat.state}; ${policy.ownerUat.armed ? 'armed' : 'not armed'}.` : 'Model execution remains unavailable.'}</p>
    <p>Request availability is not live runtime health or model qualification. Runtime admission can refuse a submitted job.</p>
    <button type="button" disabled={busy} onClick={() => refreshHistory()}>Refresh saved reviews (read only)</button>
    <p>{snapshot?.state === 'PREPARED' ? `Current snapshot: ${snapshot.manifest.scope.request.mode}, ${snapshot.manifest.scope.request.intent}; cutoff ${snapshot.manifest.scope.asOf}.` : 'Prepare an evidence snapshot above before requesting a new local review. Blocked or stale evidence cannot be sent.'}</p>
    <button type="button" disabled={busy || uncertain || snapshot?.state !== 'PREPARED' || !model || model.providerKind !== 'local' || policy?.ownerUat?.state !== 'accepted'} onClick={prepare}>Prepare local review preview</button>
    <h5>Saved review results and previews</h5>
    {!history?.items.length ? <p>No saved reviews on this page.</p> : <ul>{history.items.map(item => <li key={item.executionId}>
      <button type="button" disabled={busy} onClick={() => openResult(item)}>Open {item.coverageFrom}–{item.coverageUntil} · {item.providerSelection.modelId} · {item.state}</button>
      <span> {item.providerSelection.providerKind === 'hosted' ? 'Historical hosted selection; unavailable in local UAT.' : 'Local selection.'} Created {item.createdUtc}. Stored outcome; opening rechecks source currentness.</span>
    </li>)}</ul>}
    {history?.nextOffset != null && <button type="button" disabled={busy} onClick={() => refreshHistory(history.nextOffset!)}>Next saved review page</button>}
    {review && <section aria-label="Selected local review">
      <h5>Selected local review</h5>
      <p>{review.binding.coverageFrom}–{review.binding.coverageUntil} · {review.binding.providerSelection.modelId} ({review.binding.providerSelection.providerKind}) · {review.state}{review.staleReason ? `: ${review.staleReason}` : ''}</p>
      {review.binding.providerSelection.providerKind === 'hosted' && <p>Historical hosted selection. Local owner UAT does not permit hosted requests.</p>}
      <p>Request availability: {review.manualRequestAdmission.requestAdmissionState}. {review.manualRequestAdmission.blockingReasons.join(', ')}</p>
      {review.manualRequestAdmission.requestAdmissionState !== 'service_unavailable' && policy?.ownerUat?.state === 'accepted' && !policy.ownerUat.armed &&
        <button type="button" disabled={busy || uncertain} onClick={rearm}>Re-arm manual local model jobs</button>}
      <button type="button" disabled={busy || uncertain || !review.manualRequestAdmission.manualRequestAllowed} onClick={request}>Request local model review</button>
      <button type="button" disabled={busy} onClick={refreshReview}>Refresh review status (read only)</button>
      {canCancel && <button type="button" disabled={busy} onClick={cancel}>Cancel this model job</button>}
      {job && <p>Last read runtime phase: {job.runtimePhase ?? 'not claimed'}; attempts {job.attempts}. {job.deadlineUtc ? `Job deadline ${job.deadlineUtc}. The application does not expire at this deadline.` : ''} {job.runtimePhase === 'UNKNOWN' ? 'Stopping is unverified; further model jobs remain blocked.' : ''} {job.state === 'UNVERIFIED' ? 'Job status could not be verified; refresh before relying on this result.' : ''}</p>}
      {review.state === 'STALE' && <p>Source evidence changed. Old interpretation is withheld, not presented as current.</p>}
      {review.state === 'FAILED' && <p>No accepted result is displayed. Rejected, cancelled or unavailable output was not salvaged or applied.</p>}
      {review.draft && job?.state !== 'UNVERIFIED' && <div><p>Experimental interpretation—not a measurement, diagnosis, training prescription or readiness clearance.</p>
        {review.draft.interpretations.length === 0 && <p>This result contains no interpretations. Structural acceptance does not establish usefulness.</p>}
        <ReviewItems title="Interpretations" items={review.draft.interpretations} review={review} />
        <ReviewItems title="Questions" items={review.draft.questions} review={review} />
      </div>}
      {outputSha && ['PUBLISHED', 'STALE'].includes(review.state) && <form className="compact-form" onSubmit={event => { event.preventDefault(); saveFeedback() }}>
        <h5>Your feedback on this stored result</h5><p>Feedback is your report, not verified evidence. It cannot rewrite this result or start another request.</p>
        <label htmlFor={`${id}-rating`}>Usefulness</label><select id={`${id}-rating`} value={rating} onChange={event => setRating(event.target.value)} disabled={busy || feedbackUncertain}>
          <option value="">No rating</option><option value="useful">Useful</option><option value="not_useful">Not useful</option>
        </select>
        <label htmlFor={`${id}-feedback`}>Correction or feedback</label><textarea id={`${id}-feedback`} maxLength={4000} value={correction} onChange={event => setCorrection(event.target.value)} disabled={busy || feedbackUncertain} />
        <button type="submit" disabled={busy || feedbackUncertain || !rating && !correction.trim()}>Save feedback</button>
      </form>}
    </section>}
    {evidence && <ScopedEvidenceInspection snapshot={evidence} onSnapshot={next => { setEvidence(next); if (next.state !== 'PREPARED') { setReview(null); setJob(null) } }}
      onReadStart={() => { setReview(null); setJob(null) }} onReadError={() => { setReview(null); setJob(null) }} onRemoved={() => { setEvidence(null); setReview(null); setSelected(null); setJob(null) }} />}
  </div>
}

function ReviewItems({ title, items, review }: { title: string; items: LocalReviewItem[]; review: LocalReview }) {
  return <section aria-label={title}><h6>{title}</h6><ul>{items.map((item, index) => <li key={index}>
    <p>{item.text}</p><p>{item.kind}; uncertainty {item.uncertainty}.</p>
    {item.sources.map((ref, sourceIndex) => {
      const source = localCitation(review, ref)
      return <details key={JSON.stringify(ref)}><summary>Source {sourceIndex + 1}: {ref.kind}</summary>
        <pre>{source === null ? 'No display preview; inspect the attached snapshot records.' : JSON.stringify(source, null, 2)}</pre>
      </details>
    })}
  </li>)}</ul></section>
}
