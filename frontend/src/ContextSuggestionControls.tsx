import { useLayoutEffect, useRef, useState } from 'react'
import type { Intake } from './ContextIntakeClient'

type ContextPayload = { category: string; observedOn: string | null; applicableFrom: string | null; applicableUntil: string | null; applicableFromMode: string; applicableUntilMode: string; content: string }
type Recurrence = { frequency: 'one_off' | 'daily' | 'weekly'; interval: number | null; endMode: 'bounded' | 'open' | 'unknown'; untilDate: string | null }
type CommitmentPayload = { label: string; kind: 'commitment' | 'availability' | null; startsLocal: string | null; endsLocal: string | null; timeZone: string | null; recurrence: Recurrence | null }
export type TypedSuggestion = { kind: 'context'; sourceStart: number; sourceEnd: number; sourceExcerpt: string; context: ContextPayload; commitment: null; unresolvedFields: string[] } |
  { kind: 'commitment'; sourceStart: number; sourceEnd: number; sourceExcerpt: string; context: null; commitment: CommitmentPayload; unresolvedFields: string[] }
export type SuggestionView = { status: 'available' | 'unavailable'; draft: TypedSuggestion[]; questions: string[]; provenanceLabel?: string }
export type SuggestionLoader = (note: Intake, signal: AbortSignal) => Promise<SuggestionView>

