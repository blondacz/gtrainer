import {useEffect,useRef,useState,type FormEvent} from 'react'
import {intakeRequest,PrivateIntakeError,sameValue} from './ContextIntakeClient'
import {effortHistoryFrom,effortReceiptFrom,type EffortOrigin,type EffortReport} from './ActivityEffortClient'
import {type EffortScale} from './PlanClient'
import {type Activity} from './PlanActivity'
import {realDate} from './ContextFields'

export function ActivityEffortControls({csrfToken,activity,scale,disabled,onChanged,onBusyChange,onUncertaintyChange,origin}: {
  csrfToken:string;activity:Activity;scale:EffortScale;disabled:boolean;onChanged:()=>void;
  onBusyChange?:(busy:boolean)=>void;onUncertaintyChange?:(uncertain:boolean)=>void;origin?:EffortOrigin
}) {
  const [history,setHistory]=useState<EffortReport[]>([]),[current,setCurrent]=useState<Activity|null>(null)
  const [inspected,setInspected]=useState(false),[busy,setBusy]=useState(false),[locked,setLocked]=useState(false)
  const [value,setValue]=useState(''),[date,setDate]=useState(''),[author,setAuthor]=useState('')
  const [error,setError]=useState(''),[message,setMessage]=useState('')
  const epoch=useRef(0),abort=useRef<AbortController|null>(null),writing=useRef(false)
  const identity=JSON.stringify({activity,scale,origin})
  useEffect(()=>{
    epoch.current++;abort.current?.abort();setHistory([]);setCurrent(null);setInspected(false);setBusy(false);setLocked(false)
    setValue('');setDate('');setAuthor('');setError('');setMessage('');writing.current=false;onBusyChange?.(false)
    return()=>{epoch.current++;abort.current?.abort();onBusyChange?.(false)}
  },[csrfToken,identity,onBusyChange])
  const live=(e:number,c:AbortController)=>epoch.current===e&&!c.signal.aborted
  const markBusy=(v:boolean)=>{setBusy(v);onBusyChange?.(v)}

  async function inspect() {
    if(writing.current||busy||disabled)return
    const c=new AbortController(),e=++epoch.current;abort.current?.abort();abort.current=c;writing.current=true;markBusy(true);setError('');setMessage('')
    try {
      const raw=await intakeRequest(`/api/plans/effort-reports?${new URLSearchParams({activityId:activity.activityId,scaleId:scale.scaleId})}`,csrfToken,'GET',undefined,c.signal)
      if(!live(e,c))return
      const h=effortHistoryFrom(raw)
      if(h.activityId!==activity.activityId||!sameValue(h.scale,scale))throw Error()
      setHistory(h.reports);setCurrent(h.currentActivity);setInspected(true);setLocked(false);onUncertaintyChange?.(false)
    } catch {if(live(e,c)){setHistory([]);setInspected(false);setLocked(true);setError('Effort history unavailable or invalid. Inspect again before writing.')}}
    finally {if(live(e,c)){writing.current=false;markBusy(false)}}
  }
  async function submit(action:'report'|'withdraw',ev?:FormEvent) {
    ev?.preventDefault();if(writing.current||!inspected||busy||disabled||locked)return
    const head=history.at(-1)
    if(action==='withdraw'&&(!head||head.withdrawn))return
    if(action==='report') {
      if(!current||!sameValue(current,activity)){setError('Recording identity changed or is unavailable. Inspect parent evidence again.');return}
      const n=Number(value),steps=(n-scale.minimum)/scale.step
      if(!value.trim()||!Number.isFinite(n)||!Number.isFinite(steps)||n<scale.minimum||n>scale.maximum||Math.abs(steps-Math.round(steps))>=1e-8||!realDate(date)||!author.trim()){setError('Enter an on-scale value, explicit real observation date and attribution.');return}
    }
    const body={operationId:crypto.randomUUID(),activityId:activity.activityId,scaleId:scale.scaleId,
      expectedReportId:head?.reportId??null,expectedRevision:head?.revision??0,expectedDetachmentGeneration:head?.detachmentGeneration??0,
      expectedActivityRevision:action==='report'?activity.revision:null,expectedRecordSha256:action==='report'?activity.recordSha256:null,
      action,value:action==='report'?Number(value):null,observedOn:action==='report'?date:null,authorAttribution:action==='report'?author:null,
      origin:action==='report'?(origin??null):null}
    const c=new AbortController(),e=++epoch.current;abort.current?.abort();abort.current=c;writing.current=true;markBusy(true);setError('');setMessage('')
    try {
      const raw=await intakeRequest('/api/plans/effort-reports',csrfToken,'POST',body,c.signal)
      if(!live(e,c))return
      const r=effortReceiptFrom(raw).report
      if(r.activityId!==body.activityId||!sameValue(r.scale,scale)||head&&r.reportId!==head.reportId||r.revision!==body.expectedRevision+1||
        r.previousRevision!==(head?.revision??null)||r.withdrawn!==(action==='withdraw')||r.detachmentGeneration!==body.expectedDetachmentGeneration)throw Error()
      if(action==='report'&&(r.detached||r.activityRevision!==activity.revision||r.activityRecordSha256!==activity.recordSha256||r.value!==body.value||r.observedOn!==date||r.authorAttribution!==author))throw Error()
      if(action==='withdraw'&&(!head||r.value!==head.value||r.observedOn!==head.observedOn||r.authorAttribution!==head.authorAttribution||
        r.activityRevision!==head.activityRevision||r.activityRecordSha256!==head.activityRecordSha256||r.detached!==head.detached))throw Error()
      setHistory([]);setInspected(false);setLocked(true);setMessage('Effort report acknowledged. Inspect effort history again for the current ledger.');onUncertaintyChange?.(false);onChanged()
    } catch(cause) {if(live(e,c)){setHistory([]);setInspected(false);setLocked(true);onUncertaintyChange?.(true);setError(cause instanceof PrivateIntakeError&&cause.status===409?
      'Effort history changed. Inspect again before another write.':'Outcome unknown or response invalid. Inspect authoritative effort history before another write; no retry was made.')}}
    finally {if(live(e,c)){writing.current=false;markBusy(false)}}
  }
  return <section aria-label="Activity effort"><h3>Reported effort</h3>
    <p>Explicit user-attributed evidence only; never inferred from HR, load or prose. Original reports survive import removal as detached, inactive history. Reimport never reactivates them without explicit reaffirmation.</p>
    <button type="button" disabled={busy||disabled} onClick={()=>void inspect()}>Inspect effort report</button>
    {inspected&&<><p>{scale.name} · {scale.scaleId} · range {scale.minimum}–{scale.maximum}, step {scale.step} · {current?'current recording available':'recording missing'}</p>
      {history.map((r,i)=><article key={`${r.reportId}-${r.revision}`}><p>Revision {r.revision} · {r.value} · observed {r.observedOn} · attributed to {r.authorAttribution} · recorded {r.recordedUtc} · {
        i!==history.length-1?'historical revision':r.withdrawn?'withdrawn':r.detached?'detached from imported recording':
          !current||r.activityRecordSha256!==current.recordSha256||r.activityRevision!==current.revision?'evidence changed; reaffirmation required':'current attributed report'
      } · {r.activityRecordSha256}</p></article>)}
      <form onSubmit={e=>void submit('report',e)}>
        <label>Effort value<input type="number" min={scale.minimum} max={scale.maximum} step={scale.step} value={value} disabled={busy||disabled||locked} onChange={e=>setValue(e.target.value)}/></label>
        <label>Observed on<input type="date" value={date} disabled={busy||disabled||locked} onChange={e=>setDate(e.target.value)}/></label>
        <label>Attribution<input value={author} maxLength={160} disabled={busy||disabled||locked} onChange={e=>setAuthor(e.target.value)}/></label>
        <button disabled={busy||disabled||locked||!current||!value||!date||!author}>Save/correct/reaffirm report</button>
      </form>
      {history.at(-1)&&!history.at(-1)?.withdrawn&&<button disabled={busy||disabled||locked} onClick={()=>void submit('withdraw')}>Withdraw active report</button>}
    </>}
    {error&&<p role="alert">{error}</p>}{message&&<p role="status">{message}</p>}
  </section>
}
