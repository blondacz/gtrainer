import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { intakeRequest, PrivateIntakeError, sameValue } from './ContextIntakeClient'
import { authoredPlanFrom, plansViewFrom, planSelectionFrom, type AuthoredPlanRequest, type PlanRef, type PlansView } from './PlanClient'
import { blankPlan, PlanFields } from './PlanFields'
import { PlanCalendarControls } from './PlanCalendarControls'
import { PlanReconciliationControls } from './PlanReconciliationControls'
import { AdaptationRuleControls } from './AdaptationRuleControls'
import { PlanAmendmentControls } from './PlanAmendmentControls'
import type {AmendmentReceipt} from './PlanAmendmentClient'
import type {AmendmentDraftOverlay} from './PlanAmendmentDiff'

function matchesSaved(request: AuthoredPlanRequest, saved: AuthoredPlanRequest): boolean {
  if(request.sessions.length!==saved.sessions.length) return false
  function offsetSeconds(value: string|null):number|null {
    if(value===null) return null
    if(value==='Z') return 0
    const match=/^([+-])(\d{1,2})(?::?(\d{2}))?(?::?(\d{2}))?$/.exec(value)
    if(!match) return Number.NaN
    return (match[1]==='-' ? -1 : 1)*(Number(match[2])*3600+Number(match[3] ?? 0)*60+Number(match[4] ?? 0))
  }
  if(request.sessions.some((session,index)=>(['startOffset','endOffset'] as const).some(key=>session.schedule[key]!==null &&
    offsetSeconds(session.schedule[key])!==offsetSeconds(saved.sessions[index].schedule[key])))) return false
  // The server resolves and persists timezone offsets; every other authored field must match.
  const expected={...request,sessions:request.sessions.map((session,index)=>({...session,schedule:{...session.schedule,
    startOffset:saved.sessions[index].schedule.startOffset,endOffset:saved.sessions[index].schedule.endOffset}}))}
  return sameValue(expected,saved)
}

