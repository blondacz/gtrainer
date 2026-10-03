import { useEffect, useId, useRef, useState } from 'react'

type ManualEvent = { id: string; startDate: string; endDate: string; sport: string; goal: string; notes: string | null }
type Draft = Omit<ManualEvent, 'id'>
type EventList = { events: ManualEvent[]; nextUpcoming: ManualEvent | null; ongoing: ManualEvent[]; evaluatedOn: string }
const steps = ['Dates & sport', 'Goal', 'Notes']
const blank: Draft = { startDate: '', endDate: '', sport: '', goal: '', notes: null }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const keys = (value: Record<string, unknown>, required: string[], optional: string[] = []) =>
  required.every(key => Object.hasOwn(value, key)) && Object.keys(value).every(key => [...required, ...optional].includes(key))
function date(value: unknown): value is string {
  if (typeof value !== 'string' || !/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value) || value.startsWith('0000-')) return false
  const time = Date.parse(`${value}T00:00:00Z`)
  return Number.isFinite(time) && new Date(time).toISOString().slice(0, 10) === value
}
const text = (value: unknown, limit: number, required = false): value is string => typeof value === 'string' &&
  value.length <= limit && (!required || value.trim().length > 0) && !/[\u0000-\u0008\u000b-\u001f\u007f-\u009f]/.test(value)
function draftError(value: Draft) {
  if (!date(value.startDate) || !date(value.endDate)) return 'Enter real calendar dates (YYYY-MM-DD).'
  if (value.endDate < value.startDate || (Date.parse(value.endDate) - Date.parse(value.startDate)) / 86400000 > 3660)
    return 'End date must be on or after start date, at most 3660 days later.'
  if (!text(value.sport, 80, true)) return 'Enter a sport: 1–80 characters, with no control characters.'
  if (!text(value.goal, 2000, true)) return 'Enter a manual goal: 1–2000 characters, with no control characters.'
  if (value.notes !== null && !text(value.notes, 4000)) return 'Current-state notes allow up to 4000 characters, with no control characters.'
  return ''
}
function eventFrom(value: unknown): ManualEvent {
  if (!object(value) || !keys(value, ['id', 'startDate', 'endDate', 'sport', 'goal'], ['notes']) ||
      typeof value.id !== 'string' || !uuid.test(value.id)) throw new Error('Invalid event')
  const event = { ...value, notes: value.notes === undefined ? null : value.notes } as ManualEvent
  if (draftError(event)) throw new Error('Invalid event')
  return event
}
const same = (a: ManualEvent, b: ManualEvent) => a.id === b.id && sameDraft(a, b)
const sameDraft = (a: Draft, b: Draft) => ['startDate', 'endDate', 'sport', 'goal', 'notes'].every(key => a[key as keyof Draft] === b[key as keyof Draft])
function listFrom(value: unknown): EventList {
  if (!object(value) || !keys(value, ['events', 'nextUpcoming', 'ongoing', 'evaluatedOn']) ||
      !Array.isArray(value.events) || !Array.isArray(value.ongoing) || !date(value.evaluatedOn)) throw new Error('Invalid list')
  const evaluatedOn = value.evaluatedOn
  const events = value.events.map(eventFrom).sort((a, b) => a.startDate.localeCompare(b.startDate) || a.endDate.localeCompare(b.endDate) || a.id.localeCompare(b.id))
  if (new Set(events.map(event => event.id)).size !== events.length) throw new Error('Duplicate events')
  const upcoming = events.find(event => event.startDate >= evaluatedOn) ?? null
  const nextUpcoming = value.nextUpcoming === null ? null : eventFrom(value.nextUpcoming)
  const ongoing = value.ongoing.map(eventFrom)
  const expected = events.filter(event => event.startDate < evaluatedOn && event.endDate >= evaluatedOn)
  if ((upcoming === null) !== (nextUpcoming === null) || (upcoming && nextUpcoming && !same(upcoming, nextUpcoming)) ||
      expected.length !== ongoing.length || expected.some((event, index) => !same(event, ongoing[index]))) throw new Error('Invalid classification')
  return { events, nextUpcoming, ongoing, evaluatedOn: value.evaluatedOn }
}

