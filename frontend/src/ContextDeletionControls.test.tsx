import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ContextDeletionControls } from './ContextDeletionControls'
import type { Commitment } from './ContextIntakeClient'

const csrf = 'a'.repeat(43)
const target = '00000000-0000-0000-0000-000000000001'
const sourceId = '00000000-0000-0000-0000-000000000002'
const survivorId = '00000000-0000-0000-0000-000000000003'
const digest = 'd'.repeat(64)
const intake = { intakeId: sourceId, revision: 2, content: 'Original source wording', sourceCategory: 'coach_guidance',
  authorAttribution: 'Coach reported by athlete', observedOn: '2025-01-05', enteredBy: 'authenticated_user', recordedUtc: '2025-01-05T10:00:00Z' }
const record = { contextId: survivorId, revision: 3, category: 'goal', sourceCategory: 'coach_guidance', authorAttribution: 'Coach reported by athlete',
  enteredBy: 'authenticated_user', observedOn: '2025-01-05', applicableFrom: '2025-01-01', applicableUntil: null, sport: 'Run', activityId: null,
  reviewId: null, content: 'Surviving goal wording', retired: false, restrictionKind: null, restrictionValue: null, restrictionUnit: null }
const preview = () => ({ contextId: target, expectedRevision: 4, digest, sources: [intake],
  survivors: [{ context: record, lifecycle: { resolution: 'resolved', reviewDueOn: '2025-02-01' }, state: 'current', needsReview: false, sourceChanged: false, reasonFlags: [] }],
  sourceLinks: [{ contextId: survivorId, contextRevision: 3, intakeId: sourceId, intakeRevision: 1 }], commitments: [] as (Commitment & { eventId: string | null })[],
  deletedContextIds: [target], snapshotIds: [], executionIds: [], proposalIds: [] as string[], retainedApprovalOperationIds: [] as string[], receiptCount: 0, limitation: 'Only declared dependencies are included.' })
const success = { deletedContextIds: [target], survivingContextIds: [survivorId], retainedCommitmentIds: [], deletedCommitmentIds: [],
   notice: 'active_dependency_histories_removed;encrypted_backups_follow_retention;not_forensic_erasure' }

