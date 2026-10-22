import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { parseSnapshot, loadEvidenceRecords, prepareEvidence, type EvidenceSnapshot } from './ScopedEvidenceClient'
import { ScopedEvidenceInspection } from './ScopedEvidenceInspection'
import { ScopedReviewControls } from './ScopedReviewControls'
import { activityReport, activityRequest, options, asOf, id } from './scoped-review.test-fixtures'

afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.restoreAllMocks()})
const snapshotId='11111111-1111-4111-8111-111111111111'
function snapshot():EvidenceSnapshot {
  const report=activityReport()
  return {snapshotId,createdUtc:asOf,packetSha256:'a'.repeat(64),state:'PREPARED',
    packet:{profile:'scoped-descriptive-v1',admission:'closed_not_qualified_not_execution_bound'},
    admission:'closed_not_qualified_not_execution_bound',manifest:{scope:report.scope as EvidenceSnapshot['manifest']['scope'],
      included:[`session/${id}`],displayOnly:[],excluded:['optional_wellness'],missing:['trainingReadiness','precedingSleep','activityStreams'],
      needsConfirmation:[],dependencies:[],omitted:{wellnessObservationDetails:0,optionalContexts:0,policy:'category_atomic_optional_detail_only'},
      blockedReasons:[],packetBytes:1024,baselines:[],displayOnlyRecordCount:0,detailPolicy:'bounded_manifest_candidates_in_authenticated_record_pages'}}
}
const response=(value:unknown,status=200)=>({ok:status>=200&&status<300,status,json:async()=>value})

