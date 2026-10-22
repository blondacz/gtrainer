import type { AuthoredPlanRequest, PlanAuthor, PlannedSession, PlannedTarget } from './PlanClient'

export const blankPlan = (): AuthoredPlanRequest => ({title:'',author:{authorKind:'user',authorAttribution:''},sessions:[],policy:null})
const numeric = (value: string) => value==='' ? Number.NaN : Number(value)
const shown = (value: number) => Number.isFinite(value) ? value : ''
const targetKinds = {
  duration: ['seconds','moving'], distance: ['metres','session_total'], average_heart_rate: ['beats/minute','session_average'],
  intervals_training_load: ['Intervals.icu load','intervals_session_load'], reported_effort: ['scale_points','reported'],
} as const
const freshTarget = (metric: PlannedTarget['metric']): PlannedTarget => ({metric,unit:targetKinds[metric][0],basis:targetKinds[metric][1],
  minimum:Number.NaN,maximum:Number.NaN,effortScale:metric==='reported_effort' ? {
    scaleId:crypto.randomUUID(),name:'',minimum:Number.NaN,maximum:Number.NaN,step:Number.NaN,higherMeansMoreEffort:true,
  } : null})

function AuthorFields({value,onChange,disabled}: {value:PlanAuthor;onChange:(next:PlanAuthor)=>void;disabled:boolean}) {
  return <>
    <label>Attributed author kind<select disabled={disabled} value={value.authorKind}
      onChange={e=>onChange({...value,authorKind:e.target.value as PlanAuthor['authorKind']})}>
      <option value="user">User-authored</option><option value="coach">Coach-authored, as entered by user</option></select></label>
    <label>Author attribution<input required maxLength={160} disabled={disabled} value={value.authorAttribution}
      onChange={e=>onChange({...value,authorAttribution:e.target.value})}/></label>
  </>
}

function TargetFields({value,onChange,disabled}: {value:PlannedTarget;onChange:(next:PlannedTarget)=>void;disabled:boolean}) {
  const maximum=value.metric==='duration' ? 2678400 : value.metric==='distance' ? 10000000 : value.metric==='average_heart_rate' ? 300 :
    value.metric==='intervals_training_load' ? 10000 : value.effortScale?.maximum
  const minimum=value.metric==='duration' ? 1 : value.metric==='reported_effort' ? value.effortScale?.minimum : 0
  return <>
    <label>Target metric<select disabled={disabled} value={value.metric} onChange={e=>onChange(freshTarget(e.target.value as PlannedTarget['metric']))}>
      {Object.keys(targetKinds).map(key=><option key={key} value={key}>{key.replaceAll('_',' ')}</option>)}</select></label>
    <p>Unit: {value.unit}. Basis: {value.basis}. HR is not interval intensity; load is not reported effort.</p>
    {value.metric==='duration' && <label>Duration measurement basis<select disabled={disabled} value={value.basis}
      onChange={e=>onChange({...value,basis:e.target.value})}><option value="moving">Moving</option><option value="elapsed">Elapsed</option></select></label>}
    {(['minimum','maximum'] as const).map(key=><label key={key}>Target {key}<input type="number" required disabled={disabled}
      step={value.metric==='duration' ? 1 : 'any'} min={Number.isFinite(minimum) ? minimum : undefined} max={Number.isFinite(maximum) ? maximum : undefined}
      value={shown(value[key])} onChange={e=>onChange({...value,[key]:numeric(e.target.value)})}/></label>)}
    {value.effortScale && <fieldset><legend>Explicit reported-effort scale</legend>
      <p>Scale identity: {value.effortScale.scaleId}. Higher values mean more reported effort. A new definition needs a new identity; equal names do not imply compatibility.</p>
      <label>Scale name<input required maxLength={160} disabled={disabled} value={value.effortScale.name}
        onChange={e=>onChange({...value,effortScale:{...value.effortScale!,name:e.target.value}})}/></label>
      {(['minimum','maximum','step'] as const).map(key=><label key={key}>Scale {key}<input type="number" step="any" required disabled={disabled}
        value={shown(value.effortScale![key])} onChange={e=>onChange({...value,effortScale:{...value.effortScale!,[key]:numeric(e.target.value)}})}/></label>)}
      <button type="button" disabled={disabled} onClick={()=>onChange({...value,effortScale:{...value.effortScale!,scaleId:crypto.randomUUID()}})}>Use a new effort-scale identity</button>
    </fieldset>}
  </>
}

