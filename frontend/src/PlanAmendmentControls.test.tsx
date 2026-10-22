import {act,fireEvent,render,screen,waitFor} from '@testing-library/react'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {PlanAmendmentControls} from './PlanAmendmentControls'
import {PlansPanel} from './PlansPanel'
import {amendmentInspection,amendmentSource,amendmentReceipt,amendmentIndex,appliedInspection,created,expires,proposalId,operationId} from './amendment.test-fixtures'
import type {AmendmentInspection} from './PlanAmendmentClient'
import type {PlannedSession} from './PlanClient'
const csrf='a'.repeat(43),endpoint=`/api/plans/amendment-proposals/${proposalId}`
function response(body:unknown,status=200){return {ok:status>=200&&status<300,status,json:async()=>body}}
beforeEach(()=>{vi.spyOn(Date,'now').mockReturnValue(Date.parse(created));vi.spyOn(crypto,'randomUUID').mockReturnValue(operationId)})
afterEach(()=>{vi.restoreAllMocks()})
function harness(options:{inspection?:AmendmentInspection;source?:unknown;post?:(path:string,input:RequestInit)=>Promise<unknown>}={}) {
  const applied=vi.fn(async()=>{}),overlay=vi.fn(),lock=vi.fn()
  const fetcher=vi.fn(async(path:string,input?:RequestInit)=>{
    if(input?.method==='POST')return options.post?options.post(path,input):response(path.endsWith('/approval')?amendmentReceipt():{
      ...(options.inspection??amendmentInspection()),disposition:'rejected',state:'rejected',reason:'user_rejected',rejectedUtc:created,approval:'unavailable_terminal_or_noncurrent'})
    if(path.startsWith('/api/plans/amendment-proposals?'))return response(amendmentIndex())
    if(path===`${endpoint}/inspection`)return response(options.inspection??amendmentInspection())
    if(path===`/api/reviews/${amendmentSource().snapshotId}`)return response(options.source??amendmentSource())
    throw Error('Unexpected synthetic request')
  })
  vi.stubGlobal('fetch',fetcher)
  const props={csrfToken:csrf,selection:{selected:amendmentInspection().proposal!.basePlan,generation:1},reviewEpoch:0,onApplied:applied,onOverlayChange:overlay,onStateLockChange:lock}
  const view=render(<PlanAmendmentControls {...props}/>)
  return {fetcher,applied,overlay,lock,view,props}
}
async function open() {
  fireEvent.click(screen.getByRole('button',{name:'Browse stored proposals'}))
  fireEvent.click(await screen.findByRole('button',{name:`Inspect proposal ${proposalId}`}))
  await screen.findByRole('button',{name:'Reinspect authoritative proposal'})
}
async function readSource() {
  await waitFor(()=>expect(screen.getByRole('button',{name:'Inspect bound source evidence'})).toBeEnabled())
  fireEvent.click(screen.getByRole('button',{name:'Inspect bound source evidence'}))
  await waitFor(()=>expect(screen.getByRole('checkbox',{name:/I reviewed every before/})).toBeEnabled())
}
function attest(){fireEvent.click(screen.getByRole('checkbox',{name:/I reviewed every before/}))}

