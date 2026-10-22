import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { PlansPanel } from './PlansPanel'
import { PlanCalendarControls } from './PlanCalendarControls'
import { plansViewFrom, type AuthoredPlan, type AuthoredPlanRequest, type PlansView } from './PlanClient'

const id='11111111-1111-4111-8111-111111111111', sessionId='22222222-2222-4222-8222-222222222222'
const author={authorKind:'user' as const,authorAttribution:'Synthetic author'}
const definition:AuthoredPlanRequest={title:'Authored training',author,sessions:[{sessionId,author,sport:'run',objective:'Authored objective',
  schedule:{kind:'date_only',date:'2026-10-20',startsLocal:null,endsLocal:null,timeZone:'UTC',startOffset:null,endOffset:null},
  targets:[],notes:null,permissions:{move:null,reduceDuration:null,omit:false},omitted:false}],policy:null}
const plan:AuthoredPlan={reference:{planId:id,revision:1},previousRevision:null,definition,recordedBy:'authenticated_user',recordedUtc:'2026-10-15T12:00:00Z'}
const empty:PlansView={plans:[],selection:{generation:0,selected:null}}
const response=(body:unknown,status=200)=>({ok:status>=200 && status<300,status,json:async()=>body})

describe('authored plan entry and selection',()=>{
  it('keeps entry and draft overlays inert until one explicit save without automatically selecting',async()=>{
    let state=empty
    const fetcher=vi.fn(async(path:string,init?:RequestInit)=>{
      if(path==='/api/plans' && init?.method==='POST') {
        const saved={...plan,definition:JSON.parse(String(init.body)) as AuthoredPlanRequest}
        state={plans:[saved],selection:empty.selection};return response(saved,201)
      }
      if(path==='/api/plans') return response(state)
      throw new Error('Unexpected synthetic request')
    })
    vi.stubGlobal('fetch',fetcher);render(<PlansPanel csrfToken="csrf"/> )
    await waitFor(()=>expect(screen.getByRole('button',{name:'Enter authored plan'})).toBeEnabled())
    fireEvent.click(screen.getByRole('button',{name:'Enter authored plan'}))
    fireEvent.change(screen.getByLabelText('Plan title'),{target:{value:'Entered once'}})
    fireEvent.change(within(screen.getByRole('group',{name:'Plan authorship'})).getByLabelText('Author attribution'),{target:{value:'Synthetic author'}})
    fireEvent.click(screen.getByRole('button',{name:'Add authored session'}))
    const session=within(screen.getByRole('group',{name:'Authored session 1'}))
    expect(session.getByRole('checkbox',{name:'Permit omission with audit history'})).not.toBeChecked()
    fireEvent.change(session.getByLabelText('Sport'),{target:{value:'run'}})
    fireEvent.change(session.getByLabelText('Session objective'),{target:{value:'Entered objective'}})
    fireEvent.change(session.getByLabelText('Session timezone'),{target:{value:'UTC'}})
    fireEvent.change(session.getByLabelText('Planned local date'),{target:{value:'2026-10-20'}})
    expect(screen.getByLabelText('Unsaved plan draft overlay')).toHaveTextContent('not authoritative')
    expect(fetcher.mock.calls).toHaveLength(1)
    fireEvent.click(screen.getByRole('button',{name:'Save authored revision'}))
    expect(await screen.findByRole('status')).toHaveTextContent('not selected automatically')
    const post=fetcher.mock.calls.find(([,init])=>init?.method==='POST')
    expect(post?.[1]).toMatchObject({credentials:'same-origin',headers:{'X-CSRF-Token':'csrf'}})
    expect(JSON.parse(String(post?.[1]?.body))).toMatchObject({title:'Entered once',policy:null,sessions:[{permissions:{move:null,reduceDuration:null,omit:false}}]})
    expect(fetcher.mock.calls.filter(([,init])=>init?.method==='PUT')).toHaveLength(0)
  })

  it('edits an existing stable session under exact plan and selection revisions',async()=>{
    let state:PlansView={plans:[plan],selection:{generation:3,selected:plan.reference}}
    const fetcher=vi.fn(async(path:string,init?:RequestInit)=>{
      if(init?.method==='PUT') {
        expect(path).toBe(`/api/plans/${id}`)
        const body=JSON.parse(String(init.body)) as {expectedRevision:number;expectedSelectionGeneration:number;definition:AuthoredPlanRequest}
        expect(body).toMatchObject({expectedRevision:1,expectedSelectionGeneration:3,definition:{sessions:[{sessionId}]}})
        const saved={...plan,reference:{planId:id,revision:2},previousRevision:1,definition:body.definition}
        state={plans:[saved],selection:{generation:4,selected:saved.reference}};return response(saved)
      }
      return response(state)
    })
    vi.stubGlobal('fetch',fetcher);render(<PlansPanel csrfToken="csrf"/> )
    fireEvent.click(await screen.findByRole('button',{name:'Edit authored revision'}))
    expect(screen.getByLabelText('Session objective')).toHaveValue('Authored objective')
    expect(screen.getByRole('checkbox',{name:'Configure adaptation horizon and import grace'})).not.toBeChecked()
    fireEvent.change(screen.getByLabelText('Session objective'),{target:{value:'Explicit correction'}})
    expect(screen.getByRole('button',{name:'Deselect base plan'})).toBeDisabled()
    fireEvent.click(screen.getByRole('button',{name:'Save authored revision'}))
    expect(await screen.findByRole('status')).toHaveTextContent('revision 2 saved')
    expect(screen.queryByLabelText('Unsaved authored plan')).not.toBeInTheDocument()
    expect(fetcher.mock.calls.filter(([,init])=>init?.method==='PUT')).toHaveLength(1)
  })

  it('selects a base explicitly without creating or changing an authored revision',async()=>{
    let state:PlansView={plans:[plan],selection:empty.selection}
    const fetcher=vi.fn(async(path:string,init?:RequestInit)=>{
      if(path==='/api/plans/selection' && init?.method==='PUT') {
        expect(JSON.parse(String(init.body))).toEqual({expectedGeneration:0,selected:plan.reference})
        state={...state,selection:{generation:1,selected:plan.reference}};return response(state.selection)
      }
      return response(state)
    })
    vi.stubGlobal('fetch',fetcher);render(<PlansPanel csrfToken="csrf"/> )
    fireEvent.click(await screen.findByRole('button',{name:'Select this exact base revision'}))
    expect(await screen.findByRole('status')).toHaveTextContent('No amendment permission, inference or transfer consent')
    expect(fetcher.mock.calls.filter(([,init])=>init?.method==='POST')).toHaveLength(0)
  })

  it.each([409,503])('keeps an unsaved revision on %s without automatic retry',async status=>{
    const fetcher=vi.fn(async(_path:string,init?:RequestInit)=>init?.method==='PUT' ? response({},status) : response({plans:[plan],selection:empty.selection}))
    vi.stubGlobal('fetch',fetcher);render(<PlansPanel csrfToken="csrf"/> )
    fireEvent.click(await screen.findByRole('button',{name:'Edit authored revision'}))
    fireEvent.click(screen.getByRole('button',{name:'Save authored revision'}))
    expect(await screen.findByRole('alert')).toHaveTextContent(status===409 ? 'revision changed' : 'outcome unknown')
    expect(screen.getByLabelText('Session objective')).toHaveValue('Authored objective')
    expect(fetcher.mock.calls.filter(([,init])=>init?.method==='PUT')).toHaveLength(1)
    if(status===503) expect(screen.getByRole('button',{name:'Save authored revision'})).toBeDisabled()
  })

  it('does not retry an acknowledged create when authoritative GET fails',async()=>{
    let gets=0,posts=0
    let created:AuthoredPlan|undefined
    const fetcher=vi.fn(async(_path:string,init?:RequestInit)=>{
      if(init?.method==='POST'){
        posts++;created={...plan,definition:JSON.parse(String(init.body)) as AuthoredPlanRequest};return response(created,201)
      }
      gets++
      return gets===1 ? response(empty) : gets===2 ? response({},503) : response({plans:[created],selection:empty.selection})
    })
    vi.stubGlobal('fetch',fetcher);render(<PlansPanel csrfToken="csrf"/> )
    await waitFor(()=>expect(screen.getByRole('button',{name:'Enter authored plan'})).toBeEnabled())
    fireEvent.click(screen.getByRole('button',{name:'Enter authored plan'}))
    fireEvent.change(screen.getByLabelText('Plan title'),{target:{value:'Acknowledged'}})
    fireEvent.change(screen.getByLabelText('Author attribution'),{target:{value:'Synthetic author'}})
    fireEvent.click(screen.getByRole('button',{name:'Save authored revision'}))
    expect(await screen.findByRole('alert')).toHaveTextContent('outcome unknown')
    expect(screen.getByRole('button',{name:'Save authored revision'})).toBeDisabled()
    fireEvent.click(screen.getByRole('button',{name:'Save authored revision'}))
    expect(posts).toBe(1)
    expect(gets).toBe(2)
    fireEvent.click(screen.getByRole('button',{name:'Reload plans and discard unsaved drafts'}))
    await waitFor(()=>expect(screen.queryByLabelText('Unsaved authored plan')).not.toBeInTheDocument())
    expect(posts).toBe(1)
  })

  it.each(['Z','+01:00'])('checks explicit UTC offsets against saved offset %s',async offset=>{
    const utcDefinition={...definition,sessions:[{...definition.sessions[0],schedule:{kind:'timed' as const,date:null,startsLocal:'2026-10-20T09:00',endsLocal:'2026-10-20T10:00',timeZone:'UTC',startOffset:'+00:00',endOffset:'+00:00'}}]}
    const initial={...plan,definition:utcDefinition}
      let saved:AuthoredPlan|undefined
      const fetcher=vi.fn(async(_path:string,init?:RequestInit)=>{
        if(init?.method==='PUT') {
          const input=JSON.parse(String(init.body)) as {definition:AuthoredPlanRequest}
          saved={...initial,reference:{planId:id,revision:2},previousRevision:1,definition:{...input.definition,sessions:[{...input.definition.sessions[0],schedule:{...input.definition.sessions[0].schedule,startOffset:offset,endOffset:'Z'}}]}}
          return response(saved)
        }
        return response({plans:saved?[saved]:[initial],selection:empty.selection})
      })
      vi.stubGlobal('fetch',fetcher);render(<PlansPanel csrfToken="csrf"/> )
      fireEvent.click(await screen.findByRole('button',{name:'Edit authored revision'}))
      fireEvent.change(screen.getByLabelText('Session objective'),{target:{value:'UTC objective'}})
      fireEvent.click(screen.getByRole('button',{name:'Save authored revision'}))
      if(offset==='Z') expect(await screen.findByRole('status')).toHaveTextContent('revision 2 saved')
      else expect(await screen.findByRole('alert')).toHaveTextContent('outcome unknown')
      expect(fetcher.mock.calls.filter(([,init])=>init?.method==='PUT')).toHaveLength(1)
  })

  it('rejects malformed nested plan records before opening an editor',()=>{
    expect(()=>plansViewFrom({plans:[{...plan,definition:{...definition,sessions:[null]}}],selection:empty.selection})).toThrow()
    expect(()=>plansViewFrom({plans:[plan],selection:{generation:0,selected:{planId:id,revision:2}}})).toThrow()
  })

  it('locks plan edits matching and navigation for a rule draft but keeps authoritative rule recovery available',async()=>{
    const fetcher=vi.fn(async(path:string,_init?:RequestInit)=>response(path==='/api/plans'?{plans:[plan],selection:{generation:1,selected:plan.reference}}:[]))
    const lock=vi.fn();vi.stubGlobal('fetch',fetcher);render(<PlansPanel csrfToken="csrf" onNavigationLockChange={lock}/> )
    await screen.findByRole('button',{name:'Deselect base plan'})
    fireEvent.click(screen.getByRole('button',{name:'Load rules'}));await screen.findByText(/Authoritative rules loaded/)
    fireEvent.change(screen.getByLabelText('Time zone'),{target:{value:'UTC'}})
    expect(lock).toHaveBeenLastCalledWith(true)
    expect(screen.getByRole('button',{name:'Deselect base plan'})).toBeDisabled()
    expect(screen.getByRole('button',{name:'Edit authored revision'})).toBeDisabled()
    expect(screen.getByRole('button',{name:'Reload plans and discard unsaved drafts'})).toBeDisabled()
    expect(screen.getByRole('button',{name:'Inspect reconciliation'})).toBeDisabled()
    expect(screen.getByRole('button',{name:'Load rules'})).toBeEnabled()
    fireEvent.click(screen.getByRole('button',{name:'Load rules'}))
    await waitFor(()=>expect(lock).toHaveBeenLastCalledWith(false))
    expect(screen.getByRole('button',{name:'Deselect base plan'})).toBeEnabled()
    expect(fetcher.mock.calls.every(([,init])=>init?.method!=='POST')).toBe(true)
  })
})