describe('context deletion controls', () => {
  it('previews without mutation, prepopulates survivors, and uses current source revision on approval', async () => {
    let postBody: Record<string, unknown> | null = null
    const fetchMock = vi.fn(async (path: string, init?: RequestInit) => {
      if (path === `/api/contexts/${target}/deletion-preview`) return { ok: true, status: 200, json: async () => preview() }
      if (path === `/api/contexts/${target}/redact-and-delete`) {
        postBody = JSON.parse(String(init?.body)) as Record<string, unknown>
        return { ok: true, status: 200, json: async () => success }
      }
      throw new Error(`Unexpected request ${path}`)
    })
    const deleted = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    render(<ContextDeletionControls contextId={target} csrfToken={csrf} onDeleted={deleted} onCancel={vi.fn()} />)
    expect(await screen.findByText(/Current origin .* at source revision 2/)).toBeInTheDocument()
    expect(screen.getByLabelText('Context text')).toHaveValue('Surviving goal wording')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBe('GET')
    expect(screen.getByText('Original source wording')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: /I reviewed this surviving context/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /I explicitly approve redacting/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Approve redaction and delete' }))
    await waitFor(() => expect(deleted).toHaveBeenCalledOnce())
    expect(postBody).toMatchObject({ expectedRevision: 4, previewDigest: digest,
      sources: [{ intakeId: sourceId, expectedRevision: 2, note: { content: 'Original source wording', authorAttribution: 'Coach reported by athlete', sourceCategory: 'coach_guidance' } }],
      survivors: [{ intakeId: sourceId, item: { expectedContextRevision: 3, contextId: survivorId, lifecycle: { resolution: 'resolved', reviewDueOn: '2025-02-01' } } }] })
    expect(fetchMock.mock.calls[1]?.[1]?.method).toBe('POST')
    expect(fetchMock.mock.calls[1]?.[1]?.headers).toMatchObject({ 'X-CSRF-Token': csrf })
  })

  it('requires every survivor approval and explicit notice attestation', async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => preview() }))
    vi.stubGlobal('fetch', fetchMock)
    render(<ContextDeletionControls contextId={target} csrfToken={csrf} onDeleted={vi.fn()} onCancel={vi.fn()} />)
    await screen.findByLabelText('Context text')
    fireEvent.click(screen.getByRole('button', { name: 'Approve redaction and delete' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Review and approve every surviving context.')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('requires an explicit calendar association decision for a deleted context', async () => {
    const data = preview()
    data.commitments = [{ reference: { commitmentId: '00000000-0000-0000-0000-000000000004', revision: 1, intakeId: sourceId,
      intakeRevision: 1, contextId: target, contextRevision: 4 }, definition: { label: 'Run', kind: 'commitment', timeZone: 'UTC',
      startsLocal: '2025-01-05T09:00', endsLocal: '2025-01-05T10:00', recurrence: null, exceptions: [] }, needsReconfirmation: false,
      sourceChanged: false, contextChanged: false, authorAttribution: 'Coach', eventId: null }]
    const fetchMock = vi.fn(async (path: string, init?: RequestInit) => path.endsWith('deletion-preview') ?
      ({ ok: true, status: 200, json: async () => data }) : ({ ok: true, status: 200, json: async () => success }))
    vi.stubGlobal('fetch', fetchMock)
    render(<ContextDeletionControls contextId={target} csrfToken={csrf} onDeleted={vi.fn()} onCancel={vi.fn()} />)
    await screen.findByLabelText('Associate with surviving context')
    fireEvent.click(screen.getByRole('checkbox', { name: /I reviewed this surviving context/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /I explicitly approve redacting/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Approve redaction and delete' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Choose a surviving context or explicitly detach')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('fails closed on malformed duplicate preview IDs and never offers approval', async () => {
    const data = preview(); data.deletedContextIds = [target, target]
    const fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => data }))
    vi.stubGlobal('fetch', fetchMock)
    render(<ContextDeletionControls contextId={target} csrfToken={csrf} onDeleted={vi.fn()} onCancel={vi.fn()} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Deletion preview unavailable or malformed')
    expect(screen.queryByRole('button', { name: 'Approve redaction and delete' })).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('discloses proposal removal before approval and rejects duplicate proposal identities', async () => {
    const data=preview();data.proposalIds=[sourceId];data.retainedApprovalOperationIds=[target]
    const fetcher=vi.fn(async()=>({ok:true,status:200,json:async()=>data}))
    vi.stubGlobal('fetch',fetcher)
    const view=render(<ContextDeletionControls contextId={target} csrfToken={csrf} onDeleted={vi.fn()} onCancel={vi.fn()} />)
    expect(await screen.findByText(/amendment proposals: 1/)).toBeInTheDocument()
    expect(screen.getByText(/Retained content-free approval audit IDs: 1/)).toBeInTheDocument()
    expect(fetcher).toHaveBeenCalledTimes(1)
    view.unmount()
    data.proposalIds=[sourceId,sourceId]
    render(<ContextDeletionControls contextId={target} csrfToken={csrf} onDeleted={vi.fn()} onCancel={vi.fn()} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Deletion preview unavailable or malformed')
    expect(screen.queryByRole('button',{name:'Approve redaction and delete'})).not.toBeInTheDocument()
  })

  it('blocks retry after an unknown mutation outcome', async () => {
    const fetchMock = vi.fn(async (path: string) => {
      if (path.endsWith('deletion-preview')) return { ok: true, status: 200, json: async () => preview() }
      return { ok: false, status: 500, json: async () => ({}) }
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<ContextDeletionControls contextId={target} csrfToken={csrf} onDeleted={vi.fn()} onCancel={vi.fn()} />)
    await screen.findByLabelText('Context text')
    fireEvent.click(screen.getByRole('checkbox', { name: /I reviewed this surviving context/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /I explicitly approve redacting/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Approve redaction and delete' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Outcome unknown or response invalid')
    expect(screen.getByRole('button', { name: 'Approve redaction and delete' })).toBeDisabled()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('reconfirms the latest source and retains an explicit event association without copying it', async () => {
    const data=preview(), eventId='00000000-0000-0000-0000-000000000005'
    data.sources[0]={ ...intake,authorAttribution:'Corrected source author' }
    data.commitments=[{ reference:{commitmentId:'00000000-0000-0000-0000-000000000004',revision:1,intakeId:sourceId,
      intakeRevision:1,contextId:target,contextRevision:4},definition:{label:'Independent calendar label',kind:'commitment',timeZone:'UTC',
      startsLocal:'2025-01-05T09:00',endsLocal:'2025-01-05T10:00',recurrence:null,exceptions:[]},eventId,
      authorAttribution:'Original source author',needsReconfirmation:true,sourceChanged:true,contextChanged:false }]
    const fetcher=vi.fn(async (_path:string,init?:RequestInit) => init?.method==='POST' ?
      {ok:true,status:200,json:async()=>({...success,retainedCommitmentIds:[data.commitments[0].reference.commitmentId]})} :
      {ok:true,status:200,json:async()=>data})
    vi.stubGlobal('fetch',fetcher)
    const deleted=vi.fn()
    render(<ContextDeletionControls contextId={target} csrfToken={csrf} onDeleted={deleted} onCancel={vi.fn()} />)
    await screen.findByLabelText('Associate with surviving context')
    fireEvent.change(screen.getByLabelText('Associate with surviving context'),{target:{value:survivorId}})
    fireEvent.click(screen.getByRole('checkbox',{name:/I reviewed this surviving context/}))
    fireEvent.click(screen.getByRole('checkbox',{name:/I explicitly approve redacting/}))
    fireEvent.click(screen.getByRole('button',{name:'Approve redaction and delete'}))
    await waitFor(()=>expect(deleted).toHaveBeenCalledOnce())
    const input=JSON.parse(String(fetcher.mock.calls[1]?.[1]?.body)) as Record<string,unknown>
    expect(input).toMatchObject({survivors:[{item:{context:{authorAttribution:'Corrected source author'}}}],
      commitments:[{confirmation:{expectedIntakeRevision:3,contextId:survivorId,expectedContextRevision:4,eventId}}]})
  })

  it('requires renewed approval after editing replacement wording', async () => {
    const fetcher=vi.fn(async (_path:string,_init?:RequestInit)=>({ok:true,status:200,json:async()=>preview()}))
    vi.stubGlobal('fetch',fetcher)
    render(<ContextDeletionControls contextId={target} csrfToken={csrf} onDeleted={vi.fn()} onCancel={vi.fn()} />)
    await screen.findByLabelText('Context text')
    fireEvent.click(screen.getByRole('checkbox',{name:/I reviewed this surviving context/}))
    fireEvent.click(screen.getByRole('checkbox',{name:/I explicitly approve redacting/}))
    fireEvent.change(screen.getByLabelText('Approved replacement wording'),{target:{value:'Approved clean source'}})
    expect(screen.getByRole('checkbox',{name:/I reviewed this surviving context/})).not.toBeChecked()
    expect(screen.getByRole('checkbox',{name:/I explicitly approve redacting/})).not.toBeChecked()
    fireEvent.click(screen.getByRole('button',{name:'Approve redaction and delete'}))
    expect(await screen.findByRole('alert')).toHaveTextContent('Review and approve every surviving context')
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
})
