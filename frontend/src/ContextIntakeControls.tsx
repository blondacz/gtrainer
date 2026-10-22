import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { blankDraft, ContextFields, sourceLabel, validateDraft } from './ContextFields'
import { blankCommitment, CommitmentFields, validateCommitmentDraft } from './CommitmentFields'
import { ContextSuggestionControls, type SuggestionLoader, type TypedSuggestion } from './ContextSuggestionControls'
import { arrayFrom, commitmentFrom, intakeFrom, intakeRequest, object, PrivateIntakeError, validateConfirmation, viewFrom,
  type Commitment, type CommitmentItem, type ContextItem, type ContextView, type Intake, type PendingItem } from './ContextIntakeClient'

/** Saved source, inert manual drafts, explicit confirmation. No model/extraction/provider path. */
export function ContextIntakeControls({ csrfToken, onConfirmed, loadSuggestions, allowNewIntakes = true, intakeLabel }: {
  csrfToken: string; onConfirmed: () => void; loadSuggestions?: SuggestionLoader;
  allowNewIntakes?: boolean; intakeLabel?: (note: Intake) => string
}) {
  const [notes, setNotes] = useState<Intake[]>([]), [active, setActive] = useState<Intake | null>(null)
  const [note, setNote] = useState({ content: '', sourceCategory: 'user_report' as Intake['sourceCategory'], authorAttribution: 'athlete', observedOn: '' })
  const [correctingSource, setCorrectingSource] = useState(false), [selection, setSelection] = useState('')
  const [views, setViews] = useState<ContextView[]>([]), [calendarItems, setCalendarItems] = useState<Commitment[]>([])
  const [pending, setPending] = useState<PendingItem[]>([]), [contextDraft, setContextDraft] = useState<ContextItem | null>(null)
  const [commitmentDraft, setCommitmentDraft] = useState<CommitmentItem | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [message, setMessage] = useState('')
  const [ready, setReady] = useState(false), [uncertain, setUncertain] = useState(false)
  const [suggestionFields,setSuggestionFields]=useState<string[]>([]),[resolvedFields,setResolvedFields]=useState<string[]>([])
  const generation = useRef(0), key = useRef(0)
  const request = (path: string, method = 'GET', body?: unknown, signal?: AbortSignal) => intakeRequest(path, csrfToken, method, body, signal)

  useEffect(() => {
    const controller = new AbortController()
    void intakeRequest('/api/context-intakes', csrfToken, 'GET', undefined, controller.signal).then(body => setNotes(arrayFrom(body, intakeFrom)))
      .catch(() => { if (!controller.signal.aborted) setError('Intake notes unavailable. Reload to try again.') })
    return () => { controller.abort(); generation.current++ }
  }, [csrfToken])

  async function loadItems(source: Intake, token = generation.current) {
    const [linksBody, lifecycleBody, commitmentsBody] = await Promise.all([
      request(`/api/context-intakes/${source.intakeId}/links`), request('/api/context-lifecycle'), request('/api/commitments'),
    ])
    const links = arrayFrom(linksBody, object), report = object(lifecycleBody)
    const current = arrayFrom(report.items, viewFrom).filter(view => links.some(link => link.contextId === view.context.contextId && link.contextRevision === view.context.revision))
    const commitments = arrayFrom(commitmentsBody, commitmentFrom).filter(item => item.reference.intakeId === source.intakeId)
    if (token === generation.current) { setViews(current); setCalendarItems(commitments); setReady(true) }
  }
  async function choose(source: Intake | null) {
    const token = ++generation.current
    setActive(source); setPending([]); setViews([]); setCalendarItems([]); setContextDraft(null); setCommitmentDraft(null)
    setSelection(''); setCorrectingSource(false); setReady(false); setUncertain(false); setError(''); setMessage('')
    setSuggestionFields([]);setResolvedFields([])
    if(!source) setNote({ content:'',sourceCategory:'user_report',authorAttribution:'athlete',observedOn:'' })
    if (source) try { await loadItems(source, token) } catch { if (token === generation.current) setError('Linked items unavailable. Reload before confirming.') }
  }
  async function reload() {
    if(busy) return
    setBusy(true)
    try {
      const latest=arrayFrom(await request('/api/context-intakes'),intakeFrom)
      setNotes(latest)
      await choose(latest.find(row => row.intakeId===active?.intakeId) ?? null)
    } catch { setError('Could not reload source revisions. No confirmation was sent.'); setReady(false) }
    finally { setBusy(false) }
  }
  async function saveSource(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    if (!allowNewIntakes && !correctingSource) { setError('Choose a bundled synthetic note; new sources are disabled.'); return }
    setBusy(true); setError(''); setMessage('')
    try {
      const saved = intakeFrom(await request(correctingSource && active ? `/api/context-intakes/${active.intakeId}` : '/api/context-intakes',
        correctingSource ? 'PUT' : 'POST', correctingSource && active ? { expectedRevision: active.revision, note } : note))
      setNotes(current => [...current.filter(row => row.intakeId !== saved.intakeId), saved])
      await choose(saved)
      setMessage('Original note saved. No structured item or calendar constraint is active until explicitly confirmed.')
      onConfirmed()
    } catch (cause) {
      if(!(cause instanceof PrivateIntakeError)) setUncertain(true)
      setError(cause instanceof PrivateIntakeError ? cause.message : 'Save outcome unknown. Reload and inspect notes before saving again. No automatic retry was made.')
    }
    finally { setBusy(false) }
  }
  function newContext(content: string) {
    if (!active) return
    setCommitmentDraft(null)
    setSuggestionFields([]);setResolvedFields([])
    setContextDraft({ context: { ...blankDraft(), content, sourceCategory: active.sourceCategory, authorAttribution: active.authorAttribution,
      observedOn: active.observedOn }, lifecycle: { resolution: 'unresolved', reviewDueOn: null }, contextId: null, expectedContextRevision: null })
  }
  function editContext(view: ContextView) {
    if (!active) return
    const { contextId, revision, enteredBy: _entered, retired: _retired, ...draft } = view.context
    setCommitmentDraft(null)
    setSuggestionFields([]);setResolvedFields([])
    setContextDraft({ contextId, expectedContextRevision: revision, context: { ...draft, sourceCategory: active.sourceCategory,
      authorAttribution: active.authorAttribution }, lifecycle: { ...view.lifecycle } })
  }
  function newCommitment() {
    setSuggestionFields([]);setResolvedFields([])
    setContextDraft(null)
    setCommitmentDraft({ definition: { ...blankCommitment(), label: selection.length <= 160 ? selection : '' },
      commitmentId: null, expectedCommitmentRevision: null, context: null })
  }
  function editSuggestion(suggestion: TypedSuggestion) {
    if(!active || busy || !ready || uncertain || correctingSource) return
    setSuggestionFields(suggestion.unresolvedFields);setResolvedFields([]);setError('')
    if(suggestion.kind==='context') {
      const proposed=suggestion.context
      setCommitmentDraft(null)
      setContextDraft({context:{...blankDraft(),category:proposed.category as ContextItem['context']['category'],content:proposed.content,
        sourceCategory:active.sourceCategory,authorAttribution:active.authorAttribution,observedOn:proposed.observedOn ?? '',
        applicableFrom:proposed.applicableFrom,applicableUntil:proposed.applicableUntil},
        lifecycle:{resolution:'unresolved',reviewDueOn:null},contextId:null,expectedContextRevision:null})
    } else {
      const proposed=suggestion.commitment,recurrence=proposed.recurrence
      setContextDraft(null)
      setCommitmentDraft({definition:{...blankCommitment(),label:proposed.label,kind:proposed.kind ?? 'commitment',
        startsLocal:proposed.startsLocal ?? '',endsLocal:proposed.endsLocal ?? '',timeZone:proposed.timeZone ?? '',
        recurrence:recurrence && recurrence.frequency!=='one_off' ? {frequency:recurrence.frequency,interval:recurrence.interval ?? 0,endOn:recurrence.untilDate} : null},
        commitmentId:null,expectedCommitmentRevision:null,context:null})
    }
    setMessage('Suggestion opened as an editable, unconfirmed draft. Resolve every unknown; no item was staged or confirmed.')
  }
  function stage(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    if(suggestionFields.some(field=>!resolvedFields.includes(field))) { setError('Resolve and explicitly acknowledge each unknown suggestion field before staging.');return }
    if (pending.length >= 20) { setError('Confirm or remove drafts before adding more (20 items maximum).'); return }
    if (contextDraft) {
      const invalid = validateDraft(contextDraft.context)
      if (invalid) { setError(invalid); return }
      if (contextDraft.lifecycle.reviewDueOn && !/^\d{4}-\d{2}-\d{2}$/.test(contextDraft.lifecycle.reviewDueOn)) { setError('Choose a real review date.'); return }
      if (pending.some(row => row.kind === 'context' && row.item.contextId && row.item.contextId === contextDraft.contextId)) { setError('That item already has a draft. Remove it before staging another update.'); return }
      setPending(current => [...current, { key: ++key.current, selected: true, kind: 'context', item: contextDraft }]); setContextDraft(null)
    } else if (commitmentDraft) {
      const invalid = validateCommitmentDraft(commitmentDraft.definition)
      if (invalid) { setError(invalid); return }
      if (pending.some(row => row.kind === 'commitment' && row.item.commitmentId && row.item.commitmentId === commitmentDraft.commitmentId)) { setError('That item already has a draft. Remove it before staging another update.'); return }
      setPending(current => [...current, { key: ++key.current, selected: true, kind: 'commitment', item: commitmentDraft }]); setCommitmentDraft(null)
    }
    setError(''); setMessage('Draft staged only. Inspect it and explicitly confirm one item or the selected batch.')
    setSuggestionFields([]);setResolvedFields([])
  }
  async function confirm(items: PendingItem[]) {
    if (!active || busy || !ready || uncertain || !items.length) return
    const contexts=items.filter(row => row.kind==='context').map(row => row.item)
    const body = { confirmationId: crypto.randomUUID(), expectedRevision: active.revision,
      contexts,
      commitments: items.filter(row => row.kind === 'commitment').map(row => {
        const index=contexts.findIndex(context => context.contextId && context.contextId===row.item.context?.contextId && context.expectedContextRevision===row.item.context.revision)
        return index<0 ? row.item : { ...row.item, context: null, contextItemIndex: index }
      }) }
    if (new TextEncoder().encode(JSON.stringify(body)).length > 32_768) { setError('Selected batch exceeds 32 KiB. Select fewer items; no batch was sent or silently split.'); return }
    setBusy(true); setError(''); setMessage('')
    try {
      validateConfirmation(await request(`/api/context-intakes/${active.intakeId}/confirm`, 'POST', body),active,body.contexts,body.commitments)
      setPending(current => current.filter(row => !items.some(item => item.key === row.key)))
      await loadItems(active)
      onConfirmed(); setMessage(`${items.length} item(s) confirmed. Other drafts remain inactive. No inference or plan change occurred.`)
    } catch (cause) {
      if (cause instanceof PrivateIntakeError) setError(cause.message)
      else { setUncertain(true); setError('Confirmation outcome unknown. Reload and inspect confirmed items before drafting again. No automatic retry was made.') }
    } finally { setBusy(false) }
  }
  const disabled = busy || !ready || uncertain
  return <div className="context-intake" aria-label="Single-entry context intake">
    <h3>Enter once, confirm reusable items</h3>
    <p>Qualified extraction is unavailable. This manual fallback never calls a model. Select wording from the original note rather than retyping it; dates and recurrence must be explicit.</p>
    <label>Saved original note<select value={active?.intakeId ?? ''} disabled={busy} onChange={e => void choose(notes.find(row => row.intakeId === e.target.value) ?? null)}>
      <option value="">{allowNewIntakes ? 'New note' : 'Choose a bundled synthetic note'}</option>{notes.map(row => <option key={row.intakeId} value={row.intakeId}>{intakeLabel ? intakeLabel(row) : `${row.observedOn} · ${row.authorAttribution} · revision ${row.revision}`}</option>)}</select></label>
    {!active && <button type="button" disabled={busy} onClick={() => void reload()}>Reload original notes and discard drafts</button>}
    {((!active && allowNewIntakes) || correctingSource) && <form className="context-form" onSubmit={e => void saveSource(e)}>
      <label>Original wording<textarea required maxLength={4000} rows={4} disabled={busy} value={note.content} onChange={e => setNote({ ...note, content: e.target.value })} /></label>
      <label>Original source<select value={note.sourceCategory} disabled={busy} onChange={e => setNote({ ...note, sourceCategory: e.target.value as Intake['sourceCategory'] })}>
        {(['user_report', 'clinician_guidance', 'coach_guidance'] as const).map(source => <option key={source} value={source}>{sourceLabel[source]}</option>)}</select></label>
      <label>Original author<input required maxLength={160} value={note.authorAttribution} disabled={busy} onChange={e => setNote({ ...note, authorAttribution: e.target.value })} /></label>
      <label>Original observation date<input required type="date" value={note.observedOn} disabled={busy} onChange={e => setNote({ ...note, observedOn: e.target.value })} /></label>
      <button disabled={busy || uncertain} type="submit">{correctingSource ? 'Save source revision' : 'Save original note only'}</button>
    </form>}
    {active && <>
      <p>Source: {sourceLabel[active.sourceCategory]} · {active.authorAttribution} · observed {active.observedOn} · source revision {active.revision}</p>
      <label>Select original wording<textarea readOnly rows={4} value={active.content} onSelect={e => {
        const element = e.currentTarget; setSelection(element.value.slice(element.selectionStart, element.selectionEnd))
      }} /></label>
      <p>Source corrections retain old wording/history and require item reconfirmation. Editing source discards unconfirmed drafts. Corrections are not privacy erasure; use Delete in the context list for dependency preview and approved redaction. Encrypted backups follow separate retention.</p>
      <button type="button" disabled={busy} onClick={() => { setCorrectingSource(true); setNote({ content: active.content, sourceCategory: active.sourceCategory,
        authorAttribution: active.authorAttribution, observedOn: active.observedOn }); setPending([]); setContextDraft(null); setCommitmentDraft(null) }}>Correct original source</button>
      <button type="button" disabled={busy} onClick={() => void reload()}>Reload confirmed items and discard drafts</button>
      <div className="context-actions">
        <button type="button" disabled={disabled || correctingSource || !selection.trim()} onClick={() => newContext(selection)}>Draft context from selection</button>
        <button type="button" disabled={disabled || correctingSource} onClick={() => newContext(active.content)}>Draft entire note as context</button>
        <button type="button" disabled={disabled || correctingSource} onClick={newCommitment}>Draft commitment or availability</button>
      </div>
      {!disabled && !correctingSource && <ContextSuggestionControls key={`${active.intakeId}/${active.revision}`} note={active} csrfToken={csrfToken} onEdit={editSuggestion} loadSuggestions={loadSuggestions}/>}
      {(contextDraft || commitmentDraft) && <form className="context-form" aria-label="Unconfirmed item editor" onSubmit={stage}>
        <h4>Unconfirmed draft — not active</h4>
        <p>Confirming uses {active.authorAttribution} / {sourceLabel[active.sourceCategory]} from source revision {active.revision}. Review all changed fields.</p>
        {suggestionFields.length>0 && <fieldset><legend>Resolve unknown suggestion fields</legend>
          <p>Blank applicability means open-ended, not unknown. Review calendar kind and recurrence explicitly; the editor's initial selection is not a confirmed choice. No recurrence means one occurrence; a blank last date means open-ended recurrence.</p>
          {suggestionFields.map(field=><label key={field}><input type="checkbox" checked={resolvedFields.includes(field)} disabled={disabled}
            onChange={e=>setResolvedFields(current=>e.target.checked ? [...current,field] : current.filter(item=>item!==field))}/>I resolved {field} using the editor (including any intentional open end or one-off choice)</label>)}
        </fieldset>}
        {contextDraft && <>
          <ContextFields value={contextDraft.context} onChange={context => setContextDraft({ ...contextDraft, context })} disabled={busy} attribution={false} />
          <label>Resolution<select value={contextDraft.lifecycle.resolution} disabled={busy} onChange={e => setContextDraft({ ...contextDraft,
            lifecycle: { ...contextDraft.lifecycle, resolution: e.target.value as 'unresolved' | 'resolved' } })}>
            <option value="unresolved">Unresolved</option><option value="resolved">Explicitly resolved (not medical clearance)</option></select></label>
          <label>Review due (optional)<input type="date" value={contextDraft.lifecycle.reviewDueOn ?? ''} disabled={busy} onChange={e => setContextDraft({ ...contextDraft,
            lifecycle: { ...contextDraft.lifecycle, reviewDueOn: e.target.value || null } })} /></label>
          <p>Improving is not recovered. Review due and applicability end never imply resolution. Retirement alone does not clear unresolved safety context.</p>
        </>}
        {commitmentDraft && <>
          <CommitmentFields value={commitmentDraft.definition} onChange={definition => setCommitmentDraft({ ...commitmentDraft, definition })} disabled={busy} />
          <label>Linked confirmed context (optional)<select value={commitmentDraft.context?.contextId ?? ''} disabled={busy} onChange={e => {
            const view = views.find(row => row.context.contextId === e.target.value)
            setCommitmentDraft({ ...commitmentDraft, context: view ? { contextId: view.context.contextId, revision: view.context.revision } : null })
          }}><option value="">No new association (existing links are retained)</option>{views.map(row => <option key={row.context.contextId} value={row.context.contextId}>{row.context.category} · revision {row.context.revision}</option>)}</select></label>
        </>}
        <button type="submit" disabled={disabled || suggestionFields.some(field=>!resolvedFields.includes(field))}>Stage item for confirmation</button>
        <button type="button" disabled={busy} onClick={() => { setContextDraft(null); setCommitmentDraft(null) }}>Discard editor</button>
      </form>}
      {pending.length > 0 && <div aria-label="Pending confirmation"><h4>Inspect pending items</h4>
        <p>Only the selected visible items are confirmed atomically (20 items / 32 KiB maximum). Per-item confirmation leaves other drafts inactive.</p>
        <p>A calendar item linked to context updated in this same selected batch will reference its resulting confirmed revision. Existing links are otherwise retained, not guessed or detached.</p>
        {pending.map(row => <div key={row.key} role="group" aria-label={`Pending item ${row.key}`}>
          <label><input type="checkbox" checked={row.selected} disabled={disabled} onChange={e => setPending(current => current.map(item => item.key === row.key ? { ...item, selected: e.target.checked } : item))} />Select item {row.key}</label>
          <pre>{JSON.stringify(row.item, null, 2)}</pre>
          <button type="button" disabled={disabled} onClick={() => void confirm([row])}>Confirm this item</button>
          <button type="button" disabled={busy} onClick={() => {
            setSuggestionFields([]);setResolvedFields([])
            setPending(current => current.filter(item => item.key!==row.key))
            if(row.kind==='context') { setContextDraft(row.item); setCommitmentDraft(null) }
            else { setCommitmentDraft(row.item); setContextDraft(null) }
          }}>Edit draft</button>
          <button type="button" disabled={busy} onClick={() => setPending(current => current.filter(item => item.key !== row.key))}>Remove draft</button>
        </div>)}
        <button type="button" disabled={disabled || pending.every(row => !row.selected)} onClick={() => void confirm(pending.filter(row => row.selected))}>Confirm selected items</button>
      </div>}
      <h4>Confirmed items from this source</h4>
      {views.map(view => <div key={view.context.contextId} className="context-record">
        <p>{view.context.category} · revision {view.context.revision} · {view.state}{view.needsReview ? ' · needs review' : ''}{view.sourceChanged ? ' · source changed: reconfirm' : ''}</p>
        <p>{view.context.content}</p><p>{view.context.authorAttribution} · observed {view.context.observedOn} · review due {view.lifecycle.reviewDueOn ?? 'not set'}</p>
        <button type="button" disabled={disabled || correctingSource} onClick={() => editContext(view)}>Update or reconfirm context</button>
      </div>)}
      {calendarItems.map(item => <div key={item.reference.commitmentId} className="context-record">
        <p>{item.definition.kind}: {item.definition.label} · revision {item.reference.revision}{item.needsReconfirmation ? ' · needs reconfirmation; not authoritative/free time' : ''}</p>
        <p>{item.definition.startsLocal} – {item.definition.endsLocal} ({item.definition.timeZone}) · {item.definition.recurrence?.frequency ?? 'once'}</p>
        <button type="button" disabled={disabled || correctingSource} onClick={() => { setSuggestionFields([]);setResolvedFields([]);setContextDraft(null); setCommitmentDraft({ definition: item.definition,
          commitmentId: item.reference.commitmentId, expectedCommitmentRevision: item.reference.revision, context: item.reference.contextId ?
            { contextId: item.reference.contextId, revision: item.reference.contextRevision! } : null }) }}>Update or reconfirm calendar item</button>
      </div>)}
      <p>Historical context uses current canonical revisions, not point-in-time replay. Drafts and confirmed commitments are not a training plan or amendment approval.</p>
    </>}
    {error && <p role="alert">{error}</p>}{message && <p role="status">{message}</p>}
  </div>
}
