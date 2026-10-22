import { arrayFrom, object, sameValue } from './ContextIntakeClient'
import { planRefFrom, planScheduleFrom, planSelectionFrom, planUuid, plannedTargetFrom, type PlanRef, type PlannedTarget } from './PlanClient'
import { realDate } from './ContextFields'
import { utc, dateCount } from './ScopedReviewTypes'
import {activityFrom,type Activity} from './PlanActivity'
import {effortReportFrom,type EffortReport} from './ActivityEffortClient'
export type {Activity} from './PlanActivity'

export type Query={oldest:string;newest:string;timeZone:string}
export type Confirmation={confirmationId:string;plan:PlanRef;sessionId:string;action:'match'|'skip'|'clear';activity:Activity|null;recordedBy:string;recordedUtc:string}
export type TargetCheck={plan:PlanRef;sessionId:string;targetIndex:number;target:PlannedTarget;activityId:string|null;activityRevision:number|null;recordSha256:string|null;status:string;reason:string;actualValue:number|null;actualUnit:string|null;actualField:string|null;actualBasis:string|null;source:string|null;upstreamOrigin:string|null;effortReport:EffortReport|null;policy:string}
export type Session={sessionId:string;sport:string;objective:string;schedule:ReturnType<typeof planScheduleFrom>;state:string;basis:string;activityId:string|null;candidateActivityIds:string[];unmatchedAfter:string|null;confirmation:Confirmation|null;targetChecks:TargetCheck[]}
export type View={query:Query;asOf:string;selection:ReturnType<typeof planSelectionFrom>;digest:string;supportSha256:string;sessions:Session[];activities:{activity:Activity;state:string;sessionIds:string[]}[];coverage:{category:string;readStatus:string;upstreamFreshness:string}[];policy:string;limitation:string}
const invalid=():never=>{throw new Error('Invalid reconciliation response')}
const text=(v:unknown,max:number):v is string=>typeof v==='string'&&v.length>0&&v.length<=max
const digest=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v)
const integer=(v:unknown,min=0):v is number=>typeof v==='number'&&Number.isSafeInteger(v)&&v>=min
const enumeration=(v:unknown,values:string[]):v is string=>typeof v==='string'&&values.includes(v)
const zone=(v:unknown):v is string=>{if(!text(v,80))return false;try{new Intl.DateTimeFormat('en',{timeZone:v});return true}catch{return false}}
const states=['omitted','needs_confirmation','matched','confirmed_skipped','ambiguous','timing_unavailable','provisional_unmatched','awaiting_evidence']
const bases=['authored_omission','confirmation_evidence_changed','recorded_evidence_contradicts_confirmed_skip','user_confirmation','user_confirmation_timing_unverified','confirmation_collision','compatible_unique_evidence','competing_candidates_or_confirmed_ownership','plan_policy_required','local_evidence_absent_not_proof_of_skip','session_end_plus_grace_not_reached']

