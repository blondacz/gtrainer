import {act,fireEvent,render,screen,waitFor} from '@testing-library/react'
import {describe,expect,it,vi} from 'vitest'
import {ActivityEffortControls} from './ActivityEffortControls'
import {effortHistoryFrom,type EffortReport} from './ActivityEffortClient'
import type {Activity} from './PlanActivity'

const aid='a'.repeat(64),sha='b'.repeat(64)
const scale={scaleId:'11111111-1111-4111-8111-111111111111',name:'Synthetic effort',minimum:1,maximum:10,step:1,higherMeansMoreEffort:true}
const activity:Activity={activityId:aid,revision:1,recordSha256:sha,sport:'Run',startLocal:'2026-10-15T10:00',startInstant:null,timeContext:'local_time_only'}
const report=(revision=1,extra:Partial<EffortReport>={}):EffortReport=>({reportId:'22222222-2222-4222-8222-222222222222',revision,previousRevision:revision===1?null:revision-1,
  activityId:aid,activityRevision:1,activityRecordSha256:sha,scale,value:5,observedOn:'2026-10-15',authorAttribution:'Synthetic runner',recordedBy:'authenticated_user',
  recordedUtc:'2026-10-16T12:00:00Z',withdrawn:false,detached:false,detachmentGeneration:0,...extra})
