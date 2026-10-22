import {adaptationFactsFrom} from './AdaptationFindingsClient'
import {PlannedTargetFinding} from './PlannedTargetFinding'
import {SignalRuleEvidence,WorkloadRuleEvidence} from './AdaptationRuleEvidence'
import type {ContextRecord} from './ContextFields'
import type {CommitmentDefinition} from './ContextIntakeClient'

function ContextEndpoint({label,value}:{label:string;value:ContextRecord|null}){
  return <p>{label}: {value?`${value.category} · ${value.contextId} revision ${value.revision} · ${value.content} · observed ${value.observedOn} · attributed to ${value.authorAttribution}; ${value.restrictionKind??'no typed restriction'} ${value.restrictionValue??''} ${value.restrictionUnit??''}`:'absent endpoint, not evidence of recovery'}.</p>
}
function CommitmentEndpoint({label,value}:{label:string;value:CommitmentDefinition|null}){
  return <p>{label}: {value?`${value.label} (${value.kind}), ${value.startsLocal}–${value.endsLocal} ${value.timeZone}; recurrence ${value.recurrence?.frequency??'none'}`:'no commitment at this endpoint'}.</p>
}
export function AdaptationFindings({value,stale}:{value:unknown;stale:boolean}){
  let facts
  try{facts=adaptationFactsFrom(value)}catch{return <p role="status">Adaptation findings unavailable or invalid. No trigger or applied amendment is inferred.</p>}
  return <section aria-label="Captured adaptation findings"><h4>Captured adaptation findings</h4>
    <p>{stale?'STALE — historical evidence only. Reprepare before relying on current findings.':'Captured factual evaluation only; refresh status to check currentness.'}</p>
    <p>Base plan {facts.family.plan.planId} revision {facts.family.plan.revision}; planning horizon {facts.horizon.oldest}–{facts.horizon.newest} ({facts.family.timeZone}), separate from the displayed evidence period.</p>
    <p>Work identity {facts.workKey}. A trigger is a request for review, not an applied amendment. No plan change, medical clearance, readiness score or inference is implied. An empty reason set is not a no-change decision.</p>
    <h5>Reason provenance</h5>
    {facts.reasons.length===0?<p>No reason in this captured result.</p>:<ul>{facts.reasons.map(reason=><li key={reason.reasonId}>{reason.kind}: {reason.condition} · reason {reason.reasonId}, revision {reason.revision}.
      {reason.provenance.length===0?<p>Current source provenance unavailable; retained reason is not positive clearing.</p>:<ul>{reason.provenance.map(source=><li key={source}>{source}</li>)}</ul>}</li>)}</ul>}
    {facts.sessions.map(session=><article key={session.sessionId}><h5>{session.sport} · {session.objective} · {session.sessionId}</h5>
      <p>{session.state} ({session.basis}); activity {session.activityId??'unresolved'}; candidates {session.candidateActivityIds.join(', ')||'none'}. Missing imports never establish skipped training.</p>
      {session.targetChecks.map(check=><div key={check.targetIndex}><PlannedTargetFinding check={check}/></div>)}</article>)}
    {facts.workload.map(report=><WorkloadRuleEvidence key={report.rule.ruleId} report={report}/>)}
    {facts.signals.map(signal=><SignalRuleEvidence key={signal.rule.ruleId} rule={signal.rule} days={signal.current?[signal.current]:[]} support={signal.baselineDays}/>)}
    {facts.constraintChanges.map(change=><article key={change.eventId}><h5>Constraint event {change.eventId}</h5>
      <p>Historical before/after evidence, not a diagnosis or an instruction to remove a restriction.</p>
      {(change.beforeContext||change.afterContext)&&<><ContextEndpoint label="Before context" value={change.beforeContext}/><ContextEndpoint label="After context" value={change.afterContext}/></>}
      {(change.beforeCommitment||change.afterCommitment)&&<><CommitmentEndpoint label="Before commitment" value={change.beforeCommitment}/><CommitmentEndpoint label="After commitment" value={change.afterCommitment}/></>}
    </article>)}
  </section>
}