function SessionFields({value,onChange,disabled}: {value:PlannedSession;onChange:(next:PlannedSession)=>void;disabled:boolean}) {
  const updateSchedule=(next:Partial<PlannedSession['schedule']>)=>onChange({...value,schedule:{...value.schedule,...next}})
  const updatePermission=(next:Partial<PlannedSession['permissions']>)=>onChange({...value,permissions:{...value.permissions,...next}})
  return <>
    <p>Stable session ID: {value.sessionId}</p><AuthorFields value={value.author} onChange={author=>onChange({...value,author})} disabled={disabled}/>
    <label>Sport<input required maxLength={80} disabled={disabled} value={value.sport} onChange={e=>onChange({...value,sport:e.target.value})}/></label>
    <label>Session objective<input required maxLength={160} disabled={disabled} value={value.objective} onChange={e=>onChange({...value,objective:e.target.value})}/></label>
    <label>Time precision<select disabled={disabled} value={value.schedule.kind} onChange={e=>onChange({...value,
      schedule:{kind:e.target.value as 'date_only'|'timed',date:e.target.value==='date_only' ? '' : null,
        startsLocal:e.target.value==='timed' ? '' : null,endsLocal:e.target.value==='timed' ? '' : null,timeZone:value.schedule.timeZone,startOffset:null,endOffset:null},
      permissions:{...value.permissions,move:null}})}><option value="date_only">Date only</option><option value="timed">Exact local start and end</option></select></label>
    <label>Session timezone<input required placeholder="Europe/Prague" maxLength={80} disabled={disabled} value={value.schedule.timeZone}
      onChange={e=>updateSchedule({timeZone:e.target.value,startOffset:null,endOffset:null})}/></label>
    {value.schedule.kind==='date_only' ? <label>Planned local date<input type="date" required disabled={disabled} value={value.schedule.date ?? ''}
      onChange={e=>updateSchedule({date:e.target.value})}/></label> : <>
      {(['startsLocal','endsLocal'] as const).map(key=><label key={key}>{key==='startsLocal' ? 'Scheduled start' : 'Scheduled end'}<input type="datetime-local" required disabled={disabled}
        value={value.schedule[key] ?? ''} onChange={e=>updateSchedule({[key]:e.target.value,[key==='startsLocal' ? 'startOffset' : 'endOffset']:null})}/></label>)}
      {(['startOffset','endOffset'] as const).map(key=><label key={key}>{key==='startOffset' ? 'Start UTC offset' : 'End UTC offset'} (if ambiguous)<input disabled={disabled}
        placeholder="+02:00" maxLength={6} value={value.schedule[key] ?? ''} onChange={e=>updateSchedule({[key]:e.target.value || null})}/></label>)}
      <p>DST gaps are refused. Repeated local times require an explicit valid offset. Wall-clock end is not inferred from moving duration.</p>
    </>}
    <label>Attributed session notes (optional)<textarea rows={2} maxLength={4000} disabled={disabled} value={value.notes ?? ''}
      onChange={e=>onChange({...value,notes:e.target.value || null})}/></label>
    <p>Notes do not grant permissions, create restrictions or automatically enter a model packet.</p>
    {value.targets.map((target,index)=><fieldset key={index}><legend>Target {index+1}</legend>
      <TargetFields value={target} disabled={disabled} onChange={next=>onChange({...value,targets:value.targets.map((row,i)=>i===index ? next : row)})}/>
      <button type="button" disabled={disabled} onClick={()=>onChange({...value,targets:value.targets.filter((_,i)=>i!==index)})}>Remove target</button>
    </fieldset>)}
    <button type="button" disabled={disabled || value.targets.length>=6} onClick={()=>onChange({...value,targets:[...value.targets,freshTarget('duration')]})}>Add supported target</button>
    <fieldset><legend>Explicit amendment permissions — all denied unless enabled</legend>
      <label><input type="checkbox" disabled={disabled} checked={value.permissions.move!==null}
        onChange={e=>updatePermission({move:e.target.checked ? {earliestStart:'',latestStart:''} : null})}/>Permit moving this session within a window</label>
      {value.permissions.move && (['earliestStart','latestStart'] as const).map(key=><label key={key}>{key==='earliestStart' ? 'Earliest permitted start' : 'Latest permitted start'}
        <input type={value.schedule.kind==='date_only' ? 'date' : 'datetime-local'} required disabled={disabled} value={value.permissions.move![key]}
          onChange={e=>updatePermission({move:{...value.permissions.move!,[key]:e.target.value}})}/></label>)}
      <label><input type="checkbox" disabled={disabled} checked={value.permissions.reduceDuration!==null}
        onChange={e=>updatePermission({reduceDuration:e.target.checked ? {basis:'moving',minimumSeconds:Number.NaN,maximumSeconds:Number.NaN} : null})}/>Permit duration reduction within explicit bounds</label>
      {value.permissions.reduceDuration && <>
        <label>Reduction duration basis<select disabled={disabled} value={value.permissions.reduceDuration.basis} onChange={e=>updatePermission({reduceDuration:{...value.permissions.reduceDuration!,basis:e.target.value as 'moving'|'elapsed'}})}>
          <option value="moving">Moving</option><option value="elapsed">Elapsed</option></select></label>
        {(['minimumSeconds','maximumSeconds'] as const).map(key=><label key={key}>{key==='minimumSeconds' ? 'Minimum permitted duration (seconds)' : 'Maximum permitted duration (seconds)'}
          <input type="number" min={1} max={2678400} step={1} required disabled={disabled} value={shown(value.permissions.reduceDuration![key])}
            onChange={e=>updatePermission({reduceDuration:{...value.permissions.reduceDuration!,[key]:numeric(e.target.value)}})}/></label>)}
        <p>Requires a matching duration target. Both range endpoints must fit the permission bounds. A later reduction cannot increase load or alter calendar occupancy.</p>
      </>}
      <label><input type="checkbox" disabled={disabled} checked={value.permissions.omit} onChange={e=>updatePermission({omit:e.target.checked})}/>Permit omission with audit history</label>
    </fieldset>
    <label><input type="checkbox" disabled={disabled} checked={value.omitted} onChange={e=>onChange({...value,omitted:e.target.checked})}/>This authored session is already omitted</label>
  </>
}

