import {act,fireEvent,render,screen,waitFor} from '@testing-library/react'
import {describe,expect,it,vi} from 'vitest'
import {PlanReconciliationControls} from './PlanReconciliationControls'
import {reconciliationFrom} from './PlanReconciliationClient'

const planId='11111111-1111-4111-8111-111111111111',sessionId='22222222-2222-4222-8222-222222222222'
const uuid='33333333-3333-4333-8333-333333333333',unknownId='f'.repeat(64),recordHash='b'.repeat(64)
const activity=(activityId=unknownId,startInstant:string|null='2026-10-15T10:15:00Z')=>({activityId,revision:1,recordSha256:recordHash,sport:'run',startLocal:'2026-10-15T10:15:00',startInstant,timeContext:startInstant?'upstream_instant':'source_local'})
const confirmation=(action:'match'|'skip'|'clear'='match',a:ReturnType<typeof activity>|null=activity(),revision=1)=>({confirmationId:uuid,plan:{planId,revision},sessionId,action,activity:a,recordedBy:'authenticated_user',recordedUtc:'2026-10-16T12:00:00Z'})
const planSession=(state='ambiguous',ids=[unknownId],basis='competing_candidates_or_confirmed_ownership',saved:null|ReturnType<typeof confirmation>=null)=>({sessionId,sport:'run',objective:'Easy run',schedule:{kind:'timed',date:null,startsLocal:'2026-10-15T10:00',endsLocal:'2026-10-15T11:00',timeZone:'UTC',startOffset:'+00:00',endOffset:'+00:00'},state,basis,activityId:state==='matched'?unknownId:null,candidateActivityIds:ids,unmatchedAfter:'2026-10-15T11:30:00Z',confirmation:saved,targetChecks:[]})
const coverage={category:'activities',recordCount:1,lastAttemptUtc:'2026-10-16T11:00:00Z',lastSuccessUtc:'2026-10-16T11:00:00Z',readStatus:'complete',rejected:0,incomplete:0,latestObservedDate:'2026-10-15',latestObservedAgeDays:1,upstreamFreshness:'unknown'}
const body={query:{oldest:'2026-10-15',newest:'2026-10-15',timeZone:'UTC'},asOf:'2026-10-16T12:00:00Z',selection:{generation:2,selected:{planId,revision:1}},digest:'a'.repeat(64),supportSha256:'c'.repeat(64),sessions:[planSession()],activities:[{activity:activity(),state:'needs_confirmation',sessionIds:[sessionId]}],coverage:[coverage],policy:'one_to_one_exact_sport_verified_start_in_half_open_authored_interval_mutual_unique_v1',limitation:'current_corrected_evidence_not_past_knowledge;bounded_complete_local_support_not_upstream_completeness;extra_relative_to_selected_plan_not_unintended'}
const response=(data:unknown,status=200)=>({ok:status>=200&&status<300,status,json:async()=>data})
function inspectForm(){fireEvent.change(screen.getByLabelText('Reconciliation from'),{target:{value:body.query.oldest}});fireEvent.change(screen.getByLabelText('Reconciliation through'),{target:{value:body.query.newest}});fireEvent.change(screen.getByLabelText('Reconciliation timezone'),{target:{value:'UTC'}});fireEvent.click(screen.getByRole('button',{name:'Inspect reconciliation'}))}