function fail(): never { throw new Error('Invalid saved suggestions') }
const obj = (v: unknown): Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v) ? v as Record<string, unknown> : fail()
const keys = (o: Record<string, unknown>, expected: string[]) => { if (Object.keys(o).length !== expected.length || Object.keys(o).some(k => !expected.includes(k))) fail() }
const str = (v: unknown, max = 2000) => { if (typeof v !== 'string' || new TextEncoder().encode(v).length > max) fail(); return v }
const nullable = (v: unknown, max = 2000) => v === null ? null : str(v, max)
const date = (v: unknown) => { if (v === null) return null; const s = str(v, 32); if (!/^\d{4}-\d{2}-\d{2}$/.test(s) || !Number.isFinite(Date.parse(`${s}T00:00:00Z`)) || new Date(`${s}T00:00:00Z`).toISOString().slice(0, 10) !== s) fail(); return s }
const localDateTime = (v: unknown) => { if (v === null) return null; const s = str(v, 32); if (!/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$/.test(s) || !date(s.slice(0, 10))) fail(); return s }
const unresolvedAllowed = ['observedOn','applicableFrom','applicableUntil','startsLocal','endsLocal','timeZone','kind','recurrence','recurrence.interval','recurrence.end']
function parseSuggestion(value: unknown, note: Intake): TypedSuggestion {
  const s = obj(value); keys(s, ['kind','sourceStart','sourceEnd','sourceExcerpt','context','commitment','unresolvedFields'])
  if (!Number.isInteger(s.sourceStart) || !Number.isInteger(s.sourceEnd) || Number(s.sourceStart) < 0 || Number(s.sourceEnd) <= Number(s.sourceStart) || Number(s.sourceEnd) > note.content.length) fail()
  const excerpt = str(s.sourceExcerpt,16000); if (!excerpt || note.content.slice(Number(s.sourceStart), Number(s.sourceEnd)) !== excerpt) fail()
  if (!Array.isArray(s.unresolvedFields) || s.unresolvedFields.some(v => typeof v !== 'string' || !unresolvedAllowed.includes(v)) || new Set(s.unresolvedFields).size !== s.unresolvedFields.length) fail()
  const unresolved = s.unresolvedFields as string[]
  if (s.kind === 'context' && s.commitment === null) {
    const c = obj(s.context); keys(c, ['category','observedOn','applicableFrom','applicableUntil','applicableFromMode','applicableUntilMode','content'])
    if (!['restriction','symptom','goal','preference','feedback','note'].includes(str(c.category)) || !['specified','open','unknown'].includes(str(c.applicableFromMode)) || !['specified','open','unknown'].includes(str(c.applicableUntilMode))) fail()
    const context: ContextPayload = { category: str(c.category), observedOn: date(c.observedOn), applicableFrom: date(c.applicableFrom), applicableUntil: date(c.applicableUntil),
      applicableFromMode: str(c.applicableFromMode), applicableUntilMode: str(c.applicableUntilMode), content: str(c.content, 2000) }
    if (context.applicableFrom && context.applicableUntil && context.applicableFrom > context.applicableUntil) fail()
    if ((context.observedOn === null) !== unresolved.includes('observedOn')) fail()
    for (const [field, value, mode] of [['applicableFrom', context.applicableFrom, context.applicableFromMode], ['applicableUntil', context.applicableUntil, context.applicableUntilMode]] as const) {
      if ((mode === 'specified') !== (value !== null) || (mode === 'unknown') !== unresolved.includes(field) || mode === 'open' && unresolved.includes(field)) fail()
    }
    if (unresolved.some(f => !['observedOn','applicableFrom','applicableUntil'].includes(f))) fail()
    return { kind: 'context', sourceStart: Number(s.sourceStart), sourceEnd: Number(s.sourceEnd), sourceExcerpt: excerpt, context, commitment: null, unresolvedFields: unresolved }
  }
  if (s.kind === 'commitment' && s.context === null) {
    const c = obj(s.commitment); keys(c, ['label','kind','startsLocal','endsLocal','timeZone','recurrence'])
    const kind = c.kind === null ? null : (typeof c.kind==='string' && ['commitment','availability'].includes(c.kind) ? c.kind as 'commitment'|'availability' : fail())
    let recurrence: Recurrence | null = null
    if (c.recurrence !== null) {
      const r = obj(c.recurrence); keys(r, ['frequency','interval','endMode','untilDate'])
      if (typeof r.frequency!=='string' || !['one_off','daily','weekly'].includes(r.frequency) || typeof r.endMode!=='string' || !['bounded','open','unknown'].includes(r.endMode) || !(r.interval === null || Number.isInteger(r.interval) && Number(r.interval) >= 1 && Number(r.interval) <= 366)) fail()
      recurrence = { frequency: r.frequency as Recurrence['frequency'], interval: r.interval as number|null, endMode: r.endMode as Recurrence['endMode'], untilDate: date(r.untilDate) }
      if((recurrence.endMode==='bounded')!==(recurrence.untilDate!==null)) fail()
      if(recurrence.frequency==='one_off' && (recurrence.interval!==null || recurrence.endMode!=='open')) fail()
    }
    const commitment: CommitmentPayload = { label: str(c.label, 160), kind, startsLocal: localDateTime(c.startsLocal), endsLocal: localDateTime(c.endsLocal), timeZone: nullable(c.timeZone, 100), recurrence }
    if (commitment.timeZone !== null) { try { new Intl.DateTimeFormat('en', { timeZone: commitment.timeZone }).format() } catch { fail() } }
    if (commitment.startsLocal && commitment.endsLocal && commitment.startsLocal >= commitment.endsLocal) fail()
    const vals: Record<string, unknown> = { kind: commitment.kind, startsLocal: commitment.startsLocal, endsLocal: commitment.endsLocal, timeZone: commitment.timeZone, recurrence: commitment.recurrence }
    for (const f of ['kind','startsLocal','endsLocal','timeZone','recurrence']) if ((vals[f] === null) !== unresolved.includes(f)) fail()
    if (unresolved.some(f => !['kind','startsLocal','endsLocal','timeZone','recurrence','recurrence.interval','recurrence.end'].includes(f))) fail()
    if ((commitment.recurrence?.interval === null) !== unresolved.includes('recurrence.interval') && commitment.recurrence !== null && commitment.recurrence.frequency !== 'one_off') fail()
    if ((commitment.recurrence?.endMode === 'unknown') !== unresolved.includes('recurrence.end') && commitment.recurrence !== null) fail()
    if(commitment.recurrence===null && unresolved.some(f=>f.startsWith('recurrence.'))) fail()
    if(commitment.recurrence?.frequency==='one_off' && unresolved.some(f=>f.startsWith('recurrence.'))) fail()
    if(commitment.recurrence?.untilDate && commitment.startsLocal && commitment.recurrence.untilDate<commitment.startsLocal.slice(0,10)) fail()
    return { kind: 'commitment', sourceStart: Number(s.sourceStart), sourceEnd: Number(s.sourceEnd), sourceExcerpt: excerpt, context: null, commitment, unresolvedFields: unresolved }
  }
  return fail()
}
function parseResponse(value: unknown, note: Intake): SuggestionView {
  const v = obj(value); keys(v, ['intakeId','intakeRevision','status','executionId','draft'])
  if (v.intakeId !== note.intakeId || v.intakeRevision !== note.revision || typeof v.status!=='string' || !['available','unavailable'].includes(v.status)) fail()
  if (v.status === 'unavailable') { if (v.executionId !== null || v.draft !== null) fail(); return { status: 'unavailable', draft: [], questions: [] } }
  if (typeof v.executionId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v.executionId) || v.draft === null) fail()
  return parseSuggestionDraft(v.draft, note)
}

