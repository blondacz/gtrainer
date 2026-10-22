import {object,commitmentDefinitionFrom,sameValue,type CommitmentDefinition} from './ContextIntakeClient'
import {recordFrom,realDate,type ContextRecord} from './ContextFields'
import {planRefFrom,planUuid,type PlanRef} from './PlanClient'
import {sessionFindingFrom,type Session} from './PlanReconciliationClient'
import {signalRuleFrom,signalDayFrom,signalSupportFrom,workloadReportFrom,type SignalRule,type SignalDay,type SignalSupportDay,type WorkloadReport} from './AdaptationRulesClient'
import {dateCount} from './ScopedReviewTypes'

export type AdaptationReason={reasonId:string;revision:number;kind:string;condition:string;provenance:string[]}
export type ConstraintChange={eventId:string;beforeContext:ContextRecord|null;afterContext:ContextRecord|null;beforeCommitment:CommitmentDefinition|null;afterCommitment:CommitmentDefinition|null}
export type SignalFact={rule:SignalRule;current:SignalDay|null;baselineDays:SignalSupportDay[]}
export type AdaptationFacts={workKey:string;family:{plan:PlanRef;horizonDays:number;timeZone:string;policyOwnerId:string};horizon:{oldest:string;newest:string};
  sessions:Session[];workload:WorkloadReport[];signals:SignalFact[];constraintChanges:ConstraintChange[];reasons:AdaptationReason[];version:'adaptation_facts_v2';limitation:string}
const invalid=():never=>{throw Error('Invalid adaptation findings')}
const hash=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v)
const integer=(v:unknown,min:number,max:number):v is number=>typeof v==='number'&&Number.isSafeInteger(v)&&v>=min&&v<=max
function list<T>(v:unknown,max:number,parse:(v:unknown)=>T):T[]{if(!Array.isArray(v)||v.length>max)return invalid();return v.map(parse)}
const unique=(values:string[])=>new Set(values).size===values.length
function provenance(v:unknown):string {
  if(typeof v!=='string'||v.length>200)return invalid()
  const [kind,id,revision,...rest]=v.split('/')
  if(rest.length)return invalid()
  if(kind==='activity'||kind==='constraint_event'){if(!hash(id)||revision!==undefined)return invalid()}
  else if(kind==='plan_session'||kind==='workload_rule'){if(!planUuid(id)||revision!==undefined)return invalid()}
  else if(kind==='planned_target'){if(!planUuid(id)||!/^\d$/.test(revision??'')||Number(revision)>5)return invalid()}
  else if(kind==='signal_rule'||kind==='effort_report'){if(!planUuid(id)||!/^\d{1,4}$/.test(revision??'')||!integer(Number(revision),1,1000))return invalid()}
  else return invalid()
  return v
}
export function adaptationFactsFrom(v:unknown):AdaptationFacts {
  if(new TextEncoder().encode(JSON.stringify(v)).length>32_768)return invalid()
  const f=object(v),family=object(f.family),plan=planRefFrom(family.plan),horizon=object(f.horizon)
  if(f.version!=='adaptation_facts_v2'||f.limitation!=='factual_preparation_only_no_amendment_decision_no_medical_clearance'||!hash(f.workKey)||
    !integer(family.horizonDays,1,366)||family.policyOwnerId!==plan.planId||typeof family.timeZone!=='string'||
    typeof horizon.oldest!=='string'||!realDate(horizon.oldest)||typeof horizon.newest!=='string'||!realDate(horizon.newest)||dateCount(horizon.oldest,horizon.newest)!==family.horizonDays)return invalid()
  try{new Intl.DateTimeFormat('en',{timeZone:family.timeZone})}catch{return invalid()}
  const sessions=list(f.sessions,1000,sessionFindingFrom)
  if(!unique(sessions.map(s=>s.sessionId))||sessions.some(s=>s.targetChecks.some(c=>!sameValue(c.plan,plan))||
    s.confirmation!==null&&(s.confirmation.sessionId!==s.sessionId||s.confirmation.plan.planId!==plan.planId||s.confirmation.plan.revision>plan.revision)))return invalid()
  const workload=list(f.workload,20,workloadReportFrom)
  const signals=list(f.signals,20,(v):SignalFact=>{
    const signal=object(v),rule=signalRuleFrom(signal.rule),current=signal.current===null?null:signalDayFrom(signal.current),baselineDays=list(signal.baselineDays,366,signalSupportFrom)
    if(!unique(baselineDays.map(d=>d.date))||baselineDays.some((d,i)=>i>0&&d.date<=baselineDays[i-1].date)||
      current===null&&baselineDays.length>0||current!==null&&(!rule.enabled||!rule.configuration||baselineDays.length!==rule.configuration.baseline.trailingDays||
        current.baselineExpectedDays!==rule.configuration.baseline.trailingDays||baselineDays[0]?.date!==current.baselineOldest||baselineDays.at(-1)?.date!==current.baselineNewest||
        baselineDays.some(d=>d.date<current.baselineOldest||d.date>current.baselineNewest)))return invalid()
    return {rule,current,baselineDays}
  })
  if(!unique(workload.map(r=>r.rule.ruleId))||!unique(signals.map(s=>s.rule.ruleId)))return invalid()
  const constraintChanges=list(f.constraintChanges,1000,(v):ConstraintChange=>{
    const change=object(v)
    if(!hash(change.eventId))return invalid()
    const beforeContext=change.beforeContext===null?null:recordFrom(change.beforeContext),afterContext=change.afterContext===null?null:recordFrom(change.afterContext)
    const beforeCommitment=change.beforeCommitment===null?null:commitmentDefinitionFrom(change.beforeCommitment),afterCommitment=change.afterCommitment===null?null:commitmentDefinitionFrom(change.afterCommitment)
    if(!beforeContext&&!afterContext&&!beforeCommitment&&!afterCommitment||beforeContext&&afterContext&&beforeContext.contextId!==afterContext.contextId)return invalid()
    return {eventId:change.eventId,beforeContext,afterContext,beforeCommitment,afterCommitment}
  })
  const reasons=list(f.reasons,1000,(v):AdaptationReason=>{
    const reason=object(v)
    if(!hash(reason.reasonId)||!integer(reason.revision,1,Number.MAX_SAFE_INTEGER)||!['confirmed_skip','planned_range','extra_workload','changed_constraint','personal_signal'].includes(String(reason.kind))||
      !['active','clear','provisional','unevaluable','needs_confirmation'].includes(String(reason.condition)))return invalid()
    const sources=list(reason.provenance,1000,provenance);if(!unique(sources))return invalid()
    return {reasonId:reason.reasonId,revision:reason.revision,kind:reason.kind as string,condition:reason.condition as string,provenance:sources}
  })
  if(!unique(reasons.map(r=>r.reasonId))||!unique(constraintChanges.map(c=>c.eventId)))return invalid()
  return {workKey:f.workKey,family:{plan,horizonDays:family.horizonDays,timeZone:family.timeZone,policyOwnerId:plan.planId},
    horizon:{oldest:horizon.oldest,newest:horizon.newest},sessions,workload,signals,constraintChanges,reasons,version:'adaptation_facts_v2',limitation:f.limitation}
}
