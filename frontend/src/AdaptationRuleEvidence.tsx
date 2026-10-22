import type {SignalRule,SignalDay,SignalSupportDay,WorkloadReport} from './AdaptationRulesClient'

/** Pure displays shared by saved-rule inspection and captured factual snapshots. */
export function SignalRuleEvidence({rule,days,support}:{rule:SignalRule;days:SignalDay[];support:SignalSupportDay[]}){
  const c=rule.configuration
  return <section aria-label={`Signal evidence ${rule.ruleId}`}><h5>Personal signal {rule.ruleId} · revision {rule.revision} · {rule.enabled?'enabled':'disabled'}</h5>
    {!c?<p>No explicit configuration.</p>:<><p>{c.selector.field} · {c.selector.unit} · intermediary {c.selector.intermediary} · origin {c.selector.upstreamOrigin} · method {c.selector.method} · basis {c.selector.basis} · {c.timeZone}.</p>
      <p>Configured {c.direction} / {c.boundaryType}: trigger magnitude {c.triggerMagnitude}, reset magnitude {c.resetMagnitude}; persistence {c.triggerPersistenceDays}/{c.resetPersistenceDays} dates; cooldown {c.cooldownSeconds} seconds.</p>
      <p>Baseline: preceding {c.baseline.trailingDays} source dates, excluding the evaluation date; minimum {c.baseline.minimumSamples} samples, coverage {c.baseline.minimumCoverage}, maximum age {c.baseline.maximumAgeDays} days. Source selection does not verify measurement semantics.</p></>}
    {days.length===0&&<p>No evaluated signal dates; this is not evidence of recovery.</p>}
    {days.map(day=><article key={day.date}><p>Evaluation {day.date}: {day.status}; actual {day.value??'unavailable'} {c?.selector.unit}; baseline {day.baselineValue??'unavailable'}.</p>
      <p>Baseline dates {day.baselineOldest}–{day.baselineNewest}; selected {day.baselineSelectedDays}/{day.baselineExpectedDays}. Trigger boundary {day.triggerBoundary??'unavailable'}, reset boundary {day.resetBoundary??'unavailable'}; consecutive counts {day.triggerCount}/{day.resetCount}.</p>
      <p>Evidence status: {day.reasons.join(', ')||'no unevaluable reason reported'}. A trigger requests review, not an amendment or medical conclusion.</p></article>)}
    <details><summary>Baseline and daily support dates ({support.length})</summary><ul>{support.map(day=><li key={day.date}>{day.date}: {day.status}; value {day.value??'unavailable'}; candidates {day.candidateCount}; evidence {day.evidenceIds.join(', ')||'none'}.</li>)}</ul></details>
  </section>
}

export function WorkloadRuleEvidence({report}:{report:WorkloadReport}){
  const c=report.rule.configuration
  return <section aria-label={`Workload evidence ${report.rule.ruleId}`}><h5>Extra workload {report.rule.ruleId} · revision {report.rule.revision} · {report.rule.enabled?'enabled':'disabled'}</h5>
    {!c?<p>No explicit workload configuration.</p>:<p>Configured {c.aggregation} · {c.sport} · {c.metric}/{c.basis}: {c.comparator} {c.threshold} {c.unit}; {c.windowDays} source dates in {c.timeZone}; source {c.source}, origin {c.upstreamOrigin??'none specified'}.</p>}
    <p>Window {report.oldest??'unavailable'}–{report.newest??'unavailable'} · {report.status}; cumulative value {report.value??'not available / not cumulative'}. Extra is relative to the selected plan, not necessarily unintended.</p>
    <ul>{report.items.map(item=><li key={item.activity.activityId}>{item.activity.activityId} · revision {item.activity.revision??'unknown'}: {item.status} ({item.reason}); actual {item.value??'unavailable'} {item.unit??''} / {item.basis??'unavailable'}; source {item.source??'unknown'}, origin {item.upstreamOrigin??'unknown'}, field {item.field??'unavailable'}.</li>)}</ul>
    <p>Supporting activity IDs: {report.supportingActivityIds.join(', ')||'none'}. Missing data is not zero or positive clearing; upstream completeness is unknown.</p>
  </section>
}