/** Shared draft checks; the production execution envelope is still checked separately. */
export function parseSuggestionDraft(value: unknown, note: Intake): SuggestionView {
  const raw = obj(value); keys(raw, ['profile','intakeId','intakeRevision','suggestions','questions'])
  if (raw.profile !== 'context-intake-suggestion-v1' || raw.intakeId !== note.intakeId || raw.intakeRevision !== note.revision || !Array.isArray(raw.suggestions) || raw.suggestions.length > 20 || !Array.isArray(raw.questions) || raw.questions.length > 8) fail()
  if (new TextEncoder().encode(JSON.stringify(raw)).length > 12 * 1024) fail()
  const questions = raw.questions.map(q => { const o = obj(q); keys(o, ['text']); return str(o.text, 2000) })
  const suggestions = raw.suggestions.map(s => parseSuggestion(s, note))
  if (suggestions.some(s => s.unresolvedFields.length > 0) && questions.length === 0) fail()
  return { status: 'available', draft: suggestions, questions }
}

class SavedSuggestionsUnavailable extends Error {}
const loadSavedSuggestions: SuggestionLoader = async (note, signal) => {
  const response = await fetch(`/api/context-intakes/${note.intakeId}/suggestions?expectedRevision=${note.revision}`, { method: 'GET', credentials: 'same-origin', cache: 'no-store', signal })
  if (response.status === 404) throw new SavedSuggestionsUnavailable()
  if (!response.ok) throw new Error('unavailable')
  return parseResponse(await response.json() as unknown, note)
}

export function ContextSuggestionControls({ note, onEdit, loadSuggestions = loadSavedSuggestions }: {
  note: Intake; csrfToken: string; onEdit: (suggestion: TypedSuggestion) => void; loadSuggestions?: SuggestionLoader
}) {
  const [view, setView] = useState<SuggestionView | null>(null), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const active = useRef<AbortController | null>(null), generation = useRef(0)
  // Reset before inspection can be clicked; a delayed mount effect must not erase its result.
  useLayoutEffect(() => { generation.current++; active.current?.abort(); active.current = null; setView(null); setError(''); setBusy(false); return () => { generation.current++; active.current?.abort(); active.current = null } }, [note.intakeId, note.revision, note.content, loadSuggestions])
  async function inspect() {
    active.current?.abort(); const controller = new AbortController(), token = ++generation.current; active.current = controller
    setBusy(true); setError(''); setView(null)
    try {
      const parsed = await loadSuggestions(note, controller.signal)
      if (token === generation.current && !controller.signal.aborted) setView(parsed)
    } catch (cause) { if (token === generation.current && !controller.signal.aborted) { setView(null); setError(cause instanceof SavedSuggestionsUnavailable ? 'Saved suggestions are unavailable. Use the manual fallback to review the original note.' : 'Could not inspect saved suggestions. Use the manual fallback to review the original note.') } }
    finally { if (token === generation.current) { setBusy(false); active.current = null } }
  }
  return <section aria-label="Saved suggestion inspection">
    <button type="button" disabled={busy} onClick={() => void inspect()}>Inspect saved suggestions (read only)</button>
    {busy && <p role="status">Inspecting saved suggestions…</p>}
    {error && <p role="alert">{error}</p>}
    {view?.status === 'unavailable' && <p>Suggestions are unavailable. Use the manual fallback to review the original note.</p>}
    {view?.status === 'available' && <div><p>Read-only suggestions for source revision {note.revision}; not staged or confirmed.</p>
      {view.provenanceLabel && <p aria-label="Acquisition provenance">{view.provenanceLabel}</p>}
      <p>Attributed source: {note.authorAttribution} ({note.sourceCategory}); observed {note.observedOn}, recorded {note.recordedUtc}. Attribution is preserved from the saved note, not verified by a model.</p>
      {view.draft.map((item, i) => <article key={i}><h4>{item.kind} suggestion</h4><p>Original source excerpt: {note.content.slice(item.sourceStart, item.sourceEnd)}</p>
        <p>Proposed draft: {item.kind === 'context' ? item.context.content : item.commitment.label}</p>
        <p>Unresolved fields: {item.unresolvedFields.length ? item.unresolvedFields.join(', ') : 'none'}</p>
        <button type="button" onClick={() => onEdit(item)}>Edit suggestion</button></article>)}
      {view.questions.map((q, i) => <p key={i}>Question: {q}</p>)}
    </div>}
  </section>
}