export function PlanFields({value,onChange,disabled}: {value:AuthoredPlanRequest;onChange:(next:AuthoredPlanRequest)=>void;disabled:boolean}) {
  return <>
    <label>Plan title<input required maxLength={160} disabled={disabled} value={value.title} onChange={e=>onChange({...value,title:e.target.value})}/></label>
    <fieldset><legend>Plan authorship</legend><AuthorFields value={value.author} disabled={disabled} onChange={author=>onChange({...value,author})}/></fieldset>
    <p>Coach authorship is attribution entered by the user, not verified guidance. This is an authored plan, not an event, goal or model-generated initial plan.</p>
    <label><input type="checkbox" disabled={disabled} checked={value.policy!==null} onChange={e=>onChange({...value,
      policy:e.target.checked ? {adaptationTimeZone:'',horizonDays:Number.NaN,importGraceSeconds:Number.NaN} : null})}/>Configure adaptation horizon and import grace</label>
    {value.policy && <fieldset><legend>Explicit plan policy</legend>
      <label>Adaptation timezone<input required disabled={disabled} maxLength={80} value={value.policy.adaptationTimeZone}
        onChange={e=>onChange({...value,policy:{...value.policy!,adaptationTimeZone:e.target.value}})}/></label>
      <label>Future horizon (dates)<input type="number" required min={1} max={366} step={1} disabled={disabled} value={shown(value.policy.horizonDays)}
        onChange={e=>onChange({...value,policy:{...value.policy!,horizonDays:numeric(e.target.value)}})}/></label>
      <label>Import grace (seconds)<input type="number" required min={0} max={31622400} step={1} disabled={disabled} value={shown(value.policy.importGraceSeconds)}
        onChange={e=>onChange({...value,policy:{...value.policy!,importGraceSeconds:numeric(e.target.value)}})}/></label>
      <p>Date-only amendments close at the earlier local midnight in the session/adaptation zones. Unmatched timing uses next local date or explicit scheduled end plus grace; it never means confirmed skipped.</p>
    </fieldset>}
    {value.sessions.map((session,index)=><fieldset key={session.sessionId}><legend>Authored session {index+1}</legend>
      <SessionFields value={session} disabled={disabled} onChange={next=>onChange({...value,sessions:value.sessions.map(row=>row.sessionId===session.sessionId ? next : row)})}/>
      <button type="button" disabled={disabled} onClick={()=>onChange({...value,sessions:value.sessions.filter(row=>row.sessionId!==session.sessionId)})}>Remove session from new revision</button>
    </fieldset>)}
    <p>Removing a session edits only the new authored revision; previous revisions retain it. Editing an existing session preserves its identity.</p>
    <button type="button" disabled={disabled || value.sessions.length>=1000} onClick={()=>onChange({...value,sessions:[...value.sessions,{
      sessionId:crypto.randomUUID(),author:{...value.author},sport:'',objective:'',schedule:{kind:'date_only',date:'',startsLocal:null,endsLocal:null,timeZone:'',startOffset:null,endOffset:null},
      targets:[],notes:null,permissions:{move:null,reduceDuration:null,omit:false},omitted:false,
    }]})}>Add authored session</button>
  </>
}
