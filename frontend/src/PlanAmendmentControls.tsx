import {useEffect,useRef,useState} from 'react'
import {intakeRequest,object,PrivateIntakeError,sameValue} from './ContextIntakeClient'
import {inspectAmendment,loadAmendmentIndex,amendmentInspectionFrom,boundReceipt,type AmendmentApproval,type AmendmentIndex,type AmendmentInspection,type AmendmentReceipt} from './PlanAmendmentClient'
import type {PlanSelection} from './PlanClient'
import {loadEvidence,type EvidenceSnapshot} from './ScopedEvidenceClient'
import {ScopedEvidenceInspection} from './ScopedEvidenceInspection'
import {PlanAmendmentDiff,type AmendmentDraftOverlay} from './PlanAmendmentDiff'

export function boundAmendmentSource(source:EvidenceSnapshot,inspection:AmendmentInspection):boolean {
  try {
    const proposal=inspection.proposal,details=inspection.reviewConstraints
    if(!proposal||!details||source.state!=='PREPARED'||source.snapshotId!==proposal.sourceSnapshotId||source.packetSha256!==proposal.sourcePacketSha256)return false
    const packet=object(source.packet),plan=object(packet.plan),selection=object(plan.selection),facts=object(packet.adaptation)
    if(facts.workKey!==proposal.adaptationWorkKey||!sameValue(plan.plan,proposal.basePlan)||selection.generation!==proposal.selectionGeneration||
      !sameValue(selection.selected,proposal.basePlan)||!sameValue(plan.policy,details.policy)||!Array.isArray(plan.sessions))return false
    const sessions=plan.sessions as unknown[]
    if(proposal.selected.some(c=>!sessions.some(session=>sameValue(session,c.before))))return false
    return source.manifest.dependencies.every(d=>proposal.revisions.some(r=>r.kind===d.kind&&r.identity===d.identity&&r.revision===d.revision))
  } catch {return false}
}
type Props={csrfToken:string;selection:PlanSelection|null;disabled?:boolean;reviewEpoch:number;onStateLockChange?:(locked:boolean)=>void;
  onOverlayChange?:(overlay:AmendmentDraftOverlay|null)=>void;onApplied:(receipt:AmendmentReceipt)=>Promise<void>}
type Submitted={kind:'approval';id:string;body:AmendmentApproval}|{kind:'rejection';id:string;body:{expectedProposalSha256:string}}