describe('exact local amendment review',()=>{
  it('stays inert until explicit browsing and binds source, complete constraints and exact attestation before one approval',async()=>{
    const {fetcher,applied,overlay,lock}=harness()
    expect(fetcher).not.toHaveBeenCalled()
    await open()
    expect(screen.getByText(/Stored disposition only—not current approval status/)).toBeInTheDocument()
    expect(screen.getByText(/Constraint absent from the source packet/)).toBeInTheDocument()
    expect(screen.getByRole('button',{name:'Approve exact local amendment'})).toBeDisabled()
    expect(overlay).toHaveBeenLastCalledWith(null)
    await readSource()
    expect(overlay).toHaveBeenLastCalledWith(expect.objectContaining({proposalId,selected:expect.any(Array)}))
    expect(screen.getAllByText('Original synthetic notes').length).toBe(2)
    expect(screen.getByText(/Unchanged fields: author, sport, objective, notes, permissions, targets, schedule/)).toBeInTheDocument()
    attest();const button=screen.getByRole('button',{name:'Approve exact local amendment'})
    fireEvent.click(button);fireEvent.click(button)
    await screen.findByText(/Exact local amendment applied as revision 2/)
    const writes=fetcher.mock.calls.filter(([,input])=>input?.method==='POST')
    expect(writes).toHaveLength(1);expect(writes[0]?.[0]).toBe(`${endpoint}/approval`)
    expect(JSON.parse(String(writes[0]?.[1]?.body))).toEqual({operationId,expectedBasePlan:amendmentInspection().proposal!.basePlan,expectedProposalSha256:'a'.repeat(64),expectedSelectionGeneration:1})
    expect(writes[0]?.[1]?.headers).toMatchObject({'X-CSRF-Token':csrf})
    expect(applied).toHaveBeenCalledOnce();expect(overlay).toHaveBeenLastCalledWith(null);expect(lock).toHaveBeenLastCalledWith(true)
    expect(fetcher.mock.calls.some(([path])=>/generation|execution|interpretation|consent/.test(path))).toBe(false)
  })
  it('removes readiness on source refresh failure, relevant review epoch or expiry without posting',async()=>{
    const {fetcher,overlay,view,props}=harness()
    await open();await readSource();attest()
    fetcher.mockImplementationOnce(async()=>response({},503))
    fireEvent.click(screen.getByRole('button',{name:'Refresh snapshot status (read only)'}))
    await waitFor(()=>expect(screen.getByRole('button',{name:'Approve exact local amendment'})).toBeDisabled())
    expect(overlay).toHaveBeenLastCalledWith(null)
    await waitFor(()=>expect(screen.queryByRole('article',{name:/Exact omit_session diff/})).not.toBeInTheDocument())
    expect(screen.queryByRole('region',{name:'Complete captured amendment constraints'})).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button',{name:'Reinspect authoritative proposal'}))
    await readSource();attest()
    view.rerender(<PlanAmendmentControls {...props} reviewEpoch={1}/>)
    expect(screen.getByRole('button',{name:'Approve exact local amendment'})).toBeDisabled()
    fireEvent.click(screen.getByRole('button',{name:'Reinspect authoritative proposal'}));await readSource();attest()
    vi.mocked(Date.now).mockReturnValue(Date.parse(expires))
    act(()=>{window.dispatchEvent(new Event('focus'))})
    expect(screen.getByRole('button',{name:'Approve exact local amendment'})).toBeDisabled()
    expect(screen.queryByRole('article',{name:/Exact omit_session diff/})).not.toBeInTheDocument()
    expect(screen.queryByRole('region',{name:'Evidence snapshot inspection'})).not.toBeInTheDocument()
    expect(fetcher.mock.calls.filter(([,input])=>input?.method==='POST')).toHaveLength(0)
  })
  it.each(['stale','expired','no_change','needs_confirmation','rejected','applied'])('never enables approval for %s',async state=>{
    const value=amendmentInspection()
    if(state==='stale'||state==='expired'){value.state=state;value.freshness=state;value.proposal=null;value.reviewConstraints=null;value.approval='unavailable_terminal_or_noncurrent'}
    else if(state==='applied')Object.assign(value,appliedInspection())
    else if(state==='rejected'){value.state='rejected';value.disposition='rejected';value.reason='user_rejected';value.rejectedUtc=created;value.approval='unavailable_terminal_or_noncurrent'}
    else {value.state=state;value.disposition=state as 'no_change'|'needs_confirmation';value.proposal!.outcome=state==='no_change'?'no_change':'needs_clarification';value.proposal!.selected=[];value.approval='unavailable_terminal_or_noncurrent'}
    const {fetcher,overlay}=harness({inspection:value})
    await open()
    expect(screen.getByRole('button',{name:'Approve exact local amendment'})).toBeDisabled()
    expect(overlay).toHaveBeenLastCalledWith(null)
    expect(fetcher.mock.calls.filter(([,input])=>input?.method==='POST')).toHaveLength(0)
  })
  it('rejects a mismatched source and allows explicit rejection without applying or inference',async()=>{
    const source={...amendmentSource(),packetSha256:'0'.repeat(64)}
    const {applied,fetcher}=harness({source})
    await open();fireEvent.click(screen.getByRole('button',{name:'Inspect bound source evidence'}))
    await screen.findByRole('alert')
    expect(screen.getByRole('button',{name:'Approve exact local amendment'})).toBeDisabled()
    fireEvent.click(screen.getByRole('button',{name:'Reinspect authoritative proposal'}))
    await waitFor(()=>expect(screen.getByRole('button',{name:'Reject exact proposal'})).toBeEnabled())
    fireEvent.click(screen.getByRole('button',{name:'Reject exact proposal'}))
    await screen.findByText(/Exact proposal rejected/)
    expect(applied).not.toHaveBeenCalled()
    expect(fetcher.mock.calls.filter(([,input])=>input?.method==='POST').map(([path])=>path)).toEqual([`${endpoint}/rejection`])
  })
  it('preserves one exact operation after a lost response and pending recovery; retries only on explicit action',async()=>{
    let posts=0
    const {fetcher,applied,overlay}=harness({post:async()=>{if(posts++===0)throw TypeError('Synthetic lost response');return response(amendmentReceipt())}})
    await open();await readSource();attest();fireEvent.click(screen.getByRole('button',{name:'Approve exact local amendment'}))
    await screen.findByText(/Outcome unknown or response invalid/)
    expect(screen.getByRole('button',{name:'Close review'})).toBeDisabled();expect(overlay).toHaveBeenLastCalledWith(null)
    fireEvent.click(screen.getByRole('button',{name:'Reinspect authoritative proposal'}))
    await screen.findByText(/Inspection alone does not prove/)
    expect(posts).toBe(1)
    fireEvent.click(screen.getByRole('button',{name:'Resolve original request using the same exact payload'}))
    await screen.findByText(/Exact local amendment applied as revision 2/)
    const writes=fetcher.mock.calls.filter(([,input])=>input?.method==='POST')
    expect(writes).toHaveLength(2);expect(writes[0]?.[1]?.body).toBe(writes[1]?.[1]?.body);expect(applied).toHaveBeenCalledOnce()
  })
  it('recovers an applied receipt without another POST and distinguishes failed refresh from unknown approval',async()=>{
    const {fetcher,applied}=harness({post:async()=>response({...amendmentReceipt(),operationId:proposalId})})
    await open();await readSource();attest();fireEvent.click(screen.getByRole('button',{name:'Approve exact local amendment'}))
    await screen.findByText(/Outcome unknown or response invalid/)
    fetcher.mockImplementationOnce(async()=>response(appliedInspection()));applied.mockRejectedValueOnce(Error('Synthetic refresh failure'))
    fireEvent.click(screen.getByRole('button',{name:'Reinspect authoritative proposal'}))
    await screen.findByText(/Approval is applied, but plan\/calendar refresh failed/)
    expect(screen.getByRole('button',{name:'Approve exact local amendment'})).toBeDisabled()
    expect(screen.getByRole('button',{name:'Close review'})).toBeEnabled()
    expect(fetcher.mock.calls.filter(([,input])=>input?.method==='POST')).toHaveLength(1)
  })
  it('renders all eight exact diffs and treats authored markup as plain text',async()=>{
    const inspection=amendmentInspection(),original=inspection.proposal!.selected[0]
    inspection.proposal!.selected=Array.from({length:8},(_,index)=>{
      const sessionId=`00000000-0000-4000-8000-${String(index+1).padStart(12,'0')}`
      const before={...original.before,sessionId,notes:'<img src=x onerror=alert(1)> Synthetic plain text'}
      return {...original,candidateId:String(index+1).repeat(64),sessionId,before,after:{...before,omitted:true}}
    })
    inspection.proposal!.explanations=inspection.proposal!.selected.map(candidate=>({...inspection.proposal!.explanations[0],
      sources:[{kind:'plan_session',id:candidate.sessionId,revision:1}]}))
    inspection.reviewConstraints!.occupancy=inspection.proposal!.selected.map(c=>({sessionId:c.sessionId,schedule:c.before.schedule,omitted:false}))
    const source=amendmentSource()
    source.packet.plan.sessions=inspection.proposal!.selected.map(c=>c.before)
    harness({inspection,source})
    await open();await readSource()
    expect(screen.getAllByRole('article',{name:/Exact omit_session diff for/})).toHaveLength(8)
    expect(screen.getAllByRole('region',{name:/Before session/})).toHaveLength(8)
    expect(screen.getAllByRole('region',{name:/After session/})).toHaveLength(8)
    expect(document.querySelector('img')).toBeNull()
  })
  it('hides cached private diffs and constraints after a removed proposal inspection',async()=>{
    const {fetcher,overlay}=harness()
    await open();await readSource()
    fetcher.mockImplementationOnce(async()=>response({},404))
    fireEvent.click(screen.getByRole('button',{name:'Reinspect authoritative proposal'}))
    await screen.findByText(/Cached details, approval readiness and draft overlay were removed/)
    expect(screen.queryByText(/Constraint absent from the source packet/)).not.toBeInTheDocument()
    expect(screen.queryByRole('article',{name:/Exact omit_session diff/})).not.toBeInTheDocument()
    expect(overlay).toHaveBeenLastCalledWith(null)
    expect(screen.getByRole('button',{name:'Approve exact local amendment'})).toBeDisabled()
  })
  it('revokes details and attestation when selected revision changes',async()=>{
    const {view,props,overlay,fetcher}=harness()
    await open();await readSource();attest()
    view.rerender(<PlanAmendmentControls {...props} selection={{generation:2,selected:{...props.selection.selected,revision:2}}}/>)
    expect(screen.getByRole('button',{name:'Approve exact local amendment'})).toBeDisabled()
    expect(screen.queryByRole('article',{name:/Exact omit_session diff/})).not.toBeInTheDocument()
    expect(screen.queryByText(/Constraint absent from the source packet/)).not.toBeInTheDocument()
    expect(overlay).toHaveBeenLastCalledWith(null)
    expect(fetcher.mock.calls.filter(([,input])=>input?.method==='POST')).toHaveLength(0)
  })
  it('ignores a late source read after unmount and does not restore readiness on remount',async()=>{
    const {view,props,fetcher,overlay}=harness()
    await open()
    let finish!:(value:ReturnType<typeof response>)=>void
    fetcher.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}))
    fireEvent.click(screen.getByRole('button',{name:'Inspect bound source evidence'}))
    view.unmount()
    const calls=overlay.mock.calls.length
    render(<PlanAmendmentControls {...props}/>)
    await act(async()=>{finish(response(amendmentSource()))})
    expect(overlay.mock.calls.slice(calls).every(([value])=>value===null)).toBe(true)
    expect(screen.queryByRole('button',{name:'Approve exact local amendment'})).not.toBeInTheDocument()
    expect(screen.getByRole('button',{name:'Browse stored proposals'})).toBeEnabled()
  })
  it('shows move and duration changes with exact before/after values and unchanged fields',async()=>{
    const inspection=amendmentInspection(),original=inspection.proposal!.selected[0]
    const movable={...original.before,permissions:{...original.before.permissions,move:{earliestStart:'2026-04-01',latestStart:'2026-04-02'}}}
    const move={...original,before:movable,operation:'move_session' as const,after:{...movable,schedule:{...movable.schedule,date:'2026-04-02'}}}
    const sessionId='00000000-0000-4000-8000-000000000099'
    const before:PlannedSession={...original.before,sessionId,targets:[{metric:'duration',minimum:3600,maximum:3600,unit:'seconds',basis:'elapsed',effortScale:null}],
      permissions:{...original.before.permissions,reduceDuration:{basis:'elapsed',minimumSeconds:1800,maximumSeconds:3600}}}
    const reduce={...original,candidateId:'9'.repeat(64),sessionId,operation:'reduce_duration' as const,before,after:{...before,targets:[{...before.targets[0],minimum:1800,maximum:1800}]}}
    inspection.proposal!.selected=[move,reduce]
    inspection.proposal!.explanations=[inspection.proposal!.explanations[0],{...inspection.proposal!.explanations[0],sources:[{kind:'plan_session',id:sessionId,revision:1}]}]
    inspection.reviewConstraints!.occupancy=inspection.proposal!.selected.map(c=>({sessionId:c.sessionId,schedule:c.before.schedule,omitted:false}))
    const source=amendmentSource();source.packet.plan.sessions=inspection.proposal!.selected.map(c=>c.before)
    harness({inspection,source});await open();await readSource()
    expect(screen.getByRole('article',{name:/Exact move_session diff/})).toHaveTextContent('2026-04-02')
    expect(screen.getByRole('article',{name:/Exact reduce_duration diff/})).toHaveTextContent('3600–3600 seconds; basis elapsed')
    expect(screen.getByRole('article',{name:/Exact reduce_duration diff/})).toHaveTextContent('1800–1800 seconds; basis elapsed')
    expect(screen.getByRole('heading',{name:'Stored plan amendments'})).toHaveFocus()
  })
})

