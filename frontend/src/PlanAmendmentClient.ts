import { commitmentFrom, object, sameValue, viewFrom, intakeRequest } from './ContextIntakeClient'
import { planRefFrom, planScheduleFrom, plannedSessionFrom, type PlanRef, type PlanPolicy, type PlanSchedule, type PlannedSession } from './PlanClient'
import { utc, validDate, dateCount } from './ScopedReviewTypes'

export type AmendmentCandidate = { candidateId:string; sessionId:string; operation:'move_session'|'reduce_duration'|'omit_session'; before:PlannedSession; after:PlannedSession }
export type AmendmentRevision = {kind:string;identity:string;revision:number}
export type AmendmentProjection = { proposalId:string; sourceSnapshotId:string; basePlan:PlanRef; selectionGeneration:number; horizon:{oldest:string;newest:string}; policyVersion:string; contractVersion:string; sourcePacketSha256:string; sourceBindingSha256:string; adaptationWorkKey:string; policyInputSha256:string; candidateSetSha256:string; exactPromptBindingSha256:string; revisions:AmendmentRevision[]; sourceRevision:number; removalGeneration:number; constraintSequence:number; constraintDeletionGeneration:number; outcome:'selected'|'no_change'|'needs_clarification'|'rejected'; selected:AmendmentCandidate[]; explanations:DescriptiveItem[]; issues:{reason:string;[key:string]:unknown}[]; createdUtc:string; checkedUtc:string; expiresUtc:string; version:string; limitation:string }
export type ReviewConstraints = { proposalId:string; proposalSha256:string; policyInputSha256:string; basePlan:PlanRef; selectionGeneration:number; policyVersion:string; asOf:string; policy:PlanPolicy; horizon:{oldest:string;newest:string}; contexts:unknown[]; commitments:unknown[]; occupancy:{sessionId:string;schedule:PlanSchedule;omitted:boolean}[]; coverage:string; limitation:string }
export type AmendmentReceipt = { operationId:string; proposalId:string; appliedPlan:PlanRef; appliedUtc:string; recordedBy:'authenticated_user'; scope:string }
export type AmendmentApproval = {operationId:string;expectedBasePlan:PlanRef;expectedProposalSha256:string;expectedSelectionGeneration:number}
export type AmendmentInspection = { proposalId:string; proposalSha256:string; disposition:'pending'|'no_change'|'needs_confirmation'|'rejected'|'applied'; reason:string|null; freshness:'current'|'stale'|'expired'|'not_applicable'; state:string; createdUtc:string; expiresUtc:string; proposal:AmendmentProjection|null; rejectedUtc:string|null; approval:string; appliedReceipt:AmendmentReceipt|null; reviewConstraints:ReviewConstraints|null }
export type AmendmentIndex = { offset:number; limit:number; total:number; entries:{proposalId:string;disposition:string;createdUtc:string;expiresUtc:string}[]; nextOffset:number|null; freshness:'uninspected_open_exact_record_for_current_status' }
export type DescriptiveItem = {kind:string;text:string;sources:{kind:string;id:string;revision:number|null}[];uncertainty:string}
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
const hash=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v)
const integer=(v:unknown,min=0,max=Number.MAX_SAFE_INTEGER):v is number=>typeof v==='number'&&Number.isSafeInteger(v)&&v>=min&&v<=max
const text=(v:unknown,max=4096):v is string=>typeof v==='string'&&v.length>0&&v.length<=max
const fail=():never=>{throw Error('Invalid plan amendment response')}
const states=['pending','no_change','needs_confirmation','rejected','applied']
const unavailable='unavailable_terminal_or_noncurrent'
const pendingApproval='explicit_exact_proposal_approval_required'
function list<T>(v:unknown,max:number,parse:(item:unknown)=>T):T[] {if(!Array.isArray(v)||v.length>max)return fail();return v.map(parse)}
function unique<T>(rows:T[],key:(row:T)=>string) {if(new Set(rows.map(key)).size!==rows.length)fail()}
function deadline(created:unknown,expires:unknown) {
  if(!utc(created)||!utc(expires)||Date.parse(expires)<=Date.parse(created)||Date.parse(expires)-Date.parse(created)>86400000)fail()
}
function range(v:unknown):{oldest:string;newest:string} {
  const x=object(v)
  if(!validDate(x.oldest)||!validDate(x.newest)||dateCount(x.oldest as string,x.newest as string)<1||dateCount(x.oldest as string,x.newest as string)>366)return fail()
  return x as {oldest:string;newest:string}
}
function descriptive(v:unknown):DescriptiveItem {
  const x=object(v)
  if(!['context','observation','comparison'].includes(String(x.kind))||!text(x.text,4000)||!['low','moderate','high','unknown'].includes(String(x.uncertainty)))return fail()
  const sources=list(x.sources,20,value=>{
    const ref=object(value)
    if(!['plan_session','adaptation'].includes(String(ref.kind))||!(ref.kind==='plan_session'?uuid(ref.id)&&integer(ref.revision,1,1000):hash(ref.id)&&ref.revision===null))return fail()
    return ref as DescriptiveItem['sources'][number]
  })
  if(!sources.length)return fail()
  unique(sources,JSON.stringify)
  return {...x,sources} as DescriptiveItem
}
function candidate(v:unknown):AmendmentCandidate {
  const x=object(v)
  if(!hash(x.candidateId)||!uuid(x.sessionId)||!['move_session','reduce_duration','omit_session'].includes(String(x.operation)))return fail()
  const before=plannedSessionFrom(x.before),after=plannedSessionFrom(x.after)
  if(before.sessionId!==x.sessionId||after.sessionId!==x.sessionId||before.omitted)return fail()
  if(x.operation==='move_session') {
    if(!before.permissions.move||!sameValue({...before,schedule:after.schedule},after)||sameValue(before.schedule,after.schedule))return fail()
  } else if(x.operation==='omit_session') {
    if(!before.permissions.omit||!after.omitted||!sameValue({...before,omitted:true},after))return fail()
  } else {
    const permission=before.permissions.reduceDuration
    if(!permission||before.targets.length!==after.targets.length||!sameValue({...before,targets:after.targets},after))return fail()
    let changed=0
    before.targets.forEach((target,index)=>{
      const next=after.targets[index]
      if(sameValue(target,next))return
      changed++
      if(target.metric!=='duration'||target.basis!==permission.basis||!sameValue({...target,minimum:next.minimum,maximum:next.maximum},next)||
        next.minimum>target.minimum||next.maximum>target.maximum||next.minimum<permission.minimumSeconds||next.maximum>permission.maximumSeconds)fail()
    })
    if(changed!==1)return fail()
  }
  return {...x,before,after} as AmendmentCandidate
}
function projection(v:unknown):AmendmentProjection {
  const x=object(v)
  if(!uuid(x.proposalId)||!uuid(x.sourceSnapshotId)||!integer(x.selectionGeneration)||
    !['sourcePacketSha256','sourceBindingSha256','adaptationWorkKey','policyInputSha256','candidateSetSha256','exactPromptBindingSha256'].every(k=>hash(x[k]))||
    !['sourceRevision','removalGeneration','constraintSequence','constraintDeletionGeneration'].every(k=>integer(x[k]))||
    !['selected','no_change','needs_clarification','rejected'].includes(String(x.outcome))||!utc(x.checkedUtc)||
    x.version!=='local_amendment_proposal_v1'||x.policyVersion!=='authored_boundary_candidates_v1'||x.contractVersion!=='plan-amendment-selection-v1'||
    x.limitation!=='boundary_only_not_exhaustive;mechanical_not_clinical;explicit_approval_required;plan_unchanged')return fail()
  deadline(x.createdUtc,x.expiresUtc)
  if(Date.parse(x.checkedUtc)<Date.parse(String(x.createdUtc))||Date.parse(x.checkedUtc)>=Date.parse(String(x.expiresUtc)))return fail()
  const basePlan=planRefFrom(x.basePlan),selected=list(x.selected,8,candidate),explanations=list(x.explanations,8,descriptive)
  const revisions=list(x.revisions,10000,value=>{
    const ref=object(value)
    if(!text(ref.kind,80)||!(uuid(ref.identity)||hash(ref.identity))||!integer(ref.revision,ref.kind==='plan_selection'?0:1))return fail()
    return ref as AmendmentRevision
  })
  unique(revisions,r=>JSON.stringify(r));unique(selected,c=>c.sessionId);unique(selected,c=>c.candidateId);unique(explanations,JSON.stringify)
  if(!revisions.some(r=>r.kind==='plan'&&r.identity===basePlan.planId&&r.revision===basePlan.revision))return fail()
  const issues=list(x.issues,10000,value=>{
    const issue=object(value)
    if(!(issue.sessionId===null||uuid(issue.sessionId))||!(issue.operation===null||text(issue.operation,80))||
      !['rejected','needs_clarification'].includes(String(issue.status))||!text(issue.reason,160)||
      !(issue.attemptedStart===null||text(issue.attemptedStart,40)))return fail()
    list(issue.sources,1000,value=>{if(!text(value,256))return fail();return value})
    return issue as AmendmentProjection['issues'][number]
  })
  if(x.outcome==='selected') {
    if(!selected.length||issues.length||explanations.length!==selected.length)return fail()
    if(selected.some(c=>!explanations.some(e=>e.sources.some(s=>s.kind==='plan_session'&&s.id===c.sessionId&&s.revision===basePlan.revision))))return fail()
  } else if(selected.length||x.outcome==='rejected'&&(explanations.length||!issues.length)||x.outcome!=='rejected'&&(explanations.length!==1||issues.length))return fail()
  return {...x,basePlan,horizon:range(x.horizon),selected,explanations,revisions,issues} as AmendmentProjection
}
function constraints(v:unknown):ReviewConstraints {
  const x=object(v)
  if(!uuid(x.proposalId)||!hash(x.proposalSha256)||!hash(x.policyInputSha256)||!integer(x.selectionGeneration)||x.policyVersion!=='authored_boundary_candidates_v1'||!utc(x.asOf)||
    x.coverage!=='complete_captured_inputs_not_all_applicable;external_calendar_coverage_unavailable'||x.limitation!=='mechanical_not_clinical;inspection_not_inference_or_approval')return fail()
  const policy=object(x.policy)
  if(!text(policy.adaptationTimeZone,80)||!integer(policy.horizonDays,1,366)||!integer(policy.importGraceSeconds,0,31622400))return fail()
  try {new Intl.DateTimeFormat('en',{timeZone:policy.adaptationTimeZone})} catch {return fail()}
  const contexts=list(x.contexts,1000,viewFrom),commitments=list(x.commitments,1000,commitmentFrom)
  unique(contexts,c=>c.context.contextId);unique(commitments,c=>c.reference.commitmentId)
  const occupancy=list(x.occupancy,1000,value=>{
    const row=object(value)
    if(!uuid(row.sessionId)||typeof row.omitted!=='boolean')return fail()
    return {...row,schedule:planScheduleFrom(row.schedule)} as ReviewConstraints['occupancy'][number]
  })
  unique(occupancy,o=>o.sessionId)
  return {...x,basePlan:planRefFrom(x.basePlan),horizon:range(x.horizon),policy,contexts,commitments,occupancy} as ReviewConstraints
}
export function amendmentReceiptFrom(v:unknown):AmendmentReceipt {
  const x=object(v)
  if(!uuid(x.operationId)||!uuid(x.proposalId)||!utc(x.appliedUtc)||x.recordedBy!=='authenticated_user'||x.scope!=='exact_local_amendment_only_not_inference_hosted_transfer_or_external_write')return fail()
  return {...x,appliedPlan:planRefFrom(x.appliedPlan)} as AmendmentReceipt
}
export function boundReceipt(v:unknown,request:AmendmentApproval,proposalId:string):AmendmentReceipt {
  const receipt=amendmentReceiptFrom(v)
  if(receipt.operationId!==request.operationId||receipt.proposalId!==proposalId||!hash(request.expectedProposalSha256)||!integer(request.expectedSelectionGeneration)||
    receipt.appliedPlan.planId!==request.expectedBasePlan.planId||receipt.appliedPlan.revision!==request.expectedBasePlan.revision+1)return fail()
  return receipt
}
export function amendmentInspectionFrom(v:unknown):AmendmentInspection {
  if(new TextEncoder().encode(JSON.stringify(v)).length>2*1024*1024)return fail()
  const x=object(v)
  if(!uuid(x.proposalId)||!hash(x.proposalSha256)||!states.includes(String(x.disposition))||!['current','stale','expired','not_applicable'].includes(String(x.freshness))||
    !(x.reason===null||['user_rejected','invalid_whole_draft','policy_validation'].includes(String(x.reason)))||!(x.rejectedUtc===null||utc(x.rejectedUtc)))return fail()
  deadline(x.createdUtc,x.expiresUtc)
  if(x.disposition==='rejected'?(x.reason===null||x.rejectedUtc===null):(x.reason!==null||x.rejectedUtc!==null))return fail()
  const proposal=x.proposal===null?null:projection(x.proposal),receipt=x.appliedReceipt===null?null:amendmentReceiptFrom(x.appliedReceipt),review=x.reviewConstraints===null?null:constraints(x.reviewConstraints)
  if(x.disposition==='applied') {
    if(proposal||review||!receipt||receipt.proposalId!==x.proposalId||x.state!=='applied'||x.freshness!=='not_applicable'||x.approval!==unavailable)return fail()
  } else {
    if(receipt||x.freshness==='not_applicable')return fail()
    if(x.freshness==='stale'||x.freshness==='expired') {
      if(proposal||review||x.state!==x.freshness||x.approval!==unavailable)return fail()
    } else {
      if(!proposal||!review||x.state!==x.disposition||proposal.proposalId!==x.proposalId||proposal.createdUtc!==x.createdUtc||proposal.expiresUtc!==x.expiresUtc||
        review.proposalId!==x.proposalId||review.proposalSha256!==x.proposalSha256||review.policyInputSha256!==proposal.policyInputSha256||
        !sameValue(review.basePlan,proposal.basePlan)||review.selectionGeneration!==proposal.selectionGeneration||review.policyVersion!==proposal.policyVersion||
        !sameValue(review.horizon,proposal.horizon)||Date.parse(review.asOf)>=Date.parse(proposal.expiresUtc)||Date.parse(review.asOf)<Date.parse(proposal.createdUtc))return fail()
      const expected={selected:'pending',no_change:'no_change',needs_clarification:'needs_confirmation',rejected:'rejected'}[proposal.outcome]
      if(x.disposition!==expected&&!(x.disposition==='rejected'&&x.reason==='user_rejected'&&proposal.outcome==='selected'))return fail()
      if(x.approval!==(x.disposition==='pending'?pendingApproval:unavailable))return fail()
      if(proposal.selected.some(c=>{const occupied=review.occupancy.find(o=>o.sessionId===c.sessionId);return !occupied||occupied.omitted||!sameValue(occupied.schedule,c.before.schedule)}))return fail()
    }
  }
  return {...x,proposal,appliedReceipt:receipt,reviewConstraints:review} as AmendmentInspection
}
export function amendmentIndexFrom(v:unknown):AmendmentIndex {
  const x=object(v)
  if(!integer(x.offset,0,1000)||!integer(x.limit,1,50)||!integer(x.total,0,1000)||x.freshness!=='uninspected_open_exact_record_for_current_status')return fail()
  const entries=list(x.entries,x.limit,value=>{
    const entry=object(value)
    if(!uuid(entry.proposalId)||!states.includes(String(entry.disposition)))return fail()
    deadline(entry.createdUtc,entry.expiresUtc)
    return entry as AmendmentIndex['entries'][number]
  })
  unique(entries,e=>e.proposalId)
  if(entries.length!==Math.min(x.limit,Math.max(0,x.total-x.offset))||x.nextOffset!==(x.offset+x.limit<x.total?x.offset+x.limit:null))return fail()
  return {...x,entries} as AmendmentIndex
}
export async function loadAmendmentIndex(offset:number,limit:number,signal?:AbortSignal):Promise<AmendmentIndex> {
  if(!integer(offset,0,1000)||!integer(limit,1,50))return fail()
  const result=amendmentIndexFrom(await intakeRequest(`/api/plans/amendment-proposals?${new URLSearchParams({offset:String(offset),limit:String(limit)})}`,'','GET',undefined,signal))
  if(result.offset!==offset||result.limit!==limit)return fail()
  return result
}
export async function inspectAmendment(id:string,signal?:AbortSignal):Promise<AmendmentInspection> {
  if(!uuid(id))return fail()
  const result=amendmentInspectionFrom(await intakeRequest(`/api/plans/amendment-proposals/${encodeURIComponent(id)}/inspection`,'','GET',undefined,signal))
  if(result.proposalId!==id)return fail()
  return result
}