/** Authored local plans only. Selecting a base plan is not inference or amendment approval. */
export function PlansPanel({csrfToken,onNavigationLockChange}: {csrfToken:string;onNavigationLockChange?:(locked:boolean)=>void}) {
  const [view,setView]=useState<PlansView|null>(null), [draft,setDraft]=useState<AuthoredPlanRequest|null>(null)
  const [editing,setEditing]=useState<PlanRef|null>(null), [busy,setBusy]=useState(false), [uncertain,setUncertain]=useState(false)
  const [reconciliationLocked,setReconciliationLocked]=useState(false)
  const [rulesLocked,setRulesLocked]=useState(false)
  const [amendmentLocked,setAmendmentLocked]=useState(false),[amendmentOverlay,setAmendmentOverlay]=useState<AmendmentDraftOverlay|null>(null)
  const [calendarEpoch,setCalendarEpoch]=useState(0)
  const invalidateAmendment=useCallback(()=>{setCalendarEpoch(value=>value+1);setAmendmentOverlay(null)},[])
  const [error,setError]=useState(''), [message,setMessage]=useState(''), [revision,setRevision]=useState(0)
  useEffect(()=>{onNavigationLockChange?.(draft!==null || busy || uncertain || reconciliationLocked || rulesLocked || amendmentLocked)},[draft,busy,uncertain,reconciliationLocked,rulesLocked,amendmentLocked,onNavigationLockChange])
  useEffect(()=>()=>onNavigationLockChange?.(false),[onNavigationLockChange])
  useEffect(()=>{
    const abort=new AbortController()
    void intakeRequest('/api/plans',csrfToken,'GET',undefined,abort.signal).then(plansViewFrom).then(setView)
      .catch(()=>{if(!abort.signal.aborted) setError('Plans unavailable. Reload to inspect authoritative revisions.')})
    return ()=>abort.abort()
  },[csrfToken])

  async function reload() {
    if(busy||reconciliationLocked||rulesLocked||amendmentLocked) return
    setBusy(true)
    try {
      setView(plansViewFrom(await intakeRequest('/api/plans',csrfToken)))
      setDraft(null); setEditing(null); setUncertain(false); setError(''); setMessage('Reloaded authoritative plans; unsaved drafts discarded.')
      setRevision(current=>current+1)
    } catch { setError('Could not reload plans. No changes were submitted.') }
    finally {setBusy(false)}
  }
  function fail(cause: unknown, mutationAcknowledged=false) {
    if(cause instanceof PrivateIntakeError && !mutationAcknowledged) setError(cause.status===409 ? 'Plan or selected-base revision changed. Reload and review before saving.' : cause.message)
    else {setUncertain(true);setError('Save outcome unknown or response invalid. Reload and inspect authoritative plans before trying again. No automatic retry was made.')}
  }
  async function save(event: FormEvent) {
    event.preventDefault()
    if(!draft || !view || busy || uncertain || reconciliationLocked || rulesLocked || amendmentLocked) return
    const input=editing ? {expectedRevision:editing.revision,expectedSelectionGeneration:view.selection.generation,definition:draft} : draft
    if(new TextEncoder().encode(JSON.stringify(input)).length>32768) {setError('Authored revision exceeds 32 KiB. No request was sent or silently split.');return}
    setBusy(true);setError('');setMessage('')
    let acknowledged=false
    try {
      const saved=authoredPlanFrom(await intakeRequest(editing ? `/api/plans/${editing.planId}` : '/api/plans',csrfToken,editing ? 'PUT' : 'POST',input))
      if(saved.reference.revision!==(editing?.revision ?? 0)+1 || editing && saved.reference.planId!==editing.planId || !matchesSaved(draft,saved.definition)) throw new Error()
      acknowledged=true
      const latest=plansViewFrom(await intakeRequest('/api/plans',csrfToken))
      if(!latest.plans.some(plan=>plan.reference.planId===saved.reference.planId && plan.reference.revision>=saved.reference.revision)) throw new Error()
      setView(latest);setDraft(null);setEditing(null);setRevision(current=>current+1)
      setMessage(`Authored revision ${saved.reference.revision} saved. ${editing ? 'An already selected plan follows its successful edit.' : 'The new plan is not selected automatically.'} No model or upstream write was used.`)
    } catch(cause) {fail(cause,acknowledged)} finally {setBusy(false)}
  }
  async function select(selected: PlanRef|null) {
    if(!view || busy || uncertain || reconciliationLocked || rulesLocked || amendmentLocked) return
    setBusy(true);setError('');setMessage('')
    let acknowledged=false
    try {
      const result=planSelectionFrom(await intakeRequest('/api/plans/selection',csrfToken,'PUT',{expectedGeneration:view.selection.generation,selected}))
      if(result.generation!==view.selection.generation+1 || !sameValue(result.selected,selected)) throw new Error()
      acknowledged=true
      setView(plansViewFrom(await intakeRequest('/api/plans',csrfToken)))
      setDraft(null);setEditing(null);setRevision(current=>current+1)
      setMessage(selected ? 'Exact authored base plan selected. No amendment permission, inference or transfer consent is implied.' : 'Base plan deselected; adaptation unavailable.')
    } catch(cause) {fail(cause,acknowledged)} finally {setBusy(false)}
  }
  const refreshApplied=useCallback(async(receipt:AmendmentReceipt)=>{
    // The amendment lock is intentionally held while this dedicated read path refreshes.
    setBusy(true);setAmendmentOverlay(null)
    try {
      const latest=plansViewFrom(await intakeRequest('/api/plans',csrfToken))
      if(!latest.plans.some(plan=>plan.reference.planId===receipt.appliedPlan.planId&&plan.reference.revision>=receipt.appliedPlan.revision))throw Error()
      setView(latest);setRevision(value=>value+1);setMessage(`Applied local revision ${receipt.appliedPlan.revision}; authoritative plans refreshed.`)
    } catch {setUncertain(true);throw Error('Applied plan refresh unavailable')}
    finally {setBusy(false)}
  },[csrfToken])
  return <section aria-labelledby="plans-heading"><h2 id="plans-heading">Authored training plans</h2>
    <p>A plan lists training sessions written by you or a coach. Enter that existing plan here; an event or a goal does not create one. This screen cannot generate a plan or connect an external plan or calendar service.</p>
    <p>Saving a plan does not select it for adaptation, enable changes, allow model data transfer, or approve an amendment. Each requires a separate explicit action.</p>
    <button type="button" disabled={busy||reconciliationLocked||rulesLocked||amendmentLocked} onClick={()=>void reload()}>Reload plans and discard unsaved drafts</button>
    <button type="button" disabled={busy || reconciliationLocked || rulesLocked || amendmentLocked || uncertain || !view || draft!==null} onClick={()=>{setEditing(null);setDraft(blankPlan());setMessage('Unsaved authored draft only. No calendar or active plan has changed.')}}>Enter authored plan</button>
    {view && <>
      <p>Selected base: {view.selection.selected ? `${view.selection.selected.planId} · revision ${view.selection.selected.revision}` : 'none — an explicit authored base is required for adaptation'} · selection generation {view.selection.generation}</p>
      {view.selection.selected && <button type="button" disabled={busy || reconciliationLocked || rulesLocked || amendmentLocked || uncertain || draft!==null} onClick={()=>void select(null)}>Deselect base plan</button>}
      {view.plans.map(plan=><article key={plan.reference.planId} aria-label={`Authored plan ${plan.definition.title}`}>
        <h3>{plan.definition.title} · revision {plan.reference.revision}</h3>
        <p>{plan.definition.author.authorKind}-authored, attributed to {plan.definition.author.authorAttribution}. Entered by authenticated user at {plan.recordedUtc}. {plan.definition.sessions.length} session(s).</p>
        <p>{plan.definition.policy ? `Configured horizon ${plan.definition.policy.horizonDays} dates (${plan.definition.policy.adaptationTimeZone}), import grace ${plan.definition.policy.importGraceSeconds} seconds.` : 'Adaptation timing unavailable: no explicit policy configured.'}</p>
        <button type="button" disabled={busy || reconciliationLocked || rulesLocked || amendmentLocked || uncertain || draft!==null} onClick={()=>{setEditing(plan.reference);setDraft(plan.definition);setError('');setMessage('Unsaved revision draft. Existing session identities are preserved.')}}>Edit authored revision</button>
        <button type="button" disabled={busy || reconciliationLocked || rulesLocked || amendmentLocked || uncertain || draft!==null || sameValue(plan.reference,view.selection.selected)} onClick={()=>void select(plan.reference)}>Select this exact base revision</button>
      </article>)}
    </>}
    {draft && <form className="context-form" aria-label="Unsaved authored plan" onSubmit={event=>void save(event)}>
      <h3>Unsaved authored {editing ? `revision after ${editing.revision}` : 'plan'}</h3>
      <PlanFields value={draft} onChange={setDraft} disabled={busy || uncertain}/>
      <button type="submit" disabled={busy || uncertain}>Save authored revision</button>
      <button type="button" disabled={busy} onClick={()=>{setDraft(null);setEditing(null)}}>Discard plan draft</button>
    </form>}
    <PlanCalendarControls csrfToken={csrfToken} draft={draft} amendmentOverlay={amendmentOverlay} refreshRevision={revision} onReviewInvalidated={invalidateAmendment} disabled={busy||uncertain}/>
    <PlanAmendmentControls csrfToken={csrfToken} selection={view?.selection??null} disabled={busy||uncertain||draft!==null||rulesLocked||reconciliationLocked}
      reviewEpoch={revision+calendarEpoch} onStateLockChange={setAmendmentLocked} onOverlayChange={setAmendmentOverlay} onApplied={refreshApplied}/>
    <PlanReconciliationControls key={`reconciliation-${revision}`} csrfToken={csrfToken} disabled={busy||uncertain||draft!==null||rulesLocked||amendmentLocked} revision={revision} onStateLockChange={setReconciliationLocked}/>
    <AdaptationRuleControls csrfToken={csrfToken} disabled={busy||uncertain||draft!==null||reconciliationLocked||amendmentLocked} revision={revision} onStateLockChange={setRulesLocked}/>
    {error && <p role="alert">{error}</p>}{message && <p role="status">{message}</p>}
  </section>
}