function targetCheckFrom(v:unknown):TargetCheck {
  const c=object(v),plan=planRefFrom(c.plan),target=plannedTargetFrom(c.target)
  if(!planUuid(c.sessionId)||!integer(c.targetIndex)||c.targetIndex>5||!(c.activityId===null||digest(c.activityId))||
    !(c.activityRevision===null||integer(c.activityRevision,1))||!(c.recordSha256===null||digest(c.recordSha256))||
    !enumeration(c.status,['unevaluable','below_range','within_range','above_range'])||
    !enumeration(c.reason,['match_unresolved','confirmation_evidence_changed','activity_evidence_unavailable','ambiguous_effort_report','measurement_missing','effort_report_withdrawn','effort_report_detached','effort_scale_mismatch','unsupported_source_basis','unit_mismatch','invalid_measurement','assessed'])||
    !(c.actualValue===null||typeof c.actualValue==='number'&&Number.isFinite(c.actualValue))||
    ![c.actualUnit,c.actualField,c.actualBasis,c.source,c.upstreamOrigin].every(v=>v===null||text(v,160))||c.policy!=='authored_inclusive_range_v1')return invalid()
  const effortReport=c.effortReport===null?null:effortReportFrom(c.effortReport)
  if(c.status!=='unevaluable'&&(c.reason!=='assessed'||c.actualValue===null||c.actualUnit!==target.unit||c.actualBasis!==target.basis||c.activityId===null||c.recordSha256===null))return invalid()
  if(c.status!=='unevaluable') {
    const value=c.actualValue as number,expected=value<target.minimum?'below_range':value>target.maximum?'above_range':'within_range'
    const field=target.metric==='duration'?(target.basis==='moving'?'movingTime':'elapsedTime'):target.metric==='distance'?'distance':target.metric==='average_heart_rate'?'averageHeartRate':target.metric==='intervals_training_load'?'intervalsTrainingLoad':'reportedEffort'
    if(c.actualField!==field||c.status!==expected||target.metric==='intervals_training_load'&&c.source!=='intervals.icu'||
      target.metric==='reported_effort'&&(!effortReport||!sameValue(effortReport.scale,target.effortScale)||c.source!=='authenticated_user'||effortReport.withdrawn||effortReport.detached||effortReport.value!==value||effortReport.activityId!==c.activityId||effortReport.activityRecordSha256!==c.recordSha256||effortReport.activityRevision!==c.activityRevision))return invalid()
    const maximum:Record<string,number>={duration:2678400,distance:10000000,average_heart_rate:300,intervals_training_load:100000}
    if(target.metric!=='reported_effort'&&(value<0||value>maximum[target.metric]))return invalid()
  }
  return {...c,plan,target,effortReport} as TargetCheck
}

