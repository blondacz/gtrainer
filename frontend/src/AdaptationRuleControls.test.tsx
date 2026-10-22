import {afterEach,describe,expect,it,vi} from 'vitest'
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react'
import {AdaptationRuleControls} from './AdaptationRuleControls'
import {signalRule,signalReport,workloadRule,workloadReport} from './adaptation.test-fixtures'

afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.restoreAllMocks()})
const response=(body:unknown,status=200)=>({ok:status>=200&&status<300,status,json:async()=>body})
function fillSignal(){
  for(const [label,value] of Object.entries({'Time zone':'UTC','Signal field':'hrv',Intermediary:'synthetic','Upstream origin':'synthetic',Method:'measurement',Basis:'daily',Direction:'low','Boundary type':'absolute_limit',
    'Baseline trailing days':'3','Baseline minimum samples':'1','Baseline minimum coverage':'0','Maximum evidence age days':'3','Trigger magnitude':'20','Reset magnitude':'30','Trigger persistence days':'1','Reset persistence days':'1','Cooldown seconds':'60'}))
    fireEvent.change(screen.getByLabelText(label),{target:{value}})
}
async function load(){fireEvent.click(screen.getByRole('button',{name:'Load rules'}));await screen.findByText(/Authoritative rules loaded/)}
describe('AdaptationRuleControls',()=>{
  it('starts inert and disabled, saves explicit configuration with exact CSRF receipt and no model request',async()=>{
    const fetcher=vi.fn(async(_path:string,init?:RequestInit)=>{
      if(init?.method==='POST'){
        const body=JSON.parse(String(init.body));return response({rule:{...signalRule(),ruleId:body.ruleId,enabled:body.enabled,configuration:body.configuration},replayed:false})
      }
      return response([])
    })
    vi.stubGlobal('fetch',fetcher);const lock=vi.fn()
    render(<AdaptationRuleControls csrfToken="secret" disabled={false} revision={1} onStateLockChange={lock}/> )
    expect(fetcher).not.toHaveBeenCalled();expect(lock).toHaveBeenLastCalledWith(false)
    await load();expect(screen.getByLabelText('Time zone')).toHaveValue('')
    expect(screen.getByLabelText('Enable this configured rule explicitly')).not.toBeChecked()
    fillSignal();expect(lock).toHaveBeenLastCalledWith(true)
    fireEvent.click(screen.getByRole('button',{name:'Save rule configuration'}))
    await screen.findByText(/Exact rule configuration receipt acknowledged/)
    expect(fetcher).toHaveBeenCalledTimes(2)
    const [path,init]=fetcher.mock.calls[1]
    expect(path).toBe('/api/plans/signal-rules');expect(init).toMatchObject({method:'POST',credentials:'same-origin',cache:'no-store',headers:{'X-CSRF-Token':'secret'}})
    expect(JSON.parse(String(init?.body))).toMatchObject({expectedRevision:0,enabled:false,configuration:{selector:{unit:'ms'},triggerMagnitude:20}})
    await waitFor(()=>expect(lock).toHaveBeenLastCalledWith(false))
  })
  it('explicitly enables or disables an existing exact revision, never silently allocating a new rule',async()=>{
    const rule=signalRule(),fetcher=vi.fn(async(_path:string,init?:RequestInit)=>init?.method==='POST'?response({rule:{...rule,revision:2,enabled:false},replayed:false}):response([rule]))
    vi.stubGlobal('fetch',fetcher);render(<AdaptationRuleControls csrfToken="secret" disabled={false} revision={1}/>);await load()
    fireEvent.change(screen.getByLabelText('Existing rule'),{target:{value:rule.ruleId}})
    expect(screen.getByLabelText('Enable this configured rule explicitly')).toBeChecked()
    fireEvent.click(screen.getByLabelText('Enable this configured rule explicitly'))
    fireEvent.click(screen.getByRole('button',{name:'Save rule configuration'}));await screen.findByText(/Exact rule configuration receipt acknowledged/)
    expect(JSON.parse(String(fetcher.mock.calls[1][1]?.body))).toMatchObject({ruleId:rule.ruleId,expectedRevision:1,enabled:false,configuration:rule.configuration})
  })
  it('rejects collapsed reset hysteresis without sending a request',async()=>{
    const fetcher=vi.fn(async()=>response([]));vi.stubGlobal('fetch',fetcher)
    render(<AdaptationRuleControls csrfToken="x" disabled={false} revision={1}/>);await load();fillSignal()
    fireEvent.change(screen.getByLabelText('Reset magnitude'),{target:{value:'20'}})
    fireEvent.click(screen.getByRole('button',{name:'Save rule configuration'}));await screen.findByText(/valid reset hysteresis/)
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
  it('shows actual baseline dates and source identities from read-only evaluation',async()=>{
    const rule=signalRule(),report=signalReport(),fetcher=vi.fn(async(path:string)=>response(path==='/api/plans/signal-rules'?[rule]:report))
    vi.stubGlobal('fetch',fetcher);render(<AdaptationRuleControls csrfToken="secret" disabled={false} revision={1}/>);await load()
    fireEvent.change(screen.getByLabelText('Existing rule'),{target:{value:rule.ruleId}})
    fireEvent.change(screen.getByLabelText('Oldest evaluation date'),{target:{value:report.query.oldest}})
    fireEvent.change(screen.getByLabelText('Newest evaluation date'),{target:{value:report.query.newest}})
    fireEvent.click(screen.getByRole('button',{name:'Inspect rule'}))
    expect(await screen.findByText(/Baseline dates 2026-03-27–2026-03-29/)).toBeInTheDocument()
    expect(screen.getByText(/wellness\/synthetic\/2026-03-30\/hrv/)).toBeInTheDocument()
    expect(fetcher.mock.calls[1][0]).toContain('/inspection?oldest=2026-03-30&newest=2026-03-30')
    expect(fetcher.mock.calls).toHaveLength(2)
  })
  it('supports cumulative workload configuration and needs-confirmation evidence without a save',async()=>{
    const rule=workloadRule(),report=workloadReport();report.status='unevaluable';report.items[0]={...report.items[0],status:'needs_confirmation',reason:'timing_or_match_unresolved',value:null};report.supportingActivityIds=[]
    const fetcher=vi.fn(async(path:string)=>response(path==='/api/plans/workload-rules'?[rule]:report))
    vi.stubGlobal('fetch',fetcher);render(<AdaptationRuleControls csrfToken="x" disabled={false} revision={1}/> )
    fireEvent.change(screen.getByLabelText('Rule type'),{target:{value:'workload'}});await load()
    fireEvent.change(screen.getByLabelText('Existing rule'),{target:{value:rule.ruleId}})
    fireEvent.click(screen.getByRole('button',{name:'Inspect rule'}))
    expect(await screen.findByText(/needs_confirmation \(timing_or_match_unresolved\)/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Aggregation'),{target:{value:'cumulative'}})
    expect(screen.getByRole('button',{name:'Inspect rule'})).toBeDisabled()
    expect(screen.getByLabelText('Threshold')).toHaveValue(100)
    expect(fetcher.mock.calls).toHaveLength(2)
  })
  it('saves cumulative workload only with its explicit compatible metric source and existing revision',async()=>{
    const rule=workloadRule(),fetcher=vi.fn(async(_path:string,init?:RequestInit)=>{
      if(init?.method==='POST'){
        const input=JSON.parse(String(init.body));return response({rule:{...rule,revision:2,configuration:input.configuration},replayed:false})
      }
      return response([rule])
    })
    vi.stubGlobal('fetch',fetcher);render(<AdaptationRuleControls csrfToken="csrf" disabled={false} revision={1}/> )
    fireEvent.change(screen.getByLabelText('Rule type'),{target:{value:'workload'}});await load()
    fireEvent.change(screen.getByLabelText('Existing rule'),{target:{value:rule.ruleId}})
    fireEvent.change(screen.getByLabelText('Aggregation'),{target:{value:'cumulative'}})
    fireEvent.click(screen.getByRole('button',{name:'Save rule configuration'}));await screen.findByText(/Exact rule configuration receipt acknowledged/)
    expect(fetcher.mock.calls[1][0]).toBe('/api/plans/workload-rules')
    expect(JSON.parse(String(fetcher.mock.calls[1][1]?.body))).toMatchObject({ruleId:rule.ruleId,expectedRevision:1,enabled:true,
      configuration:{aggregation:'cumulative',metric:'duration',unit:'seconds',basis:'moving',source:'intervals.icu',threshold:100}})
  })
  it('requires authoritative reload after a conflict and discards cached evaluation before another write',async()=>{
    const fetcher=vi.fn(async(_path:string,init?:RequestInit)=>init?.method==='POST'?response({},409):response([]))
    vi.stubGlobal('fetch',fetcher);render(<AdaptationRuleControls csrfToken="x" disabled={false} revision={1}/>);await load();fillSignal()
    fireEvent.click(screen.getByRole('button',{name:'Save rule configuration'}));await screen.findByText(/Rule changed. Reload and review/)
    expect(screen.getByLabelText('Rule type')).toBeDisabled()
    expect(screen.getByRole('button',{name:'Load rules'})).toBeEnabled()
    await load();expect(screen.getByLabelText('Time zone')).toHaveValue('')
    expect(fetcher.mock.calls.filter(([,init])=>init?.method==='POST')).toHaveLength(1)
  })
  it('locks unknown or mismatched save outcomes until authoritative reload; no write retry or kind-switch bypass',async()=>{
    let writes=0
    const fetcher=vi.fn(async(_path:string,init?:RequestInit)=>{if(init?.method==='POST'){writes++;return response({rule:{...signalRule(),ruleId:'invalid'},replayed:false})}return response([])})
    vi.stubGlobal('fetch',fetcher);const lock=vi.fn()
    render(<AdaptationRuleControls csrfToken="x" disabled={false} revision={1} onStateLockChange={lock}/>);await load();fillSignal()
    fireEvent.click(screen.getByRole('button',{name:'Save rule configuration'}));await screen.findByText(/Save outcome unknown/)
    expect(lock).toHaveBeenLastCalledWith(true);expect(screen.getByLabelText('Rule type')).toBeDisabled()
    expect(screen.queryByRole('button',{name:'Save rule configuration'})).not.toBeInTheDocument();expect(writes).toBe(1)
    await load();await waitFor(()=>expect(lock).toHaveBeenLastCalledWith(false));expect(writes).toBe(1)
  })
  it('suppresses late private list responses after identity change and aborts on unmount',async()=>{
    let release:(v:unknown)=>void=()=>{},signal:AbortSignal|undefined
    const fetcher=vi.fn((_path:string,init?:RequestInit)=>{signal=init?.signal??undefined;return new Promise(resolve=>{release=resolve})})
    vi.stubGlobal('fetch',fetcher);const lock=vi.fn(),view=render(<AdaptationRuleControls csrfToken="a" disabled={false} revision={1} onStateLockChange={lock}/> )
    fireEvent.click(screen.getByRole('button',{name:'Load rules'}));view.rerender(<AdaptationRuleControls csrfToken="b" disabled={false} revision={2} onStateLockChange={lock}/> )
    expect(signal?.aborted).toBe(true);release(response([signalRule()]));await waitFor(()=>expect(screen.queryByLabelText('Existing rule')).not.toBeInTheDocument())
    view.unmount();expect(lock).toHaveBeenLastCalledWith(false)
  })
})