describe('local plan/calendar distinction',()=>{
  const calendar={asOf:'2026-10-15T12:00:00Z',from:'2026-10-15',until:'2026-10-20',timeZone:'UTC',selection:{generation:1,selected:plan.reference},
    items:[{plan:plan.reference,sessionId,kind:'authored_session',schedule:definition.sessions[0].schedule,sport:'run',objective:'Authored objective',omitted:false,amendmentEligible:false}],
    limitation:'authored_sessions_not_completed_activities_or_commitments'}
  const ref={commitmentId:'33333333-3333-4333-8333-333333333333',revision:1,intakeId:'44444444-4444-4444-8444-444444444444',intakeRevision:1,contextId:null,contextRevision:null}
  const commitments={oldest:calendar.from,newest:calendar.until,timeZone:'UTC',occurrences:[],needsReconfirmation:[ref],constraintsBlocked:true,coverage:'confirmed_local_only_external_unavailable'}
  it('loads only local GET projections and separates recorded activity, stale constraints and unsaved drafts',async()=>{
    const fetcher=vi.fn(async(path:string,_init?:RequestInit)=>response(path.startsWith('/api/plans/calendar') ? calendar :
      path.startsWith('/api/commitments/calendar') ? commitments : {activities:[{source:'synthetic',sourceRecordId:'recorded-1',sport:'run',startLocal:'2026-10-15T09:00:00',startInstant:null,timeContext:'source_local'}],wellness:[]}))
    vi.stubGlobal('fetch',fetcher);render(<PlanCalendarControls csrfToken="csrf" draft={definition}/>)
    expect(fetcher).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('Calendar from'),{target:{value:calendar.from}})
    fireEvent.change(screen.getByLabelText('Calendar through'),{target:{value:calendar.until}})
    fireEvent.change(screen.getByLabelText('Calendar timezone'),{target:{value:'UTC'}})
    fireEvent.click(screen.getByRole('button',{name:'Inspect local calendar'}))
    expect(await screen.findByText(/1 stale item.*constraint coverage blocked/)).toBeInTheDocument()
    expect(screen.getByText(/Recorded activity:.*instant unverified/)).toBeInTheDocument()
    expect(screen.getByText(/Authored session:.*not eligible/)).toBeInTheDocument()
    expect(screen.getByLabelText('Unsaved plan draft overlay')).toHaveTextContent('Draft only')
    expect(fetcher.mock.calls).toHaveLength(3)
    expect(fetcher.mock.calls.every(([,init])=>init?.method==='GET')).toBe(true)
  })
  it('refuses oversized calendar ranges before any read',async()=>{
    const fetcher=vi.fn();vi.stubGlobal('fetch',fetcher)
    render(<PlanCalendarControls csrfToken="csrf" draft={null}/>)
    fireEvent.change(screen.getByLabelText('Calendar from'),{target:{value:'2026-01-01'}})
    fireEvent.change(screen.getByLabelText('Calendar through'),{target:{value:'2027-01-02'}})
    fireEvent.change(screen.getByLabelText('Calendar timezone'),{target:{value:'UTC'}})
    fireEvent.click(screen.getByRole('button',{name:'Inspect local calendar'}))
    expect(await screen.findByRole('alert')).toHaveTextContent('1–366 real calendar dates')
    expect(fetcher).not.toHaveBeenCalled()
  })
})