describe('PlanReconciliationControls',()=>{
 it('does not GET or POST until explicit inspection/action',()=>{const fetcher=vi.fn(async(_path:string,_init?:RequestInit)=>response(body));vi.stubGlobal('fetch',fetcher);render(<PlanReconciliationControls csrfToken="x" disabled={false} revision={0}/>);expect(fetcher).not.toHaveBeenCalled()})
 it('posts only the explicitly selected recording and reloads evidence without changing active plan',async()=>{
  let latest=body
   const fetcher=vi.fn(async(path:string,init?:RequestInit)=>{if(path.startsWith('/api/plans/reconciliation?'))return response(latest);if(path==='/api/plans/reconciliation/confirmations'&&init?.method==='POST'){const req=JSON.parse(String(init.body));expect(req).toMatchObject({confirmationId:expect.any(String),plan:{planId,revision:1},sessionId,action:'match',activityId:unknownId,expectedSelectionGeneration:2,expectedConfirmationId:null,expectedDigest:body.digest,asOf:body.asOf,query:body.query});const saved={...confirmation(),confirmationId:req.confirmationId};latest={...body,sessions:[planSession('matched',[],'user_confirmation',saved)]};return response({confirmation:saved,replayed:false})}throw Error(`unexpected ${path}`)})
  vi.stubGlobal('fetch',fetcher);render(<PlanReconciliationControls csrfToken="csrf-token" disabled={false} revision={0}/>);inspectForm();await screen.findByText(/Selected base/);expect(fetcher.mock.calls.filter(([,init])=>init?.method==='POST')).toHaveLength(0)
  fireEvent.change(screen.getByLabelText(`Candidate for ${sessionId}`),{target:{value:unknownId}});fireEvent.click(screen.getByRole('button',{name:'Confirm recording match'}));await screen.findByText(/Confirmation recorded/)
  const post=fetcher.mock.calls.find(([,init])=>init?.method==='POST');expect(post?.[1]).toMatchObject({credentials:'same-origin',cache:'no-store',headers:{'X-CSRF-Token':'csrf-token','Content-Type':'application/json'}});expect(fetcher.mock.calls.filter(([path])=>path==='/api/plans/selection/activeplan')).toHaveLength(0);expect(fetcher.mock.calls.filter(([path])=>path.startsWith('/api/plans/reconciliation?'))).toHaveLength(2);expect(screen.getByText(/Saved match, attributed to authenticated_user/)).toBeInTheDocument()
 })
 it('known 409 requires explicit reinspect and never retries the write',async()=>{const fetcher=vi.fn(async(path:string,init?:RequestInit)=>path.startsWith('/api/plans/reconciliation?')?response(body):response({},409));vi.stubGlobal('fetch',fetcher);render(<PlanReconciliationControls csrfToken="csrf" disabled={false} revision={0}/>);inspectForm();await screen.findByText(/Selected base/);fireEvent.change(screen.getByLabelText(`Candidate for ${sessionId}`),{target:{value:unknownId}});fireEvent.click(screen.getByRole('button',{name:'Confirm recording match'}));expect(await screen.findByRole('alert')).toHaveTextContent('Inspect again before confirming');expect(fetcher.mock.calls.filter(([,init])=>init?.method==='POST')).toHaveLength(1);fireEvent.click(screen.getByRole('button',{name:'Inspect reconciliation'}));await waitFor(()=>expect(fetcher.mock.calls.filter(([path])=>path.startsWith('/api/plans/reconciliation?'))).toHaveLength(2));expect(fetcher.mock.calls.filter(([,init])=>init?.method==='POST')).toHaveLength(1)})
  it('csrf change aborts pending write and a valid late receipt initiates no follow-up I/O',async()=>{
    let finish:(value:ReturnType<typeof response>)=>void=()=>{};let postSignal:AbortSignal|undefined;let requestId=''
    const fetcher=vi.fn((path:string,init?:RequestInit)=>{
      if(path.startsWith('/api/plans/reconciliation?'))return Promise.resolve(response(body))
      if(init?.method==='POST'){postSignal=init.signal as AbortSignal;requestId=JSON.parse(String(init.body)).confirmationId;return new Promise<ReturnType<typeof response>>(resolve=>{finish=resolve})}
      throw Error(path)
    })
    vi.stubGlobal('fetch',fetcher);const ui=render(<PlanReconciliationControls csrfToken="first" disabled={false} revision={0}/>);inspectForm();await screen.findByText(/Selected base/)
    fireEvent.change(screen.getByLabelText(`Candidate for ${sessionId}`),{target:{value:unknownId}});fireEvent.click(screen.getByRole('button',{name:'Confirm recording match'}))
    await waitFor(()=>expect(requestId).not.toBe(''));ui.rerender(<PlanReconciliationControls csrfToken="second" disabled={false} revision={0}/>);expect(postSignal?.aborted).toBe(true)
    await act(async()=>{finish(response({confirmation:{...confirmation(),confirmationId:requestId},replayed:false}))})
    expect(screen.queryByText(/Selected base/)).not.toBeInTheDocument();expect(fetcher.mock.calls).toHaveLength(2)
    inspectForm();await screen.findByText(/Selected base/);expect(fetcher.mock.calls.filter(([path])=>path.startsWith('/api/plans/reconciliation?'))).toHaveLength(2)
  })
  it('offers an unknown-instant manual stable link with source-local provenance',async()=>{const unknown={...body,sessions:[planSession('awaiting_evidence',[],'session_end_plus_grace_not_reached')],activities:[{activity:activity(unknownId,null),state:'needs_confirmation',sessionIds:[sessionId]}]};const fetcher=vi.fn(async(path:string,init?:RequestInit)=>{if(path.startsWith('/api/plans/reconciliation?'))return response(unknown);if(init?.method==='POST'){const req=JSON.parse(String(init.body));expect(req).toMatchObject({action:'match',activityId:unknownId});return response({confirmation:{...confirmation('match',activity(unknownId,null)),confirmationId:req.confirmationId},replayed:false})}throw Error(path)});vi.stubGlobal('fetch',fetcher);render(<PlanReconciliationControls csrfToken="csrf" disabled={false} revision={0}/>);inspectForm();await screen.findByText(/Selected base/);expect(screen.getByText(/instant unknown; needs confirmation/)).toBeInTheDocument();const choice=screen.getByRole('option',{name:new RegExp(`${unknownId}.*source_local`)});fireEvent.change(screen.getByLabelText(`Candidate for ${sessionId}`),{target:{value:unknownId}});expect(choice).toBeInTheDocument();fireEvent.click(screen.getByRole('button',{name:'Confirm recording match'}));expect(await screen.findByText(/Confirmation recorded/)).toBeInTheDocument();expect(fetcher.mock.calls.filter(([,init])=>init?.method==='POST')).toHaveLength(1)})
 it('strictly rejects malformed enums, digest types, candidate references and action/activity mismatch',()=>{expect(()=>reconciliationFrom({...body,sessions:[{...planSession(),state:[]}]})).toThrow();expect(()=>reconciliationFrom({...body,digest:['a'.repeat(64)]})).toThrow();expect(()=>reconciliationFrom({...body,sessions:[planSession('ambiguous',[ 'd'.repeat(64)])]})).toThrow();expect(()=>reconciliationFrom({...body,sessions:[planSession('matched',[],'user_confirmation',confirmation('skip',activity()))]})).toThrow()})
  it('permits stale confirmation referencing deleted activity evidence while marking it for confirmation',()=>{const stale={...confirmation('match',activity('e'.repeat(64),null),1),plan:{planId,revision:1}};const v=reconciliationFrom({...body,sessions:[planSession('needs_confirmation',[],'confirmation_evidence_changed',stale)],activities:[]});expect(v.sessions[0].state).toBe('needs_confirmation')})
  it('accepts 1001 complete activities without inheriting the intake parser limit',()=>{
    const activities=Array.from({length:1001},(_,i)=>({activity:activity(i.toString(16).padStart(64,'0')),state:'extra_relative_to_selected_plan',sessionIds:[]}))
    expect(reconciliationFrom({...body,sessions:[],activities}).activities).toHaveLength(1001)
  })
  it('omitted sessions retain a usable explicit clear action for their old ownership',async()=>{
    const omitted={...body,sessions:[planSession('omitted',[],'authored_omission',confirmation())]}
    vi.stubGlobal('fetch',vi.fn(async()=>response(omitted)));render(<PlanReconciliationControls csrfToken="csrf" disabled={false} revision={0}/>);inspectForm();await screen.findByText(/Selected base/)
    expect(screen.getByRole('button',{name:'Clear saved confirmation'})).toBeEnabled()
    expect(screen.getByRole('button',{name:'Confirm recording match'})).toBeDisabled()
  })
  it('shows exact planned/actual basis and rejects mismatched range-check arithmetic and identities',async()=>{
    const check={plan:{planId,revision:1},sessionId,targetIndex:0,target:{metric:'duration',unit:'seconds',basis:'moving',minimum:100,maximum:200,effortScale:null},
      activityId:unknownId,activityRevision:1,recordSha256:recordHash,status:'above_range',reason:'assessed',actualValue:250,actualUnit:'seconds',actualField:'movingTime',actualBasis:'moving',
      source:'intervals.icu',upstreamOrigin:null,effortReport:null,policy:'authored_inclusive_range_v1'}
    const data={...body,sessions:[{...planSession('matched',[],'compatible_unique_evidence'),targetChecks:[check]}]}
    vi.stubGlobal('fetch',vi.fn(async()=>response(data)));render(<PlanReconciliationControls csrfToken="csrf" disabled={false} revision={0}/>);inspectForm()
    expect(await screen.findByText(/duration \/ moving: planned 100–200 seconds; actual 250 seconds/)).toBeInTheDocument()
    for(const altered of [{status:'within_range'},{actualBasis:'elapsed'},{plan:{planId,revision:2}},{activityId:'e'.repeat(64)},{actualValue:[250]}]){
      expect(()=>reconciliationFrom({...data,sessions:[{...data.sessions[0],targetChecks:[{...check,...altered}]}]})).toThrow()
    }
  })
  it('uncertain effort writes lock parent navigation and matching until explicit effort-history inspection',async()=>{
    const scale={scaleId:uuid,name:'Synthetic effort',minimum:1,maximum:10,step:1,higherMeansMoreEffort:true}
    const check={plan:{planId,revision:1},sessionId,targetIndex:0,target:{metric:'reported_effort',unit:'scale_points',basis:'reported',minimum:4,maximum:6,effortScale:scale},
      activityId:unknownId,activityRevision:1,recordSha256:recordHash,status:'unevaluable',reason:'measurement_missing',actualValue:null,actualUnit:null,actualField:null,actualBasis:null,
      source:null,upstreamOrigin:null,effortReport:null,policy:'authored_inclusive_range_v1'}
    const data={...body,sessions:[{...planSession('matched',[],'compatible_unique_evidence'),targetChecks:[check]}]}
    const fetcher=vi.fn(async(path:string,init?:RequestInit)=>{
      if(init?.method==='POST')return response({untrusted:'not a receipt'})
      if(path.startsWith('/api/plans/effort-reports?'))return response({activityId:unknownId,scale,currentActivity:activity(),reports:[]})
      return response(data)
    })
    const lock=vi.fn();vi.stubGlobal('fetch',fetcher);render(<PlanReconciliationControls csrfToken="csrf" disabled={false} revision={0} onStateLockChange={lock}/>)
    inspectForm();await screen.findByText(/Selected base/);fireEvent.click(screen.getByRole('button',{name:'Inspect effort report'}));await screen.findByText(/current recording available/)
    fireEvent.change(screen.getByLabelText('Effort value'),{target:{value:'7'}});fireEvent.change(screen.getByLabelText('Observed on'),{target:{value:'2026-10-15'}})
    fireEvent.change(screen.getByLabelText('Attribution'),{target:{value:'Synthetic runner'}});fireEvent.click(screen.getByRole('button',{name:'Save/correct/reaffirm report'}))
    await screen.findByText(/An effort write requires authoritative reinspection/)
    expect(lock).toHaveBeenLastCalledWith(true);expect(screen.getByRole('button',{name:'Inspect reconciliation'})).toBeDisabled()
    expect(screen.getByRole('button',{name:'Inspect effort report'})).toBeEnabled();expect(fetcher.mock.calls.filter(([,init])=>init?.method==='POST')).toHaveLength(1)
    fireEvent.click(screen.getByRole('button',{name:'Inspect effort report'}));await screen.findByText(/current recording available/)
    await waitFor(()=>expect(lock).toHaveBeenLastCalledWith(false));expect(screen.getByRole('button',{name:'Inspect reconciliation'})).toBeEnabled()
    expect(fetcher.mock.calls.filter(([,init])=>init?.method==='POST')).toHaveLength(1)
  })
})
