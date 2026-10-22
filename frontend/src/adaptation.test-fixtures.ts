import type {SignalReport,SignalRule,WorkloadReport,WorkloadRule} from './AdaptationRulesClient'
import type {AdaptationFacts} from './AdaptationFindingsClient'
import type {EvidenceSnapshot} from './ScopedEvidenceClient'
import {activityReport,asOf} from './scoped-review.test-fixtures'

export const ruleId='22222222-2222-4222-8222-222222222222',planId='11111111-1111-4111-8111-111111111111'
export const sessionId='33333333-3333-4333-8333-333333333333',workloadId='44444444-4444-4444-8444-444444444444'
export function signalRule():SignalRule{return {ruleId,revision:1,enabled:true,recordedUtc:asOf,recordedBy:'authenticated_user',
  configuration:{timeZone:'UTC',selector:{field:'hrv',unit:'ms',intermediary:'synthetic',upstreamOrigin:'synthetic',method:'measurement',basis:'daily'},
    baseline:{trailingDays:3,minimumSamples:1,minimumCoverage:0,maximumAgeDays:3},direction:'low',boundaryType:'absolute_limit',triggerMagnitude:20,resetMagnitude:30,
    triggerPersistenceDays:1,resetPersistenceDays:1,cooldownSeconds:60}}}
export function signalReport():SignalReport{
  return {rule:signalRule(),query:{oldest:'2026-03-30',newest:'2026-03-30'},asOf,status:'evaluated',
    days:[{date:'2026-03-30',status:'trigger',reasons:[],value:10,baselineValue:40,baselineOldest:'2026-03-27',baselineNewest:'2026-03-29',
      baselineSelectedDays:3,baselineExpectedDays:3,triggerBoundary:20,resetBoundary:30,triggerCount:1,resetCount:0}],
    support:['2026-03-27','2026-03-28','2026-03-29','2026-03-30'].map((date,i)=>({date,status:'unique_compatible',value:i===3?10:40,evidenceIds:[`wellness/synthetic/${date}/hrv`],candidateCount:1})),
    sourceStatus:[],admission:'not_integrated',priorHistory:'unknown_before_evaluated_horizon',
    temporalBasis:'source_date_window_using_records_available_at_preparation_not_point_in_time_replay',upstreamFreshness:'unknown'}
}
export function workloadRule():WorkloadRule{return {ruleId:workloadId,revision:1,enabled:true,recordedUtc:asOf,recordedBy:'authenticated_user',
  configuration:{aggregation:'per_record',metric:'duration',unit:'seconds',basis:'moving',sport:'ride',source:'intervals.icu',upstreamOrigin:null,timeZone:'UTC',windowDays:7,comparator:'greater_than',threshold:100}}}
export function workloadReport():WorkloadReport{return {rule:workloadRule(),oldest:'2026-03-24',newest:'2026-03-30',status:'triggered',value:null,
  items:[{activity:{activityId:'b'.repeat(64),revision:1,recordSha256:'c'.repeat(64),sport:'ride',startLocal:'2026-03-30T09:00:00',startInstant:'2026-03-30T09:00:00Z',timeContext:'upstream_instant'},
    status:'triggered',reason:'configured_workload_exceeded',value:150,field:'movingTime',unit:'seconds',basis:'moving',source:'intervals.icu',upstreamOrigin:null}],
  supportingActivityIds:['b'.repeat(64)],admission:'not_integrated',temporalBasis:'verified_start_in_configured_zone_window_through_captured_cutoff',completeness:'bounded_local_evidence_not_upstream_completeness'}}
export function adaptationFacts():AdaptationFacts{
  const report=signalReport()
  return {version:'adaptation_facts_v2',limitation:'factual_preparation_only_no_amendment_decision_no_medical_clearance',workKey:'d'.repeat(64),
    family:{plan:{planId,revision:1},horizonDays:7,timeZone:'UTC',policyOwnerId:planId},horizon:{oldest:'2026-03-30',newest:'2026-04-05'},
    sessions:[{sessionId,sport:'run',objective:'Synthetic easy session',schedule:{kind:'date_only',date:'2026-03-29',startsLocal:null,endsLocal:null,timeZone:'UTC',startOffset:null,endOffset:null},
      state:'matched',basis:'compatible_unique_evidence',activityId:'a'.repeat(64),candidateActivityIds:['a'.repeat(64)],unmatchedAfter:'2026-03-30T00:00:00Z',confirmation:null,
      targetChecks:[{plan:{planId,revision:1},sessionId,targetIndex:0,target:{metric:'duration',unit:'seconds',basis:'moving',minimum:60,maximum:100,effortScale:null},
        activityId:'a'.repeat(64),activityRevision:1,recordSha256:'e'.repeat(64),status:'above_range',reason:'assessed',actualValue:120,actualUnit:'seconds',actualField:'movingTime',
        actualBasis:'moving',source:'intervals.icu',upstreamOrigin:null,effortReport:null,policy:'authored_inclusive_range_v1'}]}],
    workload:[workloadReport()],signals:[{rule:report.rule,current:report.days[0],baselineDays:report.support.slice(0,3)}],constraintChanges:[],
    reasons:[{reasonId:'1'.repeat(64),revision:1,kind:'planned_range',condition:'active',provenance:[`plan_session/${sessionId}`,`planned_target/${sessionId}/0`,`activity/${'a'.repeat(64)}`]},
      {reasonId:'2'.repeat(64),revision:1,kind:'extra_workload',condition:'active',provenance:[`workload_rule/${workloadId}`,`activity/${'b'.repeat(64)}`]},
      {reasonId:'3'.repeat(64),revision:1,kind:'personal_signal',condition:'active',provenance:[`signal_rule/${ruleId}/1`]}]}
}
export function adaptationSnapshot():EvidenceSnapshot{
  const facts=adaptationFacts(),scope={...activityReport().scope,wellnessPolicy:'assessment' as const}
  return {snapshotId:'55555555-5555-4555-8555-555555555555',createdUtc:asOf,packetSha256:'f'.repeat(64),state:'PREPARED',
    packet:{profile:'scoped-descriptive-v1',admission:'closed_not_qualified_not_execution_bound',adaptation:facts},admission:'closed_not_qualified_not_execution_bound',
    manifest:{scope:scope as EvidenceSnapshot['manifest']['scope'],included:[`adaptation_result/${facts.workKey}`],displayOnly:[],excluded:[],missing:[],needsConfirmation:[],
      dependencies:[{kind:'adaptation_result',identity:facts.workKey,revision:1}],omitted:{wellnessObservationDetails:0,optionalContexts:0,policy:'category_atomic_optional_detail_only_no_subject_or_mandatory_truncation'},
      blockedReasons:[],packetBytes:8192,baselines:[],displayOnlyRecordCount:0,detailPolicy:'bounded_manifest_candidates_in_authenticated_record_pages'}}
}