export function confirmationFrom(v:unknown):Confirmation {
  const c=object(v),plan=planRefFrom(c.plan)
  if(!planUuid(c.confirmationId)||!planUuid(c.sessionId)||!enumeration(c.action,['match','skip','clear'])||
    c.recordedBy!=='authenticated_user'||!utc(c.recordedUtc))return invalid()
  const activity=c.activity===null?null:activityFrom(c.activity)
  if((c.action==='match')!==(activity!==null))return invalid()
  return {...c,plan,activity} as Confirmation
}
export function receiptFrom(v:unknown):{confirmation:Confirmation;replayed:boolean} {
  const r=object(v);if(typeof r.replayed!=='boolean')return invalid()
  return {confirmation:confirmationFrom(r.confirmation),replayed:r.replayed}
}
export function sessionFindingFrom(v:unknown):Session {
  const s=object(v)
  if(!planUuid(s.sessionId)||!text(s.sport,80)||!text(s.objective,160)||!enumeration(s.state,states)||!enumeration(s.basis,bases)||
    !(s.activityId===null||digest(s.activityId))||!Array.isArray(s.candidateActivityIds)||s.candidateActivityIds.length>30000||
    !s.candidateActivityIds.every(digest)||new Set(s.candidateActivityIds).size!==s.candidateActivityIds.length||!(s.unmatchedAfter===null||utc(s.unmatchedAfter)))return invalid()
  if(!Array.isArray(s.targetChecks)||s.targetChecks.length>6)return invalid()
  const targetChecks=s.targetChecks.map(targetCheckFrom)
  if(new Set(targetChecks.map(c=>c.targetIndex)).size!==targetChecks.length||targetChecks.some(c=>c.sessionId!==s.sessionId||
    c.activityId!==s.activityId||s.state!=='matched'&&c.status!=='unevaluable'))return invalid()
  return {...s,schedule:planScheduleFrom(s.schedule),confirmation:s.confirmation===null?null:confirmationFrom(s.confirmation),targetChecks} as Session
}
export function reconciliationFrom(v:unknown):View {
  if(new TextEncoder().encode(JSON.stringify(v)).length>2*1024*1024)return invalid()
  const r=object(v),q=object(r.query),selection=planSelectionFrom(r.selection)
  if(typeof q.oldest!=='string'||typeof q.newest!=='string'||!realDate(q.oldest)||!realDate(q.newest)||dateCount(q.oldest,q.newest)<1||dateCount(q.oldest,q.newest)>366||!zone(q.timeZone)||
    !utc(r.asOf)||!digest(r.digest)||!digest(r.supportSha256)||
    r.policy!=='one_to_one_exact_sport_verified_start_in_half_open_authored_interval_mutual_unique_v1'||
    r.limitation!=='current_corrected_evidence_not_past_knowledge;bounded_complete_local_support_not_upstream_completeness;extra_relative_to_selected_plan_not_unintended')return invalid()
  if(!Array.isArray(r.sessions)||r.sessions.length>1000||!Array.isArray(r.activities)||r.activities.length>50000||!Array.isArray(r.coverage)||r.coverage.length>2)return invalid()
  const sessions=arrayFrom(r.sessions,sessionFindingFrom)
  // The shared intake parser caps at 1,000; this endpoint admits complete support up to 50,000.
  const activities=r.activities.map((v:unknown)=>{
    const a=object(v)
    if(!enumeration(a.state,['base_plan_required','needs_confirmation','matched','extra_relative_to_selected_plan'])||
      !Array.isArray(a.sessionIds)||a.sessionIds.length>1000||!a.sessionIds.every(planUuid)||new Set(a.sessionIds).size!==a.sessionIds.length)return invalid()
    return {activity:activityFrom(a.activity),state:a.state,sessionIds:a.sessionIds as string[]}
  })
  const coverage=arrayFrom(r.coverage,v=>{
    const c=object(v)
    if(c.category!=='activities'||!text(c.readStatus,100)||!text(c.upstreamFreshness,100)||!integer(c.recordCount)||!integer(c.rejected)||!integer(c.incomplete)||
      !(c.lastAttemptUtc===null||utc(c.lastAttemptUtc))||!(c.lastSuccessUtc===null||utc(c.lastSuccessUtc))||
      !(c.latestObservedDate===null||typeof c.latestObservedDate==='string'&&realDate(c.latestObservedDate))||!(c.latestObservedAgeDays===null||typeof c.latestObservedAgeDays==='number'&&Number.isSafeInteger(c.latestObservedAgeDays)))return invalid()
    return {category:c.category,readStatus:c.readStatus,upstreamFreshness:c.upstreamFreshness}
  })
  if(new Set(sessions.map(s=>s.sessionId)).size!==sessions.length||new Set(activities.map(a=>a.activity.activityId)).size!==activities.length||new Set(coverage.map(c=>c.category)).size!==coverage.length)return invalid()
  const ids=new Set(activities.map(a=>a.activity.activityId))
  // Activity owners may lie outside this viewport: matching uses the whole selected plan.
  if(sessions.some(s=>s.candidateActivityIds.some(id=>!ids.has(id))||s.activityId!==null&&!ids.has(s.activityId)||s.confirmation!==null&&
    (s.confirmation.sessionId!==s.sessionId||s.confirmation.plan.planId!==selection.selected?.planId||s.confirmation.plan.revision>(selection.selected?.revision??0))))return invalid()
  if(sessions.some(s=>s.targetChecks.some(c=>c.sessionId!==s.sessionId||c.plan.planId!==selection.selected?.planId||c.plan.revision!==selection.selected?.revision||
    c.activityId!==s.activityId||s.state!=='matched'&&c.status!=='unevaluable')))return invalid()
  const identityById=new Map(activities.map(a=>[a.activity.activityId,a.activity]))
  if(sessions.some(s=>s.targetChecks.some(c=>c.activityId===null?(c.recordSha256!==null||c.activityRevision!==null):
    c.recordSha256!==identityById.get(c.activityId)?.recordSha256||c.activityRevision!==identityById.get(c.activityId)?.revision)))return invalid()
  if(!selection.selected&&sessions.length>0)return invalid()
  return {...r,query:q,selection,sessions,activities,coverage} as View
}
