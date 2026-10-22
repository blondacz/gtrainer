import type {AmendmentInspection, AmendmentReceipt} from './PlanAmendmentClient'
import type {PlannedSession} from './PlanClient'
import {adaptationSnapshot,planId,sessionId} from './adaptation.test-fixtures'
export const proposalId='66666666-6666-4666-8666-666666666666'
export const operationId='77777777-7777-4777-8777-777777777777'
export const contextId='88888888-8888-4888-8888-888888888888'
export const created='2026-03-30T12:00:00Z',expires='2026-03-31T00:00:00Z'
export function amendmentInspection():AmendmentInspection {
  const before:PlannedSession={sessionId,author:{authorKind:'coach',authorAttribution:'Unverified synthetic author'},sport:'run',objective:'Synthetic objective',notes:'Original synthetic notes',
    schedule:{kind:'date_only',date:'2026-04-01',startsLocal:null,endsLocal:null,timeZone:'UTC',startOffset:null,endOffset:null},
    targets:[{metric:'duration',unit:'seconds',basis:'moving',minimum:1800,maximum:2400,effortScale:null}],permissions:{move:null,reduceDuration:null,omit:true},omitted:false}
  const basePlan={planId,revision:1},horizon={oldest:'2026-03-30',newest:'2026-04-05'},policy={adaptationTimeZone:'UTC',horizonDays:7,importGraceSeconds:0}
  return {proposalId,proposalSha256:'a'.repeat(64),disposition:'pending',reason:null,freshness:'current',state:'pending',createdUtc:created,expiresUtc:expires,rejectedUtc:null,
    approval:'explicit_exact_proposal_approval_required',appliedReceipt:null,
    proposal:{proposalId,sourceSnapshotId:adaptationSnapshot().snapshotId,basePlan,selectionGeneration:1,horizon,policyVersion:'authored_boundary_candidates_v1',contractVersion:'plan-amendment-selection-v1',
      sourcePacketSha256:'f'.repeat(64),sourceBindingSha256:'b'.repeat(64),adaptationWorkKey:'d'.repeat(64),policyInputSha256:'c'.repeat(64),candidateSetSha256:'e'.repeat(64),exactPromptBindingSha256:'9'.repeat(64),
      revisions:[{kind:'plan',identity:planId,revision:1},{kind:'context',identity:contextId,revision:1},{kind:'adaptation_result',identity:'d'.repeat(64),revision:1}],sourceRevision:0,removalGeneration:0,constraintSequence:1,constraintDeletionGeneration:0,
      outcome:'selected',selected:[{candidateId:'1'.repeat(64),sessionId,operation:'omit_session',before,after:{...before,omitted:true}}],
      explanations:[{kind:'context',text:`Candidate ${'1'.repeat(64)}: authored plan; not verified coach/clinician guidance. Exact authored operation, not medical suitability.`,sources:[{kind:'plan_session',id:sessionId,revision:1}],uncertainty:'high'}],issues:[],
      createdUtc:created,checkedUtc:created,expiresUtc:expires,version:'local_amendment_proposal_v1',limitation:'boundary_only_not_exhaustive;mechanical_not_clinical;explicit_approval_required;plan_unchanged'},
    reviewConstraints:{proposalId,proposalSha256:'a'.repeat(64),policyInputSha256:'c'.repeat(64),basePlan,selectionGeneration:1,policyVersion:'authored_boundary_candidates_v1',asOf:created,policy,horizon,
      contexts:[{context:{contextId,revision:1,category:'restriction',sourceCategory:'user_report',authorAttribution:'Synthetic athlete',enteredBy:'authenticated_user',observedOn:'2026-03-01',
        applicableFrom:null,applicableUntil:null,sport:null,activityId:null,reviewId:null,content:'Constraint absent from the source packet',retired:false,restrictionKind:'blocked_sport',restrictionValue:'ride',restrictionUnit:null},
        lifecycle:{resolution:'unresolved',reviewDueOn:null},state:'current',needsReview:false,sourceChanged:false,reasonFlags:[]}],commitments:[],
      occupancy:[{sessionId,schedule:before.schedule,omitted:false}],coverage:'complete_captured_inputs_not_all_applicable;external_calendar_coverage_unavailable',limitation:'mechanical_not_clinical;inspection_not_inference_or_approval'}}
}
export function amendmentSource() {
  const source=adaptationSnapshot(),inspection=amendmentInspection(),proposal=inspection.proposal!
  return {...source,packet:{...(source.packet as object),plan:{selection:{generation:1,selected:proposal.basePlan},plan:proposal.basePlan,policy:inspection.reviewConstraints!.policy,sessions:proposal.selected.map(c=>c.before)}}}
}
export function amendmentReceipt():AmendmentReceipt {
  return {operationId,proposalId,appliedPlan:{planId,revision:2},appliedUtc:created,recordedBy:'authenticated_user',scope:'exact_local_amendment_only_not_inference_hosted_transfer_or_external_write'}
}
export function appliedInspection():AmendmentInspection {
  return {...amendmentInspection(),disposition:'applied',state:'applied',freshness:'not_applicable',proposal:null,reviewConstraints:null,approval:'unavailable_terminal_or_noncurrent',appliedReceipt:amendmentReceipt()}
}
export function amendmentIndex() {
  return {offset:0,limit:25,total:1,entries:[{proposalId,disposition:'pending',createdUtc:created,expiresUtc:expires}],nextOffset:null,freshness:'uninspected_open_exact_record_for_current_status'}
}