const history=(reports:EffortReport[]=[],currentActivity:Activity|null=activity)=>({activityId:aid,scale,currentActivity,reports})
const response=(body:unknown,status=200)=>({ok:status>=200&&status<300,status,json:async()=>body})
const inspect=()=>fireEvent.click(screen.getByRole('button',{name:'Inspect effort report'}))
function fill(value='5') {
  fireEvent.change(screen.getByLabelText('Effort value'),{target:{value}})
  fireEvent.change(screen.getByLabelText('Observed on'),{target:{value:'2026-10-15'}})
  fireEvent.change(screen.getByLabelText('Attribution'),{target:{value:'Synthetic runner'}})
}
describe('ActivityEffortControls',()=>{
  it('requires explicit inspection and one exact attributed report write, never plan mutation',async()=>{
    const changed=vi.fn(),fetcher=vi.fn(async(path:string,init?:RequestInit)=>{
      if(init?.method==='POST')return response({report:report(),replayed:false})
      if(path.startsWith('/api/plans/effort-reports?'))return response(history())
      throw Error(path)
    })
    vi.stubGlobal('fetch',fetcher);render(<ActivityEffortControls csrfToken="csrf" activity={activity} scale={scale} disabled={false} onChanged={changed}/>)
    expect(fetcher).not.toHaveBeenCalled();inspect();await screen.findByText(/current recording available/);fill()
    fireEvent.click(screen.getByRole('button',{name:'Save/correct/reaffirm report'}));await screen.findByText(/acknowledged/)
    expect(fetcher).toHaveBeenCalledTimes(2);expect(changed).toHaveBeenCalledTimes(1)
    expect(fetcher.mock.calls[0][1]).toMatchObject({method:'GET',cache:'no-store'})
    const [path,init]=fetcher.mock.calls[1]
    expect(path).toBe('/api/plans/effort-reports');expect(init).toMatchObject({method:'POST',cache:'no-store',headers:{'X-CSRF-Token':'csrf'}})
    expect(JSON.parse(String(init?.body))).toEqual({operationId:expect.any(String),activityId:aid,scaleId:scale.scaleId,expectedReportId:null,expectedRevision:0,
      expectedDetachmentGeneration:0,expectedActivityRevision:1,expectedRecordSha256:sha,action:'report',value:5,observedOn:'2026-10-15',authorAttribution:'Synthetic runner',origin:null})
  })
  it('withdraws detached testimony without a current recording and preserves its original evidence',async()=>{
    const prior=report(1,{detached:true,detachmentGeneration:2,activityRecordSha256:'c'.repeat(64)})
    const fetcher=vi.fn(async(_path:string,init?:RequestInit)=>init?.method==='POST'?response({report:{...prior,revision:2,previousRevision:1,withdrawn:true},replayed:false}):response(history([prior],null)))
    const changed=vi.fn();vi.stubGlobal('fetch',fetcher)
    render(<ActivityEffortControls csrfToken="csrf" activity={activity} scale={scale} disabled={false} onChanged={changed}/>);inspect();await screen.findByText(/recording missing/)
    expect(screen.getByRole('button',{name:'Save/correct/reaffirm report'})).toBeDisabled()
    fireEvent.click(screen.getByRole('button',{name:'Withdraw active report'}));await screen.findByText(/acknowledged/)
    expect(JSON.parse(String(fetcher.mock.calls[1][1]?.body))).toMatchObject({action:'withdraw',expectedRevision:1,expectedDetachmentGeneration:2,
      value:null,observedOn:null,authorAttribution:null,expectedRecordSha256:null,expectedActivityRevision:null,origin:null})
    expect(changed).toHaveBeenCalledTimes(1)
  })
  it('reimport leaves detached reports inactive until explicit reaffirmation binds current record',async()=>{
    const current={...activity,revision:2,recordSha256:'d'.repeat(64)},prior=report(1,{detached:true,detachmentGeneration:1})
    const fetcher=vi.fn(async(_path:string,init?:RequestInit)=>init?.method==='POST'?response({report:report(2,{activityRevision:2,activityRecordSha256:current.recordSha256,
      detachmentGeneration:1,value:7}),replayed:false}):response(history([prior],current)))
    const changed=vi.fn();vi.stubGlobal('fetch',fetcher)
    render(<ActivityEffortControls csrfToken="csrf" activity={current} scale={scale} disabled={false} onChanged={changed}/>);inspect();await screen.findByText(/detached from imported recording/)
    expect(fetcher).toHaveBeenCalledTimes(1);fill('7');fireEvent.click(screen.getByRole('button',{name:'Save/correct/reaffirm report'}));await screen.findByText(/acknowledged/)
    expect(JSON.parse(String(fetcher.mock.calls[1][1]?.body))).toMatchObject({expectedReportId:prior.reportId,expectedRevision:1,expectedDetachmentGeneration:1,
      expectedActivityRevision:2,expectedRecordSha256:current.recordSha256,value:7})
    expect(changed).toHaveBeenCalledTimes(1)
  })
  it('409 requires explicit reinspection and never retries a mutation',async()=>{
    const fetcher=vi.fn(async(_path:string,init?:RequestInit)=>init?.method==='POST'?response({},409):response(history()))
    vi.stubGlobal('fetch',fetcher);render(<ActivityEffortControls csrfToken="csrf" activity={activity} scale={scale} disabled={false} onChanged={vi.fn()}/>);inspect();await screen.findByText(/current recording available/)
    fill();fireEvent.click(screen.getByRole('button',{name:'Save/correct/reaffirm report'}));expect(await screen.findByRole('alert')).toHaveTextContent(/Inspect again/)
    expect(fetcher).toHaveBeenCalledTimes(2);inspect();await screen.findByText(/current recording available/)
    expect(fetcher.mock.calls.filter(([,init])=>init?.method==='POST')).toHaveLength(1)
  })
  it('token changes abort a pending write and a valid late acknowledgement causes no callback or follow-up',async()=>{
    let finish:(value:ReturnType<typeof response>)=>void=()=>{};let signal:AbortSignal|undefined
    const changed=vi.fn(),fetcher=vi.fn((_:string,init?:RequestInit)=>{
      if(init?.method==='POST'){signal=init.signal as AbortSignal;return new Promise<ReturnType<typeof response>>(resolve=>{finish=resolve})}
      return Promise.resolve(response(history()))
    })
    vi.stubGlobal('fetch',fetcher);const ui=render(<ActivityEffortControls csrfToken="first" activity={activity} scale={scale} disabled={false} onChanged={changed}/>)
    inspect();await screen.findByText(/current recording available/);fill();fireEvent.click(screen.getByRole('button',{name:'Save/correct/reaffirm report'}))
    await waitFor(()=>expect(fetcher).toHaveBeenCalledTimes(2));ui.rerender(<ActivityEffortControls csrfToken="second" activity={activity} scale={scale} disabled={false} onChanged={changed}/>)
    expect(signal?.aborted).toBe(true);await act(async()=>{finish(response({report:report(),replayed:false}))})
    expect(changed).not.toHaveBeenCalled();expect(fetcher).toHaveBeenCalledTimes(2);expect(screen.queryByText(/acknowledged/)).not.toBeInTheDocument()
  })
  it('rejects truncated, mismatched and malformed histories rather than displaying partial evidence',()=>{
    for(const bad of [history([report(2)]),history([{...report(),withdrawn:[]} as unknown as EffortReport]),
      {...history([report()]),currentActivity:{...activity,activityId:'f'.repeat(64)}},
      {...history([report()]),scale:{...scale,name:'different frozen definition'}},history([report(1,{value:5.5})]),
      {...history(),scale:{...scale,minimum:-1e308,maximum:1e308}},
      {...history([report(1,{scale:{...scale,step:Number.MIN_VALUE}})]),scale:{...scale,step:Number.MIN_VALUE}}])expect(()=>effortHistoryFrom(bad)).toThrow()
    expect(effortHistoryFrom(history([report(1,{detached:true})],null)).reports[0].detached).toBe(true)
  })
})
