import { useEffect, useRef, useState, type FormEvent } from 'react'
import { arrayFrom, commitmentReferenceFrom, intakeRequest, object } from './ContextIntakeClient'
import { realDate } from './ContextFields'
import { dateCount, utc as validUtc } from './ScopedReviewTypes'
import { planCalendarFrom, type AuthoredPlanRequest, type PlanCalendar } from './PlanClient'
import type {AmendmentDraftOverlay} from './PlanAmendmentDiff'

type Occurrence = { occurrenceId:string; label:string; kind:string; startsLocal:string; endsLocal:string; timeZone:string; authoritative:boolean; flags:string[] }
type Recorded = { key:string; source:string; sourceRecordId:string; sport:string; startLocal:string; startInstant:string|null; timeContext:string }
type CalendarData = { plans:PlanCalendar; occurrences:Occurrence[]; needsReconfirmation:number; blocked:boolean; recorded:Recorded[] }
const local = (value:unknown): value is string => typeof value==='string' && value.length<=40 && realDate(value.slice(0,10)) &&
  /^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d/.test(value)
const bounded = <T,>(value:unknown,parse:(row:unknown)=>T):T[] => {
  if(!Array.isArray(value) || value.length>50_000) throw new Error('Calendar response exceeds bounds')
  return value.map(parse)
}

function sourcesFrom(plans:PlanCalendar, commitmentsBody:unknown, historyBody:unknown):CalendarData {
  const commitments=object(commitmentsBody), history=object(historyBody)
  if(commitments.oldest!==plans.from || commitments.newest!==plans.until || commitments.timeZone!==plans.timeZone ||
    typeof commitments.constraintsBlocked!=='boolean' || typeof commitments.coverage!=='string') throw new Error('Mismatched calendar response')
  const stale=arrayFrom(commitments.needsReconfirmation,commitmentReferenceFrom)
  if(commitments.constraintsBlocked!==(stale.length>0)) throw new Error('Invalid constraint coverage')
  const occurrences=bounded(commitments.occurrences,value=>{
    const row=object(value)
    commitmentReferenceFrom(row.reference)
    if(typeof row.occurrenceId!=='string' || row.occurrenceId.length>100 || typeof row.label!=='string' || row.label.length>160 ||
      !['commitment','availability'].includes(String(row.kind)) || !local(row.startsLocal) || !local(row.endsLocal) || typeof row.timeZone!=='string' ||
      !validUtc(row.startsUtc) || !validUtc(row.endsUtc) || Date.parse(row.endsUtc)<=Date.parse(row.startsUtc) || typeof row.authoritative!=='boolean' ||
      !Array.isArray(row.flags) || !row.flags.every(flag=>typeof flag==='string')) throw new Error('Invalid calendar occurrence')
    return row as Occurrence
  })
  const recorded=bounded(history.activities,value=>{
    const row=object(value)
    if(typeof row.source!=='string' || row.source.length>80 || typeof row.sourceRecordId!=='string' || row.sourceRecordId.length>160 ||
      typeof row.sport!=='string' || row.sport.length>80 || !local(row.startLocal) || !(row.startInstant===null || validUtc(row.startInstant)) ||
      typeof row.timeContext!=='string' || row.timeContext.length>160 || row.startLocal.slice(0,10)<plans.from || row.startLocal.slice(0,10)>plans.until) throw new Error('Invalid recorded activity')
    return {...row,key:JSON.stringify([row.source,row.sourceRecordId])} as Recorded
  })
  if(new Set(occurrences.map(row=>row.occurrenceId)).size!==occurrences.length || new Set(recorded.map(row=>row.key)).size!==recorded.length) throw new Error('Duplicate calendar identity')
  return {plans,occurrences,needsReconfirmation:stale.length,blocked:commitments.constraintsBlocked,recorded}
}

