import {afterEach,describe,it,expect,vi} from 'vitest'
import {render,screen,cleanup,fireEvent} from '@testing-library/react'
import {adaptationFactsFrom} from './AdaptationFindingsClient'
import {parseSnapshot,loadEvidenceRecords} from './ScopedEvidenceClient'
import {ScopedEvidenceInspection} from './ScopedEvidenceInspection'
import {adaptationFacts,adaptationSnapshot,sessionId} from './adaptation.test-fixtures'
afterEach(()=>{cleanup();vi.unstubAllGlobals()})
describe('captured adaptation findings',()=>{
  it('shows planned actual thresholds baseline dates and source reason provenance without a request',()=>{
    const fetcher=vi.fn();vi.stubGlobal('fetch',fetcher)
    render(<ScopedEvidenceInspection snapshot={parseSnapshot(adaptationSnapshot())} onSnapshot={vi.fn()}/>)
    expect(screen.getByLabelText('Captured adaptation findings')).toBeInTheDocument()
    expect(screen.getByText(/planned 60–100 seconds; actual 120 seconds/)).toBeInTheDocument()
    expect(screen.getByText(/baseline 40/)).toBeInTheDocument()
    expect(screen.getByText(/Baseline dates 2026-03-27–2026-03-29/)).toBeInTheDocument()
    expect(screen.getByText(`planned_target/${sessionId}/0`)).toBeInTheDocument()
    expect(screen.getByText(/A trigger is a request for review, not an applied amendment/)).toBeInTheDocument()
    expect(fetcher).not.toHaveBeenCalled()
  })
  it('keeps stale and unevaluable evidence distinct from recovery or applied changes',()=>{
    const value=adaptationSnapshot(),facts=adaptationFacts()
    facts.signals[0].current={...facts.signals[0].current!,status:'unevaluable',value:null,baselineValue:null,reasons:['evaluation_unverified'],triggerCount:0}
    facts.reasons[2].condition='unevaluable'
    value.packet={profile:'scoped-descriptive-v1',admission:'closed_not_qualified_not_execution_bound',adaptation:facts};value.state='STALE'
    render(<ScopedEvidenceInspection snapshot={parseSnapshot(value)} onSnapshot={vi.fn()}/>)
    expect(screen.getByText(/STALE — historical evidence only/)).toBeInTheDocument()
    expect(screen.getByText(/Evidence status: evaluation_unverified/)).toBeInTheDocument()
    expect(screen.getByText(/personal_signal: unevaluable/)).toBeInTheDocument()
    expect(screen.getByText(/Missing imports never establish skipped training/)).toBeInTheDocument()
  })
  it('rejects forged reason references contradictory targets and invalid result inclusion',()=>{
    const f=adaptationFacts()
    expect(()=>adaptationFactsFrom({...f,reasons:[{...f.reasons[0],provenance:['applied/anything']}]})).toThrow()
    expect(()=>adaptationFactsFrom({...f,sessions:[{...f.sessions[0],state:'ambiguous'}]})).toThrow()
    expect(()=>adaptationFactsFrom({...f,signals:[{...f.signals[0],baselineDays:[f.signals[0].baselineDays[0]]}]})).toThrow()
    const s=adaptationSnapshot()
    expect(()=>parseSnapshot({...s,manifest:{...s.manifest,included:[]}})).toThrow()
    expect(()=>parseSnapshot({...s,manifest:{...s.manifest,dependencies:[{kind:'adaptation_result',identity:'a'.repeat(64),revision:1}]}})).toThrow()
    expect(()=>parseSnapshot({...s,manifest:{...s.manifest,scope:{...s.manifest.scope,wellnessPolicy:'display_only'}}})).toThrow()
  })
  it('recognizes exact result references and support roles, and hides purged captured facts',async()=>{
    const s=adaptationSnapshot()
    const fetcher=vi.fn(async(path:string)=>path.includes('/records')?{ok:true,json:async()=>({snapshotId:s.snapshotId,items:[{category:'wellness',source:'synthetic',sourceRecordId:'day',importedRevision:1,
      status:'unavailable',activity:null,wellness:null,roles:['adaptation_causal_support']}],nextOffset:null,limitation:'not replayed'})}:{ok:false,status:404})
    vi.stubGlobal('fetch',fetcher)
    expect((await loadEvidenceRecords(s.snapshotId,0,new AbortController().signal)).items[0].roles).toEqual(['adaptation_causal_support'])
    render(<ScopedEvidenceInspection snapshot={parseSnapshot(s)} onSnapshot={vi.fn()}/>)
    fireEvent.click(screen.getByRole('button',{name:'Refresh snapshot status (read only)'}))
    expect(await screen.findByText(/Cached packet and record values are not displayed/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Captured adaptation findings')).not.toBeInTheDocument()
  })
})
