import {useEffect,useRef,useState,type FormEvent} from 'react'
import {intakeRequest,PrivateIntakeError,sameValue,object} from './ContextIntakeClient'
import {realDate} from './ContextFields'
import {dateCount} from './ScopedReviewTypes'
import {signalRulesFrom,workloadRulesFrom,signalReportFrom,workloadReportFrom,signalConfigurationFrom,workloadConfigurationFrom,
  type AdaptationRule,type SignalConfiguration,type WorkloadConfiguration,type SignalReport,type WorkloadReport} from './AdaptationRulesClient'
import {SignalRuleEvidence,WorkloadRuleEvidence} from './AdaptationRuleEvidence'

type Props={csrfToken:string;disabled:boolean;revision:number;onStateLockChange?:(locked:boolean)=>void}
type SignalDraft=Omit<{[K in keyof SignalConfiguration]:string},'selector'|'baseline'>&{
  selector:{[K in keyof SignalConfiguration['selector']]:string};baseline:{[K in keyof SignalConfiguration['baseline']]:string}}
type WorkloadDraft={[K in keyof WorkloadConfiguration]:string}
const blankSignal=():SignalDraft=>({timeZone:'',selector:{field:'',unit:'',intermediary:'',upstreamOrigin:'',method:'',basis:''},
  baseline:{trailingDays:'',minimumSamples:'',minimumCoverage:'',maximumAgeDays:''},direction:'',boundaryType:'',triggerMagnitude:'',resetMagnitude:'',
  triggerPersistenceDays:'',resetPersistenceDays:'',cooldownSeconds:''})
const blankWorkload=():WorkloadDraft=>({aggregation:'',metric:'',unit:'',basis:'',sport:'',source:'',upstreamOrigin:'',timeZone:'',windowDays:'',comparator:'',threshold:''})
function number(value:string){if(!value.trim())throw Error();const result=Number(value);if(!Number.isFinite(result))throw Error();return result}