/** Existing stored proposals only. Browsing and inspection never generate, infer or apply. */
export function PlanAmendmentControls({csrfToken,selection,disabled=false,reviewEpoch,onStateLockChange,onOverlayChange,onApplied}:Props) {
  const [index,setIndex]=useState<AmendmentIndex|null>(null),[inspection,setInspection]=useState<AmendmentInspection|null>(null)
  const [source,setSource]=useState<EvidenceSnapshot|null>(null),[busy,setBusy]=useState(false),[unknown,setUnknown]=useState(false)
  const [sourceTrusted,setSourceTrusted]=useState(false)
  const [inspectionFailed,setInspectionFailed]=useState(false)
  const [attested,setAttested]=useState(false),[dirty,setDirty]=useState(false),[now,setNow]=useState(Date.now())
  const [error,setError]=useState(''),[message,setMessage]=useState('')
  const guard=useRef(false),epoch=useRef(0),active=useRef<AbortController|null>(null),submitted=useRef<Submitted|null>(null)
  const announced=useRef(new Set<string>()),heading=useRef<HTMLHeadingElement|null>(null),previousEpoch=useRef(reviewEpoch)
  const inspectedProposal=inspection?.proposal,expired=!!inspection&&now>=Date.parse(inspection.expiresUtc)
  const selectionMatches=!!inspectedProposal&&!!selection&&sameValue(selection.selected,inspectedProposal.basePlan)&&selection.generation===inspectedProposal.selectionGeneration
  const projection=inspectionFailed||expired||dirty||!selectionMatches?null:inspectedProposal
  const pending=inspection?.state==='pending'&&inspection.freshness==='current'&&!!projection&&!!inspection.reviewConstraints
  const sourceReady=sourceTrusted&&!!source&&!!inspection&&boundAmendmentSource(source,inspection)
  const ready=pending&&selectionMatches&&sourceReady&&!dirty&&!disabled&&!busy&&!unknown
  useEffect(()=>{if(!ready)setAttested(false)},[ready])
  useEffect(()=>{onStateLockChange?.(busy||unknown||inspection!==null)},[busy,unknown,inspection,onStateLockChange])
  useEffect(()=>()=>{active.current?.abort();epoch.current++;onStateLockChange?.(false);onOverlayChange?.(null)},[onStateLockChange,onOverlayChange])
  useEffect(()=>{
    if(previousEpoch.current===reviewEpoch)return
    previousEpoch.current=reviewEpoch;setSource(null);setSourceTrusted(false);setAttested(false);setDirty(inspection?.state!=='applied');epoch.current++;active.current?.abort()
  },[reviewEpoch])
  useEffect(()=>{
    const tick=()=>setNow(Date.now()),timer=window.setInterval(tick,1000)
    window.addEventListener('focus',tick);document.addEventListener('visibilitychange',tick)
    return()=>{window.clearInterval(timer);window.removeEventListener('focus',tick);document.removeEventListener('visibilitychange',tick)}
  },[])
  useEffect(()=>{
    onOverlayChange?.(ready&&inspection&&projection?{proposalId:inspection.proposalId,basePlan:projection.basePlan,expiresUtc:inspection.expiresUtc,selected:projection.selected}:null)
  },[ready,inspection,projection,onOverlayChange])
  function invalidateSource(){setSource(null);setSourceTrusted(false);setAttested(false)}
  function failedSource(){invalidateSource();setInspectionFailed(true);setDirty(true)}
  async function applied(next:AmendmentInspection,receipt:AmendmentReceipt,own:boolean) {
    setInspection({...next,state:'applied',disposition:'applied',freshness:'not_applicable',proposal:null,reviewConstraints:null,
      reason:null,rejectedUtc:null,approval:'unavailable_terminal_or_noncurrent',appliedReceipt:receipt})
    invalidateSource();setInspectionFailed(false);setUnknown(false);submitted.current=null
    setMessage(own?`Exact local amendment applied as revision ${receipt.appliedPlan.revision}. No inference or upstream write occurred.`:'This proposal was already applied by another approval operation.')
    if(!announced.current.has(receipt.operationId)) {
      announced.current.add(receipt.operationId)
      try {await onApplied(receipt)} catch {setError('Approval is applied, but plan/calendar refresh failed. Reload authoritative plans; do not approve again.')}
    }
  }
  async function browse(offset=0) {
    if(guard.current||disabled||inspection||unknown)return
    guard.current=true;setBusy(true);setError('');const token=++epoch.current,abort=new AbortController();active.current=abort
    try {const next=await loadAmendmentIndex(offset,25,abort.signal);if(token===epoch.current)setIndex(next)}
    catch {if(!abort.signal.aborted)setError('Stored proposal list unavailable. No work was started.')}
    finally {guard.current=false;setBusy(false)}
  }
  async function inspect(id:string) {
    if(guard.current||disabled)return
    guard.current=true;setBusy(true);setError('');invalidateSource();setAttested(false);setInspectionFailed(true)
    const token=++epoch.current,abort=new AbortController();active.current=abort
    try {
      const next=await inspectAmendment(id,abort.signal)
      if(token!==epoch.current)return
      setInspection(next);setInspectionFailed(false);setDirty(false);setNow(Date.now())
      const saved=submitted.current
      if(next.appliedReceipt) {
        const receipt=saved?.kind==='approval'?boundReceipt(next.appliedReceipt,{...saved.body,operationId:next.appliedReceipt.operationId},id):next.appliedReceipt
        await applied(next,receipt,saved?.kind==='approval'&&receipt.operationId===saved.body.operationId)
      } else if(saved?.kind==='rejection'&&next.disposition==='rejected'&&next.reason==='user_rejected') {setUnknown(false);submitted.current=null;setMessage('Authoritative inspection confirms rejection.')}
      else if(unknown)setMessage('Inspection alone does not prove the original request cannot still finish. Keep its operation identity; no automatic retry occurred.')
      heading.current?.focus()
    } catch {if(!abort.signal.aborted){setInspectionFailed(true);setDirty(true);setError('Proposal unavailable or inconsistent. Cached details, approval readiness and draft overlay were removed.')}}
    finally {guard.current=false;setBusy(false)}
  }
  async function inspectSource() {
    if(guard.current||disabled||!inspection?.proposal||unknown)return
    guard.current=true;setBusy(true);invalidateSource();setError('')
    const token=++epoch.current,abort=new AbortController();active.current=abort
    try {
      const next=await loadEvidence(inspection.proposal.sourceSnapshotId,abort.signal)
      if(token!==epoch.current)return
      if(!boundAmendmentSource(next,inspection))throw Error()
      setSource(next);setSourceTrusted(true)
    } catch {if(!abort.signal.aborted){failedSource();setError('Source evidence is unavailable, stale or mismatched. Cached details are hidden; reinspect the proposal.')}}
    finally {guard.current=false;setBusy(false)}
  }
  async function mutate(kind:'approval'|'rejection',retry?:Submitted) {
    if(guard.current||disabled||!inspection)return
    if(!retry&&(kind==='approval'?!(ready&&attested&&Date.now()<Date.parse(inspection.expiresUtc)):!(pending&&!unknown)))return
    const saved:Submitted=retry??(kind==='approval'?{kind,id:inspection.proposalId,body:{operationId:crypto.randomUUID(),expectedBasePlan:projection!.basePlan,
      expectedProposalSha256:inspection.proposalSha256,expectedSelectionGeneration:projection!.selectionGeneration}}:
      {kind,id:inspection.proposalId,body:{expectedProposalSha256:inspection.proposalSha256}})
    submitted.current=saved;guard.current=true;setBusy(true);setError('');invalidateSource()
    try {
      const body=await intakeRequest(`/api/plans/amendment-proposals/${saved.id}/${saved.kind}`,csrfToken,'POST',saved.body)
      if(saved.kind==='approval')await applied(inspection,boundReceipt(body,saved.body,saved.id),true)
      else {
        const next=amendmentInspectionFrom(body)
        if(next.proposalId!==saved.id||next.proposalSha256!==saved.body.expectedProposalSha256||next.disposition!=='rejected'||next.reason!=='user_rejected')throw Error()
        setInspection(next);setInspectionFailed(false);setUnknown(false);submitted.current=null;setMessage('Exact proposal rejected. Active plan and calendar are unchanged.')
      }
    } catch(cause) {
      setDirty(true)
      if(cause instanceof PrivateIntakeError&&cause.status<500){setUnknown(false);submitted.current=null;setError('Request refused. Reinspect authoritative state before any further action.')}
      else {setUnknown(true);setError('Outcome unknown or response invalid. No automatic retry. Reinspect, or explicitly resolve the same exact original request; no replacement operation will be created.')}
    } finally {guard.current=false;setBusy(false)}
  }
  return <section aria-label="Exact local amendment review"><h3 ref={heading} tabIndex={-1}>Stored plan amendments</h3>
    <p>Review existing local proposals only. Generation and model inference are separate and unavailable here. Hosted-transfer consent is not local plan approval; no external write-back is available.</p>
    <button type="button" disabled={disabled||busy||inspection!==null||unknown} onClick={()=>void browse()}>Browse stored proposals</button>
    {index&&<><p>Stored disposition only—not current approval status. {index.total} record(s).</p>
      {!index.entries.length&&<p>No stored proposals. No generation or inference was requested.</p>}
      <ul>{index.entries.map(entry=><li key={entry.proposalId}>{entry.proposalId} · stored {entry.disposition} · expiry {entry.expiresUtc}
        <button type="button" disabled={disabled||busy||inspection!==null||unknown} onClick={()=>void inspect(entry.proposalId)}>Inspect proposal {entry.proposalId}</button></li>)}</ul>
      {index.offset>0&&<button type="button" disabled={busy||inspection!==null} onClick={()=>void browse(Math.max(0,index.offset-25))}>Previous proposals</button>}
      {index.nextOffset!==null&&<button type="button" disabled={busy||inspection!==null} onClick={()=>void browse(index.nextOffset!)}>Next proposals</button>}</>}
    {inspection&&<>
      <p role="status">Proposal {inspection.proposalId}: {expired&&inspection.state==='pending'?'expired locally; server remains authoritative':inspection.state}. Freshness: {inspection.freshness}. Disposition: {inspection.disposition}.</p>
      <p>Expires {inspection.expiresUtc}. Exact proposal digest {inspection.proposalSha256}. {inspection.reason}</p>
      {projection&&<><p>Base {projection.basePlan.planId}, revision {projection.basePlan.revision}; selection generation {projection.selectionGeneration}. Boundary-only search, not exhaustive or clinical clearance.</p>
        {projection.selected.map(c=><PlanAmendmentDiff key={c.candidateId} candidate={c}/>)}
        <h4>Exact explanations, citations and uncertainty</h4>{projection.explanations.map((item,i)=><div key={i}><p>{item.text}</p><p>Uncertainty: {item.uncertainty}. Sources: {item.sources.map(s=>`${s.kind}/${s.id} revision ${s.revision??'not applicable'}`).join('; ')}</p></div>)}
        {projection.issues.length>0&&<pre>{JSON.stringify(projection.issues,null,2)}</pre>}
        <details><summary>All bound revision references and evidence identities</summary><pre>{JSON.stringify({revisions:projection.revisions,sourceSnapshotId:projection.sourceSnapshotId,sourcePacketSha256:projection.sourcePacketSha256,adaptationWorkKey:projection.adaptationWorkKey,policyInputSha256:projection.policyInputSha256},null,2)}</pre></details>
      </>}
      {projection&&inspection.reviewConstraints&&<section aria-label="Complete captured amendment constraints"><h4>Complete captured safety and availability inputs</h4>
        <p>Captured {inspection.reviewConstraints.asOf}; policy {inspection.reviewConstraints.policyVersion}. {inspection.reviewConstraints.coverage}. Mechanical checks—not medical suitability.</p>
        <details><summary>Inspect every captured context, commitment and occupied interval</summary><pre>{JSON.stringify(inspection.reviewConstraints,null,2)}</pre></details>
      </section>}
      {!inspectionFailed&&inspection.appliedReceipt&&<p>Applied local revision {inspection.appliedReceipt.appliedPlan.revision}; operation {inspection.appliedReceipt.operationId}. No pending draft remains.</p>}
      {!selectionMatches&&inspectedProposal&&<p role="status">Displayed selected plan differs from this exact base. Reload authoritative plans; cached details and approval are unavailable.</p>}
      {dirty&&<p role="status">Review changed or failed. Reinspect the exact proposal and its evidence; prior attestation is invalid.</p>}
      <button type="button" disabled={busy||disabled} onClick={()=>void inspect(inspection.proposalId)}>Reinspect authoritative proposal</button>
      {projection&&<button type="button" disabled={busy||disabled||unknown||dirty} onClick={()=>void inspectSource()}>Inspect bound source evidence</button>}
      {projection&&source&&!sourceTrusted&&<p role="status">Source inspection is not current. Refresh snapshot status after reading record pages before attesting again.</p>}
      {projection&&source&&<ScopedEvidenceInspection key={`${inspection.proposalId}-${inspection.proposalSha256}-${reviewEpoch}`} snapshot={source}
        onReadStart={()=>{setSourceTrusted(false);setAttested(false)}} onReadError={failedSource} onRemoved={failedSource}
        onSnapshot={next=>{setAttested(false);if(boundAmendmentSource(next,inspection)){setSource(next);setSourceTrusted(true)}else {failedSource();setError('Refreshed source no longer matches. Reinspect the proposal.')}}}/>}
      <label><input type="checkbox" checked={attested} disabled={!ready} onChange={event=>setAttested(event.target.checked)}/>
        I reviewed every before/after value, bound evidence, complete constraints and uncertainty for this exact local amendment.</label>
      <button type="button" disabled={!(ready&&attested)} onClick={()=>void mutate('approval')}>Approve exact local amendment</button>
      <button type="button" disabled={!pending||busy||disabled||unknown} onClick={()=>void mutate('rejection')}>Reject exact proposal</button>
      {unknown&&submitted.current&&<button type="button" disabled={busy||disabled} onClick={()=>void mutate(submitted.current!.kind,submitted.current!)}>Resolve original request using the same exact payload</button>}
      <button type="button" disabled={busy||unknown} onClick={()=>{epoch.current++;active.current?.abort();setInspection(null);invalidateSource();setDirty(false);setMessage('Review discarded. No plan change was requested.');heading.current?.focus()}}>Close review</button>
    </>}
    {error&&<p role="alert">{error}</p>}{message&&<p role="status">{message}</p>}
  </section>
}
