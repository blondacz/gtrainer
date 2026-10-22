import {useEffect,useRef,useState,type FormEvent} from 'react'
import {intakeRequest,PrivateIntakeError,sameValue} from './ContextIntakeClient'
import {realDate} from './ContextFields'
import {dateCount} from './ScopedReviewTypes'
import {receiptFrom,reconciliationFrom,type Query,type View,type Session} from './PlanReconciliationClient'
import {ActivityEffortControls} from './ActivityEffortControls'
import {PlannedTargetFinding} from './PlannedTargetFinding'

export function PlanReconciliationControls({csrfToken,disabled,revision,onStateLockChange}:{csrfToken:string;disabled:boolean;revision:number;onStateLockChange?:(locked:boolean)=>void}) {
  const [query,setQuery]=useState<Query>({oldest:'',newest:'',timeZone:''}),[view,setView]=useState<View|null>(null)
  const [selected,setSelected]=useState<Record<string,string>>({}),[busy,setBusy]=useState(false),[locked,setLocked]=useState(false)
  const [error,setError]=useState(''),[message,setMessage]=useState('')
  const [effortBusy,setEffortBusy]=useState(false)
  const [effortUncertainty,setEffortUncertainty]=useState<Record<string,boolean>>({})
  const effortUncertain=Object.values(effortUncertainty).some(Boolean)
  const generation=useRef(0),abort=useRef<AbortController|null>(null),writing=useRef(false)
  useEffect(()=>{onStateLockChange?.(busy||locked||effortBusy||effortUncertain)},[busy,locked,effortBusy,effortUncertain,onStateLockChange])
  useEffect(()=>()=>onStateLockChange?.(false),[onStateLockChange])
  useEffect(()=>{
    generation.current++;abort.current?.abort();setView(null);setSelected({});setLocked(false);setBusy(false);setError('');setMessage('');writing.current=false;setEffortUncertainty({})
    return()=>{generation.current++;abort.current?.abort()}
  },[csrfToken,revision])
  const current=(epoch:number,c:AbortController)=>generation.current===epoch&&!c.signal.aborted
  async function load(q:Query,c:AbortController):Promise<View> {
    const v=reconciliationFrom(await intakeRequest(`/api/plans/reconciliation?${new URLSearchParams(q)}`,csrfToken,'GET',undefined,c.signal))
    if(!sameValue(v.query,q))throw Error()
    return v
  }
  async function inspect(e:FormEvent) {
    e.preventDefault();if(writing.current||busy||effortBusy||effortUncertain||disabled)return
    if(!realDate(query.oldest)||!realDate(query.newest)||dateCount(query.oldest,query.newest)<1||dateCount(query.oldest,query.newest)>366||!query.timeZone){setError('Choose 1–366 real dates and an explicit timezone.');return}
    abort.current?.abort();const c=new AbortController(),epoch=++generation.current;abort.current=c
    setBusy(true);setView(null);setSelected({});setError('');setMessage('')
    try {const v=await load(query,c);if(current(epoch,c)){setView(v);setLocked(false)}}
    catch {if(current(epoch,c))setError('Reconciliation unavailable or invalid. No result was accepted.')}
    finally {if(current(epoch,c))setBusy(false)}
  }
  function possible(s:Session) {
    return view?.activities.filter(a=>a.activity.sport===s.sport&&!(a.activity.startInstant&&Date.parse(a.activity.startInstant)>Date.parse(view.asOf))).map(a=>a.activity)??[]
  }
  async function confirm(s:Session,action:'match'|'skip'|'clear') {
    if(!view||writing.current||busy||effortBusy||effortUncertain||disabled||locked||!view.selection.selected)return
    const activity=action==='match'?possible(s).find(a=>a.activityId===selected[s.sessionId]):null
    if(action==='match'&&!activity)return
    if(action==='skip'&&(s.confirmation?.action==='match'||s.candidateActivityIds.length>0||!s.unmatchedAfter||Date.parse(s.unmatchedAfter)>Date.parse(view.asOf)))return
    const body={confirmationId:crypto.randomUUID(),plan:view.selection.selected,sessionId:s.sessionId,action,activityId:activity?.activityId??null,
      expectedSelectionGeneration:view.selection.generation,expectedConfirmationId:s.confirmation?.confirmationId??null,expectedDigest:view.digest,asOf:view.asOf,query:view.query}
    const c=new AbortController(),epoch=++generation.current;abort.current?.abort();abort.current=c;writing.current=true
    setBusy(true);setError('');setMessage('');let acknowledged=false
    try {
      const raw=await intakeRequest('/api/plans/reconciliation/confirmations',csrfToken,'POST',body,c.signal)
      if(!current(epoch,c))return
      const receipt=receiptFrom(raw),saved=receipt.confirmation
      if(!sameValue(saved.plan,body.plan)||saved.confirmationId!==body.confirmationId||saved.sessionId!==s.sessionId||saved.action!==action||!sameValue(saved.activity,activity))throw Error()
      acknowledged=true
      const next=await load(view.query,c)
      if(current(epoch,c)){setView(next);setSelected({});setMessage('Confirmation recorded. The authored plan is unchanged; latest matching has been inspected.')}
    } catch(cause) {
      if(current(epoch,c)){
        setView(null);setLocked(true)
        setError(cause instanceof PrivateIntakeError&&cause.status===409?'Evidence, selection or confirmation changed. Inspect again before confirming.':
          acknowledged?'Confirmation acknowledged but inspection failed. Inspect again; no retry was made.':'Confirmation outcome unknown or response invalid. Inspect authoritative matching before another write; no retry was made.')
      }
    } finally {if(current(epoch,c)){writing.current=false;setBusy(false)}}
  }
  async function refreshAfterEffort() {
    if(!view)return
    const c=new AbortController(),epoch=++generation.current;abort.current?.abort();abort.current=c;setBusy(true);setError('')
    try {const latest=await load(view.query,c);if(current(epoch,c)){setView(latest);setSelected({});setMessage('Effort report acknowledged. Latest facts inspected; no plan amendment was applied.')}}
    catch {if(current(epoch,c)){setView(null);setLocked(true);setError('Effort acknowledged but current matching unavailable. Inspect reconciliation again; no write was retried.')}}
    finally {if(current(epoch,c))setBusy(false)}
  }
  return <section aria-label="Plan reconciliation"><h3>Plan reconciliation</h3>
    <p>Read-only inspection until an explicit confirmation. Matching is one-to-one, exact sport, with a verified start within the authored time window. Ties remain ambiguous. Missing records never prove skipped training.</p>
    <p>Unknown instants are not converted to UTC. A manual stable link is attributed to you, not a verified time match. Extra recordings are relative to the selected plan, not necessarily unintended.</p>
    <p>Target checks use exact authored inclusive ranges, with no invented tolerance. Moving and elapsed duration remain distinct; average HR and load are not effort or interval intensity. Unsupported stream prescriptions and free-text notes are not evaluated.</p>
    <form onSubmit={e=>void inspect(e)}>{(['oldest','newest','timeZone'] as const).map(k=><label key={k}>{k==='oldest'?'From':k==='newest'?'Through':'Explicit timezone'}
      <input aria-label={k==='oldest'?'Reconciliation from':k==='newest'?'Reconciliation through':'Reconciliation timezone'} type={k==='timeZone'?'text':'date'} value={query[k]} disabled={busy||effortBusy||effortUncertain||disabled}
        onChange={e=>{setQuery({...query,[k]:e.target.value});setView(null);setSelected({})}}/></label>)}
      <button disabled={busy||effortBusy||effortUncertain||disabled}>Inspect reconciliation</button>
    </form>
    {view&&<><p>Selected base {view.selection.selected?`${view.selection.selected.planId} revision ${view.selection.selected.revision}`:'none'} · generation {view.selection.generation} · as of {view.asOf} · policy {view.policy}</p>
      <p>Coverage/status: {view.coverage.map(x=>`${x.category}: ${x.readStatus}`).join('; ')||'unknown'}; upstream completeness unknown. Current corrected records, not past knowledge. Displayed dates use the query timezone for verified instants; unknown instants are browsed by their source date.</p>
      {view.sessions.map(s=><article key={s.sessionId}><h4>{s.sport} · {s.objective}</h4>
        <p>{s.schedule.kind==='date_only'?s.schedule.date:`${s.schedule.startsLocal}–${s.schedule.endsLocal}`} {s.schedule.timeZone} · {s.state} ({s.basis}) · {s.sessionId}</p>
        <p>Candidates: {s.candidateActivityIds.length?s.candidateActivityIds.join(', '):'none'} · {s.activityId??'unlinked'} · provisional boundary {s.unmatchedAfter??'unavailable; policy required'}</p>
        {s.confirmation&&<p>Saved {s.confirmation.action}, attributed to {s.confirmation.recordedBy} at {s.confirmation.recordedUtc}, plan revision {s.confirmation.plan.revision}.</p>}
        {s.targetChecks.map(check=><div key={check.targetIndex}><PlannedTargetFinding check={check}/>
          {s.state==='matched'&&check.target.effortScale&&view.selection.selected&&view.activities.find(a=>a.activity.activityId===s.activityId)?.activity&&<ActivityEffortControls
            key={`${s.sessionId}-${check.target.effortScale.scaleId}-${view.digest}`} csrfToken={csrfToken} activity={view.activities.find(a=>a.activity.activityId===s.activityId)!.activity} scale={check.target.effortScale}
            disabled={busy||disabled||locked||effortBusy||(effortUncertain&&!effortUncertainty[`${s.sessionId}-${check.target.effortScale.scaleId}`])}
            onBusyChange={setEffortBusy} onUncertaintyChange={uncertain=>setEffortUncertainty(previous=>({...previous,[`${s.sessionId}-${check.target.effortScale!.scaleId}`]:uncertain}))} onChanged={()=>void refreshAfterEffort()}
            origin={{plan:view.selection.selected,sessionId:s.sessionId,expectedSelectionGeneration:view.selection.generation,expectedDigest:view.digest,asOf:view.asOf,query:view.query}}/>}
        </div>)}
        <label>Explicit recording for {s.sessionId}<select aria-label={`Candidate for ${s.sessionId}`} disabled={busy||effortBusy||effortUncertain||disabled||locked||s.state==='omitted'} value={selected[s.sessionId]??''} onChange={e=>setSelected({...selected,[s.sessionId]:e.target.value})}>
          <option value="">Choose recording explicitly</option>{possible(s).map(a=><option key={a.activityId} value={a.activityId}>{a.activityId} · {a.startLocal} · {a.timeContext}</option>)}
        </select></label>
        <button disabled={busy||effortBusy||effortUncertain||disabled||locked||s.state==='omitted'||!possible(s).some(a=>a.activityId===selected[s.sessionId])} onClick={()=>void confirm(s,'match')}>Confirm recording match</button>
        <button disabled={busy||effortBusy||effortUncertain||disabled||locked||s.state==='omitted'||s.confirmation?.action==='match'||s.candidateActivityIds.length>0||!s.unmatchedAfter||Date.parse(s.unmatchedAfter)>Date.parse(view.asOf)} onClick={()=>void confirm(s,'skip')}>Confirm skipped</button>
        {s.confirmation&&s.confirmation.action!=='clear'&&<button disabled={busy||effortBusy||effortUncertain||disabled||locked} onClick={()=>void confirm(s,'clear')}>Clear saved confirmation</button>}
      </article>)}
      {view.activities.map(a=><p key={a.activity.activityId}>Activity {a.activity.sport} · {a.activity.startLocal} · {a.activity.startInstant??'instant unknown; needs confirmation'} · {a.state} · {a.activity.activityId}</p>)}
    </>}
    {effortUncertain&&<p role="alert">An effort write requires authoritative reinspection. Use its Inspect effort report control before leaving or changing plan/matching state.</p>}
    {error&&<p role="alert">{error}</p>}{message&&<p role="status">{message}</p>}
  </section>
}