// Bound both characters and line breaks; never truncate stored user text or split a Unicode code point.
function chunks(value: string) {
  const pages: string[] = []
  let page = '', size = 0, lines = 0
  for (const character of value) {
    if (size === 300 || (character === '\n' && lines === 5)) { pages.push(page); page = ''; size = 0; lines = 0 }
    page += character; size++; if (character === '\n') lines++
  }
  if (page || !pages.length) pages.push(page)
  return pages
}
function TextPages({ value, label }: { value: string | null; label: string }) {
  const [page, setPage] = useState(0)
  const pages = chunks(value ?? '')
  return <div>
    <h4>{label}</h4>
    <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }} aria-label={`${label} text`}>{value === null ? 'No notes supplied.' : pages[page] || 'No text supplied.'}</p>
    {pages.length > 1 && <nav className="tile-pagination" aria-label={`${label} text pages`}>
      <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous text page</button>
      <span>Text page {page + 1} of {pages.length}</span>
      <button type="button" disabled={page === pages.length - 1} onClick={() => setPage(page + 1)}>Next text page</button>
    </nav>}
  </div>
}

/** A keyed session boundary removes private drafts immediately when the authenticated token changes. */
export function ManualEventPanel({ csrfToken, onChanged }: { csrfToken: string; onChanged?: () => void }) {
  return <ManualEventContent key={csrfToken} csrfToken={csrfToken} onChanged={onChanged} />
}
function ManualEventContent({ csrfToken, onChanged }: { csrfToken: string; onChanged?: () => void }) {
  const prefix = useId()
  const [list, setList] = useState<EventList | null>(null)
  const [page, setPage] = useState(0)
  const [step, setStep] = useState(0)
  const [editor, setEditor] = useState<{ id: string | null; draft: Draft } | null>(null)
  const [confirmation, setConfirmation] = useState<string | null>(null)
  const [loadError, setLoadError] = useState('')
  const [writeError, setWriteError] = useState('')
  const [validation, setValidation] = useState('')
  const [busy, setBusy] = useState(false)
  const [unauthorized, setUnauthorized] = useState(!csrfToken)
  const generation = useRef(0)
  const controller = useRef<AbortController | null>(null)
  const changed = useRef(onChanged)
  changed.current = onChanged
  function invalidate() { generation.current++; controller.current?.abort(); controller.current = null }
  function begin() {
    invalidate()
    const abort = new AbortController()
    controller.current = abort
    setBusy(true)
    const version = generation.current
    return { abort, current: () => !abort.signal.aborted && generation.current === version }
  }
  function clearPrivate() {
    invalidate(); setList(null); setEditor(null); setConfirmation(null); setValidation(''); setWriteError(''); setLoadError('')
    setBusy(false); setUnauthorized(true)
  }
  async function load() {
    if (!csrfToken || unauthorized) return
    const request = begin()
    setList(null); setConfirmation(null); setLoadError('')
    try {
      const response = await fetch('/api/events', { cache: 'no-store', credentials: 'same-origin', signal: request.abort.signal })
      if (!request.current()) return
      if (response.status === 401) { clearPrivate(); return }
      if (response.status !== 200) throw new Error('Read failed')
      const result = listFrom(await response.json())
      if (request.current()) { setList(result); setPage(0); setStep(0) }
    } catch {
      if (request.current()) setLoadError('Manual events unavailable. Try Reload events.')
    } finally { if (request.current()) setBusy(false) }
  }
  useEffect(() => {
    void load()
    return invalidate
    // Loading is session-bound, never driven by drafts, paging, or callback identity.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  function navigate(action: () => void) {
    invalidate(); setBusy(false); setConfirmation(null); setWriteError(''); setValidation(''); action()
  }
  async function write(kind: 'POST' | 'PUT' | 'DELETE') {
    const target = kind === 'DELETE' ? confirmation : editor?.id
    const draft = editor?.draft
    if (unauthorized || busy || (kind !== 'POST' && (!target || !uuid.test(target) || !list?.events.some(event => event.id === target)))) return
    if (kind !== 'DELETE') {
      if (!draft) return
      const error = draftError(draft)
      if (error) { setValidation(error); return }
    }
    const body = kind === 'DELETE' ? undefined : JSON.stringify(draft)
    if (body && new TextEncoder().encode(body).length > 32768) { setValidation('Manual event is too large to save.'); return }
    const request = begin()
    setWriteError(''); setValidation('')
    try {
      const response = await fetch(kind === 'POST' ? '/api/events' : `/api/events/${target}`, {
        method: kind, credentials: 'same-origin', cache: 'no-store', signal: request.abort.signal,
        headers: { 'X-CSRF-Token': csrfToken, ...(body ? { 'Content-Type': 'application/json' } : {}) }, body,
      })
      if (!request.current()) return
      if (response.status === 401) { clearPrivate(); return }
      if (response.status !== (kind === 'POST' ? 201 : kind === 'PUT' ? 200 : 204)) {
        setWriteError(response.status === 404 ? 'This manual event is no longer available. Reload events.' :
          response.status === 400 ? 'Manual event was not accepted. Check your fields.' : 'Could not save this change. Please try again.')
        return
      }
      if (kind !== 'DELETE') {
        const saved = eventFrom(await response.json())
        if (!sameDraft(saved, draft!) || (kind === 'PUT' && saved.id !== target) ||
            (kind === 'POST' && list?.events.some(event => event.id === saved.id))) throw new Error('Invalid saved event')
      }
      if (!request.current()) return
      setEditor(null); setConfirmation(null); setStep(0)
      void load()
      changed.current?.()
    } catch {
      if (request.current()) setWriteError('Could not verify this change. Reload events before retrying.')
    } finally { if (request.current()) setBusy(false) }
  }
  const selected = list?.events[page]
  const status = selected && list ? selected.startDate >= list.evaluatedOn ? 'Upcoming' : selected.endDate >= list.evaluatedOn ? 'Ongoing' : 'Past' : ''
  const update = (field: keyof Draft, value: string) => {
    if (!editor) return
    // Changing input invalidates even a response whose fetch implementation ignores abort.
    invalidate(); setBusy(false); setValidation(''); setWriteError('')
    setEditor({ ...editor, draft: { ...editor.draft, [field]: value } })
  }
  return <section className="dashboard-tile events-tile" aria-labelledby={`${prefix}-heading`}>
    <h2 id={`${prefix}-heading`}>Manual events</h2>
    <p>Your dates, goal and current-state notes. No AI or training decisions; imports and schedules stay unchanged.</p>
    {unauthorized ? <p role="alert">Sign in again to view manual events.</p> : <>
      <div className="tile-pagination">
        <button type="button" onClick={() => navigate(() => { setEditor({ id: null, draft: { ...blank } }); setStep(0) })}>New manual event</button>
        <button type="button" disabled={busy || !!editor} onClick={() => { setWriteError(''); void load() }}>Reload events</button>
      </div>
      {busy && <p role="status">Loading or saving manual events…</p>}
      {loadError && <p role="alert">{loadError}</p>}
      {writeError && <p role="alert">{writeError}</p>}
      {validation && <p role="alert">{validation}</p>}
      {list && !editor && <p>Evaluated on {list.evaluatedOn} (UTC calendar date). Upcoming: {list.events.filter(event => event.startDate >= list.evaluatedOn).length}; ongoing: {list.ongoing.length}; past: {list.events.filter(event => event.endDate < list.evaluatedOn).length}.</p>}
      {(editor || selected) && <div className="section-tabs" role="tablist" aria-label={editor ? 'Manual event editor steps' : 'Manual event details'}>
        {steps.map((label, index) => <button key={label} type="button" role="tab" id={`${prefix}-tab-${index}`} aria-controls={`${prefix}-content`} aria-selected={step === index} onClick={() => setStep(index)}>{label}</button>)}
      </div>}
      {editor ? <form className="compact-form" onSubmit={event => { event.preventDefault(); void write(editor.id ? 'PUT' : 'POST') }} noValidate>
        <h3>{editor.id ? 'Edit manual event' : 'New manual event'}</h3>
        <div role="tabpanel" id={`${prefix}-content`} aria-labelledby={`${prefix}-tab-${step}`}>
          {step === 0 && <>
            <label htmlFor={`${prefix}-start`}>Start date (YYYY-MM-DD)</label>
            <input id={`${prefix}-start`} type="date" min="0001-01-01" max="9999-12-31" value={editor.draft.startDate} onChange={event => update('startDate', event.target.value)} />
            <label htmlFor={`${prefix}-end`}>End date (YYYY-MM-DD)</label>
            <input id={`${prefix}-end`} type="date" min="0001-01-01" max="9999-12-31" value={editor.draft.endDate} onChange={event => update('endDate', event.target.value)} />
            <p>Use the same date for one day, or a date range for a multi-day event. Maximum span: 3660 days.</p>
            <label htmlFor={`${prefix}-sport`}>Sport (up to 80 characters)</label>
            <input id={`${prefix}-sport`} value={editor.draft.sport} maxLength={80} onChange={event => update('sport', event.target.value)} />
          </>}
          {step === 1 && <>
            <label htmlFor={`${prefix}-goal`}>Manual goal (up to 2000 characters)</label>
            <textarea id={`${prefix}-goal`} rows={4} maxLength={2000} value={editor.draft.goal} onChange={event => update('goal', event.target.value)} />
          </>}
          {step === 2 && <>
            <label htmlFor={`${prefix}-notes`}>Current-state notes (optional, up to 4000 characters)</label>
            <textarea id={`${prefix}-notes`} rows={3} maxLength={4000} value={editor.draft.notes ?? ''} onChange={event => update('notes', event.target.value)} />
            <p>User text only, not a readiness or safety assessment. Whitespace is preserved.</p>
          </>}
        </div>
        <div className="tile-pagination"><button type="submit" disabled={busy}>Save manual event</button>
          <button type="button" onClick={() => navigate(() => { setEditor(null); setStep(0) })}>Cancel edit</button></div>
      </form> : selected && list ? <article aria-label="Manual event card">
        <h3>{selected.sport}</h3>
        <p>{status}{list.nextUpcoming?.id === selected.id ? ' — Next upcoming' : ''}</p>
        <div role="tabpanel" id={`${prefix}-content`} aria-labelledby={`${prefix}-tab-${step}`}>
          {step === 0 && <p>Manual event dates: {selected.startDate} to {selected.endDate} (calendar dates).</p>}
          {step === 1 && <TextPages key={`${selected.id}-goal`} value={selected.goal} label="Manual goal" />}
          {step === 2 && <TextPages key={`${selected.id}-notes`} value={selected.notes} label="Current-state notes" />}
        </div>
        <nav className="tile-pagination" aria-label="Manual event pages">
          <button type="button" disabled={page === 0} onClick={() => navigate(() => { setPage(page - 1); setStep(0) })}>Previous event</button>
          <span>Event {page + 1} of {list.events.length}</span>
          <button type="button" disabled={page === list.events.length - 1} onClick={() => navigate(() => { setPage(page + 1); setStep(0) })}>Next event</button>
        </nav>
        <div className="tile-pagination">
          <button type="button" onClick={() => navigate(() => { const { id, ...draft } = selected; setEditor({ id, draft }); setStep(0) })}>Edit manual event</button>
          <button type="button" disabled={busy} onClick={() => { setWriteError(''); setConfirmation(selected.id) }}>Delete manual event</button>
        </div>
        {confirmation === selected.id && <div role="dialog" aria-label="Confirm manual event deletion" aria-describedby={`${prefix}-delete`}>
          <p id={`${prefix}-delete`}>Delete this manual {selected.sport} event, {selected.startDate} to {selected.endDate}?</p>
          <button type="button" disabled={busy} onClick={() => void write('DELETE')}>Confirm delete</button>
          <button type="button" onClick={() => navigate(() => {})}>Keep event</button>
        </div>}
      </article> : list && <p>No manual events yet.</p>}
    </>}
  </section>
}
