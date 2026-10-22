import { amendmentIndexFrom, amendmentInspectionFrom, boundReceipt, inspectAmendment } from './PlanAmendmentClient'
import {amendmentInspection,amendmentReceipt,appliedInspection,amendmentIndex,proposalId,operationId} from './amendment.test-fixtures'
import { describe, expect, test, vi } from 'vitest'
describe('plan amendment response validation',()=>{
  test('accepts actual pending and metadata-only applied states, including zero imported revision',()=>{
    expect(amendmentInspectionFrom(amendmentInspection()).proposal?.sourceRevision).toBe(0)
    expect(amendmentInspectionFrom(appliedInspection()).state).toBe('applied')
  })
  test.each(['no_change','needs_clarification','rejected'] as const)('accepts complete current outcome %s',outcome=>{
    const value=amendmentInspection();value.disposition=outcome==='needs_clarification'?'needs_confirmation':outcome;value.state=value.disposition
    value.proposal!.outcome=outcome;value.proposal!.selected=[];value.approval='unavailable_terminal_or_noncurrent'
    if(outcome==='rejected') {value.reason='invalid_whole_draft';value.rejectedUtc=value.createdUtc;value.proposal!.explanations=[];value.proposal!.issues=[{sessionId:null,operation:null,status:'rejected',reason:'invalid_whole_draft',sources:[],attemptedStart:null}]}
    expect(amendmentInspectionFrom(value).proposal?.outcome).toBe(outcome)
  })
  test('rejects contradictory bindings, states, source identities and incomplete constraints',()=>{
    const mutations:((v:ReturnType<typeof amendmentInspection>)=>void)[]=[v=>{v.reviewConstraints=null},v=>{v.state='applied'},v=>{v.reviewConstraints!.proposalSha256='f'.repeat(64)},
      v=>{v.reviewConstraints!.basePlan.revision=2},v=>{v.proposal!.horizon.oldest='2026-02-30'},v=>{v.proposal!.sourceRevision=-1},
      v=>{v.proposal!.selected.push(v.proposal!.selected[0])},v=>{v.proposal!.selected[0].after.notes='Altered notes'},
      v=>{v.proposal!.revisions[0].revision='1' as unknown as number},v=>{v.proposal!.contractVersion='other'},
      v=>{v.reviewConstraints!.occupancy=[]},v=>{v.reviewConstraints!.occupancy.push(v.reviewConstraints!.occupancy[0])},
      v=>{v.reviewConstraints!.contexts=[{}]},v=>{v.reviewConstraints!.commitments=[{}]},
      v=>{v.reviewConstraints!.policy.adaptationTimeZone='Not/AZone'},v=>{v.reviewConstraints!.coverage='partial'},
      v=>{v.reviewConstraints!.asOf=v.expiresUtc}]
    for(const mutate of mutations){const value=structuredClone(amendmentInspection());mutate(value);expect(()=>amendmentInspectionFrom(value)).toThrow()}
    expect(()=>amendmentInspectionFrom({...amendmentInspection(),freshness:'stale',state:'stale'})).toThrow()
    expect(()=>amendmentInspectionFrom({...appliedInspection(),state:'not_applicable'})).toThrow()
  })
  test('receipt must match exact operation, proposal and next revision',()=>{
    const value=amendmentInspection(),receipt=amendmentReceipt(),request={operationId,expectedBasePlan:value.proposal!.basePlan,expectedProposalSha256:value.proposalSha256,expectedSelectionGeneration:1}
    expect(boundReceipt(receipt,request,proposalId)).toEqual(receipt)
    expect(()=>boundReceipt({...receipt,operationId:proposalId},request,proposalId)).toThrow()
    expect(()=>boundReceipt({...receipt,appliedPlan:{...receipt.appliedPlan,revision:3}},request,proposalId)).toThrow()
  })
  test('index remains uninspected, exactly paged and bounded',()=>{
    expect(amendmentIndexFrom(amendmentIndex()).entries).toHaveLength(1)
    expect(()=>amendmentIndexFrom({...amendmentIndex(),nextOffset:25})).toThrow()
    expect(()=>amendmentIndexFrom({...amendmentIndex(),limit:51})).toThrow()
  })
  test('loader uses exact inspection route and rejects substituted envelope',async()=>{
    const fetcher=vi.fn(async(_path:string,_init?:RequestInit)=>({ok:true,status:200,json:async()=>amendmentInspection()}));vi.stubGlobal('fetch',fetcher)
    await inspectAmendment(proposalId)
    expect(fetcher.mock.calls[0]?.[0]).toBe(`/api/plans/amendment-proposals/${proposalId}/inspection`)
    expect(()=>amendmentInspectionFrom({...amendmentInspection(),proposalId:operationId})).toThrow()
  })
})