/** Inert local projections: never reconciles, syncs, submits inference or writes event copies. */
export function PlanCalendarControls({csrfToken,draft,amendmentOverlay=null,refreshRevision=0,onReviewInvalidated,disabled=false}:
  {csrfToken:string;draft:AuthoredPlanRequest|null;amendmentOverlay?:AmendmentDraftOverlay|null;refreshRevision?:number;onReviewInvalidated?:()=>void;disabled?:boolean}) {
  const [query,setQuery]=useState({oldest:'',newest:'',timeZone:''}), [data,setData]=useState<CalendarData|null>(null)
  const [busy,setBusy]=useState(false), [error,setError]=useState(''), [page,setPage]=useState(0)
  const controller=useRef<AbortController|null>(null)
  const loaded=useRef(false),previousRevision=useRef(refreshRevision),readAgain=useRef<()=>void>(()=>{})
  useEffect(()=>()=>controller.current?.abort(),[])
  useEffect(()=>{
    if(previousRevision.current===refreshRevision)return
    previousRevision.current=refreshRevision
    controller.current?.abort();setData(null)
    if(loaded.current)readAgain.current()
  },[refreshRevision])
  async function load(event?:FormEvent) {
    event?.preventDefault()
    if(busy||disabled) return
    onReviewInvalidated?.()
    if(!realDate(query.oldest) || !realDate(query.newest) || dateCount(query.oldest,query.newest)<1 || dateCount(query.oldest,query.newest)>366) {
      setError('Choose 1–366 real calendar dates and an explicit timezone.');return
    }
    const abort=new AbortController();controller.current=abort
    setBusy(true);setData(null);setError('');setPage(0)
    try {
      const params=new URLSearchParams(query), historyParams=new URLSearchParams({oldest:query.oldest,newest:query.newest})
      const [planBody,commitments,history]=await Promise.all([
        intakeRequest(`/api/plans/calendar?${params}`,csrfToken,'GET',undefined,abort.signal),
        intakeRequest(`/api/commitments/calendar?${params}`,csrfToken,'GET',undefined,abort.signal),
        intakeRequest(`/api/history?${historyParams}`,csrfToken,'GET',undefined,abort.signal),
      ])
      const plans=planCalendarFrom(planBody)
      if(plans.from!==query.oldest || plans.until!==query.newest || plans.timeZone!==query.timeZone) throw new Error()
      if(!abort.signal.aborted) {setData(sourcesFrom(plans,commitments,history));loaded.current=true}
    } catch {if(!abort.signal.aborted) setError('Calendar data unavailable or invalid. No constraint coverage or completion is inferred.')}
    finally {if(!abort.signal.aborted) setBusy(false)}
  }
  readAgain.current=()=>{void load()}
  const maximum=Math.max(data?.plans.items.length ?? 0,data?.occurrences.length ?? 0,data?.recorded.length ?? 0)
  const slice=<T,>(values:T[])=>values.slice(page*50,(page+1)*50)
  return <section aria-label="Authored plan and recorded calendar"><h3>Local calendar projection</h3>
    <p>Authored sessions, commitments/availability, imported recorded activity and legacy events are distinct. These independent reads are not a reconciled review snapshot. External calendar coverage is unavailable, not empty.</p>
    <form onSubmit={event=>void load(event)}>
      {(['oldest','newest','timeZone'] as const).map(key=><label key={key}>{key==='oldest' ? 'Calendar from' : key==='newest' ? 'Calendar through' : 'Calendar timezone'}
        <input type={key==='timeZone' ? 'text' : 'date'} required disabled={busy||disabled} value={query[key]} maxLength={key==='timeZone' ? 80 : undefined}
          onChange={e=>{setQuery({...query,[key]:e.target.value});setData(null);loaded.current=false;onReviewInvalidated?.()}}/></label>)}
      <button type="submit" disabled={busy||disabled}>{busy ? 'Loading local projections…' : 'Inspect local calendar'}</button>
    </form>
    {data && <>
      <p>Plan cutoff {data.plans.asOf}. Plan entries use overlapping half-open instants in {data.plans.timeZone}; imported activity retains source-local dates. Source freshness/completeness is unknown. {data.plans.limitation}</p>
      <h4>Selected authored sessions ({data.plans.items.length})</h4>
      {!data.plans.selection.selected && <p>No selected base plan. An event or goal is not a substitute.</p>}
      {slice(data.plans.items).map(item=><p key={item.sessionId}>Authored session: {item.sport} · {item.objective} · {item.schedule.date ?? `${item.schedule.startsLocal} – ${item.schedule.endsLocal}`} ({item.schedule.timeZone})
        {item.omitted ? ' · omitted, not completed' : ''} · plan revision {item.plan.revision} · {item.sessionId} · {item.amendmentEligible ? 'within configured timing/permissions; constraints still require validation' : 'not eligible for amendment consideration'}</p>)}
      <h4>Confirmed calendar occurrences ({data.occurrences.length})</h4>
      <p>{data.blocked ? `${data.needsReconfirmation} stale item(s): constraint coverage blocked, not free time.` : 'Confirmed local coverage only; not a promise of complete external availability.'}</p>
      {slice(data.occurrences).map(item=><p key={item.occurrenceId}>{item.kind}: {item.label} · {item.startsLocal} – {item.endsLocal} ({item.timeZone})
        {item.authoritative ? '' : ' · non-authoritative, reconfirmation required'} · {item.flags.join(', ')}</p>)}
      <h4>Imported recorded activities ({data.recorded.length})</h4>
      {slice(data.recorded).map(item=><p key={item.key}>Recorded activity: {item.sport} · {item.startLocal} · {item.source}/{item.sourceRecordId} · {item.timeContext}
        {item.startInstant===null ? ' · instant unverified; completion/matching is not inferred' : Date.parse(item.startInstant)>Date.parse(data.plans.asOf) ? ' · future instant, not completed evidence' : ' · recorded past start, not an automatic plan match'}</p>)}
      <p>Legacy event goals remain in the Events view below; no session, commitment or recorded activity is copied into an event.</p>
      {maximum>50 && <div><button type="button" disabled={page===0} onClick={()=>setPage(current=>current-1)}>Previous calendar page</button>
        <span> Page {page+1}; up to 50 of each category per page. </span>
        <button type="button" disabled={(page+1)*50>=maximum} onClick={()=>setPage(current=>current+1)}>Next calendar page</button></div>}
    </>}
    {draft && <aside aria-label="Unsaved plan draft overlay"><h4>Unsaved draft overlay — not authoritative</h4>
      <p>{draft.title || 'Untitled draft'} · {draft.sessions.length} session(s). Saving/selecting has not occurred. No proposal is approved.</p>
      {draft.sessions.slice(0,50).map(session=><p key={session.sessionId}>Draft only: {session.sport} · {session.objective} · {session.schedule.date ?? session.schedule.startsLocal} · {session.sessionId}</p>)}
      {draft.sessions.length>50 && <p>First 50 draft entries shown here; inspect every draft in the editor above before saving.</p>}
    </aside>}
    {amendmentOverlay&&<aside aria-label="Pending amendment draft overlay"><h4>Draft proposal—not applied</h4>
      <p>Proposal {amendmentOverlay.proposalId}; base revision {amendmentOverlay.basePlan.revision}; expires {amendmentOverlay.expiresUtc}. Active authored entries above are unchanged. Exact review is separate below.</p>
      {amendmentOverlay.selected.map(candidate=><p key={candidate.candidateId}>Draft only: {candidate.operation} · {candidate.sessionId} ·
        Before {candidate.before.schedule.date??candidate.before.schedule.startsLocal} ({candidate.before.schedule.timeZone});
        After {candidate.after.schedule.date??candidate.after.schedule.startsLocal} ({candidate.after.schedule.timeZone}); omitted {String(candidate.after.omitted)}.</p>)}
    </aside>}
    {error && <p role="alert">{error}</p>}
  </section>
}
