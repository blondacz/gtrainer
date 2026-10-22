import {sameValue} from './ContextIntakeClient'
import type {AmendmentCandidate} from './PlanAmendmentClient'
import type {PlanRef,PlannedSession} from './PlanClient'
export type AmendmentDraftOverlay={proposalId:string;basePlan:PlanRef;expiresUtc:string;selected:AmendmentCandidate[]}
function SessionValues({label,value}:{label:string;value:PlannedSession}) {
  return <section aria-label={`${label} session ${value.sessionId}`}><h5>{label}</h5>
    <dl><dt>Session identity</dt><dd>{value.sessionId}</dd><dt>Author provenance</dt><dd>{value.author.authorKind}: {value.author.authorAttribution} — not independently verified approval</dd>
      <dt>Sport and objective</dt><dd>{value.sport} · {value.objective}</dd><dt>Notes</dt><dd>{value.notes??'No authored notes'}</dd>
      <dt>Omitted</dt><dd>{String(value.omitted)}</dd><dt>Schedule and precision</dt><dd>{value.schedule.kind==='date_only'?`${value.schedule.date} — whole local day`:
        `${value.schedule.startsLocal} (${value.schedule.startOffset}) – ${value.schedule.endsLocal} (${value.schedule.endOffset})`} · {value.schedule.timeZone}</dd>
      <dt>All authored targets</dt><dd>{value.targets.length===0?'No authored targets':<ul>{value.targets.map((t,i)=><li key={i}>{t.metric}: {t.minimum}–{t.maximum} {t.unit}; basis {t.basis}
        {t.effortScale&&<pre>{JSON.stringify(t.effortScale,null,2)}</pre>}</li>)}</ul>}</dd>
      <dt>Exact authored permissions</dt><dd><pre>{JSON.stringify(value.permissions,null,2)}</pre></dd></dl>
    <details><summary>Complete {label.toLowerCase()} values without omission</summary><pre>{JSON.stringify(value,null,2)}</pre></details>
  </section>
}
export function PlanAmendmentDiff({candidate}:{candidate:AmendmentCandidate}) {
  const unchanged=(['author','sport','objective','notes','permissions','targets','schedule','omitted'] as const).filter(key=>sameValue(candidate.before[key],candidate.after[key]))
  return <article aria-label={`Exact ${candidate.operation} diff for ${candidate.sessionId}`}><h4>{candidate.operation} · {candidate.sessionId}</h4>
    <p>Candidate {candidate.candidateId}. Unchanged fields: {unchanged.join(', ')||'none'}.</p>
    <SessionValues label="Before" value={candidate.before}/><SessionValues label="After" value={candidate.after}/>
  </article>
}
