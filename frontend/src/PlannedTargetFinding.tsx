import type {TargetCheck} from './PlanReconciliationClient'

/** Shared factual target display for reconciliation and immutable adaptation snapshots. */
export function PlannedTargetFinding({check}:{check:TargetCheck}) {
  return <><p>{check.target.metric} / {check.target.basis}: planned {check.target.minimum}–{check.target.maximum} {check.target.unit}; actual {check.actualValue??'unavailable'} {check.actualUnit??''} · {check.status} ({check.reason}) · {check.policy}</p>
    {check.target.effortScale&&<p>Exact effort scale: {check.target.effortScale.name} ({check.target.effortScale.scaleId}), {check.target.effortScale.minimum}–{check.target.effortScale.maximum} in steps of {check.target.effortScale.step}; higher values mean {check.target.effortScale.higherMeansMoreEffort?'more':'less'} effort. No conversion to another scale.</p>}
    {check.target.metric==='reported_effort'&&['above_range','below_range'].includes(check.status)&&<p>Reported {check.status==='above_range'?'harder':'easier'} than the authored effort range on this exact scale; subjective testimony, not measured physiological intensity.</p>}
    <p>Source {check.source??'unknown'}, metric origin {check.upstreamOrigin??'unknown'}, field {check.actualField??'unavailable'}; activity {check.activityId??'unresolved'} revision {check.activityRevision??'unknown'} · fingerprint {check.recordSha256??'unavailable'}.</p>
    {check.effortReport&&<p>Attributed report by {check.effortReport.authorAttribution}, observed {check.effortReport.observedOn}, report revision {check.effortReport.revision}; {check.effortReport.detached?'detached; reaffirmation required':check.effortReport.withdrawn?'withdrawn':'binding must remain current'}.</p>}
  </>
}