export function AdaptationRuleControls({csrfToken,disabled,revision,onStateLockChange}:Props){
  const [kind,setKind]=useState<'signal'|'workload'>('signal'),[rules,setRules]=useState<AdaptationRule[]>([])
  const [selected,setSelected]=useState(''),[enabled,setEnabled]=useState(false),[loaded,setLoaded]=useState(false)
  const [uncertain,setUncertain]=useState(false),[dirty,setDirty]=useState(false),[busy,setBusy]=useState(false)
  const [signal,setSignal]=useState<SignalDraft>(blankSignal),[workload,setWorkload]=useState<WorkloadDraft>(blankWorkload)
  const [oldest,setOldest]=useState(''),[newest,setNewest]=useState(''),[report,setReport]=useState<SignalReport|WorkloadReport|null>(null)
  const [error,setError]=useState(''),[status,setStatus]=useState('')
  const epoch=useRef(0),abort=useRef<AbortController|null>(null),writing=useRef(false)
  useEffect(()=>{onStateLockChange?.(uncertain||dirty||busy)},[uncertain,dirty,busy,onStateLockChange])
  useEffect(()=>()=>onStateLockChange?.(false),[onStateLockChange])
  useEffect(()=>{
    epoch.current++;abort.current?.abort();writing.current=false
    setRules([]);setSelected('');setEnabled(false);setLoaded(false);setUncertain(false);setDirty(false);setReport(null);setBusy(false)
    setSignal(blankSignal());setWorkload(blankWorkload());setOldest('');setNewest('');setError('');setStatus('')
    return()=>{epoch.current++;abort.current?.abort()}
  },[csrfToken,revision,kind])
  const live=(e:number,c:AbortController)=>epoch.current===e&&!c.signal.aborted
  function begin(){abort.current?.abort();const c=new AbortController(),e=++epoch.current;abort.current=c;setBusy(true);setError('');return {c,e}}
  async function load(){
    if(disabled||busy||writing.current)return
    const {c,e}=begin();setRules([]);setReport(null);setLoaded(false)
    try{
      const raw=await intakeRequest(`/api/plans/${kind}-rules`,csrfToken,'GET',undefined,c.signal)
      const parsed=kind==='signal'?signalRulesFrom(raw):workloadRulesFrom(raw)
      if(live(e,c)){
        setRules(parsed);setLoaded(true);setDirty(false);setUncertain(false);setSelected('');setEnabled(false)
        setSignal(blankSignal());setWorkload(blankWorkload());setStatus('Authoritative rules loaded; unsaved draft discarded. Adapter verification remains separate.')
      }
    }catch{if(live(e,c))setError('Rule list unavailable or invalid. Reload authoritative rules before writing.')}
    finally{if(live(e,c))setBusy(false)}
  }
  function selectRule(id:string){
    setSelected(id);setDirty(false);setReport(null);setError('');setStatus('');setSignal(blankSignal());setWorkload(blankWorkload())
    const rule=rules.find(x=>x.ruleId===id);setEnabled(rule?.enabled??false)
    if(!rule?.configuration)return
    if(kind==='signal'){
      const c=signalConfigurationFrom(rule.configuration)
      setSignal({timeZone:c.timeZone,selector:{...c.selector},baseline:{trailingDays:String(c.baseline.trailingDays),minimumSamples:String(c.baseline.minimumSamples),
        minimumCoverage:String(c.baseline.minimumCoverage),maximumAgeDays:String(c.baseline.maximumAgeDays)},direction:c.direction,boundaryType:c.boundaryType,
        triggerMagnitude:String(c.triggerMagnitude),resetMagnitude:String(c.resetMagnitude),triggerPersistenceDays:String(c.triggerPersistenceDays),
        resetPersistenceDays:String(c.resetPersistenceDays),cooldownSeconds:String(c.cooldownSeconds)})
    }else{
      const c=workloadConfigurationFrom(rule.configuration)
      setWorkload({...c,windowDays:String(c.windowDays),threshold:String(c.threshold),upstreamOrigin:c.upstreamOrigin??''})
    }
  }
  function buildConfig(){
    if(kind==='signal')return signalConfigurationFrom({timeZone:signal.timeZone,selector:{...signal.selector,unit:signal.selector.field==='sleepSecs'?'seconds':'ms'},
      baseline:{trailingDays:number(signal.baseline.trailingDays),minimumSamples:number(signal.baseline.minimumSamples),minimumCoverage:number(signal.baseline.minimumCoverage),
        maximumAgeDays:number(signal.baseline.maximumAgeDays)},direction:signal.direction,boundaryType:signal.boundaryType,triggerMagnitude:number(signal.triggerMagnitude),
      resetMagnitude:number(signal.resetMagnitude),triggerPersistenceDays:number(signal.triggerPersistenceDays),resetPersistenceDays:number(signal.resetPersistenceDays),cooldownSeconds:number(signal.cooldownSeconds)})
    return workloadConfigurationFrom({...workload,windowDays:number(workload.windowDays),threshold:number(workload.threshold),upstreamOrigin:workload.upstreamOrigin||null})
  }
  async function save(event:FormEvent){
    event.preventDefault();if(disabled||busy||writing.current||uncertain||!loaded)return
    const prior=rules.find(r=>r.ruleId===selected)
    if(selected&&!prior||!selected&&rules.length>=20){setError('Rule selection or capacity changed. Load authoritative rules; no request was sent.');return}
    let configuration:SignalConfiguration|WorkloadConfiguration
    try{configuration=buildConfig()}catch{setError('Enter every required field with compatible units, explicit thresholds and valid reset hysteresis. No request was sent.');return}
    const body={operationId:crypto.randomUUID(),ruleId:prior?.ruleId??crypto.randomUUID(),expectedRevision:prior?.revision??0,enabled,configuration}
    if(body.expectedRevision>=1000||new TextEncoder().encode(JSON.stringify(body)).length>8192){setError('Rule revision or byte limit reached. No request was sent.');return}
    const {c,e}=begin();writing.current=true;setStatus('');setReport(null)
    try{
      const receipt=object(await intakeRequest(`/api/plans/${kind}-rules`,csrfToken,'POST',body,c.signal))
      const saved=(kind==='signal'?signalRulesFrom([receipt.rule]):workloadRulesFrom([receipt.rule]))[0]
      if(typeof receipt.replayed!=='boolean'||saved.ruleId!==body.ruleId||saved.revision!==body.expectedRevision+1||saved.enabled!==enabled||!sameValue(saved.configuration,configuration))throw Error()
      if(live(e,c)){
        setDirty(false);setLoaded(false);setRules([]);setSelected('');setStatus('Exact rule configuration receipt acknowledged. Load rules to inspect current state; the plan is unchanged.')
      }
    }catch(cause){if(live(e,c)){
      setUncertain(true);setLoaded(false);setRules([])
      setError(cause instanceof PrivateIntakeError&&cause.status===409?'Rule changed. Reload and review the authoritative revision before retrying.':
        'Save outcome unknown or response invalid. Load authoritative rules before another write; no automatic retry was made.')
    }}finally{if(live(e,c)){writing.current=false;setBusy(false)}}
  }
  async function inspect(){
    if(!loaded||!selected||busy||writing.current||disabled||uncertain||dirty)return
    if(kind==='signal'&&(!realDate(oldest)||!realDate(newest)||dateCount(oldest,newest)<1||dateCount(oldest,newest)>366)){setError('Choose 1–366 real evaluation dates.');return}
    const {c,e}=begin();setReport(null)
    try{
      const path=kind==='signal'?`/api/plans/signal-rules/${selected}/inspection?${new URLSearchParams({oldest,newest})}`:`/api/plans/workload-rules/${selected}/inspection`
      const raw=await intakeRequest(path,csrfToken,'GET',undefined,c.signal),parsed=kind==='signal'?signalReportFrom(raw):workloadReportFrom(raw)
      if(!sameValue(parsed.rule,rules.find(r=>r.ruleId===selected))||'query' in parsed&&(parsed.query.oldest!==oldest||parsed.query.newest!==newest))throw Error()
      if(live(e,c))setReport(parsed)
    }catch{if(live(e,c))setError('Inspection unavailable, invalid or the saved rule changed. Load current rules before inspecting again; no write or inference was requested.')}
    finally{if(live(e,c))setBusy(false)}
  }
  const blocked=disabled||busy||uncertain||!loaded
  const field=(label:string,value:string,onChange:(v:string)=>void,type='text',required=true)=><label>{label}<input type={type} step={type==='number'?'any':undefined} required={required} value={value} disabled={blocked}
    onChange={e=>{setDirty(true);setReport(null);onChange(e.target.value)}}/></label>
  const choice=(label:string,value:string,options:string[],onChange:(v:string)=>void)=><label>{label}<select required value={value} disabled={blocked}
    onChange={e=>{setDirty(true);setReport(null);onChange(e.target.value)}}><option value="">Select explicitly</option>{options.map(x=><option key={x}>{x}</option>)}</select></label>
  return <section aria-label="Adaptation rules"><h3>Adaptation rules</h3>
    <p>Configure explicit review thresholds, not medical cutoffs. New rules start disabled. Enabling a rule does not verify a source, admit inference or apply a plan amendment. No readiness score is inferred.</p>
    <p>Inspection is read-only and does not queue work or acknowledge cooldown. Scheduling requires separate enrollment. Loading rules discards the unsaved rule draft.</p>
    <label>Rule type<select value={kind} disabled={busy||disabled||dirty||uncertain} onChange={e=>setKind(e.target.value as 'signal'|'workload')}><option value="signal">Personal signal</option><option value="workload">Extra workload</option></select></label>
    <button type="button" disabled={busy||disabled} onClick={()=>void load()}>Load rules</button>
    {loaded&&<><label>Existing rule<select value={selected} disabled={blocked||dirty} onChange={e=>selectRule(e.target.value)}><option value="">Create new rule</option>
      {rules.map(r=><option key={r.ruleId} value={r.ruleId}>{r.ruleId} · revision {r.revision} · {r.enabled?'enabled':'disabled'}</option>)}</select></label>
      <form onSubmit={e=>void save(e)}><h4>{kind==='signal'?'Personal signal configuration':'Extra workload configuration'}</h4>
        <label><input type="checkbox" checked={enabled} disabled={blocked} onChange={e=>{setEnabled(e.target.checked);setDirty(true);setReport(null)}}/>Enable this configured rule explicitly</label>
        {kind==='signal'?<>
          {field('Time zone',signal.timeZone,v=>setSignal({...signal,timeZone:v}))}
          {choice('Signal field',signal.selector.field,['sleepSecs','hrv','hrvSDNN'],v=>setSignal({...signal,selector:{...signal.selector,field:v}}))}
          <p>Exact unit: {signal.selector.field?(signal.selector.field==='sleepSecs'?'seconds':'ms'):'select a field'}. Method and basis are desired selectors, not verification authority.</p>
          {(['intermediary','upstreamOrigin','method','basis'] as const).map(k=><span key={k}>{field({intermediary:'Intermediary',upstreamOrigin:'Upstream origin',method:'Method',basis:'Basis'}[k],signal.selector[k],v=>setSignal({...signal,selector:{...signal.selector,[k]:v}}))}</span>)}
          {(['trailingDays','minimumSamples','minimumCoverage','maximumAgeDays'] as const).map(k=><span key={k}>{field({trailingDays:'Baseline trailing days',minimumSamples:'Baseline minimum samples',minimumCoverage:'Baseline minimum coverage',maximumAgeDays:'Maximum evidence age days'}[k],signal.baseline[k],v=>setSignal({...signal,baseline:{...signal.baseline,[k]:v}}),'number')}</span>)}
          {choice('Direction',signal.direction,['low','high'],v=>setSignal({...signal,direction:v}))}
          {choice('Boundary type',signal.boundaryType,['absolute_limit','absolute_deviation','percent_deviation'],v=>setSignal({...signal,boundaryType:v}))}
          {(['triggerMagnitude','resetMagnitude','triggerPersistenceDays','resetPersistenceDays','cooldownSeconds'] as const).map(k=><span key={k}>{field({triggerMagnitude:'Trigger magnitude',resetMagnitude:'Reset magnitude',triggerPersistenceDays:'Trigger persistence days',resetPersistenceDays:'Reset persistence days',cooldownSeconds:'Cooldown seconds'}[k],signal[k],v=>setSignal({...signal,[k]:v}),'number')}</span>)}
        </>:<>
          {choice('Aggregation',workload.aggregation,['per_record','cumulative'],v=>setWorkload({...workload,aggregation:v}))}
          {choice('Metric',workload.metric,['duration','distance','intervals_training_load'],v=>setWorkload({...workload,metric:v,unit:'',basis:''}))}
          {choice('Unit',workload.unit,workload.metric==='duration'?['seconds']:workload.metric==='distance'?['metres']:workload.metric==='intervals_training_load'?['Intervals.icu load']:[],v=>setWorkload({...workload,unit:v}))}
          {choice('Workload basis',workload.basis,workload.metric==='duration'?['moving','elapsed']:workload.metric==='distance'?['session_total']:workload.metric==='intervals_training_load'?['intervals_session_load']:[],v=>setWorkload({...workload,basis:v}))}
          {field('Sport',workload.sport,v=>setWorkload({...workload,sport:v}))}{field('Source',workload.source,v=>setWorkload({...workload,source:v}))}
          {field('Workload upstream origin (optional)',workload.upstreamOrigin,v=>setWorkload({...workload,upstreamOrigin:v}),'text',false)}
          {field('Workload time zone',workload.timeZone,v=>setWorkload({...workload,timeZone:v}))}{field('Window days',workload.windowDays,v=>setWorkload({...workload,windowDays:v}),'number')}
          {choice('Comparator',workload.comparator,['greater_than','at_least'],v=>setWorkload({...workload,comparator:v}))}
          {field('Threshold',workload.threshold,v=>setWorkload({...workload,threshold:v}),'number')}
        </>}
        <button disabled={blocked||(!dirty&&selected!=='')}>Save rule configuration</button>
      </form>
      <h4>Read-only inspection</h4>
      {kind==='signal'&&<>{(['oldest','newest'] as const).map(k=><label key={k}>{k==='oldest'?'Oldest':'Newest'} evaluation date<input type="date" value={k==='oldest'?oldest:newest} disabled={blocked}
        onChange={e=>{(k==='oldest'?setOldest:setNewest)(e.target.value);setReport(null)}}/></label>)}</>}
      <button type="button" disabled={blocked||dirty||!selected} onClick={()=>void inspect()}>Inspect rule</button>
    </>}
    {report&&<div aria-label="Rule inspection"><p>Saved rule evaluation: {report.status}. Not an admission receipt or an applied amendment.</p>
      {'query' in report?<><p>Evaluation dates {report.query.oldest}–{report.query.newest}, as of {report.asOf}; upstream freshness {report.upstreamFreshness}. Records available at preparation, not reconstructed past knowledge; prior episode history before this evaluation is unknown.</p>
        <ul>{report.sourceStatus.map((source,index)=><li key={index}>{source.category}: {source.readStatus}; records {source.recordCount}, rejected {source.rejected}, incomplete {source.incomplete}; latest observed {source.latestObservedDate??'unknown'}, last successful read {source.lastSuccessUtc??'unknown'}. Upstream freshness {source.upstreamFreshness}.</li>)}</ul>
        <SignalRuleEvidence rule={report.rule} days={report.days} support={report.support}/></>:
        <WorkloadRuleEvidence report={report}/>}</div>}
    {error&&<p role="alert">{error}</p>}{status&&<p role="status">{status}</p>}
  </section>
}