describe('plans and draft calendar integration',()=>{
  it('keeps pending overlay separate, locks unrelated edits and refreshes authoritative plan/calendar once without losing dates',async()=>{
    const initial=amendmentInspection(),base=initial.proposal!.basePlan,session=initial.proposal!.selected[0].before
    let applied=false
    const plan=()=>({reference:{...base,revision:applied?2:1},previousRevision:applied?1:null,recordedBy:'authenticated_user',recordedUtc:created,
      definition:{title:'Synthetic plan',author:session.author,sessions:[{...session,omitted:applied}],policy:initial.reviewConstraints!.policy}})
    const selection=()=>({generation:applied?2:1,selected:plan().reference})
    const fetcher=vi.fn(async(path:string,input?:RequestInit)=>{
      if(path==='/api/plans')return response({plans:[plan()],selection:selection()})
      if(path.startsWith('/api/plans/amendment-proposals?'))return response(amendmentIndex())
      if(path===`${endpoint}/inspection`)return response(initial)
      if(path===`${endpoint}/approval`){applied=true;return response(amendmentReceipt())}
      if(path===`/api/reviews/${amendmentSource().snapshotId}`)return response(amendmentSource())
      if(path.startsWith('/api/plans/calendar?'))return response({asOf:created,from:'2026-03-30',until:'2026-04-05',timeZone:'UTC',selection:selection(),
        items:[{plan:plan().reference,sessionId:session.sessionId,kind:'authored_session',schedule:session.schedule,sport:session.sport,objective:session.objective,omitted:applied,amendmentEligible:!applied}],limitation:'Synthetic local coverage'})
      if(path.startsWith('/api/commitments/calendar?'))return response({oldest:'2026-03-30',newest:'2026-04-05',timeZone:'UTC',constraintsBlocked:false,coverage:'local only',needsReconfirmation:[],occurrences:[]})
      if(path.startsWith('/api/history?'))return response({activities:[]})
      throw Error(`Unexpected synthetic request ${path} ${input?.method}`)
    })
    vi.stubGlobal('fetch',fetcher);const lock=vi.fn();render(<PlansPanel csrfToken={csrf} onNavigationLockChange={lock}/>)
    await screen.findByText('Synthetic plan · revision 1')
    for(const [name,value]of [['Calendar from','2026-03-30'],['Calendar through','2026-04-05'],['Calendar timezone','UTC']])fireEvent.change(screen.getByLabelText(name!),{target:{value}})
    fireEvent.click(screen.getByRole('button',{name:'Inspect local calendar'}));await screen.findByText(/Authored session: run/)
    await open();await readSource()
    expect(await screen.findByRole('complementary',{name:'Pending amendment draft overlay'})).toHaveTextContent('Draft proposal—not applied')
    expect(screen.getByRole('button',{name:'Edit authored revision'})).toBeDisabled();expect(lock).toHaveBeenLastCalledWith(true)
    expect(applied).toBe(false)
    attest();fireEvent.click(screen.getByRole('button',{name:'Approve exact local amendment'}))
    await screen.findByText('Synthetic plan · revision 2')
    await waitFor(()=>expect(fetcher.mock.calls.filter(([path])=>path.startsWith('/api/plans/calendar?'))).toHaveLength(2))
    expect(screen.getByLabelText('Calendar from')).toHaveValue('2026-03-30');expect(screen.getByLabelText('Calendar through')).toHaveValue('2026-04-05')
    expect(screen.queryByRole('complementary',{name:'Pending amendment draft overlay'})).not.toBeInTheDocument()
    expect(fetcher.mock.calls.filter(([,input])=>input?.method==='POST')).toHaveLength(1)
  })
})