describe('scoped evidence preparation and inspection',()=>{
  it('accepts the backend snapshotId/string omission policy and refuses malformed state or dates',()=>{
    const value=snapshot()
    expect(parseSnapshot(value)).toEqual(value)
    expect(()=>parseSnapshot({...value,snapshotId:undefined,id:snapshotId})).toThrow()
    expect(()=>parseSnapshot({...value,createdUtc:'2026-02-30T12:00:00Z'})).toThrow()
    expect(()=>parseSnapshot({...value,manifest:{...value.manifest,omitted:{...value.manifest.omitted,policy:0}}})).toThrow()
    expect(()=>parseSnapshot({...value,state:'BLOCKED'})).toThrow()
    expect(()=>parseSnapshot({...value,packet:null})).toThrow()
    expect(()=>parseSnapshot({...value,manifest:{...value.manifest,displayOnlyRecordCount:-1}})).toThrow()
    expect(()=>parseSnapshot({...value,manifest:{...value.manifest,displayOnly:['id'],displayOnlyRecordCount:0}})).toThrow()
    expect(()=>parseSnapshot({...value,manifest:{...value.manifest,detailPolicy:'unbounded'}})).toThrow()
  })

  it('posts only an explicit bounded schema-2 envelope without inference or model selection',async()=>{
    const fetcher=vi.fn(async()=>response({report:activityReport(),snapshot:snapshot()}))
    vi.stubGlobal('fetch',fetcher)
    const signal=new AbortController().signal
    const prepared=await prepareEvidence(activityRequest,'question','What was recorded?','Synthetic attributed note','Athlete','csrf','Europe/Prague',signal)
    expect(prepared.snapshot.snapshotId).toBe(snapshotId)
    expect(fetcher).toHaveBeenCalledTimes(1)
    const [path,init]=fetcher.mock.calls[0] as unknown as [string,RequestInit]
    expect(path).toBe('/api/reviews/prepare')
    expect(init).toMatchObject({method:'POST',cache:'no-store',credentials:'same-origin',signal,headers:{'X-CSRF-Token':'csrf'}})
    expect(JSON.parse(String(init.body))).toEqual({schemaVersion:2,selection:activityRequest,purpose:'question',question:'What was recorded?',
      additionalText:{content:'Synthetic attributed note',authorAttribution:'Athlete'}})
    await expect(prepareEvidence(activityRequest,'question','','','','csrf','Europe/Prague',signal)).rejects.toThrow()
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('inspects local records and changed status without presenting changed source values as original',async()=>{
    const fetcher=vi.fn(async(path:string)=>path.includes('/records') ? response({snapshotId,items:[{category:'activities',source:'intervals.icu',sourceRecordId:'record',
      importedRevision:1,status:'changed_since_snapshot',activity:null,wellness:null,roles:['assessment_subject']}],nextOffset:null,limitation:'changed records are not replayed'}) : response(snapshot()))
    vi.stubGlobal('fetch',fetcher)
    render(<ScopedEvidenceInspection snapshot={snapshot()} onSnapshot={vi.fn()}/> )
    expect(fetcher).not.toHaveBeenCalled()
    expect(screen.getByRole('heading',{name:'Included'})).toBeInTheDocument()
    expect(screen.getByText('trainingReadiness')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button',{name:'Read snapshot records'}))
    expect(await screen.findByText(/changed_since_snapshot/)).toBeInTheDocument()
    expect(fetcher.mock.calls[0][0]).toBe(`/api/reviews/${snapshotId}/records?offset=0&limit=25`)
    expect(fetcher.mock.calls.every(call=>!String(call[0]).includes('sync')&&!String(call[0]).includes('model'))).toBe(true)
    vi.stubGlobal('fetch',vi.fn(async()=>response({snapshotId,items:[{category:'activities',source:'intervals.icu',sourceRecordId:'record',importedRevision:1,
      status:'changed_since_snapshot',activity:{private:'not the original'},wellness:null,roles:['assessment_subject']}],nextOffset:null,limitation:'fixture'})))
    await expect(loadEvidenceRecords(snapshotId,0,new AbortController().signal)).rejects.toThrow()
  })

  it('keeps ordinary facts separate and blocks snapshot retry after an uncertain creation',async()=>{
    let snapshots=0
    const fetcher=vi.fn(async(path:string,init?:RequestInit)=>{
      if(path==='/api/reviews/options') return response(options)
      if(path==='/api/reviews/prepare') {
        const request=JSON.parse(String(init?.body)) as {schemaVersion:number}
        if(request.schemaVersion===2) {snapshots++;return response({},503)}
        return response(activityReport(request as typeof activityRequest))
      }
      throw Error('Unexpected endpoint')
    })
    vi.stubGlobal('fetch',fetcher)
    render(<ScopedReviewControls csrfToken="csrf"/> )
    await waitFor(()=>expect(screen.getByLabelText('Review mode')).toBeEnabled())
    fireEvent.change(screen.getByLabelText('Review mode'),{target:{value:'activities'}})
    fireEvent.click(screen.getByRole('checkbox',{name:/2026-03-29T09:00/}))
    fireEvent.click(screen.getByRole('button',{name:'Request factual report'}))
    expect(await screen.findByText('Factual report verified. No inference was requested.')).toBeInTheDocument()
    expect(snapshots).toBe(0)
    fireEvent.click(screen.getByRole('button',{name:'Prepare evidence snapshot'}))
    expect(await screen.findByText(/Snapshot preparation outcome unknown/)).toBeInTheDocument()
    expect(screen.getByRole('button',{name:'Prepare evidence snapshot'})).toBeDisabled()
    fireEvent.click(screen.getByRole('button',{name:'Prepare evidence snapshot'}))
    expect(snapshots).toBe(1)
    expect(screen.queryByLabelText('Evidence snapshot inspection')).not.toBeInTheDocument()
  })

  it('clears cached packet and record views when read-only inspection finds a purged snapshot',async()=>{
    vi.stubGlobal('fetch',vi.fn(async()=>response({},404)))
    const removed=vi.fn()
    render(<ScopedEvidenceInspection snapshot={snapshot()} onSnapshot={vi.fn()} onRemoved={removed}/> )
    fireEvent.click(screen.getByRole('button',{name:'Refresh snapshot status (read only)'}))
    expect(await screen.findByText(/Cached packet and record values are not displayed/)).toBeInTheDocument()
    expect(removed).toHaveBeenCalledTimes(1)
    expect(screen.queryByText(snapshotId)).not.toBeInTheDocument()
  })

  it('distinguishes requested unverified baselines from no baseline or configured signal rules',()=>{
    const value=snapshot()
    value.manifest.baselines=[{identity:{field:'hrv',unit:'ms',intermediary:'intervals.icu'},status:'not_computed',value:null,
      reasons:['semantics_unverified'],configuration:{evaluationDate:'2026-03-30',trailingDays:7,minimumSamples:4,minimumCoverage:0.5,maximumAgeDays:2}}]
    render(<ScopedEvidenceInspection snapshot={parseSnapshot(value)} onSnapshot={vi.fn()}/> )
    expect(screen.getByText(/Baseline not computed: measurement semantics are unverified/)).toBeInTheDocument()
    expect(screen.getByText(/Rule policy: not configured/)).toBeInTheDocument()
    expect(screen.queryByText('No baseline requested.')).not.toBeInTheDocument()
    expect(screen.getByText(/excluding evaluation date/)).toBeInTheDocument()
    expect(screen.getByText(/Blocked snapshots have no admitted packet/)).toBeInTheDocument()
  })

  it('discloses display-only counts beyond the bounded manifest identity preview',()=>{
    const value=snapshot()
    value.manifest.displayOnly=Array.from({length:200},(_,i)=>`wellness_record/${i}`)
    value.manifest.displayOnlyRecordCount=500
    render(<ScopedEvidenceInspection snapshot={parseSnapshot(value)} onSnapshot={vi.fn()}/> )
    expect(screen.getByText('500 items')).toBeInTheDocument()
    expect(screen.getByText(/captured source identities and roles can be paged/)).toBeInTheDocument()
  })
})
